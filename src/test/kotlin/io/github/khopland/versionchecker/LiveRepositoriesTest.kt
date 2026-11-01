package io.github.khopland.versionchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleProvider
import io.github.khopland.versionchecker.npm.NpmLookup
import io.github.khopland.versionchecker.npm.Npmrc
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import io.github.khopland.versionchecker.wrapper.GradleReleases
import kotlinx.coroutines.runBlocking

/** Talks to the real public registries. Run with `./gradlew test -Plive=true --tests '*LiveRepositoriesTest'`. */
class LiveRepositoriesTest : BasePlatformTestCase() {
    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (System.getProperty("versionchecker.live") == "true") super.runTestRunnable(testRunnable)
    }

    fun testPublicRepositories() = runBlocking {
        val junit = MavenLookup("junit", "junit", listOf(MavenRepo.CENTRAL)).fetch()
        assertTrue("4.13.2" in junit.versions)
        assertNotNull(junit.allPublishedBefore)
        assertNotNull(junit.publishedAt("4.13.2"))

        val plugin = (GradleProvider.plugin("org.jetbrains.kotlin.jvm") as MavenLookup).fetch()
        assertTrue("2.0.0" in plugin.versions)

        val react = NpmLookup("react", Npmrc(emptyMap()).registry("react"), withTimes = false).fetch()
        assertTrue("18.2.0" in react.versions)
        val scoped = NpmLookup("@types/node", Npmrc(emptyMap()).registry("@types/node"), withTimes = true).fetch()
        assertNotNull(scoped.publishedAt("20.0.0"))

        val gradle = GradleReleases().fetch()
        assertTrue("8.14" in gradle.versions)
        assertNotNull(gradle.publishedAt("8.14"))
    }
}

private typealias ThrowableRunnable<T> = com.intellij.util.ThrowableRunnable<T>
