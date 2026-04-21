package com.dark.tool_neuron.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dark.tool_neuron.global.Standards
import com.dark.tool_neuron.ui.icons.TnIcons
import com.dark.tool_neuron.viewmodel.ChatListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDrawerScreen(
    onChatSelected: (String) -> Unit,
    onVaultManagerClick: () -> Unit,
    chatViewModel: com.dark.tool_neuron.viewmodel.ChatViewModel,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Memory Vault", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(Standards.SpacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingLg)
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(40.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = onVaultManagerClick
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TnIcons.Vault,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "Manage Memories",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Search through your long-term memories and chat history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Standards.SpacingXl))

            Button(
                onClick = onVaultManagerClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd)
            ) {
                Icon(TnIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Standards.SpacingSm))
                Text("Open Vault Manager")
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = { chatViewModel.startNewConversation() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(Standards.RadiusMd)
            ) {
                Icon(TnIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Standards.SpacingSm))
                Text("Clear Conversation")
            }
        }
    }
}
