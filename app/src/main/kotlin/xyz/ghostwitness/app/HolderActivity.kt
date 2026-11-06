package xyz.ghostwitness.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import xyz.ghostwitness.app.holder.HolderNode
import xyz.ghostwitness.app.holder.HolderStatus
import xyz.ghostwitness.app.online.OnlineStore
import xyz.ghostwitness.app.online.UploadEngine
import xyz.ghostwitness.app.online.UploadService

/** Holder screen: keeps shards for a nearby recording phone. Runs until the screen is closed. */
class HolderActivity : Activity(), HolderNode.Listener {
    private var node: HolderNode? = null
    private var status: HolderStatus? = null
    private var error: String? = null
    private lateinit var text: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
        }
        button = Button(this).apply { setOnClickListener { toggle() } }
        root.addView(button)
        root.addView(text)
        setContentView(root)
        UploadService.schedule(this)
        val missing = Permissions.missing(this, Permissions.mesh)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        render()
    }

    override fun onDestroy() {
        node?.stop()
        node = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    override fun onHolderStatus(status: HolderStatus) {
        this.status = status
        render()
    }

    private fun toggle() {
        val running = node
        if (running != null) {
            running.stop()
            node = null
        } else {
            val missing = Permissions.missing(this, Permissions.mesh)
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                return
            }
            error = null
            node = try {
                HolderNode(this, this).also { it.start() }
            } catch (e: Exception) {
                error = "could not start: ${e.message}"
                null
            }
        }
        render()
    }

    private fun render() {
        val s = status
        val lines = buildList {
            add(if (node == null) "Not holding" else "HOLDING for a nearby recording")
            if (s != null) {
                add(if (s.connected) "Connected to recorder (${s.quality ?: "link pending"})" else "Searching for a recorder...")
                s.session?.let { add("Session $it") }
                add("Shards held: ${s.shardsHeld} across ${s.segmentsSeen} segments")
                s.rootUpto?.let { add("Newest signed root covers segments 0..$it") }
                if (s.rejected > 0) add("Rejected frames or shards: ${s.rejected}")
                s.lastProblem?.let { add("Last problem: $it") }
            }
            val missing = Permissions.missing(this@HolderActivity, Permissions.mesh)
            if (missing.isNotEmpty()) add("Nearby permissions needed: ${missing.joinToString { it.substringAfterLast('.') }}")
            error?.let { add("Error: $it") }
            add("")
            add("Encrypted shards upload automatically on suitable internet, including after this screen closes.")
            add(UploadEngine.status)
        }
        text.text = lines.joinToString("\n")
        button.text = if (node == null) "Start holding" else "Stop holding"
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 2
        const val PADDING = 24
    }
}
