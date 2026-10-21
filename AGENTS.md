# AGENTS.md — Convenciones para agentes de código

Guía rápida para agentes (y humanos) que trabajen en este repositorio.

Kitoru es una aplicación de escritorio y Android para cátedras: el docente diseña la cátedra, sube materiales, configura el modelo y ve la analítica; el alumno se inscribe, chatea con una IA que responde con el material de la cátedra y decide qué ve su docente. Es **Kotlin Multiplatform + Compose Multiplatform**, con los módulos `:core`, `:llm-api`, `:backend`, `:ui`, `:desktop`, `:android` y `:testkit`.

El diseño completo —dominio, pantallas, RAG, sincronización, contrato del servidor— está en `docs/design/`: leer [`docs/design/CONTEXTO.md`](docs/design/CONTEXTO.md) antes de tocar código.

Tres reglas resumen el proyecto:

1. **Reutilizar, no reescribir.** Si algo existe en FunshiEngineAI, se porta (con adaptaciones mínimas y documentadas) en vez de inventar otra cosa. El mapa está en [`docs/PORTS.md`](docs/PORTS.md).
2. **Portable por defecto.** Nada de `java.nio.file`, `java.awt`, `javax.swing`, `java.net.http` ni `ProcessBuilder` en `commonMain`.
3. **Determinista.** La analítica y la verificación de citas no dependen del modelo ni de la red: son funciones puras y testeables.

## Comandos esenciales

```bash
# Compilar todo (desktop + android)
./gradlew build

# Correr la app de escritorio
./gradlew :desktop:run

# Empaquetar el escritorio (también: packageDmg, packageDeb)
./gradlew :desktop:packageMsi

# Compilar el APK de Android
./gradlew :android:assembleDebug

# Los tests (el target `jvm("desktop")` genera la tarea `desktopTest`)
./gradlew :backend:desktopTest
./gradlew :core:desktopTest
./gradlew allTests

# Un solo test
./gradlew :backend:desktopTest --tests "rag.retrieval.Bm25IndexTest"
```

Aclaraciones que hacen falta en este repo:

- **Android**: los targets `androidTarget` necesitan el SDK 35 y `local.properties` con `sdk.dir`. Sin eso, compilar `:android` falla; para trabajar sólo en desktop, usar `desktopTest`/`desktopMain`.
- **El build es lento la primera vez**: Gradle 8.11.1, Kotlin 2.1.0 y Compose Multiplatform 1.7.3 descargan y compilan todo. Después, es incremental.
- **Los tests de `:backend` usan JUnit 5** (`desktopTest`); los de `:core` usan `kotlin-test` (`commonTest`). Los dos runners conviven: no unificarlos sin motivo. El `useJUnitPlatform()` de `:backend` está aplicado **sólo** a `desktopTest` a propósito: aplicarlo a todas las tareas `Test` rompería los tests de Android, que usan `kotlin.test`.
- **No hay CI todavía** y el repo todavía no tiene un commit inicial: el `git status` muestra todo como untracked. `git add <archivos>` explícito, nunca `git add -A`.

## Pruebas

- **Deterministas**: sin red, sin reloj real, sin disco del usuario. El reloj es `kitoru.platform.nowMillis()` (inyectable), el sistema de archivos es `kitoru.platform.FileSystem` (inyectable), el gateway es `llm.api.LlmGateway` (se usa un doble: `kitoru.testing.ScriptedGateway`).
- **Un comportamiento por test** y **nombre en español con backticks**:
  ``fun `sin efecto externo no pide permiso`()``.
- **Fallar es información**: el mensaje dice qué se esperaba y qué se obtuvo.
- **Sin `Thread.sleep`**: los timeouts son parámetros inyectados.
- **Criterio de aceptación**: el test pasa Y el comportamiento se puede ver en la app. La validación manual la hace el usuario.

### Dónde van los tests

