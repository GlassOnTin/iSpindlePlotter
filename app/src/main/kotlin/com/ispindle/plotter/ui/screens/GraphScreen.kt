package com.ispindle.plotter.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.R
import com.ispindle.plotter.analysis.Fermentation
import com.ispindle.plotter.analysis.FermentSegment
import com.ispindle.plotter.analysis.FermentSegmenter
import com.ispindle.plotter.analysis.Fits
import com.ispindle.plotter.analysis.AttenuationFit
import com.ispindle.plotter.data.Reading
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

enum class TimeWindow(val labelResId: Int, val hours: Int?) {
    H24(R.string.graph_time_window_24h, 24),
    D7(R.string.graph_time_window_7d, 24 * 7),
    D30(R.string.graph_time_window_30d, 24 * 30),
    ALL(R.string.graph_time_window_all, null)
}

/**
 * State-of-charge zones for a single 18650 lithium-ion cell. These bands
 * are painted behind the battery chart so the user reads where the cell
 * sits not just in mV but in lifecycle terms — "above 4.0 V you're full,
 * below 3.4 V you should be charging, below 3.2 V the cell is being
 * actively damaged and the iSpindle will cut out shortly".
 *
 * Voltages are conventional Li-ion thresholds:
 *   3.40–4.20 V  healthy operating range (green)
 *   3.20–3.40 V  low / approaching cutoff (yellow)
 *   3.00–3.20 V  critical / cell damage zone (red)
 */
private val LithiumZones = listOf(
    YBand(3.40..4.20, Color(0xFF4CAF50).copy(alpha = 0.18f)),
    YBand(3.20..3.40, Color(0xFFFFC107).copy(alpha = 0.18f)),
    YBand(3.00..3.20, Color(0xFFF44336).copy(alpha = 0.20f))
)

