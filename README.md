# Dockhand Android

Unofficial Android client for self-hosted [Dockhand](https://github.com/Finsys/dockhand) servers. Community port of the open-source iOS client [garanda21/dockhand_ios](https://github.com/garanda21/dockhand_ios) (MIT).

## Objectives

1. Performance: native Jetpack Compose, no webview, connection pooling, no blocking I/O on the main thread.
2. Privacy: no analytics or telemetry SDKs. Network calls go only to the Dockhand servers the user configures.
3. Security: API tokens and custom header values are encrypted with an Android Keystore AES-GCM key, cleartext HTTP is blocked per profile unless explicitly enabled, release builds are minified with R8, and secrets are never logged.
4. Reliability: pinned Maven Central dependencies, unit-tested API layer, environment-scoped destructive actions.

## Features

- Server profile management with per-profile URL, token and custom headers.
- Environment switching and health checks.
- Dashboard with container, image, volume, network, stack and host stats.
- Containers: list, filter, start, stop, restart, pause, unpause, live logs, interactive shell.
- Stacks: list, detail, start/stop/restart/down/redeploy, compose and .env editing with validation.
- Images: list, inspect, pull, tag, delete, prune and scan.

## Releases

Run the **Android Build** workflow manually (Actions tab):

- `build_type`: `debug`, `release`, or `both` — always runs tests + lint.
- `tag_and_release`: when true, creates tag `v<versionName>` at the dispatched commit and publishes a GitHub release with auto-generated changelog notes, attaching the built APK(s).

Bump `version.properties` before releasing; the workflow fails if the tag already exists. Signed release APKs require the four `DOCKHAND_*` repository secrets; without them the release APK is unsigned.

CI runs `verify` (tests + lint) and `assemble` in parallel. Regular release builds skip resource shrinking for speed; when `tag_and_release` is true the release APK is built fully shrunk.

The `verify` job is a reusable workflow (`.github/workflows/verify.yml`) and can also be dispatched standalone from the Actions tab when you only want tests and lint.

## Versioning

App version lives in `version.properties` (`versionCode`, `versionName`) and is the single source of truth for Gradle, the Settings screen and CI artifact names.

Policy: every change that lands on `main` updates it before commit.

- `feat:` → bump `versionName` minor (0.1.0 → 0.2.0)
- `fix:`, `perf:`, `refactor:` → bump `versionName` patch (0.1.0 → 0.1.1)
- breaking change → bump major, reset minor/patch
- every bump also increments `versionCode` by 1 (must always increase)

## Requirements

- JDK 17
- Android SDK with platform 37 and build-tools 37
- Gradle wrapper (bundled)

## Build and test

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug         # debug APK
./gradlew testDebugUnitTest     # JVM unit tests
./gradlew lintDebug             # Android lint
./gradlew assembleRelease       # minified release APK (unsigned)
```

## Integration tests

JVM integration tests are skipped unless a live Dockhand server is provided:

```sh
DOCKHAND_INTEGRATION_URL=http://your-host:3230 \
DOCKHAND_INTEGRATION_TOKEN=optional-token \
DOCKHAND_INTEGRATION_ENV=1 \
./gradlew testDebugUnitTest
```

## Architecture

- `api/`: models, tolerant JSON decoding, OkHttp service, SSE log parser, WebSocket shell protocol, error mapping.
- `data/`: DataStore preferences, Keystore-backed secret store, server profiles.
- `app/`: AppViewModel with profile, environment and scope state.
- `ui/`: Compose app shell, environment header, feature screens, formatting helpers.

## Security notes

- `androidx.security:security-crypto` is intentionally not used. Tokens and custom header values are encrypted directly with an Android Keystore AES-256-GCM key.
- Cleartext HTTP is disabled per profile by default; enabling it is an explicit user decision shown with a warning.
- Android Auto Backup is disabled so encrypted secrets never leave the device through backup.
- Header values and tokens are never written to logs, preferences or crash reports.

## License

The Android port code in this repository is provided under the MIT License. It is an independent, unofficial client and is not affiliated with or endorsed by the Dockhand project.
