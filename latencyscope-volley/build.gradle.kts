plugins {
    id("latencyscope.android.library")
    id("latencyscope.publish")
}

android {
    namespace = "io.github.latencyscope.volley"

    // Volley's Request constructor calls android.net.Uri and TextUtils, so its
    // tests need a real Android runtime rather than stubbed defaults.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":latencyscope-core"))

    // compileOnly: the consumer's Volley version wins.
    compileOnly(libs.volley)

    testImplementation(libs.volley)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)

    // Volley's BaseHttpStack still implements the deprecated HttpStack, whose
    // signatures reference org.apache.http. Android ships those classes; a JVM
    // test run has to put them on the classpath itself.
    testImplementation(libs.apache.httpclient)
}
