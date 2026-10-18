package com.example

import com.example.data.firebase.QuickMessageRegistry
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 21 — group-chat delivery reliability primitives: dedup by stable id, burst-safety
 * (a listener snapshot burst can never make an earlier message disappear), deterministic
 * ordering when timestamps tie or arrive out of order, and bounded history.
 */
class QuickMessageRegistryTest {

    private fun msg(id: String, ts: Long, sender: String = "rider_a"): QuickMessage =
        QuickMessage(
            id = id,
            senderId = sender,
            senderName = sender.uppercase(),
            type = QuickMessageType.IM_OKAY,
            text = "msg-$id",
            timestampMs = ts
        )

    @Test
    fun `sending a valid message creates one stable message id`() {
        val registry = QuickMessageRegistry()
        val m = msg("m1", 100L)

        assertTrue("first sighting of an id is new", registry.accept(m))
        assertFalse("the same id echoed back from the server is not new", registry.accept(m.copy()))
        assertEquals(1, registry.size())
        assertEquals(1, registry.snapshot().size)
        assertEquals("server echo keeps the same content", m.id, registry.newest()!!.id)
    }

    @Test
    fun `duplicate acknowledges and retries reuse the same id without duplicating`() {
        val registry = QuickMessageRegistry()
        registry.accept(msg("m1", 100L))
        registry.accept(msg("m1", 100L)) // same stable id, e.g. pending-write echo
        registry.accept(msg("m1", 100L))
        assertEquals("retry with the same id must not create duplicates", 1, registry.size())
    }

    @Test
    fun `out-of-order burst does not make any message disappear`() {
        val registry = QuickMessageRegistry()
        // A snapshot burst may contain several docs; listener can process them unordered.
        val first = msg("burst-1", 200L)
        val second = msg("burst-2", 100L)
        val third = msg("burst-3", 150L)

        assertTrue(registry.accept(first))
        assertTrue(registry.accept(second))
        assertTrue(registry.accept(third))

        val ordered = registry.snapshot()
        assertEquals(listOf("burst-2", "burst-3", "burst-1"), ordered.map { it.id })
        assertEquals("newest message is the latest tick, ts=200", "burst-1", registry.newest()!!.id)
    }

    @Test
    fun `equal timestamps are tie-broken by id for a deterministic order`() {
        val registry = QuickMessageRegistry()
        registry.accept(msg("tie-b", 100L, sender = "b"))
        registry.accept(msg("tie-a", 100L, sender = "a"))

        assertEquals(listOf("tie-a", "tie-b"), registry.snapshot().map { it.id })
    }

    @Test
    fun `newest is empty-safe and clear resets the session`() {
        val registry = QuickMessageRegistry()
        assertNull(registry.newest())

        registry.accept(msg("m1", 100L))
        registry.clear()
        assertEquals(0, registry.size())
        assertFalse(registry.contains("m1"))
    }

    @Test
    fun `history is bounded and evicts the oldest entry by insertion`() {
        val registry = QuickMessageRegistry(capacity = 3)
        registry.accept(msg("m1", 1L))
        registry.accept(msg("m2", 2L))
        registry.accept(msg("m3", 3L))
        registry.accept(msg("m2", 4L)) // update of an existing entry must not evict silently
        registry.accept(msg("m4", 5L))

        assertEquals(3, registry.size())
        assertTrue(registry.contains("m2"))
        assertFalse("oldest non-updated entry was evicted", registry.contains("m1"))
    }
}
