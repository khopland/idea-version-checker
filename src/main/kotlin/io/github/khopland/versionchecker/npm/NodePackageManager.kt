package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.nio.file.Path

internal enum class NodePackageManager(val id: String, val title: String, val markers: Set<String>) {
    NPM("npm", "npm", setOf("package-lock.json", "npm-shrinkwrap.json")),
    PNPM("pnpm", "pnpm", setOf("pnpm-lock.yaml", "pnpm-workspace.yaml")),
    YARN("yarn", "Yarn", setOf("yarn.lock", ".yarnrc.yml", ".yarnrc")),
    BUN("bun", "Bun", setOf("bun.lock", "bun.lockb", "bunfig.toml"));

    companion object {
        val configFiles = setOf(".npmrc", ".yarnrc", ".yarnrc.yml", "bunfig.toml", "pnpm-workspace.yaml",
            ".env", ".env.local", ".env.yarn", ".env.development", ".env.production", ".env.test")
        val markerFiles = entries.flatMap { it.markers }.toSet()
        fun pnpmGlobalConfig(environment: Map<String, String>): List<Path> {
            val userDirectory = Path.of(environment["HOME"] ?: System.getProperty("user.home"))
            return listOf(userDirectory.resolve(".config/pnpm/config.yaml"), userDirectory.resolve(".config/pnpm/rc"),
                userDirectory.resolve("Library/Preferences/pnpm/config.yaml"), userDirectory.resolve("Library/Preferences/pnpm/rc"),
                userDirectory.resolve("AppData/Local/pnpm/config.yaml"), userDirectory.resolve("AppData/Local/pnpm/rc")) +
                listOf("PNPM_CONFIG_DIR", "XDG_CONFIG_HOME").flatMap { name -> environment[name]?.takeIf(String::isNotBlank)?.let {
                    val directory = Path.of(it).let { path -> if (name == "XDG_CONFIG_HOME") path.resolve("pnpm") else path }
                    listOf(directory.resolve("config.yaml"), directory.resolve("rc"))
                }.orEmpty() }
        }

        /** Explicit declarations win over old lockfiles; conflicts and unknown managers are excluded. */
        fun detect(project: Project, file: VirtualFile, root: VirtualFile): NodePackageManager? {
            val declared = mutableSetOf<NodePackageManager>()
            for (manifestFile in listOfNotNull(root.findChild("package.json"), file).distinct()) {
                val manifest = PsiManager.getInstance(project).findFile(manifestFile)?.let { NpmManifest.ownershipRoot(it, currentDocument = true) } ?: return null
                val field = manifest.findProperty("packageManager")?.value
                if (field != null) {
                    val text = (field as? JsonStringLiteral)?.value ?: return null
                    if (text.substringAfter('@', "").isBlank() || text.any(Char::isWhitespace)) return null
                    declared += entries.singleOrNull { text.startsWith("${it.id}@") } ?: return null
                }
                val dev = (manifest.findProperty("devEngines")?.value as? JsonObject)?.findProperty("packageManager")?.value
                if (dev != null) {
                    val managers = when (dev) {
                        is JsonObject -> listOf(dev)
                        is JsonArray -> dev.valueList
                        else -> return null
                    }
                    if (managers.isEmpty()) return null
                    for (value in managers) {
                        val name = ((value as? JsonObject)?.findProperty("name")?.value as? JsonStringLiteral)?.value
                        declared += entries.singleOrNull { it.id == name } ?: return null
                    }
                }
            }
            if (declared.isNotEmpty()) return declared.singleOrNull()
            val marked = entries.filter { manager -> manager.markers.any { root.findChild(it)?.isDirectory == false || file.parent.findChild(it)?.isDirectory == false } }
            return if (marked.isEmpty()) NPM else marked.singleOrNull()
        }
    }
}

internal class PnpmBuildSystemAdapter : NpmBuildSystemAdapter(manager = NodePackageManager.PNPM)
internal class YarnBuildSystemAdapter : NpmBuildSystemAdapter(manager = NodePackageManager.YARN)
internal class BunBuildSystemAdapter : NpmBuildSystemAdapter(manager = NodePackageManager.BUN)
