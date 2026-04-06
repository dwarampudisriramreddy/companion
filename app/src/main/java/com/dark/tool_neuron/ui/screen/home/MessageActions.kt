package com.dark.tool_neuron.ui.screen.home

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.dark.tool_neuron.models.messages.ContentType
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.ui.components.AgentExecutionView
import com.dark.tool_neuron.ui.components.PluginResultCard
import com.dark.tool_neuron.ui.components.ToolChainDisplay
import com.dark.tool_neuron.ui.icons.TnIcons
import com.dark.tool_neuron.viewmodel.AgentPhase
import kotlinx.coroutines.launch
import com.dark.tool_neuron.global.Standards

// ── AssistantMessageHeader ──

/** Header part of assistant message: RAG results, tool chain, thinking block, non-text content. */
@Composable
internal fun AssistantMessageHeader(message: Messages, imageBlurEnabled: Boolean = true) {
    val hasRagResults = remember(message.ragResults) {
        message.ragResults?.isNotEmpty() == true
    }
    val hasToolChainSteps = remember(message.toolChainSteps) {
        message.toolChainSteps?.isNotEmpty() == true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasRagResults) {
            message.ragResults?.let { results ->
                SavedRagResultsDisplay(results = results)
            }
        }

        if (message.agentPlan != null) {
            AgentExecutionView(
                plan = message.agentPlan,
                steps = message.toolChainSteps ?: emptyList(),
                summary = message.agentSummary,
                phase = AgentPhase.Complete
            )
        } else if (hasToolChainSteps) {
            ToolChainDisplay(steps = message.toolChainSteps!!)
        }

        // Thinking Block (if not in Plan/Summary format)
        if (message.agentPlan == null && message.content.contentType == ContentType.Text) {
            val thinkingContent = remember(message.content.content) {
                val match = THINK_TAG_REGEX.find(message.content.content)
                match?.groupValues?.find { it.isNotEmpty() }
            }
            thinkingContent?.let {
                ThinkingBlock(thinkingText = it, isStreaming = false)
            }
        }

        // Image content
        if (message.content.contentType == ContentType.Image || message.content.contentType == ContentType.TextWithImage) {
            ImageMessageBubble(message, imageBlurEnabled)
        }

        // Plugin Results
        if (message.content.contentType == ContentType.PluginResult) {
            PluginResultCard(message = message)
        }
    }
}

// ── AssistantMessageFooter ──

/** Footer part of assistant message: only show action words. */
@Composable
internal fun AssistantMessageFooter(message: Messages) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Action words below the message as requested
        ActionWordsDisplay(message = message)
    }
}

// ── MessageActionsBottomSheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsBottomSheet(
    message: Messages,
    ttsIsPlaying: Boolean,
    ttsSynthesizing: Boolean,
    ttsModelLoaded: Boolean,
    isRegenerateEnabled: Boolean,
    onSpeak: (Messages) -> Unit,
    onStopTTS: () -> Unit,
    onRegenerate: () -> Unit,
    onPin: (Messages) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isTextContent = message.content.contentType == ContentType.Text
    val isAssistant = message.role == Role.Assistant

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Standards.SpacingXl)
        ) {
            Text(
                text = "Message Actions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(Standards.SpacingLg)
            )

            if (isTextContent && message.content.content.isNotEmpty()) {
                val textContent = remember(message.content.content) {
                    if (THINK_TAG_REGEX.containsMatchIn(message.content.content)) {
                        message.content.content.replace(THINK_TAG_REGEX, "").trim()
                    } else message.content.content
                }

                // Pin Action
                ListItem(
                    headlineContent = { Text("Pin to Vault") },
                    leadingContent = { Icon(TnIcons.Vault, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onPin(message)
                        onDismiss()
                    }
                )

                // Copy Action
                ListItem(
                    headlineContent = { Text("Copy Text") },
                    leadingContent = { Icon(TnIcons.Copy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", textContent)))
                        }
                        onDismiss()
                    }
                )

                if (isAssistant) {
                    // Speak/Stop TTS Action
                    val ttsLabel = when {
                        ttsIsPlaying -> "Stop Speaking"
                        ttsSynthesizing -> "Synthesizing..."
                        else -> "Speak Message"
                    }
                    val ttsIcon = if (ttsIsPlaying) TnIcons.PlayerStop else TnIcons.Volume

                    ListItem(
                        headlineContent = { Text(ttsLabel) },
                        leadingContent = { 
                            if (ttsSynthesizing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(ttsIcon, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable(enabled = ttsModelLoaded) {
                            if (ttsIsPlaying || ttsSynthesizing) onStopTTS() else onSpeak(message)
                            onDismiss()
                        }
                    )

                    // Regenerate Action
                    ListItem(
                        headlineContent = { Text("Regenerate") },
                        leadingContent = { Icon(TnIcons.Refresh, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = isRegenerateEnabled) {
                            onRegenerate()
                            onDismiss()
                        }
                    )
                }
            }

            // Delete Action (Common for both)
            ListItem(
                headlineContent = { Text("Delete Message", color = MaterialTheme.colorScheme.error) },
                leadingContent = { 
                    Icon(
                        TnIcons.Trash, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.error 
                    ) 
                },
                modifier = Modifier.clickable {
                    onDelete(message.msgId)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
internal fun ActionWordsDisplay(message: Messages) {
    // Placeholder for metrics display or action-related text if needed
}

@Composable
internal fun SavedRagResultsDisplay(results: List<com.dark.tool_neuron.models.messages.RagResultItem>) {
    // Implementation for displaying RAG results
}
