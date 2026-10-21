# Mapa de ports — FunshiEngineAI → Kitoru

> **Qué es este documento.** El registro de qué código de
> [FunshiEngineAI](https://github.com/Nootechnia/FunshiEngineAI) seporteda a
> Kitoru, **qué cambió** y **por qué**.
>
> **Por qué existe.** Kitoru es un port de FunshiEngineAI. Sin un mapa, el
> trabajo se duplica: alguien reimplementa el sanitizador de tool calls sin
> saber que ya está portado, o "mejora" el verificador de citas sin saber que
> hay un test que lo cubre. La tabla responde tres preguntas en un vistazo:
> **qué se portó**, **qué se cambió** y **por qué**.
>
> **Commit base.** `1ea46e2` (`origin/main` de FunshiEngineAI: *feat(mcp):
> cliente MCP real por stdio*). Es el commit contra el que se compararon los
> archivos al portar.
>
> **Regla.** *Reutilizar, no reescribir.* El port es una **copia**, no una
> reimplementación. Cada adaptación tiene un motivo, y el motivo es una
> **decisión de diseño**, no "en Kitoru es distinto".

## 1. Resumen

**Estados posibles**:

| Estado | Significado |
|---|---|
| **Portado** | Idéntico al original (o sólo cambian comentarios y KDoc). |
| **Adaptado** | Cambió algo, y el cambio está justificado abajo. |
| **Descartado** | No se portó, y está por qué. |
| **Pendiente** | Aún no. |

**Leyenda de las rutas**: `FEA/` = `FunshiEngineAI/`, `K/` = `Kitoru/`. Las
rutas de FunshiEngineAI son del módulo JVM puro (`src/main/kotlin`); las de
Kitoru son de `src/commonMain/kotlin` salvo que diga otra cosa.

### 1.1 Tabla general

| # | Origen (FunshiEngineAI) | Destino (Kitoru) | Qué cambió | Por qué | Estado | Tests |
|---|---|---|---|---|---|---|
| 1 | `FEA/backend/rag/retrieval/Models.kt` | `K/backend/src/commonMain/kotlin/rag/retrieval/Models.kt` | nada (el archivo es idéntico) | — | **Portado** | — |
| 2 | `FEA/backend/rag/retrieval/Bm25Index.kt` | `K/backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt` | `java.text.Normalizer` → `kitoru.platform.stripDiacritics`; se borra el `Regex` local de marcas | `commonMain` no puede usar `java.*` | **Adaptado** | `Bm25IndexTest` |
| 3 | `FEA/backend/rag/retrieval/Chunker.kt` | `K/backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt` | nada (idéntico) | — | **Portado** | `ChunkerTest` |
| 4 | `FEA/backend/rag/retrieval/Sources.kt` | `K/backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt` | las fuentes dejan de ser repos de GitHub y carpetas del disco: son los **materiales de la cátedra** (`CourseMaterialSource`), más `InMemorySource` | el corpus de Kitoru es el material que sube el docente, no documentación pública | **Adaptado** | — (falta) |
| 5 | `FEA/backend/rag/retrieval/KnowledgeBase.kt` | `K/backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt` | `sources` es mutable y `sync(onStatus)` reindexa en caliente; sin `java.nio.file` ni descarga por red | el docente sube material mientras la app corre | **Adaptado** | — (falta) |
| 6 | `FEA/backend/rag/engine/RagEngine.kt` | `K/backend/src/commonMain/kotlin/rag/engine/RagEngine.kt` | el prompt del sistema pasa de **leer un archivo** a una **lambda `() -> String`** inyectada; fuera `AppPaths` y `Files`; fuera `ask`/`askGeneral` (el bucle vive en `AgentLoop`) | el prompt lo fija la cátedra y puede editarse sin reiniciar; además permite inyectarlo en los tests | **Adaptado** | — (falta) |
| 7 | `FEA/backend/rag/engine/CitationVerifier.kt` | `K/backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt` | la normalización usa `stripDiacritics` en vez de `Normalizer` | portabilidad (mismo motivo que la fila 2) | **Adaptado** | `CitationVerifierTest` |
| 8 | `FEA/backend/rag/agent/Tool.kt` | `K/backend/src/commonMain/kotlin/rag/agent/Tool.kt` | `ToolContext.outputsDir`: `java.nio.file.Path` → `kitoru.platform.FilePath` | `java.nio.file` es API 26 y `minSdk` es 24 | **Adaptado** | `ToolRegistryTest` |
| 9 | `FEA/backend/rag/agent/ToolRegistry.kt` | `K/backend/src/commonMain/kotlin/rag/agent/ToolRegistry.kt` | nada (idéntico) | — | **Portado** | `ToolRegistryTest` |
| 10 | `FEA/backend/rag/agent/ToolPermissions.kt` | `K/backend/src/commonMain/kotlin/rag/agent/ToolPermissions.kt` | nada (idéntico) | — | **Portado** | `ToolPermissionsTest` |
| 11 | `FEA/backend/rag/agent/ToolCallSanitizer.kt` | `K/backend/src/commonMain/kotlin/rag/agent/ToolCallSanitizer.kt` | nada (idéntico) | — | **Portado** | `ToolCallSanitizerTest` |
| 12 | `FEA/backend/rag/agent/AgentLoop.kt` | `K/backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt` | nada (idéntico) | — | **Portado** | `AgentLoopTest` |
| 13 | `FEA/backend/rag/agent/tools/FileTools.kt` (`create_file`, `download_url`) | `K/backend/src/commonMain/kotlin/rag/agent/tools/CreateFileTool.kt` | sólo `create_file`; el `FileSystem` va **inyectado**; hay un tope `maxBytes`; sin red | el cliente no sale a internet; el archivo lo escribe la app en la carpeta del chat | **Adaptado** | — (falta) |
| 14 | `FEA/backend/rag/agent/tools/GetTimeTool.kt` | `K/backend/src/commonMain/kotlin/rag/agent/tools/GetTimeTool.kt` | reloj portable (`nowMillis()`) y formateo **manual en UTC** (algoritmo civil de Howard Hinnant) | `java.time` no está en el código común | **Adaptado** | — (falta) |
| 15 | `FEA/backend/rag/config/Config.kt` (`AppConfig`, `RagConfig`) | `K/backend/src/commonMain/kotlin/rag/config/RagConfig.kt` | sólo la parte de RAG, y **por cátedra** | la configuración de la app vive en `:core` (el dominio) y en `:ui` | **Adaptado** | — |
| 16 | `FEA/llm-api/api/LlmGateway.kt` (`OpenAiCompatibleGateway`) | `K/llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` | `HttpClient(CIO)` → `HttpClient()` (el motor lo resuelve el classpath); `Dispatchers.IO` → `kitoru.platform.ioDispatcher` | CIO no existe en Android; en `commonMain` no se puede elegir el motor | **Adaptado** | — (falta) |
| 17 | `FEA/llm-api/local/LocalServerManager.kt` + `llm-api/service/LlmService.kt` | `K/llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` + `desktopMain` + `androidMain` | `expect`/`actual`: en escritorio levanta `llama-server`; en Android es un stub que lanza `LlmException` | en Android no hay `llama-server`; y el servicio tiene que ser **común** | **Adaptado** | — |
| 18 | `FEA/llm-api/catalog/HuggingFaceDownloader.kt` + `ModelManager.kt` | — | no portado | la descarga del GGUF es un hito posterior (F10); hoy se espera que el modelo ya esté en la máquina | **Descartado** | — |
| 19 | `FEA/llm-api/config/LlmConfig.kt` (`ChatMessage`) | `K/llm-api/src/commonMain/kotlin/llm/config/ChatMessage.kt` | sólo los acentos de los comentarios y el `import` de `JsonElement` | — | **Portado** | — |
| 20 | `FEA/llm-api/config/LlmConfig.kt` (`LlmConfig`) | `K/llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt` | se queda **sólo la parte que necesita el gateway** (`baseUrl`, `apiKey`, `model`, `temperature`, `maxTokens`, `chatTimeoutMs`, `thinking`, `power`) y se le agrega `LocalModelSettings`; el `LlmConfig` completo (presets, catálogo, tema, `configVersion`) no viene | la configuración de la app vive en el dominio (`kitoru.domain.ModelPolicy`); el gateway sólo necesita saber a quién le pregunta | **Adaptado** | — |
| 21 | `FEA/llm-api/config/LlmConfig.kt` (`ModelPreset`, `ApiPreset`) | — | no portado | el modelo lo elige la cátedra, no el usuario de una lista; un catálogo contradice `ModelPolicy` | **Descartado** | — |
| 22 | `FEA/llm-api/error/LlmErrors.kt` | `K/llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt` | se sacan `java.util.concurrent.TimeoutException` e `java.io.IOException` del `wrap`; ahora clasifica **por el mensaje** | `java.io` no existe en el código común de Android | **Adaptado** | — |
| 23 | `FEA/llm-api/api/Tools.kt` | `K/llm-api/src/commonMain/kotlin/llm/api/Tools.kt` | sólo los acentos y la puntuación de los comentarios | — | **Portado** | — |
| 24 | `FEA/frontend/ui/Markdown.kt` + `ui/MathRender.kt` | `K/ui/src/commonMain/kotlin/kitoru/ui/components/Markdown.kt` + `MathSegment.kt` | **se pierde el render de fórmulas con `java.awt`**: la fórmula se muestra como **LaTeX monoespaciado**; la parte de *split* (`splitMath`) sí se porta íntegra | el render de JLaTeXMath usa `java.awt` + `BufferedImage`, que no existe en Android | **Adaptado** | — (falta) |
| 25 | `FEA/frontend/ui/CodeBlock.kt` | `K/ui/src/commonMain/kotlin/kitoru/ui/components/CodeBlock.kt` | se sacan `javax.swing.JFileChooser` y `java.awt.Toolkit`; "guardar como…" pasa por un seam `rememberFileSaver()` | el selector de archivos y el portapapeles son APIs de desktop | **Adaptado** | — |
| 26 | `FEA/frontend/core/UiState.kt` | `K/core/src/commonMain/kotlin/kitoru/domain/Chat.kt` (`ChatMessage`, `CitationStats`, `ToolCallStats`) | los tipos de UI pasan al **modelo de dominio**: pasan a ser `data class` `@Serializable`, con `id`/`threadId`/`createdAt`/`latencyMs`/`tokensIn`/`tokensOut` | lo que la UI muestra es lo que se persiste y lo que consume la analítica: tiene que ser el mismo tipo | **Adaptado** | — |
| 27 | `FEA/frontend/core/ChatTree.kt` | `K/core/src/commonMain/kotlin/kitoru/domain/Chat.kt` (`ChatThread`) | el árbol de carpetas y chats **no se porta**: en Kitoru los chats cuelgan de una cátedra (`ChatThread.courseId`) y la navegación es una pila manual | el modelo de datos de Kitoru es *cátedra → hilos*, no *carpetas → chats* | **Adaptado** | — |
| 28 | `FEA/backend/rag/store/ChatStore.kt` | `K/core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` + `JsonStore.kt` | el store se **reemplaza** por repositorios por tipo (`ThreadRepository`, `MessageRepository`, …) sobre `JsonListStore` | el modelo de datos es el dominio, no el backend; y la persistencia tiene que ser inyectable para poder testearla | **Adaptado** | — |
| 29 | `FEA/backend/rag/mcp/**` | — | no portado | MCP es un plugin opcional; entra por `ToolRegistry.replaceOrigin` cuando haga falta | **Descartado** | — |
| 30 | `FEA/backend/rag/tools/WebTools.kt` (`web_search`, `web_fetch`) | — | no portado | el cliente no sale a internet: el corpus es el material de la cátedra | **Descartado** | — |
| 31 | `FEA/backend/rag/tools/LatexTool.kt` (`latex_compile`) | — | no portado | fuera del alcance: la app no compila documentos ni adjunta PDFs | **Descartado** | — |
| 32 | `FEA/voice-api/**` | — | no portado | la voz no es del alcance de Kitoru | **Descartado** | — |
| 33 | `FEA/backend/src/test/**` (8 clases) | `K/backend/src/desktopTest/kotlin/rag/**` | se portan tal cual; sólo cambia el `Path` por `FilePath` en los dobles | el test se porta con el código | **Portado** | — (son los propios tests) |

### 1.2 Resumen numérico

| | Archivos |
|---|---:|
| Portados sin cambios | 9 |
| Adaptados | 17 |
| Descartados (a propósito) | 6 |
| Tests portados | 8 clases / 108 casos |

---

## 2. Detalle de las entradas no triviales

### 2.1 · `Bm25Index` — la normalización sale del archivo

| | |
|---|---|
| **Origen** | `FunshiEngineAI/backend/src/main/kotlin/rag/retrieval/Bm25Index.kt` |
| **Destino** | `Kitoru/backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt` |
| **Estado** | Adaptado |
| **Tests** | `Bm25IndexTest` (12 casos) |

**Qué cambió**

- El `import java.text.Normalizer` se reemplaza por `import kitoru.platform.stripDiacritics`.
- La regex local `MARKS = Regex("\\p{M}+")` se borra: la hace el `actual` de `stripDiacritics`.
- En `tokenize`, `Normalizer.normalize(spaced, NFD).replace(MARKS, "")` pasa a ser `stripDiacritics(spaced)`.

**Por qué**

`commonMain` no compila contra `java.*`: el mismo archivo tiene que servir para
escritorio y para Android. La normalización no es un detalle del índice: es la
base de la tokenización, y si "Definición" y "definicion" se comparan sin
normalizar, el índice no encuentra lo que el modelo escribió.

**Impacto en los tests**

Ninguno: `Bm25IndexTest` verifica el comportamiento (saca acentos, parte
*camel case*, saca palabras vacías), no la implementación.

---

### 2.2 · `RagEngine` — el prompt del sistema deja de ser un archivo

| | |
|---|---|
| **Origen** | `FunshiEngineAI/backend/src/main/kotlin/rag/engine/RagEngine.kt` |
| **Destino** | `Kitoru/backend/src/commonMain/kotlin/rag/engine/RagEngine.kt` |
| **Estado** | Adaptado |
| **Tests** | — (falta `RagEngineTest`) |

**Qué cambió**

- `systemPrompt()` deja de leer `config/system_prompt.md` con
  `java.nio.file.Files` y pasa a ser un **parámetro del constructor**:
  `private val systemPrompt: () -> String`.
- Se elimina la dependencia de `rag.config.AppPaths`.
- El texto del contexto vacío cambia de `"(sin resultados en la documentación)"` a
  `"(sin resultados en el material de la cátedra)"`.
- Se agregaron las propiedades `gateway` y `config` (accesores del constructor).

**Por qué**

`java.nio.file` no existe en `commonMain` (y `minSdk` es 24, por debajo de API
26). Sobre todo: el modelo de Kitoru **no tiene un archivo de prompt**. Lo
tiene la **cátedra**, en `Course.systemPrompt`, y el docente puede editarlo
mientras la app corre; la lambda se evalúa en cada pregunta. Como efecto
secundario, los tests pueden inyectar el prompt en vez de escribir un archivo
temporal.

**Impacto en los tests**

`RagEngineTest` (aún no existe) debe inyectar el prompt en vez de escribir un
archivo.

---

### 2.3 · `Tool` — la ruta del chat deja de ser un `Path`

| | |
|---|---|
| **Origen** | `FunshiEngineAI/backend/src/main/kotlin/rag/agent/Tool.kt` |
| **Destino** | `Kitoru/backend/src/commonMain/kotlin/rag/agent/Tool.kt` |
| **Estado** | Adaptado |
| **Tests** | `ToolRegistryTest` |

**Qué cambió**

- `import java.nio.file.Path` → `import kitoru.platform.FilePath`.
- `ToolContext.outputsDir: Path` → `ToolContext.outputsDir: FilePath`.
- Los KDoc de los tipos se recortan (el detalle largo quedó en el original);
  el comportamiento es idéntico.

**Por qué**

`java.nio.file.Path` es API 26 en Android y `minSdk` es 24. `FilePath` es una
ruta **opaca** (`value`, `name`, `extension`, `parent`, `resolve`,
`relativeTo`) que resuelve el seam `kitoru.platform`, y sirve igual para los
dos platforms.

**Impacto en los tests**

`ToolRegistryTest` y `ToolPermissionsTest` construyen el `ToolContext` con
`kitoru.platform.FilePath(".")` en vez de `Paths.get(".")`.

---

### 2.4 · `OpenAiCompatibleGateway` — el motor HTTP y el dispatcher

| | |
|---|---|
| **Origen** | `FunshiEngineAI/llm-api/src/main/kotlin/llm/api/LlmGateway.kt` |
| **Destino** | `Kitoru/llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` |
| **Estado** | Adaptado |
| **Tests** | — (falta) |

**Qué cambió**

| En FunshiEngineAI | En Kitoru | Por qué |
|---|---|---|
| `HttpClient(CIO) { … }` | `HttpClient { … }` | el motor se resuelve por el classpath: CIO en escritorio (`ktor-client-cio`), OkHttp en Android (`ktor-client-okhttp`) |
| `flowOn(Dispatchers.IO)` | `flowOn(ioDispatcher)` | `Dispatchers.IO` no se escribe en `commonMain`: pasa por el seam `kitoru.platform.ioDispatcher` |
| `withContext(Dispatchers.IO)` en `listModels` | `withContext(ioDispatcher)` | ídem |
| `LlmConfig` como configuración de entrada | `GatewayConfig` + un constructor secundario que la toma | `GatewayConfig` es el puente entre `kitoru.domain.ModelPolicy` y la capa de red |

El resto del gateway —el parseo del SSE, la acumulación de `delta.tool_calls`,
la clasificación de errores HTTP y el timeout— es el mismo código.

**Por qué**

El gateway tiene que ser **común**: es el mismo para el Kitoru-Server, para
Ollama, para LM Studio y para el modelo local del alumno. Escribir `HttpClient(CIO)`
en `commonMain` rompe el target de Android en compilación.

**Impacto en los tests**

El test del gateway (en FunshiEngineAI corre contra un servidor HTTP local del
JDK) hay que portarlo usando el motor de escritorio o un doble: en
`commonTest` no hay servidor.

---

### 2.5 · `LlmErrors.wrap` — sin tipos de `java.io`

| | |
|---|---|
| **Origen** | `FunshiEngineAI/llm-api/src/main/kotlin/llm/error/LlmErrors.kt` |
| **Destino** | `Kitoru/llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt` |
| **Estado** | Adaptado |
| **Tests** | — |

**Qué cambió**

`wrap(t: Throwable)` deja de mirar tipos de `java.*`:

```kotlin
// FunshiEngineAI
is java.util.concurrent.TimeoutException -> LlmException(LlmErrorCode.TIMEOUT, …)
is java.io.IOException -> LlmException(LlmErrorCode.SERVIDOR_CAIDO, …)

// Kitoru
TIMEOUT_HINTS.any { msg.contains(it, ignoreCase = true) } -> LlmException(LlmErrorCode.TIMEOUT, …)
IO_HINTS.any { msg.contains(it, ignoreCase = true) } -> LlmException(LlmErrorCode.SERVIDOR_CAIDO, …)
```

**Por qué**

`java.io.IOException` no existe en el código común de Android: el `when` no
compila en `commonMain`. La clasificación pasa a ser **por el mensaje**
(`"timeout"`, `"timed out"`, `"timeoutexception"`, `"connection refused"`,
`"connection reset"`, `"broken pipe"`, `"unreachable"`, `"eof"`), que es
portable y además cubre los errores de los motores HTTP.

**Efecto colateral aceptable**: un `IOException` con un mensaje que no está en
la lista se clasifica como `DESCONOCIDO` en vez de `SERVIDOR_CAIDO`. Es el
precio de la portabilidad, y el mensaje original viaja en `cause`.

---

### 2.6 · `Markdown` — las fórmulas se muestran en LaTeX monoespaciado

| | |
|---|---|
| **Origen** | `FunshiEngineAI/frontend/src/main/kotlin/app/ui/Markdown.kt` + `ui/MathRender.kt` |
| **Destino** | `Kitoru/ui/src/commonMain/kotlin/kitoru/ui/components/Markdown.kt` + `MathSegment.kt` |
| **Estado** | Adaptado |
| **Tests** | — (falta) |

**Qué cambió**

- Se porta **todo** el parser de markdown: los nodos (`Heading`, `Paragraph`,
  `Bullets`, `Code`, `Quote`, `Table`, `Math`, `Divider`) y el render inline
  (`bold`, `italic`, `strike`, `código`, `links`) son idénticos.
- Se porta **íntegra** la parte que decide qué es una fórmula:
  `splitMath(...)` y `MathSegment`, con sus reglas (fórmula sin cerrar → texto,
  `$5 … $10` son precios, el código en línea se copia tal cual).
- Se **pierde el render de la fórmula**: en FunshiEngineAI `MathRender.render`
  usa JLaTeXMath + `java.awt.image.BufferedImage` y devuelve una imagen que se
  enchufa en el texto con `InlineTextContent`. En Kitoru la fórmula se muestra
  como **LaTeX monoespaciado**, con los signos `$` intactos.
- `MathBlock` (fórmula centrada `$$…$$`) también pasa a ser texto monoespaciado.

**Por qué**

`java.awt` no existe en Android. La alternativa que se descartó fue una
biblioteca de render de fórmulas multiplataforma: es una dependencia grande
para una función secundaria, y el código LaTeX es **copiable y legible**, que
es lo que un alumno necesita para pasarlo a su cuaderno o a otra herramienta.

**Dónde entra si algún día hace falta**

En un seam de plataforma, `kitoru.ui.platform.MathRendering`, con un `actual`
de escritorio que use JLaTeXMath y uno de Android que no. El parser ya está
listo: sólo hay que cambiar quién dibuja el nodo `MdNode.Math`.

---

### 2.7 · `CodeBlock` — "guardar como…" pasa por un seam

| | |
|---|---|
| **Origen** | `FunshiEngineAI/frontend/src/main/kotlin/app/ui/CodeBlock.kt` |
| **Destino** | `Kitoru/ui/src/commonMain/kotlin/kitoru/ui/components/CodeBlock.kt` |
| **Estado** | Adaptado |
| **Tests** | — |

**Qué cambió**

- Se sacan `javax.swing.JFileChooser`, `javax.swing.SwingUtilities`,
  `javax.swing.filechooser.FileNameExtensionFilter` y `java.awt.Toolkit`
  (con `java.awt.datatransfer.StringSelection`).
- El "copiar al portapapeles" y el "guardar como…" pasan por
  `kitoru.ui.platform.rememberFileSaver()`, que tiene un `actual` de escritorio
  (diálogo de Swing) y uno de Android (SAF / `CreateDocument`).

**Por qué**

Son APIs de escritorio. El portapapeles y el selector de archivos son
capacidades de plataforma: van detrás de un seam, como las demás.

---

### 2.8 · `UiState` y `ChatTree` — el modelo de dominio los absorbe

| | |
|---|---|
| **Origen** | `FunshiEngineAI/frontend/src/main/kotlin/app/core/UiState.kt` + `core/ChatTree.kt` |
| **Destino** | `Kitoru/core/src/commonMain/kotlin/kitoru/domain/Chat.kt` |
| **Estado** | Adaptado |
| **Tests** | — |

**Qué cambió**

| Tipo de FunshiEngineAI | Tipo de Kitoru | Cambio |
|---|---|---|
| `UiMessage` | `kitoru.domain.ChatMessage` | pasa a ser `data class @Serializable`, con `id`, `threadId`, `createdAt`, `latencyMs`, `tokensIn`, `tokensOut`; `sources` pasa de `List<String>` a etiquetas; `citation` pasa a `CitationStats?` |
| `UiCitation` | `kitoru.domain.CitationStats` | idem, y suma `supportRate` y `fullySupported` |
| `UiToolCall` | `kitoru.domain.ToolCallStats` | idem, y suma `durationMs` |
| `UiAttachment` | — | no hay adjuntos descargables: el cliente no sale a internet |
| `ChatNode` / `ChatTree` | `kitoru.domain.ChatThread` | **el árbol no se porta**: los chats cuelgan de una cátedra (`courseId`) y no hay carpetas |
| `Screen`, `UiProgress`, `AppTheme`, `humanSize` | — | no se portan: son de la UI de escritorio |

**Por qué**

En Kitoru **lo que la UI muestra es lo que se guarda y lo que consume la
analítica**: el `ChatMessage` del dominio tiene que llevar las fuentes, la
verificación de citas, las herramientas, la latencia y los tokens. Tener dos
tipos (uno de UI y otro de persistencia) obligaría a convertirlos en el
borde, y es exactamente ahí donde se pierde información y aparecen los bugs
de "la analítica no coincide con lo que se ve".

El árbol de carpetas, en cambio, es una decisión de producto de FunshiEngineAI
(el asistente organiza los chats en carpetas). El modelo de Kitoru es
**cátedra → hilos**: el hilo pertenece a una cátedra y a un autor, y es lo
que la analítica necesita para agrupar.

---

### 2.9 · `ChatStore` — los repositorios lo reemplazan

| | |
|---|---|
| **Origen** | `FunshiEngineAI/backend/src/main/kotlin/rag/store/ChatStore.kt` |
| **Destino** | `Kitoru/core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` + `JsonStore.kt` |
| **Estado** | Adaptado |
| **Tests** | — |

**Qué cambió**

El store monolítico (un `chats.json` con el árbol entero) se reemplaza por
**seis interfaces** de repositorio (`CourseRepository`, `EnrollmentRepository`,
`MaterialRepository`, `ThreadRepository`, `MessageRepository`,
`ProfileRepository`) y sus implementaciones `File*`, cada una sobre un
`JsonListStore` (un JSON por tipo dentro de `config/`).

**Por qué**

El store de FunshiEngineAI conoce el modelo de la app (árbol de carpetas,
adjuntos, permisos por chat). El de Kitoru tiene que conocer **el dominio**:
cátedras, inscripciones, materiales, hilos y mensajes son cosas distintas, con
reglas de visibilidad distintas y con consumidores distintos (la UI del alumno,
la del docente y la analítica). Además, las interfaces permiten que los tests
usen dobles sin tocar el disco.

**Efecto colateral**: el formato en disco **no es compatible** con el de
FunshiEngineAI. Es intencional: los datos de Kitoru tienen otra forma.

---

## 3. Piezas deliberadamente no portadas

| Pieza de FunshiEngineAI | Por qué no entra | Dónde entraría |
|---|---|---|
| **MCP por stdio** (`rag/mcp/**`) | Es un plugin opcional: el modelo puede hablar con servidores MCP externos. Kitoru hoy habla con un corpus cerrado (el material de la cátedra) y con un solo gateway. El cliente tampoco puede lanzar procesos arbitrarios en Android. | Cuando el `ToolRegistry` acepte herramientas de origen remoto: entra por `ToolRegistry.replaceOrigin`, como un `Tool` más, con `ToolOrigin.MCP`. |
| **`WebTools`** (`web_search`, `web_fetch`) | El cliente **no sale a internet**: el corpus es el material que subió el docente y el modelo es el del alumno o el de la institución. Una herramienta de búsqueda web rompería la promesa de privacidad y haría que la respuesta no sea verificable con el verificador de citas. | Sólo en el **Kitoru-Server**, que sí puede salir a internet: el servidor puede ofrecer un `web_search` reescrito al modelo. |
| **`LatexTool`** (`latex_compile`) | Fuera del alcance: Kitoru no compila documentos ni genera PDFs. El render de fórmulas tampoco se porta (ver §2.6). | Una tool de escritorio, detrás del mismo seam de plataforma que el render, si alguna vez hace falta. |
| **Descarga del GGUF** (`llm-api/catalog/HuggingFaceDownloader.kt`, `ModelManager.kt`) | Es un hito posterior (F10). Hoy el modelo local **se espera que ya esté en la máquina**: el `LocalModelService` de escritorio busca el binario `llama-server` y el `.gguf` y da un error explícito si no los encuentra. | `llm-api/catalog/`, como módulo propio con su `expect/actual` (el `Storage Access Framework` de Android no descarga a una carpeta arbitraria). |
| **`ChatStore`** | No es que no se porte: se **reemplaza** (ver §2.9). | Ya está: los repositorios de `:core`. |
| **La voz** (`voice-api/**`) | La voz no es del alcance de Kitoru: el producto es "material de la cátedra + tutor + analítica", no un dictado. Además, `voice-api` es un módulo grande (micrófono, WAV, resampling, VAD, TTS/STT) que no tiene equivalente multiplataforma. | `voice-api` como módulo hermano, si algún día el alumno en tránsito necesita dictar una pregunta. |
| **La compilación de llama.cpp** (`llm-api/local/LocalServerManager.kt`, la parte que clona y compila) | Clonar y compilar C++ en el dispositivo del usuario es una decisión del producto que no se tomó. El `LocalModelService` de Kitoru sólo levanta el binario si ya está. | El **Kitoru-Server**, que es quien aloja el modelo para la institución. |
| **Los presets de API y el catálogo de modelos** (`llm-api/config/LlmConfig.kt`: `apiPresets`, `modelCatalog`) | El modelo de Kitoru no lo elige el usuario de una lista: lo elige la **cátedra** (`ModelPolicy`), y el alumno puede ajustarlo dentro de los límites que ella fija. Un catálogo de modelos contradice ese modelo. | `kitoru.domain.ModelPolicy` ya tiene `modelName`; si aparecen presets, viven en el dominio, no en la capa de red. |

---

## 4. Cómo agregar una fila

1. **Antes de escribir, mirar la tabla.** Si la fila existe, se actualiza; si
   no, se agrega **en el mismo commit** del port.
2. **El port es una copia, no una reimplementación.** Se copia el archivo de
   FunshiEngineAI y se adapta. Si hay que reescribirlo entero, es que cambió
   el modelo de datos, y eso hay que decirlo en la fila.
3. **El test se porta con el código.** Si el port viene con un test, el test se
   porta; si el código tiene un comportamiento que cambió, **se agrega un test
   nuevo** (no se borra el viejo sin dejarlo asentado en la fila).
4. **La adaptación es mínima y documentada**: cada cambio tiene un motivo, y el
   motivo es una **decisión de diseño**, no "en Kitoru es distinto".
5. **La fila manda**: si el código y la fila no coinciden, gana el código y la
   tabla se corrige (o el código se corrige). La tabla no es decorativa.
6. **El commit base se anota** cuando cambia: si el port se rehace contra un
   commit más nuevo de FunshiEngineAI, la columna del commit de la fila cambia.

---

## 5. Referencias

- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — cómo encaja el código portado en Kitoru.
- [`AGENTS.md`](../AGENTS.md) — las reglas de portabilidad por source set.
- [`docs/design/CONTEXTO.md`](design/CONTEXTO.md) — el contexto del proyecto y los repos hermanos.
- [FunshiEngineAI](https://github.com/Nootechnia/FunshiEngineAI) — el repo de origen.
