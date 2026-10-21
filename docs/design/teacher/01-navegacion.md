# Diseño de navegación y esqueleto de pantallas — Rol DOCENTE

> Subagente: *navegación del docente*.
> Documento de diseño. **No** contiene código de producción: sólo firmas,
> nombres de archivos y estructura.
> Referencias: `docs/design/CONTEXTO.md`, y los patrones de
> `FunshiEngineAI/frontend/src/main/kotlin/app/{NavigationRail,chat/ChatScreen,chat/ChatPane,chat/ChatSidebar,screens/*}.kt`.

---

## 0. Resumen ejecutivo (decisiones)

| # | Decisión | Motivo |
|---|---|---|
| D1 | El **rol no es un toggle global**, es un filtro sobre las inscripciones del usuario | `Enrollment` es por `(courseId, userId)` y lleva su propio `CourseRole`: un mismo perfil **puede ser docente en una cátedra y alumno en otra**. Un switch "Alumno/Docente" que reescriba el rol rompería ese modelo. |
| D2 | **Navegación primaria = rail lateral** (expandido o de iconos) | Hay 9 secciones y un flujo de trabajo "diseñar → medir". Un rail sostiene el contexto de la cátedra siempre visible. |
| D3 | **El rail NO se convierte en bottom bar en Android** | 9 secciones exceden el máximo recomendado (3–5) de una `NavigationBar`; obligaría a un ítem "Más" que duplicaría el drawer. Se usa `ModalNavigationDrawer` en compacto, que además es *el mismo componente* en Compose Multiplatform. |
| D4 | **Sin `navigation-compose`**: back stack manual | `androidx.navigation:navigation-compose` no es multiplatform en Compose 1.7.3 para desktop. Se implementa una pila propia (`TeacherNavState`), como el `ChatTree` de FunshiEngineAI pero lineal. |
| D5 | **ViewModel plano con `mutableStateOf`**, no `androidx.lifecycle.ViewModel` | Es el patrón de FunshiEngineAI (`ChatViewModel`) y funciona igual en `commonMain`: se instancia con `remember { }` en la raíz. La persistencia se delega a los repositorios de `:core`. |
| D6 | **`ViewModel` = estado + acciones; la lógica por área va en funciones de extensión** (`TeacherMaterials.kt`, `TeacherAnalytics.kt`, …) | Copia el patrón de `ChatLibrary.kt`/`ChatCatalog.kt` y evita un `ViewModel` de 800 líneas. |
| D7 | **El detalle va en un panel lateral**, no en navegación | Chat de alumno, material y ficha de alumno son *lectura de un elemento dentro de un contexto*, no destinos. En compacto pasan a ser pantalla completa con back. |
| D8 | La sección **Ajustes de la app es compartida** con el rol Alumno | Vive en `kitoru/ui/settings/` y el rail docente la muestra como última entrada, separada del resto. |

---

## 1. La pantalla raíz: selector de rol y de cátedra

### 1.1 El modelo real: el rol vive en la inscripción

El dominio ya lo define (`core/src/commonMain/kotlin/kitoru/domain/Roles.kt`):

```kotlin
enum class CourseRole(val label: String) {
    TITULAR("Profesor titular"),   // canDesign = true, canConfigureModel = true
    AYUDANTE("Ayudante"),         // canDesign = true, canConfigureModel = false
    ALUMNO("Alumno"),             // canDesign = false
}
data class Enrollment(id, courseId, userId, displayName, role, joinedAt, visibility)
```

**Respuesta directa:** *sí*, un perfil puede ser docente en una cátedra y alumno en otra. `Enrollment` es por par `(courseId, userId)`, así que el mismo `userId` puede tener:

```
Enrollment(userId="u1", courseId="IA-2026-1", role=TITULAR)
Enrollment(userId="u1", courseId="PROG-2026-1", role=ALUMNO)
```

El "modo" de la app **no** reescribe el rol: **filtra** la lista de cátedras por el tipo de rol que se quiere ver. El rol real de cada cátedra se muestra siempre con un badge (`Titular` / `Ayudante` / `Alumno`) y se lee de la inscripción, nunca del modo.

### 1.2 El modo de la app

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/AppMode.kt
enum class AppMode(val label: String) {
    ALUMNO("Alumno"),
    DOCENTE("Docente"),
}
```

Reglas:

- `AppMode.DOCENTE` ⇒ la lista de cátedras muestra sólo aquellas donde la inscripción `role.canDesign == true` (TITULAR o AYUDANTE).
- `AppMode.ALUMNO` ⇒ sólo aquellas donde `role == ALUMNO`.
- Una cátedra **nunca** aparece en los dos modos a la vez. Un docente titular no tiene vista "alumno" de su propia cátedra: no es un privilegio del rol, es que no existe inscripción `ALUMNO` ahí.
- El modo se persiste (`config/app-config.json` → `AppConfig.uiMode`) y se recuerda entre sesiones.
- Si el usuario es docente en una cátedra y alumno en otra, entra por defecto en `DOCENTE` (la carga de trabajo de diseño es la más pesada) y el selector queda a un clic.

### 1.3 La pantalla raíz: `RoleGateScreen`

```
KitoruApp
└── AppShell
    └── when (app.mode) {
        DOCENTE -> TeacherScreen(vm = teacherVm)
        ALUMNO  -> StudentScreen(vm = studentVm)   // otro subagente
    }
```

Antes de entrar al shell, `AppShell` resuelve el **estado de arranque**, que es una máquina de 4 estados (patrón `LoadingPane` de FunshiEngineAI):

| Estado | Qué se ve |
|---|---|
| `BOOTING` | `LoadingPane` con progreso por fases: "Leyendo perfil", "Cargando cátedras", "Cargando inscripciones" |
| `NO_PROFILE` | `ProfileSetupPane`: nombre + email → crea `UserProfile` local |
| `EMPTY` | `EmptyCoursesPane`: "No sos docente de ninguna cátedra todavía" + `Unirse con código` / `Crear cátedra` |
| `READY` | El shell completo |

### 1.4 Cómo se cambia de rol: `RoleSwitcher`

Componente **compartido** (`kitoru/ui/shell/RoleSwitcher.kt`), visible siempre:

- **Rail expandido / rail de iconos**: dos `FilterChip` apilados en la cabecera del rail ("Alumno" / "Docente") con el modo activo en `primaryContainer`.
- **Compacto (drawer)**: los mismos chips en la **cabecera del drawer** (`DrawerHeader`), que es donde el usuario ya está para navegar.
- **Teclado**: `Alt+R` cicla entre los dos modos (ver §7.2).

```kotlin
@Composable
fun RoleSwitcher(
    mode: AppMode,
    onSelect: (AppMode) -> Unit,
    modifier: Modifier = Modifier,
)
```

Cada chip muestra un contador de cátedras disponibles en ese modo (`Alumno (2)`), que se calcula con `app.courseCountsByMode`. Un modo sin cátedras se muestra **deshabilitado** con la razón en el `contentDescription` ("Sin cátedras de alumno").

### 1.5 Cómo se cambia de cátedra: `CourseSwitcher`

Componente **compartido** (`kitoru/ui/shell/CourseSwitcher.kt`), en la `TopAppBar`:

```kotlin
@Composable
fun CourseSwitcher(
    courses: List<CourseOption>,   // curso + rol real + cantidad de alumnos
    active: CourseOption?,
    onSelect: (Course) -> Unit,
    modifier: Modifier = Modifier,
)
```

- Se implementa como `ExposedDropdownMenuBox` de Material3 (disponible en `commonMain` en Compose 1.7.3).
- Muestra `course.shortName` (`"IA-2026-1 · Inteligencia Artificial"`) + el badge del rol real.
- Al cambiar de cátedra: se recargan los datos de la sección activa y **se resetea el estado local de la sección** (búsqueda, selección de detalle, filtros). Se hace en `AppViewModel.selectCourse`, no en la UI.
- La `TopAppBar` lleva además un `CourseStatusChip` que resume el estado de la cátedra con color **+ ícono + texto** (nunca sólo color): `ÍNDICE OK` / `SIN MATERIALES` / `IA SIN CONFIGURAR` / `ANALÍTICA OFF`.

### 1.6 Diagrama de decisión del arranque

```
AppShell
 ├─ app.loading == true ──────────────► LoadingPane (fases)
 ├─ app.profile == null ──────────────► ProfileSetupPane
 ├─ app.courses.isEmpty() ────────────► EmptyCoursesPane
 └─ app.courses.isNotEmpty()
      └─ app.mode == DOCENTE ─────────► TeacherScreen(teacherVm)
         app.mode == ALUMNO ──────────► StudentScreen(studentVm)