@Composable
fun GraphScreen(
    vm: MainViewModel,
    deviceId: Long,
    padding: PaddingValues
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by remember(deviceId) { vm.deviceFlow(deviceId) }.collectAsState(initial = null)
    val readings by remember(deviceId) { vm.readingsFor(deviceId).map { it } }
        .collectAsState(initial = emptyList())

    // Detect contiguous ferment episodes across the whole readings stream.
    // When at least one ferment is detected we default the chart to the
    // most recent one, since that's the brew the user is actively
    // watching; "All" falls back to the TimeWindow filter.
    val segments = remember(readings) {
        val sgList = readings.mapNotNull { r ->
            val sg = r.computedGravity ?: r.reportedGravity
            if (sg != null && sg > 0.0) r.timestampMs to sg else null
        }
        if (sgList.size < 6) emptyList()
        else FermentSegmenter.detect(
            timestamps = LongArray(sgList.size) { sgList[it].first },
            sgs = DoubleArray(sgList.size) { sgList[it].second }
        )
    }
    var selectedSegmentIdx by remember(segments.size) {
        mutableStateOf<Int?>(if (segments.isEmpty()) null else segments.lastIndex)
    }

    var window by remember { mutableStateOf(TimeWindow.D7) }
    val now = System.currentTimeMillis()
    val scoped = run {
        val sel = selectedSegmentIdx
        if (sel != null && sel in segments.indices) {
            val seg = segments[sel]
            readings.filter { it.timestampMs in seg.startMs..seg.endMs }
        } else {
            val cutoff = window.hours?.let { now - it * 3_600_000L } ?: Long.MIN_VALUE
            readings.filter { it.timestampMs >= cutoff }
        }
    }

    var showTrim by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = vm.exportReadingsCsv(deviceId)
            if (csv.isEmpty()) {
                toast = ctx.getString(R.string.graph_nothing_to_export)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast = if (ok) ctx.getString(R.string.graph_exported_n_readings, readings.size)
                    else ctx.getString(R.string.graph_export_failed)
        }
    }
    val csvImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            if (csv == null) {
                toast = ctx.getString(R.string.graph_import_read_failed)
                return@launch
            }
            val result = vm.importReadingsCsv(deviceId, csv)
            toast = if (result.error != null)
                ctx.getString(R.string.graph_import_failed, result.error)
            else
                ctx.getString(R.string.graph_import_succeeded, result.inserted, result.skipped)
        }
    }

    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val xFmt: (Double) -> String = { ms -> dateFmt.format(Date(ms.toLong())) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            device?.userLabel ?: "Device",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(R.string.graph_readings_in_window, scoped.size, readings.size),
            style = MaterialTheme.typography.bodySmall
        )
        if (segments.isNotEmpty()) {
            FermentNavRow(
                segments = segments,
                selectedIdx = selectedSegmentIdx,
                onSelect = { selectedSegmentIdx = it }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeWindow.entries.forEach { w ->
                FilterChip(
                    selected = window == w && selectedSegmentIdx == null,
                    onClick = {
                        window = w
                        // Picking a TimeWindow chip implies "show me a
                        // time-bounded slice" rather than a specific
                        // ferment — switch to All so the chip's effect
                        // is actually visible.
                        selectedSegmentIdx = null
                    },
                    label = { Text(stringResource(w.labelResId)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showTrim = true },
                enabled = readings.isNotEmpty()
            ) { Text(stringResource(R.string.graph_btn_trim_before)) }
            OutlinedButton(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val safeLabel = (device?.userLabel ?: "device")
                        .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    csvLauncher.launch("ispindle-${safeLabel}-${stamp}.csv")
                },
                enabled = readings.isNotEmpty()
            ) { Text(stringResource(R.string.graph_btn_export_csv)) }
            OutlinedButton(
                onClick = {
                    // Some Android device file pickers refuse the strict
                    // "text/csv" filter when the source file's MIME is
                    // text/comma-separated-values or text/plain. Accept any
                    // text-ish file and let the parser reject what it can't
                    // read.
                    csvImportLauncher.launch(arrayOf("text/csv", "text/*", "*/*"))
                }
            ) { Text(stringResource(R.string.graph_btn_import_csv)) }
        }
        toast?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary)
        }

        if (scoped.isEmpty()) {
            Text(stringResource(R.string.graph_no_readings_in_window))
            return@Column
        }

        // SG with the model overlay leads — it's the headline chart for
        // brewers actively watching a ferment.
        val sgPoints = scoped.mapNotNull { r ->
            val sg = r.computedGravity ?: r.reportedGravity
            if (sg != null && sg > 0.0) r.timestampMs.toDouble() to sg else null
        }
        val calR2 = device?.calRSquared
        val sgOverlay = remember(sgPoints, calR2) { buildSgOverlay(ctx, sgPoints, calR2) }
        MetricCard(
            title = stringResource(R.string.graph_section_specific_gravity),
            series = ChartSeries(
                label = "sg",
                color = Color(0xFF3E7B51),
                points = sgPoints,
                format = { "%.4f".format(it) },
                dotsOnly = true
            ),
            xFormatter = xFmt,
            emptyHint = stringResource(R.string.graph_sg_no_data_hint),
            secondaryAxis = SecondaryAxis(
                // Triple-scale-hydrometer rule of thumb: every 0.001 SG point
                // above 1.000 ≈ 0.13125 % alcohol-by-volume potential.
                transform = { sg -> (sg - 1.0) * 131.25 },
                format = { pa -> "%.1f%%".format(pa) }
            ),
            overlay = sgOverlay
        )
        SgEstimateLine(scoped, sgPoints, calR2)

        MetricCard(
            title = stringResource(R.string.graph_section_tilt_angle),
            series = ChartSeries(
                label = "angle",
                color = Color(0xFF2D5F9E),
                points = scoped.map { it.timestampMs.toDouble() to it.angle },
                format = { "%.1f°".format(it) }
            ),
            xFormatter = xFmt
        )

        MetricCard(
            title = stringResource(R.string.graph_section_temperature),
            series = ChartSeries(
                label = "temp",
                color = Color(0xFFB84A47),
                points = scoped.map { it.timestampMs.toDouble() to it.temperatureC },
                format = { "%.1f°C".format(it) }
            ),
            xFormatter = xFmt
        )

        val rawBatteryPoints = scoped.map { it.timestampMs.toDouble() to it.batteryV }
        // The cell really does sit at each ADC-quantised voltage step
        // for hours at a time, so any moving-window smoother leaves
        // visible plateaus and abrupt transitions. Fit a model curve
        // instead — a straight line at this stage of discharge is a
        // well-determined fit; the rendered line is what the OLS thinks
        // the underlying voltage trajectory is, ignoring quantisation
        // and ADC noise entirely. Raw points still show as faint dots
        // so the noise envelope is visible.
        val batteryFit = remember(rawBatteryPoints) {
            if (rawBatteryPoints.size < 2) null
            else {
                val tStart = rawBatteryPoints.first().first
                val xs = DoubleArray(rawBatteryPoints.size) {
                    (rawBatteryPoints[it].first - tStart) / 3_600_000.0
                }
                val ys = DoubleArray(rawBatteryPoints.size) { rawBatteryPoints[it].second }
                Fits.fitLinear(xs, ys)?.let { it to tStart }
            }
        }
        val modelBatteryPoints = batteryFit?.let { (fit, tStart) ->
            val first = rawBatteryPoints.first().first
            val last = rawBatteryPoints.last().first
            listOf(
                first to fit.predict((first - tStart) / 3_600_000.0),
                last to fit.predict((last - tStart) / 3_600_000.0)
            )
        }
        MetricCard(
            title = stringResource(R.string.graph_section_battery),
            series = ChartSeries(
                label = "battery",
                color = Color(0xFF7A5C8A),
                points = rawBatteryPoints,
                smoothPoints = modelBatteryPoints,
                format = { "%.2fV".format(it) },
                // Half an ADC quantum (~10 mV LSB on the iSpindle) of
                // Gaussian jitter breaks up the visible 4.13/4.14/4.15 V
                // bands in the dot cloud without shifting the apparent
                // voltage. Deterministic per (x, y) so the cloud is
                // stable across recompositions.
                dotJitterY = 0.005
            ),
            xFormatter = xFmt,
            // Lithium-ion 18650 (the cell the iSpindle uses): 4.20 V is
            // full charge, 3.00 V is the absolute minimum below which
            // the cell takes permanent damage. Lock the y-axis to that
            // window so the user sees where the cell sits in absolute
            // terms, not in autoscaled-to-data terms.
            fixedYRange = 3.00..4.20,
            yBands = LithiumZones,
            yTickStep = 0.2
        )
        BatteryEstimateLine(scoped, batteryFit?.first)
    }

    if (showTrim) {
        TrimBeforeDialog(
            vm = vm,
            deviceId = deviceId,
            onDismiss = { showTrim = false },
            onTrimmed = { count ->
                showTrim = false
                toast = if (count > 0) ctx.getString(R.string.graph_deleted_n_readings, count)
                        else ctx.getString(R.string.graph_trimmed_nothing)
            }
        )
    }
}

