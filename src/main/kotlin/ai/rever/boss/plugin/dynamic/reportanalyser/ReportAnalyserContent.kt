package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReportAnalyserContent(viewModel: ReportAnalyserViewModel) {
    val status by viewModel.status.collectAsState()

    BossTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = BossThemeColors.SurfaceColor) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    "Report analyser",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Fetch & download reports & summarise",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(status, color = BossThemeColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}
