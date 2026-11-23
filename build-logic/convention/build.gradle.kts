plugins {
    `kotlin-dsl`
}

group = "io.github.latencyscope.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "latencyscope.android.library"
            implementationClass = "LatencyscopeAndroidLibraryPlugin"
        }
    }
}
