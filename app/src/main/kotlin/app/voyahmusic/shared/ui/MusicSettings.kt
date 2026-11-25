package app.voyahmusic.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.voyahmusic.shared.R
import app.voyahmusic.shared.update.Updates

@Composable
internal fun MusicSettings(
    state: MusicSettingsState,
    installed: String,
    update: Updates.Screen,
    onBack: () -> Unit,
    onLanguage: (String) -> Unit,
    onTheme: (Boolean) -> Unit,
    onStartup: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    var languagesOpen by remember { mutableStateOf(false) }
    val themes = musicColors
    val languages = listOf("system" to stringResource(R.string.system_language),
        "en" to "English", "ru" to "Русский", "kk" to "Қазақша")
    MusicPage {
        MusicHeading(stringResource(R.string.settings), onBack)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().musicPanel(Panel, themes.edge, 20).padding(16.dp)) {
                    MusicText(stringResource(R.string.appearance), 21, medium = true)
                    Spacer(Modifier.height(17.dp))
                    MusicText(stringResource(R.string.theme), 14, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MusicButton(stringResource(R.string.graphite), { onTheme(false) },
                            Modifier.weight(1f), primary = !state.amber)
                        MusicButton(stringResource(R.string.amber), { onTheme(true) },
                            Modifier.weight(1f), primary = state.amber)
                    }
                    Spacer(Modifier.height(17.dp))
                    MusicText(stringResource(R.string.language), 14, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    MusicButton(languages.firstOrNull { it.first == state.language }?.second ?: languages[0].second,
                        { languagesOpen = true }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().musicPanel(Panel, themes.edge, 20).padding(16.dp)) {
                    MusicText(stringResource(R.string.updates), 21, medium = true)
                    Spacer(Modifier.height(8.dp))
                    MusicText(stringResource(R.string.installed_version, installed), 15, color = Muted)
                    Spacer(Modifier.height(7.dp))
                    OldSwitch(state.startup, onStartup, stringResource(R.string.startup_updates))
                    Spacer(Modifier.height(6.dp))
                    MusicButton(stringResource(R.string.check_updates), onCheck, Modifier.fillMaxWidth(),
                        enabled = update.phase != Updates.Phase.CHECKING && update.phase != Updates.Phase.DOWNLOADING)
                    if (update.phase in setOf(Updates.Phase.AVAILABLE, Updates.Phase.READY, Updates.Phase.DOWNLOAD_FAILED)) {
                        Spacer(Modifier.height(8.dp))
                        MusicButton(stringResource(if (update.phase == Updates.Phase.READY) R.string.install_update
                            else R.string.download_install), onInstall, Modifier.fillMaxWidth(), primary = true)
                    }
                    val status = when (update.phase) {
                        Updates.Phase.IDLE -> null
                        Updates.Phase.CHECKING -> stringResource(R.string.checking)
                        Updates.Phase.CURRENT -> stringResource(R.string.up_to_date)
                        Updates.Phase.AVAILABLE -> stringResource(R.string.update_available, update.version)
                        Updates.Phase.CHECK_FAILED -> stringResource(R.string.check_failed)
                        Updates.Phase.DOWNLOADING -> stringResource(R.string.downloading, update.percent)
                        Updates.Phase.READY -> stringResource(R.string.ready_to_install, update.version)
                        Updates.Phase.DOWNLOAD_FAILED -> stringResource(R.string.download_failed)
                    }
                    if (status != null) {
                        Spacer(Modifier.height(8.dp))
                        MusicText(status, 15, color = Muted)
                    }
                    if (update.phase == Updates.Phase.DOWNLOADING) {
                        LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (languagesOpen) {
        AlertDialog(onDismissRequest = { languagesOpen = false },
            title = { MusicText(stringResource(R.string.language), 20, medium = true) },
            text = { Column {
                languages.forEach { (id, label) ->
                    TextButton(onClick = { languagesOpen = false; onLanguage(id) }, modifier = Modifier.fillMaxWidth()) {
                        MusicText(label, 16, Modifier.weight(1f))
                        RadioButton(selected = id == state.language, onClick = null)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { languagesOpen = false }) {
                MusicText(stringResource(R.string.cancel), 14, color = themes.accent)
            } },
        )
    }
}
