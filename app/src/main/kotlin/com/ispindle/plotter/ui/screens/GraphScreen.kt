package com.ispindle.plotter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.flow.map
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
    val device by remember(deviceId) { vm.deviceFlow(deviceId) }.collectAsState(initial = null)
    val readings by remember(deviceId) { vm.readingsFor(deviceId).map { it } }
        .collectAsState(initial = emptyList())

    var window by remember { mutableStateOf(TimeWindow.D7) }
    val now = System.currentTimeMillis()
    val cutoff = window.hours?.let { now - it * 3_600_000L } ?: Long.MIN_VALUE
    val scoped = readings.filter { it.timestampMs >= cutoff }

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
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TimeWindow.entries.forEach { w ->
                FilterChip(
                    selected = window == w,
                    onClick = { window = w },
                    label = { Text(w.label) }
                )
            }
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
            emptyHint = "No SG data yet — add calibration points to compute SG from tilt."
        )

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
    }
}

@Composable
private fun MetricCard(
    title: String,
    series: ChartSeries,
    xFormatter: (Double) -> String,
    emptyHint: String? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (series.points.isEmpty() && emptyHint != null) {
                Text(emptyHint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            } else {
                LineChart(series = series, xFormatter = xFormatter)
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
