# LatencyScope — Android API latency monitoring library

**Measure, break down and report API latency in an Android app.** LatencyScope
records every network call your app makes — through **OkHttp, Retrofit, Ktor,
Volley or `HttpURLConnection`** — while you use the app, and writes a readable
**HTML, CSV and JSON latency report** when you stop recording. Kotlin, `minSdk
21`, no backend and no account.

> **Status: pre-release.** Everything works and is tested — the engine, all
> four HTTP stacks, the HTML/CSV/JSON exports, both in-app debug UIs, the
> API-identical no-op for release builds and the sample app. 262 tests,
> 0 lint issues. What is left is the `v0.1.0` tag itself.
>
> [Docs](docs/) · [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md)
> · [Plan](PLAN.md)

## Why

Most tooling tells you a request took `450 ms`. LatencyScope tells you *where*
those 450 ms went — connection-pool wait, DNS, TCP, TLS, request upload,
**server think time**, response download — aggregated per normalized route with
p50/p90/p95/p99, so a slow screen becomes an actionable number.

It answers the questions you actually have when a screen feels slow:

- **Is it us or the backend?** Server think time is a column of its own.
- **Which endpoint?** Calls are grouped by route, with `/users/4821` and
  `/users/9f3c…` collapsed into `/users/{id}` automatically.
- **How bad, and for whom?** p50/p90/p95/p99 per route, split by network type
  (wifi / cellular), not one average that hides the tail.
- **Is this number good?** Every route gets a plain-English verdict against a
  published scale, so you don't need to already know what "good" looks like.
- **Is anything failing quietly?** Error rate and failure count per route.

No SDK key, no dashboard, no data leaving the device. The report is a file.

## Supported HTTP clients

| Your stack | Module | One-line setup | Phase breakdown |
|---|---|---|---|
| **OkHttp** / **Retrofit** | `latencyscope-okhttp` | `OkHttpClient.Builder().installLatencyScope()` | Full — DNS, TCP, TLS, request, server, response |
| **Ktor** client (any engine) | `latencyscope-ktor` | `install(LatencyScopeKtorPlugin)` | Total time |
| **Volley** | `latencyscope-volley` | wrap your `BaseHttpStack` | Total time |
| **`HttpURLConnection`** | `latencyscope-urlconnection` | wrap the connection | Total time |

