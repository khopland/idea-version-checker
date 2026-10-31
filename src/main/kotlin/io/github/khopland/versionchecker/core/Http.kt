package io.github.khopland.versionchecker.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.net.JdkProxyProvider
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class HttpResult(val status: Int, val body: ByteArray, private val headers: Map<String, List<String>>) {
    val ok get() = status in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun text() = String(body, Charsets.UTF_8)
}

class HttpFailure(message: String) : IOException(message)

/** One HTTP/2 client for every repository, limited per host. It uses the IDE proxy settings. */
@Service
class Http internal constructor(private val client: HttpClient) {
    @Suppress("unused") // Created by the platform.
    constructor() : this(defaultClient())

    private val hosts = ConcurrentHashMap<String, Semaphore>()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = send(url, "GET", headers)

    suspend fun head(url: String, headers: Map<String, String> = emptyMap()) = send(url, "HEAD", headers)

    private suspend fun send(url: String, method: String, headers: Map<String, String>): HttpResult {
        val uri = URI(url)
        val request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).method(method, HttpRequest.BodyPublishers.noBody())
            .header("User-Agent", "IntelliJ-Version-Checker")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val permits = hosts.computeIfAbsent(uri.authority.orEmpty()) { Semaphore(PER_HOST) }
        val response = try {
            permits.withPermit { client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await() }
        } catch (e: IOException) {
            throw HttpFailure("${uri.host}: ${e.message ?: e.javaClass.simpleName}")
        }
        return HttpResult(response.statusCode(), response.body() ?: ByteArray(0), response.headers().map())
    }

    companion object {
        private const val PER_HOST = 12
        private val TIMEOUT = Duration.ofSeconds(20)

        fun getInstance(): Http = ApplicationManager.getApplication().getService(Http::class.java)

        private fun defaultClient(): HttpClient {
            val proxy = JdkProxyProvider.getInstance()
            return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(proxy.proxySelector)
                .authenticator(proxy.authenticator)
                .build()
        }

        fun basicAuth(user: String, password: String): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }
}
