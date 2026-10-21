# 02 — Editor de la cátedra (docente)

> Pantalla donde el docente **diseña la cátedra**: nombre, código, cursada,
> descripción, código de inscripción, política de IA, IA local, recuperación
> (topK / chunk), analítica y publicación.
>
> Depende de: `docs/design/CONTEXTO.md`, el dominio `kitoru.domain`
> (`Course.kt`, `ModelPolicy.kt`, `Roles.kt`, `Repositories.kt`), la UI de texto
> enriquecido de FunshiEngineAI (`app/ui/Markdown.kt`, `app/ui/CodeBlock.kt`) y
> los docs hermanos `04` (instrucciones), `05` (configuración del modelo),
> `07` (chats) y `08` (permisos).
>
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *fail-fast con mensajes en español*, *el estudiante conserva
> agencia*, *el docente no ve lo que no debe*.

---

## 0. Decisiones en una línea

| # | Decisión | Motivo |
|---|---|---|
| D1 | El editor edita un **borrador de trabajo** (`Course`); lo que ve el alumno es una **copia congelada** (`PublishedCourse`) que se crea al publicar. | El docente puede experimentar (prompt, topK, temperatura) sin tocar la cursada en curso. Es la misma idea que "Guardar y aplicar" del doc 05 y "Probar antes de guardar" del doc 04. |
| D2 | **Escritorio: dos columnas** — formulario (izquierda) + vista previa del alumno (derecha, 380 dp). **Android: una columna** con secciones por pestaña y la vista previa como destino aparte. | La vista previa es el requisito de confianza del docente ("¿así la van a ver?"). En ancho hay lugar; en angosto no. |
| D3 | Las secciones son **acordeones** en escritorio y **pestañas** (`ScrollableTabRow`) en angosto, sobre los **mismos composables** de sección. | Un solo código de sección, dos contenedores. Sin duplicar la UI. |
| D4 | El control de **cada** campo está **una sola vez en toda la app**: la sección "Modelo e IA" del editor monta los **mismos composables** que el doc 05 (mismo ViewModel, misma sección). | La regla "un control por opción" del doc 05 se cumple también entre pantallas. |
| D5 | Validación en línea con mensajes en español, **anclada al campo**: `ModelPolicy.validationIssues()` (nuevo) + `CourseValidator` (nuevo, en `:core`). `ModelPolicy.validate()` **no cambia** (mismos mensajes, mismo orden). | El `validate()` actual devuelve una lista plana: sirve para un banner, no para marcar el campo equivocado. |
| D6 | El **código de inscripción** son 6 caracteres del alfabeto Crockford de 32 símbolos (sin `0/O/1/I/L/U`), normalizado al escribir. | Se dicta por teléfono: sin caracteres ambiguos, ~2^30 combinaciones. |
| D7 | El código se **genera** (nunca se tipea), se **rota** a pedido y se **copia** con un puerto `Clipboard` (`expect/actual`). | Rotar es la mitigación cuando el código se filtró. Copiar es el caso de uso real. |
| D8 | **Autoguardado con debounce de 600 ms** + botón "Guardar" explícito. `LaunchedEffect(draft)` + `delay(600)`. | El docente no debería perder texto por cerrar una pantalla. El botón queda para el gesto explícito y para "Publicar". |
| D9 | Si se cierra la app con cambios sin guardar: en escritorio, `onCloseRequest` **vuelca** el borrador (`runBlocking`) antes de cerrar; en Android el autoguardado limita la pérdida a 600 ms. | Cierre con pérdida de datos es inaceptable; en Android el proceso puede morir sin aviso. |
| D10 | "Probar el chat" abre el chat del **docente** con el material real y la config **del borrador**, y **no** cuenta para la analítica (hilo marcado `isSmokeTest`). | Es el smoke test: tiene que probar lo que se va a publicar, no lo publicado. |
| D11 | Antes de publicar, el alumno **no ve nada**: la cátedra no aparece, el código no sirve y el error es genérico ("El código no es válido"). | No se filtra la existencia de una cátedra no publicada. |
| D12 | El editor es **sólo del titular**: el ayudante entra en modo lectura (mismo criterio que el doc 05). Las capacidades vienen del doc 08 (`COURSE_EDIT`, `COURSE_VIEW_JOIN_CODE`, `COURSE_ROTATE_JOIN_CODE`, `COURSE_CONFIG_RAG`, `COURSE_PUBLISH`, `COURSE_UNPUBLISH`). | No se inventa una matriz de permisos nueva. |
| D13 | El parser de markdown (`parseMarkdown`) **se porta** a `:ui` común; el render de LaTeX **no** (el doc 07 ya decidió: LaTeX como texto monoespaciado). | El parser es Kotlin puro y portable; `MathRender` depende de `java.awt` + JLaTeXMath. |
| D14 | El `CodeBlock` de FunshiEngineAI se porta **sin** el `JFileChooser` de Swing: el botón "Guardar como…" usa el puerto `FileDialogs` del doc 07 y el de copiar usa el puerto `Clipboard`. | Regla 2: nada de Swing en `commonMain`. |

---

## 1. Objetivo y alcance

### 1.1 Qué resuelve

El docente necesita poder responder, sin salir de la cátedra:

- ¿cómo se llama mi materia y con qué código se anotan?,
- ¿qué van a leer los alumnos cuando entren?,
- ¿el modelo con el que van a chatear está bien configurado?,
- ¿el material está indexado con el tamaño de fragmento correcto?,
- ¿puedo ver los chats de la cátedra? (respuesta: sí, salvo que la analítica esté apagada),
- ¿la cátedra está lista para que se inscriban? (publicación).

### 1.2 Qué **no** es de esta pantalla

| Va en otro doc | Por qué no acá |
|---|---|
| **Instrucciones del modelo** (doc 04) | El prompt tiene su propio editor con checklist, plantillas y prueba. Acá sólo se muestra el resumen y el enlace. |
| **Configuración avanzada del modelo** (doc 05) | Presets, reglas de herramientas, keys por alumno, rangos, `thinking`/`power`. La sección "Modelo e IA" de esta pantalla **monta los mismos composables** (D4), no los duplica. |
| **Materiales** (doc 03) | Subir, extraer texto, reindexar y visibilidad de materiales son otra pantalla. Acá sólo se cuenta y se avisa si no hay material. |
| **Alumnos / inscripciones** (doc 06) | El padrón, la visibilidad por inscripción y los roles. |
| **Analítica** (doc 08/10) | Agregados por alumno y por día. Acá sólo el interruptor que la habilita. |
| **Chats de los alumnos** (doc 07) | Explorador de chats. |
| **Avisos** (doc 07 de dominio) | Los avisos tienen su propio ciclo de borrador/publicación. |

### 1.3 La tesis: el borrador y la publicación son dos cosas

```
   CATEDRA (Course)                      PUBLICACION (PublishedCourse)
   ─────────────────────                  ─────────────────────────────
   Lo que edita el docente                Lo que ve el alumno
   · siempre existe                       · existe si la cátedra está publicada
   · se autoguarda                        · se congela al publicar
   · se puede editar mientras              · cambia sólo al volver a publicar
     la cátedra está publicada
```

Mientras el docente edita una cátedra **publicada**, los alumnos siguen viendo
la copia congelada: no aparece el texto a medio escribir, ni el `topK` que el
docente está probando, ni el prompt nuevo. Cuando el docente **publica**, la
copia se regenera con los valores de trabajo.

Esto evita el problema clásico de las herramientas de cátedra: el docente
tocando un slider y, a los treinta segundos, veinticinco alumnos viendo el
número raro que dejó mientras testeaba.

---

## 2. Layout de la pantalla

### 2.1 Escritorio (ancho ≥ 840 dp): dos columnas

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ ←  IA-2026-1 · Inteligencia Artificial            [Borrador]  [Probar chat] [⋮]     │
│    2026-1 · los cambios se guardan solos                        [Guardar]  [Publicar]│
├────────────────────────────────────────────────┬─────────────────────────────────────┤
│  IDENTIDAD                                     │  ASÍ LA VAN A VER LOS ALUMNOS       │
│  ┌──────────────────────────────────────────┐  │  ┌───────────────────────────────┐  │
│  │ Nombre      [Inteligencia Artificial   ] │  │  │ Inteligencia Artificial      │  │
│  │ Código      [IA-2026-1          ] ⚠     │  │  │ IA-2026-1 · 2026-1           │  │
│  │ Cursada     [2026-1              ]      │  │  │                               │  │
│  └──────────────────────────────────────────┘  │  │ Descripción de la materia en  │  │
│                                                 │  │ **markdown renderizado**…     │  │
│  DESCRIPCIÓN                                   │  │                               │  │
│  ┌──────────────────────────────────────────┐  │  │ 4 materiales · chat con RAG   │  │
│  │ B I ` [enlace] - 1. # >                  │  │  └───────────────────────────────┘  │
│  │ # Bienvenida                              │  │                                     │
│  │ Eres el asistente de…                    │  │  ┌───────────────────────────────┐  │
│  │ …                                        │  │  │ Código de inscripción         │  │
│  └──────────────────────────────────────────┘  │  │  K 7 F 3 Q X   [Copiar]       │  │
│                                                 │  └───────────────────────────────┘  │
│  MODELO E IA            (secciones del doc 05)  │                                     │
│  ┌──────────────────────────────────────────┐  │  ⚠ Los alumnos todavía no ven      │
│  │ Servidor · Modelo · Generación · …      │  │    nada. Publicá la cátedra.       │
│  └──────────────────────────────────────────┘  │                                     │
│                                                 │  [ Probar el chat ]                  │
│  RECUPERACIÓN                                  │                                     │
│  ┌──────────────────────────────────────────┐  │                                     │
│  │ Fragmentos recuperados  [——●——] 5        │  │                                     │
│  │ Tamaño del fragmento   [—●——]  1200      │  │                                     │
│  └──────────────────────────────────────────┘  │                                     │
│                                                 │                                     │
│  INSCRIPCIÓN · ANALÍTICA · PUBLICACIÓN         │                                     │
├────────────────────────────────────────────────┴─────────────────────────────────────┤
│ Guardado hace 2 min · 3 cambios sin publicar · Sin errores                            │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Columna izquierda: `weight(1f)`, contenido con ancho máximo **760 dp** y
  scroll vertical con barra propia (`ThemedVerticalScrollbar`, el mismo patrón
  que el doc 05).
- Columna derecha: **380 dp fijos**, con scroll propio. Es el "panel de vista
  previa".
- El divisor es un `VerticalDivider` de 1 dp (`outlineVariant`).

### 2.2 Ventana angosta (Android, < 600 dp): una columna

```
┌──────────────────────────────┐
│ ← IA-2026-1  [Borrador] ⋮   │
├──────────────────────────────┤
│ Identidad │ Descripción │…  │  ← ScrollableTabRow (una sección a la vez)
├──────────────────────────────┤
│                              │
│  (contenido de la sección)   │
│                              │
├──────────────────────────────┤
│  Guardado ✓        [Guardar] │  ← bottom bar
└──────────────────────────────┘
```

- Las secciones se eligen con un `ScrollableTabRow` (`Identidad`,
  `Descripción`, `Modelo e IA`, `Recuperación`, `Inscripción`, `Analítica`,
  `Publicación`).
- La **vista previa** es un **destino aparte** (se empuja en una pila local de
  navegación, igual que hace el doc 07 en Android): botón "Vista previa" en la
  bottom bar.
- El **"Probar el chat"** es un diálogo a pantalla completa (`Dialog` con
  `usePlatformDefaultWidth = false`).
- El markdown, en angosto, se edita con el campo + conmutador **Escribir |
  Vista previa** (ver §5).

### 2.3 Los dos contenedores, un solo contenido

El contenido de una sección es un composable que no sabe en qué contenedor
está:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/sections/CourseSections.kt
enum class CourseSection(val label: String) {
    IDENTIDAD("Identidad"),
    DESCRIPCION("Descripción"),
    MODELO("Modelo e IA"),
    RECUPERACION("Recuperación"),
    INSCRIPCION("Inscripción"),
    ANALITICA("Analítica"),
    PUBLICACION("Publicación"),
}

@Composable
fun CourseSectionContent(
    section: CourseSection,
    state: CourseEditorState,
    vm: CourseEditorViewModel,
    modifier: Modifier = Modifier,
) { /* when (section) { ... } */ }
```

