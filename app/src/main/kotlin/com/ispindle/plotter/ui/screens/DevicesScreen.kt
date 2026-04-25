package com.ispindle.plotter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.data.Device
import com.ispindle.plotter.ui.MainViewModel

@Composable
fun DevicesScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    onOpenGraph: (Long) -> Unit,
    onOpenCalibrate: (Long) -> Unit
) {
    val devices by vm.devices.collectAsState()
    var renaming by remember { mutableStateOf<Device?>(null) }
    var deleting by remember { mutableStateOf<Device?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (devices.isEmpty()) {
            item { Text("No devices yet — they show up after the first POST.", Modifier.padding(8.dp)) }
        }
        items(devices, key = { it.id }) { d ->
            DeviceRow(
                device = d,
                onRename = { renaming = d },
                onGraph = { onOpenGraph(d.id) },
                onCalibrate = { onOpenCalibrate(d.id) },
                onDelete = { deleting = d }
            )
        }
    }

    renaming?.let { target ->
        var label by remember(target.id) { mutableStateOf(target.userLabel) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(target.id, label.ifBlank { target.reportedName })
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }

    deleting?.let { target ->
        var readingCount by remember(target.id) { mutableIntStateOf(-1) }
        LaunchedEffect(target.id) { readingCount = vm.readingCount(target.id) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${target.userLabel}\"?") },
            text = {
                val countLine = when (readingCount) {
                    -1 -> "Counting readings…"
                    0 -> "No readings stored."
                    1 -> "1 reading will be permanently deleted."
                    else -> "$readingCount readings will be permanently deleted."
                }
                Text("$countLine\n\nCalibration points are also removed. " +
                        "If the iSpindle posts again it will reappear as a new entry.")
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
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
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
                "hwId ${device.hwId} · reported \"${device.reportedName}\"",
                style = MaterialTheme.typography.bodySmall
            )
            val calStatus = if (device.calDegree > 0)
                "Calibration: degree ${device.calDegree}" +
                        (device.calRSquared?.let { ", R²=%.4f".format(it) } ?: "")
            else "Calibration: none"
            Text(calStatus, style = MaterialTheme.typography.bodySmall)
            device.lastSeenIp?.let {
                Text(
                    "Last reported from $it",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onGraph) { Text("Graph") }
                TextButton(onClick = onCalibrate) { Text("Calibrate") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            }
        }
    }
}
