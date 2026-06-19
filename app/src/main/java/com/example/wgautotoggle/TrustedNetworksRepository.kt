package com.example.wgautotoggle

import android.content.Context
import org.json.JSONArray

/**
 * Salva la lista degli SSID "fidati" (es. casa, ufficio).
 * Quando il telefono è connesso a una di queste reti, WireGuard
 * viene disattivato automaticamente.
 */
class TrustedNetworksRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("trusted_networks", Context.MODE_PRIVATE)

    fun getAll(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun add(ssid: String) {
        val clean = ssid.trim()
        if (clean.isEmpty()) return
        val current = getAll().toMutableList()
        if (current.none { it.equals(clean, ignoreCase = true) }) {
            current.add(clean)
            save(current)
        }
    }

    fun remove(ssid: String) {
        val current = getAll().toMutableList()
        current.removeAll { it.equals(ssid, ignoreCase = true) }
        save(current)
    }

    fun isTrusted(ssid: String): Boolean =
        getAll().any { it.equals(ssid, ignoreCase = true) }

    private fun save(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "ssid_list"
    }
}
