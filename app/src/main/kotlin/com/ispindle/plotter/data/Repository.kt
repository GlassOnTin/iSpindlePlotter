package com.ispindle.plotter.data

import com.ispindle.plotter.calibration.Polynomial
import java.util.concurrent.atomic.AtomicReference

/**
 * Single mediator for ingesting a payload, upserting the device row,
 * evaluating the saved calibration polynomial, and inserting the reading.
 */
class Repository(
    private val deviceDao: DeviceDao,
    private val readingDao: ReadingDao,
    private val calibrationDao: CalibrationDao,
    private val pendingCalibration: AtomicReference<PendingCalibration?> =
        AtomicReference(null)
) {
    val devices = deviceDao.observeAll()

    fun observeDevice(id: Long) = deviceDao.observeById(id)
    fun observeReadings(deviceId: Long) = readingDao.observeForDevice(deviceId)
    fun observeLatestReading(deviceId: Long) = readingDao.observeLatestForDevice(deviceId)
    fun observeLatestAny() = readingDao.observeLatestAny()
    fun observeCalibration(deviceId: Long) = calibrationDao.observeForDevice(deviceId)

    suspend fun deviceById(id: Long) = deviceDao.findById(id)

    suspend fun ingest(payload: IspindlePayload, receivedMs: Long, remoteIp: String? = null): Long {
        val hwId = payload.id ?: payload.name.hashCode()
        val reportedName = payload.name ?: "iSpindel$hwId"

        var device = deviceDao.findByHwId(hwId) ?: run {
            val newId = deviceDao.insert(
                Device(
                    hwId = hwId,
                    reportedName = reportedName,
                    userLabel = reportedName,
                    firstSeenMs = receivedMs,
                    lastSeenMs = receivedMs,
                    lastSeenIp = remoteIp
                )
            )
            deviceDao.findById(newId)!!
        }

        // Adopt a pending calibration import (set by the Configure flow)
        // before computing gravity for this reading. Skip if the user has
        // already calibrated this device by hand or the hand-off has
        // expired — in the expired case, drop it so it can't catch a
        // future, unrelated device.
        val pending = pendingCalibration.get()
        if (pending != null && pending.isFresh(receivedMs) && device.calDegree == 0) {
            if (pendingCalibration.compareAndSet(pending, null)) {
                deviceDao.updateCalibration(
                    id = device.id,
                    a = pending.coeffs.getOrElse(0) { 0.0 },
                    b = pending.coeffs.getOrElse(1) { 0.0 },
                    c = pending.coeffs.getOrElse(2) { 0.0 },
                    d = pending.coeffs.getOrElse(3) { 0.0 },
                    degree = pending.degree,
                    r2 = null
                )
                device = deviceDao.findById(device.id)!!
            }
        } else if (pending != null && !pending.isFresh(receivedMs)) {
            pendingCalibration.compareAndSet(pending, null)
        }

        deviceDao.touchWithIp(device.id, receivedMs, remoteIp ?: device.lastSeenIp)

        val tempC = when (payload.tempUnits.uppercase()) {
            "F" -> (payload.temperature - 32.0) * 5.0 / 9.0
            "K" -> payload.temperature - 273.15
            else -> payload.temperature
        }

        val computedGravity = if (device.calDegree > 0) {
            Polynomial.fromDevice(device).eval(payload.angle)
        } else null

        val reading = Reading(
            deviceId = device.id,
            timestampMs = receivedMs,
            angle = payload.angle,
            temperatureC = tempC,
            batteryV = payload.battery,
            rssi = payload.rssi,
            intervalS = payload.interval,
            reportedGravity = payload.gravity,
            computedGravity = computedGravity,
            tempUnits = payload.tempUnits
        )
        return readingDao.insert(reading)
    }

    suspend fun addCalibrationPoint(deviceId: Long, angle: Double, knownSG: Double, note: String) {
        calibrationDao.insert(
            CalibrationPoint(
                deviceId = deviceId,
                angle = angle,
                knownSG = knownSG,
                note = note,
                createdMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteCalibrationPoint(point: CalibrationPoint) = calibrationDao.delete(point)
    suspend fun updateCalibrationPoint(point: CalibrationPoint) = calibrationDao.update(point)

    suspend fun saveFittedPolynomial(deviceId: Long, poly: Polynomial, r2: Double?) {
        deviceDao.updateCalibration(
            id = deviceId,
            a = poly.coeff(0),
            b = poly.coeff(1),
            c = poly.coeff(2),
            d = poly.coeff(3),
            degree = poly.degree,
            r2 = r2
        )
    }

    suspend fun clearCalibration(deviceId: Long) {
        deviceDao.updateCalibration(deviceId, 0.0, 0.0, 0.0, 0.0, 0, null)
    }

    suspend fun renameDevice(deviceId: Long, label: String) {
        val d = deviceDao.findById(deviceId) ?: return
        deviceDao.update(d.copy(userLabel = label))
    }

    /** Removes the device. ON DELETE CASCADE drops its readings and calibration points. */
    suspend fun deleteDevice(deviceId: Long) = deviceDao.deleteById(deviceId)

    suspend fun readingCount(deviceId: Long) = readingDao.countForDevice(deviceId)

    suspend fun readingCountBefore(deviceId: Long, ts: Long) =
        readingDao.countBefore(deviceId, ts)

    suspend fun deleteReadingsBefore(deviceId: Long, ts: Long): Int =
        readingDao.deleteBefore(deviceId, ts)

    /** Snapshot for export. Ordered ASC so CSV consumers see chronological rows. */
    suspend fun readingsSnapshot(deviceId: Long): List<Reading> =
        readingDao.listForDevice(deviceId)

    suspend fun enabledCalibrationPoints(deviceId: Long) =
        calibrationDao.enabledForDevice(deviceId)

    /**
     * Bulk-insert readings into [deviceId], skipping any whose timestamp
     * already has a row for this device. Returns (inserted, skipped).
     * Caller is responsible for parsing the source format.
     */
    suspend fun importReadings(deviceId: Long, readings: List<Reading>): Pair<Int, Int> {
        val existing = readingDao.timestampsFor(deviceId).toHashSet()
        var inserted = 0
        var skipped = 0
        for (r in readings) {
            if (r.timestampMs in existing) {
                skipped++
                continue
            }
            // Force the deviceId to the target device — protects against an
            // import file that carries the original Room id from a different
            // install.
            readingDao.insert(r.copy(id = 0, deviceId = deviceId))
            existing += r.timestampMs
            inserted++
        }
        if (inserted > 0) {
            // Refresh lastSeenMs so the device sorts correctly post-import.
            readings.maxOfOrNull { it.timestampMs }?.let { latest ->
                deviceDao.touch(deviceId, latest)
            }
        }
        return inserted to skipped
    }

    /** Snapshot of every device for settings backup. */
    suspend fun allDevices(): List<Device> = deviceDao.listAll()

    /** Snapshot of all calibration points for a device. */
    suspend fun calibrationSnapshot(deviceId: Long): List<CalibrationPoint> =
        calibrationDao.listForDevice(deviceId)

    /**
     * Restore one device's settings (label, calibration polynomial, raw
     * calibration points). Looks up by `hwId` — creates a new device row
     * if none exists, otherwise updates the existing row's user label and
     * polynomial coefficients. Calibration points get cleared and replaced.
     *
     * Readings are NOT touched — settings backup is intentionally
     * lightweight; CSV import is the way to restore reading data.
     */
    suspend fun restoreDeviceSettings(
        hwId: Int,
        reportedName: String,
        userLabel: String,
        calA: Double, calB: Double, calC: Double, calD: Double,
        calDegree: Int, calRSquared: Double?,
        calibrationPoints: List<CalibrationPoint>
    ) {
        val now = System.currentTimeMillis()
        val existing = deviceDao.findByHwId(hwId)
        val deviceId = if (existing == null) {
            deviceDao.insert(
                Device(
                    hwId = hwId,
                    reportedName = reportedName,
                    userLabel = userLabel,
                    firstSeenMs = now,
                    lastSeenMs = now,
                    calA = calA, calB = calB, calC = calC, calD = calD,
                    calDegree = calDegree, calRSquared = calRSquared
                )
            )
        } else {
            deviceDao.update(
                existing.copy(
                    userLabel = userLabel,
                    calA = calA, calB = calB, calC = calC, calD = calD,
                    calDegree = calDegree, calRSquared = calRSquared
                )
            )
            existing.id
        }
        // Replace calibration points: simplest restore semantics, and the
        // user's intent for "restore" is "make this device's settings
        // match the backup" not "merge".
        for (p in calibrationDao.listForDevice(deviceId)) {
            calibrationDao.delete(p)
        }
        for (p in calibrationPoints) {
            calibrationDao.insert(p.copy(id = 0, deviceId = deviceId))
        }
    }
}
