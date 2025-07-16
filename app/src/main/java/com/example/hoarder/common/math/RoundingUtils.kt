package com.example.hoarder.common.math

import kotlin.math.floor
import kotlin.math.max

object RoundingUtils {
    fun rs(rv: Int, rp: Int): Int {
        if (rp <= 0) return rv
        return (rv / rp) * rp
    }

    fun smartRSSI(v: Int): Int {
        return when {
            v >= -10 -> v
            v < -110 -> v
            v < -90 -> (v / 5) * 5
            else -> (v / 10) * 10
        }
    }

    fun smartBattery(p: Int): Int {
        return when {
            p < 0 -> 0
            p > 100 -> 100
            p <= 10 -> p
            p <= 50 -> (p / 5) * 5
            else -> (p / 10) * 10
        }
    }

    fun rb(p: Int, pr: Int): Int {
        if (pr <= 0) return p
        if (p < 0) return 0
        if (p > 100) return 100
        if (p <= 10 && pr > 1) return p
        return (p / pr) * pr
    }

    fun rn(v: Int, pr: Int): Number {
        if (v < 0) return 0

        val mbps = v.toDouble() / 1024.0

        if (mbps <= 0.1) {
            return 0
        }

        if (pr == -2) {
            return (Math.round(mbps * 10) / 10.0).toFloat()
        }

        if (pr == 0) {
            return when {
                mbps < 2.0 -> (Math.round(mbps * 10) / 10.0).toFloat()
                mbps < 7.0 -> Math.floor(mbps).toInt()
                else -> (Math.floor(mbps / 5.0) * 5).toInt()
            }
        }

        if (pr < 0) return 0

        val rounded = (Math.floor(mbps / pr) * pr).toInt()
        return if (mbps > 0.1 && rounded == 0 && pr >= 1) pr else rounded
    }

    fun rsp(s: Int, pr: Int): Int {
        if (s < 0) return 0

        if (pr == -1) {
            return when {
                s < 2 -> 0
                s < 10 -> ((s + 2) / 3) * 3
                else -> ((s + 9) / 10) * 10
            }
        }

        if (pr <= 0) return s

        return (s / pr) * pr
    }

    fun smartGPSPrecision(s: Float): Pair<Int, Int> {
        if (s < 0) return Pair(1000, 1000)

        val sk = (s * 3.6).toInt()
        return when {
            sk < 4 -> Pair(1000, 1000)
            sk < 40 -> Pair(20, 20)
            sk < 140 -> Pair(100, 100)
            else -> Pair(1000, 1000)
        }
    }

    fun smartBarometer(v: Int): Int {
        return when {
            v < -1000 -> v
            v < 0 -> v
            v < 100 -> max(0, (floor(v / 5.0) * 5).toInt())
            v < 1000 -> max(0, (floor(v / 10.0) * 10).toInt())
            else -> max(0, (floor(v / 50.0) * 50).toInt())
        }
    }

    fun roundBarometer(v: Int, precision: Int): Int {
        if (precision <= 0) return v

        return when {
            v < -1000 -> v
            v < 0 && precision > 10 -> v
            else -> (floor(v / precision.toDouble()) * precision).toInt()
        }
    }

    fun smartCapacity(c: Int): Int {
        if (c <= 0) return 0
        return (c / 100) * 100
    }
}