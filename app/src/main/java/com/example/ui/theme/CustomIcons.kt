package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object CustomIcons {
    // A distinctive brand asset rather than a generic motorcycle substitute
    val MotorcycleLogo: ImageVector
        get() = ImageVector.Builder(
            name = "MotorcycleLogo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4.0f,
                pathFillType = PathFillType.NonZero
            ) {
                // A simplified stylized motorcycle wheel and frame
                moveTo(7.5f, 18.0f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 0.0f, -9.0f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 9.0f)
                close()
                moveTo(7.5f, 11.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 0.0f, 5.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, -5.0f)
                close()
                
                moveTo(16.5f, 18.0f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 0.0f, -9.0f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 9.0f)
                close()
                moveTo(16.5f, 11.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 0.0f, 5.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, -5.0f)
                close()
                
                // Frame and handles
                moveTo(11.0f, 13.0f)
                lineToRelative(-2.0f, -4.0f)
                lineToRelative(3.5f, 0.0f)
                lineToRelative(1.5f, 3.0f)
                lineToRelative(1.0f, -1.0f)
                lineToRelative(-1.5f, -3.0f)
                lineToRelative(-2.5f, 0.0f)
                lineToRelative(2.0f, -3.0f)
                lineToRelative(3.0f, 0.0f)
                lineToRelative(0.0f, -2.0f)
                lineToRelative(-4.0f, 0.0f)
                lineToRelative(-2.5f, 3.5f)
                lineToRelative(-2.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.4f, 1.0f)
                lineToRelative(-2.0f, 5.0f)
                lineToRelative(1.9f, 0.6f)
                lineToRelative(1.5f, -3.8f)
                lineToRelative(2.1f, 4.2f)
                lineToRelative(1.9f, -0.5f)
                close()
            }
        }.build()

    // A lightweight, consistent visual for the no-rides state
    val EmptyHistory: ImageVector
        get() = ImageVector.Builder(
            name = "EmptyHistory",
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF78909C)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Clipboard with a map/location pin
                moveTo(8f, 4f)
                lineTo(16f, 4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                lineTo(18f, 20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                lineTo(8f, 20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                lineTo(6f, 6f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()

                moveTo(12f, 9f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
                curveToRelative(0f, 2.5f, -3f, 5f, -3f, 5f)
                curveToRelative(0f, 0f, -3f, -2.5f, -3f, -5f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
                close()

                moveTo(12f, 11f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
                arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
                arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                close()
            }
        }.build()
        
    // Directional, compact marker for UIs if needed
    val RiderMarker: ImageVector
        get() = ImageVector.Builder(
            name = "RiderMarker",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.0f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                lineTo(22f, 20f)
                lineTo(12f, 17f)
                lineTo(2f, 20f)
                close()
            }
        }.build()
}
