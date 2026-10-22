package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.gradle.GradleCatalogs
import org.junit.Assert.*
import org.junit.Test

class GradleCatalogRegistrationTest {
    @Test fun `literal Kotlin and Groovy settings retain catalog names and paths`() {
        for (kotlin in listOf(true, false)) {
            val parsed = GradleCatalogs.parse("""
                dependencyResolutionManagement {
                    repositories { mavenCentral() }
                    versionCatalogs {
                        create("tools") { from(files("versions/tools.toml")) }
                        create("testing") { from(files("gradle/tests.versions.toml")) }
                    }
                }
            """.trimIndent(), kotlin)
            assertTrue(parsed.reviews.isEmpty())
            assertEquals(listOf("tools", "testing"), parsed.registrations.map { it.name })
            assertEquals(listOf("versions/tools.toml", "gradle/tests.versions.toml"), parsed.registrations.map { it.path })
        }
    }

    @Test fun `computed published customized conditional and malformed registrations need review`() {
        for (body in listOf(
            "create(catalogName) { from(files(\"versions.toml\")) }",
            "create(\"tools\") { from(\"fixture:catalog:1.0.0\") }",
            "create(\"tools\") { from(files(path)) }",
            "create(\"tools\") { from(files(\"a.toml\", \"b.toml\")) }",
            "create(\"tools\") { from(files(\"a.toml\")); version(\"shared\", \"2.0.0\") }",
            "create(\"tools\") { from(files(\"${'$'}catalogPath\")) }",
            "if (flag) { create(\"tools\") { from(files(\"a.toml\")) } }",
            "create(\"tools\") { from(files(\"a.toml\"))",
            "create(\"tools\") { from(files(\"a.toml\")) }.also { configure(it) }"
        )) {
            val parsed = GradleCatalogs.parse("dependencyResolutionManagement { versionCatalogs { $body } }", true)
            assertFalse(body, parsed.reviews.isEmpty())
        }
    }

    @Test fun `comments and strings do not register catalogs and receivers do not gain ownership`() {
        val ignored = GradleCatalogs.parse("""
            // versionCatalogs { create("fake") { from(files("a.toml")) } }
            val example = "versionCatalogs"
            /* dependencyResolutionManagement { versionCatalogs { } } */
        """.trimIndent(), true)
        assertTrue(ignored.registrations.isEmpty())
        assertTrue(ignored.reviews.isEmpty())
        for (script in listOf(
            "versionCatalogs { create(\"tools\") { from(files(\"a.toml\")) } }",
            "if (flag) { dependencyResolutionManagement { versionCatalogs { } } }",
            "if (flag)\ndependencyResolutionManagement { versionCatalogs { } }",
            "fun catalogs() =\ndependencyResolutionManagement { versionCatalogs { } }",
            "other.dependencyResolutionManagement { versionCatalogs { } }",
            "dependencyResolutionManagement { other.versionCatalogs { } }"
        )) assertFalse(script, GradleCatalogs.parse(script, true).reviews.isEmpty())
    }

    @Test fun `duplicate names are reviewable and one file may have multiple names`() {
        val parsed = GradleCatalogs.parse("""
            dependencyResolutionManagement { versionCatalogs {
                create("tools") { from(files("a.toml")) }
                create("tests") { from(files("a.toml")) }
                create("tools") { from(files("b.toml")) }
            } }
        """.trimIndent(), true)
        assertEquals(listOf("tools", "tests"), parsed.registrations.map { it.name })
        assertEquals(1, parsed.reviews.size)
    }
}
