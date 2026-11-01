import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// UI integration tests: the Starter framework runs the sandbox IDE with the plugin, and the Driver controls it.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting
val integrationTestRuntimeOnly by configurations.getting

dependencies {
    testImplementation(libs.junit)
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    // Starter brings an older Kotlin standard library than the compiler expects.
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")

    intellijPlatform {
        intellijIdea("2026.1.4")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.modules.json")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
}

tasks.processResources {
    from("LICENSE") { into("META-INF") }
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider {
            val entry = changelog.getOrNull(project.version.toString()) ?: changelog.getUnreleased()
            changelog.renderItem(entry.withHeader(false), Changelog.OutputType.HTML)
        }
        ideaVersion {
            sinceBuild = "261.26222.65"
            untilBuild = "262.*"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("marketplaceChannel").map { listOf(it) }.orElse(listOf("default"))
    }
    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

tasks.test {
    systemProperty("versionchecker.live", providers.gradleProperty("live").orElse("false").get())
}

// Starter takes screenshots of the whole screen when a step fails and before the IDE closes. They can show
// other apps on your desktop, so they are deleted after every run.
val deleteUiScreenshots = tasks.register<Delete>("deleteUiScreenshots") {
    delete(fileTree("out/ide-tests") { include("**/screenshots/**") })
}

// Starts the sandbox IDE with the built plugin and runs src/integrationTest against it. It needs a desktop
// session and the network, so it is not part of `check`: run `./gradlew integrationTest`.
val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        testLogging { showStandardStreams = true }
        // The IDE the plugin compiles against, from the Gradle cache, so Starter does not download another one.
        systemProperty("versionchecker.idePath", intellijPlatform.platformPath.toString())
        systemProperty("versionchecker.demos", layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
        val pluginZip = tasks.named<Zip>("buildPlugin")
        dependsOn(pluginZip)
        systemProperty("versionchecker.pluginZip", pluginZip.get().archiveFile.get().asFile.absolutePath)
        finalizedBy(deleteUiScreenshots)
    }
}
