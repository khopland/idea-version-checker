package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle

class DiagnosticVisibilityTraceTest : BasePlatformTestCase() {
    fun testCountsDiagnosticsStartingAboveTheVisibleViewport() {
        myFixture.configureByText("visibility.txt", "first\nsecond\nthird\nfourth\n")
        val editor = myFixture.editor
        val markup = DocumentMarkupModel.forDocument(editor.document, project, true)
        val highlighter = markup.addRangeHighlighter(0, 18, HighlighterLayer.WARNING, null,
            HighlighterTargetArea.EXACT_RANGE).also {
            it.errorStripeTooltip = HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING).range(0, 18)
                .description("Update").create()!!.apply { setToolId("NewerMavenDependencyVersion") }
        }
        try {
            assertEquals(1, visibleVersionDiagnostics(project, editor,
                Rectangle(0, editor.lineHeight, 100, editor.lineHeight)))
            assertEquals(0, visibleVersionDiagnostics(project, editor,
                Rectangle(0, editor.lineHeight * 3, 100, editor.lineHeight)))
        } finally {
            markup.removeHighlighter(highlighter)
        }
    }

    fun testReadsInstalledDocumentInspectionMarkupAndExcludesOtherToolsAndOffscreenColumns() {
        myFixture.configureByText("visibility.txt", "first\n" + " ".repeat(200) + "last\n")
        val editor = myFixture.editor
        val markup = DocumentMarkupModel.forDocument(editor.document, project, true)
        fun highlight(start: Int, end: Int, tool: String) = markup.addRangeHighlighter(start, end,
            HighlighterLayer.WARNING, null, HighlighterTargetArea.EXACT_RANGE).also {
            it.errorStripeTooltip = HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING).range(start, end)
                .description("Update").create()!!.apply { setToolId(tool) }
        }
        val visible = highlight(0, 5, "NewerNpmDependencyVersion")
        val unrelated = highlight(0, 5, "UnrelatedInspection")
        val offscreen = highlight(206, 210, "NewerGradleDependencyVersion")
        try {
            assertTrue("Document inspections do not belong to the editor-local markup model", editor.markupModel.allHighlighters.isEmpty())
            val viewport = Rectangle(0, 0, 100, editor.lineHeight * 2)
            assertEquals("NewerNpmDependencyVersion", HighlightInfo.fromRangeHighlighter(visible)!!.inspectionToolId)
            assertEquals("geometry=${editor.offsetToXY(0)} to ${editor.offsetToXY(5)}, lineHeight=${editor.lineHeight}",
                1, visibleVersionDiagnostics(project, editor, viewport))
            markup.removeHighlighter(visible)
            assertEquals(0, visibleVersionDiagnostics(project, editor, viewport))
        } finally {
            listOf(visible, unrelated, offscreen).filter { it.isValid }.forEach(markup::removeHighlighter)
        }
    }
}
