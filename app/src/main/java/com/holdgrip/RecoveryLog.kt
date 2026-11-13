package com.holdgrip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small bounded local diagnostic history; no network access and no personal data. */
class RecoveryLog(context: Context) {
    private val prefs = context.getSharedPreferences("holdgrip_recovery_log", Context.MODE_PRIVATE)

    fun add(message: String) {
        val entries = read().takeLast(99).toMutableList()
        entries += Entry(System.currentTimeMillis(), message.take(240))
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("time", entry.time)
                put("message", entry.message)
            })
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    fun recent(): List<Entry> = read().takeLast(100).reversed()

    fun clear() {
        prefs.edit().remove("entries").apply()
    }

    private fun read(): List<Entry> = try {
        val array = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                Entry(item.getLong("time"), item.getString("message"))
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    data class Entry(val time: Long, val message: String) {
        fun display(): String =
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time)) + "  " + message
    }
}
