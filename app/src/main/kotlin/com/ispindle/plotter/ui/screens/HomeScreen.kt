package com.ispindle.plotter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.data.Reading
import com.ispindle.plotter.network.IspindleHttpServer
import com.ispindle.plotter.network.IspindleServerService
import com.ispindle.plotter.network.NetworkUtils
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: MainViewModel,
    serverState: StateFlow<IspindleHttpServer.State>,
    padding: PaddingValues
) {
    val state by serverState.collectAsState()
    val latest by vm.latestReading.collectAsState()
    val devices by vm.devices.collectAsState()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .padding(padding)
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP Server", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                val (status, endpoint) = when (val s = state) {
                    is IspindleHttpServer.State.Running -> {
                        val ip = NetworkUtils.preferredIpv4() ?: "<no network>"
                        "Running" to "http://$ip:${s.port}/"
                    }
                    IspindleHttpServer.State.Stopped -> "Stopped" to null
                    is IspindleHttpServer.State.Error -> "Error: ${s.message}" to null
                }
                Text(status)
                endpoint?.let {
                    Text("Point iSpindle → $it", fontFamily = FontFamily.Monospace)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { IspindleServerService.start(ctx) }) { Text("Start") }
                    OutlinedButton(onClick = { IspindleServerService.stop(ctx) }) { Text("Stop") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Latest reading", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                val r = latest
                if (r == null) {
                    Text("No readings yet. Configure your iSpindle to POST to the URL above.")
                } else {
                    LatestReadingDetails(r)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Known devices (${devices.size})", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                if (devices.isEmpty()) {
                    Text("Devices appear automatically after their first POST.")
                } else {
                    devices.forEach {
                        Text("• ${it.userLabel}  (hwId ${it.hwId})")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LatestReadingDetails(r: Reading) {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Time: ${fmt.format(Date(r.timestampMs))}")
        Text("Angle: %.2f°".format(r.angle))
        Text("Temperature: %.2f °C".format(r.temperatureC))
        Text("Battery: %.2f V".format(r.batteryV))
        r.rssi?.let { Text("RSSI: $it dBm") }
        r.computedGravity?.let { Text("SG (calibrated): %.4f".format(it)) }
            ?: r.reportedGravity?.let { Text("SG (as reported): %.4f".format(it)) }
            ?: Text("SG: — (no calibration)")
    }
}
