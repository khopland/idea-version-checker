package io.github.khopland.versionchecker.maven

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** One ownership check for local and bulk edits; only the supplied version consumers are authorized. */
internal class MavenPropertyEligibility(files: Collection<PsiFile>) {
    private val usageIndex by lazy {
        val references = files.flatMap { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }
            .filter { it.parentTag?.localName == "properties" }.mapTo(hashSetOf()) { "\${${it.localName}}" }
        MavenPropertyUsages(files, references)
    }

    data class Consumer(val version: XmlTag, val context: PsiFile)

    fun blockers(target: XmlTag, consumers: Collection<Consumer>): List<String> {
        val blockers = mutableListOf<String>()
        if (target.parentTag?.localName == "properties") {
            val reference = "\${${target.localName}}"
            val knownVersions = consumers.mapTo(hashSetOf()) { it.version }
            val allowedAliases = hashSetOf<XmlTag>()
            val pending = ArrayDeque(consumers.mapNotNull { (version, context) ->
                version.value.trimmedText.let(::versionPropertyName)?.let { context to it }
            })
            val visited = hashSetOf<Pair<PsiFile, String>>()
            while (pending.isNotEmpty()) {
                val (file, name) = pending.removeFirst()
                if (!visited.add(file to name)) continue
                val context = MavenDomUtil.getMavenDomProjectModel(file) ?: continue
                val active = MavenProjectsManager.getInstance(file.project).findProject(file.virtualFile)
                    ?.activatedProfilesIds?.enabledProfiles
                val alias = context.xmlTag?.let { findLocalVersionPropertyOwner(it, name, active) }
                    ?: MavenDomProjectProcessorUtils.searchProperty(name, context, file.project)
                if (alias != null && alias != target) {
                    val next = versionPropertyName(alias.value.trimmedText)
                    if (next == null) blockers += "${target.localName}: property ownership requires review"
                    else {
                        allowedAliases += alias
                        pending.add(file to next)
                    }
                }
            }
            fun relevantUsage(usage: XmlTag, candidate: String): Boolean {
                if (usage in knownVersions || usage in allowedAliases) return true
                val name = versionPropertyName(candidate) ?: return true
                val file = usage.containingFile
                val active = MavenProjectsManager.getInstance(file.project).findProject(file.virtualFile)
                    ?.activatedProfilesIds?.enabledProfiles
                val owner = findLocalVersionPropertyOwner(usage, name, active)
                    ?: MavenDomUtil.getMavenDomProjectModel(file)?.let {
                        MavenDomProjectProcessorUtils.searchProperty(name, it, file.project)
                    }
                return owner == null || owner == target || owner in allowedAliases
            }
            val references = setOf(reference) + allowedAliases.map { "\${${it.localName}}" }
            if (references.any { candidate ->
                    val usages = usageIndex.leaves[candidate].orEmpty().filter { relevantUsage(it, candidate) }
                    usageIndex.attributeOwners[candidate].orEmpty().any { relevantUsage(it, candidate) } || usages.isEmpty() || usages.any {
                        it.value.trimmedText != candidate || (it !in knownVersions && it !in allowedAliases)
                    }
                }) blockers += "${target.localName}: shared, inherited or other uses need review"
        }
        return blockers
    }
}
