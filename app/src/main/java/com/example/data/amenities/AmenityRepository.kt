package com.example.data.amenities

import android.util.Log
import com.example.data.db.RideDatabase
import com.example.data.map.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AmenityRepository(private val database: RideDatabase) {
    private val dao = database.amenityDao()
    private val queryMutex = Mutex()
    private val inFlightCells = mutableSetOf<String>()

    private val CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private val CELL_ZOOM = 14

    suspend fun getAmenitiesInViewport(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<AmenityEntity> = withContext(Dispatchers.IO) {
        val safeMinLat = minOf(minLat, maxLat)
        val safeMaxLat = maxOf(minLat, maxLat)
        val safeMinLon = minOf(minLon, maxLon)
        val safeMaxLon = maxOf(minLon, maxLon)

        // Find cells intersecting with viewport
        val minX = TileMath.lonToTileX(safeMinLon, CELL_ZOOM.toFloat()).toInt()
        val maxX = TileMath.lonToTileX(safeMaxLon, CELL_ZOOM.toFloat()).toInt()
        val maxY = TileMath.latToTileY(safeMinLat, CELL_ZOOM.toFloat()).toInt()
        val minY = TileMath.latToTileY(safeMaxLat, CELL_ZOOM.toFloat()).toInt()

        val cellsToFetch = mutableListOf<Pair<Int, Int>>()
        val now = System.currentTimeMillis()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val cellKey = "${CELL_ZOOM}_${x}_${y}"
                val ts: Long? = dao.getQueryTimestamp(cellKey)
                if (ts == null || now - ts > CACHE_EXPIRY_MS) {
                    cellsToFetch.add(x to y)
                }
            }
        }

        // Limit maximum cells per query batch to avoid Overpass timeout
        val boundedCells = cellsToFetch.take(16)

        if (boundedCells.isNotEmpty()) {
            fetchAndCacheCells(boundedCells)
        }
        
        // Return local db data
        dao.getAmenitiesInBox(safeMinLat, safeMaxLat, safeMinLon, safeMaxLon)
    }

    private suspend fun fetchAndCacheCells(cells: List<Pair<Int, Int>>) {
        val cellsToFetch = mutableListOf<Pair<Int, Int>>()
        
        queryMutex.withLock {
            for (cell in cells) {
                val key = "${CELL_ZOOM}_${cell.first}_${cell.second}"
                if (!inFlightCells.contains(key)) {
                    inFlightCells.add(key)
                    cellsToFetch.add(cell)
                }
            }
        }
        
        if (cellsToFetch.isEmpty()) return

        try {
            // Build Overpass QL for all these bounding boxes
            val queryBuilder = StringBuilder("[out:json][timeout:25];(")
            for (cell in cellsToFetch) {
                val s = TileMath.tileYToLat((cell.second + 1).toDouble(), CELL_ZOOM.toFloat())
                val n = TileMath.tileYToLat(cell.second.toDouble(), CELL_ZOOM.toFloat())
                val w = TileMath.tileXToLon(cell.first.toDouble(), CELL_ZOOM.toFloat())
                val e = TileMath.tileXToLon((cell.first + 1).toDouble(), CELL_ZOOM.toFloat())
                
                val bbox = "$s,$w,$n,$e"
                queryBuilder.append("node[\"amenity\"=\"fuel\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"fuel\"]($bbox);")
                queryBuilder.append("node[\"amenity\"=\"cafe\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"cafe\"]($bbox);")
                queryBuilder.append("node[\"shop\"=\"motorcycle_repair\"]($bbox);")
                queryBuilder.append("way[\"shop\"=\"motorcycle_repair\"]($bbox);")
                queryBuilder.append("node[\"amenity\"=\"motorcycle_repair\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"motorcycle_repair\"]($bbox);")
                queryBuilder.append("node[\"highway\"=\"rest_area\"]($bbox);")
                queryBuilder.append("way[\"highway\"=\"rest_area\"]($bbox);")
                queryBuilder.append("node[\"shop\"=\"convenience\"]($bbox);")
                queryBuilder.append("way[\"shop\"=\"convenience\"]($bbox);")
                queryBuilder.append("node[\"shop\"=\"supermarket\"]($bbox);")
                queryBuilder.append("way[\"shop\"=\"supermarket\"]($bbox);")
                queryBuilder.append("node[\"tourism\"=\"hotel\"]($bbox);")
                queryBuilder.append("way[\"tourism\"=\"hotel\"]($bbox);")
                queryBuilder.append("node[\"tourism\"=\"motel\"]($bbox);")
                queryBuilder.append("way[\"tourism\"=\"motel\"]($bbox);")
                queryBuilder.append("node[\"amenity\"=\"restaurant\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"restaurant\"]($bbox);")
                queryBuilder.append("node[\"amenity\"=\"fast_food\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"fast_food\"]($bbox);")
                queryBuilder.append("node[\"amenity\"=\"toilets\"]($bbox);")
                queryBuilder.append("way[\"amenity\"=\"toilets\"]($bbox);")
                queryBuilder.append("node[\"tourism\"=\"viewpoint\"]($bbox);")
                queryBuilder.append("way[\"tourism\"=\"viewpoint\"]($bbox);")
            }
            queryBuilder.append(");out center;")
            
            Log.d("AmenityRepository", "Fetching ${cellsToFetch.size} cells from Overpass")
            val response = OverpassApiClient.api.query(queryBuilder.toString())
            val entities = parseOverpassResponse(response)
            
            if (entities.isNotEmpty()) {
                dao.insertAmenities(entities)
            }
            
            val now = System.currentTimeMillis()
            for (cell in cellsToFetch) {
                val key = "${CELL_ZOOM}_${cell.first}_${cell.second}"
                dao.insertQueryCache(AmenityQueryCacheEntity(key, now))
            }
        } catch (e: Exception) {
            Log.e("AmenityRepository", "Failed to fetch amenities", e)
        } finally {
            queryMutex.withLock {
                for (cell in cellsToFetch) {
                    val key = "${CELL_ZOOM}_${cell.first}_${cell.second}"
                    inFlightCells.remove(key)
                }
            }
        }
    }

    private fun parseOverpassResponse(response: OverpassResponse): List<AmenityEntity> {
        val list = mutableListOf<AmenityEntity>()
        response.elements?.forEach { el ->
            val lat = el.lat ?: el.center?.lat
            val lon = el.lon ?: el.center?.lon
            if (lat != null && lon != null) {
                val tags = el.tags ?: emptyMap()
                val type = when {
                    tags["amenity"] == "fuel" -> AmenityType.FUEL
                    tags["amenity"] == "cafe" -> AmenityType.CAFE
                    tags["shop"] == "motorcycle_repair" || tags["amenity"] == "motorcycle_repair" -> AmenityType.REPAIR
                    tags["highway"] == "rest_area" || tags["amenity"] == "rest_stop" -> AmenityType.REST_STOP
                    tags["shop"] == "convenience" || tags["shop"] == "supermarket" -> AmenityType.CONVENIENCE_STORE
                    tags["tourism"] == "hotel" || tags["tourism"] == "motel" -> AmenityType.HOTEL
                    tags["amenity"] == "restaurant" || tags["amenity"] == "fast_food" -> AmenityType.RESTAURANT
                    tags["amenity"] == "toilets" -> AmenityType.TOILET
                    tags["tourism"] == "viewpoint" -> AmenityType.SCENIC_VIEWPOINT
                    else -> AmenityType.UNKNOWN
                }
                if (type != AmenityType.UNKNOWN) {
                    val tagsJson = tags.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { 
                        "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" 
                    }
                    list.add(AmenityEntity(
                        osmId = el.id,
                        type = type,
                        lat = lat,
                        lon = lon,
                        name = tags["name"],
                        tagsJson = tagsJson
                    ))
                }
            }
        }
        return list.distinctBy { it.osmId }
    }
}
