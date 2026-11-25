# Report metrics

Every number in the report comes from one aggregation, so the HTML, both CSVs
and the JSON can never disagree.

## The verdict

The report opens with a rating in words, because evidence without a yardstick
leaves the judgement to whoever is reading — and they will go and find a
yardstick that disagrees with the report's own colours.

| Rating | p95 | What it means |
|---|---|---|
| Excellent | under 200 ms | Feels instant |
| Good | 200 – 500 ms | Comfortable, no action needed |
| Acceptable | 500 ms – 1 s | Usable, worth keeping an eye on |
| Slow | 1 – 2 s | Noticeable to users, worth investigating |
| Poor | over 2 s | Users will feel this and may give up |

**These describe a whole round trip from the phone.** Published backend
figures are stricter, because a backend's number does not include the radio,
DNS, the TLS handshake or the download. Compare a published backend target
against the **Server** column, not against the total — otherwise a healthy
backend reads as "acceptable, monitor" for time it never spent.

The boundaries are configurable:

```kotlin
LatencyScope.init(
    this,
    LatencyConfig(
        ratingScale = LatencyRatingScale(
            excellentUnderMs = 150,
            goodUnderMs = 400,
            acceptableUnderMs = 800,
            slowUnderMs = 1_500,
        ),
    ),
)
```

The scale changes wording and colour only. It never changes what is recorded,
and it travels in the JSON so a consumer judges by the same yardstick.

### Where to look first

The verdict names one route, and then names the *kind* of problem from
whichever phase dominates it. Backend time and connection setup look identical
in a total and go to completely different people.

A route needs at least **three calls** before it can be blamed. One unlucky
call is an anecdote, and a report that always hands you a culprit is a report
that will eventually hand you an innocent one. A session whose worst repeated
route is excellent is told there is nothing to look at.

## Percentiles

`p95 = 1,420 ms` means 95 % of calls finished faster than 1,420 ms, and one
call in twenty took longer.

| | Reads as |
|---|---|
| p50 (median) | The typical call. Half were faster |
| p75 | 1 call in 4 was slower |
| p90 | 1 call in 10 was slower |
| **p95** | 1 call in 20 was slower — the verdict statistic |
| p99 | 1 call in 100 was slower |

Computed by **nearest rank** over the sorted set: the value returned is one
that actually occurred, never an interpolation between two calls that did not
happen. Exact, not estimated — the record set is bounded, so sorting it is
both affordable and correct.

### Why the verdict is not the average

The report shows an average, because people ask for it. It does not judge on
it.

Ninety-nine calls at 150 ms plus one 8-second hang averages to **228 ms** —
which reads as healthy, while one user in a hundred sat watching a spinner for
eight seconds. The average is the one statistic that reliably hides the thing
you are looking for. p95 cannot be dragged back into the green by a crowd of
fast calls.

## Phases

The stacked bar collapses nine measured values into five buckets, in
chronological order. Nine series is more than any validated categorical
palette can keep distinguishable; all nine raw values survive in the call
detail and in both CSVs.

| Bucket | What it covers | What a large value means |
|---|---|---|
| **Wait** | Queued before any work started | Connection pool exhausted, or dispatcher backlog. Usually too many parallel calls |
| **Connect** | DNS + TCP handshake + TLS | Connections are not being reused. A fresh client per call does this |
| **Request** | Sending request headers and body | Large uploads, or a slow uplink |
| **Server** | Request fully sent, waiting for the first response byte | **Backend think time.** The app cannot fix this |
| **Response** | Receiving response headers and body | Payload size, or a slow downlink |

`Connect` has TLS subtracted out of the raw connect span, which otherwise
contains the handshake and would double-count.

A redirect or a retry is **one row**, with phases summed across every hop and
the final status kept — one caller-visible call is one line that still
accounts for all of its cost. Volley is the exception: it retries above the
stack, so each attempt is its own record. Collapsing them would hide a retry
storm, which is exactly what you want to see.

**Only OkHttp provides all of this.** `HttpURLConnection` gives connect,
server and response body. Ktor and Volley give no phase breakdown at all —
no engine exposes one — so those records carry totals and sizes only, and the
report omits the bar rather than drawing zeros that would read as "instant".

## Throughput

Bytes divided by the time spent receiving the body — not by the total. Dividing
by the total would blame a fast download for a slow server.

It is **null, not zero**, for any stack that cannot isolate the response-body
window. The session figure is byte-weighted, so a single large download is not
outvoted by a hundred tiny ones.

Distinct from the **link speed estimate**, which is what the OS advertises the
radio as capable of. That is labelled an estimate everywhere it appears,
because it is routinely wrong by an order of magnitude.

## Stats by network type

This is what separates "the app is slow" from "the connection is slow". Calls
whose transport could not be read are **excluded**, not bucketed as unknown:
`ACCESS_NETWORK_STATE` is optional and never requested on your behalf, so its
absence is a known-unknown rather than a category.

## Error rate

A call is an error when the transport failed — no status code at all. A 404 or
a 500 is a **completed call** with a status: the server answered, and it
answered quickly. Errors are grouped by status code and exception class.

## Dropped records

The recorder is a bounded ring buffer, capped by `LatencyConfig.maxRecords`
(default 5,000). An app left recording overnight must not grow without limit.
If the cap is reached, the report says how many records were dropped rather
than quietly reporting on a subset.
