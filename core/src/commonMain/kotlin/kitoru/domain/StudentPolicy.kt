package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Los campos de la configuración del modelo que el alumno puede ajustar.
 *
 * Es el **vocabulario de los deltas**: cada entrada es una cosa que el
 * alumno puede cambiar por su cuenta (y que la cátedra puede acotar).
 *
 * - [LockedField] es el vocabulario del lado de la cátedra (lo que ya existe
 *   en [ModelPolicy]); [PolicyField] es el del lado del alumno, más fino:
 *   incluye el modelo local (repo, archivo, puerto, contexto, GPU) y separa
 *   la URL del servidor del resto.
 *
 * La correspondencia entre los dos está en [fromLocked].
 */
@Serializable
enum class PolicyField(val label: String) {
    /** De dónde corre el modelo: servidor de la cátedra o IA local. */
    MODE("Modo de ejecución"),

    /** URL base del endpoint (compatible con OpenAI). */
    REMOTE_BASE_URL("Dirección del servidor"),

    /** Clave con la que se autentica contra el servidor. */
    API_KEY("Clave de API"),

    /** Nombre del modelo en el servidor. */
    MODEL_NAME("Nombre del modelo"),

    /** Temperatura de muestreo. */
    TEMPERATURE("Temperatura"),

    /** Tope de tokens por respuesta. */
    MAX_TOKENS("Límite de tokens"),

    /** Cuántos fragmentos del corpus recupera el RAG. */
    TOP_K("Fragmentos recuperados"),

    /** Instrucciones del sistema del alumno (se **componen** con las de la cátedra). */
    SYSTEM_PROMPT("Instrucciones del sistema"),

    /** Repo de Hugging Face del GGUF local. */
    HF_REPO("Repo del modelo local"),

    /** Archivo GGUF local dentro del repo. */
    HF_FILE("Archivo del modelo local"),

    /** Puerto del `llama-server` local. */
    LOCAL_PORT("Puerto local"),

    /** Ventana de contexto del modelo local. */
    CONTEXT_SIZE("Tamaño del contexto"),

    /** Capas del modelo local que van a la GPU (0 = sólo CPU). */
    GPU_LAYERS("Capas en GPU"),
    ;

    /** ¿Es un secreto? (nunca se muestra en crudo, ni en el diff ni en los reportes). */
    val isSecret: Boolean get() = this == API_KEY

    /** ¿Es numérico? (sólo estos admiten un rango). */
    val isNumeric: Boolean get() = this in NUMERIC

    companion object {
        /** Los campos numéricos (los que la cátedra puede acotar con un rango). */
        val NUMERIC: Set<PolicyField> = setOf(
            TEMPERATURE, MAX_TOKENS, TOP_K, LOCAL_PORT, CONTEXT_SIZE, GPU_LAYERS,
        )

        /** Busca por nombre, tolerando mayúsculas (migración de JSON). */
        fun fromName(name: String): PolicyField? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        /**
         * Traduce el vocabulario de la cátedra ([LockedField]) al del alumno
         * ([PolicyField]).
         *
         * Los campos que la cátedra puede bloquear hoy son un subconjunto de
         * los que el alumno puede tocar: los del modelo local todavía no
         * tienen equivalente en [LockedField] (ver el TODO de
         * [ModelResolver.rulesFor]).
         */
        fun fromLocked(field: LockedField): PolicyField? = when (field) {
            LockedField.API_KEY -> API_KEY
            LockedField.BASE_URL -> REMOTE_BASE_URL
            LockedField.MODEL_NAME -> MODEL_NAME
            LockedField.SYSTEM_PROMPT -> SYSTEM_PROMPT
            LockedField.TEMPERATURE -> TEMPERATURE
            LockedField.MAX_TOKENS -> MAX_TOKENS
            LockedField.TOP_K -> TOP_K
        }
    }
}

