package com.dark.tool_neuron.worker

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.dark.tool_neuron.engine.GGUFEngine
import com.dark.tool_neuron.engine.DiffusionEngine
import com.dark.tool_neuron.global.formatNumber
import com.dark.tool_neuron.models.enums.ProviderType
import com.dark.tool_neuron.models.table_schema.Model
import com.dark.tool_neuron.models.table_schema.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Handles parsing and loading of different model formats
 */
class ModelDataParser {

    suspend fun loadModel(
        model: Model, config: ModelConfig?
    ): ModelLoadResult = withContext(Dispatchers.IO) {
        return@withContext when (model.providerType) {
            ProviderType.GGUF -> loadGGUFModel(model, config)
            ProviderType.DIFFUSION -> loadDiffusionModel(model, config)
            else -> ModelLoadResult.Error("Unsupported model type: ${model.providerType}")
        }
    }

    /**
     * Load GGUF model from content:// URI using file descriptor
     */
    suspend fun loadModelFromUri(
        context: Context,
        uri: Uri,
        modelName: String,
        config: ModelConfig?
    ): ModelLoadResult = withContext(Dispatchers.IO) {
        try {
            val engine = GGUFEngine()

            // Get FD from content resolver
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext ModelLoadResult.Error("Cannot open file descriptor for URI")

            val fd = pfd.detachFd()  // Detach so engine owns the fd

            val success = engine.loadFromFd(fd, config ?: ModelConfig(modelId = modelName, modelLoadingParams = null, modelInferenceParams = null))

            if (success) {
                ModelLoadResult.Success(
                    info = GGUFModelInfo(
                        providerType = ProviderType.GGUF,
                        architecture = engine.getModelInfo() ?: "Unknown",
                        name = modelName,
                        description = "GGUF Model loaded from URI"
                    ),
                    engine = engine
                )
            } else {
                ModelLoadResult.Error("Failed to load model from FD")
            }
        } catch (e: Exception) {
            ModelLoadResult.Error("Error loading model from URI: ${e.message}")
        }
    }

    private suspend fun loadGGUFModel(model: Model, config: ModelConfig?): ModelLoadResult {
        val engine = GGUFEngine()
        val success = engine.load(model, config ?: ModelConfig(modelId = model.id, modelLoadingParams = null, modelInferenceParams = null))
        
        return if (success) {
            ModelLoadResult.Success(
                info = GGUFModelInfo(
                    providerType = ProviderType.GGUF,
                    architecture = engine.getModelInfo() ?: "Unknown",
                    name = model.modelName,
                    description = "GGUF Model"
                ),
                engine = engine
            )
        } else {
            ModelLoadResult.Error("Failed to load GGUF model")
        }
    }

    private suspend fun loadDiffusionModel(model: Model, config: ModelConfig?): ModelLoadResult {
        // Implementation for diffusion model loading
        return ModelLoadResult.Error("Diffusion loading not implemented in parser yet")
    }

    fun unloadModel(engine: Any) {
        when (engine) {
            is GGUFEngine -> engine.unload()
            // is DiffusionEngine -> engine.cleanup()
        }
    }

    /**
     * Get display name from content:// URI
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "Unknown Model"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { 
            it.statSize
        } ?: 0L
    }

    fun checksumSHA256FromUri(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            var totalRead = 0L
            // For large models, just hash the first 10MB for speed in loader activity
            while (bytesRead != -1 && totalRead < 10 * 1024 * 1024) {
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun checksumSHA256(path: String): String {
        val file = File(path)
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            var totalRead = 0L
            while (bytesRead != -1 && totalRead < 10 * 1024 * 1024) {
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Safely close a raw fd obtained via detachFd() */
    private fun closeFdSafely(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (_: Throwable) {}
    }

