package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.NpmViewFailure
import org.junit.Assert.*
import org.junit.Test

class NpmFailureAdviceTest {
    @Test fun `native codes distinguish authentication offline transport and certificate recovery`() {
        val cases = mapOf(
            "E401" to "credentials", "E403" to "permissions", "ENEEDAUTH" to ".npmrc",
            "ENOTCACHED" to "offline", "ETIMEDOUT" to "network", "ECONNREFUSED" to "proxy",
            "ENOTFOUND" to "registry URL", "CERT_HAS_EXPIRED" to "certificate",
            "SELF_SIGNED_CERT_IN_CHAIN" to "trusted certificate", "E404" to "package name")
        for ((code, expected) in cases) {
            val advice = NpmViewFailure(code, "native-output-with-secret").recoveryMessage
            assertTrue("$code should explain $expected", advice.contains(expected))
            assertTrue(advice.contains("retry"))
            assertFalse(advice.contains("native-output-with-secret"))
        }
    }

    @Test fun `unknown errors use safe configuration guidance without copying the code or message`() {
        for (code in listOf(null, "UNKNOWN_NATIVE_CODE")) {
            val advice = NpmViewFailure(code, "https://user:secret@registry.example").recoveryMessage
            assertTrue(advice.contains("Node/npm settings"))
            assertTrue(advice.contains("registry access"))
            assertFalse(advice.contains("secret"))
            assertFalse(advice.contains("UNKNOWN_NATIVE_CODE"))
        }
    }
}
