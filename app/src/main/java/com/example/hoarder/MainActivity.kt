package com.example.hoarder
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var dt: TextView
    private lateinit var ui: UIHelper
    private lateinit var prefs: Prefs
    private lateinit var permHandler: PermHandler

    private val h = Handler(Looper.getMainLooper())
    private var ld: String? = null
    private val g = GsonBuilder().setPrettyPrinting().create()

    private val dr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("jsonString")?.let { j ->
                ld = j
                if (ui.isDataVisible()) dj(j)
            }
        }
    }

    private val ur = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra("status")
            val m = i?.getStringExtra("message")
            val b = i?.getLongExtra("totalUploadedBytes", 0L)
            ui.updateStatus(s, m, b)
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

        dt = findViewById(R.id.dataTextView)

        prefs = Prefs(this)
        permHandler = PermHandler(this, h)
        ui = UIHelper(this, prefs)

        ui.setupUI()
        NotifUtils.createSilentChannel(this)

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(dr, IntentFilter("com.example.hoarder.DATA_UPDATE"))
            registerReceiver(ur, IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
            registerReceiver(pr, IntentFilter("com.example.hoarder.PERMISSIONS_REQUIRED"))
        }

        if (prefs.isFirstRun()) {
            permHandler.setPendingAction { handleFirstRun() }
            if (permHandler.hasAllPerms()) handleFirstRun() else permHandler.requestPerms()
        } else {
            if (permHandler.hasAllPerms()) ss() else permHandler.requestPerms()
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(dr)
            unregisterReceiver(ur)
            unregisterReceiver(pr)
        }
        h.removeCallbacksAndMessages(null)
    }

    fun dj(j: String) {
        try {
            val je = JsonParser.parseString(j)
            val pj = g.toJson(je)
            dt.text = pj
        } catch (e: Exception) {
            dt.text = "Error formatting JSON: ${e.message}"
        }
    }

    private fun handleFirstRun() {
        prefs.markFirstRunComplete()
        prefs.setDataCollectionEnabled(true)
        ui.updateDataCollectionUI(true)

        val cip = prefs.getServerAddress()
        if (cip.isBlank() || !NetUtils.isValidIpPort(cip)) {
            val dip = "127.0.0.1:5000"
            ui.setServerAddress(dip)
            prefs.setServerAddress(dip)
        }

        prefs.setDataUploadEnabled(true)
        ui.updateUploadUI(true)

        ss()
        startCollection()
        startUpload(prefs.getServerAddress())

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
        dt.text = ""
        ld = null
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))
    }

    fun startUpload(sa: String) {
        if (NetUtils.isValidIpPort(sa)) {
            // Update UI immediately to show 0 bytes
            ui.updateStatus("Connecting", "Attempting to connect...", 0L)
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", sa))
        }
    }

    fun stopUpload() {
        // Update UI immediately to show 0 bytes
        ui.updateStatus("Paused", "Upload paused.", 0L)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))
    }

    fun getLastData() = ld
}