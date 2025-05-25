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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import android.util.Log
import android.content.SharedPreferences

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

    private val PREFS_NAME = "HoarderPrefs"
    private val KEY_FIRST_LAUNCH = "isFirstLaunch"
    private val KEY_TOGGLE_STATE = "dataCollectionToggleState"
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

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val isFirstLaunch = sharedPrefs.getBoolean(KEY_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            sharedPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
            dataCollectionSwitch.isChecked = true // Default to ON for first launch
            // Service will start collection based on this state in its onStartCommand
        } else {
            dataCollectionSwitch.isChecked = sharedPrefs.getBoolean(KEY_TOGGLE_STATE, false)
        }

        updateSwitchTint(dataCollectionSwitch.isChecked)


        // Increase touchable area for the switch
        switchAndIconContainer.post {
            val hitRect = Rect()
            dataCollectionSwitch.getHitRect(hitRect)
            val expandAmount = 100 // pixels to expand the touch area
            hitRect.left -= expandAmount
            hitRect.top -= expandAmount
            hitRect.right += expandAmount
            hitRect.bottom += expandAmount
            switchAndIconContainer.touchDelegate = TouchDelegate(hitRect, dataCollectionSwitch)
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
            sharedPrefs.edit().putBoolean(KEY_TOGGLE_STATE, isChecked).apply() // Save toggle state
            updateSwitchTint(isChecked)
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

        LocalBroadcastManager.getInstance(this).registerReceiver(dataReceiver, IntentFilter("com.example.hoarder.DATA_UPDATE"))

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateRawDataTitle(dataCollectionSwitch.isChecked)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
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
            Log.d("MainActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.d("MainActivity", "All permissions already granted, handling permissions.")
            handlePermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }
            Log.d("MainActivity", "Permissions granted: ${grantedPermissions.joinToString()}")
            Log.d("MainActivity", "Permissions denied: ${deniedPermissions.joinToString()}")
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
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasForegroundServiceLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true


        val canStartService = (hasFineLocation || hasCoarseLocation) && hasPhoneState && hasWifiState && hasChangeWifiState && hasNotifications && hasForegroundServiceLocation && hasNetworkState

        if (canStartService) {
            startBackgroundService()
            // The toggle state and start command are now handled in onCreate based on SharedPreferences
            // This method just ensures the service is started if permissions are granted.

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
            Log.d("MainActivity", "Service should be starting. Display message: $displayMessage")
        } else {
            dataCollectionSwitch.isChecked = false
            updateRawDataTitle(false) // Update title for initial OFF state
            updateSwitchTint(false) // Ensure tint is off if permissions are not granted

            var errorMessage = "Not all required permissions are granted. Some data may be unavailable.\n"
            val missingCritical = mutableListOf<String>()

            if (!(hasFineLocation || hasCoarseLocation)) missingCritical.add("Location (fine or coarse)")
            if (!hasPhoneState) missingCritical.add("Phone State")
            if (!hasWifiState) missingCritical.add("Wi-Fi State")
            if (!hasChangeWifiState) missingCritical.add("Change Wi-Fi State")
            if (!hasNetworkState) missingCritical.add("Network State")
            if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) missingCritical.add("Notifications")
            if (!hasForegroundServiceLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) missingCritical.add("Background Location Service")

            if (missingCritical.isNotEmpty()) {
                errorMessage += "Missing critical permissions: ${missingCritical.joinToString(", ")}"
            } else {
                errorMessage += "Check permissions in app settings."
            }
            dataTextView.text = errorMessage
            Log.w("MainActivity", "Cannot start service. Error: $errorMessage")
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Attempted to start BackgroundService as foreground service.")
    }

    private fun sendCommandToService(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d("MainActivity", "Sent command to service: $action")
    }

    private fun updateRawDataTitle(isActive: Boolean) {
        val statusText = if (isActive) "(Active)" else "(Inactive)"
        rawDataTitleTextView.text = "Raw data $statusText"
    }

    private fun updateSwitchTint(isChecked: Boolean) {
        if (isChecked) {
            dataCollectionSwitch.thumbTintList = ContextCompat.getColorStateList(this, R.color.amoled_white)
            dataCollectionSwitch.trackTintList = ContextCompat.getColorStateList(this, R.color.amoled_true_blue)
        } else {
            dataCollectionSwitch.thumbTintList = ContextCompat.getColorStateList(this, R.color.amoled_white)
            dataCollectionSwitch.trackTintList = ContextCompat.getColorStateList(this, R.color.amoled_medium_gray)
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
            Log.e("MainActivity", "Error parsing or pretty printing JSON", e)
        }
    }
}
