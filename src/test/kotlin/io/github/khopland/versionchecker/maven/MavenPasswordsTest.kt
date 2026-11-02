package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import io.github.khopland.versionchecker.core.Http
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Maven 3 values were made with Apache Maven 3.10.0: `mvn --encrypt-master-password` and `mvn --encrypt-password`.
 * The Maven 4 value was made with plexus-sec-dispatcher 4.2.0, the library Maven 4 uses, with the master password
 * `maven4-master`.
 */
class MavenPasswordsTest {
    private val master = "{Ao0ggTXy1tYHkAzqNwcRFecGokxtQSu/NdFnVfClWqA=}"
    private val password = "{qgR6pAgYzaoHnrvpK36HVXI/hKhe2OrJ4xzkukzaQZk=}"
    private val maven4 = "{[name=master,cipher=AES/GCM/NoPadding,version=4.2]LTZAd30nWPqQ/fnrS8wqvo+9+zcDzsQU6a8fjeZolfpt88W5QHvUTg3gPND6pyNfwhxjJ+x7cjc=}"

    private fun security4(directory: Path, source: String) = directory.resolve("settings-security4.xml").also {
        Files.writeString(it, """
            <settingsSecurity xmlns="http://codehaus-plexus.github.io/plexus-sec-dispatcher/4.0.0">
              <defaultDispatcher>master</defaultDispatcher>
              <configurations><configuration><name>master</name><properties>
                <property><name>source</name><value>$source</value></property>
                <property><name>cipher</name><value>AES/GCM/NoPadding</value></property>
              </properties></configuration></configurations>
            </settingsSecurity>""".trimIndent())
    }

    @Test
    fun decryptsLikeMaven() {
        assertEquals("master-secret", MavenPasswords.decrypt(master, "settings.security"))
        assertEquals("server-p@ss", MavenPasswords.decrypt(password, "master-secret"))
        // Maven allows text around the encrypted part.
        assertEquals("server-p@ss", MavenPasswords.decrypt("Rotated 2026-10 $password", "master-secret"))
        assertNull(MavenPasswords.decrypt(password, "wrong"))
        assertFalse(MavenPasswords.isEncrypted("plain"))
        assertFalse(MavenPasswords.isEncrypted("escaped \\{abc=}"))
        assertTrue(MavenPasswords.isEncrypted("{[name=master,cipher=AES/GCM/NoPadding,version=4.0]abc}"))
    }

    @Test
    fun settingsServersUseTheMasterPassword() {
        val directory = Files.createTempDirectory("m2")
        val security = directory.resolve("settings-security.xml")
        Files.writeString(directory.resolve("relocated.xml"), "<settingsSecurity><master>$master</master></settingsSecurity>")
        Files.writeString(security, "<settingsSecurity><relocation>${directory.resolve("relocated.xml")}</relocation></settingsSecurity>")
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, """
            <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><servers>
              <server><id>corp</id><username>me</username><password>$password</password></server>
              <server><id>maven4</id><username>me</username><password>{[name=master,version=4.0]abc}</password></server>
              <server><id>plain</id><username>me</username><password>text</password></server>
            </servers></settings>""".trimIndent())
        val servers = MavenSettings.servers(listOf(settings), MavenSecurity(security, directory.resolve("settings-security4.xml")))
        assertEquals(Http.basicAuth("me", "server-p@ss"), servers.getValue("corp").headers["Authorization"])
        assertNull(servers.getValue("corp").problem)
        assertEquals("A password that cannot be decrypted is reported", "settings-security4.xml has no configuration 'master'",
            servers.getValue("maven4").problem)
        assertNull(servers.getValue("maven4").headers["Authorization"])
        assertEquals(Http.basicAuth("me", "text"), servers.getValue("plain").headers["Authorization"])
        // Without settings-security.xml the encrypted password cannot be used.
        val missing = MavenSettings.parse(JDOMUtil.load(settings))
        assertEquals("there is no master password", missing.getValue("corp").problem)
    }

    @Test
    fun decryptsMaven4Values() {
        val directory = Files.createTempDirectory("m2")
        val masterFile = directory.resolve("master.txt")
        Files.writeString(masterFile, "# the master password\nmaven4-master\n")
        fun security(source: String, env: Map<String, String> = emptyMap()) =
            MavenSecurity(directory.resolve("none.xml"), security4(directory, source), env::get)

        assertTrue(MavenPasswords.isEncrypted(maven4))
        assertEquals("server-p@ss4", security("file:$masterFile").decrypt(maven4).value)
        assertEquals("server-p@ss4", security("env:MAVEN_MASTER", mapOf("MAVEN_MASTER" to "maven4-master")).decrypt(maven4).value)
        assertEquals("the environment variable MAVEN_MASTER is not set for the IDE", security("env:MAVEN_MASTER").decrypt(maven4).problem)
        assertEquals("the master password from env:MAVEN_MASTER does not fit",
            security("env:MAVEN_MASTER", mapOf("MAVEN_MASTER" to "wrong")).decrypt(maven4).problem)
        assertEquals("the master password source 'gpg-agent' needs a prompt or another program; use env:, file: or system-property: for the IDE",
            security("gpg-agent:/run/gpg-agent.sock").decrypt(maven4).problem)
        // Maven 4 still reads Maven 3 values with the master password in settings-security.xml.
        assertEquals("there is no master password in none.xml", security("file:$masterFile").decrypt(password).problem)
    }

    @Test
    fun maven4LegacyValuesUseTheMaven3MasterPassword() {
        val directory = Files.createTempDirectory("m2")
        val legacy = directory.resolve("settings-security.xml")
        Files.writeString(legacy, "<settingsSecurity><master>$master</master></settingsSecurity>")
        val security = MavenSecurity(legacy, directory.resolve("settings-security4.xml"))
        assertEquals("server-p@ss", security.decrypt("{[name=legacy,version=4.0]${password.trim('{', '}')}}").value)
        assertEquals("server-p@ss", security.decrypt(password).value)
    }
}
