package com.example.hoarder.common

import android.app.ActivityManager
import android.content.Context

object ContextUtils {
    fun isServiceRunning(ctx: Context, cls: Class<*>): Boolean {
        return try {
            val m = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = m.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == cls.name }
        } catch (e: Exception) {
            false
        }
    }
}