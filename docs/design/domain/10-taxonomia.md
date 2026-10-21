# Taxonomía de la cátedra: unidades, temas, semanas y etiquetas

> Documento de diseño · dominio de Kitoru · 10
> Escribe el diseño de **cómo se organiza el contenido de una cátedra**
> (unidades, temas, semanas) y cómo eso se refleja en el RAG (BM25) y en la
> analítica.
>
> Archivos de referencia leídos antes de escribir:
> `docs/design/CONTEXTO.md`, `core/.../kitoru/domain/Course.kt`,
> `core/.../kitoru/domain/Chat.kt`, `core/.../kitoru/domain/Analytics.kt`,
> `core/.../kitoru/domain/AnalyticsAnalyzer.kt`, `core/.../kitoru/domain/Repositories.kt`,
> `core/.../kitoru/domain/FileRepositories.kt`, `core/.../kitoru/domain/JsonStore.kt`,
> `backend/.../rag/retrieval/Models.kt`, `Chunker.kt`, `Bm25Index.kt`,
> `KnowledgeBase.kt`, `DocumentSource.kt`, `rag/engine/RagEngine.kt`,
> `rag/engine/CitationVerifier.kt`, `rag/config/RagConfig.kt`.

---

## 0. Decisiones (resumen executive)

| # | Decisión | Motivo |
|---|---|---|
| D1 | **Sí hay jerarquía**, pero es de **dos niveles** (Unidad > Tema) y **opcional**: un material sin unidades asignadas se indexa igual que hoy. | El docente no debería tener que cargar un árbol para usar Kitoru; pero cuando lo carga, la cita, el prompt y la analítica ganan mucho. |
| D2 | La jerarquía de la cátedra (entidad `Unit`) y la jerarquía **dentro** del documento (encabezados Markdown) son **dos cosas distintas** y no se mezclan: la primera manda en la cita, la segunda completa el detalle. | Un PDF no tiene por qué repetir la estructura de la materia; un material puede cubrir dos temas. |
| D3 | `Chunk` gana campos de metadato **con valor por defecto**, y el `Chunk` conserva su constructor posicional `(source, path, heading, text)`. | No se rompe ni `Bm25Index` ni los tests portados de FunshiEngineAI. |
| D4 | La cita se construye con un campo nuevo `Chunk.citationLabel`: `"Unidad 2 > Tema 2.1 > Apuntes de regresión"`. `Chunk.label` no cambia (sigue siendo `"cátedra: Apuntes de regresión"`). | `label` se usa como clave de analítica y en el verificador de citas; cambiarlo rompería datos guardados. |
| D5 | El cronograma es **opcional** (`ScheduleEntry`), pero el concepto "semana" vive siempre en `Unit.week`. | Muchas cátedra no tienen fecha por clase, pero siempre tienen "semana 1..N". |
| D6 | Las **etiquetas libres sí entran al RAG**: se indexan (una vez por fragmento) y se usan para expandir la consulta con peso 0.3. | Si el docente etiqueta un material como "graduación", el alumno que pregunta "qué es la graduación" tiene que encontrarlo. |
| D7 | **Boosting multiplicativo**, en dos factores: `Chunk.boost` (estático, del material/unidad) × `RetrievalBoost.queryFactor` (dinámico, según la consulta). Se aplica **después** del BM25. | Multiplicar preserva la semántica de BM25 y el orden relativo de los fragmentos con el mismo boost; sumar un constante no reordena nada. |
| D8 | El modelo recibe el **índice de la cátedra** (unidades con su código y título) en el **prompt del sistema**, y el **detalle de la unidad en el contexto recuperado** (`[1] (Unidad 2 > Tema 2.1 > …)`). | El índice le da el vocabulario de la materia y evita que invente unidades; el contexto le da la atribución exacta para citar. |
| D9 | Las fuentes de un mensaje se guardan **además** como `SourceRef` estructurado (`ChatMessage.sourceRefs`), sin reemplazar `ChatMessage.sources`. | La analítica necesita saber *de qué unidad* salió cada fragmento; parsear strings es frágil. |

---

## 1. ¿Hace falta una jerarquía de unidades?

### 1.1 Qué aporta y qué cuesta, en el contexto de un RAG por BM25

**A favor**

1. **La cita deja de ser opaca.** Hoy `Chunk.label` es `"cátedra: Apuntes de regresión"` y `Chunk.heading` es el último encabezado Markdown
   (`"Regresión lineal"`). El alumno no sabe si eso es de la Unidad 1 o de la Unidad 5. Con la jerarquía, la cita es
   `Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal`: navegable y accionable ("buscá esto en tus apuntes").
2. **El modelo recibe el contexto con la atribución.** Hoy `RagEngine.prepare` arma `[1] (cátedra: Apuntes de regresión — Regresión lineal)`.
   Con la ruta, el modelo puede decir *"esto es del Tema 2.1"* sin inventarlo, y el verificador de citas sigue funcionando igual
   (mira `Chunk.text`, no la etiqueta).
3. **Permite filtrar y ponderar por unidad.** "Repasá el material de la unidad 2" deja de ser un pedido laxo al modelo: es un
   `Set<String>` de unidades que entra al scoring (§5).
4. **Habilita la analítica por unidad**, que es la pregunta que realmente importa para un docente: *¿de qué temas me preguntan y
   de cuáles no me preguntan nunca?* Hoy la analítica sólo puede decir "se usó el material X".
5. **Le da al modelo el vocabulario de la materia** (nombres de temas, sinónimos del curso) sin pedirle que adivine.

**En contra**

1. **Fricción de carga.** Si el docente tiene que crear 12 unidades a mano antes de subir el primer PDF, no lo va a hacer.
   → Mitigación: las unidades son opcionales y hay **auto-detección** (§2.5): el sistema propone la unidad a partir de los
   encabezados del propio material y el docente confirma con un clic.
2. **Duplicidad de la jerarquía.** Si la taxonomía dice "Unidad 2 / Tema 2.1" y además el Markdown del material empieza con
   `# Unidad 2` / `## Tema 2.1`, la cita podría quedar `"Unidad 2 > Tema 2.1 > Apuntes > Unidad 2 > Tema 2.1"`.
   → Mitigación: **alineación** explícita al trocear (§1.4): los niveles del documento que coinciden con la ruta de la unidad
   se eliminan de la cita.
3. **Un chunk hereda la unidad de su material.** Un material que cubre dos temas tiene una sola ruta primaria.
   → Mitigación: `Material.unitIds` es una lista (la primera es la primaria) y la sección del documento aporta el detalle.
4. **Costo de tokens.** El índice de unidades en el prompt del sistema y las etiquetas en cada fragmento suman contexto.
   → Mitigación: el índice de unidades es chico (10–30 nodos ≈ 300–800 tokens) y va en el **system prompt**, que los
   proveedores cachean; las etiquetas van una sola vez por fragmento.
5. **BM25 no entiende jerarquías.** Es importante decirlo: el `path` **no** mejora el score por sí solo. Meter "unidad 2" en el
   texto indexado hace que *todos* los fragmentos de esa unidad compartan ese término, con un `df` altísimo → `idf` casi 0 →
   aporta poco. El beneficio real de la jerarquía en el RAG viene por el **boosting** y el **filtrado**, no por el score léxico.
   → Por eso el diseño NO pone la jerarquía en el medio del scoring sino en los metadatos, y usa la jerarquía para ponderar.
6. **Los labels se hornean en el índice.** Renombrar "Unidad 2" a "Unidad 2: Paradigmas" cambia lo que el índice contiene.
   → Mitigación: `KnowledgeBase.sync()` ya existe y hay que llamarlo cuando cambia la taxonomía (igual que cuando sube un
   material). La app debe reindexar en ambos casos.

**Veredicto:** la jerarquía **sí**, con dos niveles, opcional, y con los labels **fuera** del scoring léxico.

### 1.2 El `Chunk` de hoy y el `Chunk` propuesto

Hoy (`backend/src/commonMain/kotlin/rag/retrieval/Models.kt`):

```kotlin
data class Doc(val source: String, val path: String, val text: String)
data class Chunk(val source: String, val path: String, val heading: String, val text: String) {
    val label get() = "$source: $path"
}
```

Propuesta (los campos nuevos **tienen valor por defecto**, el constructor posicional no cambia):

```kotlin
data class Chunk(
    val source: String,          // corpus ("cátedra")           — sin cambios
    val path: String,            // documento (título del material) — sin cambios
    val heading: String,         // sección (último encabezado MD)  — sin cambios
    val text: String,            // cuerpo                         — sin cambios
    // ---- metadatos de taxonomía (nuevos, con default) ----
    val materialId: String = "",       // id del material en la cátedra
    val title: String = "",            // título legible del material
    val unitIds: List<String> = emptyList(),   // ["u2", "u2.1"] (el primero es el primario)
    val unitPath: List<String> = emptyList(),  // ["Unidad 2", "Tema 2.1"] (etiquetas)
    val headingPath: List<String> = emptyList(), // pila de encabezados del documento
    val tags: List<String> = emptyList(),       // etiquetas libres del material
    val week: Int = 0,                          // semana del cronograma (0 = ninguna)
    val boost: Double = 1.0,                    // prior estática (1.0 = normal)
)
```

**Regla de los tres campos que ya existen** (para que nadie los use mal):

| Campo | Qué es **exactamente** | Qué **no** es |
|---|---|---|
| `source` | El **corpus**: "cátedra". | No es el material. |
| `path` | El **documento**: hoy el título del material (`CourseMaterialSource` pone `material.title.ifBlank { material.path }`). | No es la ruta en disco ni la unidad. |
| `heading` | La **sección** dentro del documento: el último encabezado Markdown visto. | No es la unidad ni el tema. |

Si el documento no tiene encabezados, `heading` vale el nombre del archivo (comportamiento actual, se mantiene).

### 1.3 Cómo se acumula la jerarquía: dos fuentes, dos roles

```
                    ┌── taxonomía de la cátedra ──┐
  Material ───────► │  Unit (Unidad 2 > Tema 2.1) │ ──► unitPath  ["Unidad 2", "Tema 2.1"]
                    └────────────────────────────┘
                                    +
                    ┌── estructura del documento ─┐
  texto Markdown ──► │  pila de encabezados        │ ──► headingPath ["Regresión lineal"]
                    └────────────────────────────┘
```

* La **taxonomía de la cátedra** la define el docente (entidad `Unit`) y se asigna al **material**.
* La **estructura del documento** sale del propio Markdown y la calcula el `Chunker`.
* Ambas se acumulan en el `Chunk` y se combinan **sólo al mostrar la cita**.

La razón de no fusionarlas en una sola jerarquía: la taxonomía es **del curso** (el mismo "Tema 2.1" sirve para varios
materiales) y la estructura es **del documento** (un PDF escaneado no tiene ninguna). Si se fusionaran, un material sin
encabezados perdería el contexto de unidad, y un material con encabezados propios quedaría atado a una jerarquía que el
docente no diseñó.

### 1.4 La cita: `"Unidad 2 > Tema 2.1 > Apuntes de regresión"`

**Regla de composición** (`Chunk.citationLabel`):

```
citationLabel = unitPath            // ["Unidad 2", "Tema 2.1"]   (puede estar vacío)
              + [título del material]// "Apuntes de regresión"
              + [último encabezado]  // "Regresión lineal" (sólo si el documento tiene encabezados
                                     //  y no coinciden con la ruta de la unidad)
              |> joinToString(" > ")
```

**Regla de alineación** (`HeadingPath.align`): si el documento ya nombra las unidades, esos niveles **no se repiten**.

```
alinear(pilaDeEncabezados, rutaDeUnidades):
    i = 0
    mientras i < pila.size y i < ruta.size y coincide(ruta[i], pila[i]):
        i++
    devolver pila.drop(i)

coincide(etiquetaDeUnidad, encabezado):
    h = normalizar(encabezado)          // minúsculas, sin acentos, sin puntuación
    u = normalizar(etiquetaDeUnidad)    // "unidad 2" | "tema 2.1"
    si h == u:                          → verdadero
    numero = u.tokenFinal()             // "2" | "2.1"
    si numero.length < 2:               → falso     // "1" matchearía media cátedra
    devolver h.tokens().contiene(numero)
```

**Ejemplos con el mismo material** (`Apuntes de regresión`, asignado a `Unidad 2 > Tema 2.1`):

| Encabezados del Markdown | `citationLabel` |
|---|---|
| (ninguno) | `Unidad 2 > Tema 2.1 > Apuntes de regresión` |
| `# Regresión lineal` | `Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal` |
| `# Unidad 2` / `## Tema 2.1` / `### Regresión lineal` | `Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal` |
| `# Unidad 2` / `## Regresión lineal` | `Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal` |

Y con un material **sin** unidades asignadas:

| Encabezados | `citationLabel` |
|---|---|
| (ninguno) | `Apuntes de regresión` |
| `# Regresión lineal` | `Apuntes de regresión > Regresión lineal` |

`Chunk.label` **no se toca**: sigue siendo `"$source: $path"` (= `"cátedra: Apuntes de regresión"`). Es la clave estable que
usa `CitationCheck.label` y la que hoy se guarda en `ChatMessage.sources`.

