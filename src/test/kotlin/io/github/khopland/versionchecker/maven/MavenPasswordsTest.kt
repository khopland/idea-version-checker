package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import io.github.khopland.versionchecker.core.Http
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** The encrypted values were made with Apache Maven 3.10.0: `mvn --encrypt-master-password` and `mvn --encrypt-password`. */
class MavenPasswordsTest {
    private val master = "{Ao0ggTXy1tYHkAzqNwcRFecGokxtQSu/NdFnVfClWqA=}"
    private val password = "{qgR6pAgYzaoHnrvpK36HVXI/hKhe2OrJ4xzkukzaQZk=}"

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
        val servers = MavenServers.servers(listOf(settings), security)
        assertEquals(Http.basicAuth("me", "server-p@ss"), servers.getValue("corp").headers["Authorization"])
        assertFalse(servers.getValue("corp").encrypted)
        assertTrue("A password that cannot be decrypted is reported", servers.getValue("maven4").encrypted)
        assertNull(servers.getValue("maven4").headers["Authorization"])
        assertEquals(Http.basicAuth("me", "text"), servers.getValue("plain").headers["Authorization"])
        // Without settings-security.xml the encrypted password cannot be used.
        val missing = MavenServers.parse(JDOMUtil.load(settings))
        assertTrue(missing.getValue("corp").encrypted)
    }
}
