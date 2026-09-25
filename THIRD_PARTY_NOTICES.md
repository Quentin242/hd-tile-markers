# Third-party notices

## Path Marker

- Authors: GeChallengeM
- Source: https://github.com/GeChallengeM/path-marker
- Commit: `495f3594bf697a1b9a5313802f731b2c85a6e37e`
- License: BSD 2-Clause, copyright (c) 2022 GeChallengeM. Full text in `src/main/resources/META-INF/LICENSE-path-marker`, included in the JAR.

Files in `src/main/java/com/hdtilemarkers/pathmarker/` and `src/main/resources/com/hdtilemarkers/pathmarker/` (`loc_blocking.txt`, `npc_blocking.txt`) are adapted from that repository. Each Java file carries a header naming its origin.

Changes made for HD Tile Markers:

- Package and class names (`PathMarkerPlugin` → `PathMarker`, `KeyListener` → `PathKeyListener`, `MouseListener` → `PathMouseListener`).
- `PathMarker` is no longer a separate plugin; HD Tile Markers starts it and registers it on the event bus.
- Lombok annotations are replaced with plain accessors.
- Configuration lives in `HdTileMarkersConfig`, with the original option names and enums; defaults use the original HD Tile Markers development preset. The path border width is a HD Tile Markers addition.
- `PathMarkerOverlay` is not included. Its display conditions are in `PathMarker.sceneTiles()`, and HD Tile Markers' scene renderer draws the tiles and dots. The minimap overlay is kept.
- The dot marker is centered on the tile center.
- Partial/unfound routes no longer force every step into the primary tile list while running. Checkpoint movement validation likewise no longer assumes walking solely because the route was not fully found. HD Tile Markers' extended-distance path uses the same running state and primary/secondary colors.

## RuneLite

- Source: https://github.com/runelite/runelite, tag `runelite-parent-1.12.39`
- License: BSD 2-Clause, copyright (c) 2016-2017 Adam, (c) 2018 Tomas Slusny, TheLonelyDev, James Swindle, Woox, Cas and SomeoneWithAnInternetConnection, and the RuneLite contributors. Full text in `src/main/resources/META-INF/LICENSE-runelite`, included in the JAR.

HD Tile Markers does not copy RuneLite source files as a whole. It reads Ground Markers, Object Markers and NPC Indicators data through the public configuration API, and adapts these parts; each file names its origin in a header:

- `ObjectMarkerSource.java`: matching and display rules of Object Markers (`ObjectIndicatorsPlugin.checkObjectPoints`, `loadPoints`, `ObjectIndicatorsOverlay.render`). Read-only, no menus, marks returned to HD Tile Markers' renderer.
- `MarkerSources.java`: Ground Markers' point loading (`GroundMarkerPlugin.loadPoints`) and NPC Indicators' selection (`getHighlights`, `highlightMatchesNPCName`, `render`, per-NPC `highlightcolor_` and `tagstyle_` keys).
- `AggroAreaSource.java`: the display rules of NPC Aggression Timer's area lines (`NpcAggroAreaOverlay.render`, `renderPath`: colour by timer, hide out of combat, 20-tile range). The lines themselves are read through the plugin's public getters.
- `BlastFurnaceSource.java`, `AbyssSource.java` and `PyramidPlunderSource.java`: the display rules of Blast Furnace (`BlastFurnaceClickBoxOverlay`, colours by bar state), Runecraft (`AbyssOverlay`, and the rift list of `AbyssRifts`) and Pyramid Plunder (`PyramidPlunderOverlay`, and the object IDs of `PyramidPlunderPlugin`). Blast Furnace's objects and the rifts are tracked from spawn events as those plugins do; Pyramid Plunder's are read through its public getters.
- `AgilitySource.java` and `AgilityObstacles.java`: the Agility plugin's display rules (`AgilityOverlay.render`, `highlightTile`), its shortcut matching (`AgilityPlugin.onTileObject`) and its obstacle ID lists (`Obstacles`, copied unchanged). Obstacles, marks of grace and Sepulchre NPCs are read through the plugin's public getters.
- `FloatClickbox.java`: RuneLite's clickbox (`Perspective.getClickbox`, `calculateAABB`, `calculate2DBounds`), its rectangle union (`RectangleUnion.union`) and convex clipping (`SimplePolygon.intersectWithConvex`), ported from ints to floats; segments are looked up through a sorted index instead of a list walk.
- `Terrain.java`: the height interpolation of `Perspective.getTileHeight`.
- `ModelShapes.java`: the projection math of `Perspective.localToCanvasGpu` / `modelToCanvas`.
- `HdTileMarkersConfig.java`: the option names and descriptions of Tile Indicators (`TileIndicatorsConfig`); defaults use the original HD Tile Markers development preset.

## Better NPC Highlight

- Authors: Buchus (MoreBuchus); maintained by riktenx and SamuelDev
- Source: https://github.com/riktenx/better-npc-highlight
- Commit: `bf59bfb9a616897e9ffcd14d0d2b543e4c119b09`
- License: BSD 2-Clause, copyright (c) 2022 Buchus. Full text in `src/main/resources/META-INF/LICENSE-better-npc-highlight`, included in the JAR.

