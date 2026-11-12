package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.Change
import io.github.khopland.versionchecker.core.Edits
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import java.nio.file.Files

/** A build with several files: repositories from the settings script and variables set in other files. */
class GradleBuildTest : BasePlatformTestCase() {
    private var env = mapOf<String, String>()
    private val provider = GradleProvider { GradleEnvironment(env::get, null) }

    private fun repos(declaration: io.github.khopland.versionchecker.core.Declaration) = (declaration.lookup as MavenLookup).repos

    fun testSettingsRepositoriesAndVariables() {
        myFixture.addFileToProject("settings.gradle.kts", """
            pluginManagement {
                repositories { maven { name = "corp"; url = uri("https://nexus.example.com/plugins"); credentials(PasswordCredentials::class) } }
            }
            dependencyResolutionManagement {
                repositories { maven { name = "corp"; url = uri("https://nexus.example.com/maven"); credentials(PasswordCredentials::class) } }
            }
            include("app", "lib")
        """.trimIndent())
        myFixture.addFileToProject("gradle.properties", "corpUsername=me\nkotlinVersion=2.0.0\nguavaVersion = 32.1.1-jre\n")
        myFixture.addFileToProject("build.gradle", """
            ext.junitVersion = '4.12'
            ext { slf4jVersion = "2.0.9" }
        """.trimIndent())
        val app = myFixture.addFileToProject("app/build.gradle.kts", """
            plugins {
                kotlin("jvm") version "2.0.0"
                id("com.example.tool") version kotlinVersion
            }
            val local = "1.0.0"
            dependencies {
                implementation("com.google.guava:guava:${'$'}guavaVersion")
                testImplementation("junit:junit:${'$'}{rootProject.extra["junitVersion"]}")
                implementation("org.slf4j:slf4j-api:${'$'}{property("slf4jVersion")}")
                implementation("org.example:local:${'$'}local")
                implementation("org.example:unknown:${'$'}unknown")
            }
        """.trimIndent())
        myFixture.addFileToProject("lib/build.gradle", """
            dependencies { implementation "com.google.guava:guava:${'$'}guavaVersion" }
        """.trimIndent())
        env = mapOf("ORG_GRADLE_PROJECT_corpPassword" to "secret")

        val declarations = provider.collect(app)
        assertEquals(listOf("plugin org.jetbrains.kotlin.jvm", "plugin com.example.tool", "com.google.guava:guava", "junit:junit",
            "org.slf4j:slf4j-api", "org.example:local"), declarations.map { it.name })
        val text = app.text
        assertEquals(listOf("2.0.0", "kotlinVersion", "\$guavaVersion", "\${rootProject.extra[\"junitVersion\"]}", "\${property(\"slf4jVersion\")}", "\$local"),
            declarations.map { it.range.substring(text) })
        assertEquals(listOf("2.0.0", "2.0.0", "32.1.1-jre", "4.12", "2.0.9", "1.0.0"), declarations.map { it.current })
        assertEquals(listOf(null, "gradle.properties", "gradle.properties", "build.gradle", "build.gradle", "build.gradle.kts"),
            declarations.map { d -> d.edit!!.file.name.takeIf { d.edit!!.label != null } })

        val plugins = repos(declarations[0]).single()
        assertEquals("https://nexus.example.com/plugins", plugins.base)
        assertEquals(Http.basicAuth("me", "secret"), plugins.headers["Authorization"])
        assertEquals("https://nexus.example.com/maven", repos(declarations[2]).single().base)

        // The other module that uses guavaVersion takes part in the agreement on the shared version.
        val related = provider.related(app, declarations)
        assertEquals(listOf("com.google.guava:guava"), related.map { it.name })
        assertEquals(declarations[2].edit!!.key, related.single().edit!!.key)

        // The fix writes where the version is set.
        Edits.apply(project, listOf(Change(declarations[2].edit!!, "33.0.0-jre")), "Update")
        val properties = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("gradle.properties"))!!
        assertTrue(properties.text.contains("guavaVersion = 33.0.0-jre"))
    }

    fun testProjectRepositoriesWinUnlessSettingsArePreferred() {
        val settings = myFixture.addFileToProject("settings.gradle", """
            dependencyResolutionManagement { repositories { maven { url 'https://settings.example.com' } } }
        """.trimIndent())
        myFixture.addFileToProject("build.gradle", """
            subprojects { repositories { maven { url 'https://shared.example.com' } } }
        """.trimIndent())
        val module = myFixture.addFileToProject("module/build.gradle", """
            repositories { maven { url 'https://module.example.com' } }
            buildscript {
                repositories { gradlePluginPortal() }
                dependencies { classpath 'com.example:gradle-plugin:1.0' }
            }
            dependencies { implementation 'com.example:lib:1.0' }
        """.trimIndent())
        val catalog = myFixture.addFileToProject("gradle/libs.versions.toml", """
            [libraries]
            lib = "com.example:lib:1.0"
            [plugins]
            tool = "com.example.tool:1.0"
        """.trimIndent())

        val declarations = provider.collect(module)
        assertEquals(listOf(MavenRepo.GRADLE_PLUGINS.base), repos(declarations[0]).map { it.base })
        assertEquals(listOf("https://module.example.com", "https://shared.example.com"), repos(declarations[1]).map { it.base })
        val catalogDeclarations = provider.collect(catalog)
        assertEquals(listOf("https://settings.example.com"), repos(catalogDeclarations[0]).map { it.base })
        assertEquals(listOf(MavenRepo.GRADLE_PLUGINS.base), repos(catalogDeclarations[1]).map { it.base })

        myFixture.saveText(settings.virtualFile, """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_SETTINGS
                repositories { maven { url 'https://settings.example.com' } }
            }
        """.trimIndent())
        assertEquals(listOf("https://settings.example.com"), repos(provider.collect(module)[1]).map { it.base })
    }

    fun testInitScriptsAndUserProperties() {
        val home = Files.createTempDirectory("gradle-home")
        try {
            Files.createDirectories(home.resolve("init.d"))
            Files.writeString(home.resolve("init.d/corp.gradle"), """
                allprojects { repositories { maven { name = 'corp'; url = 'https://nexus.example.com/maven'; credentials(PasswordCredentials) } } }
            """.trimIndent())
            Files.writeString(home.resolve("gradle.properties"), "corpUsername=me\ncorpPassword=pw\n")
            val script = myFixture.addFileToProject("build.gradle", "dependencies { implementation 'com.example:lib:1.0' }")
            val withHome = GradleProvider { GradleEnvironment({ null }, home) }
            val repo = repos(withHome.collect(script).single()).single()
            assertEquals("https://nexus.example.com/maven", repo.base)
            assertEquals(Http.basicAuth("me", "pw"), repo.headers["Authorization"])
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    fun testChangedUserPropertiesAreReadAgain() {
        val home = Files.createTempDirectory("gradle-home")
        try {
            Files.writeString(home.resolve("gradle.properties"), "repoUrl=https://one.example.com\n")
            myFixture.addFileToProject("settings.gradle.kts", """
                dependencyResolutionManagement { repositories { maven { url = uri(property("repoUrl")) } } }
            """.trimIndent())
            val script = myFixture.addFileToProject("build.gradle.kts", """dependencies { implementation("com.example:lib:1.0") }""")
            val withHome = GradleProvider { GradleEnvironment({ null }, home) }
            assertEquals(listOf("https://one.example.com"), repos(withHome.collect(script).single()).map { it.base })
            assertSame(withHome.collect(script), withHome.collect(script))

            Files.writeString(home.resolve("gradle.properties"), "repoUrl=https://two.example.com\n")
            Files.setLastModifiedTime(home.resolve("gradle.properties"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            assertEquals(listOf("https://two.example.com"), repos(withHome.collect(script).single()).map { it.base })
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    fun testWithoutDeclaredRepositoriesTheDefaultsStay() {
        val declarations = GradleScripts.collect("""dependencies { implementation("androidx.core:core-ktx:1.12.0") }""", LightVirtualFile("build.gradle.kts"))
        assertEquals(listOf(MavenRepo.GOOGLE.base), repos(declarations.single()).map { it.base })
    }

    fun testScriptsAndBuildsAreReusedUntilTheirTextChanges() {
        myFixture.addFileToProject("settings.gradle.kts", "rootProject.name = \"demo\"")
        myFixture.addFileToProject("gradle.properties", "unused=1")
        val script = myFixture.addFileToProject("app/build.gradle.kts", """
            repositories { maven { url = uri("https://module.example.com") } }
            dependencies { implementation("com.example:lib:1.0") }
        """.trimIndent())

        val first = provider.collect(script)
        val again = provider.collect(script)
        assertSame(GradleBuildFiles.script(script.virtualFile), GradleBuildFiles.script(script.virtualFile))
        // Same instances: neither the script nor the build around it was parsed again.
        assertSame(repos(first.single()).single(), repos(again.single()).single())

        myFixture.saveText(script.virtualFile, """
            repositories { maven { url = uri("https://changed.example.com") } }
            dependencies { implementation("com.example:lib:2.0") }
        """.trimIndent())
        val changed = provider.collect(script)
        assertEquals(listOf("https://changed.example.com"), repos(changed.single()).map { it.base })
        assertEquals(listOf("2.0"), changed.map { it.current })
    }

    fun testDeclarationsAreReusedUntilAPropertyTheyUseChanges() {
        myFixture.addFileToProject("settings.gradle.kts", "include(\"app\")")
        val properties = myFixture.addFileToProject("app/gradle.properties", "libVersion=1.0\n")
        val script = myFixture.addFileToProject("app/build.gradle.kts", """dependencies { implementation("com.example:lib:${'$'}libVersion") }""")
        val other = myFixture.addFileToProject("lib/build.gradle.kts", """dependencies { implementation("com.example:other:3.0") }""")

        val first = provider.collect(script)
        assertEquals(listOf("1.0"), first.map { it.current })
        // Nothing the script reads changed, so it is not scanned again, also not after an edit to another module's script.
        myFixture.saveText(other.virtualFile, """dependencies { implementation("com.example:other:3.1") } // edited""")
        assertSame(first, provider.collect(script))

        myFixture.saveText(properties.virtualFile, "libVersion=2.0\n")
        assertEquals(listOf("2.0"), provider.collect(script).map { it.current })
    }

    fun testEditingRootPropertiesRebuildsTheRepositories() {
        myFixture.addFileToProject("settings.gradle.kts", """
            dependencyResolutionManagement { repositories { maven { url = uri(property("repoUrl")) } } }
        """.trimIndent())
        val properties = myFixture.addFileToProject("gradle.properties", "repoUrl=https://one.example.com\n")
        val script = myFixture.addFileToProject("build.gradle.kts", """dependencies { implementation("com.example:lib:1.0") }""")

        assertEquals(listOf("https://one.example.com"), repos(provider.collect(script).single()).map { it.base })
        myFixture.saveText(properties.virtualFile, "repoUrl=https://two.example.com\n")
        assertEquals(listOf("https://two.example.com"), repos(provider.collect(script).single()).map { it.base })
    }
}
