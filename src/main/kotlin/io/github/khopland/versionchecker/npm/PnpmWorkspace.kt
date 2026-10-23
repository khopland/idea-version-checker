package io.github.khopland.versionchecker.npm

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** Parse workspace membership through YAML, including flow/block forms and quoted exclusions. */
internal object PnpmWorkspace {
    internal fun parse(text: String): Map<*, *>? = runCatching {
        val settings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
            .setCodePointLimit(1_000_000).build()
        val loaded = Load(settings).loadFromString(text)
        if (loaded == null) emptyMap<String, Any>() else loaded as? Map<*, *>
    }.getOrNull()
    fun configuration(file: VirtualFile): Map<*, *>? = runCatching {
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: file.contentsToByteArray().toString(Charsets.UTF_8)
        parse(text)
    }.getOrNull()
    fun patterns(file: VirtualFile): List<String>? = runCatching {
        val root = configuration(file) ?: return null
        val packages = root["packages"] ?: return emptyList()
        val list = packages as? List<*> ?: return null
        list.map { it as? String ?: return null }
    }.getOrNull()
}
