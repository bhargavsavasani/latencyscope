# Route normalization

`/users/42/orders/9001` and `/users/7/orders/3` have to land in the same
bucket, or the report degenerates into one row per call and tells you nothing.

Both become `/users/{id}/orders/{id}`.

## What the default normalizer collapses

Each path segment is examined on its own, in this order:

| Segment looks like | Becomes | Example |
|---|---|---|
| All digits | `{id}` | `/users/42` → `/users/{id}` |
| A UUID | `{uuid}` | `/s/3f2a…-…` → `/s/{uuid}` |
| Hex, 16 chars or longer | `{hash}` | `/blob/9af3…c21` → `/blob/{hash}` |
| A long opaque token, 24 chars or longer | `{token}` | `/d/eyJhbGciOi…` → `/d/{token}` |
| Anything else | unchanged | `/orders` stays `/orders` |

A segment with an extension keeps it, and only the stem is normalized:
`/img/48213.png` → `/img/{id}.png`. A thousand images are one row, and it is
still obvious they are images.

### The token rule, and why it is fussy

A long opaque blob — a JWT, a cursor, a signed URL — is an id in disguise. But
a rule that simply collapsed long segments would eat `/recommendations` and
`/notifications`, which are real route names.

So a segment is only a `{token}` if it is at least 24 characters of
`A-Za-z0-9_~.=-`, **contains a digit**, and either is at least 15 % digits or
contains an uppercase letter. A dictionary word with a couple of digits in it
is still a word.

## When the heuristics are wrong

They will be, for slugs. `/v1/products/blue-widget` looks like a route name
and is really an id. Supply a rule — rules run **first** and win:

```kotlin
LatencyScope.init(
    this,
    LatencyConfig(
        routeNormalizer = DefaultRouteNormalizer(
            rules = listOf(
                DefaultRouteNormalizer.Rule(
                    Regex("/products/[^/]+"),
                    "/products/{slug}",
                ),
                DefaultRouteNormalizer.Rule(
                    Regex("/tenants/[a-z-]+"),
                    "/tenants/{tenant}",
                ),
            ),
        ),
    ),
)
```

A segment already in `{braces}` is left alone afterwards, so a rule's output is
never normalized a second time.

## Replacing it entirely

`RouteNormalizer` is a `fun interface`. If you have a route table, use it —
a heuristic will never beat knowing the answer:

```kotlin
LatencyConfig(
    routeNormalizer = RouteNormalizer { path ->
        myRouter.matchTemplate(path) ?: path
    },
)
```

Keep it cheap and side-effect free: it runs on the recording path, once per
call.

`IdentityRouteNormalizer` leaves paths untouched, for a small API where every
path is already a route, or for a test that wants to assert on exact URLs.

## Two symptoms, two fixes

| Symptom | Cause | Fix |
|---|---|---|
| One row per call | Ids the heuristics do not recognise — usually slugs or short non-numeric keys | Add a `Rule` |
| Two rows that should be one | Same resource reached by different paths, e.g. a trailing-slash variant or an API version in the path | Add a `Rule`, or normalize before the call |
| One row that should be several | Over-collapsing — a real route name eaten by the token rule | Add a `Rule` mapping it back to itself; rules run first |

Normalization affects **grouping only**. The raw, redacted URL of every call is
kept and appears in the call log and in `calls.csv`, so you can always see what
was actually requested.
