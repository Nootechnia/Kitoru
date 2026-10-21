# 08 · Modelo local del alumno y el camino del "el servidor no anda"

> Subagente: diseño del **subsistema de modelo local del alumno** y del camino
> completo que se abre cuando el servidor de la institución no funciona.
>
> Módulos tocados: `:core` (dominio + plataforma), `:llm-api` (gateway local,
> descargador, supervisor del proceso), `:backend` (RAG local, corpus),
> `:ui` (pantallas del alumno).
>
> Reglas del proyecto que se respetan: *reutilizar no reescribir* (todo lo de
> llama.cpp y Hugging Face viene de FunshiEngineAI), *portable por defecto*,
> *fail-fast con mensajes en español*, *el estudiante conserva agencia*, *el
> docente no ve lo que no debe*, *determinista* (la verificación de citas y la
> analítica no dependen del modelo ni de la red).

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | Hay **tres** orígenes de inferencia, no dos: `REMOTE`, `LOCAL` y `OFFLINE` (el material de la cátedra sin IA). | "Nunca quedás sin poder estudiar" no se cumple con dos estados: si no hay servidor **y** no hay modelo local, el alumno todavía tiene el material de la cátedra y puede estudiar con eso. Un tercer estado explícito evita la pantalla de error. |
| D2 | El origen se resuelve **por mensaje**, no por sesión, con una función pura `OriginResolver.resolve(...)`. | La salud del servidor cambia a mitad de una tarde; la decisión tiene que ser reproducible, testeable y auditable. Una función pura (regla 3) es la única forma de no tener lógica de fallback escondida en la UI. |
| D3 | El paso automático a `LOCAL` **sólo ocurre si el alumno ya lo habilitó antes** (opt-in). Si no hay modelo instalado, se **ofrece** y se sigue en `OFFLINE`. | Descargar 2,5 GB y calentar la máquina es un coste real del alumno (ver §4). El fallback no puede gastarle disco y batería a sus espaldas. |
| D4 | `OFFLINE` **no es un error**: es un modo de estudio con el corpus local: búsqueda BM25, fragmentos citados, apuntes, export. | Un modo que responde con "no puedo" no es estudiar. El modo offline responde con material real, etiquetado como extracción, no como IA. |
| D5 | Todo cambio de origen es **visible**: banner permanente con el origen, el motivo y un botón de reintento. | El alumno tiene que saber con qué modelo le están contestando. Y el docente tiene que poder afirmar que no vio nada que no se le haya compartido. |
| D6 | El modelo local de escritorio se entrega en **3 fases**: (1) descarga + supervisor + puerto + health check, (2) compilación de llama.cpp, (3) catálogo + medición del coste. | La fase 1 es la que cierra el hueco "el servidor no anda" con el menor riesgo; las otras dos son comodidades. |
| D7 | La descarga del GGUF es **reanudable y verificada**: `.part` + `Range` + `Content-Length` + **sha256** + magic `GGUF`. | Un modelo a medias produce respuestas absurdas. La verificación es lo que separa "descargado" de "descargado bien". |
| D8 | `llama-server` corre como **proceso hijo supervisado**: log en disco, puerto alternativo si está ocupado, health check de dos fases, apagado que mata el árbol de procesos en Windows. | El `LlamaServerLocalModelService` actual miente en su mensaje de error ("revisá el log del proceso") porque no guarda log, y muere con "address in use" sin contexto. |
| D9 | En Android, el objetivo es **llama.cpp vía JNI** (opción a); **MediaPipe LLM Inference** (opción b) es el puente de corto plazo. | llama.cpp unifica el catálogo GGUF con el escritorio y trae el chat template del modelo; MediaPipe es 1 semana de integración y cubre el hueco urgente, pero obliga a un segundo formato de modelo. |
| D10 | El coste se muestra **antes** de descargar (tamaño, RAM, velocidad) y **después** de medir: la app no estima a ciegas, **lee la cabecera GGUF** y **mide** los tokens/s en la máquina del alumno. | Decir "~8 tokens/s" sin medirlo es inventar. La app tiene el modelo, la GPU/CPU del alumno y el reloj: puede medirlo. |
| D11 | La privacidad se dice **antes** de activar el modo local, con un texto exacto (§5.2), y el banner lo repite en cada chat local. | Es un momento de confianza: el alumno está aceptando que su docente no vea nada. Se dice una vez, claro, y se recuerda todo el tiempo. |
| D12 | El corpus local se sincroniza por **manifiesto + hash** y se indexa con el **mismo `Chunker` + `Bm25Index`** que el servidor. | El RAG local tiene que dar **el mismo material** y las mismas citas. Es un invariante verificable con un test. |
| D13 | El material que el docente marca como **no visible** se **borra** de la copia local en la siguiente sincronización. | El alumno no puede tener en su disco material que la cátedra retiró. |
| D14 | En modo local, el texto **no sale del dispositivo**; las métricas se calculan localmente y **sólo** se suben si la visibilidad de la inscripción las permite. | Coherente con la matriz de visibilidad de `CONTEXTO.md`: en LOCAL el docente no ve el texto, y las estadísticas dependen de la inscripción. |

---

## 1. La promesa de la app: "el alumno nunca se queda sin poder estudiar"

### 1.1 Qué significa la promesa

La promesa **no** es "el alumno siempre tiene IA". Es:

> En cualquier momento, en cualquier dispositivo, el alumno puede **seguir
> estudiando** el material de su cátedra: con la IA de la institución, con su
> propio modelo, o con el material solo (búsqueda y citas, sin generación).

Eso se traduce en tres estados de trabajo, no en un interruptor de "on/off":

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  REMOTE   IA de la cátedra      Lo que ve el docente: el texto (si la      │
│           (servidor + key)      inscripción lo permite) + métricas          │
│                                                                             │
│  LOCAL    IA en el dispositivo  Lo que ve el docente: NADA del texto.      │
│           (llama.cpp / MP)      Métricas sólo si la inscripción lo permite │
│                                                                             │
│  OFFLINE  Material de la         Lo que ve el docente: NADA.               │
│           cátedra, sin IA       (ni siquiera métricas: no hay servidor)   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Los tres orígenes (dominio)

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/InferenceOrigin.kt`

```kotlin
@Serializable
enum class InferenceOrigin(val label: String, val shortLabel: String) {
    /** IA alojada por la institución (Kitoru-Server) con API key. */
    REMOTE("Servidor de la cátedra", "Servidor"),

    /** IA corriendo en el propio dispositivo del alumno. */
    LOCAL("IA local del alumno", "IA local"),

    /** Sin IA: sólo el material de la cátedra, sincronizado en el dispositivo. */
    OFFLINE("Material de la cátedra (sin IA)", "Sin IA"),
}

/** Por qué se está usando el origen que se está usando. */
@Serializable
enum class FallbackReason(val label: String) {
    NORMAL("Sin incidentes"),
    REMOTE_UNREACHABLE("El servidor de la cátedra no responde"),
    REMOTE_AUTH("La clave de la cátedra fue rechazada"),
    REMOTE_QUOTA("El servidor de la cátedra alcanzó su límite de uso"),
    REMOTE_TIMEOUT("El servidor de la cátedra tardó demasiado"),
    LOCAL_FAILED("El modelo local no se pudo arrancar"),
    NETWORK_OFFLINE("Este dispositivo no tiene conexión"),
}
```

### 1.3 Los estados de salud (dominio)

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/Availability.kt`

```kotlin
/** Cómo está el servidor de la cátedra. */
sealed interface RemoteStatus {
    /** Todavía no se probó. */
    data object Unknown : RemoteStatus

    /** El servidor respondió y hay modelos. */
    data class Ok(val models: List<String>) : RemoteStatus

    /** 401/403: la clave fue rechazada (puede estar vencida). */
    data class AuthFailed(val message: String) : RemoteStatus

    /** 429: el servidor alcanzó su límite de uso. */
    data class QuotaExceeded(val message: String) : RemoteStatus

    /** No se pudo hablar con el servidor (caído, sin DNS, sin red). */
    data class Unreachable(
        val message: String,
        val retryable: Boolean = true,
        /** true si además no hay red en el dispositivo. */
        val offline: Boolean = false,
    ) : RemoteStatus
}

/** Cómo está el motor local del alumno. */
sealed interface LocalStatus {
    /** El alumno nunca activó IA local en esta instalación. */
    data object NotConfigured : LocalStatus

    /** Falta el binario (llama-server / lib nativa). */
    data class MissingBinary(val hint: String) : LocalStatus

    /** Falta el archivo del modelo. */
    data class MissingModel(val preset: String, val sizeBytes: Long) : LocalStatus

    /** Se está preparando (descargando, compilando, cargando). */
    data class Preparing(val stage: SetupStage, val percent: Int?, val message: String) : LocalStatus

    /** Listo: motor corriendo. */
    data class Ready(val port: Int, val model: String, val tokensPerSecond: Double) : LocalStatus

    /** Falló el arranque. */
    data class Failed(val message: String, val retryable: Boolean) : LocalStatus
}

/** Estado de la red del dispositivo (lo alimenta un `expect/actual`). */
enum class NetworkStatus { UNKNOWN, ONLINE, OFFLINE }
```

### 1.4 El resolvedor (función pura, testeable)

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/OriginResolver.kt`

```kotlin
/** Qué hacer con el próximo mensaje. */
data class OriginDecision(
    val origin: InferenceOrigin,
    val reason: FallbackReason,
    /** Una línea para el banner ("" = no hay nada que avisar). */
    val message: String,
    /** Si el alumno puede reintentar contra el servidor. */
    val canRetryRemote: Boolean,
    /** Si corresponde ofrecerle instalar/arrancar el modelo local. */
    val canOfferLocalSetup: Boolean,
)

object OriginResolver {

