package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.SecretEntryData
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Report(
    val id: String,
    val name: String,
    /** What was requested: URL, file path, or a SupplyNote endpoint + filters. */
    val source: String,
    val isUrl: Boolean,
    /** Final URL after redirects, or absolute file path. Null for API results. */
    val location: String?,
    val fetchedAt: Long,
    val sizeBytes: Int,
    val parsed: ParsedReport,
    val stats: String,
    val authLabel: String? = null,
    /** Reading rules passed to the AI summary (SupplyNote caveats). */
    val domainHints: String? = null,
    val aiSummary: String? = null,
    val aiError: String? = null,
    val aiBusy: Boolean = false,
) {
    val rowCount: Int? get() = parsed.table?.rows?.size
}

/** A secret as shown in the picker — never carries the password. */
data class SecretOption(val id: String, val label: String, val isSupplyNote: Boolean)

data class RecentSource(val source: String, val secretId: String?, val mode: AuthMode, val headerName: String)

data class SnFeed(val id: String, val name: String, val format: String?)

enum class SourceMode { ASK, SUPPLYNOTE, URL_FILE }

data class ChatMessage(
    val id: Int,
    val fromUser: Boolean,
    val text: String,
    /** What the agent fetched to answer, e.g. "Wastage (2026-10-01 → 2026-10-15) by product". */
    val toolsUsed: List<String> = emptyList(),
    val isError: Boolean = false,
)

/** The SupplyNote request form. Dates are IST business days, YYYY-MM-DD. */
data class SnForm(
    val reportKey: String = "insight:outlet-summary",
    val startDate: String = defaultStart(),
    val endDate: String = defaultEnd(),
    /** For datasets where a date range is optional. */
    val filterDates: Boolean = false,
    val outletIds: String = "",
    val groupBy: String = "",
    val category: String = "",
    val extraParams: String = "",
    val maxRows: String = "2000",
    val sql: String = "",
) {
    val report: SnReport?
        get() = reportKey.split(':', limit = 2).takeIf { it.size == 2 }?.let { (kind, id) ->
            SupplyNoteCatalog.find(id, if (kind == "dataset") SnKind.DATASET else SnKind.INSIGHT)
        }

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        fun defaultEnd(): String = LocalDate.now(IST).minusDays(1).toString()
        fun defaultStart(): String = LocalDate.now(IST).minusDays(7).toString()
        fun keyOf(r: SnReport) = (if (r.kind == SnKind.DATASET) "dataset:" else "insight:") + r.id
    }
}

data class ReportAnalyserState(
    val mode: SourceMode = SourceMode.ASK,
    // Ask AI
    val chat: List<ChatMessage> = emptyList(),
    val chatStatus: String? = null,
    val chatDraft: String = "",
    val busy: String? = null,
    val error: String? = null,
    val reports: List<Report> = emptyList(),
    val selectedId: String? = null,
    val secrets: List<SecretOption> = emptyList(),
    val secretsAvailable: Boolean = false,
    val aiReadiness: AiReadiness = AiReadiness.GATEWAY_MISSING,
    // SupplyNote
    val sn: SnForm = SnForm(),
    val snSecretId: String? = null,
    val baseUrl: String = SupplyNoteCatalog.DEFAULT_BASE_URL,
    val feeds: List<SnFeed> = emptyList(),
    val lastExportUrl: String? = null,
    // URL / file
    val source: String = "",
    val secretId: String? = null,
    val authMode: AuthMode = AuthMode.BEARER,
    val headerName: String = "X-API-Key",
    val downloads: List<DownloadItemData> = emptyList(),
    val recentSources: List<RecentSource> = emptyList(),
) {
    val selected: Report? get() = reports.firstOrNull { it.id == selectedId }
}

/**
 * Shared state for the panel and the MCP tools: one instance per plugin load.
 */
