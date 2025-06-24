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
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import java.util.Locale
import android.util.Log
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt
class BackgroundService:Service(){
    private lateinit var h:Handler
    private lateinit var dr:Runnable
    private lateinit var ur:Runnable
    private lateinit var lm:LocationManager
    private lateinit var tm:TelephonyManager
    private lateinit var wm:WifiManager
    private lateinit var bm:BatteryManager
    private lateinit var cm:ConnectivityManager
    private lateinit var sp:SharedPreferences
    private var ll:Location?=null
    private var bd:Map<String,Any>?=null
    private var ca=false
    private var ua=false
    private var ip=""
    private var port=5000
    private var ld:String?=null
    private var lu:String?=null
    private val g=GsonBuilder().create()
    private var tb=0L
    private var ls:String?=null
    private var lm2:Pair<String,String>?=null
    private var lt=0L
    private val cd=5000L
    private val ll2=object:LocationListener{
        override fun onLocationChanged(l:Location){ll=l}
        override fun onStatusChanged(p:String?,s:Int,e:Bundle?){}
        override fun onProviderEnabled(p:String){}
        override fun onProviderDisabled(p:String){}
    }
    private val br=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            if(i?.action==Intent.ACTION_BATTERY_CHANGED){
                val l=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
                val s=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
                val p=l*100/s.toFloat()
                val st=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
                val ss=when(st){
                    BatteryManager.BATTERY_STATUS_CHARGING->"Charging"
                    else->"Discharging"
                }
                var c2:Int?=null
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP){
                    val cc=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val cp=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if(cc>0&&cp>0)c2=(cc/1000*100)/cp
                }
                bd=buildMap{
                    put("percent",p.toInt())
                    put("status",ss)
                    if(c2!=null)put("estimated_full_capacity_mAh",c2)
                }
            }
        }
    }
    private val cr=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            when(i?.action){
                "com.example.hoarder.START_COLLECTION"->{
                    if(!ca){ca=true;sd()}
                }
                "com.example.hoarder.STOP_COLLECTION"->{
                    if(ca){ca=false;h.removeCallbacks(dr);ld=null;LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString",""))}
                }
                "com.example.hoarder.START_UPLOAD"->{
                    val ip2=i?.getStringExtra("ipPort")?.split(":")
                    if(ip2!=null&&ip2.size==2&&ip2[0].isNotBlank()&&ip2[1].toIntOrNull()!=null&&ip2[1].toInt()>0&&ip2[1].toInt()<=65535){
                        ip=ip2[0];port=ip2[1].toInt();ua=true;lu=null;ls=null;su("Connecting","Attempting to connect...",tb);su2()
                    }else{ua=false;h.removeCallbacks(ur);su("Error","Invalid Server IP:Port for starting upload.",0L)}
                }
                "com.example.hoarder.STOP_UPLOAD"->{
                    if(ua){ua=false;h.removeCallbacks(ur);tb=0L;sp.edit().putLong("totalUploadedBytes",0L).apply();lu=null;ls=null;su("Paused","Upload paused.",tb)}
                }
            }
        }
    }
    override fun onCreate(){
        super.onCreate()
        h=Handler(Looper.getMainLooper())
        bm=getSystemService(Context.BATTERY_SERVICE)as BatteryManager
        lm=getSystemService(Context.LOCATION_SERVICE)as LocationManager
        tm=getSystemService(Context.TELEPHONY_SERVICE)as TelephonyManager
        wm=applicationContext.getSystemService(Context.WIFI_SERVICE)as WifiManager
        cm=getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager
        sp=getSharedPreferences("HoarderServicePrefs",Context.MODE_PRIVATE)
        registerReceiver(br,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        LocalBroadcastManager.getInstance(this).registerReceiver(cr,IntentFilter().apply{
            addAction("com.example.hoarder.START_COLLECTION")
            addAction("com.example.hoarder.STOP_COLLECTION")
            addAction("com.example.hoarder.START_UPLOAD")
            addAction("com.example.hoarder.STOP_UPLOAD")
        })
        dr=object:Runnable{override fun run(){if(ca){c();h.postDelayed(this,1000L)}}}
        ur=object:Runnable{override fun run(){
            if(ua&&ld!=null&&ip.isNotBlank()&&port>0){
                Thread{ld?.let{val(d,delta)=gj(it);if(d!=null)u(d,it,delta)else su("No Change","Data unchanged, skipping upload.",tb)}}.start()
                if(ua)h.postDelayed(this,1000L)
            }else if(ua&&(ip.isBlank()||port<=0)){su("Error","Server IP or Port became invalid.",tb);ua=false}
        }}
    }
    override fun onStartCommand(i:Intent?,f:Int,s:Int):Int{
        cn()
        val ni=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,0,ni,PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(applicationContext,"HoarderServiceChannel").setContentTitle(applicationContext.getString(R.string.app_name)).setContentText("Collecting device data in background...").setSmallIcon(R.mipmap.ic_launcher).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).build()
        startForeground(1,n)
        sl()
        val mp=applicationContext.getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        val ct=mp.getBoolean("dataCollectionToggleState",true)
        val ut=mp.getBoolean("dataUploadToggleState",false)
        val sp2=mp.getString("serverIpPortAddress","")?.split(":")
        tb=sp.getLong("totalUploadedBytes",0L)
        if(sp2!=null&&sp2.size==2&&sp2[0].isNotBlank()&&sp2[1].toIntOrNull()!=null){ip=sp2[0];port=sp2[1].toInt()}else{ip="";port=0}
        if(ct){if(!ca){ca=true;sd()}}else{ca=false;h.removeCallbacks(dr)}
        if(ut&&ip.isNotBlank()&&port>0){ua=true;lu=null;ls=null;su("Connecting","Service (re)start, attempting to connect...",tb);su2()}else ua=false
        return START_STICKY
    }
    override fun onDestroy(){
        super.onDestroy()
        h.removeCallbacks(dr)
        h.removeCallbacks(ur)
        lm.removeUpdates(ll2)
        unregisterReceiver(br)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cr)
    }
    override fun onBind(i:Intent?):IBinder?=null
    private fun cn(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            val sc=NotificationChannel("HoarderServiceChannel","Hoarder Service Channel",NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(sc)
        }
    }
    private fun sl(){
        try{
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,ll2)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0f,ll2)
        }catch(e:SecurityException){e.printStackTrace()}
    }
    private fun sd(){h.removeCallbacks(dr);if(ca)h.post(dr)}
    private fun su2(){h.removeCallbacks(ur);if(ua&&ip.isNotBlank()&&port>0)h.post(ur)else if(ua){ua=false;su("Error","Cannot start upload: Server IP or Port is invalid.",tb)}}
    private fun c(){
        if(!ca)return
        val dm=mutableMapOf<String,Any>()
        val di=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)
        dm["id"]=di.take(4)
        dm["n"]=Build.MODEL
        bd?.let{
            it["percent"]?.let{v->dm["perc"]=v}
            it["status"]?.let{v->dm["stat"]=v}
            it["estimated_full_capacity_mAh"]?.let{v->dm["cap"]=v}
        }?:run{dm["stat"]="Battery data unavailable"}
        ll?.let{
            val ra=(it.altitude/2).roundToInt()*2
            val ra2=(it.accuracy/10).roundToInt()*10
            val rb=it.bearing.roundToInt()
            val sk=(it.speed*3.6).roundToInt()
            val rl=String.format(Locale.US,"%.4f",it.latitude).toDouble()
            val rlo=String.format(Locale.US,"%.4f",it.longitude).toDouble()
            dm["lat"]=rl;dm["lon"]=rlo;dm["alt"]=ra;dm["acc"]=ra2;dm["bear"]=rb;dm["spd"]=sk
        }
        try{
            dm["op"]=tm.networkOperatorName
            val ant=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
                when(tm.dataNetworkType){
                    TelephonyManager.NETWORK_TYPE_GPRS->"GPRS"
                    TelephonyManager.NETWORK_TYPE_EDGE->"EDGE"
                    TelephonyManager.NETWORK_TYPE_UMTS->"UMTS"
                    TelephonyManager.NETWORK_TYPE_CDMA->"CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0->"EVDO_0"
                    TelephonyManager.NETWORK_TYPE_EVDO_A->"EVDO_A"
                    TelephonyManager.NETWORK_TYPE_1xRTT->"1xRTT"
                    TelephonyManager.NETWORK_TYPE_HSDPA->"HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSUPA->"HSUPA"
                    TelephonyManager.NETWORK_TYPE_HSPA->"HSPA"
                    TelephonyManager.NETWORK_TYPE_IDEN->"IDEN"
                    TelephonyManager.NETWORK_TYPE_EVDO_B->"EVDO_B"
                    TelephonyManager.NETWORK_TYPE_LTE->"LTE"
                    TelephonyManager.NETWORK_TYPE_EHRPD->"EHRPD"
                    TelephonyManager.NETWORK_TYPE_HSPAP->"HSPAP"
                    TelephonyManager.NETWORK_TYPE_GSM->"GSM"
                    TelephonyManager.NETWORK_TYPE_TD_SCDMA->"TD_SCDMA"
                    TelephonyManager.NETWORK_TYPE_IWLAN->"IWLAN"
                    TelephonyManager.NETWORK_TYPE_NR->"5G NR"
                    else->"Unknown"
                }
            }else"Unknown"
            dm["nt"]=ant
            val cl:List<CellInfo>?=tm.allCellInfo
            var fo=false
            cl?.forEach{ci->
                if(ci.isRegistered&&!fo){
                    fo=true
                    when(ci){
                        is CellInfoLte->{
                            dm["ci"]=ci.cellIdentity.ci;dm["tac"]=ci.cellIdentity.tac;dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength;if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=ss.dbm
                        }
                        is CellInfoWcdma->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac;dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength;if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=ss.dbm
                        }
                        is CellInfoGsm->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac;dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength;if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=ss.dbm
                        }
                        is CellInfoNr->{
                            val cin=ci.cellIdentity as?android.telephony.CellIdentityNr
                            dm["ci"]=cin?.nci?:"N/A";dm["tac"]=cin?.tac?:-1;dm["mcc"]=cin?.mccString?:"N/A";dm["mnc"]=cin?.mncString?:"N/A"
                            val ss=ci.cellSignalStrength as?android.telephony.CellSignalStrengthNr
                            if(ss!=null&&ss.ssRsrp!=Int.MIN_VALUE)dm["rssi"]=ss.ssRsrp
                        }
                    }
                }
            }
            if(!fo){dm["ci"]="N/A";dm["tac"]="N/A";dm["mcc"]="N/A";dm["mnc"]="N/A";dm["rssi"]="N/A"}
        }catch(e:SecurityException){dm["stat"]="No permission"}
        val wi=wm.connectionInfo
        val rs=wi.ssid
        val cs=when{rs==null||rs=="<unknown ssid>"||rs=="0x"||rs.isBlank()->0;rs.startsWith("\"")&&rs.endsWith("\"")->rs.substring(1,rs.length-1);else->rs}
        dm["ssid"]=cs
        val an=cm.activeNetwork
        val nc=cm.getNetworkCapabilities(an)
        if(nc!=null){
            val ld2=nc.linkDownstreamBandwidthKbps
            val lu2=nc.linkUpstreamBandwidthKbps
            val ldm=kotlin.math.ceil(ld2.toDouble()/1024.0).toInt()
            val lum=kotlin.math.ceil(lu2.toDouble()/1024.0).toInt()
            dm["dn"]=ldm;dm["up"]=lum
        }
        val gp=GsonBuilder().setPrettyPrinting().create()
        val js=gp.toJson(dm)
        ld=js
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString",js))
    }
    private fun gj(cf:String):Pair<String?,Boolean>{
        if(lu==null)return Pair(cf,false)
        try{
            val t=object:com.google.gson.reflect.TypeToken<Map<String,Any?>>(){}.type
            val p=g.fromJson<Map<String,Any?>>(lu,t)
            val c=g.fromJson<Map<String,Any?>>(cf,t)
            val d=mutableMapOf<String,Any?>()
            for((k,v)in c)if(!p.containsKey(k)||p[k]!=v)d[k]=v
            if(c.containsKey("id"))d["id"]=c["id"]
            if(d.keys==setOf("id"))return Pair(null,true)
            if(d.isEmpty())return Pair(null,true)
            return Pair(g.toJson(d),true)
        }catch(e:Exception){return Pair(cf,false)}
    }
    private fun u(js:String,of:String,id:Boolean){
        if(ip.isBlank()||port<=0){su("Error","Server IP or Port not set.",tb);return}
        val us="http://$ip:$port/api/telemetry"
        var uc:HttpURLConnection?=null
        try{
            val url=URL(us)
            uc=url.openConnection()as HttpURLConnection
            uc.requestMethod="POST"
            uc.setRequestProperty("Content-Type","application/json")
            uc.setRequestProperty("X-Data-Type",if(id)"delta"else"full")
            uc.doOutput=true
            uc.connectTimeout=10000
            uc.readTimeout=10000
            val jb=js.toByteArray(StandardCharsets.UTF_8)
            val d=Deflater(7,true)
            val co=ByteArrayOutputStream()
            DeflaterOutputStream(co,d).use{it.write(jb)}
            val cb=co.toByteArray()
            Log.d("HoarderService","${if(id)"Sending delta"else"Sending full"} JSON data: $js")
            uc.outputStream.write(cb)
            uc.outputStream.flush()
            val rc=uc.responseCode
            val rm=uc.responseMessage
            if(rc==HttpURLConnection.HTTP_OK){
                val r=uc.inputStream.bufferedReader().use{it.readText()}
                tb+=cb.size.toLong()
                sp.edit().putLong("totalUploadedBytes",tb).apply()
                lu=of
                su(if(id)"OK (Delta)"else"OK (Full)","Uploaded successfully.",tb)
                Log.d("HoarderService","Sent compressed packet size: ${cb.size} bytes")
            }else{
                val es=uc.errorStream
                val er=es?.bufferedReader()?.use{it.readText()}?:"No error response"
                su("HTTP Error","$rc: $rm. Server response: $er",tb)
            }
        }catch(e:Exception){su("Network Error","Failed to connect: ${e.message}",tb)}finally{uc?.disconnect()}
    }
    private fun su(s:String,m:String,ub:Long){
        val cm2=Pair(s,m)
        val ct=System.currentTimeMillis()
        var sf=true
        if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip)){
            if(ls=="Network Error"&&lm2?.first=="Network Error"&&lm2?.second?.contains(ip)==true&&ct-lt<cd)sf=false
        }
        val cc=(ls!=s||lm2!=cm2)
        if(sf&&cc){
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{putExtra("status",s);putExtra("message",m);putExtra("totalUploadedBytes",ub)})
            ls=s;lm2=cm2
            if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip))lt=ct
        }else if(!sf&&s=="Network Error"){
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{putExtra("totalUploadedBytes",ub)})
        }else if(ls==s&&lm2==cm2){
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{putExtra("totalUploadedBytes",ub)})
        }
    }
    companion object{
        const val CHANNEL_ID="HoarderServiceChannel"
        const val NOTIFICATION_ID=1
    }
}