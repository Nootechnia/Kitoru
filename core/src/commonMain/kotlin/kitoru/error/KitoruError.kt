package kitoru.error

import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Qué tan grave es un error.
 *
 * Define el color del aviso y el tono: un [INFO] no es un error (es un
 * dato), un [WARNING] se puede esquivar y un [ERROR] cortó una operación.
 */
@Serializable
enum class ErrorSeverity(val label: String) {

    /** Aviso: la app sigue, el usuario puede seguir trabajando. */
    INFO("Aviso"),

    /** Algo no anda pero se puede seguir con otra parte de la app. */
    WARNING("Advertencia"),

    /** La operación falló; el usuario tiene que hacer algo para continuar. */
    ERROR("Error"),

    /** La app no puede seguir: se muestra la pantalla de crash. */
    FATAL("Error grave"),
}

/**
 * Una acción que el aviso puede ofrecer.
 *
 * El enum existe para que el **componente** no reciba lambdas sueltas: el
 * ViewModel decide qué hacer con cada acción y la UI sólo la dibuja. El
 * [label] es el texto literal del botón.
 */
@Serializable
enum class ErrorAction(val label: String) {

    /** "Reintentar": repetir la operación que falló. */
    RETRY("Reintentar"),

    /** "Abrir ajustes": ir a los ajustes de la app. */
    OPEN_SETTINGS("Abrir ajustes"),

    /** "Configurar el modelo": abrir la configuración del modelo de la cátedra. */
    OPEN_COURSE_MODEL("Configurar el modelo"),

    /** "Copiar diagnóstico": generar el reporte local para el bug report. */
    EXPORT_DIAGNOSTIC("Copiar diagnóstico"),

    /** "Usar IA local": degradar al modelo que corre en el dispositivo. */
    SWITCH_TO_LOCAL_MODEL("Usar IA local"),

    /** "Avisar al docente": abrir el contacto de la cátedra. */
    CONTACT_TEACHER("Avisar al docente"),

    /** "Cerrar": descartar el aviso. */
    DISMISS("Cerrar"),
}

/**
 * Un error tal como lo ve el usuario.
 *
 * Es lo que viaja del `catch` a la pantalla. **Nunca** se construye a mano
 * con un `Throwable`: se construye con [ErrorCatalog.of] o con
 * [toKitoruError].
 *
 * @param code el código del error: el índice del catálogo.
 * @param message qué pasó. Una frase, en español, sin jerga técnica.
 * @param hint qué se puede hacer. Otra frase. Puede ir vacía sólo si de verdad
 *   no hay nada que hacer.
 * @param severity qué tan grave es, para el color y para el tono.
 * @param actions las acciones que el aviso ofrece, en orden.
 * @param detail la parte técnica (tipo de excepción, código HTTP, stack
 *   trace). Va al desplegable "Ver detalles": **nunca** al mensaje.
 */
@Serializable
data class KitoruError(
    val code: ErrorCode,
    val message: String,
    val hint: String = "",
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    val actions: List<ErrorAction> = emptyList(),
    val detail: String? = null,
) {

    /** ¿Hay algo que ofrecerle al usuario? */
    val hasActions: Boolean get() = actions.isNotEmpty()

    /** El mensaje completo tal como se lee: mensaje + pista. */
    val fullText: String
        get() = if (hint.isBlank()) message else "$message $hint"

    /** ¿Es un error de verdad, o un aviso que no interrumpe? */
    val isBlocking: Boolean
        get() = severity == ErrorSeverity.ERROR || severity == ErrorSeverity.FATAL
}

/**
 * Convierte cualquier excepción en el error que ve el usuario.
 *
 * Es el **único** camino válido para mostrar un fallo: nunca se escribe
 * `e.message` ni `e.toString()` en pantalla.
 *
 * - Una **cancelación** no es un error (es una acción del usuario): vuelve
 *   como [ErrorCode.CANCELADO], con severidad [ErrorSeverity.INFO].
 * - Un **error del LLM** se traduce por su código (ver [ErrorCatalog.fromLlmCodeName]).
 * - Cualquier **otra** excepción es [ErrorCode.DESCONOCIDO], con el detalle
 *   técnico en [KitoruError.detail] y el mensaje crudo **fuera** de la
 *   pantalla.
 */
