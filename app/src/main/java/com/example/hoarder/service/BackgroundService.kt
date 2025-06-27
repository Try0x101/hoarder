package com.example.hoarder.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.ui.MainActivity
import com.example.hoarder.R
import com.example.hoarder.data.DataUploader
import com.example.hoarder.sensors.DataCollector

class BackgroundService: Service(){
    private lateinit var h: Handler
    private lateinit var dataCollector: DataCollector
    private lateinit var dataUploader: DataUploader
    private var ca=false
    private var ua=false

    private val cr=object: BroadcastReceiver(){
        override fun onReceive(c: Context?, i: Intent?){
            when(i?.action){
                "com.example.hoarder.START_COLLECTION"->
                    if(!ca){ca=true;dataCollector.start()}
                "com.example.hoarder.STOP_COLLECTION"->
                    if(ca){ca=false;dataCollector.stop();
                        LocalBroadcastManager.getInstance(applicationContext)
                            .sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString",""))}
                "com.example.hoarder.START_UPLOAD"->{
                    val ip=i?.getStringExtra("ipPort")?.split(":")
                    if(ip!=null&&ip.size==2&&ip[0].isNotBlank()&&ip[1].toIntOrNull()!=null
                        &&ip[1].toInt()>0&&ip[1].toInt()<=65535){
                        dataUploader.resetCounter()
                        dataUploader.setServer(ip[0],ip[1].toInt())
                        ua=true
                        dataUploader.start()
                    }else{ua=false;dataUploader.notifyStatus("Error","Invalid Server IP:Port for starting upload.",0L)}
                }
                "com.example.hoarder.STOP_UPLOAD"->
                    if(ua){ua=false;dataUploader.stop();dataUploader.resetCounter()}
            }
        }
    }

    override fun onCreate(){
        super.onCreate()
        h= Handler(Looper.getMainLooper())
        val p=getSharedPreferences("HoarderServicePrefs", MODE_PRIVATE)

        dataCollector= DataCollector(this, h) { json ->
            LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(
                    Intent("com.example.hoarder.DATA_UPDATE").putExtra(
                        "jsonString",
                        json
                    )
                )
            if (ua) dataUploader.queueData(json)
        }

        dataUploader= DataUploader(this, h, p)

        LocalBroadcastManager.getInstance(this).registerReceiver(cr, IntentFilter().apply{
            addAction("com.example.hoarder.START_COLLECTION")
            addAction("com.example.hoarder.STOP_COLLECTION")
            addAction("com.example.hoarder.START_UPLOAD")
            addAction("com.example.hoarder.STOP_UPLOAD")
        })
    }

    override fun onStartCommand(i: Intent?, f:Int, s:Int):Int{
        createNotificationChannel()
        initService()
        return START_STICKY
    }

    private fun initService(){
        val lbm= LocalBroadcastManager.getInstance(this)
        if(!hasRequiredPermissions()){
            lbm.sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf()
            return
        }

        try{
            startForeground(1,createNotification())
            dataCollector.init()
        }catch(e:SecurityException){
            lbm.sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf()
            return
        }

        val prefs=applicationContext.getSharedPreferences("HoarderPrefs", MODE_PRIVATE)
        val shouldCollect=prefs.getBoolean("dataCollectionToggleState",true)
        val shouldUpload=prefs.getBoolean("dataUploadToggleState",false)
        val server=prefs.getString("serverIpPortAddress","")?.split(":")

        if(server!=null&&server.size==2&&server[0].isNotBlank()&&server[1].toIntOrNull()!=null)
            dataUploader.setServer(server[0],server[1].toInt())

        if(shouldCollect){ca=true;dataCollector.start()}
        if(shouldUpload&&dataUploader.hasValidServer()){
            ua=true
            dataUploader.resetCounter()
            dataUploader.start()
        }
    }

    override fun onDestroy(){
        super.onDestroy()
        dataCollector.cleanup()
        dataUploader.stop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cr)
        restartService()
    }

    override fun onTaskRemoved(r: Intent?){
        super.onTaskRemoved(r)
        scheduleRestart()
    }

    private fun scheduleRestart(){
        val i= Intent(applicationContext, BackgroundService::class.java)
        val pi= PendingIntent.getService(applicationContext,1,i, PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE)as AlarmManager).set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis()+1000,pi)
    }

    private fun restartService(){
        val i= Intent(applicationContext, BackgroundService::class.java)
        try{
            if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.O)
                applicationContext.startForegroundService(i)
            else
                applicationContext.startService(i)
        }catch(e:Exception){scheduleRestart()}
    }

    override fun onBind(i: Intent?): IBinder?=null

    private fun createNotificationChannel(){
        if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.O){
            val c= NotificationChannel(
                "HoarderServiceChannel", "Hoarder Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            c.apply{
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility= NotificationCompat.VISIBILITY_SECRET
            }
            this.getSystemService(NotificationManager::class.java)?.createNotificationChannel(c)
        }
    }

    private fun createNotification(): Notification {
        val pi= PendingIntent.getActivity(this,0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext,"HoarderServiceChannel")
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun hasRequiredPermissions()=
        (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)==
                PackageManager.PERMISSION_GRANTED||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)==
                PackageManager.PERMISSION_GRANTED)&&
                ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION)==
                PackageManager.PERMISSION_GRANTED
}