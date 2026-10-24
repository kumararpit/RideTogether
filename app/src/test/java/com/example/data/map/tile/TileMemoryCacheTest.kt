package com.example.data.map.tile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileMemoryCacheTest {

    private lateinit var stats: TileStats
    private lateinit var cache: TileMemoryCache

    @Before
    fun setUp() {
        stats = TileStats()
        cache = TileMemoryCache(maxEntries = 3, stats = stats)
    }

    @Test
    fun `get returns null for absent key`() {
        assertNull(cache.get(TileKey(1, 0, 0)))
    }

    @Test
    fun `put then get returns the bitmap`() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cache.put(TileKey(1, 0, 0), bmp)
        assertSame(bmp, cache.get(TileKey(1, 0, 0)))
        bmp.recycle()
    }

    @Test
    fun `evicts LRU entries when over cap and records evictions`() {
        val bmp1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bmp3 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bmp4 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        cache.put(TileKey(1, 0, 0), bmp1)
        cache.put(TileKey(1, 0, 1), bmp2)
        cache.put(TileKey(1, 0, 2), bmp3)
        // Access bmp1 to make it recently used
        cache.get(TileKey(1, 0, 0))
        // bmp2 is now LRU; adding bmp4 evicts bmp2
        cache.put(TileKey(1, 0, 3), bmp4)

        assertNotNull(cache.get(TileKey(1, 0, 0))) // bmp1 still there (was accessed)
        assertNull(cache.get(TileKey(1, 0, 1)))   // bmp2 evicted
        assertNotNull(cache.get(TileKey(1, 0, 2))) // bmp3 still there
        assertNotNull(cache.get(TileKey(1, 0, 3))) // bmp4 just added

        assertTrue(stats.toString().contains("evictions=1"))
        bmp1.recycle(); bmp3.recycle(); bmp4.recycle()
    }

    @Test
    fun `remove recycles bitmap and records eviction`() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cache.put(TileKey(1, 0, 0), bmp)
        cache.remove(TileKey(1, 0, 0))

        assertNull(cache.get(TileKey(1, 0, 0)))
        assertTrue(stats.toString().contains("evictions=1"))
        bmp.recycle()
    }

    @Test
    fun `clear recycles all and records eviction`() {
        val bmp1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cache.put(TileKey(1, 0, 0), bmp1)
        cache.put(TileKey(1, 0, 1), bmp2)
        cache.clear()

        assertNull(cache.get(TileKey(1, 0, 0)))
        assertNull(cache.get(TileKey(1, 0, 1)))
        assertTrue(stats.toString().contains("evictions=1"))
        bmp1.recycle(); bmp2.recycle()
    }

    @Test
    fun `size and maxSize are correct`() {
        assertEquals(0, cache.size)
        assertEquals(3, cache.maxSize)

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cache.put(TileKey(1, 0, 0), bmp)
        assertEquals(1, cache.size)
        bmp.recycle()
    }
}
