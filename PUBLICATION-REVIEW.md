# Publication review — 2026-09-22

Release: 0.1.1. Target: RuneLite 1.12.39 (the Plugin Hub target on the review date).

## Rules and packaging

Reviewed against the [Plugin Hub submission instructions](https://github.com/runelite/plugin-hub),
[RuneLite's rejected features and forbidden language features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features),
and [Jagex's third-party client guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1).
The existing [Drop Enhancer submission](https://github.com/runelite/plugin-hub/pull/16570) was used as a publication reference.

- Java-only production sources, Java 11 bytecode, `build=standard`, and one plugin entry point.
- Production sources use public RuneLite APIs. No reflection, native calls, subprocess execution, runtime code downloads, input automation, outgoing chat changes, or credential access was found.
- Input listeners observe user actions for display; they do not generate game actions. Path prediction estimates the user's walking route, not attacks, prayers or combat hazards. Outside loaded terrain, it is an approximation with no collision knowledge.
- Ground/object/NPC settings are read from their owning plugins. No writes to another plugin's configuration were found.
- NPC highlighting includes existing Better NPC Highlight selections. The plugin does not introduce a standalone user-entered-ID marking feature. This compatibility implementation and the external PluginMessage drawing interface remain subject to maintainer review.
- Sailing and Stealing Artefacts indicators adapt the upstream implementations credited in THIRD_PARTY_NOTICES.md. They should be reviewed as rendering integrations; upstream acceptance does not imply approval of this derivative.
- Original BSD/MIT notices are retained and seven license resources are bundled in the JAR.
- The original tile outline icon is 48×48 PNG, below the Hub's 48×72 limit; editable source is assets/icon.svg and is covered by LICENSE.
- Build output, local research, assistant instructions, logs and local environment files are excluded from Git.

## Validation

- Java 11 build and 108 isolated tests passed against RuneLite 1.12.39. Tests do not launch RuneLite.
- Regression coverage added for prediction display/keybind/target-only settings and external-marker clearing on logout, hop, connection loss and profile change.
- Checked Java bytecode version 55 and bundled licenses in the production JAR.
- The Plugin Hub standard production build is also checked in an isolated directory using the official standard-build.gradle, with its RuneLite dependencies pinned to 1.12.39. The full Hub API/dependency checks run on the submission PR.

## Follow-up review fixes (0.1.1)

The deeper review found three defects in 0.1.0: a soft outline memory limit, global fallback decisions that omitted individual markers, and overlay reset ignoring a feature's current setting. All three are corrected in 0.1.1 with 13 regression tests. Outline raster allocation includes padded dimensions and raster work is bounded; unsupported projections remain eligible for native 2D fallback. The overlay checks handled state per marker/style and retains the source's outline feather settings. Held overlay reset uses the current feature predicate.

Existing path recalculation and allocation patterns remain candidates for profiling; no measured FPS claim is made.

## Remaining manual checks

This source/build review is not Plugin Hub approval or a live compatibility/performance certification.
Before promoting the prerelease as stable, check GPU and 117 HD rendering in Stretched Mode, bridges and instance/world-view transitions; toggle source plugins and their overlay settings; disable/re-enable HD Tile Markers and verify original overlays return; check logout/profile changes, fallback rendering and crowded scenes. Frame cost has not been measured.

The supported Hub workflow is a public source repository and a manifest pinned to its full commit hash. Distribution through the Hub remains pending RuneLite maintainer review and merge.
