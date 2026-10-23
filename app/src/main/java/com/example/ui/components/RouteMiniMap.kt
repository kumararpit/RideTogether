package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.RouteBlue
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import kotlin.math.cos

/**
 * A compact, truthful preview of the route and the candidate stop.
 *
 * It draws the **actual** route geometry the app holds, the rider, the
 * destination and the place being considered — never a decorative or
 * invented map. It is used by the add-stop confirmation, where the rider
 * needs to see where the stop sits relative to the ride they are on.
 */
@Composable
fun RouteMiniMap(
    route: List<LatLng>,
    rider: LatLng?,
    candidate: LatLng,
    destination: LatLng?,
    modifier: Modifier = Modifier
) {
    val description = buildString {
        append("Route preview. ")
        append("The stop is shown in amber")
        if (destination != null) append(", the destination with a flag")
        append(".")
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SlateDark900)
            .testTag("route_mini_map")
            .semantics { contentDescription = description }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // A subtle grid gives the preview depth without pretending to be
            // map imagery.
            val grid = 28.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(SlateDark800, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += grid
            }
            var y = 0f
            while (y < size.height) {
                drawLine(SlateDark800, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += grid
            }

            val points = buildList {
                addAll(route)
                rider?.let { add(it) }
                add(candidate)
                destination?.let { add(it) }
            }
            if (points.isEmpty()) return@Canvas

            val meanLat = points.sumOf { it.latitude } / points.size
            val lonScale = cos(Math.toRadians(meanLat)).coerceAtLeast(0.2)
            val xs = points.map { it.longitude * lonScale }
            val ys = points.map { it.latitude }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()
            val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-4
            val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-4
            val pad = 26.dp.toPx()
            val usableW = (size.width - pad * 2).coerceAtLeast(1f)
            val usableH = (size.height - pad * 2).coerceAtLeast(1f)
            val scale = minOf(usableW / spanX, usableH / spanY)
            // Centre the geometry in the viewport.
            val offsetX = (size.width - spanX * scale) / 2f
            val offsetY = (size.height - spanY * scale) / 2f

            fun project(p: LatLng): Offset {
                val px = offsetX + ((p.longitude * lonScale) - minX) * scale
                // Latitude grows upward, screen y grows downward.
                val py = offsetY + (maxY - p.latitude) * scale
                return Offset(px.toFloat(), py.toFloat())
            }

            if (route.size >= 2) {
                val path = Path().apply {
                    val first = project(route.first())
                    moveTo(first.x, first.y)
                    for (i in 1 until route.size) {
                        val p = project(route[i])
                        lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    path = path,
                    color = SlateDark900,
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = RouteBlue,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Destination: a white flag-like dot.
            destination?.let {
                val p = project(it)
                drawCircle(Color.White, radius = 5.dp.toPx(), center = p)
                drawLine(
                    color = Color.White,
                    start = p,
                    end = Offset(p.x, p.y - 14.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Rider: blue dot in a white ring.
            rider?.let {
                val p = project(it)
                drawCircle(Color.White, radius = 7.dp.toPx(), center = p)
                drawCircle(RouteBlue, radius = 5.dp.toPx(), center = p)
            }

            // Candidate stop: the amber pin with a white ring.
            val stop = project(candidate)
            drawCircle(SlateDark900, radius = 13.dp.toPx(), center = stop)
            drawCircle(AmberPrimary, radius = 9.dp.toPx(), center = stop)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = stop)
        }
    }
}

/**
 * A compact overview of one candidate route: the real geometry the engine
 * returned, with its own start and destination. Used by the route-alternatives
 * chooser, where there is no candidate stop to mark.
 */
@Composable
fun RouteOverviewMap(
    points: List<LatLng>,
    start: LatLng?,
    destination: LatLng?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SlateDark900)
            .testTag("route_overview_map")
            .semantics { contentDescription = "Route preview with start and destination" }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val grid = 28.dp.toPx()
            var gx = 0f
            while (gx < size.width) {
                drawLine(SlateDark800, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += grid
            }
            var gy = 0f
            while (gy < size.height) {
                drawLine(SlateDark800, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += grid
            }

            val all = buildList {
                addAll(points)
                start?.let { add(it) }
                destination?.let { add(it) }
            }
            if (all.isEmpty()) return@Canvas

            val meanLat = all.sumOf { it.latitude } / all.size
            val lonScale = cos(Math.toRadians(meanLat)).coerceAtLeast(0.2)
            val xs = all.map { it.longitude * lonScale }
            val ys = all.map { it.latitude }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()
            val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-4
            val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-4
            val pad = 26.dp.toPx()
            val usableW = (size.width - pad * 2).coerceAtLeast(1f)
            val usableH = (size.height - pad * 2).coerceAtLeast(1f)
            val scale = minOf(usableW / spanX, usableH / spanY)
            val offsetX = (size.width - spanX * scale) / 2f
            val offsetY = (size.height - spanY * scale) / 2f

            fun project(p: LatLng): Offset {
                val px = offsetX + ((p.longitude * lonScale) - minX) * scale
                val py = offsetY + (maxY - p.latitude) * scale
                return Offset(px.toFloat(), py.toFloat())
            }

            if (points.size >= 2) {
                val path = Path().apply {
                    val first = project(points.first())
                    moveTo(first.x, first.y)
                    for (i in 1 until points.size) {
                        val p = project(points[i])
                        lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    path = path,
                    color = SlateDark900,
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = RouteBlue,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            start?.let {
                val p = project(it)
                drawCircle(Color.White, radius = 8.dp.toPx(), center = p)
                drawCircle(com.example.ui.theme.StatusRidingGreen, radius = 5.5.dp.toPx(), center = p)
            }
            destination?.let {
                val p = project(it)
                drawCircle(Color.White, radius = 6.dp.toPx(), center = p)
                drawLine(
                    color = Color.White,
                    start = p,
                    end = Offset(p.x, p.y - 14.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
