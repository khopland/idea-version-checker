package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts `{…}` passwords the way Maven 3 does (plexus-cipher): the master password in
 * `settings-security.xml` is encrypted with the fixed passphrase `settings.security`, and server
 * passwords are encrypted with the master password.
 */
internal object MavenPasswords {
    private val payload = Regex("""(?<!\\)\{([A-Za-z0-9+/]+=*)}""")
    private const val MASTER_PASSPHRASE = "settings.security"

    /** Maven treats a value with an unescaped `{…}` as encrypted. Maven 4 uses `{[name=…]…}`, which is not supported. */
    fun isEncrypted(value: String) = payload.containsMatchIn(value) || value.contains("{[")

    fun decrypt(value: String, passphrase: String): String? {
        val encoded = payload.find(value)?.groupValues?.get(1) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            val salt = bytes.copyOfRange(0, 8)
            val padding = bytes[8].toInt()
            val body = bytes.copyOfRange(9, bytes.size - padding)
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(passphrase.toByteArray(Charsets.UTF_8))
                update(salt)
                digest()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.copyOfRange(0, 16), "AES"), IvParameterSpec(digest.copyOfRange(16, 32)))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    /** `-Dsettings.security`, or `~/.m2/settings-security.xml`. */
    fun securityFile(): Path = System.getProperty("settings.security")?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".m2", "settings-security.xml")

    /** The decrypted master password, following one `<relocation>`, or null. */
    fun master(file: Path = securityFile()): String? {
        var current = file
        repeat(2) {
            val root = runCatching { if (Files.isRegularFile(current)) JDOMUtil.load(current) else null }.getOrNull() ?: return null
            root.children.firstOrNull { it.name == "master" }?.textTrim?.takeIf { it.isNotEmpty() }?.let { return decrypt(it, MASTER_PASSPHRASE) }
            current = root.children.firstOrNull { it.name == "relocation" }?.textTrim?.let { Paths.get(it) } ?: return null
        }
        return null
    }
}
