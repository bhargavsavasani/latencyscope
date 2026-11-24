plugins {
    id("latencyscope.android.library")
    id("latencyscope.api.parity")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.latencyscope.noop"

    buildFeatures {
        compose = true
    }
}

/**
 * The modules this one stands in for.
 *
 * Deliberately its own configuration rather than `compileOnly`: the no-op must
 * never see a LatencyScope type at compile time, or a signature could match
 * only because it was inherited from the real thing. These jars are read by
 * the `apiCheck` task and by nothing else.
 */
val mirroredModules: Configuration = configurations.create("mirroredModules") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(
            com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
            objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "release"),
        )
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_API))
    }
}

dependencies {
    // Every third-party type that appears in a mirrored signature has to be on
    // the compile classpath. compileOnly throughout: swapping in the no-op must
    // not add a single runtime dependency to the consumer's release build.
    compileOnly(libs.okhttp)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.volley)
    compileOnly(libs.androidx.core)
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.androidx.recyclerview)

    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.activity.compose)

    // The no-op's own behaviour tests need the third-party types on the
    // runtime classpath, which compileOnly does not provide.
    testImplementation(libs.okhttp)
    testImplementation(libs.volley)
    // The Compose compiler plugin runs over the test source set too and needs
    // the runtime present even though no test touches a composable.
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.appcompat)
    // BaseHttpStack still implements Volley's deprecated HttpStack, whose
    // signatures name org.apache.http types. Android ships them; a JVM test
    // run has to put them on the classpath itself.
    testImplementation(libs.apache.httpclient)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    mirroredModules(project(":latencyscope-core"))
    mirroredModules(project(":latencyscope-okhttp"))
    mirroredModules(project(":latencyscope-ktor"))
    mirroredModules(project(":latencyscope-volley"))
    mirroredModules(project(":latencyscope-urlconnection"))
    mirroredModules(project(":latencyscope-export"))
    mirroredModules(project(":latencyscope-ui-compose"))
    mirroredModules(project(":latencyscope-ui-view"))
}

apiParity {
    realJars.from(
        mirroredModules.incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            // Only this build's own modules. Without this the view also yields
            // kotlin-stdlib, OkHttp and Compose, and the no-op would be asked
            // to reimplement the Kotlin standard library.
            componentFilter { it is ProjectComponentIdentifier }
        }.files,
    )
    mirrorJar.set(
        layout.buildDirectory.file(
            "intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar",
        ),
    )
}

tasks.named("apiCheck").configure { dependsOn("syncReleaseLibJars") }
