package io.github.khopland.versionchecker.npm

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class YarnRcTest {
    @Test
    fun parsesTheYamlSubsetYarnUses() {
        val values = YarnRc.parse("""
            # comment
            yarnPath: .yarn/releases/yarn-4.5.0.cjs
            npmRegistryServer: "https://npm.corp.example/repo/npm-all"
            npmScopes:
              corp:
                npmRegistryServer: 'https://npm.corp.example/repo/npm-private'
                npmAuthToken: "${'$'}{CORP_TOKEN}"
            npmRegistries:
              "https://npm.corp.example/repo/npm-all":
                npmAuthToken: all-token
            packageExtensions:
              - ignored
        """.trimIndent())
        assertEquals("https://npm.corp.example/repo/npm-private", values["npmScopes.corp.npmRegistryServer"])
        assertEquals("all-token", values["npmRegistries.https://npm.corp.example/repo/npm-all.npmAuthToken"])
        assertEquals(".yarn/releases/yarn-4.5.0.cjs", values["yarnPath"])
    }

    @Test
    fun registriesScopesAndTokens() {
        val home = Files.createTempDirectory("home")
        val project = Files.createTempDirectory("project")
        val pkg = Files.createDirectories(project.resolve("packages/app"))
        Files.writeString(project.resolve(".npmrc"), "registry=https://ignored-by-yarn.example/\n")
        Files.writeString(project.resolve(".yarnrc.yml"), """
            npmRegistryServer: "https://npm.corp.example/repo/npm-all"
            npmScopes:
              corp:
                npmRegistryServer: "https://npm.corp.example/repo/npm-private"
                npmAuthToken: "${'$'}{CORP_TOKEN:-fallback}"
            npmRegistries:
              "//npm.corp.example/repo/npm-all/":
                npmAuthIdent: "me:secret"
        """.trimIndent())
        Files.writeString(home.resolve(".yarnrc.yml"), "npmAuthToken: home-token\n")
        val yarn = YarnRc.forDirectory(pkg, env = mapOf("CORP_TOKEN" to "scoped"), home = home.toString())!!
        assertEquals(NpmRegistry("https://npm.corp.example/repo/npm-private/", mapOf("Authorization" to "Bearer scoped")), yarn.registry("@corp/lib"))
        assertEquals(NpmRegistry("https://npm.corp.example/repo/npm-all/",
            mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("me:secret".toByteArray()))), yarn.registry("react"))
        assertEquals("Bearer fallback", YarnRc.forDirectory(pkg, env = emptyMap(), home = home.toString())!!.registry("@corp/lib").headers["Authorization"])
    }

    @Test
    fun npmrcIsUsedWithoutAYarnrcAndYarnDefaultsToItsRegistry() {
        val home = Files.createTempDirectory("home").toString()
        val plain = Files.createTempDirectory("npm-project")
        assertNull(YarnRc.forDirectory(plain, env = emptyMap(), home = home))
        val yarn = Files.createTempDirectory("yarn-project")
        Files.writeString(yarn.resolve(".yarnrc.yml"), "nodeLinker: node-modules\n")
        assertEquals(YarnRc.DEFAULT, YarnRc.forDirectory(yarn, env = emptyMap(), home = home)!!.registry("react").url)
        assertTrue(RegistryConfig.forDirectory(yarn) is YarnRc)
    }
}
