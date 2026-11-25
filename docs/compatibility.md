# Compatibility

## Versioning

[Semantic Versioning](https://semver.org/). Before `1.0.0`, a minor bump may
break source compatibility; it will be in the
[CHANGELOG](../CHANGELOG.md) with the reason and the migration.

## What "breaking" means here

The published surface of every module, as Kotlin sees it. Specifically:

- Class names and their supertypes
- Enum entries
- Constructors and public member signatures
- The same, in `latencyscope-noop`

That last one is not a courtesy — it is enforced. A build-time gate
(`./gradlew apiCheck`, wired into `check`) reads Kotlin metadata from the
compiled classes of the real modules and the no-op and diffs them. Adding a
public declaration fails the build until the mirror has it. The gate has been
tested in both directions: a method added to either side names the exact
signature and fails.

The gate reads **Kotlin** metadata rather than bytecode on purpose. The JVM
has no `internal` — everything `internal` is public in bytecode — so a
javap-style dump would have demanded the no-op reimplement every internal
class in the library.

**Not** part of the contract:

- `internal` declarations, whatever the JVM thinks of them
- The exact HTML, CSS or layout of the report. The *data* in the JSON is
  stable; the page around it is free to improve
- The exact wording of a rating or a diagnosis
- Anything named `*ForTesting`

## Supported versions

| | |
|---|---|
| `minSdk` | **21** for every library module |
| `compileSdk` | 37 |
| Java / Kotlin target | JVM 11 |
| Kotlin | 2.2.10, as shipped inside AGP 9.5.2 |
| JDK to build | 17 or newer. Unit tests run on a pinned 17 toolchain |

The sample app is `minSdk 30`, which is a constraint of demoing Ktor
un-minified — `ktor-client-core` declares identifiers containing spaces, which
DEX only permits from version 040. It is not a constraint of the library.

## HTTP client versions

OkHttp, Ktor, Volley and Compose are **`compileOnly`**. Your version is the one
that runs; LatencyScope adds nothing to your dependency graph for them.

The versions in this repository are therefore the **minimum supported**, not
the latest:

| | Floor |
|---|---|
| OkHttp | 4.12.0 — works with OkHttp 5 |
| Ktor | 3.2.0 |
| Volley | 1.2.1 |
| Compose | BOM 2025.12.01 |

Compiling against the oldest API we promise to support is what keeps a
consumer on OkHttp 4 from hitting `NoSuchMethodError`. Raising a floor is a
breaking change and gets a major or minor bump.

## Upgrading

```bash
./gradlew dependencies --configuration debugRuntimeClasspath | grep latencyscope
```

Every module should be on the same version. They are released together and
`latencyscope-core` is exposed with `api`, so a mismatch is possible and will
produce confusing failures rather than a clean error.

## Deprecation

A declaration is deprecated with `@Deprecated` and a `ReplaceWith` for at
least one minor release before it is removed, and the removal is in the
changelog. Nothing is removed in a patch.
