package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.AiBudget
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiStopReason
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import ai.rever.boss.plugin.api.PluginContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/** Base URL, API key and display label of the SupplyNote credential to use. */
typealias SnCredentials = suspend () -> Triple<String, String, String>

/**
 * "Ask AI": answers natural-language questions about SupplyNote data by letting the
 * host AI gateway call SupplyNote insights, datasets and SQL as tools.
 */
class SupplyNoteChatAgent(
    private val context: PluginContext,
    private val client: SupplyNoteClient,
    private val credentials: SnCredentials,
) {

    data class Turn(val fromUser: Boolean, val text: String)

    data class Answer(val text: String, val toolsUsed: List<String>, val stopReason: AiStopReason)

    private val describeCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var outletNames: Map<String, String>? = null

    suspend fun ask(history: List<Turn>, question: String, onStatus: (String) -> Unit): Result<Answer> {
        val gateway = runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull()
            ?: return Result.failure(IllegalStateException("The BOSS AI gateway isn't available in this build"))
        // Fail fast with a readable message if the key isn't set up.
        runCatching { credentials() }.onFailure { return Result.failure(it) }

        val messages = history.takeLast(MAX_HISTORY).map {
            AiMessage(if (it.fromUser) AiMessage.ROLE_USER else AiMessage.ROLE_ASSISTANT, it.text.take(MAX_HISTORY_CHARS))
        } + AiMessage(AiMessage.ROLE_USER, question)

        val request = AiRequest(
            system = systemPrompt(),
            messages = messages,
            temperature = 0.1f,
            maxTokens = 1500,
            timeoutMs = TIMEOUT_MS,
        )
        val used = ArrayList<String>()
        onStatus("Thinking…")
        val result = AiRouting.withFallback(gateway, request, onFallback = { model ->
            synchronized(used) { used.clear() }
            onStatus("Default AI model unavailable — retrying with $model…")
        }) { req ->
            gateway.runAgent(req, TOOLS, AiBudget(maxSteps = MAX_STEPS, timeoutMs = TIMEOUT_MS)) { call ->
                val (label, outcome) = handle(call, onStatus)
                synchronized(used) { used += label + if (outcome.isError) " (failed)" else "" }
                onStatus("Thinking…")
                outcome
            }
        }
        return result.map { (r, model) ->
            val text = r.text.trim().ifEmpty {
                when (r.stopReason) {
                    AiStopReason.MAX_STEPS -> "I couldn't finish within the step limit — try a more specific question (e.g. add a date range)."
                    AiStopReason.TIMEOUT -> "That took too long — try a narrower date range."
                    else -> "I didn't get an answer back from the AI provider."
                }
            }
            Answer(text, used.toList() + "model $model", r.stopReason)
        }
    }

    // ---- tool handling -----------------------------------------------------------------

    private suspend fun handle(call: AiToolCall, onStatus: (String) -> Unit): Pair<String, AiToolOutcome> {
        val args = runCatching { MiniJson.parse(call.argumentsJson.ifBlank { "{}" }) as? Map<*, *> }.getOrNull().orEmpty()
        fun str(key: String) = (args[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun int(key: String) = (args[key] as? Number)?.toInt() ?: (args[key] as? String)?.toIntOrNull()

        var label = call.name
        return try {
            val content = when (call.name) {
                "fetch_report" -> {
                    val id = str("report") ?: throw IllegalArgumentException("'report' is required")
                    val kind = when (str("kind")) { "dataset" -> SnKind.DATASET; "insight" -> SnKind.INSIGHT; else -> null }
                    val report = SupplyNoteCatalog.find(id, kind) ?: SupplyNoteCatalog.find(id)
                        ?: throw IllegalArgumentException("Unknown report '$id' — use an id from the catalogue")
                    val params = paramsFor(report, args)
                    label = report.title + rangeLabel(params) + (params["group_by"]?.let { " by $it" } ?: "")
                    onStatus("Fetching ${label.lowercase()}…")
                    val (base, key, _) = credentials()
                    val maxRows = (int("max_rows") ?: DEFAULT_ROWS).coerceIn(1, MAX_ROWS)
                    val result = client.fetchAll(base, report.path, params, key, maxRows, report.offsetPaged)
                    render(result, report.caveat)
                }
                "run_sql" -> {
                    val query = str("query") ?: throw IllegalArgumentException("'query' is required")
                    label = "SQL query"
                    onStatus("Running a SQL query…")
                    val (base, key, _) = credentials()
                    val body = buildMap<String, Any?> {
                        put("query", query)
                        (args["params"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { put("params", it) }
                    }
                    val env = client.post(base, "/v2/sql", body, key)
                    val (rows, extras) = SupplyNoteClient.splitData(env.data)
                    render(SnResult(rows.take(MAX_ROWS), extras, env.meta, 1, rows.size > MAX_ROWS, env.requestId), null)
                }
                "describe_dataset" -> {
                    val id = str("dataset") ?: throw IllegalArgumentException("'dataset' is required")
                    require(id.matches(Regex("[a-z0-9-]+"))) { "Invalid dataset id" }
                    label = "Columns of $id"
                    onStatus("Looking up the $id columns…")
                    describeCache[id] ?: describe(id).also { describeCache[id] = it }
                }
                else -> throw IllegalArgumentException("Unknown tool '${call.name}'")
            }
            label to AiToolOutcome(call.id, content.take(MAX_TOOL_CHARS), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            label to AiToolOutcome(call.id, "Error: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    /** Lenient version of the form rules: refused dates are dropped instead of failing the call. */
    private fun paramsFor(report: SnReport, args: Map<*, *>): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        (args["params"] as? Map<*, *>)?.forEach { (k, v) -> if (k != null && v != null) params[k.toString()] = v.toString() }
        listOf("outlet_ids", "group_by", "category").forEach { k -> (args[k] as? String)?.takeIf { it.isNotBlank() }?.let { params[k] = it.trim() } }
        val start = (args["start_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val end = (args["end_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        when (report.dates) {
            SnDates.REFUSED -> { params.remove("start_date"); params.remove("end_date") }
            SnDates.REQUIRED -> {
                require(start != null && end != null) { "${report.id} needs start_date and end_date (YYYY-MM-DD)" }
                params["start_date"] = LocalDate.parse(start).toString()
                params["end_date"] = LocalDate.parse(end).toString()
            }
            SnDates.OPTIONAL -> if (start != null && end != null) {
                params["start_date"] = LocalDate.parse(start).toString()
                params["end_date"] = LocalDate.parse(end).toString()
            }
        }
        return params
    }

    private suspend fun describe(id: String): String {
        val (base, key, _) = credentials()
        val env = client.get(base, "/v2/meta/datasets/$id", emptyMap(), key)
        val data = env.data
        val columns = ((data as? Map<*, *>)?.get("columns") as? List<*>) ?: (data as? List<*>)
        if (columns != null && columns.all { it is Map<*, *> }) {
            return buildString {
                (data as? Map<*, *>)?.let { d ->
                    listOf("table", "description", "date_column", "grain").forEach { k -> d[k]?.let { appendLine("$k: $it") } }
                }
                appendLine("columns:")
                columns.forEach { c ->
                    val m = c as Map<*, *>
                    append("- ").append(m["name"])
                    listOfNotNull(m["type"], m["unit"]).takeIf { it.isNotEmpty() }?.let { append(" (").append(it.joinToString(", ")).append(")") }
                    m["description"]?.let { append(": ").append(it.toString().take(160)) }
                    appendLine()
                }
            }.trimEnd()
        }
        return MiniJson.write(data).take(MAX_TOOL_CHARS)
    }

    // ---- rendering ---------------------------------------------------------------------

    private suspend fun render(result: SnResult, caveat: String?): String = buildString {
        val rows = withOutletNames(result.rows)
        val units = result.meta["units"] as? Map<*, *>
        if (units != null) {
            appendLine("Units: currency=${units["currency"] ?: "INR"}, values_include_tax=${units["values_include_tax"]}")
        }
        caveat?.let { appendLine("Caveat: $it") }
        flatten(result.extras).take(40).forEach { (k, v) -> appendLine("$k: $v") }
        (result.meta["warnings"] as? List<*>)?.forEach { appendLine("Warning: $it") }
        (result.meta["definitions"] as? Map<*, *>)?.entries?.take(4)?.forEach { appendLine("Definition ${it.key}: ${it.value.toString().take(240)}") }
        appendLine("Rows: ${rows.size}${if (result.truncated) " (stopped early — more rows exist; aggregate or narrow the filters)" else ""}")
        if (rows.isNotEmpty()) {
            val table = ReportParser.fromRecords(rows, "", emptyList()).table
            if (table != null) {
                val csv = ReportStats.tableSample(table, SHOWN_ROWS)
                appendLine(if (rows.size > SHOWN_ROWS) "First $SHOWN_ROWS rows (CSV, in the API's order):" else "CSV:")
                appendLine(csv)
            }
        }
    }.trimEnd()

    /** Adds `outlet_name` to rows that only carry an `outlet_id`. */
    private suspend fun withOutletNames(rows: List<Map<*, *>>): List<Map<*, *>> {
        if (rows.isEmpty() || rows.none { it.containsKey("outlet_id") && !it.containsKey("outlet_name") }) return rows
        val names = outletNames ?: runCatching {
            val (base, key, _) = credentials()
            val outlets = SupplyNoteCatalog.find("outlets", SnKind.DATASET)!!
            client.fetchAll(base, outlets.path, emptyMap(), key, 5000, false).rows
                .mapNotNull { r -> (r["outlet_id"] as? String)?.let { id -> id to (r["name"]?.toString() ?: id) } }.toMap()
        }.getOrNull()?.also { outletNames = it } ?: return rows
        return rows.map { r ->
            val id = r["outlet_id"] as? String
            if (id == null || r.containsKey("outlet_name")) r
            else LinkedHashMap<Any?, Any?>().apply { put("outlet_name", names[id] ?: id); putAll(r) }
        }
    }

    private fun flatten(map: Map<String, Any?>, prefix: String = ""): List<Pair<String, String>> =
        map.flatMap { (k, v) ->
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when {
                v is Map<*, *> && prefix.isEmpty() -> {
                    @Suppress("UNCHECKED_CAST")
                    flatten(v as Map<String, Any?>, key)
                }
                v is List<*> && v.size > 20 -> listOf(key to "${v.size} items")
                v is Map<*, *> || v is List<*> -> listOf(key to MiniJson.write(v).take(600))
                else -> listOf(key to v.toString())
            }
        }

    private fun rangeLabel(params: Map<String, String>): String {
        val s = params["start_date"] ?: return ""
        val e = params["end_date"] ?: return ""
        return " ($s → $e)"
    }

    // ---- prompt ------------------------------------------------------------------------

    private fun systemPrompt(): String {
        val today = LocalDate.now(IST)
        val fyStart = if (today.monthValue >= 4) LocalDate.of(today.year, 4, 1) else LocalDate.of(today.year - 1, 4, 1)
        val catalogue = buildString {
            appendLine("Insights (fetch_report kind=insight):")
            SupplyNoteCatalog.insights.forEach { appendLine("- ${it.id} [${datesLabel(it)}]: ${it.description}${it.caveat?.let { c -> " Note: $c" } ?: ""}") }
            appendLine("Datasets (fetch_report kind=dataset; SQL table in brackets):")
            SupplyNoteCatalog.datasets.forEach { appendLine("- ${it.id} {${it.table}} [${datesLabel(it)}]: ${it.description}${it.caveat?.let { c -> " Note: $c" } ?: ""}") }
        }
        return """
            You are "Ask AI", a concise analyst for a food business that uses SupplyNote (inventory, purchasing, recipes, POS sales).
            Answer questions in plain language using ONLY data you fetch with the tools. Never guess or invent numbers.

            Today is $today (IST). This month so far: ${today.withDayOfMonth(1)} to $today. Yesterday: ${today.minusDays(1)}.
            Last month: ${today.minusMonths(1).withDayOfMonth(1)} to ${today.withDayOfMonth(1).minusDays(1)}. Financial year started $fyStart.
            Resolve relative dates ("this month", "last week", "yesterday") into explicit YYYY-MM-DD ranges, inclusive.
            If the user gives no period, use this month so far and say so.

            How to answer common questions:
            - Wastage / most wasted item / wastage by outlet or reason: fetch_report wastage with group_by=product, outlet, reason or category.
              The ungrouped call returns a summary (total, top reason, worst outlet, wastage % of purchases).
            - Purchases ("how much did we purchase / buy"): value received into stock = sum of purchase_value in daily-valuation
              (SQL: SELECT sum(purchase_value) FROM sn_public.agg_outlet_daily_valuation WHERE business_day BETWEEN {start:Date} AND {end:Date}).
              For ordered vs received, or by vendor, use the purchase-fulfilment or vendor-scorecard insights. Say which basis you used.
            - Sales / revenue: daily-sales (net_sales excludes tax; gross_sales includes it) — aggregate with SQL on sn_public.agg_pos_daily.
            - Best/worst selling items or food cost: menu insight, menu-item-sales, category-sales.
            - Stock on hand / stock value: current-stock insight (no dates). Expiring stock: expiry insight.
            - Per-outlet overview: outlet-summary insight.
            Prefer aggregated insights or SQL (GROUP BY, sum, ORDER BY … LIMIT) over pulling raw rows.
            If you need column names for SQL, call describe_dataset first. SQL is ClickHouse: SELECT/WITH only, tables sn_public.*,
            pass values as named parameters like {start:Date} with the params object.
            If a call fails, read the error, fix the request and retry once; if it still fails, explain what went wrong.

            ${SupplyNoteCatalog.GENERAL_CAVEATS.replace("\n", "\n            ")}

            Answer style:
            - Lead with the direct answer and the key number(s). Money as ₹ with Indian grouping (₹1,72,350). Use outlet/product names, not ids.
            - State the period and the basis (e.g. "value received into stock, incl. tax").
            - Add at most 3 short supporting bullets (top items, shares, notable outliers). No tables wider than 4 columns.
            - Keep it under 150 words unless the user asks for detail.

            Catalogue:
            ${catalogue.trimEnd().replace("\n", "\n            ")}
        """.trimIndent()
    }

    private fun datesLabel(r: SnReport) = when (r.dates) {
        SnDates.REQUIRED -> "dates required"
        SnDates.OPTIONAL -> "dates optional"
        SnDates.REFUSED -> "no dates"
    }

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private const val MAX_STEPS = 10
        private const val TIMEOUT_MS = 180_000L
        private const val MAX_HISTORY = 12
        private const val MAX_HISTORY_CHARS = 4000
        private const val DEFAULT_ROWS = 500
        private const val MAX_ROWS = 5000
        private const val SHOWN_ROWS = 120
        private const val MAX_TOOL_CHARS = 16_000

        private val TOOLS = listOf(
            AiToolSpec(
                "fetch_report",
                "Fetch a SupplyNote insight or dataset from the catalogue. Returns units, summary fields and rows as CSV.",
                """
                {"type":"object","properties":{
                  "report":{"type":"string","description":"Insight or dataset id from the catalogue, e.g. wastage, daily-sales"},
                  "kind":{"type":"string","enum":["insight","dataset"],"description":"Needed only for purchase-fulfilment (exists as both)"},
                  "start_date":{"type":"string","description":"YYYY-MM-DD (IST), inclusive"},
                  "end_date":{"type":"string","description":"YYYY-MM-DD (IST), inclusive"},
                  "group_by":{"type":"string","description":"Insight grain, e.g. product, outlet, reason, category, vendor"},
                  "outlet_ids":{"type":"string","description":"Comma-separated outlet ids"},
                  "category":{"type":"string"},
                  "params":{"type":"object","description":"Other query parameters, e.g. {\"sort\":\"value\",\"limit\":\"20\"}"},
                  "max_rows":{"type":"integer","description":"Row cap (default 500)"}
                },"required":["report"]}
                """.trimIndent(),
            ),
            AiToolSpec(
                "run_sql",
                "Run one read-only ClickHouse SELECT against SupplyNote's sn_public tables. Best for totals and rankings.",
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"SELECT or WITH statement; use {name:Type} placeholders for values"},
                  "params":{"type":"object","description":"Named parameter values, e.g. {\"start\":\"2026-10-01\",\"end\":\"2026-10-15\"}"}
                },"required":["query"]}
                """.trimIndent(),
            ),
            AiToolSpec(
                "describe_dataset",
                "List a dataset's columns (name, type, unit, description) — use before writing SQL against it.",
                """{"type":"object","properties":{"dataset":{"type":"string","description":"Dataset id, e.g. daily-valuation"}},"required":["dataset"]}""",
            ),
        )
    }
}
