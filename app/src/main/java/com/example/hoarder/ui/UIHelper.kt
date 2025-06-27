package com.example.hoarder.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.Prefs
import com.example.hoarder.utils.NetUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale
import kotlin.math.ceil

class UIHelper(private val a: MainActivity, private val p: Prefs) {
    private lateinit var dataCollectionHeader: RelativeLayout
    private lateinit var dataCollectionSwitch: Switch
    private lateinit var dataCollectionSubtitle: TextView
    private lateinit var dataCollectionArrow: ImageView
    private lateinit var dataCollectionContent: HorizontalScrollView
    private lateinit var rawJsonTextView: TextView

    private lateinit var serverUploadRow: RelativeLayout
    private lateinit var serverUploadSwitch: Switch
    private lateinit var serverUploadStatus: TextView
    private lateinit var serverUploadBytes: TextView

    private lateinit var precisionSettingsHeader: RelativeLayout
    private lateinit var precisionSettingsArrow: ImageView
    private lateinit var precisionSettingsContent: LinearLayout

    private lateinit var gpsPrecisionValue: TextView
    private lateinit var gpsAltitudePrecisionValue: TextView
    private lateinit var rssiPrecisionValue: TextView
    private lateinit var batteryPrecisionValue: TextView
    private lateinit var networkPrecisionValue: TextView
    private lateinit var speedPrecisionValue: TextView

    private lateinit var gpsPrecisionInfo: TextView
    private lateinit var gpsAltitudePrecisionInfo: TextView
    private lateinit var rssiPrecisionInfo: TextView
    private lateinit var batteryPrecisionInfo: TextView
    private lateinit var networkPrecisionInfo: TextView
    private lateinit var speedPrecisionInfo: TextView

    private val g by lazy { GsonBuilder().setPrettyPrinting().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create() }
    private var lastErrorMessage: String? = null
    private val BUFFER_LIMIT_BYTES = 5 * 1024
    private val RECORDS_PER_PAGE = 20

    fun setupUI() {
        findViews()
        setupState()
        setupListeners()
    }

    private fun findViews() {
        dataCollectionHeader = a.findViewById(R.id.dataCollectionHeader)
        dataCollectionSwitch = a.findViewById(R.id.dataCollectionSwitch)
        dataCollectionSubtitle = a.findViewById(R.id.dataCollectionSubtitle)
        dataCollectionArrow = a.findViewById(R.id.dataCollectionArrow)
        dataCollectionContent = a.findViewById(R.id.dataCollectionContent)
        rawJsonTextView = a.findViewById(R.id.rawJsonTextView)

        serverUploadRow = a.findViewById(R.id.serverUploadRow)
        serverUploadSwitch = a.findViewById(R.id.serverUploadSwitch)
        serverUploadStatus = a.findViewById(R.id.serverUploadStatus)
        serverUploadBytes = a.findViewById(R.id.serverUploadBytes)

        precisionSettingsHeader = a.findViewById(R.id.precisionSettingsHeader)
        precisionSettingsArrow = a.findViewById(R.id.precisionSettingsArrow)
        precisionSettingsContent = a.findViewById(R.id.precisionSettingsContent)

        gpsPrecisionValue = a.findViewById(R.id.gpsPrecisionValue)
        gpsAltitudePrecisionValue = a.findViewById(R.id.gpsAltitudePrecisionValue)
        rssiPrecisionValue = a.findViewById(R.id.rssiPrecisionValue)
        batteryPrecisionValue = a.findViewById(R.id.batteryPrecisionValue)
        networkPrecisionValue = a.findViewById(R.id.networkPrecisionValue)
        speedPrecisionValue = a.findViewById(R.id.speedPrecisionValue)

        gpsPrecisionInfo = a.findViewById(R.id.gpsPrecisionInfo)
        gpsAltitudePrecisionInfo = a.findViewById(R.id.gpsAltitudePrecisionInfo)
        rssiPrecisionInfo = a.findViewById(R.id.rssiPrecisionInfo)
        batteryPrecisionInfo = a.findViewById(R.id.batteryPrecisionInfo)
        networkPrecisionInfo = a.findViewById(R.id.networkPrecisionInfo)
        speedPrecisionInfo = a.findViewById(R.id.speedPrecisionInfo)
    }

