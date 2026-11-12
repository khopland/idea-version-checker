package io.github.khopland.versionchecker.core

/** What one version needs beside it: the ranges of its npm peer dependencies by package name, and of Node.js in `engines`. */
class Needs(val peers: Map<String, String> = emptyMap(), val node: String? = null)

/**
 * One build file whose dependencies are installed together, such as a `package.json`, on Node.js [node] when the project
 * says which. Its declarations share one instance. [satisfies] says whether a version is in a range, or null when it cannot tell.
 */
class Install(val node: String? = null, val satisfies: (range: String, version: String) -> Boolean?)

/** [dependent] at [dependentVersion] needs [on] in [range], and the project would have [have]. */
data class Conflict(val dependent: String, val dependentVersion: String, val on: String, val range: String, val have: String,
                    /** A newer version of [dependent] that accepts [have]. */
                    val resolvedBy: String? = null) {
    val text get() = "$dependent $dependentVersion needs $on $range, not $have"

    /** The other side, seen from [declaration]. */
    fun with(declaration: Declaration) = if (dependent == declaration.name) on else dependent

    /** What an update of [declaration] to [version] runs into. */
    fun text(declaration: Declaration, version: String) =
        if (dependent == declaration.name) "$version needs $on $range, not $have"
        else "$version breaks $dependent $dependentVersion, which needs $range" + (resolvedBy?.let { " ($dependent $it accepts it)" } ?: "")
}

object Conflicts {
    const val NODE = "Node.js"

    /**
     * The conflicts an update of [declaration] to [version] adds among the declarations installed with it. [versions] says
     * which version each of the others has by then, by default the one it has now. Conflicts the project already has are left out.
     */
    fun of(declaration: Declaration, version: String, outcomes: Map<Declaration, Outcome>,
           versions: (Declaration) -> String = { it.checked }): List<Conflict> {
        val install = declaration.install ?: return emptyList()
        fun needs(of: Declaration, at: String) = (outcomes[of] as? Outcome.Updates)?.needs?.get(at)
        val before = needs(declaration, declaration.checked)
        val after = needs(declaration, version)
        val others = outcomes.keys.filter { it.install === install && it.name != declaration.name }.distinctBy { it.name }
        val result = mutableListOf<Conflict>()
        for (other in others) {
            val has = versions(other)
            val range = after?.peers?.get(other.name)
            // The install moves a peer as far as its declared range allows: `^18.2.0` gives 18.3.1 to a package that needs it.
            val reachable = (outcomes[other] as? Outcome.Updates)?.targets?.takeIf { has == other.checked }?.let { targets -> other.floats?.let { targets[it] } }
            if (range != null && install.satisfies(range, has) == false && reachable?.let { install.satisfies(range, it) } != true &&
                before?.peers?.get(other.name)?.let { install.satisfies(it, has) } != false)
                result += Conflict(declaration.name, version, other.name, range, has)
            val theirs = needs(other, has)?.peers?.get(declaration.name) ?: continue
            if (install.satisfies(theirs, version) != false || install.satisfies(theirs, declaration.checked) == false) continue
            val newer = (outcomes[other] as? Outcome.Updates)?.let { updates -> UpdateKind.entries.mapNotNull { updates.targets[it] } }.orEmpty()
                .firstOrNull { Versions.compare(it, has) > 0 && needs(other, it)?.peers?.get(declaration.name)?.let { r -> install.satisfies(r, version) } != false }
            result += Conflict(other.name, has, declaration.name, theirs, version, newer)
        }
        val node = install.node
        val range = after?.node
        if (node != null && range != null && install.satisfies(range, node) == false && before?.node?.let { install.satisfies(it, node) } != false)
            result += Conflict(declaration.name, version, NODE, range, node)
        return result
    }

    /** The conflicts of each version the editor offers for [declaration]. Versions without any are left out. */
    fun offered(declaration: Declaration, outcomes: Map<Declaration, Outcome>): Map<String, List<Conflict>> {
        val targets = (outcomes[declaration] as? Outcome.Updates)?.targets ?: return emptyMap()
        if (declaration.install == null) return emptyMap()
        return UpdateKind.entries.mapNotNull { targets[it] }.distinct().associateWith { of(declaration, it, outcomes) }.filterValues { it.isNotEmpty() }
    }
}
