package com.dark.tool_neuron.models.diary

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val topic: String = "Self", // "Self", "User", "Awareness"
    val mood: String? = null,
    val places: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val events: List<String> = emptyList()
)
