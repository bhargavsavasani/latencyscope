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
- **Done when:** tests against MockWebServer for both.
- **✅ COMPLETE** — 22 tests (10 Volley, 12 `HttpURLConnection`), all green as
  JVM unit tests rather than instrumented ones. Notes:
  - Volley tests run under **Robolectric**: `Request`'s constructor calls
    `android.net.Uri` and `TextUtils`, which stubbed defaults cannot satisfy.
    Pinned to `sdk = [34]` (Robolectric 4.14 ships no SDK 37 runtime) and the
    deprecated `HttpStack` interface drags in `org.apache.http` signatures, so
    httpclient is on the test classpath.
  - **Unit tests now run on a pinned JDK 17 toolchain.** The Gradle daemon
    here starts on JDK 25 and Robolectric's bytecode reader rejects class file
    major version 69. Pinning also stops a developer's JDK from changing the
    result relative to CI.
  - Test tasks get a 10-minute Gradle timeout and the connection tests a
    30-second JUnit `Timeout` rule, after a wrapper test hung the build.
  - Volley records **each retry attempt separately**, because Volley retries
    above the stack. Collapsing them would hide a retry storm, which is
    exactly what the report should expose.
  - `HttpURLConnection` records once, at whichever comes first: body fully
    read, stream closed, `disconnect()`, or failure. Phases are limited to
    connect / server / response-body — the API exposes nothing below the
    socket.
  - No global `URL.setURLStreamHandlerFactory` hook, as decided in Phase 0.

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
- **✅ COMPLETE** — 42 tests. Notes:
  - **Everything is rendered server-side in Kotlin**; the inline script only
    adds sorting, filtering, tooltips and the theme toggle. The report is
    complete and readable with scripting disabled, and there is one source of
    truth per number instead of a Kotlin copy and a JavaScript copy.
  - **Phases collapse to five buckets** in the stacked bar — Wait, Connect
    (DNS+TCP+TLS), Request, Server, Response — on the first five categorical
    colour slots, in chronological order. Nine series would exceed what any
    validated categorical palette can separate; all nine raw values remain in
    the call detail and in both CSVs.
  - Palette validated with the data-viz validator in **both modes**: every
    hard gate passes (worst adjacent CVD ΔE 9.1 light / 8.4 dark, normal
    vision 19.6 / 19.3). Three light-mode hues sit below 3:1 contrast, so the
    **relief rule** applies and the bar always ships direct numeric labels
    plus the full route table.
  - Latency status is never colour alone: the slow band carries a warning
    glyph and the number is always present.
  - JSON escapes `<`, `>` and `&` as `\u00xx`, plus U+2028/9. A recorded URL
    containing a closing script tag therefore cannot break out of the
    embedded data block — asserted by counting script elements in the output.
  - CSV writes CRLF per RFC 4180, and an unmeasured byte count is a **blank
    cell, not 0**, so a spreadsheet average is not dragged down by it.
  - Files land in `cacheDir/latencyscope/<session>/` and share through a
    library-local FileProvider authority (`${'$'}{applicationId}.latencyscope.fileprovider`),
    so the consumer adds nothing to their manifest.
  - `SampleReportWriterTest` writes a realistic 88-call report to
    `build/reports/latencyscope-sample.html` for eyeballing in a browser.
    Verified structurally: well-formed tags, waterfall bars inside the axis,
    stacked segments summing to 100%, embedded JSON parses, 194 kB.

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
- **Done when:** both UIs render a report and are covered by tests.
- **✅ COMPLETE** — 32 tests (10 Compose, 22 View). Notes:
  - A shared `LatencyFormat` / `PhaseBreakdown` was extracted into
    `latencyscope-export` first, so a duration cannot read as `1.5 s` in the
    HTML and `1500 ms` in the UI. The HTML renderer now delegates to it.
  - **Compose needs the Compose compiler plugin even under AGP 9's built-in
    Kotlin** — `buildFeatures.compose = true` alone fails with "the Compose
    Compiler Gradle plugin is required". Applying
    `org.jetbrains.kotlin.plugin.compose` works and, unlike `kotlin-android`,
    does not clash with the built-in `kotlin` extension.
  - The View module uses `resourcePrefix = "latencyscope_"` and a `merge`
    root, so it adds no nesting and cannot collide with consumer resources.
  - `ListAdapter` diffs **inline** rather than on a background thread. Rows
    are a few thousand pre-computed strings, and an inline diff keeps the list
    in step with the filter being typed instead of lagging a frame behind.
  - Compose tests run under Robolectric with a tall window qualifier
    (`w480dp-h2400dp`); the default screen is too short for a `LazyColumn` to
    compose the rows below the summary tiles, which looks exactly like a
    missing row.
  - Accessibility carried through both UIs: the slow band is prefixed with
    `!`, every latency has a content description naming its band, and phase
    segments are labelled with name and duration — three of the five hues sit
    below 3:1 contrast on a light surface.
  - Both report activities are `exported="false"`: a report carries internal
    hostnames and route shapes.

