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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.network.IspindleHttpServer
import com.ispindle.plotter.network.NetworkUtils

@Composable
fun SetupScreen(padding: PaddingValues, onAutoConfigure: () -> Unit = {}) {
    val ctx = LocalContext.current
    val ip = NetworkUtils.preferredIpv4() ?: "<not connected>"
    val port = IspindleHttpServer.DEFAULT_PORT
    val canCopy = ip != "<not connected>"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Pairing your iSpindle", style = MaterialTheme.typography.titleLarge)

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto-configure (Android 10+)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Drives the iSpindle's config portal directly: joins its AP, scans your home networks, " +
                            "submits the form, and waits for the device to come online. Skips the manual browser step entirely.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onAutoConfigure) { Text("Configure iSpindle now") }
            }
        }

        Text("Or do it manually:", style = MaterialTheme.typography.titleMedium)

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MTB iSpindel PCB 4.0 runs the standard iSpindel firmware on an ESP8266. " +
                        "There is no Bluetooth — readings come in over WiFi as an HTTP POST.")
                Text(
                    text = "This phone's current IP: $ip",
                    fontFamily = FontFamily.Monospace,
                    textDecoration = if (canCopy) TextDecoration.Underline else TextDecoration.None,
                    modifier = if (canCopy) Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            copyToClipboard(ctx, "iSpindle host IP", ip)
                        } else Modifier
                )
                Text("Server port: $port", fontFamily = FontFamily.Monospace)
                if (canCopy) {
                    Text(
                        "Tap the IP to copy it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Steps", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Step(1, "On the Home tab, tap Start to bring up the HTTP server. A persistent notification confirms it is listening.")
                Step(2, "Boot the iSpindle into config mode: hold it horizontally for ~20 seconds at power-on. It raises an AP named \"iSpindel\".")
                Step(3, "Connect this phone to the \"iSpindel\" WiFi AP and open http://192.168.4.1/ in a browser to load the config portal.")
                Step(4, "In the portal: set WiFi credentials for your home network, set \"Service Type\" = HTTP, \"Server Address\" = $ip, \"Server Port\" = $port, and a short sample interval (60 s works for testing).")
                Step(5, "Save. The iSpindle reboots, joins your home network, wakes, POSTs JSON, then sleeps. Reconnect this phone to the same home network.")
                Step(6, "Within one interval the Home tab shows the first reading. Open the Devices tab to see it listed, then Calibrate and Graph.")
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Calibration workflow", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("Calibration turns tilt angle into specific gravity. You need at least 3 reference solutions for a quadratic fit.")
                Step(1, "Pure water at 20 °C = SG 1.000. Let the iSpindle stabilise, read the angle from Home, and add it as (angle, 1.000) on the Calibrate tab.")
                Step(2, "Mix a sugar+water solution, measure SG with a refractometer or hydrometer, float the iSpindle, record (angle, SG).")
                Step(3, "Repeat for 2–3 more reference points across the expected SG range (1.000 → 1.080).")
                Step(4, "Tap \"Fit & save\". R² should be > 0.999 for a clean fit. Future readings auto-populate SG.")
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Troubleshooting", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("• Phone and iSpindle must be on the same subnet. Check your router's guest-network isolation.")
                Text("• If the phone's IP changes (DHCP lease expiry), re-enter the new IP in the iSpindle portal.")
                Text("• The iSpindle deep-sleeps between intervals; you will not be able to ping it mid-cycle.")
                Text("• For a static IP, reserve the phone's MAC on your router.")
            }
        }
    }
}

@Composable
private fun Step(n: Int, body: String) {
    Text("$n. $body", style = MaterialTheme.typography.bodyMedium)
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(ctx, "Copied $text", Toast.LENGTH_SHORT).show()
    }
}
