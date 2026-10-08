package io.github.khopland.versionchecker

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** Delay after each completed check: slow repositories never produce a queue of timer requests. */
internal suspend fun periodicVersionChecks(intervalMillis: Long, check: suspend () -> Unit) {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        delay(intervalMillis.milliseconds)
        check()
    }
}

@Service(Service.Level.PROJECT)
internal class ScheduledVersionChecks(private val project: Project, private val scope: CoroutineScope) {
    private var job: Job? = null

    @Synchronized fun configure() {
        job?.cancel()
        job = null
        val settings = project.service<VersionCheckerSettings>().state
        if (!settings.enabled || !settings.scheduledChecks) return
        val interval = TimeUnit.MINUTES.toMillis(settings.checkIntervalMinutes.coerceIn(1, 1440).toLong())
        job = scope.launch(Dispatchers.IO) {
            periodicVersionChecks(interval) {
                try {
                    project.service<VersionCheckService>().refreshScheduled()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logger.getInstance(ScheduledVersionChecks::class.java).warn("Scheduled version check failed", failure)
                }
            }
        }
    }
}

internal class VersionCheckerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ScheduledVersionChecks>().configure()
    }
}
