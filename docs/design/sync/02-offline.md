# 02 · Offline-first y caché

> Cómo funciona Kitoru cuando no hay red, qué se guarda en el dispositivo y
> qué se tira.
>
> Módulo propuesto: **`:sync`** (nuevo). Depende de `:core`, usa Ktor como
> `:llm-api`. El dominio (`:core/domain`) **no sabe que existe la red**.

Estado: propuesta cerrada. Tipos de referencia: `kitoru.platform.KitoruPaths`
(`configDir`, `coursesDir`, `cacheDir`), `kitoru.platform.FileSystem`,
`kitoru.domain.JsonStore` (`JsonListStore`), `kitoru.domain.FileRepositories`
(`File*Repository`), `kitoru.domain.Course` (`Course`, `Enrollment`, `Material`),
`kitoru.domain.Chat` (`ChatThread`, `ChatMessage`),
`rag.retrieval.KnowledgeBase` / `Bm25Index` / `CourseMaterialSource`.

---

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| ¿La app depende de la red? | **No, nunca.** La red es un extra, no un prerrequisito | Una app de cursada se usa en el-bondi, en una kéti sin señal y en un parcial sin wifi |
| ¿La búsqueda en el material necesita red? | **No. Jamás.** El corpus es local y el índice es local (BM25 en memoria) | Es la función que más se usa: si dependiera de la red, la app "no funciona" en el 80 % del tiempo real |
| ¿Dónde vive el estado de conexión? | `NetworkMonitor` (`expect/actual`): pista del SO + *heartbeat* HTTP al servidor | No hay API portable de "hay internet"; y "hay internet" ≠ "el servidor responde" |
| ¿Qué pasa con lo que hay que enviar? | `Outbox`: cola persistente en disco con reintento por *backoff* exponencial | Perder un chat porque el servidor estaba caído es inaceptable |
| ¿Y si lo que se encoló ya no sirve? | **Caduca**: `expiresAt` por tipo de operación; lo que caduca se descarta y se cuenta | Una "lectura de aviso" de hace 40 días no vale nada; una "entrega" sí, y avisa |
| ¿El servidor es necesario? | **Para el alumno, sí, la primera vez.** Es el punto de distribución del material | Si el material sólo existe en la notebook del docente, los alumnos no tienen nada |
| ¿El modo sin servidor bloquea algo? | **No.** Sin servidor la app es una app local completa (cátedra por `.kitoru`) | El milestone 1 no tiene servidor y la app tiene que ser útil igual |
| ¿Módulo nuevo? | **`:sync`**, con Ktor, sin meter red en `:core` | `:core` hoy sólo depende de coroutines + serialization; sigue así |

---

## 1. La regla: la app nunca deja de funcionar por falta de red

### 1.1 La regla, escrita de a una

> **Kitoru abre, muestra y deja trabajar sin importar el estado de la red.**
> La red sólo puede *agregar* capacidades (sincronizar, ver los chats de los
> alumnos, usar el modelo alojado), nunca *quitar* las que ya están.

Tres corolarios que gobiernan todo el documento:

1. **Ningún `if (online)` antes de una acción local.** La UI no consulta el
   estado de la red para decidir si puede mostrar algo: muestra el dato local y
   *después* agrega el estado. Si la UI consulta la red, cada pantalla tiene un
   estado de carga que no debería existir.
2. **El error de red nunca se muestra como error.** "No se pudo conectar" es
   un *estado*, no una excepción. La excepción real es "el servidor dijo que
   no".
3. **Nada se pierde.** Toda operación que requiere red y no se pudo hacer queda
   en la `Outbox` (§4) y se reintenta sola.

### 1.2 Qué sigue funcionando sin conexión

| Función | ¿Sin red? | Dónde está el dato | Nota |
|---|---|---|---|
| **Chat con IA local** | ✅ Sí, completo | `llama-server` en la máquina | Modo `LOCAL` de `ModelPolicy`; no toca la red ni el servidor |
| **Chat con IA alojada (REMOTE)** | ❌ No | el modelo vive en el servidor | Se dice con todas las letras (§5.4) |
| **Búsqueda en el material** | ✅ Sí, completo | corpus local + `Bm25Index` en memoria | **Función clave.** Ver §1.3 |
| **Leer el material** (PDF, apuntes) | ✅ Sí | `courses/<id>/materials/` | Los archivos bajados son copias locales |
| **Historial de chats** | ✅ Sí | `courses/<id>/chats/<autor>/<hilo>/` | El hilo se escribe local primero, siempre |
| **Anuncios** | ✅ Sí (copia) | `cache/announcements/<courseId>.json` | Se marca "copia local del 12/10" |
| **Tareas y enunciados** | ✅ Sí (copia) | `config/` + `cache/` | Igual que los anuncios |
| **Configuración de la cátedra** | ✅ Sí | `config/courses.json` | Se usa la última conocida |
| **Configuración de la app** (perfil, tema, modelo local) | ✅ Sí | `config/*.json` | Nunca dependió de la red |
| **Analítica del docente** | ❌ No (datos de otros) | el servidor | Con datos ya descargados, sí |
| **Inscribirse a una cátedra por código** | ⚠️ Sólo si la cátedra ya está local | `config/courses.json` | Si no: hace falta el bundle `.kitoru` (§6) |
| **Entregar una tarea** | ❌ No se envía | `Outbox` | Queda encolada y se envía sola (§4) |

### 1.3 La búsqueda en el material: siempre local, siempre

Esta es la decisión más importante del documento, así que va con detalle.

El RAG de Kitoru **no es un servicio**: es código que corre en el dispositivo.

```
Material (PDF, .md, .txt)
   │  extracción de texto (local)
   ▼
Material.extractedText          ← guardado en config/materials.json
   │  Chunker.split(...)        ← rag/retrieval/Chunker.kt
   ▼
Chunk (heading, text)
   │  Bm25Index(chunks)         ← rag/retrieval/Bm25Index.kt
   ▼
Índice BM25 en memoria          ← rag/retrieval/KnowledgeBase.kt
   │
   ▼
retrieve(query, k)              ← sin red, sin disco, sin reloj
```

`KnowledgeBase.sync()` arma el índice desde `CourseMaterialSource(materials)` y
`Bm25Index.search(query, k)` resuelve en memoria. **No hay una sola llamada de
red en ese camino.** Por lo tanto:

- La búsqueda en el material **no tiene una versión "online"**: es la misma
  función siempre. No hay dos códigos, no hay un `when (isOnline)`.
- El índice es **derivado**: se puede borrar y se reconstruye. Se persiste el
  *chunking* en `courses/<id>/index.json` para no re-chunkar en cada arranque
  (el BM25 en sí se rehidrata desde los chunks; no se serializa el índice
 invertido, que no vale la pena).
- La búsqueda **no depende de que el servidor esté arriba**, ni de que la
  cátedra exista en el servidor, ni de la visibilidad de nada.

Lo que **sí** depende de la red es la *frescura* del corpus: si el docente sube
material nuevo y el alumno no sincronizó, el alumno no lo tiene. Eso se dice
explícitamente en la UI (§2.4), no se oculta.

**Búsqueda global en el dispositivo.** Un detalle de producto que sale
natural de esto: como el índice es local, la app puede ofrecer una búsqueda
**entre todas las cátedras del dispositivo** (el alumno a veces busca en la
cátedra equivocada). Es una función **sin red por definición** y de alto
valor: es lo que el alumno hace en el viaje.

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/CourseSearchIndex.kt
package rag.retrieval

/**
 * Índice de búsqueda de una cátedra, reconstruible desde el corpus local.
 *
 * Es **derivado**: `Material.extractedText` es la verdad; este índice se puede
 * borrar y se regenera. Nunca se serializa el índice BM25: se serializan los
 * *chunks* y el índice se rehidrata (reconstruir un índice de 40 MB tarda
 * ~200 ms; serializarlo es un formato nuevo que hay que mantener).
 */
class CourseSearchIndex(private val cfg: RagConfig) {
    fun build(materials: List<Material>): CourseSearchIndex
    fun search(query: String, k: Int): List<SearchHit>
    val chunkCount: Int
}

data class SearchHit(
    val materialId: String,
    val materialTitle: String,
    val chunk: Chunk,
    val score: Double,
)
```

### 1.4 Qué NO funciona sin conexión (la lista honesta)

Nada de esto se disimula:

| No funciona | Por qué | Qué se le dice al usuario |
|---|---|---|
| Chat con IA **alojada** | El modelo está en el servidor | "El modelo de esta cátedra está en el servidor de la institución. Sin conexión no se puede chatear. Podés seguir leyendo el material y usando el modelo local si lo tenés." |
| Ver los chats de los alumnos (docente) | Los datos están en el servidor | "Los chats de los alumnos se descargan cuando hay conexión." |
| Publicar un aviso | Es una escritura remota | "Se guardó en tu dispositivo. Se publica cuando vuelva la conexión." |
| Verificar la API key | Es una consulta al servidor | "No pudimos validar la clave (sin conexión). La app funciona igual; la clave se valida sola después." |
| Inscribirse con un código desconocido | El directorio está en el servidor | "Ese código no corresponde a ninguna cátedra en este dispositivo. Pedile a tu docente el archivo de la cátedra." |

### 1.5 El principio: el dominio no sabe que existe la red

`Course`, `Material`, `Announcement`, `ChatThread` y `ChatMessage` **no tienen
campos de red, ni de caché, ni de "pendiente de sincronizar"**. Son datos
puros.

Todo lo que es red vive en `:sync` y se *aplica encima* de los repositorios:

```
   :ui  ──►  :core/domain  ◄──  :sync  ──►  Kitoru-Server
              (repositorios)      (capa de
                                  sincronización)
```

Consecuencias concretas:

- `MaterialRepository` no cambia. La sincronización **escribe en el mismo
  repositorio** que usa la UI: la UI no distingue "bajado del servidor" de
  "importado de un `.kitoru`" de "subido por el docente". No hay dos caminos
  de escritura.
- El estado "pendiente de enviar" vive **en la `Outbox`**, no en las
  entidades. Un `ChatThread` no tiene un campo `synced: Boolean` que se pueda
  desincronizar con la realidad; hay una cola, que es la verdad.
- La regla "sin red" se implementa **una sola vez**, en `:sync`, y no se
  filtra al dominio.

---

## 2. Qué se cachea y con qué política

### 2.1 `CachePolicy`

```kotlin
package kitoru.sync

/**
 * Política de caché de un recurso, vista desde el cliente.
 *
 * - [ALWAYS]: la copia local es **la** copia. No se borra automáticamente y
 *   la app funciona completo sin ella.
 * - [IF_ONLINE]: la copia local es una **réplica**. Se puede tirar y
 *   recuperar; mientras no haya red, es lo único que hay.
 * - [NEVER]: no se guarda copia local (o se guarda fuera del alcance de la
 *   caché: en el almacén de credenciales, o no se guarda).
 */
