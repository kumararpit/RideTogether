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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.map.MapCameraMode
import com.example.data.map.OrientationMode
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.StatusStoppedAmber
import kotlin.math.PI
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun RideMapCanvas(
    route: List<LatLng>,
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
    onViewportChanged: (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val currentUser = members.find { it.isCurrentUser }
    val initialCenter = currentUser?.location ?: if (route.isNotEmpty()) route[0] else LatLng(18.5204, 73.8567)

    val cameraController = remember { com.example.data.map.MapCameraController() }
    val cameraState by cameraController.state.collectAsState()

    var displayCenterLat by remember { mutableFloatStateOf(initialCenter.latitude.toFloat()) }
    var displayCenterLng by remember { mutableFloatStateOf(initialCenter.longitude.toFloat()) }
    var displayZoom by remember { mutableFloatStateOf(if (route.isNotEmpty()) 16f else 14f) }
    var displayHeading by remember { mutableFloatStateOf(0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var displayAnchorY by remember { mutableFloatStateOf(0f) }
    var isDarkTacticalStyle by remember { mutableStateOf(true) }
    var hasInitialized by remember { mutableStateOf(false) }
    
    // Initialize map position on first launch or when route becomes available
    LaunchedEffect(route, isFollowRiderMode) {
        if (!hasInitialized) {
            if (isFollowRiderMode) {
                cameraController.recenter(initialCenter, currentUser?.headingDeg)
                cameraController.setZoom(17f)
                hasInitialized = true
            } else if (route.isNotEmpty()) {
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

    // Frame-paced display interpolation
    LaunchedEffect(Unit) {
        var lastTime = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastTime != 0L) {
                    val dt = (frameTimeNanos - lastTime) / 1_000_000_000f
                    val tau = 0.25f
                    val alpha = 1f - kotlin.math.exp(-dt / tau)

                    val targetState = cameraController.state.value

                    if (targetState.mode == MapCameraMode.FOLLOWING || targetState.mode == MapCameraMode.OVERVIEW) {
                        displayCenterLat += (targetState.center.latitude.toFloat() - displayCenterLat) * alpha
                        displayCenterLng += (targetState.center.longitude.toFloat() - displayCenterLng) * alpha
                        displayZoom += (targetState.zoom - displayZoom) * alpha
                    }

                    val targetHeading = targetState.effectiveHeading
                    val deltaH = ((targetHeading - displayHeading + 540f) % 360f) - 180f
                    displayHeading = (displayHeading + deltaH * alpha + 360f) % 360f

                    if (targetState.mode == MapCameraMode.FOLLOWING || targetState.mode == MapCameraMode.OVERVIEW) {
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
                }
                lastTime = frameTimeNanos
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
        if (displayAnchorY == 0f) displayAnchorY = targetAnchorY
        
        // Smoothly interpolate anchorY in the composition phase
        displayAnchorY += (targetAnchorY - displayAnchorY) * 0.15f
        
        val tileSizeDp = 256.dp
        val baseTileSizePx = with(density) { tileSizeDp.toPx() }

        // Camera Anchor using usable viewport
        val anchorX = widthPx / 2f
        val anchorY = displayAnchorY
        
        val pivotX = anchorX + panOffsetX

        val effectiveHeading = displayHeading

        // Dark tactical color filter for high-contrast night/adventure rides
        val darkTacticalColorFilter = remember {
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -0.80f, 0f, 0f, 0f, 215f,
                0f, -0.75f, 0f, 0f, 220f,
                0f, 0f, -0.70f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

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
                                    .background(SlateDark800)
                            ) {
                                // Fallback layer (parent tile) to prevent black stripes while loading
                                if (tileZoom > 0) {
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
                                        colorFilter = if (isDarkTacticalStyle) darkTacticalColorFilter else null,
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
                                    colorFilter = if (isDarkTacticalStyle) darkTacticalColorFilter else null,
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
                    val routePath = Path()
                    val routePoints = route.map { latLngToUnrotatedScreen(it) }

                    routePath.moveTo(routePoints.first().x, routePoints.first().y)
                    for (i in 1 until routePoints.size) {
                        routePath.lineTo(routePoints[i].x, routePoints[i].y)
                    }

                    // 1. Dark Road Shadow
                    drawPath(
                        path = routePath,
                        color = Color(0xAA0B0F19),
                        style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 2. High-contrast Orange/Amber Casing
                    drawPath(
                        path = routePath,
                        color = Color(0xFFE65100),
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 3. Vibrant Road Line
                    drawPath(
                        path = routePath,
                        color = AmberPrimary,
                        style = Stroke(width = 7.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 4. Center lane guide
                    drawPath(
                        path = routePath,
                        color = Color(0xFFFFF9C4),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Direction chevrons/dots along road every 15 points
                    if (routePoints.size > 15) {
                        val step = (routePoints.size / 10).coerceAtLeast(4)
                        for (k in step until routePoints.size - 2 step step) {
                            val pt = routePoints[k]
                            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = pt)
                            drawCircle(color = Color(0xFFE65100), radius = 2.dp.toPx(), center = pt)
                        }
                    }

                    // Start Marker
                    val startScreen = routePoints.first()
                    drawCircle(color = Color(0x664CAF50), radius = 16.dp.toPx(), center = startScreen)
                    drawCircle(color = StatusRidingGreen, radius = 10.dp.toPx(), center = startScreen)
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = startScreen)

                    // Destination Marker
                    val destScreen = routePoints.last()
                    drawCircle(color = Color(0x66F44336), radius = 18.dp.toPx(), center = destScreen)
                    drawCircle(color = StatusEmergencyRed, radius = 11.dp.toPx(), center = destScreen)
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = destScreen)
                }
            }
        }

        // 2. Unrotated Overlay Canvas (Rider Markers, Upright Labels, Gestures)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ride_map_canvas")
                .pointerInput(effectiveHeading) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            val zoomDelta = kotlin.math.log2(zoom)
                            val newZoom = (displayZoom + zoomDelta).coerceIn(3f, 18f)
                            if (newZoom != displayZoom) {
                                displayZoom = newZoom
                                cameraController.setZoom(newZoom)
                            }
                        }
                        if (pan != Offset.Zero) {
                            cameraController.pan(pan.x, pan.y, displayZoom, effectiveHeading, baseTileSizePx)
                            val targetState = cameraController.state.value
                            displayCenterLat = targetState.center.latitude.toFloat()
                            displayCenterLng = targetState.center.longitude.toFloat()
                        }
                        cameraController.onUserGesture()
                        onFollowRiderChange(false)
                    }
                }
                .pointerInput(members, amenities) {
                    detectTapGestures { tapOffset ->
                        var clickedRider: RiderMember? = null
                        for (member in members) {
                            val pos = riderScreenPositions[member.id] ?: continue
                            val distance = (tapOffset - pos).getDistance()
                            if (distance < 70f) {
                                clickedRider = member
                                break
                            }
                        }
                        if (clickedRider != null) {
                            onSelectRider(clickedRider)
                            onSelectAmenity(null)
                        } else {
                            var clickedAmenity: com.example.data.amenities.AmenityEntity? = null
                            val tapTargetRadius = with(density) { 24.dp.toPx() } // Minimum 48dp tap target
                            for (amenity in amenities) {
                                val pos = amenityScreenPositions[amenity.osmId] ?: continue
                                val distance = (tapOffset - pos).getDistance()
                                if (distance < tapTargetRadius) {
                                    clickedAmenity = amenity
                                    break
                                }
                            }
                            onSelectAmenity(clickedAmenity)
                            if (clickedAmenity != null) onSelectRider(null)
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

            // Draw Start & Finish text tags upright at their rotated screen positions
            if (route.size >= 2) {
                val startRotated = latLngToRotatedScreen(route.first())
                drawSafeText(
                    text = "START",
                    x = startRotated.x - 20f,
                    y = startRotated.y + 14f,
                    style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                )

                val destRotated = latLngToRotatedScreen(route.last())
                drawSafeText(
                    text = "FINISH",
                    x = destRotated.x - 22f,
                    y = destRotated.y - 28f,
                    style = TextStyle(color = AmberPrimary, fontSize = 11.sp, fontWeight = FontWeight.Black)
                )
            }

            // Draw Custom Google Maps Style Amenity Pins
            amenityScreenPositions.clear()
            for (amenity in amenities) {
                val screenPos = latLngToRotatedScreen(LatLng(amenity.lat, amenity.lon))
                amenityScreenPositions[amenity.osmId] = screenPos

                if (screenPos.x < -100f || screenPos.x > canvasW + 100f ||
                    screenPos.y < -100f || screenPos.y > canvasH + 100f) {
                    continue
                }

                val isSelected = amenity.osmId == selectedAmenity?.osmId
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
                val categoryColor = AmenityIconConfig.getCategoryColor(amenity.type)

                val cx = screenPos.x
                val cy = screenPos.y - pinRadiusPx - tipHeightPx

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

                drawPath(path = pinPath, color = categoryColor)
                drawPath(path = pinPath, color = Color.White, style = Stroke(width = 1.8.dp.toPx()))

                // 4. White Category Icon Glyph
                val painter = categoryPainters[amenity.type] ?: categoryPainters[com.example.data.amenities.AmenityType.UNKNOWN]!!
                val glyphSize = pinRadiusPx * 1.15f
                drawContext.transform.translate(left = cx - glyphSize / 2f, top = cy - glyphSize / 2f)
                try {
                    with(painter) {
                        draw(
                            size = androidx.compose.ui.geometry.Size(glyphSize, glyphSize),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }
                } finally {
                    drawContext.transform.translate(left = -(cx - glyphSize / 2f), top = -(cy - glyphSize / 2f))
                }
            }

            // Draw Rider Markers at rotated screen positions
            riderScreenPositions.clear()
            for (rider in members) {
                val screenPos = latLngToRotatedScreen(rider.location)
                riderScreenPositions[rider.id] = screenPos

                // Skip drawing markers far outside the viewport
                if (screenPos.x < -150f || screenPos.x > canvasW + 150f ||
                    screenPos.y < -150f || screenPos.y > canvasH + 150f) {
                    continue
                }

                val isSelected = rider.id == selectedRider?.id
                val isEmergency = rider.status == MemberStatus.EMERGENCY

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
                    center = Offset(screenPos.x + 2.dp.toPx(), screenPos.y + 4.dp.toPx())
                )

                // Layer C: Base Disc Fill
                drawCircle(
                    color = markerFillColor,
                    radius = markerRadius,
                    center = screenPos
                )

                // Layer D: Outer Casing / Border (Dashed for Offline/Stale, Solid White for Online)
                if (isDisconnectedOrOffline) {
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
                        center = screenPos,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }

                // Layer E: Directional Arrowhead ▲ inside marker disc
                // Relative to screen top, arrow direction is (rider.headingDeg - effectiveHeading)
                val arrowAngleDeg = rider.headingDeg - effectiveHeading
                val angleRad = (arrowAngleDeg - 90f) * (PI.toFloat() / 180f)
                val arrowLen = markerRadius * 0.7f

                val tipX = screenPos.x + cos(angleRad) * arrowLen
                val tipY = screenPos.y + sin(angleRad) * arrowLen

                val leftX = screenPos.x + cos(angleRad + 2.4f) * (arrowLen * 0.75f)
                val leftY = screenPos.y + sin(angleRad + 2.4f) * (arrowLen * 0.75f)

                val rightX = screenPos.x + cos(angleRad - 2.4f) * (arrowLen * 0.75f)
                val rightY = screenPos.y + sin(angleRad - 2.4f) * (arrowLen * 0.75f)

                val innerX = screenPos.x + cos(angleRad + PI.toFloat()) * (arrowLen * 0.25f)
                val innerY = screenPos.y + sin(angleRad + PI.toFloat()) * (arrowLen * 0.25f)

                val arrowPath = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(leftX, leftY)
                    lineTo(innerX, innerY)
                    lineTo(rightX, rightY)
                    close()
                }
                drawPath(arrowPath, color = Color.White)

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
            }
        }

        // Live Speed HUD for Current User (top-left)
        if (currentUser != null && isFollowRiderMode) {
            // Speed HUD is now drawn separately in LiveRideScreen to avoid overlaps
        }

        // Floating Map Controls (Compass Toggle, Recenter, Overview, Zoom In, Zoom Out, Style)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .padding(bottom = 140.dp) // Avoid overlapping bottom sheet elements
        ) {
            // Compass Toggle Button (Heading-Up vs North-Up)
            SmallFloatingActionButton(
                onClick = {
                    cameraController.toggleOrientationMode()
                },
                modifier = Modifier.testTag("compass_toggle_button"),
                containerColor = SlateDark800,
                contentColor = if (cameraState.orientationMode == OrientationMode.NORTH_UP) AmberPrimary else Color.White
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

            // Re-center on current user button
            FloatingActionButton(
                onClick = {
                    cameraController.recenter(currentUser?.location, currentUser?.headingDeg)
                    onFollowRiderChange(true)
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("recenter_button"),
                containerColor = if (isFollowRiderMode) AmberPrimary else SlateDark800,
                contentColor = if (isFollowRiderMode) SlateDark900 else AmberPrimary,
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
                        cameraController.showOverview(allPoints, widthPx, heightPx, 100f)
                        onFollowRiderChange(false)
                    }
                },
                modifier = Modifier.testTag("fit_group_button"),
                containerColor = SlateDark800,
                contentColor = AmberPrimary
            ) {
                Icon(imageVector = Icons.Default.FilterCenterFocus, contentDescription = "Fit Group in View")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom In (+)
            SmallFloatingActionButton(
                onClick = {
                    if (cameraState.zoom < 18f) {
                        val newZoom = cameraState.zoom + 1f
                        cameraController.setZoom(newZoom)
                    }
                },
                modifier = Modifier.testTag("zoom_in_button"),
                containerColor = SlateDark800,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Zoom In")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom Out (-)
            SmallFloatingActionButton(
                onClick = {
                    if (cameraState.zoom > 3f) {
                        val newZoom = cameraState.zoom - 1f
                        cameraController.setZoom(newZoom)
                    }
                },
                modifier = Modifier.testTag("zoom_out_button"),
                containerColor = SlateDark800,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Remove, contentDescription = "Zoom Out")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Map Style Toggle (Dark Tactical vs OpenStreetMap Standard)
            SmallFloatingActionButton(
                onClick = {
                    isDarkTacticalStyle = !isDarkTacticalStyle
                },
                modifier = Modifier.testTag("map_style_toggle_button"),
                containerColor = SlateDark800,
                contentColor = if (isDarkTacticalStyle) AmberPrimary else Color.White
            ) {
                Icon(imageVector = Icons.Default.Layers, contentDescription = "Toggle Map Style")
            }
        }
    }
}
