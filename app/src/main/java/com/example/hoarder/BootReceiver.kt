package com.example.hoarder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context?,i:Intent?){
        if(i?.action==Intent.ACTION_BOOT_COMPLETED&&c!=null){
            Log.d(
                "BootReceiver",
                "Device rebooted - deferring service start to next user interaction"
            )
            // Always set pendingServiceStart to true, regardless of permissions
            val prefs = c.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("pendingServiceStart", true).apply()
        }
    }
}
