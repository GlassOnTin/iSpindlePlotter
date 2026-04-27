package com.ispindle.plotter.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Pair<Double, Double>>,
    val format: (Double) -> String = { "%.2f".format(it) },
    /** Skip the polyline through the data points; render dots only. */
    val dotsOnly: Boolean = false,
    /**
     * When non-null, the polyline is drawn through these points instead
     * of [points]. Use for a smoothed trace over noisy raw data — the
     * dots still come from [points] so the underlying density is visible.
     */
    val smoothPoints: List<Pair<Double, Double>>? = null
)

/**
 * Right-edge axis that re-labels the same y-values via a transform.
 * Used for the SG → potential-alcohol display: same line, two scales.
 */
data class SecondaryAxis(
    val caption: String,
    val transform: (Double) -> Double,
    val format: (Double) -> String
)

/**
 * Optional model curve overlaid on the chart. The chart stretches its
 * axes (time outward, value downward for an SG decay) to make room for
 * the prediction so the user sees where the data is heading.
 *
 * The overlay is split into two segments at [dashAfterX] — solid up to
 * the last observed x, dashed beyond — so the extrapolated portion is
 * visually distinguishable from the in-sample fit.
 */
data class ChartOverlay(
    val color: Color,
    val sample: (Double) -> Double,
    val sampleCount: Int = 96,
    val extendXTo: Double? = null,
    val extendYDownTo: Double? = null,
    val dashAfterX: Double? = null,
    /**
     * Optional uncertainty band. When both samplers are non-null, a
     * translucent fill is drawn between [bandLow] and [bandHigh] across
     * the overlay's x range, behind the line itself.
     */
    val bandLow: ((Double) -> Double)? = null,
    val bandHigh: ((Double) -> Double)? = null,
    /**
     * Vertical spans (in chart x-units) to shade — used to mark detected
     * plateau regions. Drawn behind the data and band, full chart height.
     */
    val plateauSpans: List<PlateauSpan> = emptyList()
)

data class PlateauSpan(
    val xRange: ClosedFloatingPointRange<Double>,
    val label: String? = null
)

/**
 * Very small multi-series line chart. X is a shared axis across series
 * (typically time in ms). Each series gets its own Y axis scale, but only
 * the first series' Y axis is drawn — extra series are normalised to that
 * range, which is fine for trend visualisation of a single series at a time.
 *
 * For multiple concurrent metrics, render one LineChart per metric.
 */
