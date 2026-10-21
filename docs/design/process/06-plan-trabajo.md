# 06 — Plan de trabajo del Hito 1 (primer hito funcional)

> **Qué es este documento.** La lista de tareas concreta para llevar Kitoru del
> estado real del repo (verificado el 2026-10-21) al **primer hito funcional**:
> un docente crea una cátedra, sube material, lo ve indexado, abre el chat de
> prueba; un alumno se une con el código, chatea, la respuesta cita el material
> con el distintivo de citas verificadas; el docente ve el mensaje del alumno
> y su tasa de citas en la analítica; y el alumno con IA local no aparece en el
> listado de texto del docente.
>
> Cada tarea tiene: **qué hace**, **qué archivos toca**, **cómo se sabe que
> está terminada** y **cuánto cuesta**. Está pensado para usarse como lista de
> trabajo: se puede copiar tal cual a un tablero.
>
> **Estado de este documento:** diseño. No escribe código.
> **Reglas del proyecto que condicionan el plan:** *reutilizar no reescribir*,
> *portable por defecto*, *determinista*, *fail-fast con mensajes en español*,
> *el estudiante conserva agencia*, *el docente no ve lo que no debe*.
>
> **Documentos relacionados:**
> `docs/design/CONTEXTO.md` (referencia común),
> `docs/design/process/02-roadmap.md` (hoja de ruta por fases F0–F11 y
> decisiones §7), `docs/design/teacher/03-materiales.md` (pipeline de
> materiales), `docs/design/domain/01-catedra.md` (ciclo de vida de la cátedra),
> `docs/design/security/01-claves.md` (claves), `docs/design/teacher/06-analitica.md`,
> `docs/design/teacher/07-chats.md` (visibilidad).

---

## 0. Advertencia: el repo se está construyendo en paralelo

Durante la escritura de este plan, **otro agente estaba escribiendo en el
mismo repo**. Al momento de la verificación:

- `:ui` pasó de 2 archivos a 21, y `:desktop` y `:android` aparecieron de la
  nada (creados por ese agente).
- La primera compilación de `:ui` falló con 4 errores; media hora después
  compilaba.

**Consecuencia práctica:** las cifras de "estado real" de §1 son una **foto
del 2026-10-21**, no una garantía. **Antes de arrancar el plan hay que
re-verificar** con los comandos de §1.1 y **corregir §1** con lo que cambió.
Todo lo demás del plan (tareas, criterios, orden) sigue valiendo: las tareas
apuntan a **huecos funcionales**, no a líneas de código, y un hueco funcional
no se cierra porque otro agente haya escrito el archivo.

**Regla operativa recomendada**: este plan asume que hay **una sola persona
modificando el repo**. Si hay dos, hay que Partitionar por área (§4) y hacer
un commit por tarea, porque hoy el repo **no tiene ni un commit** y el trabajo
se está perdiendo en el working tree.

---

## 1. El estado real hoy (verificado, no del contexto)

### 1.1 Comandos de verificación

```bash
# 1) Qué compila
./gradlew :core:compileKotlinDesktop :llm-api:compileKotlinDesktop \
          :backend:compileKotlinDesktop :ui:compileKotlinDesktop \
          :desktop:compileKotlin :android:assembleDebug

# 2) Qué tests pasan (y cuántos)
./gradlew :backend:desktopTest --rerun-tasks
grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
     backend/build/test-results/desktopTest/*.xml

# 3) Estado de git
git log --oneline   # hoy: "does not have any commits yet"
git status --short
```

### 1.2 Módulos: estado real

| Módulo | Estado real (2026-10-21) | Detalle |
|---|---|---|
| **`:core`** | ✅ compila desktop y Android | 15 archivos: 9 en `domain/` (Analytics, AnalyticsAnalyzer, Chat, Course, FileRepositories, JsonStore, ModelPolicy, Repositories, Roles), 4 en `platform/` (FileSystem, KitoruPaths, Text, Dispatchers), 2 en `jvmCommonMain/`. **Cero tests.** |
| **`:llm-api`** | ✅ compila desktop y Android | 8 archivos. `LlmGateway` + `OpenAiCompatibleGateway` (SSE, tool_calls nativos, errores tipados), `LocalModelService` con `expect/actual` (desktop = `llama-server` real; Android = stub que lanza `LlmException`). **Cero tests.** |
| **`:backend`** | ✅ compila desktop y Android · ✅ **108 tests verdes** | 15 archivos en `commonMain` (RAG completo: `Bm25Index`, `Chunker`, `KnowledgeBase`, `CourseMaterialSource`, `RagEngine`, `CitationVerifier`, `AgentLoop`, tools) + 8 en `desktopTest`. Verificado con `--rerun-tasks`: 8 clases, 108 tests, 0 fallas. |
| **`:ui`** | ✅ compila (recién arreglado) · **0 tests** | 21 archivos. `KitoruViewModel` + `KitoruApp` (pila de navegación manual, 12 `AppScreen`), `Theme`, `ChatEngine`/`ChatViewModel`/`ChatScreen`, `StudentHomeScreen`, `StudentModelScreen`, `TeacherHomeScreen`/`TeacherCourseScreen`/`TeacherMaterialsScreen`/`TeacherAnalyticsScreen`/`TeacherChatsScreen`/`TeacherStudentsScreen`, `CitationBadge`/`SourceList`/`ToolCallRow`/`MetricCard`, `Markdown`/`CodeBlock`/`MathSegment`, `FileSaver` (expect/actual). |
| **`:desktop`** | ✅ compila (recién creado) | `desktop/build.gradle.kts` + `Main.kt` con `mainClass = "com.funshiengine.kitoru.desktop.MainKt"`. Fija `KitoruPaths.setDataDir` a `<user.home>/.kitoru` (o `-Dkitoru.home` / `KITORU_HOME`). |
| **`:android`** | ❌ **NO compila** | `MainActivity.kt:29` y `:36`: *"Functions which invoke @Composable functions must be marked with the @Composable annotation"*. Falta `@Composable` en el `setContent` de `android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt`. Además `:android` **no es parte del hito 1** (§8). |
| **CI** | ❌ no existe | No hay `.github/workflows/`. |
| **Git** | ❌ **cero commits** | `git log` → *does not have any commits yet*. Todo está untracked. `.gitignore` existe pero sin trackear. |

### 1.3 Qué tests pasan

| Suite | Resultado |
|---|---|
| `:backend:desktopTest` | ✅ **108 tests, 0 fallas** (8 clases JUnit 5, `useJUnitPlatform()` correctamente configurado en `backend/build.gradle.kts:65`) |
| `:core:*Test` | ❌ no existe la suite |
| `:llm-api:*Test` | ❌ no existe la suite |
| `:ui:*Test` | ❌ no existe la suite |
| `:desktop`, `:android` | ❌ no existen |

> **Consecuencia:** **todo el dominio que decide la privacidad está sin
> probar.** `ModelPolicy.resolveFor`, `AnalyticsAnalyzer`, `ChatVisibility` y
> los repositorios no tienen ni un test. Eso es la brecha más urgente del
> proyecto, no la UI.

### 1.4 Hallazgos concretos: bugs y huecos que bloquean el hito 1

Ordenados por lo que bloquean. Todos verificados leyendo el código.

| # | Hallazgo | Archivo:línea | Bloquea |
|---|---|---|---|
| **B1** | **El chat nunca tiene fuentes.** `ChatScreen` crea un `KnowledgeBase` **vacío** y nunca lo puebla ni llama a `sync()`. Con `useDocs = true`, `RagEngine.prepare` devuelve `sources = emptyList()` ⇒ `ChatEngine.ask` deja `citation = null` ⇒ **el distintivo de citas nunca aparece** y el RAG no recupera nada. | `ui/.../chat/ChatScreen.kt:65-67` + `backend/.../KnowledgeBase.kt:24` | **E1, E2** |
| **B2** | **`KitoruViewModel` no puede crear cátedras ni inscripciones.** No existen `createCourse`, `joinCourse`, `setVisibility`, `uploadMaterial`. `TeacherHomeScreen` lo admite en un placeholder: *"La creación de cátedras llega en el próximo hito"*. | `ui/.../KitoruViewModel.kt` (227 líneas, sin esos métodos) | **E1–E4** |
| **B3** | **Nada se persiste del chat.** `ChatViewModel` guarda `UiChatMessage` en un `mutableStateListOf` en memoria. `ThreadRepository` y `MessageRepository` existen y están completos, pero **nadie los usa**. Sin persistencia no hay analítica. | `ui/.../chat/ChatViewModel.kt:66` | **E3** |
| **B4** | **`AnalyticsAnalyzer` filtra mal las fuentes.** El cálculo de `topSources` recorre **todos** los hilos y **todos** los mensajes sin mirar `enrollment.visibility.showsMetrics`. Un alumno con `visibility = NONE` **se filtra en el texto de la respuesta pero deja su material en "Material más consultado"**. Es una fuga de privacidad por la ruta que el diseño dice cerrar. | `core/.../domain/AnalyticsAnalyzer.kt:69-81` | **E4** |
| **B5** | **`FileMessageRepository.delete(threadId)` no borra nada.** Llama a `removeById(threadId)`, que compara contra el **id del mensaje**, no contra el `threadId`. | `core/.../domain/FileRepositories.kt:126` | E3 |
| **B6** | **`listForCourse` depende de una convención no escrita:** filtra por el prefijo `"$courseId|"` del id del hilo. Un hilo con id arbitrario desaparece. | `core/.../domain/FileRepositories.kt:121` | E3 |
| **B7** | **El texto del material vive dentro de `materials.json`.** `JsonStore` reescribe el archivo entero en cada `save`: con 50 materiales de 200 KB son 10 MB reescritos por guardado. | `core/.../domain/Course.kt:107` + `JsonStore.kt:70` | C (escala) |
| **B8** | **`FileSystem` no tiene `readBytes`/`writeBytes`/`rename`.** Sin bytes no se puede subir un archivo; sin `rename` no se puede escribir atómicamente. | `core/.../platform/FileSystem.kt:47-57` | C |
| **B9** | **No hay `InMemoryFileSystem`.** Los tests de repositorio no se pueden escribir sin tocar el disco. | — | I |
| **B10** | **No existe `MaterialStatus` ni pipeline de extracción.** `Material` no tiene `status`, `extractor`, `chunkCount`, `originalName`, `extractedAt`. No hay `TextExtractor`, ni `MaterialFormat`, ni `FilePicker`. | `core/.../domain/Course.kt:91-114` | **E1** |
| **B11** | **La API key se persiste en tres lugares**, incluido `ChatThread.effectiveModel` (un `ModelPolicy` completo con `apiKey` en claro). No hay `redacted()`. | `core/.../domain/ModelPolicy.kt:44` + `Chat.kt:24` | seguridad |
| **B12** | **`KitoruViewModel` usa `Dispatchers.Main`** ⇒ cualquier test de ViewModel en `:ui:desktopTest` revienta sin `Dispatchers.setMain`. | `ui/.../KitoruViewModel.kt:74` | I |
| **B13** | **No hay forma de que el material del docente llegue al alumno.** El RAG corre **en el cliente** (`KnowledgeBase` es local), pero los materiales viven en el disco del docente y el Kitoru-Server no existe. Sin un mecanismo de reparto, el alumno chattea **sin corpus**. | `backend/.../DocumentSource.kt:27` | **E2** |
| **B14** | **La configuración de la cátedra tampoco llega al alumno.** `ModelPolicy` (URL, modelo, key, prompt) está en `Course`, que vive en el disco del docente. El alumno no tiene ni la URL del servidor ni la key. | `core/.../domain/Course.kt:67` | **E2** |

