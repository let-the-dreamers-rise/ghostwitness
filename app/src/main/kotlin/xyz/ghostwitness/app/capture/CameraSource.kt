package xyz.ghostwitness.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor
import kotlin.math.abs
import xyz.ghostwitness.core.capture.Tier

/** What the scaler, encoders and muxer need to know about the back camera. */
class CameraSpec(
    val cameraId: String,
    /** The stream into the GL scaler; tiers larger than its 16:9 crop are not offered. */
    val streamSize: Size,
    /** The preview SurfaceView's buffer size. */
    val previewSize: Size,
    val sensorOrientation: Int,
    /** True when frame timestamps use elapsedRealtimeNanos; otherwise they use System.nanoTime. */
    val realtimeTimestamps: Boolean,
    /** Tiers this camera and this phone's H.264 encoder can do, best first. Always ends with [Tier.A]. */
    val tiers: List<Tier>,
)

/**
 * Picks the back camera and a stream size near 1280x720 (spec section 5). Phones differ in which sizes
 * they offer: the closest one wins, and a phone without 720p falls back to 480p or 240p tiers instead of
 * failing setup. Recording never starts above the chosen tier, and never at the camera's 1080p or 4K
 * default.
 */
object CameraSelector {
    private val WANTED = Size(1280, 720)
    private const val WIDE = 16.0 / 9.0
    private const val ASPECT_TOLERANCE = 0.05

    fun backCamera(context: Context): CameraSpec {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList
        val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
            ?: throw IllegalStateException("this device has no camera")
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("camera $id reports no stream configurations")
        val stream = pickStream(map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList())
        val preview = pickPreview(map.getOutputSizes(SurfaceHolder::class.java).orEmpty().toList(), stream)
        val timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val (cropWidth, cropHeight) = wideCrop(stream)
        val tiers = Tier.fitting(cropWidth, cropHeight).filter { tier -> tier.video?.let(VideoEncoder::supports) ?: true }
        return CameraSpec(
            cameraId = id,
            streamSize = stream,
            previewSize = preview,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            realtimeTimestamps = timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            tiers = tiers,
        )
    }

    /** Exactly 720p; else the smallest 16:9 size at least that big; else the largest size up to 720p. */
    private fun pickStream(sizes: List<Size>): Size {
        if (WANTED in sizes) return WANTED
        return sizes.filter { isWide(it) && it.width >= WANTED.width && it.height >= WANTED.height }.minByOrNull { area(it) }
            ?: sizes.filter { area(it) <= area(WANTED) }.maxByOrNull { area(it) }
            ?: sizes.minByOrNull { area(it) }
            ?: throw IllegalStateException("camera offers no stream sizes")
    }

    private fun pickPreview(sizes: List<Size>, stream: Size): Size =
        sizes.firstOrNull { it == stream }
            ?: sizes.filter { abs(aspect(it) - aspect(stream)) < ASPECT_TOLERANCE && area(it) <= area(WANTED) }.maxByOrNull { area(it) }
            ?: stream

    /** The 16:9 centre crop the scaler takes from the stream. */
    private fun wideCrop(size: Size): Pair<Int, Int> =
        if (size.width * 9 > size.height * 16) size.height * 16 / 9 to size.height else size.width to size.width * 9 / 16

    private fun isWide(size: Size): Boolean = abs(aspect(size) - WIDE) < ASPECT_TOLERANCE

    private fun aspect(size: Size): Double = size.width.toDouble() / size.height

    private fun area(size: Size): Long = size.width.toLong() * size.height
}

/** Opens the camera and streams into the given surfaces. All callbacks run on [handler]. */
class CameraSource(
    private val context: Context,
    private val handler: Handler,
    private val onError: (String) -> Unit,
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var closed = false

    // The activity checks CAMERA before it builds a pipeline.
    @SuppressLint("MissingPermission")
    fun open(spec: CameraSpec, targets: List<Surface>) {
        val manager = context.getSystemService(CameraManager::class.java)
        try {
            manager.openCamera(spec.cameraId, stateCallback(targets), handler)
        } catch (e: CameraAccessException) {
            onError("camera unavailable: ${e.message}")
        }
    }

    /** Must be called on [handler]'s thread. */
    fun close() {
        closed = true
        session?.close()
        session = null
        device?.close()
        device = null
    }

    private fun stateCallback(targets: List<Surface>) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }
            device = camera
            startSession(camera, targets)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            if (!closed) onError("camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            if (!closed) onError("camera error $error")
        }
    }

    private fun startSession(camera: CameraDevice, targets: List<Surface>) {
        val executor = Executor { handler.post(it) }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    targets.forEach { request.addTarget(it) }
                    configured.setRepeatingRequest(request.build(), null, handler)
                } catch (e: CameraAccessException) {
                    onError("camera request failed: ${e.message}")
                } catch (e: IllegalStateException) {
                    onError("camera closed while starting: ${e.message}")
                }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                onError("camera could not stream to the encoder and preview")
            }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, targets.map { OutputConfiguration(it) }, executor, callback)
        try {
            camera.createCaptureSession(config)
        } catch (e: CameraAccessException) {
            onError("camera session failed: ${e.message}")
        }
    }
}
