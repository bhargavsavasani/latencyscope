# LatencyScope

Drop-in Android library that records how long your app's API calls actually
take — while you use the app — and produces a detailed HTML and CSV latency
report when you stop recording.

> **Status: pre-release.** Phases 0-2 complete: the `latencyscope-core` engine
> and OkHttp/Retrofit instrumentation. Ktor, Volley and `HttpURLConnection`
> support, the HTML/CSV reports and the debug UIs are still to come.
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
| `latencyscope-ktor` | Ktor client plugin (any engine) |
| `latencyscope-volley` | Volley `BaseHttpStack` wrapper |
| `latencyscope-urlconnection` | `HttpURLConnection` wrapper |
| `latencyscope-export` | HTML / CSV / JSON report renderers |
| `latencyscope-ui-compose` | Compose debug report screen |
| `latencyscope-ui-view` | View-based debug report screen |
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

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Requires JDK 17. Library modules target `minSdk 21`, `compileSdk 37`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
