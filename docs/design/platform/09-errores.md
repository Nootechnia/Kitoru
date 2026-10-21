# 09 — Tratamiento de errores y estados vacíos

> Subagente: diseño de **cómo Kitoru comunica los problemas y los estados
> vacíos**: el catálogo de errores por pantalla, el lenguaje de los mensajes,
> el componente `ErrorBanner`, los estados vacíos, los estados de carga, el
> modo degradado, la política de reintento, la validación en línea y la
> pantalla de crash.
> Módulos tocados: `:core` (modelo de error, catálogo de textos, política de
> reintento, validación por campo), `:llm-api` (traducción de `LlmException` a
> error de UI), `:ui` (banner, estados vacíos, esqueleto, `LoadingPane`,
> `CrashScreen`, validación en línea).
> Código de origen (a portar): `FunshiEngineAI/frontend/src/main/kotlin/app/chat/ChatScreen.kt`
> (`ErrorBanner`, `ModelLoadingBanner`), `app/chat/LoadingPane.kt`,
> `app/core/UiState.kt` (`UiProgress`, `humanSize`), `app/chat/ChatViewModel.kt`
> (`friendly`).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*, *el
> estudiante conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Objetivo, alcance y decisiones

### 0.1 El problema que se resuelve

Kitoru es una app que habla con un modelo de IA: el modelo puede no estar, la
red puede caerse, la clave puede vencer, el material puede no tener texto. El
alumno y el docente van a encontrarse, tarde o temprano, con una pantalla en
falso. Lo que **no** puede pasar es que:

1. aparezca un `java.net.ConnectException: Connection refused` en pantalla;
2. el mensaje diga qué **no** puede hacer el usuario sin decir qué **sí** puede;
3. un error del modelo (**un problema del servidor**) se presente como si fuera
   culpa del usuario;
4. un problema local (el modelo no está) tape la app entera.

### 0.2 Las doce decisiones

| # | Decisión | Alternativa descartada | Por qué |
|---|---|---|---|
| D-1 | **Un solo tipo de error para toda la app**: `kitoru.error.KitoruError` (mensaje + hint + severidad + acciones + detalle técnico) | Un `String` de error por pantalla (como el `var error: String` de FunshiEngineAI) | El `String` no distingue "recuperable" de "irrecuperable", ni "se puede reintentar", ni sabe qué botón ofrecer. Con un tipo, el banner, el reintento y el diagnóstico se escriben una vez |
| D-2 | **El texto del error vive en un catálogo** (`ErrorCatalog` en `:core`), no en el `catch` | Escribir el texto en cada `catch` | Escribir el mismo texto en 14 pantallas es como Ensure que se desincronice. El catálogo es **una función pura**: se testea y se revisa como un documento |
| D-3 | **Nunca `e.message` ni `e.toString()` en pantalla**; el texto crudo va al "detalle técnico" expandible | Mostrar el mensaje de la excepción | `LlmException.fromHttp` genera mensajes con "HTTP 401" y fragmentos de cuerpo; `IllegalStateException` muestra nombres de clase. Son para el que depura, no para el que usa |
| D-4 | **Todo error tiene dos partes**: `message` (qué pasó) y `hint` (qué hacer) | Un solo párrafo | El mensaje sin acción deja al usuario sin salida. La acción va en el texto (usuario nuevo) **y** en el botón (usuario que ya sabe) |
| D-5 | **Un solo banner** (`ErrorBanner`) para toda la app; el del chat es el mismo componente con nivel `INFO` | Un `ErrorBanner` y un `ChatBanner` distintos (como hoy en `student/02-chat.md`) | Dos banners = dos estilos, dos tonos, dos Gramáticas. Se reconcilia en §4.6 |
| D-6 | **El estado vacío no es un error**: sin ícono de alarma, sin color de error, con la acción principal a la vista | Estados vacíos como "error" | Un vacío no es un problema: es la ausencia de datos. "Todavía no estás en ninguna cátedra" no es un error, es el punto de partida |
| D-7 | **Esqueleto (shimmer) para listas largas; texto para operaciones cortas; spinner sólo en overlay** | Spinner en todo lado | El spinner hace esperar sin decir qué va a aparecer. El esqueleto dice la forma; el texto dice el nombre de la operación |
| D-8 | **`LoadingPane` con fases**, port del de FunshiEngineAI pero con la lista de fases completa | Una barra sola | Con 4 fases (indexar → descargar → cargar → conectar) el usuario tiene que saber en cuál está: si no, no sabe si falta mucho |
| D-9 | **La app nunca se cae por un error del modelo**: el modelo caído degrada el chat, no la app | Pantalla de error a pantalla completa | El 90 % de la app funciona sin modelo (materiales, tareas, anuncios, historial, analítica del docente) |
| D-10 | **Reintento automático sólo para errores `retryable` y sólo antes de que haya salido un solo token** | Reintentar siempre | Reintentar a ciegas un streaming que ya emitió texto duplica contenido y gasta cuota |
| D-11 | **La validación de formularios es en línea, bajo el campo, con los textos de `ModelPolicy.validate()`** | Un `AlertDialog` con la lista de errores | El diálogo con 6 errores es un muro; el error bajo el campo enseña a corregir. Y **los textos son los mismos** que ya conoce el usuario |
| D-12 | **`CrashScreen` con "Copiar diagnóstico"**, atado al panel de diagnóstico de `process/04-observabilidad.md` | Dejar que la JVM muela | Un crash sin contexto no se arregla. El diagnóstico local (log + crash + métricas) es el contexto |

### 0.3 Qué queda fuera de alcance

- Envío de crashes o de telemetría a un servidor (decisión 5 de
  `process/04-observabilidad.md`).
- Traducción de los mensajes a otros idiomas (el mecanismo de i18n está en
  `student/10-accesibilidad-i18n.md`; este documento define **el español**).
- Reintentos "en segundo plano" de la cola de salida (es de
  `student/01-entrada.md` §8.3; acá sólo se define el motor de reintento).
- Cualquier IA que genere el texto de un error: los mensajes son **literales y
  revisables**, no generados.

---

## 1. El modelo de error

### 1.1 `KitoruError`: el tipo que viaja a la UI

Vive en `:core` (el módulo del que dependen todos) y **no depende de Compose**:
es un dato, no una vista.

```kotlin
// core/src/commonMain/kotlin/kitoru/error/KitoruError.kt
package kitoru.error

/**
 * Un error tal como lo ve el usuario.
 *
 * Es lo que viaja del `catch` a la pantalla. **Nunca** se construye a mano
 * con un `Throwable`: se construye con [ErrorCatalog] o con
 * `llm.error.toKitoruError()`.
 *
 * @param message qué pasó. Una frase, en español, sin jerga técnica.
 * @param hint qué se puede hacer. Una frase. Puede ir vacía si de verdad no
 *   hay nada que hacer (por ejemplo, un error de configuración del servidor).
 * @param severity qué tan grave es, para el color y para el tono.
 * @param retryable si un reintento automático tiene sentido (§8).
 * @param actions las acciones que el banner ofrece, en orden.
 * @param technicalDetail la parte técnica (código HTTP, tipo de excepción,
 *   stack trace). **Nunca** se muestra en el mensaje principal: va al
 *   desplegable "Ver detalles".
 */
data class KitoruError(
    val code: KitoruErrorCode,
    val message: String,
    val hint: String = "",
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    val retryable: Boolean = false,
    val actions: List<ErrorAction> = emptyList(),
    val technicalDetail: String = "",
) {
    /** ¿Hay algo que ofrecerle al usuario? */
    val hasActions: Boolean get() = actions.isNotEmpty()

    /** El mensaje completo tal como se lee: message + hint. */
    val fullText: String
        get() = if (hint.isBlank()) message else "$message $hint"
}
```

### 1.2 Los tres enums

```kotlin
// core/src/commonMain/kotlin/kitoru/error/ErrorSeverity.kt
package kitoru.error

/** Qué tan grave es un error. Determina el color del banner y el tono. */
enum class ErrorSeverity(val label: String) {
    /** Aviso: la app sigue, el usuario puede seguir trabajando. */
    INFO("Aviso"),

    /** Algo no anda pero se puede seguir con otra parte de la app. */
    WARNING("Advertencia"),

    /** La operación falló; el usuario tiene que hacer algo para continuar. */
    ERROR("Error"),

    /** La app no puede seguir: se muestra la [CrashScreen]. */
    FATAL("Error grave"),
}
```

```kotlin
// core/src/commonMain/kotlin/kitoru/error/ErrorAction.kt
package kitoru.error

/**
 * Una acción que el banner puede ofrecer.
 *
 * El enum existe para que el **componente** no reciba lambdas sueltas: el
 * ViewModel decide qué hacer con cada acción y la UI sólo la dibuja. El
 * `label` es el texto literal del botón.
 */
enum class ErrorAction(val label: String) {
    /** "Reintentar" — repetir la operación que falló. */
    RETRY("Reintentar"),

    /** "Usar IA local" — degradar al modelo del dispositivo. */
    USE_LOCAL_MODEL("Usar IA local"),

    /** "Cambiar de modelo" — abrir la configuración del modelo. */
    OPEN_MODEL_SETTINGS("Cambiar de modelo"),

    /** "Configurar la cátedra" — ir a la configuración (docente). */
    OPEN_COURSE_SETTINGS("Configurar la cátedra"),

    /** "Acortar la pregunta" — llevar al redactor con la pregunta recortada. */
    SHORTEN_QUESTION("Acortar la pregunta"),

    /** "Ver materiales" — ir a la lista de materiales. */
    OPEN_MATERIALS("Ver materiales"),

    /** "Ir al chat" — abrir el chat de la cátedra. */
    OPEN_CHAT("Ir al chat"),

    /** "Copiar diagnóstico" — generar el reporte local (§10). */
    COPY_DIAGNOSTIC("Copiar diagnóstico"),

    /** "Avisar al docente" — abrir el contacto de la cátedra. */
    CONTACT_TEACHER("Avisar al docente"),

    /** "Descargar de nuevo" — reintentar la descarga del modelo local. */
    REDOWNLOAD("Descargar de nuevo"),
}
```

```kotlin
// core/src/commonMain/kotlin/kitoru/error/KitoruErrorCode.kt
package kitoru.error

/**
 * Los códigos de error de la app.
 *
 * Es un **vocabulario cerrado**: el texto de cada código está en
 * [ErrorCatalog] y en ningún otro lado. Un código nuevo obliga a escribir su
 * texto (el test de §2.5 lo verifica).
 */
enum class KitoruErrorCode {
    // ---- Red y servidor de la cátedra ----
    /** No hay red (o el servidor no responde). */
    SIN_CONEXION,

    /** El servidor de la cátedra respondió un error 5xx. */
    SERVIDOR_NO_DISPONIBLE,

    /** La clave fue rechazada (401/403). */
    CLAVE_INVALIDA,

    /** La clave de la inscripción venció. */
    CLAVE_VENCIDA,

    /** Se alcanzó el límite de uso (429). */
    SIN_CUOTA,

    /** La operación tardó demasiado. */
    TIMEOUT,

    /** La ventana de contexto del modelo se agotó. */
    CONTEXTO_AGOTADO,

    /** El modelo o la ruta no existen (404) o el modelo no está disponible. */
    MODELO_NO_DISPONIBLE,

    /** El modelo local todavía no está en disco. */
    MODELO_NO_DESCARGADO,

    /** Falló la descarga del modelo local. */
    DESCARGA_FALLIDA,

    /** No hay modelo configurado en absoluto. */
    NO_CONFIGURADO,

    // ---- Datos locales ----
    /** El índice de la cátedra está vacío. */
    INDICE_VACIO,

    /** El índice se está reconstruyendo. */
    INDICE_DESACTUALIZADO,

    /** El material no tiene texto extraíble. */
    MATERIAL_SIN_TEXTO,

    /** Falló la extracción de texto de un archivo. */
    EXTRACCION_FALLIDA,

    /** El archivo ya no está en disco. */
    ARCHIVO_NO_ENCONTRADO,

    /** El archivo supera el tamaño máximo. */
    ARCHIVO_DEMASIADO_GRANDE,

    /** El tipo de archivo no se puede leer. */
    TIPO_NO_SOPORTADO,

    /** Ya existe un material con ese nombre. */
    NOMBRE_DUPLICADO,

    /** No se pudo escribir en el disco. */
    ESCRITURA_FALLIDA,

    /** No hay permiso sobre un archivo o una carpeta. */
    SIN_PERMISOS,

    // ---- Reglas de negocio ----
    /** El código de inscripción no coincide con ninguna cátedra. */
    CODIGO_INVALIDO,

    /** La configuración del modelo no es válida (ver §9). */
    CONFIG_INVALIDA,

    /** Se alcanzó la cuota de materiales de la cátedra. */
    CUOTA_MATERIALES,

    /** La visibilidad de la inscripción no deja mostrar ese dato. */
    VISIBILIDAD_OCULTA,

    /** La entrega del alumno no se pudo enviar. */
    ENTREGA_FALLIDA,

    /** La herramienta que pidió el modelo falló. */
    HERRAMIENTA_FALLIDA,

    /** El modelo respondió vacío. */
    RESPUESTA_VACIA,

    /** Error sin clasificar: nunca debería llegar a la UI. */
    DESCONOCIDO,
}
```

### 1.3 El catálogo: la fuente única de los textos

```kotlin
// core/src/commonMain/kotlin/kitoru/error/ErrorCatalog.kt
package kitoru.error

/**
 * Catálogo de errores: **la fuente única de los textos que ve el usuario**.
 *
 * Todos los mensajes salen de acá. Si un texto aparece en un `catch`, está
 * mal: se escribe acá y se llama a [of].
 *
 * Las reglas del texto están en `docs/design/platform/09-errores.md` §2.
 */
object ErrorCatalog {

    /**
     * Construye el error de un código.
     *
     * @param technicalDetail la parte técnica: va al desplegable "Ver
     *   detalles", nunca al mensaje principal.
     */
    fun of(code: KitoruErrorCode, technicalDetail: String = ""): KitoruError = when (code) {
        KitoruErrorCode.SIN_CONEXION -> KitoruError(
            code = code,
            message = "Estás sin conexión.",
            hint = "Revisá tu conexión a internet. Podés seguir usando lo que ya está en este dispositivo.",
            severity = ErrorSeverity.WARNING,
            retryable = true,
            actions = listOf(ErrorAction.RETRY),
            technicalDetail = technicalDetail,
        )

        KitoruErrorCode.CLAVE_INVALIDA -> KitoruError(
            code = code,
            message = "El servidor de la cátedra rechazó la clave de acceso.",
            hint = "Si la clave venció, pedí una nueva a la cátedra. También podés seguir con la IA de tu dispositivo.",
            severity = ErrorSeverity.ERROR,
            retryable = false,
            actions = listOf(ErrorAction.USE_LOCAL_MODEL, ErrorAction.OPEN_MODEL_SETTINGS),
            technicalDetail = technicalDetail,
        )

        // … los demás códigos, con el mismo patrón (tabla completa en §1.4).
        else -> KitoruError(
            code = code,
            message = "No se pudo completar la operación.",
            hint = "Probá de nuevo. Si sigue pasando, copiá el diagnóstico y avisale al docente.",
            severity = ErrorSeverity.ERROR,
            retryable = false,
            actions = listOf(ErrorAction.RETRY, ErrorAction.COPY_DIAGNOSTIC),
            technicalDetail = technicalDetail,
        )
    }
}
```

