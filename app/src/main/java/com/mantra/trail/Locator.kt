package com.mantra.trail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * WHERE THE PHONE IS, BY EVERY MEANS IT HAS.
 *
 * The fused provider is the system service that already combines GNSS, Wi-Fi networks, cell
 * towers and the motion sensors, and it does it with almanac data and a model no app can match.
 * Asking for PRIORITY_HIGH_ACCURACY tells it to use the satellites as well as the cheap sources,
 * not instead of them.
 *
 * THE SATELLITE COUNT IS READ SEPARATELY, from the platform's own GNSS status, because the fused
 * fix will not say where it came from. On this phone (Pixel 7) the receiver is dual frequency,
 * L1 and L5, which is what makes a three-metre fix under trees possible at all — and the count on
 * the screen is how somebody can tell a real fix from a network guess.
 *
 * ACCURACY IS NEVER INVENTED. A fix that carries no accuracy figure is passed on with null and
 * the screen draws a dash. A dot drawn confidently in the wrong place is the failure this whole
 * app exists to avoid.
 */
class Locator(private val context: Context) {

    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var satellites: Int? = null
    private var gnssCallback: GnssStatus.Callback? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            // The point that was actually recorded goes to whoever is writing the file, so the
            // file and the list can never disagree about what is in the track.
            Trail.onFix(toFix(l, satellites))?.let { FixWriter.wrote(it) }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One second between fixes while walking. Slower than that and a switchback is a straight
     * line; faster and the file grows without the track getting any truer.
     */
    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 1_000L) {
        if (!hasPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, callback, context.mainLooper)
        startSatelliteCount()
    }

    fun stop() {
        fused.removeLocationUpdates(callback)
        stopSatelliteCount()
    }

    @SuppressLint("MissingPermission")
    private fun startSatelliteCount() {
        if (gnssCallback != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                satellites = used
            }
        }
        gnssCallback = cb
        lm.registerGnssStatusCallback(cb, android.os.Handler(context.mainLooper))
    }

    private fun stopSatelliteCount() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        gnssCallback?.let { lm?.unregisterGnssStatusCallback(it) }
        gnssCallback = null
    }

    companion object {
        fun toFix(l: Location, satellites: Int?): Fix = Fix(
            lat = l.latitude,
            lon = l.longitude,
            // A location without an altitude answers 0.0 from getAltitude(), which is a real
            // height somebody could be standing at. hasAltitude() is the only honest question.
            ele = if (l.hasAltitude()) l.altitude else null,
            timeMs = l.time,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            speedMs = if (l.hasSpeed()) l.speed else null,
            satellites = satellites,
        )

        /**
         * The vertical accuracy, when the phone will give one. The Pixel's barometer is what
         * makes it worth showing at all; without one an altitude is the GNSS solution's weakest
         * axis and is routinely twice the horizontal error.
         */
        fun verticalAccuracy(l: Location): Float? =
            if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null
    }
}
