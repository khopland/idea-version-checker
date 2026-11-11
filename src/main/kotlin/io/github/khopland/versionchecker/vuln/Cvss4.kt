package io.github.khopland.versionchecker.vuln

/**
 * The CVSS 4.0 score of a vector such as `CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N`, with any threat,
 * environmental and supplemental metrics after the base ones.
 *
 * A port of `cvss_score.js` in FIRST's reference calculator, https://github.com/FIRSTdotorg/cvss-v4-calculator
 * (commit c5b0d40; Copyright FIRST.ORG, Inc., Red Hat, and contributors; BSD-2-Clause): the vector maps to a macrovector,
 * whose score comes from [SCORES], and the score drops by how far the vector is from the most severe vectors of that macrovector.
 */
internal object Cvss4 {
    fun score(vector: String): Double? {
        val selected = parse(vector) ?: return null
        // Absent threat and environmental metrics take their worst case; modified metrics replace base ones.
        fun m(metric: String): String {
            val value = selected[metric] ?: "X"
            if (metric == "E" && value == "X") return "A"
            if (metric in listOf("CR", "IR", "AR") && value == "X") return "H"
            return selected["M$metric"]?.takeIf { it != "X" } ?: value
        }
        if (listOf("VC", "VI", "VA", "SC", "SI", "SA").all { m(it) == "N" }) return 0.0

        val eq1 = when {
            m("AV") == "N" && m("PR") == "N" && m("UI") == "N" -> 0
            (m("AV") == "N" || m("PR") == "N" || m("UI") == "N") && m("AV") != "P" -> 1
            else -> 2
        }
        val eq2 = if (m("AC") == "L" && m("AT") == "N") 0 else 1
        val eq3 = when {
            m("VC") == "H" && m("VI") == "H" -> 0
            m("VC") == "H" || m("VI") == "H" || m("VA") == "H" -> 1
            else -> 2
        }
        val eq4 = when {
            m("MSI") == "S" || m("MSA") == "S" -> 0
            m("SC") == "H" || m("SI") == "H" || m("SA") == "H" -> 1
            else -> 2
        }
        val eq5 = when (m("E")) { "A" -> 0; "P" -> 1; else -> 2 }
        val eq6 = if (m("CR") == "H" && m("VC") == "H" || m("IR") == "H" && m("VI") == "H" || m("AR") == "H" && m("VA") == "H") 0 else 1
        val value = SCORES["$eq1$eq2$eq3$eq4$eq5$eq6"] ?: return null
        fun lookup(vararg eq: Int) = SCORES[eq.joinToString("")] ?: Double.NaN

        // The score of the next lower macrovector in each equivalence class, NaN where there is none.
        val lowerEq3Eq6 = when {
            eq3 == 0 && eq6 == 0 -> lookup(eq1, eq2, eq3, eq4, eq5, eq6 + 1).let { left ->
                lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6).let { right -> if (left > right) left else right }
            }
            eq3 == 1 && eq6 == 0 -> lookup(eq1, eq2, eq3, eq4, eq5, eq6 + 1)
            eq3 == 2 -> lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6 + 1)
            else -> lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6)
        }
        val lower = listOf(
            lookup(eq1 + 1, eq2, eq3, eq4, eq5, eq6), lookup(eq1, eq2 + 1, eq3, eq4, eq5, eq6), lowerEq3Eq6,
            lookup(eq1, eq2, eq3, eq4 + 1, eq5, eq6), lookup(eq1, eq2, eq3, eq4, eq5 + 1, eq6),
        )

        // The severity distance from the first most severe vector of the macrovector that is at least as severe in every metric.
        val maxes = MAX_EQ1[eq1].flatMap { a ->
            MAX_EQ2[eq2].flatMap { b -> MAX_EQ3_EQ6.getValue("$eq3$eq6").flatMap { c -> MAX_EQ4[eq4].map { d -> "$a/$b/$c/$d" } } }
        }
        var distance = emptyMap<String, Double>()
        for (max in maxes) {
            val metrics = max.split('/').associate { it.substringBefore(':') to it.substringAfter(':') }
            distance = LEVELS.mapValues { (metric, levels) -> levels.getValue(m(metric)) - levels.getValue(metrics.getValue(metric)) }
            if (distance.values.none { it < 0 }) break
        }
        fun sum(vararg metrics: String) = metrics.fold(0.0) { total, metric -> total + distance.getValue(metric) }
        val current = listOf(sum("AV", "PR", "UI"), sum("AC", "AT"), sum("VC", "VI", "VA", "CR", "IR", "AR"), sum("SC", "SI", "SA"), 0.0)
        val depth = listOf(MAX_SEVERITY_EQ1[eq1], MAX_SEVERITY_EQ2[eq2], MAX_SEVERITY_EQ3_EQ6.getValue("$eq3$eq6"), MAX_SEVERITY_EQ4[eq4], 1)

        // The score drops by the mean of the proportional distances, over the classes that have a lower macrovector.
        val existing = lower.indices.filter { !(value - lower[it]).isNaN() }
        val normalized = lower.indices.map { if (it in existing) (value - lower[it]) * (current[it] / (depth[it] * 0.1)) else 0.0 }
        val mean = if (existing.isEmpty()) 0.0 else normalized.fold(0.0, Double::plus) / existing.size
        return Math.round((value - mean).coerceIn(0.0, 10.0) * 10) / 10.0
    }

    /** The metrics of a vector, or null unless it has every base metric, in the specification's order, with valid values. */
    private fun parse(vector: String): Map<String, String>? {
        val parts = vector.split('/')
        if (parts.first() != "CVSS:4.0") return null
        val order = ORDER.entries.toList()
        var next = 0
        return parts.drop(1).associate { part ->
            val (key, value) = part.split(':').takeIf { it.size == 2 } ?: return null
            // Later metrics may be left out; base ones may not.
            while (order.getOrNull(next)?.key != key) if (next < BASE_METRICS || ++next >= order.size) return null
            if (value !in order[next++].value) return null
            key to value
        }.takeIf { it.size >= BASE_METRICS }
    }

    private const val BASE_METRICS = 11

    /** Each metric with its values, in the order a vector lists them (`metrics.js`). */
    private val ORDER = """
        AV:NALP AC:LH AT:NP PR:NLH UI:NPA VC:HLN VI:HLN VA:HLN SC:HLN SI:HLN SA:HLN E:XAPU CR:XHML IR:XHML AR:XHML
        MAV:XNALP MAC:XLH MAT:XNP MPR:XNLH MUI:XNPA MVC:XHLN MVI:XHLN MVA:XHLN MSC:XHLN MSI:XSHLN MSA:XSHLN
        S:XNP AU:XNY R:XAUI V:XDC RE:XLMH U:X,Clear,Green,Amber,Red
    """.trim().split(Regex("\\s+")).associate { entry ->
        val values = entry.substringAfter(':')
        entry.substringBefore(':') to if (',' in values) values.split(',') else values.map(Char::toString)
    }

    /** The severity level of each value; lower is more severe (`cvss_score.js`). */
    private val LEVELS = mapOf(
        "AV" to mapOf("N" to 0.0, "A" to 0.1, "L" to 0.2, "P" to 0.3), "PR" to mapOf("N" to 0.0, "L" to 0.1, "H" to 0.2),
        "UI" to mapOf("N" to 0.0, "P" to 0.1, "A" to 0.2), "AC" to mapOf("L" to 0.0, "H" to 0.1), "AT" to mapOf("N" to 0.0, "P" to 0.1),
        "VC" to mapOf("H" to 0.0, "L" to 0.1, "N" to 0.2), "VI" to mapOf("H" to 0.0, "L" to 0.1, "N" to 0.2), "VA" to mapOf("H" to 0.0, "L" to 0.1, "N" to 0.2),
        "SC" to mapOf("H" to 0.1, "L" to 0.2, "N" to 0.3), "SI" to mapOf("S" to 0.0, "H" to 0.1, "L" to 0.2, "N" to 0.3),
        "SA" to mapOf("S" to 0.0, "H" to 0.1, "L" to 0.2, "N" to 0.3), "CR" to mapOf("H" to 0.0, "M" to 0.1, "L" to 0.2),
        "IR" to mapOf("H" to 0.0, "M" to 0.1, "L" to 0.2), "AR" to mapOf("H" to 0.0, "M" to 0.1, "L" to 0.2),
    )

    /** The most severe vectors of each level of each equivalence class (`max_composed.js`; EQ5 only sets E, which has no distance). */
    private val MAX_EQ1 = listOf(listOf("AV:N/PR:N/UI:N"), listOf("AV:A/PR:N/UI:N", "AV:N/PR:L/UI:N", "AV:N/PR:N/UI:P"), listOf("AV:P/PR:N/UI:N", "AV:A/PR:L/UI:P"))
    private val MAX_EQ2 = listOf(listOf("AC:L/AT:N"), listOf("AC:H/AT:N", "AC:L/AT:P"))
    private val MAX_EQ3_EQ6 = mapOf(
        "00" to listOf("VC:H/VI:H/VA:H/CR:H/IR:H/AR:H"),
        "01" to listOf("VC:H/VI:H/VA:L/CR:M/IR:M/AR:H", "VC:H/VI:H/VA:H/CR:M/IR:M/AR:M"),
        "10" to listOf("VC:L/VI:H/VA:H/CR:H/IR:H/AR:H", "VC:H/VI:L/VA:H/CR:H/IR:H/AR:H"),
        "11" to listOf("VC:L/VI:H/VA:L/CR:H/IR:M/AR:H", "VC:L/VI:H/VA:H/CR:H/IR:M/AR:M", "VC:H/VI:L/VA:H/CR:M/IR:H/AR:M",
            "VC:H/VI:L/VA:L/CR:M/IR:H/AR:H", "VC:L/VI:L/VA:H/CR:H/IR:H/AR:M"),
        "21" to listOf("VC:L/VI:L/VA:L/CR:H/IR:H/AR:H"),
    )
    private val MAX_EQ4 = listOf(listOf("SC:H/SI:S/SA:S"), listOf("SC:H/SI:H/SA:H"), listOf("SC:L/SI:L/SA:L"))

    /** The depth of each macrovector, in steps of 0.1 and plus one (`max_severity.js`). */
    private val MAX_SEVERITY_EQ1 = listOf(1, 4, 5)
    private val MAX_SEVERITY_EQ2 = listOf(1, 2)
    private val MAX_SEVERITY_EQ3_EQ6 = mapOf("00" to 7, "01" to 6, "10" to 8, "11" to 8, "21" to 10)
    private val MAX_SEVERITY_EQ4 = listOf(6, 5, 4)

    /** The score of each of the 270 macrovectors, EQ1 to EQ6 (`cvss_lookup.js`). */
    private val SCORES = """
        000000:10 000001:9.9 000010:9.8 000011:9.5 000020:9.5 000021:9.2 000100:10 000101:9.6 000110:9.3 000111:8.7 000120:9.1 000121:8.1 000200:9.3 000201:9 000210:8.9 000211:8 000220:8.1 000221:6.8
        001000:9.8 001001:9.5 001010:9.5 001011:9.2 001020:9 001021:8.4 001100:9.3 001101:9.2 001110:8.9 001111:8.1 001120:8.1 001121:6.5 001200:8.8 001201:8 001210:7.8 001211:7 001220:6.9 001221:4.8
        002001:9.2 002011:8.2 002021:7.2 002101:7.9 002111:6.9 002121:5 002201:6.9 002211:5.5 002221:2.7
        010000:9.9 010001:9.7 010010:9.5 010011:9.2 010020:9.2 010021:8.5 010100:9.5 010101:9.1 010110:9 010111:8.3 010120:8.4 010121:7.1 010200:9.2 010201:8.1 010210:8.2 010211:7.1 010220:7.2 010221:5.3
        011000:9.5 011001:9.3 011010:9.2 011011:8.5 011020:8.5 011021:7.3 011100:9.2 011101:8.2 011110:8 011111:7.2 011120:7 011121:5.9 011200:8.4 011201:7 011210:7.1 011211:5.2 011220:5 011221:3
        012001:8.6 012011:7.5 012021:5.2 012101:7.1 012111:5.2 012121:2.9 012201:6.3 012211:2.9 012221:1.7
        100000:9.8 100001:9.5 100010:9.4 100011:8.7 100020:9.1 100021:8.1 100100:9.4 100101:8.9 100110:8.6 100111:7.4 100120:7.7 100121:6.4 100200:8.7 100201:7.5 100210:7.4 100211:6.3 100220:6.3 100221:4.9
        101000:9.4 101001:8.9 101010:8.8 101011:7.7 101020:7.6 101021:6.7 101100:8.6 101101:7.6 101110:7.4 101111:5.8 101120:5.9 101121:5 101200:7.2 101201:5.7 101210:5.7 101211:5.2 101220:5.2 101221:2.5
        102001:8.3 102011:7 102021:5.4 102101:6.5 102111:5.8 102121:2.6 102201:5.3 102211:2.1 102221:1.3
        110000:9.5 110001:9 110010:8.8 110011:7.6 110020:7.6 110021:7 110100:9 110101:7.7 110110:7.5 110111:6.2 110120:6.1 110121:5.3 110200:7.7 110201:6.6 110210:6.8 110211:5.9 110220:5.2 110221:3
        111000:8.9 111001:7.8 111010:7.6 111011:6.7 111020:6.2 111021:5.8 111100:7.4 111101:5.9 111110:5.7 111111:5.7 111120:4.7 111121:2.3 111200:6.1 111201:5.2 111210:5.7 111211:2.9 111220:2.4 111221:1.6
        112001:7.1 112011:5.9 112021:3 112101:5.8 112111:2.6 112121:1.5 112201:2.3 112211:1.3 112221:0.6
        200000:9.3 200001:8.7 200010:8.6 200011:7.2 200020:7.5 200021:5.8 200100:8.6 200101:7.4 200110:7.4 200111:6.1 200120:5.6 200121:3.4 200200:7 200201:5.4 200210:5.2 200211:4 200220:4 200221:2.2
        201000:8.5 201001:7.5 201010:7.4 201011:5.5 201020:6.2 201021:5.1 201100:7.2 201101:5.7 201110:5.5 201111:4.1 201120:4.6 201121:1.9 201200:5.3 201201:3.6 201210:3.4 201211:1.9 201220:1.9 201221:0.8
        202001:6.4 202011:5.1 202021:2 202101:4.7 202111:2.1 202121:1.1 202201:2.4 202211:0.9 202221:0.4
        210000:8.8 210001:7.5 210010:7.3 210011:5.3 210020:6 210021:5 210100:7.3 210101:5.5 210110:5.9 210111:4 210120:4.1 210121:2 210200:5.4 210201:4.3 210210:4.5 210211:2.2 210220:2 210221:1.1
        211000:7.5 211001:5.5 211010:5.8 211011:4.5 211020:4 211021:2.1 211100:6.1 211101:5.1 211110:4.8 211111:1.8 211120:2 211121:0.9 211200:4.6 211201:1.8 211210:1.7 211211:0.7 211220:0.8 211221:0.2
        212001:5.3 212011:2.4 212021:1.4 212101:2.4 212111:1.2 212121:0.5 212201:1 212211:0.3 212221:0.1
    """.trim().split(Regex("\\s+")).associate { it.substringBefore(':') to it.substringAfter(':').toDouble() }
}
