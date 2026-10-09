package io.github.khopland.versionchecker.core

/** Shared project policy syntax. Adapters retain responsibility for validating ecosystem names. */
internal fun parseDeprecationPolicy(text: String): Map<String, String> = text.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith('#') }
    .mapNotNull { line ->
        val parts = line.split('=', limit = 2).map(String::trim)
        val name = parts[0].takeIf(String::isNotBlank) ?: return@mapNotNull null
        name to (parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: "Deprecated by project policy")
    }.toMap()
