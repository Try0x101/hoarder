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
import com.example.hoarder.ui.main.MainViewModel
import com.example.hoarder.ui.service.ServiceCommander
import com.example.hoarder.ui.state.UploadState
import com.example.hoarder.utils.NotifUtils
import com.example.hoarder.utils.PermHandler

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val permHandler by lazy { PermHandler(this, h) }
    private val ui by lazy { UIHelper(this, prefs) }
    private val serviceCommander by lazy { ServiceCommander(this) }
    internal val viewModel: MainViewModel by viewModels()

    private var lastData: String? = null

    private val pr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ServiceCommander.ACTION_PERMISSIONS_REQUIRED) {
                permHandler.requestPerms()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun observeViewModel() {
        viewModel.lastJson.observe(this) { json ->
            lastData = json
            ui.updateRawJson(json)
        }
        viewModel.uploadState.observe(this) { state ->
            updateFullUploadUI(state)
        }
    }

    private fun updateFullUploadUI(state: UploadState) {
        ui.updateUploadUI(
            prefs.isDataUploadEnabled(),
            state
        )
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        permHandler.handleResult(rc, res)
    }

    override fun onResume() {
        super.onResume()
        NotifUtils.createSilentChannel(this)
        if (permHandler.hasAllPerms()) {
            startServiceIfNeeded()
            restoreServiceState()
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
        prefs.setDataUploadEnabled(false)
        ui.updateDataCollectionUI(true)
        ui.updateUploadUI(false, UploadState())
        startServiceIfNeeded()
        startCollection()
        h.postDelayed({ finishAffinity() }, 2000)
    }

    private fun startServiceIfNeeded() {
        if (ContextUtils.isServiceRunning(this, BackgroundService::class.java)) return
        val si = Intent(this, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
    }

    private fun restoreServiceState() {
        if (prefs.isDataCollectionEnabled()) startCollection()
        if (prefs.isDataUploadEnabled()) startUpload(prefs.getServerAddress())
    }

    fun onPowerModeChanged() = serviceCommander.notifyPowerModeChanged(prefs.getPowerMode())

    fun onBatchingSettingsChanged(
        enabled: Boolean, recordCount: Int, byCount: Boolean,
        timeout: Int, byTimeout: Boolean, maxSize: Int, byMaxSize: Boolean, compLevel: Int
    ) = serviceCommander.notifyBatchingSettingsChanged(enabled, recordCount, byCount, timeout, byTimeout, maxSize, byMaxSize, compLevel)

    fun startCollection() = serviceCommander.startCollection()
    fun stopCollection() {
        ui.updateRawJson(null)
        lastData = null
        serviceCommander.stopCollection()
    }
    fun startUpload(sa: String) = serviceCommander.startUpload(sa, getLastData())
    fun stopUpload() = serviceCommander.stopUpload()
    fun sendBuffer() = serviceCommander.sendBuffer()
    fun getLastData(): String? = lastData
}