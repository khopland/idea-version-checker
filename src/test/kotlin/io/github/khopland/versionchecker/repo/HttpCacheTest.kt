package io.github.khopland.versionchecker.repo

import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.http.HttpClient
import java.nio.file.Files

class HttpCacheTest {
    private fun http(directory: java.nio.file.Path) = Http(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), HttpCache(directory))

    @Test
    fun revalidatesAfterARestartAndKeepsCredentialsApart() = runBlocking {
        FakeRepository().use { repository ->
            val directory = Files.createTempDirectory("http-cache")
            repository.routes["/meta.xml"] = FakeRepository.Response(200, "<metadata/>", mapOf("ETag" to "\"v1\""))
            assertEquals("<metadata/>", http(directory).get("${repository.url}/meta.xml").text())

            // A new client is like a restart: it sends the stored ETag, and the 304 gives the stored body.
            val restarted = http(directory).get("${repository.url}/meta.xml")
            assertEquals(200, restarted.status)
            assertEquals("<metadata/>", restarted.text())

            // Other credentials use their own entry, so they get a full response.
            http(directory).get("${repository.url}/meta.xml", mapOf("Authorization" to "Bearer other"))

            // A changed document has a new ETag and replaces the stored copy.
            repository.routes["/meta.xml"] = FakeRepository.Response(200, "<metadata>2</metadata>", mapOf("ETag" to "\"v2\""))
            assertEquals("<metadata>2</metadata>", http(directory).get("${repository.url}/meta.xml").text())
            assertEquals("<metadata>2</metadata>", http(directory).get("${repository.url}/meta.xml").text())
            assertEquals(listOf(200, 304, 200, 200, 304), repository.statuses)
        }
    }
}

class HttpReasonTest {
    @Test
    fun networkErrorsReadWell() {
        assertEquals("could not connect", Http.reason(java.io.IOException(java.net.ConnectException())))
        assertEquals("unknown host", Http.reason(java.net.UnknownHostException("nexus.example")))
        assertEquals("connection timed out", Http.reason(java.net.http.HttpConnectTimeoutException("x")))
        assertEquals("Bad gateway", Http.reason(java.io.IOException("Bad gateway")))
    }
}
