package com.example.wgautotoggle

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var trustedNetworksRepository: TrustedNetworksRepository
    private lateinit var networkAdapter: NetworkAdapter
    private lateinit var connectivityManager: ConnectivityManager

    private lateinit var configEditText: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDot: View
    private lateinit var ssidEditText: EditText

    private var uiCallbackRegistered = false

    // Aggiorna la schermata in tempo reale ad ogni cambio di rete, senza
    // bisogno di chiudere e riaprire l'app o toccare un pulsante.
    private val uiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { updateStatus() }
        override fun onLost(network: Network) = runOnUiThread { updateStatus() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            runOnUiThread { updateStatus() }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startMonitoring()
        } else {
            enabledSwitch.isChecked = false
            Toast.makeText(this, "Permesso VPN negato", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            proceedEnabling()
        } else {
            enabledSwitch.isChecked = false
            Toast.makeText(
                this,
                "Servono i permessi per leggere il nome della rete Wi-Fi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val openConfigLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            }.getOrNull()?.let { text -> configEditText.setText(text) }
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            configEditText.setText(result.contents)
            expandConfigSection()
            Toast.makeText(this, "QR letto: controlla il testo e tocca Salva", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Scansione annullata", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tunnelRepository = TunnelRepository.getInstance(applicationContext)
        trustedNetworksRepository = TrustedNetworksRepository(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        configEditText = findViewById(R.id.configEditText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statusText = findViewById(R.id.statusText)
        statusTitle = findViewById(R.id.statusTitle)
        statusDot = findViewById(R.id.statusDot)
        ssidEditText = findViewById(R.id.ssidEditText)

        applyStyling()

        configEditText.setText(tunnelRepository.getConfigText().orEmpty())
        enabledSwitch.isChecked = tunnelRepository.isServiceEnabled()

        if (!tunnelRepository.hasValidConfig()) {
            expandConfigSection()
        }
        findViewById<View>(R.id.configHeader).setOnClickListener {
            val configSection = findViewById<View>(R.id.configSection)
            val expanded = configSection.visibility == View.VISIBLE
            if (expanded) collapseConfigSection() else expandConfigSection()
        }

        findViewById<Button>(R.id.saveConfigButton).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.importConfigButton).setOnClickListener {
            openConfigLauncher.launch("*/*")
        }
        findViewById<Button>(R.id.scanQrButton).setOnClickListener { scanQrCode() }
        findViewById<Button>(R.id.excludeAppsButton).setOnClickListener {
            startActivity(Intent(this, AppExclusionActivity::class.java))
        }
        findViewById<Button>(R.id.addSsidButton).setOnClickListener { addSsidFromInput() }
        findViewById<Button>(R.id.useCurrentSsidButton).setOnClickListener { useCurrentSsid() }
        findViewById<Button>(R.id.manualUpButton).setOnClickListener { manualSetState(Tunnel.State.UP) }
        findViewById<Button>(R.id.manualDownButton).setOnClickListener { manualSetState(Tunnel.State.DOWN) }
        findViewById<TextView>(R.id.diagnosticButton).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.requestBatteryButton).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.openAppSettingsButton).setOnClickListener { openAppSettings() }

        val recyclerView = findViewById<RecyclerView>(R.id.networksRecyclerView)
        networkAdapter = NetworkAdapter(trustedNetworksRepository.getAll().toMutableList()) { ssid ->
            trustedNetworksRepository.remove(ssid)
            networkAdapter.submitList(trustedNetworksRepository.getAll())
            refreshServiceIfEnabled()
            updateStatus()
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = networkAdapter

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestEnable() else disableService()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()

        // Forza una verifica immediata del servizio in background: così se il
        // telefono è stato in stand-by e lo stato si era disallineato (es. dal
        // risparmio energetico del produttore), riaprendo l'app si corregge
        // subito, senza aspettare il controllo periodico.
        if (tunnelRepository.isServiceEnabled()) {
            WifiMonitorService.start(this)
        }

        if (!uiCallbackRegistered) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { connectivityManager.registerNetworkCallback(request, uiNetworkCallback) }
            uiCallbackRegistered = true
        }

        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        if (uiCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(uiNetworkCallback) }
            uiCallbackRegistered = false
        }
    }

    // ---- Stile (sfondi arrotondati creati a runtime, niente drawable XML) ----

    private fun pill(colorRes: Int, radiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
        }
    }

    private fun outlineBackground(strokeColorRes: Int, radiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setStroke((1.2f * density).toInt(), ContextCompat.getColor(this@MainActivity, strokeColorRes))
            setColor(Color.TRANSPARENT)
        }
    }

    private fun applyStyling() {
        findViewById<View>(R.id.statusCard).background = pill(R.color.bg_surface, 18f)
        findViewById<View>(R.id.switchCard).background = pill(R.color.bg_surface, 18f)
        findViewById<View>(R.id.networksCard).background = pill(R.color.bg_surface, 18f)
        findViewById<View>(R.id.configSection).background = pill(R.color.bg_surface, 18f)
        findViewById<View>(R.id.batteryCard).background = pill(R.color.bg_surface, 18f)

        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, R.color.accent_idle))
        }

        findViewById<EditText>(R.id.ssidEditText).background = pill(R.color.bg_surface_alt, 10f)
        findViewById<EditText>(R.id.configEditText).background = pill(R.color.bg_surface_alt, 10f)

        findViewById<Button>(R.id.saveConfigButton).apply {
            background = pill(R.color.accent_protected, 12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_base))
        }
        findViewById<Button>(R.id.addSsidButton).apply {
            background = pill(R.color.accent_protected, 10f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_base))
        }
        findViewById<Button>(R.id.requestBatteryButton).apply {
            background = pill(R.color.accent_trusted, 12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_base))
        }

        listOf(
            R.id.manualUpButton, R.id.manualDownButton, R.id.useCurrentSsidButton,
            R.id.importConfigButton, R.id.scanQrButton, R.id.openAppSettingsButton, R.id.excludeAppsButton
        ).forEach { id -> findViewById<Button>(id).background = outlineBackground(R.color.divider, 12f) }
    }

    // ---- Sezione configurazione (richiudibile) ----

    private fun expandConfigSection() {
        findViewById<View>(R.id.configSection).visibility = View.VISIBLE
        findViewById<TextView>(R.id.configToggleArrow).text = "▾"
    }

    private fun collapseConfigSection() {
        findViewById<View>(R.id.configSection).visibility = View.GONE
        findViewById<TextView>(R.id.configToggleArrow).text = "▸"
    }

    // ---- Logica ----

    private fun scanQrCode() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Inquadra il QR della configurazione WireGuard")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        qrScanLauncher.launch(options)
    }

    private fun saveConfig() {
        val text = configEditText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Inserisci una configurazione WireGuard valida", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }.onSuccess {
            tunnelRepository.saveConfigText(text)
            Toast.makeText(this, "Configurazione salvata", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Configurazione non valida: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addSsidFromInput() {
        val ssid = ssidEditText.text.toString().trim()
        if (ssid.isEmpty()) return
        trustedNetworksRepository.add(ssid)
        networkAdapter.submitList(trustedNetworksRepository.getAll())
        ssidEditText.setText("")
        refreshServiceIfEnabled()
        updateStatus()
    }

    private fun useCurrentSsid() {
        val ssid = getCurrentSsid()
        if (ssid == null) {
            Toast.makeText(
                this,
                "Nessuna rete Wi-Fi rilevata (usa 'Diagnostica' per capire perché)",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        trustedNetworksRepository.add(ssid)
        networkAdapter.submitList(trustedNetworksRepository.getAll())
        refreshServiceIfEnabled()
        updateStatus()
    }

    /**
     * Dice subito al servizio in background di ricontrollare la situazione,
     * invece di aspettare il prossimo cambio di rete o il controllo
     * periodico (60s). Serve quando si aggiunge/rimuove una rete fidata
     * mentre si è già connessi a quella rete: lo stato del tunnel deve
     * aggiornarsi all'istante, non dopo un minuto.
     */
    private fun refreshServiceIfEnabled() {
        if (tunnelRepository.isServiceEnabled()) {
            WifiMonitorService.start(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiState(): Pair<Boolean, String?> {
        val network = connectivityManager.activeNetwork ?: return false to null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false to null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false to null

        val fromCapabilities = (caps.transportInfo as? WifiInfo)?.ssid
        val ssid = if (!fromCapabilities.isNullOrEmpty() && fromCapabilities != WifiManager.UNKNOWN_SSID) {
            fromCapabilities
        } else {
            (getSystemService(WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
        }

        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) return true to null
        return true to ssid.removePrefix("\"").removeSuffix("\"")
    }

    private fun getCurrentSsid(): String? = readWifiState().second

    @Suppress("DEPRECATION")
    private fun showDiagnostics() {
        val sb = StringBuilder()

        val locationGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        sb.append("1) Permesso posizione concesso all'app: ")
        sb.append(if (locationGranted) "SÌ\n" else "NO ← da correggere\n")

        val locationManager = getSystemService(LocationManager::class.java)
        val locationEnabled = locationManager?.isLocationEnabled ?: false
        sb.append("2) Posizione attiva nel sistema: ")
        sb.append(if (locationEnabled) "SÌ\n" else "NO ← da correggere\n")

        val network = connectivityManager.activeNetwork
        sb.append("3) Rete attiva sul telefono: ")
        sb.append(if (network != null) "presente\n" else "NESSUNA (Wi-Fi spento o non connesso)\n")

        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        sb.append("4) La rete attiva è Wi-Fi: ")
        sb.append(if (isWifi) "SÌ\n" else "NO (probabilmente dati mobili)\n")

        val info = caps?.transportInfo as? WifiInfo
        sb.append("5) Dettagli Wi-Fi leggibili dal sistema: ")
        sb.append(if (info != null) "SÌ\n" else "NO\n")

        val rawSsid = info?.ssid
        sb.append("6) SSID - metodo nuovo: ")
        sb.append(rawSsid ?: "(vuoto/null)")
        sb.append("\n")

        val legacySsid = (getSystemService(WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
        sb.append("7) SSID - metodo classico: ")
        sb.append(legacySsid ?: "(vuoto/null)")

        AlertDialog.Builder(this)
            .setTitle("Diagnostica rilevamento Wi-Fi")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestEnable() {
        if (!tunnelRepository.hasValidConfig()) {
            Toast.makeText(this, "Salva prima una configurazione WireGuard valida", Toast.LENGTH_LONG).show()
            enabledSwitch.isChecked = false
            return
        }
        val needed = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            proceedEnabling()
        }
    }

    private fun proceedEnabling() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        tunnelRepository.setServiceEnabled(true)
        WifiMonitorService.start(this)
        updateStatus()
    }

    private fun disableService() {
        tunnelRepository.setServiceEnabled(false)
        WifiMonitorService.stop(this)
        tunnelRepository.applyState(Tunnel.State.DOWN)
        updateStatus()
    }

    private fun manualSetState(state: Tunnel.State) {
        if (!tunnelRepository.hasValidConfig()) {
            Toast.makeText(this, "Salva prima una configurazione", Toast.LENGTH_SHORT).show()
            return
        }
        if (state == Tunnel.State.UP) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
                return
            }
        }
        tunnelRepository.applyState(state) { success, error ->
            runOnUiThread {
                if (success) {
                    val label = if (state == Tunnel.State.UP) "attivato" else "disattivato"
                    Toast.makeText(this, "Tunnel $label correttamente", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Errore: $error", Toast.LENGTH_LONG).show()
                }
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val (isOnWifi, ssid) = readWifiState()
        val ambiguous = isOnWifi && ssid == null
        val trusted = ssid != null && trustedNetworksRepository.isTrusted(ssid)
        val realState = tunnelRepository.currentState()
        val serviceEnabled = tunnelRepository.isServiceEnabled()
        val error = tunnelRepository.lastError

        val title: String
        val colorRes: Int
        when {
            !serviceEnabled -> { title = "DISATTIVATO"; colorRes = R.color.accent_idle }
            error != null -> { title = "ERRORE"; colorRes = R.color.accent_error }
            ambiguous -> { title = "VERIFICA IN CORSO"; colorRes = R.color.accent_idle }
            trusted && realState == Tunnel.State.UP ->
                { title = "ANOMALIA: tunnel attivo su rete fidata"; colorRes = R.color.accent_warning }
            realState == Tunnel.State.UP -> { title = "PROTETTO"; colorRes = R.color.accent_protected }
            trusted -> { title = "RETE FIDATA"; colorRes = R.color.accent_trusted }
            else -> { title = "IN ATTESA"; colorRes = R.color.accent_warning }
        }
        statusTitle.text = title
        (statusDot.background as? GradientDrawable)?.setColor(ContextCompat.getColor(this, colorRes))

        val ssidLine = when {
            ssid != null -> "Rete: $ssid" + if (trusted) " (fidata)" else ""
            ambiguous -> "Rete Wi-Fi rilevata, nome non leggibile al momento"
            else -> "Nessuna rete Wi-Fi"
        }
        val errorLine = error?.let { "\nErrore: $it" } ?: ""
        statusText.text = ssidLine + errorLine

        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.batteryStatusText).text = if (ignoring) {
            "Stato: ottimizzazione batteria Android già disattivata ✓\n(ricontrolla comunque le impostazioni del produttore, es. \"App in sospensione\" su Samsung)"
        } else {
            "Stato: l'ottimizzazione batteria Android è ancora attiva\npuò interrompere il monitoraggio in background"
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Già disattivata per questa app", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                this,
                "Non riesco ad aprire la richiesta diretta: vai su Impostazioni > Batteria a mano",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Non riesco ad aprire le impostazioni dell'app", Toast.LENGTH_SHORT).show()
        }
    }
}