- **Escritorio**: `Column` con scroll que renderiza `CourseSectionCard` por
  cada sección (acordeón; abiertas por defecto Identidad, Descripción,
  Publicación; el resto cerrado).
- **Angosto**: `Column` con `ScrollableTabRow` + `CourseSectionContent(section)`.

Ninguna sección se escribe dos veces: el desacople está en el `when`.

### 2.4 Barra superior, barra de estado y atajos

**Top app bar (escritorio y angosto)**

| Elemento | Comportamiento |
|---|---|
| Volver | `onBack` |
| Título | `course.shortName` (código · nombre) |
| Subtítulo | cursada + estado de guardado |
| Chip de estado | `Borrador` (ámbar) / `Publicada` (verde) + "Publicada el 12/03/2026" si aplica |
| "Probar el chat" | abre el chat de prueba (§8) |
| "Guardar" | `Button` deshabilitado si no hay cambios; fuerza el guardado inmediato |
| "⋮" | "Publicar" / "Publicar cambios" / "Despublicar" / "Ver la cátedra como alumno" / "Exportar" |

**Barra de estado (abajo, escritorio)**

Una línea con tres datos, en `bodySmall` / `onSurfaceVariant`:

- `Guardado` / `Guardando…` / `Sin guardar` / `No se pudo guardar: <motivo>`.
- `N cambios sin publicar` (sólo si la cátedra está publicada y el borrador
  difiere de la copia publicada) — al tocarlo, abre el diff (§9.3).
- Errores: `N errores` en rojo si hay `CourseIssue` de severidad `ERROR`.

**Atajos de teclado (escritorio)**

| Atajo | Acción |
|---|---|
| `Ctrl/Cmd + S` | Guardar ahora |
| `Ctrl/Cmd + P` | Publicar |
| `Ctrl/Cmd + Enter` | Probar el chat |
| `Ctrl/Cmd + Z / Shift+Z` | Deshacer / rehacer dentro del editor de markdown (buffer local de 50 pasos) |

### 2.5 Qué se ve en la vista previa

Es la **proyección de `PublishedCourse`** (o del borrador, si la cátedra nunca
se publicó, con un banner que lo aclara). En este orden:

1. **Nombre** de la cátedra (`titleLarge`).
2. **`código · cursada`** (`bodySmall`, `onSurfaceVariant`).
3. **Descripción renderizada** con `MarkdownText` (el mismo composable del
   doc 07): el docente ve exactamente lo que va a leer el alumno.
4. **Metadatos de la cátedra**: "N materiales · chat con el material de la
   cátedra".
5. **Código de inscripción** (sólo si el docente tiene `COURSE_VIEW_JOIN_CODE`),
   en monoespaciada, con botón "Copiar".
6. **Banner de estado**:
   - no publicada → ámbar: "Los alumnos todavía no ven nada. Publicá la cátedra
     para que se puedan inscribir con el código."
   - publicada con cambios pendientes → azul: "Hay N cambios sin publicar."
   - publicada y al día → verde: "Publicada el 12/03/2026."

---

## 3. Modelo de dominio (cambios en `:core`)

Todos los cambios son **aditivos**: campos con valor por defecto, para que los
JSON ya guardados sigan deserializando (`KitoruJson` usa `ignoreUnknownKeys` y
`encodeDefaults`).

### 3.1 `Course` — publicación

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt
@Serializable
data class Course(
    val id: String,
    val code: String,
    val name: String,
    val term: String = "",
    val description: String = "",
    val createdAt: Long = 0L,
    val joinCode: String = "",
    val modelPolicy: ModelPolicy = ModelPolicy(),
    val systemPrompt: String = "",
    val topK: Int = 5,
    val chunkSize: Int = 1200,
    val analyticsEnabled: Boolean = true,
    val allowLocalModels: Boolean = true,

    /** NUEVO: copia congelada de lo que ve el alumno. Null = nunca publicada. */
    val publishedSnapshot: PublishedCourse? = null,
) {
    val shortName: String get() = if (code.isBlank()) name else "$code · $name"

    /** ¿La cátedra está publicada? El doc 08 ya consulta `ctx.course.published`. */
    val published: Boolean get() = publishedSnapshot != null
}
```

> El accessor `published` es **calculado** (no un campo serializado): el doc 08
> usa `ctx.course.published` en la condición `CoursePublished` y sigue
> funcionando sin tocar ese documento. La fuente de verdad es la existencia
> del snapshot: no pueden desincronizarse.

### 3.2 `PublishedCourse` — lo que ve el alumno

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/PublishedCourse.kt
package kitoru.domain

/**
 * La cátedra tal como la ve el alumno.
 *
 * Es una **copia congelada**: cuando el docente publica, se copia el borrador
 * de trabajo acá. Los alumnos leen este objeto, nunca el `Course` en edición,
 * para que el docente pueda cambiar la configuración sin que la cursada en
 * curso se entere.
 */
@Serializable
data class PublishedCourse(
    val courseId: String,
    val name: String,
    val code: String,
    val term: String,
    val description: String,
    /** Código de inscripción vigente (el del snapshot, no el del borrador). */
    val joinCode: String,
    /** Política base que la cátedra impone al alumno. */
    val modelPolicy: ModelPolicy,
    val systemPrompt: String,
    val topK: Int,
    val chunkSize: Int,
    val publishedAt: Long,
    /** Id del docente que publicó (trazabilidad). */
    val publishedBy: String = "",
) {
    val shortName: String get() = if (code.isBlank()) name else "$code · $name"
}
```

**Invariante** (verificable con un test): si `course.publishedSnapshot != null`,
entonces `publishedSnapshot.joinCode == course.joinCode`. El servicio de
publicación y el de rotación de código lo garantizan (§6.3).

### 3.3 `CourseValidator` — validación en línea, en `:core`

Puro, sin Compose, testeable. Es la **única** fuente de los mensajes de
validación de la cátedra.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CourseValidator.kt
package kitoru.domain

/** A qué campo del editor pertenece un problema. */
enum class CourseField {
    NAME, CODE, TERM, DESCRIPTION, JOIN_CODE,
    TOP_K, CHUNK_SIZE,
    BASE_URL, MODEL_NAME, MAX_TOKENS, TEMPERATURE,
}

enum class IssueSeverity { ERROR, WARNING }

/**
 * Un problema de la cátedra, con el campo al que pertenece.
 *
 * `ERROR` impide publicar; `WARNING` avisa pero no bloquea.
 */
data class CourseIssue(
    val field: CourseField,
    val severity: IssueSeverity,
    val message: String,
)

object CourseValidator {

    /** Rango del tamaño de fragmento (mismo que el slider del doc 05). */
    const val CHUNK_MIN = 600
    const val CHUNK_MAX = 2400

    /** Rango de fragmentos recuperados (mismo que el slider del doc 05). */
    const val TOP_K_MIN = 1
    const val TOP_K_MAX = 20

    /**
     * Valida la cátedra.
     *
     * @param course borrador de trabajo.
     * @param policy política base (puede venir de `course.modelPolicy`).
     * @param existingCodes códigos de otras cátedras, para detectar duplicados.
     */
    fun validate(
        course: Course,
        policy: ModelPolicy = course.modelPolicy,
        existingCodes: Set<String> = emptySet(),
    ): List<CourseIssue> {
        val out = mutableListOf<CourseIssue>()

        // ---- Identidad ----
        if (course.name.isBlank()) {
            out += CourseIssue(CourseField.NAME, IssueSeverity.ERROR,
                "El nombre de la cátedra no puede estar vacío.")
        } else if (course.name.length > 120) {
            out += CourseIssue(CourseField.NAME, IssueSeverity.WARNING,
                "El nombre es muy largo (${course.name.length} caracteres): se ve cortado en los listados.")
        }

        if (course.code.isBlank()) {
            out += CourseIssue(CourseField.CODE, IssueSeverity.ERROR,
                "El código de la cátedra no puede estar vacío.")
        } else {
            if (!CODE_PATTERN.matches(course.code.trim())) {
                out += CourseIssue(CourseField.CODE, IssueSeverity.ERROR,
                    "El código sólo puede tener letras, números, punto, guion y guion bajo (máximo 24 caracteres).")
            }
            val dup = existingCodes.any { it.equals(course.code.trim(), ignoreCase = true) }
            if (dup) {
                out += CourseIssue(CourseField.CODE, IssueSeverity.ERROR,
                    "Ya existe una cátedra con el código «${course.code.trim()}».")
            }
        }

        if (course.term.isBlank()) {
            out += CourseIssue(CourseField.TERM, IssueSeverity.WARNING,
                "Sin cursada, los alumnos no pueden distinguir esta cátedra de otra.")
        }

        if (course.description.isBlank()) {
            out += CourseIssue(CourseField.DESCRIPTION, IssueSeverity.WARNING,
                "Sin descripción, los alumnos no saben qué esperar de la materia.")
        } else if (course.description.length > 8_000) {
            out += CourseIssue(CourseField.DESCRIPTION, IssueSeverity.WARNING,
                "La descripción es muy larga (${course.description.length} caracteres): se va a recortar en el chat del alumno.")
        }

        // ---- Inscripción ----
        if (course.joinCode.isNotBlank() && !JoinCode.isValid(course.joinCode)) {
            out += CourseIssue(CourseField.JOIN_CODE, IssueSeverity.ERROR,
                "El código de inscripción tiene un formato inválido. Regeneralo.")
        }

        // ---- Recuperación ----
        val topK = policy.topK
        if (topK < TOP_K_MIN || topK > TOP_K_MAX) {
            out += CourseIssue(CourseField.TOP_K, IssueSeverity.ERROR,
                "El número de fragmentos recuperados debe estar entre $TOP_K_MIN y $TOP_K_MAX (actual: $topK).")
        }
        if (course.chunkSize < CHUNK_MIN || course.chunkSize > CHUNK_MAX) {
            out += CourseIssue(CourseField.CHUNK_SIZE, IssueSeverity.ERROR,
                "El tamaño del fragmento debe estar entre $CHUNK_MIN y $CHUNK_MAX caracteres (actual: ${course.chunkSize}).")
        }

        // ---- Política de IA ----
        out += policy.validationIssues().map { issue ->
            CourseIssue(
                field = issue.field.toCourseField(),
                severity = IssueSeverity.ERROR,
                message = issue.message,
            )
        }

        return out
    }

