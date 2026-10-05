package io.github.khopland.versionchecker.npm

import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.VersionChangeKind

/** Compare declared range floors, never claim to know the installed/locked version. */
internal data class NpmVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<NpmVersion> {
    override fun compareTo(other: NpmVersion): Int =
        compareValuesBy(this, other, NpmVersion::major, NpmVersion::minor, NpmVersion::patch)
    override fun toString() = "$major.$minor.$patch"
    fun allows(other: NpmVersion, mode: UpdateMode) = other > this && when (mode) {
        UpdateMode.PATCH -> major == other.major && minor == other.minor
        UpdateMode.MINOR -> major == other.major
        UpdateMode.MAJOR -> true
    }
    fun change(other: NpmVersion) = when {
        major != other.major -> VersionChangeKind.MAJOR
        minor != other.minor -> VersionChangeKind.MINOR
        else -> VersionChangeKind.PATCH
    }
    companion object {
        private val stable = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z.-]+)?")
        fun parse(text: String): NpmVersion? {
            val parts = stable.matchEntire(text)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null } ?: return null
            return NpmVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class NpmSelector(val packageName: String, val baseline: NpmVersion, val prefix: String) {
    fun replace(version: String) = prefix + version
    companion object {
        private val packageName = Regex("(?:@[A-Za-z0-9][A-Za-z0-9._-]*/)?[A-Za-z0-9][A-Za-z0-9._-]*")
        fun validName(name: String) = packageName.matches(name)
        fun parse(name: String, selector: String): NpmSelector? {
            if (!validName(name)) return null
            var target = name
            var version = selector
            var prefix = ""
            if (selector.startsWith("npm:")) {
                val separator = selector.lastIndexOf('@')
                if (separator <= 4) return null
                target = selector.substring(4, separator)
                if (!validName(target)) return null
                prefix = selector.substring(0, separator + 1)
                version = selector.substring(separator + 1)
            }
            if (version.startsWith('^') || version.startsWith('~')) {
                prefix += version.first()
                version = version.drop(1)
            }
            return NpmSelector(target, NpmVersion.parse(version) ?: return null, prefix)
        }
    }
}
