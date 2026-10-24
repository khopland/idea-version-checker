package io.github.khopland.versionchecker

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import io.github.khopland.versionchecker.core.BuildSystemAdapter

/** Visitors are non-blocking in production; wait for their background input capture in direct tests. */
internal fun awaitInspectionInputs(adapterId: String, file: PsiFile) {
    val adapter = BuildSystemAdapter.find(adapterId) ?: return
    val service = file.project.service<VersionCheckService>()
    PlatformTestUtil.waitWithEventsDispatching("$adapterId inspection inputs captured", {
        service.inspectionSnapshot(adapter, file.virtualFile) != null
    }, 30_000)
}

/** Build the visitor inside the wait: inputs can expire between a readiness check and their use. */
internal fun LocalInspectionTool.buildCapturedVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val adapterId = when (this) {
        is io.github.khopland.versionchecker.maven.NewerMavenDependencyInspection -> "maven"
        is io.github.khopland.versionchecker.npm.NewerNpmDependencyInspection -> "npm"
        is io.github.khopland.versionchecker.gradle.NewerGradleDependencyInspection -> "gradle"
        else -> error("Unsupported inspection")
    }
    var visitor = PsiElementVisitor.EMPTY_VISITOR
    PlatformTestUtil.waitWithEventsDispatching("$adapterId inspection visitor captured", {
        visitor = buildVisitor(holder, isOnTheFly)
        visitor !== PsiElementVisitor.EMPTY_VISITOR
    }, 30_000)
    return visitor
}
