package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class ReportAnalyserDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.reportanalyser"
    override val displayName: String = "Report analyser"
    override val version: String = "0.1.0"
    override val description: String = "Ask AI about your SupplyNote data — wastage, purchases, sales, stock — and fetch, download & summarise reports"
    override val author: String = "SupplyNote"
    override val url: String = "https://github.com/Supply-Note/boss-plugin-report-analyser"

    private var viewModel: ReportAnalyserViewModel? = null

    override fun register(context: PluginContext) {
        // One shared model so the panel and AI agents see the same reports.
        val vm = ReportAnalyserViewModel(context, pluginId)
        viewModel = vm

        context.panelRegistry.registerPanel(ReportAnalyserInfo) { ctx, panelInfo ->
            ReportAnalyserComponent(ctx, panelInfo, vm)
        }
        runCatching { context.registerMcpToolProvider(ReportAnalyserMcpToolProvider(pluginId, vm)) }
    }

    override fun dispose() {
        viewModel?.dispose()
        viewModel = null
    }
}
