package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class DataUploader(
    private val ctx: Context,
    private val h: Handler,
    private val sp: SharedPreferences
){
    private var ua=false
    private var ip=""
    private var port=5000
    private var ld:String?=null
    private var lu:String?=null
    private val g= GsonBuilder().create()
    private var tb=sp.getLong("totalUploadedBytes",0L)
    private var ls:String?=null
    private var lm2:Pair<String,String>?=null
    private var lt=0L
    private val cd=5000L
    private val ur=object:Runnable{
        override fun run(){
            if(ua&&ld!=null&&ip.isNotBlank()&&port>0){
                Thread{ld?.let{val(d,delta)=gj(it);if(d!=null)u(d,it,delta)else notifyStatus("No Change","Data unchanged, skipping upload.",tb)}}.start()
                if(ua)h.postDelayed(this,1000L)
            }else if(ua&&(ip.isBlank()||port<=0)){notifyStatus("Error","Server IP or Port became invalid.",tb);ua=false}
        }
    }

    fun setServer(ip:String,port:Int){this.ip=ip;this.port=port}
    fun hasValidServer()=ip.isNotBlank()&&port>0
    fun queueData(data:String){ld=data}

    fun start(){
        h.removeCallbacks(ur)
        lu = null // Reset last uploaded data to force full upload
        ua=true
        notifyStatus("Connecting","Attempting to connect...",tb)
        h.post(ur)
    }

    fun stop(){
        ua=false
        h.removeCallbacks(ur)
    }

    fun resetCounter() {
        tb = 0L
        lu = null // Reset last uploaded data for next upload
        sp.edit().putLong("totalUploadedBytes", 0L).apply()
        notifyStatus("Paused", "Upload paused.", tb)
    }

    fun getUploadedBytes(): Long {
        return tb
    }

    fun notifyStatus(s:String,m:String,ub:Long){
        val cm2=Pair(s,m)
        val ct=System.currentTimeMillis()
        var sf=true
        if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip)){
            if(ls=="Network Error"&&lm2?.first=="Network Error"&&lm2?.second?.contains(ip)==true&&ct-lt<cd)sf=false
        }
        val cc=(ls!=s||lm2!=cm2)
        if(sf&&cc){
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("status",s);putExtra("message",m);putExtra("totalUploadedBytes",ub)
            })
            ls=s;lm2=cm2
            if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip))lt=ct
        }else if(!sf&&s=="Network Error"||ls==s&&lm2==cm2){
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("totalUploadedBytes",ub)
            })
        }
    }

    private fun gj(cf:String):Pair<String?,Boolean>{
        if(lu==null)return Pair(cf,false)
        try{
            val t=object: TypeToken<Map<String, Any?>>(){}.type
            val p=g.fromJson(lu,t)as Map<String,Any?>
            val c=g.fromJson(cf,t)as Map<String,Any?>
            val d=mutableMapOf<String,Any?>()
            for((k,v)in c.entries)if(!p.containsKey(k)||p[k]!=v)d[k]=v
            if(c.containsKey("id"))d["id"]=c["id"]
            if(d.keys==setOf("id")||d.isEmpty())return Pair(null,true)
            return Pair(g.toJson(d),true)
        }catch(e:Exception){return Pair(cf,false)}
    }

    private fun u(js:String,of:String,id:Boolean){
        if(ip.isBlank()||port<=0){notifyStatus("Error","Server IP or Port not set.",tb);return}
        val us="http://$ip:$port/api/telemetry"
        var uc: HttpURLConnection?=null
        try{
            val url= URL(us)
            uc=url.openConnection()as HttpURLConnection
            uc.requestMethod="POST"
            uc.setRequestProperty("Content-Type","application/json")
            uc.setRequestProperty("X-Data-Type",if(id)"delta"else"full")
            uc.doOutput=true
            uc.connectTimeout=10000
            uc.readTimeout=10000
            val jb=js.toByteArray(StandardCharsets.UTF_8)
            val d= Deflater(7, true)
            val co= ByteArrayOutputStream()
            DeflaterOutputStream(co, d).use{it.write(jb)}
            val cb=co.toByteArray()
            uc.outputStream.write(cb)
            uc.outputStream.flush()
            val rc=uc.responseCode
            if(rc== HttpURLConnection.HTTP_OK){
                tb+=cb.size.toLong()
                sp.edit().putLong("totalUploadedBytes",tb).apply()
                lu=of
                notifyStatus(if(id)"OK (Delta)"else"OK (Full)","Uploaded successfully.",tb)
            }else{
                val er=uc.errorStream?.bufferedReader()?.use{it.readText()}?:"No error response"
                notifyStatus("HTTP Error","$rc: ${uc.responseMessage}. Server response: $er",tb)
            }
        }catch(e:Exception){notifyStatus("Network Error","Failed to connect: ${e.message}",tb)}finally{uc?.disconnect()}
    }
}