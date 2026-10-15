package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ReportAnalyserContent(viewModel: ReportAnalyserViewModel) {
    val state by viewModel.state.collectAsState()

    BossTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = BossThemeColors.SurfaceColor) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Header(state, viewModel)
                    ModeTabs(state.mode, viewModel::setMode)
                }
                if (state.mode == SourceMode.ASK) {
                    AskSection(state, viewModel, Modifier.weight(1f))
                } else {
                    ExplorerContent(state, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ExplorerContent(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state.mode) {
            SourceMode.SUPPLYNOTE -> SupplyNoteSection(state, viewModel)
            SourceMode.URL_FILE -> UrlFileSection(state, viewModel)
            SourceMode.ASK -> {}
        }
        state.busy?.let { label ->
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = BossThemeColors.AccentColor)
            Muted(label)
        }
        state.error?.let { ErrorBanner(it, viewModel::dismissError) }
        if (state.reports.isNotEmpty()) {
            ReportList(state, viewModel)
            state.selected?.let { ReportDetail(it, state.aiReadiness, viewModel) }
        }
    }
}

// ---- Header & mode ---------------------------------------------------------------------

@Composable
private fun Header(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Report analyser", color = BossThemeColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Muted("Ask questions, fetch & summarise reports")
        }
        val (label, color) = when (state.aiReadiness) {
            AiReadiness.READY -> "AI ready" to BossThemeColors.SuccessColor
            AiReadiness.NO_PROVIDER -> "AI: set up" to BossThemeColors.WarningColor
            AiReadiness.GATEWAY_MISSING -> "AI off" to BossThemeColors.TextMuted
        }
        Chip(label, color)
    }
}

@Composable
private fun ModeTabs(mode: SourceMode, onSelect: (SourceMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp)),
    ) {
        listOf(SourceMode.ASK to "Ask AI", SourceMode.SUPPLYNOTE to "Reports", SourceMode.URL_FILE to "URL / File").forEach { (m, label) ->
            val selected = m == mode
            Box(
                Modifier.weight(1f)
                    .background(if (selected) BossThemeColors.AccentColor.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---- Ask AI ----------------------------------------------------------------------------

private val SUGGESTIONS = listOf(
    "What is the top wasted item this month?",
    "How much did we purchase this month?",
    "Which outlet had the highest sales last week?",
    "Which vendors short-supplied us this month?",
    "What stock is expiring in the next 7 days?",
)

@Composable
private fun AskSection(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel, modifier: Modifier) {
    val busy = state.chatStatus != null
    val listState = rememberLazyListState()
    LaunchedEffect(state.chat.size, busy) {
        val count = state.chat.size + (if (busy) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyLine(state, viewModel)
        if (state.chat.isEmpty() && !busy) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ask anything about your SupplyNote data", color = BossThemeColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Muted("Wastage, purchases, sales, stock, expiry, vendors, recipes — in plain language. Answers come from live SupplyNote data.")
                Spacer(Modifier.height(4.dp))
                SUGGESTIONS.forEach { q ->
                    Text(
                        q,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                            .clickable(enabled = state.snSecretId != null) { viewModel.askFromUi(q) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.chat, key = { it.id }) { msg -> ChatBubble(msg, viewModel) }
                if (busy) {
                    item(key = "status") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), color = BossThemeColors.AccentColor, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Muted(state.chatStatus ?: "Thinking…")
                        }
                    }
                }
            }
        }
        ChatInput(state, viewModel)
    }
}

@Composable
private fun KeyLine(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    val selected = state.secrets.firstOrNull { it.id == state.snSecretId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted("Data:")
        Selector(
            current = selected?.label ?: "Choose SupplyNote key…",
            options = state.secrets.map { it.id to (if (it.isSupplyNote) "★ " else "") + it.label },
            onSelect = { viewModel.setSnSecret(it) },
            modifier = Modifier.weight(1f),
        )
        if (state.chat.isNotEmpty()) SmallButton("Clear", enabled = state.chatStatus == null) { viewModel.clearChat() }
    }
    if (selected == null) {
        Warning("Pick the secret that holds your SupplyNote API key (BOSS Secrets) to start asking.")
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, viewModel: ReportAnalyserViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (msg.fromUser) Alignment.End else Alignment.Start) {
        val bg = when {
            msg.fromUser -> BossThemeColors.AccentColor.copy(alpha = 0.18f)
            msg.isError -> BossThemeColors.ErrorColor.copy(alpha = 0.12f)
            else -> BossThemeColors.BackgroundColor
        }
        Box(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                .border(1.dp, if (msg.fromUser) Color.Transparent else BossThemeColors.BorderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            SelectionContainer {
                Text(
                    msg.text,
                    color = if (msg.isError) BossThemeColors.ErrorColor else BossThemeColors.TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        if (!msg.fromUser && msg.toolsUsed.isNotEmpty()) {
            Text(
                "Used: " + msg.toolsUsed.joinToString(" · "),
                color = BossThemeColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp),
            )
        }
        if (!msg.fromUser && !msg.isError) {
            Text(
                "Copy",
                color = BossThemeColors.AccentColor,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp).clickable { viewModel.copyText(msg.text) },
            )
        }
    }
}

@Composable
private fun ChatInput(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    val busy = state.chatStatus != null
    val canSend = !busy && state.chatDraft.isNotBlank() && state.snSecretId != null
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(6.dp))
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                .background(BossThemeColors.BackgroundColor).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (state.chatDraft.isEmpty()) {
                Text("Ask about wastage, purchases, sales, stock…", color = BossThemeColors.TextMuted, fontSize = 12.sp, maxLines = 1)
            }
            BasicTextField(
                value = state.chatDraft,
                onValueChange = viewModel::setChatDraft,
                textStyle = TextStyle(color = BossThemeColors.TextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(BossThemeColors.AccentColor),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    // Enter sends; Shift+Enter inserts a newline.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        if (canSend) viewModel.askFromUi(state.chatDraft)
                        true
                    } else false
                },
            )
        }
        PrimaryButton(if (busy) "…" else "Ask", enabled = canSend) { viewModel.askFromUi(state.chatDraft) }
    }
}

