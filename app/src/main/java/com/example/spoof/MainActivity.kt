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
import android.widget.EditText
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
    private lateinit var destinationInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var accuracyInput: EditText
    private lateinit var jitterInput: EditText
    private lateinit var statusText: TextView
    private lateinit var tapModeGroup: MaterialButtonToggleGroup
    private lateinit var map: LocationMap

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
        destinationInput = findViewById(R.id.destinationInput)
        speedInput = findViewById(R.id.speedInput)
        accuracyInput = findViewById(R.id.accuracyInput)
        jitterInput = findViewById(R.id.jitterInput)
        statusText = findViewById(R.id.statusText)

        locationInput.setText(prefs.getString(KEY_LOCATION, "37.422000, -122.084100"))
        destinationInput.setText(prefs.getString(KEY_DESTINATION, ""))
        speedInput.setText(prefs.getString(KEY_SPEED, "1.4"))
        accuracyInput.setText(prefs.getString(KEY_ACCURACY, "5"))
        jitterInput.setText(prefs.getString(KEY_JITTER, "0"))

        setUpMap()

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
                val input = if (tapModeGroup.checkedButtonId == R.id.tapDestinationButton) {
                    destinationInput
                } else {
                    locationInput
                }
                input.setText(formatLatLng(point))
            },
            onLocationDragged = { locationInput.setText(formatLatLng(it)) },
            onDestinationDragged = { destinationInput.setText(formatLatLng(it)) },
            onDestinationCleared = { destinationInput.setText("") },
        )

        // Keep the markers in sync with whatever is typed or pasted into the fields.
        locationInput.addTextChangedListener(afterTextChanged {
            map.setLocation(parseLatLng(it)?.toGeoPoint())
        })
        destinationInput.addTextChangedListener(afterTextChanged {
            map.setDestination(parseLatLng(it)?.toGeoPoint())
        })
        map.setLocation(parseLatLng(locationInput.text.toString())?.toGeoPoint())
        map.setDestination(parseLatLng(destinationInput.text.toString())?.toGeoPoint())
        parseLatLng(locationInput.text.toString())?.let {
            map.centerOn(it.toGeoPoint())
        }

        findViewById<View>(R.id.myLocationButton).setOnClickListener { map.centerOnBest() }
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
            putString(KEY_DESTINATION, destinationInput.text.toString())
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
        val destText = destinationInput.text.toString()
        val dest = if (destText.isBlank()) null else parseLatLng(destText)
        if (destText.isNotBlank() && dest == null) {
            destinationInput.error = "Enter as: lat, lng"
            return
        }

        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LAT, start.first)
            .putExtra(MockLocationService.EXTRA_LNG, start.second)
            .putExtra(MockLocationService.EXTRA_SPEED, speedInput.text.toString().toDoubleOrNull() ?: 0.0)
            .putExtra(MockLocationService.EXTRA_ACCURACY, accuracyInput.text.toString().toDoubleOrNull() ?: 5.0)
            .putExtra(MockLocationService.EXTRA_JITTER, jitterInput.text.toString().toDoubleOrNull() ?: 0.0)
        if (dest != null) {
            intent.putExtra(MockLocationService.EXTRA_DEST_LAT, dest.first)
            intent.putExtra(MockLocationService.EXTRA_DEST_LNG, dest.second)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private fun Pair<Double, Double>.toGeoPoint() = GeoPoint(first, second)

        private fun formatLatLng(p: GeoPoint) =
            String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)

        private fun afterTextChanged(block: (String) -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
        }

        private const val KEY_LOCATION = "location"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_SPEED = "speed"
        private const val KEY_ACCURACY = "accuracy"
        private const val KEY_JITTER = "jitter"

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
