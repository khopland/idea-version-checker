package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.util.TextRange
import io.github.khopland.versionchecker.core.*
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import org.tomlj.TomlTable
import java.math.BigInteger

internal data class GradleDeclaration(val declaration: VersionDeclaration, val range: TextRange?, val reason: String? = null)

/** Only fixed, numeric releases can be edited automatically. Gradle orders candidates during resolution. */
internal object GradleVersions {
    const val STABLE = "(?i)^[0-9]+(?:\\.[0-9]+)*(?:[.-](?:final|ga|release|sp[0-9]*|jre|android))?$"
    const val SUFFIX = "(?i)[.-](final|ga|release|sp[0-9]*|jre|android)$"
    private val stable = Regex(STABLE)
    private val suffix = Regex(SUFFIX)
    private val numericPrefix = Regex("^[0-9]+(?:\\.[0-9]+)*")
    private fun numbers(version: String): List<BigInteger> =
        numericPrefix.find(version)?.value?.split('.')?.map(String::toBigInteger).orEmpty()
    private fun qualifier(version: String) = suffix.find(version)?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT).orEmpty()
    private fun channel(version: String) = qualifier(version).takeIf { it == "jre" || it == "android" }.orEmpty()
    fun fixed(version: String) = stable.matches(version)
    fun newer(current: String, latest: String): Boolean {
        if (!fixed(current) || !fixed(latest) || channel(current) != channel(latest)) return false
        val old = numbers(current); val next = numbers(latest)
        for (index in 0 until maxOf(old.size, next.size)) {
            val comparison = (next.getOrNull(index) ?: BigInteger.ZERO).compareTo(old.getOrNull(index) ?: BigInteger.ZERO)
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
        val old = numbers(current); val next = numbers(latest)
        return when {
            (old.getOrNull(0) ?: BigInteger.ZERO) != (next.getOrNull(0) ?: BigInteger.ZERO) -> VersionChangeKind.MAJOR
            (old.getOrNull(1) ?: BigInteger.ZERO) != (next.getOrNull(1) ?: BigInteger.ZERO) -> VersionChangeKind.MINOR
            else -> VersionChangeKind.PATCH
        }
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
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
    private val coordinatePart = Regex("[A-Za-z0-9_.-]+")
    private val platformWrappers = setOf("platform", "enforcedPlatform")
    private data class Token(val text: String, val start: Int, val string: Boolean = false)
    private fun tokens(text: String, groovy: Boolean): List<Token> {
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
                groovy && text[i] == '/' -> {
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
        val tokens = tokens(text, !file.endsWith(".gradle.kts"))
        val blocks = mutableListOf<String>()
        val result = mutableListOf<GradleDeclaration>()
        var mapEnd = -1
        for ((index, token) in tokens.withIndex()) {
            if (!token.string && token.text == "{") blocks += tokens.getOrNull(index - 1)?.text.orEmpty()
            if (!token.string && token.text == "}" && blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
            if ("dependencies" !in blocks || "buildscript" in blocks || token.start < mapEnd) continue
            if (!token.string) {
                if (token.text in configurations) mapDependency(file, text, tokens, index)?.let { map ->
                    result += map
                    mapEnd = map.range!!.endOffset
                }
                continue
            }
            val callIndex = if (tokens.getOrNull(index - 1)?.text == "(") index - 2 else index - 1
            val call = tokens.getOrNull(callIndex)?.text
            if (call == null || !identifier.matches(call) || call in setOf("println", "print", "because", "version", "require", "prefer", "strictly", "reject")) continue
            val outerIndex = if (tokens.getOrNull(callIndex - 1)?.text == "(") callIndex - 2 else callIndex - 1
            val supportedCall = (call in configurations && tokens.getOrNull(callIndex - 1)?.text != ".") ||
                (call in platformWrappers && tokens.getOrNull(outerIndex)?.text in configurations &&
                    tokens.getOrNull(outerIndex - 1)?.text != ".")
            val coordinate = token.text.substringBefore('@').split(':')
            if (coordinate.size !in 3..4 || coordinate.take(2).any { !coordinatePart.matches(it) }) continue
            val version = coordinate[2]
            val extendedNotation = coordinate.size == 4 || '@' in token.text
            val versionStart = token.start + coordinate[0].length + coordinate[1].length + 2
            val range = TextRange(versionStart, versionStart + version.length)
            val expression = tokens.getOrNull(index + 1)?.text in setOf("+", ".")
            // Peek past closing call parentheses without copying the remaining token list for
            // every dependency. Quoted tokens remain distinct from punctuation.
            var next = index + 1
            while (next < tokens.size && !tokens[next].string && tokens[next].text == ")") next++
            val customized = tokens.getOrNull(next)?.text == "{"
            val fixed = supportedCall && !extendedNotation && GradleVersions.fixed(version) && !expression && !customized && "constraints" !in blocks
            val declaration = VersionDeclaration(DeclarationId(file, "dependency@${token.start}"), ArtifactId(coordinate[0], coordinate[1]), version, if (fixed) version else "")
            val reason = when {
                extendedNotation -> "Classifier or extension dependency notation needs manual review"
                !supportedCall -> "Unknown dependency calls or feature wrappers need manual review"
                !fixed -> "Dynamic, interpolated, composite or customized dependency versions need manual review"
                else -> null
            }
            result += GradleDeclaration(declaration, range, reason)
        }
        return result
    }

    /** Map notation is coverage only: it does not establish literal/native edit ownership. */
    private fun mapDependency(file: String, text: String, tokens: List<Token>, callIndex: Int): GradleDeclaration? {
        val call = tokens[callIndex]
        var first = callIndex + 1
        val parenthesized = tokens.getOrNull(first)?.text == "("
        if (parenthesized) first++
        val start = tokens.getOrNull(first) ?: return null
        val keys = setOf("group", "name", "version", "classifier", "ext")
        if (start.text != "mapOf" && start.text != "[" &&
            !(start.text in keys && tokens.getOrNull(first + 1)?.text in setOf(":", "="))) return null
        var end = first
        var depth = if (parenthesized) 1 else 0
        while (end < tokens.size) {
            val token = tokens[end]
            if (!parenthesized && depth == 0 && end > first &&
                (('\n' in text.substring(tokens[end - 1].start, token.start) && tokens[end - 1].text != ",") || token.text in setOf(";", "}"))) break
            if (!token.string) {
                if (token.text in setOf("(", "[")) depth++
                if (token.text in setOf(")", "]")) {
                    depth--
                    if (parenthesized && depth == 0) break
                }
            }
            end++
        }
        val fields = mutableMapOf<String, String>()
        var hasMapKey = false
        for (index in first until end) {
            val key = tokens[index]
            if (key.text !in keys || tokens.getOrNull(index + 1)?.text !in setOf(":", "=", "to")) continue
            hasMapKey = true
            val value = tokens.getOrNull(index + 2) ?: continue
            val next = tokens.getOrNull(index + 3)
            if (value.string && (index + 3 >= end || next?.text in setOf(",", ")", "]"))) fields[key.text] = value.text
        }
        if (!hasMapKey) return null
        val group = fields["group"]?.takeIf(coordinatePart::matches)
        val name = fields["name"]?.takeIf(coordinatePart::matches)
        val artifact = if (group != null && name != null) ArtifactId(group, name) else ArtifactId("gradle", "map@${call.start}")
        val last = tokens.getOrNull(end.coerceAtMost(tokens.lastIndex)) ?: start
        val rangeEnd = if (end < tokens.size && parenthesized && last.text == ")") last.start + 1 else
            tokens.getOrNull(end - 1)?.let { it.start + it.text.length + if (it.string) 1 else 0 } ?: call.start + call.text.length
        return GradleDeclaration(VersionDeclaration(DeclarationId(file, "dependency@${call.start}"), artifact,
            fields["version"].orEmpty(), ""), TextRange(call.start, rangeEnd.coerceAtMost(text.length)),
            "Map dependency notation needs manual review; literal version ownership is not established")
    }

    private fun catalog(file: String, text: String): List<GradleDeclaration> {
        val parsed = Toml.parse(text)
        if (parsed.hasErrors()) return emptyList()
        val result = mutableListOf<GradleDeclaration>()
        // Walk raw values so schema errors do not throw TomlInvalidTypeException. Do not
        // catch parsing/runtime failures: cancellation and unexpected bugs must propagate.
        fun value(table: TomlTable, vararg path: String): Any? {
            var current: Any? = table
            for (key in path) current = (current as? TomlTable)?.get(listOf(key)) ?: return null
            return current
        }
        fun anchor(table: TomlTable, path: List<String>): TextRange? =
            table.inputPositionOf(path)?.let { position ->
                val start = offset(text, position).coerceIn(0, text.length)
                TextRange(start, (text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length).coerceAtLeast(start))
            }
        fun review(table: TomlTable, path: List<String>, location: String, message: String) {
            result += GradleDeclaration(VersionDeclaration(DeclarationId(file, location),
                ArtifactId("catalog", location), "", ""), anchor(table, path), "$message; manual review required")
        }
        fun section(name: String): TomlTable? {
            val raw = parsed.get(listOf(name))
            if (raw != null && raw !is TomlTable) review(parsed, listOf(name), name, "Catalog $name must be a table")
            return raw as? TomlTable
        }
        val libraries = section("libraries")
        val versions = section("versions")
        val plugins = section("plugins")
        fun richVersion(table: TomlTable): Boolean = table.keySet().isNotEmpty() && table.keySet().all { key ->
            val raw = table.get(listOf(key))
            when (key) {
                "require", "strictly", "prefer" -> raw is String
                "rejectAll" -> raw is Boolean
                "reject" -> raw is TomlArray && (0 until raw.size()).all { raw.get(it) is String }
                else -> false
            }
        }
        versions?.keySet()?.forEach { alias ->
            val raw = versions.get(listOf(alias))
            if (raw !is String && (raw !is TomlTable || !richVersion(raw)))
                review(versions, listOf(alias), "versions/$alias", "Catalog version must be a string or rich version table")
        }
        val pluginRefs = mutableSetOf<String>()
        var unknownPluginRefs = parsed.get(listOf("plugins")) != null && plugins == null
        plugins?.keySet()?.forEach { alias ->
            val entry = plugins.get(listOf(alias))
            val version = (entry as? TomlTable)?.get(listOf("version"))
            val ref = (version as? TomlTable)?.get(listOf("ref"))
            if (ref is String && ref.isNotBlank()) pluginRefs += ref
            val valid = when (entry) {
                is String -> entry.split(':').let { it.size == 2 && it.all(String::isNotBlank) }
                is TomlTable -> (value(entry, "id") as? String)?.isNotBlank() == true &&
                    (version is String || version is TomlTable &&
                        (if (ref == null) richVersion(version) else ref is String && ref.isNotBlank() && version.keySet() == setOf("ref")))
                else -> false
            }
            if (!valid) {
                review(plugins, listOf(alias), "plugins/$alias", "Malformed catalog plugin declaration")
                // A malformed reference may still consume a shared version. Without a
                // known reference, no shared library version can be authorized safely.
                if (ref !is String || ref.isBlank()) unknownPluginRefs = true
            }
        }
        val malformedLibraryRefs = mutableSetOf<String>()
        var unknownLibraryRefs = false
        libraries?.keySet()?.forEach { alias ->
            val entry = libraries.get(listOf(alias)) as? TomlTable ?: return@forEach
            val rawVersion = value(entry, "version") as? TomlTable ?: return@forEach
            val ref = rawVersion.get(listOf("ref")) ?: return@forEach
            if (ref !is String || ref.isBlank()) {
                unknownLibraryRefs = true
                return@forEach
            }
            val rawModule = value(entry, "module")
            val group = value(entry, "group")
            val name = value(entry, "name")
            val module = if (rawModule != null) rawModule as? String else
                if (group is String && name is String) "$group:$name" else null
            val parts = module?.split(':')
            if (parts == null || parts.size != 2 || parts.any { !coordinatePart.matches(it) } ||
                (group != null && group !is String) || (name != null && name !is String))
                malformedLibraryRefs += ref
        }
        fun range(table: TomlTable, path: List<String>, value: String): TextRange? {
            val position = table.inputPositionOf(path) ?: return null
            val start = offset(text, position)
            val equals = text.indexOf('=', start).takeIf { it >= 0 } ?: return null
            val quote = (equals + 1 until text.length).firstOrNull { !text[it].isWhitespace() } ?: return null
            if (text[quote] !in "\"'" || !text.startsWith(value + text[quote], quote + 1) || '\\' in value) return null
            return TextRange(quote + 1, quote + 1 + value.length)
        }
        libraries?.keySet()?.forEach { alias ->
            val entry = libraries.get(listOf(alias))
            var version = ""
            var target: TextRange? = null
            var reason: String? = null
            var module: String? = null
            if (entry is String) {
                val parts = entry.split(':')
                if (parts.size == 3) {
                    module = parts.take(2).joinToString(":"); version = parts[2]
                    target = range(libraries, listOf(alias), entry)?.let { TextRange(it.endOffset - version.length, it.endOffset) }
                }
            } else if (entry is TomlTable) {
                val rawModule = value(entry, "module")
                val group = value(entry, "group")
                val name = value(entry, "name")
                module = if (rawModule != null) rawModule as? String else
                    if (group is String && name is String) "$group:$name" else null
                if ((group != null && group !is String) || (name != null && name !is String)) module = null
                val rawVersion = value(entry, "version")
                val rawRef = (rawVersion as? TomlTable)?.get(listOf("ref"))
                if (rawRef is String && rawRef.isNotBlank()) {
                    version = versions?.get(listOf(rawRef)) as? String ?: ""
                    target = versions?.let { range(it, listOf(rawRef), version) }
                    if (rawRef in pluginRefs) reason = "Version reference is also used by a plugin"
                    if ((rawVersion as TomlTable).keySet() != setOf("ref")) reason = "Composite catalog version references need manual review"
                    if (unknownPluginRefs) reason = "Malformed catalog plugins prevent proving shared version ownership"
                    if (rawRef in malformedLibraryRefs || unknownLibraryRefs)
                        reason = "Malformed catalog libraries prevent proving shared version ownership"
                } else {
                    version = rawVersion as? String ?: ""
                    target = range(entry, listOf("version"), version)
                    if (rawRef != null) reason = "Catalog version reference must be a string"
                }
            }
            val parts = module?.split(':')
            if (parts == null || parts.size != 2 || parts.any { !coordinatePart.matches(it) }) {
                review(libraries, listOf(alias), "libraries/$alias", "Malformed catalog library coordinates or declaration")
            } else {
                if (!GradleVersions.fixed(version) || target == null)
                    reason = reason ?: "Rich, missing, malformed or dynamic catalog versions need manual review"
                result += GradleDeclaration(VersionDeclaration(DeclarationId(file, "libraries/$alias"),
                    ArtifactId(parts[0], parts[1]), version, if (reason == null) version else ""),
                    target ?: anchor(libraries, listOf(alias)), reason)
            }
        }
        return result
    }

    private fun offset(text: String, position: TomlPosition): Int {
        var start = 0
        repeat(position.line() - 1) { start = text.indexOf('\n', start) + 1 }
        return start + position.column() - 1
    }
}
