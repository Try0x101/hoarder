package com.example.hoarder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
class BootReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context?,i:Intent?){
        if(i?.action==Intent.ACTION_BOOT_COMPLETED&&c!=null){
            val si=Intent(c,BackgroundService::class.java)
            if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.O)ContextCompat.startForegroundService(c,si)else c.startService(si)
        }
    }
}