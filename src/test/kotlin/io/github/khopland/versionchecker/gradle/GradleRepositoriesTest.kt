package io.github.khopland.versionchecker.gradle

import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradleRepositoriesTest {
    private fun parse(text: String, properties: Map<String, String> = emptyMap(), env: Map<String, String> = emptyMap()): DeclaredRepos {
        val script = ScriptText(text)
        return GradleRepositories.parse(script, GradleValues(script, properties::get, env::get))
    }

    @Test
    fun blocksSkipStringsAndComments() {
        val script = ScriptText("""
            val brace = "{" // {
            /* } { */
            pluginManagement.repositories { maven("https://a") { name = "x" } }
            allprojects { project -> repositories { } }
        """.trimIndent())
        assertEquals(listOf(listOf("pluginManagement", "repositories"), listOf("maven"), listOf("allprojects"), listOf("repositories")),
            script.blocks.map { it.chain })
        assertEquals("\"https://a\"", script.blocks[1].args)
        assertEquals(listOf("allprojects", "repositories"), script.blocks[3].path)
    }

    @Test
    fun settingsScopesAndMode() {
        val declared = parse("""
            pluginManagement {
                repositories {
                    maven("https://plugins.example.com/m2")
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    mavenCentral()
                    // maven("https://commented.example.com")
                    maven { url = uri("https://nexus.example.com/repository/maven-public/") }
                    mavenLocal()
                    maven { url = uri("file:///tmp/repo") }
                    google { content { includeGroupByRegex("androidx.*") } }
                }
            }
        """.trimIndent())
        assertEquals(listOf("https://plugins.example.com/m2", MavenRepo.GRADLE_PLUGINS.base), declared[RepoScope.PLUGINS].map { it.base })
        assertEquals(listOf(MavenRepo.CENTRAL.base, "https://nexus.example.com/repository/maven-public", MavenRepo.GOOGLE.base),
            declared[RepoScope.SETTINGS].map { it.base })
        assertEquals(true, declared.preferSettings)
    }

    @Test
    fun projectSharedBuildscriptAndPublishing() {
        val declared = parse("""
            buildscript { repositories { google() } }
            allprojects { repositories { maven { url 'https://shared.example.com' } } }
            repositories { maven(url = "https://project.example.com") }
            publishing { repositories { maven { url = uri("https://upload.example.com") } } }
        """.trimIndent())
        assertEquals(listOf(MavenRepo.GOOGLE.base), declared[RepoScope.BUILDSCRIPT].map { it.base })
        assertEquals(listOf("https://shared.example.com"), declared[RepoScope.SHARED].map { it.base })
        assertEquals(listOf("https://project.example.com"), declared[RepoScope.PROJECT].map { it.base })
        assertEquals(false, declared.preferSettings)
    }

    @Test
    fun credentials() {
        val declared = parse("""
            repositories {
                maven {
                    name = "corp"
                    url = uri("${'$'}{nexusUrl}/repository/maven")
                    credentials(PasswordCredentials::class)
                }
                maven {
                    url = uri("https://kotlin.example.com")
                    credentials {
                        username = providers.gradleProperty("kotlinUser").get()
                        password = System.getenv("KOTLIN_TOKEN") ?: "fallback"
                    }
                }
                maven {
                    url "https://groovy.example.com"
                    credentials {
                        username groovyUser
                        password = token
                    }
                }
                maven("https://gitlab.example.com/api/v4/groups/1/-/packages/maven") {
                    credentials(HttpHeaderCredentials::class) {
                        name = "Private-Token"
                        value = findProperty("gitLabToken") as String?
                    }
                    authentication { create<HttpHeaderAuthentication>("header") }
                }
            }
            val token = "local-secret"
        """.trimIndent(), properties = mapOf("nexusUrl" to "https://nexus.example.com", "corpUsername" to "me", "corpPassword" to "pw",
            "kotlinUser" to "k", "groovyUser" to "g", "gitLabToken" to "glpat"), env = mapOf("KOTLIN_TOKEN" to "kt"))
        val repos = declared[RepoScope.PROJECT]
        assertEquals(listOf("https://nexus.example.com/repository/maven", "https://kotlin.example.com", "https://groovy.example.com",
            "https://gitlab.example.com/api/v4/groups/1/-/packages/maven"), repos.map { it.base })
        assertEquals("corp", repos[0].id)
        assertEquals(Http.basicAuth("me", "pw"), repos[0].headers["Authorization"])
        assertEquals(Http.basicAuth("k", "kt"), repos[1].headers["Authorization"])
        assertEquals(Http.basicAuth("g", "local-secret"), repos[2].headers["Authorization"])
        assertEquals(mapOf("Private-Token" to "glpat"), repos[3].headers)
    }

    @Test
    fun missingCredentialsExplainWhatToSet() {
        val repos = parse("""
            repositories {
                maven { name = "corp"; url = "https://nexus.example.com"; credentials(PasswordCredentials::class) }
                maven { url = "https://env.example.com"; credentials { username = "me"; password = System.getenv("TOKEN") } }
                maven { url = "https://open.example.com" }
            }
        """.trimIndent())[RepoScope.PROJECT]
        assertEquals(emptyMap<String, String>(), repos[0].headers)
        assertEquals("Set the Gradle property corpUsername and Gradle property corpPassword for the IDE: " +
            "Gradle properties in ~/.gradle/gradle.properties, environment variables before the IDE starts", repos[0].hint)
        assertEquals(true, repos[1].hint!!.startsWith("Set the environment variable TOKEN for the IDE"))
        assertEquals("Add credentials to the repository in the build script", repos[2].hint)
    }

    @Test
    fun unresolvedUrlIsSkipped() {
        assertEquals(emptyList<MavenRepo>(), parse("""repositories { maven { url = uri(nexusUrl) } }""")[RepoScope.PROJECT])
    }

    @Test
    fun values() {
        val script = ScriptText("""
            val base = providers.gradleProperty("base").getOrElse("x")
            def other = base
        """.trimIndent())
        val values = GradleValues(script, mapOf("base" to "b")::get) { null }
        assertEquals("b", values.resolve("other"))
        assertEquals("b/path", values.resolve("\"${'$'}base/path\""))
        assertNull(values.resolve("\"${'$'}{missing}/path\""))
    }
}
