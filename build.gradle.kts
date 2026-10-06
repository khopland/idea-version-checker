import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    compileOnly("org.checkerframework:checker-qual:3.21.2")
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("JavaScript")
        bundledPlugin("com.intellij.modules.json")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    from("LICENSE") { into("META-INF") }
}

tasks.test {
    val mavenIntegration = providers.gradleProperty("mavenIntegration").orElse("false").get()
    systemProperty("versionchecker.mavenIntegration", mavenIntegration)
    if (mavenIntegration != "true") exclude("**/MavenSettingsIntegrationTest*")
    val npmIntegration = providers.gradleProperty("npmIntegration").orElse("false").get()
    systemProperty("versionchecker.npmIntegration", npmIntegration)
    if (npmIntegration != "true") exclude("**/NpmRegistryIntegrationTest*")
    val gradleIntegration = providers.gradleProperty("gradleIntegration").orElse("false").get()
    systemProperty("versionchecker.gradleIntegration", gradleIntegration)
    if (gradleIntegration != "true") exclude("**/GradleRepositoryIntegrationTest*")
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider {
            val entry = changelog.getOrNull(project.version.toString()) ?: changelog.getUnreleased()
            changelog.renderItem(entry.withHeader(false), Changelog.OutputType.HTML)
        }
        ideaVersion {
            sinceBuild = "253.33813.55"
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
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1.4")
        }
    }
}

// Publish the exact archive selected by the release workflow, including its signature.
tasks.named<PublishPluginTask>("publishPlugin") {
    providers.gradleProperty("releaseArchive").orNull?.let {
        archiveFiles.setFrom(layout.projectDirectory.file(it))
    }
}

// The signature verifier consumes signPlugin's output; declare the dependency for Gradle validation.
tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}
