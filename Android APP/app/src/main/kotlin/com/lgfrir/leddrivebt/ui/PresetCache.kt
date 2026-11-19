package com.lgfrir.leddrivebt.ui

import android.content.Context
import android.util.Base64
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.decodeEffect
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PresetCache(context: Context) {
    private val root = File(context.applicationContext.filesDir, "preset-cache").apply { mkdirs() }

    suspend fun load(mac: String): List<PresetRecord>? = withContext(Dispatchers.IO) {
        if (mac.isEmpty()) return@withContext null
        val file = File(root, fileName(mac))
        if (!file.isFile) return@withContext null
        runCatching { decode(file.readText()) }.getOrNull()
    }

    suspend fun save(mac: String, presets: List<PresetRecord>) = withContext(Dispatchers.IO) {
        if (mac.isEmpty()) return@withContext
        File(root, fileName(mac)).writeText(encode(presets))
    }

    private fun fileName(mac: String) = mac.replace(":", "_") + ".json"

    private fun encode(presets: List<PresetRecord>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("effect", Base64.encodeToString(preset.effectBytes(), Base64.NO_WRAP)),
            )
        }
        return array.toString()
    }

    private fun decode(text: String): List<PresetRecord> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val effect = Base64.decode(item.getString("effect"), Base64.NO_WRAP)
            PresetRecord(item.getInt("id"), item.optString("name"), decodeEffect(effect))
        }
    }
}