```

---

## 2. Estructura de navegación del docente

### 2.1 Decisión: rail lateral (no navegación superior, no panel de pestañas)

Se descartan las dos alternativas:

- **Navegación superior (top bar con tabs)**: la barra ya está ocupada por el selector de cátedra, el badge de rol, la búsqueda y el menú de usuario. Meter 9 pestañas ahí es imposible. Además las pestañas no transmiten "estas secciones están en grupos distintos".
- **Panel de pestañas dentro de la sección**: sí se usa, pero **local**: dentro de *Materiales* hay pestañas `Todos / Documentos / Diapositivas / Enlaces`; dentro de *Chats de alumnos* hay `Por alumno / Por fecha`. No es navegación global.

**Rail lateral, siempre a la izquierda**, como en FunshiEngineAI (`NavigationRailPane`), con dos variantes:

| Variante | Ancho | Cuándo |
|---|---|---|
| Expandida | 240 dp | ≥ 840 dp: ícono + etiqueta + agrupadores visibles |
| Colapsada (sólo ícono) | 72 dp | 600–839 dp: ícono + tooltip |

El rail se **colapsa solo** (el usuario puede forzar el colapso con `Ctrl+B`, como en FunshiEngineAI).

### 2.2 Las 9 secciones, agrupadas

El rail muestra 4 grupos, separados por `Spacer` + `HorizontalDivider`:

| Grupo | Sección | Ícono (`materialIconsExtended`) | Ruta |
|---|---|---|---|
| **Cátedra** | Panel | `Icons.Filled.Dashboard` | `TeacherSection.DASHBOARD` |
| | Materiales | `Icons.Filled.FolderOpen` | `TeacherSection.MATERIALS` |
| | Tareas | `Icons.Filled.Assignment` | `TeacherSection.TASKS` |
| | Anuncios | `Icons.Filled.Campaign` | `TeacherSection.ANNOUNCEMENTS` |
| **Configuración** | Modelo | `Icons.Filled.Tune` | `TeacherSection.MODEL` |
| **Seguimiento** | Analítica | `Icons.Filled.Insights` | `TeacherSection.ANALYTICS` |
| | Chats | `Icons.Filled.Forum` | `TeacherSection.STUDENT_CHATS` |
| | Alumnos | `Icons.Filled.People` | `TeacherSection.STUDENTS` |
| — | Ajustes | `Icons.Filled.Settings` | `TeacherSection.APP_SETTINGS` |

`TeacherSection` (en `commonMain`) lleva el ícono como `ImageVector` (disponible en `compose.ui` common) y el permiso requerido:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/TeacherSection.kt
enum class TeacherSection(
    val label: String,
    val icon: ImageVector,
    val group: TeacherGroup,
    /** Permiso mínimo para que la sección tenga contenido. */
    val requires: Permission = Permission.NONE,
) {
    DASHBOARD("Panel", Icons.Filled.Dashboard, TeacherGroup.COURSE),
    MATERIALS("Materiales", Icons.Filled.FolderOpen, TeacherGroup.COURSE),
    TASKS("Tareas", Icons.Filled.Assignment, TeacherGroup.COURSE),
    ANNOUNCEMENTS("Anuncios", Icons.Filled.Campaign, TeacherGroup.COURSE),
    MODEL("Modelo", Icons.Filled.Tune, TeacherGroup.CONFIG),
    ANALYTICS("Analítica", Icons.Filled.Insights, TeacherGroup.TRACKING),
    STUDENT_CHATS("Chats", Icons.Filled.Forum, TeacherGroup.TRACKING),
    STUDENTS("Alumnos", Icons.Filled.People, TeacherGroup.TRACKING),
    APP_SETTINGS("Ajustes", Icons.Filled.Settings, TeacherGroup.APP);
}

enum class TeacherGroup { COURSE, CONFIG, TRACKING, APP }
```

### 2.3 Árbol de pantallas

La navegación del docente es de **dos niveles**: sección (rail) + elemento (panel de detalle). No hay un árbol profundo.

```
AppShell
└── TeacherScreen
    └── TeacherSectionHost        (switch sobre vm.section)
        ├── DashboardPane
        ├── MaterialsPane
        │   ├── MaterialList          (lista, filtros, búsqueda)
        │   └── MaterialDetail        (panel de detalle)   ← elemento
        ├── TasksPane
        │   ├── TaskList
        │   └── TaskDetail                                  ← elemento
        ├── AnnouncementsPane
        │   ├── AnnouncementList
        │   └── AnnouncementEditorDialog (modal, no panel)
        ├── ModelPolicyPane
        ├── AnalyticsPane
        │   ├── KpiRow
        │   ├── ActivityChart
        │   ├── SourceUsageList
        │   └── StudentAnalyticsTable
        ├── StudentChatsPane
        │   ├── ThreadList            (agrupada por alumno)
        │   └── ThreadReader          (panel de detalle)    ← elemento
        ├── StudentsPane
        │   ├── StudentList
        │   └── StudentDetail          (panel de detalle)   ← elemento
        └── AppSettingsPane           (compartido con Alumno)
```

### 2.4 Back stack manual

Sin `navigation-compose` (no es KMP en Compose 1.7.3). Se implementa en el ViewModel:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/TeacherNavState.kt
data class TeacherRoute(val section: TeacherSection, val elementId: String? = null)

class TeacherNavState {
    val stack: List<TeacherRoute>  // pila; el último es el visible
    fun push(section: TeacherSection, elementId: String? = null)
    fun pop(): Boolean              // false si la pila tiene 1 elemento
    fun select(section: TeacherSection)  // reemplaza el tope
    fun clearTo(section: TeacherSection)
}
```

- **Desktop**: `Esc` hace `pop()`. Si la pila tiene un solo elemento, `Esc` no hace nada (no cierra la app).
- **Android**: `PlatformBackHandler(enabled = nav.stack.size > 1) { nav.pop() }` (`expect/actual`, porque `BackHandler` es Android-only).
- Al cambiar de sección desde el rail: `nav.select(section)` (reemplaza, no apila) → el botón "atrás" del sistema no vuelve a la sección anterior.
- Al tocar un elemento de una lista: `nav.push(section, elementId)` → aparece el panel de detalle y el "atrás" vuelve a la lista.

### 2.5 El panel de detalle

`DetailPane` es un componente compartido:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/DetailPane.kt
@Composable
fun DetailPane(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
)
```

Reglas:

- **Visible sólo si hay un elemento seleccionado** (`nav.stack.last().elementId != null`).
- En `EXPANDED` (≥840 dp) es una columna fija de ancho flexible a la derecha de la lista (la lista ocupa 320 dp fijos).
- En `MEDIUM` (600–839 dp) es una columna de 320 dp a la derecha, y **la lista se oculta** mientras el detalle está abierto (no hay ancho para las dos). El botón "volver" (flecha) cierra el detalle.
- En `COMPACT` (<600 dp) **no hay panel**: el detalle es una pantalla completa, con `TopAppBar` propio y `←` que hace `nav.pop()`.

---

## 3. Las secciones, en detalle

Cada sección define: **qué muestra** y **qué acciones** tiene. Las acciones marcadas con 🔒 requieren un permiso que se calcula desde `CourseRole`.

### (a) Panel — `DashboardPane`

**Muestra**
1. **Fila de KPIs** (`StatTile`): alumnos inscritos / activos, materiales publicados, tareas abiertas, hilos de chat, % de citas respaldadas, latencia media.
2. **Estado de la cátedra**: ¿el índice RAG está construido? ¿hay materiales sin texto extraíble? ¿la `ModelPolicy` valida (`policy.validate()`)? ¿la analítica está habilitada (`course.analyticsEnabled`)?
3. **Acciones rápidas**: "Subir material", "Publicar anuncio", "Crear tarea", "Ver analítica".
4. **Actividad reciente**: últimos hilos de chat (autor, título, modelo, hace cuánto), últimos materiales subidos.
5. **Avisos**: `ErrorBanner` de operaciones fallidas (p. ej. un PDF sin capa de texto, una material whose indexado falló).

**Acciones**
- `onOpenSection(section)` → navega a la sección correspondiente.
- `onQuickUpload()` → abre el diálogo de subida de material.
- `onDismissNotice()`.

**Componentes**: `DashboardPane.kt`, `KpiGrid.kt`, `StatTile.kt`, `RecentActivityList.kt`, `CourseHealthCard.kt`.

### (b) Materiales — `MaterialsPane`

**Muestra**
- Lista de `Material` de la cátedra (`MaterialRepository.listForCourse`), con: título, tipo (`MaterialKind`), tamaño, fecha, ícono de visibilidad (visible / oculto para alumnos), **advertencia de indexado** (`material.warning`) y nº de fragmentos indexados.
- **Filtros**: por `MaterialKind`, por tag, por visibilidad. **Búsqueda** por título.
- **Detalle** (`MaterialDetail`): texto extraído completo (con `TextField` sólo lectura), tags editables, título editable, visibilidad con `Switch`, ruta del archivo, "usado por N alumnos" (de `analytics.topSources`).
- Estado vacío: "Todavía no subiste materiales. El RAG de la cátedra usa este corpus."

**Acciones**
- `onUpload()` — desktop: drag & drop o diálogo con `JFileChooser` (vía `expect/actual`); Android: SAF (`ActivityResultContracts.OpenMultipleDocuments`).
- `onEditMaterial(material)` — título, tipo, tags, visible.
- `onDeleteMaterial(id)` — con `ConfirmDialog` ("Se borra también del índice").
- `onReindex()` — reindexa todo el corpus (`KnowledgeBase.sync`).
- `onToggleVisibility(materialId, visible)`.
- `onOpenRaw(filePath)` — desktop: abrir en el explorador; Android: no aplica (se oculta el botón).

