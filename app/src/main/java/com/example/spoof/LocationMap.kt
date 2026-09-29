package com.example.spoof

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Wraps an osmdroid [MapView] used to pick the mock location and destination.
 *
 * Taps call [onTap]; dragging a marker calls [onLocationDragged] / [onDestinationDragged];
 * tapping the destination flag calls [onDestinationCleared].
 */
class LocationMap(
    private val mapView: MapView,
    private val onTap: (GeoPoint) -> Unit,
    private val onLocationDragged: (GeoPoint) -> Unit,
    private val onDestinationDragged: (GeoPoint) -> Unit,
    private val onDestinationCleared: () -> Unit,
) {
    private val context = mapView.context

    private val route = Polyline(mapView).apply {
        outlinePaint.color = 0xFF3D5AFE.toInt()
        outlinePaint.strokeWidth = 8f
        isEnabled = false
        setOnClickListener { _, _, _ -> false }
    }

    private val destinationMarker = Marker(mapView).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_flag)
        // The flagpole's foot is at the lower-left of the icon.
        setAnchor(0.22f, 0.9f)
        title = context.getString(R.string.marker_destination)
        isDraggable = true
        setOnMarkerDragListener(dragListener { onDestinationDragged(it) })
        setOnMarkerClickListener { _, _ ->
            onDestinationCleared()
            true
        }
    }

    private val locationMarker = Marker(mapView).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_pin_marker)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = context.getString(R.string.marker_location)
        isDraggable = true
        setOnMarkerDragListener(dragListener { onLocationDragged(it) })
        setOnMarkerClickListener { _, _ -> true }
    }

    private val currentMarker = Marker(mapView).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_dot)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = context.getString(R.string.marker_current)
        setOnMarkerClickListener { _, _ -> true }
    }

    private var hasLocation = false
    private var hasDestination = false
    private var hasCurrent = false

    init {
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        mapView.isTilesScaledToDpi = true
        mapView.controller.setZoom(16.0)

        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        // Order matters: events at the bottom so markers get touches first.
        mapView.overlays.add(events)
        mapView.overlays.add(route)
        mapView.overlays.add(currentMarker)
        mapView.overlays.add(destinationMarker)
        mapView.overlays.add(locationMarker)
        destinationMarker.isEnabled = false
        locationMarker.isEnabled = false
        currentMarker.isEnabled = false

        disallowParentScrollWhileTouched()
    }

    fun setStyle(style: MapStyle) {
        mapView.setTileSource(style.tileSource)
    }

    fun setLocation(point: GeoPoint?) {
        hasLocation = point != null
        locationMarker.isEnabled = hasLocation
        if (point != null) locationMarker.position = point
        refreshRoute()
    }

    fun setDestination(point: GeoPoint?) {
        hasDestination = point != null
        destinationMarker.isEnabled = hasDestination
        if (point != null) destinationMarker.position = point
        refreshRoute()
    }

    /** Shows the live mocked position, or hides it when [point] is null. */
    fun setCurrent(point: GeoPoint?) {
        hasCurrent = point != null
        currentMarker.isEnabled = hasCurrent
        if (point != null) currentMarker.position = point
        mapView.invalidate()
    }

    fun centerOn(point: GeoPoint) {
        mapView.controller.animateTo(point)
    }

    /** Centers on the live position if mocking, otherwise on the chosen location. */
    fun centerOnBest() {
        when {
            hasCurrent -> centerOn(currentMarker.position)
            hasLocation -> centerOn(locationMarker.position)
        }
    }

    fun onResume() = mapView.onResume()

    fun onPause() = mapView.onPause()

    private fun refreshRoute() {
        route.isEnabled = hasLocation && hasDestination
        if (route.isEnabled) {
            route.setPoints(listOf(locationMarker.position, destinationMarker.position))
        }
        mapView.invalidate()
    }

    private fun dragListener(onEnd: (GeoPoint) -> Unit) = object : Marker.OnMarkerDragListener {
        override fun onMarkerDrag(marker: Marker) = refreshRoute()
        override fun onMarkerDragStart(marker: Marker) = Unit
        override fun onMarkerDragEnd(marker: Marker) = onEnd(marker.position)
    }

    /** The map lives inside a ScrollView; keep pans/zooms from scrolling the page. */
    @SuppressLint("ClickableViewAccessibility")
    private fun disallowParentScrollWhileTouched() {
        mapView.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    companion object {
        /** Must run before the MapView is inflated. */
        fun configure(context: Context) {
            val config = Configuration.getInstance()
            config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // OSM's tile usage policy requires a user agent that identifies the app and a
            // contact; placeholder-looking ones (e.g. a bare "com.example.*") get 403 tiles.
            config.userAgentValue = userAgent(context)
            val base = File(context.cacheDir, "osmdroid")
            config.osmdroidBasePath = base
            // New directory so tiles cached under the old, blocked user agent are dropped.
            config.osmdroidTileCache = File(base, "tiles-v2")
            File(base, "tiles").deleteRecursively()
        }

        private fun userAgent(context: Context): String {
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) {
                null
            } ?: "dev"
            return "Spoof/$version (Android mock location debug tool; " +
                "+https://github.com/bambamcam1003/spoof)"
        }
    }
}
