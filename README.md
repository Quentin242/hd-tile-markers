# HD Tile Markers

![HD Tile Markers](icon.png)

Sharp tile, NPC, object and walking-path markers drawn in the game world, designed for Stretched Mode with GPU rendering. Includes true tile, destination, hover and through-walls display options, with a 2D fallback.

**How the HD rendering works:** Normal overlay lines can look blurry when Stretched Mode enlarges the interface. This plugin builds markers from small, flat triangles inside the game scene, so the GPU renders them at the scene's resolution. The geometry follows the camera each frame to keep border widths consistent in screen pixels. This changes how markers are drawn; it does not replace game textures.

Uses the markers you already saved in RuneLite's marker plugins and their settings; nothing is copied or changed. See [the guide](docs/GUIDE.md#included) for everything it draws.

**Marks casting shadows that move with the camera?** Turn off "Shadow transparency" in your HD renderer's settings; HD Tile Markers says so in the chat box when it finds that setting on. See [the guide](docs/GUIDE.md).

[Features and setup](docs/GUIDE.md) · [Credits](THIRD_PARTY_NOTICES.md) · [License](LICENSE)
