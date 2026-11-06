package xyz.ghostwitness.app

import android.app.Activity
import android.content.Intent
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
import xyz.ghostwitness.app.mesh.MeshStatus
import xyz.ghostwitness.core.capture.Tier

/**
 * Record screen: preview, start/stop, tier (Auto follows the rate controller), mesh status and the
 * OK TO LOSE PHONE banner. Stop ends recording but keeps spreading shards; leaving the screen ends both,
 * because Android blocks background camera use without a foreground service.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, CapturePipeline.Listener {
    private data class ViewState(
        val capturing: Boolean = false,
        val sessionId: String? = null,
        val segments: Int = 0,
        val audioOnlySegments: Int = 0,
        val lastDurMs: Int = 0,
        val lastBytes: Int = 0,
        val totalBytes: Long = 0,
        val selfCheck: String? = null,
        val testMode: Boolean = false,
        val mesh: MeshStatus? = null,
        val error: String? = null,
    )

    private var spec: CameraSpec? = null
    private var previewSurface: Surface? = null
    private var pipeline: CapturePipeline? = null
    private var tier: Tier = Tier.V1
    private var autoTier = true
    private var state = ViewState()

    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var holderButton: Button
    private lateinit var autoButton: Button
    private lateinit var tierButtons: Map<Tier, Button>
    private var debugToggle: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spec = runCatching { CameraSelector.backCamera(this) }
            .onFailure { state = state.copy(error = "camera: ${it.message}") }
            .getOrNull()
        setContentView(buildLayout())
        requestMissingPermissions()
        render()
    }

    override fun onPause() {
        shutdownPipeline()
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
        shutdownPipeline()
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

    override fun onMeshStatus(status: MeshStatus) {
        state = state.copy(mesh = status)
        render()
    }

    override fun onTierApplied(tier: Tier, automatic: Boolean) {
        this.tier = tier
        render()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        spec?.let { root.addView(buildPreview(it), previewParams(it)) }
        banner = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = BANNER_TEXT_SP
            setTextColor(Color.WHITE)
            setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
        }
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
        }
        root.addView(status)
        recordButton = Button(this).apply { setOnClickListener { toggleRecording() } }
        root.addView(recordButton)
        root.addView(buildTierRow())
        holderButton = Button(this).apply {
            text = "Hold shards for someone else"
            setOnClickListener { startActivity(Intent(this@MainActivity, HolderActivity::class.java)) }
        }
        root.addView(holderButton)
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

    private fun buildTierRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(button: Button) = row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        autoButton = Button(this).apply {
            text = "Auto"
            setOnClickListener { selectAuto() }
        }.also(::add)
        val offered = spec?.tiers.orEmpty()
        tierButtons = TIERS.filter { it in offered }.associateWith { t ->
            Button(this).apply {
                text = t.name
                setOnClickListener { selectTier(t) }
            }.also(::add)
        }
        return row
    }

    private fun buildPreview(cameraSpec: CameraSpec): SurfaceView = SurfaceView(this).apply {
        holder.setFixedSize(cameraSpec.previewSize.width, cameraSpec.previewSize.height)
        holder.addCallback(this@MainActivity)
    }

    /** Portrait preview of a landscape sensor, capped at half the screen. */
    private fun previewParams(cameraSpec: CameraSpec): LinearLayout.LayoutParams {
        val metrics = resources.displayMetrics
        val size = cameraSpec.previewSize
        val natural = metrics.widthPixels * size.width / size.height
        val height = minOf(natural, metrics.heightPixels / 2)
        val width = height * size.height / size.width
        return LinearLayout.LayoutParams(width, height).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun toggleRecording() {
        if (state.capturing) {
            pipeline?.stopCapture()
            state = state.copy(capturing = false)
            render()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val cameraSpec = spec ?: return
        if (Permissions.missing(this, Permissions.capture).isNotEmpty()) {
            requestMissingPermissions()
            return
        }
        val surface = previewSurface
        if (surface == null) {
            state = state.copy(error = "preview is not ready yet")
            render()
            return
        }
        shutdownPipeline()
        try {
            val recovery = RecoveryKeys.resolve(BuildConfig.RECOVERY_PUB_HEX)
            if (recovery.testMode && !BuildConfig.DEBUG) {
                state = state.copy(error = "set a recovery key first: without it nothing can be recovered")
                render()
                return
            }
            val debugDir = if (debugToggle?.isChecked == true) getExternalFilesDir("debug-plain") else null
            val created = CapturePipeline(this, cameraSpec, surface, tier, recovery, debugDir, this)
            if (!autoTier) created.setManualTier(tier)
            pipeline = created
            created.start()
            state = ViewState(capturing = true, sessionId = created.sessionIdHex.take(SESSION_ID_CHARS), testMode = recovery.testMode)
        } catch (e: Exception) {
            pipeline = null
            state = state.copy(error = "could not start: ${e.message}")
        }
        render()
    }

    private fun shutdownPipeline() {
        pipeline?.shutdown()
        pipeline = null
        state = state.copy(capturing = false)
        if (::status.isInitialized) render()
    }

    private fun selectTier(selected: Tier) {
        autoTier = false
        tier = selected
        pipeline?.setManualTier(selected)
        render()
    }

    private fun selectAuto() {
        autoTier = true
        pipeline?.setAutoTier()
        render()
    }

    private fun requestMissingPermissions() {
        val missing = Permissions.missing(this, Permissions.capture + Permissions.mesh)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun render() {
        renderBanner()
        status.text = statusLines().joinToString("\n")
        recordButton.text = if (state.capturing) "Stop recording" else "Start recording"
        recordButton.isEnabled = spec != null
        holderButton.isEnabled = !state.capturing
        autoButton.alpha = if (autoTier) 1f else DIMMED
        tierButtons.forEach { (t, button) -> button.alpha = if (!autoTier && t == tier) 1f else DIMMED }
        debugToggle?.isEnabled = !state.capturing
    }

    private fun renderBanner() {
        val mesh = state.mesh
        val (text, color) = when {
            pipeline == null -> "Not recording" to Color.DKGRAY
            mesh == null || mesh.sealed == 0 -> "Starting..." to Color.DKGRAY
            mesh.peers == 0 && !mesh.okToLosePhone -> "LOCAL ONLY: no holders, audio only" to UNSAFE_RED
            mesh.okToLosePhone -> "OK TO LOSE PHONE  (${survives(mesh)})" to SAFE_GREEN
            else -> "NOT SAFE YET" to UNSAFE_RED
        }
        banner.text = text
        banner.setBackgroundColor(color)
    }

    /** Fault tolerance in holders, not shards: 2 holders with 5 shards between them tolerate 0 losses. */
    private fun survives(mesh: MeshStatus): String = when {
        mesh.minHolders <= 1 -> "single holder, tolerates 0 holder losses"
        else -> "tolerates ${mesh.spare} holder loss${if (mesh.spare == 1) "" else "es"}"
    }

    private fun statusLines(): List<String> = buildList {
        val mesh = state.mesh
        add(
            when {
                state.capturing -> "RECORDING  session ${state.sessionId}"
                pipeline != null -> "Stopped, still spreading  session ${state.sessionId}"
                else -> "Idle"
            },
        )
        val picture = tier.video?.let { "${it.width}x${it.height}@${it.fps}, ${tier.videoBitrate / 1000} kbps" } ?: "audio only"
        add("Tier ${tier.name} (${if (autoTier) "auto" else "manual"}): $picture")
        add("Sealed: ${state.segments} segments (${state.audioOnlySegments} audio only), ${state.totalBytes / 1024} KiB")
        if (state.segments > 0) add("Last: ${state.lastDurMs} ms, ${state.lastBytes / 1024} KiB")
        if (mesh != null) {
            add("Holders: ${mesh.peers} (${mesh.videoPeers} on Wi-Fi), ~${mesh.goodputKBps} KB/s")
            add("Recoverable up to segment ${mesh.recoverableUpTo} of ${mesh.sealed - 1}, fully spread: ${mesh.fullySpread}")
            if (mesh.peers > 0 && mesh.videoPeers == 0) add("DEGRADED: Bluetooth only, audio only")
            if (mesh.peers == 0) add("No holders nearby yet")
            mesh.lastError?.let { add("Mesh: $it") }
        }
        state.selfCheck?.let { add("Rebuild from shards 1,3,4: $it") }
        if (state.testMode) add("TEST MODE: no recovery key set, recordings cannot be recovered")
        val missing = Permissions.missing(this@MainActivity, Permissions.capture + Permissions.mesh)
        if (missing.isNotEmpty()) add("Permissions needed: ${missing.joinToString { it.substringAfterLast('.') }}")
        state.error?.let { add("Error: $it") }
    }

    private companion object {
        /** Manual picks; Auto can also choose the in-between frame rates. */
        val TIERS = listOf(Tier.V2, Tier.V1, Tier.V0, Tier.A)
        const val REQUEST_PERMISSIONS = 1
        const val PADDING = 24
        const val DIMMED = 0.45f
        const val BANNER_TEXT_SP = 20f
        const val SESSION_ID_CHARS = 12
        val SAFE_GREEN = Color.rgb(0x1B, 0x7F, 0x3A)
        val UNSAFE_RED = Color.rgb(0xA3, 0x1F, 0x1F)
    }
}
