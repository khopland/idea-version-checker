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

        // Spock publishes one build per Groovy version; an update must keep the Groovy variant.
        val spock = MavenLookup("org.spockframework", "spock-core", listOf(MavenRepo.CENTRAL)).fetch()
        val targets = io.github.khopland.versionchecker.core.Targets.pick("2.0-groovy-3.0", spock.versions) { true }
        listOfNotNull(targets.patch, targets.minor, targets.major).forEach { assertTrue(it, it.endsWith("-groovy-3.0")) }
        assertNotNull(targets.major)

        val mysql = MavenLookup("mysql", "mysql-connector-java", listOf(MavenRepo.CENTRAL)).fetch()
        assertEquals("com.mysql:mysql-connector-j", mysql.moved?.to)
        val jackson = MavenLookup("com.fasterxml.jackson.core", "jackson-databind", listOf(MavenRepo.CENTRAL)).fetch()
        assertEquals("tools.jackson.core:jackson-databind", jackson.moved?.to)
        assertNull(MavenLookup("com.fasterxml.jackson.core", "jackson-annotations", listOf(MavenRepo.CENTRAL)).fetch().moved)

        val plugin = (GradleProvider.plugin("org.jetbrains.kotlin.jvm") as MavenLookup).fetch()
        assertTrue("2.0.0" in plugin.versions)

        val react = NpmLookup("react", Npmrc(emptyMap()).registry("react")).fetch()
        assertTrue("18.2.0" in react.versions)
        val scoped = NpmLookup("@types/node", Npmrc(emptyMap()).registry("@types/node")).fetch()
        assertTrue("20.0.0" in scoped.versions)

        val gradle = GradleReleases().fetch()
        assertTrue("8.14" in gradle.versions)
        assertNotNull(gradle.publishedAt("8.14"))
    }
}

private typealias ThrowableRunnable<T> = com.intellij.util.ThrowableRunnable<T>
