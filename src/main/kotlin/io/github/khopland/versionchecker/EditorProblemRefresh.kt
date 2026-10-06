package io.github.khopland.versionchecker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project

/** Refresh standard inspection highlighting after settings or repository results change. */
internal fun refreshEditorProblems(project: Project, reason: Any) {
    DaemonCodeAnalyzer.getInstance(project).restart(reason)
}