**Componentes**: `MaterialsPane.kt`, `MaterialList.kt`, `MaterialFilterBar.kt`, `MaterialDetail.kt`, `MaterialUploadDialog.kt`, `MaterialTagEditor.kt`.

### (c) Tareas — `TasksPane`

**Requiere un tipo nuevo en el dominio** (no existe todavía). Propuesta para `core/src/commonMain/kotlin/kitoru/domain/Assignment.kt`:

```kotlin
@Serializable
data class Assignment(
    val id: String,
    val courseId: String,
    val title: String,
    val description: String = "",
    /** Fecha de entrega (epoch ms, 0 = sin fecha). */
    val dueAt: Long = 0L,
    /** Materiales de la cátedra asociados (ids). */
    val materialIds: List<String> = emptyList(),
    /** Borrador o publicada. */
    val published: Boolean = false,
    /** Cierre de la tarea (epoch ms, 0 = abierta). */
    val closedAt: Long = 0L,
    val createdAt: Long = 0L,
)
```

**Muestra**
- Lista de tareas agrupadas por estado: **Borrador / Publicadas / Cerradas**.
- Cada ítem: título, fecha de entrega (conchip rojo si venció y sigue abierta), nº de alumnos que interactuaron con los materiales asociados (de `StudentAnalytics`).
- **Detalle** (`TaskDetail`): descripción completa, materiales adjuntos, fecha, estado, y **métricas de cumplimiento** (cuántos alumnos del curso tocaron esos materiales).
- Selector de estado por alumno: `Borrador → Publicado → Cerrado`.

**Acciones**
- `onCreate()` / `onEdit(task)` / `onDelete(task)` (con confirmación).
- `onPublish(taskId)` / `onUnpublish(taskId)` / `onClose(taskId)` / `onReopen(taskId)`.
- `onAttachMaterial(taskId, materialId)` / `onDetachMaterial(...)`.
- `onSetDueDate(taskId, epochMs)`.

**Componentes**: `TasksPane.kt`, `TaskList.kt`, `TaskStatusChip.kt`, `TaskDetail.kt`, `TaskEditorDialog.kt`, `TaskMaterialPicker.kt`.

### (d) Anuncios — `AnnouncementsPane`

**Requiere un tipo nuevo**: `core/src/commonMain/kotlin/kitoru/domain/Announcement.kt`:

```kotlin
@Serializable
data class Announcement(
    val id: String,
    val courseId: String,
    val title: String,
    val body: String = "",
    val createdAt: Long = 0L,
    /** 0 = publicado ya; >0 = programado. */
    val publishAt: Long = 0L,
    /** 0 = no expira. */
    val expiresAt: Long = 0L,
    val pinned: Boolean = false,
)
```

**Muestra**
- Lista cronológica (los fijados primero) con título, extracto, fecha de publicación, estado (`Programado` / `Vigente` / `Expirado`).
- Vista previa del texto en el detalle (panel) o en un diálogo de edición.

**Acciones**
- `onCreate()` / `onEdit()` / `onDelete()`.
- `onPublish(announcementId)` (inmediato) / `onSchedule(announcementId, epochMs)` / `onUnpublish()`.
- `onPin(announcementId, pinned)` / `onExpire(announcementId)`.

**Componentes**: `AnnouncementsPane.kt`, `AnnouncementList.kt`, `AnnouncementEditorDialog.kt`, `AnnouncementStateChip.kt`.

### (e) Configuración del modelo — `ModelPolicyPane`

Es el **heartbeat** del rol docente: fija la base que los alumnos pueden ajustar.

**Muestra** — editor de `ModelPolicy` (`core/.../ModelPolicy.kt`), en 4 tarjetas:

1. **Modo**: `FilterChip` REMOTE / LOCAL (`ModelMode`). En REMOTE: URL base, API key, nombre del modelo. En LOCAL: repo HF, archivo GGUF, carpeta de modelos, ruta de `llama-server`, puerto, contexto, capas GPU.
2. **Generación**: temperatura (`Slider` 0..2), `maxTokens`, `topK`.
3. **Instrucciones del sistema**: `TextField` multilínea (`course.systemPrompt` + `policy.systemPrompt`).
4. **Reglas para el alumno**: `allowStudentOverride` (Switch) y **`lockedFields`**: 7 `Checkbox` (`LockedField.API_KEY`, `.BASE_URL`, `.MODEL_NAME`, `.SYSTEM_PROMPT`, `.TEMPERATURE`, `.MAX_TOKENS`, `.TOP_K`) con explicación en cada uno ("El alumno **no** puede cambiar la clave: la provee la institución").
5. **Herramientas**: `toolsEnabled`, `course.allowLocalModels`, `course.analyticsEnabled`, `course.chunkSize`.

Además: **banner de validación** con `policy.validate()` (mensajes en español, ya implementados) y un botón **"Probar conexión"** (patrón `ApisScreen.kt` de FunshiEngineAI): llama al gateway y muestra `testResult` en verde/rojo.

**Acciones**
- `onSave()` → `course.copy(modelPolicy = draft)` + `CourseRepository.save`. **Guardado explícito** (no en cada tecla) para no escribir el JSON 60 veces por minuto.
- `onReset()` → vuelve el borrador a la `ModelPolicy` persistida.
- `onTestConnection()`.
- `onToggleLock(lockedField, locked)`.
- `onToggleAllowStudentOverride(allowed)`.

**Permisos** 🔒
- `TITULAR` (`role.canConfigureModel == true`): puede editar y guardar todo.
- `AYUDANTE`: la sección **se muestra en modo lectura**, con un `InfoBanner` arriba: *"Sólo el profesor titular puede cambiar la configuración base del modelo. Podés consultar los valores y copiar el system prompt."*. Los campos se renderizan `enabled = false`, no ocultos (para que el ayudante sepa qué hay).
- `ALUMNO` (nunca entra por esta ruta): la sección no aparece.

**Componentes**: `ModelPolicyPane.kt`, `PolicyModeCard.kt`, `PolicyRemoteFields.kt`, `PolicyLocalFields.kt`, `PolicyGenerationCard.kt`, `LockedFieldsGrid.kt`, `ConnectionTestCard.kt`, `PolicyValidationBanner.kt`.

### (f) Analítica — `AnalyticsPane`

**Muestra** (todo de `CourseAnalytics`, calculado por `AnalyticsAnalyzer.analyze()`, que ya es **puro y determinista**):

1. **KPIs de la cátedra**: `students`, `activeStudents`, `totalMessages`, `totalReplies`, `avgMessagesPerStudent`, `citationSupportRate`, `avgLatencyMs`, `totalTokens`.
2. **Gráfico de actividad por día** (`CourseAnalytics.perDay`): barras `messages`/`replies` por `DayActivity.day`.
3. **Fuentes más consultadas** (`topSources`): barra horizontal con `SourceUsage.times` — dice qué material de la cátedra se usa de verdad.
4. **Tabla por alumno** (`perStudent`): mensajes, respuestas, hilos, días activos, latencia media, tokens, `citationSupportRate`, `ungroundedAnswerRate`. Ordenable por columna.
5. **Alumnos sin actividad** (`inactiveStudents`): lista con acción "enviar recordatorio".
6. Filtros: rango de fechas, alumno, modelo usado.

**Acciones**
- `onFilter(range)`, `onFilterStudent(userId)`, `onSort(column, asc)`.
- `onExportCsv()` — escribe `analytics-<código>-<fecha>.csv` en `KitoruPaths.dataDir`; en Android usa el *share sheet*.
- `onOpenStudent(userId)` → salta a la sección Alumnos con ese alumno seleccionado (`nav.push(STUDENTS, userId)`).
- `onRefresh()`.

**Restricción de visibilidad**: si `course.analyticsEnabled == false`, la sección muestra un `EmptyState` con el motivo y un botón para habilitarla (🔒 sólo `TITULAR`). Nótese que `AnalyticsAnalyzer` ya excluye a los alumnos con `ChatVisibility.NONE` — no hay que filtrar nada más en la UI.

**Componentes**: `AnalyticsPane.kt`, `KpiRow.kt`, `ActivityChart.kt` (Canvas), `SourceUsageList.kt`, `StudentAnalyticsTable.kt`, `AnalyticsFilterBar.kt`, `InactiveStudentsCard.kt`.

### (g) Chats de alumnos — `StudentChatsPane`

La sección más delicada: **el docente no ve lo que no debe**.

**Muestra**
- Lista de `ChatThread` de la cátedra (`ThreadRepository.listForCourse`), **agrupada por alumno**, ordenada por `updatedAt` descendente.
- Filtros: por alumno, por modelo (`thread.effectiveModel.modelLabel`), por rango de fechas, por "sólo hilos con citas sin respaldo".
- **Detalle** (`ThreadReader`): los mensajes en modo **sólo lectura**, con:
  - rol, timestamp, latencia, tokens in/out,
  - **fuentes usadas** (`message.sources`),
  - **verificación de citas** (`message.citation`: `verified` / `unsupported` / `unknown` → `CitationStats.supportRate`),
  - **tool calls** (`message.toolCalls` → `ToolCallStats`).
