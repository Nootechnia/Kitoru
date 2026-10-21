# 09 · Gestión del corpus de la cátedra (catálogo, versiones, publicación y calidad)

> Subagente: diseño de la **gestión del corpus** como producto: no "subir un
> archivo" sino **un catálogo cuidado** con ciclo de vida, versiones,
> publicación, salud y feedback.
> Módulos tocados: `:core` (dominio + corpus puro), `:backend` (índices,
> checks, sugerencia de preguntas), `:ui` (catálogo del docente, todavía no
> construido).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*, *el
> estudiante conserva agencia*, *el docente no ve lo que no debe*.
> Documentos hermanos leídos: `docs/design/CONTEXTO.md`,
> `docs/design/teacher/03-materiales.md` (pantalla de materiales),
> `docs/design/student/04-materiales.md` (vista del alumno),
> `docs/design/teacher/09-calidad.md` (calidad de las respuestas),
> `docs/design/domain/01-catedra.md` (ciclo de vida de la cátedra),
> `docs/design/domain/08-permisos.md` (capacidades),
> `docs/design/domain/10-taxonomia.md` (unidades y etiquetas).
> Todo lo que sigue es **diseño**, no código: los nombres de archivo, tipos y
> firmas son los que deberían existir en el repo.

---

## 0. Decisiones en una línea

| # | Decisión | Respuesta | Motivo |
|---|---|---|---|
| D1 | El estado del material | **Dos ejes ortogonales**: `MaterialLifecycle` (editorial: borrador / en revisión / publicado / retirado) y `MaterialStatus` (técnico: pendiente / extrayendo / indexando / listo / requiere revisión / error) | El estado editorial responde "**¿lo pueden ver los alumnos?**"; el técnico responde "**¿el pipeline pudo extraerlo?**". Meterlos en un solo enum obliga a inventar estados como `PUBLICADO_PERO_FALLIDO` |
| D2 | El índice del alumno | Se construye con la **versión publicada** (`Material.publishedVersionId`), no con la última | Un apunte a medio hacer no puede entrar al corpus de los alumnos |
| D3 | El índice del docente | Se construye con la **última versión** (`Material.currentVersionId`) | El docente tiene que poder probar lo que está por publicar sin tocar lo que los alumnos ven |
| D4 | Las versiones viejas | **Se conservan** (últimas 10 + todas las que un alumno ya vio) | Sin ellas, las citas viejas quedan huérfanas y se pierde la analítica "el 40 % leyó la versión vieja" |
| D5 | Cuándo se crea una versión | Sólo cuando **el texto cambia de verdad** (hash distinto) | Una versión por clic de "guardar" llena el historial de ruido |
| D6 | Publicación | Tres modos: `INMEDIATO`, `AL_PUBLICAR_CATEDRA`, `DIARIO` (lote a una hora) | El docente decide cuándo el cambio llega; el alumno no ve material a medio hacer |
| D7 | El contrato de distribución | Un **manifiesto** (`CorpusManifest`) con `version` monótona + hash por documento | Es lo que permite al cliente del alumno detectar "hay algo nuevo" y es el contrato con Kitoru-Server |
| D8 | El catálogo | `CorpusCatalog` con **estadísticas de uso por material** (citas, alumnos, respuestas sin respaldo, aperturas) | El corpus no es un depósito de archivos: es un producto con salud y uso |
| D9 | La salud | Checks **automáticos y deterministas** al indexar, con severidad `BLOQUEANTE` / `ADVERTENCIA` / `INFO` | El 80 % de las quejas ("no encuentra lo que pregunto") son problemas del material, no del modelo |
| D10 | Duplicados | Detección por **hash del contenido normalizado** (no por nombre ni por tamaño) | "El mismo PDF subido dos veces" es el error más común y el más caro |
| D11 | La licencia | **Dos campos**: `MaterialLicense` (legal, para mostrar y atribuir) y `MaterialUsagePolicy` (técnica, para lo que la app permite hacer) | Confundir "copyleft" con "la IA no puede usarlo" es un error de diseño; lo que **efecta al comportamiento** es la política |
| D12 | El límite técnico | `SIN_IA_LOCAL` + alumno en modo `LOCAL` ⇒ **el material queda fuera del índice local** (fail-closed) | Conecta licencia y privacidad: el material no sale del dispositivo si la licencia no lo permite |
| D13 | El material de referencia | Bandera `MaterialUsagePolicy.SOLO_REFERENCIA`: el alumno lo lee, **la IA no lo cita** | Para no contaminar las citas con material que no quiere que se cite |
| D14 | La pregunta de ejemplo | Se genera de forma **determinista** (sin modelo): el fragmento más "informativo" del material | Un modelo en el loop de ingestión hace la operación lenta, cara y no reproducible |
| D15 | El smoke test | El docente guarda **preguntas de control** por material y la app las corre en cada reindexado | Es lo que avisa "el cambio del ejemplo 3 rompió la pregunta que uso en clase" antes de que lo descubra un alumno |

---

## 1. El corpus como producto

### 1.1 La tesis

El material de una cátedra es **el producto**. No es un adjunto: es lo que la
IA sabe, lo que el alumno lee y lo que el docente usa para enseñar. Por eso
la gestión del corpus no puede ser "un botón de subir archivo":

> Un corpus no es un conjunto de archivos. Es un **catálogo curado**, con
> estados, con historial, con salud, con uso y con un ciclo de publicación
> que decide **cuándo lo que el docente cambió llega a los alumnos**.

Tres consecuencias de producto que este documento diseña:

1. **Un material a medio hacer existe y es visible para el docente, pero no
   para el alumno.** De ahí el ciclo de vida (§2) y las dos versiones que
   conviven (vigente y publicada).
