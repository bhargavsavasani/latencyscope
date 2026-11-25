plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
}

android {
    namespace = "io.github.latencyscope.ktor"
}

dependencies {
    api(project(":latencyscope-core"))

    // compileOnly: the consumer's Ktor version wins.
    compileOnly(libs.ktor.client.core)

    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    // Proves the Ktor-on-OkHttp double-counting case end to end.
    testImplementation(project(":latencyscope-okhttp"))
    testImplementation(libs.okhttp)
}
