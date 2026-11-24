import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * Wires the `apiCheck` gate onto `latencyscope-noop`.
 *
 * The no-op artifact only earns its place if it is a drop-in replacement: an
 * app swaps it in for its release build and nothing stops compiling. That is a
 * promise no reviewer can keep by eye across nine modules, so the build keeps
 * it instead.
 */
class LatencyscopeApiParityPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.create("apiParity", ApiParityExtension::class.java)

        val check = tasks.register<ApiParityTask>("apiCheck") {
            group = "verification"
            description = "Fails if latencyscope-noop no longer mirrors the real modules' public API."
            mirrorJar.set(extension.mirrorJar)
            realJars.setFrom(extension.realJars)
            report.set(layout.buildDirectory.file("reports/api-parity.txt"))
        }

        tasks.named("check").configure { dependsOn(check) }
    }
}

/** Configuration for [LatencyscopeApiParityPlugin]. */
abstract class ApiParityExtension {
    /** The no-op module's own compiled jar. */
    abstract val mirrorJar: RegularFileProperty

    /** Jars of the real modules whose API the no-op must reproduce. */
    abstract val realJars: ConfigurableFileCollection
}

@CacheableTask
abstract class ApiParityTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mirrorJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val realJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = realJars.files
            .filter { it.isFile }
            .flatMap { ApiSignatures.extract(it) }
            .filterNot { line -> IGNORED.any { line.contains(it) } }
            .toSortedSet()

        val actual = ApiSignatures.extract(mirrorJar.get().asFile).toSortedSet()

        val missing = (expected - actual).toSortedSet()
        val extra = (actual - expected).toSortedSet()

        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            buildString {
                appendLine("# LatencyScope API parity")
                appendLine("# ${expected.size} declaration(s) expected, ${actual.size} mirrored.")
                appendLine()
                missing.forEach { appendLine("MISSING FROM NOOP  $it") }
                extra.forEach { appendLine("EXTRA IN NOOP      $it") }
                if (missing.isEmpty() && extra.isEmpty()) appendLine("OK — surfaces are identical.")
            },
        )

        if (missing.isEmpty() && extra.isEmpty()) {
            logger.lifecycle("API parity OK: ${expected.size} public declarations mirrored.")
            return
        }

        throw GradleException(
            buildString {
                appendLine("latencyscope-noop does not mirror the real modules' public API.")
                appendLine()
                if (missing.isNotEmpty()) {
                    appendLine("Missing from latencyscope-noop (${missing.size}):")
                    missing.take(MAX_SHOWN).forEach { appendLine("  $it") }
                    if (missing.size > MAX_SHOWN) appendLine("  ...and ${missing.size - MAX_SHOWN} more")
                    appendLine()
                }
                if (extra.isNotEmpty()) {
                    appendLine("Present only in latencyscope-noop (${extra.size}):")
                    extra.take(MAX_SHOWN).forEach { appendLine("  $it") }
                    if (extra.size > MAX_SHOWN) appendLine("  ...and ${extra.size - MAX_SHOWN} more")
                    appendLine()
                }
                append("Full report: ${reportFile.absolutePath}")
            },
        )
    }

    private companion object {
        const val MAX_SHOWN = 40

        /**
         * ViewBinding classes are generated from layout XML, not authored. A
         * consumer never names one — they reach the report screen through
         * `LatencyReportActivity` or `LatencyReportView` — and asking the no-op
         * to ship fake bindings would mean shipping fake layouts to generate
         * them from.
         */
        val IGNORED = listOf(".databinding.")
    }
}
