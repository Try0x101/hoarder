package com.example.hoarder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.*
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import com.google.gson.GsonBuilder
import java.util.*
import kotlin.math.roundToInt

class DataCollector(
    private val ctx:Context,
    private val h:Handler,
    private val callback:(String)->Unit
){
    private lateinit var lm:LocationManager
    private lateinit var tm:TelephonyManager
    private lateinit var wm:WifiManager
    private lateinit var bm:BatteryManager
    private lateinit var cm:ConnectivityManager
    private var ll:Location?=null
    private var bd:Map<String,Any>?=null
    private var ca=false
    private val dr=object:Runnable{override fun run(){if(ca){c();h.postDelayed(this,1000L)}}}
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
                    if(cc>0&&cp>0){c2=(cc/1000*100)/cp;c2=(c2/100)*100}
                }
                bd=buildMap{put("perc",p.toInt());if(c2!=null)put("cap",c2)}
            }
        }
    }

    fun init(){
        lm=ctx.getSystemService(Context.LOCATION_SERVICE)as LocationManager
        tm=ctx.getSystemService(Context.TELEPHONY_SERVICE)as TelephonyManager
        wm=ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)as WifiManager
        bm=ctx.getSystemService(Context.BATTERY_SERVICE)as BatteryManager
        cm=ctx.getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager
        ctx.registerReceiver(br,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,ll2)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0f,ll2)
        }catch(e:SecurityException){}
    }

    fun start(){h.removeCallbacks(dr);ca=true;h.post(dr)}
    fun stop(){ca=false;h.removeCallbacks(dr)}

    fun cleanup(){
        stop()
        try{lm.removeUpdates(ll2);ctx.unregisterReceiver(br)}catch(e:Exception){}
    }

    private fun c(){
        if(!ca)return
        val dm=mutableMapOf<String,Any>()
        val di=Settings.Secure.getString(ctx.contentResolver,Settings.Secure.ANDROID_ID)
        dm["id"]=di.take(4)
        dm["n"]=Build.MODEL

        val sp=ctx.getSharedPreferences("HoarderPrefs",Context.MODE_PRIVATE)
        val bp=sp.getInt("batteryPrecision",5)

        bd?.let{
            it["perc"]?.let{v->
                when(v){
                    is Int->{dm["perc"]=if(bp==-1)DataUtils.smartBattery(v)else DataUtils.rb(v,bp)}
                    else->dm["perc"]=v
                }
            }
            it["cap"]?.let{v->dm["cap"]=v}
        }

        ll?.let{
            val gp=sp.getInt("gpsPrecision",100)
            val spdp=sp.getInt("speedPrecision",-1)
            val ra=(it.altitude/2).roundToInt()*2
            val sk=(it.speed*3.6).roundToInt()
            val rs=DataUtils.rsp(sk,spdp)
            val(prec,acc2)=if(gp==-1)DataUtils.smartGPSPrecision(it.speed)else Pair(gp,gp)
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
            val rp=sp.getInt("rssiPrecision",5)
            cl?.forEach{ci->
                if(ci.isRegistered&&!fo){
                    fo=true
                    when(ci){
                        is CellInfoLte->{
                            dm["ci"]=ci.cellIdentity.ci;dm["tac"]=ci.cellIdentity.tac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE){
                                dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                            }
                        }
                        is CellInfoWcdma->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE){
                                dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                            }
                        }
                        is CellInfoGsm->{
                            dm["ci"]=ci.cellIdentity.cid;dm["tac"]=ci.cellIdentity.lac
                            dm["mcc"]=ci.cellIdentity.mccString?:"N/A";dm["mnc"]=ci.cellIdentity.mncString?:"N/A"
                            val ss=ci.cellSignalStrength
                            if(ss.dbm!=Int.MAX_VALUE){
                                dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.dbm)else DataUtils.rs(ss.dbm,rp)
                            }
                        }
                        is CellInfoNr->{
                            val cin=ci.cellIdentity as?android.telephony.CellIdentityNr
                            dm["ci"]=cin?.nci?:"N/A";dm["tac"]=cin?.tac?:-1
                            dm["mcc"]=cin?.mccString?:"N/A";dm["mnc"]=cin?.mncString?:"N/A"
                            val ss=ci.cellSignalStrength as?android.telephony.CellSignalStrengthNr
                            if(ss!=null&&ss.ssRsrp!=Int.MIN_VALUE){
                                dm["rssi"]=if(rp==-1)DataUtils.smartRSSI(ss.ssRsrp)else DataUtils.rs(ss.ssRsrp,rp)
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
            val np=sp.getInt("networkPrecision",0)
            val ldm=kotlin.math.ceil(ld2.toDouble()/1024.0).toInt()
            val lum=kotlin.math.ceil(lu2.toDouble()/1024.0).toInt()
            dm["dn"]=DataUtils.rn(ldm,np);dm["up"]=DataUtils.rn(lum,np)
        }

        val js=GsonBuilder().setPrettyPrinting().create().toJson(dm)
        callback(js)
    }
}