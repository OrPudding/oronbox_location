package org.zxor.oronbox_location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.flutter.plugin.common.MethodChannel.Result

internal class LocationEngine(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: (() -> Unit)? = null
    private var updates: LocationListener? = null
    private var satellitesUsed: Int? = null
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
        override fun onStopped() { satellitesUsed = null }
    }
    var emit: ((Map<String, Any>) -> Unit)? = null
    var fail: ((String) -> Unit)? = null

    fun enabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    fun precise(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }

    fun current(timeout: Long, maximumAge: Long, result: Result) {
        if (current != null) { result.error("BUSY", "Location request already active", null); return }
        if (!precise()) { result.error("PRECISE_PERMISSION_REQUIRED", "Precise location required", null); return }
        val providers = providers()
        if (providers.isEmpty()) { result.error("SERVICE_DISABLED", "Location is disabled", null); return }
        val started = SystemClock.elapsedRealtimeNanos()
        fun valid(location: Location): Boolean {
            val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            return location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0 && age >= 0 &&
                (if (maximumAge > 0) age <= maximumAge else location.elapsedRealtimeNanos >= started)
        }
        if (maximumAge > 0) {
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter(::valid)
                .maxByOrNull { it.elapsedRealtimeNanos }
            if (cached != null) { result.success(asMap(cached)); return }
        }
        var finished = false
        lateinit var listener: LocationListener
        lateinit var timeoutTask: Runnable
        fun finish(location: Location?, code: String?) {
            if (finished) return
            finished = true
            manager.removeUpdates(listener)
            handler.removeCallbacks(timeoutTask)
            current = null
            if (location != null) result.success(asMap(location))
            else result.error(code ?: "NO_FIX", "Location request did not produce a fix", null)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (valid(location)) finish(location, null) }
            override fun onProviderDisabled(provider: String) { if (!enabled()) finish(null, "SERVICE_DISABLED") }
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        timeoutTask = Runnable { finish(null, "TIMEOUT") }
        current = { finish(null, "CANCELLED") }
        try {
            providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, listener, Looper.getMainLooper()) }
            handler.postDelayed(timeoutTask, timeout.coerceIn(1000, 120000))
        } catch (_: SecurityException) { finish(null, "PERMISSION_DENIED") }
    }

    fun start() {
        if (updates != null) return
        check(precise()) { "PRECISE_PERMISSION_REQUIRED" }
        val providers = providers().filter { it == LocationManager.GPS_PROVIDER }
        check(providers.isNotEmpty()) { "SERVICE_DISABLED" }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0) emit?.invoke(asMap(location))
            }
            override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) fail?.invoke("SERVICE_DISABLED") }
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        updates = listener
        manager.registerGnssStatusCallback(gnss, handler)
        try { providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, listener, Looper.getMainLooper()) } }
        catch (error: Exception) { stop(); throw error }
    }
    fun cancelCurrent() { current?.invoke() }
    fun stop() { updates?.let(manager::removeUpdates); updates = null; manager.unregisterGnssStatusCallback(gnss); satellitesUsed = null }
    fun dispose() { cancelCurrent(); stop(); emit = null; fail = null }

    private fun asMap(location: Location): Map<String, Any> = buildMap {
        location.provider?.let { put("provider", it) }
        satellitesUsed?.let { put("satellitesUsed", it) }
        put("latitude", location.latitude); put("longitude", location.longitude)
        put("timestamp", location.time)
        put("ageMilliseconds", ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0))
        if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
        if (location.hasAltitude()) put("altitude", location.altitude)
        if (location.hasSpeed()) put("speed", location.speed.toDouble())
        if (location.hasBearing()) put("bearing", location.bearing.toDouble())
        if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) put("verticalAccuracy", location.verticalAccuracyMeters.toDouble())
    }
}
