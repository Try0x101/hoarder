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
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_FULL,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Discharging"
                    else -> "Discharging"
                }

                var estimatedFullCapacityMah: Int? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val capacityPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (chargeCounter > 0 && capacityPercent > 0) {
                        val chargeCounterMah = chargeCounter / 1000
                        estimatedFullCapacityMah = (chargeCounterMah * 100) / capacityPercent
                    }
                }

                latestBatteryData = buildMap {
                    put("percent", batteryPct.toInt())
                    put("status", statusString)
                    if (estimatedFullCapacityMah != null) {
                        put("estimated_full_capacity_mAh", estimatedFullCapacityMah)
                    }
                }
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
                        val currentFullJson = latestJsonData
                        currentFullJson?.let { fullJson ->
                            val (dataToSend, isDelta) = generateJsonToSend(fullJson)
                            if (dataToSend != null) {
                                uploadDataToServer(dataToSend, fullJson, isDelta)
                            } else {
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
        val shortId = deviceId.take(3)
        dataMap["id"] = shortId

        // dev block
        val currentDateTime = Date()
        val cal = java.util.Calendar.getInstance()
        cal.time = currentDateTime
        dataMap["n"] = Build.MODEL
        // dt как строка DD.MM.YYYY
        val dtString = String.format(
            "%02d.%02d.%04d",
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.YEAR)
        )
        dataMap["dt"] = dtString // <-- всегда строка!
        val timeZone = TimeZone.getDefault()
        val currentOffsetMillis = timeZone.getOffset(currentDateTime.time)
        val totalMinutes = currentOffsetMillis / (1000 * 60)
        val hoursOffset = totalMinutes / 60
        val minutesOffset = kotlin.math.abs(totalMinutes % 60)
        val gmtOffsetString = if (minutesOffset == 0) {
            if (hoursOffset >= 0) "GMT+$hoursOffset" else "GMT$hoursOffset"
        } else {
            val sign = if (hoursOffset >= 0) "+" else "-"
            "GMT$sign${kotlin.math.abs(hoursOffset)}:${String.format("%02d", minutesOffset)}"
        }
        dataMap["tz"] = gmtOffsetString

        // bat block
        latestBatteryData?.let {
            it["percent"]?.let { v -> dataMap["perc"] = v }
            it["status"]?.let { v -> dataMap["stat"] = v }
            it["estimated_full_capacity_mAh"]?.let { v -> dataMap["cap"] = v }
        } ?: run {
            dataMap["stat"] = "Battery data unavailable"
        }

        // gps block
        if (lastKnownLocation != null) {
            val it = lastKnownLocation!!
            val roundedAltitude = (it.altitude / 2).roundToInt() * 2
            val roundedAccuracy = (it.accuracy / 10).roundToInt() * 10
            val roundedBearing = it.bearing.roundToInt()
            val speedKmH = (it.speed * 3.6).roundToInt()
            val roundedLatitude = String.format(Locale.US, "%.4f", it.latitude).toDouble()
            val roundedLongitude = String.format(Locale.US, "%.4f", it.longitude).toDouble()
            dataMap["lat"] = roundedLatitude
            dataMap["lon"] = roundedLongitude
            dataMap["alt"] = roundedAltitude
            dataMap["acc"] = roundedAccuracy
            dataMap["bear"] = roundedBearing
            dataMap["spd"] = speedKmH
        }

        // net block (cell info)
        try {
            dataMap["op"] = telephonyManager.networkOperatorName
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
            dataMap["nt"] = activeNetworkType

            // --- Вытаскиваем только первую зарегистрированную ячейку ---
            val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo
            var found = false
            cellInfoList?.forEach { cellInfo ->
                if (cellInfo.isRegistered && !found) {
                    found = true
                    when (cellInfo) {
                        is CellInfoLte -> {
                            dataMap["ci"] = cellInfo.cellIdentity.ci
                            dataMap["tac"] = cellInfo.cellIdentity.tac
                            dataMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                            dataMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                            val ssLte = cellInfo.cellSignalStrength
                            if (ssLte.dbm != Int.MAX_VALUE) {
                                dataMap["rssi"] = ssLte.dbm
                            }
                        }
                        is CellInfoWcdma -> {
                            dataMap["ci"] = cellInfo.cellIdentity.cid
                            dataMap["tac"] = cellInfo.cellIdentity.lac
                            dataMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                            dataMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                            val ssWcdma = cellInfo.cellSignalStrength
                            if (ssWcdma.dbm != Int.MAX_VALUE) {
                                dataMap["rssi"] = ssWcdma.dbm
                            }
                        }
                        is CellInfoGsm -> {
                            dataMap["ci"] = cellInfo.cellIdentity.cid
                            dataMap["tac"] = cellInfo.cellIdentity.lac
                            dataMap["mcc"] = cellInfo.cellIdentity.mccString ?: "N/A"
                            dataMap["mnc"] = cellInfo.cellIdentity.mncString ?: "N/A"
                            val ssGsm = cellInfo.cellSignalStrength
                            if (ssGsm.dbm != Int.MAX_VALUE) {
                                dataMap["rssi"] = ssGsm.dbm
                            }
                        }
                        is CellInfoNr -> {
                            val cellIdentityNr = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                            dataMap["ci"] = cellIdentityNr?.nci ?: "N/A"
                            dataMap["tac"] = cellIdentityNr?.tac ?: -1
                            dataMap["mcc"] = cellIdentityNr?.mccString ?: "N/A"
                            dataMap["mnc"] = cellIdentityNr?.mncString ?: "N/A"
                            val ssNr = cellInfo.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                            if (ssNr != null && ssNr.ssRsrp != Int.MIN_VALUE) {
                                dataMap["rssi"] = ssNr.ssRsrp
                            }
                        }
                        else -> { /* ignore */ }
                    }
                }
            }
            if (!found) {
                dataMap["ci"] = "N/A"
                dataMap["tac"] = "N/A"
                dataMap["mcc"] = "N/A"
                dataMap["mnc"] = "N/A"
                dataMap["rssi"] = "N/A"
            }
        } catch (e: SecurityException) {
            dataMap["stat"] = "No permission"
        }

        // wifi block
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo.ssid
        dataMap["ssid"] = if (ssid == null || ssid == "<unknown ssid>" || ssid == "0x" || ssid.isBlank()) 0 else ssid

        // ds/us (network speed) block
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
        dataMap["ds"] = String.format(Locale.US, "%.1f", roundedDownloadSpeedMbps).toDouble()
        dataMap["us"] = String.format(Locale.US, "%.1f", roundedUploadSpeedMbps).toDouble()

        // ncs block (только dn, up)
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (networkCapabilities != null) {
            val linkDownstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
            val linkUpstreamKbps = networkCapabilities.linkUpstreamBandwidthKbps
            val linkDownstreamMbps = kotlin.math.ceil(linkDownstreamKbps.toDouble() / 1024.0).toInt()
            val linkUpstreamMbps = kotlin.math.ceil(linkUpstreamKbps.toDouble() / 1024.0).toInt()
            dataMap["dn"] = linkDownstreamMbps
            dataMap["up"] = linkUpstreamMbps
        }

        val gsonPretty = GsonBuilder().setPrettyPrinting().create()
        val jsonString = gsonPretty.toJson(dataMap)
        latestJsonData = jsonString
        val dataIntent = Intent("com.example.hoarder.DATA_UPDATE")
        dataIntent.putExtra("jsonString", jsonString)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(dataIntent)
    }

    private fun generateJsonToSend(currentFullJson: String): Pair<String?, Boolean> {
        if (lastSuccessfullyUploadedJson == null) {
            return Pair(currentFullJson, false)
        }
        try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            val prev = gson.fromJson<Map<String, Any?>>(lastSuccessfullyUploadedJson, type)
            val curr = gson.fromJson<Map<String, Any?>>(currentFullJson, type)
            val delta = mutableMapOf<String, Any?>()

            // Жёстко контролируем формат dt: всегда строка DD.MM.YYYY
            fun normalizeDt(dt: Any?): String {
                return when (dt) {
                    is String -> {
                        val regex = Regex("""\d{2}\.\d{2}\.\d{4}""")
                        if (regex.matches(dt)) dt
                        else try {
                            val longVal = dt.toLongOrNull()
                            if (longVal != null) {
                                val cal = java.util.Calendar.getInstance()
                                cal.timeInMillis = longVal
                                String.format(
                                    "%02d.%02d.%04d",
                                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                                    cal.get(java.util.Calendar.MONTH) + 1,
                                    cal.get(java.util.Calendar.YEAR)
                                )
                            } else dt
                        } catch (_: Exception) { dt }
                    }
                    is Number -> {
                        val cal = java.util.Calendar.getInstance()
                        cal.timeInMillis = dt.toLong()
                        String.format(
                            "%02d.%02d.%04d",
                            cal.get(java.util.Calendar.DAY_OF_MONTH),
                            cal.get(java.util.Calendar.MONTH) + 1,
                            cal.get(java.util.Calendar.YEAR)
                        )
                    }
                    else -> ""
                }
            }

            for ((k, v) in curr) {
                if (k == "dt") {
                    val currDt = normalizeDt(v)
                    val prevDt = normalizeDt(prev[k])
                    if (currDt != prevDt) {
                        delta[k] = currDt
                    }
                } else if (k == "ds" || k == "us") {
                    val currVal = (v as? Number)?.toDouble() ?: 0.0
                    val prevVal = (prev[k] as? Number)?.toDouble() ?: 0.0
                    val currNorm = if (currVal < 0.3) 0.0 else currVal
                    val prevNorm = if (prevVal < 0.3) 0.0 else prevVal
                    if (currNorm != prevNorm) {
                        delta[k] = currNorm
                    }
                } else {
                    if (!prev.containsKey(k) || prev[k] != v) {
                        delta[k] = v
                    }
                }
            }

            // Всегда добавляем id из текущего JSON, даже если не изменился
            if (curr.containsKey("id")) {
                delta["id"] = curr["id"]
            }

            // Если изменений нет (только id) — ничего не отправлять
            if (delta.keys == setOf("id")) return Pair(null, true)
            if (delta.isEmpty()) return Pair(null, true)
            val deltaJson = gson.toJson(delta)
            return Pair(deltaJson, true)
        } catch (e: Exception) {
            return Pair(currentFullJson, false)
        }
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
            urlConnection.setRequestProperty("X-Data-Type", if (isDelta) "delta" else "full")
            urlConnection.doOutput = true
            urlConnection.connectTimeout = 10000
            urlConnection.readTimeout = 10000

            val jsonBytes = jsonStringToSend.toByteArray(StandardCharsets.UTF_8)

            // Сжимаем данные с помощью Deflater (уровень 7), без GZIP-заголовков
            val deflater = Deflater(7, true)
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed, deflater).use { it.write(jsonBytes) }
            val compressedBytes = compressed.toByteArray()

            Log.d("HoarderService", "${if (isDelta) "Sending delta" else "Sending full"} JSON data: $jsonStringToSend")

            urlConnection.outputStream.write(compressedBytes)
            urlConnection.outputStream.flush()

            val responseCode = urlConnection.responseCode
            val responseMessage = urlConnection.responseMessage

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = urlConnection.inputStream.bufferedReader().use { it.readText() }
                totalUploadedBytes += compressedBytes.size.toLong()
                lastSuccessfullyUploadedJson = originalFullJson
                sendUploadStatus(if (isDelta) "OK (Delta)" else "OK (Full)", "Uploaded successfully.", totalUploadedBytes)
                Log.d("HoarderService", "Sent compressed packet size: ${compressedBytes.size} bytes")
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
