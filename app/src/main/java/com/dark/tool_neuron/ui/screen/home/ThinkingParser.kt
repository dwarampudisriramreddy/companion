package com.dark.tool_neuron.ui.screen.home

/**
 * Data class representing a parsed message with thinking tags separated from content.
 */
data class ParsedMessage(
    val thinkingContent: String? = null,
    val actualContent: String,
    val isThinkingInProgress: Boolean = false
)

/**
 * Parses a message string for <think>...</think> tags.
 * Handles cases where tags are incomplete (during streaming).
 */
fun parseThinkingTags(text: String): ParsedMessage {
    val thinkStart = "<think>"
    val thinkEnd = "</think>"
    
    val startIndex = text.indexOf(thinkStart)
    if (startIndex == -1) {
        return ParsedMessage(actualContent = text)
    }
    
    val contentAfterStart = text.substring(startIndex + thinkStart.length)
    val endIndex = contentAfterStart.indexOf(thinkEnd)
    
    if (endIndex == -1) {
        // Thinking in progress
        val thinking = contentAfterStart.trim()
        val beforeThink = text.substring(0, startIndex).trim()
        return ParsedMessage(
            thinkingContent = thinking,
            actualContent = beforeThink,
            isThinkingInProgress = true
        )
    }
    
    // Thinking complete
    val thinking = contentAfterStart.substring(0, endIndex).trim()
    val afterThink = contentAfterStart.substring(endIndex + thinkEnd.length).trim()
    val beforeThink = text.substring(0, startIndex).trim()
    
    val combinedContent = if (beforeThink.isEmpty()) afterThink else "$beforeThink\n\n$afterThink"
    
    return ParsedMessage(
        thinkingContent = thinking.ifEmpty { null },
        actualContent = combinedContent.trim()
    )
}
