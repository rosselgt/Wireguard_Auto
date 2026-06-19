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