@Composable
private fun FermentNavRow(
    segments: List<FermentSegment>,
    selectedIdx: Int?,
    onSelect: (Int?) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    val seg = selectedIdx?.takeIf { it in segments.indices }?.let { segments[it] }
    val label = if (seg == null) {
        stringResource(R.string.graph_all_ferments, segments.size)
    } else {
        val startStr = dateFmt.format(Date(seg.startMs))
        val endStr = dateFmt.format(Date(seg.endMs))
        val drop = seg.ogObserved - seg.fgObserved
        stringResource(
            R.string.graph_ferment_label,
            selectedIdx!! + 1,
            segments.size,
            startStr,
            endStr,
            "%.3f".format(drop)
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { selectedIdx?.let { if (it > 0) onSelect(it - 1) } },
            enabled = selectedIdx != null && selectedIdx > 0,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("◀") }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { selectedIdx?.let { if (it < segments.lastIndex) onSelect(it + 1) } },
            enabled = selectedIdx != null && selectedIdx < segments.lastIndex,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("▶") }
        FilterChip(
            selected = selectedIdx == null,
            onClick = {
                onSelect(if (selectedIdx == null) segments.lastIndex else null)
            },
            label = { Text(stringResource(R.string.graph_all_chip_label)) }
        )
    }
}

