package com.example.hoarder.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.dialogs.PrecisionChooserDialog
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import com.example.hoarder.common.math.RoundingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private var optimizedJson: String? = null
        private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        private val gsonParser = com.google.gson.Gson()

        fun updateJson(json: String?) {
            optimizedJson = json
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JsonViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_json_page, parent, false)
            return JsonViewHolder(view)
        }

        override fun onBindViewHolder(holder: JsonViewHolder, position: Int) {
            when (position) {
                0 -> holder.bindOptimized("Optimized Data (swipe →)", optimizedJson)
                1 -> holder.bindReadable("Readable Format (swipe ←)", optimizedJson)
            }
        }

        private val readableContentCache = WeakHashMap<LinearLayout, String?>()
        private val readableJobCache = WeakHashMap<LinearLayout, Job?>()

        private fun populateReadableDataView(container: LinearLayout, json: String?) {
            if (readableContentCache[container] == json) return
            readableContentCache[container] = json
            readableJobCache[container]?.cancel()
            val job = CoroutineScope(Dispatchers.Main).launch {
                val rows = withContext(Dispatchers.Default) {
                    val rowsList = mutableListOf<View>()
                    try {
                        val type =
                            object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                        val dataMap: Map<String, Any> = gsonParser.fromJson(json, type)
                        Prefs(container.context)

                        val fieldDefinitions = linkedMapOf(
                            "i" to ("Device ID" to ""),
                            "n" to ("Device Name" to ""),
                            "y" to ("Latitude" to "°"),
                            "x" to ("Longitude" to "°"),
                            "a" to ("Altitude" to " m"),
                            "s" to ("Speed" to " km/h"),
                            "ac" to ("GPS Accuracy" to " m"),
                            "p" to ("Battery Level" to "%"),
                            "c" to ("Battery Capacity" to " mAh"),
                            "d" to ("Download Speed" to " Mbps"),
                            "u" to ("Upload Speed" to " Mbps"),
                            "b" to ("WiFi BSSID" to ""),
                            "o" to ("Network Operator" to ""),
                            "t" to ("Network Type" to ""),
                            "r" to ("Signal Strength" to " dBm"),
                            "ci" to ("Cell ID" to ""),
                            "tc" to ("Tracking Area Code" to ""),
                            "mc" to ("Mobile Country Code" to ""),
                            "mn" to ("Mobile Network Code" to ""),
                            "bts" to ("Base Timestamp" to ""),
                            "tso" to ("Timestamp Offset" to " s")
                        )

                        for ((key, def) in fieldDefinitions) {
                            if (dataMap.containsKey(key)) {
                                val (label, unit) = def
                                val rawValue = dataMap[key]
                                var valueStr: String? = null

                                when (key) {
                                    "y", "x" -> valueStr = rawValue.toString()
                                    "a" -> valueStr = rawValue.toString()
                                    "ac" -> valueStr = rawValue.toString()
                                    "p" -> valueStr = rawValue.toString()
                                    "r" -> valueStr = rawValue.toString()
                                    "s" -> valueStr = rawValue.toString()
                                    "d" -> valueStr = rawValue.toString()
                                    "u" -> valueStr = rawValue.toString()
                                    "c" -> valueStr = rawValue.toString()
                                    "bts" -> valueStr =
                                        ((rawValue as? Double)?.toLong() ?: rawValue.toString()
                                            .toLongOrNull())?.let {
                                            SimpleDateFormat(
                                                "yyyy-MM-dd HH:mm:ss",
                                                Locale.getDefault()
                                            ).format(Date(it * 1000))
                                        } ?: rawValue.toString()
                                    "b" -> {
                                        val bssid = rawValue.toString()
                                        valueStr = if (bssid == "0" || bssid.length != 12) {
                                            "Disconnected"
                                        } else {
                                            bssid.chunked(2).joinToString(":")
                                                .uppercase(Locale.ROOT)
                                        }
                                    }

                                    "ci", "tc", "mc", "mn" -> {
                                        valueStr = when (rawValue) {
                                            is Number -> rawValue.toLong().toString()
                                            is String -> rawValue.toDoubleOrNull()?.toLong()
                                                ?.toString() ?: rawValue

                                            else -> rawValue.toString()
                                        }
                                    }
                                    else -> valueStr = rawValue?.toString() ?: "-"
                                }

                                val row = RelativeLayout(container.context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { topMargin = 4; bottomMargin = 4 }
                                }

                                val labelView = TextView(container.context).apply {
                                    text = label
                                    setTextColor(container.context.getColor(R.color.amoled_white))
                                    textSize = 13f
                                }

                                val valueView = TextView(container.context).apply {
                                    text = "${valueStr ?: "-"}$unit"
                                    setTextColor(container.context.getColor(R.color.amoled_light_gray))
                                    textSize = 13f
                                    typeface = Typeface.MONOSPACE
                                    layoutParams = RelativeLayout.LayoutParams(
                                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                                        RelativeLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
                                }
                                row.addView(labelView)
                                row.addView(valueView)
                                rowsList.add(row)
                            }
                        }
                    } catch (e: Exception) {
                        val errorView = TextView(container.context).apply {
                            text = "Error parsing data: ${e.message}"
                            setTextColor(container.context.getColor(R.color.amoled_red))
                            textSize = 12f
                        }
                        rowsList.clear(); rowsList.add(errorView)
                    }
                    rowsList
                }
                container.removeAllViews()
                rows.forEach { container.addView(it) }
                readableJobCache.remove(container)
            }
            readableJobCache[container] = job
        }

        inner class JsonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.jsonTitle)
            private val jsonContentView: TextView = itemView.findViewById(R.id.jsonContent)
            private val readableScrollView: ScrollView = itemView.findViewById(R.id.readableScrollView)
            private val readableDataContainer: LinearLayout = itemView.findViewById(R.id.readableDataContainer)

            fun bindOptimized(title: String, content: String?) {
                titleView.text = title
                jsonContentView.visibility = View.VISIBLE
                readableScrollView.visibility = View.GONE

                val prettyJson = if (content.isNullOrEmpty() || content.startsWith("Collection is inactive")) {
                    "Collection is inactive or waiting for data..."
                } else {
                    try {
                        gson.toJson(com.google.gson.JsonParser.parseString(content))
                    } catch (e: Exception) { content }
                }
                jsonContentView.text = prettyJson
            }

            fun bindReadable(title: String, content: String?) {
                titleView.text = title
                jsonContentView.visibility = View.GONE
                readableScrollView.visibility = View.VISIBLE
                populateReadableDataView(readableDataContainer, content)
            }
        }
    }
}