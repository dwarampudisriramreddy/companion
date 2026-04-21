package com.dark.tool_neuron.ui.screen.memory

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dark.tool_neuron.di.AppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.table_schema.AiMemory
import com.dark.tool_neuron.models.table_schema.MemoryCategory
import com.dark.tool_neuron.ui.components.ActionButton
import com.dark.tool_neuron.worker.MemoryExtractor
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dark.tool_neuron.global.ImageUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.dark.tool_neuron.ui.icons.TnIcons
import com.dark.tool_neuron.global.Standards

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiMemoryScreen(
    onNavigateBack: () -> Unit
) {
    val memoryRepo = remember { AppContainer.getMemoryRepo() }
    val memoryExtractor = remember {
        MemoryExtractor(memoryRepo)
    }
    val allMemories by memoryRepo.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    
    val chatRepo = remember { com.dark.tool_neuron.data.VaultManager.chatRepo }
    var reactedMessages by remember { mutableStateOf<List<Messages>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        reactedMessages = chatRepo?.getAllReactedMessages() ?: emptyList()
    }

    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var selectedReaction by remember { mutableStateOf<String?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearStaleDialog by remember { mutableStateOf(false) }

    val filteredMemories = remember(allMemories, searchQuery, selectedCategory, selectedReaction) {
        if (selectedReaction != null) emptyList() // Reactions are separate
        else allMemories.filter { memory ->
            val matchesSearch = searchQuery.isBlank() ||
                    memory.fact.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null ||
                    memory.category == selectedCategory
            matchesSearch && matchesCategory
        }
    }
    
    val filteredReactedMessages = remember(reactedMessages, searchQuery, selectedReaction) {
        if (selectedReaction == null && selectedCategory != null) emptyList()
        else reactedMessages.filter { msg ->
            val matchesSearch = searchQuery.isBlank() ||
                    msg.content.content.contains(searchQuery, ignoreCase = true)
            val matchesReaction = selectedReaction == null || msg.reaction == selectedReaction
            matchesSearch && matchesReaction
        }
    }

    val staleCount = remember(allMemories) {
        allMemories.count { memoryExtractor.isStale(it) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "AI Memory",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${allMemories.size + reactedMessages.size} items",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    ActionButton(
                        onClickListener = onNavigateBack,
                        icon = TnIcons.ArrowLeft,
                        contentDescription = "Back"
                    )
                },
                actions = {
                    if (staleCount > 0) {
                        IconButton(onClick = { showClearStaleDialog = true }) {
                            Icon(
                                TnIcons.TrashX,
                                contentDescription = "Clear Stale",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingXs),
                placeholder = { Text("Search memories and reactions...") },
                leadingIcon = { Icon(TnIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(TnIcons.X, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Standards.RadiusLg)
            )

            // Category filter chips
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingXs),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null && selectedReaction == null,
                    onClick = { 
                        selectedCategory = null
                        selectedReaction = null
                    },
                    label = { Text("All") }
                )
                
                MemoryCategory.entries.forEach { category ->
                    val count = allMemories.count { it.category == category }
                    if (count > 0) {
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                                selectedReaction = null
                            },
                            label = { Text("${categoryLabel(category)} ($count)") }
                        )
                    }
                }
                
                // Reaction filter chips
                val reactions = reactedMessages.mapNotNull { it.reaction }.distinct()
                reactions.forEach { emoji ->
                    val count = reactedMessages.count { it.reaction == emoji }
                    FilterChip(
                        selected = selectedReaction == emoji,
                        onClick = {
                            selectedReaction = if (selectedReaction == emoji) null else emoji
                            selectedCategory = null
                        },
                        label = { Text("$emoji ($count)") }
                    )
                }
            }

            if (filteredMemories.isEmpty() && filteredReactedMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Standards.SpacingXxl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (allMemories.isEmpty() && reactedMessages.isEmpty()) 
                            "No memories or reacted items yet.\nPin messages, react with emojis, or chat with the AI to populate your vault."
                        else "No items match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Standards.SpacingLg, vertical = Standards.SpacingSm),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Show reacted messages
                    items(filteredReactedMessages, key = { "msg-${it.msgId}" }) { msg ->
                        ReactedMessageItem(
                            message = msg,
                            onDelete = {
                                scope.launch {
                                    chatRepo?.deleteMessage(msg.msgId)
                                    reactedMessages = chatRepo?.getAllReactedMessages() ?: emptyList()
                                }
                            }
                        )
                    }

                    // Show AI memories
                    items(filteredMemories, key = { "mem-${it.id}" }) { memory ->
                        MemoryItem(
                            memory = memory,
                            isStale = memoryExtractor.isStale(memory),
                            strength = memoryExtractor.computeStrength(memory),
                            onDelete = {
                                scope.launch { memoryRepo.delete(memory) }
                            }
                        )
                    }

                    // Clear all button at bottom
                    if (allMemories.size > 3) {
                        item {
                            Spacer(modifier = Modifier.height(Standards.SpacingSm))
                            TextButton(
                                onClick = { showClearAllDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Clear All Memories",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(Standards.SpacingLg)) }
                }
            }
        }
    }

    // Clear all dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Memories?") },
            text = { Text("This will permanently delete all ${allMemories.size} memories. The AI will forget everything about you.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        memoryRepo.deleteAll()
                        showClearAllDialog = false
                    }
                }) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear stale dialog
    if (showClearStaleDialog) {
        AlertDialog(
            onDismissRequest = { showClearStaleDialog = false },
            title = { Text("Clear Stale Memories?") },
            text = { Text("This will remove $staleCount memories that haven't been accessed recently and have low strength scores.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        memoryExtractor.clearStaleMemories()
                        showClearStaleDialog = false
                    }
                }) {
                    Text("Clear Stale", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStaleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemoryItem(
    memory: AiMemory,
    isStale: Boolean,
    strength: Float,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isStale)
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (isStale) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd)
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Fact text
                Text(
                    text = memory.fact,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (isStale) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.width(Standards.SpacingSm))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        TnIcons.X,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            if (memory.imageData != null) {
                val bitmap = remember(memory.imageData) {
                    try {
                        val bytes = java.util.Base64.getDecoder().decode(memory.imageData)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Standards.SpacingSm)
                            .aspectRatio(1.5f)
                            .clip(RoundedCornerShape(Standards.RadiusMd)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.height(Standards.SpacingXs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Category chip
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                categoryLabel(memory.category),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )

                    // Stale indicator
                    if (isStale) {
                        Text(
                            text = "stale",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }

                // Metadata
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (memory.imageData != null) {
                        IconButton(
                            onClick = { ImageUtils.downloadImage(context, memory.imageData) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(TnIcons.Download, null, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { ImageUtils.shareImage(context, memory.imageData) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(TnIcons.Share, null, modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        text = "${(strength * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            strength >= 0.7f -> MaterialTheme.colorScheme.primary
                            strength >= 0.4f -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        }
                    )
                    Text(
                        text = dateFormat.format(Date(memory.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactedMessageItem(
    message: Messages,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Emoji indicator
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = message.reaction ?: "✨", fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.width(Standards.SpacingMd))

                // Message text
                Text(
                    text = message.content.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        TnIcons.X,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Standards.SpacingSm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (message.role == com.dark.tool_neuron.models.messages.Role.User) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = dateFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun categoryLabel(category: MemoryCategory): String {
    return when (category) {
        MemoryCategory.PERSONAL -> "Personal"
        MemoryCategory.PREFERENCE -> "Preference"
        MemoryCategory.WORK -> "Work"
        MemoryCategory.INTEREST -> "Interest"
        MemoryCategory.GENERAL -> "General"
        MemoryCategory.PINNED -> "Pinned"
        MemoryCategory.EVENT -> "Event"
        MemoryCategory.PEOPLE -> "People"
        MemoryCategory.PLACE -> "Place"
    }
}
