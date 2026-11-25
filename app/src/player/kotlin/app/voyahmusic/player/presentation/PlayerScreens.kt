@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package app.voyahmusic.player.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player
import app.voyahmusic.player.data.TrustedPhones
import app.voyahmusic.player.domain.Track
import app.voyahmusic.player.playback.PlaybackService
import app.voyahmusic.shared.R
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.PairQr
import app.voyahmusic.shared.ui.*
import app.voyahmusic.shared.ui.MusicGlyphs.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun PlayerScreen(
    library: PlayerViewModel.LibraryState, media: PlaybackService.State?, player: PlaybackService?,
    checkpoint: Int, model: PlayerViewModel, update: Boolean, settings: () -> Unit, receive: () -> Unit,
) {
    val track = library.snapshot?.tracks?.firstOrNull { it.id == media?.id }
    MusicPage { wide ->
        MusicHeading(stringResource(R.string.player_name)) { SettingsAction(update, settings) }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val compact = !wide || maxHeight < 382.dp
            val availableWidth = maxWidth
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    val panelWidth = ((availableWidth.value + 56) * .36f).coerceIn(320f, 445f).toInt().dp
                    NowPlaying(track, media, player, checkpoint, compact, false,
                        Modifier.width(panelWidth).fillMaxHeight())
                    LibraryPanel(library, media?.id, player, model, receive, Modifier.weight(1f).fillMaxHeight(), true)
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NowPlaying(track, media, player, checkpoint, true, availableWidth < 343.dp, Modifier.fillMaxWidth())
                    LibraryPanel(library, media?.id, player, model, receive, Modifier.weight(1f), false)
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(
    track: Track?, media: PlaybackService.State?, player: PlaybackService?, checkpoint: Int,
    compact: Boolean, narrow: Boolean, modifier: Modifier,
) {
    val colors = musicColors
    val enabled = track != null && player != null
    val playing = media?.wantsPlay == true
    Column(modifier.musicPanel(Panel, colors.selectedEdge, 22)
        .padding(if (compact) 12.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(2.dp))
        MusicText(stringResource(R.string.now_playing), 15, color = colors.accent, medium = true)
        if (!compact) {
            Spacer(Modifier.weight(1f))
            val art = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 590) 126 else 82
            Spacer(Modifier.height(8.dp))
            val shape = RoundedCornerShape(22.dp)
            Box(Modifier.size(art.dp).background(Brush.linearGradient(listOf(colors.selected, Field)), shape)
                .border(1.dp, colors.selectedEdge, shape),
                contentAlignment = Alignment.Center) { MusicIcon(Icon.NOTE, art / 2, colors.accent) }
        }
        Spacer(Modifier.height(if (compact) 10.dp else 20.dp))
        MusicText(track?.title ?: stringResource(R.string.player_empty), if (compact) 21 else 29,
            Modifier.fillMaxWidth(), medium = true, align = TextAlign.Center, lines = if (compact) 1 else 2)
        Spacer(Modifier.height(6.dp))
        MusicText(track?.let(::trackDetail) ?: stringResource(R.string.player_empty_hint), 15, color = Muted,
            align = TextAlign.Center, lines = 1)
        if (!compact) Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        PlayerPosition(player, media, checkpoint)
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MusicIconButton(Icon.SHUFFLE, stringResource(R.string.shuffle), { player?.shuffle() },
                Modifier.size(48.dp), enabled, active = media?.shuffle == true, glyphSize = 21)
            MusicIconButton(Icon.PREVIOUS, stringResource(R.string.previous), { player?.previous() },
                Modifier.size(48.dp), enabled)
            MusicIconButton(if (playing) Icon.PAUSE else Icon.PLAY, stringResource(if (playing) R.string.pause else R.string.play),
                { player?.toggle() }, Modifier.size(if (narrow) 56.dp else 70.dp, if (compact) 52.dp else 58.dp), enabled, primary = true)
            MusicIconButton(Icon.NEXT, stringResource(R.string.next), { player?.next() },
                Modifier.size(48.dp), enabled && player.hasNext())
            val repeat = media?.repeat ?: Player.REPEAT_MODE_OFF
            MusicIconButton(if (repeat == Player.REPEAT_MODE_ONE) Icon.REPEAT_ONE else Icon.REPEAT,
                stringResource(when (repeat) {
                    Player.REPEAT_MODE_ONE -> R.string.repeat_one
                    Player.REPEAT_MODE_ALL -> R.string.repeat_all
                    else -> R.string.repeat_off
                }), { player?.repeat() }, Modifier.size(48.dp), enabled, active = repeat != Player.REPEAT_MODE_OFF,
                glyphSize = 21)
        }
        if (media?.failed == true) {
            Spacer(Modifier.height(8.dp))
            MusicText(stringResource(R.string.playback_error), 14, color = ErrorInk, align = TextAlign.Center)
        }
    }
}