### 1.5 Resumen: de 0 a 1, qué falta

| Capa | Estado | Qué falta para el hito 1 |
|---|---|---|
| Build | 4 de 6 módulos compilan | Arreglar `:android` (o sacarlo) · primer commit · CI |
| Tests | 108 en `:backend`, 0 en el resto | `InMemoryFileSystem` + suites de `:core`, `:ui`, `:llm-api` |
| Dominio | Completo y bien diseñado | `MaterialStatus`, `JoinCode`, `redacted()`, arreglar `AnalyticsAnalyzer` |
| Persistencia | JSON por tipo, funciona | Corregir `MessageRepository`, sacar el texto del material del JSON |
| Materiales | **0 %** | `TextExtractor`, `MaterialFormat`, `FilePicker`, `MaterialIngestionService`, `CourseIndex` |
| Chat | Motor completo, **sin fuentes ni persistencia** | Poblarel KB, persistir hilo y mensajes, exponer los fragmentos |
| Cátedras | No se pueden crear ni joining | `createCourse`, `joinCourse`, `setVisibility` |
| Analítica | Calculador puro listo, **filtrado mal** | Arreglar `topSources`, cablear datos reales |
| Privacidad | Reglas de dominio listas | `TeacherChatRepository` que filtre **antes** de leer |
| Distribución | **inexistente** | Paquete de cátedra (`.kitoru`) export/import |

---

## 2. El objetivo del hito 1: escenarios de aceptación

Cada escenario es un guion **ejecutable a mano** por una persona, en una
instalación limpia, con el modelo de IA que se indique. Se verifican en este
orden porque cada uno presupone el anterior.

### Precondiciones (lo que hay que tener antes de empezar)

- **P1** — Un endpoint OpenAI-compatible alcanzable. Para la demo: **Ollama**
  (`http://127.0.0.1:11434/v1`, modelo `qwen2.5:3b` o similar) o
  **`llama-server`** con un GGUF. La app habla con él por `remoteBaseUrl`.
- **P2** — La app desktop arranca: `./gradlew :desktop:run` abre una ventana
  titulada "Kitoru" y el `dataDir` es `<user.home>/.kitoru` (o el que se pase
  con `-Dkitoru.home`).
- **P3** — Un material de prueba: `Apuntes-IA.md` con la frase exacta:
  `"El gradiente se calcula como la derivada de la función de pérdida."` y una
  segunda frase con un nombre propio y un número (para que el verificador de
  citas pueda marcar "sin respaldo": *"el curso usa DirectX 12"*).
- **P4** — **Dos perfiles locales distintos** (el hito 1 es multi-perfil
  manual, no hay login): el docente y el alumno. Se distinguen por
  `UserProfile.id`, que hoy está fijo a `"alumno-local"` en
  `ui/.../student/StudentHomeScreen.kt:189` — **hay que volverlo configurable**
  (tarea E1).

> **Nota sobre P3**: se usa Markdown a propósito. El PDF queda fuera del hito 1
> (§6, D4). El material tiene que ser el que el docente **tiene**, no el que
> la app puede parsear.

---

### E1 — El docente crea una cátedra, sube un material y lo ve indexado

**Dado** la app abierta como docente, sin cátedras
**Cuando** crea la cátedra "IA-2026-1 · Inteligencia Artificial" (cursada
`2026-1`), y sube `Apuntes-IA.md`
**Entonces**

1. La cátedra aparece en "Mis cátedras" con un **código de inscripción** de 8
   caracteres visible y copiable.
2. El material aparece en la lista con su **estado** ("Listo"), su **tamaño**,
   su **fecha** y el **número de fragmentos** que aporta al índice.
3. Un texto de estado dice, literalmente, algo como
   `"Índice listo: 1 documentos / 3 fragmentos"`.
4. El docente abre la **pestaña "Probar pregunta"**, escribe
   *"¿cómo se calcula el gradiente?"* y la respuesta **cita el material** con
   un marcador `[1]` y un distintivo **"Citas verificadas (1)"**.
5. El material **sigue indexado después de cerrar y reabrir la app** (o, si no,
   se reindexa al abrir la cátedra y el estado lo dice).

**Cómo se verifica**: a mano + test de integración
`MaterialToChatPipelineTest` (tarea I3) que sube un material, indexa, pregunta
y asserta `citation.verified >= 1`.

---

### E2 — El alumno se une con el código, chatea y la respuesta cita el material

**Dado** la app abierta como **alumno** (perfil distinto al del docente), en
otra carpeta de datos o en la misma con perfil switched
**Cuando** pega el código de inscripción, se une, y pregunta
*"¿cómo se calcula el gradiente?"*
**Entonces**

1. La cátedra aparece en "Mis cátedras" con una línea que dice **qué ve el
   docente** ("Tu docente ve el texto de tus conversaciones.").
2. El chat abre con el **modelo de la cátedra** (el `modelLabel` coincide con
   el que configuró el docente).
3. La respuesta **trae al menos un marcador `[n]`**.
4. La burbuja muestra el **distintivo de citas verificadas**: el texto literal
   `"Citas verificadas (N)"` con `N >= 1`, con ícono de verificado.
5. Debajo del distintivo aparece la **lista de fuentes** con la etiqueta del
   fragmento (por ejemplo `"cátedra: Apuntes-IA.md"`).
6. Al reabrir la app, el hilo y sus mensajes **siguen ahí**, con su distintivo.

**Cómo se verifica**: a mano + test de `:ui` con un `FakeGateway` que devuelve
`"El gradiente se calcula como la derivada [1]."`.

---

### E3 — El docente ve en la analítica el mensaje del alumno y su tasa de citas

**Dado** el hilo del alumno del escenario E2
**Cuando** el docente abre "Analítica"
**Entonces**

1. "Por alumno" muestra una fila del alumno con **1 mensaje**.
2. La fila muestra la **tasa de citas**: `"1 de 1 citas respaldadas (100 %)"`.
3. El **mensaje del alumno aparece con su texto** (el docente tiene
   `visibility = FULL`): el docente lee *literalmente* lo que escribió el
   alumno.
4. En "En números" la tarjeta de citas muestra el mismo **100 %**.
5. "Material más consultado" lista **`Apuntes-IA.md` con 1 consulta**.
6. Los números **coinciden con lo que dice el chat**: 1 mensaje del alumno, 1
   respuesta, 1 cita verificada.

**Cómo se verifica**: a mano + **test golden** de `AnalyticsAnalyzer` con un
dataset fijo (tarea I1) que asserta los mismos números.

---

### E4 — El alumno con IA local no aparece en el listado de texto del docente

**Dado** que el alumno cambió su configuración a **IA local**
(`mode = LOCAL`, `remoteBaseUrl = http://127.0.0.1:8081/v1`), chatteó, y su
inscripción quedó en `visibility = METRICS_ONLY`
**Cuando** el docente abre "Chats" → su ficha → el listado de hilos
**Entonces**

1. **El listado de hilos del alumno está vacío**: no hay títulos ni
   previsualizaciones.
2. El texto de sus mensajes **no aparece en ninguna parte** de la pantalla del
   docente.
3. Aparece un aviso explícito: *"Este alumno usa IA local y no compartió sus
   conversaciones"*.
4. El **repositorio de mensajes no se invoca** para ese alumno (prueba
   automatizada con un fake que cuenta llamadas: `assertEquals(0, fake.calls)`).
5. En la **analítica**, el alumno **sí** cuenta en los agregados (mensajes,
   tasa de citas) pero su **texto** no es accesible.
6. El alumno, en su propia home, ve la línea de visibilidad que dice que el
   docente ve métricas pero **no** el texto.

**Cómo se verifica**: a mano + test de `TeacherChatRepository` que cuenta
llamadas (tarea G2).

---

### E5 — (transversal) La app es coherente después de cerrar y abrir

**Dado** la app con una cátedra, un material y un hilo
**Cuando** se cierra la ventana y se vuelve a abrir
**Entonces**