    /**
     * Decide el origen del próximo mensaje.
     *
     * Reglas (en este orden):
     *  1. Si el origen preferido es REMOTE y el servidor está OK → REMOTE.
     *  2. Si el servidor falla y el motor local está Ready y la cátedra permite
     *     IA local → LOCAL (fallback automático, D3).
     *  3. Si el servidor falla y no hay motor local listo → OFFLINE, con
     *     `canOfferLocalSetup = true`.
     *  4. Si el origen preferido es LOCAL y el motor está Ready → LOCAL.
     *  5. Si el origen preferido es LOCAL y el motor falló → OFFLINE
     *     (con `canRetryRemote` si el servidor está OK: el alumno puede
     *      volver al servidor).
     *  6. OFFLINE no sube solo: el alumno decide cuándo vuelve al servidor.
     */
    fun resolve(
        preferred: InferenceOrigin,
        remote: RemoteStatus,
        local: LocalStatus,
        network: NetworkStatus,
        localAllowed: Boolean,
    ): OriginDecision { /* ... */ }
}
```

Tabla de comportamiento (es la que se testea):

| Preferido | Servidor | Motor local | Red | → Origen | Razón | Banner |
|---|---|---|---|---|---|---|
| REMOTE | `Ok` | — | ONLINE | **REMOTE** | `NORMAL` | — |
| REMOTE | `Unreachable` | `Ready` | ONLINE | **LOCAL** | `REMOTE_UNREACHABLE` | "El servidor de la cátedra no responde. Seguimos con tu modelo local." |
| REMOTE | `AuthFailed` | `Ready` | ONLINE | **LOCAL** | `REMOTE_AUTH` | "La clave de la cátedra fue rechazada. Seguimos con tu modelo local." |
| REMOTE | `QuotaExceeded` | `Ready` | ONLINE | **LOCAL** | `REMOTE_QUOTA` | "El servidor alcanzó su límite de uso. Seguimos con tu modelo local." |
| REMOTE | `Unreachable` | `NotConfigured` | ONLINE | **OFFLINE** | `REMOTE_UNREACHABLE` | "El servidor no responde. Podés descargar el modelo local o estudiar con el material." |
| REMOTE | `Unreachable` | — | OFFLINE | **OFFLINE** | `NETWORK_OFFLINE` | "Estás sin conexión." |
| LOCAL | — | `Ready` | cualquier | **LOCAL** | `NORMAL` | banner permanente de modo local |
| LOCAL | `Ok` | `Failed` | ONLINE | **OFFLINE** | `LOCAL_FAILED` | "No se pudo arrancar el modelo local. El servidor de la cátedra sí responde." |
| OFFLINE | `Ok` | — | ONLINE | **OFFLINE** | `NORMAL` | "Estás en modo sin IA. Volvé al servidor cuando quieras." |

**Regla de no-subida (D3 aplicada):** `OFFLINE` nunca sube solo a `REMOTE`.
Cuando el servidor vuelve a responder y el alumno estaba en `OFFLINE` por un
fallo, la UI muestra un aviso con un botón *"Volver al servidor"*; el cambio lo
confirma el alumno. En cambio, si el alumno **no** había elegido `LOCAL`
(su preferido es `REMOTE`) y hubo un fallback automático a `LOCAL`, cuando el
servidor vuelve **sí** se vuelve solo, porque se está restituyendo su
preferencia, no tomándole una decisión.

### 1.5 Qué ve el alumno en cada fallo

Los cuatro fallos pedidos, con lo que ve, lo que puede hacer y lo que se le
ofrece.

#### A. El servidor está caído (`Unreachable`, HTTP 5xx, conexión rechazada)

**Ve**: un aviso arriba del chat, con el nombre del servidor y la hora del
último intento.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ⚠ El servidor de la cátedra no responde                                  │
│                                                                            │
│ No pudimos hablar con ia.universidad.edu/kitoru/v1 (lo intentamos a las   │
│ 10:42). El servicio puede estar caído o mantenimiento.                    │
│                                                                            │
│ Podés seguir estudiando:                                                   │
│   ● Chatear con tu modelo local     Phi-4-mini · ~8 tokens/s en este eq.  │
│   ● Buscar en el material           24 documentos descargados             │
│   • Studying without AI             el material en este dispositivo       │
│                                                                            │
│ [ Reintentar servidor ]  [ Chatear con mi modelo ]                        │
│ Volvemos al servidor solos en cuanto responda.                             │
└────────────────────────────────────────────────────────────────────────────┘
```

**Puede hacer**: enviar mensajes (en `LOCAL` u `OFFLINE`), buscar en el
material, seguir leyendo los hilos anteriores, exportar.
**Se le ofrece**: usar su modelo local (si lo tiene), o el material local.

#### B. La key venció o fue rechazada (`AuthFailed`, HTTP 401/403)

**Ve**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 🔑 La clave de la cátedra fue rechazada                                   │
│                                                                            │
│ El servidor respondió "no autorizado". Lo más probable es que la clave de  │
│ la cátedra haya vencido (las claves vencen al terminar la cursada).       │
│                                                                            │
│ Podés:                                                                     │
│   ● Avisarle a tu docente (te copiamos el texto)                          │
│   ● Chatear con tu modelo local                                           │
│   ● Seguir con el material de la cátedra                                  │
│                                                                            │
│ [ Copiar el mensaje para el docente ]  [ Chatear con mi modelo ]          │
└────────────────────────────────────────────────────────────────────────────┘
```

El texto que se copia:

> Hola, la clave de API de la cátedra **IA-2026-1** fue rechazada por el
> servidor (HTTP 401). ¿Podés revisarla o emitirme una nueva? Gracias.

**Puede hacer**: todo lo del caso A, y pedir la key nueva.
**Se le ofrece**: copiar el mensaje para el docente (un clic, sin salir de la
app) y arrancar el modelo local.

> **Nota de diseño**: la app **no** afirma "tu key venció". Afirma lo que sabe
> ("fue rechazada") y ofrece la causa más probable ("puede haber vencido").
> El docente puede haber rotado la key, o el servidor puede estar mal
> configurado. Decir "venció" sería inventar.

#### C. Sin red (`NetworkStatus.OFFLINE`)

**Ve**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 📡 Estás sin conexión                                                     │
│                                                                            │
│ El material de IA-2026-1 que tenías descargado sigue disponible: 24        │
│ documentos, 1.412 fragmentos.                                              │
│                                                                            │
│ La IA necesita conexión (salvo que instales el modelo local en el          │
│ dispositivo).                                                              │
│                                                                            │
│ [ Buscar en el material ]  [ Ver el material ]                             │
└────────────────────────────────────────────────────────────────────────────┘
```

**Puede hacer**: buscar en el corpus local, leer los materiales, escribir
apuntes, exportar chats.
**Se le ofrece**: instalar el modelo local (que es justamente el modo que
funciona sin red).

> La app **no** deshabilita el campo de escribir en este caso: el alumno
> puede estar redactando la pregunta y la red puede volver. Deshabilitarlo
> impide recuperar el trabajo.

#### D. El modelo local no está compilado / no está descargado

**Ve** (dentro del panel de IA local, no como error del chat):

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 🤖 IA local                                                               │
│                                                                            │
│ Estado: no está lista                                                     │
│   ● Modelo      Phi-4-mini-instruct Q4_K_M   (no descargado)  2,5 GB      │
│   ● Motor       llama-server                 (no compilado)                │
│                                                                            │
│ Para usarla hace falta:                                                   │
│   1. Descargar el modelo (2,5 GB) — te va a ocupar disco y red            │
│   2. Tener un llama-server: Kitoru puede compilarlo (necesitás git y     │
│      cmake, unos 10 minutos) o indicá la ruta de uno que ya tengas.       │
│                                                                            │
│ [ Instalar ahora ]  [ Buscar un llama-server instalado ]                   │
└────────────────────────────────────────────────────────────────────────────┘
```

Y en el chat, el fallback sigue su curso: si el modelo local no está, se cae a
`OFFLINE` y se muestra el aviso del caso A.

### 1.6 El modo `OFFLINE`: estudiar sin IA

Es el tercer estado y el que hace verdadera la promesa. No es un error: es un
**determinista** (regla 3). El alumno escribe su pregunta y la app responde con
**fragmentos del material de la cátedra**, sin generar nada.

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Pregunta: ¿qué dice el teorema de Bayes aplicado al material?            │
│                                                                            │
│  📄 Extracto del material (sin IA)                                        │
│  ─────────────────────────────────────────────────────────────            │
│  [1] Unidad 2 — Probabilidad (cátedra: Unidad 2.pdf)                      │
│      …el teorema de Bayes se enuncia como P(A|B) = P(B|A)·P(A) / P(B)…     │
│  [2] Práctica 1 — (cátedra: Práctica 1.pdf)                               │
│      …un ejemplo del material: se calcula la probabilidad posterior…       │
│  ─────────────────────────────────────────────────────────────            │
│  Esto es texto extraído de tu material, no una respuesta generada.        │
│  [ Copiar ]  [ Abrir el material ]  [ Ver los 5 fragmentos ]              │
└────────────────────────────────────────────────────────────────────────────┘
```

Implementación (backend, `rag/engine/OfflineAssistant.kt`):

```kotlin
/**
 * Responde sin IA: devuelve los fragmentos del corpus local que mejor
 * matchean la pregunta.
 *
 * Es determinista a propósito (regla 3): el mismo corpus y la misma pregunta
 * dan siempre la misma respuesta, y nunca inventa nada. La UI lo etiqueta
 * como "extracto del material", no como respuesta del modelo.
 */
class OfflineAssistant(private val kb: KnowledgeBase, private val topK: Int = 5) {
    fun answer(question: String): OfflineAnswer
}

data class OfflineAnswer(
    val question: String,
    val sources: List<Chunk>,
    /** Texto listo para mostrar (los fragmentos con su etiqueta). */
    val text: String,
)
```

El texto se arma con los mismos `Chunk.label` que usa el RAG con IA, para que
el alumno no tenga que aprender dos formatos de cita.

**Reglas**:
- Si no hay fragmentos: *"No encontré nada sobre esto en el material
  descargado."* + botón "Actualizar material" (puede que el docente haya
  publicado algo nuevo).
- Nunca se redacta una respuesta: sólo se muestran fragmentos literales.

### 1.7 La máquina de estados en la UI

Archivo nuevo: `ui/src/commonMain/kotlin/kitoru/ui/student/AvailabilityBanner.kt`

```kotlin
/** Estado de la UI del alumno, derivado de la disponibilidad. */
data class AvailabilityUiState(
    val origin: InferenceOrigin,
    val reason: FallbackReason,
    /** Banner visible con el motivo ("" = nada que avisar). */
    val banner: String,
    val canRetryRemote: Boolean,
    val canOfferLocalSetup: Boolean,
    /** El chat se puede enviar (en OFFLINE también: contesta el asistente offline). */
    val canSend: Boolean = true,
)
```

El banner es un `Surface` con `tertiaryContainer` (ámbar) cuando es un
incidente y con `secondaryContainer` cuando es informativo (modo local
permanente). **Nunca** es un modal: el alumno tiene que poder seguir
trabajando mientras lo lee.

---

## 2. El modelo local en escritorio

### 2.1 Estado actual y sus problemas

`llm-api/src/desktopMain/kotlin/llm/api/LocalModelService.desktop.kt` (hoy):

- arranca `llama-server` **sólo si el binario ya está** (no clona, no compila,
  no descarga el GGUF);
- usa el puerto **fijo** de la config → si está ocupado, `llama-server` muere y
  el error dice "llama-server no respondió a tiempo. Revisá el log del
  proceso." — **pero no hay log**: el `redirectErrorStream(true)` manda la
  salida a la consola y se pierde;
- `findModel` acepta **cualquier** `.gguf` de la carpeta: puede arrancar con un
  modelo distinto del configurado sin avisar;
- `stop()` no mata el árbol de procesos: en Windows quedan hijos colgados
  ocupando el puerto;
- no hay *shutdown hook*: cerrar la app deja el servidor vivo;
- el mensaje de error no dice **qué hacer** ("instalá X", "descargá Y").

### 2.2 Alcance por fases

