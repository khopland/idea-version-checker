package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*

class IgnoredVersionsTest : BasePlatformTestCase() {
    fun testIgnoresAreExactReversiblePersistedAndKeepNoticesAndOtherReleases() {
        val artifact = ArtifactId("g", "a")
        val declaration = VersionDeclaration(DeclarationId("file", "dependency"), artifact, "1.0", "1.0")
        val candidate = UpdateCandidate(declaration, "1.1")
        val report = UpdateReport(listOf(candidate, candidate.copy(version = "1.2"),
            candidate.copy(declaration = declaration.copy(artifact = ArtifactId("g", "b")))),
            listOf(UpdateNotice(declaration, NoticeKind.DEPRECATED, "Requires replacement")))
        val file = myFixture.configureByText("ignored.txt", "unchanged")
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(file, "Update", null as com.intellij.codeInspection.LocalQuickFix?,
            ProblemHighlightType.WARNING, false)
        val fix = IgnorePublishedVersionFix(IgnoredVersion("maven", artifact, "1.1"))
        fix.applyFix(project, descriptor)
        fix.applyFix(project, descriptor)
        val options = project.service<VersionCheckerSettings>().state
        assertEquals("maven g:a = 1.1", options.ignoredVersions)
        val restored = VersionCheckerSettings().apply { loadState(options.copy()) }
        val filtered = report.withoutIgnored("maven", restored.state)
        assertEquals(2, filtered.candidates.size)
        assertEquals(report.notices, filtered.notices)
        assertEquals(report, report.withoutIgnored("gradle", options))
        assertEquals("unchanged", file.text)
        options.ignoredVersions = ""
        assertEquals(report, report.withoutIgnored("maven", options))
    }

    fun testScopedNpmNamesAndAliasesUseRegistryIdentityAndMalformedRulesDoNotBroadenAnIgnore() {
        val artifact = ArtifactId("npm", "@scope/package")
        val declaration = VersionDeclaration(DeclarationId("file", "dependencies/alias"), artifact, "npm:@scope/package@^1.0.0", "1.0.0")
        val report = UpdateReport(listOf(UpdateCandidate(declaration, "1.2.3")))
        val options = VersionCheckerSettings.Options(ignoredVersions = "npm @scope/package = 1.2.3\nall * = *\nmaven g:a:bad = 1.0\nmalformed")
        assertEquals(setOf(IgnoredVersion("npm", artifact, "1.2.3")), ignoredVersions(options.ignoredVersions))
        assertTrue(report.withoutIgnored("npm", options).candidates.isEmpty())
        assertEquals(1, report.copy(candidates = listOf(report.candidates.single().copy(version = "1.2.4")))
            .withoutIgnored("npm", options).candidates.size)
    }

    fun testMavenSnapshotCoordinatesMatchSettingsWithoutChangingRawReports() {
        val declaration = VersionDeclaration(DeclarationId("pom.xml", "DEPENDENCY:1"),
            ArtifactId("maven", "g:a"), "1.0", "1.0")
        val candidate = UpdateCandidate(declaration, "1.1")
        val report = UpdateReport(listOf(candidate, candidate.copy(version = "1.2"),
            candidate.copy(declaration = declaration.copy(artifact = ArtifactId("maven", "other:a")))))
        val options = VersionCheckerSettings.Options(ignoredVersions = "maven g:a = 1.1")
        assertEquals(report.candidates.drop(1), report.withoutIgnored("maven", options).candidates)
        assertEquals(3, report.candidates.size)
        options.ignoredVersions = ""
        assertSame(report, report.withoutIgnored("maven", options))
    }
}
