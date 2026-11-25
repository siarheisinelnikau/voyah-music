package app.voyahmusic.shared.lan

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

data class PairQr(val host: String, val fingerprint: String) {
    sealed interface Result {
    data class Valid(val pair: PairQr) : Result
    data class Unsupported(val version: Int) : Result
        data object Invalid : Result
    }

    fun encode(): String = Uri.Builder().scheme("voyahmusic").authority("pair")
        .appendQueryParameter("v", MusicWire.VERSION.toString())
        .appendQueryParameter("ip", host)
        .appendQueryParameter("id", fingerprint)
        .build().toString()

    fun bitmap(size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(encode(), BarcodeFormat.QR_CODE, size, size)
        val side = matrix.width
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) pixels[y * side + x] = if (matrix[x, y]) 0xff000000.toInt() else -1
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    companion object {
        fun parse(data: String?): Result {
            if (data == null || data.length > 200) return Result.Invalid
            val uri = Uri.parse(data)
            if (uri.scheme != "voyahmusic" || uri.authority != "pair" || !uri.isHierarchical) {
                return Result.Invalid
            }
            val version = uri.getQueryParameter("v")?.toIntOrNull() ?: return Result.Invalid
            if (version !in 1..0xFFFF) return Result.Invalid
            if (version != MusicWire.VERSION) return Result.Unsupported(version)
            val host = uri.getQueryParameter("ip") ?: return Result.Invalid
            val identity = uri.getQueryParameter("id") ?: return Result.Invalid
            if (MusicWire.address(host) == null || !MusicWire.fingerprint(identity)) return Result.Invalid
            return Result.Valid(PairQr(host, identity))
        }

        fun decodeFrame(frame: ByteArray, width: Int, height: Int): String? {
            val source = PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false)
            return try {
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (error: ReaderException) {
                null
            }
        }
    }
}