| Fase | Qué entra | Por qué en esa fase | Esfuerzo |
|---|---|---|---|
| **F0 — hoy** | `LlamaServerLocalModelService`: arrancar si el binario existe. | Ya está. | — |
| **F1 — "se puede usar"** | (a) catálogo de modelos + **descargador reanudable y verificado**; (b) **supervisor** del proceso hijo con log en disco y apagado limpio; (c) **puerto alternativo**; (d) **health check** de dos fases; (e) apagado al cerrar la app. | Es lo que realmente cierra el hueco "el servidor no anda": el alumno baja un modelo (una vez) y tiene IA propia para siempre. Todo lo demás es decoración. | 8–10 j-h |
| **F2 — "se instala solo"** | **Clonado + compilación** de llama.cpp con cmake (port de `LlamaManager`), **opt-in** del alumno. | Muchos alumnos no tienen `llama-server`; exigirlo a mano rompe la promesa. Pero compilar es un coste (tiempo, disco, CPU) ⇒ lo autoriza el alumno con un botón. | 4–6 j-h |
| **F3 — "se conoce el coste"** | **Lectura de la cabecera GGUF** (RAM real, cuantización), **benchmark** en la máquina del alumno, ficha de coste, panel de "liberar espacio". | Sin esto el alumno no puede decidir si le conviene; y la app no puede estimar tokens/s sin medir. | 4–5 j-h |

Las fases son **independientes**: F1+F3 son el hito "IA local usable"; F2 es un
extra que se puede posponer; Android (§3) es otro eje.

### 2.3 Fase 1 (a) — El descargador reanudable y verificado

**Origen**: `FunshiEngineAI/llm-api/src/main/kotlin/llm/catalog/HuggingFaceDownloader.kt`.
Se **porta** tal cual (misma técnica anti-array-buffer, misma reanudación por
`Range`, mismo lock por archivo) y se le agrega la verificación de integridad.

**Ubicación**: `llm-api/src/jvmCommonMain/kotlin/llm/catalog/HuggingFaceDownloader.kt`
— desktop y Android son los dos JVM, así que el descargador vive en el source
set compartido por los dos (regla 2: nada de `java.nio` en `commonMain`).

**Cambio de plataforma necesario**: `kitoru.platform.FileSystem` hoy sólo tiene
`readText`/`writeText`, insuficiente para archivos de 2,5 GB (no se puede
cargar en memoria). Se agrega una API de escritura en streaming:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt
interface FileSystem {
    // ... las que ya están ...
    /**
     * Abre el archivo para escribir bytes de a chunks.
     * `append = true` continúa un archivo a medias (reanudación).
     */
    fun openWriter(path: FilePath, append: Boolean = false): FileWriter
}

/** Escritor de bytes en streaming (nunca acumula el archivo en memoria). */
interface FileWriter {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun close()
}
```

`actual` en `core/src/jvmCommonMain/.../FileSystem.jvm.kt` sobre
`java.io.FileOutputStream` (disponible en desktop y Android).

**Firma del descargador (portada + endurecida)**:

```kotlin
class HuggingFaceDownloader(private val token: String = "") {

    /**
     * Se asegura de que el modelo esté descargado en [dir].
     *
     * Reanuda desde el `.part` con `Range: bytes=<ya-descargado>-`, verifica
     * el `Content-Length`, el **sha256** declarado por Hugging Face y el magic
     * `GGUF`, y recién entonces renombra el `.part` al nombre final.
     */
    suspend fun ensureModel(
        repo: String,
        file: String,
        dir: FilePath,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): FilePath

    /** Lista los `.gguf` de un repo con su tamaño y su sha256. */
    suspend fun listGgufFiles(repo: String): List<HfGgufFile>

    companion object {
        /** Locks por ruta absoluta, compartidos entre instancias. */
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}

@Serializable
data class HfGgufFile(
    /** Ruta completa dentro del repo ("subcarpeta/archivo.gguf"). */
    val name: String,
    val size: Long = 0L,
    /** sha256 del archivo (viene de `lfs.oid` en la API de HF). */
    val sha256: String = "",
) {
    val display: String get() = name.substringAfterLast("/")
}
```

**Las cinco verificaciones, en orden** (todas con mensaje en español):

| # | Verificación | Qué detecta | Qué hace si falla |
|---|---|---|---|
| 1 | `Content-Length` vs bytes leídos | Red cortada a mitad | `LlmException(DESCARGA_FALLIDA, retryable = true)`; el `.part` queda para reanudar |
| 2 | HTTP `416` al reanudar | El `.part` ya estaba completo | Se da por bueno (mismo comportamiento que FunshiEngineAI) |
| 3 | El servidor ignoró el `Range` (responde `200` en vez de `206`) | Proxy que no soporta rangos | Se **trunca** el `.part` y se baja de cero (si no, se concatenaría basura) |
| 4 | **sha256** del archivo contra `lfs.oid` de HF | Archivo corrupto o alterado | Se borra el `.part` y se reintenta una vez; si vuelve a fallar, error con el hash esperado y el obtenido |
| 5 | Magic `GGUF` (`47 47 55 46`) en los primeros 4 bytes | Se guardó una página de error HTML como `.gguf` | Error: "El archivo descargado no es un modelo GGUF (empezaba por «<!DO»). Volvé a intentarlo." |

> El punto 4 y el 5 son **nuevos** respecto de FunshiEngineAI. El 5 es
> barato (4 bytes) y evita el clásico "el modelo anda rarísimo" caused por un
> HTML guardado con extensión `.gguf`.

**El sha256 se calcula en streaming**, leyendo el archivo a trozos de 1 MB con
el `FileSystem` portable: nunca se carga el modelo en memoria.

**Cancelación y reanudación**: si el alumno cancela la descarga (o cierra la
app), el `.part` queda en `models/<archivo>.gguf.part` y la próxima vez se
continúa desde ahí. El `.part` se muestra en la UI como "descarga a medias:
1,2 GB de 2,5 GB" (mismo patrón que `ModelEntry.partBytes` de FunshiEngineAI).

### 2.4 Fase 1 (b) — El supervisor del proceso hijo

**Origen**: `FunshiEngineAI/llm-api/src/main/kotlin/llm/local/LocalServerManager.kt`.

**Ubicación**: `llm-api/src/desktopMain/kotlin/llm/local/LocalServerSupervisor.kt`.

```kotlin
/**
 * Levanta y baja `llama-server` (llama.cpp) en localhost.
 *
 * Es un **singleton por instalación**: varios chats comparten el mismo motor
 * (arrancar dos llama-server con el mismo modelo desperdicia 3 GB de RAM).
 */
object LocalServerSupervisor {

    /** Puerto realmente usado (puede diferir del configurado si estaba ocupado). */
    val boundPort: Int

    /** Arranca (o reutiliza) el motor. Idempotente con el mismo modelo. */
    suspend fun start(
        model: FilePath,
        settings: LocalModelSettings,
        onProgress: (SetupProgress) -> Unit,
    ): LlmGateway

