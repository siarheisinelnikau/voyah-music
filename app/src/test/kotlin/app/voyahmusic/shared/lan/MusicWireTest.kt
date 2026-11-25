package app.voyahmusic.shared.lan

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class MusicWireTest {
    @Test
    fun pairsOnlyWithLocalAddressesOnTheActualPrefix() {
        listOf("8.8.8.8", "192.168.1.256", "localhost", "127.0.0.1", "192.168.1.2.").forEach {
            assertNull(MusicWire.address(it))
        }
        val local = MusicWire.address(" 192.168.2.130 ")
        assertTrue(MusicWire.sameSubnet(local, MusicWire.address("192.168.2.254"), 25))
        assertFalse(MusicWire.sameSubnet(local, MusicWire.address("192.168.2.62"), 25))
    }

    @Test
    fun preservesUnicodeNamesAndRejectsPathsAndNonAudioExtensions() {
        assertTrue(MusicWire.validName("Әндер — Музыка.FLAC"))
        listOf("../escape.mp3", "nested\\escape.mp3", "payload.apk", "bad\nname.mp3").forEach {
            assertFalse(MusicWire.validName(it))
        }
    }

    @Test
    fun retainsThePublishedVersionTwoFrame() {
        val frame = bytes {
            MusicWire.request(it, MusicWire.UPLOAD, "00001234")
            MusicWire.Status.DUPLICATE.write(it)
        }
        assertArrayEquals(byteArrayOf(0x56, 0x4D, 0, 2, 2, 0, 8, 48, 48, 48, 48, 49, 50, 51, 52, 2), frame)
        val input = DataInputStream(ByteArrayInputStream(frame))
        assertEquals(MusicWire.UPLOAD, MusicWire.command(input))
        assertEquals("00001234", input.readUTF())
        assertEquals(MusicWire.Status.DUPLICATE, MusicWire.Status.read(input))
    }

    @Test
    fun unsupportedVersionsAreRejectedBeforeReadingTheCommandOrPayload() {
        for (version in listOf(1, 3, 0xFFFF)) {
            val frame = bytes { it.writeInt(0x564D0000 or version); it.writeByte(MusicWire.UPLOAD) }
            val input = DataInputStream(ByteArrayInputStream(frame))
            val error = assertThrows(MusicWire.UnsupportedVersion::class.java) { MusicWire.command(input) }
            assertEquals(version, error.version)
            assertEquals(MusicWire.UPLOAD, input.readUnsignedByte())
        }
        val response = bytes { MusicWire.Status.PROTOCOL_VERSION.write(it) }
        assertArrayEquals(byteArrayOf(13), response)
        assertEquals(MusicWire.Status.PROTOCOL_VERSION,
            MusicWire.Status.read(DataInputStream(ByteArrayInputStream(response))))
    }

    @Test
    fun invalidMagicAndUnknownCommandsAreRejected() {
        val badMagic = bytes { it.writeInt(0x12340002) }
        val badCommand = bytes { MusicWire.request(it, 255, "") }
        for (frame in listOf(badMagic, badCommand)) {
            val error = assertThrows(IOException::class.java) {
                MusicWire.command(DataInputStream(ByteArrayInputStream(frame)))
            }
            assertFalse(error is MusicWire.UnsupportedVersion)
        }
    }

    @Test
    fun derivesFixedWidthManualCodeFromPinnedIdentity() {
        val identity = "00000000002a" + "0".repeat(52)
        assertTrue(MusicWire.fingerprint(identity))
        assertEquals("00000042", MusicWire.pairingCode(identity))
        assertFalse(MusicWire.fingerprint("00000042"))
    }

    @Test
    fun folderNamesAreDisplayNamesRatherThanPaths() {
        assertTrue(MusicWire.validFolder("Дорога — Қазақша"))
        listOf("..", "root/album", "   ").forEach { assertFalse(MusicWire.validFolder(it)) }
    }

    private fun bytes(action: (DataOutputStream) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(action)
        return output.toByteArray()
    }
}
