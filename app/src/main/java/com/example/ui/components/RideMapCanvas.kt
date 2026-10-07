package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusOfflineGray
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.StatusStoppedAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RideMapCanvas(
    route: List<LatLng>,
    members: List<RiderMember>,
    selectedRider: RiderMember?,
    onSelectRider: (RiderMember?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val currentUser = members.find { it.isCurrentUser }
    val initialCenter = currentUser?.location ?: if (route.isNotEmpty()) route[0] else LatLng(18.5204, 73.8567)

    var centerLat by remember { mutableStateOf(initialCenter.latitude) }
    var centerLng by remember { mutableStateOf(initialCenter.longitude) }
    var zoomLevel by remember { mutableIntStateOf(13) } // OSM Slippy tile zoom level (1..18)
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Follow user if center not moved manually
    LaunchedEffect(currentUser?.location) {
        if (currentUser != null && panOffsetX == 0f && panOffsetY == 0f) {
            centerLat = currentUser.location.latitude
            centerLng = currentUser.location.longitude
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
        val tileSizePx = 256f

        // Calculate center tile coordinates
        val centerTileXDouble = TileMath.lonToTileX(centerLng, zoomLevel)
        val centerTileYDouble = TileMath.latToTileY(centerLat, zoomLevel)

        // Web Mercator coordinate conversion to Screen Pixels
        fun latLngToScreen(latLng: LatLng): Offset {
            val tileX = TileMath.lonToTileX(latLng.longitude, zoomLevel)
            val tileY = TileMath.latToTileY(latLng.latitude, zoomLevel)
            val x = (widthPx / 2f) + panOffsetX + ((tileX - centerTileXDouble) * tileSizePx).toFloat()
            val y = (heightPx / 2f) + panOffsetY + ((tileY - centerTileYDouble) * tileSizePx).toFloat()
            return Offset(x, y)
        }

        // 1. Real Slippy Map Tiles Layer
        val centerTileXInt = centerTileXDouble.toInt()
        val centerTileYInt = centerTileYDouble.toInt()
        val maxTileIndex = (1 shl zoomLevel) - 1

        val rangeX = -2..2
        val rangeY = -2..2

        Box(modifier = Modifier.fillMaxSize()) {
            for (dx in rangeX) {
                for (dy in rangeY) {
                    val tileX = (centerTileXInt + dx).coerceIn(0, maxTileIndex)
                    val tileY = (centerTileYInt + dy).coerceIn(0, maxTileIndex)
                    val tileScreenX = (widthPx / 2f) + panOffsetX + ((tileX - centerTileXDouble) * tileSizePx).toFloat()
                    val tileScreenY = (heightPx / 2f) + panOffsetY + ((tileY - centerTileYDouble) * tileSizePx).toFloat()

                    val tileUrl = TileMath.getCartoDarkTileUrl(zoomLevel, tileX, tileY)

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(tileUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Map tile",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset { IntOffset(tileScreenX.toInt(), tileScreenY.toInt()) }
                            .size(256.dp)
                    )
                }
            }
        }

        // 2. Map Vector Elements & Rider Markers Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ride_map_canvas")
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panOffsetX += dragAmount.x
                        panOffsetY += dragAmount.y
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

            // Draw Route Line if available
            if (route.size >= 2) {
                val routePath = Path()
                val routePoints = route.map { latLngToScreen(it) }

                routePath.moveTo(routePoints.first().x, routePoints.first().y)
                for (i in 1 until routePoints.size) {
                    routePath.lineTo(routePoints[i].x, routePoints[i].y)
                }

                // Route halo
                drawPath(
                    path = routePath,
                    color = Color(0x44FFA726),
                    style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Route line
                drawPath(
                    path = routePath,
                    color = AmberPrimary,
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Start Marker
                val startScreen = routePoints.first()
                drawCircle(color = StatusRidingGreen, radius = 9f, center = startScreen)
                drawCircle(color = Color.White, radius = 4f, center = startScreen)
                drawSafeText(
                    text = "Start",
                    x = startScreen.x - 20f,
                    y = startScreen.y + 12f,
                    style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                )

                // Destination Marker
                val destScreen = routePoints.last()
                drawCircle(color = StatusEmergencyRed, radius = 9f, center = destScreen)
                drawCircle(color = Color.White, radius = 4f, center = destScreen)
                drawSafeText(
                    text = "Finish",
                    x = destScreen.x - 20f,
                    y = destScreen.y - 28f,
                    style = TextStyle(color = AmberPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
            }

            // Draw Rider Markers
            riderScreenPositions.clear()
            for (rider in members) {
                val screenPos = latLngToScreen(rider.location)
                riderScreenPositions[rider.id] = screenPos

                // Skip drawing markers that are far outside the viewport
                if (screenPos.x < -150f || screenPos.x > canvasW + 150f ||
                    screenPos.y < -150f || screenPos.y > canvasH + 150f) {
                    continue
                }

                val isSelected = rider.id == selectedRider?.id
                val isEmergency = rider.status == MemberStatus.EMERGENCY

                // Draw Emergency Beacon Radiating Waves
                if (isEmergency) {
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = radarAlpha),
                        radius = 28f * radarRadiusFactor,
                        center = screenPos,
                        style = Stroke(width = 4f)
                    )
                    drawCircle(
                        color = StatusEmergencyRed.copy(alpha = 0.25f),
                        radius = 20f,
                        center = screenPos
                    )
                }

                // If selected: Draw focus ring
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = 24f,
                        center = screenPos,
                        style = Stroke(width = 3f)
                    )
                }

                // Base Marker Disc
                val markerColor = when (rider.status) {
                    MemberStatus.EMERGENCY -> StatusEmergencyRed
                    MemberStatus.STOPPED -> StatusStoppedAmber
                    MemberStatus.RIDING -> StatusRidingGreen
                    MemberStatus.OFFLINE -> StatusOfflineGray
                }

                // Shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 16f,
                    center = Offset(screenPos.x + 2f, screenPos.y + 4f)
                )

                // Outer border & fill
                drawCircle(
                    color = Color(rider.avatarColorHex),
                    radius = 15f,
                    center = screenPos
                )
                drawCircle(
                    color = Color.White,
                    radius = 15f,
                    center = screenPos,
                    style = Stroke(width = 2.5f)
                )

                // Status indicator pip
                val pipCenter = Offset(screenPos.x + 10f, screenPos.y + 10f)
                drawCircle(color = Color.Black, radius = 6f, center = pipCenter)
                drawCircle(color = markerColor, radius = 5f, center = pipCenter)

                // Heading arrow
                if (rider.status == MemberStatus.RIDING && rider.speedKmh > 0) {
                    val angleRad = (rider.headingDeg - 90f) * (PI.toFloat() / 180f)
                    val tipX = screenPos.x + cos(angleRad) * 22f
                    val tipY = screenPos.y + sin(angleRad) * 22f
                    val base1X = screenPos.x + cos(angleRad + 2.5f) * 14f
                    val base1Y = screenPos.y + sin(angleRad + 2.5f) * 14f
                    val base2X = screenPos.x + cos(angleRad - 2.5f) * 14f
                    val base2Y = screenPos.y + sin(angleRad - 2.5f) * 14f

                    val arrowPath = Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(base1X, base1Y)
                        lineTo(base2X, base2Y)
                        close()
                    }
                    drawPath(arrowPath, color = markerColor)
                }

                // Rider Initials
                val initials = rider.name.take(1).uppercase()
                drawSafeText(
                    text = initials,
                    x = screenPos.x - 5f,
                    y = screenPos.y - 8f,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                )

                // Name & Status Pill
                val labelText = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name
                val statusText = when (rider.status) {
                    MemberStatus.EMERGENCY -> "🚨 SOS!"
                    MemberStatus.STOPPED -> "Stopped"
                    MemberStatus.RIDING -> "${rider.speedKmh.toInt()} km/h"
                    MemberStatus.OFFLINE -> "Offline"
                }

                val fullTag = "$labelText • $statusText"
                val tagStyle = TextStyle(
                    color = if (isEmergency) StatusEmergencyRed else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                val layoutResult = textMeasurer.measure(fullTag, tagStyle)
                val textW = layoutResult.size.width.toFloat()
                val textH = layoutResult.size.height.toFloat()

                val boxTopLeft = Offset(screenPos.x - (textW / 2f) - 6f, screenPos.y - 38f)
                drawRoundRect(
                    color = Color(0xDD121721),
                    topLeft = boxTopLeft,
                    size = androidx.compose.ui.geometry.Size(textW + 12f, textH + 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
                drawSafeText(
                    text = fullTag,
                    x = boxTopLeft.x + 6f,
                    y = boxTopLeft.y + 3f,
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
                            modifier = Modifier.size(20.dp)
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

        // Floating Map Controls (Zoom In, Zoom Out, Re-center) on Right Side
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            // Re-center on current user button
            FloatingActionButton(
                onClick = {
                    panOffsetX = 0f
                    panOffsetY = 0f
                    if (currentUser != null) {
                        centerLat = currentUser.location.latitude
                        centerLng = currentUser.location.longitude
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("recenter_button"),
                containerColor = SlateDark800,
                contentColor = AmberPrimary,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Re-center map"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
        }
    }
}
