package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.UpdateScope
import junit.framework.TestCase

class VersionPreviewStateTest : TestCase() {
    private val ready = VersionPreviewState(UpdateMode.PATCH, UpdateScope.WHOLE_PROJECT)
    private fun VersionPreviewState.authorizes(generation: Long = ready.generation) =
        authorizes(generation, UpdateMode.PATCH, UpdateScope.WHOLE_PROJECT)

    fun testAuthorizationRequiresThePreparedGenerationAndSelectors() {
        assertTrue(ready.authorizes())
        assertFalse(ready.authorizes(1))
        assertFalse(ready.authorizes(0, UpdateMode.MINOR, UpdateScope.WHOLE_PROJECT))
        assertFalse(ready.authorizes(0, UpdateMode.PATCH, UpdateScope.CURRENT_FILE))
    }

    fun testRefreshAndRecoveryNeverReviveAHeldPlan() {
        val checking = ready.recheck()
        assertTrue(checking.checking)
        assertFalse(checking.canApply)
        assertFalse(checking.authorizes())
        val failed = checking.failed("Offline")
        assertFalse(failed.canInspect)
        assertFalse(failed.authorizes(checking.generation))
        val recovered = failed.recheck().prepared(ready.mode, ready.scope)
        assertTrue(recovered.canApply)
        assertFalse(recovered.authorizes())
        assertTrue(recovered.authorizes(recovered.generation))
    }

    fun testApplyingPreservesAuthorizationAndFinishesOnlyItsOwnGeneration() {
        val applying = ready.applying()
        assertFalse(applying.canApply)
        assertTrue(applying.authorizes())
        assertEquals(applying, applying.applying())
        assertEquals(ready, applying.applyFinished(ready.generation))
        val checking = applying.recheck()
        assertEquals(checking, checking.applyFinished(ready.generation))
        val failed = applying.failed("Write preparation failed")
        assertEquals(failed, failed.applyFinished(ready.generation))
        val replaced = checking.prepared(ready.mode, ready.scope).applying()
        assertEquals(replaced, replaced.applyFinished(ready.generation))
    }

    fun testCancellationAndDisposalAreTerminal() {
        val closed = ready.closed(accepted = false)
        assertFalse(closed.authorizes())
        assertFalse(closed.canApply)
        assertEquals(closed, closed.recheck())
        assertEquals(closed, closed.prepared(ready.mode, ready.scope))
        assertEquals(closed, closed.failed("Late failure"))
        assertEquals(closed, closed.closed(accepted = true))
    }

    fun testAcceptedModalReviewRetainsOnlyItsExistingAuthorization() {
        for (state in listOf(ready, ready.applying())) {
            val accepted = state.closed(accepted = true)
            assertTrue(accepted.authorizes())
            assertFalse(accepted.canApply)
            assertEquals(accepted, accepted.recheck())
            assertEquals(accepted, accepted.applyFinished(ready.generation))
        }
        assertFalse(ready.recheck().closed(accepted = true).authorizes())
        assertFalse(ready.failed("Offline").closed(accepted = true).authorizes())
    }
}
