package io.github.khopland.versionchecker

import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * The IDE that the plugin compiles against, from the Gradle cache, so Starter does not download another one.
 * On macOS the cache holds the bundle's `Contents` folder. The launcher needs a real `.app` around it,
 * so it is cloned once into `build/ide-app` (an APFS clone, which uses almost no disk space).
 */
object LocalIde {
    val info: IdeInfo = IdeProductProvider.IU.copy(getInstaller = { Installer })

    private object Installer : IdeInstaller {
        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
            val ide = DefaultIdeDistributionFactory.installIDE(home().toFile(), ideInfo.executableFileName)
            return ide.build to ide
        }
    }

    private fun home(): Path {
        val cached = Path.of(System.getProperty("versionchecker.idePath"))
        if (!System.getProperty("os.name").startsWith("Mac")) return cached
        val app = Path.of("build", "ide-app", "IntelliJ IDEA.app").toAbsolutePath()
        val contents = app.resolve("Contents")
        val marker = app.resolveSibling("source.txt")
        if (!Files.isDirectory(contents) || !Files.exists(marker) || Files.readString(marker) != cached.toString()) {
            app.toFile().deleteRecursively()
            Files.createDirectories(app)
            val copy = ProcessBuilder("cp", "-cR", cached.toString(), contents.toString()).inheritIO().start()
            check(copy.waitFor() == 0) { "Could not copy the IDE from $cached" }
            Files.writeString(marker, cached.toString())
        }
        // Starter expects the folder that contains the `.app`.
        return app.parent
    }
}
