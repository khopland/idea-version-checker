import java.util.HexFormat
import java.security.MessageDigest

plugins { java }

val helperSources = fileTree("src/main") { include("**/*") }.files.sortedBy { it.path } + file("build.gradle.kts")
val helperVersion = "1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(helperSources.flatMap { it.readBytes().toList() }.toByteArray())).take(24)
tasks.processResources {
    inputs.property("helperVersion", helperVersion)
    val tokens = mapOf("helperVersion" to helperVersion)
    filesMatching("META-INF/maven/plugin.xml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to tokens)
    }
}

dependencies {
    compileOnly("org.apache.maven:maven-plugin-api:3.9.11")
    compileOnly("org.apache.maven:maven-core:3.9.11")
    compileOnly("org.apache.maven.resolver:maven-resolver-api:1.9.24")
    compileOnly("org.apache.maven.resolver:maven-resolver-util:1.9.24")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.compileJava { options.release = 17 }
tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
