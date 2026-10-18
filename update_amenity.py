import re
with open('/home/arpit/Projects/RideTogether/app/src/main/java/com/example/ui/components/AmenityDetailsSheet.kt', 'r') as f:
    text = f.read()

text = text.replace(
    'val dist = com.example.data.location.RouteGeometry.distance(\n                    riderLocation.latitude, riderLocation.longitude,\n                    amenity.lat, amenity.lon\n                )',
    'val results = FloatArray(1)\n                android.location.Location.distanceBetween(riderLocation.latitude, riderLocation.longitude, amenity.lat, amenity.lon, results)\n                val dist = results[0]'
)
text = text.replace('String.format("%.1f km"', 'String.format(java.util.Locale.US, "%.1f km"')

with open('/home/arpit/Projects/RideTogether/app/src/main/java/com/example/ui/components/AmenityDetailsSheet.kt', 'w') as f:
    f.write(text)
