package com.example.wgautotoggle

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Gestisce il backend WireGuard (implementazione userspace "GoBackend",
 * non richiede root) e la configurazione del tunnel, salvata in modo
 * cifrato sul dispositivo tramite EncryptedSharedPreferences perché
 * contiene la chiave privata.
 */
class TunnelRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val backend: Backend by lazy { GoBackend(appContext) }
    private val executor = Executors.newSingleThreadExecutor()

    val tunnel: WireGuardTunnel = WireGuardTunnel(TUNNEL_NAME)

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "wg_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveConfigText(text: String) {
        prefs.edit().putString(KEY_CONFIG, text).apply()
    }

    fun getConfigText(): String? = prefs.getString(KEY_CONFIG, null)

    fun hasValidConfig(): Boolean = !getConfigText().isNullOrBlank()

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Legge i nomi dei pacchetti elencati nella riga "ExcludedApplications"
     * dentro la sezione [Interface] della configurazione salvata.
     */
    fun getExcludedApplications(): Set<String> {
        val text = getConfigText() ?: return emptySet()
        return readExcludedApplications(text)
    }

    /**
     * Aggiorna la riga "ExcludedApplications" dentro la sezione [Interface]
     * della configurazione salvata (la crea se non c'è, la rimuove se
     * l'insieme è vuoto) e salva il risultato, solo se resta un config
     * valido (con un controllo Config.parse prima di salvare).
     */
    fun setExcludedApplications(packages: Set<String>): Boolean {
        val current = getConfigText() ?: return false
        val updated = writeExcludedApplications(current, packages)
        return runCatching {
            Config.parse(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8)))
        }.onSuccess {
            saveConfigText(updated)
        }.isSuccess
    }

    private fun readExcludedApplications(configText: String): Set<String> {
        var inInterface = false
        for (rawLine in configText.lines()) {
            val trimmed = rawLine.trim()
            when {
                trimmed.equals("[Interface]", ignoreCase = true) -> inInterface = true
                trimmed.startsWith("[") && trimmed.endsWith("]") -> inInterface = false
                inInterface -> {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].trim().equals("ExcludedApplications", ignoreCase = true)) {
                        return parts[1].split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }
                }
            }
        }
        return emptySet()
    }

    private fun writeExcludedApplications(configText: String, packages: Set<String>): String {
        val lines = configText.lines().toMutableList()

        var interfaceStart = -1
        var interfaceEnd = lines.size
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals("[Interface]", ignoreCase = true)) {
                interfaceStart = i
            } else if (interfaceStart != -1 && i > interfaceStart && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                interfaceEnd = i
                break
            }
        }
        // Nessuna sezione [Interface]: non possiamo inserire in modo sicuro,
        // restituiamo il testo originale senza modifiche.
        if (interfaceStart == -1) return configText

        var existingLineIndex = -1
        for (i in interfaceStart + 1 until interfaceEnd) {
            val key = lines[i].trim().split("=", limit = 2).getOrNull(0)?.trim()
            if (key?.equals("ExcludedApplications", ignoreCase = true) == true) {
                existingLineIndex = i
                break
            }
        }
        if (existingLineIndex != -1) {
            lines.removeAt(existingLineIndex)
            if (existingLineIndex < interfaceEnd) interfaceEnd--
        }

        if (packages.isNotEmpty()) {
            lines.add(interfaceEnd, "ExcludedApplications = " + packages.joinToString(", "))
        }

        return lines.joinToString("\n")
    }

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Porta il tunnel UP o DOWN. Esegue il lavoro su un thread in background
     * perché le chiamate al backend sono bloccanti (operazioni di rete/JNI).
     */
    fun applyState(state: Tunnel.State, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        executor.execute {
            try {
                val config = if (state == Tunnel.State.UP) buildConfig() else null
                backend.setState(tunnel, state, config)
                lastError = null
                onResult(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Errore applicando stato $state", e)
                lastError = e.message ?: e.toString()
                onResult(false, lastError)
            }
        }
    }

    fun currentState(): Tunnel.State = try {
        backend.getState(tunnel)
    } catch (e: Exception) {
        Tunnel.State.DOWN
    }

    @Throws(Exception::class)
    private fun buildConfig(): Config {
        val text = getConfigText()
            ?: throw IllegalStateException("Nessuna configurazione WireGuard salvata")
        return Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val TAG = "TunnelRepository"
        private const val TUNNEL_NAME = "wgauto"
        private const val KEY_CONFIG = "wg_config_text"
        private const val KEY_ENABLED = "service_enabled"

        @Volatile
        private var instance: TunnelRepository? = null

        fun getInstance(context: Context): TunnelRepository =
            instance ?: synchronized(this) {
                instance ?: TunnelRepository(context).also { instance = it }
            }
    }
}
