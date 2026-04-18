package com.dark.tool_neuron.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dark.tool_neuron.R
import com.dark.tool_neuron.activity.MainActivity
import com.dark.tool_neuron.di.AppContainer
import com.dark.tool_neuron.engine.GenerationEvent
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.service.LLMService
import kotlinx.coroutines.flow.Flow

class ChatProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "ChatProactiveWorker"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "proactive_chat"

    override suspend fun doWork(): Result {
        try {
            val engine = LLMService.instance?.ggufEngine
            if (engine == null || !engine.isLoaded) {
                Log.d(TAG, "No model loaded, skipping proactive message")
                return Result.success()
            }

            createNotificationChannel()

            val memoryRepo = AppContainer.getMemoryRepo()
            val chatRepo = AppContainer.getChatRepo()
            
            val memories = memoryRepo.getAllOnce()
            
            // Get last chat to get recent messages
            val chats = chatRepo.getAllChats().getOrNull()
            val recentMessages = if (!chats.isNullOrEmpty()) {
                chatRepo.getMessages(chats.first().id, limit = 10).getOrNull() ?: emptyList()
            } else {
                emptyList()
            }

            val contextBuilder = StringBuilder()
            if (memories.isNotEmpty()) {
                contextBuilder.append("Here are some things I remember about the user:\n")
                memories.take(5).forEach { contextBuilder.append("- ${it.fact}\n") }
            }
            if (recentMessages.isNotEmpty()) {
                contextBuilder.append("\nHere is our recent conversation:\n")
                recentMessages.forEach { msg ->
                    val role = when (msg.role) {
                        Role.USER -> "User"
                        Role.ASSISTANT -> "Assistant"
                        Role.SYSTEM -> "System"
                    }
                    val text = msg.content.filterIsInstance<MessageContent.Text>().firstOrNull()?.value ?: ""
                    if (text.isNotBlank()) {
                        contextBuilder.append("$role: $text\n")
                    }
                }
            }

            val finalPrompt = """
                System: You are a proactive AI companion. Your goal is to send a short, personal message to the user based on your context and memories.
                
                Context:
                $contextBuilder
                
                Proactive Message:
                Be brief (max 2 sentences). Do not use hashtags or emojis.
                Focus on being personal and demonstrating you remember them.
                If you have nothing relevant to say, just say 'IDLE_SKIP'.
            """.trimIndent()

            // Generate message
            var generatedMessage = ""
            val flow: Flow<GenerationEvent> = engine.generateFlow(finalPrompt, maxTokens = 60)
            
            flow.collect { event ->
                if (event is GenerationEvent.Token) {
                    generatedMessage += event.text
                }
            }

            generatedMessage = generatedMessage.trim()

            if (generatedMessage.isBlank() || generatedMessage.contains("IDLE_SKIP", ignoreCase = true)) {
                return Result.success()
            }

            showNotification(generatedMessage)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in proactive worker", e)
            return Result.failure()
        }
    }

    private fun showNotification(message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Thinking of you")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Proactive Companion"
            val descriptionText = "Notifications from your AI companion"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
