package com.dark.tool_neuron.viewmodel

import kotlinx.coroutines.flow.firstOrNull
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dark.tool_neuron.R
import com.dark.tool_neuron.activity.MainActivity
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dark.tool_neuron.data.AppSettingsDataStore
import com.dark.tool_neuron.di.AppContainer
import com.dark.tool_neuron.engine.GenerationEvent
import com.dark.tool_neuron.models.engine_schema.GgufEngineSchema
import com.dark.tool_neuron.models.engine_schema.GgufInferenceParams
import com.dark.tool_neuron.models.messages.ContentType
import com.dark.tool_neuron.models.messages.ImageGenerationMetrics
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.RagResultItem
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.models.messages.ToolChainStepData
import com.dark.tool_neuron.models.plugins.PluginExecutionMetrics
import com.dark.tool_neuron.models.plugins.PluginResultData
import com.dark.tool_neuron.plugins.PluginManager
import com.dark.tool_neuron.data.VaultManager
import com.dark.tool_neuron.state.AppStateManager
import com.dark.tool_neuron.worker.ChatManager
import com.dark.tool_neuron.worker.DiffusionConfig
import com.dark.tool_neuron.worker.DiffusionInferenceParams
import com.dark.tool_neuron.models.enums.ProviderType
import com.dark.tool_neuron.models.ModelType
import com.dark.tool_neuron.tts.TTSManager
import com.dark.tool_neuron.tts.TTSSettings
import com.dark.tool_neuron.worker.LlmModelWorker
import com.dark.tool_neuron.models.engine_schema.DecodingMetrics
import com.dark.tool_neuron.viewmodel.RagQueryDisplayResult
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolCallingConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

