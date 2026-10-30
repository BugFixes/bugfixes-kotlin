package es.bugfix.client

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** POSTs one report with the mobile key. Never follows redirects, so the key only goes to [server]. */
internal class Sender(
    private val server: String,
    private val key: String,
    private val app: String,
    private val timeoutMs: Int = 15_000,
) {
    enum class Outcome { SENT, DROP, RETRY }

    fun send(endpoint: String, body: String): Outcome {
        val conn = try {
            URI("$server/$endpoint").toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return Outcome.DROP
        }
        return try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("X-Bugfixes-App", app)
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (conn.responseCode) {
                in 200..299 -> Outcome.SENT
                408, 429, in 500..599 -> Outcome.RETRY
                // a redirect or a rejected report (bad key, bad payload) won't succeed later either
                else -> Outcome.DROP
            }
        } catch (_: IOException) {
            Outcome.RETRY
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val localHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2") // 10.0.2.2: the emulator's host

        /** https, or plain http only to this machine (or the emulator's host) for local development. */
        fun allowed(server: String): Boolean = runCatching {
            val uri = URI(server)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() || uri.scheme == "http" && uri.host in localHosts
        }.getOrDefault(false)
    }
}
