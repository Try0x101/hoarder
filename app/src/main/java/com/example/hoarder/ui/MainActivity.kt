package com.example.hoarder.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.common.ContextUtils
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.service.BackgroundService
import com.example.hoarder.ui.service.ServiceCommander
import com.example.hoarder.utils.PermHandler

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val permHandler by lazy { PermHandler(this, h) }
    private val serviceCommander by lazy { ServiceCommander(this) }

    private val pr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ServiceCommander.ACTION_PERMISSIONS_REQUIRED) {
                permHandler.requestPerms()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<RelativeLayout>(R.id.telemetrySettingsButton).setOnClickListener {
            startActivity(Intent(this, TelemetrySettingsActivity::class.java))
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(pr, IntentFilter(ServiceCommander.ACTION_PERMISSIONS_REQUIRED))

        if (prefs.isFirstRun()) {
            permHandler.setPendingAction { handleFirstRun() }
            if (permHandler.hasAllPerms()) handleFirstRun() else permHandler.requestPerms()
        } else {
            if (permHandler.hasAllPerms()) startServiceIfNeeded() else permHandler.requestPerms()
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(99999)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        permHandler.handleResult(rc, res)
    }

    override fun onResume() {
        super.onResume()
        if (permHandler.hasAllPerms()) {
            startServiceIfNeeded()
            restoreServiceState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pr)
        h.removeCallbacksAndMessages(null)
    }

    private fun handleFirstRun() {
        prefs.markFirstRunComplete()
        prefs.setDataCollectionEnabled(true)
        prefs.setDataUploadEnabled(false)
        startServiceIfNeeded()
        startActivity(Intent(this, TelemetrySettingsActivity::class.java))
        h.postDelayed({ finishAffinity() }, 2000)
    }

    private fun startServiceIfNeeded() {
        if (ContextUtils.isServiceRunning(this, BackgroundService::class.java)) return
        val si = Intent(this, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
    }

    private fun restoreServiceState() {
        if (prefs.isDataCollectionEnabled()) serviceCommander.startCollection()
        if (prefs.isDataUploadEnabled()) serviceCommander.startUpload(prefs.getServerAddress(), null)
    }
}