package com.example.hoarder.ui

import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.hoarder.utils.NetUtils
import com.example.hoarder.R
import com.example.hoarder.data.Prefs
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class UIHelper(private val a: MainActivity, private val p: Prefs) {
    private lateinit var rh: LinearLayout
    private lateinit var rc: LinearLayout
    private lateinit var ds: Switch
    private lateinit var sc: LinearLayout
    private lateinit var rt: TextView
    private lateinit var uc: MaterialCardView
    private lateinit var ut: TextView
    private lateinit var us: Switch
    private lateinit var ub: TextView
    private lateinit var um: TextView
    private lateinit var ue: EditText
    private lateinit var sb: Button
    private lateinit var uh: LinearLayout
    private lateinit var uc2: LinearLayout

    private lateinit var spinnerData: SpinnerData
    private lateinit var spinnerManager: SpinnerManager

    fun setupUI() {
        findViews()
        setupSpinners()
        setupState()
        setupListeners()
    }

    private fun findViews() {
        // Main UI components
        rh = a.findViewById(R.id.rawDataHeader)
        rc = a.findViewById(R.id.rawDataContent)
        ds = a.findViewById(R.id.dataCollectionSwitch)
        sc = a.findViewById(R.id.switchAndIconContainer)
        rt = a.findViewById(R.id.rawDataTitleTextView)
        uc = a.findViewById(R.id.serverUploadCard)
        ut = a.findViewById(R.id.serverUploadTitleTextView)
        us = a.findViewById(R.id.serverUploadSwitch)
        ub = a.findViewById(R.id.uploadedBytesTextView)
        um = a.findViewById(R.id.uploadMessageTextView)
        ue = a.findViewById(R.id.serverIpPortEditText)
        sb = a.findViewById(R.id.saveServerIpButton)
        uh = a.findViewById(R.id.serverUploadHeader)
        uc2 = a.findViewById(R.id.serverUploadContent)

        // Create SpinnerData object with all spinner references
        spinnerData = SpinnerData(
            a.findViewById(R.id.gpsPrecisionSpinner),
            a.findViewById(R.id.gpsPrecisionInfo),
            a.findViewById(R.id.gpsAltitudePrecisionSpinner),
            a.findViewById(R.id.gpsAltitudePrecisionInfo),
            a.findViewById(R.id.rssiPrecisionSpinner),
            a.findViewById(R.id.rssiPrecisionInfo),
            a.findViewById(R.id.batteryPrecisionSpinner),
            a.findViewById(R.id.batteryPrecisionInfo),
            a.findViewById(R.id.networkPrecisionSpinner),
            a.findViewById(R.id.networkPrecisionInfo),
            a.findViewById(R.id.speedPrecisionSpinner),
            a.findViewById(R.id.speedPrecisionInfo),
            a.findViewById(R.id.barometerPrecisionSpinner),
            a.findViewById(R.id.barometerPrecisionInfo)
        )
    }

    private fun setupSpinners() {
        spinnerManager = SpinnerManager(a)
        spinnerManager.setupAllSpinners(spinnerData, p)
    }

    private fun setupState() {
        val tr = Rect()
        sc.post {
            sc.getHitRect(tr)
            tr.top -= 100
            tr.bottom += 100
            tr.left -= 100
            tr.right += 100
            (sc.parent as View).touchDelegate = TouchDelegate(tr, sc)
        }

        val dc = p.isDataCollectionEnabled()
        val du = p.isDataUploadEnabled()
        val sa = p.getServerAddress()

        ds.isChecked = dc
        us.isChecked = du
        ue.setText(sa)

        rt.text = if (dc) "Json data (Active)" else "Json data (Inactive)"
        ut.text = if (du) "Server Upload (Active)" else "Server Upload (Inactive)"

        ub.text = "Uploaded: 0 B"
        um.text = ""

        rc.visibility = View.GONE
        uc2.visibility = View.GONE
    }

    private fun setupListeners() {
        setupDataPanelListeners()
        setupUploadPanelListeners()
    }

    private fun setupDataPanelListeners() {
        rh.setOnClickListener {
            rc.visibility = if (rc.visibility == View.GONE) {
                View.VISIBLE.also { a.getLastData()?.let { a.dj(it) } }
            } else View.GONE
        }

        ds.setOnCheckedChangeListener { _, c ->
            p.setDataCollectionEnabled(c)
            updateDataCollectionUI(c)
            if (c) a.startCollection() else a.stopCollection()
        }
    }

    private fun setupUploadPanelListeners() {
        uh.setOnClickListener {
            uc2.visibility = if (uc2.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        us.setOnCheckedChangeListener { _, c ->
            p.setDataUploadEnabled(c)
            if (c) {
                val addr = ue.text.toString()
                if (NetUtils.isValidIpPort(addr)) {
                    updateUploadUI(true)
                    updateStatus("Connecting", "Attempting to connect...", 0L)
                    a.startUpload(addr)
                } else {
                    us.isChecked = false
                    updateUploadUI(false)
                    Toast.makeText(a, "Invalid server IP:Port", Toast.LENGTH_SHORT).show()
                }
            } else {
                updateUploadUI(false)
                updateStatus("Paused", "Upload paused.", 0L)
                a.stopUpload()
            }
        }

        sb.setOnClickListener {
            val addr = ue.text.toString()
            if (NetUtils.isValidIpPort(addr)) {
                p.setServerAddress(addr)
                Toast.makeText(a, "Server address saved", Toast.LENGTH_SHORT).show()
                if (us.isChecked) {
                    updateStatus("Connecting", "Attempting to connect...", 0L)
                    a.stopUpload()
                    a.startUpload(addr)
                }
            } else {
                Toast.makeText(a, "Invalid server IP:Port format", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateDataCollectionUI(isActive: Boolean) {
        rt.text = if (isActive) "Json data (Active)" else "Json data (Inactive)"
    }

    fun updateUploadUI(isActive: Boolean) {
        ut.text = if (isActive) "Server Upload (Active)" else "Server Upload (Inactive)"
    }

    fun updateStatus(status: String?, message: String?, bytes: Long?) {
        val fb = if (bytes != null) formatBytes(bytes) else "0 B"
        ub.text = "Uploaded: $fb"

        if (status != null || message != null) {
            val text = when {
                status == "OK" -> "Status: OK\n"
                status == "Paused" -> "Status: Paused\n"
                status != null && message != null -> "Status: $status - $message\n"
                else -> "\n"
            }
            um.text = text
            val color = when (status) {
                "OK" -> R.color.amoled_green
                "Paused" -> R.color.amoled_light_gray
                else -> R.color.amoled_red
            }
            um.setTextColor(ContextCompat.getColor(a, color))
        }
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val e = (Math.log(b.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.Companion.format(Locale.getDefault(), "%.1f %sB", b / Math.pow(1024.0, e.toDouble()), p)
    }

    fun isDataVisible() = rc.visibility == View.VISIBLE
    fun setServerAddress(a: String) = ue.setText(a)
}