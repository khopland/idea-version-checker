package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.gradle.*
import org.junit.Assert.*
import org.junit.Test

class GradleDeclarationsTest {
    @Test fun classifierAndExtensionNotationRetainAnchoredReviewCoverage() {
        for (extension in listOf("gradle", "gradle.kts")) {
            val text = """dependencies {
                implementation("g:classifier:1.2.3:tests")
                implementation("g:extension:1.2.3@zip")
                implementation("g:both:1.2.3:tests@jar")
                implementation("g:plain:1.2.3")
            }"""
            val declarations = GradleDeclarations.parse("build.$extension", text)
            assertEquals(listOf("classifier", "extension", "both", "plain"), declarations.map { it.declaration.artifact.name })
            assertEquals(listOf("", "", "", "1.2.3"), declarations.map { it.declaration.baseline })
            assertTrue(declarations.take(3).all { it.reason!!.contains("manual review") })
            assertTrue(declarations.all { it.range!!.substring(text) == "1.2.3" })
        }
    }

    @Test fun mapNotationIsAnchoredReviewOnlyIncludingComputedAndMissingFields() {
        for ((file, notation) in listOf(
            "build.gradle" to "implementation group: 'g', name: 'a', version: '1.2.3', classifier: 'tests'",
            "build.gradle" to "implementation(group: 'g', name: 'a', version: '1.2.3', ext: 'jar')",
            "build.gradle" to "implementation group: 'g',\n name: 'a',\n version: '1.2.3'",
            "build.gradle" to "implementation([group: 'g', name: 'a', version: '1.2.3'])",
            "build.gradle.kts" to "implementation(mapOf(\"group\" to \"g\", \"name\" to \"a\", \"version\" to \"1.2.3\"))",
            "build.gradle" to "implementation group: groupName, name: 'a', version: versionName",
            "build.gradle.kts" to "implementation(mapOf(\"group\" to groupName, \"name\" to \"a\"))"
        )) {
            val text = "dependencies {\n$notation\nimplementation(\"g:plain:1.2.3\")\n}"
            val declarations = GradleDeclarations.parse(file, text)
            assertEquals(notation, 2, declarations.size)
            val map = declarations.first()
            assertTrue(notation, map.declaration.baseline.isEmpty())
            assertTrue(notation, map.reason!!.contains("manual review"))
            assertTrue(notation, map.range!!.substring(text).startsWith("implementation"))
            assertEquals("1.2.3", declarations.last().declaration.baseline)
            assertEquals("1.2.3", declarations.last().range!!.substring(text))
        }
    }

    @Test fun hugeNumericComponentsHaveConsistentOrderingAndChangeKinds() {
        val cases = listOf(
            Triple("2147483648.1.0", "2147483649.1.0", io.github.khopland.versionchecker.core.VersionChangeKind.MAJOR),
            Triple("1.9223372036854775808.0", "1.9223372036854775809.0", io.github.khopland.versionchecker.core.VersionChangeKind.MINOR),
            Triple("1.0.999999999999999999999999", "1.0.1000000000000000000000000", io.github.khopland.versionchecker.core.VersionChangeKind.PATCH),
            Triple("2147483648.1.Final", "2147483649.1.GA", io.github.khopland.versionchecker.core.VersionChangeKind.MAJOR),
            Triple("1.2147483648-jre", "1.2147483649.JRE", io.github.khopland.versionchecker.core.VersionChangeKind.MINOR),
            Triple("1.0-sp2147483648", "1.0.sp2147483649", io.github.khopland.versionchecker.core.VersionChangeKind.PATCH)
        )
        for ((current, latest, kind) in cases) {
            assertTrue("$current -> $latest", GradleVersions.newer(current, latest))
            assertFalse("$latest -> $current", GradleVersions.newer(latest, current))
            assertEquals(kind, GradleVersions.kind(current, latest))
        }
        assertFalse(GradleVersions.newer("1.2147483648-jre", "2.2147483649-android"))
        assertFalse(GradleVersions.newer("2147483648.0", "2147483648.0.0.Final"))
    }

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
    @Test fun malformedTypedLibrariesRetainValidSiblingsAndAnchoredReview() {
        for (malformed in listOf(
            "123", "true", "[1, 2]", "'g:missing-version'",
            "{ module = 123, version = '1.2.3' }",
            "{ group = false, name = 'alpha', version = '1.2.3' }",
            "{ group = 'g', name = ['alpha'], version = '1.2.3' }",
            "{ module = 'g:a', version = 123 }",
            "{ module = 'g:a', version.ref = 123 }",
            "{ module = 'g:a', version = { strictly = 123 } }"
        )) {
            val text = "[libraries]\nalpha = $malformed\nbeta = 'g:b:1.2.3'"
            val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
            assertEquals(malformed, 2, declarations.size)
            val invalid = declarations.first()
            assertEquals("libraries/alpha", invalid.declaration.id.location)
            assertTrue(malformed, invalid.declaration.baseline.isEmpty())
            assertNotNull(malformed, invalid.reason)
            assertNotNull(malformed, invalid.range)
            assertEquals("1.2.3", declarations.last().declaration.baseline)
            assertEquals("1.2.3", declarations.last().range!!.substring(text))
        }
    }

