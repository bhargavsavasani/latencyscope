# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

What "breaking" means here is set out in
[docs/compatibility.md](docs/compatibility.md) — in short, the published
surface of `latencyscope-core` and its mirror in `latencyscope-noop`.

## [Unreleased]

## [0.1.0] — 2026-11-25

First public release.

### Added

- **Four HTTP stacks instrumented.** OkHttp (and therefore Retrofit) with a
  full per-phase breakdown; `HttpURLConnection` with connect, server and
  response-body; Ktor as a client plugin on any engine; Volley as a
  `BaseHttpStack` wrapper. What each one can actually see is tabulated in the
  README, rather than implied.
- **A report that states a verdict.** Every latency is rated against one
  published scale — 200 ms / 500 ms / 1 s / 2 s — and the report opens with
  the rating in words, the route worth looking at first, and what kind of
  problem it is, inferred from the dominant phase.
- **Four output formats from one aggregation**: a self-contained HTML page
  that makes no network requests, a per-route CSV, a per-call CSV, and JSON.
  They cannot disagree about a number because they share the aggregated
  report.
- **A recording notification** with live elapsed time, call count and failure
  count, and a *Stop & report* action that writes all four files and offers
  to open or share them.
- **`autoStart`**, for measuring app startup, where there is no later moment
  to call `start()`.
- **Two in-app report screens**, one Compose and one View-based, both handling
  window insets and both rendering in light and dark.
- **`latencyscope-noop`** — an API-identical no-op for release builds. A
  build-time gate diffs the Kotlin-visible public surface of the real modules
  against the mirror, so the two cannot drift.
- **Redaction by default**: query values, credentials and URL fragments are
  stripped before a record is stored, and nothing leaves the device.

### Known limitations

- The per-call overhead budget (< 50 µs) is asserted by an instrumented
  benchmark that needs a device; it has not been run on hardware here.
- Ktor and Volley cannot report a phase breakdown, because no engine exposes
  one. Those records carry totals and sizes only.

[Unreleased]: https://github.com/bhargavsavasani/latencyscope/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/bhargavsavasani/latencyscope/releases/tag/v0.1.0
