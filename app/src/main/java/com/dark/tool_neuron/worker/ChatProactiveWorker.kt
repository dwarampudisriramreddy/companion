package com.dark.tool_neuron.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dark.tool_neuron.R
import com.dark.tool_neuron.engine.GenerationEvent
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.service.LLMService
import com.dark.tool_neuron.state.AppStateManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import java.util.UUID

class ChatProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val llmService = LLMService(context)
    private val appStateManager = AppStateManager(context)
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ChatProactiveWorker"

    override suspend fun doWork(): Result {
        try {
            createNotificationChannel()
            
            val memories = appStateManager.getMemories()
            val recentMessages = appStateManager.getRecentMessages()

            var systemPrompt = appStateManager.getSystemPrompt()
            if (systemPrompt.isNullOrEmpty()){
                systemPrompt = "You are a helpful assistant."
            }

            val contextBuilder = StringBuilder()
            if (memories.isNotEmpty()) {
                contextBuilder.append("Here are some things I remember about the user:
")
                memories.forEach { contextBuilder.append("- ${it.fact}
") }
            }
            if (recentMessages.isNotEmpty()) {
                contextBuilder.append("
Here is our recent conversation:
")
                recentMessages.forEach { msg ->
                    val role = when (msg.role) {
                        Role.USER -> "User"
                        Role.ASSISTANT -> "Assistant"
                        Role.SYSTEM -> "System"
                    }
                    contextBuilder.append("$role: ${msg.content.firstOrNull { it is MessageContent.Text }?.value ?: ""}
")
                }
            }

            val finalPrompt = "System: $systemPrompt

Context:
$contextBuilder

Proactive Message:
Be brief (max 2 sentences). Do not use hashtags or emojis.
Focus on being personal and demonstrating you remember them.
If you have nothing relevant to say, just say 'IDLE_SKIP'.
".trimIndent()

            // 5. Generate message
            var generatedMessage = ""
            // Explicitly specify the type parameter for Flow
            val flow: Flow<GenerationEvent> = llmService.ggufEngine.generateFlow(finalPrompt, maxTokens = 60)
            
            flow.collect { event ->
                if (event is GenerationEvent.Token) {
                    generatedMessage += event.text
                }
            }

            generatedMessage = generatedMessage.trim()

            if (generatedMessage.isEmpty() || generatedMessage.contains("IDLE_SKIP")) {
                return Result.success() // Nothing to do
            }

            // Create notification
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("A thought for you...")
                .setContentText(generatedMessage)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, Class.forName("com.dark.tool_neuron.activity.MainActivity")),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .apply {
                    val ringtoneUri = appStateManager.getRingtoneUri()
                    if (ringtoneUri != null) {
                        setSound(Uri.parse(ringtoneUri))
                    } else {
                        setDefaults(NotificationCompat.DEFAULT_ALL)
                    }
                }
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Proactive Chat",
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Notifications for proactive chat messages"
        notificationManager.createNotificationChannel(channel)
    }
}