### 1.4 La tabla completa del catálogo

Esta tabla **es** el contrato de los textos: si se cambia una celda, se cambia
el comportamiento de la app y hay que actualizarla.

| Código | `message` (qué pasó) | `hint` (qué hacer) | Severidad | ¿Reintentable? | Acciones |
|---|---|---|---|---|---|
| `SIN_CONEXION` | "Estás sin conexión." | "Revisá tu conexión a internet. Podés seguir usando lo que ya está en este dispositivo." | WARNING | Sí | Reintentar |
| `SERVIDOR_NO_DISPONIBLE` | "El servidor de la cátedra no está respondiendo." | "Podés ver tus cátedras, tus materiales y tus chats guardados mientras tanto." | WARNING | Sí | Reintentar |
| `CLAVE_INVALIDA` | "El servidor de la cátedra rechazó la clave de acceso." | "Si la clave venció, pedí una nueva a la cátedra. También podés seguir con la IA de tu dispositivo." | ERROR | No | Usar IA local · Cambiar de modelo |
| `CLAVE_VENCIDA` | "La clave de esta cátedra venció." | "Pedí una clave nueva al docente de la cátedra." | ERROR | No | Contactar al docente · Cambiar de modelo |
| `SIN_CUOTA` | "El servidor de la cátedra alcanzó su límite de uso." | "Podés reintentar en unos minutos o seguir con la IA de tu dispositivo." | WARNING | Sí | Reintentar · Usar IA local |
| `TIMEOUT` | "El servidor tardó demasiado en responder." | "Probá de nuevo. Si sigue igual, enviá una pregunta más corta." | WARNING | Sí | Reintentar |
| `CONTEXTO_AGOTADO` | "La respuesta no entra en la ventana de contexto del modelo." | "Probá con una pregunta más corta o con menos fragmentos del material." | WARNING | No | Acortar la pregunta |
| `MODELO_NO_DISPONIBLE` | "No se pudo obtener una respuesta del modelo." | "El servidor puede estar caído o el modelo no estar disponible. Probá en un momento." | ERROR | Sí | Reintentar · Usar IA local |
| `MODELO_NO_DESCARGADO` | "El modelo local todavía no está descargado." | "Descargalo (ocupa alrededor de 1,2 GB) o seguí con la IA de la cátedra." | INFO | No | Descargar de nuevo · Cambiar de modelo |
| `DESCARGA_FALLIDA` | "No se pudo descargar el modelo local." | "Revisá la conexión y el espacio libre en disco, y probá de nuevo." | ERROR | Sí | Descargar de nuevo |
| `NO_CONFIGURADO` | "No hay ningún modelo configurado para esta cátedra." | "Pedile al docente que configure el modelo en «Configuración del modelo»." | INFO | No | — |
| `INDICE_VACIO` | "Todavía no hay material indexado en esta cátedra." | "Cuando tu docente suba material, va a aparecer acá." | INFO | No | Ver materiales |
| `INDICE_DESACTUALIZADO` | "El material cambió y se está actualizando el índice." | "Podés seguir preguntando; las citas pueden tardar un momento en aparecer." | INFO | No | — |
| `MATERIAL_SIN_TEXTO` | "Este material no tiene texto extraído." | "La IA no puede responder con él." | WARNING | No | Ver materiales |
| `EXTRACCION_FALLIDA` | "No se pudo leer este archivo." | "Revisá que no esté dañado ni protegido con contraseña." | ERROR | Sí | Reintentar · Ver materiales |
| `ARCHIVO_NO_ENCONTRADO` | "El archivo ya no está en este dispositivo." | "Puede que se haya movido o borrado." | WARNING | No | Ver materiales |
| `ARCHIVO_DEMASIADO_GRANDE` | "El archivo supera el tamaño máximo permitido." | "Comprimilo o subilo en partes." | ERROR | No | — |
| `TIPO_NO_SOPORTADO` | "No se puede leer este tipo de archivo." | "Subilo como PDF, DOCX, PPTX, TXT o Markdown." | ERROR | No | — |
| `NOMBRE_DUPLICADO` | "Ya hay un material con ese nombre." | "Renombrá el archivo o subilo con otro nombre." | ERROR | No | — |
| `ESCRITURA_FALLIDA` | "No se pudo guardar en el disco." | "Revisá que haya espacio libre y que la carpeta de datos sea escribible." | ERROR | Sí | Reintentar · Copiar diagnóstico |
| `SIN_PERMISOS` | "Kitoru no tiene permiso para acceder a ese archivo." | "Elegí otra carpeta o revisá los permisos del sistema." | ERROR | No | — |
| `CODIGO_INVALIDO` | "Ese código no coincide con ninguna cátedra." | "Revisá los caracteres y probá de nuevo." | ERROR | No | — |
| `CONFIG_INVALIDA` | "Faltan datos en la configuración del modelo." | "Revisá los campos marcados en rojo." | ERROR | No | — |
| `CUOTA_MATERIALES` | "Llegaste al límite de materiales de la cátedra." | "Borrá un material que no uses o pedile a la administración que lo aumente." | WARNING | No | Ver materiales |
| `VISIBILIDAD_OCULTA` | "Esta información está oculta por la visibilidad de la inscripción." | "Sólo se pueden ver las métricas, no el contenido de los chats." | INFO | No | — |
| `ENTREGA_FALLIDA` | "No se pudo enviar la entrega." | "Revisá tu conexión y probá de nuevo. El archivo quedó guardado en este dispositivo." | ERROR | Sí | Reintentar |
| `HERRAMIENTA_FALLIDA` | "La herramienta que pidió el modelo falló." | "El modelo siguió sin usarla. Podés reintentarlo si te hace falta." | WARNING | Sí | Reintentar |
| `RESPUESTA_VACIA` | "El modelo no devolvió ninguna respuesta." | "Podés reintentarlo." | WARNING | Sí | Reintentar |
| `DESCONOCIDO` | "No se pudo completar la operación." | "Probá de nuevo. Si sigue pasando, copiá el diagnóstico y avisale al docente." | ERROR | No | Reintentar · Copiar diagnóstico |

### 1.5 De `LlmException` a `KitoruError` (en `:llm-api`)

`:llm-api` depende de `:core`, así que la traducción vive ahí y conoce los
códigos de LLM:

```kotlin
// llm-api/src/commonMain/kotlin/llm/error/LlmErrorMapping.kt
package llm.error

import kitoru.error.ErrorAction
import kitoru.error.ErrorSeverity
import kitoru.error.KitoruError
import kitoru.error.KitoruErrorCode

/**
 * Traduce un [LlmException] al error que ve el usuario.
 *
 * **Regla dura**: el `message` de la excepción (que trae el código HTTP y un
 * fragmento del cuerpo de la respuesta) **nunca** se muestra en pantalla: va
 * al detalle técnico. El texto que ve el usuario sale del catálogo.
 */
fun LlmException.toKitoruError(local: Boolean = false): KitoruError {
    // CANCELADO no es un error: es una acción del usuario. Vuelve como `null`
    // para que la UI no muestre ningún banner.
    if (code == LlmErrorCode.CANCELADO) return CANCELLED

    val uiCode = when (code) {
        LlmErrorCode.SIN_CUOTA -> KitoruErrorCode.SIN_CUOTA
        LlmErrorCode.CLAVE_INVALIDA -> KitoruErrorCode.CLAVE_INVALIDA
        LlmErrorCode.TIMEOUT -> KitoruErrorCode.TIMEOUT
        LlmErrorCode.NO_CONFIGURADO -> KitoruErrorCode.NO_CONFIGURADO
        LlmErrorCode.MODELO_NO_DISPONIBLE -> KitoruErrorCode.MODELO_NO_DISPONIBLE
        LlmErrorCode.CONTEXTO_AGOTADO -> KitoruErrorCode.CONTEXTO_AGOTADO
        LlmErrorCode.DESCARGA_FALLIDA -> KitoruErrorCode.DESCARGA_FALLIDA
        // El servidor local caído y el remoto caído se parecen en el código,
        // pero el texto y la acción son distintos (§1.5, tabla de abajo).
        LlmErrorCode.SERVIDOR_CAIDO -> KitoruErrorCode.MODELO_NO_DISPONIBLE
        LlmErrorCode.CANCELADO -> KitoruErrorCode.DESCONOCIDO
        LlmErrorCode.DESCONOCIDO -> KitoruErrorCode.DESCONOCIDO
    }
    val base = ErrorCatalog.of(uiCode, technicalDetail = technicalDetail())
    // El "local" cambia el mensaje y la acción: revisar el modelo local es
    // otra cosa que revisar el servidor de la cátedra.
    return if (local && code == LlmErrorCode.SERVIDOR_CAIDO) {
        base.copy(
            message = "No se pudo hablar con el modelo local.",
            hint = "Revisá que el servidor local esté corriendo y probá de nuevo.",
            severity = ErrorSeverity.WARNING,
            actions = listOf(ErrorAction.RETRY),
        )
    } else {
        base
    }
}
```

Y el caso "cancelado", que **no** es un error:

```kotlin
/** La generación fue cancelada: no es un error, no muestra banner. */
val CANCELLED = KitoruError(
    code = KitoruErrorCode.DESCONOCIDO,
    message = "Se detuvo la generación.",
    severity = ErrorSeverity.INFO,
)
```


Con un matiz importante: **`LlmErrorCode.SERVIDOR_CAIDO` (modelo local) y
`MODELO_NO_DISPONIBLE` (servidor remoto) se diferencian en el texto**, porque
la acción es distinta:

| Situación | Código de UI | `message` |
|---|---|---|
| El `llama-server` local no responde | `MODELO_NO_DISPONIBLE` + severidad `WARNING` | "No se pudo hablar con el modelo local." / "Revisá que el servidor local esté corriendo y probá de nuevo." |
| El gateway remoto no responde | `MODELO_NO_DISPONIBLE` + severidad `ERROR` | "No se pudo obtener una respuesta del modelo." / "El servidor puede estar caído o el modelo no estar disponible. Probá en un momento." |

> El `LlmException` ya trae el `retryable` correcto (ver `LlmErrors.kt`), así
> que la UI **no recalcula** la reintentabilidad: la lee.

### 1.6 `friendly(e)`: la función que reemplaza al `e.message` crudo

En FunshiEngineAI, `ChatViewModel.friendly(e)` devuelve `e.message` tal cual:

```kotlin
// FunshiEngineAI/frontend/.../app/chat/ChatViewModel.kt:574
internal fun friendly(e: Throwable): String = when (e) {
    is LlmException -> e.message
    else -> e.message ?: "Error desconocido"
}
```

Eso es exactamente lo que hay que eliminar: `e.message` de una
`LlmException` dice *"$origin rechazó la clave (HTTP $status)."* y el de una
excepción de I/O dice *"Connection refused"*.

El equivalente en Kitoru:

```kotlin
// core/src/commonMain/kotlin/kitoru/error/ErrorMapping.kt
package kitoru.error

/**
 * Convierte cualquier excepción en un error de UI.
 *
 * Es el **único** camino válido para mostrar un fallo. Nunca se escribe
 * `e.message` en pantalla.
 */
fun Throwable.toKitoruError(): KitoruError = when (this) {
    is CancellationException -> KitoruError(
        code = KitoruErrorCode.DESCONOCIDO,
        message = "Se detuvo la operación.",
        severity = ErrorSeverity.INFO,
    )
    else -> ErrorCatalog.of(KitoruErrorCode.DESCONOCIDO, technicalDetail = technicalDetail())
}

/**
 * La parte técnica de una excepción: tipo, mensaje y stack trace.
 *
 * Va al desplegable "Ver detalles" y al log. Pasa por el
 * [kitoru.platform.log.Redactor] para que una API key escrita por error en un
 * mensaje no llegue al portapapeles.
 */
fun Throwable.technicalDetail(): String = buildString {
    append(this::class.qualifiedName ?: this::class.simpleName ?: "Throwable")
    this.message?.takeIf { it.isNotBlank() }?.let { append(": "); append(it) }
    appendLine()
    append(stackTraceToString())
}
```

> El `Redactor` viene de `process/04-observabilidad.md` §5.2. Si ese módulo
> todavía no existe cuando se implementa este documento, el
> `technicalDetail()` se redacta con un reemplazo local equivalente y se
> cambia cuando el `Redactor` esté.

### 1.7 El detalle técnico: qué entra y qué no

| Entra al detalle técnico | No entra nunca |
|---|---|
| Tipo de la excepción (`java.net.SocketTimeoutException`) | Nombres de archivo con rutas absolutas del usuario |
| Mensaje crudo de la excepción | La API key (se redacta) |
| Código HTTP (401, 429, 503) | El texto del alumno |
| Los primeros 20 frames del stack | El contenido de un material |
| El `LlmErrorCode` (`SIN_CUOTA`) | El cuerpo completo de la respuesta del proveedor (se corta a 300 caracteres) |

El detalle técnico **tiene un botón "Copiar"**: es lo que el usuario pega en el
bug report cuando el banner no le sirve.

---

## 2. El lenguaje de los mensajes

### 2.1 Las siete reglas

