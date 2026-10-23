package com.example

import com.example.data.amenities.LocalTime
import com.example.data.amenities.OpenStatus
import com.example.data.amenities.OpeningHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The opening-hours reader.
 *
 * The rule that matters most: a value the reader does not understand yields
 * **no status at all**. A wrong "Open" is worse than no badge.
 */
class OpeningHoursTest {

    /** Wednesday 10:00. */
    private val wednesdayMorning = LocalTime(dayOfWeek = 3, minutesOfDay = 10 * 60)

    @Test
    fun `twenty four seven is always open`() {
        val status = OpeningHours.status("24/7", wednesdayMorning)
        assertEquals(OpenStatus.State.OPEN, status?.state)
    }

    @Test
    fun `a place inside its hours is open and says when it closes`() {
        val status = OpeningHours.status("Mo-Su 09:00-21:00", wednesdayMorning)
        assertEquals(OpenStatus.State.OPEN, status?.state)
        assertEquals("Open · until 21:00", status?.text)
    }

    @Test
    fun `a place before it opens says when it opens`() {
        val status = OpeningHours.status("Mo-Su 09:00-21:00", LocalTime(3, 7 * 60))
        assertEquals(OpenStatus.State.CLOSED, status?.state)
        assertEquals("Closed · opens 9:00", status?.text)
    }

    @Test
    fun `a place after it closes says when it opens again`() {
        // It opens every day, so at 22:00 the rider is told when it opens
        // again rather than only that it is shut.
        val status = OpeningHours.status("Mo-Su 09:00-21:00", LocalTime(3, 22 * 60))
        assertEquals(OpenStatus.State.CLOSED, status?.state)
        assertEquals("Closed · opens tomorrow 9:00", status?.text)
    }

    @Test
    fun `a place closed today says when it opens next`() {
        // Open Monday to Friday; it is Wednesday 22:00 here.
        val status = OpeningHours.status("Mo-Fr 08:00-20:00", LocalTime(3, 22 * 60))
        assertEquals(OpenStatus.State.CLOSED, status?.state)
        assertEquals("Closed · opens tomorrow 8:00", status?.text)
    }

    @Test
    fun `several rules in one day are read`() {
        val status = OpeningHours.status("Mo-Su 08:00-12:00,16:00-20:00", wednesdayMorning)
        assertEquals(OpenStatus.State.OPEN, status?.state)
        assertEquals("Open · until 12:00", status?.text)
    }

    @Test
    fun `several days in one rule are read`() {
        val status = OpeningHours.status("Sa,Su 10:00-18:00", LocalTime(6, 12 * 60))
        assertEquals(OpenStatus.State.OPEN, status?.state)
    }

    @Test
    fun `a day that is not covered is closed`() {
        val status = OpeningHours.status("Sa,Su 10:00-18:00", wednesdayMorning)
        assertEquals(OpenStatus.State.CLOSED, status?.state)
    }

    @Test
    fun `an unreadable value produces no status at all`() {
        assertNull(OpeningHours.status("sunrise to sunset", wednesdayMorning))
        assertNull(OpeningHours.status("Jan 1-31 10:00-12:00", wednesdayMorning))
        assertNull(OpeningHours.status("Mo-Fr 08:00-20:00 open from 2020", wednesdayMorning))
        assertNull(OpeningHours.status(null, wednesdayMorning))
        assertNull(OpeningHours.status("", wednesdayMorning))
    }

    @Test
    fun `a closed tag closes the place`() {
        val status = OpeningHours.status("closed", wednesdayMorning)
        assertEquals(OpenStatus.State.CLOSED, status?.state)
    }

    @Test
    fun `the closing time of a range that crosses midnight is not understood`() {
        // A window that ends before it starts cannot be read, and a guess
        // would be worse than nothing.
        assertNull(OpeningHours.status("Mo-Su 22:00-02:00", wednesdayMorning))
    }
}
