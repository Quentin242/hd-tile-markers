# HD Tile Markers guide

[Back to overview](../README.md)

Draw tile borders as geometry in the game scene, instead of stretching their pixels with the UI.

Initial release candidate for Plugin Hub review. Live GPU/117 HD compatibility and frame cost have not been verified as part of this release review.

## Included

Every marker is rebuilt each frame as flat scene geometry with its border width in screen pixels, so it stays sharp in Stretched Mode with GPU or 117 HD. Vertices are nudged towards the camera along their own view ray, which keeps them above the terrain without moving them on screen. Bridge tiles render on the plane above, like the client does.

- **Ground Markers:** the active profile's saved marks, including imported tilepacks, with Ground Markers' own color, border width and fill. Nothing is copied or migrated. Labels and the minimap stay with Ground Markers.
- **Tile Indicators:** destination, hovered and true tile, with the same options and the original development preset as defaults, plus Corner Tile Indicators' "Corners only" and corner size.
- **Tile Packs** (Hub plugin): the packs enabled there, including custom packs, with its override color, border width, fill and labels. The built-in pack list is bundled from Tile Packs (see notices).
- **Better NPC Highlight** (Hub plugin): its tags and all its settings (per-style name lists, colors, presets, task highlight, rave, widths, ignore dead/pets, distance). Tile styles (regular and corner lines), hull, area, clickbox, outline and respawn tiles are drawn in the scene; dashed lines, names and respawn countdowns with its own 2D code. Its overlay is hidden while HD Tile Markers draws; its menus and entity hider keep working. Its NPC ID lists are not read, so NPCs only in an ID list are not highlighted while HD Tile Markers draws.
- **Stealing Artefacts** (Hub plugin): in Port Piscarilius, the target house's drawers and ladder (clickbox), the patrol guards (hull, green when lured) and Captain Khaled without a task, with its settings and colours. Its three scene overlays are hidden while HD Tile Markers draws; the patrol facing arrows stay 2D; its hint arrow and panel keep working.
- **Sailing** (Hub plugin): while sailing, its rapids (safe/dangerous by your helm), lightning cloud strikes, shipwreck salvage areas, Barracuda Trials lost crates and the boat's true "tile", with its settings and colours. Those five overlays are hidden while HD Tile Markers draws; everything on the boat itself (facilities, sails) and its panels stay its own.
- **The Gauntlet** (Hub plugin): its maze resources (outline, tile and icon, hidden once you have gathered enough with its resource tracker) and utilities, with its settings and colours. Its maze overlay is hidden while HD Tile Markers draws; its NPC highlights, counters, minimap and timer stay its own. Gathered resources are counted from the start of a run, like that plugin does.
- **NPCs from NPC Indicators:** its Tag-All and NPC list (wildcards), per-NPC colors and tag styles, fill, border width, ignore dead and ignore pets. The styles (hull, tile, true tile, south-west tile, south-west true tile, outline) come from NPC Indicators' own options. Its own 2D drawing cannot be hidden (it goes through RuneLite's shared NPC overlay), so those styles are drawn by both. Its single-NPC "Tag" is kept privately by that plugin and is not available to HD Tile Markers.
- **Objects from Object Markers:** everything marked with Mark object, with each object's own border color, fill and style (hull, clickbox, tile, outline) and Object Markers' defaults. Walls and decorations get both hull parts. HD Tile Markers replaces Object Markers' overlay while it draws; Object Markers keeps its menus and data.
- **Paths:** a fork of Path Marker (see [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)): active and hover path, primary/secondary tiles for running, tile or dot style, draw locations (game world, minimap), draw mode, display keybinds and "draw only if no active path".
- **NPC Aggression Timer:** its unaggressive area lines, with its colours (aggressive while the timer runs) and its "hide out of combat" option. Its line overlay is hidden while HD Tile Markers draws; the timer stays its own.
- **Agility:** the Agility plugin's obstacle and shortcut clickboxes (orange above your level), traps, marks of grace, the Agility Arena stick and Sepulchre highlights, with its settings and colours. Its overlay is hidden while HD Tile Markers draws; its lap counter stays its own.
- **Tile Indicators extras:** a delay before the fade-out, fading only out of combat, and a predicted destination and path when you click beyond the loaded area (such as 117 HD's extended terrain).
- **Through walls** (default on): every mark is drawn in front of walls and characters, like the normal 2D overlays, and lit the same way everywhere with 117 HD.
- **117 HD:** marks are lit like flat ground seen from above, the same from every camera angle.
- **117 HD shadows — turn off "Shadow transparency":** marks drawn through walls float in front of the camera, so a shadow they cast moves over the ground as the camera moves. With 117 HD's **Shadow transparency off**, HD Tile Markers keeps these marks just under 117 HD's shadow threshold (at most 70 % opaque) and they cast **no shadow**. With **Shadow transparency on**, 117 HD lets every visible face cast a shadow, and no plugin can prevent that: the marks then cast shadows that move with the camera. HD Tile Markers never changes 117 HD's settings; when it finds Shadow transparency on, it says so once in the chat box.
- **Marks from other plugins:** see [For plugin developers](#for-plugin-developers).
- A 2D fallback for anything not drawn in the scene (no GPU, errors, other world views, more than 1,500 tiles).

HD Tile Markers only draws marks while the plugin that owns them is enabled, and never changes another plugin's settings.

## Defaults

The defaults use the original development preset: 200-tile draw distance, a green destination with corner borders, cyan true-tile corners, hover highlighting and a game-world-only active path with translucent green/grey fills. Saved settings take precedence; reset HD Tile Markers to defaults to apply this preset to an existing profile. Legacy NPC/object tags are not bundled as defaults.

## Rendering limits

The scene route requires GPU or 117 HD. With Through walls disabled, 117 HD applies its own lighting, so colors can differ slightly from the configured RGB, and walls and entities can occlude scene markers. A floor that is an object model (for example some docks) rather than terrain can still cover tiles. Real frame cost has not been measured.

## For plugin developers

Other plugins can have HD Tile Markers draw their tiles and NPC highlights, without depending on HD Tile Markers: post a `PluginMessage` on the event bus with namespace `hd-tile-markers`. Without HD Tile Markers installed the message is simply ignored.

```java
Map<String, Object> tile = new HashMap<>();
tile.put("point", new WorldPoint(3222, 3218, 0)); // or "x", "y", "plane"
tile.put("color", Color.CYAN);                    // Color or ARGB int
tile.put("fill", new Color(0, 255, 255, 40));     // optional, default black at alpha 50
tile.put("width", 2);                              // optional border width in pixels
tile.put("size", 1);                               // optional, n x n tiles from the south-west tile
tile.put("label", "Safespot");                     // optional

Map<String, Object> data = new HashMap<>();
data.put("owner", "my-plugin");                    // your plugin's id
data.put("tiles", List.of(tile));
eventBus.post(new PluginMessage("hd-tile-markers", "tiles", data));
```

- `tiles`: replaces all tiles of `owner`.
- `npcs`: `owner` and `npcs`, a list of maps with `npc` (the NPC) or `index`, `style` (`hull`, `outline`, `clickbox`, `tile` or `truetile`), `color`, and optional `fill` and `width`. Replaces all NPC marks of `owner`.
- `clear`: `owner`. Removes everything of `owner`.

Marks stay until their owner replaces or clears them, at most 1,000 per owner. They are also cleared on logout, world hop, connection loss, profile change and HD Tile Markers shutdown. Send `clear` in your plugin's `shutDown` and republish marks after session changes.

## Development

Java 11 and the Gradle wrapper:

```sh
./gradlew test jar
./gradlew run
```

`test` uses synthetic data and mocks and does not launch RuneLite. `run` starts a separate development client for manual testing. For Jagex accounts, use RuneLite's [development login instructions](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts). The build defaults to the latest release; `-PruneliteVersion=1.12.39` reproduces the initial local build.


Source: [BSD-2-Clause](../LICENSE). Adapted code and its licenses: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
