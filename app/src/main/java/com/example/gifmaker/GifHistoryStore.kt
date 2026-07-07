package com.example.gifmaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GifHistoryEntry(
    val id: String,
    val createdAt: Long,
    val outputFilePath: String,
    val thumbnailPath: String,
    val sourceUri: String,
    val fps: Int,
    val targetWidth: Int,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val frameCount: Int
)

/**
 * Stores metadata about previously-generated GIFs (path, source video,
 * and the settings used) so the Studio screen can list them and let the
 * user reopen one to tweak and re-render.
 *
 * Backed by a single SharedPreferences JSON array — deliberately simple,
 * since this is just a list of a few dozen small records at most.
 */
object GifHistoryStore {
    private const val PREFS_NAME = "gif_history"
    private const val KEY_ENTRIES = "entries"

    fun add(context: Context, entry: GifHistoryEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = readArray(prefs)
        array.put(toJson(entry))
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun getAll(context: Context): List<GifHistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = readArray(prefs)
        val result = mutableListOf<GifHistoryEntry>()
        for (i in 0 until array.length()) {
            fromJson(array.getJSONObject(i))?.let { result.add(it) }
        }
        return result.sortedByDescending { it.createdAt }
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = readArray(prefs)
        val filtered = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("id") != id) filtered.put(obj)
        }
        prefs.edit().putString(KEY_ENTRIES, filtered.toString()).apply()
    }

    /** Deletes both the history record and its backing files (GIF + thumbnail copy). */
    fun deleteWithFiles(context: Context, entry: GifHistoryEntry) {
        remove(context, entry.id)
        runCatching { File(entry.outputFilePath).delete() }
        runCatching { File(entry.thumbnailPath).delete() }
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun toJson(entry: GifHistoryEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("createdAt", entry.createdAt)
        put("outputFilePath", entry.outputFilePath)
        put("thumbnailPath", entry.thumbnailPath)
        put("sourceUri", entry.sourceUri)
        put("fps", entry.fps)
        put("targetWidth", entry.targetWidth)
        put("trimStartMs", entry.trimStartMs)
        put("trimEndMs", entry.trimEndMs)
        put("frameCount", entry.frameCount)
    }

    private fun fromJson(obj: JSONObject): GifHistoryEntry? = runCatching {
        GifHistoryEntry(
            id = obj.getString("id"),
            createdAt = obj.getLong("createdAt"),
            outputFilePath = obj.getString("outputFilePath"),
            thumbnailPath = obj.getString("thumbnailPath"),
            sourceUri = obj.getString("sourceUri"),
            fps = obj.getInt("fps"),
            targetWidth = obj.getInt("targetWidth"),
            trimStartMs = obj.getLong("trimStartMs"),
            trimEndMs = obj.getLong("trimEndMs"),
            frameCount = obj.getInt("frameCount")
        )
    }.getOrNull()
}
