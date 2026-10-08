package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import org.junit.Assert.*
import org.junit.Test

class MavenScanInputsTest {
    @Test fun `scan copies declarations and properties and groups only selected supported categories`() {
        val dependency = DependencyVersion("g", "a", "1.0")
        val plugin = dependency.copy(artifactKind = MavenArtifactKind.PLUGIN)
        val parent = dependency.copy(artifactKind = MavenArtifactKind.PARENT)
        val declarations = mutableListOf(dependency, dependency, plugin, parent, dependency.copy(version = ""))
        val properties = mutableMapOf("version" to "1.0", "flag" to "true")
        val kinds = mutableSetOf(MavenArtifactKind.DEPENDENCY, MavenArtifactKind.PLUGIN)
        val inputs = MavenScanInputs(declarations, kinds, properties)
        declarations.clear(); kinds.clear(); properties["version"] = "2.0"
        assertEquals(setOf(MavenArtifactKind.DEPENDENCY, MavenArtifactKind.PLUGIN), inputs.kinds)
        assertEquals(listOf(dependency), inputs[MavenArtifactKind.DEPENDENCY])
        assertEquals(listOf(plugin), inputs[MavenArtifactKind.PLUGIN])
        assertTrue(inputs[MavenArtifactKind.PARENT].isEmpty())
        assertEquals("1.0", inputs.properties["version"])
    }
    @Test fun `empty categories skip native setup`() {
        assertTrue(MavenScanInputs(emptyList(), MavenArtifactKind.entries.toSet(), emptyMap()).kinds.isEmpty())
    }
}
