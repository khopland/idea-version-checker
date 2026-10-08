package io.github.khopland.versionchecker

import com.intellij.openapi.diagnostic.Logger

/** Opt-in debug traces contain only fixed stage names, durations and counts, never build settings. */
internal object CheckPerformance {
    enum class Stage {
        MAVEN_SNAPSHOT, NPM_SNAPSHOT, CHECK_QUEUE, CHECK,
        MAVEN_PROJECT_INPUTS, MAVEN_DECLARATIONS, MAVEN_SESSION, MAVEN_MODEL, MAVEN_SETTINGS, MAVEN_METADATA_EXPIRATION,
        MAVEN_DEPENDENCY_GOAL, MAVEN_PLUGIN_GOAL, MAVEN_PARENT_GOAL,
        NPM_COMMAND_SETUP, NPM_VIEW, HIGHLIGHT_QUEUE, HIGHLIGHT_RESTART
    }

    inline fun <T> measure(stage: Stage, count: Int = 1, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { record(stage, started, count) }
    }

    @PublishedApi internal fun record(stage: Stage, started: Long, count: Int = 1) {
        val log = Logger.getInstance(CheckPerformance::class.java)
        if (log.isDebugEnabled) log.debug("version-check stage=$stage elapsedNs=${System.nanoTime() - started} count=$count")
    }
}
