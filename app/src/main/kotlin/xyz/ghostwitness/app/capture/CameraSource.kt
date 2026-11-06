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
import android.media.MediaCodec
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/** What the encoder and muxer need to know about the back camera. */
class CameraSpec(
    val cameraId: String,
    val size: Size,
    val sensorOrientation: Int,
    /** True when frame timestamps use elapsedRealtimeNanos; otherwise they use System.nanoTime. */
    val realtimeTimestamps: Boolean,
)

object CameraSelector {
    private val PREFERRED = Size(640, 480)
    private const val MACROBLOCK = 16

    fun backCamera(context: Context): CameraSpec {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList
        val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
            ?: throw IllegalStateException("this device has no camera")
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("camera $id reports no stream configurations")
        val sizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toList()
        val size = sizes.firstOrNull { it == PREFERRED }
            ?: sizes.filter { it.width % MACROBLOCK == 0 && it.height % MACROBLOCK == 0 }
                .minByOrNull { abs(it.width * it.height - PREFERRED.width * PREFERRED.height) }
            ?: PREFERRED
        val timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        return CameraSpec(
            cameraId = id,
            size = size,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            realtimeTimestamps = timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }
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
