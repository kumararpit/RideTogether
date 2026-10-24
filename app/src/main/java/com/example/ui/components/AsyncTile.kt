package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import com.example.data.map.tile.TileKey
import com.example.data.map.tile.TileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Compose component that loads and paints a single map tile.
 *
 * Replaces raw Coil `AsyncImage` in the tile layer so RideTogether controls:
 *
 *   - Request deduplication (one in-flight load per TileKey)
 *   - Priority-ordered loading (viewport-centre tiles first)
 *   - Memory → disk → network cache hierarchy with TTL
 *   - Empty-tile caching (no re-fetch of confirmed-missing tiles)
 *   - Placeholder rendering while loading / on failure (no black gaps)
 *   - Cancellation on camera move / zoom change
 *   - Cache hit/miss statistics
 *
 * The bitmap is decoded off the main thread via [TileRepository]. While the
 * load is in flight, the placeholder color shows through. On load failure the
 * placeholder persists (never a broken-image glyph).
 */
@Composable
fun AsyncTile(
    key: TileKey,
    repository: TileRepository,
    modifier: Modifier = Modifier,
    /**
     * Load priority: lower numbers load first (0 = viewport centre).
     * Tiles closer to the camera centre get lower numbers so the map centre
     * paints before off-screen tiles.
     */
    priority: Int = 0,
    colorFilter: ColorFilter? = null,
    placeholderColor: Color = Color.Unspecified,
    contentDescription: String? = null
) {
    val coroutineScope = rememberCoroutineScope()

    val androidBitmapState = mutableStateOf<android.graphics.Bitmap?>(null)
    var loadJob: Job? = null

    // Cancel any previous load for this key when entering this effect.
    LaunchedEffect(key) {
        loadJob?.cancel()
        androidBitmapState.value = null

        loadJob = coroutineScope.launch {
            try {
                val bmp = repository.loadBitmap(key, priority = priority)
                androidBitmapState.value = bmp
            } catch (e: CancellationException) {
                // Expected on camera move / disposal — placeholder stays.
            } catch (e: Exception) {
                // Load failure — placeholder stays visible.
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (placeholderColor != Color.Unspecified) placeholderColor else Color.Unspecified)
            .drawBehind {
                val bmp = androidBitmapState.value
                if (bmp != null) {
                    val paint = Paint().apply { this@apply.colorFilter = colorFilter }
                    drawImage(
                        image = bmp.asImageBitmap(),
                        colorFilter = colorFilter
                    )
                }
            },
    )
}
