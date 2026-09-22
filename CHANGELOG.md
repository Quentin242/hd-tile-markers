# Changelog

## 0.1.2

Use the original development preset as the plugin defaults, including tile colors, corner markers, path colors and draw distance. Existing saved settings retain precedence.

## 0.1.1

- Enforce the outline raster cell limit including padding; reject non-finite, oversized and excessively overlapping projections before raster allocation/work.
- Fall back per marker when scene rendering skips a model or tile, including the 64-model limit; preserve native NPC/Object outline settings and Better NPC Highlight style rendering.
- Keep offscreen and transparent markers handled without drawing them twice.
- Restore held overlays only when their owning plugin and feature are still enabled.
- Add 13 regression tests; all 108 isolated tests pass against RuneLite 1.12.39.

Live GPU/117 HD compatibility and frame cost remain unverified in this release review.

## 0.1.0

Initial Plugin Hub review release:

- Scene-rendered tile, NPC, object and walking-path markers, including Stretched Mode support and 2D fallback.
- Rendering integrations for Ground Markers, Object Markers, NPC Indicators, Better NPC Highlight, Tile Packs, Stealing Artefacts, Sailing and NPC Aggression Timer.
- Walking-path estimates respect display keybinds, Never, minimap-only and target-only settings.
- External plugin markers clear across session/profile changes and shutdown.
- Tile-marker icon, third-party license notices, Java 11 CI and 95 isolated tests.

Live GPU/117 HD compatibility and frame cost remain unverified in this release review. Plugin Hub approval is pending.
