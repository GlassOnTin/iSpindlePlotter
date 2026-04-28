package com.ispindle.plotter.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.R
import com.ispindle.plotter.network.IspindleService
import com.ispindle.plotter.network.LiveReading
import com.ispindle.plotter.network.ScannedAp
import com.ispindle.plotter.ui.ConfigureViewModel

@Composable
fun ConfigureScreen(
    vm: ConfigureViewModel,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    val ui by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val phase = ui.phase) {
            ConfigureViewModel.Phase.Idle -> IdleCard(vm, ui)
            ConfigureViewModel.Phase.Joining -> StatusCard(
                title = stringResource(R.string.configure_joining_title),
                body = stringResource(R.string.configure_joining_body),
                showSpinner = true,
                onCancel = { vm.reset() }
            )
            ConfigureViewModel.Phase.Unsupported -> StatusCard(
                title = stringResource(R.string.configure_unsupported_title),
                body = stringResource(R.string.configure_unsupported_body),
                onCancel = onBack
            )
            is ConfigureViewModel.Phase.Failed -> StatusCard(
                title = stringResource(R.string.configure_failed_title),
                body = phase.message,
                onCancel = { vm.reset() }
            )
            is ConfigureViewModel.Phase.Connected -> ConnectedCard(vm, ui, networks = emptyList())
            is ConfigureViewModel.Phase.ScanLoaded -> ConnectedCard(vm, ui, networks = phase.networks)
            ConfigureViewModel.Phase.Saving -> StatusCard(
                title = stringResource(R.string.configure_saving_title),
                body = stringResource(R.string.configure_saving_body),
                showSpinner = true
            )
            is ConfigureViewModel.Phase.Joined -> StatusCard(
                title = stringResource(R.string.configure_joined_title, phase.homeSsid),
                body = stringResource(
                    R.string.configure_joined_body,
                    phase.deviceIp.ifBlank { stringResource(R.string.configure_joined_ip_unknown) }
                ),
                onCancel = {
                    vm.reset()
                    onBack()
                }
            )
            ConfigureViewModel.Phase.SaveTimeout -> StatusCard(
                title = stringResource(R.string.configure_save_timeout_title),
                body = stringResource(R.string.configure_save_timeout_body),
                onCancel = {
                    vm.reset()
                    onBack()
                }
            )
        }

        ui.live?.takeIf { it.tiltDeg != null || it.batteryV != null }?.let { LiveReadingCard(it) }

        ui.firmwarePolynomial?.let { FirmwareCalibrationCard(vm, ui) }

        if (ui.pushableCalibrations.isNotEmpty()) {
            PushCalibrationCard(vm, ui)
        }
    }
}

