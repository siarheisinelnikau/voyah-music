package app.voyahmusic.player.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import app.voyahmusic.player.PlayerApp
import app.voyahmusic.player.playback.PlaybackService
import app.voyahmusic.player.presentation.PlayerScreen
import app.voyahmusic.player.presentation.PlayerViewModel
import app.voyahmusic.player.presentation.ReceiverScreen
import app.voyahmusic.shared.ui.MusicActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : MusicActivity() {
    private val model by viewModels<PlayerViewModel> {
        viewModelFactory {
            initializer {
                val app = application as PlayerApp
                PlayerViewModel(app, app.library())
            }
        }
    }

    private val service = MutableStateFlow<PlaybackService?>(null)
    private val playback = MutableStateFlow<PlaybackService.State?>(null)
    private val revision = MutableStateFlow(0)
    private var bound = false
    private var listening = false
    private val changed = Runnable {
        playback.value = service.value?.state()
        revision.value++
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val player = (binder as PlaybackService.LocalBinder).service()
            service.value = player
            player.observe(changed)
            changed.run()
        }

        override fun onServiceDisconnected(name: ComponentName) = detach()
    }
    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshWifi()
        override fun onLost(network: Network) = refreshWifi()
        private fun refreshWifi() = runOnUiThread { if (listening) model.networksChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        savedInstanceState?.let { saved ->
            model.restoreLibrary(saved.getString("query").orEmpty(), saved.getLong("folder"))
            model.navigateReceive(saved.getBoolean("receiving"))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(model.libraryState, service) { state, player -> state.snapshot to player }
                        .distinctUntilChanged().collect { (snapshot, player) ->
                            if (snapshot != null && player != null) player.setTracks(snapshot.tracks)
                        }
                }
                launch {
                    model.errors.collect { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("receiving", model.receiving.value)
        out.putString("query", model.libraryState.value.query)
        out.putLong("folder", model.libraryState.value.folder)
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        model.setForeground(true)
        bound = bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
        val manager = getSystemService(ConnectivityManager::class.java)
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
            listening = true
        } catch (_: SecurityException) {
            // The receive screen can refresh network availability on demand.
        }
    }

    override fun onStop() {
        if (listening) getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifi)
        listening = false
        model.setForeground(false)
        detach()
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private fun detach() {
        service.value?.removeObserver(changed)
        service.value = null
        playback.value = null
    }

    override fun onSettingsChanged(open: Boolean) {
        if (open) model.navigateReceive(false)
    }

    @Composable
    override fun MainContent(updateAvailable: Boolean) {
        val library by model.libraryState.collectAsStateWithLifecycle()
        val receiving by model.receiving.collectAsStateWithLifecycle()
        BackHandler(receiving || library.folder != 0L) {
            if (receiving) model.navigateReceive(false) else model.openFolder(0)
        }
        if (receiving) {
            val receiver by model.receiver.collectAsStateWithLifecycle()
            LifecycleStartEffect(receiver.state) {
                if (receiver.state == PlayerViewModel.Receiving.WAITING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onStopOrDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            ReceiverScreen(receiver, model, { model.navigateReceive(false) })
        } else {
            val media by playback.collectAsStateWithLifecycle()
            val player by service.collectAsStateWithLifecycle()
            val checkpoint by revision.collectAsStateWithLifecycle()
            PlayerScreen(library, media, player, checkpoint, model, updateAvailable,
                { openSettings(true) }, { model.navigateReceive(true) })
        }
    }
}
