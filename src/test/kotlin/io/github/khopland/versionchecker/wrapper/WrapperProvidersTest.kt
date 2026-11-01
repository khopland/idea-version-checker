package io.github.khopland.versionchecker.wrapper

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WrapperProvidersTest : BasePlatformTestCase() {
    fun testMavenWrapperUsesTheRepositoryInItsUrl() {
        val file = myFixture.configureByText("maven-wrapper.properties",
            "wrapperVersion=3.3.2\ndistributionUrl=https\\://nexus.example/repository/maven/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n")
        val declaration = MavenWrapperProvider().collect(file).single()
        assertEquals("3.9.6", declaration.current)
        assertEquals("3.9.6", declaration.range.substring(file.text))
        assertEquals("https\\://nexus.example/repository/maven/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip",
            declaration.edit!!.format("3.9.9"))
        assertTrue(declaration.lookup.key.endsWith("@https://nexus.example/repository/maven"))
    }

    fun testGradleWrapperChecksumAndMirrorNeedManualUpdates() {
        val official = "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n"
        assertNotNull(GradleWrapperProvider().collect(myFixture.configureByText("gradle-wrapper.properties", official)).single().edit)
        val checksum = GradleWrapperProvider().collect(myFixture.configureByText("gradle-wrapper.properties", official + "distributionSha256Sum=abc\n")).single()
        assertNull(checksum.edit)
        assertTrue(checksum.note!!.contains("distributionSha256Sum"))
        val mirror = GradleWrapperProvider().collect(myFixture.configureByText("gradle-wrapper.properties",
            "distributionUrl=https\\://mirror.example/gradle/gradle-8.14-all.zip\n")).single()
        assertNull(mirror.edit)
    }
}