@Composable
private fun PushCalibrationCard(
    vm: ConfigureViewModel,
    ui: ConfigureViewModel.UiState
) {
    val selected = ui.pushFromDeviceId?.let { id ->
        ui.pushableCalibrations.firstOrNull { it.id == id }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.configure_push_cal_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.configure_push_cal_description),
                style = MaterialTheme.typography.bodySmall
            )
            ui.pushableCalibrations.forEach { device ->
                val isSelected = device.id == ui.pushFromDeviceId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.selectPushSource(if (isSelected) null else device.id) }
                        .padding(vertical = 4.dp)
                ) {
                    Text(if (isSelected) "●" else "○",
                        style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${device.userLabel}  (degree ${device.calDegree}" +
                                    (device.calRSquared?.let { ", R²=%.4f".format(it) } ?: "") + ")",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            selected?.let { d ->
                val expr = remember(d.id, d.calA, d.calB, d.calC, d.calD, d.calDegree) {
                    com.ispindle.plotter.calibration.Polynomial.fromDevice(d).toTinyExpr()
                }
                Text(
                    "POLYN = $expr",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (ui.pushFromDeviceId != null) {
                TextButton(onClick = { vm.selectPushSource(null) }) {
                    Text(stringResource(R.string.configure_dont_change_polynomial))
                }
            }
        }
    }
}

@Composable
private fun FirmwareCalibrationCard(
    vm: ConfigureViewModel,
    ui: ConfigureViewModel.UiState
) {
    val raw = ui.firmwarePolynomial ?: return
    val parsed = ui.parsedCoeffs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.configure_on_device_cal_title), style = MaterialTheme.typography.titleMedium)
            Text(
                raw,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            when {
                ui.polynomialImported -> Text(
                    stringResource(R.string.configure_imported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                parsed != null -> {
                    val terms = listOfNotNull(
                        parsed[0].takeIf { it != 0.0 }?.let { "%.4g".format(it) },
                        parsed[1].takeIf { it != 0.0 }?.let { "%.4g·tilt".format(it) },
                        parsed[2].takeIf { it != 0.0 }?.let { "%.4g·tilt²".format(it) },
                        parsed[3].takeIf { it != 0.0 }?.let { "%.4g·tilt³".format(it) }
                    )
                    Text(stringResource(R.string.configure_parsed_as, terms.joinToString(" + ")),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.importFirmwareCalibration() }) {
                        Text(stringResource(R.string.configure_btn_import_cal))
                    }
                }
                else -> Text(
                    stringResource(R.string.configure_parse_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun IdleCard(vm: ConfigureViewModel, ui: ConfigureViewModel.UiState) {
    val ctx = LocalContext.current
    var prefix by remember { mutableStateOf(ui.ssidPrefix) }
    var permRationale by remember { mutableStateOf<String?>(null) }

    val requiredPermission: String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    fun launchConnect() {
        vm.updateApSsid(prefix, exact = null, passphrase = null)
        vm.connect()
    }

    val permDeniedMsg = stringResource(R.string.configure_permission_denied)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchConnect()
        else permRationale = permDeniedMsg
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.configure_auto_pair_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.configure_auto_pair_instructions),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text(stringResource(R.string.configure_ap_ssid_prefix_label)) },
                supportingText = { Text(stringResource(R.string.configure_ap_ssid_prefix_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val perm = requiredPermission
                if (perm != null &&
                    ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(perm)
                } else {
                    launchConnect()
                }
            }) { Text(stringResource(R.string.configure_btn_connect)) }
            permRationale?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ConnectedCard(
    vm: ConfigureViewModel,
    ui: ConfigureViewModel.UiState,
    networks: List<ScannedAp>
) {
    val form = ui.form

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.configure_connected_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.configure_pick_home_network),
                style = MaterialTheme.typography.bodySmall
            )

            if (networks.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(18.dp))
                    Text(stringResource(R.string.configure_scanning), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                NetworkList(
                    networks = networks,
                    selected = form.homeSsid,
                    onPick = { vm.updateForm { f -> f.copy(homeSsid = it) } }
                )
            }

            OutlinedTextField(
                value = form.homeSsid,
                onValueChange = { vm.updateForm { f -> f.copy(homeSsid = it) } },
                label = { Text(stringResource(R.string.configure_home_ssid_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.homePassword,
                onValueChange = { vm.updateForm { f -> f.copy(homePassword = it) } },
                label = { Text(stringResource(R.string.configure_home_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.configure_server_description),
                style = MaterialTheme.typography.bodySmall
            )
            val phoneDefault = ui.homeIpSnapshot
            val pointsAtPhone = phoneDefault != null && form.serverHost == phoneDefault &&
                    form.serverPort == com.ispindle.plotter.network.IspindleHttpServer.DEFAULT_PORT &&
                    form.serverPath == "/"
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { vm.applyPhoneDefaults() },
                    enabled = phoneDefault != null && !pointsAtPhone
                ) {
                    Text(
                        if (pointsAtPhone) stringResource(R.string.configure_pointing_at_phone)
                        else stringResource(R.string.configure_use_this_phone_ip)
                    )
                }
                if (phoneDefault == null) {
                    Text(
                        stringResource(R.string.configure_no_home_ip),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            HostnameRow(vm = vm, ui = ui)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.serverHost.orEmpty(),
                    onValueChange = { vm.updateForm { f -> f.copy(serverHost = it) } },
                    label = { Text(stringResource(R.string.configure_server_host_label)) },
                    singleLine = true,
                    modifier = Modifier.width(180.dp)
                )
                OutlinedTextField(
                    value = form.serverPort?.toString().orEmpty(),
                    onValueChange = { v -> vm.updateForm { f -> f.copy(serverPort = v.toIntOrNull()) } },
                    label = { Text(stringResource(R.string.configure_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
            }
            OutlinedTextField(
                value = form.serverPath.orEmpty(),
                onValueChange = { vm.updateForm { f -> f.copy(serverPath = it) } },
                label = { Text(stringResource(R.string.configure_uri_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ServiceTypeDropdown(
                selected = IspindleService.fromSelApi(form.serviceTypeIndex),
                onSelected = { svc ->
                    vm.updateForm { it.copy(serviceTypeIndex = svc.selApi) }
                }
            )

            OutlinedTextField(
                value = form.sleepSeconds?.toString().orEmpty(),
                onValueChange = { v -> vm.updateForm { f -> f.copy(sleepSeconds = v.toIntOrNull()) } },
                label = { Text(stringResource(R.string.configure_sleep_interval_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.deviceName.orEmpty(),
                onValueChange = { vm.updateForm { f -> f.copy(deviceName = it) } },
                label = { Text(stringResource(R.string.configure_device_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }) { Text(stringResource(R.string.configure_btn_save_reboot)) }
                OutlinedButton(onClick = { vm.reset() }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

@Composable
private fun HostnameRow(vm: ConfigureViewModel, ui: ConfigureViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { vm.probeHostname() },
                enabled = ui.homeIpSnapshot != null &&
                        ui.hostnameProbe !is ConfigureViewModel.HostnameProbe.Probing
            ) { Text(stringResource(R.string.configure_btn_find_hostname)) }
            when (val p = ui.hostnameProbe) {
                ConfigureViewModel.HostnameProbe.Idle -> Text(
                    stringResource(R.string.configure_hostname_idle_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConfigureViewModel.HostnameProbe.Probing -> Text(
                    stringResource(R.string.configure_hostname_resolving),
                    style = MaterialTheme.typography.labelSmall
                )
                ConfigureViewModel.HostnameProbe.NotFound -> Text(
                    stringResource(R.string.configure_hostname_not_found),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                is ConfigureViewModel.HostnameProbe.Unique -> Text(
                    p.hostname,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                is ConfigureViewModel.HostnameProbe.Ambiguous -> Text(
                    stringResource(R.string.configure_hostname_ambiguous, p.hostname, p.resolvesTo.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        when (val p = ui.hostnameProbe) {
            is ConfigureViewModel.HostnameProbe.Unique -> {
                Button(onClick = { vm.applyHostnameAsServer(p.hostname) }) {
                    Text(stringResource(R.string.configure_btn_use_hostname, p.hostname))
                }
            }
            is ConfigureViewModel.HostnameProbe.Ambiguous -> {
                Text(
                    stringResource(
                        R.string.configure_hostname_other_ips,
                        p.resolvesTo.filter { it != ui.homeIpSnapshot }.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceTypeDropdown(
    selected: IspindleService,
    onSelected: (IspindleService) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isHttp = selected == IspindleService.GenericHttp || selected == IspindleService.GenericHttps
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = "${selected.label} (selAPI=${selected.selApi})",
                onValueChange = { /* read-only */ },
                readOnly = true,
                label = { Text(stringResource(R.string.configure_service_type_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                IspindleService.entries.forEach { svc ->
                    DropdownMenuItem(
                        text = { Text("${svc.label} (selAPI=${svc.selApi})") },
                        onClick = {
                            onSelected(svc)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (!isHttp) {
            Text(
                stringResource(R.string.configure_http_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun NetworkList(
    networks: List<ScannedAp>,
    selected: String,
    onPick: (String) -> Unit
) {
    // Plain Column — the screen above is already verticalScroll, and Compose
    // forbids nesting LazyColumn inside a vertically scrollable parent.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp)) {
            networks.forEach { ap ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(ap.ssid) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (ap.encrypted) Icons.Default.Lock else Icons.Default.Wifi,
                        contentDescription = null
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            ap.ssid + if (ap.ssid == selected) "  ✓" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val encryptedSuffix = if (ap.encrypted)
                            stringResource(R.string.configure_signal_wpa2)
                        else
                            stringResource(R.string.configure_signal_open)
                        Text(
                            stringResource(R.string.configure_signal_quality, ap.quality) + encryptedSuffix,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    showSpinner: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSpinner) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(18.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            onCancel?.let {
                OutlinedButton(onClick = it) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun LiveReadingCard(reading: LiveReading) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.configure_live_reading_title), style = MaterialTheme.typography.titleMedium)
            reading.tiltDeg?.let { Text(stringResource(R.string.configure_live_tilt, "%.2f".format(it)), fontFamily = FontFamily.Monospace) }
            reading.temperature?.let { Text(stringResource(R.string.configure_live_temperature, "%.2f".format(it)), fontFamily = FontFamily.Monospace) }
            reading.batteryV?.let { Text(stringResource(R.string.configure_live_battery, "%.2f".format(it)), fontFamily = FontFamily.Monospace) }
            reading.gravity?.let { Text(stringResource(R.string.configure_live_gravity, "%.4f".format(it)), fontFamily = FontFamily.Monospace) }
        }
    }
}