- **El texto se respeta según `Enrollment.visibility`**:
  - `FULL` → se ve el texto completo.
  - `METRICS_ONLY` → **no se muestra el texto**: el mensaje se reemplaza por una "tarjeta de métrica" (latencia, tokens, nº de citas, fuentes) con un candado y el texto *"El contenido no es visible para esta inscripción"*.
  - `NONE` → el hilo **no aparece en la lista** (ni siquiera como métrica): `ThreadRepository` se filtra en el ViewModel antes de llegar a la UI.
- En el detalle, un `VisibilityBadge` en la `TopAppBar` del panel: `Ojo abierto` (FULL) / `Ojo tachado` (METRICS_ONLY).

**Acciones**
- `onOpenThread(threadId)` → `nav.push(STUDENT_CHATS, threadId)`.
- `onFilterStudent(userId)`, `onFilterModel(modelLabel)`, `onFilterQuality(badOnly: Boolean)`.
- `onChangeVisibility(enrollmentId, visibility)` — la misma acción que en la sección Alumnos; se deja aquí por conveniencia.
- `onExportThread(threadId)` — exporta el hilo a Markdown (desktop: diálogo "guardar como"; Android: share sheet). **Exportar respeta la visibilidad**: con `METRICS_ONLY` se exportan sólo las métricas, y el archivo lleva un encabezado de aviso.
- 🔒 **NO existe** "responder al alumno" ni "editar el hilo": el rol docente es de *lectura y análisis*.

**Componentes**: `StudentChatsPane.kt`, `ThreadList.kt`, `ThreadGroupHeader.kt`, `ThreadReader.kt`, `MessageInspectorCard.kt`, `CitationBadge.kt`, `VisibilityBadge.kt`, `VisibilityNoticeCard.kt`.

### (h) Alumnos — `StudentsPane`

**Muestra**
- Lista de `Enrollment` de la cátedra con: nombre, rol, fecha de inscripción, **visibilidad** (`ChatVisibility` con etiqueta y color), actividad (mensajes / última actividad).
- **Detalle** (`StudentDetail`): sus métricas (`StudentAnalytics`), sus hilos, y el **selector de visibilidad**.
- **Código de inscripción**: `course.joinCode` en un `Card` con botón "Copiar" y "Regenerar" (🔒 `TITULAR`).

**Acciones**
- `onChangeVisibility(enrollmentId, visibility)` — `FULL` / `METRICS_ONLY` / `NONE`, cada opción con su descripción (las que ya están en `ChatVisibility`).
- `onChangeRole(enrollmentId, role)` — promover `ALUMNO` → `AYUDANTE` / `TITULAR` y degradar (🔒 `TITULAR`; no se puede degradar al último titular).
- `onRemove(enrollmentId)` — con `ConfirmDialog`: "Se borra la inscripción; los chats quedan sin autor visible".
- `onCopyJoinCode()`, `onRotateJoinCode()` (🔒 `TITULAR`).
- `onInvite()` — genera un link `kitoru://join/<joinCode>` y lo copia.
- `onOpenStudentChats(userId)` → `nav.push(STUDENT_CHATS, ...)` filtrando por ese alumno.

**Componentes**: `StudentsPane.kt`, `StudentList.kt`, `StudentDetail.kt`, `VisibilitySelector.kt` (3 opciones con descripción), `RoleSelector.kt`, `JoinCodeCard.kt`, `StudentActivitySummary.kt`.

### (i) Ajustes de la app — `AppSettingsPane`

**Compartida con el rol Alumno**: vive en `kitoru/ui/settings/`, no en `teacher/`. Aparece como última entrada del rail docente, separada por un `Spacer(weight(1f))` + `HorizontalDivider` (como el `Config` de FunshiEngineAI).

**Muestra**
1. **Apariencia**: tema claro/oscuro (2 botones separados, no tarjetas), color de acento (selector HSV portado de `SettingsPane.kt` de FunshiEngineAI), tamaño de fuente.
2. **Perfil**: nombre y email del `UserProfile` local.
3. **Servidor**: URL del Kitoru-Server (la institución), carpeta de datos, carpeta de modelos local.
4. **Datos**: exportar / importar un respaldo (`.kitoru.zip`), "borrar caché de índice", "borrar todos los datos locales" (con doble confirmación).
5. **Acerca de**: versión, repositorio, licences.

**Acciones**
- `onSetTheme(dark, accent)`, `onSetFontScale(scale)`.
- `onSaveProfile(displayName, email)`.
- `onSetServerUrl(url)`.
- `onExportBackup()`, `onImportBackup(path)`, `onClearCache()`, `onWipeAll()`.

**Componentes**: `settings/AppSettingsPane.kt`, `settings/AppearanceSection.kt`, `settings/ProfileSection.kt`, `settings/ServerSection.kt`, `settings/DataSection.kt`, `settings/AboutSection.kt`, `settings/HsvColorPicker.kt` (port de FunshiEngineAI).

---

## 4. Cómo se estructura el código Compose compartido

### 4.1 Módulos y(source sets)

`:ui` ya existe con `commonMain` + `jvmCommonMain` (manual) + `desktopMain` + `androidMain`. **Todo lo que sigue va en `commonMain` salvo lo marcado.**

```
ui/src/commonMain/kotlin/kitoru/ui/
├── KitoruApp.kt                    @Composable fun KitoruApp(env: KitoruEnv)
├── AppViewModel.kt                 estado raíz (modo, perfil, cátedras)
├── core/
│   ├── AppMode.kt                  enum AppMode { ALUMNO, DOCENTE }
│   ├── WindowSizeClass.kt          enum WindowSizeClass + rememberWindowSizeClass()
│   ├── CourseOption.kt             data class CourseOption(course, role, studentCount)
│   └── Formatting.kt               humanSize(), humanDate(), relativeTime()
├── shell/
│   ├── AppShell.kt                 Scaffold raíz
│   ├── AppTopBar.kt
│   ├── RoleSwitcher.kt
│   ├── CourseSwitcher.kt
│   ├── CourseStatusChip.kt
│   ├── TeacherRail.kt              NavigationRail del docente
│   ├── TeacherDrawer.kt            Drawer compacto del docente
│   ├── SectionEntry.kt             ítem del rail (label + ícono + badge)
│   ├── DetailPane.kt
│   ├── LoadingPane.kt              (port de FunshiEngineAI)
│   ├── ErrorBanner.kt              (port de FunshiEngineAI)
│   └── BusyBanner.kt
├── teacher/
│   ├── TeacherViewModel.kt
│   ├── TeacherSection.kt           enum + TeacherGroup
│   ├── TeacherScreen.kt            dispatcher de secciones
│   ├── TeacherNavState.kt          pila de navegación manual
│   ├── TeacherDashboard.kt         extension functions del área
│   ├── TeacherMaterials.kt
│   ├── TeacherTasks.kt
│   ├── TeacherAnnouncements.kt
│   ├── TeacherModel.kt
│   ├── TeacherAnalytics.kt
│   ├── TeacherChats.kt
│   ├── TeacherStudents.kt
│   ├── dashboard/…                 (ver §3)
│   ├── materials/…
│   ├── tasks/…
│   ├── announcements/…
│   ├── model/…
│   ├── analytics/…
│   ├── chats/…
│   └── students/…
├── student/…                       (otro subagente)
├── settings/…                      (compartido)
├── components/                     StatTile, EmptyState, SearchField, ConfirmDialog, …
└── theme/                          KitoruTheme, Color, Type

ui/src/desktopMain/kotlin/kitoru/ui/
├── FilePicker.desktop.kt           expect fun pickFiles(): List<FilePath>
├── OpenPath.desktop.kt             expect fun revealInFileManager(path: FilePath)
├── DropTarget.desktop.kt           Modifier.dragAndDropTarget(...)
└── WindowInfo.desktop.kt           atajos globales de ventana

ui/src/androidMain/kotlin/kitoru/ui/
├── FilePicker.android.kt           SAF: OpenMultipleDocuments
├── ShareSheet.android.kt           intent ACTION_SEND
└── BackHandler.android.kt          expect @Composable fun PlatformBackHandler(...)
```

### 4.2 El patrón de ViewModel (adaptado a KMP)

FunshiEngineAI usa `class ChatViewModel` plano con `var x by mutableStateOf(...)` + `internal set`, y **funciones de extensión en archivos siblings** para repartir la lógica. Kitoru mantiene eso, con tres cambios:

