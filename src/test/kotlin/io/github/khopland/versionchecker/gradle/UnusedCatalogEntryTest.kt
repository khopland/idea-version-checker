package io.github.khopland.versionchecker.gradle

import com.intellij.codeInspection.InspectionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UnusedCatalogEntryTest : BasePlatformTestCase() {
    private val catalog = """
        [versions]
        junit = "5.10.0"
        kotlin = "2.0.0"
        unused-version = "1.0"   # comment
        script-version = "3.0"

        [libraries]
        junit = "junit:junit:4.13.2"
        junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }
        groovy-core = "org.apache.groovy:groovy:4.0.0"
        groovy-json = "org.apache.groovy:groovy-json:4.0.0"
        guava = { module = "com.google.guava:guava",
                  version = "33.0.0-jre" }
        # commented = "a:b:1.0"
        found = "org.example:found:1.0"

        [bundles]
        groovy = ["groovy-core",
                  "groovy-json"]

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = { ref = "kotlin" } }
        shadow = "com.gradleup.shadow:9.0.0"
    """.trimIndent()

    private val script = """
        plugins { alias(libs.plugins.kotlin.jvm) }
        dependencies {
            testImplementation(libs.junit.api)
            implementation(libs.bundles.groovy)
            println(libs.versions.script.version.get())
            implementation(libs.findLibrary("found").get())
        }
    """.trimIndent()

    fun testUnusedEntries() {
        assertEquals(listOf("unused-version", "junit", "guava", "shadow"), CatalogEntries.unused(catalog, listOf(script)).map { it.alias })
        val guava = CatalogEntries.parse(catalog).single { it.alias == "guava" }
        assertTrue(guava.lines.substring(catalog).endsWith("version = \"33.0.0-jre\" }\n"))
    }

    fun testInspectionReadsTheBuildAndRemovesTheEntry() {
        myFixture.addFileToProject("app/build.gradle.kts", script)
        myFixture.addFileToProject("settings.gradle.kts", """include("app")""")
        val file = myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val problems = UnusedCatalogEntryInspection().checkFile(file, InspectionManager.getInstance(project), true)!!
        assertEquals(listOf("Version unused-version is not used", "Library junit is not used", "Library guava is not used", "Plugin shadow is not used"),
            problems.map { it.descriptionTemplate })

        val guava = problems.single { "guava" in it.descriptionTemplate }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { guava.fixes!!.single().applyFix(project, guava) }
        assertFalse(myFixture.editor.document.text.contains("guava"))
        assertTrue(myFixture.editor.document.text.contains("groovy-json = \"org.apache.groovy:groovy-json:4.0.0\"\n# commented"))
    }

    fun testOtherCatalogsSkipTheCheck() {
        myFixture.addFileToProject("build.gradle.kts", script)
        myFixture.addFileToProject("settings.gradle.kts", """
            dependencyResolutionManagement { versionCatalogs { create("deps") { from(files("gradle/deps.versions.toml")) } } }
        """.trimIndent())
        val file = myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
        assertNull(UnusedCatalogEntryInspection().checkFile(file, InspectionManager.getInstance(project), false))
    }

    fun testIncludedBuildCodeCounts() {
        myFixture.addFileToProject("build.gradle.kts", script)
        myFixture.addFileToProject("settings.gradle.kts", """includeBuild("build-logic")""")
        myFixture.addFileToProject("build-logic/settings.gradle.kts", """
            dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }
        """.trimIndent())
        myFixture.addFileToProject("build-logic/src/main/kotlin/Conventions.kt", """val guava = libs.findLibrary("guava")""")
        val file = myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
        val problems = UnusedCatalogEntryInspection().checkFile(file, InspectionManager.getInstance(project), false)!!
        assertDoesntContain(problems.map { it.descriptionTemplate }, "Library guava is not used")
    }
}