### 1.5 Dónde se ve la cita

* **En el contexto del modelo** (`RagEngine.prepare`): de `[1] (cátedra: Apuntes de regresión — Regresión lineal)` a
  `[1] (Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal)`.
* **En la UI del alumno**: la lista de fuentes de la respuesta, con la jerarquía navegable (tocar "Tema 2.1" abre el material).
* **En la analítica del docente**: `SourceRef.unitPath` y `SourceRef.unitIds` permiten agrupar por unidad.

---

## 2. Entidad `Unit`

### 2.1 Modelo

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Tipo de nodo de la taxonomía de la cátedra.
 */
@Serializable
enum class UnitKind(val label: String) {
    UNIDAD("Unidad"),
    TEMA("Tema"),
    APARTADO("Apartado"),
    ;

    companion object {
        fun fromName(name: String): UnitKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Un nodo de la taxonomía: "Unidad 2 — Paradigmas", "Tema 2.1 — Supervisión".
 *
 * El árbol se arma con [parentId]; la raíz tiene `parentId = ""`.
 * Por defecto son dos niveles (UNIDAD > TEMA), pero el modelo no lo limita:
 * un tema puede tener APARTADOs.
 */
@Serializable
data class Unit(
    val id: String,
    val courseId: String,
    /** Id del padre ("" = raíz). */
    val parentId: String = "",
    val kind: UnitKind = UnitKind.UNIDAD,
    /** Número o código corto: "2", "2.1". */
    val number: String = "",
    /** Nombre: "Paradigmas", "Supervisión". */
    val title: String = "",
    val description: String = "",
    /** Orden de presentación dentro del padre. */
    val order: Int = 0,
    /** Semana del cronograma en la que se da (0 = sin asignar). */
    val week: Int = 0,
    /** Visible para los alumnos. */
    val visible: Boolean = true,
    /** Peso en la recuperación (1.0 = normal). Rango útil: 0.8 … 1.5. */
    val boost: Double = 1.0,
    /** Etiquetas libres del nodo ("regresión", "parcial 1"). */
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
) {
    /** "Unidad 2" — la etiqueta corta que va en las citas. */
    val shortLabel: String
        get() = if (number.isBlank()) kind.label else "${kind.label} $number"

    /** "Unidad 2 — Paradigmas" — la etiqueta larga (árbol de la UI, prompt). */
    val fullLabel: String
        get() = if (title.isBlank()) shortLabel else "$shortLabel — $title"
}
```

**Convenciones**

* `id`: sigue la convención del resto del dominio (`"$courseId|$uuid"`, como los `ChatThread`).
* `number`: **lo escribe el docente** ("2", "2.1"), no se deriva. Es lo que permite que el material use
  `# Unidad 2` en su Markdown y que la alineación funcione. El código jerárquico completo se *deriva* (`UnitTree.code`).
* `week`: la semana del cronograma. Permite el caso "cátedra sin calendario" (sólo numeración de semanas).
* `boost`: peso propio de la unidad en la recuperación (por defecto 1.0).

### 2.2 Asignación a materiales y a tareas

**Convención única para cualquier cosa asignable a la taxonomía:**

```kotlin
/** Ids de las unidades a las que pertenece la entidad. El primero es la primaria. */
val unitIds: List<String> = emptyList()
```

* `Material.unitIds: List<String>` — el primero es la **unidad primaria** (la que va en `unitPath` y en la cita).
  Los siguientes son "también aparece en" (un TP puede estar en el Tema 2.1 y en el 3.2).
* `Task.unitIds: List<String>` — **misma convención**. `Task` todavía no existe en el dominio
  (`CONTEXTO.md` no lo lista); si otro documento de diseño lo define, debe usar esta lista y no un `unitId: String`,
  para que la misma maquinaria de boost/analítica funcione sin cambios. Si más adelante se define `Task`, el campo
  queda listo y este documento no requiere cambios.

**Por qué una lista y no un `unitId` único:** el caso real "este material de repaso cubre dos temas" es frecuente, y un
campo único obligaría a duplicar el material. El primario resuelve la ambigüedad de la cita; los secundarios alimentan el
boost (un fragmento cuya unidad secundaria coincide con la consulta también sube) y la analítica.

**Diff en `core/src/commonMain/kotlin/kitoru/domain/Course.kt`:**

```diff
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
+    /** Unidades a las que pertenece (la primera es la primaria). */
+    val unitIds: List<String> = emptyList(),
+    /** Peso del material en la recuperación (1.0 = normal). Rango útil: 0.5 … 2.0. */
+    val boost: Double = 1.0,
     val visible: Boolean = true,
     val extractedText: String = "",
     val warning: String = "",
 )
```

```diff
 @Serializable
 data class Course(
     ...
+    /** Inicio de la cursada en formato ISO "2026-04-06" (opcional). */
+    val startDate: String = "",
     ...
 )
```

`Course.startDate` es lo que permite calcular "en qué semana estamos" sin depender del modelo.

### 2.3 Consultas sobre el árbol: `UnitTree`

```kotlin
package kitoru.domain

/**
 * Consultas sobre el árbol de unidades de una cátedra.
 *
 * Todas son funciones puras sobre la lista de [Unit]: no tocan disco ni el modelo.
 * El árbol se valida con [validate] y se muestra con [outline].
 */
object UnitTree {

    /** Índice id -> unidad. */
    fun byId(units: List<Unit>): Map<String, Unit> = units.associateBy { it.id }

    /** Hijos directos de un padre ("" = raíces), ordenados. */
    fun children(units: List<Unit>, parentId: String): List<Unit> =
        units.filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.order }, { it.number }, { it.title }))

    /** Raíces del árbol. */
    fun roots(units: List<Unit>): List<Unit> = children(units, "")

    /**
     * Ruta de ids desde la raíz hasta la unidad (incluida).
     * Vacía si la unidad no existe. Corta si hay un ciclo.
     */
    fun pathIds(units: List<Unit>, unitId: String): List<String> {
        val byId = byId(units)
        val path = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = unitId
        while (current.isNotBlank() && seen.add(current)) {
            val unit = byId[current] ?: break
            path += current
            current = unit.parentId
        }
        return path.reversed()
    }

    /** Etiquetas cortas de la ruta: ["Unidad 2", "Tema 2.1"]. */
    fun pathLabels(units: List<Unit>, unitId: String): List<String> =
        pathIds(units, unitId).mapNotNull { byId(units)[it]?.shortLabel }

    /** Etiquetas largas de la ruta: ["Unidad 2 — Paradigmas", "Tema 2.1 — Supervisión"]. */
    fun pathTitles(units: List<Unit>, unitId: String): List<String> =
        pathIds(units, unitId).mapNotNull { byId(units)[it]?.fullLabel }

    /** Código jerárquico derivado: "2" o "2.1". */
    fun code(units: List<Unit>, unitId: String): String =
        pathIds(units, unitId)
            .mapNotNull { byId(units)[it]?.number.takeIf { n -> n.isNotBlank() } }
            .joinToString(".")

    /** La unidad y todas sus descendientes (ids). Corta ante ciclos. */
    fun descendants(units: List<Unit>, unitId: String): List<String> {
        if (units.none { it.id == unitId }) return emptyList()
        val out = mutableListOf(unitId)
        var frontier = listOf(unitId)
        while (frontier.isNotEmpty()) {
            val fresh = units.filter { it.parentId in frontier }.map { it.id }.filter { it !in out }
            if (fresh.isEmpty()) break
            out += fresh
            frontier = fresh
        }
        return out
    }

    /** Unidades que mencionan [query] (para "de qué unidad me preguntan"). */
    fun search(units: List<Unit>, query: String, limit: Int = 20): List<Unit> {
        val q = HeadingKey.key(query)
        if (q.isBlank()) return emptyList()
        return units.filter { unit ->
            HeadingKey.key(unit.shortLabel).contains(q) ||
                HeadingKey.key(unit.fullLabel).contains(q) ||
                (unit.title.isNotBlank() && HeadingKey.key(unit.title).contains(q))
        }.take(limit)
    }

    /** Problemas de consistencia (para mostrarlos al docente). */
    fun validate(units: List<Unit>): List<String> {
        val problems = mutableListOf<String>()
        val byId = byId(units)
        for (unit in units) {
            if (unit.parentId.isNotBlank() && unit.parentId !in byId) {
                problems += "«${unit.fullLabel}» apunta a un padre que no existe."
            }
            if (unit.parentId == unit.id) problems += "«${unit.fullLabel}» es su propio padre."
        }
        for (unit in units) if (hasCycle(units, unit.id)) {
            problems += "«${unit.fullLabel}» está en un ciclo de padres."
        }
        return problems
    }

    private fun hasCycle(units: List<Unit>, unitId: String): Boolean {
        val byId = byId(units)
        val seen = mutableSetOf<String>()
        var current = byId[unitId]?.parentId ?: return false
        while (current.isNotBlank()) {
            if (!seen.add(current)) return true
            current = byId[current]?.parentId ?: return false
        }
        return false
    }

    /**
     * Índice de la cátedra para el prompt del sistema.
     * Sólo las unidades visibles; el orden es el del árbol.
     */
    fun outline(
        units: List<Unit>,
        materialsByUnit: Map<String, Int> = emptyMap(),
        maxLines: Int = 200,
    ): String {
        val byId = byId(units)
        val lines = mutableListOf<String>()
        fun walk(parentId: String, depth: Int) {
            if (lines.size >= maxLines) return
            for (unit in children(units, parentId)) {
                if (!unit.visible) continue
                val week = if (unit.week > 0) " (semana ${unit.week})" else ""
                val count = materialsByUnit[unit.id] ?: 0
                val mats = if (count > 0) " — $count material(es)" else " — sin material"
                lines += "${"  ".repeat(depth)}- ${unit.fullLabel}$week$mats"
                walk(unit.id, depth + 1)
            }
        }
        walk("", 0)
        return lines.joinToString("\n")
    }
}
```

### 2.4 `CourseCatalog`: las consultas que la UI y el RAG necesitan

```kotlin
package kitoru.domain

/**
 * Consultas de catálogo de una cátedra: qué material hay, en qué unidad y
 * en qué semana. Todo lo que necesita la UI del docente y el indexador.
 */
object CourseCatalog {

    /** Ids de las unidades de un material (primaria primero). */
    fun unitIdsOf(materials: List<Material>, materialId: String): List<String> =
        materials.firstOrNull { it.id == materialId }?.unitIds.orEmpty()

    /** Ruta legible de unidades de un material: ["Unidad 2", "Tema 2.1"]. */
    fun unitPathOf(materials: List<Material>, units: List<Unit>, materialId: String): List<String> {
        val ids = unitIdsOf(materials, materialId)
        return ids.firstOrNull()?.let { UnitTree.pathLabels(units, it) }.orEmpty()
    }

    /** Unidad primaria de un material (o null). */
    fun primaryUnit(materials: List<Material>, units: List<Unit>, materialId: String): Unit? {
        val id = unitIdsOf(materials, materialId).firstOrNull() ?: return null
        return units.firstOrNull { it.id == id }
    }

    /** Materiales de una unidad (incluye los de sus descendientes). */
    fun materialsOfUnit(materials: List<Material>, units: List<Unit>, unitId: String): List<Material> {
        val ids = UnitTree.descendants(units, unitId).toSet()
        return materials.filter { material -> material.unitIds.any { it in ids } }
    }

    /** Unidades sin ningún material asignado (huecos de la cátedra). */
    fun unitsWithoutMaterial(materials: List<Material>, units: List<Unit>): List<Unit> {
        val covered = materials.flatMap { it.unitIds }.toSet()
        return units.filter { it.id !in covered }
    }

    /**
     * Semana de un material: la de su unidad primaria; si no tiene, la de la
     * entrada del cronograma que lo lista.
     */
    fun weekOfMaterial(
        materials: List<Material>,
        units: List<Unit>,
        schedule: List<ScheduleEntry>,
        materialId: String,
    ): Int {
        primaryUnit(materials, units, materialId)?.week?.takeIf { it > 0 }?.let { return it }
        return schedule.firstOrNull { materialId in it.materialIds }?.week ?: 0
    }

    /** Cantidad de materiales por unidad (para el índice del prompt). */
    fun materialCountByUnit(materials: List<Material>, units: List<Unit>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (unit in units) {
            val n = materialsOfUnit(materials, units, unit.id).size
            if (n > 0) counts[unit.id] = n
        }
        return counts
    }
}
```

### 2.5 Auto-detección (para bajar la fricción)

El docente sube un PDF cuyo Markdown empieza con `# Unidad 2` / `## Regresión lineal`. El sistema puede **proponer** la
unidad sin que el docente la busque:

```kotlin
/**
 * Propone la unidad de un material a partir de sus encabezados.
 *
 * Se usa en la pantalla de materiales: "Este material parece ser del
 * Tema 2.1 — Supervisión. ¿Asignarlo?".
 */
object UnitSuggester {
    fun suggest(material: Material, units: List<Unit>): Unit? {
        val headings = material.extractedText.lineSequence()
            .filter { it.startsWith("#") }
            .map { it.trimStart('#').trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .toList()
        if (headings.isEmpty()) return null
        return units.firstOrNull { unit ->
            val labels = UnitTree.pathLabels(units, unit.id)
            labels.isNotEmpty() && labels.zip(headings).all { (label, heading) ->
                HeadingKey.matches(label, heading)
            }
        }
    }
}
```

Es una **sugerencia**, no una asignación automática: el docente la acepta o la descarta. Nada se escribe solo.

---

## 3. Semanas y cronograma

### 3.1 ¿Tiene calendario la cátedra?

**Depende de la cátedra, y el diseño tiene que servir para las dos.** Hay cátedras con clases los lunes 18hs y cátedras
con "semana 1..16" y nada más. De ahí la separación:

* **El número de semana es un dato de la taxonomía**: vive en `Unit.week` y **siempre** existe (por defecto 0 = "sin asignar").
* **El calendario es un dato opcional**: `ScheduleEntry` con fecha, tipo de clase, unidades y materiales.

Si la cátedra no carga cronograma, todo lo de abajo sigue funcionando: `Unit.week` alimenta el boost por semana y la
analítica por semana. Lo que se pierde es la resolución de "la semana pasada" en tiempo real (que necesita fechas).

### 3.2 `ScheduleEntry`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Tipo de evento del cronograma. */
@Serializable
enum class ScheduleKind(val label: String) {
    CLASE("Clase"),
    PRACTICA("Práctica"),
    LAB("Laboratorio"),
    EXAMEN("Examen"),
    LECTURA("Lectura"),
    OTRO("Otro"),
    ;

    companion object {
        fun fromName(name: String): ScheduleKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Un evento del cronograma de la cátedra: "Semana 3 — Clase 3: Paradigmas".
 *
 * Es opcional: una cátedra puede no tener cronograma. Cuando lo tiene,
 * habilita dos cosas que sin él no se pueden hacer:
 *  - resolver referencias relativas en el chat ("la clase de la semana pasada");
 *  - atribuir preguntas y respuestas a una semana en la analítica.
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val courseId: String,
    /** Semana de la cursada (1 = la primera). */
    val week: Int = 1,
    /** Fecha del evento en formato ISO "2026-04-06" (opcional). */
    val date: String = "",
    val title: String = "",
    val kind: ScheduleKind = ScheduleKind.CLASE,
    /** Unidades que se ven en este evento. */
    val unitIds: List<String> = emptyList(),
    /** Materiales de apoyo de este evento. */
    val materialIds: List<String> = emptyList(),
    val notes: String = "",
    val createdAt: Long = 0L,
) {
    /** "Semana 3 — Clase 3: Paradigmas". */
    val label: String get() = if (title.isBlank()) "Semana $week" else "Semana $week — $title"
}
```

### 3.3 Para qué sirve

**(a) Contexto del chat: "la clase de la semana pasada"**

El alumno escribe *"¿qué vimos la semana pasada?"*. Sin taxonomía, el modelo tiene que adivinar (y adivina mal: no sabe
qué es "la semana pasada" en una cursada de marzo).

Con el cronograma, la resolución es **determinista** (regla 3 del proyecto: nada que dependa del modelo):

```
detectTemporal(question) → "semana pasada"
   ↓
currentWeek(course, schedule, now) → 5        (determinista, con `now` inyectado)
   ↓
week = 5 - 1 = 4
   ↓
ScheduleResolver.entriesOfWeek(schedule, 4) → [ScheduleEntry(week=4, unitIds=[u2, u2.1])]
   ↓
contexto extra para el prompt:
   "Esta semana (5) la cursada va por: Unidad 2 — Paradigmas.
    La semana pasada (4) fue: Clase 4 — Regresión | Temas: Unidad 2, Tema 2.1"
   ↓
BoostContext.week = 4  → los fragmentos de la semana 4 suben ×1.4 en la recuperación
```

La resolución es un **regex determinista** + aritmética de fechas. No hay llamada al modelo, no hay red, es testeable al
100 %.

**(b) Analítica: "¿los alumnos preguntan sobre el material de la semana 3?"**

Cada respuesta del asistente guarda sus `SourceRef` (con `unitIds` y `week`). De ahí sale una tabla:

| Semana | Preguntas | Respuestas | Materiales usados | Unidades tocadas |
|---|---|---|---|---|
| 1 | 3 | 3 | 2 | Unidad 1, Tema 1.1 |
| 2 | 0 | 0 | 0 | — |
| 3 | 11 | 9 | 4 | Unidad 2, Tema 2.1, Tema 2.2 |
| 4 | 27 | 25 | 6 | Unidad 3, Tema 3.1 |

La fila con 0 preguntas y 0 respuestas es exactamente la pregunta que el docente se hace: *"nadie me preguntó sobre la
semana 2"*.

### 3.4 `ScheduleResolver` y `TemporalReference`

```kotlin
package kitoru.domain

/**
 * Resolución del cronograma: qué semana es, qué hay en cada semana y cómo se
 * traduce una frase del alumno ("la semana pasada") a una semana concreta.
 *
 * Todo es determinista: recibe `now` por parámetro y no consulta el reloj.
 */
object ScheduleResolver {

    /**
     * Semana actual de la cursada.
     *
     *  - Si la cátedra tiene `startDate`, la semana es
     *    `floor((hoy - inicio) / 7) + 1`.
     *  - Si no, se usa la fecha de las entradas: la semana de la última
     *    entrada que ya pasó; si todavía no empezó ninguna, la de la primera.
     *  - Si no hay ninguna fecha: 0 (no se sabe).
     */
    fun currentWeek(course: Course, entries: List<ScheduleEntry>, now: Long): Int {
        val start = IsoDate.parse(course.startDate)
        if (start != null) {
            val days = IsoDate.daysBetween(start, now)
            return (days / 7 + 1).coerceAtLeast(1)
        }
        val dated = entries.mapNotNull { entry -> IsoDate.parse(entry.date)?.let { entry to it } }
        if (dated.isEmpty()) return 0
        val today = IsoDate.daysOf(now)
        val past = dated.filter { (_, day) -> day <= today }.maxByOrNull { (_, day) -> day }
        val anchor = past ?: dated.minByOrNull { (_, day) -> day }!!
        return anchor.first.week
    }

    /** Semana de una fecha ISO, según la fecha de inicio de la cátedra. */
    fun weekOfDate(course: Course, date: String): Int {
        val day = IsoDate.parse(date) ?: return 0
        val start = IsoDate.parse(course.startDate) ?: return 0
        val days = IsoDate.daysBetween(start, day)
        return (days / 7 + 1).coerceAtLeast(1)
    }

    /** Entradas de una semana, ordenadas por fecha. */
    fun entriesOfWeek(entries: List<ScheduleEntry>, week: Int): List<ScheduleEntry> =
        entries.filter { it.week == week }.sortedBy { it.date }

    /** Unidades de una semana (deduplicadas, en orden de aparición). */
    fun unitsOfWeek(entries: List<ScheduleEntry>, week: Int): List<String> =
        entriesOfWeek(entries, week).flatMap { it.unitIds }.distinct()

    /** Materiales de una semana. */
    fun materialsOfWeek(entries: List<ScheduleEntry>, week: Int): List<String> =
        entriesOfWeek(entries, week).flatMap { it.materialIds }.distinct()

    /** Semana en la que aparece un material (0 si no aparece). */
    fun weekOfMaterial(entries: List<ScheduleEntry>, materialId: String): Int =
        entries.firstOrNull { materialId in it.materialIds }?.week ?: 0

    /**
     * Bloque de cronograma para el prompt (sólo las semanas pedidas).
     * @param weeksSemanas semanas a incluir (la actual y la anterior, por ejemplo).
     */
    fun render(
        entries: List<ScheduleEntry>,
        weeks: List<Int>,
        units: List<Unit>,
    ): String = weeks
        .filter { it > 0 }
        .flatMap { week ->
            entriesOfWeek(entries, week).map { entry ->
                val temas = entry.unitIds.mapNotNull { id -> units.firstOrNull { it.id == id }?.fullLabel }
                val temasTxt = if (temas.isEmpty()) "" else " | Temas: ${temas.joinToString(", ")}"
                val fecha = if (entry.date.isBlank()) "" else " (${entry.date})"
                "- Semana $week$fecha: ${entry.title}$temasTxt"
            }
        }
        .joinToString("\n")
}
```

```kotlin
package kitoru.domain

/** Tipo de referencia temporal detectada en una pregunta. */
enum class TemporalKind { LAST_WEEK, THIS_WEEK, NEXT_WEEK, YESTERDAY, TODAY, WEEK_NUMBER }

/** Referencia temporal detectada en una pregunta. */
data class TemporalRef(val kind: TemporalKind, val week: Int = 0)

/**
 * Detección de referencias temporales en el texto del alumno.
 *
 * Es un regex, no un modelo: determinista, barato y testeable.
 * "la clase de la semana pasada" → LAST_WEEK; "el material de la semana 3" → WEEK_NUMBER(3).
 */
object TemporalReference {

    private val NUMEROS = mapOf(
        "uno" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
    )

    private val RE_SEMANA_NUM = Regex("""semana\s+(\d{1,2})\b""")
    private val RE_SEMANA_PALABRA = Regex("""semana\s+(uno|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)\b""")

    fun detect(question: String): TemporalRef? {
        val q = HeadingKey.key(question)
        if (q.isBlank()) return null
        // El orden importa: "la semana pasada" matchea antes que "semana N".
        if (q.contains("semana pasada") || q.contains("semanas pasadas")) {
            return TemporalRef(TemporalKind.LAST_WEEK)
        }
        if (q.contains("esta semana") || q.contains("semana actual")) {
            return TemporalRef(TemporalKind.THIS_WEEK)
        }
        if (q.contains("semana que viene") || q.contains("proxima semana")) {
            return TemporalRef(TemporalKind.NEXT_WEEK)
        }
        RE_SEMANA_NUM.find(q)?.let { m ->
            return TemporalRef(TemporalKind.WEEK_NUMBER, m.groupValues[1].toIntOrNull() ?: 0)
        }
        RE_SEMANA_PALABRA.find(q)?.let { m ->
            return TemporalRef(TemporalKind.WEEK_NUMBER, NUMEROS[m.groupValues[1]] ?: 0)
        }
        if (q.contains("ayer")) return TemporalRef(TemporalKind.YESTERDAY)
        if (q.contains("hoy")) return TemporalRef(TemporalKind.TODAY)
        return null
    }

    /**
     * Traduce la referencia a una semana concreta de la cursada.
     * @param currentWeek semana actual (0 si no se puede determinar).
     * @return la semana referida (0 si no se puede).
     */
    fun resolve(ref: TemporalRef, currentWeek: Int): Int = when (ref.kind) {
        TemporalKind.LAST_WEEK -> (currentWeek - 1).coerceAtLeast(0)
        TemporalKind.THIS_WEEK, TemporalKind.TODAY, TemporalKind.YESTERDAY -> currentWeek
        TemporalKind.NEXT_WEEK -> currentWeek + 1
        TemporalKind.WEEK_NUMBER -> ref.week
    }
}
```

### 3.5 Fechas ISO sin `java.time`

`commonMain` no puede usar `java.time` (regla 2 del proyecto). El cálculo de fechas se hace con aritmética de días
civiles (algoritmo de Hinnant), que es portable y determinista:

```kotlin
package kitoru.domain

/**
 * Fechas en formato ISO "2026-04-06" calculadas con aritmética pura.
 *
 * No usa `java.time` (no existe en commonMain). Trabaja en UTC y a medianoche:
 * el cronograma de una cátedra no necesita hora local.
 */
object IsoDate {
    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private const val DAY_MS = 86_400_000L

    /** "2026-04-06" -> milisegundos (medianoche UTC). null si no parsea. */
    fun parse(text: String): Long? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return daysFromCivil(y, mo, d) * DAY_MS
    }

    /** Milisegundos -> "2026-04-06". */
    fun format(millis: Long): String {
        val (y, m, d) = civilFromDays(Math.floorDiv(millis, DAY_MS))
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** Día (medianoche UTC) de un instante. */
    fun daysOf(millis: Long): Long = Math.floorDiv(millis, DAY_MS)

    /** Días entre dos instantes (positivo si `to` es posterior). */
    fun daysBetween(from: Long, to: Long): Long = daysOf(to) - daysOf(from)

    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = Math.floorDiv(yy.toLong(), 400L)
        val yoe = yy - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1)
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }
}
```

---

## 4. Etiquetas libres (tags)

### 4.1 Modelo

Ya existen `Material.tags` y `ChatThread.tags` (`List<String>`). La decisión es **no inventar un modelo nuevo**: las
etiquetas son strings libres, con una normalización canónica para poder compararlas.

```kotlin
package kitoru.domain

import kitoru.platform.stripDiacritics

/**
 * Normalización de etiquetas libres.
 *
 * Las etiquetas son texto libre (el docente escribe "Parcial 1", "examen",
 * "Graduación"). Para poder compararlas (con una consulta, con otra
 * etiqueta) se normalizan a una forma canónica.
 */
object Tags {
    /** Forma canónica: minúsculas, espacios colapsados. "  Parcial 1 " -> "parcial 1". */
    fun normalize(tag: String): String =
        tag.trim().lowercase().replace(Regex("""\s+"""), " ")

