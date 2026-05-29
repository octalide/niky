package com.octalide.niky

import android.location.Location
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * Encodes [android.location.Location] as Nikon-compatible NMEA-0183 sentences.
 *
 * The D800/D800e expects $GPRMC + $GPGGA at 4800 baud. Checksum is XOR of
 * every char between '$' and '*'. Lat/lon are DDMM.MMMM (degrees + decimal
 * minutes), not decimal degrees.
 */
object Nmea {

    fun encode(loc: Location): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = loc.time }
        val time = "%02d%02d%02d.%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND) / 10,
        )
        val date = "%02d%02d%02d".format(
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100,
        )

        val (latStr, latHem) = ddmm(loc.latitude, isLat = true)
        val (lonStr, lonHem) = ddmm(loc.longitude, isLat = false)

        val fixQual = 1
        val sats = "08"
        val hdop = "1.0"
        val alt = "%.1f".format(Locale.US, if (loc.hasAltitude()) loc.altitude else 0.0)
        val sogKnots = if (loc.hasSpeed()) loc.speed * 1.943844f else 0f  // m/s -> knots
        val cog = if (loc.hasBearing()) loc.bearing else 0f

        val ggaBody = "GPGGA,$time,$latStr,$latHem,$lonStr,$lonHem,$fixQual,$sats,$hdop,$alt,M,0.0,M,,"
        val rmcBody = "GPRMC,$time,A,$latStr,$latHem,$lonStr,$lonHem,${"%.1f".format(Locale.US, sogKnots)},${"%.1f".format(Locale.US, cog)},$date,,,A"

        val out = StringBuilder()
        out.append('$').append(ggaBody).append('*').append("%02X".format(checksum(ggaBody))).append("\r\n")
        out.append('$').append(rmcBody).append('*').append("%02X".format(checksum(rmcBody))).append("\r\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun ddmm(decimalDeg: Double, isLat: Boolean): Pair<String, Char> {
        val abs = abs(decimalDeg)
        val deg = floor(abs).toInt()
        val min = (abs - deg) * 60.0
        val degWidth = if (isLat) 2 else 3
        val str = "%0${degWidth}d%07.4f".format(Locale.US, deg, min)
        val hem = if (isLat) (if (decimalDeg >= 0) 'N' else 'S')
                  else        (if (decimalDeg >= 0) 'E' else 'W')
        return str to hem
    }

    private fun checksum(body: String): Int {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return cs and 0xFF
    }
}