**R1 — Nunca un `Exception.toString()` crudo.**
Ni `e.message`, ni `e.toString()`, ni `e.cause?.message` llegan a la pantalla.
El texto sale de `ErrorCatalog` o de `LlmException.toKitoruError()`. Si el
error no está en el catálogo, se muestra `DESCONOCIDO` ("No se pudo completar la
operación.") y el detalle técnico lleva la excepción cruda.

**R2 — El mensaje dice QUÉ pasó Y QUÉ hacer.**
Son dos campos: `message` (qué pasó) y `hint` (qué hacer). El `hint` puede
estar vacío **sólo** cuando de verdad no hay nada que hacer (un error del
servidor que el usuario no puede arreglar). Regla práctica: si el usuario
puede tocar algo, el `hint` existe.

> ❌ "La API alcanzó su límite de uso (HTTP 429)."
> ✅ "El servidor de la cátedra alcanzó su límite de uso." + "Podés reintentar en unos minutos o seguir con la IA de tu dispositivo."

**R3 — Sin culpar al usuario.**
El enunciado describe **el sistema**, no la persona. Se dice "no se pudo", "no
se alcanzó", "falta", "el servidor rechazó". Nunca "no hiciste bien", "te
equivocaste", "introduciste mal", "olvidate", "es un error tuyo".

La diferencia es sutil pero real: en un error de validación (donde el usuario
**sí** se equivocó) el texto señala **el campo**, no la persona:

> ❌ "Introduciste una temperatura inválida."
> ✅ "La temperatura debe estar entre 0 y 2 (actual: 2,5)."

**R4 — Español rioplatense/neutro, como el resto de la app.**
Voseo ("revisá", "esperá", "probá"), sin regionalismos marcados ("computadora"
o "equipo", nunca "la PC" ni "la máquina"), sin "vos" y "usted" mezclados en
el mismo texto. Los términos técnicos que la app ya usa en inglés (streaming,
indexar, chunk, timeout, prompt) **se dejan así**: forzar una traducción
inventa un término nuevo que el usuario tiene que aprender. Los números usan
coma decimal y espacio de miles.

**R5 — Los detalles técnicos van al desplegable.**
Código HTTP, tipo de excepción, stack trace, nombre del modelo interno:
nunca en el mensaje principal. El mensaje principal es de una línea.

**R6 — Sin títulos de alerta.**
No se escribe "¡ERROR!", "¡Fallo!", "¡Ups!". El `message` es una frase que
empieza por el sujeto: "No se pudo…", "El servidor…", "La clave…". El ícono
(⚠ / ✕) ya marca la gravedad; el texto no tiene que hacerlo.

**R7 — Una sola vez el "problema", una sola vez la "solución".**
No se repite la causa en el `hint` ("El servidor no respondió. El servidor no
respondió."). Y el botón no repite el `hint` ("Reintentar" y no "Reintentar
ahora que falló la conexión").

### 2.2 El formato canónico

```
<mensaje: qué pasó>. <hint: qué hacer>.
```

- Los dos textos terminan en punto.
- `message` ≤ 90 caracteres. `hint` ≤ 140 caracteres.
- Los paréntesis se usan para el dato comparativo ("actual: 2,5"), como ya hace
  `ModelPolicy.validate()`.
- Las comillas angulares («…») para nombres de cosas que el usuario reconoce:
  «Configuración del modelo», «Editar texto», «Reintentar».

### 2.3 Ejemplos: bueno / malo

| ❌ Mal | ✅ Bien | Por qué |
|---|---|---|
| `java.net.ConnectException: Connection refused` | "No se pudo conectar con el servidor de la cátedra." + "Revisá tu conexión a internet. El chat quedó pausado: lo que ya enviaste sigue guardado." | R1, R5 |
| "HTTP 401 Unauthorized" | "El servidor de la cátedra rechazó la clave de acceso." + "Si la clave venció, pedí una nueva a la cátedra." | R1, R5 |
| "Error al indexar: java.io.FileNotFoundException: /home/user/.kitoru/courses/x/y.pdf" | "No se pudo leer este archivo." + "Revisá que no esté dañado ni protegido con contraseña." | R1, R5, R4 |
| "No pudiste escribir el archivo" | "No se pudo guardar en el disco." + "Revisá que haya espacio libre y que la carpeta de datos sea escribible." | R3 |
| "La clave que escribiste está mal" | "El servidor de la cátedra rechazó la clave de acceso." + "Si la clave venció, pedí una nueva a la cátedra." | R3 |
| "Falta el nombre del modelo en el servidor." | (igual) | ✓ ya está bien: señala el campo, no la persona |
| "La API alcanzó su límite de uso (HTTP 429)." | "El servidor de la cátedra alcanzó su límite de uso." + "Podés reintentar en unos minutos o seguir con la IA de tu dispositivo." | R2, R5 |
| "Welcome to Kitoru!!!" | "Kitoru se detuvo de forma inesperada." | R6 |
| "Timeout" | "El servidor tardó demasiado en responder." + "Probá de nuevo. Si sigue igual, enviá una pregunta más corta." | R1, R2 |
| "2.5 no es una temperatura válida" | "La temperatura debe estar entre 0 y 2 (actual: 2,5)." | R3, R4 |

### 2.4 Los números y las unidades

- Miles: `1 284` (espacio fino), nunca `1.284` ni `1284`.
- Decimales: coma (`2,1 s`, `0,2 GB`).
- Tamaño de archivo: `2,1 MB`, `48 KB`, `1,2 GB`.
- Latencia: `1 842 ms` si es < 10 s; `2,1 s` si es mayor.
- El `humanSize` de FunshiEngineAI (`app/core/UiState.kt`) usa **punto**
  decimal ("2.35 GB"): el port a Kitoru lo corrige (ver §6.6).

### 2.5 El test de estilo (automático)

Los mensajes son un contrato: un test los revisa, como el que ya fija el
formato del log en `process/04-observabilidad.md` §4.4.

```kotlin
// core/src/desktopTest/kotlin/kitoru/error/ErrorCatalogTest.kt
class ErrorCatalogTest {

    @Test
    fun `todos los codigos tienen mensaje y terminacion`() {
        for (code in KitoruErrorCode.entries) {
            val e = ErrorCatalog.of(code)
            assertTrue(e.message.isNotBlank(), "El código $code no tiene mensaje.")
            assertTrue(e.message.endsWith("."), "El mensaje de $code no termina en punto.")
            if (e.hint.isNotBlank()) {
                assertTrue(e.hint.endsWith("."), "El hint de $code no termina en punto.")
            }
        }
    }

    @Test
    fun `ningun mensaje principal contiene jerga tecnica`() {
        val prohibido = listOf("Exception", "HTTP ", "at ", "java.", "kotlin.", "null")
        for (code in KitoruErrorCode.entries) {
            val e = ErrorCatalog.of(code)
            for (p in prohibido) {
                assertFalse(e.message.contains(p), "El mensaje de $code contiene «$p»: ${e.message}")
                assertFalse(e.hint.contains(p), "El hint de $code contiene «$p»: ${e.hint}")
            }
        }
    }

    @Test
    fun `ningun mensaje culpa al usuario`() {
        val prohibido = listOf("hiciste", "te equivocaste", "mal", "tu error", "incorrecto")
        for (code in KitoruErrorCode.entries) {
            val e = ErrorCatalog.of(code)
            for (p in prohibido) {
                assertFalse(e.message.contains(p), "El mensaje de $code culpa al usuario: ${e.message}")
            }
        }
    }

    @Test
    fun `el mensaje principal es corto`() {
        for (code in KitoruErrorCode.entries) {
            val e = ErrorCatalog.of(code)
            assertTrue(e.message.length <= 90, "El mensaje de $code es demasiado largo: ${e.message}")
        }
    }
}
```

> La lista `prohibido` es deliberadamente corta: no se busca un perfectionista
> de estilo, se busca que **no se cuele un `toString()`**. Un mensaje con
> "HTTP 429" falla el test; uno con "mal" (de "mal configurado") también
> falla, y eso es correcto: la palabra "mal" casi siempre es una culpa
> implícita.

---

## 3. El catálogo de errores por pantalla

Cada tabla tiene las columnas: **pantalla | error | mensaje al usuario | acción
ofrecida | ¿es culpa del usuario?**

La última columna es **interna**: no se muestra. Sirve para decidir si la
acción tiene que ver con el usuario (corregir un campo, reintentar) o con el
sistema (esperar, avisarle al docente). Regla: **ningún error del servidor o
del modelo es culpa del usuario**; sólo lo son los errores de entrada (un
código mal tipeado, un archivo con el nombre repetido, un campo con un valor
fuera de rango).

### 3.1 Alumno

#### 3.1.1 Entrada / Home — `StudentHomeScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Home | El código de inscripción no existe | "Ese código no coincide con ninguna cátedra." + "Revisá los caracteres y probá de nuevo." | [Probar de nuevo] | **Sí** |
| Home | El código existe pero venció | "Ese código ya no sirve." + "Pedile a tu docente un código nuevo." | [Pedir código nuevo] | No |
| Home | Sin red al entrar a una cátedra | "Estás sin conexión." + "Necesitás conexión para entrar a una cátedra. Podés seguir con la IA de tu computadora mientras tanto." | [Reintentar] | No |
| Home | El servidor de la cátedra no responde | "El servidor de la cátedra no está respondiendo." + "Podés ver tus cátedras y tus materiales guardados mientras tanto." | [Reintentar] | No |
| Home | La clave de la inscripción venció | "La clave de esta cátedra venció." + "Pedí una clave nueva al docente de la cátedra." | [Solicitar clave nueva] | No |
| Home | No se pudo guardar en disco | "No se pudo guardar en el disco." + "Revisá que haya espacio libre y que la carpeta de datos sea escribible." | [Reintentar] | Parcial |
| Home | La carpeta de datos no se puede abrir | "No se pudo abrir la carpeta de datos de Kitoru." + "Revisá los permisos de la carpeta o elegí otra ubicación en los ajustes." | [Reintentar] [Abrir ajustes] | Parcial |

#### 3.1.2 Chat — `StudentChatScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Chat | No hay red | "Estás sin conexión." + "Guardamos tu mensaje y lo enviamos apenas vuelva la conexión." | [Reintentar] | No |
| Chat | El servidor remoto no responde | "No se pudo conectar con el servidor de la cátedra." + "Revisá tu conexión a internet. El chat quedó pausado: lo que ya enviaste sigue guardado." | [Reintentar] · [Usar IA local] | No |
| Chat | La clave fue rechazada | "El servidor de la cátedra rechazó la clave de acceso." + "Si la clave venció, pedí una nueva a la cátedra." | [Usar IA local] · [Cambiar de modelo] | No |
| Chat | Cuota agotada (429) | "El servidor de la cátedra alcanzó su límite de uso." + "Podés reintentar en unos minutos o seguir con la IA de tu dispositivo." | [Reintentar] · [Usar IA local] | No |
| Chat | Contexto agotado | "La respuesta no entra en la ventana de contexto del modelo." + "Probá con una pregunta más corta o con menos fragmentos del material." | [Acortar la pregunta] | No |
| Chat | Timeout | "El servidor tardó demasiado en responder." + "Probá de nuevo. Si sigue igual, enviá una pregunta más corta." | [Reintentar] | No |
| Chat | El modelo no existe / 5xx | "No se pudo obtener una respuesta del modelo." + "El servidor puede estar caído o el modelo no estar disponible. Probá en un momento." | [Reintentar] · [Usar IA local] | No |
| Chat | El modelo local no está descargado | "El modelo local todavía no está descargado." + "Descargalo (ocupa alrededor de 1,2 GB) o seguí con la IA de la cátedra." | [Descargar de nuevo] · [Cambiar de modelo] | No |
| Chat | El `llama-server` local no levanta | "No se pudo hablar con el modelo local." + "Revisá que el servidor local esté corriendo y probá de nuevo." | [Reintentar] | No |
| Chat | El modelo devolvió una respuesta vacía | "El modelo no devolvió ninguna respuesta." + "Podés reintentarlo." | [Reintentar] | No |
| Chat | La herramienta que pidió el modelo falló | "La herramienta que pidió el modelo falló." + "El modelo siguió sin usarla. Podés reintentarlo si te hace falta." | [Reintentar] | No |
| Chat | El modelo pidió permiso y se denegó | "El modelo pidió permiso para usar una herramienta y no se lo diste." + "Contestale y autorizalo si lo necesitás." | (ninguna: es información) | Parcial |
| Chat | Se agotó la cola de mensajes encolados | "Hay 3 mensajes en cola." + "Se enviarán juntos cuando termine la respuesta actual." | [Cancelar envío] | No |
| Chat | El hilo ya no existe | "Este chat ya no está en tu historial." + "Puede que lo hayas borrado." | [Volver al chat] | No |

#### 3.1.3 Modelo / configuración — `StudentModelScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Modelo | La configuración tiene campos inválidos | (validación en línea, §9) | [Guardar] (con foco en el primer error) | **Sí** |
| Modelo | La clave no fue aceptada al probarla | "La clave no fue aceptada por el servidor." + "Revisá que la hayas pegado completa, sin espacios." | [Probar de nuevo] | **Sí** |
| Modelo | No se pudo consultar la lista de modelos | "No se pudo consultar la lista de modelos del servidor." + "Podés escribir el nombre del modelo a mano." | [Reintentar] | No |
| Modelo | El modelo elegido no existe | "El modelo «X» no está disponible en el servidor." + "Elegí otro modelo de la lista." | [Cambiar de modelo] | **Sí** |
| Modelo | El docente bloqueó el campo | "Tu cátedra bloqueó este campo." + "La configuración del modelo la define tu docente." | (ninguna) | No |
| Modelo | El servidor local no está disponible en Android | "La IA local todavía no está disponible en Android." + "Usá la IA de la cátedra por ahora." | [Usar la IA de la cátedra] | No |
| Modelo | No se pudo leer la clave del almacén | "No se pudo leer la clave guardada." + "Volvé a escribirla para guardarla de nuevo." | — | No |

#### 3.1.4 Materiales — `StudentMaterialsScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Materiales | El índice está vacío | (estado vacío, §5) | [Ir al chat] | No |
| Materiales | El índice se está reconstruyendo | "El material cambió y se está actualizando el índice." + "Podés seguir preguntando; las citas pueden tardar un momento en aparecer." | — | No |
| Materiales | El material no tiene texto | "Este material no tiene texto extraído." + "La IA no puede responder con él." | [Ver materiales] | No |
| Materiales | No se pudo descargar un enlace | "No se pudo descargar el enlace." + "Podés pegar el texto a mano en «Editar texto»." | [Reintentar] | Parcial |
| Materiales | El archivo ya no está en disco | "El archivo ya no está en este dispositivo." + "Puede que se haya movido o borrado." | [Ver materiales] | No |
| Materiales | No hay ninguna app para abrir el archivo | "No hay ninguna aplicación instalada para abrir «X»." + "Podés guardarlo en otro lugar y abrirlo desde ahí." | — | Parcial |
| Materiales | La búsqueda no encontró nada | (estado vacío con sugerencias, §5) | — | No |

#### 3.1.5 Tareas — `StudentTasksScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Tareas | La entrega no se pudo enviar | "No se pudo enviar la entrega." + "Revisá tu conexión y probá de nuevo. El archivo quedó guardado en este dispositivo." | [Reintentar] | No |
| Tareas | El archivo es demasiado grande | "El archivo supera el tamaño máximo permitido." + "Comprimilo o subilo en partes." | — | Parcial |
| Tareas | La tarea ya cerró | "Esta tarea ya cerró." + "Podés abrirla para ver las entregas, pero no acepta más entregas." | — | No |
| Tareas | La entrega ya fue enviada | "Esta entrega ya está enviada." + "No se puede enviar dos veces la misma tarea." | — | **Sí** |

#### 3.1.6 Anuncios e historial — `StudentAnnouncementsScreen`, `StudentHistoryScreen`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Anuncios | No se pudieron descargar los anuncios nuevos | "No se pudieron descargar los anuncios nuevos." + "Los que ya tenías siguen disponibles." | [Reintentar] | No |
| Historial | No se pudo leer el historial | "No se pudo leer el historial de esta cátedra." + "Probá de nuevo; los chats que ya están cargados siguen visibles." | [Reintentar] | No |
| Historial | Sin conexión | "Estás sin conexión." + "Los chats guardados se muestran igual; los nuevos no llegan hasta que vuelva la conexión." | [Reintentar] | No |

### 3.2 Docente

#### 3.2.1 Panel — `DashboardPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Panel | No se pudo calcular la analítica | "No se pudo calcular la analítica." + "Los datos del panel pueden estar incompletos." | [Reintentar] | No |
| Panel | Faltan datos de la cátedra | "Esta cátedra todavía no tiene materiales ni alumnos." + "Empezá subiendo el material de la cursada." | [Subir material] | No |

#### 3.2.2 Materiales — `MaterialsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Materiales | Falló la extracción | "No se pudo leer este archivo." + "Revisá que no esté dañado ni protegido con contraseña." | [Reintentar] · [Reemplazar archivo] | Parcial |
| Materiales | PDF sin capa de texto | "Este PDF no tiene capa de texto (0 de 12 páginas tienen texto)." + "Parece un escaneo: las imágenes no se pueden leer. Subilo desde el original con texto seleccionable, o pegá el texto a mano en «Editar texto»." | [Pegar el texto] · [Reconocer texto (OCR)] | Parcial |
| Materiales | Falló la indexación | "No se pudo indexar este material." + "El resto de la cátedra sigue funcionando." | [Reintentar] | No |
| Materiales | El tipo no se puede leer | "No se puede leer este tipo de archivo." + "Subilo como PDF, DOCX, PPTX, TXT o Markdown." | — | **Sí** |
| Materiales | El nombre está repetido | "Ya hay un material con ese nombre." + "Renombrá el archivo o subilo con otro nombre." | — | **Sí** |
| Materiales | Se alcanzó la cuota | "Llegaste al límite de materiales de la cátedra." + "Borrá un material que no uses o pedile a la administración que lo aumente." | [Ver materiales] | No |
| Materiales | El material tiene riesgo de inyección | "3 patrones de instrucción detectados en el material." + "Revisá el documento antes de publicarlo para los alumnos." | [Ver detalle] | No |
| Materiales | El archivo se borró de la carpeta | "El archivo ya no está en este dispositivo." + "Puede que se haya movido o borrado." | [Reemplazar archivo] | No |

#### 3.2.3 Tareas y anuncios — `TasksPane`, `AnnouncementsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Tareas | Falló la publicación | "No se pudo publicar la tarea." + "Los alumnos todavía no la ven. Probá de nuevo." | [Reintentar] | No |
| Tareas | No se pudieron avisar los alumnos | "No se pudieron avisar los alumnos." + "Podés avisarles desde el chat de la cátedra." | [Reintentar] | No |
| Anuncios | Falló la publicación | "No se pudo publicar el anuncio." + "Los alumnos todavía no lo ven. Probá de nuevo." | [Reintentar] | No |
| Anuncios | No hay a quién avisar | "No hay alumnos a los que avisar." + "Compartí el código de inscripción para que se sumen." | [Copiar código] | No |

#### 3.2.4 Configuración del modelo — `ModelPolicyPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Modelo (docente) | La clave no fue aceptada | "La clave no fue aceptada por el servidor." + "Revisá que la hayas pegado completa, sin espacios." | [Probar de nuevo] | **Sí** |
| Modelo (docente) | El modelo no existe | "El modelo «X» no está disponible en el servidor." + "Elegí otro modelo de la lista." | [Cambiar de modelo] | **Sí** |
| Modelo (docente) | El servidor no responde | "El servidor de la cátedra no está respondiendo." + "Podés guardar la configuración igual: se aplicará cuando vuelva." | [Reintentar] | No |
| Modelo (docente) | No se pudo guardar | "No se pudo guardar la configuración." + "Revisá que haya espacio libre en el disco." | [Reintentar] | No |
| Modelo (docente) | La configuración tiene campos inválidos | (validación en línea, §9) | [Guardar] | **Sí** |

#### 3.2.5 Analítica — `AnalyticsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Analítica | No se pudo calcular | "No se pudo calcular la analítica." + "Los datos pueden estar incompletos." | [Reintentar] | No |
| Analítica | La analítica está desactivada | "La analítica está desactivada para esta cátedra." + "Activala en «Configuración» si querés ver las estadísticas." | [Activar] | No |
| Analítica | Un alumno ocultó su texto | "Este alumno tiene el texto de sus chats oculto." + "Sólo se pueden ver las métricas." | — | No |
| Analítica | El filtro no arroja resultados | (estado vacío con sugerencias, §5) | [Quitar filtros] | No |

#### 3.2.6 Chats de alumnos — `StudentChatsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Chats | No se pudo cargar la lista | "No se pudieron cargar las conversaciones." + "Probá de nuevo." | [Reintentar] | No |
| Chats | El chat no tiene texto visible | "Este chat no tiene texto visible." + "La inscripción del alumno está en modo «sólo métricas»." | — | No |
| Chats | No se pudo exportar el paquete | "No se pudo exportar el paquete de conversaciones." + "Revisá que la carpeta de destino sea escribible." | [Reintentar] | Parcial |

#### 3.2.7 Alumnos — `StudentsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Alumnos | No se pudo generar el código | "No se pudo generar el código de inscripción." + "Probá de nuevo; si sigue, usá el enlace manual." | [Reintentar] | No |
| Alumnos | El alumno no está más | "Este alumno ya no está inscripto en la cátedra." + "Su historial queda disponible para consulta." | — | No |
| Alumnos | No se pudo cambiar la visibilidad | "No se pudo cambiar la visibilidad de la inscripción." + "Probá de nuevo." | [Reintentar] | No |

#### 3.2.8 Ajustes — `AppSettingsPane`

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Ajustes | No se pudo guardar un ajuste | "No se pudo guardar el ajuste." + "Revisá que haya espacio libre en el disco." | [Reintentar] | No |
| Ajustes | No se pudo generar el diagnóstico | "No se pudo generar el diagnóstico." + "Revisá que la carpeta de datos sea accesible." | [Reintentar] | No |
| Ajustes | No se pudieron borrar los datos | "No se pudieron borrar todos los datos." + "Cerrá los archivos que estén usando la carpeta de Kitoru y probá de nuevo." | [Reintentar] | Parcial |

### 3.3 Plataforma

| Pantalla | Error | Mensaje al usuario | Acción | ¿Culpa del usuario? |
|---|---|---|---|---|
| Arranque | No se pudo abrir la carpeta de datos | "No se pudo abrir la carpeta de datos de Kitoru." + "Revisá los permisos de la carpeta o elegí otra ubicación en los ajustes." | [Reintentar] · [Abrir ajustes] | Parcial |
| Arranque | Falló la indexación inicial | "No se pudo indexar el material de la cátedra." + "Podés seguir usando el chat; las respuestas saldrán sin citas." | [Reintentar] | No |
| Arranque | Falló la descarga del modelo local | "No se pudo descargar el modelo local." + "Revisá la conexión y el espacio libre en disco, y probá de nuevo." | [Reintentar] | No |
| Arranque | El `llama-server` no levanta | "No se pudo arrancar el modelo local." + "Revisá que `llama-server` esté instalado y que el puerto esté libre." | [Reintentar] | Parcial |
| Arranque | La configuración del modelo no es válida | "La configuración del modelo tiene errores." + "Revisá los campos marcados en rojo antes de continuar." | [Abrir configuración] | **Sí** |
| Sincronización | Sin red durante la sincronización | "Estás sin conexión." + "Los datos de este dispositivo están al día; los del servidor llegan después." | [Reintentar] | No |
| Sincronización | Hay cambios en el servidor | "Había cambios en el servidor." + "Se trajeron y ya podés seguir trabajando." | — | No |
| Sincronización | Falló la sincronización | "No se pudo sincronizar con el servidor." + "Seguí trabajando: los cambios se guardan en este dispositivo y se suben después." | [Reintentar] | No |
| Diagnóstico | No se pudo generar el reporte | "No se pudo generar el diagnóstico." + "Revisá que la carpeta de datos sea accesible." | [Reintentar] | No |
| Global | Excepción no capturada | "Kitoru se detuvo de forma inesperada." + "Podés copiar el diagnóstico y volver a intentarlo." | [Copiar diagnóstico] · [Reiniciar la app] | No |

### 3.4 La columna "¿es culpa del usuario?"

Es **interna** y se usa para una sola decisión: **qué botón tiene sentido**.

| Valor | Significado | Botón típico |
|---|---|---|
| **No** | El problema es del servidor, de la red, del modelo o del sistema de archivos | [Reintentar], [Usar IA local], [Avisar al docente] |
| **Parcial** | Depende de una acción del usuario, pero la causa raíz no es suya (un PDF escaneado, un disco lleno) | [Reintentar] con un hint que explica qué revisar |
| **Sí** | El usuario aportó un dato inválido (un código mal tipeado, un nombre repetido, un valor fuera de rango) | [Probar de nuevo] con el foco en el campo, o un mensaje de validación bajo el campo |

**Regla dura**: un error del modelo, del servidor o de la red **nunca** es
"culpa del usuario", por mucho que el mensaje mencione lo que el usuario
puede cambiar. Lo que el usuario puede cambiar es **la salida**, no la causa.

---

## 4. El componente `ErrorBanner`

### 4.1 Qué se porta de FunshiEngineAI

El original (`app/chat/ChatScreen.kt:57`):

```kotlin
@Composable
fun ErrorBanner(vm: ChatViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠ ${vm.error}", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 4)
            TextButton(onClick = vm::dismissError) { Text("Cerrar") }
        }
    }
}
```

Qué se conserva: la `Card` con `errorContainer`, la fila interna, el ícono
`⚠`, el botón "Cerrar", el `bodySmall`.

Qué cambia (y por qué):

| Cambio | Motivo |
|---|---|
| El texto viene de `KitoruError`, no de `vm.error: String` | Sin tipo no hay severidad, ni acciones, ni reintento (D-1) |
| Se separa `message` y `hint` en dos renglones | R2: qué pasó / qué hacer |
| Se agrega el botón de la acción principal ("Reintentar") | El 60 % de los errores se resuelven con un toque |
| Se agrega "Ver detalles" (expandible) | R5: lo técnico no va en el mensaje |
| El banner se puede **desechar** pero no **desaparece solo** | Un error que desaparece solo es un error que el usuario no vio |
| El nivel `INFO` no usa `errorContainer` | Un aviso ("la cátedra todavía no subió material") no es un error: usar el color de error entrena al usuario a ignorar el banner |

### 4.2 La API

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/ErrorBanner.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kitoru.error.ErrorAction
import kitoru.error.KitoruError

/**
 * Banner de error (o de aviso) de la app.
 *
 * Es el **único** componente de aviso de error de Kitoru: lo usan el chat, el
 * panel del docente, la pantalla de materiales, el arranque y la sincronización.
 *
 * @param error el error a mostrar.
 * @param onRetry reintenta la operación que falló. Si es `null`, no se
 *   muestra el botón "Reintentar" (aunque el error sea reintentable).
 * @param onAction ejecuta la acción elegida del enum. Si es `null`, las
 *   acciones del enum se muestran **deshabilitadas**… salvo la principal,
 *   que se oculta (mejor no ofrecer un botón que no hace nada).
 * @param onDismiss descarta el banner. Si es `null`, no hay botón "Cerrar".
 */
@Composable
fun ErrorBanner(
    error: KitoruError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onAction: ((ErrorAction) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    /** Si el detalle técnico está desplegado de entrada. */
    initiallyExpanded: Boolean = false,
)
```

### 4.3 La anatomía

Escritorio:

```
┌──────────────────────────────────────────────────────────────────────┐
│ ⚠  No se pudo conectar con el servidor de la cátedra.                │
│    Revisá tu conexión a internet. El chat quedó pausado: lo que ya    │
│    enviaste sigue guardado.                                          │
│    ▾ Ver detalles                                     [Reintentar] [×]│
└──────────────────────────────────────────────────────────────────────┘
```

Con el detalle desplegado:

```
┌──────────────────────────────────────────────────────────────────────┐
│ ⚠  No se pudo conectar con el servidor de la cátedra.                │
│    Revisá tu conexión a internet. El chat quedó pausado: lo que ya    │
│    enviaste sigue guardado.                                          │
│    ▾ Ver detalles                                     [Reintentar] [×]│
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ llm.error.LlmException: Fallo de red con la API: Connection    │  │
│  │ refused                                                        │  │
│  │     at llm.api.OpenAiCompatibleGateway.chatStream(...)          │  │
│  │     at rag.engine.RagEngine.answer(RagEngine.kt:96)             │  │
│  └────────────────────────────────────────────────────────────────┘  │
│    [Copiar detalle]                                                   │
└──────────────────────────────────────────────────────────────────────┘
```

Android (≤ 600 dp): los botones pasan a una fila propia debajo del texto y
el banner se pliega a **una línea** con elipsis si no entra:

```
┌──────────────────────────────────────┐
│ ⚠ No se pudo conectar con el serv…   │
│ [Reintentar]  [Cerrar]               │
└──────────────────────────────────────┘
```

El texto **no** se pliega a una línea: el `hint` es la parte que el usuario
necesita leer. En Android el banner ocupa como máximo 3 líneas y el resto
scrollea en el detalle.

### 4.4 Niveles y colores

| Severidad | Contenedor | Texto | Ícono | Cuándo |
|---|---|---|---|---|
| `INFO` | `surfaceVariant` | `onSurfaceVariant` | ℹ | Un aviso que no impide trabajar ("la cátedra todavía no subió material") |
| `WARNING` | `secondaryContainer` | `onSecondaryContainer` | ⚠ | Algo no anda pero se puede seguir ("estás sin conexión") |
| `ERROR` | `errorContainer` | `onErrorContainer` | ✕ | La operación falló ("el servidor rechazó la clave") |
| `FATAL` | `errorContainer` + borde de 2 dp | `onErrorContainer` | ✕ | Se muestra la `CrashScreen` en vez del banner |

> El `WARNING` **no** usa `tertiaryContainer`: en el tema por defecto de
> Material 3 el terciario es un tono cercano al primario y no se distingue
> bien del fondo. `secondaryContainer` sí.

### 4.5 Accesibilidad

- El banner es una **`liveRegion`**: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`,
  para que TalkBack lo lea cuando aparece.
- El ícono es **decorativo**: `Modifier.clearAndSetSemantics {}`; el texto ya
  dice todo.
- El botón de reintento tiene `contentDescription = "Reintentar"`.
- Con "reducir movimiento" (`student/10-accesibilidad-i18n.md` §9.3), el
  desplegable de detalles aparece **sin transición** (`AnimatedVisibility` con
  duración 0).
- Con TalkBack, el detalle técnico se lee como texto monoespaciado dentro de
  un `Text` normal (no como un `SelectionContainer`): no hace falta que el
  usuario lo selecte, tiene el botón "Copiar detalle".

### 4.6 Reconciliación con el banner del chat

`student/02-chat.md` §9 define un `ChatBanner` propio (`level`, `title`,
`detail`, `actionLabel`, `onAction`, `dismissible`) y un
`ChatStatusBanner(banner, onDismiss)`. Con D-5 ese par **desaparece** y se
usa `KitoruError` + `ErrorBanner`:

| Antes (`student/02-chat.md`) | Ahora |
|---|---|
| `ChatBanner(level = WARNING, title = "No se pudo conectar…")` | `KitoruError(code = MODELO_NO_DISPONIBLE, severity = WARNING, …)` |
| `ChatBanner(actionLabel = "Reintentar", onAction = …)` | `KitoruError(actions = listOf(ErrorAction.RETRY))` + `onRetry` del `ErrorBanner` |
| `ChatBanner(level = INFO, title = "La cátedra todavía no subió material.")` | `KitoruError(code = INDICE_VACIO, severity = INFO, …)` |
| `ChatStatusBanner(banner, onDismiss)` | `ErrorBanner(error, onDismiss = onDismiss)` |
| `vm.banner: ChatBanner?` | `vm.error: KitoruError?` |

Las **texturas exactas** de los mensajes del chat no cambian: son las del
catálogo (§1.4). Lo que cambia es que ahora salen de un solo lugar.

---

## 5. Los estados vacíos

### 5.1 Qué es un estado vacío (y qué no)

Un estado vacío es **la ausencia de datos en un lugar que sí tiene sentido**:
la lista de cátedras de un alumno que todavía no sejó a ninguna, los materiales
de una cátedra nueva, los chats de una cátedra que todavía no se usó.

| | Estado vacío | Error |
|---|---|---|
| Qué es | "Todavía no hay nada acá" | "Algo que debía funcionar falló" |
| Color | Neutro (`surfaceVariant`), **sin** `errorContainer` | `errorContainer` |
| Ícono | Neutro o ninguno (nunca ⚠/✕) | ⚠ / ✕ |
| Título | Una frase sobre lo que falta | Una frase sobre lo que falló |
| Cuerpo | Qué va a aparecer y cuándo | Qué hacer ahora |
| Acción | **Siempre** una (o ninguna, si no hay ninguna posible) | Sólo si hay algo que hacer |

> Coherente con `student/01-entrada.md` §7.6: *"Sin culpa, sin ícono de error,
> con la acción principal a la vista."*

**Regla del contenido**: un estado vacío dice **qué falta** y **qué pasa
después**, nunca "no hay datos" a secas. "No hay datos" deja al usuario
preguntándose si la app está rota.

### 5.2 El componente

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/EmptyState.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Estado vacío: la ausencia de datos, con la acción principal a la vista.
 *
 * @param title qué falta. Una frase corta, sin punto final (es un título).
 * @param message qué va a pasar o qué puede hacer la persona. Una frase.
 * @param actionLabel el texto del botón principal.
 * @param secondaryActionLabel el texto del botón secundario (opcional).
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
)
```

Anatomía:

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                              [ 🎓 ]                               │
│                                                                  │
│                  Todavía no estás en ninguna cátedra             │
│                                                                  │
│        Necesitás el código que te da tu docente para unirte.     │
│                                                                  │
│                    [ Unirme a una cátedra ]                      │
│                                                                  │
│        Si tu docente te pasó un archivo de cátedra, abrilo con   │
│        doble clic.                                               │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

- El ícono es **opcional** y neutro (🎓, 📄, 💬). Si no hay un ícono
  natural, **no se pone**: un ícono inventado es ruido.
- El botón principal va **centrado y destacado** (`FilledTonalButton`).
- El texto de ayuda va debajo, en `bodySmall`, `onSurfaceVariant`.

### 5.3 El catálogo completo

Todos los textos son **literales**. Los que ya estaban definidos en otros
documentos se conservan textualmente (se marca la fuente).

#### 5.3.1 Alumno sin cátedras — `StudentHomeScreen`

```
[ 🎓 ]
Todavía no estás en ninguna cátedra
Necesitás el código que te da tu docente para unirte.
[ Unirme a una cátedra ]
Si tu docente te pasó un archivo de cátedra, abrilo con doble clic.
```

*(Texto heredado de `student/01-entrada.md` §7.6, sin cambios.)*

#### 5.3.2 Docente sin cátedras — `RoleGateScreen`

```
[ 🏫 ]
No sos docente ni ayudante en ninguna cátedra
Si necesitás entrar a una como equipo, pedile el código del equipo a tu docente.
[ Crear una cátedra ]
[ Unirme con un código ]
```

*(Texto heredado de `student/01-entrada.md` §11, sin cambios.)*

#### 5.3.3 Cátedra sin materiales — `StudentMaterialsScreen` / `MaterialsPane`

Alumno:

```
[ 📄 ]
Esta cátedra todavía no tiene material
Cuando el docente suba material, va a aparecer acá.
[ Ir al chat ]
```

Docente:

```
[ 📄 ]
Todavía no subiste materiales
El RAG de la cátedra usa este corpus: sin material, las respuestas no tienen citas.
[ Subir material ]
```

*(Texto del docente heredado de `teacher/01-navegacion.md` §3.b.)*

#### 5.3.4 Material sin texto extraído — `MaterialDetailPane`

No es un estado vacío de lista: es el estado **de un material concreto**, y por
eso usa el aviso del material (`Material.warning`) más una acción:

```
Este PDF no tiene capa de texto (0 de 12 páginas tienen texto).
Parece un escaneo: las imágenes no se pueden leer.
Subilo desde el original con texto seleccionable, o pegá el texto a mano en «Editar texto».

[ Pegar el texto a mano ]   [ Reconocer texto (OCR) ]
```

El texto exacto del aviso es el que produce el pipeline de extracción
(`rag/01-extraccion.md` §"avisos"), no uno nuevo: el pipeline ya define
"Este PDF no tiene capa de texto (0 de 12 páginas tienen texto). Parece un
escaneo: las imágenes no se pueden leer. Subilo desde el original con texto
seleccionable, o pegá el texto a mano en «Editar texto».".

En el índice de la cátedra, el material aparece con el chip "Requiere
revisión" y no aporta fragmentos. El panel "Probar pregunta" lo dice:

> "Este material no tiene texto extraído: el PDF es un escaneo. La IA no puede
> responder con él."

*(Heredado de `teacher/03-materiales.md` §4.6.)*

#### 5.3.5 Índice vacío — `IndexStatusPane` / chip del chat

Docente (panel de índice):

```
[ 🗂 ]
El índice está vacío
No hay fragmentos para buscar. Subí material o esperá a que termine de indexarse.
[ Ver materiales ]   [ Reindexar ]
```

Alumno (en el chat): el chip "Usar el material" queda **deshabilitado** con el
motivo *"La cátedra todavía no subió material."* y el modo efectivo pasa a
"libre". No es un error: es un estado del chat, y por eso va en el chip, no en
un banner.

*(Heredado de `student/02-chat.md` §8.6 y §9.1.)*

#### 5.3.6 Chat sin mensajes — `MessageList` / `StudentChatScreen`

```
[ 💬 ]
Este chat todavía no tiene mensajes
Escribí una pregunta para empezar. Podés preguntarle sobre el material de la cátedra.
```

En la pantalla del chat, el estado vacío **no** ocupa el centro de la pantalla
con un ícono gigante: la lista de mensajes queda vacía arriba y el redactor
abajo, con una línea de ayuda en el medio:

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│       Escribí tu pregunta para empezar.                 │
│       Podés preguntarle sobre el material de la cátedra.  │
│                                                          │
│       [ ¿Qué es un tensor? ]  [ ¿Dame un ejemplo? ]      │  ← sugerencias
│                                                          │
├──────────────────────────────────────────────────────────┤
│ [● Usar el material] [Preguntas libres]                 │
│ ┌──────────────────────────────────────────┐  ┌───────┐   │
│ │ Escribí tu pregunta…                     │  │ Enviar│   │
│ └──────────────────────────────────────────┘  └───────┘   │
└──────────────────────────────────────────────────────────┘
```

Las sugerencias son **preguntas reales** del material (las "preguntas
frecuentes" que calcula `analytics/03-preguntas-repetidas.md`), no texto de
 relleno. Si no hay preguntas frecuentes todavía, no se muestran sugerencias:
el estado vacío es sólo la frase.

#### 5.3.7 Analítica sin datos — `AnalyticsPane`

`AnalyticsEmptyState(reason, action)` (ya existe en
`teacher/06-analitica.md` §9.2) con tres motivos:

| Motivo | Título | Mensaje | Acción |
|---|---|---|---|
| La cátedra no tiene alumnos | "Todavía no hay alumnos inscriptos." | "Compartí el código de inscripción para que se sumen." | [Copiar código] |
| Nadie chateó todavía | "Todavía no hay datos para mostrar." | "Las estadísticas aparecen cuando los alumnos empiecen a chatear." | [Ver los chats] |
| El filtro no arroja nada | "No hay datos con estos filtros." | "Probá con otro período o quitá el filtro de alumnos." | [Quitar filtros] |

La analítica **desactivada** no es un estado vacío: es un estado de
configuración, y va en un aviso arriba, no en el centro:

> "La analítica está desactivada para esta cátedra." + "Activala en
> «Configuración» si querés ver las estadísticas."

#### 5.3.8 Cátedra sin alumnos — `StudentsPane`

```
[ 👥 ]
Todavía no hay alumnos inscriptos
Compartí el código de inscripción para que se sumen.
[ Copiar código de inscripción ]
```

*(El texto base es el de `teacher/06-analitica.md`; el de `StudentsPane` es el
mismo, para que no haya dos frases distintas en la app.)*

#### 5.3.9 Alumno sin actividad — `StudentAnalyticsDetail`

En la lista de alumnos, la celda de última actividad muestra "— sin actividad
—" (así está en `teacher/08-alumnos.md` §3). En el detalle del alumno:

```
[ 💤 ]
Este alumno todavía no chateó
No hay preguntas ni respuestas registradas.
```

Si el alumno chateó pero en una ventana que no incluye el filtro activo:

```
Sin actividad en este período
Este alumno chateó 4 veces en las últimas dos semanas.
[ Ver todo el curso ]
```

#### 5.3.10 Los demás vacíos (para que la lista esté completa)

| Dónde | Título | Mensaje | Acción |
|---|---|---|---|
| Chats de alumnos | "Todavía no hay conversaciones de alumnos." | "Los chats aparecen cuando alguien empiece a chatear." | [Ver alumnos] |
| Tareas (docente) | "Todavía no hay tareas." | "Publicá una tarea para que los alumnos la vean." | [Crear tarea] |
| Tareas (alumno) | "No hay tareas publicadas." | "Cuando el docente publique una tarea, va a aparecer acá." | — |
| Anuncios (docente) | "Todavía no hay anuncios." | "Publicá un anuncio para avisarle a la cátedra." | [Crear anuncio] |
| Anuncios (alumno) | "No hay anuncios." | "Cuando el docente publique un aviso, va a aparecer acá." | — |
| Búsqueda sin resultados | "No encontramos nada con «X»." | "Probá con otras palabras, o revisá si el material está publicado." | [Limpiar búsqueda] |
| Panel de "probar pregunta" | "No se encontraron fragmentos para «X»." | "Probá con otras palabras, o revisá que el material esté indexado." | [Reindexar] |
| Panel del docente sin actividad | "Todavía no hay actividad en la cátedra." | "Empezá subiendo el material de la cursada." | [Subir material] |
| Historial sin chats | "Todavía no tenés chats en esta cátedra." | "Cuando chatees, tus conversaciones aparecen acá." | [Ir al chat] |
| Selección de modelo vacía | "No hay modelos disponibles." | "Revisá la configuración del servidor de la cátedra." | [Cambiar de modelo] |

### 5.4 El catálogo y los errores: cómo se distinguen en la UI

Un mismo lugar puede mostrar las dos cosas. La regla:

> Si la operación **funcionó** y el resultado está vacío → estado vacío.
> Si la operación **falló** → banner de error.

Ejemplo: la lista de chats del alumno. Si el repo se leyó bien y no hay chats
→ "Todavía no tenés chats en esta cátedra." Si el repo falló al leer → banner
"No se pudo leer el historial de esta cátedra." + [Reintentar].

En el estado vacío **nunca** hay un botón "Reintentar": no hay nada que
reintentar.

---

## 6. Los estados de carga

### 6.1 La regla: esqueleto, texto o spinner

| Situación | Qué se muestra | Por qué |
|---|---|---|
| **Lista o tabla larga** (materiales, alumnos, chats, analítica) que todavía no cargó | **Esqueleto** (shimmer) con la forma real de las filas | El usuario ve la estructura y no cambia de contexto al llegar el dato. Un spinner lo deja mirando una pantalla vacía |
| **Operación corta** (< 2 s: guardar, borrar, refrescar, reindexar) | **Texto**: "Guardando…", "Reindexando…" | Un esqueleto para 300 ms es un parpadeo. Un texto dice qué está pasando |
| **Operación larga con progreso conocido** (descarga de modelo, indexación con % real) | **Texto + barra de progreso** con el porcentaje y los MB | El número es la información; la barra es el contexto |
| **Operación larga sin progreso** (arranque, conexión) | **Texto + barra indeterminada** | Igual que el anterior, sin inventar un porcentaje |
| **Sobre un diálogo o un menú** | **Spinner** (el `CircularProgressIndicator` de M3) | El spinner es lo único que no tapa el contenido de abajo |
| **Streaming del modelo** | El texto que llega, growing | No es una animación decorativa: es el contenido (`student/10-accesibilidad-i18n.md` §9.4) |

**Prohibido**: el spinner gigante en el centro de una lista vacía. Es la
combinación que más se ve y la que menos informa.

### 6.2 El esqueleto

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/Skeleton.kt
package kitoru.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bloque de esqueleto con brillo ("shimmer").
 *
 * Con "reducir movimiento" (el ajuste del SO) el brillo **no** se anima: el
 * bloque queda con el color base. El usuario recibe la misma información
 * (hay algo cargando) sin el movimiento.
 */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    baseColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    highlightColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
)

/** Esqueleto de una lista: [items] filas de alto [itemHeight]. */
@Composable
fun SkeletonList(
    items: Int = 6,
    itemHeight: Dp = 56.dp,
    modifier: Modifier = Modifier,
)

/** Esqueleto de una tabla: [rows] filas con [columns] columnas. */
@Composable
fun SkeletonTable(
    rows: Int = 8,
    columns: Int = 5,
    modifier: Modifier = Modifier,
)

/** Esqueleto de una tarjeta (material, alumno). */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier)
```

La animación del brillo (común, sin `expect/actual`):

```kotlin
val transition = rememberInfiniteTransition(label = "shimmer")
val shift by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 1200, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    ),
    label = "shimmerShift",
)
val brush = Brush.linearGradient(
    colors = listOf(baseColor, highlightColor, baseColor),
    start = Offset(shift * 600f - 300f, 0f),
    end = Offset(shift * 600f + 300f, 0f),
)
```

> Con `LocalReduceMotion.current == true`, `shift` se fija en `0.5f` (el
> bloque queda con el brillo estático) y no se anima nada.

### 6.3 El texto de carga

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/LoadingText.kt
package kitoru.ui.components

/**
 * Texto de carga para operaciones cortas.
 *
 * @param text qué se está haciendo, en presente: "Guardando…".
 */
@Composable
fun LoadingText(
    text: String = "Cargando…",
    modifier: Modifier = Modifier,
    /** Un detalle más debajo (por ejemplo, "1 284 fragmentos"). */
    detail: String = "",
)
```

