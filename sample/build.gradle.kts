plugins {
    alias(libs.plugins.android.application)
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

    buildTypes {
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
     * API-identical no-op in release. LatencyScopeWiring.kt is compiled into
     * both, so a release build of this module is the proof that swapping them
     * needs no source changes.
     */
    debugImplementation(project(":latencyscope-okhttp"))
    debugImplementation(project(":latencyscope-ktor"))
    debugImplementation(project(":latencyscope-volley"))
    debugImplementation(project(":latencyscope-urlconnection"))
    debugImplementation(project(":latencyscope-export"))
    debugImplementation(project(":latencyscope-ui-compose"))
    debugImplementation(project(":latencyscope-ui-view"))
    releaseImplementation(project(":latencyscope-noop"))

    // The instrumentation modules take these compileOnly, so the app supplies
    // them — which is the arrangement a real consumer is already in.
    implementation(libs.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.volley)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}