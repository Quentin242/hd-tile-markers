# HD Tile Markers

![HD Tile Markers](icon.png)

Sharp tile, NPC, object and walking-path markers drawn in the game world, designed for Stretched Mode with GPU or 117 HD. Includes true tile, destination, hover and through-walls display options, with a 2D fallback.

**How the HD rendering works:** Normal overlay lines can look blurry when Stretched Mode enlarges the interface. This plugin builds markers from small, flat triangles inside the game scene, so GPU or 117 HD renders them at the scene's resolution. The geometry follows the camera each frame to keep border widths consistent in screen pixels. This changes how markers are drawn; it does not replace game textures or require 117 HD specifically.

Uses your existing Ground Markers, Object Markers and NPC Indicators settings. Also integrates with Better NPC Highlight, Tile Packs, Sailing, Stealing Artefacts, The Gauntlet, NPC Aggression Timer and Agility. Keep the source plugins enabled; their saved settings are left unchanged.

**With 117 HD, turn off its "Shadow transparency" setting.** Otherwise marks drawn through walls cast shadows that move with the camera; 117 HD gives plugins no way to prevent that. See [the guide](docs/GUIDE.md).

[Features and setup](docs/GUIDE.md) · [Development](docs/GUIDE.md#development) · [Credits](THIRD_PARTY_NOTICES.md) · [License](LICENSE)
