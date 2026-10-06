package io.github.khopland.versionchecker

enum class UpdateMode(val label: String) {
    PATCH("Patch only"), MINOR("Minor + patch"), MAJOR("Major + minor + patch")
}
