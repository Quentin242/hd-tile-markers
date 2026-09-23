# Changelog

## Unreleased

- Through-walls marks are drawn after see-through scenery such as tree leaves, so their fill no longer disappears at some camera angles.
- A chat notice when 117 HD's "Shadow transparency" is on, shown again after turning the plugin on.
- Through-walls faces stay under 117 HD's shadow threshold when its shadow transparency is off.
- Fixes: scene geometry read by the renderer while the next frame is written, the carrier model being reloaded every frame after a failed load, and a possible out-of-bounds read in the hover path.
- Replaced deprecated RuneLite API calls in the Path Marker, Better NPC Highlight and Gauntlet integrations.

## 0.1.2

Use the original development preset as the plugin defaults, including tile colors, corner markers, path colors and draw distance. Existing saved settings retain precedence.

## 0.1.1

- Enforce the outline raster cell limit including padding; reject non-finite, oversized and excessively overlapping projections before raster allocation/work.
- Fall back per marker when scene rendering skips a model or tile, including the 64-model limit; preserve native NPC/Object outline settings and Better NPC Highlight style rendering.
- Keep offscreen and transparent markers handled without drawing them twice.
- Restore held overlays only when their owning plugin and feature are still enabled.

## 0.1.0

Initial release:

- Scene-rendered tile, NPC, object and walking-path markers, including Stretched Mode support and 2D fallback.
- Rendering integrations for Ground Markers, Object Markers, NPC Indicators, Better NPC Highlight, Tile Packs, Stealing Artefacts, Sailing and NPC Aggression Timer.
- Walking-path estimates respect display keybinds, Never, minimap-only and target-only settings.
- External plugin markers clear across session/profile changes and shutdown.
