// DataCollector.kt
package com.example.hoarder
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import com.google.gson.GsonBuilder
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt

class DataCollector(private val ctx:Context,private val h:Handler,private val callback:(String)->Unit){
    private lateinit var lm:LocationManager
    private lateinit var tm:TelephonyManager
    private lateinit var wm:WifiManager
    private lateinit var bm:BatteryManager
    private lateinit var cm:ConnectivityManager
    private lateinit var sm:SensorManager
    private var ll:Location?=null
    private var bd:Map<String,Any>?=null
    private var ca=false
    private var bv:Float?=null
    private val dr=object:Runnable{override fun run(){if(ca){c();h.postDelayed(this,1000L)}}}

    private val altitudeFilter = AltitudeKalmanFilter()
    private var lastGpsAltitude: Double = Double.NaN
    private var lastBarometerAltitude: Double = Double.NaN
    private var lastGpsAccuracy: Float = 10f

    private val ll2=object:LocationListener{
        override fun onLocationChanged(l:Location){
            ll=l
            lastGpsAltitude = l.altitude
            lastGpsAccuracy = l.accuracy
        }
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
                    if(cc>0&&cp>0){c2=(cc/1000*100)/cp;c2=(c2/100)*100}
                }
                bd=buildMap{put("perc",p.toInt());if(c2!=null)put("cap",c2)}
            }
        }
    }

    private val sel=object:SensorEventListener{
        override fun onSensorChanged(event:SensorEvent){
            if(event.sensor.type==Sensor.TYPE_PRESSURE){
                bv=event.values[0]
                // Convert pressure to altitude
                val pressureInHpa = event.values[0]
                val standardPressure = 1013.25f
                lastBarometerAltitude = 44330.0 * (1.0 - Math.pow((pressureInHpa / standardPressure).toDouble(), 0.1903))
            }
        }
        override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    }

    fun init(){
        lm=ctx.getSystemService(Context.LOCATION_SERVICE)as LocationManager
        tm=ctx.getSystemService(Context.TELEPHONY_SERVICE)as TelephonyManager
        wm=ctx.getSystemService(Context.WIFI_SERVICE)as WifiManager
        bm=ctx.getSystemService(Context.BATTERY_SERVICE)as BatteryManager
        cm=ctx.getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager
        sm=ctx.getSystemService(Context.SENSOR_SERVICE)as SensorManager

        ctx.registerReceiver(br,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,ll2)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0f,ll2)
        }catch(e:SecurityException){}

        val ps=sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if(ps!=null){
            sm.registerListener(sel,ps,SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun start(){h.removeCallbacks(dr);ca=true;h.post(dr)}
    fun stop(){ca=false;h.removeCallbacks(dr)}
    fun cleanup(){
        stop()
        try{
            lm.removeUpdates(ll2)
            ctx.unregisterReceiver(br)
            sm.unregisterListener(sel)
            altitudeFilter.reset() // Reset the Kalman filter
        }catch(e:Exception){}
    }

    private fun c(){
        if(!ca)return
        val dm=mutableMapOf<String,Any>()
        dm["id"]=Settings.Secure.getString(ctx.contentResolver,Settings.Secure.ANDROID_ID).take(4)
        dm["n"]=Build.MODEL

        val sp=ctx.getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        val bp=sp.getInt("batteryPrecision",5)
        bd?.let{
            it["perc"]?.let{v->when(v){is Int->{dm["perc"]=if(bp==-1)DataUtils.smartBattery(v)else DataUtils.rb(v,bp)};else->dm["perc"]=v}}
            it["cap"]?.let{v->dm["cap"]=v}
        }

        ll?.let{
            val gp=sp.getInt("gpsPrecision",-1)
            val spdp=sp.getInt("speedPrecision",-1)
            val altP=sp.getInt("gpsAltitudePrecision",-1)

            // Use the Kalman filter for altitude
            val filteredAltitude = altitudeFilter.update(lastGpsAltitude, lastBarometerAltitude, lastGpsAccuracy)
            val altitudeValue = when (altP) {
                0 -> filteredAltitude.toInt() // Maximum precision - actual Kalman filter value
                -1 -> altitudeFilter.applySmartRounding(filteredAltitude, -1) // Smart precision with our defined tiers
                else -> (Math.floor(it.altitude/altP)*altP).toInt() // Fixed precision (existing logic)
            }

            dm["alt"] = altitudeValue

            val sk=(it.speed*3.6).roundToInt()
            val rs=DataUtils.rsp(sk,spdp)
            val(prec,acc2)=if(gp==-1)DataUtils.smartGPSPrecision(it.speed)else Pair(gp,gp)
            val(rl,rlo,ac)=when(prec){
                0->{Triple(String.format(Locale.US,"%.6f",it.latitude).toDouble(),
                    String.format(Locale.US,"%.6f",it.longitude).toDouble(),
                    (it.accuracy/1).roundToInt()*1)}
                20->{Triple((it.latitude*10000).roundToInt()/10000.0,
                    (it.longitude*10000).roundToInt()/10000.0,
                    maxOf(20,(it.accuracy/20).roundToInt()*20))}
                100->{Triple((it.latitude*1000).roundToInt()/1000.0,
                    (it.longitude*1000).roundToInt()/1000.0,
                    maxOf(100,(it.accuracy/100).roundToInt()*100))}
                1000->{Triple((it.latitude*100).roundToInt()/100.0,
                    (it.longitude*100).roundToInt()/100.0,
                    maxOf(1000,(it.accuracy/1000).roundToInt()*1000))}
                10000->{Triple((it.latitude*10).roundToInt()/10.0,
                    (it.longitude*10).roundToInt()/10.0,
                    maxOf(10000,(it.accuracy/10000).roundToInt()*10000))}
                else->{Triple(String.format(Locale.US,"%.6f",it.latitude).toDouble(),
                    String.format(Locale.US,"%.6f",it.longitude).toDouble(),
                    (it.accuracy/1).roundToInt()*1)}
            }
            dm["lat"]=rl;dm["lon"]=rlo;dm["acc"]=ac;dm["spd"]=rs
        }

        // Add barometer data if available
        bv?.let{
            // Get the barometer precision setting
            val bp = sp.getInt("barometerPrecision", -1)

            if(bp == 0) {
                // Maximum precision: show actual pressure in hPa
                dm["bar"] = it  // Keep the original pressure value in hPa
            } else {
                // Convert pressure to altitude for other precision settings
                val pressureInHpa = it
                val standardPressure = 1013.25f
                val altitudeInMeters = 44330.0 * (1.0 - Math.pow((pressureInHpa / standardPressure).toDouble(), 0.1903))
                val barometerValue = altitudeInMeters.toInt()

                // Apply altitude-based precision rules
                dm["bar"] = if(bp == -1) {
                    // Smart precision
                    if(barometerValue < -10) barometerValue else kotlin.math.max(0, (floor(barometerValue/5.0)*5).toInt())
                } else {
                    // Fixed precision
                    if(barometerValue < -10) {
                        barometerValue  // Show exact value for values below -10
                    } else {
                        kotlin.math.max(0, (floor(barometerValue/bp.toDouble())*bp).toInt())  // Round and ensure minimum 0
                    }
                }
            }
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
            val rp=sp.getInt("rssiPrecision",-1)
            cl?.forEach{ci->
                if(ci.isRegistered&&!fo){
                    fo=true
                    when(ci){
                        is CellInfoLte->{
                            dm["ci"]=ci.cellIdentity.ci;dm["tac"]=ci.cellIdentity.tac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                        }
                        is CellInfoWcdma->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                        }
                        is CellInfoGsm->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE)dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                        }
                        is CellInfoNr->{
                            val cin=ci.cellIdentity as?android.telephony.CellIdentityNr
                            dm["ci"]=cin?.nci?:"N/A";dm["tac"]=cin?.tac?:-1
                            dm["mcc"]=cin?.mccString?:"N/A";dm["mnc"]=cin?.mncString?:"N/A"
                            val ss=ci.cellSignalStrength as?android.telephony.CellSignalStrengthNr
                            if(ss!=null&&ss.ssRsrp!=Int.MIN_VALUE)
                                dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.ssRsrp)else DataUtils.rs(ss.ssRsrp,rp)
                        }
                    }
                }
            }
            if(!fo){dm["ci"]="N/A";dm["tac"]="N/A";dm["mcc"]="N/A";dm["mnc"]="N/A";dm["rssi"]="N/A"}
        }catch(e:SecurityException){}

        val wi=wm.connectionInfo
        val rs=wi.ssid
        dm["ssid"]=when{
            rs==null||rs=="<unknown ssid>"||rs=="0x"||rs.isEmpty()->0
            rs.startsWith("\"")&&rs.endsWith("\"")->rs.substring(1,rs.length-1)
            else->rs
        }

        val an = cm.activeNetwork
        val nc = cm.getNetworkCapabilities(an)
        if(nc != null) {
            val np = sp.getInt("networkPrecision", 0)

            // Handle the network speed based on the precision mode
            if(np == -2) {
                // Float precision mode - use raw values for more accurate decimal representation
                dm["dn"] = DataUtils.rn(nc.linkDownstreamBandwidthKbps, np)
                dm["up"] = DataUtils.rn(nc.linkUpstreamBandwidthKbps, np)
            } else {
                // Integer modes - convert to Mbps and round as integers
                val ldm = kotlin.math.ceil(nc.linkDownstreamBandwidthKbps.toDouble()/1024.0).toInt()
                val lum = kotlin.math.ceil(nc.linkUpstreamBandwidthKbps.toDouble()/1024.0).toInt()
                dm["dn"] = DataUtils.rn(nc.linkDownstreamBandwidthKbps, np)
                dm["up"] = DataUtils.rn(nc.linkUpstreamBandwidthKbps, np)
            }
        }

        callback(GsonBuilder().setPrettyPrinting().create().toJson(dm))
    }
}