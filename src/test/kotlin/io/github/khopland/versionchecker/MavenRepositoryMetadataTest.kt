package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import com.intellij.openapi.util.JDOMUtil
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Properties

class MavenRepositoryMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    private val dependency = DependencyVersion("no.example.felles", "infrastructure-api-ats", "5.40.0")

    @Test fun `refresh expires only active remote metadata timestamps and retains cached files`() {
        val root = temp.root.toPath()
        val directory = Files.createDirectories(root.resolve("no/example/felles/infrastructure-api-ats"))
        val status = directory.resolve("resolver-status.properties")
        val values = Properties().apply {
            setProperty("maven-metadata-nexus.xml.lastUpdated", "123456")
            setProperty("maven-metadata-nexus.xml/auth@default-nexus-url.lastUpdated", "234567")
            setProperty("maven-metadata-nexus.xml.error", "")
            setProperty("maven-metadata-other.xml.lastUpdated", "345678")
            setProperty("maven-metadata-local.xml.lastUpdated", "456789")
            setProperty("library-5.40.0.jar.lastUpdated", "567890")
        }
        Files.newOutputStream(status).use { values.store(it, null) }
        val remote = directory.resolve("maven-metadata-nexus.xml")
        val local = directory.resolve("maven-metadata-local.xml")
        Files.writeString(remote, metadata("5.40.0", "5.51.2"))
        Files.writeString(local, metadata("0-SNAPSHOT"))
        MavenRepositoryMetadata.expireUpdates(root, listOf(dependency, dependency), setOf("nexus", "local"))
        val refreshed = Properties().apply { Files.newInputStream(status).use { load(it) } }
        assertEquals("0", refreshed.getProperty("maven-metadata-nexus.xml.lastUpdated"))
        assertEquals("0", refreshed.getProperty("maven-metadata-nexus.xml/auth@default-nexus-url.lastUpdated"))
        for (key in values.stringPropertyNames().filter { !it.startsWith("maven-metadata-nexus.xml") }) {
            assertEquals(values.getProperty(key), refreshed.getProperty(key))
        }
        assertEquals(metadata("5.40.0", "5.51.2"), Files.readString(remote))
        assertEquals(metadata("0-SNAPSHOT"), Files.readString(local))
    }

    @Test fun `refresh leaves inactive metadata status files untouched`() {
        val directory = Files.createDirectories(temp.root.toPath().resolve("no/example/felles/infrastructure-api-ats"))
        val status = directory.resolve("resolver-status.properties")
        val text = "maven-metadata-other.xml.lastUpdated=123456\n"
        Files.writeString(status, text)
        MavenRepositoryMetadata.expireUpdates(temp.root.toPath(), listOf(dependency), setOf("nexus"))
        assertEquals(text, Files.readString(status))
    }

    @Test fun `picks the newest stable remote version and ignores local installs`() {
        val directory = temp.root.toPath().resolve("no/example/felles/infrastructure-api-ats")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("maven-metadata-nexus.xml"), metadata("5.40.0", "5.51.2", "5.9.0", "6.0.0-RC5",
            "feature_SPAP_51175-SNAPSHOT", "BUMP_INFORMASJONSMODELL-SNAPSHOT"))
        Files.writeString(directory.resolve("maven-metadata-local.xml"), metadata("0-SNAPSHOT", "99.0.0"))
        assertEquals("5.51.2", MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR, setOf("nexus", "local")))
    }

    @Test fun `respects update mode and missing artifacts`() {
        val versions = listOf("5.40.1", "5.51.2", "6.1.0")
        assertEquals("6.1.0", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.MAJOR))
        assertEquals("5.51.2", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.MINOR))
        assertEquals("5.40.1", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.PATCH))
        assertNull(MavenRepositoryMetadata.latest(versions, "6.1.0", UpdateMode.MAJOR))
        assertNull(MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR, setOf("central")))
    }

    @Test fun `ignores metadata from inactive repositories including malformed files`() {
        val directory = Files.createDirectories(temp.root.toPath().resolve("no/example/felles/infrastructure-api-ats"))
        Files.writeString(directory.resolve("maven-metadata-active-mirror.xml"), metadata("5.51.2"))
        Files.writeString(directory.resolve("maven-metadata-unrelated-private.xml"), metadata("99.0.0"))
        Files.writeString(directory.resolve("maven-metadata-old-broken.xml"), "not xml")
        assertEquals("5.51.2", MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR, setOf("active-mirror")))
        assertNull(MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR, setOf("central")))
        assertNull(MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR, emptySet()))
    }

    @Test fun `reads effective repository policies without including plugin repositories`() {
        val pom = JDOMUtil.load("""
            <project xmlns="http://maven.apache.org/POM/4.0.0"><repositories>
              <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository>
              <repository><id>snapshots</id><url>https://example.test/snapshots</url>
                <releases><enabled>false</enabled></releases><snapshots><enabled>true</enabled></snapshots></repository>
            </repositories><pluginRepositories><pluginRepository><id>plugins-only</id>
              <url>https://example.test/plugins</url></pluginRepository></pluginRepositories></project>
        """.trimIndent())
        val repositories = MavenRepositoryMetadata.repositories(pom)
        assertEquals(listOf("central", "snapshots"), repositories.map { it.id })
        assertEquals(true, repositories.first().releasesPolicy?.isEnabled)
        assertEquals(true, repositories.first().snapshotsPolicy?.isEnabled)
        assertEquals(false, repositories.last().releasesPolicy?.isEnabled)
        assertEquals(true, repositories.last().snapshotsPolicy?.isEnabled)
        assertEquals(listOf("plugins-only"), MavenRepositoryMetadata.repositories(pom, plugins = true).map { it.id })
    }

    @Test fun `reads namespaced metadata`() {
        val xml = """<metadata xmlns="http://maven.apache.org/METADATA/1.1.0"><versioning><versions>
            <version>1.0</version><version>2.0</version></versions></versioning></metadata>"""
        assertEquals(listOf("1.0", "2.0"), MavenRepositoryMetadata.versions(JDOMUtil.load(xml)))
    }

    @Test fun `extracts the artifact Maven could not order`() {
        val details = "Failed to execute goal org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates " +
            "(default-cli) on project app: Unable to retrieve versions for no.example.felles:infrastructure-api-ats:jar:5.40.0:compile"
        assertEquals("no.example.felles:infrastructure-api-ats", VersionRetrievalFailure.artifact(details))
        assertEquals("g:a", VersionRetrievalFailure.artifact("Unable to retrieve versions for g:a:jar:\${infra.version}"))
        assertNull(VersionRetrievalFailure.artifact("Could not transfer metadata g:a/maven-metadata.xml"))
    }

    private fun metadata(vararg versions: String) =
        "<metadata><versioning><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>"
}