class ReportAnalyserViewModel(private val context: PluginContext, pluginId: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetcher = ReportFetcher()
    private val sn = SupplyNoteClient()
    private val summariser = ReportSummariser(context)
    private val agent = SupplyNoteChatAgent(context, sn) { supplyNoteKey() }
    private val chatIds = AtomicInteger(1)
    private val nextId = AtomicInteger(1)
    private val storage: PluginStorageProvider? =
        runCatching { context.pluginStorageFactory?.createStorage(pluginId) }.getOrNull()

    private val _state = MutableStateFlow(ReportAnalyserState())
    val state: StateFlow<ReportAnalyserState> = _state.asStateFlow()

    /** Full secret entries, kept off the UI state so keys never reach composables. */
    @Volatile
    private var secretEntries: List<SecretEntryData> = emptyList()

    init {
        _state.update { it.copy(aiReadiness = summariser.readiness(), secretsAvailable = context.secretDataProvider != null) }
        scope.launch {
            loadSettings()
            refreshSecrets()
        }
        context.downloadDataProvider?.let { provider ->
            scope.launch {
                provider.downloads.collect { items ->
                    val reports = items
                        .filter { it.status == DownloadStatusData.COMPLETED }
                        .filter { it.fileName.substringAfterLast('.', "").lowercase() in ReportParser.SUPPORTED_EXTENSIONS }
                        .sortedByDescending { it.endTime ?: it.startTime }
                        .take(MAX_DOWNLOADS)
                    _state.update { it.copy(downloads = reports) }
                }
            }
        }
    }

    // ---- Inputs ------------------------------------------------------------------------

    fun setMode(mode: SourceMode) = _state.update { it.copy(mode = mode) }
    fun updateSn(transform: (SnForm) -> SnForm) = _state.update { it.copy(sn = transform(it.sn)) }

    fun setSnSecret(id: String?) {
        _state.update { it.copy(snSecretId = id) }
        scope.launch { saveSettings() }
    }

    fun setBaseUrl(url: String) {
        _state.update { it.copy(baseUrl = url) }
        scope.launch { saveSettings() }
    }

    fun setSource(value: String) {
        val remembered = _state.value.recentSources.firstOrNull { it.source == value.trim() }
        _state.update {
            if (remembered != null) {
                it.copy(source = value, secretId = remembered.secretId, authMode = remembered.mode, headerName = remembered.headerName)
            } else it.copy(source = value)
        }
    }

    fun setSecret(id: String?) = _state.update { it.copy(secretId = id) }
    fun setAuthMode(mode: AuthMode) = _state.update { it.copy(authMode = mode) }
    fun setHeaderName(name: String) = _state.update { it.copy(headerName = name) }
    fun select(id: String) = _state.update { it.copy(selectedId = id) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun remove(id: String) = _state.update { s ->
        val remaining = s.reports.filterNot { it.id == id }
        s.copy(reports = remaining, selectedId = if (s.selectedId == id) remaining.firstOrNull()?.id else s.selectedId)
    }

    // ---- Ask AI ------------------------------------------------------------------------

    fun setChatDraft(text: String) = _state.update { it.copy(chatDraft = text) }

    fun clearChat() = _state.update { if (it.chatStatus != null) it else it.copy(chat = emptyList()) }

    fun askFromUi(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.chatStatus != null) return
        _state.update { it.copy(chatDraft = "") }
        scope.launch {
            if (summariser.readiness() != AiReadiness.READY) {
                val readiness = summariser.promptToFix()
                _state.update { it.copy(aiReadiness = readiness) }
                if (readiness != AiReadiness.READY) {
                    _state.update { it.copy(chatDraft = q) }
                    return@launch
                }
            }
            ask(q)
        }
    }

    /** Asks the agent; the question and answer are appended to the shared chat. */
    suspend fun ask(question: String): ChatMessage {
        val history = _state.value.chat.filterNot { it.isError }.map { SupplyNoteChatAgent.Turn(it.fromUser, it.text) }
        val userMsg = ChatMessage(chatIds.getAndIncrement(), fromUser = true, text = question)
        _state.update { it.copy(chat = it.chat + userMsg, chatStatus = "Thinking…") }
        val reply = try {
            agent.ask(history, question) { status -> _state.update { it.copy(chatStatus = status) } }.fold(
                onSuccess = { ChatMessage(chatIds.getAndIncrement(), false, it.text, it.toolsUsed) },
                onFailure = { e -> ChatMessage(chatIds.getAndIncrement(), false, e.message ?: "Something went wrong", isError = true) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(chatStatus = null) }
            throw e
        } catch (e: Exception) {
            ChatMessage(chatIds.getAndIncrement(), false, e.message ?: "Something went wrong", isError = true)
        }
        _state.update { it.copy(chat = it.chat + reply, chatStatus = null, aiReadiness = summariser.readiness()) }
        return reply
    }

    // ---- SupplyNote actions (UI) -------------------------------------------------------

    fun fetchSupplyNoteFromForm() = launchAction {
        val form = _state.value.sn
        val report = form.report ?: error("Choose a report")
        val params = paramsFromForm(form, report)
        val result = fetchSupplyNote(report, params, form.maxRows.toIntOrNull() ?: DEFAULT_MAX_ROWS)
        toast("Fetched ${report.title}: ${result.rowCount ?: 0} rows", NotificationType.SUCCESS)
    }

    fun exportFromForm() = launchAction {
        val form = _state.value.sn
        val report = form.report ?: error("Choose a report")
        require(report.kind == SnKind.DATASET) { "Only datasets can be exported" }
        val url = export(report, paramsFromForm(form, report), "csv")
        if (openInBrowser(url, "${report.id}.csv")) {
            toast("Export ready — downloading in the BOSS browser", NotificationType.SUCCESS)
        }
    }

    fun runSqlFromForm() = launchAction {
        val query = _state.value.sn.sql.trim()
        require(query.isNotEmpty()) { "Write a SELECT query first" }
        val report = runSql(query, emptyMap(), _state.value.sn.maxRows.toIntOrNull() ?: DEFAULT_MAX_ROWS)
        toast("Query returned ${report.rowCount ?: 0} rows", NotificationType.SUCCESS)
    }

    fun loadFeedsAsync() = launchAction {
        val feeds = listFeeds()
        if (feeds.isEmpty()) toast("No feeds found for this key", NotificationType.INFO)
    }

    fun fetchFeedAsync(feed: SnFeed) = launchAction { fetchFeed(feed.id) }

    // ---- URL / file actions (UI) -------------------------------------------------------

    fun fetchCurrent() {
        val s = _state.value
        val source = s.source.trim()
        if (source.isEmpty() || s.busy != null) return
        val auth = if (ReportFetcher.isUrl(source)) {
            s.secretId?.let { id -> secretEntries.firstOrNull { it.id == id } }?.let { FetchAuth(it, s.authMode, s.headerName) }
        } else null
        launchAction {
            val report = analyse(source, auth)
            rememberSource(RecentSource(source, auth?.secret?.id, s.authMode, s.headerName))
            toast("Analysed ${report.name}", NotificationType.SUCCESS)
        }
    }

    fun analyseDownload(item: DownloadItemData) {
        _state.update { it.copy(mode = SourceMode.URL_FILE) }
        setSource(item.destinationPath)
        fetchCurrent()
    }

    fun pickFile() {
        val picker = context.filePickerProvider
        if (picker == null) {
            toast("File picker isn't available — paste a file path instead", NotificationType.WARNING)
            return
        }
        picker.pickFile("Choose a report", ReportParser.SUPPORTED_EXTENSIONS.toList()) { path ->
            if (path != null) {
                setSource(path)
                fetchCurrent()
            }
        }
    }

    /** Opens a URL in a BOSS browser tab — downloads land in the host's download manager. */
    fun openInBrowser(url: String, title: String = "Report"): Boolean {
        val ops = context.splitViewOperations
        if (ops == null || !ReportFetcher.isUrl(url)) {
            toast("Can't open a browser tab here", NotificationType.WARNING)
            return false
        }
        ops.openUrlInActivePanel(url, title, true)
        return true
    }

    fun openReportSource(report: Report) {
        val location = report.location ?: return
        if (report.isUrl) {
            openInBrowser(location)
        } else {
            val ops = context.splitViewOperations ?: return toast("Can't open files here", NotificationType.WARNING)
            if (report.parsed.format == ReportFormat.PDF || report.parsed.format == ReportFormat.HTML) {
                ops.openFileInBrowser(location, report.name)
            } else {
                ops.openFileInEditor(location, report.name)
            }
        }
    }

    fun summariseSelected(focus: String?) {
        val id = _state.value.selectedId ?: return
        scope.launch {
            if (summariser.readiness() != AiReadiness.READY) {
                val readiness = summariser.promptToFix()
                _state.update { it.copy(aiReadiness = readiness) }
                if (readiness != AiReadiness.READY) return@launch
            }
            summarise(id, focus)
        }
    }

    fun copySummary(report: Report) {
        val text = buildString {
            appendLine(report.name)
            appendLine(report.source)
            appendLine()
            report.aiSummary?.let { appendLine(it); appendLine() }
            append(report.stats)
        }
        val ok = context.clipboardProvider?.setText(text) ?: false
        toast(if (ok) "Summary copied" else "Clipboard unavailable", if (ok) NotificationType.SUCCESS else NotificationType.WARNING)
    }

    fun copyText(text: String) {
        val ok = context.clipboardProvider?.setText(text) ?: false
        toast(if (ok) "Copied" else "Clipboard unavailable", if (ok) NotificationType.SUCCESS else NotificationType.WARNING)
    }

    fun copyCsv(report: Report) {
        val table = report.parsed.table ?: return toast("This report has no table", NotificationType.INFO)
        val ok = context.clipboardProvider?.setText(ReportStats.tableSample(table, table.rows.size)) ?: false
        toast(if (ok) "Copied ${table.rows.size} rows as CSV" else "Clipboard unavailable", if (ok) NotificationType.SUCCESS else NotificationType.WARNING)
    }

    fun refreshSecretsAsync() {
        scope.launch { refreshSecrets() }
    }

    // ---- SupplyNote core (shared with MCP) ---------------------------------------------

    /** Builds query parameters for [report], enforcing the guide's date-range rules. */
    fun buildParams(
        report: SnReport,
        startDate: String?,
        endDate: String?,
        outletIds: String?,
        groupBy: String?,
        category: String?,
        extra: Map<String, String>,
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        val hasDates = !startDate.isNullOrBlank() || !endDate.isNullOrBlank()
        when (report.dates) {
            SnDates.REQUIRED -> {
                require(!startDate.isNullOrBlank() && !endDate.isNullOrBlank()) { "${report.title} needs a start and end date" }
            }
            SnDates.REFUSED -> require(!hasDates) { "${report.title} doesn't take a date range" }
            SnDates.OPTIONAL -> require(!hasDates || (!startDate.isNullOrBlank() && !endDate.isNullOrBlank())) { "Give both start and end dates" }
        }
        if (hasDates) {
            val start = parseDate(startDate!!, "start_date")
            val end = parseDate(endDate!!, "end_date")
            require(!end.isBefore(start)) { "end_date must be on or after start_date" }
            params["start_date"] = start.toString()
            params["end_date"] = end.toString()
        }
        outletIds?.replace(" ", "")?.takeIf { it.isNotEmpty() }?.let { params["outlet_ids"] = it }
        groupBy?.trim()?.takeIf { it.isNotEmpty() }?.let { params["group_by"] = it }
        category?.trim()?.takeIf { it.isNotEmpty() }?.let { params["category"] = it }
        extra.forEach { (k, v) -> if (k.isNotBlank()) params[k.trim()] = v.trim() }
        return params
    }

    suspend fun fetchSupplyNote(report: SnReport, params: Map<String, String>, maxRows: Int, secret: String? = null): Report = busy("Fetching ${report.title}…") {
        val (baseUrl, key, keyLabel) = supplyNoteKey(secret)
        val result = sn.fetchAll(baseUrl, report.path, params, key, maxRows.coerceIn(1, MAX_ROWS_LIMIT), report.offsetPaged)
        val hints = listOfNotNull(SupplyNoteCatalog.GENERAL_CAVEATS, report.caveat?.let { "- ${report.title}: $it" }).joinToString("\n")
        addReport(buildSnReport(report.title, "SupplyNote ${report.path}${describeParams(params)}", result, hints, keyLabel))
    }

    suspend fun runSql(query: String, sqlParams: Map<String, Any?>, maxRows: Int, secret: String? = null): Report = busy("Running SQL…") {
        val (baseUrl, key, keyLabel) = supplyNoteKey(secret)
        val body = buildMap<String, Any?> {
            put("query", query)
            if (sqlParams.isNotEmpty()) put("params", sqlParams)
        }
        val env = sn.post(baseUrl, "/v2/sql", body, key)
        val (rows, extras) = SupplyNoteClient.splitData(env.data)
        val result = SnResult(rows.take(maxRows), extras, env.meta, 1, rows.size > maxRows, env.requestId)
        val firstLine = query.lineSequence().first().take(60)
        addReport(buildSnReport("SQL: $firstLine", "SupplyNote POST /v2/sql\n$query", result, SupplyNoteCatalog.GENERAL_CAVEATS, keyLabel))
    }

    /** Requests a whole-dataset export and returns the time-limited download URL. */
    suspend fun export(report: SnReport, params: Map<String, String>, format: String, secret: String? = null): String = busy("Preparing export…") {
        require(report.kind == SnKind.DATASET) { "Only datasets can be exported" }
        require(format in setOf("csv", "jsonl", "parquet")) { "format must be csv, jsonl or parquet" }
        val (baseUrl, key, _) = supplyNoteKey(secret)
        val clean = params - "limit" - "cursor"
        val env = sn.get(baseUrl, report.path, clean + ("export" to format), key)
        val url = SupplyNoteClient.exportUrl(env.meta) ?: error("SupplyNote didn't return an export link (request id ${env.requestId})")
        _state.update { it.copy(lastExportUrl = url) }
        url
    }

    suspend fun listFeeds(secret: String? = null): List<SnFeed> = busy("Loading feeds…") {
        val (baseUrl, key, _) = supplyNoteKey(secret)
        val env = sn.get(baseUrl, "/v2/feeds", emptyMap(), key)
        val (rows, _) = SupplyNoteClient.splitData(env.data)
        val feeds = rows.mapNotNull { r ->
            val id = (r["feed_id"] ?: r["id"])?.toString() ?: return@mapNotNull null
            SnFeed(id, (r["name"] ?: r["title"] ?: id).toString(), (r["format"] ?: r["file_format"])?.toString())
        }
        _state.update { it.copy(feeds = feeds) }
        feeds
    }

    suspend fun fetchFeed(feedId: String, secret: String? = null): Report = busy("Fetching feed…") {
        require(feedId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid feed id" }
        val (baseUrl, key, keyLabel) = supplyNoteKey(secret)
        val file = sn.getFile(baseUrl, "/v2/feeds/$feedId/data", emptyMap(), key)
        val parsed = withContext(Dispatchers.Default) { ReportParser.parse(file.bytes, file.fileName, file.contentType) }
        val name = _state.value.feeds.firstOrNull { it.id == feedId }?.name ?: "Feed $feedId"
        addReport(
            Report(
                id = newId(), name = name, source = "SupplyNote feed $feedId", isUrl = false, location = null,
                fetchedAt = System.currentTimeMillis(), sizeBytes = file.bytes.size, parsed = parsed,
                stats = ReportStats.describe(parsed, ReportStats::isQuantityColumn),
                authLabel = keyLabel, domainHints = SupplyNoteCatalog.GENERAL_CAVEATS,
            ),
        )
    }

    fun supplyNoteConfigured(): Boolean = currentSnSecret() != null

    // ---- Generic core (shared with MCP) ------------------------------------------------

    /** Fetches/reads [source], parses and profiles it, and adds it to the report list. */
    suspend fun analyse(source: String, auth: FetchAuth?): Report {
        val isUrl = ReportFetcher.isUrl(source)
        return busy(if (isUrl) "Fetching…" else "Reading…") {
            val fetched = if (isUrl) fetcher.fetchUrl(source, auth) else fetcher.readFile(source)
            val report = withContext(Dispatchers.Default) {
                val parsed = ReportParser.parse(fetched.bytes, fetched.fileName, fetched.contentType)
                Report(
                    id = newId(),
                    name = fetched.fileName,
                    source = source,
                    isUrl = isUrl,
                    location = fetched.location,
                    fetchedAt = System.currentTimeMillis(),
                    sizeBytes = fetched.bytes.size,
                    parsed = parsed,
                    stats = ReportStats.describe(parsed),
                    authLabel = auth?.let { "${secretLabel(it.secret)} · ${it.mode.label}" },
                )
            }
            addReport(report)
        }
    }

    suspend fun summarise(id: String, focus: String?): Result<String> {
        report(id) ?: return Result.failure(IllegalArgumentException("Unknown report id '$id'"))
        updateReport(id) { it.copy(aiBusy = true, aiError = null) }
        val result = runCatching { summariser.summarise(report(id)!!, focus).getOrThrow() }
        updateReport(id) {
            result.fold(
                onSuccess = { text -> it.copy(aiBusy = false, aiSummary = text) },
                onFailure = { e -> it.copy(aiBusy = false, aiError = e.message ?: "AI summary failed") },
            )
        }
        _state.update { it.copy(aiReadiness = summariser.readiness()) }
        return result
    }

    fun report(id: String): Report? = _state.value.reports.firstOrNull { it.id == id }

    fun aiReady(): Boolean = summariser.readiness() == AiReadiness.READY

    fun aiDiagnostics(): String = summariser.diagnostics()

    /** Resolves a secret by id or website for agent calls; it may only be sent to its own site. */
    suspend fun resolveSecret(query: String, url: String): Result<SecretEntryData> {
        if (context.secretDataProvider == null) return Result.failure(IllegalStateException("Secret manager unavailable"))
        if (secretEntries.isEmpty()) refreshSecrets()
        val q = query.trim().lowercase()
        val match = secretEntries.firstOrNull { it.id == query.trim() }
            ?: secretEntries.firstOrNull { ReportFetcher.secretHost(it) == q.removePrefix("www.") }
            ?: secretEntries.firstOrNull { it.website.lowercase().contains(q) }
            ?: return Result.failure(IllegalArgumentException("No secret matches '$query'"))
        if (!ReportFetcher.secretMatchesUrl(match, url)) {
            return Result.failure(
                IllegalArgumentException("Secret '${secretLabel(match)}' belongs to ${ReportFetcher.secretHost(match) ?: "no website"}; refusing to send it to this URL"),
            )
        }
        return Result.success(match)
    }

    fun dispose() {
        scope.cancel()
    }

    // ---- Internals ---------------------------------------------------------------------

    private fun launchAction(block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        scope.launch {
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    private suspend fun <T> busy(label: String, block: suspend () -> T): T {
        _state.update { it.copy(busy = label, error = null) }
        try {
            return block()
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    private fun newId() = "r${nextId.getAndIncrement()}"

    private fun addReport(report: Report): Report {
        _state.update { s -> s.copy(reports = (listOf(report) + s.reports).take(MAX_REPORTS), selectedId = report.id) }
        return report
    }

    private fun updateReport(id: String, transform: (Report) -> Report) = _state.update { s ->
        s.copy(reports = s.reports.map { if (it.id == id) transform(it) else it })
    }

    private fun paramsFromForm(form: SnForm, report: SnReport): Map<String, String> {
        val useDates = report.dates == SnDates.REQUIRED || (report.dates == SnDates.OPTIONAL && form.filterDates)
        return buildParams(
            report,
            startDate = form.startDate.takeIf { useDates },
            endDate = form.endDate.takeIf { useDates },
            outletIds = form.outletIds,
            groupBy = form.groupBy.takeIf { report.kind == SnKind.INSIGHT },
            category = form.category,
            extra = parseExtraParams(form.extraParams),
        )
    }

    private fun currentSnSecret(): SecretEntryData? {
        val id = _state.value.snSecretId
        return secretEntries.firstOrNull { it.id == id }
    }

    /** Base URL, API key and a display label. Throws a readable error when not configured. */
    private suspend fun supplyNoteKey(secretQuery: String? = null): Triple<String, String, String> {
        if (secretEntries.isEmpty()) refreshSecrets()
        val secret = if (!secretQuery.isNullOrBlank()) {
            findSecret(secretQuery) ?: error("No secret matches '$secretQuery'")
        } else {
            currentSnSecret() ?: error(
                if (context.secretDataProvider == null) "BOSS secret manager isn't available"
                else "Add your SupplyNote API key to BOSS Secrets (website: api.supplynote.in, password: the key), then pick it here",
            )
        }
        require(secret.password.isNotBlank()) { "The selected secret has an empty password — paste the API key there" }
        // The key only ever goes to the base URL configured in the panel (agents can't change it).
        // A secret saved for a different real host is refused; a website that is just a label is allowed.
        val baseUrl = _state.value.baseUrl.ifBlank { SupplyNoteCatalog.DEFAULT_BASE_URL }
        require(ReportFetcher.secretHost(secret) == null || ReportFetcher.secretMatchesUrl(secret, baseUrl)) {
            "The secret '${secretLabel(secret)}' is saved for ${ReportFetcher.secretHost(secret)}, not ${runCatching { ReportFetcher.parseHttpUri(baseUrl).host }.getOrDefault(baseUrl)}"
        }
        return Triple(baseUrl, secret.password, secretLabel(secret))
    }

    private fun buildSnReport(name: String, source: String, result: SnResult, hints: String, keyLabel: String): Report {
        val notes = buildList {
            if (result.truncated) add("Stopped at ${result.rows.size} rows after ${result.pages} page(s) — narrow the filters or raise max rows for the full set.")
            if (result.rows.isEmpty() && result.extras.isEmpty()) add("No rows returned for these filters.")
        }
        val context = snContext(result)
        val text = buildString {
            appendLine(context)
            if (result.extras.isNotEmpty()) appendLine(MiniJson.write(result.extras).take(20_000))
        }
        val parsed = ReportParser.fromRecords(result.rows, text, notes)
        val stats = context + "\n\n" + ReportStats.describe(parsed, ReportStats::isQuantityColumn)
        return Report(
            id = newId(), name = name, source = source, isUrl = false, location = null,
            fetchedAt = System.currentTimeMillis(), sizeBytes = text.length, parsed = parsed, stats = stats,
            authLabel = keyLabel, domainHints = hints,
        )
    }

    /** Renders meta.units, summaries, warnings and definitions from a SupplyNote response. */
    private fun snContext(result: SnResult): String = buildString {
        val meta = result.meta
        (meta["units"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { units ->
            appendLine("Units: " + units.entries.joinToString(" · ") { "${it.key}=${render(it.value, 60)}" })
        }
        flatten(result.extras).take(25).forEach { (k, v) -> appendLine("$k: $v") }
        listOf("warnings", "staleness").forEach { key ->
            meta[key]?.let { v ->
                when (v) {
                    is List<*> -> v.forEach { appendLine("Warning: ${render(it, 300)}") }
                    else -> appendLine("${key.replaceFirstChar { it.uppercase() }}: ${render(v, 300)}")
                }
            }
        }
        (meta["definitions"] as? Map<*, *>)?.entries?.take(8)?.forEach { appendLine("Definition — ${it.key}: ${render(it.value, 300)}") }
        result.requestId?.let { appendLine("Request id: $it") }
    }.trimEnd()

    private fun flatten(map: Map<String, Any?>, prefix: String = ""): List<Pair<String, String>> =
        map.flatMap { (k, v) ->
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                is Map<*, *> -> if (prefix.isEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    flatten(v as Map<String, Any?>, key)
                } else listOf(key to render(v, 200))
                is List<*> -> listOf(key to (if (v.size > 5) "${v.size} items" else render(v, 200)))
                else -> listOf(key to render(v, 200))
            }
        }

    private fun render(v: Any?, max: Int): String = when (v) {
        null -> "null"
        is String -> v
        is Double -> ReportStats.fmt(v)
        is Map<*, *>, is List<*> -> MiniJson.write(v)
        else -> v.toString()
    }.take(max)

    private fun describeParams(params: Map<String, String>): String =
        if (params.isEmpty()) "" else " (" + params.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")"

    private fun parseDate(value: String, name: String): LocalDate =
        runCatching { LocalDate.parse(value.trim()) }.getOrElse { throw IllegalArgumentException("$name must be YYYY-MM-DD") }

    private suspend fun refreshSecrets() {
        val provider = context.secretDataProvider ?: return
        val all = ArrayList<SecretEntryData>()
        var offset = 0
        for (page in 0 until MAX_SECRET_PAGES) {
            val data = provider.getUserSecrets(limit = SECRET_PAGE_SIZE, offset = offset).getOrNull() ?: break
            all += data.data
            if (!data.hasMore || data.data.isEmpty()) break
            offset += data.data.size
        }
        secretEntries = all.distinctBy { it.id }
        _state.update { s ->
            val options = secretEntries.map { SecretOption(it.id, secretLabel(it), isSupplyNoteSecret(it)) }
                .sortedWith(compareBy({ !it.isSupplyNote }, { it.label.lowercase() }))
            val snId = s.snSecretId?.takeIf { id -> secretEntries.any { it.id == id } }
                ?: options.firstOrNull { it.isSupplyNote }?.id
            s.copy(
                secrets = options,
                snSecretId = snId,
                secretId = s.secretId?.takeIf { id -> secretEntries.any { it.id == id } },
            )
        }
    }

    private fun isSupplyNoteSecret(secret: SecretEntryData): Boolean {
        val host = ReportFetcher.secretHost(secret)
        if (host != null) return host == SupplyNoteCatalog.SECRET_DOMAIN || host.endsWith("." + SupplyNoteCatalog.SECRET_DOMAIN)
        // Label-style websites, e.g. "SN Aubree API".
        val site = secret.website.trim()
        return site.contains("supplynote", ignoreCase = true) || site.startsWith("SN ")
    }

    /** By id, exact website/label, or substring of the label (case-insensitive). */
    private fun findSecret(query: String): SecretEntryData? {
        val q = query.trim()
        return secretEntries.firstOrNull { it.id == q }
            ?: secretEntries.firstOrNull { it.website.equals(q, ignoreCase = true) }
            ?: secretEntries.filter { secretLabel(it).contains(q, ignoreCase = true) }.singleOrNull()
            ?: secretEntries.filter { secretLabel(it).contains(q, ignoreCase = true) && isSupplyNoteSecret(it) }.singleOrNull()
    }

    private fun secretLabel(secret: SecretEntryData): String {
        val site = secret.website.ifBlank { "(no website)" }
        return if (secret.username.isNotBlank()) "$site · ${secret.username}" else site
    }

    private suspend fun loadSettings() {
        val json = runCatching { storage?.getJson(KEY_SETTINGS) }.getOrNull()
        val settings = json?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
        val recents = (settings?.get("recentSources") as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            RecentSource(
                source = m["source"] as? String ?: return@mapNotNull null,
                secretId = m["secretId"] as? String,
                mode = runCatching { AuthMode.valueOf(m["mode"] as String) }.getOrDefault(AuthMode.BEARER),
                headerName = m["headerName"] as? String ?: "X-API-Key",
            )
        }
        _state.update {
            it.copy(
                recentSources = recents,
                snSecretId = settings?.get("snSecretId") as? String ?: it.snSecretId,
                baseUrl = settings?.get("baseUrl") as? String ?: it.baseUrl,
            )
        }
    }

    private suspend fun saveSettings() {
        val s = _state.value
        val json = MiniJson.write(
            mapOf(
                "snSecretId" to s.snSecretId,
                "baseUrl" to s.baseUrl,
                "recentSources" to s.recentSources.map {
                    mapOf("source" to it.source, "secretId" to it.secretId, "mode" to it.mode.name, "headerName" to it.headerName)
                },
            ),
        )
        runCatching { storage?.putJson(KEY_SETTINGS, json) }
    }

    private suspend fun rememberSource(recent: RecentSource) {
        val list = (listOf(recent) + _state.value.recentSources.filterNot { it.source == recent.source }).take(MAX_RECENT)
        _state.update { it.copy(recentSources = list) }
        saveSettings()
    }

    private fun toast(message: String, type: NotificationType) {
        runCatching { context.notificationProvider?.showToast(message = message, type = type, title = "Report analyser") }
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 2000
        const val MAX_ROWS_LIMIT = 50_000
        private const val MAX_REPORTS = 25
        private const val MAX_RECENT = 10
        private const val MAX_DOWNLOADS = 5
        private const val SECRET_PAGE_SIZE = 100
        private const val MAX_SECRET_PAGES = 10
        private const val KEY_SETTINGS = "settings"

        /** `a=1&b=2` or one `key=value` per line (values may contain commas, e.g. outlet ids). */
        fun parseExtraParams(text: String): Map<String, String> =
            text.split('&', '\n').mapNotNull { part ->
                val p = part.trim()
                if (p.isEmpty() || !p.contains('=')) null else p.substringBefore('=').trim() to p.substringAfter('=').trim()
            }.toMap()

        fun displayPath(path: String): String {
            val home = System.getProperty("user.home")
            return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
        }
    }
}
