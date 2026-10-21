# 02 — Hoja de ruta por fases de Kitoru

> **Qué es este documento.** La hoja de ruta de Kitoru: qué se construye, en qué
> orden, con qué criterio se da por terminado cada fase, cuánto cuesta, qué la
> puede bloquear y qué se deja afuera.
>
> **Modelo seguido.** El de `FunshiEngineAI/docs/PLAN-INTEGRACION.md`: fases
> numeradas, cada una con objetivo, alcance, fuera de alcance, **criterios de
> aceptación verificables** y riesgos. Y el estado con checkboxes de
> `FunshiEngineAI/README.md` §"Estado / roadmap".
>
> **Fuentes leídas.** `docs/design/CONTEXTO.md`, `FunshiEngineAI/docs/PLAN-INTEGRACION.md`,
> `FunshiEngineAI/README.md`, y los documentos de diseño de Kitoru que existen al
> 2026-10-21 (`docs/design/domain/*`, `docs/design/teacher/*`,
> `docs/design/student/04-materiales.md`, `docs/design/analytics/07-temporal-cohortes.md`,
> `docs/design/platform/03-kitoru-server-api.md`), más el código real de
> `:core`, `:llm-api`, `:backend` y `:ui`.
>
> **Estado de este documento:** diseño. No escribe código.
> **Reglas del proyecto que condicionan la hoja de ruta:** *reutilizar no
> reescribir*, *portable por defecto*, *determinista*, *fail-fast con mensajes en
> español*, *el estudiante conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Resumen en una pantalla

| Fase | Qué cierra | Esfuerzo (dp) | Depende de | Estado |
|---|---|---:|---|---|
| **F0** | Base multiplatform: repo que compila, tests verdes, app desktop que abre | 3 | — | 🟡 80 % hecha |
| **F1** | Persistencia y dominio completo: esquema definitivo, migraciones, `FileSystem` en memoria | 6 | F0 | ⬜ no empezada |
| **F2** | UI base y roles: selector de rol, home del alumno, shell del docente, tema, navegación adaptativa | 10 | F0, F1 | 🟡 5 % (`Markdown`, `CodeBlock` portados) |
| **F3** | Chat del alumno con RAG: streaming, markdown, fuentes, distintivo de citas, herramientas y permisos | 12 | F1, F2 | ⬜ no empezada |
| **F4** | Materiales de la cátedra: subida, extracción, indexación, y el chat usando ese corpus | 18 | F1, F2, F3 | ⬜ no empezada |
| **F5** | Configuración del modelo: base de la cátedra, libertad del alumno, bloqueos | 10 | F1, F2, F3 | ⬜ no empezada |
| **F6** | Analítica del docente: panel con las métricas deterministas | 14 | F1, F3, F5 | ⬜ no empezada |
| **F7** | Explorador de chats: ver los chats de los alumnos respetando la visibilidad | 10 | F3, F6 | ⬜ no empezada |
| **F8** | Tareas y entregas | 12 | F2, F3, F6, F7 | ⬜ no empezada |
| **F9** | Sincronización y Kitoru-Server: contrato y cliente | 8 | F1, F5, F7 | ⬜ no empezada |
| **F10** | IA local en escritorio: descarga del GGUF y `llama-server` | 12 | F3, F5 | ⬜ no empezada |
| **F11** | Android: app, SAF, adaptación de pantallas | 14 | F1, F2, F3, F9 | ⬜ no empezada |

**Total: 129 días-persona.**

- **1 dp = 6 horas efectivas** (código + tests + documentación + revisión).
- El calendario de §5 asume **una persona a 4 días por semana** (≈ 4 dp/semana):
  129 dp ≈ **32 semanas ≈ 8 meses**. Con dos personas a medio tiempo, la
  primera mitad se acorta a ~5 meses (ver §5.4).
- **Fechas tentativas**, no comprometidas: se recalculan al cerrar cada fase.

**Los dos objetivos que hay que responder de entrada:**

| Pregunta | Respuesta corta | Fecha tentativa |
|---|---|---|
| ¿Cuándo un **alumno** puede usarlo de verdad? | Chat con RAG sobre el material de la cátedra, con citas verificadas (fin de F4) | **2027-01-27** |
| ¿Cuándo un **docente** puede usarlo de verdad? | Configura la cátedra, sube material, ve analítica y chats de sus alumnos (fin de F7) | **2027-03-31** |

Y el hito de piloto: **un docente real, con sus alumnos reales, usando Kitoru**
— fin de F7, con el Kitoru-Server todavía opcional (§5.3).

---

## 1. Punto de partida real (verificado en el repo el 2026-10-21)

### 1.1 Lo que ya está

| Módulo | Estado real | Nota |
|---|---|---|
| `:core` | ✅ compila desktop + Android | Plataforma + dominio + repositorios `File*` sobre `JsonListStore` |
| `:llm-api` | ✅ compila desktop + Android | `LlmGateway`, `OpenAiCompatibleGateway`, `LlmChunk` tipado, `LocalModelService` (desktop real, **Android stub que lanza `LlmException`**) |
| `:backend` | ✅ compila desktop + Android | RAG (BM25, `Chunker`, `KnowledgeBase`), `AgentLoop`, `CitationVerifier`, tools (`get_time`, `create_file`) |
| `:ui` | 🟡 parcial | Sólo `Markdown.kt` y `CodeBlock.kt` portados. **No hay app, ni ViewModel, ni tema, ni pantallas** |
| `:desktop` | ❌ **no existe** | `settings.gradle.kts` lo incluye, pero **no hay directorio ni `build.gradle.kts`** |
| `:android` | ❌ **no existe** | Ídem |

Tests: **sólo `:backend/src/desktopTest`** (8 clases JUnit 5 portadas de
FunshiEngineAI: `Bm25IndexTest`, `ChunkerTest`, `CitationVerifierTest`,
`AgentLoopTest`, `ToolCallSanitizerTest`, `ToolPermissionsTest`,
`ToolRegistryTest`, `PromptToolParserTest`). **`:core` y `:llm-api` no tienen
ningún test.** `:ui` tampoco.

Git: **el repo no tiene ni un commit.** `git status` muestra todo como untracked.

### 1.2 Lo que falta para que esto sea una base (y no un cajón de partes)

1. **`:desktop` y `:android` no existen** aunque `settings.gradle.kts` los
   incluye, y `ui/build.gradle.kts` apunta a
   `mainClass = "com.funshiengine.kitoru.desktop.MainKt"`, una clase que no
   está en ningún lado. Hoy no hay **nada** que se pueda ejecutar.
2. **Cero tests en `:core`** — y `:core` es donde vive la regla de negocio
   (`ModelPolicy.resolveFor`, `AnalyticsAnalyzer`, `ChatVisibility`,
   `Enrollment`). Es la parte con más invariantes y sin una sola prueba.
3. **Sin CI**: nadie sabe si el repo compila después de un cambio.
4. **Sin commit inicial**: no hay historia, ni rama, ni remoto sincronizado.
5. **`FileSystem` no tiene operaciones binarias** (`readBytes`/`writeBytes`) ni
   `rename`: sin eso no se puede subir un PDF ni hacer escrituras atómicas.
6. **No hay `InMemoryFileSystem`**: los tests de repositorio no se pueden
   escribir sin tocar el disco.
7. `FileMessageRepository.listForCourse` filtra por el **prefijo del id**
   (`"$courseId|"`): es una convención no escrita, frágil y ya candidata a
   romper (ver F1).
8. Los documentos de diseño de `docs/design/` **siguen apareciendo** (los
   subagentes hermanos escriben en paralelo). F1 y F9 dependen de documentos que
   todavía no están: `docs/design/platform/02-persistencia.md` y el contrato de
   distribución de la cátedra al alumno.

### 1.3 Definición de "terminada" (aplica a todas las fases)

Una fase está terminada cuando **las cinco** cosas son verdad:

1. `./gradlew build` (desktop) y `./gradlew :android:assembleDebug` pasan.
2. `./gradlew allTests` pasa: **0 tests rojos**, y el número de tests de la fase
   es el declarado en sus criterios.
3. Los criterios de aceptación "manuales" tienen un guion escrito y
   **reproducible a mano** en `docs/design/process/scripts/` (o un test
   automatizado equivalente), y alguien lo ejecutó.
4. El **documento de diseño** de la fase (§9) está actualizado y su *checklist
   de implementación* tiene marcadas las partes hechas.
5. Hay **un commit** con mensaje `F<n>: <qué cierra>` y el `CHANGELOG` de la
   fase.

---

## 2. Las fases

### F0 — Base multiplatform (cierre)

**Objetivo.** Cerrar la base: que un clone limpio del repo **compile, tenga
tests verdes y abra una ventana de escritorio** en menos de cinco minutos de
setup, y que a partir de acá cada fase sea un incremento que no rompe lo
anterior.

**Estado real.** Los tres módulos de lógica (`:core`, `:llm-api`, `:backend`)
compilan en desktop y Android y sus tests pasan. **Falta el 20 % que hace que
eso sea un producto y no un repo de bibliotecas**: los módulos de app, la CI, el
primer commit y los tests del dominio.

**Alcance.**

- Crear `:desktop` (aplicación Compose Desktop, `mainClass` real, ícono y
  título "Kitoru", pantalla de arranque con el nombre de la app y un `dataDir`
  inicializado) y `:android` (módulo `com.android.application`, `minSdk 24`,
  launcher mínimo).
- Un **`AppState`/`AppViewModel` mínimo** en `:ui` que arranque la app con un
  `FileSystem` real (`systemFileSystem()`), fije `KitoruPaths.dataDir` y
  muestre un estado vacío ("Kitoru — sin cátedras"). Sin pantallas de negocio.
- **Tests de `:core`**: al menos 40 casos para `ModelPolicy.resolveFor`
  (bloqueos), `ChatVisibility`, `Roles`, `AnalyticsAnalyzer` y los repositorios
  (con un `FileSystem` temporal real, aún sin `InMemoryFileSystem`).
- **CI**: workflow de GitHub Actions con `./gradlew build allTests` en JDK 21
  (Windows o Linux) y caché de Gradle.
- **Primer commit** + `README.md` del repo (qué es, cómo se compila, cómo se
  corren los tests) y `docs/ARQUITECTURE.md` inicial con el mapa de módulos.
- **`FileSystem` binario**: agregar `readBytes`/`writeBytes`/`rename` a la
  interfaz y a la implementación `jvmCommonMain` (los tres son triviales con
  `java.io.File`).

**Fuera de alcance.** Persistencia definitiva (F1), cualquier pantalla de
producto, el chat, el servidor, la IA local.

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F0.1 | Dado un clone limpio del repo con JDK 21, cuando se ejecuta `./gradlew build`, entonces termina con código 0 y sin errores de compilación en `:core`, `:llm-api`, `:backend`, `:ui`, `:desktop`. |
| F0.2 | Dado el mismo clone, cuando se ejecuta `./gradlew allTests`, entonces pasan **al menos 48 tests** (los 8 + los 40 nuevos de `:core`), 0 rojos. |
| F0.3 | Cuando se ejecuta `./gradlew :desktop:run`, entonces se abre una ventana titled **"Kitoru"** que muestra el estado vacío y no tira excepciones en la consola. |
| F0.4 | Cuando se ejecuta `./gradlew :android:assembleDebug`, entonces produce `android/build/outputs/apk/debug/android-debug.apk`. |
| F0.5 | El `README.md` del repo dice, en español, los tres comandos (`build`, `allTests`, `:desktop:run`) y el JDK requerido, y un lector puede reproducirlos sin preguntar nada. |
| F0.6 | Existe un workflow de CI que corre `build` + `allTests` en cada push y está **verde** en el primer push. |
| F0.7 | `git log` muestra **al menos un commit** con todos los archivos versionados (`.gitignore` incluye `.gradle/`, `build/`, `local.properties`). |
| F0.8 | `FileSystem` tiene `readBytes`, `writeBytes` y `rename`, y hay un test en `:core` que escribe un archivo binario de 1 MB, lo renombra y lo lee de vuelta con el mismo contenido. |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **3 dp** | — | **El toolchain no compila para Android en CI.** Kotlin 2.1.0 + Compose 1.7.3 + AGP 8.7.2 es una combinación que ya funciona local pero puede fallar en un runner limpio por falta de Android SDK. **Mitigación:** la CI corre el build de Android **sólo** si el SDK está disponible (`setup-android`), y el build de desktop + tests es **obligatorio** en todo push; el APK se valida en un job opcional y scheduled. |

