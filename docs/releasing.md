# Releasing

For maintainers. Everything here happens from a tag; nothing is published by
hand.

## One-time setup

### Coordinates

`GROUP` in the root `gradle.properties` is `io.github.<your GitHub username>`.
Replace the `GH_USER` token there, in `README.md`, in `CHANGELOG.md` and in
`.github/ISSUE_TEMPLATE/config.yml` before the first release.

**Coordinates on Maven Central are permanent.** An artifact cannot be deleted
or re-published, so this is the one decision worth getting right before the
first tag.

### Central Portal

[OSSRH is retired](https://central.sonatype.org/) — register the namespace at
[central.sonatype.com](https://central.sonatype.com). An `io.github.*`
namespace is verified by proving you control that GitHub account. Generate a
user token; it is the username/password pair the build uses.

### Signing key

```bash
gpg --full-generate-key                      # RSA 4096
gpg --list-secret-keys --keyid-format=long   # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>    # this goes in the secret
```

Central verifies against a public keyserver, so the `--send-keys` step is not
optional.

### Repository secrets

| Secret | What it is |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal user token, username half |
| `MAVEN_CENTRAL_PASSWORD` | Portal user token, password half |
| `SIGNING_KEY` | The full ASCII-armoured private key, newlines and all |
| `SIGNING_KEY_PASSWORD` | Its passphrase |

## Cutting a release

1. **Check the gate locally.** `./gradlew check lintDebug assembleStaging`.
   Everything CI runs, so a red tag is avoidable.
2. **Write the changelog.** Move `Unreleased` into a dated version heading.
   Write it for someone deciding whether to upgrade — what changed for them,
   not what changed in the code.
3. **Set the version.** `VERSION_NAME` in `gradle.properties`, without a
   `-SNAPSHOT` suffix.
4. **Verify the artifacts.** `./gradlew publishToMavenLocal`, then look in
   `~/.m2/repository/io/github/<group>/`. Each module should have an `.aar`, a
   `-sources.jar`, a `-javadoc.jar` and a `.pom`. Read one POM: the licence and
   developer should appear **once** each.
5. **Commit and tag.**
   ```bash
   git commit -am "Release 0.1.0"
   git tag v0.1.0
   git push && git push --tags
   ```
6. **Release it in the Portal.** The workflow uploads a *staged* deployment
   and stops. Nothing is public until you release it at
   [central.sonatype.com](https://central.sonatype.com) — deliberately, because
   it cannot be undone. Expect 10–30 minutes before it is resolvable.
7. **Set the next version**: bump `VERSION_NAME` to the next
   `-SNAPSHOT` and open a fresh `Unreleased` section.

## If it fails

| Symptom | Cause |
|---|---|
| Rejected: missing signature | `SIGNING_KEY` truncated — it must include the `-----BEGIN`/`-----END` lines and every newline |
| Rejected: key not found | `--send-keys` was skipped, or the keyserver has not propagated yet |
| Rejected: missing javadoc or sources | The publish convention plugin is not applied to that module |
| `apiCheck` fails on the tag | A public declaration was added without mirroring it in `latencyscope-noop` |

A failed upload is safe to retry — nothing is public until step 6.

## JitPack, as a fallback

If Central is not ready, JitPack builds straight from a tag and needs no
account:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    debugImplementation("com.github.GH_USER.latencyscope:latencyscope-okhttp:v0.1.0")
    releaseImplementation("com.github.GH_USER.latencyscope:latencyscope-noop:v0.1.0")
}
```

Different coordinates, so it is a stopgap rather than a parallel channel —
move to Central and leave the JitPack build to history.
