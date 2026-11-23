# Latencyscope — Implementation Plan

Android library that records API latency while the app runs, and emits a
detailed HTML + CSV report when the session stops.

> **Assumptions (change before Phase 0 if wrong)**
> - Library name: **Latencyscope**, facade class `Latencyscope`
> - Group id: `io.github.<GITHUB_USERNAME>` — placeholder `GH_USER` used below
> - Repo name: `latencyscope`
> - License: Apache-2.0

---

## 0. Current state

| Item | Value |
|---|---|
| Root | `/Users/indianic/Bhargav/AndroidProjects/APILatencyReport` |
| AGP | 9.5.2 |
| compileSdk | 37; sample minSdk 28, libraries minSdk 21 |
| Kotlin | 2.2.10, built into AGP 9 (no KGP plugin) |
| Modules | 9 library modules + `:sample` |
| Git | initialised, Apache-2.0 |

The existing `:app` becomes `:sample`. Library modules are new.

---

## 1. Target module layout

```
latencyscope/
├── latencyscope-core          (android-library, kotlin)   no 3rd-party deps
├── latencyscope-okhttp        (android-library)           okhttp compileOnly
├── latencyscope-ktor          (android-library)           ktor-client-core compileOnly
├── latencyscope-volley        (android-library)           volley compileOnly
├── latencyscope-urlconnection (android-library)           no deps
├── latencyscope-export        (android-library)           html/csv/json renderers
├── latencyscope-ui-compose    (android-library)           compose BOM compileOnly
├── latencyscope-ui-view       (android-library)           appcompat + recyclerview
├── latencyscope-noop          (android-library)           API-identical no-op
└── sample                     (android-application)       demo of all 4 stacks
```

Consumer wiring:
```kotlin
debugImplementation("io.github.GH_USER:latencyscope-okhttp:0.1.0")
debugImplementation("io.github.GH_USER:latencyscope-ui-compose:0.1.0")
releaseImplementation("io.github.GH_USER:latencyscope-noop:0.1.0")
```

**Binary-compatibility rule:** `latencyscope-noop` must expose byte-for-byte the
same public signatures as core.

> **Decision (Phase 0, verified empirically):** `binary-compatibility-validator`
> cannot be used here. AGP 9.5.2 ships *built-in* Kotlin support — it registers
> the `kotlin` extension itself via `KotlinBaseApiPlugin`, so applying
> `org.jetbrains.kotlin.android` fails with
> *"Cannot add extension with name 'kotlin'"*, and BCV only registers its
> `apiDump`/`apiCheck` tasks when it sees that plugin id. Replacement: a custom
> Gradle task in Phase 7 that extracts public signatures from the compiled
> `release` classes of core and noop and diffs them. This is a better fit
> anyway — it checks cross-module *parity*, not just drift of one module.

---

## 2. Public API surface (frozen in Phase 1)

```kotlin
object Latencyscope {
    @JvmStatic fun init(context: Context, config: LatencyConfig = LatencyConfig())
    @JvmStatic fun start(sessionName: String? = null): SessionHandle
    @JvmStatic fun stop(): LatencySession?
    @JvmStatic fun isRecording(): Boolean
    @JvmStatic fun currentSession(): LatencySession?
    @JvmStatic fun mark(label: String)                 // user-flow marker on timeline
    @JvmStatic fun clear()
    @JvmStatic fun <T> track(route: String, method: String = "GET", block: () -> T): T
    @JvmStatic fun record(record: ApiCallRecord)       // manual/bridge entry point
}
```

```kotlin
data class LatencyConfig(
    val maxRecords: Int = 5_000,                       // ring buffer cap
    val captureQueryParams: Boolean = false,           // redacted by default
    val capturedHeaders: Set<String> = emptySet(),     // allowlist, lowercase
    val routeNormalizer: RouteNormalizer = DefaultRouteNormalizer(),
    val slowThresholdMs: Long = 1_000,
    val warnThresholdMs: Long = 300,
    val autoStart: Boolean = false,
    val persist: Boolean = false,                      // reserved for Room module
    val urlFilter: (String) -> Boolean = { true },     // exclude analytics/CDN noise
)
```