    /** Sólo los errores que impiden publicar. */
    fun blocking(issues: List<CourseIssue>): List<CourseIssue> =
        issues.filter { it.severity == IssueSeverity.ERROR }

    private val CODE_PATTERN = Regex("^[A-Za-z0-9._-]{2,24}$")
}
```

Mensajes con el mismo estilo de `ModelPolicy.validate()`: tercera persona,
"actual: N" cuando hay un valor, punto final.

### 3.4 `ModelPolicy.validationIssues()` — anclar mensajes a campos

`ModelPolicy.validate()` devuelve `List<String>`: sirve para un banner, pero no
para saber **qué campo** marcar. Se agrega una vista con campo, **sin tocar el
comportamiento de `validate()`**:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt (aditivo)

/** Campo de la política al que pertenece un problema. */
enum class PolicyField { MAX_TOKENS, TEMPERATURE, TOP_K, BASE_URL, MODEL_NAME }

/** Problema de la política con el campo asociado. */
data class PolicyIssue(val field: PolicyField, val message: String)

/**
 * Los mismos problemas que [validate], pero con el campo asociado.
 *
 * `validate()` sigue devolviendo exactamente la misma lista, en el mismo
 * orden, con los mismos textos: es `validationIssues().map { it.message }`.
 */
fun validationIssues(): List<PolicyIssue> {
    val out = mutableListOf<PolicyIssue>()
    if (maxTokens <= 0) {
        out += PolicyIssue(PolicyField.MAX_TOKENS,
            "El límite de tokens debe ser mayor que 0 (actual: $maxTokens).")
    }
    if (temperature < 0.0 || temperature > 2.0) {
        out += PolicyIssue(PolicyField.TEMPERATURE,
            "La temperatura debe estar entre 0 y 2 (actual: $temperature).")
    }
    if (topK <= 0) {
        out += PolicyIssue(PolicyField.TOP_K,
            "El número de fragmentos recuperados debe ser mayor que 0 (actual: $topK).")
    }
    if (mode == ModelMode.REMOTE) {
        if (remoteBaseUrl.isBlank()) {
            out += PolicyIssue(PolicyField.BASE_URL,
                "Falta la dirección del servidor de la cátedra.")
        } else if (!remoteBaseUrl.trim().startsWith("http://") &&
            !remoteBaseUrl.trim().startsWith("https://")
        ) {
            out += PolicyIssue(PolicyField.BASE_URL,
                "La dirección del servidor debe empezar con http:// o https://.")
        } else if (modelName.isBlank()) {
            out += PolicyIssue(PolicyField.MODEL_NAME,
                "Falta el nombre del modelo en el servidor.")
        }
    }
    return out
}

/** Sin cambios en el comportamiento: mismos mensajes, mismo orden. */
fun validate(): List<String> = validationIssues().map { it.message }
```

El mapeo `PolicyField → CourseField` es biunívoco (`BASE_URL`, `MODEL_NAME`,
`MAX_TOKENS`, `TEMPERATURE`, `TOP_K` existen en ambos); el mapeo vive en
`CourseValidator.toCourseField()`.

### 3.5 `JoinCode` — generación y normalización

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/JoinCode.kt
package kitoru.domain

import kotlin.random.Random

/**
 * El código con el que los alumnos se anotan a una cátedra.
 *
 * Formato: **6 caracteres** de un alfabeto de 32 símbolos (los de Crockford:
 * sin `0`, `O`, `1`, `I`, `L`, `U`). Se dicta por teléfono y se teclea en un
 * móvil: 32^6 ≈ 1.073.741.824 combinaciones.
 */
object JoinCode {

    const val LENGTH = 6

    /** Alfabeto sin caracteres ambiguos (0/O, 1/I/L, U/V). */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Genera un código. No chequea unicidad: eso lo hace [generateUnique]. */
    fun generate(random: Random = Random.Default): String =
        buildString {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /**
     * Genera un código que no exista todavía.
     *
     * @throws IllegalStateException si no encuentra uno libre en 20 intentos
     *   (mensaje en español, como el resto del proyecto).
     */
    fun generateUnique(
        existing: Set<String>,
        random: Random = Random.Default,
    ): String {
        repeat(20) {
            val code = generate(random)
            if (existing.none { it.equals(code, ignoreCase = true) }) return code
        }
        throw IllegalStateException("No se pudo generar un código de inscripción único. Probá de nuevo.")
    }

    /** ¿El código tiene el formato correcto? */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }

