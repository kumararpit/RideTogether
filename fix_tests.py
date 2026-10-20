import re

with open('/home/arpit/Projects/RideTogether/app/src/test/java/com/example/MapCameraControllerTest.kt', 'r') as f:
    text = f.read()
    
# Remove panOffset and effectiveHeadingDeg from calculateVisibleTileRange in MapCameraControllerTest
text = re.sub(r'\s*panOffsetX = [^,]*,', '', text)
text = re.sub(r'\s*panOffsetY = [^,]*,', '', text)
text = re.sub(r'\s*effectiveHeadingDeg = [^,]*,', '', text)
with open('/home/arpit/Projects/RideTogether/app/src/test/java/com/example/MapCameraControllerTest.kt', 'w') as f:
    f.write(text)

with open('/home/arpit/Projects/RideTogether/app/src/test/java/com/example/MapTileRenderingAndRotationTest.kt', 'r') as f:
    text = f.read()
    
# Remove panOffset and effectiveHeadingDeg from calculateVisibleTileRange in MapTileRenderingAndRotationTest
text = re.sub(r'\s*panOffsetX = [^,]*,', '', text)
text = re.sub(r'\s*panOffsetY = [^,]*,', '', text)
text = re.sub(r'\s*effectiveHeadingDeg = [^,]*,', '', text)

text = text.replace(
    'TileMath.calculateVisibleTileRange(\n            widthPx, heightPx, widthPx / 2f, heightPx / 2f, 0f, 0f,\n            TileMath.lonToTileX(centerLng, 14f), TileMath.latToTileY(centerLat, 14f),\n            14, tileSizePx, 45f, tileMargin = 2\n        )',
    'TileMath.calculateVisibleTileRange(\n            widthPx, heightPx, widthPx / 2f, heightPx / 2f,\n            TileMath.lonToTileX(centerLng, 14f), TileMath.latToTileY(centerLat, 14f),\n            14, tileSizePx, tileMargin = 2\n        )'
)
text = text.replace(
    'TileMath.calculateVisibleTileRange(\n            widthPx, heightPx, widthPx / 2f, heightPx / 2f, 0f, 0f,\n            TileMath.lonToTileX(centerLng, 17f), TileMath.latToTileY(centerLat, 17f),\n            17, tileSizePx, 45f, tileMargin = 2\n        )',
    'TileMath.calculateVisibleTileRange(\n            widthPx, heightPx, widthPx / 2f, heightPx / 2f,\n            TileMath.lonToTileX(centerLng, 17f), TileMath.latToTileY(centerLat, 17f),\n            17, tileSizePx, tileMargin = 2\n        )'
)

with open('/home/arpit/Projects/RideTogether/app/src/test/java/com/example/MapTileRenderingAndRotationTest.kt', 'w') as f:
    f.write(text)