    private fun checksumDirectory(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        // Hash directory name
        digest.update(dir.name.toByteArray())

        // Hash key files
        val keyFiles = listOf(
            "unet.bin", "unet.mnn", "vae_decoder.bin", "vae_decoder.mnn", "tokenizer.json"
        )

        keyFiles.forEach { fileName ->
            val file = File(dir, fileName)
            if (file.exists()) {
                digest.update(fileName.toByteArray())
                digest.update(file.length().toString().toByteArray())
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Configuration for Diffusion models
 */
data class DiffusionConfig(
    val textEmbeddingSize: Int = 768,
    val runOnCpu: Boolean = false,
    val useCpuClip: Boolean = true,
    val isPony: Boolean = false,
    val httpPort: Int = 8081,
    val safetyMode: Boolean = false,
    val width: Int = 512,
    val height: Int = 512
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("text_embedding_size", textEmbeddingSize)
            put("run_on_cpu", runOnCpu)
            put("use_cpu_clip", useCpuClip)
            put("is_pony", isPony)
            put("http_port", httpPort)
            put("safety_mode", safetyMode)
            put("width", width)
            put("height", height)
        }.toString()
    }

    companion object {
        fun fromJson(jsonString: String?): DiffusionConfig {
            if (jsonString == null) return DiffusionConfig()
            return try {
                val json = JSONObject(jsonString)
                DiffusionConfig(
                    textEmbeddingSize = json.optInt("text_embedding_size", 768),
                    runOnCpu = json.optBoolean("run_on_cpu", false),
                    useCpuClip = json.optBoolean("use_cpu_clip", true),
                    isPony = json.optBoolean("is_pony", false),
                    httpPort = json.optInt("http_port", 8081),
                    safetyMode = json.optBoolean("safety_mode", false),
                    width = json.optInt("width", 512),
                    height = json.optInt("height", 512)
                )
            } catch (_: Exception) {
                DiffusionConfig()
            }
        }
    }
}

/**
 * Result of model loading operation
 */
sealed class ModelLoadResult {
    data class Success(
        val info: ModelInfo, val engine: Any // Can be GGUFEngine, "DiffusionEngine", etc.
    ) : ModelLoadResult()
    data class Error(val message: String) : ModelLoadResult()
}

/**
 * Common interface for model information
 */
interface ModelInfo {
    val providerType: ProviderType
    val architecture: String
    val name: String
    val description: String
    val parameters: Map<String, String>
    val additionalInfo: Map<String, String>?
}

/**
 * GGUF-specific model information
 */
data class GGUFModelInfo(
    override val providerType: ProviderType,
    override val architecture: String,
    override val name: String,
    override val description: String,
    override val parameters: Map<String, String> = emptyMap(),
    val vocabularyInfo: Map<String, String>? = null,
    val systemInfo: String = "",
    val chatTemplate: String = "",
    val templateType: String = ""
) : ModelInfo {
    override val additionalInfo: Map<String, String>?
        get() = vocabularyInfo
}

/**
 * Diffusion-specific model information
 */
data class DiffusionModelInfo(
    override val providerType: ProviderType,
    override val architecture: String,
    override val name: String,
    override val description: String,
    override val parameters: Map<String, String> = emptyMap(),
    val modelConfig: DiffusionConfig,
    val inferenceParams: DiffusionInferenceParams = DiffusionInferenceParams()
) : ModelInfo {
    override val additionalInfo: Map<String, String> = buildMap {
        put("Backend", if (modelConfig.runOnCpu) "CPU" else "NPU/GPU")
        put("CLIP", if (modelConfig.useCpuClip) "CPU (MNN)" else "NPU")
        put("Default Size", "${modelConfig.width}×${modelConfig.height}")

        if (modelConfig.safetyMode) {
            put("Safety Filter", "Enabled")
        }
    }
}


/**
 * Inference parameters for Diffusion models
 */
data class DiffusionInferenceParams(
    val negativePrompt: String = "",
    val steps: Int = 28,
    val cfgScale: Float = 7f,
    val scheduler: String = "dpm",
    val useOpenCL: Boolean = false,
    val denoiseStrength: Float = 0.6f,
    val showDiffusionProcess: Boolean = false,
    val showDiffusionStride: Int = 1
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("negative_prompt", negativePrompt)
            put("steps", steps)
            put("cfg_scale", cfgScale)
            put("scheduler", scheduler)
            put("use_opencl", useOpenCL)
            put("denoise_strength", denoiseStrength)
            put("show_diffusion_process", showDiffusionProcess)
            put("show_diffusion_stride", showDiffusionStride)
        }.toString()
    }

    companion object {
        fun fromJson(jsonString: String?): DiffusionInferenceParams {
            if (jsonString == null) return DiffusionInferenceParams()

            return try {
                val json = JSONObject(jsonString)
                DiffusionInferenceParams(
                    negativePrompt = json.optString("negative_prompt", ""),
                    steps = json.optInt("steps", 28),
                    cfgScale = json.optDouble("cfg_scale", 7.0).toFloat(),
                    scheduler = json.optString("scheduler", "dpm"),
                    useOpenCL = json.optBoolean("use_opencl", false),
                    denoiseStrength = json.optDouble("denoise_strength", 0.6).toFloat(),
                    showDiffusionProcess = json.optBoolean("show_diffusion_process", false),
                    showDiffusionStride = json.optInt("show_diffusion_stride", 1)
                )
            } catch (e: Exception) {
                DiffusionInferenceParams()
            }
        }
    }

    /**
     * Available schedulers for Stable Diffusion
     */
    enum class Scheduler(val value: String) {
        DPM("dpm"), EULER("euler"), EULER_A("euler_a"), DDIM("ddim"), PNDM("pndm");

        companion object {
            fun fromString(value: String): Scheduler =
                entries.find { it.value == value.lowercase() } ?: DPM
        }
    }
}

/**
 * Configuration for TTS models
 */
data class TtsConfig(
    val useNNAPI: Boolean = false
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("use_nnapi", useNNAPI)
        }.toString()
    }

    companion object {
        fun fromJson(jsonString: String?): TtsConfig {
            if (jsonString == null) return TtsConfig()
            return try {
                val json = JSONObject(jsonString)
                TtsConfig(
                    useNNAPI = json.optBoolean("use_nnapi", false)
                )
            } catch (_: Exception) {
                TtsConfig()
            }
        }
    }
}
