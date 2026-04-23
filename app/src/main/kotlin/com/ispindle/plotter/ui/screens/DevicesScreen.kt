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
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    var editing by remember { mutableStateOf<Device?>(null) }

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
                onRename = { editing = d },
                onGraph = { onOpenGraph(d.id) },
                onCalibrate = { onOpenCalibrate(d.id) }
            )
        }
    }

    editing?.let { target ->
        var label by remember(target.id) { mutableStateOf(target.userLabel) }
        AlertDialog(
            onDismissRequest = { editing = null },
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
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    onRename: () -> Unit,
    onGraph: () -> Unit,
    onCalibrate: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.userLabel, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("hwId ${device.hwId} · reported \"${device.reportedName}\"",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            val calStatus = if (device.calDegree > 0)
                "Calibration: degree ${device.calDegree}" +
                        (device.calRSquared?.let { ", R²=%.4f".format(it) } ?: "")
            else "Calibration: none"
            Text(calStatus, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onGraph) { Text("Graph") }
                TextButton(onClick = onCalibrate) { Text("Calibrate") }
                TextButton(onClick = onRename) { Text("Rename") }
            }
        }
    }
}
