package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

/** Capture config properties for matching model and goal inputs. */
internal object MavenConfigProperties {
    fun read(directory: Path): Map<String, String> {
        val root = generateSequence(directory) { it.parent }.firstOrNull { Files.isDirectory(it.resolve(".mvn")) }
            ?: return emptyMap()
        return buildMap {
            for (name in listOf("jvm.config", "maven.config")) {
                val file = root.resolve(".mvn").resolve(name)
                if (Files.isRegularFile(file)) putAll(parse(Files.readString(file)))
            }
        }
    }

    fun parse(text: String): Map<String, String> = buildMap {
        val arguments = ParametersListUtil.parse(text.lineSequence().filterNot { it.trimStart().startsWith('#') }.joinToString("\n"))
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index++]
            val property = when {
                argument == "-D" -> arguments.getOrNull(index++)
                argument.startsWith("-D") -> argument.substring(2)
                else -> null
            } ?: continue
            val name = property.substringBefore('=')
            if (name.isNotBlank()) put(name, property.substringAfter('=', "true"))
        }
    }
}
