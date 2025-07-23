package com.example.hoarder.ui

import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.settings.ServerSettingsDialogHandler
import com.google.gson.Gson

class DialogManager(private val a: TelemetrySettingsActivity, private val p: Prefs) {

    private val logRepository by lazy { LogRepository(a, Gson()) }
    private val serverSettingsDialogHandler by lazy { ServerSettingsDialogHandler(a, p, logRepository) }

    fun showServerSettingsDialog() {
        serverSettingsDialogHandler.show()
    }
}