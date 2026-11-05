package io.github.khopland.versionchecker.core

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** A package without versions in [VERSIONS] fails to check. */
internal class FakeLookup(override val key: String, private val versions: List<String>, private val deprecated: Map<String, String> = emptyMap()) : Lookup {
    override suspend fun fetch() = if (versions.isEmpty()) throw HttpFailure("repo needs credentials") else Releases(versions, deprecated, moved = MOVED[key])
}

/** `<lib name="x" version="1.0"/>`: each version attribute is a declaration. Names that start with "shared" share one edit. */
internal class FakeProvider : DeclarationProvider {
    override val fileNames = listOf("fake.xml")
    override fun accepts(file: PsiFile) = file.name == "fake.xml"
    override fun collect(file: PsiFile): List<Declaration> {
        val text = file.text
        val shared = Regex("""<shared>([^<]+)</shared>""").find(text)
        return Regex("""<lib name="([^"]+)" version="([^"]+)"/>""").findAll(text).map { match ->
            val name = match.groupValues[1]
            val group = match.groups[2]!!
            val range = TextRange(group.range.first, group.range.last + 1)
            val edit = if (name.startsWith("shared") && shared != null)
                Edit(file.virtualFile, TextRange(shared.groups[1]!!.range.first, shared.groups[1]!!.range.last + 1), shared.groupValues[1], "shared")
            else Edit(file.virtualFile, range, group.value)
            Declaration(name, range, group.value, FakeLookup(name, VERSIONS[name].orEmpty(), mapOf("1.0.0" to "Use other")), edit)
        }.toList()
    }
}

private val VERSIONS = mapOf(
    "a" to listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0", "2.0.0", "3.0.0-beta"),
    "current" to listOf("1.0.0", "2.0.0"),
    "shared-x" to listOf("1.0.0", "1.0.1", "1.2.0", "2.0.0"),
    "shared-y" to listOf("1.0.0", "1.0.1", "1.1.0"),
    "old" to listOf("1.0.1", "1.1.0"),
    "old-current" to listOf("1.0.0", "1.1.0"))
private val MOVED = mapOf("old" to Moved("org.new:old", "2.0.0"), "old-current" to Moved("org.new:old", message = "Renamed"))
