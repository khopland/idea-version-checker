import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.changelog.Changelog

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
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
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider {
            changelog.renderItem(changelog.get(project.version.toString()).withHeader(false), Changelog.OutputType.HTML)
        }
        ideaVersion {
            sinceBuild = "253.33813.55"
        }
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1.4")
        }
    }
}
