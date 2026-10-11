package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import junit.framework.TestCase

class DiagnosticSanitizerTest : TestCase() {
    fun testRepositoryHostsAndFailureCodesRemainWithoutCredentialsOrQueryValues() {
        val raw = "E401 GET https://private-user:p%40ss@registry.example.test/package?arbitrary=private-query#private-fragment " +
            "\nAuthorization: Bearer private-header\nProxy-Authorization: Basic cHJpdmF0ZQ==\n" +
            "//registry.example.test/:_authToken=private-npm-token\npassword='private password'\n" +
            "{\"access_token\": \"private-json-token\", \"status\": 401}\n"
        val safe = DiagnosticSanitizer.sanitize(raw)
        listOf("private-user", "p%40ss", "private-query", "private-fragment", "private-header", "cHJpdmF0ZQ==",
            "private-npm-token", "private password", "private-json-token").forEach { assertFalse(it, safe.contains(it)) }
        assertTrue(safe.contains("E401 GET https://registry.example.test/package"))
        assertTrue(safe.contains("\"status\": 401"))
    }

    fun testMavenCredentialsPrivateKeysAndPathsAreRemovedFromNestedCauses() {
        val cause = IllegalStateException("certificate failure in /Users/person/private project/settings.xml " +
            "and file:///home/person/security.xml\n<password>private-maven-password</password>\n" +
            "<master>private-master</master>\n-----BEGIN PRIVATE KEY-----\nprivate-key-content\n-----END PRIVATE KEY-----\n" +
            "C:\\Users\\person\\private-project\\settings.xml")
        val safe = DiagnosticSanitizer.sanitize(IllegalArgumentException("Native resolver failed", cause).stackTraceToString())
        listOf("/Users/person", "/home/person", "C:\\Users\\person", "private project", "private-project", "private-maven-password",
            "private-master", "private-key-content").forEach { assertFalse(it, safe.contains(it)) }
        assertTrue(safe.contains("IllegalArgumentException: Native resolver failed"))
        assertTrue(safe.contains("Caused by: java.lang.IllegalStateException: certificate failure"))
    }

    fun testFailureSamplesAreSanitizedBeforeTruncationWhileRetryTargetsStayExact() {
        val target = VersionCheckTarget("npm", "/Users/person/private-project/package.json")
        val failures = VersionCheckFailures()
        val message = "token=" + "private-long-token".repeat(1_000)
        failures.add(target, "npm", message, IllegalStateException(message))
        assertEquals(setOf(target), failures.targets)
        assertFalse(failures.details.contains("private-long-token"))
        assertFalse(failures.details.contains("private-project"))
        assertTrue(failures.details.contains("IllegalStateException"))
        assertTrue(failures.content.contains("1 build file"))
    }
}
