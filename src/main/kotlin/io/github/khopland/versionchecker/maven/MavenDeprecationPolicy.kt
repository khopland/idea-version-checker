package io.github.khopland.versionchecker.maven

/** Explicit project policy: Maven repositories do not define a general deprecation flag. */
internal fun deprecatedDependencies(text: String): Map<String, String> = text.lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
    .mapNotNull { line ->
        val parts = line.split('=', limit = 2).map { it.trim() }
        val coordinate = parts[0]
        if (!Regex("""[^\s:]+:[^\s:]+""").matches(coordinate)) null
        else coordinate to (parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Deprecated by project policy")
    }.toMap()