### Audit of Phases 0-6 (done before Phase 7)

`./gradlew clean assembleDebug testDebugUnitTest lintDebug` — **185 unit tests
green, 0 lint errors** across all 10 modules. Structure, module graph, manifests
and the root layout are as planned. Nine issues found and fixed:

| # | Severity | Issue | Fix |
|---|---|---|---|
| 1 | **Security** | Stored XSS in the HTML report. A `data-tip` value is read with `getAttribute` and assigned to `innerHTML`, so it is decoded **twice**; escaping once left `&lt;img onerror=…&gt;` alive after the first decode. A `mark()` label or a recorded route could run script in whoever opened the report | `escForTip` = escape twice, applied to every `data-tip`; two regression tests assert the attribute survives a simulated browser decode |
| 2 | **Security** | CSV formula injection — a cell starting `=`, `+`, `-` or `@` executes in Excel/Sheets, and cells hold recorded URLs and server error text | `defuseFormula` prefixes an apostrophe, skipping anything that parses as a number so `-40` stays a figure |
| 3 | Performance | `NetworkTypeProvider.current()` ran a `getNetworkCapabilities` binder round trip **per recorded call**, on the caller's network thread — the exact overhead this library exists to measure | 1-second TTL cache; steady state is one monotonic read plus one volatile read |
| 4 | Correctness | `LatencyExport.share` writes four files (~200 kB of HTML) synchronously, and both UI modules called it straight from a click handler → main-thread I/O, ANR risk | New `shareAsync` renders on a daemon thread and opens the chooser on the main one; both UIs and the README now use it |
| 5 | Concurrency | `LatencyHttpURLConnection`'s phase and byte fields were plain `var` while the request is set up on one thread and the body drained on another — the same defect fixed in the OkHttp listener in the previous audit, missed here | All mutable fields `@Volatile` |
| 6 | Performance | `PhaseBarView.onDraw` allocated a `RectF` per segment per frame (lint `DrawAllocation`) | Preallocated and reused |
| 7 | Dead API | `LatencyScopeKtorConfig.recordHttpErrors` was public, documented and **never read**. Its stated meaning is not even representable: core derives success from `statusCode` | Removed, per the rule already applied to `persist` and `capturedHeaders` |
| 8 | Dead API | `Redactor.ALWAYS_SENSITIVE_HEADERS` was public and unused, with a comment promising Phase 5 would consume it. Phase 5 shipped without header capture | Removed, with a comment saying why it comes back with the feature |
| 9 | Plan deviation | `latencyscope-ui-compose` took Compose as `implementation`, pushing its BOM onto every consumer — Phase 6 specified `compileOnly` | Switched to `compileOnly`, matching OkHttp/Ktor; documented in the README |

Also fixed while in there: the OkHttp listener built a `RawCall` and a `Phases`
on every call even with no session recording — now gated on `isRecording()`
first, so a disabled library really does cost one volatile read. Ktor no longer
copies a request body (`ByteArrayContent.bytes()`, `String.toByteArray()`) just
to measure it; only declared lengths are read.

Deliberately **not** changed:
- **Volley still calls `request.body` to size the payload**, which re-serialises
  it. `Content-Length` is the only alternative and Volley does not set it in
  `getHeaders()`, so reading headers would report "unknown" for every POST. The
  cost is now stated in the KDoc rather than hidden.
- **`RecordStore.count` can drift from the queue** if `clear()` races an
  in-flight `add()`. The drift is bounded and self-correcting within a few
  appends, and every alternative puts a lock or an O(n) size call on the hot
  path.
