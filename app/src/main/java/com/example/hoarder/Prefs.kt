package com.example.hoarder
import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)

    fun isFirstRun() = p.getBoolean("isFirstRun", true)
    fun markFirstRunComplete() = p.edit().putBoolean("isFirstRun", false).apply()

    fun isDataCollectionEnabled() = p.getBoolean("dataCollectionToggleState", true)
    fun setDataCollectionEnabled(e: Boolean) = p.edit().putBoolean("dataCollectionToggleState", e).apply()

    fun isDataUploadEnabled() = p.getBoolean("dataUploadToggleState", false)
    fun setDataUploadEnabled(e: Boolean) = p.edit().putBoolean("dataUploadToggleState", e).apply()

    fun getServerAddress() = p.getString("serverIpPortAddress", "") ?: ""
    fun setServerAddress(a: String) = p.edit().putString("serverIpPortAddress", a).apply()

    fun getGPSPrecision() = p.getInt("gpsPrecision", -1)  // Default to smart GPS precision
    fun setGPSPrecision(p: Int) = this.p.edit().putInt("gpsPrecision", p).apply()

    fun getGPSAltitudePrecision() = p.getInt("gpsAltitudePrecision", -1)  // Default to smart altitude precision
    fun setGPSAltitudePrecision(p: Int) = this.p.edit().putInt("gpsAltitudePrecision", p).apply()

    fun getRSSIPrecision() = p.getInt("rssiPrecision", -1)  // Default to smart RSSI precision
    fun setRSSIPrecision(p: Int) = this.p.edit().putInt("rssiPrecision", p).apply()

    fun getBatteryPrecision() = p.getInt("batteryPrecision", -1)  // Default to smart battery precision
    fun setBatteryPrecision(p: Int) = this.p.edit().putInt("batteryPrecision", p).apply()

    fun getNetworkPrecision() = p.getInt("networkPrecision", 0)  // Default to smart precision (0)
    fun setNetworkPrecision(p: Int) = this.p.edit().putInt("networkPrecision", p).apply()

    fun getSpeedPrecision() = p.getInt("speedPrecision", -1)  // Default to smart speed precision
    fun setSpeedPrecision(p: Int) = this.p.edit().putInt("speedPrecision", p).apply()

    fun getBarometerPrecision() = p.getInt("barometerPrecision", -1)  // Default to smart barometer precision
    fun setBarometerPrecision(p: Int) = this.p.edit().putInt("barometerPrecision", p).apply()
}