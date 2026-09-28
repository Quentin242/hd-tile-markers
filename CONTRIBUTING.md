# Contributing to HD Tile Markers

## Development

Java 11 and the Gradle wrapper:

```sh
./gradlew test jar
./gradlew run
```

`test` uses synthetic data and mocks and does not launch RuneLite. `run` starts a separate development client for manual testing. For Jagex accounts, use RuneLite's [development login instructions](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts). The build defaults to the latest release; `-PruneliteVersion=1.12.39` reproduces the initial local build.

The "Debug info" option only works in RuneLite's developer mode. `./gradlew run` starts the client with `--developer-mode --debug`, so the status line and the frame-time log lines show there.

Other plugins can send HD Tile Markers their marks through a `PluginMessage`; see [For plugin developers](docs/GUIDE.md#for-plugin-developers).
