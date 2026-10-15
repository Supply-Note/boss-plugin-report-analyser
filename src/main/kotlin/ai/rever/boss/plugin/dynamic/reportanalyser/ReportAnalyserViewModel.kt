package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the Report analyser panel.
 *
 * TODO: implement the tool behavior — Fetch & download reports & summarise
 */
class ReportAnalyserViewModel(private val context: PluginContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow("Not implemented yet")
    val status: StateFlow<String> = _status.asStateFlow()
}