- **Versions stay pinned.** The lint "newer version available" notices are all
  for `compileOnly` floors (OkHttp 4.12, Ktor 3.2) or for test-only tooling.
- **`:sample` is still an empty shell** and six modules have no production code
  beyond their package — those are Phases 7 and 8, not regressions.

Known gaps, all scheduled: no `latencyscope-noop` body and no API-parity gate
(Phase 7), no sample app (Phase 8), no consumer ProGuard rules, overhead
benchmark or coverage gate (Phase 9), no CI, docs or publishing (Phase 10).

### Phase 7 — `latencyscope-noop`
- Mirror every public class/function; bodies empty, returning benign defaults.
- `apiCheck` in CI diffs core `.api` vs noop `.api` — build fails on drift.
- **Done when:** sample `releaseImplementation` compiles unchanged and R8
  strips calls.
- **✅ COMPLETE** — **639 public declarations mirrored across eight modules**,
  7 behaviour tests, `apiCheck` green. Notes:
  - **Scope: everything, not just core.** §1's own wiring example swaps
    `-okhttp` and `-ui-compose` for `-noop`, so a release build of shared code
    calling `installLatencyScope()` or `LatencyExport.shareAsync()` has to keep
    compiling. Mirroring core alone would have made the documented wiring fail
    on a consumer's first release build. The no-op therefore takes
    `compileOnly` dependencies on OkHttp, Ktor, Volley, Compose and AppCompat —
    compile-time only, so swapping it in adds nothing at runtime.
  - **The gate reads Kotlin metadata, not bytecode.** The JVM has no
    `internal`: a plain `javap`-style dump would have demanded the no-op
    reimplement `Recorder`, `CsvWriter`, `UrlParts` and every other
    implementation class. `kotlin-metadata-jvm` recovers the real Kotlin
    visibility and ASM pulls the `@Metadata` annotation off each class without
    loading it. Nested types are judged by *reachability*, not their own
    modifier, so a `Companion` inside an `internal class` is excluded.
  - Compared: class names, supertypes, enum entries, constructors and member
    JVM descriptors. Supertypes are included because a consumer can hand
    `LatencyReportActivity` to something expecting an `AppCompatActivity`.
    Top-level declarations are qualified by package, or two modules' own
    `installLatencyScope` would satisfy each other.
  - **ViewBinding classes are the one exclusion**, documented in the task: they
    are generated from layout XML rather than authored, and mirroring them
    would mean shipping fake layouts to generate fakes from.
  - **The gate was tested in both directions** — adding a method to
    `core.Marker`, then adding one to the no-op's `Marker` — and each fails the
    build naming the exact signature. A gate that has never failed is not a gate.
  - **The mirrors are real delegating no-ops, not stubs.** `LatencyCallListener`
    still forwards every OkHttp callback to a listener the app installed
    itself, so a client built through `installLatencyScope()` behaves exactly
    as one built without LatencyScope. `LatencyVolley.stack()` returns the
    delegate itself rather than wrapping it, and `LatencyUrlConnection.wrap()`
    returns the caller's own connection: with nothing to measure, interposing
    would cost calls for no reason.
  - `track(url, method) { ... }` still runs the block and still lets its
    exception propagate. The measurement is optional; the caller's work is not.
  - **Both report activities are declared in the no-op's manifest** and finish
    in `onCreate`, so a `startActivity(LatencyReportActivity.intent(ctx))` left
    in release code is a no-op rather than an `ActivityNotFoundException`.
    This is the one deliberate cost — see the R8 numbers.
  - **Verified end to end in `:sample`.** Every source file of the sample
    compiles into both build types and between them they touch every entry
    point; real modules on `debug`, the no-op on `release`. Debug APK: **329
    LatencyScope classes**. Minified release APK: **4** — the sample's own
    `MainActivity` and `SampleApp`, plus the two no-op report activities, which
    R8 must keep because the manifest is a root. Every library class is gone.
    (Phase 7 landed this as a dedicated `LatencyScopeWiring.kt` probe; Phase 8
    replaced it with the real app, which exercises strictly more.)
  - Two `:sample` changes were needed to make that check real. Its release
    `optimization.packageScope` omitted `io.github.**`, so R8 was leaving
    LatencyScope untouched; and `minSdk` moved 28 → 30 because
    `ktor-client-core` declares identifiers containing spaces, which DEX only
    permits from version 040 unless R8 renames them away. The second is a
    constraint of demoing Ktor un-minified, not of LatencyScope — the library
    modules remain `minSdk 21`.

