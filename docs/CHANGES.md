# Changelog

Todos los cambios de Kitoru se anotan acá. El formato sigue el de
FunshiEngineAI: por versión, con el motivo y no sólo la lista de archivos.

Las versiones usan SemVer. Durante el desarrollo, `0.x.y`: el `0` significa
"la API todavía puede cambiar".

---

## [0.1.0] — Base multiplatform

Primer hito: el proyecto compila para escritorio y Android, el dominio
está modelado, y el RAG, el bucle de herramientas y el verificador de citas
de FunshiEngineAI están portados al código común.

### Agregado

- **Proyecto Kotlin Multiplatform** con Compose Multiplatform: targets
  `jvm("desktop")` y `androidTarget`, y un source set manual `jvmCommonMain`
  para el código que comparten los dos (los dos son JVM).
- **`:core`** — el dominio y la costura de portabilidad:
  - `kitoru.platform`: `FilePath`, `FileSystem` (sobre `java.io.File`, que
    existe en Android desde la API 1), `stripDiacritics`, `nowMillis`,
    `ioDispatcher`, `KitoruPaths`.
  - `kitoru.domain`: `Course`, `Enrollment`, `Material`, `ChatThread`,
    `ChatMessage`, `CitationStats`, `ToolCallStats`, `ModelPolicy`,
    `CourseRole`, `ChatVisibility`, `StudentAnalytics`, `CourseAnalytics`,
    `AnalyticsAnalyzer`, los repositorios y sus implementaciones sobre JSON.
- **`:llm-api`** — `LlmGateway`, `OpenAiCompatibleGateway` (Ktor, con el
  motor resuelto por el classpath), `GatewayConfig`, `LocalModelSettings`,
  `LocalModelService` (llama-server en escritorio; error explícito en
  Android), `LlmErrorCode`/`LlmException`.
- **`:backend`** — port de FunshiEngineAI: `Bm25Index`, `Chunker`,
  `KnowledgeBase` con fuentes enchufables, `RagEngine`, `CitationVerifier`,
  `AgentLoop`, `ToolRegistry`, `ToolPermissions`, `ToolCallSanitizer`,
  `GetTimeTool`, `CreateFileTool`.
- **`:ui`** — el shell de la app, el selector de rol, la home del alumno, el
  chat con streaming y distintivo de citas, y las pantallas del docente
  (editor de la cátedra, materiales, analítica, chats y alumnos).
- **`:desktop`** y **`:android`** — los dos puntos de entrada.
- **`:testkit`** — los dobles compartidos: `InMemoryFileSystem`,
  `ScriptedGateway`, `FailingGateway`, `Fixtures`.

### Portado desde FunshiEngineAI

El mapa completo, con el motivo de cada cambio, está en
[`docs/PORTS.md`](docs/PORTS.md). Los cambios de fondo:

- `java.nio.file.Path` → `kitoru.platform.FilePath`, porque `java.nio.file`
  no existe en Android por debajo de la API 26 y el `minSdk` de Kitoru es 24.
- `java.text.Normalizer` → `kitoru.platform.stripDiacritics`, porque
  `java.text` no está en el código común de Kotlin.
- `java.net.http.HttpClient` → Ktor, porque `java.net.http` no existe en el
  SDK de Android.
- `HttpClient(CIO)` → `HttpClient()` sin motor: el motor se resuelve por el
  classpath (CIO en escritorio, OkHttp en Android).
- `Dispatchers.IO` → `kitoru.platform.ioDispatcher`.
- El render de fórmulas con `java.awt` + JLaTeXMath → LaTeX monoespaciado,
  porque `java.awt` no existe en Android. En escritorio se puede activar el
  render real más adelante.
- `javax.swing.JFileChooser` y `java.awt.Toolkit` → `LocalClipboardManager`
  (que ya es multiplataforma) y la costura `FileSaver`.

### Decisiones de producto

- **El rol se deriva de la inscripción**, no es un flag: una persona puede
  ser titular en una cátedra y alumno en otra, y la app lo refleja.
- **La visibilidad de los chats tiene tres niveles** (`FULL`,
  `METRICS_ONLY`, `NONE`) y se decide por inscripción: es lo que hace que un
  alumno con IA local pueda decidir qué ve su docente.
- **La calidad de las respuestas se mide con un verificador determinista**
  (`CitationVerifier`): cada cita de la respuesta se comprueba contra el
  texto de la fuente. No depende de la opinión del modelo, así que el
  docente ve un número reproducible.
- **El modelo local del alumno no es un pariente del servidor**: si el
  alumno lo usa, el servidor no ve sus conversaciones. La app lo dice de
  forma explícita.

### Agregado

- **El contrato del corpus** (`:core`) — el manifiesto es lo único que
  travels entre el docente y el cliente del alumno:
  - `CorpusManifest` + `ManifestEntry` + `ChunkingConfig`: qué material hay,
    con qué versión y con qué hash. El `chunking` viaja para que el cliente
    reindexe sin volver a descargar un byte cuando el docente cambia el
    troceado.
  - `Sha256` en **Kotlin puro** (sin `java.security` ni dependencias
    nuevas) y `ContentHasher`: el hash del **texto extraído** es el del
    contrato (si cambia un carácter, el material hay que rebajarlo) y el
    hash normalizado es el que detecta duplicados.
  - `CorpusManifest.compare(anterior, nuevo)`: qué se agregó, qué cambió,
    qué se quitó y qué cambió de visibilidad. Es lo que permite bajar sólo
    lo que cambió.
  - `CorpusQualityChecker`: checks deterministas (texto vacío, fragmento
    gigante único, fragmentos diminutos, falta de encabezados, duplicados y
    mezcla de idiomas) con severidad bloqueante/aviso y mensajes
    accionables en español.
  - `CorpusCatalog` + `CorpusCatalogBuilder`: la salud 0–100 del corpus y
    las estadísticas de uso por material (cuántas veces se recuperó, cuántas
    respuestas sin respaldo lo citan).
  - `CorpusStore`: el manifiesto en `courses/<courseId>/corpus/manifest.json`,
    escrito de forma atómica (`.tmp` + `copy` + `delete`).
  - `CorpusPublisher`: publicación con los modos `INMEDIATO`,
    `AL_PUBLICAR_CATEDRA` y `DIARIO`; la versión del manifiesto sube sólo
    cuando el corpus cambia de verdad.

### Estado

- `:core`, `:llm-api`, `:backend`, `:ui`, `:desktop` y `:android` compilan.
- Los tests portados de FunshiEngineAI (108) pasan en `:backend`.
- Todavía no hay CI, ni empaquetado firmado, ni sincronización con el
  Kitoru-Server