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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
                title = "Joining iSpindle AP…",
                body = "Approve the system dialog when it appears. The first time can take 10–20 s.",
                showSpinner = true,
                onCancel = { vm.reset() }
            )
            ConfigureViewModel.Phase.Unsupported -> StatusCard(
                title = "Not supported on this device",
                body = "Programmatic WiFi join requires Android 10 (API 29) or newer.",
                onCancel = onBack
            )
            is ConfigureViewModel.Phase.Failed -> StatusCard(
                title = "Couldn't connect",
                body = phase.message,
                onCancel = { vm.reset() }
            )
            is ConfigureViewModel.Phase.Connected -> ConnectedCard(vm, ui, networks = emptyList())
            is ConfigureViewModel.Phase.ScanLoaded -> ConnectedCard(vm, ui, networks = phase.networks)
            ConfigureViewModel.Phase.Saving -> StatusCard(
                title = "Saving and waiting for iSpindle to join…",
                body = "The device reboots and joins your home network. Poll runs every 1.5 s for up to 45 s.",
                showSpinner = true
            )
            is ConfigureViewModel.Phase.Joined -> StatusCard(
                title = "iSpindle joined ${phase.homeSsid}",
                body = "Station IP: ${phase.deviceIp.ifBlank { "(not yet known)" }}\n\n" +
                        "Reconnect this phone to the same home WiFi to receive readings.",
                onCancel = {
                    vm.reset()
                    onBack()
                }
            )
            ConfigureViewModel.Phase.SaveTimeout -> StatusCard(
                title = "Save submitted, but join not confirmed",
                body = "The iSpindle accepted the form but did not appear on the home network within the polling window. " +
                        "It may still join after a longer settle time. Reconnect this phone to home WiFi and check the Home tab.",
                onCancel = {
                    vm.reset()
                    onBack()
                }
            )
        }

        ui.live?.takeIf { it.tiltDeg != null || it.batteryV != null }?.let { LiveReadingCard(it) }
    }
}

@Composable
private fun IdleCard(vm: ConfigureViewModel, ui: ConfigureViewModel.UiState) {
    val ctx = LocalContext.current
    var prefix by remember { mutableStateOf(ui.ssidPrefix) }
    var exact by remember { mutableStateOf(ui.exactSsid.orEmpty()) }
    var passphrase by remember { mutableStateOf(ui.apPassphrase.orEmpty()) }
    var permRationale by remember { mutableStateOf<String?>(null) }

    val requiredPermission: String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.updateApSsid(prefix, exact, passphrase)
            vm.connect()
        } else {
            permRationale = "WiFi-scan permission was declined. Auto-pair can't proceed without it."
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-pair with iSpindle", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Hold the iSpindle horizontally at power-on for ~20 s — it raises an AP named iSpindel_<chipID>.\n" +
                        "2. Tap Connect. Android will ask you to approve joining its WiFi.\n" +
                        "3. Pick your home network, enter the password, save. The iSpindle reboots and joins.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text("AP SSID prefix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = exact,
                onValueChange = { exact = it },
                label = { Text("Exact SSID (optional, overrides prefix)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("AP passphrase (blank for open)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val perm = requiredPermission
                if (perm != null &&
                    ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(perm)
                } else {
                    vm.updateApSsid(prefix, exact, passphrase)
                    vm.connect()
                }
            }) { Text("Connect") }
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
            Text("Connected to iSpindle", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick the home network you want the iSpindle to join, then save.",
                style = MaterialTheme.typography.bodySmall
            )

            if (networks.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(18.dp))
                    Text("  Scanning…", style = MaterialTheme.typography.bodySmall)
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
                label = { Text("Home WiFi SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.homePassword,
                onValueChange = { vm.updateForm { f -> f.copy(homePassword = it) } },
                label = { Text("Home WiFi password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.serverHost.orEmpty(),
                    onValueChange = { vm.updateForm { f -> f.copy(serverHost = it) } },
                    label = { Text("Server host") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp)
                )
                OutlinedTextField(
                    value = form.serverPort?.toString().orEmpty(),
                    onValueChange = { v -> vm.updateForm { f -> f.copy(serverPort = v.toIntOrNull()) } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
            }
            OutlinedTextField(
                value = form.serverPath.orEmpty(),
                onValueChange = { vm.updateForm { f -> f.copy(serverPath = it) } },
                label = { Text("URI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.sleepSeconds?.toString().orEmpty(),
                onValueChange = { v -> vm.updateForm { f -> f.copy(sleepSeconds = v.toIntOrNull()) } },
                label = { Text("Sleep interval (s)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.deviceName.orEmpty(),
                onValueChange = { vm.updateForm { f -> f.copy(deviceName = it) } },
                label = { Text("Device name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }) { Text("Save & reboot") }
                OutlinedButton(onClick = { vm.reset() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun NetworkList(
    networks: List<ScannedAp>,
    selected: String,
    onPick: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            items(networks, key = { it.ssid }) { ap ->
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
                        Text(
                            "Signal ${ap.quality}%${if (ap.encrypted) " · WPA2" else " · open"}",
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
                OutlinedButton(onClick = it) { Text("Close") }
            }
        }
    }
}

@Composable
private fun LiveReadingCard(reading: LiveReading) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Live reading from iSpindle", style = MaterialTheme.typography.titleMedium)
            reading.tiltDeg?.let { Text("Tilt: %.2f°".format(it), fontFamily = FontFamily.Monospace) }
            reading.temperature?.let { Text("Temperature: %.2f".format(it), fontFamily = FontFamily.Monospace) }
            reading.batteryV?.let { Text("Battery: %.2f V".format(it), fontFamily = FontFamily.Monospace) }
            reading.gravity?.let { Text("Gravity: %.4f".format(it), fontFamily = FontFamily.Monospace) }
        }
    }
}
