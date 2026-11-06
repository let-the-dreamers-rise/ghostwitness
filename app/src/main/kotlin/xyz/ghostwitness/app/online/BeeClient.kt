package xyz.ghostwitness.app.online

import org.json.JSONObject

class BeeClient(private val settings: OnlineSettings) {
    fun upload(bytes: ByteArray): String {
        val response = Http.request(settings.bee + "/bytes", bytes, mapOf(
            "Content-Type" to "application/octet-stream", "Swarm-Postage-Batch-Id" to settings.postage,
            "Swarm-Deferred-Upload" to "false",
        ))
        return JSONObject(String(response, Charsets.UTF_8)).getString("reference").also(::validate)
    }
    fun fetch(reference: String): ByteArray {
        validate(reference)
        return Http.request(settings.bee + "/bytes/" + reference)
    }
    private fun validate(reference: String) { require(reference.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid Swarm reference" } }
}
