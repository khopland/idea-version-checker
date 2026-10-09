package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.util.TextRange
import io.github.khopland.versionchecker.core.*
import org.tomlj.Toml
import org.tomlj.TomlPosition
import org.tomlj.TomlTable

internal data class GradleDeclaration(val declaration: VersionDeclaration, val range: TextRange?, val reason: String? = null)

/** Only fixed, numeric releases can be edited automatically. Gradle orders candidates during resolution. */
internal object GradleVersions {
    const val STABLE = "(?i)^[0-9]+(?:\\.[0-9]+)*(?:[.-](?:final|ga|release|sp[0-9]*|jre|android))?$"
    const val SUFFIX = "(?i)[.-](final|ga|release|sp[0-9]*|jre|android)$"
    private fun qualifier(version: String) = Regex(SUFFIX).find(version)?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT).orEmpty()
    private fun channel(version: String) = qualifier(version).takeIf { it == "jre" || it == "android" }.orEmpty()
    fun fixed(version: String) = Regex(STABLE).matches(version)
    fun newer(current: String, latest: String): Boolean {
        if (!fixed(current) || !fixed(latest) || channel(current) != channel(latest)) return false
        fun numbers(value: String) = Regex("^[0-9]+(?:\\.[0-9]+)*").find(value)!!.value.split('.').map { it.toBigInteger() }
        val old = numbers(current); val next = numbers(latest)
        for (index in 0 until maxOf(old.size, next.size)) {
            val comparison = (next.getOrNull(index) ?: java.math.BigInteger.ZERO).compareTo(old.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        val oldQualifier = qualifier(current); val newQualifier = qualifier(latest)
        val oldPack = oldQualifier.startsWith("sp"); val newPack = newQualifier.startsWith("sp")
        if (oldPack != newPack) return newPack
        if (!oldPack) return false // final/ga/release are equivalent stable releases.
        fun packNumber(value: String) = value.removePrefix("sp").ifEmpty { "0" }.toBigInteger()
        return packNumber(newQualifier) > packNumber(oldQualifier)
    }
    fun kind(current: String, latest: String): VersionChangeKind {
        fun parts(s: String) = s.substringBefore('-').split('.').take(3).map { it.toIntOrNull() ?: 0 }.let { it + List(3 - it.size) { 0 } }
        val old = parts(current); val new = parts(latest)
        return when { old[0] != new[0] -> VersionChangeKind.MAJOR; old[1] != new[1] -> VersionChangeKind.MINOR; else -> VersionChangeKind.PATCH }
    }
}

internal object GradleDeclarations {
    // Unknown calls may be user helpers, even if their arguments match a real dependency.
    private val configurations = setOf(
        "api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly", "annotationProcessor",
        "testImplementation", "testCompileOnly", "testRuntimeOnly", "testAnnotationProcessor",
        "testFixturesApi", "testFixturesImplementation", "testFixturesCompileOnly", "testFixturesRuntimeOnly",
        "kapt", "kaptTest", "ksp", "kspTest", "coreLibraryDesugaring"
    )
    private val platformWrappers = setOf("platform", "enforcedPlatform")
    private data class Token(val text: String, val start: Int, val string: Boolean = false)
    private fun tokens(text: String): List<Token> {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> { i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length }
                text.startsWith("/*", i) -> {
                    var depth = 1; i += 2
                    while (i < text.length && depth > 0) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                text.startsWith("$/", i) -> {
                    i += 2
                    while (i < text.length && !text.startsWith("/$", i)) {
                        i += if (text.startsWith("$$", i) || text.startsWith("$/", i)) 2 else 1
                    }
                    i = (i + 2).coerceAtMost(text.length)
                }
                text[i] == '/' -> {
                    // Conservatively skip Groovy slashy strings; arithmetic may suppress discovery.
                    i++
                    while (i < text.length && text[i] != '/') { if (text[i] == '\\') i++; i++ }
                    i = (i + 1).coerceAtMost(text.length)
                }
                text[i] == '\'' || text[i] == '"' -> {
                    val quote = text[i++]
                    if (text.startsWith("$quote$quote", i)) {
                        i = text.indexOf("$quote$quote$quote", i + 2).takeIf { it >= 0 }?.plus(3) ?: text.length
                        result += Token("", start, true)
                    } else {
                        while (i < text.length && text[i] != quote) { if (text[i] == '\\') i++; i++ }
                        if (i < text.length) result += Token(text.substring(start + 1, i), start + 1, true)
                        i = (i + 1).coerceAtMost(text.length)
                    }
                }
                text[i].isLetterOrDigit() || text[i] == '_' -> { while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++; result += Token(text.substring(start, i), start) }
                else -> { result += Token(text[i].toString(), i); i++ }
            }
        }
        return result
    }

    fun parse(file: String, text: String): List<GradleDeclaration> =
        if (file.endsWith(".toml")) catalog(file, text) else script(file, text)

    private fun script(file: String, text: String): List<GradleDeclaration> {
        val tokens = tokens(text)
        val blocks = mutableListOf<String>()
        val result = mutableListOf<GradleDeclaration>()
        for ((index, token) in tokens.withIndex()) {
            if (!token.string && token.text == "{") blocks += tokens.getOrNull(index - 1)?.text.orEmpty()
            if (!token.string && token.text == "}" && blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
            if (!token.string || "dependencies" !in blocks || "buildscript" in blocks) continue
            val callIndex = if (tokens.getOrNull(index - 1)?.text == "(") index - 2 else index - 1
            val call = tokens.getOrNull(callIndex)?.text
            if (call == null || !Regex("[A-Za-z_][A-Za-z_0-9]*").matches(call) || call in setOf("println", "print", "because", "version", "require", "prefer", "strictly", "reject")) continue
            val outerIndex = if (tokens.getOrNull(callIndex - 1)?.text == "(") callIndex - 2 else callIndex - 1
            val supportedCall = (call in configurations && tokens.getOrNull(callIndex - 1)?.text != ".") ||
                (call in platformWrappers && tokens.getOrNull(outerIndex)?.text in configurations &&
                    tokens.getOrNull(outerIndex - 1)?.text != ".")
            val coordinate = token.text.split(':')
            if (coordinate.size != 3 || coordinate.take(2).any { !Regex("[A-Za-z0-9_.-]+").matches(it) }) continue
            val version = coordinate[2]
            val range = TextRange(token.start + coordinate[0].length + coordinate[1].length + 2, token.start + token.text.length)
            val expression = tokens.getOrNull(index + 1)?.text in setOf("+", ".")
            // Peek past closing call parentheses without copying the remaining token list for
            // every dependency. Quoted tokens remain distinct from punctuation.
            var next = index + 1
            while (next < tokens.size && !tokens[next].string && tokens[next].text == ")") next++
            val customized = tokens.getOrNull(next)?.text == "{"
            val fixed = supportedCall && GradleVersions.fixed(version) && !expression && !customized && "constraints" !in blocks
            val declaration = VersionDeclaration(DeclarationId(file, "dependency@${token.start}"), ArtifactId(coordinate[0], coordinate[1]), version, if (fixed) version else "")
            val reason = when {
                !supportedCall -> "Unknown dependency calls or feature wrappers need manual review"
                !fixed -> "Dynamic, interpolated, composite or customized dependency versions need manual review"
                else -> null
            }
            result += GradleDeclaration(declaration, range, reason)
        }
        return result
    }

    private fun catalog(file: String, text: String): List<GradleDeclaration> {
        val parsed = Toml.parse(text)
        if (parsed.hasErrors()) return emptyList()
        val libraries = parsed.getTable("libraries") ?: return emptyList()
        val versions = parsed.getTable("versions")
        val plugins = parsed.getTable("plugins")
        val pluginRefs = plugins?.keySet()?.mapNotNull { (plugins.get(listOf(it)) as? TomlTable)?.getString("version.ref") }?.toSet().orEmpty()
        fun range(table: TomlTable, path: List<String>, value: String): TextRange? {
            val position = table.inputPositionOf(path) ?: return null
            val start = offset(text, position)
            val equals = text.indexOf('=', start).takeIf { it >= 0 } ?: return null
            val quote = (equals + 1 until text.length).firstOrNull { !text[it].isWhitespace() } ?: return null
            if (text[quote] !in "\"'" || !text.startsWith(value + text[quote], quote + 1) || '\\' in value) return null
            return TextRange(quote + 1, quote + 1 + value.length)
        }
        return libraries.keySet().mapNotNull { alias ->
            val entry = libraries.get(listOf(alias))
            var version: String
            var target: TextRange? = null
            var reason: String? = null
            val module: String
            if (entry is String) {
                val parts = entry.split(':')
                if (parts.size != 3) return@mapNotNull null
                module = parts.take(2).joinToString(":"); version = parts[2]
                target = range(libraries, listOf(alias), entry)?.let { TextRange(it.endOffset - version.length, it.endOffset) }
            } else if (entry is TomlTable) {
                module = entry.getString("module") ?: listOfNotNull(entry.getString("group"), entry.getString("name")).takeIf { it.size == 2 }?.joinToString(":") ?: return@mapNotNull null
                val ref = entry.getString("version.ref")
                if (ref != null) {
                    version = versions?.get(listOf(ref)) as? String ?: ""
                    target = versions?.let { range(it, listOf(ref), version) }
                    if (ref in pluginRefs) reason = "Version reference is also used by a plugin"
                } else {
                    version = entry.get("version") as? String ?: ""
                    target = range(entry, listOf("version"), version)
                }
            } else return@mapNotNull null
            val parts = module.split(':')
            if (parts.size != 2) return@mapNotNull null
            if (!GradleVersions.fixed(version) || target == null) reason = "Rich, missing or dynamic catalog versions need manual review"
            GradleDeclaration(VersionDeclaration(DeclarationId(file, "libraries/$alias"), ArtifactId(parts[0], parts[1]), version, if (reason == null) version else ""), target, reason)
        }
    }

    private fun offset(text: String, position: TomlPosition): Int {
        var start = 0
        repeat(position.line() - 1) { start = text.indexOf('\n', start) + 1 }
        return start + position.column() - 1
    }
}
