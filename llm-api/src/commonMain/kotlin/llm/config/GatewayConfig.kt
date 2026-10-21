package llm.config

import kotlinx.serialization.Serializable

/**
 * Configuración mínima que necesita el gateway HTTP.
 *
 * Es el puente entre el modelo de dominio ([kitoru.domain.ModelPolicy], que
 * además trae la parte de modelo local) y la capa de red: el gateway sólo
 * necesita saber a quién le pregunta, con qué clave y con qué parámetros de
 * generación.
 */
@Serializable
data class GatewayConfig(
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int = 1024,
    /** Timeout por respuesta en ms (0 = sin límite). */
    val chatTimeoutMs: Long = 0L,
    /** Nivel de razonamiento pedido al modelo ("off" | "low" | "medium" | "high"). */
    val thinking: String = "off",
    /** Potencia de respuesta ("low" | "medium" | "high"). */
    val power: String = "medium",
)

/**
 * Parámetros del modelo local (llama.cpp corriendo en el dispositivo).
 *
 * Los carga el [llm.api.LocalModelService]; en Android no hay implementación
 * (el modelo local del alumno es un hito posterior) y la interfaz devuelve un
 * error claro.
 */
@Serializable
data class LocalModelSettings(
    /** Repo de Hugging Face del GGUF. */
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    /** Archivo GGUF dentro del repo. */
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    /** Ruta del binario `llama-server` (vacío = autodetectar en el PATH). */
    val llamaServerPath: String = "",
    /** Puerto local donde se levanta el servidor. */
    val localPort: Int = 8081,
    /** Tamaño de contexto. */
    val contextSize: Int = 8192,
    /** Capas en GPU (0 = sólo CPU). */
    val gpuLayers: Int = 0,
    /** Carpeta donde vive el modelo ya descargado. */
    val modelsDir: String = "models",
)
