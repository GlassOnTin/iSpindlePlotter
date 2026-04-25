package com.ispindle.plotter.network

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin HTTP client for the iSpindel WiFiManager config portal.
 *
 * Verified surface from the firmware (universam1/iSpindel — pio/lib/WiFiManagerKT/WiFiManagerKT.cpp):
 *
 * | Path        | Method | Notes |
 * |-------------|--------|-------|
 * | /state      | GET    | Single-quoted JSON: {'SSID':...,'Station_IP':...} |
 * | /scan       | GET    | Single-quoted wrapper, double-quoted items |
 * | /iSpindel   | GET    | HTML; we regex-extract Tilt/Temp/Battery/Gravity |
 * | /wifisave   | GET    | Form fields in querystring; HTML response, no status JSON |
 * | /reset      | GET    | Wipes config and reboots |
 *
 * Calls are issued through [Network.openConnection] so they always travel
 * over the iSpindel AP, regardless of the rest of the process's networking.
 */
class IspindleConfigClient(private val network: Network) {

    /** Verifies reachability and returns the device's view of its connection. */
    suspend fun getState(): StateInfo = request("/state") { body ->
        val json = parseLooseJson(body) as? JsonObject
            ?: throw IOException("Unexpected /state body: $body")
        StateInfo(
            ssid = json["SSID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            stationIp = json["Station_IP"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            apIp = json["Soft_AP_IP"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            stationMac = json["Station_MAC"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            apMac = json["Soft_AP_MAC"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            hasPassword = json["Password"]?.jsonPrimitive?.boolean ?: false
        )
    }

    /** Lists nearby WiFi networks the iSpindle can see. */
    suspend fun scanNetworks(): List<ScannedAp> = request("/scan") { body ->
        val outer = parseLooseJson(body) as? JsonObject
            ?: throw IOException("Unexpected /scan body: $body")
        val arr = outer["Access_Points"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ScannedAp(
                ssid = obj["SSID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                encrypted = obj["Encryption"]?.jsonPrimitive?.boolean ?: true,
                quality = obj["Quality"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            )
        }.filter { it.ssid.isNotBlank() }
            .distinctBy { it.ssid }
            .sortedByDescending { it.quality }
    }

    /** Parses the auto-refreshing /iSpindel HTML page for live readings. */
    suspend fun getLiveReading(): LiveReading = request("/iSpindel") { body ->
        fun tdAfter(label: String): String? {
            val rx = Regex(
                "<tr><td>$label[^<]*</td><td>([^<]*)",
                RegexOption.IGNORE_CASE
            )
            return rx.find(body)?.groupValues?.getOrNull(1)?.trim()
        }
        fun firstNumber(s: String?): Double? =
            s?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

        LiveReading(
            tiltDeg = firstNumber(tdAfter("Tilt")),
            temperature = firstNumber(tdAfter("Temperature")),
            batteryV = firstNumber(tdAfter("Battery")),
            gravity = firstNumber(tdAfter("Gravity"))
        )
    }

    /**
     * Submits the WiFi credentials and iSpindel parameters via /wifisave.
     *
     * The firmware reads each [WiFiManagerParameter] by ID (see
     * iSpindel.cpp:325-345). We pass only the params we know about; unknown
     * keys are ignored.
     */
    suspend fun saveConfig(form: ConfigForm): String = withContext(Dispatchers.IO) {
        val params = buildList {
            add("s" to form.homeSsid)
            add("p" to form.homePassword)
            form.deviceName?.let { add("name" to it) }
            form.sleepSeconds?.let { add("sleep" to it.toString()) }
            form.serverHost?.let { add("server" to it) }
            form.serverPort?.let { add("port" to it.toString()) }
            form.serverPath?.let { add("uri" to it) }
            form.token?.let { add("token" to it) }
            // The firmware reads selAPI as a DataType enum (Globals.h),
            // where 3 = Generic HTTP — the only mode that POSTs to a user-
            // configurable host:port matching what this app listens for.
            // Mistakes here are silent: the iSpindle just reports to the
            // wrong service. Default to GenericHttp unless caller overrides.
            add("selAPI" to (form.serviceTypeIndex ?: IspindleService.GenericHttp.selApi).toString())
        }
        val q = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        rawGet("/wifisave?$q")
    }

    /** Waits for the iSpindle to drop the AP and join [expectedSsid]. */
    suspend fun pollUntilJoined(
        expectedSsid: String,
        attempts: Int = 30,
        intervalMs: Long = 1_500
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(attempts) {
            try {
                val state = getState()
                if (state.ssid.equals(expectedSsid, ignoreCase = true) &&
                    state.stationIp.isNotBlank() &&
                    state.stationIp != "0.0.0.0"
                ) {
                    return@withContext true
                }
            } catch (_: IOException) {
                // Expected: once the iSpindle reboots, the AP goes away and
                // /state stops responding. Treat as "still working".
            }
            Thread.sleep(intervalMs)
        }
        false
    }

    private suspend fun rawGet(path: String): String = withContext(Dispatchers.IO) {
        val url = URL("http://$HOST$path")
        val conn = network.openConnection(url) as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..399) throw IOException("HTTP $code from $path: ${body.take(200)}")
            body
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private suspend fun <T> request(path: String, parse: (String) -> T): T {
        val body = rawGet(path)
        return try {
            parse(body)
        } catch (t: Throwable) {
            Log.w(TAG, "Parse failed for $path: ${t.message}; body=${body.take(200)}")
            throw IOException("Failed to parse $path: ${t.message}", t)
        }
    }

    private fun parseLooseJson(body: String): JsonElement {
        // The firmware emits single-quoted keys/values for /state and the
        // /scan wrapper. The SSID/Quality strings inside scan items use double
        // quotes already, so a blanket replacement is safe — no string in the
        // payload contains a literal ASCII quote of either kind.
        val sanitised = body.trim().replace('\'', '"')
        return LOOSE_JSON.parseToJsonElement(sanitised)
    }

    companion object {
        const val HOST = "192.168.4.1"
        private const val TIMEOUT_MS = 6_000
        private const val TAG = "IspindleConfigClient"
        private val LOOSE_JSON = Json { isLenient = true; ignoreUnknownKeys = true }
    }
}

@Serializable
data class StateInfo(
    val ssid: String,
    val stationIp: String,
    val apIp: String,
    val stationMac: String,
    val apMac: String,
    val hasPassword: Boolean
) {
    val isJoinedToHome: Boolean
        get() = ssid.isNotBlank() && stationIp.isNotBlank() && stationIp != "0.0.0.0"
}

@Serializable
data class ScannedAp(val ssid: String, val encrypted: Boolean, val quality: Int)

@Serializable
data class LiveReading(
    val tiltDeg: Double?,
    val temperature: Double?,
    val batteryV: Double?,
    val gravity: Double?
)

data class ConfigForm(
    val homeSsid: String,
    val homePassword: String,
    val deviceName: String? = null,
    val sleepSeconds: Int? = null,
    val serverHost: String? = null,
    val serverPort: Int? = null,
    val serverPath: String? = "/",
    val token: String? = null,
    val serviceTypeIndex: Int? = null
)

/**
 * iSpindel firmware's `selAPI` field maps to a service-type enum defined in
 * `pio/lib/Globals/Globals.h` (the `DT*` defines). The integer value posted
 * to /wifisave?selAPI=N is taken directly from that enum, so picking the
 * wrong number sends the device's reports to a completely different
 * service. The default for this app is `Generic HTTP` (3) — that is the one
 * mode that POSTs to a configurable host:port and matches what
 * IspindleHttpServer listens for.
 */
enum class IspindleService(val selApi: Int, val label: String) {
    GenericHttp(3, "Generic HTTP"),
    GenericHttps(15, "Generic HTTPS"),
    Ubidots(0, "Ubidots"),
    Thingspeak(1, "Thingspeak"),
    CraftBeerPi(2, "CraftBeerPi"),
    Tcontrol(4, "TControl"),
    Fhem(5, "FHEM"),
    GenericTcp(6, "Generic TCP"),
    Ispindelde(7, "iSpindel.de"),
    InfluxDb(8, "InfluxDB"),
    Prometheus(9, "Prometheus"),
    Mqtt(10, "MQTT"),
    Blynk(12, "Blynk"),
    BrewBlox(13, "BrewBlox"),
    AwsMqtt(14, "AWS IoT MQTT"),
    Bricks(16, "Bricks");

    companion object {
        fun fromSelApi(value: Int?): IspindleService =
            entries.firstOrNull { it.selApi == value } ?: GenericHttp
    }
}