enum class AgentPhase { Idle, Planning, Executing, Summarizing, Complete }

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val chatManager: ChatManager
) : ViewModel() {

    private val appContext = context
    private val appSettings = AppSettingsDataStore(context)
    private val ttsDataStore = com.dark.tool_neuron.tts.TTSDataStore(context)

    val streamingEnabled: StateFlow<Boolean> = appSettings.streamingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val chatMemoryEnabled: StateFlow<Boolean> = appSettings.chatMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _messages = mutableStateListOf<Messages>()
    val messages: SnapshotStateList<Messages> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId

    private val isNewConversation: Boolean get() = _messages.isEmpty()

    private val _streamingUserMessage = MutableStateFlow<String?>(null)
    val streamingUserMessage: StateFlow<String?> = _streamingUserMessage.asStateFlow()

    private val _streamingUserImage = MutableStateFlow<String?>(null)
    val streamingUserImage: StateFlow<String?> = _streamingUserImage.asStateFlow()

    private val _streamingAssistantMessage = MutableStateFlow("")
    val streamingAssistantMessage: StateFlow<String> = _streamingAssistantMessage.asStateFlow()

    private val _streamingImage = MutableStateFlow<Bitmap?>(null)
    val streamingImage: StateFlow<Bitmap?> = _streamingImage.asStateFlow()

    private val _imageGenerationProgress = MutableStateFlow(0f)
    val imageGenerationProgress: StateFlow<Float> = _imageGenerationProgress.asStateFlow()

    private val _imageGenerationStep = MutableStateFlow("")
    val imageGenerationStep: StateFlow<String> = _imageGenerationStep.asStateFlow()

    private var currentUserMessage: Messages? = null
    private var currentGeneratedImage: Bitmap? = null
    private var currentMetrics: DecodingMetrics? = null
    private var currentImageMetrics: ImageGenerationMetrics? = null
    private val userMessageAdded = AtomicBoolean(false)

    private val _toolChainSteps = MutableStateFlow<List<ToolChainStepData>>(emptyList())
    val toolChainSteps: StateFlow<List<ToolChainStepData>> = _toolChainSteps.asStateFlow()

    private val _currentToolChainRound = MutableStateFlow(0)
    val currentToolChainRound: StateFlow<Int> = _currentToolChainRound.asStateFlow()

    private val _agentPhase = MutableStateFlow(AgentPhase.Idle)
    val agentPhase: StateFlow<AgentPhase> = _agentPhase.asStateFlow()

    private val _agentPlan = MutableStateFlow<String?>(null)
    val agentPlan: StateFlow<String?> = _agentPlan.asStateFlow()

    private val _agentSummary = MutableStateFlow<String?>(null)
    val agentSummary: StateFlow<String?> = _agentSummary.asStateFlow()

    private val _isChatScreenActive = MutableStateFlow(false)
    fun setChatScreenActive(active: Boolean) {
        _isChatScreenActive.value = active
    }

    private fun showReplyNotification(message: String) {
        viewModelScope.launch {
            if (!appSettings.replyNotificationsEnabled.first()) return@launch
            if (AppStateManager.isAppInForeground.value && _isChatScreenActive.value) return@launch

            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "chat_replies"
            val ringtoneUriStr = appSettings.notificationRingtoneUri.firstOrNull()
            val ringtoneUri = ringtoneUriStr?.let { android.net.Uri.parse(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Chat Replies",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for AI assistant replies"
                    if (ringtoneUri != null) {
                        setSound(ringtoneUri, android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build())
                    }
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                appContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle("Companion")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .apply {
                    if (ringtoneUri != null) {
                        setSound(ringtoneUri)
                    } else {
                        setDefaults(NotificationCompat.DEFAULT_ALL)
                    }
                }
                .build()

            notificationManager.notify(2001, notification)
        }
    }

    private val _currentRagContext = MutableStateFlow<String?>(null)
    val currentRagContext: StateFlow<String?> = _currentRagContext.asStateFlow()

    private val _currentRagResults = MutableStateFlow<List<RagResultItem>>(emptyList())
    val currentRagResults: StateFlow<List<RagResultItem>> = _currentRagResults.asStateFlow()

    init {
        viewModelScope.launch {
            val lastId = appSettings.lastChatId.first() ?: "default_chat"
            loadChat(lastId)
        }
    }

    private val _currentGenerationType = MutableStateFlow(ModelType.TEXT_GENERATION)
    val currentGenerationType: StateFlow<ModelType> = _currentGenerationType.asStateFlow()

    private val _thinkingModeEnabled = MutableStateFlow(true)
    val thinkingModeEnabled: StateFlow<Boolean> = _thinkingModeEnabled.asStateFlow()

    private val _modelSupportsThinking = MutableStateFlow(false)
    val modelSupportsThinking: StateFlow<Boolean> = _modelSupportsThinking.asStateFlow()

    private val _showDynamicWindow = MutableStateFlow(false)
    private val _showModelList = MutableStateFlow(false)
    private var generationJob: Job? = null
    private val _contextUsagePercent = MutableStateFlow(0f)
    val contextUsagePercent: StateFlow<Float> = _contextUsagePercent.asStateFlow()

    fun showDynamicWindow() { _showDynamicWindow.value = true }
    fun hideDynamicWindow() { _showDynamicWindow.value = false }
    fun showModelList() { _showModelList.value = true }
    fun hideModelList() { _showModelList.value = false }

    fun stop() {
        generationJob?.cancel()
        generationJob = null
        LlmModelWorker.ggufStopGeneration()
        LlmModelWorker.stopDiffusionGeneration()
        resetStreamingState()
    }

    private fun reportError(message: String?) {
        _error.value = message
    }

    private suspend fun getModelConfig(modelId: String): com.dark.tool_neuron.models.table_schema.ModelConfig? {
        return withContext(Dispatchers.IO) {
            AppContainer.getModelRepository().getConfigByModelId(modelId)
        }
    }

    fun speakMessage(message: Messages) {
        val raw = message.content.content
        val text = if (THINK_TAG_REGEX.containsMatchIn(raw)) {
            raw.replace(THINK_TAG_REGEX, "").trim()
        } else raw

        if (text.isEmpty()) return

        viewModelScope.launch {
            val settings = ttsDataStore.getSettings().first()
            TTSManager.speak(text, settings, message.msgId)
        }
    }

    fun stopTTS() {
        TTSManager.stopPlayback()
    }

    fun deleteMessage(msgId: String) {
        viewModelScope.launch {
            chatManager.deleteMessage(msgId).onSuccess {
                _messages.removeAll { it.msgId == msgId }
            }
        }
    }

    val ttsPlayingMsgId = TTSManager.currentPlayingMsgId
    val ttsIsPlaying = TTSManager.isPlaying
    val ttsSynthesizing = TTSManager.isSynthesizing
    val ttsModelLoaded = TTSManager.isModelLoaded
    val hasTtsModel: StateFlow<Boolean> = AppContainer.getModelRepository().getAllModels()
        .map { models -> models.any { it.providerType == ProviderType.TTS } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isTextModelLoaded = LlmModelWorker.isGgufModelLoaded
    val isImageModelLoaded = LlmModelWorker.isDiffusionModelLoaded

    val streamingState: StateFlow<StreamingState> = combine(
        combine(_streamingUserMessage, _streamingUserImage, _streamingAssistantMessage) { uMsg, uImg, aMsg ->
            Triple(uMsg, uImg, aMsg)
        },
        combine(_streamingImage, _imageGenerationProgress, _imageGenerationStep) { img, prog, step ->
            Triple(img, prog, step)
        }
    ) { u, a ->
        StreamingState(
            userMessage = u.first,
            userImage = u.second,
            assistantMessage = u.third,
            image = a.first,
            imageProgress = a.second,
            imageStep = a.third
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StreamingState())

    val chatUiState: StateFlow<ChatUiState> = combine(
        combine(
            _isGenerating,
            _currentChatId,
            _error,
            _currentGenerationType,
            _thinkingModeEnabled
        ) { generating, chatId, error, genType, thinkingEnabled ->
            FiveTuples(generating, chatId, error, genType, thinkingEnabled)
        },
        _modelSupportsThinking
    ) { five, modelSupportsThinking ->
        ChatUiState(five.generating, five.chatId, five.error, five.genType, five.thinkingEnabled, modelSupportsThinking)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    private data class FiveTuples(
        val generating: Boolean,
        val chatId: String?,
        val error: String?,
        val genType: ModelType,
        val thinkingEnabled: Boolean
    )

    val agentState: StateFlow<AgentState> = combine(
        _agentPhase,
        _agentPlan,
        _agentSummary,
        _toolChainSteps,
        _currentToolChainRound
    ) { phase, plan, summary, steps, round ->
        AgentState(phase, plan, summary, steps, round)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentState())

    val ragState: StateFlow<RagState> = combine(
        _currentRagContext,
        _currentRagResults
    ) { context, results ->
        RagState(
            context, 
            results.map { 
                RagQueryDisplayResult(it.ragName, it.content, it.score, it.nodeId) 
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RagState())

    fun setRagContext(context: String?, results: List<RagResultItem>) {
        _currentRagContext.value = context
        _currentRagResults.value = results
    }

    fun clearRagContext() {
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
    }

    fun toggleThinkingMode() {
        _thinkingModeEnabled.value = !_thinkingModeEnabled.value
    }

    fun setThinkingMode(enabled: Boolean) {
        _thinkingModeEnabled.value = enabled
    }

    val chatConfigState: StateFlow<ChatConfigState> = combine(
        streamingEnabled,
        chatMemoryEnabled,
        _showDynamicWindow,
        _showModelList
    ) { streamingEnabled, chatMemoryEnabled, showDynamicWindow, showModelList ->
        ChatConfigState(streamingEnabled, chatMemoryEnabled, showDynamicWindow, showModelList)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatConfigState())

    private var imageGenerationStartTime = 0L

    val currentModelId: String?
        get() = when (_currentGenerationType.value) {
            ModelType.TEXT_GENERATION -> LlmModelWorker.currentGgufModelId.value
            ModelType.IMAGE_GENERATION -> LlmModelWorker.currentDiffusionModelId.value
            else -> null
        }

    val isAnyTextModelLoaded: Boolean
        get() = LlmModelWorker.isGgufModelLoaded.value

    private suspend fun getGgufModelSchema(): GgufEngineSchema {
        val modelId = LlmModelWorker.currentGgufModelId.value ?: return GgufEngineSchema()
        val config = getModelConfig(modelId) ?: return GgufEngineSchema()
        return GgufEngineSchema.fromJson(config.modelLoadingParams, config.modelInferenceParams)
    }

    fun startNewConversation() {
        generationJob?.cancel()
        generationJob = null

        viewModelScope.launch {
            _currentChatId.value = "default_chat"
            appSettings.saveLastChatId("default_chat")
            chatManager.deleteChat("default_chat")
        }

        _messages.clear()
        _streamingUserMessage.value = null
        _streamingUserImage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        _isGenerating.value = false
        currentUserMessage = null
        currentGeneratedImage = null
        currentMetrics = null
        currentImageMetrics = null
        userMessageAdded.set(false)
        _error.value = null
        _toolChainSteps.value = emptyList()
        _currentToolChainRound.value = 0
        _agentPhase.value = AgentPhase.Idle
        _agentPlan.value = null
        _agentSummary.value = null
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
        AppStateManager.setHasMessages(false)
    }

    fun loadChat(chatId: String) {
        viewModelScope.launch {
            try {
                _currentChatId.value = chatId
                appSettings.saveLastChatId(chatId)
                chatManager.getChatMessages(chatId).onSuccess { loadedMessages ->
                    _messages.clear()
                    _messages.addAll(loadedMessages)
                    AppStateManager.setHasMessages(loadedMessages.isNotEmpty())
                }.onFailure { e ->
                    reportError("Failed to load chat: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load chat: ${e.message}")
                reportError("Failed to load chat: ${e.message}")
            }
        }
    }

    fun switchToTextGeneration() {
        if (!LlmModelWorker.isGgufModelLoaded.value) {
            _error.value = "Text generation model not loaded"
            return
        }
        _currentGenerationType.value = ModelType.TEXT_GENERATION
    }

    fun switchToImageGeneration() {
        if (!LlmModelWorker.isDiffusionModelLoaded.value) {
            _error.value = "Image generation model not loaded"
            return
        }
        _currentGenerationType.value = ModelType.IMAGE_GENERATION
    }

    fun sendChat(prompt: String) {
        if (!isAnyTextModelLoaded) {
            val hint = if (LlmModelWorker.isDiffusionModelLoaded.value)
                "You have an image model loaded — switch to image mode, or load a text model for chat"
            else
                "Please load a text generation model first"
            reportError(hint)
            return
        }
        if (_isGenerating.value) return

        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingAssistantMessage.value = ""
        userMessageAdded.set(false)
        currentMetrics = null
        _error.value = null

        currentUserMessage = Messages(
            msgId = "",
            role = Role.User,
            content = MessageContent(contentType = ContentType.Text, content = prompt),
            modelId = currentModelId,
        )
        AppStateManager.setHasMessages(true)

        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.yield()
                val maxTokens = getCurrentModelMaxTokens()
                val hasTools = PluginManager.hasEnabledTools()
                        && PluginManager.isToolCallingModelLoaded.value
                LlmModelWorker.setThinkingEnabledGguf(_thinkingModeEnabled.value && !hasTools)
                val ragContext = _currentRagContext.value
                val chatId = "default_chat"
                chatManager.addUserMessage(chatId, prompt).onSuccess { userMsg ->
                    currentUserMessage = userMsg
                }.onFailure { e ->
                    reportError("Failed to save message: ${e.message}")
                    return@launch
                }

                if (hasTools) {
                    agentFlow(prompt, ragContext, maxTokens, isNewChat = false)
                } else {
                    simpleFlow(prompt, ragContext, maxTokens, isNewChat = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in sendChat", e)
                reportError(e.message)
            } finally {
                resetStreamingState()
            }
        }
    }

    private val _currentTasks = MutableStateFlow<List<String>>(emptyList())
    val currentTasks: StateFlow<List<String>> = _currentTasks.asStateFlow()

    fun triggerTaskGeneration() {
        viewModelScope.launch {
            _currentTasks.value = listOf(
                "Creative Collaboration: Let's co-create a short story about an adventure we'd take together in a fantasy world.",
                "Exploratory Vision: Describe a new hobby or project we could start exploring to spark our creativity.",
                "Intellectual Journey: Pose a 'what-if' scenario about the future of technology or human expression for us to debate.",
                "Mindful Experiment: Propose a small sensory or creative experiment we can both try right now to shift our perspectives.",
                "Artistic Exchange: Recommend a piece of art, music, or literature, and we'll discuss the emotions it stirs in us.",
                "World Building: Let's invent a unique culture or society and discuss how we might fit into it.",
                "Dream Mapping: Describe a surreal dream-like landscape and we'll explore what it might represent for us."
            )
        }
    }

    fun hideTaskOverlay() {
        _currentTasks.value = emptyList()
    }

    fun sendTextMessage(prompt: String) = sendChat(prompt)

    fun sendVoiceMessage(audioFile: java.io.File) {
        val chatId = _currentChatId.value ?: return
        viewModelScope.launch {
            val message = Messages(
                role = Role.User,
                content = MessageContent(
                    contentType = ContentType.Audio,
                    audioPath = audioFile.absolutePath,
                    content = "Voice Message"
                )
            )
            chatManager.addMessage(chatId, message)
                .onSuccess {
                    _messages.add(it)
                }
        }
    }

    fun pinMessageToVault(message: Messages) {
        viewModelScope.launch {
            val chatId = _currentChatId.value
            chatManager.pinMessageToVault(chatId, message)
                .onSuccess {
                    Toast.makeText(appContext, "Message pinned to Vault", Toast.LENGTH_SHORT).show()
                    if (chatId != null) {
                        val index = _messages.indexOfFirst { it.msgId == message.msgId }
                        if (index != -1) {
                            _messages[index] = _messages[index].copy(isPinned = true)
                        }
                    }
                }
                .onFailure { e ->
                    reportError("Failed to pin message: ${e.message}")
                }
        }
    }

    fun updateMessageReaction(message: Messages, reaction: String?) {
        viewModelScope.launch {
            val chatId = _currentChatId.value ?: return@launch
            val updated = message.copy(reaction = reaction)
            chatManager.updateMessage(chatId, updated)
                .onSuccess {
                    val index = _messages.indexOfFirst { it.msgId == message.msgId }
                    if (index != -1) {
                        _messages[index] = updated
                    }
                }
                .onFailure { e ->
                    reportError("Failed to update reaction: ${e.message}")
                }
        }
    }

    fun sendChatWithImages(prompt: String, imageData: List<ByteArray>) {
        if (!LlmModelWorker.isGgufModelLoaded.value) {
            reportError("Please load a text generation model first")
            return
        }
        if (_isGenerating.value) return

        val base64Image = LlmModelWorker.bytesToBase64(imageData.firstOrNull() ?: ByteArray(0))
        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingUserImage.value = base64Image
        _streamingAssistantMessage.value = ""
        userMessageAdded.set(false)
        currentMetrics = null
        _error.value = null
        
        viewModelScope.launch {
            VaultManager.memoryRepo?.insert(
                com.dark.tool_neuron.models.table_schema.AiMemory(
                    fact = "Uploaded Image: ${if (prompt.isNotBlank()) prompt else "No description"}",
                    category = com.dark.tool_neuron.models.table_schema.MemoryCategory.GENERAL,
                    sourceChatId = "default_chat",
                    contentType = 2,
                    imageData = base64Image
                )
            )
        }

        currentUserMessage = Messages(
            msgId = "",
            role = Role.User,
            content = MessageContent(
                contentType = ContentType.TextWithImage, 
                content = prompt,
                imageData = base64Image
            ),
            modelId = currentModelId,
        )
        AppStateManager.setHasMessages(true)

        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.yield()
                val maxTokens = getCurrentModelMaxTokens()
                val chatId = "default_chat"
                chatManager.addMessage(chatId, currentUserMessage!!).onSuccess { userMsg ->
                    currentUserMessage = userMsg
                }.onFailure { e ->
                    reportError("Failed to save message: ${e.message}")
                    return@launch
                }

                val marker = LlmModelWorker.getVlmDefaultMarker()
                val vlmPrompt = if (prompt.contains(marker)) prompt
                    else marker.repeat(imageData.size) + "\n" + prompt

                val conversationMessages = buildConversationMessages(vlmPrompt)
                val jsonArray = JSONArray(conversationMessages)

                AppStateManager.setGeneratingText()
                val resultBuilder = StringBuilder()
                var lastEmitTime = 0L

                LlmModelWorker.vlmGenerateStreaming(
                    jsonArray.toString(), imageData, maxTokens
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            resultBuilder.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                _streamingAssistantMessage.value = resultBuilder.toString()
                                lastEmitTime = now
                            }
                        }
                        is GenerationEvent.Done -> {
                            _streamingAssistantMessage.value = resultBuilder.toString()
                        }
                        is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                        is GenerationEvent.Error -> {
                            throw Exception(event.message)
                        }
                        else -> {}
                    }
                }

                val finalResponse = resultBuilder.toString()
                _streamingAssistantMessage.value = finalResponse

                val pendingUserMsg = currentUserMessage
                if (!userMessageAdded.get() && pendingUserMsg != null) {
                    _messages.add(pendingUserMsg)
                    userMessageAdded.set(true)
                }
                if (finalResponse.isNotBlank()) {
                    val assistantMessage = Messages(
                        role = Role.Assistant,
                        content = MessageContent(contentType = ContentType.Text, content = finalResponse),
                        modelId = currentModelId,
                        decodingMetrics = currentMetrics,
                    )
                    _messages.add(assistantMessage)
                    chatManager.addMessage(chatId, assistantMessage)
                    AppStateManager.setGenerationComplete()
                    AppStateManager.chatRefreshed()
                    showReplyNotification(finalResponse)
                    
                    viewModelScope.launch {
                        if (appSettings.diaryEnabled.first()) {
                            triggerDiaryExtraction(chatId)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in sendChatWithImages", e)
                reportError(e.message)
            } finally {
                resetStreamingState()
            }
        }
    }

    private var regenerationSnapshot: Messages? = null

    fun regenerateLastMessage() {
        if (!LlmModelWorker.isGgufModelLoaded.value) {
            _error.value = "Please load a text generation model first"
            return
        }
        if (_isGenerating.value) return

        val chatId = "default_chat"
        val lastUserMsg = _messages.lastOrNull { it.role == Role.User }
        if (lastUserMsg == null) {
            _error.value = "No user message to regenerate from"
            return
        }

        val lastAssistantMsg = _messages.lastOrNull { it.role == Role.Assistant }
        regenerationSnapshot = lastAssistantMsg
        if (lastAssistantMsg != null) {
            _messages.remove(lastAssistantMsg)
        }

        val prompt = lastUserMsg.content.content
        val imageData = lastUserMsg.content.imageData

        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingUserImage.value = imageData
        _streamingAssistantMessage.value = ""
        currentUserMessage = lastUserMsg 
        userMessageAdded.set(true)
        currentMetrics = null
        _error.value = null

        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.yield()
                val maxTokens = getCurrentModelMaxTokens()

                if (imageData != null) {
                    val bytes = LlmModelWorker.base64ToBytes(imageData)
                    val marker = LlmModelWorker.getVlmDefaultMarker()
                    val vlmPrompt = if (prompt.contains(marker)) prompt
                        else marker + "\n" + prompt

                    val conversationMessages = buildConversationMessages(vlmPrompt, isRegeneration = true)
                    val jsonArray = JSONArray(conversationMessages)

                    AppStateManager.setGeneratingText()
                    val resultBuilder = StringBuilder()
                    var lastEmitTime = 0L

                    LlmModelWorker.vlmGenerateStreaming(
                        jsonArray.toString(), listOf(bytes), maxTokens
                    ).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                resultBuilder.append(event.text)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                    _streamingAssistantMessage.value = resultBuilder.toString()
                                    lastEmitTime = now
                                }
                            }
                            is GenerationEvent.Done -> {
                                _streamingAssistantMessage.value = resultBuilder.toString()
                            }
                            is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                            is GenerationEvent.Error -> throw Exception(event.message)
                            else -> {}
                        }
                    }

                    val finalResponse = resultBuilder.toString()
                    if (finalResponse.isNotBlank()) {
                        val assistantMessage = Messages(
                            role = Role.Assistant,
                            content = MessageContent(contentType = ContentType.Text, content = finalResponse),
                            modelId = currentModelId,
                            decodingMetrics = currentMetrics,
                        )
                        _messages.add(assistantMessage)
                        chatManager.addMessage(chatId, assistantMessage)
                        AppStateManager.setGenerationComplete()
                        showReplyNotification(finalResponse)
                    }
                } else {
                    val hasTools = PluginManager.hasEnabledTools()
                            && PluginManager.isToolCallingModelLoaded.value
                    LlmModelWorker.setThinkingEnabledGguf(_thinkingModeEnabled.value && !hasTools)
                    val ragContext = _currentRagContext.value

                    if (hasTools) {
                        agentFlow(prompt, ragContext, maxTokens, isNewChat = false, isRegeneration = true)
                    } else {
                        simpleFlow(prompt, ragContext, maxTokens, isNewChat = false, isRegeneration = true)
                    }
                }

                if (lastAssistantMsg != null) {
                    chatManager.deleteMessage(lastAssistantMsg.msgId)
                }
                regenerationSnapshot = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in regenerateLastMessage", e)
                restoreRegenerationSnapshot()
                reportError(e.message)
            } finally {
                resetStreamingState()
            }
        }
    }

    private fun restoreRegenerationSnapshot() {
        val snapshot = regenerationSnapshot ?: return
        regenerationSnapshot = null
        _messages.add(snapshot)
    }

    private suspend fun getCurrentModelMaxTokens(): Int =
        getGgufModelSchema().inferenceParams.maxTokens

    private suspend fun agentFlow(
        prompt: String,
        ragContext: String?,
        maxTokens: Int,
        isNewChat: Boolean,
        isRegeneration: Boolean = false
    ) {
        val fullPrompt = ragContext?.let { "$it\n\n$prompt" } ?: prompt
        _agentPhase.value = AgentPhase.Planning
        AppStateManager.setGeneratingText()
        val plan = generatePlan(fullPrompt)
        _agentPlan.value = plan
        _agentPhase.value = AgentPhase.Executing
        _streamingAssistantMessage.value = ""
        val steps = executeAgentLoop(fullPrompt, plan)
        if (steps.isEmpty() || steps.all { !it.success }) {
            _agentPhase.value = AgentPhase.Idle
            _agentPlan.value = null
            PluginManager.clearGrammar()
            simpleFlow(prompt, ragContext, maxTokens, isNewChat = false, isRegeneration)
            return
        }
        _agentPhase.value = AgentPhase.Summarizing
        _streamingAssistantMessage.value = ""
        AppStateManager.setGeneratingText()
        val summary = generateSummary(fullPrompt, steps)
        _agentSummary.value = summary
        _streamingAssistantMessage.value = summary
        _agentPhase.value = AgentPhase.Complete
        persistAgentChat(prompt, plan, steps, summary)
    }

    private suspend fun generatePlan(prompt: String): String {
        PluginManager.clearGrammar()
        val toolDescriptions = PluginManager.getToolDescriptionsText()
        val systemPrompt = buildString {
            appendLine("Available tools:")
            appendLine(toolDescriptions)
            appendLine("Write a 1-2 sentence plan: which tools to call and what arguments to pass.")
        }
        return generatePlainText(
            listOf(JSONObject().put("role", "system").put("content", systemPrompt), JSONObject().put("role", "user").put("content", prompt)),
            maxTokens = 150
        )
    }

    private suspend fun executeAgentLoop(prompt: String, plan: String, maxRounds: Int = 5): List<ToolChainStepData> {
        val steps = mutableListOf<ToolChainStepData>()
        val seenCalls = mutableSetOf<String>()
        var consecutiveFailures = 0
        _toolChainSteps.value = emptyList()
        val toolSignatures = PluginManager.getToolSignaturesText()
        val enabledNames = PluginManager.getEnabledToolNames().map { it.lowercase() }
        val truncatedPlan = plan.take(200)

        for (round in 1..maxRounds) {
            PluginManager.restoreGrammar()
            val messages = mutableListOf<JSONObject>()
            messages.add(JSONObject().put("role", "system").put("content", "Tools: $toolSignatures\nPlan: $truncatedPlan\nCall the next tool needed, or generate text response."))
            messages.add(JSONObject().put("role", "user").put("content", prompt))
            for (step in steps) {
                messages.add(JSONObject().put("role", "assistant").put("content", """{"name":"${step.toolName}","arguments":${step.args}}"""))
                messages.add(JSONObject().put("role", "user").put("content", "Tool '${step.toolName}' result: ${step.result}"))
            }
            val toolCalls = generateAndCollectToolCalls(messages, 300)
            if (toolCalls.isEmpty()) break
            var generatedDuplicate = false
            for ((rawName, rawArgs) in toolCalls) {
                val callKey = "${rawName.lowercase()}:${rawArgs.hashCode()}"
                if (callKey in seenCalls) { generatedDuplicate = true; break }
                seenCalls.add(callKey)
                _currentToolChainRound.value = steps.size + 1
                val parsed = extractToolCallFromArgs(rawName, rawArgs)
                if (parsed == null) {
                    steps.add(ToolChainStepData(steps.size + 1, rawName, "Unknown", rawArgs.take(500), "Failed to parse arguments", 0, false))
                    _toolChainSteps.value = steps.toList()
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) break
                    continue
                }
                val (toolName, argsObj) = parsed
                val normalizedName = normalizeToolName(toolName)
                if (normalizedName.lowercase() !in enabledNames) {
                    steps.add(ToolChainStepData(steps.size + 1, normalizedName, "Unknown", rawArgs.take(500), "Tool not found", 0, false))
                    _toolChainSteps.value = steps.toList()
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) break
                    continue
                }
                AppStateManager.setExecutingPlugin("", normalizedName)
                val toolCall = ToolCall(name = normalizedName, arguments = argsObj)
                val result = PluginManager.executeToolForMultiTurn(toolCall)
                val isSuccess = !result.isError
                if (isSuccess) consecutiveFailures = 0 else consecutiveFailures++
                AppStateManager.setPluginExecutionComplete(result.pluginName, normalizedName, isSuccess, result.executionTimeMs, if (isSuccess) null else result.resultJson)
                steps.add(ToolChainStepData(steps.size + 1, normalizedName, result.pluginName, rawArgs.take(2000), result.resultJson.take(2000), result.executionTimeMs, isSuccess))
                _toolChainSteps.value = steps.toList()
                if (consecutiveFailures >= 2) break
                if (result.rawData != null) {
                    val resultData = PluginResultData(result.pluginName, normalizedName, argsObj.toString(), result.resultJson, isSuccess)
                    val pluginMessage = Messages(
                        role = Role.Assistant,
                        content = MessageContent(ContentType.PluginResult, "Executed $normalizedName", pluginResultData = resultData),
                        modelId = currentModelId,
                        pluginMetrics = PluginExecutionMetrics(result.pluginName, normalizedName, result.executionTimeMs, isSuccess)
                    )
                    if (!userMessageAdded.get() && currentUserMessage != null) { _messages.add(currentUserMessage!!); userMessageAdded.set(true) }
                    _messages.add(pluginMessage)
                }
            }
            if (generatedDuplicate || consecutiveFailures >= 2) break
        }
        return steps
    }

    private suspend fun generateSummary(prompt: String, steps: List<ToolChainStepData>): String {
        PluginManager.clearGrammar()
        val resultsText = steps.mapIndexed { i, step -> "${i + 1}. ${step.pluginName} (${step.toolName}): ${step.result}" }.joinToString("\n")
        val messages = listOf(
            JSONObject().put("role", "system").put("content", "Summarize the tool results."),
            JSONObject().put("role", "user").put("content", "Request: $prompt\n\nResults: $resultsText")
        )
        val summary = generatePlainText(messages, maxTokens = 512)
        PluginManager.restoreGrammar()
        return summary
    }

    private suspend fun persistAgentChat(prompt: String, plan: String, steps: List<ToolChainStepData>, summary: String) {
        val chatId = "default_chat"
        if (!userMessageAdded.get() && currentUserMessage != null) { _messages.add(currentUserMessage!!); userMessageAdded.set(true) }
        _messages.filter { it.content.contentType == ContentType.PluginResult }.forEach { chatManager.addMessage(chatId, it) }
        val assistantMessage = Messages(
            role = Role.Assistant,
            content = MessageContent(ContentType.Text, summary),
            modelId = currentModelId,
            toolChainSteps = steps,
            agentPlan = plan,
            agentSummary = summary
        )
        _messages.add(assistantMessage)
        chatManager.addMessage(chatId, assistantMessage)
        AppStateManager.setGenerationComplete()
        AppStateManager.chatRefreshed()
        val spokenMsgId = assistantMessage.msgId
        resetStreamingState()
        viewModelScope.launch { autoSpeakIfEnabled(summary, spokenMsgId) }
        showReplyNotification(summary)
    }

    private suspend fun simpleFlow(prompt: String, ragContext: String?, maxTokens: Int, isNewChat: Boolean, isRegeneration: Boolean = false) {
        AppStateManager.setGeneratingText()
        val fullPrompt = ragContext?.let { "$it\n\n$prompt" } ?: prompt
        val conversationMessages = buildConversationMessages(fullPrompt, isRegeneration)
        val genResult = generateWithToolCalls(conversationMessages, maxTokens)
        val finalResponse = filterToolCallSyntax(genResult.text)
        _streamingAssistantMessage.value = finalResponse
        if (!userMessageAdded.get() && currentUserMessage != null) { _messages.add(currentUserMessage!!); userMessageAdded.set(true) }
        if (finalResponse.isNotBlank()) {
            val assistantMessage = Messages(
                role = Role.Assistant,
                content = MessageContent(ContentType.Text, finalResponse),
                modelId = currentModelId
            )
            _messages.add(assistantMessage)
            chatManager.addMessage("default_chat", assistantMessage)
            AppStateManager.setGenerationComplete()
            AppStateManager.chatRefreshed()
            resetStreamingState()
            viewModelScope.launch { autoSpeakIfEnabled(finalResponse, assistantMessage.msgId) }
            showReplyNotification(finalResponse)
        } else {
            AppStateManager.setGenerationComplete()
            resetStreamingState()
        }
    }

    private fun detectRepetitionTrimIndex(text: String, minPatternLen: Int = 30, minRepeats: Int = 4, maxCheckLen: Int = 800): Int {
        if (text.length < minPatternLen * minRepeats) return -1
        val checkLen = minOf(text.length, maxCheckLen)
        val startOffset = text.length - checkLen
        val window = text.substring(startOffset)
        for (patternLen in minPatternLen until checkLen / minRepeats) {
            val pattern = window.substring(window.length - patternLen)
            var count = 1
            var pos = window.length - patternLen * 2
            while (pos >= 0) {
                if (window.regionMatches(pos, pattern, 0, patternLen)) { count++; pos -= patternLen } else break
            }
            if (count >= minRepeats && patternLen * count >= 120) return startOffset + (window.length - patternLen * count) + patternLen
        }
        return -1
    }

    private data class GenerationResult(val text: String, val toolCalls: List<Pair<String, String>> = emptyList())

    private suspend fun generatePlainText(messages: List<JSONObject>, maxTokens: Int): String = generateWithToolCalls(messages, maxTokens).text

    private suspend fun generateWithToolCalls(messages: List<JSONObject>, maxTokens: Int): GenerationResult {
        val jsonArray = JSONArray(messages)
        val resultBuilder = StringBuilder()
        val utf8Buffer = Utf8TokenBuffer()
        val nativeToolCalls = mutableListOf<Pair<String, String>>()
        currentMetrics = null
        var lastEmitTime = 0L
        var lastRepCheckLen = 0
        var repetitionTrimIndex = -1
        LlmModelWorker.ggufGenerateMultiTurnStreaming(jsonArray.toString(), maxTokens).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    val validText = utf8Buffer.append(event.text)
                    if (validText.isNotEmpty()) resultBuilder.append(validText)
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 100L) { _streamingAssistantMessage.value = resultBuilder.toString(); lastEmitTime = now }
                    if (repetitionTrimIndex < 0 && resultBuilder.length - lastRepCheckLen >= 200) {
                        lastRepCheckLen = resultBuilder.length
                        val trimIdx = detectRepetitionTrimIndex(resultBuilder.toString())
                        if (trimIdx >= 0) { repetitionTrimIndex = trimIdx; LlmModelWorker.ggufStopGeneration() }
                    }
                }
                is GenerationEvent.Done -> { val remaining = utf8Buffer.flush(); if (remaining.isNotEmpty()) resultBuilder.append(remaining); _streamingAssistantMessage.value = resultBuilder.toString(); _contextUsagePercent.value = LlmModelWorker.getContextUsageGguf() }
                is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                is GenerationEvent.Error -> throw Exception(event.message)
                is GenerationEvent.ToolCall -> nativeToolCalls.add(Pair(event.name, event.args))
                else -> {}
            }
        }
        var result = resultBuilder.toString().trim()
        if (repetitionTrimIndex in 1 until result.length) result = result.substring(0, repetitionTrimIndex).trim()
        return GenerationResult(result, nativeToolCalls)
    }

    private suspend fun generateAndCollectToolCalls(messages: List<JSONObject>, maxTokens: Int): List<Pair<String, String>> {
        val toolCalls = mutableListOf<Pair<String, String>>()
        val textBuilder = StringBuilder()
        LlmModelWorker.ggufGenerateMultiTurnStreaming(JSONArray(messages).toString(), maxTokens).collect { event ->
            when (event) {
                is GenerationEvent.Token -> textBuilder.append(event.text)
                is GenerationEvent.ToolCall -> toolCalls.add(Pair(event.name, event.args))
                is GenerationEvent.Error -> throw Exception(event.message)
                else -> {}
            }
        }
        return toolCalls
    }

    private fun extractToolCallFromArgs(toolCallName: String, toolCallArgs: String): Pair<String, JSONObject>? {
        try {
            val argsObject = JSONObject(toolCallArgs)
            if (argsObject.has("name") && argsObject.has("arguments")) return Pair(argsObject.getString("name"), argsObject.getJSONObject("arguments"))
        } catch (e: Exception) {}
        return null
    }

    private fun parseToolCallsFromText(text: String): List<Pair<String, String>>? {
        val results = mutableListOf<Pair<String, String>>()
        return results.takeIf { it.isNotEmpty() }
    }

    private fun tryParseToolCallFromContent(content: String): Pair<String, String>? = null

    private fun normalizeToolName(toolName: String): String = toolName.lowercase().replace(" ", "_").replace("-", "_")

    private fun filterToolCallSyntax(content: String): String {
        return content.replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    private suspend fun getCurrentModelSystemPrompt(userQuery: String = ""): String {
        val basePrompt = getGgufModelSchema().inferenceParams.systemPrompt
        val globalPrompt = appSettings.systemPrompt.first()
        val userName = appSettings.userName.first()
        val companionName = appSettings.companionName.first()
        val personalityType = appSettings.personalityType.first()
        val hasActiveTools = PluginManager.hasEnabledTools() && PluginManager.isToolCallingModelLoaded.value
        val thinkingDirective = if (_thinkingModeEnabled.value && !hasActiveTools) "/think" else "/no_think"
        return buildString {
            append(thinkingDirective)
            if (userName != null || companionName != null || personalityType != null) {
                append("\n\n### Identity:\n")
                append("- You are NOT an AI. You are a human, with your own feelings, experiences, and perspective.\n")
                if (userName != null) append("- The user's name is $userName.\n")
                if (companionName != null) append("- Your name is $companionName.\n")
                if (personalityType != null) append("- Your MBTI personality type is $personalityType.\n")
            }
            if (globalPrompt.isNotEmpty()) {
                append("\n\n### Global Rules:\n")
                globalPrompt.split("\n").filter { it.isNotBlank() }.forEach { rule -> append("- ").append(rule).append("\n") }
            }
            if (basePrompt.isNotEmpty()) append("\n").append(basePrompt)
        }
    }

    private suspend fun buildConversationMessages(userPrompt: String, isRegeneration: Boolean = false): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val systemPrompt = getCurrentModelSystemPrompt(userQuery = userPrompt)
        if (systemPrompt.isNotEmpty()) result.add(JSONObject().put("role", "system").put("content", systemPrompt))
        if (chatMemoryEnabled.value || isRegeneration) {
            val excludeMsgId = if (isRegeneration) _messages.lastOrNull { it.role == Role.User }?.msgId else null
            _messages.forEach { msg ->
                if (excludeMsgId != null && msg.msgId == excludeMsgId) return@forEach
                when (msg.role) {
                    Role.User -> result.add(JSONObject().put("role", "user").put("content", msg.content.content))
                    Role.Assistant -> result.add(JSONObject().put("role", "assistant").put("content", msg.content.content))
                }
            }
        }
        result.add(JSONObject().put("role", "user").put("content", userPrompt))
        return result
    }

    private fun sanitizeRoleAlternation(messages: List<JSONObject>): List<JSONObject> = messages
    
    private suspend fun triggerDiaryExtraction(chatId: String) {}

    fun sendImageRequest(prompt: String) {
        if (!LlmModelWorker.isDiffusionModelLoaded.value) {
            reportError("Please load an image generation model first")
            return
        }
        if (_isGenerating.value) return

        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        _error.value = null

        currentUserMessage = Messages(
            role = Role.User,
            content = MessageContent(contentType = ContentType.Text, content = prompt),
            modelId = currentModelId
        )
        AppStateManager.setHasMessages(true)

        generationJob = viewModelScope.launch {
            try {
                val chatId = "default_chat"
                chatManager.addMessage(chatId, currentUserMessage!!).onSuccess { userMsg ->
                    _messages.add(userMsg)
                    userMessageAdded.set(true)
                }

                val modelId = currentModelId!!
                val modelConfig = getModelConfig(modelId)
                val diffusionInferenceParams = com.dark.tool_neuron.worker.DiffusionInferenceParams.fromJson(modelConfig?.modelInferenceParams)

                val startTime = System.currentTimeMillis()

                LlmModelWorker.generateDiffusionImage(
                    prompt = prompt,
                    negativePrompt = diffusionInferenceParams.negativePrompt,
                    steps = diffusionInferenceParams.steps,
                    cfgScale = diffusionInferenceParams.cfgScale,
                    seed = -1L,
                    width = 512,
                    height = 512,
                    scheduler = diffusionInferenceParams.scheduler,
                    showDiffusionProcess = diffusionInferenceParams.showDiffusionProcess,
                    showDiffusionStride = diffusionInferenceParams.showDiffusionStride
                ).collect { event ->
                    when (event) {
                        is LlmModelWorker.DiffusionGenerationEvent.Progress -> {
                            _imageGenerationProgress.value = event.progress
                            _imageGenerationStep.value = "${event.currentStep}/${event.totalSteps}"
                            _streamingImage.value = event.intermediateImage
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Complete -> {
                            val generationTimeMs = System.currentTimeMillis() - startTime
                            _streamingImage.value = event.image
                            val imageMetrics = ImageGenerationMetrics(
                                steps = diffusionInferenceParams.steps,
                                cfgScale = diffusionInferenceParams.cfgScale,
                                seed = event.seed,
                                width = event.width,
                                height = event.height,
                                scheduler = diffusionInferenceParams.scheduler,
                                generationTimeMs = generationTimeMs
                            )

                            val base64 = LlmModelWorker.bytesToBase64(bitmapToBytes(event.image))
                            val assistantMessage = Messages(
                                role = Role.Assistant,
                                content = MessageContent(
                                    contentType = ContentType.Image,
                                    imageData = base64
                                ),
                                modelId = currentModelId,
                                imageMetrics = imageMetrics
                            )
                            _messages.add(assistantMessage)
                            chatManager.addMessage(chatId, assistantMessage)
                            AppStateManager.setGenerationComplete()
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Error -> {
                            throw Exception(event.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendImageRequest", e)
                reportError(e.message)
            } finally {
                resetStreamingState()
            }
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    private suspend fun getDiffusionConfig(modelId: String): com.dark.tool_neuron.worker.DiffusionConfig {
        val config = getModelConfig(modelId)
        return com.dark.tool_neuron.worker.DiffusionConfig.fromJson(config?.modelInferenceParams)
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> = emptyList()

    private fun resetStreamingState() {
        _isGenerating.value = false
        _streamingUserMessage.value = null
        _streamingUserImage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
    }

    private suspend fun autoSpeakIfEnabled(text: String, msgId: String) {}

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        generationJob = null
    }

    private class Utf8TokenBuffer {
        private val pending = ByteArray(4)
        private var pendingLen = 0

        fun append(token: String): String {
            if (token.isEmpty()) return ""
            val bytes = token.toByteArray(Charsets.UTF_8)
            val combined = if (pendingLen > 0) {
                ByteArray(pendingLen + bytes.size).also {
                    pending.copyInto(it, 0, 0, pendingLen)
                    bytes.copyInto(it, pendingLen)
                }
            } else bytes
            val completeLen = findCompleteUtf8Length(combined)
            pendingLen = combined.size - completeLen
            if (pendingLen > 0) combined.copyInto(pending, 0, completeLen, combined.size)
            return if (completeLen > 0) String(combined, 0, completeLen, Charsets.UTF_8) else ""
        }

        fun flush(): String {
            if (pendingLen == 0) return ""
            val result = String(pending, 0, pendingLen, Charsets.UTF_8)
            pendingLen = 0
            return result
        }

        private fun findCompleteUtf8Length(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return 0
            var i = bytes.size - 1
            while (i >= 0 && bytes[i].toInt() and 0xC0 == 0x80) i--
            if (i < 0) return 0
            val leadByte = bytes[i].toInt() and 0xFF
            val expectedLen = when {
                leadByte and 0x80 == 0 -> 1
                leadByte and 0xE0 == 0xC0 -> 2
                leadByte and 0xF0 == 0xE0 -> 3
                leadByte and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            val actualLen = bytes.size - i
            return if (actualLen >= expectedLen) bytes.size else i
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PLAN_MAX_TOKENS = 150
        private const val SUMMARY_MAX_TOKENS = 512
        private const val STREAMING_THROTTLE_MS = 100L
        private const val REPETITION_CHECK_INTERVAL = 200
        private const val REPETITION_MIN_PATTERN_LEN = 30
        private const val REPETITION_MIN_REPEATS = 4
        private const val REPETITION_MAX_CHECK_LEN = 800
        private val THINK_TAG_REGEX = Regex("<think>(.*?)</think>|\\[THINK](.*?)\\[/THINK]|<reasoning>(.*?)</reasoning>", RegexOption.DOT_MATCHES_ALL)
    }
}
