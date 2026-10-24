package com.example.data.map

import com.example.data.model.LatLng
import kotlin.math.cos
import kotlin.math.sin

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

    /**
     * Computes the camera center for [newZoom] and [newHeading] such that the map
     * point under the pinch centroid ([anchorRotatedX], [anchorRotatedY] in rotated
     * screen space at [currentHeading]) projects to the same rotated screen position
     * after both zoom and heading change.
     *
     * Steps:
     *   1. Unrotate the centroid by [currentHeading] → unrotated position U_old.
     *   2. Rotate U_old by -deltaDeg around [anchorX, anchorY] → U_new (a +deltaDeg
     *      map rotation moves unrotated content by -deltaDeg around the anchor).
     *   3. Solve for new center at [newZoom] so anchorLatLng projects to U_new.
     *
     * Falls back to [anchoredCenter] (pure zoom, no rotation delta) when deltaDeg is
     * zero or when inputs are degenerate.
     */
    fun anchoredCenterWithRotation(
        currentCenterLat: Double,
        currentCenterLng: Double,
        currentZoom: Float,
        newZoom: Float,
        currentHeading: Float,
        newHeading: Float,
        anchorLatLng: LatLng,
        anchorRotatedX: Float,
        anchorRotatedY: Float,
        anchorX: Float,
        anchorY: Float,
        tileSizePx: Float
    ): LatLng {
        val fallback = LatLng(currentCenterLat, currentCenterLng)
        if (currentZoom <= 0f || newZoom <= 0f) return fallback
        if (!anchorLatLng.latitude.isFinite() || !anchorLatLng.longitude.isFinite()) return fallback
        if (tileSizePx <= 0f || !tileSizePx.isFinite()) return fallback
        if (!anchorRotatedX.isFinite() || !anchorRotatedY.isFinite() ||
            !anchorX.isFinite() || !anchorY.isFinite()
        ) return fallback

        val deltaDeg = (newHeading - currentHeading + 540f) % 360f - 180f // shortest arc
        if (deltaDeg == 0f) {
            return anchoredCenter(
                currentCenterLat, currentCenterLng, currentZoom, newZoom,
                anchorLatLng, anchorRotatedX, anchorRotatedY,
                anchorX, anchorY, tileSizePx
            )
        }

        // Step 1: unrotate the rotated centroid by currentHeading → U_old.
        val hRad = Math.toRadians(-currentHeading.toDouble())
        val cosH = cos(hRad).toFloat()
        val sinH = sin(hRad).toFloat()
        val dx = anchorRotatedX - anchorX
        val dy = anchorRotatedY - anchorY
        val uOldX = anchorX + dx * cosH - dy * sinH
        val uOldY = anchorY + dx * sinH + dy * cosH

        // Step 2: rotate U_old by -deltaDeg around anchor → U_new.
        val dRad = Math.toRadians(-deltaDeg.toDouble())
        val cosD = cos(dRad).toFloat()
        val sinD = sin(dRad).toFloat()
        val ux = uOldX - anchorX
        val uy = uOldY - anchorY
        val uNewX = anchorX + ux * cosD - uy * sinD
        val uNewY = anchorY + ux * sinD + uy * cosD

        // Step 3: solve for new center so anchorLatLng projects to U_new at newZoom.
        val anchorTileX = TileMath.lonToTileX(anchorLatLng.longitude, newZoom)
        val anchorTileY = TileMath.latToTileY(anchorLatLng.latitude, newZoom)
        val centerTileX = anchorTileX - (uNewX - anchorX) / tileSizePx
        val centerTileY = anchorTileY - (uNewY - anchorY) / tileSizePx
        return LatLng(
            TileMath.tileYToLat(centerTileY, newZoom),
            TileMath.tileXToLon(centerTileX, newZoom)
        )
    }
}
