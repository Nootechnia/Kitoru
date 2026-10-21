package kitoru.domain

import kitoru.platform.SecretRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** De dónde corre el modelo que el alumno está usando. */
@Serializable
enum class ModelMode(val label: String) {
    /** Modelo alojado por la institución (Kitoru-Server) con API key. */
    REMOTE("Servidor de la cátedra"),

    /** Modelo corriendo en el propio dispositivo del alumno (llama.cpp). */
    LOCAL("IA local del alumno"),
}

/** Campos de la configuración del modelo que el docente puede bloquear. */
@Serializable
enum class LockedField(val label: String) {
    API_KEY("Clave de API"),
    BASE_URL("Dirección del servidor"),
    MODEL_NAME("Nombre del modelo"),
    SYSTEM_PROMPT("Instrucciones del sistema"),
    TEMPERATURE("Temperatura"),
    MAX_TOKENS("Límite de tokens"),
    TOP_K("Fragmentos recuperados"),
}

/**
 * Configuración del modelo que la cátedra impone como base.
 *
 * El alumno puede ajustarla libremente salvo en los campos que el docente
 * bloquee con [lockedFields]: la clave de API, por ejemplo, la provee la
 * institución y no debería ser tocable.
 *
 * Es el mismo concepto que la configuración de FunshiEngineAI (modo, endpoint,
 * modelo, temperatura, tokens, prompt), con el agregado del bloqueo por campo.
 *
 * **La clave de API no está en este objeto serializado.** El campo [apiKey]
 * es de sólo memoria (`@Transient`: nunca se escribe en un JSON) y el campo
 * que sí se persiste es [apiKeyRef], un alias que apunta al secreto guardado
 * en el almacén seguro de la plataforma (`kitoru.platform.SecureStore`).
 */
