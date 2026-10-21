package com.example.spoof

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.view.View
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButtonToggleGroup
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var locationInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var accuracyInput: EditText
    private lateinit var jitterInput: EditText
    private lateinit var statusText: TextView
    private lateinit var tapModeGroup: MaterialButtonToggleGroup
    private lateinit var map: LocationMap
    private lateinit var waypointList: LinearLayout
    private lateinit var pathTitle: TextView
    private lateinit var pathEmptyText: TextView

    /** The path to follow after the start location; speeds are in mph. */
    private val waypoints = mutableListOf<Waypoint>()

    private val prefs by lazy { getSharedPreferences("spoof", Context.MODE_PRIVATE) }
    private val statusListener: () -> Unit = { renderStatus() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startMocking()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to run the location foreground service",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocationMap.configure(applicationContext)
        setContentView(R.layout.activity_main)

        locationInput = findViewById(R.id.locationInput)
        speedInput = findViewById(R.id.speedInput)
        accuracyInput = findViewById(R.id.accuracyInput)
        jitterInput = findViewById(R.id.jitterInput)
        statusText = findViewById(R.id.statusText)

        locationInput.setText(prefs.getString(KEY_LOCATION, "37.422000, -122.084100"))
        speedInput.setText(prefs.getString(KEY_SPEED, "3"))
        accuracyInput.setText(prefs.getString(KEY_ACCURACY, "16"))
        jitterInput.setText(prefs.getString(KEY_JITTER, "0"))

        setUpMap()
        setUpPath()

        findViewById<TextView>(R.id.startButton).setOnClickListener { onStartClicked() }
        findViewById<TextView>(R.id.stopButton).setOnClickListener {
            startService(
                Intent(this, MockLocationService::class.java)
                    .setAction(MockLocationService.ACTION_STOP)
            )
        }
        findViewById<TextView>(R.id.devOptionsButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Enable Developer options first: tap Build number 7 times in About phone",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setUpMap() {
        tapModeGroup = findViewById(R.id.tapModeGroup)
        map = LocationMap(
            findViewById<MapView>(R.id.mapView),
            onTap = { point ->
                if (tapModeGroup.checkedButtonId == R.id.tapWaypointButton) {
                    waypoints += Waypoint(point.latitude, point.longitude, defaultSpeedMph())
                    onPathChanged()
                } else {
                    locationInput.setText(formatLatLng(point))
                }
            },
            onLocationDragged = { locationInput.setText(formatLatLng(it)) },
            onWaypointDragged = { i, point ->
                waypoints[i] = waypoints[i].copy(lat = point.latitude, lng = point.longitude)
                onPathChanged()
            },
            onWaypointClicked = { i ->
                waypoints.removeAt(i)
                onPathChanged()
            },
        )

        // Keep the marker in sync with whatever is typed or pasted into the field.
        locationInput.addTextChangedListener(afterTextChanged {
            map.setLocation(parseLatLng(it)?.toGeoPoint())
            renderPath()
        })
        map.setLocation(parseLatLng(locationInput.text.toString())?.toGeoPoint())
        parseLatLng(locationInput.text.toString())?.let {
            map.centerOn(it.toGeoPoint())
        }

        findViewById<View>(R.id.myLocationButton).setOnClickListener { map.centerOnBest() }

        val attributionText = findViewById<TextView>(R.id.attributionText)
        val styleGroup = findViewById<MaterialButtonToggleGroup>(R.id.mapStyleGroup)
        fun applyStyle(style: MapStyle) {
            map.setStyle(style)
            attributionText.text = style.attribution
            prefs.edit { putString(KEY_MAP_STYLE, style.name) }
        }
        val initialStyle = MapStyle.fromName(prefs.getString(KEY_MAP_STYLE, null))
        styleGroup.check(initialStyle.buttonId)
        applyStyle(initialStyle)
        styleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) applyStyle(MapStyle.fromButtonId(checkedId))
        }
    }

    private fun setUpPath() {
        waypointList = findViewById(R.id.waypointList)
        pathTitle = findViewById(R.id.pathTitle)
        pathEmptyText = findViewById(R.id.pathEmptyText)

        waypoints += loadPath()
        findViewById<View>(R.id.fitPathButton).setOnClickListener { map.zoomToPath() }
        findViewById<View>(R.id.clearPathButton).setOnClickListener {
            waypoints.clear()
            onPathChanged()
        }
        onPathChanged()
    }

    private fun loadPath(): List<Waypoint> {
        prefs.getString(KEY_PATH, null)?.let { return Waypoint.decode(it).orEmpty() }
        // Migrate the single destination saved by older versions.
        val dest = parseLatLng(prefs.getString(KEY_DESTINATION, "").orEmpty())
            ?: return emptyList()
        return listOf(Waypoint(dest.first, dest.second, defaultSpeedMph()))
    }

    private fun defaultSpeedMph() =
        speedInput.text.toString().toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_SPEED_MPH

    /** Saves the path and rebuilds the map overlay and the waypoint rows. */
    private fun onPathChanged() {
        prefs.edit { putString(KEY_PATH, Waypoint.encode(waypoints)) }
        map.setWaypoints(waypoints.map { GeoPoint(it.lat, it.lng) })

        waypointList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        waypoints.forEachIndexed { i, wp ->
            val row = inflater.inflate(R.layout.item_waypoint, waypointList, false)
            val speed = row.findViewById<EditText>(R.id.waypointSpeed)
            speed.setText(formatSpeed(wp.speed))
            speed.addTextChangedListener(afterTextChanged {
                val mph = it.toDoubleOrNull()
                speed.error = if (mph != null && mph > 0) null else getString(R.string.speed_required)
                if (mph != null && mph > 0 && waypoints.getOrNull(i) != null) {
                    waypoints[i] = waypoints[i].copy(speed = mph)
                    prefs.edit { putString(KEY_PATH, Waypoint.encode(waypoints)) }
                    renderPath()
                }
            })
            row.findViewById<View>(R.id.waypointRemove).apply {
                contentDescription = getString(R.string.remove_waypoint, i + 1)
                setOnClickListener {
                    waypoints.removeAt(i)
                    onPathChanged()
                }
            }
            waypointList.addView(row)
        }
        renderPath()
    }

    /** Refreshes the leg distances and the path's total distance and time. */
    private fun renderPath() {
        pathEmptyText.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
        var from = parseLatLng(locationInput.text.toString())
        var totalMeters = 0.0
        var totalSeconds = 0.0
        waypoints.forEachIndexed { i, wp ->
            val meters = from?.let {
                MockLocationService.distanceMeters(it.first, it.second, wp.lat, wp.lng)
            }
            if (meters != null) {
                totalMeters += meters
                if (wp.speed > 0) totalSeconds += meters / Units.mphToMetersPerSecond(wp.speed)
            }
            waypointList.getChildAt(i)?.findViewById<TextView>(R.id.waypointLabel)?.text =
                getString(
                    R.string.waypoint_label,
                    i + 1,
                    formatLatLng(GeoPoint(wp.lat, wp.lng)),
                    meters?.let { Units.formatDistance(it) } ?: "?",
                )
            from = wp.lat to wp.lng
        }
        pathTitle.text = if (waypoints.isEmpty()) {
            getString(R.string.path_title)
        } else {
            getString(
                R.string.path_summary,
                Units.formatDistance(totalMeters),
                formatDuration(totalSeconds),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        Status.addListener(statusListener)
        renderStatus()
    }

    override fun onStop() {
        Status.removeListener(statusListener)
        super.onStop()
    }

    private fun renderStatus() {
        statusText.text = Status.message.ifEmpty { getString(R.string.status_idle) }
        map.setCurrent(Status.position?.takeIf { Status.running }?.toGeoPoint())
    }

    private fun onStartClicked() {
        prefs.edit {
            putString(KEY_LOCATION, locationInput.text.toString())
            putString(KEY_SPEED, speedInput.text.toString())
            putString(KEY_ACCURACY, accuracyInput.text.toString())
            putString(KEY_JITTER, jitterInput.text.toString())
        }

        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startMocking() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startMocking() {
        val start = parseLatLng(locationInput.text.toString())
        if (start == null) {
            locationInput.error = "Enter as: lat, lng"
            return
        }
        val badSpeed = (0 until waypointList.childCount).any {
            waypointList.getChildAt(it).findViewById<EditText>(R.id.waypointSpeed).error != null
        }
        if (badSpeed || waypoints.any { it.speed <= 0 }) {
            Toast.makeText(this, R.string.speed_required, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LAT, start.first)
            .putExtra(MockLocationService.EXTRA_LNG, start.second)
            // The UI is in mph / feet; the service takes m/s / meters.
            .putExtra(
                MockLocationService.EXTRA_PATH,
                Waypoint.encode(
                    waypoints.map { it.copy(speed = Units.mphToMetersPerSecond(it.speed)) }
                )
            )
            .putExtra(
                MockLocationService.EXTRA_ACCURACY,
                Units.feetToMeters(accuracyInput.text.toString().toDoubleOrNull() ?: 16.0)
            )
            .putExtra(
                MockLocationService.EXTRA_JITTER,
                Units.feetToMeters(jitterInput.text.toString().toDoubleOrNull() ?: 0.0)
            )
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private fun Pair<Double, Double>.toGeoPoint() = GeoPoint(first, second)

        private fun formatLatLng(p: GeoPoint) =
            String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)

        private fun formatSpeed(mph: Double) =
            String.format(Locale.US, "%.2f", mph).trimEnd('0').trimEnd('.')

        private fun formatDuration(seconds: Double): String {
            val total = seconds.toLong()
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return when {
                h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
                m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
                else -> "${s}s"
            }
        }

        private fun afterTextChanged(block: (String) -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
        }

        private const val KEY_LOCATION = "location"
        /** Only read, to migrate the single destination saved by older versions. */
        private const val KEY_DESTINATION = "destination"
        private const val KEY_PATH = "path_mph"
        private const val KEY_SPEED = "speed_mph"
        private const val KEY_ACCURACY = "accuracy_ft"
        private const val KEY_JITTER = "jitter_ft"
        private const val KEY_MAP_STYLE = "map_style"
        private const val DEFAULT_SPEED_MPH = 3.0

        /** Parses "lat, lng" (or "lat lng"), as copied from Google Maps. */
        fun parseLatLng(text: String): Pair<Double, Double>? {
            val parts = text.trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return lat to lng
        }
    }
}
