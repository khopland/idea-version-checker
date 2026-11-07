package io.github.khopland.versionchecker.vuln

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.ApplicationHint
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.VulnerabilityService
import io.github.khopland.versionchecker.repo.FakeRepository
import kotlinx.coroutines.runBlocking

/** A project uses Nexus IQ when it names a server. The application comes from the build file or the settings. */
class NexusIqSettingsTest : BasePlatformTestCase() {
    private val settings get() = NexusIqSettings.getInstance(project)

    override fun tearDown() {
        try {
            settings.loadState(NexusIqSettings.State())
            settings.clear()
            settings.http = Http::getInstance
            NexusIqCredentials.set("https://iq.example.com", "", "")
        } finally {
            super.tearDown()
        }
    }

    fun testOsvUnlessTheProjectNamesAServer() = runBlocking {
        val service = VulnerabilityService.getInstance()
        assertSame(service.source, service.source(project))
        settings.state.serverUrl = "https://iq.example.com/"
        settings.state.applicationId = " my-app "

        val source = service.source(project) as NexusIqSource
        assertEquals("https://iq.example.com", source.server)
        assertEquals("my-app", source.applicationId)
        // The same settings give the same source, so it keeps its application ID and its cached results.
        assertSame(source, service.source(project))
        assertSame(service.source, service.source(null))
    }

    fun testCredentialsComeFromThePasswordSafeAndSeparateTheCache() = runBlocking {
        settings.state.serverUrl = "https://iq.example.com"
        settings.state.applicationId = "my-app"
        val anonymous = settings.source()!!
        NexusIqCredentials.set("https://iq.example.com", "me", "token")
        val signedIn = settings.source()!!
        assertNotSame(anonymous, signedIn)
        assertFalse(anonymous.key == signedIn.key)
        assertEquals("me", NexusIqCredentials.get("https://iq.example.com")?.userName)
    }

    fun testTheBuildFileDeclaresTheApplicationOrNamesIt() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": []}""")
            settings.state.serverUrl = repository.url
            settings.http = { repository.http }

            // A declared application comes before the settings, and is not looked up first.
            settings.state.applicationId = "team-app"
            assertEquals("pom-app", settings.source(ApplicationHint("pom-app", listOf("web")))!!.applicationId)
            assertEquals("team-app", settings.source(ApplicationHint(names = listOf("web")))!!.applicationId)
            assertEquals(emptyList<String>(), repository.requests)

            // Otherwise the first name that the server has.
            settings.state.applicationId = null
            val error = runCatching { settings.source(ApplicationHint(names = listOf("web", "shop"), where = "clm.applicationId in the POM")) }.exceptionOrNull()
            assertEquals("Nexus IQ has no application web or shop. Set clm.applicationId in the POM, or the application ID in ${NexusIqSettings.SETTINGS}", error?.message)
            assertEquals(2, repository.requests.size)
            // A missing name is not asked for again right away.
            runCatching { settings.source(ApplicationHint(names = listOf("web", "shop"))) }
            assertEquals(2, repository.requests.size)

            settings.clear()
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-2", "publicId": "shop"}]}""")
            assertEquals("shop", settings.source(ApplicationHint(names = listOf("web", "shop")))!!.applicationId)
            assertEquals("No Nexus IQ application. Set the application ID in ${NexusIqSettings.SETTINGS}",
                runCatching { settings.source(null) }.exceptionOrNull()?.message)
        }
    }
}
