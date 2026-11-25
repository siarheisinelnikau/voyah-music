package app.voyahmusic.shared.lan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

object MusicWire {
    const val VERSION = 2
    private const val MAGIC = 0x564D0000
    private const val MAGIC_MASK = -0x10000
    const val PORT = 47477
    const val CHUNK = 64 * 1024
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val CHECK = 1
    const val UPLOAD = 2
    const val PAIR = 3
    const val MAX_FOLDERS = 256

    private val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "mka", "amr")
    private val fingerprintPattern = Regex("[0-9a-f]{64}")
    private val octetPattern = Regex("[0-9]{1,3}")

    data class Folder(val id: Long, val name: String)

    class UnsupportedVersion(val version: Int) : IOException("Unsupported transfer protocol version: $version")

    enum class Status(private val value: Int) {
        OK(1), DUPLICATE(2), CODE(3), FORMAT(4), TOO_LARGE(5), STORAGE(6), IO(7), EXPIRED(8),
        DECLINED(9), UNPAIRED(10), FOLDER(11), BUSY(12), PROTOCOL_VERSION(13);

        fun write(output: DataOutputStream) {
            output.writeByte(value)
            output.flush()
        }

        companion object {
            fun read(input: DataInputStream): Status {
                val value = input.readUnsignedByte()
                return entries.firstOrNull { it.value == value } ?: throw IOException("Unknown transfer status")
            }
        }
    }

    fun request(output: DataOutputStream, command: Int, credential: String) {
        output.writeInt(MAGIC or VERSION)
        output.writeByte(command)
        output.writeUTF(credential)
    }

    fun command(input: DataInputStream): Int {
        val header = input.readInt()
        if (header and MAGIC_MASK != MAGIC) throw IOException("Invalid transfer header")
        val version = header and 0xFFFF
        if (version != VERSION) throw UnsupportedVersion(version)
        val command = input.readUnsignedByte()
        if (command != CHECK && command != UPLOAD && command != PAIR) throw IOException("Unknown transfer command")
        return command
    }

    fun validFolder(name: String): Boolean =
        name.trim { it <= ' ' }.isNotEmpty() && name.length <= 60 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    fun fingerprint(value: String): Boolean = fingerprintPattern.matches(value)

    fun pairingCode(fingerprint: String): String {
        val prefix = fingerprint.substring(0, 12).toLong(16)
        return String.format(Locale.ROOT, "%08d", prefix % 100_000_000L)
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (value in bytes) {
            append(Character.forDigit((value.toInt() and 255) ushr 4, 16))
            append(Character.forDigit(value.toInt() and 15, 16))
        }
    }

    fun address(value: String): Inet4Address? {
        val parts = value.trim { it <= ' ' }.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (!octetPattern.matches(part)) return null
            val octet = part.toInt()
            if (octet > 255) return null
            bytes[index] = octet.toByte()
        }
        return (InetAddress.getByAddress(bytes) as Inet4Address).takeIf(::isLocal)
    }

    fun isLocal(address: InetAddress?): Boolean =
        address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress)

    fun sameSubnet(local: InetAddress?, peer: InetAddress?, prefix: Int): Boolean {
        if (local == null || peer == null || !isLocal(local) || !isLocal(peer) || prefix !in 1..32) return false
        val a = local.address
        val b = peer.address
        for (index in a.indices) {
            val bits = (prefix - index * 8).coerceIn(0, 8)
            val mask = 0xFF shl (8 - bits)
            if ((a[index].toInt() xor b[index].toInt()) and 0xFF and mask != 0) return false
        }
        return true
    }

    fun validName(name: String): Boolean =
        name.trim { it <= ' ' }.isNotEmpty() && name.length <= 180 && !name.startsWith('.') &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            extension(name) in extensions && name.toByteArray(Charsets.UTF_8).size <= 240

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }
}
