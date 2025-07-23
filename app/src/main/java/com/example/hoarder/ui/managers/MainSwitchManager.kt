package com.example.hoarder.ui.managers

import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs

class MainSwitchManager(private val a: AppCompatActivity, private val p: Prefs, private val callbacks: MainSwitchCallbacks) {

    interface MainSwitchCallbacks {
        fun startCollection()
        fun stopCollection()
        fun startUpload(address: String)
        fun stopUpload()
    }

    fun setup() {
        val dataCollectionSwitch = a.findViewById<Switch>(R.id.dataCollectionSwitch)
        val serverUploadSwitch = a.findViewById<Switch>(R.id.serverUploadSwitch)

        dataCollectionSwitch.isChecked = p.isDataCollectionEnabled()
        serverUploadSwitch.isChecked = p.isDataUploadEnabled()

        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataCollectionEnabled(isChecked)
            if (isChecked) callbacks.startCollection() else callbacks.stopCollection()
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataUploadEnabled(isChecked)
            if (isChecked) {
                val addr = p.getServerAddress()
                if (addr.isNotEmpty()) {
                    callbacks.startUpload(addr)
                } else {
                    serverUploadSwitch.isChecked = false
                    p.setDataUploadEnabled(false)
                }
            } else {
                callbacks.stopUpload()
            }
        }
    }
}