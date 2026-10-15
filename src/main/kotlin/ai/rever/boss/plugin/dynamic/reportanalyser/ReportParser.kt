package ai.rever.boss.plugin.dynamic.reportanalyser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

enum class ReportFormat(val label: String) {
    CSV("CSV"),
    TSV("TSV"),
    JSON("JSON"),
    XLSX("Excel"),
    HTML("HTML"),
    TEXT("Text"),
    PDF("PDF"),
    BINARY("Binary"),
}

data class ReportTable(val headers: List<String>, val rows: List<List<String>>) {
    val columnCount: Int get() = headers.size
}

data class ParsedReport(
    val format: ReportFormat,
    /** Plain-text rendering of the report (capped), used for previews and AI prompts. */
    val text: String,
    val table: ReportTable?,
    /** Parser caveats worth surfacing (truncation, unsupported encodings…). */
    val notes: List<String> = emptyList(),
)

/** Turns raw report bytes into text and, where possible, a table. No external libraries. */
object ReportParser {

    const val MAX_TEXT_CHARS = 2_000_000
    private const val MAX_ROWS = 100_000
    private const val MAX_COLUMNS = 256

    val SUPPORTED_EXTENSIONS = setOf("csv", "tsv", "txt", "json", "xlsx", "html", "htm", "md", "log", "pdf", "xml")

    fun parse(bytes: ByteArray, fileName: String, contentType: String?): ParsedReport {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val ctype = contentType?.lowercase().orEmpty()
        return try {
            when {
                bytes.startsWith("%PDF") -> parsePdf(bytes)
                bytes.startsWith("PK") -> parseXlsx(bytes)
                looksBinary(bytes) -> ParsedReport(
                    ReportFormat.BINARY, "", null,
                    listOf("Binary content (${ctype.ifBlank { ext.ifBlank { "unknown type" } }}) can't be summarised."),
                )
                else -> parseText(decode(bytes), ext, ctype)
            }
        } catch (e: Exception) {
            val text = if (looksBinary(bytes)) "" else decode(bytes).take(MAX_TEXT_CHARS)
            ParsedReport(ReportFormat.TEXT, text, null, listOf("Couldn't parse as structured data: ${e.message}"))
        }
    }

    // ---- Text formats ------------------------------------------------------------------

