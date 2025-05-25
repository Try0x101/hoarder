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
import androidx.activity.ComponentActivity
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

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val isFirstLaunch = sharedPrefs.getBoolean(KEY_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            sharedPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
            dataCollectionSwitch.isChecked = true
            serverUploadSwitch.isChecked = false
            sharedPrefs.edit().putBoolean(KEY_COLLECTION_TOGGLE_STATE, true).apply()
            sharedPrefs.edit().putBoolean(KEY_UPLOAD_TOGGLE_STATE, false).apply()
            sharedPrefs.edit().putString(KEY_SERVER_IP_PORT, "").apply()
        } else {
            dataCollectionSwitch.isChecked = sharedPrefs.getBoolean(KEY_COLLECTION_TOGGLE_STATE, true)
            serverUploadSwitch.isChecked = sharedPrefs.getBoolean(KEY_UPLOAD_TOGGLE_STATE, false)
        }

        updateSwitchTint(dataCollectionSwitch, dataCollectionSwitch.isChecked)
        updateSwitchTint(serverUploadSwitch, serverUploadSwitch.isChecked)
        updateServerUploadTitle(serverUploadSwitch.isChecked)

        val savedServerIpPort = sharedPrefs.getString(KEY_SERVER_IP_PORT, "")
        serverIpPortEditText.setText(savedServerIpPort)
        if (!serverUploadSwitch.isChecked) {
            uploadedBytesTextView.text = "Uploaded: 0 B"
            uploadMessageTextView.text = "Status: Paused\n"
            uploadMessageTextView.setTextColor(ContextCompat.getColor(this, R.color.amoled_light_gray))
        }

        switchAndIconContainer.post {
            val hitRect = Rect()
            dataCollectionSwitch.getHitRect(hitRect)
            val expandAmount = 100
            hitRect.left -= expandAmount
            hitRect.top -= expandAmount
            hitRect.right += expandAmount
            hitRect.bottom += expandAmount
            switchAndIconContainer.touchDelegate = TouchDelegate(hitRect, dataCollectionSwitch)
        }

        findViewById<LinearLayout>(R.id.serverUploadSwitchContainer).post {
            val hitRect = Rect()
            serverUploadSwitch.getHitRect(hitRect)
            val expandAmount = 100
            hitRect.left -= expandAmount
            hitRect.top -= expandAmount
            hitRect.right += expandAmount
            hitRect.bottom += expandAmount
            findViewById<LinearLayout>(R.id.serverUploadSwitchContainer).touchDelegate = TouchDelegate(hitRect, serverUploadSwitch)
        }


        rawDataContent.visibility = View.GONE
        dataTextView.text = "Data: Collapsed to save resources."

        rawDataHeader.setOnClickListener {
            if (rawDataContent.visibility == View.GONE) {
                rawDataContent.visibility = View.VISIBLE
                displayRawPrettyPrintData(latestJsonData)
            } else {
                rawDataContent.visibility = View.GONE
                dataTextView.text = "Data: Collapsed to save resources."
            }
        }

        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRawDataTitle(isChecked)
            sharedPrefs.edit().putBoolean(KEY_COLLECTION_TOGGLE_STATE, isChecked).apply()
            updateSwitchTint(dataCollectionSwitch, isChecked)
            if (isChecked) {
                sendCommandToService(BackgroundService.ACTION_START_COLLECTION)
                if (rawDataContent.visibility == View.VISIBLE) {
                    displayRawPrettyPrintData(latestJsonData)
                } else {
                    dataTextView.text = "Data: Collecting in background (card collapsed)."
                }
            } else {
                sendCommandToService(BackgroundService.ACTION_STOP_COLLECTION)
                dataTextView.text = "Data: Collection paused by switch."
            }
        }

        saveServerIpButton.setOnClickListener {
            val ipPort = serverIpPortEditText.text.toString()
            val parts = ipPort.split(":")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].toIntOrNull() != null && parts[1].toInt() > 0 && parts[1].toInt() <= 65535) {
                sharedPrefs.edit()
                    .putString(KEY_SERVER_IP_PORT, ipPort)
                    .apply()
                Toast.makeText(this, "Server IP:Port saved: $ipPort", Toast.LENGTH_SHORT).show()
                serverIpPortEditText.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(serverIpPortEditText.windowToken, 0)

                if (serverUploadSwitch.isChecked) {
                    sendCommandToService(BackgroundService.ACTION_START_UPLOAD, ipPort)
                }
            } else {
                Toast.makeText(this, "Invalid Server IP:Port format. Use e.g., 188.132.234.72:5000", Toast.LENGTH_LONG).show()
            }
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateServerUploadTitle(isChecked)
            sharedPrefs.edit().putBoolean(KEY_UPLOAD_TOGGLE_STATE, isChecked).apply()
            updateSwitchTint(serverUploadSwitch, isChecked)
            if (isChecked) {
                val ipPort = serverIpPortEditText.text.toString()
                val parts = ipPort.split(":")
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].toIntOrNull() != null && parts[1].toInt() > 0 && parts[1].toInt() <= 65535) {
                    sendCommandToService(BackgroundService.ACTION_START_UPLOAD, ipPort)
                    uploadMessageTextView.text = "Status: Connecting...\n"
                    uploadMessageTextView.setTextColor(ContextCompat.getColor(this, R.color.amoled_light_gray))
                } else {
                    Toast.makeText(this, "Server IP:Port is invalid/empty. Cannot start upload.", Toast.LENGTH_LONG).show()
                    serverUploadSwitch.isChecked = false
                    uploadedBytesTextView.text = "Uploaded: 0 B"
                    uploadMessageTextView.text = "Status: Error - Invalid IP:Port\n"
                    uploadMessageTextView.setTextColor(ContextCompat.getColor(this, R.color.amoled_red))
                }
            } else {
                sendCommandToService(BackgroundService.ACTION_STOP_UPLOAD)
                uploadedBytesTextView.text = "Uploaded: 0 B"
                uploadMessageTextView.text = "Status: Paused\n"
                uploadMessageTextView.setTextColor(ContextCompat.getColor(this, R.color.amoled_light_gray))
            }
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(dataReceiver, IntentFilter("com.example.hoarder.DATA_UPDATE"))
        LocalBroadcastManager.getInstance(this).registerReceiver(uploadStatusReceiver, IntentFilter("com.example.hoarder.UPLOAD_STATUS"))

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateRawDataTitle(dataCollectionSwitch.isChecked)
        updateServerUploadTitle(serverUploadSwitch.isChecked)
        updateSwitchTint(dataCollectionSwitch, dataCollectionSwitch.isChecked)
        updateSwitchTint(serverUploadSwitch, serverUploadSwitch.isChecked)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uploadStatusReceiver)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }


        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            handlePermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            handlePermissionsGranted()
        }
    }

    private fun handlePermissionsGranted() {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasWifiState = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        val hasChangeWifiState = ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        val hasNetworkState = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED
        val hasInternet = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasForegroundServiceLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true


        val canStartService = (hasFineLocation || hasCoarseLocation) && hasPhoneState && hasWifiState && hasChangeWifiState && hasNotifications && hasForegroundServiceLocation && hasNetworkState && hasInternet

        if (canStartService) {
            startBackgroundService()

            var displayMessage = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasBackgroundLocation) {
                    displayMessage += "For full location data collection, please go to App Settings -> Permissions -> Location and select 'Allow all the time'.\n"
                }
            }
            if (displayMessage.isEmpty()) {
                displayMessage = "Data collection enabled."
            }
            dataTextView.text = displayMessage
        } else {
            dataCollectionSwitch.isChecked = false
            serverUploadSwitch.isChecked = false
            updateRawDataTitle(false)
            updateServerUploadTitle(false)
            updateSwitchTint(dataCollectionSwitch, false)
            updateSwitchTint(serverUploadSwitch, false)

            var errorMessage = "Not all required permissions are granted. Some data may be unavailable.\n"
            val missingCritical = mutableListOf<String>()

            if (!(hasFineLocation || hasCoarseLocation)) missingCritical.add("Location (fine or coarse)")
            if (!hasPhoneState) missingCritical.add("Phone State")
            if (!hasWifiState) missingCritical.add("Wi-Fi State")
            if (!hasChangeWifiState) missingCritical.add("Change Wi-Fi State")
            if (!hasNetworkState) missingCritical.add("Network State")
            if (!hasInternet) missingCritical.add("Internet")
            if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) missingCritical.add("Notifications")
            if (!hasForegroundServiceLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) missingCritical.add("Background Location Service")

            if (missingCritical.isNotEmpty()) {
                errorMessage += "Missing critical permissions: ${missingCritical.joinToString(", ")}"
            } else {
                errorMessage += "Check permissions in app settings."
            }
            dataTextView.text = errorMessage
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun sendCommandToService(action: String, ipPort: String? = null) {
        val intent = Intent(action)
        ipPort?.let {
            intent.putExtra("ipPort", it)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateRawDataTitle(isActive: Boolean) {
        val statusText = if (isActive) "(Active)" else "(Inactive)"
        rawDataTitleTextView.text = "Raw data $statusText"
    }

    private fun updateServerUploadTitle(isUploading: Boolean) {
        val statusText = if (isUploading) "(Active)" else "(Inactive)"
        serverUploadTitleTextView.text = "Server Upload $statusText"
    }

    private fun updateSwitchTint(switch: Switch, isChecked: Boolean) {
        if (isChecked) {
            switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.amoled_white)
            switch.trackTintList = ContextCompat.getColorStateList(this, R.color.amoled_true_blue)
        } else {
            switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.amoled_white)
            switch.trackTintList = ContextCompat.getColorStateList(this, R.color.amoled_medium_gray)
        }
    }

    private fun displayRawPrettyPrintData(jsonString: String?) {
        if (jsonString == null) {
            dataTextView.text = "Data: No data available yet."
            return
        }
        try {
            val parsedJson = JsonParser.parseString(jsonString)
            dataTextView.text = gson.toJson(parsedJson)
        } catch (e: Exception) {
            dataTextView.text = "Error parsing or pretty printing JSON: ${e.message}\nRaw JSON:\n$jsonString"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
