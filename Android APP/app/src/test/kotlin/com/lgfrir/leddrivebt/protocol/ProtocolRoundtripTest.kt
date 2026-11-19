package com.lgfrir.leddrivebt.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRoundtripTest {
    @Test
    fun effectRoundTripKeepsStaticColor() {
        val detail = blankDetail().copy(solid = StaticCfg(Rgb(12, 34, 56)))
        val decoded = decodeEffect(encodeEffect(detail))
        assertEquals(MODE_STATIC, decoded.mode)
        assertEquals(Rgb(12, 34, 56), decoded.solid.color)
    }

    @Test
    fun scheduleNameStopsAt63BytesWithoutSplittingACharacter() {
        val tooLong = "名".repeat(22)
        val decoded = decodeSchedule(encodeSchedule(1, tooLong, listOf(8 * 3600)))
        assertEquals(63, nameBytes(decoded.name))
        assertTrue(decoded.name.all { it == '名' })
    }

    @Test
    fun backupJsonKeepsPresetNameAndScheduleTime() {
        val preset = PresetRecord(3, "夜灯", blankDetail())
        val schedule = ScheduleRecord(2, "晚上", listOf(18 * 3600))
        val bundle = BackupBundle(
            fw = "test",
            ioCount = 1,
            segCount = 1,
            presets = listOf(preset),
            schedules = listOf(schedule),
            bindings = listOf(SegBinding(1, 2, listOf(3))),
            ioModes = mapOf(1 to 1),
        )
        val decoded = decodeBackup(encodeBackup(bundle))
        assertEquals("夜灯", decoded.presets.single().name)
        assertEquals(listOf(18 * 3600), decoded.schedules.single().atSec)
        assertEquals(3, decoded.bindings.single().presetIds.single())
    }
}