1. **Sin `Dispatchers.Main` en el constructor**: el `scope` se recibe por parámetro (`internal val scope = CoroutineScope(SupervisorJob() + dispatcher)`), para que los tests puedan inyectar `TestScope`. En producción: `rememberCoroutineScope()`.
2. **Sin `java.nio.file`**: se usa `kitoru.platform.FilePath` y `kitoru.platform.FileSystem`.
3. **IO con `kitoru.platform.ioDispatcher`** (ya existe en `:core`), no `Dispatchers.IO` (que no existe en `commonMain` para Kotlin/Native y no está garantizado en Android).

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/TeacherViewModel.kt
class TeacherViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val assignments: AssignmentRepository,
    private val announcements: AnnouncementRepository,
) {
    // ---- estado (Compose state) ----
    var section: TeacherSection by mutableStateOf(TeacherSection.DASHBOARD)
    var loading: Boolean by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
    var enrollments: List<Enrollment> by mutableStateOf(emptyList())
    var materials: List<Material> by mutableStateOf(emptyList())
    var threads: List<ChatThread> by mutableStateOf(emptyList())
    var assignments: List<Assignment> by mutableStateOf(emptyList())
    var announcements: List<Announcement> by mutableStateOf(emptyList())
    var analytics: CourseAnalytics? by mutableStateOf(null)
    var policyDraft: ModelPolicy by mutableStateOf(ModelPolicy())
    var policyErrors: List<String> by mutableStateOf(emptyList())
    var saving: Boolean by mutableStateOf(false)
    var nav: TeacherNavState = TeacherNavState()          // pila de navegación

    // ---- estado derivado ----
    val activeCourse: Course? get() = app.activeCourse
    val courses: List<Course> get() = app.courses
    val myRole: CourseRole get() = app.roleIn(app.activeCourseId) ?: CourseRole.ALUMNO
    val canConfigureModel: Boolean get() = myRole.canConfigureModel
    val canDesign: Boolean get() = myRole.canDesign
}
```

`mutableStateOf` se puede leer y escribir **fuera de una composición** (es `SnapshotMutableState`), así que el ViewModel es testeable sin `@Composable`.

### 4.3 Reparto `commonMain` vs. plataforma

| Necesidad | `commonMain` | `desktopMain` | `androidMain` |
|---|---|---|---|
| Navegación, rail, drawer, paneles | ✅ | | |
| ViewModel y estado | ✅ | | |
| Lógica de dominio (repos, analítica) | ✅ (viene de `:core`) | | |
| Selector de archivos | `expect fun pickFiles(): List<FilePath>` | `JFileChooser` (Swing, en hilo de Swing) | SAF `OpenMultipleDocuments` |
| Drag & drop de archivos | `expect @Composable fun Modifier.dropTarget(...)` | `onDragEnter/onDrop` de Compose Desktop | `Modifier.dragAndDropTarget` (Android) |
| Volver atrás | `expect @Composable fun PlatformBackHandler(enabled, onBack)` | no-op (usa `Esc`) | `androidx.activity.compose.BackHandler` |
| Compartir / exportar | `expect suspend fun shareText(text, mime)` | diálogo "guardar como" | `Intent.ACTION_SEND` |
| Abrir carpeta en el explorador | `expect fun revealInFileManager(path)` | `Desktop.getDesktop().open` | — (se oculta el botón) |
| Tamaño de ventana | `BoxWithConstraints` | `rememberWindowState` (sólo para el `Window`) | `WindowSizeClass` de Activity |

**Nada de `java.nio.file`, `javax.swing`, `java.awt` ni `ProcessBuilder` en `commonMain`** (regla 2 del CONTEXTO).

### 4.4 Instanciación en la raíz

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruApp.kt
@Composable
fun KitoruApp(env: KitoruEnv) {
    val scope = rememberCoroutineScope()
    val app = remember { AppViewModel(scope, env.courses, env.enrollments, env.profiles, env.userId) }
    val teacher = remember { TeacherViewModel(app, scope, env.courses, env.enrollments, env.materials, env.threads, env.messages, env.assignments, env.announcements) }

    KitoruTheme(dark = app.isDark, accent = app.accentColor) {
        AppShell(app = app, teacher = teacher)
    }
}
```

El `remember { }` en la raíz es el mismo patrón que `Main.kt` de FunshiEngineAI (`val vm = remember { ChatViewModel() }`).

---

## 5. El ViewModel del rol docente

### 5.1 `AppViewModel` (raíz, compartido por los dos roles)

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/AppViewModel.kt
class AppViewModel(
    private val scope: CoroutineScope,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
    val userId: String,
) {
    var mode: AppMode by mutableStateOf(AppMode.DOCENTE); internal set
    var profile: UserProfile? by mutableStateOf(null); internal set
    var allCourses: List<Course> by mutableStateOf(emptyList()); internal set
    var myEnrollments: List<Enrollment> by mutableStateOf(emptyList()); internal set
    var activeCourseId: String? by mutableStateOf(null); internal set
    var loading: Boolean by mutableStateOf(false); internal set
    var error: String? by mutableStateOf(null); internal set
    var isDark: Boolean by mutableStateOf(false); internal set
    var accentColor: Int by mutableStateOf(0xFF6200EE.toInt()); internal set

    /** Cátedras visibles en el modo actual (filtradas por el rol de la inscripción). */
    val visibleCourses: List<Course> get() = allCourses.filter { roleIn(it.id) != null }
    val activeCourse: Course? get() = allCourses.firstOrNull { it.id == activeCourseId }

    /** Rol real del usuario en una cátedra (null = no está inscripto). */
    fun roleIn(courseId: String?): CourseRole?

    fun switchMode(mode: AppMode)
    fun selectCourse(id: String)
    fun reload()
    fun setTheme(dark: Boolean, accent: Int)
    fun saveProfile(displayName: String, email: String)
    fun dismissError()
}
```

`roleIn` es la pieza que hace todo el trabajo del §1:

```kotlin
fun roleIn(courseId: String?): CourseRole? {
    if (courseId == null) return null
    val role = myEnrollments.firstOrNull { it.courseId == courseId }?.role ?: return null
    return if (mode == AppMode.DOCENTE) role.takeIf { it.canDesign } else role.takeIf { it == CourseRole.ALUMNO }
}
```

Es decir: **el modo no reescribe el rol, decide qué rol se está mirando.** Consecuencia: en modo DOCENTE, una inscripción `ALUMNO` devuelve `null` (la cátedra no aparece); en modo ALUMNO, una inscripción `TITULAR` también devuelve `null`.

### 5.2 `TeacherViewModel`: estado expuesto

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/TeacherViewModel.kt
class TeacherViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val assignments: AssignmentRepository,
    private val announcements: AnnouncementRepository,
) {
    // ---------- estado ----------
    var section: TeacherSection by mutableStateOf(TeacherSection.DASHBOARD); internal set
    var loading: Boolean by mutableStateOf(false); internal set
    var error: String? by mutableStateOf(null); internal set
    var saving: Boolean by mutableStateOf(false); internal set

    /** Cátedra actual (derivada de la app: una sola fuente de verdad). */
    val activeCourse: Course? get() = app.activeCourse
    /** Cátedras del usuario en las que es docente. */
    val courses: List<Course> get() = app.visibleCourses
    /** Rol real en la cátedra actual. */
    val myRole: CourseRole get() = app.roleIn(app.activeCourseId) ?: CourseRole.ALUMNO

    // ---------- datos de la cátedra ----------
    var enrollments: List<Enrollment> by mutableStateOf(emptyList()); internal set
    var materials: List<Material> by mutableStateOf(emptyList()); internal set
    var threads: List<ChatThread> by mutableStateOf(emptyList()); internal set
    var assignments: List<Assignment> by mutableStateOf(emptyList()); internal set
    var announcements: List<Announcement> by mutableStateOf(emptyList()); internal set
    var analytics: CourseAnalytics? by mutableStateOf(null); internal set

    // ---------- borrador de configuración ----------
    var policyDraft: ModelPolicy by mutableStateOf(ModelPolicy()); internal set
    var policyErrors: List<String> by mutableStateOf(emptyList()); internal set

    // ---------- navegación ----------
    val nav: TeacherNavState = TeacherNavState()
}
```

### 5.3 `TeacherViewModel`: acciones (firmas exactas)

```kotlin
// ---- navegación ----
fun go(section: TeacherSection)
fun openElement(elementId: String)          // nav.push(sectionActual, elementId)
fun closeElement()                          // nav.pop()
fun back()                                  // nav.pop()

// ---- carga ----
fun reload()                                // recarga TODO lo de la cátedra actual
fun refreshAnalytics()
fun dismissError()

// ---- materiales (TeacherMaterials.kt) ----
fun addMaterial(
    title: String,
    kind: MaterialKind,
    path: FilePath,
    sizeBytes: Long,
    visible: Boolean = true,
    tags: List<String> = emptyList(),
)
fun updateMaterial(material: Material)
fun deleteMaterial(materialId: String)
fun toggleMaterialVisibility(materialId: String, visible: Boolean)
fun reindexMaterials()

// ---- tareas (TeacherTasks.kt) ----
fun upsertAssignment(assignment: Assignment)
fun deleteAssignment(assignmentId: String)
fun publishAssignment(assignmentId: String)
fun unpublishAssignment(assignmentId: String)
fun closeAssignment(assignmentId: String)
fun reopenAssignment(assignmentId: String)

// ---- anuncios (TeacherAnnouncements.kt) ----
fun upsertAnnouncement(announcement: Announcement)
fun deleteAnnouncement(announcementId: String)
fun publishAnnouncement(announcementId: String)
fun scheduleAnnouncement(announcementId: String, publishAt: Long)
fun unpublishAnnouncement(announcementId: String)
fun pinAnnouncement(announcementId: String, pinned: Boolean)

// ---- modelo (TeacherModel.kt) ----
fun editPolicy(transform: (ModelPolicy) -> ModelPolicy)   // muta el borrador
fun savePolicy()                                          // valida + persiste
fun resetPolicy()
fun toggleLockedField(field: LockedField, locked: Boolean)
fun toggleStudentOverride(allowed: Boolean)
fun testConnection()                                      // "Probar conexión"

// ---- alumnos (TeacherStudents.kt) ----
fun changeVisibility(enrollmentId: String, visibility: ChatVisibility)
fun changeRole(enrollmentId: String, role: CourseRole)
fun removeEnrollment(enrollmentId: String)
fun rotateJoinCode()

// ---- chats (TeacherChats.kt) ----
fun visibleThreads(): List<ChatThread>       // filtra por ChatVisibility.showsText/showsMetrics
fun messagesOf(threadId: String): List<ChatMessage>
fun exportThread(threadId: String)
```

