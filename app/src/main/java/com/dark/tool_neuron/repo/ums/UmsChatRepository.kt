package com.dark.tool_neuron.repo.ums

import com.dark.tool_neuron.data.Tags
import com.dark.tool_neuron.data.UmsCollections
import com.dark.tool_neuron.models.messages.ContentType
import com.dark.tool_neuron.models.messages.ImageGenerationMetrics
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.RagResultItem
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.models.messages.ToolChainStepData
import com.dark.tool_neuron.models.vault.ChatExport
import com.dark.tool_neuron.models.vault.ChatInfo
import com.dark.tool_neuron.models.vault.VaultStatistics
import com.dark.tool_neuron.models.engine_schema.DecodingMetrics
import com.dark.ums.UmsRecord
import com.dark.ums.UnifiedMemorySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class UmsChatRepository(private val ums: UnifiedMemorySystem) {

    private val chatCollection = UmsCollections.CHATS
    private val msgCollection = UmsCollections.MESSAGES
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun init() {
        ums.ensureCollection(chatCollection)
        ums.addIndex(chatCollection, Tags.Chat.CHAT_ID, UnifiedMemorySystem.WIRE_BYTES)

        ums.ensureCollection(msgCollection)
        ums.addIndex(msgCollection, Tags.Message.MSG_ID, UnifiedMemorySystem.WIRE_BYTES)
        ums.addIndex(msgCollection, Tags.Message.CHAT_ID, UnifiedMemorySystem.WIRE_BYTES)
    }

    suspend fun createChat(id: String? = null, title: String = "New Chat"): String = withContext(Dispatchers.IO) {
        val chatId = id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val existing = ums.queryString(chatCollection, Tags.Chat.CHAT_ID, chatId).firstOrNull()
        val record = UmsRecord.create()
            .id(existing?.id ?: 0)
            .putString(Tags.Chat.CHAT_ID, chatId)
            .putTimestamp(Tags.Chat.CREATED_AT, existing?.getTimestamp(Tags.Chat.CREATED_AT) ?: now)
            .putString(Tags.Chat.TITLE, title)
            .putTimestamp(Tags.Chat.LAST_MESSAGE_AT, existing?.getTimestamp(Tags.Chat.LAST_MESSAGE_AT) ?: now)
            .putInt(Tags.Chat.MESSAGE_COUNT, existing?.getInt(Tags.Chat.MESSAGE_COUNT) ?: 0)
            .build()
        ums.put(chatCollection, record)
        chatId
    }

    suspend fun getAllChats(): List<ChatInfo> = withContext(Dispatchers.IO) {
        ums.getAll(chatCollection).map { it.toChatInfo() }
            .sortedByDescending { it.lastMessageTime ?: it.createdAt }
            .distinctBy { it.chatId }
    }

    suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        // Delete messages first
        val messages = ums.queryString(msgCollection, Tags.Message.CHAT_ID, chatId)
        messages.forEach { ums.delete(msgCollection, it.id) }

        // Delete chat
        val chatRecords = ums.queryString(chatCollection, Tags.Chat.CHAT_ID, chatId)
        chatRecords.forEach { ums.delete(chatCollection, it.id) }
    }

    suspend fun addMessage(chatId: String, message: Messages) = withContext(Dispatchers.IO) {
        val existing = ums.queryString(msgCollection, Tags.Message.MSG_ID, message.msgId).firstOrNull()
        ums.put(msgCollection, message.toRecord(chatId, existing?.id ?: 0))
        updateChatStats(chatId)
    }

    suspend fun updateMessage(chatId: String, message: Messages) = withContext(Dispatchers.IO) {
        val existing = ums.queryString(msgCollection, Tags.Message.MSG_ID, message.msgId).firstOrNull()
        if (existing != null) {
            ums.put(msgCollection, message.toRecord(chatId, existing.id))
        }
    }

    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        val existing = ums.queryString(msgCollection, Tags.Message.MSG_ID, messageId).firstOrNull()
        if (existing != null) {
            val chatId = existing.getString(Tags.Message.CHAT_ID)
            ums.delete(msgCollection, existing.id)
            if (chatId != null) {
                updateChatStats(chatId)
            }
        }
    }

    suspend fun getMessagesForChat(chatId: String, limit: Int = 1000): List<Messages> = withContext(Dispatchers.IO) {
        ums.queryString(msgCollection, Tags.Message.CHAT_ID, chatId)
            .map { it.toMessages() }
            .distinctBy { it.msgId }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    suspend fun getAllReactedMessages(): List<Messages> = withContext(Dispatchers.IO) {
        ums.getAll(msgCollection)
            .map { it.toMessages() }
            .filter { it.reaction != null }
            .distinctBy { it.msgId }
            .sortedByDescending { it.timestamp }
    }

    private suspend fun updateChatStats(chatId: String) {
        val chatRecord = ums.queryString(chatCollection, Tags.Chat.CHAT_ID, chatId).firstOrNull() ?: return
        val messages = ums.queryString(msgCollection, Tags.Message.CHAT_ID, chatId)
            .distinctBy { it.getString(Tags.Message.MSG_ID) }
        val count = messages.size
        val lastMsg = messages.maxByOrNull { it.getTimestamp(Tags.Message.TIMESTAMP) ?: 0L }
        val lastTime = lastMsg?.getTimestamp(Tags.Message.TIMESTAMP) ?: chatRecord.getTimestamp(Tags.Chat.CREATED_AT) ?: System.currentTimeMillis()

        val updated = UmsRecord.create()
            .id(chatRecord.id)
            .putString(Tags.Chat.CHAT_ID, chatId)
            .putTimestamp(Tags.Chat.CREATED_AT, chatRecord.getTimestamp(Tags.Chat.CREATED_AT) ?: System.currentTimeMillis())
            .putString(Tags.Chat.TITLE, chatRecord.getString(Tags.Chat.TITLE) ?: "New Chat")
            .putTimestamp(Tags.Chat.LAST_MESSAGE_AT, lastTime)
            .putInt(Tags.Chat.MESSAGE_COUNT, count)
            .build()
        ums.put(chatCollection, updated)
    }

    suspend fun exportChat(chatId: String): ChatExport = withContext(Dispatchers.IO) {
        val chat = ums.queryString(chatCollection, Tags.Chat.CHAT_ID, chatId).firstOrNull()
            ?: throw Exception("Chat not found")
        val messages = getMessagesForChat(chatId)
        ChatExport(
            chatId = chatId,
            createdAt = chat.getTimestamp(Tags.Chat.CREATED_AT) ?: 0L,
            messages = messages,
            exportedAt = System.currentTimeMillis()
        )
    }

    suspend fun importChat(export: ChatExport) = withContext(Dispatchers.IO) {
        // Create chat record if not exists
        val existing = ums.queryString(chatCollection, Tags.Chat.CHAT_ID, export.chatId).firstOrNull()
        if (existing == null) {
            val record = UmsRecord.create()
                .putString(Tags.Chat.CHAT_ID, export.chatId)
                .putTimestamp(Tags.Chat.CREATED_AT, export.createdAt)
                .putString(Tags.Chat.TITLE, "Imported Chat")
                .putTimestamp(Tags.Chat.LAST_MESSAGE_AT, export.exportedAt)
                .putInt(Tags.Chat.MESSAGE_COUNT, export.messages.size)
                .build()
            ums.put(chatCollection, record)
        }

        // Import messages
        export.messages.forEach { msg ->
            val msgExisting = ums.queryString(msgCollection, Tags.Message.MSG_ID, msg.msgId).firstOrNull()
            ums.put(msgCollection, msg.toRecord(export.chatId, msgExisting?.id ?: 0))
        }
        updateChatStats(export.chatId)
    }

    suspend fun getVaultStats(): VaultStatistics = withContext(Dispatchers.IO) {
        val allChats = ums.getAll(chatCollection)
        val allMessages = ums.getAll(msgCollection)
        
        val totalChats = allChats.distinctBy { it.getString(Tags.Chat.CHAT_ID) }.size
        val totalMessages = allMessages.distinctBy { it.getString(Tags.Message.MSG_ID) }.size
        
        val oldest = allMessages.minByOrNull { it.getTimestamp(Tags.Message.TIMESTAMP) ?: Long.MAX_VALUE }
            ?.getTimestamp(Tags.Message.TIMESTAMP) ?: 0L
        val newest = allMessages.maxByOrNull { it.getTimestamp(Tags.Message.TIMESTAMP) ?: 0L }
            ?.getTimestamp(Tags.Message.TIMESTAMP) ?: 0L
            
        VaultStatistics(
            totalChats = totalChats,
            totalMessages = totalMessages,
            totalSizeBytes = 0L, // UMS doesn't expose file size directly here
            compressionRatio = 1.0f,
            oldestMessage = oldest,
            newestMessage = newest
        )
    }

    private fun UmsRecord.toChatInfo() = ChatInfo(
        chatId = getString(Tags.Chat.CHAT_ID) ?: "",
        createdAt = getTimestamp(Tags.Chat.CREATED_AT) ?: 0L,
        messageCount = getInt(Tags.Chat.MESSAGE_COUNT) ?: 0,
        lastMessageTime = getTimestamp(Tags.Chat.LAST_MESSAGE_AT)
    )

    private fun Messages.toRecord(chatId: String, existingId: Int = 0): UmsRecord {
        val b = UmsRecord.create()
        if (existingId != 0) b.id(existingId)
        b.putString(Tags.Message.MSG_ID, msgId)
        b.putString(Tags.Message.CHAT_ID, chatId)
        b.putInt(Tags.Message.ROLE, if (role == Role.User) 0 else 1)
        b.putInt(Tags.Message.CONTENT_TYPE, when (content.contentType) {
            ContentType.None -> 0
            ContentType.Text -> 1
            ContentType.Image -> 2
            ContentType.TextWithImage -> 3
            ContentType.PluginResult -> 4
            ContentType.Audio -> 5
        })
        b.putString(Tags.Message.CONTENT, content.content)
        if (content.imageData != null) b.putString(Tags.Message.IMAGE_DATA, content.imageData)
        if (content.imagePrompt != null) b.putString(Tags.Message.IMAGE_PROMPT, content.imagePrompt)
        if (content.imageSeed != null) b.putTimestamp(Tags.Message.IMAGE_SEED, content.imageSeed)
        if (content.audioPath != null) b.putString(Tags.Message.AUDIO_PATH, content.audioPath)
        b.putTimestamp(Tags.Message.TIMESTAMP, timestamp)
        if (modelId != null) b.putString(Tags.Message.MODEL_ID, modelId)
        if (personaId != null) b.putString(Tags.Message.PERSONA_ID, personaId)
        
        decodingMetrics?.let { b.putString(Tags.Message.DECODING_METRICS, json.encodeToString(it)) }
        imageMetrics?.let { b.putString(Tags.Message.IMAGE_METRICS, json.encodeToString(it)) }
        ragResults?.let { b.putString(Tags.Message.RAG_RESULTS, json.encodeToString(it)) }
        toolChainSteps?.let { b.putString(Tags.Message.TOOL_CHAIN_STEPS, json.encodeToString(it)) }
        agentPlan?.let { b.putString(Tags.Message.AGENT_PLAN, it) }
        agentSummary?.let { b.putString(Tags.Message.AGENT_SUMMARY, it) }
        b.putInt(Tags.Message.IS_PINNED, if (isPinned) 1 else 0)
        if (reaction != null) b.putString(Tags.Message.REACTION, reaction)

        return b.build()
    }

    private fun UmsRecord.toMessages(): Messages {
        val roleVal = getInt(Tags.Message.ROLE) ?: 1
        val contentTypeVal = getInt(Tags.Message.CONTENT_TYPE) ?: 1
        val isPinnedVal = getInt(Tags.Message.IS_PINNED) ?: 0
        
        val decodingMetricsJson = getString(Tags.Message.DECODING_METRICS)
        val imageMetricsJson = getString(Tags.Message.IMAGE_METRICS)
        val ragResultsJson = getString(Tags.Message.RAG_RESULTS)
        val toolChainStepsJson = getString(Tags.Message.TOOL_CHAIN_STEPS)

        return Messages(
            msgId = getString(Tags.Message.MSG_ID) ?: "",
            role = if (roleVal == 0) Role.User else Role.Assistant,
            content = MessageContent(
                contentType = when (contentTypeVal) {
                    0 -> ContentType.None
                    1 -> ContentType.Text
                    2 -> ContentType.Image
                    ContentType.TextWithImage -> 3
                    ContentType.PluginResult -> 4
                    5 -> ContentType.Audio
                    else -> ContentType.Text
                    },
                    content = getString(Tags.Message.CONTENT) ?: "",
                    imageData = getString(Tags.Message.IMAGE_DATA),
                    imagePrompt = getString(Tags.Message.IMAGE_PROMPT),
                    imageSeed = getTimestamp(Tags.Message.IMAGE_SEED),
                    audioPath = getString(Tags.Message.AUDIO_PATH)
                    ),
            timestamp = getTimestamp(Tags.Message.TIMESTAMP) ?: 0L,
            modelId = getString(Tags.Message.MODEL_ID),
            personaId = getString(Tags.Message.PERSONA_ID),
            decodingMetrics = decodingMetricsJson?.let { runCatching { json.decodeFromString<DecodingMetrics>(it) }.getOrNull() },
            imageMetrics = imageMetricsJson?.let { runCatching { json.decodeFromString<ImageGenerationMetrics>(it) }.getOrNull() },
            ragResults = ragResultsJson?.let { runCatching { json.decodeFromString<List<RagResultItem>>(it) }.getOrNull() },
            toolChainSteps = toolChainStepsJson?.let { runCatching { json.decodeFromString<List<ToolChainStepData>>(it) }.getOrNull() },
            agentPlan = getString(Tags.Message.AGENT_PLAN),
            agentSummary = getString(Tags.Message.AGENT_SUMMARY),
            isPinned = isPinnedVal == 1,
            reaction = getString(Tags.Message.REACTION)
        )
    }
}
