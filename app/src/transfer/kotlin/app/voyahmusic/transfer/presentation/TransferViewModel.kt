package app.voyahmusic.transfer.presentation

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.MusicWire.Status
import app.voyahmusic.shared.lan.WifiLink
import app.voyahmusic.transfer.data.CarConnection
import app.voyahmusic.transfer.data.SelectedFile
import app.voyahmusic.transfer.data.SendSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

internal class TransferViewModel(private val context: Context) : ViewModel() {
    enum class State { READY, READING, CONNECTING, SENDING, DONE, CANCELLED, NO_WIFI, ADDRESS, CODE, CONNECTION, FAILED }
    enum class Link { UNPAIRED, PAIRING, CHECKING, CONNECTED, OFFLINE, DISCONNECTED, INCOMPATIBLE }

    data class Connection(
        val state: Link,
        val address: String = "",
        val confirmation: String = "",
        val folders: List<Folder> = emptyList(),
        val destination: Long = 0,
    )

    data class Screen(
        val files: List<SelectedFile> = emptyList(),
        val connection: Connection,
        val state: State = State.READY,
        val completed: Int = 0,
        val duplicates: Int = 0,
        val current: SelectedFile? = null,
        val bytes: Long = 0,
        val error: Status? = null,
    ) {
        val busy: Boolean
            get() = state in setOf(State.READING, State.CONNECTING, State.SENDING) ||
                connection.state == Link.PAIRING
    }

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val selected = linkedMapOf<Uri, SelectedFile>()
    private var car = CarConnection.load(context)
    private var connection = Connection(
        when {
            car == null -> Link.UNPAIRED
            CarConnection.paused(context) -> Link.DISCONNECTED
            else -> Link.CHECKING
        },
        car?.address.orEmpty(),
    )
    private val mutableState = MutableStateFlow(Screen(connection = connection))
    val screen = mutableState.asStateFlow()
    private var monitor: Job? = null
    private var probe: Job? = null
    private var operation: Job? = null
    private val ioSession = AtomicReference<SendSession?>()

    fun monitor(visible: Boolean) {
        monitor?.cancel()
        monitor = null
        if (!visible) return
        monitor = viewModelScope.launch {
            while (true) {
                probe()
                delay(7_000)
            }
        }
    }

    fun choose(uris: List<Uri>) {
        if (screen.value.busy || uris.isEmpty()) return
        show(State.READING)
        operation = viewModelScope.launch {
            val files = withContext(worker) {
                uris.map { uri ->
                    currentCoroutineContext().ensureActive()
                    SelectedFile.read(context.contentResolver, uri)
                }
            }
            files.forEach { selected[it.uri] = it }
            show(State.READY)
            probe()
        }
    }

    fun remove(file: SelectedFile) {
        if (screen.value.busy) return
        selected.remove(file.uri)
        show(State.READY)
    }

    fun pair(host: String, pin: String) {
        if (screen.value.busy || car != null) return
        val address = MusicWire.address(host)
        if (address == null) {
            show(State.ADDRESS)
            return
        }
        if (!pin.matches(Regex("[0-9]{8}")) && !MusicWire.fingerprint(pin)) {
            show(State.CODE)
            return
        }
        val digits = String.format(Locale.ROOT, "%04d", SecureRandom().nextInt(10_000))
        connection = Connection(Link.PAIRING, confirmation = digits)
        show(State.READY)
        operation = viewModelScope.launch {
            try {
                val result = withContext(worker) {
                    val wifi = wifi(address) ?: return@withContext null
                    val session = SendSession(wifi, address, pin, "")
                    useSession(session) {
                        val name = Build.MODEL.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").trim()
                        session.pair(name.ifBlank { "Android" }.take(60), digits)
                    }
                }
                when {
                    result == null -> pairFailed(State.NO_WIFI)
                    result.status != Status.OK -> pairFailed(State.FAILED, result.status)
                    else -> {
                        val paired = CarConnection(address.hostAddress ?: host, result.fingerprint, result.token)
                        if (paired.save(context)) {
                            car = paired
                            connection = Connection(Link.CHECKING, paired.address)
                            show(State.READY)
                            probe()
                        } else pairFailed(State.FAILED, Status.IO)
                    }
                }
            } catch (error: SSLHandshakeException) {
                currentCoroutineContext().ensureActive()
                pairFailed(State.FAILED, Status.CODE)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                pairFailed(State.CONNECTION)
            }
        }
    }

    private fun pairFailed(state: State, error: Status? = null) {
        connection = Connection(Link.UNPAIRED)
        show(state, error = error)
    }

    fun disconnect() {
        if (car == null) {
            cancel()
            return
        }
        stopWork()
        CarConnection.paused(context, true)
        connection = connection.copy(state = Link.DISCONNECTED)
        show(State.READY)
    }

    fun reconnect() {
        if (car == null || screen.value.busy || probe?.isActive == true) return
        CarConnection.paused(context, false)
        connection = connection.copy(state = Link.CHECKING)
        show(State.READY)
        probe()
    }

    fun forget() {
        stopWork()
        clearCar()
        show(State.READY)
    }

