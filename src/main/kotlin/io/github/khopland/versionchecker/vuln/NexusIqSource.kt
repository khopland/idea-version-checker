package io.github.khopland.versionchecker.vuln

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.diagnostic.logger
import io.github.khopland.versionchecker.core.Advisory
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.HttpResult
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.VulnerabilitySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A [Nexus IQ Server](https://help.sonatype.com/en/iq-server.html) (Sonatype Lifecycle). The packages are evaluated
 * against the policies of one application, so its waivers apply: a vulnerability shows only while a policy violation that
 * names it is neither waived nor legacy. Vulnerabilities that no policy reports are left out, as Nexus IQ leaves them out of
 * the build. The fix is the version Nexus IQ suggests that has no policy violations.
 */
class NexusIqSource(
    server: String,
    val applicationId: String,
    private val user: String?,
    password: String?,
    private val http: () -> Http = Http::getInstance,
    private val pollMillis: Long = 500,
    private val timeoutMillis: Long = 120_000,
) : VulnerabilitySource {
    val server = server.trim().trimEnd('/')
    override val key = "nexus-iq $server $applicationId ${user.orEmpty()}"
    private val headers = if (user.isNullOrEmpty()) emptyMap() else mapOf("Authorization" to Http.basicAuth(user, password.orEmpty()))

    @Volatile
    private var internalId: String? = null

    /** The internal ID of the application, which the other calls need. Checks the server, the credentials and the application. */
    suspend fun application(): String = internalId ?: run {
        val response = http().get("$server/api/v2/applications?publicId=${encode(applicationId)}", headers, store = false)
        if (!response.ok) throw failure(response, "reading application $applicationId")
        val id = parse(response.text()).array("applications").objects().firstOrNull { it.string("publicId") == applicationId }?.string("id")
            ?: throw HttpFailure("Nexus IQ has no application $applicationId, or this user cannot see it")
        id.also { internalId = it }
    }

    override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> = coroutineScope {
        if (packages.isEmpty()) return@coroutineScope emptyMap()
        val app = application()
        val found = packages.distinct().chunked(BATCH).map { async { evaluate(app, it) } }.awaitAll()
            .flatMap { it.entries }.associate { it.toPair() }.filterValues { it.isNotEmpty() }
        val fixes = found.mapValues { (pkg, _) -> async { remediation(app, pkg) } }
        // Versions below the suggested one count as affected, and it and newer ones as fixed.
        found.mapValues { (pkg, advisories) -> fixes.getValue(pkg).await()?.let { fix -> advisories.map { it.copy(fixed = listOf(fix)) } } ?: advisories }
    }

    override fun actions(pkg: Pkg, advisories: List<Advisory>): List<IntentionAction> = listOf(OpenInNexusIqFix(this, pkg))

    /** The page of the application's newest scan report, where a user can waive violations, or null before its first scan. */
    suspend fun reportUrl(): String? {
        val response = http().get("$server/api/v2/reports/applications/${application()}", headers, store = false)
        if (!response.ok) throw failure(response, "reading the reports")
        val reports = JsonParser.parseString(response.text()).takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().objects()
        val newest = reports.maxByOrNull { report -> report.string("evaluationDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: Instant.MIN }
        return newest?.string("reportHtmlUrl")?.let { "$server/${it.trimStart('/')}" }
    }

    private suspend fun evaluate(app: String, packages: List<Pkg>): Map<Pkg, List<Advisory>> {
        val components = JsonArray().apply { packages.forEach { pkg -> add(JsonObject().apply { addProperty("packageUrl", purl(pkg)) }) } }
        val response = http().post("$server/api/v2/evaluation/applications/$app", JsonObject().apply { add("components", components) }.toString(), headers)
        if (!response.ok) throw failure(response, "evaluating dependencies")
        val result = parse(response.text()).string("resultId") ?: throw HttpFailure("Nexus IQ returned no evaluation result")
        // The result is 404 until the evaluation is done.
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val evaluation = http().get("$server/api/v2/evaluation/applications/$app/results/$result", headers, store = false)
            if (evaluation.ok) return parseEvaluation(evaluation.text(), packages)
            if (evaluation.status != 404) throw failure(evaluation, "reading the evaluation")
            if (System.nanoTime() > deadline) throw HttpFailure("Nexus IQ did not finish the evaluation in ${timeoutMillis / 1000} seconds")
            delay(pollMillis)
        }
    }

    /** The version Nexus IQ suggests, or null without one. A failure leaves the advisories without a fix. */
    private suspend fun remediation(app: String, pkg: Pkg): String? = try {
        val body = JsonObject().apply { addProperty("packageUrl", purl(pkg)) }.toString()
        val response = http().post("$server/api/v2/components/remediation/application/$app?stageId=develop", body, headers)
        if (!response.ok) throw failure(response, "finding a remediation")
        parseRemediation(response.text())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.info("Could not find a remediation for ${pkg.name} ${pkg.version}", e)
        null
    }

    companion object {
        private val LOG = logger<NexusIqSource>()
        private const val BATCH = 500

        fun purl(pkg: Pkg): String = when (pkg.ecosystem) {
            Ecosystem.MAVEN -> "pkg:maven/${pkg.name.split(':').joinToString("/") { encode(it) }}@${encode(pkg.version)}?type=jar"
            Ecosystem.NPM -> "pkg:npm/${pkg.name.split('/').joinToString("/") { encode(it) }}@${encode(pkg.version)}"
        }

        /** A package URL without qualifiers and escapes, to match the one Nexus IQ returns. */
        internal fun normalize(purl: String) = URLDecoder.decode(purl.substringBefore('#').substringBefore('?').replace("+", "%2B"), Charsets.UTF_8)

        /** The advisories of each evaluated package, matched by package URL. */
        internal fun parseEvaluation(text: String, packages: List<Pkg>): Map<Pkg, List<Advisory>> {
            val document = parse(text)
            if (document.get("isError")?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean == true)
                throw HttpFailure("Nexus IQ could not evaluate the dependencies: ${document.string("errorMessage") ?: "no reason given"}")
            val byPurl = packages.associateBy { normalize(purl(it)) }
            return document.array("results").objects().mapNotNull { result ->
                val pkg = result.obj("component")?.string("packageUrl")?.let { byPurl[normalize(it)] } ?: return@mapNotNull null
                pkg to advisories(result)
            }.toMap()
        }

        /**
         * The security issues of one component that an active policy violation names, by its reference or in its reason text.
         * Issues that only waived or legacy violations name, and those marked not applicable, are left out.
         */
        internal fun advisories(result: JsonObject): List<Advisory> {
            val issues = result.obj("securityData")?.array("securityIssues").objects()
                .filter { it.string("reference") != null && !it.string("status").equals("Not Applicable", ignoreCase = true) }
            // The policies that report each vulnerability.
            val active = mutableMapOf<String, MutableList<String>>()
            for (violation in result.obj("policyData")?.array("policyViolations").objects()) {
                if (violation.string("waiveTime") != null || violation.string("legacyViolationTime") != null) continue
                val reasons = violation.array("constraintViolations").objects().flatMap { it.array("reasons").objects() }
                val named = reasons.mapNotNull { reason -> reason.obj("reference")?.takeIf { it.string("type") == "SECURITY_VULNERABILITY_REFID" }?.string("value") }
                val text = reasons.mapNotNull { it.string("reason") }
                val refs = named + issues.mapNotNull { it.string("reference") }.filter { ref -> text.any { Regex("\\b${Regex.escape(ref)}\\b").containsMatchIn(it) } }
                refs.distinct().forEach { active.getOrPut(it) { mutableListOf() } += violation.string("policyName") ?: "Unnamed" }
            }
            return issues.filter { it.string("reference") in active }.map { issue ->
                val ref = issue.string("reference")!!
                Advisory(
                    id = ref,
                    aliases = issue.array("vulnIds").mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.filter { it != ref },
                    severity = issue.get("severity")?.takeIf(JsonElement::isJsonPrimitive)?.asDouble?.let(Cvss::rating),
                    summary = active.getValue(ref).distinct().joinToString(", ", postfix = " policy"),
                    fixed = emptyList(),
                )
            }.distinctBy { it.id }
        }

        /** The suggested version without policy violations. */
        internal fun parseRemediation(text: String): String? =
            parse(text).obj("remediation")?.array("versionChanges").objects().firstOrNull { it.string("type") == "next-no-violations" }
                ?.obj("data")?.obj("component")?.let { component ->
                    component.obj("componentIdentifier")?.obj("coordinates")?.string("version")
                        ?: component.string("packageUrl")?.let { normalize(it).substringAfterLast('@') }
                }

        private fun failure(response: HttpResult, action: String) = HttpFailure(when (response.status) {
            401 -> "Nexus IQ did not accept the user name and password (HTTP 401)"
            403 -> "Nexus IQ denied this user while $action (HTTP 403)"
            else -> "Nexus IQ returned HTTP ${response.status} while $action"
        })

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
        private fun parse(text: String) = JsonParser.parseString(text).asJsonObject
        private fun JsonObject.string(name: String): String? = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
        private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()
        private fun List<JsonElement>?.objects(): List<JsonObject> = orEmpty().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    }
}
