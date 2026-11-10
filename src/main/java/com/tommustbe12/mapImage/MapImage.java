package com.tommustbe12.mapImage;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.bukkit.map.MapRenderer;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class MapImage extends JavaPlugin implements CommandExecutor, Listener {

    private FileConfiguration config;
    private final Queue<Runnable> mapQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, MapData> pendingMaps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingChatInput = new ConcurrentHashMap<>();

    private int xpCostLevels;
    private boolean requireEmptyMap;
    private int maxMaps;

    private static final int TILE_SIZE = 128;
    private static final int MAX_DIMENSION = 512;
    private static final int MAPS_PER_TICK = 2;

    // --- Restart persistence: the rendered palette bytes are saved in config
    // (Base64) so a restart never needs the network. Entries without saved
    // pixels are migrated once (download -> extract -> persist) and then
    // behave like saved ones.
    private static final int PIXEL_COUNT = TILE_SIZE * TILE_SIZE;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_DELAY_TICKS = 60L; // 3s
    private static final int MAX_COLOR_CACHE_RETRIES = 10;
    private static final long COLOR_CACHE_RETRY_DELAY_TICKS = 100L; // 5s; cold cache build takes ~39s
    private static final int MAX_APPLY_RETRIES = 12;
    private static final long APPLY_RETRY_DELAY_TICKS = 100L; // 5s; ~60s total

    // Bookkeeping for one restore run (enable, or /mapimage repair).
    private final AtomicInteger pendingRestore = new AtomicInteger();
    private final AtomicInteger restoredFromPixels = new AtomicInteger();
    private final AtomicInteger migratedFromDownload = new AtomicInteger();
    private final AtomicInteger restoreFailed = new AtomicInteger();
    private final Set<Integer> countedIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Integer> mapRun = new ConcurrentHashMap<>();
    private volatile int currentRun = 0;
    private volatile boolean restoreSummarySent = true;
    private volatile Player restoreNotifyPlayer = null;

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"
    );

    @Override
    public void onEnable() {
        this.getCommand("mapimage").setExecutor(this);
        saveDefaultConfig();
        config = getConfig();

        xpCostLevels = Math.max(0, config.getInt("xp-cost-levels", 5));
        requireEmptyMap = config.getBoolean("require-empty-map", true);
        maxMaps = Math.max(1, config.getInt("max-maps", 64));

        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                for (int i = 0; i < MAPS_PER_TICK; i++) {
                    Runnable job = mapQueue.poll();
                    if (job == null) break;
                    try {
                        job.run();
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "Exception while running queued map job", t);
                    }
                }
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Map queue processor failed", t);
            }
        }, 1L, 1L);

        beginRestoreRun(null);
        restoreSavedMaps();
    }

    @Override
    public void onDisable() {
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.isOp() && !player.hasPermission("mapimage.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("repair")) {
            runRepair(player);
            return true;
        }

        int rows = 1, cols = 1;
        if (args.length >= 2 && args[1].matches("\\d+x\\d+")) {
            String[] parts = args[1].split("x");
            rows = Integer.parseInt(parts[0]);
            cols = Integer.parseInt(parts[1]);
        }
        if (!isValidSize(player, rows, cols)) return true;

        if (args.length < 1) {
            // User-friendly flow: hold an empty map, run /mapimage with no arguments,
            // then enter the image URL in the dialog popup (or in chat as a fallback).
            if (!canCreateNow(player, rows, cols)) return true;
            startUrlInput(player);
            return true;
        }

        if (!canCreateNow(player, rows, cols)) return true;

        startImageProcessing(args[0], rows, cols, player);
        return true;
    }

    /**
     * Starts the interactive URL input: shows a dialog popup (Minecraft 1.21.6+)
     * and also listens for the URL in chat as a fallback for older clients.
     */
    private void startUrlInput(Player player) {
        long timeoutSeconds = Math.max(1L, config.getLong("chat-input-timeout-seconds", 30L));
        long expiry = System.currentTimeMillis() + timeoutSeconds * 1000L;
        pendingChatInput.put(player.getUniqueId(), expiry);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pendingChatInput.remove(player.getUniqueId(), expiry)) {
                player.sendMessage(ChatColor.RED + "Image URL input timed out. Hold an empty map and run /mapimage again.");
            }
        }, timeoutSeconds * 20L);

        player.sendMessage(ChatColor.GREEN + "Enter the image URL in the popup"
                + ChatColor.GRAY + " (or type it in chat within " + timeoutSeconds + "s if you don't see a popup)"
                + ChatColor.GREEN + ":");
        showUrlDialog(player);
    }

    /**
     * Shows a dialog popup with a text field for the image URL and an optional size field.
     * Requires a Paper server with the Dialog API (1.21.7+); falls back to chat input otherwise.
     */
    private void showUrlDialog(Player player) {
        try {
            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(Component.text("Create Image Map", NamedTextColor.GOLD))
                            .body(List.of(
                                    DialogBody.plainMessage(Component.text("Paste an image URL below and click Create.", NamedTextColor.GRAY)),
                                    DialogBody.plainMessage(Component.text("Cost: " + xpCostLevels + " XP level(s) + 1 empty map per tile (2x2 = 4 maps, max " + maxMaps + ")", NamedTextColor.YELLOW))
                            ))
                            .inputs(List.of(
                                    DialogInput.text("url", Component.text("Image URL", NamedTextColor.AQUA))
                                            .width(400).maxLength(2048).build(),
                                    DialogInput.text("size", Component.text("Map size (optional, e.g. 2x3)", NamedTextColor.AQUA))
                                            .width(400).maxLength(16).initial("1x1").build()
                            ))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.create(
                                    Component.text("Create Map", NamedTextColor.GREEN),
                                    Component.text("Create the image map (" + xpCostLevels + " levels + 1 empty map per tile)"),
                                    150,
                                    DialogAction.customClick((view, audience) -> {
                                        if (!(audience instanceof Player p)) return;
                                        String input = view.getText("url");
                                        String size = view.getText("size");
                                        pendingChatInput.remove(p.getUniqueId());
                                        Bukkit.getScheduler().runTask(this, () -> handleInput(p, input, size));
                                    }, ClickCallback.Options.builder()
                                            .uses(1)
                                            .lifetime(Duration.ofMinutes(10))
                                            .build())
                            ),
                            ActionButton.create(
                                    Component.text("Cancel", NamedTextColor.RED),
                                    Component.text("Close this dialog"),
                                    150,
                                    null
                            )
                    )));
            player.showDialog(dialog);
        } catch (Throwable t) {
            // Server/client too old for the Dialog API - the chat input fallback is already active.
            getLogger().log(Level.WARNING, "Could not show dialog popup (requires Paper 1.21.7+ / client 1.21.6+); using chat input instead", t);
        }
    }

    /**
     * Fallback for clients/servers without dialog support: the next chat message
     * from a player who ran /mapimage with no arguments is used as the image URL.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Long expiry = pendingChatInput.get(player.getUniqueId());
        if (expiry == null) return;

        event.setCancelled(true);

        if (System.currentTimeMillis() > expiry) {
            pendingChatInput.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Image URL input timed out. Hold an empty map and run /mapimage again.");
            return;
        }
        pendingChatInput.remove(player.getUniqueId());

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String input = message;
        String size = null;
        String[] split = message.split("\\s+", 2);
        if (split.length == 2) {
            input = split[0];
            size = split[1];
        }
        final String finalInput = input;
        final String finalSize = size;
        Bukkit.getScheduler().runTask(this, () -> handleInput(player, finalInput, finalSize));
    }

    private void handleInput(Player player, String inputRaw, String sizeRaw) {
        if (inputRaw == null || inputRaw.isBlank()) {
            player.sendMessage(ChatColor.RED + "No image URL entered. Hold an empty map and run /mapimage again.");
            return;
        }
        String input = inputRaw.trim();

        int rows = 1, cols = 1;
        if (sizeRaw != null && sizeRaw.trim().matches("\\d+x\\d+")) {
            String[] parts = sizeRaw.trim().split("x");
            rows = Integer.parseInt(parts[0]);
            cols = Integer.parseInt(parts[1]);
        }
        if (!isValidSize(player, rows, cols)) return;

        if (!canCreateNow(player, rows, cols)) return;
        startImageProcessing(input, rows, cols, player);
    }

    /**
     * Validates a requested grid size: each side must be at least 1 and the total
     * number of maps (rows * cols) may not exceed the configured max (default 64).
     */
    private boolean isValidSize(Player player, int rows, int cols) {
        int mapsNeeded = rows * cols;
        if (rows < 1 || cols < 1 || mapsNeeded > maxMaps) {
            player.sendMessage(ChatColor.RED + "Invalid size " + rows + "x" + cols + " (" + mapsNeeded
                    + " maps). Maximum is " + maxMaps + " maps (e.g. 8x8).");
            return false;
        }
        return true;
    }

    /**
     * Up-front check (main thread): the player must hold an empty map, have enough
     * empty maps for the whole grid, and have enough XP. The actual maps/XP are only
     * consumed once the image has loaded successfully.
     */
    private boolean canCreateNow(Player player, int rows, int cols) {
        int mapsNeeded = rows * cols;
        if (requireEmptyMap) {
            if (!isHoldingEmptyMap(player)) {
                player.sendMessage(ChatColor.RED + "Hold an empty map in your hand to create an image map.");
                return false;
            }
            int available = countEmptyMaps(player);
            if (available < mapsNeeded) {
                player.sendMessage(ChatColor.RED + "A " + rows + "x" + cols + " image needs " + mapsNeeded
                        + " empty maps, but you only have " + available + ".");
                return false;
            }
        }
        if (xpCostLevels > 0 && player.getLevel() < xpCostLevels) {
            player.sendMessage(ChatColor.RED + "Creating an image map costs " + xpCostLevels
                    + " XP level(s). You only have " + player.getLevel() + ".");
            return false;
        }
        return true;
    }

    private boolean isHoldingEmptyMap(Player player) {
        PlayerInventory inv = player.getInventory();
        return isEmptyMap(inv.getItemInMainHand()) || isEmptyMap(inv.getItemInOffHand());
    }

    private boolean isEmptyMap(ItemStack item) {
        return item != null && item.getType() == Material.MAP;
    }

    /**
     * Consumes the required number of empty maps (one per tile, e.g. 4 for 2x2) and the
     * XP cost on the main thread, right before the maps are created. Returns false (and
     * charges nothing) if the player no longer has what they need.
     */
    private boolean commitCosts(Player player, int mapsNeeded) {
        if (!player.isOnline()) return false;

        if (requireEmptyMap && !consumeEmptyMaps(player, mapsNeeded)) {
            player.sendMessage(ChatColor.RED + "You no longer have " + mapsNeeded
                    + " empty map(s). Creation cancelled, nothing was charged.");
            return false;
        }

        if (xpCostLevels > 0) {
            if (player.getLevel() < xpCostLevels) {
                player.sendMessage(ChatColor.RED + "You no longer have enough XP levels (need " + xpCostLevels
                        + "). Creation cancelled, nothing was charged.");
                return false;
            }
            player.giveExpLevels(-xpCostLevels);
            player.sendMessage(ChatColor.GOLD + "Charged " + xpCostLevels + " XP level(s) for the image map.");
        }
        return true;
    }

    private int countEmptyMaps(Player player) {
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getStorageContents()) count += isEmptyMap(item) ? item.getAmount() : 0;
        for (ItemStack item : inv.getArmorContents()) count += isEmptyMap(item) ? item.getAmount() : 0;
        for (ItemStack item : inv.getExtraContents()) count += isEmptyMap(item) ? item.getAmount() : 0;
        return count;
    }

    /**
     * Removes {@code count} empty maps, preferring the hand (main, then off) before the
     * rest of the inventory. Returns false if the player doesn't have enough.
     */
    private boolean consumeEmptyMaps(Player player, int count) {
        if (countEmptyMaps(player) < count) return false;

        PlayerInventory inv = player.getInventory();
        int remaining = takeFrom(inv.getItemInMainHand(), count);
        if (remaining > 0) remaining = takeFrom(inv.getItemInOffHand(), remaining);
        if (remaining > 0) {
            for (ItemStack item : inv.getStorageContents()) {
                remaining = takeFrom(item, remaining);
                if (remaining <= 0) break;
            }
        }
        if (remaining > 0) {
            for (ItemStack item : inv.getArmorContents()) {
                remaining = takeFrom(item, remaining);
                if (remaining <= 0) break;
            }
        }
        if (remaining > 0) {
            for (ItemStack item : inv.getExtraContents()) {
                remaining = takeFrom(item, remaining);
                if (remaining <= 0) break;
            }
        }
        return remaining <= 0;
    }

    /** Removes up to {@code count} from the given stack, returning how many were not taken. */
    private int takeFrom(ItemStack item, int count) {
        if (!isEmptyMap(item)) return count;
        int take = Math.min(count, item.getAmount());
        item.setAmount(item.getAmount() - take);
        return count - take;
    }

    private void startImageProcessing(String input, int rows, int cols, Player player) {
        boolean isUrl = input.startsWith("http://") || input.startsWith("https://");

        if (isUrl) {
            final String imageUrl = input;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    BufferedImage original = ImageIO.read(in);
                    if (original == null) {
                        player.sendMessage(ChatColor.RED + "Failed to load image from URL.");
                        return;
                    }
                    processAndQueueTiles(original, imageUrl, "url", null, rows, cols, player);
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error loading image: " + e.getMessage());
                    getLogger().log(Level.WARNING, "Error processing image URL: " + imageUrl, e);
                }
            });

        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                File imageFile = resolveLocalFile(input);
                if (imageFile == null) {
                    player.sendMessage(ChatColor.RED + "Could not find image file '" + input + "' in the plugin data folder.");
                    player.sendMessage(ChatColor.YELLOW + "Supported formats: .png .jpg .jpeg .gif .bmp .webp");
                    return;
                }
                try {
                    BufferedImage original = ImageIO.read(imageFile);
                    if (original == null) {
                        player.sendMessage(ChatColor.RED + "Failed to read image file: " + imageFile.getName());
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN + "Loading local image: " + imageFile.getName());
                    processAndQueueTiles(original, null, "local", imageFile.getName(), rows, cols, player);
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error reading local image: " + e.getMessage());
                    getLogger().log(Level.WARNING, "Error processing local image: " + imageFile.getAbsolutePath(), e);
                }
            });
        }
    }

    private void processAndQueueTiles(BufferedImage original, String url, String source, String fileName,
                                      int finalRows, int finalCols, Player player) {
        int fullW = finalCols * TILE_SIZE;
        int fullH = finalRows * TILE_SIZE;

        BufferedImage scaled = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        Image tmp = original.getScaledInstance(fullW, fullH, Image.SCALE_SMOOTH);
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        original.flush();

        List<Tile> tiles = new ArrayList<>();
        for (int tileY = 0; tileY < finalRows; tileY++) {
            for (int tileX = 0; tileX < finalCols; tileX++) {
                BufferedImage tile = scaled.getSubimage(tileX * TILE_SIZE, tileY * TILE_SIZE, TILE_SIZE, TILE_SIZE);

                byte[] pixels = new byte[TILE_SIZE * TILE_SIZE];
                for (int y = 0; y < TILE_SIZE; y++) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        java.awt.Color c = new java.awt.Color(tile.getRGB(x, y));
                        pixels[y * TILE_SIZE + x] = org.bukkit.map.MapPalette.matchColor(c);
                    }
                }
                tile.flush();

                tiles.add(new Tile(tileY, tileX, pixels));
            }
        }
        scaled.flush();

        // Charge the empty maps (one per tile) + XP once, on the main thread, right before
        // the maps are created. If the charge fails, no maps are created and nothing is lost.
        mapQueue.add(() -> {
            if (!commitCosts(player, finalRows * finalCols)) return;
            for (Tile tile : tiles) {
                final Tile t = tile;
                mapQueue.add(() -> createMapOnMainThread(
                        player.getWorld().getName(), url, source, fileName,
                        t.row(), t.col(), finalRows, finalCols, t.pixels(), player));
            }
        });

        player.sendMessage(ChatColor.GREEN + "Queued map image creation: " + finalRows + "x" + finalCols);
    }

    private void createMapOnMainThread(String worldName, String url, String source, String fileName,
                                       int row, int col, int totalRows, int totalCols,
                                       byte[] pixels, Player giver) {
        try {
            MapView mapView = Bukkit.createMap(Bukkit.getWorld(worldName));
            int mapId = mapView.getId();

            mapView.getRenderers().forEach(mapView::removeRenderer);
            mapView.addRenderer(new PersistentRenderer(pixels));

            try {
                mapView.setUnlimitedTracking(true);
                mapView.setTrackingPosition(false);
            } catch (Throwable ignored) {}

            // persist
            ConfigurationSection entry = config.createSection("maps." + mapId);
            entry.set("source", source);
            if ("local".equals(source)) {
                entry.set("file", fileName);
            } else {
                entry.set("url", url);
            }
            entry.set("row", row);
            entry.set("col", col);
            entry.set("totalRows", totalRows);
            entry.set("totalCols", totalCols);
            // Persist the rendered palette bytes themselves, so a restart can
            // restore this map without any download (and without MapPalette).
            entry.set("pixels", Base64.getEncoder().encodeToString(pixels));
            saveConfig();

            giver.getInventory().addItem(createMapItem(mapView));

        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Failed to create map on main thread", t);
        }
    }

    // ------------------------------------------------------------------
    // Restart restore: saved pixels first, one-shot migration for legacy
    // URL/local entries that have no saved pixels yet.
    // ------------------------------------------------------------------

    /** Starts a restore run (at enable, or from /mapimage repair). */
    private void beginRestoreRun(Player notifyPlayer) {
        currentRun++;
        countedIds.clear();
        mapRun.clear();
        pendingRestore.set(0);
        restoredFromPixels.set(0);
        migratedFromDownload.set(0);
        restoreFailed.set(0);
        restoreSummarySent = false;
        restoreNotifyPlayer = notifyPlayer;
        // Safety net: if some tile never reaches a terminal state, still
        // report what happened instead of staying silent forever.
        final int run = currentRun;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (run == currentRun) finishRestoreSummary(true);
        }, 2400L); // 2 minutes
    }

    /**
     * Queues the restore of every map in config. Maps with saved pixels are
     * restored from those pixels on the main thread (no download, no
     * MapPalette, so no color-cache race). Maps without pixels are migrated
     * once: download -> extract -> persist pixels -> apply. Returns how many
     * maps were queued.
     */
    private int restoreSavedMaps() {
        if (!config.isConfigurationSection("maps")) return 0;
        ConfigurationSection section = config.getConfigurationSection("maps");
        if (section == null) return 0;

        int fromPixels = 0;
        int toMigrate = 0;
        for (String key : section.getKeys(false)) {
            int mapId;
            try {
                mapId = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;

            byte[] pixels = decodePixels(entry.getString("pixels"));
            if (pixels != null) {
                mapRun.put(mapId, currentRun);
                pendingRestore.incrementAndGet();
                final int id = mapId;
                final byte[] p = pixels;
                mapQueue.add(() -> applyWithRetry(id, p, true, 0));
                fromPixels++;
                continue;
            }

            String source = entry.getString("source", "url");
            int row = entry.getInt("row");
            int col = entry.getInt("col");
            int totalRows = entry.getInt("totalRows", 1);
            int totalCols = entry.getInt("totalCols", 1);
            final int id = mapId;
            if ("local".equals(source)) {
                String fileName = entry.getString("file");
                if (fileName == null) {
                    getLogger().warning("MapImage: map " + mapId + " has no file and no saved pixels; skipping");
                    continue;
                }
                mapRun.put(mapId, currentRun);
                pendingRestore.incrementAndGet();
                mapQueue.add(() -> migrateMapAsync(id, "local", null, fileName, row, col, totalRows, totalCols, 1));
            } else {
                String url = entry.getString("url");
                if (url == null) {
                    getLogger().warning("MapImage: map " + mapId + " has no URL and no saved pixels; skipping");
                    continue;
                }
                mapRun.put(mapId, currentRun);
                pendingRestore.incrementAndGet();
                mapQueue.add(() -> migrateMapAsync(id, "url", url, null, row, col, totalRows, totalCols, 1));
            }
            toMigrate++;
        }

        getLogger().info("MapImage: restoring " + (fromPixels + toMigrate) + " saved map(s): "
                + fromPixels + " from saved pixels, " + toMigrate + " need migration (download once, then persist)");
        return fromPixels + toMigrate;
    }

    /** Decodes a saved pixel blob, or null if it is missing/invalid. */
    private static byte[] decodePixels(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            byte[] pixels = Base64.getDecoder().decode(base64);
            return pixels.length == PIXEL_COUNT ? pixels : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean hasPersistentRenderer(MapView view) {
        for (MapRenderer renderer : view.getRenderers()) {
            if (renderer instanceof PersistentRenderer) return true;
        }
        return false;
    }

    /**
     * Tries to (re-)attach the image renderer to an existing map. Returns
     * true if the map now shows the image (or already did). Uses the int
     * overload of Bukkit.getMap: the short overload truncates ids > 32767.
     */
    private boolean tryApplyRenderer(int mapId, byte[] pixels) {
        MapView mapView;
        try {
            mapView = Bukkit.getMap(mapId);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "MapImage: failed to look up map " + mapId, t);
            return false;
        }
        if (mapView == null) return false;
        if (hasPersistentRenderer(mapView)) return true;
        mapView.getRenderers().forEach(mapView::removeRenderer);
        mapView.addRenderer(new PersistentRenderer(pixels));
        try {
            mapView.setUnlimitedTracking(true);
            mapView.setTrackingPosition(false);
        } catch (Throwable ignored) {}
        return true;
    }

    /** Applies a renderer, retrying while the map data is not loaded yet. */
    private void applyWithRetry(int mapId, byte[] pixels, boolean fromPixels, int attempt) {
        if (tryApplyRenderer(mapId, pixels)) {
            if (fromPixels) {
                getLogger().info("MapImage: restored map " + mapId + " from saved pixels");
            } else {
                getLogger().info("MapImage: migrated map " + mapId + " (downloaded once, pixels now saved)");
            }
            finishMap(mapId, fromPixels, true);
            return;
        }
        if (attempt >= MAX_APPLY_RETRIES) {
            getLogger().warning("MapImage: map " + mapId + " still not loaded after ~60s; "
                    + "will retry when the map initializes or on the next restart");
            finishMap(mapId, fromPixels, false);
            return;
        }
        if (attempt == 0) {
            getLogger().info("MapImage: map " + mapId + " not loaded yet, will retry");
        }
        final int next = attempt + 1;
        Bukkit.getScheduler().runTaskLater(this,
                () -> mapQueue.add(() -> applyWithRetry(mapId, pixels, fromPixels, next)),
                APPLY_RETRY_DELAY_TICKS);
    }

    /**
     * One-shot migration for a legacy URL/local entry with no saved pixels:
     * downloads the image, then queues the (serialized, main-thread) pixel
     * extraction. Download failures are retried; a tile that still fails is
     * left un-restored and retried on the next restart, never silently lost.
     */
    private void migrateMapAsync(int mapId, String source, String url, String fileName,
                                 int row, int col, int totalRows, int totalCols, int attempt) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            BufferedImage original = null;
            Exception failure = null;
            try {
                if ("local".equals(source)) {
                    File imageFile = resolveLocalFile(fileName);
                    if (imageFile == null) {
                        getLogger().warning("MapImage: cannot migrate map " + mapId
                                + ": local file not found: " + fileName);
                        finishMap(mapId, false, false);
                        return;
                    }
                    original = ImageIO.read(imageFile);
                    if (original == null) {
                        getLogger().warning("MapImage: cannot migrate map " + mapId
                                + ": could not read local file: " + fileName);
                        finishMap(mapId, false, false);
                        return;
                    }
                } else {
                    getLogger().info("MapImage: migrating map " + mapId + ": downloading " + url);
                    try (InputStream in = new URL(url).openStream()) {
                        original = ImageIO.read(in);
                    }
                    if (original == null) {
                        getLogger().warning("MapImage: cannot migrate map " + mapId
                                + ": downloaded data is not a readable image: " + url);
                        finishMap(mapId, false, false);
                        return;
                    }
                }
            } catch (Exception e) {
                failure = e;
            }
            if (failure != null) {
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    getLogger().warning("MapImage: failed to download map " + mapId
                            + " (attempt " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS + "): "
                            + failure + " -- retrying");
                    final int next = attempt + 1;
                    Bukkit.getScheduler().runTaskLater(this,
                            () -> mapQueue.add(() -> migrateMapAsync(mapId, source, url, fileName, row, col, totalRows, totalCols, next)),
                            DOWNLOAD_RETRY_DELAY_TICKS);
                } else {
                    getLogger().warning("MapImage: failed to download map " + mapId + " from " + url
                            + " after " + MAX_DOWNLOAD_ATTEMPTS + " attempts: " + failure
                            + " -- will retry on the next restart");
                    finishMap(mapId, false, false);
                }
                return;
            }

            // Pixels are extracted on the main thread, serialized through
            // mapQueue, so concurrent MapPalette.matchColor calls can never
            // race the color-cache init (cold cache build takes ~39s; the
            // first call throws IllegalStateException until it is ready).
            final BufferedImage image = original;
            mapQueue.add(() -> extractAndPersistTile(mapId, row, col, totalRows, totalCols, image, 0));
        });
    }

    /** Extracts one tile's pixels (main thread), persists them, then applies. */
    private void extractAndPersistTile(int mapId, int row, int col, int totalRows, int totalCols,
                                       BufferedImage original, int attempt) {
        byte[] pixels;
        try {
            pixels = extractTilePixels(original, row, col, totalRows, totalCols);
        } catch (IllegalStateException e) {
            // Map color cache still building (cold start). Retry with a delay
            // instead of dropping the tile.
            if (attempt < MAX_COLOR_CACHE_RETRIES) {
                if (attempt == 0) {
                    getLogger().info("MapImage: map color cache not ready yet for map " + mapId
                            + ", will retry (cold cache build takes a while)");
                }
                final int next = attempt + 1;
                Bukkit.getScheduler().runTaskLater(this,
                        () -> mapQueue.add(() -> extractAndPersistTile(mapId, row, col, totalRows, totalCols, original, next)),
                        COLOR_CACHE_RETRY_DELAY_TICKS);
                return;
            }
            getLogger().log(Level.SEVERE, "MapImage: map color cache still not ready after "
                    + MAX_COLOR_CACHE_RETRIES + " retries for map " + mapId
                    + " -- will retry on the next restart", e);
            finishMap(mapId, false, false);
            return;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "MapImage: failed to render map " + mapId, t);
            finishMap(mapId, false, false);
            return;
        }

        ConfigurationSection entry = config.getConfigurationSection("maps." + mapId);
        if (entry == null) entry = config.createSection("maps." + mapId);
        entry.set("pixels", Base64.getEncoder().encodeToString(pixels));
        saveConfig();

        applyWithRetry(mapId, pixels, false, 0);
    }

    /** Terminal accounting for one map in the current restore run. */
    private void finishMap(int mapId, boolean fromPixels, boolean success) {
        Integer run = mapRun.get(mapId);
        if (run == null || run != currentRun) return;
        if (!countedIds.add(mapId)) return;
        if (success) {
            if (fromPixels) restoredFromPixels.incrementAndGet();
            else migratedFromDownload.incrementAndGet();
        } else {
            restoreFailed.incrementAndGet();
        }
        pendingRestore.decrementAndGet();
        maybeFinishRestoreSummary();
    }

    private void maybeFinishRestoreSummary() {
        if (restoreSummarySent) return;
        if (pendingRestore.get() > 0) return;
        finishRestoreSummary(false);
    }

    private void finishRestoreSummary(boolean forced) {
        if (restoreSummarySent) return;
        restoreSummarySent = true;
        int pending = pendingRestore.get();
        String msg = "MapImage: restore finished: " + restoredFromPixels.get() + " restored from saved pixels, "
                + migratedFromDownload.get() + " migrated (downloaded + pixels saved), "
                + restoreFailed.get() + " failed"
                + (pending > 0 ? " (" + pending + " still pending)" : "")
                + (forced && pending > 0 ? " -- some tiles are still retrying; they will finish or retry on the next restart" : "");
        getLogger().info(msg);
        Player player = restoreNotifyPlayer;
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.GREEN + msg.substring("MapImage: ".length()));
        }
        restoreNotifyPlayer = null;
    }

    /**
     * Safety net for maps that load after enable (or whose enable-time
     * restore ran before the map data was loaded): when a known map
     * initializes, re-attach its saved image renderer.
     */
    @EventHandler
    public void onMapInitialize(MapInitializeEvent event) {
        MapView view = event.getMap();
        int mapId = view.getId();
        ConfigurationSection entry = config == null ? null : config.getConfigurationSection("maps." + mapId);
        if (entry == null) return;
        byte[] pixels = decodePixels(entry.getString("pixels"));
        if (pixels == null) return;
        if (hasPersistentRenderer(view)) return;
        view.getRenderers().forEach(view::removeRenderer);
        view.addRenderer(new PersistentRenderer(pixels));
        try {
            view.setUnlimitedTracking(true);
            view.setTrackingPosition(false);
        } catch (Throwable ignored) {}
        getLogger().info("MapImage: re-applied saved image to map " + mapId + " as it initialized");
    }

    /** /mapimage repair: re-run the enable restore now, and report the result. */
    private void runRepair(Player player) {
        beginRestoreRun(player);
        int queued = restoreSavedMaps();
        if (queued == 0) {
            player.sendMessage(ChatColor.YELLOW + "No image maps saved in config.yml to repair.");
            finishRestoreSummary(false);
            return;
        }
        player.sendMessage(ChatColor.GREEN + "Repair started for " + queued + " map(s): "
                + "restoring from saved pixels or re-downloading missing ones. "
                + "I'll report the result when it finishes.");
    }

    private byte[] extractTilePixels(BufferedImage original, int row, int col, int totalRows, int totalCols) {
        int fullW = totalCols * TILE_SIZE;
        int fullH = totalRows * TILE_SIZE;
        BufferedImage scaled = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        Image tmp = original.getScaledInstance(fullW, fullH, Image.SCALE_SMOOTH);
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        original.flush();

        BufferedImage tile = scaled.getSubimage(col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
        byte[] pixels = new byte[TILE_SIZE * TILE_SIZE];
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                java.awt.Color c = new java.awt.Color(tile.getRGB(x, y));
                pixels[y * TILE_SIZE + x] = org.bukkit.map.MapPalette.matchColor(c);
            }
        }
        tile.flush();
        scaled.flush();
        return pixels;
    }

    private File resolveLocalFile(String name) {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File direct = new File(dataFolder, name);
        if (direct.exists() && direct.isFile()) return direct;

        String baseName = name;
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.toLowerCase().endsWith(ext)) {
                baseName = name.substring(0, name.length() - ext.length());
                break;
            }
        }

        for (String ext : IMAGE_EXTENSIONS) {
            File candidate = new File(dataFolder, baseName + ext);
            if (candidate.exists() && candidate.isFile()) return candidate;
        }

        return null;
    }

    private ItemStack createMapItem(MapView view) {
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);
        return mapItem;
    }

    private record Tile(int row, int col, byte[] pixels) {}

    private static class PersistentRenderer extends MapRenderer {
        private final byte[] pixels;

        public PersistentRenderer(byte[] pixels) {
            super(true);
            this.pixels = pixels;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, org.bukkit.entity.Player player) {
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    canvas.setPixel(x, y, pixels[y * TILE_SIZE + x]);
                }
            }
        }
    }

    private static class MapData {
        final String url;
        final int row;
        final int col;
        final int totalRows;
        final int totalCols;

        MapData(String url, int row, int col, int totalRows, int totalCols) {
            this.url = url;
            this.row = row;
            this.col = col;
            this.totalRows = totalRows;
            this.totalCols = totalCols;
        }
    }
}
