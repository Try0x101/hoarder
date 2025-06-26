package com.example.hoarder
import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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

    private lateinit var gps: Spinner
    private lateinit var rssi: Spinner
    private lateinit var batt: Spinner
    private lateinit var net: Spinner
    private lateinit var spd: Spinner

    private lateinit var spi: TextView
    private lateinit var ni: TextView
    private lateinit var ri: TextView
    private lateinit var bi: TextView
    private lateinit var gi: TextView

    fun setupUI() {
        findViews()
        setupSpinners()
        setupState()
        setupListeners()
    }

    private fun findViews() {
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

        gps = a.findViewById(R.id.gpsPrecisionSpinner)
        rssi = a.findViewById(R.id.rssiPrecisionSpinner)
        batt = a.findViewById(R.id.batteryPrecisionSpinner)
        net = a.findViewById(R.id.networkPrecisionSpinner)
        spd = a.findViewById(R.id.speedPrecisionSpinner)

        spi = a.findViewById(R.id.speedPrecisionInfo)
        ni = a.findViewById(R.id.networkPrecisionInfo)
        ri = a.findViewById(R.id.rssiPrecisionInfo)
        bi = a.findViewById(R.id.batteryPrecisionInfo)
        gi = a.findViewById(R.id.gpsPrecisionInfo)
    }

    private fun setupSpinners() {
        setupSpinner(gps, arrayOf("Smart GPS Precision", "Maximum precision", "20 m", "100 m", "1 km", "10 km"),
            p.getGPSPrecision(), { pos ->
                val nv = when(pos) {0->-1; 1->0; 2->20; 3->100; 4->1000; 5->10000; else->-1}
                p.setGPSPrecision(nv)
                updateInfoText(gi, pos == 0, "• If speed <4 km/h → round up to 1 km\n• If speed 4-40 km/h → round up to 20 m\n• If speed 40-140 km/h → round up to 100 m\n• If speed >140 km/h → round up to 1 km")
            }, { v ->
                when(v) {-1->0; 0->1; 20->2; 100->3; 1000->4; 10000->5; else->0}
            })

        setupSpinner(rssi, arrayOf("Smart RSSI Precision", "Maximum precision", "3 dBm", "5 dBm", "10 dBm"),
            p.getRSSIPrecision(), { pos ->
                val nv = when(pos) {0->-1; 1->0; 2->3; 3->5; 4->10; else->-1}
                p.setRSSIPrecision(nv)
                updateInfoText(ri, pos == 0, "• If signal worse than -110 dBm → show precise value\n• If signal worse than -90 dBm → round to nearest 5\n• If signal better than -90 dBm → round to nearest 10")
            }, { v ->
                when(v) {-1->0; 0->1; 3->2; 5->3; 10->4; else->0}
            })

        setupSpinner(batt, arrayOf("Smart Battery Precision", "Maximum precision", "2%", "5%", "10%"),
            p.getBatteryPrecision(), { pos ->
                val nv = when(pos) {0->-1; 1->0; 2->2; 3->5; 4->10; else->-1}
                p.setBatteryPrecision(nv)
                updateInfoText(bi, pos == 0, "• If battery below 10% → show precise value\n• If battery 10-50% → round to nearest 5%\n• If battery above 50% → round to nearest 10%")
            }, { v ->
                when(v) {-1->0; 0->1; 2->2; 5->3; 10->4; else->0}
            })

        setupSpinner(net, arrayOf("Smart Network Rounding", "Round to 1 Mbps", "Round to 2 Mbps", "Round to 5 Mbps"),
            p.getNetworkPrecision(), { pos ->
                val nv = when(pos) {0->0; 1->1; 2->2; 3->5; else->0}
                p.setNetworkPrecision(nv)
                updateInfoText(ni, pos == 0, "• If speed <7 Mbps → show precise value\n• If speed ≥7 Mbps → round to nearest 5 Mbps")
            }, { v ->
                when(v) {0->0; 1->1; 2->2; 5->3; else->0}
            })

        setupSpinner(spd, arrayOf("Smart Speed Rounding", "Maximum precision", "1 km/h", "3 km/h", "5 km/h", "10 km/h"),
            p.getSpeedPrecision(), { pos ->
                val nv = when(pos) {0->-1; 1->0; 2->1; 3->3; 4->5; 5->10; else->-1}
                p.setSpeedPrecision(nv)
                updateInfoText(spi, pos == 0, "• If speed <2 km/h → show 0\n• If speed <10 km/h → round to nearest 3 km/h\n• If speed ≥10 km/h → round to nearest 10 km/h")
            }, { v ->
                when(v) {-1->0; 0->1; 1->2; 3->3; 5->4; 10->5; else->0}
            })
    }

    private fun <T> setupSpinner(s: Spinner, o: Array<T>, cv: Int, oc: (Int) -> Unit, vm: (Int) -> Int) {
        val a = ArrayAdapter(a, android.R.layout.simple_spinner_item, o)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        s.adapter = a
        s.setSelection(vm(cv))
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = oc(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateInfoText(tv: TextView, show: Boolean, text: String) {
        if (show) {
            tv.text = text
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
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

        // Initialize status text
        ub.text = "Uploaded: 0 B"
        um.text = ""

        rc.visibility = View.GONE
        uc2.visibility = View.GONE
    }

    private fun setupListeners() {
        rh.setOnClickListener {
            if (rc.visibility == View.GONE) {
                rc.visibility = View.VISIBLE
                a.getLastData()?.let { a.dj(it) }
            } else {
                rc.visibility = View.GONE
            }
        }

        uh.setOnClickListener {
            uc2.visibility = if (uc2.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        ds.setOnCheckedChangeListener { _, c ->
            p.setDataCollectionEnabled(c)
            updateDataCollectionUI(c)
            if (c) a.startCollection() else a.stopCollection()
        }

        us.setOnCheckedChangeListener { _, c ->
            p.setDataUploadEnabled(c)

            if (c) {
                val addr = ue.text.toString()
                if (NetUtils.isValidIpPort(addr)) {
                    updateUploadUI(true)
                    // Reset display immediately for better UX
                    updateStatus("Connecting", "Attempting to connect...", 0L)
                    a.startUpload(addr)
                } else {
                    us.isChecked = false
                    updateUploadUI(false)
                    Toast.makeText(a, "Invalid server IP:Port", Toast.LENGTH_SHORT).show()
                }
            } else {
                updateUploadUI(false)
                // Reset display immediately for better UX
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
                    // Restart upload with new address
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
        val fb = if (bytes != null) fb(bytes) else "0 B"
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

    private fun fb(b: Long): String {
        if (b < 1024) return "$b B"
        val e = (Math.log(b.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", b / Math.pow(1024.0, e.toDouble()), p)
    }

    fun isDataVisible() = rc.visibility == View.VISIBLE

    fun setServerAddress(a: String) {
        ue.setText(a)
    }
}