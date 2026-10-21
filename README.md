# Kitoru

El aula con IA: **la cátedra sube su material, el alumno chatea contra un modelo que responde con ese material y cita sus fuentes, y el docente ve cómo se usa y qué tan buenas son las respuestas**.

Kitoru es una aplicación **de escritorio y Android** escrita en **Kotlin Multiplatform + Compose Multiplatform**. Corre en la máquina del alumno y en la del docente; la IA puede estar alojada por la institución (el *Kitoru-Server*) o ser **local** en el dispositivo del alumno.

La regla central es la misma de [FunshiEngineAI](https://github.com/Nootechnia/FunshiEngineAI): **no inventar sin referencia**. Cada respuesta se comprueba contra la fuente que dice haber usado, y el resultado se muestra: *citas verificadas* o *citas sin respaldo*.

## Qué estamos montando

- **`:core`** — Dominio y plataforma. `Course`, `Enrollment`, `Material`, `ChatThread`, `ChatMessage`, `ModelPolicy`, `ChatVisibility`, la analítica y la matriz de permisos. Del lado de la plataforma, el *seam* que hace que el mismo código corra en escritorio y en Android: `FilePath`, `FileSystem`, `stripDiacritics`, `nowMillis`, `ioDispatcher` y `KitoruPaths`.
- **`:llm-api`** — Fachada de LLM: `OpenAiCompatibleGateway` habla SSE contra cualquier `/v1/chat/completions` (Kitoru-Server, Ollama, LM Studio, OpenAI) y acumula los `tool_calls` fragmentados. Los errores son **tipados** (`LlmException` + `LlmErrorCode`), nunca cadenas sueltas.
- **`:backend`** — El RAG de la cátedra: indexa los materiales, los trocea, recupera los fragmentos relevantes con **BM25** y arma el prompt con el `CONTEXTO` numerado. Encima, el **bucle de herramientas** (`rag.agent`): el modelo puede pedir acciones a la app, con permisos por chat. Y el **verificador determinista de citas** (`rag.engine.CitationVerifier`).
- **`:ui`** — Compose Multiplatform: selector de rol, home del alumno, chat con streaming y distintivo de citas, configuración del modelo del alumno, y el panel del docente (cátedra, materiales, analítica, chats, alumnos). Render propio de markdown y de bloques de código.
- **`:desktop` / `:android`** — Los dos entry points. Una línea de `main` y nada más.
- **`:testkit`** — Los dobles de prueba compartidos: `InMemoryFileSystem`, `ScriptedGateway`, `Fixtures`.

El mapa completo de paquetes, flujos y seams está en [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Los dos roles

Kitoru tiene **dos maneras de entrar**, y una misma persona puede usarlas en cátedras distintas: el rol no es un botón, se deriva de las inscripciones.

| Rol | Qué hace | Qué ve |
|---|---|---|
| **Alumno** | Se inscribe a una cátedra con un código, chatea, ajusta el modelo (salvo lo que el docente bloquee) y decide qué comparte. | Sus chats, el material de la cátedra, su configuración de modelo y **qué ve su docente**. |
| **Docente** | Crea la cátedra, sube materiales, fija la configuración base del modelo y analiza el uso. | La analítica de la cátedra, la calidad de las respuestas y —según la visibilidad— los chats de sus alumnos. |

La visibilidad es asimétrica y explícita: `Completo`, `Solo métricas` u `Oculto`. La **visibilidad efectiva es el mínimo** entre lo que la cátedra permite y lo que el alumno acepta, así que bajar el nivel es libre y subirlo exige consentimiento.

## Módulos

| Módulo | Paquete raíz | Targets | Qué aporta |
|---|---|---|---|
| `:core` | `kitoru.domain`, `kitoru.platform` | `jvm("desktop")`, `androidTarget` | Dominio, repositorios y el *seam* de portabilidad. No depende de nada del proyecto. |
| `:llm-api` | `llm.api`, `llm.config`, `llm.error` | `jvm("desktop")`, `androidTarget` | Gateway LLM, configuración y errores tipados. |
| `:backend` | `rag.config`, `rag.retrieval`, `rag.engine`, `rag.agent` | `jvm("desktop")`, `androidTarget` | RAG, verificador de citas, bucle de herramientas. |
| `:ui` | `kitoru.ui` | `jvm("desktop")`, `androidTarget` | Pantallas, ViewModel y render (Compose Multiplatform). |
| `:desktop` | `com.funshiengine.kitoru.desktop` | JVM | Entry point de escritorio (`mainClass = com.funshiengine.kitoru.desktop.MainKt`). |
| `:android` | `com.funshiengine.kitoru` | Android | Entry point de Android (`MainActivity`). |
| `:testkit` | `kitoru.testing` | `jvm("desktop")`, `androidTarget` | Dobles compartidos por los tests. |

## Requisitos

| Requisito | Para qué | Notas |
|---|---|---|
| **JDK 21** | Correr Gradle | Los módulos compilan a bytecode JVM 11, pero el *toolchain* que corre Gradle es JDK 21. |
| **Android SDK 35** | `:android` | Va en `local.properties` (`sdk.dir=…`). Sin eso, `:android` no compila; `:desktop` y los tests sí. |
| **Gradle 8.11.1** | Build | Va por el wrapper (`./gradlew`): no hace falta instalarlo. |
| `llama-server` (llama.cpp) | Modelo local | **Opcional**: sólo hace falta si el alumno usa IA local en el escritorio. |

Toolchain actual: Kotlin 2.1.0, Compose Multiplatform 1.7.3, AGP 8.7.2, Gradle 8.11.1, Ktor 3.0.3, kotlinx.serialization 1.7.3, coroutines 1.9.0, JUnit 5.11.4. `compileSdk = 35`, `minSdk = 24`.

## Compilar y ejecutar

```bash
# Compilar todo (desktop + android)
./gradlew build

# Correr la app de escritorio
./gradlew :desktop:run

# Empaquetar el escritorio (también: packageDmg, packageDeb)
./gradlew :desktop:packageMsi

# Compilar el APK de Android
./gradlew :android:assembleDebug
```

La carpeta de datos de la app:

- **Escritorio**: `<user.home>/.kitoru`, salvo que se pase `-Dkitoru.home=…` o la variable de entorno `KITORU_HOME`.
- **Android**: `context.filesDir/kitoru` (dentro del sandbox, sin permisos invasivos).

## Tests

```bash
# Los tests del backend (JUnit 5, sobre el target `desktop`)
./gradlew :backend:desktopTest

# Los tests del dominio (kotlin.test, sobre `commonTest`)
./gradlew :core:desktopTest

# Todos los tests de todos los targets
./gradlew allTests

# Un solo test
./gradlew :backend:desktopTest --tests "rag.retrieval.Bm25IndexTest"
```

El target `jvm("desktop")` genera la tarea `desktopTest`; los tests de `:backend` usan **JUnit 5** (son los portados de FunshiEngineAI) y los de `:core` usan **`kotlin.test`** (así corren en los dos targets). Los dos runners conviven; no se unifican sin motivo. Las reglas de testing están en la sección «Pruebas» del [`AGENTS.md`](AGENTS.md); `docs/TESTING.md` se escribe cuando cierre la primera fase.

## Estado / roadmap

- [x] **Base multiplatform**: repo Gradle con siete módulos, `jvm("desktop")` + `androidTarget`, y un source set manual `jvmCommonMain` compartido por escritorio y Android.
- [x] **`:core` — dominio y plataforma**: cátedra, inscripción, material, hilos, mensajes, analítica, matriz de permisos (64 capacidades en 7 áreas), registro de accesos, `ModelPolicy` con bloqueos por campo, repositorios `File*` sobre `JsonListStore` y el *seam* `kitoru.platform` (`FilePath`, `FileSystem`, `stripDiacritics`, `nowMillis`, `ioDispatcher`, `KitoruPaths`, logger con redacción de secretos).
- [x] **`:llm-api` — gateway LLM**: `OpenAiCompatibleGateway` con SSE y tool-calling, errores tipados, `LocalModelService` con `expect/actual` (real en escritorio, stub explícito en Android).
- [x] **`:backend` — RAG y agente**: `Bm25Index`, `Chunker`, `KnowledgeBase` con reindexado en caliente, `DocumentSource` (el corpus es el material de la cátedra), `RagEngine` con el prompt como lambda, `PromptAssembler`, `CitationVerifier` determinista, `AgentLoop`, `ToolRegistry`, permisos, sanitizador y las herramientas `get_time` y `create_file`.
- [x] **`:ui` — pantallas**: selector de rol, home del alumno, chat con streaming, distintivo de citas, configuración del modelo del alumno, lista de materiales del alumno, y el panel del docente (cátedra, materiales, analítica, chats, alumnos). Render propio de markdown y de bloques de código, con *guardar como…* portable.
- [x] **`:desktop` y `:android`**: los dos entry points abren la app y fijan la carpeta de datos.
- [x] **`:testkit`**: `InMemoryFileSystem`, `ScriptedGateway`, `FailingGateway` y `Fixtures`, compartidos por todos los módulos.
- [x] **Tests**: 108 tests deterministas portados en `:backend:desktopTest` (JUnit 5) y la matriz de permisos en `:core:commonTest` (`kotlin.test`). Cero red, cero reloj real, cero disco real.
- [x] **Documentación raíz**: `README.md`, `AGENTS.md`, [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) y [`docs/PORTS.md`](docs/PORTS.md).
- [ ] **Materiales de la cátedra**: subida de archivos, extracción de texto, indexación y el chat respondiendo con ese corpus.
- [ ] **Analítica completa**: el panel existe; falta el resto de las métricas (actividad, retención, cohortes, horarios) y la exportación.
- [ ] **Sincronización con el Kitoru-Server**: contrato, *outbox* en disco y sincronización delta.
- [ ] **IA local completa**: descarga del GGUF y arranque de `llama-server` (hoy se espera que el binario y el modelo ya estén en la máquina).
- [ ] **Tareas y entregas**, anuncios y accesibilidad.
- [ ] **Manuales de usuario** (`MANUAL-DOCENTE.md`, `MANUAL-ALUMNO.md`, `MANUAL-RAG.md`), `PRIVACIDAD.md`, `API-KITORU-SERVER.md`, `TESTING.md` y `CHANGES.md`.

El plan por fases con criterios de aceptación está en [`docs/design/process/02-roadmap.md`](docs/design/process/02-roadmap.md).

## Documentación

| Documento | Para qué |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Cómo está hecho por dentro: módulos, paquetes, source sets, seams, flujos y diagramas. |
| [`docs/PORTS.md`](docs/PORTS.md) | Mapa de ports desde FunshiEngineAI: qué se portó, qué cambió, por qué y con qué test. |
| [`AGENTS.md`](AGENTS.md) | Cómo trabaja un agente de IA (o un humano nuevo) en este repo: comandos, reglas de portabilidad, estilo y qué no tocar. |
| [`docs/design/CONTEXTO.md`](docs/design/CONTEXTO.md) | El contexto compartido del proyecto: qué es Kitoru, los dos entornos y las reglas de diseño. |
| [`docs/design/`](docs/design/) | La memoria de diseño: por qué se decidió así. No es documentación de uso. |

## Datos y privacidad

Kitoru guarda todo **en el dispositivo**: cátedras, inscripciones, materiales, hilos, mensajes y el registro de accesos, en archivos JSON dentro de la carpeta de datos. Lo único que sale de la máquina es lo que el modelo necesita para responder, y **el alumno decide cuánto**: la visibilidad efectiva es el mínimo entre lo que la cátedra permite y lo que el alumno acepta, y cada acceso del docente queda anotado en un registro de auditoría con el motivo de la decisión.

El detalle completo está en `docs/PRIVACIDAD.md` (aún no escrito) y en el diseño de [`docs/design/security/02-privacidad.md`](docs/design/security/02-privacidad.md).

## Licencia

Por definir: todavía no hay archivo `LICENSE` en el repo.
