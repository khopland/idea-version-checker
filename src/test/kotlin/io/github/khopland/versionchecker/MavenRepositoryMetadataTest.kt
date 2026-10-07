package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import com.intellij.openapi.util.JDOMUtil
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MavenRepositoryMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    private val dependency = DependencyVersion("no.example.felles", "infrastructure-api-ats", "5.40.0")

    @Test fun `picks the newest stable remote version and ignores local installs`() {
        val directory = temp.root.toPath().resolve("no/example/felles/infrastructure-api-ats")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("maven-metadata-nexus.xml"), metadata("5.40.0", "5.51.2", "5.9.0", "6.0.0-RC5",
            "feature_SPAP_51175-SNAPSHOT", "BUMP_INFORMASJONSMODELL-SNAPSHOT"))
        Files.writeString(directory.resolve("maven-metadata-local.xml"), metadata("0-SNAPSHOT", "99.0.0"))
        assertEquals("5.51.2", MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR))
    }

    @Test fun `respects update mode and missing artifacts`() {
        val versions = listOf("5.40.1", "5.51.2", "6.1.0")
        assertEquals("6.1.0", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.MAJOR))
        assertEquals("5.51.2", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.MINOR))
        assertEquals("5.40.1", MavenRepositoryMetadata.latest(versions, "5.40.0", UpdateMode.PATCH))
        assertNull(MavenRepositoryMetadata.latest(versions, "6.1.0", UpdateMode.MAJOR))
        assertNull(MavenRepositoryMetadata.latest(temp.root.toPath(), dependency, UpdateMode.MAJOR))
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
