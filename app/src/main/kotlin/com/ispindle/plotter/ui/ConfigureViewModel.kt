package com.ispindle.plotter.ui

import android.content.Context
import android.net.Network
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.ispindle.plotter.IspindleApp
import com.ispindle.plotter.calibration.CubicParser
import com.ispindle.plotter.data.PendingCalibration
import com.ispindle.plotter.network.ConfigForm
import com.ispindle.plotter.network.IspindleApBinder
import com.ispindle.plotter.network.IspindleConfigClient
import com.ispindle.plotter.network.IspindleHttpServer
import com.ispindle.plotter.network.IspindleService
import com.ispindle.plotter.network.LiveReading
import com.ispindle.plotter.network.NetworkUtils
import com.ispindle.plotter.network.ScannedAp
import com.ispindle.plotter.network.StateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State machine for the "Configure iSpindle" flow:
 *
 *   Idle ──connect──▶ Joining ──onAvailable──▶ Connected
 *                 \                          ╱
 *                  ▼                        ▼
 *                 Failed              ScanLoaded ──save──▶ Saving ──▶ Joined / Timeout
 *
 * Every transition carries the live reading where available so the UI can
 * reassure the user that the iSpindle is still talking.
 */
class ConfigureViewModel(
    private val appContext: Context
) : ViewModel() {

    sealed class HostnameProbe {
        data object Idle : HostnameProbe()
        data object Probing : HostnameProbe()
        /** Reverse DNS gave a single hostname that round-trips to our IP. */
        data class Unique(val hostname: String) : HostnameProbe()
        /** Reverse DNS gave a name, but forward returned more than one IP. */
        data class Ambiguous(val hostname: String, val resolvesTo: List<String>) : HostnameProbe()
        /** Reverse DNS produced no usable name. */
        data object NotFound : HostnameProbe()
    }

    sealed class Phase {
        data object Idle : Phase()
        data object Joining : Phase()
        data class Connected(val state: StateInfo) : Phase()
        data class ScanLoaded(val state: StateInfo, val networks: List<ScannedAp>) : Phase()
        data object Saving : Phase()
        data class Joined(val homeSsid: String, val deviceIp: String) : Phase()
        data object SaveTimeout : Phase()
        data class Failed(val message: String) : Phase()
        data object Unsupported : Phase()
    }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val live: LiveReading? = null,
        val form: ConfigForm = ConfigForm(homeSsid = "", homePassword = ""),
        val ssidPrefix: String = "iSpindel",
        val exactSsid: String? = null,
        val apPassphrase: String? = null,
        // Captured before the WiFi specifier joins the iSpindle AP, so it
        // still reflects the home-network address. Once we're on the AP,
        // NetworkUtils returns 192.168.4.x which the iSpindle can't reach.
        val homeIpSnapshot: String? = null,
        val firmwarePolynomial: String? = null,
        val parsedCoeffs: DoubleArray? = null,
        val polynomialImported: Boolean = false,
        // Snapshot of every form field on the iSpindle, captured at
        // connect-time and echoed back unchanged in /wifisave so the
        // firmware doesn't blank fields we don't explicitly resend.
        val firmwareFields: Map<String, String> = emptyMap(),
        // Result of the reverse-DNS suggestion ↔ forward-DNS sanity check.
        val hostnameProbe: HostnameProbe = HostnameProbe.Idle
    ) {
        override fun equals(other: Any?): Boolean = other is UiState &&
                phase == other.phase && live == other.live && form == other.form &&
                ssidPrefix == other.ssidPrefix && exactSsid == other.exactSsid &&
                apPassphrase == other.apPassphrase && homeIpSnapshot == other.homeIpSnapshot &&
                firmwarePolynomial == other.firmwarePolynomial &&
                (parsedCoeffs?.contentEquals(other.parsedCoeffs) ?: (other.parsedCoeffs == null)) &&
                polynomialImported == other.polynomialImported

        override fun hashCode(): Int {
            var r = phase.hashCode()
            r = 31 * r + (live?.hashCode() ?: 0)
            r = 31 * r + form.hashCode()
            r = 31 * r + ssidPrefix.hashCode()
            r = 31 * r + (exactSsid?.hashCode() ?: 0)
            r = 31 * r + (apPassphrase?.hashCode() ?: 0)
            r = 31 * r + (homeIpSnapshot?.hashCode() ?: 0)
            r = 31 * r + (firmwarePolynomial?.hashCode() ?: 0)
            r = 31 * r + (parsedCoeffs?.contentHashCode() ?: 0)
            r = 31 * r + polynomialImported.hashCode()
            return r
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bindJob: Job? = null
    private var liveJob: Job? = null
    private var network: Network? = null
    private var client: IspindleConfigClient? = null

    fun updateForm(transform: (ConfigForm) -> ConfigForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun updateApSsid(prefix: String?, exact: String?, passphrase: String?) {
        _state.update {
            it.copy(
                ssidPrefix = prefix ?: it.ssidPrefix,
                exactSsid = exact?.takeIf { v -> v.isNotBlank() },
                apPassphrase = passphrase?.takeIf { v -> v.isNotBlank() }
            )
        }
    }

    fun connect() {
        bindJob?.cancel()
        liveJob?.cancel()
        // Snapshot the home-network IP *before* binding to the iSpindle AP.
        // After the bind, wlan0 reports the AP's subnet and we lose the
        // home address we want the iSpindle to POST to.
        val homeIp = NetworkUtils.preferredIpv4()
        _state.update { it.copy(phase = Phase.Joining, live = null, homeIpSnapshot = homeIp) }

        val ui = _state.value
        bindJob = viewModelScope.launch {
            IspindleApBinder.connect(
                context = appContext,
                ssidPrefix = ui.ssidPrefix,
                exactSsid = ui.exactSsid,
                passphrase = ui.apPassphrase
            ).collect { ev ->
                when (ev) {
                    IspindleApBinder.State.Requesting -> { /* shown as Joining */ }
                    IspindleApBinder.State.Unsupported ->
                        _state.update { it.copy(phase = Phase.Unsupported) }
                    is IspindleApBinder.State.Lost ->
                        _state.update { it.copy(phase = Phase.Failed(ev.reason)) }
                    is IspindleApBinder.State.Connected -> onApConnected(ev.network)
                }
            }
        }
    }

    private fun onApConnected(net: Network) {
        network = net
        val c = IspindleConfigClient(net)
        client = c
        viewModelScope.launch {
            try {
                val st = c.getState()
                val pre = prefilledForm(st)
                _state.update { it.copy(phase = Phase.Connected(st), form = pre) }
                // Kick off scan, live reading, and polynomial fetch in parallel.
                launch { loadScan() }
                launch { loadPolynomial() }
                startLiveLoop(c)
            } catch (t: Throwable) {
                Log.e(TAG, "post-connect probe failed", t)
                _state.update { it.copy(phase = Phase.Failed("Joined AP but iSpindle did not respond: ${t.message}")) }
            }
        }
    }

    private suspend fun loadPolynomial() {
        val c = client ?: return
        try {
            val fields = c.getCurrentFormFields()
            val raw = fields["POLYN"]?.takeIf { it.isNotBlank() }
            val parsed = raw?.let { CubicParser.parse(it) }
            _state.update {
                it.copy(
                    firmwareFields = fields,
                    firmwarePolynomial = raw,
                    parsedCoeffs = parsed,
                    polynomialImported = false
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "polynomial fetch failed: ${t.message}")
        }
    }

    /**
     * Stores the parsed coefficients in [IspindleApp.pendingCalibration] so
     * the next ingest from this iSpindle (after it joins home WiFi) adopts
     * them as that device's calibration. No-op if parsing failed.
     */
    fun importFirmwareCalibration() {
        val ui = _state.value
        val coeffs = ui.parsedCoeffs ?: return
        val raw = ui.firmwarePolynomial ?: return
        val app = appContext as? IspindleApp ?: return
        val degree = CubicParser.degree(coeffs)
        app.pendingCalibration.set(
            PendingCalibration(
                coeffs = coeffs,
                degree = degree,
                rawExpression = raw,
                capturedMs = System.currentTimeMillis()
            )
        )
        _state.update { it.copy(polynomialImported = true) }
    }

    private fun prefilledForm(state: StateInfo): ConfigForm {
        // Prefer the snapshot taken before binding to the AP, since
        // NetworkUtils.preferredIpv4() now returns the iSpindle subnet.
        val phoneIp = _state.value.homeIpSnapshot ?: NetworkUtils.preferredIpv4()
        val current = _state.value.form
        return current.copy(
            serverHost = current.serverHost?.takeUnless { it.isBlank() } ?: phoneIp,
            serverPort = current.serverPort ?: IspindleHttpServer.DEFAULT_PORT,
            serverPath = current.serverPath?.takeUnless { it.isBlank() } ?: "/",
            sleepSeconds = current.sleepSeconds ?: 900,
            serviceTypeIndex = current.serviceTypeIndex ?: IspindleService.GenericHttp.selApi
        )
    }

    /**
     * Resets the server-side fields to point at this phone — what the user
     * almost always wants, restorable in one tap if they edit by accident.
     */
    fun applyPhoneDefaults() {
        val phoneIp = _state.value.homeIpSnapshot ?: NetworkUtils.preferredIpv4()
        _state.update {
            it.copy(
                form = it.form.copy(
                    serverHost = phoneIp,
                    serverPort = IspindleHttpServer.DEFAULT_PORT,
                    serverPath = "/",
                    serviceTypeIndex = IspindleService.GenericHttp.selApi
                )
            )
        }
    }

    /**
     * Reverse-resolves the snapshotted home IP and forward-checks the
     * result. If the round-trip yields a single IP that matches ours, we
     * have a stable name (e.g. `Pixel-8-Pro.fritz.box`) the iSpindle can
     * use across phone IP changes. The probe runs off-main because Java's
     * resolver blocks on UDP.
     */
    fun probeHostname() {
        val phoneIp = _state.value.homeIpSnapshot ?: NetworkUtils.preferredIpv4()
        if (phoneIp == null) {
            _state.update { it.copy(hostnameProbe = HostnameProbe.NotFound) }
            return
        }
        _state.update { it.copy(hostnameProbe = HostnameProbe.Probing) }
        viewModelScope.launch(Dispatchers.IO) {
            val name = NetworkUtils.reverseLookup(phoneIp)
            val outcome = if (name == null) {
                HostnameProbe.NotFound
            } else {
                val ips = NetworkUtils.forwardLookupIpv4(name)
                when {
                    ips.size == 1 && ips[0] == phoneIp -> HostnameProbe.Unique(name)
                    phoneIp in ips -> HostnameProbe.Ambiguous(name, ips)
                    else -> HostnameProbe.NotFound
                }
            }
            _state.update { it.copy(hostnameProbe = outcome) }
        }
    }

    fun applyHostnameAsServer(hostname: String) {
        _state.update { it.copy(form = it.form.copy(serverHost = hostname)) }
    }

    private suspend fun loadScan() {
        val c = client ?: return
        try {
            val nets = c.scanNetworks()
            val current = _state.value
            val phase = current.phase
            if (phase is Phase.Connected) {
                _state.update { it.copy(phase = Phase.ScanLoaded(phase.state, nets)) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan failed: ${t.message}")
        }
    }

    private fun startLiveLoop(c: IspindleConfigClient) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (isActive) {
                try {
                    _state.update { it.copy(live = c.getLiveReading()) }
                } catch (_: Throwable) { /* expected as we tear down */ }
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    fun save() {
        val ui = _state.value
        val c = client ?: return
        val form = ui.form
        if (form.homeSsid.isBlank()) {
            _state.update { it.copy(phase = Phase.Failed("Pick a home network SSID first")) }
            return
        }
        liveJob?.cancel()
        _state.update { it.copy(phase = Phase.Saving) }
        val baseline = ui.firmwareFields
        viewModelScope.launch {
            runCatching { c.saveConfig(form, baseline) }
                .onFailure {
                    Log.w(TAG, "saveConfig threw (often expected — device reboots): ${it.message}")
                }
            // Whether or not the save HTTP call returns cleanly, the iSpindle
            // is now rebooting and will join the home network. Poll /state.
            val joined = runCatching { c.pollUntilJoined(form.homeSsid) }.getOrDefault(false)
            if (joined) {
                val finalState = runCatching { c.getState() }.getOrNull()
                _state.update {
                    it.copy(
                        phase = Phase.Joined(
                            homeSsid = form.homeSsid,
                            deviceIp = finalState?.stationIp.orEmpty()
                        )
                    )
                }
            } else {
                _state.update { it.copy(phase = Phase.SaveTimeout) }
            }
            // Either way we're done with the AP.
            disconnect()
        }
    }

    fun disconnect() {
        liveJob?.cancel(); liveJob = null
        bindJob?.cancel(); bindJob = null
        network = null
        client = null
    }

    fun reset() {
        disconnect()
        _state.value = UiState()
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    class Factory(private val appContext: Context) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConfigureViewModel(appContext) as T
    }

    companion object {
        private const val TAG = "ConfigureViewModel"
    }
}
