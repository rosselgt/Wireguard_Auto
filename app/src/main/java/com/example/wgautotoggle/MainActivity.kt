package com.example.wgautotoggle

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var trustedNetworksRepository: TrustedNetworksRepository
    private lateinit var networkAdapter: NetworkAdapter

    private lateinit var configEditText: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var ssidEditText: EditText

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tunnelRepository = TunnelRepository.getInstance(applicationContext)
        trustedNetworksRepository = TrustedNetworksRepository(applicationContext)

        configEditText = findViewById(R.id.configEditText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statusText = findViewById(R.id.statusText)
        ssidEditText = findViewById(R.id.ssidEditText)

        configEditText.setText(tunnelRepository.getConfigText().orEmpty())
        enabledSwitch.isChecked = tunnelRepository.isServiceEnabled()

        findViewById<Button>(R.id.saveConfigButton).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.importConfigButton).setOnClickListener {
            openConfigLauncher.launch("*/*")
        }
        findViewById<Button>(R.id.addSsidButton).setOnClickListener { addSsidFromInput() }
        findViewById<Button>(R.id.useCurrentSsidButton).setOnClickListener { useCurrentSsid() }
        findViewById<Button>(R.id.manualUpButton).setOnClickListener { manualSetState(Tunnel.State.UP) }
        findViewById<Button>(R.id.manualDownButton).setOnClickListener { manualSetState(Tunnel.State.DOWN) }

        val recyclerView = findViewById<RecyclerView>(R.id.networksRecyclerView)
        networkAdapter = NetworkAdapter(trustedNetworksRepository.getAll().toMutableList()) { ssid ->
            trustedNetworksRepository.remove(ssid)
            networkAdapter.submitList(trustedNetworksRepository.getAll())
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
        updateStatus()
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
    }

    private fun useCurrentSsid() {
        val ssid = getCurrentSsid()
        if (ssid == null) {
            Toast.makeText(
                this,
                "Nessuna rete Wi-Fi rilevata (controlla i permessi di posizione)",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        trustedNetworksRepository.add(ssid)
        networkAdapter.submitList(trustedNetworksRepository.getAll())
    }

    private fun getCurrentSsid(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid ?: return null
        if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID) return null
        return ssid.removePrefix("\"").removeSuffix("\"")
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
        tunnelRepository.applyState(state) { _, _ ->
            runOnUiThread { updateStatus() }
        }
    }

    private fun updateStatus() {
        val ssid = getCurrentSsid()
        val trusted = ssid != null && trustedNetworksRepository.isTrusted(ssid)
        statusText.text = when {
            ssid != null && trusted -> "Rete attuale: $ssid (fidata -> WireGuard OFF)"
            ssid != null -> "Rete attuale: $ssid (non fidata -> WireGuard ON)"
            else -> "Rete attuale: nessuna rete Wi-Fi"
        }
    }
}
