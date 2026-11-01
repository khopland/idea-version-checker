package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Edit

/** `dependencies`, `devDependencies` and `optionalDependencies` in `package.json`, for any package manager. */
class NpmProvider : DeclarationProvider {
    override val fileNames = listOf("package.json")

    override fun accepts(file: PsiFile) = file is JsonFile && file.name == "package.json" && "/node_modules/" !in file.virtualFile?.path.orEmpty()

    override fun collect(file: PsiFile): List<Declaration> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        val npmrc by lazy { RegistryConfig.forDirectory(vfile.parent?.let { it.fileSystem.getNioPath(it) } ?: java.nio.file.Paths.get(System.getProperty("user.home"))) }
        return SECTIONS.flatMap { section ->
            (root.findProperty(section)?.value as? JsonObject)?.propertyList.orEmpty().mapNotNull { property ->
                val literal = property.value as? JsonStringLiteral ?: return@mapNotNull null
                val spec = NpmSpec.parse(literal.value) ?: return@mapNotNull null
                val raw = literal.value
                val range = TextRange.from(literal.textRange.startOffset + 1, raw.length).takeIf { literal.text.length == raw.length + 2 } ?: return@mapNotNull null
                val lookup = NpmLookup(property.name, npmrc.registry(property.name))
                Declaration(property.name, range, spec.version, lookup, Edit(vfile, range, raw) { spec.prefix + it })
            }
        }
    }

    companion object {
        private val SECTIONS = listOf("dependencies", "devDependencies", "optionalDependencies")
    }
}

/** A version selector the plugin can update: exact, `^` or `~`, with a full `major.minor.patch` version. */
internal data class NpmSpec(val prefix: String, val version: String) {
    companion object {
        private val pattern = Regex("""^(\^|~|=|v)?(\d+\.\d+\.\d+)$""")

        fun parse(text: String): NpmSpec? = pattern.matchEntire(text.trim())?.let { NpmSpec(it.groupValues[1], it.groupValues[2]) }
            ?.takeIf { text == text.trim() }
    }
}
