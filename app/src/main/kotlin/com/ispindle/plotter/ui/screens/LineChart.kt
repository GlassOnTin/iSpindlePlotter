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
    val dotsOnly: Boolean = false
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
    val dashAfterX: Double? = null
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
    val yMinRaw = kotlin.math.min(ys.min(), overlay?.extendYDownTo ?: Double.POSITIVE_INFINITY)
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

        // X labels at start / mid / end
        listOf(xMin, (xMin + xMax) / 2.0, xMax).forEachIndexed { i, xVal ->
            val px = when (i) {
                0 -> paddingLeft
                1 -> paddingLeft + plotW / 2f
                else -> paddingLeft + plotW
            }
            drawTextAt(
                textMeasurer, xFormatter(xVal), labelColor,
                x = px - 30f, y = paddingTop + plotH + 6f
            )
        }

        // Optional model overlay drawn underneath the data points so the
        // dots stay visually crisp on top.
        overlay?.let { ov ->
            val xStart = kotlin.math.max(xMin, xs.min())
            val xEnd = kotlin.math.min(xMax, ov.extendXTo ?: xs.max())
            if (xEnd > xStart && ov.sampleCount > 1) {
                val step = (xEnd - xStart) / ov.sampleCount
                val dataMaxX = xs.max()
                val splitX = ov.dashAfterX ?: dataMaxX
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

        // Polyline through observed points unless the caller asked for dots-only.
        if (!series.dotsOnly) {
            val path = Path()
            series.points.forEachIndexed { idx, (x, y) ->
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
        // points stand out without the supporting polyline.
        val dotRadius = with(density) { (if (series.dotsOnly) 2.0 else 2.5).dp.toPx() }
        series.points.forEach { (x, y) ->
            drawCircle(
                color = series.color,
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
