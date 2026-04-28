package com.ispindle.plotter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ispindle.plotter.R
import com.ispindle.plotter.network.IspindleHttpServer
import com.ispindle.plotter.network.NetworkUtils
import com.ispindle.plotter.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SetupScreen(
    padding: PaddingValues,
    vm: MainViewModel? = null,
    onAutoConfigure: () -> Unit = {}
) {
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
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.setup_auto_configure_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setup_auto_configure_description),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onAutoConfigure) { Text(stringResource(R.string.setup_btn_configure_now)) }
            }
        }

        Text(stringResource(R.string.setup_or_manually), style = MaterialTheme.typography.titleMedium)

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.setup_manual_intro))
                Text(
                    text = stringResource(R.string.setup_this_phone_ip, ip),
                    fontFamily = FontFamily.Monospace,
                    textDecoration = if (canCopy) TextDecoration.Underline else TextDecoration.None,
                    modifier = if (canCopy) Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            copyToClipboard(ctx, "iSpindle host IP", ip)
                        } else Modifier
                )
                Text(stringResource(R.string.setup_server_port, port.toString()), fontFamily = FontFamily.Monospace)
                if (canCopy) {
                    Text(
                        stringResource(R.string.setup_tap_ip_to_copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.setup_steps_title), style = MaterialTheme.typography.titleMedium)
                Step(1, stringResource(R.string.setup_step_1))
                Step(2, stringResource(R.string.setup_step_2))
                Step(3, stringResource(R.string.setup_step_3))
                Step(4, stringResource(R.string.setup_step_4, ip, port.toString()))
                Step(5, stringResource(R.string.setup_step_5))
                Step(6, stringResource(R.string.setup_step_6))
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.setup_calibration_workflow_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.setup_calibration_intro))
                Step(1, stringResource(R.string.setup_cal_step_1))
                Step(2, stringResource(R.string.setup_cal_step_2))
                Step(3, stringResource(R.string.setup_cal_step_3))
                Step(4, stringResource(R.string.setup_cal_step_4))
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.setup_stable_address_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setup_stable_address_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                Step(1, stringResource(R.string.setup_stable_step_1))
                Step(2, stringResource(R.string.setup_stable_step_2))
                Step(3, stringResource(R.string.setup_stable_step_3))
            }
        }

        if (vm != null) BackupRestoreCard(vm)

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.setup_troubleshooting_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.setup_trouble_same_subnet))
                Text(stringResource(R.string.setup_trouble_dhcp))
                Text(stringResource(R.string.setup_trouble_deep_sleep))
                Text(stringResource(R.string.setup_trouble_static_ip))
            }
        }
    }
}

@Composable
private fun BackupRestoreCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.exportSettingsJson()
            val deviceCount = (org.json.JSONObject(json)
                .optJSONArray("devices")?.length()) ?: 0
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast = if (ok) ctx.getString(R.string.setup_backup_succeeded, deviceCount)
                    else ctx.getString(R.string.setup_backup_failed)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            if (json == null) {
                toast = ctx.getString(R.string.graph_import_read_failed)
                return@launch
            }
            val result = vm.importSettingsJson(json)
            toast = if (result.error != null)
                ctx.getString(R.string.setup_restore_failed, result.error)
            else
                ctx.getString(R.string.setup_restore_succeeded, result.inserted)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_backup_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.setup_backup_intro),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    saveLauncher.launch("ispindle-plotter-settings-$stamp.json")
                }) { Text(stringResource(R.string.setup_btn_backup)) }
                OutlinedButton(onClick = {
                    restoreLauncher.launch(arrayOf("application/json", "*/*"))
                }) { Text(stringResource(R.string.setup_btn_restore)) }
            }
            toast?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Toast.makeText(ctx, ctx.getString(R.string.setup_copied_toast, text), Toast.LENGTH_SHORT).show()
    }
}