```kotlin
data class ApiCallRecord(
    val id: String,
    val source: Source,            // OKHTTP | KTOR | VOLLEY | URL_CONNECTION | MANUAL
    val rawUrl: String,            // redacted per config
    val route: String,             // normalized: /users/{id}/orders
    val host: String,
    val method: String,
    val statusCode: Int?,          // null on transport failure
    val startedAtEpochMs: Long,
    val totalMs: Long,
    val phases: Phases,
    val requestBytes: Long,
    val responseBytes: Long,
    val protocol: String?,         // http/1.1, h2
    val fromCache: Boolean,
    val retryCount: Int,
    val threadName: String,
    val error: String?,            // exception class + message, no stacktrace
    val networkType: String?,      // wifi / cellular / none at call time
)

data class Phases(
    val dnsMs: Long?, val connectMs: Long?, val tlsMs: Long?,
    val requestHeadersMs: Long?, val requestBodyMs: Long?,
    val serverMs: Long?,           // responseHeadersStart - requestBodyEnd  ← key metric
    val responseHeadersMs: Long?, val responseBodyMs: Long?,
    val queueMs: Long?,            // callStart -> dnsStart (connection-pool wait)
)
```

```kotlin
data class LatencySession(
    val id: String, val name: String,
    val startedAt: Long, val endedAt: Long?,
    val device: DeviceInfo,
    val records: List<ApiCallRecord>,
    val markers: List<Marker>,
    val droppedCount: Int,         // honest reporting when ring buffer overflowed
)
```

```kotlin
data class LatencyReport(
    val session: LatencySession,
    val overall: Stats,
    val routes: List<RouteStats>,  // sorted by totalMs desc
    val hosts: List<HostStats>,
    val errors: List<ErrorGroup>,
)
data class Stats(
    val count: Int, val successCount: Int, val errorCount: Int,
    val avgMs: Double, val minMs: Long, val maxMs: Long,
    val p50: Long, val p75: Long, val p90: Long, val p95: Long, val p99: Long,
    val totalBytes: Long, val phaseAverages: Phases,
)
```

Exporters:
```kotlin
object LatencyExport {
    @JvmStatic fun toHtml(report: LatencyReport): String
    @JvmStatic fun toCsvCalls(report: LatencyReport): String
    @JvmStatic fun toCsvSummary(report: LatencyReport): String
    @JvmStatic fun toJson(report: LatencyReport): String
    @JvmStatic fun writeAll(context: Context, report: LatencyReport): List<File>
    @JvmStatic fun share(context: Context, report: LatencyReport)   // FileProvider
}
```

---

## 3. Phases

### Phase 0 — Project scaffolding
- `git init`, Apache-2.0 `LICENSE`, `.gitignore` audit, `README.md` stub.
- Kotlin: **no plugin needed** — AGP 9.5.2 compiles Kotlin out of the box
  (verified: Kotlin 2.2.10, `src/main/kotlin` source set honoured).
- Rename `:app` → `:sample`, applicationId `io.github.GH_USER.latencyscope.sample`.
- `build-logic` included build, convention plugin `latencyscope.android.library` —
  centralises compileSdk 37, minSdk **21**, `ExplicitApiMode.Strict`, JVM 11
  target, consumer ProGuard, test wiring; publishing block added in Phase 10.
- Create all 9 empty library modules + `settings.gradle.kts` includes.
- **Done when:** `./gradlew assembleDebug` green on empty modules.
- **✅ COMPLETE** — 9 library modules + `:sample` build green; `testDebugUnitTest`
  green; explicit-API strict verified to reject an untyped public declaration.

### Phase 1 — `latencyscope-core`
1. Model classes above (`ApiCallRecord`, `Phases`, `LatencySession`, …).
2. `RecordStore` — lock-free bounded ring buffer (`ArrayBlockingQueue` +
   `AtomicLong droppedCount`). Append must be O(1), allocation-light, no I/O,
   no locks held across the network path.
3. `SessionManager` — start/stop/clear, `@Volatile recording` flag checked first
   in every hot path so disabled cost ≈ one volatile read.
4. `RouteNormalizer` interface + `DefaultRouteNormalizer`:
   - segment is numeric → `{id}`
   - UUID regex → `{uuid}`
   - hex ≥ 16 chars → `{hash}`
   - base64-ish / long opaque ≥ 24 chars → `{token}`
   - user-supplied `Regex -> replacement` rules run first (highest priority)
5. `Redactor` — strips query values unless `captureQueryParams`; always strips
   `authorization`, `cookie`, `set-cookie`, `x-api-key`, `proxy-authorization`;
   masks userinfo in URL.
6. `Aggregator` — exact percentiles via sort (nearest-rank), per route / host /
   overall, error grouping by `statusCode|exceptionClass`.
7. `DeviceInfo` collector — manufacturer, model, SDK int, app versionName/Code,
   locale. Network type via `ConnectivityManager` (no permission needed for
   `NetworkCapabilities` on 28+; degrade to `null` if unavailable).