@Composable
fun LineChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    xFormatter: (Double) -> String = { "%.0f".format(it) },
    /**
     * Treat the x-axis as epoch-ms timestamps. When true, ticks are placed
     * at "nice" clock-aligned offsets (every 1/2/3/6/12/24/48/… h, anchored
     * to local midnight) and labelled with date at midnight crossings,
     * `HH:mm` otherwise. The [xFormatter] is ignored in this mode.
     */
    xIsTimeMs: Boolean = false,
    secondaryAxis: SecondaryAxis? = null,
    overlay: ChartOverlay? = null,
    height: androidx.compose.ui.unit.Dp = 220.dp
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (series.points.size < 2) {
        Box(
            modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Not enough data yet (${series.points.size} pts)",
                color = labelColor
            )
        }
        return
    }

    val xs = series.points.map { it.first }
    val ys = series.points.map { it.second }
    val xMin = xs.min()
    // Extend the x-axis to the overlay's predicted finish so the user can
    // see the model line crossing the FG threshold rather than just
    // running off the right edge.
    val xMax = kotlin.math.max(xs.max(), overlay?.extendXTo ?: Double.NEGATIVE_INFINITY)
    // The uncertainty band can dip below the line's own extendYDownTo, so
    // pull the y range down to whichever floor is lower.
    val bandFloor = overlay?.bandLow?.let { low ->
        val xEnd = overlay.extendXTo ?: xs.max()
        low(xEnd)
    }
    val yMinRaw = kotlin.math.min(
        ys.min(),
        kotlin.math.min(overlay?.extendYDownTo ?: Double.POSITIVE_INFINITY,
                        bandFloor ?: Double.POSITIVE_INFINITY)
    )
    val yMaxRaw = ys.max()

    // Sticky autoscale: keep the y-axis still as new points come in.
    // Range only widens on out-of-range data, and re-snaps when the
    // visible data span has shrunk to a small fraction of the cached
    // range (e.g. after the user trims pre-fermentation noise).
    val rangeState = remember(series.label) { mutableStateOf<Pair<Double, Double>?>(null) }
    val (yMin, yMax) = stickyRange(rangeState.value, yMinRaw, yMaxRaw)
    LaunchedEffect(series.label, yMin, yMax) {
        if (rangeState.value != yMin to yMax) rangeState.value = yMin to yMax
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val paddingLeft = with(density) { 48.dp.toPx() }
        val paddingRight = with(density) { (if (secondaryAxis != null) 56.dp else 8.dp).toPx() }
        val paddingTop = with(density) { 8.dp.toPx() }
        val paddingBottom = with(density) { 28.dp.toPx() }

        val plotW = size.width - paddingLeft - paddingRight
        val plotH = size.height - paddingTop - paddingBottom
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        fun xToPx(x: Double): Float {
            val t = if (xMax == xMin) 0.5 else (x - xMin) / (xMax - xMin)
            return paddingLeft + (t.toFloat() * plotW)
        }
        fun yToPx(y: Double): Float {
            val t = if (yMax == yMin) 0.5 else (y - yMin) / (yMax - yMin)
            return paddingTop + plotH - (t.toFloat() * plotH)
        }

        // Gridlines + Y labels
        val yTicks = 5
        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        for (i in 0..yTicks) {
            val yVal = yMin + (yMax - yMin) * i / yTicks
            val py = yToPx(yVal)
            drawLine(
                color = axisColor.copy(alpha = 0.3f),
                start = Offset(paddingLeft, py),
                end = Offset(paddingLeft + plotW, py),
                strokeWidth = 1f,
                pathEffect = dashed
            )
            drawTextAt(
                textMeasurer, series.format(yVal), labelColor,
                x = 4f, y = py - 10f
            )
            secondaryAxis?.let { ax ->
                drawTextAt(
                    textMeasurer,
                    ax.format(ax.transform(yVal)),
                    labelColor,
                    x = paddingLeft + plotW + 4f,
                    y = py - 10f
                )
            }
        }
        secondaryAxis?.let { ax ->
            drawTextAt(
                textMeasurer,
                ax.caption,
                labelColor,
                x = paddingLeft + plotW + 4f,
                y = paddingTop - 6f
            )
        }

        // Axis
        drawLine(
            color = axisColor,
            start = Offset(paddingLeft, paddingTop),
            end = Offset(paddingLeft, paddingTop + plotH),
            strokeWidth = 1.5f
        )
        drawLine(
            color = axisColor,
            start = Offset(paddingLeft, paddingTop + plotH),
            end = Offset(paddingLeft + plotW, paddingTop + plotH),
            strokeWidth = 1.5f
        )

        // X labels — clock-aligned for time axes, start/mid/end otherwise.
        val (tickXs, tickStepH) = if (xIsTimeMs) clockAlignedTicks(xMin, xMax)
            else listOf(xMin, (xMin + xMax) / 2.0, xMax) to 0.0
        for (xVal in tickXs) {
            val px = xToPx(xVal)
            val label = if (xIsTimeMs) timeAxisLabel(xVal.toLong(), tickStepH)
                else xFormatter(xVal)
            val layout = textMeasurer.measure(
                text = label,
                style = androidx.compose.ui.text.TextStyle(color = labelColor, fontSize = 10.sp)
            )
            // Centre the label on its tick, but clamp so it never spills
            // off the canvas edges.
            val labelX = (px - layout.size.width / 2f)
                .coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(labelX, paddingTop + plotH + 6f))
        }

        // Optional model overlay drawn underneath the data points so the
        // dots stay visually crisp on top.
        overlay?.let { ov ->
            // Plateau shading: vertical bands across the full plot height,
            // drawn first so everything else lays on top. Each span also
            // gets a small label near the top of the plot when it's wide
            // enough to fit one.
            for (span in ov.plateauSpans) {
                val sx = xToPx(span.xRange.start.coerceIn(xMin, xMax))
                val ex = xToPx(span.xRange.endInclusive.coerceIn(xMin, xMax))
                if (ex <= sx) continue
                drawRect(
                    color = axisColor.copy(alpha = 0.10f),
                    topLeft = Offset(sx, paddingTop),
                    size = androidx.compose.ui.geometry.Size(ex - sx, plotH)
                )
                span.label?.let { label ->
                    val layout = textMeasurer.measure(
                        text = label,
                        style = androidx.compose.ui.text.TextStyle(
                            color = labelColor, fontSize = 10.sp
                        )
                    )
                    // Centre the label horizontally on the band. If the
                    // text is wider than the band it's allowed to spill
                    // into adjacent dot space — being centred over the
                    // shaded region preserves the visual association.
                    val bandW = ex - sx
                    drawText(
                        layout,
                        topLeft = Offset(
                            x = sx + (bandW - layout.size.width) / 2f,
                            y = paddingTop + 2f
                        )
                    )
                }
            }

            val xStart = kotlin.math.max(xMin, xs.min())
            val xEnd = kotlin.math.min(xMax, ov.extendXTo ?: xs.max())
            if (xEnd > xStart && ov.sampleCount > 1) {
                val step = (xEnd - xStart) / ov.sampleCount
                val dataMaxX = xs.max()
                val splitX = ov.dashAfterX ?: dataMaxX

                // Uncertainty band: filled translucent region between
                // bandLow and bandHigh, drawn first so the line and dots
                // stay on top.
                val low = ov.bandLow
                val high = ov.bandHigh
                if (low != null && high != null) {
                    val band = Path()
                    var started = false
                    for (i in 0..ov.sampleCount) {
                        val x = xStart + i * step
                        val y = high(x)
                        val px = xToPx(x); val py = yToPx(y)
                        if (!started) { band.moveTo(px, py); started = true }
                        else band.lineTo(px, py)
                    }
                    for (i in ov.sampleCount downTo 0) {
                        val x = xStart + i * step
                        val y = low(x)
                        band.lineTo(xToPx(x), yToPx(y))
                    }
                    band.close()
                    drawPath(band, ov.color.copy(alpha = 0.18f))
                }

                val solid = Path()
                val dashed = Path()
                var solidStarted = false
                var dashedStarted = false
                // Walk from xStart to xEnd. The boundary at splitX gets a
                // shared point on both paths so the visual seam doesn't
                // leave a gap.
                for (i in 0..ov.sampleCount) {
                    val x = xStart + i * step
                    val y = ov.sample(x)
                    val px = xToPx(x); val py = yToPx(y)
                    if (x <= splitX) {
                        if (!solidStarted) { solid.moveTo(px, py); solidStarted = true }
                        else solid.lineTo(px, py)
                    }
                    if (x >= splitX) {
                        if (!dashedStarted) { dashed.moveTo(px, py); dashedStarted = true }
                        else dashed.lineTo(px, py)
                    }
                }
                val strokeW = with(density) { 1.5.dp.toPx() }
                drawPath(solid, ov.color, style = Stroke(width = strokeW))
                drawPath(
                    dashed, ov.color,
                    style = Stroke(
                        width = strokeW,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(with(density) { 5.dp.toPx() }, with(density) { 4.dp.toPx() }),
                            0f
                        )
                    )
                )
            }
        }

        // Polyline through observed points (or the smoothed series when
        // provided) unless the caller asked for dots-only.
        if (!series.dotsOnly) {
            val polyPoints = series.smoothPoints ?: series.points
            val path = Path()
            polyPoints.forEachIndexed { idx, (x, y) ->
                val px = xToPx(x); val py = yToPx(y)
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path = path,
                color = series.color,
                style = Stroke(width = with(density) { 2.dp.toPx() })
            )
        }

        // Dots — slightly larger when this is a dots-only series so the
        // points stand out without the supporting polyline. When a
        // smoothed line is present the raw dots are drawn smaller and
        // semi-transparent so the trend dominates visually.
        val hasSmoothLine = !series.dotsOnly && series.smoothPoints != null
        val dotRadius = with(density) {
            (when {
                series.dotsOnly -> 2.0
                hasSmoothLine -> 1.2
                else -> 2.5
            }).dp.toPx()
        }
        val dotColor = if (hasSmoothLine) series.color.copy(alpha = 0.45f) else series.color
        series.points.forEach { (x, y) ->
            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = Offset(xToPx(x), yToPx(y))
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextAt(
    textMeasurer: TextMeasurer,
    text: String,
    color: Color,
    x: Float,
    y: Float
) {
    val layout = textMeasurer.measure(
        text = text,
        style = androidx.compose.ui.text.TextStyle(color = color, fontSize = 10.sp)
    )
    drawText(layout, topLeft = Offset(x, y))
}

/**
 * Stateless decision rule for the y-axis range.
 *
 * Returns one of:
 *   - `niceRange(rawMin, rawMax)` if no cached range yet.
 *   - A widened nice range when the new data spills out of `cached`.
 *   - A tighter `niceRange(rawMin, rawMax)` when the cached range is at
 *     least `RESNAP_RATIO` times wider than the data — the only way the
 *     axis ever narrows. Stops the chart from looking permanently
 *     zoomed-out after a Trim before… cleared most of the history.
 *   - `cached` itself otherwise, so successive renders agree even though
 *     the data has shifted slightly.
 *
 * Pure for testability. Compose pulls the state and feeds it in here.
 */
internal fun stickyRange(
    cached: Pair<Double, Double>?,
    rawMin: Double,
    rawMax: Double
): Pair<Double, Double> {
    if (cached == null) return niceRange(rawMin, rawMax)
    val outOfRange = rawMin < cached.first || rawMax > cached.second
    if (outOfRange) {
        return niceRange(
            kotlin.math.min(rawMin, cached.first),
            kotlin.math.max(rawMax, cached.second)
        )
    }
    val cachedSpan = cached.second - cached.first
    val dataSpan = rawMax - rawMin
    if (dataSpan > 0.0 && cachedSpan > RESNAP_RATIO * dataSpan) {
        return niceRange(rawMin, rawMax)
    }
    return cached
}

private const val RESNAP_RATIO = 2.5

private fun niceRange(min: Double, max: Double): Pair<Double, Double> {
    if (min == max) {
        val pad = if (min == 0.0) 1.0 else abs(min) * 0.1
        return (min - pad) to (max + pad)
    }
    val span = max - min
    val step = niceStep(span / 5.0)
    val niceMin = floor(min / step) * step
    val niceMax = ceil(max / step) * step
    return niceMin to niceMax
}

/**
 * Picks a "nice" clock-friendly hour spacing for x-axis ticks given a
 * total span in hours. The step is chosen so the implied tick count
 * (`span / step`) is as close to 4 as possible, snapped to a value
 * humans expect on a chart (every 30 min, every 6 h, every 12 h, every
 * day, every two days, weekly, fortnightly).
 *
 * "Closest to 4" rather than "smallest step ≥ span/4" — the latter
 * happily picks a 24 h step on a 50 h span and shows only 2 ticks; this
 * version picks 12 h there (≈ 4 ticks, with noon/midnight markers).
 */
internal fun niceHourStep(spanH: Double): Double {
    if (spanH <= 0.0 || !spanH.isFinite()) return 1.0
    val niceSteps = doubleArrayOf(
        0.25, 0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0, 48.0, 72.0, 168.0, 336.0
    )
    return niceSteps.minByOrNull { abs(spanH / it - 4.0) } ?: 1.0
}

/**
 * Generates clock-aligned tick positions across `[xMin, xMax]` (epoch
 * ms). Anchored to local midnight on or before `xMin`; step chosen by
 * [niceHourStep]. Returns the list of ticks in ascending order plus the
 * step in hours (used by the label formatter to decide whether `HH:mm`
 * or full `HH:mm:ss` is appropriate — currently only `HH:mm`).
 */
internal fun clockAlignedTicks(xMin: Double, xMax: Double): Pair<List<Double>, Double> {
    if (xMax <= xMin || !xMin.isFinite() || !xMax.isFinite()) return emptyList<Double>() to 1.0
    val spanH = (xMax - xMin) / 3_600_000.0
    val stepH = niceHourStep(spanH)
    val stepMs = (stepH * 3_600_000.0).toLong()
    if (stepMs <= 0L) return emptyList<Double>() to stepH

    val cal = Calendar.getInstance()
    cal.timeInMillis = xMin.toLong()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val midnightMs = cal.timeInMillis

    var k = ((xMin - midnightMs) / stepMs.toDouble()).let {
        if (it <= 0.0) 0L else ceil(it).toLong()
    }
    val ticks = mutableListOf<Double>()
    while (true) {
        val t = midnightMs + k * stepMs
        if (t > xMax) break
        if (t >= xMin) ticks += t.toDouble()
        k++
    }
    return ticks to stepH
}

/**
 * Smart time-axis label: bare date (`MM-dd`) at midnight crossings so
 * the day anchors stand out; bare time-of-day (`HH:mm`) at intra-day
 * ticks. Compact and unambiguous on a 320 dp wide chart.
 */
internal fun timeAxisLabel(ms: Long, stepH: Double): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    val isMidnight = cal[Calendar.HOUR_OF_DAY] == 0 && cal[Calendar.MINUTE] == 0
    val pattern = if (isMidnight) "MM-dd" else "HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val exp = floor(log10(raw))
    val base = 10.0.pow(exp)
    val frac = raw / base
    val niceFrac = when {
        frac < 1.5 -> 1.0
        frac < 3.0 -> 2.0
        frac < 7.0 -> 5.0
        else -> 10.0
    }
    return niceFrac * base
}
