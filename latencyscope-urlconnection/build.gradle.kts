plugins {
    id("latencyscope.android.library")
}

android {
    namespace = "io.github.latencyscope.urlconnection"
}

dependencies {
    api(project(":latencyscope-core"))

    testImplementation(libs.okhttp.mockwebserver)
}
