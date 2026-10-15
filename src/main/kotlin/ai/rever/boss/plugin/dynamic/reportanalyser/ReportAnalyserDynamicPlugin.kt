package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class ReportAnalyserDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.reportanalyser"
    override val displayName: String = "Report analyser"
    override val version: String = "0.1.0"
    override val description: String = "Fetch & download reports & summarise"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-report-analyser"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(ReportAnalyserInfo) { ctx, panelInfo ->
            ReportAnalyserComponent(ctx, panelInfo, context)
        }
    }

    override fun dispose() {}
}
