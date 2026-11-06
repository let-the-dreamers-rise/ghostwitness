package xyz.ghostwitness.app.capture.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import xyz.ghostwitness.core.capture.FrameGate
import xyz.ghostwitness.core.capture.VideoProfile

/**
 * The GL scaler between the camera and the video encoders (spec section 5). The camera streams into one
 * SurfaceTexture at [cameraSize]; each camera frame is drawn, scaled and centre-cropped, into every
 * attached encoder input surface whose [FrameGate] says the frame is due. So each encoder generation gets
 * its own size and frame rate, and a size change never touches the camera session.
 *
 * Every public method may be called from any thread; all GL work runs on its own thread ("gw-gl").
 */
class CameraRenderer(private val cameraSize: Size, private val onError: (String) -> Unit) {
    private class Target(val surface: Surface, val egl: EGLSurface, val width: Int, val height: Int, val gate: FrameGate)

    private val thread = HandlerThread("gw-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val transform = FloatArray(MATRIX_SIZE)

    // GL thread only below.
    private var egl: EglCore? = null
    private var idle: EGLSurface? = null
    private var program: CameraProgram? = null
    private var texture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var textureId = 0
    private var targets: Map<Int, Target> = emptyMap()
    private var released = false

    /** Sets up EGL and the camera texture, then hands the camera's output surface to [onReady]. */
    fun start(onReady: (Surface) -> Unit) = handler.post {
        try {
            val core = EglCore().also { egl = it }
            val pbuffer = core.createPbuffer().also { idle = it }
            core.makeCurrent(pbuffer)
            val drawer = CameraProgram().also { program = it }
            textureId = drawer.createTexture()
            val st = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(cameraSize.width, cameraSize.height)
                setOnFrameAvailableListener({ onFrame() }, handler)
            }
            texture = st
            onReady(Surface(st).also { cameraSurface = it })
        } catch (e: RuntimeException) {
            onError("video scaler failed to start: ${e.message}")
        }
    }

    /** Starts drawing into an encoder's input surface at [profile]'s size and frame rate. */
    fun addTarget(generation: Int, surface: Surface, profile: VideoProfile) = handler.post {
        val core = egl ?: return@post
        if (released) return@post
        try {
            val window = core.createWindowSurface(surface)
            targets = targets + (generation to Target(surface, window, profile.width, profile.height, FrameGate(profile.fps)))
        } catch (e: RuntimeException) {
            onError("could not attach encoder $generation: ${e.message}")
        }
    }

    fun setFps(generation: Int, fps: Int) = handler.post {
        val target = targets[generation] ?: return@post
        targets = targets + (generation to Target(target.surface, target.egl, target.width, target.height, target.gate.withFps(fps)))
    }

    /** Stops drawing into an encoder; [then] runs on the GL thread once its EGL surface is gone. */
    fun removeTarget(generation: Int, then: () -> Unit) = handler.post {
        targets[generation]?.let { target ->
            targets = targets - generation
            egl?.let { core ->
                idle?.let(core::makeCurrent)
                core.releaseSurface(target.egl)
            }
        }
        then()
    }

    /** Releases everything and ends the GL thread; [then] runs on the GL thread just before it quits. */
    fun release(then: () -> Unit) = handler.post {
        released = true
        val core = egl
        if (core != null) {
            idle?.let(core::makeCurrent)
            targets.values.forEach { core.releaseSurface(it.egl) }
            targets = emptyMap()
            texture?.release()
            cameraSurface?.release()
            program?.release()
            idle?.let(core::releaseSurface)
            core.release()
        }
        egl = null
        then()
        thread.quitSafely()
    }

    private fun onFrame() {
        val st = texture ?: return
        val core = egl ?: return
        val drawer = program ?: return
        if (released) return
        try {
            idle?.let(core::makeCurrent)
            st.updateTexImage()
            st.getTransformMatrix(transform)
            val timestampNs = st.timestamp
            targets = targets.mapValues { (generation, target) -> drawInto(core, drawer, generation, target, timestampNs) }
        } catch (e: RuntimeException) {
            onError("video scaler: ${e.message}")
        }
    }

    private fun drawInto(core: EglCore, drawer: CameraProgram, generation: Int, target: Target, timestampNs: Long): Target {
        val (gate, due) = target.gate.offer(timestampNs)
        if (due) {
            core.makeCurrent(target.egl)
            drawer.draw(textureId, transform, target.width, target.height, cameraSize.width, cameraSize.height)
            core.setPresentationTime(target.egl, timestampNs)
            if (!core.swapBuffers(target.egl)) onError("encoder $generation dropped a frame")
        }
        return Target(target.surface, target.egl, target.width, target.height, gate)
    }

    private companion object {
        const val MATRIX_SIZE = 16
    }
}