1. La cátedra, el material y el hilo siguen ahí.
2. Los `config/*.json` son **JSON válido** y legibles con un editor de texto.
3. La app no tira excepciones en consola.

**Cómo se verifica**: a mano + `JsonStore` con escritura atómica (tarea B2).

---

## 3. Las tareas

Ordenadas por dependencia. Cada una tiene un **criterio de terminado** que se
puede comprobar con un comando o a mano, sin pedirle permiso a nadie.

### Área A — Que la app arranque y el trabajo no se pierda

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **A1** | **Verificar y dejar compiling el módulo `:desktop`** (lo está construyendo otro agente: hay que revisar que use `KitoruPaths.setDataDir`, `systemFileSystem()` y los tres repositorios `File*`). | `desktop/build.gradle.kts`, `desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt` | `./gradlew :desktop:run` abre una ventana titled **"Kitoru"** con el selector de rol, y `%USERPROFILE%/.kitoru/config/` se crea. | 1 |
| **A2** | **Arreglar `:android`** (`@Composable` en el `setContent` de `MainActivity`). Opcional: si no entra en el hito 1, **sacarlo de `settings.gradle.kts`** para que el build verde signifique algo. | `android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt` **o** `settings.gradle.kts` | `./gradlew :android:assembleDebug` produce el APK, **o** `:android` deja de existir en `settings.gradle.kts` y `:android:assembleDebug` da "task not found" de forma consciente. | 1 |
| **A3** | **Primer commit + `.gitignore` revisado.** El repo tiene **cero commits** y hay otro agente escribiendo encima: sin commit, el trabajo se pierde. | `.gitignore`, todo el árbol | `git log --oneline` muestra ≥ 1 commit. `git status --short` sólo muestra `build/`, `.gradle/`, `local.properties` como ignorados. **Regla: un commit por tarea del plan.** | 1 |
| **A4** | **CI mínima**: workflow que corra `./gradlew :core:compileKotlinDesktop :llm-api:compileKotlinDesktop :backend:compileKotlinDesktop :ui:compileKotlinDesktop :desktop:compileKotlin build` + `:backend:desktopTest` en JDK 21, con caché de Gradle. | `.github/workflows/ci.yml` | El workflow está **verde** en el primer push. | 2 |

### Área B — Dominio y persistencia (todo esto es testeable sin disco)

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **B1** | **`InMemoryFileSystem`** en `commonMain` de `:core`: mapa de rutas → bytes, con `exists`, `isDirectory`, `sizeBytes`, `readText`, `writeText`, `readBytes`, `writeBytes`, `delete`, `createDirectories`, `listFiles`, `copy`, `rename`. `readText` de un inexistente devuelve `""` (nunca excepción). | **nuevo** `core/src/commonMain/kotlin/kitoru/platform/InMemoryFileSystem.kt` | Test en `core/src/desktopTest/kotlin/kitoru/platform/InMemoryFileSystemTest.kt`: escribir, listar, renombrar, borrar, leer inexistente ⇒ sin excepción. | 3 |
| **B2** | **`FileSystem` binario + escritura atómica**: agregar `readBytes(path)`, `writeBytes(path, bytes, append=false)`, `rename(from, to)` a la interfaz **y** a `jvmCommonMain`. Además, `JsonStore._write` escribe a `<archivo>.tmp` y renombra sobre el destino. | `core/.../platform/FileSystem.kt`, `core/src/jvmCommonMain/.../FileSystem.jvm.kt`, `core/.../domain/JsonStore.kt` | Test: escribir 1 MB de bytes, renombrar, leer de vuelta idéntico. Test: un `writeBytes` interrumpido deja el `.json` original **íntegro** (se simula fallando el `rename`). | 2 |
| **B3** | **`MaterialStatus`** + campos nuevos de `Material`: `status` (`PENDING/EXTRACTING/INDEXING/READY/NEEDS_REVIEW/FAILED`), `statusDetail`, `originalName`, `extractor`, `extractedAt`, `chunkCount`, `textPreview` (los primeros 2 000 caracteres). **Todos con valor por defecto** (compatibilidad con los JSON existentes). | `core/.../domain/Course.kt` | Test de ida y vuelta: un `Material` serializado con los campos por defecto se deserializa sin excepción y `status == PENDING`. | 2 |
| **B4** | **`MaterialRepository` delega el texto a un archivo.** Interfaz: `suspend fun loadText(id: String): String`, `suspend fun updateExtraction(id, text, warning, extractor, status, chunkCount)`, `suspend fun setVisible(id, visible)`. Implementación: metadatos en `config/materials.json`, texto en `courses/<courseId>/text/<materialId>.txt`. | `core/.../domain/Repositories.kt`, `core/.../domain/FileRepositories.kt` | Test con `InMemoryFileSystem`: 50 materiales de 10 KB ⇒ `materials.json` < 20 KB, y `loadText` devuelve el texto **byte a byte** igual. | 5 |
| **B5** | **Arreglar `FileMessageRepository`**: `delete(threadId)` borra por `threadId` (**hoy está roto**), y `listForCourse` deja de filtrar por el prefijo `"$courseId|"` (usa el `ThreadRepository` para cruzar, o guarda `courseId` en el mensaje). | `core/.../domain/FileRepositories.kt` | Test: un hilo con id **arbitrario** (`"abc-123"`) aparece en `listForCourse(courseId)`; `delete(threadId)` borra **todos** sus mensajes y deja los del otro hilo. | 3 |
| **B6** | **`JoinCode`**: generación de **8 caracteres base32** (alfabeto sin `I`, `L`, `O`, `U` — se leen en un pizarrón), normalización (sin espacios, en mayúsculas), **rotación** (el código viejo muere al instante) y **comparación** sin distinguir mayúsculas. | **nuevo** `core/src/commonMain/kotlin/kitoru/domain/JoinCode.kt` | Test: 1 000 códigos generados son únicos, tienen 8 caracteres del alfabeto, y `normalize("ia2-6 01") == "IA2601"`. Un código rotado no valida el anterior. | 3 |
| **B7** | **Arreglar la fuga de privacidad de `AnalyticsAnalyzer`**: `topSources` debe contar **sólo** los hilos de alumnos con `visibility.showsMetrics`. Además: `perStudent` no debe incluir a los docentes en la lista "Por alumno". | `core/.../domain/AnalyticsAnalyzer.kt` | Test: un alumno con `visibility = NONE` whose respuesta cita `Material X` ⇒ `topSources` **no** menciona `Material X`. | 2 |
| **B8** | **`ModelPolicy.redacted()`**: devuelve la política con `apiKey = ""`. `ChatThread.effectiveModel` se persiste **siempre** redactado. `ModelPolicy` gana `redacted()` y `ModelPolicy.resolveFor` no cambia. | `core/.../domain/ModelPolicy.kt`, el lugar donde se persiste el hilo | Test: se serializa un `ChatThread` con `effectiveModel.apiKey = "secreto"` ⇒ el JSON **no** contiene `"secreto"`. Test: `resolveFor` con `API_KEY` bloqueado sigue devolviendo la key de la cátedra. | 2 |

### Área C — Materiales: extraer, guardar, indexar

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **C1** | **`MaterialFormat`**: detección por extensión **y por firma binaria** (`%PDF-`, `PK\x03\x04` → OOXML, `{\rtf`). Si la extensión y la firma discrepan ⇒ `warning` ("El archivo se llama .pdf pero su contenido no es un PDF"). Formatos del hito 1: `MARKDOWN`, `TEXT`, `CSV`, `HTML`, `LINK`, `UNKNOWN`. | **nuevo** `core/src/commonMain/kotlin/kitoru/material/MaterialFormat.kt` | Test: un `.pdf` renombrado a `.txt` se detecta como PDF; un `.md` se detecta como Markdown; un `.exe` como `UNKNOWN`. | 3 |
| **C2** | **`TextExtractor` + `PlainTextExtractor`**: interfaz `suspend fun extract(bytes: ByteArray, name: String): ExtractionResult` con `data class ExtractionResult(text, warning, extractor)`. Implementaciones en `commonMain`: `txt`/`md` (UTF-8 con fallback Latin-1), `csv` (a texto con ` | `), `html` (a texto legible: sin scripts, sin estilos, entidades des-escapadas). **PDF: no soportado** ⇒ devuelve `warning = "El formato PDF todavía no está soportado. Guardá el material como Markdown (.md) o pegá el texto."` y `status = NEEDS_REVIEW`. | **nuevo** `core/src/commonMain/kotlin/kitoru/material/TextExtractor.kt`, `.../PlainTextExtractor.kt` | Test: un `.md` con acentos y emojis extrae el texto exacto; un `.html` con `<script>` **no** incluye el JavaScript; un `.pdf` ⇒ `NEEDS_REVIEW` con el aviso en español. | 4 |
| **C3** | **`MaterialIngestionService`**: el pipeline de 5 pasos — (1) copiar el archivo a `courses/<courseId>/files/<materialId>__<nombre>`; (2) detectar formato; (3) extraer; (4) persistir (`Material` + `.txt`); (5) devolver el `Material` con su `status`. Límite de tamaño: **50 MB**, con mensaje en español y **sin** dejar el archivo a medias. | **nuevo** `core/src/commonMain/kotlin/kitoru/material/MaterialIngestionService.kt` | Test: un `.md` de 3 KB termina en `READY`, con `extractedText` igual al archivo, `extractor = "plain-text"`, `sizeBytes` correcto y el archivo original copiado en `files/`. Un archivo de 60 MB ⇒ error en español y **el `.tmp` no queda en disco**. | 5 |
| **C4** | **`CourseIndex`**: envuelve `KnowledgeBase` + `CourseMaterialSource` y da una API de cátedra: `suspend fun reindex(materials: List<Material>): IndexResult`, `fun retrieve(query, k): List<Chunk>`, `val chunkCount: Int`. **El índice NO se persiste**: se reconstruye al abrir la cátedra (BM25 sobre 1 000 fragmentos es instantáneo). | **nuevo** `backend/src/commonMain/kotlin/rag/retrieval/CourseIndex.kt` | Test: 3 materiales ⇒ `chunkCount > 0`; `reindex` con `visible = false` **no** los incluye; reindexar dos veces da el mismo `chunkCount`. | 4 |
| **C5** | **`FilePicker` (seam de plataforma)**: interfaz en `:core` (`core/.../platform/FilePicker.kt`) con `launch(request, onResult)` y `suspend fun copyTo(file: PickedFile, dest: FilePath, fs: FileSystem): Long`. Implementación **desktop** con `java.awt.FileDialog` (diálogo nativo, multi-selección con `File.pathSeparatorChar`). Android: **stub** que devuelve lista vacía (el SAF es hito 2). | **nuevo** `core/.../platform/FilePicker.kt`, **nuevo** `ui/src/desktopMain/kotlin/kitoru/ui/platform/DesktopFilePicker.kt`, **nuevo** `ui/src/androidMain/kotlin/kitoru/ui/platform/AndroidFilePicker.kt` (stub) | Test con un `FakeFilePicker`: la ingesta corre con un archivo falso sin tocar disco. A mano: el diálogo nativo abre y devuelve el archivo elegido con su nombre y tamaño. | 6 |

