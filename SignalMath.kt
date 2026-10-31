package com.tonix.wifireader

import java.util.Locale
import kotlin.math.pow

/** Only distance + beep helpers. NO fake speed estimates anymore - Mbps comes from real measurement. */
object SignalMath {
    /** Rough distance from RSSI (path-loss model). Always shown with "~". */
    fun estimateDistanceMeters(rssi: Int): Double {
        val txPowerAt1m = -40.0
        val pathLossExponent = 2.7
        return 10.0.pow((txPowerAt1m - rssi) / (10 * pathLossExponent))
    }

    fun quality(rssi: Int): Int = ((rssi + 100) * 2).coerceIn(0, 100)

    /** Beep interval in ms: faster beeps as the live signal gets stronger. */
    fun beepIntervalMs(rssi: Int): Long {
        val frac = ((rssi + 95f) / 65f).coerceIn(0f, 1f)
        return (900 - frac * 820).toLong().coerceIn(80, 900)
    }

    /** Pitch also rises with signal strength: 500 Hz (weak) .. 1800 Hz (strong). */
    fun beepFreqHz(rssi: Int): Double {
        val frac = ((rssi + 95f) / 65f).coerceIn(0f, 1f)
        return 500.0 + frac * 1300.0
    }

    /** metres AND kilometres, e.g. "12.4 m · 0.012 km" or "1.25 km · 1,250 m" */
    fun distText(m: Double): String =
        if (m >= 1000.0) String.format(Locale.US, "%.2f km · %,.0f m", m / 1000, m)
        else String.format(Locale.US, "%.1f m · %.3f km", m, m / 1000)

    fun distShort(m: Double): String =
        if (m >= 1000.0) String.format(Locale.US, "%.2f km", m / 1000) else String.format(Locale.US, "%.1f m", m)

    fun compassName(bearing: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearing + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)]

    /** Strong -30..-60 green, medium -61..-75 yellow, weak below red */
    fun color(r: Int): Int = when {
        r >= -60 -> 0xFF2BFF5A.toInt()
        r >= -75 -> 0xFFFFE81F.toInt()
        else -> 0xFFFF2D2D.toInt()
    }

    fun channel(freq: Int): Int = when {
        freq <= 0 -> 0
        freq < 3000 -> (freq - 2407) / 5
        freq >= 5925 -> (freq - 5950) / 5
        else -> (freq - 5000) / 5
    }

    fun band(freq: Int): String = when {
        freq >= 5925 -> "6 GHz"
        freq >= 4900 -> "5 GHz"
        freq > 0 -> "2.4 GHz"
        else -> "--"
    }
}
