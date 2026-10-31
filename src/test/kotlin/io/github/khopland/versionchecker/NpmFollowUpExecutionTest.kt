package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.followup.NpmFollowUpExecution
import io.github.khopland.versionchecker.followup.NpmFollowUpOutcome
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class NpmFollowUpExecutionTest {
    @Test fun `cancelled parent still inspects possible writes and publishes exactly one outcome`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val outcomes = CopyOnWriteArrayList<NpmFollowUpOutcome>()
        var inspections = 0
        val job = launch {
            NpmFollowUpExecution.run(
                execute = { started.complete(Unit); awaitCancellation() },
                inspect = { currentCoroutineContext().ensureActive(); inspections++; listOf(Path.of("package-lock.json")) },
                isAlive = { true }, publish = outcomes::add,
            )
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, inspections)
        assertEquals(1, outcomes.size)
        assertEquals(NpmFollowUpOutcome.Phase.CANCELLED, outcomes.single().phase)
        assertTrue(outcomes.single().changesKnown)
        assertEquals(listOf(Path.of("package-lock.json")), outcomes.single().changed)
    }

    @Test fun `command timeout differs from cancellation and inspection timeout preserves original outcome`() = runBlocking {
        val outcomes = mutableListOf<NpmFollowUpOutcome>()
        NpmFollowUpExecution.run(execute = { awaitCancellation() }, inspect = { awaitCancellation() },
            isAlive = { true }, publish = outcomes::add, commandTimeoutMillis = 20, inspectionTimeoutMillis = 20)
        assertEquals(1, outcomes.size)
        assertEquals(NpmFollowUpOutcome.Phase.TIMED_OUT, outcomes.single().phase)
        assertFalse(outcomes.single().changesKnown)
        assertTrue(outcomes.single().message.contains("could not be inspected"))
    }

    @Test fun `inspection failure is reported as a warning`() = runBlocking {
        val outcomes = mutableListOf<NpmFollowUpOutcome>()
        NpmFollowUpExecution.run(execute = { 0 }, inspect = { error("private diagnostic") },
            isAlive = { true }, publish = outcomes::add)
        assertEquals(NpmFollowUpOutcome.Phase.COMPLETED, outcomes.single().phase)
        assertFalse(outcomes.single().changesKnown)
        assertTrue(outcomes.single().warning)
        assertFalse(outcomes.single().message.contains("private diagnostic"))
        assertTrue(outcomes.single().message.contains("could not be inspected"))
    }

    @Test fun `native failure reports exit code and known inspected writes`() = runBlocking {
        val outcomes = mutableListOf<NpmFollowUpOutcome>()
        NpmFollowUpExecution.run(execute = { 17 }, inspect = { emptyList() }, isAlive = { true }, publish = outcomes::add)
        assertEquals(NpmFollowUpOutcome.Phase.FAILED, outcomes.single().phase)
        assertEquals(17, outcomes.single().exitCode)
        assertTrue(outcomes.single().changesKnown)
    }

    @Test fun `disposed owner never starts a native command`() = runBlocking {
        var executed = false
        var inspected = false
        NpmFollowUpExecution.run(execute = { executed = true; 0 }, inspect = { inspected = true; emptyList() },
            isAlive = { false }, publish = { fail("Disposed owner must not publish") })
        assertFalse(executed)
        assertFalse(inspected)
    }

    @Test fun `disposal after execution skips inspection and late publication`() = runBlocking {
        var alive = true
        var inspected = false
        val outcomes = mutableListOf<NpmFollowUpOutcome>()
        NpmFollowUpExecution.run(execute = { alive = false; 0 }, inspect = { inspected = true; emptyList() },
            isAlive = { alive }, publish = outcomes::add)
        assertFalse(inspected)
        assertTrue(outcomes.isEmpty())
    }

    @Test fun `disposal during inspection rejects terminal publication`() = runBlocking {
        var alive = true
        val outcomes = mutableListOf<NpmFollowUpOutcome>()
        NpmFollowUpExecution.run(execute = { 0 }, inspect = { alive = false; emptyList() },
            isAlive = { alive }, publish = outcomes::add)
        assertTrue(outcomes.isEmpty())
    }
}
