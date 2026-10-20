package org.zxor.oronbox_location

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*

class OronboxLocationPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var context: Context
    private lateinit var engine: LocationEngine
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var permissionResult: MethodChannel.Result? = null
    private var startResult: MethodChannel.Result? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startTimeout: Runnable? = null
    private var sink: EventChannel.EventSink? = null
    private val prefs get() = context.getSharedPreferences("oronbox_location", Context.MODE_PRIVATE)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        engine = LocationEngine(context)
        methods = MethodChannel(binding.binaryMessenger, "org.zxor.oronbox/location").also { it.setMethodCallHandler(this) }
        events = EventChannel(binding.binaryMessenger, "org.zxor.oronbox/location/positions").also { it.setStreamHandler(this) }
        engine.emit = { sink?.success(it) }
        engine.fail = { sink?.error(it, "Location updates unavailable", null) }
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun permission(): Int {
        if (engine.precise() || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            return if (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) 3 else 2
        val act = activity
        return if (prefs.getBoolean("requested", false) && act != null &&
            !act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) 1 else 0
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "serviceEnabled" -> result.success(engine.enabled())
                "preciseEnabled" -> result.success(engine.precise())
                "permission" -> result.success(permission())
                "requestPermission" -> request(false, result)
                "requestBackgroundPermission" -> request(true, result)
                "openLocationSettings" -> {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); result.success(null)
                }
                "openSettings" -> { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); result.success(null) }
                "current" -> engine.current(call.argument<Number>("timeoutMs")?.toLong() ?: 30000,
                    call.argument<Number>("maximumAgeMs")?.toLong() ?: 0, result)
                "cancelCurrent" -> { engine.cancelCurrent(); result.success(null) }
                "start" -> start(call, result)
                "stop" -> { stop(); result.success(null) }
                else -> result.notImplemented()
            }
        } catch (_: SecurityException) { result.error("PERMISSION_DENIED", "Location permission unavailable", null) }
        catch (error: Exception) { result.error(error.message ?: "LOCATION_ERROR", "Location operation failed", null) }
    }
    private fun request(background: Boolean, result: MethodChannel.Result) {
        val act = activity ?: run { result.error("NO_ACTIVITY", "Open the app to grant permission", null); return }
        if (permissionResult != null) { result.error("BUSY", "Permission request already active", null); return }
        if (background && !engine.precise()) { result.error("FOREGROUND_PERMISSION_REQUIRED", "Grant foreground permission first", null); return }
        if ((!background && engine.precise()) || (background && permission() == 3)) { result.success(permission()); return }
        if (background && Build.VERSION.SDK_INT >= 30) {
            // Android 11+ grants Always from app settings, separately from foreground.
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            result.success(permission()); return
        }
        permissionResult = result
        if (!background) prefs.edit().putBoolean("requested", true).apply()
        val permissions = if (background && Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        try { act.requestPermissions(permissions, 7300) }
        catch (_: Exception) { permissionResult = null; result.error("PERMISSION_REQUEST_FAILED", "Open settings to grant location permission", null) }
    }
    private fun start(call: MethodCall, result: MethodChannel.Result) {
        if (startResult != null) { result.error("BUSY", "Location session is starting", null); return }
        if (!engine.precise()) { result.error("PRECISE_PERMISSION_REQUIRED", "Precise location required", null); return }
        if (!engine.enabled()) { result.error("SERVICE_DISABLED", "Location is disabled", null); return }
        startResult = result
        LocationService.onStopped = {
            engine.stop()
            sink?.error("SERVICE_STOPPED", "Location service stopped", null)
        }
        LocationService.onStarted = { error ->
            val pending = startResult
            startResult = null
            startTimeout?.let(handler::removeCallbacks); startTimeout = null
            LocationService.onStarted = null
            if (error != null) { engine.stop(); recovery(call); pending?.error("BACKGROUND_START_RESTRICTED", "Open the app to start location", null) }
            else try { engine.start(); pending?.success(null) }
            catch (failure: Exception) {
                stop()
                val code = if (failure is SecurityException) "PERMISSION_DENIED"
                    else if (failure is IllegalStateException) failure.message ?: "LOCATION_START_FAILED"
                    else "LOCATION_START_FAILED"
                pending?.error(code, "Location updates unavailable", null)
            }
        }
        val intent = Intent(context, LocationService::class.java)
        listOf("notificationTitle", "notificationText", "channelName").forEach { intent.putExtra(it, call.argument<String>(it)) }
        startTimeout = Runnable {
            val pending = startResult
            startResult = null
            stop()
            pending?.error("LOCATION_START_TIMEOUT", "Location service did not start", null)
        }.also { handler.postDelayed(it, 10000) }
        try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
        catch (_: Exception) {
            startResult = null; LocationService.onStarted = null
            startTimeout?.let(handler::removeCallbacks); startTimeout = null
            recovery(call)
            result.error("BACKGROUND_START_RESTRICTED", "Open the app to start location", null)
        }
    }
    private fun recovery(call: MethodCall) {
        val title = call.argument<String>("recoveryTitle") ?: return
        val text = call.argument<String>("recoveryText") ?: return
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("oronbox_location_recovery",
            call.argument<String>("channelName"), NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(context, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, "oronbox_location_recovery") else Notification.Builder(context)
        manager.notify(7302, builder.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title)
            .setContentText(text).setContentIntent(pending).setAutoCancel(true).build())
    }
    private fun stop() {
        startTimeout?.let(handler::removeCallbacks); startTimeout = null
        engine.stop(); LocationService.onStarted = null; LocationService.onStopped = null
        startResult?.error("CANCELLED", "Location session cancelled", null); startResult = null
        context.stopService(Intent(context, LocationService::class.java))
    }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { sink = events }
    override fun onCancel(arguments: Any?) { sink = null }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray): Boolean {
        if (code != 7300) return false
        permissionResult?.success(permission()); permissionResult = null; return true
    }
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding; activity = binding.activity; binding.addRequestPermissionsResultListener(this)
    }
    private fun detach() { binding?.removeRequestPermissionsResultListener(this); binding = null; activity = null }
    override fun onDetachedFromActivityForConfigChanges() { detach() }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { onAttachedToActivity(binding) }
    override fun onDetachedFromActivity() {
        detach(); permissionResult?.error("NO_ACTIVITY", "Permission request interrupted", null); permissionResult = null
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stop(); engine.dispose(); methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null
        permissionResult?.error("CANCELLED", "Plugin detached", null); permissionResult = null
    }
}
