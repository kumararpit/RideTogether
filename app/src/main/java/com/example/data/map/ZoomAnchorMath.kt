package com.example.data.map

import com.example.data.model.LatLng

/**
 * Anchored (gesture-centroid) zoom math for the FREE_EXPLORE/OVERVIEW pinch path.
 *
 * Contract (projection invariance): the geographic point under the pinch centroid
 * before the zoom must remain under the same centroid after the zoom, instead of
 * pivoting around the camera anchor. In FOLLOWING mode the rider marker is the
 * intended anchor, so the centroid path is intentionally not applied there —
 * follow-mode pinch uses [MapCameraController.setZoom] which never moves the center.
 */
object ZoomAnchorMath {

    /**
     * Minimum per-event log2 scale delta that qualifies as a real pinch. Below this
     * a two-finger event is treated as a pan (finger-jitter suppression), so a
     * two-finger pan can still exit follow mode instead of being misread as zoom.
     */
    const val PINCH_ZOOM_DELTA_LOG2 = 0.005f

    /**
     * Computes the camera center for [newZoom] such that [anchorLatLng] — the map
     * point that sat at unrotated screen position ([anchorUnrotatedX],
     * [anchorUnrotatedY]) at [currentZoom] with the current center — projects to
     * exactly the same screen position at [newZoom].
     *
     * Derived from the unrotated projection used by the canvas:
     *   x = anchorX + (tileX(point, zoom) - tileX(center, zoom)) * tileSizePx
     * Solved for the center tile coordinate at [newZoom].
     *
     * Falls back to the unchanged current center for degenerate inputs (invalid
     * anchor coordinates, non-positive zoom/tile size) rather than producing a
     * NaN center that would blank the map.
     */
    fun anchoredCenter(
        currentCenterLat: Double,
        currentCenterLng: Double,
        currentZoom: Float,
        newZoom: Float,
        anchorLatLng: LatLng,
        anchorUnrotatedX: Float,
        anchorUnrotatedY: Float,
        anchorX: Float,
        anchorY: Float,
        tileSizePx: Float
    ): LatLng {
        val fallback = LatLng(currentCenterLat, currentCenterLng)
        if (currentZoom <= 0f || newZoom <= 0f) return fallback
        if (!anchorLatLng.latitude.isFinite() || !anchorLatLng.longitude.isFinite()) return fallback
        if (tileSizePx <= 0f || !tileSizePx.isFinite() || !anchorUnrotatedX.isFinite() ||
            !anchorUnrotatedY.isFinite() || !anchorX.isFinite() || !anchorY.isFinite()
        ) return fallback

        val anchorTileX = TileMath.lonToTileX(anchorLatLng.longitude, newZoom)
        val anchorTileY = TileMath.latToTileY(anchorLatLng.latitude, newZoom)
        val centerTileX = anchorTileX - (anchorUnrotatedX - anchorX) / tileSizePx
        val centerTileY = anchorTileY - (anchorUnrotatedY - anchorY) / tileSizePx
        return LatLng(
            TileMath.tileYToLat(centerTileY, newZoom),
            TileMath.tileXToLon(centerTileX, newZoom)
        )
    }
}