    /** Cierra el motor (apagado limpio: 3 s de gracia y matar el árbol). */
    fun stop()
}
```

Diferencias con el `LocalServerManager` de FunshiEngineAI:

| Aspecto | FunshiEngineAI | Kitoru |
|---|---|---|
| Alcance | Una instancia por app de escritorio | **Singleton** compartido por todos los chats |
| Reutilización | Si el puerto ya está sano, lo reutiliza | Idéntico, **pero** verifica que sea **nuestro** y que sirva **el mismo modelo** (`healthyFor(model)`) |
| Puerto | Escanea 20 puertos | Igual (`findFreePort(8081, 20)`), y **avisa**: "El puerto 8081 está ocupado; usando el 8082" |
| Log | `logs/llama-server.log` (en el `app-home`) | `KitoruPaths.dataDir/logs/llama-server.log` (por instalación) |
| Errores | Incluye el *tail* del log | Igual (el tail es lo que convierte "murió" en "murió porque no encuentra la GPU") |
| Apagado | `destroy()` + `taskkill /T /F` en Windows | Igual, **y** además `Runtime.addShutdownHook` para que cerrar la app no deje el puerto tomado |
| Progreso | Porcentaje parseado del log | Igual (`logPercent`), con la etapa nueva `SetupStage.START` |

Comando exacto (el de FunshiEngineAI, con un ajuste):

```
llama-server -m <modelo.gguf> --host 127.0.0.1 --port <puerto> \
             -c <contexto> -ngl <capasGPU> --jinja
```

> `--jinja` importa: es lo que habilita el **template de chat del propio
> modelo** y, con él, las llamadas a herramientas con el esquema OpenAI. Sin
> `--jinja`, el `AgentLoop` tiene que caer al modo por prompt.

### 2.5 Fase 1 (c) — El puerto alternativo

Port directo de `LocalServerManager.findFreePort(start, span)`:

```kotlin
/** Primer puerto libre en [start, start+span) o null. */
fun findFreePort(start: Int, span: Int = 20): Int?
```

Reglas:

- Se prueba el puerto configurado y los 19 siguientes.
- Si el usado ≠ el configurado, el arranque **avisa**:
  *"El puerto 8081 está ocupado; usando el 8082."* (no es un error).
- El puerto real se guarda en `LocalServerSupervisor.boundPort` y es el que
  se pone en la URL del gateway (`http://127.0.0.1:<puerto>/v1`).
- Caso borde: si los 20 están ocupados → `LlmException(SERVIDOR_CAIDO,
  "No hay puertos libres entre 8081 y 8100.", retryable = false)`.

> Por qué importa: la app deja el servidor vivo al cerrar (para no perder la
> descarga de 3 GB) y el alumno puede tener otro `llama-server` propio en el
> 8081. Sin escaneo, el arranque falla con un error de socket ininteligible.

### 2.6 Fase 1 (d) — El health check de dos fases

El health check actual (`gateway.listModels()` en un bucle de 120 × 500 ms)
tiene dos problemas: (1) no distingue "cargando" de "murió"; (2) no verifica
que el servidor sea el nuestro.

Diseño (port de `LocalServerManager.healthy` / `healthyFor`):

```
Fase 1 — ¿vivo?         GET /health        → 200 = sano; 503 = todavía carga
Fase 2 — ¿sirve mi modelo?  GET /v1/models  → 200 y el cuerpo contiene el nombre del .gguf
```

Algoritmo del arranque:

```
1. Si ya hay un motor sano en el puerto Y sirve el mismo modelo → reutilizar.
2. Si hay un motor sano en el puerto pero es el de OTRO modelo → pararlo (es
   nuestro) y arrancar el nuevo.
3. Buscar un puerto libre.
4. Lanzar el proceso con el log a `logs/llama-server.log`.
5. Loop hasta `loadTimeoutMs` (180 s por defecto):
     - si el proceso murió → LlmException(SERVIDOR_CAIDO) con el TAIL del log.
     - si /health == 200 y /v1/models contiene el modelo → READY.
     - si no → reportar el % de carga parseado del log ("Cargando modelo en
       memoria… 45 %").
6. Si se agota el tiempo → LlmException(MODELO_NO_DISPONIBLE, retryable = true)
   con el tail del log.
```

El tail del log es la parte que hace el diagnóstico posible:

```
llama-server terminó (código 1). Log (llama-server.log):
  llama_model_load: error loading model: failed to load model from <ruta>
  common_init_from_params: failed to initialize the model
```

### 2.7 Fase 2 — Compilar llama.cpp (opt-in)

**Origen**: `FunshiEngineAI/llm-api/src/main/kotlin/llm/local/LlamaManager.kt`.

**Ubicación**: `llm-api/src/desktopMain/kotlin/llm/local/LlamaBuilder.kt`.

Se portan **todos** los detalles que hacen que funcione:

| Detalle de FunshiEngineAI | Se mantiene | Por qué |
|---|---|---|
| `git clone --progress --depth 1` con el % parseado del stderr | ✔ | Deep clone de llama.cpp son varios GB |
| Reintento sin `--depth 1` si el shallow falla | ✔ | Algunos proxies/mirrores no soportan shallow |
| `verifyCloneComplete` (`.git` + `CMakeLists.txt` + `vendor/`) antes de compilar | ✔ | Un clon a medias hacía fallar el cmake con un error críptico |
| `requireTool("git"/"cmake", hint)` **antes** de clonar | ✔ | Fallar en 1 s en vez de descargar 500 MB para nada |
| `cmake -S . -B build -DCMAKE_BUILD_TYPE=Release` + `cmake --build build --config Release -j N` | ✔ | |
| Candidatos del binario: `build/bin/`, `build/bin/Release/`, `build/bin/Debug/`, `build/` | ✔ | El generador MSVC deja el `.exe` en `build/bin/Release/` |
| Timeout de clon 10 min / de build 20 min, con log a `logs/llama-build.log` | ✔ | |
| Mutex por directorio de repo (`ConcurrentHashMap<String, Mutex>`) | ✔ | Dos chats que arranquen a la vez no pueden compilar en paralelo |

**Adaptaciones en Kitoru**:

| Aspecto | Kitoru |
|---|---|
| Dónde vive el clon | `KitoruPaths.dataDir/llama.cpp` (por instalación, sobrevive a las actualizaciones de la app) |
| Quién lo dispara | **El alumno**, con un botón: "Compilar llama-server (10 min, 1,2 GB)". **Nunca** automáticamente al abrir un chat. |
| Requisitos | Se verifican **antes**: "Necesitás Git 2.30+ y CMake 3.22+ (o Visual Studio Build Tools). Faltan: git." |
| Versión fijada | `llamaRepoVersion` configurable; por defecto se fija una **tag** conocida (no `HEAD`), para que el build sea reproducible: *"Se va a instalar llama.cpp b4XXX. Si tenés una versión distinta, indicá la ruta."* |
| Instalaciones gestionadas | Si `AppSettings.managed == true`, el build y la descarga se bloquean (capacidad `APP_LOCAL_MODEL_MANAGE` condicional). |
| Espacio | Se avisa del tamaño: el clon + los artefactos de build ocupan ~1,2 GB. Se puede borrar después con "Liberar espacio". |

### 2.8 Fase 3 — Saber el coste (lectura del GGUF + benchmark)

Ver §4 con detalle. Lo que se necesita de F3 en el escritorio:

- **Lectura de la cabecera GGUF** (portable, `commonMain`): el formato GGUF es
  un header con pares clave/valor; de ahí salen el número de capas, el tamaño
  de embedding, las cabezas KV y el tipo de cuantización. Con eso la app
  calcula la **RAM real** en vez de estimarla.
- **Benchmark**: se mide una generación real (o `llama-bench` si está
  compilado) y se guarda `measuredTokensPerSecond` **en la máquina del alumno**.

### 2.9 El modelo local como estado de dominio

El `ModelPolicy` del docente define la **base**. El alumno tiene su propia
instalación, que es otra cosa: un `LocalModelSetup` propio.

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/LocalModelSetup.kt`

```kotlin
/**
 * La instalación local del alumno: qué modelo tiene, dónde, cómo lo corre.
 *
 * Es del **alumno**, no de la cátedra: vive en `config/local-models.json` y
 * sobrevive a que el docente cambie la política del curso.
 */
@Serializable
data class LocalModelSetup(
    /** Repo de Hugging Face del GGUF. */
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    /** Archivo GGUF dentro del repo. */
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    /** Carpeta donde vive el modelo (relativa a `KitoruPaths.dataDir`). */
    val modelsDir: String = "models",
    /** Ruta del binario `llama-server` (vacío = usar el clonado/compilado). */
    val llamaServerPath: String = "",
    /** Puerto local preferido. */
    val localPort: Int = 8081,
    /** Tamaño de contexto. */
    val contextSize: Int = 8192,
    /** Capas en GPU (0 = sólo CPU). */
    val gpuLayers: Int = 0,

    /** El alumno autorizó clonar/compilar llama.cpp. */
    val buildFromSource: Boolean = false,
    /** Repo git de llama.cpp. */
    val llamaRepoUrl: String = "https://github.com/ggml-org/llama.cpp.git",
    /** Tag/branch a compilar (vacío = HEAD). */
    val llamaRepoVersion: String = "b4585",

    /** Estado de la instalación (lo escribe el servicio, no el usuario). */
    val installState: LocalInstallState = LocalInstallState.NONE,
    /** Velocidad medida en ESTA máquina (0 = sin medir). */
    val measuredTokensPerSecond: Double = 0.0,
    /** Cuándo se midió (epoch ms). */
    val measuredAt: Long = 0L,
) {
    /** Merge con la base que propone la cátedra (lo del alumno manda). */
    fun effective(base: LocalModelSetup): LocalModelSetup =
        if (this == LocalModelSetup()) base else this
}

@Serializable
enum class LocalInstallState {
    /** Nada instalado. */
    NONE,

    /** Descargando el modelo. */
    DOWNLOADING,

    /** El modelo está, falta el motor. */
    NEEDS_ENGINE,

    /** Listo. */
    READY,

