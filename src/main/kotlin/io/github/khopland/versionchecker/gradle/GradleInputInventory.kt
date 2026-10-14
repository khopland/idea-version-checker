package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** The directory description is also the cache key: unsaved include changes rebuild it. */
internal data class GradleInputDirectories(val paths: List<String>, val buildLogic: Set<String>)

/** One selection policy for saved inputs, editor state and fresh physical validation. */
internal class GradleInputInventory private constructor(
    val files: List<VirtualFile>,
    private val roots: List<Root>,
    private val buildLogic: Set<Path>
) {
    private data class Root(val file: VirtualFile?, val physicalPath: Path)

    fun resolutionInputPaths(): Set<String> = files.mapTo(mutableSetOf()) { it.path }

    fun hasUnsavedDocuments(): Boolean = files.any { FileDocumentManager.getInstance().isFileModified(it) }

    /** Runs on the caller's IO dispatcher, never inside the final edit/read action. */
    fun diskMatches(saved: Map<VirtualFile, String>, checkCancellation: () -> Unit): Boolean {
        val knownPaths = saved.keys.mapTo(mutableSetOf()) { Path.of(it.canonicalPath ?: it.path).normalize() }
        try {
            val physicalLogic = buildLogic.mapTo(mutableSetOf()) { path ->
                checkCancellation()
                if (Files.exists(path)) path.toRealPath() else path
            }
            val visited = mutableSetOf<Path>()
            for (root in roots) {
                checkCancellation()
                if (!Files.isDirectory(root.physicalPath)) continue
                val base = root.physicalPath.toRealPath()
                if (!visited.add(base)) continue
                var current = true
                Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCancellation()
                        return if (dir != base && dir.fileName.toString() in excludedDirectories)
                            FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCancellation()
                        if (tracked(base, path, physicalLogic) && path.toRealPath() !in knownPaths) {
                            current = false
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
                if (!current) return false
            }
            return saved.all { (file, expected) ->
                checkCancellation()
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(Path.of(file.path)).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkCancellation()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                HexFormat.of().formatHex(digest.digest()) == expected
            }
        } catch (_: IOException) {
            // Missing/unreadable inputs cannot authorize an edit. Cancellation is not swallowed.
            return false
        }
    }

    companion object {
        val excludedDirectories = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules", "vendor")

        fun discover(directories: GradleInputDirectories, find: (String) -> VirtualFile?): GradleInputInventory {
            // Preserve the linked root's namespace, but never walk its canonical alias twice.
            // Keep physical directories even if VFS has not discovered them yet.
            val candidates = directories.paths.map { path ->
                val file = find(path)
                Root(file, Path.of(file?.canonicalPath ?: path).normalize())
            }.distinctBy { it.physicalPath }
            // An explicitly imported root below a generated boundary must be walked separately.
            val roots = candidates.filter { candidate -> candidates.none { parent ->
                parent != candidate && candidate.physicalPath.startsWith(parent.physicalPath) &&
                    parent.physicalPath.relativize(candidate.physicalPath).none { it.toString() in excludedDirectories }
            } }
            val logic = directories.buildLogic.mapTo(mutableSetOf()) { path ->
                Path.of(find(path)?.canonicalPath ?: path).normalize()
            }
            val editorLogic = directories.buildLogic.mapTo(mutableSetOf()) { Path.of(it).normalize() }
            val files = mutableSetOf<VirtualFile>()
            for (root in roots) {
                val editorRoot = root.file ?: continue
                val editorBase = Path.of(editorRoot.path).normalize()
                VfsUtilCore.iterateChildrenRecursively(editorRoot, { it == editorRoot || it.name !in excludedDirectories }) { file ->
                    if (!file.isDirectory && (tracked(editorBase, Path.of(file.path).normalize(), editorLogic) ||
                            tracked(root.physicalPath, Path.of(file.canonicalPath ?: file.path).normalize(), logic))) files += file
                    true
                }
            }
            return GradleInputInventory(files.distinctBy { it.canonicalPath ?: it.path }, roots, logic)
        }

        private fun tracked(base: Path, path: Path, logic: Set<Path>): Boolean {
            // The name check also captures a newly created buildSrc before any VFS refresh.
            val buildLogic = logic.any { path.startsWith(it) } ||
                path.parent?.let { parent -> parent.startsWith(base) &&
                    (base.fileName?.toString() == "buildSrc" || base.relativize(parent).any { it.toString() == "buildSrc" }) } == true
            val name = path.fileName.toString()
            return buildLogic || name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".toml") ||
                name in setOf("gradle.properties", "gradle-wrapper.properties", "gradle-wrapper.jar", "gradlew", "gradlew.bat", "gradle.lockfile", "verification-metadata.xml") ||
                path.toString().contains("/gradle/dependency-locks/")
        }
    }
}
