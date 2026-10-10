package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CheckPerformanceTest {
    @Test fun `interaction follows coroutine dispatch and is restored after cancellation`() = runBlocking {
        val trace = CheckPerformance.Interaction(17, 100)
        assertNull(CheckPerformance.current())
        CheckPerformance.traced(trace) {
            withContext(Dispatchers.Default) { assertSame(trace, CheckPerformance.current()) }
            val child = launch(Dispatchers.IO) {
                assertSame(trace, CheckPerformance.current())
                awaitCancellation()
            }
            child.cancelAndJoin()
            assertSame(trace, CheckPerformance.current())
        }
        assertNull(CheckPerformance.current())
        CheckPerformance.locally(trace) {
            assertSame(trace, CheckPerformance.current())
            CheckPerformance.locally(null) { assertNull(CheckPerformance.current()) }
            assertSame(trace, CheckPerformance.current())
        }
        assertNull(CheckPerformance.current())
    }

    @Test fun `trace schema has only anonymous identity fixed stages times and counts`() {
        assertEquals("version-check stage=GRADLE_QUERY elapsedNs=300 count=4 interaction=7 startNs=200 endNs=500 originNs=100",
            CheckPerformance.format(CheckPerformance.Stage.GRADLE_QUERY, 200, 500, 4, CheckPerformance.Interaction(7, 100)))
    }
}