// ---- SupplyNote ------------------------------------------------------------------------

@Composable
private fun SupplyNoteSection(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    val form = state.sn
    val report = form.report
    val busy = state.busy != null

    Label("API key (BOSS Secrets)")
    if (!state.secretsAvailable) {
        Warning("The BOSS secret manager isn't available in this build.")
    } else {
        val selected = state.secrets.firstOrNull { it.id == state.snSecretId }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Selector(
                current = selected?.label ?: "Choose secret…",
                options = state.secrets.map { it.id to (if (it.isSupplyNote) "★ " else "") + it.label },
                onSelect = { viewModel.setSnSecret(it) },
                modifier = Modifier.weight(1f),
            )
            SmallButton("↻", enabled = true) { viewModel.refreshSecretsAsync() }
        }
        if (state.secrets.none { it.isSupplyNote }) {
            Muted("Save your key in BOSS Secrets with website api.supplynote.in and the key as the password.")
        }
    }

    Label("Report")
    Selector(
        current = report?.label ?: "Choose report…",
        options = SupplyNoteCatalog.insights.map { SnForm.keyOf(it) to "Insight · ${it.title}" } +
            SupplyNoteCatalog.datasets.map { SnForm.keyOf(it) to "Dataset · ${it.title}" },
        onSelect = { key -> viewModel.updateSn { it.copy(reportKey = key) } },
        modifier = Modifier.fillMaxWidth(),
    )
    report?.let {
        Muted(it.description)
        it.caveat?.let { c -> Muted("⚠ $c", color = BossThemeColors.WarningColor) }
    }

    if (report != null) {
        when (report.dates) {
            SnDates.REQUIRED -> DateRange(form, viewModel)
            SnDates.OPTIONAL -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.filterDates,
                        onCheckedChange = { c -> viewModel.updateSn { it.copy(filterDates = c) } },
                        colors = CheckboxDefaults.colors(checkedColor = BossThemeColors.AccentColor),
                        modifier = Modifier.size(28.dp),
                    )
                    Text("Filter by date range", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
                }
                if (form.filterDates) DateRange(form, viewModel)
            }
            SnDates.REFUSED -> Muted("No date range — this report is a current position or a catalogue.")
        }

        var showFilters by remember { mutableStateOf(false) }
        Disclosure("Filters", showFilters) { showFilters = it }
        if (showFilters) {
            LabeledField("Outlet ids (comma-separated)", form.outletIds, "o_123,o_456") { v -> viewModel.updateSn { it.copy(outletIds = v) } }
            if (report.kind == SnKind.INSIGHT) {
                LabeledField("group_by", form.groupBy, "e.g. outlet, product, category, reason") { v -> viewModel.updateSn { it.copy(groupBy = v) } }
            }
            LabeledField("Category", form.category, "Product category") { v -> viewModel.updateSn { it.copy(category = v) } }
            LabeledField("Extra parameters", form.extraParams, "sort=variance&include_zero_stock=true", singleLine = false) { v ->
                viewModel.updateSn { it.copy(extraParams = v) }
            }
            LabeledField("Max rows", form.maxRows, "2000") { v -> viewModel.updateSn { it.copy(maxRows = v.filter(Char::isDigit)) } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton("Fetch & analyse", enabled = !busy && state.snSecretId != null) { viewModel.fetchSupplyNoteFromForm() }
            if (report.kind == SnKind.DATASET) {
                SmallButton("Download CSV", enabled = !busy && state.snSecretId != null) { viewModel.exportFromForm() }
            }
        }
        state.lastExportUrl?.let { url ->
            Text(
                "Open last export link",
                color = BossThemeColors.AccentColor,
                fontSize = 11.sp,
                modifier = Modifier.clickable { viewModel.openInBrowser(url, "SupplyNote export") },
            )
        }
    }

    var showSql by remember { mutableStateOf(false) }
    Disclosure("Custom SQL", showSql) { showSql = it }
    if (showSql) {
        Muted("SELECT/WITH only, against sn_public.* tables (e.g. sn_public.agg_pos_daily). Max 10,000 rows, 10 s.")
        Field(form.sql, { v -> viewModel.updateSn { it.copy(sql = v) } }, "SELECT outlet_name, sum(net_sales) AS sales FROM sn_public.agg_pos_daily GROUP BY outlet_name", singleLine = false, mono = true, minHeight = 70)
        SmallButton("Run query", enabled = !busy && state.snSecretId != null && form.sql.isNotBlank()) { viewModel.runSqlFromForm() }
    }

    var showFeeds by remember { mutableStateOf(false) }
    Disclosure("Feeds", showFeeds) { showFeeds = it }
    if (showFeeds) {
        SmallButton(if (state.feeds.isEmpty()) "Load feeds" else "Reload feeds", enabled = !busy && state.snSecretId != null) { viewModel.loadFeedsAsync() }
        state.feeds.forEach { feed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(feed.name, color = BossThemeColors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOfNotNull(feed.id, feed.format).joinToString(" · "))
                }
                SmallButton("Analyse", enabled = !busy) { viewModel.fetchFeedAsync(feed) }
            }
        }
    }

    var showAdvanced by remember { mutableStateOf(false) }
    Disclosure("Connection", showAdvanced) { showAdvanced = it }
    if (showAdvanced) {
        LabeledField("API base URL", state.baseUrl, SupplyNoteCatalog.DEFAULT_BASE_URL) { viewModel.setBaseUrl(it) }
        Muted("The key is only ever sent to the host of its secret's website. Limit: 60 requests/minute per key.")
    }
}