### Área D — Chat: que el corpus exista y el hilo se guarde

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **D1** | **`ChatOutcome` expone los fragmentos, no sólo la etiqueta.** Agregar `data class RetrievedSource(index: Int, label: String, heading: String, snippet: String)` y cambiar `ChatOutcome.sources` a `List<RetrievedSource>`. La UI puede mostrar el fragmento real y el "ver en el material". | `ui/.../chat/ChatEngine.kt` | Test: un `FakeGateway` + un `KnowledgeBase` con 1 material ⇒ `outcome.sources.first().snippet` **contiene** el texto del material y `label` es `"cátedra: Apuntes-IA.md"`. | 3 |
| **D2** | **Persistencia del hilo y los mensajes.** `ChatViewModel` recibe `ThreadRepository`, `MessageRepository` y `CourseRepository`. Al enviar: crea/actualiza el `ChatThread` (id `"<courseId>\|<uuid>"`, `authorId`, `authorName`, `title` = primeras palabras de la primera pregunta, `modelLabel`, `effectiveModel` **redactado**) y guarda cada `ChatMessage` con `sources`, `citation`, `latencyMs`, `tokensIn/Out`. | `ui/.../chat/ChatViewModel.kt` | Test: enviar un mensaje con `FakeGateway` ⇒ `MessageRepository.listForThread(threadId)` tiene 2 mensajes (user + assistant) y el de assistant tiene `citation != null` y `latencyMs >= 0`. Cerrar y reabrir la app ⇒ el hilo sigue en la lista. | 5 |
| **D3** | **Que el chat use el índice real** (arregla **B1**). `ChatScreen` deja de crear un `KnowledgeBase` vacío: recibe un `CourseIndex` ya **poblado** (`reindex` hecho al abrir la cátedra) y lo usa. La reindexación se dispara al entrar a la cátedra y cuando el docente sube material, con un estado visible ("Indexando…"). | `ui/.../chat/ChatScreen.kt`, `ui/.../KitoruViewModel.kt` | **Este es el cambio que enciende el distintivo de citas.** Test: con 1 material indexado y un `FakeGateway` que cita `[1]`, la respuesta trae `citation.verified == 1`. Sin materiales ⇒ `citation == null` y la UI **no** muestra distintivo (comportamiento correcto, no un error). | 4 |
| **D4** | **Distintivo de citas: ya existe `CitationBadge`; falta el detalle.** Agregar que al tocar el distintivo se abra el **detalle cita por cita**: por cada `[n]`, el fragmento citado y el motivo (`supported ? "Respaldada" : reason` — el `reason` ya lo produce `CitationVerifier` en español). `CitationStats` no guarda el detalle ⇒ hace falta `CitationStats.details: List<CitationDetail>` (con default vacío, retrocompatible). | `ui/.../components/ChatParts.kt`, `core/.../domain/Chat.kt`, `ui/.../chat/ChatEngine.kt` | Test: un `FakeGateway` que responde "el curso usa DirectX 12 [1]" contra un material que dice "Vulkan" ⇒ el distintivo dice **"1 cita sin respaldo"** y el detalle muestra el motivo *"La cita menciona \"DirectX 12\", que no aparece en la fuente."*. | 3 |
| **D5** | **"Chat de prueba" del docente.** Misma pantalla, pero `authorId = "teacher:<profileId>"`, `role = TITULAR` y **excluido de la analítica** (el `AnalyticsAnalyzer` ya filtra `role == ALUMNO` en los totales; falta excluirlo también de `topSources` — ver B7). El chat de prueba **no** se persiste como hilo de alumno (o se persiste con `authorId` de docente y no cuenta). | `ui/.../teacher/TeacherCourseScreen.kt` (o pantalla nueva), `ui/.../chat/ChatScreen.kt` | A mano: el docente pregunta en el chat de prueba, ve el distintivo, y **la analítica no cambia**. Test: `AnalyticsAnalyzer` con un hilo de docente de 5 mensajes ⇒ `totalMessages == 0`. | 3 |
| **D6** | **`resolveFor` por mensaje.** El `ChatEngine` recibe la política del alumno + la de la cátedra y aplica `coursePolicy.resolveFor(studentChoice)` **en cada mensaje** (no al arrancar). Si un campo está bloqueado, la UI del alumno lo muestra deshabilitado con el motivo. | `ui/.../chat/ChatEngine.kt`, `ui/.../chat/ChatViewModel.kt`, `ui/.../student/StudentModelScreen.kt` | Test: `lockedFields = {TEMPERATURE}`, el alumno pone `temperature = 0.9` ⇒ el `GatewayConfig` que llega al gateway tiene `temperature = 0.0` (el de la cátedra). | 2 |

### Área E — Cátedras e inscripciones (el dominio que falta)

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **E1** | **Identidad local configurable.** `UserProfile.id` deja de ser el constante `"alumno-local"`: hay un `Perfil local` (nombre + id) que el usuario edita, y un **selector de perfil** en el `RolePickerScreen` para cambiar entre docente y alumno en la misma máquina. (El multi-perfil es §4.13 del roadmap: fuera de alcance para el *producto*, pero **necesario** para poder demostrar E1–E4 en una sola máquina.) | `ui/.../KitoruViewModel.kt`, `ui/.../KitoruApp.kt`, `ui/.../student/StudentHomeScreen.kt` | A mano: se crea el perfil "Docente" y el perfil "Ana" y se cambia entre los dos; cada uno ve sus propias cátedras. | 3 |
| **E2** | **`KitoruViewModel.createCourse(...)`**: genera `id`, `code`, `name`, `term`, `joinCode = JoinCode.generate()`, `createdAt`, y **crea la `Enrollment` del creador con `role = TITULAR`** (si no, el docente no ve su propia cátedra). Persiste en `CourseRepository` + `EnrollmentRepository`. | `ui/.../KitoruViewModel.kt` | Test: `createCourse` ⇒ `CourseRepository.get(id)` devuelve la cátedra con `joinCode` de 8 caracteres, y `EnrollmentRepository.listForCourse(id)` tiene **una** inscripción `TITULAR`. Un segundo `createCourse` genera **otro** código. | 3 |
| **E3** | **`KitoruViewModel.joinCourse(code)`**: busca la cátedra por `joinCode` (normalizado), si no existe ⇒ error en español *"No encontré ninguna cátedra con ese código."*; si ya está inscripto ⇒ *"Ya estás en esa cátedra."*; si no, crea `Enrollment(role = ALUMNO, visibility = FULL, joinedAt = now)`. **Si la cátedra no está en el disco local** ⇒ error explícito: *"esa cátedra todavía no está en este dispositivo"*. | `ui/.../KitoruViewModel.kt` | Test: los tres casos (no existe / ya estás / éxito). Tras el éxito, `learningCourses` incluye la cátedra. | 3 |
| **E4** | **`KitoruViewModel.setVisibility(enrollmentId, visibility)`** y `setModelMode(mode)`: el docente cambia la visibilidad de una inscripción; el alumno declara su modo (LOCAL/REMOTE). | `ui/.../KitoruViewModel.kt` | Test: cambiar la visibilidad ⇒ `EnrollmentRepository.get(id).visibility` cambió; `activeVisibility` del ViewModel refleja el cambio. | 2 |
| **E5** | **UI del docente: crear/editar cátedra y mostrar el código.** `TeacherCourseScreen`: formulario (código, nombre, cursada, descripción), botón "Guardar", y el **código de inscripción** con botón "Copiar". `TeacherHomeScreen`: el botón "Crear cátedra" **deja de ser un placeholder** y abre el formulario. | `ui/.../teacher/TeacherCourseScreen.kt`, `ui/.../teacher/TeacherHomeScreen.kt` | A mano: se crea la cátedra y aparece en "Mis cátedras" con su código. | 4 |
| **E6** | **UI del alumno: unirse con un código.** `StudentHomeScreen`: botón "Unirme con un código" ⇒ diálogo con el campo de texto ⇒ `joinCourse` ⇒ aparece la cátedra. El placeholder actual (`onAction = { vm.setProfile(profileId(), "Alumno") }`) **se reemplaza**. | `ui/.../student/StudentHomeScreen.kt` | A mano: se pega el código y la cátedra aparece en "Mis cátedras" en < 5 s. | 3 |

### Área F — Que el material y la configuración lleguen al alumno