### Phase 8 — `sample` app
- Compose app, 4 tabs: OkHttp/Retrofit, Ktor, Volley, HttpURLConnection.
- Each fires a mix against a real public API (`https://httpbin.org`,
  `https://jsonplaceholder.typicode.com`): fast GETs, slow endpoint
  (`/delay/2`), 404, 500, timeout, large payload, parallel burst.
- Start/Stop recording button, `mark()` buttons for flow stages, view report in
  Compose UI + View UI, export & share.
- **Done when:** manual run produces a correct HTML report on-device.
- **✅ COMPLETE** — Compose app, 4 tabs × 7 scenarios, 7 unit tests, 0 lint
  issues, both build types green. Notes:
  - **No Retrofit dependency, deliberately.** Retrofit is a typed layer *over*
    OkHttp and issues every call through the `OkHttpClient` it is given, so the
    OkHttp tab already covers it exactly. Adding Retrofit would demo Retrofit,
    not a second thing LatencyScope does.
  - **Real endpoints, not a loopback mock.** A local server has no DNS, no TLS
    handshake and no server think-time — it would exercise precisely the phases
    the library exists to show. The cost is that the scenarios need network.
  - **A 404 and a 500 are completed calls, not failures**, in all four tabs.
    That takes work per stack: Ktor needs `expectSuccess = false`, Volley
    delivers them through `deliverError` and the status has to be rescued from
    `error.networkResponse`, and HttpURLConnection serves their body on
    `errorStream` — reading `inputStream` instead throws and would record a
    transport error that never happened.
  - **Volley retries are turned off** (`DefaultRetryPolicy(timeout, 0, 1f)`).
    Volley retries by default, and a silent retry puts a second recorded call
    in the report with nothing to explain it.
  - Clients are **built once and kept**. A fresh client per run means a cold
    connection pool, and every call would show a TLS handshake that a real app
    would not have paid for. They are closed in a `DisposableEffect`, which is
    also what Phase 9's leak check will exercise.
  - The burst uses `async`/`awaitAll` rather than a loop: pool contention only
    appears as queue and connect time if the calls actually overlap.
  - **Not verified:** the "correct HTML report **on-device**" half of the
    done-when. What is verified is that both build types assemble, 198 unit
    tests and `check` pass, lint is clean, and the release APK contains four
    LatencyScope classes. Running the app against the live endpoints and
    opening the exported HTML is a manual step that needs a device.

### Phase 9 — Hardening & performance
- Overhead benchmark: 10k recorded calls vs baseline, assert added per-call cost
  < 50 µs and allocation bounded; JMH-style loop in instrumented test.
