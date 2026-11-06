package xyz.ghostwitness.app.mesh

import android.content.Context
import android.os.Handler
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import xyz.ghostwitness.core.mesh.LinkQuality

/**
 * Nearby Connections with P2P_STAR (spec section 6.1): the recording phone advertises as the hub, holders
 * discover and connect. Nearby starts on Bluetooth and upgrades to Wi-Fi on its own; [Events.onQuality]
 * reports the result. Control frames are BYTES payloads; shard bodies are FILE payloads announced by a
 * header frame carrying the payload id. Nearby calls back on the main thread; every event is re-posted to
 * [handler], so the owner sees a single thread.
 */
class NearbyLink(context: Context, private val handler: Handler, private val events: Events) {
    interface Events {
        fun onConnected(endpointId: String, name: String)

        fun onDisconnected(endpointId: String)

        fun onQuality(endpointId: String, quality: LinkQuality)

        fun onBytes(endpointId: String, bytes: ByteArray)

        fun onFile(endpointId: String, payloadId: Long, bytes: ByteArray)

        fun onLinkError(message: String)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val names = ConcurrentHashMap<String, String>()
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Incoming FILE payloads waiting for their transfer to finish. Handler thread only. */
    private val incomingFiles = HashMap<Long, Payload>()

    @Volatile
    private var localName = ""

    @Volatile
    private var peerPrefix = ""

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            handler.post {
                when (payload.type) {
                    Payload.Type.BYTES -> payload.asBytes()?.let { events.onBytes(endpointId, it) }
                    Payload.Type.FILE -> incomingFiles[payload.id] = payload
                    else -> events.onLinkError("unexpected payload type ${payload.type} from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            handler.post { onTransferFinished(endpointId, update) }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!info.endpointName.startsWith(peerPrefix)) {
                client.rejectConnection(endpointId)
                return
            }
            names[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloads).addOnFailureListener { fail("accept $endpointId", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            requested.remove(endpointId)
            if (resolution.status.isSuccess) {
                val name = names[endpointId] ?: endpointId
                handler.post { events.onConnected(endpointId, name) }
            } else {
                names.remove(endpointId)
                handler.post { events.onLinkError("connection to $endpointId failed: ${resolution.status.statusCode}") }
            }
        }

        override fun onDisconnected(endpointId: String) {
            names.remove(endpointId)
            handler.post { events.onDisconnected(endpointId) }
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            val quality = when (info.quality) {
                BandwidthInfo.Quality.HIGH -> LinkQuality.HIGH
                BandwidthInfo.Quality.MEDIUM -> LinkQuality.MEDIUM
                else -> LinkQuality.LOW
            }
            handler.post { events.onQuality(endpointId, quality) }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || !info.endpointName.startsWith(peerPrefix)) return
            if (!requested.add(endpointId)) return
            names[endpointId] = info.endpointName
            client.requestConnection(localName, endpointId, lifecycle).addOnFailureListener {
                requested.remove(endpointId)
                fail("connect $endpointId", it)
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    /** Hub role: accept holders whose name starts with [acceptPrefix]. */
    fun advertise(name: String, acceptPrefix: String) {
        localName = name
        peerPrefix = acceptPrefix
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(name, SERVICE_ID, lifecycle, options).addOnFailureListener { fail("advertise", it) }
    }

    /** Spoke role: connect to the first hub whose name starts with [connectPrefix]. */
    fun discover(name: String, connectPrefix: String) {
        localName = name
        peerPrefix = connectPrefix
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(SERVICE_ID, discovery, options).addOnFailureListener { fail("discover", it) }
    }

    fun sendBytes(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).addOnFailureListener { fail("send to $endpointId", it) }
    }

    /** Sends the header frame built from the FILE payload's id, then the file itself. */
    fun sendFile(endpointId: String, file: File, header: (payloadId: Long) -> ByteArray) {
        val payload = try {
            Payload.fromFile(file)
        } catch (e: FileNotFoundException) {
            events.onLinkError("missing shard file ${file.name}")
            return
        }
        sendBytes(endpointId, header(payload.id))
        client.sendPayload(endpointId, payload).addOnFailureListener { fail("send file to $endpointId", it) }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        requested.clear()
        names.clear()
    }

    private fun onTransferFinished(endpointId: String, update: PayloadTransferUpdate) {
        // BYTES payloads and our own outgoing files also report here; only incoming files are tracked.
        val payload = incomingFiles.remove(update.payloadId) ?: return
        if (update.status != PayloadTransferUpdate.Status.SUCCESS) {
            events.onLinkError("file ${update.payloadId} from $endpointId ended with status ${update.status}")
            return
        }
        val file = payload.asFile() ?: return
        val bytes = try {
            ParcelFileDescriptor.AutoCloseInputStream(file.asParcelFileDescriptor()).use { it.readBytes() }
        } catch (e: IOException) {
            events.onLinkError("could not read file ${update.payloadId}: ${e.message}")
            return
        }
        @Suppress("DEPRECATION")
        runCatching { file.asJavaFile()?.delete() }
        events.onFile(endpointId, update.payloadId, bytes)
    }

    private fun fail(what: String, error: Exception) {
        handler.post { events.onLinkError("$what: ${error.message}") }
    }

    companion object {
        const val SERVICE_ID = "xyz.ghostwitness.mesh"
        const val WITNESS_PREFIX = "GW-W"
        const val HOLDER_PREFIX = "GW-H"
    }
}