> **Sin esta área, E2 es imposible**: el RAG corre en el cliente y el material
> vive en el disco del docente (hallazgo **B13**), y la `ModelPolicy` también
> (hallazgo **B14**).

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **F1** | **Paquete de cátedra `.kitoru`**: un ZIP con `manifest.json` (`{format: "kitoru-course", version: 1, courseId, exportedAt}`), `course.json` (la `Course` **sin** la `apiKey`: la key viaja aparte o se pega a mano), `materials.json` (metadatos) y `text/<materialId>.txt`. **Implementación en `commonMain` con `java.util.zip`** (existe en el JDK y en Android ⇒ va en `jvmCommonMain`). Export: `CoursePackage.export(course, materials, textProvider): ByteArray`. Import: `CoursePackage.import(bytes): ImportedCourse` con **validación** (versión desconocida ⇒ error en español; material sin texto ⇒ se marca `NEEDS_REVIEW`). | **nuevo** `core/src/jvmCommonMain/kotlin/kitoru/sync/CoursePackage.kt` + `core/src/commonMain/kotlin/kitoru/sync/CoursePackage.kt` (contrato) | Test de ida y vuelta: exportar una cátedra con 2 materiales e importarla en un `InMemoryFileSystem` vacío ⇒ los 2 materiales quedan con su texto **idéntico**. Un `.kitoru` corrupto ⇒ error en español y **nada** escrito en disco. | 6 |
| **F2** | **Botón "Exportar cátedra" (docente) + "Importar cátedra" (alumno).** El docente exporta un `.kitoru` a una carpeta; el alumno lo importa desde un `FilePicker` y después se une con el código. | `ui/.../teacher/TeacherCourseScreen.kt`, `ui/.../student/StudentHomeScreen.kt`, `ui/.../platform/FileSaver.kt` (ya existe, falta desktop) | A mano: el `.kitoru` sale en el escritorio del docente y se importa en el del alumno. | 4 |
| **F3** | **El alumno indexa el material importado.** Al unirse, el `CourseIndex` del alumno se puebla con los materiales **de su copia local**. Si el alumno no importó la cátedra, el chat funciona **sin corpus** y la UI lo dice ("Esta cátedra no tiene material en este dispositivo"). | `ui/.../KitoruViewModel.kt`, `ui/.../chat/ChatScreen.kt` | A mano: el alumno importa, chatea y la respuesta cita `[1]`. Sin importar: el chat responde y **no** muestra distintivo, con el aviso. | 2 |

### Área G — Analítica y chats del docente (con la visibilidad aplicada)

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **G1** | **La analítica muestra el mensaje del alumno y su tasa de citas.** `TeacherAnalyticsScreen`: en la fila por alumno, si `enrollment.visibility.showsText`, mostrar el **último mensaje del alumno** (con autor y hora) y la tasa de citas; si no, mostrar sólo los números y una nota. Cablear los datos reales (hoy la pantalla ya llama al analizador, pero no tiene los mensajes a mano). | `ui/.../teacher/TeacherAnalyticsScreen.kt`, `ui/.../KitoruViewModel.kt` | A mano: el docente lee el mensaje literal del alumno y ve "1 de 1 citas respaldadas (100 %)". | 4 |
| **G2** | **`TeacherChatRepository`: la frontera que aplica la visibilidad ANTES de leer.** Interfaz nueva en `:core`: `suspend fun listThreads(courseId, studentId): List<ThreadSummary>`, `suspend fun readThread(threadId, viewer): ChatThreadView?`. Con `visibility != FULL` ⇒ **devuelve `null`/lista vacía y NO llama al `MessageRepository`**. `TeacherChatsScreen` usa **sólo** esta interfaz. | **nuevo** `core/.../domain/TeacherChatRepository.kt` + implementación, `ui/.../teacher/TeacherChatsScreen.kt` | **Test clave**: un `MessageRepository` fake que **cuenta llamadas**; con `visibility = METRICS_ONLY` ⇒ `assertEquals(0, fake.calls)` **y** el texto no aparece en la pantalla. Con `FULL` ⇒ `fake.calls > 0` y el texto aparece. Con `NONE` ⇒ el alumno no aparece en el listado. | 6 |
| **G3** | **Listado de alumnos con su visibilidad** (opcional): `TeacherStudentsScreen` lista los alumnos con su `visibility` y un selector para cambiarla. | `ui/.../teacher/TeacherChatsScreen.kt` (la pantalla ya existe) | A mano: el docente ve "Ana — Solo métricas" y puede cambiarlo a "Completo". | 3 |

### Área H — IA local y privacidad (el mínimo del hito 1)

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **H1** | **Modo LOCAL usable sin descarga de modelos.** El alumno puede poner `mode = LOCAL` + `remoteBaseUrl = http://127.0.0.1:<puerto>/v1` y usar **cualquier** endpoint OpenAI-compatible local. `ChatEngine.prepare` ya falla en LOCAL con *"El modelo local se arranca por separado"*: cambiarlo para que acepte una URL local y arme el `OpenAiCompatibleGateway` contra ella. **No** se implementa la descarga del GGUF ni el supervisor de `llama-server` (eso es F10): el alumno apunta a un servidor que ya tiene. | `ui/.../chat/ChatEngine.kt`, `ui/.../student/StudentModelScreen.kt` | A mano: con un `llama-server` (u Ollama) ya corriendo, el alumno cambia a LOCAL y chatea **sin** que la app descargue nada. El `modelLabel` muestra el nombre del modelo local. | 3 |
| **H2** | **Declaración de modo y su efecto en la visibilidad.** Cuando el alumno elige LOCAL, su inscripción pasa a `visibility = METRICS_ONLY` (default de la cátedra) — o el docente la deja en `NONE` — y la UI **lo dice con la frase exacta**: *"Con IA local, el docente ve tus métricas pero no puede leer este chat, salvo que lo compartas"*. El alumno puede cambiarlo a `FULL` si quiere (declaración explícita, no un default oculto). | `ui/.../student/StudentModelScreen.kt`, `ui/.../KitoruViewModel.kt` | A mano: al cambiar a LOCAL aparece el aviso; al volver a la home, la línea de visibilidad dice "Solo métricas". Test: cambiar a LOCAL ⇒ `enrollment.visibility == METRICS_ONLY`. | 3 |

### Área I — Tests (lo que hace que el hito sea repetible)

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **I1** | **Suite de `:core`** (hoy **cero** tests): `ModelPolicyTest` (≥ 12 casos de `resolveFor`, campo por campo + `validate`), `RolesTest`, `ChatVisibilityTest`, `AnalyticsAnalyzerTest` (**golden**: 50 mensajes con números exactos escritos en el test + el caso `visibility = NONE` + el caso `topSources`), `JoinCodeTest`, y `FileRepositoriesTest` con `InMemoryFileSystem` (ida y vuelta de cada entidad, borrado, filtrado por cátedra/autor). | **nuevos** `core/src/desktopTest/kotlin/kitoru/domain/*.kt` | `./gradlew :core:desktopTest` ⇒ **≥ 45 tests verdes**, 0 rojos. | 8 |
| **I2** | **Suite de `:ui`** con fakes: `FakeCourseRepository`, `FakeEnrollmentRepository`, `FakeMessageRepository`, `FakeGateway` (que emite texto y citas controladas). Tests de: `createCourse` genera código, `joinCourse` (3 casos), `ChatViewModel` persiste hilo y mensajes, el distintivo sale "Citas verificadas (1)", `TeacherChatRepository` **no llama** al repositorio de mensajes con `METRICS_ONLY`. **Requiere `Dispatchers.setMain`** (hallazgo **B12**). | **nuevos** `ui/src/desktopTest/kotlin/kitoru/ui/*.kt` + `ui/src/commonTest/.../fakes/` | `./gradlew :ui:desktopTest` ⇒ **≥ 12 tests verdes**. | 6 |
| **I3** | **Test de integración end-to-end del pipeline**: material `.md` ⇒ `MaterialIngestionService` ⇒ `CourseIndex.reindex` ⇒ `RagEngine.prepare` ⇒ `FakeGateway` que responde citando `[1]` ⇒ `CitationVerifier` ⇒ `citation.verified == 1` y `sources[0].label` contiene el nombre del material. Es el **test que enciende el distintivo** sin tocar la UI. | **nuevo** `ui/src/desktopTest/kotlin/kitoru/ui/MaterialToChatPipelineTest.kt` o `backend/src/desktopTest/kotlin/rag/` | El test pasa en verde y **falla si alguien desconecta el `CourseIndex` del `ChatEngine`**. | 6 |

### Área J — Verificación end-to-end

| ID | Tarea | Archivos | Criterio de terminado | h |
|---|---|---|---|---|
| **J1** | **Guion manual** de los 5 escenarios (E1–E5) con los pasos exactos, lo que hay que tener preparado y qué se ve en pantalla en cada paso. Vive junto al plan. | **nuevo** `docs/design/process/scripts/H1-guion.md` | Otra persona lo sigue **sin preguntarte nada** y llega al final. | 4 |
| **J2** | **Documentar el "servidor de IA de prueba"**: cómo levantar Ollama o `llama-server` y qué modelo usar; cómo configurar la cátedra con esa URL; y un `KITORU_HOME` para tener dos instalaciones (docente y alumno) en la misma máquina. | `README.md` (o `docs/design/process/scripts/H1-guion.md`) | Los pasos del guion J1 funcionan desde cero en una máquina limpia. | 2 |

---

## 4. El orden sugerido

### 4.1 La secuencia (con las flechas de dependencia)

