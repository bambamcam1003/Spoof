package com.example.spoof

import androidx.annotation.IdRes
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/** Keyless raster tile sources the map can switch between. */
enum class MapStyle(@IdRes val buttonId: Int, val attribution: String, val tileSource: ITileSource) {
    STREETS(
        R.id.styleStreetsButton,
        "Tiles © Esri — Esri, HERE, Garmin, USGS, OpenStreetMap contributors",
        EsriTileSource("EsriWorldStreetMap", "World_Street_Map"),
    ),
    SATELLITE(
        R.id.styleSatelliteButton,
        "Tiles © Esri — Esri, Maxar, Earthstar Geographics, GIS User Community",
        EsriTileSource("EsriWorldImagery", "World_Imagery"),
    ),
    OSM(
        R.id.styleOsmButton,
        "© OpenStreetMap contributors, tiles by Humanitarian OSM Team / OSM France",
        XYTileSource(
            "OsmFranceHot", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.fr/hot/",
                "https://b.tile.openstreetmap.fr/hot/",
                "https://c.tile.openstreetmap.fr/hot/",
            ),
            "© OpenStreetMap contributors",
        ),
    );

    companion object {
        fun fromButtonId(@IdRes id: Int) = entries.firstOrNull { it.buttonId == id } ?: STREETS

        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: STREETS
    }
}

/** ArcGIS Online tiles, which use z/y/x order rather than the usual z/x/y. */
private class EsriTileSource(name: String, service: String) : OnlineTileSourceBase(
    name, 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
    "Tiles © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}
