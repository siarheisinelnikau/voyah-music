package app.voyahmusic.transfer.data

import android.content.ContentResolver
import android.os.SystemClock
import app.voyahmusic.shared.lan.LanTls
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.WifiLink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.function.LongConsumer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SendSession(
    private val link: WifiLink,
    private val address: InetAddress,
    private val pin: String,
    private val token: String
) {
    data class Check(val status: MusicWire.Status, val folders: MutableList<Folder>)

    data class Pair(val status: MusicWire.Status, val fingerprint: String, val token: String)

    private val tls: SSLContext
    private var current: Socket? = null

    @kotlin.concurrent.Volatile
    private var cancelled = false

    init {
        try {
            tls = LanTls.client(pin)
        } catch (e: GeneralSecurityException) {
            throw IOException(e)
        }
    }

    @kotlin.Throws(IOException::class)
    fun pair(name: String, digits: String): Pair {
        try {
            connect().use { socket ->
                socket.setSoTimeout(50000)
                val out = DataOutputStream(socket.getOutputStream())
                MusicWire.request(out, MusicWire.PAIR, pin)
                out.writeUTF(name)
                out.writeUTF(digits)
                out.flush()
                val `in` = DataInputStream(socket.getInputStream())
                val status: MusicWire.Status = MusicWire.Status.read(`in`)
                if (status != MusicWire.Status.OK) return Pair(status, "", "")
                val credential = `in`.readUTF()
                if (!MusicWire.fingerprint(credential)) throw IOException("Invalid pairing credential")
                val identity = LanTls.fingerprint(
                    socket.getSession().getPeerCertificates()[0] as X509Certificate
                )
                return Pair(status, identity, credential)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException(e)
        } finally {
            clear()
        }
    }

    @kotlin.Throws(IOException::class)
    fun check(): Check {
        try {
            connect().use { socket ->
                socket.setSoTimeout(5000)
                val out = DataOutputStream(socket.getOutputStream())
                MusicWire.request(out, MusicWire.CHECK, token)
                out.flush()
                val `in` = DataInputStream(socket.getInputStream())
                val status: MusicWire.Status = MusicWire.Status.read(`in`)
                if (status != MusicWire.Status.OK) return Check(status, mutableListOf<Folder>())
                val count = `in`.readInt()
                if (count < 0 || count > MusicWire.MAX_FOLDERS) throw IOException("Invalid folder count")
                val folders: MutableList<Folder> = ArrayList<Folder>(count)
                for (i in 0..<count) {
                    val id = `in`.readLong()
                    val name = `in`.readUTF()
                    if (id <= 0 || !MusicWire.validFolder(name)) throw IOException("Invalid folder")
                    folders.add(Folder(id, name))
                }
                return Check(status, Collections.unmodifiableList<Folder>(folders))
            }
        } finally {
            clear()
        }
    }

    @kotlin.Throws(IOException::class)
    fun send(
        resolver: ContentResolver,
        file: SelectedFile,
        folder: Long,
        progress: LongConsumer
    ): MusicWire.Status {
        if (!MusicWire.validName(file.name) || file.size == 0L) return MusicWire.Status.FORMAT
        if (file.size > MusicWire.MAX_BYTES) return MusicWire.Status.TOO_LARGE

        try {
            resolver.openInputStream(file.uri).use { source ->
                if (source == null) return MusicWire.Status.IO
                try {
                    connect().use { socket ->
                        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                        val `in` = DataInputStream(BufferedInputStream(socket.getInputStream()))
                        MusicWire.request(out, MusicWire.UPLOAD, token)
                        out.writeLong(folder)
                        out.writeUTF(file.name)
                        out.writeLong(if (file.size < 0) -1 else file.size)
                        out.flush()
                        val ready: MusicWire.Status = MusicWire.Status.read(`in`)
                        if (ready != MusicWire.Status.OK) return ready
                        val buffer = ByteArray(MusicWire.CHUNK)
                        var bytes: Long = 0
                        var nextProgressAt: Long = 0
                        var count: Int
                        while ((source.read(buffer).also { count = it }) != -1) {
                            if (cancelled) throw InterruptedIOException()
                            if (count == 0) {
                                continue
                            }
                            bytes += count.toLong()
                            if (bytes > MusicWire.MAX_BYTES) return MusicWire.Status.TOO_LARGE
                            out.writeInt(count)
                            out.write(buffer, 0, count)
                            val now = SystemClock.elapsedRealtime()
                            if (now >= nextProgressAt) {
                                progress.accept(bytes)
                                nextProgressAt = now + 240
                            }
                        }
                        out.writeInt(0)
                        out.flush()
                        progress.accept(bytes)
                        return MusicWire.Status.read(`in`)
                    }
                } finally {
                    clear()
                }
            }
        } catch (e: SecurityException) {
            return MusicWire.Status.IO
        }
    }

    @kotlin.Throws(IOException::class)
    private fun connect(): SSLSocket {
        val socket = requireNotNull(link.network).socketFactory.createSocket()
        synchronized(this) {
            if (cancelled) {
                socket.close()
                throw InterruptedIOException()
            }
            current = socket
        }
        try {
            socket.connect(InetSocketAddress(address, MusicWire.PORT), 4000)
            socket.setSoTimeout(5000)
            val secured = tls.getSocketFactory()
                .createSocket(socket, address.getHostAddress(), MusicWire.PORT, true) as SSLSocket
            secured.startHandshake()
            secured.setSoTimeout(25000)
            return secured
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    @kotlin.jvm.Synchronized
    private fun clear() {
        current = null
    }

    @kotlin.jvm.Synchronized
    fun cancel() {
        cancelled = true
        if (current != null) {
            try {
                current!!.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