Textos canónicos (se usan tal cual en toda la app):

| Situación | Texto |
|---|---|
| Genérico | "Cargando…" |
| Guardando | "Guardando…" |
| Borrando | "Borrando…" |
| Enviando | "Enviando…" |
| Reindexando | "Reindexando…" |
| Sincronizando | "Sincronizando…" |
| Calculando | "Calculando…" |
| Publicando | "Publicando…" |

### 6.4 `LoadingPane`: el modelo de fases

Es el port de `FunshiEngineAI/frontend/.../app/chat/LoadingPane.kt`, con una
extensión: en vez de **una** barra, muestra la **lista de fases** con el estado
de cada una, porque Kitoru tiene más de un paso y el usuario tiene que saber
en cuál está.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/LoadingPane.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kitoru.error.KitoruError

/**
 * Estado de una fase del arranque.
 */
enum class PhaseState { PENDING, RUNNING, DONE, FAILED }

/**
 * Una fase del arranque con progreso real.
 *
 * Es el [app.core.UiProgress] de FunshiEngineAI, pero con estado: así la
 * pantalla puede mostrar las fases ya terminadas y la que está corriendo.
 */
data class BootPhase(
    /** Identificador estable ("index", "download", "load", "connect"). */
    val id: String,
    /** Texto de la fase, en presente, con puntos suspensivos. */
    val label: String,
    /** Detalle secondary: "12 documentos", "1 284 fragmentos". */
    val detail: String = "",
    /** Porcentaje, si la fase lo conoce. */
    val percent: Int? = null,
    /** Bytes descargados / totales (fases de descarga). */
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val state: PhaseState = PhaseState.PENDING,
    /** Si el usuario puede cancelar esta fase. */
    val cancellable: Boolean = false,
)

