package com.example.data.place

import com.example.data.model.LatLng
import java.util.Locale

/**
 * Advisory proximity check for a selected place (Phase B, backlog B-05;
 * audit F6-D1 / F6-D2).
 *
 * **Purely advisory.** It never changes a coordinate, never blocks a selection
 * and never forbids a legitimate long-distance trip — the user can always
 * confirm the place. What it prevents is the failure mode proven on device: a
 * short, ambiguous name resolving to a same-named place in another country
 * ("Pavana" → Italy ⇒ an 18,066 km route estimate) with **no visible cue**.
 *
 * The reference product disambiguates by *displaying* server-supplied locality
 * and distance; the independent stack does the same with the locality the
 * Photon parser already extracts, plus this distance advisory.
 */
object PlaceProximityGuard {

    /**
     * Distance beyond which a selection is almost certainly a *different* place
     * with the same name (1,000 km). Chosen so that every realistic domestic
     * motorcycle tour stays silent, while a cross-country name collision
     * (India → Europe/Africa/Americas) is flagged.
     */
    const val DEFAULT_THRESHOLD_METERS = 1_000_000.0

    /** Straight-line distance from [from] to the selected place, in meters. */
    fun distanceMeters(from: LatLng, place: SelectedPlace): Double =
        from.distanceTo(place.latLng()) * 1000.0

    /** True when the selection is implausibly far from the reference point. */
    fun isImplausiblyDistant(
        distanceMeters: Double,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS
    ): Boolean = distanceMeters.isFinite() && distanceMeters > thresholdMeters

    /**
     * The warning text for a given distance, or `null` when the selection is
     * plausible. The message names the distance so the user can judge it
     * themselves — it never asserts that the place is wrong.
     */
    fun warningFor(from: LatLng?, place: SelectedPlace?): String? {
        if (from == null || place == null) return null
        val meters = distanceMeters(from, place)
        if (!isImplausiblyDistant(meters)) return null
        return warningText(meters)
    }

    /** Human-readable distance warning. */
    fun warningText(distanceMeters: Double): String {
        val km = distanceMeters / 1000.0
        return if (km >= 1000.0) {
            String.format(Locale.US, "This place is %,d km away — is that intended?", km.toInt())
        } else {
            String.format(Locale.US, "This place is %,d km away — is that intended?", km.toInt())
        }
    }
}