@Composable
private fun SgEstimateLine(
    readings: List<Reading>,
    sgPoints: List<Pair<Double, Double>>,
    calRSquared: Double? = null
) {
    if (sgPoints.size < 6) return
    val ctx = LocalContext.current
    val tStartMs = sgPoints.first().first
    val xs = DoubleArray(sgPoints.size) { (sgPoints[it].first - tStartMs) / 3_600_000.0 }
    val ys = DoubleArray(sgPoints.size) { sgPoints[it].second }
    val state = remember(sgPoints, calRSquared) { Fermentation.analyse(xs, ys, calRSquared) }

    val phaseActive = stringResource(R.string.graph_phase_active)
    val phaseSlowing = stringResource(R.string.graph_phase_slowing)
    val phaseConditioning = stringResource(R.string.graph_phase_conditioning)
    val phaseLag = stringResource(R.string.graph_phase_lag)
    val phaseStuck = stringResource(R.string.graph_phase_stuck)

    when (state) {
        is Fermentation.State.Insufficient -> EstimateText(
            stringResource(R.string.graph_sg_insufficient)
        )
        is Fermentation.State.Lag -> EstimateText(
            stringResource(
                R.string.graph_sg_lag_phase,
                "%.4f".format(state.og),
                "%.4f".format(state.current),
                "%.1f".format(state.durationHours)
            )
        )
        is Fermentation.State.Active -> {
            val abvNow = (state.og - state.current) * 131.25
            val abvAtFg = (state.og - state.predictedFg) * 131.25
            EstimateText(buildString {
                append("$phaseActive · ")
                append(ogToFg(ctx, state.og, state.predictedFg))
                state.predictedFgSigma?.let { append(formatFgSigma(it)) }
                append(nowFragment(ctx, state.current))
                append(rateFragment(ctx, state.ratePerHour))
                append(etaFragment(ctx, state.etaToFinishHours))
                appendEtaCredible(ctx, state.etaCredibleLowHours, state.etaCredibleHighHours)
                append(abvFragment(abvNow, abvAtFg))
                append(" · ${state.source.label(ctx)}")
                appendMidPlateau(ctx, state.plateaus)
                state.measurementSigma?.let {
                    append(ctx.getString(R.string.graph_sg_meas_sigma, "%.4f".format(it)))
                }
            })
        }
        is Fermentation.State.Slowing -> {
            val abvNow = (state.og - state.current) * 131.25
            val abvAtFg = (state.og - state.predictedFg) * 131.25
            EstimateText(buildString {
                append("$phaseSlowing · ")
                append(ogToFg(ctx, state.og, state.predictedFg))
                state.predictedFgSigma?.let { append(formatFgSigma(it)) }
                append(nowFragment(ctx, state.current))
                append(rateFragment(ctx, state.ratePerHour))
                append(etaFragment(ctx, state.etaToFinishHours))
                appendEtaCredible(ctx, state.etaCredibleLowHours, state.etaCredibleHighHours)
                append(abvFragment(abvNow, abvAtFg))
                append(" · ${state.source.label(ctx)}")
                appendMidPlateau(ctx, state.plateaus)
                state.measurementSigma?.let {
                    append(ctx.getString(R.string.graph_sg_meas_sigma, "%.4f".format(it)))
                }
            })
        }
        is Fermentation.State.Conditioning -> {
            val abv = (state.og - state.fg) * 131.25
            EstimateText(
                stringResource(
                    R.string.graph_sg_conditioning,
                    "%.4f".format(state.og),
                    "%.4f".format(state.fg),
                    "%.1f".format(abv)
                )
            )
        }
        is Fermentation.State.Stuck -> {
            val abvNow = (state.og - state.current) * 131.25
            EstimateText(
                stringResource(
                    R.string.graph_sg_stuck,
                    "%.4f".format(state.og),
                    "%.4f".format(state.current),
                    "%.4f".format(state.expectedFg),
                    "%.1f".format(state.flatHours),
                    "%.1f".format(abvNow)
                ),
                isError = true
            )
        }
    }

    // Stage-specific brewing guidance — what the brewer should be
    // thinking about right now given the current phase.
    val guidanceRes: Int? = when (state) {
        is Fermentation.State.Insufficient -> R.string.graph_guidance_insufficient
        is Fermentation.State.Lag -> R.string.graph_guidance_lag
        is Fermentation.State.Active -> R.string.graph_guidance_active
        is Fermentation.State.Slowing -> R.string.graph_guidance_slowing
        is Fermentation.State.Conditioning -> R.string.graph_guidance_conditioning
        is Fermentation.State.Stuck -> R.string.graph_guidance_stuck
    }
    if (guidanceRes != null) {
        Text(
            text = stringResource(guidanceRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }

    // Academic reference for the parametric model used in OG/FG/ETA
    // inference. Shown as a small caption under the guidance so anyone
    // reading the chart can find the model in the literature.
    if (state is Fermentation.State.Active ||
        state is Fermentation.State.Slowing ||
        state is Fermentation.State.Conditioning
    ) {
        Text(
            text = stringResource(R.string.graph_model_prefix) + AttenuationFit.ReferenceCitation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

// ── String fragment helpers (non-Composable, take Context for i18n) ─────────

private fun ogToFg(ctx: Context, og: Double, fg: Double): String =
    ctx.getString(R.string.graph_sg_og_to_fg, "%.4f".format(og), "%.4f".format(fg))

private fun nowFragment(ctx: Context, current: Double): String =
    ctx.getString(R.string.graph_sg_now_fragment, "%.4f".format(current))

private fun rateFragment(ctx: Context, rate: Double): String =
    ctx.getString(R.string.graph_sg_rate_fragment, "%.3f".format(rate))

private fun etaFragment(ctx: Context, hours: Double?): String =
    ctx.getString(R.string.graph_sg_eta_fragment, formatHoursAhead(ctx, hours))

private fun abvFragment(abvNow: Double, abvAtFg: Double): String =
    " · ABV %.1f%% → %.1f%%".format(abvNow, abvAtFg)

/**
 * Builds a model overlay for the SG chart:
 *   - extends the time axis to the predicted finish (now + ETA)
 *   - extends the SG axis down to predicted FG so the curve fits in
 *   - samples the fit (logistic where trusted, otherwise linear from the
 *     latest reading toward predicted FG)
 *   - dashes the segment past the latest data so the extrapolated portion
 *     is visually distinct.
 *
 * Returns null when there's nothing useful to overlay (Lag with no rate,
 * Insufficient, or Stuck/Complete where the data already covers the story).
 */
private fun buildSgOverlay(
    ctx: Context,
    sgPoints: List<Pair<Double, Double>>,
    calRSquared: Double? = null
): ChartOverlay? {
    if (sgPoints.size < 6) return null
    val tStartMs = sgPoints.first().first
    val xs = DoubleArray(sgPoints.size) { (sgPoints[it].first - tStartMs) / 3_600_000.0 }
    val ys = DoubleArray(sgPoints.size) { sgPoints[it].second }
    val state = Fermentation.analyse(xs, ys, calRSquared)
    val nowH = xs.last()
    val nowMs = sgPoints.last().first

    fun overlayMs(
        predict: (Double) -> Double,
        etaH: Double,
        fg: Double,
        bandLowFn: ((Double) -> Double)?,
        bandHighFn: ((Double) -> Double)?,
        floor: Double,
        plateaus: List<com.ispindle.plotter.analysis.Plateau>
    ): ChartOverlay {
        val finishMs = nowMs + etaH * 3_600_000.0
        return ChartOverlay(
            color = androidx.compose.ui.graphics.Color(0xFFD1495B),
            sample = { msX ->
                val hours = (msX - tStartMs) / 3_600_000.0
                predict(hours)
            },
            sampleCount = 96,
            extendXTo = finishMs,
            extendYDownTo = floor,
            dashAfterX = nowMs,
            bandLow = bandLowFn,
            bandHigh = bandHighFn,
            plateauSpans = plateaus.map {
                PlateauSpan(
                    xRange = (tStartMs + it.startH * 3_600_000.0)..
                        (tStartMs + it.endH * 3_600_000.0),
                    label = plateauLabel(ctx, it)
                )
            }
        )
    }

    return when (state) {
        is Fermentation.State.Active -> {
            val fg = state.predictedFg
            val eta = state.etaToFinishHours
            if (eta != null) {
                val (predict, low, high, floor) =
                    predictorWithBand(state.source, xs, ys, state, fg, nowH, tStartMs, nowMs, eta)
                overlayMs(predict, eta, fg, low, high, floor, state.plateaus)
            } else {
                // No future to extrapolate (data already at/past FG) — show
                // the in-sample logistic fit so the user can still read the
                // ferment shape.
                buildInSampleOverlay(ctx, xs, ys, fg, tStartMs, state.plateaus)
            }
        }
        is Fermentation.State.Slowing -> {
            val fg = state.predictedFg
            val eta = state.etaToFinishHours
            if (eta != null) {
                val (predict, low, high, floor) =
                    predictorWithBand(state.source, xs, ys, state, fg, nowH, tStartMs, nowMs, eta)
                overlayMs(predict, eta, fg, low, high, floor, state.plateaus)
            } else {
                buildInSampleOverlay(ctx, xs, ys, fg, tStartMs, state.plateaus)
            }
        }
        is Fermentation.State.Conditioning -> {
            // The overlay isn't only forward-prediction — it's also a way
            // to read the shape of the ferment after the fact (lag length,
            // fastest-attenuation point, residual-vs-model). Refit the
            // logistic on the full record and draw the curve through the
            // data window only — no future extension, no dashed segment.
            buildInSampleOverlay(ctx, xs, ys, state.fg, tStartMs, state.plateaus)
        }
        else -> null
    }
}

/**
 * Overlay variant for "we're already at FG" states (Complete, or a
 * Slowing/Active where the recent rate has gone non-negative so the
 * predicted ETA is null). Refits the logistic on the full record and
 * draws the curve through the data window only — no future extension,
 * no dashed extrapolated segment.
 */
private fun buildInSampleOverlay(
    ctx: Context,
    xs: DoubleArray,
    ys: DoubleArray,
    fg: Double,
    tStartMs: Double,
    plateaus: List<com.ispindle.plotter.analysis.Plateau>
): ChartOverlay? {
    val fit = AttenuationFit.fit(xs, ys) ?: return null
    val nowH = xs.last()
    val t0 = xs.first()

    val predict: (Double) -> Double = { msX ->
        val h = (msX - tStartMs) / 3_600_000.0
        fit.predict(h)
    }

    val (bandLowFn, bandHighFn, sampledFloor) = if (fit.covariance != null) {
        val gridSize = 96
        val grid = DoubleArray(gridSize) { t0 + (nowH - t0) * it.toDouble() / (gridSize - 1) }
        val rng = Random(0x5E1ED)
        val bands = fit.predictiveBand(
            times = grid, rng = rng, nSamples = 256,
            quantiles = doubleArrayOf(0.025, 0.5, 0.975)
        )
        val lows = bands[0]; val highs = bands[2]
        fun interp(arr: DoubleArray): (Double) -> Double = { msX ->
            val h = (msX - tStartMs) / 3_600_000.0
            val u = ((h - t0) / (nowH - t0)).coerceIn(0.0, 1.0)
            val pos = u * (gridSize - 1)
            val i0 = pos.toInt().coerceAtMost(gridSize - 2)
            val frac = pos - i0
            arr[i0] + frac * (arr[i0 + 1] - arr[i0])
        }
        Triple(interp(lows) as (Double) -> Double, interp(highs) as (Double) -> Double, lows.min())
    } else {
        Triple(null, null, fg - 0.0015)
    }

    val floor = kotlin.math.min(sampledFloor, fg - 0.0015)

    return ChartOverlay(
        color = androidx.compose.ui.graphics.Color(0xFFD1495B),
        sample = predict,
        sampleCount = 96,
        extendXTo = null,
        extendYDownTo = floor,
        dashAfterX = null,
        bandLow = bandLowFn,
        bandHigh = bandHighFn,
        plateauSpans = plateaus.map {
            PlateauSpan(
                xRange = (tStartMs + it.startH * 3_600_000.0)..
                    (tStartMs + it.endH * 3_600_000.0),
                label = plateauLabel(ctx, it)
            )
        }
    )
}

/**
 * Bundle of (point predictor, lower-band, upper-band, y-floor) for an
 * SG overlay. Band functions take the chart's millisecond x-axis and
 * return the SG at that point.
 */
private data class OverlayCurves(
    val predict: (Double) -> Double,
    val bandLow: ((Double) -> Double)?,
    val bandHigh: ((Double) -> Double)?,
    val floor: Double
)

/**
 * Build the predictor and the 95 % credible band for the SG overlay.
 *
 * When `source == Logistic`, refits the logistic so we can pull samples
 * from the Laplace approximation: 256 parameter draws evaluated on a
 * dense time grid, with 2.5 % / 50 % / 97.5 % quantiles taken at each
 * grid point. The band reads off the per-grid quantile arrays via
 * linear interpolation in `t`.
 *
 * When the logistic source isn't available (or the fit doesn't carry a
 * covariance), falls back to the older single-anchor analytical S-curve
 * with no band.
 */
private fun predictorWithBand(
    source: Fermentation.PredictionSource,
    xs: DoubleArray,
    ys: DoubleArray,
    state: Fermentation.State,
    fg: Double,
    nowH: Double,
    tStartMs: Double,
    nowMs: Double,
    etaH: Double
): OverlayCurves {
    val finishH = nowH + etaH

    if (source == Fermentation.PredictionSource.Gompertz) {
        val fit = AttenuationFit.fit(xs, ys)
        if (fit?.covariance != null) {
            // Dense time grid spanning the whole chart x-extent, in
            // hours from the same anchor as the data array.
            val gridSize = 96
            val t0 = xs.first()
            val grid = DoubleArray(gridSize) {
                t0 + (finishH - t0) * it.toDouble() / (gridSize - 1)
            }
            val rng = Random(0x5E1ED)
            val bands = fit.predictiveBand(
                times = grid,
                rng = rng,
                nSamples = 256,
                quantiles = doubleArrayOf(0.025, 0.5, 0.975)
            )
            val lows = bands[0]
            val highs = bands[2]

            // Linear interpolation lookup from chart-time (ms) → SG.
            fun interp(arr: DoubleArray): (Double) -> Double = { msX ->
                val h = (msX - tStartMs) / 3_600_000.0
                val u = (h - t0) / (finishH - t0)
                val pos = (u * (gridSize - 1)).coerceIn(0.0, (gridSize - 1).toDouble())
                val i0 = pos.toInt().coerceAtMost(gridSize - 2)
                val frac = pos - i0
                arr[i0] + frac * (arr[i0 + 1] - arr[i0])
            }
            val predict: (Double) -> Double = { h -> fit.predict(h).coerceAtLeast(fg) }
            // Floor the chart's y-extent to the lowest sampled value or
            // a small margin below the MAP FG, whichever is lower.
            val sampledFloor = lows.min()
            val floor = kotlin.math.min(sampledFloor, fg - 0.0015)
            return OverlayCurves(predict, interp(lows), interp(highs), floor)
        }
    }

    // Fallback: older deterministic single-anchor S-curve with no band.
    val predict = predictorFor(source, xs, ys, state, fg, nowH)
    return OverlayCurves(predict, null, null, fg - 0.0015)
}

private fun predictorFor(
    source: Fermentation.PredictionSource,
    xs: DoubleArray,
    ys: DoubleArray,
    state: Fermentation.State,
    fg: Double,
    nowH: Double
): (Double) -> Double {
    if (source == Fermentation.PredictionSource.Gompertz) {
        val fit = AttenuationFit.fit(xs, ys)
        if (fit != null) return { h -> fit.predict(h).coerceAtLeast(fg) }
    }
    // Fallback when the full-window logistic was rejected: build an analytic
    // logistic anchored through (nowH, current) with span [fg, og] and the
    // observed recent rate. Solving for k and tMid:
    //   s = (current - fg) / (og - fg)
    //   k = -rate / ((og - fg) * s * (1 - s))
    //   tMid = nowH - ln((1-s)/s) / k
    // This gives an S-curve that asymptotes to OG going back and FG going
    // forward — visually correct for a fermentation, unlike a straight line.
    val current = ys.last()
    val og = ys.max()
    val rate = when (state) {
        is Fermentation.State.Active -> state.ratePerHour
        is Fermentation.State.Slowing -> state.ratePerHour
        else -> 0.0
    }
    val span = og - fg
    val s = if (span > 1e-6) (current - fg) / span else 0.5
    if (rate < 0.0 && span > 1e-6 && s > 1e-3 && s < 1.0 - 1e-3) {
        val k = -rate / (span * s * (1.0 - s))
        if (k.isFinite() && k > 0.0) {
            val tMid = nowH - ln((1.0 - s) / s) / k
            return { h -> fg + span / (1.0 + exp(k * (h - tMid))) }
        }
    }
    // No usable rate — hold flat at the latest reading.
    return { _ -> current }
}

private fun Fermentation.PredictionSource.label(ctx: Context): String = ctx.getString(
    when (this) {
        Fermentation.PredictionSource.Gompertz -> R.string.graph_source_gompertz
        Fermentation.PredictionSource.ExpDecay -> R.string.graph_source_expdecay
        Fermentation.PredictionSource.Linear -> R.string.graph_source_linear
        Fermentation.PredictionSource.Default -> R.string.graph_source_default
    }
)

/**
 * Short label for a plateau span on the chart. Mid plateaus get the SG
 * value (the diagnostic detail); Lag and Tail are self-explanatory.
 */
private fun plateauLabel(ctx: Context, p: com.ispindle.plotter.analysis.Plateau): String =
    when (p.kind) {
        com.ispindle.plotter.analysis.Plateau.Kind.Lag ->
            ctx.getString(R.string.graph_plateau_lag)
        // Three decimals (1 mSG resolution) keeps the label narrow enough
        // for typical band widths; the user can read 4 dp off the y-axis if
        // they want more precision.
        com.ispindle.plotter.analysis.Plateau.Kind.Mid ->
            ctx.getString(R.string.graph_plateau_paused, "%.3f".format(p.sg))
        com.ispindle.plotter.analysis.Plateau.Kind.Tail ->
            ctx.getString(R.string.graph_plateau_asymptote, "%.3f".format(p.sg))
    }

/**
 * Annotate the estimate text when a mid-ferment plateau was detected —
 * the diauxic-shift signature. Tail and Lag plateaus aren't called out
 * here: Lag has its own state, Tail effectively turns into Stuck or
 * Complete on its own. Only Mid is interesting in an Active/Slowing
 * narrative.
 */
private fun StringBuilder.appendMidPlateau(
    ctx: Context,
    plateaus: List<com.ispindle.plotter.analysis.Plateau>
) {
    val mid = plateaus.firstOrNull { it.kind == com.ispindle.plotter.analysis.Plateau.Kind.Mid }
        ?: return
    append(
        ctx.getString(
            R.string.graph_sg_paused_at,
            "%.4f".format(mid.sg),
            "%.1f".format(mid.durationH)
        )
    )
}

/** Format the 95 % credible interval on ETA as `(low–high)`. */
private fun StringBuilder.appendEtaCredible(ctx: Context, low: Double?, high: Double?) {
    if (low == null || high == null) return
    val lo = formatHoursAhead(ctx, low)
    val hi = formatHoursAhead(ctx, high)
    // Squelch when both endpoints round to the same display value — e.g.
    // very tight posterior or very large dataset.
    if (lo == hi) return
    append(ctx.getString(R.string.graph_sg_eta_ci, lo, hi))
}

@Composable
private fun BatteryEstimateLine(readings: List<Reading>, fit: Fits.Linear?) {
    if (readings.size < 2 || fit == null) return
    val ctx = LocalContext.current
    val tStartMs = readings.first().timestampMs.toDouble()
    val nowMs = System.currentTimeMillis().toDouble()

    val nowX = (nowMs - tStartMs) / 3_600_000.0
    val slopePerDay = fit.slope * 24.0
    val tCutoff = fit.timeToReach(3.4)
    val etaCutoff = tCutoff?.takeIf { it > nowX }?.minus(nowX)

    val prefix = stringResource(R.string.graph_battery_prefix)
    val body = if (slopePerDay >= 0) {
        // Positive or zero slope: nothing to predict. Probably charging or
        // sitting in storage rather than discharging.
        stringResource(R.string.graph_battery_no_discharge, "%+.3f".format(slopePerDay))
    } else {
        stringResource(R.string.graph_battery_drop_per_day, "%.3f".format(-slopePerDay)) +
            stringResource(R.string.graph_battery_days_to_cutoff, formatHoursAhead(ctx, etaCutoff, asDays = true))
    }
    val rms = stringResource(R.string.graph_battery_rms, "%.3f".format(fit.rmsResidual))
    EstimateText("$prefix$body · $rms")
}

/**
 * Formats `± σ` for the FG-uncertainty annotation. With thousands of
 * tail points, the Laplace posterior on FG can collapse to fractional
 * sub-mSG width — `%.4f` then rounds to "± 0.0000", which reads like a
 * bug. Floor the display at <0.0001 SG instead.
 */
private fun formatFgSigma(sigma: Double): String =
    if (sigma < 5e-5) " ± <0.0001" else " ± %.4f".format(sigma)

@Composable
private fun EstimateText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

private fun formatHoursAhead(ctx: Context, hours: Double?, asDays: Boolean = false): String {
    if (hours == null || !hours.isFinite() || hours <= 0) {
        return ctx.getString(R.string.graph_dur_em_dash)
    }
    val days = hours / 24.0
    return when {
        asDays || hours >= 48 -> ctx.getString(R.string.graph_dur_days, "%.1f".format(days))
        hours >= 1.0 -> ctx.getString(R.string.graph_dur_hours, "%.1f".format(hours))
        else -> ctx.getString(R.string.graph_dur_min, "%.0f".format(hours * 60))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimBeforeDialog(
    vm: MainViewModel,
    deviceId: Long,
    onDismiss: () -> Unit,
    onTrimmed: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = now - 24 * 3_600_000L
    )
    val cal = remember { java.util.Calendar.getInstance() }
    val timePickerState = rememberTimePickerState(
        initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = 0,
        is24Hour = true
    )
    var preview by remember { mutableIntStateOf(-1) }

    // The DatePicker exposes UTC midnight on the selected date, but users
    // think in local time when they pick "trim before 14:00 yesterday".
    // Recombine the date (extracted as UTC LocalDate) with the local-time
    // hour/minute, then convert back through the device's zone to epoch ms.
    val cutoffMs = remember(
        datePickerState.selectedDateMillis,
        timePickerState.hour,
        timePickerState.minute
    ) {
        val utcMidnight = datePickerState.selectedDateMillis ?: return@remember null
        val date = java.time.Instant.ofEpochMilli(utcMidnight)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        date.atTime(timePickerState.hour, timePickerState.minute)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    LaunchedEffect(cutoffMs) {
        preview = cutoffMs?.let { vm.readingCountBefore(deviceId, it) } ?: 0
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = cutoffMs != null && preview > 0,
                onClick = {
                    val ts = cutoffMs ?: return@TextButton
                    scope.launch {
                        val deleted = vm.deleteReadingsBefore(deviceId, ts)
                        onTrimmed(deleted)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    when (preview) {
                        -1 -> stringResource(R.string.graph_trim_counting)
                        0 -> stringResource(R.string.graph_trim_nothing_to_delete)
                        1 -> stringResource(R.string.graph_trim_delete_one)
                        else -> stringResource(R.string.graph_trim_delete_n, preview)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.graph_trim_delete_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            DatePicker(state = datePickerState, showModeToggle = false)
            Text(
                stringResource(R.string.graph_trim_time_of_day),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TimeInput(state = timePickerState)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    series: ChartSeries,
    xFormatter: (Double) -> String,
    emptyHint: String? = null,
    secondaryAxis: SecondaryAxis? = null,
    overlay: ChartOverlay? = null,
    yBands: List<YBand> = emptyList(),
    fixedYRange: ClosedFloatingPointRange<Double>? = null,
    yTickStep: Double? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (series.points.isEmpty() && emptyHint != null) {
                Text(emptyHint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            } else {
                LineChart(
                    series = series,
                    xFormatter = xFormatter,
                    xIsTimeMs = true,
                    secondaryAxis = secondaryAxis,
                    overlay = overlay,
                    yBands = yBands,
                    fixedYRange = fixedYRange,
                    yTickStep = yTickStep
                )
                Latest(series)
            }
        }
    }
}

@Composable
private fun Latest(series: ChartSeries) {
    val last = series.points.lastOrNull() ?: return
    Text(
        text = stringResource(R.string.graph_latest_label, series.format(last.second)),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
    )
}
