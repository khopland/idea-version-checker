package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.followup.BuildReloadOutcome
import io.github.khopland.versionchecker.followup.NpmFollowUpOutcome
import io.github.khopland.versionchecker.followup.NpmFollowUpPresentation
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class FollowUpOutcomeTest {
    @Test fun `completed reload points to the build tool window`() {
        val outcome = BuildReloadOutcome(BuildReloadOutcome.Phase.COMPLETED, "Maven", requestAttempted = true)
        assertTrue(outcome.message.contains("reload completed"))
        assertTrue(outcome.message.contains("build tool window"))
        assertFalse(outcome.message.contains("completion was not observed"))
        assertFalse(outcome.warning)
    }

    @Test fun `npm outcomes identify selected workspace even without changed lockfiles`() {
        for (phase in NpmFollowUpOutcome.Phase.entries) {
            val message = NpmFollowUpPresentation.withWorkspace(NpmFollowUpOutcome(phase).message,
                Path.of("/project/other-workspace"), "/project")
            assertTrue(message.startsWith("Selected npm workspace root: other-workspace (project-relative)."))
            assertFalse(message.contains("/project"))
        }
        assertTrue(NpmFollowUpPresentation.withWorkspace("Done", Path.of("/project"), "/project")
            .contains("root: . (project-relative)"))
        assertTrue(NpmFollowUpPresentation.withWorkspace("Done", Path.of("/elsewhere/private"), "/project")
            .contains("<outside-project>"))
        assertFalse(NpmFollowUpPresentation.withWorkspace("Done", Path.of("/elsewhere/private"), "/project")
            .contains("private"))
        assertTrue(NpmFollowUpPresentation.withWorkspace("Cancelled", Path.of("/project/work\nspace"), "/project")
            .contains("work?space"))
    }

    @Test fun `npm terminal outcomes distinguish execution`() {
        val lock = Path.of("package-lock.json")
        for (phase in NpmFollowUpOutcome.Phase.entries) {
            val outcome = NpmFollowUpOutcome(phase, listOf(lock), exitCode = if (phase == NpmFollowUpOutcome.Phase.COMPLETED) 0 else 1)
            if (phase in setOf(NpmFollowUpOutcome.Phase.NOT_STARTED, NpmFollowUpOutcome.Phase.CANCELLED_BEFORE_START)) {
                assertFalse(outcome.message.contains("Changed lockfiles"))
            } else {
                assertTrue(outcome.message.contains("package-lock.json"))
                assertTrue(outcome.message.contains("declaration undo"))
                assertTrue(outcome.message.contains("installed dependencies were not synchronized"))
            }
            if (phase in setOf(NpmFollowUpOutcome.Phase.CANCELLED, NpmFollowUpOutcome.Phase.TIMED_OUT)) {
                assertTrue(outcome.message.contains("Process termination was not confirmed"))
                assertTrue(outcome.message.contains("writes may still occur"))
            }
        }
        assertTrue(NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.TIMED_OUT).message.contains("timed out"))
        assertTrue(NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.CANCELLED).message.contains("cancellation was requested"))
        assertTrue(NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.FAILED, exitCode = 17).message.contains("exit 17"))
    }

    @Test fun `completed command with uninspectable outputs still needs diff review`() {
        val outcome = NpmFollowUpOutcome(NpmFollowUpOutcome.Phase.COMPLETED, changesKnown = false)
        assertTrue(outcome.warning)
        assertTrue(outcome.message.contains("could not be inspected"))
        assertTrue(outcome.message.contains("partial changes"))
        assertFalse(outcome.message.contains("Changed lockfiles at inspection: none"))
    }

    @Test fun `reload cancellation distinguishes saving from request dispatch`() {
        val before = BuildReloadOutcome(BuildReloadOutcome.Phase.CANCELLED, "Gradle")
        val after = before.copy(requestAttempted = true)
        assertTrue(before.message.contains("before requesting reload"))
        assertTrue(before.message.contains("may already have been saved"))
        assertTrue(after.message.contains("during or after the reload request"))
        assertFalse(after.message.contains("before requesting reload"))
    }
}