@Composable
private fun DateRange(form: SnForm, viewModel: ReportAnalyserViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Label("Start (IST)")
            Field(form.startDate, { v -> viewModel.updateSn { it.copy(startDate = v) } }, "YYYY-MM-DD")
        }
        Column(Modifier.weight(1f)) {
            Label("End (IST)")
            Field(form.endDate, { v -> viewModel.updateSn { it.copy(endDate = v) } }, "YYYY-MM-DD")
        }
    }
}

// ---- URL / file ------------------------------------------------------------------------

@Composable
private fun UrlFileSection(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    val busy = state.busy != null
    val isUrl = ReportFetcher.isUrl(state.source)

    Label("Report URL or file path")
    Field(state.source, viewModel::setSource, "https://… or ~/Downloads/report.csv")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PrimaryButton("Analyse", enabled = !busy && state.source.isNotBlank()) { viewModel.fetchCurrent() }
        SmallButton("Browse…", enabled = !busy) { viewModel.pickFile() }
        if (isUrl) SmallButton("Download", enabled = true) { viewModel.openInBrowser(state.source.trim()) }
    }
    Muted("CSV, TSV, JSON, Excel (.xlsx), HTML tables, text and PDF. \"Download\" opens the link in the BOSS browser.")

    if (isUrl && state.secretsAvailable) {
        Label("Authentication")
        Selector(
            current = state.secrets.firstOrNull { it.id == state.secretId }?.label ?: "None",
            options = listOf<Pair<String?, String>>(null to "None") + state.secrets.map { it.id to it.label },
            onSelect = viewModel::setSecret,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.secretId != null) {
            Selector(
                current = state.authMode.label,
                options = AuthMode.entries.map { it to it.label },
                onSelect = viewModel::setAuthMode,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.authMode == AuthMode.HEADER) {
                LabeledField("Header name", state.headerName, "X-API-Key", onChange = viewModel::setHeaderName)
            }
        }
    }

    if (state.recentSources.isNotEmpty()) {
        Label("Recent")
        state.recentSources.take(5).forEach { recent ->
            Text(
                ReportAnalyserViewModel.displayPath(recent.source),
                color = BossThemeColors.AccentColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setSource(recent.source) }.padding(vertical = 2.dp),
            )
        }
    }

    if (state.downloads.isNotEmpty()) {
        Label("Recent BOSS downloads")
        state.downloads.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.fileName,
                    color = BossThemeColors.TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SmallButton("Analyse", enabled = !busy) { viewModel.analyseDownload(item) }
            }
        }
    }
}