**Guardas de permiso dentro del ViewModel** (no sólo en la UI): cada acción destructiva o sensible empieza con

```kotlin
if (!myRole.canConfigureModel) { error = "Sólo el profesor titular puede cambiar la configuración base del modelo."; return }
```

Así la regla "el docente no ve lo que no debe" y la de "el ayudante no toca el modelo" se upheld en **dos** capas (UI deshabilitada + ViewModel que rechaza).

### 5.4 Cómo se testea

**Ubicación**: `ui/src/commonTest/kotlin/kitoru/ui/teacher/TeacherViewModelTest.kt` (y `ui/src/desktopTest/…` si se necesita `JFileChooser`; no debería).

**Infraestructura de test** — repositorios falsos en memoria:

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/support/FakeRepositories.kt
class FakeCourseRepository(var items: List<Course> = emptyList()) : CourseRepository {
    override suspend fun list() = items
    override suspend fun get(id: String) = items.firstOrNull { it.id == id }
    override suspend fun save(course: Course) { items = items.filterNot { it.id == course.id } + course }
    override suspend fun delete(id: String) { items = items.filterNot { it.id == id } }
}
// ... FakeEnrollmentRepository, FakeMaterialRepository, FakeThreadRepository,
//     FakeMessageRepository, FakeAssignmentRepository, FakeAnnouncementRepository
```

**Patrón de test**:

```kotlin
class TeacherViewModelTest {
    @Test
    fun `cambiar de seccion limpia el error anterior`() = runTest {
        val vm = buildTeacherVm(scope = this)          // `this` es el TestScope
        vm.error = "algo falló"
        vm.go(TeacherSection.MATERIALS)
        assertEquals(TeacherSection.MATERIALS, vm.section)
    }

    @Test
    fun `el ayudante no puede guardar la politica del modelo`() = runTest {
        val vm = buildTeacherVm(role = CourseRole.AYUDANTE, scope = this)
        vm.editPolicy { it.copy(temperature = 1.5) }
        vm.savePolicy()
        assertNotNull(vm.error)
        assertTrue(vm.error!!.contains("titular"))
    }

    @Test
    fun `los hilos de un alumno con visibilidad NONE no aparecen`() = runTest {
        val vm = buildTeacherVm(scope = this)
        val visibles = vm.visibleThreads()
        assertTrue(visibles.none { it.authorId == "alumno-oculto" })
    }
}
```

**Reglas de testabilidad** (diseño con esto en mente desde el principio):

1. El ViewModel recibe el `CoroutineScope` por constructor ⇒ los tests usan `runTest { }` y el `TestScope` como scope.
2. El ViewModel **no** crea `Dispatchers.IO` ni `Dispatchers.Main` internamente: el dispatcher se inyecta (`private val io: CoroutineDispatcher = kitoru.platform.ioDispatcher`) para que el test sea determinista.
3. Ninguna acción toca el sistema de archivos directamente: va por los repositorios, que en test son fakes.
4. Las operaciones de red (probar conexión) van detrás de una interfaz inyectable `CourseGatewayProbe`; en test, un fake que devuelve éxito/fallo.
5. `AnalyticsAnalyzer` ya es puro y determinista ⇒ los números de la sección Analítica se testean en `:core`, no acá. En `:ui` se testea sólo que el ViewModel **lo llame y lo exponga**.
6. El estado es `mutableStateOf`: se lee en tests sin `@Composable` y sin `Snapshot.sendApplyNotifications` (el valor ya está actualizado al leer).

---

## 6. Navegación responsive

### 6.1 Breakpoints

Se implementan en `commonMain` con `BoxWithConstraints` (no se usa `androidx.compose.material3:material3-window-size-class`, que no es multiplatform para desktop en Compose 1.7.3):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/WindowSizeClass.kt
enum class WindowSizeClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    BoxWithConstraints {
        val w = maxWidth
        return remember(w) {
            when {
                w < 600.dp -> WindowSizeClass.COMPACT
                w < 840.dp -> WindowSizeClass.MEDIUM
                else        -> WindowSizeClass.EXPANDED
            }
        }
    }
}
```

| Clase | Ancho | Navegación primaria | Contenido | Detalle |
|---|---|---|---|---|
| **COMPACT** | < 600 dp | `ModalNavigationDrawer` (hamburger en la `TopAppBar`) | 1 columna | Pantalla completa con `←` (back) |
| **MEDIUM** | 600–839 dp | `NavigationRail` **de iconos** (72 dp) | 1 columna | Panel derecho de 320 dp; **la lista se oculta** mientras el detalle está abierto |
| **EXPANDED** | ≥ 840 dp | `NavigationRail` **expandida** (240 dp, con etiquetas) | 2 columnas | Lista 320 dp fija + detalle flexible |

Los cortes 600/840 dp son los de las *window size classes* de Material 3, para que la app se sienta nativa en Android (COMPACT cubre el 99 % de los teléfonos) y nativa en desktop.

### 6.2 Qué se oculta en cada clase

| Elemento | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Rail lateral | ❌ (vive en el drawer) | ✅ sólo íconos + tooltip | ✅ con etiquetas |
| Etiquetas del rail | n/a | sólo al pasar el mouse / tooltip | siempre |
| Cabecera del rail (rol + logo) | dentro del drawer | sólo ícono de rol | completa |
| Selector de cátedra | chip en la `TopAppBar` | chip en la `TopAppBar` | chip + nombre completo |
| Panel de detalle | pantalla completa | 320 dp (reemplaza la lista) | columna fija |
| Lista + detalle simultáneos | ❌ | ❌ | ✅ |
| Botón "abrir en explorador" | ❌ | ✅ | ✅ |
| Filtros avanzados de Analítica | en un diálogo | en un diálogo | en línea |
| Diálogo vs. panel | siempre diálogo | siempre diálogo | panel si hay espacio |

En Android, el `COMPACT` cubre la práctica totalidad de los dispositivos (360–480 dp de ancho). Por eso **el rail de iconos casi nunca se ve en Android**: en la práctica, Android usa drawer + pantalla completa, y el rail sólo aparece en tablets.

### 6.3 Reglas de comportamiento

- **El rail nunca desaparece en desktop** salvo que el usuario lo colapse (`Ctrl+B`). El estado de colapso se guarda en `AppConfig`.
- **Cambiar el tamaño de la ventana** recalcula la clase al instante (Compose `BoxWithConstraints`); la pila de navegación no se pierde: si en COMPACT se estaba viendo un detalle, al pasar a EXPANDED el mismo `elementId` se muestra en el panel.
- **En COMPACT, el detalle es una ruta** de la pila: `nav.push(section, elementId)` muestra `MaterialDetailScreen` a pantalla completa en lugar de `MaterialList`.
- **El drawer se cierra al navegar** (comportamiento estándar) y devuelve el foco al botón del hamburger.

### 6.4 El rail en Android: decisión explícita

**No hay bottom bar.** Motivos:

1. **9 secciones** no entran en una `NavigationBar` (Material recomienda 3–5). Con una bottom bar habría un ítem "Más" que abre un segundo menú: dos niveles de navegación en mobile, lo que empeora la usabilidad.
2. El flujo del docente **no es de 3–5 tareas frecuentes**: alterna entre diseñar (materiales/tareas) y medir (analítica/chats). Un rail/drawer con grupos jerárquicos expresa mejor esa estructura.
3. **`ModalNavigationDrawer` es el mismo componente en desktop y Android** en Compose Multiplatform ⇒ un único código, un único modelo mental, menos superficie de bugs.

**Alternativa considerada y descartada**: bottom bar con 4 ítems (`Panel`, `Analítica`, `Chats`, `Más`) + drawer. Se descarta por el punto 1: obligaría a mantener dos listas de secciones sincronizadas, que es exactamente la clase de bug que ya suffered FunshiEngineAI con el rail colapsado vs. expandido.

**Si más adelante se quiere una bottom bar**, el punto de extensión es `TeacherNav.kt`: `TeacherNavHost` decide el contenedor según `WindowSizeClass`, y sumar un `COMPACT_BOTTOM` no obliga a tocar las secciones.

---

## 7. Accesibilidad

### 7.1 Reglas transversales (a aplicar desde el primer día)

