# Contexto compartido para los subagentes de diseño de Kitoru

> Este archivo es la referencia para todos los subagentes que diseñan partes de
> Kitoru. Leerlo antes de empezar.

## Qué es Kitoru

Kitoru es una **aplicación de escritorio estilo Moodle** escrita en Kotlin,
**compatible con Android** (Kotlin Multiplatform + Compose Multiplatform).

Vive en `C:\Users\gianf\Desktop\Projects\Kitoru` (repo git vacío, remoto
`https://github.com/Nootechnia/Kitoru.git`).

### Repos hermanos (fuente de código a reutilizar)

| Repo | Ruta | Qué aporta |
|---|---|---|
| **FunshiEngineAI** | `C:\Users\gianf\Desktop\Projects\FunshiEngineAI` | Asistente de IA: RAG BM25, bucle de tool-calling, verificador de citas, MCP, modelos locales llama.cpp, gateway OpenAI-compatible. **Es el código base a portar.** Último commit `1ea46e2` en `origin/main`. |
| FunshiEngineCLI | `C:\Users\gianf\Desktop\Projects\FunshiEngineCLI` | TUI C++ del motor gráfico |
| Silvy | `C:\Users\gianf\Desktop\Projects\Silvy` | Runtime/gateway LLM en C++ (supervisor de llama-server, gateway remoto) |
| FunshiEngineCore | `C:\Users\gianf\Desktop\Projects\FunshiEngineCore` | Core C++ del motor gráfico |

### Los dos entornos de la app

1. **Alumno (cliente de una cátedra).** Se inscribe en una cátedra con un
   código. Chatea con un modelo de IA que la cátedra configura como base pero
   que el alumno puede **modificar libremente** (igual que en FunshiEngineAI).
   El RAG del chat usa **el material que subió la cátedra**, no documentación
   general.
2. **Docente (diseña la cátedra).** Profesor titular y ayudantes. Configuran
   la cátedra, suben materiales, fijan la configuración base del modelo y
   **acceden a los chats de los alumnos** para analizar **estadísticas,
   comportamiento y calidad de las respuestas**.

### Kitoru-Server (repo hermano, futuro)

Una **app de escritorio** que:
- descarga un modelo (backend estilo FunshiEngineAI),
- levanta un servidor con ese modelo,
- **emite API keys** para que los alumnos chateen contra la IA alojada
  (la institución la provee; **vence al terminar la cursada**),
- es el componente que **puede mandar los chats a los profesores**.

Matriz de visibilidad (ya modelada en el dominio):

| Modo del alumno | ¿El docente ve el texto? | ¿Ve estadísticas? |
|---|---|---|
| IA alojada (REMOTE, con API key) | Sí | Sí |
| IA local del alumno (LOCAL) | No (salvo que el alumno lo comparta) | Depende de la visibilidad de la inscripción |

## Estado actual del repo Kitoru

Toolchain: Kotlin 2.1.0, Compose Multiplatform 1.7.3, AGP 8.7.2, Gradle 8.11.1,
JDK 21, Ktor 3.0.3, kotlinx.serialization 1.7.3, coroutines 1.9.0.
`compileSdk = 35`, `minSdk = 24`. Targets: `jvm("desktop")` + `androidTarget`,
con un source set manual `jvmCommonMain` compartido por ambos.

### Módulos

```
:core     kitoru.platform + kitoru.domain   (KMP, ya construido)
:llm-api  gateway LLM                        (KMP, ya construido)
:backend  RAG + agente                       (KMP, ya construido)
:ui       Compose Multiplatform              (NO construido todavía)
:desktop  entry point escritorio             (NO construido)
:android  app Android                        (NO construido)
```

### `:core` — plataforma

- `kitoru.platform.FilePath` — ruta opaca (`value`, `name`, `extension`,
  `parent`, `resolve`, `relativeTo`).
- `kitoru.platform.FileSystem` — interfaz portable (`exists`, `isDirectory`,
  `sizeBytes`, `readText`, `writeText`, `delete`, `createDirectories`,
  `listFiles`, `copy`). `actual fun systemFileSystem()` en `jvmCommonMain`
  sobre `java.io.File`.
