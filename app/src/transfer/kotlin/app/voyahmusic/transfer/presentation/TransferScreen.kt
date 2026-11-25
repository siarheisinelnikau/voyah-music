package app.voyahmusic.transfer.presentation

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.voyahmusic.shared.R
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.MusicWire.Status
import app.voyahmusic.shared.ui.*
import app.voyahmusic.shared.ui.MusicGlyphs.Icon
import app.voyahmusic.transfer.presentation.TransferViewModel.Link
import app.voyahmusic.transfer.presentation.TransferViewModel.State

@Composable
internal fun TransferScreen(
    state: TransferViewModel.Screen, model: TransferViewModel, update: Boolean, settings: () -> Unit,
    scan: () -> Unit, pick: () -> Unit, lastAddress: String,
) {
    val colors = musicColors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val connection = state.connection
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(lastAddress) }
    var pin by rememberSaveable { mutableStateOf("") }
    var forget by rememberSaveable { mutableStateOf(false) }
    var folderPicker by rememberSaveable { mutableStateOf(false) }
    val folders = listOf(Folder(0, stringResource(R.string.library_root))) + connection.folders
    val connected = connection.state == Link.CONNECTED
    val contentWidth = if (LocalConfiguration.current.screenWidthDp >= 616) 560.dp else Dp.Infinity

    MusicPage {
        MusicHeading(stringResource(R.string.transfer_name)) { SettingsAction(update, settings) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().musicPanel(Panel, colors.edge, 20).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val label = when (connection.state) {
                    Link.UNPAIRED -> R.string.no_car
                    Link.PAIRING -> R.string.waiting_approval
                    Link.CHECKING -> R.string.connecting
                    Link.CONNECTED -> R.string.car_connected
                    Link.OFFLINE -> R.string.car_offline
                    Link.DISCONNECTED -> R.string.car_disconnected
                    Link.INCOMPATIBLE -> R.string.incompatible_protocol
                }
                MusicText(stringResource(label), 17, medium = true, color = if (connected) colors.accent else Ink)
                val detail = if (connection.state == Link.PAIRING)
                    stringResource(R.string.match_confirmation, connection.confirmation) else connection.address
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    MusicText(detail, 14, color = Muted)
                }
            }
            if (connection.state != Link.UNPAIRED) {
                Spacer(Modifier.width(8.dp))
                when (connection.state) {
                    Link.PAIRING -> MusicButton(stringResource(R.string.cancel), model::cancel)
                    Link.CONNECTED, Link.CHECKING -> MusicButton(stringResource(R.string.disconnect), model::disconnect)
                    else -> MusicButton(stringResource(R.string.connect_car), model::reconnect, enabled = !state.busy)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = contentWidth).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (connection.state == Link.UNPAIRED) {
                    Column(Modifier.fillMaxWidth().musicPanel(Panel, colors.edge, 20).padding(16.dp)) {
                        MusicText(stringResource(R.string.transfer_instructions), 15, color = Muted, lineHeight = 21)
                        Spacer(Modifier.height(12.dp))
                        MusicButton(stringResource(R.string.scan_qr), scan, Modifier.fillMaxWidth(), primary = true, enabled = !state.busy)
                        Spacer(Modifier.height(8.dp))
                        MusicButton(stringResource(if (manual) R.string.hide_manual else R.string.manual_pair),
                            { manual = !manual }, Modifier.fillMaxWidth())
                        if (manual) {
                            Spacer(Modifier.height(14.dp))
                            MusicText(stringResource(R.string.car_address), 14, medium = true)
                            Spacer(Modifier.height(7.dp))
                            MusicInput(address, { address = it.take(30) }, stringResource(R.string.address_example),
                                enabled = !state.busy, height = 49, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            Spacer(Modifier.height(12.dp))
                            MusicText(stringResource(R.string.pairing_code), 14, medium = true)
                            Spacer(Modifier.height(7.dp))
                            MusicInput(pin, { pin = it.filter { digit -> digit in '0'..'9' }.take(8) },
                                stringResource(R.string.code_hint), enabled = !state.busy, height = 49,
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.height(10.dp))
                            MusicButton(stringResource(R.string.connect_car), {
                                focus.clearFocus()
                                model.pair(address.trim(), pin)
                            }, Modifier.fillMaxWidth(), primary = true, enabled = !state.busy)
                        }
                    }
                }
                if (connection.state in setOf(Link.OFFLINE, Link.DISCONNECTED, Link.INCOMPATIBLE)) {
                    MusicButton(stringResource(R.string.forget_car), { forget = true }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().musicPanel(Panel, colors.edge, 20).padding(16.dp)) {
                    Row(Modifier.height(25.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusicIcon(Icon.NOTE, 24, colors.accent)
                        Spacer(Modifier.width(9.dp))
                        MusicText(stringResource(R.string.files), 20, medium = true)
                    }
                    if (connected) {
                        Spacer(Modifier.height(9.dp))
                        val dest = folders.firstOrNull { it.id == connection.destination }?.name ?: folders.first().name
                        MusicButton(stringResource(R.string.destination_value, dest), { folderPicker = true },
                            Modifier.fillMaxWidth(), enabled = !state.busy)
                    }
                    Spacer(Modifier.height(8.dp))
                    state.files.forEach { file ->
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().musicPanel(Field, colors.edge, 12)
                            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp, end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MusicText(file.name, 15, medium = true, lines = 2)
                                if (file.size >= 0) {
                                    Spacer(Modifier.height(4.dp))
                                    MusicText(Formatter.formatShortFileSize(context, file.size), 13, color = Muted)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            MusicIconButton(Icon.CLOSE, stringResource(R.string.remove_file, file.name),
                                { model.remove(file) }, Modifier.size(48.dp), enabled = !state.busy)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    MusicButton(stringResource(if (state.files.isEmpty()) R.string.add_files else R.string.add_more),
                        pick, Modifier.fillMaxWidth(), primary = state.files.isEmpty(), enabled = !state.busy)
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = contentWidth).fillMaxWidth()) {
                val message = transferMessage(state)
                if (message.isNotEmpty()) {
                    MusicText(message, 15, color = if (state.state in setOf(State.FAILED, State.ADDRESS,
                        State.CODE, State.NO_WIFI, State.CONNECTION)) ErrorInk else if (state.state == State.DONE) colors.accent else Muted)
                    Spacer(Modifier.height(8.dp))
                }
                if (state.busy) {
                    if ((state.current?.size ?: 0) > 0) {
                        LinearProgressIndicator(progress = { (state.bytes.toDouble() / state.current!!.size).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.edge)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.edge)
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.state == State.SENDING || state.state == State.CONNECTING) {
                        MusicButton(stringResource(R.string.cancel), model::cancel, height = 52.dp)
                    }
                    MusicButton(stringResource(R.string.send_files), model::send, Modifier.weight(1f),
                        primary = true, enabled = connected && !state.busy && state.files.isNotEmpty(), height = 52.dp)
                }
            }
        }
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false },
        text = { MusicText(stringResource(R.string.forget_car_prompt), 16) },
        confirmButton = { TextButton(onClick = { model.forget(); forget = false }) { MusicText(stringResource(R.string.forget_car), 14) } },
        dismissButton = { TextButton(onClick = { forget = false }) { MusicText(stringResource(R.string.cancel), 14) } },
    )
    if (folderPicker && connected) AlertDialog(onDismissRequest = { folderPicker = false },
        title = { MusicText(stringResource(R.string.destination), 20, medium = true) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            folders.forEach { folder ->
                TextButton(onClick = { model.target(folder.id); folderPicker = false },
                    modifier = Modifier.fillMaxWidth()) { MusicText(folder.name, 16, Modifier.weight(1f)) }
            }
        } },
        confirmButton = { TextButton(onClick = { folderPicker = false }) { MusicText(stringResource(R.string.cancel), 14) } },
    )
}

