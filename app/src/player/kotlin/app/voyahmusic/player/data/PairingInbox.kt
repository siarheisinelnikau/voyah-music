package app.voyahmusic.player.data

import app.voyahmusic.shared.lan.MusicWire.Status
import java.util.concurrent.TimeUnit

class PairingInbox(private val changed: (Request?) -> Unit) {
    class Request(val name: String, val digits: String) {
        @Volatile internal var result: Status? = null
    }

    private var open = false
    private var pending: Request? = null

    @Synchronized
    fun setOpen(enabled: Boolean) {
        open = enabled
        if (!enabled) pending?.let { decide(it, Status.EXPIRED) }
    }

    fun ask(name: String, digits: String, connected: () -> Boolean): Status {
        val request = Request(name, digits)
        synchronized(this) {
            if (!open) return Status.EXPIRED
            if (pending != null) return Status.BUSY
            pending = request
            changed(request)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (request.result == null && System.nanoTime() < deadline) {
            if (!connected()) {
                decide(request, Status.EXPIRED)
                return Status.EXPIRED
            }
        }
        decide(request, Status.EXPIRED)
        return request.result ?: Status.EXPIRED
    }

    @Synchronized
    fun decide(request: Request, result: Status) {
        if (pending !== request) return
        pending = null
        request.result = result
        changed(null)
    }
}
