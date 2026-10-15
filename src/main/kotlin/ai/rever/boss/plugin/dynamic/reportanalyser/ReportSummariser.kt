package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.PluginContext

/** AI summaries through the host's AI gateway (uses whatever provider the user configured in BOSS). */
class ReportSummariser(private val context: PluginContext) {

    fun readiness(): AiReadiness = runCatching { AiAvailability.check(context) }.getOrDefault(AiReadiness.GATEWAY_MISSING)

    /** Opens the host's "set up AI" flow; returns the readiness afterwards. */
    suspend fun promptToFix(): AiReadiness =
        runCatching { AiAvailability.promptToFix(context, "Report summaries") }.getOrElse { readiness() }

    /** Which provider/model the gateway routes to, its capabilities and what else is available — for debugging. */
    fun diagnostics(): String {
        val gateway = runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull()
            ?: return "AI gateway: not available in this BOSS build"
        return buildString {
            appendLine("Readiness: ${readiness()}")
            val active = runCatching { gateway.activeModel() }.getOrNull()
            appendLine("Active: " + (active?.let { "${it.providerName} (${it.providerId}) · model ${it.modelId}" } ?: "none"))
            appendLine("Capabilities: " + runCatching { gateway.capabilities().sorted().joinToString() }.getOrDefault("unknown").ifBlank { "none reported" })
            runCatching { gateway.availableModels() }.getOrNull()?.forEach { p ->
                appendLine("Provider ${p.providerName} (${p.providerId}): " + p.models.joinToString { it.id })
            }
        }.trimEnd()
    }

    suspend fun summarise(report: Report, focus: String?): Result<String> {
        val gateway = runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull()
            ?: return Result.failure(IllegalStateException("AI gateway isn't available in this BOSS build"))
        if (readiness() != AiReadiness.READY) {
            return Result.failure(IllegalStateException("No AI provider configured — set one up in BOSS settings"))
        }
        val request = AiRequest(
            system = SYSTEM_PROMPT,
            messages = listOf(AiMessage(AiMessage.ROLE_USER, buildPrompt(report, focus))),
            temperature = 0.2f,
            maxTokens = 1500,
            timeoutMs = 120_000,
        )
        return AiRouting.withFallback(gateway, request) { gateway.complete(it) }.map { (reply, _) -> reply.text.trim() }
    }

    private fun buildPrompt(report: Report, focus: String?): String = buildString {
        appendLine("Report: ${report.name}")
        appendLine("Source: ${report.source}")
        appendLine()
        report.domainHints?.let {
            appendLine("How to read this data (rules you must follow):")
            appendLine(it)
            appendLine()
        }
        appendLine("Computed statistics (exact, from the full data):")
        appendLine(report.stats)
        appendLine()
        val table = report.parsed.table
        if (table != null && table.rows.isNotEmpty()) {
            val shown = minOf(table.rows.size, SAMPLE_ROWS)
            appendLine("Data sample (first $shown of ${table.rows.size} rows, CSV):")
            appendLine(ReportStats.tableSample(table, SAMPLE_ROWS).take(SAMPLE_CHARS))
        } else if (report.parsed.text.isNotBlank()) {
            val text = report.parsed.text
            appendLine("Report text${if (text.length > SAMPLE_CHARS) " (truncated)" else ""}:")
            appendLine(text.take(SAMPLE_CHARS))
        }
        if (!focus.isNullOrBlank()) {
            appendLine()
            appendLine("The reader specifically wants to know: $focus")
        }
    }

    companion object {
        private const val SAMPLE_ROWS = 150
        private const val SAMPLE_CHARS = 24_000
        private val SYSTEM_PROMPT = """
            You are a precise business analyst summarising a report for a busy reader.
            Write plain text (no markdown tables) with:
            1. A one-sentence headline of what the report shows.
            2. 3–6 bullet key findings with concrete numbers.
            3. Notable trends, outliers or risks, if any.
            4. Data-quality caveats, if any.
            Prefer the computed statistics for totals and ranges — the sample may be partial.
            Never invent figures that are not in the provided data.
        """.trimIndent()
    }
}