@Composable
private fun PlayerPosition(player: PlaybackService?, media: PlaybackService.State?, checkpoint: Int) {
    val colors = musicColors
    var active by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { active = true; onStopOrDispose { active = false } }
    val sampled by produceState(player?.position() ?: 0L, player, media, checkpoint, active) {
        value = player?.position() ?: 0
        while (active && player != null && media?.playing == true) { delay(500); value = player.position() }
    }
    val duration = player?.duration() ?: 0
    val ratio = if (duration > 0) (sampled.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    var drag by remember(media?.id) { mutableStateOf<Float?>(null) }
    val current = drag ?: ratio
    val label = stringResource(R.string.playback_position)
    val seek = Modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
        if (duration > 0) setProgress { player?.seek((it * duration).toLong()); true }
    }.then(if (duration <= 0) Modifier else Modifier
        .pointerInput(player, duration) { detectTapGestures { player?.seek((it.x / size.width * duration).toLong()) } }
        .pointerInput(player, duration) {
            detectDragGestures(
                onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let { player?.seek((it * duration).toLong()) }; drag = null },
                onDragCancel = { drag = null },
            ) { event, _ -> event.consume(); drag = (event.position.x / size.width).coerceIn(0f, 1f) }
        })
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 7.dp).height(26.dp).then(seek)) {
        val y = size.height / 2
        val end = 0.dp.toPx()
        val x = end + (size.width - 2 * end) * current
        drawLine(colors.edge.copy(alpha = .54f), Offset(end, y), Offset(size.width - end, y), 2.dp.toPx())
        if (x > end) drawLine(colors.accent, Offset(end,y), Offset(x,y), 2.dp.toPx())
        drawCircle(colors.accent, if (duration > 0) 5.dp.toPx() else 3.dp.toPx(),
            Offset(x,y), style = if (duration > 0) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.dp.toPx()))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        MusicText(formatTime(drag?.let { (it * duration).toLong() } ?: sampled), 14, color = Muted)
        MusicText(formatTime(duration), 14, color = Muted)
    }
}

private fun formatTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private fun trackDetail(track: Track): String = listOf(track.artist, track.album).filter(String::isNotBlank)
    .joinToString(" · ").ifEmpty { track.file.name }

