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
    private val handler = Handler(Looper.getMainLooper())

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
                cp()
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
        sp=getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        sgs()
        srs()
        sbs()
        sns()
        sss()
        su()
        scl()
        cp()
        ss()

        LocalBroadcastManager.getInstance(this).apply{
            registerReceiver(dr,IntentFilter("com.example.hoarder.DATA_UPDATE"))
            registerReceiver(usr,IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
            registerReceiver(permissionReceiver, IntentFilter("com.example.hoarder.PERMISSIONS_REQUIRED"))
        }

        // Run first install logic after everything is set up
        handleFirstInstall()
    }

    private fun handleFirstInstall() {
        val isFirstRun = sp.getBoolean("isFirstRun", true)

        if (isFirstRun) {
            // Set the flag to false so this won't run again
            sp.edit().putBoolean("isFirstRun", false).apply()

            // Make sure initial state of both toggles is off (regardless of saved preference)
            if (ds.isChecked) {
                ds.isChecked = false
                sp.edit().putBoolean("dataCollectionToggleState", false).apply()
                rt.text = "Json data (Inactive)"
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))
            }

            if (us.isChecked) {
                us.isChecked = false
                sp.edit().putBoolean("dataUploadToggleState", false).apply()
                ut.text = "Server Upload (Inactive)"
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))
            }

            // After 2 seconds, enable data collection
            handler.postDelayed({
                if (!ds.isChecked) {
                    ds.isChecked = true
                    // The toggle change listener will handle the rest

                    // After data collection is enabled, enable server upload if we have a valid IP
                    handler.postDelayed({
                        val ipPort = ue.text.toString()
                        if (!us.isChecked && ipPort.isNotEmpty() && vip(ipPort)) {
                            us.isChecked = true
                            // The toggle change listener will handle the rest
                        } else if (ipPort.isEmpty() || !vip(ipPort)) {
                            // If we don't have a valid IP, set a default one
                            val defaultIp = "127.0.0.1:5000"
                            ue.setText(defaultIp)
                            sp.edit().putString("serverIpPortAddress", defaultIp).apply()
                            Toast.makeText(this@MainActivity, "Using default server address", Toast.LENGTH_SHORT).show()

                            // Now enable the server upload
                            us.isChecked = true
                        }
                    }, 1000) // 1 second after data collection
                }
            }, 2000) // 2 seconds after start
        }
    }

    private fun sgs(){
        val po=arrayOf("Maximum precision","20 m","50 m","100 m","500 m","1 km","10 km")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gps.adapter=ad
        val cp=sp.getInt("gpsPrecision",100)
        val ps=when(cp){0->0;20->1;50->2;100->3;500->4;1000->5;10000->6;else->3}
        gps.setSelection(ps)
        gps.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->0;1->20;2->50;3->100;4->500;5->1000;6->10000;else->100}
                sp.edit().putInt("gpsPrecision",nv).apply()
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }
    private fun srs(){
        val po=arrayOf("Maximum precision","3 dBm","5 dBm","10 dBm")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        rssi.adapter=ad
        val cp=sp.getInt("rssiPrecision",5)
        val ps=when(cp){0->0;3->1;5->2;10->3;else->2}
        rssi.setSelection(ps)
        rssi.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->0;1->3;2->5;3->10;else->5}
                sp.edit().putInt("rssiPrecision",nv).apply()
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
    }
    private fun sbs(){
        val po=arrayOf("Maximum precision","1%","5%","10%")
        val ad=ArrayAdapter(this,android.R.layout.simple_spinner_item,po)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        batt.adapter=ad
        val cp=sp.getInt("batteryPrecision",5)
        val ps=when(cp){0->0;1->1;5->2;10->3;else->2}
        batt.setSelection(ps)
        batt.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){
                val nv=when(pos){0->0;1->1;2->5;3->10;else->5}
                sp.edit().putInt("batteryPrecision",nv).apply()
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
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
    private fun cp(){
        val p=arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
        )
        val ptr=mutableListOf<String>()
        for(pe in p)if(ContextCompat.checkSelfPermission(this,pe)!=PackageManager.PERMISSION_GRANTED)ptr.add(pe)
        if(ptr.isNotEmpty())ActivityCompat.requestPermissions(this,ptr.toTypedArray(),100)
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