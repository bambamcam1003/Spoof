package com.example.spoof

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Foreground service that registers test providers and pushes a mock location to them
 * once per second. Optionally moves in a straight line toward a destination.
 */
class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val installedProviders = mutableListOf<String>()

    private var lat = 0.0
    private var lng = 0.0
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var speed = 0f
    private var accuracy = DEFAULT_ACCURACY
    private var jitter = 0f
    private var bearing = 0f
    private var lastTickMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            advance()
            push()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Satisfy the startForegroundService() contract in case STOP arrived that way.
            startInForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || !intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LNG)) {
            Log.w(TAG, "Start intent is missing lat/lng; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        lat = intent.getNumberExtra(EXTRA_LAT)!!
        lng = intent.getNumberExtra(EXTRA_LNG)!!
        destLat = intent.getNumberExtra(EXTRA_DEST_LAT)
        destLng = intent.getNumberExtra(EXTRA_DEST_LNG)
        speed = intent.getNumberExtra(EXTRA_SPEED)?.toFloat() ?: 0f
        accuracy = intent.getNumberExtra(EXTRA_ACCURACY)?.toFloat() ?: DEFAULT_ACCURACY
        jitter = intent.getNumberExtra(EXTRA_JITTER)?.toFloat() ?: 0f
        lastTickMs = SystemClock.elapsedRealtime()

        startInForeground()

        if (installedProviders.isEmpty()) {
            try {
                installProviders()
            } catch (e: SecurityException) {
                Log.e(TAG, "Not the selected mock location app", e)
                Status.update(
                    false,
                    "Error: select Spoof as the mock location app in Developer options."
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }

        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        removeProviders()
        Status.update(false, getString(R.string.status_idle))
        super.onDestroy()
    }

    private fun installProviders() {
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers += LocationManager.FUSED_PROVIDER
        }
        for (provider in providers) {
            try {
                addTestProvider(provider)
                locationManager.setTestProviderEnabled(provider, true)
                installedProviders += provider
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Could not add test provider $provider", e)
            }
        }
    }

    private fun addTestProvider(provider: String) {
        // Remove any stale provider left behind by a previous crash.
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                provider,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                false, false, false, false,
                true, true, true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE
            )
        }
    }

    private fun removeProviders() {
        for (provider in installedProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove test provider $provider", e)
            }
        }
        installedProviders.clear()
    }

    /** Moves the current position toward the destination based on elapsed time. */
    private fun advance() {
        val now = SystemClock.elapsedRealtime()
        val dtSec = (now - lastTickMs) / 1000.0
        lastTickMs = now

        val dLat = destLat ?: return
        val dLng = destLng ?: return
        if (speed <= 0f) return

        val remaining = distanceMeters(lat, lng, dLat, dLng)
        if (remaining < 0.5) {
            lat = dLat
            lng = dLng
            return
        }
        bearing = bearingDegrees(lat, lng, dLat, dLng)
        val fraction = min(1.0, speed * dtSec / remaining)
        lat += (dLat - lat) * fraction
        lng += (dLng - lng) * fraction
    }

    private fun push() {
        var outLat = lat
        var outLng = lng
        if (jitter > 0f) {
            val r = jitter * sqrt(Random.nextDouble())
            val theta = Random.nextDouble() * 2 * Math.PI
            outLat += (r * cos(theta)) / METERS_PER_DEGREE
            outLng += (r * sin(theta)) / (METERS_PER_DEGREE * cos(Math.toRadians(lat)))
        }

        val moving = destLat != null && speed > 0f &&
            distanceMeters(lat, lng, destLat!!, destLng!!) >= 0.5

        for (provider in installedProviders) {
            val location = Location(provider).apply {
                latitude = outLat
                longitude = outLng
                altitude = 0.0
                accuracy = this@MockLocationService.accuracy
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (moving) {
                    speed = this@MockLocationService.speed
                    bearing = this@MockLocationService.bearing
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = accuracy
                    if (moving) {
                        speedAccuracyMetersPerSecond = 0.5f
                        bearingAccuracyDegrees = 5f
                    }
                }
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push location to $provider", e)
            }
        }

        val coords = "%.6f, %.6f".format(outLat, outLng)
        val text = if (moving) {
            val remaining = distanceMeters(lat, lng, destLat!!, destLng!!)
            "$coords\n${Units.formatSpeed(speed.toDouble())}, " +
                "${Units.formatDistance(remaining)} to go"
        } else {
            coords
        }
        Status.update(
            true,
            "Mocking: $text\nProviders: ${installedProviders.joinToString()}",
            outLat to outLng
        )
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("%.6f, %.6f".format(lat, lng)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle("Mocking location")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    /** Accepts extras sent as double, float, int, long or string (handy from adb). */
    private fun Intent.getNumberExtra(key: String): Double? {
        if (!hasExtra(key)) return null
        return when (val v = extras?.get(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    companion object {
        private const val TAG = "Spoof"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val DEFAULT_ACCURACY = 5f
        private const val METERS_PER_DEGREE = 111_320.0

        const val ACTION_START = "com.example.spoof.START"
        const val ACTION_STOP = "com.example.spoof.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LNG = "dest_lng"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_JITTER = "jitter"

        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, out)
            return out[0].toDouble()
        }

        fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lng2 - lng1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }
    }
}