- Leak check (LeakCanary in sample) on cancelled/failed calls.
- R8/ProGuard consumer rules per module; verify minified sample.
- Thread-safety review of every mutable field.
- Strict mode clean — no disk/network on main thread.
- `kover` coverage gate ≥ 80 % on core.
- **✅ COMPLETE** — 217 unit tests, core line coverage **84.7 %** (floor 80 %),
  one real race fixed. Notes:
  - **JaCoCo, not Kover.** Kover 0.9.1 discovers what to instrument through the
    standalone Kotlin plugin, which AGP 9's built-in Kotlin never applies: it
    produced a report of **zero classes**, and an empty report satisfies every
    threshold. That is a gate that cannot fail — the same trap as
    binary-compatibility-validator in Phase 0. `coverageVerify` therefore runs
    JaCoCo directly and asserts its own class set is non-empty before checking
    the ratio. It was proved to fail by raising the floor to 95 %.
  - **The floor found a real gap, not just a number.** `LatencyConfig.Builder`
    — public API, the entire Java entry point — was at **0 % coverage**, as
    were `DeviceInfoCollector` and `NetworkTypeProvider`. Covering them took
    core from 81.6 % to 84.7 % and documented two behaviours that were
    previously only implied: that an app without `ACCESS_NETWORK_STATE` gets a
    null label rather than a crash, and that the network type is cached.
  - **Branch coverage is 53.9 %** and is *not* gated. Most uncovered branches
    are SDK-level forks (`Build.VERSION.SDK_INT >= M`) that a single Robolectric
    SDK cannot reach both sides of. Gating a number that honest testing cannot
    move would only invite `@Suppress`.
  - **Thread-safety review, every mutable field.** One real defect:
    `CountingInputStream` guarded its "already reported" flag with a plain
    check-then-set `Boolean`, while reads happen on the body thread and
    `close()` can arrive from another — a cancel, or a watchdog calling
    `disconnect()`. Two threads could both pass the check and record the call
    **twice**. Now an `AtomicBoolean.compareAndSet` with a volatile counter.
    The regression test reproduces the old failure reliably over 200 trials.
    Everything else was already sound: `LatencyScope`'s fields are all
    `@Volatile` and written under its lock, `RecordStore` is a
    `ConcurrentLinkedQueue` with atomic counters, the OkHttp and
    HttpURLConnection listeners are fully volatile, and the UI fields are
    main-thread only.
  - **Consumer ProGuard rules are now real, and two of them are load-bearing.**
    Most modules need none — and the files say so explicitly, with the
    reasoning, because a blanket `-keep` pins classes in every consumer's
    release build and defeats what the no-op exists for. `latencyscope-ui-view`
    needs two: `LatencyReportFragment`, which the `FragmentManager` re-creates
    by name after process death, and `PhaseBarView`, which is inflated from
    layout XML. Removing the first and rebuilding makes the class **disappear
    from the minified APK** — a consumer would have seen the screen work until
    their process was killed, then crash with `ClassNotFoundException`. The
    `PhaseBarView` rule is belt and braces: AGP generates an equivalent rule
    from merged resources, but a library should not depend on that.
  - **A new `staging` build type is what makes any of that checkable.**
    `release` links the no-op, so R8 there only ever proves the no-op
    disappears — it can never exercise a keep rule, because nothing is left to
    keep. `staging` is release-shaped (minified, obfuscated, **not**
    debuggable, since R8 skips renaming for debuggable builds) with the real
    modules linked. That is the configuration a shipping consumer is in.
  - **Overhead is measured in two places for two different reasons.**
    `OverheadBenchmark` is instrumented and owns the 50 µs/call budget, because
    that number is only meaningful on ART on a phone — it is written and
    compiles, but **needs a device and has not been run here**.
    `RecordingCostTest` runs on the JVM in CI and gates the part that is
    machine-independent: per-call cost must not grow with session length.
    Measured locally at 2,000 calls vs 20,000: **4.2 µs → 2.4 µs per call**
    (it falls as the JIT warms), ratio 0.6 against a ceiling of 4.0. The
    regression it exists to catch — making `RecordStore.add` scan, or
    `buildRecord` consult the records so far — is invisible in a test that
    records ten calls and fatal in an app recording for an hour.
  - **StrictMode is on in the sample** whenever the variant is debuggable,
    before `LatencyScope.init` so it sees initialisation too. `penaltyLog`
    rather than `penaltyDeath`: a violation raised inside a third-party
    dependency on an untested device would turn the sample into a
    crash-on-launch. **LeakCanary** is wired in as `debugImplementation`.
    Neither has been exercised here — both need a device.
  - **Known wart, deliberately not changed:** `LatencyConfig` is a `data class`
    whose `routeNormalizer` and `urlFilter` defaults are a fresh object and a
    fresh lambda, so `LatencyConfig() != LatencyConfig()`. Fixing it would
    change published equality semantics; it is recorded here and worked around
    in the test rather than altered.

### Audit of Phases 0-9 (done before Phase 10)

A release audit rather than a bug hunt: the question was whether a stranger
arriving at the GitHub page could install this, understand it, and trust it.
**220 tests, 0 failures, 0 lint issues, core line coverage 84.7 %.**

