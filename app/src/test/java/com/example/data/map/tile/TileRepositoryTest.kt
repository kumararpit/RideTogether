package com.example.data.map.tile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileRepositoryTest {

    @Test
    fun `memory cache hit returns bitmap without disk or network`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        memoryCache.put(TileKey(1, 0, 0), bmp)

        val result = repository.loadBitmap(TileKey(1, 0, 0))

        assertNotNull(result)
        assertTrue(stats.toString().contains("memoryHits=1"))
        bmp.recycle()
    }

    @Test
    fun `empty tile is not fetched from network`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        diskCache.putEmpty(TileKey(1, 0, 0))

        val result = repository.loadBitmap(TileKey(1, 0, 0))

        assertNull(result)
        assertTrue(stats.toString().contains("emptyTileHits=1"))
        assertTrue(loader.loadCalls.isEmpty())
    }

    @Test
    fun `disk cache hit decodes and caches to memory`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        diskCache.put(TileKey(1, 0, 0), baos.toByteArray())

        val result = repository.loadBitmap(TileKey(1, 0, 0))

        assertNotNull(result)
        assertNotNull(memoryCache.get(TileKey(1, 0, 0)))
        assertTrue(stats.toString().contains("staleTextureReloads=1"))
        bmp.recycle()
    }

    @Test
    fun `network load downloads decodes and caches to memory and disk`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        loader.setResult(TileKey(1, 0, 0), baos.toByteArray())

        val result = repository.loadBitmap(TileKey(1, 0, 0))
        advanceUntilIdle()

        assertNotNull(result)
        assertNotNull(memoryCache.get(TileKey(1, 0, 0)))
        assertNotNull(diskCache.get(TileKey(1, 0, 0)))
        assertTrue(stats.toString().contains("networkLoads=1"))
        assertEquals(1, loader.loadCalls.size)
        bmp.recycle()
        result?.recycle()
    }

    @Test
    fun `network failure caches as empty tile and tracks failed key`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        loader.setFail(TileKey(1, 0, 0))

        val result = repository.loadBitmap(TileKey(1, 0, 0))
        advanceUntilIdle() // let scope.launch { diskCache.putEmpty(key) } complete

        assertNull(result)
        assertTrue(diskCache.isEmpty(TileKey(1, 0, 0)))
        assertTrue(stats.toString().contains("networkFailures=1"))
    }

    @Test
    fun `onConnectivityRestored retries failed keys`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        loader.setFail(TileKey(1, 0, 0))
        val firstResult = repository.loadBitmap(TileKey(1, 0, 0))
        advanceUntilIdle() // let scope.launch { diskCache.putEmpty(key) } complete

        assertNull(firstResult)
        assertTrue(diskCache.isEmpty(TileKey(1, 0, 0)))
        assertEquals(1, loader.loadCalls.size)

        val retryBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val baos = java.io.ByteArrayOutputStream()
        retryBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        loader.setResult(TileKey(1, 0, 0), baos.toByteArray())
        repository.onConnectivityRestored()

        // Let the retry cascade complete: scope.launch → removeEmpty → loadBitmap → dispatcher → decode
        for (i in 1..10) {
            advanceUntilIdle()
            if (loader.loadCalls.size >= 2) break
        }

        // Retry must have re-attempted the network load for the failed key
        assertEquals(2, loader.loadCalls.size)
        assertTrue(stats.toString().contains("networkLoads=2"))
        retryBmp.recycle()
    }

    @Test
    fun `cancelPending cancels all in-flight loads`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        loader.setSlow(TileKey(1, 0, 0))
        loader.setSlow(TileKey(1, 0, 1))

        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { delay(Long.MAX_VALUE); null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { delay(Long.MAX_VALUE); null })

        assertEquals(2, dispatcher.pendingCount())

        repository.cancelPending()

        assertEquals(0, dispatcher.pendingCount())
        assertEquals(0, dispatcher.waitingCount())
    }

    @Test
    fun `cancelKeysNotIn cancels out-of-range keys`() = runTest {
        val memoryCache = TileMemoryCache(256)
        val fakeDao = FakeTileDao()
        val diskCache = TileDiskCache(fakeDao)
        val loader = FakeTileLoader()
        val dispatcher = TileDispatcher(this, maxConcurrent = 8)
        val stats = TileStats()
        val repository = TileRepository(
            memoryCache = memoryCache,
            diskCache = diskCache,
            loader = loader,
            stats = stats,
            scope = this,
            dispatcher = dispatcher,
            isConnected = { true },
        )

        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { null })

        assertTrue(dispatcher.isPending(TileKey(1, 0, 0)))
        assertTrue(dispatcher.isPending(TileKey(1, 0, 1)))

        repository.cancelKeysNotIn(setOf(TileKey(1, 0, 0)))

        assertTrue(dispatcher.isPending(TileKey(1, 0, 0)))
        assertFalse(dispatcher.isPending(TileKey(1, 0, 1)))
    }

    // ── Fakes ──────────────────────────────────────────────────────────

    /** In-memory DAO for testing TileDiskCache without Room. */
    private class FakeTileDao : TileDao {
        private val tiles = mutableMapOf<String, TileEntity?>()
        private val emptyTiles = mutableMapOf<String, TileEntity?>()

        override suspend fun insert(tile: TileEntity) {
            tiles["${tile.zoom}/${tile.x}/${tile.y}"] = tile
            emptyTiles["${tile.zoom}/${tile.x}/${tile.y}"] = if (tile.isEmpty) tile else null
        }

        override suspend fun findTile(zoom: Int, x: Int, y: Int): TileEntity? {
            return tiles["$zoom/$x/$y"]?.let { if (it.isEmpty) null else it }
        }

        override suspend fun findEmptyTile(zoom: Int, x: Int, y: Int): TileEntity? {
            return emptyTiles["$zoom/$x/$y"]
        }

        override suspend fun loadUnexpired(nowMs: Long): List<TileEntity> = emptyList()
        override suspend fun deleteExpired(nowMs: Long): Int = 0
        override suspend fun deleteEmpty(zoom: Int, x: Int, y: Int) {
            emptyTiles.remove("$zoom/$x/$y")
        }
        override suspend fun clearAll() {
            tiles.clear()
            emptyTiles.clear()
        }
        override suspend fun count(): Int = tiles.size
        override suspend fun totalBytes(): Long? = 0L
    }

    /** Fake tile loader with programmable results per key. */
    private class FakeTileLoader : TileLoader(okhttp3.OkHttpClient()) {
        private val results = mutableMapOf<String, ByteArray?>()
        private val failures = mutableSetOf<String>()
        private val slowKeys = mutableSetOf<String>()
        val loadCalls = mutableListOf<TileKey>()

        fun setResult(key: TileKey, data: ByteArray) {
            results[key.toString()] = data
            failures.remove(key.toString())
        }

        fun setFail(key: TileKey) {
            failures.add(key.toString())
            results.remove(key.toString())
        }

        fun setSlow(key: TileKey) {
            slowKeys.add(key.toString())
        }

        override suspend fun load(url: String, isConnected: () -> Boolean): ByteArray? {
            val key = parseTileUrl(url) ?: return null
            loadCalls.add(key)
            val keyStr = key.toString()

            if (slowKeys.contains(keyStr)) {
                // Never completes — simulates a stuck load
                delay(Long.MAX_VALUE)
            }

            if (failures.contains(keyStr)) {
                // Simulates exhausted retries — real TileLoader returns null
                return null
            }

            return results[keyStr]
        }
    }
}

/** Parses an OSM tile URL back into a TileKey. */
private fun parseTileUrl(url: String): TileKey? {
    // https://{sub}.tile.openstreetmap.org/{z}/{x}/{y}.png
    val parts = url.split("/")
    if (parts.size < 5) return null
    try {
        val yPart = parts.last().removeSuffix(".png")
        return TileKey(parts[parts.size - 3].toInt(), parts[parts.size - 2].toInt(), yPart.toInt())
    } catch (_: Exception) {
        return null
    }
}
