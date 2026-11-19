package com.lgfrir.leddrivebt.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAlignTest {
    @Test
    fun insertKeepsPresetOnTheSameMinute() {
        val aligned = alignPresetIds(
            oldTimes = listOf(8 * 3600, 18 * 3600),
            oldIds = listOf(0x80, 0x81),
            newTimes = listOf(8 * 3600, 12 * 3600, 18 * 3600),
        )
        assertEquals(listOf(0x80, PRESET_ID_NONE, 0x81), aligned)
    }

    @Test
    fun secondsInsideTheSameMinuteStayWithThatPreset() {
        val aligned = alignPresetIds(
            oldTimes = listOf(8 * 3600 + 40),
            oldIds = listOf(0x82),
            newTimes = listOf(8 * 3600),
        )
        assertEquals(listOf(0x82), aligned)
    }

    @Test
    fun removedMinuteDropsItsPreset() {
        val aligned = alignPresetIds(
            oldTimes = listOf(8 * 3600, 12 * 3600),
            oldIds = listOf(0x80, 0x81),
            newTimes = listOf(8 * 3600),
        )
        assertEquals(listOf(0x80), aligned)
    }

    @Test
    fun nextSlotSkipsTimesAlreadyPassed() {
        val morning = 8 * 3600
        val evening = 18 * 3600
        val epoch = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, java.util.Calendar.NOVEMBER, 12, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L
        assertEquals(evening, nextSlotSec(listOf(morning, evening), epoch))
        assertEquals(0, nextSlotSec(listOf(morning), epoch))
    }
}
