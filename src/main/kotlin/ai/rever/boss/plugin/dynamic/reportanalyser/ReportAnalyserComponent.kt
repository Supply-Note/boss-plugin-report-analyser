package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

class ReportAnalyserComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    pluginContext: PluginContext,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = ReportAnalyserViewModel(pluginContext)

    @Composable
    override fun Content() {
        ReportAnalyserContent(viewModel)
    }
}