@Composable
private fun transferMessage(state: TransferViewModel.Screen): String = when (state.state) {
    State.READY -> ""
    State.READING -> stringResource(R.string.reading_files)
    State.CONNECTING -> stringResource(R.string.connecting)
    State.SENDING -> stringResource(R.string.sending, state.completed + 1,
        state.completed + state.files.size, state.current?.name.orEmpty())
    State.DONE -> stringResource(R.string.sent_summary, state.completed - state.duplicates, state.duplicates)
    State.CANCELLED -> stringResource(R.string.cancelled)
    State.NO_WIFI -> stringResource(R.string.phone_no_wifi)
    State.ADDRESS -> stringResource(R.string.bad_address)
    State.CODE -> stringResource(R.string.bad_code)
    State.CONNECTION -> stringResource(R.string.connection_lost)
    State.FAILED -> stringResource(when (state.error) {
        Status.CODE -> R.string.wrong_code
        Status.FORMAT -> R.string.format_error
        Status.TOO_LARGE -> R.string.size_error
        Status.STORAGE -> R.string.storage_error
        Status.EXPIRED -> R.string.session_expired
        Status.DECLINED -> R.string.pair_denied
        Status.UNPAIRED -> R.string.car_forgot_phone
        Status.FOLDER -> R.string.folder_unavailable
        Status.BUSY -> R.string.car_busy
        Status.PROTOCOL_VERSION -> R.string.incompatible_protocol
        else -> R.string.transfer_error
    }).let { message -> listOfNotNull(state.current?.name, message).joinToString("\n") }
}
