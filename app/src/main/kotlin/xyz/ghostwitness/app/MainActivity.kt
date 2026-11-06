package xyz.ghostwitness.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import xyz.ghostwitness.app.capture.CameraSelector
import xyz.ghostwitness.app.capture.CameraSpec
import xyz.ghostwitness.app.capture.CapturePipeline
import xyz.ghostwitness.app.capture.SegmentReport
import xyz.ghostwitness.core.capture.Tier

/**
 * Record screen for block 2: preview, start/stop, a manual tier switch (the automatic rate controller is
 * wired in with the mesh in block 3) and live counters. Recording stops when the screen leaves the
 * foreground, because Android blocks background camera use without a foreground service.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, CapturePipeline.Listener {
    private data class ViewState(
        val recording: Boolean = false,
        val sessionId: String? = null,
        val segments: Int = 0,
        val audioOnlySegments: Int = 0,
        val lastDurMs: Int = 0,
        val lastBytes: Int = 0,
        val totalBytes: Long = 0,
        val selfCheck: String? = null,
        val testMode: Boolean = false,
        val error: String? = null,
    )

    private var spec: CameraSpec? = null
    private var previewSurface: Surface? = null
    private var pipeline: CapturePipeline? = null
    private var tier: Tier = Tier.V1
    private var state = ViewState()

    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var tierButtons: Map<Tier, Button>
    private var debugToggle: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spec = runCatching { CameraSelector.backCamera(this) }
            .onFailure { state = state.copy(error = "camera: ${it.message}") }
            .getOrNull()
        setContentView(buildLayout())
        if (!hasPermissions()) requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS)
        render()
    }

    override fun onPause() {
        stopRecording()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        previewSurface = holder.surface
        render()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRecording()
        previewSurface = null
    }

    override fun onSegmentSealed(report: SegmentReport) {
        state = state.copy(
            segments = state.segments + 1,
            audioOnlySegments = state.audioOnlySegments + if (report.withVideo) 0 else 1,
            lastDurMs = report.durMs,
            lastBytes = report.ciphertextBytes,
            totalBytes = state.totalBytes + report.ciphertextBytes,
            selfCheck = report.selfCheck ?: state.selfCheck,
        )
        render()
    }

    override fun onPipelineError(message: String) {
        state = state.copy(error = message)
        render()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        spec?.let { root.addView(buildPreview(it), previewParams(it)) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        root.addView(status)
        recordButton = Button(this).apply { setOnClickListener { toggleRecording() } }
        root.addView(recordButton)
        val tierRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tierButtons = TIERS.associateWith { t ->
            Button(this).apply {
                text = t.name
                setOnClickListener { selectTier(t) }
            }.also { tierRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        root.addView(tierRow)
        if (BuildConfig.DEBUG) {
            val toggle = CheckBox(this).apply {
                text = "Debug: save a playable copy of each segment"
                setTextColor(Color.WHITE)
            }
            debugToggle = toggle
            root.addView(toggle)
        }
        return root
    }

    private fun buildPreview(cameraSpec: CameraSpec): SurfaceView = SurfaceView(this).apply {
        holder.setFixedSize(cameraSpec.size.width, cameraSpec.size.height)
        holder.addCallback(this@MainActivity)
    }

    /** Portrait preview of a landscape sensor: the view is rotated 90 degrees, capped at 60% of the screen. */
    private fun previewParams(cameraSpec: CameraSpec): LinearLayout.LayoutParams {
        val metrics = resources.displayMetrics
        val natural = metrics.widthPixels * cameraSpec.size.width / cameraSpec.size.height
        val height = minOf(natural, metrics.heightPixels * 3 / 5)
        val width = height * cameraSpec.size.height / cameraSpec.size.width
        return LinearLayout.LayoutParams(width, height).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun toggleRecording() {
        if (pipeline == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val cameraSpec = spec ?: return
        if (!hasPermissions()) {
            requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS)
            return
        }
        val surface = previewSurface
        if (surface == null) {
            state = state.copy(error = "preview is not ready yet")
            render()
            return
        }
        try {
            val recovery = RecoveryKeys.resolve(BuildConfig.RECOVERY_PUB_HEX)
            val debugDir = if (debugToggle?.isChecked == true) getExternalFilesDir("debug-plain") else null
            val created = CapturePipeline(this, cameraSpec, surface, tier, recovery, debugDir, this)
            pipeline = created
            created.start()
            state = ViewState(recording = true, sessionId = created.sessionIdHex.take(SESSION_ID_CHARS), testMode = recovery.testMode)
        } catch (e: Exception) {
            pipeline = null
            state = state.copy(error = "could not start: ${e.message}")
        }
        render()
    }

    private fun stopRecording() {
        pipeline?.stop()
        pipeline = null
        state = state.copy(recording = false)
        if (::status.isInitialized) render()
    }

    private fun selectTier(selected: Tier) {
        tier = selected
        pipeline?.setTier(selected)
        render()
    }

    private fun hasPermissions(): Boolean = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun render() {
        val lines = buildList {
            add(if (state.recording) "RECORDING  session ${state.sessionId}" else "Stopped")
            add("Tier ${tier.name} (${tier.videoBitrate / 1000} kbps video)")
            add("Segments sealed: ${state.segments} (${state.audioOnlySegments} audio only)")
            if (state.segments > 0) add("Last: ${state.lastDurMs} ms, ${state.lastBytes / 1024} KiB in 5 shards")
            add("Ciphertext total: ${state.totalBytes / 1024} KiB")
            state.selfCheck?.let { add("Rebuild from shards 1,3,4: $it") }
            if (state.testMode) add("TEST MODE: no recovery key set, so these recordings cannot be recovered")
            if (!hasPermissions()) add("Camera and microphone permission needed")
            state.error?.let { add("Error: $it") }
        }
        status.text = lines.joinToString("\n")
        recordButton.text = if (pipeline == null) "Start recording" else "Stop"
        recordButton.isEnabled = spec != null
        tierButtons.forEach { (t, button) -> button.alpha = if (t == tier) 1f else DIMMED }
        debugToggle?.isEnabled = pipeline == null
    }

    private companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val TIERS = listOf(Tier.V1, Tier.V0, Tier.A)
        const val REQUEST_PERMISSIONS = 1
        const val PADDING = 24
        const val DIMMED = 0.45f
        const val SESSION_ID_CHARS = 12
    }
}
