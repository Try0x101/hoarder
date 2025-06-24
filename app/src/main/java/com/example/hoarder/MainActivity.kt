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
import android.view.TouchDelegate
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.EditText
import android.widget.Button
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
import kotlinx.coroutines.*

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
    private lateinit var queueStatsText:TextView
    private lateinit var db:TelemetryDatabase
    private val activityScope=CoroutineScope(Dispatchers.Main+SupervisorJob())

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
                val nu=if(s=="OK"||s=="OK (Delta)"||s=="OK (Full)")"Status: OK\n"else if(s=="Paused")"Status: Paused\n"else if(s=="Uploading")"Status: Uploading\n"else if(s=="Offline")"Status: Offline\n"else if(s=="Stored")"Status: Stored Locally\n"else if(s!=null&&m!=null)"Status: "+s+" - "+m+"\n"else"\n"
                um.text=nu
                if(s=="OK"||s=="OK (Delta)"||s=="OK (Full)"||s=="Uploading")um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_green))
                else if(s=="Paused")um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_light_gray))
                else if(s=="Stored")um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_true_blue))
                else um.setTextColor(ContextCompat.getColor(c!!,R.color.amoled_red))
            }
            activityScope.launch{updateQueueStats()}
        }
    }

    private suspend fun updateQueueStats(){
        try{
            val dao=db.telemetryDao()
            val pendingCount=dao.getPendingCount()
            val uploadedCount=dao.getUploadedCount()
            val failedRecords=dao.getFailedRecords()

            withContext(Dispatchers.Main){
                queueStatsText.text="Queue: $pendingCount pending, $uploadedCount uploaded, ${failedRecords.size} failed"
            }
        }catch(e:Exception){
            withContext(Dispatchers.Main){
                queueStatsText.text="Queue stats unavailable"
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
        queueStatsText=findViewById(R.id.queueStatsTextView)

        sp=getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        db=TelemetryDatabase.getDatabase(this)

        su()
        scl()
        cp()
        ss()

        LocalBroadcastManager.getInstance(this).apply{
            registerReceiver(dr,IntentFilter("com.example.hoarder.DATA_UPDATE"))
            registerReceiver(usr,IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
        }

        activityScope.launch{updateQueueStats()}
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

        queueStatsText.setOnClickListener{
            activityScope.launch{
                try{
                    val dao=db.telemetryDao()
                    val failedRecords=dao.getFailedRecords()
                    if(failedRecords.isNotEmpty()){
                        dao.resetFailedRecords()
                        Toast.makeText(this@MainActivity,"Reset ${failedRecords.size} failed records",Toast.LENGTH_SHORT).show()
                        updateQueueStats()
                    }else{
                        Toast.makeText(this@MainActivity,"No failed records to reset",Toast.LENGTH_SHORT).show()
                    }
                }catch(e:Exception){
                    Toast.makeText(this@MainActivity,"Error resetting failed records",Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cp(){
        val p=arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.READ_PHONE_STATE,Manifest.permission.FOREGROUND_SERVICE,Manifest.permission.POST_NOTIFICATIONS)
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
        activityScope.cancel()
        LocalBroadcastManager.getInstance(this).apply{unregisterReceiver(dr);unregisterReceiver(usr)}
    }
}