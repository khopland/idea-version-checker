package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ScheduledVersionChecksTest {
    @Test fun `slow checks do not overlap and cancellation stops the timer`() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            periodicVersionChecks(10) {
                calls++
                started.complete(Unit)
                gate.await()
            }
        }
        withTimeout(5_000.milliseconds) { started.await() }
        delay(40.milliseconds)
        assertEquals(1, calls)
        job.cancelAndJoin()
        gate.complete(Unit)
        delay(40.milliseconds)
        assertEquals(1, calls)
    }

    @Test fun `first check waits for the interval and repeats after completion`() = runBlocking {
        val twice = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            periodicVersionChecks(10) { if (++calls == 2) twice.complete(Unit) }
        }
        yield()
        assertEquals(0, calls)
        withTimeout(5_000.milliseconds) { twice.await() }
        job.cancelAndJoin()
        assertEquals(2, calls)
    }
}
