package com.ispindle.plotter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.ispindle.plotter.R
import com.ispindle.plotter.calibration.Polynomial
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun CalibrationScreen(
    vm: MainViewModel,
    deviceId: Long,
    padding: PaddingValues
) {
    val ctx = LocalContext.current
    val device by remember(deviceId) { vm.deviceFlow(deviceId) }.collectAsState(initial = null)
    val latest by remember(deviceId) { vm.latestReadingFor(deviceId) }.collectAsState(initial = null)
    val points by remember(deviceId) { vm.calibrationFlow(deviceId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var angleText by remember { mutableStateOf("") }
    var sgText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var degree by remember { mutableStateOf(2) }
    var fitMessage by remember { mutableStateOf<String?>(null) }

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

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.cal_add_point_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.cal_add_point_description),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                latest?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.cal_current_angle, "%.2f".format(it.angle)),
                            fontFamily = FontFamily.Monospace
                        )
                        TextButton(onClick = { angleText = "%.2f".format(it.angle) }) {
                            Text(stringResource(R.string.cal_use_this))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = angleText,
                        onValueChange = { angleText = it },
                        label = { Text(stringResource(R.string.cal_angle_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(140.dp)
                    )
                    OutlinedTextField(
                        value = sgText,
                        onValueChange = { sgText = it },
                        label = { Text(stringResource(R.string.cal_known_sg_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(140.dp)
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.cal_note_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val a = angleText.toDoubleOrNull()
                        val sg = sgText.toDoubleOrNull()
                        if (a != null && sg != null) {
                            vm.addCalibrationPoint(deviceId, a, sg, noteText)
                            angleText = ""; sgText = ""; noteText = ""
                        }
                    }
                ) { Text(stringResource(R.string.cal_btn_add_point)) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cal_points_title, points.size), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (points.isEmpty()) {
                    Text(stringResource(R.string.cal_no_points_yet), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                } else {
                    points.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(
                                checked = p.enabled,
                                onCheckedChange = { vm.toggleCalibrationPoint(p) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "%.2f°   →   SG %.4f".format(p.angle, p.knownSG),
                                    fontFamily = FontFamily.Monospace
                                )
                                if (p.note.isNotBlank()) {
                                    Text(p.note, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = { vm.deleteCalibrationPoint(p) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cal_delete_point_cd))
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cal_fit_polynomial_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { d ->
                        FilterChip(
                            selected = degree == d,
                            onClick = { degree = d },
                            label = { Text(stringResource(R.string.cal_degree_chip, d)) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.cal_fit_description),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                val fitSingularLabel = stringResource(R.string.cal_fit_singular)
                val calClearedLabel = stringResource(R.string.cal_cleared)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            fitMessage = when (val r = vm.fitAndSave(deviceId, degree)) {
                                is MainViewModel.FitOutcome.Fitted -> {
                                    val poly = r.polynomial.format()
                                    val r2 = r.rSquared
                                    if (r2 != null) ctx.getString(R.string.cal_fit_saved_r2, poly, "%.4f".format(r2))
                                    else ctx.getString(R.string.cal_fit_saved, poly)
                                }
                                is MainViewModel.FitOutcome.NotEnoughPoints ->
                                    ctx.getString(R.string.cal_fit_not_enough_points, r.need, r.have)
                                MainViewModel.FitOutcome.Singular ->
                                    fitSingularLabel
                            }
                        }
                    }) { Text(stringResource(R.string.cal_btn_fit_save)) }
                    OutlinedButton(onClick = {
                        vm.clearCalibration(deviceId)
                        fitMessage = calClearedLabel
                    }) { Text(stringResource(R.string.cal_btn_clear_calibration)) }
                }
                fitMessage?.let {
                    Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }

        device?.let { d ->
            if (d.calDegree > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.cal_current_polynomial_title),
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        val poly = Polynomial.fromDevice(d)
                        Text(stringResource(R.string.cal_sg_function, poly.format()),
                            fontFamily = FontFamily.Monospace,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        d.calRSquared?.let {
                            Text("R² = %.6f".format(it),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                        val chartPts = (30..90 step 2).map { a ->
                            a.toDouble() to poly.eval(a.toDouble())
                        }
                        LineChart(
                            series = ChartSeries(
                                label = "fit",
                                color = Color(0xFF3E7B51),
                                points = chartPts,
                                format = { "%.4f".format(it) }
                            ),
                            xFormatter = { "%.0f°".format(it) }
                        )
                    }
                }
            }
        }
    }
}
