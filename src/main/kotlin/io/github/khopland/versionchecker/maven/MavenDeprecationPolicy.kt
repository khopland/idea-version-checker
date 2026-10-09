package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.parseDeprecationPolicy

private val coordinate = Regex("""[^\s:]+:[^\s:]+""")

/** Explicit project policy: Maven repositories do not define a general deprecation flag. */
internal fun deprecatedDependencies(text: String): Map<String, String> =
    parseDeprecationPolicy(text).filterKeys(coordinate::matches)
