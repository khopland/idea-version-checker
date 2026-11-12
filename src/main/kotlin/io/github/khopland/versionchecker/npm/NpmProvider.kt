package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.core.ApplicationHint
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.Install
import io.github.khopland.versionchecker.core.PinFix
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.Transitive
import io.github.khopland.versionchecker.core.UpdateKind
import io.github.khopland.versionchecker.core.Versions

/**
 * `package.json`: `dependencies`, `devDependencies` and `optionalDependencies`, for any package manager, and also
 * - `packageManager`, the package manager version Corepack installs,
 * - `overrides` (npm), `resolutions` (Yarn) and `pnpm.overrides`, which pin packages deeper in the tree,
 * - Bun's `catalog` and `catalogs`, at the top level or in `workspaces`.
 */
class NpmProvider : DeclarationProvider {
    override val fileNames = listOf("package.json")

    override fun accepts(file: PsiFile) = file is JsonFile && file.name == "package.json" && "/node_modules/" !in file.virtualFile?.path.orEmpty()

    /** The registry settings, which also come from the user's `.npmrc` or `.yarnrc.yml`. */
    override fun stamp(file: PsiFile): Any? = file.virtualFile?.let(NpmPackages::registries)

    /**
     * The Nexus IQ application named after this package or a package.json above it in the project, nearest first, such
     * as the root of a workspace. A scoped name is also tried without its scope.
     */
    override fun application(file: PsiFile): ApplicationHint? {
        val vfile = file.virtualFile ?: return null
        val index = ProjectFileIndex.getInstance(file.project)
        val psi = PsiManager.getInstance(file.project)
        val names = generateSequence(vfile.parent) { it.parent }.takeWhile { index.isInContent(it) }
            .mapNotNull { it.findChild("package.json")?.let(psi::findFile) as? JsonFile }
            .mapNotNull { ((it.topLevelValue as? JsonObject)?.findProperty("name")?.value as? JsonStringLiteral)?.value?.takeIf(String::isNotBlank) }
            .flatMap { listOf(it, it.substringAfter('/')) }
        return ApplicationHint(names = names.distinct().toList())
    }

    override fun collect(file: PsiFile): List<Declaration> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        val registries by lazy { NpmPackages.registries(vfile) }
        val lockfile by lazy { Lockfile.find(vfile) }
        val install by lazy { Install(nodeVersion(file), NpmRange::satisfies) }
        val result = mutableListOf<Declaration>()
        // Overrides pin packages deeper in the tree, which the vulnerability check leaves out.
        // A [locked] dependency is checked for vulnerabilities at the version the lockfile installs, and its updates for
        // conflicts with the other dependencies and with Node.js.
        fun add(name: String, literal: JsonStringLiteral, label: String = name, ecosystem: Ecosystem? = Ecosystem.NPM, locked: Boolean = false) {
            val spec = NpmSpec.parse(literal.value) ?: return
            val raw = literal.value
            val range = TextRange.from(literal.textRange.startOffset + 1, raw.length).takeIf { literal.text.length == raw.length + 2 } ?: return
            val installed = if (locked) lockfile?.let { (lock, workspace) -> lock.direct(workspace, name, raw) }?.takeIf { it.name == name }?.version else null
            result += Declaration(label, range, spec.version, NpmLookup(name, registries.registry(name)), Edit(vfile, range, raw) { spec.prefix + it },
                ecosystem = ecosystem, installed = installed, install = if (locked) install else null, floats = spec.floats)
        }
        fun section(value: JsonValue?) = (value as? JsonObject)?.propertyList.orEmpty()
        fun dependencies(value: JsonValue?, locked: Boolean = false) =
            section(value).forEach { property -> (property.value as? JsonStringLiteral)?.let { add(property.name, it, locked = locked) } }