    /**
     * Normaliza lo que escribió el alumno (o el docente): quita espacios y
     * guiones, pasa a mayúsculas y aplica las reglas de Crockford
     * (`I`/`L` → `1`, `O` → `0`, `U` → `V`).
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(LENGTH)
        for (ch in raw.uppercase()) {
            when (ch) {
                ' ', '-', '_' -> Unit
                'I', 'L' -> sb.append('1')
                'O' -> sb.append('0')
                'U' -> sb.append('V')
                else -> sb.append(ch)
            }
            if (sb.length == LENGTH) break
        }
        return sb.toString()
    }
}
```

`normalize` + `isValid` son la base del flujo de inscripción del alumno: el
alumno teclea "k7f-3qx" y queda "K7F3QX".

### 3.6 `CourseDiff` — qué falta publicar

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CourseDiff.kt
package kitoru.domain

/**
 * Diferencia entre el borrador de trabajo y la copia publicada.
 *
 * Es puro y se usa para (a) avisar "N cambios sin publicar" y (b) mostrar el
 * detalle antes de volver a publicar.
 */
data class CourseDiff(
    val name: Boolean = false,
    val code: Boolean = false,
    val term: Boolean = false,
    val description: Boolean = false,
    val joinCode: Boolean = false,
    val policy: Boolean = false,
    val systemPrompt: Boolean = false,
    val topK: Boolean = false,
    val chunkSize: Boolean = false,
    val allowLocalModels: Boolean = false,
    val analyticsEnabled: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !name && !code && !term && !description && !joinCode && !policy &&
            !systemPrompt && !topK && !chunkSize && !allowLocalModels && !analyticsEnabled

    /** Cuántos campos cambiaron (para el contador). */
    val count: Int
        get() = listOf(name, code, term, description, joinCode, policy, systemPrompt,
            topK, chunkSize, allowLocalModels, analyticsEnabled).count { it }

    companion object {
        /** Compara el borrador con la copia publicada (null = nunca publicada). */
        fun between(working: Course, published: PublishedCourse?): CourseDiff {
            if (published == null) return CourseDiff(
                name = true, code = true, term = true, description = true, joinCode = true,
                policy = true, systemPrompt = true, topK = true, chunkSize = true,
                allowLocalModels = true, analyticsEnabled = true,
            )
            return CourseDiff(
                name = working.name != published.name,
                code = working.code != published.code,
                term = working.term != published.term,
                description = working.description != published.description,
                joinCode = working.joinCode != published.joinCode,
                policy = working.modelPolicy != published.modelPolicy,
                systemPrompt = working.systemPrompt != published.systemPrompt,
                topK = working.modelPolicy.topK != published.topK,
                chunkSize = working.chunkSize != published.chunkSize,
                allowLocalModels = working.allowLocalModels != published.allowLocalModels,
                analyticsEnabled = working.analyticsEnabled != published.analyticsEnabled,
            )
        }
    }
}
```

---

## 4. Los datos editables, control por control

### 4.1 Tabla maestra

Un control por campo. "Dueño" = la pantalla que lo monta.

| # | Campo | Control | Tipo | Dónde vive | Dueño | Validación en línea |
|---|---|---|---|---|---|---|
| 1 | Nombre de la cátedra | `OutlinedTextField` (1 línea) | `String` | `Course.name` | Editor | `CourseField.NAME` |
| 2 | Código | `OutlinedTextField` (1 línea, mayúsculas) | `String` | `Course.code` | Editor | `CourseField.CODE` |
| 3 | Cursada | `OutlinedTextField` (1 línea) | `String` | `Course.term` | Editor | `CourseField.TERM` |
| 4 | Descripción | `MarkdownEditor` | `String` (markdown) | `Course.description` | Editor | `CourseField.DESCRIPTION` |
| 5 | Código de inscripción | campo de sólo lectura + "Copiar" + "Rotar" | `String` | `Course.joinCode` | Editor | `CourseField.JOIN_CODE` |
| 6 | Servidor / modelo / generación / herramientas / bloqueos | secciones del doc 05 | `ModelPolicy` | `Course.modelPolicy` | **Doc 05** (montado acá) | `BASE_URL`, `MODEL_NAME`, `MAX_TOKENS`, `TEMPERATURE` |
| 7 | Permitir IA local | `Switch` | `Boolean` | `Course.allowLocalModels` | **Doc 05** (`LocalAiSection`, montado acá) | — |
| 8 | Fragmentos recuperados | `Slider` 1..20 + número | `Int` | `ModelPolicy.topK` | **Doc 05** (`RagSection`) | `CourseField.TOP_K` |
| 9 | Tamaño del fragmento | `Slider` 600..2400 + número | `Int` | `Course.chunkSize` | **Doc 05** (`RagSection`) | `CourseField.CHUNK_SIZE` |
| 10 | Analítica de la cátedra | `Switch` | `Boolean` | `Course.analyticsEnabled` | Editor | — |
| 11 | Publicación | botones + diff | `PublishedCourse?` | `Course.publishedSnapshot` | Editor | — |
| 12 | Instrucciones del modelo | sólo lectura + enlace | `String` | `Course.systemPrompt` | **Doc 04** | — |

> Filas 6–9: el editor **monta** los composables del doc 05 (mismo ViewModel,
> misma sección). No hay dos controles del mismo valor en la app (D4).
> La fila 12 se muestra como un resumen de dos líneas con el botón
> "Editar instrucciones →" (doc 04).

### 4.2 Identidad

```
▾ IDENTIDAD
  Nombre   [Inteligencia Artificial            ]
  Código   [IA-2026-1                          ]  ayuda: "El código con el que los alumnos ven la materia."
  Cursada  [2026-1                             ]  ayuda: "Por ejemplo 2026-1. Opcional."
```

- **Nombre**: `singleLine = true`. Error debajo del campo cuando
  `CourseField.NAME` tiene un `ERROR`.
- **Código**: `singleLine = true`, `KeyboardOptions(capitalization = Characters)`.
  En `onValueChange` se filtra: se pasan sólo `[A-Za-z0-9._-]` y se corta a 24
  caracteres. Si el código ya existe en otra cátedra, el error aparece
  **debajo del campo** con el mensaje del validador, no en un banner.
- **Cursada**: `singleLine = true`, sin máscara (el docente escribe "2026-1",
  "1er cuatrimestre", etc.).
- Sugerencia bajo el código: *"El código con el que los alumnos ven la materia.
  Se puede cambiar; el de inscripción es otro."* — evita confundir código de
  materia con código de inscripción.

### 4.3 Descripción (markdown)

Se detalla en §5. En la sección:

```
▾ DESCRIPCIÓN
  [B] [I] [`] [enlace] [-] [1.] [#] [>]       ayuda: 2.140 caracteres
  ┌────────────────────────────────────────────────────────┐
  │ # Bienvenida a la materia                             │
  │                                                        │
  │ Esta cátedra cubre **búsqueda heurística** y…        │
  └────────────────────────────────────────────────────────┘
```

El render en vivo **no** se muestra en escritorio (la vista previa de la
columna derecha ya muestra el markdown renderizado de toda la cátedra); en
angosto, el conmutador "Escribir | Vista previa" lo muestra.

### 4.4 Inscripción

```
▾ INSCRIPCIÓN
  Código de inscripción
  ┌────────────────────────────────────────────┐
  │  K 7 F 3 Q X                                │  [Copiar]  [Rotar]
  └────────────────────────────────────────────┘
  Los alumnos se anotan con este código. No se muestra en las listas.

  6 caracteres · 1.073.741.824 combinaciones posibles
```

- El campo es de **sólo lectura** (monoespaciada, con separación entre letras
  para que se pueda dictar).
- "Copiar" → puerto `Clipboard` (§6.4) → snackbar "Código copiado: K7F3QX".
- "Rotar" → diálogo de confirmación (§6.3).
- Si el docente **no** tiene `COURSE_VIEW_JOIN_CODE` (ayudante), toda la
  sección se oculta y se muestra: *"El código de inscripción sólo lo ve el
  profesor titular."*
- El código **se genera solo** al crear la cátedra (ver §6.2). Nunca se escribe
  a mano: escribir un código elegido por el docente abre la puerta a colisiones
  y a códigos adivinables.

### 4.5 Analítica

```
▾ ANALÍTICA
  [x] Analítica de la cátedra
      El docente ve estadísticas de uso y calidad de las respuestas
      (citas verificadas contra el material). Los alumnos pueden dejar
      sus chats ocultos: eso lo decide cada uno al anotarse.
```

- `Switch` → `Course.analyticsEnabled`.
- El texto aclara que la analítica es ** agregada y del docente**, y que cada
  alumno puede ocultar sus conversaciones (matriz de `CONTEXTO.md`).
- Si la cátedra está publicada, apagar la analítica **no** borra nada: sólo
  deja de mostrar los agregados (mismo criterio que el doc 07, caso borde
  "cátedra con `analyticsEnabled = false`").
- Si la analítica está apagada y el docente entra a los chats (doc 07), el
  panel de métricas agregadas muestra el aviso; esa pantalla **sigue
  disponible**: es el derecho del docente.

### 4.6 Modelo e IA (composición con el doc 05)

```
▾ MODELO E IA
  Servidor de la cátedra      [Completo]
  Modelo                      [3 presets]
  Generación                  [0.20 · 1024]
  IA local                    [Permitida]
  Permisos de herramientas    [1 de 2 activa]
  Lo que el alumno puede tocar[3 bloqueados]
```

Cada bloque es un `CourseSectionCard` con el **composable del doc 05**
(`ServerSection`, `ModelSection`, `GenerationSection`, `ToolsSection`,
`StudentOverridesSection`, `LocalAiSection`), alimentados por el **mismo
`ModelConfigViewModel`**. Reglas:

- El doc 05 debe exponer las secciones como **composables con estado elevado**
  (`ModelSection(state, onChange, …)`), no como funciones que leen el VM por
  dentro: es lo que permite montarlas en dos lugares distintos.
- El `RagSection` del doc 05 (`topK` + `chunkSize`) se muestra **dentro** de
  "Recuperación" en esta pantalla (§4.7), por el mismo motivo.
- Un mensaje de estado al pie: *"La configuración avanzada (presets, claves
  por alumno, rangos) está en Configuración del modelo →"*.
- Los cambios de la política se guardan con el **mismo** botón "Guardar y
  aplicar" del doc 05 (o sea: escriben en el borrador de trabajo de esta
  pantalla, no en la publicación). La publicación sigue siendo explícita.

### 4.7 Recuperación (topK y chunkSize)

```
▾ RECUPERACIÓN
  Fragmentos recuperados
  [●———————] 5
  Cuántos fragmentos del material se le pasan al modelo en cada pregunta.
  Más fragmentos = más contexto y más costo. (1 a 20)

  Tamaño del fragmento
  [——●——————] 1200
  Cada cuánto se corta el material al indexarlo. Cambiarlo exige reindexar
  los materiales. (600 a 2400)
```

- `Slider` + campo numérico editable (mismo patrón del doc 05: el slider
  mueve, el número muestra).
- El valor se escribe en `ModelPolicy.topK` y `Course.chunkSize`
  respectivamente. `Course.topK` queda **legacy** (doc 05) y no se muestra.
- Si cambia `chunkSize`, aparece un aviso al pie de la sección:
  *"El material va a tener que reindexarse con el tamaño nuevo."* con botón
  "Reindexar ahora" que lleva a la pantalla de materiales (doc 03).
- **Permisos**: según el doc 08, `COURSE_CONFIG_RAG` es condicional
  ("la cátedra está publicada"). Si la cátedra no está publicada, los dos
  controles se muestran **deshabilitados** con el motivo:
  *"Sólo se puede ajustar la recuperación con la cátedra publicada."*

### 4.8 IA local

El control es el del doc 05 (`LocalAiSection`, fila 7.1): un `Switch` sobre
`Course.allowLocalModels`. En esta pantalla se muestra dentro de "Modelo e IA"
y su texto de ayuda ya está en el doc 05 (§10.2).

Lo que esta pantalla **suma** es la consecuencia operativa, visible en la
vista previa: si la IA local está permitida, la vista previa muestra el chip
"El alumno puede usar IA local en su dispositivo" (con la nota de que en ese
caso el docente ve los chats sólo si el alumno los comparte).

---

## 5. El editor de descripción en markdown

### 5.1 Qué se porta de FunshiEngineAI

`FunshiEngineAI/frontend/src/main/kotlin/app/ui/Markdown.kt` tiene dos mitades
muy distintas:

| Parte | ¿Portable? | Decisión |
|---|---|---|
| `MdNode`, `parseMarkdown`, `buildInlineAnnotated` | Sí: Kotlin puro, sin dependencias | **Se porta tal cual** a `:ui` común. Es la base de la vista previa y del chat. |
| `MarkdownText` y el render (`HeadingText`, `BulletsText`, `TableText`…) | Sí, salvo las fórmulas | **Se porta** usando el `LatexText` del doc 07 en lugar de `MathBlock`. |
| `MathRender` (JLaTeXMath + `java.awt`) | No | **No se porta** (D13, decisión D5 del doc 07): el LaTeX se muestra como texto monoespaciado. |
| `splitMath` (puro) | Sí | Se porta (doc 07 ya lo define en `MathSplit.kt`). |
| `CodeBlock.kt`: resaltador (`tokenize`, `highlightCode`, `KEYWORDS_BY_LANG`) | Sí: Kotlin puro | **Se porta** el resaltador. |
| `CodeBlock.kt`: `saveAsFile` con `JFileChooser` y `copyToClipboard` con AWT | No | Se reemplazan por los puertos `FileDialogs` (doc 07) y `Clipboard` (§6.4). |

Ubicación (coherente con el doc 07, que ya reserva estos nombres):

```
ui/src/commonMain/kotlin/kitoru/ui/markdown/
├── MarkdownText.kt     ← MarkdownText, parseMarkdown, MdNode, buildInlineAnnotated
├── MathSplit.kt        ← splitMath, MathSegment
├── LatexText.kt        ← LatexText (LaTeX como texto, sin java.awt)
└── CodeBlock.kt        ← CodeBlock(lang, code, closed, onCopy, onSave)
```

> `parseMarkdown` y `buildInlineAnnotated` quedan **públicos** en `kitoru.ui.markdown`:
> el editor de la cátedra los usa para la vista previa y para contar estructuras.

**El parser no se toca.** Es el mismo de FunshiEngineAI, con sus casos borde ya
resueltos: bloque de código sin cerrar (`closed = false`), tabla sin fila de
separación, lista mal formada, fórmula sin cerrar (que no se dibuja para que
el streaming no muestre media fórmula).

### 5.2 `MarkdownEditor`: layout y modos

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/components/MarkdownEditor.kt
package kitoru.ui.teacher.course.components

/** Cómo se muestra el editor de markdown. */
enum class MarkdownEditorMode { SPLIT, WRITE, PREVIEW }

@Composable
fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Describí la cátedra…",
    mode: MarkdownEditorMode = MarkdownEditorMode.SPLIT,
    onModeChange: (MarkdownEditorMode) -> Unit = {},
    /** Límite blando: avisa, no bloquea. */
    softLimit: Int = 8_000,
    enabled: Boolean = true,
)
```

**Escritorio (`SPLIT`)**

```
┌───────────────────────────────┬────────────────────────────────┐
│ B I ` [] - 1. # >             │  # Bienvenida a la materia     │
│                               │                                │
│ (campo mono, scroll propio)  │  (MarkdownText, scroll propio) │
└───────────────────────────────┴────────────────────────────────┘
```