    private fun setupState() {
        updateDataCollectionUI(p.isDataCollectionEnabled())
        dataCollectionSwitch.isChecked = p.isDataCollectionEnabled()

        updateUploadUI(p.isDataUploadEnabled(), null, null, null, null, 0L)
        serverUploadSwitch.isChecked = p.isDataUploadEnabled()

        updateAllPrecisionLabels()
    }

    private fun setupListeners() {
        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataCollectionEnabled(isChecked)
            updateDataCollectionUI(isChecked)
            if (isChecked) a.startCollection() else a.stopCollection()
        }
        dataCollectionHeader.setOnClickListener {
            toggleVisibility(dataCollectionContent, dataCollectionArrow)
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataUploadEnabled(isChecked)
            if (isChecked) {
                val addr = p.getServerAddress()
                if (NetUtils.isValidIpPort(addr)) {
                    a.startUpload(addr)
                } else {
                    serverUploadSwitch.isChecked = false
                    p.setDataUploadEnabled(false)
                    showServerSettingsDialog()
                }
            } else {
                a.stopUpload()
            }
            updateUploadUI(isChecked, null, null, null, null, 0L)
        }
        serverUploadRow.setOnClickListener { showServerSettingsDialog() }

        precisionSettingsHeader.setOnClickListener { toggleVisibility(precisionSettingsContent, precisionSettingsArrow) }

        setupPrecisionDialogListeners()

        rawJsonTextView.setOnLongClickListener {
            val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hoarder JSON", rawJsonTextView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(a, "JSON data copied to clipboard", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupPrecisionDialogListeners() {
        a.findViewById<LinearLayout>(R.id.gpsPrecisionSetting).setOnClickListener { showGpsPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.gpsAltitudePrecisionSetting).setOnClickListener { showGpsAltitudePrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.rssiPrecisionSetting).setOnClickListener { showRssiPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.batteryPrecisionSetting).setOnClickListener { showBatteryPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.networkPrecisionSetting).setOnClickListener { showNetworkPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.speedPrecisionSetting).setOnClickListener { showSpeedPrecisionChooser() }
    }

    fun updateDataCollectionUI(isActive: Boolean) {
        dataCollectionSubtitle.text = if (isActive) "Active" else "Inactive"
    }

    fun updateUploadUI(isActive: Boolean, status: String?, message: String?, totalBytes: Long?, lastUploadBytes: Long?, bufferedSize: Long) {
        if (!isActive) {
            serverUploadStatus.text = "Inactive"
            serverUploadBytes.visibility = View.GONE
            lastErrorMessage = null
            return
        }

        val serverAddress = p.getServerAddress()
        if (!NetUtils.isValidIpPort(serverAddress)) {
            serverUploadStatus.text = "Invalid Address"
            serverUploadBytes.visibility = View.GONE
            lastErrorMessage = "Invalid Address"
            return
        }

        val statusText = when {
            status == "Saving Locally" -> {
                lastErrorMessage = message
                var localStatus = "Saving locally: ${formatBytes(bufferedSize)}"
                if (bufferedSize > BUFFER_LIMIT_BYTES) {
                    localStatus += "\nBuffer large, confirm send in settings."
                }
                localStatus
            }
            status?.startsWith("OK") == true || status == "No Change" || status == "Connecting" || status == "OK (Batch)" -> {
                lastErrorMessage = null
                var connectedStatus = "Connected"
                if (bufferedSize > 0) {
                    connectedStatus += " | Local: ${formatBytes(bufferedSize)}"
                }
                connectedStatus
            }
            status == "Network Error" && message == "Internet not accessible" -> {
                lastErrorMessage = message
                message
            }
            status == "HTTP Error" || status == "Network Error" -> {
                lastErrorMessage = "Error (Check logs)"
                lastErrorMessage
            }
            else -> lastErrorMessage ?: "Connected"
        }
        serverUploadStatus.text = statusText
        serverUploadBytes.text = "Uploaded: ${formatBytes(totalBytes ?: 0)}"
        serverUploadBytes.visibility = View.VISIBLE
    }

