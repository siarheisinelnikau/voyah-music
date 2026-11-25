package app.voyahmusic.shared.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.voyahmusic.shared.R
import app.voyahmusic.shared.data.MusicPreferences
import app.voyahmusic.shared.update.MusicApp
import app.voyahmusic.shared.update.UpdateFiles
import app.voyahmusic.shared.update.Updates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class MusicActivity : ComponentActivity() {
    private val updates by lazy { (application as MusicApp).updates() }
    private val settingsModel by viewModels<MusicSettingsModel> {
        viewModelFactory { initializer { MusicSettingsModel(MusicPreferences(application)) } }
    }

    private val allowInstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) launchInstaller()
    }

    private val install = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_FIRST_USER) reportInstallFailure()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(MusicPreferences.localized(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (MusicPreferences(this).amber()) R.style.MusicTheme_Amber else R.style.MusicTheme)
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("open_settings") == true) settingsModel.open(true)
        setContent {
            val settings by settingsModel.state.collectAsStateWithLifecycle()
            val update by updates.state.collectAsStateWithLifecycle()
            val screens = rememberSaveableStateHolder()
            MusicTheme(settings.amber) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings.open) {
                        BackHandler { openSettings(false) }
                        MusicSettings(
                            settings, updates.installed(), update, { openSettings(false) },
                            onLanguage = {
                                if (settingsModel.language(it)) recreate()
                            },
                            onTheme = settingsModel::amber,
                            onStartup = settingsModel::startup,
                            onCheck = updates::check,
                            onInstall = {
                                if (update.phase == Updates.Phase.READY) launchInstaller()
                                else {
                                    settingsModel.installRequested = true
                                    updates.download()
                                }
                            },
                        )
                    } else {
                        screens.SaveableStateProvider("main") {
                            MainContent(update.phase in setOf(Updates.Phase.AVAILABLE,
                                Updates.Phase.DOWNLOAD_FAILED, Updates.Phase.READY))
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updates.state.collect {
                    if (it.phase == Updates.Phase.READY && settingsModel.installRequested) {
                        settingsModel.installRequested = false
                        launchInstaller()
                    }
                    if (it.phase == Updates.Phase.DOWNLOAD_FAILED) settingsModel.installRequested = false
                }
            }
        }
        updates.startup()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("open_settings", settingsModel.state.value.open)
        super.onSaveInstanceState(out)
    }

    protected fun openSettings(open: Boolean) {
        settingsModel.open(open)
        onSettingsChanged(open)
    }

    protected open fun onSettingsChanged(open: Boolean) = Unit

    @Composable
    protected abstract fun MainContent(updateAvailable: Boolean)

    private fun launchInstaller() {
        if (updates.state.value.file?.isFile != true) {
            settingsModel.installRequested = true
            updates.download()
            return
        }
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null)))
                return
            }
            @Suppress("DEPRECATION")
            install.launch(Intent(Intent.ACTION_INSTALL_PACKAGE, UpdateFiles.uri(this))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (error: ActivityNotFoundException) {
            reportInstallFailure()
        } catch (error: SecurityException) {
            reportInstallFailure()
        }
    }

    private fun reportInstallFailure() {
        Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
    }
}

internal data class MusicSettingsState(
    val open: Boolean,
    val language: String,
    val amber: Boolean,
    val startup: Boolean,
)

internal class MusicSettingsModel(private val preferences: MusicPreferences) : ViewModel() {
    private val mutableState = MutableStateFlow(
        MusicSettingsState(false, preferences.language(), preferences.amber(), preferences.startupUpdates()),
    )
    val state = mutableState.asStateFlow()
    var installRequested = false

    fun open(value: Boolean) {
        mutableState.update { it.copy(open = value) }
    }

    fun language(value: String): Boolean {
        if (mutableState.value.language == value) return false
        preferences.language(value)
        mutableState.update { it.copy(language = value) }
        return true
    }

    fun amber(value: Boolean) {
        preferences.amber(value)
        mutableState.update { it.copy(amber = value) }
    }

    fun startup(value: Boolean) {
        preferences.startupUpdates(value)
        mutableState.update { it.copy(startup = value) }
    }
}