    @Test fun malformedCatalogSectionsDoNotThrowOrHideOtherSections() {
        for (section in listOf("libraries", "versions", "plugins")) {
            val text = "$section = 123\n" + if (section == "libraries") "" else
                "[libraries]\nbeta = 'g:b:1.2.3'"
            val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
            val review = declarations.single { it.declaration.id.location == section }
            assertTrue(review.declaration.baseline.isEmpty())
            assertNotNull(review.reason)
            assertNotNull(review.range)
            if (section != "libraries") assertEquals("1.2.3", declarations.last().declaration.baseline)
        }
    }

    @Test fun malformedVersionsAreReviewedWithoutDiscardingValidReferences() {
        val text = """
            [versions]
            broken = 123
            rich = { strictly = 123 }
            valid = "1.2.3"
            [libraries]
            invalid = { module = "g:a", version.ref = "broken" }
            missing = { module = "g:missing", version.ref = "absent" }
            valid = { module = "g:b", version.ref = "valid" }
        """.trimIndent()
        val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
        assertEquals(setOf("versions/broken", "versions/rich", "libraries/invalid", "libraries/missing"),
            declarations.filter { it.reason != null }.map { it.declaration.id.location }.toSet())
        assertTrue(declarations.filter { it.reason != null }.all { it.declaration.baseline.isEmpty() && it.range != null })
        assertEquals("1.2.3", declarations.last().declaration.baseline)
    }

    @Test fun malformedPluginReferencesCannotAuthorizeSharedVersionEdits() {
        for (plugin in listOf("123", "{ id = 'g.plugin', version.ref = 123 }", "{ id = 123, version.ref = 'shared' }")) {
            val text = """
                [versions]
                shared = "1.2.3"
                [libraries]
                shared = { module = "g:a", version.ref = "shared" }
                direct = "g:b:1.2.3"
                [plugins]
                broken = $plugin
            """.trimIndent()
            val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
            assertEquals(3, declarations.size)
            assertTrue(declarations.single { it.declaration.id.location == "libraries/shared" }.declaration.baseline.isEmpty())
            assertEquals("1.2.3", declarations.single { it.declaration.id.location == "libraries/direct" }.declaration.baseline)
            val review = declarations.single { it.declaration.id.location == "plugins/broken" }
            assertTrue(review.declaration.baseline.isEmpty())
            assertNotNull(review.reason)
            assertNotNull(review.range)
        }
    }