```
A3 (commit)                    ← nada, pero hacerlo YA: se está perdiendo trabajo
 │
B1 InMemoryFileSystem ──┬── B4 MaterialRepository (texto aparte)
B2 FileSystem bytes ────┤
B6 JoinCode ────────────┼── E2 createCourse ──┐
B8 redacted() ──────────┤                      ├── E5/E6 UI cátedra + unirse
B7 fix AnalyticsAnalyzer┘                      │
                                              │
C1 MaterialFormat ──┬── C2 TextExtractor ──┬── C3 IngestionService ──┐
C5 FilePicker ──────┘                      │                        │
                                         C4 CourseIndex ─────────────┤
                                                                    │
D1 RetrievedSource ──┬── D4 detalle del distintivo                    │
D2 persistencia ─────┼── D3 KB poblada ───────────────────────────────┤
D6 resolveFor ───────┘                                              │
                                                                    ▼
                                       F1 Paquete .kitoru ── F2 export/import ── F3 indexar
                                                                    │
G2 TeacherChatRepository ──┐                                          │
G1 analítica con textos ───┤                                          │
B7 (ya arriba) ─────────────┘                                          │
                                                                    │
H1 modo LOCAL ── H2 declaración de modo ──────────────────────────────┤
                                                                    ▼
                                            I1/I2/I3 tests ── J1/J2 guion ── E1..E5
```

### 4.2 Por qué ese orden

1. **A3 primero, siempre.** El repo tiene **cero commits** y hay otro agente
   escribiendo. Sin commit, todo lo demás es frágil. Es 1 hora y elimina la
   peor contingencia del plan.
2. **B (dominio y persistencia) antes que C (materiales).** B1 y B2 son la
   **infraestructura de pruebas**: sin `InMemoryFileSystem` no se puede testear
   ningún repositorio, y sin `readBytes`/`writeBytes` no se puede subir un
   archivo. Son 5 horas que **desbloquean** todo lo demás. B3–B8 son
   correcciones de dominio que, si se hacen más tarde, obligan a tocar código
   ya escrito.
3. **C antes que D.** El distintivo de citas sólo aparece si el `KnowledgeBase`
   tiene fuentes (hallazgo **B1**). El orden natural es *primero el material
   entra al índice, después el chat lo consume*.
4. **D (chat) antes que E/F/G.** El chat es el que produce los datos que la
   analítica y el explorador muestran. Construir la analítica antes de que
   haya mensajes es construir sobre datos falsos.
5. **F (paquete de cátedra) antes de E2/E3 en la práctica.** Es la única
   forma de que el alumno tenga material. Si el tiempo aprieta, es lo
   primero que se puede **postergar**… con una salvedad: **sin F, E2 no se
   puede demostrar**. No es una tarea opcional del camino crítico; es
   **una** forma de hacer E2.
6. **G2 (`TeacherChatRepository`) antes que G1.** Es la frontera de privacidad.
   Si la analítica se construye primero leyendo mensajes "a pelo", después
   hay que refactorizar para aplicar la visibilidad en el repositorio. Es
   exactamente el error que el diseño de `teacher/07-chats.md` quiere evitar.
7. **H (IA local) al final del camino funcional.** Es el escenario E4, el
   menos central del producto. Se hace cuando E1–E3 ya funcionan.
8. **I (tests) transversal, no al final.** I1 acompaña a B; I2 a D/E/G; I3
   es el que **verifica** el hito. Los tests se escriben con la tarea que
   introducen el comportamiento, no en un sprint final.

### 4.3 Qué es paralizable

Con **dos personas**:

| Persona A | Persona B |
|---|---|
| B (dominio y persistencia) | C1–C3 (extracción) y C5 (`FilePicker`) |
| D (chat) | E (cátedras e inscripciones) |
| G2 (`TeacherChatRepository`) | F (paquete de cátedra) |
| I1 (tests de `:core`) | I2 (tests de `:ui`) |

**Cuellos de botella (no paralelizables):**

- **B1/B2** bloquean a todos: son 5 h y hay que hacerlas primero.
- **C4 (`CourseIndex`)** bloquea a D3 y F3.
- **F1 (paquete)** bloquea a E2.
- **G2** bloquea a G1.

**Horas de camino crítico** (con atajos): ≈ **70 h** ≈ 9 días-persona.
**Total con todo el alcance**: ≈ **141 h** ≈ 18 días-persona.

---

## 5. El esfuerzo estimado

### 5.1 Por área

| Área | Tareas | h | Días-persona (8 h) |
|---|---|---:|---:|
| **A** — Arranque, git, CI | A1–A4 | 5 | 0,6 |
| **B** — Dominio y persistencia | B1–B8 | 22 | 2,8 |
| **C** — Materiales | C1–C5 | 22 | 2,8 |
| **D** — Chat | D1–D6 | 20 | 2,5 |
| **E** — Cátedras e inscripciones | E1–E6 | 18 | 2,3 |
| **F** — Paquete de cátedra | F1–F3 | 12 | 1,5 |
| **G** — Analítica y chats del docente | G1–G3 | 13 | 1,6 |
| **H** — IA local y privacidad | H1–H2 | 6 | 0,8 |
| **I** — Tests | I1–I3 | 20 | 2,5 |
| **J** — Verificación manual | J1–J2 | 6 | 0,8 |
| **TOTAL** | **42 tareas** | **144** | **18,0** |

### 5.2 Con los recortes de alcance de §7 aplicados

Estos recortes son **sobre esta lista de tareas**. Los atajos de §7 miden su
ahorro contra el alcance completo del diseño (roadmap F0–F7), que es mucho
mayor; por eso los dos listados no restan el mismo número.

| Recorte | Ahorro |
|---|---:|
| B4 sin mover el texto a `.txt` (queda en el JSON, se acepta reescribir) | −3 h |
| B5 sin sharding (sólo arreglar `delete`) | −2 h |
| G3 (listado de alumnos con su visibilidad) fuera del hito 1 | −3 h |
| C5 sólo desktop (sin stub de Android) | −2 h |
| F1/F2 paquete mínimo (sin `manifest.json` con versión) | −3 h |
| I2 sin tests de UI de Compose (sólo ViewModels) | −2 h |
| A2 (`:android`): se arregla la compilación pero no se adapta | −1 h |
| **TOTAL con los recortes** | **≈ 128 h** ≈ **16 días-persona** |

### 5.3 Calendario tentativo

Una persona a 4 días por semana (≈ 32 h/semana):

| Semana | Qué | h |
|---|---|---:|
| **1** | A3, A1, A2, A4 · B1, B2, B6 | 21 |
| **2** | B3, B4, B5, B7, B8 · I1 (parte) · C1 | 27 |
| **3** | C2, C3, C5 · C4 · E1, E2 | 30 |
| **4** | D1, D2, D3 · E3, E4, E5, E6 | 30 |
| **5** | F1, F2, F3 · D4, D6 | 25 |
| **6** | G2, G1 · H1, H2 | 16 |
| **7** | I2, I3 · J1, J2 ·(buffer) | 18 |
| | **Total** | **167 h** (con buffer) |

**El hito 1 se cierra entre la semana 6 y la 7** (≈ 5–6 semanas de una
persona a medio tiempo, o **3 semanas con dos personas**).

---

## 6. Las decisiones que hay que tomar ANTES de empezar

> Cada una tiene una recomendación. **Ninguna se puede postergar más allá de
> la primera semana**: la decisión **congela el esquema** y cambiar después
> obliga a reescribir dos veces.

### D1 — ¿SQLite o JSON?

**Recomendación: JSON**, con dos correcciones baratas:
(a) el texto del material **fuera** del JSON (`courses/<courseId>/text/<id>.txt`);
(b) los mensajes **por hilo** (no un `messages.json` gigante).

**Por qué:**

- Ya existe y funciona en desktop y Android sin dependencias nativas.
- SQLite multiplataforma exige SQLDelight (KSP, configuración por source set,
  `*.sq` files) — 8–12 h de setup para un beneficio que el hito 1 no usa.
- Room no compila en desktop. Y `androidx.sqlite` no es multiplataforma.
- El volumen real de un curso es de **miles** de mensajes, no millones.
- Los JSON se **diagnostican con un editor de texto**: cuando un docente
  reporta "no me aparece mi material", se abre el archivo y se ve.

**Disparador de re-evaluación (anotarlo en el código):** más de **50 000
mensajes por curso**, o más de **200 materiales por curso**, o una consulta
que el JSON no puede responder (agregaciones, búsquedas por texto). Ahí sí,
SQLDelight.

**Cuándo cerrar:** antes de C3 (la ingesta escribe el esquema de archivos).
**Deuda que queda anotada:** el sharding por hilo (B5 ampliado) **no** se hace
en el hito 1; queda como tarea de la §5.4 del roadmap.

### D2 — ¿La API key en el modelo o en un almacén seguro?

**Recomendación: en el hito 1, la key sigue en el modelo** (`ModelPolicy.apiKey`),
**pero** con tres decisiones que sí se toman ahora:

1. `ModelPolicy.redacted()` (B8): **nunca** se persiste un `ModelPolicy` con la
   key en `ChatThread.effectiveModel` ni en un log.
2. La UI **siempre** enmascara la key (`ktor_K01J…kA`), salvo el instante
   explícito de pegarla.
3. La key **nunca** se loguea: un `SecretRedactor` (o, en el hito 1, un test
   que captura la salida y busca el token) lo verifica.

**Por qué NO `SecureStore` en el hito 1:**

- `SecureStore` (AndroidKeyStore + AES/GCM, DPAPI en Windows, llavero en macOS,
  Secret Service en Linux) son **≈ 200 h** y cuatro implementaciones que hay que
  probar en cuatro sistemas.
- La key **vence con la cursada**: el modelo de amenaza real es "alguien copia
  el archivo de configuración", y un almacén del SO **no** protege contra eso
  (el `dataDir` se sigue respaldando).
- El roadmap ya lo decidió así en §7.4.

**Alternativa barata que sí conviene:** sacar la key **del modelo de dominio**
y dejarla en `config/secrets.json` con permisos restrictivos, referenciada por
`apiKeyRef`. **No es seguridad real**, pero reduce el radio de copia (el
`courses.json` deja de ser un archivo sensible) y el cambio es de ~4 h. **Mi
recomendación concreta: NO hacerlo en el hito 1** (es otra decisión de esquema
y ya tenemos B8 + la UI enmascarada); **anotarlo como deuda** para el hito 2.

