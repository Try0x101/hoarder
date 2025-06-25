// app/src/main/java/com/example/hoarder/MainActivity.kt
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
import android.widget.EditText
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import android.content.SharedPreferences
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.app.NotificationChannel
import androidx.core.app.NotificationCompat

class MainActivity:AppCompatActivity(){
    private lateinit var dt:TextView
    private lateinit var rh:LinearLayout
    private lateinit var rc:LinearLayout
    private lateinit var ds:Switch
    private lateinit var sc:LinearLayout
    private lateinit var rt:TextView
    private var ld:String?=null
    private val g=GsonBuilder().setPrettyPrinting().create()
    private lateinit var uc:com.google.android.material.card.MaterialCardView
    private lateinit var ut:TextView
    private lateinit var us:Switch
    private lateinit var ub:TextView
    private lateinit var um:TextView
    private lateinit var ue:EditText
    private lateinit var sb:Button
    private lateinit var uh:LinearLayout
    private lateinit var uc2:LinearLayout
    private lateinit var sp:SharedPreferences
    private lateinit var gps:Spinner
    private lateinit var rssi:Spinner
    private lateinit var batt:Spinner
    private lateinit var net:Spinner
    private lateinit var spd:Spinner
    private lateinit var spdInfo:TextView
    private lateinit var netInfo:TextView
    private lateinit var rssiInfo:TextView
    private lateinit var battInfo:TextView
    private lateinit var gpsInfo:TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFirstRunInit = false
    private var permissionsDialogActive = AtomicBoolean(false)

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION
    )

    private val dr=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            i?.getStringExtra("jsonString")?.let{js->ld=js;if(rc.visibility==View.VISIBLE)dp(js)}
        }
    }
    private val usr=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            val s=i?.getStringExtra("status")
            val m=i?.getStringExtra("message")
            val tb=i?.getLongExtra("totalUploadedBytes",0L)
            val fb=if(tb!=null)fb2(tb)else"0 B"
            ub.text="Uploaded: "+fb
            if(s!=null||m!=null){
                val nu=if(s=="OK")"Status: OK\n"else if(s=="Paused")"Status: Paused\n"else if(s!=null&&m!=null)"Status: "+s+" - "+m+"\n"else"\n"
                um.text=nu
                if(s=="OK")um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_green))
                else if(s=="Paused")um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_light_gray))
                else um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_red))
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == "com.example.hoarder.PERMISSIONS_REQUIRED") {
                Toast.makeText(
                    this@MainActivity,
                    "Location permissions required for this app to function properly",
                    Toast.LENGTH_LONG
                ).show()
                requestNextPermission()
            }
        }
    }

    private fun dp(js:String){
        try{val je=JsonParser.parseString(js);val pj=g.toJson(je);dt.text=pj}catch(e:Exception){dt.text="Error formatting JSON: "+e.message}
    }
    private fun fb2(b:Long):String{
        if(b<1024)return b.toString()+" B"
        val e=(Math.log(b.toDouble())/Math.log(1024.0)).toInt()
        val p="KMGTPE"[e-1]
        return String.format(Locale.getDefault(),"%.1f %sB",b/Math.pow(1024.0,e.toDouble()),p)
    }
    override fun onCreate(sb2:Bundle?){
        super.onCreate(sb2)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)){v,i->val sb3=i.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(sb3.left,sb3.top,sb3.right,sb3.bottom);i}
        dt=findViewById(R.id.dataTextView)
        rh=findViewById(R.id.rawDataHeader)
        rc=findViewById(R.id.rawDataContent)
        ds=findViewById(R.id.dataCollectionSwitch)
        sc=findViewById(R.id.switchAndIconContainer)
        rt=rh.findViewById(R.id.rawDataTitleTextView)
        uc=findViewById(R.id.serverUploadCard)
        ut=findViewById(R.id.serverUploadTitleTextView)
        us=findViewById(R.id.serverUploadSwitch)
        ub=findViewById(R.id.uploadedBytesTextView)
        um=findViewById(R.id.uploadMessageTextView)
        ue=findViewById(R.id.serverIpPortEditText)
        sb=findViewById(R.id.saveServerIpButton)
        uh=findViewById(R.id.serverUploadHeader)
        uc2=findViewById(R.id.serverUploadContent)
        gps=findViewById(R.id.gpsPrecisionSpinner)
        rssi=findViewById(R.id.rssiPrecisionSpinner)
        batt=findViewById(R.id.batteryPrecisionSpinner)
        net=findViewById(R.id.networkPrecisionSpinner)
        spd=findViewById(R.id.speedPrecisionSpinner)
        spdInfo=findViewById(R.id.speedPrecisionInfo)
        netInfo=findViewById(R.id.networkPrecisionInfo)
        rssiInfo=findViewById(R.id.rssiPrecisionInfo)
        battInfo=findViewById(R.id.batteryPrecisionInfo)
        gpsInfo=findViewById(R.id.gpsPrecisionInfo)
        sp=getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)

        // Create silent notification channel immediately
        createSilentNotificationChannel()

        sgs()
        srs()
        sbs()
        sns()
        sss()
        su()
        scl()

        LocalBroadcastManager.getInstance(this).apply{
            registerReceiver(dr,IntentFilter("com.example.hoarder.DATA_UPDATE"))
            registerReceiver(usr,IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
            registerReceiver(permissionReceiver, IntentFilter("com.example.hoarder.PERMISSIONS_REQUIRED"))
        }

        val isFirstRun = sp.getBoolean("isFirstRun", true)
        if (isFirstRun) {
            pendingFirstRunInit = true
            if (areAllPermissionsGranted()) {
                pendingFirstRunInit = false
                handleFirstInstall()
            } else {
                requestNextPermission()
            }
        } else {
            if (areAllPermissionsGranted()) {
                ss()
            } else {
                requestNextPermission()
            }
        }
    }

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Check if channel exists first
            val existingChannel = notificationManager.getNotificationChannel("HoarderServiceChannel")

            // Create or update the channel with silent settings
            val channel = existingChannel ?: NotificationChannel(
                "HoarderServiceChannel",
                "Hoarder Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )

            // Configure for silent operation
            channel.apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                setSound(null, null)
                description = "Silent background operation"
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun requestNextPermission() {
        if (permissionsDialogActive.get()) {
            return
        }

        val pendingPermissions = mutableListOf<String>()
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                pendingPermissions.add(permission)
            }
        }

        if (pendingPermissions.isEmpty()) {
            if (pendingFirstRunInit) {
                pendingFirstRunInit = false
                handleFirstInstall()
            } else {
                ss()
            }
            return
        }

        permissionsDialogActive.set(true)
        ActivityCompat.requestPermissions(this, pendingPermissions.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsDialogActive.set(false)

        if (requestCode == 100) {
            if (areAllPermissionsGranted()) {
                if (pendingFirstRunInit) {
                    pendingFirstRunInit = false
                    handleFirstInstall()
                } else {
                    ss()
                }
            } else {
                Toast.makeText(this, "App requires all permissions to function properly", Toast.LENGTH_LONG).show()
                handler.postDelayed({ requestNextPermission() }, 1000)
            }
        }
    }

    private fun handleFirstInstall() {
        sp.edit().putBoolean("isFirstRun", false).apply()

        // Force data collection to be ON by default
        sp.edit().putBoolean("dataCollectionToggleState", true).apply()
        ds.isChecked = true
        rt.text = "Json data (Active)"

        // Set a default server if not already set
        val currentIp = sp.getString("serverIpPortAddress", "")
        if (currentIp.isNullOrBlank() || !vip(currentIp)) {
            val defaultIp = "127.0.0.1:5000"
            ue.setText(defaultIp)
            sp.edit().putString("serverIpPortAddress", defaultIp).apply()
        }

        // Force upload to be ON by default
        sp.edit().putBoolean("dataUploadToggleState", true).apply()
        us.isChecked = true
        ut.text = "Server Upload (Active)"

        // Ensure notification channel is silent
        createSilentNotificationChannel()

        // Start the service immediately
        ss()

        // Send broadcast to start collection and upload
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_COLLECTION"))
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", ue.text.toString())
        )

        // Show a toast to let the user know the app is configured and closing
        Toast.makeText(
            this,
            "Setup complete. App will run in background.",
            Toast.LENGTH_SHORT
        ).show()

        // Close the app after a short delay to allow the toast to be seen
        handler.postDelayed({
            finishAffinity() // This will close all activities in the app
        }, 2000) // 2 second delay
    }

    private fun sgs(){
        val po=arrayOf("Smart GPS Precision", "Maximum precision","20 m","100 m","1 km","10 km")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gps.adapter=ad
        val cp=sp.getInt("gpsPrecision",-1)
        val ps=when(cp){-1->0; 0->1; 20->2; 100->3; 1000->4; 10000->5; else->0}
        gps.setSelection(ps)

        updateGPSInfoText(ps)

        gps.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->-1; 1->0; 2->20; 3->100; 4->1000; 5->10000; else->-1}
                sp.edit().putInt("gpsPrecision",nv).apply()
                updateGPSInfoText(pos)
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }

    private fun updateGPSInfoText(position: Int) {
        if (position == 0) {
            gpsInfo.text = "• If speed <4 km/h → round up to 1 km\n• If speed 4-40 km/h → round up to 20 m\n• If speed 40-140 km/h → round up to 100 m\n• If speed >140 km/h → round up to 1 km"
            gpsInfo.visibility = View.VISIBLE
        } else {
            gpsInfo.visibility = View.GONE
        }
    }

    private fun srs(){
        val po=arrayOf("Smart RSSI Precision", "Maximum precision","3 dBm","5 dBm","10 dBm")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        rssi.adapter=ad
        val cp=sp.getInt("rssiPrecision",-1)
        val ps=when(cp){-1->0; 0->1; 3->2; 5->3; 10->4; else->0}
        rssi.setSelection(ps)

        updateRSSIInfoText(ps)

        rssi.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->-1; 1->0; 2->3; 3->5; 4->10; else->-1}
                sp.edit().putInt("rssiPrecision",nv).apply()
                updateRSSIInfoText(pos)
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }

    private fun updateRSSIInfoText(position: Int) {
        if (position == 0) {
            rssiInfo.text = "• If signal worse than -110 dBm → show precise value\n• If signal worse than -90 dBm → round to nearest 5\n• If signal better than -90 dBm → round to nearest 10"
            rssiInfo.visibility = View.VISIBLE
        } else {
            rssiInfo.visibility = View.GONE
        }
    }

    private fun sbs(){
        val po=arrayOf("Smart Battery Precision", "Maximum precision","2%","5%","10%")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        batt.adapter=ad
        val cp=sp.getInt("batteryPrecision",-1)
        val ps=when(cp){-1->0; 0->1; 2->2; 5->3; 10->4; else->0}
        batt.setSelection(ps)

        updateBatteryInfoText(ps)

        batt.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->-1; 1->0; 2->2; 3->5; 4->10; else->-1}
                sp.edit().putInt("batteryPrecision",nv).apply()
                updateBatteryInfoText(pos)
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }

    private fun updateBatteryInfoText(position: Int) {
        if (position == 0) {
            battInfo.text = "• If battery below 10% → show precise value\n• If battery 10-50% → round to nearest 5%\n• If battery above 50% → round to nearest 10%"
            battInfo.visibility = View.VISIBLE
        } else {
            battInfo.visibility = View.GONE
        }
    }

    private fun sns(){
        val po=arrayOf("Smart Network Rounding","Round to 1 Mbps","Round to 2 Mbps","Round to 5 Mbps")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        net.adapter=ad
        val cp=sp.getInt("networkPrecision",0)
        val ps=when(cp){0->0;1->1;2->2;5->3;else->0}
        net.setSelection(ps)

        updateNetworkInfoText(ps)

        net.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->0;1->1;2->2;3->5;else->0}
                sp.edit().putInt("networkPrecision",nv).apply()

                updateNetworkInfoText(pos)
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }

    private fun updateNetworkInfoText(position: Int) {
        if (position == 0) {
            netInfo.text = "• If speed <7 Mbps → show precise value\n• If speed ≥7 Mbps → round to nearest 5 Mbps"
            netInfo.visibility = View.VISIBLE
        } else {
            netInfo.visibility = View.GONE
        }
    }

    private fun sss(){
        val po = arrayOf("Smart Speed Rounding", "Maximum precision", "1 km/h", "3 km/h", "5 km/h", "10 km/h")
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spd.adapter = ad

        val cp = sp.getInt("speedPrecision", -1)
        val ps = when(cp) {-1->0; 0->1; 1->2; 3->3; 5->4; 10->5; else->0}
        spd.setSelection(ps)

        updateSpeedInfoText(ps)

        spd.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val nv = when(pos) {0->-1; 1->0; 2->1; 3->3; 4->5; 5->10; else->-1}
                sp.edit().putInt("speedPrecision", nv).apply()

                updateSpeedInfoText(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateSpeedInfoText(position: Int) {
        if (position == 0) {
            spdInfo.text = "• If speed <2 km/h → show 0\n• If speed <10 km/h → round to nearest 3 km/h\n• If speed ≥10 km/h → round to nearest 10 km/h"
            spdInfo.visibility = View.VISIBLE
        } else {
            spdInfo.visibility = View.GONE
        }
    }

    private fun su(){
        val tr=Rect()
        sc.post{sc.getHitRect(tr);tr.top-=100;tr.bottom+=100;tr.left-=100;tr.right+=100;(sc.parent as View).touchDelegate=TouchDelegate(tr,sc)}
        val ce=sp.getBoolean("dataCollectionToggleState",true)
        val ue2=sp.getBoolean("dataUploadToggleState",false)
        val ss=sp.getString("serverIpPortAddress","")
        ds.isChecked=ce
        us.isChecked=ue2
        ue.setText(ss)
        rt.text=if(ce)"Json data (Active)"else"Json data (Inactive)"
        ut.text=if(ue2)"Server Upload (Active)"else"Server Upload (Inactive)"
        rc.visibility=View.GONE
        uc2.visibility=View.GONE
    }

    private fun scl(){
        rh.setOnClickListener{
            if(rc.visibility==View.GONE){rc.visibility=View.VISIBLE;ld?.let{js->dp(js)}}else rc.visibility=View.GONE
        }
        uh.setOnClickListener{
            if(uc2.visibility==View.GONE)uc2.visibility=View.VISIBLE else uc2.visibility=View.GONE
        }
        ds.setOnCheckedChangeListener{_,c->
            sp.edit().putBoolean("dataCollectionToggleState",c).apply()
            rt.text=if(c)"Json data (Active)"else"Json data (Inactive)"
            if(c)LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_COLLECTION"))
            else{dt.text="";ld=null;LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))}
        }
        us.setOnCheckedChangeListener{_,c->
            sp.edit().putBoolean("dataUploadToggleState",c).apply()
            if(c){
                val sip=ue.text.toString()
                if(vip(sip)){ut.text="Server Upload (Active)";LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort",sip))}
                else{us.isChecked=false;ut.text="Server Upload (Inactive)";Toast.makeText(this,"Invalid server IP:Port",Toast.LENGTH_SHORT).show()}
            }else{ut.text="Server Upload (Inactive)";LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))}
        }
        sb.setOnClickListener{
            val sip=ue.text.toString()
            if(vip(sip)){sp.edit().putString("serverIpPortAddress",sip).apply();Toast.makeText(this,"Server address saved",Toast.LENGTH_SHORT).show();if(us.isChecked)LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort",sip))}
            else Toast.makeText(this,"Invalid server IP:Port format",Toast.LENGTH_SHORT).show()
        }
    }

    private fun ss(){
        val si=Intent(this,BackgroundService::class.java)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)startForegroundService(si)else startService(si)
    }

    private fun vip(ip:String):Boolean{
        if(ip.isBlank())return false
        val p=ip.split(":")
        if(p.size!=2)return false
        val i=p[0]
        val po=p[1].toIntOrNull()
        val ipa=i.split(".")
        if(ipa.size!=4)return false
        for(pa in ipa){val n=pa.toIntOrNull();if(n==null||n<0||n>255)return false}
        if(po==null||po<=0||po>65535)return false
        return true
    }

    override fun onResume() {
        super.onResume()
        // Ensure notification channel is still silent (some devices reset this)
        createSilentNotificationChannel()

        // If service is not running, restart it
        if (areAllPermissionsGranted()) {
            val serviceRunning = isServiceRunning(BackgroundService::class.java)
            if (!serviceRunning) {
                ss()
                // Restore toggle states if needed
                val dataCollectionEnabled = sp.getBoolean("dataCollectionToggleState", true)
                val uploadEnabled = sp.getBoolean("dataUploadToggleState", true)

                if (dataCollectionEnabled) {
                    LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_COLLECTION"))
                }

                if (uploadEnabled) {
                    val ipPort = sp.getString("serverIpPortAddress", "127.0.0.1:5000") ?: "127.0.0.1:5000"
                    if (vip(ipPort)) {
                        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", ipPort))
                    }
                }
            }
        }
    }

    // Add this method to check if service is running
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onDestroy(){
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).apply{
            unregisterReceiver(dr)
            unregisterReceiver(usr)
            unregisterReceiver(permissionReceiver)
        }
        handler.removeCallbacksAndMessages(null)
    }
}