1. **Todo ícono decorativo lleva `contentDescription = null`**; todo ícono *interactivo* lleva `contentDescription` con la acción completa ("Ir a Analítica", "Subir material", "Copiar código de inscripción").
2. **El estado nunca se codifica sólo con color**: siempre ícono + texto (`"● Publicada"` / `"Programada"` / `"Expirada"`). Esto cubre daltonismo y contraste bajo.
3. **Se usan los roles de color de Material 3** (`primaryContainer`/`onPrimaryContainer`, `errorContainer`/`onErrorContainer`) y no colores crudos, para que el tema claro/oscuro y el color de acento manten el contraste.
4. **Contraste mínimo**: texto normal ≥ 4.5:1, texto grande (≥18 sp) ≥ 3:1, contra `surface` y `surfaceContainerHighest`. Los chips del rail usan `onSurfaceVariant` sobre `surfaceContainerHighest`, que en el tema por defecto cumple 4.6:1.
5. **Áreas táctiles ≥ 48 dp** en mobile (el `NavigationBarItem` de Material ya lo cumple); en desktop, los ítems del rail miden 48 dp de alto aunque el ícono sea de 24 dp.
6. **Escalado de texto hasta 200 %**: las filas de lista usan `maxLines = 2` + `TextOverflow.Ellipsis` y **alturas no fijas** (`heightIn(min = 48.dp)`), nunca `height = 40.dp` con texto.
7. **`Modifier.semantics`**: los títulos de sección llevan `Modifier.semantics { heading() }`; los toggles, `stateDescription`; los avisos de estado, `liveRegion = LiveRegionMode.Polite`.
8. **Foco visible**: nunca `indication = null`; los ítems del rail usan `Modifier.focusable()` con el `indication` por defecto de Material 3, y el `focusGroup()` por sección.
9. **El foco no se roba**: al cambiar de sección se mueve el foco al *título* de la sección (no al primer control de un formulario). Al abrir un diálogo, el foco va al primer campo; al cerrarlo, vuelve al elemento que lo abrió.

### 7.2 Navegación por teclado (desktop)

**Orden de tabulación** (natural, de arriba hacia abajo, rail → contenido):

```
[Rol] [Cátedra ▾] [Buscar] [Menú usuario]
Rail:  Panel · Materiales · Tareas · Anuncios · Modelo · Analítica · Chats · Alumnos · Ajustes
Contenido: [filtros] → [lista] → [detalle] → [acciones del panel]
```

**Atajos globales** (`onPreviewKeyEvent` en la raíz de `AppShell`):

| Atajo | Acción |
|---|---|
| `Ctrl/Cmd + 1..9` | Ir a la sección *n* (en el orden del rail) |
| `Ctrl/Cmd + B` | Expandir / colapsar el rail |
| `Ctrl/Cmd + K` | Paleta de comandos (búsqueda global: secciones, alumnos, materiales) |
| `Ctrl/Cmd + F` | Foco en el campo de búsqueda de la sección actual |
| `Alt + R` | Cambiar de rol (Alumno ⇄ Docente) |
| `Alt + ↑/↓` | Mover el foco dentro del rail (arriba/abajo) |
| `Esc` | Cerrar drawer / diálogo / panel de detalle; si no hay nada abierto, `nav.pop()` |
| `Enter` / `Space` | Activar el ítem de rail o el botón con foco (comportamiento estándar de `clickable`) |
| `Ctrl/Cmd + S` | Guardar (según la sección: política, tarea, anuncio) |

**Implementación**:

```kotlin
// en AppShell, sobre Modifier.focusGroup()
Modifier.onPreviewKeyEvent { ev ->
    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    when {
        ctrl && ev.key == Key.B -> { toggleRail(); true }
        ctrl && ev.key == Key.K -> { openCommandPalette(); true }
        ctrl && ev.key in Key.One..Key.Nine -> { goToSection(ev.key); true }
        ev.key == Key.Escape -> { onEscape(); true }
        else -> false
    }
}
```

`Key.One..Key.Nine` es el rango de `androidx.compose.ui.input.key.Key`.

**Foco al cambiar de sección**: `LaunchedEffect(vm.section) { sectionTitleFocusRequester.requestFocus() }`, con `Modifier.focusRequester(sectionTitleFocusRequester).semantics { heading() }` en el `Text` del título de la sección.

**Foco al abrir/cerrar el panel de detalle**: `LaunchedEffect(detailOpen)` → si se abre, foco al título del detalle; si se cierra, foco al ítem de lista que lo abrió (guardado en `nav.lastFocusedItemId`).

### 7.3 Lector de pantalla

- El rail y el drawer usan `NavigationRail` / `NavigationDrawer` de Material 3, que ya publican la semántica correcta (`Role.Tab` / lista de destinos).
- Cada ítem: `Modifier.semantics { selected = isSelected }` (lo pone el componente M3) + `contentDescription = "Analítica"`.
- Los grupos del rail llevan un `Text` de encabezado **no seleccionable** (`Modifier.clearAndSetSemantics { }`) para que el lector no lo lea como un destino.
- Los `StatTile` de analítica llevan `contentDescription = "Citas respaldadas: 87 por ciento"` (no "87%"), porque el lector de pantalla lee "%" como "por ciento vacío" en algunos motores.
- Los cambios de estado (error, carga, publicación) se anuncian con `liveRegion = LiveRegionMode.Polite` en el `ErrorBanner` / `BusyBanner`.
- Los gráficos (`ActivityChart`) llevan `contentDescription` con el resumen textual ("Actividad de los últimos 14 días: pico el 3 de junio con 42 mensajes") y la lista tabular equivalente disponible para lector de pantalla (`StudentAnalyticsTable` es una tabla real, no una imagen).
- La `CourseStatusChip` describe su estado con texto, no sólo con color.

### 7.4 Android

- `TalkBack`: los `IconButton` de la `TopAppBar` llevan `contentDescription`; el `ModalNavigationDrawer` se abre con `Es` (escape) y se cierra con atrás.
- `TalkBack` + `NavigationRail`: el rail de iconos tiene etiquetas accesibles por componente (`NavigationRailItem` las expone).
- Con TalkBack activo, la `TopAppBar` no debe capturar el foco inicial: el foco arranca en el título de la sección.
- Rotación: la app es responsive, no hace falta orientation lock.

### 7.5 Limitación conocida

En **desktop**, Compose Multiplatform 1.7.3 tiene soporte de accesibilidad **parcial** (la semántica básica de texto funciona en Windows vía `SemanticsOwner`, pero no hay soporte completo de TalkBack/VoiceOver ni de lector de pantalla del SO). Por eso la **accesibilidad de desktop se apoya en el teclado** (§7.2) y en el contraste (§7.1), no en el lector de pantalla. En **Android**, todo lo anterior aplica completo. Esto debe quedar anotado en el README de accesibilidad del proyecto.

---

## 8. Diagrama textual de la jerarquía de composables

