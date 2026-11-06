package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.PinFix
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.Transitive

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
        // Overrides pin packages deeper in the tree, which the vulnerability check leaves out.
        fun add(name: String, literal: JsonStringLiteral, label: String = name, ecosystem: Ecosystem? = Ecosystem.NPM) {
            val spec = NpmSpec.parse(literal.value) ?: return
            val raw = literal.value
            val range = TextRange.from(literal.textRange.startOffset + 1, raw.length).takeIf { literal.text.length == raw.length + 2 } ?: return
            result += Declaration(label, range, spec.version, NpmLookup(name, registries.registry(name)), Edit(vfile, range, raw) { spec.prefix + it },
                ecosystem = ecosystem)
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
                    is JsonStringLiteral -> name?.let { add(it, child, "$it (override)", null) }
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

    /**
     * The packages that `package-lock.json` or `pnpm-lock.yaml` installs below each declared dependency, breadth first,
     * so each package gets its shortest path. Workspace packages are linked, not installed from the registry, so they are left out.
     * The walk stops at a package this file declares: the vulnerable dependency inspection checks it, and its own
     * dependencies show under it.
     */
    override fun transitive(file: PsiFile): List<Transitive> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        val (lock, workspace) = Lockfile.find(vfile) ?: return emptyList()
        val registries by lazy { NpmPackages.registries(vfile) }
        val declared = SECTIONS.flatMap { (root.findProperty(it)?.value as? JsonObject)?.propertyList.orEmpty() }.distinctBy { it.name }
        // By name and version: pnpm tells copies with other peers apart.
        val direct = declared.mapNotNullTo(hashSetOf()) { property -> lock.direct(workspace, property.name)?.let { it.name to it.version } }
        return declared.flatMap { property ->
            val key = property.nameElement
            val range = TextRange(key.textRange.startOffset + 1, key.textRange.endOffset - 1)
            val top = lock.direct(workspace, property.name) ?: return@flatMap emptyList()
            val seen = hashSetOf(top.id)
            val queue = ArrayDeque(listOf(top to emptyList<String>()))
            buildList {
                while (queue.isNotEmpty()) {
                    ProgressManager.checkCanceled()
                    val (node, path) = queue.removeFirst()
                    for (child in lock.dependencies(node)) {
                        if (!seen.add(child.id) || (child.name to child.version) in direct) continue
                        add(Transitive(property.name, range, Pkg(Ecosystem.NPM, child.name, child.version), path, NpmLookup(child.name, registries.registry(child.name))))
                        queue += child to path + child.name
                    }
                }
            }.distinctBy { it.pkg }
        }
    }

    /**
     * An override in the root of the workspace, scoped to the vulnerable version's major, so other copies keep theirs:
     * npm `overrides`, or for pnpm see [pnpmOverridesInWorkspaceFile]. Not for a package that this file depends on directly,
     * which npm refuses to override, nor for one that is already overridden, in either file for pnpm.
     */
    override fun pin(file: PsiFile, transitive: Transitive, version: String): PinFix? {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return null
        val vfile = file.virtualFile ?: return null
        val (lock, workspace) = Lockfile.find(vfile) ?: return null
        val name = transitive.pkg.name
        if (workspace.isNotEmpty() || (SECTIONS + "peerDependencies").any { (root.findProperty(it)?.value as? JsonObject)?.findProperty(name) != null }) return null
        val key = NpmOverrides.key(name, transitive.pkg.version, version) ?: return null
        val keys = if (lock is PackageLock) listOf("overrides") else listOf("pnpm", "overrides")
        if (!NpmOverrides.canAdd(root, keys, name, key)) return null
        if (lock is PnpmLock) {
            val workspaceText = vfile.parent?.findChild(PnpmWorkspace.FILE_NAME)?.let { PnpmWorkspace.text(it) ?: return null }
            if (workspaceText != null && !PnpmWorkspace.canAddOverride(workspaceText, name, key)) return null
            if (pnpmOverridesInWorkspaceFile(root, vfile, NpmOverrides.find(root, keys) != null, workspaceText)) return PnpmWorkspaceOverrideFix(name, key, version)
        }
        return NpmOverrideFix(keys.toTypedArray(), name, key, version)
    }

    /**
     * Whether the pnpm override goes to `pnpm-workspace.yaml` rather than to `pnpm.overrides` in `package.json`:
     * - pnpm 11 and later read only the workspace file. The version comes from `packageManager`, or else from the last install.
     * - Older pnpm read `package.json`, and while it has overrides, pnpm 10 ignores the workspace file's. So overrides
     *   already in `package.json` keep the new one there, and overrides already in the workspace file keep it there.
     * - Otherwise `package.json`: a workspace file without `packages` makes pnpm 9 and early pnpm 10 fail to install,
     *   while pnpm 11 only warns that it ignores `package.json`.
     */
    private fun pnpmOverridesInWorkspaceFile(root: JsonObject, packageJson: VirtualFile, inPackageJson: Boolean, workspaceText: String?): Boolean {
        val declared = (root.findProperty("packageManager")?.value as? JsonStringLiteral)?.value?.let { pnpmMajor.find(it) }
        // pnpm writes the version it installed with to node_modules/.modules.yaml, as YAML before pnpm 11 and as JSON since.
        val installed = declared ?: packageJson.parent?.findChild("node_modules")?.findChild(".modules.yaml")
            ?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }?.let { installedPnpm.find(it) }
        val major = (declared ?: installed)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            major != null && major >= 11 -> true
            inPackageJson -> false
            else -> workspaceText?.let(PnpmWorkspace::hasOverrides) == true
        }
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
        private val pnpmMajor = Regex("""^pnpm@(\d+)\.""")
        private val installedPnpm = Regex("""packageManager"?\s*:\s*"?pnpm@(\d+)\.""")
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