enum class CachePolicy(val label: String) {
    ALWAYS("Siempre local"),
    IF_ONLINE("Si hay red"),
    NEVER("Nunca"),
}
```

### 2.2 Tabla de recursos

La tabla es **una sola**, en un solo lugar, y es la fuente de verdad:

```kotlin
package kitoru.sync

/** Qué clase de recurso se está cacheando. */
enum class CacheKind {
    /** Archivos originales del corpus (PDF, slides, apuntes). */
    MATERIAL_FILES,

    /** Texto extraído + chunks del corpus (derivado). */
    MATERIAL_INDEX,

    /** Avisos de la cátedra. */
    ANNOUNCEMENTS,

    /** Tareas, enunciados y rúbricas. */
    ASSIGNMENTS,

    /** Configuración de la cátedra: `Course` + `ModelPolicy` + prompt. */
    COURSE_CONFIG,

    /** Catálogo de inscripciones (roster). */
    ENROLLMENTS,

    /** Hilos y mensajes del propio usuario. */
    OWN_THREADS,

    /** Hilos y mensajes de otros (vista del docente). */
    OTHER_THREADS,

    /** Entregas del alumno (texto + archivos). */
    SUBMISSIONS,

    /** Lecturas de avisos. */
    READS,

    /** Secretos (API key, token de sesión). */
    SECRETS,

    /** Catálogo de modelos del servidor (`/models`). */
    MODEL_CATALOG,
}

/** Identidad de un recurso cacheado. */
data class CacheKey(
    val kind: CacheKind,
    /** Cátedra (o "" para recursos globales). */
    val courseId: String = "",
    /** Id del recurso dentro de la cátedra (o ""). */
    val resourceId: String = "",
) {
    /** Clave plana para el índice y para las rutas en disco. */
    val flat: String
        get() = listOf(kind.name, courseId, resourceId)
            .filter { it.isNotBlank() }
            .joinToString("/")
}
```

Y las políticas:

```kotlin
package kitoru.sync

/**
 * La tabla de políticas de caché. **Un solo lugar** decide qué se guarda.
 *
 * La regla de dedo: si el recurso es la **materia prima** de una función que
 * tiene que funcionar sin red → ALWAYS. Si es una **réplica** de algo que el
 * servidor puede volver a mandar → IF_ONLINE. Si es un **secreto** o algo
 * efímero → NEVER.
 */
object CachePolicies {

    val policyOf: Map<CacheKind, CachePolicy> = mapOf(
        // El corpus es la materia prima de la búsqueda: sin red, sin material.
        CacheKind.MATERIAL_FILES to CachePolicy.ALWAYS,
        CacheKind.MATERIAL_INDEX to CachePolicy.ALWAYS,

        // Réplicas de lectura: se pueden volver a bajar.
        CacheKind.ANNOUNCEMENTS to CachePolicy.IF_ONLINE,
        CacheKind.ASSIGNMENTS to CachePolicy.IF_ONLINE,
        CacheKind.COURSE_CONFIG to CachePolicy.ALWAYS,
        CacheKind.ENROLLMENTS to CachePolicy.ALWAYS,
        CacheKind.OTHER_THREADS to CachePolicy.IF_ONLINE,

        // El origen local: la verdad está en el dispositivo.
        CacheKind.OWN_THREADS to CachePolicy.ALWAYS,
        CacheKind.SUBMISSIONS to CachePolicy.ALWAYS,
        CacheKind.READS to CachePolicy.ALWAYS,

        // Nunca en el store JSON.
        CacheKind.SECRETS to CachePolicy.NEVER,
        CacheKind.MODEL_CATALOG to CachePolicy.NEVER,
    )

    fun policyOf(kind: CacheKind): CachePolicy = policyOf[kind] ?: CachePolicy.IF_ONLINE
}
```

La tabla completa, con invalidación:

| Recurso | `CacheKind` | Política | Dónde vive | Invalidación |
|---|---|---|---|---|
| **Materiales** (archivos originales) | `MATERIAL_FILES` | **ALWAYS** | `courses/<id>/materials/` | Por `rev` del manifiesto + `sha256` del archivo. Si el hash local coincide, no se vuelve a bajar. |
| **Texto extraído + chunks** | `MATERIAL_INDEX` | **ALWAYS** (derivado) | `config/materials.json` + `courses/<id>/index.json` | Se recalcula si cambia el conjunto de ids/versiones del corpus. Se puede borrar sin pérdida. |
| **Anuncios** | `ANNOUNCEMENTS` | **IF_ONLINE** | `cache/announcements/<courseId>.json` | TTL 24 h **o** `updatedAt`/`rev` del servidor, lo que llegue antes. |
| **Tareas** | `ASSIGNMENTS` | **IF_ONLINE** | `cache/assignments/<courseId>.json` | Ídem. |
| **Config de la cátedra** (`Course`, `ModelPolicy`, prompt) | `COURSE_CONFIG` | **ALWAYS** | `config/courses.json` | Por `rev` de la cátedra. La **API key no va acá**: al `CredentialStore`. |
| **Inscripciones / roster** | `ENROLLMENTS` | **ALWAYS** | `config/enrollments.json` | El servidor propone; el local acepta (el alumno puede cambiar su `visibility` en cualquier momento). |
| **Chats propios** | `OWN_THREADS` | **ALWAYS** | `courses/<id>/chats/<autor>/<hilo>/` | Nunca se borran por caché. El origen es local. |
| **Chats de otros** (docente) | `OTHER_THREADS` | **IF_ONLINE** | `cache/threads/<courseId>/<authorId>/…` | TTL corto (1 h) + `rev` del hilo. Badge "copia local". |
| **Entregas** | `SUBMISSIONS` | **ALWAYS** (origen) + outbox (destino) | `courses/<id>/submissions/` | Nunca se borran por caché. |
| **Lecturas de avisos** | `READS` | **ALWAYS** | `config/announcement_reads.json` | Nunca. |
| **API key / tokens** | `SECRETS` | **NEVER** | `CredentialStore` (§8 de `09-identidad.md`) | No hay caché: o está o no está. |
| **Catálogo `/models`** | `MODEL_CATALOG` | **NEVER** | sólo en memoria, TTL 5 min | Se vuelve a pedir. |
| **Pesos del modelo local (GGUF)** | — | **NEVER** (fuera de alcance) | los maneja `LocalModelService` (`modelsDir`) | Kitoru no los borra ni los cuenta. |

### 2.3 El detalle que hay que decir en voz alta: **la dirección de la caché**

La caché no es simétrica: depende de quién es el dueño de la verdad.

```
ALUMNO                                   DOCENTE
────────                                 ───────
sus chats        → ORIGEN local (ALWAYS)  los chats de los alumnos → RÉPLICA (IF_ONLINE)
sus entregas    → ORIGEN local (ALWAYS)  la config de la cátedra → ORIGEN local (ALWAYS)
el corpus        → RÉPLICA (ALWAYS*)      el corpus             → ORIGEN local (ALWAYS)
los avisos      → RÉPLICA (IF_ONLINE)    los avisos           → ORIGEN local (ALWAYS)
la config        → RÉPLICA (ALWAYS)       el roster            → RÉPLICA (IF_ONLINE)
```

(*) El corpus del alumno es una réplica del servidor, pero con política
**ALWAYS** y no `IF_ONLINE`, porque es la materia prima de la búsqueda
(§1.3) y porque un alumno que no puede buscar es un alumno sin app. La
"réplica" se distingue por el hecho de que **el servidor puede volver a
mandarla**; la política dice qué pasa si hay que elegir entre espacio y
funcionalidad, y acá no se elige: espacio.

Corolario de diseño: **el docente escribe local y el servidor reparte**. El
alumno escribe local y el servidor *recolecta*. Los dos lados tienen un
outbox, pero con contenidos distintos (§4.10, §7).

### 2.4 Invalidación: tres estrategias y una regla

| Estrategia | Se usa en | Cómo funciona |
|---|---|---|
| **Por versión (`rev`)** | Todo lo que viene del servidor | El servidor expone un manifiesto por cátedra: `{ rev, config: {rev}, materials: [{id, rev, size, sha256}], announcements: [{id, rev}] }`. El cliente guarda el `rev` aplicado de cada recurso y sólo baja lo que cambió. Es la estrategia principal. |
| **Por TTL** | Réplicas sin versión (anuncios, chats ajenos) | `expiresAt = now + ttl`. Vencido, el recurso queda marcado "puede estar desactualizado" y se refresca en el próximo sync. **Nunca se borra por vencer**: se marca. |
| **Por contenido (hash)** | Archivos del corpus | El manifiesto trae `sha256`; el cliente guarda el hash del archivo que bajó (sidecar `.sha256` junto al archivo). Si coincide, no se vuelve a descargar ni a verificar. |

Y la regla que las engloba:

> **Stale-while-revalidate**: la UI muestra **siempre** el dato local, apenas
> disponible, y la sincronización corre por detrás. Un recurso vencido se
> muestra con un chip ("copia del 12/10"), nunca con un spinner infinito, y
> nunca se oculta mientras se refresca.

La invalidación por `rev` es la que resuelve el caso difícil: *"el docente
editó el prompt de la cátedra"*. El cliente tiene `configRev = 7`; el servidor
dice `configRev = 8` → baja la config nueva → `configRev = 8`. Sin `rev`, la
única opción sería comparar el contenido completo en cada sync, que es peor.

### 2.5 Dos niveles en disco: `courses/` (tier 1) y `cache/` (tier 2)

El layout de `KitoruPaths` ya separa `coursesDir` de `cacheDir`. Esa
separación es exactamente la separación entre los dos niveles:

| Nivel | Carpeta | Qué hay | ¿Se borra automáticamente? |
|---|---|---|---|
| **Tier 1 — working set** | `courses/<id>/` | materiales, índice, chats, entregas | **No.** Es material del usuario, no caché |
| **Tier 2 — caché** | `cache/` | réplicas de lectura (anuncios, tareas, chats ajenos), descargas en curso, thumbnails | **Sí**, por LRU (§8.3) |

En Android esto calza perfecto con el sandbox: `filesDir` (tier 1, el SO no
lo borra) vs `context.cacheDir` (tier 2, el SO lo puede borrar cuando le
falta espacio). Por eso `KitoruPaths.setDataDir` va a aceptar un segundo
directorio:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt (modificado)
object KitoruPaths {
    var dataDir: FilePath = FilePath(".")
        private set

    /** Carpeta de caché descartable (tier 2). En Android: context.cacheDir. */
    var cacheDir: FilePath = FilePath(".")
        private set

    /** Carpeta de la cola de salida. NO es caché: no se descarta. */
    var outboxDir: FilePath = FilePath(".")
        private set

    fun setDataDir(
        path: FilePath,
        cache: FilePath = path.resolve("cache"),
        outbox: FilePath = path.resolve("outbox"),
    ) {
        dataDir = path
        cacheDir = cache
        outboxDir = outbox
    }

    val configDir: FilePath get() = dataDir.resolve("config")
    val coursesDir: FilePath get() = dataDir.resolve("courses")
}
```