| Módulo | Ruta | Runner | Qué va ahí |
|---|---|---|---|
| `:backend` | `backend/src/desktopTest/kotlin/rag/**` | JUnit 5 | Los tests portados de FunshiEngineAI (`Bm25IndexTest`, `ChunkerTest`, `CitationVerifierTest`, `AgentLoopTest`, `ToolCallSanitizerTest`, `ToolPermissionsTest`, `ToolRegistryTest`, `PromptToolParserTest`). |
| `:backend` | `backend/src/commonTest/kotlin/**` | `kotlin.test` | Tests nuevos, propios de Kitoru, que corren también en Android. |
| `:core` | `core/src/commonTest/kotlin/**` | `kotlin.test` | Dominio: matriz de permisos, analítica, `ModelPolicy.resolveFor`, repositorios. |
| `:llm-api` | `llm-api/src/commonTest/kotlin/**` | `kotlin.test` | Gateway y errores tipados. |
| `:ui` | `ui/src/commonTest/kotlin/**` | `kotlin.test` | Lógica de UI testeable (el render en sí se prueba con `compose.uiTest` en `desktopTest`). |

Reglas de ubicación:

- **El código que sólo tiene sentido en el escritorio va en `desktopTest`**: si el test usa `java.*`, `java.awt`, `javax.swing` o un archivo real, es de `desktopTest`.
- **El código que es igual en los dos platforms va en `commonTest`**: si el test compila en Android, que compile en los dos.
- **Los dobles compartidos van en `:testkit`** (`kitoru.testing`): `InMemoryFileSystem`, `ScriptedGateway`, `FailingGateway`, `Fixtures`. No se duplican en cada módulo.
- **El helper local de corrutinas** (`rag.test.runTest`) vive en `backend/src/desktopTest/kotlin/rag/TestSupport.kt`; los tests de `commonTest` usan `kotlinx.coroutines.test.runTest`.

## Arquitectura

```
Kitoru/
├── core/       kitoru.domain + kitoru.platform   (dominio, repositorios, seam de portabilidad)
├── llm-api/    llm.api + llm.config + llm.error  (gateway LLM, errores tipados)
├── backend/    rag.*                               (RAG, verificador, agente, herramientas)
├── ui/         kitoru.ui                          (Compose Multiplatform)
├── desktop/    com.funshiengine.kitoru.desktop    (entry point JVM)
├── android/    com.funshiengine.kitoru            (entry point Android)
└── testkit/    kitoru.testing                     (dobles compartidos)
```

Reglas que sostienen esta arquitectura:

- **`:core` no depende de nada del proyecto**: sólo `kotlinx.coroutines` y `kotlinx.serialization`. Es donde vive el dominio y la plataforma; es la superficie de integración.
- **`:llm-api` depende de `:core`**: el gateway LLM y sus tipos.
- **`:backend` depende de `:core` y `:llm-api`**: RAG, agente, herramientas.
- **`:ui` depende de los tres**: Compose Multiplatform, sin I/O directo (toda la persistencia va por repositorios).
- **`:desktop` y `:android` son entry points**: una línea de `main`, nada de lógica.
- **La UI nunca habla con la red**: todo pasa por `:llm-api`.
- **El dominio no depende de la UI**: los ViewModel traducen; el dominio no sabe qué es una pantalla.
- **La analítica es una función pura**: `AnalyticsAnalyzer.analyze(...)` no toca disco ni red. Si hay que testearla, es testear una función.
- **La navegación es una pila manual** (`AppScreen` en `KitoruViewModel`), no `navigation-compose`: no es multiplataforma en Compose 1.7.3.
- **El estado de la UI es `mutableStateOf`**, no `androidx.lifecycle.ViewModel`: no existe en el target común. `KitoruViewModel` es un objeto plano con su propio `CoroutineScope`.

## Reglas de portabilidad (el seam)

Esta es la sección más importante, porque es la que más reglas se rompen.

| Source set | Puede usar | **No** puede usar |
|---|---|---|
| `commonMain` | `kotlin.*`, `kotlinx.coroutines.*`, `kotlinx.serialization.*`, `ktor-client-core` | `java.*`, `javax.*`, `android.*`, `java.nio.file`, `java.time`, `java.awt`, `ProcessBuilder`, `System.getenv`, reloj real |
| `jvmCommonMain` (desktop + android) | lo de `commonMain` + `java.io.*`, `java.util.*` (¡no `java.util.Date`!), `javax.crypto` | `java.nio.file` (API 26; `minSdk` es 24), `java.awt`, `javax.swing`, `java.lang.ProcessBuilder` |
| `desktopMain` | lo de `jvmCommonMain` + `java.awt`, `javax.swing`, `ProcessBuilder`, `skiko` | nada propio de Android |
| `androidMain` | lo de `jvmCommonMain` + `android.*` | `java.awt`, `javax.swing`, `java.nio.file` |

