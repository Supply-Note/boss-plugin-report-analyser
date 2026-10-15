package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

class ReportAnalyserComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val viewModel: ReportAnalyserViewModel,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        ReportAnalyserContent(viewModel)
    }
}