Using more than one? Install more than one — they record into the same
session. See [Other HTTP stacks](#other-http-stacks) and
[What each stack can see](#what-each-stack-can-see).

## Install

> **Not published yet.** `v0.1.0` is in preparation. Until it reaches Maven
> Central, build it yourself — `./gradlew publishToMavenLocal` and add
> `mavenLocal()` to your repositories, or wire the repo in as a
> [composite build][composite].

[composite]: https://docs.gradle.org/current/userguide/composite_builds.html

Take only the modules you need. Each HTTP module pulls in `latencyscope-core`;
nothing else is required.

```kotlin
dependencies {
    // the stack you use — one or more
    debugImplementation("io.github.bhargavsavasani:latencyscope-okhttp:0.1.0")

    // reading the report
    debugImplementation("io.github.bhargavsavasani:latencyscope-export:0.1.0")      // HTML / CSV / JSON
                                                                            // + the recording notification
    debugImplementation("io.github.bhargavsavasani:latencyscope-ui-compose:0.1.0")  // optional in-app screen

    // and nothing at all in release
    releaseImplementation("io.github.bhargavsavasani:latencyscope-noop:0.1.0")
}
```

That last line is the point of the library's shape: see
[Keeping it out of release builds](#keeping-it-out-of-release-builds). If you
would rather ship the real thing in release, that is safe too — an installed
but non-recording LatencyScope costs one volatile read per call.

`latencyscope-export` is the one to take if you want the recording
notification — `LatencyNotification` lives there, next to the writers it
hands the report to.

**Requirements:** `minSdk 21`, Java 11, AndroidX. OkHttp, Ktor, Volley and
Compose are `compileOnly` — the library compiles against the oldest version it
supports and uses whichever one *your* app already ships, so it never drags a
version into your build or overrides yours.

## Modules

| Module | Purpose |
|---|---|
| `latencyscope-core` | Models, recorder, route normalization, redaction, aggregation |
| `latencyscope-okhttp` | OkHttp / Retrofit instrumentation (full phase breakdown) |
| `latencyscope-ktor` | Ktor client plugin (any engine) |
| `latencyscope-volley` | Volley `BaseHttpStack` wrapper |
| `latencyscope-urlconnection` | `HttpURLConnection` wrapper |
| `latencyscope-export` | HTML / CSV / JSON report renderers |
| `latencyscope-ui-compose` | Compose debug report screen |
| `latencyscope-ui-view` | View-based debug report screen |
| `latencyscope-noop` | API-identical no-op for release builds |
| `sample` | Demo app exercising all four HTTP stacks |

## Quick start

Three steps. The whole thing, start to finished report, with no manual
`start()` or `stop()` anywhere.

**1 — wire it up once, in `Application.onCreate`:**

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the notification first, so the session that autoStart
        // begins is announced rather than missed.
        LatencyNotification.install(this)
        LatencyScope.init(this, LatencyConfig(autoStart = true))
    }
}
```

**2 — instrument the HTTP client you already have:**

```kotlin
val client = OkHttpClient.Builder()
    .installLatencyScope()          // pass your existing EventListener.Factory
    .build()                        // if you have one: .installLatencyScope(mine)
```

Using Ktor, Volley or `HttpURLConnection` instead? One line each —
see [Other HTTP stacks](#other-http-stacks).

**3 — run the app.**

A notification appears saying it is recording. Use the app. Press **Stop &
report**, then tap the notification to open the report.

That is the whole integration. Everything below is detail.

---

Want to mark the stages of a flow so the timeline is readable?

```kotlin
LatencyScope.mark("cart opened")
LatencyScope.mark("checkout submitted")
```

Prefer to control the window yourself? Drop `autoStart`, and bracket the flow:

```kotlin
LatencyScope.start("checkout")
// ... use the app ...
val report = LatencyScope.report(LatencyScope.stop())
```

Leaving the instrumentation installed in release builds is safe: with no
session recording, each callback performs one volatile read and returns.

## How a session works

A session is a window of recording. Everything else — the report, the HTML,
the CSV — comes from one.

There are two ways to open that window, and **one way to close it**.

### Start it yourself

```kotlin
LatencyScope.start("checkout")
// ... use the app ...
val report = LatencyScope.report(LatencyScope.stop())
```

Full control, no notification needed. Good for an automated test, or when you
want a session to cover exactly one flow.

### Or let it start on launch

```kotlin
LatencyScope.init(this, LatencyConfig(autoStart = true))
```

Recording begins the moment the app does, so nothing is missed — including
everything that happens during startup, which is usually the part you cannot
get at any other way.

### Either way, a notification is how you stop it

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LatencyNotification.install(this)                          // before init
        LatencyScope.init(this, LatencyConfig(autoStart = true))
    }
}
```

Whenever a session starts — by `start()` or by `autoStart` — a notification
appears:

```
┌──────────────────────────────────────────────┐
│  Recording · checkout                        │
│  1m 12s · 47 calls · 2 failed                │
│                             [ Stop & report ]│
└──────────────────────────────────────────────┘
```

It updates once a second while the session runs. Press **Stop & report**
once. The same row becomes a progress state while the report is rendered —
a busy session takes a second or two — and then the finished report:

```
┌──────────────────────────────────────────────┐
│  Writing report…                             │
│  checkout · 47 calls                         │
│  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
└──────────────────────────────────────────────┘
```

then:

```
┌──────────────────────────────────────────────┐
│  Latency report ready                        │
│  Tap to open, or share all four files.       │
│                                     [ Share ]│
└──────────────────────────────────────────────┘
```

Tapping opens the HTML report. **Share** sends all four files wherever you
like. Every path is also printed to logcat the moment the report is written:

```
$ adb logcat -s LatencyScope
I/LatencyScope: ──── Latency report written ────
I/LatencyScope: Session   : checkout
I/LatencyScope: Calls     : 47 (2 failed)
I/LatencyScope: Folder    : /storage/emulated/0/Android/data/your.app/files/report/checkout
I/LatencyScope: HTML      : /storage/emulated/0/Android/data/your.app/files/report/checkout/latency-checkout-20261125-143022.html
I/LatencyScope: Summary   : …-summary.csv
I/LatencyScope: Calls CSV : …-calls.csv
I/LatencyScope: JSON      : ….json
I/LatencyScope: Pull it   : adb pull /storage/emulated/0/Android/data/your.app/files/report/checkout
```

All four are listed, not just the HTML: the CSVs go into a spreadsheet and
the JSON into a script, and guessing a filename from a directory is busywork
when the writer already knows it.

**Stop means one thing.** Whether you press the notification's button or call
`LatencyScope.stop()` yourself, the session ends, the report is written and
the notification offers it. There is no second kind of stop that quietly
skips the report.

Install `LatencyNotification` **before** `init` if you use `autoStart` —
otherwise the session `init` starts has already begun and its notification is
missed.

### Why a notification, and not stopping automatically

Because no library can know when your flow ended. Pressing Home in the middle
of a checkout is not the end of the checkout, and an export triggered there
would cut the session in half. Worse, there is no dependable hook for an app
*exiting* at all: `Application.onTerminate` is never called on a real device,
and a swipe-away, an out-of-memory kill or a crash destroys the process
without running any of your code — so "write the report when the app closes"
loses reports silently.

The notification solves both. Recording stays visible the whole time it is
running, so it is never on by accident, and the decision to stop belongs to
the only party who knows when the flow is over.

It is **not** a foreground service. Recording happens inside your process
while it is already alive, so there is nothing to keep running — which spares
you the `FOREGROUND_SERVICE` permissions and service-type declarations
Android 14 demands.

### Notification permission

`POST_NOTIFICATIONS` is declared by `latencyscope-export`, so there is nothing
to add to your manifest. On Android 13+ it is a runtime permission, and the
library never requests it on your behalf — prompting your users is your
decision, not a debug tool's. Ask when it suits you:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
}
```

Deny it and nothing breaks: the session records exactly as before, it is just
invisible. `LatencyNotification.areNotificationsAllowed(context)` tells you
which case you are in.

### Where the files go

One location, always:

```
/storage/emulated/0/Android/data/<your.package.name>/files/report/<session name>/
    latency-<session>-20261125-143022.html
    latency-<session>-20261125-143022-summary.csv
    latency-<session>-20261125-143022-calls.csv
    latency-<session>-20261125-143022.json
