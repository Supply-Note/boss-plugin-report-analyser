package ai.rever.boss.plugin.dynamic.reportanalyser

import java.math.BigDecimal
import java.math.RoundingMode

enum class ColumnType { NUMBER, DATE, TEXT, EMPTY }

data class ColumnProfile(
    val name: String,
    val type: ColumnType,
    val filled: Int,
    val empty: Int,
    val distinct: Int,
    val sum: Double? = null,
    val mean: Double? = null,
    val min: String? = null,
    val max: String? = null,
    val topValues: List<Pair<String, Int>> = emptyList(),
)

/** Deterministic, offline summary of a parsed report. */
object ReportStats {

    private val ISO_DATE = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}([ T].*)?$")
    private val DMY_DATE = Regex("^\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}$")
    private val NUMBER_STRIP = Regex("[\\s,$€£₹¥%]")
    private val KEY_LINE = Regex(
        "\\b(total|subtotal|sum|net|gross|revenue|sales|profit|loss|margin|balance|count|average|avg|growth|cost|expenses?|income|tax|due|outstanding)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val HAS_NUMBER = Regex("\\d")

    fun profile(table: ReportTable): List<ColumnProfile> =
        table.headers.mapIndexed { idx, name -> profileColumn(name, table.rows.map { it.getOrNull(idx).orEmpty() }) }

    private fun profileColumn(name: String, values: List<String>): ColumnProfile {
        val present = values.map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", true) && it != "-" }
        val empty = values.size - present.size
        if (present.isEmpty()) return ColumnProfile(name, ColumnType.EMPTY, 0, empty, 0)
        val distinct = present.toSet().size

        val numbers = present.mapNotNull(::parseNumber)
        if (numbers.size >= present.size * 0.9) {
            val sum = numbers.sum()
            return ColumnProfile(
                name, ColumnType.NUMBER, present.size, empty, distinct,
                sum = sum, mean = sum / numbers.size,
                min = fmt(numbers.min()), max = fmt(numbers.max()),
            )
        }
        val dates = present.filter { ISO_DATE.matches(it) || DMY_DATE.matches(it) }
        if (dates.size >= present.size * 0.9) {
            val iso = dates.filter { ISO_DATE.matches(it) }.sorted()
            return ColumnProfile(
                name, ColumnType.DATE, present.size, empty, distinct,
                min = iso.firstOrNull() ?: dates.first(), max = iso.lastOrNull() ?: dates.last(),
            )
        }
        val top = present.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return ColumnProfile(name, ColumnType.TEXT, present.size, empty, distinct, topValues = top)
    }

    fun parseNumber(raw: String): Double? {
        var s = raw.trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        s = NUMBER_STRIP.replace(s, "")
        if (s.isEmpty() || s.count { it == '.' } > 1) return null
        val v = s.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val bd = BigDecimal(v).setScale(if (kotlin.math.abs(v) >= 100) 2 else 4, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val sign = if (intPart.startsWith("-")) "-" else ""
        val grouped = intPart.removePrefix("-").reversed().chunked(3).joinToString(",").reversed()
        return sign + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }

    /** Columns whose sum is meaningless: prices, rates, percentages, identifiers, calendar parts. */
    private val NON_ADDITIVE = Regex(
        "(^|_)(pct|percent|percentage|price|rate|ratio|avg|average|mean|id|code|no|number|year|month|day|days|temperature|lat|lng|phone|factor)($|_)",
        RegexOption.IGNORE_CASE,
    )

    /** Quantities in per-product base units — SupplyNote forbids summing these across products. */
    private val QUANTITY = Regex("(quantity|qty|(^|_)stock($|_)|stock_before|stock_after|consumed_quantity|batch_size)", RegexOption.IGNORE_CASE)

    fun isQuantityColumn(name: String): Boolean = QUANTITY.containsMatchIn(name) && !name.contains("value", true)

    /**
     * Human-readable overview used as the built-in summary and as AI prompt context.
     * [skipTotal] suppresses totals for columns that must not be summed.
     */
    fun describe(parsed: ParsedReport, skipTotal: (String) -> Boolean = { false }): String = buildString {
        val table = parsed.table
        if (table != null && table.rows.isNotEmpty()) {
            appendLine("${parsed.format.label} report · ${fmtInt(table.rows.size)} rows × ${table.columnCount} columns")
            appendLine()
            val profiles = profile(table)
            appendLine("Columns:")
            profiles.forEach { appendLine("• ${describeColumn(it, table.rows.size)}") }
            val numeric = profiles.filter {
                it.type == ColumnType.NUMBER && it.sum != null && !NON_ADDITIVE.containsMatchIn(it.name) && !skipTotal(it.name)
            }
            if (numeric.isNotEmpty()) {
                appendLine()
                appendLine("Totals:")
                numeric.forEach { appendLine("• ${it.name}: ${fmt(it.sum!!)}") }
            }
            val sparse = profiles.filter { it.empty > 0 && it.type != ColumnType.EMPTY }
            val emptyCols = profiles.filter { it.type == ColumnType.EMPTY }
            if (sparse.isNotEmpty() || emptyCols.isNotEmpty()) {
                appendLine()
                appendLine("Data quality:")
                emptyCols.forEach { appendLine("• ${it.name} is entirely empty") }
                sparse.sortedByDescending { it.empty }.take(5).forEach {
                    appendLine("• ${it.name}: ${fmtInt(it.empty)} missing (${pct(it.empty, table.rows.size)})")
                }
            }
        } else {
            describeText(parsed)
        }
        if (parsed.notes.isNotEmpty()) {
            appendLine()
            parsed.notes.forEach { appendLine("Note: $it") }
        }
    }.trimEnd()

    private fun StringBuilder.describeText(parsed: ParsedReport) {
        val text = parsed.text
        if (text.isBlank()) {
            appendLine("${parsed.format.label} report · no readable text")
            return
        }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val words = lines.sumOf { line -> line.split(Regex("\\s+")).count { it.isNotEmpty() } }
        appendLine("${parsed.format.label} report · ${fmtInt(lines.size)} lines · ${fmtInt(words)} words")
        val headings = lines.filter { isHeading(it) }.distinct().take(12)
        if (headings.isNotEmpty()) {
            appendLine()
            appendLine("Sections:")
            headings.forEach { appendLine("• ${it.trimStart('#', ' ').take(100)}") }
        }
        val keyLines = lines.filter { KEY_LINE.containsMatchIn(it) && HAS_NUMBER.containsMatchIn(it) && it.length <= 200 }
            .distinct().take(12)
        if (keyLines.isNotEmpty()) {
            appendLine()
            appendLine("Key figures:")
            keyLines.forEach { appendLine("• $it") }
        }
        appendLine()
        appendLine("Opening:")
        lines.filterNot { isHeading(it) }.take(4).forEach { appendLine("  ${it.take(200)}") }
    }

    private fun isHeading(line: String): Boolean =
        line.startsWith("#") ||
            (line.length in 4..60 && line.any { it.isLetter() } && line == line.uppercase() && !HAS_NUMBER.containsMatchIn(line))

    private fun describeColumn(p: ColumnProfile, rows: Int): String {
        val missing = if (p.empty > 0) " · ${pct(p.empty, rows)} empty" else ""
        return when (p.type) {
            ColumnType.NUMBER -> "${p.name} (number) — min ${p.min} · max ${p.max} · mean ${fmt(p.mean ?: 0.0)}$missing"
            ColumnType.DATE -> "${p.name} (date) — ${p.min} → ${p.max}$missing"
            ColumnType.EMPTY -> "${p.name} (empty)"
            ColumnType.TEXT -> {
                val top = p.topValues.take(3).joinToString(", ") { (v, n) -> "${v.take(40)} (${pct(n, p.filled)})" }
                val uniqueNote = if (p.distinct == p.filled && p.filled > 1) "all unique" else "${fmtInt(p.distinct)} distinct"
                "${p.name} (text) — $uniqueNote" + (if (p.distinct < p.filled) " · top: $top" else "") + missing
            }
        }
    }

    private fun pct(n: Int, total: Int): String =
        if (total == 0) "0%" else "${BigDecimal(n * 100.0 / total).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%"

    fun fmtInt(n: Int): String = fmt(n.toDouble())

    /** Compact CSV rendering of the first [maxRows] rows (for previews / AI context). */
    fun tableSample(table: ReportTable, maxRows: Int): String = buildString {
        fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        appendLine(table.headers.joinToString(",") { esc(it) })
        table.rows.take(maxRows).forEach { row -> appendLine(row.joinToString(",") { esc(it.take(200)) }) }
    }.trimEnd()
}
