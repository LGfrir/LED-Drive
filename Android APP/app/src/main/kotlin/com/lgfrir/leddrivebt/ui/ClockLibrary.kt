package com.lgfrir.leddrivebt.ui

import com.lgfrir.leddrivebt.ble.BleException
import com.lgfrir.leddrivebt.ble.LightBleClient
import kotlin.coroutines.cancellation.CancellationException
import com.lgfrir.leddrivebt.protocol.BackupBundle
import com.lgfrir.leddrivebt.protocol.IO_MODE_TIME
import com.lgfrir.leddrivebt.protocol.NAME_MAX_BYTES
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_FIRST
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_LAST
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.UsageReport
import com.lgfrir.leddrivebt.protocol.alignPresetIds
import com.lgfrir.leddrivebt.protocol.blankDetail
import com.lgfrir.leddrivebt.protocol.decodeBackup
import com.lgfrir.leddrivebt.protocol.encodeBackup
import com.lgfrir.leddrivebt.protocol.encodeEffect
import com.lgfrir.leddrivebt.protocol.nameBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed class ImportResult {
    data class Finished(val message: String) : ImportResult()
    data class Overflow(
        val presets: List<String>,
        val schedules: List<String>,
        val presetRoom: Int,
        val scheduleRoom: Int,
    ) : ImportResult()
}

data class LibraryState(
    val presets: List<PresetRecord> = emptyList(),
    val schedules: List<ScheduleRecord> = emptyList(),
    val bindings: Map<Int, SegBinding> = emptyMap(),
    val ioSchedules: Map<Int, Int> = emptyMap(),
    val ioScheduleNames: Map<Int, String> = emptyMap(),
    val editingPreset: PresetRecord? = null,
    val editingSchedule: ScheduleRecord? = null,
    val editingBinding: SegBinding? = null,
    val usage: UsageReport? = null,
    val usageKind: String = "",
    val usageId: Int = 0,
    val note: String = "",
    val busy: Boolean = false,
) {
    fun scheduleIdOf(ioId: Int): Int = ioSchedules[ioId] ?: 0

    fun scheduleLabel(ioId: Int): String {
        val id = ioSchedules[ioId] ?: return ""
        if (id == 0) return ""
        val chosen = schedules.firstOrNull { it.id == id }
        if (chosen != null) return chosen.name.ifEmpty { "调度 ${chosen.id}" }
        return ioScheduleNames[ioId]?.takeIf { it.isNotEmpty() } ?: "调度 $id"
    }
}