- Mitad y mitad, con `VerticalDivider`.
- El campo: `OutlinedTextField`, `FontFamily.Monospace`, 13.sp, `minLines = 14`,
  con scroll propio.
- La vista previa: `MarkdownText(value)` sobre `surfaceContainerLow`, con
  padding de 12 dp.

**Angosto (`WRITE` / `PREVIEW`)**

```
┌───────────────────────────────┐
│ [ Escribir ] [ Vista previa ] │  ← SingleChoiceSegmentedButtonRow
├───────────────────────────────┤
│ (campo)          o            │
│                  (MarkdownText)│
└───────────────────────────────┘
```

- Un conmutador arriba del área; el conmutador de modo **no** aparece en
  escritorio (siempre `SPLIT`), salvo que la ventana sea angosta: el
  `MarkdownEditor` decide el modo por defecto según el ancho disponible
  (`BoxWithConstraints`), y el docente lo puede forzar a `WRITE` o `PREVIEW`
  en cualquier momento (el modo es estado de UI, no del dominio).

**Estado interno**: el editor guarda un `TextFieldValue` (no un `String`) para
conocer la selección del cursor (necesaria para la barra de formato). Se
sincroniza con el valor externo:

```kotlin
var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
LaunchedEffect(value) {
    if (value != fieldValue.text) fieldValue = fieldValue.copy(text = value)
}
```

### 5.3 La barra de formato

Botones `IconButton` con `contentDescription` en español. Operan sobre la
selección del `TextFieldValue`:

| Botón | Acción | Atajo |
|---|---|---|
| **B** | envolver la selección en `**…**` (o insertar `****` con el cursor en el medio) | `Ctrl+B` |
| *I* | envolver en `*…*` | `Ctrl+I` |
| `` ` `` | envolver en `` `…` `` | `Ctrl+E` |
| Enlace | `[selección](url)` con `url` vacío seleccionado | `Ctrl+K` |
| Lista | prefijar cada línea con `- ` | |
| 1. | prefijar con `1. ` | |
| `#` | prefijar la línea con `## ` | |
| `>` | prefijar con `> ` (cita) | |

Implementación (puro, testeable):

```kotlin
/** Aplica una acción de formato al texto con la selección actual. */
object MarkdownFormat {

    /** Envuelve la selección. Si no hay selección, inserta el marcador con el cursor en el medio. */
    fun wrap(value: TextFieldValue, marker: String): TextFieldValue {
        val text = value.text
        val sel = value.selection
        if (sel.collapsed) {
            val ins = "$marker$marker"
            val newText = text.substring(0, sel.start) + ins + text.substring(sel.end)
            val cursor = sel.start + marker.length
            return value.copy(text = newText, selection = TextRange(cursor))
        }
        val selected = text.substring(sel.start, sel.end)
        val newText = text.substring(0, sel.start) + marker + selected + marker + text.substring(sel.end)
        return value.copy(
            text = newText,
            selection = TextRange(sel.start + marker.length, sel.end + marker.length),
        )
    }

    /** Prefija cada línea de la selección. */
    fun prefixLines(value: TextFieldValue, prefix: String): TextFieldValue { /* … */ }
}
```

- El formato **nunca** borra texto: si no hay nada seleccionado, se insertan
  los marcadores con el cursor en el medio.
- Es texto plano con insertar marcadores: no hay edición enriquecida (mismo
  criterio que el doc 04 para el prompt).

### 5.4 Límites y avisos

- **Límite blando de 8.000 caracteres** (mismo número que el warning del
  validador): al superarlo aparece un aviso ámbar bajo el campo: *"La
  descripción es muy larga (N caracteres): se va a recortar en el chat del
  alumno."* No bloquea la escritura ni el guardado.
- El contador de caracteres vive en la barra de formato: `"2.140 caracteres"`.
- La vista previa se recalcula en cada tecla: `remember(value) { parseMarkdown(value) }`.
  Con 8.000 caracteres el parseo es despreciable; no hace falta debounce. Si
  en el futuro se admiten descripciones de 100.000 caracteres, ahí sí se
  debouncea el render (el `MarkdownText` del doc 07 ya cachea con `remember`).

---

## 6. El código de inscripción

### 6.1 Formato

- 6 caracteres, alfabeto Crockford de 32 símbolos: `ABCDEFGHJKMNPQRSTUVWXYZ23456789`.
- Sin `0/O`, `1/I/L`, `U/V` → no se confunde al dictar ni al teclear.
- 32^6 = 1.073.741.824 combinaciones. En el futuro, cuando el alta sea por
  servidor, el `Kitoru-Server` tiene que **limitar los intentos** por código
  y por IP (nota para el doc del server).
- Se muestra con espacio entre letras (monoespaciada) para poder leerlo en
  voz alta; en disco se guarda pegado (`K7F3QX`).

### 6.2 Generar

Se genera **al crear la cátedra** (pantalla de alta, no es de este doc pero
comparte el servicio) y con el botón "Rotar" después.

```kotlin
// En el servicio de cátedras (o en el ViewModel, si no hay servicio):
suspend fun ensureJoinCode(courseId: String) {
    val existing = courses.list().map { it.joinCode }.filter { it.isNotBlank() }.toSet()
    val code = JoinCode.generateUnique(existing)
    courses.save(course.copy(joinCode = code))
}
```

Si `JoinCode.generateUnique` lanza (no encontró uno libre en 20 intentos), la
UI muestra el mensaje tal cual: *"No se pudo generar un código de inscripción
único. Probá de nuevo."*

### 6.3 Rotar

Diálogo de confirmación (con el motivo, no un `Alert` pelado):

```
┌ Rotar el código de inscripción ────────────────────────────────────┐
│ El código actual (K7F3QX) va a dejar de servir para que los       │
│ alumnos se anoten.                                                │
│                                                                     │
│ Los 48 alumnos ya anotados siguen entrando con su cuenta: el       │
│ código sólo se usa al anotarse.                                    │
│                                                                     │
│ Hay que comunicar el código nuevo.                                 │
│                                                                     │
│                              [Cancelar]  [Generar uno nuevo]        │
└─────────────────────────────────────────────────────────────────────┘
```

Al confirmar:

1. Se genera el código nuevo (`JoinCode.generateUnique`).
2. Se escribe en **las dos** copias: `Course.joinCode` (borrador) y
   `Course.publishedSnapshot.joinCode` (la que el alumno teclea), en **un solo
   guardado** — así el invariante `snapshot.joinCode == course.joinCode` se
   mantiene y el código viejo deja de servir **de inmediato**, sin esperar a
   republicar.
3. Se copia al portapapeles y se avisa: "Código nuevo: K7F3QX (copiado)".

Si la cátedra **no** está publicada, sólo se actualiza el borrador.

Permiso: `COURSE_ROTATE_JOIN_CODE` (titular; el ayudante no lo tiene, según el
doc 08).

### 6.4 Copiar: el puerto `Clipboard`

El copiado lo dispara un ViewModel (o un callback de snackbar), no
necesariamente un composable: por eso hace falta un puerto y no sólo
`LocalClipboardManager`.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/Clipboard.kt
package kitoru.ui.platform

/**
 * Copia texto al portapapeles del sistema.
 *
 * Es un puerto `expect/actual` (y no `LocalClipboardManager` de Compose) por
 * dos razones:
 *  1. se llama desde el ViewModel, fuera de una composición;
 *  2. devuelve si la copia **funcionó**, para poder avisar "No se pudo copiar"
 *     en vez de mentir con un "Copiado".
 */
interface ClipboardWriter {
    /** @return true si el texto quedó en el portapapeles. */
    fun copy(text: String): Boolean
}

expect fun systemClipboard(): ClipboardWriter
```

**Escritorio** (`ui/src/desktopMain/kotlin/kitoru/ui/platform/Clipboard.desktop.kt`):

```kotlin
package kitoru.ui.platform

import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

actual fun systemClipboard(): ClipboardWriter = DesktopClipboard

private object DesktopClipboard : ClipboardWriter {
    override fun copy(text: String): Boolean = try {
        // El portapapeles de AWT se toca desde el EDT.
        SwingUtilities.invokeLater {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
        }
        true
    } catch (_: HeadlessException) {
        false   // servidor sin display: no se puede copiar
    } catch (_: Exception) {
        false
    }
}
```

> El paquete correcto es `java.awt.datatransfer` (`StringSelection`), no
> `java.awt.dataterminal`.

**Android** (`ui/src/androidMain/kotlin/kitoru/ui/platform/Clipboard.android.kt`):

```kotlin
package kitoru.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual fun systemClipboard(): ClipboardWriter = AndroidClipboard

private object AndroidClipboard : ClipboardWriter {
    override fun copy(text: String): Boolean = try {
        val ctx = KitoruAndroid.appContext
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Kitoru", text))
        true
    } catch (_: Exception) {
        false
    }
}
```

El `Context` de la aplicación se inyecta una vez, igual que
`KitoruPaths.setDataDir`:

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/KitoruAndroid.kt
package kitoru.ui.platform

import android.content.Context

/** El `Context` de la aplicación, fijado al arrancar. */
object KitoruAndroid {
    lateinit var appContext: Context
        private set
    fun attach(context: Context) { appContext = context.applicationContext }
}
```

> En Android 13+ el sistema muestra su propio aviso al copiar; el snackbar de
> la app se puede seguir mostrando (es apenas un duplicado visual) o se
> puede omitir en API ≥ 33. Se elige **omitir el snackbar en API ≥ 33**.

Alternativa válida: `LocalClipboardManager` de Compose (que ya usa
FunshiEngineAI en `ChatPane.kt`) alcanza para el copiado **dentro** de una
composición. Se elige el puerto para poder copiar desde el ViewModel y para
saber si funcionó.

---

## 7. Guardado

### 7.1 Decisión

**Autoguardado con debounce de 600 ms**, más un botón "Guardar" explícito.

Por qué autoguardado y no sólo botón:

- el docente escribe la descripción, se acuerda de otra cosa, cambia de
  pantalla y vuelve: no puede perder dos párrafos;
- el editor es largo (7 secciones) y el scroll es largo: es fácil olvidar que
  abajo hay un botón;
- el almacenamiento es local (un JSON) y el costo de guardar es despreciable.

Por qué el botón igual:

- el gesto explícito genera confianza ("ya está guardado");
- es la acción previa a "Publicar";
- permite forzar el guardado antes de hacer algo riesgoso (rotar el código).