8. `Latencyscope` facade + `LatencyConfig`.
- **Tests:** normalizer table-driven, percentile math vs known fixtures,
  ring-buffer overflow + drop count, redaction, concurrent append (stress 8
  threads × 10k records, assert no loss beyond cap and no exception).
- **Done when:** core unit tests green.
- **✅ COMPLETE** — 57 unit tests across 6 suites, all green.
  Deviations from the sketch above, all deliberate:
  - Facade object is `LatencyScope` (not `Latencyscope`).
  - Instrumentation modules submit a `RawCall`; core owns redaction, route
    normalization, host parsing and id assignment, so all four stacks produce
    identical records and the logic is unit-tested once.
  - `Clock` interface injected (`setClockForTesting`) — durations use
    `SystemClock.elapsedRealtime`, never wall-clock, which jumps mid-session.
  - `persist` dropped from `LatencyConfig` until the Room module actually
    exists; a config flag that does nothing is worse than no flag.
  - `UrlParts` uses `java.net.URI`, not `android.net.Uri`, so URL handling is
    covered by JVM unit tests.

### Phase 2 — `latencyscope-okhttp`
- `LatencyEventListener : okhttp3.EventListener` capturing every callback
  timestamp via `SystemClock.elapsedRealtimeNanos()`; `EventListener.Factory`
  wrapping any existing user listener (delegation, never replacement).
- `LatencyInterceptor` (application-level) for status, byte counts, `fromCache`,
  `priorResponse` retry depth; network-level variant optional.
- Correlation: `Call` identity map (`IdentityHashMap` guarded, cleared on
  `callEnd`/`callFailed`) — must not leak on cancelled calls.
- `OkHttpClient.Builder.withLatencyscope()` extension + `LatencyOkHttp.attach()`
  for Java callers.
- **Edge cases tested:** redirects (multiple `callStart` phases), retries,
  connection reuse (no DNS/TLS events), cancellation, h2 multiplexing,
  WebSocket calls excluded, cached responses.
- **Done when:** MockWebServer tests assert phase values within tolerance.
- **✅ COMPLETE** — 14 MockWebServer tests, green on three consecutive runs.
  Deviations from the sketch above, all deliberate:
  - **No interceptor.** `EventListener` already exposes status
    (`responseHeadersEnd`), byte counts (`requestBodyEnd`/`responseBodyEnd`),
    protocol, and cache outcome (`cacheHit`/`cacheConditionalHit`). Adding an
    interceptor would duplicate all of it and create a second place for the
    two to disagree. Retry depth comes from counting `requestHeadersStart`
    callbacks, which also covers route retries that `priorResponse` misses.
  - **No identity map.** OkHttp builds a fresh `EventListener` per call via the
    factory, so per-call state lives in fields. The leak risk the plan worried
    about cannot occur: there is no map entry for a cancelled call to strand.
  - `connectMs` has TLS subtracted out, since OkHttp's connect span contains
    the handshake — leaving it in would double-count in the stacked bar.
  - Redirect/retry phases are **summed** across exchanges within one call, and
    the record keeps the final status, so one caller-visible call is one row
    that still accounts for every hop's cost.

### Phase 3 — `latencyscope-ktor`
- `ClientPlugin("Latencyscope")` hooking `SendPipeline.Before` /
  `ReceivePipeline.After`, timing via coroutine-safe attribute key on
  `HttpRequestBuilder.attributes`.
- Engine-agnostic (CIO/Android/OkHttp). When engine *is* OkHttp and the OkHttp
  module is present, prefer the richer EventListener data and suppress the Ktor
  record to avoid double counting (dedupe by request attribute marker).
- **Done when:** MockEngine tests + one real CIO test green.
- **✅ COMPLETE** — 13 tests (MockEngine, real CIO, real OkHttp engine).
  Deviations from the sketch above, all deliberate:
  - Implemented on the `Send` hook of `createClientPlugin`, which wraps the
    whole exchange, rather than raw `SendPipeline`/`ReceivePipeline` phases.
  - **Dedupe is an explicit `deferToEngine` flag, not auto-detection.** There
    is no reliable way from the plugin to tell whether *this* client's OkHttp
    engine actually has our EventListener installed. Guessing "yes" when it is
    not produces a silently empty report, which is the worse failure: a
    duplicate row is visible and fixable, a missing one is not. Both the
    deduped and the duplicated case are covered by tests.
  - Phases are left empty for Ktor records. No Ktor engine exposes DNS/TLS or
    server think time, and emitting zeros would render as "instant" in the
    stacked bar. `Source.hasFullPhaseBreakdown` lets the renderer omit the bar.
  - Response size comes from `Content-Length`; reading the body to measure it
    would consume the stream before the caller sees it.

