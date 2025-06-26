package com.example.hoarder

object DataUtils{
    fun rs(rv:Int,rp:Int)=when(rp){0->rv;else->(rv/rp)*rp}
    fun smartRSSI(v:Int)=when{v<-110->v;v<-90->(v/5)*5;else->(v/10)*10}
    fun smartBattery(p:Int)=when{p<10->p;p<50->(p/5)*5;else->(p/10)*10}
    fun rb(p:Int,pr:Int):Int{if(pr==0)return p;if(p<10&&pr>1)return p;return(p/pr)*pr}
    fun rn(v:Int,pr:Int):Int{if(pr==0)return if(v<7)v else(v/5)*5;return(v/pr)*pr}
    fun rsp(s:Int,pr:Int):Int{
        if(pr==-1)return when{s<2->0;s<10->(s/3)*3;else->(s/10)*10}
        if(pr==0)return s
        return(s/pr)*pr
    }
    fun smartGPSPrecision(s:Float):Pair<Int,Int>{
        val sk=(s*3.6).toInt()
        return when{sk<4->Pair(1000,1000);sk<40->Pair(20,20);sk<140->Pair(100,100);else->Pair(1000,1000)}
    }

    fun isServiceRunning(ctx:android.content.Context,cls:Class<*>):Boolean{
        val m=ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)as android.app.ActivityManager
        for(s in m.getRunningServices(Integer.MAX_VALUE))if(cls.name==s.service.className)return true
        return false
    }
}