El autoguardado **nunca** publica: guardar toca el borrador de trabajo;
publicar es otro botón, con su diálogo.

### 7.2 El patrón

Estado en el ViewModel:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/CourseEditorViewModel.kt
class CourseEditorViewModel(
    private val courseId: String,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val clipboard: ClipboardWriter,
    private val scope: CoroutineScope,
) {
    /** Estado de la pantalla. */
    var state by mutableStateOf(CourseEditorState())
        private set

    /**
     * El borrador de trabajo.
     *
     * Es un `mutableStateOf` y no un `StateFlow` a propósito: el debounce
     * vive en un `LaunchedEffect` de la vista, que se reinicia en cada
     * cambio (D8). Con un Flow haría falta `snapshotFlow { … }.debounce(...)`,
     * que exige `@OptIn(FlowPreview::class)`.
     */
    var draft by mutableStateOf(Course())
        private set

    /** El `Course` tal como está en disco: sirve para el diff y para descartar. */
    private var persisted: Course? = null

    private val saveMutex = Mutex()

    fun edit(block: (Course) -> Course) {
        draft = block(draft)
        state = state.copy(dirty = draft != persisted, issues = validate(draft))
    }

    /** Guarda el borrador (idempotente). */
    suspend fun flush() {
        if (persisted == draft) return
        saveMutex.withLock {
            val toSave = draft
            state = state.copy(saving = true, saveError = null)
            try {
                courses.save(toSave)
                persisted = toSave
                state = state.copy(
                    dirty = false,
                    saving = false,
                    lastSavedAt = nowMillis(),
                    published = toSave.publishedSnapshot,
                    diff = CourseDiff.between(toSave, toSave.publishedSnapshot),
                )
            } catch (e: Exception) {
                state = state.copy(
                    saving = false,
                    saveError = "No se pudo guardar: ${e.message ?: "error desconocido"}",
                )
            }
        }
    }
}
```

Y el debounce, **en la vista**:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/CourseEditorScreen.kt
private const val AUTOSAVE_DEBOUNCE_MS = 600L

@Composable
fun CourseEditorRoute(
    vm: CourseEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = vm.draft
    val state by vm.state

    // Autoguardado con debounce.
    //
    // El LaunchedEffect está claveado en el borrador: cada tecla cambia el
    // borrador, cancela el efecto anterior (y su delay) y arranca uno nuevo.
    // Sólo sobrevive el último, que es el que guarda.
    LaunchedEffect(draft) {
        delay(AUTOSAVE_DEBOUNCE_MS)
        vm.flush()          // no hace nada si el borrador no cambió
    }

    // Guardado explícito: el botón y Ctrl/Cmd+S.
    // (El efecto de arriba ya dejó el estado en "guardado" 600 ms después.)

    CourseEditorScreen(
        state = state,
        onEdit = vm::edit,
        onSave = { scope.launch { vm.flush() } },
        onBack = onBack,
        modifier = modifier,
    )
}
```

Detalles que importan:

- `vm.flush()` es **idempotente**: si el borrador no cambió, no escribe.
- El `Mutex` evita dos escrituras simultáneas (por ejemplo, autoguardado +
  "Publicar" al mismo tiempo).
- Tras guardar, `state.dirty = false` y `state.lastSavedAt = nowMillis()`.
- Si el guardado falla (disco lleno, permisos), `saveError` queda con un
  mensaje en español y **el borrador sigue intacto**: no se pierde nada y el
  docente ve el error en la barra de estado.

### 7.3 Estado sucio

`dirty` se calcula comparando estructuralmente el borrador con la copia en
disco (`Course` es un `data class`; los `Set<LockedField>` comparan bien).

| Situación | Texto de la barra de estado | Botón "Guardar" |
|---|---|---|
| Sin cambios | "Guardado hace 2 min" (o "Guardado") | deshabilitado |
| Escribiendo | "Sin guardar" | habilitado |
| Guardando | "Guardando…" | deshabilitado |
| Error | "No se pudo guardar: <motivo>" (rojo) | habilitado |
| Borrador ≠ publicación (cátedra publicada) | "N cambios sin publicar" | habilitado + "Publicar cambios" |

El "hace X min" se formatea con el helper de formato que el doc 07 propone en
`kitoru/ui/ui/Format.kt` (`formatRelative`).

### 7.4 Cierre de la app sin guardar

**Escritorio.** La ventana de Compose Desktop tiene `onCloseRequest`. Ahí se
vuelca el borrador antes de cerrar:

```kotlin
// desktop/src/main/kotlin/.../Main.kt (esquema)
window = Window(onCloseRequest = {
    val vm = editorVm
    if (vm == null || !vm.state.dirty) {
        window.close()          // nothing to flush
    } else {
        // The write is a local JSON file: a few ms. runBlocking is
        // acceptable here precisely because it is short and local.
        runBlocking { vm.flush() }
        window.close()
    }
})
```

- Si el `flush` falla, se **no** cierra: se muestra un diálogo "No se pudo
  guardar la cátedra. Los cambios quedaron en memoria. ¿Cerrar igual?" con
  "Cerrar igual" / "Reintentar".
- En modo lectura (ayudante) `dirty` siempre es `false`: no hay nada que
  volcar.

**Android.** El proceso puede morir sin aviso (el sistema mata la app en
segundo plano). La protección real es el autoguardado: la pérdida máxima es de
**600 ms de tecleo**. No se intenta un `onStop` porque no es confiable
(`onStop` no se dispara si el proceso muere).

Además, en Android el `Back` del sistema dentro del editor pide confirmación
sólo si `dirty` es `true`:

```
┌───────────────────────────────┐
│ ¿Salir sin guardar?           │
│ Los cambios se guardan solos   │
│ en 600 ms, pero todavía no se  │
│ guardaron.                    │
│              [Seguir editando] [Salir] │
└───────────────────────────────┘
```

---

## 8. Vista previa y "Probar el chat"

### 8.1 La vista previa

