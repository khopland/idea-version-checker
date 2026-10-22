package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.VersionVerificationOutcome

/** Shared explicit entry point; retained notifications and dialog actions capture only bounded-store tokens. */
internal object MavenSessionFollowUp {
    data class Availability(val reload: Boolean, val verify: Boolean)

    fun availability(providers: Set<String>, providerAvailable: Boolean, changes: AppliedVersionChanges?): Availability {
        val reload = "maven" in providers && providerAvailable
        return Availability(reload, reload && changes?.mavenVerificationTargets?.isNotEmpty() == true)
    }

    fun verify(project: Project, diffToken: Long, sessionToken: Long?) {
        if (project.isDisposed) return
        val sessions = project.service<UpdateSessionService>()
        val step = sessions.beginStep(sessionToken, "maven", UpdateStepKind.VERSION_OBSERVATION,
            "Preparing a fresh Maven effective-version observation; this does not run tests.")
        val captured = project.service<AppliedVersionChangesService>().captured(diffToken)
        val adapter = BuildSystemAdapter.find("maven")
        val unavailable = when {
            captured == null -> "Unknown. Historical evidence expired; apply a fresh reviewed update to capture verification evidence."
            adapter == null -> "Unknown. The Maven provider is unavailable. Enable Maven support before verifying."
            captured.mavenVerificationTargets.isEmpty() -> "Unknown. This update has no captured Maven verification targets."
            else -> null
        }
        if (unavailable != null) {
            sessions.recordStep(step, UpdateStepStatus.UNAVAILABLE, unavailable)
            sessions.recordVerification(sessionToken, "maven", unavailable)
            Messages.showInfoMessage(project, unavailable, "Maven Effective Versions")
        } else {
            sessions.recordStep(step, UpdateStepStatus.RUNNING, "Reading Maven import evidence; expected versions have not yet been compared.")
            adapter!!.verifyUpdatedVersions(project, captured!!) { outcome ->
                if (!project.isDisposed) {
                    val status = when (outcome.status) {
                        VersionVerificationOutcome.Status.OBSERVED -> UpdateStepStatus.OBSERVED
                        VersionVerificationOutcome.Status.CANCELLED -> UpdateStepStatus.CANCELLED
                        VersionVerificationOutcome.Status.FAILED -> UpdateStepStatus.FAILED
                    }
                    sessions.recordStep(step, status, outcome.message)
                    sessions.recordVerification(sessionToken, "maven", outcome.message)
                }
            }
        }
    }
}
