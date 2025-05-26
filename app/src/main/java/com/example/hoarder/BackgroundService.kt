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
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrength
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
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.CRC32
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

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
    private var lastSuccessfullyUploadedJson: String? = null // Stores the last full JSON successfully uploaded
    private val gson = GsonBuilder().create() // Gson instance for delta logic
    private var totalUploadedBytes: Long = 0L
    private var lastSentUploadStatus: String? = null
    private var lastSentMessageContent: Pair<String, String>? = null
    private var lastNetworkErrorSentTimestampMs: Long = 0L
    private val NETWORK_ERROR_MESSAGE_COOLDOWN_MS = 5000L

    private val PREFS_NAME = "HoarderServicePrefs"
    private val MAIN_ACTIVITY_PREFS_NAME = "HoarderPrefs"
    private val KEY_TOGGLE_STATE = "dataCollectionToggleState"
    private val KEY_UPLOAD_TOGGLE_STATE = "dataUploadToggleState"
    private val KEY_SERVER_IP_PORT = "serverIpPortAddress"

    private val TRAFFIC_UPDATE_INTERVAL_MS = 1000L
    private val UPLOAD_INTERVAL_MS = 1000L

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastTrafficStatsTimestamp: Long = 0L

    // --- New Throttling State Variables ---
    private val ONE_MINUTE_MS = 60 * 1000L
    private val TEN_SECONDS_MS = 10 * 1000L
    private val PERCENTAGE_THRESHOLD = 0.10 // 10%

    private var lastTimeDrivenDeviceInfoSentMs: Long = 0L
    private var lastTimeDrivenBatteryInfoSentMs: Long = 0L // For non-status battery fields

    private var lastSentWifiRssi: Int? = null
    private var lastTimeDrivenWifiRssiSentMs: Long = 0L

    private var lastSentMobileCellRssi: Int? = null // Assuming primary cell's RSSI for one cell
    private var lastTimeDrivenMobileRssiSentMs: Long = 0L

    private var lastSentLinkDownstreamMbps: Double? = null
    private var lastSentLinkUpstreamMbps: Double? = null
    private var lastTimeDrivenLinkRatesSentMs: Long = 0L

    private var lastSentDownloadSpeedMbps: Double? = null
    private var lastTimeDrivenDownloadSpeedSentMs: Long = 0L

    private var lastSentUploadSpeedMbps: Double? = null
    // --- End New Throttling State Variables ---

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
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
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
                    "percent" to batteryPct.toInt(),
                    "status" to statusString,
                    "current_mA" to currentNow,
                    "remaining_capacity_mAh" to remainingCapacityMah.toInt(),
                    "estimated_full_capacity_mAh" to estimatedFullCapacityMah.toInt()
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
                        lastSuccessfullyUploadedJson = null // Reset for full upload
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
                        lastSuccessfullyUploadedJson = null // Clear last uploaded on stop
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
                        val currentFullJson = latestJsonData // Capture current state
                        currentFullJson?.let { fullJson ->
                            val (dataToSend, isDelta) = generateJsonToSend(fullJson)
                            if (dataToSend != null) {
                                uploadDataToServer(dataToSend, fullJson, isDelta)
                            } else {
                                // No changes, send a status update but don't call server
                                sendUploadStatus("No Change", "Data unchanged, skipping upload.", totalUploadedBytes)
                            }
                        }
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
            lastSuccessfullyUploadedJson = null // Reset for full upload on service (re)start
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
        val currentDateTime = Date()
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        deviceInfo["date"] = dateFormatter.format(currentDateTime)
        deviceInfo["time"] = timeFormatter.format(currentDateTime)

        val timeZone = TimeZone.getDefault()
        val currentOffsetMillis = timeZone.getOffset(currentDateTime.time)
        val hoursOffset = currentOffsetMillis / (1000 * 60 * 60)
        val gmtOffsetString = if (hoursOffset >= 0) {
            "GMT+$hoursOffset"
        } else {
            "GMT$hoursOffset"
        }
        deviceInfo["timezone"] = gmtOffsetString

        dataMap["deviceInfo"] = deviceInfo

        latestBatteryData?.let {
            dataMap["batteryInfo"] = it
        } ?: run {
            dataMap["batteryInfo"] = mapOf("status" to "Battery data unavailable")
        }

        lastKnownLocation?.let {
            val roundedAltitude = (it.altitude / 2).roundToInt() * 2
            val roundedAccuracy = (it.accuracy / 10).roundToInt() * 10
            val roundedBearing = it.bearing.roundToInt()
            val speedKmH = (it.speed * 3.6).roundToInt()

            val roundedLatitude = String.format(Locale.US, "%.4f", it.latitude).toDouble()
            val roundedLongitude = String.format(Locale.US, "%.4f", it.longitude).toDouble()

            dataMap["gps"] = mapOf(
                "latitude" to roundedLatitude,
                "longitude" to roundedLongitude,
                "altitude_m" to roundedAltitude,
                "accuracy_m" to roundedAccuracy,
                "bearing_deg" to roundedBearing,
                "speed_kmh" to speedKmH
            )
        } ?: run {
            dataMap["gps"] = mapOf("status" to "GPS data unavailable")
        }

        try {
            val mobileNetworkData = mutableMapOf<String, Any>()
            mobileNetworkData["operatorName"] = telephonyManager.networkOperatorName
            val activeNetworkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
            mobileNetworkData["networkType"] = activeNetworkType

            val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo
            val detailedCellInfo = mutableListOf<Map<String, Any>>()

            cellInfoList?.forEach { cellInfo ->
                if (cellInfo.isRegistered) {
                    val cellTypeString = when (cellInfo) {
                        is CellInfoLte -> "LTE"
                        is CellInfoWcdma -> "WCDMA"
                        is CellInfoGsm -> "GSM"
                        is CellInfoNr -> "5G NR"
                        else -> "Unknown"
                    }

                    if (cellTypeString == activeNetworkType) {
                        val cellMap = mutableMapOf<String, Any>()

                        when (cellInfo) {
                            is CellInfoLte -> {
                                cellMap["cid"] = cellInfo.cellIdentity.ci
                                cellMap["tac"] = cellInfo.cellIdentity.tac
                                cellMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                                cellMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                                val ssLte = cellInfo.cellSignalStrength
                                cellMap["signalStrength"] = mapOf(
                                    "rssi" to ssLte.rssi
                                )
                            }
                            is CellInfoWcdma -> {
                                cellMap["cid"] = cellInfo.cellIdentity.cid
                                cellMap["lac"] = cellInfo.cellIdentity.lac
                                cellMap["psc"] = cellInfo.cellIdentity.psc
                                cellMap["uarfcn"] = cellInfo.cellIdentity.uarfcn
                                cellMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                                cellMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                                val ssWcdma = cellInfo.cellSignalStrength
                                cellMap["signalStrength"] = mapOf(
                                    "rssi" to ssWcdma.dbm
                                )
                            }
                            is CellInfoGsm -> {
                                cellMap["cid"] = cellInfo.cellIdentity.cid
                                cellMap["lac"] = cellInfo.cellIdentity.lac
                                cellMap["arfcn"] = cellInfo.cellIdentity.arfcn
                                cellMap["bsic"] = cellInfo.cellIdentity.bsic
                                cellMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                                cellMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                                val ssGsm = cellInfo.cellSignalStrength
                                cellMap["signalStrength"] = mapOf(
                                    "rssi" to ssGsm.dbm
                                )
                            }
                            is CellInfoNr -> {
                                val cellIdentityNr = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                                cellMap["nci"] = cellIdentityNr?.nci ?: "N/A"
                                cellMap["tac"] = cellIdentityNr?.tac ?: -1
                                cellMap["mcc"] = cellIdentityNr?.mccString ?: "N/A"
                                cellMap["mnc"] = cellIdentityNr?.mncString ?: "N/A"
                                val ssNr = cellInfo.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                                cellMap["signalStrength"] = mapOf<String, Int>(
                                    "csiRsrp" to (ssNr?.csiRsrp ?: Int.MIN_VALUE),
                                    "csiRsrq" to (ssNr?.csiRsrq ?: Int.MIN_VALUE),
                                    "csiSinr" to (ssNr?.csiSinr ?: Int.MIN_VALUE),
                                    "ssRsrp" to (ssNr?.ssRsrp ?: Int.MIN_VALUE),
                                    "ssRsrq" to (ssNr?.ssRsrq ?: Int.MIN_VALUE),
                                    "ssSinr" to (ssNr?.ssSinr ?: Int.MIN_VALUE)
                                )
                            }
                            else -> {
                                cellMap["details"] = cellInfo.toString()
                            }
                        }
                        detailedCellInfo.add(cellMap)
                    }
                }
            }
            mobileNetworkData["cellInfo"] = if (detailedCellInfo.isNotEmpty()) detailedCellInfo else "Cell info unavailable or no active SIM detected for current network type"
            dataMap["mobileNetwork"] = mobileNetworkData
        } catch (e: SecurityException) {
            dataMap["mobileNetwork"] = mapOf("status" to "No permission to read phone state or location")
        }

        val wifiData = mutableMapOf<String, Any>()
        val wifiInfo = wifiManager.connectionInfo
        wifiData["SSID"] = wifiInfo.ssid
        wifiData["RSSI"] = wifiInfo.rssi
        wifiData["ipAddress"] = formatIpAddress(wifiInfo.ipAddress)

        dataMap["wifi"] = wifiData

        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val networkState = mutableMapOf<String, Any>()

        networkState["isConnected"] = activeNetwork != null
        if (networkCapabilities != null) {
            val linkDownstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
            val linkUpstreamKbps = networkCapabilities.linkUpstreamBandwidthKbps

            val linkDownstreamMbps = (linkDownstreamKbps.toDouble() / 1024.0 / 0.1).roundToInt() * 0.1
            val linkUpstreamMbps = (linkUpstreamKbps.toDouble() / 1024.0 / 0.1).roundToInt() * 0.1

            networkState["linkDownstreamMbps"] = String.format(Locale.US, "%.1f", linkDownstreamMbps).toDouble()
            networkState["linkUpstreamMbps"] = String.format(Locale.US, "%.1f", linkUpstreamMbps).toDouble()
        }

        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTimestamp = System.currentTimeMillis()

        var downloadSpeedMbps = 0.0
        var uploadSpeedMbps = 0.0

        if (lastTrafficStatsTimestamp > 0 && currentTimestamp > lastTrafficStatsTimestamp) {
            val deltaRxBytes = currentRxBytes - lastRxBytes
            val deltaTxBytes = currentTxBytes - lastTxBytes
            val deltaTimeSeconds = (currentTimestamp - lastTrafficStatsTimestamp) / 1000.0

            if (deltaTimeSeconds > 0) {
                downloadSpeedMbps = (deltaRxBytes * 8.0) / (1024 * 1024) / deltaTimeSeconds
                uploadSpeedMbps = (deltaTxBytes * 8.0) / (1024 * 1024) / deltaTimeSeconds
            }
        }

        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastTrafficStatsTimestamp = currentTimestamp

        val roundedDownloadSpeedMbps = (downloadSpeedMbps / 0.1).roundToInt() * 0.1
        val roundedUploadSpeedMbps = (uploadSpeedMbps / 0.1).roundToInt() * 0.1

        networkState["downloadSpeedMbps"] = String.format(Locale.US, "%.1f", roundedDownloadSpeedMbps).toDouble()
        networkState["uploadSpeedMbps"] = String.format(Locale.US, "%.1f", roundedUploadSpeedMbps).toDouble()

        dataMap["networkConnectivityState"] = networkState

        val gsonPretty = GsonBuilder().setPrettyPrinting().create()
        val jsonString = gsonPretty.toJson(dataMap)

        latestJsonData = jsonString
        val dataIntent = Intent("com.example.hoarder.DATA_UPDATE")
        dataIntent.putExtra("jsonString", jsonString)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(dataIntent)
    }

    private fun isSignificantlyChanged(current: Double?, previous: Double?, threshold: Double): Boolean {
        if (current == null && previous == null) return false
        if (current == null || previous == null) return true // Changed if one is null and other isn't
        if (previous == 0.0) return current != 0.0 // Avoid division by zero; change if current is non-zero
        return kotlin.math.abs((current - previous) / previous) > threshold
    }

    private fun generateJsonToSend(currentFullJson: String): Pair<String?, Boolean> {
        val currentTimeMs = System.currentTimeMillis()

        if (lastSuccessfullyUploadedJson == null) {
            Log.d("HoarderService", "No last successful upload, sending full data.")
            // Reset all throttling timestamps as we are sending a full packet
            lastTimeDrivenDeviceInfoSentMs = currentTimeMs
            lastTimeDrivenBatteryInfoSentMs = currentTimeMs
            lastTimeDrivenWifiRssiSentMs = currentTimeMs
            lastTimeDrivenMobileRssiSentMs = currentTimeMs
            lastTimeDrivenLinkRatesSentMs = currentTimeMs
            lastTimeDrivenDownloadSpeedSentMs = currentTimeMs
            // also reset last sent values that are part of throttling logic
            // (will be effectively set by the data in currentFullJson once it's parsed below for the next cycle)
            return Pair(currentFullJson, false) // Send full data, not a delta
        }

        val currentDataTree = com.google.gson.JsonParser.parseString(currentFullJson).asJsonObject
        val lastDataTree = com.google.gson.JsonParser.parseString(lastSuccessfullyUploadedJson).asJsonObject

        if (currentDataTree == lastDataTree) {
            Log.d("HoarderService", "Data is identical to last successful upload, skipping.")
            return Pair(null, false) // No changes
        }

        val delta = com.google.gson.JsonObject()
        var significantChangeMadeToDelta = false

        // Always include device_id
        currentDataTree.get("device_id")?.let { delta.add("device_id", it) }

        // 1. deviceInfo (Time throttling for "time" field)
        val currentDeviceInfo = currentDataTree.getAsJsonObject("deviceInfo")
        val lastDeviceInfo = lastDataTree.getAsJsonObject("deviceInfo")
        if (currentDeviceInfo != null) {
            if (lastDeviceInfo == null || currentDeviceInfo.get("deviceName") != lastDeviceInfo.get("deviceName") ||
                currentDeviceInfo.get("timezone") != lastDeviceInfo.get("timezone") ||
                (currentTimeMs - lastTimeDrivenDeviceInfoSentMs >= ONE_MINUTE_MS)) {
                delta.add("deviceInfo", currentDeviceInfo)
                significantChangeMadeToDelta = true
                if (currentDeviceInfo.get("time") != lastDeviceInfo?.get("time")) { // if time change drove this
                    lastTimeDrivenDeviceInfoSentMs = currentTimeMs
                }
            } else {
                // If only time/date changed and it's throttled, effectively send old deviceInfo to show no change for delta.
                // However, our delta only includes changed fields. So if time is throttled, deviceInfo won't be added unless other parts changed.
                // For simplicity, if time is the only change and throttled, this block is skipped, deviceInfo not added to delta.
                // If other parts of deviceInfo changed, it's added.
            }
        }


        // 2. batteryInfo (Time throttling for non-status fields)
        val currentBatteryInfo = currentDataTree.getAsJsonObject("batteryInfo")
        val lastBatteryInfo = lastDataTree.getAsJsonObject("batteryInfo")
        if (currentBatteryInfo != null) {
            val currentStatus = currentBatteryInfo.get("status")
            val lastStatus = lastBatteryInfo?.get("status")
            var batteryDeltaChanged = false

            val tempBatteryDelta = com.google.gson.JsonObject() // build battery changes here

            if (lastBatteryInfo == null || currentStatus != lastStatus) {
                tempBatteryDelta.add("status", currentStatus)
                batteryDeltaChanged = true
                significantChangeMadeToDelta = true
                lastTimeDrivenBatteryInfoSentMs = currentTimeMs // Status change resets timer for other fields too
            }

            // Check non-status fields
            val checkNonStatus = { fieldName: String ->
                val currentVal = currentBatteryInfo.get(fieldName)
                val lastVal = lastBatteryInfo?.get(fieldName)
                if (currentVal != lastVal) {
                    if (lastBatteryInfo == null || currentStatus != lastStatus || currentTimeMs - lastTimeDrivenBatteryInfoSentMs >= ONE_MINUTE_MS) {
                        tempBatteryDelta.add(fieldName, currentVal)
                        batteryDeltaChanged = true
                        significantChangeMadeToDelta = true
                    } else {
                        // throttled non-status change
                    }
                }
            }
            checkNonStatus("percent")
            checkNonStatus("current_mA")
            checkNonStatus("remaining_capacity_mAh")
            checkNonStatus("estimated_full_capacity_mAh")

            if (batteryDeltaChanged) {
                delta.add("batteryInfo", tempBatteryDelta)
                if (currentTimeMs - lastTimeDrivenBatteryInfoSentMs >= ONE_MINUTE_MS && currentStatus == lastStatus) {
                     lastTimeDrivenBatteryInfoSentMs = currentTimeMs // Update if non-status drove the change after throttle
                }
            }
        }


        // 3. GPS (Included if changed)
        val currentGps = currentDataTree.getAsJsonObject("gps")
        val lastGps = lastDataTree.getAsJsonObject("gps")
        if (currentGps != null && currentGps != lastGps) {
            delta.add("gps", currentGps)
            significantChangeMadeToDelta = true
        }

        // 4. WiFi (RSSI throttling)
        val currentWifi = currentDataTree.getAsJsonObject("wifi")
        val lastWifi = lastDataTree.getAsJsonObject("wifi")
        if (currentWifi != null) {
            val tempWifiDelta = com.google.gson.JsonObject()
            var wifiDeltaChanged = false

            val currentRssiEl = currentWifi.get("RSSI")
            if (currentRssiEl != null && !currentRssiEl.isJsonNull){
                val currentRssi = currentRssiEl.asInt
                 if (lastWifi == null || currentRssi != lastSentWifiRssi || currentTimeMs - lastTimeDrivenWifiRssiSentMs >= TEN_SECONDS_MS) {
                    if (currentRssi != lastSentWifiRssi) { // only add if changed
                        tempWifiDelta.addProperty("RSSI", currentRssi)
                        wifiDeltaChanged = true
                        significantChangeMadeToDelta = true
                        lastSentWifiRssi = currentRssi
                        lastTimeDrivenWifiRssiSentMs = currentTimeMs
                    } else if (currentTimeMs - lastTimeDrivenWifiRssiSentMs >= TEN_SECONDS_MS && lastSentWifiRssi != null) {
                         // If timer up but value same, send it to confirm it's still this value.
                        tempWifiDelta.addProperty("RSSI", currentRssi)
                        wifiDeltaChanged = true
                        significantChangeMadeToDelta = true
                        lastTimeDrivenWifiRssiSentMs = currentTimeMs // update time
                    }
                }
            }
            // Other WiFi fields (SSID, ipAddress)
            val checkWifiField = { fieldName: String ->
                val currentVal = currentWifi.get(fieldName)
                val lastVal = lastWifi?.get(fieldName)
                if (currentVal != lastVal && currentVal != null) { // only add if changed and not null
                    tempWifiDelta.add(fieldName, currentVal)
                    wifiDeltaChanged = true
                    significantChangeMadeToDelta = true
                }
            }
            checkWifiField("SSID")
            checkWifiField("ipAddress")
            // BSSID, linkSpeed can be added if needed following same pattern

            if (wifiDeltaChanged) {
                delta.add("wifi", tempWifiDelta)
            }
        }


        // 5. Mobile Network (RSSI throttling for the first registered cell of active type)
        val currentMobile = currentDataTree.getAsJsonObject("mobileNetwork")
        val lastMobile = lastDataTree.getAsJsonObject("mobileNetwork")
        if (currentMobile != null) {
            val tempMobileDelta = com.google.gson.JsonObject()
            var mobileDeltaChanged = false

            // OperatorName, networkType
             val checkMobileField = { fieldName: String ->
                val currentVal = currentMobile.get(fieldName)
                val lastVal = lastMobile?.get(fieldName)
                if (currentVal != lastVal && currentVal != null) {
                    tempMobileDelta.add(fieldName, currentVal)
                    mobileDeltaChanged = true
                    significantChangeMadeToDelta = true
                }
            }
            checkMobileField("operatorName")
            checkMobileField("networkType")

            val cellInfoArray = currentMobile.getAsJsonArray("cellInfo")
            if (cellInfoArray != null && cellInfoArray.size() > 0) {
                // Simplified: Check RSSI of the first cell for throttling
                val firstCell = cellInfoArray[0].asJsonObject
                val signalStrength = firstCell.getAsJsonObject("signalStrength")
                if (signalStrength != null && signalStrength.has("rssi")) {
                    val currentMobileRssi = signalStrength.get("rssi").asInt
                    if (lastMobile == null || currentMobileRssi != lastSentMobileCellRssi || currentTimeMs - lastTimeDrivenMobileRssiSentMs >= TEN_SECONDS_MS) {
                         if (currentMobileRssi != lastSentMobileCellRssi) {
                            // If actual cellInfo structure changed, send the whole array
                            // This simplified RSSI check might need to be part of a more complex cellInfo diff
                            // For now, if RSSI of first cell passes throttle, the current cellInfo is added if different from last.
                             if (currentMobile.get("cellInfo") != lastMobile?.get("cellInfo")) {
                                tempMobileDelta.add("cellInfo", currentMobile.get("cellInfo"))
                                mobileDeltaChanged = true
                                significantChangeMadeToDelta = true
                            }
                            lastSentMobileCellRssi = currentMobileRssi
                            lastTimeDrivenMobileRssiSentMs = currentTimeMs
                        } else if (currentTimeMs - lastTimeDrivenMobileRssiSentMs >= TEN_SECONDS_MS && lastSentMobileCellRssi != null){
                             if (currentMobile.get("cellInfo") != lastMobile?.get("cellInfo")) {
                                tempMobileDelta.add("cellInfo", currentMobile.get("cellInfo"))
                                mobileDeltaChanged = true
                                significantChangeMadeToDelta = true
                            }
                            lastTimeDrivenMobileRssiSentMs = currentTimeMs
                        }
                    }
                } else if (currentMobile.get("cellInfo") != lastMobile?.get("cellInfo")) { // No RSSI but other cell info changed
                     tempMobileDelta.add("cellInfo", currentMobile.get("cellInfo"))
                     mobileDeltaChanged = true
                     significantChangeMadeToDelta = true
                }
            } else if (currentMobile.get("cellInfo") != null && currentMobile.get("cellInfo") != lastMobile?.get("cellInfo")) { // e.g. cell info becomes unavailable
                 tempMobileDelta.add("cellInfo", currentMobile.get("cellInfo")) // Send "unavailable" status
                 mobileDeltaChanged = true
                 significantChangeMadeToDelta = true
            }


            if (mobileDeltaChanged) {
                delta.add("mobileNetwork", tempMobileDelta)
            }
        }


        // 6. Network Connectivity State (Link speed, Download/Upload speed throttling)
        val currentNetState = currentDataTree.getAsJsonObject("networkConnectivityState")
        val lastNetState = lastDataTree.getAsJsonObject("networkConnectivityState")
        if (currentNetState != null) {
            val tempNetStateDelta = com.google.gson.JsonObject()
            var netStateDeltaChanged = false

            // isConnected
            if (currentNetState.get("isConnected") != lastNetState?.get("isConnected")) {
                tempNetStateDelta.add("isConnected", currentNetState.get("isConnected"))
                netStateDeltaChanged = true; significantChangeMadeToDelta = true
            }

            // Link Downstream/Upstream (10% change AND 10 sec interval)
            val currentLinkDown = currentNetState.get("linkDownstreamMbps")?.asDouble
            val currentLinkUp = currentNetState.get("linkUpstreamMbps")?.asDouble

            val linkRatesChanged = (isSignificantlyChanged(currentLinkDown, lastSentLinkDownstreamMbps, PERCENTAGE_THRESHOLD) ||
                                   isSignificantlyChanged(currentLinkUp, lastSentLinkUpstreamMbps, PERCENTAGE_THRESHOLD))

            if (linkRatesChanged && (lastNetState == null || currentTimeMs - lastTimeDrivenLinkRatesSentMs >= TEN_SECONDS_MS)) {
                currentLinkDown?.let { tempNetStateDelta.addProperty("linkDownstreamMbps", it); lastSentLinkDownstreamMbps = it }
                currentLinkUp?.let { tempNetStateDelta.addProperty("linkUpstreamMbps", it); lastSentLinkUpstreamMbps = it }
                netStateDeltaChanged = true; significantChangeMadeToDelta = true
                lastTimeDrivenLinkRatesSentMs = currentTimeMs
            }

            // Download Speed (updated AND 10 sec interval)
            val currentDownload = currentNetState.get("downloadSpeedMbps")?.asDouble
            if (currentDownload != null && (lastNetState == null || currentDownload != lastSentDownloadSpeedMbps) &&
                (currentTimeMs - lastTimeDrivenDownloadSpeedSentMs >= TEN_SECONDS_MS) ) {
                tempNetStateDelta.addProperty("downloadSpeedMbps", currentDownload)
                netStateDeltaChanged = true; significantChangeMadeToDelta = true
                lastSentDownloadSpeedMbps = currentDownload
                lastTimeDrivenDownloadSpeedSentMs = currentTimeMs
            }

            // Upload Speed (10% change)
            val currentUpload = currentNetState.get("uploadSpeedMbps")?.asDouble
            if (currentUpload != null && isSignificantlyChanged(currentUpload, lastSentUploadSpeedMbps, PERCENTAGE_THRESHOLD)) {
                tempNetStateDelta.addProperty("uploadSpeedMbps", currentUpload)
                netStateDeltaChanged = true; significantChangeMadeToDelta = true
                lastSentUploadSpeedMbps = currentUpload
            }

            if (netStateDeltaChanged) {
                delta.add("networkConnectivityState", tempNetStateDelta)
            }
        }

        // Fallback: if no specific rules matched for a top-level key, but it changed, add it.
        // This is more for ensuring any other uncategorized changes are caught.
        for (key in currentDataTree.keySet()) {
            if (key == "device_id" || key == "deviceInfo" || key == "batteryInfo" || key == "gps" || key == "wifi" || key == "mobileNetwork" || key == "networkConnectivityState") continue

            val currentValue = currentDataTree.get(key)
            val lastValue = lastDataTree.get(key)
            if (currentValue != lastValue) {
                delta.add(key, currentValue)
                significantChangeMadeToDelta = true
            }
        }
        // Check for keys in lastDataTree that are not in currentDataTree (deleted)
        for (key in lastDataTree.keySet()) {
            if (!currentDataTree.has(key) && key != "device_id") { // device_id always present or handled
                delta.add(key, com.google.gson.JsonNull.INSTANCE) // Mark as deleted/null
                significantChangeMadeToDelta = true
            }
        }


        if (!significantChangeMadeToDelta && delta.size() <= 1) { // Only device_id might be there
             // Check if deviceInfo was the only potential change but got throttled
            val cdi = currentDataTree.getAsJsonObject("deviceInfo")
            val ldi = lastDataTree.getAsJsonObject("deviceInfo")
            val devInfoTimeOnlyChanged = cdi != null && ldi != null &&
                                         cdi.get("deviceName") == ldi.get("deviceName") &&
                                         cdi.get("timezone") == ldi.get("timezone") &&
                                         (cdi.get("time") != ldi.get("time") || cdi.get("date") != ldi.get("date"))
            if (devInfoTimeOnlyChanged && !(currentTimeMs - lastTimeDrivenDeviceInfoSentMs >= ONE_MINUTE_MS)) {
                 Log.d("HoarderService", "Delta empty or only device_id due to throttled time-only deviceInfo change. Skipping.")
                 return Pair(null, false)
            }
            if (delta.size() <=1 && !significantChangeMadeToDelta){ // still mostly empty
                 Log.d("HoarderService", "Delta effectively empty after throttling. Skipping.")
                 return Pair(null, false)
            }
        }
        Log.d("HoarderService", "Generated delta: ${delta.toString()}")
        return Pair(delta.toString(), true) // Send delta
    }

    private fun uploadDataToServer(jsonStringToSend: String, originalFullJson: String, isDelta: Boolean) {
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
            urlConnection.setRequestProperty("X-Data-Type", if (isDelta) "delta" else "full") // Custom header
            urlConnection.doOutput = true
            urlConnection.connectTimeout = 10000
            urlConnection.readTimeout = 10000

            val outputStream = ByteArrayOutputStream()
            val jsonBytes = jsonStringToSend.toByteArray(StandardCharsets.UTF_8)

            // Log the raw JSON data before compression
            Log.d("HoarderService", "Sending ${if(isDelta) "delta" else "full"} JSON data: $jsonStringToSend")

            if (jsonBytes.isEmpty()) {
                sendUploadStatus("Error", "JSON data is empty for compression.", totalUploadedBytes)
                return
            }

            // Manually build GZIP header (simplified)
            outputStream.write(byteArrayOf(
                0x1f.toByte(), // Magic number 1
                0x8b.toByte(), // Magic number 2
                Deflater.DEFLATED.toByte(), // Compression method (8 = deflate)
                0x00.toByte(), // Flags (no extra fields, comments, etc.)
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // MTIME (modification time)
                0x00.toByte(), // Extra flags
                0x03.toByte()  // OS (0x03 = Unix, common)
            ))

            // Create Deflater with compression level 7
            val deflater = Deflater(7, true) // level 7, nowrap = true for raw deflate
            DeflaterOutputStream(outputStream, deflater).use { deflaterOs ->
                deflaterOs.write(jsonBytes)
            }
            deflater.end() // Release native resources

            // Calculate CRC32 and ISIZE for GZIP footer
            val crc32 = CRC32()
            crc32.update(jsonBytes)
            val crcValue = crc32.value

            outputStream.write(byteArrayOf(
                (crcValue and 0xFF).toByte(),
                ((crcValue shr 8) and 0xFF).toByte(),
                ((crcValue shr 16) and 0xFF).toByte(),
                ((crcValue shr 24) and 0xFF).toByte()
            ))

            val isize = jsonBytes.size.toLong()
            outputStream.write(byteArrayOf(
                (isize and 0xFF).toByte(),
                ((isize shr 8) and 0xFF).toByte(),
                ((isize shr 16) and 0xFF).toByte(),
                ((isize shr 24) and 0xFF).toByte()
            ))

            val compressedData = outputStream.toByteArray()

            // Log the size of the compressed packet
            Log.d("HoarderService", "Sent compressed packet size: ${compressedData.size} bytes")

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
                lastSuccessfullyUploadedJson = originalFullJson // IMPORTANT: Store the original full JSON
                sendUploadStatus(if (isDelta) "OK (Delta)" else "OK (Full)", "Uploaded successfully.", totalUploadedBytes)
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

        if (status == "Network Error" &&
            serverIpAddress.isNotBlank() &&
            message.contains(serverIpAddress)) {

            if (lastSentUploadStatus == "Network Error" &&
                lastSentMessageContent?.first == "Network Error" &&
                lastSentMessageContent?.second?.contains(serverIpAddress) == true &&
                currentTimeMs - lastNetworkErrorSentTimestampMs < NETWORK_ERROR_MESSAGE_COOLDOWN_MS) {
                shouldSendFullUpdate = false
            }
        }

        val contentChanged = (lastSentUploadStatus != status || lastSentMessageContent != currentMessagePair)

        if (shouldSendFullUpdate && contentChanged) {
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("status", status)
                putExtra("message", message)
                putExtra("totalUploadedBytes", uploadedBytes)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

            lastSentUploadStatus = status
            lastSentMessageContent = currentMessagePair
            if (status == "Network Error" && serverIpAddress.isNotBlank() && message.contains(serverIpAddress)) {
                lastNetworkErrorSentTimestampMs = currentTimeMs
            }
        } else if (!shouldSendFullUpdate && status == "Network Error") {
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("totalUploadedBytes", uploadedBytes)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        } else if (lastSentUploadStatus == status && lastSentMessageContent == currentMessagePair) {
            val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("totalUploadedBytes", uploadedBytes)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        }
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