        SECTIONS.forEach { dependencies(root.findProperty(it)?.value, locked = true) }
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
     * The packages that `package-lock.json`, `pnpm-lock.yaml`, `yarn.lock` or `bun.lock` installs below each declared dependency, breadth first,
     * so each package gets its shortest path. Workspace packages are linked, not installed from the registry, so they are left out.
     * The walk stops at a package this file declares: the vulnerable dependency inspection checks it, and its own
     * dependencies show under it.
     */
    override fun transitive(file: PsiFile): List<Transitive> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        val located = Lockfile.locate(vfile) ?: return emptyList()
        val lock = located.lockfile
        val workspace = located.workspace
        val registries = NpmPackages.registries(vfile)
        // The walk only changes when package.json, the lockfile or the registry settings do, and the annotator repeats it for every pass.
        val stamp = WalkStamp(file.modificationStamp, located.file, located.file.modificationStamp, registries)
        file.getUserData(TRANSITIVE)?.takeIf { it.first == stamp }?.let { return it.second }
        // Many declared dependencies bring in the same package, so each name is looked up once.
        val lookups = HashMap<String, NpmLookup>()
        val declared = SECTIONS.flatMap { (root.findProperty(it)?.value as? JsonObject)?.propertyList.orEmpty() }.distinctBy { it.name }
        // By name and version: pnpm tells copies with other peers apart.
        fun direct(property: JsonProperty) = lock.direct(workspace, property.name, (property.value as? JsonStringLiteral)?.value)
        val direct = declared.mapNotNullTo(hashSetOf()) { property -> direct(property)?.let { it.name to it.version } }
        val result = declared.flatMap { property ->
            val key = property.nameElement
            val range = TextRange(key.textRange.startOffset + 1, key.textRange.endOffset - 1)
            val top = direct(property) ?: return@flatMap emptyList()
            val seen = hashSetOf(top.id)
            val queue = ArrayDeque(listOf(top to emptyList<String>()))
            buildList {
                while (queue.isNotEmpty()) {
                    ProgressManager.checkCanceled()
                    val (node, path) = queue.removeFirst()
                    for (child in lock.dependencies(node)) {
                        if (!seen.add(child.id) || (child.name to child.version) in direct) continue
                        add(Transitive(property.name, range, Pkg(Ecosystem.NPM, child.name, child.version), path,
                            lookups.getOrPut(child.name) { NpmLookup(child.name, registries.registry(child.name)) }))
                        queue += child to path + child.name
                    }
                }
            }.distinctBy { it.pkg }
        }
        file.putUserData(TRANSITIVE, stamp to result)
        return result
    }

    /**
     * An override in the root of the workspace, scoped to the vulnerable version's major, so other copies keep theirs:
     * npm `overrides`, or for pnpm see [pnpmOverridesInWorkspaceFile]. Yarn `resolutions` and Bun `overrides` name a package
     * but no range, so they move every copy, and get the fix only while all installed copies are in that major.
     * Not for a package that this file depends on directly, which npm refuses to override, nor for one that is already
     * overridden where the package manager reads it.
     */
    override fun pin(file: PsiFile, transitive: Transitive, version: String): PinFix? {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return null
        val vfile = file.virtualFile ?: return null
        val (lock, workspace) = Lockfile.find(vfile) ?: return null
        val name = transitive.pkg.name
        if (workspace.isNotEmpty() || (SECTIONS + "peerDependencies").any { (root.findProperty(it)?.value as? JsonObject)?.findProperty(name) != null }) return null
        val keys = when (lock) {
            is PackageLock -> listOf("overrides")
            is PnpmLock -> listOf("pnpm", "overrides")
            is YarnLock -> listOf("resolutions")
            is BunLock -> listOf("overrides")
            else -> return null
        }
        val scoped = NpmOverrides.key(name, transitive.pkg.version, version) ?: return null
        // The installed copies, for a package manager that overrides by name.
        val copies = (lock as? YarnLock)?.versions(name) ?: (lock as? BunLock)?.versions(name)
        if (copies != null && copies.any { NpmOverrides.key(name, it, version) == null }) return null
        // Bun reads `resolutions` too.
        if (lock is BunLock && !NpmOverrides.canAdd(root, listOf("resolutions"), name, name)) return null
        val key = if (copies != null) name else scoped
        if (lock is PnpmLock) {
            val workspaceText = vfile.parent?.findChild(PnpmWorkspace.FILE_NAME)?.let { PnpmWorkspace.text(it) ?: return null }
            // pnpm reads overrides from one of the files, so only an override in the one the fix writes to counts.
            // pnpm reads root `resolutions` like `pnpm.overrides`; either one, when not empty, hides the workspace file's.
            val inPackageJson = listOf(keys, listOf("resolutions")).any { NpmOverrides.find(root, it)?.propertyList?.isNotEmpty() == true }
            if (pnpmOverridesInWorkspaceFile(root, vfile, inPackageJson, workspaceText))
                return if (workspaceText == null || PnpmWorkspace.canAddOverride(workspaceText, name, key)) PnpmWorkspaceOverrideFix(name, key, version) else null
        }
        return if (NpmOverrides.canAdd(root, keys, name, key)) NpmOverrideFix(keys.toTypedArray(), name, key, version) else null
    }

    /**
     * Whether the pnpm override goes to `pnpm-workspace.yaml` rather than to `pnpm.overrides` in `package.json`. The pnpm
     * version comes from `packageManager`, or else from the last install.
     * - pnpm 11 and later read only the workspace file.
     * - pnpm before 10.5.1 read only `package.json`.
     * - Between those, pnpm reads both, but ignores the workspace file's while `package.json` has overrides. So the new one
     *   goes where the project already keeps them, and without any, to `package.json`.
     * - With an unknown version the same, which never breaks an install: a workspace file without `packages` makes pnpm 9
     *   fail, while pnpm 11 only warns that it ignores `package.json`.
     */
    private fun pnpmOverridesInWorkspaceFile(root: JsonObject, packageJson: VirtualFile, inPackageJson: Boolean, workspaceText: String?): Boolean {
        val declared = (root.findProperty("packageManager")?.value as? JsonStringLiteral)?.value?.let { pnpmVersion.find(it) }
        val version = declared?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
            ?: packageJson.parent?.findChild("node_modules")?.findChild(MODULES_FILE)?.let(::installedPnpm)
        return when {
            version != null && Versions.compare(version, "11.0.0") >= 0 -> true
            version != null && Versions.compare(version, "10.5.1") < 0 -> false
            inPackageJson -> false
            else -> workspaceText?.let(PnpmWorkspace::hasOverrides) == true
        }
    }

    /**
     * The Node.js version in the nearest `.nvmrc`, `.node-version` or `.tool-versions`, beside [file] or above it in the
     * project. It may lack the minor or patch number. Aliases such as `lts/iron` say nothing.
     */
    private fun nodeVersion(file: PsiFile): String? {
        val index = ProjectFileIndex.getInstance(file.project)
        return generateSequence(file.virtualFile?.parent) { it.parent }.takeWhile { index.isInContent(it) }.firstNotNullOfOrNull { directory ->
            NODE_FILES.firstNotNullOfOrNull { (name, pattern) ->
                directory.findChild(name)?.takeIf { !it.isDirectory }?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }
                    ?.let { pattern.find(it) }?.groupValues?.get(1)
            }
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
        private val nodeFile = Regex("""\A\s*v?(\d+(?:\.\d+){0,2})\s*\z""")
        private val NODE_FILES = listOf(".nvmrc" to nodeFile, ".node-version" to nodeFile,
            ".tool-versions" to Regex("""(?m)^[ \t]*(?:nodejs|node)[ \t]+v?(\d+(?:\.\d+){0,2})(?=\s|$)"""))
        private val pnpmVersion = Regex("""^pnpm@(\d+)\.(\d+)\.(\d+)""")
        private val installedPnpmVersion = Regex("""packageManager"?\s*:\s*"?pnpm@(\d+)\.(\d+)\.(\d+)""")
        private val INSTALLED_PNPM = Key.create<Pair<Long, String?>>("version-checker.installed-pnpm")
        private val TRANSITIVE = Key.create<Pair<WalkStamp, List<Transitive>>>("version-checker.transitive")

        /**
         * What a transitive walk was made from: the PSI of package.json, whose offsets the result has, the lockfile, and
         * the registries that the lookups of the packages go to.
         */
        private data class WalkStamp(val psi: Long, val lockfile: VirtualFile, val lock: Long, val registries: RegistryConfig)

        /** The file where pnpm records the version it installed with, as YAML before pnpm 11 and as JSON since. */
        const val MODULES_FILE = ".modules.yaml"

        /** The pnpm version in [modules], read once per change of the file, since every vulnerable package asks. */
        private fun installedPnpm(modules: VirtualFile): String? {
            modules.getUserData(INSTALLED_PNPM)?.takeIf { it.first == modules.modificationStamp }?.let { return it.second }
            val version = runCatching { VfsUtilCore.loadText(modules) }.getOrNull()?.let { installedPnpmVersion.find(it) }
                ?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
            modules.putUserData(INSTALLED_PNPM, modules.modificationStamp to version)
            return version
        }
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
    /** The updates the selector takes by itself. A caret stops at the first number that is not zero. */
    val floats: UpdateKind? get() {
        val (major, minor) = version.split('.')
        return when {
            prefix == "^" && major != "0" -> UpdateKind.MINOR
            prefix == "~" || prefix == "^" && minor != "0" -> UpdateKind.PATCH
            else -> null
        }
    }

    companion object {
        private val pattern = Regex("""^(\^|~|=|v)?(\d+\.\d+\.\d+)$""")

        fun parse(text: String): NpmSpec? = pattern.matchEntire(text.trim())?.let { NpmSpec(it.groupValues[1], it.groupValues[2]) }
            ?.takeIf { text == text.trim() }
    }
}