@Serializable
data class ModelPolicy(
    val mode: ModelMode = ModelMode.REMOTE,

    // ---- Servidor remoto (Kitoru-Server / API compatible OpenAI) ----
    val remoteBaseUrl: String = "",

    /**
     * La clave de API, **sólo en memoria**.
     *
     * Es `@Transient` a propósito: un secreto escrito en un archivo de
     * configuración es un secreto copiable, sincronizable con la nube y
     * respaldable. Esta clave se llena al *usar* el modelo
     * (`PolicySecrets.hydrate`) y se vacía al *guardar*
     * (`PolicySecrets.dehydrate`).
     */
    @Transient
    val apiKey: String = "",

    val modelName: String = "",

    // ---- Modelo local (llama.cpp en el dispositivo del alumno) ----
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    val modelsDir: String = "models",
    val llamaServerPath: String = "",
    val localPort: Int = 8081,
    val contextSize: Int = 8192,
    val gpuLayers: Int = 0,

    // ---- Generación ----
    val temperature: Double = 0.2,
    val maxTokens: Int = 1024,

    // ---- RAG ----
    val topK: Int = 5,

    // ---- Comportamiento del alumno ----
    /** Si el alumno puede tocar la configuración (o sólo usarla). */
    val allowStudentOverride: Boolean = true,
    /** Campos que el alumno no puede modificar. */
    val lockedFields: Set<LockedField> = setOf(LockedField.API_KEY),

    /** Herramientas habilitadas para el modelo. */
    val toolsEnabled: Boolean = true,

    /** Instrucciones del sistema propias de la cátedra. */
    val systemPrompt: String = "",

    /**
     * Alias de la clave de API en el almacén seguro.
     *
     * **No es la clave**: es un identificador determinista y no secreto
     * (`apikey:course:IA-2026-1`, `apikey:student:enroll_9f3`) que apunta al
     * secreto guardado en el `SecureStore` de la plataforma. Puede aparecer en
     * un log o en la interfaz sin filtrar nada.
     *
     * Vacío = esta política no tiene credencial propia (usa la de la cátedra o
     * la que emitió el servidor para este alumno).
     */
    val apiKeyRef: String = "",
) {
    /** Etiqueta corta del modelo activo, para la UI. */
    val modelLabel: String
        get() = when (mode) {
            ModelMode.REMOTE -> modelName.ifBlank { "servidor de la cátedra" }
            ModelMode.LOCAL -> hfFile.ifBlank { hfRepo }.substringBeforeLast('.')
        }

    /**
     * Aplica la configuración de la cátedra sobre lo que el alumno quiere
     * usar: lo que el alumno cambió se respeta salvo que el campo esté
     * bloqueado, en cuyo caso manda la cátedra.
     *
     * Trabaja sobre políticas **serializadas**: la credencial se resuelve
     * después, con `PolicySecrets.hydrate`. Si la cátedra bloquea la clave y
     * tiene una, se impone su alias (y se limpia la clave en memoria del
     * alumno: la credencial que vale es la de la cátedra).
     */
    fun resolveFor(studentChoice: ModelPolicy): ModelPolicy {
        if (!allowStudentOverride) return this
        var out = studentChoice
        if (LockedField.API_KEY in lockedFields && apiKeyRef.isNotBlank()) {
            out = out.copy(apiKeyRef = apiKeyRef, apiKey = "")
        }
        if (LockedField.BASE_URL in lockedFields) out = out.copy(remoteBaseUrl = remoteBaseUrl)
        if (LockedField.MODEL_NAME in lockedFields) out = out.copy(modelName = modelName)
        if (LockedField.SYSTEM_PROMPT in lockedFields) out = out.copy(systemPrompt = systemPrompt)
        if (LockedField.TEMPERATURE in lockedFields) out = out.copy(temperature = temperature)
        if (LockedField.MAX_TOKENS in lockedFields) out = out.copy(maxTokens = maxTokens)
        if (LockedField.TOP_K in lockedFields) out = out.copy(topK = topK)
        return out
    }

    /**
     * Copia sin la credencial.
     *
     * Es lo que se persiste cuando la política se guarda dentro de otra
     * cosa (por ejemplo, la configuración efectiva de un hilo): la
     * credencial de la cátedra no se escribe en el disco del alumno ni viaja
     * en una exportación.
     */
    fun redacted(): ModelPolicy = copy(apiKey = "", apiKeyRef = "")

    /** Problemas de la configuración (vacío = válida). En español, como en FunshiEngineAI. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (maxTokens <= 0) errors += "El límite de tokens debe ser mayor que 0 (actual: $maxTokens)."
        if (temperature < 0.0 || temperature > 2.0) {
            errors += "La temperatura debe estar entre 0 y 2 (actual: $temperature)."
        }
        if (topK <= 0) errors += "El número de fragmentos recuperados debe ser mayor que 0 (actual: $topK)."
        if (mode == ModelMode.REMOTE) {
            if (remoteBaseUrl.isBlank()) {
                errors += "Falta la dirección del servidor de la cátedra."
            } else if (!remoteBaseUrl.trim().startsWith("http://") &&
                !remoteBaseUrl.trim().startsWith("https://")
            ) {
                errors += "La dirección del servidor debe empezar con http:// o https://."
            } else if (modelName.isBlank()) {
                errors += "Falta el nombre del modelo en el servidor."
            }
        }
        return errors
    }

    /**
     * `toString()` **redactado**.
     *
     * El `toString()` autogenerado de un `data class` imprime todos los
     * campos: con la clave adentro, un `log` de diagnóstico o un reporte de
     * crash filtra la credencial. Este `toString()` es la barrera.
     */
    override fun toString(): String =
        "ModelPolicy(mode=$mode, remoteBaseUrl=$remoteBaseUrl, " +
            "apiKey=${if (apiKey.isBlank()) "\"\"" else SecretRedactor.MASK}, " +
            "apiKeyRef=$apiKeyRef, modelName=$modelName, hfRepo=$hfRepo, hfFile=$hfFile, " +
            "modelsDir=$modelsDir, llamaServerPath=$llamaServerPath, localPort=$localPort, " +
            "contextSize=$contextSize, gpuLayers=$gpuLayers, temperature=$temperature, " +
            "maxTokens=$maxTokens, topK=$topK, allowStudentOverride=$allowStudentOverride, " +
            "lockedFields=$lockedFields, toolsEnabled=$toolsEnabled, " +
            "systemPrompt=$systemPrompt)"
}
