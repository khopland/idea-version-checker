package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Edit

/**
 * `package.json`: `dependencies`, `devDependencies` and `optionalDependencies`, for any package manager, and also
 * - `packageManager`, the package manager version Corepack installs,
 * - `overrides` (npm), `resolutions` (Yarn) and `pnpm.overrides`, which pin packages deeper in the tree,
 * - Bun's `catalog` and `catalogs`, at the top level or in `workspaces`.
 */
class NpmProvider : DeclarationProvider {
    override val fileNames = listOf("package.json")

    override fun accepts(file: PsiFile) = file is JsonFile && file.name == "package.json" && "/node_modules/" !in file.virtualFile?.path.orEmpty()

    override fun collect(file: PsiFile): List<Declaration> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        val registries by lazy { NpmPackages.registries(vfile) }
        val result = mutableListOf<Declaration>()
        fun add(name: String, literal: JsonStringLiteral, label: String = name) {
            val spec = NpmSpec.parse(literal.value) ?: return
            val raw = literal.value
            val range = TextRange.from(literal.textRange.startOffset + 1, raw.length).takeIf { literal.text.length == raw.length + 2 } ?: return
            result += Declaration(label, range, spec.version, NpmLookup(name, registries.registry(name)), Edit(vfile, range, raw) { spec.prefix + it })
        }
        fun section(value: JsonValue?) = (value as? JsonObject)?.propertyList.orEmpty()
        fun dependencies(value: JsonValue?) = section(value).forEach { property -> (property.value as? JsonStringLiteral)?.let { add(property.name, it) } }

        SECTIONS.forEach { dependencies(root.findProperty(it)?.value) }
        (root.findProperty("packageManager")?.value as? JsonStringLiteral)?.let { packageManager(it, vfile, registries) }?.let { result += it }

        // npm overrides nest: `"foo": {".": "1.0.0", "bar": "2.0.0"}` pins foo and the bar under foo.
        fun overrides(value: JsonValue?, parent: String?) {
            for (property in section(value)) {
                val name = if (property.name == ".") parent else NpmPackages.target(property.name)
                when (val child = property.value) {
                    is JsonStringLiteral -> name?.let { add(it, child, "$it (override)") }
                    is JsonObject -> overrides(child, name)
                    else -> {}
                }
            }
        }
        overrides(root.findProperty("overrides")?.value, null)
        overrides(root.findProperty("resolutions")?.value, null)
        overrides((root.findProperty("pnpm")?.value as? JsonObject)?.findProperty("overrides")?.value, null)

        val workspaces = root.findProperty("workspaces")?.value as? JsonObject
        for (owner in listOfNotNull(root, workspaces)) {
            dependencies(owner.findProperty("catalog")?.value)
            section(owner.findProperty("catalogs")?.value).forEach { dependencies(it.value) }
        }
        return result.sortedBy { it.range.startOffset }
    }

    /** `"pnpm@9.12.0"`. A `+sha512.…` hash must change with the version, so the plugin only warns. */
    private fun packageManager(literal: JsonStringLiteral, file: VirtualFile, registries: RegistryConfig): Declaration? {
        val match = packageManager.matchEntire(literal.value) ?: return null
        if (literal.text.length != literal.value.length + 2) return null
        val (manager, version, hash) = match.destructured
        val group = match.groups[2]!!
        val range = TextRange.from(literal.textRange.startOffset + 1 + group.range.first, version.length)
        // Corepack installs Yarn 2 and later from @yarnpkg/cli-dist; Yarn 1 is the yarn package.
        val pkg = if (manager == "yarn" && (version.substringBefore('.').toIntOrNull() ?: 1) >= 2) "@yarnpkg/cli-dist" else manager
        val edit = if (hash.isEmpty()) Edit(file, range, version, "packageManager") else null
        val note = if (hash.isEmpty()) null else "The hash must match the version: run corepack use $manager@<version>"
        return Declaration("packageManager $manager", range, version, NpmLookup(pkg, registries.registry(pkg)), edit, note)
    }

    companion object {
        private val SECTIONS = listOf("dependencies", "devDependencies", "optionalDependencies")
        private val packageManager = Regex("""^(npm|pnpm|yarn|bun)@(\d+\.\d+\.\d+)(\+.*)?$""")
    }
}

/** Shared by `package.json` and `pnpm-workspace.yaml`. */
internal object NpmPackages {
    private val name = Regex("""^(?:@[a-z0-9][\w.\-~]*/)?[a-z0-9][\w.\-~]*$""", RegexOption.IGNORE_CASE)

    fun registries(file: VirtualFile): RegistryConfig =
        RegistryConfig.forDirectory(file.parent?.let { it.fileSystem.getNioPath(it) } ?: java.nio.file.Paths.get(System.getProperty("user.home")))

    /**
     * The package an override or resolution pins: `foo`, `foo@^1.0.0`, `parent>foo` (pnpm), and Yarn paths such
     * as `parent/foo`, with scoped names kept whole.
     */
    fun target(selector: String): String? {
        val last = selector.substringAfterLast('>')
        val parts = last.split('/')
        var pkg = if (parts.size >= 2 && parts[parts.size - 2].startsWith("@")) parts.takeLast(2).joinToString("/") else parts.last()
        val at = pkg.indexOf('@', 1)
        if (at > 0) pkg = pkg.substring(0, at)
        return pkg.takeIf { name.matches(it) }
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
