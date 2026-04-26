package com.ispindle.plotter.ui.screens

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
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.analysis.Fermentation
import com.ispindle.plotter.analysis.Fits
import com.ispindle.plotter.data.Reading
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TimeWindow(val label: String, val hours: Int?) {
    H24("24h", 24),
    D7("7d", 24 * 7),
    D30("30d", 24 * 30),
    ALL("All", null)
}

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

    var window by remember { mutableStateOf(TimeWindow.D7) }
    val now = System.currentTimeMillis()
    val cutoff = window.hours?.let { now - it * 3_600_000L } ?: Long.MIN_VALUE
    val scoped = readings.filter { it.timestampMs >= cutoff }

    var showTrim by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = vm.exportReadingsCsv(deviceId)
            if (csv.isEmpty()) {
                toast = "Nothing to export."
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast = if (ok) "Exported ${readings.size} readings." else "Export failed."
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
            "${scoped.size} readings in window · ${readings.size} total",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeWindow.entries.forEach { w ->
                FilterChip(
                    selected = window == w,
                    onClick = { window = w },
                    label = { Text(w.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showTrim = true },
                enabled = readings.isNotEmpty()
            ) { Text("Trim before…") }
            OutlinedButton(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val safeLabel = (device?.userLabel ?: "device")
                        .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    csvLauncher.launch("ispindle-${safeLabel}-${stamp}.csv")
                },
                enabled = readings.isNotEmpty()
            ) { Text("Export CSV…") }
        }
        toast?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary)
        }

        if (scoped.isEmpty()) {
            Text("No readings in this window.")
            return@Column
        }

        MetricCard(
            title = "Tilt angle (°)",
            series = ChartSeries(
                label = "angle",
                color = Color(0xFF2D5F9E),
                points = scoped.map { it.timestampMs.toDouble() to it.angle },
                format = { "%.1f°".format(it) }
            ),
            xFormatter = xFmt
        )

        MetricCard(
            title = "Temperature (°C)",
            series = ChartSeries(
                label = "temp",
                color = Color(0xFFB84A47),
                points = scoped.map { it.timestampMs.toDouble() to it.temperatureC },
                format = { "%.1f°C".format(it) }
            ),
            xFormatter = xFmt
        )

        val sgPoints = scoped.mapNotNull { r ->
            val sg = r.computedGravity ?: r.reportedGravity
            if (sg != null && sg > 0.0) r.timestampMs.toDouble() to sg else null
        }
        MetricCard(
            title = "Specific gravity",
            series = ChartSeries(
                label = "sg",
                color = Color(0xFF3E7B51),
                points = sgPoints,
                format = { "%.4f".format(it) }
            ),
            xFormatter = xFmt,
            emptyHint = "No SG data yet — add calibration points to compute SG from tilt.",
            secondaryAxis = SecondaryAxis(
                caption = "PA%",
                // Triple-scale-hydrometer rule of thumb: every 0.001 SG point
                // above 1.000 ≈ 0.13125 % alcohol-by-volume potential.
                transform = { sg -> (sg - 1.0) * 131.25 },
                format = { pa -> "%.1f%%".format(pa) }
            )
        )
        SgEstimateLine(scoped, sgPoints)

        MetricCard(
            title = "Battery (V)",
            series = ChartSeries(
                label = "battery",
                color = Color(0xFF7A5C8A),
                points = scoped.map { it.timestampMs.toDouble() to it.batteryV },
                format = { "%.2fV".format(it) }
            ),
            xFormatter = xFmt
        )
        BatteryEstimateLine(scoped)
    }

    if (showTrim) {
        TrimBeforeDialog(
            vm = vm,
            deviceId = deviceId,
            onDismiss = { showTrim = false },
            onTrimmed = { count ->
                showTrim = false
                toast = if (count > 0) "Deleted $count readings." else "Nothing to trim."
            }
        )
    }
}

