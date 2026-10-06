package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import com.intellij.platform.util.progress.RawProgressReporter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Run on an IO worker. Keep the public progress helper open for the full Maven request. */
internal suspend fun <T> withMavenProgress(action: suspend (RawProgressReporter) -> T): T {
    // Use the worker's event loop while retaining cancellation and the project's coroutine context.
    val context = currentCoroutineContext().minusKey(ContinuationInterceptor)
    return suspendCancellableCoroutine { continuation ->
        try {
            val result =
                ProgressReporting.report({ reporter -> runBlocking(context) { action(reporter) } }, continuation)
            if (result !== COROUTINE_SUSPENDED) {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(result as T)
            }
        } catch (failure: Throwable) {
            continuation.resumeWithException(failure)
        }
    }
}