// ---- Reports ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportList(state: ReportAnalyserState, viewModel: ReportAnalyserViewModel) {
    Divider()
    Label("Reports (${state.reports.size})")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.reports.forEach { r ->
            val selected = r.id == state.selectedId
            Text(
                r.name,
                color = if (selected) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) BossThemeColors.AccentColor.copy(alpha = 0.22f) else BossThemeColors.BackgroundColor)
                    .clickable { viewModel.select(r.id) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .widthIn(max = 180.dp),
            )
        }
    }
}

@Composable
private fun ReportDetail(report: Report, aiReadiness: AiReadiness, viewModel: ReportAnalyserViewModel) {
    var tab by remember(report.id) { mutableStateOf(if (report.aiSummary != null) 1 else 0) }
    var focus by remember(report.id) { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
            .background(BossThemeColors.BackgroundColor).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(report.name, color = BossThemeColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Close, "Remove report", tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(16.dp).clickable { viewModel.remove(report.id) },
            )
        }
        val time = SimpleDateFormat("HH:mm").format(Date(report.fetchedAt))
        Muted(
            listOfNotNull(
                report.parsed.format.label,
                report.rowCount?.let { "${ReportStats.fmtInt(it)} rows" },
                if (report.location != null) formatBytes(report.sizeBytes) else null,
                time,
                report.authLabel?.let { "via $it" },
            ).joinToString(" · "),
        )
        Text(report.source, color = BossThemeColors.TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (report.location != null) SmallButton(if (report.isUrl) "Open / download" else "Open file", enabled = true) { viewModel.openReportSource(report) }
            SmallButton("Copy summary", enabled = true) { viewModel.copySummary(report) }
            if (report.parsed.table != null) SmallButton("Copy CSV", enabled = true) { viewModel.copyCsv(report) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Summary", "AI summary", "Preview").forEachIndexed { i, label ->
                Text(
                    label,
                    color = if (tab == i) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { tab = i }.padding(vertical = 2.dp),
                )
            }
        }

        when (tab) {
            0 -> SelectableText(report.stats)
            1 -> {
                if (aiReadiness == AiReadiness.GATEWAY_MISSING) {
                    Muted("AI summaries need a BOSS build with the AI gateway. The Summary tab works offline.")
                } else {
                    Field(focus, { focus = it }, "Optional focus, e.g. \"which outlets wasted most?\"")
                    PrimaryButton(
                        when {
                            report.aiBusy -> "Summarising…"
                            report.aiSummary != null -> "Regenerate"
                            aiReadiness == AiReadiness.NO_PROVIDER -> "Set up AI & summarise"
                            else -> "Summarise with AI"
                        },
                        enabled = !report.aiBusy,
                    ) { viewModel.summariseSelected(focus.ifBlank { null }) }
                    if (report.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BossThemeColors.AccentColor)
                    report.aiError?.let { Text(it, color = BossThemeColors.ErrorColor, fontSize = 11.sp) }
                    report.aiSummary?.let { SelectableText(it, mono = false) }
                }
            }
            else -> SelectableText(previewOf(report))
        }
    }
}