    /** Forma canónica comparable sin acentos ni puntuación. */
    fun key(text: String): String =
        stripDiacritics(normalize(text)).replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /** Etiquetas únicas y ordenadas, ya normalizadas. */
    fun dedup(tags: List<String>): List<String> =
        tags.map { normalize(it) }.filter { it.isNotBlank() }.distinct().sorted()
}
```

**Dónde viven las etiquetas**

| Entidad | Campo | Quién las pone | Para qué |
|---|---|---|---|
| `Material` | `tags: List<String>` (ya existe) | el docente | clasificar, marcar prioridad ("examen"), vocabulario de búsqueda |
| `Unit` | `tags: List<String>` (nuevo) | el docente | sinónimos del tema ("RL", "regresión lineal") |
| `ChatThread` | `tags: List<String>` (ya existe) | el alumno | "duda de parcial" — **no entra al RAG** (ver abajo) |
| `Task` (futuro) | `tags: List<String>` | el docente | mismo criterio que `Material` |

### 4.2 Para qué sirven

1. **Vocabulario de la cátedra.** El profesor escribe "degradado" y el alumno escribe "gradiente": la etiqueta es el puente.
2. **Marcas de prioridad.** Una etiqueta reservada (`examen`, `parcial`, `final`, `importante`, `clave`) activa el boost (§5).
3. **Filtro en la UI del docente**: "materiales del Tema 2.1" / "materiales con etiqueta X".
4. **Agrupar preguntas.** Un hilo puede llevar etiquetas ("duda de parcial") → la analítica puede mostrar "12 hilos
   etiquetados 'duda de parcial'". No entra al RAG: las etiquetas de un hilo son del alumno, no del corpus.

### 4.3 ¿Entran al RAG como términos de consulta?

**Sí, de dos maneras y sólo las de los materiales** (las de los hilos, no: son del alumno).

**(a) Lado índice — las etiquetas se indexan.**
Cada fragmento de un material lleva las etiquetas del material, **una vez** (no repetidas por el texto), agregadas al
texto que se indexa (`Chunk.indexedText`). Consecuencia: si un material tiene la etiqueta "graduación", *todos* sus
fragmentos son alcanzables con la palabra "graduación", aunque el cuerpo no la mencione. Como el `tf` de la etiqueta es 1
contra el `tf` del cuerpo, el efecto es de **prior suave**, no de salto.

**(b) Lado consulta — expansión con peso.**
Si la consulta ya matchea una etiqueta, se agregan las etiquetas **co-ocurrentes** (las de los mismos materiales) con peso
**0.3** en el score BM25. El peso no es 1.0 a propósito: la expansión es una sugerencia, no la pregunta del alumno.

```
pregunta: "regresión"
  → tags en la pregunta: {} (ninguna etiqueta matchea)
pregunta: "graduación"
  → tags en la pregunta: {"graduacion"}
  → expansión: {"regresion": 0.3, "lineal": 0.3}   (las etiquetas de los materiales con "graduación")
  → consulta al índice: "graduación regresion lineal"  con pesos {graduacion:1.0, regresion:0.3, lineal:0.3}
```

La expansión **no puede** repetir un término que ya estaba en la pregunta: ese conserva el peso 1.0.

**Por qué no con un modelo de embeddings:** el motor es BM25 (determinista, sin dependencias, sin GPU) y la regla 1 del
proyecto es reutilizar, no reinventar. La expansión por etiquetas es la versión "de a poco" del reranking semántico: si
algún día se agrega un reranker, esta capa sigue sirviendo como filtro léxico previo.

### 4.4 `TagVocabulary` (backend)

```kotlin
package rag.retrieval

import kitoru.domain.Material
import kitoru.domain.Tags

/**
 * Vocabulario de etiquetas de la cátedra.
 *
 * Sirve para dos cosas:
 *  - detectar qué etiquetas de la cátedra aparecen en la pregunta;
 *  - expandir la pregunta con las etiquetas co-ocurrentes (peso bajo).
 */
class TagVocabulary(private val materials: List<Material>) {

