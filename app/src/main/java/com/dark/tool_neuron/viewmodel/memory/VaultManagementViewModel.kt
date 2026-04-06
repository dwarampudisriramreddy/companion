package com.dark.tool_neuron.viewmodel.memory

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dark.tool_neuron.data.AppSettingsDataStore
import com.dark.tool_neuron.data.VaultManager
import com.dark.tool_neuron.models.vault.ChatInfo
import com.dark.tool_neuron.models.vault.VaultStatistics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VaultManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val appSettings = AppSettingsDataStore(application)

    var vaultStats by mutableStateOf<VaultStatistics?>(null)
        private set

    var appOpenCount by mutableStateOf(0)
        private set

    var totalTimeSpentMs by mutableStateOf(0L)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var defragProgress by mutableStateOf(0f)
        private set

    var isDefragging by mutableStateOf(false)
        private set

    var chatList by mutableStateOf<List<ChatInfo>>(emptyList())
        private set

    private var activeLoads = 0

    private fun startLoading() {
        activeLoads++
        isLoading = true
    }

    private fun endLoading() {
        activeLoads--
        if (activeLoads <= 0) {
            activeLoads = 0
            isLoading = false
        }
    }

    fun loadVaultStats() {
        viewModelScope.launch {
            try {
                startLoading()
                val chatRepo = VaultManager.chatRepo ?: return@launch
                vaultStats = chatRepo.getVaultStats()

                // Load app usage metrics
                appOpenCount = appSettings.appOpenCount.first()
                totalTimeSpentMs = appSettings.totalTimeSpentMs.first()
            } catch (e: Exception) {
                Log.e("VaultManagementVM", "Failed to load vault stats", e)
            } finally {
                endLoading()
            }
        }
    }

    fun loadChatList() {
        viewModelScope.launch {
            try {
                startLoading()
                val chatRepo = VaultManager.chatRepo ?: return@launch
                chatList = chatRepo.getAllChats()
            } catch (e: Exception) {
                Log.e("VaultManagementVM", "Failed to load chats", e)
            } finally {
                endLoading()
            }
        }
    }

    fun performDefragmentation() {
        viewModelScope.launch {
            try {
                isDefragging = true
                defragProgress = 0f
                // UMS handles its own WAL compaction; no manual defrag needed
                defragProgress = 1f
                loadVaultStats()
            } catch (e: Exception) {
                Log.e("VaultManagementVM", "Defragmentation failed", e)
            } finally {
                isDefragging = false
                defragProgress = 0f
            }
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                isLoading = true
                val chatRepo = VaultManager.chatRepo ?: return@launch
                chatRepo.deleteChat(chatId)
                loadChatList()
            } catch (e: Exception) {
                Log.e("VaultManagementVM", "Failed to delete chat", e)
            } finally {
                isLoading = false
            }
        }
    }

}
