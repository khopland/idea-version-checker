package io.github.khopland.versionchecker.repo

import com.sun.net.httpserver.HttpServer
import io.github.khopland.versionchecker.core.Http
import java.net.InetSocketAddress
import java.net.http.HttpClient

/** A local HTTP server. [routes] maps a path to status, body and headers. Records each request as "METHOD path". */
class FakeRepository : AutoCloseable {
    class Response(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    val routes = mutableMapOf<String, Response>()
    val requests = mutableListOf<String>()
    val authorizations = mutableListOf<String?>()
    /** The status code sent for each request. */
    val statuses = mutableListOf<Int>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            synchronized(requests) {
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                authorizations += exchange.requestHeaders.getFirst("Authorization")
            }
            val route = routes[exchange.requestURI.path] ?: Response(404)
            val etag = route.headers["ETag"]
            val response = if (etag != null && exchange.requestHeaders.getFirst("If-None-Match") == etag) Response(304, headers = route.headers) else route
            synchronized(requests) { statuses += response.status }
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            if (exchange.requestMethod == "HEAD" || response.status == 304) exchange.sendResponseHeaders(response.status, -1)
            else {
                exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }

    val url = "http://127.0.0.1:${server.address.port}"
    val http = Http(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())

    override fun close() = server.stop(0)

    companion object {
        fun metadata(vararg versions: String, lastUpdated: String? = null) = """
            <metadata><groupId>g</groupId><artifactId>a</artifactId><versioning>
              <versions>${versions.joinToString("") { "<version>$it</version>" }}</versions>
              ${lastUpdated?.let { "<lastUpdated>$it</lastUpdated>" } ?: ""}
            </versioning></metadata>""".trimIndent()
    }
}