```

External app storage, so you can reach a report with `adb pull`, over USB, or
from the device's Files app — with **no permission**, and it is removed when
the app is uninstalled. Internal cache would be none of those things:
invisible from a desktop, and the system may delete it at any moment, which is
the wrong promise for a file you are about to attach to a ticket.

The folder is named after the session. Recording `"checkout"` twice puts both
runs in `report/checkout/` rather than overwriting the first, because every
filename already carries the time the session started. The ten most recent
session folders are kept.

This is the only place LatencyScope writes. `LatencyExport.writeAll`,
`share()`, `shareAsync()` and the notification's **Stop & report** all land
here, so "where is my report" has one answer. `LatencyExport.reportsRoot(context)`
returns the directory if you want it in code.

To pull them into your project:

```bash
./gradlew :sample:pullLatencyReports     # → <repo>/reports/
```

### Customising it

```kotlin
LatencyNotification.install(
    this,
    NotificationConfig(
        smallIcon = R.drawable.ic_stat_latency,
        stopLabel = "Finish run",
        keepLastSessions = 25,
        directory = File(cacheDir, "latency"),      // where reports land
        openIntent = { files -> myOwnViewer(files.html) },
    ),
)
```

In release builds `latencyscope-noop` makes `install()` register no listener,
no receiver and no handler, post nothing and start no thread.

## Other HTTP stacks

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

## What gets recorded

A latency report is meant to be shared — attached to a ticket, dropped in a
chat, mailed to a backend team — so the library assumes everything it stores
will be read by someone it was not written for.

**Redacted before it is ever stored**, not filtered at render time, because
the in-memory session is itself shareable:

- **Query values are masked by default.** `?token=abc&page=2` is stored as
  `?token=***&page=***`. Set `captureQueryParams = true` to keep them, and
  even then a known-sensitive key (`token`, `api_key`, `password`,
  `signature`, `code`, and a dozen more) is still masked.
- **Credentials in the URL are dropped** — `https://user:pass@host/` loses the
  `user:pass` entirely.
- **Fragments are dropped.**

**Never recorded at all:** request and response bodies, headers, cookies, and
stack traces. A failure is stored as its exception class and message, nothing
more. Sizes are counted, never contents.

