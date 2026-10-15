package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension

object ReportAnalyserInfo : PanelInfo {
    override val id = PanelId("report-analyser", 50)
    override val displayName = "Report analyser"
    override val icon = Icons.Outlined.Extension
    override val defaultSlotPosition = left.bottom
}
