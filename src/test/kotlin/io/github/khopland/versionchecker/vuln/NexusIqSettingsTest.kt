package io.github.khopland.versionchecker.vuln

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.VulnerabilityService

/** A project uses Nexus IQ only when it names both a server and an application. */
class NexusIqSettingsTest : BasePlatformTestCase() {
    private val settings get() = NexusIqSettings.getInstance(project)

    override fun tearDown() {
        try {
            settings.loadState(NexusIqSettings.State())
            NexusIqCredentials.set("https://iq.example.com", "", "")
        } finally {
            super.tearDown()
        }
    }

    fun testOsvUnlessTheProjectNamesAServerAndAnApplication() {
        val service = VulnerabilityService.getInstance()
        assertSame(service.source, service.source(project))
        settings.state.serverUrl = "https://iq.example.com/"
        assertSame(service.source, service.source(project))
        settings.state.applicationId = " my-app "

        val source = service.source(project) as NexusIqSource
        assertEquals("https://iq.example.com", source.server)
        assertEquals("my-app", source.applicationId)
        // The same settings give the same source, so it keeps its application ID and its cached results.
        assertSame(source, service.source(project))
        assertSame(service.source, service.source(null))
    }

    fun testCredentialsComeFromThePasswordSafeAndSeparateTheCache() {
        settings.state.serverUrl = "https://iq.example.com"
        settings.state.applicationId = "my-app"
        val anonymous = settings.source()!!
        NexusIqCredentials.set("https://iq.example.com", "me", "token")
        val signedIn = settings.source()!!
        assertNotSame(anonymous, signedIn)
        assertFalse(anonymous.key == signedIn.key)
        assertEquals("me", NexusIqCredentials.get("https://iq.example.com")?.userName)
    }
}
