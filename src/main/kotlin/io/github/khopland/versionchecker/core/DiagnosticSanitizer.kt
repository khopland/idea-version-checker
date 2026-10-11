package io.github.khopland.versionchecker.core

/** Display/copy boundary only. Retry identities and native provider inputs remain untouched. */
internal object DiagnosticSanitizer {
    private val privateKey = Regex("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)")
    private val xmlCredential = Regex("(?is)(<(password|passphrase|username|master|token|secret)(?:\\s[^>]*)?>).*?(</\\2\\s*>)")
    private val credential = Regex(
        "(?im)((?:[\\\"']?)(?:authorization|proxy-authorization|password|passwd|passphrase|username|_authToken|_auth|npm_token|access_token|refresh_token|api[_-]?key|client[_-]?secret|secret|token)(?:[\\\"']?)\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\r\\n,;]+)"
    )
    private val authHeader = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_~.-]+")
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"']+")
    private val windowsPath = Regex("(?i)\\b[A-Z]:[\\\\/][^\\r\\n\\\"'<>]*")
    // Unquoted native paths can contain spaces. Prefer hiding the remainder of that
    // path-bearing clause over exposing a project/user name after the first space.
    private val localPath = Regex("(?<![\\w:/])/(?!/)[^\\r\\n\\\"'<>:,;)]+")

    fun sanitize(text: String): String {
        var safe = privateKey.replace(text, "<private-key redacted>")
        safe = xmlCredential.replace(safe) { "${it.groupValues[1]}<redacted>${it.groupValues[3]}" }
        safe = credential.replace(safe) { "${it.groupValues[1]}<redacted>" }
        safe = authHeader.replace(safe) { "${it.groupValues[1]} <redacted>" }
        safe = url.replace(safe) { match ->
            val value = match.value
            val schemeEnd = value.indexOf("://") + 3
            if (value.substring(0, schemeEnd).equals("file://", ignoreCase = true)) "file://<local-path>"
            else {
                val withoutQuery = value.substringBefore('?').substringBefore('#')
                val authorityEnd = withoutQuery.indexOf('/', schemeEnd).let { if (it < 0) withoutQuery.length else it }
                val authority = withoutQuery.substring(schemeEnd, authorityEnd).substringAfterLast('@')
                withoutQuery.substring(0, schemeEnd) + authority + withoutQuery.substring(authorityEnd)
            }
        }
        safe = windowsPath.replace(safe, "<local-path>")
        return localPath.replace(safe, "<local-path>")
    }
}
