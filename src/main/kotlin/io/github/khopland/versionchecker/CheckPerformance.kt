package io.github.khopland.versionchecker

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Opt-in traces contain fixed stages, anonymous IDs and monotonic times, never build settings. */
internal object CheckPerformance {
    enum class Stage {
        FILE_ACTIVATED, INTERACTION_STARTED, IDEA_HIGHLIGHTING, DIAGNOSTIC_VISIBLE, PREVIEW_INVOKED, PREVIEW_PREPARATION,
        PREVIEW_PLAN, PREVIEW_READY, FIX_INVOKED, EDITOR_TEXT_CHANGED,
        GRADLE_SNAPSHOT, GRADLE_SETUP, GRADLE_CONFIGURATION, GRADLE_QUERY, GRADLE_NATIVE,
        MAVEN_SNAPSHOT, NPM_SNAPSHOT, CHECK_QUEUE, CHECK, FIRST_INSPECTION_RESULT, RESULT_ACCEPTED,
        MAVEN_PROJECT_INPUTS, MAVEN_DECLARATIONS, MAVEN_SESSION, MAVEN_MODEL, MAVEN_SETTINGS, MAVEN_METADATA_EXPIRATION,
        MAVEN_DEPENDENCY_GOAL, MAVEN_PLUGIN_GOAL, MAVEN_PARENT_GOAL,
        MAVEN_METADATA_BATCH, MAVEN_METADATA_REUSED, MAVEN_QUERY_COORDINATES, MAVEN_QUERY_CONTEXTS,
        NPM_RUNTIME_RESOLUTION, NPM_METADATA_WAIT, NPM_COMMAND_SETUP, NPM_VIEW, NPM_VERSION_INDEX, HIGHLIGHT_QUEUE, HIGHLIGHT_RESTART
    }

    data class Interaction(val id: Long, val started: Long)
    private val sequence = AtomicLong()
    private val active = ThreadLocal<Interaction?>()
    fun enabled() = Logger.getInstance(CheckPerformance::class.java).isDebugEnabled
    fun current(): Interaction? = active.get()
    fun context(interaction: Interaction? = current()): CoroutineContext =
        if (interaction == null) EmptyCoroutineContext else active.asContextElement(interaction)
    fun start(stage: Stage): Interaction? {
        if (!enabled()) return null
        return Interaction(sequence.incrementAndGet(), System.nanoTime()).also { record(stage, it.started, interaction = it) }
    }
    suspend fun <T> traced(interaction: Interaction?, block: suspend CoroutineScope.() -> T): T = withContext(context(interaction)) { block() }
    fun <T> locally(interaction: Interaction?, block: () -> T): T {
        val previous = active.get()
        active.set(interaction)
        try { return block() } finally { active.set(previous) }
    }
    inline fun <T> measure(stage: Stage, count: Int = 1, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { record(stage, started, count) }
    }
    @PublishedApi internal fun record(stage: Stage, started: Long, count: Int = 1, interaction: Interaction? = current()) {
        val log = Logger.getInstance(CheckPerformance::class.java)
        if (log.isDebugEnabled) {
            log.debug(format(stage, started, System.nanoTime(), count, interaction))
        }
    }
    internal fun interval(stage: Stage, started: Long, finished: Long, count: Int = 1, interaction: Interaction? = current()) {
        val log = Logger.getInstance(CheckPerformance::class.java)
        if (log.isDebugEnabled) log.debug(format(stage, started, finished, count, interaction))
    }
    internal fun format(stage: Stage, started: Long, finished: Long, count: Int, interaction: Interaction?): String =
        "version-check stage=$stage elapsedNs=${finished - started} count=$count" +
            " interaction=${interaction?.id ?: 0} startNs=$started endNs=$finished originNs=${interaction?.started ?: started}"
}
