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
 * Signing is required rather than conditional: an unsigned artifact is
 * rejected at the *end* of a release instead of the start of one. Keys come
 * from the environment (see `.github/workflows/release.yml`), and
 * `publishToMavenLocal` still works without them, since Maven Local does not
 * verify signatures.
 */
class LatencyscopePublishPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            // OSSRH is retired; the Central Portal is the only route for a
            // group id registered from here on. Not automatic: a release
            // should be looked at in the Portal before it becomes permanent.
            publishToMavenCentral(automaticRelease = false)
            signAllPublications()
        }
    }
}
