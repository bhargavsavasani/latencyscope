plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.latencyscope.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.latencyscope.sample"
        // 30, not 28, because of Ktor: ktor-client-core declares identifiers
        // containing spaces, which DEX only permits from version 040 (API 30)
        // unless R8 renames them away. That is a constraint of demoing Ktor in
        // an un-minified debug build, not of LatencyScope — the library
        // modules are still minSdk 21.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        /*
         * Minification over the *real* library.
         *
         * `release` links latencyscope-noop, so R8 there only ever proves the
         * no-op disappears — it never exercises a consumer rule, because there
         * is nothing left to keep. `staging` is release with the real modules
         * linked, which is the configuration an app that ships LatencyScope is
         * actually in, and the only place a missing keep rule can show up.
         */
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // Deliberately NOT debuggable: R8 skips obfuscation for debuggable
            // builds, and renaming is half of what this variant is here to
            // exercise. A class that survives only because nothing was renamed
            // proves nothing about a consumer's real release build.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**",
                    "io.github.**",
                )
            }
        }
        release {
            optimization {
                enable = true
                // The app's own packages are in scope too, so R8 may remove
                // LatencyScope entirely from the release build. Without
                // `io.github.**` here the no-op's classes survive shrinking
                // even though nothing reaches them.
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**",
                    "io.github.**",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    /*
     * The wiring the README tells consumers to use: real modules in debug, the
     * API-identical no-op in release. Every source file in this app is compiled
     * into both build types and none of them is written twice, so a release
     * build is the proof that swapping them needs no source changes.
     */
    debugImplementation(project(":latencyscope-okhttp"))
    debugImplementation(project(":latencyscope-ktor"))
    debugImplementation(project(":latencyscope-volley"))
    debugImplementation(project(":latencyscope-urlconnection"))
    debugImplementation(project(":latencyscope-export"))
    debugImplementation(project(":latencyscope-ui-compose"))
    debugImplementation(project(":latencyscope-ui-view"))
    releaseImplementation(project(":latencyscope-noop"))

    // staging = release-style minification, real library. See the build type.
    "stagingImplementation"(project(":latencyscope-okhttp"))
    "stagingImplementation"(project(":latencyscope-ktor"))
    "stagingImplementation"(project(":latencyscope-volley"))
    "stagingImplementation"(project(":latencyscope-urlconnection"))
    "stagingImplementation"(project(":latencyscope-export"))
    "stagingImplementation"(project(":latencyscope-ui-compose"))
    "stagingImplementation"(project(":latencyscope-ui-view"))

    // The instrumentation modules take these compileOnly, so the app supplies
    // them — which is the arrangement a real consumer is already in.
    implementation(libs.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.volley)
    implementation(libs.kotlinx.coroutines.android)

    // Debug only, and only in the sample: LeakCanary watches the Activity and
    // the clients it owns, which is where a cancelled or failed call would
    // strand a reference.
    debugImplementation(libs.leakcanary)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    // The sample is the only module that has every piece on one classpath, so
    // it is where the end-to-end test can live: real HTTP in, HTML report out.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
