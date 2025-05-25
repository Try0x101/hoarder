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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
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

class BackgroundService : Service() {

    private lateinit var handler: Handler
    private lateinit var runnable: Runnable
    private lateinit var locationManager: LocationManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sharedPrefs: SharedPreferences

    private var lastKnownLocation: Location? = null
    private var latestBatteryData: Map<String, Any>? = null
    private var isCollectionActive: Boolean = false

    private val PREFS_NAME = "HoarderServicePrefs"
    private val KEY_TOGGLE_STATE = "dataCollectionToggleState"

    private val TRAFFIC_UPDATE_INTERVAL_MS = 1000L


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
                        Log.d("BackgroundService", "Received START_COLLECTION command.")
                        isCollectionActive = true
                        startDataCollectionLoop()
                    }
                }
                ACTION_STOP_COLLECTION -> {
                    if (isCollectionActive) {
                        Log.d("BackgroundService", "Received STOP_COLLECTION command.")
                        isCollectionActive = false
                        handler.removeCallbacks(runnable)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BackgroundService", "Service onCreate")
        handler = Handler(Looper.getMainLooper())
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        Log.d("BackgroundService", "ApplicationInfo: ${applicationContext.applicationInfo}")

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        val controlFilter = IntentFilter()
        controlFilter.addAction(ACTION_START_COLLECTION)
        controlFilter.addAction(ACTION_STOP_COLLECTION)
        LocalBroadcastManager.getInstance(this).registerReceiver(controlReceiver, controlFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BackgroundService", "Service onStartCommand called, attempting startForeground")
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
        Log.d("BackgroundService", "startForeground called")

        startLocationUpdates()

        // Check toggle state from MainActivity's preferences and start collection if needed
        val mainActivityPrefs = applicationContext.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
        val isToggleOn = mainActivityPrefs.getBoolean(KEY_TOGGLE_STATE, false)

        if (isToggleOn) {
            if (!isCollectionActive) {
                Log.d("BackgroundService", "onStartCommand: Toggle is ON, starting data collection loop.")
                isCollectionActive = true
                startDataCollectionLoop()
            }
        } else {
            Log.d("BackgroundService", "onStartCommand: Toggle is OFF, not starting data collection loop.")
            isCollectionActive = false
            handler.removeCallbacks(runnable)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("BackgroundService", "Service onDestroy")
        handler.removeCallbacks(runnable)
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
                Log.d("BackgroundService", "Notification channel created with HIGH importance")
            } else {
                Log.e("BackgroundService", "NotificationManager is null, cannot create channel")
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
            Log.d("BackgroundService", "Location updates requested")
        } catch (e: SecurityException) {
            Log.e("BackgroundService", "Location permission denied: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startDataCollectionLoop() {
        runnable = object : Runnable {
            override fun run() {
                if (isCollectionActive) {
                    collectAndSendAllData()
                    handler.postDelayed(this, TRAFFIC_UPDATE_INTERVAL_MS)
                } else {
                    Log.d("BackgroundService", "Data collection paused.")
                }
            }
        }
        handler.post(runnable)
        Log.d("BackgroundService", "Data collection loop started/resumed")
    }

    private fun collectAndSendAllData() {
        Log.d("BackgroundService", "collectAndSendAllData running at ${System.currentTimeMillis()}")
        val dataMap = mutableMapOf<String, Any>()

        val deviceInfo = mutableMapOf<String, Any>()
        deviceInfo["deviceName"] = Build.MODEL
        deviceInfo["deviceId"] = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
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
            Log.e("BackgroundService", "Mobile network data permission denied: ${e.message}")
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

        val dataIntent = Intent("com.example.hoarder.DATA_UPDATE")
        dataIntent.putExtra("jsonString", jsonString)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(dataIntent)
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
    }
}
