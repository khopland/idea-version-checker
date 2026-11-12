import java.security.MessageDigest
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

version = providers.gradleProperty("pluginVersion").get()

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
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    // Starter brings an older Kotlin standard library than the compiler expects.
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.33.0")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

    intellijPlatform {
        intellijIdea("2026.1.4")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("Docker")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
}

tasks.processResources {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider {
            val entry = changelog.getOrNull(project.version.toString()) ?: changelog.getUnreleased()
            changelog.renderItem(entry.withHeader(false), Changelog.OutputType.HTML)
        }
        ideaVersion {
            sinceBuild = "261.26222.65"
            untilBuild = "263.*"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 2.4.0-eap.1 publishes to the "eap" channel; plain versions go to "default".
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
        ignoredProblemsFile = layout.projectDirectory.file("gradle/verifier-ignored-problems.txt")
    }
}

val hasCertificate = providers.environmentVariable("CERTIFICATE_CHAIN").orNull?.isNotBlank() == true
val hasPrivateKey = providers.environmentVariable("PRIVATE_KEY").orNull?.isNotBlank() == true
val releaseDir = layout.buildDirectory.dir("release")

// The release workflow tests the plugin in one job, then signs and publishes that same ZIP in a job with the
// secrets. There, -PtestedArchive=<zip> takes the place of buildPlugin's output, so nothing is rebuilt.
val testedArchive = providers.gradleProperty("testedArchive").orNull?.let { layout.projectDirectory.file(it) }
if (testedArchive != null) {
    tasks.signPlugin {
        archiveFile = testedArchive
    }
    // Publishes the ZIP that prepareRelease staged, without building or signing again.
    tasks.publishPlugin {
        setDependsOn(emptyList<Any>())
        archiveFiles.setFrom(fileTree(releaseDir) { include("*.zip") })
    }
}
val unsignedArchive = testedArchive?.let { provider { it } } ?: tasks.buildPlugin.flatMap { it.archiveFile }
val releaseArchive = if (hasCertificate && hasPrivateKey) {
    tasks.signPlugin.flatMap { it.signedArchiveFile }
} else {
    unsignedArchive
}

tasks.verifyPluginSignature {
    inputArchiveFile = tasks.signPlugin.flatMap { it.signedArchiveFile }
}

tasks.register<Sync>("prepareRelease") {
    group = "distribution"
    description = "Signs the plugin when configured and stages the release ZIP with a SHA-256 checksum."
    inputs.property("signingConfigurationComplete", hasCertificate == hasPrivateKey)
    if (hasCertificate && hasPrivateKey) dependsOn(tasks.verifyPluginSignature)
    from(releaseArchive)
    into(releaseDir)
    doFirst {
        require(hasCertificate == hasPrivateKey) {
            "Provide both CERTIFICATE_CHAIN and PRIVATE_KEY, or leave both unset."
        }
    }
    doLast {
        val archive = destinationDir.resolve(releaseArchive.get().asFile.name)
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
        val checksum = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        destinationDir.resolve("SHA256SUMS").writeText("$checksum  ${archive.name}\n")
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
fun Test.runInSandboxIde() {
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

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        runInSandboxIde()
        filter { excludeTestsMatching("*MarketplaceScreenshots") }
    }
}

// Screenshots for the Marketplace page, in build/marketplace-screenshots. The IDE paints only its own window.
val marketplaceScreenshots by intellijPlatformTesting.testIdeUi.registering {
    task {
        runInSandboxIde()
        filter { includeTestsMatching("*MarketplaceScreenshots") }
        val out = layout.buildDirectory.dir("marketplace-screenshots").get().asFile
        systemProperty("versionchecker.screenshots", out.absolutePath)
        outputs.upToDateWhen { false }
    }
}
