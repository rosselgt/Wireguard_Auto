package com.example.wgautotoggle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = TunnelRepository.getInstance(context)
        if (repo.isServiceEnabled()) {
            WifiMonitorService.start(context)
        }
    }
}