### Audit of Phases 0-3 (done before Phase 4)

`./gradlew clean assembleDebug testDebugUnitTest lintDebug` — 85 unit tests
green, **0 lint errors** across all 10 modules. Seven issues found and fixed:

| # | Issue | Fix |
|---|---|---|
| 1 | Lint `MissingPermission` ×3 in `NetworkTypeProvider` | Runtime gate was already there but invisible to lint; annotated `@SuppressLint` with the reason |
| 2 | `RecordStore.add` could spin forever — two threads trimming, one polls null while `count` is transiently inflated | Break out of the trim loop on a null poll |
| 3 | OkHttp listener fields not `@Volatile`; async calls hand events between caller and dispatcher threads | All mutable fields `@Volatile`, terminal write guarded by `AtomicBoolean`; new `AsyncCallTest` covers 16 enqueued calls |
| 4 | `finished` flag was a plain `Boolean` — a cancel delivers both `canceled` and `callFailed` | `AtomicBoolean.compareAndSet` |
| 5 | `LatencyConfig.capturedHeaders` and `Redactor.redactHeader` were dead — nothing records headers | Removed until Phase 5 adds a header field to `ApiCallRecord`; same rule already applied to `persist` |
| 6 | `clear()` also wiped `lastSession`, which the KDoc did not say | KDoc corrected to match behaviour |
| 7 | Stale androidx versions in the catalog | Bumped core-ktx, appcompat, material, test-ext, espresso |

Deliberately **not** changed:
- **OkHttp stays at 4.12.0 and Ktor at 3.2.0** in the catalog. Both are
  `compileOnly`, so these are the *minimum supported* versions, not the
  latest. Compiling against the oldest API we promise to support is what stops
  a consumer still on OkHttp 4 from hitting `NoSuchMethodError`.
- **Gradle 9.6.0** — the 9.9.2 nudge is informational; upgrading the wrapper
  mid-phase buys nothing.
- `setClockForTesting` / `initForTesting` remain public. They are named for
  what they are, and hiding them behind `@VisibleForTesting` would pull
  `androidx.annotation` into every consumer of the core module.

Known gaps, all scheduled: `:sample` has no UI yet (Phase 8), six modules
still hold a `Placeholder.kt` (Phases 4-7), no CI and no publishing (Phase 10).

### Phase 4 — `latencyscope-volley` + `latencyscope-urlconnection`
- Volley: `LatencyHttpStack(delegate: BaseHttpStack)` timing `executeRequest`,
  plus `LatencyRequestQueue.create(context)` convenience.
- URLConnection: `Latencyscope.wrap(connection)` returning a delegating
  `HttpURLConnection` that times connect / first-byte / stream drain; counts
  bytes via wrapped streams. Documented explicitly as lower-fidelity.
- **Done when:** instrumented tests against MockWebServer for both.

### Phase 5 — `latencyscope-export`
- `CsvWriter` — RFC-4180 escaping. Two files: `calls.csv`, `summary.csv`.
- `JsonWriter` — hand-rolled minimal writer (keeps core dependency-free; no
  kotlinx-serialization requirement forced on consumers).
- `HtmlReportRenderer` — single self-contained file, inline CSS + vanilla JS,
  **zero network requests**, dark/light via `prefers-color-scheme`, works
  offline in any browser and on mobile width. Sections:
  1. Header — session name, device, app version, start/end/duration, network.
  2. Summary cards — total calls, success/error, error %, total bytes,
     overall p50/p95/p99, slowest route, dropped-record warning if any.
  3. Route table — sortable, filterable, colour-coded by threshold:
     route · method · count · avg · min · p50 · p90 · p95 · p99 · max ·
     err% · req bytes · resp bytes · total time share %.
  4. Phase breakdown — stacked horizontal bars per route
     (queue/DNS/connect/TLS/request/**server**/response), pure CSS flex, no lib.
  5. Waterfall timeline — absolute-positioned divs on a time axis, user markers
     from `mark()` drawn as vertical lines.
  6. Latency distribution histogram per route (expandable).
  7. Full call log — expandable rows with every field, status badge, error text.
  8. Footer — library version, generation timestamp, methodology note
     (how percentiles computed, what `serverMs` means).
  - All data injected as one inline `JSON.parse(...)` blob, HTML-escaped;
    rendering done by the inline JS. **XSS test required** — a URL containing
    `</script>` must not break the page.
