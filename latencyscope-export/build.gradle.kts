plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
}

android {
    namespace = "io.github.latencyscope.export"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":latencyscope-core"))

    // FileProvider, for sharing a written report without handing out a file:// URI.
    implementation(libs.androidx.core)

    // A real JSON parser in tests, so the hand-rolled writer is checked
    // against a parser rather than against string matching.
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
