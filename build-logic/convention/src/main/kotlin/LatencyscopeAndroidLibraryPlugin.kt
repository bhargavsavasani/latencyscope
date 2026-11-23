import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared configuration for every LatencyScope library module.
 *
 * Keeps compile/min SDK, Java level, test wiring and the strict explicit-API
 * requirement in one place so the published modules can never drift apart.
 */
class LatencyscopeAndroidLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK

            defaultConfig {
                minSdk = MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }

            buildFeatures {
                buildConfig = false
            }

            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            // Every public declaration must spell out its visibility and return
            // type: this is a published API surface, not app code.
            explicitApi = ExplicitApiMode.Strict

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xjvm-default=all",
                    "-Xconsistent-data-class-copy-visibility",
                )
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
            add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
        }
    }

    private companion object {
        const val COMPILE_SDK = 37
        const val MIN_SDK = 21
    }
}