    /** Falló (el mensaje está en el log de la app). */
    FAILED,
}
```

Y el **catálogo** de modelos que la app ofrece (port de
`ModelManager`/`defaultModelCatalog` de FunshiEngineAI):

```kotlin
@Serializable
data class ModelCatalogEntry(
    val id: String,
    val label: String,
    val repo: String,
    val file: String,
    /** Tamaño en bytes (0 = desconocido hasta listar el repo). */
    val sizeBytes: Long = 0L,
    /** sha256 declarado por HF ("" = desconocido). */
    val sha256: String = "",
    /** Descripción de una línea para la UI. */
    val description: String = "",
    /** Recomendado para teléfono (sólo en Android). */
    val mobileFriendly: Boolean = false,
)
```

Catálogo inicial (el de FunshiEngineAI, con el flag de móvil):

| id | Etiqueta | Repo / archivo | Tamaño | ¿Móvil? |
|---|---|---|---|---|
| `phi-4-mini` | Phi-4-mini-instruct (Q4_K_M) | `unsloth/Phi-4-mini-instruct-GGUF` / `Phi-4-mini-instruct-Q4_K_M.gguf` | 2,5 GB | no (2,5 GB de RAM) |
| `qwen3-4b` | Qwen3-4B-instruct (Q4_K_M) | `unsloth/Qwen3-4B-Instruct-2507-GGUF` / `Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2,6 GB | no |
| `llama-3.2-3b` | Llama-3.2-3B-instruct (Q4_K_M) | `unsloth/Llama-3.2-3B-Instruct-GGUF` / `Llama-3.2-3B-Instruct-Q4_K_M.gguf` | 2,0 GB | sí |
| `gemma-2-2b` | Gemma 2 2B-instruct (Q4_K_M) | `bartowski/gemma-2-2b-it-GGUF` / `gemma-2-2b-it-Q4_K_M.gguf` | 1,6 GB | **sí (el del teléfono)** |

### 2.10 La interfaz `LocalModelService` (rediseñada)

Hoy la interfaz es `start(settings) → LlmGateway` / `stop()`. Con varios chats
compartiendo un motor y con el aprovisionamiento por etapas, la interfaz tiene
que decir más:

Archivo: `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` (modificado)

```kotlin
/** Etapa del aprovisionamiento del modelo local. */
enum class SetupStage {
    /** Chequeando qué falta. */
    CHECK,

    /** Descargando el GGUF. */
    DOWNLOAD,

    /** Verificando la integridad (sha256 + magic GGUF). */
    VERIFY,

    /** Compilando llama.cpp. */
    BUILD,

    /** Arrancando el motor y cargando el modelo. */
    START,

    /** Listo. */
    READY,
}

/** Progreso del aprovisionamiento (una línea para la UI + barra). */
data class SetupProgress(
    val stage: SetupStage,
    /** 0..100 o null si es indeterminado. */
    val percent: Int?,
    val message: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
)

interface LocalModelService {

    /** Estado vivo del motor local (la UI lo observa). */
    val status: StateFlow<LocalStatus>

    /**
     * Deja el motor listo y devuelve el gateway.
     *
     * Es idempotente: si ya hay un motor sano con el mismo modelo, lo
     * reutiliza (no vuelve a pagar la carga de 3 GB en RAM).
     */
    suspend fun acquire(
        settings: LocalModelSettings,
        onProgress: (SetupProgress) -> Unit = {},
    ): LlmGateway

    /** Avisa que un chat dejó de usar el motor (no lo apaga: puede haber otro). */
    fun release()

    /** Apaga el motor (lo usa la app al cerrar). */
    fun shutdown()
}
```

`LocalModelSettings` (`:llm-api`) se extiende con lo que necesitan las fases:

```kotlin
@Serializable
data class LocalModelSettings(
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    val llamaServerPath: String = "",
    val localPort: Int = 8081,
    val contextSize: Int = 8192,
    val gpuLayers: Int = 0,
    val modelsDir: String = "models",
    // --- nuevos ---
    /** Token de Hugging Face (opcional, para repos privados o cuotas). */
    val hfToken: String = "",
    /** Autorizar clonar/compilar llama.cpp. */
    val buildFromSource: Boolean = false,
    val llamaRepoUrl: String = "https://github.com/ggml-org/llama.cpp.git",
    val llamaRepoVersion: String = "",
    /** Espera máxima a que el motor esté sano (ms). */
    val loadTimeoutMs: Long = 180_000,
)
```

---

## 3. El modelo local en Android

### 3.1 Opciones evaluadas

#### (a) `llama.cpp` vía JNI

**Qué existe ya**: el repo de llama.cpp trae `examples/llama.android`, y en
`FunshiEngineAI/app-home/llama.cpp/examples/llama.android` está el clon con:

```
lib/src/main/cpp/ai_chat.cpp                       ← JNI
lib/src/main/java/com/arm/aichat/AiChat.kt         ← singleton
lib/src/main/java/com/arm/aichat/InferenceEngine.kt ← interfaz
lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt
```

Los métodos nativos disponibles (de `InferenceEngineImpl.kt`):

| Nativo | Firma | Qué hace |
|---|---|---|
| `init` | `(nativeLibDir: String)` | Carga la lib nativa y el allocator |
| `load` | `(modelPath: String): Int` | Carga el GGUF (`llama_model_load`) |
| `prepare` | `(): Int` | Crea el contexto (`llama_new_context_with_model`) |
| `systemInfo` | `(): String` | Info del dispositivo y del backend |
| `benchModel` | `(pp, tg, pl, nr): String` | **Benchmark** (¡justo lo que necesita F3!) |
| `processSystemPrompt` | `(prompt: String): Int` | Tokeniza el prompt de sistema |
| `processUserPrompt` | `(prompt, predictLength): Int` | Tokeniza la pregunta |
| `generateNextToken` | `(): String?` | **Un token por llamada** → base del streaming |
| `unload` / `shutdown` | `()` | Libera el modelo / la lib |

Dos detalles decisivos:

1. **El chat template es el del modelo**: `ai_chat.cpp` usa
   `common_chat_templates_init` + `common_chat_templates_apply`, o sea el
   mismo motor de plantillas (Jinja) que usa `llama-server` con `--jinja`. El
   modelo "habla" igual que en el escritorio.
2. **`benchModel` ya existe**: el benchmark de F3 sale de ahí, sin escribirlo.

**Lo que falta para Kitoru**:

- Un módulo Android `:llama-jni` (o un `aar` con las libs nativas precompiladas)
  con la `ai_chat.cpp` portada y `minSdk = 24` (el ejemplo de Arm pide 33;
  llama.cpp compila con API 21+, así que es cuestión de bajar el `minSdk` y
  revisar la API usada).
- ABIs: el ejemplo construye `arm64-v8a` y `x86_64`. Falta `armeabi-v7a`
  (teléfonos baratos) — decisión: **arm64-v8a + x86_64** en la v1;
  `armeabi-v7a` cuando el pipeline de build esté automatizado.
- **Adaptador `LlmGateway`**: `LlamaJniGateway` que arma el prompt con el
  historial, llama a `processUserPrompt` y emite `LlmChunk.Text` token a token
  con `generateNextToken()`, respetando la cancelación.
- **Tool calls**: la JNI no expone el parser de tool calls de llama-server. El
  modo local en Android correrá el `AgentLoop` en **modo por prompt**
  (`nativeToolCalls = false`), que el `AgentLoop` portado ya soporta.
- La JNI **no** expone `llama-server` como HTTP: el gateway local de Android
  **no** es un `OpenAiCompatibleGateway` sino un `LlmGateway` propio. El
  contrato `LlmGateway` es la costura; por eso la interfaz no puede depender
  de HTTP.

**Esfuerzo**: 12–18 j-h (build NDK + CI de las 2 ABIs + `LlamaJniGateway` +
tests en emulador + manejo de memoria y cancelación).

#### (b) MediaPipe LLM Inference (Google)

**Qué es**: `com.google.mediapipe:tasks-genai`, API de Kotlin:

```kotlin
val options = LlmInferenceOptions.builder()
    .setModelPath(rutaTask)          // archivo .task / .bin
    .setMaxTokens(2048)
    .setPreferredBackend(LlmInferenceBackend.GPU)  // o CPU
    .build()
val inference = LlmInference.createFromOptions(context, options)
val session = inference.createSession()
val response = session.generateResponse(prompt)    // String completo
```

**A favor**: Kotlin puro, sin NDK, `minSdk 24`, delegate de GPU, modelos
optimizados (`.task`) de Gemma 2B/3B, Phi-3.5-mini, Qwen2.5.

**En contra**:

- **No es GGUF**: otro catálogo, otro descargador (los `.task` no se sirve
  desde la API de blobs de HF igual de simple), otro verificador de integridad.
- **No hay tool calls** y el prompt lo arma la app (no hay plantilla de chat
  del modelo): hay que construir el prompt a mano.
- `generateResponse` devuelve la respuesta **completa**: no hay streaming
  token a token (hay `generateResponseAsync`, pero no por token). El chat
  pierde el efecto de "se escribe mientras genera".
- Los modelos `.task` de Google son de licencia más restrictiva y menos
  Choices que GGUF.

**Esfuerzo**: 6–10 j-h (integración + prompts + modelo + UI).

#### (c) Motor ejecutable propio empaquetado

Un `llama-server` (o un motor propio en C++) empaquetado dentro del APK **no
funciona en Android**: es un binario ELF linkeado contra glibc; Android usa
bionic y no puede ejecutar un `.so` de escritorio como proceso. Es la razón por
la que el camino de Android es siempre "librería nativa embebida" y no
"proceso hijo".

**Veredicto**: descartada. Es el error conceptual que el diseño del escritorio
invita a cometer.

#### (d) Motor remoto propio

Un gateway nuestro que sirva inferencia en el campus. Es el proyecto
"Kitoru-Server" (repo hermano) y **contradice el objetivo**: la promesa es no
depender del servidor. Fuera de alcance.

### 3.2 Veredicto

| Opción | Esfuerzo | Viabilidad | Veredicto |
|---|---|---|---|
| (a) llama.cpp JNI | 12–18 j-h + pipeline NDK | Alta | **Objetivo final**: unifica el catálogo GGUF con el escritorio, mismo chat template, `benchModel` incluido |
| (b) MediaPipe | 6–10 j-h | Alta | **Puente (Hito A)**: cierra el hueco urgente en 1 semana, detrás de la misma interfaz `LocalModelService` |
| (c) ejecutable propio | — | Nula en Android | Descartada |
| (d) motor remoto propio | 40+ j-h | — | Fuera de alcance |

**Plan**:

```
Hito A (1 semana)   →  MediaPipe en Android + corpus local + modo OFFLINE.
                       Cierra el caso "el servidor de la cátedra no anda" en el
                       teléfono. Es el mínimo honesto.

Hito B (2–3 semanas) →  llama.cpp JNI en Android, catálogo GGUF unificado con
                       el escritorio, Phi-4-mini y Gemma 2 2B en el teléfono.

Hito C (desktop)    →  Fases 1 y 3 de §2 (descarga verificada, supervisor,
                       medida del coste). Es el camino que hoy no existe.
```

Los dos motores de Android conviven detrás de `LocalModelService`:
`MediaPipeLocalModelService` y `LlamaJniLocalModelService`. El selector de
motor es una preferencia del alumno en el panel de IA local
("Motor: MediaPipe / llama.cpp"), con la regla: si el modelo elegido es
`.task` → MediaPipe; si es `.gguf` → llama.cpp JNI. **La app no adivina**:
el motor se deduce del formato del modelo.

### 3.3 Qué modelo usaría un teléfono

| Motor | Modelo | Tamaño | RAM (con contexto 4k) | Velocidad ( Snapdragon 8 Gen 1 / gama media) | Español |
|---|---|---|---|---|---|
| llama.cpp JNI | **Gemma 2 2B Instruct Q4_K_M** | 1,6 GB | ~2,0 GB | 12–18 tok/s / 6–9 tok/s | bueno |
| llama.cpp JNI | Phi-4-mini Q4_K_M | 2,5 GB | ~3,1 GB | 6–10 tok/s / 3–5 tok/s | **muy bueno** |
| MediaPipe | `gemma-2b-it-cpu-int4.task` | ~1,5 GB | ~2,0 GB | 10–16 tok/s (GPU delegate) / 5–8 tok/s | bueno |
| MediaPipe | `phi-3.5-mini-e2e-it-int4.task` | ~2,3 GB | ~2,8 GB | 6–10 tok/s | muy bueno |

**Decisión**:

- **Por defecto en el teléfono: Gemma 2 2B Instruct Q4_K_M (1,6 GB).** Es el
  punto dulce del teléfono: entra en la RAM de un gama media de 2023 sin
  matar la batería, y responde a 12–18 tok/s en un flagship (que se lee como
  "una persona escribiendo rápido"). El español de Gemma 2 2B es correcto
  aunque menos idiomático que el de Phi-4-mini: la app lo dice en la ficha
  ("mejor para explica; el español es correcto pero menos natural que en
  Phi-4-mini").
- **Phi-4-mini Q4_K_M** como alternativa "más capaz" para quien tenga un
  teléfono con 8 GB de RAM o más y acepte 3–5 tok/s.
- **El contexto en el teléfono baja a 4096** (de 8192): la KV cache de
  Phi-4-mini a 8k son ~640 MB, que en un teléfono es la diferencia entre
  funcionar y que el sistema mate el proceso.

**Guardas de memoria en Android** (obligatorias):

```kotlin
// Android: leer ActivityManager.MemoryInfo
val mem = activityManager.getMemoryInfo()
val availableGb = mem.availMem / 1024.0 / 1024.0 / 1024.0
```

- Si la RAM disponible < la estimada + 512 MB de margen → **no se arranca**:
  *"No hay memoria suficiente: este modelo necesita ~3,1 GB y tenés 2,4 GB
  libres. Probá con Gemma 2 2B (2,0 GB)."* con un botón que lo cambia.
- Si `mem.lowMemory` → la app ofrece **descargar el material y dejar la IA**
  (el modo `OFFLINE`).
- Si la app pasa a segundo plano durante una generación → el proceso puede
  morir: la UI avisa *"La app se cerró en segundo plano: en Android el sistema
  puede detener las apps en background."* y ofrece dejar el chat guardado.

### 3.4 El gateway embebido (Android)

```kotlin
// llm-api/src/androidMain/kotlin/llm/local/llama/LlamaJniGateway.kt
class LlamaJniGateway(
    private val engine: InferenceEngine,
    private val modelName: String,
) : LlmGateway {

    /**
     * Genera token a token con la JNI de llama.cpp.
     *
     * El prompt se arma con el **historial completo** y con el contexto del
     * RAG (mismo formato que `RagEngine.prepare` produce), para que el modelo
     * local reciba exactamente la misma información que el remoto.
     */
    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        val prompt = PromptBuilder.build(messages, tools)
        engine.processUserPrompt(prompt, predictLength = 1024)
        while (true) {
            val token = engine.generateNextToken() ?: break
            if (token.isEmpty()) continue
            emit(LlmChunk.Text(token))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<String> = listOf(modelName)
}
```

`PromptBuilder` es **común** (`:llm-api/commonMain`): arma el prompt en el
formato que la JNI y MediaPipe esperan. Es el único punto donde el "formato de
prompt" se decide, y es compartible.

> **Nota sobre el RAG local en Android**: el `RagEngine` ya arma los mensajes
> con el contexto (`[1] … [2] …` + "CONTEXTO:/PREGUNTA:"), así que el camino
> es idéntico en local y en remoto: cambia el `LlmGateway`, no el `RagEngine`.

---

## 4. Quién paga el costo

El alumno con IA local consume **batería, disco, RAM y CPU** de su equipo. La
app tiene que decirlo, con números, **antes** de que gaste y **después** de
medir.

### 4.1 Lo que la app puede medir de verdad

| Dato | De dónde sale | Por qué no se estima a ciegas |
|---|---|---|
| Tamaño en disco | Metadatos de HF (`siblings[].size`) | Exacto |
| RAM del modelo | Tamaño del GGUF × 1,08 (overhead de mmap y del runtime) | Exacto |
| RAM de la KV cache | **Cabecera GGUF**: `block_count`, `attention.head_count_kv`, `embedding_length`, `head_count` | Exacto, y es lo que más surprise (640 MB a 8k de contexto) |
| Cuantización | `general.file_type` de la cabecera GGUF | Exacto |
| Velocidad | **Benchmark en la máquina del alumno** (`benchModel` en Android; una generación real en desktop) | Es lo único que no se puede estimar: depende de la CPU, la GPU, la RAM y el modelo |
| Batería | `BatteryManager` en Android (nivel antes/después de una generación) + extrapolación | Aproximado pero **medido** |
| Espacio del corpus | Tamaño de los archivos locales | Exacto |

**Fórmula de la KV cache** (la que se implementa):

```
bytes_por_token = 2 (K y V) × block_count × head_count_kv × (embedding_length / head_count) × 2 (f16)

Phi-4-mini: 2 × 32 × 8 × (2560/32) × 2 = 81.920 bytes/token
            × 8192 tokens = 671 MB  (~0,65 GB)
            × 4096 tokens = 336 MB  (~0,33 GB)
```

### 4.2 La ficha del modelo (lo que ve el alumno)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Phi-4-mini-instruct · Q4_K_M                                             │
│                                                                            │
│ En disco              2,5 GB          (te quedan 118 GB libres)           │
│ Memoria (RAM)         ~3,2 GB          (2,5 del modelo + 0,7 de contexto)  │
│ Velocidad             ~8 tokens/s      (medido en este equipo el 12/03)   │
│ Contexto              8.192 tokens     (≈ 25 páginas del material)         │
│ Cuantización          Q4_K_M           (4 bits)                             │
│                                                                            │
│ ⚠ Mientras responde, el equipo se calienta y consume más batería.        │
│   Con la pantalla apagada: ~4 % de batería por cada 10 minutos de uso      │
│   continuo (estimado a partir de tu última medición).                     │
│                                                                            │
│ [ Descargar (2,5 GB) ]  [ Usar el modelo ]  [ Borrar y liberar 2,5 GB ]    │
└────────────────────────────────────────────────────────────────────────────┘
```

**Textos exactos** (los que se ven en la UI):

| Elemento | Texto |
|---|---|
| Tamaño | "2,5 GB en tu disco (te quedan 118 GB libres)" |
| RAM | "~3,2 GB de memoria mientras responde (2,5 GB del modelo + 0,7 GB de contexto)" |
| Velocidad | "~8 tokens/s en este equipo (medido el 12/03). Una respuesta de 400 tokens tarda ~50 s." |
| Contexto | "8.192 tokens de contexto (≈ 25 páginas del material de la cátedra)" |
| Batería (desktop) | "El equipo va a trabajar a full: se va a calentar y el ventilador va a girar. Cerrá otras tareas pesadas mientras esperás." |
| Batería (Android) | "Va a usar ~4 % de batería por cada 10 minutos de uso continuo. No lo dejes generando con la pantalla apagada." |
| Antes de descargar | "Se van a descargar 2,5 GB. Te quedan 118 GB libres. ¿Seguimos?" |
| Espacio total | "IA local usa 3,7 GB en total (modelo 2,5 GB + compilación de llama.cpp 1,2 GB). Podés borrar la compilación." |

### 4.3 Cuándo se mide

- **La primera vez** que el motor arranca con un modelo: se manda un prompt
  fijo de ~120 tokens y se cronometra la generación → `tokensPerSecond`. Se
  guarda en `LocalModelSetup.measuredTokensPerSecond` con la fecha.
- Si el modelo cambia, se vuelve a medir.
- El usuario puede **re-medir** desde el panel ("Medir de nuevo"), que además
  muestra la comparación con la medición anterior ("antes: 8 tok/s; ahora:
> 6 tok/s — ¿tenés otra cosa corriendo?").
- El benchmark **no** se hace en el arranque normal: es una acción del alumno
  (o se hace en segundo plano, una sola vez, con un aviso).

### 4.4 El coste del *fallback* también se avisa

Cuando el servidor cae y la app pasa al modelo local, el banner incluye el
coste, porque el alumno no pidió la IA local: se la encontró.

```
El servidor de la cátedra no responde. Para no cortar la tarea, seguimos con
tu modelo local: ~8 tokens/s, 3,2 GB de memoria, y consume batería mientras
responde. Este chat no se envía al servidor.
```

### 4.5 Liberar espacio

- "Borrar el modelo" (2,5 GB): deja el modelo local sin usar, **no** borra el
  corpus ni los chats.
- "Borrar la compilación de llama.cpp" (1,2 GB): no afecta al modelo ya
  descargado.
- "Borrar el material descargado" (38 MB): avisa de que el modo `OFFLINE`
  deja de estar disponible.
- Todo esto es `Capability.APP_LOCAL_MODEL_MANAGE` (ver `domain/08-permisos.md`).

---

## 5. La contrapartida de privacidad

### 5.1 El contrato, en una frase

> Si activás la IA local, **nada de lo que escribas ni de lo que responda el
> modelo sale de tu dispositivo**. Tu docente no lo ve. Si lo necesitás, lo
> exportás y lo compartís vos.

Lo que el docente ve en cada modo (coherente con `CONTEXTO.md`):

| Modo | ¿Ve el texto? | ¿Ve métricas? |
|---|---|---|
| REMOTE | Sí, si `Enrollment.visibility.showsText` | Sí, si `showsMetrics` |
| LOCAL | **No** (salvo que el alumno exporte y el docente importe) | Sí, si `showsMetrics` **y** el alumno las habilita (§5.5) |
| OFFLINE | No | No (no hay servidor: no hay a quién mandar las métricas) |

### 5.2 El aviso al elegir el modo local (texto exacto)

Es un momento de confianza: el texto se escribe una vez, se revisa con cuidado y
**no** se muestra nunca más (queda el banner, que es otro mensaje).

```
┌────────────────────────────────────────────────────────────────────────────┐
│  🤖 IA local en tu dispositivo                                            │
│                                                                            │
│  Cuando uses IA local, el modelo corre en esta máquina.                   │
│                                                                            │
│    ✔ Tus preguntas y las respuestas no salen del dispositivo.              │
│      No llegan al servidor de la cátedra.                                 │
│    ✘ Tu docente no puede leer este chat. Si necesitás que lo revise,      │
│      exportalo y compartilo vos.                                          │
│                                                                            │
│  El modelo va a usar 2,5 GB de tu disco y ~3,2 GB de memoria, y va a      │
│  consumir batería mientras responde.                                      │
│                                                                            │
│                        [ Cancelar ]   [ Activar IA local ]                 │
└────────────────────────────────────────────────────────────────────────────┘
```

Variantes del mismo diálogo:

| Situación | Cambio en el texto |
|---|---|
| El modelo ya está descargado | "El modelo (2,5 GB) ya está en tu disco. No vas a descargar nada." |
| No hay suficiente RAM | Se agrega arriba: "⚠ A este equipo le quedan 2,4 GB de memoria libre y el modelo necesita 3,2 GB. Podés elegir uno más liviano." |
| La cátedra no permite IA local | El diálogo no aparece: la opción está deshabilitada con el motivo (§10.2 de `teacher/05-config-modelo.md`) |
| Instalación gestionada por la institución |ídem: "Esta instalación es administrada por la institución: la IA local no está disponible." |

> **Por qué el "✘" y no un texto neutro**: esconder la contrapartida es
> exactamente lo que hace que la promesa de privacidad se quede sin
> valor. El alumno tiene que **ver las dos caras** antes de aceptar.

### 5.3 El banner permanente del chat (texto exacto)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 🤖 IA local · Phi-4-mini (en este dispositivo) · ~8 tokens/s                │
│    Este chat no se envía al servidor: tus docentes no lo ven.              │
│    [ Exportar chat ]   [ ¿Qué ve mi docente? ]                            │
└────────────────────────────────────────────────────────────────────────────┘
```

Clic en **"¿Qué ve mi docente?"** abre un diálogo con el texto exacto de §5.4.

### 5.4 "¿Qué ve mi docente?" (texto exacto)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  ¿Qué ve tu docente?                                                      │
│                                                                            │
│  Con IA local, tu docente ve:                                             │
│    · Que estudiaste: cuántos mensajes mandaste, cuánto tardó el modelo      │
│      y qué material consultaste. Eso sólo si tu inscripción permite ver    │
│      esas estadísticas.                                                    │
│    · Nada de lo que escribiste ni de lo que respondió el modelo.          │
│                                                                            │
│  Tus conversaciones están en este dispositivo. Si querés que tu docente   │
│  las lea, exportá el chat y compartilo vos:                                │
│                                                                            │
│    [ Exportar este chat ]  [ Entendido ]                                  │
│                                                                            │
│  Cuando vuelvas al servidor de la cátedra, los chats que mandes ahí sí    │
│  los ve tu docente, como los de cualquier alumno.                         │
└────────────────────────────────────────────────────────────────────────────┘
```

### 5.5 El aviso del cambio automático (fallback)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  El servidor de la cátedra no responde                                   │
│                                                                            │
│  Para no cortar la tarea, seguimos con tu modelo local (~8 tokens/s,       │
│  3,2 GB de memoria). Este chat no se envía al servidor.                   │
│                                                                            │
│  [ Reintentar servidor ]   [ Exportar este chat ]   [ Volver al material ]│
│  Volvemos al servidor solos en cuanto responda.                            │
└────────────────────────────────────────────────────────────────────────────┘
```

Y el aviso inverso, cuando el servidor vuelve:

```
El servidor de la cátedra volvió a responder. Los próximos mensajes van al
modelo de la cátedra (Qwen3-8B). Este chat sigue siendo privado: lo generaste
con IA local.
```

### 5.6 Exportar el chat (el único camino de salida)

El mecanismo ya está diseñado en `teacher/07-chats.md` §8 (el paquete `.kitoru`
que el docente importa). Lo que se define acá es el **lado del alumno**:

- Botón **"Exportar chat"** en el `⋮` del hilo (mismo que en modo remoto).
- Diálogo previo con el texto exacto:

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Exportar este chat                                                       │
│                                                                            │
│  El archivo incluye todas las preguntas y todas las respuestas de este     │
│  chat. Quien lo reciba —por ejemplo, tu docente— va a poder leerlo.        │
│                                                                            │
│  Formato: [ Markdown ]  [ JSON ]                                          │
│  Destino: la carpeta de exports de Kitoru                                  │
│                                                                            │
│                 [ Cancelar ]   [ Exportar ]                                │
└────────────────────────────────────────────────────────────────────────────┘
```

- El paquete exportado incluye, en el manifiesto, `"mode": "LOCAL"` y el
  nombre del modelo, para que el docente vea que el chat no salió de su
  servidor (importante para la analítica: no se puede atribuir al modelo de
  la cátedra una conversación que generó otro modelo).
- **Auditoría**: la importación por parte del docente queda registrada en el
  `AccessLog` con `kind = IMPORT` (`teacher/07-chats.md` §6).

### 5.7 Las métricas locales (lo único que puede viajar)

En modo local, la app calcula **en el dispositivo** las mismas métricas que
calcula el servidor: cantidad de mensajes, tokens de entrada/salida, latencia,
estadísticas de citas y uso de fuentes.

Reglas:

1. Se quedan en el dispositivo **por defecto**.
2. Se suben **sólo** si (a) la inscripción del alumno tiene
   `visibility.showsMetrics == true` **y** (b) el alumno lo habilitó
   explícitamente ("Enviar mis estadísticas de estudio al servidor de la
   cátedra", switch en el panel de privacidad).
3. **Nunca** se sube texto: ni preguntas, ni respuestas, ni fragmentos del
   material. El payload es un agregado sin contenido.

```kotlin
@Serializable
data class LocalChatMetrics(
    val threadId: String,
    val courseId: String,
    val modelLabel: String,
    val messages: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val citations: CitationStats,
    /** Ids de los materiales consultados (no su contenido). */
    val sourceIds: List<String> = emptyList(),
    val at: Long,
)
```

Si el alumno no habilitó el envío, las métricas se descartan al cerrar el
hilo (y la UI dice: *"Tus estadísticas de estudio se quedaron en este
dispositivo"*).

### 5.8 Lo que ve el docente

En el panel del docente (pantalla de chats), un alumno que usa IA local aparece
así:

```
│ Ana Ruiz          [IA local]  [métricas]  │  (sin hilos en el servidor)
│                                     📥 1 paquete · 13/03
```

Y al hacer clic:

> Este alumno usa IA local: sus conversaciones no llegan al servidor. Acá no
> vas a ver los chats que no comparta. Si comparte alguno, lo importás desde
> "Importar chats".

### 5.9 La lista de lo que **nunca** sale del dispositivo

Una lista explícita, para que el alumno la pueda leer (y para que la app la
pueda auditar):

- El texto de las preguntas del alumno.
- El texto de las respuestas del modelo.
- Los fragmentos del material de la cátedra que se le pasan al modelo.
- El contenido de los chats (sólo salen si el alumno los exporta).
- Los archivos que el modelo crea con herramientas.
- Las claves de API del servidor de la cátedra (no se usan en modo local).

Lo que **sí** puede salir, sólo con consentimiento explícito:

- Las métricas agregadas (§5.7).
- El paquete `.kitoru` que el alumno exportó a mano.

---

## 6. La caché del corpus

### 6.1 El problema

El RAG del chat usa **el material de la cátedra**. En modo remoto, ese material
lo tiene el servidor. En modo local, el modelo corre en el dispositivo del
alumno: si el corpus no está en el dispositivo, el modelo responde sin
contexto de la materia — que es exactamente lo que el alumno no quiere.

Y en modo `OFFLINE` el corpus es **lo único** que hay.

Conclusión: **el corpus es tan parte del modelo local como el modelo**. Sin
corpus local, el modo local no sirve para estudiar.

### 6.2 La garantía: "el mismo material"

> **Invariante I-CORPUS.** Para un material dado y los mismos parámetros de
> troceado, el cliente y el servidor producen **la misma lista de fragmentos**
> (`Chunk.source`, `Chunk.path`, `Chunk.heading`, `Chunk.text`) y, por lo
> tanto, **las mismas citas**.

Cómo se logra:

1. El servidor manda, en el manifiesto, el **`chunkSize` y el `chunkOverlap`
   con los que indexó** (no los que indexó el cliente).
2. El cliente trocea con **el mismo código** (`rag.retrieval.Chunker.split`) y
   los mismos parámetros.
3. El cliente rankea con **el mismo código** (`rag.retrieval.Bm25Index`, con
   los mismos `k1 = 1.5` y `b = 0.75`) y el mismo `topK` (que viene de la
   política de la cátedra).

No hay formato de índice serializado que versionar: el índice se **reconstruye
en memoria** en cada arranque a partir de los textos locales. Con ~2.000
fragmentos por cátedra, reconstruir es de ~50 ms: no vale la pena persistirlo.

### 6.3 El manifiesto y el contrato con el servidor

**Datos** (`:core`):

```kotlin
@Serializable
data class CorpusManifest(
    val courseId: String,
    /** Sube cada vez que el docente publica un cambio del corpus. */
    val revision: Long = 0L,
    /** Tamaño de chunk con el que el servidor indexó (obligatorio). */
    val chunkSize: Int = 1200,
    /** Solapamiento con el que el servidor indexó. */
    val chunkOverlap: Int = 150,
    /** Cuándo se generó el manifiesto. */
    val generatedAt: Long = 0L,
    val entries: List<CorpusEntry> = emptyList(),
) {
    /** Los materiales que el alumno puede tener (los visibles). */
    val visibleEntries: List<CorpusEntry> get() = entries.filter { it.visible }
}

@Serializable
data class CorpusEntry(
    /** Id del material (mismo id que en el dominio `Material`). */
    val id: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /** Tamaño del **texto extraído** (lo que se descarga). */
    val chars: Int = 0,
    /** sha256 del texto extraído: es el diff y la verificación. */
    val sha256: String = "",
    val uploadedAt: Long = 0L,
    /** Si el docente lo muestra a los alumnos. */
    val visible: Boolean = true,
    /** Tamaño del archivo original (para el botón "bajar el PDF"). */
    val originalSizeBytes: Long = 0L,
)
```

**Contrato REST mínimo** (mismo estilo que el de `teacher/05-config-modelo.md`):

```http
GET /api/courses/{courseId}/corpus/manifest
→ 200 {
    "courseId": "IA-2026-1",
    "revision": 7,
    "chunkSize": 1200,
    "chunkOverlap": 150,
    "generatedAt": 1773012345000,
    "entries": [
      { "id": "m-12", "title": "Unidad 2 — Probabilidad.pdf", "kind": "DOCUMENT",
        "chars": 45231, "sha256": "9f2c…", "uploadedAt": 1772900000000,
        "visible": true, "originalSizeBytes": 812345 }
    ]
  }
```

```http
GET /api/courses/{courseId}/corpus/materials/{materialId}
→ 200 application/json  { "id": "m-12", "title": "…", "text": "…", "sha256": "9f2c…" }
```

```http
GET /api/courses/{courseId}/materials/{materialId}/file
→ 200 application/pdf   (el archivo original, opcional y grande)
```

**Errores** (mismo estilo que `LlmErrorMessages`):

| HTTP | Significado | Lo que ve el alumno |
|---|---|---|
| 401/403 | La key no alcanza para este corpus | "No tenés acceso al material de esta cátedra." |
| 404 | La cátedra no existe en el servidor | "Esta cátedra no está en el servidor." |
| 409 | El material cambió mientras se descargaba | Se reintenta una vez con el hash nuevo. |
| 503 | El servidor está indexando | "El servidor está preparando el material. Probá en unos minutos." |

### 6.4 El sincronizador

**Ubicación**: `backend/src/commonMain/kotlin/rag/corpus/CorpusSyncService.kt`.

```kotlin
/**
 * Mantiene la copia local del material de una cátedra.
 *
 * Es idempotente y barata: si la revisión del manifiesto remoto es la misma
 * que la local, no hace **nada** (ni una request de material).
 */
class CorpusSyncService(
    private val repo: CorpusRepository,
    private val remote: CorpusRemote,   // cliente HTTP del manifiesto y los textos
) {

    suspend fun sync(
        courseId: String,
        courseChunkSize: Int,
        onProgress: (CorpusSyncProgress) -> Unit = {},
    ): CorpusSyncResult
}

data class CorpusSyncResult(
    val status: CorpusSyncStatus,   // UP_TO_DATE, SYNCED, OFFLINE, FAILED
    val downloaded: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val bytes: Long = 0L,
    val chunks: Int = 0,
    val message: String = "",
)
```

**Algoritmo**:

```
1. Si no hay red → return OFFLINE (se conserva la copia local, sin tocar nada).
2. GET el manifiesto.
   - Si remote.revision == local.revision → return UP_TO_DATE (0 requests extra).
3. Diff por id:
   - id nuevo                        → descargar texto → verificar sha256 → guardar
   - id presente con hash distinto  → descargar texto → verificar sha256 → reemplazar
   - id local que no está en el remoto → borrar el texto local
   - entrada con visible == false   → borrar el texto local  (D13)
4. Escribir el manifiesto local.
5. Reconstruir el índice local (mismo Chunker + mismo Bm25Index).
6. Devolver SYNCED con los números.
```

**Verificación**: el `sha256` del texto se compara con el del manifiesto. Si no
coincide, el texto **no** se guarda y se reintenta una vez; si vuelve a fallar,
se registra el material como "no se pudo sincronizar" y el resto del corpus
sigue funcionando.

**Regla de visibilidad (D13)**: un material con `visible = false` **se borra**
de la copia local en la siguiente sincronización y desaparece del índice.
> Honestidad necesaria: si el alumno no vuelve a sincronizar, conserva la copia
> anterior. La app lo avisa en el panel: *"El material que el docente ocultó se
> la próxima vez que se sincronice (necesitás conexión)."*

### 6.5 Dónde vive la copia local

```
KitoruPaths.dataDir/
├── courses/
│   └── <courseId>/
│       ├── corpus/
│       │   ├── manifest.json          ← CorpusManifest local
│       │   └── texts/
│       │       ├── m-12.txt            ← texto extraído (UTF-8)
│       │       └── m-13.txt
│       └── original/                   ← (opcional) los PDF, si el alumno los pidió
│           └── m-12.pdf
└── cache/
    └── corpus/<courseId>/             ← descargas a medias (.part)
```

El repositorio (`:core`):

```kotlin
interface CorpusRepository {
    suspend fun manifest(courseId: String): CorpusManifest?
    suspend fun saveManifest(manifest: CorpusManifest)
    suspend fun text(courseId: String, materialId: String): String?
    suspend fun saveText(courseId: String, materialId: String, text: String)
    suspend fun deleteText(courseId: String, materialId: String)
    /** Tamaño total en disco de la copia local. */
    suspend fun localBytes(courseId: String): Long
}
```

### 6.6 La fuente local para el RAG

Archivo nuevo: `backend/src/commonMain/kotlin/rag/corpus/LocalCorpusSource.kt`

```kotlin
/**
 * Fuente de documentos que lee la **copia local** del corpus.
 *
 * Produce los mismos `Doc` que `CourseMaterialSource` (mismo `source` y mismo
 * `path`), para que las citas sean idénticas a las del modo remoto.
 */
class LocalCorpusSource(
    private val repo: CorpusRepository,
    private val courseId: String,
    private val corpusLabel: String = "cátedra",
) : DocumentSource {

    override val label: String get() = corpusLabel

    override suspend fun load(): List<Doc> {
        val manifest = repo.manifest(courseId) ?: return emptyList()
        return manifest.visibleEntries.mapNotNull { entry ->
            val text = repo.text(courseId, entry.id) ?: return@mapNotNull null
            Doc(source = corpusLabel, path = entry.title, text = text)
        }
    }
}
```

> `path = entry.title` (igual que `CourseMaterialSource`, que usa
> `material.title.ifBlank { material.path }`) ⇒ **la cita se ve igual**:
> `[1] (cátedra — Unidad 2 — Probabilidad)`.

### 6.7 Cuándo se sincroniza

| Momento | Comportamiento |
|---|---|
| Al entrar a la cátedra | Sincronización silenciosa (1 request si no cambió la revisión). |
| Al abrir la app | Una sincronización por cátedra, en background, sólo si hay red. |
| Cuando el docente publica material | El servidor publica un **aviso** (`domain/07-anuncios.md`) con el tipo "material nuevo" → el alumno recibe la notificación y toca "Actualizar material". |
| Cuando se cae el servidor | **No** se sincroniza (no hay a quién); la copia local sigue sirviendo. |
| Manual | Botón "Actualizar material" en el panel de la cátedra del alumno. |

**El aviso del docente** (el mismo `Announcement` del dominio, sin tipo nuevo):

> **Material nuevo en IA-2026-1**
> Agregué "Unidad 3 — Optimización.pdf" (48 páginas).
> [ Actualizar mi material ]

### 6.8 El caso "sin red y sin corpus"

Si el alumno nunca sincronizó y está sin red:

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 📚 El material de la cátedra no está en este dispositivo                  │
│                                                                            │
│ Para usar IA local o buscar en el material necesitás descargar el          │
│ material de IA-2026-1 (38 MB) una vez. Después va a funcionar sin        │
│ conexión.                                                                  │
│                                                                            │
│ [ Descargar el material ]   (necesitás conexión)                           │
└────────────────────────────────────────────────────────────────────────────┘
```

### 6.9 El coste del corpus

Es **chico**: se descarga el **texto extraído**, no los PDF. Para una cátedra
típica: 24 materiales × ~45.000 caracteres ≈ 1 MB de texto (comprimido, ~400
KB). Los PDF originales (8–200 MB) sólo se descargan si el alumno los pide
(button "Descargar también los archivos" por material), y van a
`courses/<courseId>/original/`.

El panel lo dice:

```
Material de IA-2026-1 en este dispositivo
  24 documentos · 1,4 MB de texto · 0 archivos originales
  Última sincronización: 13/03 09:12 (revisión 7)
  [ Actualizar ]  [ Borrar la copia ]
```

---

## 7. Nombres de archivos y clases

### 7.1 `:core` — `core/src/commonMain/kotlin/kitoru/`

```
domain/
├── InferenceOrigin.kt        # InferenceOrigin, FallbackReason
├── Availability.kt           # RemoteStatus, LocalStatus, NetworkStatus
├── OriginResolver.kt         # OriginDecision, OriginResolver (función pura)
├── LocalModelSetup.kt        # LocalModelSetup, LocalInstallState
├── ModelCatalogEntry.kt      # ModelCatalogEntry, LocalModelCatalog
├── CorpusManifest.kt         # CorpusManifest, CorpusEntry
├── CorpusRepository.kt       # CorpusRepository + FileCorpusRepository
├── LocalChatMetrics.kt       # LocalChatMetrics (+ PrivacySettings)
└── ModelPolicy.kt            # (modificado) → resolveLocalSetup(base)
platform/
├── FileSystem.kt             # (modificado) + openWriter / FileWriter
├── GgufHeader.kt             # (nuevo) GgufInfo + GgufHeaderReader (lee el GGUF)
└── NetworkMonitor.kt         # (nuevo) expect/actual: isOnline
```

### 7.2 `:llm-api` — `llm-api/src/`

```
commonMain/kotlin/llm/
├── api/
│   ├── LocalModelService.kt        # (modificado) acquire/release/shutdown + SetupProgress
│   ├── LlmGateway.kt               # (sin cambios)
│   └── PromptBuilder.kt            # (nuevo) arma el prompt (común a JNI y MediaPipe)
├── config/
│   └── GatewayConfig.kt            # (modificado) LocalModelSettings + campos nuevos
└── error/
    └── LlmErrorMessages.kt         # (nuevo, del doc del docente) + casos local

jvmCommonMain/kotlin/llm/
└── catalog/
    ├── HuggingFaceDownloader.kt    # (nuevo, portado + verificado)
    └── ModelCatalog.kt             # (nuevo) catálogo de presets

desktopMain/kotlin/llm/
└── local/
    ├── LocalServerSupervisor.kt    # (nuevo, port de LocalServerManager)
    ├── LlamaBuilder.kt             # (nuevo, port de LlamaManager)
    └── LocalModelService.desktop.kt# (reescrito) sobre el supervisor

androidMain/kotlin/llm/
└── local/
    ├── MediaPipeLocalModelService.kt   # (nuevo) Hito A
    ├── LlamaJniLocalModelService.kt    # (nuevo) Hito B
    ├── llama/LlamaJniGateway.kt        # (nuevo) LlmGateway sobre la JNI
    └── llama/LlamaJniBridge.kt         # (nuevo) wrapper de la JNI (extern)
```

### 7.3 `:backend` — `backend/src/commonMain/kotlin/rag/`

```
corpus/
├── CorpusSyncService.kt       # (nuevo) diff + descarga + reindexado
├── CorpusRemote.kt            # (nuevo) cliente HTTP del manifiesto y los textos
├── LocalCorpusSource.kt       # (nuevo) DocumentSource sobre la copia local
└── CorpusSyncResult.kt        # (nuevo) CorpusSyncStatus, CorpusSyncProgress
engine/
└── OfflineAssistant.kt        # (nuevo) respuesta determinista sin IA
```

### 7.4 `:ui` — `ui/src/commonMain/kotlin/kitoru/ui/`

```
student/
├── LocalModelScreen.kt        # el panel de IA local (descargar, instalar, medir)
├── LocalModelViewModel.kt     # estado + acciones (descargar, compilar, medir)
├── ModelCostCard.kt           # la ficha de coste (§4.2)
├── AvailabilityBanner.kt      # el banner de modo degradado (§1.7)
├── LocalPrivacyDialog.kt      # el aviso de privacidad (§5.2)
├── TeacherVisibilityDialog.kt # "¿Qué ve mi docente?" (§5.4)
└── CorpusSyncPanel.kt         # estado y sincronización del corpus (§6.9)
common/
└── OfflineAnswerCard.kt       # la tarjeta de "extracto del material" (§1.6)
```

---

## 8. Plan de tests

| Test | Módulo | Qué cubre |
|---|---|---|
| `OriginResolverTest` | `:core` `commonTest` | Toda la tabla de §1.4: cada combinación de `RemoteStatus` × `LocalStatus` × `NetworkStatus` produce el origen y el motivo esperados. Incluye: el fallback a `LOCAL` **sólo** si el motor está `Ready`; `OFFLINE` no sube solo; el servidor recuperado **sí** restituye el preferido `REMOTE`. |
| `AvailabilityMessagesTest` | `:core` `commonTest` | Los textos del banner: cada `FallbackReason` produce un mensaje no vacío y en español; los mensajes de los cuatro fallos (servidor caído, key vencida, sin red, modelo no compilado) son los exactos de §1.5. |
| `GgufHeaderReaderTest` | `:core` `commonTest` | Lee una cabecera GGUF de prueba y calcula bien `block_count`, `head_count_kv`, `embedding_length`, `file_type` y los bytes de la KV cache (comparado con el valor a mano de Phi-4-mini: 81.920 bytes/token a 32 capas). |
| `CorpusSyncServiceTest` | `:backend` `commonTest` | El diff: nuevo / cambiado / borrado / `visible=false`; la verificación por `sha256`; que un material no visible **desaparezca** del índice; que `revision` igual ⇒ 0 requests; que sin red no se borre nada. |
| `CorpusParityTest` | `:backend` `commonTest` | **El invariante I-CORPUS**: el mismo texto con el mismo `chunkSize`/`chunkOverlap` produce la misma lista de `Chunk` en el cliente y en el servidor (mismo `source`, `path`, `heading` y `text`). Test de caracterización del `Chunker` portado. |
| `LocalCorpusSourceTest` | `:backend` `commonTest` | Las etiquetas de las citas locales coinciden con las de `CourseMaterialSource` (`"cátedra — <título>"`). |
| `OfflineAssistantTest` | `:backend` `commonTest` | Determinismo: misma pregunta + mismo corpus ⇒ misma respuesta; nunca inventa texto (las citas son literales del corpus); con corpus vacío devuelve el mensaje de "no encontré nada". |
| `HuggingFaceDownloaderTest` | `:llm-api` `jvmCommonTest` | (con un servidor HTTP de prueba) reanudación por `Range`, `.part` que sobrevive a la cancelación, rechazo del `200` cuando se pidió `Range`, verificación del sha256, rechazo del magic no-GGUF, y el mensaje de "descarga incompleta". |
| `LocalModelSetupTest` | `:core` `commonTest` | `effective(base)` y la precedencia de los bloqueos (`LOCAL_MODE` bloqueado ⇒ el alumno no puede activarlo aunque tenga el modelo). |
| `LocalTelemetryTest` | `:core` `commonTest` | Las métricas locales no contienen texto: se verifica que ningún campo carry texto de mensajes (por construcción y por un test que serializa y busca subcadenas del mensaje). |

---

## 9. Deuda conocida y fuera de alcance

| Tema | Por qué queda afuera |
|---|---|
| Compilar llama.cpp **en Android** (NDK en el dispositivo) | El build de la NDK en un teléfono es inviable. En Android el motor viene precompilado en el APK. |
| Búsqueda semántica (embeddings) para el corpus local | El RAG es BM25 (determinista, sin modelo). Agregar embeddings rompería la regla 3 y multiplicaría el tamaño del modelo local. |
| Indexación persistente en disco | Reconstruir el índice en memoria cuesta ~50 ms por cátedra. Persistirlo implica versionar un formato. |
| Descarga de los **archivos originales** (PDF) por defecto | Son 8–200 MB por material. Se deja como acción explícita del alumno. |
| Sincronización del corpus por *push* (notificación del servidor) | Requiere un canal persistente. En la v1 es *pull* (al entrar a la cátedra y con el botón "Actualizar"), con el aviso del docente como señal. |
| Encriptar la copia local del corpus | El material es del docente, no del alumno. La privacidad en juego es la de los **chats**, que ya están en el directorio de datos de la app. |
| Cuota de uso de la IA local (límite de tokens por día) | El coste lo paga el alumno en su equipo, no la institución. Un límite agregaría fricción sin problema que resolver. |
| Migración del `LocalModelService.start/stop` a `acquire/release` | Es un cambio de API en `:llm-api`. Se hace junto con la Fase 1; hasta entonces `start()` queda como fachada de `acquire()`. |

---

## 10. Resumen operativo: qué pasa cuando el servidor no anda

```
        ┌───────────────────────────────────────────────────────────┐
        │  El alumno manda un mensaje                              │
        └───────────────────────────┬───────────────────────────────┘
                                    │
                    ┌───────────────▼────────────────┐
                    │  OriginResolver.resolve(...)    │  ← función pura
                    └───────────────┬────────────────┘
        ┌───────────────────────────┼───────────────────────────────┐
        │                           │                               │
   servidor OK              servidor caído                 servidor caído
        │                    + motor local Ready            + sin motor local
        ▼                           │                               │
   ┌─────────┐                     ▼                               ▼
   │ REMOTE  │               ┌─────────┐                    ┌─────────┐
   │ cátedra │               │  LOCAL  │                    │ OFFLINE │
   │ (IA de  │               │ (~8 tok/s,│                   │ material│
   │ la inst.)│               │ 3,2 GB) │                    │  solo   │
   └─────────┘               └─────────┘                    └─────────┘
        │                           │                               │
   el docente                  el docente NO                     el docente NO
   ve el texto                 ve nada del texto                 ve nada
```

Y en cada uno de los tres caminos, el alumno puede **exportar** el chat si
quiere que su docente lo lea. Esa es la única puerta, y es explícita.
