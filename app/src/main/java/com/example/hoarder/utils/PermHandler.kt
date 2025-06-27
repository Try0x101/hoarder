package com.example.hoarder.utils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class PermHandler(private val a: Activity, private val h: Handler) {
    private val busy = AtomicBoolean(false)
    private var act: (() -> Unit)? = null

    private val perms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION
    )

    fun hasAllPerms(): Boolean {
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(a, p) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun setPendingAction(action: () -> Unit) {
        act = action
    }

    fun requestPerms() {
        if (busy.get()) return

        val needed = mutableListOf<String>()
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(a, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }

        if (needed.isEmpty()) {
            act?.invoke()
            act = null
            return
        }

        busy.set(true)
        ActivityCompat.requestPermissions(a, needed.toTypedArray(), 100)
    }

    fun handleResult(rc: Int, res: IntArray) {
        busy.set(false)

        if (rc == 100) {
            if (hasAllPerms()) {
                act?.invoke()
                act = null
            } else {
                Toast.makeText(a, "App requires all permissions", Toast.LENGTH_LONG).show()
                h.postDelayed({ requestPerms() }, 1000)
            }
        }
    }
}