package io.github.khopland.versionchecker.vuln

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.khopland.versionchecker.core.ApplicationHint
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.refreshAll
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The Nexus IQ server of one project, and optionally its application. They are stored in `.idea/version-checker.xml`, so
 * the team shares them. The user name and password are each user's own, in the IDE password safe.
 */
@Service(Service.Level.PROJECT)
@State(name = "VersionCheckerNexusIq", storages = [Storage("version-checker.xml")])
class NexusIqSettings : SimplePersistentStateComponent<NexusIqSettings.State>(State()) {
    class State : BaseState() {
        var serverUrl by string()
        var applicationId by string()
    }

    val serverUrl get() = state.serverUrl?.trim()?.trimEnd('/')?.ifEmpty { null }
    val applicationId get() = state.applicationId?.trim()?.ifEmpty { null }

    /** By server, application and credentials, so each keeps its application's internal ID. */
    private val sources = ConcurrentHashMap<List<String?>, NexusIqSource>()

    /** Replaced in tests. */
    internal var http: () -> Http = Http::getInstance

    fun clear() = sources.clear()

    /**
     * The source for a build file when the project names a server, otherwise null. Its application is the one the file
     * declared, else the one in the settings, else the first of the file's names that the server has. Reads the password
     * safe once per server, and may ask the server, so it runs in the background.
     */
    suspend fun source(hint: ApplicationHint? = null): NexusIqSource? {
        val server = serverUrl ?: return null
        val credentials = NexusIqCredentials.get(server)
        val user = credentials?.userName
        val password = credentials?.getPasswordAsString()
        fun source(application: String) = sources.getOrPut(listOf(server, application, user, password)) { NexusIqSource(server, application, user, password, http) }
        (hint?.declared ?: applicationId)?.let { return source(it) }
        val names = hint?.names.orEmpty()
        names.firstOrNull { source(it).exists() }?.let { return source(it) }
        val where = listOfNotNull(hint?.where, "the application ID in $SETTINGS").joinToString(", or ")
        throw HttpFailure((if (names.isEmpty()) "No Nexus IQ application" else "Nexus IQ has no application ${names.joinToString(" or ")}") + ". Set $where")
    }

    companion object {
        const val SETTINGS = "Settings | Tools | Version Checker | Nexus IQ"

        fun getInstance(project: Project): NexusIqSettings = project.service()
    }
}

/** The user name and password or user token for each Nexus IQ server, in the IDE password safe. */
object NexusIqCredentials {
    private val memory = ConcurrentHashMap<String, Credentials>()
    private val none = Credentials(null, null as String?)

    private fun attributes(server: String) = CredentialAttributes(generateServiceName("Version Checker", "Nexus IQ $server"))

    fun get(server: String): Credentials? =
        memory.getOrPut(server) { PasswordSafe.instance.get(attributes(server)) ?: none }.takeIf { it !== none }

    fun set(server: String, user: String, password: String) {
        val credentials = if (user.isEmpty() && password.isEmpty()) null else Credentials(user, password)
        PasswordSafe.instance.set(attributes(server), credentials)
        memory[server] = credentials ?: none
    }
}

/** **Settings → Tools → Version Checker → Nexus IQ**, per project. */
class NexusIqConfigurable(private val project: Project) : BoundConfigurable("Nexus IQ") {
    private val settings get() = NexusIqSettings.getInstance(project)
    private var user = ""
    private var password = ""
    private lateinit var serverField: JBTextField
    private lateinit var applicationField: JBTextField
    private lateinit var userField: JBTextField
    private lateinit var passwordField: JBPasswordField

    override fun createPanel(): DialogPanel {
        val credentials = settings.serverUrl?.let(NexusIqCredentials::get)
        user = credentials?.userName.orEmpty()
        password = credentials?.getPasswordAsString().orEmpty()
        return panel {
            row {
                comment("With a server, <b>Check for known vulnerabilities</b> asks Nexus IQ instead of osv.dev. " +
                    "It reports the vulnerabilities that the application's policies flag and that are not waived, and Alt+Enter opens the application's report to waive them.")
            }
            row("Server URL:") {
                serverField = textField().columns(COLUMNS_LARGE).comment("For example https://iq.example.com")
                    .bindText({ settings.state.serverUrl.orEmpty() }, { settings.state.serverUrl = it.trim().ifEmpty { null } })
                    .validationOnApply { field -> serverProblem(field.text)?.let { error(it) } }.component
            }
            row("Application ID:") {
                applicationField = textField().columns(COLUMNS_LARGE)
                    .comment("Optional. The public ID of the application whose policies and waivers apply. A <code>clm.applicationId</code> in a POM comes first. " +
                        "Without either, a build file uses the application named after its artifact ID or package name.")
                    .bindText({ settings.state.applicationId.orEmpty() }, { settings.state.applicationId = it.trim().ifEmpty { null } }).component
            }
            row("User name:") { userField = textField().columns(COLUMNS_LARGE).bindText(::user).component }
            row("Password or user token:") {
                passwordField = passwordField().columns(COLUMNS_LARGE).bindText(::password)
                    .comment("Kept in the IDE password safe, not in the project.").component
            }
            row { button("Test Connection") { testConnection() } }
        }
    }

    private fun testConnection() {
        val server = serverField.text.trim()
        val application = applicationField.text.trim()
        val problem = serverProblem(server) ?: "Enter the server URL.".takeIf { server.isEmpty() }
        if (problem != null) {
            Messages.showErrorDialog(project, problem, "Nexus IQ")
            return
        }
        // Without an application, the project's name is looked up, which checks the server and the credentials too.
        val name = application.ifEmpty { project.name }
        val source = NexusIqSource(server, name, userField.text.trim(), String(passwordField.password))
        val result = runWithModalProgressBlocking(project, "Connecting to Nexus IQ") { runCatching { source.exists() } }
        result.fold(
            { exists ->
                when {
                    exists -> Messages.showInfoMessage(project, "Connected. Nexus IQ has application $name.", "Nexus IQ")
                    application.isEmpty() -> Messages.showInfoMessage(project, "Connected. Each build file uses its clm.applicationId, " +
                        "or the application named after its artifact ID or package name.", "Nexus IQ")
                    else -> Messages.showErrorDialog(project, "Connected, but Nexus IQ has no application $name, or this user cannot see it.", "Nexus IQ")
                }
            },
            { Messages.showErrorDialog(project, it.message ?: it.javaClass.simpleName, "Nexus IQ") })
    }

    /** Why [url] cannot be a server URL, or null when it can or is empty. */
    private fun serverProblem(url: String): String? {
        val text = url.trim().ifEmpty { return null }
        val uri = runCatching { URI(text) }.getOrNull()
        return if (uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrEmpty()) null else "Enter a URL that starts with https:// or http://"
    }

    override fun apply() {
        super.apply()
        settings.serverUrl?.let { NexusIqCredentials.set(it, user.trim(), password) }
        refreshAll()
    }
}
