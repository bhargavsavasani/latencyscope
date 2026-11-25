# Privacy and redaction

LatencyScope records URLs and renders them into a file people forward to each
other. That makes redaction a correctness requirement, not a nicety.

**Nothing leaves the device.** There is no upload, no analytics, no network
call of any kind in the library. The HTML report makes no network requests
either — no fonts, no scripts, no images from anywhere — so opening one does
not phone home.

## What is recorded

| | |
|---|---|
| URL | Path kept. Query **values masked** unless you opt in. Fragment dropped. `user:pass@` stripped |
| Route | The normalized path — see [route normalization](route-normalization.md) |
| Host | Kept |
| Method, status code | Kept |
| Timings | Total and per-phase |
| Sizes | Request and response bytes |
| Protocol, cache hit, retry count, thread name | Kept |
| Error | Exception class and message. **No stack trace** |
| Network type | wifi / cellular / none at the time of the call, when readable |
| Device | Manufacturer, model, API level, app version, locale |

## What is never recorded

- **Request and response bodies.** Not sampled, not truncated, not hashed.
  They are never read. This is also why sizes come from declared lengths or
  counted streams rather than from buffering.
- **Headers.** No module records them. There is deliberately no
  "sensitive headers" list in the API, because a published allowlist that
  nothing consults reads as a promise the library does not keep.
- **Stack traces.** An exception contributes its class name and message.

## Query values

Masked by default. Tokens live in query strings far more often than people
expect.

```
https://api.example.com/v1/search?q=shoes&token=eyJhbGciOi...
→ https://api.example.com/v1/search?q=***&token=***
```

Turning masking off keeps ordinary values but **still masks anything that
looks like a credential** by key name — `token`, `access_token`,
`refresh_token`, `id_token`, `api_key`, `apikey`, `key`, `secret`,
`password`, `pwd`, `signature`, `sig`, `auth`, `session`, `code`.

```kotlin
LatencyConfig(captureQueryParams = true)
```

Redaction happens **before a record is stored**, not at render time. The
in-memory session is itself shareable — it is handed to the report screens and
to `toJson()` — so filtering late would leave the secret reachable.

## Keeping traffic out entirely

`urlFilter` runs on the recording path and returns false to ignore a URL
completely. Analytics beacons, image CDNs and log uploads are usually noise in
a latency report anyway.

```kotlin
LatencyConfig(
    urlFilter = { url ->
        "analytics." !in url && "/telemetry" !in url
    },
)
```

Keep it cheap — it is called on a network thread, for every call.

## Where the report lands

```
Android/data/<your app>/files/report/<session name>/
```

External app storage: reachable with `adb pull`, over USB and from the Files
app, with no permission, and removed when the app is uninstalled. On a device
with no external volume at all it falls back to internal storage.

This is app-scoped storage, not the shared Downloads folder — other apps
cannot read it. Sharing is explicit, through the bundled `FileProvider`, and
grants read access only to the app you pick.

## Before you forward a report

A report carries your internal hostnames, route shapes, status codes and
error messages. That is a map of your API.

Both in-app report screens are `exported="false"` for the same reason. If you
attach a report to a public issue, read it first.

## Release builds

The recommended wiring puts the real modules on `debug` and
`latencyscope-noop` on `release`, so a shipped app records nothing — there is
nothing left to record with. The sample's minified release APK contains **5**
LatencyScope classes against 365 in debug, and `POST_NOTIFICATIONS` does not
appear in the release manifest at all.

If you do ship the real library in a release build, that is a deliberate
choice to make: everything above still holds, but the recording and the report
are now in users' hands.

## Permissions

The library requests nothing on your behalf.

- `POST_NOTIFICATIONS` is declared by `latencyscope-export` so you do not have
  to add it. Denied, recording works exactly as before and is simply
  invisible. A debug tool must never be why an app crashes.
- `ACCESS_NETWORK_STATE` is optional. Without it, network type and link speed
  are null rather than a crash, and calls with no readable transport are left
  out of the per-network stats.
