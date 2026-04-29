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

/**
 * SG → Plato (mass percent sucrose). Hackbarth/Lincoln cubic; accurate to
 * ~0.05 °P across 1.000–1.130. Used to translate the user's target SG
 * into the sugar-mass fraction needed to compose the starting solution.
 */
private fun sgToPlato(sg: Double): Double =
    -616.868 + 1111.14 * sg - 630.272 * sg * sg + 135.997 * sg * sg * sg

/**
 * Returns (sugar_grams, water_grams) for a sucrose solution of [volumeMl]
 * at [sg]. 1 mL of solution at SG x weighs x g, so total mass is V × SG;
 * sugar mass = total × Plato/100, water mass = total − sugar.
 */
private fun sugarWaterRecipe(volumeMl: Double, sg: Double): Pair<Double, Double> {
    val totalMassG = volumeMl * sg
    val plato = sgToPlato(sg).coerceAtLeast(0.0)
    val sugarG = totalMassG * plato / 100.0
    return sugarG to (totalMassG - sugarG)
}

/**
 * Volume of solution to remove and replace with the same volume of water
 * to step from [currentSg] to [targetSg] in a fixed [volumeMl] vessel.
 *
 * Uses the linear approximation (SG − 1) ∝ sucrose concentration, which
 * holds within ~1 % over 1.000–1.090 — well below the iSpindle's own
 * calibration noise floor, and avoids making the user juggle Plato.
 */
private fun swapVolumeMl(volumeMl: Double, currentSg: Double, targetSg: Double): Double {
    val cur = currentSg - 1.0
    val tgt = targetSg - 1.0
    if (cur <= 0.0) return 0.0
    return volumeMl * (1.0 - (tgt / cur)).coerceIn(0.0, 1.0)
}

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

    // Sugar/water mixer state — sticky so the helper persists across
    // recompositions while the user mixes and steps down.
    var helperVolMl by remember { mutableStateOf("1000") }
    var helperStartSg by remember { mutableStateOf("1.080") }
    var helperCurSg by remember { mutableStateOf("1.080") }
    var helperTargetSg by remember { mutableStateOf("1.060") }

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
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cal_helper_title),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.cal_helper_intro),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = helperVolMl,
                        onValueChange = { helperVolMl = it },
                        label = { Text(stringResource(R.string.cal_helper_volume)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(160.dp)
                    )
                    OutlinedTextField(
                        value = helperStartSg,
                        onValueChange = { helperStartSg = it },
                        label = { Text(stringResource(R.string.cal_helper_start_sg)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(140.dp)
                    )
                }
                val volMl = helperVolMl.toDoubleOrNull()
                val startSg = helperStartSg.toDoubleOrNull()
                val recipeText = if (volMl != null && volMl > 0.0 &&
                    startSg != null && startSg in 1.001..1.130) {
                    val (sugarG, waterG) = sugarWaterRecipe(volMl, startSg)
                    stringResource(
                        R.string.cal_helper_recipe,
                        "%.1f".format(sugarG),
                        "%.1f".format(waterG),
                        "%.0f".format(volMl),
                        "%.4f".format(startSg)
                    )
                } else stringResource(R.string.cal_helper_recipe_invalid)
                Text(
                    recipeText,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = helperCurSg,
                        onValueChange = { helperCurSg = it },
                        label = { Text(stringResource(R.string.cal_helper_current_sg)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(140.dp)
                    )
                    OutlinedTextField(
                        value = helperTargetSg,
                        onValueChange = { helperTargetSg = it },
                        label = { Text(stringResource(R.string.cal_helper_target_sg)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(140.dp)
                    )
                }
                val curSg = helperCurSg.toDoubleOrNull()
                val targetSg = helperTargetSg.toDoubleOrNull()
                val swapText = if (volMl != null && volMl > 0.0 &&
                    curSg != null && targetSg != null &&
                    curSg > 1.0 && targetSg in 1.000..curSg) {
                    val swapMl = swapVolumeMl(volMl, curSg, targetSg)
                    stringResource(
                        R.string.cal_helper_swap,
                        "%.0f".format(swapMl),
                        "%.4f".format(targetSg)
                    )
                } else stringResource(R.string.cal_helper_swap_invalid)
                Text(
                    swapText,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

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
