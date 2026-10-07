package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.gradle.*
import org.junit.Assert.*
import org.junit.Test

class GradleDeclarationsTest {
    @Test fun helperArgumentsWithMatchingCoordinatesRequireReview() {
        for (extension in listOf("gradle", "gradle.kts")) {
            val text = """dependencies {
                implementation("g:a:1.2.3")
                verifyNotation("g:a:1.2.3")
                helper.implementation("g:a:1.2.3")
            }"""
            val declarations = GradleDeclarations.parse("build.$extension", text)
            assertEquals(listOf("1.2.3", "", ""), declarations.map { it.declaration.baseline })
            assertTrue(declarations.drop(1).all { it.reason != null })
        }
    }
    @Test fun onlyPlatformWrappersInsideDependencyCallsAreAutomaticallyEditable() {
        val text = """dependencies {
            implementation(platform("g:bom:1.2.3"))
            implementation enforcedPlatform('g:enforced:1.2.3')
            testImplementation(testFixtures("g:fixtures:1.2.3"))
            implementation(helper("g:helper:1.2.3"))
            helper(platform("g:wrapped:1.2.3"))
            customConfiguration("g:custom:1.2.3")
        }"""
        val declarations = GradleDeclarations.parse("build.gradle", text)
        assertEquals(listOf("1.2.3", "1.2.3", "", "", "", ""), declarations.map { it.declaration.baseline })
        assertTrue(declarations.drop(2).all { it.reason!!.contains("manual review") })
    }
    @Test fun groovyAndKotlinLiteralDependenciesExcludeCommentsAndUnrelatedStrings() {
        for (extension in listOf("gradle", "gradle.kts")) {
            val text = """
                // dependencies { implementation("ignored:comment:1.2.3") }
                val note = "ignored:note:1.2.3"
                buildscript { dependencies { classpath("ignored:buildscript:1.2.3") } }
                dependencies {
                    implementation("org.example:alpha:1.2.3")
                    testImplementation 'org.example:beta:2.0.0'
                    implementation(platform("org.example:bom:3.1.0"))
                    // api("ignored:line:1.2.3")
                    /* api("ignored:block:1.2.3") */
                    println("ignored:print:1.2.3")
                }
            """.trimIndent()
            val declarations = GradleDeclarations.parse("build.$extension", text)
            assertEquals(listOf("alpha", "beta", "bom"), declarations.map { it.declaration.artifact.name })
            assertEquals(listOf("1.2.3", "2.0.0", "3.1.0"), declarations.map { it.range!!.substring(text) })
            assertTrue(declarations.all { it.reason == null })
        }
    }
    @Test fun dynamicAndCompositeVersionsRequireReview() {
        val text = """dependencies {
            implementation("g:a:1.+")
            implementation("g:b:latest.release")
            implementation("g:c:${'$'}version")
            implementation("g:d:1.2.3" + suffix)
        }"""
        assertEquals(4, GradleDeclarations.parse("build.gradle.kts", text).count { it.declaration.baseline.isEmpty() && it.reason != null })
    }
    @Test fun catalogSupportsInlineAndReferencedVersionsWithoutChangingCoordinates() {
        val text = """
            [versions]
            shared = "1.2.3" # preserve this comment
            [libraries]
            alpha = { module = "org.example:alpha", version.ref = "shared" }
            beta = { group = "org.example", name = "beta", version.ref = "shared" }
            direct = { module = "org.example:direct", version = '2.3.4' }
            short = "org.example:short:3.4.5"
            rich = { module = "org.example:rich", version = { strictly = "[1,2[" } }
        """.trimIndent()
        val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
        assertEquals(5, declarations.size)
        assertEquals(declarations[0].range, declarations[1].range)
        assertEquals(listOf("1.2.3", "1.2.3", "2.3.4", "3.4.5"), declarations.take(4).map { it.range!!.substring(text) })
        assertTrue(declarations.last().declaration.baseline.isEmpty())
    }
    @Test fun catalogPluginConsumersBlockSharedLibraryUpdates() {
        val text = """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:a", version.ref = "shared" }
            [plugins]
            alpha = { id = "g.plugin", version.ref = "shared" }
            direct = { id = "g.other", version = "2.0.0" }
        """.trimIndent()
        val declaration = GradleDeclarations.parse("gradle/libs.versions.toml", text).single()
        assertTrue(declaration.declaration.baseline.isEmpty())
        assertTrue(declaration.reason!!.contains("plugin"))
    }
    @Test fun malformedCatalogsAreNotEdited() {
        assertTrue(GradleDeclarations.parse("libs.versions.toml", "[libraries]\na='g:a:1.0'\na='g:a:2.0'").isEmpty())
    }
    @Test fun groovySlashyStringsAndNestedCommentsAreNotDeclarations() {
        val text = """
            dependencies {
                def slashy = /implementation("g:fake:1.2.3")/
                def dollarSlashy = ${'$'}/implementation("g:also-fake:1.2.3")/${'$'}
                /* outer /* inner */ implementation("g:comment:1.2.3") */
                implementation("g:real:1.2.3")
            }
        """.trimIndent()
        assertEquals(listOf("real"), GradleDeclarations.parse("build.gradle", text).map { it.declaration.artifact.name })
    }
    @Test fun constraintsAndDependencyCustomizationRequireReview() {
        val text = """
            dependencies {
                implementation("g:custom:1.2.3") { version { strictly("1.2.3") } }
                constraints { implementation("g:constraint:1.2.3") }
                implementation("g:plain:1.2.3")
            }
        """.trimIndent()
        val declarations = GradleDeclarations.parse("build.gradle.kts", text)
        assertEquals(listOf("", "", "1.2.3"), declarations.map { it.declaration.baseline })
    }
    @Test fun stableVersionsNeverDowngradeAndKeepPrereleasesForReview() {
        for (version in listOf("1.0-RC1", "2.0-beta", "3.0-SNAPSHOT", "1.+", "latest.release", "[1,2[")) assertFalse(version, GradleVersions.fixed(version))
        assertTrue(GradleVersions.fixed("33.4.0-jre"))
        assertTrue(GradleVersions.newer("1.2.3", "1.2.10"))
        assertFalse(GradleVersions.newer("2.0.0", "1.9.9"))
        assertFalse(GradleVersions.newer("1.0", "1.0.0"))
    }
}
