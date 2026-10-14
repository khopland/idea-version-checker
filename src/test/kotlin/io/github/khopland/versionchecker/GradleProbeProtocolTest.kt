package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.gradle.GradleVersionLookup
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class GradleProbeProtocolTest {
    @Test fun `paths and request content remain JSON data independent of script source`() {
        val script = GradleVersionLookup.script()
        val hostile = "/tmp/quote' slash\\ dollar\$ interpolation\${throw new Exception()}\nfile"
        val request = GradleVersionLookup.Request("0:1", "fixture", "library", "1.0.0", hostile)
        val output = Path.of(hostile + "-output")
        val timings = Path.of(hostile + "-timings")
        for (indexed in listOf(true, false)) {
            val json = JsonParser.parseString(GradleVersionLookup.configuration(listOf(request), output, hostile,
                UpdateMode.PATCH, hostile + "-task", timings, indexed)).asJsonObject
            assertEquals(hostile, json["source"].asString)
            assertEquals(output.toString(), json["output"].asString)
            assertEquals(timings.toString(), json["timings"].asString)
            assertEquals(hostile + "-task", json["task"].asString)
            assertEquals(hostile, json["requests"].asJsonArray[0].asJsonObject["source"].asString)
            assertEquals("PATCH", json["mode"].asString)
            assertEquals(indexed, json["indexed"].asBoolean)
            assertEquals(GradleVersionLookup.PROTOCOL_VERSION, json["protocolVersion"].asInt)
            assertEquals(script, GradleVersionLookup.script())
            assertFalse(script.contains(hostile))
        }
        assertTrue(script.contains("gradle.startParameter.systemPropertiesArgs"))
        assertFalse(script.contains("System.getProperty"))
    }

    @Test fun `versioned response preserves declaration results and review reasons`() {
        val results = GradleVersionLookup.parseResponse("""{"protocolVersion":1,"results":{
            "0:1":{"versions":["1.2.0"],"reason":null},
            "0:2":{"versions":[],"reason":"Needs manual review"}}}""", listOf("0:1", "0:2"))
        assertEquals(listOf("1.2.0"), results["0:1"]!!.versions)
        assertNull(results["0:1"]!!.reason)
        assertEquals("Needs manual review", results["0:2"]!!.reason)
    }

    @Test fun `unsupported incomplete and omitted declaration reports fail closed`() {
        for (json in listOf("null", "{}", """{"protocolVersion":2,"results":{}}""",
            """{"results":{}}""", """{"protocolVersion":1}""",
            """{"protocolVersion":1,"results":null}""")) {
            assertThrows(IllegalStateException::class.java) { GradleVersionLookup.parseResponse(json) }
        }
        for (json in listOf("""{"protocolVersion":1,"results":{}}""",
            """{"protocolVersion":1,"results":{"0:1":null}}""")) {
            assertThrows(IllegalStateException::class.java) { GradleVersionLookup.parseResponse(json, listOf("0:1")) }
        }
    }

    @Test fun `distributed plugin contains the probe resource`() {
        val expected = GradleVersionLookup.script()
        val archive = Path.of(System.getProperty("versionchecker.pluginArchive"))
        var packagedScript: String? = null
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".jar") }.forEach { jar ->
                ZipInputStream(zip.getInputStream(jar)).use { entries ->
                    while (true) {
                        val entry = entries.nextEntry ?: break
                        if (entry.name == "gradle/version-checker.gradle") {
                            packagedScript = entries.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                    }
                }
            }
        }
        assertEquals(expected, packagedScript)
    }
}
