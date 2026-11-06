package xyz.ghostwitness.app.online

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A problem worth showing on screen: the message is written by this app and never contains a URL, access
 * key, private key or service response body.
 */
class OnlineIssue(message: String) : IOException(message)

object Http {
    fun request(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8 * 1024 * 1024): ByteArray {
        val target = URL(url)
        require(target.protocol == "https") { "HTTPS required" }
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw OnlineIssue("Remote service returned HTTP $code")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("Response exceeds size limit")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
