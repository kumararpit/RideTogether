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
import kotlin.math.cos
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val currentUser = members.find { it.isCurrentUser }
    val initialCenter = currentUser?.location ?: if (route.isNotEmpty()) route[0] else LatLng(18.5204, 73.8567)

    val cameraController = remember { com.example.data.map.MapCameraController() }
    val cameraState by cameraController.state.collectAsState()

    var centerLat by remember { mutableStateOf(initialCenter.latitude) }
    var centerLng by remember { mutableStateOf(initialCenter.longitude) }
    var zoomLevel by remember { mutableIntStateOf(if (route.isNotEmpty()) 16 else 14) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var isDarkTacticalStyle by remember { mutableStateOf(true) }

    // Synchronize camera state machine when follow mode or rider location updates
    LaunchedEffect(currentUser?.location, currentUser?.speedKmh, currentUser?.headingDeg, isFollowRiderMode, distanceToNextManeuverMeters) {
        if (currentUser != null && isFollowRiderMode) {
            val locUpdate = com.example.data.location.LocationUpdate(
                latLng = currentUser.location,
                speedKmh = currentUser.speedKmh,
                headingDeg = currentUser.headingDeg,
                accuracyMeters = 10f
            )
            cameraController.processLocation(locUpdate, distanceToNextManeuverMeters)
            val st = cameraController.state.value
            centerLat = st.center.latitude
            centerLng = st.center.longitude
            zoomLevel = st.zoom
            if (st.mode == MapCameraMode.FOLLOWING) {
                panOffsetX = 0f
                panOffsetY = 0f
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark900)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val tileSizeDp = 256.dp
        val tileSizePx = with(density) { tileSizeDp.toPx() }

        // Camera Anchor: (0.5w, 0.65h) in FOLLOWING mode, (0.5w, 0.5h) in FREE_EXPLORE or OVERVIEW
        val anchorX = widthPx / 2f
        val anchorY = if (cameraState.mode == MapCameraMode.FOLLOWING) heightPx * 0.65f else heightPx / 2f
        val pivotX = anchorX + panOffsetX
        val pivotY = anchorY + panOffsetY

        val effectiveHeading = cameraState.effectiveHeading

        // Dark tactical color filter for high-contrast night/adventure rides
        val darkTacticalColorFilter = remember {
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -0.80f, 0f, 0f, 0f, 215f,
                0f, -0.75f, 0f, 0f, 220f,
                0f, 0f, -0.70f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // Center tile coordinates
        val centerTileXDouble = TileMath.lonToTileX(centerLng, zoomLevel)
        val centerTileYDouble = TileMath.latToTileY(centerLat, zoomLevel)

        // Web Mercator coordinate conversion to Unrotated Screen Pixels
        fun latLngToUnrotatedScreen(latLng: LatLng): Offset {
            val tileX = TileMath.lonToTileX(latLng.longitude, zoomLevel)
            val tileY = TileMath.latToTileY(latLng.latitude, zoomLevel)
            val x = anchorX + panOffsetX + ((tileX - centerTileXDouble) * tileSizePx).toFloat()
            val y = anchorY + panOffsetY + ((tileY - centerTileYDouble) * tileSizePx).toFloat()
            return Offset(x, y)
        }

        // Coordinate conversion to Rotated Screen Pixels (for overlay markers and hit testing)
        fun unrotatedToRotatedScreen(unrotated: Offset): Offset {
            if (effectiveHeading == 0f) return unrotated
            val rad = Math.toRadians(-effectiveHeading.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            val dx = unrotated.x - pivotX
            val dy = unrotated.y - pivotY
            val rx = (pivotX + dx * cosA - dy * sinA).toFloat()
            val ry = (pivotY + dx * sinA + dy * cosA).toFloat()
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
                        pivotX / widthPx,
                        pivotY / heightPx
                    )
                }
        ) {
            // A. Real Slippy Map Tiles Layer with 100% Rotated Viewport Coverage
            val tileRange = TileMath.calculateVisibleTileRange(
                widthPx = widthPx,
                heightPx = heightPx,
                anchorX = anchorX,
                anchorY = anchorY,
                panOffsetX = panOffsetX,
                panOffsetY = panOffsetY,
                centerTileXDouble = centerTileXDouble,
                centerTileYDouble = centerTileYDouble,
                zoomLevel = zoomLevel,
                tileSizePx = tileSizePx,
                effectiveHeadingDeg = effectiveHeading
            )

            Box(modifier = Modifier.fillMaxSize()) {
                for (tileX in tileRange.minX..tileRange.maxX) {
                    for (tileY in tileRange.minY..tileRange.maxY) {
                        val tileUnrotatedX = anchorX + panOffsetX + ((tileX - centerTileXDouble) * tileSizePx).toFloat()
                        val tileUnrotatedY = anchorY + panOffsetY + ((tileY - centerTileYDouble) * tileSizePx).toFloat()

                        val tileUrl = TileMath.getOsmTileUrl(zoomLevel, tileX, tileY)

                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(tileUrl)
                                .addHeader("User-Agent", TileMath.OSM_USER_AGENT)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Map tile",
                            contentScale = ContentScale.FillBounds,
                            colorFilter = if (isDarkTacticalStyle) darkTacticalColorFilter else null,
                            modifier = Modifier
                                .offset { IntOffset(tileUnrotatedX.roundToInt(), tileUnrotatedY.roundToInt()) }
                                .size(tileSizeDp)
                        )
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
                        style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 2. High-contrast Orange/Amber Casing
                    drawPath(
                        path = routePath,
                        color = Color(0xFFE65100),
                        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 3. Vibrant Road Line
                    drawPath(
                        path = routePath,
                        color = AmberPrimary,
                        style = Stroke(width = 7.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // 4. Center lane guide
                    drawPath(
                        path = routePath,
                        color = Color(0xFFFFF9C4),
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Direction chevrons/dots along road every 15 points
                    if (routePoints.size > 15) {
                        val step = (routePoints.size / 10).coerceAtLeast(4)
                        for (k in step until routePoints.size - 2 step step) {
                            val pt = routePoints[k]
                            drawCircle(color = Color.White, radius = 3.5f, center = pt)
                            drawCircle(color = Color(0xFFE65100), radius = 2f, center = pt)
                        }
                    }

                    // Start Marker
                    val startScreen = routePoints.first()
                    drawCircle(color = Color(0x664CAF50), radius = 16f, center = startScreen)
                    drawCircle(color = StatusRidingGreen, radius = 10f, center = startScreen)
                    drawCircle(color = Color.White, radius = 5f, center = startScreen)

                    // Destination Marker
                    val destScreen = routePoints.last()
                    drawCircle(color = Color(0x66F44336), radius = 18f, center = destScreen)
                    drawCircle(color = StatusEmergencyRed, radius = 11f, center = destScreen)
                    drawCircle(color = Color.White, radius = 5f, center = destScreen)
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
                            val newZoom = (zoomLevel * zoom).roundToInt().coerceIn(3, 18)
                            if (newZoom != zoomLevel) {
                                zoomLevel = newZoom
                                cameraController.setZoom(newZoom)
                            }
                        }
                        if (pan != Offset.Zero) {
                            val rad = Math.toRadians(effectiveHeading.toDouble())
                            val cosH = cos(rad).toFloat()
                            val sinH = sin(rad).toFloat()

                            val unrotatedDx = pan.x * cosH + pan.y * sinH
                            val unrotatedDy = -pan.x * sinH + pan.y * cosH

                            panOffsetX += unrotatedDx
                            panOffsetY += unrotatedDy
                        }
                        cameraController.onUserGesture()
                        onFollowRiderChange(false)
                    }
                }
                .pointerInput(members) {
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
                        onSelectRider(clickedRider)
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
                val markerRadius = when {
                    zoomLevel >= 16 -> 22f
                    zoomLevel >= 14 -> 18f
                    else -> 14f
                }

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
                        radius = markerRadius + 10f,
                        center = screenPos
                    )
                }

                // Layer A2: Emergency Beacon Radiating Waves
                if (isEmergency) {
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = radarAlpha),
                        radius = (markerRadius + 12f) * radarRadiusFactor,
                        center = screenPos,
                        style = Stroke(width = 4f)
                    )
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = 0.25f),
                        radius = markerRadius + 8f,
                        center = screenPos
                    )
                }

                // Layer A3: Selected Focus Ring
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = markerRadius + 8f,
                        center = screenPos,
                        style = Stroke(width = 3.5f)
                    )
                }

                // Layer B: Soft Drop Shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f),
                    radius = markerRadius + 1f,
                    center = Offset(screenPos.x + 2f, screenPos.y + 4f)
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
                            width = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        )
                    )
                } else {
                    drawCircle(
                        color = Color.White,
                        radius = markerRadius,
                        center = screenPos,
                        style = Stroke(width = 2.5f)
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
        if (currentUser != null) {
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, top = 76.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .testTag("speed_hud"),
                color = SlateDark800.copy(alpha = 0.94f),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SPEED",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${currentUser.speedKmh.toInt()}",
                                color = if (currentUser.speedKmh > 0) StatusRidingGreen else StatusStoppedAmber,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "km/h",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Compass Heading
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Compass",
                            tint = AmberPrimary,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    rotationZ = -effectiveHeading
                                }
                        )
                        Text(
                            text = "${currentUser.headingDeg.toInt()}°",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Floating Map Controls (Compass Toggle, Recenter, Overview, Zoom In, Zoom Out, Style)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
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
                    panOffsetX = 0f
                    panOffsetY = 0f
                    cameraController.recenter(currentUser?.location, currentUser?.headingDeg)
                    if (currentUser != null) {
                        centerLat = currentUser.location.latitude
                        centerLng = currentUser.location.longitude
                    }
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
                        cameraController.showOverview(allPoints)
                        val st = cameraController.state.value
                        centerLat = st.center.latitude
                        centerLng = st.center.longitude
                        zoomLevel = st.zoom
                        panOffsetX = 0f
                        panOffsetY = 0f
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
                    if (zoomLevel < 18) {
                        zoomLevel += 1
                        panOffsetX = 0f
                        panOffsetY = 0f
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
                    if (zoomLevel > 3) {
                        zoomLevel -= 1
                        panOffsetX = 0f
                        panOffsetY = 0f
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
