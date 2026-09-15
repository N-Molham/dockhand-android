# Dockhand iOS → Android Port: Findings, Concerns, Plan

Status: research done. Lib facts verified vs official sources 2026-09-09.
Target: Android (Kotlin/Compose) port of `garanda21/dockhand_ios` (MIT), built by deepseek-v4-pro (DeepSeek-V4-Pro-0813, thinking on, reasoning_effort high).

## 0. Objectives (drive all decisions)

1. **Performance** — native Compose, no webview; lazy lists; connection pooling; no main-thread blocking I/O; minimal recomposition.
2. **Privacy** — no analytics/telemetry; network only to user-configured Dockhand servers; tokens never logged/prefs/crash reports.
3. **Security** — Keystore token storage (deprecated androidx.security banned), TLS validation, default-deny cleartext, R8, least-privilege token guidance.
4. **Reliability** — Maven Central only (no Jitpack), pinned version catalog, env-scoped destructive actions, offline states, parser/decoding tests.

## 1. iOS inventory (measured)

| Area | Files | LOC |
|---|---|---|
| Service | `DockhandService.swift` 1503 + `+Decoding` 261 | ~1760 |
| Generated OpenAPI | Types 3062 + Client 994 + ClientFactory 57 | ~4110 |
| Dashboard | `DashboardView.swift` 1228 | 1228 (plain SwiftUI, no Charts) |
| Images | View 1154 + Store 359 | ~1510 |
| Stacks | View 935 + Store 328 + EditorSupport 262 | ~1520 |
| Containers | View 454 | 454 |
| Logs | View 490 + SSE parser in service | ~600 |
| Shell | SwiftTerm view 91 + Store 259 + View 183 + Models 141 | ~670 |
| Settings | View 676 | 676 |
| App/persistence | AppModel 248 + PreferencesStore 159 + KeychainStore 96 + RootView 82 | ~585 |
| Shared | Formatting 519 + UserFacingError 261 + EnvHeaderBar 150 | ~930 |
| Tests | 588 | 588 |
| **Handwritten total** | | **~10,400** |

Deps: swift-openapi-runtime/urlsession, Yams 6.2.2, SwiftTerm 1.10.1. iOS 26, Swift 6.

## 2. API surface (iOS code = only sanctioned reference)

Spec paths (12): health, environments, containers, images, stacks, stack compose, stack env raw, container start/stop/restart/pause/unpause.

Hand-rolled paths outside spec:
- `/api/dashboard/stats`, `/api/system`
- `/api/containers/pending-updates` (GET/DELETE), `/api/containers/check-updates` (POST), `/api/containers/batch-update` (POST)
- `/api/jobs/{id}` (async poll)
- `/api/volumes`, `/api/networks`, `/api/activity`
- `/api/containers/{id}/logs` + `/logs/stream` (SSE), `/api/containers/{id}/shells`, `/api/containers/{id}/exec` (WS)
- image pull/tag/delete/prune, stack redeploy/delete (job-based)

Auth: optional `Authorization: Bearer <token>`. Env via `env`/`envId` query. Exec WS: http→ws, https→wss.

Headers (iOS parity, verified):

| Request | Headers |
|---|---|
| All REST | `Accept: application/json` |
| Body | `Content-Type: application/json` |
| Authed | `Authorization: Bearer <token>` (omit if empty) |
| SSE logs | `Accept: text/event-stream`, timeout 20s |
| Exec WS | `Authorization` only |
| Generated client | request 30s, resource 120s, ephemeral (no cookies) |

No iOS custom headers, no UA override, no X-headers, no cookie store. Android: OkHttp interceptor `Accept`+`Authorization`; per-request `Content-Type`; `CookieJar.NO_COOKIES`; matching timeouts. **Android add: per-profile custom headers** — Concern 10, Phases 1/2/3.

## 3. Lib picks (official sources)

| Need | Pick | Version | License |
|---|---|---|---|
| HTTP+SSE+WS | OkHttp | 5.5.0 | Apache-2.0 (`okhttp-sse`, WS RFC 6455, API 21+) |
| REST | Retrofit | 3.0.0 | Apache-2.0 (docs lysine.dev/retrofit; square.github.io 404s) |
| JSON | kotlinx.serialization | 1.11.0 | Apache-2.0 (compiler plugin, no kapt) |
| API gen (optional) | openapi-generator kotlin | v7.25.0 | STABLE; `jvm-retrofit2` + `kotlinx_serialization` + `useCoroutines` |
| YAML validation | snakeyaml-engine | 3.1.1 | Apache-2.0, active, no CVEs, YAML 1.2 |
| .env parse | manual | — | trivial KEY=VALUE |
| Secure storage | Keystore AES-GCM direct | platform | security-crypto deprecated 1.1.0 |
| Prefs | Preferences DataStore | 1.2.1 | Apache-2.0 |
| Alt stack | Ktor client | 3.5.2 | REST+WS ok; SSE manual |

Rejected: kaml (archived 2025-11-30), org.yaml:snakeyaml 2.7 (CVE history), jackpal ATE (archived 2022), datastore-tink (alpha).

## 4. Concerns

