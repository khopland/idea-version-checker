package io.github.khopland.versionchecker.gradle

/** Static ownership only. Executable settings DSL is deliberately not evaluated in the IDE. */
internal object GradleCatalogs {
    data class Registration(val name: String, val path: String, val offset: Int)
    data class Review(val offset: Int, val reason: String)
    data class Parsed(val registrations: List<Registration>, val reviews: List<Review>)

    fun parse(text: String, kotlin: Boolean): Parsed {
        val tokens = GradleDeclarations.tokens(text, !kotlin)
        val registrations = mutableListOf<Registration>()
        val reviews = mutableListOf<Review>()
        fun review(offset: Int) {
            reviews += Review(offset, "Only literal local TOML catalog registrations using create(\"name\") { from(files(\"path.toml\")) } " +
                "have known input ownership; published, computed or customized catalogs need manual review")
        }
        val delimiters = mutableListOf<String>()
        for (token in tokens.filterNot { it.string }) {
            if (token.text in setOf("{", "(", "[")) delimiters += token.text
            if (token.text in setOf("}", ")", "]")) {
                val expected = mapOf("}" to "{", ")" to "(", "]" to "[").getValue(token.text)
                if (delimiters.lastOrNull() != expected) {
                    if (tokens.any { !it.string && it.text == "versionCatalogs" }) review(token.start)
                    return Parsed(emptyList(), reviews)
                }
                delimiters.removeAt(delimiters.lastIndex)
            }
        }
        if (delimiters.isNotEmpty()) {
            tokens.firstOrNull { !it.string && it.text == "versionCatalogs" }?.let { review(it.start) }
            return Parsed(emptyList(), reviews)
        }
        fun close(start: Int): Int? {
            var depth = 0
            for (index in start until tokens.size) {
                val token = tokens[index]
                if (token.string) continue
                if (token.text == "{") depth++
                if (token.text == "}" && --depth == 0) return index
            }
            return null
        }
        val blocks = mutableListOf<String>()
        fun directBlock(index: Int): String {
            val call = tokens.getOrNull(index - 1) ?: return ""
            if (call.string || tokens.getOrNull(index - 2)?.text == ".") return ""
            if (call.text != "dependencyResolutionManagement") return call.text
            val before = tokens.getOrNull(index - 2)
            if (before?.text in setOf("=", "else", "return", "@")) return ""
            if (before != null && (before.string || before.text !in setOf(";", "}")) &&
                '\n' !in text.substring(before.start, call.start)) return ""
            if (before?.text == ")") {
                var depth = 1
                var previous = index - 3
                while (previous >= 0 && depth > 0) {
                    if (!tokens[previous].string) {
                        if (tokens[previous].text == ")") depth++
                        if (tokens[previous].text == "(") depth--
                    }
                    previous--
                }
                if (tokens.getOrNull(previous)?.text in setOf("if", "while", "for")) return ""
            }
            return call.text
        }
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.string) { index++; continue }
            if (token.text == "versionCatalogs") {
                val open = index + 1
                val end = if (tokens.getOrNull(open)?.text == "{") close(open) else null
                if (blocks != listOf("dependencyResolutionManagement") || tokens.getOrNull(index - 1)?.text == "." || end == null) {
                    review(token.start)
                    index++
                    continue
                }
                var cursor = open + 1
                while (cursor < end) {
                    if (tokens[cursor].text == ";" && !tokens[cursor].string) { cursor++; continue }
                    // Exact token grammar prevents concatenation, interpolation, extra from calls,
                    // builder overrides and conditional registrations from acquiring edit authority.
                    val pattern = listOf("create", "(", null, ")", "{", "from", "(", "files", "(", null, ")", ")", "}")
                    val part = tokens.subList(cursor, minOf(cursor + pattern.size, end))
                    val matches = part.size == pattern.size && pattern.indices.all { at ->
                        val expected = pattern[at]
                        if (expected == null) part[at].string && part[at].text.isNotBlank() &&
                            part[at].text.none { it == '$' || it == '\\' || it == '\n' || it == '\r' }
                        else !part[at].string && part[at].text == expected
                    }
                    if (!matches) { review(tokens[cursor].start); break }
                    val name = part[2].text
                    val path = part[9].text
                    if (!Regex("[A-Za-z][A-Za-z0-9_]*").matches(name) || registrations.any { it.name == name }) {
                        review(part[0].start)
                    } else registrations += Registration(name, path, part[0].start)
                    cursor += pattern.size
                }
                index = end + 1
                continue
            }
            if (token.text == "{") blocks += directBlock(index)
            if (token.text == "}" && blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
            index++
        }
        return Parsed(registrations, reviews)
    }
}
