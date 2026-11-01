package io.github.khopland.versionchecker.gradle

import com.intellij.testFramework.LightVirtualFile
import io.github.khopland.versionchecker.repo.MavenLookup
import org.junit.Assert.assertEquals
import org.junit.Test

class GradleProviderTest {
    private val file = LightVirtualFile("build.gradle.kts")

    @Test
    fun scripts() {
        val text = """
            plugins {
                id("org.jetbrains.intellij.platform") version "2.19.0"
                id 'com.example.groovy' version '1.0'
                kotlin("jvm") version "2.4.21"
                id("no.version")
            }
            dependencies {
                implementation("com.google.guava:guava:33.0.0-jre")
                testImplementation 'junit:junit:4.13.2'
                implementation(platform("org.junit:junit-bom:5.10.0"))
                implementation("org.example:dynamic:1.+")
                implementation("org.example:templated:${'$'}version")
                // implementation("org.example:commented:1.0")
                implementation("androidx.core:core-ktx:1.12.0")
            }
        """.trimIndent()
        val declarations = GradleScripts.collect(text, file)
        assertEquals(listOf("plugin org.jetbrains.intellij.platform", "plugin com.example.groovy", "plugin org.jetbrains.kotlin.jvm",
            "com.google.guava:guava", "junit:junit", "org.junit:junit-bom", "androidx.core:core-ktx"), declarations.map { it.name })
        assertEquals(listOf("2.19.0", "1.0", "2.4.21", "33.0.0-jre", "4.13.2", "5.10.0", "1.12.0"), declarations.map { it.range.substring(text) })
        assertEquals("org.jetbrains.kotlin.jvm.gradle.plugin", (declarations[2].lookup as MavenLookup).artifactId)
        assertEquals("https://dl.google.com/dl/android/maven2", (declarations[6].lookup as MavenLookup).repos.single().base)
    }

    @Test
    fun catalog() {
        val text = """
            [versions]
            kotlin = "2.0.0"
            junit = "5.10.0"   # comment
            strict = { strictly = "1.0" }

            [libraries]
            junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }
            junit-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit" }
            guava = "com.google.guava:guava:33.0.0-jre"
            slf4j = { module = "org.slf4j:slf4j-api", version = "2.0.9" }
            managed = { module = "org.example:managed" }
            strict-lib = { module = "org.example:strict", version.ref = "strict" }

            [plugins]
            kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
            detekt = "io.gitlab.arturbosch.detekt:1.23.0"
        """.trimIndent()
        val declarations = GradleCatalog.collect(text, LightVirtualFile("libs.versions.toml"))
        assertEquals(listOf("org.junit.jupiter:junit-jupiter-api", "org.junit.jupiter:junit-jupiter-engine", "com.google.guava:guava",
            "org.slf4j:slf4j-api", "plugin org.jetbrains.kotlin.jvm", "plugin io.gitlab.arturbosch.detekt"), declarations.map { it.name })
        // Shared references highlight the reference and edit the [versions] entry.
        assertEquals("junit", declarations[0].range.substring(text))
        assertEquals("5.10.0", declarations[0].edit!!.range.substring(text))
        assertEquals(declarations[0].edit!!.key, declarations[1].edit!!.key)
        assertEquals(listOf("5.10.0", "5.10.0", "33.0.0-jre", "2.0.9", "2.0.0", "1.23.0"), declarations.map { it.current })
        assertEquals("33.0.0-jre", declarations[2].range.substring(text))
        assertEquals("1.23.0", declarations[5].range.substring(text))
    }

    @Test
    fun thisRepositorysSettingsScript() {
        val text = java.io.File("settings.gradle.kts").readText()
        val names = GradleScripts.collect(text, file).map { it.name }
        assertEquals(listOf("plugin org.jetbrains.kotlin.jvm", "plugin org.jetbrains.changelog",
            "plugin org.gradle.toolchains.foojay-resolver-convention", "plugin org.jetbrains.intellij.platform.settings"), names)
    }
}