> **La `Outbox` no va en `cache/`.** Si viviera ahí, el SO (Android) o una
> limpieza de espacio (desktop) podrían tirar cola de envíos pendientes. La
> cola es un compromiso con el usuario: sale de `filesDir`, o de `dataDir`.

### 2.6 Lo que nunca se cachea

- **La API key y los tokens**: viven en el `CredentialStore`
  (`09-identidad.md` §8.2). `Course.modelPolicy.apiKey` sigue existiendo
  como campo (caso del bundle `.kitoru` que el docente comparte a propósito),
  pero la key que emite el servidor **nunca** entra en un JSON de caché.
- **El catálogo `/models`**: se repregunta; TTL 5 min en memoria.
- **Los pesos del modelo local**: no son de Kitoru.
- **Lo que el usuario no tiene derecho a ver**: la regla de visibilidad no es
  una política de caché. Un `Enrollment.visibility = NONE` significa que el
  cliente **no descarga** esos chats; no es "los descarga y los esconde".

---

## 3. El estado de conexión: `NetworkMonitor`

### 3.1 Por qué no hay API portable de "hay internet"

En KMP no existe `isOnline()`. Lo que hay:

| Plataforma | Qué hay | Qué falta |
|---|---|---|
| Desktop (JVM) | `java.net.Socket`, `InetAddress.isReachable` | No dice si **hay internet**; sólo si hay ruta a **un** host. `isReachable` usa ICMP y en muchos equipos está bloqueado: devuelve `false` con la red perfecta. |
| Android | `ConnectivityManager` + `NetworkCapabilities` | `NET_CAPABILITY_VALIDATED` dice "el sistema validó internet", pero no dice si **el servidor de la institución** está vivo. Y un portal cautivo rompe la suposición. |
| Común | — | Lo único portable es **preguntarle a alguien**: un request HTTP. |

De ahí la arquitectura de dos señales:

1. **Pista del sistema operativo** (`platformHint`): rápida, gratis, pero
   approximate. Sirve para *no intentar* cuando sabemos que no hay red.
2. **Heartbeat al servidor** (`probe`): lento (4 s), pero **veraz**: es la
   única señal que responde a la pregunta que importa, *"¿puedo hablar con
   Kitoru-Server?"*.

La regla: **el heartbeat manda**. La pista del SO sólo se usa para no gastar
un timeout cuando ya sabemos que no hay red.

### 3.2 Los estados

```kotlin
package kitoru.sync

/**
 * Estado de la conexión, desde el punto de vista de la app.
 *
 * No es "hay internet": es "puedo hablar con el servidor de la institución".
 */
sealed interface ConnectionState {
    /** Todavía no se comprobó. */
    data object Unknown : ConnectionState

    /** El servidor respondió. */
    data object Online : ConnectionState

    /** No hay red (o no se puede alcanzar al host). */
    data object Offline : ConnectionState

    /**
     * Hay algo, pero no alcanza para trabajar:
     * - el SO ve red pero no valida internet (portal cautivo),
     * - el host responde pero el servidor no (500 / 404 / TLS roto),
     * - las credenciales no son válidas (401).
     */
    data class Limited(val reason: String) : ConnectionState
}

/** Modo de trabajo de la app, derivado del estado de conexión. */
enum class SyncMode(val label: String) {
    /** Todo habilitado: el servidor responde. */
    FULL("Conectado"),

    /** Hay servidor pero no responde: se lee todo, se encola lo que se escribe. */
    READ_ONLY("Sin conexión (sólo lectura)"),

    /** No hay servidor configurado: la app es local. */
    LOCAL_ONLY("Sólo local"),
}
```

Derivación:

```kotlin
fun ConnectionState.toSyncMode(hasServer: Boolean): SyncMode = when {
    hasServer && this == ConnectionState.Online -> SyncMode.FULL
    hasServer -> SyncMode.READ_ONLY
    else -> SyncMode.LOCAL_ONLY
}
```

`LOCAL_ONLY` es un modo **de primera clase**, no un error: es el estado del
milestone 1 y del alumno que usa la cátedra descargada. En `LOCAL_ONLY` no hay
banner de "sin conexión": hay un aviso distinto (*"Estás trabajando sin
servidor: nada de lo que hagas llega a tu docente"*).

### 3.3 La interfaz (común) y el `expect/actual`

```kotlin
package kitoru.sync

/**
 * Vigila el estado de la conexión.
 *
 * Combina dos señales: la **pista del sistema operativo** (rápida, no
 * veraz) y el **heartbeat** al servidor (lento, veraz). Cuando hay un
 * servidor configurado, el heartbeat manda.
 */
interface NetworkMonitor {
    /** Estado actual. Es un `StateFlow`: la UI lo observa y dibuja el chip. */
    val state: StateFlow<ConnectionState>

    /** Momento del último heartbeat exitoso (0 = nunca). */
    val lastOnlineAt: Long

    /**
     * Pista del sistema operativo: rápido, sin red, aproximado.
     * `Unknown` si el SO no puede decir nada.
     */
    fun platformHint(): ConnectionState

    /**
     * Chequeo autoritativo: un request al servidor.
     * Devuelve [ConnectionState.Online] si respondió.
     */
    suspend fun probe(): ConnectionState

    /** Arranca el sondeo periódico (llamado una vez al iniciar la app). */
    fun start()

    /** Detiene el sondeo (llamado al cerrar la app). */
    fun stop()
}

/**
 * Crea el monitor. `serverProvider` se llama en cada chequeo porque la
 * dirección del servidor se puede cambiar en Ajustes sin reiniciar la app.
 *
 * `heartbeat` es el probe HTTP, común a las dos plataformas (Ktor).
 */
expect fun createNetworkMonitor(
    serverProvider: () -> ServerEndpoint?,
    heartbeat: Heartbeat,
    scope: CoroutineScope,
): NetworkMonitor
```

Y el probe HTTP, que es común:

```kotlin
package kitoru.sync

/**
 * El "ping" al Kitoru-Server.
 *
 * Contrato con el servidor (a definir en `01-protocolo.md`):
 *   GET {base}/v1/ping
 *   200 {"ok":true,"serverTime":1730000000000,"version":"0.3.0"}
 *
 * Es una respuesta diminuta y rápida: el objetivo es *saber si hay alguien
 * al otro lado*, no hacer trabajo.
 */
class Heartbeat(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
    private val timeoutMs: Long = 4_000,
) {
    suspend fun ping(): Boolean = runCatching {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank()) return false
        client.get("$base/v1/ping") {
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }.getOrNull()?.status?.isSuccess() == true
}
```

### 3.4 Android: `ConnectivityManager`

```kotlin
// sync/src/androidMain/kotlin/kitoru/sync/NetworkMonitor.android.kt
package kitoru.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

private class AndroidPlatformProbe(private val context: Context) {

    private val cm: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun current(): ConnectionState {
        val manager = cm ?: return ConnectionState.Unknown
        val network = manager.activeNetwork ?: return ConnectionState.Offline
        val caps = manager.getNetworkCapabilities(network) ?: return ConnectionState.Offline
        return when {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                ConnectionState.Online
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                // Red asociada pero sin validación: portal cautivo, Wi-Fi de hotel.
                ConnectionState.Limited("Hay red, pero no está verificada (¿portal del hotel?)")
            else -> ConnectionState.Offline
        }
    }

    /** Callback del sistema: avisa cuando cambia la red. */
    fun observe(onChange: () -> Unit) {
        val manager = cm ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        })
    }
}
```

Detalles de por qué así:

- `NET_CAPABILITY_VALIDATED` (API 23+) es la señal que vale: el sistema hizo
  una prueba de internet real. `NET_CAPABILITY_INTERNET` solo significa "hay
  una interfaz de red configurada" (un hotspot sin datos, una Wi-Fi de
  aeropuerto con portal).
- `registerDefaultNetworkCallback` (API 24+) es el callback correcto para
  "la red por defecto del dispositivo": `requestNetwork` exigiría pedir
  una red concreta y `NetworkRequest` con `NET_CAPABILITY_INTERNET` son
  APIs que piden permisos o estados que no queremos.
- **Ojo con el Wi-Fi de la facultad**: si el Kitoru-Server está en la LAN, el
  dispositivo puede tener `VALIDATED = true` por el 4G y aun así no llegar al
  servidor de la LAN. Por eso la señal del SO **no decide**: decide el
  heartbeat (§3.6).

Permiso necesario en el módulo `:android`:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 3.5 Desktop: socket al host conocido

En desktop no hay `ConnectivityManager`. Lo más cercano es **abrir un socket
TCP contra un host conocido** con timeout corto. No es "hay internet": es
"el host del Kitoru-Server acepta conexiones".

```kotlin
// sync/src/desktopMain/kotlin/kitoru/sync/NetworkMonitor.desktop.kt
package kitoru.sync

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private class DesktopPlatformProbe(
    private val serverProvider: () -> ServerEndpoint?,
    private val timeoutMs: Int = 1_500,
) {

    fun current(): ConnectionState {
        val server = serverProvider() ?: return ConnectionState.Unknown
        return when {
            tcpReachable(server.host, server.port) -> ConnectionState.Online
            else -> ConnectionState.Offline
        }
    }

    /**
     * Un TCP connect con timeout corto. No manda datos: sólo prueba que hay
     * alguien escuchando.
     *
     * Ojo: esto **no** comprueba internet, comprueba *ese* host. Si el
     * servidor está en la LAN y el alumno está en casa, da `Offline` aunque
     * haya internet perfecto — que es la respuesta correcta para la pregunta
     * que importa.
     */
    private fun tcpReachable(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
```

Decisiones y sus motivos:

- **El host es el del servidor configurado**, no `8.8.8.8` ni `1.1.1.1`.
  Chequear un host público DNS (ni DNS, ni ICMP) es un pedido a un tercero
  en cada chequeo, y la respuesta no dice nada útil: el servidor de la
  institución puede estar en una LAN sin salida a internet, y en ese caso
  "no hay internet" es irrelevante.
- **Timeout de 1,5 s**: si el socket no abre en 1,5 s, para el usuario la
  red está mala. Más corto y da falsos negativos en redes celulares; más
  largo y la UI se queda esperando.
