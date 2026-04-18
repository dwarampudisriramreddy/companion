package com.dark.tool_neuron.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dark.tool_neuron.models.engine_schema.GgufEngineSchema
import com.dark.tool_neuron.models.engine_schema.GgufInferenceParams
import com.dark.tool_neuron.models.engine_schema.GgufLoadingParams
import com.dark.tool_neuron.models.enums.ProviderType
import com.dark.tool_neuron.models.table_schema.Model
import com.dark.tool_neuron.models.table_schema.ModelConfig
import com.dark.tool_neuron.repo.ModelRepository
import com.dark.tool_neuron.worker.DiffusionConfig
import com.dark.tool_neuron.worker.DiffusionInferenceParams
import com.dark.tool_neuron.worker.TtsConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class ModelConfigEditorViewModel @Inject constructor(
    private val repository: ModelRepository
) : ViewModel() {

    val models: StateFlow<List<Model>> = repository.getAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedModel = MutableStateFlow<Model?>(null)
    val selectedModel: StateFlow<Model?> = _selectedModel.asStateFlow()

    private val _ggufConfig = MutableStateFlow(GgufEngineSchema())
    val ggufConfig: StateFlow<GgufEngineSchema> = _ggufConfig.asStateFlow()

    private val _diffusionConfig = MutableStateFlow(DiffusionConfig())
    val diffusionConfig: StateFlow<DiffusionConfig> = _diffusionConfig.asStateFlow()

    private val _diffusionInferenceParams = MutableStateFlow(DiffusionInferenceParams())
    val diffusionInferenceParams: StateFlow<DiffusionInferenceParams> = _diffusionInferenceParams.asStateFlow()

    private val _ttsConfig = MutableStateFlow(TtsConfig())
    val ttsConfig: StateFlow<TtsConfig> = _ttsConfig.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    fun selectModel(model: Model) {
        _selectedModel.value = model
        loadConfigForModel(model)
    }

    private fun loadConfigForModel(model: Model) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val config = repository.getConfigByModelId(model.id)

                when (model.providerType) {
                    ProviderType.GGUF -> {
                        _ggufConfig.value = if (config != null) {
                            GgufEngineSchema.fromJson(config.modelLoadingParams, config.modelInferenceParams)
                        } else {
                            GgufEngineSchema()
                        }
                    }
                    ProviderType.DIFFUSION -> {
                        _diffusionConfig.value = if (config != null) {
                            DiffusionConfig.fromJson(config.modelLoadingParams)
                        } else {
                            DiffusionConfig()
                        }
                        _diffusionInferenceParams.value = if (config != null) {
                            DiffusionInferenceParams.fromJson(config.modelInferenceParams)
                        } else {
                            DiffusionInferenceParams()
                        }
                    }
                    ProviderType.TTS -> {
                        _ttsConfig.value = if (config != null) {
                            TtsConfig.fromJson(config.modelLoadingParams)
                        } else {
                            TtsConfig()
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    // GGUF Updaters
    fun updateGgufThreads(threads: Int) {
        _ggufConfig.update { it.copy(loadingParams = it.loadingParams.copy(threads = threads)) }
    }

    fun updateGgufContextSize(ctxSize: Int) {
        _ggufConfig.update { it.copy(loadingParams = it.loadingParams.copy(ctxSize = ctxSize)) }
    }

    fun updateGgufUseMmap(useMmap: Boolean) {
        _ggufConfig.update { it.copy(loadingParams = it.loadingParams.copy(useMmap = useMmap)) }
    }

    fun updateGgufUseMlock(useMlock: Boolean) {
        _ggufConfig.update { it.copy(loadingParams = it.loadingParams.copy(useMlock = useMlock)) }
    }

    fun updateGgufTemperature(temp: Float) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(temperature = temp)) }
    }

    fun updateGgufTopK(topK: Int) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(topK = topK)) }
    }

    fun updateGgufTopP(topP: Float) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(topP = topP)) }
    }

    fun updateGgufMinP(minP: Float) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(minP = minP)) }
    }

    fun updateGgufMaxTokens(maxTokens: Int) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(maxTokens = maxTokens)) }
    }

    fun updateGgufMirostat(mirostat: Int) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(mirostat = mirostat)) }
    }

    fun updateGgufMirostatTau(tau: Float) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(mirostatTau = tau)) }
    }

    fun updateGgufMirostatEta(eta: Float) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(mirostatEta = eta)) }
    }

    fun updateGgufSystemPrompt(prompt: String) {
        _ggufConfig.update { it.copy(inferenceParams = it.inferenceParams.copy(systemPrompt = prompt)) }
    }

    // Diffusion Updaters
    fun updateDiffusionEmbeddingSize(size: Int) {
        _diffusionConfig.update { it.copy(textEmbeddingSize = size) }
    }

    fun updateDiffusionRunOnCpu(enabled: Boolean) {
        _diffusionConfig.update { it.copy(runOnCpu = enabled) }
    }

    fun updateDiffusionUseCpuClip(enabled: Boolean) {
        _diffusionConfig.update { it.copy(useCpuClip = enabled) }
    }

    fun updateDiffusionIsPony(enabled: Boolean) {
        _diffusionConfig.update { it.copy(isPony = enabled) }
    }

    fun updateDiffusionSafetyMode(enabled: Boolean) {
        _diffusionConfig.update { it.copy(safetyMode = enabled) }
    }

    fun updateDiffusionNegativePrompt(prompt: String) {
        _diffusionInferenceParams.update { it.copy(negativePrompt = prompt) }
    }

    fun updateDiffusionSteps(steps: Int) {
        _diffusionInferenceParams.update { it.copy(steps = steps) }
    }

    fun updateDiffusionCfgScale(scale: Float) {
        _diffusionInferenceParams.update { it.copy(cfgScale = scale) }
    }

    fun updateDiffusionDenoiseStrength(strength: Float) {
        _diffusionInferenceParams.update { it.copy(denoiseStrength = strength) }
    }

    fun updateDiffusionScheduler(scheduler: String) {
        _diffusionInferenceParams.update { it.copy(scheduler = scheduler) }
    }

    fun updateDiffusionUseOpenCL(enabled: Boolean) {
        _diffusionInferenceParams.update { it.copy(useOpenCL = enabled) }
    }

    fun updateDiffusionShowProcess(enabled: Boolean) {
        _diffusionInferenceParams.update { it.copy(showDiffusionProcess = enabled) }
    }

    fun updateDiffusionShowStride(stride: Int) {
        _diffusionInferenceParams.update { it.copy(showDiffusionStride = stride) }
    }

    // TTS Updaters
    fun updateTtsUseNNAPI(enabled: Boolean) {
        _ttsConfig.update { it.copy(useNNAPI = enabled) }
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
                            modelInferenceParams = _diffusionInferenceParams.value.toJson()
                        )
                    }
                    ProviderType.TTS -> {
                        ModelConfig(
                            modelId = model.id,
                            modelLoadingParams = _ttsConfig.value.toJson(),
                            modelInferenceParams = null
                        )
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