    fun updateRawJson(json: String?) {
        if (json.isNullOrEmpty()) {
            rawJsonTextView.text = "Collection is inactive or waiting for data..."
            return
        }
        try {
            rawJsonTextView.text = g.toJson(JsonParser.parseString(json))
        } catch (e: Exception) {
            rawJsonTextView.text = "Error parsing JSON: ${e.message}"
        }
    }

    private fun showServerSettingsDialog() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_server_settings, null)
        builder.setView(view)

        val editText = view.findViewById<TextView>(R.id.serverIpPortEditText)
        editText.text = p.getServerAddress()

        val (lastHour, lastDay, last7Days) = calculateUploadStats()
        view.findViewById<TextView>(R.id.statsLastHour).text = formatBytes(lastHour)
        view.findViewById<TextView>(R.id.statsLastDay).text = formatBytes(lastDay)
        view.findViewById<TextView>(R.id.statsLast7Days).text = formatBytes(last7Days)

        val sendBufferButton = view.findViewById<Button>(R.id.sendBufferedDataButton)
        val viewCachedUploadLogButton = view.findViewById<Button>(R.id.viewCachedUploadLogButton)
        val viewSuccessLogButton = view.findViewById<Button>(R.id.viewSuccessLogButton)
        val viewErrorLogButton = view.findViewById<Button>(R.id.viewErrorLogButton)
        val servicePrefs = a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)

        fun updateButtonsState() {
            val bufferSize = servicePrefs.getStringSet("data_buffer", emptySet())?.sumOf { it.toByteArray().size }?.toLong() ?: 0L
            if (bufferSize > 0) {
                sendBufferButton.visibility = View.VISIBLE
                sendBufferButton.text = "Send Buffered Data (${formatBytes(bufferSize)})"
                sendBufferButton.isEnabled = true
            } else {
                sendBufferButton.visibility = View.GONE
            }
            val lastUploadFile = File(a.cacheDir, "last_upload_details.json")
            viewCachedUploadLogButton.visibility = if (lastUploadFile.exists()) View.VISIBLE else View.GONE
        }

        updateButtonsState()

        sendBufferButton.setOnClickListener {
            a.sendBuffer()
            it.isEnabled = false
            (it as Button).text = "Sending..."
        }

        viewCachedUploadLogButton.setOnClickListener { showDetailedLogDialog("cached") }
        viewSuccessLogButton.setOnClickListener { showDetailedLogDialog("success") }
        viewErrorLogButton.setOnClickListener { showDetailedLogDialog("error") }

        val dialog = builder.setTitle("Server Settings")
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        val uploadStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateButtonsState()
                val status = intent?.getStringExtra("status")
                when (status) {
                    "OK (Batch)" -> Toast.makeText(a, "Buffered data sent successfully!", Toast.LENGTH_SHORT).show()
                    "HTTP Error", "Network Error" -> {
                        val message = intent.getStringExtra("message") ?: "Check logs for details."
                        Toast.makeText(a, "Failed to send buffer: $message", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveServerAddress(editText.text.toString(), dialog)
            }
            view.findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
                clearAllLogs()
                updateButtonsState()
                Toast.makeText(a, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            LocalBroadcastManager.getInstance(a).registerReceiver(uploadStatusReceiver, IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
        }

        dialog.setOnDismissListener {
            LocalBroadcastManager.getInstance(a).unregisterReceiver(uploadStatusReceiver)
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveServerAddress(editText.text.toString(), dialog)
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun showDetailedLogDialog(logType: String) {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_log_viewer, null)
        val container = view.findViewById<LinearLayout>(R.id.logViewerContainer)
        val controlsContainer = view.findViewById<LinearLayout>(R.id.controlsContainer)
        val prevButton = view.findViewById<Button>(R.id.prevButton)
        val nextButton = view.findViewById<Button>(R.id.nextButton)
        val pageIndicator = view.findViewById<TextView>(R.id.pageIndicator)
        val copyPageButton = view.findViewById<Button>(R.id.copyPageButton)
        builder.setView(view)

        val logEntries = getLogEntries(logType)
        var currentPage = 0
        val totalPages = if (logEntries.isEmpty()) 1 else ceil(logEntries.size.toDouble() / RECORDS_PER_PAGE).toInt()

        fun renderPage(page: Int) {
            currentPage = page.coerceIn(0, totalPages - 1)
            container.removeAllViews()

            val startIndex = currentPage * RECORDS_PER_PAGE
            val endIndex = (startIndex + RECORDS_PER_PAGE).coerceAtMost(logEntries.size)
            val pageEntries = if (logEntries.isNotEmpty()) logEntries.subList(startIndex, endIndex) else emptyList()

            if (pageEntries.isNotEmpty()) {
                pageEntries.forEachIndexed { index, entry ->
                    val recordNum = startIndex + index + 1
                    val (headerText, contentText, copyText) = formatLogEntry(logType, entry, recordNum)

                    val header = TextView(a).apply {
                        text = headerText
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(a, R.color.amoled_white))
                        setPadding(0, if (index > 0) 24 else 0, 0, 8)
                    }
                    container.addView(header)

                    val content = TextView(a).apply {
                        text = contentText
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(ContextCompat.getColor(a, R.color.amoled_light_gray))
                        setOnLongClickListener {
                            val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Hoarder Log Record", copyText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(a, "$headerText copied to clipboard", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                    container.addView(content)
                }
            } else {
                container.addView(createLogTextView("No logs found."))
            }

            pageIndicator.text = "Page ${currentPage + 1} of $totalPages"
            prevButton.isEnabled = currentPage > 0
            nextButton.isEnabled = currentPage < totalPages - 1
            copyPageButton.isEnabled = pageEntries.isNotEmpty()
        }

        controlsContainer.visibility = View.VISIBLE
        renderPage(0)

        prevButton.setOnClickListener { renderPage(currentPage - 1) }
        nextButton.setOnClickListener { renderPage(currentPage + 1) }
        copyPageButton.setOnClickListener {
            val startIndex = currentPage * RECORDS_PER_PAGE
            val endIndex = (startIndex + RECORDS_PER_PAGE).coerceAtMost(logEntries.size)
            val pageEntries = if (logEntries.isNotEmpty()) logEntries.subList(startIndex, endIndex) else emptyList()

            val allPageJson = pageEntries.joinToString(separator = ",\n\n") { entry ->
                val (_, _, copyText) = formatLogEntry(logType, entry, 0)
                copyText
            }
            val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hoarder Page Log", allPageJson)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(a, "Copied ${pageEntries.size} records from page", Toast.LENGTH_SHORT).show()
        }

        val title = when(logType) {
            "cached" -> "Last Cached Upload Details"
            "success" -> "Upload Log"
            "error" -> "Error Log"
            else -> "Log"
        }

        builder.setTitle(title)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun getLogEntries(logType: String): List<String> {
        return when (logType) {
            "cached" -> {
                try {
                    val file = File(a.cacheDir, "last_upload_details.json")
                    if (file.exists()) {
                        val jsonText = file.readText()
                        val type = object : TypeToken<List<String>>() {}.type
                        g.fromJson(jsonText, type)
                    } else emptyList()
                } catch (e: Exception) { emptyList() }
            }
            "success" -> a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
                .getStringSet("success_logs", emptySet())?.toMutableList()?.sortedDescending() ?: emptyList()
            "error" -> a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
                .getStringSet("error_logs", emptySet())?.toMutableList()?.sortedDescending() ?: emptyList()
            else -> emptyList()
        }
    }

    private fun formatLogEntry(logType: String, entry: String, recordNum: Int): Triple<String, String, String> {
        return when (logType) {
            "cached" -> {
                val header = "Cached Record $recordNum"
                val content = g.toJson(JsonParser.parseString(entry))
                Triple(header, content, content)
            }
            "success" -> {
                val parts = entry.split("|", limit = 3)
                val header = parts.getOrElse(0) { "Success Record" }
                val content: String
                val copyText: String
                if (parts.size == 3) {
                    val size = parts[1].toLongOrNull() ?: 0
                    val json = parts[2]
                    if (json.startsWith("Batch upload")) {
                        content = "${json} - Size: ${formatBytes(size)}"
                        copyText = content
                    } else {
                        content = "Size: ${formatBytes(size)}\nJSON: ${g.toJson(JsonParser.parseString(json))}"
                        copyText = g.toJson(JsonParser.parseString(json))
                    }
                } else {
                    content = entry
                    copyText = entry
                }
                Triple(header, content, copyText)
            }
            "error" -> {
                val parts = entry.split("|", limit = 2)
                val header = parts.getOrElse(0) { "Error Record" }
                val content = parts.getOrElse(1) { entry }
                Triple(header, content, content)
            }
            else -> Triple("Record $recordNum", entry, entry)
        }
    }

    private fun saveServerAddress(addr: String, dialog: AlertDialog) {
        if (NetUtils.isValidIpPort(addr)) {
            p.setServerAddress(addr)
            Toast.makeText(a, "Server address saved", Toast.LENGTH_SHORT).show()
            if (p.isDataUploadEnabled()) {
                a.stopUpload()
                a.startUpload(addr)
            }
            updateUploadUI(p.isDataUploadEnabled(), null, null, null, null, 0L)
            dialog.dismiss()
        } else {
            Toast.makeText(a, "Invalid server IP:Port format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllLogs() {
        val prefs = a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("error_logs")
            .remove("success_logs")
            .remove("uploadRecords")
            .remove("data_buffer")
            .apply()
        try {
            File(a.cacheDir, "last_upload_details.json").delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createLogTextView(text: String): TextView {
        return TextView(a).apply {
            this.text = text
            textSize = 12f
            setTextColor(a.getColor(R.color.amoled_light_gray))
            setPadding(0, 4, 0, 4)
        }
    }

    private fun calculateUploadStats(): Triple<Long, Long, Long> {
        val records = a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
            .getStringSet("uploadRecords", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 60 * 60 * 1000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L

        var lastHourBytes = 0L
        var lastDayBytes = 0L
        var last7DaysBytes = 0L

        records.forEach { record ->
            val parts = record.split(":")
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull()
                val bytes = parts[1].toLongOrNull()
                if (timestamp != null && bytes != null) {
                    last7DaysBytes += bytes
                    if (timestamp >= oneDayAgo) {
                        lastDayBytes += bytes
                    }
                    if (timestamp >= oneHourAgo) {
                        lastHourBytes += bytes
                    }
                }
            }
        }
        return Triple(lastHourBytes, lastDayBytes, last7DaysBytes)
    }

    private fun toggleVisibility(content: View, arrow: ImageView) {
        val isVisible = content.visibility == View.VISIBLE
        content.visibility = if (isVisible) View.GONE else View.VISIBLE
        arrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(300).start()
        if (content.id == R.id.dataCollectionContent && !isVisible) {
            updateRawJson(a.getLastData())
        }
    }

    private fun updateAllPrecisionLabels() {
        gpsPrecisionValue.text = getGpsPrecisionString(p.getGPSPrecision())
        gpsAltitudePrecisionValue.text = getGpsAltitudePrecisionString(p.getGPSAltitudePrecision())
        rssiPrecisionValue.text = getRssiPrecisionString(p.getRSSIPrecision())
        batteryPrecisionValue.text = getBatteryPrecisionString(p.getBatteryPrecision())
        networkPrecisionValue.text = getNetworkPrecisionString(p.getNetworkPrecision())
        speedPrecisionValue.text = getSpeedPrecisionString(p.getSpeedPrecision())
        updatePrecisionInfoVisibility()
    }

    private fun updatePrecisionInfoVisibility() {
        gpsPrecisionInfo.visibility = if (p.getGPSPrecision() == -1) View.VISIBLE else View.GONE
        gpsPrecisionInfo.text = "• If speed <4 km/h → round up to 1 km\n• If speed 4-40 km/h → round up to 20 m\n• If speed 40-140 km/h → round up to 100 m\n• If speed >140 km/h → round up to 1 km"

        gpsAltitudePrecisionInfo.visibility = if (p.getGPSAltitudePrecision() == -1) View.VISIBLE else View.GONE
        gpsAltitudePrecisionInfo.text = "• Below 100m: 10m precision\n• 100-1000m: 50m precision\n• Above 1000m: 100m precision"

        rssiPrecisionInfo.visibility = if (p.getRSSIPrecision() == -1) View.VISIBLE else View.GONE
        rssiPrecisionInfo.text = "• If signal worse than -110 dBm → show precise value\n• If signal worse than -90 dBm → round to nearest 5\n• If signal better than -90 dBm → round to nearest 10"

        batteryPrecisionInfo.visibility = if (p.getBatteryPrecision() == -1) View.VISIBLE else View.GONE
        batteryPrecisionInfo.text = "• If battery below 10% → show precise value\n• If battery 10-50% → round to nearest 5%\n• If battery above 50% → round to nearest 10%"

        networkPrecisionInfo.visibility = if (p.getNetworkPrecision() == 0) View.VISIBLE else View.GONE
        networkPrecisionInfo.text = "• Below 2 Mbps → show decimal precision\n• 2-7 Mbps → round to nearest lower 1 Mbps\n• Above 7 Mbps → round to nearest lower 5 Mbps"

        speedPrecisionInfo.visibility = if (p.getSpeedPrecision() == -1) View.VISIBLE else View.GONE
        speedPrecisionInfo.text = "• If speed <2 km/h → show 0\n• If speed <10 km/h → round to nearest 3 km/h\n• If speed ≥10 km/h → round to nearest 10 km/h"
    }

    private fun showGpsPrecisionChooser() = showChooser("GPS Precision", arrayOf("Smart", "Maximum", "20m", "100m", "1km", "10km"), intArrayOf(-1, 0, 20, 100, 1000, 10000), p.getGPSPrecision()) { p.setGPSPrecision(it); updateAllPrecisionLabels() }
    private fun showGpsAltitudePrecisionChooser() = showChooser("GPS Altitude", arrayOf("Smart", "Maximum", "2m", "10m", "25m", "50m", "100m"), intArrayOf(-1, 0, 2, 10, 25, 50, 100), p.getGPSAltitudePrecision()) { p.setGPSAltitudePrecision(it); updateAllPrecisionLabels() }
    private fun showRssiPrecisionChooser() = showChooser("RSSI", arrayOf("Smart", "Maximum", "3dBm", "5dBm", "10dBm"), intArrayOf(-1, 0, 3, 5, 10), p.getRSSIPrecision()) { p.setRSSIPrecision(it); updateAllPrecisionLabels() }
    private fun showBatteryPrecisionChooser() = showChooser("Battery", arrayOf("Smart", "Maximum", "2%", "5%", "10%"), intArrayOf(-1, 0, 2, 5, 10), p.getBatteryPrecision()) { p.setBatteryPrecision(it); updateAllPrecisionLabels() }
    private fun showNetworkPrecisionChooser() = showChooser("Network Speed", arrayOf("Smart", "Float", "1Mbps", "2Mbps", "5Mbps"), intArrayOf(0, -2, 1, 2, 5), p.getNetworkPrecision()) { p.setNetworkPrecision(it); updateAllPrecisionLabels() }
    private fun showSpeedPrecisionChooser() = showChooser("Speed", arrayOf("Smart", "Maximum", "1km/h", "3km/h", "5km/h", "10km/h"), intArrayOf(-1, 0, 1, 3, 5, 10), p.getSpeedPrecision()) { p.setSpeedPrecision(it); updateAllPrecisionLabels() }

    private fun showChooser(title: String, options: Array<String>, values: IntArray, current: Int, onSelected: (Int) -> Unit) {
        val checkedItem = values.indexOf(current).takeIf { it != -1 } ?: 0
        AlertDialog.Builder(a, R.style.AlertDialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val e = (Math.log(b.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", b / Math.pow(1024.0, e.toDouble()), p)
    }

    private fun getGpsPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 20 -> "20m"; 100 -> "100m"; 1000 -> "1km"; 10000 -> "10km"; else -> "Unknown" }
    private fun getGpsAltitudePrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 2 -> "2m"; 10 -> "10m"; 25 -> "25m"; 50 -> "50m"; 100 -> "100m"; else -> "Unknown" }
    private fun getRssiPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 3 -> "3dBm"; 5 -> "5dBm"; 10 -> "10dBm"; else -> "Unknown" }
    private fun getBatteryPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 2 -> "2%"; 5 -> "5%"; 10 -> "10%"; else -> "Unknown" }
    private fun getNetworkPrecisionString(v: Int) = when(v) { 0 -> "Smart"; -2 -> "Float"; 1 -> "1Mbps"; 2 -> "2Mbps"; 5 -> "5Mbps"; else -> "Unknown" }
    private fun getSpeedPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 1 -> "1km/h"; 3 -> "3km/h"; 5 -> "5km/h"; 10 -> "10km/h"; else -> "Unknown" }
}