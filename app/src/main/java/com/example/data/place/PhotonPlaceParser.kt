package com.example.data.place

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure GeoJSON parser for the Photon place-search response.
 *
 * Photon returns FeatureCollection features whose geometry coordinates are in
 * [longitude, latitude] order and whose `properties` block is entirely optional
 * per-field. This parser:
 *  - converts coordinates to the app's (latitude, longitude) convention,
 *  - rejects features with missing names or invalid coordinates instead of crashing,
 *  - never treats display strings as identifiers (stable OSM id, deterministic fallback),
 *  - formats a concise human address without repeating the primary name.
 */
object PhotonPlaceParser {

    /** Structured view of the (all-optional) Photon feature properties. */
    data class PhotonProperties(
        val name: String?,
        val street: String?,
        val housenumber: String?,
        val postcode: String?,
        val district: String?,
        val city: String?,
        val county: String?,
        val state: String?,
        val country: String?,
        val osmKey: String?,
        val osmValue: String?,
        val osmType: String?,
        val osmId: Long?
    )

    /**
     * Parses a full GeoJSON FeatureCollection body. Returns null when the body is
     * malformed (not JSON / not an object / no `features` array); an empty features
     * array is a valid "no results" response and yields an empty list.
     * Individual bad features are skipped, never fatal.
     */
    fun parseFeatureCollection(body: String): List<SelectedPlace>? {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }

        val features = root.optJSONArray("features") ?: return null
        val places = ArrayList<SelectedPlace>(features.length())
        for (i in 0 until features.length()) {
            if (features.optJSONObject(i) == null) continue
            val feature = features.getJSONObject(i)

            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2 || coords.isNull(0) || coords.isNull(1)) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!isValidCoordinate(lat, lon)) continue

            val props = feature.optJSONObject("properties")?.let(::parseProperties) ?: continue
            val displayName = resolveDisplayName(props) ?: continue
            if (!isValidCoordinate(lat, lon)) continue

            places.add(
                SelectedPlace(
                    id = stablePlaceId(props.osmType, props.osmId, displayName, lat, lon),
                    name = displayName,
                    address = formatAddress(props, displayName),
                    latitude = lat,
                    longitude = lon,
                    source = PlaceSource.PHOTON,
                    category = (props.osmValue ?: props.osmKey)?.takeIf { it.isNotBlank() }
                )
            )
        }
        return places
    }

    fun parseProperties(props: JSONObject): PhotonProperties {
        return PhotonProperties(
            name = props.optStringOrNull("name"),
            street = props.optStringOrNull("street"),
            housenumber = props.optStringOrNull("housenumber"),
            postcode = props.optStringOrNull("postcode"),
            district = props.optStringOrNull("district"),
            city = props.optStringOrNull("city"),
            county = props.optStringOrNull("county"),
            state = props.optStringOrNull("state"),
            country = props.optStringOrNull("country"),
            osmKey = props.optStringOrNull("osm_key"),
            osmValue = props.optStringOrNull("osm_value"),
            osmType = props.optStringOrNull("osm_type"),
            osmId = if (props.isNull("osm_id")) null else props.optLong("osm_id", 0L).takeIf { it != 0L }
        )
    }

    /**
     * Primary label. Prefers the OSM name; street-address features without a name
     * degrade to "housenumber street" or bare street. Features with no usable
     * label are rejected (spec: "feature with no name" must not display).
     */
    fun resolveDisplayName(props: PhotonProperties): String? {
        props.name?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val street = props.street?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val housenumber = props.housenumber?.trim()
        return if (!housenumber.isNullOrBlank()) "$housenumber $street" else street
    }

    /**
     * Concise secondary label built from available administrative/street fields.
     * Rules:
     *  - never repeats the primary name (case-insensitive compare),
     *  - never contains duplicate components (city == district keeps only one),
     *  - city is preferred over district/county when present,
     *  - returns null when there is nothing more informative than the name itself.
     */
    fun formatAddress(props: PhotonProperties, displayName: String): String? {
        val primary = displayName.trim().lowercase(Locale.US)
        val streetLine = listOf(
            props.housenumber?.trim(),
            props.street?.trim()
        ).filter { !it.isNullOrBlank() }.joinToString(" ").trim()

        val locality = props.city?.trim()?.takeIf { it.isNotBlank() }
            ?: props.district?.trim()?.takeIf { it.isNotBlank() }
            ?: props.county?.trim()?.takeIf { it.isNotBlank() }

        val components = listOfNotNull(
            streetLine.takeIf { it.isNotBlank() },
            props.postcode?.trim()?.takeIf { it.isNotBlank() },
            locality,
            props.state?.trim()?.takeIf { it.isNotBlank() },
            props.country?.trim()?.takeIf { it.isNotBlank() }
        )

        val deduped = ArrayList<String>(components.size)
        for (component in components) {
            val lower = component.lowercase(Locale.US)
            if (deduped.any { it.lowercase(Locale.US) == lower }) continue
            if (lower == primary) continue
            deduped.add(component)
        }
        if (deduped.isEmpty()) return null
        return deduped.joinToString(", ")
    }

    /**
     * Stable identity from OSM metadata (osm_type ∈ {N,W,R} + osm_id). When the
     * provider gives no OSM identity ( Photon can omit it), a deterministic
     * fallback is derived from the normalized label + rounded coordinates —
     * never from a collection index.
     */
    fun stablePlaceId(osmType: String?, osmId: Long?, displayName: String, lat: Double, lon: Double): String {
        val type = osmType?.trim()?.uppercase(Locale.US)
        if (!type.isNullOrBlank() && osmId != null && osmId != 0L) {
            return "osm:$type:$osmId"
        }
        val normalized = displayName.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        return "place:$normalized:${String.format(Locale.US, "%.5f", lat)}:${String.format(Locale.US, "%.5f", lon)}"
    }

    fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() &&
            lat >= -90.0 && lat <= 90.0 &&
            lon >= -180.0 && lon <= 180.0
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "")
        return value.takeIf { it.isNotBlank() }
    }
}