| # | Area | Finding | Fix |
|---|---|---|---|
| 1 | **Public API** | 13 declarations were public that no documented workflow needs — `RecordStore` (its cap is set through `LatencyConfig.maxRecords`) and `Redactor` (nothing constructs one). Every public type is a permanent commitment the day the library is released, and each one also has to be mirrored in the no-op forever | Both `internal`. Parity surface 639 → **626** |
| 2 | **Correctness** | Volley's `DEPRECATED_GET_OR_POST` was labelled `"GET"` unconditionally. Volley itself picks POST when the request carries a body, so a legacy POST was filed under the wrong method — and split one route into two rows | Apply Volley's own rule, reusing the body size already measured. The test fails on the old code |
| 3 | **Integration** | Every link in the chain had tests; the chain had none. A defect in the joins — a route key the renderer cannot find, a redaction applied too late to reach the file — would have shipped | `EndToEndReportTest` in `:sample`: real MockWebServer calls through OkHttp, out to HTML/JSON/CSV, asserting route collapsing, phase data, and that a `token=` value reaches **none** of the three formats |
| 4 | **Docs** | The README had no install section — the Gradle coordinates were buried 175 lines down inside a section about *removing* the library. The modules table was still headed "Planned", and "Usage today" read like a work log | Install, Requirements and Modules moved to the top; headings rewritten for a reader rather than an author |
| 5 | **Docs** | Nothing stated what is recorded. For an instrumentation library that is the first question anyone asks, and the answer was only discoverable by reading `Redactor.kt` | A "What gets recorded" section: query masking, credential and fragment stripping, the never-recorded list, route normalization, `urlFilter`, and that nothing leaves the device. Every claim was checked against the code, and all are already pinned by existing tests |
| 6 | **Docs** | The R8 figure said 4 surviving classes; Phase 9's new `LatencyReportFragment` keep rule made it 5 | Re-measured and corrected, with the reason the fragment is kept |
| 7 | Readability | `statusCode = error?.let { null } ?: lastKnownStatus()` — a `let` used to mean "if not null, then null" | `if (error != null) null else lastKnownStatus()` |
| 8 | Java interop | `initForTesting` was the one defaulted public function without `@JvmOverloads` | Added, in core and the mirror |
| 9 | Docs | `LatencyConfig` documented three of its seven properties with `@property`; `routeNormalizer`, the two thresholds and `autoStart` were bare | Completed, including that the thresholds colour the report and never change what is recorded |

Checked and found correct, so deliberately unchanged:
- **Percentiles.** Nearest-rank over a sorted array, guarded for empty input
  and out-of-range arguments; always returns a value that actually occurred.
- **`api` vs `implementation`.** Every instrumentation module exposes
  `latencyscope-core` with `api`, so `LatencyScope` resolves for a consumer
  who depends only on `latencyscope-okhttp`. A single `implementation` here
  would have made the README's first example fail to compile.
- **Code hygiene.** No `TODO`, `FIXME`, `!!`, `printStackTrace`, stray logging
  or empty `catch` blocks anywhere in the library.
- **Repo hygiene.** `local.properties`, `.idea/`, `build/` and `.kotlin/` are
  all untracked.
- **Thread safety.** Re-confirmed after Phase 9: every mutable field is
  `@Volatile`, atomic, or confined to one thread.
- **`LatencyConfig` equality** still has the data-class wart from Phase 9
  (fresh normalizer and lambda defaults). Changing it alters published
  semantics; it stays documented rather than quietly fixed.

**Follow-up after running the sample on a device (same audit round):** the
screenshot showed the title drawn under the camera cutout and the last card
under the navigation bar.

| # | Area | Finding | Fix |
|---|---|---|---|
| 10 | **UI, affects every consumer** | Neither report screen handled window insets. Android 15 draws every app edge to edge and ignores the opt-out, so the first row of the report sat under the status bar and the last under the navigation bar — in any app that shipped either screen, not just the sample | `enableEdgeToEdge()` plus `safeDrawingPadding()` in the Compose activity; an `OnApplyWindowInsetsListener` in the View activity. Four Robolectric tests, which fail against the old code |
| 11 | **UI, affects every consumer** | The Compose report activity wrapped itself in a bare `MaterialTheme {}`, which is **always light**, while the report's accent palette already followed the system. In dark mode that produced dark-mode accents on light surfaces — and the README claimed both UIs render in light and dark | An explicit light/dark scheme chosen from `isSystemInDarkTheme()`. The KDoc claimed it inherited "the host app's Material theme", which a separate Activity cannot do; corrected too |
| 12 | Sample UI | Header and tab row were hand-rolled, with no inset handling and no feedback while a ten-second scenario ran | Rebuilt on `Scaffold` + `TopAppBar` with `WindowInsets.safeDrawing`, a progress bar while a scenario is in flight, a spinner in the pressed button, an empty-state for the log, toast confirmation on `mark()`, and a recording dot that reads in greyscale |