```
KitoruApp(env)                                             [commonMain]
│  rememberCoroutineScope() → scope
│  remember { AppViewModel(...) }      → app
│  remember { TeacherViewModel(...) }  → teacher
│
└─ KitoruTheme(dark, accent)
   │
   └─ AppShell(app, teacher)                                [shell/AppShell.kt]
      │
      ├─ AppTopBar(app, teacher)                            [shell/AppTopBar.kt]
      │  ├─ (COMPACT) MenuButton → abre TeacherDrawer
      │  ├─ RoleSwitcher(mode, counts, onSelect)            [shell/RoleSwitcher.kt]
      │  │  ├─ FilterChip "Alumno (2)"
      │  │  └─ FilterChip "Docente (1)"
      │  ├─ CourseSwitcher(options, active, onSelect)        [shell/CourseSwitcher.kt]
      │  │  └─ ExposedDropdownMenuBox → CourseOptionRow × N
      │  ├─ CourseStatusChip(course, health)                 [shell/CourseStatusChip.kt]
      │  ├─ SearchField (habilitado por sección)
      │  └─ OverflowMenu (perfil, ayuda, salir)
      │
      ├─ TeacherRail(section, onSelect)                      [shell/TeacherRail.kt]  (MEDIUM/EXPANDED)
      │  └─ SectionEntry × 9
      │     └─ TeacherGroup header (COURSE / CONFIG / TRACKING / APP)
      │
      ├─ TeacherDrawer(drawerState, content = TeacherRail)   [shell/TeacherDrawer.kt] (COMPACT)
      │  └─ DrawerHeader → RoleSwitcher + CourseSwitcher
      │
      ├─ ErrorBanner(error, onDismiss)                       [shell/ErrorBanner.kt]
      ├─ BusyBanner(busy, label)                             [shell/BusyBanner.kt]
      │
      └─ TeacherScreen(vm = teacher)                         [teacher/TeacherScreen.kt]
         │
         └─ TeacherSectionHost(section = vm.section, nav = vm.nav)
            │
            ├─ DASHBOARD → DashboardPane(vm)                 [teacher/dashboard/DashboardPane.kt]
            │  ├─ KpiGrid
            │  │  └─ StatTile × 6                            [components/StatTile.kt]
            │  ├─ CourseHealthCard
            │  ├─ QuickActionsRow → onOpenSection / onQuickUpload
            │  └─ RecentActivityList
            │
            ├─ MATERIALS → MaterialsPane(vm)                 [teacher/materials/MaterialsPane.kt]
            │  ├─ MaterialFilterBar(kind, tag, visibility, query)
            │  ├─ MaterialList(items, onOpen, onUpload, onReindex)
            │  │  └─ MaterialRow × N  (extensión, tamaño, warning, visibility)
            │  └─ MaterialDetail(material)                    [teacher/materials/MaterialDetail.kt]
            │     ├─ MaterialTagEditor
            │     ├─ ExtractedTextView (sólo lectura)
            │     └─ RevealInFileManagerButton  (desktop)
            │
            ├─ TASKS → TasksPane(vm)                          [teacher/tasks/TasksPane.kt]
            │  ├─ TaskList(grouped by status)
            │  │  └─ TaskRow × N  (TaskStatusChip)
            │  ├─ TaskDetail(assignment, analytics)           [teacher/tasks/TaskDetail.kt]
            │  └─ TaskEditorDialog(assignment)                [teacher/tasks/TaskEditorDialog.kt]
            │     └─ TaskMaterialPicker
            │
            ├─ ANNOUNCEMENTS → AnnouncementsPane(vm)          [teacher/announcements/AnnouncementsPane.kt]
            │  ├─ AnnouncementList
            │  │  └─ AnnouncementRow × N (AnnouncementStateChip)
            │  └─ AnnouncementEditorDialog(announcement)
            │
            ├─ MODEL → ModelPolicyPane(vm)                   [teacher/model/ModelPolicyPane.kt]
            │  ├─ PolicyModeCard(REMOTE | LOCAL)
            │  ├─ PolicyRemoteFields | PolicyLocalFields
            │  ├─ PolicyGenerationCard (temperature / maxTokens / topK)
            │  ├─ LockedFieldsGrid(LockedField × 7)
            │  ├─ ConnectionTestCard(testResult)
            │  └─ PolicyValidationBanner(policyErrors)
            │
            ├─ ANALYTICS → AnalyticsPane(vm)                  [teacher/analytics/AnalyticsPane.kt]
            │  ├─ AnalyticsFilterBar(range, student, model)
            │  ├─ KpiRow → StatTile × 8
            │  ├─ ActivityChart(perDay)      [Canvas + contentDescription]
            │  ├─ SourceUsageList(topSources)
            │  ├─ StudentAnalyticsTable(perStudent, onSort, onOpen)
            │  └─ InactiveStudentsCard(inactiveStudents)
            │
            ├─ STUDENT_CHATS → StudentChatsPane(vm)           [teacher/chats/StudentChatsPane.kt]
            │  ├─ ThreadFilterBar(student, model, badOnly)
            │  ├─ ThreadList(grouped by student)
            │  │  └─ ThreadGroupHeader(student) → ThreadRow × N
            │  └─ ThreadReader(thread, visibility)            [teacher/chats/ThreadReader.kt]
            │     ├─ VisibilityBadge(visibility)
            │     ├─ MessageInspectorCard × N
            │     │  ├─ CitationBadge(citation)
            │     │  ├─ SourceList(sources)
            │     │  └─ ToolCallList(toolCalls)
            │     └─ VisibilityNoticeCard  (si METRICS_ONLY: reemplaza el texto)
            │
            ├─ STUDENTS → StudentsPane(vm)                    [teacher/students/StudentsPane.kt]
            │  ├─ StudentList(enrollments)
            │  │  └─ StudentRow × N (rol, visibilidad, actividad)
            │  ├─ StudentDetail(enrollment, analytics)         [teacher/students/StudentDetail.kt]
            │  │  ├─ VisibilitySelector(3 opciones + descripción)
            │  │  ├─ RoleSelector
            │  │  └─ StudentActivitySummary
            │  └─ JoinCodeCard(course.joinCode, onCopy, onRotate)
            │
            └─ APP_SETTINGS → AppSettingsPane(vm)             [settings/AppSettingsPane.kt]  (compartido)
               ├─ AppearanceSection(tema, acento, escala de fuente)
               │  └─ HsvColorPicker
               ├─ ProfileSection(displayName, email)
               ├─ ServerSection(serverUrl, dataDir, modelsDir)
               ├─ DataSection(exportar / importar / limpiar)
               └─ AboutSection(versión)
```

### 8.1 Archivos `commonMain` del rol docente (lista cerrada)

```
ui/src/commonMain/kotlin/kitoru/ui/teacher/
├── TeacherSection.kt          enum TeacherSection + TeacherGroup
├── TeacherViewModel.kt        estado + acciones
├── TeacherNavState.kt         pila de navegación manual
├── TeacherScreen.kt           dispatcher de secciones
├── TeacherDashboard.kt        ext: computeKpis(), recentActivity(), health()
├── TeacherMaterials.kt        ext: add/update/delete/reindex
├── TeacherTasks.kt            ext: upsert/publish/close/delete
├── TeacherAnnouncements.kt    ext: upsert/publish/schedule/pin/delete
├── TeacherModel.kt            ext: editPolicy/savePolicy/resetPolicy/testConnection
├── TeacherAnalytics.kt        ext: refreshAnalytics/exportCsv/filter
├── TeacherChats.kt            ext: visibleThreads/messagesOf/exportThread
├── TeacherStudents.kt         ext: changeVisibility/changeRole/remove/rotateJoinCode
├── dashboard/{DashboardPane, KpiGrid, CourseHealthCard, RecentActivityList}.kt
├── materials/{MaterialsPane, MaterialList, MaterialFilterBar, MaterialDetail, MaterialUploadDialog, MaterialTagEditor}.kt
├── tasks/{TasksPane, TaskList, TaskStatusChip, TaskDetail, TaskEditorDialog, TaskMaterialPicker}.kt
├── announcements/{AnnouncementsPane, AnnouncementList, AnnouncementEditorDialog, AnnouncementStateChip}.kt
├── model/{ModelPolicyPane, PolicyModeCard, PolicyRemoteFields, PolicyLocalFields, PolicyGenerationCard, LockedFieldsGrid, ConnectionTestCard, PolicyValidationBanner}.kt
├── analytics/{AnalyticsPane, AnalyticsFilterBar, KpiRow, ActivityChart, SourceUsageList, StudentAnalyticsTable, InactiveStudentsCard}.kt
├── chats/{StudentChatsPane, ThreadFilterBar, ThreadList, ThreadGroupHeader, ThreadReader, MessageInspectorCard, CitationBadge, VisibilityBadge, VisibilityNoticeCard}.kt
└── students/{StudentsPane, StudentList, StudentRow, StudentDetail, VisibilitySelector, RoleSelector, JoinCodeCard, StudentActivitySummary}.kt
```

---

## 9. Dependencias y dependencias que faltan

**Faltan en `:core`** (los pide esta sección; deben ser agregados por el subagente de dominio o por el de implementación):

| Tipo | Archivo propuesto | Para qué |
|---|---|---|
| `Assignment` | `core/.../domain/Assignment.kt` | Sección Tareas |
| `Announcement` | `core/.../domain/Announcement.kt` | Sección Anuncios |
| `AssignmentRepository` | `core/.../domain/Repositories.kt` | Persistencia de tareas |
| `AnnouncementRepository` | `core/.../domain/Repositories.kt` | Persistencia de anuncios |
| `FileAssignmentRepository`, `FileAnnouncementRepository` | `core/.../domain/FileRepositories.kt` | Implementaciones JSON (`tasks.json`, `announcements.json`) |
| `AppConfig` (ui) | `ui/.../AppConfig.kt` | Modo, tema, acento, tamaño de fuente, URL del servidor, rail colapsado |

**No hace falta** (ya existe en `:core`): `Course`, `Enrollment`, `Material`, `ChatThread`, `ChatMessage`, `ModelPolicy`, `LockedField`, `ChatVisibility`, `CourseRole`, `StudentAnalytics`, `CourseAnalytics`, `AnalyticsAnalyzer`, `CitationStats`, `ToolCallStats`.

---

## 10. Checklist de implementación (orden sugerido)

1. `core/WindowSizeClass.kt` + `core/AppMode.kt` + `core/CourseOption.kt`.
2. `AppViewModel` + `shell/AppShell.kt` + `shell/AppTopBar.kt` + `shell/LoadingPane.kt` + `shell/ErrorBanner.kt`.
3. `shell/RoleSwitcher.kt` + `shell/CourseSwitcher.kt` + `shell/CourseStatusChip.kt`.
4. `teacher/TeacherSection.kt` + `teacher/TeacherNavState.kt` + `teacher/TeacherScreen.kt` + `shell/TeacherRail.kt` + `shell/TeacherDrawer.kt` + `shell/DetailPane.kt`.
5. `teacher/TeacherViewModel.kt` (estado y acciones) + fakes de test ⇒ **el esqueleto ya debe andar con datos falsos**.
6. Las 9 secciones, empezando por **Panel** y **Alumnos** (las que no dependen de tipos nuevos), después **Materiales**, **Modelo**, **Analítica**, **Chats**, y por último **Tareas** y **Anuncios** (que dependen de los tipos nuevos del dominio).
7. `settings/AppSettingsPane.kt` (compartido).
8. Accesibilidad: foco, semántica, atajos, contraste ⇒ **en el mismo PR** que cada sección, no al final.
