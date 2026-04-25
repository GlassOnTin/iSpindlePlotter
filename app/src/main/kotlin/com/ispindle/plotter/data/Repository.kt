package com.ispindle.plotter.data

import com.ispindle.plotter.calibration.Polynomial

/**
 * Single mediator for ingesting a payload, upserting the device row,
 * evaluating the saved calibration polynomial, and inserting the reading.
 */
class Repository(
    private val deviceDao: DeviceDao,
    private val readingDao: ReadingDao,
    private val calibrationDao: CalibrationDao
) {
    val devices = deviceDao.observeAll()

    fun observeDevice(id: Long) = deviceDao.observeById(id)
    fun observeReadings(deviceId: Long) = readingDao.observeForDevice(deviceId)
    fun observeLatestReading(deviceId: Long) = readingDao.observeLatestForDevice(deviceId)
    fun observeLatestAny() = readingDao.observeLatestAny()
    fun observeCalibration(deviceId: Long) = calibrationDao.observeForDevice(deviceId)

    suspend fun deviceById(id: Long) = deviceDao.findById(id)

    suspend fun ingest(payload: IspindlePayload, receivedMs: Long): Long {
        val hwId = payload.id ?: payload.name.hashCode()
        val reportedName = payload.name ?: "iSpindel$hwId"

        val device = deviceDao.findByHwId(hwId) ?: run {
            val newId = deviceDao.insert(
                Device(
                    hwId = hwId,
                    reportedName = reportedName,
                    userLabel = reportedName,
                    firstSeenMs = receivedMs,
                    lastSeenMs = receivedMs
                )
            )
            deviceDao.findById(newId)!!
        }

        deviceDao.touch(device.id, receivedMs)

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

    suspend fun enabledCalibrationPoints(deviceId: Long) =
        calibrationDao.enabledForDevice(deviceId)
}
