package com.example.hoarder.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.common.ContextUtils
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.service.BackgroundService
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.main.MainViewModel
import com.example.hoarder.utils.NotifUtils
import com.example.hoarder.utils.PermHandler
import com.example.hoarder.utils.ToastHelper

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val permHandler by lazy { PermHandler(this, h) }
    private val ui by lazy { UIHelper(this, prefs) }
    private val viewModel: MainViewModel by viewModels()

    private var lastData: String? = null

    private val pr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == "com.example.hoarder.PERMISSIONS_REQUIRED") {
                ToastHelper.showToast(this@MainActivity, "Location permissions required", Toast.LENGTH_LONG)
                permHandler.requestPerms()
            }
        }
    }

    override fun onCreate(sb: Bundle?) {
        super.onCreate(sb)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, i ->
            val sb = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            i
        }

        ui.setupUI()
        NotifUtils.createSilentChannel(this)
        viewModel.registerReceivers()
        observeViewModel()

        LocalBroadcastManager.getInstance(this).registerReceiver(pr, IntentFilter("com.example.hoarder.PERMISSIONS_REQUIRED"))

        if (prefs.isFirstRun()) {
            permHandler.setPendingAction { handleFirstRun() }
            if (permHandler.hasAllPerms()) handleFirstRun() else permHandler.requestPerms()
        } else {
            if (permHandler.hasAllPerms()) ss() else permHandler.requestPerms()
        }
    }

    private fun observeViewModel() {
        viewModel.lastJson.observe(this) { json ->
            lastData = json
            ui.updateRawJson(json)
        }
        viewModel.isUploadEnabled.observe(this) { isEnabled ->
            ui.updateUploadUI(
                isEnabled,
                viewModel.uploadStatus.value,
                viewModel.uploadMessage.value,
                viewModel.totalUploadedBytes.value,
                viewModel.bufferedDataSize.value ?: 0L
            )
        }
        viewModel.totalUploadedBytes.observe(this) {
            ui.updateUploadUI(
                viewModel.isUploadEnabled.value ?: false,
                viewModel.uploadStatus.value,
                viewModel.uploadMessage.value,
                it,
                viewModel.bufferedDataSize.value ?: 0L
            )
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        permHandler.handleResult(rc, res)
    }

    override fun onResume() {
        super.onResume()
        NotifUtils.createSilentChannel(this)
        if (permHandler.hasAllPerms() && !ContextUtils.isServiceRunning(this, BackgroundService::class.java)) {
            ss()
            rs()
        }
        ui.updateRawJson(getLastData())
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pr)
        h.removeCallbacksAndMessages(null)
    }

    private fun handleFirstRun() {
        prefs.markFirstRunComplete()
        prefs.setDataCollectionEnabled(true)
        ui.updateDataCollectionUI(true)

        val cip = prefs.getServerAddress()
        if (cip.isBlank() || !NetUtils.isValidIpPort(cip)) {
            val dip = "127.0.0.1:5000"
            prefs.setServerAddress(dip)
        }

        prefs.setDataUploadEnabled(false)
        ui.updateUploadUI(false, null, null, null, 0L)

        ss()
        startCollection()

        ToastHelper.showToast(this, "Setup complete. App will run in background.")
        h.postDelayed({ finishAffinity() }, 2000)
    }

    private fun ss() {
        val si = Intent(this, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
    }

    private fun rs() {
        if (prefs.isDataCollectionEnabled()) startCollection()
        if (prefs.isDataUploadEnabled()) {
            val ip = prefs.getServerAddress()
            if (NetUtils.isValidIpPort(ip)) startUpload(ip)
        }
    }

    fun startCollection() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_COLLECTION"))
    }

    fun stopCollection() {
        ui.updateRawJson(null)
        lastData = null
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))
    }

    fun startUpload(sa: String) {
        if (NetUtils.isValidIpPort(sa)) {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", sa))

            val currentData = getLastData()
            if (currentData != null && currentData.isNotBlank()) {
                h.postDelayed({
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent("com.example.hoarder.FORCE_UPLOAD").putExtra("forcedData", currentData))
                }, 500)
            }
        }
    }

    fun stopUpload() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))
    }

    fun sendBuffer() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.SEND_BUFFER"))
    }

    fun getLastData(): String? = lastData
}