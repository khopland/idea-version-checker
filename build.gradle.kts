import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdea("2026.1.4")
        testFramework(TestFrameworkType.Platform)
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
