package com.example.hoarder.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.DataCollector
import com.example.hoarder.ui.service.ServiceCommander
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ServiceInitializer(
    private val context: Context,
    private val appPrefs: Prefs,
    private val handler: Handler,
    private val serviceScope: CoroutineScope,
    private val powerManager: PowerManager,
    private val dataCollector: DataCollector,
    private val dataUploader: DataUploader,
    private val commandHandler: ServiceCommandHandler,
    private val uploadActive: AtomicBoolean,
    private val broadcastStateUpdate: () -> Unit,
    private val stopService: () -> Unit
) {
    fun initialize() {
        if (!hasRequiredPermissions()) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ServiceCommander.ACTION_PERMISSIONS_REQUIRED))
            stopService()
            return
        }

        try {
            dataCollector.init()
            powerManager.start()
        } catch (e: SecurityException) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ServiceCommander.ACTION_PERMISSIONS_REQUIRED))
            stopService()
            return
        }

        restoreServiceState()
    }

    private fun hasRequiredPermissions(): Boolean {
        val mode = appPrefs.getPowerMode()
        return if (mode == Prefs.POWER_MODE_PASSIVE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun restoreServiceState() {
        val shouldCollect = appPrefs.isDataCollectionEnabled()
        val shouldUpload = appPrefs.isDataUploadEnabled()
        val serverAddress = appPrefs.getServerAddress()

        dataUploader.setServer(serverAddress)

        if (shouldCollect) {
            commandHandler.handle(Intent(ServiceCommander.ACTION_START_COLLECTION))
        }

        if (shouldUpload && dataUploader.hasValidServer() && uploadActive.compareAndSet(false, true)) {
            dataUploader.resetCounter()
            dataUploader.start()
        }

        handler.postDelayed({
            broadcastStateUpdate()
            schedulePeriodicCleanup()
        }, 1000)
    }

    private fun schedulePeriodicCleanup() {
        serviceScope.launch {
            while (true) {
                delay(24 * 60 * 60 * 1000L)
                withContext(Dispatchers.IO) {
                    dataUploader.cleanup()
                }
            }
        }
    }
}