Y las tres reglas:

1. **Si necesitás una capacidad de plataforma, va en `kitoru.platform` como `expect`/`actual`, no se usa directo.** (`FilePath`, `FileSystem`, `stripDiacritics`, `nowMillis`, `ioDispatcher`.)
2. **`desktopMain` y `androidMain` nunca se importan entre sí.** Si algo se usa en los dos, va a `jvmCommonMain`.
3. **El dominio (`:core/kitoru.domain`) no importa nada de `java.*`, ni siquiera en `commonMain`.** Si un tipo del dominio necesita una ruta, usa `FilePath` (una ruta opaca), no `java.io.File`.

### La tabla de seams `expect`/`actual`

Todo lo específico de plataforma entra por acá. **Un `expect` nuevo se agrega a esta tabla en el mismo commit.**

| `expect` (en `commonMain`) | `actual` en `jvmCommonMain` | `actual` en `desktopMain` | `actual` en `androidMain` | Por qué |
|---|---|---|---|---|
| `kitoru.platform.systemFileSystem(): FileSystem` | `FileSystem.jvm.kt` sobre `java.io.File` | (hereda `jvmCommonMain`) | (hereda `jvmCommonMain`) | `java.io.File` existe desde la API 1: el mismo código sirve para los dos platforms sin *desugaring*. `java.nio.file` sería API 26 y `minSdk` es 24. |
| `kitoru.platform.stripDiacritics(text): String` | `FileSystem.jvm.kt` sobre `java.text.Normalizer` (NFD + `Regex("\\p{M}+")`) | (hereda) | (hereda) | Es la base de la tokenización del BM25 y de la comparación de citas: sin normalizar, "Definición" y "definicion" no se encuentran. `Normalizer` está disponible en Android. |
| `kitoru.platform.nowMillis(): Long` | `FileSystem.jvm.kt` → `System.currentTimeMillis()` | (hereda) | (hereda) | El reloj es inyectable para que los tests sean deterministas. |
| `kitoru.platform.ioDispatcher: CoroutineDispatcher` | `Dispatchers.jvm.kt` → `Dispatchers.IO` | (hereda) | (hereda) | El código de red y de disco no debe saber en qué plataforma corre. |
| `llm.api.createLocalModelService(): LocalModelService` | — | `LlamaServerLocalModelService` (levanta `llama-server` como proceso hijo) | `UnavailableLocalModelService` (lanza `LlmException`) | En Android no hay `llama-server`: el stub devuelve un error **explícito** en vez de fallar de forma confusa. |
| `kitoru.ui.platform.rememberFileSaver()` | — | `FileSaver.desktop.kt` (diálogo de Swing) | `FileSaver.android.kt` (SAF / `CreateDocument`) | "Guardar como…" necesita un selector de archivos nativo, y cada platform lo tiene distinto. |

Dos trampas concretas de este repo:

- **El motor HTTP de Ktor no se elige en el código**: `HttpClient()` sin argumento resuelve CIO en escritorio y OkHttp en Android por el classpath. Escribir `HttpClient(CIO)` en `commonMain` rompe el target de Android.
- **`Dispatchers.IO` no se escribe en `commonMain`**: se usa `kitoru.platform.ioDispatcher`.

## Convenciones del código

- **Idioma**: identificadores, paquetes, comentarios, KDoc, mensajes de error, textos de UI y nombres de test en español. Los términos técnicos en inglés cuando es lo natural (`streaming`, `chunk`, `gateway`, `tool`, `token`, `prompt`, `rollback`); no se traducen con inventos: "fragmento (chunk)", no "fragmenttroceado".
- **Kotlin oficial** (`kotlin.code.style=official`, ya está en `gradle.properties`): 4 espacios, sin tabs, sangría de continuación de 4, trailing commas en las colecciones multilínea, `when` exhaustivo, `data class` en vez de clases con getters y setters.
- **Errores tipados**: `LlmException` + `LlmErrorCode`. Un error de dominio nuevo es un `enum class` con su `label` en español. Los códigos van en `SCREAMING_SNAKE_CASE` **en español** (`CLAVE_INVALIDA`, `SIN_CUOTA`, `CONTEXTO_AGOTADO`, `NO_CONFIGURADO`).
- **Fail-fast con la lista completa**: `validate()` devuelve **todos** los problemas, no el primero.
- **Mensajes de error en español** con el valor actual entre paréntesis:
  `"La temperatura debe estar entre 0 y 2 (actual: $temperature)."`
  Terminan en punto, no incluyen `"Error:"` redundante ni stack traces, y no llevan emojis.