/**
 * Pantalla de carga del arranque, con el progreso REAL por fases.
 *
 * Port de `app/chat/LoadingPane.kt` de FunshiEngineAI, con la lista de fases
 * completa y el error con su banner.
 */
@Composable
fun LoadingPane(
    title: String,
    phases: List<BootPhase>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    error: KitoruError? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
)
```

### 6.5 Las fases de Kitoru

| # | `id` | Texto literal | Cuándo | Progreso | ¿Cancelable? |
|---|---|---|---|---|---|
| 1 | `data` | "Preparando los datos locales…" | Siempre | Indeterminado | No |
| 2 | `index` | "Indexando el material de la cátedra…" | Si hay material visible | Fragmentos: "12 documentos" / "1 284 fragmentos" | No |
| 3 | `download` | "Descargando el modelo local…" | Modo local y el modelo no está en disco | % + MB | **Sí** |
| 4 | `load` | "Cargando el modelo en memoria…" | Modo local | % | No |
| 5 | `connect` | "Conectando con el servidor de la cátedra…" | Modo remoto | Indeterminado | No |
| 6 | `ready` | "Listo" | Todo listo | — | — |

> La fase 2 es el port del "Indexando documentación…" de FunshiEngineAI; la
> fase 3, del "Descargando $file…". Los textos se adaptan al vocabulario de
> Kitoru ("material de la cátedra" en vez de "documentación").

Anatomía:

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                      Preparando el asistente                     │
│                                                                  │
│  ✓ Preparando los datos locales…                                │
│  ● Indexando el material de la cátedra…                         │
│      12 documentos · 1 284 fragmentos                            │
│  ○ Descargando el modelo local…                                  │
│  ○ Cargando el modelo en memoria…                                │
│  ○ Conectando con el servidor de la cátedra…                     │
│                                                                  │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

Con error (el mismo comportamiento que el `LoadingPane` de FunshiEngineAI,
que deja la pantalla en `LOADING` con un botón "Reintentar"):

```
┌──────────────────────────────────────────────────────────────────┐
│                      Preparando el asistente                     │
│                                                                  │
│  ✓ Preparando los datos locales…                                │
│  ✕ Indexando el material de la cátedra…                         │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ ⚠ No se pudo leer este archivo.                            │  │
│  │   Revisá que no esté dañado ni protegido con contraseña.    │  │
│  │                          [Reintentar]  [Continuar sin él]  │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

