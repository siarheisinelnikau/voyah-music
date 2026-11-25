package app.voyahmusic.transfer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.voyahmusic.shared.R
import app.voyahmusic.shared.lan.PairQr
import app.voyahmusic.shared.ui.MusicActivity
import app.voyahmusic.shared.ui.MusicText
import app.voyahmusic.transfer.presentation.ScannerScreen
import app.voyahmusic.transfer.presentation.TransferScreen
import app.voyahmusic.transfer.presentation.TransferViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class TransferActivity : MusicActivity() {
    private val model by viewModels<TransferViewModel> {
        viewModelFactory { initializer { TransferViewModel(application) } }
    }
    private val scanner = MutableStateFlow(false)
    private val unsupportedVersion = MutableStateFlow<Int?>(null)
    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.choose(it) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.value = true else notify(R.string.camera_unavailable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleLink(intent)
        else scanner.value = savedInstanceState.getBoolean("scanner") &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("scanner", scanner.value)
        super.onSaveInstanceState(out)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        model.monitor(true)
    }

    override fun onStop() {
        model.monitor(false)
        super.onStop()
    }

    @Composable
    override fun MainContent(updateAvailable: Boolean) {
        val state by model.screen.collectAsStateWithLifecycle()
        val scanning by scanner.collectAsStateWithLifecycle()
        val version by unsupportedVersion.collectAsStateWithLifecycle()
        val screens = rememberSaveableStateHolder()
        LifecycleStartEffect(state.busy) {
            if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onStopOrDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BackHandler(scanning) { scanner.value = false }
        if (scanning) {
            ScannerScreen({ scanner.value = false }, { scanner.value = false; acceptPair(it) })
        } else {
            screens.SaveableStateProvider("transfer") {
                TransferScreen(state, model, updateAvailable, { openSettings(true) }, ::scan, ::pick,
                    getPreferences(MODE_PRIVATE).getString("car_address", "").orEmpty())
            }
        }
        version?.let { incompatible ->
            AlertDialog(onDismissRequest = { unsupportedVersion.value = null },
                text = { MusicText(stringResource(R.string.unsupported_qr_version, incompatible), 16) },
                confirmButton = { TextButton(onClick = { unsupportedVersion.value = null }) {
                    MusicText(stringResource(android.R.string.ok), 14, medium = true)
                } },
            )
        }
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) acceptPair(PairQr.parse(intent.dataString))
    }

    private fun acceptPair(result: PairQr.Result) {
        when (result) {
            is PairQr.Result.Valid -> { openSettings(false); model.pair(result.pair.host, result.pair.fingerprint) }
            is PairQr.Result.Unsupported -> { openSettings(false); unsupportedVersion.value = result.version }
            PairQr.Result.Invalid -> notify(R.string.invalid_qr)
        }
    }

    private fun scan() {
        when {
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> notify(R.string.camera_unavailable)
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> scanner.value = true
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pick() {
        try {
            documents.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            notify(R.string.no_file_picker)
        }
    }

    private fun notify(label: Int) {
        Toast.makeText(this, label, Toast.LENGTH_LONG).show()
    }
}