class ClockLibrary(private val ble: LightBleClient) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    fun clear() {
        _state.value = LibraryState()
    }

    fun showPresets(records: List<PresetRecord>) {
        _state.update { it.copy(presets = sortPresets(records)) }
    }

    suspend fun loadPresets() {
        val heads = ble.presetIndex.value
        val records = heads.map { head -> ble.readPreset(head.id) }
        _state.update { it.copy(presets = sortPresets(records)) }
    }

    suspend fun loadSchedules() {
        val records = ble.scheduleIds.value.map { id -> ble.readSchedule(id) }
        _state.update { it.copy(schedules = records.sortedBy { it.id }) }
    }

    fun restoreIoSchedules(saved: Map<Int, IoPin>) {
        if (saved.isEmpty()) return
        _state.update { state ->
            state.copy(
                ioSchedules = saved.mapValues { it.value.id } + state.ioSchedules,
                ioScheduleNames = saved.mapValues { it.value.name } + state.ioScheduleNames,
            )
        }
    }

    fun inferIoSchedules(groups: List<Pair<Int, List<Int>>>) {
        _state.update { state ->
            val ids = state.ioSchedules.toMutableMap()
            val names = state.ioScheduleNames.toMutableMap()
            groups.forEach { (ioId, segIds) ->
                if (ids[ioId] != null) return@forEach
                val found = segIds.firstNotNullOfOrNull { id ->
                    state.bindings[id]?.schedId?.takeIf { it != 0 }
                } ?: return@forEach
                ids[ioId] = found
                names[ioId] = state.schedules.firstOrNull { it.id == found }?.name?.ifEmpty { null } ?: "调度 $found"
            }
            state.copy(ioSchedules = ids, ioScheduleNames = names)
        }
    }

    fun pinIoSchedule(
        ioId: Int,
        schedule: ScheduleRecord,
        segIds: List<Int>,
        saved: Map<Int, List<Int>> = emptyMap(),
    ): List<SegBinding> {
        val pending = segIds.map { segId ->
            val cached = saved[segId]
            val existing = _state.value.bindings[segId]
            val oldSchedule = _state.value.schedules.firstOrNull { it.id == existing?.schedId }
            val ids = when {
                /* 这个调度以前配过：按本地缓存下来的槽位原样恢复 */
                cached != null ->
                    List(schedule.atSec.size) { cached.getOrElse(it) { PRESET_ID_NONE } }
                existing != null && oldSchedule != null ->
                    alignPresetIds(oldSchedule.atSec, existing.presetIds, schedule.atSec)
                else -> List(schedule.atSec.size) { PRESET_ID_NONE }
            }
            SegBinding(segId, schedule.id, ids)
        }
        val label = schedule.name.ifEmpty { "调度 ${schedule.id}" }
        _state.update { state ->
            state.copy(
                ioSchedules = state.ioSchedules + (ioId to schedule.id),
                ioScheduleNames = state.ioScheduleNames + (ioId to label),
                bindings = state.bindings + pending.associateBy { it.segId },
            )
        }
        return pending
    }

    fun adoptFromDevice(groups: List<Pair<Int, List<Int>>>) {
        _state.update { state ->
            val ids = state.ioSchedules.toMutableMap()
            val names = state.ioScheduleNames.toMutableMap()
            groups.forEach { (ioId, segIds) ->
                val found = segIds.firstNotNullOfOrNull { id ->
                    state.bindings[id]?.schedId?.takeIf { it != 0 }
                }
                if (found == null) {
                    ids.remove(ioId)
                    names.remove(ioId)
                } else {
                    ids[ioId] = found
                    names[ioId] = state.schedules.firstOrNull { it.id == found }?.name?.ifEmpty { null }
                        ?: "调度 $found"
                }
            }
            state.copy(ioSchedules = ids, ioScheduleNames = names)
        }
    }

    suspend fun pushBindings(bindings: List<SegBinding>) {
        if (bindings.isEmpty()) return
        var failed = 0
        var last = ""
        bindings.forEach { binding ->
            try {
                ble.writeBinding(binding)
            } catch (error: Exception) {
                failed += 1
                last = error.message ?: "同步失败"
            }
        }
        if (failed > 0) {
            val message = if (failed == bindings.size) last.ifEmpty { "调度没能同步到设备" } else "已记下，还有 $failed 段没能同步"
            throw IllegalStateException(message)
        }
    }

    suspend fun loadBindings(segIds: List<Int>): Map<Int, SegBinding> {
        val map = LinkedHashMap<Int, SegBinding>()
        segIds.forEach { id ->
            map[id] = try {
                ble.readBinding(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleException) {
                val text = error.message.orEmpty()
                if (text.contains("断开") || text.contains("超时") || text.contains("没有返回")) throw error
                SegBinding(id, 0, emptyList())
            }
        }
        _state.update { state -> state.copy(bindings = state.bindings + map) }
        return map
    }

    fun beginPreset(record: PresetRecord?) {
        _state.value = _state.value.copy(
            editingPreset = record ?: PresetRecord(0, "", blankDetail()),
            note = "",
        )
    }

    fun editPreset(block: (PresetRecord) -> PresetRecord) {
        val current = _state.value.editingPreset ?: return
        _state.value = _state.value.copy(editingPreset = block(current))
    }

    suspend fun savePreset(): String {
        val editing = _state.value.editingPreset ?: return ""
        if (editing.id in PRESET_ROM_FIRST..PRESET_ROM_LAST) return "只读"
        val used = nameBytes(editing.name)
        if (used > NAME_MAX_BYTES) return "名称已用 $used/63 字节，请缩短后再保存"
        val result = ble.writePreset(editing.id, editing.name, editing.detail)
        val saved = editing.copy(id = result.a, name = result.name ?: editing.name)
        _state.update { state ->
            val rest = state.presets.filterNot { it.id == saved.id }
            state.copy(
                presets = sortPresets(rest + saved),
                editingPreset = saved,
                note = "已保存 ${saved.name}",
            )
        }
        return _state.value.note
    }

    suspend fun requestDeletePreset(id: Int) {
        val report = ble.presetUsage(id)
        _state.value = _state.value.copy(usage = report, usageKind = "preset", usageId = id)
    }

    suspend fun confirmUsage(): String {
        val state = _state.value
        val report = when (state.usageKind) {
            "preset" -> ble.confirmDeletePreset(state.usageId).let {
                loadPresets()
                "已删除。正在使用它的灯段会改绑成同一种模式的内置效果。"
            }
            "schedule" -> ble.confirmDeleteSchedule(state.usageId).let {
                loadSchedules()
                "已删除。这些灯段已解除绑定，时间模式下会关灯。"
            }
            else -> return ""
        }
        _state.value = _state.value.copy(usage = null, usageKind = "", editingPreset = null, editingSchedule = null, note = report)
        return report
    }

    fun dismissUsage() {
        _state.value = _state.value.copy(usage = null, usageKind = "")
    }

    fun beginSchedule(record: ScheduleRecord?) {
        _state.value = _state.value.copy(
            editingSchedule = record ?: ScheduleRecord(0, "", listOf(8 * 3600)),
            note = "",
        )
    }

    fun editSchedule(record: ScheduleRecord) {
        _state.value = _state.value.copy(editingSchedule = record)
    }

    suspend fun saveSchedule(): String {
        val editing = _state.value.editingSchedule ?: return ""
        val minutes = editing.atSec.map { (it / 60) * 60 }
        if (minutes.size != minutes.distinct().size) return "同一分钟不能有两个时间点"
        if (minutes.any { it !in 0 until 86400 }) return "时间点超出当天"
        val used = nameBytes(editing.name)
        if (used > NAME_MAX_BYTES) return "名称已用 $used/63 字节，请缩短后再保存"
        val previous = _state.value.schedules.firstOrNull { it.id == editing.id && editing.id != 0 }
        val affected = if (previous != null) {
            _state.value.bindings
                .filter { it.value.schedId == previous.id }
                .mapValues { (_, binding) -> previous.atSec to binding.presetIds }
        } else {
            emptyMap()
        }
        val result = ble.writeSchedule(editing.id, editing.name, minutes)
        loadSchedules()
        val saved = _state.value.schedules.firstOrNull { it.id == result.a }
        val newTimes = saved?.atSec ?: minutes.distinct().sorted()
        val rewritten = affected.map { (seg, pair) ->
            SegBinding(seg, result.a, alignPresetIds(pair.first, pair.second, newTimes))
        }
        if (rewritten.isNotEmpty()) {
            _state.update { state ->
                state.copy(bindings = state.bindings + rewritten.associateBy { it.segId })
            }
            pushBindings(rewritten)
        }
        val note = "已保存 ${result.name ?: saved?.name.orEmpty()}"
        _state.value = _state.value.copy(editingSchedule = saved, note = note)
        return note
    }

    suspend fun requestDeleteSchedule(id: Int) {
        val report = ble.scheduleUsage(id)
        _state.value = _state.value.copy(usage = report, usageKind = "schedule", usageId = id)
    }

    fun stageBinding(segId: Int) {
        val known = _state.value.bindings[segId] ?: SegBinding(segId, 0, emptyList())
        _state.value = _state.value.copy(editingBinding = known, note = "")
    }

    suspend fun openBinding(segId: Int) {
        val binding = ble.readBinding(segId)
        _state.value = _state.value.copy(editingBinding = binding, note = "")
    }

    fun editBinding(binding: SegBinding) {
        _state.value = _state.value.copy(editingBinding = binding)
    }

    suspend fun saveBinding(): String {
        val binding = _state.value.editingBinding ?: return ""
        ble.writeBinding(binding)
        _state.value = _state.value.copy(
            bindings = _state.value.bindings + (binding.segId to binding),
            note = "绑定已写入",
        )
        return "绑定已写入"
    }

    suspend fun exportBundle(ioCount: Int, segIds: List<Int>): BackupBundle {
        loadPresets()
        loadSchedules()
        loadBindings(segIds)
        val state = _state.value
        return BackupBundle(
            fw = ble.devInfo.value?.fw.orEmpty(),
            ioCount = ioCount,
            segCount = segIds.size,
            presets = state.presets,
            schedules = state.schedules,
            bindings = segIds.mapNotNull { state.bindings[it] },
            ioModes = ble.ioModes.value,
        )
    }

    suspend fun importBundle(
    text: String,
    segIds: Set<Int>,
    ioIds: Set<Int>,
    onNote: (String) -> Unit,
    dropPresets: Set<String> = emptySet(),
    dropSchedules: Set<String> = emptySet(),
): ImportResult {
        val bundle = decodeBackup(text)
        onNote("正在读取设备")
        loadPresets()
        loadSchedules()
        val devicePresets = _state.value.presets
        val deviceSchedules = _state.value.schedules
        val presetMap = HashMap<Int, Int>()
        val pendingPresets = ArrayList<PresetRecord>()
        bundle.presets.forEach { incoming ->
            val label = incoming.name.ifEmpty { "未命名" }
            val bytes = incoming.effectBytes()
            val same = devicePresets.firstOrNull { encodeEffect(it.detail).contentEquals(bytes) }
            when {
                same != null -> presetMap[incoming.id] = same.id
                label in dropPresets -> presetMap[incoming.id] = PRESET_ID_NONE
                else -> pendingPresets += incoming
            }
        }
        val userCount = devicePresets.count { it.id !in PRESET_ROM_FIRST..PRESET_ROM_LAST }
        val presetMax = ble.devInfo.value?.presetMax ?: 32
        val schedMap = HashMap<Int, Int>()
        val pendingSchedules = ArrayList<ScheduleRecord>()
        bundle.schedules.forEach { incoming ->
            val label = incoming.name.ifEmpty { "未命名" }
            val key = incoming.atSec.sorted()
            val same = deviceSchedules.firstOrNull { it.atSec.sorted() == key }
            when {
                same != null -> schedMap[incoming.id] = same.id
                label in dropSchedules -> Unit
                else -> pendingSchedules += incoming
            }
        }
        val schedMax = ble.devInfo.value?.schedMax ?: 8
        val presetRoom = (presetMax - userCount).coerceAtLeast(0)
        val scheduleRoom = (schedMax - deviceSchedules.size).coerceAtLeast(0)
        if (pendingPresets.size > presetRoom || pendingSchedules.size > scheduleRoom) {
            return ImportResult.Overflow(
                presets = pendingPresets.map { it.name.ifEmpty { "未命名" } },
                schedules = pendingSchedules.map { it.name.ifEmpty { "未命名" } },
                presetRoom = presetRoom,
                scheduleRoom = scheduleRoom,
            )
        }
        val steps = pendingPresets.size + pendingSchedules.size + bundle.bindings.size + bundle.ioModes.size
        var done = 0
        fun tick(label: String) {
            done += 1
            onNote("$label  $done/$steps")
        }
        pendingPresets.forEach { preset ->
            try {
                val result = ble.writePreset(0, preset.name, preset.detail)
                presetMap[preset.id] = result.a
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException("预设「${preset.name.ifEmpty { "未命名" }}」没有写入：${error.message ?: "失败"}")
            }
            tick("预设")
        }
        pendingSchedules.forEach { sched ->
            try {
                val result = ble.writeSchedule(0, sched.name, sched.atSec.sorted())
                schedMap[sched.id] = result.a
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException("调度「${sched.name.ifEmpty { "未命名" }}」没有写入：${error.message ?: "失败"}")
            }
            tick("调度")
        }
        val skipped = ArrayList<String>()
        bundle.bindings.forEach { binding ->
            if (binding.segId !in segIds) {
                skipped += "段 ${binding.segId}"
                tick("绑定")
                return@forEach
            }
            val sched = if (binding.schedId == 0) 0 else schedMap[binding.schedId]
            if (binding.schedId != 0 && sched == null) {
                skipped += "段 ${binding.segId}"
                tick("绑定")
                return@forEach
            }
            val presets = binding.presetIds.map { id ->
                when {
                    id == PRESET_ID_NONE || id in PRESET_ROM_FIRST..PRESET_ROM_LAST -> id
                    else -> presetMap[id] ?: PRESET_ID_NONE
                }
            }
            try {
                ble.writeBinding(SegBinding(binding.segId, sched ?: 0, presets))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException("灯段 ${binding.segId} 的绑定没有写入：${error.message ?: "失败"}")
            }
            tick("绑定")
        }
        bundle.ioModes.forEach { (io, mode) ->
            if (io in ioIds && (mode == 0 || mode == IO_MODE_TIME)) {
                try {
                    ble.setClockIoMode(io, mode)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw IllegalStateException("IO $io 的模式没有写入：${error.message ?: "失败"}")
                }
            }
            tick("模式")
        }
        loadPresets()
        loadSchedules()
        loadBindings(segIds.toList())
        val message = if (skipped.isEmpty()) "导入完成" else "导入完成。跳过：${skipped.joinToString("、")}"
        return ImportResult.Finished(message)
    }
}

fun encodeBackupText(bundle: BackupBundle): String = encodeBackup(bundle)

private fun sortPresets(records: List<PresetRecord>): List<PresetRecord> =
    records.sortedWith(compareBy({ it.id >= PRESET_ROM_FIRST }, { it.id }))
