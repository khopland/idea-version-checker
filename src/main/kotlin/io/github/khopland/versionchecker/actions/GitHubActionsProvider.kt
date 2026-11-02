package io.github.khopland.versionchecker.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.khopland.versionchecker.core.*

/**
 * `uses: owner/repo@v4` in GitHub workflows (YAML files in `.github/workflows`) and composite actions (`action.yml`). Only
 * version tags are checked, not branches or commit hashes, and an update keeps the shape of the tag: `v4` is offered
 * `v5`, and `v4.1.2` is offered `v4.2.0`. Docker and local actions are skipped.
 */
class GitHubActionsProvider : DeclarationProvider {
    override val fileNames = listOf("action.yml", "action.yaml")

    override fun buildFiles(project: Project, scope: GlobalSearchScope): Collection<VirtualFile> =
        super.buildFiles(project, scope) + listOf("yml", "yaml").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }.filter(::isWorkflow)

    override fun accepts(file: PsiFile) = file.name in fileNames || file.virtualFile?.let(::isWorkflow) == true

    private fun isWorkflow(file: VirtualFile) =
        (file.extension == "yml" || file.extension == "yaml") && file.parent?.name == "workflows" && file.parent?.parent?.name == ".github"

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return uses.findAll(file.text).mapNotNull { match ->
            val (owner, repo, ref) = match.destructured
            if (!tag.matches(ref)) return@mapNotNull null
            val group = match.groups[3]!!
            val range = TextRange(group.range.first, group.range.last + 1)
            Declaration("$owner/$repo", range, ref, GitHubTags(owner, repo, ref), Edit(vfile, range, ref))
        }.toList()
    }

    private companion object {
        val uses = Regex("""(?m)^[ \t]*(?:-[ \t]+)?uses[ \t]*:[ \t]*['"]?([\w.\-]+)/([\w.\-]+)(?:/[^@\s'"]*)?@([\w.\-]+)['"]?[ \t]*(?:#.*)?$""")
        val tag = Regex("""^v?\d+(?:\.\d+){0,2}$""")
    }
}

/**
 * The tags of a GitHub repository, read from the Git protocol's reference list, which needs no API token.
 * Only tags with the same shape as [current] count: the same `v` prefix and the same number of parts.
 */
class GitHubTags(private val owner: String, private val repo: String, private val current: String, private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "github-tags:$owner/$repo:" + (if (current.startsWith("v")) "v" else "") + current.removePrefix("v").split('.').size

    override suspend fun fetch(): Releases {
        val response = http().get("https://github.com/$owner/$repo.git/info/refs?service=git-upload-pack")
        // A private or deleted repository asks for credentials.
        if (response.status == 401 || response.status == 404) return Releases(emptyList())
        if (!response.ok) throw HttpFailure("github.com returned HTTP ${response.status}")
        return Releases(shaped(tags(response.text()), current))
    }

    override val hasReleaseNotes get() = true
    override suspend fun releaseNotes(version: String) = "https://github.com/$owner/$repo/releases/tag/$version"

    companion object {
        private val numbers = Regex("""^\d+(?:\.\d+)*$""")
        private val ref = Regex("""refs/tags/([^\s^\u0000]+)(\^\{})?""")

        /** The tags with the same `v` prefix and number of parts as [current]. */
        fun shaped(tags: List<String>, current: String): List<String> {
            val prefix = if (current.startsWith("v")) "v" else ""
            val parts = current.removePrefix("v").split('.').size
            return tags.filter { tag ->
                tag.startsWith(prefix) && tag.removePrefix(prefix).let { numbers.matches(it) && it.split('.').size == parts }
            }
        }

        fun tags(advertisement: String): List<String> = ref.findAll(advertisement).filter { it.groups[2] == null }.map { it.groupValues[1] }.distinct().toList()
    }
}
