package com.example.spoof

import java.util.Locale

/** Imperial units for the UI; everything passed to Android's location APIs stays metric. */
object Units {
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val MPS_PER_MPH = METERS_PER_MILE / 3600.0

    fun feetToMeters(feet: Double) = feet * METERS_PER_FOOT

    fun mphToMetersPerSecond(mph: Double) = mph * MPS_PER_MPH

    fun metersPerSecondToMph(mps: Double) = mps / MPS_PER_MPH

    /** Formats a distance as feet under a tenth of a mile, otherwise as miles. */
    fun formatDistance(meters: Double): String {
        val miles = meters / METERS_PER_MILE
        return if (miles < 0.1) {
            String.format(Locale.US, "%.0f ft", meters / METERS_PER_FOOT)
        } else {
            String.format(Locale.US, "%.2f mi", miles)
        }
    }

    fun formatSpeed(mps: Double) = String.format(Locale.US, "%.1f mph", metersPerSecondToMph(mps))
}