---

### F1 — Persistencia y dominio completo

**Objetivo.** Decidir y **fijar** el esquema de datos definitivo, tener
persistencia real (con `FileSystem` en memoria para poder testear sin disco),
repositorios que funcionen y **migraciones** que no rompan nada. Es la fase
que, si se apura, se paga cara en F7 y F9.

**Estado real.** Hay repositorios `File*` sobre `JsonListStore` (un JSON por
tipo en `config/`), `KitoruJson` con `ignoreUnknownKeys` y `encodeDefaults`, y
`KitoruPaths` (`dataDir`, `configDir`, `coursesDir`, `cacheDir`). **No hay
migraciones, no hay versión de esquema, no hay `FileSystem` en memoria, y el
texto extraído de los materiales vive dentro del mismo JSON que los metadatos**
(peor: `JsonStore` reescribe el archivo entero en cada `save`).

**Decisión de esquema (propuesta, a confirmar — ver §7.1).**
**JSON por tipo + archivos sueltos**, no SQLite, en el hito 1:

| Dato | Dónde vive |
|---|---|
| Cátedras, inscripciones, perfiles, políticas | `config/courses.json`, `config/enrollments.json`, `config/profiles.json` |
| Materiales (metadatos) | `config/materials.json` |
| Texto extraído de un material | `courses/<courseId>/text/<materialId>.txt` (**sale del JSON**) |
| Hilos | `config/threads.json` |
| Mensajes | `courses/<courseId>/threads/<threadId>.json` (**uno por hilo**: un `messages.json` gigante se reescribe entero en cada mensaje) |
| Archivos originales | `courses/<courseId>/files/<materialId>__<nombre>` |
| Salidas de herramientas | `courses/<courseId>/outputs/<threadId>/` |
| Versión de esquema | `config/app.json` → `{ "schemaVersion": 3 }` |

**Por qué JSON y no SQLite:** (a) es lo que ya existe y funciona en las dos
plataformas sin dependencias nativas; (b) SQLite multiplataforma exige SQLDelight
o Room (Android-only) — Room no compila en desktop; (c) el volumen real de un
curso es de miles de mensajes, no millones; (d) los archivos JSON se **diagnostican
con un editor de texto**, que es una ventaja enorme cuando el usuario reporta
un problema. **El coste** (reescritura completa, sin consultas) se acota con el
sharding por hilo. SQLite queda como opción de la §7.1 con un disparador
explícito: *más de 50 000 mensajes por curso o más de 200 materiales*.

**Alcance.**

1. **`InMemoryFileSystem`** en `:core` (`commonTest` **y** `commonMain`, porque
   la app móvil también necesita un FS por sandbox): mapa de rutas → bytes, con
   `exists`, `isDirectory`, `sizeBytes`, `readText`, `writeText`, `readBytes`,
   `writeBytes`, `delete`, `createDirectories`, `listFiles`, `copy`, `rename`.
   Test: escribir, listar, renombrar, borrar y "leer un archivo que no existe"
   devuelve el vacío sin excepción.
2. **Repositorios definitivos**, con las interfaces de `:core` como contrato
   (no se rompen: `CourseRepository` etc. ya son interfaces):
   - `FileMessageRepository` pasa a guardar **un archivo por hilo** y su
     `listForCourse` deja de filtrar por el prefijo del id (se elimina la
     convención `"curso|hilo"`).
   - `FileMaterialRepository` guarda **sólo metadatos**; el texto extraído va al
     `.txt` y se hidrata al leer (`MaterialRepository.loadText(id)`).
   - `MaterialRepository` gana `updateExtraction(id, text, warning, extractor)`.
3. **Versionado y migraciones.**
   - `config/app.json` con `schemaVersion`.
   - `object Migrations { fun migrate(json: JsonObject, from: Int): JsonObject }`
     con un paso por versión, **idempotente** (aplicar dos veces = mismo
     resultado) y **fail-fast** si la versión del archivo es **mayor** que la
     soportada ("Este archivo de Kitoru lo creó una versión más nueva").
   - `JsonStore` gana `readMigrated()`: al leer, si la versión es vieja, migra
     en memoria y **reescribe** el archivo migrado.
4. **Escritura atómica**: `JsonStore._write` escribe a `<archivo>.tmp` y
   `rename` sobre el destino. Un corte de luz a mitad de escritura deja el
   archivo viejo intacto, no uno corrupto.
5. **Respaldo y corrupción**: al leer un JSON inválido, se renombra a
   `<archivo>.corrupto-<timestamp>` y se devuelve el vacío **con un aviso en
   español** (nunca un crash silencioso). Hoy `JsonStore` ya traga la excepción,
   pero **no avisa**: eso se corrige.
6. **Rutas por plataforma**: `KitoruPaths` con `actual` por plataforma —
   desktop: `%LOCALAPPDATA%\Kitoru` (Windows) o `~/.local/share/Kitoru`
   (Linux); Android: `context.filesDir/kitoru`. Se documenta en el README.
7. **Concurrencia**: un `Mutex` por archivo (ya está) + **un `Mutex` global de
   escritura** por repositorio para evitar el "read-modify-write" perdido entre
   dos corrutinas.
8. **Tests de dominio y de repositorio** (con `InMemoryFileSystem`): guardar,
   leer, listar, borrar, filtrar por cátedra/autor; **ida y vuelta de
   serialización** de cada entidad con todos sus campos por defecto; **migración
   desde un `messages.json` de la versión 1** (el formato que wrote FunshiEngineAI)
   sin perder mensajes; **archivo corrupto** → vacío + aviso + copia `.corrupto`.

**Fuera de alcance.** SQLite (hasta que se cumpla el disparador de §7.1),
cifrado en reposo, sincronización (F9), el formato de paquete `.kitoru` (F7), y
el reparto de la cátedra al alumno (§7.2: cómo llega la config de la cátedra al
dispositivo del alumno — **decisión abierta**).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F1.1 | `InMemoryFileSystem` está en `core/src/commonMain` y su test (en `:core:desktopTest`) cubre escribir, listar, renombrar, borrar y leer un inexistente (devuelve `""`/excepción tipada, nunca `NullPointerException`). |
| F1.2 | `FileMessageRepository` **no** contiene la cadena `"$courseId|"`: un test verifica que un hilo con id arbitrario (`"abc-123"`) aparece en `listForCourse(courseId)` y en `listForUser`. |
| F1.3 | El texto de un material de 500 KB **no** está en `materials.json`: un test afirma que `materials.json` pesa < 20 KB con 50 materiales y que el texto completo se lee del `.txt` y coincide byte a byte. |
| F1.4 | Un `config/app.json` con `schemaVersion: 1` y un `messages.json` viejo se abren y **migran**: tras abrir la app, `schemaVersion` vale la versión actual, ningún mensaje se perdió y un segundo arranque no cambia el archivo (idempotencia). |
| F1.5 | Un `courses.json` con `{"id": "x",` (JSON truncado) **no** rompe la app: `courses.json` queda renombrado a `courses.json.corrupto-<ts>` y la app muestra el aviso "El archivo de cátedras estaba dañado; se guardó una copia y se empezó de cero". |
| F1.6 | La escritura es atómica: un test simula un fallo **entre** escribir el `.tmp` y el `rename` y verifica que el `courses.json` original sigue íntegro y legible. |
| F1.7 | `KitoruPaths.dataDir` en desktop apunta dentro del perfil del usuario (no a la raíz del disco) y en Android dentro del sandbox de la app. Un test por plataforma lo verifica. |
| F1.8 | Todos los campos de `Course`, `Enrollment`, `Material`, `ChatThread` y `ChatMessage` tienen **valor por defecto**, salvo los que ya no tenían (compatibilidad con JSON existentes): un test serializa una entidad vacía y la deserializa sin excepción. |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **6 dp** | F0 | **Decidir el esquema tarde obliga a reescribir dos veces** (F7 y F9 tocan el mismo código). **Mitigación:** (a) el esquema se **congela en el cierre de F1** y las decisiones §7.1 tienen fecha de cierre; (b) los repositorios ya son interfaces: cambiar el motor es escribir otra implementación, no tocar la UI; (c) el sharding por hilo deja la puerta abierta a SQLite sin cambiar las interfaces. |

---

### F2 — UI base y roles

**Objetivo.** Que la app tenga **esqueleto navegable** y que el usuario sepa
qué es: entra, elige **rol** (docente o alumno), elige **cátedra**, y la app
lo lleva a su pantalla. Sin esto, ninguna otra fase es demostrable.

**Estado real.** `:ui` tiene dos componentes portados (`Markdown.kt`,
`CodeBlock.kt`). **No hay tema, ni shell, ni ViewModel, ni navegación.** El
diseño de la navegación del docente ya está escrito
(`docs/design/teacher/01-navegacion.md`) y es la referencia de esta fase.

**Alcance.**

1. **Tema** en `:ui/commonMain`: Material 3 con paleta propia (claro y oscuro),
   tipografía y espaciado; toggle en `AppSettings` que **persiste**.
2. **`AppViewModel`** (estado + acciones, con `mutableStateOf`; sin
   `androidx.lifecycle.ViewModel`, según `teacher/01` D5) + `AppShell` +
   `AppTopBar` + `ErrorBanner` + `LoadingPane`.
3. **Selector de rol**: el rol **no es un toggle global**: es un filtro sobre
   las inscripciones del usuario (`teacher/01` D1). Si el usuario es docente en
   una cátedra y alumno en otra, ve ambas. La pantalla raíz lista sus
   cátedras agrupadas por rol.
4. **Home del alumno**: lista de sus cátedras, con estado de la cátedra, código
   de inscripción, y **unirse a una cátedra** con código (`joinCode`,
   ver `domain/01` §2).
5. **Shell del docente**: rail lateral con las 9 secciones de `teacher/01` §2.2
   (Panel, Materiales, Tareas, Anuncios, Modelo, Analítica, Chats, Alumnos,
   Ajustes), agrupadas, con el badge de rol y el selector de cátedra en la top
   bar. **Las secciones todavía no implementadas muestran un placeholder
   honesto** ("Esta sección llega en la fase F4"), no un error.
6. **Navegación adaptativa**: rail expandido ≥ 840 dp, rail de iconos 600–839
   dp, `ModalNavigationDrawer` por debajo. **Sin `navigation-compose`**: pila
   manual (`TeacherNavState`), como manda `teacher/01` D4.
7. **Back stack manual** + `WindowSizeClass` propio en `:core`.
8. **Accesibilidad en el mismo PR** que cada pantalla (foco, semántica,
   contraste, atajos): `teacher/01` §7.
