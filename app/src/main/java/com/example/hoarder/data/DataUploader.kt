package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.utils.NetUtils
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private val g = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private var tb=sp.getLong("totalUploadedBytes",0L)
    private var ls:String?=null
    private var lm2:Pair<String,String>?=null
    private var lt=0L
    private val cd=5000L
    private val ur=object:Runnable{
        override fun run(){
            if(ua&&ip.isNotBlank()&&port>0){
                Thread{
                    ld?.let { dataToProcess ->
                        val (d, delta) = gj(dataToProcess)
                        if (d != null) {
                            lu = dataToProcess
                            if (NetUtils.isNetworkAvailable(ctx)) {
                                u(d, delta)
                            } else {
                                saveToBuffer(d)
                                notifyStatus("Saving Locally", "Internet not accessible. Delta saved locally.", tb, getBufferedDataSize())
                            }
                        } else {
                            notifyStatus("No Change", "Data unchanged, skipping upload.", tb, getBufferedDataSize())
                        }
                        ld = null
                    }
                }.start()
                if(ua)h.postDelayed(this,1000L)
            }else if(ua&&(ip.isBlank()||port<=0)){notifyStatus("Error","Server IP or Port became invalid.",tb, getBufferedDataSize());ua=false}
        }
    }

    fun setServer(ip:String,port:Int){this.ip=ip;this.port=port}
    fun hasValidServer()=ip.isNotBlank()&&port>0
    fun queueData(data:String){ld=data}

    fun start(){
        h.removeCallbacks(ur)
        lu = null
        ua=true
        notifyStatus("Connecting","Attempting to connect...",tb, getBufferedDataSize())
        h.post(ur)
    }

    fun stop(){
        ua=false
        h.removeCallbacks(ur)
    }

    fun resetCounter() {
        tb = 0L
        lu = null
        sp.edit().putLong("totalUploadedBytes", 0L).apply()
        notifyStatus("Paused", "Upload paused.", tb, getBufferedDataSize())
    }

    fun getUploadedBytes(): Long {
        return tb
    }

    fun forceSendBuffer() {
        if (ua && ip.isNotBlank() && port > 0) {
            Thread {
                val bufferedData = getBufferedData()
                if (bufferedData.isNotEmpty()) {
                    uBatch(bufferedData)
                }
            }.start()
        }
    }

    fun notifyStatus(s:String,m:String,ub:Long, bufferSize: Long, lastUploadSize: Long? = null){
        val cm2=Pair(s,m)
        val ct=System.currentTimeMillis()
        var sf=true
        if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip)){
            if(ls=="Network Error"&&lm2?.first=="Network Error"&&lm2?.second?.contains(ip)==true&&ct-lt<cd)sf=false
        }
        val cc=(ls!=s||lm2!=cm2)
        if(sf&&cc){
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("status",s)
                putExtra("message",m)
                putExtra("totalUploadedBytes",ub)
                putExtra("bufferedDataSize", bufferSize)
                lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
            })
            ls=s;lm2=cm2
            if(s=="Network Error"&&ip.isNotBlank()&&m.contains(ip))lt=ct
        }else if(!sf&&s=="Network Error"||ls==s&&lm2==cm2){
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply{
                putExtra("totalUploadedBytes",ub)
                putExtra("bufferedDataSize", bufferSize)
                lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
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

    private fun u(js:String,id:Boolean){
        if (!NetUtils.isNetworkAvailable(ctx)) {
            val errorMessage = "Internet not accessible"
            addErrorLog(errorMessage)
            saveToBuffer(js)
            notifyStatus("Saving Locally", errorMessage, tb, getBufferedDataSize())
            return
        }

        if(ip.isBlank()||port<=0){notifyStatus("Error","Server IP or Port not set.",tb, getBufferedDataSize());return}
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
                val uploadedBytes = cb.size.toLong()
                tb+=uploadedBytes
                sp.edit().putLong("totalUploadedBytes",tb).apply()
                addUploadRecord(uploadedBytes)
                addSuccessLog(js, uploadedBytes)
                notifyStatus(if(id)"OK (Delta)"else"OK (Full)","Uploaded successfully.",tb, getBufferedDataSize(), uploadedBytes)
            }else{
                val er=uc.errorStream?.bufferedReader()?.use{it.readText()}?:"No error response"
                val errorMessage = "$rc: ${uc.responseMessage}. Server response: $er"
                addErrorLog(errorMessage)
                saveToBuffer(js)
                notifyStatus("HTTP Error",errorMessage,tb, getBufferedDataSize())
            }
        }catch(e:Exception){
            val errorMessage = "Failed to connect: ${e.message}"
            addErrorLog(errorMessage)
            saveToBuffer(js)
            notifyStatus("Network Error",errorMessage,tb, getBufferedDataSize())
        }finally{uc?.disconnect()}
    }

    private fun uBatch(batch: List<String>) {
        if (ip.isBlank() || port <= 0) {
            notifyStatus("Error", "Server IP or Port not set.", tb, getBufferedDataSize())
            return
        }
        val us = "http://$ip:$port/api/batch-delta"
        var uc: HttpURLConnection? = null
        try {
            val url = URL(us)
            uc = url.openConnection() as HttpURLConnection
            uc.requestMethod = "POST"
            uc.setRequestProperty("Content-Type", "application/json")
            uc.doOutput = true
            uc.connectTimeout = 30000
            uc.readTimeout = 30000

            val jsonBatch = "[" + batch.joinToString(",") + "]"
            val requestBody = jsonBatch.toByteArray(StandardCharsets.UTF_8)

            uc.outputStream.write(requestBody)
            uc.outputStream.flush()

            val rc = uc.responseCode
            if (rc == HttpURLConnection.HTTP_OK) {
                val uploadedBytes = requestBody.size.toLong()
                tb += uploadedBytes
                sp.edit().putLong("totalUploadedBytes", tb).apply()
                addUploadRecord(uploadedBytes)
                addSuccessLog("Batch upload of ${batch.size} records", uploadedBytes)
                saveLastUploadDetails(batch)
                clearBuffer(batch)
                notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb, getBufferedDataSize(), uploadedBytes)
            } else {
                val er = uc.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error response"
                val errorMessage = "$rc: ${uc.responseMessage}. Server response: $er"
                addErrorLog(errorMessage)
                notifyStatus("HTTP Error", errorMessage, tb, getBufferedDataSize())
            }
        } catch (e: Exception) {
            val errorMessage = "Failed to connect: ${e.message}"
            addErrorLog(errorMessage)
            notifyStatus("Network Error", errorMessage, tb, getBufferedDataSize())
        } finally {
            uc?.disconnect()
        }
    }

    private fun saveToBuffer(jsonString: String) {
        val buffer = getBufferedData().toMutableSet()
        try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap = g.fromJson<MutableMap<String, Any>>(jsonString, type)

            val currentTimeMillis = System.currentTimeMillis()
            val secondsInMinute = (currentTimeMillis / 1000L) % 60L

            dataMap["ts"] = secondsInMinute

            val modifiedJsonString = g.toJson(dataMap)
            buffer.add(modifiedJsonString)

            sp.edit()
                .putStringSet("data_buffer", buffer)
                .putLong("buffer_entry_${modifiedJsonString.hashCode()}", currentTimeMillis)
                .apply()
            cleanupOldBufferData()
        } catch (e: Exception) {
            // Fallback for malformed json
        }
    }

    private fun getBufferedData(): List<String> {
        cleanupOldBufferData()
        return sp.getStringSet("data_buffer", emptySet())?.toList() ?: emptyList()
    }

    private fun clearBuffer(sentData: List<String>) {
        val buffer = getBufferedData().toMutableSet()
        buffer.removeAll(sentData)
        sp.edit().putStringSet("data_buffer", buffer).apply()
    }

    fun getBufferedDataSize(): Long {
        return getBufferedData().sumOf { it.toByteArray(StandardCharsets.UTF_8).size }.toLong()
    }

    private fun cleanupOldBufferData() {
        val currentTimeMillis = System.currentTimeMillis()
        val sevenDaysAgoMillis = currentTimeMillis - 7 * 24 * 60 * 60 * 1000L
        val sevenDaysAgoMinute = sevenDaysAgoMillis / 60000L

        val buffer = sp.getStringSet("data_buffer", emptySet())?.toMutableSet() ?: return
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val toRemove = buffer.filter {
            try {
                val data = g.fromJson<Map<String, Any>>(it, type)
                val tsInMinute = (data["ts"] as? Number)?.toLong() ?: return@filter false

                val entryCreationTime = sp.getLong("buffer_entry_${it.hashCode()}", currentTimeMillis)
                val entryMinute = entryCreationTime / 60000L

                entryMinute < sevenDaysAgoMinute
            } catch (e: Exception) {
                true
            }
        }
        if (toRemove.isNotEmpty()) {
            buffer.removeAll(toRemove)
            toRemove.forEach {
                sp.edit().remove("buffer_entry_${it.hashCode()}").apply()
            }
            sp.edit().putStringSet("data_buffer", buffer).apply()
        }
    }

    private fun saveLastUploadDetails(jsonData: List<String>) {
        try {
            val file = File(ctx.cacheDir, "last_upload_details.json")
            file.writeText(g.toJson(jsonData))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun addUploadRecord(bytes: Long) {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L
        val newRecord = "$now:$bytes"

        val records = sp.getStringSet("uploadRecords", mutableSetOf()) ?: mutableSetOf()

        val updatedRecords = records.filter { record ->
            val timestamp = record.split(":").firstOrNull()?.toLongOrNull()
            timestamp != null && timestamp >= sevenDaysAgo
        }.toMutableSet()

        updatedRecords.add(newRecord)

        sp.edit().putStringSet("uploadRecords", updatedRecords).apply()
    }

    private fun addErrorLog(message: String) {
        val logs = sp.getStringSet("error_logs", mutableSetOf())?.toMutableList() ?: mutableListOf()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "$timestamp|$message")
        while (logs.size > 10) {
            logs.removeAt(logs.size - 1)
        }
        sp.edit().putStringSet("error_logs", logs.toSet()).apply()
    }

    private fun addSuccessLog(json: String, size: Long) {
        val logs = sp.getStringSet("success_logs", mutableSetOf())?.toMutableList() ?: mutableListOf()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "$timestamp|$size|$json")
        while (logs.size > 10) {
            logs.removeAt(logs.size - 1)
        }
        sp.edit().putStringSet("success_logs", logs.toSet()).apply()
    }
}