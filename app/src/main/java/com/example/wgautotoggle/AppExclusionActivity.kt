package com.example.wgautotoggle

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.backend.Tunnel

class AppExclusionActivity : AppCompatActivity() {

    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_exclusion)

        tunnelRepository = TunnelRepository.getInstance(applicationContext)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        val searchEditText = findViewById<EditText>(R.id.searchEditText)
        searchEditText.background = UiStyle.pill(this, R.color.bg_surface_alt, 10f)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        adapter = AppListAdapter { entry, isExcluded -> onToggle(entry, isExcluded) }
        val recyclerView = findViewById<RecyclerView>(R.id.appListRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        findViewById<TextView>(R.id.loadingText).visibility = View.VISIBLE
        Thread {
            val excluded = tunnelRepository.getExcludedApplications()
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)

            val seen = HashSet<String>()
            val entries = mutableListOf<AppEntry>()
            for (info in resolved) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                if (!seen.add(pkg)) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                entries.add(AppEntry(label, pkg, icon, excluded.contains(pkg)))
            }
            entries.sortBy { it.label.lowercase() }

            runOnUiThread {
                allApps = entries
                findViewById<TextView>(R.id.loadingText).visibility = View.GONE
                applyFilter(findViewById<EditText>(R.id.searchEditText).text.toString())
            }
        }.start()
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
    }

    private fun onToggle(entry: AppEntry, isExcluded: Boolean) {
        entry.excluded = isExcluded
        val excludedSet = allApps.filter { it.excluded }.map { it.packageName }.toSet()
        val saved = tunnelRepository.setExcludedApplications(excludedSet)

        if (!saved) {
            Toast.makeText(this, "Salva prima una configurazione valida", Toast.LENGTH_SHORT).show()
            entry.excluded = !isExcluded
            adapter.submitList(allApps.filter {
                val q = findViewById<EditText>(R.id.searchEditText).text.toString()
                q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            })
            return
        }

        // Se il tunnel è già attivo, lo riapplichiamo per usare subito la
        // nuova lista di esclusioni, senza dover spegnere e riaccendere a mano.
        if (tunnelRepository.currentState() == Tunnel.State.UP) {
            tunnelRepository.applyState(Tunnel.State.UP)
        }
    }
}