El botón **"Continuar sin él"** (continuar sin el material que falló) es la
decisión de diseño clave: **el arranque no se cae porque un PDF esté roto**.
El material problemático queda marcado con `NEEDS_REVIEW` y la app sigue.

### 6.6 `humanSize` en español

El `humanSize` de FunshiEngineAI (`app/core/UiState.kt`) usa **punto** decimal
("2.35 GB"), que es formato inglés. El port a Kitoru usa coma:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/HumanSize.kt
package kitoru.ui.components

import kotlin.math.roundToInt

/**
 * Tamaño legible en español: "2,1 MB", "48 KB", "1,2 GB".
 *
 * PORT de `app/core/UiState.kt` (FunshiEngineAI) con dos cambios:
 * la coma decimal (aquí era punto) y el separador de miles con espacio.
 */
fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1L shl 30 -> "${(bytes / (1L shl 30).toDouble()).round1()} GB"
    bytes >= 1L shl 20 -> "${(bytes / (1L shl 20).toDouble()).round1()} MB"
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Un decimal, con coma: 2 → "2,0"; 2,35 → "2,4" (redondeo a un dígito). */
private fun Double.round1(): String {
    val v = (this * 10).roundToInt() / 10.0
    val s = v.toString()                 // "2.0", "12.3"
    return s.replace('.', ',')          // "2,0", "12,3"
}
```

Con esto, el texto de una fase de descarga queda:

> "Descargando el modelo local… — 412,3 MB / 1,2 GB — 34 %"

---

## 7. Recuperable vs irrecuperable: el modo degradado

### 7.1 La regla

> **Si el modelo no está disponible, la app sigue viva.**
> El chat muestra el error; el resto de la app funciona.

Consecuencias concretas:

- Un error del modelo **nunca** reemplaza la pantalla completa.
- Un error del modelo **nunca** bloquea la navegación.
- Un error del modelo **nunca** borra lo que el usuario ya escribió ni lo que ya
  se generó: el historial se lee siempre.
- La única excepción: la **carpeta de datos** ilegible. Sin ella no hay chats,
  ni materiales, ni configuración: ahí sí, pantalla de arranque con error
  (nivel `FATAL`) y nada más.

### 7.2 Los cuatro niveles de degradación

| Nivel | Qué falta | Qué sigue funcionando | Cómo se ve |
|---|---|---|---|
| **L0** | Nada | Todo | — |
| **L1** | El modelo (remoto o local) | Materiales, tareas, anuncios, historial, configuración, analítica del docente | Banner en el chat + el chat no envía |
| **L2** | El servidor de la cátedra | Todo lo local: material descargado, tareas, anuncios, historial, IA local | Barra delgada arriba + "Requiere conexión" en las acciones que necesitan red |
| **L3** | La carpeta de datos local | Nada | Pantalla de arranque de error con [Reintentar] / [Elegir otra carpeta] |

El nivel se calcula con una función pura, para que sea testeable:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Degradation.kt
package kitoru.domain

/**
 * Nivel de degradación de la app.
 *
 * Lo calcula un presenter, no la UI: la UI sólo lo pinta.
 */
enum class DegradationLevel { NORMAL, SIN_MODELO, SIN_SERVIDOR, SIN_DATOS }

/**
 * Calcula el nivel de degradación a partir de lo que se sabe.
 *
 * Función pura: es la que permite testear los tres niveles sin UI.
 */
fun degradationLevel(
    dataDirUsable: Boolean,
    serverReachable: Boolean?,
    modelUsable: Boolean,
): DegradationLevel = when {
    !dataDirUsable -> DegradationLevel.SIN_DATOS
    serverReachable == false -> DegradationLevel.SIN_SERVIDOR
    !modelUsable -> DegradationLevel.SIN_MODELO
    else -> DegradationLevel.NORMAL
}
```

### 7.3 El modo degradado visual

**Regla**: se reemplaza **sólo la zona afectada**. El cromo de la app (rail,
top bar, navegación) sigue igual.

```
┌──────────────────────────────────────────────────────────────────┐
│  ⚠ El chat no está disponible ahora.                             │
│    El servidor de la cátedra no responde.                        │
│    Podés seguir leyendo el material y los chats guardados.       │
│    [Reintentar]                                    [Usar IA local]│
├──────────────────────────────────────────────────────────────────┤
│  (la lista de mensajes sigue, con lo que ya se generó)           │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│  (el redactor, deshabilitado, con el motivo abajo)               │
│  ┌────────────────────────────────────────────────────┐  ┌────┐  │
│  │ No se puede enviar: falta conexión con el servidor.│  │Enviar│ │
│  └────────────────────────────────────────────────────┘  └────┘  │
└──────────────────────────────────────────────────────────────────┘
```

El componente del panel degradado:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/DegradedPanel.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kitoru.error.KitoruError

/**
 * Panel de modo degradado: reemplaza **sólo** la zona que no funciona.
 *
 * No es un error a pantalla completa: el resto de la app sigue viva
 * (§7.1). Se usa en lugar del `ErrorBanner` cuando el área afectada es
 * grande (el cuerpo del chat, el cuerpo de la analítica).
 */
@Composable
fun DegradedPanel(
    error: KitoruError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onAction: ((ErrorAction) -> Unit)? = null,
    /** Qué sigue funcionando, para que el usuario no crea que se perdió todo. */
    stillWorks: String = "",
)
```

Textos de "qué sigue funcionando", por contexto:

| Contexto | Texto |
|---|---|
| Chat | "Podés seguir leyendo el material y los chats guardados." |
| Analítica | "Los datos ya calculados se siguen mostrando." |
| Materiales | "Podés seguir viendo los materiales que ya descargaste." |
| Panel del docente | "El resto del panel sigue funcionando." |

### 7.4 El modo degradado sin conexión (L2)

Es un caso aparte porque **no es un error**: es una condición. La app lo
comunica con una barra delgada arriba, no con un banner:

```
┌──────────────────────────────────────────────────────────────────┐
│  ⚠ Estás sin conexión · los chats con el servidor de la cátedra  │
│    están pausados                            [Reintentar]       │
└──────────────────────────────────────────────────────────────────┘
```

Y las acciones que necesitan red quedan **deshabilitadas con el motivo**, no
ocultas (ocultarlas hace que el usuario no entienda por qué no pasa nada):

| Acción | Estado sin conexión |
|---|---|
| "Unirme a una cátedra" | Deshabilitada — "Necesitás conexión para unirte a una cátedra." |
| "Descargar material" | Deshabilitada — "Necesitás conexión para descargar material." |
| "Pedir una clave" | Deshabilitada — "Necesitás conexión para pedir tu clave. Podés seguir con la IA de tu computadora mientras tanto." |
| "Enviar" en el chat remoto | Deshabilitado — "Sin conexión con el servidor de la cátedra." |
| Ver el material descargado | Activa |
| Chatear con la IA local | Activa |

*(Consistente con `student/01-entrada.md` §8.2, que ya define esta tabla.)*

---

## 8. La reintención

### 8.1 Qué se reintenta automáticamente

Tres condiciones, **todas** necesarias:

1. El error es `retryable` (`LlmException.retryable`, que ya lo define
   `LlmErrors.kt`).
2. La operación es **idempotente** o **no empezó a producir salida**: si el
   modelo ya emitió texto, reintentar duplica la respuesta.
3. El reintento tiene sentido **con la espera máxima** de la tabla de §8.2: si
   el próximo intento cae fuera de esa ventana, no se reintenta (se muestra
   el banner y el usuario decide).

> El reintento automático **siempre es visible**: el banner dice
> "Reintentando… (2 de 4)" y ofrece "Cancelar". Un reintento invisible es un
> reintento que el usuario no puede interrumpir.

### 8.2 La tabla de reintentos

| Error | ¿Automático? | Intentos | Espera | Razón |
|---|---|---|---|---|
| `TIMEOUT` | **Sí** | 3 totales (2 reintentos) | 2 s, 4 s | El servidor suele tardar más la segunda vez |
| `MODELO_NO_DISPONIBLE` (5xx / 404) | **Sí** | 3 totales | 1 s, 3 s | Caída momentánea del servidor |
| `SERVIDOR_CAIDO` (local) | **Sí** | 3 totales | 1 s, 4 s | El `llama-server` tarda en levantar |
| `DESCARGA_FALLIDA` | **Sí** | 4 totales | 2 s, 5 s, 15 s | La descarga se corta por la red |
| `SIN_CUOTA` (429) | **No** | — | — | La cuota no se recupera en segundos: un reintento automático quemaría los intentos sin chances |
| `CONTEXTO_AGOTADO` | **No** | — | — | El reintento falla igual: hay que acortar la pregunta |
| `CLAVE_INVALIDA` / `CLAVE_VENCIDA` | **No** | — | — | Es determinístico: hay que cambiar la clave |
| `NO_CONFIGURADO` | **No** | — | — | Es determinístico: hay que configurar |
| `RESPUESTA_VACIA` | **No** (automático no) | — | — | Se reintenta a mano: el modelo puede estar listo para otra cosa |
| `CODIGO_INVALIDO`, `CONFIG_INVALIDA`, `NOMBRE_DUPLICADO`, `TIPO_NO_SOPORTADO` | **No** | — | — | Errores de entrada: sólo los arregla el usuario |

**Los errores que no son de LLM** (`ESCRITURA_FALLIDA`, `ENTREGA_FALLIDA`,
`EXTRACCION_FALLIDA`) **no** se reintentan automáticamente: la acción es del
usuario ("Reintentar") y una espera invisible no aporta nada.

### 8.3 El backoff

```kotlin
// core/src/commonMain/kotlin/kitoru/error/RetryPolicy.kt
package kitoru.error