9. **Tests de UI**: al menos 3 tests de Compose (`compose.uiTest` en desktop)
   que verifiquen el selector de rol, el rail y el breakpoint.

**Fuera de alcance.** Las pantallas de negocio (Materiales, Tareas, Anuncios,
Analítica, Chats, Alumnos, Modelo): en esta fase son placeholders. El chat
(F3). La persistencia de la config del modelo (F5).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F2.1 | Dado un perfil con una inscripción docente y una alumno, cuando se abre la app, entonces la pantalla raíz muestra **dos grupos** ("Docente" y "Alumno") y al elegir uno se entra a la pantalla de ese rol. |
| F2.2 | Dado un perfil con una sola inscripción de alumno, cuando se abre la app, entonces se muestra la **home del alumno** directamente (sin pasar por un selector que no aplica). |
| F2.3 | Dado el shell del docente, cuando se mira el rail, entonces aparecen las **9 secciones** con sus 4 grupos y sus íconos, en el orden de `teacher/01` §2.2. |
| F2.4 | Dada una ventana de 1000 px de ancho, cuando se redimensiona a 700 px, entonces el rail colapsa a iconos; a 500 px, entonces aparece el drawer; y no hay solapamiento ni corte de texto en ningún ancho intermedio (verificable con los tests de UI). |
| F2.5 | El toggle de tema claro/oscuro cambia los colores **al instante** y el choix **persiste** al cerrar y reabrir la app. |
| F2.6 | Todas las etiquetas visibles están **en español** y ningún texto de la UI muestra una clave de API en claro (test: `assertFalse(ui.contains(apiKey))`). |
| F2.7 | El esqueleto corre **con datos falsos** (fakes de repositorio), sin tocar el disco: hay un modo de desarrollo (`KITORU_FAKE_DATA=1`) documentado en el README. |
| F2.8 | Hay al menos **3 tests de UI** verdes en `:ui:desktopTest` y la navegación con teclado funciona en todas las secciones (Tab/Enter reaches every action). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **10 dp** | F0, F1 | **Compose Multiplatform 1.7.3 tiene huecos** (p. ej. `navigation-compose` no es multiplatform): cada hueco se convierte en trabajo no planificado. **Mitigación:** (a) el diseño ya decidió no usar `navigation-compose` y usar pila manual; (b) **prohibido subir de versión** de Compose/Kotlin durante F0–F5 (queda congelado); (c) todo hueco nuevo se resuelve con `expect/actual` en `jvmCommonMain`, nunca con `java.awt`/`javax.swing` en `commonMain`. |

---

### F3 — Chat del alumno con RAG

**Objetivo.** El corazón del producto: el alumno chatea contra el modelo de la
cátedra, con **streaming**, **markdown**, **fuentes**, **distintivo de citas**,
**herramientas con permisos**, y todo persistido.

**Estado real.** Todo el motor ya está portado y testeado en `:backend`:
`AgentLoop` (tool-calling nativo y por prompt), `ToolPermissions`, `ToolRegistry`,
`ToolCallSanitizer`, `PromptToolParser`, `CitationVerifier`, `Bm25Index`,
`Chunker`, `KnowledgeBase`, `RagEngine`. `OpenAiCompatibleGateway` con SSE.
**Lo que falta es la UI y el cableado**: no hay `ChatViewModel`, ni pantalla,
ni `@Composable` de burbuja, de fuentes, de distintivo ni de diálogo de permiso.

**Alcance.**

1. **Pantalla de chat**: lista de hilos del alumno, vista de hilo, redactor con
   enviar/detener, streaming con scroll automático y botón "Detener".
2. **`ChatViewModel`**: arma el `AgentLoop`, mantiene el historial, aplica
   `ModelPolicy.resolveFor(studentChoice)` **por mensaje**, guarda hilo y
   mensajes en los repositorios de F1, y expone el estado como `mutableStateOf`.
3. **Markdown y código**: `Markdown.kt` y `CodeBlock.kt` ya portados → se
   integran al hilo. El bloque de código ofrece **Copiar** y **Guardar como…**
   (con extensión real según el lenguaje del fence).
4. **Fuentes**: panel de fuentes por respuesta (etiqueta, ruta, fragmento),
   **clickeable** para abrir el material en el fragmento exacto (el hook existe
   desde F4; en F3 abre la vista de texto si el material está disponible).
5. **Distintivo de citas**: al terminar cada respuesta, `CitationVerifier`
   corre (es una función pura, sin red) y la burbuja muestra el distintivo con
   el **copy exacto** de `teacher/09` §4.4:
   - `Citas verificadas (2)`
   - `1 cita sin respaldo`
   - `Sin citas`
   y al abrirlo, el detalle cita por cita (por qué pasó o por qué no).
6. **Herramientas y permisos**: `ToolRegistry` con las tools habilitadas por la
   cátedra; diálogo "¿Permitir?" con tres botones (Permitir siempre / Una vez /
   Cancelar) **por chat**; comandos `/permitir-<categoría>`, `/permisos`,
   `/revocar-<categoría>`; tarjetas de tool call en el hilo (en curso / OK /
   error).
7. **Modo local**: si la cátedra habilita IA local y el alumno la usa, el chat
   funciona con `LocalModelService` (desktop) y en **Android muestra el error
   tipado** ("La IA local no está disponible en Android todavía") sin romper la
   sesión.
8. **Guardado**: hilo y mensajes persistidos con `latencyMs`, `tokensIn/Out`,
   `sources`, `citation` y `toolCalls` — que es lo que después consume la
   analítica (F6).

