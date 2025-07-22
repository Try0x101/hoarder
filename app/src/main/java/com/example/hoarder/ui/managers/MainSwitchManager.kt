package com.example.hoarder.ui.managers

import android.widget.Switch
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.MainActivity

class MainSwitchManager(private val a: MainActivity, private val p: Prefs) {

    fun setup() {
        val dataCollectionSwitch = a.findViewById<Switch>(R.id.dataCollectionSwitch)
        val serverUploadSwitch = a.findViewById<Switch>(R.id.serverUploadSwitch)

        dataCollectionSwitch.isChecked = p.isDataCollectionEnabled()
        serverUploadSwitch.isChecked = p.isDataUploadEnabled()

        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataCollectionEnabled(isChecked)
            if (isChecked) a.startCollection() else a.stopCollection()
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataUploadEnabled(isChecked)
            if (isChecked) {
                val addr = p.getServerAddress()
                if (addr.isNotEmpty()) {
                    a.startUpload(addr)
                } else {
                    serverUploadSwitch.isChecked = false
                    p.setDataUploadEnabled(false)
                }
            } else {
                a.stopUpload()
            }
        }
    }
}