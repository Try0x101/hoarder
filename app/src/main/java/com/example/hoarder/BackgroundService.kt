package com.example.hoarder
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.*

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
                var c2:Int?=null
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP){
                    val cc=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val cp=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if(cc>0&&cp>0){c2=(cc/1000*100)/cp}
                }
                bd=buildMap{put("perc",p.toInt());if(c2!=null)put("cap",c2)}
            }
        }
    }

    private val cr=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            when(i?.action){
                "com.example.hoarder.START_COLLECTION"->{if(!ca){ca=true;sd()}}
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
        val startedFromBoot = i?.getBooleanExtra("startedFromBoot", false) ?: false
        cn()
        if(startedFromBoot){h.postDelayed({initializeService()},3000)}else{initializeService()}
        return START_STICKY
    }

    private fun initializeService() {
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasForegroundLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission || !hasForegroundLocationPermission) {
            Log.e("HoarderService", "Missing required permissions. Cannot start foreground service.")
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf() // Stop the service gracefully instead of crashing
            return
        }

        val ni=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,0,ni,PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(applicationContext,"HoarderServiceChannel")
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            startForeground(1,n)
            sl()
        } catch (e: SecurityException) {
            Log.e("HoarderService", "Security exception: ${e.message}")
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf() // Stop the service if we can't start it in the foreground
            return
        }

        val mp=applicationContext.getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        val ct=mp.getBoolean("dataCollectionToggleState",true)
        val ut=mp.getBoolean("dataUploadToggleState",false)
        val sp2=mp.getString("serverIpPortAddress","")?.split(":")
        tb=sp.getLong("totalUploadedBytes",0L)
        if(sp2!=null&&sp2.size==2&&sp2[0].isNotBlank()&&sp2[1].toIntOrNull()!=null){ip=sp2[0];port=sp2[1].toInt()}else{ip="";port=0}
        if(ct){if(!ca){ca=true;sd()}}else{ca=false;h.removeCallbacks(dr)}
        if(ut&&ip.isNotBlank()&&port>0){ua=true;lu=null;ls=null;su("Connecting","Service (re)start, attempting to connect...",tb);su2()}else ua=false
    }

    override fun onDestroy(){
        super.onDestroy()
        h.removeCallbacks(dr)
        h.removeCallbacks(ur)
        lm.removeUpdates(ll2)
        unregisterReceiver(br)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cr)
        restartService()
    }

    private fun restartService() {
        val restartIntent = Intent(applicationContext, BackgroundService::class.java)
        try {
            Log.d("HoarderService", "Attempting to restart service after destruction")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (e: Exception) {
            Log.e("HoarderService", "Failed to restart service: ${e.message}")
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, restartServicePendingIntent)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("HoarderService", "Task removed, scheduling service restart")
        val restartServiceIntent = Intent(applicationContext, BackgroundService::class.java)
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, restartServicePendingIntent)
    }

    override fun onBind(i:Intent?):IBinder?=null

    private fun cn(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            val sc=NotificationChannel("HoarderServiceChannel", "Hoarder Service Channel", NotificationManager.IMPORTANCE_MIN)
                .apply {setShowBadge(false);enableLights(false);enableVibration(false);lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET}
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
    private fun rs(rv:Int,rp:Int):Int=when(rp){0->rv;else->(rv/rp)*rp}
    private fun smartRSSI(v:Int):Int=when{v<-110->v;v<-90->(v/5)*5;else->(v/10)*10}
    private fun smartBattery(p:Int):Int=when{p<10->p;p<50->(p/5)*5;else->(p/10)*10}
    private fun rb(p:Int,pr:Int):Int{if(pr==0)return p;if(p<10&&pr>1)return p;return(p/pr)*pr}
    private fun rn(v:Int,pr:Int):Int{if(pr==0)return if(v<7)v else(v/5)*5;return(v/pr)*pr}
    private fun rsp(s:Int,pr:Int):Int{
        if(pr==-1)return when{s<2->0;s<10->(s/3)*3;else->(s/10)*10}
        if(pr==0)return s
        return(s/pr)*pr
    }
    private fun smartGPSPrecision(s:Float):Pair<Int,Int>{
        val sk=(s*3.6).toInt()
        return when{sk<4->Pair(1000,1000);sk<40->Pair(20,20);sk<140->Pair(100,100);else->Pair(1000,1000)}
    }

    private fun c(){
        if(!ca)return
        val dm=mutableMapOf<String,Any>()
        val di=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)
        dm["id"]=di.take(4)
        dm["n"]=Build.MODEL

        val mp=applicationContext.getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        val batteryPrecision = mp.getInt("batteryPrecision", 5)

        bd?.let{
            it["perc"]?.let{v->
                when(v){
                    is Int-> {dm["perc"] = if(batteryPrecision == -1) smartBattery(v) else rb(v, batteryPrecision)}
                    else->dm["perc"]=v
                }
            }
            it["cap"]?.let{v->dm["cap"]=v}
        }

        ll?.let{
            val gpsPrecision = mp.getInt("gpsPrecision", 100)
            val speedPrecision = mp.getInt("speedPrecision", -1)
            val ra = (it.altitude/2).roundToInt()*2
            val sk = (it.speed*3.6).roundToInt()
            val rs = rsp(sk, speedPrecision)
            val (prec, acc2) = if(gpsPrecision == -1) smartGPSPrecision(it.speed) else Pair(gpsPrecision, gpsPrecision)
            val(rl,rlo,ac)=when(prec){
                0->{
                    val lt=String.format(Locale.US,"%.6f",it.latitude).toDouble()
                    val ln=String.format(Locale.US,"%.6f",it.longitude).toDouble()
                    Triple(lt,ln,(it.accuracy/1).roundToInt()*1)
                }
                20->{
                    val lt=(it.latitude*10000).roundToInt()/10000.0
                    val ln=(it.longitude*10000).roundToInt()/10000.0
                    Triple(lt,ln,maxOf(20,(it.accuracy/20).roundToInt()*20))
                }
                100->{
                    val lt=(it.latitude*1000).roundToInt()/1000.0
                    val ln=(it.longitude*1000).roundToInt()/1000.0
                    Triple(lt,ln,maxOf(100,(it.accuracy/100).roundToInt()*100))
                }
                1000->{
                    val lt=(it.latitude*100).roundToInt()/100.0
                    val ln=(it.longitude*100).roundToInt()/100.0
                    Triple(lt,ln,maxOf(1000,(it.accuracy/1000).roundToInt()*1000))
                }
                10000->{
                    val lt=(it.latitude*10).roundToInt()/10.0
                    val ln=(it.longitude*10).roundToInt()/10.0
                    Triple(lt,ln,maxOf(10000,(it.accuracy/10000).roundToInt()*10000))
                }
                else->{
                    val lt=String.format(Locale.US,"%.6f",it.latitude).toDouble()
                    val ln=String.format(Locale.US,"%.6f",it.longitude).toDouble()
                    Triple(lt,ln,(it.accuracy/1).roundToInt()*1)
                }
            }
            dm["lat"]=rl;dm["lon"]=rlo;dm["alt"]=ra;dm["acc"]=ac;dm["spd"]=rs
        }

        try{
            dm["op"]=tm.networkOperatorName
            val ant=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
                when(tm.dataNetworkType){
                    TelephonyManager.NETWORK_TYPE_GPRS->"GPRS";TelephonyManager.NETWORK_TYPE_EDGE->"EDGE"
                    TelephonyManager.NETWORK_TYPE_UMTS->"UMTS";TelephonyManager.NETWORK_TYPE_CDMA->"CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0->"EVDO_0";TelephonyManager.NETWORK_TYPE_EVDO_A->"EVDO_A"
                    TelephonyManager.NETWORK_TYPE_1xRTT->"1xRTT";TelephonyManager.NETWORK_TYPE_HSDPA->"HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSUPA->"HSUPA";TelephonyManager.NETWORK_TYPE_HSPA->"HSPA"
                    TelephonyManager.NETWORK_TYPE_IDEN->"IDEN";TelephonyManager.NETWORK_TYPE_EVDO_B->"EVDO_B"
                    TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_EHRPD->"EHRPD"
                    TelephonyManager.NETWORK_TYPE_HSPAP->"HSPAP";TelephonyManager.NETWORK_TYPE_GSM->"GSM"
                    TelephonyManager.NETWORK_TYPE_TD_SCDMA->"TD_SCDMA";TelephonyManager.NETWORK_TYPE_IWLAN->"IWLAN"
                    TelephonyManager.NETWORK_TYPE_NR->"5G NR";else->"Unknown"
                }
            }else"Unknown"
            dm["nt"]=ant

            val cl:List<CellInfo>?=tm.allCellInfo
            var fo=false
            val rssiPrecision = mp.getInt("rssiPrecision", 5)
            cl?.forEach{ci->
                if(ci.isRegistered&&!fo){
                    fo=true
                    when(ci){
                        is CellInfoLte->{
                            dm["ci"]=ci.cellIdentity.ci;dm["tac"]=ci.cellIdentity.tac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE) {
                                dm["rssi"] = if(rssiPrecision == -1) smartRSSI(ss.dbm) else rs(ss.dbm, rssiPrecision)
                            }
                        }
                        is CellInfoWcdma->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE) {
                                dm["rssi"] = if(rssiPrecision == -1) smartRSSI(ss.dbm) else rs(ss.dbm, rssiPrecision)
                            }
                        }
                        is CellInfoGsm->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE) {
                                dm["rssi"] = if(rssiPrecision == -1) smartRSSI(ss.dbm) else rs(ss.dbm, rssiPrecision)
                            }
                        }
                        is CellInfoNr->{
                            val cin=ci.cellIdentity as?android.telephony.CellIdentityNr
                            dm["ci"]=cin?.nci?:"N/A";dm["tac"]=cin?.tac?:-1
                            dm["mcc"]=cin?.mccString?:"N/A";dm["mnc"]=cin?.mncString?:"N/A"
                            val ss=ci.cellSignalStrength as?android.telephony.CellSignalStrengthNr
                            if(ss!=null&&ss.ssRsrp!=Int.MIN_VALUE) {
                                dm["rssi"] = if(rssiPrecision == -1) smartRSSI(ss.ssRsrp) else rs(ss.ssRsrp, rssiPrecision)
                            }
                        }
                    }
                }
            }
            if(!fo){dm["ci"]="N/A";dm["tac"]="N/A";dm["mcc"]="N/A";dm["mnc"]="N/A";dm["rssi"]="N/A"}
        }catch(e:SecurityException){}

        val wi=wm.connectionInfo
        val rs=wi.ssid
        val cs=when{rs==null||rs=="<unknown ssid>"||rs=="0x"||rs.isBlank()->0;rs.startsWith("\"")&&rs.endsWith("\"")->rs.substring(1,rs.length-1);else->rs}
        dm["ssid"]=cs

        val an=cm.activeNetwork
        val nc=cm.getNetworkCapabilities(an)
        if(nc!=null){
            val ld2=nc.linkDownstreamBandwidthKbps
            val lu2=nc.linkUpstreamBandwidthKbps
            val np=mp.getInt("networkPrecision",0)
            val ldm=kotlin.math.ceil(ld2.toDouble()/1024.0).toInt()
            val lum=kotlin.math.ceil(lu2.toDouble()/1024.0).toInt()
            dm["dn"]=rn(ldm,np);dm["up"]=rn(lum,np)
        }

        val js=GsonBuilder().setPrettyPrinting().create().toJson(dm)
        ld=js
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString",js))
    }

    private fun gj(cf:String):Pair<String?,Boolean>{
        if(lu==null)return Pair(cf,false)
        try{
            val t = object : TypeToken<Map<String, Any?>>() {}.type
            val p = g.fromJson(lu, t) as Map<String, Any?>
            val c = g.fromJson(cf, t) as Map<String, Any?>
            val d = mutableMapOf<String, Any?>()
            for((k,v) in c.entries) if (!p.containsKey(k) || p[k] != v) d[k]=v
            if (c.containsKey("id")) d["id"] = c["id"]
            if (d.keys == setOf("id") || d.isEmpty()) return Pair(null,true)
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
            uc.outputStream.write(cb)
            uc.outputStream.flush()
            val rc=uc.responseCode
            if(rc==HttpURLConnection.HTTP_OK){
                tb+=cb.size.toLong()
                sp.edit().putLong("totalUploadedBytes",tb).apply()
                lu=of
                su(if(id)"OK (Delta)"else"OK (Full)","Uploaded successfully.",tb)
            }else{
                val er=uc.errorStream?.bufferedReader()?.use{it.readText()}?:"No error response"
                su("HTTP Error","$rc: ${uc.responseMessage}. Server response: $er",tb)
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
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("status",s);putExtra("message",m);putExtra("totalUploadedBytes",ub)
            })
            ls=s;lm2=cm2
            if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip))lt=ct
        }else if(!sf&&s=="Network Error"||ls==s&&lm2==cm2){
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("totalUploadedBytes",ub)
            })
        }
    }
}