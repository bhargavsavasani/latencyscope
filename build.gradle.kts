// Top-level build file. Shared module configuration lives in build-logic/.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Applied per module by the `latencyscope.publish` convention plugin;
    // declared here so it lands on the build classpath exactly once.
    alias(libs.plugins.maven.publish) apply false
}