- `writeAll` → `context.cacheDir/latencyscope/<session>/` ; `share` via
  `FileProvider` with a library-local authority
  `${applicationId}.latencyscope.fileprovider`, manifest-merged automatically.
- **Done when:** golden-file tests for CSV, HTML renders a fixture session,
  escaping tests pass.

### Phase 6 — UI modules
- `latencyscope-ui-compose`: `LatencyReportScreen(report)` — summary cards,
  route list, call detail bottom sheet, filter/sort chips, export FAB.
  Material 3. Compose deps `compileOnly` + documented.
- `latencyscope-ui-view`: `LatencyReportActivity` + `LatencyReportFragment`
  with ViewBinding/RecyclerView, same feature set.
- `Latencyscope.showReport(context)` launches whichever is on the classpath
  (reflection-free: each UI module registers itself via its own entry point;
  facade documents both explicit launchers).
- Optional notification shown while recording (debug only) with stop + share
  actions.
- **Done when:** sample renders both UIs.

### Phase 7 — `latencyscope-noop`
- Mirror every public class/function; bodies empty, returning benign defaults.
- `apiCheck` in CI diffs core `.api` vs noop `.api` — build fails on drift.
- **Done when:** sample `releaseImplementation` compiles unchanged and R8
  strips calls.

### Phase 8 — `sample` app
- Compose app, 4 tabs: OkHttp/Retrofit, Ktor, Volley, HttpURLConnection.
- Each fires a mix against a real public API (`https://httpbin.org`,
  `https://jsonplaceholder.typicode.com`): fast GETs, slow endpoint
  (`/delay/2`), 404, 500, timeout, large payload, parallel burst.
- Start/Stop recording button, `mark()` buttons for flow stages, view report in
  Compose UI + View UI, export & share.
- **Done when:** manual run produces a correct HTML report on-device.

### Phase 9 — Hardening & performance
- Overhead benchmark: 10k recorded calls vs baseline, assert added per-call cost
  < 50 µs and allocation bounded; JMH-style loop in instrumented test.
- Leak check (LeakCanary in sample) on cancelled/failed calls.
- R8/ProGuard consumer rules per module; verify minified sample.
- Thread-safety review of every mutable field.
- Strict mode clean — no disk/network on main thread.
- `kover` coverage gate ≥ 80 % on core.

### Phase 10 — Docs & release
- `README.md`: what/why, 30-second quickstart per stack, screenshots of HTML
  report, config table, overhead numbers, limitations table per stack.
- `docs/` — route normalization, privacy & redaction, report metric
  definitions, migration/compat policy, FAQ.
- `CHANGELOG.md` (Keep a Changelog), `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  issue/PR templates.
- `vanniktech/gradle-maven-publish-plugin` → **Central Portal** (OSSRH is
  retired), GPG signing, POM metadata, sources + javadoc jars.
- GitHub Actions:
  - `ci.yml` — build, unit tests, `apiCheck`, lint, detekt on PR + main.
  - `instrumented.yml` — emulator tests (API 28 + 34) on main.
  - `release.yml` — on tag `v*`: publish to Central, attach sample APK,
    generate release notes.
- JitPack fallback documented for `0.1.0`.
- Tag `v0.1.0`, publish, write announcement snippet.

---

## 4. Risks & decisions

| Risk | Decision |
|---|---|
| Double counting when Ktor runs on OkHttp engine | Dedupe via request attribute; OkHttp record wins |
| `URL.setURLStreamHandlerFactory` global hook | **Rejected** — JVM-wide one-shot, breaks OkHttp. Explicit wrapper instead |
| Unbounded memory | Ring buffer cap + `droppedCount` surfaced in report |
| Leaking tokens into report | Redact-by-default; allowlist opt-in; documented |
| Release-build overhead | `noop` artifact + API-equality CI gate |
| Percentile accuracy vs memory | Exact sort over bounded set — simple and correct at app scale |
| No BCV under AGP 9 built-in Kotlin | Custom signature-diff task in Phase 7 (see Phase 0 decision) |
| Streams-based byte counting changes behaviour | Only wrap when consumer opts in; never buffer whole body |

## 5. Definition of done (v0.1.0)
Four stacks instrumented · HTML + 2 CSV + JSON export · Compose and View UI ·
noop release artifact with enforced API parity · sample app exercising
everything · unit + instrumented tests green in CI · published to Maven
Central · README with report screenshots.
