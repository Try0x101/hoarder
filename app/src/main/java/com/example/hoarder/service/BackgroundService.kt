package com.example.hoarder.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.DataCollector
import com.example.hoarder.utils.NotifUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundService: Service(){
    private lateinit var h: Handler
    internal lateinit var powerManager: PowerManager
    private lateinit var dataCollector: DataCollector
    private lateinit var dataUploader: DataUploader
    private lateinit var servicePrefs: SharedPreferences
    private lateinit var appPrefs: Prefs
    private lateinit var commandHandler: ServiceCommandHandler
    private lateinit var serviceInitializer: ServiceInitializer

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
                .sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString", json))
        }

        commandHandler = ServiceCommandHandler(this, serviceScope, dataCollector, dataUploader, powerManager,
            ca, ua, this::updateAppPreferences, this::broadcastStateUpdate)

        serviceInitializer = ServiceInitializer(this, appPrefs, h, serviceScope, powerManager, dataCollector,
            dataUploader, commandHandler, ua, this::broadcastStateUpdate, this::stopSelf)

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
            if (!isInitialized.get()) initService()
            commandHandler.handle(i)
            return START_STICKY
        }

        if (isInitialized.compareAndSet(false, true)) {
            initService()
        }
        return START_STICKY
    }

    private fun initService(){
        NotifUtils.createSilentChannel(this)
        startForeground(1, NotifUtils.createServiceNotification(this))
        serviceInitializer.initialize()
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
            try { LocalBroadcastManager.getInstance(this).unregisterReceiver(cr) } catch (e: Exception) {}
        }
        isInitialized.set(false)
    }

    override fun onBind(i: Intent?): IBinder? = null
}