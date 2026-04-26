package com.ispindle.plotter

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.ispindle.plotter.network.IspindleServerService
import com.ispindle.plotter.ui.AppRoot
import com.ispindle.plotter.ui.MainViewModel
import com.ispindle.plotter.ui.theme.IspindleTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels {
        MainViewModel.Factory(application as IspindleApp)
    }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Auto-start the HTTP server unless the user previously tapped Stop.
        // Activity is in the foreground here, so the FGS launch is allowed
        // even on Android 12+'s background-restriction regime.
        IspindleServerService.startIfEnabled(this)

        val app = application as IspindleApp
        setContent {
            IspindleTheme {
                AppRoot(app = app, vm = vm)
            }
        }
    }
}
