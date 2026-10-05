package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenProgressTest {
    @Test fun `cancelling a check cancels the suspended Maven request`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val check = launch(Dispatchers.IO) {
            withMavenProgress {
                entered.complete(Unit)
                try { awaitCancellation() } finally { finished.complete(Unit) }
            }
        }
        withTimeout(5_000) {
            entered.await()
            check.cancelAndJoin()
            finished.await()
        }
        assertTrue(check.isCancelled)
    }
}
