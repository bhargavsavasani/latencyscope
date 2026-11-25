plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
}

android {
    namespace = "io.github.latencyscope.ui.view"

    // Every resource this module ships is namespaced, so it cannot collide
    // with a consumer's own resources.
    resourcePrefix = "latencyscope_"

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":latencyscope-core"))
    api(project(":latencyscope-export"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
