package com.example.hoarder.ui.dialogs.settings

import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.hoarder.R
import com.example.hoarder.ui.dialogs.server.ServerStatsManager
import com.example.hoarder.ui.formatters.ByteFormatter
import kotlinx.coroutines.launch

class ServerStatsViewManager(
    private val view: View,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val serverStatsManager: ServerStatsManager
) {
    private val statsLastHour: TextView = view.findViewById(R.id.statsLastHour)
    private val statsLastDay: TextView = view.findViewById(R.id.statsLastDay)
    private val statsLast7Days: TextView = view.findViewById(R.id.statsLast7Days)

    fun loadInitialStats() {
        statsLastHour.text = "Calculating..."
        statsLastDay.text = "Calculating..."
        statsLast7Days.text = "Calculating..."
        updateStats()
    }

    fun updateStats() {
        lifecycleScope.launch {
            val (lastHour, lastDay, last7Days) = serverStatsManager.calculateUploadStats()
            statsLastHour.text = formatStats(lastHour)
            statsLastDay.text = formatStats(lastDay)
            statsLast7Days.text = formatStats(last7Days)
        }
    }

    private fun formatStats(stats: com.example.hoarder.ui.dialogs.server.UploadStats): String {
        return if (stats.actualNetworkBytes > 0) {
            "${ByteFormatter.format(stats.payloadBytes)} / ${ByteFormatter.format(stats.actualNetworkBytes)}"
        } else {
            ByteFormatter.format(stats.payloadBytes)
        }
    }
}