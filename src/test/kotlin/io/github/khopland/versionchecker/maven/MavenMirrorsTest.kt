package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenRepo
import org.junit.Assert.*
import org.junit.Test

class MavenMirrorsTest {
    private val settings = JDOMUtil.load("""
        <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
          <servers><server><id>nexus</id><username>me</username><password>secret</password></server></servers>
          <mirrors><mirror><id>nexus</id><mirrorOf>*</mirrorOf><url>https://nexus.example/repository/maven-intern</url></mirror></mirrors>
        </settings>""".trimIndent())

    @Test
    fun wildcardMirrorReplacesEveryRepositoryAndUsesItsServer() {
        val repos = MavenRepos.resolve(listOf(MavenRepo("central", "http://central"), MavenRepo("other", "https://other.example/m2")),
            MavenSettings.parse(settings), MavenSettings.mirrors(settings))
        assertEquals(listOf("https://nexus.example/repository/maven-intern"), repos.map { it.url })
        assertEquals(Http.basicAuth("me", "secret"), repos.single().headers["Authorization"])
    }

    @Test
    fun centralFallbackGoesThroughTheMirror() {
        val repos = MavenRepos.resolve(emptyList(), emptyMap(), MavenSettings.mirrors(settings))
        assertEquals(listOf("nexus"), repos.map { it.id })
    }

    @Test
    fun patternsMatchLikeMaven() {
        assertTrue(MavenMirror.matches("external:*", "a", "https://repo.example"))
        assertFalse(MavenMirror.matches("external:*", "a", "http://localhost:8081/m2"))
        assertTrue(MavenMirror.matches("external:http:*", "a", "http://repo.example"))
        assertFalse(MavenMirror.matches("external:http:*", "a", "https://repo.example"))
        assertFalse(MavenMirror.matches("*,!central", "central", "https://repo.example"))
        assertTrue(MavenMirror.matches("a,b", "b", "https://repo.example"))
        val exact = MavenMirror("exact", "central", "https://exact.example")
        val any = MavenMirror("any", "*", "https://any.example")
        assertEquals(exact, MavenMirror.select(listOf(any, exact), "central", "https://repo.example"))
    }

    @Test
    fun blockedMirrorDropsTheRepository() {
        val blocker = MavenMirror("maven-default-http-blocker", "external:http:*", "http://0.0.0.0/", blocked = true)
        val repos = MavenRepos.resolve(listOf(MavenRepo("old", "http://old.example"), MavenRepo("central", "https://repo.example")),
            emptyMap(), listOf(blocker))
        assertEquals(listOf("central"), repos.map { it.id })
    }
}
