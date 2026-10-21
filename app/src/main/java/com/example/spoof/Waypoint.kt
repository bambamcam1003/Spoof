package com.example.spoof

import java.util.Locale

/**
 * A point on the path to spoof, plus the speed to travel the leg that ends here.
 *
 * The unit of [speed] depends on who holds it: the UI keeps mph, the service takes m/s.
 */
data class Waypoint(val lat: Double, val lng: Double, val speed: Double) {

    companion object {
        /** Encodes as "lat,lng,speed;lat,lng,speed;…", the format the service's path extra takes. */
        fun encode(path: List<Waypoint>): String = path.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f,%.3f", it.lat, it.lng, it.speed)
        }

        /** Parses [encode]'s format; returns null if any entry is malformed. */
        fun decode(text: String): List<Waypoint>? {
            if (text.isBlank()) return emptyList()
            return text.split(';').filter { it.isNotBlank() }.map { entry ->
                val parts = entry.split(',').map { it.trim().toDoubleOrNull() ?: return null }
                if (parts.size != 3) return null
                val (lat, lng, speed) = parts
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
                Waypoint(lat, lng, speed)
            }
        }
    }
}