Files in `src/main/java/com/hdtilemarkers/betternpc/` are adapted from that repository; each carries a header naming its origin. Changes: package names; `BetterNpcHighlightConfig` is used only to read Better NPC Highlight's saved settings (same group and keys), never to write; its setters and the menu-tagging methods that write settings are removed, and it is not bound in HD Tile Markers' injector (so RuneLite never shows it as HD Tile Markers' settings or fills in its defaults); the Slayer plugin is never enabled by HD Tile Markers; `BetterNpcEvents` keeps only the list-maintenance event handling of the plugin class; `BetterNpcView` is the overlay split into selection (`visit`), per-style 2D drawing (`render2d`) and names/respawn timers (`renderExtras`). Draw-beneath, the entity hider, menu highlighting and the debug option are not included (their settings are not read). `BetterNpcSource.java` follows the overlay's colours, alphas and widths per style. Menus, the entity hider, config migration and the minimap overlay stay in Better NPC Highlight itself.

## Tile Packs

- Author: TrevorMDev
- Source: https://github.com/TrevorMDev/tile-packs
- Commit: `d02a2e2f3197eb71df8b94e1282749f1a2844e46` (the Plugin Hub version at the time)
- License: BSD 2-Clause, copyright (c) 2022 TrevorMDev. Full text in `src/main/resources/META-INF/LICENSE-tile-packs`, included in the JAR.

`src/main/resources/com/hdtilemarkers/tilepacks/tilePacks.jsonc` is the pack list of that commit, unchanged. `TilePackSource.java` adapts its pack loading (`TilePackManager`, `PointManager`): read-only, enabled and custom packs from the Tile Packs configuration, markers returned to HD Tile Markers' renderer. Packs added to Tile Packs after that commit are missing until the file is updated; custom packs are always current.

## Corner Tile Indicators

- Author: geheur (Plugin Hub `corner-tile-indicators`, version 1.0.2)

HD Tile Markers contains no code from this plugin. The Tile Indicators options "Corners only" and "... Corner Size" use its option keys and names, with defaults from the HD Tile Markers development preset, and corner lines follow its rule of 1/size of each side (the same rule as Better NPC Highlight's corner style, also credited there to Geheur).

## Stealing Artefacts

- Author: Christopher Bitler (pajlads)
- Source: https://github.com/pajlads/StealingArtefacts
- Commit: `631227f32d7518da6615165c4658bf6e3704dedb`
- License: MIT, copyright 2020 Christopher Bitler. Full text in `src/main/resources/META-INF/LICENSE-stealing-artefacts`, included in the JAR.

`StealingArtefactsSource.java` adapts its target rules (`StealingArtefactsState`, `shouldMarkObject`, `isGuardLured`, `isInPisc`) and its overlay colours; `IndicatorOverlay.facingArrow` adapts its `renderFacingDirection`. Read-only: HD Tile Markers reads its settings, never writes them, and leaves its hint arrow, panel and saved state to that plugin.

## Sailing

- Author: LlemonDuck
- Source: https://github.com/LlemonDuck/sailing
- Commit: `af987f3be93bf0ca666af3d1f111cbf91e6a4d0d`
- License: BSD 2-Clause, copyright (c) 2025 LlemonDuck. Full text in `src/main/resources/META-INF/LICENSE-sailing`, included in the JAR.

`SailingSource.java` adapts the target rules of its `RapidsOverlay` (rapid ids, helm tiers of `HelmTier`), `LightningCloudsOverlay`, `SalvagingHighlight` (wreck levels, 15-tile area), `LostCargoHighlighter` and `TrueTileIndicator` (`renderBoatArea`), plus its option keys and defaults. Read-only: HD Tile Markers reads its settings, never writes them. Its other features (boat facilities, charting, courier, trials helpers, panels) stay with that plugin.

## The Gauntlet

- Authors: rdutta; maintained by LlemonDuck
- Source: https://github.com/LlemonDuck/the-gauntlet
- Commit: `bf0246abf6dc04ce5541264c10e663536f9864c2`
- License: BSD 2-Clause, copyright (c) 2023 rdutta. Full text in `src/main/resources/META-INF/LICENSE-the-gauntlet`, included in the JAR.

`GauntletSource.java` adapts the resource and utility object IDs (`MazeModule`, `ResourceGameObject`, `Resource`), the chat message resource tracking (`ResourceManager`) and the display rules and icons of `MazeOverlay`. Read-only: HD Tile Markers reads its settings, never writes them. Its NPC highlights, infobox counters, minimap and timer stay with that plugin.

## Rogues' Den

- Author: Jordan (nightfirecat)
- Source: https://github.com/nightfirecat/plugin-hub-plugins
- Commit: `3ece9e0401f5ebac9da2762015449d5e68bb0bfc`
- License: BSD 2-Clause, copyright (c) 2021 Jordan. Full text in `src/main/resources/META-INF/LICENSE-rogues-den`, included in the JAR.
- `RoguesDenSource.java`: the obstacle objects per tile (`Obstacles`), the jewel check and object tracking (`RoguesDenPlugin`) and the clickbox colours (`RoguesDenOverlay`). HD Tile Markers does not reference its classes; its hint tiles and text still come from its own overlay.

## Star Info

- Author: Cute Rock (pwatts6060)
- Source: https://github.com/pwatts6060/runelite-plugins
- Commit: `38fc77770ad0a5c07e74e7491975a90e40e62173`
- License: BSD 2-Clause, copyright (c) 2022 Cute Rock. Full text in `src/main/resources/META-INF/LICENSE-star-info`, included in the JAR.
- `StarInfoSource.java`: the crashed star tier IDs (`Star.TIER_IDS`) and the hull colour by Mining level (`StarInfoOverlay.getStarColor`). HD Tile Markers does not reference its classes; its text and health bar still come from its own overlay.