- **Los datos persistidos son `data class` + `@Serializable`**, con **valor por defecto en todos los campos** para que un JSON viejo se pueda leer. **Los campos nuevos van al final.** Al leer: `Json { ignoreUnknownKeys = true; encodeDefaults = true }` (ya está en `KitoruJson`).
- **KDoc en toda la API pública** de `:core` y `:llm-api`: qué es, para qué sirve y, si aplica, `@param`/`@return`/`@throws`. La primera línea es una frase que termina en punto.
- **Comentarios que explican el porqué**, nunca el qué.
  ```kotlin
  // Bien
  // `java.nio.file` no existe en Android por debajo de API 26 y `minSdk` es 24:
  // por eso la ruta es opaca y no `Path`.
  val path = FilePath(value)

  // Mal
  // Devuelve el nombre del archivo.
  val name = value.substringAfterLast('/')
  ```
- **Sin referencias de gestión en el código**: ni en comentarios ni en KDoc hay identificadores de fases, hitos, fechas, números de issue, "pendiente" ni "TODO". Eso vive en `docs/`.
- **Sin código muerto**: lo diseñado y no implementado se documenta en `docs/PLAN-INTEGRACION.md` (con su fase).
- **Sin `!!`**, sin `println`, sin `GlobalScope`, sin `Thread.sleep`, sin `lateinit` sin justificación. Se usa `?.`, `?:`, `requireNotNull { "…explicación…" }` o `error("…")`.
- **Coroutines con dispatcher explícito**: `ioDispatcher` para I/O, `Dispatchers.Main` para la UI. La cancelación se propaga: `CancellationException` no se envuelve en un error de negocio.
- **Un tipo público por archivo**, con el nombre del tipo. Las interfaces de repositorio viven en `Repositories.kt`; las implementaciones `File*` en `FileRepositories.kt`; el store JSON en `JsonStore.kt`.
- **Sin dependencias nuevas sin justificación**: si la dependencia exige subir el toolchain (Kotlin, Ktor, AGP) de **todo** el proyecto, primero se evalúa implementar el núcleo chico detrás de un seam.

## Qué no se toca

Lista explícita, para que un agente no rompa algo por buena intención:

- **El dominio de `:core`** (`kitoru/domain/*.kt`) es contrato: si un campo cambia de tipo o se borra, hay que migrar los JSON de `config/` y avisar en `docs/CHANGES.md`.
- **La matriz de permisos** (`kitoru/domain/Capabilities.kt`) y su verificador de visibilidad efectiva: `effectiveVisibility(...)` es el **mínimo** de tres ejes y es *fail-closed* (sin consentimiento explícito, la visibilidad nunca es `FULL`). Es la pieza que hace que la promesa de privacidad sea código.
- **Las firmas públicas de `:llm-api`** (`LlmGateway`, `LlmChunk`, `ToolSpec`, `GatewayConfig`) son la superficie de integración con el Kitoru-Server y con cualquier cliente futuro.
- **El `Routing`/contrato HTTP del Kitoru-Server**: si cambia, se cambia en `docs/API-KITORU-SERVER.md` **y** en el cliente, en el mismo commit.
- **Los archivos que son ports literales de FunshiEngineAI**: `backend/src/commonMain/kotlin/rag/agent/{AgentLoop,ToolRegistry,ToolPermissions,ToolCallSanitizer}.kt`, `backend/src/commonMain/kotlin/rag/retrieval/{Chunker,Models}.kt` y `llm-api/src/commonMain/kotlin/llm/api/Tools.kt` son **copias** del repo hermano, byte a byte. Un cambio ahí se hace **primero** en FunshiEngineAI y después se porta (y se anota en `docs/PORTS.md`). Los que **sí** cambiaron al portar (`Bm25Index`, `CitationVerifier`, `RagEngine`, `Tool`, `LlmGateway`, `LlmErrors`, `Markdown`, `CodeBlock`) tienen su motivo escrito en `docs/PORTS.md`: no se "arreglan" sin actualizar esa fila.
- **`.agents/`**: es memoria de trabajo, no se versiona.
- **`docs/design/`**: es memoria de diseño; no se edita al implementar, salvo para corregir un error.
- **Los tests existentes**: un test que falla es un bug del test o del código; no se desactiva. Si el comportamiento cambió a propósito, se actualiza el test **y** se escribe por qué en `docs/CHANGES.md`.

