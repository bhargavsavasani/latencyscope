import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Publishing for one LatencyScope module.
 *
 * Coordinates, licence, SCM and developer are **not** set here: the plugin
 * reads the `GROUP`, `VERSION_NAME` and `POM_*` properties out of
 * `gradle.properties` on its own. Restating them in Kotlin produced a POM with
 * every licence and developer listed twice, which is how that was found. One
 * place, and it is the place the tool already looks.
 *
 * Artifact id defaults to the module's own directory name, so adding a module
 * to the release is one line in its build file and nothing else.
 *
 * Signing is required for Maven Central (keys come from the environment, see
 * `.github/workflows/release.yml`). JitPack builds cannot sign; they skip the
 * signature by not running the sign task. Local Maven builds do not need
 * signatures either since Maven Local does not verify them.
 */
class LatencyscopePublishPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            // OSSRH is retired; the Central Portal is the only route for a
            // group id registered from here on. Not automatic: a release
            // should be looked at in the Portal before it becomes permanent.
            publishToMavenCentral(automaticRelease = false)

            // Sign only when a key is actually available. Calling this
            // unconditionally registers a sign task that produces no `.asc`
            // when there is no key, and the publication then fails on a
            // missing artifact — which is exactly how JitPack's build broke:
            //
            //   artifact file does not exist: 'pom-default.xml.asc'
            //
            // Central still gets signed artifacts, because the release
            // workflow sets these properties from repository secrets. Maven
            // Local and JitPack do not verify signatures, so skipping is safe.
            val hasSigningKey =
                providers.gradleProperty("signingInMemoryKey").isPresent ||
                    providers.gradleProperty("signing.keyId").isPresent
            if (hasSigningKey) {
                signAllPublications()
            }
        }
    }
}
