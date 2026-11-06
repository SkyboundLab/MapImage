# MapImage
A useful plugin for utility/moderation purposes that allows you to create a map corresponding to any image URL. Server needs internet on each startup for this to work, and needs to be able to access the image URL every time it starts.

## Usage
The easy way: **hold an empty map in your hand and run `/mapimage` with no arguments.** A popup dialog (Minecraft 1.21.6+) opens where you paste the image URL and click "Create Map". On older clients the URL can be typed in chat instead (you have 30 seconds). Creating a map costs XP levels and consumes empty maps — **one empty map per tile**, so a `2x2` image needs 4 empty maps (max 64 maps, e.g. 8x8, configurable via `max-maps`). Both are only charged once the image has loaded successfully, so a bad URL costs you nothing.

You can also use arguments directly: `/mapimage <image-url-or-filename> [rowsxcols]`. Include https:// in URLs, or it will not work. `rowsxcols` customizes the size of the image (e.g. `2x4` = 2 maps high, 4 maps wide), useful for bigger images. The maps start from top left and go left to right on each row until bottom right.

## Configuration (config.yml)
- `xp-cost-levels`: XP levels it costs to create an image map (default `5`, set `0` for free).
- `require-empty-map`: require an empty map in the player's hand (default `true`).
- `max-maps`: maximum number of maps (rows x cols) a single image may use (default `64`).
- `chat-input-timeout-seconds`: how long the chat URL fallback stays active after running `/mapimage` with no arguments (default `30`).

## Perms
`mapimage.use` is default to op, and corresponds to the /mapimage command. Using luckperms or another permissions updater you can change this, or just change the default in the source code and build.

## Requirements
Paper 1.21.6+ (the dialog popup needs Paper 1.21.7+; without it the chat fallback is used automatically).

## Questions
Add me on Discord, 'tommustbe12', and tell me what you're confused on. Thanks!
