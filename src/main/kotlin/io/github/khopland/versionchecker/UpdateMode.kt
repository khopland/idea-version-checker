package io.github.khopland.versionchecker

enum class UpdateMode(val label: String) {
    PATCH("Patch only"), MINOR("Minor + patch"), MAJOR("Major + minor + patch");

    fun allows(current: String, latest: String): Boolean {
        if (this == MAJOR) return true
        val old = numericPrefix(current) ?: return false
        val new = numericPrefix(latest) ?: return false
        return old.first == new.first && (this == MINOR || old.second == new.second)
    }

    private fun numericPrefix(version: String): Pair<Int, Int>? {
        val match = Regex("""^(\d+)(?:\.(\d+))?(?:\.|-|$).*""").matchEntire(version) ?: return null
        return (match.groupValues[1].toIntOrNull() ?: return null) to
            (match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0)
    }
}
