package com.example.hoarder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.DataCollector
import com.example.hoarder.ui.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundService: Service(){
    private lateinit var h: Handler
    private lateinit var dataCollector: DataCollector
    private lateinit var dataUploader: DataUploader
    private lateinit var servicePrefs: SharedPreferences
    private lateinit var appPrefs: Prefs
    private lateinit var commandHandler: ServiceCommandHandler
    private lateinit var powerManager: PowerManager

    private val ca = AtomicBoolean(false)
    private val ua = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cr = object: BroadcastReceiver(){
        override fun onReceive(c: Context?, i: Intent?){
            commandHandler.handle(i)
        }
    }

    override fun onCreate(){
        super.onCreate()
        h = Handler(Looper.getMainLooper())
        servicePrefs = getSharedPreferences("HoarderServicePrefs", MODE_PRIVATE)
        appPrefs = Prefs(this)

        powerManager = PowerManager(this, appPrefs)
        dataUploader = DataUploader(this, h, servicePrefs, powerManager, appPrefs)

        dataCollector = DataCollector(this, h, powerManager) { json ->
            LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(
                    Intent("com.example.hoarder.DATA_UPDATE").putExtra(
                        "jsonString",
                        json
                    )
                )
        }

        commandHandler = ServiceCommandHandler(
            this, serviceScope, dataCollector, dataUploader, powerManager,
            ca, ua, this::updateAppPreferences, this::broadcastStateUpdate
        )

        dataCollector.setDataUploader(dataUploader)

        registerServiceReceiver()
    }

    private fun registerServiceReceiver() {
        if (receiverRegistered.compareAndSet(false, true)) {
            LocalBroadcastManager.getInstance(this).registerReceiver(cr, IntentFilter().apply{
                addAction("com.example.hoarder.START_COLLECTION")
                addAction("com.example.hoarder.STOP_COLLECTION")
                addAction("com.example.hoarder.START_UPLOAD")
                addAction("com.example.hoarder.STOP_UPLOAD")
                addAction("com.example.hoarder.FORCE_UPLOAD")
                addAction("com.example.hoarder.SEND_BUFFER")
                addAction("com.example.hoarder.GET_STATE")
                addAction("com.example.hoarder.POWER_MODE_CHANGED")
                addAction("com.example.hoarder.BATCHING_SETTINGS_CHANGED")
            })
        }
    }

    override fun onStartCommand(i: Intent?, f:Int, s:Int):Int{
        if (i?.action == "com.example.hoarder.MOTION_STATE_CHANGED") {
            if (!isInitialized.get()) {
                initService()
            }
            commandHandler.handle(i)
            return START_STICKY
        }

        if (isInitialized.compareAndSet(false, true)) {
            createNotificationChannel()
            initService()
        }
        return START_STICKY
    }

    private fun initService(){
        val lbm = LocalBroadcastManager.getInstance(this)
        if (!hasRequiredPermissions()){
            lbm.sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf()
            return
        }

        try{
            startForeground(1, createNotification())
            dataCollector.init()
            powerManager.start()
        } catch(e:SecurityException){
            lbm.sendBroadcast(Intent("com.example.hoarder.PERMISSIONS_REQUIRED"))
            stopSelf()
            return
        }

        restoreServiceState()
    }

    private fun restoreServiceState() {
        val shouldCollect = appPrefs.isDataCollectionEnabled()
        val shouldUpload = appPrefs.isDataUploadEnabled()
        val server = appPrefs.getServerAddress().split(":")

        if (server.size == 2 && server[0].isNotBlank() && server[1].toIntOrNull() != null) {
            dataUploader.setServer(server[0], server[1].toInt())
        }

        if (shouldCollect) {
            commandHandler.handle(Intent("com.example.hoarder.START_COLLECTION"))
        }

        if (shouldUpload && dataUploader.hasValidServer() && ua.compareAndSet(false, true)) {
            dataUploader.resetCounter()
            dataUploader.start()
        }

        h.postDelayed({
            broadcastStateUpdate()
            schedulePeriodicCleanup()
        }, 1000)
    }

    private fun schedulePeriodicCleanup() {
        serviceScope.launch {
            while (isInitialized.get()) {
                try {
                    delay(24 * 60 * 60 * 1000L)
                    withContext(Dispatchers.IO) {
                        dataUploader.cleanup()
                    }
                } catch (e: Exception) {
                    Log.e("BackgroundService", "Exception during periodic cleanup", e)
                }
            }
        }
    }

    private fun updateAppPreferences(key: String, value: Any) {
        val editor = getSharedPreferences("HoarderPrefs", MODE_PRIVATE).edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
        editor.apply()
    }

    private fun broadcastStateUpdate() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("com.example.hoarder.SERVICE_STATE_UPDATE").apply {
                putExtra("dataCollectionActive", ca.get())
                putExtra("dataUploadActive", ua.get())
                putExtra("serviceInitialized", isInitialized.get())
            }
        )
    }

    override fun onDestroy(){
        super.onDestroy()
        cleanup()
        ServiceUtils.restartService(applicationContext)
    }

    override fun onTaskRemoved(r: Intent?){
        super.onTaskRemoved(r)
        ServiceUtils.scheduleRestart(applicationContext)
    }

    private fun cleanup() {
        ca.set(false)
        ua.set(false)

        try {
            dataCollector.cleanup()
            dataUploader.cleanup()
            powerManager.stop()
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e("BackgroundService", "Exception during cleanup", e)
        }

        if (receiverRegistered.compareAndSet(true, false)) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(cr)
            } catch (e: Exception) {
                Log.e("BackgroundService", "Exception during receiver unregister", e)
            }
        }

        isInitialized.set(false)
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun createNotificationChannel(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val c = NotificationChannel(
                "HoarderServiceChannel", "Hoarder Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            c.apply{
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            this.getSystemService(NotificationManager::class.java)?.createNotificationChannel(c)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this,0, Intent(this, MainActivity::class.java),
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

    private fun hasRequiredPermissions() =
        (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}