plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
    jacoco
}

android {
    namespace = "io.github.latencyscope.core"
}

dependencies {
    // Needed to test the two classes that read the framework directly —
    // DeviceInfoCollector and NetworkTypeProvider — which is also where the
    // coverage floor found this module's real gap.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

jacoco {
    toolVersion = "0.8.12"
}

/*
 * Coverage gate.
 *
 * Core is the only module with a hard threshold: it is the engine, it is pure
 * Kotlin with no Android UI to make coverage meaningless, and a regression
 * here is silent — a mis-aggregated percentile still renders a plausible
 * report.
 *
 * JaCoCo directly rather than Kover, for the same reason Phase 0 dropped
 * binary-compatibility-validator: Kover 0.9.1 discovers what to instrument
 * through the standalone Kotlin plugin, which AGP 9's built-in Kotlin does not
 * apply. It produces an empty report, and an empty report *passes* every
 * threshold — a gate that cannot fail. See the assertion in `coverageVerify`
 * below, which fails if the class set is ever empty.
 */
private val coverageExclusions = listOf(
    // Generated, not authored.
    "**/BuildConfig.class",
    "**/R.class",
    "**/R$*.class",
)

// Referenced by path, not by task output: AGP registers its compile tasks
// after this script is evaluated, so `tasks.named("compileDebugKotlin")` here
// fails outright. The producing task is named in `dependsOn` instead.
private val coverageClasses = fileTree(
    layout.buildDirectory.dir(
        "intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug",
    ),
).matching {
    include("**/*.class")
    exclude(coverageExclusions)
}

// The single exec file the jacoco plugin writes for the debug unit tests.
// A `**/*.exec` glob over the build directory would work, but it makes every
// task that writes anywhere under build/ an implicit input of this one, which
// Gradle rejects as an undeclared dependency.
private val coverageExecData = files(
    layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"),
)

tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Line and branch coverage for latencyscope-core."
    dependsOn("testDebugUnitTest", "bundleLibRuntimeToDirDebug")

    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(coverageExecData)

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("coverageVerify") {
    group = "verification"
    description = "Fails if latencyscope-core drops below the line coverage floor."
    dependsOn("testDebugUnitTest", "bundleLibRuntimeToDirDebug")

    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(coverageExecData)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }

    doFirst {
        // An empty class set satisfies every ratio rule. Checking here is what
        // stops a future build change from turning this gate into a no-op.
        check(!classDirectories.isEmpty) {
            "coverageVerify found no classes to measure — the gate would pass vacuously."
        }
    }
}

tasks.named("check") {
    dependsOn("coverageVerify")
}