1. **Terminal = only hard part.** No maintained Apache/MIT Android terminal lib on Maven Central.
   - Termux terminal-view/emulator (60k stars, active): best fit (VT100, colors, UTF-8, resize). GPL-3.0-only, Jitpack only.
   - jediterm (LGPL-3.0 **OR** Apache-2.0 dual, active): no Maven artifact, Swing UI — vendor `core` + custom Compose view.
   - Fallback: scrollback shell (no `vim`/`htop` TUI).
   - **Decision: jediterm core (Apache-2.0 option) vendored + custom Compose renderer; scrollback shell first, upgrade second.** Termux rejected: GPL-3.0 + Jitpack fails license + reliability.
2. **security-crypto deprecated** — no EncryptedSharedPreferences; Keystore AES-GCM direct. Model likely suggests deprecated API; prompt must ban.
3. **Dockhand server repo = "LLM must not scrape"** (BSL 1.1). Never ingest server source; iOS client openapi.yaml + endpoint usage = reference only.
4. **~20 endpoints outside spec** — iOS code authoritative; no guessing. Risk only if server version differs.
5. **No live Dockhand server here** — integration tests env-gated; user validates vs real server.
6. **Compose API drift** — pin versions, stable APIs only, verify vs developer.android.com.
7. **Scope > single-shot** — ~10K Kotlin LOC; file-by-file w/ compile checkpoints.
8. **Cleartext HTTP** — Dockhand often `http://host:3230`; Android blocks cleartext since API 28. Network Security Config default-deny; per-profile opt-in only; never global.
9. **Token hygiene** — no LoggingInterceptor in release (redact debug); tokens never DataStore/prefs/backup; exclude token ciphertext from Auto Backup.
10. **Custom headers (new scope)** — per-profile name/value map on all requests: REST, SSE, WS handshake.
    - Storage: may hold gateway secrets → encrypt same Keystore AES-GCM util as token; exclude backup.
    - Validation: RFC 7230 name; reject CR/LF (injection); reject reserved `Authorization`, `Accept`, `Content-Type`, `Content-Length`, `Host`, `Connection`, `Upgrade`.
    - Apply: merge in OkHttp interceptor before `Accept`/`Authorization`; same map on WS upgrade `Request.Builder.header()`.
    - Logging: values never logged; redact debug.

## 5. Plan

- **Phase 0** — scaffold: Gradle Kotlin DSL + version catalog (pin Compose BOM, OkHttp 5.5.0, Retrofit 3.0.0, kotlinx.serialization 1.11.0, snakeyaml-engine 3.1.1, DataStore 1.2.1), minSdk 26, Compose. Network Security Config default-deny cleartext, R8 + serialization keep rules, no release logging-interceptor, backup rules exclude token ciphertext. Gate: `./gradlew assembleDebug` + lint green.
- **Phase 1** — API layer: port `DockhandService` (~30 calls), token normalization, error mapping → sealed types, SSE parser state machine, WS shell protocol. OkHttp interceptor chain: custom headers → `Accept` → `Authorization`. Port 588-line test file first as JUnit (specifies exact behavior). Correctness core.
- **Phase 2** — state: `AppModel` → `AppViewModel`; PreferencesStore → DataStore; KeychainStore → Keystore AES-GCM util; custom-header map encrypted same util.
- **Phase 3** — screens in order: Settings (profiles, token, custom-header editor w/ validation) → Containers → ContainerLogs → Dashboard → Stacks (+YAML/.env editor) → Images. EnvironmentHeaderBar early (shared).
- **Phase 4** — shell: WS exec store + scrollback terminal first, jediterm-core ANSI renderer second.
- **Phase 5** — tests + CI: JUnit (parsers, decoding, formatting, error mapping), env-gated integration mirroring iOS.
- **Phase 6** — polish: destructive-action confirmations, env scoping guards, release build.

Output estimate: ~9,500–10,500 Kotlin LOC + Gradle/config + tests + ~200 LOC custom headers.

## 6. Verdict for deepseek-v4-pro

- **Feasibility: high.** 100% logic translatable; hard behaviors (SSE, WS, job poll, error mapping) fully specified by iOS source + tests. Zero reverse-engineering.
- **Moderate effort.** ~25 files, sequential w/ checkpoints; 128K context enough per file cluster.
- **Only real risk:** terminal integration (sparse docs, no clean Maven artifact) — must not invent API.
- **Not one-shot.** Multi-session build; top failure = hallucinated Compose/Keystore APIs; mitigated by pinned versions + deprecated-API bans.

## 7. Sources

- iOS: github.com/garanda21/dockhand_ios (MIT)
- Server: github.com/Finsys/dockhand (BSL 1.1, no-ingest notice respected)
- OkHttp: github.com/square/okhttp; docs lysine.dev/okhttp
- Retrofit: Maven Central; docs lysine.dev/retrofit
- Ktor: ktor.io/docs/client-websockets.html (3.5.2)
- openapi-generator: openapi-generator.tech/docs/generators/kotlin (v7.25.0)
- security deprecation: developer.android.com/jetpack/androidx/releases/security (1.1.0)
- Keystore: developer.android.com/privacy-and-security/keystore
- DataStore: developer.android.com/topic/libraries/architecture/datastore
- Terminal: github.com/JetBrains/jediterm; github.com/termux/termux-app
- YAML: snakeyaml-engine (Maven Central 3.1.1)
- DeepSeek: api-docs.deepseek.com
