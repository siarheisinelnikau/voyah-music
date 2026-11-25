package app.voyahmusic.player.data

import android.database.sqlite.SQLiteException
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Status
import app.voyahmusic.shared.lan.WifiLink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import java.util.function.Consumer
import java.util.function.IntPredicate
import javax.net.ssl.SSLServerSocket

class ReceiveSession(
    private val library: MusicLibrary,
    private val link: WifiLink,
    private val identity: LocalIdentity,
    private val pairing: PairingInbox,
    private val phones: TrustedPhones,
    private val trustedChanged: Runnable,
    private val received: Consumer<String?>,
    private val stopped: Runnable
) : AutoCloseable {
    private val server: ServerSocket
    private val clients: MutableSet<Socket> = HashSet<Socket>()
    private val requests = ThreadPoolExecutor(
        0, 4, 20, TimeUnit.SECONDS,
        SynchronousQueue<Runnable?>()
    )

    @kotlin.concurrent.Volatile
    private var closed = false

    init {
        val listener = identity.tls.getServerSocketFactory().createServerSocket() as SSLServerSocket
        listener.enabledProtocols = listener.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        server = listener
        try {
            server.setReuseAddress(true)
            server.bind(InetSocketAddress(link.address, MusicWire.PORT), 4)
        } catch (e: IOException) {
            server.close()
            throw e
        }
    }

    fun run() {
        try {
            while (!closed) accept(server.accept())
        } catch (ignored: IOException) {
        } finally {
            close()
            stopped.run()
        }
    }

    @kotlin.jvm.Synchronized
    private fun accept(socket: Socket) {
        if (closed || !link.includes(socket.getInetAddress())) {
            ReceiveSession.closeClient(socket)
            return
        }
        clients.add(socket)
        try {
            requests.execute(Runnable {
                try {
                    socket.use { client ->
                        handle(client)
                    }
                } catch (ignored: IOException) {
                    // The next request can proceed after a disconnect or local storage failure.
                } catch (ignored: SQLiteException) {
                } finally {
                    synchronized(this) {
                        clients.remove(socket)
                    }
                }
            })
        } catch (e: RejectedExecutionException) {
            clients.remove(socket)
            ReceiveSession.closeClient(socket)
        }
    }

    @kotlin.Throws(IOException::class)
    private fun handle(socket: Socket) {
        socket.setSoTimeout(5000)
        val `in` = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val command = try {
            MusicWire.command(`in`)
        } catch (error: MusicWire.UnsupportedVersion) {
            Status.PROTOCOL_VERSION.write(out)
            return
        }
        val token = `in`.readUTF()
        if (command == MusicWire.PAIR) {
            pair(socket, `in`, out, token)
            return
        }
        if (!phones.knows(token)) {
            MusicWire.Status.UNPAIRED.write(out)
            return
        }
        if (command == MusicWire.CHECK) {
            val folders = library.folders()
            MusicWire.Status.OK.write(out)
            out.writeInt(folders.size)
            for (folder in folders) {
                out.writeLong(folder.id)
                out.writeUTF(folder.name)
            }
            out.flush()
            return
        }
        socket.setSoTimeout(20000)
        val folder = `in`.readLong()
        val name = `in`.readUTF()
        val declared = `in`.readLong()
        if (!library.hasFolder(folder)) {
            MusicWire.Status.FOLDER.write(out)
        } else if (!MusicWire.validName(name) || declared == 0L || declared < -1) {
            MusicWire.Status.FORMAT.write(out)
        } else if (declared > MusicWire.MAX_BYTES) {
            MusicWire.Status.TOO_LARGE.write(out)
        } else if (!library.hasSpace(declared.coerceAtLeast(0L))) {
            MusicWire.Status.STORAGE.write(out)
        } else {
            receive(`in`, out, name, declared, folder, token)
        }
    }

    @kotlin.Throws(IOException::class)
    private fun pair(socket: Socket, `in`: DataInputStream, out: DataOutputStream, proof: String?) {
        val fingerprint = identity.fingerprint
        if (fingerprint != proof && MusicWire.pairingCode(fingerprint) != proof) {
            MusicWire.Status.CODE.write(out)
            return
        }
        val name = `in`.readUTF()
        val digits = `in`.readUTF()
        if (!digits.matches("[0-9]{4}".toRegex()) || name.length > 60 || name.trim { it <= ' ' }
                .isEmpty()
            || name.codePoints()
                .anyMatch(IntPredicate { c: Int -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() })) {
            MusicWire.Status.IO.write(out)
            return
        }
        socket.setSoTimeout(300)
        val decision =
            pairing.ask(name, digits) { waiting(`in`) }
        if (decision != MusicWire.Status.OK) {
            decision.write(out)
            return
        }
        val token = phones.add(name)
        if (token.isEmpty()) {
            MusicWire.Status.STORAGE.write(out)
            return
        }
        try {
            MusicWire.Status.OK.write(out)
            out.writeUTF(token)
            out.flush()
            trustedChanged.run()
        } catch (e: IOException) {
            phones.remove(TrustedPhones.Phone(token, name))
            throw e
        }
    }

    @kotlin.Throws(IOException::class)
    private fun receive(
        `in`: DataInputStream,
        out: DataOutputStream,
        name: String,
        declared: Long,
        folder: Long,
        token: String
    ) {
        val staging: File
        try {
            staging = library.staging()
        } catch (e: IOException) {
            MusicWire.Status.STORAGE.write(out)
            return
        }
        try {
            MusicWire.Status.OK.write(out)
            val digest: MessageDigest = ReceiveSession.sha256()
            var result: MusicWire.Status
            try {
                FileOutputStream(staging).use { file ->
                    DigestOutputStream(file, digest).use { data ->
                        result = copy(`in`, data, declared)
                        if (result == MusicWire.Status.OK) file.getFD().sync()
                    }
                }
            } catch (e: IOException) {
                result = MusicWire.Status.IO
            }
            if (result == MusicWire.Status.OK) {
                if (closed || !phones.knows(token)) {
                    result = MusicWire.Status.EXPIRED
                } else if (!MusicLibrary.isAudio(staging)) {
                    result = MusicWire.Status.FORMAT
                } else {
                    try {
                        synchronized(this) {
                            result = if (closed || !phones.knows(token))
                                MusicWire.Status.EXPIRED
                            else
                                library.publish(
                                    staging,
                                    MusicWire.hex(digest.digest()),
                                    name,
                                    folder
                                )
                        }
                        if (result == MusicWire.Status.OK) received.accept(name)
                    } catch (e: IOException) {
                        result = MusicWire.Status.STORAGE
                    }
                }
            }
            val reported = result
            reported.write(out)
        } finally {
            MusicLibrary.discard(staging)
        }
    }

    @kotlin.Throws(IOException::class)
    private fun copy(
        `in`: DataInputStream,
        output: DigestOutputStream,
        declared: Long
    ): MusicWire.Status {
        val buffer = ByteArray(MusicWire.CHUNK)
        var bytes: Long = 0
        var nextSpaceCheck: Long = 0
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20)
        while (!closed && System.nanoTime() < deadline) {
            val chunk = `in`.readInt()
            if (chunk == 0) return if (bytes > 0 && (declared < 0 || bytes == declared)) MusicWire.Status.OK else MusicWire.Status.IO
            if (chunk < 0 || chunk > buffer.size) return MusicWire.Status.IO
            if (bytes + chunk > MusicWire.MAX_BYTES) return MusicWire.Status.TOO_LARGE
            if (declared >= 0 && bytes + chunk > declared) return MusicWire.Status.IO
            if (bytes >= nextSpaceCheck) {
                if (!library.hasSpace(4L * 1024 * 1024)) return MusicWire.Status.STORAGE
                nextSpaceCheck = bytes + 4L * 1024 * 1024
            }
            `in`.readFully(buffer, 0, chunk)
            output.write(buffer, 0, chunk)
            bytes += chunk.toLong()
        }
        return MusicWire.Status.EXPIRED
    }

    @kotlin.jvm.Synchronized
    override fun close() {
        closed = true
        pairing.setOpen(false)
        try {
            server.close()
        } catch (ignored: IOException) {
        }
        for (socket in clients) ReceiveSession.closeClient(socket)
        requests.shutdownNow()
    }

    companion object {
        private fun waiting(`in`: DataInputStream): Boolean {
            try {
                `in`.read()
                return false
            } catch (timeout: SocketTimeoutException) {
                return true
            } catch (e: IOException) {
                return false
            }
        }

        private fun sha256(): MessageDigest {
            try {
                return MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw AssertionError(e)
            }
        }

        private fun closeClient(socket: Socket) {
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