Ya está descripta en §2.5. Se implementa con un composable que **no** consulta
el repositorio: recibe el estado y proyecta.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/preview/StudentCoursePreview.kt
@Composable
fun StudentCoursePreview(
    course: Course,                 // borrador o publicado, según el caso
    published: PublishedCourse?,   // la copia congelada (si existe)
    enrollmentCount: Int,
    materialCount: Int,
    canViewJoinCode: Boolean,
    onCopyJoinCode: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Proyección: si `published != null`, se dibuja **la copia publicada** (lo que
ve el alumno hoy); si no, se dibuja el **borrador** con el banner ámbar
"los alumnos todavía no ven nada". Nunca se dibuja el borrador con el aspecto
del publicado: el docente tiene que ver la diferencia.

### 8.2 El botón "Probar el chat"

Abre el chat del **docente** dentro de la cátedra, con:

- el **material real** de la cátedra (los materiales visibles, indexados);
- la **config del borrador** (la `ModelPolicy` en edición, el `topK` y el
  `chunkSize` en edición, el prompt guardado);
- como **docente**, no como alumno: los chats del docente no se mezclan con los
  de los alumnos.

Es el smoke test del ciclo de diseño: **probar → corregir → publicar**.

### 8.3 Flujo técnico

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/CourseChatProbe.kt
package kitoru.ui.teacher.course

/**
 * El chat de prueba del docente.
 *
 * Corre contra la **config del borrador** y el material real. Los hilos que
 * crea quedan marcados como `isSmokeTest` para que no entren en la analítica
 * de la cátedra.
 */
class CourseChatProbe(
    private val courseId: String,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
) {
    /**
     * Prepara el motor con el borrador.
     *
     * @param policy política en edición.
     * @param chunkSize tamaño de fragmento en edición.
     * @param materials materiales visibles de la cátedra.
     * @param systemPrompt prompt vigente de la cátedra.
     */
    suspend fun prepare(
        policy: ModelPolicy,
        chunkSize: Int,
        materials: List<Material>,
        systemPrompt: String,
    ): PreparedProbe {
        val cfg = RagConfig(
            chunkSize = chunkSize,
            topK = policy.topK,
            historyTurns = 6,
        )
        val kb = KnowledgeBase(cfg)
        kb.sources = listOf(CourseMaterialSource(materials))
        kb.sync()
        val gateway = when (policy.mode) {
            ModelMode.REMOTE -> OpenAiCompatibleGateway(policy.toGatewayConfig())
            ModelMode.LOCAL -> localModelService().gateway(cfg)   // desktop; Android: error claro
        }
        val engine = RagEngine(cfg, kb, gateway) { systemPrompt }
        return PreparedProbe(cfg, kb, engine)
    }
}
```

Detalles:

- **Usa la `KnowledgeBase` real** (el mismo `CourseMaterialSource` que usa el
  chat del alumno) → el smoke test mide el mismo RAG que van a medir los
  alumnos, con el mismo `chunkSize` y el mismo `topK` del borrador.
- Si `policy.validationIssues()` no está vacío, el botón **no** se habilita y
  el motivo es el primer mensaje del validador ("Falta la dirección del
  servidor de la cátedra.").
- Si no hay materiales visibles, el chat arranca con un aviso: *"La cátedra
  no tiene material visible: las respuestas saldrán sin contexto."*
- El chat **no** se persiste como hilo de alumno: se crea un `ChatThread` con
  `authorId` = id del docente, `title` = "Prueba del docente" y
  **`isSmokeTest = true`** (campo nuevo en `ChatThread`, §10.1).

### 8.4 Aislamiento del smoke test

`AnalyticsAnalyzer` (`:core`) separa por rol: los hilos del docente no
entran en las métricas de los alumnos, pero sí aparecen en `perStudent` (con
rol `TITULAR`). Para que el smoke test **no** contamine nada:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (aditivo)
/**
 * Si el hilo es una prueba del docente (smoke test del editor de la cátedra).
 * Los hilos de prueba no cuentan para la analítica.
 */
val isSmokeTest: Boolean = false,
```

y en `AnalyticsAnalyzer.analyze`, dos filtradas:

```kotlin
val threads = threads.filterNot { it.isSmokeTest }   // los smoke tests no cuentan
val messages = messages.filter { it.threadId !in smokeTestThreadIds }
```

Son **dos líneas** y dejan la analítica igual: la de los alumnos, la de siempre.

---

## 9. Publicación: borrador vs publicada

### 9.1 Qué ve el alumno en cada estado

| Aspecto | Borrador (no publicada) | Publicada |
|---|---|---|
| Listado de cátedras del alumno | **No aparece** | Aparece con nombre, código y cursada |
| Anotarse con el código | **No funciona**. El mensaje es genérico: *"El código no es válido."* (el mismo que para un código inexistente) | Entra a la cátedra |
| Descripción | No la ve | La ve, renderizada en markdown |
| Materiales | No accesibles | Los visibles |
| Chat | No disponible | Chat con RAG sobre el material de la cátedra |
| Código de inscripción | El del borrador; **no sirve** hasta publicar | El vigente (el del snapshot) |
| Analítica del docente | Activa (el docente ve lo suyo) | Activa |

> El mensaje de error del código es **idéntico** para "código inexistente" y
> "cátedra no publicada": no se revela la existencia de una cátedra que el
> alumno no debería ver todavía.

### 9.2 Publicar

Botón "Publicar" (o "Publicar cambios" si ya está publicada). Abre un
diálogo de **checklist** antes de confirmar:

```
┌ Publicar la cátedra ────────────────────────────────────────────┐
│ Los alumnos van a ver:                                          │
│                                                                  │
│  ✓  Nombre            Inteligencia Artificial                   │
│  ✓  Código            IA-2026-1                                 │
│  ✓  Cursada           2026-1                                    │
│  ✓  Descripción       2.140 caracteres                          │
│  ✓  Código de insc.   K7F3QX                                    │
│  ✗  Servidor          Falta la dirección del servidor de la      │
│                       cátedra.                                   │
│                                                                  │
│  ℹ  4 materiales visibles                                        │
│  ℹ  La analítica de la cátedra está activa                       │
│                                                                  │
│  Podés publicar igual: el servidor se configura en «Modelo e IA».  │
│  Los alumnos que se anoten ahora no van a poder chatear.          │
│                                                    [Cancelar] [Publicar] │
└──────────────────────────────────────────────────────────────────┘
```

Reglas:

- Los `ERROR` del validador **bloquean** la publicación y el botón queda
  deshabilitado con el motivo. Los `WARNING` no bloquean.
- El botón "Publicar" **guarda primero** (el borrador se persiste) y después
  congela la copia: si el guardado falla, no se publica.
- Al publicar se crea el `PublishedCourse`:

```kotlin
suspend fun publish(course: Course, teacherId: String): Course {
    val snapshot = PublishedCourse(
        courseId = course.id,
        name = course.name,
        code = course.code,
        term = course.term,
        description = course.description,
        joinCode = course.joinCode,
        modelPolicy = course.modelPolicy,
        systemPrompt = course.systemPrompt,
        topK = course.modelPolicy.topK,
        chunkSize = course.chunkSize,
        publishedAt = nowMillis(),
        publishedBy = teacherId,
    )
    return course.copy(publishedSnapshot = snapshot)
}
```

- `publishedAt` **no** se cambia al volver a publicar una cátedra ya
  publicada: se conserva la fecha original y se agrega `lastPublishedAt` si
  hace falta saber cuándo se tocó por última vez. (Opcional; la v1 usa
  `publishedAt` como "publicada por primera vez".)
- Tras publicar: snackbar "Cátedra publicada. Los alumnos pueden anotarse con
  el código K7F3QX."

### 9.3 Cambios sin publicar

Si la cátedra está publicada y el borrador difiere de la copia publicada:

- la barra de estado muestra "N cambios sin publicar" (toca → diálogo con el
  diff);
- el botón dice "Publicar cambios";
- la vista previa muestra un banner azul "Hay N cambios sin publicar" con
  botón "Publicar".

El diff usa `CourseDiff` (§3.6):

```
Cambios sin publicar
  Descripción            cambió
  Fragmentos recuperados 5 → 8
  Tamaño del fragmento    1200 → 1800
  Permitir IA local       no → sí
```

El diff **no** es un diff de texto línea por línea: con descripciones largas
eso no ayuda al docente. Es una lista de campos cambiados, con el valor
anterior y el nuevo cuando son cortos.

### 9.4 Despublicar

Botón "Despublicar" (menú `⋮`), sólo para el titular (`COURSE_UNPUBLISH`).
Diálogo de confirmación explícito:

```
┌ Despublicar la cátedra ────────────────────────────────────────┐
│ Los 48 alumnos anotados van a ver «Esta cátedra ya no está       │
│ disponible» al entrar. El material y los chats no se borran.     │
│ El código de inscripción deja de servir.                        │
│                                                                  │
│ Podés volver a publicarla cuando quieras.                      │
│                                              [Cancelar] [Despublicar] │
└──────────────────────────────────────────────────────────────────┘
```

Efectos:

- `publishedSnapshot = null` → la cátedra desaparece del listado del alumno y
  el código deja de servir.
- **No** se borra material, chats, inscripciones ni analítica.
- Los alumnos que ya estaban anotados ven "Esta cátedra ya no está
  disponible" y **conservan su historial de chats** (que sigue en disco).
- Es reversible: volver a publicar crea un snapshot nuevo.

### 9.5 Permisos de publicación

Del doc 08, sin excepciones:

| Capacidad | TITULAR | AYUDANTE | Uso en esta pantalla |
|---|:---:|:---:|---|
| `COURSE_VIEW` | ✔ | ✔ | Ver la cátedra |
| `COURSE_EDIT` | ✔ | ✖ | Editar nombre, código, cursada, descripción, analítica |
| `COURSE_VIEW_JOIN_CODE` | ✔ | ✖ | Ver y copiar el código |
| `COURSE_ROTATE_JOIN_CODE` | ✔ | ✖ | Botón "Rotar" |
| `COURSE_CONFIG_RAG` | ◆ | ◆ | Editar `topK` y `chunkSize` (condicional: cátedra publicada) |
| `COURSE_PUBLISH` | ✔ | ✖ | Botón "Publicar" |
| `COURSE_UNPUBLISH` | ✔ | ✖ | "Despublicar" |
| `COURSE_VIEW_SETTINGS` | ✔ | ✔ | Ver la configuración completa sin editar |

El ayudante entra en **modo lectura**: banner arriba

> *"Sólo el profesor titular puede editar la cátedra. Podés consultarla y ver
> el material."*

(la misma fórmula que usa el doc 05 en §11.7).

---

## 10. Nombres de archivos, tipos y composables

### 10.1 `:core` (KMP, `commonMain` salvo indicado)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Course.kt` | **Cambios**: `+ publishedSnapshot: PublishedCourse?`; `+ val published: Boolean`. |
| `core/src/commonMain/kotlin/kitoru/domain/PublishedCourse.kt` | **Nuevo**: `PublishedCourse`. |
| `core/src/commonMain/kotlin/kitoru/domain/CourseValidator.kt` | **Nuevo**: `CourseField`, `IssueSeverity`, `CourseIssue`, `CourseValidator`. |
| `core/src/commonMain/kotlin/kitoru/domain/CourseDiff.kt` | **Nuevo**: `CourseDiff`. |
| `core/src/commonMain/kotlin/kitoru/domain/JoinCode.kt` | **Nuevo**: `JoinCode` (generate, generateUnique, isValid, normalize). |
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt` | **Cambios**: `+ PolicyField`, `+ PolicyIssue`, `+ validationIssues()`; `validate()` pasa a ser su proyección (mismo resultado). |
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | **Cambios**: `+ ChatThread.isSmokeTest: Boolean = false`. |
| `core/src/commonMain/kotlin/kitoru/domain/AnalyticsAnalyzer.kt` | **Cambios**: excluir los hilos con `isSmokeTest`. |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | **Cambios**: `CourseRepository.existsWithCode(code, exceptId)` (para el chequeo de duplicados en línea). |
| `core/src/commonTest/kotlin/kitoru/domain/CourseValidatorTest.kt` | **Nuevo**: nombre vacío, código duplicado, código con caracteres inválidos, `topK` fuera de rango, `chunkSize` fuera de rango, warnings no bloqueantes, `validate()` de la política intacto. |
| `core/src/commonTest/kotlin/kitoru/domain/JoinCodeTest.kt` | **Nuevo**: formato, alfabeto sin ambiguos, `normalize` (Crockford), unicidad con `Random(seeded)`. |
| `core/src/commonTest/kotlin/kitoru/domain/CourseDiffTest.kt` | **Nuevo**: diff vacío, diff con un campo, `between(borrador, null)`. |
| `core/src/commonTest/kotlin/kitoru/domain/ModelPolicyTest.kt` | **Nuevo**: `validate()` devuelve **exactamente** la misma lista que antes del refactor (texto y orden). |

### 10.2 `:ui` (Compose Multiplatform, `commonMain` salvo indicado)

```
ui/src/commonMain/kotlin/kitoru/ui/
├── markdown/                        ← (definido también por el doc 07)
│   ├── MarkdownText.kt              ← MarkdownText, parseMarkdown, MdNode, buildInlineAnnotated
│   ├── MathSplit.kt                 ← splitMath, MathSegment
│   ├── LatexText.kt                 ← LatexText
│   └── CodeBlock.kt                 ← CodeBlock(lang, code, closed, onCopy, onSave)
├── platform/
│   └── Clipboard.kt                 ← ClipboardWriter + expect fun systemClipboard()
├── teacher/course/
│   ├── CourseEditorScreen.kt        ← CourseEditorScreen, CourseEditorRoute
│   ├── CourseEditorViewModel.kt     ← estado, borrador, autoguardado, publicar, rotar
│   ├── CourseEditorState.kt         ← CourseEditorState, PublicationBadge
│   ├── sections/
│   │   ├── CourseSections.kt        ← enum CourseSection + CourseSectionContent
│   │   ├── CourseIdentitySection.kt ← nombre, código, cursada
│   │   ├── CourseDescriptionSection.kt
│   │   ├── CourseRecoverySection.kt ← topK + chunkSize (envuelve RagSection del doc 05)
│   │   ├── CourseEnrollmentSection.kt
│   │   ├── CourseAnalyticsSection.kt
│   │   └── CoursePublicationSection.kt
│   ├── components/
│   │   ├── MarkdownEditor.kt        ← MarkdownEditor, MarkdownEditorMode
│   │   ├── MarkdownFormat.kt        ← MarkdownFormat (wrap, prefixLines)
│   │   ├── MarkdownToolbar.kt       ← la barra de formato
│   │   ├── JoinCodeField.kt         ← campo + Copiar + Rotar
│   │   ├── CourseDiffList.kt        ← lista de cambios sin publicar
│   │   └── ValidationBanner.kt      ← banner de errores de la cátedra
│   ├── preview/
│   │   ├── StudentCoursePreview.kt  ← lo que ve el alumno
│   │   └── CoursePreviewPane.kt     ← el panel de la columna derecha
│   ├── dialogs/
│   │   ├── PublishCourseDialog.kt   ← checklist de publicación
│   │   ├── UnpublishCourseDialog.kt
│   │   ├── RotateJoinCodeDialog.kt
│   │   └── CourseDiffDialog.kt
│   └── CourseChatProbe.kt           ← motor del smoke test
└── teacher/model/                   ← (doc 05) secciones montadas desde acá
```

### 10.3 Puertos de plataforma

| Puerto | `commonMain` | `desktopMain` | `androidMain` |
|---|---|---|---|
| Portapapeles | `ClipboardWriter`, `expect fun systemClipboard()` | `java.awt.datatransfer.StringSelection` + `SwingUtilities.invokeLater` | `android.content.ClipboardManager` + `ClipData` (contexto de app inyectado) |
| Diálogos de archivo | `FileDialogs` (doc 07) | `JFileChooser` | `ActivityResultContracts` |
| Abrir en el sistema | `openInSystem` (doc 07) | `java.awt.Desktop` | `Intent.ACTION_VIEW` |

### 10.4 Estado y ViewModel

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/CourseEditorState.kt
data class CourseEditorState(
    val loading: Boolean = true,
    /** Borrador de trabajo (lo que edita el docente). */
    val working: Course = Course(id = "", code = "", name = ""),
    /** La copia congelada, si la cátedra está publicada. */
    val published: PublishedCourse? = null,
    /** Diferencia entre borrador y publicación. */
    val diff: CourseDiff = CourseDiff(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val lastSavedAt: Long = 0L,
    val saveError: String? = null,
    /** Problemas de validación, anclados al campo. */
    val issues: List<CourseIssue> = emptyList(),
    /** Materiales visibles de la cátedra (para el checklist de publicación). */
    val materialCount: Int = 0,
    val visibleMaterialCount: Int = 0,
    /** Alumnos anotados (para el diálogo de rotación/despublicación). */
    val enrollmentCount: Int = 0,
    /** Capacidades effectivas (doc 08). */
    val canEdit: Boolean = false,
    val canPublish: Boolean = false,
    val canViewJoinCode: Boolean = false,
    val canRotateJoinCode: Boolean = false,
    val canConfigureRag: Boolean = false,
    /** Mensaje efímero para el snackbar. */
    val message: String? = null,
) {
    val errors: List<CourseIssue> get() = issues.filter { it.severity == IssueSeverity.ERROR }
    val warnings: List<CourseIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
    fun issueFor(field: CourseField): String? =
        issues.firstOrNull { it.field == field }?.message
}
```

### 10.5 Firmas de los composables principales

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/course/CourseEditorScreen.kt
@Composable
fun CourseEditorScreen(
    state: CourseEditorState,
    onEdit: ((Course) -> Course) -> Unit,
    onSave: () -> Unit,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
    onRotateJoinCode: () -> Unit,
    onCopyJoinCode: () -> Unit,
    onProbeChat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)

/** La ruta: crea el ViewModel, cablea el autoguardado y hospeda la pantalla. */
@Composable
fun CourseEditorRoute(
    courseId: String,
    scope: CoroutineScope,
    deps: CourseEditorDeps,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

---

## 11. Casos borde

| Caso | Qué hace la pantalla |
|---|---|
| Cátedra nueva (recién creada) | Ya tiene código de inscripción generado y topK/chunkSize por defecto. El badge dice "Borrador" y la vista previa pide publicar. |
| El docente borra todo el nombre | Error bajo el campo: "El nombre de la cátedra no puede estar vacío." El autoguardado **igual** guarda (guardar no es publicar): el borrador puede quedar incompleto. |
| El código de materia choca con otra cátedra | Error bajo el campo: "Ya existe una cátedra con el código «IA-2026-1»." Se chequea al escribir (contra el repositorio, con debounce de 300 ms) y al publicar. |
| El docente edita una cátedra publicada | Los alumnos siguen viendo la copia publicada. La barra muestra "N cambios sin publicar". |
| El docente rota el código con 48 alumnos anotados | Se actualizan borrador **y** publicación en un solo guardado; el código viejo deja de servir de inmediato; los 48 siguen entrando con su cuenta. |
| El autoguardado falla (disco lleno) | Barra de estado en rojo: "No se pudo guardar: …". El borrador sigue en memoria. Al cerrar, el escritorio pregunta antes de cerrar. |
| El docente cierra la app con cambios sin guardar | Escritorio: se vuelca el borrador antes de cerrar; si falla, se pregunta. Android: se pierden como máximo 600 ms de tecleo. |
| La cátedra no tiene materiales | El checklist de publicación avisa "0 materiales visibles" (info, no bloquea). El smoke test avisa: "las respuestas saldrán sin contexto". |
| La política del modelo está incompleta | El botón "Probar el chat" queda deshabilitado con el motivo: "Falta la dirección del servidor de la cátedra." |
| El ayudante abre el editor | Modo lectura: banner "Sólo el profesor titular puede editar la cátedra."; sin "Guardar", sin "Publicar", sin "Rotar", sin ver el código. |
| La cátedra está archivada | `COURSE_ARCHIVE` (doc 08): deja de aceptar inscripciones. El editor muestra el estado "Archivada" y el botón "Publicar" pasa a "Reactivar". |
| La analítica está apagada | El switch queda apagado con la explicación; la pantalla de chats (doc 07) sigue disponible y avisa que no hay agregados. |
| La descripción tiene un bloque de código sin cerrar | La vista previa lo dibuja como bloque "en curso" (el parser ya lo soporta: `closed = false`). No se pierde texto. |
| La descripción tiene `$` de precios ("$5 a $10") | `splitMath` no lo toma como fórmula (el cierre no puede estar seguido de un dígito). La vista previa lo muestra como texto. |
| Dos ventanas del editor abiertas sobre la misma cátedra | La segunda sobreescribe a la primera al guardar (último que escribe gana). Mitigación: el `Course` lleva `createdAt` pero no `updatedAt`; **se propone agregar `updatedAt`** para detectar el conflicto y avisar "Esta cátedra se modificó en otra ventana". |

---

## 12. Criterios de aceptación

1. En escritorio (≥ 840 dp) la pantalla muestra **dos columnas**: formulario a
   la izquierda y vista previa del alumno a la derecha (380 dp). En ventana
   angosta (< 600 dp) muestra **una columna** con pestañas de sección y la
   vista previa como destino aparte.
2. Cada campo de §4.1 tiene **exactamente un control** en toda la app; la
   sección "Modelo e IA" monta los composables del doc 05 (mismo ViewModel).
3. Al escribir un nombre vacío, un código duplicado o un `topK` fuera de
   rango, el error aparece **debajo del campo** con un mensaje en español, y la
   lista de `CourseValidator.validate()` devuelve esos mismos mensajes.
4. `ModelPolicy.validate()` devuelve **exactamente** la misma lista que antes
   (mismos textos, mismo orden): hay un test que lo fija.
5. La descripción se edita en markdown y se ve **renderizada** en la vista
   previa de la columna derecha con el mismo `MarkdownText` que usa el chat.
6. En pantalla angosta, el editor de markdown tiene un conmutador
   **Escribir | Vista previa** y el texto renderizado es el mismo que el de
   escritorio.
7. El código de inscripción tiene 6 caracteres sin `0/O/1/I/L/U`, se puede
   copiar con un clic y se puede rotar con confirmación; al rotar, el código
   anterior deja de servir de inmediato aunque la cátedra esté publicada.
8. El editor **autoguarda 600 ms** después de la última tecla: el cambio
   queda en disco y la barra dice "Guardado".
9. Al cerrar la ventana del escritorio con cambios sin guardar, el borrador se
   vuelca a disco antes de cerrar (y si el guardado falla, se pregunta).
10. "Probar el chat" abre un chat con el **material real** y la **config del
    borrador**; el hilo queda marcado como `isSmokeTest` y **no** aparece en
    la analítica de la cátedra.
11. Una cátedra no publicada **no** aparece en el listado del alumno, su
    código no sirve y el mensaje de error es el mismo que para un código
    inexistente.
12. "Publicar" guarda primero, congela la copia y muestra el checklist con
    los `ERROR` bloqueando y los `WARNING` avisando.
13. Con la cátedra publicada, editar el borrador **no** cambia lo que ve el
    alumno hasta volver a publicar; la barra muestra "N cambios sin publicar".
14. El ayudante entra en modo lectura y no ve el código de inscripción.

---

## 13. Dependencias con otros documentos

| Otro doc | Relación |
|---|---|
| **04 · Instrucciones (docente)** | El editor muestra el prompt como resumen + enlace; el smoke test usa el prompt guardado. El doc 04 propone `CoursePromptTemplate`; acá se usa `Course.systemPrompt`. |
| **05 · Configuración del modelo (docente)** | **El editor monta las secciones del doc 05** (mismo ViewModel). El doc 05 debe exponer `ServerSection`, `ModelSection`, `GenerationSection`, `ToolsSection`, `StudentOverridesSection`, `LocalAiSection` y `RagSection` como composables con **estado elevado**, y `RagSection` se muestra dentro de "Recuperación". `ModelPolicy.validate()` no cambia: se le agrega `validationIssues()` al lado. |
| **03 · Materiales (docente)** | El editor sólo cuenta los materiales (para el checklist y el aviso del smoke test) y enlaza a "Reindexar ahora" cuando cambia `chunkSize`. |
| **06 · Alumnos e inscripciones** | El editor muestra el **número** de anotados (diálogo de rotación/despublicación), no la lista. |
| **07 · Chats (docente)** | Comparte `MarkdownText`, `CitationBadge`, `SourceList` y `CodeBlock`. El doc 07 propose `kitoru/ui/platform/FileDialogs.kt`, que el `CodeBlock` portado usa para "Guardar como…". |
| **08 · Permisos** | Se usan tal cual: `COURSE_EDIT`, `COURSE_VIEW_JOIN_CODE`, `COURSE_ROTATE_JOIN_CODE`, `COURSE_CONFIG_RAG`, `COURSE_PUBLISH`, `COURSE_UNPUBLISH`, `COURSE_ARCHIVE`. La condición `CoursePublished` del doc 08 sigue funcionando: se agrega `Course.published` como accessor calculado. |
| **08 · Analítica (docente)** | `Course.analyticsEnabled` se expone acá; el doc de analítica la consume. `AnalyticsAnalyzer` excluye los hilos `isSmokeTest`. |
| **Chat del alumno** | El alumno lee `course.publishedSnapshot` (o un `PublishedCourse` equivalente), no el `Course` en edición. Hay que decidir si el cliente del alumno recibe el snapshot completo o sólo los campos que usa. |
| **Servidor (Kitoru-Server)** | El alta por servidor tiene que **limitar los intentos** de código de inscripción (32^6 es mucho si se fuerza). Acá sólo se genera localmente. |
