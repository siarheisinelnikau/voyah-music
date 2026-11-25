package app.voyahmusic.transfer.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import app.voyahmusic.shared.lan.PairQr
import kotlin.math.abs

internal class QrCamera(
    context: Context,
    private val onCode: (String) -> Unit,
    private val onError: () -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("QR preview").apply { start() }
    private val worker = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var closed = false
    private var nextDecode = 0L

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(preview: Surface) {
        worker.post {
            try {
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.first()
                val sizes = requireNotNull(manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val supported = sizes.getOutputSizes(ImageFormat.YUV_420_888)
                val size = supported.minBy { abs(it.width * it.height - 1280 * 720) }
                val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
                reader = frames
                frames.setOnImageAvailableListener(::decode, worker)
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closed) { device.close(); return }
                        camera = device
                        try {
                            device.createCaptureSession(listOf(preview, frames.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(active: CameraCaptureSession) {
                                    if (closed) { active.close(); return }
                                    session = active
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        request.addTarget(preview)
                                        request.addTarget(frames.surface)
                                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        active.setRepeatingRequest(request.build(), null, worker)
                                    } catch (_: Exception) { reportError() }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) = reportError()
                            }, worker)
                        } catch (_: Exception) { reportError() }
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        reportError()
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        reportError()
                    }
                }, worker)
            } catch (_: Exception) { reportError() }
        }
    }

    private fun decode(source: ImageReader) {
        source.acquireLatestImage()?.use { image ->
            val now = SystemClock.elapsedRealtime()
            if (closed || now < nextDecode) return
            nextDecode = now + 180
            val plane = image.planes[0]
            val bytes = ByteArray(image.width * image.height)
            for (row in 0 until image.height) {
                plane.buffer.position(row * plane.rowStride)
                plane.buffer.get(bytes, row * image.width, image.width)
            }
            PairQr.decodeFrame(bytes, image.width, image.height)?.let { code ->
                main.post { if (!closed) onCode(code) }
            }
        }
    }

    private fun reportError() {
        main.post { if (!closed) onError() }
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.post {
            session?.close()
            camera?.close()
            reader?.close()
            thread.quitSafely()
        }
    }
}
