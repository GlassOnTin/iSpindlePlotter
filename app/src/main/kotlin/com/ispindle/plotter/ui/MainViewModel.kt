package com.ispindle.plotter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ispindle.plotter.IspindleApp
import com.ispindle.plotter.calibration.Polynomial
import com.ispindle.plotter.calibration.PolynomialFit
import com.ispindle.plotter.data.CalibrationPoint
import com.ispindle.plotter.data.Device
import com.ispindle.plotter.data.Reading
import com.ispindle.plotter.data.Repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repo: Repository) : ViewModel() {

    val devices: StateFlow<List<Device>> =
        repo.devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val latestReading: StateFlow<Reading?> =
        repo.observeLatestAny().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun readingsFor(deviceId: Long): Flow<List<Reading>> = repo.observeReadings(deviceId)
    fun deviceFlow(deviceId: Long): Flow<Device?> = repo.observeDevice(deviceId)
    fun calibrationFlow(deviceId: Long): Flow<List<CalibrationPoint>> = repo.observeCalibration(deviceId)
    fun latestReadingFor(deviceId: Long): Flow<Reading?> = repo.observeLatestReading(deviceId)

    fun rename(deviceId: Long, label: String) {
        viewModelScope.launch { repo.renameDevice(deviceId, label) }
    }

    fun deleteDevice(deviceId: Long) {
        viewModelScope.launch { repo.deleteDevice(deviceId) }
    }

    suspend fun readingCount(deviceId: Long): Int = repo.readingCount(deviceId)

    suspend fun readingCountBefore(deviceId: Long, cutoffMs: Long): Int =
        repo.readingCountBefore(deviceId, cutoffMs)

    suspend fun deleteReadingsBefore(deviceId: Long, cutoffMs: Long): Int =
        repo.deleteReadingsBefore(deviceId, cutoffMs)

    /**
     * Builds an RFC4180-ish CSV of every reading for [deviceId]. Header
     * names are stable so receivers can parse by column name. Timestamps
     * are written both as ISO-8601 in UTC and as raw epoch milliseconds
     * so spreadsheets and scripts can pick whichever they prefer.
     */
    suspend fun exportReadingsCsv(deviceId: Long): String {
        val device = repo.deviceById(deviceId) ?: return ""
        val readings = repo.readingsSnapshot(deviceId)
        val iso = java.time.format.DateTimeFormatter.ISO_INSTANT
        val sb = StringBuilder(readings.size * 80 + 256)
        sb.append("device_id,device_label,reported_name,hw_id,timestamp_iso,timestamp_ms,")
        sb.append("angle_deg,temperature_c,battery_v,rssi_dbm,interval_s,")
        sb.append("reported_gravity,computed_gravity\n")
        val labelEsc = csvEscape(device.userLabel)
        val nameEsc = csvEscape(device.reportedName)
        for (r in readings) {
            sb.append(device.id).append(',')
            sb.append(labelEsc).append(',')
            sb.append(nameEsc).append(',')
            sb.append(device.hwId).append(',')
            sb.append(iso.format(java.time.Instant.ofEpochMilli(r.timestampMs))).append(',')
            sb.append(r.timestampMs).append(',')
            sb.append("%.4f".format(r.angle)).append(',')
            sb.append("%.4f".format(r.temperatureC)).append(',')
            sb.append("%.3f".format(r.batteryV)).append(',')
            sb.append(r.rssi?.toString().orEmpty()).append(',')
            sb.append(r.intervalS?.toString().orEmpty()).append(',')
            sb.append(r.reportedGravity?.let { "%.4f".format(it) }.orEmpty()).append(',')
            sb.append(r.computedGravity?.let { "%.4f".format(it) }.orEmpty()).append('\n')
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    fun addCalibrationPoint(deviceId: Long, angle: Double, sg: Double, note: String) {
        viewModelScope.launch { repo.addCalibrationPoint(deviceId, angle, sg, note) }
    }

    fun deleteCalibrationPoint(p: CalibrationPoint) {
        viewModelScope.launch { repo.deleteCalibrationPoint(p) }
    }

    fun toggleCalibrationPoint(p: CalibrationPoint) {
        viewModelScope.launch { repo.updateCalibrationPoint(p.copy(enabled = !p.enabled)) }
    }

    fun clearCalibration(deviceId: Long) {
        viewModelScope.launch { repo.clearCalibration(deviceId) }
    }

    /**
     * Fits a polynomial through the enabled calibration points for this
     * device and stores it. Returns a human-readable summary of the outcome.
     */
    suspend fun fitAndSave(deviceId: Long, degree: Int): FitOutcome {
        val pts = repo.enabledCalibrationPoints(deviceId)
        if (pts.size < degree + 1) {
            return FitOutcome.NotEnoughPoints(pts.size, degree + 1)
        }
        val xs = pts.map { it.angle }.toDoubleArray()
        val ys = pts.map { it.knownSG }.toDoubleArray()
        val result = PolynomialFit.fit(xs, ys, degree) ?: return FitOutcome.Singular
        repo.saveFittedPolynomial(deviceId, result.polynomial, result.rSquared)
        return FitOutcome.Fitted(result.polynomial, result.rSquared)
    }

    sealed class FitOutcome {
        data class Fitted(val polynomial: Polynomial, val rSquared: Double?) : FitOutcome()
        data class NotEnoughPoints(val have: Int, val need: Int) : FitOutcome()
        data object Singular : FitOutcome()
    }

    /**
     * Parses a CSV produced by [exportReadingsCsv] (or compatible
     * tools — anything that has a header row with the documented column
     * names) and inserts the rows into the given device. Rows whose
     * timestamp already has a stored reading for this device are skipped
     * so re-importing a partially-overlapping export is idempotent.
     *
     * Returns the import outcome. Doesn't throw on malformed individual
     * rows — those increment `skipped` and the rest of the file is still
     * imported.
     */
    suspend fun importReadingsCsv(deviceId: Long, csv: String): ImportResult {
        val lines = csv.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size < 2) return ImportResult(0, 0, "empty CSV")
        val header = parseCsvRow(lines.first()).map { it.trim().lowercase() }
        fun col(name: String): Int = header.indexOf(name)
        val tsIdx = col("timestamp_ms")
        val angleIdx = col("angle_deg")
        val tempIdx = col("temperature_c")
        val battIdx = col("battery_v")
        val rssiIdx = col("rssi_dbm")
        val intervalIdx = col("interval_s")
        val reportedSgIdx = col("reported_gravity")
        val computedSgIdx = col("computed_gravity")
        if (tsIdx < 0 || angleIdx < 0 || tempIdx < 0 || battIdx < 0) {
            return ImportResult(
                inserted = 0, skipped = 0,
                error = "missing required columns (timestamp_ms, angle_deg, temperature_c, battery_v)"
            )
        }
        val parsed = mutableListOf<Reading>()
        var malformed = 0
        for (line in lines.drop(1)) {
            val cells = parseCsvRow(line)
            try {
                val ts = cells[tsIdx].trim().toLong()
                val angle = cells[angleIdx].trim().toDouble()
                val temp = cells[tempIdx].trim().toDouble()
                val batt = cells[battIdx].trim().toDouble()
                val rssi = cells.getOrNull(rssiIdx)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                val intervalS = cells.getOrNull(intervalIdx)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                val reportedSg = cells.getOrNull(reportedSgIdx)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                val computedSg = cells.getOrNull(computedSgIdx)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                parsed += Reading(
                    deviceId = deviceId,
                    timestampMs = ts,
                    angle = angle,
                    temperatureC = temp,
                    batteryV = batt,
                    rssi = rssi,
                    intervalS = intervalS,
                    reportedGravity = reportedSg,
                    computedGravity = computedSg
                )
            } catch (_: Exception) {
                malformed++
            }
        }
        val (inserted, skipped) = repo.importReadings(deviceId, parsed)
        return ImportResult(
            inserted = inserted,
            skipped = skipped + malformed,
            error = null
        )
    }

    /**
     * Builds a JSON settings backup: every device's user label, calibration
     * polynomial, and raw calibration points. Reading data is NOT
     * included — that goes through the CSV export/import surface, which
     * can ship gigabytes of rows that this lightweight backup shouldn't
     * try to round-trip.
     */
    suspend fun exportSettingsJson(): String {
        val root = org.json.JSONObject()
        root.put("schema", "ispindle-plotter-settings")
        root.put("version", 1)
        root.put("exportedAtMs", System.currentTimeMillis())
        val devicesArr = org.json.JSONArray()
        for (d in repo.allDevices()) {
            val obj = org.json.JSONObject()
            obj.put("hwId", d.hwId)
            obj.put("reportedName", d.reportedName)
            obj.put("userLabel", d.userLabel)
            obj.put("calA", d.calA)
            obj.put("calB", d.calB)
            obj.put("calC", d.calC)
            obj.put("calD", d.calD)
            obj.put("calDegree", d.calDegree)
            d.calRSquared?.let { obj.put("calRSquared", it) }
            val pointsArr = org.json.JSONArray()
            for (p in repo.calibrationSnapshot(d.id)) {
                pointsArr.put(
                    org.json.JSONObject()
                        .put("angle", p.angle)
                        .put("knownSG", p.knownSG)
                        .put("note", p.note)
                        .put("createdMs", p.createdMs)
                        .put("enabled", p.enabled)
                )
            }
            obj.put("calibrationPoints", pointsArr)
            devicesArr.put(obj)
        }
        root.put("devices", devicesArr)
        return root.toString(2)
    }

    /**
     * Restores devices and calibration from a JSON file produced by
     * [exportSettingsJson]. Matches by hwId — devices that aren't yet
     * known to this install are created; existing devices are updated
     * in place. Calibration points are replaced (not merged) on a per-
     * device basis. Reading history is left alone.
     */
    suspend fun importSettingsJson(json: String): ImportResult {
        return try {
            val root = org.json.JSONObject(json)
            val schema = root.optString("schema")
            if (schema != "ispindle-plotter-settings") {
                return ImportResult(0, 0, "unrecognised file (schema=$schema)")
            }
            val devicesArr = root.getJSONArray("devices")
            var restored = 0
            for (i in 0 until devicesArr.length()) {
                val obj = devicesArr.getJSONObject(i)
                val hwId = obj.getInt("hwId")
                val reportedName = obj.optString("reportedName", "iSpindel$hwId")
                val userLabel = obj.optString("userLabel", reportedName)
                val calA = obj.optDouble("calA", 0.0)
                val calB = obj.optDouble("calB", 0.0)
                val calC = obj.optDouble("calC", 0.0)
                val calD = obj.optDouble("calD", 0.0)
                val calDegree = obj.optInt("calDegree", 0)
                val calRSquared = if (obj.has("calRSquared") && !obj.isNull("calRSquared"))
                    obj.getDouble("calRSquared") else null
                val pointsArr = obj.optJSONArray("calibrationPoints")
                val points = mutableListOf<CalibrationPoint>()
                if (pointsArr != null) {
                    for (j in 0 until pointsArr.length()) {
                        val p = pointsArr.getJSONObject(j)
                        points += CalibrationPoint(
                            deviceId = 0, // overwritten by repo
                            angle = p.getDouble("angle"),
                            knownSG = p.getDouble("knownSG"),
                            note = p.optString("note", ""),
                            createdMs = p.optLong("createdMs", System.currentTimeMillis()),
                            enabled = p.optBoolean("enabled", true)
                        )
                    }
                }
                repo.restoreDeviceSettings(
                    hwId = hwId,
                    reportedName = reportedName,
                    userLabel = userLabel,
                    calA = calA, calB = calB, calC = calC, calD = calD,
                    calDegree = calDegree, calRSquared = calRSquared,
                    calibrationPoints = points
                )
                restored++
            }
            ImportResult(inserted = restored, skipped = 0, error = null)
        } catch (e: Exception) {
            ImportResult(0, 0, "parse error: ${e.message}")
        }
    }

    /**
     * RFC4180 row parser: splits a CSV line on commas, handling quoted
     * fields with embedded commas, quotes (doubled `""`), and line breaks
     * within quotes. Used by [importReadingsCsv].
     */
    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i += 2
                }
                c == '"' -> { inQuotes = !inQuotes; i++ }
                !inQuotes && c == ',' -> { out += sb.toString(); sb.setLength(0); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out += sb.toString()
        return out
    }

    data class ImportResult(
        val inserted: Int,
        val skipped: Int,
        val error: String?
    )

    class Factory(private val app: IspindleApp) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(app.repository) as T
        }
    }
}
