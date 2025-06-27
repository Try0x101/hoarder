package com.example.hoarder
import kotlin.math.floor
import kotlin.math.max

object DataUtils{
    fun rs(rv:Int,rp:Int)=when(rp){0->rv;else->(rv/rp)*rp}
    fun smartRSSI(v:Int)=when{v<-110->v;v<-90->(v/5)*5;else->(v/10)*10}
    fun smartBattery(p:Int)=when{p<10->p;p<50->(p/5)*5;else->(p/10)*10}
    fun rb(p:Int,pr:Int):Int{if(pr==0)return p;if(p<10&&pr>1)return p;return(p/pr)*pr}

    fun rn(v:Int, pr:Int):Number {
        val mbps = v.toDouble() / 1024.0  // Convert Kbps to Mbps

        // Float precision mode (-2): Return as float with 1 decimal place
        if(pr == -2) {
            return (Math.round(mbps * 10) / 10.0).toFloat()
        }

        // Smart rounding (0): Apply the tiered precision logic
        if(pr == 0) {
            return when {
                mbps < 2.0 -> (Math.round(mbps * 10) / 10.0).toFloat() // Below 2 Mbps: float precision
                mbps < 7.0 -> Math.floor(mbps).toInt() // Between 2-7 Mbps: round to nearest lower 1 Mbps
                else -> (Math.floor(mbps / 5.0) * 5).toInt() // Above 7 Mbps: round to nearest lower 5 Mbps
            }
        }

        // Fixed precision: Round to nearest lower multiple of precision value
        // Important fix: We need to ensure we're not returning 0 when the value is small
        val rounded = (Math.floor(mbps / pr) * pr).toInt()
        return if (mbps > 0 && rounded == 0) 1 else rounded  // Ensure at least 1 Mbps if non-zero
    }

    fun rsp(s:Int,pr:Int):Int{
        if(pr==-1)return when{s<2->0;s<10->(s/3)*3;else->(s/10)*10}
        if(pr==0)return s
        return(s/pr)*pr
    }
    fun smartGPSPrecision(s:Float):Pair<Int,Int>{
        val sk=(s*3.6).toInt()
        return when{sk<4->Pair(1000,1000);sk<40->Pair(20,20);sk<140->Pair(100,100);else->Pair(1000,1000)}
    }

    fun smartBarometer(v:Int):Int{
        return when{
            v < -10 -> v  // Show exact value for values below -10 meters
            else -> max(0, (floor(v/5.0)*5).toInt())  // Round to lowest 5 meters, min value 0
        }
    }

    fun roundBarometer(v:Int, precision:Int):Int{
        if(precision==0) return v  // No rounding (maximum precision)
        return (floor(v/precision.toDouble())*precision).toInt()  // Round to lowest multiple of precision
    }

    fun isServiceRunning(ctx:android.content.Context,cls:Class<*>):Boolean{
        val m=ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)as android.app.ActivityManager
        for(s in m.getRunningServices(Integer.MAX_VALUE))if(cls.name==s.service.className)return true
        return false
    }
}