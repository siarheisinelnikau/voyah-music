package app.voyahmusic.player.presentation

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voyahmusic.player.data.LocalIdentity
import app.voyahmusic.player.data.MusicLibrary
import app.voyahmusic.player.data.PairingInbox
import app.voyahmusic.player.data.ReceiveSession
import app.voyahmusic.player.data.TrustedPhones
import app.voyahmusic.player.domain.Track
import app.voyahmusic.shared.R
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.MusicWire.Status
import app.voyahmusic.shared.lan.WifiLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Locale

internal class PlayerViewModel(
    private val context: Context,
    private val library: MusicLibrary,
) : ViewModel() {
    enum class Receiving { INACTIVE, OPENING, WAITING, NO_NETWORK, FAILED, STOPPED }

    data class Receiver(
        val state: Receiving = Receiving.INACTIVE,
        val networks: List<WifiLink> = emptyList(),
        val link: WifiLink? = null,
        val fingerprint: String = "",
        val latest: String = "",
        val request: PairingInbox.Request? = null,
        val phones: List<TrustedPhones.Phone> = emptyList(),
    )

    data class LibraryState(
        val snapshot: MusicLibrary.Snapshot? = null,
        val query: String = "",
        val folder: Long = 0L,
        val tracks: List<Track> = emptyList(),
        val folders: List<Folder> = emptyList(),
    )

    private val phones = TrustedPhones(context)
    private val storage = Mutex()
    private val events = Channel<Int>(Channel.BUFFERED)
    val errors = events.receiveAsFlow()
    private val mutableLibrary = MutableStateFlow(LibraryState())
    val libraryState = mutableLibrary.asStateFlow()
    private val mutableReceiver = MutableStateFlow(Receiver())
    val receiver = mutableReceiver.asStateFlow()
    private val mutableReceiving = MutableStateFlow(false)
    val receiving = mutableReceiving.asStateFlow()

    private var session: ReceiveSession? = null
    private var inbox: PairingInbox? = null
    private var startJob: Job? = null
    private var serverJob: Job? = null
    private var generation = 0
    private var foreground = false

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            refresh()
            startReceiving()
        } else stopReceiving()
    }

    fun navigateReceive(open: Boolean) {
        mutableReceiving.value = open
        inbox?.setOpen(open && foreground)
        if (open && foreground) startReceiving()
    }

    fun refresh() {
        viewModelScope.launch {
            storage.withLock {
                val snapshot = withContext(Dispatchers.IO) { library.snapshot() }
                updateList(mutableLibrary.value.copy(snapshot = snapshot))
            }
        }
    }

    fun restoreLibrary(query: String, folder: Long) = updateList(mutableLibrary.value.copy(query = query, folder = folder))

    fun search(query: String) = updateList(mutableLibrary.value.copy(query = query))

    fun openFolder(folder: Long) = updateList(mutableLibrary.value.copy(folder = folder, query = ""))

    private fun updateList(next: LibraryState) {
        val snapshot = next.snapshot
        val query = next.query.trim().lowercase(Locale.ROOT)
        val folders = snapshot?.folders.orEmpty()
        val folder = if (snapshot == null || folders.any { it.id == next.folder }) next.folder else 0L
        val tracks = snapshot?.tracks.orEmpty().filter {
            val inFolder = snapshot?.folderOf(it) == folder || (folder == 0L && query.isNotEmpty())
            inFolder && (query.isEmpty() || it.matches(query))
        }
        val shownFolders = if (folder != 0L) emptyList() else folders.filter {
            query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query)
        }
        mutableLibrary.value = next.copy(folder = folder, tracks = tracks, folders = shownFolders)
    }

    fun delete(track: Track) = edit(R.string.delete_failed) { library.remove(track) }

    fun createFolder(name: String) {
        if (!MusicWire.validFolder(name)) {
            events.trySend(R.string.invalid_folder)
            return
        }
        edit(R.string.folder_failed) { library.createFolder(name.trim()) > 0 }
    }

    fun move(track: Track, folder: Long) = edit(R.string.move_failed) { library.moveTrack(track, folder) }

    private fun edit(failure: Int, operation: () -> Boolean) {
        viewModelScope.launch {
            storage.withLock {
                val snapshot = withContext(Dispatchers.IO) {
                    try {
                        if (operation()) library.snapshot() else null
                    } catch (error: SQLiteException) {
                        null
                    }
                }
                if (snapshot == null) events.trySend(failure)
                else updateList(mutableLibrary.value.copy(snapshot = snapshot))
            }
        }
    }

    fun choose(request: PairingInbox.Request, allow: Boolean) {
        if (receiving.value && foreground) inbox?.decide(request, if (allow) Status.OK else Status.DECLINED)
    }

    fun forgetPhone(phone: TrustedPhones.Phone) {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { phones.remove(phone) }) refreshPhones()
            else events.trySend(R.string.forget_failed)
        }
    }

    fun networksChanged() {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { WifiLink.available(context, true) }
            if (!foreground || startJob?.isActive == true) return@launch
            if (session == null || receiver.value.link !in available) startReceiving()
            else mutableReceiver.update { it.copy(networks = available) }
        }
    }

    fun startReceiving(selected: WifiLink? = null) {
        if (!foreground || startJob?.isActive == true) return
        if (session != null && (selected == null || selected == receiver.value.link)) return
        restart(selected)
    }

    private fun restart(selected: WifiLink?) {
        stopSocket()
        val generationAtStart = ++generation
        mutableReceiver.value = Receiver(Receiving.OPENING, phones = receiver.value.phones)
        startJob = viewModelScope.launch(Dispatchers.IO) {
            val links = WifiLink.available(context, true)
            val link = selected?.takeIf(links::contains) ?: links.firstOrNull()
            if (link == null) {
                fail(generationAtStart, Receiving.NO_NETWORK, links, null)
                return@launch
            }
            val mailbox = PairingInbox { request ->
                onCurrent(generationAtStart) { mutableReceiver.update { it.copy(request = request) } }
            }
            var active: ReceiveSession? = null
            var attached = false
            try {
                val identity = LocalIdentity.load()
                val opened = ReceiveSession(
                    library, link, identity, mailbox, phones,
                    { onCurrent(generationAtStart, ::refreshPhones) },
                    { name ->
                        viewModelScope.launch {
                            refresh()
                            if (current(generationAtStart)) mutableReceiver.update { it.copy(latest = name.orEmpty()) }
                        }
                    },
                    { onCurrent(generationAtStart, ::serverStopped) },
                )
                active = opened
                val trusted = phones.list()
                withContext(Dispatchers.Main.immediate) {
                    if (!current(generationAtStart)) return@withContext
                    session = opened
                    inbox = mailbox
                    mailbox.setOpen(receiving.value)
                    mutableReceiver.value = Receiver(Receiving.WAITING, links, link, identity.fingerprint, phones = trusted)
                    attached = true
                    serverJob = viewModelScope.launch(Dispatchers.IO) { opened.run() }
                }
            } catch (error: IOException) {
                fail(generationAtStart, Receiving.FAILED, links, link)
            } catch (error: GeneralSecurityException) {
                fail(generationAtStart, Receiving.FAILED, links, link)
            } finally {
                if (!attached) active?.close()
            }
        }
    }

    private fun refreshPhones() {
        viewModelScope.launch {
            val trusted = withContext(Dispatchers.IO) { phones.list() }
            mutableReceiver.update { it.copy(phones = trusted) }
        }
    }

    private suspend fun fail(task: Int, state: Receiving, links: List<WifiLink>, link: WifiLink?) {
        withContext(Dispatchers.Main.immediate) {
            if (current(task)) mutableReceiver.value = Receiver(state, links, link, phones = receiver.value.phones)
        }
    }

    private fun onCurrent(task: Int, action: () -> Unit) {
        viewModelScope.launch { if (current(task)) action() }
    }

    private fun current(task: Int) = foreground && generation == task

    private fun serverStopped() {
        stopSocket()
        mutableReceiver.update { it.copy(state = Receiving.STOPPED, fingerprint = "", request = null) }
    }

    private fun stopSocket() {
        inbox?.setOpen(false)
        inbox = null
        session?.close()
        session = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun stopReceiving() {
        generation++
        startJob?.cancel()
        startJob = null
        stopSocket()
        mutableReceiver.update { it.copy(state = Receiving.STOPPED, fingerprint = "", request = null) }
    }

    override fun onCleared() {
        stopReceiving()
    }
}