**Cuándo cerrar:** antes de D2 (persistencia del hilo).

### D3 — ¿El Kitoru-Server aloja los materiales?

**Recomendación: NO. En el hito 1, el Kitoru-Server no existe en el flujo.**

- El Kitoru-Server es **otro repo** y no está escrito. Acoplar el hito 1 a él
  es bloquear el hito 1 con el ritmo de otro repo.
- El server aloja **modelo + keys + relay de chats**; los materiales son del
  curso.

**Consecuencia que hay que decidir ya (y es la que define el área F):** si el
RAG corre **en el cliente** (que es lo que ya hace `KnowledgeBase`), el
material tiene que **llegar** al alumno por algún lado. Las tres opciones:

| Opción | Qué implica | Veredicto |
|---|---|---|
| **(a) Paquete de cátedra `.kitoru`** (export del docente + import del alumno) | 12 h (F1–F3), funciona **sin** server, sin red y sin cuenta | ✅ **Elegida para el hito 1** |
| (b) El servidor aloja el material y lo sirve por API | Requiere el server: bloqueante | ❌ Hito 2+ |
| (c) El alumno pega la URL y la key a mano, y el material no viaja | El alumno chathea **sin corpus** ⇒ no hay citas ⇒ **E2 no se cumple** | ❌ Descartada |

**El paquete `.kitoru` es, de hecho, la respuesta a la pregunta "¿cómo llega
la configuración de la cátedra al alumno?"** (roadmap §7.3) y queda resuelto
en el mismo trabajo. Es la decisión más rentable del hito 1.

**Cuándo cerrar:** antes de F1.

### D4 — ¿Cuál es el alcance del material? (¿sólo Markdown y texto, o también PDF?)

**Recomendación: en el hito 1, Markdown + texto plano + CSV + HTML.**
**PDF entra en el hito 1.1 si sobra tiempo** (sólo desktop, con PDFBox).

**Por qué:**

- **PDF necesita dos librerías con paquetes distintos**: PDFBox 2.0.x en
  desktop (`org.apache.pdfbox`) y `com.tom-roush:pdfbox-android` 2.0.27 en
  Android. **El mismo archivo fuente no compila en las dos plataformas**: hay
  que escribir el algoritmo dos veces o inventar un backend propio
  (`PdfBackend` + `PdfTextAssembler`, como propone `teacher/03-materiales.md`
  §4.3). Son **5–8 h** más un riesgo de runtime real en Android
  (`PDFBoxResourceLoader.init`, `java.io.tmpdir` no escribible).
- El **80 % del valor** de la tesis ("la IA cita **mi** material") se demuestra
  con `.md`/`.txt`: un docente puede convertir sus apuntes a Markdown en
  segundos, y **el material del piloto va a ser Markdown igual**.
- El camino de escape es **honesto y siempre está**: el formato no soportado
  ⇒ `status = NEEDS_REVIEW` + aviso *"El formato PDF todavía no está
  soportado. Guardá el material como Markdown (.md) o pegá el texto."* — y en
  el hito 1.1 se agrega el botón **"Pegar texto"** en el diálogo de material,
  que es la vía `NEEDS_REVIEW` del diseño (`teacher/03-materiales.md` §7).

**El atajo de PDF si hay tiempo (5 h, desktop-only):** `pdfbox:2.0.36` en
`core/desktopMain`, `PdfBackend` con `pageTexts()`, `PdfTextAssembler` con
marcas `## Página N` (para que el `Chunker` de `:backend` corte por página y
las citas digan "Página 7"). Android queda con el aviso `NEEDS_REVIEW`.

**Lo que NO entra en el hito 1 en ningún caso:** DOCX, PPTX, OCR (roadmap §4).

**Cuándo cerrar:** antes de C2.

### D5 — ¿Dónde corre el RAG, en el cliente o en el servidor?

**Recomendación: en el cliente**, sin discusión. `KnowledgeBase` y
`Bm25Index` ya son **portables y deterministas**, y el verificador de citas
necesita **el texto de la fuente en el mismo proceso** para comprobar la cita.
Un RAG en el servidor obligaría a mover `CitationVerifier` al server y a
transportar los fragmentos: un rediseño completo, sin ganancia en el hito 1.

### D6 — ¿Qué es un "modelo" en el hito 1: remoto, local, o los dos?

**Recomendación: los dos, pero el local es "apuntar a un endpoint que ya
existe", no "descargar el modelo".**

- **REMOTE**: es el camino principal. La cátedra configura `remoteBaseUrl` +
  `modelName` (+ key) y el alumno chathea contra eso.
- **LOCAL**: el alumno apunta a `http://127.0.0.1:<puerto>/v1` (H1). **No**
  hay descarga de GGUF ni supervisor de `llama-server` (eso es F10, 12 dp).
  El `LocalModelService` desktop **ya existe** y sirve, pero requiere que el
  binario y el `.gguf` ya estén en la máquina.

**Por qué esto es suficiente para E4:** el escenario E4 verifica la
**visibilidad**, no la inferencia local. Con un `llama-server` ya corriendo (o
cualquier endpoint local), el alumno cambia a LOCAL, chattea, su inscripción
pasa a `METRICS_ONLY`, y el docente no ve el texto. La inferencia local real
(la descarga) es una línea de trabajo aparte.

### D7 — ¿Se arregla `:android` o se saca del hito 1?

**Recomendación: se arregla (1 h) y se deja fuera del alcance funcional.**

El módulo existe, `settings.gradle.kts` lo incluye, y compilar en rojo
**envenena todos los tests de CI**: nadie distingue "rompí algo" de "Android
no está en el hito 1". 1 hora de `@Composable` y el build vuelve a ser una
señal fiable. El resto de Android (SAF, `MainActivity` real, adaptación de
pantallas) es hito 2.

---

## 7. Los atajos: cómo llegar al primer hito rápido

El objetivo es **recortar sin que la app deje de ser coherente**. Cada atajo
dice qué se sacrifica y por qué la app sigue siendo coherente.

> **Cómo se mide el ahorro de esta tabla:** contra el **alcance completo del
> diseño** (roadmap F0–F7: persistencia definitiva, analítica temporal,
> explorador de chats completo, cifrado en reposo, PDF/DOCX/PPTX, IA local con
> descarga, Android completo), **no** contra las 42 tareas de §3. Los recortes
> que sí son sobre esta lista están en §5.2.

| # | Atajo | Ahorro | Qué se sacrifica | Por qué sigue siendo coherente |
|---|---|---:|---|---|
| **1** | **`:android` fuera del hito 1** (se arregla la compilación, pero no se lo adapta). | 13 h | SAF, `MainActivity` real, pantallas táctiles | El hito 1 es de escritorio; el `:ui` es `commonMain` y no depende de nada de Android |
| **2** | **El texto del material queda en `materials.json`** (no se mueve a `.txt`). | 3 h | Escalabilidad con muchos materiales | Con 1–20 materiales por cátedra es invisible; se acepta reescribir el archivo |
| **3** | **Los mensajes siguen en un `messages.json` único** (no hay sharding por hilo). Se arregla **sólo** el bug de `delete(threadId)` y la convención del prefijo. | 2 h | Escalabilidad con > 10 000 mensajes | Un curso del piloto tiene cientos de mensajes, no decenas de miles |
| **4** | **Sin `SecureStore`**: la key sigue en el modelo, con `redacted()` + UI enmascarada. | 200 h | Protección en reposo de la key | La key vence con la cursada; el `dataDir` se respalda igual |
| **5** | **El índice NO se persiste**: se reconstruye al abrir la cátedra. | 4 h | Arranque más lento con corpus grandes | BM25 sobre 1 000 fragmentos tarda milisegundos |
| **6** | **El paquete `.kitoru` es mínimo**: un ZIP con `course.json` + `materials.json` + `text/*.txt`, sin `manifest.json` con versión (se valida por la presencia de `course.json`). | 3 h | Versionado del formato de paquete | El formato es interno al hito 1; la versioning entra con el server |
| **7** | **La analítica es la que ya existe** (`TeacherAnalyticsScreen`), sin pestañas, sin calendario, sin cohortes, sin CSV, sin graficos. | 8 h | La mitad del diseño de `analytics/07` | El `AnalyticsAnalyzer` **ya** calcula lo que el escenario E3 necesita; el resto es presentación |
| **8** | **El explorador de chats es una lista mínima** que respeta la visibilidad. Sin búsqueda, sin filtros, sin exportación, sin importación de chats. | 6 h | La pantalla `teacher/07-chats.md` completa | E4 verifica que **no** se ve el texto: una lista vacía con aviso lo cumple |
| **9** | **Sin `AccessLog`** (auditoría de accesos). | 4 h | La pantalla "Auditoría de mis chats" del alumno | Es trazabilidad, no funcionalidad; se agrega después de que el flujo funcione |
| **10** | **Sin `compose.uiTest`**: los tests de UI son de los más caros de mantener. Se testea la **lógica** (ViewModels con fakes) y la UI se verifica a mano con el guion J1. | 4 h | Tests de composición | Los ViewModels concentran el 90 % de la lógica; la UI es presentación fina |
| **11** | **La gestión de modelos del alumno es un formulario simple**: URL, modelo, temperatura, "usar IA local". Sin sliders de rango, sin presets, sin frase resumen gorgeous. | 6 h | El diseño completo de `teacher/05-config-modelo.md` | `resolveFor` (el dominio) **sí** está completo y testeado; lo que falta es la decoración |
| **12** | **Un solo tipo de material en el piloto**: `.md` y `.txt`. CSV y HTML se aceptan si el extractor ya está (§C2), pero no se prueban a mano. | 1 h | — | Es el mismo código |

