# LatencyScope

Drop-in Android library that records how long your app's API calls actually
take — while you use the app — and produces a detailed HTML and CSV latency
report when you stop recording.

> **Status: pre-release.** Phases 0-6 complete: the `latencyscope-core`
> engine, all four HTTP stacks, the HTML/CSV/JSON exports and both in-app
> debug UIs. Remaining: the no-op release artifact, the sample app, hardening
> and publishing.
> See [PLAN.md](PLAN.md) for the full phased implementation plan.

## Why

Most tooling tells you a request took `450 ms`. LatencyScope tells you *where*
those 450 ms went — connection-pool wait, DNS, TCP, TLS, request upload,
**server think time**, response download — aggregated per normalized route with
p50/p90/p95/p99, so a slow screen becomes an actionable number.

## Planned modules

| Module | Purpose |
|---|---|
| `latencyscope-core` | ✅ Models, recorder, route normalization, redaction, aggregation |
| `latencyscope-okhttp` | ✅ OkHttp / Retrofit instrumentation (full phase breakdown) |
| `latencyscope-ktor` | ✅ Ktor client plugin (any engine) |
| `latencyscope-volley` | ✅ Volley `BaseHttpStack` wrapper |
| `latencyscope-urlconnection` | ✅ `HttpURLConnection` wrapper |
| `latencyscope-export` | ✅ HTML / CSV / JSON report renderers |
| `latencyscope-ui-compose` | ✅ Compose debug report screen |
| `latencyscope-ui-view` | ✅ View-based debug report screen |
| `latencyscope-noop` | API-identical no-op for release builds |
| `sample` | Demo app exercising all four HTTP stacks |

## Usage today (OkHttp)

```kotlin
// once, in Application.onCreate
LatencyScope.init(this)

val client = OkHttpClient.Builder()
    .installLatencyScope()          // pass your existing EventListener.Factory
    .build()                        // if you have one: .installLatencyScope(mine)

// around the flow you care about
LatencyScope.start("checkout")
LatencyScope.mark("cart opened")
// ... use the app ...
val report = LatencyScope.report(LatencyScope.stop())
```

Leaving the instrumentation installed in release builds is safe: with no
session recording, each callback performs one volatile read and returns.

### Ktor

```kotlin
val client = HttpClient(CIO) {
    install(LatencyScopeKtorPlugin)
}
```

Ktor records the total round trip, status and declared sizes — no engine
exposes DNS/TLS/server phases, so those are reported as absent rather than as
zero. If you run Ktor **on the OkHttp engine** and install
`installLatencyScope()` on that engine's `OkHttpClient`, set
`deferToEngine = true` so the call is recorded once, by the layer that can see
the phase breakdown:

```kotlin
install(LatencyScopeKtorPlugin) { deferToEngine = true }
```

### Volley

```kotlin
val queue = LatencyVolley.newRequestQueue(context)
// or wrap a stack you already build:
Volley.newRequestQueue(context, LatencyHttpStack(HurlStack()))
```

Volley retries *above* the stack, so each attempt is recorded as its own call
— a retry storm stays visible instead of being averaged away.

### HttpURLConnection

```kotlin
val connection = LatencyUrlConnection.open(URL("https://api.example.com/users"))
connection.inputStream.use { ... }
```

There is no global install hook on purpose: `URL.setURLStreamHandlerFactory`
can be set only once per process and claiming it breaks OkHttp.

## Getting the report out

```kotlin
val report = LatencyScope.report(LatencyScope.stop()) ?: return

LatencyExport.shareAsync(context, report)  // render + write off the main thread, then share
val files = LatencyExport.writeAll(context, report)   // blocking — call off the main thread
val html = LatencyExport.toHtml(report)   // or render in memory
```

`writeAll` produces four files in `cacheDir/latencyscope/<session>/`:

| File | What it is |
|---|---|
| `*.html` | The readable report — one self-contained file, no network requests |
| `*-summary.csv` | One row per route: counts, percentiles, average phase times |
| `*-calls.csv` | One row per call, every phase as its own column |
| `*.json` | The whole report, machine-readable (same document embedded in the HTML) |

Sharing goes through a FileProvider bundled with the library, so there is
nothing to add to your manifest.

Rendering and writing is real work — a busy session produces a few hundred
kilobytes of HTML — so `shareAsync` does it on a background thread and opens
the chooser on the main one. `share` and `writeAll` are the blocking forms;
call those only from a thread where blocking is fine.

Text in the exports is treated as hostile. Recorded URLs, routes and `mark()`
labels are escaped for the context they land in, including the double decode a
tooltip attribute goes through, and CSV cells that begin with `=`, `+`, `-` or
`@` are quoted so a spreadsheet shows them instead of executing them.

### What the HTML report contains

Summary tiles &middot; sortable, filterable route table with p50/p90/p95/p99
&middot; stacked phase bars showing where each route's time actually goes
&middot; a waterfall timeline with your `mark()` labels &middot; per-route
latency histograms &middot; failures grouped by status or exception &middot;
an expandable log of every call &middot; a methodology note explaining how
each number was derived.

It opens offline, works with JavaScript disabled, renders in light and dark,
and prints. Unmeasurable phases are shown as absent, and slow latencies carry
a glyph as well as a colour, so the report survives being printed in
greyscale or read by a colour-blind reviewer.

## Looking at the report in the app

`latencyscope-ui-compose` takes Compose as `compileOnly`, so it uses whatever
Compose version your app already has rather than forcing its own BOM on you.
Your app supplies `androidx.compose.ui`, `foundation`, `material3` and
`activity-compose`; if you are calling `LatencyReportScreen` you have them.

Compose:

```kotlin
val report = LatencyScope.report()
if (report != null) {
    LatencyReportScreen(report, onShare = { LatencyExport.shareAsync(context, report) })
}
// or the ready-made screen:
startActivity(io.github.latencyscope.ui.compose.LatencyReportActivity.intent(context))
```

Views:

```kotlin
startActivity(io.github.latencyscope.ui.view.LatencyReportActivity.intent(context))
// or embed it anywhere:
reportView.setReport(report)
reportView.onShare = { LatencyExport.shareAsync(context, report) }
```

Both show the same thing — summary, routes, calls and failures with a filter,
expandable detail and the phase breakdown — and both render in light and dark.
Slow latencies carry a `!` and an accessibility label as well as a colour, so
the warning survives a greyscale screenshot or a colour-blind reader.

## What each stack can see

| Stack | Total | Status | Sizes | Phase breakdown |
|---|---|---|---|---|
| OkHttp / Retrofit | ✅ | ✅ | ✅ wire bytes | ✅ queue, DNS, connect, TLS, request, **server**, response |
| `HttpURLConnection` | ✅ | ✅ | ✅ counted as read | partial — connect, **server**, response body |
| Ktor | ✅ | ✅ | declared `Content-Length` | ✗ no engine exposes it |
| Volley | ✅ | ✅ | declared length | ✗ one opaque stack call |

Where a phase cannot be measured it is reported as **absent**, never as zero —
"instant" and "unknown" are different facts.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Requires JDK 17. Library modules target `minSdk 21`, `compileSdk 37`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