fun Throwable.toKitoruError(): KitoruError {
    val detail = technicalDetail()

    // Una cancelación es una decisión del usuario, no un fallo.
    if (this is CancellationException) {
        return ErrorCatalog.of(ErrorCode.CANCELADO, detail = detail)
    }

    // Un error del LLM trae su propio código: se traduce por el nombre.
    val llmCode = llmErrorCodeName()
    if (llmCode != null) {
        return ErrorCatalog.of(ErrorCatalog.fromLlmCodeName(llmCode), detail = detail)
    }

    return ErrorCatalog.of(ErrorCode.DESCONOCIDO, detail = detail)
}

/**
 * La parte técnica de una excepción: tipo, mensaje y stack trace.
 *
 * Va al desplegable "Ver detalles" y al log, nunca al mensaje principal. Las
 * claves de API se redactan antes de salir: el diagnóstico se copia y se
 * pega en un bug report, no puede filtrar una clave.
 */
fun Throwable.technicalDetail(maxFrames: Int = 20): String = redactSecrets(
    buildString {
        append(typeName(this@technicalDetail))
        val raw = message
        if (!raw.isNullOrBlank()) {
            append(": ")
            append(raw)
        }
        appendLine()
        stackTraceToString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(maxFrames)
            .forEach { append(it).appendLine() }
        cause?.let { causa ->
            append("causada por: ")
            append(typeName(causa))
            causa.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }
    }
)

/**
 * El nombre del código de un error del LLM, si esta excepción lo trae.
 *
 * Por qué se reconoce por su nombre y no por su tipo: `:core` no depende de
 * `:llm-api` (al revés: `:llm-api` depende de `:core`), así que acá no se
 * puede importar `llm.error.LlmException` ni leer su propiedad `code` sin
 * crear una dependencia circular. En su lugar se reconoce la clase por su
 * nombre y se saca el código del `toString()`, que tiene la forma
 * `"LlmException[CODIGO, retryable=...]"`.
 *
 * Si el formato cambia, se degrada a `null` (y el error queda
 * [ErrorCode.DESCONOCIDO]): nunca a una excepción sin traducir.
 */
internal fun Throwable.llmErrorCodeName(): String? {
    val simple = typeName(this)
    if (!simple.contains("Llm")) return null
    val raw = toString()
    val entreCorchetes = CODIGO_ENTRE_CORCHETES.find(raw)?.groupValues?.get(1)
    if (entreCorchetes != null) return entreCorchetes
    return ErrorCatalog.llmCodeNames.firstOrNull { raw.contains(it) }
}

/** El nombre simple del tipo de una excepción (o el que se pueda). */
private fun typeName(t: Throwable): String {
    val clase = t::class
    return clase.simpleName ?: clase.toString()
}

/** Las claves de API que se redactan antes de salir en el diagnóstico. */
private val CLAVE_SK = Regex("""sk-[A-Za-z0-9_\-]{8,}""")
private val CLAVE_ASIGNADA = Regex("""(?i)((?:api[_-]?key|apikey|clave|token|authorization|bearer)\s*[:=]\s*)("?)[^\s",;]+""")

/** Reemplaza las claves de API por un marcador. */
private fun redactSecrets(texto: String): String = texto
    .replace(CLAVE_SK, "<clave-redactada>")
    .replace(CLAVE_ASIGNADA) { m -> "${m.groupValues[1]}<clave-redactada>" }

/** El código del LLM tiene la forma `LlmException[CODIGO, retryable=...]`. */
private val CODIGO_ENTRE_CORCHETES = Regex("""\[([A-Z][A-Z0-9_]{2,})""")