/**
 * El modelo local **del alumno**: su GGUF, su puerto, su contexto.
 *
 * Vive en el perfil del alumno (`config/student-model-profiles.json`), nunca
 * en la política de la cátedra: el modelo que corre en la notebook del alumno
 * es un asunto suyo y no ensucia la política de la cátedra.
 *
 * La cátedra puede *acotar* algunos de estos valores (contexto máximo,
 * capas en GPU) con las reglas de [ModelResolver], pero no puede verlos ni
 * fijarlos: el alumno decide con qué modelo local trabaja.
 */
@Serializable
data class LocalModelProfile(
    /** Repo de Hugging Face del GGUF. */
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",

    /** Archivo GGUF dentro del repo. */
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",

    /** Carpeta donde se guarda el modelo descargado. */
    val modelsDir: String = "models",

    /** Ruta del binario `llama-server` (vacío = autodetectar en el PATH). */
    val llamaServerPath: String = "",

    /** Puerto del `llama-server` local. */
    val localPort: Int = 8081,

    /** Ventana de contexto. */
    val contextSize: Int = 8192,

    /** Capas en GPU (0 = sólo CPU). */
    val gpuLayers: Int = 0,
) {
    /** Etiqueta corta del modelo local ("Phi-4-mini-instruct"). */
    val label: String get() = hfFile.ifBlank { hfRepo }.substringBeforeLast('.')
}

/**
 * Lo que el alumno eligió en una cátedra: sus **deltas** sobre la base.
 *
 * Regla de oro (ver `docs/design/domain/05-modelo-config.md`):
 * **la cátedra guarda valores; el alumno guarda deltas.** El perfil nunca
 * copia la política: [deltas] tiene sólo lo que el alumno cambió.
 *
 * Por eso:
 *  - el diff es, literalmente, la lista de deltas;
 *  - cuando la cátedra cambia su base (por ejemplo sube `topK` de 5 a 8),
 *    todos los alumnos se ven afectados sin tocar sus perfiles;
 *  - el perfil es del alumno y no se manda a la cátedra.
 *
 * El valor de cada delta es el **string** que puso el alumno (el dominio no
 * guarda "0.5" como número para que el diff sea textual y el JSON legible);
 * [ModelResolver] lo interpreta según el tipo del campo.
 */
@Serializable
data class StudentModelProfile(
    /** Alumno dueño del perfil. */
    val userId: String = "",

    /** Cátedra a la que pertenece este perfil. */
    val courseId: String = "",

    /**
     * Lo que el alumno cambió, campo a campo.
     *
     * Si un campo **no** está acá, el alumno lo hereda de la cátedra.
     * Nunca se guardan valores de la política: sólo los ajustes del alumno.
     */
    val deltas: Map<PolicyField, String> = emptyMap(),

    /**
     * Referencia a la credencial personal del alumno (id en el almacén de
     * secretos del sistema).
     *
     * Se guarda la **referencia**, no la clave: la clave en sí vive en el
     * keychain del sistema y se resuelve al momento de chatear. Mientras no
     * haya almacén de secretos, el alumno puede dejar la clave en
     * [deltas] (`PolicyField.API_KEY`), que igual es de él y nunca sale de su
     * máquina.
     */
    val personalKeyRef: String = "",

    /** Su propio modelo local (GGUF, puerto, contexto…). */
    val localModel: LocalModelProfile? = null,
) {
    /** El delta del alumno para un campo (`null` = no lo tocó). */
    fun delta(field: PolicyField): String? = deltas[field]?.takeIf { it.isNotBlank() }

    /** ¿El alumno tocó algo? */
    val hasDeltas: Boolean get() = deltas.values.any { it.isNotBlank() }

    /** Copia con un delta agregado o quitado (si el valor es `null`, se quita). */
    fun withDelta(field: PolicyField, value: String?): StudentModelProfile =
        if (value == null) copy(deltas = deltas - field)
        else copy(deltas = deltas + (field to value))
}

