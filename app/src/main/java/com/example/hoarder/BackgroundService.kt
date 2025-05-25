package com.example.hoarder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle // Added import for Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.util.Log
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

class BackgroundService : Service() {

    private lateinit var handler: Handler
    private lateinit var dataCollectionRunnable: Runnable
    private lateinit var uploadLoopRunnable: Runnable
    private lateinit var locationManager: LocationManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sharedPrefs: SharedPreferences

    private var lastKnownLocation: Location? = null
    private var latestBatteryData: Map<String, Any>? = null
    private var isCollectionActive: Boolean = false
    private var isUploadActive: Boolean = false
    private var serverIpAddress: String = ""
    private var serverPort: Int = 5000
    private var latestJsonData: String? = null
    private var totalUploadedBytes: Long = 0L
    private var lastSentUploadStatus: String? = null
    private var lastSentMessageContent: Pair<String, String>? = null
    private var lastNetworkErrorSentTimestampMs: Long = 0L
    private val NETWORK_ERROR_MESSAGE_COOLDOWN_MS = 5000L // 5 seconds

    private val PREFS_NAME = "HoarderServicePrefs"
    private val MAIN_ACTIVITY_PREFS_NAME = "HoarderPrefs"
    private val KEY_TOGGLE_STATE = "dataCollectionToggleState"
    private val KEY_UPLOAD_TOGGLE_STATE = "dataUploadToggleState"
    private val KEY_SERVER_IP_PORT = "serverIpPortAddress"

