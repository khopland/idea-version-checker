package io.github.khopland.versionchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.ArtifactId
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.VersionDeclaration
import io.github.khopland.versionchecker.npm.NpmVersionEdit

class VersionReviewItemTest : BasePlatformTestCase() {
    fun testNpmCapturedSourceAndFactsSurviveCombinedSelectionWithoutParsingLocation() {
        val file = myFixture.addFileToProject("review/package.json", """{"dependencies":{"demo":"^1.0.0"}}""")
        val declaration = VersionDeclaration(DeclarationId(file.virtualFile.path, "dependencies/demo"), ArtifactId("", "demo"), "^1.0.0", "1.0.0")
        val value = io.github.khopland.versionchecker.npm.NpmManifest.values(file).getValue(declaration.id)
        val edit = NpmVersionEdit(value, "^1.1.0", "Display text: with colons: never a source").withReview(listOf(declaration), "npm")
        val item = VersionReviewItem("Unsupported selector", VersionReviewSource(file.virtualFile.path, value.textOffset))
        val combined = BulkUpdatePlan.combine(listOf(BulkUpdatePlan(listOf(edit), listOf("legacy"), reviewItems = listOf(item))))
        val selected = combined.select(setOf(0))
        assertEquals(listOf(item), selected.reviewItems)
        val review = selected.changes.single().review!!
        assertEquals(file.virtualFile.path, review.source!!.filePath)
        assertEquals(value.textOffset, review.source.offset)
        assertEquals("^1.0.0", review.current)
        assertEquals("^1.1.0", review.target)
        assertEquals(listOf("demo"), review.consumers)
        assertEquals("npm", selected.changes.single().buildSystemId)
        assertTrue(review.description().contains("compatibility"))
    }

    fun testSourceOffsetsRemainSafeAfterShorterOrEmptySource() {
        assertEquals(3, VersionReviewSource("missing", 100).navigationOffset(3))
        assertEquals(0, VersionReviewSource("missing", -10).navigationOffset(3))
        assertEquals(0, VersionReviewSource("missing", 100).navigationOffset(0))
        assertNull(VersionReviewSource.capture(null))
        assertNull(VersionReviewSource("/missing-version-review-source/does-not-exist").findFile())
    }

    fun testExplanationSanitizesSensitiveProviderText() {
        val text = VersionReviewItem("Review https://user:password@example.com/repo", current = "https://user:password@example.com/repo").description()
        assertFalse(text.contains("password"))
    }
}
