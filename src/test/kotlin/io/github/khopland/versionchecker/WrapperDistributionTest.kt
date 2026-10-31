package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.gradle.GradleWrapperAdapter
import io.github.khopland.versionchecker.maven.MavenWrapperAdapter
import org.junit.Assert.*
import org.junit.Test

class WrapperDistributionTest {
    private val maven = MavenWrapperAdapter()
    private val gradle = GradleWrapperAdapter { emptyList() }

    @Test fun `maven wrapper url keeps its repository and changes both version segments`() {
        val text = "# comment\nwrapperVersion=3.3.2\ndistributionUrl=https\\://nexus.example/repository/maven/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n"
        val distribution = maven.distribution(text)!!
        assertEquals("3.9.6", distribution.version)
        assertFalse(distribution.checksum)
        assertEquals(distribution.url, distribution.range.substring(text))
        assertEquals("https\\://nexus.example/repository/maven/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip",
            distribution.withVersion("3.9.9"))
    }

    @Test fun `maven wrapper with mismatched path and file versions is not recognized`() {
        val text = "distributionUrl=https://repo/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.5-bin.zip"
        assertNull(maven.distribution(text)!!.version)
    }

    @Test fun `gradle wrapper url versions, checksums and mirrors`() {
        val official = "distributionBase=GRADLE_USER_HOME\r\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\r\nzipStoreBase=GRADLE_USER_HOME\r\n"
        val distribution = gradle.distribution(official)!!
        assertEquals("8.10.2", distribution.version)
        assertEquals(distribution.url, distribution.range.substring(official))
        assertNull(gradle.review(distribution))
        assertEquals("https\\://services.gradle.org/distributions/gradle-9.1.0-bin.zip", distribution.withVersion("9.1.0"))
        assertEquals("8.5", gradle.distribution("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-all.zip")!!.version)
        val mirror = gradle.distribution("distributionUrl=https\\://nexus.example/gradle/gradle-8.5-bin.zip")!!
        assertEquals("8.5", mirror.version)
        assertTrue(gradle.review(mirror)!!.contains("manual review"))
        assertTrue(gradle.distribution("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\ndistributionSha256Sum=abc\n")!!.checksum)
        assertFalse(gradle.distribution("#distributionSha256Sum=abc\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip")!!.checksum)
        assertNull(gradle.distribution("zipStorePath=wrapper/dists"))
    }

    @Test fun `gradle release list keeps only final releases with their build time`() {
        val releases = GradleWrapperAdapter.parse("""[
            {"version":"9.1.0","buildTime":"20250918120000+0000","snapshot":false,"nightly":false,"releaseNightly":false,"broken":false,"rcFor":"","milestoneFor":""},
            {"version":"9.2.0-rc-1","buildTime":"20251001120000+0000","snapshot":false,"nightly":false,"releaseNightly":false,"broken":false,"rcFor":"9.2.0","milestoneFor":""},
            {"version":"9.2.0-20251002","buildTime":"20251002120000+0000","snapshot":true,"nightly":true,"releaseNightly":false,"broken":false,"rcFor":"","milestoneFor":""},
            {"version":"8.0","buildTime":"20230213120000+0000","snapshot":false,"nightly":false,"releaseNightly":false,"broken":true,"rcFor":"","milestoneFor":""}
        ]""")
        assertEquals(listOf("9.1.0"), releases.map { it.version })
        assertEquals(java.time.Instant.parse("2025-09-18T12:00:00Z").toEpochMilli(), releases.single().buildTime)
    }
}
