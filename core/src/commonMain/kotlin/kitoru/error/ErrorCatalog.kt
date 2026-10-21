package kitoru.error

import kotlinx.serialization.Serializable

/**
 * Los códigos de error de Kitoru.
 *
 * Es un **vocabulario cerrado**: el texto de cada código vive en
 * [ErrorCatalog] y en ningún otro lado. Si aparece una frase de error escrita
 * a mano en un `catch`, está mal: el texto se escribe acá y se llama a
 * [ErrorCatalog.of].
 *
 * El enum es serializable (el código viaja en logs, en el diagnóstico y en
 * los archivos de ajustes) y [fromName] es **tolerante**: acepta mayúsculas,
 * espacios y guiones, y nunca lanza; lo que no reconoce cae en [DESCONOCIDO].
 */
@Serializable
enum class ErrorCode {

    // ---- Red y servidor de la cátedra ----

    /** No hay red, o el servidor de la cátedra no responde. */
    SIN_CONEXION,

    /** El servidor de la cátedra respondió con un error del servidor. */
    SERVIDOR_NO_DISPONIBLE,

    /** La clave de acceso fue rechazada. */
    CLAVE_INVALIDA,

    /** La clave de la inscripción venció. */
    CLAVE_VENCIDA,

    /** Se alcanzó el límite de uso del servidor. */
    SIN_CUOTA,

    /** La operación tardó demasiado. */
    TIMEOUT,

    // ---- Modelo ----

    /** El modelo o la ruta no existen, o el modelo no está disponible. */
    MODELO_NO_DISPONIBLE,

    /** El modelo local (el que corre en el dispositivo) no responde. */
    SERVIDOR_CAIDO,

    /** El modelo local todavía no está en disco. */
    MODELO_NO_DESCARGADO,

    /** Falló la descarga del modelo local. */
    DESCARGA_FALLIDA,

    /** No hay ningún modelo configurado. */
    NO_CONFIGURADO,

    /** La ventana de contexto del modelo se agotó. */
    CONTEXTO_AGOTADO,

    // ---- Archivos y disco ----

    /** El archivo ya no está en el dispositivo. */
    ARCHIVO_NO_ENCONTRADO,

    /** El archivo supera el tamaño máximo permitido. */
    ARCHIVO_DEMASIADO_GRANDE,

    /** El tipo de archivo no se puede leer. */
    TIPO_NO_SOPORTADO,

    /** El material no tiene texto extraíble. */
    MATERIAL_SIN_TEXTO,

    /** Falló la extracción de texto de un archivo. */
    EXTRACCION_FALLIDA,

    /** Ya existe un material con ese nombre. */
    NOMBRE_DUPLICADO,

    /** No se pudo escribir en el disco. */
    ESCRITURA_FALLIDA,

    // ---- Permisos y base de datos ----

    /** No hay permiso sobre un archivo o una carpeta. */
    SIN_PERMISOS,

    /** No se pudo abrir la base de datos local. */
    BASE_NO_ACCESIBLE,

    // ---- Configuración ----

    /** La configuración del modelo no es válida. */
    CONFIG_INVALIDA,

    // ---- Cátedra ----

    /** El código de inscripción no coincide con ninguna cátedra. */
    CODIGO_INVALIDO,

    /** Se alcanzó la cuota de materiales de la cátedra. */
    CUOTA_MATERIALES,

    /** La visibilidad de la inscripción no deja mostrar ese dato. */
    VISIBILIDAD_OCULTA,

    /** La entrega del alumno no se pudo enviar. */
    ENTREGA_FALLIDA,

    // ---- Chat ----

    /** La herramienta que pidió el modelo falló. */
    HERRAMIENTA_FALLIDA,

    /** El modelo respondió vacío. */
    RESPUESTA_VACIA,

    // ---- Otros ----

    /** La operación se canceló: no es un error, es una acción del usuario. */
    CANCELADO,

    /** Error sin clasificar: nunca debería llegar a la UI. */
    DESCONOCIDO;

    /** Nombre estable para logs y archivos (el mismo que [name]). */
    val wireName: String get() = name

