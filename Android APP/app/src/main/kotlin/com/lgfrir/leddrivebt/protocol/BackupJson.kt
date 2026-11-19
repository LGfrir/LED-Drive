package com.lgfrir.leddrivebt.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class BackupBundle(
    val fw: String,
    val ioCount: Int,
    val segCount: Int,
    val presets: List<PresetRecord>,
    val schedules: List<ScheduleRecord>,
    val bindings: List<SegBinding>,
    val ioModes: Map<Int, Int>,
)

fun encodeBackup(bundle: BackupBundle): String {
    val root = JSONObject()
    root.put("schema_version", 1)
    root.put(
        "meta",
        JSONObject()
            .put("io_count", bundle.ioCount)
            .put("seg_count", bundle.segCount)
            .put("fw", bundle.fw),
    )
    root.put("presets", JSONArray().apply {
        bundle.presets.filter { !it.id.isRom() }.forEach { preset ->
            put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("effect", Base64.getEncoder().encodeToString(preset.effectBytes())),
            )
        }
    })
    root.put("schedules", JSONArray().apply {
        bundle.schedules.forEach { sched ->
            put(
                JSONObject()
                    .put("id", sched.id)
                    .put("name", sched.name)
                    .put("at_sec", JSONArray(sched.atSec)),
            )
        }
    })
    root.put("bindings", JSONArray().apply {
        bundle.bindings.forEach { binding ->
            put(
                JSONObject()
                    .put("seg_id", binding.segId)
                    .put("sched_id", binding.schedId)
                    .put("preset_ids", JSONArray(binding.presetIds)),
            )
        }
    })
    root.put("io_modes", JSONArray().apply {
        bundle.ioModes.forEach { (io, mode) ->
            put(JSONObject().put("io", io).put("mode", mode))
        }
    })
    return root.toString(2)
}

fun decodeBackup(text: String): BackupBundle {
    val root = JSONObject(text)
    if (root.optInt("schema_version") != 1) throw PacketException("备份版本无法识别")
    val meta = root.optJSONObject("meta") ?: JSONObject()
    val presets = root.optJSONArray("presets") ?: JSONArray()
    val schedules = root.optJSONArray("schedules") ?: JSONArray()
    val bindings = root.optJSONArray("bindings") ?: JSONArray()
    val modes = root.optJSONArray("io_modes") ?: JSONArray()
    return BackupBundle(
        fw = meta.optString("fw"),
        ioCount = meta.optInt("io_count"),
        segCount = meta.optInt("seg_count"),
        presets = List(presets.length()) { index ->
            val item = presets.getJSONObject(index)
            val effect = Base64.getDecoder().decode(item.getString("effect"))
            PresetRecord(item.getInt("id"), item.optString("name"), decodeEffect(effect))
        },
        schedules = List(schedules.length()) { index ->
            val item = schedules.getJSONObject(index)
            val times = item.getJSONArray("at_sec")
            ScheduleRecord(item.getInt("id"), item.optString("name"), List(times.length()) { times.getInt(it) })
        },
        bindings = List(bindings.length()) { index ->
            val item = bindings.getJSONObject(index)
            val ids = item.getJSONArray("preset_ids")
            SegBinding(item.getInt("seg_id"), item.getInt("sched_id"), List(ids.length()) { ids.getInt(it) })
        },
        ioModes = buildMap {
            repeat(modes.length()) { index ->
                val item = modes.getJSONObject(index)
                put(item.getInt("io"), item.getInt("mode"))
            }
        },
    )
}

private fun Int.isRom(): Boolean = this in PRESET_ROM_FIRST..PRESET_ROM_LAST
