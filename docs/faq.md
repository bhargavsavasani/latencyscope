# FAQ

### Is it safe to leave this in a release build?

It is safe, but the recommended wiring does not. The real modules go on
`debug`, and `latencyscope-noop` on `release` — an API-identical no-op, so
every call site still compiles and does nothing. The sample's minified release
APK holds 5 LatencyScope classes against 365 in debug.

If you do ship the real library, see [privacy.md](privacy.md): the recording
and the report are then in users' hands.

### What does it cost when it is not recording?

One volatile read. Every recording entry point checks a single `@Volatile`
field first and returns, before building a record or reading a clock. That is
what makes it safe to leave the instrumentation installed.

When it *is* recording, the budget is under 50 µs per call, and per-call cost
must not grow with session length — both are gated by tests. See
[Overhead](../README.md#overhead).

### Why is my phase breakdown empty?

Because your stack cannot produce one. Only OkHttp exposes every phase;
`HttpURLConnection` gives connect, server and response body; Ktor and Volley
give none, because no engine exposes them. The report omits the bar rather
than drawing zeros, which would read as "instant".

### Why does the route table have one row per call?

Ids the default normalizer does not recognise — usually slugs. See
[route normalization](route-normalization.md).

### Where is my report?

```
Android/data/<your app>/files/report/<session name>/
```

Every path is also printed to logcat under the tag `LatencyScope` when the
report is written, including the `adb pull` line to copy.

### Can I stop the session from code instead of the notification?

Yes — `LatencyScope.stop()`. Stop means one thing whoever triggers it: the
report is written either way.

### Does backgrounding the app end the session?

No, deliberately. An earlier version exported on the foreground → background
transition and it answered the wrong question: backgrounding is not the end of
a flow, so pressing Home mid-checkout cut the session in half. A session ends
when you end it.

There is also no reliable "app exited" hook on Android.
`Application.onTerminate` is never called on a device, and a swipe-away, an
OOM kill or a crash destroys the process without running any of your code.

### Why is the verdict based on p95 and not the average?

Ninety-nine calls at 150 ms plus one 8-second hang averages to a healthy-looking
228 ms. See [why the verdict is not the average](metrics.md#why-the-verdict-is-not-the-average).

### A published scale says 200 ms is excellent. Does that apply to my p95?

It applies to the whole round trip from the phone, which is what this report
measures. Published *backend* figures are stricter, because a backend's number
excludes the radio, DNS, TLS and the download — compare those against the
**Server** column instead. See [the verdict](metrics.md#the-verdict).

### Does it work with Retrofit?

Yes, through `latencyscope-okhttp`. Retrofit is a typed layer over OkHttp and
issues every call through the `OkHttpClient` you give it, so instrumenting the
client covers Retrofit exactly. There is no separate module, and the sample
deliberately does not add one.

### Does it record WebSockets, or gRPC?

No. WebSocket calls are excluded from the OkHttp instrumentation — a
long-lived connection has no "latency" in the sense the report means. gRPC is
not instrumented.

### Is any of it sent anywhere?

No. There is no network code in the library, and the HTML report makes no
network requests — no fonts, no scripts, no images.

### Why is there a notification?

So that recording is visible while it is happening, and ends when you decide
it does rather than when the library guesses. It also carries the live call
count, which is usually the first thing that tells you the instrumentation is
actually wired up.

It is not a foreground service: recording runs in your app's own live process,
so there is nothing to keep alive — which avoids the permissions and
service-type declarations Android 14 requires.

### Notification permission is denied. What happens?

Recording works exactly as before and is simply invisible. The `notify` call
is guarded; a debug tool must never be why an app crashes. Stop the session
from code instead.

### Can I change what counts as "slow"?

Yes — `LatencyConfig.ratingScale`. It changes wording and colour only, never
what is recorded. See [the verdict](metrics.md#the-verdict).

### Why are there two report UIs?

One for Compose, one for Views, so adding a report screen does not mean adding
a UI toolkit. Both are optional; the HTML file needs neither.

### Why no Kover / binary-compatibility-validator?

Both discover what to work on through the standalone Kotlin Gradle plugin,
which AGP 9's built-in Kotlin never applies. Kover produced a report of zero
classes — and an empty coverage report satisfies every threshold, which is a
gate that cannot fail. Both were replaced with something that can: JaCoCo with
an explicit non-empty assertion, and a custom Kotlin-metadata signature diff.
