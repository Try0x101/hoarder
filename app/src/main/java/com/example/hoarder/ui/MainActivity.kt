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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.ConcurrentDataManager
import com.example.hoarder.data.DataUtils
import com.example.hoarder.data.Prefs
import com.example.hoarder.service.BackgroundService
import com.example.hoarder.utils.NetUtils
import com.example.hoarder.utils.NotifUtils
import com.example.hoarder.utils.PermHandler

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val permHandler by lazy { PermHandler(this, h) }
    private val ui by lazy { UIHelper(this, prefs) }
    private val dataManager by lazy { ConcurrentDataManager() }

    private var ld: String? = null
    private val rcvs = mutableListOf<BroadcastReceiver>()

    private val dr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("jsonString")?.let { j ->
                ld = j
                dataManager.setJsonData(j)
                ui.updateRawJson(j)
            }
        }
    }

    private val ur = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            ui.updateUploadUI(
                prefs.isDataUploadEnabled(),
                i?.getStringExtra("status"),
                i?.getStringExtra("message"),
                i?.getLongExtra("totalUploadedBytes", 0L),
                i?.getLongExtra("lastUploadSizeBytes", -1L)?.takeIf { it != -1L }
            )
        }
    }

    private val pr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == "com.example.hoarder.PERMISSIONS_REQUIRED") {
                Toast.makeText(this@MainActivity, "Location permissions required", Toast.LENGTH_LONG).show()
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

        registerReceivers()

        if (prefs.isFirstRun()) {
            permHandler.setPendingAction { handleFirstRun() }
            if (permHandler.hasAllPerms()) handleFirstRun() else permHandler.requestPerms()
        } else {
            if (permHandler.hasAllPerms()) ss() else permHandler.requestPerms()
        }
    }

    private fun registerReceivers() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(dr, IntentFilter("com.example.hoarder.DATA_UPDATE")).also { rcvs.add(dr) }
        lbm.registerReceiver(ur, IntentFilter("com.example.hoarder.UPLOAD_STATUS")).also { rcvs.add(ur) }
        lbm.registerReceiver(pr, IntentFilter("com.example.hoarder.PERMISSIONS_REQUIRED")).also { rcvs.add(pr) }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        permHandler.handleResult(rc, res)
    }

    override fun onResume() {
        super.onResume()
        NotifUtils.createSilentChannel(this)
        if (permHandler.hasAllPerms() && !DataUtils.isServiceRunning(this, BackgroundService::class.java)) {
            ss()
            rs()
        }
        ui.updateRawJson(getLastData())
    }

    override fun onDestroy() {
        super.onDestroy()
        rcvs.forEach { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        h.removeCallbacksAndMessages(null)
        dataManager.clearData()
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
        ui.updateUploadUI(false, null, null, null, null)

        ss()
        startCollection()

        Toast.makeText(this, "Setup complete. App will run in background.", Toast.LENGTH_SHORT).show()
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
        ld = null
        dataManager.setJsonData(null)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))
    }

    fun startUpload(sa: String) {
        if (NetUtils.isValidIpPort(sa)) {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", sa))
        }
    }

    fun stopUpload() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))
    }

    fun getLastData(): String? = dataManager.getJsonData() ?: ld
}