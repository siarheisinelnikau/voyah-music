package app.voyahmusic.transfer.presentation

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.voyahmusic.shared.R
import app.voyahmusic.shared.lan.PairQr
import app.voyahmusic.shared.ui.*
import app.voyahmusic.transfer.platform.QrCamera

@Composable
internal fun ScannerScreen(onBack: () -> Unit, onResult: (PairQr.Result) -> Unit) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onResult)
    var resumed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var status by remember { mutableIntStateOf(R.string.scan_hint) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    MusicPage {
        MusicHeading(stringResource(R.string.scan_qr), onBack)
        Spacer(Modifier.height(15.dp))
        MusicText(stringResource(status), 16, color = Muted)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (resumed && !failed) {
                AndroidExternalSurface(Modifier.fillMaxHeight().aspectRatio(9f / 16f)) {
                    onSurface { surface, _, _ ->
                        val camera = QrCamera(context, { value ->
                            when (val result = PairQr.parse(value)) {
                                PairQr.Result.Invalid -> status = R.string.invalid_qr
                                else -> report(result)
                            }
                        }, { status = R.string.camera_unavailable; failed = true })
                        surface.onDestroyed { camera.close() }
                        camera.start(surface)
                    }
                }
            }
        }
    }
}
