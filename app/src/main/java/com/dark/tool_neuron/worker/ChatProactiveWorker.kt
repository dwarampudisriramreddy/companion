package com.dark.tool_neuron.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dark.tool_neuron.R
import com.dark.tool_neuron.activity.MainActivity
import com.dark.tool_neuron.data.AppSettingsDataStore
import com.dark.tool_neuron.data.VaultManager
import com.dark.tool_neuron.di.AppContainer
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.service.LLMService
import com.dark.tool_neuron.state.AppStateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ChatProactiveWorker"
        private const val CHANNEL_ID = "proactive_messages"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Proactive worker started")

        // 1. Check if vault is ready
        if (!VaultManager.isReady.value) {
            Log.d(TAG, "Vault not ready, skipping proactive message")
            return Result.success()
        }

        // 2. Check if a model is loaded in LLMService
        val service = LLMService.instance
        if (service == null || !service.ggufEngine.isLoaded) {
            Log.d(TAG, "Model not loaded, skipping proactive message")
            return Result.success()
        }

        try {
            // 3. Gather context: Last chat and Memories
            val chatRepo = AppContainer.getChatRepo()
            val memoryRepo = AppContainer.getMemoryRepo()
            
            val lastChat = chatRepo.getAllChats().firstOrNull() ?: return Result.success()
            val recentMessages = chatRepo.getMessagesForChat(lastChat.chatId, limit = 10)
            val memories = memoryRepo.getAllOnce().take(20)

            if (recentMessages.isEmpty() && memories.isEmpty()) {
                Log.d(TAG, "No context found, skipping")
                return Result.success()
            }

            // 4. Construct prompt for proactive message
            val contextBuilder = StringBuilder()
            contextBuilder.append("User Memories/Facts:\n")
            memories.forEach { contextBuilder.append("- ${it.fact}\n") }
            
            contextBuilder.append("\nRecent Chat History:\n")
            recentMessages.forEach { msg ->
                val role = if (msg.role == Role.User) "User" else "Assistant"
                contextBuilder.append("$role: ${msg.content.content}\n")
            }

            val systemPrompt = """
                You are a helpful and caring AI assistant with a great memory. 
                Based on the provided context (memories and recent chat), generate a SHORT, friendly, and proactive message to the user.
                The message should sound like a friend checking in or following up on something they mentioned.
                BE BRIEF (max 2 sentences). Do not use hashtags or emojis.
                Focus on being personal and demonstrating you remember them.
                If you have nothing relevant to say, just say 'IDLE_SKIP'.
            """.trimIndent()

            val finalPrompt = "System: $systemPrompt\n\nContext:\n$contextBuilder\n\nProactive Message:"

            // 5. Generate message
            var generatedMessage = ""
            val flow = service.ggufEngine.generateFlow(finalPrompt, maxTokens = 60)
            
            flow.collect { event ->
                if (event is com.dark.tool_neuron.engine.GenerationEvent.Token) {
                    generatedMessage += event.text
                }
            }

            generatedMessage = generatedMessage.trim()

            if (generatedMessage.isEmpty() || generatedMessage.contains("IDLE_SKIP")) {
                Log.d(TAG, "AI decided to skip proactive message or failed to generate")
                return Result.success()
            }

            // 6. Save message to chat history
            val newMessage = Messages(
                msgId = UUID.randomUUID().toString(),
                role = Role.Assistant,
                content = MessageContent(
                    contentType = com.dark.tool_neuron.models.messages.ContentType.Text,
                    content = generatedMessage
                ),
                timestamp = System.currentTimeMillis(),
                personaId = recentMessages.lastOrNull()?.personaId
            )
            chatRepo.addMessage(lastChat.chatId, newMessage)

            // 7. Show Notification
            showNotification(generatedMessage)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in proactive worker", e)
            return Result.failure()
        }
    }

    private fun showNotification(message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proactive Assistant",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Messages from your AI assistant"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.user) // Use existing icon
            .setContentTitle("NeuroVerse")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