    private fun parseText(raw: String, ext: String, ctype: String): ParsedReport {
        val text = raw.removePrefix("﻿")
        val trimmed = text.trimStart()
        return when {
            ext == "json" || ctype.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[") ->
                runCatching { parseJson(text) }.getOrElse { plain(text, "Invalid JSON: ${it.message}") }
            ext == "tsv" || ctype.contains("tab-separated") -> delimited(text, '\t', ReportFormat.TSV)
            ext == "csv" || ctype.contains("csv") -> delimited(text, detectDelimiter(text), ReportFormat.CSV)
            ext in setOf("html", "htm") || ctype.contains("html") || trimmed.startsWith("<!doctype html", true) ||
                trimmed.startsWith("<html", true) -> parseHtml(text)
            ext in setOf("txt", "md", "log", "xml") -> plain(text)
            looksDelimited(text) -> {
                val d = detectDelimiter(text)
                delimited(text, d, if (d == '\t') ReportFormat.TSV else ReportFormat.CSV)
            }
            else -> plain(text)
        }
    }

    private fun plain(text: String, note: String? = null): ParsedReport {
        val notes = listOfNotNull(note, truncationNote(text))
        return ParsedReport(ReportFormat.TEXT, text.take(MAX_TEXT_CHARS), null, notes)
    }

    private fun truncationNote(text: String): String? =
        if (text.length > MAX_TEXT_CHARS) "Text truncated to ${MAX_TEXT_CHARS / 1000}k characters." else null

    private fun delimited(text: String, delimiter: Char, format: ReportFormat): ParsedReport {
        val records = parseDelimited(text, delimiter)
        val table = toTable(records)
        val notes = buildList {
            if (records.size > MAX_ROWS) add("Only the first $MAX_ROWS rows were analysed.")
            truncationNote(text)?.let(::add)
        }
        return ParsedReport(format, text.take(MAX_TEXT_CHARS), table, notes)
    }

    private fun looksDelimited(text: String): Boolean {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(10).toList()
        if (lines.size < 2) return false
        val d = detectDelimiter(text)
        val counts = lines.map { line -> line.count { it == d } }
        return counts.first() > 0 && counts.all { it == counts.first() }
    }

    private fun detectDelimiter(text: String): Char {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> countOutsideQuotes(header, d) } ?: ','
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQuotes = false
        var n = 0
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes else if (c == d && !inQuotes) n++
        }
        return n
    }

    /** RFC 4180-ish parser: quoted fields, escaped quotes, newlines inside quotes. */
    fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { record.add(field.toString().trim()); field.setLength(0) }
        fun endRecord() {
            endField()
            if (record.any { it.isNotEmpty() }) records.add(record)
            record = ArrayList()
        }
        while (i < text.length && records.size <= MAX_ROWS) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> if (field.isBlank()) { field.setLength(0); inQuotes = true } else field.append(c)
                delimiter -> endField()
                '\r' -> {}
                '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    private fun toTable(records: List<List<String>>): ReportTable? {
        if (records.isEmpty()) return null
        val width = records.maxOf { it.size }.coerceAtMost(MAX_COLUMNS)
        val rawHeaders = records.first()
        val headers = (0 until width).map { i ->
            rawHeaders.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "column_${i + 1}"
        }
        val rows = records.drop(1).take(MAX_ROWS).map { row ->
            (0 until width).map { i -> row.getOrNull(i).orEmpty() }
        }
        return ReportTable(headers, rows)
    }

    // ---- JSON --------------------------------------------------------------------------

    private fun parseJson(text: String): ParsedReport {
        val root = MiniJson.parse(text)
        val records = findRecordArray(root)
        val table = records?.let { recordsToTable(it) }
        return ParsedReport(ReportFormat.JSON, text.take(MAX_TEXT_CHARS), table, listOfNotNull(truncationNote(text)))
    }

    /** Root array of objects, or the largest array of objects one level down (`data`, `results`, …). */
    private fun findRecordArray(root: Any?): List<Map<*, *>>? {
        fun asRecords(v: Any?): List<Map<*, *>>? =
            (v as? List<*>)?.takeIf { it.isNotEmpty() && it.all { e -> e is Map<*, *> } }?.map { it as Map<*, *> }
        asRecords(root)?.let { return it }
        val obj = root as? Map<*, *> ?: return null
        return obj.values.mapNotNull { asRecords(it) }.maxByOrNull { it.size }
    }

    /** Builds a report from already-decoded API records (e.g. a SupplyNote `data` array). */
    fun fromRecords(records: List<Map<*, *>>, text: String, notes: List<String>): ParsedReport =
        ParsedReport(ReportFormat.JSON, text.take(MAX_TEXT_CHARS), records.takeIf { it.isNotEmpty() }?.let(::recordsToTable), notes)

    private fun recordsToTable(records: List<Map<*, *>>): ReportTable {
        val keys = LinkedHashSet<String>()
        records.take(1000).forEach { r -> r.keys.forEach { if (keys.size < MAX_COLUMNS) keys.add(it.toString()) } }
        val headers = keys.toList()
        val rows = records.take(MAX_ROWS).map { r ->
            headers.map { h ->
                when (val v = r[h]) {
                    null -> ""
                    is Map<*, *>, is List<*> -> MiniJson.write(v)
                    else -> v.toString()
                }
            }
        }
        return ReportTable(headers, rows)
    }

    // ---- HTML --------------------------------------------------------------------------

    private val TABLE_RE = Regex("<table\\b[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ROW_RE = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL_RE = Regex("<t[hd]\\b[^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_RE = Regex("<(script|style|noscript)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_RE = Regex("</?(p|div|br|tr|li|h[1-6]|section|article|table)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("<[^>]+>")

    private fun parseHtml(html: String): ParsedReport {
        val cleaned = SCRIPT_RE.replace(html, " ")
        val tables = TABLE_RE.findAll(cleaned).map { m ->
            ROW_RE.findAll(m.groupValues[1]).map { row ->
                CELL_RE.findAll(row.groupValues[1]).map { htmlToText(it.groupValues[1]).replace('\n', ' ').trim() }.toList()
            }.filter { it.isNotEmpty() }.toList()
        }.toList()
        val biggest = tables.maxByOrNull { it.size }
        val text = htmlToText(cleaned)
        val notes = buildList {
            if (tables.size > 1) add("Found ${tables.size} tables; analysed the largest (${biggest?.size ?: 0} rows).")
            truncationNote(text)?.let(::add)
        }
        return ParsedReport(ReportFormat.HTML, text.take(MAX_TEXT_CHARS), biggest?.let(::toTable), notes)
    }

    private fun htmlToText(html: String): String {
        val withBreaks = BLOCK_RE.replace(html, "\n")
        return decodeEntities(TAG_RE.replace(withBreaks, " "))
            .lineSequence().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    // ---- XLSX (zip of XML) -------------------------------------------------------------

    private fun parseXlsx(bytes: ByteArray): ParsedReport {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))) {
                    entries[name] = zip.readLimited(MAX_ZIP_ENTRY_BYTES)
                }
                entry = zip.nextEntry
            }
        }
        val sheets = entries.keys.filter { it.startsWith("xl/worksheets/") }
            .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
        if (sheets.isEmpty()) {
            return ParsedReport(ReportFormat.BINARY, "", null, listOf("Zip archive without an Excel worksheet; only .xlsx is supported."))
        }
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            val doc = secureXml(xml)
            val items = doc.getElementsByTagNameNS("*", "si")
            (0 until items.length).map { i ->
                val ts = (items.item(i) as Element).getElementsByTagNameNS("*", "t")
                (0 until ts.length).joinToString("") { j ->
                    val t = ts.item(j)
                    // Skip phonetic runs (<rPh><t>), keep rich-text runs (<r><t>).
                    if (t.parentNode?.localName == "rPh") "" else t.textContent
                }
            }
        }.orEmpty()

        val doc = secureXml(entries.getValue(sheets.first()))
        val rowNodes = doc.getElementsByTagNameNS("*", "row")
        val records = ArrayList<List<String>>()
        for (i in 0 until minOf(rowNodes.length, MAX_ROWS + 1)) {
            val cells = (rowNodes.item(i) as Element).getElementsByTagNameNS("*", "c")
            val row = ArrayList<String>()
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val col = columnIndex(c.getAttribute("r")) ?: row.size
                if (col >= MAX_COLUMNS) continue
                while (row.size < col) row.add("")
                val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                val value = when (c.getAttribute("t")) {
                    "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> c.getElementsByTagNameNS("*", "t").item(0)?.textContent.orEmpty()
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v
                }
                if (row.size == col) row.add(value) else row[col] = value
            }
            if (row.any { it.isNotBlank() }) records.add(row)
        }
        val table = toTable(records)
        val text = records.take(5000).joinToString("\n") { it.joinToString("\t") }
        val notes = buildList {
            if (sheets.size > 1) add("Workbook has ${sheets.size} sheets; analysed the first.")
            if (rowNodes.length > MAX_ROWS) add("Only the first $MAX_ROWS rows were analysed.")
            add("Excel dates appear as serial numbers.")
        }
        return ParsedReport(ReportFormat.XLSX, text, table, notes)
    }

    private fun columnIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    private fun secureXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    // ---- PDF (best effort) -------------------------------------------------------------

    private val STREAM_RE = Regex("stream\\r?\\n")

    /**
     * Best-effort text extraction: inflate FlateDecode content streams and read the
     * strings passed to text operators. Works for simply-encoded generated reports;
     * PDFs with custom font encodings fall back to a note.
     */
    private fun parsePdf(bytes: ByteArray): ParsedReport {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val pageCount = Regex("/Type\\s*/Page(?!s)").findAll(raw).count()
        val out = StringBuilder()
        for (m in STREAM_RE.findAll(raw)) {
            if (out.length > MAX_TEXT_CHARS) break
            val start = m.range.last + 1
            val end = raw.indexOf("endstream", start).takeIf { it > 0 } ?: continue
            val dictStart = raw.lastIndexOf("<<", m.range.first).coerceAtLeast(0)
            val dict = raw.substring(dictStart, m.range.first)
            if (dict.contains("/Subtype/Image") || dict.contains("/Subtype /Image")) continue
            val content = decodeStream(bytes.copyOfRange(start, end), dict) ?: continue
            val text = extractPdfText(String(content, Charsets.ISO_8859_1))
            if (text.isNotBlank()) out.append(text).append('\n')
        }
        val text = winAnsi(out.toString()).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val pages = if (pageCount > 0) "$pageCount page(s). " else ""
        return if (text.length >= 20 && looksLikeProse(text)) {
            ParsedReport(ReportFormat.PDF, text.take(MAX_TEXT_CHARS), null, listOf("${pages}Text extracted on a best-effort basis; layout is not preserved."))
        } else {
            ParsedReport(ReportFormat.PDF, "", null, listOf("${pages}This PDF's text couldn't be extracted (embedded fonts or scanned images). Open it in the BOSS browser to read it."))
        }
    }

    private val FILTER_NAME = Regex("/(ASCII85Decode|A85|FlateDecode|Fl|ASCIIHexDecode|AHx|[A-Za-z0-9]+Decode)")

    /** Applies the stream's filter chain in order; null when a filter isn't supported (images, LZW…). */
    private fun decodeStream(data: ByteArray, dict: String): ByteArray? {
        val filterSpec = dict.substringAfter("/Filter", "").let { rest ->
            if (rest.trimStart().startsWith("[")) rest.substringBefore("]") else rest.trimStart().substringBefore(" ").substringBefore("/Length")
        }
        val filters = FILTER_NAME.findAll(filterSpec).map { it.groupValues[1] }.toList()
        var out = data
        for (f in filters) {
            out = when (f) {
                "FlateDecode", "Fl" -> inflate(out) ?: return null
                "ASCII85Decode", "A85" -> ascii85(out) ?: return null
                "ASCIIHexDecode", "AHx" -> String(out, Charsets.ISO_8859_1).substringBefore('>').filter { it.isLetterOrDigit() }
                    .chunked(2).mapNotNull { it.padEnd(2, '0').toIntOrNull(16)?.toByte() }.toByteArray()
                else -> return null
            }
        }
        return out
    }

    private fun ascii85(data: ByteArray): ByteArray? = try {
        val s = String(data, Charsets.ISO_8859_1).substringBefore("~>").removePrefix("<~").filterNot { it.isWhitespace() }
        val out = ByteArrayOutputStream()
        var tuple = 0L
        var count = 0
        for (c in s) {
            if (c == 'z' && count == 0) { repeat(4) { out.write(0) }; continue }
            if (c !in '!'..'u') return null
            tuple = tuple * 85 + (c - '!')
            count++
            if (count == 5) {
                for (shift in intArrayOf(24, 16, 8, 0)) out.write(((tuple shr shift) and 0xFF).toInt())
                tuple = 0
                count = 0
            }
        }
        if (count > 0) {
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            for (i in 0 until count - 1) out.write(((tuple shr (24 - 8 * i)) and 0xFF).toInt())
        }
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished() && out.size() < MAX_ZIP_ENTRY_BYTES) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun extractPdfText(content: String): String {
        if (!content.contains("BT")) return ""
        val sb = StringBuilder()
        val pending = StringBuilder()
        var i = 0
        var inArray = false
        fun newline() { if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n') }
        while (i < content.length) {
            val c = content[i]
            when {
                c == '(' -> {
                    var depth = 1
                    i++
                    while (i < content.length && depth > 0) {
                        val ch = content[i]
                        when (ch) {
                            '\\' -> {
                                i++
                                if (i < content.length) when (val e = content[i]) {
                                    'n' -> pending.append('\n')
                                    'r', 't' -> pending.append(' ')
                                    in '0'..'7' -> {
                                        val oct = content.substring(i, minOf(i + 3, content.length)).takeWhile { it in '0'..'7' }
                                        pending.append(oct.toInt(8).toChar())
                                        i += oct.length - 1
                                    }
                                    else -> pending.append(e)
                                }
                            }
                            '(' -> { depth++; pending.append(ch) }
                            ')' -> { depth--; if (depth > 0) pending.append(ch) }
                            else -> pending.append(ch)
                        }
                        i++
                    }
                    continue
                }
                c == '<' && content.getOrNull(i + 1) != '<' -> {
                    val close = content.indexOf('>', i)
                    if (close < 0) break
                    pending.append(decodeHexString(content.substring(i + 1, close)))
                    i = close + 1
                    continue
                }
                c == '[' -> inArray = true
                c == ']' -> inArray = false
                inArray && (c == '-' || c.isDigit()) -> {
                    val start = i
                    while (i < content.length && (content[i] == '-' || content[i] == '.' || content[i].isDigit())) i++
                    val n = content.substring(start, i).toDoubleOrNull() ?: 0.0
                    if (n < -200) pending.append(' ')
                    continue
                }
                c.isLetter() || c == '\'' || c == '"' || c == '*' -> {
                    val start = i
                    while (i < content.length && (content[i].isLetter() || content[i] == '*' || content[i] == '\'' || content[i] == '"')) i++
                    when (content.substring(start, i)) {
                        "Tj", "TJ" -> { sb.append(pending); pending.setLength(0) }
                        "'", "\"" -> { newline(); sb.append(pending); pending.setLength(0) }
                        "Td", "TD", "T*", "ET" -> newline()
                        else -> if (!inArray) pending.setLength(0)
                    }
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }

    /** Maps the WinAnsi punctuation range (0x80–0x9F) that ISO-8859-1 decoding leaves as control chars. */
    private fun winAnsi(s: String): String {
        val map = mapOf(
            '\u0085' to "…", '\u0091' to "‘", '\u0092' to "’", '\u0093' to "“", '\u0094' to "”",
            '\u0095' to "•", '\u0096' to "–", '\u0097' to "—", '\u0080' to "€", '\u0099' to "™",
        )
        return buildString(s.length) {
            for (c in s) {
                val m = map[c]
                when {
                    m != null -> append(m)
                    c in '\u0080'..'\u009f' -> {}
                    else -> append(c)
                }
            }
        }
    }

    private fun decodeHexString(hex: String): String {
        val clean = hex.filter { it.isLetterOrDigit() }
        val bytes = clean.chunked(2).mapNotNull { it.padEnd(2, '0').toIntOrNull(16)?.toByte() }.toByteArray()
        val utf16 = bytes.size >= 2 && bytes.size % 2 == 0 && bytes.filterIndexed { idx, _ -> idx % 2 == 0 }.all { it.toInt() == 0 }
        return if (utf16) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
    }

    /** Real text is mostly letters/digits with regular spaces and dictionary-like words; encoded junk isn't. */
    private fun looksLikeProse(text: String): Boolean {
        val alnum = text.count { it.isLetterOrDigit() }.toDouble() / text.length
        val spaces = text.count { it == ' ' || it == '\n' }.toDouble() / text.length
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wordLike = words.count { w -> w.trim('.', ',', ':', ';', '(', ')', '"', '\'').all { it.isLetterOrDigit() || it in "-/%₹$&@" } }
        return alnum > 0.6 && spaces > 0.06 && words.isNotEmpty() && wordLike.toDouble() / words.size > 0.6
    }

    // ---- helpers -----------------------------------------------------------------------

    private const val MAX_ZIP_ENTRY_BYTES = 50 * 1024 * 1024

    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IllegalStateException("Workbook entry too large")
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(8192)
        return sample.any { it == 0.toByte() }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Many exported reports are Windows-1252; fall back when UTF-8 decoding produced replacement chars.
        return if (utf8.count { it == '�' } > 5) String(bytes, Charsets.ISO_8859_1) else utf8
    }
}
