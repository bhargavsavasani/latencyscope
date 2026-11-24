plugins {
    id("latencyscope.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.latencyscope.ui.compose"

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":latencyscope-core"))
    api(project(":latencyscope-export"))

    // compileOnly, like OkHttp and Ktor in the instrumentation modules: the
    // consumer's Compose BOM wins. An `implementation` here would push this
    // module's BOM onto every app that shows a report, and a debug screen does
    // not get to move an app's Compose version.
    //
    // This makes the versions below the *minimum supported*, so the module
    // must only call Compose APIs that existed then. An app already using
    // Compose has these on the classpath; one that is not would not be
    // depending on this module.
    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.activity.compose)

    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.foundation)
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