    companion object {

        /**
         * Traduce un nombre a código, **tolerante**.
         *
         * Ignora mayúsculas, espacios y guiones: "sin conexion", "SIN-CONEXION"
         * y "Sin_Conexion" son el mismo código. Lo que no reconoce devuelve
         * [fallback] (por defecto [DESCONOCIDO]) y nunca lanza.
         */
        fun fromName(raw: String?, fallback: ErrorCode = DESCONOCIDO): ErrorCode {
            val key = raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')
            if (key.isNullOrEmpty()) return fallback
            return entries.firstOrNull { it.name == key } ?: fallback
        }
    }
}

/**
 * Catálogo de errores: **la fuente única de los textos que ve el usuario**.
 *
 * Todos los mensajes salen de acá. Si un texto aparece en un `catch`, está
 * mal: se escribe acá y se llama a [of].
 *
 * Cada código tiene cuatro cosas:
 * - **mensaje**: qué pasó. Una frase, en español, sin jerga técnica.
 * - **pista**: qué se puede hacer. Otra frase.
 * - **severidad**: qué tan grave es (define el color y el tono del aviso).
 * - **acciones**: qué botones puede ofrecer la pantalla, en orden.
 *
 * Las reglas del texto (mayúscula inicial, punto final, nada de nombres de
 * clases de Java, nada de `null`) están verificadas por `ErrorCatalogTest`.
 */
object ErrorCatalog {

    /**
     * Construye el error de un código.
     *
     * @param detail la parte técnica (tipo de excepción, código HTTP, stack
     *   trace). Va al desplegable "Ver detalles", **nunca** al mensaje.
     */
    fun of(code: ErrorCode, detail: String? = null): KitoruError {
        val entry = entry(code)
        return KitoruError(
            code = code,
            message = entry.message,
            hint = entry.hint,
            severity = entry.severity,
            actions = entry.actions,
            detail = detail,
        )
    }

    /**
     * Traduce el **nombre** de un código de error del LLM al código de Kitoru.
     *
     * Por qué por nombre y no por tipo: `:core` no depende de `:llm-api`
     * (al revés: `:llm-api` depende de `:core`), así que acá no se puede
     * importar `llm.error.LlmErrorCode` sin crear una dependencia circular.
     * El mapeo se hace entonces sobre `code.name`, que es un contrato estable
     * entre los dos módulos.
     *
     * Lo que no se reconoce cae en [ErrorCode.DESCONOCIDO]: un código nuevo
     * del LLM degrada a "no se pudo completar la operación", nunca a una
     * excepción sin traducir.
     */
    fun fromLlmCodeName(name: String?): ErrorCode = when (name?.trim()?.uppercase()) {
        "SIN_CUOTA" -> ErrorCode.SIN_CUOTA
        "CLAVE_INVALIDA" -> ErrorCode.CLAVE_INVALIDA
        "TIMEOUT" -> ErrorCode.TIMEOUT
        "NO_CONFIGURADO" -> ErrorCode.NO_CONFIGURADO
        "MODELO_NO_DISPONIBLE" -> ErrorCode.MODELO_NO_DISPONIBLE
        "CONTEXTO_AGOTADO" -> ErrorCode.CONTEXTO_AGOTADO
        "DESCARGA_FALLIDA" -> ErrorCode.DESCARGA_FALLIDA
        "SERVIDOR_CAIDO" -> ErrorCode.SERVIDOR_CAIDO
        "CANCELADO" -> ErrorCode.CANCELADO
        "DESCONOCIDO" -> ErrorCode.DESCONOCIDO
        else -> ErrorCode.DESCONOCIDO
    }

    /** Los nombres de los códigos del LLM que Kitoru sabe traducir. */
    val llmCodeNames: Set<String> = setOf(
        "SIN_CUOTA",
        "CLAVE_INVALIDA",
        "TIMEOUT",
        "NO_CONFIGURADO",
        "MODELO_NO_DISPONIBLE",
        "CONTEXTO_AGOTADO",
        "DESCARGA_FALLIDA",
        "SERVIDOR_CAIDO",
        "CANCELADO",
        "DESCONOCIDO",
    )

    /** El texto de un código: mensaje, pista, severidad y acciones. */
    private data class Entry(
        val message: String,
        val hint: String,
        val severity: ErrorSeverity,
        val actions: List<ErrorAction>,
    )

