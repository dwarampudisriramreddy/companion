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
    var companionName by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(Standards.SpacingXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = TnIcons.User,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(Standards.SpacingLg))

            Text(
                text = "Welcome",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Let's personalize your experience.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Standards.SpacingXxl))

            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Your Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(Standards.SpacingMd))

            OutlinedTextField(
                value = companionName,
                onValueChange = { companionName = it },
                label = { Text("Companion Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(Standards.SpacingXxl))

            Button(
                onClick = {
                    if (userName.isNotBlank() && companionName.isNotBlank()) {
                        settingsViewModel.setUserName(userName)
                        settingsViewModel.setCompanionName(companionName)
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
