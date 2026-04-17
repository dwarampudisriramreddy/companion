package com.dark.tool_neuron.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dark.tool_neuron.models.engine_schema.GggufEngineSchema
import com.dark.tool_neuron.models.enums.ProviderType
import com.dark.tool_neuron.models.table_schema.Model
import com.dark.tool_neuron.models.table_schema.ModelConfig
import com.dark.tool_neuron.repository.ModelRepository
import com.dark.tool_neuron.service.LLMService
import com.dark.tool_neuron.state.AppStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.lifecycle.viewModelScope // Ensure this import is present
import kotlinx.coroutines.launch // Ensure this import is present
import kotlinx.coroutines.delay // Ensure this import is present


class ModelConfigEditorViewModel(
    private val repository: ModelRepository,
    private val llmService: LLMService,
    private val ggufService: GggufEngineSchema.GGUFService // Assuming this is the correct type
) : ViewModel() {

    private val _selectedModel = MutableStateFlow<Model?>(null)
    val selectedModel: StateFlow<Model?> = _selectedModel.asStateFlow()

    private val _ggufConfig = MutableStateFlow(GggufEngineSchema())
    val ggufConfig: StateFlow<GggufEngineSchema> = _ggufConfig.asStateFlow()

    private val _diffusionConfig = MutableStateFlow(com.dark.tool_neuron.worker.DiffusionConfig())
    val diffusionConfig: StateFlow<com.dark.tool_neuron.worker.DiffusionConfig> = _diffusionConfig.asStateFlow()

    private val _ttsConfig = MutableStateFlow(com.dark.tool_neuron.worker.TtsConfig())
    val ttsConfig: StateFlow<com.dark.tool_neuron.worker.TtsConfig> = _ttsConfig.asStateFlow()


    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    init {
        loadConfiguration()
    }

    private fun loadConfiguration() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val models = repository.getAllModels()
                if (models.isNotEmpty()) {
                    _selectedModel.value = models.first()
                    loadConfigForModel(models.first())
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadConfigForModel(model: Model) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val config = repository.getConfigByModelId(model.id)

                when (model.providerType) {
                    ProviderType.GGUF -> {
                        _ggufConfig.value = if (config != null) {
                            GggufEngineSchema.fromJson(config.modelLoadingParams, config.modelInferenceParams)
                        } else {
                            GggufEngineSchema()
                        }
                    }
                    ProviderType.DIFFUSION -> {
                        _diffusionConfig.value = if (config != null) {
                            com.dark.tool_neuron.worker.DiffusionConfig.fromJson(config.modelLoadingParams)
                        } else {
                            com.dark.tool_neuron.worker.DiffusionConfig()
                        }
                    }
                    ProviderType.TTS -> {
                        _ttsConfig.value = if (config != null) {
                            com.dark.tool_neuron.worker.TtsConfig.fromJson(config.modelLoadingParams)
                        } else {
                            com.dark.tool_neuron.worker.TtsConfig()
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectModel(model: Model) {
        _selectedModel.value = model
        loadConfigForModel(model)
    }

    fun updateGGUFLoadingParams(params: GggufEngineSchema.GGUFLoadingParams) {
        _ggufConfig.update { it.copy(loadingParams = params) }
    }

    fun updateGGUFInferenceParams(params: GggufEngineSchema.GGUFInferenceParams) {
        _ggufConfig.update { it.copy(inferenceParams = params) }
    }

    fun updateDiffusionParams(params: com.dark.tool_neuron.worker.DiffusionConfig) {
        _diffusionConfig.value = params
    }

    fun updateTtsParams(params: com.dark.tool_neuron.worker.TtsConfig) {
        _ttsConfig.value = params
    }


    fun saveConfiguration() {
        val model = _selectedModel.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val existingConfig = repository.getConfigByModelId(model.id)

                val config = when (model.providerType) {
                    ProviderType.GGUF -> {
                        ModelConfig(
                            modelId = model.id,
                            modelLoadingParams = _ggufConfig.value.toLoadingJson(),
                            modelInferenceParams = _ggufConfig.value.toInferenceJson()
                        )
                    }
                    ProviderType.DIFFUSION -> {
                        ModelConfig(
                            modelId = model.id,
                            modelLoadingParams = _diffusionConfig.value.toJson(),
                            modelInferenceParams = null
                        )
                    }
                    ProviderType.TTS -> {
                        ModelConfig(
                            modelId = model.id,
                            modelLoadingParams = _ttsConfig.value.toJson(),
                            modelInferenceParams = null
                        )
                    }
                    else -> {
                        // Should not happen
                        throw IOException("Unknown provider type: ${model.providerType}")
                    }
                }

                if (existingConfig != null) {
                    repository.updateConfig(config)
                } else {
                    repository.insertConfig(config)
                }

                _saveSuccess.value = true
                delay(2000)
                _saveSuccess.value = false
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }
}
