package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.parseDeprecationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DeprecationPolicyTest {
    @Test fun `comments and blank entries do not create rules`() {
        assertEquals(emptyMap<String, String>(), parseDeprecationPolicy("\n  # comment\n = missing name\n"))
    }

    @Test fun `package names and coordinates share the same message syntax`() {
        assertEquals(mapOf(
            "old-package" to "Use new-package",
            "@scope/package" to "Use @scope/replacement",
            "g:a" to "Use g:b = replacement"
        ), parseDeprecationPolicy("""
            old-package = Use new-package
            @scope/package = Use @scope/replacement
            g:a = Use g:b = replacement
        """.trimIndent()))
    }

    @Test fun `missing and blank messages use the default and later rules win`() {
        assertEquals(mapOf(
            "g:a" to "Deprecated by project policy",
            "package" to "Deprecated by project policy"
        ), parseDeprecationPolicy("g:a = superseded\ng:a =  \npackage"))
    }
}
