package com.ispindle.plotter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.R
import com.ispindle.plotter.data.Device
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
    padding: PaddingValues,
    onOpenGraph: (Long) -> Unit,
    onOpenCalibrate: (Long) -> Unit
) {
    val state by serverState.collectAsState()
    val latest by vm.latestReading.collectAsState()
    val devices by vm.devices.collectAsState()
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<Device?>(null) }
    var deleting by remember { mutableStateOf<Device?>(null) }

    Column(
        modifier = Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.home_section_http_server),
                    style = MaterialTheme.typography.titleMedium
                )
                val (status, endpoint) = when (val s = state) {
                    is IspindleHttpServer.State.Running -> {
                        val ip = NetworkUtils.preferredIpv4() ?: "<no network>"
                        stringResource(R.string.home_status_running) to "http://$ip:${s.port}/"
                    }
                    IspindleHttpServer.State.Stopped -> stringResource(R.string.home_status_stopped) to null
                    is IspindleHttpServer.State.Error -> stringResource(R.string.home_status_error_prefix, s.message) to null
                }
                Text(status)
                endpoint?.let { url ->
                    Text(
                        text = stringResource(R.string.home_endpoint_point_ispindle, url),
                        fontFamily = FontFamily.Monospace,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                copyToClipboard(ctx, label = "iSpindle endpoint", text = url)
                            }
                    )
                    Text(
                        text = stringResource(R.string.home_tap_to_copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { IspindleServerService.start(ctx) }) {
                        Text(stringResource(R.string.home_btn_start))
                    }
                    OutlinedButton(onClick = { IspindleServerService.stop(ctx) }) {
                        Text(stringResource(R.string.home_btn_stop))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.home_section_latest_reading),
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider()
                val r = latest
                if (r == null) {
                    Text(stringResource(R.string.home_no_readings_yet))
                } else {
                    LatestReadingDetails(r)
                }
            }
        }

        Text(
            stringResource(R.string.home_section_known_devices, devices.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.home_devices_appear_automatically),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
            devices.forEach { d ->
                DeviceRow(
                    device = d,
                    onRename = { renaming = d },
                    onGraph = { onOpenGraph(d.id) },
                    onCalibrate = { onOpenCalibrate(d.id) },
                    onDelete = { deleting = d }
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }

    renaming?.let { target ->
        var label by remember(target.id) { mutableStateOf(target.userLabel) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.devices_rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(target.id, label.ifBlank { target.reportedName })
                    renaming = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    deleting?.let { target ->
        var readingCount by remember(target.id) { mutableIntStateOf(-1) }
        LaunchedEffect(target.id) { readingCount = vm.readingCount(target.id) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.devices_delete_dialog_title, target.userLabel)) },
            text = {
                val countLine = when (readingCount) {
                    -1 -> stringResource(R.string.devices_counting_readings)
                    0 -> stringResource(R.string.devices_no_readings_stored)
                    1 -> stringResource(R.string.devices_one_reading_will_be_deleted)
                    else -> stringResource(R.string.devices_n_readings_will_be_deleted, readingCount)
                }
                Text("$countLine\n\n${stringResource(R.string.devices_calibration_also_removed)}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDevice(target.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    onRename: () -> Unit,
    onGraph: () -> Unit,
    onCalibrate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.userLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.devices_hw_id_reported, device.hwId, device.reportedName),
                style = MaterialTheme.typography.bodySmall
            )
            val calStatus = if (device.calDegree > 0)
                stringResource(R.string.devices_calibration_degree, device.calDegree) +
                    (device.calRSquared?.let { ", R²=%.4f".format(it) } ?: "")
            else stringResource(R.string.devices_calibration_none)
            Text(calStatus, style = MaterialTheme.typography.bodySmall)
            device.lastSeenIp?.let {
                Text(
                    stringResource(R.string.devices_last_reported_from, it),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onGraph) { Text(stringResource(R.string.devices_action_graph)) }
                TextButton(onClick = onCalibrate) { Text(stringResource(R.string.devices_action_calibrate)) }
                TextButton(onClick = onRename) { Text(stringResource(R.string.devices_action_rename)) }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.devices_action_delete)) }
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(ctx, ctx.getString(R.string.home_copied_toast, text), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LatestReadingDetails(r: Reading) {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.home_reading_time, fmt.format(Date(r.timestampMs))))
        Text(stringResource(R.string.home_reading_angle, "%.2f".format(r.angle)))
        Text(stringResource(R.string.home_reading_temperature, "%.2f".format(r.temperatureC)))
        Text(stringResource(R.string.home_reading_battery, "%.2f".format(r.batteryV)))
        r.rssi?.let { Text(stringResource(R.string.home_reading_rssi, it.toString())) }
        r.computedGravity?.let { Text(stringResource(R.string.home_reading_sg_calibrated, "%.4f".format(it))) }
            ?: r.reportedGravity?.let { Text(stringResource(R.string.home_reading_sg_reported, "%.4f".format(it))) }
            ?: Text(stringResource(R.string.home_reading_sg_none))
    }
}
