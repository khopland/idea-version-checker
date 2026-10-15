package io.github.khopland.versionchecker.maven

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/** Used only during this preparation pass; never retained with cached reports or plans. */
internal class MavenPropertyUsages(files: Collection<PsiFile>, references: Set<String>) {
    val attributes = hashSetOf<String>()
    val attributeOwners = hashMapOf<String, MutableSet<XmlTag>>()
    val leaves = hashMapOf<String, MutableList<XmlTag>>()
    private val maximumLength = references.maxOfOrNull { it.length } ?: 0

    init {
        for (file in files) {
            for (tag in PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)) {
                tag.attributes.forEach { attribute ->
                    attribute.value?.let { value ->
                        matchingReferences(value, references).forEach { reference ->
                            attributes += reference
                            attributeOwners.getOrPut(reference) { hashSetOf() } += tag
                        }
                    }
                }
                if (tag.subTags.isEmpty()) {
                    matchingReferences(tag.value.trimmedText, references).forEach { reference ->
                        leaves.getOrPut(reference) { mutableListOf() } += tag
                    }
                }
            }
        }
    }

    private fun matchingReferences(text: String, references: Set<String>): Set<String> {
        val matches = hashSetOf<String>()
        var start = text.indexOf("\${")
        var end = -1
        while (start >= 0) {
            if (end < start + 2) end = text.indexOf('}', start + 2)
            if (end < 0) break
            if (end + 1 - start <= maximumLength) {
                val reference = text.substring(start, end + 1)
                if (reference in references) matches += reference
            }
            // Inspect nested markers too: ${outer-${shared}} contains ${shared}.
            start = text.indexOf("\${", start + 2)
        }
        return matches
    }
}

