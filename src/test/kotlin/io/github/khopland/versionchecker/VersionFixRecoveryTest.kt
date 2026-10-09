package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleVersionEdit
import io.github.khopland.versionchecker.gradle.UpdateGradleVersionFix
import io.github.khopland.versionchecker.maven.OverrideDependencyVersionFix
import io.github.khopland.versionchecker.maven.UpdateDependencyVersionFix
import io.github.khopland.versionchecker.npm.NpmManifest
import io.github.khopland.versionchecker.npm.NpmVersionEdit
import io.github.khopland.versionchecker.npm.UpdateNpmVersionFix
import io.github.khopland.versionchecker.npm.UpdateNpmWorkspaceVersionFix
import java.util.concurrent.CopyOnWriteArrayList

class VersionFixRecoveryTest : BasePlatformTestCase() {
    private fun recovery(): List<Notification> {
        val captured = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.title != "Version update needs a fresh check") return
                captured += notification
                Disposer.register(testRootDisposable) { notification.expire() }
            }
        })
        return captured
    }
    private fun apply(fix: LocalQuickFix, element: PsiElement) {
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(element, "update", fix,
            ProblemHighlightType.WARNING, false)
        if (fix.startInWriteAction()) WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        else fix.applyFix(project, descriptor)
    }
    private fun assertRecovery(notifications: List<Notification>) {
        PlatformTestUtil.waitWithEventsDispatching("Recovery offered", { notifications.size == 1 }, 10)
        assertTrue(notifications.single().content.contains("No versions were changed"))
        assertEquals(listOf("Refresh This File"), notifications.single().actions.map { it.templatePresentation.text })
    }

    fun testChangedMavenLiteralAndOverrideOfferOneRecoveryAndPreserveEditedText() {
        val file = myFixture.addFileToProject("fix-recovery/pom.xml", "<project><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></dependency></dependencies></project>") as XmlFile
        val dependency = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single()
        val version = dependency.findFirstSubTag("version")!!
        val literal = UpdateDependencyVersionFix(version, "2.0")
        val override = OverrideDependencyVersionFix(dependency, "2.0")
        WriteCommandAction.runWriteCommandAction(project) { version.value.setText("3.0") }
        val before = file.text
        val notifications = recovery()
        apply(literal, version); apply(override, version)
        assertRecovery(notifications)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, notifications.size)
        assertEquals(before, file.text)
    }

    fun testNpmLocalCurrentnessGuardPreservesManifestAndOffersRecovery() {
        val file = myFixture.addFileToProject("fix-recovery/package.json", """{"dependencies":{"library":"^1.0.0"}}""")
        val value = NpmManifest.values(file).values.single()
        val fix = UpdateNpmVersionFix(value, "^1.0.1", isCurrent = { false })
        val before = file.text
        val notifications = recovery()
        apply(fix, value)
        assertRecovery(notifications)
        assertEquals(before, file.text)
    }

    fun testNpmWorkspaceRevalidatesInsideTheWriteCommandAndExplainsRejection() {
        val file = myFixture.addFileToProject("workspace-fix-recovery/package.json", """{"dependencies":{"library":"^1.0.0"}}""")
        val other = myFixture.addFileToProject("workspace-fix-recovery/other/package.json", """{"dependencies":{"library":"~1.0.0"}}""")
        val value = NpmManifest.values(file).values.single()
        val second = NpmManifest.values(other).values.single()
        var validations = 0
        val fix = UpdateNpmWorkspaceVersionFix("library", "1.0.1",
            listOf(NpmVersionEdit(value, "^1.0.1", "first"), NpmVersionEdit(second, "~1.0.1", "second"))) { ++validations == 1 }
        val before = listOf(file.text, other.text)
        val notifications = recovery()
        apply(fix, value)
        assertRecovery(notifications)
        assertEquals(2, validations)
        assertEquals(before, listOf(file.text, other.text))
    }

    fun testGradleRevalidatesInsideTheWriteCommandAndExplainsRejection() {
        val file = myFixture.addFileToProject("gradle-fix-recovery/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        val offset = file.text.indexOf("1.0")
        var validations = 0
        val fix = UpdateGradleVersionFix(GradleVersionEdit(file, TextRange(offset, offset + 3), "1.1", "test")) { ++validations == 1 }
        val before = file.text
        val notifications = recovery()
        apply(fix, file)
        assertRecovery(notifications)
        assertEquals(2, validations)
        assertEquals(before, file.text)
    }
}
