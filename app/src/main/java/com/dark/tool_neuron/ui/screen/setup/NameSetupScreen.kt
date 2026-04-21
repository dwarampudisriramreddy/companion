package com.dark.tool_neuron.ui.screen.setup

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
import com.dark.tool_neuron.global.Standards
import com.dark.tool_neuron.ui.icons.TnIcons
import com.dark.tool_neuron.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSetupScreen(
    onComplete: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    var userName by remember { mutableStateOf("") }
    var companionName by remember { mutableStateOf("Companion") }
    var selectedPersonality by remember { mutableStateOf("INFJ") }

    val mbtiTypes = listOf(
        "INTJ", "INTP", "ENTJ", "ENTP",
        "INFJ", "INFP", "ENFJ", "ENFP",
        "ISTJ", "ISFJ", "ESTJ", "ESFJ",
        "ISTP", "ISFP", "ESTP", "ESFP"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(Standards.SpacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            Spacer(modifier = Modifier.height(Standards.SpacingXxl))

            Icon(
                imageVector = TnIcons.User,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Personalize",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Set up your identity and your AI's personality.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Standards.SpacingMd))

            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Your Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd),
                singleLine = true
            )

            OutlinedTextField(
                value = companionName,
                onValueChange = { companionName = it },
                label = { Text("Companion Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd),
                singleLine = true
            )

            Text(
                text = "Select AI Personality (MBTI)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mbtiTypes.forEach { type ->
                    androidx.compose.material3.FilterChip(
                        selected = selectedPersonality == type,
                        onClick = { selectedPersonality = type },
                        label = { Text(type) },
                        shape = RoundedCornerShape(Standards.RadiusMd)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (userName.isNotBlank() && companionName.isNotBlank()) {
                        settingsViewModel.setUserName(userName)
                        settingsViewModel.setCompanionName(companionName)
                        settingsViewModel.setPersonalityType(selectedPersonality)
                        onComplete()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = userName.isNotBlank() && companionName.isNotBlank(),
                shape = RoundedCornerShape(Standards.RadiusMd)
            ) {
                Text("Get Started")
            }
        }
    }
}