- **No se manda ni un byte**: un `connect()` a un puerto cerrado devuelve
  `ECONNREFUSED` de inmediato, lo que también sirve como señal ("hay
  alguien en la red, pero el servidor no está").
- **Si no hay servidor configurado**, el estado es `Unknown`, **no**
  `Offline`: no es lo mismo "no hay red" que "no hay a quién preguntarle".
  La app muestra "Sólo local" en vez de "Sin conexión".

### 3.6 El heartbeat: la señal que manda

```kotlin
// sync/src/commonMain/kotlin/kitoru/sync/NetworkMonitor.kt
private class DefaultNetworkMonitor(
    private val serverProvider: () -> ServerEndpoint?,
    private val heartbeat: Heartbeat,
    private val platformProbe: PlatformProbe,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 60_000,
) : NetworkMonitor {

    private val _state = MutableStateFlow(ConnectionState.Unknown)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override var lastOnlineAt: Long = 0L
        private set

    private var job: Job? = null

    override fun platformHint(): ConnectionState = platformProbe.current()

    override suspend fun probe(): ConnectionState {
        val server = serverProvider()
        if (server == null) {
            _state.value = ConnectionState.Unknown
            return ConnectionState.Unknown
        }

        // 1) La pista del SO: si dice que no hay red, no gastamos 4 s.
        val hint = platformProbe.current()
        if (hint == ConnectionState.Offline) {
            _state.value = ConnectionState.Offline
            return ConnectionState.Offline
        }

        // 2) El heartbeat: la verdad.
        val ok = heartbeat.ping()
        val result = when {
            ok -> ConnectionState.Online
            hint == ConnectionState.Online ->
                // El SO dice que hay red pero el servidor no respondió.
                ConnectionState.Limited("El servidor no responde")
            else -> ConnectionState.Limited("No se pudo verificar el servidor")
        }
        _state.value = result
        if (ok) lastOnlineAt = nowMillis()
        return result
    }

    override fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                probe()
                delay(pollIntervalMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
```

**Frecuencia y momento**: cada 60 s con la app abierta; además, un `probe()`
forzado (a) al entrar en `SyncMode.FULL` desde otro estado, (b) antes de
cada vaciado de la outbox, (c) cuando el usuario aprieta "Sincronizar ahora".
La app **no** sondea en background: en desktop no hay daemon y en Android
sería un `WorkManager` (§10, fuera de alcance).

### 3.7 Precedencia de las señales (la tabla que resuelve las contradicciones)

| `platformHint()` | Heartbeat | Estado final | Lo que se ve |
|---|---|---|---|
| `Offline` | (no se intenta) | `Offline` | "Sin conexión" |
| `Online` / `Limited` | OK | `Online` | "Conectado" |
| `Online` | falla (timeout/5xx) | `Limited("El servidor no responde")` | "El servidor no responde" |
| `Limited` (portal) | OK | `Online` | "Conectado" (manda el heartbeat) |
| `Limited` (portal) | falla | `Limited("No se pudo verificar el servidor")` | "Sin conexión" |
| `Unknown` (sin servidor configurado) | n/a | `Unknown` | "Sólo local" |

### 3.8 Persistir el último estado conocido

Para que, después de cerrar y abrir la app, el banner no arranque mintiendo:

```kotlin
// config/netstate.json
@Serializable
data class NetworkSnapshot(
    /** Último estado conocido antes de cerrar. */
    val state: String = "Unknown",
    /** Cuándo se comprobó. */
    val checkedAt: Long = 0L,
    /** Cuándo fue el último heartbeat exitoso. */
    val lastOnlineAt: Long = 0L,
    /** Mensaje del último error (para el detalle). */
    val lastError: String = "",
)
```

En el arranque: `state = snapshot.state` hasta que haya un probe real, con el
chip mostrando *"Última conexión: hace 3 horas"* en vez de "Conectado". Un
"Conectado" que en realidad no se comprobó nunca es una mentira.

---

## 4. La cola de salida (`Outbox`)

### 4.1 Qué es

Una cola **persistente** de operaciones que la app quiso hacer contra el
servidor y no pudo. Vive en disco, sobrevive al cierre de la app y al cierre
de la máquina, y se vacía sola cuando hay red.

No es un `try/catch` con reintento: es una **cola con estado**, porque el
reintento tiene que sobrevivir al proceso.

### 4.2 La entrada

```kotlin
package kitoru.sync

/** Qué clase de operación es. */
enum class OutboxKind {
    /** Sube un hilo (cabecera). */
    UPLOAD_THREAD,

    /** Sube un mensaje de un hilo. */
    UPLOAD_MESSAGE,

    /** Envía una entrega de una tarea. */
    SUBMIT_ASSIGNMENT,

    /** Marca un aviso como leído. */
    MARK_ANNOUNCEMENT_READ,

    /** Sube la configuración de la cátedra (docente). */
    SYNC_COURSE,

    /** Sube un material (docente). Los bytes van aparte, en el archivo. */
    SYNC_MATERIAL,

    /** Borra un material (docente). */
    REMOVE_MATERIAL,

    /** Publica un aviso (docente). */
    PUBLISH_ANNOUNCEMENT,

    /** Publica una tarea (docente). */
    PUBLISH_ASSIGNMENT,

    /** Cambia la visibilidad de una inscripción (docente). */
    UPDATE_ENROLLMENT,

    /** Revoca una API key (docente). */
    REVOKE_KEY,
}

/** Estado de una entrada de la cola. */
enum class OutboxState(val label: String) {
    /** Espera su turno. */
    PENDING("Pendiente"),

    /** Se reintentó y falló de forma permanente; necesita intervención. */
    FAILED("No se pudo enviar"),

    /** Se descartó por caducidad. */
    DISCARDED("Descartada"),
}

/**
 * Una operación pendiente de enviar.
 *
 * El `id` lo genera el cliente y **es la clave de idempotencia**: el
 * servidor hace `upsert` por id, así que reenviar la misma entrada dos veces
 * no duplica nada.
 */
@Serializable
data class OutboxEntry(
    /** Id local (UUID v4). Se manda como `Idempotency-Key`. */
    val id: String,
    val kind: OutboxKind,
    val courseId: String = "",
    /** Id del objeto afectado (hilo, material, aviso…). */
    val targetId: String = "",
    /** Cuerpo de la operación, en JSON (el código exacto depende del kind). */
    val payload: String = "",
    /** Archivo asociado (material, entrega con archivo), relativo a `dataDir`. */
    val attachmentPath: String = "",
    /** Bytes del archivo asociado (para el progreso y el presupuesto). */
    val attachmentBytes: Long = 0L,

    val createdAt: Long = 0L,
    /** Intentos ya realizados. */
    val attempts: Int = 0,
    /** Cuándo se puede volver a intentar. */
    val nextAttemptAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    /** Último error (texto en español, listo para mostrar). */
    val lastError: String = "",
    /** 0 = no caduca. */
    val expiresAt: Long = 0L,
    val state: OutboxState = OutboxState.PENDING,
)
```

`payload` es un `String` y no un `JsonElement` a propósito: el store es un
`JsonListStore<OutboxEntry>` común, sin serialización polimórfica, y cada
`kind` tiene su propio `codec` que sabe leer y escribir ese `payload`.

### 4.3 Persistencia: en disco, y atómica

```
<dataDir>/outbox/
├── outbox.json          # la cola (JsonListStore<OutboxEntry>)
├── outbox.json.tmp      # temporal de la escritura atómica
└── outbox.log.jsonl    # bitácora (opcional): qué se envió, cuándo
```

**No hay carpeta de adjuntos.** El archivo que hay que subir (un material, una
entrega con archivo) **ya vive en su lugar definitivo**
(`courses/<id>/materials/…`, `courses/<id>/submissions/…`); la entrada de la
cola lo referencia con `attachmentPath` y guarda `attachmentBytes` para el
progreso. Copiarlo a `outbox/attachments/` duplicaría hasta cientos de
megabytes sin ganar nada.

**Escritura atómica.** `JsonStore._write` hace `fs.writeText(path, text)`, que
en `java.io.File` **trunca el archivo y después escribe**: un corte de luz en
medio de la escritura deja el JSON partido. Para un archivo de cola, eso
significa perder operaciones que el usuario ya dio por hechas. Por eso
este documento pide **una extensión mínima de `FileSystem`**:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt (modificado)
interface FileSystem {
    // … lo que ya existe …
    /**
     * Mueve (renombra) un archivo. Debe ser atómico cuando el sistema lo
     * permite: en el mismo volumen, `rename` es atómico.
     */
    fun move(from: FilePath, to: FilePath)
}
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt (modificado)
override fun move(from: FilePath, to: FilePath) {
    val src = f(from)
    val dst = f(to)
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {
        // Respaldo: copiar y borrar (no atómico, pero no se pierde nada).
        src.copyTo(dst, overwrite = true)
        src.delete()
    }
}
```

Y el store de la cola escribe con el patrón *escribir-temp → mover*:

```kotlin
// sync/src/commonMain/kotlin/kitoru/sync/OutboxStore.kt
package kitoru.sync

/**
 * Persistencia de la cola de salida.
 *
 * A diferencia del resto de los stores, **no** usa `JsonStore` tal cual:
 * escribe a un temporal y lo mueve, para que un corte de luz no deje la
 * cola corrupta. Si aun así el archivo queda ilegible (corte durante el
 * `move`, disco lleno), se recupera desde `outbox.log.jsonl`; si tampoco,
 * se empieza de cero **avisando al usuario** ("se perdieron N acciones
 * pendientes"), nunca en silencio.
 */
class OutboxStore(
    private val fs: FileSystem,
    private val dir: FilePath = KitoruPaths.outboxDir,
) {
    private val mutex = Mutex()
    private val file = dir.resolve("outbox.json")

    suspend fun read(): List<OutboxEntry>
    suspend fun write(entries: List<OutboxEntry>)   // atómico
    suspend fun update(transform: (List<OutboxEntry>) -> List<OutboxEntry>): List<OutboxEntry>
}
```

### 4.4 La política de reintento

**Backoff exponencial con jitter.** Determinista en la forma, impredecible en
el tiempo (para que 40 alumnos no le peguen al servidor en el mismo
milisegundo cuando vuelve la red).

```kotlin
package kitoru.sync

/**
 * Backoff exponencial con jitter.
 *
 * `delay(n) = min(base * 2^(n-1), max) * jitter`, con `jitter ∈ [0.8, 1.2]`.
 *
 * Es **función pura del intento y de un generador determinista**, así se
 * puede testear sin reloj y sin `Random` global.
 */
data class Backoff(
    val baseDelayMs: Long = 5_000,
    val maxDelayMs: Long = 300_000,
    val jitterRatio: Double = 0.2,
) {
    /**
     * Milisegundos a esperar antes del intento [attempt] (1 = el primero).
     * [jitter] es un valor en [0,1] provisto por el llamador (testeable).
     */
    fun delayFor(attempt: Int, jitter: Double = 0.5): Long {
        require(attempt >= 1) { "El intento arranca en 1." }
        val exp = baseDelayMs shl (attempt - 1).coerceAtMost(32)
        val capped = exp.coerceAtMost(maxDelayMs)
        val factor = 1.0 + (jitter * 2 - 1) * jitterRatio
        return (capped * factor).toLong().coerceAtLeast(1_000)
    }
}
```

Con los valores por defecto:

| Intento | Espera nominal | Con jitter (±20 %) |
|---|---|---|
| 1 | 5 s | 4 s – 6 s |
| 2 | 10 s | 8 s – 12 s |
| 3 | 20 s | 16 s – 24 s |
| 4 | 40 s | 32 s – 48 s |
| 5 | 80 s | 64 s – 96 s |
| 6 | 160 s | 128 s – 192 s |
| 7 | 300 s (tope) | 240 s – 360 s |
| 8+ | 300 s (tope) | 240 s – 360 s |

**Techo de intentos.** A los **10 intentos** la entrada pasa a `FAILED`: no se
insiste más en automático. Queda a la vista del usuario con su error y con
dos botones: *"Reintentar"* (resetea el contador) y *"Descartar"*.

> **Los intentos sólo avanzan mientras la app está abierta.** La cola se
> persiste, pero el reloj del reintento es el de los intentos, no el del
> paso del tiempo. Si el alumno cierra la app y la abre 8 horas después,
> la entrada tiene el mismo `attempts` que tenía: no se "quema" por haber
> estado dormida.

**Reintento inmediato al volver la red.** Cuando `NetworkMonitor.state` pasa
a `Online`, el coordinador **vacía la cola sin esperar el `nextAttemptAt`**
(pero sin resetear `attempts`). Si un intento falla, se recalcula el
`nextAttemptAt` con el backoff.

### 4.5 Clasificación de errores: qué se reintenta y qué no

```kotlin
package kitoru.sync

/** Por qué falló un envío. */
enum class FailureKind {
    /** No se pudo llegar al servidor (timeout, DNS, socket). */
    NETWORK,

    /** El servidor está vivo pero tuvo un problema (5xx, 429, 408). */
    SERVER_ERROR,

    /** El servidor rechazó la operación (4xx). No tiene sentido reintentar. */
    REJECTED,

    /** El cliente mandó algo inválido (payload corrupto, id duplicado). */
    INVALID,
}
```

| Respuesta | `FailureKind` | Qué hace la app | Texto para el usuario |
|---|---|---|---|
| Timeout / `UnknownHost` / socket | `NETWORK` | Reintenta con backoff | "Sin conexión con el servidor." |
| 500, 502, 503, 504 | `SERVER_ERROR` | Reintenta con backoff | "El servidor tuvo un problema. Reintentamos en {n}." |
| 429 | `SERVER_ERROR` | Reintenta, honrando `Retry-After` si viene | "El servidor está saturado. Reintentamos en {n}." |
| 401 / 403 | `REJECTED` | **No reintenta** → `FAILED` | "Tu sesión venció. Volvé a vincular tu cuenta en Ajustes." |
| 404 | `REJECTED` | **No reintenta** → `FAILED` | "El servidor no conoce este recurso (se borró del servidor)." |
| 409 | `REJECTED` | **No reintenta** → `FAILED` | "El servidor tiene una versión distinta de esto. Actualizá la cátedra." |
| 400 / 413 | `INVALID` | **No reintenta** → `FAILED` | "No se pudo enviar: {detalle del servidor}." |

El contador `attempts` se **reinicia** cuando un vaciado termina con al menos
un éxito: si el servidor está vivo, el problema anterior era de la red.

### 4.6 Caducidad y descarte

Cada `kind` tiene una **caducidad de negocio** (no técnica):

| `kind` | `expiresAt` | Al expirar |
|---|---|---|
| `MARK_ANNOUNCEMENT_READ` | 30 días | Se **descarta en silencio** (sólo se cuenta). A nadie le sirve saber que leíste un aviso hace dos meses |
| `UPLOAD_MESSAGE`, `UPLOAD_THREAD` | 90 días | Se descarta **con aviso**: "El chat del 3/3 no se pudo subir y se descartó." Chats viejos sin subir son casi siempre de un curso anterior |
| `SUBMIT_ASSIGNMENT` | Fecha de entrega + 24 h (`Assignment.lateUntil`) | Se marca `FAILED` **con aviso**: "La entrega de «TP2» venció antes de poder enviarse." Nunca se descarta en silencio: es una nota académica |
| `SYNC_COURSE`, `SYNC_MATERIAL`, `PUBLISH_*` (docente) | 180 días | Se descarta con aviso en el panel del docente |
| `REVOKE_KEY` | 24 h | Se descarta en silencio (revocar una key vieja no tiene sentido) |

La poda corre en el arranque, antes de cada vaciado y una vez por hora:

```kotlin
/**
 * Poda la cola: descarta lo que caducó.
 *
 * Devuelve lo que descartó para que la UI pueda avisar. Lo descartado **no
 * se borra del historial**: una entrega que caducó sigue en
 * `courses/<id>/submissions/`, marcada como "no entregada".
 */
data class PruneReport(
    val discarded: List<OutboxEntry>,
    val failed: List<OutboxEntry>,
)
```

### 4.7 Orden e idempotencia

**Orden.** Dos reglas:

1. **Por cátedra y por tipo, FIFO.** Los mensajes de un hilo se suben en
   orden de creación: el `ChatMessage` lleva `createdAt` y el servidor los
   ordena por `seq`. Si un mensaje queda en `FAILED`, los siguientes **no se
   bloquean** (se saltan los fallidos y se marcan en la UI como "hueco"), pero
   se avisa: *"Falta un mensaje en este hilo"*.
2. **Un vaciado a la vez.** Un `Mutex` por cátedra: dos subidas simultáneas
   del mismo curso pelearían por el orden.

**Idempotencia.** El id lo genera el cliente (`newId()` de
`core/platform/Ids.kt`, ya usado por los chats), así que:

- Reenviar la misma entrada **no duplica** (el servidor hace upsert por id).
- El `id` de la entrada viaja como cabecera `Idempotency-Key`, y el servidor
  puede devolver la respuesta original si la ve repetida.
- Un reintento después de un timeout en el que el servidor **sí** recibió el
  mensaje no genera mensajes repetidos.

### 4.8 El vaciado (`SyncCoordinator`)

```kotlin
package kitoru.sync

/**
 * Coordina la sincronización: heartbeat, vaciado de la outbox y descarga de
 * cambios del servidor.
 */
class SyncCoordinator(
    private val monitor: NetworkMonitor,
    private val outbox: Outbox,
    private val server: ServerClient,
    private val scope: CoroutineScope,
) {
    /** Vacía la cola. Devuelve cuántas entradas se enviaron. */
    suspend fun flush(): FlushResult

    /** Refresca desde el servidor (manifiesto → delta). */
    suspend fun pull(courseId: String): PullResult

    /** Dispara un vaciado con debounce (agrupa ráfagas de escrituras). */
    fun requestFlush(delayMs: Long = 3_000)
}
```

Cuándo se dispara un vaciado:

| Disparador | Detalle |
|---|---|
| Arranque de la app | Si el estado es `Online`, vaciado immediately |
| La red pasa a `Online` | Vaciado inmediato, ignorando `nextAttemptAt` |
| Una escritura local con contraparte remota | Debounce de 3 s (un chat genera varias entradas: hilo + mensajes) |
| Periódico | Cada 60 s con la app abierta y la cola no vacía |
| "Sincronizar ahora" (manual) | Inmediato, con spinner |
| Cierre de la app | Mejor esfuerzo, con un presupuesto de 2 s |

### 4.9 Qué ve el usuario de la cola

En el pie de la pantalla de la cátedra, cuando hay cola:

```
┌──────────────────────────────────────────────────────────────┐
│ ⏳ 3 cambios pendientes de enviar                             │
│    "Tú preguntaste sobre el teorema de Bayes"  · hace 2 min   │
│    Se enviarán solos cuando vuelva la conexión.               │
│    [ Ver los 3 ]  [ Reintentar ahora ]                        │
└──────────────────────────────────────────────────────────────┘
```

Si hay entradas en `FAILED`:

```
┌──────────────────────────────────────────────────────────────┐
│ ⚠ 1 cambio no se pudo enviar                                   │
│    "Entrega de TP2" — El servidor no acepta el archivo (413).  │
│    [ Ver detalle ]  [ Reintentar ]  [ Descartar ]             │
└──────────────────────────────────────────────────────────────┘
```

### 4.10 El outbox del docente (resumen; en detalle §7)

El mismo `Outbox`, con los kinds de docente. La diferencia de fondo:

| | Alumno | Docente |
|---|---|---|
| Quién espera | El alumno | El **alumno**, que no sabe que el docente está sin red |
| Qué pasa si no se sube | El alumno no ve su chat en el servidor (el docente no lo analiza) | **Los alumnos no tienen el material ni el aviso** |
| Prioridad | Ninguna | **Alta**: `SYNC_MATERIAL` y `PUBLISH_ANNOUNCEMENT` van primero |
| UI | Chip discreto | Panel con progreso y advertencia explícita |

---

## 5. El modo "sólo lectura"

### 5.1 Cómo se deriva

```
ConnectionState.Online                → SyncMode.FULL       → todo habilitado
ConnectionState.Offline / Limited     → SyncMode.READ_ONLY  → lectura + cola
sin servidor configurado              → SyncMode.LOCAL_ONLY → todo local, sin cola
```

El modo es **derivado**, no un estado que se setea a mano: no puede quedar
"desincronizado" con la realidad.

### 5.2 Qué se puede hacer y qué no, en `READ_ONLY`

| Acción | ¿Se puede? | Qué pasa |
|---|---|---|
| Leer material, avisos, tareas, chats, config | ✅ | Todo desde la copia local |
| **Buscar en el material** | ✅ | Índice local |
| Chatear con la **IA local** | ✅ | El hilo se escribe local; la subida queda en la cola |
| Chatear con la **IA alojada** | ❌ | No hay modelo: es un error de producto, no de red (§5.4) |
| Editar la config de la cátedra (docente) | ✅ | Se guarda local y se encola el `SYNC_COURSE` |
| Publicar un aviso (docente) | ✅* | Se encola; **el aviso no existe para los alumnos hasta que se sube** |
| Subir material (docente) | ✅* | Se encola; **los alumnos no lo tienen hasta que se sube** |
| Enviar una entrega | ✅* | Queda en cola; se ve como "Entrega guardada, pendiente de envío" |
| Marcar un aviso como leído | ✅* | Se encola un `MARK_ANNOUNCEMENT_READ` (es ruido: por eso caduca a los 30 días) |
| Borrar algo del servidor | ✅* | Se encola (o se descarta si el servidor no lo soporta) |

(*) El patrón es siempre el mismo: **se permite la acción local, se encola la
remota, y la UI dice que es una operación pendiente**. La app no pone un
botón deshabilitado sin explicación: pone el botón, y al apriarlo dice qué
pasó.

### 5.3 El banner

Un solo banner, arriba de todo, con un estado por vez:

```
┌────────────────────────────────────────────────────────────────────┐
│ ⚠ Sin conexión con el servidor.                                      │
│   Estás viendo la copia local de la cátedra (actualizada el 12/10). │
│   Lo que escribas se guarda y se envía solo cuando vuelva.          │
│                                     [ Reintentar ahora ]  [ ✕ ]     │
└────────────────────────────────────────────────────────────────────┘
```

Variantes:

| Estado | Banner |
|---|---|
| `Offline` | "Sin conexión con el servidor." |
| `Limited("El servidor no responde")` | "El servidor no responde. Puede ser que esté apagado." |
| `Limited("Tu sesión venció")` | "Tu sesión venció. [Volver a vincular]" |
| `LOCAL_ONLY` | "Estás trabajando sin servidor: nada de lo que hagas llega a tu docente." |
| `FULL` | Ninguno (o un chip chico con el último sync) |

El banner **se puede cerrar** (la app recuerda que se cerró), pero el estado
sigue visible en el chip de la barra de título. Cerrar el banner no cambia el
modo: es sólo ruido visual.

### 5.4 El chat sin red: los dos casos, sin mezclarlos

Esto se confunde seguido y hay que separar los dos casos con cuidado:

| | IA local (`ModelPolicy.mode = LOCAL`) | IA alojada (`REMOTE`) |
|---|---|---|
| ¿El modelo corre en el dispositivo? | Sí (`llama-server`) | No (está en el servidor) |
| ¿El chat funciona sin red? | **Sí, completo** | **No** |
| ¿Qué hace la app? | Responde con el corpus local; guarda el hilo; encola la subida | Muestra el error y ofrece el fallback |
| Texto al usuario | (nada, funciona) | "El modelo de esta cátedra está en el servidor de la institución. Sin conexión no se puede chatear. Podés seguir leyendo el material; si tenés un modelo local configurado, cambialo en Ajustes." |

Y un detalle importante de **producto**, que es el corolario del modo local:

> Cuando el modelo es local y la red no está, **el chat no pierde nada**. El
> hilo se escribe en `courses/<id>/chats/…` antes de llamar al modelo (se
> escribe el mensaje del usuario, se genera, se escribe la respuesta). Si el
> proceso se muere a mitad de camino, queda el mensaje del usuario y el hilo se
> puede retomar. La subida al servidor es **una tarea posterior**, no parte de
> la respuesta.

---

## 6. La primera ejecución sin red

### 6.1 El flujo completo

```
1. Arranque
   ├─ core init (local)                ← sin red
   ├─ identidad local                  ← sin red
   └─ NetworkMonitor.start()           ← en paralelo, no bloquea

2. "Creá tu identidad" (09-identidad.md)
   → nombre, email opcional, idioma, zona horaria
   → UserProfile + device.json
   → SIN RED. Nunca se pide nada remoto acá.

3. "Entrá a una cátedra"
   → el alumno escribe el código (ej. "IA-7F3K")

   ┌─ ¿La cátedra está en el store local?
   │     SÍ → se inscribe (todo local) → paso 4
   │
   ├─ ¿Hay un bundle .kitoru importado?
   │     SÍ → se inscribe desde el bundle → paso 4
   │
   └─ NO → se consulta el directorio del servidor
         ├─ hay red y responde → inscripción normal (remota)
         └─ NO HAY RED → mensaje honesto (§6.2)
```

### 6.2 Qué ve y qué se le dice

En el caso "código desconocido + sin red":

```
┌──────────────────────────────────────────────────────────────────────┐
│  No encontramos esa cátedra                                        │
│                                                                      │
│  El código IA-7F3K no corresponde a ninguna cátedra en este          │
│  dispositivo, y no pudimos consultar al servidor de la institución.  │
│                                                                      │
│  Podés:                                                              │
│   • Volver a intentar cuando tengas conexión.                       │
│   • Pedirle a tu docente el archivo de la cátedra (.kitoru) e       │
│     importarlo: funciona sin conexión.                              │
│                                                                      │
│  [ Importar archivo… ]  [ Reintentar ]                               │
└──────────────────────────────────────────────────────────────────────┘
```

Dos decisiones acá:

- **No se inventa un error de "código inválido"**. El código puede ser
  perfectly válido; lo que no sabemos es si existe. Decir "código inválido"
  sería mentir (`09-identidad.md` §6.1 dice lo mismo).
- **Se ofrece el camino que sí funciona**: el bundle `.kitoru`. Es la
  respuesta honesta y además conecta con §7 (el bundle es la alternativa al
  servidor).

### 6.3 La API key no se puede validar

El botón "Probar conexión" (`teacher/05-config-modelo.md` §4) hace
`GET {base}/models`. Sin red, la UI muestra el error del `LlmException`… y
**no bloquea nada**:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Estado de la clave:  ⚠ Sin validar                                   │
│  Última validación: nunca                                             │
│  No pudimos verificar la clave porque no hay conexión con el          │
│  servidor. La app funciona igual; la clave se valida sola cuando       │
│  vuelva la conexión.                                                   │
│  [ Probar ahora ]                                                      │
└────────────────────────────────────────────────────────────────────────┘
```

Consecuencias:

- El chat **no se bloquea** por una key sin validar. Si el servidor la
  acepta, el chat funciona; si no, el error sale del gateway (`LlmException`
  con 401), que es un mensaje mucho mejor que "la clave no está validada".
- La validación automática se reintenta sola (mismo backoff de §4.4) con el
  próximo heartbeat exitoso.
- La key **nunca se descarta** por no poder validarse: perder la key porque
  el servidor estaba caído sería imperdonable.

### 6.4 El corpus no se puede bajar

La cátedra aparece en la lista, con el chip de estado del material:

```
┌────────────────────────────────────────────────────────────────────┐
│  Inteligencia Artificial · IA-2026-1                               │
│  ● Sin conexión                                                     │
│  📄 Material pendiente de descarga (0 de 12)                        │
│     [ Descargar cuando haya conexión ]                             │
└────────────────────────────────────────────────────────────────────┘
```

Y si el alumno abre el chat sin material:

- El chat **funciona** (con IA local), pero sin RAG: responde con lo que
  sabe, y la UI lo dice: *"Ahora no tengo el material de la cátedra, así que
  no puedo citarte los apuntes. Va a volver cuando se descargue."*
- El banner de la cátedra muestra el mismo estado.

### 6.5 La lista completa de "no hay red" en el primer arranque

| Momento | Qué pasa | Qué ve |
|---|---|---|
| Arranque | No se consulta la red de forma bloqueante | La app abre en < 1 s |
| Crear identidad | Todo local | — |
| Buscar cátedra | Falla la consulta al servidor | Mensaje honesto + opción de importar bundle |
| Validar key | Falla | "Sin validar", la app sigue |
| Descargar corpus | Falla | "Material pendiente de descarga (0 de 12)" |
| Cargar avisos | Falla | "0 avisos" + chip "sin conexión" |
| Chatear (IA local) | Funciona | Sin RAG si no hay material |
| Chatear (IA alojada) | Falla | Mensaje de §5.4 |

**Ninguno de estos casos es una pantalla de error.** Son estados con
explicación y acción. El único caso donde la app **no** puede hacer lo que
el usuario quiere (inscribirse a una cátedra que no conoce) se resuelve
ofreciendo el bundle, no dejando al usuario en un error.

---

## 7. El docente sin red

### 7.1 Qué puede hacer el docente sin red

**Todo lo que es diseño de la cátedra.** El docente no es un "cliente que
depende del servidor": es el **dueño** del contenido, y su trabajo es
offline-first por naturaleza.

| Acción | ¿Sin red? | Dónde queda |
|---|---|---|
| Crear la cátedra | ✅ | `config/courses.json` |
| Configurar el modelo (REMOTE/LOCAL, parámetros, campos bloqueados) | ✅ | `config/courses.json` |
| Escribir el prompt de la cátedra | ✅ | ídem |
| Subir material (PDF, slides, apuntes) | ✅ | `courses/<id>/materials/` + extracción de texto + índice |
| Indexar / reindexar | ✅ | `courses/<id>/index.json` (local) |
| Escribir un aviso | ✅ | `config/announcements.json` + outbox `PUBLISH_ANNOUNCEMENT` |
| Publicar una tarea | ✅ | `config/assignments.json` + outbox |
| Ver el roster | ✅ | `config/enrollments.json` |
| Ver la analítica de lo ya descargado | ✅ | `courses/<id>/aggregate.json` |
| **Ver los chats de los alumnos** | ❌ | Requiere red (el servidor los tiene) |
| **Que los alumnos vean lo que publicó** | ❌ (hasta que se sube) | Outbox |

### 7.2 El outbox del docente

Es el **mismo `Outbox`**, con los kinds de docente. Lo que cambia es la
**prioridad** y la **visibilidad**.

```kotlin
package kitoru.sync

/**
 * Prioridad de una entrada de la cola.
 *
 * Las operaciones del docente van primero: si el docente acaba de subir
 * material, lo que el alumno necesita es el material, no una marca de
 * "leído" de hace tres semanas.
 */
enum class OutboxPriority(val order: Int) {
    /** Ruido: lecturas de avisos. */
    LOW(0),

    /** Contenido del alumno: mensajes, entregas. */
    NORMAL(1),

    /** Contenido de la cátedra: material, avisos, config. */
    HIGH(2),
}
```

Reglas del outbox docente:

1. **Orden FIFO dentro de la misma cátedra.** El docente sube un material,
   publica un aviso que lo adjunta y cambia la config: el orden importa. No
   hay paralelismo dentro de una cátedra.
2. **Alta prioridad primero entre cátedras.** Con varias cátedras, la cola
   se ordena por `priority` y después por `createdAt`. Un `SYNC_MATERIAL`
   (HIGH) sale antes que un `MARK_ANNOUNCEMENT_READ` (LOW).
3. **El archivo viaja por referencia, no copiado.** `SYNC_MATERIAL` guarda en
   la entrada el `attachmentPath` (relativo a `dataDir`, apuntando a
   `courses/<id>/materials/…`) y el `attachmentBytes`. El archivo real **no
   se copia**: ya está en su lugar definitivo (§4.3). La entrada apunta ahí.
4. **Reintento con las mismas reglas** que el alumno: mismo backoff, mismo
   tope de 10 intentos, misma caducidad (180 días).
5. **El `413` (payload demasiado grande) es un caso especial**: un material
   de 80 MB puede ser rechazado. La app avisa: *"«Clase 4.pdf» pesa 80 MB y
   el servidor no acepta archivos de más de 50 MB. Subilo comprimido o
   dividilo."* (El límite lo define el servidor; el cliente lo muestra.)

### 7.3 El panel del docente

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ⚠ Sin conexión con el servidor.                                            │
│   4 cambios pendientes de subir. Los alumnos NO tienen este material        │
│   hasta que se suba.                                                        │
│                                                                             │
│   📄 Subir material          «Clase 4.pdf» (12 MB)              [ archivo ]  │
│   📝 Publicar aviso          "Clase del martes cancelada"                  │
│   ⚙ Actualizar config       Prompt de la cátedra                          │
│                                                                             │
│   [ Subir todo cuando vuelva ]   [ Ver detalle ]                          │
└────────────────────────────────────────────────────────────────────────────┘
```

La frase **"Los alumnos NO tienen este material hasta que se suba"** es
obligatoria: es la información que el docente necesita y que ninguna otra
parte de la app le da.

### 7.4 Corolario: el servidor es el punto de distribución

Este es el punto que hay que dejar escrito sin rodeos, porque es la
consecuencia arquitectónica de todo lo anterior:

> **Si el material existe sólo en el dispositivo del docente y los alumnos
> necesitan el servidor para obtenerlo, entonces Kitoru-Server es el punto de
> distribución — o hay que inventar otro canal.**

El diseño offline-first del **cliente** no resuelve el problema de la
**distribución**. Son dos problemas distintos:

- Offline-first del cliente = el alumno puede trabajar aunque el servidor
  esté caído. ✔ (resuelto por §1–§5)
- Distribución del contenido = cómo llega el material del docente a los
  alumnos. ✘ (esto necesita un canal)

El corolario es directo: **el Kitoru-Server no es opcional en el escenario
"docente con material + alumnos que chatean con ese material"**. Sin él, la
única distribución posible es manual (el bundle).

Esto tiene una consecuencia operativa que hay que anticipar:

> **El Kitoru-Server es una app de escritorio.** Si corre en la notebook del
> docente, cuando el docente apaga la notebook los alumnos no pueden
> sincronizar. Un servidor que es "la distribución del curso" tiene que estar
> **siempre encendido** en algún lado (una VM de la facultad, un servidor de
> la cátedra, un contenedor). Si no, la alternativa realista es el bundle
> `.kitoru` reenviado a mano.

**Y una consecuencia de diseño:** la app del docente tiene que **avisar**
cuando su material todavía no llegó al servidor, y el alumno tiene que poder
**ver** que el material que tiene puede estar viejo. De ahí los chips
"Material pendiente de descarga" y "copia local del 12/10".

### 7.5 Alternativas de distribución (y por qué el servidor gana)

| Alternativa | Cómo funciona | Cuándo sirve | Limitaciones |
|---|---|---|---|
| **Kitoru-Server (hub)** | El docente sube (push); los alumnos bajan (pull), con manifiesto y versiones | **El caso general**: muchas cátedras, actualizaciones, registro de alumnos | Requiere un servidor siempre encendido y alcanzable (LAN de la facultad o internet) |
| **Bundle `.kitoru`** | El docente exporta un archivo con `Course` + `ModelPolicy` + materiales y lo comparte (drive, LMS, pendrive, QR). El alumno lo **importa sin red** | Curso chico, primer día, cátedra sin servidor, o **plan B** cuando el servidor está caído | Es una **foto**: no hay actualizaciones; hay que reimportar a mano; no escala a 200 alumnos |
| **Carpeta compartida / bucket** | El docente sube los archivos a una carpeta de red o un bucket con índice JSON; el alumno consulta un `index.json` por HTTP | Un curso, pocos alumnos, sin servidor que mantener | Sin autenticación; el alumno necesita internet (o estar en la red de la facultad); el "servidor" es un directory listing, no un sistema |
| **P2P (LAN / Bluetooth / USB)** | Los alumnos bajan el material del docente directamente | Laboratorio sin internet | Criptografía, descubrimiento, versiones: enorme complejidad para un caso que el bundle ya cubre |
| **Correo / mensajería** | El docente manda el material por mail | Casos puntuales, material chico | Ilimitado en tamaño, sin estructura, no sincronizable |

**Recomendación:**

1. **El Kitoru-Server es el punto de distribución** (caso general). La app
   del docente sube con push; la del alumno baja con pull.
2. **El bundle `.kitoru` es el plan B y el mecanismo del primer día**: siempre
   disponible, funciona con la app instalada y sin nada más. Un docente
   siempre puede exportar un bundle, incluso con el servidor arriba (para un
   alumno con una conexión de Rho).
3. **La carpeta compartida queda como opción de institución** para un
   laboratorio o una facultad sin servidor: es un `.kitoru` actualizado
   periódicamente, descargado por HTTP.

Lo que **no** se hace: un modo "distribución peer-to-peer" automático. La
complejidad no se justifica con un bundle que ya resuelve el caso.

### 7.6 El aviso al docente cuando publica sin red

Cuando el docente aprieta "Publicar" sin red, la app **no** dice "publicado".
Dice:

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Aviso guardado en este dispositivo.                                      │
│  Se publica en el servidor cuando vuelva la conexión.                    │
│  Hasta entonces, los alumnos no lo ven.                                  │
│                                                                             │
│  [ Publicar ahora (reintentar) ]   [ Publicar igual y avisar después ]   │
└────────────────────────────────────────────────────────────────────────────┘
```

El botón "Publicar ahora" fuerza un intento (y si falla, sigue encolado).

---

## 8. Consumo de espacio

### 8.1 Estimaciones

Medido sobre el código de FunshiEngineAI (extracción de PDF) y estimado sobre
cátedras típicas:

| Ítem | Por unidad | 1 cátedra típica | 10 cátedras |
|---|---|---|---|
| Material original (PDF/slides) | 2 – 8 MB | 40 archivos → **160 MB** | 1,6 GB |
| Texto extraído (`extractedText`) | 3 – 8 % del PDF | **8 MB** | 80 MB |
| Chunks (JSON en disco, con solapamiento) | ~1,15 × el texto | **9 MB** | 90 MB |
| Índice BM25 **en RAM** | ~4 × el texto | **32 MB** | 320 MB |
| Avisos (JSON) | 2 – 6 KB | 30 avisos → **120 KB** | 1,2 MB |
| Hilos de chat (con citas) | 20 – 80 KB | 50 hilos → **2 MB** | 20 MB |
| Entregas (texto + archivos) | 0,1 – 20 MB | 10 entregas → **15 MB** | 150 MB |

**Presupuesto típico:** ~180 MB en disco (tier 1) y ~32 MB de índice en RAM
por cátedra. En RAM sólo vive el índice de la cátedra abierta (las demás se
descargan al cambiar de cátedra).

### 8.2 El presupuesto

```kotlin
package kitoru.sync

/**
 * Presupuesto de disco de la caché (tier 2).
 *
 * El tier 1 (materiales, chats, entregas) **no entra en el presupuesto**:
 * es material del usuario. El presupuesto gobierna las réplicas descartables.
 */
data class CacheBudget(
    /** Tope de la caché descartable. */
    val maxBytes: Long,
    /** Se empieza a purgar cuando se pasa este umbral (90 % por defecto). */
    val highWaterMark: Double = 0.9,
) {
    companion object {
        /**
         * Presupuesto por defecto: 10 % del espacio libre del volumen, entre
         * 256 MB y 2 GB.
         *
         * @param freeBytes espacio libre del volumen donde vive `dataDir`.
         */
        fun default(freeBytes: Long): CacheBudget = CacheBudget(
            maxBytes = (freeBytes / 10).coerceIn(256L * 1024 * 1024, 2L * 1024 * 1024 * 1024),
        )
    }
}
```

El usuario puede ajustarlo en Ajustes → Espacio: "Caché: 340 MB de 2 GB"
con un slider (256 MB / 512 MB / 1 GB / 2 GB / 5 GB / sin límite).

### 8.3 Limpieza LRU

La caché de tier 2 (`cache/`) se purga por **LRU sobre `lastAccessAt`**.

```kotlin
package kitoru.sync

/**
 * Índice de la caché: qué hay, cuánto pesa y cuándo se usó por última vez.
 */
@Serializable
data class CacheEntry(
    val key: String,           // CacheKey.flat
    val kind: CacheKind,
    val courseId: String = "",
    /** Ruta del archivo (relativa a `cacheDir`). */
    val path: String = "",
    /** Bytes occupied. */
    val bytes: Long = 0L,
    /** Último acceso (epoch millis). Es lo que ordena el LRU. */
    val lastAccessAt: Long = 0L,
    /** Un recurso marcado `pinned` no se purga automáticamente. */
    val pinned: Boolean = false,
    /** Vencimiento (TTL). 0 = no vence. */
    val expiresAt: Long = 0L,
    /** Hash del contenido (para no volver a bajarlo). */
    val sha256: String = "",
)

@Serializable
data class CacheIndex(
    val entries: List<CacheEntry> = emptyList(),
    /** Versión del formato del índice. */
    val version: Int = 1,
)
```

Reglas de purga:

1. **Nunca se purga un recurso `ALWAYS`.** Es material del usuario, no caché.
2. **Nunca se purga un `pinned`.** El docente puede fijar un material para
   que no se borre.
3. **Se purga por `lastAccessAt` ascendente**, ignorando lo anterior.
4. **Se purga sólo hasta el 90 % del presupuesto.** Nunca se borra todo: el
   objetivo es bajar el uso, no vaciar la caché.
5. **La purga corre** al arrancar, después de cada descarga y cuando se pasa
   el umbral.

```kotlin
/**
 * Purga la caché por LRU.
 *
 * @param except recursos que no se pueden tocar (pinned, ALWAYS).
 * @return los recursos purgados.
 */
suspend fun evictLru(
    usage: CacheUsage,
    index: List<CacheEntry>,
    budget: CacheBudget,
    except: Set<String> = emptySet(),
): List<CacheEntry>
```

### 8.4 Lo que no se borra automáticamente

| Recurso | ¿Se purga? | Por qué |
|---|---|---|
| Materiales del corpus (tier 1) | **No** | Es la materia prima de la búsqueda sin red. Borrarlo rompe la §1.3 |
| Chats del alumno | **No** | Son datos del usuario |
| Entregas | **No** | Son datos académicos |
| Config de la cátedra | **No** | Pequeña y essencial |
| Réplicas de lectura (avisos, tareas, chats ajenos) | **Sí**, por LRU | Se pueden volver a bajar |
| Descargas en curso (archivos a medio bajar) | **Sí** | Restos de una descarga interrumpida |

Para liberar espacio del corpus hay una acción **explícita** en Ajustes →
Espacio: *"Liberar material de «Inteligencia Artificial» (160 MB)"*, con la
advertencia: *"La búsqueda en el material va a dejar de funcionar hasta que
vuelvas a descargarlo."* Es una decisión del usuario, no una automatización.

### 8.5 La pantalla "Espacio"

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Espacio                                                                    │
│                                                                             │
│ Material de las cátedras        180,2 MB    ████████░░░░░░░░  (tier 1)     │
│   IA-2026-1                       160,0 MB                                  │
│   Redes                           20,2 MB                                  │
│ Conversaciones y entregas         17,1 MB    ███░░░░░░░░░░░░░  (tier 1)    │
│ Réplicas del servidor               4,3 MB    █░░░░░░░░░░░░░░  (tier 2)    │
│   Avisos                            0,1 MB                                  │
│   Chats de alumnos (descargados)    4,2 MB                                  │
│                                                                             │
│ Caché descartable: 4,3 MB de 2 GB      [ Liberar caché ]                   │
│                                                                             │
│ [ Liberar material de IA-2026-1 ]   ← con advertencia                        │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Archivos a crear o tocar

### Nuevos — módulo `:sync`

| Archivo | Contenido |
|---|---|
| `sync/build.gradle.kts` | Módulo KMP; `api(project(":core"))`; Ktor `cio` en desktop y `okhttp` en Android (igual que `:llm-api`) |
| `sync/src/commonMain/kotlin/kitoru/sync/Connection.kt` | `ConnectionState`, `SyncMode`, `ConnectionSnapshot`, `toSyncMode()` |
| `sync/src/commonMain/kotlin/kitoru/sync/NetworkMonitor.kt` | Interfaz `NetworkMonitor`, `expect createNetworkMonitor(...)`, implementación común |
| `sync/src/commonMain/kotlin/kitoru/sync/Heartbeat.kt` | `Heartbeat` (Ktor), `ServerEndpoint` (`host`, `port`, `baseUrl`) |
| `sync/src/commonMain/kotlin/kitoru/sync/CachePolicy.kt` | `CachePolicy`, `CacheKind`, `CacheKey`, `CachePolicies` |
| `sync/src/commonMain/kotlin/kitoru/sync/CacheEntry.kt` | `CacheEntry`, `CacheIndex`, `CacheUsage` |
| `sync/src/commonMain/kotlin/kitoru/sync/CacheManager.kt` | Lectura/escritura, `touch`, `evictLru`, `usage()` |
| `sync/src/commonMain/kotlin/kitoru/sync/Outbox.kt` | `OutboxEntry`, `OutboxKind`, `OutboxState`, `OutboxPriority` |
| `sync/src/commonMain/kotlin/kitoru/sync/OutboxStore.kt` | Persistencia atómica + bitácora |
| `sync/src/commonMain/kotlin/kitoru/sync/OutboxService.kt` | `enqueue`, `flush`, `prune`, `retry`, `discard` |
| `sync/src/commonMain/kotlin/kitoru/sync/RetryPolicy.kt` | `Backoff`, `FailureKind`, clasificación de errores |
| `sync/src/commonMain/kotlin/kitoru/sync/SyncCoordinator.kt` | Orquesta heartbeat + flush + pull |
| `sync/src/commonMain/kotlin/kitoru/sync/ServerClient.kt` | Cliente Ktor del Kitoru-Server (`/v1/…`) |
| `sync/src/desktopMain/kotlin/kitoru/sync/NetworkMonitor.desktop.kt` | `Socket` al host del servidor |
| `sync/src/androidMain/kotlin/kitoru/sync/NetworkMonitor.android.kt` | `ConnectivityManager` |

### Modificados

| Archivo | Cambio |
|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt` | `setDataDir(dataDir, cache, outbox)`; `outboxDir` |
| `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` | `move(from, to)` para escrituras atómicas |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt` | `actual` de `move` (`File.renameTo` + respaldo) |
| `settings.gradle.kts` | `include(":sync")` |
| `android/app/src/main/AndroidManifest.xml` | `ACCESS_NETWORK_STATE`, `INTERNET` |
| `core/src/commonMain/kotlin/kitoru/domain/JsonStore.kt` | (opcional) `JsonStore.writeAtomic` reutilizando `FileSystem.move` |
| `android/…/KitoruPaths` init | `setDataDir(filesDir/kitoru, cacheDir/kitoru, filesDir/kitoru/outbox)` |
| `desktop/…/KitoruPaths` init | `setDataDir(~/.kitoru, ~/.kitoru/cache, ~/.kitoru/outbox)` |

### Nuevos — tests

| Archivo | Qué testea |
|---|---|
| `sync/src/desktopTest/kotlin/kitoru/sync/BackoffTest.kt` | Secuencia de esperas, tope, jitter, `attempt = 0` falla |
| `sync/src/desktopTest/kotlin/kitoru/sync/OutboxTest.kt` | Encolar, reintentar, backoff, caducidad, descarte, orden FIFO, persistencia round-trip |
| `sync/src/desktopTest/kotlin/kitoru/sync/CacheManagerTest.kt` | LRU: no purga `ALWAYS` ni `pinned`; purga por `lastAccessAt`; respeta el presupuesto |
| `sync/src/desktopTest/kotlin/kitoru/sync/SyncCoordinatorTest.kt` | Con un `ServerClient` falso: sin red la cola crece; con red se vacía en orden; un 401 deja la entrada en `FAILED` |
| `sync/src/desktopTest/kotlin/kitoru/sync/ConnectionTest.kt` | Derivación de `SyncMode`, precedencia de señales |

---

## 10. Casos borde

| Caso | Comportamiento |
|---|---|
| La app se cierra con 5 entradas en la cola | Al reabrir, la cola está ahí (archivo atómico) y se reintenta |
| El corte de luz ocurre durante la escritura de la cola | Se escribe `outbox.json.tmp` y se mueve; el archivo anterior queda íntegro. Si aun así queda corrupto, se recupera de `outbox.log.jsonl` y se avisa |
| El servidor responde 200 pero la respuesta se pierde (timeout) | El cliente reintenta; el servidor deduplica por `id` (upsert) → no se duplica |
| El servidor devuelve 401 a mitad de un vaciado | Las entradas que fallaron con 401 pasan a `FAILED` con el mensaje "tu sesión venció"; las demás siguen su curso |
| El alumno tiene IA local y no hay red | El chat funciona completo; el hilo queda local; la subida espera |
| El alumno tiene IA alojada y no hay red | El chat no funciona; el mensaje es explícito; el material sigue legible |
| El docente sube 200 MB de material sin red | Se encola; el cliente avisa si el servidor va a rechazar por tamaño (413) cuando se suba |
| El servidor cambia de dirección (el docente lo reconfigura) | `serverProvider` se lee en cada chequeo: el monitor y la cola siguen al servidor nuevo; las entradas que fallaron con 404 pasan a `FAILED` |
| El disco se llena durante una descarga | La descarga se corta, el archivo parcial queda en `cache/` (tier 2) y se purga por LRU; la entrada de la cola queda `PENDING` |
| La red vuelve después de 3 horas | El monitor pasa a `Online` → flush inmediato (sin esperar el backoff) → la UI muestra "Sincronizado hace un instante" |
| Un mensaje quedó en `FAILED` y los siguientes son del mismo hilo | Los siguientes se suben igual; la UI marca el hueco ("falta un mensaje en este hilo") |
| La cátedra se cierra (el docente la cerró) | Los mensajes pendientes se marcan `FAILED` ("la cátedra está cerrada"); no se reintentan para siempre |
| El usuario borró el material a mano desde el explorador | El manifiesto dice que falta → se re-baja; el índice se reindexa |
| Hay dos instancias de la app abiertas (desktop) | El `OutboxStore` usa un `Mutex` **por proceso**: dos procesos podrían pisarse. Se documenta el límite (una sola instancia) en vez de inventar un lock entre procesos |
| El reloj del sistema salta hacia adelante | `expiresAt` se evalúa contra `nowMillis()`: un salto adelante descarta entradas. Se acepta: el reloj del sistema es la fuente de verdad del calendario de la app |
| El heartbeat falla pero el chat con IA local funciona | `ConnectionState` es `Limited`, la app muestra el banner… pero el chat local no se ve afectado. El banner dice "el **servidor** no responde", no "no tenés internet" |

---

## 11. Fuera de alcance (explícito)

- **Detección de "hay internet" por DNS/ICMP** a hosts públicos: no dice nada
  útil y es un pedido a un tercero en cada chequeo.
- **Sondeo en background con la app cerrada**: en desktop no hay daemon; en
  Android sería `WorkManager` (15 min mínimo) y lo deja el subsistema de
  avisos (`07-anuncios.md` §5.3). No se duplica acá.
- **Sincronización diferencial de los chats** (diff de mensajes): se sube por
  `upsert` de mensajes con id de cliente; no hace falta un diff.
- **Resolución de conflictos**: en el modelo actual el cliente genera los ids
  y el servidor hace upsert; no hay Editiones concurrentes del mismo objeto
  desde dos dispositivos. Si aparece, se resuelve con `updatedAt` del
  servidor (last-write-wins) y se avisa.
- **Caché cifrada**: los archivos del corpus son material del curso, no
  secretos. La API key **sí** va en el `CredentialStore` (`09-identidad.md`
  §8.2), que es un tema aparte.
- **Distribución peer-to-peer** del material (§7.5).
- **Modo "sólo lectura" forzado por el usuario** (un flag "no me mandes
  nada"): es una función útil y chica, pero es una política de privacidad,
  no de caché. Vive en el `Enrollment.visibility` y en el
  `CredentialStore`, no acá.
- **Cuotas por cátedra configurables por el docente**: la cuota global de
  caché alcanza; afinar por cátedra es un refinamiento posterior.

---

## 12. Resumen operativo

1. **La app abre y trabaja sin red.** Todo lo local es local; la red sólo
   agrega.
2. **La búsqueda en el material es local, siempre** — es la función que
   justifies todo el diseño.
3. **Lo que hay que enviar se encola** (`Outbox`), con backoff exponencial y
   jitter, tope de 10 intentos y caducidad por tipo de operación.
4. **El estado de conexión** es la combinación de la pista del SO
   (`expect/actual`: `ConnectivityManager` en Android, `Socket` en desktop) y
   un heartbeat HTTP de 4 s al servidor, que es el que manda.
5. **El servidor es el punto de distribución del material.** El bundle
   `.kitoru` es el plan B y el mecanismo del primer día.