Insets belong to the **host**, not the embeddable composable or view: a
consumer who drops `LatencyReportScreen` into their own `Scaffold` already has
padding applied, and padding it twice is as wrong as not padding it at all.
Only the ready-made activities apply it.

Known gaps, all Phase 10: no CHANGELOG, CONTRIBUTING, CODE_OF_CONDUCT, issue
or PR templates, no CI workflows, no publishing config — and the README's
`io.github.GH_USER` coordinates are a labelled placeholder until the real
group id is chosen.

### Zero-setup recording and a richer report (after the audit)

Asked for: start the session automatically, write the report when the app
stops, land it in a `reports/` directory, and put more context in the report.

**What was already there:** `LatencyConfig.autoStart` — built in Phase 1,
wired in `init`, and never covered by a behavioural test. The flag worked;
nothing proved it. It also went *unhonoured* by `initForTesting`, so a
consumer testing their own integration would have seen it silently do
nothing. Both fixed, and the new test was checked against a reverted
implementation to be sure it is not vacuous.

**Where the request could not be met as stated:** there is no reliable hook
for an app exiting. `Application.onTerminate` is never called on a real
device, and swipe-away, OOM kill, force-stop and crash all destroy the
process without running any user code. An "on exit" export would lose reports
silently — the worst failure here, because it is only discovered when the
data is missing. The export therefore runs on the **foreground → background
transition**, the last moment guaranteed to execute. For a person it is the
same instant, and it still works when the process is killed a minute later.

Likewise there is no "project root" on a device. The app writes to
`getExternalFilesDir("latencyscope")` — no permission, readable over adb — and
a new `:sample:pullLatencyReports` Gradle task pulls the folders into
`<repo>/reports/`, which is what the request actually described.

| Piece | Decision |
|---|---|
| `LatencyAutoReport` | In `latencyscope-export`, since it needs both the recorder and the writers. `install(application)`, `uninstall`, `writeNow`, `defaultRoot` |
| Background detection | `Application.registerActivityLifecycleCallbacks` and a started-activity count — **no new dependency**. `ProcessLifecycleOwner` would have put `lifecycle-process` in every consumer's graph for two callbacks |
| Rotation | A configuration change stops the old activity *after* starting the new one, so the count never reaches zero and no report is written. Covered by a test |
| Session handling | The session is **not** stopped. Each background refreshes that session's files, so a flow split across two visits stays one flow and nothing is lost |
| Retention | Ten session folders, oldest deleted. A library meant to be left switched on would otherwise fill the device one background at a time |
| Threading | A daemon single-thread executor. Blocking a lifecycle callback is precisely what a latency library must not do — which also meant the tests had to wait for the write rather than assume it |

**Report additions**

| Addition | Note |
|---|---|
| App display name | `appId` was the package name; `DeviceInfo.appName` is what the user sees |
| Transport at session start, locale | Context for the header |
| OS-declared link speed | `NetworkCapabilities` downstream/upstream kbps, labelled **estimate** everywhere it appears — it describes the radio's capability, and is routinely wrong by an order of magnitude |
| **Measured throughput** | Derived from bytes and `responseBodyMs` — the window where bytes were actually moving. Deliberately **null** for stacks that cannot split that phase: dividing by `totalMs` would blame a fast download for a slow server. Session figure is byte-weighted, so one tiny response cannot drag it around |
| **Stats split by network** | p50/p95/max/errors per transport. The most diagnostic of the four — it separates "the app is slow" from "the connection is slow". Calls with no readable transport are left out entirely rather than bucketed as "unknown", since `ACCESS_NETWORK_STATE` is optional and never requested on the consumer's behalf |

All of it is in HTML, JSON and CSV, mirrored in the no-op (parity 626 → 665),
and absent from a minified release build — the sample's release APK still
contains 5 LatencyScope classes against 351 in debug.

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
