package io.github.khopland.versionchecker.core

/** Provider-owned recovery text, safe to display without native output or credentials. */
internal interface VersionCheckFailureAdvice {
    val recoveryMessage: String
}
