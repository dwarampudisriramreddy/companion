package com.dark.tool_neuron.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dark.tool_neuron.models.ModelType
import com.dark.tool_neuron.models.messages.ContentType
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.ui.components.MarkdownText
import com.dark.tool_neuron.ui.theme.Motion
import com.dark.tool_neuron.viewmodel.ChatViewModel
import com.dark.tool_neuron.viewmodel.LLMModelViewModel
import com.dark.tool_neuron.global.Standards
import java.text.SimpleDateFormat
import java.util.*

// ── Pre-compiled regex ──

internal val THINK_TAG_REGEX = Regex(
    "<think>(.*?)</think>|\\[THINK](.*?)\\[/THINK]|<reasoning>(.*?)</reasoning>",
    RegexOption.DOT_MATCHES_ALL
)

@Composable
fun BodyContent(
    paddingValues: PaddingValues,
    chatViewModel: ChatViewModel,
    llmModelViewModel: LLMModelViewModel
) {
    val messages = chatViewModel.messages
    val streaming by chatViewModel.streamingState.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val agent by chatViewModel.agentState.collectAsStateWithLifecycle()
    val config by chatViewModel.chatConfigState.collectAsStateWithLifecycle()
    val appState by com.dark.tool_neuron.state.AppStateManager.appState.collectAsStateWithLifecycle()
    val ttsPlayingMsgId by chatViewModel.ttsPlayingMsgId.collectAsStateWithLifecycle()
    val ttsIsPlaying by chatViewModel.ttsIsPlaying.collectAsStateWithLifecycle()
    val ttsSynthesizing by chatViewModel.ttsSynthesizing.collectAsStateWithLifecycle()
    val ttsModelLoaded by chatViewModel.ttsModelLoaded.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val dataStore = remember { com.dark.tool_neuron.data.AppSettingsDataStore(context) }
    val imageBlurEnabled by dataStore.imageBlurEnabled.collectAsStateWithLifecycle(initialValue = true)

    val listState = rememberLazyListState()

    var selectedMessage by remember { mutableStateOf<Messages?>(null) }
    var showActionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !chatState.isGenerating) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
    ) {
        if (messages.isEmpty() && !chatState.isGenerating) {
            EmptyMessagesState()
        } else {
            if (chatState.isGenerating && streaming.userMessage != null) {
                StreamingView(
                    userMessage = streaming.userMessage!!,
                    userImage = streaming.userImage,
                    assistantMessage = streaming.assistantMessage,
                    streamingImage = streaming.image,
                    imageProgress = streaming.imageProgress,
                    imageStep = streaming.imageStep,
                    isImageGeneration = chatState.generationType == ModelType.IMAGE_GENERATION,
                    ragResults = emptyList(),
                    appState = appState,
                    messages = messages,
                    toolChainSteps = agent.toolChainSteps,
                    currentToolChainRound = agent.currentRound,
                    agentPhase = agent.phase,
                    agentPlan = agent.plan,
                    agentSummary = agent.summary,
                    thinkingEnabled = chatState.thinkingEnabled
                )
            } else {
                val deduped = remember(messages.size) { messages.distinctBy { it.msgId } }
                val lastAssistantIndex = remember(deduped.size) { deduped.indexOfLast { it.role == Role.Assistant } }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Standards.SpacingSm),
                    verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
                ) {
                    var lastDate = ""

                    deduped.forEachIndexed { index, message ->
                        val currentDate = formatDateHeader(message.timestamp)
                        if (currentDate != lastDate) {
                            item(key = "date-$currentDate") {
                                DateHeader(currentDate)
                            }
                            lastDate = currentDate
                        }

                        when (message.role) {
                            Role.User -> {
                                item(key = "${message.msgId}-user") {
                                    UserMessageBubble(message, onLongClick = {
                                        selectedMessage = message
                                        showActionsSheet = true
                                    })
                                }
                            }
                            else -> {
                                val isLastAssistant = index == lastAssistantIndex
                                item(key = "${message.msgId}-bubble") {
                                    AssistantMessageBubble(message, onLongClick = {
                                        selectedMessage = message
                                        showActionsSheet = true
                                    }) {
                                        Column {
                                            AssistantMessageHeader(message, imageBlurEnabled)
                                            if (message.content.contentType == ContentType.Text) {
                                                val raw = message.content.content
                                                val parsedText = if (THINK_TAG_REGEX.containsMatchIn(raw)) {
                                                    raw.replace(THINK_TAG_REGEX, "").trim()
                                                } else raw
                                                if (parsedText.isNotEmpty()) {
                                                    MarkdownText(
                                                        text = parsedText,
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    )
                                                }
                                            }
                                            AssistantMessageFooter(message = message)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (chatState.isGenerating && streaming.assistantMessage.isEmpty()) {
                        item(key = "typing-indicator") {
                            TypingIndicator()
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(Standards.SpacingLg))
                    }
                }
            }
        }

        if (showActionsSheet && selectedMessage != null) {
            val isLastAssistant = messages.indexOfLast { it.role == Role.Assistant && it.msgId == selectedMessage?.msgId } == messages.indexOfLast { it.role == Role.Assistant }
            
            MessageActionsBottomSheet(
                message = selectedMessage!!,
                ttsIsPlaying = ttsIsPlaying && ttsPlayingMsgId == selectedMessage?.msgId,
                ttsSynthesizing = ttsSynthesizing && ttsPlayingMsgId == selectedMessage?.msgId,
                ttsModelLoaded = ttsModelLoaded,
                isRegenerateEnabled = !chatState.isGenerating && isLastAssistant,
                onSpeak = { chatViewModel.speakMessage(it) },
                onStopTTS = { chatViewModel.stopTTS() },
                onRegenerate = { chatViewModel.regenerateLastMessage() },
                onPin = { chatViewModel.pinMessageToVault(it) },
                onDelete = { chatViewModel.deleteMessage(it) },
                onDismiss = { 
                    showActionsSheet = false
                    selectedMessage = null
                }
            )
        }

        AnimatedVisibility(
            visible = config.showDynamicWindow,
            enter = fadeIn(Motion.entrance()),
            exit = fadeOut(Motion.exit())
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            chatViewModel.hideDynamicWindow()
                        }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingLg),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val ragCount by com.dark.tool_neuron.plugins.PluginManager.enabledPluginNames.collectAsStateWithLifecycle()
                    val ttsLoaded by com.dark.tool_neuron.tts.TTSManager.isModelLoaded.collectAsStateWithLifecycle()

                    DynamicActionWindow(
                        chatViewModel = chatViewModel,
                        modelViewModel = llmModelViewModel,
                        enabledToolCount = ragCount.size,
                        ttsModelLoaded = ttsLoaded
                    )
                }
            }
        }
    }
}

@Composable
fun DateHeader(date: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = Standards.SpacingMd),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color.LightGray.copy(alpha = 0.2f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Standards.SpacingSm, top = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp
        ) {
            Text(
                text = "typing...",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

fun formatDateHeader(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val now = Calendar.getInstance()
    cal.timeInMillis = timestamp

    return when {
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "Today"

        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 -> "Yesterday"

        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
