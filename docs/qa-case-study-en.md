# Android QA Case Study

This page gives reviewers an English, evidence-focused view of the quality work behind Xia Gu BP. The repository was developed through iterative prompts and review with OpenAI Codex, and its commits identify Codex authorship. The claims below describe observable repository artifacts and dated verification results.

## Product and risk surface

Xia Gu BP combines a React web interface with a native Android assistant. The Android app reads public hero and relationship data, recognizes hero portraits from captured screens, lets users correct lanes and rosters, and produces recommendations from incomplete or changing evidence.

The main failure classes are incorrect hero identity, duplicate or stale roster state, layout drift across screen ratios, time-sensitive upstream data, invalid lane assignments, release-only breakage, and conclusions that overstate the available evidence.

## Automated coverage

The Android test suite contains 23 JVM unit tests across four focused test classes:

- `RecommendationEngineTest` checks positive and negative relationship evidence, reverse endpoints, bans, exclusions, and recommendation behavior.
- `BpScreenLayoutTest` checks recognition geometry and layout boundaries.
- `MatchupAnalysisEngineTest` checks lane probabilities, global lane assignment, irregular rosters, and manual lane corrections.
- `LaneTextParserTest` checks lane text parsing.

Run the public suite with:

```bash
cd android
./gradlew testDebugUnitTest --no-daemon --console=plain
```

Debug unit tests do not require the private release signing key. The build now checks for signing material only when a Release task is present in the Gradle task graph. Release builds still fail closed when the keystore or signing properties are missing.

The GitHub Actions workflow installs Android API 35 and runs the same command for every relevant push and pull request.

## Regression design

The 1.8.0 verification record covers ten aspect-ratio and edge-condition variants from approximately 4:3 through 24:9. It validates the deduplicated selected and banned hero sets and includes a black-edge case.

The scope is intentionally narrow: the ten variants were derived from one existing screenshot, not ten independent devices or matches. New skins, occlusion, device-specific rendering, and future game UI changes remain field-regression risks.

## Defect example

Regression work exposed two interacting failure sources in the ban row: spacing calibration and duplicate guesses. The final fix used whole-row geometry, same-side hero deduplication, and clearer anchors. It did not reduce the recognition acceptance threshold to make the test pass.

This distinction matters because loosening a threshold could hide the immediate failure while increasing false positives in unseen layouts.

## Release and integration checks

The published 1.8.0 record documents:

- 23 passing JVM unit tests.
- A signed upgrade from 1.7.0 to 1.8.0 on an Android 11 x86_64 emulator with the same certificate.
- Release execution with R8, resource shrinking, and `lintVital`.
- State transfer through the main page, manual draft, and matchup analysis flow.
- An empty-roster path without a runtime crash.
- A dated API snapshot covering 132 heroes and 667 relationship rows.

The API result is treated as a dated observation. It does not freeze rankings or promise that upstream data will remain unchanged.

## Review links

- [Repository](https://github.com/3173616612/xia-gu-bp)
- [Published QA record in Chinese](../android/QA-1.8.0.md)
- [Android unit test sources](../android/app/src/test/java/com/xiagu/bp)
- [Release 1.8.0](https://github.com/3173616612/xia-gu-bp/releases/tag/v1.8.0)

The repository and this page are suitable as public evidence of test design, reproducible defect analysis, release verification, and explicit documentation of residual risk. They are not presented as employment history or client work.