    /** Etiquetas de la cátedra, normalizadas y únicas. */
    val tags: List<String> = materials.flatMap { it.tags }.map { Tags.normalize(it) }
        .filter { it.isNotBlank() }.distinct().sorted()

    /** materialId -> etiquetas normalizadas de ese material. */
    private val byMaterial: Map<String, Set<String>> =
        materials.associate { it.id to it.tags.map { t -> Tags.normalize(t) }.toSet() }

    /**
     * Etiquetas de la cátedra que aparecen en la consulta.
     * Se comparan por token: la etiqueta "parcial 1" matchea si la consulta
     * contiene "parcial 1" o "parcial" y "1".
     */
    fun tagsIn(query: String): Set<String> {
        val q = Tags.key(query)
        if (q.isBlank()) return emptySet()
        return tags.filterTo(mutableSetOf()) { tag ->
            val key = Tags.key(tag)
            key.isNotBlank() && key.split(' ').all { token -> q.contains(token) }
        }
    }

    /**
     * Etiquetas relacionadas con las de la consulta (las de los mismos
     * materiales), para expandirla. No repite etiquetas ya presentes.
     */
    fun expand(query: String, found: Set<String>, max: Int = 4): List<String> {
        if (found.isEmpty()) return emptyList()
        val related = linkedSetOf<String>()
        for (material in materials) {
            val materialTags = byMaterial[material.id].orEmpty()
            if (materialTags.none { it in found }) continue
            for (tag in materialTags) {
                if (tag in found) continue
                related += tag
            }
        }
        return related.take(max)
    }
}
```

---

## 5. Boosting en el RAG

### 5.1 La pregunta

> Si un material está etiquetado como "relevante para el examen", ¿debería pesar más en la recuperación?

**Sí**, pero **de forma acotada y explicable**. El riesgo de un boosting fuerte es que el modelo responda con material
irrelevante sólo porque el docente lo marcó. La regla que se adopta:

* El boost **multiplica** el score BM25, no lo reemplaza ni lo suma.
* El boost es **acotado** (0.5 … 3.0): un material marcado puede subir de rango, pero no tapar el material que la pregunta
  realmente describe.
* El boost es **determinista**: función pura de (metadatos del fragmento, contexto de la consulta). Cero modelo, cero red.
* El boost es **trazable**: la UI puede mostrar por qué un fragmento salió arriba ("×1.4: material de examen").

### 5.2 Los dos factores

```
scoreFinal(i) = scoreBM25(i) × boostEstatico(i) × factorConsulta(i)
```

**`boostEstatico(i)` — lo decide el docente, no la consulta.** Se calcula al indexar y se hornea en `Chunk.boost`:

```
boostEstatico = material.boost                                  (por defecto 1.0)
               × (1.4 si alguna etiqueta del material está en priorityTags) else 1.0
               × boostDeLaUnidadPrimaria                          (por defecto 1.0)
               acotado a [0.5, 3.0]
```

`priorityTags` por defecto: `{"examen", "parcial", "final", "importante", "clave"}` (configurable por cátedra).

**`factorConsulta(i)` — depende de lo que se preguntó.** Se aplica en `KnowledgeBase.retrieve`, después del índice:

```
factorConsulta = 1.0
               × (1.3 si las unidades del fragmento ∩ unidades de la consulta ≠ ∅)
               × (1.4 si la consulta refiere una semana y el fragmento es de esa semana)
               × (1.2 si las etiquetas del fragmento ∩ etiquetas de la consulta ≠ ∅)
               acotado a [0.5, 3.0]
```

### 5.3 Algoritmo exacto (pseudo-código)

```
fun retrieve(query, k, ctx, termWeights):
    # 1. Candidatos del índice (BM25 puro + peso estático)
    pool = min(tamañoDelIndice, max(k * 4, k + 10))
    candidatos = indice.search(query, pool, termWeights)

    # 2. Re-ordenación por el factor de consulta
    scored = candidatos.map { (chunk, score) ->
        (chunk, score * factorConsulta(chunk, ctx))
    }.filter { it.second > 0.0 }

    # 3. Orden estable (Kotlin: sortedByDescending es estable) y corte
    return scored.sortedByDescending { it.second }.take(k).map { it.first }
```

Y dentro del índice:

```
fun search(query, k, termWeights = {}):
    para cada término t de tokenize(query).distinct():
        d = df[t]; si no existe, seguir
        idf = ln(1 + (n - d + 0.5) / (d + 0.5))
        w = termWeights[t] ?: 1.0                    # NUEVO (0.3 para la expansión)
        para cada chunk i:
            f = tf[i][t]; si f == 0, seguir
            scores[i] += w * idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len[i] / avgLen))
    para cada i:
        scores[i] *= chunks[i].boost                # NUEVO (prior estático)
    devolver los k con score > 0, ordenados descendente (empate: orden original del índice)
