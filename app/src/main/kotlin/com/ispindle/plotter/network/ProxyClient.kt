package com.ispindle.plotter.network

import com.ispindle.plotter.data.IspindlePayload
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pulls buffered readings from the iSpindle proxy (see the repo's `proxy/`
 * dir). Uses [HttpURLConnection] on the default network — the proxy lives on
 * the home LAN, unlike [IspindleConfigClient] which binds to the device AP.
 *
 * The proxy answers `GET /readings?since=N` with:
 *   {"cursor":M,"readings":[{"id":..,"ts":..,"ip":"..","payload":{…}},…]}
 * where `ts` is the proxy's receive time (epoch-ms) — the canonical reading
 * timestamp, replayed unchanged through [com.ispindle.plotter.data.Repository.ingest].
 */
class ProxyClient(private val json: Json = DEFAULT_JSON) {

    @Serializable
    data class Record(
        val id: Long,
        val ts: Long,
        val ip: String? = null,
        val payload: IspindlePayload
    )

    @Serializable
    data class Response(val cursor: Long, val readings: List<Record>)

    suspend fun pullSince(baseUrl: String, since: Long): Response = withContext(Dispatchers.IO) {
        val conn = URL("$baseUrl/readings?since=$since").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from proxy: ${body.take(200)}")
            json.decodeFromString<Response>(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