    private val TRAFFIC_UPDATE_INTERVAL_MS = 1000L
    private val UPLOAD_INTERVAL_MS = 1000L


    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val batteryReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = level * 100 / scale.toFloat()

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val statusString = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                    else -> "Unknown"
                }

                val currentNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                } else {
                    0
                }

                val chargeCounter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                } else {
                    0L
                }

                val currentCapacityPercent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                } else {
                    0
                }

                val remainingCapacityMah = chargeCounter / 1000.0
                val estimatedFullCapacityMah = if (currentCapacityPercent > 0) {
                    (remainingCapacityMah / currentCapacityPercent) * 100
                } else {
                    0.0
                }

                latestBatteryData = mapOf(
                    "percent" to batteryPct,
                    "status" to statusString,
                    "current_mA" to currentNow,
                    "remaining_capacity_mAh" to remainingCapacityMah,
                    "estimated_full_capacity_mAh" to estimatedFullCapacityMah
                )
            }
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_COLLECTION -> {
                    if (!isCollectionActive) {
                        isCollectionActive = true
                        startDataCollectionLoop()
                    }
                }
                ACTION_STOP_COLLECTION -> {
                    if (isCollectionActive) {
                        isCollectionActive = false
                        handler.removeCallbacks(dataCollectionRunnable)
                    }
                }
                ACTION_START_UPLOAD -> {
                    val ipPort = intent?.getStringExtra("ipPort")
                    val parts = ipPort?.split(":")
                    if (parts != null && parts.size == 2 && parts[0].isNotBlank() && parts[1].toIntOrNull() != null && parts[1].toInt() > 0 && parts[1].toInt() <= 65535) {
                        serverIpAddress = parts[0]
                        serverPort = parts[1].toInt()

                        isUploadActive = true
                        lastSentUploadStatus = null
                        totalUploadedBytes = 0L
                        sendUploadStatus("Connecting", "Attempting to connect...", totalUploadedBytes)
                        startUploadLoop()
                    } else {
                        isUploadActive = false
                        handler.removeCallbacks(uploadLoopRunnable)
                        sendUploadStatus("Error", "Invalid Server IP:Port for starting upload.", 0L)
                    }
                }
                ACTION_STOP_UPLOAD -> {
                    if (isUploadActive) {
                        isUploadActive = false
                        handler.removeCallbacks(uploadLoopRunnable)
                        totalUploadedBytes = 0L
                        lastSentUploadStatus = null
                        sendUploadStatus("Paused", "Upload paused.", totalUploadedBytes)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        val controlFilter = IntentFilter().apply {
            addAction(ACTION_START_COLLECTION)
            addAction(ACTION_STOP_COLLECTION)
            addAction(ACTION_START_UPLOAD)
            addAction(ACTION_STOP_UPLOAD)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(controlReceiver, controlFilter)

        dataCollectionRunnable = object : Runnable {
            override fun run() {
                if (isCollectionActive) {
                    collectAndSendAllData()
                    handler.postDelayed(this, TRAFFIC_UPDATE_INTERVAL_MS)
                }
            }
        }

        uploadLoopRunnable = object : Runnable {
            override fun run() {
                if (isUploadActive && latestJsonData != null && serverIpAddress.isNotBlank() && serverPort > 0) {
                    Thread {
                        latestJsonData?.let { data -> uploadDataToServer(data) }
                    }.start()
                    if (isUploadActive) {
                        handler.postDelayed(this, UPLOAD_INTERVAL_MS)
                    }
                } else if (isUploadActive && (serverIpAddress.isBlank() || serverPort <= 0)) {
                    sendUploadStatus("Error", "Server IP or Port became invalid.", totalUploadedBytes)
                    isUploadActive = false
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Collecting device data in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startLocationUpdates()

        val mainActivityPrefs = applicationContext.getSharedPreferences(MAIN_ACTIVITY_PREFS_NAME, Context.MODE_PRIVATE)
        val isCollectionToggleOn = mainActivityPrefs.getBoolean(KEY_TOGGLE_STATE, true)
        val isUploadToggleOn = mainActivityPrefs.getBoolean(KEY_UPLOAD_TOGGLE_STATE, false)
        val savedServerIpPort = mainActivityPrefs.getString(KEY_SERVER_IP_PORT, "")

        val parts = savedServerIpPort?.split(":")
        if (parts != null && parts.size == 2 && parts[0].isNotBlank() && parts[1].toIntOrNull() != null) {
            serverIpAddress = parts[0]
            serverPort = parts[1].toInt()
        } else {
            serverIpAddress = ""
            serverPort = 0
        }

        if (isCollectionToggleOn) {
            if (!isCollectionActive) {
                isCollectionActive = true
                startDataCollectionLoop()
            }
        } else {
            isCollectionActive = false
            handler.removeCallbacks(dataCollectionRunnable)
        }

        if (isUploadToggleOn && serverIpAddress.isNotBlank() && serverPort > 0) {
            isUploadActive = true
            lastSentUploadStatus = null
            totalUploadedBytes = 0L
            sendUploadStatus("Connecting", "Service (re)start, attempting to connect...", totalUploadedBytes)
            startUploadLoop()
        } else {
            isUploadActive = false
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(dataCollectionRunnable)
        handler.removeCallbacks(uploadLoopRunnable)
        locationManager.removeUpdates(locationListener)
        unregisterReceiver(batteryReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(controlReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Hoarder Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                0f,
                locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000,
                0f,
                locationListener
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun startDataCollectionLoop() {
        handler.removeCallbacks(dataCollectionRunnable)
        if (isCollectionActive) {
            handler.post(dataCollectionRunnable)
        }
    }

    private fun startUploadLoop() {
        handler.removeCallbacks(uploadLoopRunnable)
        if (isUploadActive && serverIpAddress.isNotBlank() && serverPort > 0) {
            handler.post(uploadLoopRunnable)
        } else if (isUploadActive) {
            isUploadActive = false
            sendUploadStatus("Error", "Cannot start upload: Server IP or Port is invalid.", totalUploadedBytes)
        }
    }

    private fun collectAndSendAllData() {
        val dataMap = mutableMapOf<String, Any>()

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        dataMap["device_id"] = deviceId

        val deviceInfo = mutableMapOf<String, Any>()
        deviceInfo["deviceName"] = Build.MODEL
        deviceInfo["deviceId"] = deviceId
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        deviceInfo["dateTime"] = dateFormat.format(Date())
        dataMap["deviceInfo"] = deviceInfo

        latestBatteryData?.let {
            dataMap["batteryInfo"] = it
        } ?: run {
            dataMap["batteryInfo"] = mapOf("status" to "Battery data unavailable")
        }

        lastKnownLocation?.let {
            dataMap["gps"] = mapOf(
                "latitude" to it.latitude,
                "longitude" to it.longitude,
                "altitude" to it.altitude,
                "accuracy" to it.accuracy,
                "bearing" to it.bearing,
                "speed" to it.speed,
                "time" to it.time
            )
        } ?: run {
            dataMap["gps"] = mapOf("status" to "GPS data unavailable")
        }

        try {
            val mobileNetworkData = mutableMapOf<String, Any>()
            mobileNetworkData["operatorName"] = telephonyManager.networkOperatorName
            mobileNetworkData["networkType"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (telephonyManager.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                    TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                    TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
                    TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
                    TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
                    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                    else -> "Unknown"
                }
            } else {
                "Unknown"
            }

            val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo
            val cellInfoString = StringBuilder()
            cellInfoList?.forEach { cellInfo ->
                cellInfoString.append(cellInfo.toString()).append("\n")
            }
            mobileNetworkData["cellInfo"] = if (cellInfoString.isNotEmpty()) cellInfoString.toString() else "Cell info unavailable"
            dataMap["mobileNetwork"] = mobileNetworkData
        } catch (e: SecurityException) {
            dataMap["mobileNetwork"] = mapOf("status" to "No permission to read phone state or location")
        }

        val wifiData = mutableMapOf<String, Any>()
        val wifiInfo = wifiManager.connectionInfo
        wifiData["SSID"] = wifiInfo.ssid
        wifiData["BSSID"] = wifiInfo.bssid
        wifiData["RSSI"] = wifiInfo.rssi
        wifiData["linkSpeed"] = wifiInfo.linkSpeed
        wifiData["ipAddress"] = formatIpAddress(wifiInfo.ipAddress)

        dataMap["wifi"] = wifiData

        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val networkState = mutableMapOf<String, Any>()

        networkState["isConnected"] = activeNetwork != null
        if (networkCapabilities != null) {
            networkState["hasTransportWifi"] = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            networkState["hasTransportCellular"] = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            networkState["hasTransportEthernet"] = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

            networkState["linkDownstreamBandwidthKbps"] = networkCapabilities.linkDownstreamBandwidthKbps
            networkState["linkUpstreamBandwidthKbps"] = networkCapabilities.linkUpstreamBandwidthKbps
        }

        val totalRxBytes = TrafficStats.getTotalRxBytes()
        val totalTxBytes = TrafficStats.getTotalTxBytes()

        networkState["totalDownloadBytes"] = totalRxBytes
        networkState["totalUploadBytes"] = totalTxBytes

        dataMap["networkConnectivityState"] = networkState

        val gson = GsonBuilder().setPrettyPrinting().create()
        val jsonString = gson.toJson(dataMap)

        latestJsonData = jsonString
        val dataIntent = Intent("com.example.hoarder.DATA_UPDATE")
        dataIntent.putExtra("jsonString", jsonString)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(dataIntent)
    }

    private fun uploadDataToServer(jsonString: String) {
        if (serverIpAddress.isBlank() || serverPort <= 0) {
            sendUploadStatus("Error", "Server IP or Port not set.", totalUploadedBytes)
            return
        }

        val urlString = "http://$serverIpAddress:$serverPort/api/telemetry"
        var urlConnection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.requestMethod = "POST"
            urlConnection.setRequestProperty("Content-Type", "application/json")
            urlConnection.setRequestProperty("Content-Encoding", "gzip")
            urlConnection.doOutput = true
            urlConnection.connectTimeout = 10000
            urlConnection.readTimeout = 10000

            val outputStream = ByteArrayOutputStream()
            val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)

            if (jsonBytes.isEmpty()) {
                sendUploadStatus("Error", "JSON data is empty for compression.", totalUploadedBytes)
                return
            }

            GZIPOutputStream(outputStream, 8192).use { gzipOs ->
                gzipOs.write(jsonBytes)
            }
            val compressedData = outputStream.toByteArray()

            if (compressedData.isEmpty()) {
                sendUploadStatus("Error", "Compressed data is empty after compression.", totalUploadedBytes)
                return
            }

            urlConnection.outputStream.write(compressedData)
            urlConnection.outputStream.flush()

            val responseCode = urlConnection.responseCode
            val responseMessage = urlConnection.responseMessage

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = urlConnection.inputStream.bufferedReader().use { it.readText() }
                totalUploadedBytes += compressedData.size.toLong()
                sendUploadStatus("OK", "Uploaded successfully.", totalUploadedBytes)
            } else {
                val errorStream = urlConnection.errorStream
                val errorResponse = errorStream?.bufferedReader()?.use { it.readText() } ?: "No error response"
                sendUploadStatus("HTTP Error", "$responseCode: $responseMessage. Server response: $errorResponse", totalUploadedBytes)
            }

        } catch (e: Exception) {
            sendUploadStatus("Network Error", "Failed to connect: ${e.message}", totalUploadedBytes)
        } finally {
            urlConnection?.disconnect()
        }
    }

    private fun sendUploadStatus(status: String, message: String, uploadedBytes: Long) {
        val currentMessagePair = Pair(status, message)
        val currentTimeMs = System.currentTimeMillis()

        var shouldSendFullUpdate = true

        // Check for recurring Network Error for the *same serverIpAddress* within cooldown
        if (status == "Network Error" && 
            serverIpAddress.isNotBlank() && // Cooldown logic only applies if serverIpAddress is set
            message.contains(serverIpAddress)) { // Current error is related to our target server

            if (lastSentUploadStatus == "Network Error" &&
                lastSentMessageContent?.first == "Network Error" && // Last status was also Network Error
                lastSentMessageContent?.second?.contains(serverIpAddress) == true && // Last error was for the same server
                currentTimeMs - lastNetworkErrorSentTimestampMs < NETWORK_ERROR_MESSAGE_COOLDOWN_MS) {
                // Suppress full update if it's a recurring Network Error for the same server within cooldown
                shouldSendFullUpdate = false
            }
        }

        val contentChanged = (lastSentUploadStatus != status || lastSentMessageContent != currentMessagePair)

        if (shouldSendFullUpdate && contentChanged) {
            // Send a full update (status, message, bytes)
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("status", status)
                putExtra("message", message)
                putExtra("totalUploadedBytes", uploadedBytes)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

            lastSentUploadStatus = status
            lastSentMessageContent = currentMessagePair
            // Update timestamp only when a Network Error *for the target server* is sent as a full update
            if (status == "Network Error" && serverIpAddress.isNotBlank() && message.contains(serverIpAddress)) {
                lastNetworkErrorSentTimestampMs = currentTimeMs
            }
        } else if (!shouldSendFullUpdate && status == "Network Error") {
            // Case: Network error for the same server, but new message details suppressed by cooldown.
            // Send updated bytes, but keep the displayed status and message stable (using the last sent full error).
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("totalUploadedBytes", uploadedBytes)
                // Ensure UI continues to show "Network Error" and the *first message of this sequence*
                if (lastSentUploadStatus == "Network Error" && lastSentMessageContent?.first == "Network Error") {
                    putExtra("status", lastSentUploadStatus) // Should be "Network Error"
                    putExtra("message", lastSentMessageContent?.second) // The message that initiated cooldown
                }
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        } else if (lastSentUploadStatus == status && lastSentMessageContent == currentMessagePair) {
            // Case: Status and message are identical to the last sent one (and not a suppressed Network Error).
            // Send only bytes to update the counter without changing the message.
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("totalUploadedBytes", uploadedBytes)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        }
        // If none of the above, no update is sent. This could happen if shouldSendFullUpdate is true but contentChanged is false,
        // which is already covered by the third `else if` (as it implies full content is identical).
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun formatIpAddress(ipAddress: Int): String {
        return String.format(
            Locale.getDefault(),
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    companion object {
        const val CHANNEL_ID = "HoarderServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_COLLECTION = "com.example.hoarder.START_COLLECTION"
        const val ACTION_STOP_COLLECTION = "com.example.hoarder.STOP_COLLECTION"
        const val ACTION_START_UPLOAD = "com.example.hoarder.START_UPLOAD"
        const val ACTION_STOP_UPLOAD = "com.example.hoarder.STOP_UPLOAD"
    }
}