private fun previewOf(report: Report): String {
    val table = report.parsed.table
    if (table != null && table.rows.isNotEmpty()) {
        val shown = minOf(PREVIEW_ROWS, table.rows.size)
        return "First $shown of ${table.rows.size} rows (CSV):\n\n" + ReportStats.tableSample(table, PREVIEW_ROWS)
    }
    val text = report.parsed.text
    if (text.isBlank()) return report.parsed.notes.joinToString("\n").ifBlank { "Nothing to preview." }
    return text.lineSequence().take(80).joinToString("\n").take(8000)
}

private fun formatBytes(n: Int): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

private const val PREVIEW_ROWS = 40

// ---- Small building blocks -------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(text, color = BossThemeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Muted(text: String, color: Color = BossThemeColors.TextMuted) {
    Text(text, color = color, fontSize = 11.sp)
}

@Composable
private fun Warning(text: String) = Muted(text, BossThemeColors.WarningColor)

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BossThemeColors.BorderColor))
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Disclosure(title: String, open: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!open) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(BossThemeColors.ErrorColor.copy(alpha = 0.12f)).padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(message, color = BossThemeColors.ErrorColor, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.Close, "Dismiss", tint = BossThemeColors.ErrorColor, modifier = Modifier.size(14.dp).clickable { onDismiss() })
    }
}

@Composable
private fun SelectableText(text: String, mono: Boolean = true) {
    SelectionContainer {
        Text(
            text,
            color = BossThemeColors.TextPrimary,
            fontSize = if (mono) 11.sp else 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = if (mono) 15.sp else 17.sp,
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeight: Int = 0,
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, BossThemeColors.BorderColor), RoundedCornerShape(4.dp))
            .background(BossThemeColors.BackgroundColor).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = BossThemeColors.TextMuted, fontSize = 12.sp, maxLines = if (singleLine) 1 else 4, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(BossThemeColors.AccentColor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LabeledField(label: String, value: String, placeholder: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Label(label)
        Field(value, onChange, placeholder, singleLine = singleLine)
    }
}

@Composable
private fun <T> Selector(current: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                .background(BossThemeColors.BackgroundColor)
                .clickable(enabled = options.isNotEmpty()) { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = BossThemeColors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (value, label) ->
                DropdownMenuItem(onClick = { open = false; onSelect(value) }) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Color.White else BossThemeColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (enabled) BossThemeColors.AccentColor else BossThemeColors.BorderColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SmallButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
