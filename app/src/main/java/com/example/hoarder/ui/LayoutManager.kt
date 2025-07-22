package com.example.hoarder.ui

import android.widget.TextView
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.managers.MainSwitchManager
import com.example.hoarder.ui.managers.PowerSettingsManager
import com.example.hoarder.ui.managers.PrecisionSettingsManager
import com.example.hoarder.ui.managers.SectionToggleManager

class LayoutManager(private val a: MainActivity, private val p: Prefs, private val onPrecisionChanged: () -> Unit) {

    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    private val jsonContent: TextView by lazy { a.findViewById(R.id.jsonContent) }

    private val sectionToggleManager = SectionToggleManager(a) { updateJson(a.getLastData()) }
    private val mainSwitchManager = MainSwitchManager(a, p)
    private val precisionSettingsManager = PrecisionSettingsManager(a, p, onPrecisionChanged)
    private val powerSettingsManager = PowerSettingsManager(a, p)

    fun setupUI() {
        sectionToggleManager.setup()
        mainSwitchManager.setup()
        precisionSettingsManager.setup()
        powerSettingsManager.setup()
        setupTextCopyListeners()
    }

    fun updateJson(json: String?) {
        val prettyJson = if (json.isNullOrEmpty() || json.startsWith("Collection is inactive")) {
            "Collection is inactive or waiting for data..."
        } else {
            try {
                gson.toJson(com.google.gson.JsonParser.parseString(json))
            } catch (e: Exception) { json }
        }
        jsonContent.text = prettyJson
    }

    private fun setupTextCopyListeners() {
        a.findViewById<TextView>(R.id.timestampInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Timestamp Info")
            true
        }
        a.findViewById<TextView>(R.id.precisionInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Precision Settings Info")
            true
        }
        a.findViewById<TextView>(R.id.fieldsInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Data Fields Reference")
            true
        }
    }

    private fun copyTextToClipboard(textView: TextView, label: String) {
        val clipboard = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, textView.text)
        clipboard.setPrimaryClip(clip)
    }
}