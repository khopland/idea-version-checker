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
    implementation("org.tomlj:tomlj:2.2.0")
    implementation("org.snakeyaml:snakeyaml-engine:3.2")
    compileOnly("org.checkerframework:checker-qual:4.3.1")
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.4")
        testFramework(TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("JavaScript")
        bundledPlugin("com.intellij.modules.json")
    }
}

kotlin {
    jvmToolchain(21)
    // Inherit SDK interface defaults instead of emitting compatibility bridges to legacy APIs.
    compilerOptions.jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
}

tasks.processResources {
    from("LICENSE") { into("META-INF") }
    from(project(":maven-helper").tasks.named("jar")) {
        into("maven-helper")
        rename { "version-checker-maven-helper.jar" }
    }
}

tasks.test {
    val pluginArchive = tasks.named<Zip>("buildPlugin")
    dependsOn(pluginArchive)
    systemProperty("versionchecker.pluginArchive", pluginArchive.get().archiveFile.get().asFile.absolutePath)
    // Keep opt-in integration suites in one place so local and CI selection agree.
    mapOf(
        "mavenIntegration" to "MavenSettingsIntegrationTest",
        "npmIntegration" to "NpmRegistryIntegrationTest",
        "nodeIntegration" to "NodeRegistryIntegrationTest",
        "gradleIntegration" to "GradleRepositoryIntegrationTest"
    ).forEach { (option, testClass) ->
        val enabled = providers.gradleProperty(option).orElse("false").get()
        systemProperty("versionchecker.$option", enabled)
        if (enabled != "true") exclude("**/$testClass*")
    }
    systemProperty("versionchecker.mavenLatencyBenchmark",
        providers.gradleProperty("mavenLatencyBenchmark").orElse("false").get())
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
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1.4")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2.3")
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

// Compile against the minimum IDE, then run Maven 4 regressions on its supported IDE runtime.
val maven4Home = providers.gradleProperty("maven4Home")
intellijPlatformTesting {
    testIde.register("testMaven4") {
        version = "2026.2.3"
        testFramework(TestFrameworkType.Platform)
        plugins {
            // Obfuscated Ultimate extensions cannot share the test framework's forced core classloader.
            disablePlugin("com.intellij.modules.ultimate")
            bundledPlugin("com.intellij.java")
            bundledPlugin("org.jetbrains.idea.maven")
            bundledPlugin("com.intellij.gradle")
            bundledPlugin("JavaScript")
            bundledPlugin("com.intellij.modules.json")
        }
        task {
            dependsOn("buildPlugin")
            systemProperty("versionchecker.mavenIntegration", "true")
            systemProperty("versionchecker.mavenHome", maven4Home.orElse("").get())
            systemProperty("versionchecker.pluginArchive", tasks.named<Zip>("buildPlugin").get().archiveFile.get().asFile.absolutePath)
            filter {
                includeTestsMatching("*MavenSettingsIntegrationTest.testSettingsProfileMirrorAndServerAuthentication")
                includeTestsMatching("*MavenSettingsIntegrationTest.testMaven4Model41SettingsProfileMirrorAndServerAuthentication")
                includeTestsMatching("*MavenSettingsIntegrationTest.testActiveProfilePropertyEditsChangeTheNativeEffectiveDependencyVersion")
                includeTestsMatching("*MavenInheritedVersionFixTest.testNativeEffectivePomUsesNewInheritedPropertyForManagedOnlyChild")
                includeTestsMatching("*MavenInheritedVersionFixTest.testNativeEffectivePomSeesEveryKindOfUpdate")
                includeTestsMatching("*MavenSettingsIntegrationTest.testMinimumReleaseAgeWithholdsRecentVersionsInNativeAndFallbackChecks")
                includeTestsMatching("*MavenSettingsIntegrationTest.testMetadataResponsesKeepTheSelectedModulesRepositories")
                includeTestsMatching("*MavenSettingsIntegrationTest.testGoalProcessUsesImportVmOptionsAndExplicitProfilesWithoutSavingUnrelatedDocuments")
                includeTestsMatching("*MavenSettingsIntegrationTest.testGoalProcessRejectsMavenConfigDisabledInIde")
                includeTestsMatching("*MavenSettingsIntegrationTest.testSuccessfulCliShortcutCannotBeReportedAsNoUpdates")
            }
            doFirst {
                require(systemProperties["versionchecker.mavenHome"].toString().isNotBlank()) { "Set -Pmaven4Home to an extracted Maven 4 distribution" }
            }
        }
    }
}

if (maven4Home.isPresent) {
    // IDEA's Maven server locates a combined telemetry JAR by class. External split SDK JARs
    // from the test framework would leave its remote process without API/context classes.
    configurations.configureEach { exclude(group = "io.opentelemetry") }
}