```

**Por qué `pool = max(k*4, k+10)`:** el factor de consulta puede promoting un fragmento que el BM25 dejó fuera del top-k.
Con un pool 4 veces mayor, esos candidatos todavía están disponibles. El pool se acota al tamaño real del índice.

**Por qué el filtro `score > 0` se mantiene:** un fragmento que no matchea nada (ni por texto, ni por etiqueta vía el
factor) no entra. El factor de consulta **no** crea fragmentos de la nada: multiplica, no suma. Esto es deliberado —
un material relevante sin ninguna coincidencia textual no debería aparecer por el solo hecho de estar etiquetado.

### 5.4 Ejemplo numérico

Corpus de 40 fragmentos. Pregunta: *"¿regresión lineal?"*.

| | chunk A | chunk B | chunk C |
|---|---|---|---|
| Material | Apuntes de regresión | Diapositivas 3 | Apuntes de ML |
| Etiquetas | `examen` | — | — |
| Unidad | Tema 2.1 | Unidad 2 | Unidad 4 |
| Semana | 4 | 4 | 2 |
| BM25 | 3.42 | 3.09 | 3.00 |
| `boost` estático (×1.4 por "examen") | **4.79** | 3.09 | 3.00 |
| factor de consulta (sin referencia temporal) | ×1.0 | ×1.0 | ×1.0 |
| **Final** | **4.79** | **3.09** | **3.00** |

Ahora la misma pregunta con el contexto *"¿qué vimos la semana pasada?"* y semana actual 5 (⇒ semana referida 4):

| | chunk A | chunk B | chunk C |
|---|---|---|---|
| Final (×1.4 por semana) | **6.71** | **4.33** | 3.00 |

El material de examen (A) queda arriba y el de la semana pasada (B) sube por encima del material simplemente similar (C).

### 5.5 Cambios en `Bm25Index` (compatibles)

`backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt`:

```diff
 class Bm25Index(private val chunks: List<Chunk>) {
     private val k1 = 1.5
     private val b = 0.75
 
     private val tfs: List<Map<String, Int>> =
-        chunks.map { c -> tokenize("${c.heading} ${c.path} ${c.text}").groupingBy { it }.eachCount() }
+        chunks.map { c -> tokenize(c.indexedText).groupingBy { it }.eachCount() }
     private val lens = tfs.map { it.values.sum() }
     private val avgLen = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
     private val df: Map<String, Int> = HashMap<String, Int>().also { m ->
         tfs.forEach { tf -> tf.keys.forEach { m.merge(it, 1, Int::plus) } }
     }
 
     val size get() = chunks.size
 
-    fun search(query: String, k: Int): List<Pair<Chunk, Double>> {
+    /**
+     * Busca los fragmentos relevantes.
+     *
+     * @param termWeights peso por término de la consulta (1.0 por defecto).
+     *   Se usa para la expansión por etiquetas (peso 0.3).
+     */
+    fun search(
+        query: String,
+        k: Int,
+        termWeights: Map<String, Double> = emptyMap(),
+    ): List<Pair<Chunk, Double>> {
         if (chunks.isEmpty()) return emptyList()
         val n = chunks.size.toDouble()
         val scores = DoubleArray(chunks.size)
         for (term in tokenize(query).distinct()) {
             val d = df[term] ?: continue
             val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
+            val w = termWeights[term] ?: 1.0
             for (i in chunks.indices) {
                 val f = tfs[i][term] ?: continue
-                scores[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
+                scores[i] += w * idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
             }
         }
+        // Prior estática del fragmento (material marcado, unidad con foco).
+        for (i in scores.indices) scores[i] *= chunks[i].boost
         return scores.withIndex()
             .filter { it.value > 0 }
             .sortedByDescending { it.value }
             .take(k)
             .map { chunks[it.index] to it.value }
     }
```

**Por qué no rompe nada:**

1. `search(query, k)` sigue compilando (el parámetro nuevo tiene default).
2. Con `termWeights` vacío, `w = 1.0` ⇒ **scores idénticos** a los de hoy.
3. Con `Chunk.boost = 1.0` (default) ⇒ **scores idénticos** a los de hoy.
4. `Chunk.indexedText` para un fragmento sin metadatos produce exactamente los mismos tokens que
   `"${c.heading} ${c.path} ${c.text}"` (los espacios sobrantes no generan tokens: el tokenizador corta con `[^a-z0-9]+`).
5. El orden es estable: `sortedByDescending` de Kotlin es un sort estable, así que los empates conservan el orden del
   índice (que es el orden de los materiales ⇒ reproducible).
6. Los tests portados (`Bm25IndexTest`, `ChunkerTest`) siguen pasando sin tocarlos.

### 5.6 `RetrievalBoost` (backend)

```kotlin
package rag.retrieval

import kitoru.domain.BoostContext
import kitoru.domain.Tags
import kitoru.domain.Unit

/**
 * Ajustes de la puntuación por metadatos de la cátedra.
 *
 * Dos factores, con responsabilidades distintas:
 *  - [staticBoost]: lo decide el docente (importancia del material, foco de la
 *    unidad, etiqueta de examen). Se calcula al indexar.
 *  - [queryFactor]: depende de la pregunta (unidad mencionada, semana
 *    referida, etiqueta repetida). Se calcula al recuperar.
 *
 * Los dos son multiplicativos y acotados: un material marcado sube de rango,
 * pero no tapa el material que la pregunta realmente describe.
 */
object RetrievalBoost {

    /** Peso extra si el material tiene una etiqueta de prioridad ("examen"). */
    const val PRIORITY_TAG = 1.4

    /** Peso extra si la pregunta menciona la unidad del fragmento. */
    const val UNIT_MATCH = 1.3

    /** Peso extra si la pregunta refiere la semana del fragmento. */
    const val WEEK_MATCH = 1.4

    /** Peso extra si la pregunta repite una etiqueta del material. */
    const val TAG_MATCH = 1.2

    /** Piso y techo de cualquier factor. */
    const val MIN_FACTOR = 0.5
    const val MAX_FACTOR = 3.0

    /**
     * Prior estática de un material (se hornea en `Chunk.boost`).
     */
    fun staticBoost(
        materialBoost: Double,
        materialTags: List<String>,
        unit: Unit?,
        priorityTags: Set<String>,
    ): Double {
        var factor = materialBoost
        val tags = materialTags.map { Tags.normalize(it) }.toSet()
        if (tags.any { it in priorityTags.map { p -> Tags.normalize(p) } }) factor *= PRIORITY_TAG
        factor *= unit?.boost ?: 1.0
        return factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Factor dependiente de la consulta.
     */
    fun queryFactor(chunk: Chunk, ctx: BoostContext): Double {
        var factor = 1.0
        if (ctx.unitIds.isNotEmpty() && chunk.unitIds.any { it in ctx.unitIds }) factor *= UNIT_MATCH
        if (ctx.week > 0 && chunk.week == ctx.week) factor *= WEEK_MATCH
        if (ctx.queryTags.isNotEmpty() && chunk.tags.any { Tags.normalize(it) in ctx.queryTags }) factor *= TAG_MATCH
        return factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }
}
```

Y el contexto de boost (datos puros, en `core`):

```kotlin
package kitoru.domain

/**
 * Contexto de una consulta: lo que la pregunta "activó" en la taxonomía.
 *
 * Lo arma la capa de aplicación (a partir de la pregunta del alumno) y lo usa
 * la recuperación para ajustar la puntuación. Es dato puro, sin lógica.
 */
@Serializable
data class BoostContext(
    /** Etiquetas de la cátedra que aparecen en la pregunta (normalizadas). */
    val queryTags: Set<String> = emptySet(),
    /** Ids de las unidades que la pregunta menciona. */
    val unitIds: Set<String> = emptySet(),
    /** Semana del cronograma a la que se refiere la pregunta (0 = ninguna). */
    val week: Int = 0,
    /** Etiquetas que el docente usa para marcar material prioritario. */
    val priorityTags: Set<String> = setOf("examen", "parcial", "final", "importante", "clave"),
)
```

### 5.7 `KnowledgeBase.retrieve` con el ajuste

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt
    /**
     * Recupera los fragmentos más relevantes para una consulta.
     *
     * @param ctx contexto de boost (etiquetas, unidades y semana de la pregunta).
     * @param termWeights peso por término de la consulta (expansión de etiquetas).
     */
    fun retrieve(
        query: String,
        k: Int = cfg.topK,
        ctx: BoostContext = BoostContext(),
        termWeights: Map<String, Double> = emptyMap(),
    ): List<Chunk> {
        val pool = minOf(index.size, maxOf(k * 4, k + 10))
        val candidates = index.search(query, pool, termWeights)
        return candidates
            .map { (chunk, score) -> chunk to score * RetrievalBoost.queryFactor(chunk, ctx) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }   // estable: conserva el orden BM25 en los empates
            .take(k)
            .map { it.first }
    }
```

### 5.8 Qué **no** hacer

| Trampa | Por qué está mal | Qué se hace en su lugar |
|---|---|---|
| Sumar un bonus al score | No reordena nada si el bonus es constante; si es variable, deja de ser comparable entre consultas | Multiplicar |
| Poner el texto de la unidad en el cuerpo indexado | Infla el `tf` de palabras como "unidad" y "tema" en todos los fragmentos | Las etiquetas de la unidad van en `unitPath`, una vez, y el boost hace el trabajo |
| Que el modelo decida el boost | Rompe la regla 3 (determinismo) y el docente pierde el control | Reglas puras en `RetrievalBoost` |
| Filtrar por "material de examen" sin fallback | Si el material de examen no matchea la pregunta, el alumno se queda sin respuesta | El boost sube de rango, no excluye |
| Un boost mayor a 3 | El material marcado termina respondiendo preguntas que no son suyas | Techo duro de 3.0 |

---

## 6. Cómo se leen las unidades en el prompt

### 6.1 La decisión

**Las unidades van en DOS lugares, con propósitos distintos:**

1. **El índice de la cátedra en el prompt del sistema** (estático, cambia poco):
   le da al modelo el **vocabulario** de la materia y le permite razonar sobre la estructura
   ("la unidad 2 trata sobre X", "¿esto es de la unidad 1 o de la 3?").
2. **La ruta de la unidad en el contexto recuperado** (dinámico, cambia por pregunta):
   le da la **atribución exacta** para citar ("esto salió del Tema 2.1") sin que tenga que adivinar.

**No se pasa el árbol completo en cada mensaje de usuario**: el system prompt ya es estático y lo cachean los proveedores;
meterlo en el user message lo paga el alumno en tokens y en latencia en cada turno.

### 6.2 El índice de la cátedra (system prompt)

Se genera con `UnitTree.outline(...)` y lo compone la app:

```
## Contenido de la cátedra
Esta es la estructura de la materia. Usala para ubicar la respuesta y para
nombrar el tema correcto. Si una fuente tiene una ruta entre paréntesis, esa
es su unidad.

- Unidad 1 — Paradigmas (semana 1)
  - Tema 1.1 — Supervisión (semana 1) — 2 material(es)
  - Tema 1.2 — No supervisado (semana 2) — 1 material(es)
- Unidad 2 — Modelos lineales (semana 3)
  - Tema 2.1 — Regresión (semana 3) — 3 material(es)
```

Se **antepone** al prompt del sistema de la cátedra (`Course.systemPrompt`), que el docente puede editar:

```kotlin
// en la capa de aplicación (:ui), al construir el motor
val systemPrompt = buildString {
    append(course.systemPrompt)
    val outline = UnitTree.outline(units, CourseCatalog.materialCountByUnit(materials, units))
    if (outline.isNotBlank()) {
        append("\n\n## Contenido de la cátedra\n")
        append(outline)
    }
}
val engine = RagEngine(cfg, kb, gateway) { systemPrompt }
```

**Costo**: ~30 líneas, ~400 tokens. Se puede acotar con `maxLines` (por defecto 200) y, si la cátedra es enorme, mostrar sólo
los dos primeros niveles.

### 6.3 El contexto recuperado

`RagEngine.prepare` cambia el formato de las fuentes:

```diff
-        val context = if (hits.isEmpty()) "(sin resultados en el material de la cátedra)" else
-            hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }
-                .joinToString("\n\n---\n\n")
+        val context = if (hits.isEmpty()) "(sin resultados en el material de la cátedra)" else
+            hits.mapIndexed { i, c -> "[${i + 1}] (${c.citationLabel})\n${c.text}" }
+                .joinToString("\n\n---\n\n")
```

Ejemplo de bloque de contexto:

```
[1] (Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal)
La regresión lineal modela y = b0 + b1*x1 + ... + bn*xn + e. El estimador de
mínimos cuadrados minimiza la suma de residuos al cuadrado...

[2] (Unidad 2 > Tema 2.1 > Diapositivas 3 > Coeficientes)
El estimador de mínimos cuadrados es (X'X)^-1 X'y...
```

El modelo ve la jerarquía **en el mismo lugar donde ve el texto**: no hay que resolver ids ni buscar una tabla aparte.

### 6.4 El bloque de cronograma (dinámico, sólo cuando hace falta)

Se agrega **únicamente** si la pregunta tiene una referencia temporal detectada (§3.4). Va en el mensaje de usuario, junto
al contexto, porque cambia cada semana:

```
CRONOGRAMA (semana actual: 5 de 16):
- Semana 4 (2026-04-13): Clase 4 — Regresión | Temas: Unidad 2 — Modelos lineales, Tema 2.1 — Regresión
- Semana 5 (2026-04-20): Clase 5 — Validación | Temas: Unidad 2 — Modelos lineales
```

### 6.5 El prompt del sistema resultante

```
{SYSTEM_PROMPT_DE_LA_CATEDRA}          # lo edita el docente

## Contenido de la cátedra
{outline}

{Urgencias del docente, p.ej.: "Respondé siempre en español. Si no encontrás
 la respuesta en el material, decilo."}
```

El `RagEngine` no cambia su firma: el system prompt ya es una lambda `() -> String` que se evalúa en cada pregunta (§ el
docente puede editarlo sin reiniciar). Sólo cambia el formato del contexto.

---

## 7. Código Kotlin final

### 7.1 `core/src/commonMain/kotlin/kitoru/domain/Taxonomy.kt`

Todo el contenido de §2.1 y §2.3 en un solo archivo (`Unit`, `UnitKind`, `UnitTree`), más `HeadingKey` (§7.2).

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

@Serializable
enum class UnitKind(val label: String) {
    UNIDAD("Unidad"),
    TEMA("Tema"),
    APARTADO("Apartado"),
    ;

    companion object {
        fun fromName(name: String): UnitKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

@Serializable
data class Unit(
    val id: String,
    val courseId: String,
    val parentId: String = "",
    val kind: UnitKind = UnitKind.UNIDAD,
    val number: String = "",
    val title: String = "",
    val description: String = "",
    val order: Int = 0,
    val week: Int = 0,
    val visible: Boolean = true,
    val boost: Double = 1.0,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
) {
    val shortLabel: String get() = if (number.isBlank()) kind.label else "${kind.label} $number"
    val fullLabel: String get() = if (title.isBlank()) shortLabel else "$shortLabel — $title"
}

object UnitTree {
    fun byId(units: List<Unit>): Map<String, Unit> = units.associateBy { it.id }

    fun children(units: List<Unit>, parentId: String): List<Unit> =
        units.filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.order }, { it.number }, { it.title }))

    fun roots(units: List<Unit>): List<Unit> = children(units, "")

    fun pathIds(units: List<Unit>, unitId: String): List<String> {
        val byId = byId(units)
        val path = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = unitId
        while (current.isNotBlank() && seen.add(current)) {
            val unit = byId[current] ?: break
            path += current
            current = unit.parentId
        }
        return path.reversed()
    }

    fun pathLabels(units: List<Unit>, unitId: String): List<String> =
        pathIds(units, unitId).mapNotNull { byId(units)[it]?.shortLabel }

    fun pathTitles(units: List<Unit>, unitId: String): List<String> =
        pathIds(units, unitId).mapNotNull { byId(units)[it]?.fullLabel }

    fun code(units: List<Unit>, unitId: String): String =
        pathIds(units, unitId)
            .mapNotNull { byId(units)[it]?.number.takeIf { n -> n.isNotBlank() } }
            .joinToString(".")

    fun descendants(units: List<Unit>, unitId: String): List<String> {
        if (units.none { it.id == unitId }) return emptyList()
        val out = mutableListOf(unitId)
        var frontier = listOf(unitId)
        while (frontier.isNotEmpty()) {
            val fresh = units.filter { it.parentId in frontier }.map { it.id }.filter { it !in out }
            if (fresh.isEmpty()) break
            out += fresh
            frontier = fresh
        }
        return out
    }

    fun search(units: List<Unit>, query: String, limit: Int = 20): List<Unit> {
        val q = HeadingKey.key(query)
        if (q.isBlank()) return emptyList()
        return units.filter { unit ->
            HeadingKey.key(unit.shortLabel).contains(q) ||
                HeadingKey.key(unit.fullLabel).contains(q) ||
                (unit.title.isNotBlank() && HeadingKey.key(unit.title).contains(q))
        }.take(limit)
    }

    fun validate(units: List<Unit>): List<String> {
        val problems = mutableListOf<String>()
        val byId = byId(units)
        for (unit in units) {
            if (unit.parentId.isNotBlank() && unit.parentId !in byId) {
                problems += "«${unit.fullLabel}» apunta a un padre que no existe."
            }
            if (unit.parentId == unit.id) problems += "«${unit.fullLabel}» es su propio padre."
            if (hasCycle(units, unit.id)) problems += "«${unit.fullLabel}» está en un ciclo de padres."
        }
        return problems
    }

    private fun hasCycle(units: List<Unit>, unitId: String): Boolean {
        val byId = byId(units)
        val seen = mutableSetOf<String>()
        var current = byId[unitId]?.parentId ?: return false
        while (current.isNotBlank()) {
            if (!seen.add(current)) return true
            current = byId[current]?.parentId ?: return false
        }
        return false
    }

    fun outline(
        units: List<Unit>,
        materialsByUnit: Map<String, Int> = emptyMap(),
        maxLines: Int = 200,
    ): String {
        val lines = mutableListOf<String>()
        fun walk(parentId: String, depth: Int) {
            if (lines.size >= maxLines) return
            for (unit in children(units, parentId)) {
                if (!unit.visible) continue
                val week = if (unit.week > 0) " (semana ${unit.week})" else ""
                val count = materialsByUnit[unit.id] ?: 0
                val mats = if (count > 0) " — $count material(es)" else " — sin material"
                lines += "${"  ".repeat(depth)}- ${unit.fullLabel}$week$mats"
                walk(unit.id, depth + 1)
            }
        }
        walk("", 0)
        return lines.joinToString("\n")
    }
}
```

### 7.2 `core/src/commonMain/kotlin/kitoru/domain/HeadingKey.kt`

```kotlin
package kitoru.domain

import kitoru.platform.stripDiacritics

/**
 * Comparación de encabezados (Markdown) con etiquetas de la taxonomía.
 *
 * Vive en el dominio porque lo usan tres cosas: la alineación de la cita
 * (backend), la sugerencia de unidad (UI) y la búsqueda de unidades por
 * nombre (dominio).
 */
object HeadingKey {

    /** Normaliza para comparar: minúsculas, sin acentos, sin puntuación. */
    fun normalize(text: String): String =
        stripDiacritics(text.trim().lowercase()).replace(Regex("""[^a-z0-9.]+"""), " ")
            .trim()

    /** Tokens normalizados. */
    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotBlank() }

    /**
     * ¿El encabezado del documento corresponde a esta etiqueta de unidad?
     *
     * "Tema 2.1" matchea con "Tema 2.1", con "2.1" y con "Tema 2.1 Supervisión".
     * "Unidad 2" NO matchea con "2" (número demasiado corto: matchearía media cátedra).
     */
    fun matches(unitLabel: String, heading: String): Boolean {
        val h = normalize(heading)
        val u = normalize(unitLabel)
        if (h.isBlank() || u.isBlank()) return false
        if (h == u) return true
        val number = u.split(' ').lastOrNull().orEmpty()
        if (number.length < 2) return false
        return h.split(' ').contains(number)
    }

    /** ¿El texto contiene la frase completa (por token)? */
    fun containsPhrase(text: String, phrase: String): Boolean {
        val t = tokens(text)
        val p = tokens(phrase)
        if (p.isEmpty()) return false
        if (p.size == 1) return t.contains(p[0])
        for (i in 0..(t.size - p.size)) {
            if (t.subList(i, i + p.size) == p) return true
        }
        return false
    }
}
```

### 7.3 `core/src/commonMain/kotlin/kitoru/domain/Schedule.kt`

`ScheduleKind`, `ScheduleEntry`, `ScheduleResolver`, `TemporalKind`, `TemporalRef`, `TemporalReference` e `IsoDate`
(§3.2, §3.4, §3.5) en dos archivos: `Schedule.kt` y `IsoDate.kt`.

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleKind(val label: String) {
    CLASE("Clase"),
    PRACTICA("Práctica"),
    LAB("Laboratorio"),
    EXAMEN("Examen"),
    LECTURA("Lectura"),
    OTRO("Otro"),
    ;

    companion object {
        fun fromName(name: String): ScheduleKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

@Serializable
data class ScheduleEntry(
    val id: String,
    val courseId: String,
    val week: Int = 1,
    val date: String = "",
    val title: String = "",
    val kind: ScheduleKind = ScheduleKind.CLASE,
    val unitIds: List<String> = emptyList(),
    val materialIds: List<String> = emptyList(),
    val notes: String = "",
    val createdAt: Long = 0L,
) {
    val label: String get() = if (title.isBlank()) "Semana $week" else "Semana $week — $title"
}

enum class TemporalKind { LAST_WEEK, THIS_WEEK, NEXT_WEEK, YESTERDAY, TODAY, WEEK_NUMBER }

data class TemporalRef(val kind: TemporalKind, val week: Int = 0)

object TemporalReference {
    private val NUMEROS = mapOf(
        "uno" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
    )
    private val RE_SEMANA_NUM = Regex("""semana\s+(\d{1,2})\b""")
    private val RE_SEMANA_PALABRA =
        Regex("""semana\s+(uno|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)\b""")

    fun detect(question: String): TemporalRef? {
        val q = HeadingKey.normalize(question).replace('.', ' ')
        if (q.isBlank()) return null
        if (q.contains("semana pasada") || q.contains("semanas pasadas")) {
            return TemporalRef(TemporalKind.LAST_WEEK)
        }
        if (q.contains("esta semana") || q.contains("semana actual")) {
            return TemporalRef(TemporalKind.THIS_WEEK)
        }
        if (q.contains("semana que viene") || q.contains("proxima semana")) {
            return TemporalRef(TemporalKind.NEXT_WEEK)
        }
        RE_SEMANA_NUM.find(q)?.let { m ->
            return TemporalRef(TemporalKind.WEEK_NUMBER, m.groupValues[1].toIntOrNull() ?: 0)
        }
        RE_SEMANA_PALABRA.find(q)?.let { m ->
            return TemporalRef(TemporalKind.WEEK_NUMBER, NUMEROS[m.groupValues[1]] ?: 0)
        }
        if (q.contains("ayer")) return TemporalRef(TemporalKind.YESTERDAY)
        if (q.contains("hoy")) return TemporalRef(TemporalKind.TODAY)
        return null
    }

    fun resolve(ref: TemporalRef, currentWeek: Int): Int = when (ref.kind) {
        TemporalKind.LAST_WEEK -> (currentWeek - 1).coerceAtLeast(0)
        TemporalKind.THIS_WEEK, TemporalKind.TODAY, TemporalKind.YESTERDAY -> currentWeek
        TemporalKind.NEXT_WEEK -> currentWeek + 1
        TemporalKind.WEEK_NUMBER -> ref.week
    }
}
```

### 7.4 `core/src/commonMain/kotlin/kitoru/domain/CourseCatalog.kt`

```kotlin
package kitoru.domain

/**
 * Consultas de catálogo: qué material hay, en qué unidad y en qué semana.
 */
object CourseCatalog {

    fun unitIdsOf(materials: List<Material>, materialId: String): List<String> =
        materials.firstOrNull { it.id == materialId }?.unitIds.orEmpty()

    fun unitPathOf(materials: List<Material>, units: List<Unit>, materialId: String): List<String> {
        val id = unitIdsOf(materials, materialId).firstOrNull() ?: return emptyList()
        return UnitTree.pathLabels(units, id)
    }

    fun primaryUnit(materials: List<Material>, units: List<Unit>, materialId: String): Unit? {
        val id = unitIdsOf(materials, materialId).firstOrNull() ?: return null
        return units.firstOrNull { it.id == id }
    }

    fun materialsOfUnit(materials: List<Material>, units: List<Unit>, unitId: String): List<Material> {
        val ids = UnitTree.descendants(units, unitId).toSet()
        return materials.filter { material -> material.unitIds.any { it in ids } }
    }

    fun unitsWithoutMaterial(materials: List<Material>, units: List<Unit>): List<Unit> {
        val covered = materials.flatMap { it.unitIds }.toSet()
        return units.filter { it.id !in covered }
    }

    fun weekOfMaterial(
        materials: List<Material>,
        units: List<Unit>,
        schedule: List<ScheduleEntry>,
        materialId: String,
    ): Int {
        primaryUnit(materials, units, materialId)?.week?.takeIf { it > 0 }?.let { return it }
        return schedule.firstOrNull { materialId in it.materialIds }?.week ?: 0
    }

    fun materialCountByUnit(materials: List<Material>, units: List<Unit>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (unit in units) {
            val n = materialsOfUnit(materials, units, unit.id).size
            if (n > 0) counts[unit.id] = n
        }
        return counts
    }
}
```

### 7.5 `backend/src/commonMain/kotlin/rag/retrieval/Models.kt`

```kotlin
package rag.retrieval

/**
 * Documento que entra al índice.
 *
 * Los campos de metadatos son opcionales: un documento sin taxonomía (un PDF
 * suelto, un test) se indexa exactamente igual que antes.
 */
data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Id del material en la cátedra ("" si el documento no viene de uno). */
    val materialId: String = "",
    /** Título legible del material. */
    val title: String = "",
    /** Ids de las unidades (el primero es la primaria). */
    val unitIds: List<String> = emptyList(),
    /** Etiquetas de la ruta de unidades: ["Unidad 2", "Tema 2.1"]. */
    val unitPath: List<String> = emptyList(),
    /** Etiquetas libres del material. */
    val tags: List<String> = emptyList(),
    /** Semana del cronograma (0 = ninguna). */
    val week: Int = 0,
    /** Prior estática del fragmento (1.0 = normal). */
    val boost: Double = 1.0,
)

/**
 * Fragmento indexado.
 *
 * El constructor posicional `(source, path, heading, text)` no cambia: los
 * campos nuevos tienen valor por defecto.
 */
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    val materialId: String = "",
    val title: String = "",
    val unitIds: List<String> = emptyList(),
    val unitPath: List<String> = emptyList(),
    val headingPath: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val week: Int = 0,
    val boost: Double = 1.0,
) {
    /** Etiqueta corta y estable: "cátedra: Apuntes de regresión". */
    val label get() = "$source: $path"

    /**
     * Etiqueta de cita, con la jerarquía:
     * "Unidad 2 > Tema 2.1 > Apuntes de regresión > Regresión lineal".
     */
    val citationLabel: String
        get() = buildString {
            for (label in unitPath) {
                append(label).append(" > ")
            }
            append(if (title.isNotBlank()) title else path)
            val section = headingPath.lastOrNull()
            if (!section.isNullOrBlank() && section != title && section != path) {
                append(" > ").append(section)
            }
        }

    /**
     * Texto que se indexa: cuerpo + metadatos.
     *
     * Para un fragmento sin metadatos produce los mismos tokens que
     * "${heading} ${path} ${text}".
     */
    val indexedText: String
        get() = buildString {
            append(unitPath.joinToString(" ")).append(' ')
            append(headingPath.dropLast(1).joinToString(" ")).append(' ')
            append(heading).append(' ')
            append(path).append(' ')
            if (title.isNotBlank() && title != path) append(title).append(' ')
            append(tags.joinToString(" ")).append(' ')
            append(text)
        }
}
```

### 7.6 `backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt`

```kotlin
package rag.retrieval

import kitoru.domain.HeadingKey

/**
 * Parte documentos por encabezados Markdown y luego por tamaño, con solapamiento.
 *
 * Además de trocear, **acumula la jerarquía**: la pila de encabezados del
 * documento se guarda en el fragmento (`headingPath`) y se alinea con la ruta
 * de unidades del material para que la cita no repita "Unidad 2" dos veces.
 */
object Chunker {
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var heading = doc.path.substringAfterLast('/')
        val stack = ArrayDeque<Pair<Int, String>>()   // (nivel, texto)
        val buf = StringBuilder()

        fun flush() {
            val text = buf.toString().trim()
            buf.clear()
            if (text.isBlank()) return
            val headingPath = align(stack.map { it.second }, doc.unitPath)
            var start = 0
            while (start < text.length) {
                var end = minOf(start + size, text.length)
                if (end < text.length) {
                    val nl = text.lastIndexOf('\n', end)
                    if (nl > start + size / 2) end = nl
                }
                val piece = text.substring(start, end).trim()
                if (piece.isNotEmpty()) {
                    out += Chunk(
                        source = doc.source,
                        path = doc.path,
                        heading = heading,
                        text = piece,
                        materialId = doc.materialId,
                        title = doc.title,
                        unitIds = doc.unitIds,
                        unitPath = doc.unitPath,
                        headingPath = headingPath,
                        tags = doc.tags,
                        week = doc.week,
                        boost = doc.boost,
                    )
                }
                if (end >= text.length) break
                start = maxOf(end - overlap, start + 1)
            }
        }

        var inFence = false
        for (line in doc.text.lineSequence()) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (!inFence && line.startsWith("#")) {
                flush()
                val level = line.takeWhile { it == '#' }.length
                val text = line.trimStart('#').trim().ifEmpty { heading }
                while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
                stack.addLast(level to text)
                heading = text
            }
            buf.appendLine(line)
        }
        flush()
        return out
    }

    /**
     * Saca de la pila de encabezados los niveles que ya están en la ruta de
     * unidades del material.
     *
     * El material está en "Unidad 2 > Tema 2.1" y su Markdown empieza con
     * "# Unidad 2" / "## Tema 2.1": esos dos niveles no se repiten en la cita.
     */
    private fun align(headingStack: List<String>, unitPath: List<String>): List<String> {
        if (unitPath.isEmpty() || headingStack.isEmpty()) return headingStack
        var i = 0
        while (i < headingStack.size && i < unitPath.size &&
            HeadingKey.matches(unitPath[i], headingStack[i])
        ) {
            i++
        }
        return headingStack.drop(i)
    }
}
```

### 7.7 `backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt`

```kotlin
package rag.retrieval

import kitoru.domain.CourseCatalog
import kitoru.domain.Material
import kitoru.domain.ScheduleEntry
import kitoru.domain.Unit

/**
 * Fuente que toma los materiales de una cátedra.
 *
 * Con la taxonomía, cada documento lleva los metadatos de su material
 * (unidades, etiquetas, semana, boost) para que el índice y la cita los
 * puedan usar.
 */
class CourseMaterialSource(
    private val materials: List<Material>,
    private val corpusLabel: String = "cátedra",
    private val units: List<Unit> = emptyList(),
    private val schedule: List<ScheduleEntry> = emptyList(),
    private val priorityTags: Set<String> = DEFAULT_PRIORITY_TAGS,
) : DocumentSource {

    override val label: String get() = corpusLabel

    override suspend fun load(): List<Doc> = materials
        .filter { it.visible && it.extractedText.isNotBlank() }
        .map { material -> toDoc(material) }

    private fun toDoc(material: Material): Doc {
        val unit = CourseCatalog.primaryUnit(materials, units, material.id)
        val unitIds = material.unitIds
        val unitPath = unitIds.firstOrNull()
            ?.let { UnitTree.pathLabels(units, it) }
            .orEmpty()
        val week = CourseCatalog.weekOfMaterial(materials, units, schedule, material.id)
        val boost = RetrievalBoost.staticBoost(
            materialBoost = material.boost,
            materialTags = material.tags,
            unit = unit,
            priorityTags = priorityTags,
        )
        return Doc(
            source = corpusLabel,
            path = material.title.ifBlank { material.path },
            text = material.extractedText,
            materialId = material.id,
            title = material.title.ifBlank { material.path },
            unitIds = unitIds,
            unitPath = unitPath,
            tags = material.tags,
            week = week,
            boost = boost,
        )
    }

    companion object {
        /** Etiquetas que marcan un material como prioritario para el boost. */
        val DEFAULT_PRIORITY_TAGS: Set<String> =
            setOf("examen", "parcial", "final", "importante", "clave")
    }
}
```

### 7.8 `backend/src/commonMain/kotlin/rag/retrieval/RetrievalBoost.kt` y `TagVocabulary.kt`

Ver §5.6 y §4.4 (código completo arriba).

### 7.9 `backend/src/commonMain/kotlin/rag/engine/CourseQueryPlanner.kt`

El planificador que une todo: detecta la referencia temporal, las unidades y las
etiquetas de la pregunta, arma el contexto y devuelve la consulta efectiva.

```kotlin
package rag.engine

import kitoru.domain.BoostContext
import kitoru.domain.Course
import kitoru.domain.CourseCatalog
import kitoru.domain.HeadingKey
import kitoru.domain.Material
import kitoru.domain.ScheduleEntry
import kitoru.domain.ScheduleResolver
import kitoru.domain.TemporalReference
import kitoru.domain.Unit
import kitoru.domain.UnitTree
import rag.retrieval.Bm25Index
import rag.retrieval.TagVocabulary

/**
 * Plan de recuperación para una pregunta.
 */
data class QueryPlan(
    /** Consulta que se manda al índice (pregunta + expansión). */
    val query: String,
    /** Peso por término de la consulta (1.0 normal, 0.3 para la expansión). */
    val termWeights: Map<String, Double>,
    /** Contexto de boost. */
    val boost: BoostContext,
    /** Bloque de cronograma para el prompt (vacío si la pregunta no es temporal). */
    val contextBlock: String = "",
    /** Semana referida por la pregunta (0 = ninguna). */
    val week: Int = 0,
)

/**
 * Interpreta la pregunta del alumno a la luz de la taxonomía de la cátedra.
 *
 * Todo determinista: no llama al modelo ni a la red. Recibe `now` por
 * parámetro para que los tests sean reproducibles.
 */
class CourseQueryPlanner(
    private val course: Course,
    private val units: List<Unit>,
    private val materials: List<Material>,
    private val schedule: List<ScheduleEntry>,
) {
    private val vocabulary = TagVocabulary(materials)

    fun plan(question: String, now: Long): QueryPlan {
        // 1. Etiquetas de la cátedra que aparecen en la pregunta.
        val foundTags = vocabulary.tagsIn(question)
        val expanded = vocabulary.expand(question, foundTags)

        // 2. Unidades que menciona la pregunta.
        val mentionedUnits = detectUnits(question)

        // 3. Referencia temporal.
        val currentWeek = ScheduleResolver.currentWeek(course, schedule, now)
        val ref = TemporalReference.detect(question)
        val week = ref?.let { TemporalReference.resolve(it, currentWeek) } ?: 0

        // 4. Consulta efectiva y pesos.
        val expansionText = expanded.joinToString(" ")
        val effectiveQuery = if (expansionText.isBlank()) question else "$question $expansionText"
        val termWeights = buildMap {
            // Los términos de la pregunta valen 1.0.
            for (term in Bm25Index.tokenize(question)) put(term, 1.0)
            // Los de la expansión valen 0.3 y no pisan los de la pregunta.
            for (tag in expanded) {
                for (term in Bm25Index.tokenize(tag)) putIfAbsent(term, 0.3)
            }
        }

        // 5. Bloque de cronograma (sólo si la pregunta es temporal).
        val contextBlock = if (week > 0) {
            val weeks = listOfNotNull(week, currentWeek).distinct().filter { it > 0 }
            val body = ScheduleResolver.render(schedule, weeks, units)
            if (body.isBlank()) "" else "CRONOGRAMA (semana actual: $currentWeek):\n$body"
        } else {
            ""
        }

        return QueryPlan(
            query = effectiveQuery,
            termWeights = termWeights,
            boost = BoostContext(
                queryTags = foundTags,
                unitIds = mentionedUnits,
                week = week,
            ),
            contextBlock = contextBlock,
            week = week,
        )
    }

    /** Ids de las unidades que la pregunta menciona. */
    private fun detectUnits(question: String): Set<String> {
        val found = mutableSetOf<String>()
        for (unit in units) {
            val labels = UnitTree.pathLabels(units, unit.id)
            val hit = labels.any { HeadingKey.containsPhrase(question, it) } ||
                (unit.title.isNotBlank() && HeadingKey.containsPhrase(question, unit.title))
            if (hit) {
                found += unit.id
                // Si menciona un tema, también cuenta la unidad padre.
                UnitTree.pathIds(units, unit.id).dropLast(1).forEach { found += it }
            }
        }
        return found
    }
}
```

> El tokenizador es el mismo que usa el índice (`rag.retrieval.Bm25Index.tokenize`, que ya es `public`), para que los
> pesos por término de la expansión coincidan con los tokens que el índice realmente indexa.

### 7.10 `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` (adiciones)

```kotlin
interface UnitRepository {
    suspend fun listForCourse(courseId: String): List<Unit>
    suspend fun get(id: String): Unit?
    suspend fun save(unit: Unit)
    suspend fun delete(id: String)
}

interface ScheduleRepository {
    suspend fun listForCourse(courseId: String): List<ScheduleEntry>
    suspend fun listForWeek(courseId: String, week: Int): List<ScheduleEntry>
    suspend fun get(id: String): ScheduleEntry?
    suspend fun save(entry: ScheduleEntry)
    suspend fun delete(id: String)
}
```

### 7.11 `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` (adiciones)

```kotlin
class FileUnitRepository(fs: FileSystem) : FileBackedRepository<Unit>(
    fs, "units.json", Unit.serializer(),
), UnitRepository {

    override suspend fun listForCourse(courseId: String): List<Unit> =
        all().filter { it.courseId == courseId }

    override suspend fun get(id: String): Unit? = all().firstOrNull { it.id == id }

    override suspend fun save(unit: Unit) = put(unit)

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: Unit): String = item.id
}

class FileScheduleRepository(fs: FileSystem) : FileBackedRepository<ScheduleEntry>(
    fs, "schedule.json", ScheduleEntry.serializer(),
), ScheduleRepository {

    override suspend fun listForCourse(courseId: String): List<ScheduleEntry> =
        all().filter { it.courseId == courseId }.sortedBy { it.week }

    override suspend fun listForWeek(courseId: String, week: Int): List<ScheduleEntry> =
        all().filter { it.courseId == courseId && it.week == week }.sortedBy { it.date }

    override suspend fun get(id: String): ScheduleEntry? = all().firstOrNull { it.id == id }

    override suspend fun save(entry: ScheduleEntry) = put(entry)

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: ScheduleEntry): String = item.id
}
```

### 7.12 `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` (adición: `SourceRef`)

```kotlin
/**
 * Referencia estructurada a una fuente recuperada.
 *
 * Antes las fuentes eran sólo etiquetas ("cátedra: Apuntes de regresión"). Con
 * la taxonomía hace falta saber **de qué unidad** salió cada fragmento para
 * poder analizarla por unidad y por semana sin parsear strings.
 */
@Serializable
data class SourceRef(
    /** Posición en la lista de fuentes (1-based: el número de la cita [n]). */
    val index: Int = 0,
    val materialId: String = "",
    val materialTitle: String = "",
    /** Ids de las unidades del fragmento (el primero es la primaria). */
    val unitIds: List<String> = emptyList(),
    /** Etiquetas de la ruta: ["Unidad 2", "Tema 2.1"]. */
    val unitPath: List<String> = emptyList(),
    /** Sección dentro del material. */
    val heading: String = "",
    /** Semana del material (0 = ninguna). */
    val week: Int = 0,
    /** Etiqueta de cita: "Unidad 2 > Tema 2.1 > Apuntes de regresión". */
    val label: String = "",
    /** Puntuación de recuperación (0 si no se guardó). */
    val score: Double = 0.0,
)
```

Y en `ChatMessage`:

```diff
 @Serializable
 data class ChatMessage(
     val id: String,
     val threadId: String,
     val role: String,
     val text: String,
     val createdAt: Long = 0L,
     /** Etiquetas de las fuentes recuperadas (corpus: ruta). */
     val sources: List<String> = emptyList(),
+    /** Fuentes recuperadas con su taxonomía (para la analítica por unidad/semana). */
+    val sourceRefs: List<SourceRef> = emptyList(),
     val citation: CitationStats? = null,
     ...
 )
```

### 7.13 `core/src/commonMain/kotlin/kitoru/domain/Analytics.kt` (adiciones)

```kotlin
/**
 * Uso de una semana de la cursada.
 *
 * Responde "¿los alumnos preguntan sobre el material de la semana 3?".
 */
@Serializable
data class WeekUsage(
    val week: Int,
    /** Preguntas de los alumnos atribuidas a esta semana. */
    val questions: Int = 0,
    /** Respuestas del modelo que usaron material de esta semana. */
    val answers: Int = 0,
    /** Materiales distintos consultados. */
    val materials: Int = 0,
    /** Unidades distintas tocadas. */
    val units: Int = 0,
)

/** Uso de una unidad de la cátedra. */
@Serializable
data class UnitUsage(
    val unitId: String,
    /** "Tema 2.1 — Regresión". */
    val label: String = "",
    val week: Int = 0,
    /** Veces que se recuperó material de esta unidad. */
    val uses: Int = 0,
    /** Preguntas atribuidas a esta unidad. */
    val questions: Int = 0,
)
```

Y en `CourseAnalytics`:

```diff
     val topSources: List<SourceUsage> = emptyList(),
+    /** Actividad por semana de la cursada. */
+    val perWeek: List<WeekUsage> = emptyList(),
+    /** Uso por unidad de la taxonomía. */
+    val perUnit: List<UnitUsage> = emptyList(),
     val inactiveStudents: List<String> = emptyList(),
 )
```

### 7.14 `core/src/commonMain/kotlin/kitoru/domain/AnalyticsAnalyzer.kt` (adiciones)

```kotlin
    /**
     * Analiza una cátedra.
     *
     * @param units taxonomía de la cátedra (opcional: sin ella no hay
     *   métricas por unidad).
     * @param schedule cronograma (opcional: sin él no hay atribución por fecha).
     * @param now instante de referencia para las referencias temporales
     *   (0 = no se resuelve: el analizador sigue siendo determinista).
     */
    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        units: List<Unit> = emptyList(),
        schedule: List<ScheduleEntry> = emptyList(),
        now: Long = 0L,
    ): CourseAnalytics {
        ...
        val (perWeek, perUnit) = taxonomyUsage(course, threads, messagesByThread, visible, units, schedule, now)
        return CourseAnalytics(..., perWeek = perWeek, perUnit = perUnit)
    }

    /**
     * Uso por semana y por unidad.
     *
     * Reglas (deterministas):
     *  - Una **respuesta** se atribuye a las semanas de sus fuentes.
     *  - Una **pregunta** se atribuye a la semana que menciona de forma
     *    explícita ("la semana pasada"); si no, a la semana de las fuentes
     *    que usó la respuesta siguiente.
     *  - Una unidad acumula el uso de sus fuentes.
     */
    private fun taxonomyUsage(
        course: Course,
        threads: List<ChatThread>,
        messagesByThread: Map<String, List<ChatMessage>>,
        visible: List<Enrollment>,
        units: List<Unit>,
        schedule: List<ScheduleEntry>,
        now: Long,
    ): Pair<List<WeekUsage>, List<UnitUsage>> {
        val visibleIds = visible.map { it.userId }.toSet()
        val answers = mutableMapOf<Int, Int>()
        val questions = mutableMapOf<Int, Int>()
        val weekMaterials = mutableMapOf<Int, MutableSet<String>>()
        val weekUnits = mutableMapOf<Int, MutableSet<String>>()
        val unitUses = mutableMapOf<String, Int>()
        val unitQuestions = mutableMapOf<String, Int>()

        for (thread in threads) {
            if (thread.authorId !in visibleIds) continue
            var pending: ChatMessage? = null
            for (message in messagesByThread[thread.id].orEmpty()) {
                if (message.isUser) {
                    pending = message
                    continue
                }
                val refs = message.sourceRefs
                val weeks = refs.map { it.week }.filter { it > 0 }.distinct()
                for (week in weeks) answers[week] = (answers[week] ?: 0) + 1
                for (ref in refs) {
                    for (unitId in ref.unitIds) unitUses[unitId] = (unitUses[unitId] ?: 0) + 1
                    if (ref.week > 0) {
                        weekMaterials.getOrPut(ref.week) { mutableSetOf() }.add(ref.materialId)
                        weekUnits.getOrPut(ref.week) { mutableSetOf() }.addAll(ref.unitIds)
                    }
                }
                val question = pending
                if (question != null) {
                    val explicit = explicitWeek(question.text, course, schedule, now)
                    val week = if (explicit > 0) explicit else weeks.minOrNull() ?: 0
                    if (week > 0) {
                        questions[week] = (questions[week] ?: 0) + 1
                        for (ref in refs) {
                            for (unitId in ref.unitIds) {
                                unitQuestions[unitId] = (unitQuestions[unitId] ?: 0) + 1
                            }
                        }
                    }
                    pending = null
                }
            }
        }

        val perWeek = (answers.keys + questions.keys).sorted().map { week ->
            WeekUsage(
                week = week,
                questions = questions[week] ?: 0,
                answers = answers[week] ?: 0,
                materials = weekMaterials[week]?.size ?: 0,
                units = weekUnits[week]?.size ?: 0,
            )
        }
        val perUnit = unitUses.keys.sorted().map { unitId ->
            val unit = units.firstOrNull { it.id == unitId }
            UnitUsage(
                unitId = unitId,
                label = unit?.fullLabel ?: unitId,
                week = unit?.week ?: 0,
                uses = unitUses[unitId] ?: 0,
                questions = unitQuestions[unitId] ?: 0,
            )
        }
        return perWeek to perUnit
    }

    /** Semana que la pregunta menciona de forma explícita (0 si no menciona). */
    private fun explicitWeek(text: String, course: Course, schedule: List<ScheduleEntry>, now: Long): Int {
        if (now <= 0L) return 0
        val ref = TemporalReference.detect(text) ?: return 0
        val current = ScheduleResolver.currentWeek(course, schedule, now)
        return TemporalReference.resolve(ref, current)
    }
```

### 7.15 Guardar las fuentes estructuradas

En el punto donde la app arma el mensaje del asistente:

```kotlin
// de los hits del RAG
val sourceRefs = hits.mapIndexed { i, chunk ->
    SourceRef(
        index = i + 1,
        materialId = chunk.materialId,
        materialTitle = chunk.title.ifBlank { chunk.path },
        unitIds = chunk.unitIds,
        unitPath = chunk.unitPath,
        heading = chunk.headingPath.lastOrNull() ?: chunk.heading,
        week = chunk.week,
        label = chunk.citationLabel,
        score = score,
    )
}
message = message.copy(sources = hits.map { it.label }, sourceRefs = sourceRefs)
```

`ChatMessage.sources` sigue guardando las etiquetas cortas (`"cátedra: Apuntes de regresión"`), así que los datos
existentes siguen siendo válidos y `topSources` de la analítica no cambia.

---

## 8. Migración de datos existentes

No hay migración: todo es **aditivo** y con valores por defecto.

| Qué | Cómo |
|---|---|
| `Material` sin `unitIds` | `unitIds = []` → el material no tiene ruta de unidad; la cita es "Título > Sección". |
| `Material` sin `boost` | `1.0` → sin efecto en el score. |
| `Course` sin `startDate` | `""` → la semana actual se deduce de las fechas del cronograma; si no hay, `0`. |
| `Chunk` viejo (serializado en `messages.json` como `sources`) | `ChatMessage.sourceRefs = []` → la analítica por unidad muestra 0; el resto sigue igual. |
| `units.json` / `schedule.json` | No existen: se crean vacíos la primera vez (`JsonListStore` devuelve `emptyList()` si el archivo no está). |
| Cátedra que ya tenía "Unidad 1" en el nombre del archivo | Nada: el nombre del archivo sigue siendo `path` y se sigue indexando. |

**Reindexado:** cambiar la taxonomía (unidades, tags, `boost`, `week`) cambia lo que el índice contiene ⇒ hay que llamar a
`KnowledgeBase.sync()` otra vez. La app debe reindexar cuando cambian los materiales **y** cuando cambia la taxonomía.
(El `KnowledgeBase.sync()` ya está preparado: lee `sources`, trocea y reconstruye el índice.)

---

## 9. Casos borde

| Caso | Qué pasa |
|---|---|
| Material sin unidades | `unitPath` vacío → la cita es "Título > Sección". El boost de unidad no aplica. |
| Material asignado a una unidad que después se borra | `unitIds` apunta a un id inexistente → `UnitTree.pathLabels` devuelve `[]` → la cita degrada a "Título > Sección". `UnitTree.validate` avisa al docente. |
| Ciclo de padres (`a.parent = b`, `b.parent = a`) | `pathIds` corta con el set de visitados; `validate` lo reporta. |
| Etiqueta duplicada ("Examen" y "examen") | `Tags.normalize` las unifica; `dedup` las deja una sola vez. |
| Etiqueta con acentos ("Exámen") | `Tags.key` la normaliza; el índice tokeniza sin acentos (`stripDiacritics`). |
| Material con texto vacío | `CourseMaterialSource` ya lo filtra (`extractedText.isNotBlank()`). |
| Pregunta sin ninguna coincidencia | `search` devuelve `[]` → el RAG manda "(sin resultados en el material de la cátedra)". Sin cambios. |
| Pregunta temporal sin cronograma | `currentWeek = 0` → `week = 0` → no hay boost por semana ni bloque de cronograma; el modelo responde con lo que encuentre. |
| `now = 0` en el analizador | No se resuelven referencias temporales (el analizador es puro y testeable). |
| Chunk sin encabezados | `headingPath` vacío → `citationLabel` no agrega la sección. |
| Material con 5000 unidades... no | El `outline` se corta en `maxLines` (200). |
| Muchos hilos con el mismo nombre | Los ids son únicos; las etiquetas son sólo de presentación. |
| Un alumno pregunta en un hilo con tags | Las etiquetas del hilo **no** entran al RAG (no son del corpus). |

---

## 10. Checklist de implementación

**`core` (dominio)**
1. `Taxonomy.kt`: `Unit`, `UnitKind`, `UnitTree`.
2. `HeadingKey.kt`: `HeadingKey`.
3. `Schedule.kt`: `ScheduleEntry`, `ScheduleKind`, `ScheduleResolver`, `TemporalKind`, `TemporalRef`, `TemporalReference`.
4. `IsoDate.kt`: `IsoDate`.
5. `Tags.kt`: `Tags`.
6. `BoostContext.kt`: `BoostContext`.
7. `CourseCatalog.kt`: `CourseCatalog`, `UnitSuggester`.
8. `Course.kt`: `Material.unitIds`, `Material.boost`, `Course.startDate`.
9. `Chat.kt`: `SourceRef`, `ChatMessage.sourceRefs`.
10. `Analytics.kt`: `WeekUsage`, `UnitUsage`, `CourseAnalytics.perWeek/perUnit`.
11. `AnalyticsAnalyzer.kt`: `analyze(...)` con `units`, `schedule`, `now`; `taxonomyUsage`.
12. `Repositories.kt` / `FileRepositories.kt`: `UnitRepository`, `ScheduleRepository` + implementaciones `File*`.

**`backend` (RAG)**
13. `retrieval/Models.kt`: `Doc` y `Chunk` con metadatos; `citationLabel`; `indexedText`.
14. `retrieval/Chunker.kt`: pila de encabezados + `align`.
15. `retrieval/Bm25Index.kt`: `indexedText`, `termWeights`, `chunks[i].boost`.
16. `retrieval/RetrievalBoost.kt`: `staticBoost`, `queryFactor`.
17. `retrieval/TagVocabulary.kt`: `tagsIn`, `expand`.
18. `retrieval/DocumentSource.kt`: `CourseMaterialSource` con taxonomía.
19. `retrieval/KnowledgeBase.kt`: `retrieve(query, k, ctx, termWeights)` con re-ordenación.
20. `engine/CourseQueryPlanner.kt`: `QueryPlan` + `plan(question, now)`.
21. `engine/RagEngine.kt`: contexto con `citationLabel` + bloque de cronograma.

**Tests nuevos (`:backend/src/desktopTest`)**
22. `ChunkerTest`: la pila de encabezados y la alineación con la ruta de unidades.
23. `Bm25IndexTest`: el boost estático y los `termWeights` (y que sin ellos los scores no cambian).
24. `RetrievalBoostTest`: los factores y los topes.
25. `TagVocabularyTest`: detección y expansión.
26. `CourseQueryPlannerTest`: "la semana pasada" → semana correcta; "semana 3" → semana 3.

**Tests nuevos (`:core/src/commonTest`)**
27. `UnitTreeTest`: rutas, código, descendientes, ciclos, `validate`.
28. `ScheduleResolverTest`: `currentWeek` con `startDate`, con fechas de entradas y sin ninguna.
29. `TemporalReferenceTest`: detección de cada forma.
30. `IsoDateTest`: ida y vuelta `parse`/`format`.
31. `AnalyticsAnalyzerTest`: `perWeek` y `perUnit` con mensajes de ejemplo.

---

## Anexo: resumen de los archivos tocados

```
core/src/commonMain/kotlin/kitoru/domain/
  Taxonomy.kt            (nuevo)  Unit, UnitKind, UnitTree
  HeadingKey.kt          (nuevo)  HeadingKey
  Schedule.kt            (nuevo)  ScheduleEntry, ScheduleKind, ScheduleResolver, Temporal*
  IsoDate.kt             (nuevo)  IsoDate
  Tags.kt                (nuevo)  Tags
  BoostContext.kt        (nuevo)  BoostContext (datos puros del contexto de consulta)
  CourseCatalog.kt       (nuevo)  CourseCatalog, UnitSuggester
  Course.kt              (mod)    Material.unitIds, Material.boost, Course.startDate
  Chat.kt                (mod)    SourceRef, ChatMessage.sourceRefs
  Analytics.kt           (mod)    WeekUsage, UnitUsage, CourseAnalytics.perWeek/perUnit
  AnalyticsAnalyzer.kt   (mod)    analyze(units, schedule, now), taxonomyUsage
  Repositories.kt        (mod)    UnitRepository, ScheduleRepository
  FileRepositories.kt    (mod)    FileUnitRepository, FileScheduleRepository

backend/src/commonMain/kotlin/rag/
  retrieval/Models.kt          (mod)  Doc, Chunk con metadatos; citationLabel; indexedText
  retrieval/Chunker.kt         (mod)  pila de encabezados + alineación
  retrieval/Bm25Index.kt        (mod)  indexedText, termWeights, boost
  retrieval/RetrievalBoost.kt  (nuevo) staticBoost, queryFactor
  retrieval/TagVocabulary.kt    (nuevo) tagsIn, expand
  retrieval/DocumentSource.kt   (mod)  CourseMaterialSource con taxonomía
  retrieval/KnowledgeBase.kt    (mod)  retrieve(query, k, ctx, termWeights)
  engine/CourseQueryPlanner.kt  (nuevo) QueryPlan, plan()
  engine/RagEngine.kt           (mod)  contexto con citationLabel + cronograma
```