- `kitoru.platform.stripDiacritics(text)` — `expect/actual` (JVM:
  `java.text.Normalizer`).
- `kitoru.platform.nowMillis()`, `kitoru.platform.ioDispatcher` — `expect/actual`.
- `kitoru.platform.KitoruPaths` — `dataDir` (lo fija la app al arrancar),
  `configDir`, `coursesDir`, `cacheDir`.

### `:core` — dominio

- `CourseRole`: `TITULAR`, `AYUDANTE`, `ALUMNO`. Con `canDesign`,
  `canConfigureModel`.
- `ChatVisibility`: `FULL`, `METRICS_ONLY`, `NONE`. Con `showsText`,
  `showsMetrics`.
- `UserProfile(id, displayName, email)`.
- `ModelPolicy`: `mode` (`REMOTE`/`LOCAL`), `remoteBaseUrl`, `apiKey`,
  `modelName`, `hfRepo`, `hfFile`, `modelsDir`, `llamaServerPath`, `localPort`,
  `contextSize`, `gpuLayers`, `temperature`, `maxTokens`, `topK`,
  `allowStudentOverride`, `lockedFields: Set<LockedField>`, `toolsEnabled`,
  `systemPrompt`. Métodos: `modelLabel`, `resolveFor(studentChoice)` (aplica
  los bloqueos), `validate()`.
- `LockedField`: `API_KEY`, `BASE_URL`, `MODEL_NAME`, `SYSTEM_PROMPT`,
  `TEMPERATURE`, `MAX_TOKENS`, `TOP_K`.
- `Course(id, code, name, term, description, createdAt, joinCode, modelPolicy,
  systemPrompt, topK, chunkSize, analyticsEnabled, allowLocalModels)`.
- `Enrollment(id, courseId, userId, displayName, role, joinedAt, visibility)`.
- `Material(id, courseId, title, kind, path, sizeBytes, uploadedBy, uploadedAt,
  tags, visible, extractedText, warning)`; `MaterialKind`: `DOCUMENT`,
  `SLIDES`, `TRANSCRIPT`, `LINK`, `OTHER`.
- `ChatThread(id, courseId, authorId, authorName, title, createdAt, updatedAt,
  modelLabel, effectiveModel, tags)`.
- `ChatMessage(id, threadId, role, text, createdAt, sources, citation,
  toolCalls, latencyMs, tokensOut, tokensIn)`.
- `CitationStats(verified, unsupported, unknown)` con `supportRate`,
  `fullySupported`.
- `ToolCallStats(name, args, ok, result, durationMs)`.
- `StudentAnalytics` y `CourseAnalytics` (+ `DayActivity`, `SourceUsage`).
- `AnalyticsAnalyzer.analyze(course, enrollments, threads, messages)` — función
  pura; ya implementada.
- Repositorios: `CourseRepository`, `EnrollmentRepository`,
  `MaterialRepository`, `ThreadRepository`, `MessageRepository`,
  `ProfileRepository` + implementaciones `File*Repository` sobre
  `JsonListStore` (un JSON por tipo en `config/`).

### `:llm-api`

- `llm.api.LlmGateway` — `chatStream(messages, tools): Flow<LlmChunk>`,
  `chatText`, `chat`, `listModels`, `close`.
- `llm.api.LlmChunk` — `Text(delta)` | `ToolCall(id, name, argsJson)`.
- `llm.api.ToolSpec(name, description, parametersSchema)`.
- `llm.api.OpenAiCompatibleGateway` — SSE contra `/v1/chat/completions`,
  acumula `delta.tool_calls`, clasifica errores HTTP en `LlmException`.
- `llm.config.ChatMessage(role, content, toolCalls, toolCallId, toolName)`.
- `llm.config.GatewayConfig(baseUrl, apiKey, model, temperature, maxTokens,
  chatTimeoutMs, thinking, power)`.
- `llm.config.LocalModelSettings(hfRepo, hfFile, llamaServerPath, localPort,
  contextSize, gpuLayers, modelsDir)`.
