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
- Configuration lives in `HdTileMarkersConfig`, with the original option names, enums and defaults. The path border width is a HD Tile Markers addition.
- `PathMarkerOverlay` is not included. Its display conditions are in `PathMarker.sceneTiles()`, and HD Tile Markers' scene renderer draws the tiles and dots. The minimap overlay is kept.
- The dot marker is centered on the tile center.
- Partial/unfound routes no longer force every step into the primary tile list while running. Checkpoint movement validation likewise no longer assumes walking solely because the route was not fully found. HD Tile Markers' extended-distance path uses the same running state and primary/secondary colors.

## RuneLite

- Source: https://github.com/runelite/runelite, tag `runelite-parent-1.12.39`
- License: BSD 2-Clause, copyright (c) 2016-2017 Adam, (c) 2018 Tomas Slusny, TheLonelyDev, James Swindle and Woox, and the RuneLite contributors. Full text in `src/main/resources/META-INF/LICENSE-runelite`, included in the JAR.

HD Tile Markers does not copy RuneLite source files as a whole. It reads Ground Markers, Object Markers and NPC Indicators data through the public configuration API, and adapts these parts; each file names its origin in a header:

- `ObjectMarkerSource.java`: matching and display rules of Object Markers (`ObjectIndicatorsPlugin.checkObjectPoints`, `loadPoints`, `ObjectIndicatorsOverlay.render`). Read-only, no menus, marks returned to HD Tile Markers' renderer.
- `MarkerSources.java`: Ground Markers' point loading (`GroundMarkerPlugin.loadPoints`) and NPC Indicators' selection (`getHighlights`, `highlightMatchesNPCName`, `render`, per-NPC `highlightcolor_` and `tagstyle_` keys).
- `AggroAreaSource.java`: the display rules of NPC Aggression Timer's area lines (`NpcAggroAreaOverlay.render`, `renderPath`: colour by timer, hide out of combat, 20-tile range). The lines themselves are read through the plugin's public getters.
- `Terrain.java`: the height interpolation of `Perspective.getTileHeight`.
- `ModelShapes.java`: the projection math of `Perspective.localToCanvasGpu` / `modelToCanvas`.
- `HdTileMarkersConfig.java`: the option names, descriptions and defaults of Tile Indicators (`TileIndicatorsConfig`), so the settings match.

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

HD Tile Markers contains no code from this plugin. The Tile Indicators options "Corners only" and "... Corner Size" use its option keys, names and defaults, and corner lines follow its rule of 1/size of each side (the same rule as Better NPC Highlight's corner style, also credited there to Geheur).

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