**Routes are normalized** before aggregation, so `/users/8311/orders` and
`/users/9022/orders` become one row: `/users/{id}/orders`. Numeric ids
(`{id}`), UUIDs (`{uuid}`), long hex digests (`{hash}`) and opaque blobs like
JWTs or signed-URL cursors (`{token}`) are all recognised, and a file
extension survives normalization — `/images/12345.jpg` becomes
`/images/{id}.jpg` rather than losing the suffix. Add your own rules,
or replace the normalizer outright:

```kotlin
LatencyConfig(
    routeNormalizer = DefaultRouteNormalizer(
        rules = listOf(
            DefaultRouteNormalizer.Rule(Regex("/v[0-9]+/"), "/{version}/"),
        ),
    ),
)
```

If a whole host should never be recorded, exclude it:

```kotlin
LatencyScope.init(
    this,
    LatencyConfig(urlFilter = { url -> "analytics.example.com" !in url }),
)
```

Nothing leaves the device. The library has no network code of its own, no
uploads and no analytics; a report goes where *you* send it.

Full detail, including what is never recorded and what to check before
forwarding a report: **[docs/privacy.md](docs/privacy.md)**. For routes that
the defaults get wrong — slugs, mostly — see
**[docs/route-normalization.md](docs/route-normalization.md)**.

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

### It tells you whether the numbers are good

The report opens with a verdict in words, not a table:

```
OVERALL
Slow
Noticeable to users. Worth investigating.

Judged on p95 = 1.4 s — 19 of every 20 calls were faster than that.
A typical call took 180 ms, and the average was 240 ms.

Where to look first: GET /checkout — 1.8 s at p95 (slow), over 6 calls.
94% of the time is Server. The server is doing the waiting. This is a
backend question, not something the app can fix.
```

That last line is the part that saves an argument: backend time and
connection setup look identical in a total and go to completely different
people, so the report says which it is.

Ratings use the scale commonly published for API response times — **under
200 ms** excellent, **500 ms** good, **1 s** acceptable, **2 s** slow, above
that poor — and the report prints the scale, so a reader who looks the
numbers up finds them agreeing rather than contradicting.

Two things worth knowing about how to read it:

- **The verdict is on p95, not the average.** Ninety-nine calls at 150 ms and
  one that hangs for 8 seconds average out to 228 ms, which reads as healthy
  while one person in a hundred watched a spinner for eight seconds. The
  average is shown — it is the number people expect — and immediately
  qualified.
- **These ratings describe a whole round trip from the phone.** Published
  figures for a *backend* are stricter, because they exclude the radio, DNS
  and TLS the device pays for first. Compare those against the report's
  **Server** column, not the total.

Change the scale with `LatencyConfig(ratingScale = LatencyRatingScale(...))`;
the report always prints whichever one it was rated against. Every metric is
defined in **[docs/metrics.md](docs/metrics.md)**.

### It explains its own numbers

The report is written to be forwarded — to a backend team, into a ticket, at
someone who has never heard of a percentile. So it defines every term it
uses, inline: the percentile columns carry their meaning on hover, the
summary tiles read "1 call in 20 was slower than this" rather than just
"p95", and a **How to read this** section at the end covers every percentile,
every phase of a call, and why the report shows no average at all.

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

Both ready-made activities draw edge to edge and inset themselves clear of
the status bar, navigation bar and display cutout, and both follow the
system's light/dark setting. `LatencyReportScreen` and `LatencyReportView`
deliberately do **not** apply insets — embedded in your own screen, that is
your layout's job, and doing it in both places would double the gap.

Both show the same thing — summary, routes, calls and failures with a filter,
expandable detail and the phase breakdown — and both render in light and dark.
Slow latencies carry a `!` and an accessibility label as well as a colour, so
the warning survives a greyscale screenshot or a colour-blind reader.

## Keeping it out of release builds

Leaving the instrumentation installed in release is already cheap — one
volatile read per call — but `latencyscope-noop` removes it entirely:

```kotlin
debugImplementation("io.github.bhargavsavasani:latencyscope-okhttp:0.1.0")
debugImplementation("io.github.bhargavsavasani:latencyscope-ui-compose:0.1.0")
debugImplementation("io.github.bhargavsavasani:latencyscope-export:0.1.0")

releaseImplementation("io.github.bhargavsavasani:latencyscope-noop:0.1.0")
```

