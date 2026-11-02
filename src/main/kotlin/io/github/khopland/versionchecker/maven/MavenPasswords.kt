package io.github.khopland.versionchecker.maven

import com.intellij.openapi.util.JDOMUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A decrypted password, or why it could not be decrypted. */
internal class Decryption private constructor(val value: String?, val problem: String?) {
    companion object {
        fun of(value: String) = Decryption(value, null)
        fun failed(problem: String) = Decryption(null, problem)
    }
}

/**
 * Decrypts `{…}` passwords in `settings.xml` like Maven.
 *
 * Maven 3 (plexus-cipher): the master password in `settings-security.xml` is encrypted with the fixed passphrase
 * `settings.security`, and server passwords are encrypted with the master password.
 *
 * Maven 4 (plexus-sec-dispatcher 4): a value is `{[name=master,cipher=AES/GCM/NoPadding,version=…]…}`. The
 * `master` configuration in `settings-security4.xml` names a `source` for the master password: `env:NAME`,
 * `system-property:name` or `file:/absolute/path`. Prompts, the GPG agent and 1Password need a terminal or another
 * program, so they are not supported. `name=legacy` values use the Maven 3 master password.
 */
internal class MavenSecurity(
    private val legacyFile: Path = legacyFile(),
    private val file: Path = Paths.get(System.getProperty("user.home"), ".m2", "settings-security4.xml"),
    private val env: (String) -> String? = System::getenv,
) {
    /** Changes when either configuration file changes. */
    val stamp get() = "${stamp(legacyFile)}/${stamp(file)}"

    private val legacyMaster by lazy { MavenPasswords.master(legacyFile) }
    private val configurations by lazy { configurations(file) }

    fun decrypt(value: String): Decryption {
        val attributes = MavenPasswords.attributes(value.trim())
        if (attributes == null) return decryptLegacy(value)
        val (fields, payload) = attributes
        return when (val name = fields["name"]) {
            "legacy" -> decryptLegacy("{$payload}")
            null -> Decryption.failed("the encrypted value has no dispatcher name")
            else -> decrypt(name, fields["cipher"], payload)
        }
    }

    private fun decryptLegacy(value: String): Decryption {
        val master = legacyMaster ?: return Decryption.failed("there is no master password in ${legacyFile.fileName}")
        return MavenPasswords.decrypt(value, master)?.let(Decryption::of) ?: Decryption.failed("the master password in ${legacyFile.fileName} does not fit")
    }

    private fun decrypt(name: String, cipher: String?, payload: String): Decryption {
        val configuration = configurations[name] ?: return Decryption.failed("${file.fileName} has no configuration '$name'")
        if (cipher != MavenPasswords.GCM) return Decryption.failed("the cipher '$cipher' is not supported")
        val source = configuration["source"] ?: return Decryption.failed("the configuration '$name' in ${file.fileName} has no source")
        val master = when {
            source.startsWith("env:") -> source.removePrefix("env:").let { env(it) ?: return Decryption.failed("the environment variable $it is not set for the IDE") }
            source.startsWith("system-property:") -> source.removePrefix("system-property:")
                .let { System.getProperty(it) ?: return Decryption.failed("the system property $it is not set for the IDE") }
            source.startsWith("file:") -> source.removePrefix("file:").let { readMaster(it) ?: return Decryption.failed("the master password file $it cannot be read") }
            else -> return Decryption.failed("the master password source '${source.substringBefore(':')}' needs a prompt or another program; " +
                "use env:, file: or system-property: for the IDE")
        }
        return MavenPasswords.decryptGcm(payload, master)?.let(Decryption::of) ?: Decryption.failed("the master password from $source does not fit")
    }

    companion object {
        /** `-Dsettings.security`, or `~/.m2/settings-security.xml`. */
        fun legacyFile(): Path = System.getProperty("settings.security")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".m2", "settings-security.xml")

        private fun stamp(file: Path) = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()

        /** The first line that is not a comment, from an absolute path, like Maven's file source. */
        private fun readMaster(path: String): String? {
            val file = Paths.get(path)
            if (!file.isAbsolute) return null
            return runCatching { Files.readAllLines(file) }.getOrNull()?.firstOrNull { !it.startsWith("#") }?.trim()
        }

        /** `<configuration>` properties by configuration name. */
        private fun configurations(file: Path): Map<String, Map<String, String>> {
            val root = runCatching { if (Files.isRegularFile(file)) JDOMUtil.load(file) else null }.getOrNull() ?: return emptyMap()
            fun org.jdom.Element.child(name: String) = children.firstOrNull { it.name == name }
            return root.child("configurations")?.children.orEmpty().filter { it.name == "configuration" }.mapNotNull { configuration ->
                val name = configuration.child("name")?.textTrim ?: return@mapNotNull null
                name to configuration.child("properties")?.children.orEmpty().filter { it.name == "property" }.mapNotNull { property ->
                    val key = property.child("name")?.textTrim ?: return@mapNotNull null
                    key to (property.child("value")?.textTrim ?: return@mapNotNull null)
                }.toMap()
            }.toMap()
        }
    }
}

internal object MavenPasswords {
    private val payload = Regex("""(?<!\\)\{([A-Za-z0-9+/]+=*)}""")
    private val attributed = Regex("""^\{\[([^\]]*)]([A-Za-z0-9+/]+=*)}$""")
    private const val MASTER_PASSPHRASE = "settings.security"
    const val GCM = "AES/GCM/NoPadding"

    /** Maven treats a value with an unescaped `{…}`, or a Maven 4 `{[…]…}`, as encrypted. */
    fun isEncrypted(value: String) = payload.containsMatchIn(value) || attributes(value.trim()) != null

    /** The `[key=value,…]` attributes and the payload of a Maven 4 value, or null for another value. */
    fun attributes(value: String): Pair<Map<String, String>, String>? {
        val match = attributed.matchEntire(value) ?: return null
        val fields = match.groupValues[1].split(',').filter { it.isNotBlank() }.associate { pair ->
            pair.substringBefore('=').trim() to pair.substringAfter('=', "").trim()
        }
        return fields to match.groupValues[2]
    }

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

    /** Maven 4's `AES/GCM/NoPadding`: a 12-byte IV, a 16-byte salt and the cipher text, with a PBKDF2-SHA512 key. */
    fun decryptGcm(encoded: String, password: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        val iv = bytes.copyOfRange(0, 12)
        val salt = bytes.copyOfRange(12, 28)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256)).encoded
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes.copyOfRange(28, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    /** The decrypted master password, following one `<relocation>`, or null. */
    fun master(file: Path): String? {
        var current = file
        repeat(2) {
            val root = runCatching { if (Files.isRegularFile(current)) JDOMUtil.load(current) else null }.getOrNull() ?: return null
            root.children.firstOrNull { it.name == "master" }?.textTrim?.takeIf { it.isNotEmpty() }?.let { return decrypt(it, MASTER_PASSPHRASE) }
            current = root.children.firstOrNull { it.name == "relocation" }?.textTrim?.let { Paths.get(it) } ?: return null
        }
        return null
    }
}