**Fuera de alcance.** LaTeX (F12), búsqueda web (fuera de alcance, §4),
MCP (§4), adjuntos descargables de `download_url` (§4), voz (§4), y el corpus
real de la cátedra (eso es F4: en F3 el chat funciona contra un corpus de
prueba y, si no hay material, responde en modo "sin material").

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F3.1 | Dado un `FakeGateway` que emite 3 chunks de texto, cuando el alumno envía un mensaje, entonces la UI los muestra **en orden, incrementalmente** (el test verifica que tras el primer chunk ya hay texto en pantalla). |
| F3.2 | Dado un material de prueba con un texto y una pregunta cuya respuesta cita `[1]`, cuando termina la respuesta, entonces el distintivo dice exactamente **`Citas verificadas (1)`**. |
| F3.3 | Dado un material cuya fuente **no** contiene lo que la respuesta afirma, cuando termina la respuesta, entonces el distintivo dice **`1 cita sin respaldo`** y el detalle explica por qué (motivo en español, tomado del verificador). |
| F3.4 | Dado un mensaje del modelo con ` ```json `, cuando se renderiza, entonces se ve como bloque de código con el lenguaje, con botón **Copiar** que pone el texto exacto en el portapapeles y **Guardar como…** que propone el nombre con la extensión real (`.py`, `.kt`, `.cpp`…). |
| F3.5 | Dado un modelo que pide una tool de la categoría `WEB`, cuando la tool no está concedida, entonces aparece el diálogo de permiso con el nombre de la tool y sus argumentos; si el usuario **cancela**, el chat **no** se corta: la respuesta sigue y el modelo recibe el feedback de "el usuario no autorizó la herramienta". |
| F3.6 | Dado un modelo que insiste con la misma tool después de un "Cancelar", cuando llega el **segundo** intento, entonces el permiso se deniega **sin** volver a mostrar el diálogo y el bucle corta. |
| F3.7 | Dado un `ModelPolicy` con `lockedFields = {TEMPERATURE}` y un alumno que fija `temperature = 0.9`, cuando se manda el mensaje, entonces al gateway le llega `temperature = 0.0` (el de la cátedra) y la UI de configuración del alumno muestra el campo **deshabilitado** con el motivo. |
| F3.8 | Dado un hilo con 3 mensajes, cuando se cierra la app y se reabre, entonces el hilo aparece con su título, sus mensajes, sus fuentes y su distintivo de citas **igual** que antes (comparación de datos, no de píxeles). |
| F3.9 | En Android, un intento de IA local produce el mensaje **`La IA local todavía no está disponible en Android`** y la app **no** se cae (test en `:llm-api:androidUnitTest` o en el harness compartido). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **12 dp** | F1 (repos), F2 (shell, tema), `:backend` (ya hecho) | **El streaming + markdown se rompen juntos**: un bloque de código a medio emitir produce "render raro" (el riesgo real de FunshiEngineAI). **Mitigación:** render por **fragmentos estables** (no se parsea un fence abierto; se muestra como texto hasta que cierra) y test de UI con un stream truncado a mitad de fence. Además: el distintivo de citas se calcula **después** de terminar el mensaje, nunca durante. |

---

### F4 — Materiales de la cátedra

**Objetivo.** El docente sube material, el sistema le **saca el texto**, lo
**trocea**, lo **indexa**, y el chat del alumno **responde con ese corpus** y
cita la fuente.

**Estado real.** Diseño completo escrito (`teacher/03-materiales.md` con el
pipeline de 7 pasos, `student/04-materiales.md` con la vista del alumno). El
código **no existe**: no hay extracción de texto, ni `MaterialStatus`, ni
`CourseIndex`, ni la pantalla.

**Alcance — F4a: corpus de texto plano (4 dp).**

- `TextExtractor` (interfaz) + implementación para `.txt`, `.md`, `.csv`,
  `.html` (HTML → texto legible), y **`.pdf` con capa de texto** si hay
  extractor disponible.
- Copia del archivo a `courses/<courseId>/files/`, `Material.status`,
  extracción a `courses/<courseId>/text/<materialId>.txt`, `Chunker.split` con
  `course.chunkSize`, indexación en `Bm25Index` (`CourseIndex` en memoria, con
  `sync(onStatus)`).
- Pantalla del docente: lista, subida (file picker + drag & drop en desktop),
  detalle con 3 pestañas (**Texto**, **Fragmentos**, **Probar pregunta**),
  acciones (reindexar, ocultar, editar texto, reetiquetar, eliminar).
- Pantalla del alumno: lista con tipo/tamaño/fragmentos/fecha/etiquetas, estado
  de extracción, búsqueda full-text por fragmento con término resaltado,
  "ver en el material" con scroll al fragmento, "preguntar a la IA sobre esto".

**Alcance — F4b: formatos office y PDF reales (10 dp).**

- `MaterialFormat` por extensión + **firma binaria** (un `.pdf` renombrado a
  `.txt` se detecta por la firma `%PDF`).
- Extractores: **PDF** (capa de texto), **DOCX/PPTX** (OOXML), **PPTX** por
  diapositiva.
- Cada extractor es un `expect/actual` o una implementación por plataforma con
  **el mismo contrato**: `ExtractionResult(text, warning, extractor, pageCount)`.
- Un PDF **escaneado** (sin texto) queda `NEEDS_REVIEW` con el aviso "Este PDF
  no tiene capa de texto: pegá el texto o subí el documento original".

**Alcance — F4c: integración con el chat (4 dp).**

- `CourseMaterialSource` alimentado por los materiales **visibles** de la
  cátedra; `RagEngine.prepare(question, history, useDocs=true)`; las fuentes
  que llegan al mensaje son **fragmentos reales** (etiqueta + material +
  posición), y el chat los muestra y los hace clicables.

**Fuera de alcance.** OCR (§4), edición colaborativa del material,
previsualización binaria del PDF en la app (sólo el texto y los fragmentos),
almacenamiento remoto de materiales (§7.2), y el boosting por unidades del
dominio (`domain/10` §5: entra en F5 con el RAG configurable).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F4.1 | Dado un PDF con capa de texto subido por el docente, cuando termina el pipeline, entonces el material queda en `READY`, con `chunkCount > 0`, y el texto extraído coincide con el texto del PDF en los primeros 500 caracteres. |
| F4.2 | Dado un PDF escaneado (imagen sin texto), cuando se extrae, entonces el estado es `NEEDS_REVIEW` con el aviso **"Este PDF no tiene capa de texto…"** y el material **no** entra al índice. |
| F4.3 | Dado un material con `visible = false`, cuando un alumno pregunta algo que ese material contiene, entonces el RAG **no** lo recupera, la respuesta no lo cita, y el alumno **no** lo ve en la lista de materiales (verificable con un test que compruebe que el `KnowledgeBase` no tiene chunks de ese material). |
| F4.4 | Dado un material de prueba que dice "el gradiente se calcula como la derivada de la función de pérdida", cuando el docente escribe "cómo se calcula el gradiente" en la pestaña **Probar pregunta**, entonces el recuperador devuelve ese fragmento en el **primer** resultado, con el término resaltado. |
| F4.5 | Dado un material con 10 000 palabras, cuando se indexa, entonces el número de fragmentos es `≈ texto / chunkSize` (± 15 %) y **cada fragmento conserva su material y su posición**. |
| F4.6 | Dado que el docente cambia `chunkSize` de 1200 a 600 y pulsa **Reindexar**, cuando termina, entonces `chunkCount` **se duplica aproximadamente** y el material queda `READY` con la fecha de reindexado actualizada. |
| F4.7 | Dado un alumno que selecciona un fragmento y pulsa "Preguntar a la IA sobre esto", cuando se manda la pregunta, entonces el prompt al modelo contiene ese fragmento como `[1]` y el mensaje del usuario incluye la nota de "seleccionado por el estudiante". |
| F4.8 | Un `.pdf` renombrado a `.txt` se detecta por **firma binaria** y se procesa como PDF, no como texto (test del detector). |
| F4.9 | Subir un archivo de 200 MB falla con un mensaje en español que dice el límite, y **no** deja el archivo a medias en disco (se limpia el `.tmp`). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **18 dp** (4 + 10 + 4) | F1 (materiales, paths), F2 (pantallas), F3 (chat) | **La extracción de PDF/DOCX es un pozo sin fondo de dependencias** (PDFBox no anda en Android; POI es JVM puro y enorme). **Mitigación:** (a) el pipeline tiene **contrato** (`TextExtractor`) y **orden de entrega**: F4a (texto plano) **primero**, que ya da el 80 % del valor; (b) los formatos office se parsean con un extractor **propio y mínimo** (OOXML es ZIP + XML: ~1 dp) en vez de meter Apache POI; (c) la alternativa "el docente pega el texto" siempre está disponible (es el camino de `NEEDS_REVIEW`); (d) **OCR queda explícitamente afuera** (§4). |

---

### F5 — Configuración del modelo

**Objetivo.** Que el docente **fije la base** (servidor, modelo, generación, RAG,
herramientas, IA local) y que el alumno **ajuste lo que pueda** — y no lo que
esté bloqueado.

**Estado real.** El dominio ya tiene `ModelPolicy` (con `resolveFor`,
`validate`, `lockedFields`, `LockedField`), `Course.modelPolicy` y
`Course.toolRules`/`modelPresets` **pendientes** (propuestos en `teacher/05`).
La UI no existe. El cliente del Kitoru-Server tampoco (`platform/03` §14).

**Alcance.**

1. **Pantalla del docente** (`teacher/05`): 7 secciones — *Servidor de la
   cátedra* (URL + key), *Modelo*, *Generación*, *RAG*, *Permisos de
   herramientas*, *Lo que el alumno puede tocar*, *IA local*. Con el principio
   de **"un control por opción"** y los presets que **heredan** (`null`).
2. **Bloqueos en tres estados** (libre / rango / bloqueado) por campo, con la
   frase resumen `overrideSummary()` que se muestra **en la pantalla del
   docente y en la del alumno**.
3. **Emisión de keys**: (a) pegar la key de la institución (modo v1), o
   (b) pedirla al Kitoru-Server (F9, se deja el botón deshabilitado con el
   motivo hasta que exista).
4. **"Probar conexión"**: reusa `OpenAiCompatibleGateway.listModels()` con
   timeout de 20 s y cuatro estados; los errores salen por
   `LlmErrorMessages.describe(LlmException)` (nuevo, en `:llm-api`), **en
   español**.
5. **Panel del alumno**: los mismos campos, pero **deshabilitados** los
   bloqueados, con sliders de rango cuando hay rango, y un texto que explica
   qué cambió y por qué.
6. **`resolveFor` aplicado por mensaje** (no al arrancar): cambiar la política
   de la cátedra no reinicia el chat.
7. **`ApiKeyRepository`**: la key se guarda **enmascarada** en la UI, **en
   claro** en el disco (decisión §7.4), y se puede "olvidar".

**Fuera de alcance.** La emisión de keys contra el Kitoru-Server (F9), los
presets remotos de modelos, y el multi-perfil (F9/§7.8).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F5.1 | Dado un `ModelPolicy` con `lockedFields = {API_KEY}` y un alumno que **intenta** cambiar la key, cuando se guarda la configuración del alumno, entonces `resolveFor` devuelve la key de la cátedra y la UI muestra el campo deshabilitado con el motivo. |
| F5.2 | Dado un campo **con rango** (ej. `temperature` entre 0 y 1), cuando el alumno pone 1.8, entonces la UI **rechaza** con un mensaje en español y el valor effective queda dentro del rango. |
| F5.3 | Dado un campo libre (ej. `systemPrompt` desbloqueado), cuando el alumno lo edita, entonces `resolveFor` devuelve **el valor del alumno** y el chat siguiente lo usa. |
| F5.4 | El botón "Probar conexión" con una URL inválida muestra un error **en español** que distingue: URL mal formada / no responde / 401 (key inválida) / 404 (modelo inexistente) — cuatro mensajes distintos, con test. |
| F5.5 | La frase resumen (`overrideSummary()`) es **exactamente la misma** en la pantalla del docente y en la del alumno (test que compara el string de las dos vistas). |
| F5.6 | Cambiar la política de la cátedra **no borra ni reinicia** los chats existentes del alumno (test: el hilo sigue ahí y el siguiente mensaje usa la política nueva). |
| F5.7 | La key **no aparece en claro** en ninguna pantalla salvo el momento explícito de pegarla, y nunca en los logs (test que captura la salida de `println`/logger y busca el token). |
| F5.8 | Con `allowLocalModels = false` en la cátedra, cuando el alumno abre su configuración, entonces la sección de IA local está **oculta** (no sólo deshabilitada) y el chat sigue funcionando con el modelo de la cátedra. |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **10 dp** | F1, F2, F3 | **"El alumno puede tocar de más" o "de menos"**: un bloqueo mal implementado es una fuga de la política de la cátedra (p. ej. el alumno cambia la key y usa otro modelo). **Mitigación:** (a) `resolveFor` es **la única** vía por la que se arma la config efectiva, y se testea campo por campo (b) la UI **nunca** manda la config efectiva al gateway: manda `resolveFor(alumno)` y el dominio decide (c) la clave de la cátedra no viaja al cliente del alumno cuando está bloqueada: el servidor la resuelve. |

---

### F6 — Analítica del docente

**Objetivo.** El panel donde el docente ve **qué pasa en su cátedra**: uso,
retención, cohortes, horarios, calidad de las respuestas. Todo **determinista**
(sin modelo, sin red) y **respetando la visibilidad** de cada inscripción.

**Estado real.** `AnalyticsAnalyzer.analyze(...)` ya existe y calcula
`CourseAnalytics`/`StudentAnalytics` (`core/src/commonMain/.../AnalyticsAnalyzer.kt`).
El diseño de la vista temporal y por cohortes está escrito
(`analytics/07-temporal-cohortes.md`, 1279 líneas) y el de calidad en
`teacher/09-calidad.md`. **La UI no existe** y falta la parte temporal
(`TemporalAnalyzer`, `CalendarMath`, `CourseSchedule`), que además **corrige un
bug real**: `dayStart()` es medianoche **UTC** mientras `DayActivity` se
documenta como "medianoche local".

**Alcance.**

1. `CalendarMath` + `CourseSchedule` (ancla de la cursada, zona horaria,
   `termStart`) + `ChatMessage.tzOffsetMinutes` + `TemporalAnalyzer` —
   **deterministas**, con `now` como **parámetro** y porcentajes en **puntos
   básicos** (`Int`), sin `Double` en comparaciones.
2. `QualityAnalyzer` (de `teacher/09`): respuestas apoyadas / problemáticas /
   sin cita, por material, con las razones del verificador.
3. **Panel**: pestañas `Resumen | Actividad | Retención | Cohortes | Horarios |
   Calidad | Calendario`.
4. **Privacidad, dura**: la visibilidad se aplica **en el analizador**, no en la
   UI. `visibility.showsMetrics == false` ⇒ el alumno **no** aparece en ningún
   agregado. Cohortes con **n < 5 ⇒ "—"**.
5. Exportación **CSV** de los agregados (no del texto).
6. Tests "golden": un dataset fijo de 50 mensajes produce **números exactos**
   que están escritos en el test.

**Fuera de alcance.** Comparar alumnos entre sí (rankings, scores, "el peor
alumno"): **no existe** (§6.4). Series por token/costo. Notificaciones. Un
"heatmap de calidad" por palabra. Los gráficos en canvas ya están especificados
en `analytics/07` §4.3.

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F6.1 | Dado el dataset fijo de 50 mensajes, cuando se corre el analizador, entonces los números coinciden **exactamente** con los esperados escritos en el test golden (mensajes, respuestas, alumnos activos, latencia media, citas verificadas/sin respaldo). |
| F6.2 | Dado un alumno con `visibility = NONE`, cuando se corre el analizador, entonces no aparece en ningún agregado, no cuenta para `activeStudents` y `messagesConsidered` **excluye** sus mensajes. |
| F6.3 | Dado un alumno con `visibility = METRICS_ONLY`, cuando se corre el analizador, entonces **sí** cuenta en los números, pero ningún texto suyo es accesible (test: `MessageRepository` no se consulta para el texto). |
| F6.4 | Dado un mensaje enviado a las 21:30 en una cátedra con zona `UTC−3`, cuando se agrupa por día, entonces cae en **ese mismo día local** (no en el siguiente) — y el heatmap usa la zona del **autor**. |
| F6.5 | Dado una cohorte con 3 personas, cuando se muestra la tabla de cohortes, entonces la celda dice **"—"** y no un porcentaje. |
| F6.6 | El encabezado del panel de calidad dice la frase con los números reales del conjunto de datos: *"De 100 respuestas, 78 se apoyaron en el material; 12 afirmaron cosas que el material no dice; 10 no comportaron ninguna cita."* (con los números del dataset, no los del ejemplo). |
| F6.7 | La identidad `grounded + problematic + uncited == answers` **siempre** se cumple (invariante testeada sobre 50 datasets aleatorios con semilla fija). |
| F6.8 | El export CSV sale con los mismos números que la pantalla y **no contiene texto de conversaciones**. |
| F6.9 | Todo el cálculo es reproducible: correr el analizador dos veces con el mismo input da el mismo output (test de idempotencia). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **14 dp** | F1 (mensajes con `citation`), F3 (los datos), F5 (`modelLabel` para filtrar por modelo) | **La analítica como vigilancia** (§6.4). **Mitigación de diseño, no de código:** la primera pestaña se llama **"Calidad de las respuestas"** y no "Alumnos"; no hay ranking ni score por alumno; n mínimo de cohorte = 5; el docente ve un aviso explícito de qué está viendo; el alumno tiene una pantalla de **"qué ve el docente"** (F7). Además: el cálculo va en `:core` **puro**, sin I/O, para que sea testeable y auditable. |

---

### F7 — Explorador de chats

**Objetivo.** Que el docente vea **los chats de sus alumnos** — y sólo lo que
tiene derecho a ver. Es la fase donde la **tensión de producto** del punto (c)
se vuelve código.

**Estado real.** Diseño escrito (`teacher/07-chats.md` con los criterios de
aceptación completos). No hay `AccessLog` en el dominio (viene de
`domain/08-permisos.md`), ni `TeacherChatRepository`, ni la pantalla.

**Alcance.**

1. **`AccessLog`** en `:core` (`domain/08` §8): `VIEW_TEXT`, `IMPORT`,
   `EXPORT`, con `teacherId`, `studentId`, `threadId`, `at`, `occurrences`
   (coalescencia de 5 minutos).
2. **`TeacherChatRepository`**: una **frontera** que aplica la visibilidad
   **antes** de tocar los datos (no en la UI).
3. **Pantalla**: lista de alumnos → hilos → conversación, con el **filtro de
   visibilidad** en el centro; conversación con burbujas, fuentes, distintivo de
   citas, tarjetas de tool y adjuntos.
4. **Filtros y búsqueda**: por fecha, "sin respaldo", material y modelo;
   búsqueda de texto que encuentra **por fragmento** (no por palabra exacta).
5. **Exportación**: Markdown (con cabecera de procedencia) y JSON (con bloque
   `provenance`).
6. **Importación `.kitoru`**: el alumno que usa IA local puede **exportar su
   paquete** y el docente lo **importa** (queda auditado como `IMPORT`).
7. **Auditoría del alumno**: el alumno ve **sus propios** accesos en una
   pantalla "Auditoría de mis chats" (inversión del `AccessLog`).

**Fuera de alcance.** Push de chats (el servidor los entrega por *pull*,
`platform/03` D6), la moderación, la búsqueda semántica, y el docente
**escribiendo** en el hilo del alumno (queda fuera hasta que haya un caso de
uso claro; en `teacher/09` §6 se propone, así que sí entra, pero al final de la
fase).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F7.1 | Dado un alumno con `visibility = FULL`, cuando el docente abre su chat, entonces ve el texto completo **y** se escribe una entrada `AccessLog` de tipo `VIEW_TEXT` con `teacherId`, `studentId`, `threadId` y `at`. |
| F7.2 | Dado un alumno con `visibility = METRICS_ONLY`, cuando el docente abre su ficha, entonces la columna de hilos **no** muestra títulos, el visor **no** muestra texto y muestra el aviso "Este alumno usa IA local y no compartió sus conversaciones"; además, **el repositorio de mensajes no se invoca** (test con un fake que cuenta llamadas: `assertEquals(0, fake.calls)`). |
| F7.3 | Dado un alumno con `visibility = NONE`, cuando el docente mira el listado, entonces no aparece, y el pie de pantalla informa la cantidad de alumnos ocultos. |
| F7.4 | Abrir el mismo chat dos veces en menos de 5 minutos produce **una** entrada de auditoría con `occurrences = 2`. |
| F7.5 | La exportación a Markdown incluye la cabecera de procedencia (docente, alumno, curso, fecha, permiso) y reproduce el texto con las fuentes y el veredicto de citas; la de JSON es parseable e incluye `provenance`. |
| F7.6 | Un `.kitoru` inválido muestra un error en español y **no escribe nada** en disco (test: el directorio de destino queda igual que antes). |
| F7.7 | Un `.kitoru` válido muestra una vista previa del manifiesto, crea los hilos marcados como "importado por el alumno" y deja un `AccessLog` de tipo `IMPORT`. |
| F7.8 | El alumno puede ver **sus** accesos: la pantalla "Auditoría de mis chats" lista las entradas donde `studentId` es él, y coincide con lo que el docente hizo. |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **10 dp** | F3 (datos del chat), F6 (métricas compartidas), `domain/08` (permisos) | **Fuga de privacidad por una condición mal puesta** (un `if` mal escrito en la UI expone un chat a un ayudante sin permiso). **Mitigación:** la regla se aplica **en el repositorio** (`TeacherChatRepository`), que **filtra antes de leer**, y la UI nunca recibe lo que no debe; test explícito que **cuenta las llamadas al repositorio de mensajes** y afirma 0 para `METRICS_ONLY`; ningún texto de alumno se loguea. |

---

### F8 — Tareas y entregas

**Objetivo.** Que el docente pida **trabajos** y el alumno **entregue**, con
política de IA explícita y la posibilidad de **enlazar el chat** que el alumno
usó para resolverlo.

**Estado real.** Diseño muy completo (`domain/06-tareas.md`: `Assignment`,
`AiPolicy`, `Submission`, `AssignmentFeedback`, relación tarea↔chat). **Nada
implementado.** Es la fase más grande del dominio después de la analítica.

**Alcance.**

1. `Assignment` (tarea), `AiPolicy` (`ALLOWED`, `ALLOWED_RAG`, `ALLOWED_LOCAL`,
   `FORBIDDEN`), `Submission` (alumno), `AssignmentFeedback` (revisión del
   docente con las métricas del chat enlazado).
2. **Pantalla docente**: crear tarea, fecha de entrega, política de IA,
   enunciado, adjuntar material de la cátedra.
3. **Pantalla alumno**: ver tareas, **entregar** (texto + enlace a un hilo),
   ver la nota y el feedback.
4. **El chat enlazado**: el alumno puede abrir la tarea **desde** un hilo y
   viceversa; el docente ve el chat ligado **si tiene permiso de lectura**.
5. **Feedback**: el docente escribe una devolución y el sistema **adjunta las
   métricas deterministas** del chat (verificables, no opinions).

**Fuera de alcance.** Calificaciones con escala, rúbricas, plagio, entrega en
formato código (el formato texto + enlace), notificaciones push, y la
corrección automática por IA (¡no!).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F8.1 | Dado que el docente crea una tarea con `AiPolicy = FORBIDDEN`, cuando el alumno la abre, entonces la app **muestra la política** y no permite abrir chats desde esa tarea. |
| F8.2 | Dado que el alumno entrega con un enlace a un hilo, cuando el docente abre la entrega, entonces ve el texto de la entrega **y** las métricas del hilo enlazado (sólo si su visibilidad lo permite; si no, ve sólo los números agregados). |
| F8.3 | Dada una fecha de entrega vencida, cuando el alumno intenta entregar, entonces la app lo impide con un mensaje en español y **no** escribe la entrega en disco. |
| F8.4 | La nota y el feedback del docente se guardan, se ven en la pantalla del alumno y persisten al reabrir la app. |
| F8.5 | El `AccessLog` registra la lectura del chat enlazado por parte del docente (mismo mecanismo que F7). |
| F8.6 | Con `AiPolicy = ALLOWED_RAG`, el alumno puede abrir el chat con la materia y el sistema **marca** en el hilo que estaba en modo "tarea con RAG permitido" (para que el docente pueda auditar la coherencia). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **12 dp** | F2, F3, F6, F7 | **Scope creep**: `domain/06` es enorme (1 583 líneas de diseño con flujos, relaciones y casos borde). **Mitigación:** en el hito 1 se implementan **sólo** `Assignment`, `Submission` y `AssignmentFeedback` con **un** tipo de entrega (texto + enlace a chat). Lo demás (rúbricas, calificaciones, tipos de entrega múltiples, `AiPolicy` completo) es **hito 2** y queda anotado como deuda. |

---

### F9 — Sincronización y Kitoru-Server (lado cliente)

**Objetivo.** Kitoru habla con el **Kitoru-Server**: pide keys, **baja chats**,
reporta uso. Y — si el servidor es opcional — Kitoru **funciona igual sin él**.

**Estado real.** El contrato **ya está escrito** (`platform/03-kitoru-server-api.md`,
2 700+ líneas: API `/v1`, tokens `ktor_<kid>_<secreto>`, `apiVersion`, errores
tipados, `expiresAt ≤ endsAt` de la cursada, chats por *pull*, resolución de
visibilidad **en el servidor**). **El cliente no existe.** El
**Kitoru-Server es otro repo**, con su propio ritmo (riesgo §6.2).

**Alcance (sólo el lado Kitoru).**

1. `:llm-api`: `KitoruServerClient` (los 15 métodos de `platform/03` §14.1),
   `KitoruToken` (parseo y validación del token), `KitoruApiException`
   (status + code + mensaje en español + `retryable`), `KitoruModels`.
2. `OpenAiCompatibleGateway` acepta **cabeceras extra** (`X-Kitoru-*`) sin
   romper el uso contra llama.cpp/Ollama.
3. **Descarga de chats** (`GET /v1/chats?afterSeq=`) con cursor persistente
   (`lastSeq` por curso) y **resolución local de visibilidad** (el servidor ya
   la aplica; el cliente **también**, por si acaso).
4. **Emisión/revocación de keys** desde la pantalla del docente (la parte de
   F5 que quedó deshabilitada).
5. **Cobertura** (`coverage`) para el panel: cuántos alumnos usaron la IA de la
   institución y cuántos declararon IA local — **con el lenguaje honesto de
   `platform/03` D7**: "no usó la IA de la institución" (evidencia dura) vs
   "declará que usa IA local" (evidencia blanda).
6. **Contrato cliente**: tests contra un **servidor falso** (Ktor test host o
   fixtures grabados) — no contra el server real.

**Fuera de alcance.** Implementar el servidor (es otro repo), el transporte
push (el docente hace *pull*), el hosting de materiales (§7.2), la
autenticación institucional del docente (§7.8), y la sincronización de la
**configuración de la cátedra** hacia el alumno (sigue siendo la decisión
abierta §7.2 — **es el hueco más grande de F9**).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F9.1 | `KitoruToken.parse("ktor_<kid>_<secret>")` acepta un token válido con `kid` en base32 y **rechaza** (devuelve `null`) un token con longitud, alfabeto o prefijo incorrectos — tests con una tabla de 12 casos. |
| F9.2 | Emitir una key contra el server falso devuelve `expiresAt` **≤ `endsAt` de la cursada**; el cliente **rechaza** una key que venza después (fail-fast en español). |
| F9.3 | `fetchChats` pagina correctamente: con 250 chats y `limit=100` hace 3 llamadas y el cursor final es el `seq` del último chat. |
| F9.4 | Un chat de un alumno con `METRICS_ONLY` **nunca** llega al cliente: si el server falso lo devuelve igual, el cliente lo **descarta** y registra el evento (test). |
| F9.5 | El cliente distingue `404 key_not_found` de `429 quota_exceeded` de `503` y produce **tres mensajes distintos en español** (`LlmErrorMessages.describe`). |
| F9.6 | `apiVersion` del servidor se **verifica** en cada llamada; si es mayor que la soportada, el cliente avisa "El servidor es más nuevo que esta app" y sigue funcionando en modo lectura. |
| F9.7 | **Kitoru funciona entero sin el servidor**: con el server falso caído, la app sigue arrancando, el alumno chatea con la key pegada a mano y el docente ve sólo lo local (test de integración: server caído → la app no se cae). |
| F9.8 | El panel de cobertura muestra los dos enunciados honestos y **nunca** afirma "este alumno no usó IA" (test sobre el texto renderizado). |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **8 dp** | F1, F5, F7, `platform/03` | **El server llega tarde o con otro ritmo.** **Mitigación:** (a) el cliente se implementa **contra un server falso**, no contra el real: la especificación es el contrato; (b) **toda** la app funciona sin server (F9.7 es un criterio de aceptación, no una aspiración); (c) la emisión de keys queda **deshabilitada con motivo** hasta que el server exista; (d) el trabajo del server se trackea en **su propio repo** con los mismos números de fase, sin bloquear el calendario de Kitoru. |

---

### F10 — IA local en escritorio

**Objetivo.** Que el alumno pueda usar **un modelo en su propia máquina**, sin
internet y sin que la institución pague un token.

**Estado real.** En `:llm-api` ya está `LocalModelService` con
`expect/actual`: **desktop** real (arranca `llama-server`), **Android stub**.
En FunshiEngineAI existe el `HuggingFaceDownloader` (con reanudación) y el
supervisor de `llama-server`, **portables**.

**Alcance.**

1. **Descarga del GGUF** desde Hugging Face: barra de progreso, **reanudación**
   si se corta, verificación de tamaño, y el modelo queda en `modelsDir`.
2. **Arranque y supervisión de `llama-server`**: proceso hijo, puerto, logs,
   apagado limpio, reintento, y **detección de que no está instalado** →
   fail-fast en español con la guía de instalación.
3. **UI local**: elegir modelo, ver el estado (descargando / arrancando /
   listo / error), y el chat usando el gateway local (`OpenAiCompatibleGateway`
   contra `http://127.0.0.1:<port>`) en **modo prompt** para tool-calling
   (como en FunshiEngineAI: el catálogo va en el system prompt).