    private fun clearCar() {
        CarConnection.forget(context)
        car = null
        connection = Connection(Link.UNPAIRED)
    }

    fun target(folder: Long) {
        if (screen.value.busy || (folder != 0L && connection.folders.none { it.id == folder })) return
        connection = connection.copy(destination = folder)
        mutableState.value = screen.value.copy(connection = connection)
    }

    private fun probe() {
        val saved = car ?: return
        if (connection.state == Link.DISCONNECTED || screen.value.busy || probe?.isActive == true) return
        probe = viewModelScope.launch {
            val result = try {
                withContext(worker) {
                    val session = connect(saved) ?: return@withContext null
                    useSession(session, session::check)
                }
            } catch (error: IOException) {
                null
            }
            if (car != saved || screen.value.busy || connection.state == Link.DISCONNECTED) return@launch
            when (result?.status) {
                Status.OK -> {
                    val folders = result.folders
                    val destination = connection.destination.takeIf { id -> folders.any { it.id == id } } ?: 0L
                    connection = connection.copy(state = Link.CONNECTED, folders = folders, destination = destination)
                    mutableState.value = screen.value.copy(connection = connection)
                }
                Status.UNPAIRED -> {
                    clearCar()
                    show(State.FAILED, error = Status.UNPAIRED)
                }
                Status.PROTOCOL_VERSION -> {
                    connection = connection.copy(state = Link.INCOMPATIBLE)
                    show(State.FAILED, error = Status.PROTOCOL_VERSION)
                }
                else -> {
                    if (connection.state != Link.INCOMPATIBLE) connection = connection.copy(state = Link.OFFLINE)
                    mutableState.value = screen.value.copy(connection = connection)
                }
            }
        }
    }

    fun send() {
        val saved = car ?: return
        val previous = screen.value
        if (previous.files.isEmpty() || previous.busy || connection.state != Link.CONNECTED) return
        val destination = connection.destination
        val batch = previous.files
        probe?.cancel()
        ioSession.get()?.cancel()
        show(State.CONNECTING)
        operation = viewModelScope.launch {
            var completed = 0
            var duplicates = 0
            var current: SelectedFile? = null
            try {
                val status = withContext(worker) {
                    val session = connect(saved) ?: return@withContext null
                    useSession(session) {
                        val check = session.check()
                        if (check.status != Status.OK) return@useSession check.status
                        for (file in batch) {
                            currentCoroutineContext().ensureActive()
                            current = file
                            withContext(Dispatchers.Main.immediate) {
                                show(State.SENDING, completed, duplicates, file)
                            }
                            val owner = currentCoroutineContext()[Job]
                            val response = session.send(context.contentResolver, file, destination) { bytes ->
                                viewModelScope.launch {
                                    if (owner?.isActive == true && screen.value.current == file &&
                                        screen.value.state == State.SENDING) {
                                        mutableState.value = screen.value.copy(bytes = bytes)
                                    }
                                }
                            }
                            if (response != Status.OK && response != Status.DUPLICATE) return@useSession response
                            if (response == Status.DUPLICATE) duplicates++
                            completed++
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (selected[file.uri] === file) selected.remove(file.uri)
                                mutableState.value = screen.value.copy(files = selected.values.toList())
                            }
                        }
                        Status.OK
                    }
                }
                when (status) {
                    null -> {
                        connection = connection.copy(state = Link.OFFLINE)
                        show(State.NO_WIFI)
                    }
                    Status.OK -> show(State.DONE, completed, duplicates)
                    else -> {
                        if (status == Status.UNPAIRED) clearCar()
                        if (status == Status.PROTOCOL_VERSION) connection = connection.copy(state = Link.INCOMPATIBLE)
                        show(State.FAILED, completed, duplicates, current, status)
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                connection = connection.copy(state = Link.OFFLINE)
                show(State.CONNECTION, completed, duplicates, current)
            }
        }
    }

    private suspend inline fun <T> useSession(session: SendSession, work: () -> T): T {
        ioSession.set(session)
        try {
            currentCoroutineContext().ensureActive()
            return work()
        } finally {
            ioSession.compareAndSet(session, null)
        }
    }

    private fun connect(saved: CarConnection): SendSession? {
        val address = MusicWire.address(saved.address) ?: return null
        val wifi = wifi(address) ?: return null
        return SendSession(wifi, address, saved.fingerprint, saved.token)
    }

    private fun wifi(address: InetAddress): WifiLink? =
        WifiLink.available(context, false).firstOrNull { it.includes(address) }

    private fun show(
        state: State,
        completed: Int = 0,
        duplicates: Int = 0,
        file: SelectedFile? = null,
        error: Status? = null,
    ) {
        mutableState.value = Screen(selected.values.toList(), connection, state, completed, duplicates, file, error = error)
    }

    private fun stopWork() {
        operation?.cancel()
        probe?.cancel()
        ioSession.get()?.cancel()
    }

    fun cancel() {
        val current = screen.value
        val pairing = connection.state == Link.PAIRING
        stopWork()
        if (pairing) connection = Connection(Link.UNPAIRED)
        show(if (pairing) State.READY else State.CANCELLED, current.completed, current.duplicates)
    }

    override fun onCleared() {
        stopWork()
    }
}
