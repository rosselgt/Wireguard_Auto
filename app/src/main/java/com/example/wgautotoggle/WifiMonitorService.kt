package com.example.wgautotoggle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.wireguard.android.backend.Tunnel

/**
 * Servizio in primo piano (foreground service) che resta in ascolto dei
 * cambiamenti di rete tramite ConnectivityManager.NetworkCallback.
 *
 * Logica:
 *  - se la rete Wi-Fi attuale è nella lista delle reti fidate -> tunnel DOWN
 *  - in tutti gli altri casi (altra rete Wi-Fi, dati mobili, nessuna rete) -> tunnel UP
 */
class WifiMonitorService : Service() {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var trustedNetworksRepository: TrustedNetworksRepository

    private var registered = false

    private val periodicHandler = Handler(Looper.getMainLooper())
    private val periodicCheck = object : Runnable {
        override fun run() {
            evaluateAndApply()
            periodicHandler.postDelayed(this, 60_000L)
        }
    }

    // Piccolo ritardo prima di applicare un cambio di rete: appena il
    // Wi-Fi si connette, Android può segnalare la rete come "disponibile"
    // prima che DHCP e le rotte siano del tutto pronte. Aspettando un
    // momento si evita di creare il tunnel con informazioni di rete non
    // ancora stabili (causa probabile di problemi nel raggiungere la
    // rete locale subito dopo la connessione).
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debouncedEvaluate = Runnable { evaluateAndApply() }

    private fun scheduleEvaluate(delayMs: Long) {
        debounceHandler.removeCallbacks(debouncedEvaluate)
        debounceHandler.postDelayed(debouncedEvaluate, delayMs)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Non agiamo già qui: la rete è appena apparsa, aspettiamo che
            // arrivi la conferma "convalidata" in onCapabilitiesChanged.
        }

        override fun onLost(network: Network) {
            // Quando la rete sparisce non c'è nulla da aspettare: agiamo
            // comunque con un ritardo minimo per evitare scatti durante
            // passaggi rapidi tra reti (es. handover tra access point).
            scheduleEvaluate(500L)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                scheduleEvaluate(1_500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        tunnelRepository = TunnelRepository.getInstance(applicationContext)
        trustedNetworksRepository = TrustedNetworksRepository(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification("In attesa di una rete...", false))
        periodicHandler.postDelayed(periodicCheck, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!registered) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            registered = true
        }
        evaluateAndApply()
        return START_STICKY
    }

    override fun onDestroy() {
        periodicHandler.removeCallbacks(periodicCheck)
        debounceHandler.removeCallbacks(debouncedEvaluate)
        if (registered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            registered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluateAndApply() {
        if (!tunnelRepository.isServiceEnabled()) return

        val (isOnWifi, ssid) = readWifiState()

        if (isOnWifi && ssid == null) {
            // Connessi al Wi-Fi ma il nome della rete non è leggibile in
            // questo momento (capita tipicamente quando lo schermo è
            // spento/stand-by: Android limita la scansione Wi-Fi per
            // risparmiare batteria). Non sappiamo se siamo su una rete
            // fidata o no: non cambiamo nulla, per evitare di accendere il
            // tunnel per errore. Riproveremo al prossimo evento di rete o
            // al prossimo controllo periodico (60s).
            val actualNow = tunnelRepository.currentState()
            updateNotification(
                "Rete Wi-Fi rilevata, nome non leggibile al momento (in attesa)",
                actualNow == Tunnel.State.UP
            )
            return
        }

        val isTrusted = ssid != null && trustedNetworksRepository.isTrusted(ssid)
        val desired = if (isTrusted) Tunnel.State.DOWN else Tunnel.State.UP
        // Confrontiamo sempre con lo stato REALE del tunnel (non con un valore
        // "ricordato"): così un tocco manuale su "Attiva/Disattiva ora", un
        // riavvio del telefono o qualsiasi altra causa di disallineamento si
        // autocorregge automaticamente al prossimo cambio di rete.
        val actual = tunnelRepository.currentState()

        val label = when {
            ssid != null && isTrusted -> "Rete fidata: $ssid"
            ssid != null -> "Rete: $ssid"
            else -> "Nessuna rete Wi-Fi"
        }

        if (desired == actual) {
            updateNotification(label, actual == Tunnel.State.UP)
            return
        }
        if (!tunnelRepository.hasValidConfig()) {
            updateNotification("$label (nessuna configurazione salvata)", actual == Tunnel.State.UP)
            return
        }

        updateNotification("$label - applicazione in corso...", desired == Tunnel.State.UP)
        tunnelRepository.applyState(desired) { success, error ->
            if (success) {
                updateNotification(label, desired == Tunnel.State.UP)
            } else {
                Log.e(TAG, "Impossibile impostare lo stato $desired: $error")
                updateNotification("$label - ERRORE: $error", actual == Tunnel.State.UP)
            }
        }
    }

    /**
     * Restituisce (siamo su una rete Wi-Fi?, nome della rete o null se non
     * leggibile in questo momento). Richiede il permesso ACCESS_FINE_LOCATION
     * concesso e la posizione attiva sul dispositivo: è una limitazione
     * imposta da Android stesso, non da questa app.
     */
    @Suppress("DEPRECATION")
    private fun readWifiState(): Pair<Boolean, String?> {
        val network = connectivityManager.activeNetwork ?: return false to null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false to null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false to null

        // Su alcuni dispositivi (es. certi Samsung) il metodo "nuovo"
        // (NetworkCapabilities.transportInfo) restituisce sempre
        // <unknown ssid>: in quel caso usiamo il metodo "classico" di
        // WifiManager, che su quei dispositivi funziona correttamente.
        val fromCapabilities = (caps.transportInfo as? WifiInfo)?.ssid
        val ssid = if (!fromCapabilities.isNullOrEmpty() && fromCapabilities != WifiManager.UNKNOWN_SSID) {
            fromCapabilities
        } else {
            (getSystemService(WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
        }

        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) return true to null
        return true to ssid.removePrefix("\"").removeSuffix("\"")
    }

    private fun buildNotification(text: String, vpnUp: Boolean): Notification {
        val channelId = "wifi_monitor_channel"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "Monitoraggio Wi-Fi / WireGuard",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle(if (vpnUp) "RosselGT-WG attivo" else "RosselGT-WG disattivato")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, vpnUp: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, vpnUp))
    }

    companion object {
        private const val TAG = "WifiMonitorService"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WifiMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiMonitorService::class.java))
        }
    }
}