import kotlin.random.Random

/**
 * Política de reintento.
 *
 * @param maxAttempts intentos **totales**, incluido el original: 1 = sin
 *   reintento.
 * @param baseDelayMs espera del primer reintento.
 * @param maxDelayMs tope de la espera.
 * @param jitterRatio variación aleatoria (0,2 = ±20 %) para que varios
 *   clientes no reintenten a la vez.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val baseDelayMs: Long,
    val maxDelayMs: Long,
    val jitterRatio: Double = 0.2,
) {
    init {
        require(maxAttempts >= 1) { "El número de intentos debe ser al menos 1." }
        require(baseDelayMs > 0) { "La espera base debe ser mayor que 0." }
        require(maxDelayMs >= baseDelayMs) { "La espera máxima no puede ser menor que la base." }
    }

    /** La espera del reintento [attempt] (1 = el primero), sin jitter. */
    fun baseDelayFor(attempt: Int): Long {
        val exp = baseDelayMs shl (attempt - 1).coerceIn(0, 20)
        return exp.coerceAtMost(maxDelayMs)
    }

    /** La espera con jitter. */
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val base = baseDelayFor(attempt)
        val delta = (base * jitterRatio).toLong()
        if (delta <= 0) return base
        return (base - delta + random.nextLong(0, 2 * delta + 1)).coerceAtLeast(0)
    }
}
```

Las políticas concretas:

```kotlin
// core/src/commonMain/kotlin/kitoru/error/RetryPolicies.kt
package kitoru.error

/** Políticas de reintento por código. */
object RetryPolicies {

    /** Timeout: 3 intentos, 2 s y 4 s. */
    val TIMEOUT = RetryPolicy(maxAttempts = 3, baseDelayMs = 2_000, maxDelayMs = 4_000)

    /** Modelo no disponible (5xx/404): 3 intentos, 1 s y 3 s. */
    val MODELO_NO_DISPONIBLE = RetryPolicy(maxAttempts = 3, baseDelayMs = 1_000, maxDelayMs = 3_000)

    /** Servidor local caído: 3 intentos, 1 s y 4 s. */
    val SERVIDOR_LOCAL_CAIDO = RetryPolicy(maxAttempts = 3, baseDelayMs = 1_000, maxDelayMs = 4_000)

    /** Descarga del modelo: 4 intentos, 2 s, 5 s y 15 s. */
    val DESCARGA = RetryPolicy(maxAttempts = 4, baseDelayMs = 2_000, maxDelayMs = 15_000)

    /** La política de un error, o `null` si no se reintenta solo. */
    fun forCode(code: KitoruErrorCode): RetryPolicy? = when (code) {
        KitoruErrorCode.TIMEOUT -> TIMEOUT
        KitoruErrorCode.MODELO_NO_DISPONIBLE -> MODELO_NO_DISPONIBLE
        KitoruErrorCode.DESCARGA_FALLIDA -> DESCARGA
        else -> null
    }
}
```

El motor de reintento (reutilizable por el gateway, el chat y la cola):

```kotlin
// core/src/commonMain/kotlin/kitoru/error/Retry.kt
package kitoru.error

import kotlinx.coroutines.delay

/**
 * Ejecuta [block] reintentando según [policy].
 *
 * @param onBeforeRetry se llama antes de cada reintento (para el banner
 *   "Reintentando… (2 de 4)").
 * @param shouldRetry decide si vale la pena el siguiente intento (por
 *   ejemplo: si ya salió texto, no).
 */
suspend fun <T> retryWithPolicy(
    policy: RetryPolicy?,
    onBeforeRetry: suspend (attempt: Int, delayMs: Long) -> Unit = { _, _ -> },
    shouldRetry: suspend (error: Throwable) -> Boolean = { true },
    block: suspend () -> T,
): T {
    if (policy == null) return block()
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            attempt++
            if (attempt >= policy.maxAttempts || !shouldRetry(e)) throw e
            val wait = policy.delayFor(attempt)
            onBeforeRetry(attempt + 1, wait)
            delay(wait)
        }
    }
}
```

### 8.4 El reintento manual

Cuando el automático se agota (o el error no es reintentable), el banner
ofrece el botón "Reintentar". El reintento manual:

- **no** lleva cuenta atrás ni backoff (el usuario decidió);
- **sí** muestra el estado ("Reintentando…" y el botón "Reintentar" pasa a
  ser "Cancelar");
- **sí** limpia el error anterior al empezar (si no, el banner queda con un
  error viejo mientras se reintenta);
- **no** borra el mensaje del alumno ni la respuesta parcial.

### 8.5 La respuesta parcial y el reintento

Regla dura del chat:

> Si el modelo **ya emitió texto**, el error **no** se reintenta
> automáticamente. La respuesta parcial se conserva, marcada como incompleta,
> y el banner ofrece "Reintentar" a mano.

Esto evita duplicar contenido y gastar cuota. El mensaje parcial queda con el
marcador `"(detenido)"` que ya usa `student/02-chat.md` §3.4.

### 8.6 Cancelar el reintento

El botón "Cancelar" del banner de reintento:

1. cancela el job de reintento;
2. **no** cancela la respuesta ya empezada (eso es "Detener");
3. deja el error visible con el texto "Se canceló el reintento." + "Probá de
   nuevo cuando quieras."

---

## 9. Los errores de validación de los formularios

### 9.1 El contrato

La validación de Kitoru **ya tiene un estilo** (`ModelPolicy.validate()`):

> "El límite de tokens debe ser mayor que 0 (actual: $maxTokens)."
> "La temperatura debe estar entre 0 y 2 (actual: $temperature)."
> "Falta el nombre del modelo en el servidor."

Ese estilo se conserva **exactamente** en toda la app:

1. La frase empieza por **el campo**, no por la persona ("La temperatura…", no
   " pusiste una temperatura inválida").
2. **Termina en punto.**
3. Cuando ayuda, incluye el valor actual **entre paréntesis**.
4. **No** dice "inválido", "incorrecto" ni "mal": dice qué tiene que ser.

### 9.2 Asociación campo → mensaje

Hoy `ModelPolicy.validate()` devuelve una `List<String>` sin saber a qué campo
pertenece cada mensaje. Para poder marcar el campo en la UI hay que asociar.
**Los textos no cambian**: `validate()` pasa a ser una vista de la tabla.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicyValidation.kt
package kitoru.domain

/**
 * Los campos de la configuración del modelo que la UI puede marcar.
 */
enum class ModelField { BASE_URL, MODEL_NAME, API_KEY, TEMPERATURE, MAX_TOKENS, TOP_K }

/**
 * Validación de la configuración **por campo**.
 *
 * Los textos son **exactamente** los de `ModelPolicy.validate()`: la lista
 * sigue existiendo, pero ahora cada mensaje sabe a qué campo pertenece.
 */
fun ModelPolicy.validateByField(): Map<ModelField, String> {
    val errors = linkedMapOf<ModelField, String>()
    if (maxTokens <= 0) {
        errors[ModelField.MAX_TOKENS] =
            "El límite de tokens debe ser mayor que 0 (actual: ${maxTokens.esNumber()})."
    }
    if (temperature < 0.0 || temperature > 2.0) {
        errors[ModelField.TEMPERATURE] =
            "La temperatura debe estar entre 0 y 2 (actual: ${temperature.esNumber()})."
    }
    if (topK <= 0) {
        errors[ModelField.TOP_K] =
            "El número de fragmentos recuperados debe ser mayor que 0 (actual: $topK)."
    }
    if (mode == ModelMode.REMOTE) {
        if (remoteBaseUrl.isBlank()) {
            errors[ModelField.BASE_URL] = "Falta la dirección del servidor de la cátedra."
        } else if (!remoteBaseUrl.trim().startsWith("http://") &&
            !remoteBaseUrl.trim().startsWith("https://")
        ) {
            errors[ModelField.BASE_URL] =
                "La dirección del servidor debe empezar con http:// o https://."
        } else if (modelName.isBlank()) {
            errors[ModelField.MODEL_NAME] = "Falta el nombre del modelo en el servidor."
        }
    }
    return errors
}
```

Y `ModelPolicy.validate()` se redefine como la vista de esa tabla, **sin
cambiar un solo texto**:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt (modificado)

/** Problemas de la configuración (vacío = válida). En español, como en FunshiEngineAI. */
fun validate(): List<String> = validateByField().values.toList()
```

> `validate()` sigue siendo `List<String>` y sigue devolviendo los mismos
> textos: ningún llamador existente se rompe. Lo que **suma** es la tabla por
> campo, que la UI usa para marcar el `OutlinedTextField` correcto.

### 9.3 El formato de los números

`$maxTokens` con un `Int` no tiene problema, pero `$temperature` con un
`Double` produce "2.5" (punto). En español va coma:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/NumberFormat.kt
package kitoru.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Un número en formato español: coma decimal y espacio de miles.
 *
 * "2,5" · "0,2" · "1 284"
 */
fun Double.esNumber(decimals: Int = 2): String {
    val factor = 10.0.pow(decimals)
    val v = round(this * factor) / factor
    val intPart = floor(v).toLong()
    val frac = abs(v - intPart)
    val text = if (frac < 10.0 / factor) {
        intPart.toString()
    } else {
        val d = (frac * factor).roundToInt().toString().padStart(decimals, '0')
        "$intPart,$d"
    }
    // Separador de miles con espacio: 1284 → "1 284".
    val (sign, digits) = if (text.startsWith("-")) "-" to text.drop(1) else "" to text
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return sign + grouped
}

/** El mismo formato, para enteros: "1 284". */
fun Int.esNumber(): String = toLong().esNumber()

/** El mismo formato, para enteros largos: "1 284". */
fun Long.esNumber(): String {
    val text = toString()
    val sign = if (startsWith("-")) "-" else ""
    val digits = text.removePrefix("-")
    return sign + digits.reversed().chunked(3).joinToString(" ").reversed()
}
```

Con esto, el mensaje queda: *"La temperatura debe estar entre 0 y 2 (actual:
2,5)."* — igual que en el resto de la app (el log y el diagnóstico usan coma
decimal, §2.4).

### 9.4 El composable del error de campo

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/FieldError.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * El error de un campo, **debajo del campo**.
 *
 * Mismo estilo que `ModelPolicy.validate()`: una frase, con el nombre del
 * campo como sujeto.
 */
