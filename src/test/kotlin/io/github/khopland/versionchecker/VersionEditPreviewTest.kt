package io.github.khopland.versionchecker

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleDeclarations
import io.github.khopland.versionchecker.gradle.GradleVersionEdit
import io.github.khopland.versionchecker.maven.MavenVersionEdit
import io.github.khopland.versionchecker.npm.NpmVersionEdit

class VersionEditPreviewTest : BasePlatformTestCase() {
    fun testMavenPropertyDiffIsReadOnlyAndMatchesActualOwnerEdit() {
        val file = myFixture.addFileToProject("diff/pom.xml", "<project><properties><shared.version>1.2.3</shared.version></properties></project>") as XmlFile
        val target = file.rootTag!!.findFirstSubTag("properties")!!.findFirstSubTag("shared.version")!!
        val edit = MavenVersionEdit(SmartPointerManager.createPointer(target), "1.2.3", "1.2.9", "shared owner")
        val original = file.text
        val preview = edit.preview()!!
        assertEquals(original, file.text)
        assertEquals(original, preview.before)
        assertEquals(original.replace("1.2.3", "1.2.9"), preview.after)
        assertTrue(BulkUpdatePlan(listOf(edit), emptyList()).apply(project))
        assertEquals(preview.after, file.text)
    }

    fun testNpmAliasDiffUsesTheNativeStringReplacementAndPreservesOtherFields() {
        val file = myFixture.addFileToProject("diff/package.json", """{"version":"1.2.3","dependencies":{"alias":"npm:@scope/library@^1.2.3"}}""") as JsonFile
        val root = file.topLevelValue as JsonObject
        val target = ((root.findProperty("dependencies")!!.value as JsonObject).propertyList.single().value as JsonStringLiteral)
        val edit = NpmVersionEdit(target, "npm:@scope/library@^1.2.9", "alias")
        val original = file.text
        val preview = edit.preview()!!
        assertEquals(original, file.text)
        assertEquals(original.replace("library@^1.2.3", "library@^1.2.9"), preview.after)
        assertTrue(BulkUpdatePlan(listOf(edit), emptyList()).apply(project))
        assertEquals(preview.after, file.text)
    }

    fun testSharedCatalogDiffChangesTheOwnerOnceAndPreservesBothConsumers() {
        val file = myFixture.addFileToProject("diff/gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
        """.trimIndent())
        val declarations = GradleDeclarations.parse(file.virtualFile.path, file.text)
        assertEquals(2, declarations.size)
        assertEquals(declarations.first().range, declarations.last().range)
        val edit = GradleVersionEdit(file, declarations.first().range!!, "1.2.9", "shared version")
        val original = file.text
        val preview = edit.preview()!!
        assertEquals(original, file.text)
        assertEquals(original.replace("1.2.3", "1.2.9"), preview.after)
        assertTrue(BulkUpdatePlan(listOf(edit), emptyList()).apply(project))
        assertEquals(preview.after, file.text)
    }

    fun testChangedOrUncommittedSourceCannotProduceAnAuthoritativeDiff() {
        val file = myFixture.addFileToProject("diff/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val declaration = GradleDeclarations.parse(file.virtualFile.path, file.text).single()
        val edit = GradleVersionEdit(file, declaration.range!!, "1.2.9", "alpha")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            document.insertString(document.textLength, " // changed")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertNull(edit.preview())
    }
}
