import re
with open('/home/arpit/Projects/RideTogether/app/src/main/java/com/example/ui/components/RideMapCanvas.kt', 'r') as f:
    text = f.read()

# Fix Unrotated Screen logic
text = text.replace(
    'val x = anchorX + panOffsetX + ((tileX - centerTileXDouble) * baseTileSizePx).toFloat()',
    'val x = anchorX + ((tileX - centerTileXDouble) * baseTileSizePx).toFloat()'
)
text = text.replace(
    'val y = anchorY + panOffsetY + ((tileY - centerTileYDouble) * baseTileSizePx).toFloat()',
    'val y = anchorY + ((tileY - centerTileYDouble) * baseTileSizePx).toFloat()'
)
text = text.replace(
    'val tileUnrotatedX = anchorX + panOffsetX + ((tileX - centerTileXAtTileZoom) * scaledTileSizePx).toFloat()',
    'val tileUnrotatedX = anchorX + ((tileX - centerTileXAtTileZoom) * scaledTileSizePx).toFloat()'
)
text = text.replace(
    'val tileUnrotatedY = anchorY + panOffsetY + ((tileY - centerTileYAtTileZoom) * scaledTileSizePx).toFloat()',
    'val tileUnrotatedY = anchorY + ((tileY - centerTileYAtTileZoom) * scaledTileSizePx).toFloat()'
)

# Fix unrotatedToRotatedScreen
text = text.replace(
    'val dx = unrotated.x - pivotX\n            val dy = unrotated.y - pivotY\n            val rx = (pivotX + dx * cosA - dy * sinA).toFloat()\n            val ry = (pivotY + dx * sinA + dy * cosA).toFloat()',
    'val dx = unrotated.x - anchorX\n            val dy = unrotated.y - anchorY\n            val rx = (anchorX + dx * cosA - dy * sinA).toFloat()\n            val ry = (anchorY + dx * sinA + dy * cosA).toFloat()'
)

# Fix graphicsLayer
text = text.replace(
    'pivotX / widthPx,\n                        pivotY / heightPx',
    'anchorX / widthPx,\n                        anchorY / heightPx'
)

with open('/home/arpit/Projects/RideTogether/app/src/main/java/com/example/ui/components/RideMapCanvas.kt', 'w') as f:
    f.write(text)