@Composable
fun FieldErrorText(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
```

Uso dentro de un `OutlinedTextField`:

```kotlin
val error = policy.validateByField()[ModelField.BASE_URL]

OutlinedTextField(
    value = baseUrl,
    onValueChange = { baseUrl = it },
    label = { Text("Dirección del servidor") },
    isError = error != null,
    supportingText = error?.let { { FieldErrorText(it) } },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)
```

### 9.5 Cuándo se valida

| Momento | Se valida | Motivo |
|---|---|---|
| Mientras se escribe | **No** | El error aparece antes de que el usuario termine de escribir; "La dirección del servidor debe empezar con http://" mientras se tipea "h" es ruido |
| Al salir del campo (blur) | **Sí** | El usuario ya terminó ese campo |
| Al presionar "Guardar" | **Sí, todos** | Se validan todos los campos y el foco va al primero con error |
| Al cambiar de modo (REMOTE ↔ LOCAL) | **Sí** | El conjunto de campos válidos cambia |
| Después de un guardado exitoso | Se limpia | — |

El botón "Guardar" **no se deshabilita** cuando hay errores. Motivo: un
botón deshabilitado no explica por qué; el usuario cree que la app está
rota. El botón se habilita, y al presarlo:

1. se validan todos los campos;
2. se marca el primero con error;
3. el foco va a ese campo (`FocusRequester`);
4. el mensaje del primer error se lee en voz alta (TalkBack).

```kotlin
// En el ViewModel de la configuración del modelo
fun save() {
    val errors = policy.validateByField()
    if (errors.isNotEmpty()) {
        firstErrorField = errors.keys.first()
        // El foco lo pide la UI con un LaunchedEffect(firstErrorField).
        return
    }
    // … guarda
}
```

### 9.6 El formulario entero

Cuando el formulario tiene **muchos** campos con error, la lista de errores se
muestra **también** arriba, como resumen, con enlaces a cada campo:

```
┌──────────────────────────────────────────────────────────────┐
│ ⚠ Faltan datos en la configuración del modelo.               │
│   · La dirección del servidor debe empezar con http:// o       │
│     https://.                                                 │
│   · La temperatura debe estar entre 0 y 2 (actual: 2,5).       │
└──────────────────────────────────────────────────────────────┘
```

Es el mismo `ErrorBanner` con `severity = ERROR` y `code = CONFIG_INVALIDA`,
pero **sin** botón "Reintentar" (no hay nada que reintentar): el mensaje es
`"Faltan datos en la configuración del modelo."` + `"Revisá los campos
marcados en rojo."`, y las acciones son `[]`.

---

## 10. El "algo salió mal" global: `CrashScreen`

### 10.1 Cuándo aparece

| Situación | Qué hace la app |
|---|---|
| Excepción no capturada en un hilo de la UI (desktop) | Se escribe el crash en `logs/crash.log` (§7 de `process/04-observabilidad.md`) y se muestra la `CrashScreen` |
| Excepción no capturada en Android | El proceso muere (comportamiento normal de Android); en el **próximo** arranque, el panel de diagnóstico muestra el aviso *"La última sesión terminó de forma inesperada (2026-10-21 13:44:02)."* con un botón "Ver" |
| Error de datos locales irrecuperable (carpeta ilegible) | `StartupErrorScreen` (no es un crash: es un error de arranque controlado) |
| Un error de la UI (excepción en un `catch` que se escapa) | Idéntico al primero: el handler global la atrapa |

### 10.2 La pantalla

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/CrashScreen.kt
package kitoru.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pantalla de crash: la app se detuvo y no puede seguir.
 *
 * Es la única pantalla a pantalla completa de error. Existe para que el
 * usuario pueda **copiar el diagnóstico** y llevar el problema a quien lo
 * resuelva (ver `docs/design/process/04-observabilidad.md` §5 y §9).
 */
@Composable
fun CrashScreen(
    error: Throwable,
    modifier: Modifier = Modifier,
    onCopyDiagnostic: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
)
```

Anatomía:

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                              [ ⚠ ]                               │
│                                                                  │
│                Kitoru se detuvo de forma inesperada              │
│                                                                  │
│    Algo salió mal y la app se cerró. Podés copiar el             │
│    diagnóstico y volver a intentarlo.                             │
│    Tus chats y materiales quedaron guardados en este dispositivo. │
│                                                                  │
│         [ Copiar diagnóstico ]   [ Reiniciar la app ]   [ Salir ] │
│                                                                  │
│    ▾ Ver detalles técnicos                                       │
│                                                                  │
│    No se envió nada a ningún servidor.                            │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

Textos literales:

| Elemento | Texto |
|---|---|
| Título | "Kitoru se detuvo de forma inesperada." |
| Mensaje | "Algo salió mal y la app se cerró. Podés copiar el diagnóstico y volver a intentarlo." |
| Mensaje (2ª línea) | "Tus chats y materiales quedaron guardados en este dispositivo." |
| Botón principal | "Copiar diagnóstico" |
| Botón secundario | "Reiniciar la app" |
| Botón terciario | "Salir" |
| Enlace | "Ver detalles técnicos" |
| Pie | "No se envió nada a ningún servidor." |

> El pie es **obligatorio**: el usuario que ve "Kitoru se detuvo" puede
> suponer que se mandó un reporte. En Kitoru no se manda nada: el diagnóstico
> queda en el portapapeles y lo manda la persona, por el canal que ya use.

### 10.3 El botón "Copiar diagnóstico"

**No** copia directamente: abre el diálogo de consentimiento de
`process/04-observabilidad.md` §5.5 (`DiagnosticShareDialog`), porque el
diagnóstico puede incluir el contenido del chat y hay que pedir permiso.

```
┌──────────────────────────────────────────────────────────────┐
│  Compartir diagnóstico                                        │
│                                                              │
│  Se va a generar un texto plano con el estado de la app       │
│  para que puedas pegarlo en un reporte de problema.           │
│  No se envía nada a ningún servidor: sólo queda en tu          │
│  portapapeles.                                                │
│                                                              │
│  ◉ Diagnóstico básico                                         │
│    Versión, sistema, estado del índice, configuración         │
│    efectiva, métricas, últimos errores y logs.                │
│    No incluye el contenido de las conversaciones.             │
│                                                              │
│  ○ Diagnóstico con el contenido del chat                     │
│    …                                                          │
│                                                              │
│  [ Cancelar ]                    [ Copiar diagnóstico ]       │
└──────────────────────────────────────────────────────────────┘
```

En la `CrashScreen` el diálogo se abre **con el diagnóstico básico ya
marcado** (es el crash: el contexto es lo que importa) y el contenido del
chat **desmarcado** (el usuario puede estar mirando la pantalla del
docente).

Después de copiar, la `CrashScreen` muestra un aviso de confirmación:

> "Diagnóstico copiado. Pegalo en el reporte de problema."

### 10.4 El detalle técnico

El desplegable "Ver detalles técnicos" muestra:

- el tipo de la excepción;
- el mensaje crudo;
- los primeros 20 frames del stack trace;

en monoespaciado, con un botón "Copiar". Va redactado con el `Redactor`
(§1.7): ni la API key ni el texto del alumno.

---

## 11. Nombres: archivos, tipos y composables

### 11.1 `:core` — modelo de error (nuevo)

```
core/src/commonMain/kotlin/kitoru/error/
├── KitoruError.kt          data class KitoruError
├── KitoruErrorCode.kt      enum KitoruErrorCode
├── ErrorSeverity.kt        enum ErrorSeverity
├── ErrorAction.kt          enum ErrorAction
├── ErrorCatalog.kt         object ErrorCatalog  (LA fuente de los textos)
├── ErrorMapping.kt         Throwable.toKitoruError(), Throwable.technicalDetail()
├── RetryPolicy.kt          data class RetryPolicy
├── RetryPolicies.kt        object RetryPolicies
└── Retry.kt                suspend fun <T> retryWithPolicy(...)
```

```
core/src/commonMain/kotlin/kitoru/domain/
├── ModelPolicyValidation.kt   enum ModelField, ModelPolicy.validateByField()
├── NumberFormat.kt            Double.esNumber(), Int.esNumber()
└── Degradation.kt             enum DegradationLevel, degradationLevel(...)
```

### 11.2 `:llm-api` — la traducción (modificado)

```
llm-api/src/commonMain/kotlin/llm/error/
├── LlmErrors.kt           (existente, sin cambios)
└── LlmErrorMapping.kt     (nuevo) fun LlmException.toKitoruError(): KitoruError
```

### 11.3 `:ui` — los componentes (nuevo)

```
ui/src/commonMain/kotlin/kitoru/ui/
├── components/
│   ├── ErrorBanner.kt        @Composable ErrorBanner(error, onRetry, onAction, onDismiss)
│   ├── ErrorDetails.kt       @Composable ErrorDetails(detail) + "Copiar detalle"
│   ├── DegradedPanel.kt      @Composable DegradedPanel(error, …, stillWorks)
│   ├── EmptyState.kt         @Composable EmptyState(title, message, …)
│   ├── Skeleton.kt           ShimmerBlock, SkeletonList, SkeletonTable, SkeletonCard
│   ├── LoadingText.kt        @Composable LoadingText(text, detail)
│   ├── LoadingPane.kt        @Composable LoadingPane(…), BootPhase, PhaseState
│   ├── HumanSize.kt          fun humanSize(bytes): String
│   └── FieldError.kt         @Composable FieldErrorText(message)
└── CrashScreen.kt            @Composable CrashScreen(error, onCopyDiagnostic, onRestart, onExit)
```

### 11.4 Composables públicos

| Componente | Lo usa |
|---|---|
| `ErrorBanner` | Todas las pantallas (es el único banner de error) |
| `DegradedPanel` | Chat, analítica, materiales |
| `EmptyState` | Todas las listas vacías |
| `SkeletonList` / `SkeletonTable` / `SkeletonCard` | Materiales, alumnos, chats, analítica |
| `LoadingText` | Acciones cortas (guardar, borrar, publicar) |
| `LoadingPane` | Arranque de la app y descarga del modelo local |
| `FieldErrorText` | Todos los formularios |
| `CrashScreen` | El handler de excepción no capturada (desktop) |

### 11.5 Tests previstos

| Ruta | Test | Qué fija |
|---|---|---|
| `core/src/desktopTest/kotlin/kitoru/error/ErrorCatalogTest.kt` | §2.5 | Todos los códigos tienen mensaje; sin jerga técnica; sin culpas; longitud |
| `core/src/desktopTest/kotlin/kitoru/error/ErrorMappingTest.kt` | — | `LlmException` → código de UI correcto; el texto crudo **nunca** aparece en `message` |
| `core/src/desktopTest/kotlin/kitoru/error/RetryPolicyTest.kt` | — | El backoff crece, respeta el tope y el jitter está en el rango |
| `core/src/desktopTest/kotlin/kitoru/domain/ModelPolicyValidationTest.kt` | — | `validate()` devuelve **los mismos textos** que antes; `validateByField()` asocia cada mensaje a su campo |
| `core/src/desktopTest/kotlin/kitoru/domain/NumberFormatTest.kt` | — | "2,5" · "0,2" · "1 284" |
| `core/src/desktopTest/kotlin/kitoru/domain/DegradationTest.kt` | — | Los cuatro niveles |

---

## 12. Checklist de implementación

1. `:core`: `kitoru.error` completo (`KitoruError`, enums, `ErrorCatalog`,
   `ErrorMapping`).
2. `:core`: `RetryPolicy`, `RetryPolicies`, `retryWithPolicy`.
3. `:core`: `ModelPolicy.validateByField()` + `validate()` delegando + `esNumber()`.
4. `:llm-api`: `LlmException.toKitoruError()`.
5. `:ui`: `ErrorBanner` + `ErrorDetails` + `DegradedPanel`.
6. `:ui`: `EmptyState` + el catálogo de textos (§5.3).
7. `:ui`: `Skeleton`, `LoadingText`, `LoadingPane` (+ `BootPhase`), `humanSize` en español.
8. `:ui`: `FieldErrorText` + el foco al primer error en los formularios.
9. `:ui`: `CrashScreen` + el diálogo de diagnóstico.
10. Reemplazar los `String` de error sueltos de los ViewModels por `KitoruError?`.
11. Reemplazar el `friendly(e)` de FunshiEngineAI por `Throwable.toKitoruError()`.
12. Tests de `:core` (§11.5) y el test de humo de `:backend` (`process/04-observabilidad.md` §8.4), que ya tiene un caso de error del gateway.

---

## 13. Casos borde

| Caso | Comportamiento |
|---|---|
| El modelo devuelve un mensaje **vacío** | La burbuja muestra "…" (el placeholder) y se reemplaza por "⚠ El modelo no devolvió ninguna respuesta." con la opción "Reintentar" (ya definido en `student/02-chat.md` §11) |
| La respuesta se corta a la mitad | Se conserva el texto parcial, marcado "(detenido)", y **no** se reintenta automáticamente (§8.5) |
| El usuario pide tres cosas y la tercera falla | Cada error va a su propio banner; el chat no se bloquea entero |
| Dos errores a la vez | Se muestra **el más reciente**; el anterior se guarda en el log y en el panel de diagnóstico |
| El error aparece mientras el usuario escribe | El texto en el redactor **no se pierde**: el banner va arriba, el redactor abajo |
| El error es de un hilo que ya no está | El error se muestra en el chat actual (que es el que está abierto), no en el anterior |
| El usuario cierra el banner y el error sigue | El error vuelve a aparecer en el próximo intento; el banner no es un silenciador permanente |
| La red se va y vuelve | Al volver, el banner de "Sin conexión" se descarta solo y la cola se envía (`student/01-entrada.md` §8.3) |
| El docente bloquea un campo que el alumno había cambiado | La diferencia de configuración se muestra como aviso, no como error: "Tu cátedra bloqueó este campo." + "La configuración del modelo la define tu docente." |
| El usuario pegó la clave con espacios | La validación del campo: "Revisá que la hayas pegada completa, sin espacios." — pero el mensaje **del servidor** (401) es el del catálogo: "El servidor de la cátedra rechazó la clave de acceso." |
| Dos reintentos automáticos a la vez | Cada uno lleva su propio banner y su propio contador |
| El usuario pide "Reintentar" mientras ya hay un reintento en curso | El botón se deshabilita y pasa a "Cancelar" |
| Un material falla al extraerse | No tira la app: queda `NEEDS_REVIEW` con su aviso, y el arranque ofrece "Continuar sin él" (§6.5) |
| La analítica tarda más de 10 s | El esqueleto sigue; después de 10 s, un texto "Calculando…" debajo del esqueleto |
| El chat se abre sin material y sin modelo | Chat vacío + banner "No se pudo obtener una respuesta del modelo." + el redactor deshabilitado. La app no se cae |

---

## 14. Criterios de aceptación

1. **Ningún mensaje de la app contiene el `toString()` de una excepción**: lo
   verifica `ErrorCatalogTest` y, para los errores de LLM, `ErrorMappingTest`.
2. **Todo error mostrado tiene `message` + `hint`**, salvo los casos de §2.1
   donde de verdad no hay nada que hacer.
3. **Ningún mensaje culpa al usuario**: lo verifica `ErrorCatalogTest`.
4. **Todo error tiene una acción** cuando se puede hacer algo: el banner
   ofrece el botón de la acción principal.
5. **El error nunca borra ni oculta lo ya generado**: un banner de error sobre
   un chat no borra los mensajes.
6. **Un error del modelo no tapa la app**: el resto de las secciones sigue
   navegable y el material sigue legible.
7. **Los reintentos automáticos son visibles** (el banner dice cuántas
   veces reintentó) y cancelables.
8. **El chat no reintenta solo si el modelo ya emitió texto.**
9. **Todo estado vacío dice qué falta y qué hacer**, y tiene la acción
   principal a la vista; ninguno usa el color ni el ícono de error.
10. **La validación de los formularios es en línea, bajo el campo**, con los
    textos de `ModelPolicy.validate()` sin una palabra cambiada.
11. **El botón "Guardar" nunca se deshabilita por un error de validación**:
    al presarlo se marca el primer campo con error y el foco va a él.
12. **La `CrashScreen` ofrece "Copiar diagnóstico"** y deja claro que no se
    envió nada a ningún servidor.
13. **Los estados de carga de listas largas usan esqueleto**, y las
    operaciones cortas usan texto. No hay un spinner gigante en el centro de
    una lista.
14. **El `LoadingPane` muestra la fase actual** y el porcentaje real cuando lo
    hay; el arranque no se cae porque un material falle.

---

## 15. Reconciliación con los otros documentos

| Documento | Qué cambia con este diseño |
|---|---|
| `student/02-chat.md` §9 | El `ChatBanner` y el `ChatStatusBanner` se reemplazan por `KitoruError` + `ErrorBanner` (§4.6). **Los textos no cambian**: son los del catálogo |
| `student/01-entrada.md` §7.6 y §8 | Los estados vacíos y el modo degradado sin conexión se adoptan tal cual (§5.3.1, §7.4) |
| `student/04-materiales.md` §3.7 | El estado vacío de búsqueda con sugerencias se mantiene; la tabla de la §5.3.10 lo lista |
| `teacher/01-navegacion.md` §3.b | El estado vacío de materiales del docente se mantiene literal (§5.3.3) |
| `teacher/03-materiales.md` §4.6 | El aviso de "PDF sin capa de texto" se mantiene literal (§5.3.4) |
| `teacher/06-analitica.md` §9.2 | `AnalyticsEmptyState(reason, action)` se mantiene; se le agrega la tabla de los tres motivos (§5.3.7) |
| `teacher/08-alumnos.md` §3 | El "— sin actividad —" se mantiene en la tabla; el detalle del alumno usa el estado vacío de §5.3.9 |
| `process/04-observabilidad.md` §5, §7, §9 | El `CrashScreen` usa su `DiagnosticShareDialog` y su `Redactor` (§10.3, §1.7); el banner del crash del panel de diagnóstico ya está definido ahí |
| `student/10-accesibilidad-i18n.md` §9.3 | El esqueleto respeta "reducir movimiento": sin brillo animado (§6.2) |

---

## 16. Lo que este documento NO hace (a propósito)

- No define el **tono** de los mensajes del modelo (eso es del prompt del
  sistema de la cátedra, `rag/05-prompt-catedra.md`).
- No define la **traducción** de los mensajes a otros idiomas (el mecanismo
  está en `student/10-accesibilidad-i18n.md`; este documento fija **el
  español**, que es el idioma por defecto).
- No define la **cola de salida** del alumno sin conexión (es de
  `student/01-entrada.md` §8.3); acá sólo el motor de reintento, que es
  reutilizable por ella.
- No **genera** los mensajes con un modelo: son literales y revisables.
- No manda **nada** a ningún servidor, tampoco los errores.
