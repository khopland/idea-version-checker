package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.VersionChangeKind
import io.github.khopland.versionchecker.core.ArtifactId

import io.github.khopland.versionchecker.*

import com.intellij.openapi.components.service
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import com.intellij.util.xml.DomManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

internal data class DependencyProblem(
    val coordinate: DependencyVersion, val latest: String?, val anchor: XmlTag, val target: XmlTag?,
    val kind: VersionChangeKind,
    val severity: VersionSeverity,
    val notice: String? = null
) {
    val message: String get() = notice ?: updateHint(
        ArtifactId(coordinate.groupId, coordinate.artifactId), kind, coordinate.version, latest.orEmpty()) + when (coordinate.artifactKind) {
            MavenArtifactKind.PLUGIN -> " (Maven plugin)"
            MavenArtifactKind.PARENT -> " (parent POM)"
            else -> ""
        }
}

internal class MavenDependencyAnalysis(
    private val model: MavenDomProjectModel,
    private val mavenProject: MavenProject,
    private val updates: Map<DependencyVersion, String>,
    private val options: VersionCheckerSettings.Options = VersionCheckerSettings.Options(),
    private val relocations: Map<DependencyVersion, String> = emptyMap(),
    private val inspection: MavenInspectionResult? = null,
) {
    private val policy = projectPolicy(model.manager.project).copy(ignoredText = options.ignoredVersions, deprecatedText = options.deprecatedDependencies)
    private val deprecated = policy.deprecations("maven")
    private val ignored = ignoredVersions(options.ignoredVersions)
    private fun isRestricted(coordinate: DependencyVersion, version: String): Boolean {
        val artifact = ArtifactId(coordinate.groupId, coordinate.artifactId)
        return IgnoredVersion("maven", artifact, version) in ignored ||
            policy.restriction("maven", artifact, version, MavenVersionSemantics.between(coordinate.version, version)) != null
    }
    private val versionProperties by lazy {
        MavenVersionProperties(model, mavenProject.activatedProfilesIds.enabledProfiles, ::coordinate, ::managingVersion)
    }
    internal val propertyConsumers by lazy { versionProperties.consumers() }
    internal val inheritedPropertyConsumers by lazy { versionProperties.consumers(inherited = true) }
    internal val observedInheritedPropertyConsumers by lazy {
        val usedDependencies = mavenProject.dependencies.mapTo(hashSetOf()) { Triple(it.groupId, it.artifactId, it.version) }
        inheritedPropertyConsumers.filter { consumer ->
            val managed = generateSequence(consumer.tag.parentTag) { it.parentTag }
                .any { it.localName in setOf("dependencyManagement", "pluginManagement") }
            !managed || consumer.coordinate.let { Triple(it.groupId, it.artifactId, it.version) in usedDependencies }
        }
    }
    private val propertyConsumersByTarget by lazy { propertyConsumers.groupBy { it.target } }
    private val sharedConsumers by lazy {
        val manager = MavenProjectsManager.getInstance(model.manager.project)
        val models = manager.nonIgnoredProjects.mapNotNull { pom ->
            com.intellij.psi.PsiManager.getInstance(model.manager.project).findFile(pom.file)?.let { file ->
                MavenDomUtil.getMavenDomProjectModel(file)?.let { it to pom }
            }
        }.ifEmpty { listOf(model to mavenProject) }
        val consumers = models.flatMap { (dom, pom) ->
            val analysis = MavenDependencyAnalysis(dom, pom, emptyMap())
            val local = PsiTreeUtil.findChildrenOfType(dom.xmlTag, XmlTag::class.java).mapNotNull { tag ->
                val coordinate = analysis.coordinate(tag) ?: return@mapNotNull null
                val version = tag.findFirstSubTag("version") ?: analysis.managingVersion(tag)
                val target = analysis.sharedVersionTarget(version, coordinate.version) ?: return@mapNotNull null
                target to Triple(tag, dom.xmlTag!!.containingFile, coordinate)
            }
            local + analysis.propertyConsumers.map { it.target to Triple(it.tag, dom.xmlTag!!.containingFile, it.coordinate) }
        }
        consumers.groupBy({ it.first }, { it.second })
    }
    private val sharedScopes by lazy {
        sharedConsumers.mapValues { (_, contexts) ->
            "${contexts.distinctBy { it.first to it.second }.size} version declarations across ${contexts.map { it.second }.distinct().size} POMs"
        }
    }
    private val propertyUsageFiles by lazy {
        (MavenProjectsManager.getInstance(model.manager.project).nonIgnoredProjects.mapNotNull {
            com.intellij.psi.PsiManager.getInstance(model.manager.project).findFile(it.file)
        } + sharedConsumers.values.flatten().map { it.second } + listOfNotNull(model.xmlTag?.containingFile) +
            sharedConsumers.keys.map { it.containingFile }).distinct()
    }
    private val propertyEligibility by lazy { MavenPropertyEligibility(propertyUsageFiles) }
    private val preparedSharedUpdates = mutableMapOf<XmlTag, MavenPreparedUpdate>()
    private fun prepareShared(target: XmlTag): MavenPreparedUpdate = preparedSharedUpdates.getOrPut(target) {
        val consumers = sharedConsumers[target].orEmpty()
        val blockers = mutableListOf<String>()
        blockers += propertyEligibility.blockers(target, consumers.mapNotNull { (tag, context, _) ->
            tag.findFirstSubTag("version")?.let { MavenPropertyEligibility.Consumer(it, context) }
        })
        MavenPreparedUpdate.prepare(target, consumers.map { (_, _, coordinate) ->
            val latest = updates[coordinate]?.takeUnless { isRestricted(coordinate, it) }
            MavenPreparedUpdate.Consumer(coordinate, latest,
                relocations[coordinate] ?: deprecated["${coordinate.groupId}:${coordinate.artifactId}"])
        }, blockers, verificationConsumers = consumers.map { (_, context, coordinate) ->
            MavenVerificationConsumer(context.virtualFile.path, coordinate)
        })
    }

    /** Reuse proven target ownership; equal version values alone never establish a consumer. */
    internal fun verificationConsumers(target: XmlTag, coordinate: DependencyVersion): List<MavenVerificationConsumer> =
        sharedConsumers[target].orEmpty().map { (_, context, consumer) ->
            MavenVerificationConsumer(context.virtualFile.path, consumer)
        }.ifEmpty { listOf(MavenVerificationConsumer(mavenProject.path, coordinate)) }.distinct()

    private fun scopedAction(target: XmlTag, latest: String): String {
        val scope = sharedScopes[target] ?: "shared scope requires review"
        return "Update ${target.localName} to $latest in ${target.containingFile.name} ($scope)"
    }
    private val quickFixAdapter by lazy { inspection?.adapter ?: MavenBuildSystemAdapter() }
    private val quickFixSnapshot by lazy {
        inspection?.snapshot ?: model.xmlTag?.containingFile?.virtualFile?.let {
            quickFixAdapter.snapshot(model.manager.project, it)
        }
    }
    private val resultDeclarationIds by lazy {
        inspection?.report?.candidates.orEmpty().groupBy { it.declaration.coordinate() }
            .mapValues { (_, candidates) -> candidates.map { it.declaration.id } }
    }
    private val resultGuards = mutableMapOf<Set<DependencyVersion>, () -> Boolean>()
    private val sharedResultGuards = mutableMapOf<XmlTag, () -> Boolean>()
    private fun resultGuard(coordinates: Set<DependencyVersion>): () -> Boolean {
        val result = inspection ?: return { true }
        return resultGuards.getOrPut(coordinates) {
            val ids = coordinates.flatMap { resultDeclarationIds[it].orEmpty() }.toSet()
            val lease = result.report.lease?.let { origin ->
                // Unrepresented shared consumers keep the conservative whole-observation guard.
                if (coordinates.isEmpty() || coordinates.any { resultDeclarationIds[it].isNullOrEmpty() }) origin
                else origin.forDeclarations(ids)
            }
            return@getOrPut { lease?.isCurrent() == true }
        }
    }

    private fun sharedResultGuard(target: XmlTag, coordinate: DependencyVersion): () -> Boolean =
        sharedResultGuards.getOrPut(target) {
            val consumers = sharedConsumers[target].orEmpty().map { it.third }.toSet()
            if (consumers.isEmpty() && inspection != null) {
                val lease = inspection.report.lease
                return@getOrPut { lease?.isCurrent() == true }
            } else resultGuard(consumers.ifEmpty { setOf(coordinate) })
        }

    fun coordinate(tag: XmlTag): DependencyVersion? {
        if (isProjectParent(tag)) return parentCoordinate(tag)
        val artifactKind = when {
            isProjectDependency(tag) -> MavenArtifactKind.DEPENDENCY
            isProjectPlugin(tag) -> MavenArtifactKind.PLUGIN
            else -> return null
        }
        var ancestor = tag.parentTag
        while (ancestor != null) {
            if (ancestor.localName == "profile" && ancestor.findFirstSubTag("id")?.value?.trimmedText !in
                mavenProject.activatedProfilesIds.enabledProfiles) return null
            ancestor = ancestor.parentTag
        }
        val group = tag.findFirstSubTag("groupId")?.value?.trimmedText
            ?: if (artifactKind == MavenArtifactKind.PLUGIN) "org.apache.maven.plugins" else return null
        val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText ?: return null
        val versionTag = tag.findFirstSubTag("version")
        val rawVersion = versionTag?.value?.trimmedText
        val resolvedGroup = MavenPropertyResolver.resolve(group, model)
        val resolvedArtifact = MavenPropertyResolver.resolve(artifact, model)
        val current = rawVersion?.let { MavenPropertyResolver.resolve(it, model) }
            ?: if (artifactKind == MavenArtifactKind.DEPENDENCY)
                mavenProject.findManagedDependencyVersion(resolvedGroup, resolvedArtifact)
            else mavenProject.findPlugin(resolvedGroup, resolvedArtifact)?.version
        if (current == null) return null
        if (!DependencyUpdateReport.isFixedVersion(current)) return null
        return DependencyVersion(resolvedGroup, resolvedArtifact, current, artifactKind)
    }

    /** Called only for recognized forms that coordinate() excluded; no extra native lookup. */
    fun skippedReason(tag: XmlTag): String? {
        if (!isProjectDependency(tag) && !isProjectPlugin(tag) && !isProjectParent(tag)) return null
        if (generateSequence(tag.parentTag) { it.parentTag }.any {
                it.localName == "profile" && it.findFirstSubTag("id")?.value?.trimmedText !in
                    mavenProject.activatedProfilesIds.enabledProfiles
            }) return "Inactive Maven profile is outside the imported resolution context."
        if (isProjectParent(tag)) {
            val group = tag.findFirstSubTag("groupId")?.value?.trimmedText
            val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText
            if (MavenProjectsManager.getInstance(tag.project).projects.any {
                    it.mavenId.groupId == group && it.mavenId.artifactId == artifact
                }) return "Reactor parent is versioned with this workspace."
        }
        return "Missing, unresolved or non-fixed Maven coordinates/version need manual review."
    }

    private fun parentCoordinate(tag: XmlTag): DependencyVersion? {
        val group = tag.findFirstSubTag("groupId")?.value?.trimmedText ?: return null
        val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText ?: return null
        val version = tag.findFirstSubTag("version")?.value?.trimmedText ?: return null
        if (!DependencyUpdateReport.isFixedVersion(version)) return null
        // Workspace parents are versioned with the reactor, so their children must not be bumped to a release.
        if (MavenProjectsManager.getInstance(tag.project).projects.any {
                it.mavenId.groupId == group && it.mavenId.artifactId == artifact }) return null
        return DependencyVersion(group, artifact, version, MavenArtifactKind.PARENT)
    }

    fun problem(tag: XmlTag): DependencyProblem? {
        val coordinate = coordinate(tag) ?: return null
        val latest = updates[coordinate]?.takeUnless { isRestricted(coordinate, it) }
        val id = "${coordinate.groupId}:${coordinate.artifactId}"
        val label = when (coordinate.artifactKind) {
            MavenArtifactKind.PLUGIN -> "Maven plugin"
            MavenArtifactKind.PARENT -> "Parent POM"
            MavenArtifactKind.DEPENDENCY -> "Dependency"
        }
        val notice = relocations[coordinate]?.let { "$label $id:${coordinate.version} has been relocated to $it" }
            ?: deprecated[id]?.let { "$label $id is marked deprecated: $it" }
        if (latest == null && notice == null) return null
        val kind = if (notice != null) VersionChangeKind.DEPRECATED else MavenVersionSemantics.between(coordinate.version, latest!!)
        val versionTag = tag.findFirstSubTag("version")
        val rawVersion = versionTag?.value?.trimmedText
        val current = coordinate.version
        val target = when {
            notice != null -> null // Replacements need a coordinate/API review, not a version-only edit.
            versionTag == null -> managingVersion(tag)?.let { versionProperties.target(it, current) }
            rawVersion == current -> versionTag
            else -> versionPropertyTarget(versionTag, rawVersion, current)
        }
        return DependencyProblem(coordinate, latest, versionTag ?: tag.findFirstSubTag("artifactId") ?: tag,
            target, kind, kind.severity(options), notice)
    }

    internal fun versionPropertyTarget(versionTag: XmlTag, raw: String?, current: String): XmlTag? =
        findLocalVersionProperty(versionTag, raw, current, mavenProject.activatedProfilesIds.enabledProfiles)

    internal fun bulkPropertyScopeBlockers(target: XmlTag, selected: Map<PsiFile, MavenDependencyAnalysis>, replacement: String?): List<String> {
        if (target.parentTag?.localName != "properties") return emptyList()
        val contexts = sharedConsumers[target].orEmpty()
        val authorized = contexts.isNotEmpty() && contexts.all { (_, context, coordinate) ->
            val analysis = selected[context] ?: return@all false
            val latest = analysis.updates[coordinate] ?: return@all false
            latest == replacement && !analysis.isRestricted(coordinate, latest) &&
                coordinate !in analysis.relocations && "${coordinate.groupId}:${coordinate.artifactId}" !in analysis.deprecated
        }
        return if (authorized) emptyList() else listOf("${target.localName}: consumers outside the selected results need review")
    }

    internal fun bulkProblem(problem: DependencyProblem): DependencyProblem {
        if (problem.target != null || problem.notice != null || problem.anchor.localName != "version") return problem
        val target = findLocalVersionPropertyChain(problem.anchor, problem.anchor.value.trimmedText,
            problem.coordinate.version, mavenProject.activatedProfilesIds.enabledProfiles)
        return problem.copy(target = target)
    }

    internal fun propertyProblems(): List<DependencyProblem> = propertyConsumers.mapNotNull { consumer ->
        val problem = problem(consumer.tag) ?: return@mapNotNull null
        problem.copy(anchor = consumer.target, target = consumer.target.takeIf { problem.notice == null })
    }.distinctBy { it.anchor to it.coordinate }

    internal fun propertyQuickFixes(property: XmlTag): Array<LocalQuickFix> {
        val consumers = propertyConsumersByTarget[property].orEmpty()
        val problems = consumers.map { problem(it.tag) }
        val latest = problems.map { it?.latest }.distinct().singleOrNull() ?: return emptyArray()
        val prepared = prepareShared(property)
        if (problems.any { it?.notice != null } || prepared.edit == null || prepared.replacement != latest) return emptyArray()
        val snapshot = quickFixSnapshot ?: return emptyArray()
        val resultIsCurrent = resultGuard(consumers.map { it.coordinate }.toSet())
        return arrayOf(UpdateDependencyVersionFix(property, latest,
            isCurrent = { resultIsCurrent() && property.project.service<VersionCheckerSettings>().state.enabled &&
                policy.isCurrent(property.project) })
            .withPreparedUpdate(prepared)
            .withInputValidation { quickFixAdapter.prepareEditValidation(property.project, listOf(snapshot)) }
            .withActionName { scopedAction(property, latest) })
    }

    /** Creating a local owner affects inherited declarations in this child, not parent/sibling contexts. */
    private fun inheritedPropertyFix(target: XmlTag, latest: String): LocalQuickFix? {
        val root = model.xmlTag ?: return null
        val consumers = inheritedPropertyConsumers.filter { it.target == target }
        if (consumers.isEmpty()) return null
        fun managed(tag: XmlTag) = generateSequence(tag.parentTag) { it.parentTag }
            .any { it.localName in setOf("dependencyManagement", "pluginManagement") }
        val direct = PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
            .filter { isProjectDependency(it) && !managed(it) }
        val directKeys = direct.map { tag ->
            val group = tag.findFirstSubTag("groupId")?.value?.trimmedText ?: return null
            val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText ?: return null
            val resolved = MavenPropertyResolver.resolve(group, model) to MavenPropertyResolver.resolve(artifact, model)
            if ('$' in resolved.first || '$' in resolved.second) return null
            resolved
        }.toSet()
        val usedKeys = directKeys + mavenProject.dependencies.map { it.groupId to it.artifactId } +
            consumers.filter { !managed(it.tag) }.map { it.coordinate.groupId to it.coordinate.artifactId }
        val actual = mavenProject.dependencies.map { Triple(it.groupId, it.artifactId, it.version) } +
            direct.mapNotNull(::coordinate).map { Triple(it.groupId, it.artifactId, it.version) }
        if (consumers.any { consumer -> consumer.coordinate.let { coordinate ->
                coordinate.artifactKind == MavenArtifactKind.DEPENDENCY && actual.any {
                    it.first == coordinate.groupId && it.second == coordinate.artifactId && it.third != coordinate.version
                }
            } }) return null // An explicit/resolved override needs a separate context review.
        val checked = consumers.filterNot { consumer ->
            val tag = consumer.tag
            val ordinaryManagement = isProjectDependency(tag) && generateSequence(tag.parentTag) { it.parentTag }
                .any { it.localName == "dependencyManagement" } &&
                tag.findFirstSubTag("scope")?.value?.trimmedText.let { it == null || (it != "import" && '$' !in it) } &&
                tag.findFirstSubTag("type")?.value?.trimmedText.let { it == null || (it != "pom" && '$' !in it) }
            ordinaryManagement && (consumer.coordinate.groupId to consumer.coordinate.artifactId) !in usedKeys
        }
        if (checked.isEmpty()) return null
        val uncheckedManagement = consumers.map { it.coordinate }.toSet() - checked.map { it.coordinate }.toSet()
        val descendants = MavenProjectsManager.getInstance(root.project).projects.filter { candidate ->
            if (candidate.file == mavenProject.file) false else {
                val file = com.intellij.psi.PsiManager.getInstance(root.project).findFile(candidate.file)
                val context = file?.let(MavenDomUtil::getMavenDomProjectModel)
                context != null && MavenDomProjectProcessorUtils.collectParentProjects(context).any { it.xmlTag == root }
            }
        }
        // Until every descendant context can be previewed and checked, avoid implicitly changing a reactor subtree.
        if (descendants.isNotEmpty()) return null
        val blockers = MavenPropertyEligibility((MavenDomProjectProcessorUtils.collectParentProjects(model) + model)
            .mapNotNull { it.xmlTag?.containingFile }).blockers(target,
            consumers.mapNotNull { consumer -> consumer.tag.findFirstSubTag("version")?.let {
                MavenPropertyEligibility.Consumer(it, root.containingFile)
            } })
        val prepared = MavenPreparedUpdate.prepare(target, checked.map { consumer ->
            val coordinate = consumer.coordinate
            MavenPreparedUpdate.Consumer(coordinate, updates[coordinate]?.takeUnless {
                isRestricted(coordinate, it)
            }, relocations[coordinate] ?: deprecated["${coordinate.groupId}:${coordinate.artifactId}"])
        }, blockers)
        if (prepared.edit == null || prepared.replacement != latest) return null
        val snapshot = quickFixSnapshot ?: return null
        val resultIsCurrent = resultGuard(checked.map { it.coordinate }.toSet())
        val provenance = (MavenDomProjectProcessorUtils.collectParentProjects(model) + model).mapNotNull { source ->
            source.xmlTag?.let { com.intellij.psi.SmartPointerManager.createPointer(it) to it.text }
        }
        val provenancePaths = provenance.mapNotNull { it.first.element?.containingFile?.virtualFile?.path }.toSet()
        return DefineInheritedMavenPropertyFix(root, target.localName, latest,
            checked.map { "${it.coordinate.groupId}:${it.coordinate.artifactId}" }.distinct(),
            uncheckedManagement.map { "${it.groupId}:${it.artifactId}" },
            verificationCoordinates = checked.map { it.coordinate }.distinct(),
            isCurrent = { resultIsCurrent() && provenance.all { (pointer, text) -> pointer.element?.text == text } &&
                root.project.service<VersionCheckerSettings>().state.enabled &&
                policy.isCurrent(root.project) })
            .withInputValidation {
                val standard = quickFixAdapter.prepareEditValidation(root.project, listOf(snapshot))
                val sources = io.github.khopland.versionchecker.core.prepareSavedBuildInputValidation(root.project, provenancePaths)
                io.github.khopland.versionchecker.core.CapturedEditInputs { standard.isCurrent() && sources.isCurrent() }
            }
    }

    internal fun inheritedPropertyProblems(): List<DependencyProblem> {
        val anchor = model.xmlTag?.findFirstSubTag("parent") ?: return emptyList()
        val localCoordinates = model.xmlTag?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }
            .orEmpty().mapNotNull(::coordinate).toSet()
        return observedInheritedPropertyConsumers.filter { it.tag.containingFile != anchor.containingFile && it.coordinate !in localCoordinates }
            .mapNotNull { consumer -> problem(consumer.tag)?.copy(anchor = anchor, target = null) }
            .distinctBy { it.coordinate }
    }

    internal fun inheritedPropertyQuickFixes(problem: DependencyProblem): Array<LocalQuickFix> {
        val latest = problem.latest ?: return emptyArray()
        if (problem.notice != null) return emptyArray()
        val target = inheritedPropertyConsumers.firstOrNull { it.coordinate == problem.coordinate }?.target ?: return emptyArray()
        return listOfNotNull(inheritedPropertyFix(target, latest)).toTypedArray()
    }

    fun quickFixes(tag: XmlTag, problem: DependencyProblem): Array<LocalQuickFix> {
        val latest = problem.latest ?: return emptyArray()
        if (problem.notice != null) return emptyArray()
        val project = tag.project
        val adapter = quickFixAdapter
        val snapshot = quickFixSnapshot ?: return emptyArray()
        fun guard(resultIsCurrent: () -> Boolean): () -> Boolean = {
            resultIsCurrent() && project.service<VersionCheckerSettings>().state.enabled &&
                policy.isCurrent(project)
        }
        problem.target?.let {
            val shared = it.parentTag?.localName == "properties" || it.parentTag?.parentTag?.parentTag?.localName == "dependencyManagement"
            val prepared = if (shared) prepareShared(it) else MavenPreparedUpdate.prepare(it,
                listOf(MavenPreparedUpdate.Consumer(problem.coordinate, problem.latest, problem.notice)),
                verificationConsumers = listOf(MavenVerificationConsumer(mavenProject.path, problem.coordinate)))
            if (prepared.edit == null || prepared.replacement != latest) return emptyArray()
            val fix = UpdateDependencyVersionFix(it, latest,
                isCurrent = guard(if (shared) sharedResultGuard(it, problem.coordinate) else resultGuard(setOf(problem.coordinate))))
                .withPreparedUpdate(prepared)
                .withInputValidation { adapter.prepareEditValidation(project, listOf(snapshot)) }
            if (shared) fix.withActionName { scopedAction(it, latest) }
            return arrayOf(fix)
        }
        if (problem.coordinate.artifactKind != MavenArtifactKind.DEPENDENCY) return emptyArray()
        val version = tag.findFirstSubTag("version")
        // Composite expressions and ranges retain their existing manual-review behavior.
        if (version != null && versionPropertyName(version.value.trimmedText) == null) return emptyArray()
        val fixes = mutableListOf<LocalQuickFix>(OverrideDependencyVersionFix(tag, latest, guard(resultGuard(setOf(problem.coordinate))))
            .withVerificationConsumer(MavenVerificationConsumer(mavenProject.path, problem.coordinate))
            .withInputValidation { adapter.prepareEditValidation(project, listOf(snapshot)) })
        val target = sharedVersionTarget(version ?: managingVersion(tag), problem.coordinate.version)
        val prepared = target?.let(::prepareShared)
        if (target != null && prepared?.edit != null && prepared.replacement == latest) {
            val file = target.containingFile.virtualFile
            val base = project.basePath?.trimEnd('/')
            val path = if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
            val location = if (file == tag.containingFile.virtualFile) "managed" else "parent"
            fixes += UpdateDependencyVersionFix(target, latest, isCurrent = guard(sharedResultGuard(target, problem.coordinate)))
                .withPreparedUpdate(prepared)
                .withInputValidation { adapter.prepareEditValidation(project, listOf(snapshot)) }.withActionName {
                "Update $location version to $latest in $path (${sharedScopes[target] ?: "shared scope requires review"})"
            }
        }
        val inheritedTarget = (version ?: managingVersion(tag))?.let { versionProperties.inheritedTarget(it, problem.coordinate.version) }
        inheritedTarget?.let { inheritedPropertyFix(it, latest) }?.let(fixes::add)
        return fixes.toTypedArray()
    }

    private fun managingVersion(tag: XmlTag): XmlTag? {
        val dependency = DomManager.getDomManager(tag.project).getDomElement(tag) as? MavenDomDependency ?: return null
        return MavenDomProjectProcessorUtils.searchManagingDependency(dependency)?.version?.xmlTag
    }

    private fun sharedVersionTarget(version: XmlTag?, current: String): XmlTag? {
        var target = version ?: return null
        val project = target.project
        val manager = MavenProjectsManager.getInstance(project)
        val sourceFiles = (MavenDomProjectProcessorUtils.collectParentProjects(model) + model)
            .mapNotNull { it.xmlTag?.containingFile?.virtualFile }.toSet()
        val visited = mutableSetOf<XmlTag>()
        while (visited.add(target)) {
            val file = target.containingFile.virtualFile
            if (file !in sourceFiles || manager.findProject(file)?.let { manager.isIgnored(it) } != false) return null
            val raw = target.value.trimmedText
            if (raw == current) return target
            val property = versionPropertyName(raw) ?: return null
            val root = model.xmlTag ?: return null
            val active = mavenProject.activatedProfilesIds.enabledProfiles
            val locallyDeclared = root.findFirstSubTag("properties")?.findFirstSubTag(property) != null ||
                root.findFirstSubTag("profiles")?.subTags.orEmpty().any {
                    it.findFirstSubTag("id")?.value?.trimmedText in active &&
                        it.findFirstSubTag("properties")?.findFirstSubTag(property) != null
                }
            val owner = if (locallyDeclared) findLocalVersionPropertyOwner(root, property, active)
                else MavenDomProjectProcessorUtils.searchProperty(property, model, project)?.let { found ->
                    // The DOM lookup can return the default property even if its source POM
                    // has an active override. Never offer that default as a shared edit.
                    findLocalVersionPropertyOwner(found, property).takeIf { it == found }
                }
            target = owner ?: return null
        }
        return null
    }

    fun problems(file: PsiFile): List<DependencyProblem> =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(::problem)

    companion object {
        fun forFile(file: PsiFile): MavenDependencyAnalysis? {
            if (!file.project.service<VersionCheckerSettings>().state.enabled) return null
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return null
            val mavenProject = MavenProjectsManager.getInstance(file.project).findProject(file.virtualFile ?: return null) ?: return null
            val inspection = file.project.service<MavenVersionCheckService>().inspection(mavenProject) ?: return null
            return MavenDependencyAnalysis(model, mavenProject, inspection.report.mavenUpdates(),
                file.project.service<VersionCheckerSettings>().state, inspection.report.mavenRelocations(), inspection)
        }
    }
}