**Ahorro total de los atajos 1–12: ≈ 42 h** contra el alcance completo del
diseño. Sobre las 42 tareas de este plan, el efecto real es el de §5.2
(≈ 128 h).

### 7.1 El atajo que **NO** hay que tomar

**No hay que atajar el "paquete de cátedra" (área F)**, aunque sea la parte más
incomoda. Atajarla significa que el alumno chattea **sin material**, y sin
material **no hay citas, no hay distintivo, no hay analítica de calidad** — es
decir, se ataja **la tesis del producto**. Si el tiempo se acaba:

- **Recortá el resto** (los atajos 1–12).
- Si aun así no entra: **hacé el hito 1 en dos pasos** —
  **Hito 1a**: docente + material + chat de prueba (**E1**, el 60 % del valor
  técnico) y **Hito 1b**: reparto + alumno + analítica + privacidad
  (**E2–E4**). El 1a se puede hacer **sin** el paquete: el docente chattea
  contra **su** propio material con **su** gateway, y ya demuestra que el
  pipeline material → índice → RAG → citas funciona.

---

## 8. Qué NO hacer todavía (y por qué)

| # | Qué | Por qué no ahora | Cuándo vuelve |
|---|---|---|---|
| 1 | **SQLite / SQLDelight** | Congela el esquema y cuesta 8–12 h de setup para un beneficio que el hito 1 no usa. Los repositorios **ya son interfaces**: cambiar de motor es escribir otra implementación. | > 50 000 mensajes/curso o > 200 materiales/curso (§6, D1) |
| 2 | **`SecureStore` / AndroidKeyStore / DPAPI** | ~200 h en 4 plataformas. La key **vence con la cursada** y el `dataDir` se respalda igual. Con `redacted()` + UI enmascarada + test anti-log, el riesgo real queda cubierto. | Hito 2, cuando la key la emite el Kitoru-Server (ahí sí hay que proteger un token de largo plazo) |
| 3 | **El Kitoru-Server, `KitoruServerClient`, sincronización** | Es **otro repo** y no está escrito. Acoplar el hito 1 a él bloquea el hito 1 con el ritmo de otro repo. | Hito 2, contra un **servidor falso** (el contrato es la especificación) |
| 4 | **PDF, DOCX, PPTX, OCR** | PDF necesita dos librerías con paquetes distintos y dos implementaciones del mismo algoritmo (5–8 h + riesgo en Android). DOCX/PPTX son un parser OOXML propio. OCR es un proyecto entero. | Hito 1.1: PDF desktop-only con PDFBox. DOCX/PPTX/OCR: hito 2 |
| 5 | **La IA local real** (descarga del GGUF, supervisor de `llama-server`, reanudación, detección de hardware) | 12 dp con un riesgo alto (el modelo no entra, no hay GPU, no hay RAM). Para E4 **alcanza** con apuntar a un endpoint local que ya existe (H1). | F10 del roadmap |
| 6 | **Android como producto** (SAF, `ACTION_SEND`, drawer, `FileProvider`, teclado virtual) | 14 dp con huecos de plataforma por todas partes. En el hito 1, Android sólo tiene que **compilar**. | F11 |
| 7 | **La analítica temporal, cohortes, horarios y calendario** (`TemporalAnalyzer`, `CalendarMath`, `CourseSchedule`) | Requiere `ChatMessage.tzOffsetMinutes` (migración de esquema) y arregla un bug real de `dayStart()` (medianoche UTC vs local), pero **E3 no lo necesita**. | Hito 2 (F6 completo) |
| 8 | **`AccessLog` y la auditoría de accesos** | Es trazabilidad, no funcionalidad: 4 h y un esquema nuevo. **El diseño de la visibilidad (G2) no depende de él.** | Hito 1.1 |
| 9 | **El `.kitoru` de *chats* (import/export de conversaciones), `TeacherChatRepository` con búsqueda, filtros, CSV** | Es F7 completo (10 dp). Para E4 alcanza con que la lista respete la visibilidad. | Hito 2 |
| 10 | **Tareas y entregas** (`Assignment`, `Submission`, `AiPolicy`) | F8 (12 dp), con un `domain/06-tareas.md` de 1 583 líneas. No aporta al hito 1. | Hito 2 |
| 11 | **Identidad institucional (login de la universidad), SSO, multi-perfil como producto** | Exige acuerdos con la universidad. El hito 1 usa **perfiles locales manuales** (E1), que es suficiente para demostrar. | Hito 2 (con el server) |
| 12 | **Herramientas avanzadas del chat: diálogo de permisos, `/permitir-<categoría>`, tools de web** | El `AgentLoop` y el `ToolRegistry` **ya funcionan**; lo que falta es la UI de permisos, que es un mundo aparte (y el roadmap §4 excluye la búsqueda web: en una cátedra el modelo debe responder con **el material**, no con internet). | Hito 2 |
| 13 | **Ranking de alumnos, scores, comparaciones entre alumnos** | **Nunca** sin una decisión de producto explícita: es el camino corto a que la herramienta se queme (roadmap §6.4). | **Nunca** |
| 14 | **Búsqueda web / `web_fetch`, MCP, LaTeX, voz** | Fuera del alcance del roadmap (§4). La web en el chat del alumno contradice la tesis ("tutor **de mi materia**"). | Hito 2, sólo como opt-in explícito del docente |
| 15 | **Persistencia del índice (BM25 en disco, embeddings, búsqueda semántica)** | BM25 en memoria reconstruido al abrir es **instantáneo** para el tamaño de un curso real. Los embeddings son una línea de trabajo propia (`rag/04-embeddings.md`). | Cuando el corpus supere ~50 000 fragmentos |
| 16 | **Multi-perfil como funcionalidad de producto** (un dispositivo compartido por dos personas) | En el hito 1 el multi-perfil es una **herramienta de prueba** (E1), no una función: cambia el `UserProfile.id` activo. | Hito 2 |
| 17 | **Empaquetado multiplataforma (dmg/deb), firma, Play Store** | El target de lanzamiento es Windows. `compose.desktop` ya declara los formatos, pero probarlos es trabajo de release. | Hito 2 |

---

## 9. Checklist de cierre del hito 1

El hito está cerrado cuando **todo** esto es verdad:

- [ ] `./gradlew :core:compileKotlinDesktop :llm-api:compileKotlinDesktop :backend:compileKotlinDesktop :ui:compileKotlinDesktop :desktop:compileKotlin :android:assembleDebug` → **BUILD SUCCESSFUL**
- [ ] `./gradlew allTests` → **0 rojos**, con **≥ 165 tests** (108 de `:backend` + ≥ 45 de `:core` + ≥ 12 de `:ui`)
- [ ] `./gradlew :desktop:run` → ventana "Kitoru", sin excepciones en consola
- [ ] **E1, E2, E3, E4 y E5** ejecutados **a mano** por otra persona, siguiendo `docs/design/process/scripts/H1-guion.md`, sin preguntar nada
- [ ] Cada escenario tiene **al menos un test automatizado** que lo cubre (E1+E2 → I3; E3 → I1 golden; E4 → G2)
- [ ] `git log` muestra **un commit por tarea** del plan, con mensajes `Hito 1/<área>-<tarea>: <qué hace>`
- [ ] `README.md` dice: cómo compilar, cómo correr los tests, cómo levantar el servidor de IA de prueba, y qué es el paquete `.kitoru`
- [ ] La **deuda anotada** quedó escrita en `docs/design/process/06-plan-trabajo.md` §7 (atajos aplicados) y §8 (qué NO se hizo)
- [ ] **No hay ninguna clave de API en un log, en un `println`, ni en la UI** salvo el instante de pegarla (test que captura la salida y busca el token)

---

## 10. Riesgos del plan

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| **Otro agente sigue escribiendo en el repo** (ocurre hoy) | **Alta** | Pérdida de trabajo, Merge hell, tareas que se pisan | **A3 (commit) ya**, y un commit por tarea. Antes de empezar cada tarea: `git status` limpio |
| **El modelo de prueba no responde o alucina** | Alta | Los escenarios E1–E4 fallan por culpa del modelo, no del código | El guion J2 fija un modelo concreto (Ollama con un modelo chico) y un material de prueba **escrito para que la cita sea literal**. El distintivo de citas tiene tres estados: si el modelo no cita, sale "Respondió sin citar el material" — que también es un resultado correcto |
| **El verificador marca "sin respaldo" en una cita correcta** | Media | E2 falla (el distintivo no dice "Citas verificadas") | El verificador es determinista y ya está testeado (27 tests). El material de prueba debe usar la frase **literal** del fragmento. Escribir el material así es una decisión de guion, no de código |
| **El `CourseIndex` no se conecta al `ChatEngine`** (el bug B1 vuelve) | Media | **El distintivo de citas nunca aparece** ⇒ el hito "no funciona" sin que nada falle visiblemente | **Tarea I3** falla exactamente en ese caso. Es el test que protege el hito |
| **El paquete `.kitoru` resulta frágil** (ZIP, rutas, codificación) | Media | El alumno no puede chathear con material ⇒ E2 bloqueado | Es un formato simple y **con validación explícita**; un `.kitoru` roto da un error en español y no escribe nada. Test de ida y vuelta |
| **La analítica muestra números que no cuadran con el chat** | Media | El docente desconfía del panel | El test golden de I1 y el escenario E3 comparan **los mismos números** desde dos caminos |
| **El alcance se infla** ("mientras estamos, agreguemos…") | **Alta** | El hito no se cierra nunca | §8 es la lista de "no hacer todavía", y §7 la lista de atajos. **Cada tarea fuera de esta lista se anota como deuda, no se hace** |

---

*Fin del plan. Las 35 tareas son la unidad de trabajo; los 5 escenarios de §2
son la definición de "terminado" del hito; §7 es el menú de recortes y §8 la
frontera. Si sólo se lee una sección, que sea §2: eso es lo que hay que poder
hacer cuando el hito esté cerrado.*
