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

    /**
     * Scrapes every `<input id="..." ... value="...">` from the /wifi
     * page so we can echo non-edited fields back unchanged in /wifisave.
     *
     * The firmware (WiFiManagerKT.cpp:790-808) iterates every registered
     * parameter and writes whatever `server->arg(id)` returns — including
     * missing parameters, which come back as empty strings. Without this
     * baseline, every save would wipe POLYN, name, vfact (battery cal),
     * token, and any other field we didn't explicitly resend.
     */
    suspend fun getCurrentFormFields(): Map<String, String> {
        val body = rawGet("/wifi")
        val tagRegex = Regex("""<input\b[^>]*?>""", RegexOption.IGNORE_CASE)
        val idRegex = Regex("""\bid="([^"]+)"""", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("""\bvalue="([^"]*)"""", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<String, String>()
        for (m in tagRegex.findAll(body)) {
            val tag = m.value
            val id = idRegex.find(tag)?.groupValues?.getOrNull(1) ?: continue
            val value = valueRegex.find(tag)?.groupValues?.getOrNull(1) ?: continue
            out[id] = value.replace("&amp;", "&")
        }
        return out
    }

    /**
     * Reads the saved POLYN expression by scraping the /wifi config form.
     * The firmware renders every saved parameter as
     *   <input id="POLYN" name="POLYN" length=N placeholder="..." value="...">
     * (HTTP_FORM_PARAM in WiFiManagerKT.h:131), and this is the only public
     * way to read the calibration string back — there is no JSON dump.
     *
     * Returns null if the field is absent or empty (uncalibrated device).
     */
    suspend fun getCurrentPolynomial(): String? {
        val body = rawGet("/wifi")
        // Match the input element with id="POLYN" anywhere on the page,
        // then pull the value attribute. Anchored to id= to avoid catching
        // a stray "POLYN" inside the placeholder text.
        val inputRegex = Regex("""<input\s[^>]*id="POLYN"[^>]*>""", RegexOption.IGNORE_CASE)
        val tag = inputRegex.find(body)?.value ?: return null
        val valueRegex = Regex("""value="([^"]*)"""", RegexOption.IGNORE_CASE)
        val value = valueRegex.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        // The firmware HTML-encodes &amp;, &lt;, etc. via htmlencode() —
        // for the polynomial form (digits, +, -, *, ^, e, dots, tilt) only
        // &amp; would matter, so undo that one.
        return value.replace("&amp;", "&").takeIf { it.isNotBlank() }
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
     * Starts from [baseline] (every field currently saved on the device,
     * read from /wifi via [getCurrentFormFields]) so any field we don't
     * explicitly override is preserved. Without this, the firmware would
     * blank POLYN, vfact, name, token and friends on every save — the bug
     * that left the user's precalibrated cubic empty after pairing.
     *
     * The firmware reads each [WiFiManagerParameter] by ID
     * (iSpindel.cpp:325-345). Unknown keys in the querystring are ignored.
     */
    suspend fun saveConfig(form: ConfigForm, baseline: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val params = LinkedHashMap<String, String>()
            // Echo everything we read back, so unmodified fields survive.
            params.putAll(baseline)
            // Then overlay the WiFi credentials and any form values the
            // user explicitly set. Null entries leave the baseline alone.
            params["s"] = form.homeSsid
            params["p"] = form.homePassword
            form.deviceName?.let { params["name"] = it }
            form.sleepSeconds?.let { params["sleep"] = it.toString() }
            form.serverHost?.let { params["server"] = it }
            form.serverPort?.let { params["port"] = it.toString() }
            form.serverPath?.let { params["uri"] = it }
            form.token?.let { params["token"] = it }
            // selAPI must match this app's listener (3 = Generic HTTP);
            // see Globals.h for the enum and earlier session for the
            // selAPI=0 = Ubidots regression that prompted this default.
            params["selAPI"] =
                (form.serviceTypeIndex ?: IspindleService.GenericHttp.selApi).toString()

            val q = params.entries.joinToString("&") { (k, v) ->
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