The no-op mirrors the public API of **every** module, not just core, so
`installLatencyScope()`, `LatencyExport.shareAsync()` and
`LatencyReportActivity.intent()` all still compile in release and none of them
do anything. Nothing moves to a debug-only source set.

A build task, `:latencyscope-noop:apiCheck`, compares the two surfaces
declaration by declaration and fails on any drift in either direction, so the
drop-in claim cannot quietly stop being true.

With R8 enabled and your own packages in scope, what survives in a release
build is the handful of classes a manifest names, which R8 treats as roots —
the two report activities — plus `LatencyReportFragment`, kept by a consumer
rule because the system re-creates fragments by name after process death.

The sample's minified release APK contains 5 classes under
`io.github.latencyscope` against 329 in its debug APK, and two of those five
are the sample's own `MainActivity` and `Application`. No library code
survives.

## What each stack can see

| Stack | Total | Status | Sizes | Phase breakdown |
|---|---|---|---|---|
| OkHttp / Retrofit | ✅ | ✅ | ✅ wire bytes | ✅ queue, DNS, connect, TLS, request, **server**, response |
| `HttpURLConnection` | ✅ | ✅ | ✅ counted as read | partial — connect, **server**, response body |
| Ktor | ✅ | ✅ | declared `Content-Length` | ✗ no engine exposes it |
| Volley | ✅ | ✅ | declared length | ✗ one opaque stack call |

Where a phase cannot be measured it is reported as **absent**, never as zero —
"instant" and "unknown" are different facts.

## The sample app

`:sample` is a Compose app with a tab per stack — OkHttp, Ktor, Volley,
`HttpURLConnection` — and the same seven scenarios behind each: a fast GET, a
two-second endpoint, a 404, a 500, a client-side timeout, a ~1 MB payload and a
burst of six parallel calls. Start recording, fire a few, drop `mark()`s as you
go, then open the report in either UI or export and share it.

```bash
./gradlew :sample:installDebug
```

It calls `https://httpbin.org` and `https://jsonplaceholder.typicode.com`, so it
needs network. Those are real endpoints on purpose: a loopback mock has no DNS,
no TLS handshake and no server think-time, which is most of what the report is
for.

Retrofit has no tab because it does not need one — it issues every call through
the `OkHttpClient` you hand it, so `installLatencyScope()` on that client is all
there is to it, and the OkHttp tab is already showing you the result.

The app is also the no-op's proof: nothing in it branches on the build type, and
the release variant compiles against `latencyscope-noop` instead of the real
modules without a line changing.

## Overhead

`submit` runs on the thread that just finished the HTTP call, so whatever it
spends is added to that call's real latency. The budget is **50 µs per call**,
asserted by an instrumented benchmark against a 10,000-call loop with the
not-recording path subtracted as the baseline:

```bash
./gradlew :latencyscope-core:connectedDebugAndroidTest   # needs a device
```

A second test, `RecordingCostTest`, runs on the JVM in CI and gates the part
that holds on any machine: per-call cost must not grow with session length.
When not recording, the cost is one volatile read.

The record store is bounded by `maxRecords` (5,000 by default). Past that the
oldest record is dropped and counted, and the report says how many — a report
that silently loses data is worse than one that admits it.

## Documentation

| Doc | Read it when |
|---|---|
| [Report metrics](docs/metrics.md) | A number needs interpreting — what p95 means, what "Server" covers, why there is a verdict |
| [Privacy and redaction](docs/privacy.md) | Before sharing a report, or before deciding what to ship |
| [Route normalization](docs/route-normalization.md) | The route table has one row per call |
| [Compatibility](docs/compatibility.md) | Upgrading, or wondering what counts as a breaking change |
| [FAQ](docs/faq.md) | Everything else |
| [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Releasing](docs/releasing.md) | |

## Build

```bash
./gradlew assembleDebug
./gradlew check            # tests, lint, API parity, coverage floor
```

Requires JDK 17. Library modules target `minSdk 21`, `compileSdk 37`.

`:latencyscope-core` carries an 80 % line-coverage floor (`coverageVerify`).
The sample has a third build type, `staging`: release-shaped minification with
the *real* library linked, which is the only configuration where a missing
consumer ProGuard rule can show up.

```bash
./gradlew :sample:assembleStaging
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
