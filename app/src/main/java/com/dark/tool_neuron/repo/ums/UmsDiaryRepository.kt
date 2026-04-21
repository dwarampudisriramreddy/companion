package com.dark.tool_neuron.repo.ums

import com.dark.tool_neuron.data.Tags
import com.dark.tool_neuron.data.UmsCollections
import com.dark.tool_neuron.models.diary.DiaryEntry
import com.dark.ums.UmsRecord
import com.dark.ums.UnifiedMemorySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UmsDiaryRepository(private val ums: UnifiedMemorySystem) {

    private val diaryCollection = UmsCollections.DIARY

    fun init() {
        ums.ensureCollection(diaryCollection)
        ums.addIndex(diaryCollection, Tags.Diary.ENTITY_ID, UnifiedMemorySystem.WIRE_BYTES)
        ums.addIndex(diaryCollection, Tags.Diary.TOPIC, UnifiedMemorySystem.WIRE_BYTES)
    }

    suspend fun insert(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val record = UmsRecord.create()
            .putString(Tags.Diary.ENTITY_ID, entry.id)
            .putString(Tags.Diary.CONTENT, entry.content)
            .putTimestamp(Tags.Diary.CREATED_AT, entry.createdAt)
            .putString(Tags.Diary.TOPIC, entry.topic)
            .apply {
                if (entry.mood != null) putString(Tags.Diary.MOOD, entry.mood)
            }
            .build()
        ums.put(diaryCollection, record)
    }

    suspend fun getAll(): List<DiaryEntry> = withContext(Dispatchers.IO) {
        ums.getAll(diaryCollection).map { it.toDiaryEntry() }
            .sortedByDescending { it.createdAt }
    }

    suspend fun getByTopic(topic: String): List<DiaryEntry> = withContext(Dispatchers.IO) {
        ums.queryString(diaryCollection, Tags.Diary.TOPIC, topic)
            .map { it.toDiaryEntry() }
            .sortedByDescending { it.createdAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val record = ums.queryString(diaryCollection, Tags.Diary.ENTITY_ID, id).firstOrNull()
        if (record != null) {
            ums.delete(diaryCollection, record.id)
        }
    }

    private fun UmsRecord.toDiaryEntry() = DiaryEntry(
        id = getString(Tags.Diary.ENTITY_ID) ?: "",
        content = getString(Tags.Diary.CONTENT) ?: "",
        createdAt = getTimestamp(Tags.Diary.CREATED_AT) ?: 0L,
        topic = getString(Tags.Diary.TOPIC) ?: "Self",
        mood = getString(Tags.Diary.MOOD)
    )

    private fun listToJson(list: List<String>): String {
        return org.json.JSONArray(list).toString()
    }

    private fun jsonToList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
