package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException

/** Exposes report fetching and summarising to AI agents as `mcp__boss__reportanalyser_*`. */
class ReportAnalyserMcpToolProvider(
    override val providerId: String,
    private val viewModel: ReportAnalyserViewModel,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "reportanalyser_ask",
            description = "Ask a natural-language question about the user's SupplyNote data (wastage, purchases, sales, stock, expiry, vendors…). " +
                "An internal agent picks the right SupplyNote insights/datasets/SQL and returns a plain-language answer. " +
                "The question and answer also appear in the Report analyser panel's Ask AI chat.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "question": {"type": "string", "description": "e.g. 'What is the top wasted item this month?'"}
                  },
                  "required": ["question"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { ask(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_supplynote_catalog",
            description = "List the SupplyNote reports this tool can fetch: insights and datasets with their date-range rules and caveats.",
            inputSchema = """{"type":"object","properties":{}}""",
            readOnly = true,
            handler = { _ -> McpToolResult(catalogText()) },
        ),
        McpToolDefinition(
            name = "reportanalyser_supplynote_fetch",
            description = "Fetch a SupplyNote insight or dataset (all pages up to max_rows), analyse it and return statistics, units and caveats. " +
                "Uses the SupplyNote API key saved in BOSS Secrets. Optionally adds an AI summary.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "secret": {"type": "string", "description": "BOSS secret holding the SupplyNote API key (id or name, e.g. 'SN Aubree API'). Default: the key selected in the panel."},
                    "report": {"type": "string", "description": "Report id from reportanalyser_supplynote_catalog, e.g. 'wastage' or 'daily-sales'"},
                    "kind": {"type": "string", "enum": ["insight", "dataset"], "description": "Disambiguates ids that exist as both (purchase-fulfilment). Default: insight if one exists."},
                    "start_date": {"type": "string", "description": "YYYY-MM-DD, IST business day (required for some reports, refused by others)"},
                    "end_date": {"type": "string", "description": "YYYY-MM-DD, inclusive"},
                    "outlet_ids": {"type": "string", "description": "Comma-separated outlet ids"},
                    "group_by": {"type": "string", "description": "Result grain for insights"},
                    "category": {"type": "string"},
                    "params": {"type": "object", "description": "Any other query parameters, as string values", "additionalProperties": {"type": "string"}},
                    "max_rows": {"type": "integer", "description": "Stop paging after this many rows (default 2000)"},
                    "ai_summary": {"type": "boolean", "description": "Also generate an AI summary via the BOSS AI gateway"},
                    "focus": {"type": "string", "description": "Question to focus the AI summary on"}
                  },
                  "required": ["report"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { fetchSupplyNote(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_supplynote_sql",
            description = "Run a read-only SELECT against SupplyNote's sn_public tables (POST /v2/sql) and analyse the result. " +
                "Use named parameters like {start:Date} with the params object instead of interpolating values.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "secret": {"type": "string", "description": "BOSS secret (id or name) holding the SupplyNote API key"},
                    "query": {"type": "string", "description": "One SELECT or WITH statement against sn_public.* tables"},
                    "params": {"type": "object", "description": "Named query parameters"},
                    "max_rows": {"type": "integer"},
                    "ai_summary": {"type": "boolean"},
                    "focus": {"type": "string"}
                  },
                  "required": ["query"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { runSql(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_supplynote_export",
            description = "Request a full SupplyNote dataset export (csv/jsonl/parquet) and return its time-limited download link; " +
                "optionally open it in the BOSS browser to download the file. Needs the exports:write scope.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "secret": {"type": "string", "description": "BOSS secret (id or name) holding the SupplyNote API key"},
                    "dataset": {"type": "string", "description": "Dataset id, e.g. 'inventory-log'"},
                    "format": {"type": "string", "enum": ["csv", "jsonl", "parquet"]},
                    "start_date": {"type": "string"},
                    "end_date": {"type": "string"},
                    "outlet_ids": {"type": "string"},
                    "category": {"type": "string"},
                    "params": {"type": "object", "additionalProperties": {"type": "string"}},
                    "download": {"type": "boolean", "description": "Open the link in the BOSS browser so it downloads (default true)"}
                  },
                  "required": ["dataset"]
                }
            """.trimIndent(),
            readOnly = false,
            handler = { args -> guarded { export(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_supplynote_feeds",
            description = "List SupplyNote feeds (saved queries), or fetch and analyse one feed's current data when feed_id is given.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "feed_id": {"type": "string", "description": "Feed to fetch; omit to list feeds"},
                    "secret": {"type": "string", "description": "BOSS secret (id or name) holding the SupplyNote API key"}
                  }
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { feeds(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_analyse_source",
            description = "Fetch a report from an http(s) URL or read a local file (CSV, TSV, JSON, XLSX, HTML, text, PDF) and return its statistics.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "source": {"type": "string", "description": "http(s) URL or local file path"},
                    "secret": {"type": "string", "description": "Optional BOSS secret id or website for authentication; must belong to the URL's host"},
                    "auth_mode": {"type": "string", "enum": ["bearer", "basic", "header"], "description": "How to send the secret (default bearer)"},
                    "header_name": {"type": "string", "description": "Header for auth_mode=header (default X-API-Key)"},
                    "ai_summary": {"type": "boolean"},
                    "focus": {"type": "string"}
                  },
                  "required": ["source"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { analyseSource(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_ai_status",
            description = "Diagnostics: which AI provider/model the BOSS AI gateway routes this plugin's requests to, its capabilities, and the available models.",
            inputSchema = """{"type":"object","properties":{}}""",
            readOnly = true,
            handler = { _ -> McpToolResult(viewModel.aiDiagnostics()) },
        ),
        McpToolDefinition(
            name = "reportanalyser_list",
            description = "List reports analysed in this BOSS session (newest first) with their ids.",
            inputSchema = """{"type":"object","properties":{}}""",
            readOnly = true,
            handler = { _ -> McpToolResult(listText()) },
        ),
        McpToolDefinition(
            name = "reportanalyser_get",
            description = "Read an analysed report: its statistics plus its rows as CSV (or text), paged by offset.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "string", "description": "Report id from reportanalyser_list"},
                    "offset": {"type": "integer", "description": "First row (tables) or character (text) to return; default 0"},
                    "max_chars": {"type": "integer", "description": "Maximum characters of content to return (default 20000)"}
                  },
                  "required": ["id"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { getReport(args) } },
        ),
        McpToolDefinition(
            name = "reportanalyser_summarise",
            description = "Generate (or regenerate) an AI summary of an analysed report via the BOSS AI gateway.",
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "string"},
                    "focus": {"type": "string", "description": "Optional question to focus on"}
                  },
                  "required": ["id"]
                }
            """.trimIndent(),
            readOnly = true,
            handler = { args -> guarded { summarise(args) } },
        ),
    )

    // ---- handlers ----------------------------------------------------------------------

    private suspend fun ask(args: McpToolArgs): McpToolResult {
        val question = args.string("question")?.trim()?.takeIf { it.isNotEmpty() } ?: return error("Missing 'question'")
        if (!viewModel.aiReady()) return error("No AI provider is configured in BOSS — set one up in Settings, or use reportanalyser_supplynote_fetch directly.")
        val reply = viewModel.ask(question)
        if (reply.isError) return error(reply.text + "\n\n" + viewModel.aiDiagnostics())
        val used = if (reply.toolsUsed.isEmpty()) "" else "\n\nData used: " + reply.toolsUsed.joinToString("; ")
        return McpToolResult(reply.text + used)
    }

    private suspend fun fetchSupplyNote(args: McpToolArgs): McpToolResult {
        val id = args.string("report")?.trim() ?: return error("Missing 'report'")
        val kind = when (args.string("kind")?.lowercase()) {
            "dataset" -> SnKind.DATASET
            "insight" -> SnKind.INSIGHT
            null -> null
            else -> return error("kind must be 'insight' or 'dataset'")
        }
        val report = SupplyNoteCatalog.find(id, kind) ?: SupplyNoteCatalog.find(id)
            ?: return error("Unknown report '$id'. Call reportanalyser_supplynote_catalog for the list.")
        val params = viewModel.buildParams(
            report,
            startDate = args.string("start_date"),
            endDate = args.string("end_date"),
            outletIds = args.string("outlet_ids"),
            groupBy = args.string("group_by"),
            category = args.string("category"),
            extra = objectArg(args, "params"),
        )
        val maxRows = args.int("max_rows") ?: ReportAnalyserViewModel.DEFAULT_MAX_ROWS
        val result = viewModel.fetchSupplyNote(report, params, maxRows, args.string("secret"))
        return McpToolResult(withOptionalSummary(result, args))
    }

    private suspend fun runSql(args: McpToolArgs): McpToolResult {
        val query = args.string("query")?.takeIf { it.isNotBlank() } ?: return error("Missing 'query'")
        if (query.length > 16_000) return error("Query exceeds the 16,000 character limit")
        @Suppress("UNCHECKED_CAST")
        val params = (rawObject(args)?.get("params") as? Map<String, Any?>).orEmpty()
        val report = viewModel.runSql(query, params, args.int("max_rows") ?: ReportAnalyserViewModel.DEFAULT_MAX_ROWS, args.string("secret"))
        return McpToolResult(withOptionalSummary(report, args))
    }

    private suspend fun export(args: McpToolArgs): McpToolResult {
        val id = args.string("dataset")?.trim() ?: return error("Missing 'dataset'")
        val report = SupplyNoteCatalog.find(id, SnKind.DATASET) ?: return error("Unknown dataset '$id'")
        val params = viewModel.buildParams(
            report,
            startDate = args.string("start_date"),
            endDate = args.string("end_date"),
            outletIds = args.string("outlet_ids"),
            groupBy = null,
            category = args.string("category"),
            extra = objectArg(args, "params"),
        )
        val url = viewModel.export(report, params, args.string("format") ?: "csv", args.string("secret"))
        val opened = if (args.boolean("download") != false) viewModel.openInBrowser(url, "$id export") else false
        return McpToolResult(
            buildString {
                appendLine("Export link (time-limited): $url")
                if (opened) appendLine("Opened in the BOSS browser — the file is downloading via the BOSS download manager.")
                append("Once downloaded, analyse it with reportanalyser_analyse_source using the downloaded file path.")
            },
        )
    }

    private suspend fun feeds(args: McpToolArgs): McpToolResult {
        val feedId = args.string("feed_id")?.trim()
        if (feedId.isNullOrEmpty()) {
            val feeds = viewModel.listFeeds(args.string("secret"))
            if (feeds.isEmpty()) return McpToolResult("No feeds found for this API key.")
            return McpToolResult(feeds.joinToString("\n") { "- ${it.id}: ${it.name}${it.format?.let { f -> " ($f)" } ?: ""}" })
        }
        return McpToolResult(describe(viewModel.fetchFeed(feedId, args.string("secret"))))
    }

    private suspend fun analyseSource(args: McpToolArgs): McpToolResult {
        val source = args.string("source")?.trim()?.takeIf { it.isNotEmpty() } ?: return error("Missing 'source'")
        val secretQuery = args.string("secret")?.takeIf { it.isNotBlank() }
        val auth = if (secretQuery != null) {
            if (!ReportFetcher.isUrl(source)) return error("'secret' only applies to URLs")
            val secret = viewModel.resolveSecret(secretQuery, source).getOrElse { return error(it.message ?: "Secret not usable") }
            val mode = when (args.string("auth_mode")?.lowercase()) {
                null, "bearer" -> AuthMode.BEARER
                "basic" -> AuthMode.BASIC
                "header" -> AuthMode.HEADER
                else -> return error("auth_mode must be bearer, basic or header")
            }
            FetchAuth(secret, mode, args.string("header_name") ?: "X-API-Key")
        } else null
        val report = viewModel.analyse(source, auth)
        return McpToolResult(withOptionalSummary(report, args))
    }

    private fun getReport(args: McpToolArgs): McpToolResult {
        val id = args.string("id") ?: return error("Missing 'id'")
        val report = viewModel.report(id) ?: return error("Unknown report id '$id'. Call reportanalyser_list.")
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val maxChars = (args.int("max_chars") ?: 20_000).coerceIn(500, 200_000)
        val table = report.parsed.table
        val body = buildString {
            appendLine(header(report))
            appendLine()
            appendLine(report.stats)
            appendLine()
            if (table != null && table.rows.isNotEmpty()) {
                val slice = ReportTable(table.headers, table.rows.drop(offset))
                val csv = ReportStats.tableSample(slice, slice.rows.size)
                val clipped = csv.take(maxChars)
                val shown = (clipped.count { it == '\n' }).coerceAtMost(slice.rows.size)
                appendLine("Rows ${offset + 1}–${offset + shown} of ${table.rows.size} (CSV):")
                appendLine(clipped)
                if (offset + shown < table.rows.size) append("… more rows: call again with offset=${offset + shown}")
            } else {
                val text = report.parsed.text
                appendLine("Text ${offset}–${minOf(text.length, offset + maxChars)} of ${text.length} chars:")
                append(text.drop(offset).take(maxChars))
            }
        }
        return McpToolResult(body)
    }

    private suspend fun summarise(args: McpToolArgs): McpToolResult {
        val id = args.string("id") ?: return error("Missing 'id'")
        if (!viewModel.aiReady()) return error("No AI provider is configured in BOSS; use reportanalyser_get and summarise the statistics yourself.")
        return viewModel.summarise(id, args.string("focus")).fold(
            onSuccess = { McpToolResult(it) },
            onFailure = { error(it.message ?: "AI summary failed") },
        )
    }

    // ---- formatting --------------------------------------------------------------------

    private suspend fun withOptionalSummary(report: Report, args: McpToolArgs): String {
        val base = describe(report)
        if (args.boolean("ai_summary") != true) return base
        if (!viewModel.aiReady()) return "$base\n\nAI summary unavailable: no AI provider configured in BOSS."
        val summary = viewModel.summarise(report.id, args.string("focus"))
        return base + "\n\nAI summary:\n" + summary.getOrElse { "(failed: ${it.message})" }
    }

    private fun describe(report: Report): String = buildString {
        appendLine(header(report))
        appendLine()
        appendLine(report.stats)
        report.domainHints?.let {
            appendLine()
            appendLine(it)
        }
        appendLine()
        append("Use reportanalyser_get with id=${report.id} to read the rows.")
    }

    private fun header(report: Report): String =
        "Report ${report.id}: ${report.name} — ${report.source.lineSequence().first()}" +
            (report.rowCount?.let { " · $it rows" } ?: "")

    private fun listText(): String {
        val reports = viewModel.state.value.reports
        if (reports.isEmpty()) return "No reports analysed yet."
        return reports.joinToString("\n") { r ->
            "- ${r.id}: ${r.name} · ${r.parsed.format.label}" + (r.rowCount?.let { " · $it rows" } ?: "") +
                (if (r.aiSummary != null) " · AI summary" else "") + " — ${r.source.lineSequence().first()}"
        }
    }

    private fun catalogText(): String = buildString {
        appendLine("SupplyNote reports (configured: ${if (viewModel.supplyNoteConfigured()) "yes" else "no — save the API key in BOSS Secrets with website api.supplynote.in"})")
        appendLine()
        appendLine("Insights (GET /v2/insights/{id}, analytics:read):")
        SupplyNoteCatalog.insights.forEach { appendLine(line(it)) }
        appendLine()
        appendLine("Datasets (GET /v2/data/{id}, data:read):")
        SupplyNoteCatalog.datasets.forEach { appendLine(line(it)) }
        appendLine()
        append(SupplyNoteCatalog.GENERAL_CAVEATS)
    }

    private fun line(r: SnReport): String {
        val dates = when (r.dates) {
            SnDates.REQUIRED -> "dates required"
            SnDates.OPTIONAL -> "dates optional"
            SnDates.REFUSED -> "no dates"
        }
        return "- ${r.id}: ${r.description} [$dates]" + (r.caveat?.let { " Caveat: $it" } ?: "")
    }

    // ---- helpers -----------------------------------------------------------------------

    private suspend fun guarded(block: suspend () -> McpToolResult): McpToolResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error(e.message ?: e.javaClass.simpleName)
    }

    private fun error(message: String) = McpToolResult(message, isError = true)

    private fun rawObject(args: McpToolArgs): Map<*, *>? = runCatching { MiniJson.parse(args.raw) as? Map<*, *> }.getOrNull()

    private fun objectArg(args: McpToolArgs, key: String): Map<String, String> =
        (rawObject(args)?.get(key) as? Map<*, *>).orEmpty().entries
            .filter { it.value != null }
            .associate { it.key.toString() to it.value.toString() }
}
