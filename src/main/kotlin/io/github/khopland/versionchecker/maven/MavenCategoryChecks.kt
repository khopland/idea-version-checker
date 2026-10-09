package io.github.khopland.versionchecker.maven

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException

/** Strict callers fail immediately; inspection callers keep successful independent categories. */
internal suspend fun checkMavenCategories(
    kinds: List<MavenArtifactKind>,
    publish: (suspend (MavenArtifactKind, Result<Map<DependencyVersion, String>>) -> Unit)?,
    check: suspend (MavenArtifactKind) -> Map<DependencyVersion, String>
): Map<DependencyVersion, String> = buildMap {
    for (kind in kinds) {
        val result = try {
            Result.success(check(kind))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            if (publish == null) throw failure
            Result.failure(failure)
        }
        result.getOrNull()?.let(::putAll)
        // Publisher cancellation/failure belongs to the caller, never to the checked category.
        publish?.invoke(kind, result)
    }
}
