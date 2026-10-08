package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class MavenDependencyFiltersTest {
    @Test fun `includes exact group artifact pairs deduplicated across baselines`() {
        val dependencies = listOf(DependencyVersion("org.example", "a", "1.0"),
            DependencyVersion("org.example", "a", "2.0"), DependencyVersion("g", "bom", "3.0"))
        val properties = Properties().apply { setProperty("custom", "value") }
        MavenDependencyFilters.apply(properties, dependencies, setOf("org.example:a"))
        assertEquals("g:bom,org.example:a", properties.getProperty("dependencyIncludes"))
        assertEquals(properties.getProperty("dependencyIncludes"), properties.getProperty("dependencyManagementIncludes"))
        assertEquals("org.example:a", properties.getProperty("dependencyExcludes"))
        assertEquals(properties.getProperty("dependencyExcludes"), properties.getProperty("dependencyManagementExcludes"))
        assertEquals("value", properties.getProperty("custom"))
    }

    @Test fun `unsupported pattern syntax keeps the broad path for the entire category`() {
        for (value in listOf("", "g:*", "\${group}", "g,a", "g?", "g a", "g:a", "g\\a")) {
            for (dependency in listOf(DependencyVersion(value, "a", "1.0"), DependencyVersion("g", value, "1.0"))) {
                val properties = Properties()
                MavenDependencyFilters.apply(properties, listOf(DependencyVersion("g", "supported", "1.0"), dependency), setOf("g:broken"))
                assertNull(properties.getProperty("dependencyIncludes"))
                assertNull(properties.getProperty("dependencyManagementIncludes"))
                assertEquals("g:broken", properties.getProperty("dependencyExcludes"))
            }
        }
    }

    @Test fun `empty and legacy inputs never install an empty inclusion pattern`() {
        for (dependencies in listOf(null, emptyList<DependencyVersion>())) {
            val properties = Properties()
            MavenDependencyFilters.apply(properties, dependencies, emptySet())
            assertTrue(properties.isEmpty())
        }
    }
}
