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
    compileOnly(libs.mavenPublish.gradlePlugin)

    // The API-parity gate reads class files directly, so these ship with the
    // convention plugin rather than with any published module.
    implementation(libs.kotlin.metadata)
    implementation(libs.asm)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "latencyscope.android.library"
            implementationClass = "LatencyscopeAndroidLibraryPlugin"
        }
        register("publish") {
            id = "latencyscope.publish"
            implementationClass = "LatencyscopePublishPlugin"
        }
        register("apiParity") {
            id = "latencyscope.api.parity"
            implementationClass = "LatencyscopeApiParityPlugin"
        }
    }
}
