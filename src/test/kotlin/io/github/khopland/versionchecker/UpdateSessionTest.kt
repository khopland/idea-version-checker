package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.followup.NativeFileChange
import io.github.khopland.versionchecker.followup.NativeFileChanges
import org.junit.Assert.*
import org.junit.Test

class UpdateSessionTest {
    @Test fun `session Maven followups require the matching provider and verification evidence`() {
        val changes = AppliedVersionChanges(emptyList(), mavenVerificationTargets = listOf(
            MavenVerificationTarget("/project/pom.xml", "g", "a", "DEPENDENCY", "2", 0)))
        assertEquals(MavenSessionFollowUp.Availability(true, true),
            MavenSessionFollowUp.availability(setOf("maven"), true, changes))
        assertEquals(MavenSessionFollowUp.Availability(true, false),
            MavenSessionFollowUp.availability(setOf("maven"), true, null))
        assertEquals(MavenSessionFollowUp.Availability(false, false),
            MavenSessionFollowUp.availability(setOf("gradle"), true, changes))
        assertEquals(MavenSessionFollowUp.Availability(false, false),
            MavenSessionFollowUp.availability(setOf("maven"), false, changes))
        assertFalse(MavenSessionFollowUp.availability(setOf("maven"), true, AppliedVersionChanges(emptyList())).verify)
    }

    @Test fun `late outcomes belong to the originating session and expired tokens are ignored`() {
        val store = UpdateSessionStore(2)
        val first = store.start(10, "First declaration", setOf("maven"))
        val second = store.start(11, "Second declaration", setOf("maven", "npm"))
        val step = store.begin(first, "maven", UpdateStepKind.NATIVE_RELOAD, "Preparing")!!
        store.update(step, UpdateStepStatus.CANCELLED, "Reload cancelled after request")
        assertEquals("Reload cancelled after request", store.get(first)!!.steps.single().message)
        assertTrue(store.get(second)!!.steps.isEmpty())
        store.start(null, "Third declaration", setOf("gradle"))
        store.update(step, UpdateStepStatus.COMPLETED, "Late completion")
        assertNull(store.get(first))
        assertTrue(store.get(second)!!.steps.isEmpty())
    }

    @Test fun `completed followup leaves verification unknown and declaration review available`() {
        val store = UpdateSessionStore()
        val token = store.start(42, "Declarations applied", setOf("npm"))
        val step = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Preparing")!!
        store.update(step, UpdateStepStatus.COMPLETED, "Command completed; lockfile changed")
        val result = store.get(token)!!
        assertEquals(42L, result.diffToken)
        assertTrue(result.summary().contains("Historical verification observation (not revalidated): Unknown"))
        assertTrue(result.summary().contains("Declarations applied"))
        assertTrue(result.summary().contains("Cancellation does not undo"))
        store.observe(token, "npm", "Expected version confirmed")
        assertTrue(store.get(token)!!.summary().contains("Historical verification observation (not revalidated): Recorded at"))
        assertTrue(store.get(token)!!.summary().contains("Expected version confirmed"))
    }

    @Test fun `stale declaration evidence downgrades recorded confirmation to unknown`() {
        val store = UpdateSessionStore()
        val token = store.start(12, "Declarations applied", setOf("maven"))
        store.observe(token, "maven", "Confirmed expected version")
        val step = store.begin(token, "maven", UpdateStepKind.VERSION_OBSERVATION, "Preparing")!!
        store.update(step, UpdateStepStatus.OBSERVED, "Confirmed expected version")
        val stale = store.get(token)!!.withoutCurrentEvidence()
        assertTrue(stale.verification.getValue("maven").startsWith("Unknown."))
        assertEquals(UpdateStepStatus.UNKNOWN, stale.steps.single().status)
        assertTrue(stale.steps.single().message.startsWith("Unknown."))
        assertTrue(stale.summary().contains("Previous observation: Recorded at"))
        assertTrue(stale.summary().contains("Verify again after any edit, undo"))
    }

    @Test fun `retention limits text and providers and clear removes all sessions`() {
        val store = UpdateSessionStore()
        val token = store.start(null, "a".repeat(5000), (1..100).map { "provider$it" }.toSet())
        assertEquals(16, store.get(token)!!.providers.size)
        assertEquals(4096, store.get(token)!!.declarationNotice.length)
        val step = store.begin(token, "provider1", UpdateStepKind.NATIVE_RELOAD, "Preparing")!!
        store.update(step, UpdateStepStatus.UNKNOWN, "b".repeat(10000))
        assertNull(store.begin(token, "unrelated", UpdateStepKind.NATIVE_RELOAD, "Ignored"))
        assertEquals(8192, store.get(token)!!.steps.single().message.length)
        store.clear()
        assertNull(store.latest())
        assertNull(store.get(token))
    }
    @Test fun `repeated attempts retain separate typed outcomes and ignore late expired steps`() {
        val store = UpdateSessionStore()
        val token = store.start(12, "Declarations", setOf("npm"))
        val first = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "First preview")!!
        val second = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Second preview")!!
        store.update(second, UpdateStepStatus.COMPLETED, "Second finished")
        store.update(first, UpdateStepStatus.TIMED_OUT, "First termination unknown")
        val steps = store.get(token)!!.steps
        assertEquals(listOf(UpdateStepStatus.TIMED_OUT, UpdateStepStatus.COMPLETED), steps.map { it.status })
        assertTrue(store.get(token)!!.verification.isEmpty())
        repeat(16) { store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Later") }
        store.update(first, UpdateStepStatus.COMPLETED, "Expired completion")
        assertEquals(16, store.get(token)!!.steps.size)
        assertTrue(store.get(token)!!.steps.none { it.message == "Expired completion" })
    }

    @Test fun `native snapshot budget evicts text while preserving outcomes declarations and omissions`() {
        val store = UpdateSessionStore(nativeCharacters = 8)
        fun complete(token: Long): UpdateStepToken {
            val step = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Preparing")!!
            store.update(step, UpdateStepStatus.COMPLETED, "Command finished", NativeFileChanges(
                listOf(NativeFileChange("package-lock.json", "old", "new")), listOf("npm-shrinkwrap.json: omitted")))
            return step
        }
        val first = store.start(40, "First declarations", setOf("npm"))
        complete(first)
        val second = store.start(41, "Second declarations", setOf("npm"))
        complete(second)
        val previous = store.get(first)!!.steps.single()
        assertEquals(UpdateStepStatus.COMPLETED, previous.status)
        assertTrue(previous.nativeChanges!!.captureUnavailable)
        assertTrue(previous.nativeChanges.files.isEmpty())
        assertEquals(listOf("npm-shrinkwrap.json: omitted"), previous.nativeChanges.omitted)
        assertEquals(40L, store.get(first)!!.diffToken)
        assertEquals(1, store.get(second)!!.steps.single().nativeChanges!!.files.size)
        assertTrue(store.get(first)!!.summary().contains("evicted"))
    }

    @Test fun `retiring an unstarted coroutine closes pending steps but preserves terminal evidence`() {
        val store = UpdateSessionStore()
        val token = store.start(null, "Declaration changes", setOf("npm"))
        val pending = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Preparing")!!
        store.retirePending(pending, "Cancelled before command start")
        assertEquals(UpdateStepStatus.CANCELLED, store.get(token)!!.steps.single().status)
        val complete = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Preparing")!!
        store.update(complete, UpdateStepStatus.COMPLETED, "Command completed")
        store.retirePending(complete, "Late coroutine cleanup")
        assertEquals("Command completed", store.get(token)!!.steps.last().message)
    }

}
