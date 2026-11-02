package io.github.khopland.versionchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleProvider
import io.github.khopland.versionchecker.npm.NpmLookup
import io.github.khopland.versionchecker.npm.Npmrc
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import io.github.khopland.versionchecker.wrapper.GradleReleases
import kotlinx.coroutines.async
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
        // Every listed move must still find a stable release at its new coordinates.
        val moves = MavenLookup.MOVES.map { (old, new) ->
            Triple(old, new, async { MavenLookup(old.substringBefore(':'), old.substringAfter(':'), listOf(MavenRepo.CENTRAL)).fetch().moved?.to })
        } + MavenLookup.PLUGIN_MOVES.map { (old, new) -> Triple(old, new, async { (GradleProvider.plugin(old) as MavenLookup).fetch().moved?.to }) }
        moves.forEach { (old, new, found) -> assertEquals(old, new, found.await()) }

        val plugin = (GradleProvider.plugin("org.jetbrains.kotlin.jvm") as MavenLookup).fetch()
        assertTrue("2.0.0" in plugin.versions)

        val react = NpmLookup("react", Npmrc(emptyMap()).registry("react")).fetch()
        assertTrue("18.2.0" in react.versions)
        val scoped = NpmLookup("@types/node", Npmrc(emptyMap()).registry("@types/node")).fetch()
        assertTrue("20.0.0" in scoped.versions)

        val gradle = GradleReleases().fetch()
        assertTrue("8.14" in gradle.versions)
        assertNotNull(gradle.publishedAt("8.14"))

        val node = io.github.khopland.versionchecker.toolchain.NodeReleases("20.0.0").fetch()
        assertTrue("20.11.0" in node.versions)
        assertTrue("22.12.0" in node.versions)
        assertFalse("Odd major versions are not LTS", node.versions.any { it.startsWith("21.") })
        val java = io.github.khopland.versionchecker.toolchain.SdkmanLookup("java").fetch()
        assertTrue(java.versions.toString(), java.versions.any { it.endsWith("-tem") })
        val checkout = io.github.khopland.versionchecker.actions.GitHubTags("actions", "checkout", "v3").fetch()
        assertTrue(checkout.versions.toString(), "v4" in checkout.versions && "v4.1.0" !in checkout.versions)

        assertEquals("https://github.com/junit-team/junit4/releases",
            MavenLookup("junit", "junit", listOf(MavenRepo.CENTRAL)).releaseNotes("4.13.2"))
        assertEquals("https://github.com/facebook/react/releases", NpmLookup("react", Npmrc(emptyMap()).registry("react")).releaseNotes("18.2.0"))
    }
}

private typealias ThrowableRunnable<T> = com.intellij.util.ThrowableRunnable<T>