## Memoria de trabajo: documentos locales ignorados por git

Todo el estado local que un agente necesita para retomar el trabajo sin re-investigarlo vive bajo **una única carpeta ignorada por git**, `.agents/`, en la raíz del repo.

| Ruta | Qué guarda |
|---|---|
| `.agents/MEMORIA.md` | Estado del trabajo en curso, decisiones recientes, comandos que funcionan en este entorno y archivos que no hay que tocar. |
| `.agents/plan-*.md` | El análisis de un tema antes de implementarlo. |

Reglas:

- **Crear lo que haga falta, manteniendo la raíz ordenada**: no crear archivos sueltos en la raíz del repo; la raíz es del proyecto, `.agents/` del agente.
- **Leerlos al arrancar** una tarea que toque los temas que cubren, y **escribir en ellos** lo nuevo relevante de forma incremental.
- Al commitear, **nunca** incluir `.agents/`: un `git add <archivos>` explícito de los archivos propios de la tarea es la única forma correcta de agregar.

## Flujo de trabajo

- **Lista de tareas antes de escribir código**, si el cambio tiene más de un paso. Se trabaja contra esa lista, marcando cada tarea al terminar.
- **Una tarea, un commit** (o varios si es grande), atómicos y convencionales (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`), con el *scope* = módulo o área (`core`, `llm`, `rag`, `agent`, `ui`, `desktop`, `android`, `server`, `sync`, `docs`, `build`, `test`). El mensaje va **en español**, en imperativo, ≤ 72 caracteres y sin punto final.
- **Commits sólo con archivos propios**: `git add <archivos>`, nunca `git add -A` ni `git add .`.
- **Sin `Co-authored-by`**, sin atribuciones ajenas.
- **Comandos destructivos** (`checkout`, `reset`, `restore`, `clean`) requieren permiso explícito del usuario, con la justificación en español.
- **Validar antes de commitear**: `./gradlew build` + `./gradlew allTests` en verde.
- **No declarar un cambio como "resuelto"** hasta que el usuario lo pruebe y lo confirme explícitamente.

## Documentación: qué actualizar con cada cambio

| Tipo de cambio | Documentos a tocar (en el mismo commit) |
|---|---|
| Cambió una API pública de `:core`/`:llm-api` | `docs/ARCHITECTURE.md` + KDoc + `docs/CHANGES.md` |
| Cambió el comportamiento de la app (pantalla, texto, flujo) | `docs/MANUAL-DOCENTE.md` / `docs/MANUAL-ALUMNO.md` + capturas + `docs/CHANGES.md` |
| Cambió el RAG | `docs/MANUAL-RAG.md` + `docs/ARCHITECTURE.md` |
| Cambió el contrato del servidor | `docs/API-KITORU-SERVER.md` + `docs/openapi/kitoru-server-v1.yaml` + `docs/CHANGES.md` |
| Se portó código de FunshiEngineAI | `docs/PORTS.md` (una fila) + `docs/CHANGES.md` |
| Se agregó o cambió un test | `docs/TESTING.md` (si cambió una regla) + `docs/CHANGES.md` |
| Cambió la tabla de seams `expect`/`actual` | `AGENTS.md` (la tabla de portabilidad) + `docs/ARCHITECTURE.md` |
| Se agregó un módulo o un paquete | `README.md` (tabla de módulos) + `docs/ARCHITECTURE.md` (mapa de paquetes) |
| Cualquier cambio | `docs/CHANGES.md` (siempre) |

Los documentos de `docs/` que todavía no existen (los manuales, `PRIVACIDAD.md`, `API-KITORU-SERVER.md`, `TESTING.md`, `CHANGES.md`, `PLAN-INTEGRACION.md`) están planificados en [`docs/design/process/03-documentacion.md`](docs/design/process/03-documentacion.md): el índice de cada uno ya está escrito ahí. Cuando se escriba uno, se hace con el contenido completo, no con un esqueleto.