4. **Declaración de modo**: cuando el alumno usa IA local, la app lo **declara**
   en su inscripción (o en el paquete `.kitoru`), y la UI le explica **qué va a
   ver el docente**: "Con IA local, el docente ve tus métricas pero no puede
   leer este chat, salvo que lo compartas".
5. **Fallback**: si el modelo local falla, el chat ofrece volver al modelo de la
   cátedra sin perder el hilo.

**Fuera de alcance.** Compilar llama.cpp desde cero (se usa el binario
precompilado; la compilación se documenta aparte), la inferencia local en
Android ((§4, y §6.1), y el modo local **obligatorio** por parte de la cátedra.

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F10.1 | Dada una descarga interrumpida al 40 %, cuando se reanuda, entonces **continúa** desde el offset (no desde 0) y el archivo final tiene el tamaño esperado y su hash coincide con el del servidor. |
| F10.2 | Si `llama-server` no está en el PATH, cuando el alumno tries a arrancar el modelo local, entonces el error dice **"No encontré llama-server"** con la guía de instalación, y la app **no** se cae. |
| F10.3 | Con un modelo local cargado, cuando el alumno chatea, entonces el texto sale del gateway local (test: la URL del gateway es `http://127.0.0.1:<port>`) y el **tool-calling por prompt** funciona (el `AgentLoop` detecta el bloque ` ```tool_call `). |
| F10.4 | Con un modelo local en marcha, cuando el proceso se cuelga, entonces la app lo detecta en ≤ 10 s, lo reinicia una vez y avisa; si vuelve a fallar, vuelve al modelo de la cátedra con un mensaje claro. |
| F10.5 | Cuando el alumno usa IA local, entonces su inscripción queda marcada como tal y la UI del alumno muestra **literalmente** qué ve el docente ("no puede leer este chat"). |
| F10.6 | Al cerrar la app, el proceso `llama-server` **se termina** (no quedan procesos zombis): test que verifica que el PID no existe tras el cierre. |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **12 dp** | F3, F5 | **La inferencia local es lenta y frágil** (el modelo no entra, la GPU no existe, la RAM no alcanza). **Mitigación:** (a) es **opcional** en todo el flujo: el chat con modelo de la cátedra es siempre el camino por defecto; (b) requisitos mínimos **declarados antes** de descargar (espacio, RAM, GPU); (c) el supervisor viene **portado** de FunshiEngineAI, que ya lo resolvió; (d) el fallback al modelo remoto es automático y sin perder el hilo. |

---

### F11 — Android

**Objetivo.** La app en Android: mismo código, pantalla táctil, cámara roll,
y **el mismo producto** para el alumno.

**Estado real.** `:android` **no existe** como módulo (está en
`settings.gradle.kts` sin directorio). `LocalModelService` en Android es un stub
que lanza `LlmException`. `minSdk = 24`, `compileSdk = 35`.

**Alcance.**

1. Módulo `:android` (`com.android.application`), launcher, ícono, y
   `MainActivity` con `setContent { App() }`.
2. **Adaptación de pantallas**: `ModalNavigationDrawer` en compacto (según
   `teacher/01` D3), chat con teclado virtual, listas con scroll anidado
   razonable.
3. **Archivos**: SAF (`ACTION_OPEN_DOCUMENT`) para elegir material; `FileProvider`
   + `ACTION_VIEW` para abrir; `ACTION_CREATE_DOCUMENT` para guardar.
4. **Permisos**: sólo los de Android (notificaciones, si las hubiera);
   `minSdk 24` sin permisos peligrosos salvo los inevitables.
5. **Ciclo de vida**: la app sobrevive a rotación y a proceso muerto (el estado
   del chat se rehidrata desde los repositorios).
6. **Tests**: `androidUnitTest` (JVM, sin emulador) para la lógica; la UI
   Android se valida a mano o con `compose.uiTest` en instrumentación (fuera del
   hito 1).
7. **Sin inferencia local** en este hito (§4): la opción local del alumno está
   oculta en Android y, si se fuerza, el mensaje es explícito.

**Fuera de alcance.** Inferencia local en Android (§4, §6.1), notificaciones
push, widgets, Wear, tablet UI dedicada, Play Store (publicación: hito 2).

**Criterios de aceptación.**

| # | Criterio verificable |
|---|---|
| F11.1 | `./gradlew :android:assembleDebug` produce un APK instalable; la app abre y muestra la home del alumno en un dispositivo o emulador con API 24 y con API 35. |
| F11.2 | En un teléfono de 360 dp de ancho, la navegación es de drawer (no rail) y **no** hay desbordes horizontales (test de UI o captura verificada). |
| F11.3 | El alumno puede **unirse** a una cátedra con el código, chatear con el modelo alojado y ver el distintivo de citas; los mensajes **persisten** al cerrar la app. |
| F11.4 | En Android, elegir un PDF para un material usa SAF y **no** pide permiso de archivos; abrir un material usa `FileProvider` y la app del sistema lo abre sin crashear. |
| F11.5 | La app **no** pide permisos que no necesita: un test automatizado sobre el manifiesto verifica que no hay `MANAGE_EXTERNAL_STORAGE` ni `READ_EXTERNAL_STORAGE`. |
| F11.6 | Con la app en background y en foreground, el hilo en curso **no** se duplica ni se pierde (verificable a mano en 5 minutos de prueba). |
| F11.7 | Con `allowLocalModels = true` en la cátedra, en Android la opción local está **oculta** y, si se fuerza, el mensaje es "La IA local todavía no está disponible en Android". |

**Esfuerzo / dependencias / riesgo.**

| Esfuerzo | Dependencias | Riesgo principal y mitigación |
|---|---|---|
| **14 dp** | F1, F2, F3, F9 (para contenido del server) | **Android es un sistema operativo distinto**: permisos, SAF, ciclo de vida, teclado. Cada hueco puede comer días. **Mitigación:** (a) Android entra **al final**, cuando la lógica ya está probada en desktop — el `commonMain` ya está; (b) el `minSdk 24` evita APIs modernas; (c) se **excluye** la inferencia local del hito (la mayor fuente de riesgo); (d) tests `androidUnitTest` (JVM) para todo lo que no sea UI. |

---

## 3. Los hitos verificables (aceptación end-to-end)

Cada hito es un guion ejecutable a mano **y** cubierto por al menos un test
automatizado. Se escriben como: **dado / cuando / entonces**.

### H1 — "El docente sube un PDF, el alumno pregunta, y la respuesta cita la fuente"

| | |
|---|---|
| **Dado** | La cátedra `IA-2026-1` con el material `Apuntes-1.pdf` (con capa de texto) en estado `READY`, y un alumno `Ana` inscrito |
| **Cuando** | Ana pregunta *"¿qué dice el material sobre el gradiente?"* |
| **Entonces** | La respuesta trae al menos un marcador `[1]`; el distintivo de la burbuja dice exactamente **`Citas verificadas (2)`** (o el número que corresponda a las citas de esa respuesta); el panel de fuentes lista el fragmento del PDF; y el docente, en su explorador, ve **la misma respuesta con el mismo veredicto** |
| **Verificación** | Test de integración `:backend`+`:core` (`MaterialToChatPipelineTest`) + guion manual `scripts/H1-material-y-chat.md` |

### H2 — "La cátedra bloquea la key y el alumno no puede cambiarla"

| | |
|---|---|
| **Dado** | `ModelPolicy` de la cátedra con `lockedFields = {API_KEY, TEMPERATURE}` |
| **Cuando** | El alumno abre su configuración, intenta cambiar la key y la temperatura, y envía un mensaje |
| **Entonces** | Los dos campos están deshabilitados con el motivo; el mensaje sale con **la key y la temperatura de la cátedra**; la frase resumen es idéntica en las dos pantallas |
| **Verificación** | Test de `resolveFor` (`:core`) + test de UI de las dos pantallas + guion `scripts/H2-bloqueos.md` |

### H3 — "El alumno usa IA local: el docente ve métricas pero NO el texto"

| | |
|---|---|
| **Dado** | Ana usa IA local (declarado en su inscripción) y su `visibility` es `METRICS_ONLY` |
| **Cuando** | El docente busca "gradiente" en el explorador de chats |
| **Entonces** | La búsqueda **no** encuentra sus chats; la columna de hilos **no** muestra títulos; el aviso dice "Este alumno usa IA local y no compartió sus conversaciones"; el panel de analítica **sí** lo cuenta en los números agregados; y un `AccessLog` registra la consulta |
| **Verificación** | Test con un `MessageRepository` fake que **cuenta** las llamadas y afirma 0 (`teacher/07-chats.md` criterio 2) + guion `scripts/H3-visibilidad.md` |

### H4 — "La analítica dice un número exacto y el mismo que la calculadora"

| | |
|---|---|
| **Dado** | El dataset de 50 mensajes de la cátedra |
| **Cuando** | El docente abre "Calidad de las respuestas" |
| **Entonces** | El encabezado dice la frase con los números **del dataset**; la suma `grounded + problematic + uncited == answers`; el material con peor tasa aparece arriba en la tabla; y un alumno con `visibility = NONE` **no aparece en ningún lado** |
| **Verificación** | Test golden en `:core` (`TemporalAnalyzerTest`, `QualityAnalyzerTest`) + guion `scripts/H4-analitica.md` |

### H5 — "El docente ve sus propios accesos en el log y el alumno también"

| | |
|---|---|
| **Dado** | Ana, y el docente Juan que abre 3 chats de Ana |
| **Cuando** | Ambos van a su pantalla de auditoría |
| **Entonces** | Juan ve 3 entradas `VIEW_TEXT` (2 de ellas coalescidas si fueron en menos de 5 min); Ana ve **las mismas** entradas desde su lado; y el `AccessLog` del alumno es el mismo que el del docente |
| **Verificación** | Test de `AccessLog` (`:core`) + guion `scripts/H5-auditoria.md` |

### H6 — "El alumno entrega una tarea con su chat y el docente evalúa con números"

| | |
|---|---|
| **Dado** | La tarea `TP-1` con `AiPolicy = ALLOWED_RAG` y fecha de entrega vigente |
| **Cuando** | Ana entrega un texto y enlaza su hilo "duda de parcial"; el docente abre la entrega |
| **Entonces** | El docente ve el texto de la entrega **y** las métricas deterministas del hilo (citas verificadas / sin respaldo / latencia); el `AccessLog` registra la lectura del chat; y si la entrega llega después de la fecha, **no** se guarda |
| **Verificación** | Test de `Submission` + `AssignmentFeedback` (`:core`) + guion `scripts/H6-tarea.md` |

### H7 — "El docente recibe los chats del servidor y el alumno no pierde nada"

| | |
|---|---|
| **Dado** | Ana usa la IA de la institución (key del Kitoru-Server) y el server tiene 250 chats de la cátedra |
| **Cuando** | El docente entra en "Chats" y tira para actualizar |
| **Entonces** | Baja los chats paginados (3 llamadas, cursor final correcto); los chats de alumnos `METRICS_ONLY` **no** llegan; el `coverage` informa "no usó la IA de la institución" **sin** afirmar "no usó IA"; y si el server está caído, la app **sigue funcionando** con los datos locales |
| **Verificación** | Test de contrato contra el server falso (`:llm-api`) + guion `scripts/H7-sync.md` |

---

## 4. Fuera de alcance (explícito) y cuándo vuelve

| # | Qué queda afuera | Por qué | Cuándo vuelve |
|---|---|---|---|
| 1 | **MCP** (cliente y servidores) | FunshiEngineAI ya lo tiene y funciona; en Kitoru no hay todavía un caso de uso que lo justifique (el corpus es la cátedra, no el mundo). Es una superficie de ataque grande. | Hito 2 (2027-H2), **si** un docente pide conectar un Moodle/Slack. Se porta el cliente tal cual (el seam `McpConnector` ya está diseñado). |
| 2 | **LaTeX** (math en el chat, compilación a PDF) | Es una línea de trabajo completa (JLaTeXMath + `latex_compile` + detección de motores) y **no es el problema** que Kitoru resuelve. El texto plano con `$…$` se muestra tal cual. | Hito 2. Se porta de FunshiEngineAI (que ya lo tiene implementado y testeado). |
| 3 | **OCR** (PDF escaneado) | Es un proyecto aparte (Tesseract nativo: NDK, modelos de idioma, memoria, calidad). | Hito 2/3, sólo si hay demanda real. Mientras tanto: `NEEDS_REVIEW` + "pegá el texto". |
| 4 | **IA local en Android** (llama.cpp en NDK, ABIs, temperatura, batería) | Es un proyecto entero (ver §6.1). | **F12**, después de F11, como proyecto separado con su propio presupuesto. |
| 5 | **Multi-cátedra sincronizada** (un curso en varios dispositivos) | El modelo de datos ya soporta varias cátedras, pero la **sincronización** es del server. | Con el Kitoru-Server (F9) y su endpoint de `upsertCourse`/`upsertEnrollments`. |
| 6 | **El Kitoru-Server aloja los materiales** | Ver §7.2: decisión abierta. El server es "el modelo y las keys"; los materiales son del curso y hoy viven en el disco del docente. | Cuando el server tenga almacenamiento (F9+ o v2 del server). |
| 7 | **Búsqueda web / `web_fetch` en el chat del alumno** | En una cátedra, el modelo debe responder **con el material de la cátedra**, no con internet: si no, el chat deja de ser un tutor de la materia y pasa a ser "ChatGPT con_PLUS". | Sólo como opt-in **explícito** del docente, y con el mismo régimen de permisos por categoría. |
| 8 | **Voz** (dictado, TTS) | Es una línea aparte (STT/TTS remoto o whisper.cpp) y no aporta al problema central. | Hito 3. |
| 9 | **Notas, foros, encuestas, asistencia,calificaciones con escala, plagio** | Cada uno es una app. Kitoru es "material + chat + analítica + tareas". | Hito 2/3, uno por vez. |
| 10 | **Calendario académico importado (`.ics`)** | Una tabla de feriados mal cargada es peor que ninguna: el docente marca 12 fechas a mano. | Sólo `.ics` si hay demanda; el milestone 1 es carga manual (ya diseñado en `analytics/07` §9). |
| 11 | **Empaquetado multiplataforma de escritorio** (Linux, macOS) | El target de lanzamiento es Windows; `compose.desktop` ya declara dmg/msi/deb pero no se prueban. | Cuando haya un usuario de Linux/macOS que lo pida. |
| 12 | **Publicación en tiendas** (Play Store,签名) | Firma, política de privacidad, cuentas de desarrollador. | Hito 2 de Android. |
| 13 | **Multi-perfil en un mismo dispositivo** | Está diseñado (`domain/09` §9) pero no es necesario para el piloto. | Cuando un dispositivo sea compartido (laboratorio de una facultad). |
| 14 | **Comparar alumnos entre sí** (rankings, score, "el peor alumno") | Es el camino corto a que la herramienta se queme (§6.4). | **Nunca** sin una decisión de producto explícita y un diseño de privacidad nuevo. |

---

## 5. La línea de tiempo

### 5.1 Supuestos

- **Una persona**, 4 días por semana (≈ 4 dp/semana), sin holidays.
- Los documentos de diseño que faltan (`platform/02-persistencia.md`, el
  contrato de distribución de la cátedra al alumno) se escriben **durante F1**.
- Sin interrupciones de más de una semana. Si hay una, la línea se corre.
- Las fechas son **tentativas**: se recalculan al cerrar cada fase.

### 5.2 Calendario

| Fase | dp | Desde | Hasta | Hito al cerrar |
|---|---:|---|---|---|
| F0 | 3 | 2026-10-22 | 2026-10-28 | La app abre y los tests pasan |
| F1 | 6 | 2026-10-29 | 2026-11-11 | Los datos sobreviven a cerrar y reabrir |
| F2 | 10 | 2026-11-12 | 2026-12-02 | Hay un shell navegable con rol |
| F3 | 12 | 2026-12-03 | 2026-12-23 | El alumno chatea (sin material todavía) |
| **F4** | 18 | 2026-12-24 | **2027-01-27** | **H1: el material entra al chat** |
| **F5** | 10 | 2027-01-28 | **2027-02-17** | **H2: la cátedra fija la base y bloquea** |
| F6 | 14 | 2027-02-18 | 2027-03-10 | H4: la analítica dice números exactos |
| **F7** | 10 | 2027-03-11 | **2027-03-31** | **H3/H5: el docente ve los chats, con límites** |
| F8 | 12 | 2027-04-01 | 2027-04-21 | H6: tareas y entregas |
| F9 | 8 | 2027-04-22 | 2027-05-05 | H7: sincronización con el server |
| F10 | 12 | 2027-05-06 | 2027-05-26 | IA local en escritorio |
| F11 | 14 | 2027-05-27 | 2027-06-16 | La app anda en Android |

```
2026                          2027
oct nov dic │ ene feb mar abr may jun
  F0 F1 F2 │F3   F4   F5 F6 F7 F8 F9 F10 F11
      ▲        ▲       ▲       ▲           ▲
      │        │       │       │           │
      │        │       │       │           └─ Android (y el server ya es opcional)
      │        │       │       └─ el docente ve los chats (fin de F7): **piloto**
      │        │       └─ el docente configura la cátedra (fin de F5)
      │        └─ el alumno usa el chat con el material (fin de F4): **primer uso real**
      └─ el esqueleto corre (fin de F2)
```

### 5.3 Las dos preguntas

> **¿Cuándo es usable la primera vez por un alumno?**
> **2027-01-27** (fin de F4): se inscribe con un código, chatea contra el modelo
> de la cátedra, y las respuestas citan el material con el distintivo
> "Citas verificadas (N)". Antes de eso (fin de F3, 2026-12-23) puede chatear,
> pero **sin corpus de la cátedra**: es una prueba de engranajes, no un producto.
>
> **¿Cuándo es usable la primera vez por un docente?**
> **2027-03-31** (fin de F7): crea la cátedra, sube materiales, fija la
> configuración base y bloquea lo que quiere, y ve la analítica y los chats de
> sus alumnos **respetando la visibilidad**. Antes de eso (fin de F5,
> 2027-02-17) puede configurar y subir material, pero todavía **no ve nada de
> lo que hacen sus alumnos** — que es la mitad de su valor.
>
> **El piloto real** (un docente, sus alumnos, la cursada de verdad) es
> **F9, 2027-05-05**, con el Kitoru-Server **opcional**: si el server no está,
> el docente reparte las keys a mano y los alumnos importan el paquete de la
> cátedra.

### 5.4 Si hace falta ir más rápido: el camino mínimo del piloto

**F0 + F1 + F2 + F3 + F4a + F5 = 45 dp ≈ 11 semanas → 2027-01-18**
(feriados del 25/12 y el 6/1 descontados).

- El docente crea la cátedra, sube **material de texto** (`.md`, `.txt`, PDF
  con capa de texto si ya está el extractor), fija la configuración.
- El alumno recibe el **paquete de la cátedra** (ver §7.2), se inscribe, chat.
- **Sin** analítica, **sin** explorador de chats, **sin** server, **sin**
  Android, **sin** IA local.
- Sirve para **validar la tesis del producto** (el alumno pregunta y la cita
  sale respaldada) con usuarios reales, que es lo que hay que saber antes de
  invertir 14 dp en la analítica.

---

## 6. Los riesgos del proyecto

### 6.1 La inferencia local en Android es un proyecto entero

**Riesgo.** Compilar llama.cpp para Android (NDK, 4 ABIs, modelo por
dispositivo, memoria y temperatura), empaquetar el GGUF (cientos de MB), y
mantenerlo vivo, es un proyecto **de la misma magnitud** que F11 completa.
Si entra en el cronograma de Android, Android se multiplica por dos.

**Mitigación de diseño.**
1. **Decisión de producto: la inferencia local en Android es una fase
   aparte (F12)**, con su propio presupuesto y su propio repo de trabajo.
   No es "una más" de F11.
2. **El seam ya existe**: `expect fun createLocalModelService()` en
   `:llm-api`. La UI **no cambia** cuando llegue: es un `actual` más.
3. **En Android, la opción local está oculta** (no deshabilitada), y si se
   fuerza, el error es explícito: *"La IA local todavía no está disponible en
   Android"*.
4. **La declaración de modo es honesta**: el sistema nunca afirma que un alumno
   "no usó IA" (regla D7 del contrato del server): sólo puede decir "**no usó
   la IA de la institución**" y "**declaró que usa IA local**".
5. **El riesgo de la alternativa (obligar a la IA alojada) es peor**: si la
   institución obliga, el alumno delega su privacidad y el docente recibe
   todo — eso destruye la relación. Kitoru **no** obliga: la cátedra puede
   deshabilitar la IA local (`allowLocalModels = false`), y esa es una
   decisión **explícita y visible** del docente, no un default.

### 6.2 El Kitoru-Server es otro repo, con otro ritmo

**Riesgo.** El server es una app de escritorio (descarga de modelo, supervisor
de llama-server, emisión de keys, outbox de chats) **en otro repo**, con
prioridades propias. Si Kitoru depende de él para el piloto, el piloto se
atrasa a la velocidad del otro repo.

**Mitigación de diseño.**
1. **El server es opcional en todo el flujo**: el docente pega la key a mano
   (modo v1) y el alumno importa el paquete de la cátedra. **Criterio de
   aceptación explícito** (F9.7): con el server caído, Kitoru funciona entero.
2. **El cliente se construye contra un server falso** (Ktor test host o
   fixtures grabados), no contra el real: el contrato escrito es la
   especificación.
3. **El trabajo del server se trackea en su propio repo** con los mismos
   números de fase (S1…S6), y la línea de tiempo de Kitoru **no lo espera**.
4. **La resolución de la visibilidad ocurre en los dos lados** (servidor y
   cliente): si el server falla en filtrar, el cliente **vuelve a filtrar**.

### 6.3 Si el alumno usa IA local, el docente no ve los chats (tensión de producto)

**Riesgo.** Es el corazón del producto: el docente quiere ver qué Ask Copilot
aprende de sus alumnos; el alumno, si usa su propio modelo, no deja rastro. Si
Kitoru resuelve mal esto, o el docente se siente engañado ("¿por qué no veo
nada?") o el alumno se siente vigilado ("me están mirando").

**Mitigación de diseño (cuatro piezas).**
1. **Transparencia en el momento de la decisión**: cuando el alumno elige IA
   local, la app dice **literalmente** qué pasa: "El docente ve tus métricas
   (cuántas preguntas, qué materiales usaste, si las citas están respaldadas)
   pero **no puede leer este chat**, salvo que lo compartas".
2. **Evidencia de dos clases**: el panel del docente distingue
   *"no usó la IA de la institución"* (duro) de *"declará que usa IA local"*
   (blando). Nunca se afirma más de lo que se sabe.
3. **Consentimiento explícito y reversible**: el alumno puede exportar un
   paquete `.kitoru` con sus chats e importarlo en el curso del docente (queda
   auditado como `IMPORT`, y el alumno ve ese acceso en su propia auditoría).
   Importar es **opt-in del alumno**, no automático.
4. **La cátedra decide, y se lo dice**: `allowLocalModels = false` desactiva la
   opción local para los alumnos de esa materia — y la app le **explica al
   alumno por qué** (mensaje editable por el docente), sin degradar la
   experiencia: sigue habiendo chat con el modelo de la cátedra.
5. **El valor del producto no depende de leer el texto**: el paquete central de
   Kitoru es *"la calidad de las respuestas de la IA sobre mi material"*, que
   se puede medir **sin** leer los chats privados. El texto es una **muestra**,
   no el producto.

### 6.4 La analítica puede construed como vigilancia y quemarla

**Riesgo.** Una pantalla que muestra "qué hace cada alumno" en un contexto
universitario es un riesgo reputacional y deethical serious. Si los docentes
la usan para calificar o para perseguir, los alumnos lo detectan (en una
universidad, todos hablan) y la herramienta se quema en la primera cátedra.

**Mitigación de diseño.**
1. **El encuadre de la primera pantalla es "calidad de las respuestas", no
   "alumnos"**: lo que se mide es **la IA**, no la persona. La analítica
   responde *"¿mi material está bien? ¿el modelo alucina?"*.
2. **Prohibido el ranking y el score individual**: no hay "orden de alumnos",
   ni "actividad del alumno X", ni/exportes por persona. El mínimo de cohorte
   es 5 personas: un porcentaje sobre 2 personas no es un dato, es el dato de
   una persona.
3. **El texto de los alumnos es de lectura, no de análisis**: el panel no
   busca palabras ni sentiments. Y cuando se lee, queda auditado (con acceso
   que el alumno puede ver).
4. **El alumno tiene su propio panel**: "qué ve el docente de mí" y "auditoría
   de mis chats". El secreto es incompatible con la confianza.
5. **La app no moraliza, pero sí informa**: la primera vez que el docente entra
   a la analítica, un panel explica el alcance de los datos y qué **no** se
   hace con ellos (no hay calificación automática, no hay detección de plagio).
6. **El diseño de los datos refuerza la regla**: los agregados se calculan en
   `:core`, con funciones **puras y testeadas**, y el filtro de visibilidad
   está en el **analizador** — no puede "escaparse" con un error de UI.

---

## 7. Decisiones pendientes (hay que responder antes de seguir)

> Cada una tiene una **fecha de cierre** propuesta. Sin decisión, la fase
> afectada se frena o se hace con un default explícito y anotado.

| # | Pregunta | Recomendación | Dueño | Cierra antes de |
|---|---|---|---|---|
| 1 | **¿SQLite o JSON?** | **JSON** (sharded por hilo) en el hito 1. SQLite (SQLDelight) sólo si: > 50 000 mensajes/curso **o** > 200 materiales/curso **o** consultas que el JSON no puede responder. La decisión **congela el esquema**: los repositorios ya son interfaces, así que cambiar después es acotado | dev | **fin de F1** |
| 2 | **¿El Kitoru-Server aloja los materiales?** | **No, en el hito 1.** El server aloja **modelo + keys + relay de chats**; los materiales son de la cátedra y viven en el disco del docente. El reparto al alumno es un **paquete de cátedra** (export/import, tipo `.kitoru`) hasta que el server tenga almacenamiento. **Es el hueco más grande de F9**: sin esto, el alumno no puede tener la config de la cátedra sin internet | dev + teacher | **inicio de F9** |
| 3 | **¿Cómo llega la configuración de la cátedra al alumno?** | **Paquete de cátedra** (`.kitoru`): el docente lo exporta (config + política + materiales + prompt) y el alumno lo importa. Con el server: `upsertCourse` + `upsertEnrollments` y **pull** del alumno. Mientras tanto, el alumno **pega la key a mano** | dev | **inicio de F4** |
| 4 | **¿Dónde se guarda la API key?** | En el `dataDir` de la app, en claro, con la UI **enmascarada** y un "olvidar" explícito. Es una key que **vence con la cursada**; cifrarla con el keystore de Android agrega fricción y no agrega seguridad real. La key **nunca** se loguea | dev | **fin de F5** |
| 5 | **¿El alumno ve la analítica de su propia cátedra?** | **Sí, agregada y sin datos de otros** (opt-in). Le da un motivo para seguir usando la herramienta y refuerza la confianza. Nunca por persona | dev + teacher | **inicio de F6** |
| 6 | **¿Visibilidad por defecto de un alumno en modo LOCAL?** | **`METRICS_ONLY`** como default de la cátedra, **ajustable por inscripción** por el docente. `NONE` es posible pero se deja como decisión explícita | teacher | **inicio de F7** |
| 7 | **¿Qué librería de extracción para PDF/DOCX/PPTX?** | **PDFBox-Android** (`com.tom-roush`) para PDF en ambas plataformas, y un **extractor OOXML propio y mínimo** (ZIP + XML) para DOCX/PPTX: meter Apache POI no funciona en Android. Si PDFBox-Android resulta problemático, el fallback es **el docente pega el texto** (`NEEDS_REVIEW`) | dev | **inicio de F4b** |
| 8 | **¿Identidad institucional (login de la universidad) o sólo local?** | **Sólo local** (perfil + código de cátedra) en el hito 1. El login institucional exige SSO y acuerdos con la universidad: es un proyecto paralelo. El multi-dispositivo llega con el server | dev | **inicio de F9** |
| 9 | **¿El docente puede escribir en el hilo del alumno?** | **Sí**, como un mensaje con `role = "teacher"` (propuesto en `teacher/09` §6), al final de F7. Es lo que hace que la herramienta sea un **espacio de cátedra** y no un canal anónimo | teacher | **fin de F7** |
| 10 | **¿Cuánto tiempo se guardan los chats?** | Hasta el fin de la cursada + **90 días**; después, se borran (o se anonimizan). El alumno puede borrar sus propios chats en cualquier momento y la baja se propaga. El docente ve "la retención vence en X" en la UI | dev + teacher | **inicio de F6** |
| 11 | **¿El material de la cátedra se puede ocultar a **algunos** alumnos?** | **No** en el hito 1: `Material.visible` es por material y para toda la cátedra. La visibilidad por alumno es para los **chats**, no para el material | teacher | **fin de F4** |
| 12 | **¿Se sube el modelo a GitHub?** | Sí, pero **sin** secretos: `.gitignore` ya cubre `.gradle/`, `build/`, `local.properties`. El primer commit de F0 es el que deja el repo en condiciones de subirse | dev | **fin de F0** |

---

## 8. Checklist de la hoja de ruta (para el README del repo)

```markdown
- [x] **F0 — Base multiplatform**: `:core`, `:llm-api`, `:backend` compilan en
      desktop y Android; tests portados de FunshiEngineAI.
- [ ] **F0 (cierre)** — app desktop que abre, `:android` que compila, CI verde,
      primer commit, tests de `:core`.
- [ ] **F1 — Persistencia y dominio completo**: esquema definitivo (JSON
      sharded), `InMemoryFileSystem`, migraciones, escritura atómica.
- [ ] **F2 — UI base y roles**: selector de rol, home del alumno, shell del
      docente, tema, navegación adaptativa.
- [ ] **F3 — Chat del alumno con RAG**: streaming, markdown, fuentes,
      distintivo de citas, herramientas y permisos.
- [ ] **F4 — Materiales de la cátedra**: subida, extracción, indexación y el
      chat usando ese corpus.
- [ ] **F5 — Configuración del modelo**: base de la cátedra, libertad del
      alumno, bloqueos.
- [ ] **F6 — Analítica del docente**: panel con las métricas deterministas.
- [ ] **F7 — Explorador de chats**: ver los chats respetando la visibilidad.
- [ ] **F8 — Tareas y entregas**.
- [ ] **F9 — Sincronización y Kitoru-Server**: contrato y cliente.
- [ ] **F10 — IA local en escritorio**: descarga del GGUF y llama.cpp.
- [ ] **F11 — Android**: app, SAF, adaptación de pantallas.
```

---

## 9. Trazabilidad: fase ↔ documento de diseño

| Fase | Documentos de diseño que la gobiernan | Estado del documento |
|---|---|---|
| F0 | `docs/design/CONTEXTO.md` | ✅ |
| F1 | `docs/design/platform/02-persistencia.md` | ⬜ **falta escribirlo** (dueño: subagente de plataforma) |
| F2 | `docs/design/teacher/01-navegacion.md` | ✅ |
| F3 | `docs/design/domain/04-conversaciones.md`, `docs/design/student/*` (chat) | ⚠️ `domain/04` ✅; falta el doc del chat del alumno |
| F4 | `docs/design/teacher/03-materiales.md`, `docs/design/student/04-materiales.md` | ✅ ✅ |
| F5 | `docs/design/teacher/04-prompt.md`, `docs/design/teacher/05-config-modelo.md` | ✅ ✅ |
| F6 | `docs/design/analytics/07-temporal-cohortes.md`, `docs/design/teacher/09-calidad.md` | ✅ ✅ |
| F7 | `docs/design/teacher/07-chats.md`, `docs/design/domain/08-permisos.md` | ✅ ✅ |
| F8 | `docs/design/domain/06-tareas.md` | ✅ |
| F9 | `docs/design/platform/03-kitoru-server-api.md` | ✅ |
| F10 | (falta) doc de IA local en escritorio | ⬜ **falta escribirlo** |
| F11 | (falta) doc de Android | ⬜ **falta escribirlo** |
| transversal | `docs/design/domain/01-catedra.md`, `03-inscripciones.md`, `07-anuncios.md`, `09-identidad.md`, `10-taxonomia.md` | ✅ (fuera del hito 1: anuncios y taxonomía) |

---

*Fin de la hoja de ruta. Los criterios de aceptación son **verificables**:
cada uno nombra el test o el guion que lo comprueba. Una fase no se cierra
hasta que todos los suyos están en verde.*
