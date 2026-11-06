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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class MapImage extends JavaPlugin implements CommandExecutor, Listener {

    private FileConfiguration config;
    private final Queue<Runnable> mapQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, MapData> pendingMaps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingChatInput = new ConcurrentHashMap<>();

    private int xpCostLevels;
    private boolean requireEmptyMap;

    private static final int TILE_SIZE = 128;
    private static final int MAX_DIMENSION = 512;
    private static final int MAPS_PER_TICK = 2;

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

        if (config.isConfigurationSection("maps")) {
            ConfigurationSection section = config.getConfigurationSection("maps");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        int mapId = Integer.parseInt(key);
                        ConfigurationSection entry = section.getConfigurationSection(key);
                        if (entry == null) continue;

                        String source = entry.getString("source", "url");
                        int row = entry.getInt("row");
                        int col = entry.getInt("col");
                        int totalRows = entry.getInt("totalRows", 1);
                        int totalCols = entry.getInt("totalCols", 1);

                        if ("local".equals(source)) {
                            String fileName = entry.getString("file");
                            if (fileName != null) {
                                int finalMapId = mapId;
                                mapQueue.add(() -> reRenderMapFromFile(finalMapId, fileName, row, col, totalRows, totalCols));
                            }
                        } else {
                            String url = entry.getString("url");
                            if (url != null) {
                                int finalMapId = mapId;
                                mapQueue.add(() -> reRenderMapAsync(finalMapId, url, row, col, totalRows, totalCols));
                            }
                        }

                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
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

        int rows = 1, cols = 1;
        if (args.length >= 2 && args[1].matches("\\d+x\\d+")) {
            String[] parts = args[1].split("x");
            rows = Integer.parseInt(parts[0]);
            cols = Integer.parseInt(parts[1]);
        }

        if (args.length < 1) {
            // User-friendly flow: hold an empty map, run /mapimage with no arguments,
            // then enter the image URL in the dialog popup (or in chat as a fallback).
            if (!canCreateNow(player)) return true;
            startUrlInput(player);
            return true;
        }

        if (!canCreateNow(player)) return true;

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
                                    DialogBody.plainMessage(Component.text("Cost: " + xpCostLevels + " XP level(s) + 1 empty map", NamedTextColor.YELLOW))
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
                                    Component.text("Create the image map (" + xpCostLevels + " levels + 1 empty map)"),
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

        if (!canCreateNow(player)) return;
        startImageProcessing(input, rows, cols, player);
    }

    /**
     * Up-front check (main thread): the player must hold an empty map and have enough XP.
     * The actual map/XP are only consumed once the image has loaded successfully.
     */
    private boolean canCreateNow(Player player) {
        if (requireEmptyMap && !isHoldingEmptyMap(player)) {
            player.sendMessage(ChatColor.RED + "Hold an empty map in your hand to create an image map.");
            return false;
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
     * Consumes one empty map and the XP cost on the main thread, right before the
     * maps are created. Returns false (and charges nothing) if the player no longer
     * has what they need.
     */
    private boolean commitCosts(Player player) {
        if (!player.isOnline()) return false;

        if (requireEmptyMap && !consumeEmptyMap(player)) {
            player.sendMessage(ChatColor.RED + "You no longer have an empty map. Creation cancelled, nothing was charged.");
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

    private boolean consumeEmptyMap(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (isEmptyMap(main)) {
            main.setAmount(main.getAmount() - 1);
            return true;
        }
        ItemStack off = inv.getItemInOffHand();
        if (isEmptyMap(off)) {
            off.setAmount(off.getAmount() - 1);
            return true;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isEmptyMap(item)) {
                item.setAmount(item.getAmount() - 1);
                return true;
            }
        }
        return false;
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

        // Charge the empty map + XP once, on the main thread, right before the maps are created.
        // If the charge fails, no maps are created and nothing is lost.
        mapQueue.add(() -> {
            if (!commitCosts(player)) return;
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
            saveConfig();

            giver.getInventory().addItem(createMapItem(mapView));

        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Failed to create map on main thread", t);
        }
    }

    private void reRenderMapAsync(int mapId, String url, int row, int col, int totalRows, int totalCols) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(url).openStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) return;

                byte[] pixels = extractTilePixels(original, row, col, totalRows, totalCols);

                mapQueue.add(() -> applyRendererToExistingMap(mapId, pixels));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to re-download map image: " + url, e);
            }
        });
    }

    private void reRenderMapFromFile(int mapId, String fileName, int row, int col, int totalRows, int totalCols) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File imageFile = resolveLocalFile(fileName);
            if (imageFile == null) {
                getLogger().warning("Cannot re-render map " + mapId + ": local file not found: " + fileName);
                return;
            }
            try {
                BufferedImage original = ImageIO.read(imageFile);
                if (original == null) return;

                byte[] pixels = extractTilePixels(original, row, col, totalRows, totalCols);

                mapQueue.add(() -> applyRendererToExistingMap(mapId, pixels));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to re-render map " + mapId + " from file: " + fileName, e);
            }
        });
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

    private void applyRendererToExistingMap(int mapId, byte[] pixels) {
        try {
            MapView mapView = Bukkit.getMap((short) mapId);
            if (mapView == null) return;
            mapView.getRenderers().forEach(mapView::removeRenderer);
            mapView.addRenderer(new PersistentRenderer(pixels));
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to re-render map " + mapId, t);
        }
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