2. **Un material que se corrigió deja rastro.** De ahí las versiones (§3) y la
   analítica de exposición ("el 40 % de los alumnos leyó la versión vieja,
   que tenía el error del ejemplo 3").
3. **Un corpus que no se mide no se cuida.** De ahí el catálogo con
   estadísticas (§5) y los checks automáticos (§6).

### 1.2 Qué hay hoy (auditoría del código real)

| Pieza | Estado | Qué falta |
|---|---|---|
| `kitoru.domain.Material` | ✅ existe (`id, courseId, title, kind, path, sizeBytes, uploadedBy, uploadedAt, tags, visible, extractedText, warning`) | Estado editorial, versión vigente/publicada, rol en el corpus, licencia, hash, unidad, checks |
| `kitoru.domain.MaterialKind` | ✅ existe (`DOCUMENT, SLIDES, TRANSCRIPT, LINK, OTHER`) | `LINK` existe pero no tiene ni URL ni política de refresco |
| `MaterialRepository` | ✅ existe (`listForCourse, get, save, delete`) | Listar versiones, listar por hash, eventos de uso |
| `rag.retrieval.CourseMaterialSource` | ✅ existe; filtra `it.visible && it.extractedText.isNotBlank()` | No sabe de versiones, de rol en el corpus, de licencia ni de plano (docente/alumno) |
| `rag.retrieval.Doc` / `Chunk` | ⚠️ no tienen el id del material | Sin identidad de material no hay cruce con la analítica ni con las citas |
| `rag.retrieval.Chunker` | ✅ parte por encabezados Markdown y por tamaño | No expone las medidas de los fragmentos (para el check de "chunks diminutos") |
| `rag.retrieval.KnowledgeBase` | ✅ `sources` mutable + `sync(onStatus)` | No distingue planos; no hay índice de biblioteca (para el material de referencia) |
| `kitoru.domain.SourceUsage` | ✅ existe (`label, times`) | Es por *label*, no por material: no alcanza para versionar ni para cruzar con licencias |
| `AnalyticsAnalyzer` | ✅ puro y determinista | Agrega por label de fuente; no sabe de versiones ni de eventos de corpus |

> **Nota de reconciliación.** Los documentos hermanos propusieron tres nombres
> distintos para el id del material en un fragmento: `docId`
> (`teacher/03-materiales.md` §4.7), `ref` (`student/04-materiales.md` §1.3)
> y `sourceId` (`teacher/09-calidad.md` §7.4). Aquí se **unifican** en
> `materialId` + `versionId` (§12.4), que es la única forma de que la
> analítica pueda distinguir "citó la versión vieja" de "citó la versión nueva".

### 1.3 Dos ejes de estado, no uno

El error clásico es meter en un solo enum el "estado editorial" y el "estado
técnico". El resultado son estados como `PUBLICADO_CON_ERROR`, que no
significan nada para la UI.

| Eje | Enum | Pregunta que responde | Ejemplos |
|---|---|---|---|
| **Editorial** | `MaterialLifecycle` | ¿En qué fase de la vida del material está, **respecto de los alumnos**? | `BORRADOR`, `EN_REVISION`, `PUBLICADO`, `RETIRADO` |
| **Técnico** | `MaterialStatus` (propuesto en `teacher/03-materiales.md` §4.1) | ¿El pipeline pudo extraer y preparar el material? | `PENDING`, `EXTRACTING`, `INDEXING`, `READY`, `NEEDS_REVIEW`, `FAILED` |

Reglas de composición:

- Un material es **publicable** si `lifecycle != RETIRADO` **y** `status == READY`.
- Un material **entra al corpus del alumno** si además `lifecycle == PUBLICADO`
  **y** `usagePolicy` lo permite en el modo del alumno (§9).
- El `status` técnico **nunca** se ve el alumno: es del docente.

### 1.4 Los dos planos del corpus

El mismo corpus se ve distinto según quién mira:

| Plano | Qué incluye | Para qué |
|---|---|---|
| `CorpusPlane.DOCENTE` | Todos los materiales no retirados, con la **última versión**, incluidos los de sólo referencia | Que el docente pueda probar, buscar y depurar todo |
| `CorpusPlane.ALUMNO` | Sólo materiales **publicados**, **listos**, **visibles**, **indexables** y **permitidos por la licencia en el modo del alumno** | Lo que la IA puede citar |

El plano del alumno **no es un subconjunto por ids** (eso sería frágil): es el
resultado de aplicar una función pura sobre el estado. Por eso el filtro vive
en `:core` y se testea sin disco (§9.4).

Los tipos que lo modelan:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusSnapshot.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/** Para quién se está armando el corpus. */
@Serializable
enum class CorpusPlane(val label: String) {
    /** Lo que ve y prueba el docente. */
    DOCENTE("Docente"),

    /** Lo que ven y consultan los alumnos. */
    ALUMNO("Alumno"),
}

/**
 * Un documento del corpus, ya resuelto: material + versión + texto.
 *
 * Es la unidad que consume el índice. La arma [CorpusResolver].
 */
@Serializable
data class CorpusDocument(
    val materialId: String,
    val versionId: String,
    val versionNumber: Int = 0,
    val title: String = "",
    /** Ruta del archivo original (relativa a la cátedra). */
    val path: String = "",
    val text: String = "",
    /** Qué puede hacer la app con este documento. */
    val role: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    val license: MaterialLicense = MaterialLicense.SIN_LICENCIA,
    val tags: List<String> = emptyList(),
    val unitId: String = "",
    val lifecycle: MaterialLifecycle = MaterialLifecycle.PUBLICADO,
)

/**
 * El corpus resuelto.
 *
 * [indexable] y [browsable] son las dos vistas del mismo corpus:
 *  - [indexable]: lo que el modelo puede recuperar y citar.
 *  - [browsable]: lo que el alumno puede leer y buscar (incluye el material
 *    de sólo referencia, que se lee pero no se cita).
 */
@Serializable
data class CorpusSnapshot(
    val indexable: List<CorpusDocument> = emptyList(),
    val browsable: List<CorpusDocument> = emptyList(),
    /** Lo que quedó afuera y por qué (para explicárselo al docente). */
    val excluded: List<ExcludedDocument> = emptyList(),
    /** Versión del manifiesto que generó este snapshot. */
    val manifestVersion: Long = 0L,
) {
    /** Nº de documentos que aportan fragmentos al RAG. */
    val indexableCount: Int get() = indexable.size

    /** Nº de documentos que el alumno puede leer. */
    val browsableCount: Int get() = browsable.size
}

/** Por qué un material no entró al corpus. */
@Serializable
enum class ExclusionReason(val label: String) {
    /** Está retirado. */
    RETIRADO("Retirado de la cátedra"),

    /** El docente lo ocultó. */
    OCULTO("Oculto para los alumnos"),

    /** Todavía no se publicó. */
    NO_PUBLICADO("Todavía no está publicado"),

    /** La política de uso prohíbe el uso con IA. */
    LICENCIA_SIN_IA("La política de uso prohíbe el uso con IA"),

    /** La política de uso prohíbe el procesamiento local. */
    LICENCIA_SIN_IA_LOCAL("La política de uso prohíbe el procesamiento en el dispositivo"),

    /** La cátedra no permite inferencia local. */
    IA_LOCAL_PROHIBIDA("La cátedra no permite la IA local"),
}

/** Un material que quedó afuera, con su motivo. */
@Serializable
data class ExcludedDocument(
    val materialId: String,
    val title: String = "",
    val reason: ExclusionReason,
    /** Detalle legible del motivo. */
    val detail: String = "",
)
```

---

## 2. El ciclo de vida del material

### 2.1 El enum

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusLifecycle.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Estado **editorial** de un material: en qué fase está respecto de los alumnos.
 *
 * Es ortogonal a [MaterialStatus] (estado técnico del pipeline). Un material
 * puede estar `PUBLICADO` y `READY`, o `PUBLICADO` y `NEEDS_REVIEW` (el
 * docente lo editó a mano y el extractor no vuelve a correr).
 *
 * BORRADOR ──pedir revisión──▶ EN_REVISION ──publicar──▶ PUBLICADO
 *    ▲                            │                        │
 *    └──────devolver a borrador────┘                        │
 *    ▲                                                     │
 *    └─────────────restaurar──────────────── RETIRADO ◀─────┘
 */
@Serializable
enum class MaterialLifecycle(
    val label: String,
    val description: String,
) {
    /** Sólo el docente lo ve. No está en el corpus de nadie. */
    BORRADOR(
        "Borrador",
        "Sólo el docente lo ve. Todavía no lo ven los alumnos.",
    ),

    /**
     * El docente lo está revisando.
     *
     * Los alumnos siguen viendo la **versión publicada** (la anterior): por eso
     * entrar en revisión no cambia lo que la IA responde.
     */
    EN_REVISION(
        "En revisión",
        "Los alumnos siguen viendo la versión anterior; la nueva se publica cuando el docente la apruebe.",
    ),

    /** Vigente: los alumnos lo ven y la IA lo puede citar. */
    PUBLICADO(
        "Publicado",
        "Los alumnos lo ven y la IA lo puede citar.",
    ),

    /**
     * Fuera del curso.
     *
     * No se ve, no se indexa, no se edita. **Se conserva**: las citas viejas
     * siguen apuntando a un material que existió, y la analítica histórica no
     * se rompe.
     */
    RETIRADO(
        "Retirado",
        "Fuera del curso. Se conserva para que las citas y la analítica viejas no queden rotas.",
    ),
    ;

    /** ¿El material está visible para los alumnos? */
    val visibleToStudents: Boolean get() = this == EN_REVISION || this == PUBLICADO

    /** ¿El material puede tener una versión vigente? */
    val isEditable: Boolean get() = this != RETIRADO

    /** ¿El material está en el curso? (no retirado) */
    val isActive: Boolean get() = this != RETIRADO

    companion object {
        fun fromName(name: String): MaterialLifecycle? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 2.2 Tabla de transiciones

| Desde | Hacia | Botón en la UI | Efectos |
|---|---|---|---|
| `BORRADOR` | `EN_REVISION` | **Pedir revisión** | Avisa a los ayudantes; el material sigue sin publicarse |
| `BORRADOR` | `PUBLICADO` | **Publicar** | Exige `status == READY` y **ningún check bloqueante**; fija `publishedVersionId = currentVersionId`; dispara la publicación según el modo de la cátedra (§4) |
| `BORRADOR` | `RETIRADO` | **Retirar** | Descarta un material nunca publicado |
| `EN_REVISION` | `BORRADOR` | **Devolver a borrador** | Los alumnos dejan de verlo |
| `EN_REVISION` | `PUBLICADO` | **Publicar** | Igual que desde `BORRADOR` |
| `EN_REVISION` | `RETIRADO` | **Retirar** | Los alumnos dejan de verlo; las citas viejas quedan con "material retirado" |
| `PUBLICADO` | `EN_REVISION` | **Retirar de la vista / revisar** | **No** cambia lo que los alumnos ven: la versión publicada sigue viva |
| `PUBLICADO` | `RETIRADO` | **Retirar** | Sale del corpus del alumno; se conserva el histórico |
| `RETIRADO` | `BORRADOR` | **Restaurar** | Vuelve como borrador (hay que publicarlo de nuevo) |

Transiciones **prohibidas** (error explícito en español):
`BORRADOR → RETIRADO` está permitida, pero `PUBLICADO → BORRADOR` **no**: para
dejar de mostrar un material publicado se usa `RETIRADO` (que conserva el
histórico) o `EN_REVISION` (que conserva la versión publicada). La razón es
que `BORRADOR` implica "nunca se publicó", y un material que ya estuvo
publicado no puede volver a ese estado sin perder la verdad del historial.

### 2.3 Qué implica cada estado

| Estado | ¿Lo ve el alumno? | ¿Entra al índice del alumno? | ¿Lo puede citar la IA? | ¿Lo edita el docente? | ¿Cuenta para la analítica? |
|---|---|---|---|---|---|
| `BORRADOR` | no | no | no | sí | no |
| `EN_REVISION` | sí (la versión **publicada**) | sí (la versión **publicada**) | sí | sí | sí |
| `PUBLICADO` | sí | sí | sí | sí | sí |
| `RETIRADO` | no | no | no | no (sólo restaurar) | sí (histórico) |

Dos filas merecen explicación:

- **`EN_REVISION` no saca el material del corpus.** Es el estado más
  contraintuitivo y por eso importa dejarlo escrito: entrar en revisión es
  "dejo de trabajar en público", no "dejo de publicarlo". Si el docente
  quita el material del corpus del alumno sin querer, rompe la clase.
- **`RETIRADO` conserva.** No es borrar: es "ya no es parte de la materia".
  Las citas de los alumnos siguen apuntando a un material que existió, y el
  `CitationVerifier` lo clasifica como fuente existente pero retirada
  (no como fuente inexistente). La distinción importa: "fuente inexistente"
  es un bug; "fuente retirada" es una decisión.

### 2.4 El código: la máquina de estados

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusLifecycle.kt (continúa)

/**
 * Transiciones del material. Función pura: recibe, devuelve, no toca disco.
 */
object MaterialLifecycleMachine {

    private val TRANSITIONS: Map<MaterialLifecycle, Set<MaterialLifecycle>> = mapOf(
        MaterialLifecycle.BORRADOR to setOf(
            MaterialLifecycle.EN_REVISION,
            MaterialLifecycle.PUBLICADO,
            MaterialLifecycle.RETIRADO,
        ),
        MaterialLifecycle.EN_REVISION to setOf(
            MaterialLifecycle.BORRADOR,
            MaterialLifecycle.PUBLICADO,
            MaterialLifecycle.RETIRADO,
        ),
        MaterialLifecycle.PUBLICADO to setOf(
            MaterialLifecycle.EN_REVISION,
            MaterialLifecycle.RETIRADO,
        ),
        MaterialLifecycle.RETIRADO to setOf(
            MaterialLifecycle.BORRADOR,
        ),
    )

    /** ¿Se puede pasar de [from] a [to]? */
    fun canTransition(from: MaterialLifecycle, to: MaterialLifecycle): Boolean =
        to in TRANSITIONS[from].orEmpty()

    /** Estados alcanzables (la UI dibuja un botón por cada uno). */
    fun nextStates(from: MaterialLifecycle): Set<MaterialLifecycle> =
        TRANSITIONS[from].orEmpty()

    /**
     * Transición del material.
     *
     * @param material material actual.
     * @param to estado destino.
     * @param actorId usuario que pide el cambio.
     * @param now instante actual (epoch millis), inyectable para los tests.
     * @param quality informe de calidad de la versión vigente (para bloquear
     *   la publicación si hay checks bloqueantes).
     * @return el material actualizado.
     * @throws IllegalStateException si la transición no es válida o falta algo.
     */
    fun transition(
        material: Material,
        to: MaterialLifecycle,
        actorId: String,
        now: Long,
        quality: CorpusQualityReport = CorpusQualityReport(),
    ): Material {
        if (!canTransition(material.lifecycle, to)) {
            throw IllegalStateException(
                "No se puede pasar «${material.title}» de ${material.lifecycle.label} a ${to.label}.",
            )
        }
        if (to == MaterialLifecycle.PUBLICADO) {
            val errors = publishBlockers(material, quality)
            if (errors.isNotEmpty()) {
                throw IllegalStateException(errors.joinToString(" "))
            }
        }
        return material.copy(
            lifecycle = to,
            updatedAt = now,
            lastEditedBy = actorId,
        )
    }

    /**
     * Por qué no se puede publicar. Lista vacía = se puede publicar.
     *
     * Los mensajes son los que ve el docente en el diálogo de publicación.
     */
    fun publishBlockers(
        material: Material,
        quality: CorpusQualityReport,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (material.status != MaterialStatus.READY) {
            errors += "«${material.title}» todavía no está listo (estado: ${material.status.label})."
        }
        quality.blocking.forEach { check ->
            errors += "«${material.title}» tiene un problema que impide publicarlo: ${check.message}"
        }
        if (material.currentVersionId.isBlank()) {
            errors += "«${material.title}» no tiene ninguna versión de texto."
        }
        return errors
    }
}
```

> `MaterialStatus` es el enum técnico propuesto en
> `teacher/03-materiales.md` §4.1. Si ese documento no se implementa, el
> bloqueante equivalente es `material.extractedText.isBlank()`.

### 2.5 Publicar ≠ reindexar; retirar ≠ borrar

Tres operaciones que se confunden y que este diseño separa:

| Operación | Qué cambia | Qué **no** cambia |
|---|---|---|
| **Publicar** | `publishedVersionId = currentVersionId`; se genera el manifiesto (§4) | El archivo, el texto, el historial de versiones |
| **Reindexar** | Se recalcula el índice BM25 | El estado editorial, lo que ven los alumnos (sólo el plano docente) |
| **Retirar** | `lifecycle = RETIRADO` | **No borra nada**: ni el archivo, ni las versiones, ni las citas viejas |

Retirar es reversible ("Restaurar" → `BORRADOR`). Borrar es otra operación,
explícitamente destructiva, y vive en la pantalla de materiales
(`teacher/03-materiales.md` §6.2). El diseño del corpus **no** agrega el
borrado: agrega el retiro, que es lo que un docente hace todos los años
("el apunte del año pasado ya no sirve, pero no lo borres porque hay citas").

---

## 3. Versiones

### 3.1 Por qué versionar

El caso real: el docente sube "Apuntes-IA.pdf", lo publica, da clase. A la
semana siguiente descubre que el **ejemplo 3** tiene un error. Edita el texto
y vuelve a publicar.

Sin versiones:

- Los alumnos que leyeron la versión vieja **no se enteran**: el material
  cambió debajo de sus pies.
- Las citas que el modelo ya dio ("según Apuntes-IA, página 7") quedan
  apuntando a un texto que ya no existe.
- El docente no puede responder "¿cuántos alumnos leyeron la versión con el
  error?" — que es **exactamente** la pregunta que quiere hacer: *"el 40 % de
  los alumnos leyó la versión vieja, que tenía el error del ejemplo 3"*,
  para decidir si avisar por el grupo.

Con versiones, las tres cosas se resuelven: la exposición se mide, las citas
guardan la versión, y el docente puede **avisar selectivamente** ("avisá a los
que no releyeron").

### 3.2 El modelo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusVersion.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Una versión del **texto** de un material.
 *
 * El texto de cada versión vive en un archivo aparte
 * (`courses/<courseId>/versions/<materialId>/v<n>.txt`); acá queda la
 * metadata. Así el historial no infla `materials.json`.
 */
@Serializable
data class MaterialVersion(
    val id: String,
    val materialId: String,
    val courseId: String,
    /** Número de versión, monotónico y 1-based dentro del material. */
    val number: Int,
    /** Cómo se creó esta versión. */
    val origin: VersionOrigin,
    /** Hash del texto normalizado (para detectar que "no cambió"). */
    val textHash: String = "",
    /** Caracteres del texto de esta versión. */
    val charCount: Int = 0,
    /** Cuándo se creó. */
    val createdAt: Long = 0L,
    /** Quién la creó. */
    val createdBy: String = "",
    /** Nota del docente: "Corregido el ejemplo 3". */
    val note: String = "",
    /** Ruta del archivo con el texto (relativa a la carpeta de la cátedra). */
    val textPath: String = "",
    /** Ruta del archivo original de esta versión (si el archivo cambió). */
    val filePath: String = "",
    /** Fragmentos que aporta (se recalcula al reindexar). */
    val chunkCount: Int = 0,
) {
    /** "v3" */
    val shortLabel: String get() = "v$number"
}

/** Por qué existe esta versión. */
@Serializable
enum class VersionOrigin(val label: String) {
    /** La primera vez que se subió el material. */
    ALTA("Subida inicial"),

    /** El docente editó el texto a mano. */
    EDICION_MANUAL("Edición manual del texto"),

    /** Se volvió a correr el extractor sobre el archivo. */
    REEXTRACCION("Nueva extracción del archivo"),

    /** Se reemplazó el archivo original. */
    ARCHIVO_NUEVO("Archivo reemplazado"),

    /** El contenido de un enlace cambió. */
    REFRESH_ENLACE("Refresco del enlace"),

    /** Se restauró el texto de una versión anterior. */
    REVERSIÓN("Reversión a una versión anterior"),
}
```

### 3.3 Dónde viven los archivos

```
courses/<courseId>/
├── files/
│   └── mat_7f3a__Apuntes-IA.pdf          # el archivo original (último)
├── versions/
│   └── mat_7f3a/
│       ├── v1.txt                          # texto de la versión 1
│       ├── v2.txt                          # texto de la versión 2
│       └── v3.txt                          # texto de la versión 3
└── published/                              # el snapshot que ven los alumnos
    ├── corpus-manifest.json
    └── texts/mat_7f3a.txt
```

- `versions/<materialId>/v<n>.txt` es **inmutable**: una vez escrita, una
  versión no se edita. Si el docente cambia el texto, se crea otra versión.
- `published/texts/<materialId>.txt` es la **copia publicada**: la que leen
  los alumnos. Es una copia, no un enlace, para que "retirar" y "publicar" no
  dependan de que el archivo de la versión siga existiendo.

### 3.4 Cuándo se crea una versión

| Disparador | ¿Crea versión? | `origin` | Nota |
|---|---|---|---|
| Subida de un archivo nuevo | Sí (v1) | `ALTA` | |
| Reemplazar el archivo original | Sí, **si el texto cambió** | `ARCHIVO_NUEVO` | |
| "Editar texto" → Guardar | Sí, **si el texto cambió** | `EDICION_MANUAL` | Es el caso principal |
| "Volver a extraer" | Sí, **si el texto cambió** | `REEXTRACCION` | |
| Refresco de un enlace | Sí, **si el contenido cambió** | `REFRESH_ENLACE` | |
| "Revertir a la v2" | Sí (crea v4 con el texto de v2) | `REVERSIÓN` | **No borra la v3**: el historial no se reescribe |
| Renombrar el título | **No** | — | Es metadata, no contenido |
| Cambiar etiquetas / unidad | **No** | — | Idem |
| Cambiar visibilidad / política de uso | **No** | — | Idem |
| Guardar el texto sin cambios | **No** | — | La UI avisa: "El texto no cambió, no se creó una versión nueva" |

La regla "**sólo si el texto cambió**" se implementa comparando el hash
normalizado (§6.4) del texto nuevo con el de la versión vigente. Es la regla
que evita que "Guardar" 20 veces sin tocar nada cree 20 versiones.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusVersionService.kt (extracto)

/**
 * Crea una versión a partir de un texto nuevo, **sólo si el texto cambió**.
 *
 * @return la versión creada, o `null` si el texto es idéntico al vigente.
 */
suspend fun newVersionIfChanged(
    material: Material,
    newText: String,
    origin: VersionOrigin,
    note: String,
    actorId: String,
    now: Long,
): MaterialVersion? {
    val hash = ContentHasher.of(newText)
    val current = versions.get(material.currentVersionId)
    if (current != null && current.textHash == hash) return null   // no-op

    val number = (versions.listForMaterial(material.id).maxOfOrNull { it.number } ?: 0) + 1
    val version = MaterialVersion(
        id = newId("ver"),
        materialId = material.id,
        courseId = material.courseId,
        number = number,
        origin = origin,
        textHash = hash,
        charCount = newText.length,
        createdAt = now,
        createdBy = actorId,
        note = note,
        textPath = "versions/${material.id}/v$number.txt",
        filePath = material.path,
    )
    versions.writeText(version, newText)
    versions.save(version)
    return version
}
```

### 3.5 Qué versión usa el índice

Ésta es la pieza que hace que el ciclo de vida funcione:

| Plano | Versión que se indexa | Consecuencia |
|---|---|---|
| Alumno | `publishedVersionId` | El alumno ve la última versión **aprobada** |
| Docente | `currentVersionId` | El docente prueba lo último que hizo, sin publicar |

Consecuencias concretas:

- **Editar el texto no reindexa el corpus del alumno.** Se reindexa sólo el
  plano del docente. Los alumnos no sienten nada hasta que se publica.
- **Publicar sí reindexa** (en modo `INMEDIATO`): el manifiesto cambia, el
  cliente del alumno sincroniza, y su índice local se rehace.
- Un material `EN_REVISION` con `publishedVersionId = v2` y
  `currentVersionId = v3` → el alumno ve v2, el docente ve v3. La UI del
  docente lo dice explícitamente: *"Los alumnos están viendo la v2; la v3
  está en revisión."*

### 3.6 Las versiones viejas: para qué se conservan

Se conservan por **tres** razones concretas, no por sentimentalismo:

1. **Las citas.** Un mensaje de un alumno guarda `versionId`. Si la v2 se
   borra, esa cita queda apuntando a un texto que no existe y el
   `CitationVerifier` la clasifica como "fuente inexistente" (un bug) en lugar
   de "fuente retirada" (una decisión).
2. **La analítica de exposición** (§3.7): sin la v2 no se puede contar
    cuántos la leyeron.
3. **La reversión.** El docente puede volver atrás ("volví a la v2, la v3
   estaba peor").

Política de retención:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusRetention.kt
object VersionRetentionPolicy {
    /** Versiones que se conservan por material (además de las citadas). */
    const val KEEP_PER_MATERIAL = 10

    /**
     * ¿Se puede borrar esta versión?
     *
     * **Nunca** se borra una versión que un alumno ya vio (o que está
     * publicada): la política de retención es un techo, no un objetivo.
     */
    fun canDelete(
        version: MaterialVersion,
        material: Material,
        citedVersionIds: Set<String>,
    ): Boolean =
        version.id != material.publishedVersionId &&
            version.id !in citedVersionIds &&
            version.id != material.currentVersionId
}
```

El borrado de una versión vieja es una operación explícita del docente
("Limpiar historial: borrar las versiones que nadie ha visto"), nunca una
tarea automática silenciosa.

### 3.7 Exposición a una versión: la analítica que responde la pregunta del docente

La pregunta: *"el 40 % de los alumnos leyó la versión vieja, que tenía el
error del ejemplo 3"*.

Se responde con **dos** números, no con una opinión:

| Número | Qué mide | De dónde sale |
|---|---|---|
| `onOld` | Alumnos que **sólo** vieron una versión anterior a la vigente | `CorpusEvent` con `versionId` distinto del vigente, agrupado por alumno |
| `staleCitations` | Citas a una versión vieja **después** de que se publicó la nueva | `ChatMessage.sourceRefs[].versionId` vs. `publishedVersionId` en la fecha del mensaje |

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusExposure.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Exposición a una versión de un material.
 *
 * Todo se deriva de eventos con marca de tiempo: es determinista y
 * reproducible (regla 3 del proyecto).
 */
@Serializable
data class VersionExposure(
    val materialId: String,
    val versionId: String,
    val versionNumber: Int,
    /** Alumnos únicos que interactuaron con esta versión. */
    val students: Int = 0,
    /** Citas de esta versión en respuestas del modelo. */
    val citations: Int = 0,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
)

/**
 * Informe de exposición de un material respecto de su versión vigente.
 */
@Serializable
data class VersionExposureReport(
    val materialId: String,
    val title: String = "",
    val currentVersionId: String = "",
    val currentVersionNumber: Int = 0,
    /** Cuándo se publicó la versión vigente. */
    val publishedAt: Long = 0L,
    /** Alumnos que ya interactuaron con la versión vigente. */
    val onCurrent: Int = 0,
    /** Alumnos que sólo interactuaron con versiones anteriores. */
    val onOld: Int = 0,
    /** Citas a versiones viejas **después** de publicar la vigente. */
    val staleCitations: Int = 0,
    /** Total de alumnos considerados (con métricas visibles). */
    val students: Int = 0,
) {
    /**
     * Proporción de alumnos que quedaron con la versión vieja, en **puntos
     * básicos** (0..10000). Mismo criterio que la analítica temporal: sin
     * `Double` en las comparaciones.
     */
    val staleStudentShare: Int
        get() = if (students <= 0) 0 else (onOld * 10_000) / students
}
```

El analizador (puro, en `:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusExposureAnalyzer.kt
object CorpusExposureAnalyzer {

    /**
     * Calcula la exposición por versión de un material.
     *
     * @param events eventos de corpus (aperturas, búsquedas, citas).
     * @param messages mensajes con `sourceRefs` (para las citas con versión).
     * @param visibleUserIds ids de los alumnos cuyas métricas son visibles
     *   (regla 6: el docente no ve lo que no debe).
     */
    fun analyze(
        material: Material,
        versions: List<MaterialVersion>,
        events: List<CorpusEvent>,
        messages: List<ChatMessage>,
        visibleUserIds: Set<String>,
        now: Long,
    ): VersionExposureReport
}
```

**Reglas de privacidad** (mismas que el resto de la analítica):

- Sólo cuentan los alumnos cuya inscripción tiene `visibility.showsMetrics`.
- Los **porcentajes** sólo se muestran si el grupo tiene al menos 5 personas
  (criterio de `analytics/07-temporal-cohortes.md` §0): con 2 personas, un
  "50 %" es el dato de una persona.
- El informe **no** expone qué alumno vio qué versión: sólo agregados.
- El registro de eventos guarda **metadatos, nunca texto**.

La respuesta al docente sale así:

```
⚠ «Apuntes-IA.pdf» se actualizó (v2 → v3) el 12/10 a las 18:00.
  23 de 58 alumnos interactuaron con la v3.
  35 alumnos todavía leyeron la v2 (60 %), que tiene el error del ejemplo 3.
  → [Avisar a los que no releyeron]
```

### 3.8 La diferencia entre dos versiones

El docente tiene que **ver** qué cambió antes de publicar. Un diff de
bloques (por encabezado) es suficiente y es implementable en `commonMain` sin
librerías:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusDiff.kt
package kitoru.domain

/** Un bloque de texto que cambió entre dos versiones. */
data class ChangedBlock(
    /** Encabezado del bloque ("## Página 7" / "3.2 Búsqueda heurística"). */
    val heading: String,
    /** Texto antes (vacío si el bloque es nuevo). */
    val before: String,
    /** Texto después (vacío si el bloque se borró). */
    val after: String,
) {
    /** ¿El bloque es nuevo? */
    val isNew: Boolean get() = before.isEmpty()
    /** ¿El bloque se borró? */
    val isRemoved: Boolean get() = after.isEmpty()
}

/**
 * Diff por bloques: parte los dos textos por encabezados Markdown y compara
 * bloque por bloque. Es determinista y no necesita el modelo.
 */
object CorpusDiff {

    /** Une el diff de dos versiones de un material. */
    fun diff(before: String, after: String): List<ChangedBlock> {
        val a = blocks(before)
        val b = blocks(after)
        val out = mutableListOf<ChangedBlock>()
        // Índice por encabezado: la clave de un bloque es su encabezado.
        val keys = (a.keys + b.keys).distinct().sorted()
        for (key in keys) {
            val old = a[key]
            val now = b[key]
            if (old != null && now != null && old != now) {
                out += ChangedBlock(key, old, now)
            } else if (old == null && now != null) {
                out += ChangedBlock(key, "", now)
            } else if (old != null && now == null) {
                out += ChangedBlock(key, old, "")
            }
        }
        return out
    }

    /** Parte por encabezados; el texto sin encabezado va bajo "(sin título)". */
    private fun blocks(text: String): Map<String, String> {
        val out = linkedMapOf<String, StringBuilder>()
        var key = "(sin título)"
        out.getOrPut(key) { StringBuilder() }
        for (line in text.lineSequence()) {
            if (line.startsWith("#")) {
                key = line.trimStart('#').trim().ifEmpty { key }
                out.getOrPut(key) { StringBuilder() }
            }
            out.getValue(key).appendLine(line)
        }
        return out.mapValues { it.value.toString().trim() }
    }
}
```

La UI de publicación muestra:

```
 Cambios en «Apuntes-IA.pdf» (v2 → v3)
 ─────────────────────────────────────────────
 ## Página 7                        (modificado)
 - …el perceptrón es la unidad básica de una red…
 + …el perceptrón es la unidad básica de una **red neuronal**…
 ─────────────────────────────────────────────
 1 bloque modificado · 0 agregados · 0 borrados
 [Ver el texto completo]  [Publicar]
```

### 3.9 Revertir

"Revertir" **no** borra la historia: crea una versión nueva con el texto de
una versión anterior. El `origin` es `REVERSIÓN` y la nota dice de qué
versión se copió ("revertido a la v2"). El resultado es que el historial
queda como una línea de tiempo honesta: v1 → v2 → v3 → v4(=v2).

---

## 4. Publicación y sincronización

### 4.1 Los tres modos

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusPolicy.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Cuándo llega a los alumnos un cambio del corpus.
 */
@Serializable
enum class CorpusPublishMode(val label: String, val description: String) {
    /** En el acto, al publicar el material. */
    INMEDIATO(
        "Inmediato",
        "El cambio llega a los alumnos en el momento en que publicás el material.",
    ),

    /** En el próximo cambio de estado de la cátedra a PUBLICADA. */
    AL_PUBLICAR_CATEDRA(
        "Al publicar la cátedra",
        "Los cambios se acumulan y salen juntos la próxima vez que publiques la cátedra.",
    ),

    /** En un lote diario a una hora fija. */
    DIARIO(
        "Por lote diario",
        "Los cambios se acumulan y salen todos juntos a la hora que elijas.",
    ),
}

/**
 * Política del corpus de la cátedra.
 */
@Serializable
data class CourseCorpusPolicy(
    val publishMode: CorpusPublishMode = CorpusPublishMode.INMEDIATO,
    /** Hora local del lote diario (0..23). */
    val publishHour: Int = 8,
    /** Idioma principal del material (para el check de consistencia). */
    val language: String = "es",
    /**
     * Si la cátedra permite que el alumno procese el material en su
     * dispositivo. `false` ⇒ ningún material entra al índice local.
     */
    val allowLocalInference: Boolean = true,
    /** Política por defecto de los materiales nuevos. */
    val defaultUsagePolicy: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    /** Registrar eventos de uso del corpus (aperturas, búsquedas). */
    val trackUsage: Boolean = true,
)
```

Y en `Course`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt (campo nuevo)
    /** Política del corpus (publicación, licencia por defecto, eventos). */
    val corpusPolicy: CourseCorpusPolicy = CourseCorpusPolicy(),
```

### 4.2 El manifiesto: el contrato de distribución

El alumno no lee los archivos del docente: recibe un **manifiesto** que dice
qué hay publicado, con qué versión y con qué hash.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusManifest.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Manifiesto del corpus publicado de una cátedra.
 *
 * Es el contrato entre el docente y el cliente del alumno (y, más adelante,
 * con Kitoru-Server). La [version] es monótona: si el cliente la ve más
 * grande que la local, hay que sincronizar.
 */
@Serializable
data class CorpusManifest(
    val courseId: String,
    /** Versión del manifiesto. Sube en cada publicación. */
    val version: Long = 0L,
    /** Cuándo se generó. */
    val generatedAt: Long = 0L,
    /** Modo con el que se publicó. */
    val mode: CorpusPublishMode = CorpusPublishMode.INMEDIATO,
    val documents: List<ManifestEntry> = emptyList(),
) {
    /** Ids de los materiales publicados. */
    val materialIds: List<String> get() = documents.map { it.materialId }
}

/** Un documento dentro del manifiesto. */
@Serializable
data class ManifestEntry(
    val materialId: String,
    val versionId: String,
    val versionNumber: Int,
    val title: String,
    val role: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    val license: MaterialLicense = MaterialLicense.SIN_LICENCIA,
    /** Hash del texto publicado (para detectar cambios). */
    val contentHash: String = "",
    val charCount: Int = 0,
    /** Ruta del texto publicado, relativa a la carpeta publicada. */
    val textPath: String = "",
    /** Ruta del archivo original (opcional: puede no estar en el cliente). */
    val filePath: String = "",
    /** Cuándo se publicó esta versión. */
    val publishedAt: Long = 0L,
)
```

### 4.3 El ciclo de publicación

```
   el docente publica un material
              │
              ▼
   ┌──────────────────────┐
   │ ¿modo INMEDIATO?     │──sí──▶ publicarAHora()
   └──────────┬───────────┘              │
              │ no                       │
              ▼                           │
   ┌──────────────────────┐               │
   │ ¿modo DIARIO?        │──sí──▶ encolarLote(hoy o mañana, publishHour)
   └──────────┬───────────┘               │
              │ no (AL_PUBLICAR_CATEDRA)  │
              ▼                           ▼
   queda pendiente hasta que        publicarAHora()
   la cátedra pasa a PUBLICADA
```

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusPublicationService.kt
package kitoru.domain

/**
 * Publica y retira material, y decide **cuándo** llega a los alumnos.
 *
 * Es un servicio de `:core`: no toca la red ni el índice, sólo el estado y el
 * manifiesto. El reindexado y la distribución los hace quien lo llama.
 */
class CorpusPublicationService(
    private val materials: MaterialRepository,
    private val versions: MaterialVersionRepository,
    private val manifests: CorpusManifestRepository,
    private val batches: PublicationBatchRepository,
) {

    /**
     * Publica la versión vigente de un material.
     *
     * @return el material actualizado y, si el modo lo exige, el lote
     *   programado (null si salió en el acto).
     */
    suspend fun publish(
        materialId: String,
        actorId: String,
        course: Course,
        now: Long,
    ): PublishOutcome

    /** Retira el material del corpus del alumno (conservando el histórico). */
    suspend fun retire(materialId: String, actorId: String, now: Long): Material

    /** Ejecuta los pendientes (al publicar la cátedra o en el lote diario). */
    suspend fun flushPending(courseId: String, course: Course, now: Long): PublishOutcome
}

/** Resultado de una publicación. */
data class PublishOutcome(
    val material: Material,
    /** Lote programado (si el modo no es inmediato). */
    val batch: PublicationBatch? = null,
    /** Manifiesto generado (si se publicó en el acto). */
    val manifest: CorpusManifest? = null,
)
```

El lote diario:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusPublication.kt
@Serializable
data class PublicationBatch(
    val id: String,
    val courseId: String,
    val mode: CorpusPublishMode,
    /** Instante en el que sale el lote. */
    val scheduledAt: Long,
    /** Materiales y versiones incluidas en el lote. */
    val entries: List<ManifestEntry> = emptyList(),
    val state: BatchState = BatchState.PROGRAMADO,
    /** Instante en que se ejecutó. */
    val executedAt: Long = 0L,
)

@Serializable
enum class BatchState(val label: String) {
    PROGRAMADO("Programado"),
    EJECUTADO("Publicado"),
    CANCELADO("Cancelado"),
}
```

### 4.4 El servicio de sincronización (lado alumno)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusSyncService.kt
package kitoru.domain

/**
 * Sincroniza el corpus del alumno con el manifiesto publicado.
 *
 * Es idempotente: si el manifiesto no cambió, no hace nada.
 */
class CorpusSyncService(
    private val manifests: CorpusManifestRepository,
    private val local: LocalCorpusStore,
) {

    /** Sincroniza y devuelve qué cambió. */
    suspend fun sync(courseId: String): CorpusSyncResult
}

/** Qué cambió en la última sincronización. */
data class CorpusSyncResult(
    val manifestVersion: Long = 0L,
    val added: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val unchanged: Int = 0,
    val error: String = "",
) {
    /** ¿Hubo cambios? */
    val changed: Boolean get() = added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()
}
```

> **El problema real del hito 1.** En el hito 1 no hay servidor: el cliente
> del alumno y el del docente son apps separadas y el material tiene que
> viajar de alguna forma. La abstracción es `CorpusPublisher`, con dos
> implementaciones: `FolderCorpusPublisher` (escribe el manifiesto y los
> textos en `courses/<courseId>/published/`, que el cliente del alumno lee de
> una carpeta compartida o sincronizada) y `ServerCorpusPublisher` (Kitoru-
> Server, que es el destino natural). El **resto del diseño no depende de
> cuál** se use: el contrato es el manifiesto.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusPublisher.kt
package kitoru.domain

/**
 * Distribuye el corpus publicado a los clientes.
 *
 * Es el **único** punto donde la app habla con el exterior para publicar
 * material: cambiar de transporte (carpeta, servidor, nube) no toca el resto
 * del diseño.
 */
interface CorpusPublisher {

    /**
     * Publica el corpus de una cátedra.
     *
     * @return la versión del manifiesto publicada.
     */
    suspend fun publish(
        courseId: String,
        manifest: CorpusManifest,
        documents: List<CorpusDocument>,
    ): Long
}
```

### 4.5 Qué ve el alumno y cuándo

| Momento | Qué ve el alumno |
|---|---|
| El docente sube un material (`BORRADOR`) | Nada |
| El docente publica (modo `INMEDIATO`) | El material aparece en su biblioteca en la **siguiente sincronización** (o al abrir la app) con un chip **"nuevo"** |
| El docente edita y vuelve a publicar | El material cambia de contenido; el alumno ve el banner *"«Apuntes-IA» se actualizó"* (§4.6) |
| Modo `AL_PUBLICAR_CATEDRA` | Nada hasta que la cátedra se publica |
| Modo `DIARIO` | Nada hasta la hora del lote |

**El chip "nuevo" y el banner son la comunicación mínima**: el alumno tiene
que saber que el material cambió, porque la respuesta de la IA va a ser
distinta.

### 4.6 Cómo se comunica el cambio

Tres canales, con textos exactos:

**a) En la biblioteca del alumno** (al abrir la app):

```
┌─ Materiales · IA-2026-1 ──────────────────────────────────────┐
│ ⟳ Se actualizaron 2 materiales de esta cátedra.               │
│    · «Unidad 1 — Búsqueda heurística» (v3)                    │
│    · «Diapositivas — Clase 3» (v2)                            │
└────────────────────────────────────────────────────────────────┘
```

**b) En la lista de materiales** (chip por material):

```
📄 Unidad 1 — Búsqueda heurística      [v3 · actualizado]
```

**c) En el chat**, la primera vez que el alumno pregunta después de la
actualización (una sola vez por versión, guardada en el cliente):

```
ℹ Este material se actualizó: «Unidad 1 — Búsqueda heurística» (v3, 12/10).
  Las respuestas pueden ser distintas a las de antes.
```

En el lado del docente, la publicación muestra el resumen:

```
✅ «Apuntes-IA.pdf» publicado (v3).
   58 alumnos tienen la v2. 35 todavía no releyeron.
   → [Avisar a los que no releyeron]   [Ver el diff]
```

El "avisar" es un **anuncio de la cátedra** (la entidad ya diseñada en
`domain/07-anuncios.md`): "El material «Apuntes-IA» cambió: se corrigió el
ejemplo 3. Vuelvan a leerlo."

### 4.7 Qué se reindexa y cuándo

| Evento | Plano del docente | Plano del alumno | Manifiesto |
|---|---|---|---|
| Se sube un material | se reindexa | no | no |
| Se edita el texto (nueva versión) | se reindexa | **no** | no |
| Se publica (modo inmediato) | se reindexa | **se reindexa** | **se regenera** |
| Se retira | se reindexa | **se reindexa** | **se regenera** |
| Se cambia la visibilidad | se reindexa | se reindexa | se regenera |
| Lote diario | — | se reindexa | se regenera |
| Se publica la cátedra | se reindexa | se reindexa | se regenera |

> En el hito 1 el reindexado es **completo** (BM25 en memoria: 100 docs /
> 800 fragmentos ≈ 200 ms). Cuando el corpus sea grande, el índice se hace
> incremental (re-trocear sólo el material cambiado y fusionar), que es
> compatible con este diseño porque el índice se identifica por
> `materialId + versionId`.

---

## 5. El catálogo del corpus

### 5.1 La idea

El catálogo es la **pantalla principal** del docente en el área de materiales,
y es lo que convierte "un gestor de archivos" en "un producto". Cada fila
tiene que responder cinco preguntas:

1. **¿Qué es?** — título, tipo, unidad, etiquetas.
2. **¿Está bien?** — salud, checks, estado técnico.
3. **¿Lo ven los alumnos?** — estado editorial, visibilidad, rol, licencia.
4. **¿Sirve?** — estadísticas de uso (citas, alumnos, calidad de las respuestas).
5. **¿Hay que hacer algo?** — cola de acción: publicar, corregir, retirar,
   reindexar.

### 5.2 La fila del catálogo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusCatalog.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Una fila del catálogo del corpus.
 *
 * Es una **vista**: no se persiste. Se arma con el material, su versión
 * vigente, el informe de calidad y las estadísticas de uso.
 */
@Serializable
data class CorpusEntry(
    val materialId: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,

    // --- Estado ---
    val lifecycle: MaterialLifecycle = MaterialLifecycle.BORRADOR,
    val technicalStatus: MaterialStatus = MaterialStatus.PENDING,
    val visibleToStudents: Boolean = false,
    val role: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    val license: MaterialLicense = MaterialLicense.SIN_LICENCIA,

    // --- Tamaño y forma ---
    val sizeBytes: Long = 0L,
    val charCount: Int = 0,
    val chunkCount: Int = 0,
    val pageCount: Int = 0,

    // --- Versiones ---
    val currentVersionNumber: Int = 0,
    val publishedVersionNumber: Int = 0,
    /** ¿Hay una versión sin publicar? */
    val hasPendingChanges: Boolean = false,
    /** ¿La versión publicada es más vieja que la vigente? */
    val stalePublished: Boolean = false,

    // --- Organización ---
    val tags: List<String> = emptyList(),
    val unitId: String = "",
    val unitLabel: String = "",

    // --- Salud y uso ---
    val quality: CorpusQualityReport = CorpusQualityReport(),
    val usage: MaterialUsage = MaterialUsage(),

    // --- Tiempos ---
    val updatedAt: Long = 0L,
    val publishedAt: Long = 0L,
) {
    /** La salud, en un número 0..100 (para la barrita). */
    val health: Int get() = quality.healthScore

    /** ¿Necesita una acción del docente? */
    val needsAttention: Boolean
        get() = quality.blocking.isNotEmpty() ||
            quality.warnings.isNotEmpty() ||
            hasPendingChanges ||
            technicalStatus != MaterialStatus.READY
}

/**
 * Estadísticas de uso de un material.
 *
 * Se derivan de la analítica y de los eventos de corpus; nunca del modelo.
 */
@Serializable
data class MaterialUsage(
    /** Respuestas del modelo en las que este material fue citado. */
    val citations: Int = 0,
    /** Respuestas del modelo en las que apareció alguna fuente de este material. */
    val answers: Int = 0,
    /** Alumnos distintos que interactuaron con el material. */
    val students: Int = 0,
    /** Citas que el verificador clasificó como sin respaldo. */
    val unsupportedCitations: Int = 0,
    /** Aperturas del material en la vista del alumno. */
    val opens: Int = 0,
    /** Búsquedas dentro del material. */
    val searches: Int = 0,
    /** Última actividad (0 = nunca). */
    val lastActivityAt: Long = 0L,
    /** Alumnos que quedaron con una versión vieja (§3.7). */
    val staleStudents: Int = 0,
) {
    /** Tasa de citas sin respaldo, en puntos básicos (0..10000). */
    val unsupportedRate: Int
        get() = if (citations <= 0) 0 else (unsupportedCitations * 10_000) / citations

    /** ¿Lo usó alguien? */
    val isUsed: Boolean get() = citations > 0 || opens > 0 || searches > 0
}
```

### 5.3 De dónde salen las estadísticas

El cálculo es **determinista** (regla 3) y reusa lo que ya existe:

| Dato | Origen | Nota |
|---|---|---|
| `citations` | `ChatMessage.sourceRefs` (material ya versionado) | `teacher/09-calidad.md` §7.4 propone `sourceIds`; acá se usa `sourceRefs` con `versionId` |
| `answers` | Respuestas con al menos una fuente de este material | |
| `students` | Alumnos únicos de esas respuestas ∪ eventos de corpus | Sólo los que tienen `visibility.showsMetrics` |
| `unsupportedCitations` | `ChatMessage.citation` + cruce con el material | El `CitationVerifier` ya separa los motivos |
| `opens` / `searches` | `CorpusEvent` (§5.4) | Sólo si `corpusPolicy.trackUsage` |
| `staleStudents` | `CorpusExposureAnalyzer` (§3.7) | |

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusCatalogBuilder.kt
object CorpusCatalogBuilder {

    /**
     * Arma el catálogo del corpus de una cátedra.
     *
     * @param materials materiales de la cátedra.
     * @param versions versiones de esos materiales.
     * @param qualityByMaterial informes de calidad por material.
     * @param usage estadísticas de uso por material.
     */
    fun build(
        course: Course,
        materials: List<Material>,
        versions: List<MaterialVersion>,
        qualityByMaterial: Map<String, CorpusQualityReport>,
        usageByMaterial: Map<String, MaterialUsage>,
    ): CorpusCatalog
}

/** El catálogo completo. */
@Serializable
data class CorpusCatalog(
    val courseId: String,
    val entries: List<CorpusEntry> = emptyList(),
    /** Totales para la cabecera. */
    val summary: CatalogSummary = CatalogSummary(),
) {
    /** Filtra el catálogo. */
    fun filter(filter: CatalogFilter): CorpusCatalog
}

/** Filtros del catálogo. */
@Serializable
data class CatalogFilter(
    val query: String = "",
    val lifecycle: Set<MaterialLifecycle> = emptySet(),
    val kinds: Set<MaterialKind> = emptySet(),
    val roles: Set<MaterialUsagePolicy> = emptySet(),
    val tags: Set<String> = emptySet(),
    val unitId: String = "",
    /** Sólo los materiales con checks o cambios pendientes. */
    val onlyNeedsAttention: Boolean = false,
)

/** Totales de la cabecera del catálogo. */
@Serializable
data class CatalogSummary(
    val materials: Int = 0,
    val published: Int = 0,
    val inReview: Int = 0,
    val drafts: Int = 0,
    val retired: Int = 0,
    val chunks: Int = 0,
    val chars: Long = 0L,
    val sizeBytes: Long = 0L,
    val withBlocking: Int = 0,
    val withWarnings: Int = 0,
    val pendingPublication: Int = 0,
    val unused: Int = 0,
)
```

### 5.4 Los eventos de corpus (lo que el alumno hizo con el material)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusEvent.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Un evento de uso del corpus.
 *
 * Guarda **metadatos, nunca texto**: quién abrió qué material, qué versión
 * estaba vigente y cuándo. Es la fuente de "el 40 % leyó la versión vieja".
 */
@Serializable
data class CorpusEvent(
    val id: String,
    val courseId: String,
    val materialId: String,
    /** Versión vigente en el momento del evento. */
    val versionId: String = "",
    /** Qué hizo. */
    val kind: CorpusEventKind,
    /** Usuario (id). */
    val userId: String,
    /** Instante (epoch millis). */
    val at: Long,
)

@Serializable
enum class CorpusEventKind(val label: String) {
    OPENED("abrió el material"),
    SEARCHED("buscó en el material"),
    CITED("fue citado en una respuesta"),
    DOWNLOADED("descargó el material"),
}
```

El repositorio es append-only con tope (como el `AccessLog` de
`domain/08-permisos.md`):

```kotlin
interface CorpusEventRepository {
    suspend fun list(courseId: String, since: Long = 0L): List<CorpusEvent>
    suspend fun append(event: CorpusEvent)
    suspend fun clear(courseId: String)
}
```

**Si el docente no quiere esto, se apaga**: `corpusPolicy.trackUsage = false`
deja de registrar eventos. Es una decisión explícita del docente, porque
"que el profesor vea qué material abre cada alumno" es información sensible
aunque sea sólo metadata.

### 5.5 La pantalla del catálogo

```
┌─ Corpus · IA-2026-1 ───────────────────────────────────────────────────────┐
│ 12 materiales · 87 fragmentos · 312 MB            [Subir] [⟳ Reindexar]     │
│ ⚠ 1 con problemas · 2 sin publicar · 3 sin uso desde hace 30 días         │
├────────────────────────────────────────────────────────────────────────────┤
│ [🔍 buscar…] [Estado ▾] [Tipo ▾] [Unidad ▾] [Etiquetas ▾] [Ordenar ▾]     │
│                                                                            │
│ ●  📄 Unidad 1 — Búsqueda heurística          v3 publicado   🟢 100        │
│    Documento · 1,2 MB · 24 fragmentos · 18.430 car. · 12/10                │
│    #Unidad-1 #Parcial-1 · 24 citas · 11 alumnos · 0 sin respaldo           │
│                                                                            │
│ ⚠  📊 Diapositivas — Clase 3                   v2 publicado   🟡  74        │
│    Diapositivas · 4,1 MB · 61 fragmentos · 92.004 car. · 10/10              │
│    #Unidad-2 · 61 citas · 22 alumnos · ⚠ 12 citas sin respaldo             │
│    ⚠ "El 30 % de los fragmentos son mayores a 2.000 caracteres."            │
│                                                                            │
│ ●  🔗 Repo de la cátedra — GitHub             v1 publicado   🟢  92        │
│    Enlace · 12 fragmentos · se refresca cada día · revisado hace 2 h        │
│                                                                            │
│ ○  📝 Parcial 1 2025                            v1 borrador    ▫  —         │
│    Apuntes · 0,4 MB · 5 fragmentos · sólo el docente lo ve                  │
│    [Revisar] [Publicar]                                                   │
└────────────────────────────────────────────────────────────────────────────┘
```

- El **círculo de color** es el estado editorial: ● verde = publicado, ⚠
  ámbar = en revisión, ○ gris = borrador, ⊘ tachado = retirado.
- La **barrita de salud** (0..100) resume los checks: 100 = sin observaciones,
  menos de 60 = el material necesita trabajo.
- La **flecha de versión** (`v3`) con un punto naranja indica "hay cambios sin
  publicar".

### 5.6 Filtros y orden

| Filtro | Valores |
|---|---|
| Estado | Borrador / En revisión / Publicado / Retirado |
| Tipo | Los `MaterialKind` |
| Rol | Indexado / Sólo referencia / Sin IA / Sin IA local |
| Unidad | Las unidades de la taxonomía (`domain/10-taxonomia.md`) |
| Etiquetas | El vocabulario de la cátedra |
| Salud | Sólo con bloqueantes / sólo con advertencias |
| Pendientes | Sólo con cambios sin publicar |
| Sin uso | Sólo los que hace 30 días no usa nadie |

| Orden | Por qué |
|---|---|
| **Recientes** (por defecto) | Lo último que tocó el docente |
| **Más usados** | Lo que los alumnos consultan → primero lo que hay que mantener |
| **Más citados** | Idem, con la métrica de citas |
| **Más problemáticos** | Primero lo que hay que arreglar (tasa de citas sin respaldo) |
| **Alfabético** | Para buscar |
| **Por unidad** | Para preparar la clase |

### 5.7 Materiales huérfanos

Un material que nadie cita hace 30 días es **ruido**: ocupa índice, infla el
catálogo y le roba atención al docente. El catálogo lo señala, pero **no lo
borra**:

```
⚘ «Parcial 1 2025» no lo consultó nadie en 38 días (0 citas, 3 aperturas).
  [Retirar]  [Dejar como está]
```

La acción sugerida es **retirar** (§2.5), que conserva todo y saca el
material del corpus del alumno. Es la diferencia entre "curar" y "borrar".

---

## 6. La calidad del corpus: los checks automáticos

### 6.1 El marco

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusQuality.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/** Qué tan grave es un problema del material. */
@Serializable
enum class CheckSeverity(val label: String) {
    /** Impide publicar el material. */
    BLOQUEANTE("Bloqueante"),

    /** Se puede publicar, pero hay que revisarlo. */
    ADVERTENCIA("Advertencia"),

    /** Informativo: no requiere acción. */
    INFO("Info"),
}

/**
 * Un check de calidad del corpus.
 *
 * El [message] es el texto **exacto** que ve el docente: no se arma en la UI
 * para que el mensaje sea el mismo en todos los lugares donde aparece.
 */
@Serializable
data class CorpusCheck(
    /** Identificador estable ("VACIO", "DUPLICADO", …). */
    val id: String,
    val severity: CheckSeverity,
    /** Título corto: "El texto está vacío". */
    val title: String,
    /** Mensaje completo, en español, con la acción a seguir. */
    val message: String,
    /** Acción sugerida: "Editar texto", "Reindexar", "Revisar". */
    val action: String = "",
    /** Cuántos casos hay (p. ej. 12 fragmentos diminutos). */
    val count: Int = 0,
)

/** Informe de calidad de un material. */
@Serializable
data class CorpusQualityReport(
    val checks: List<CorpusCheck> = emptyList(),
    /** Instante del análisis. */
    val checkedAt: Long = 0L,
    /** Versión analizada. */
    val versionId: String = "",
) {
    /** Checks que impiden publicar. */
    val blocking: List<CorpusCheck> get() = checks.filter { it.severity == CheckSeverity.BLOQUEANTE }

    /** Checks que hay que revisar. */
    val warnings: List<CorpusCheck> get() = checks.filter { it.severity == CheckSeverity.ADVERTENCIA }

    /** ¿Se puede publicar? */
    val publishable: Boolean get() = blocking.isEmpty()

    /**
     * Salud del material, 0..100.
     *
     * 100 = sin observaciones; cada bloqueante resta 30 y cada advertencia 8.
     */
    val healthScore: Int
        get() = (100 - blocking.size * 30 - warnings.size * 8).coerceIn(0, 100)

    /** ¿Hay algo que reportar? */
    val hasObservations: Boolean get() = checks.isNotEmpty()
}
```

### 6.2 Los checks

| id | Severidad | Qué detecta | Algoritmo (resumen) | Mensaje (exacto) |
|---|---|---|---|---|
| `VACIO` | **BLOQUEANTE** | No se extrajo texto (PDF escaneado, PPTX de imágenes) | `text.isBlank()` | "No se pudo extraer texto: el archivo parece un escaneo (0 de 12 páginas tienen texto). Subilo con texto seleccionable o pegá el texto en «Editar texto»." |
| `SIN_FRAGMENTOS` | **BLOQUEANTE** | Hay texto pero el troceado no produjo nada útil | `chunks.isEmpty() && text.isNotBlank()` | "El texto tiene ${text.length} caracteres pero no se generó ningún fragmento: el material no se puede consultar." |
| `PAGINAS_SIN_TEXTO` | ADVERTENCIA | PDF parcialmente escaneado | páginas con texto < 40 % | "Sólo 3 de 12 páginas tienen texto: el resto es un escaneo y la IA no lo va a poder leer." |
| `TEXTO_CORTO` | ADVERTENCIA | Material con muy poco texto | `text.length < 500` | "El texto es muy corto (312 caracteres): este material casi no aporta al índice." |
| `CHUNK_DIMINUTO` | ADVERTENCIA | Troceado en pedazos inservibles | % de fragmentos con < 80 caracteres > 20 % | "El 34 % de los fragmentos (12 de 35) son más chicos que 80 caracteres: el troceado parte el material en pedazos que el modelo no puede usar. Probá subir el tamaño de fragmento (ahora ${course.chunkSize})." |
| `CHUNK_GIGANTE` | ADVERTENCIA | Fragmentos que desbordan el contexto | % de fragmentos > 1,8 × `chunkSize` > 10 % | "Hay 7 fragmentos de más de 2.000 caracteres: el modelo los lee enteros y las citas quedan larguísimas." |
| `SIN_ENCABEZADOS` | ADVERTENCIA | Documento sin estructura (no hay `##`) | ninguna línea empieza con `#` y el texto tiene > 2.000 caracteres | "El texto no tiene encabezados: cada fragmento va a quedar sin título y las citas van a decir sólo «cátedra: Apuntes-IA»." |
| `DUPLICADO` | **BLOQUEANTE** | El mismo contenido ya está en la cátedra | `contentHash` igual al de otro material | "Este material es idéntico a «Apuntes-IA.pdf» (mismo contenido). No lo agregues dos veces: elegí uno y ocultá el otro." |
| `CASI_DUPLICADO` | ADVERTENCIA | Contenido casi idéntico (≥ 90 % de shingles en común) | Jaccard de shingles de 5 palabras | "Este material es casi idéntico a «Apuntes-IA.pdf» (92 % del texto se repite). Revisá si no es el mismo archivo con otro nombre." |
| `RUIDO_OCR` | ADVERTENCIA | Texto con basura de reconocimiento óptico | ratio de caracteres raros > 4 % **o** ratio de símbolos > 1,5 % **o** ratio de letras sueltas > 3 % | "El texto parece venir de un OCR: hay 7 % de caracteres raros («¤», «¶», «§»). La IA va a entender menos de lo que parece." |
| `CODIFICACION_MALA` | ADVERTENCIA | Texto leído con el encoding equivocado | cantidad de `Ã`, `Â`, `â€`, `�` > 0,1 % | "El texto tiene caracteres rotos (parece Latin-1 leído como UTF-8). Volvé a subir el archivo o corregilo en «Editar texto»." |
| `IDIOMA_INCONSISTENTE` | ADVERTENCIA | Mezcla de idiomas | ratio de marcadores de otro idioma > 15 % | "El 23 % del texto está en inglés y el material de la cátedra es en español: las citas van a mezclar idiomas." |
| `TRUNCADO` | ADVERTENCIA | El texto se cortó por un límite | `extraction.truncated` | "El texto extraído tiene 3.200.000 caracteres; se indexaron los primeros 2.000.000. Revisá el texto en «Editar texto»." |
| `ENLACE_MUERTO` | ADVERTENCIA | La URL no responde | último `GET` con error o 4xx/5xx | "El enlace no respondió (última comprobación: 12/10, error 404). Los alumnos siguen viendo la última versión buena." |
| `SIN_USO` | INFO | Nadie lo consulta | 0 citas y 0 aperturas en 30 días | "Nadie consultó este material en 38 días. Si ya no sirve, retiralo: ocupa índice y ruido." |
| `VERSION_VIEJA` | INFO | La versión publicada no es la vigente | `publishedVersionNumber < currentVersionNumber` | "Los alumnos están viendo la v2 y la v3 todavía no se publicó." |

### 6.3 Los algoritmos no obvios

#### 6.3.1 Texto vacío y páginas sin texto

```kotlin
// :backend, rag/corpus/CorpusQualityChecker.kt
private fun checkEmpty(material: Material, text: String): CorpusCheck? {
    if (text.isNotBlank()) return null
    val pages = material.pageCount
    val detail = if (pages > 0) " (0 de $pages páginas tienen texto)" else ""
    return CorpusCheck(
        id = "VACIO",
        severity = CheckSeverity.BLOQUEANTE,
        title = "El texto está vacío",
        message = "No se pudo extraer texto$detail. El archivo parece un escaneo: " +
            "subilo con texto seleccionable o pegá el texto en «Editar texto».",
        action = "Editar texto",
    )
}
```

#### 6.3.2 Chunks diminutos y gigantes

```kotlin
/** Umbral de fragmento "inservible". */
const val MIN_USEFUL_CHARS = 80

private fun checkChunkSizes(chunks: List<Chunk>, course: Course): List<CorpusCheck> {
    if (chunks.isEmpty()) return emptyList()
    val tiny = chunks.count { it.text.length < MIN_USEFUL_CHARS }
    val tinyShare = (tiny * 100) / chunks.size
    val out = mutableListOf<CorpusCheck>()

    if (tinyShare > 20) {
        out += CorpusCheck(
            id = "CHUNK_DIMINUTO",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Fragmentos demasiado chicos",
            message = "El $tinyShare % de los fragmentos ($tiny de ${chunks.size}) son más chicos " +
                "que $MIN_USEFUL_CHARS caracteres: el troceado parte el material en pedazos que el " +
                "modelo no puede usar. Probá subir el tamaño de fragmento (ahora ${course.chunkSize}).",
            action = "Ajustar fragmento",
            count = tiny,
        )
    }

    val limit = (course.chunkSize * 1.8).toInt()
    val huge = chunks.count { it.text.length > limit }
    val hugeShare = (huge * 100) / chunks.size
    if (hugeShare > 10) {
        out += CorpusCheck(
            id = "CHUNK_GIGANTE",
            severity = CheckSeverity.ADVERTENCIA,
            title = "Fragmentos demasiado grandes",
            message = "Hay $huge fragmentos de más de $limit caracteres: el modelo los lee enteros " +
                "y las citas quedan larguísimas.",
            action = "Ajustar fragmento",
            count = huge,
        )
    }
    return out
}
```

> Nota: el `Chunker` actual (`rag/retrieval/Chunker.kt`) parte por encabezados
> y luego por tamaño, con solapamiento. Los fragmentos diminutos aparecen
> cuando hay muchos encabezados cortos ("## Página 12" con dos líneas) — un
> problema real de los PDF con marcas de página de `teacher/03-materiales.md`
> §4.3. Por eso el check importa.

#### 6.3.3 Ruido de OCR

```kotlin
/** Caracteres que un PDF bien extraído no tiene. */
private val RARE = Regex("[^\\p{L}\\p{N}\\s.,;:!?()\\[\\]{}«»\"'…\\-–—_/%+=*#@&|<>\\\\]")

/** Secuencias de símbolos sin letras ni números. */
private val SYMBOL_RUN = Regex("[^\\p{L}\\p{N}\\s]{3,}")

private fun checkOcrNoise(text: String): CorpusCheck? {
    if (text.length < 500) return null
    val rare = RARE.findAll(text).count().toDouble() / text.length
    val symbols = SYMBOL_RUN.findAll(text).sumOf { it.value.length }.toDouble() / text.length
    val loneLetters = Regex("(?<![\\p{L}\\p{N}])\\p{L}(?![\\p{L}\\p{N}])").findAll(text).count()
    val loneShare = loneLetters.toDouble() / text.length
    val score = maxOf(rare, symbols * 2, loneShare)
    if (score < 0.04) return null
    val pct = (score * 100).toInt()
    return CorpusCheck(
        id = "RUIDO_OCR",
        severity = CheckSeverity.ADVERTENCIA,
        title = "El texto parece un OCR",
        message = "El texto parece venir de un OCR: hay $pct % de caracteres raros. " +
            "La IA va a entender menos de lo que parece. Revisalo en «Editar texto» o subí el original.",
        action = "Editar texto",
    )
}
```

#### 6.3.4 Idioma inconsistente

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/LanguageProfile.kt
package kitoru.domain

/**
 * Perfil de idioma, determinista y sin modelo.
 *
 * No es un detector de idioma académico: es un **contador de marcadores
 * léxicos**. Suficiente para la pregunta que importa ("¿este material está
 * en el idioma de la cátedra?").
 */
object LanguageProfile {

    /** Marcadores discriminativos por idioma (elegidos para no solaparse). */
    private val MARKERS: Map<String, Set<String>> = mapOf(
        "es" to setOf(
            "los", "las", "una", "del", "más", "ción", "para", "como", "este",
            "esta", "sus", "entre", "también", "pero", "donde", "cuál",
        ),
        "en" to setOf(
            "the", "and", "of", "to", "in", "is", "that", "this", "with",
            "for", "are", "was", "were", "which", "from", "have", "been",
        ),
        "pt" to setOf(
            "não", "uma", "com", "ção", "você", "mais", "quando", "sobre",
            "deve", "entre", "também", "pelo", "pela", "seu", "sua",
        ),
    )

    /** Resultado del análisis de un texto. */
    data class Signal(
        /** Idioma dominante detectado. */
        val language: String,
        /** Marcadores del idioma dominante. */
        val markers: Int,
        /** Proporción de tokens en otro idioma (0..1). */
        val foreignRatio: Double,
    )

    /**
     * Analiza un texto.
     *
     * @param tokens tokens ya normalizados (los produce el tokenizador del
     *   índice, que saca acentos y palabras vacías).
     */
    fun detect(tokens: List<String>): Signal {
        if (tokens.isEmpty()) return Signal("es", 0, 0.0)
        val counts = MARKERS.mapValues { (_, set) -> tokens.count { it in set } }
        val dominant = counts.maxByOrNull { it.value } ?: return Signal("es", 0, 0.0)
        val foreign = counts.filterKeys { it != dominant.key }.sumOf { it.value }
        return Signal(
            language = dominant.key,
            markers = dominant.value,
            foreignRatio = foreign.toDouble() / tokens.size,
        )
    }
}
```

El check compara con `course.corpusPolicy.language`:

```kotlin
private fun checkLanguage(text: String, course: Course): CorpusCheck? {
    val tokens = tokenize(text)                       // el mismo del índice
    if (tokens.size < 200) return null
    val signal = LanguageProfile.detect(tokens)
    val expected = course.corpusPolicy.language
    if (signal.language == expected) return null
    if (signal.foreignRatio < 0.15) return null
    val pct = (signal.foreignRatio * 100).toInt()
    return CorpusCheck(
        id = "IDIOMA_INCONSISTENTE",
        severity = CheckSeverity.ADVERTENCIA,
        title = "Idioma distinto al de la cátedra",
        message = "El $pct % del texto está en ${signal.language} y el material de la cátedra " +
            "está en $expected: las citas van a mezclar idiomas.",
        action = "Revisar",
    )
}
```

### 6.4 El hash de contenido

La detección de duplicados se hace por **hash del contenido normalizado**, no
por nombre ni por tamaño.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ContentHasher.kt
package kitoru.domain

/**
 * Hash del contenido de un material.
 *
 * No es criptográfico: es **FNV-1a de 64 bits**, suficiente para detectar
 * duplicados dentro de una cátedra y trivial de testear. Si alguna vez hay
 * que comparar contra un catálogo externo, la interfaz permite cambiar la
 * implementación sin tocar a nadie.
 */
object ContentHasher {

    /**
     * Normaliza el texto antes de hashear:
     * sin acentos, en minúsculas, sin marcas de página y con espacios
     * colapsados. Así, el mismo PDF re-exportado por otra herramienta
     * produce el mismo hash.
     */
    fun normalize(text: String): String {
        val noAccents = stripDiacritics(text).lowercase()
        val noPageMarks = noAccents.replace(Regex("(?m)^\\s*#+?\\s*página\\s*\\d+.*$"), " ")
        return noPageMarks.replace(Regex("\\s+"), " ").trim()
    }

    /** Hash del texto normalizado, en hexadecimal (16 dígitos). */
    fun of(text: String): String {
        val bytes = normalize(text).encodeToByteArray()
        var hash = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325 (FNV offset basis)
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 0x100000001b3L                // FNV prime
        }
        val hex = "0123456789abcdef"
        val sb = StringBuilder(16)
        for (shift in 60 downTo 0 step 4) {
            sb.append(hex[((hash ushr shift) and 0xF).toInt()])
        }
        return sb.toString()
    }
}
```

> **Trampa de portabilidad.** `java.lang.String.format("%016x", hash)` es lo
> que se escribe primero y **no compila en `commonMain`**: el formateo de
> números en hexadecimal hay que hacerlo a mano (como arriba) o con
> `hash.toULong().toString(16).padStart(16, '0')` (Kotlin 1.5+; `toULong` es
> `experimental` hasta Kotlin 2.0). El literal `0xcbf29ce484222325L` tampoco
> existe como `Long` (es mayor que `Long.MAX_VALUE`): se escribe con su
> complemento a dos, `-0x340d631b7bdddcdbL`.

La comparación de duplicados:

```kotlin
// :backend, rag/corpus/CorpusQualityChecker.kt
private fun checkDuplicates(
    material: Material,
    hash: String,
    siblings: List<Material>,
    siblingHashes: Map<String, String>,
): List<CorpusCheck> {
    val out = mutableListOf<CorpusCheck>()
    val exact = siblings.firstOrNull { it.id != material.id && siblingHashes[it.id] == hash }
    if (exact != null) {
        out += CorpusCheck(
            id = "DUPLICADO",
            severity = CheckSeverity.BLOQUEANTE,
            title = "El material ya estaba en la cátedra",
            message = "Este material es idéntico a «${exact.title}» (mismo contenido). " +
                "No lo agregues dos veces: elegí uno y ocultá el otro.",
            action = "Ver el otro",
        )
    }
    return out
}
```

Los **casi duplicados** usan shingles de 5 palabras y Jaccard:

```kotlin
/**
 * Proporción de shingles de 5 palabras compartidos por dos textos (0..1).
 *
 * Es O(n) con un `Set` de hashes: no se comparan todos contra todos.
 */
fun shingleSimilarity(a: String, b: String, k: Int = 5): Double {
    val sa = shingles(a, k)
    val sb = shingles(b, k)
    if (sa.isEmpty() || sb.isEmpty()) return 0.0
    val shared = sa.count { it in sb }
    return shared.toDouble() / (sa.size + sb.size - shared)
}
```

Costo: O(n) por par. Con 50 materiales son 1.225 pares × 200 KB de texto: 245
MB de cómputo. Por eso el check de casi-duplicado **sólo corre** cuando el
material nuevo se sube y se compara contra los **hashes de rolling** (MinHash
de 64 firmas) precomputados por material: el par se descarta si las firmas no
se parecen. Es la misma técnica de `teacher/09-calidad.md` no propone, pero es
la única que escala sin servidor.

### 6.5 Severidad → efecto

| Severidad | Efecto en la publicación | Efecto en el catálogo |
|---|---|---|
| `BLOQUEANTE` | **Impide publicar**: el diálogo de publicación muestra el mensaje y el botón queda deshabilitado | Chip rojo en la fila; el material va primero en el orden "más problemáticos" |
| `ADVERTENCIA` | Se puede publicar, pero el diálogo muestra el aviso arriba ("Publicá igual") | Chip ámbar + el mensaje bajo la fila |
| `INFO` | No impide nada | Chip gris, sólo en el detalle |

El diálogo de publicación:

```
┌ Publicar «Apuntes-IA.pdf» (v3) ─────────────────────────────┐
│ ⚠ El texto no tiene encabezados: las citas van a decir sólo   │
│   «cátedra: Apuntes-IA».                                      │
│                                                                 │
│ Cambios: 1 bloque modificado (## Página 7)                     │
│ 23 alumnos tienen la v2. 35 todavía no releyeron.              │
│                                                                 │
│ [Cancelar]                     [Publicar igual]                │
└─────────────────────────────────────────────────────────────────┘
```

### 6.6 Dónde corre el checker

El checker necesita los fragmentos **reales** (los del `Chunker` con el
`chunkSize` de la cátedra), así que vive en `:backend`:

```kotlin
// backend/src/commonMain/kotlin/rag/corpus/CorpusQualityChecker.kt
package rag.corpus

import kitoru.domain.*
import rag.retrieval.Chunk
import rag.retrieval.Chunker
import rag.retrieval.Doc

/**
 * Checks de calidad del corpus.
 *
 * **Determinista y sin modelo**: los mismos datos dan el mismo informe en
 * cualquier máquina (regla 3 del proyecto). Vive en `:backend` porque usa el
 * [Chunker] real: el check de "fragmentos diminutos" tiene que mirar los
 * fragmentos que van a entrar al índice, no una aproximación.
 */
object CorpusQualityChecker {

    /**
     * Analiza un material.
     *
     * @param material material a analizar.
     * @param text texto de la versión vigente.
     * @param course cátedra (define `chunkSize` y el idioma).
     * @param siblings los demás materiales de la cátedra (para los duplicados).
     * @param siblingHashes hash de contenido de los hermanos.
     * @param pageTexts texto por página (sólo para PDFs), si se tiene.
     */
    fun check(
        material: Material,
        text: String,
        course: Course,
        siblings: List<Material> = emptyList(),
        siblingHashes: Map<String, String> = emptyMap(),
        pageTexts: List<String> = emptyList(),
    ): CorpusQualityReport {
        val chunks = Chunker.split(
            Doc(source = "cátedra", path = material.title, text = text, materialId = material.id),
            course.chunkSize,
            course.chunkSize / 8,
        )
        val checks = buildList {
            checkEmpty(material, text)?.let { add(it) }
            addAll(checkChunkSizes(chunks, course))
            checkHeadings(text)?.let { add(it) }
            checkOcrNoise(text)?.let { add(it) }
            checkEncoding(text)?.let { add(it) }
            checkLanguage(text, course)?.let { add(it) }
            checkScannedPages(material, pageTexts)?.let { add(it) }
            addAll(checkDuplicates(material, ContentHasher.of(text), siblings, siblingHashes))
        }
        return CorpusQualityReport(checks = checks, checkedAt = 0L, versionId = material.currentVersionId)
    }
}
```

**Cuándo corre:**

| Momento | Qué se analiza |
|---|---|
| Al indexar (reindexado completo) | Todos los materiales publicados: se recalcula el informe y se guarda en `Material.quality` |
| Al subir un material | Sólo el nuevo (los demás no cambian) |
| Al editar el texto | Sólo el material editado |
| Al publicar | Se revalida: si un check nuevo es bloqueante, la publicación se corta (§2.4) |

El informe se guarda en el material (`Material.quality`) para que el catálogo
no tenga que recalcularlo cada vez que se dibuja.

---

## 7. El flujo de "material nuevo": el loop de retroalimentación

### 7.1 El flujo completo

```
 1. SUBIR      el docente elige el archivo (o arrastra o comparte)
        │
 2. COPIAR     → courses/<courseId>/files/mat_7f3a__Apuntes-IA.pdf
        │
 3. EXTRAER    → texto (+ aviso si es escaneo)
        │
 4. VERSIONAR  → se crea la v1 con el texto extraído
        │
 5. TROCEAR    → 37 fragmentos
        │
 6. CHECKS     → 1 advertencia ("sin encabezados")
        │
 7. INDEXAR    → entra al plano del docente (el del alumno, cuando se publique)
        │
 8. SUGERIR    → "¿Qué es un perceptrón?"  ← el loop se cierra acá
        │
 9. PROBAR     → el docente ve los 3 fragmentos recuperados con su puntaje
        │
10. PUBLICAR   → (o "dejar en borrador" / "pedir revisión")
```

### 7.2 El mensaje

El resultado de la ingestión, en el panel del docente:

```
┌ Subido «Apuntes-IA.pdf» ───────────────────────────────────────────┐
│                                                                   │
│ ✅ El material quedó indexado en 37 fragmentos.                   │
│                                                                   │
│ ⚠ 1 advertencia: el texto no tiene encabezados.                  │
│                                                                   │
│ Probá con esta pregunta:                                          │
│    [ ¿Qué es un perceptrón? ]  [ ¿Cómo se entrena una red? ]      │
│    [ ¿Qué dice sobre función de activación? ]                     │
│                                                                   │
│ [Ver los 37 fragmentos]  [Probar la pregunta]  [Publicar]        │
└───────────────────────────────────────────────────────────────────┘
```

El texto exacto (que es el que se muestra en el snackbar cuando la subida
viene por drag & drop o por `ACTION_SEND`):

> **"El material quedó indexado en 37 fragmentos; probá con esta pregunta: ¿Qué es un perceptrón?"**

### 7.3 Cómo se sugiere la pregunta (determinista)

La pregunta de ejemplo tiene que cumplir tres cosas:

1. **Ser respondible con ese material** (si no, el docente pierde la confianza
   en la herramienta).
2. **Ser una pregunta que un alumno haría de verdad**.
3. **No depender del modelo** (determinismo + sin costo + sin latencia).

El algoritmo elige el **fragmento más informativo** y arma la pregunta a
partir de su contenido:

```kotlin
// backend/src/commonMain/kotlin/rag/corpus/CorpusQuestionSuggester.kt
package rag.corpus

import kitoru.domain.Material
import kitoru.domain.SuggestedQuestion
import rag.retrieval.Chunk

/**
 * Propone preguntas de prueba para un material recién indexado.
 *
 * Determinista: el mismo material y el mismo índice dan las mismas
 * preguntas, siempre en el mismo orden.
 */
object CorpusQuestionSuggester {

    /** Cuántas preguntas se proponen como máximo. */
    const val MAX_SUGGESTIONS = 3

    /** Patrón de definición: "el perceptrón es …", "backpropagation: …". */
    private val DEFINITION = Regex(
        "(?i)\\b([\\p{L}][\\p{L} ]{2,40}?)\\s+(es|son|se define como|se denomina|significa)\\s+",
    )

    /** Marcadores de ejemplo. */
    private val EXAMPLE = Regex("(?i)\\b(ejemplo|ej\\.|por ejemplo|consideremos)\\b")

    /**
     * @param chunks fragmentos del material (los que entraron al índice).
     * @param df frecuencia de cada término en el corpus (para elegir el
     *   término más distintivo).
     */
    fun suggest(
        material: Material,
        chunks: List<Chunk>,
        df: Map<String, Int> = emptyMap(),
    ): List<SuggestedQuestion> {
        if (chunks.isEmpty()) return emptyList()
        val scored = chunks.mapIndexed { index, chunk ->
            index to score(chunk, df)
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })

        val out = mutableListOf<SuggestedQuestion>()
        val usedHeadings = mutableSetOf<String>()
        for ((index, _) in scored) {
            if (out.size >= MAX_SUGGESTIONS) break
            val chunk = chunks[index]
            val question = questionFor(chunk, df) ?: continue
            if (!usedHeadings.add(chunk.heading)) continue
            out += SuggestedQuestion(
                question = question,
                chunkIndex = index,
                heading = chunk.heading,
                reason = reasonFor(chunk),
            )
        }
        return out
    }

    /** Puntaje de "este fragmento sirve para una pregunta de prueba". */
    private fun score(chunk: Chunk, df: Map<String, Int>): Int {
        var s = 0
        val len = chunk.text.length
        if (chunk.heading.isNotBlank() && !chunk.heading.startsWith("Página")) s += 3
        if (len in 200..900) s += 2
        if (DEFINITION.containsMatchIn(chunk.text)) s += 4
        if (EXAMPLE.containsMatchIn(chunk.text)) s += 2
        if (Regex("(?m)^\\s*(?:[-*]|\\d+[.)])\\s+").containsMatchIn(chunk.text)) s += 2
        if (Regex("\\d").containsMatchIn(chunk.text)) s += 1
        if (len < MIN_USEFUL_CHARS) s -= 3
        if (chunk.text.count { it == '\n' } < 2) s -= 1
        return s
    }

    /** Arma la pregunta a partir del fragmento. */
    private fun questionFor(chunk: Chunk, df: Map<String, Int>): String? {
        DEFINITION.find(chunk.text)?.let { m ->
            val subject = m.groupValues[1].trim().trimStart("el ", "la ", "los ", "las ")
            if (subject.length in 3..60) return "¿Qué es $subject?"
        }
        val heading = chunk.heading.trim()
        if (heading.isNotBlank() && !heading.startsWith("Página")) {
            return "¿Qué dice el material sobre «$heading»?"
        }
        // Último recurso: el término más distintivo del fragmento.
        val term = mostDistinctiveTerm(chunk.text, df) ?: return null
        return "¿Qué dice el material sobre $term?"
    }

    /** El token menos común del corpus (el más "distintivo"). */
    private fun mostDistinctiveTerm(text: String, df: Map<String, Int>): String? =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 5 }
            .distinct()
            .minByOrNull { df[it] ?: 0 }
}
```

> El `df` (frecuencia de documentos) es el que ya calcula `Bm25Index`; hay que
> exponerlo: `fun Bm25Index.documentFrequencies(): Map<String, Int>`.

### 7.4 La prueba: qué muestra el resultado

El docente clickea "¿Qué es un perceptrón?" y la app hace **exactamente** lo
que hace el chat del alumno, pero aislado sobre ese material:

```
┌ Probar pregunta ─────────────────────────────────────────────────┐
│ ¿Qué es un perceptrón?                              [Probar]       │
│ ◉ Sólo este material   ○ Toda la cátedra                          │
├────────────────────────────────────────────────────────────────────┤
│ #1   12.43   cátedra · Apuntes-IA · Página 7      ← el esperado   │
│     «…el perceptrón es la unidad básica de una red neuronal…»      │
│ #2    3.10   cátedra · Apuntes-IA · Página 3                      │
│     «…las redes neuronales se componen de capas de perceptrones…»  │
├────────────────────────────────────────────────────────────────────┤
│ ✅ El material contiene la respuesta.                             │
│ [Probar de extremo a extremo con el modelo]                        │
└────────────────────────────────────────────────────────────────────┘
```

(La pantalla de prueba y sus diagnósticos ya están diseñados en
`teacher/03-materiales.md` §5.3; acá se agrega el **origen** de la pregunta:
la sugerencia determinista.)

### 7.5 Las preguntas de control (el smoke test del corpus)

El paso que convierte el corpus en algo que se puede **cuidar**: el docente
guarda las preguntas que usa en clase y la app las corre en cada reindexado.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ControlQuestion.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Una pregunta de control del corpus.
 *
 * Es la forma de automatizar "probalo": el docente marca la pregunta que
 * usa en clase y la app verifica que el material siga respondiéndola.
 */
@Serializable
data class ControlQuestion(
    val id: String,
    val courseId: String,
    val materialId: String,
    /** La pregunta, tal como la hace un alumno. */
    val question: String,
    /** Hash del texto del fragmento que debería recuperar. */
    val expectedChunkHash: String = "",
    val createdAt: Long = 0L,
    val createdBy: String = "",
    /** Última vez que se ejecutó. */
    val lastRunAt: Long = 0L,
    /** Cuándo funcionó por última vez. */
    val lastOkAt: Long = 0L,
    /** Resultado de la última ejecución. */
    val lastStatus: ControlStatus = ControlStatus.DESCONOCIDO,
)

@Serializable
enum class ControlStatus(val label: String) {
    /** La pregunta recupera el fragmento esperado. */
    OK("Responde"),

    /** Ya no recupera el fragmento esperado. */
    FALLA("Ya no responde"),

    /** Nunca se ejecutó. */
    DESCONOCIDO("Sin ejecutar"),
}
```

El ejecutor (en `:backend`, porque necesita recuperar del índice):

```kotlin
// backend/src/commonMain/kotlin/rag/corpus/CorpusSmokeTest.kt
package rag.corpus

/**
 * Corre las preguntas de control contra el índice.
 *
 * Determinista: mismo índice + mismas preguntas = mismo resultado.
 */
object CorpusSmokeTest {

    /**
     * @param index índice de la cátedra.
     * @param questions preguntas de control.
     * @param topK cuántos fragmentos recuperar (el de la cátedra).
     */
    fun run(
        index: KnowledgeBase,
        questions: List<ControlQuestion>,
        topK: Int,
    ): SmokeTestReport
}

/** Resultado de correr el smoke test. */
data class SmokeTestReport(
    val total: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    val details: List<ControlQuestionResult> = emptyList(),
) {
    /** ¿Todo anda bien? */
    val ok: Boolean get() = failed == 0
}

/** El detalle de una pregunta. */
data class ControlQuestionResult(
    val questionId: String,
    val materialId: String,
    val question: String,
    val status: ControlStatus,
    /** Rango del fragmento esperado (1-based) si se recuperó. */
    val expectedRank: Int = 0,
    /** Texto que se recuperó en su lugar (para diagnóstico). */
    val gotInstead: String = "",
)
```

### 7.6 La regresión: el aviso que vale la pena

Después de publicar una versión nueva, la app corre el smoke test y avisa:

```
⚠ Publicaste «Apuntes-IA.pdf» (v3).
  2 de 3 preguntas de control ya no recuperan el fragmento esperado:
   · "¿Qué es un perceptrón?"   → recuperó «Página 3» en vez de «Página 7»
   · "¿Cómo se entrena una red?" → no recuperó nada
  [Ver las 3 preguntas]  [Ajustar la pregunta]
```

Este aviso es **el corazón del producto**: convierte "el docente cambió un
apunte" en "el docente sabe si el cambio rompió algo". Sin eso, el corpus es
un Dropbox con un índice.

---

## 8. Materiales enlazados

### 8.1 El modelo

Un material `MaterialKind.LINK` es una URL que puede cambiar. El docente lo
usa para: el repo de la cátedra, la bibliografía en línea, las diapositivas
publicadas, la página de la cátedra.

```kotlin
// campos nuevos en Material
    /** URL del material (si kind == LINK). */
    val url: String = "",
    /** Cada cuánto se reintenta la descarga. */
    val refreshPolicy: LinkRefreshPolicy = LinkRefreshPolicy.MANUAL,
    /** Última comprobación. */
    val lastCheckedAt: Long = 0L,
    /** Última vez que el contenido cambió. */
    val lastChangedAt: Long = 0L,
    /** Resultado de la última comprobación ("ok", "404", "timeout"). */
    val linkStatus: String = "",
    /** ETag del servidor (para la descarga condicional). */
    val etag: String = "",
    /** Last-Modified del servidor. */
    val lastModified: String = "",
```

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusLink.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/** Cada cuánto se refresca un material que es un enlace. */
@Serializable
enum class LinkRefreshPolicy(val label: String, val intervalMs: Long) {
    /** Nunca. El docente lo refresca a mano. */
    NUNCA("Nunca", 0L),

    /** Sólo cuando el docente lo pide. */
    MANUAL("A pedido", 0L),

    /** Cada 24 h. */
    DIARIO("Cada día", 24L * 60 * 60 * 1000),

    /** Cada 7 días. */
    SEMANAL("Cada semana", 7L * 24 * 60 * 60 * 1000),
}

/** Estado de la última comprobación de un enlace. */
@Serializable
enum class LinkStatus(val label: String) {
    OK("Responde"),
    NOT_MODIFIED("Sin cambios"),
    HTTP_ERROR("Error del servidor"),
    TIMEOUT("Se agotó el tiempo"),
    UNREACHABLE("No se pudo conectar"),
    TOO_LARGE("Demasiado grande"),
    UNSUPPORTED("Tipo de contenido no soportado"),
    NEVER_CHECKED("Sin comprobar"),
}
```

### 8.2 La política de refresco

| Política | Cuándo se comprueba | Uso típico |
|---|---|---|
| `NUNCA` | Sólo al publicar el material | Un PDF publicado en la web que no cambia |
| `MANUAL` | Cuando el docente aprieta "Refrescar" | La que conviene por defecto: el docente no quiere sorpresas |
| `DIARIO` | Una vez por día, en la primera apertura de la app del día | El repo de la cátedra, las diapositivas |
| `SEMANAL` | Una vez por semana | Bibliografía estable |

La comprobación **diaria** no descarga el contenido si el servidor responde
`304 Not Modified` con una `ETag` o un `Last-Modified`: es una petición
condicional, de unos 200 bytes.

### 8.3 El refresco condicional

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusLinkRefresher.kt
package kitoru.domain

/**
 * Refresca los materiales que son enlaces.
 *
 * Sólo se descargan los que **vencen** según su política, y sólo si el
 * servidor dice que cambiaron (ETag / Last-Modified).
 */
class CorpusLinkRefresher(
    private val materials: MaterialRepository,
    private val versions: MaterialVersionRepository,
    private val http: HttpClient,
    private val extractor: LinkTextExtractor,
) {

    /**
     * Refresca los enlaces que corresponde.
     *
     * @return los materiales que cambiaron (y por lo tanto generaron versión).
     */
    suspend fun refreshDue(
        courseId: String,
        now: Long,
        force: Boolean = false,
    ): List<LinkRefreshResult>
}

/** El resultado de refrescar un enlace. */
data class LinkRefreshResult(
    val materialId: String,
    val status: LinkStatus,
    /** ¿Cambió el contenido? */
    val changed: Boolean = false,
    /** Versión creada (si cambió). */
    val version: MaterialVersion? = null,
    val message: String = "",
)
```

El flujo de una comprobación:

```
 1. ¿Venció según refreshPolicy?  ──no──▶ seguir
 2. GET con If-None-Match / If-Modified-Since
 3. 304 Not Modified        → status = NOT_MODIFIED, no cambia nada
 4. 200 + ETag/Last-Modified iguales → status = NOT_MODIFIED
 5. 200 + contenido distinto → extraer texto → ¿el texto cambió?
        ├─ no → status = OK, no se crea versión
        └─ sí → se crea la versión (origin = REFRESH_ENLACE)
                 → el material pasa a EN_REVISION (no se publica solo)
 6. 4xx/5xx/timeout → status = error; **se conserva la última versión buena**
```

El punto 5 es importante: **el refresco no publica**. Un enlace que cambió
genera una versión y deja el material "en revisión", con el aviso:

```
🔗 «Repo de la cátedra» cambió (v1 → v2). Lo reviso y lo publico.
```

### 8.4 Qué pasa cuando el enlace muere

El **material no desaparece**: los alumnos siguen viendo la última versión
buena (que es lo que funciona). Se marca el estado y se avisa:

```
⚠ «Repo de la cátedra» no responde desde el 12/10 (error 404).
  Los alumnos siguen viendo la última versión que se pudo descargar.
  [Reintentar]  [Cambiar la URL]  [Retirar el material]
```

En el catálogo, el material aparece con un triángulo ámbar y el detalle
explica la fecha y el motivo.

### 8.5 El cliente HTTP

`commonMain` no tiene `java.net.http` (ni debe tenerlo). La costura es una
interfaz:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/HttpClient.kt
package kitoru.platform

/** Petición HTTP. */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** Tiempo máximo de espera. */
    val timeoutMs: Long = 15_000L,
    /** Tope de bytes a descargar. */
    val maxBytes: Long = 5L * 1024 * 1024,
)

/** Respuesta HTTP. */
data class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "",
    val etag: String = "",
    val lastModified: String = "",
    val finalUrl: String = "",
    /** ¿Se cortó por `maxBytes`? */
    val truncated: Boolean = false,
)

/**
 * Cliente HTTP mínimo.
 *
 * La implementación real vive en `jvmCommonMain` (escritorio **y** Android
 * tienen `java.net.HttpURLConnection`), así que el mismo código corre en las
 * dos plataformas. Más adelante puede reemplazarse por Ktor sin tocar nada.
 */
interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Cliente HTTP de la plataforma. */
expect fun platformHttpClient(): HttpClient
```

Límites (obligatorios, y son de seguridad):

| Límite | Valor | Por qué |
|---|---|---|
| Esquemas | Sólo `http` y `https` | Nada de `file://` ni `jar:` |
| Tamaño máximo | 5 MB | Un HTML gigante no es material |
| Tiempo máximo | 15 s | Un servidor colgado no bloquea la app |
| Redirecciones | Máximo 3, sólo a `http(s)` | Evitar redirigir a esquemas raros |
| Credenciales | Nunca se envían cookies ni `Authorization` | El material público no necesita credenciales |
| User-Agent | `Kitoru/1.0 (+corpus)` | Que el servidor sepa quién es |
| HTML | Se convierte a texto con el extractor que ya existe | El contenido remoto **no es de confianza**: es texto, nunca código |

### 8.6 El extractor del enlace

El texto de un enlace se extrae con la misma machinery de `teacher/03`:
HTML → texto (se quitan `script`/`style`, los `h1..h6` pasan a `#`..`######`),
PDF → texto con PDFBox, texto plano → tal cual. La diferencia es que la
fuente es la red: se marca el origen con una cabecera de provenance.

```
## Fuente: https://github.com/Nootechnia/curso-ia
<!-- material enlazado, descargado el 2026-10-12 -->

(contenido…)
```

Esa cabecera es lo que permite al docente (y al modelo) saber que el texto
viene de una URL y de cuál.

---

## 9. Derechos de autor: licencia y política de uso

### 9.1 Dos campos, no uno

Confundir "la licencia" con "lo que la app puede hacer" es un error de diseño
que después cuesta caro. Por eso hay **dos campos**:

| Campo | Qué es | Quién lo mira | Ejemplo |
|---|---|---|---|
| `MaterialLicense` | El **récord legal** que declara el docente | El alumno (para atribuir) y el docente | `CC_BY_SA`, `USO_ACADEMICO`, `PROPIETARIO` |
| `MaterialUsagePolicy` | Lo que **la app permite hacer** con el material | La app (para armar el índice) | `INDEXADO`, `SOLO_REFERENCIA`, `SIN_IA`, `SIN_IA_LOCAL` |

La licencia **no** decide el comportamiento por sí sola: el docente declara
la licencia y **además** marca la política. La app nunca adivina: si la
licencia es `CC_BY` pero la política es `SIN_IA`, gana la política (es la
decisión operativa del docente).

### 9.2 La licencia

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialLicense.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Licencia declarada por el docente para un material.
 *
 * Kitoru **no** es un gestor de licencias: no verifica ni aplica licencias.
 * Lo que hace es **registrar** lo que el docente declara y **mostrarlo** al
 * alumno, para que la atribución sea posible y para que la política de uso
 * (§9.3) pueda ser explícita.
 */
@Serializable
enum class MaterialLicense(
    val label: String,
    /** Identificador SPDX (vacío si no hay uno estándar). */
    val spdx: String = "",
    /** ¿Hay que atribuir al autor? */
    val requiresAttribution: Boolean = false,
    /** ¿Se puede redistribuir (el alumno puede descargarlo)? */
    val allowsRedistribution: Boolean = false,
) {
    /** El docente no declaró licencia: uso interno de la cátedra. */
    SIN_LICENCIA(
        "Sin licencia declarada (uso interno de la cátedra)",
    ),

    /** Material propio: uso exclusivo de la cátedra. */
    PROPIETARIO(
        "Material propio del docente (uso exclusivo de la cátedra)",
    ),

    /** Uso académico: no redistribuir. */
    USO_ACADEMICO(
        "Sólo para uso académico (no redistribuir)",
    ),

    CC_BY("Creative Commons Atribución 4.0", "CC-BY-4.0", true, true),
    CC_BY_SA("Creative Commons Atribución-CompartirIgual 4.0", "CC-BY-SA-4.0", true, true),
    CC_BY_NC("Creative Commons Atribución-NoComercial 4.0", "CC-BY-NC-4.0", true, false),
    CC_BY_ND("Creative Commons Atribución-NoDerivados 4.0", "CC-BY-ND-4.0", true, false),
    COPILEFT("Copyleft (las adaptaciones deben compartir la misma licencia)", "", true, true),
    OTRO("Otra licencia (describila en las notas del material)", "", false, false),
    ;

    /** Texto corto para el chip. */
    val shortLabel: String get() = spdx.ifBlank { label.substringBefore(" (") }
}
```

### 9.3 La política de uso y sus efectos

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialUsagePolicy.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Qué puede hacer la app con un material.
 *
 * Es el campo que **efecta al comportamiento** (§9.4). La licencia (§9.2) es
 * informativa; ésta manda.
 */
@Serializable
enum class MaterialUsagePolicy(
    val label: String,
    val description: String,
) {
    /** La IA lo usa y lo puede citar. */
    INDEXADO(
        "La IA lo usa y lo cita",
        "El material entra al índice y el modelo puede citarlo.",
    ),

    /** El alumno lo lee; la IA no lo cita. */
    SOLO_REFERENCIA(
        "Sólo de referencia",
        "El alumno puede leerlo, pero la IA no lo cita (sirve para material de apoyo o de contexto).",
    ),

    /** La IA no lo usa en absoluto. */
    SIN_IA(
        "La IA no lo usa",
        "El material queda fuera del índice: el alumno lo lee, pero ninguna respuesta puede citarlo.",
    ),

    /** La IA lo usa sólo cuando la inferencia ocurre en el servidor de la institución. */
    SIN_IA_LOCAL(
        "La IA lo usa sólo en el servidor de la cátedra",
        "Con IA local en el dispositivo del alumno, este material no entra al índice: el contenido no sale del dispositivo.",
    ),
    ;

    /** ¿Puede aparecer en el corpus del alumno (según el modo)? */
    fun allows(mode: ModelMode, localInferenceAllowed: Boolean): Boolean = when (this) {
        INDEXADO -> true
        SOLO_REFERENCIA, SIN_IA -> false
        SIN_IA_LOCAL -> mode == ModelMode.REMOTE && localInferenceAllowed
    }
}
```

### 9.4 El efecto técnico: la IA local

Este es el punto donde la licencia se vuelve un **límite técnico real**, y
donde se cruza con la privacidad (regla 6 del CONTEXTO: con IA local, el
servidor no ve los chats).

**Escenario**: el docente sube un libro de texto con licencia
`USO_ACADEMICO` y marca la política `SIN_IA_LOCAL`. El alumno usa IA local
(llama.cpp en su teléfono).

| Pieza | Consecuencia |
|---|---|
| `CorpusResolver` | El material **no entra** al índice local del alumno |
| El índice del alumno | Quedan los demás materiales; el RAG sigue funcionando |
| El chat | Un aviso **una vez**: *"El material «Libro de texto» tiene una licencia que no permite procesarlo en tu dispositivo: no se incluye en las respuestas."* |
| La privacidad | El contenido de ese material **no sale del dispositivo** del alumno: es lo que el docente pidió |
| El docente | En el panel de calidad del material: *"19 de 24 alumnos usan IA local: este material no les sirve. Considerá permitirlo."* |

El aviso para el docente es un dato **accionable** y es una de las razones por
las que el catálogo muestra las estadísticas de uso por material (§5).

La resolución en el resolver (todo en `:core`, puro y testeable):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusResolver.kt
package kitoru.domain

/**
 * Resuelve el corpus visible para un actor.
 *
 * Es una **función pura**: los mismos datos dan el mismo corpus. Toda la
 * lógica de "qué entra y qué no" vive acá, y por eso se testea sin disco,
 * sin red y sin UI.
 */
object CorpusResolver {

    /**
     * Resuelve el corpus para un actor.
     *
     * Devuelve **dos** listas: la que el modelo puede recuperar
     * ([CorpusSnapshot.indexable]) y la que el alumno puede leer y buscar
     * ([CorpusSnapshot.browsable]). La segunda incluye el material de sólo
     * referencia, que el alumno sí puede leer pero la IA no puede citar.
     *
     * @param plane qué plano se está armando.
     * @param inference dónde va a correr la inferencia del actor.
     */
    fun resolve(
        course: Course,
        materials: List<Material>,
        versions: List<MaterialVersion>,
        plane: CorpusPlane,
        inference: ModelMode,
        now: Long,
        versionTexts: Map<String, String> = emptyMap(),
    ): CorpusSnapshot {
        val indexable = mutableListOf<CorpusDocument>()
        val browsable = mutableListOf<CorpusDocument>()
        val excluded = mutableListOf<ExcludedDocument>()

        for (material in materials) {
            val reason = exclusionReason(course, material, plane, inference)
            if (reason != null) {
                excluded += ExcludedDocument(material.id, material.title, reason, reason.detail)
                continue
            }
            val versionId = when (plane) {
                CorpusPlane.ALUMNO -> material.publishedVersionId
                CorpusPlane.DOCENTE -> material.currentVersionId
            }
            val version = versions.firstOrNull { it.id == versionId } ?: continue
            val text = versionTexts[versionId] ?: material.extractedText
            if (text.isBlank()) continue
            val doc = CorpusDocument(
                materialId = material.id,
                versionId = version.id,
                versionNumber = version.number,
                title = material.title,
                path = material.path,
                text = text,
                role = material.usagePolicy,
                license = material.license,
                tags = material.tags,
                unitId = material.unitId,
                lifecycle = material.lifecycle,
            )
            // El material de sólo referencia se puede leer, pero no se cita.
            if (material.usagePolicy != MaterialUsagePolicy.SOLO_REFERENCIA) {
                indexable += doc
            }
            browsable += doc
        }
        return CorpusSnapshot(
            indexable = indexable,
            browsable = browsable,
            excluded = excluded,
        )
    }

    /**
     * Por qué un material no entra (null = entra).
     *
     * `SOLO_REFERENCIA` **no** es motivo de exclusión: es material que se
     * lee pero no se cita (§10). Por eso la comprobación vive en
     * `resolve`, no acá.
     */
    fun exclusionReason(
        course: Course,
        material: Material,
        plane: CorpusPlane,
        inference: ModelMode,
    ): ExclusionReason? = when {
        material.lifecycle == MaterialLifecycle.RETIRADO ->
            ExclusionReason.RETIRADO
        plane == CorpusPlane.ALUMNO && !material.visible ->
            ExclusionReason.OCULTO
        plane == CorpusPlane.ALUMNO && material.lifecycle != MaterialLifecycle.PUBLICADO ->
            ExclusionReason.NO_PUBLICADO
        material.usagePolicy == MaterialUsagePolicy.SIN_IA ->
            ExclusionReason.LICENCIA_SIN_IA
        inference == ModelMode.LOCAL && !course.corpusPolicy.allowLocalInference ->
            ExclusionReason.IA_LOCAL_PROHIBIDA
        material.usagePolicy == MaterialUsagePolicy.SIN_IA_LOCAL &&
            inference == ModelMode.LOCAL ->
            ExclusionReason.LICENCIA_SIN_IA_LOCAL
        else -> null
    }
}
```

> **Fail-closed.** El orden de las reglas importa: los motivos de exclusión
> se evalúan de más restrictivo a menos restrictivo, y el `when` **no** tiene
> `else` permisivo para los casos dudosos. Si el modo de inferencia no se
> puede determinar (por ejemplo, un `ModelPolicy` incompleto), el resolver
> **no incluye** los materiales con `SIN_IA_LOCAL`: se asume `LOCAL`, que es
> el modo más restrictivo para el material.

### 9.5 La atribución: qué ve el alumno

Si la licencia exige atribución, el alumno la ve en la ficha del material y
al pie de la cita:

```
📄 Apuntes-IA.pdf
   Creative Commons Atribución-CompartirIgual 4.0 · autor: la cátedra
   ⓘ  Este material tiene licencia CC BY-SA: si lo compartís, mantené la atribución.
```

En la cita del chat:

```
[1] cátedra · Apuntes-IA · Página 7   (CC BY-SA)
```

### 9.6 El aviso al docente

Al guardar un material con licencia que exige atribución, la app dice:

```
ℹ «Apuntes-IA.pdf» tiene licencia CC BY-SA: se mostrará la atribución en el chat
   y en la ficha del material.
```

Y si el docente marca `SIN_IA_LOCAL`:

```
⚠ «Apuntes-IA.pdf» no se usará en el dispositivo de los alumnos que usen IA local.
   19 de 24 alumnos usan IA local (según la analítica de la cátedra).
   [Dejarlo así]  [Permitirlo en local]
```

---

## 10. El material de sólo referencia

### 10.1 La bandera y por qué no es `visible`

`Material.visible` ya existe y significa **"el alumno no sabe que este
material existe"**. El material de referencia es otra cosa: **el alumno lo
lee, pero la IA no lo cita**.

| | `visible = false` | `usagePolicy = SOLO_REFERENCIA` |
|---|---|---|
| ¿El alumno lo ve en la biblioteca? | **no** | **sí** |
| ¿Puede descargarlo? | no | sí |
| ¿Entra al índice del RAG? | no | **no** |
| ¿Lo puede citar el modelo? | no | **no** |
| ¿Se puede buscar dentro (biblioteca)? | no | **sí** |
| ¿Cuenta para la analítica? | no | sí (aperturas y búsquedas) |

Por eso son dos campos distintos y no un solo booleano.

### 10.2 El efecto en `CourseMaterialSource` (el cambio concreto)

Hoy:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt (actual)
override suspend fun load(): List<Doc> = materials
    .filter { it.visible && it.extractedText.isNotBlank() }
    .map { ... }
```

El problema: `visible` es un filtro de **visibilidad**, y no puede expresar
"visible pero no indexable" ni "indexable sólo en remoto". El filtro correcto
lo aplica el resolver (§9.4) y la fuente sólo recibe lo que ya pasó el filtro.

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt (nuevo)
package rag.retrieval

import kitoru.domain.CorpusDocument
import kitoru.domain.Material

/**
 * Fuente que toma los materiales de una cátedra.
 *
 * **Cambio de contrato**: recibe [CorpusDocument] (el corpus ya resuelto) en
 * lugar de `List<Material>`. El filtro de qué entra (publicado, visible,
 * indexable, permitido por la licencia) lo hace
 * [kitoru.domain.CorpusResolver], que es una función pura testeable; esta
 * clase sólo traduce a [Doc].
 */
class CourseMaterialSource(
    private val documents: List<CorpusDocument>,
    private val corpusLabel: String = "cátedra",
) : DocumentSource {

    override val label: String get() = corpusLabel

    override suspend fun load(): List<Doc> = documents.map { doc ->
        Doc(
            source = corpusLabel,
            path = doc.title.ifBlank { doc.path },
            text = doc.text,
            materialId = doc.materialId,
            versionId = doc.versionId,
        )
    }

    companion object {
        /**
         * Constructor de compatibilidad para el hito 1: arma los documentos
         * desde los materiales, usando el texto de trabajo.
         *
         * Es un **companion object** y no un constructor secundario a propósito:
         * `List<Material>` y `List<CorpusDocument>` tienen la misma firma
         * borrada en la JVM (`List`), y dos constructores con el mismo tipo
         * erasure no compilan ("platform declaration clash").
         */
        fun fromMaterials(
            materials: List<Material>,
            corpusLabel: String = "cátedra",
        ): CourseMaterialSource = CourseMaterialSource(
            materials.map { m ->
                CorpusDocument(
                    materialId = m.id,
                    versionId = m.currentVersionId,
                    versionNumber = 0,
                    title = m.title,
                    path = m.path,
                    text = m.extractedText,
                    role = m.usagePolicy,
                    license = m.license,
                    tags = m.tags,
                    unitId = m.unitId,
                    lifecycle = m.lifecycle,
                )
            },
            corpusLabel,
        )
    }
}
```

**El filtro que se agrega hoy (`visible && extractedText.isNotBlank()`) queda
reemplazado** por el resolver, que además filtra por rol, licencia y estado
editorial. El comportamiento observable para el alumno es el mismo en el caso
común (material visible y con texto) y **más correcto** en los casos nuevos.

### 10.3 Los dos índices: el del RAG y el de la biblioteca

Con material de sólo referencia, el "un solo índice, una sola verdad" de
`student/04-materiales.md` §1.1 ya no alcanza: la biblioteca del alumno tiene
que **buscar** dentro del material de referencia, pero el RAG **no lo puede
recuperar**.

La solución son **dos vistas del mismo corpus**, construidas con el mismo
`CorpusResolver` y distinto plano:

| Índice | Qué contiene | Para qué |
|---|---|---|
| `ragIndex` | Plano ALUMNO, sólo materiales **indexables** | Lo que recupera el modelo |
| `browseIndex` | Plano ALUMNO, **todo** lo publicado (incluido lo de referencia) | Lo que el alumno busca y lee |

Cuando la cátedra no tiene material de referencia, `browseIndex` y `ragIndex`
contienen lo mismo y el costo es nulo. Cuando lo tiene, divergen **a
propósito**: la biblioteca muestra más que lo que la IA sabe.

```kotlin
// backend/src/commonMain/kotlin/rag/CourseIndex.kt
package rag

import kitoru.domain.*
import rag.retrieval.*

/**
 * Índice vivo de una cátedra.
 *
 * Mantiene **dos** índices BM25:
 *  - [ragIndex]: lo que el modelo puede recuperar (materiales indexables).
 *  - [browseIndex]: lo que el alumno puede buscar y leer (todo lo publicado).
 *
 * Cuando la cátedra no tiene material de sólo referencia, ambos índices
 * tienen el mismo contenido.
 */
class CourseIndex(private val cfg: RagConfig) {

    val kb = KnowledgeBase(cfg)                 // el del RAG
    private val browse = Bm25Index(emptyList())

    /** Reconstruye el índice del RAG. */
    suspend fun reindex(snapshot: CorpusSnapshot, onStatus: (String) -> Unit = {}) {
        kb.sources = listOf(CourseMaterialSource(snapshot.indexable))
        kb.sync(onStatus)
    }

    /** Reconstruye el índice de biblioteca (búsqueda del alumno). */
    suspend fun reindexBrowse(snapshot: CorpusSnapshot, onStatus: (String) -> Unit = {}) {
        val chunks = snapshot.browsable.flatMap { doc ->
            Chunker.split(
                Doc("cátedra", doc.title, doc.text, doc.materialId, doc.versionId),
                cfg.chunkSize, cfg.chunkOverlap,
            )
        }
        browse = Bm25Index(chunks)
        onStatus("Biblioteca: ${snapshot.browsable.size} documentos / ${chunks.size} fragmentos")
    }

    /** Búsqueda en la biblioteca (incluye el material de sólo referencia). */
    fun searchBrowse(query: String, k: Int = cfg.topK): List<Pair<Chunk, Double>> =
        browse.search(query, k)
}
```

`CorpusSnapshot` expone las dos listas (`indexable` y `browsable`, definidas en
§1.4): la primera es la que entra al RAG, la segunda es la que el alumno puede
leer y buscar.

### 10.4 Lo que ve el alumno

En la biblioteca del alumno, el material de referencia aparece con un chip:

```
📄 Guía de estudio — Capítulo 4          [sólo de referencia]
   El alumno lo lee y lo busca; la IA no lo cita.
```

Y si el alumno le pregunta a la IA sobre un tema que sólo está en el material
de referencia, la app lo dice:

```
ℹ Ese contenido está en «Guía de estudio — Capítulo 4», que es material de
   sólo referencia: no lo puedo usar como fuente. Te lo dejo para que lo leas.
```

Ese último párrafo es una de las **mejores decisiones de producto** del
diseño: en vez de responder "no sé", la IA **orienta al alumno** hacia el
material. El catálogo de referencia deja de ser un archivo muerto y pasa a
ser el "índice de la biblioteca" del curso.

---

## 11. Nombres de entidades y funciones

### 11.1 `:core` — dominio (nuevo)

| Archivo | Contenido | Tipos / funciones |
|---|---|---|
| `kitoru/domain/CorpusLifecycle.kt` | Ciclo de vida editorial | `MaterialLifecycle`, `MaterialLifecycleMachine` |
| `kitoru/domain/CorpusVersion.kt` | Versiones | `MaterialVersion`, `VersionOrigin` |
| `kitoru/domain/CorpusPolicy.kt` | Política de la cátedra | `CorpusPublishMode`, `CourseCorpusPolicy` |
| `kitoru/domain/CorpusUsagePolicy.kt` | Rol del material en el corpus | `MaterialUsagePolicy` |
| `kitoru/domain/MaterialLicense.kt` | Licencia | `MaterialLicense` |
| `kitoru/domain/CorpusLink.kt` | Enlaces | `LinkRefreshPolicy`, `LinkStatus` |
| `kitoru/domain/CorpusManifest.kt` | Contrato de distribución | `CorpusManifest`, `ManifestEntry` |
| `kitoru/domain/CorpusPublication.kt` | Lotes de publicación | `PublicationBatch`, `BatchState` |
| `kitoru/domain/CorpusResolver.kt` | Resolución del corpus (puro) | `CorpusResolver` |
| `kitoru/domain/CorpusSnapshot.kt` | Tipos del corpus resuelto | `CorpusPlane`, `CorpusDocument`, `CorpusSnapshot`, `ExclusionReason`, `ExcludedDocument` |
| `kitoru/domain/CorpusQuality.kt` | Tipos de calidad | `CheckSeverity`, `CorpusCheck`, `CorpusQualityReport` |
| `kitoru/domain/CorpusCatalog.kt` | Catálogo | `CorpusEntry`, `MaterialUsage`, `CorpusCatalog`, `CatalogFilter`, `CatalogSummary`, `CorpusCatalogBuilder` |
| `kitoru/domain/CorpusEvent.kt` | Eventos de uso | `CorpusEvent`, `CorpusEventKind`, `CorpusEventRepository` |
| `kitoru/domain/CorpusExposure.kt` | Exposición a versiones | `VersionExposure`, `VersionExposureReport`, `CorpusExposureAnalyzer` |
| `kitoru/domain/CorpusDiff.kt` | Diff por bloques | `ChangedBlock`, `CorpusDiff` |
| `kitoru/domain/CorpusRetention.kt` | Retención | `VersionRetentionPolicy` |
| `kitoru/domain/ControlQuestion.kt` | Preguntas de control | `ControlQuestion`, `ControlStatus` |
| `kitoru/domain/ContentHasher.kt` | Hash | `ContentHasher` |
| `kitoru/domain/LanguageProfile.kt` | Idioma | `LanguageProfile`, `LanguageProfile.Signal` |
| `kitoru/domain/SuggestedQuestion.kt` | Preguntas sugeridas (tipo) | `SuggestedQuestion` |
| `kitoru/domain/CorpusPublicationService.kt` | Publicación (servicio) | `CorpusPublicationService`, `PublishOutcome`, `PublicationBatchRepository`, `FilePublicationBatchRepository` |
| `kitoru/domain/CorpusPublisher.kt` | Transporte del corpus publicado | `CorpusPublisher`, `FolderCorpusPublisher` |
| `kitoru/domain/CorpusSyncService.kt` | Sincronización (alumno) | `CorpusSyncService`, `CorpusSyncResult`, `LocalCorpusStore` |
| `kitoru/domain/CorpusVersionService.kt` | Versionado (servicio) | `CorpusVersionService` |
| `kitoru/domain/CorpusLinkRefresher.kt` | Refresco de enlaces (servicio) | `CorpusLinkRefresher`, `LinkRefreshResult` |
| `kitoru/domain/CorpusVersionRepository.kt` | Persistencia de versiones | `MaterialVersionRepository`, `FileMaterialVersionRepository` |
| `kitoru/domain/CorpusManifestRepository.kt` | Persistencia de manifiestos | `CorpusManifestRepository`, `FileCorpusManifestRepository` |
| `kitoru/platform/HttpClient.kt` | HTTP | `HttpRequest`, `HttpResponse`, `HttpClient`, `platformHttpClient()` |

### 11.2 `:backend` — RAG

| Archivo | Contenido | Tipos / funciones |
|---|---|---|
| `rag/corpus/CorpusQualityChecker.kt` | Checks | `CorpusQualityChecker.check(...)` |
| `rag/corpus/CorpusQuestionSuggester.kt` | Preguntas sugeridas | `CorpusQuestionSuggester.suggest(...)` |
| `rag/corpus/CorpusSmokeTest.kt` | Smoke test | `CorpusSmokeTest.run(...)`, `SmokeTestReport`, `ControlQuestionResult` |
| `rag/corpus/DocumentFingerprint.kt` | Huellas para casi-duplicados | `DocumentFingerprint.minHash(...)`, `shingleSimilarity(...)` |
| `rag/CourseIndex.kt` | Índice de la cátedra | `CourseIndex.reindex(...)`, `reindexBrowse(...)`, `searchBrowse(...)` |
| `rag/retrieval/DocumentSource.kt` | Fuente (modificada) | `CourseMaterialSource(documents)`, `CourseMaterialSource.fromMaterials(...)` |
| `rag/retrieval/Models.kt` | `Doc` / `Chunk` (modificados) | `materialId`, `versionId` |
| `rag/retrieval/Bm25Index.kt` | (modificado) | `documentFrequencies()` |

### 11.3 `:ui` — catálogo del docente (todavía no construido)

| Archivo | Contenido | Composables |
|---|---|---|
| `kitoru/ui/teacher/corpus/CorpusScreen.kt` | Pantalla del catálogo | `CorpusScreen` |
| `kitoru/ui/teacher/corpus/CorpusListPane.kt` | Lista + filtros | `CorpusListPane`, `CorpusFilters` |
| `kitoru/ui/teacher/corpus/CorpusRow.kt` | Fila | `CorpusRow`, `CorpusHealthBar`, `CorpusVersionBadge` |
| `kitoru/ui/teacher/corpus/CorpusSummaryBar.kt` | Cabecera | `CorpusSummaryBar` |
| `kitoru/ui/teacher/corpus/CorpusQualityChips.kt` | Checks | `CorpusQualityChips`, `CorpusCheckCard` |
| `kitoru/ui/teacher/corpus/PublishDialog.kt` | Publicar | `PublishDialog`, `PublishDiffView` |
| `kitoru/ui/teacher/corpus/VersionHistory.kt` | Historial | `VersionHistory`, `VersionTimeline` |
| `kitoru/ui/teacher/corpus/UsagePanel.kt` | Estadísticas de uso | `UsagePanel`, `UsageSparkline` |
| `kitoru/ui/teacher/corpus/CorpusViewModel.kt` | Estado | `CorpusViewModel`, `CorpusUiState` |

### 11.4 `:ui` — biblioteca del alumno

| Archivo | Contenido | Composables |
|---|---|---|
| `kitoru/ui/student/library/LibraryScreen.kt` | Biblioteca con el chip "nuevo" | `LibraryScreen`, `LibraryRow` |
| `kitoru/ui/student/library/CorpusSyncBanner.kt` | Aviso de actualización | `CorpusSyncBanner` |
| `kitoru/ui/student/library/ReferenceOnlyBadge.kt` | Chip de sólo referencia | `ReferenceOnlyBadge` |

---

## 12. Cambios concretos en el código existente

### 12.1 `Course` (`:core`)

```kotlin
// campo nuevo
    /** Política del corpus (publicación, licencia por defecto, eventos). */
    val corpusPolicy: CourseCorpusPolicy = CourseCorpusPolicy(),
```

### 12.2 `Material` (`:core`)

```kotlin
@Serializable
data class Material(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val path: String = "",
    val sizeBytes: Long = 0L,
    val uploadedBy: String = "",
    val uploadedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val visible: Boolean = true,
    val extractedText: String = "",
    val warning: String = "",

    // ---- Estado editorial (nuevo) ----
    /** En qué fase está el material respecto de los alumnos. */
    val lifecycle: MaterialLifecycle = MaterialLifecycle.BORRADOR,
    /** Estado técnico del pipeline (propuesto en teacher/03 §4.1). */
    val status: MaterialStatus = MaterialStatus.PENDING,

    // ---- Versiones (nuevo) ----
    /** Última versión creada: la "copia de trabajo" del docente. */
    val currentVersionId: String = "",
    /** Versión que ven los alumnos. */
    val publishedVersionId: String = "",
    /** Hash del contenido de la versión vigente (detección de duplicados). */
    val contentHash: String = "",

    // ---- Rol en el corpus (nuevo) ----
    /** Qué puede hacer la app con este material. */
    val usagePolicy: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    /** Licencia declarada. */
    val license: MaterialLicense = MaterialLicense.SIN_LICENCIA,

    // ---- Enlaces (nuevo) ----
    val url: String = "",
    val refreshPolicy: LinkRefreshPolicy = LinkRefreshPolicy.MANUAL,
    val lastCheckedAt: Long = 0L,
    val lastChangedAt: Long = 0L,
    val linkStatus: LinkStatus = LinkStatus.NEVER_CHECKED,
    val etag: String = "",
    val lastModified: String = "",

    // ---- Organización y salud (nuevo) ----
    val unitId: String = "",
    val quality: CorpusQualityReport = CorpusQualityReport(),
    val qualityCheckedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastEditedBy: String = "",
)
```

> **Compatibilidad.** Todos los campos nuevos tienen default → los
> `materials.json` viejos siguen cargando. Los materiales existentes quedan
> en `BORRADOR`: el docente tiene que publicarlos explícitamente (o se
> publica automáticamente la v1 al detectarlos, que es lo que hace la
> migración de §12.6).

### 12.3 `Doc` y `Chunk` (`:backend`)

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Id del material de origen ("" si la fuente no es un material). */
    val materialId: String = "",
    /** Id de la versión indexada ("" si no aplica). */
    val versionId: String = "",
)

data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    /** Id del material de origen. */
    val materialId: String = "",
    /** Id de la versión de la que sale el fragmento. */
    val versionId: String = "",
) {
    val label get() = "$source: $path"
}
```

El `Chunker` propaga los dos campos:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt (línea 22)
out += Chunk(doc.source, doc.path, heading, piece, doc.materialId, doc.versionId)
```

### 12.4 `ChatMessage` (`:core`)

```kotlin
// campo nuevo (completa el MaterialSourceRef de student/04)
    /** Fuentes con las que se armó la respuesta, con material y versión. */
    val sourceRefs: List<MaterialSourceRef> = emptyList(),
```

```kotlin
@Serializable
data class MaterialSourceRef(
    val materialId: String,
    /** Versión del material que se usó (clave de la analítica de exposición). */
    val versionId: String = "",
    val title: String = "",
    val heading: String = "",
    val start: Int = -1,
    val end: Int = -1,
)
```

### 12.5 `KnowledgeBase` (`:backend`)

```kotlin
// :backend — agregar
/** Fragmento recuperado con su puntaje BM25. */
data class ScoredChunk(val chunk: Chunk, val score: Double)

/** Recupera los fragmentos más relevantes, con su puntaje. */
fun retrieveScored(query: String, k: Int = cfg.topK): List<ScoredChunk> =
    index.search(query, k).map { ScoredChunk(it.first, it.second) }

/** Cuántos fragmentos aporta cada material. */
fun chunkCountByDoc(): Map<String, Int> = index.chunksByMaterialId()
```

Y `chunkCountForSource` (que hoy devuelve el total sin importar la fuente) se
elimina o se corrige: es un bug silencioso.

### 12.6 Migración de los materiales existentes

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CourseMigration.kt (agregar)

/**
 * Migra los materiales antiguos al modelo con versiones.
 *
 * Un material viejo (sin `lifecycle`) se publica como v1: es lo que el
 * docente ya estaba usando, y publicarlo de golpe rompería la cursada.
 */
fun Material.migrateToVersions(now: Long): Material {
    if (currentVersionId.isNotBlank()) return this
    val version = MaterialVersion(
        id = newId("ver"),
        materialId = id,
        courseId = courseId,
        number = 1,
        origin = VersionOrigin.ALTA,
        textHash = ContentHasher.of(extractedText),
        charCount = extractedText.length,
        createdAt = uploadedAt,
        createdBy = uploadedBy,
        textPath = "versions/$id/v1.txt",
        filePath = path,
    )
    return copy(
        lifecycle = if (visible) MaterialLifecycle.PUBLICADO else MaterialLifecycle.BORRADOR,
        currentVersionId = version.id,
        publishedVersionId = if (visible) version.id else "",
        contentHash = version.textHash,
    )
}
```

### 12.7 Reconciliación con los documentos hermanos

| Campo | `teacher/03` | `student/04` | `teacher/09` | **Decisión aquí** |
|---|---|---|---|---|
| Id del material en `Doc`/`Chunk` | `docId` | `ref` | `sourceId` | **`materialId`** (los tres alias quedan obsoletos) |
| Versión | — | — | — | **`versionId`** (nuevo) |
| Identidad en el mensaje | — | `sourceRefs` | `sourceIds` | **`sourceRefs` con `versionId`** (el `label` se conserva para `SourceUsage`) |
| Estado editorial | — | — | — | **`MaterialLifecycle`** (distinto de `MaterialStatus`) |
| Filtro del RAG | `visible` | `visible` | — | **el resolver** (plano + rol + licencia) |

---

## 13. Tests

| Test | Qué cubre |
|---|---|
| `core/src/commonTest/…/MaterialLifecycleMachineTest` | Transiciones válidas e inválidas; publicar sin `READY` falla; publicar con check bloqueante falla; el mensaje dice qué pasa |
| `core/src/commonTest/…/CorpusResolverTest` | Plano docente vs. alumno; material en revisión → sigue entrando con la versión publicada; sólo referencia → fuera del RAG pero en la biblioteca; `SIN_IA` → fuera siempre; `SIN_IA_LOCAL` + `LOCAL` → fuera; fail-closed con modo indeterminado |
| `core/src/commonTest/…/ContentHasherTest` | Normalización (acentos, mayúsculas, marcas de página); el mismo texto da el mismo hash; dos textos distintos casi nunca colisionan; vector de control conocido |
| `core/src/commonTest/…/CorpusDiffTest` | Bloques modificados / nuevos / borrados; texto sin encabezados; determinismo |
| `core/src/commonTest/…/CorpusExposureAnalyzerTest` | Sólo cuentan los alumnos con métricas visibles; `onOld` vs `onCurrent`; `staleCitations` después de publicar |
| `core/src/commonTest/…/LanguageProfileTest` | Detecta es/en/pt; un texto mixto da la proporción esperada; el umbral del 15 % |
| `core/src/commonTest/…/CorpusCatalogBuilderTest` | El resumen cuenta bien; `needsAttention` con cambios pendientes; el orden "más problemáticos" |
| `core/src/commonTest/…/VersionRetentionPolicyTest` | No se borra una versión publicada, la vigente ni una citada |
| `backend/src/desktopTest/…/CorpusQualityCheckerTest` | Cada check: vacío, sin fragmentos, diminutos, gigantes, sin encabezados, OCR, encoding, idioma, páginas sin texto, duplicado |
| `backend/src/desktopTest/…/CorpusQuestionSuggesterTest` | La sugerencia sale del fragmento más informativo; la pregunta es respondible con el material; es determinista; no propone nada si no hay fragmentos |
| `backend/src/desktopTest/…/CorpusSmokeTestTest` | Detecta la regresión: cambiar el texto hace fallar la pregunta de control |
| `backend/src/desktopTest/…/CourseIndexTest` | `ragIndex` y `browseIndex` divergen sólo si hay material de referencia; `chunkCountByDoc` |
| `core/src/desktopTest/…/CorpusLinkRefresherTest` | 304 → no cambia; 200 con ETag igual → no cambia; 200 distinto → versión nueva; 404 → conserva la última versión buena |

---

## 14. Checklist de implementación (orden sugerido)

1. `MaterialLifecycle` + `MaterialLifecycleMachine` + `Material` con los campos
   nuevos + la migración de §12.6.
2. `MaterialVersion` + `MaterialVersionRepository` (`FileMaterialVersionRepository`)
   + `ContentHasher` + `CorpusVersionService`.
3. `CorpusResolver` + `CorpusSnapshot` + `CorpusDocument` (todo en `:core`, puro).
4. `CourseMaterialSource` con el nuevo contrato + `Doc`/`Chunk` con
   `materialId`/`versionId` + `Chunker` que los propaga.
5. `CourseIndex` con los dos índices (`ragIndex` + `browseIndex`).
6. `CorpusQualityChecker` (en `:backend`) + `Material.quality` + los checks en
   el catálogo.
7. `CorpusQuestionSuggester` + el mensaje de "quedó indexado en N fragmentos".
8. `ControlQuestion` + `CorpusSmokeTest` + el aviso de regresión.
9. `CorpusManifest` + `CorpusPublicationService` + `PublicationBatch` +
   `CorpusPublisher` (`FolderCorpusPublisher`).
10. `CorpusSyncService` (lado alumno) + el banner de actualización.
11. `MaterialUsagePolicy` + `MaterialLicense` + los efectos en el resolver +
   el aviso de la IA local.
12. `CorpusEvent` + `CorpusEventRepository` + `CorpusExposureAnalyzer` + las
    estadísticas de uso del catálogo.
13. `LinkRefreshPolicy` + `HttpClient` (`expect`/`actual`) +
    `CorpusLinkRefresher`.
14. La pantalla del catálogo (`CorpusScreen`) + el diálogo de publicación con
    el diff.
15. `CorpusDiff` + la vista de historial de versiones.

---

## 15. Riesgos y límites

| Riesgo | Mitigación |
|---|---|
| **El modelo de versiones infla el disco** | Retención: 10 versiones por material + las citadas; el texto de cada versión es un archivo aparte, no está en el JSON |
| **El catálogo crece y deja de ser legible** | Filtros, orden por "más problemáticos", el resumen de cabecera y el chip de salud; el material sin uso se propone retirar |
| **La sugerencia de pregunta propone algo que la IA no responde** | La sugerencia sale de un fragmento **real** del índice, y la prueba muestra el puntaje; si el fragmento no es el primero, el docente lo ve |
| **El smoke test da falsos positivos** | Sólo se compara con el fragmento esperado (hash del texto); si el texto cambió, la falla es real, y el docente puede re-apuntar el fragmento esperado |
| **El resuelvo de duplicados da falsos positivos** | El hash es del texto **normalizado** (sin acentos, sin marcas de página) y la comparación es exacta; los "casi duplicados" son sólo advertencias |
| **El modo de publicación en lote confunde al docente** | La UI muestra siempre la fecha del próximo lote y un botón "Publicar ahora"; el efecto de un cambio es reversible |
| **La distribución del hito 1 no existe todavía** | El manifiesto es el contrato; la implementación de carpeta es un detalle de transporte; con Kitoru-Server cambia sólo el `CorpusPublisher` |
| **La licencia como simple etiqueta** | Es exactamente lo que se propone: Kitoru no verifica licencias. Lo que **sí** hace la app es aplicar la `usagePolicy`, que es la decisión operativa del docente |
| **El modo local rompe el corpus del alumno** | Es intencional (fail-closed) y es visible: el alumno recibe un aviso y el docente ve cuántos alumnos usan IA local |

**Fuera de alcance (anotado, no implementado):**

- **Indexación incremental**: hoy se reindexa todo (BM25 en memoria: instantáneo).
  Cuando el corpus sea grande, el índice se identifica por
  `materialId + versionId` y se fusiona.
- **Diff a nivel de palabra**: hoy el diff es por bloques (por encabezado). Un
  diff palabra a palabra es posible pero no aporta mucho en el contexto del
  material docente.
- **Detección de plagio o de material de otro curso**: fuera del alcance.
- **Firma criptográfica de las versiones**: el hash de contenido es de
  detección, no de integridad. Si el corpus se comparte entre docentes, hace
  falta SHA-256 con la misma interfaz `ContentHasher`.
- **OCR**: la costura ya está definida (`OcrEngine` en
  `teacher/03-materiales.md` §4.6). Con OCR, el check `VACIO` se convierte en
  "el texto viene de un OCR" con su propio aviso de ruido.
- **Distribución real a los alumnos**: depende de Kitoru-Server o de una
  carpeta compartida. El diseño no depende de cuál.

---

## Anexo A · Catálogo de mensajes (copy exacto)

| Situación | Mensaje |
|---|---|
| Material nuevo indexado | "«{título}» quedó indexado en {n} fragmentos; probá con esta pregunta: {pregunta}" |
| Material sin texto | "No se pudo extraer texto: el archivo parece un escaneo ({0} de {n} páginas tienen texto). Subilo con texto seleccionable o pegá el texto en «Editar texto»." |
| Material duplicado | "Este material es idéntico a «{otro}» (mismo contenido). No lo agregues dos veces: elegí uno y ocultá el otro." |
| Sin encabezados | "El texto no tiene encabezados: cada fragmento va a quedar sin título y las citas van a decir sólo «cátedra: {título}»." |
| Fragmentos diminutos | "El {p}% de los fragmentos ({k} de {n}) son más chicos que 80 caracteres: el troceado parte el material en pedazos que el modelo no puede usar. Probá subir el tamaño de fragmento (ahora {chunkSize})." |
| Ruido de OCR | "El texto parece venir de un OCR: hay {p}% de caracteres raros. La IA va a entender menos de lo que parece." |
| Idioma mixto | "El {p}% del texto está en {idioma} y el material de la cátedra está en {esperado}: las citas van a mezclar idiomas." |
| Publicado | "«{título}» publicado (v{n}). {k} alumnos tienen la v{n−1}." |
| Con cambios sin publicar | "Los alumnos están viendo la v{n−1} y la v{n} todavía no se publicó." |
| Sin cambios al guardar | "El texto no cambió: no se creó una versión nueva." |
| Regresión de las preguntas de control | "{k} de {m} preguntas de control ya no recuperan el fragmento esperado." |
| Material sin uso | "Nadie consultó este material en {d} días. Si ya no sirve, retiralo: ocupa índice y ruido." |
| Enlace muerto | "«{título}» no responde desde el {fecha} ({error}). Los alumnos siguen viendo la última versión que se pudo descargar." |
| Enlace cambiado | "«{título}» cambió (v{n} → v{n+1}). Lo reviso y lo publico." |
| Material excluido por licencia (alumno en local) | "El material «{título}» tiene una licencia que no permite procesarlo en tu dispositivo: no se incluye en las respuestas." |
| Material de sólo referencia (alumno) | "Ese contenido está en «{título}», que es material de sólo referencia: no lo puedo usar como fuente. Te lo dejo para que lo leas." |
| Actualización en la biblioteca | "Se actualizaron {n} materiales de esta cátedra." |
| Aviso de la IA local (docente) | "{n} de {m} alumnos usan IA local: este material no les sirve. Considerá permitirlo." |
| Bloqueo de publicación | "«{título}» tiene un problema que impide publicarlo: {motivo}" |
