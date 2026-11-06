package io.github.khopland.versionchecker.vuln

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/** The CVSS 3.x base score of a vector such as `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`, and its rating. */
internal object Cvss {
    fun score(vector: String): Double? {
        if (!vector.startsWith("CVSS:3.")) return null
        val metrics = vector.split('/').drop(1).associate { it.substringBefore(':') to it.substringAfter(':') }
        val changed = metrics["S"] == "C"
        val av = mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.2)[metrics["AV"]] ?: return null
        val ac = mapOf("L" to 0.77, "H" to 0.44)[metrics["AC"]] ?: return null
        val pr = mapOf("N" to 0.85, "L" to if (changed) 0.68 else 0.62, "H" to if (changed) 0.5 else 0.27)[metrics["PR"]] ?: return null
        val ui = mapOf("N" to 0.85, "R" to 0.62)[metrics["UI"]] ?: return null
        val cia = listOf("C", "I", "A").map { mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)[metrics[it]] ?: return null }
        val iss = 1 - cia.fold(1.0) { product, value -> product * (1 - value) }
        val impact = if (changed) 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02).pow(15) else 6.42 * iss
        if (impact <= 0) return 0.0
        val exploitability = 8.22 * av * ac * pr * ui
        return roundUp(min(if (changed) 1.08 * (impact + exploitability) else impact + exploitability, 10.0))
    }

    /** The GitHub advisory names for the CVSS ratings. */
    fun rating(vector: String): String? = score(vector)?.let(::rating)

    fun rating(score: Double): String? = when {
        score >= 9.0 -> "CRITICAL"
        score >= 7.0 -> "HIGH"
        score >= 4.0 -> "MODERATE"
        score > 0.0 -> "LOW"
        else -> null
    }

    /** Round up to one decimal, as the CVSS 3.1 specification does, without floating point surprises. */
    private fun roundUp(value: Double): Double {
        val scaled = Math.round(value * 100_000)
        return if (scaled % 10_000 == 0L) scaled / 100_000.0 else (ceil(scaled / 10_000.0)) / 10.0
    }
}
