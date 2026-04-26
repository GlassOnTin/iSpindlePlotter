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

    class Factory(private val app: IspindleApp) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(app.repository) as T
        }
    }
}
