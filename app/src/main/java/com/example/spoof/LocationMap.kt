package com.example.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Wraps an osmdroid [MapView] used to pick the mock location and the path's waypoints.
 *
 * Taps call [onTap]; dragging a marker calls [onLocationDragged] / [onWaypointDragged];
 * tapping a numbered waypoint calls [onWaypointClicked]. Waypoint indexes are 0-based.
 */
class LocationMap(
    private val mapView: MapView,
    private val onTap: (GeoPoint) -> Unit,
    private val onLocationDragged: (GeoPoint) -> Unit,
    private val onWaypointDragged: (Int, GeoPoint) -> Unit,
    private val onWaypointClicked: (Int) -> Unit,
) {
    private val context = mapView.context

    private val route = Polyline(mapView).apply {
        outlinePaint.color = 0xFF3D5AFE.toInt()
        outlinePaint.strokeWidth = 8f
        isEnabled = false
        setOnClickListener { _, _, _ -> false }
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

    private val waypointMarkers = mutableListOf<Marker>()

    private var hasLocation = false
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
        // Waypoint markers are inserted here, between the live dot and the location pin.
        mapView.overlays.add(locationMarker)
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

    fun setWaypoints(points: List<GeoPoint>) {
        while (waypointMarkers.size > points.size) {
            mapView.overlays.remove(waypointMarkers.removeAt(waypointMarkers.lastIndex))
        }
        while (waypointMarkers.size < points.size) {
            val marker = newWaypointMarker(waypointMarkers.size)
            mapView.overlays.add(mapView.overlays.indexOf(locationMarker), marker)
            waypointMarkers += marker
        }
        points.forEachIndexed { i, p -> waypointMarkers[i].position = p }
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

    /** Zooms to show the location and every waypoint. */
    fun zoomToPath() {
        val points = waypointMarkers.map { it.position } +
            listOfNotNull(locationMarker.position.takeIf { hasLocation })
        if (points.size < 2) {
            points.firstOrNull()?.let { centerOn(it) }
            return
        }
        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), true)
    }

    fun onResume() = mapView.onResume()

    fun onPause() = mapView.onPause()

    private fun refreshRoute() {
        route.isEnabled = hasLocation && waypointMarkers.isNotEmpty()
        if (route.isEnabled) {
            route.setPoints(listOf(locationMarker.position) + waypointMarkers.map { it.position })
        }
        mapView.invalidate()
    }

    private fun newWaypointMarker(index: Int) = Marker(mapView).apply {
        icon = numberedIcon(index + 1)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = context.getString(R.string.marker_waypoint, index + 1)
        isDraggable = true
        setOnMarkerDragListener(dragListener { onWaypointDragged(index, it) })
        setOnMarkerClickListener { _, _ ->
            onWaypointClicked(index)
            true
        }
    }

    /** A filled circle with [number] in it, matching the route line's color. */
    private fun numberedIcon(number: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (26 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(r, r, r, paint)
        paint.color = route.outlinePaint.color
        canvas.drawCircle(r, r, r - 2 * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = (if (number < 100) 13 else 10) * density
        canvas.drawText(number.toString(), r, r - (paint.descent() + paint.ascent()) / 2, paint)
        return BitmapDrawable(context.resources, bitmap)
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
