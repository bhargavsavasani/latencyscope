plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
}

android {
    namespace = "io.github.latencyscope.okhttp"
}

dependencies {
    api(project(":latencyscope-core"))

    // compileOnly: the consumer's OkHttp version wins. This module must never
    // drag an OkHttp upgrade into an app that pinned its own.
    compileOnly(libs.okhttp)

    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