- `llm.api.LocalModelService` + `expect fun createLocalModelService()`
  (desktop: `llama-server`; android: stub que lanza `LlmException`).
- `llm.error.LlmErrorCode` / `LlmException`.

### `:backend` (port de FunshiEngineAI)

- `rag.config.RagConfig(chunkSize=1200, chunkOverlap=150, topK=5,
  historyTurns=6, extensions)`.
- `rag.retrieval.Doc(source, path, text)`, `Chunk(source, path, heading, text)`
  con `label`.
- `rag.retrieval.Bm25Index` — BM25 en memoria, tokenizador con
  `stripDiacritics`.
- `rag.retrieval.Chunker.split(doc, size, overlap)` — por encabezados Markdown
  y luego por tamaño.
- `rag.retrieval.DocumentSource` (interfaz) + `CourseMaterialSource(materials)`
  + `InMemorySource`.
- `rag.retrieval.KnowledgeBase(cfg)` — `sources` mutable, `sync(onStatus)`,
  `retrieve(query, k)`, `chunkCount`.
- `rag.engine.RagEngine(cfg, kb, llm, systemPrompt: () -> String)` —
  `prepare(question, history, useDocs)`, `verify(answer, sources)`.
- `rag.engine.CitationVerifier` — verificador **determinista** de citas
  (literal / sin detalles inventados / paráfrasis). `verify(answer, sources)`,
  `CitationReport`, `CitationCheck`.
- `rag.agent.Tool` / `ToolContext(chatId, outputsDir: FilePath,
  grantedCategories, requestPermission)` / `ToolResult` / `Artifact` /
  `ToolCategory` / `ToolOrigin` / `PermissionDecision` / `PermissionRequest`.
- `rag.agent.ToolRegistry`, `ToolPermissions`, `ToolCallSanitizer`.
- `rag.agent.AgentLoop(gateway, registry, nativeToolCalls, toolTimeoutMs)` —
  `run(messages, ctx, onEvent)` → `AgentAnswer`; eventos `AgentEvent.Text`,
  `.ToolStart`, `.ToolEnd`. Modo nativo (tool_calls OpenAI) o por prompt
  (bloque ```` ```tool_call ````).
- `rag.agent.tools.GetTimeTool`, `rag.agent.tools.CreateFileTool(fs, maxBytes)`.

### Tests ya portados (pasan)

`:backend/src/desktopTest` con JUnit 5: `Bm25IndexTest`, `ChunkerTest`,
`CitationVerifierTest`, `AgentLoopTest`, `ToolCallSanitizerTest`,
`ToolPermissionsTest`, `ToolRegistryTest`, `PromptToolParserTest`.

## Reglas de diseño del proyecto

1. **Reutilizar, no reescribir.** Si algo existe en FunshiEngineAI, portarlo
   (conAdaptaciones mínimas y documentadas) en vez de inventar otra cosa.
2. **Portable por defecto.** Nada de `java.nio.file`, `java.awt`, `javax.swing`,
   `java.net.http`, `ProcessBuilder` en `commonMain`. Lo específico de
   plataforma va en `desktopMain`/`androidMain`/`jvmCommonMain`.
3. **Determinista.** La analítica y la verificación de citas no pueden
   depender del modelo ni de la red: son funciones puras y testeables.
4. **Fail-fast y con mensajes en español**, como en FunshiEngineAI.
5. **El estudiante conserva agencia**: la cátedra pone la base, el alumno
   puede ajustarla salvo en los campos bloqueados.
6. **El docente no ve lo que no debe**: la visibilidad por inscripción es la
   regla, no una excepción.

## Cómo debe trabajar cada subagente

- Leer los archivos reales del repo (y de FunshiEngineAI) antes de proponer.
- Proponer **detalles concretos**: nombres de tipos, firmas, rutas de archivo,
  algoritmos, casos borde, ejemplos de datos.
- **No escribir archivos de código.** El único archivo que se escribe es el
  documento de diseño que se le indica.
- El documento de diseño va **en español**, con los términos técnicos en
  inglés cuando sea lo natural.
