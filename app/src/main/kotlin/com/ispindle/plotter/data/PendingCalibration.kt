package com.ispindle.plotter.data

/**
 * In-memory hand-off between the Configure flow and the ingest path.
 *
 * The auto-pair session reads the calibration polynomial from the iSpindle
 * over the AP, but there is no device row to attach it to until the freshly-
 * configured device joins home WiFi and POSTs its first reading. We park
 * the parsed coefficients here and let [Repository.ingest] adopt them on
 * the next matching POST.
 *
 * Single-slot is fine because the pair flow only runs against one device
 * at a time. The TTL prevents an abandoned pair from silently overwriting
 * a future, unrelated device's calibration much later.
 */
data class PendingCalibration(
    val coeffs: DoubleArray,
    val degree: Int,
    val rawExpression: String,
    val capturedMs: Long,
    val ttlMs: Long = 30 * 60 * 1000L
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - capturedMs < ttlMs

    override fun equals(other: Any?): Boolean = other is PendingCalibration &&
            coeffs.contentEquals(other.coeffs) &&
            degree == other.degree &&
            rawExpression == other.rawExpression &&
            capturedMs == other.capturedMs &&
            ttlMs == other.ttlMs

    override fun hashCode(): Int {
        var r = coeffs.contentHashCode()
        r = 31 * r + degree
        r = 31 * r + rawExpression.hashCode()
        r = 31 * r + capturedMs.hashCode()
        r = 31 * r + ttlMs.hashCode()
        return r
    }
}
