package com.pocketai.chat

import com.pocketai.chat.phone.PhoneTools
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class PhoneToolsTest {
    @Test
    fun timesModelsWrite() {
        assertEquals(LocalTime.of(6, 30), PhoneTools.parseTime("06:30"))
        assertEquals(LocalTime.of(7, 0), PhoneTools.parseTime("7"))
        assertEquals(LocalTime.of(19, 5), PhoneTools.parseTime("7:05 pm"))
        assertEquals(LocalTime.of(0, 15), PhoneTools.parseTime("12:15am"))
        assertEquals(LocalTime.of(23, 59), PhoneTools.parseTime("23.59"))
    }

    @Test
    fun dateTimesModelsWrite() {
        val t = LocalDateTime.of(2026, 11, 13, 15, 0)
        assertEquals(t, PhoneTools.parseDateTime("2026-11-13T15:00"))
        assertEquals(t, PhoneTools.parseDateTime("2026-11-13 15:00"))
        assertEquals(t, PhoneTools.parseDateTime("2026-11-13T15:00:00+08:00"))
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), PhoneTools.parseDateTime("2026-11-13"))
    }

    @Test
    fun repeatDays() {
        val weekdays = listOf("mon", "Tuesday", "wed", "thu", "fri").mapNotNull { PhoneTools.dayOf(it) }
        assertEquals("Mon–Fri", PhoneTools.daysText(weekdays))
        assertEquals("weekends", PhoneTools.daysText(listOf(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)))
        assertEquals("Mon, Wed", PhoneTools.daysText(listOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
    }
}
