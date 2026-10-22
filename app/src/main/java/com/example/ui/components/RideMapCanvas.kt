package com.example.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.example.data.map.MapCameraMode
import com.example.data.map.OrientationMode
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.map.AmenityClustering
import com.example.ui.map.AmenityPalette
import com.example.ui.map.AmenitySelection
import com.example.ui.map.MapChromeInsets
import com.example.ui.map.MapLabelTheme
import com.example.ui.map.MapOverlayContract
import com.example.ui.map.MapOverlayLayer
import com.example.ui.map.RiderMarkerStyle
import com.example.ui.map.RouteRenderMath
import com.example.ui.map.ScaleBarMath
import kotlin.math.PI
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Route band palette (Phase B, backlog B-11) — RT-original colors, structured
 * after the measured reference *relationship* (dark casing + bright core +
 * light centre streak), not copied from any reference artwork.
 */
private val RouteCasing = Color(0xFF0C1017)   // brand slate — separates the line from pale tiles
private val RouteStreak = Color(0xFFFFE0A3)  // pale amber centre accent

/** How often the map publishes its frame statistics (5 s). */
private const val STATS_LOG_INTERVAL_NANOS = 5_000_000_000L

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun RideMapCanvas(
    route: List<LatLng>,
    /**
     * Vertex indices where a new route leg begins (Phase B, backlog B-11). The
     * renderer pins these vertices so simplification can never draw a straight
     * chord across a waypoint. Empty for legacy single-destination routes.
     */
    routeLegStartIndices: List<Int> = emptyList(),
    members: List<RiderMember>,
    selectedRider: RiderMember?,
    onSelectRider: (RiderMember?) -> Unit,
    isFollowRiderMode: Boolean = true,
    onFollowRiderChange: (Boolean) -> Unit = {},
    distanceToNextManeuverMeters: Double? = null,
    modifier: Modifier = Modifier,
    topInsetDp: androidx.compose.ui.unit.Dp = 120.dp,
    bottomInsetDp: androidx.compose.ui.unit.Dp = 140.dp,
    amenities: List<com.example.data.amenities.AmenityEntity> = emptyList(),
    selectedAmenity: com.example.data.amenities.AmenityEntity? = null,
    onSelectAmenity: (com.example.data.amenities.AmenityEntity?) -> Unit = {},
    /**
     * The amenities the rider has expanded out of a cluster. They are drawn as
     * single markers and are never merged again, so a tapped cluster stays
     * open while the rider looks at it.
     */
    expandedAmenityIds: Set<Long> = emptySet(),
    /** Called when the rider taps a cluster: the cluster's members are expanded. */
    onExpandAmenityCluster: (List<Long>) -> Unit = {},
    onViewportChanged: (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) -> Unit = { _, _, _, _ -> },
    initialStartLocation: LatLng? = null,
    onMapTapped: (() -> Unit)? = null,
    // Task 18 — Automatic day/night map appearance.
    // LIVE screen wiring: resolved lighting + theme override come from the ViewModel owner
    // and are stable state (never recomputed per draw/tile). Without wiring the canvas
    // preserves the legacy dark-default appearance and its local style toggle.
    mapLighting: com.example.data.map.MapLighting = com.example.data.map.MapLighting.NIGHT,
    allowThemeControl: Boolean = false,
    mapThemeMode: com.example.data.map.MapThemeMode = com.example.data.map.MapThemeMode.AUTO,
    onMapThemeModeChange: (com.example.data.map.MapThemeMode) -> Unit = {},
    onThemeResumed: () -> Unit = {},
    // Camera center reporting for AUTO lighting context gating (material-change throttled
    // on the receiving controller; sampled here only on ~0.1° moves).
    onLightingContext: (lat: Double, lng: Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = (event == Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentUser = members.find { it.isCurrentUser }
    val initialCenter = currentUser?.location ?: if (route.isNotEmpty()) route[0] else LatLng(18.5204, 73.8567)

    /**
     * Single owner of rider marker animation state: validates GPS targets from the
     * members flow and glides rendered position/bearing toward them frame-by-frame.
     * Consumed by the rideMapCanvas overlay only — never by camera logic or navigation.
     */
    val riderMarkerAnimator = remember { com.example.data.map.RiderMarkerAnimator() }
    var animatedMarkerFrame by remember { mutableIntStateOf(0) }

    // Target state updates: independent of animation frame timing (GPS flow cadence).
    // Invalid, stale, or inaccurate updates never reach the animator.
    LaunchedEffect(members) {
        for (rider in members) {
            if (rider.isStale()) continue                       // frozen target while stale
            if (rider.accuracyMeters > 30f) continue            // inaccurate fix → ignore
            riderMarkerAnimator.updateTarget(
                riderId = rider.id,
                lat = rider.location.latitude,
                lng = rider.location.longitude,
                bearingDeg = rider.headingDeg?.toDouble(),
                speedKmh = rider.speedKmh,
                accuracyMeters = rider.accuracyMeters
            )
        }
        riderMarkerAnimator.retain(members.map { it.id })
    }

    val cameraController = remember { com.example.data.map.MapCameraController() }
    val cameraState by cameraController.state.collectAsState()

    var displayCenterLat by remember { mutableFloatStateOf(initialCenter.latitude.toFloat()) }
    var displayCenterLng by remember { mutableFloatStateOf(initialCenter.longitude.toFloat()) }
    var displayZoom by remember { mutableFloatStateOf(if (isFollowRiderMode) 17f else if (route.isNotEmpty()) 16f else 14f) }
    var displayHeading by remember { mutableFloatStateOf(0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var displayAnchorY by remember { mutableFloatStateOf(0f) }
    // Legacy local style toggle for canvas instances without ViewModel theme wiring
    // (lobby/summary previews): preserves the previous dark-default behavior.
    var legacyDarkStyle by remember { mutableStateOf(true) }
    var hasInitialized by remember { mutableStateOf(false) }

    // Effective resolved appearance: stable param state when wired, legacy behavior otherwise.
    val effectiveLighting = if (allowThemeControl) {
        mapLighting
    } else {
        if (legacyDarkStyle) com.example.data.map.MapLighting.NIGHT else com.example.data.map.MapLighting.DAY
    }

    // Report lifecycle resumes so AUTO lighting re-evaluates after backgrounding.
    LaunchedEffect(isResumed, allowThemeControl) {
        if (isResumed && allowThemeControl) onThemeResumed()
    }
    // Report the camera center as lighting context on material change only (~0.1°).
    if (allowThemeControl) {
        LaunchedEffect(allowThemeControl) {
            snapshotFlow {
                val lat = displayCenterLat.toDouble()
                val lng = displayCenterLng.toDouble()
                val latBucket = (lat * 10).toInt()
                val lngBucket = (lng * 10).toInt()
                latBucket to lngBucket
            }.collect { (latBucket, lngBucket) ->
                onLightingContext(latBucket / 10.0, lngBucket / 10.0)
            }
        }
    }
    
    // Initialize map position on first launch or when route becomes available.
    // LiveRide launches with isFollowRiderMode == true and must lock directly into
    // FOLLOWING mode at navigation zoom — never into the Free-Pan route overview.
    LaunchedEffect(route, isFollowRiderMode) {
        if (!hasInitialized) {
            if (isFollowRiderMode) {
                val followCenter = currentUser?.location
                    ?: initialStartLocation
                    ?: route.firstOrNull()
                    ?: LatLng(18.5204, 73.8567)
                // D5 fix (INV-E): boot locks FOLLOWING in ONE controller write.
                // The old recenter()+setZoom(17f) pair wrote a 16f target that was
                // immediately replaced by 17f — a two-step zoom target at startup.
                cameraController.recenter(followCenter, currentUser?.headingDeg, 17f)
                hasInitialized = true
            } else if (route.isNotEmpty()) {
                // Lobby / summary previews only: overview the whole route once.
                cameraController.showOverview(route)
                hasInitialized = true
            }
        }
    }

    // Send location to controller
    LaunchedEffect(currentUser?.location, currentUser?.speedKmh, currentUser?.headingDeg, isFollowRiderMode, distanceToNextManeuverMeters) {
        if (currentUser != null && isFollowRiderMode) {
            val locUpdate = com.example.data.location.LocationUpdate(
                latLng = currentUser.location,
                speedKmh = currentUser.speedKmh,
                headingDeg = currentUser.headingDeg,
                accuracyMeters = 10f
            )
            cameraController.processLocation(locUpdate, distanceToNextManeuverMeters)
        }
    }

    // Phase B (backlog B-18): the map renders at a declared target rate and
    // collects frame statistics. The ANIMATION stays time-based (the damping
    // always uses the real elapsed time), so a skipped frame only means the
    // screen is sampled less often — the motion never slows down.
    val frameBudget = remember { com.example.ui.map.FrameBudget() }

    // Frame-paced display interpolation
    LaunchedEffect(Unit) {
        var lastTime = 0L
        var lastStatsLogNanos = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (!isResumed) {
                    lastTime = 0L
                } else {
                    if (lastTime != 0L) {
                        val dt = (frameTimeNanos - lastTime) / 1_000_000_000f
                        val tau = 0.25f
                        val alpha = 1f - kotlin.math.exp(-dt / tau)

                        val targetState = cameraController.state.value

                        // Phase B (B-18): the state is only published when the
                        // frame budget allows a draw. Publishing it is what
                        // invalidates the canvas, so this is the render rate.
                        val mayRender = frameBudget.shouldRender(frameTimeNanos)
                        if (mayRender) {
                            displayCenterLat += (targetState.center.latitude.toFloat() - displayCenterLat) * alpha
                            displayCenterLng += (targetState.center.longitude.toFloat() - displayCenterLng) * alpha
                            displayZoom += (targetState.zoom - displayZoom) * alpha

                            val targetHeading = targetState.effectiveHeading
                            val deltaH = ((targetHeading - displayHeading + 540f) % 360f) - 180f
                            displayHeading = (displayHeading + deltaH * alpha + 360f) % 360f
                        }

                        if (mayRender && (targetState.mode == MapCameraMode.FOLLOWING || targetState.mode == MapCameraMode.OVERVIEW)) {
                            // Smoothly fold residual panOffset into center lat/lng when recentering
                            if (kotlin.math.abs(panOffsetX) > 0.5f || kotlin.math.abs(panOffsetY) > 0.5f) {
                                val invScale = 1f / with(density) { 256.dp.toPx() } 
                                val unrotatedDx = (panOffsetX * cos(Math.toRadians(displayHeading.toDouble())) - panOffsetY * sin(Math.toRadians(displayHeading.toDouble()))).toFloat()
                                val unrotatedDy = (panOffsetX * sin(Math.toRadians(displayHeading.toDouble())) + panOffsetY * cos(Math.toRadians(displayHeading.toDouble()))).toFloat()
                                
                                val tileXDelta = unrotatedDx * invScale
                                val tileYDelta = unrotatedDy * invScale
                                
                                val currentTileX = TileMath.lonToTileX(displayCenterLng.toDouble(), displayZoom)
                                val currentTileY = TileMath.latToTileY(displayCenterLat.toDouble(), displayZoom)
                                
                                displayCenterLng = TileMath.tileXToLon(currentTileX - tileXDelta, displayZoom).toFloat()
                                displayCenterLat = TileMath.tileYToLat(currentTileY - tileYDelta, displayZoom).toFloat()
                                
                                panOffsetX = 0f
                                panOffsetY = 0f
                            } else {
                                panOffsetX = 0f
                                panOffsetY = 0f
                            }
                        }

                        // Rider marker glide: same frame-paced loop, same elapsed-frame time.
                        // Marker animation is render-only; camera center/zoom above are untouched.
                        riderMarkerAnimator.advance(dt.toDouble())
                        if (mayRender) {
                            animatedMarkerFrame = riderMarkerAnimator.frameVersion
                            // Phase B (B-18): the frame's work is timed against the
                            // budget so "frames over budget" is a real measurement.
                            frameBudget.recordFrameDuration((frameTimeNanos - lastTime) * 1_000_000L)
                            // The statistics are published periodically so they are
                            // observable in the field (logcat) instead of being
                            // collected and never read.
                            if (frameTimeNanos - lastStatsLogNanos > STATS_LOG_INTERVAL_NANOS) {
                                lastStatsLogNanos = frameTimeNanos
                                android.util.Log.d("RideMapCanvas", "FrameStats: ${frameBudget.stats().summary()}")
                            }
                        }
                    }
                    lastTime = frameTimeNanos
                }
            }
        }
    }

    // Emergency beacon pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "emergencyRadar")
    val radarRadiusFactor by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarRadius"
    )
    val radarAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAlpha"
    )

    val riderScreenPositions = remember { mutableMapOf<String, Offset>() }
    val amenityScreenPositions = remember { mutableMapOf<Long, Offset>() }
    /**
     * The visible amenity markers in draw order (bottom first).
     *
     * The tap handler resolves against this list instead of the amenity list,
     * so the marker the rider sees on top is the one that answers the tap, and
     * a cluster is tappable at all.
     */
    val amenityDrawnMarkers = remember { mutableListOf<AmenitySelection.Marker>() }
    // Painted footprint (screen-space bounds) of each drawn pin, recorded during draw
    // with the exact same geometry used to paint it, for icon-avoiding label placement.
    val amenityPaintedBounds = remember { mutableMapOf<Long, AmenityLabelPlanner.IconRect>() }

    // Phase B (backlog B-11): render-side route geometry cache — world-space
    // projection + per-tier simplification, recomputed only when the route or
    // the zoom tier changes (never per frame).
    val routeGeometryCache = remember { com.example.ui.map.RouteGeometryCache() }
    val routeRenderTier = com.example.ui.map.RouteRenderMath.tierForZoom(displayZoom)

    // Phase B (backlog B-12): the map captions are measured ONCE here instead of
    // per frame inside the draw phase.
    val startLabelLayout = remember {
        textMeasurer.measure(text = "START", style = MapLabelTheme.waypointStartStyle())
    }
    val finishLabelLayout = remember {
        textMeasurer.measure(text = "FINISH", style = MapLabelTheme.waypointFinishStyle())
    }

    // Pan-jitter gate for follow-exit (px at this density): how much cumulative
    // single-finger displacement must accumulate inside FOLLOWING before the map
    // treats the gesture as a deliberate pan and abandons follow. ~24 dp absorbs
    // finger-placement micro-drift so a pinch's first frames can't sabotage it.
    val followExitPanJitterPx = with(density) { 24.dp.toPx() }
    // Label anchors (screen-space, below each pin tip) written during draw; read only
    // inside the same draw phase, so a plain map is sufficient (not snapshot state).
    val amenityLabelAnchors = remember { mutableMapOf<Long, Float>() }
    // One-frame label visibility memory for zoom hysteresis (draw-phase only).
    val previousAmenityLabelIds = remember { HashSet<Long>() }

    val categoryPainters = com.example.data.amenities.AmenityType.entries.associateWith { type ->
        androidx.compose.ui.graphics.vector.rememberVectorPainter(AmenityIconConfig.getCategoryIcon(type))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark900)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val topInsetPx = with(density) { topInsetDp.toPx() }
        val bottomInsetPx = with(density) { bottomInsetDp.toPx() }
        
        val usableHeightPx = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(100f)
        
        val targetAnchorY = if (cameraState.mode == MapCameraMode.FOLLOWING) {
            topInsetPx + usableHeightPx * 0.65f
        } else {
            topInsetPx + usableHeightPx / 2f
        }
        
        // Initialize anchor on first pass
        if (displayAnchorY == 0f) {
            displayAnchorY = targetAnchorY
        } else if (kotlin.math.abs(targetAnchorY - displayAnchorY) > 0.5f) {
            // D5 fix (INV-B + INV-C pivot-stability): the anchor is a projection
            // PIVOT, not an animated channel. On a mode flip (FREE_EXPLORE ↔
            // FOLLOWING) it jumps in this single frame while the same frame
            // re-bases the display center so the map point currently rendered at
            // the old anchor keeps its screen position:
            //   screenY = anchorY + (tileY − centerTileY) · tileSize
            //   ⇒ centerTileY′ = centerTileY + Δanchor / tileSize
            // The old per-frame 0.15f anchor lerp animated the pivot at a rate
            // disconnected from the dt-based zoom/center/heading loop — the
            // second half of the recenter zoom-pop. No anchor clock remains:
            // every remaining animated channel shares the one dt-based loop.
            val anchorDeltaPx = targetAnchorY - displayAnchorY
            val baseTilePx = with(density) { 256.dp.toPx() }
            val centerTileY = TileMath.latToTileY(displayCenterLat.toDouble(), displayZoom)
            val newCenterTileY = centerTileY + (anchorDeltaPx / baseTilePx).toDouble()
            displayCenterLat = TileMath.tileYToLat(newCenterTileY, displayZoom).toFloat()
            displayAnchorY = targetAnchorY
        }
        
        val tileSizeDp = 256.dp
        val baseTileSizePx = with(density) { tileSizeDp.toPx() }

        // Camera Anchor using usable viewport
        val anchorX = widthPx / 2f
        val anchorY = displayAnchorY
        
        val pivotX = anchorX + panOffsetX

        val effectiveHeading = displayHeading

        // Task 18 — theme-driven tile appearance. The tile source is the fixed OSM
        // standard light carto, so night/dusk are renderer-level color transformations
        // (draw-time only — Coil caches raw bitmaps, so no cross-style contamination
        // and no cache clearing is required when the theme changes).
        val nightColorFilter = remember {
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -0.80f, 0f, 0f, 0f, 215f,
                0f, -0.75f, 0f, 0f, 220f,
                0f, 0f, -0.70f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        // Light/dark only: DAY passes tiles through unfiltered, NIGHT uses the matrix.
        val tileColorFilter = when (effectiveLighting) {
            com.example.data.map.MapLighting.DAY -> null
            com.example.data.map.MapLighting.NIGHT -> nightColorFilter
        }
        // Placeholder behind tiles must follow the lighting so loading/failed tiles no
        // longer appear as dark squares among light tiles (documented inconsistency fix).
        val tilePlaceholderColor = com.example.ui.theme.MapAppearancePalette.tilePlaceholder(effectiveLighting)

        // Center tile coordinates using continuous displayZoom for exact vector math
        val centerTileXDouble = TileMath.lonToTileX(displayCenterLng.toDouble(), displayZoom)
        val centerTileYDouble = TileMath.latToTileY(displayCenterLat.toDouble(), displayZoom)

        // Web Mercator coordinate conversion to Unrotated Screen Pixels
        fun latLngToUnrotatedScreen(latLng: LatLng): Offset {
            val tileX = TileMath.lonToTileX(latLng.longitude, displayZoom)
            val tileY = TileMath.latToTileY(latLng.latitude, displayZoom)
            val x = anchorX + ((tileX - centerTileXDouble) * baseTileSizePx).toFloat()
            val y = anchorY + ((tileY - centerTileYDouble) * baseTileSizePx).toFloat()
            return Offset(x, y)
        }

        // Coordinate conversion to Rotated Screen Pixels (for overlay markers and hit testing)
        fun unrotatedToRotatedScreen(unrotated: Offset): Offset {
            if (effectiveHeading == 0f) return unrotated
            val rad = Math.toRadians(-effectiveHeading.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            val dx = unrotated.x - anchorX
            val dy = unrotated.y - anchorY
            val rx = (anchorX + dx * cosA - dy * sinA).toFloat()
            val ry = (anchorY + dx * sinA + dy * cosA).toFloat()
            return Offset(rx, ry)
        }

        fun latLngToRotatedScreen(latLng: LatLng): Offset {
            return unrotatedToRotatedScreen(latLngToUnrotatedScreen(latLng))
        }

        // Inverse of unrotatedToRotatedScreen: converts a rotated overlay-screen point
        // back into unrotated map space (rotating by +heading undoes the map's -heading).
        fun rotatedToUnrotatedScreen(rotated: Offset): Offset {
            if (effectiveHeading == 0f) return rotated
            val rad = Math.toRadians(effectiveHeading.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            val dx = rotated.x - anchorX
            val dy = rotated.y - anchorY
            return Offset(
                (anchorX + dx * cosA - dy * sinA).toFloat(),
                (anchorY + dx * sinA + dy * cosA).toFloat()
            )
        }

        // Inverse of latLngToUnrotatedScreen: projects an unrotated screen pixel back
        // to geographic coordinates at the CURRENT display zoom (snapshot-fresh reads —
        // safe to call inside gesture callbacks between recompositions).
        fun unrotatedScreenToLatLng(x: Float, y: Float): LatLng {
            val zoom = displayZoom
            val centerTileX = TileMath.lonToTileX(displayCenterLng.toDouble(), zoom)
            val centerTileY = TileMath.latToTileY(displayCenterLat.toDouble(), zoom)
            val tileX = centerTileX + (x - anchorX) / baseTileSizePx
            val tileY = centerTileY + (y - anchorY) / baseTileSizePx
            return LatLng(TileMath.tileYToLat(tileY, zoom), TileMath.tileXToLon(tileX, zoom))
        }

        // 1. Single Camera Transform Container (Holds Tiles Layer & Route Lines Canvas)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = -effectiveHeading
                    transformOrigin = TransformOrigin(
                        anchorX / widthPx,
                        anchorY / heightPx
                    )
                }
        ) {
            // A. Real Slippy Map Tiles Layer with 100% Rotated Viewport Coverage
            val tileZoom = floor(displayZoom).toInt()
            val tileScale = Math.pow(2.0, (displayZoom - tileZoom).toDouble()).toFloat()
            val scaledTileSizePx = baseTileSizePx * tileScale
            val scaledTileSizeDp = tileSizeDp * tileScale

            val centerTileXAtTileZoom = TileMath.lonToTileX(displayCenterLng.toDouble(), tileZoom.toFloat())
            val centerTileYAtTileZoom = TileMath.latToTileY(displayCenterLat.toDouble(), tileZoom.toFloat())

            val tileRange = TileMath.calculateVisibleTileRange(
                widthPx = widthPx,
                heightPx = heightPx,
                anchorX = anchorX,
                anchorY = anchorY,
                centerTileXDouble = centerTileXAtTileZoom,
                centerTileYDouble = centerTileYAtTileZoom,
                zoomLevel = tileZoom,
                tileSizePx = scaledTileSizePx
            )

            // TEMPORARY D7 DIAGNOSTIC LOGGING (remove after root cause confirmed):
            // one line per stable range change: camera geometry + composed row bounds.
            val d7Slice = mutableListOf<Int>()
            for (tyx in tileRange.minY..tileRange.maxY) {
                val yUn = anchorY + ((tyx - centerTileYAtTileZoom) * scaledTileSizePx).toFloat()
                d7Slice.add((yUn + scaledTileSizePx).roundToInt())
            }
            android.util.Log.d(
                "RT-D7",
                "range z=%d disp=%.6f anchor=(%.1f,%.1f) cXY=(%.4f,%.4f) S=%.4f rowsY(top..bot)=%s lighting=%s phColor=%s".format(
                    tileZoom, displayZoom, anchorX, anchorY, centerTileXAtTileZoom, centerTileYAtTileZoom,
                    scaledTileSizePx, d7Slice, effectiveLighting, tilePlaceholderColor
                )
            )

            LaunchedEffect(tileRange, tileZoom) {
                val minLon = TileMath.tileXToLon(tileRange.minX.toDouble(), tileZoom.toFloat())
                val maxLon = TileMath.tileXToLon((tileRange.maxX + 1).toDouble(), tileZoom.toFloat())
                val maxLat = TileMath.tileYToLat(tileRange.minY.toDouble(), tileZoom.toFloat())
                val minLat = TileMath.tileYToLat((tileRange.maxY + 1).toDouble(), tileZoom.toFloat())
                onViewportChanged(minLat, maxLat, minLon, maxLon)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                for (tileX in tileRange.minX..tileRange.maxX) {
                    for (tileY in tileRange.minY..tileRange.maxY) {
                        key("$tileZoom/$tileX/$tileY") {
                            val tileUnrotatedX = anchorX + ((tileX - centerTileXAtTileZoom) * scaledTileSizePx).toFloat()
                            val tileUnrotatedY = anchorY + ((tileY - centerTileYAtTileZoom) * scaledTileSizePx).toFloat()

                            val tileUrl = TileMath.getOsmTileUrl(tileZoom, tileX, tileY)
                            
                            // Calculate exact layout bounds rounding mathematically to avoid sub-pixel gaps (seams)
                            // We use Math.ceil for the size and strict float rounding for the offset to guarantee 
                            // neighboring tiles snap perfectly edge-to-edge without 1px gaps when scaled.
                            val left = tileUnrotatedX.roundToInt()
                            val top = tileUnrotatedY.roundToInt()
                            val right = (tileUnrotatedX + scaledTileSizePx).roundToInt()
                            val bottom = (tileUnrotatedY + scaledTileSizePx).roundToInt()

                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(left, top) }
                                    .size(
                                        width = with(density) { (right - left).toDp() }, 
                                        height = with(density) { (bottom - top).toDp() }
                                    )
                                    .clip(androidx.compose.ui.graphics.RectangleShape)
                                    .background(tilePlaceholderColor)
                            ) {
                                // Fallback layer (multi-level ancestor tiles) to prevent black stripes.
                                // The exact tile AsyncImage below always paints over these once loaded.
                                if (tileZoom > 3) {
                                    // 1. Cached coarse ancestors (deltaZ 4..2): only files already in the
                                    //    Coil memory cache are used, so no extra tile-server requests.
                                    val memoryCache = imageLoader.memoryCache
                                    val cachedAncestors = TileMath.ancestorFallbackChain(tileZoom, tileX, tileY, minZoom = 3, maxDeltaZ = 4)
                                        .filter { anc -> anc.deltaZ >= 2 }
                                        .filter { anc ->
                                            try {
                                                memoryCache?.get(coil.memory.MemoryCache.Key(TileMath.getOsmTileUrl(anc.zoom, anc.x, anc.y))) != null
                                            } catch (_: Exception) {
                                                false
                                            }
                                        }
                                    for (anc in cachedAncestors) {
                                        val ancTileW = right - left
                                        val ancTileH = bottom - top
                                        val ancFullW = ancTileW * (1 shl anc.deltaZ)
                                        val ancFullH = ancTileH * (1 shl anc.deltaZ)
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(TileMath.getOsmTileUrl(anc.zoom, anc.x, anc.y))
                                                .addHeader("User-Agent", TileMath.OSM_USER_AGENT)
                                                .crossfade(false)
                                                .build(),
                                            contentDescription = "Ancestor map tile fallback (z${anc.zoom})",
                                            contentScale = ContentScale.FillBounds,
                                            colorFilter = tileColorFilter,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clipToBounds()
                                                // Offset the full ancestor bitmap so its enclosing
                                                // sub-quadrant (subX, subY) aligns exactly over this tile slot.
                                                .offset { IntOffset(-anc.subX * ancTileW, -anc.subY * ancTileH) }
                                                .size(
                                                    width = with(density) { ancFullW.toDp() },
                                                    height = with(density) { ancFullH.toDp() }
                                                )
                                        )
                                    }

                                    // 2. Immediate parent (deltaZ = 1) — always requested so it warms the
                                    //    cache and acts as first-line fallback while the exact tile loads.
                                    val parentTileX = tileX / 2
                                    val parentTileY = tileY / 2
                                    val parentTileUrl = TileMath.getOsmTileUrl(tileZoom - 1, parentTileX, parentTileY)

                                    val parentOffsetX = if (tileX % 2 == 0) 0 else -(right - left)
                                    val parentOffsetY = if (tileY % 2 == 0) 0 else -(bottom - top)

                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(parentTileUrl)
                                            .addHeader("User-Agent", TileMath.OSM_USER_AGENT)
                                            .crossfade(false)
                                            .build(),
                                        contentDescription = "Parent map tile fallback",
                                        contentScale = ContentScale.FillBounds,
                                        colorFilter = tileColorFilter,
                                        modifier = Modifier
                                            .offset { IntOffset(parentOffsetX, parentOffsetY) }
                                            .size(
                                                width = with(density) { ((right - left) * 2).toDp() },
                                                height = with(density) { ((bottom - top) * 2).toDp() }
                                            )
                                    )
                                }

                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(tileUrl)
                                        .addHeader("User-Agent", TileMath.OSM_USER_AGENT)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Map tile",
                                    contentScale = ContentScale.FillBounds,
                                    colorFilter = tileColorFilter,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // B. Map Vector Elements (Route Line & Start/Finish Points inside rotated container)
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (route.size >= 2) {
                    // Render-side geometry cache (Phase B, backlog B-11): the route is
                    // projected into a fixed world space (tile-17) once per route, and
                    // simplified per zoom tier. Per frame this is now an affine
                    // transform plus three stroke passes — no per-point projection,
                    // no per-frame list/Path allocation of the full OSRM polyline.
                    routeGeometryCache.ensure(
                        route = route,
                        tier = routeRenderTier,
                        tileSizePx = baseTileSizePx,
                        legStartIndices = routeLegStartIndices
                    )
                    val world = routeGeometryCache.worldVertices
                    val kept = routeGeometryCache.keptIndices

                    if (world.isNotEmpty() && kept.size >= 2) {
                        val transform = RouteRenderMath.worldToScreenTransform(
                            displayZoom = displayZoom,
                            centerLat = displayCenterLat.toDouble(),
                            centerLng = displayCenterLng.toDouble(),
                            anchorX = anchorX,
                            anchorY = anchorY,
                            tileSizePx = baseTileSizePx
                        )

                        val routePath = Path()
                        val firstIdx = kept[0]
                        routePath.moveTo(
                            transform.mapX(world[firstIdx * 2]),
                            transform.mapY(world[firstIdx * 2 + 1])
                        )
                        for (k in 1 until kept.size) {
                            val idx = kept[k]
                            routePath.lineTo(
                                transform.mapX(world[idx * 2]),
                                transform.mapY(world[idx * 2 + 1])
                            )
                        }

                        val coreWidthPx = with(density) { RouteRenderMath.coreWidthDp(displayZoom).dp.toPx() }
                        val casingWidthPx = with(density) { RouteRenderMath.casingWidthDp(displayZoom).dp.toPx() }
                        val streakWidthPx = with(density) { RouteRenderMath.streakWidthDp(displayZoom).dp.toPx() }

                        // 1. Soft outer shadow (separation from pale daytime imagery)
                        drawPath(
                            path = routePath,
                            color = Color(0x33000000),
                            style = Stroke(width = casingWidthPx + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 2. Dark casing — RT brand slate, high separation on both
                        //    the light day raster and the dark night raster.
                        drawPath(
                            path = routePath,
                            color = RouteCasing,
                            style = Stroke(width = casingWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 3. Core — brand amber, width scales with zoom (18 → 17 dp).
                        drawPath(
                            path = routePath,
                            color = AmberPrimary,
                            style = Stroke(width = coreWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 4. Light centre streak (RT-original pale amber accent).
                        drawPath(
                            path = routePath,
                            color = RouteStreak,
                            style = Stroke(width = streakWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 5. Direction dots — spaced by SCREEN distance (not vertex
                        //    count), so the cadence is zoom-independent.
                        val dotSpacingPx = RouteRenderMath.directionDotSpacingPx(displayZoom)
                        if (dotSpacingPx > 0f) {
                            var accumulated = dotSpacingPx
                            var prevX = 0f
                            var prevY = 0f
                            for (k in 1 until kept.size) {
                                val idx = kept[k]
                                val x = transform.mapX(world[idx * 2])
                                val y = transform.mapY(world[idx * 2 + 1])
                                if (k == 1) {
                                    prevX = x
                                    prevY = y
                                    continue
                                }
                                val dx = x - prevX
                                val dy = y - prevY
                                val segment = kotlin.math.hypot(dx, dy)
                                if (accumulated + segment >= dotSpacingPx) {
                                    drawCircle(color = Color.White, radius = 2.6.dp.toPx(), center = Offset(x, y))
                                    drawCircle(color = RouteStreak, radius = 1.4.dp.toPx(), center = Offset(x, y))
                                    accumulated = 0f
                                } else {
                                    accumulated += segment
                                }
                                prevX = x
                                prevY = y
                            }
                        }
                    }

                    // Start Marker
                    val startPos = initialStartLocation ?: route.firstOrNull()
                    if (startPos != null) {
                        val startScreen = latLngToUnrotatedScreen(startPos)
                        drawCircle(color = Color(0x664CAF50), radius = 16.dp.toPx(), center = startScreen)
                        drawCircle(color = StatusRidingGreen, radius = 10.dp.toPx(), center = startScreen)
                        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = startScreen)
                    }

                    // Destination Marker
                    val destScreen = latLngToUnrotatedScreen(route.last())
                    drawCircle(color = Color(0x66F44336), radius = 18.dp.toPx(), center = destScreen)
                    drawCircle(color = StatusEmergencyRed, radius = 11.dp.toPx(), center = destScreen)
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = destScreen)
                }
            }
        }

        // Phase B (backlog B-17): the map surface carries a short accessibility
        // description — the rider/amenity markers are painted on the canvas and
        // are therefore invisible to screen readers, while the turn instructions
        // live in the real Text composables of the banner (already readable).
        val mapContentDescription = remember(route.size, members.size) {
            buildString {
                append("Map. ")
                append(members.size)
                append(if (members.size == 1) " rider" else " riders")
                append(" shown. Turn instructions are in the navigation panel above.")
            }
        }

        // 2. Unrotated Overlay Canvas (Rider Markers, Upright Labels, Gestures)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = mapContentDescription }
                .testTag("ride_map_canvas")
                .pointerInput(effectiveHeading) {
                    // Cumulative single-finger pan swallowed while FOLLOWING before
                    // the gesture counts as a deliberate pan→follow-exit. Covers the
                    // finger-placement drift that otherwise sabotages every pinch
                    // (the first finger's few-px micro-pan must not exit follow).
                    var microPanAccumPx = 0f
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val zoomDeltaLog2 = kotlin.math.log2(zoom)
                        val hasPinchZoom = kotlin.math.abs(zoomDeltaLog2) >= com.example.data.map.ZoomAnchorMath.PINCH_ZOOM_DELTA_LOG2
                        if (hasPinchZoom) {
                            microPanAccumPx = 0f
                            val newZoom = (displayZoom + zoomDeltaLog2).coerceIn(3f, 18f)
                            if (newZoom != displayZoom) {
                                val following = cameraController.state.value.mode == MapCameraMode.FOLLOWING
                                if (following) {
                                    // Defect D2 fix: a pinch inside FOLLOWING only changes the
                                    // zoom level. The camera stays in FOLLOWING (rider stays
                                    // anchored at the camera anchor) and the user's zoom
                                    // override persists until the next recenter.
                                    cameraController.setZoom(newZoom)
                                } else {
                                    // Defect D3 fix: outside FOLLOWING, zoom pivots on the
                                    // pinch centroid — the map point under the fingers stays
                                    // under the fingers instead of flying around the anchor.
                                    val unrotated = rotatedToUnrotatedScreen(centroid)
                                    val anchorLatLng = unrotatedScreenToLatLng(unrotated.x, unrotated.y)
                                    val anchoredCenter = com.example.data.map.ZoomAnchorMath.anchoredCenter(
                                        currentCenterLat = displayCenterLat.toDouble(),
                                        currentCenterLng = displayCenterLng.toDouble(),
                                        currentZoom = displayZoom,
                                        newZoom = newZoom,
                                        anchorLatLng = anchorLatLng,
                                        anchorUnrotatedX = unrotated.x,
                                        anchorUnrotatedY = unrotated.y,
                                        anchorX = anchorX,
                                        anchorY = anchorY,
                                        tileSizePx = baseTileSizePx
                                    )
                                    cameraController.setZoomFree(newZoom, anchoredCenter)
                                    displayCenterLat = anchoredCenter.latitude.toFloat()
                                    displayCenterLng = anchoredCenter.longitude.toFloat()
                                    onFollowRiderChange(false)
                                }
                                displayZoom = newZoom
                            }
                        }
                        if (pan != Offset.Zero && !hasPinchZoom) {
                            val followingMode = cameraController.state.value.mode == MapCameraMode.FOLLOWING
                            if (followingMode) {
                                microPanAccumPx = (microPanAccumPx + pan.getDistance())
                                    .coerceAtMost(followExitPanJitterPx * 2f)
                                if (microPanAccumPx >= followExitPanJitterPx) {
                                    // Deliberate pan confirmed: exit follow, then pan freely.
                                    cameraController.onUserGesture()
                                    onFollowRiderChange(false)
                                    cameraController.pan(pan.x, pan.y, displayZoom, effectiveHeading, baseTileSizePx)
                                    val targetState = cameraController.state.value
                                    displayCenterLat = targetState.center.latitude.toFloat()
                                    displayCenterLng = targetState.center.longitude.toFloat()
                                }
                                // Below threshold: fragment swallowed — the following
                                // camera feed keeps the rider anchored (jitter-free).
                            } else {
                                cameraController.pan(pan.x, pan.y, displayZoom, effectiveHeading, baseTileSizePx)
                                val targetState = cameraController.state.value
                                displayCenterLat = targetState.center.latitude.toFloat()
                                displayCenterLng = targetState.center.longitude.toFloat()
                                cameraController.onUserGesture()
                                onFollowRiderChange(false)
                            }
                        }
                    }
                }
                .pointerInput(members, amenities) {
                    detectTapGestures { tapOffset ->
                        var clickedRider: RiderMember? = null
                        // Phase B (backlog B-07): density-correct touch target.
                        // The previous 70f was a raw pixel constant (≈23 dp only on
                        // a 3× device, shrinking on denser screens); the reference
                        // product uses a 48 dp floor.
                        val riderTapRadiusPx = with(density) { 24.dp.toPx() }
                        // Phase B (backlog B-16): the SELECTED rider is tested
                        // first, then the remaining riders by list order. Without
                        // this, a selected rider covered by another rider could
                        // not be tapped at all.
                        val orderedForHit = if (selectedRider != null) {
                            listOf(selectedRider) + members.filter { it.id != selectedRider.id }
                        } else {
                            members
                        }
                        for (member in orderedForHit) {
                            val pos = riderScreenPositions[member.id] ?: continue
                            val distance = (tapOffset - pos).getDistance()
                            if (distance < riderTapRadiusPx) {
                                clickedRider = member
                                break
                            }
                        }
                        if (clickedRider != null) {
                            onSelectRider(clickedRider)
                            onSelectAmenity(null)
                        } else {
                            val tapTargetRadius = with(density) { 24.dp.toPx() } // Minimum 48dp tap target
                            // The tap resolves to the **topmost** marker under it
                            // (the one the rider actually sees), not to the first
                            // one in the data list: a marker that is drawn under
                            // another must not steal the tap. A cluster answers
                            // with an expansion, a single marker with a
                            // selection.
                            val outcome = AmenitySelection.resolveTap(
                                tapX = tapOffset.x,
                                tapY = tapOffset.y,
                                markers = amenityDrawnMarkers.toList(),
                                radiusPx = tapTargetRadius
                            )
                            when (outcome) {
                                is AmenitySelection.TapOutcome.Picked -> {
                                    val picked = amenities.firstOrNull { it.osmId == outcome.amenityId }
                                    onSelectAmenity(picked)
                                    if (picked != null) onSelectRider(null)
                                }
                                is AmenitySelection.TapOutcome.Expanded -> {
                                    onExpandAmenityCluster(outcome.memberIds)
                                }
                                is AmenitySelection.TapOutcome.Nothing -> {
                                    onSelectAmenity(null)
                                    onMapTapped?.invoke()
                                }
                            }
                        }
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height

            // Safe text drawing helper
            fun drawSafeText(
                text: String,
                x: Float,
                y: Float,
                style: TextStyle
            ) {
                if (x < -300f || x > canvasW + 150f || y < -100f || y > canvasH + 100f) {
                    return
                }
                val layout = textMeasurer.measure(
                    text = text,
                    style = style
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, y)
                )
            }

            /**
             * Halo text (Phase B, backlog B-12): the same 8-direction offset
             * technique as the reference's stroke+fill halo, with RT-original
             * ink. Keeps the map captions legible over pale daytime imagery and
             * bright night tiles alike. The layout is measured once in
             * composition, so no text measurement happens per frame.
             */
            fun drawHaloText(layout: androidx.compose.ui.text.TextLayoutResult, x: Float, y: Float) {
                if (x < -300f || x > canvasW + 150f || y < -100f || y > canvasH + 100f) {
                    return
                }
                val radiusPx = with(density) { MapLabelTheme.HALO_RADIUS_DP.dp.toPx() }
                val offsets = MapLabelTheme.haloOffsets(radiusPx)
                var i = 0
                while (i < offsets.size) {
                    val dx = offsets[i]
                    val dy = offsets[i + 1]
                    translate(dx, dy) {
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(x, y),
                            color = MapLabelTheme.HALO_COLOR
                        )
                    }
                    i += 2
                }
                drawText(textLayoutResult = layout, topLeft = Offset(x, y))
            }

            // Draw Start & Finish text tags upright at their rotated screen positions
            if (route.size >= 2) {
                val startPos = initialStartLocation ?: route.first()
                val startRotated = latLngToRotatedScreen(startPos)
                drawHaloText(
                    layout = startLabelLayout,
                    x = startRotated.x - 20f,
                    y = startRotated.y + 14f
                )

                val destRotated = latLngToRotatedScreen(route.last())
                drawHaloText(
                    layout = finishLabelLayout,
                    x = destRotated.x - 22f,
                    y = destRotated.y - 28f
                )
            }

            // Draw Custom Google Maps Style Amenity Pins
            amenityScreenPositions.clear()
            amenityLabelAnchors.clear()
            amenityPaintedBounds.clear()
            amenityDrawnMarkers.clear()
            // Phase B (backlog B-14): dense amenities are CLUSTERED — one marker
            // is drawn per group (with a count badge), which keeps a dense area
            // readable instead of turning into a pin field. The clustering is
            // deterministic and order independent (see AmenityClustering).
            //
            // A cluster the rider has tapped stays open: its members are
            // excluded from the grouping and are drawn as single markers.
            val visibleAmenityPositions = ArrayList<Pair<Long, Pair<Float, Float>>>()
            for (amenity in amenities) {
                val pos = latLngToRotatedScreen(LatLng(amenity.lat, amenity.lon))
                if (pos.x < -100f || pos.x > canvasW + 100f ||
                    pos.y < -100f || pos.y > canvasH + 100f
                ) {
                    continue
                }
                visibleAmenityPositions.add(amenity.osmId to (pos.x to pos.y))
            }
            val amenityClusters = AmenityClustering.cluster(
                points = visibleAmenityPositions,
                maxDistancePx = 30f,
                expandedIds = expandedAmenityIds
            )
            val amenitiesById = amenities.associateBy { it.osmId }
            // The selected amenity always gets its own marker (never hidden in a
            // cluster) and is drawn last, on top of everything else.
            val selectedCluster = amenityClusters.firstOrNull { cluster ->
                selectedAmenity != null && cluster.memberIds.contains(selectedAmenity.osmId)
            }
            val drawnAmenityIds = LinkedHashSet<Long>()
            amenityClusters.forEach { cluster ->
                cluster.memberIds.forEach { drawnAmenityIds.add(it) }
            }
            val drawOrder = amenityClusters
                .filter { selectedCluster == null || it.representativeId != selectedCluster.representativeId }
                .toMutableList()
            if (selectedCluster != null) drawOrder.add(selectedCluster)

            var amenityDrawIndex = 0
            for (cluster in drawOrder) {
                val drawnId = if (selectedCluster != null && cluster.representativeId == selectedCluster.representativeId) {
                    selectedAmenity?.osmId ?: cluster.representativeId
                } else {
                    cluster.representativeId
                }
                val amenity = amenitiesById[drawnId] ?: continue
                val groupCount = cluster.count

                val screenPos = latLngToRotatedScreen(LatLng(amenity.lat, amenity.lon))

                val isSelected = amenity.osmId == selectedAmenity?.osmId
                // When a place is selected, the other markers are dimmed: the
                // selection has to read at a glance, and the rider must still
                // see what else is around it.
                val markerAlpha = AmenitySelection.markerAlpha(
                    isSelected = isSelected,
                    hasSelection = selectedAmenity != null
                )
                val basePinSizeDp = when {
                    displayZoom >= 17f -> 32.dp
                    displayZoom >= 15f -> 28.dp
                    displayZoom >= 13f -> 24.dp
                    else -> 20.dp
                }
                val pinSizeDp = if (isSelected) 38.dp else basePinSizeDp
                val pinSizePx = pinSizeDp.toPx()
                val pinRadiusPx = pinSizePx / 2f
                val tipHeightPx = 4.dp.toPx()
                // Phase B (B-14): the RT-original palette, with a day and a night
                // variant so the marker stays separable from the raster in both
                // themes.
                val categoryColor = AmenityPalette.colorFor(
                    amenity.type,
                    if (effectiveLighting == com.example.data.map.MapLighting.NIGHT) {
                        AmenityPalette.Theme.NIGHT
                    } else {
                        AmenityPalette.Theme.DAY
                    }
                )

                val cx = screenPos.x
                val cy = screenPos.y - pinRadiusPx - tipHeightPx

                // Save visible pin head center for hit-testing alignment
                amenityScreenPositions[amenity.osmId] = Offset(cx, cy)

                // The marker as the rider sees it: the tap handler resolves
                // against these, so a cluster is tappable and the topmost
                // marker is the one that answers a tap.
                amenityDrawnMarkers.add(
                    AmenitySelection.Marker(
                        id = amenity.osmId,
                        x = cx,
                        y = cy,
                        isCluster = groupCount > 1,
                        memberIds = cluster.memberIds,
                        drawIndex = amenityDrawIndex
                    )
                )
                amenityDrawIndex++

                // 1. Selected Focus Halo / Glow Ring
                if (isSelected) {
                    drawCircle(
                        color = categoryColor.copy(alpha = 0.35f),
                        radius = pinRadiusPx + 8.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = pinRadiusPx + 4.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }

                // 2. Base Drop Shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = pinRadiusPx * 0.45f,
                    center = Offset(screenPos.x, screenPos.y + 1.dp.toPx())
                )

                // 3. Teardrop Map Pin Body
                val pinPath = Path().apply {
                    moveTo(screenPos.x, screenPos.y)
                    lineTo(cx + pinRadiusPx * 0.707f, cy + pinRadiusPx * 0.707f)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(cx - pinRadiusPx, cy - pinRadiusPx, cx + pinRadiusPx, cy + pinRadiusPx),
                        startAngleDegrees = 45f,
                        sweepAngleDegrees = 270f,
                        forceMoveTo = false
                    )
                    lineTo(screenPos.x, screenPos.y)
                    close()
                }

                drawPath(path = pinPath, color = categoryColor.copy(alpha = markerAlpha))
                // Dual outline: dark rim first (separates the marker from pale daytime
                // imagery where a white border alone is invisible), then bright inner rim.
                drawPath(path = pinPath, color = Color(0x66101828).copy(alpha = markerAlpha), style = Stroke(width = 4.6.dp.toPx()))
                drawPath(path = pinPath, color = Color.White.copy(alpha = markerAlpha), style = Stroke(width = 2.1.dp.toPx()))

                // 4. White Category Icon Glyph
                val painter = categoryPainters[amenity.type] ?: categoryPainters[com.example.data.amenities.AmenityType.UNKNOWN]!!
                val glyphSize = pinRadiusPx * 1.25f
                drawContext.transform.translate(left = cx - glyphSize / 2f, top = cy - glyphSize / 2f)
                try {
                    with(painter) {
                        draw(
                            size = androidx.compose.ui.geometry.Size(glyphSize, glyphSize),
                            colorFilter = ColorFilter.tint(Color.White.copy(alpha = markerAlpha))
                        )
                    }
                } finally {
                    drawContext.transform.translate(left = -(cx - glyphSize / 2f), top = -(cy - glyphSize / 2f))
                }

                // Phase B (B-14): a cluster is drawn as ONE marker with a count
                // badge, so a dense area stays readable.
                if (groupCount > 1) {
                    val badgeRadius = (pinRadiusPx * 0.62f).coerceAtLeast(7.dp.toPx())
                    drawCircle(
                        color = Color(0xFF0C1017),
                        radius = badgeRadius,
                        center = Offset(cx + pinRadiusPx * 0.72f, cy - pinRadiusPx * 0.72f)
                    )
                    val badgeLayout = textMeasurer.measure(
                        text = groupCount.toString(),
                        style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    )
                    drawText(
                        textLayoutResult = badgeLayout,
                        topLeft = Offset(
                            cx + pinRadiusPx * 0.72f - badgeLayout.size.width / 2f,
                            cy - pinRadiusPx * 0.72f - badgeLayout.size.height / 2f
                        )
                    )
                }

                // Label anchor (screen-space) just below the pin tip for Part B labels.
                amenityLabelAnchors[amenity.osmId] = screenPos.y + 3.dp.toPx()

                // Painted footprint for icon-avoiding label placement, derived from the
                // exact values used above (not arbitrary padding): pin head circle
                // center (cx, cy) radius pinRadiusPx; outline stroke half-width 2.3.dp
                // beyond path bounds, or the selected focus halo radius 8.dp; drop
                // shadow under the tip at +1.dp with radius 0.45 x pinRadiusPx.
                val footprintPx = pinRadiusPx +
                    (if (isSelected) 8.dp.toPx() else 2.3.dp.toPx())
                amenityPaintedBounds[amenity.osmId] = AmenityLabelPlanner.IconRect(
                    id = amenity.osmId,
                    left = cx - footprintPx,
                    top = cy - footprintPx,
                    right = cx + footprintPx,
                    bottom = screenPos.y + 1.dp.toPx() + pinRadiusPx * 0.45f
                )
            }

            // Task 18 Part B — Amenity name labels: deterministic planner (zoom gate +
            // hysteresis + priority + collision). Labels draw BEFORE rider markers so they
            // can never obscure the current rider or navigation indicators; geographic
            // coordinates are never modified, only screen-space suppression.
            val labelReserveHalfW = 62.dp.toPx()
            val labelReserveHalfH = 11.dp.toPx()
            val labelCandidates = buildList {
                for (amenity in amenities) {
                    val anchorY = amenityLabelAnchors[amenity.osmId] ?: continue
                    val head = amenityScreenPositions[amenity.osmId] ?: continue
                    val name = amenity.name?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    add(
                        AmenityLabelPlanner.Candidate(
                            id = amenity.osmId,
                            xPx = head.x,
                            yPx = anchorY,
                            isSelected = amenity.osmId == selectedAmenity?.osmId,
                            type = amenity.type
                        )
                    )
                }
            }
            if (labelCandidates.isNotEmpty()) {
                val plannedLabelIds = AmenityLabelPlanner.plan(
                    candidates = labelCandidates,
                    zoom = displayZoom,
                    previouslyVisible = previousAmenityLabelIds,
                    reserveHalfWidthPx = labelReserveHalfW,
                    reserveHalfHeightPx = labelReserveHalfH,
                    iconRects = amenityPaintedBounds.values.toList()
                )
                // Phase B (backlog B-17): the map chrome (maneuver banner, trip
                // HUD, control column) is PUBLISHED to the renderer, and a label
                // that would sit under it is suppressed. The reference does the
                // same natively (it passes the banner height and the bottom-sheet
                // exposure into the renderer every frame).
                val chromeInsets = MapChromeInsets(
                    topPx = if (MapOverlayContract.isChrome(MapOverlayLayer.MANEUVER_BANNER)) topInsetPx else 0f,
                    bottomPx = if (MapOverlayContract.isChrome(MapOverlayLayer.TRIP_HUD)) bottomInsetPx else 0f,
                    rightPx = if (MapOverlayContract.isChrome(MapOverlayLayer.MAP_CONTROLS)) {
                        with(density) { 64.dp.toPx() }
                    } else {
                        0f
                    }
                )
                val chromeFreeLabelIds = plannedLabelIds.filter { id ->
                    val head = amenityScreenPositions[id] ?: return@filter false
                    val anchorY = amenityLabelAnchors[id] ?: return@filter false
                    !chromeInsets.overlaps(
                        x = head.x - labelReserveHalfW,
                        y = anchorY,
                        width = labelReserveHalfW * 2f,
                        height = labelReserveHalfH * 2f,
                        viewportWidth = canvasW,
                        viewportHeight = canvasH
                    )
                }

                val pillBackground = com.example.ui.theme.MapAppearancePalette
                    .amenityLabelBackground(effectiveLighting)
                val labelForeground = com.example.ui.theme.MapAppearancePalette
                    .amenityLabelText(effectiveLighting)
                val maxTextWidthPx = (labelReserveHalfW * 2f - 12.dp.toPx()).roundToInt()
                for (amenity in amenities) {
                    if (amenity.osmId !in chromeFreeLabelIds) continue
                    val head = amenityScreenPositions[amenity.osmId] ?: continue
                    val anchorY = amenityLabelAnchors[amenity.osmId] ?: continue
                    val name = amenity.name?.trim() ?: continue
                    val labelLayout = textMeasurer.measure(
                        text = androidx.compose.ui.text.AnnotatedString(name),
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = labelForeground
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxTextWidthPx)
                    )
                    val pillW = labelLayout.size.width + 10.dp.toPx()
                    val pillH = labelLayout.size.height + 5.dp.toPx()
                    drawRoundRect(
                        color = pillBackground,
                        topLeft = Offset(head.x - pillW / 2f, anchorY),
                        size = androidx.compose.ui.geometry.Size(pillW, pillH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = labelLayout,
                        topLeft = Offset(
                            head.x - labelLayout.size.width / 2f,
                            anchorY + (pillH - labelLayout.size.height) / 2f
                        )
                    )
                }
                previousAmenityLabelIds.clear()
                previousAmenityLabelIds.addAll(chromeFreeLabelIds)
            } else {
                previousAmenityLabelIds.clear()
            }

            // Draw Rider Markers at rotated screen positions
            riderScreenPositions.clear()
            // Subscribe this draw scope to the frame-paced marker animation (draw-only
            // invalidation; no recomposition of the map composition tree per frame).
            @Suppress("UNUSED_EXPRESSION")
            animatedMarkerFrame

            // Phase B (backlog B-16): pre-pass that computes every rider's screen
            // position, groups the overlapping ones and derives a deterministic
            // offset per rider. The offsets are derived from the rider id, so they
            // are stable across frames and independent of the member list order.
            val riderPositionsForClustering = ArrayList<Pair<String, Pair<Float, Float>>>()
            for (rider in members) {
                val animLat0 = riderMarkerAnimator.renderedLat(rider.id)
                val animLng0 = riderMarkerAnimator.renderedLng(rider.id)
                val markerLatLng0 = if (animLat0 != null && animLng0 != null) LatLng(animLat0, animLng0) else rider.location
                riderPositionsForClustering.add(rider.id to (latLngToRotatedScreen(markerLatLng0).x to latLngToRotatedScreen(markerLatLng0).y))
            }
            val riderStackOffsets: Map<String, Pair<Float, Float>> = if (members.size > 1) {
                val clusters = RiderMarkerStyle.clusterRiders(riderPositionsForClustering, maxDistancePx = 34f)
                val perClusterIndex = HashMap<Int, Int>()
                val offsets = HashMap<String, Pair<Float, Float>>()
                for ((id, pos) in riderPositionsForClustering) {
                    val cluster = clusters[id] ?: 0
                    val idx = perClusterIndex.getOrDefault(cluster, 0)
                    perClusterIndex[cluster] = idx + 1
                    offsets[id] = RiderMarkerStyle.stackingOffset(id, idx, 2)
                }
                offsets
            } else {
                emptyMap()
            }

            for (rider in members) {
                // Rendered (interpolated) position/bearing from the marker animator;
                // falls back to the raw member location until the first valid target.
                val animLat = riderMarkerAnimator.renderedLat(rider.id)
                val animLng = riderMarkerAnimator.renderedLng(rider.id)
                val animBearing = riderMarkerAnimator.renderedBearing(rider.id)
                val markerLatLng = if (animLat != null && animLng != null) LatLng(animLat, animLng) else rider.location
                val screenPos = latLngToRotatedScreen(markerLatLng)
                riderScreenPositions[rider.id] = screenPos

                // Skip drawing markers far outside the viewport
                if (screenPos.x < -150f || screenPos.x > canvasW + 150f ||
                    screenPos.y < -150f || screenPos.y > canvasH + 150f) {
                    continue
                }

                val isSelected = rider.id == selectedRider?.id
                val isEmergency = rider.status == MemberStatus.EMERGENCY

                // Phase B (backlog B-15/B-16): freshness and fix quality are part
                // of the rider's presentation. Both are derived from the MEASURED
                // fix only — the dead-reckoning prediction never reaches here.
                val riderAgeMs = (System.currentTimeMillis() - rider.lastUpdatedMs).coerceAtLeast(0L)
                val isStaleRider = RiderMarkerStyle.isStaleStyle(riderAgeMs)
                val accuracyRingAlpha = RiderMarkerStyle.accuracyRingAlpha(rider.accuracyMeters)

                // Phase B (backlog B-16): deterministic separation of riders whose
                // markers overlap (the offset is derived from the rider id, so it
                // never jitters between frames nor reshuffles when the member
                // list is re-sorted).
                val stackOffset = riderStackOffsets[rider.id] ?: (0f to 0f)
                val drawPos = if (stackOffset.first == 0f && stackOffset.second == 0f) {
                    screenPos
                } else {
                    Offset(screenPos.x + stackOffset.first, screenPos.y + stackOffset.second)
                }

                // Zoom-Adaptive Marker Disc Radius
                val markerRadiusDp = when {
                    displayZoom >= 16f -> 22.dp
                    displayZoom >= 14f -> 18.dp
                    else -> 14.dp
                }
                val markerRadius = markerRadiusDp.toPx()

                val isDisconnectedOrOffline = rider.connectionStatus == com.example.data.model.ConnectionStatus.DISCONNECTED || rider.status == MemberStatus.OFFLINE
                val isStopped = rider.status == MemberStatus.STOPPED

                // Distinct Theme Color for Rider Types according to design spec
                val markerFillColor = when {
                    isEmergency -> StatusEmergencyRed
                    rider.isCurrentUser -> Color(0xFF1E88E5) // Vibrant Navigation Blue ("You")
                    rider.role == com.example.data.model.RideRole.LEADER -> Color(0xFFD32F2F) // Leader Crimson Red
                    isStopped -> Color(0xFFFFB300) // Stopped Amber Yellow
                    isDisconnectedOrOffline -> Color(0xFF607D8B) // Muted Slate Gray
                    else -> Color(rider.avatarColorHex) // Unique Group Rider Accent Color
                }

                // Layer A0: fix-quality ring (B-15). Only coarse fixes get a ring,
                // so a clean fix keeps the marker uncluttered.
                if (accuracyRingAlpha > 0f) {
                    drawCircle(
                        color = Color.White.copy(alpha = accuracyRingAlpha * 0.8f),
                        radius = markerRadius + 4.dp.toPx(),
                        center = drawPos,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                // Layer A1: GPS Accuracy Pulse for Current User
                if (rider.isCurrentUser) {
                    drawCircle(
                        color = Color(0x331E88E5),
                        radius = markerRadius + 10.dp.toPx(),
                        center = screenPos
                    )
                }

                // Layer A2: Emergency Beacon Radiating Waves
                if (isEmergency) {
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = radarAlpha),
                        radius = (markerRadius + 12.dp.toPx()) * radarRadiusFactor,
                        center = screenPos,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = 0.25f),
                        radius = markerRadius + 8.dp.toPx(),
                        center = screenPos
                    )
                }

                // Layer A3: Selected Focus Ring
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = markerRadius + 8.dp.toPx(),
                        center = screenPos,
                        style = Stroke(width = 3.5.dp.toPx())
                    )
                }

                // Layer B: Soft Drop Shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f),
                    radius = markerRadius + 1.dp.toPx(),
                    center = Offset(drawPos.x + 2.dp.toPx(), drawPos.y + 4.dp.toPx())
                )

                // Layer C: Base Disc Fill
                drawCircle(
                    color = markerFillColor,
                    radius = markerRadius,
                    center = drawPos
                )

                // Layer D: Outer Casing / Border (Dashed for Offline/Stale, Solid White for Online)
                if (isDisconnectedOrOffline || isStaleRider) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = markerRadius,
                        center = screenPos,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        )
                    )
                } else {
                    drawCircle(
                        color = Color.White,
                        radius = markerRadius,
                        center = drawPos,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }

                // Layer E: Directional Arrowhead ▲ inside marker disc
                // Relative to screen top, arrow direction is (bearing - effectiveHeading);
                // source preference: animator bearing (qualified source shared with
                // the camera) → member heading.
                //
                // Phase B (backlog B-15): when NO qualified bearing has ever been
                // available, the arrow is NOT drawn — the previous fallback pointed
                // the rider north, which fabricates a direction. A neutral "direction
                // unknown" pip is drawn instead.
                val hasQualifiedBearing = animBearing != null || rider.headingDeg != null
                if (RiderMarkerStyle.canDrawDirectionArrow(hasQualifiedBearing)) {
                    val arrowSourceBearingDeg = animBearing?.toFloat()
                        ?: rider.headingDeg
                        ?: 0f
                    val arrowAngleDeg = arrowSourceBearingDeg - effectiveHeading
                    val angleRad = (arrowAngleDeg - 90f) * (PI.toFloat() / 180f)
                    val arrowLen = markerRadius * 0.7f

                    val tipX = drawPos.x + cos(angleRad) * arrowLen
                    val tipY = drawPos.y + sin(angleRad) * arrowLen

                    val leftX = drawPos.x + cos(angleRad + 2.4f) * (arrowLen * 0.75f)
                    val leftY = drawPos.y + sin(angleRad + 2.4f) * (arrowLen * 0.75f)

                    val rightX = drawPos.x + cos(angleRad - 2.4f) * (arrowLen * 0.75f)
                    val rightY = drawPos.y + sin(angleRad - 2.4f) * (arrowLen * 0.75f)

                    val innerX = drawPos.x + cos(angleRad + PI.toFloat()) * (arrowLen * 0.25f)
                    val innerY = drawPos.y + sin(angleRad + PI.toFloat()) * (arrowLen * 0.25f)

                    val arrowPath = Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(leftX, leftY)
                        lineTo(innerX, innerY)
                        lineTo(rightX, rightY)
                        close()
                    }
                    drawPath(arrowPath, color = Color.White)
                } else {
                    // Direction unknown — never fabricate a heading.
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 2.5.dp.toPx(),
                        center = drawPos
                    )
                }

                // Layer F: Leader Crown Indicator 👑
                if (rider.role == com.example.data.model.RideRole.LEADER) {
                    drawSafeText(
                        text = "👑",
                        x = screenPos.x - 7f,
                        y = screenPos.y - markerRadius - 16f,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }

                // Layer G: Minimalist Pill Badge (First Name Only e.g. "You", "Rohan", "Aman")
                val firstName = if (rider.isCurrentUser) "You" else rider.name.split(" ").firstOrNull() ?: rider.name
                val tagStyle = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                val layoutResult = textMeasurer.measure(firstName, tagStyle)
                val textW = layoutResult.size.width.toFloat()
                val textH = layoutResult.size.height.toFloat()

                val boxTopLeft = Offset(screenPos.x - (textW / 2f) - 8f, screenPos.y + markerRadius + 4f)
                drawRoundRect(
                    color = markerFillColor,
                    topLeft = boxTopLeft,
                    size = androidx.compose.ui.geometry.Size(textW + 16f, textH + 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                )
                drawSafeText(
                    text = firstName,
                    x = boxTopLeft.x + 8f,
                    y = boxTopLeft.y + 2f,
                    style = tagStyle
                )

                // Phase B (backlog B-15): a stale rider says how long ago it was
                // last seen — without it, a rider that vanished ten minutes ago
                // still looks live.
                val lastSeen = RiderMarkerStyle.lastSeenLabel(riderAgeMs)
                if (lastSeen != null) {
                    drawSafeText(
                        text = lastSeen,
                        x = boxTopLeft.x + 8f,
                        y = boxTopLeft.y + 2f + textH + 1f,
                        style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp)
                    )
                }
            }
        }

        // Live Speed HUD for Current User (top-left)
        if (currentUser != null && isFollowRiderMode) {
            // Speed HUD is now drawn separately in LiveRideScreen to avoid overlaps
        }

        // Floating Map Controls (Compass Toggle, Recenter, Overview, Zoom In, Zoom Out, Style)
        // Positioned adaptively below topInsetDp (turn banner) to prevent overlap on all device sizes
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topInsetDp + 12.dp, end = 16.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Compass Toggle Button (Heading-Up vs North-Up)
            SmallFloatingActionButton(
                onClick = {
                    cameraController.toggleOrientationMode()
                },
                modifier = Modifier.testTag("compass_toggle_button"),
                containerColor = Color(0xFF1B222E),
                contentColor = if (cameraState.orientationMode == OrientationMode.NORTH_UP) Color(0xFF38BDF8) else Color.White
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "Toggle Compass Orientation",
                    modifier = Modifier.graphicsLayer {
                        rotationZ = -cameraState.heading
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Re-center on current user button (Primary Blue when active)
            FloatingActionButton(
                onClick = {
                    cameraController.recenter(currentUser?.location, currentUser?.headingDeg)
                    // Immediate rider/camera alignment: snap the marker to its current
                    // target, bypassing the normal 150 ms position glide.
                    if (currentUser != null) {
                        riderMarkerAnimator.snap(currentUser.id)
                    }
                    onFollowRiderChange(true)
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("recenter_button"),
                containerColor = if (isFollowRiderMode) Color(0xFF2563EB) else Color(0xFF1B222E),
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Re-center map"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fit Entire Group / Route Overview
            SmallFloatingActionButton(
                onClick = {
                    val allPoints = members.map { it.location } + route
                    if (allPoints.isNotEmpty()) {
                        // Chrome-aware framing (B-06): the fit must clear the
                        // maneuver banner (top) and the trip HUD (bottom).
                        cameraController.showOverview(
                            points = allPoints,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            paddingPx = 100f,
                            topInsetPx = topInsetPx,
                            bottomInsetPx = bottomInsetPx
                        )
                        onFollowRiderChange(false)
                    }
                },
                modifier = Modifier.testTag("fit_group_button"),
                containerColor = Color(0xFF1B222E),
                contentColor = Color(0xFF38BDF8)
            ) {
                Icon(imageVector = Icons.Default.FilterCenterFocus, contentDescription = "Fit Group in View")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom In (+)
            SmallFloatingActionButton(
                onClick = {
                    val curZoom = cameraController.state.value.zoom
                    if (curZoom < 18f) {
                        cameraController.setZoom(curZoom + 1f)
                    }
                },
                modifier = Modifier.testTag("zoom_in_button"),
                containerColor = Color(0xFF1B222E),
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Zoom In")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom Out (-)
            SmallFloatingActionButton(
                onClick = {
                    val curZoom = cameraController.state.value.zoom
                    if (curZoom > 3f) {
                        cameraController.setZoom(curZoom - 1f)
                    }
                },
                modifier = Modifier.testTag("zoom_out_button"),
                containerColor = Color(0xFF1B222E),
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Remove, contentDescription = "Zoom Out")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Map Appearance Control — cycles Auto → Day → Night when wired to the
            // theme owner (live screen); legacy dark/standard toggle otherwise.
            SmallFloatingActionButton(
                onClick = {
                    if (allowThemeControl) {
                        val next = when (mapThemeMode) {
                            com.example.data.map.MapThemeMode.AUTO -> com.example.data.map.MapThemeMode.DAY
                            com.example.data.map.MapThemeMode.DAY -> com.example.data.map.MapThemeMode.NIGHT
                            com.example.data.map.MapThemeMode.NIGHT -> com.example.data.map.MapThemeMode.AUTO
                        }
                        onMapThemeModeChange(next)
                    } else {
                        legacyDarkStyle = !legacyDarkStyle
                    }
                },
                modifier = Modifier.testTag("map_style_toggle_button"),
                containerColor = Color(0xFF1B222E),
                contentColor = if (allowThemeControl) {
                    when (mapThemeMode) {
                        com.example.data.map.MapThemeMode.AUTO -> Color(0xFF38BDF8)
                        com.example.data.map.MapThemeMode.DAY -> Color(0xFFFBBF24)
                        com.example.data.map.MapThemeMode.NIGHT -> Color.White
                    }
                } else {
                    if (legacyDarkStyle) Color(0xFF38BDF8) else Color.White
                }
            ) {
                Icon(imageVector = Icons.Default.Layers, contentDescription = "Toggle Map Style")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Map Scale Bar — dynamic (Phase B, backlog B-13). The previous bar
            // was a static 48-dp line captioned "500 m" regardless of zoom, so it
            // lied at every zoom level except one. The caption and the bar length
            // are now derived from the real ground distance at the current centre
            // latitude and zoom. Drawn in a Canvas so it invalidates only the
            // draw phase instead of recomposing the control column every frame.
            Canvas(
                modifier = Modifier
                    .width(72.dp)
                    .height(24.dp)
                    .testTag("map_scale_bar")
            ) {
                val perScreenPx = ScaleBarMath.metersPerScreenPixel(
                    latDeg = displayCenterLat.toDouble(),
                    zoom = displayZoom,
                    pxPerDp = density.density
                )
                val segment = ScaleBarMath.chooseSegment(
                    metersPerScreenPx = perScreenPx,
                    barWidthDp = 72f
                )
                val barHeightPx = 2.dp.toPx()
                val barWidthPx = segment.widthPx
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset((size.width - barWidthPx) / 2f, 0f),
                    size = androidx.compose.ui.geometry.Size(barWidthPx, barHeightPx)
                )
                val captionLayout = textMeasurer.measure(
                    text = ScaleBarMath.caption(segment.meters),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                drawText(
                    textLayoutResult = captionLayout,
                    topLeft = Offset(
                        (size.width - captionLayout.size.width) / 2f,
                        barHeightPx + 1.dp.toPx()
                    )
                )
            }
        }
    }
}