    /**
     * El texto de cada código.
     *
     * Es un `when` exhaustivo a propósito: si se agrega un código nuevo, el
     * compilador obliga a escribir su texto acá.
     */
    private fun entry(code: ErrorCode): Entry = when (code) {

        // ---- Red y servidor de la cátedra ----

        ErrorCode.SIN_CONEXION -> Entry(
            message = "Estás sin conexión.",
            hint = "Revisá tu conexión a internet. Podés seguir usando lo que ya está en este dispositivo.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.SERVIDOR_NO_DISPONIBLE -> Entry(
            message = "El servidor de la cátedra no está respondiendo.",
            hint = "Podés ver tus cátedras, tus materiales y tus chats guardados mientras tanto.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.CLAVE_INVALIDA -> Entry(
            message = "El servidor de la cátedra rechazó la clave de acceso.",
            hint = "Si la clave venció, pedí una nueva a la cátedra. También podés seguir con la IA de tu dispositivo.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.SWITCH_TO_LOCAL_MODEL, ErrorAction.OPEN_COURSE_MODEL),
        )

        ErrorCode.CLAVE_VENCIDA -> Entry(
            message = "La clave de esta cátedra venció.",
            hint = "Pedí una clave nueva al docente de la cátedra.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.CONTACT_TEACHER, ErrorAction.OPEN_COURSE_MODEL),
        )

        ErrorCode.SIN_CUOTA -> Entry(
            message = "El servidor de la cátedra alcanzó su límite de uso.",
            hint = "Podés reintentar en unos minutos o seguir con la IA de tu dispositivo.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY, ErrorAction.SWITCH_TO_LOCAL_MODEL),
        )

        ErrorCode.TIMEOUT -> Entry(
            message = "El servidor tardó demasiado en responder.",
            hint = "Probá de nuevo. Si sigue igual, enviá una pregunta más corta.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        // ---- Modelo ----

        ErrorCode.MODELO_NO_DISPONIBLE -> Entry(
            message = "No se pudo obtener una respuesta del modelo.",
            hint = "El servidor puede estar caído o el modelo no estar disponible. Probá en un momento.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY, ErrorAction.SWITCH_TO_LOCAL_MODEL),
        )

        ErrorCode.SERVIDOR_CAIDO -> Entry(
            message = "No se pudo hablar con el modelo local.",
            hint = "Revisá que el servidor local esté corriendo y probá de nuevo.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.MODELO_NO_DESCARGADO -> Entry(
            message = "El modelo local todavía no está descargado.",
            hint = "Descargalo (ocupa alrededor de 1,2 GB) o seguí con la IA de la cátedra.",
            severity = ErrorSeverity.INFO,
            actions = listOf(ErrorAction.RETRY, ErrorAction.OPEN_COURSE_MODEL),
        )

        ErrorCode.DESCARGA_FALLIDA -> Entry(
            message = "No se pudo descargar el modelo local.",
            hint = "Revisá la conexión y el espacio libre en disco, y probá de nuevo.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.NO_CONFIGURADO -> Entry(
            message = "No hay ningún modelo configurado para esta cátedra.",
            hint = "Pedile al docente que configure el modelo en «Configuración del modelo».",
            severity = ErrorSeverity.INFO,
            actions = listOf(ErrorAction.OPEN_COURSE_MODEL, ErrorAction.CONTACT_TEACHER),
        )

        ErrorCode.CONTEXTO_AGOTADO -> Entry(
            message = "La respuesta no entra en la ventana de contexto del modelo.",
            hint = "Probá con una pregunta más corta o con menos fragmentos del material.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        // ---- Archivos y disco ----

        ErrorCode.ARCHIVO_NO_ENCONTRADO -> Entry(
            message = "El archivo ya no está en este dispositivo.",
            hint = "Puede que se haya movido o borrado.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.DISMISS),
        )

        ErrorCode.ARCHIVO_DEMASIADO_GRANDE -> Entry(
            message = "El archivo supera el tamaño máximo permitido.",
            hint = "Comprimilo o subilo en partes.",
            severity = ErrorSeverity.ERROR,
            actions = emptyList(),
        )

        ErrorCode.TIPO_NO_SOPORTADO -> Entry(
            message = "No se puede leer este tipo de archivo.",
            hint = "Subilo como PDF, DOCX, PPTX, TXT o Markdown.",
            severity = ErrorSeverity.ERROR,
            actions = emptyList(),
        )

        ErrorCode.MATERIAL_SIN_TEXTO -> Entry(
            message = "Este material no tiene texto extraído.",
            hint = "La IA no puede responder con él. Podés pegarle el texto a mano en «Editar texto».",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.DISMISS),
        )

        ErrorCode.EXTRACCION_FALLIDA -> Entry(
            message = "No se pudo leer este archivo.",
            hint = "Revisá que no esté dañado ni protegido con contraseña.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.NOMBRE_DUPLICADO -> Entry(
            message = "Ya hay un material con ese nombre.",
            hint = "Renombrá el archivo o subilo con otro nombre.",
            severity = ErrorSeverity.ERROR,
            actions = emptyList(),
        )

        ErrorCode.ESCRITURA_FALLIDA -> Entry(
            message = "No se pudo guardar en el disco.",
            hint = "Revisá que haya espacio libre y que la carpeta de datos sea escribible.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY, ErrorAction.EXPORT_DIAGNOSTIC),
        )

        // ---- Permisos y base de datos ----

        ErrorCode.SIN_PERMISOS -> Entry(
            message = "Kitoru no tiene permiso para acceder a ese archivo.",
            hint = "Elegí otra carpeta o revisá los permisos del sistema.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.OPEN_SETTINGS),
        )

        ErrorCode.BASE_NO_ACCESIBLE -> Entry(
            message = "No se pudo abrir la base de datos de Kitoru.",
            hint = "Revisá los permisos de la carpeta de datos o elegí otra ubicación en los ajustes.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.OPEN_SETTINGS, ErrorAction.RETRY),
        )

        // ---- Configuración ----

        ErrorCode.CONFIG_INVALIDA -> Entry(
            message = "Faltan datos en la configuración del modelo.",
            hint = "Revisá los campos marcados en rojo antes de continuar.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.OPEN_COURSE_MODEL),
        )

        // ---- Cátedra ----

        ErrorCode.CODIGO_INVALIDO -> Entry(
            message = "Ese código no coincide con ninguna cátedra.",
            hint = "Revisá los caracteres y probá de nuevo.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.CUOTA_MATERIALES -> Entry(
            message = "Llegaste al límite de materiales de la cátedra.",
            hint = "Borrá un material que no uses o pedile a la administración que lo aumente.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.DISMISS),
        )

        ErrorCode.VISIBILIDAD_OCULTA -> Entry(
            message = "Esta información está oculta por la visibilidad de la inscripción.",
            hint = "Sólo se pueden ver las métricas, no el contenido de los chats.",
            severity = ErrorSeverity.INFO,
            actions = listOf(ErrorAction.DISMISS),
        )

        ErrorCode.ENTREGA_FALLIDA -> Entry(
            message = "No se pudo enviar la entrega.",
            hint = "Revisá tu conexión y probá de nuevo. El archivo quedó guardado en este dispositivo.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY),
        )

        // ---- Chat ----

        ErrorCode.HERRAMIENTA_FALLIDA -> Entry(
            message = "La herramienta que pidió el modelo falló.",
            hint = "El modelo siguió sin usarla. Podés reintentarlo si te hace falta.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        ErrorCode.RESPUESTA_VACIA -> Entry(
            message = "El modelo no devolvió ninguna respuesta.",
            hint = "Podés reintentarlo.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )

        // ---- Otros ----

        ErrorCode.CANCELADO -> Entry(
            message = "Se detuvo la operación.",
            hint = "Podés seguir trabajando: lo que ya estaba guardado no se pierde.",
            severity = ErrorSeverity.INFO,
            actions = listOf(ErrorAction.DISMISS),
        )

        ErrorCode.DESCONOCIDO -> Entry(
            message = "No se pudo completar la operación.",
            hint = "Probá de nuevo. Si sigue pasando, copiá el diagnóstico y avisale al docente.",
            severity = ErrorSeverity.ERROR,
            actions = listOf(ErrorAction.RETRY, ErrorAction.EXPORT_DIAGNOSTIC, ErrorAction.CONTACT_TEACHER),
        )
    }
}
