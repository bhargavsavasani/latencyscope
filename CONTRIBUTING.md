# Contributing

Thanks for looking. This is a measurement tool, so the bar for a change is a
little different from most libraries: a number that is subtly wrong is worse
than a feature that is missing, because people act on it.

## Before you start

For anything beyond a typo, **open an issue first**. Two things in particular
are worth agreeing on before code gets written:

- **A new public declaration is permanent.** Every `public` type and function
  is a commitment from the first release, and has to be mirrored in
  `latencyscope-noop` forever. If a workflow does not need it, it should be
  `internal`.
- **A new dependency lands in every consumer's build.** HTTP clients are
  `compileOnly` on purpose, so the consumer's own version wins.

## Getting set up

```bash
git clone https://github.com/bhargavsavasani/latencyscope.git
cd latencyscope
./gradlew check
```

JDK 17 or newer. Unit tests are pinned to a JDK 17 toolchain regardless of
what launched the daemon — Robolectric's bytecode reader rejects class files
from a newer JDK, and a developer should not get a different result from CI.

To try a change against a real app, `./gradlew publishToMavenLocal` and point
the app at `mavenLocal()`.

## The gate

`./gradlew check` is the whole thing, and it is what CI runs:

| What | Why it exists |
|---|---|
| Unit tests | Every module |
| `apiCheck` | Diffs the Kotlin-visible public surface of the real modules against `latencyscope-noop`. Add a public declaration and this fails until the mirror has it |
| `coverageVerify` | JaCoCo line coverage floor on `latencyscope-core`. It asserts its own class set is non-empty first, because an empty coverage report passes every threshold |

Then `./gradlew lintDebug` and, for anything touching the release wiring,
`./gradlew assembleStaging` — `staging` is the minified build that still links
the real library, so it is the only variant where an R8 keep rule can be shown
to work.

## Tests

**A test that has never failed is not a test.** Write the test, run it against
the unfixed code, and watch it fail before you accept it. Several real bugs
here were found by a test that turned out to pass either way.

A few conventions worth copying from what is already there:

- Name the behaviour, not the method: `aOneOffOutlierDoesNotBecomeTheHeadline`
  rather than `testWorstRoute`.
- Say *why* in a KDoc on the test when the reason is not obvious from the
  assertion. The next reader needs to know what breaks if it is deleted.
- Flakiness is worse than failure — it teaches people to re-run rather than to
  look. If a test is timing-sensitive, make it robust to a loaded machine
  rather than generous with its tolerance.

## Style

- Explicit API mode is **strict**: every public declaration spells out its
  visibility and return type.
- Comments explain *why*, not what. The code already says what.
- No `TODO`, `!!`, `printStackTrace` or empty `catch` blocks — there are none
  in the library today and the reviewer will ask.
- A debug tool must never be the reason an app crashes. Anything touching the
  recording path or a framework callback is guarded.

## Commits and pull requests

Write the subject as what the change does for a reader, in the imperative:
*"Fix stale recording header after the notification stops a session"*. Explain
the reasoning in the body — especially anything you decided **not** to do.

In the PR, say what you verified and what you could not. "Not tested on a
device" is a useful sentence; silence about it is not.

## Reporting a bug

A latency report itself is usually the fastest way to show the problem — but
**read it first**. It contains your hostnames and route shapes.

Please include the HTTP stack and version, the device and API level, and what
you expected the number to be.

## Security

Do not open a public issue for a vulnerability. See
[SECURITY.md](SECURITY.md).

## Licence

Contributions are licensed under Apache-2.0, matching the project.
