package com.dark.tool_neuron.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dark.tool_neuron.global.Standards
import com.dark.tool_neuron.ui.icons.TnIcons

@Composable
fun TaskModeOverlay(
    tasks: List<String>,
    onTaskSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingLg),
            shape = RoundedCornerShape(Standards.RadiusXl),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(Standards.SpacingLg),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Text(
                    text = "Guided Task Mode",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Select a task to explore and grow together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(Standards.SpacingMd))
                
                tasks.forEach { task ->
                    OutlinedButton(
                        onClick = { onTaskSelected(task) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.RadiusMd)
                    ) {
                        Text(task)
                    }
                }
            }
        }
    }
}
