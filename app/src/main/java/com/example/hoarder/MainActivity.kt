package com.example.hoarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TouchDelegate
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.EditText
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.card.MaterialCardView
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import android.content.SharedPreferences
import android.widget.Toast
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private lateinit var dataTextView: TextView
    private lateinit var rawDataHeader: LinearLayout
    private lateinit var rawDataContent: LinearLayout
    private lateinit var dataCollectionSwitch: Switch
    private lateinit var switchAndIconContainer: LinearLayout
    private lateinit var rawDataTitleTextView: TextView
    private var latestJsonData: String? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private lateinit var serverUploadCard: com.google.android.material.card.MaterialCardView
    private lateinit var serverUploadTitleTextView: TextView
    private lateinit var serverUploadSwitch: Switch
    private lateinit var uploadedBytesTextView: TextView
    private lateinit var uploadMessageTextView: TextView
    private lateinit var serverIpPortEditText: EditText
    private lateinit var saveServerIpButton: Button
    private lateinit var serverUploadHeader: LinearLayout
    private lateinit var serverUploadContent: LinearLayout

    private val PREFS_NAME = "HoarderPrefs"
    private val KEY_FIRST_LAUNCH = "isFirstLaunch"
    private val KEY_COLLECTION_TOGGLE_STATE = "dataCollectionToggleState"
    private val KEY_UPLOAD_TOGGLE_STATE = "dataUploadToggleState"
    private val KEY_SERVER_IP_PORT = "serverIpPortAddress"
    private lateinit var sharedPrefs: SharedPreferences

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("jsonString")?.let { jsonString ->
                latestJsonData = jsonString
                if (rawDataContent.visibility == View.VISIBLE) {
                    displayRawPrettyPrintData(jsonString)
                }
            }
        }
    }

    private val uploadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            val message = intent?.getStringExtra("message")
            val totalUploadedBytes = intent?.getLongExtra("totalUploadedBytes", 0L)

            val formattedBytes = if (totalUploadedBytes != null) formatBytes(totalUploadedBytes) else "0 B"

            uploadedBytesTextView.text = "Uploaded: $formattedBytes"

            if (status != null || message != null) {
                val newUploadMessage = if (status == "OK") {
                    "Status: OK\n"
                } else if (status == "Paused") {
                    "Status: Paused\n"
                } else if (status != null && message != null) {
                    "Status: $status - $message\n"
                } else {
                    "\n"
                }
                uploadMessageTextView.text = newUploadMessage
                if (status == "OK") {
                    uploadMessageTextView.setTextColor(ContextCompat.getColor(context!!, R.color.amoled_green))
                } else if (status == "Paused") {
                    uploadMessageTextView.setTextColor(ContextCompat.getColor(context!!, R.color.amoled_light_gray))
                } else {
                    uploadMessageTextView.setTextColor(ContextCompat.getColor(context!!, R.color.amoled_red))
                }
            }
        }
    }

    private fun displayRawPrettyPrintData(jsonString: String) {
        try {
            val jsonElement = JsonParser.parseString(jsonString)
            val prettyJson = gson.toJson(jsonElement)
            dataTextView.text = prettyJson
        } catch (e: Exception) {
            dataTextView.text = "Error formatting JSON: ${e.message}"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dataTextView = findViewById(R.id.dataTextView)
        rawDataHeader = findViewById(R.id.rawDataHeader)
        rawDataContent = findViewById(R.id.rawDataContent)
        dataCollectionSwitch = findViewById(R.id.dataCollectionSwitch)
        switchAndIconContainer = findViewById(R.id.switchAndIconContainer)
        rawDataTitleTextView = rawDataHeader.findViewById(R.id.rawDataTitleTextView)

        serverUploadCard = findViewById(R.id.serverUploadCard)
        serverUploadTitleTextView = findViewById(R.id.serverUploadTitleTextView)
        serverUploadSwitch = findViewById(R.id.serverUploadSwitch)
        uploadedBytesTextView = findViewById(R.id.uploadedBytesTextView)
        uploadMessageTextView = findViewById(R.id.uploadMessageTextView)
        serverIpPortEditText = findViewById(R.id.serverIpPortEditText)
        saveServerIpButton = findViewById(R.id.saveServerIpButton)
        serverUploadHeader = findViewById(R.id.serverUploadHeader)
        serverUploadContent = findViewById(R.id.serverUploadContent)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize switches and UI state
        setupUIComponents()
        setupClickListeners()
        checkAndRequestPermissions()
        startBackgroundService()

        // Register broadcast receivers
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(dataReceiver, IntentFilter("com.example.hoarder.DATA_UPDATE"))
            registerReceiver(uploadStatusReceiver, IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
        }
    }

    private fun setupUIComponents() {
        // Expand touch area for switches
        val touchRect = Rect()
        switchAndIconContainer.post {
            switchAndIconContainer.getHitRect(touchRect)
            touchRect.top -= 100
            touchRect.bottom += 100
            touchRect.left -= 100
            touchRect.right += 100
            (switchAndIconContainer.parent as View).touchDelegate = TouchDelegate(touchRect, switchAndIconContainer)
        }

        // Load saved states
        val isCollectionEnabled = sharedPrefs.getBoolean(KEY_COLLECTION_TOGGLE_STATE, true)
        val isUploadEnabled = sharedPrefs.getBoolean(KEY_UPLOAD_TOGGLE_STATE, false)
        val savedServerIpPort = sharedPrefs.getString(KEY_SERVER_IP_PORT, "")

        dataCollectionSwitch.isChecked = isCollectionEnabled
        serverUploadSwitch.isChecked = isUploadEnabled
        serverIpPortEditText.setText(savedServerIpPort)
        
        // Set initial active/inactive status
        rawDataTitleTextView.text = if (isCollectionEnabled) "Json data (Active)" else "Json data (Inactive)"
        serverUploadTitleTextView.text = if (isUploadEnabled) "Server Upload (Active)" else "Server Upload (Inactive)"
        
        // Initially hide content sections
        rawDataContent.visibility = View.GONE
        serverUploadContent.visibility = View.GONE
    }

    private fun setupClickListeners() {
        rawDataHeader.setOnClickListener {
            if (rawDataContent.visibility == View.GONE) {
                rawDataContent.visibility = View.VISIBLE
                latestJsonData?.let { jsonString -> displayRawPrettyPrintData(jsonString) }
            } else {
                rawDataContent.visibility = View.GONE
            }
        }

        serverUploadHeader.setOnClickListener {
            if (serverUploadContent.visibility == View.GONE) {
                serverUploadContent.visibility = View.VISIBLE
            } else {
                serverUploadContent.visibility = View.GONE
            }
        }

        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean(KEY_COLLECTION_TOGGLE_STATE, isChecked).apply()
            rawDataTitleTextView.text = if (isChecked) "Json data (Active)" else "Json data (Inactive)"
            if (isChecked) {
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(BackgroundService.ACTION_START_COLLECTION))
            } else {
                // Clear data when disabled
                dataTextView.text = ""
                latestJsonData = null
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(BackgroundService.ACTION_STOP_COLLECTION))
            }
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean(KEY_UPLOAD_TOGGLE_STATE, isChecked).apply()
            if (isChecked) {
                val serverIpPort = serverIpPortEditText.text.toString()
                if (validateServerIpPort(serverIpPort)) {
                    serverUploadTitleTextView.text = "Server Upload (Active)"
                    val intent = Intent(BackgroundService.ACTION_START_UPLOAD).apply {
                        putExtra("ipPort", serverIpPort)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                } else {
                    serverUploadSwitch.isChecked = false
                    serverUploadTitleTextView.text = "Server Upload (Inactive)"
                    Toast.makeText(this, "Invalid server IP:Port", Toast.LENGTH_SHORT).show()
                }
            } else {
                serverUploadTitleTextView.text = "Server Upload (Inactive)"
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(BackgroundService.ACTION_STOP_UPLOAD))
            }
        }

        saveServerIpButton.setOnClickListener {
            val serverIpPort = serverIpPortEditText.text.toString()
            if (validateServerIpPort(serverIpPort)) {
                sharedPrefs.edit().putString(KEY_SERVER_IP_PORT, serverIpPort).apply()
                Toast.makeText(this, "Server address saved", Toast.LENGTH_SHORT).show()
                if (serverUploadSwitch.isChecked) {
                    val intent = Intent(BackgroundService.ACTION_START_UPLOAD).apply {
                        putExtra("ipPort", serverIpPort)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
            } else {
                Toast.makeText(this, "Invalid server IP:Port format", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val permissionsToRequest = mutableListOf<String>()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun validateServerIpPort(ipPort: String): Boolean {
        if (ipPort.isBlank()) return false
        
        val parts = ipPort.split(":")
        if (parts.size != 2) return false

        val ip = parts[0]
        val port = parts[1].toIntOrNull()

        // Basic IP address validation
        val ipParts = ip.split(".")
        if (ipParts.size != 4) return false

        for (part in ipParts) {
            val num = part.toIntOrNull()
            if (num == null || num < 0 || num > 255) return false
        }

        // Port validation
        if (port == null || port <= 0 || port > 65535) return false

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(dataReceiver)
            unregisterReceiver(uploadStatusReceiver)
        }
    }
}
