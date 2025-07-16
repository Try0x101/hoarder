package com.example.hoarder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.dialogs.PrecisionChooserDialog

class LayoutManager(private val a: MainActivity, private val p: Prefs, private val onPrecisionChanged: () -> Unit) {
    private lateinit var dataCollectionHeader: RelativeLayout
    private lateinit var dataCollectionSwitch: Switch
    private lateinit var dataCollectionSubtitle: TextView
    private lateinit var dataCollectionArrow: ImageView
    private lateinit var dataCollectionContent: ViewPager2
    private lateinit var dataDescriptionHeader: RelativeLayout
    private lateinit var dataDescriptionArrow: ImageView
    private lateinit var dataDescriptionContent: LinearLayout
    private lateinit var serverUploadRow: RelativeLayout
    private lateinit var serverUploadSwitch: Switch
    private lateinit var serverUploadStatus: TextView
    private lateinit var serverUploadBytes: TextView
    private lateinit var precisionSettingsHeader: RelativeLayout
    private lateinit var precisionSettingsArrow: ImageView
    private lateinit var precisionSettingsContent: LinearLayout
    private lateinit var powerSavingHeader: RelativeLayout
    private lateinit var powerSavingArrow: ImageView
    private lateinit var powerSavingContent: LinearLayout
    private lateinit var powerSavingSubtitle: TextView
    private lateinit var powerModeRadioGroup: RadioGroup

    private val precisionChooser by lazy { PrecisionChooserDialog(a, p, onPrecisionChanged) }
    private lateinit var jsonPagerAdapter: JsonPagerAdapter

    fun setupUI() {
        findViews()
        setupPager()
        setupState()
        setupListeners()
    }

    private fun findViews() {
        dataCollectionHeader = a.findViewById(R.id.dataCollectionHeader)
        dataCollectionSwitch = a.findViewById(R.id.dataCollectionSwitch)
        dataCollectionSubtitle = a.findViewById(R.id.dataCollectionSubtitle)
        dataCollectionArrow = a.findViewById(R.id.dataCollectionArrow)
        dataCollectionContent = a.findViewById(R.id.dataCollectionContent)
        dataDescriptionHeader = a.findViewById(R.id.dataDescriptionHeader)
        dataDescriptionArrow = a.findViewById(R.id.dataDescriptionArrow)
        dataDescriptionContent = a.findViewById(R.id.dataDescriptionContent)
        serverUploadRow = a.findViewById(R.id.serverUploadRow)
        serverUploadSwitch = a.findViewById(R.id.serverUploadSwitch)
        serverUploadStatus = a.findViewById(R.id.serverUploadStatus)
        serverUploadBytes = a.findViewById(R.id.serverUploadBytes)
        precisionSettingsHeader = a.findViewById(R.id.precisionSettingsHeader)
        precisionSettingsArrow = a.findViewById(R.id.precisionSettingsArrow)
        precisionSettingsContent = a.findViewById(R.id.precisionSettingsContent)
        powerSavingHeader = a.findViewById(R.id.powerSavingHeader)
        powerSavingArrow = a.findViewById(R.id.powerSavingArrow)
        powerSavingContent = a.findViewById(R.id.powerSavingContent)
        powerSavingSubtitle = a.findViewById(R.id.powerSavingSubtitle)
        powerModeRadioGroup = a.findViewById(R.id.powerModeRadioGroup)
    }

    private fun setupPager() {
        jsonPagerAdapter = JsonPagerAdapter()
        dataCollectionContent.adapter = jsonPagerAdapter
    }

    private fun setupState() {
        dataCollectionSwitch.isChecked = p.isDataCollectionEnabled()
        serverUploadSwitch.isChecked = p.isDataUploadEnabled()
        updatePowerSavingUI()
    }

    private fun setupListeners() {
        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataCollectionEnabled(isChecked)
            if (isChecked) a.startCollection() else a.stopCollection()
        }

        dataCollectionHeader.setOnClickListener {
            toggleVisibility(dataCollectionContent, dataCollectionArrow)
        }

        dataDescriptionHeader.setOnClickListener {
            toggleVisibility(dataDescriptionContent, dataDescriptionArrow)
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataUploadEnabled(isChecked)
            if (isChecked) {
                val addr = p.getServerAddress()
                if (addr.isNotEmpty()) {
                    a.startUpload(addr)
                } else {
                    serverUploadSwitch.isChecked = false
                    p.setDataUploadEnabled(false)
                }
            } else {
                a.stopUpload()
            }
        }

        precisionSettingsHeader.setOnClickListener {
            toggleVisibility(precisionSettingsContent, precisionSettingsArrow)
        }

        powerSavingHeader.setOnClickListener {
            toggleVisibility(powerSavingContent, powerSavingArrow)
        }

        powerModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.radioOptimized) Prefs.POWER_MODE_OPTIMIZED else Prefs.POWER_MODE_CONTINUOUS
            p.setPowerMode(newMode)
            updatePowerSavingUI()
            a.onPowerModeChanged()
        }

        setupPrecisionListeners()
        setupTextCopyListeners()
    }

    private fun updatePowerSavingUI() {
        val currentMode = p.getPowerMode()
        powerSavingSubtitle.text = if (currentMode == Prefs.POWER_MODE_OPTIMIZED) "Optimized" else "Continuous"
        val checkedRadioId = if (currentMode == Prefs.POWER_MODE_OPTIMIZED) R.id.radioOptimized else R.id.radioContinuous
        if (powerModeRadioGroup.checkedRadioButtonId != checkedRadioId) {
            powerModeRadioGroup.check(checkedRadioId)
        }
    }

    private fun setupPrecisionListeners() {
        a.findViewById<LinearLayout>(R.id.gpsPrecisionSetting).setOnClickListener { precisionChooser.showGpsPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.gpsAltitudePrecisionSetting).setOnClickListener { precisionChooser.showGpsAltitudePrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.rssiPrecisionSetting).setOnClickListener { precisionChooser.showRssiPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.batteryPrecisionSetting).setOnClickListener { precisionChooser.showBatteryPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.networkPrecisionSetting).setOnClickListener { precisionChooser.showNetworkPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.speedPrecisionSetting).setOnClickListener { precisionChooser.showSpeedPrecisionChooser() }
    }

    private fun setupTextCopyListeners() {
        a.findViewById<TextView>(R.id.timestampInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Timestamp Info")
            true
        }
        a.findViewById<TextView>(R.id.precisionInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Precision Settings Info")
            true
        }
        a.findViewById<TextView>(R.id.fieldsInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Data Fields Reference")
            true
        }
    }

    fun toggleVisibility(content: View, arrow: ImageView) {
        val isVisible = content.visibility == View.VISIBLE
        content.visibility = if (isVisible) View.GONE else View.VISIBLE
        arrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(300).start()
        if (content.id == R.id.dataCollectionContent && !isVisible) {
            jsonPagerAdapter.updateJson(a.getLastData())
        }
    }

    private fun copyTextToClipboard(textView: TextView, label: String) {
        val clipboard = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, textView.text)
        clipboard.setPrimaryClip(clip)
    }

    fun updateJson(json: String?) {
        jsonPagerAdapter.updateJson(json)
    }

    inner class JsonPagerAdapter : RecyclerView.Adapter<JsonPagerAdapter.JsonViewHolder>() {
        private var optimizedJson = ""
        private var readableJson = ""

        fun updateJson(json: String?) {
            if (json.isNullOrEmpty()) {
                optimizedJson = "Collection is inactive or waiting for data..."
                readableJson = "Collection is inactive or waiting for data..."
            } else {
                optimizedJson = json
                readableJson = convertToReadableJson(json)
            }
            notifyDataSetChanged()
        }

        private fun convertToReadableJson(optimizedJson: String): String {
            return try {
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                val jsonObject = com.google.gson.JsonParser.parseString(optimizedJson).asJsonObject
                val readableMap = mutableMapOf<String, Any>()

                jsonObject.entrySet().forEach { (key, value) ->
                    val readableKey = when (key) {
                        "i" -> "id"
                        "n" -> "name"
                        "p" -> "battery_percent"
                        "c" -> "battery_capacity"
                        "a" -> "altitude"
                        "s" -> "speed"
                        "y" -> "latitude"
                        "x" -> "longitude"
                        "ac" -> "accuracy"
                        "d" -> "download_speed"
                        "u" -> "upload_speed"
                        "b" -> "wifi_bssid"
                        "o" -> "operator"
                        "t" -> "network_type"
                        "ci" -> "cell_id"
                        "tc" -> "tracking_area"
                        "mc" -> "country_code"
                        "mn" -> "network_code"
                        "r" -> "signal_strength"
                        else -> key
                    }

                    val readableValue = when {
                        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber
                        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                        else -> value.toString()
                    }

                    readableMap[readableKey] = readableValue
                }

                gson.toJson(readableMap)
            } catch (e: Exception) {
                "Error converting to readable format: ${e.message}"
            }
        }

        override fun getItemCount(): Int = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JsonViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_json_page, parent, false)
            return JsonViewHolder(view)
        }

        override fun onBindViewHolder(holder: JsonViewHolder, position: Int) {
            when (position) {
                0 -> holder.bind("Optimized Data (swipe →)", optimizedJson)
                1 -> holder.bind("Readable Format (swipe ←)", readableJson)
            }
        }

        inner class JsonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.jsonTitle)
            private val contentView: TextView = itemView.findViewById(R.id.jsonContent)

            fun bind(title: String, content: String) {
                titleView.text = title
                contentView.text = content
            }
        }
    }
}