@Composable
private fun SgEstimateLine(
    readings: List<Reading>,
    sgPoints: List<Pair<Double, Double>>
) {
    if (sgPoints.size < 6) return
    val tStartMs = sgPoints.first().first
    val xs = DoubleArray(sgPoints.size) { (sgPoints[it].first - tStartMs) / 3_600_000.0 }
    val ys = DoubleArray(sgPoints.size) { sgPoints[it].second }
    val state = remember(sgPoints) { Fermentation.analyse(xs, ys) }

    when (state) {
        is Fermentation.State.Insufficient -> EstimateText(
            "SG: not enough trend yet — wait for more readings or widen the time window."
        )
        is Fermentation.State.Lag -> EstimateText(
            "Lag phase · OG %.4f · current %.4f · %.1f h since first reading, no decline yet"
                .format(state.og, state.current, state.durationHours)
        )
        is Fermentation.State.Active -> {
            val abvNow = (state.og - state.current) * 131.25
            val abvAtFg = (state.og - state.predictedFg) * 131.25
            EstimateText(buildString {
                append("Active · ")
                append("OG %.4f → est FG %.4f".format(state.og, state.predictedFg))
                append(" · %.4f now · ".format(state.current))
                append("rate %.3f SG/h · ".format(state.ratePerHour))
                append("ETA ${formatHoursAhead(state.etaToFinishHours)}")
                append(" · ABV %.1f%% → %.1f%%".format(abvNow, abvAtFg))
                append(" · ${state.source.label()}")
            })
        }
        is Fermentation.State.Slowing -> {
            val abvNow = (state.og - state.current) * 131.25
            val abvAtFg = (state.og - state.predictedFg) * 131.25
            EstimateText(buildString {
                append("Slowing · ")
                append("OG %.4f → est FG %.4f".format(state.og, state.predictedFg))
                append(" · %.4f now · ".format(state.current))
                append("rate %.3f SG/h · ".format(state.ratePerHour))
                append("ETA ${formatHoursAhead(state.etaToFinishHours)}")
                append(" · ABV %.1f%% → %.1f%%".format(abvNow, abvAtFg))
                append(" · ${state.source.label()}")
            })
        }
        is Fermentation.State.Complete -> {
            val abv = (state.og - state.fg) * 131.25
            EstimateText(
                "Complete · OG %.4f · FG %.4f · %.1f%% ABV".format(state.og, state.fg, abv)
            )
        }
        is Fermentation.State.Stuck -> {
            val abvNow = (state.og - state.current) * 131.25
            EstimateText(
                "⚠ Stuck · OG %.4f · current %.4f · expected ~%.4f · flat for %.1f h · ABV so far %.1f%%"
                    .format(state.og, state.current, state.expectedFg, state.flatHours, abvNow),
                isError = true
            )
        }
    }
}

private fun Fermentation.PredictionSource.label(): String = when (this) {
    Fermentation.PredictionSource.Logistic -> "logistic"
    Fermentation.PredictionSource.ExpDecay -> "exp"
    Fermentation.PredictionSource.Linear -> "rate-based (75% attenuation prior)"
    Fermentation.PredictionSource.Default -> "75% attenuation prior"
}

@Composable
private fun BatteryEstimateLine(readings: List<Reading>) {
    if (readings.size < 2) return
    val tStartMs = readings.first().timestampMs.toDouble()
    val nowMs = System.currentTimeMillis().toDouble()
    val xs = DoubleArray(readings.size) {
        (readings[it].timestampMs - tStartMs) / 3_600_000.0
    }
    val ys = DoubleArray(readings.size) { readings[it].batteryV }
    val fit = remember(readings) { Fits.fitLinear(xs, ys) } ?: return

    val nowX = (nowMs - tStartMs) / 3_600_000.0
    val slopePerDay = fit.slope * 24.0
    val tCutoff = fit.timeToReach(3.4)
    val etaCutoff = tCutoff?.takeIf { it > nowX }?.minus(nowX)
    val text = buildString {
        append("Battery: ")
        if (slopePerDay >= 0) {
            // Positive or zero slope: nothing to predict. Probably charging or
            // sitting in storage rather than discharging.
            append("trend ${"%+.3f".format(slopePerDay)} V/day (no discharge to extrapolate)")
        } else {
            append("%.3f V/day drop · ".format(-slopePerDay))
            append("days to 3.4 V cutoff: ${formatHoursAhead(etaCutoff, asDays = true)}")
        }
        append(" · RMS ±%.3f V".format(fit.rmsResidual))
    }
    EstimateText(text)
}

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

private fun formatHoursAhead(hours: Double?, asDays: Boolean = false): String {
    if (hours == null || !hours.isFinite() || hours <= 0) return "—"
    val days = hours / 24.0
    return when {
        asDays || hours >= 48 -> "%.1f days".format(days)
        hours >= 1.0 -> "%.1f h".format(hours)
        else -> "%.0f min".format(hours * 60)
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
                        -1 -> "Counting…"
                        0 -> "Nothing to delete"
                        1 -> "Delete 1 reading"
                        else -> "Delete $preview readings"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Delete every reading recorded before this date and local time.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            DatePicker(state = datePickerState, showModeToggle = false)
            Text(
                "Time of day (local)",
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
    secondaryAxis: SecondaryAxis? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (series.points.isEmpty() && emptyHint != null) {
                Text(emptyHint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            } else {
                LineChart(series = series, xFormatter = xFormatter, secondaryAxis = secondaryAxis)
                Latest(series)
            }
        }
    }
}

@Composable
private fun Latest(series: ChartSeries) {
    val last = series.points.lastOrNull() ?: return
    Text(
        text = "Latest: ${series.format(last.second)}",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
    )
}