@Composable
private fun LibraryPanel(
    state: PlayerViewModel.LibraryState, selected: String?, playback: PlaybackService?, model: PlayerViewModel,
    receive: () -> Unit, modifier: Modifier, wide: Boolean,
) {
    val colors = musicColors
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<Track?>(null) }
    var move by remember { mutableStateOf<Track?>(null) }
    val folders = state.snapshot?.folders.orEmpty()
    Column(modifier.fillMaxWidth().musicPanel(Panel, colors.edge, 20).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.folder != 0L) {
                MusicIconButton(Icon.BACK, stringResource(R.string.library), { model.openFolder(0) }, Modifier.size(48.dp))
                Spacer(Modifier.width(8.dp))
            }
            MusicText(folders.firstOrNull { it.id == state.folder }?.name ?: stringResource(R.string.library),
                if (wide) 24 else 20, Modifier.weight(1f), medium = true, lines = 1)
            Spacer(Modifier.width(6.dp))
            if (state.folder == 0L) {
                MusicIconButton(Icon.FOLDER_ADD, stringResource(R.string.new_folder), { create = true; name = "" }, Modifier.size(48.dp))
                Spacer(Modifier.width(8.dp))
            }
            MusicButton(stringResource(R.string.pair_phone), receive, primary = state.snapshot?.tracks?.isEmpty() == true)
        }
        Spacer(Modifier.height(10.dp))
        MusicInput(state.query, model::search, stringResource(R.string.search_library), search = true)
        Spacer(Modifier.height(8.dp))
        val term = state.query.trim()
        if (state.tracks.isEmpty() && state.folders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MusicText(stringResource(when {
                        state.snapshot == null -> R.string.loading_library
                        term.isNotEmpty() -> R.string.no_matches
                        state.folder != 0L -> R.string.empty_folder
                        else -> R.string.empty_library
                    }), 19, medium = true)
                    if (state.snapshot != null && term.isEmpty() && state.folder == 0L) {
                        Spacer(Modifier.height(7.dp))
                        MusicText(stringResource(R.string.empty_library_hint), 15, color = Muted, align = TextAlign.Center)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.folders, key = { "folder-${it.id}" }) { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).musicPanel(Field, colors.edge, 14)
                        .clickable { model.openFolder(item.id) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MusicIcon(Icon.FOLDER, 23, colors.accent)
                        Spacer(Modifier.width(10.dp))
                        MusicText(item.name, 16, Modifier.weight(1f), medium = true)
                    }
                }
                items(state.tracks, key = Track::id) { item ->
                    val active = item.id == selected
                    Row(Modifier.fillMaxWidth().musicPanel(if (active) colors.selected else Field,
                        if (active) colors.selectedEdge else colors.edge, 14), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 60.dp).clickable(enabled = playback != null) {
                            playback?.select(item.id)
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                            MusicText(item.title, 16, medium = true, lines = 2, color = if (active) colors.accent else Ink)
                            MusicText(trackDetail(item), 14, color = Muted, lines = 1)
                        }
                        Box {
                            MusicIconButton(Icon.MORE, stringResource(R.string.track_options), { menu = item.id }, Modifier.size(48.dp))
                            DropdownMenu(expanded = menu == item.id, onDismissRequest = { menu = null }) {
                                DropdownMenuItem(text = { MusicText(stringResource(R.string.move_track), 16) },
                                    onClick = { menu = null; move = item })
                                DropdownMenuItem(text = { MusicText(stringResource(R.string.delete_track), 16) },
                                    onClick = { menu = null; delete = item })
                            }
                        }
                    }
                }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false },
        title = { MusicText(stringResource(R.string.new_folder), 20, medium = true) },
        text = { MusicInput(name, { name = it.take(60) }, stringResource(R.string.folder_name)) },
        confirmButton = { TextButton(onClick = { model.createFolder(name); create = false }) { MusicText(stringResource(R.string.create_folder), 14) } },
        dismissButton = { TextButton(onClick = { create = false }) { MusicText(stringResource(R.string.cancel), 14) } },
    )
    delete?.let { item ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { MusicText(stringResource(R.string.delete_track), 20, medium = true) },
            text = { MusicText(stringResource(R.string.delete_hint, item.title), 16) },
            confirmButton = { TextButton(onClick = { model.delete(item); delete = null }) { MusicText(stringResource(R.string.delete_track), 14) } },
            dismissButton = { TextButton(onClick = { delete = null }) { MusicText(stringResource(R.string.cancel), 14) } },
        )
    }
    move?.let { item ->
        AlertDialog(onDismissRequest = { move = null },
            title = { MusicText(stringResource(R.string.move_track), 20, medium = true) },
            text = { Column(Modifier.heightIn(max = 312.dp).verticalScroll(rememberScrollState())) {
                (listOf(Folder(0, stringResource(R.string.library_root))) + folders).forEach { folder ->
                    TextButton(onClick = { model.move(item, folder.id); move = null }, modifier = Modifier.fillMaxWidth()) {
                        MusicText(folder.name, 16, Modifier.weight(1f))
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { move = null }) { MusicText(stringResource(R.string.cancel), 14) } },
        )
    }
}

@Composable
internal fun ReceiverScreen(state: PlayerViewModel.Receiver, model: PlayerViewModel, back: () -> Unit) {
    val colors = musicColors
    val ready = state.state == PlayerViewModel.Receiving.WAITING
    var networkMenu by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<TrustedPhones.Phone?>(null) }
    MusicPage {
        MusicHeading(stringResource(R.string.pair_phone), back)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .musicPanel(Panel, colors.edge, 20).padding(22.dp)) {
                MusicText(stringResource(R.string.receive_instructions), 17, color = Muted, lineHeight = 24)
                if (ready) {
                    state.request?.let { request ->
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().musicPanel(colors.selected, colors.selectedEdge, 14).padding(14.dp)) {
                            MusicText(stringResource(R.string.approve_phone, request.name, request.digits), 18, medium = true)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MusicButton(stringResource(R.string.deny), { model.choose(request, false) }, Modifier.weight(1f))
                                MusicButton(stringResource(R.string.allow), { model.choose(request, true) }, Modifier.weight(1f), primary = true)
                            }
                        }
                    }
                    val host = state.link?.address?.hostAddress.orEmpty()
                    val pixels = with(LocalDensity.current) { 202.dp.roundToPx() }
                    val qr by produceState<Bitmap?>(null, host, state.fingerprint, pixels) {
                        value = withContext(Dispatchers.Default) { PairQr(host, state.fingerprint).bitmap(pixels) }
                    }
                    val qrImage: @Composable () -> Unit = {
                        Box(Modifier.size(202.dp)) {
                            qr?.let { Image(it.asImageBitmap(), stringResource(R.string.pair_qr),
                                Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                    }
                    val details: @Composable () -> Unit = {
                        Column {
                            ReceiveValue(R.string.car_address, host)
                            Spacer(Modifier.height(8.dp))
                            ReceiveValue(R.string.pairing_code, MusicWire.pairingCode(state.fingerprint))
                            if (state.networks.size > 1) {
                                Spacer(Modifier.height(8.dp))
                                Box {
                                    MusicButton(stringResource(R.string.car_address), { networkMenu = true })
                                    DropdownMenu(networkMenu, { networkMenu = false }) {
                                        state.networks.forEach { link ->
                                            DropdownMenuItem(text = { MusicText(link.address.hostAddress.orEmpty(), 16) },
                                                onClick = { model.startReceiving(link); networkMenu = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 650) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            qrImage()
                            Box(Modifier.weight(1f)) { details() }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { qrImage() }
                        Spacer(Modifier.height(12.dp))
                        details()
                    }
                }
                Spacer(Modifier.height(18.dp))
                MusicText(stringResource(when (state.state) {
                    PlayerViewModel.Receiving.WAITING -> R.string.receive_active
                    PlayerViewModel.Receiving.OPENING -> R.string.opening_receiver
                    PlayerViewModel.Receiving.NO_NETWORK -> R.string.receive_no_wifi
                    PlayerViewModel.Receiving.FAILED -> R.string.receive_failed
                    else -> R.string.receive_expired
                }), 16, medium = true, color = if (ready) colors.accent else Ink)
                if (state.latest.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    MusicText(stringResource(R.string.received_track, state.latest), 15, color = Muted)
                }
                if (!ready && state.state != PlayerViewModel.Receiving.OPENING) {
                    Spacer(Modifier.height(16.dp))
                    MusicButton(stringResource(R.string.start_receiving), { model.startReceiving(state.link) }, primary = true)
                }
                if (state.phones.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    MusicText(stringResource(R.string.trusted_phones), 17, medium = true)
                    state.phones.forEach { phone ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MusicText(phone.name, 15, Modifier.weight(1f), medium = true, color = Muted)
                            MusicButton(stringResource(R.string.forget_phone), { forget = phone })
                        }
                    }
                }
            }
        }
    }
    forget?.let { phone ->
        AlertDialog(onDismissRequest = { forget = null },
            text = { MusicText(stringResource(R.string.forget_phone_prompt, phone.name), 16) },
            confirmButton = { TextButton(onClick = { model.forgetPhone(phone); forget = null }) { MusicText(stringResource(R.string.forget_phone), 14) } },
            dismissButton = { TextButton(onClick = { forget = null }) { MusicText(stringResource(R.string.cancel), 14) } },
        )
    }
}

@Composable
private fun ReceiveValue(label: Int, value: String) {
    Column(Modifier.fillMaxWidth().musicPanel(Field, musicColors.edge, 14).padding(16.dp)) {
        MusicText(stringResource(label), 14, medium = true, color = Muted)
        Spacer(Modifier.height(8.dp))
        SelectionContainer { MusicText(value, 28, mono = true) }
    }
}
