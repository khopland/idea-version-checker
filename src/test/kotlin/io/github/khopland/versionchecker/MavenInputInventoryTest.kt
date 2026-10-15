package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.MavenInputInventory
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class MavenInputInventoryTest {
    @Test fun `selected saving keeps every ownership pom but only selected distribution and ancestors`() = withDirectory { root ->
        val selected = root.resolve("reactor/selected")
        val sibling = root.resolve("other/sibling")
        val selectedHome = root.resolve("maven-selected")
        val siblingHome = root.resolve("maven-sibling")
        val layout = MavenInputInventory.Layout(root.resolve("settings.xml"), System.getProperty("user.home"),
            setOf(selected.resolve("pom.xml").toString(), sibling.resolve("pom.xml").toString()),
            mapOf(selected to MavenInputInventory.Distribution(selectedHome, "3")))
        val inventory = MavenInputInventory.discover(layout)
        val paths = inventory.resolutionInputPaths()
        assertTrue(paths.containsAll(layout.poms))
        assertTrue(paths.contains(selectedHome.resolve("conf/settings.xml").toString()))
        assertTrue(paths.contains(selected.resolve(".mvn/wrapper/maven-wrapper.properties").toString()))
        assertTrue(paths.contains(root.resolve("reactor/.mvn/extensions.xml").toString()))
        assertFalse(paths.contains(siblingHome.resolve("conf/settings.xml").toString()))
        assertFalse(paths.contains(sibling.resolve(".mvn/maven.config").toString()))
        assertTrue(inventory.externalPaths.none { it.toString() in layout.poms })
    }

    @Test fun `project fingerprints share settings while retaining separate distribution and local inputs`() = withDirectory { root ->
        val first = root.resolve("reactor/first")
        val second = root.resolve("reactor/second")
        val firstHome = root.resolve("maven-first")
        val secondHome = root.resolve("maven-second")
        val inventory = MavenInputInventory.discover(MavenInputInventory.Layout(root.resolve("settings.xml"),
            System.getProperty("user.home"), emptySet(), mapOf(
                first to MavenInputInventory.Distribution(firstHome, "3"), second to MavenInputInventory.Distribution(secondHome, "4"))))
        val firstPaths = inventory.projectPaths(first)
        val secondPaths = inventory.projectPaths(second)
        assertTrue(firstPaths.containsAll(inventory.sharedPaths))
        assertTrue(secondPaths.containsAll(inventory.sharedPaths))
        assertTrue(firstPaths.contains(root.resolve("reactor/.mvn/jvm.config")))
        assertTrue(secondPaths.contains(root.resolve("reactor/.mvn/jvm.config")))
        assertTrue(firstPaths.contains(firstHome.resolve("conf/settings.xml")))
        assertFalse(secondPaths.contains(firstHome.resolve("conf/settings.xml")))
        assertFalse(firstPaths.contains(second.resolve(".mvn/maven.config")))
        assertEquals(inventory.externalPaths, firstPaths + secondPaths)
    }

    @Test fun `security relocations and absent distribution inputs remain in the inventory`() = withDirectory { root ->
        val security = root.resolve("settings-security.xml")
        val target = root.resolve("credentials/missing.xml")
        Files.writeString(security, "<settingsSecurity><relocation>credentials/missing.xml</relocation></settingsSecurity>")
        val directory = root.resolve("module")
        val home = root.resolve("distribution")
        Files.createDirectories(home.resolve("conf"))
        Files.writeString(home.resolve("conf/settings-security.xml"),
            "<settingsSecurity><relocation>../../distribution-secret.xml</relocation></settingsSecurity>")
        val inventory = MavenInputInventory.discover(MavenInputInventory.Layout(root.resolve("settings.xml"),
            System.getProperty("user.home"), emptySet(), mapOf(directory to MavenInputInventory.Distribution(home, null))))
        assertTrue(inventory.sharedPaths.contains(target))
        assertTrue(inventory.projectPaths(directory).contains(root.resolve("distribution-secret.xml")))
        assertTrue(inventory.externalPaths.contains(home.resolve("conf/settings.xml")))
        assertTrue(inventory.externalPaths.contains(directory.resolve(".mvn/extensions.xml")))
        assertFalse(Files.exists(target))
    }

    @Test fun `discovery honors cancellation before probing settings`() = withDirectory { root ->
        var checks = 0
        try {
            MavenInputInventory.discover(MavenInputInventory.Layout(root.resolve("settings.xml"),
                System.getProperty("user.home"), emptySet(), emptyMap())) {
                checks++
                throw CancellationException("stop inventory")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, checks) }
    }

    private fun withDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("maven-inventory").toRealPath()
        try { action(directory) } finally { directory.toFile().deleteRecursively() }
    }
}
