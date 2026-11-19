package com.lgfrir.leddrivebt.ui

import android.content.Context
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IoPin(val id: Int, val name: String)

/**
 * 按 MAC 存两样东西：
 *
 *   pins  每个 IO 当前选中的调度（原有功能）
 *   maps  每个「IO + 调度」下，各灯段 8 个槽位分别绑了哪个预设
 *
 * maps 存在的理由：固件里每个灯段只存**一条**绑定（只有当前调度那一份，
 * 见 clock_scheduler.c 的 bind_blob_t），所以把一个 IO 切到别的调度时，
 * 原来那套槽位分配会被覆盖。这份缓存让「切走再切回来」能原样恢复，
 * 而且不需要改 BLE 协议——恢复时仍然只是把缓存里的 map 重新 bind_set 一遍。
 *
 * 文件格式（v2）：
 * {
 *   "version": 2,
 *   "pins": { "1": {"id": 2, "name": "工作日"} },
 *   "maps": { "1|2": { "1": [128, 255], "2": [3, 255] } }
 * }
 *   "ioId|schedId" → "segId" → presetIds
 *
 * v1 的文件根对象直接就是 pins，读取时兼容。
 */
class IoScheduleStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "io-schedule").apply { mkdirs() }

    /* pins 与 maps 都是"读-改-写"，并发调用会互相覆盖 */
    private val mutex = Mutex()

    suspend fun load(mac: String): Map<Int, IoPin> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val obj = readRoot(mac) ?: return@withLock emptyMap()
            val pins = obj.optJSONObject(KEY_PINS) ?: obj
            val out = linkedMapOf<Int, IoPin>()
            pins.keys().forEach { key ->
                val ioId = key.toIntOrNull() ?: return@forEach
                val item = pins.optJSONObject(key) ?: return@forEach
                val id = item.optInt("id")
                if (id != 0) out[ioId] = IoPin(id, item.optString("name"))
            }
            out
        }
    }

    suspend fun save(mac: String, pins: Map<Int, IoPin>) = withContext(Dispatchers.IO) {
        if (mac.isEmpty()) return@withContext
        mutex.withLock {
            val obj = readRoot(mac) ?: JSONObject()
            val pinsObj = JSONObject()
            pins.forEach { (ioId, pin) ->
                pinsObj.put(
                    ioId.toString(),
                    JSONObject().put("id", pin.id).put("name", pin.name),
                )
            }
            obj.put("version", 2)
            obj.put(KEY_PINS, pinsObj)
            writeRoot(mac, obj)
        }
    }

    suspend fun loadMaps(mac: String, ioId: Int, schedId: Int): Map<Int, List<Int>> =
        withContext(Dispatchers.IO) {
            if (mac.isEmpty() || schedId == 0) return@withContext emptyMap()
            mutex.withLock {
                val obj = readRoot(mac) ?: return@withLock emptyMap()
                val segs = obj.optJSONObject(KEY_MAPS)?.optJSONObject(mapKey(ioId, schedId))
                    ?: return@withLock emptyMap()
                val out = linkedMapOf<Int, List<Int>>()
                segs.keys().forEach { key ->
                    val segId = key.toIntOrNull() ?: return@forEach
                    val arr = segs.optJSONArray(key) ?: return@forEach
                    out[segId] = List(arr.length()) { arr.optInt(it, PRESET_ID_NONE) }
                }
                out
            }
        }

    suspend fun saveMaps(mac: String, ioId: Int, schedId: Int, maps: Map<Int, List<Int>>) =
        withContext(Dispatchers.IO) {
            if (mac.isEmpty() || schedId == 0 || maps.isEmpty()) return@withContext
            mutex.withLock {
                val obj = readRoot(mac) ?: JSONObject()
                val mapsObj = obj.optJSONObject(KEY_MAPS) ?: JSONObject()
                val segs = JSONObject()
                maps.forEach { (segId, ids) ->
                    segs.put(segId.toString(), JSONArray(ids))
                }
                mapsObj.put(mapKey(ioId, schedId), segs)
                obj.put("version", 2)
                obj.put(KEY_MAPS, mapsObj)
                writeRoot(mac, obj)
            }
        }

    private fun mapKey(ioId: Int, schedId: Int) = "$ioId|$schedId"

    private fun fileName(mac: String) = mac.replace(":", "_") + ".json"

    private fun readRoot(mac: String): JSONObject? {
        if (mac.isEmpty()) return null
        val file = File(root, fileName(mac))
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun writeRoot(mac: String, obj: JSONObject) {
        File(root, fileName(mac)).writeText(obj.toString())
    }

    private companion object {
        const val KEY_PINS = "pins"
        const val KEY_MAPS = "maps"
    }
}
