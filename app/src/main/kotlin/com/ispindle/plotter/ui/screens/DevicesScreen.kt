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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.R
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
            item { Text(stringResource(R.string.devices_no_devices_yet), Modifier.padding(8.dp)) }
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
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) }
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
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) }
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