    @Test fun malformedLibraryConsumersBlockSharedVersionEdits() {
        for (entry in listOf(
            "{ module = 123, version.ref = 'shared' }",
            "{ module = 'g:broken', version.ref = 123 }"
        )) {
            val text = """
                [versions]
                shared = "1.2.3"
                [libraries]
                broken = $entry
                shared = { module = "g:shared", version.ref = "shared" }
                direct = "g:direct:1.2.3"
            """.trimIndent()
            val declarations = GradleDeclarations.parse("gradle/libs.versions.toml", text)
            assertTrue(declarations.single { it.declaration.id.location == "libraries/shared" }.declaration.baseline.isEmpty())
            assertTrue(declarations.single { it.declaration.id.location == "libraries/shared" }.reason!!.contains("ownership"))
            assertEquals("1.2.3", declarations.single { it.declaration.id.location == "libraries/direct" }.declaration.baseline)
        }
    }

    @Test fun kotlinDivisionDoesNotHideFollowingDependencies() {
        val text = """dependencies {
            val ratio = 8 / 2
            implementation("g:after-division:1.2.3")
        }"""
        val declaration = GradleDeclarations.parse("build.gradle.kts", text).single()
        assertEquals("after-division", declaration.declaration.artifact.name)
        assertEquals("1.2.3", declaration.range!!.substring(text))
        assertNull(declaration.reason)
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
    @Test fun customizationLookaheadPreservesWrappedCallsCommentsAndFollowingStatements() {
        for (extension in listOf("gradle", "gradle.kts")) {
            val text = """dependencies {
                implementation(platform("g:custom-bom:1.2.3")) /* keep */ { because("review") }
                api(enforcedPlatform("g:plain-bom:1.2.3"));
                implementation("g:custom:1.2.3")
                    // The customization block belongs to the preceding call.
                    { because("review") }
                implementation("g:plain:1.2.3")
                println(")") { println("{") }
                testImplementation 'g:last:1.2.3'
            }""".trimIndent()
            val declarations = GradleDeclarations.parse("build.$extension", text)
            assertEquals(listOf("custom-bom", "plain-bom", "custom", "plain", "last"), declarations.map { it.declaration.artifact.name })
            assertEquals(listOf("", "1.2.3", "", "1.2.3", "1.2.3"), declarations.map { it.declaration.baseline })
            assertTrue(declarations.filter { it.declaration.baseline.isEmpty() }.all { it.reason!!.contains("manual review") })
            assertTrue(declarations.all { it.range!!.substring(text) == "1.2.3" })
        }
    }
    @Test fun stableVersionsNeverDowngradeAndKeepPrereleasesForReview() {
        for (version in listOf("1.0-RC1", "2.0-beta", "3.0-SNAPSHOT", "1.+", "latest.release", "[1,2[")) assertFalse(version, GradleVersions.fixed(version))
        assertTrue(GradleVersions.fixed("33.4.0-jre"))
        assertTrue(GradleVersions.newer("1.2.3", "1.2.10"))
        assertFalse(GradleVersions.newer("2.0.0", "1.9.9"))
        assertFalse(GradleVersions.newer("1.0", "1.0.0"))
    }
    @Test fun testStableQualifierCaseAndVariantChannels() {
        assertTrue(GradleVersions.newer("1.0.Final", "1.1.final"))
        assertTrue(GradleVersions.newer("1.0.GA", "1.1.ga"))
        assertTrue(GradleVersions.newer("1.0.JRE", "1.1.jre"))
        assertFalse(GradleVersions.newer("1.0.JRE", "2.0.android"))
        assertFalse(GradleVersions.newer("1.0.android", "2.0.JRE"))
        assertFalse(GradleVersions.newer("1.0.Final", "1.0.ga"))
    }

    @Test fun testServicePackNumbersAdvanceWithoutDowngrades() {
        for ((current, latest) in listOf("1.0-sp1" to "1.0-sp2", "1.0-sp2" to "1.0-sp10", "1.0-SP1" to "1.0.sp2")) {
            assertTrue("$current -> $latest", GradleVersions.newer(current, latest))
            assertFalse("$latest -> $current", GradleVersions.newer(latest, current))
            assertEquals(io.github.khopland.versionchecker.core.VersionChangeKind.PATCH, GradleVersions.kind(current, latest))
        }
        assertTrue(GradleVersions.newer("1.0.Final", "1.0-sp1"))
        assertFalse(GradleVersions.newer("1.0-sp1", "1.0.Final"))
    }

}
