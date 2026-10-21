# 04 · Materiales de la cátedra (vista del alumno)

> Pantalla del **entorno alumno** donde el alumno recorre el material que subió
> la cátedra, lo **busca** dentro, y **pregunta a la IA** sobre un fragmento
> concreto.
>
> Es la pantalla que hace que el RAG sea usable: sin ella, el chat es una caja
> negra que responde cosas y el alumno no puede verificar nada.

---

## 0. Resumen en cinco líneas

1. La pantalla tiene **dos estados**: *navegar* (lista de materiales con tipo,
   título, tamaño, etiquetas y estado de extracción) y *buscar* (resultados
   full-text con los términos resaltados). En escritorio se resuelve con un
   panel de previsualización al lado; en Android, con navegación pila.
2. **Buscar es la función principal**: búsqueda BM25 sobre los *mismos*
   fragmentos que indexa el RAG (`KnowledgeBase` → `Bm25Index`), con los
   términos **resaltados**, el fragmento anclado a un offset dentro del texto
   del material, y un botón **"Preguntar a la IA sobre esto"** que abre el chat
   con ese fragmento **fijado**.
3. El fragmento seleccionado entra al prompt como **contexto priorizado**: va
   **primero** en el bloque `CONTEXTO` (queda numerado `[1]`, así que el
   `CitationVerifier` sigue funcionando sin cambios), el resto se sigue
   recuperando por BM25 con el presupuesto que queda, y el mensaje del usuario
   lleva una nota que le dice al modelo que responda sobre ese fragmento.
4. **Los materiales ocultos no existen para el alumno**: no aparecen en la
   lista, no se buscan, no se descargan **y el RAG tampoco los usa**
   (`CourseMaterialSource` ya filtra por `visible`).
5. Cada respuesta guarda **de qué material salió** (`ChatMessage.sourceRefs`), y
   el camino inverso —"del material, ver en qué respuestas mías se usó"— sólo
   muestra **las conversaciones del propio alumno**, nunca las de otros.

---

## 1. Qué ve el alumno y qué no

### 1.1 Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| ¿Materiales ocultos en la lista? | **No** | `Material.visible == false` significa "para el alumno no existe". |
| ¿El RAG los usa? | **No** | Si un material está oculto, el modelo no puede citarlo ni usarlo. `CourseMaterialSource` ya filtra `it.visible && it.extractedText.isNotBlank()`. |
| ¿El texto extraído o el archivo original manda? | **El texto extraído** es la fuente de verdad de la pantalla | Es lo que el RAG ve. Si el alumno lee otra cosa, la IA y él no están mirando lo mismo. El original es un atajo ("abrir con la app de siempre"). |
| ¿Un índice de búsqueda aparte? | **No**: el mismo `Bm25Index` del chat | Un solo índice, una sola verdad. Si divergen, el alumno busca una cosa y la IA responde otra. |
| ¿Qué se le inyecta al modelo al preguntar sobre un fragmento? | **Contexto priorizado** (el fragmento va primero, como `[1]`) | Es la única forma de que la cita `[1]` siga significando "este pasaje" y de que el verificador de citas no se rompa. |
| ¿Descargar o abrir? | Escritorio: app por defecto. Android: `FileProvider` + SAF | En Android no se puede abrir un archivo de `filesDir` con otra app sin exponerlo con un `content://`. |
| ¿Ver en qué respuestas se usó el material? | **Sí**, y sólo las propias | Es el cierre del círculo: el alumno verifica el material que la IA le mostró. Ver las de otros alumnos violaría la privacidad entre ellos. |
| ¿Unidades / temas? | Hoy son **`Material.tags`** | El dominio no tiene entidad `Unit` (ver `teacher/04-prompt.md` §4.4). Los tags cumplen el papel y la pantalla queda lista para migrar. |
| ¿Se puede editar el material? | **No** | El material es del docente. El alumno lee, busca, pregunta y descarga. |

### 1.2 El modelo de datos: lo que ya existe y lo que falta

| Dato | Dónde | Estado |
|---|---|---|
| Texto del material | `Material.extractedText` | ✅ listo (es lo que indexa el RAG) |
| Etiquetas / temas | `Material.tags` | ✅ listo |
| Aviso de extracción | `Material.warning` (string libre) | ✅ existe, pero hay que derivar un **estado** (§7) |
| Visibilidad | `Material.visible` | ✅ listo |
| Ruta del archivo original | `Material.path` (relativa a la carpeta de la cátedra) | ✅ listo |
| Tipo | `MaterialKind` (`DOCUMENT`, `SLIDES`, `TRANSCRIPT`, `LINK`, `OTHER`) | ✅ listo |
| **A qué material pertenece un fragmento** | — | ❌ falta: `Chunk` no tiene el id del material (`CourseMaterialSource` pone `path = material.title` y nada más) |
| **Dónde está el fragmento dentro del material** | — | ❌ falta: `Chunker.split` tira los offsets |
| **De qué material salió una respuesta** | `ChatMessage.sources: List<String>` (labels) | ⚠️ parcial: el label es `"cátedra: <título>"`; sirve para mostrar, no para unir con ids |
| **Cuántos fragmentos aporta un material** | — | ❌ falta: se deriva del índice |

### 1.3 Los agregados que hay que agregar (cambios mínimos y aditivos)

**a) `Chunk` y `Doc` learn a ubicar el fragmento.**

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Id del material del que sale el documento ("" si no aplica). */
    val ref: String = "",
)

data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    /** Id del material (id de `kitoru.domain.Material`). */
    val ref: String = "",
    /** Offset del fragmento dentro de `material.extractedText` (-1 si no se sabe). */
    val start: Int = -1,
    val end: Int = -1,
) {
    val label get() = "$source: $path"
}
```

Los parámetros nuevos **tienen default**, así que los `Chunk("Manual", path, heading, text)`
de los tests y de `Chunker` siguen compilando sin tocarlos.

**b) `CourseMaterialSource` pasa el id del material.**

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt
override suspend fun load(): List<Doc> = materials
    .filter { it.visible && it.extractedText.isNotBlank() }
    .map { material ->
        Doc(
            source = corpusLabel,
            path = material.title.ifBlank { material.path },
            text = material.extractedText,
            ref = material.id,          // ← nuevo: el material del que sale
        )
    }
```

El `label` del fragmento **no cambia** (`"cátedra: Unidad 1"`): es lo que el
modelo ve en el `CONTEXTO` y lo que el `CitationVerifier` muestra. El `ref` es
para la app.

**c) `ChatMessage` guarda la referencia completa, no sólo el label.**

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (agregar)
@Serializable
data class MaterialSourceRef(
    /** Id del material (`Material.id`). */
    val materialId: String,
    /** Título del material (copia: el material puede cambiar después). */
    val title: String = "",
    /** Encabezado del fragmento dentro del material ("# Conceptos clave"). */
    val heading: String = "",
    /** Offset del fragmento dentro de `material.extractedText`. */
    val start: Int = -1,
    val end: Int = -1,
)
```

y en `ChatMessage`:

```kotlin
/** Fuentes con las que se armó la respuesta (para poder volver al material). */
val sourceRefs: List<MaterialSourceRef> = emptyList(),
```

`ChatMessage.sources` (los labels) **se conserva**: lo usa
`AnalyticsAnalyzer` para `SourceUsage(label, times)` y no se toca nada de la
analítica.

**d) El chunker tiene que conservar los offsets.**

Hoy `Chunker.split` arma los fragmentos y **tira la posición**: un `Chunk` es
sólo texto. Sin offsets no hay "ver en el material" (no se puede hacer scroll
al pasaje) ni "este fragmento se usó en 2 respuestas" (no se puede cruzar con
el `start` del `sourceRef`).

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt (modificar)
object Chunker {

    /** Como antes, pero cada fragmento trae su rango en `doc.text`. */
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> =
        splitWithOffsets(doc, size, overlap)

    /**
     * Trocea el documento conservando el rango de cada fragmento dentro de
     * `doc.text`.
     *
     * Los offsets son del **texto extraído** (`Material.extractedText`), no
     * del archivo original: son los únicos que el alumno puede ver.
     */
    fun splitWithOffsets(doc: Doc, size: Int, overlap: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var heading = doc.path.substringAfterLast('/')
        var inFence = false
        val buf = StringBuilder()
        // Offset absoluto del primer carácter que hay en `buf`.
        var bufStart = 0
        // Offset del cursor dentro de `doc.text`.
        var cursor = 0

        fun flush() {
            val raw = buf.toString()
            if (raw.isBlank()) { buf.clear(); return }
            // `flush` hace `trim()`: el texto puede empezar con espacios.
            val lead = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val text = raw.trim()
            val base = bufStart + lead          // ← offset real del texto troceado
            var start = 0
            while (start < text.length) {
                var end = minOf(start + size, text.length)
                if (end < text.length) {
                    val nl = text.lastIndexOf('\n', end)
                    if (nl > start + size / 2) end = nl
                }
                val piece = text.substring(start, end).trim()
                if (piece.isNotEmpty()) {
                    // El `trim()` final puede volver a mover el inicio: se recalcula.
                    val pieceStart = base + start + (text.substring(start, end).indexOfFirst { !it.isWhitespace() })
                    out += Chunk(
                        source = doc.source,
                        path = doc.path,
                        heading = heading,
                        text = piece,
                        ref = doc.ref,
                        start = pieceStart,
                        end = pieceStart + piece.length,
                    )
                }
                if (end >= text.length) break
                start = maxOf(end - overlap, start + 1)
            }
            buf.clear()
        }

        for (line in doc.text.lineSequence()) {
            if (buf.isEmpty()) bufStart = cursor
            if (!inFence && line.startsWith("#")) {
                flush()
                heading = line.trimStart('#').trim().ifEmpty { heading }
            }
            buf.appendLine(line)
            cursor += line.length + 1      // +1 del '\n' que agrega appendLine
        }
        flush()
        return out
    }
}
```

Dos detalles que hay que tener presentes:

1. El buffer se arma con `appendLine`, así que **el texto del buffer es igual
   al del documento** (salvo el salto de línea final, que agrega uno de más:
   el `end` del último fragmento puede quedar 1 carácter corrido — sin
   consecuencias, porque el resaltado se calcula sobre el texto real).
2. `flush()` hace `trim()`, así que el offset base hay que **recalcularlo**
   después del `trim`. Es el error más fácil de cometer: sin esto, el scroll
   "aterriza" un párrafo antes o después.

---

## 2. Layout

### 2.1 Escritorio: lista + panel de previsualización

```
┌─ Material · IA-2026-1 · Inteligencia Artificial ──────────────────────────────────────┐
│ [ 🔍 Buscar en el material…                         ]  [Tipo ▾] [Etiquetas ▾] [Ordenar ▾]│
│ Índice: 12 documentos · 318 fragmentos · actualizado hace 2 min          [Reindexar]│
├──────────────────────────────────┬──────────────────────────────────────────────────────┤
│ MATERIALES (12)                  │ Unidad 1 — Búsqueda heurística                       │
│ ┌──────────────────────────────┐ │ ──────────────────────────────────────────────────  │
│ │ 📄 Unidad 1 — Búsqueda heur.│ │ [Texto] [Fragmentos (24)] [Original]        [⋮ ⋮ ⋮] │
│ │    Documento · 1,2 MB · 24   │ │ ┌─────────────────────────────────────────────────┐ │
│ │    fragmentos · 12/10/2026   │ │ │ # Búsqueda heurística                          │ │
│ │    #Unidad-1 #Parcial-1      │ │ │                                                 │ │
│ └──────────────────────────────┘ │ │ Una función de evaluación heurística…            │ │
│ ┌──────────────────────────────┐ │ │ …el texto del material, con las coincidencias     │ │
│ │ 📊 Diapositivas — Clase 3    │ │ │ resaltadas cuando viene de una búsqueda…        │ │
│ │    Diapositivas · 4,1 MB · 61 │ │ │                                                 │ │
│ │    fragmentos                │ │ └─────────────────────────────────────────────────┘ │
│ │    ⚠ texto parcial           │ │ 3 coincidencias                                       │
│ └──────────────────────────────┘ │ [ ❓ Preguntar a la IA sobre esto ] [ ⬇ Abrir ] [ 🕘 ]│
│ …                                │                                                      │
└──────────────────────────────────┴──────────────────────────────────────────────────────┘
```

- **Panel izquierdo (320–420 px)**: lista de materiales + barra de filtros.
- **Panel derecho**: detalle del material seleccionado (con previsualización del
  texto) o, en modo búsqueda, **los resultados de la búsqueda**.
- **Barra superior**: el buscador (siempre visible), los filtros y el estado
  del índice.
- El selector de material y el buscador **comparten el panel derecho**: si hay
  resultados de búsqueda, el panel derecho muestra los resultados; al tocar
  "Ver en el material", vuelve al detalle con el fragmento anclado.

En pantallas angostas (< 900 px) el detalle pasa a ser una pantalla completa
(superposición), como en la versión Android.

### 2.2 Android: navegación pila

```
MaterialsScreen (lista)
   └─ tap en un material  →  MaterialDetailScreen(materialId)
                                ├─ "Fragmentos (24)"  →  MaterialFragmentsScreen
                                ├─ "Original"          →  intent SAF / visor
                                ├─ "Preguntar"         →  ChatScreen (pila) con contexto
                                └─ "Usado en…"         →  MaterialUsageScreen
```

- El buscador vive **en la pantalla de lista** (arriba, bajo la barra de la app)
  y, al escribir, la lista se reemplaza por los resultados.
- La pantalla de detalle tiene su propio buscador con alcance "en este material".
- El botón atrás del sistema vuelve a la lista; desde el chat, vuelve al detalle.

### 2.3 La ficha de un material en la lista

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialListItem.kt
@Composable
fun MaterialListItem(
    material: Material,
    fragmentCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Contenido, en este orden:

| Elemento | Origen | Regla |
|---|---|---|
| Ícono del tipo | `MaterialKind` → `DOCUMENT` 📄, `SLIDES` 📊, `TRANSCRIPT` 📝, `LINK` 🔗, `OTHER` 📦 | El ícono va antes del título. |
| Título | `material.title` | 2 líneas como máximo, con elipsis. |
| Subtítulo | `material.kind.label` · tamaño · nº de fragmentos · fecha | Tamaño con `formatBytes` (1,2 MB). Fecha corta (`12/10/2026`). |
| Etiquetas | `material.tags` | Hasta 3 chips + "��N". |
| Estado | `material.extractionStatus()` (§7) | Sólo si no es `OK`: chip ámbar "texto parcial" o rojo "sin texto". |

El "nº de fragmentos" se calcula del índice (cuántos chunks tienen
`ref == material.id`); es información real, no un adorno: le dice al alumno si
el material es buscable y qué tan denso es.

### 2.4 La vista de detalle: tres pestañas

| Pestaña | Qué muestra | Cuándo sirve |
|---|---|---|
| **Texto** | El texto extraído, en bloques (párrafos), con las coincidencias resaltadas y con el scroll al fragmento. | Es lo que el RAG lee. Es la vista por defecto. |
| **Fragmentos** | La lista de los fragmentos (chunks) del material, con su encabezado y su largo, en el orden en que están en el índice. | Para entender cómo el material se le da al modelo. Deja ver los solapamientos (`chunkOverlap = 150`). |
| **Original** | Si el archivo existe en este dispositivo: botón para abrirlo con la app por defecto; si no existe, un aviso claro. | Para leer el PDF con la app de siempre, con las figuras. |

Encabezado del detalle (siempre visible arriba del panel derecho):

```
Unidad 1 — Búsqueda heurística
Documento · 1,2 MB · 24 fragmentos · subido el 12/10/2026 por Ana Pérez
#Unidad-1  #Parcial-1
```

### 2.5 Estados de la pantalla

| Estado | Qué se ve |
|---|---|
| Cargando materiales | Skeleton de la lista + "Indexando el material de la cátedra…" (el `sync` ya reporta por `onStatus`). |
| Cátedra sin material | Estado vacío: "Esta cátedra todavía no tiene material. Cuando el docente suba material, va a aparecer acá." + el atajo al chat. |
| Sin resultados de búsqueda | Estado vacío con **sugerencias concretas** (§3.7), no un "no hay resultados" seco. |
| Error de índice | Banner ámbar: "No se pudo indexar el material: <motivo>" + botón "Reintentar". El listado de materiales **sigue funcionando** (el texto extraído se lee igual). |

---

## 3. Buscar en el material — el núcleo de la pantalla

### 3.1 Dos búsquedas distintas, en dos lugares distintos

| Búsqueda | Dónde | Cómo funciona | Costo |
|---|---|---|---|
| **Filtro de lista** (título, etiquetas, tipo) | La barra de filtros de la lista | `material.title.contains(q, ignoreCase = true) || material.tags.any { it.contains(q, true) }` | Instantáneo, sin índice. |
| **Buscar en el material** (full-text) | La caja grande de arriba | BM25 sobre los fragmentos del índice (§3.3) | ~10 ms para 300 fragmentos. |

No se mezclan: el filtro de lista filtra **materiales**, la búsqueda full-text
devuelve **fragmentos**. El alumno que busca "backpropagation" quiere el
*pasaje*, no el archivo que lo contiene.

### 3.2 Qué significa lo que el alumno escribe

**El texto que escribe es las dos cosas a la vez, y el diseño las separa:**

1. Como **consulta de recuperación** → se tokeniza y se rankea con BM25.
2. Como **pregunta para el modelo** → viaja **tal cual**, sin reescribir, al
   chat cuando el alumno aprieta "Preguntar a la IA sobre esto".

La consecuencia de que BM25 sea un modelo de bolsa de palabras: **no importa si
el alumno escribe "backpropagation" o "¿cómo se hace la retropropagación?"**.
En los dos casos el motor ve una bolsa de términos y rankea. Lo que hay que
arreglar es el *preprocesamiento*, no el modelo:

- **`Bm25Index.tokenize` ya normaliza**: minúsculas, parte camelCase
  (`llamaServer` → `llama`, `server`), saca diacríticos (`definicion`),
  saca palabras vacías (`el`, `de`, `que`, `se`, `como`…) y descarta las
  palabras de **una letra**.
- **`"¿Cómo se calcula el gradiente?"`** → `["calcula", "gradiente"]`. Las
  palabras de la pregunta desaparecen y quedan los términos con contenido.
- **`"backpropagation"`** → `["backpropagation"]`. Funciona igual.

El diseño del algoritmo cubre los tres casos que un índice de bolsa de palabras
no cubre solo:

| Caso | Qué pasa | Qué hace el algoritmo |
|---|---|---|
| Pregunta en lenguaje natural | Faltan términos útiles y sobran stopwords | El tokenizador las saca (§3.3 paso 1). |
| Morfología del español (`gradiente` / `gradientes` / `cálculo` / `calcular`) | El término no está tal cual en el índice | **Resolución por prefijo** contra el vocabulario del índice (§3.3 paso 3). |
| Término de una letra (`C`, `R`, `K`) | `tokenize` los descarta (`it.length > 1`) | **Barrido por subcadena** sobre los textos (§3.3 paso 6). |

> Nota sobre el último caso: `Bm25Index.tokenize` **descarta los tokens de una
> letra**, y el índice se construye con esa misma función. Por eso "C" o "R"
> (lenguaje C, lenguaje R) no se pueden recuperar por BM25: no están en el
> índice. Cambiar el tokenizador del índice afectaría al RAG entero (y los
> tests `Bm25IndexTest` lo fijan explícitamente: *"la tokenizacion ignora las
> palabras de una letra"*). La búsqueda del alumno lo resuelve **por fuera**,
> con un barrido por subcadena, sin tocar el índice.

### 3.3 El algoritmo, paso a paso

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/MaterialSearch.kt
package rag.retrieval

/**
 * Búsqueda de material para la pantalla del alumno.
 *
 * No es el RAG: el RAG recupera `topK` fragmentos para meterlos en el prompt.
 * Esto es un buscador: devuelve muchos resultados, con los términos que
 * casaron, y resortea por material para que un PDF de 300 páginas no tape el
 * resto.
 */
object MaterialSearch {

    /** Máximo de resultados que se devuelven. */
    const val MAX_RESULTS = 50

    /** Máximo de fragmentos del mismo material en los resultados. */
    const val MAX_PER_MATERIAL = 5

    /** Longitud mínima del prefijo con el que se resuelve un término. */
    const val PREFIX_MIN = 5

    /** Tope de candidatos por prefijo (evita explosiones: "calc" → 40 palabras). */
    const val MAX_PREFIX_CANDIDATES = 20

    fun search(
        kb: KnowledgeBase,
        query: String,
        scope: SearchScope = SearchScope.All,
        limit: Int = MAX_RESULTS,
    ): SearchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return SearchOutcome.EmptyQuery

        // 1. Términos normalizados (sin acentos, sin stopwords, sin 1 letra).
        val terms = Bm25Index.tokenize(q)
        if (terms.isEmpty()) {
            // 2. Si no quedó nada (query = "C", "???"), se usan los tokens crudos.
            val raw = rawTokens(q)
            if (raw.isEmpty()) {
                return SearchOutcome.NoResults(q, "Escribí al menos una palabra.")
            }
            return scanBySubstring(kb, raw, scope, limit)
        }

        // 3. Resolver los términos contra el vocabulario del índice:
        //    exacto, o por prefijo si la forma no está ("gradiente" → "gradientes").
        val resolved = resolveTerms(kb, terms)

        // 4. BM25 con los términos resueltos.
        var hits = kb.searchTerms(resolved.all, limit * 4)
            .filter { scope.matches(it.chunk) }
        var relaxed = false

        // 5. Si no hubo resultados, se relaja: sólo los 3 términos más
        //    discriminantes (los más raros). Cubre "¿qué es una función de
        //    evaluación?" cuando el material dice "función de heurística".
        if (hits.isEmpty() && resolved.all.size > 1) {
            hits = kb.searchTerms(resolved.mostSelective(kb, 3), limit * 4)
                .filter { scope.matches(it.chunk) }
            relaxed = hits.isNotEmpty()
        }

        // 6. Si sigue sin resultados, barrido por subcadena (términos de una
        //    letra, o formas que el prefijo no alcanzó).
        if (hits.isEmpty()) {
            return scanBySubstring(kb, terms, scope, limit)
        }

        // 7. Tope por material: un PDF largo no monopoliza los resultados.
        hits = capPerMaterial(hits, MAX_PER_MATERIAL)

        // 8. Límite final.
        hits = hits.take(limit)

        if (hits.isEmpty()) {
            return SearchOutcome.NoResults(q, "No encontré «$q» en el material de la cátedra.")
        }
        return SearchOutcome.Results(hits, resolved.all, relaxed)
    }
}
```

#### Paso 3 en detalle: resolver un término contra el vocabulario

```kotlin
/** Cómo se resolvió cada término de la consulta. */
data class ResolvedTerms(
    /** Términos que están tal cual en el índice. */
    val exact: List<String> = emptyList(),
    /** Expansiones por prefijo ("gradiente" → "gradiente", "gradientes"). */
    val prefixed: List<String> = emptyList(),
    /** Términos que no se encontraron de ninguna forma. */
    val missing: List<String> = emptyList(),
) {
    /** Todos los términos con los que se busca. */
    val all: List<String> get() = exact + prefixed

    /**
     * Los `n` términos más discriminantes: los de **menor** frecuencia de
     * documento (df más bajo), que son los que más peso tienen en BM25.
     */
    fun mostSelective(kb: KnowledgeBase, n: Int): List<String> =
        (exact + prefixed)
            .sortedBy { kb.documentFrequency(it) }
            .take(n)
}
```

> Por eso `Bm25Index` necesita exponer `documentFrequency(term)` y
> `vocabulary`: sin el IDF, "relajar" sería elegir términos al azar.

```kotlin
private fun resolveTerms(kb: KnowledgeBase, terms: List<String>): ResolvedTerms {
    val vocab = kb.vocabulary
    val exact = mutableListOf<String>()
    val prefixed = mutableListOf<String>()
    val missing = mutableListOf<String>()
    for (t in terms) {
        when {
            t in vocab -> exact += t
            t.length >= PREFIX_MIN -> {
                val candidates = vocab.filter { it.startsWith(t.take(PREFIX_MIN)) }
                if (candidates.isNotEmpty() && candidates.size <= MAX_PREFIX_CANDIDATES) {
                    prefixed += candidates
                } else {
                    missing += t
                }
            }
            else -> missing += t
        }
    }
    return ResolvedTerms(exact, prefixed.distinct(), missing)
}
```

Ejemplo con el índice real:

| Consulta | `tokenize` | Resolución | Se busca |
|---|---|---|---|
| `backpropagation` | `["backpropagation"]` | exacto | `backpropagation` |
| `¿cómo se calcula el gradiente?` | `["calcula", "gradiente"]` | `calcula` exacto; `gradiente` → prefijo `gradie` → `["gradiente", "gradientes"]` | 3 términos |
| `Kotlin` | `["kotlin"]` | exacto | `kotlin` |
| `C` | `[]` (1 letra) | — | barrido por subcadena |
| `hipótesis` vs material con `hipotesis` | `["hipotesis"]` | exacto (el tokenizador ya saca el acento) | `hipotesis` |

#### El paso 6 en detalle: barrido por subcadena

```kotlin
/**
 * Búsqueda literal sobre los textos de los fragmentos.
 *
 * Es la red de seguridad: funciona aunque el término no esté en el índice
 * (palabras de una letra) y aunque el prefijo no alcance.
 */
private fun scanBySubstring(
    kb: KnowledgeBase,
    terms: List<String>,
    scope: SearchScope,
    limit: Int,
): SearchOutcome {
    val hits = kb.allChunks.mapNotNull { chunk ->
        if (!scope.matches(chunk)) return@mapNotNull null
        val ranges = TextHighlight.findRanges(chunk.text, terms)
        if (ranges.isEmpty()) return@mapNotNull null
        // Puntaje simple: cuántos términos distintos casaron, y cuántas veces.
        val matched = terms.filter { t -> chunk.text.contains(t, ignoreCase = true) }
        ChunkHit(chunk, matched.size.toDouble(), matched)
    }.sortedByDescending { it.score }.take(limit)
    return if (hits.isEmpty()) {
        SearchOutcome.NoResults(terms.joinToString(" "), "No encontré esa palabra en el material.")
    } else {
        SearchOutcome.Results(hits, terms, relaxed = true)
    }
}
```

> El barrido es O(texto total de la cátedra). Con 300 fragmentos de 1.200
> caracteres son ~360 KB: menos de 5 ms. Si una cátedra tuviera 10.000
> fragmentos, el paso 4 (BM25) resuelve el 99 % de las consultas y el barrido
> sólo se dispara cuando BM25 no encontró nada.

### 3.4 Resaltar: dónde están las coincidencias

El resaltado tiene que funcionar **con y sin acentos** y tiene que devolver
**posiciones en el texto original**, porque el texto se muestra tal como está
("Definición") y el término puede venir sin tilde ("definicion").

Para eso hace falta un tokenizador que **conserva el mapa de posiciones**:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Text.kt (agregar)

/**
 * Quita diacríticos y devuelve además el mapa de posiciones.
 *
 * @return (texto normalizado, mapa) donde `mapa[i]` es el índice del carácter
 *   **original** que corresponde al carácter normalizado `i`.
 */
expect fun stripDiacriticsWithMap(text: String): Pair<String, IntArray>
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/Text.jvm.kt (agregar)
actual fun stripDiacriticsWithMap(text: String): Pair<String, IntArray> {
    val sb = StringBuilder(text.length)
    val map = ArrayList<Int>(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        val normalized = stripDiacritics(String(Character.toChars(cp)))
        if (normalized.isNotEmpty()) {
            sb.append(normalized)
            // Un carácter original puede producir varios normalizados (caso
            // raro): se mapean todos al mismo índice original.
            repeat(normalized.length) { map += i }
        }
        i += n
    }
    return sb.toString() to map.toIntArray()
}
```

Y el resaltador:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/TextHighlight.kt
package rag.retrieval

import kitoru.platform.stripDiacriticsWithMap

/**
 * Resaltado de coincidencias en texto plano.
 *
 * Comparación sin diacríticos y sin distinguir mayúsculas, pero devolviendo
 * **rangos sobre el texto original**: es lo que permite pintar "Definición"
 * cuando el alumno escribió "definicion".
 */
object TextHighlight {

    /**
     * Rangos del texto original donde aparecen los términos.
     *
     * Sólo cuentan las coincidencias **en límites de palabra**: "ion" no
     * resalta dentro de "distribución".
     */
    fun findRanges(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val (normalized, map) = stripDiacriticsWithMap(text)
        val lower = normalized.lowercase()
        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            val t = term.lowercase()
            if (t.isEmpty()) continue
            var from = 0
            while (true) {
                val i = lower.indexOf(t, from)
                if (i < 0) break
                val beforeOk = i == 0 || !lower[i - 1].isLetterOrDigit()
                val after = i + t.length
                val afterOk = after >= lower.length || !lower[after].isLetterOrDigit()
                if (beforeOk && afterOk) {
                    val lastIdx = (i + t.length - 1).coerceAtMost(map.size - 1)
                    ranges += map[i]..map[lastIdx]
                }
                from = i + 1
            }
        }
        return merge(ranges)
    }

    /** Une rangos solapados o contiguos y los ordena. */
    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf(sorted.first())
        for (r in sorted.drop(1)) {
            val last = out.last()
            if (r.first <= last.last + 1) {
                out[out.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                out += r
            }
        }
        return out
    }
}
```

El rango devuelto puede no coincidir con el texto original si el término
aparece con distinta capitalización o con tilde; por eso el render **no
reconstruye** el texto: usa los rangos para pintar el `AnnotatedString` que se
construye sobre el texto real.

### 3.5 Anatomía de un resultado

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Unidad 1 — Búsqueda heurística                                          │
│ # Conceptos clave                                                        │
│ …una función de **evaluación** *heurística* es una función que…           │
│ 3 coincidencias · fragmento 4 de 24 · 12% del documento                   │
│                                          [Ver en el material] [❓ Preguntar]│
└──────────────────────────────────────────────────────────────────────────┘
```

Datos de cada resultado:

| Dato | De dónde sale |
|---|---|
| Título del material | `chunk.ref` → `Material.title` (o `chunk.path` si no se puede resolver el id) |
| Encabezado | `chunk.heading` (del `#` que tenía el Markdown) |
| Snippet | `TextHighlight.snippet(chunk.text, terms)` — 220 caracteres alrededor de la mejor coincidencia, cortados en palabra |
| Términos que casaron | `chunkHit.matchedTerms` |
| "fragmento 4 de 24" | posición del chunk dentro de su material |
| "% del documento" | `chunk.text.length / material.extractedText.length` |

Acciones de cada resultado:

- **Ver en el material** → cambia al panel de detalle de ese material y hace
  scroll hasta el fragmento (usando `chunk.start`), con el término resaltado.
- **Preguntar a la IA sobre esto** → crea el `PendingAsk` (§4) con ese chunk
  fijado y lleva al chat.
- **Copiar el fragmento** → al portapapeles (el texto pelado, sin formato).

### 3.6 Alcance de la búsqueda

| Alcance | Dónde | Implementación |
|---|---|---|
| En todo el material | Buscador de arriba | `SearchScope.All` |
| En este material | Buscador de la pantalla de detalle | `SearchScope.InMaterial(material.id)` — el filtro se aplica sobre `chunk.ref` |

La búsqueda con alcance se hace en el **mismo índice**: no hay un segundo
índice por material (serían 300 índices y una memoria absurda). El filtro es
post-búsqueda.

Además, cuando el alcance es "un material", los resultados se muestran en la
propia pantalla de detalle (debajo del texto), no en el panel de resultados.

### 3.7 Casos borde de la búsqueda

| Caso | Qué hace la pantalla |
|---|---|
| Consulta vacía | Vuelve al modo *navegar*. El buscador se limpia. |
| Sólo palabras vacías ("¿y?", "the") | "Escribí al menos una palabra con contenido." + 3 sugerencias de búsqueda (los títulos de los materiales, o las etiquetas más frecuentes). |
| Un término con tilde y el material sin tilde | Se encuentra: la comparación es sin diacríticos. |
| Un término en otra forma ("gradiente" vs "gradientes") | Se encuentra por prefijo; el resultado dice "coincidencia por prefijo" en un tooltip. |
| Consulta con comillas (`"cadena de markov"`) | Se busca la frase **exacta** primero (normalizada); si hay resultados, se usan; si no, se cae a la búsqueda por términos. |
| Un material sin texto extraído | No aparece en los resultados (no tiene fragmentos). Si es el único material de la cátedra, la pantalla lo avisa: "El material de esta cátedra no tiene texto extraído: no se puede buscar." |
| El RAG recuperó 0 resultados pero la búsqueda también | "No encontré «X». Probá con menos palabras o con otra forma." + los 3 materiales más parecidos por etiqueta. |
| 300 resultados | Se muestran 50 y el botón "Ver más" carga los siguientes 50 (paginación simple, sin scroll infinito). |
| Un material con 40 coincidencias | Se muestran 5 como máximo (§3.3 paso 8) + el texto "40 coincidencias en este material" con un link a buscarlos todos con alcance "en este material". |
| Búsqueda mientras se reindexa | El buscador se deshabilita con "Indexando…" y se rehabilita al terminar. |

---

## 4. Preguntar sobre un fragmento concreto

### 4.1 Los dos caminos que llegan al mismo lugar

```
 Camino A: desde un resultado de búsqueda
   SearchResultItem → [❓ Preguntar a la IA sobre esto]
        → PendingAsk(question = <lo que escribió el alumno en el buscador>,
                      pinned = listOf(chunk))

 Camino B: desde la vista de un material
   MaterialDetailPane → el alumno toca un párrafo (o un fragmento de la
   pestaña "Fragmentos") → el párrafo queda seleccionado
        → [❓ Preguntar a la IA sobre esto]
        → PendingAsk(question = <prellenada o vacía>,
                      pinned = listOf(chunkSintético)
```

Los dos caminos producen el mismo objeto: **`PendingAsk`**.

### 4.2 Cómo se inyecta el fragmento en el prompt

**Decisión: contexto priorizado, no "material del usuario" y no "mensaje aparte".**

Las tres opciones y por qué:

| Opción | Cómo funciona | Por qué no / por qué sí |
|---|---|---|
| **(A) Contexto priorizado** (elegida) | El fragmento va **primero** en el bloque `CONTEXTO`, numerado `[1]`. El resto se sigue recuperando por BM25. | ✅ El `CONTEXTO` conserva **exactamente** la forma que produce `PromptPayload.formatContext`, así que el `CitationVerifier` (que mapea `[n]` → `sources[n-1]`) sigue funcionando sin cambios. El modelo tiene la información que el alumno quiso señalar, y puede citar `[1]`. |
| (B) "Material del usuario" (un mensaje `user` extra con el fragmento) | Un `ChatMessage("user", "<texto del material>")` antes del `CONTEXTO`. | ❌ Rompe la forma del prompt que la cátedra define (y que la pantalla del docente muestra en la vista previa). Además el modelo no tiene forma de saber que ese mensaje es material y no una pregunta: citaría `[n]` mal. |
| (C) Pregunta reescrita ("resumí este fragmento: …") | Se mete el texto del fragmento dentro de la pregunta. | ❌ La pregunta del alumno se pierde de vista y el verificador de citas queda sin fragmento al que comparar. |

**Cómo queda el prompt con la opción (A):**

```
CONTEXTO:
[1] (cátedra: Unidad 1 — Búsqueda heurística — Conceptos clave)
Una función de evaluación heurística es una función que evalúa una solución
aproximada a un problema de optimización…

---

[2] (cátedra: Unidad 2 — Redes neuronales — Backpropagation)
El algoritmo de retropropagación…

PREGUNTA:
¿podrías explicarme esto con un ejemplo?

(El fragmento [1] fue seleccionado por el estudiante: respondé sobre él primero
y citá [1] cuando lo uses.)
```

- El `[1]` es el fragmento del alumno. Es lo que el modelo cita y lo que el
  verificador contrasta.
- Los demás fragmentos son el contexto general, recovered por BM25 con el
  presupuesto restante.
- La nota final es **determinista** (un string fijo, con el título del
  material) y va **después** de la pregunta, así que no rompe la forma del
  prompt que la pantalla del docente previsualiza.

### 4.3 El cambio en `RagEngine.prepare`

```kotlin
// backend/src/commonMain/kotlin/rag/engine/RagEngine.kt
/**
 * Arma los mensajes de una pregunta (con o sin RAG).
 *
 * @param pinned fragmentos fijados por el estudiante (el que seleccionó en la
 *   pantalla de material). Van **primeros** en el bloque CONTEXTO, así que
 *   quedan numerados `[1]`, `[2]`… y el verificador de citas sigue funcionando.
 */
fun prepare(
    question: String,
    history: List<ChatMessage>,
    useDocs: Boolean,
    pinned: List<Chunk> = emptyList(),
): Prepared {
    val fijados = pinned.distinctBy { it.ref to it.start }
    if (!useDocs && fijados.isEmpty()) {
        return Prepared(
            emptyList(),
            buildList {
                add(ChatMessage("system", GENERAL_PROMPT))
                addAll(history.takeLast(cfg.historyTurns * 2))
                add(ChatMessage("user", question))
            },
        )
    }

    val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()

    // El presupuesto de recuperación se reparte: lo fijado entra primero y
    // lo que queda se recupera por BM25.
    val recuperado = if (useDocs) {
        val k = (cfg.topK - fijados.size).coerceAtLeast(0)
        if (k == 0) emptyList() else kb.retrieve("$question $lastUser", k)
    } else {
        emptyList()
    }

    val sources = (fijados + recuperado)
        .distinctBy { it.ref to it.start }
        // No hay `take`: los fijados nunca se descartan (si el alumno fijó más
        // de `topK`, el contexto crece; el total recuperado queda acotado por
        // `cfg.topK - fijados.size`, así que el presupuesto no se dispara).

    val context = PromptPayload.formatContext(sources)
    val nota = if (fijados.isEmpty()) "" else
        "\n\n(${fijados.size} fragmento${if (fijados.size == 1) "" else "s"} " +
        "seleccionado${if (fijados.size == 1) "" else "s"} por el estudiante: " +
        "respondé sobre ${if (fijados.size == 1) "él" else "ellos"} primero y " +
        "citá ${fijados.indices.joinToString(", ") { "[${it + 1}]" }} cuando los uses.)"

    val messages = buildList {
        add(ChatMessage("system", systemPrompt()))
        addAll(history.takeLast(cfg.historyTurns * 2))
        add(ChatMessage("user", "CONTEXTO:\n$context\n\nPREGUNTA:\n$question$nota"))
    }
    return Prepared(sources, messages)
}
```

Notas de diseño del código:

1. **`PromptPayload.formatContext` se extrae a `:backend`** (lo propone la
   pantalla del docente en `teacher/04-prompt.md` §3.3). `RagEngine.prepare`
   pasa a usarlo y la UI de materiales lo usa para **mostrarle al alumno
   exactamente lo que va a ver el modelo**. Un solo lugar arma el `CONTEXTO`.
2. El **presupuesto**: con `topK = 5` y 1 fragmento fijado, el modelo recibe 1
   fijado + 4 recuperados. El total no crece, así que el tamaño del prompt no
   se dispara por usar el botón. (Si el alumno fija más fragmentos que
   `topK`, gana el contexto elegido por él: el resto no se recupera.)
3. `distinctBy { it.ref to it.start }`: si el fragmento fijado también salió
   en la recuperación por BM25, **no se duplica**. (Sin offsets, `ref` solo
   bastaría; con offsets, el par es exacto.)
4. El modo general (`useDocs = false`) **sí** acepta fragmentos fijados: si el
   alumno seleccionó un pasaje, se lo mandamos igual. Es la excepción que hace
   útil el botón.

### 4.4 `PendingAsk`: el viaje del material al chat

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/chat/PendingAsk.kt
package kitoru.ui.student.chat

import rag.retrieval.Chunk

/**
 * Una pregunta que el alumno está por hacer, con el contexto ya elegido.
 *
 * La crea la pantalla de Materiales y la consume la de Chat. Viaja como un
 * evento de navegación: la pantalla de Materiales **no** conoce al chat.
 */
data class PendingAsk(
    /** Texto precargado en el composer. */
    val question: String = "",
    /** Fragmentos fijados: van primeros en el bloque CONTEXTO. */
    val pinned: List<Chunk> = emptyList(),
    /** Material de origen (para la etiqueta "preguntando sobre: …"). */
    val materialId: String = "",
    val materialTitle: String = "",
    /** Hilo donde se manda; null = hilo nuevo. */
    val threadId: String? = null,
)
```

Y el estado del chat:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/chat/ChatState.kt
data class ChatState(
    ...
    /** Contexto fijado por el alumno (se muestra sobre el composer). */
    val pinned: List<Chunk> = emptyList(),
    /** Etiqueta del contexto fijado ("Unidad 1 — Búsqueda heurística"). */
    val pinnedLabel: String = "",
    ...
)
```

El ViewModel del chat:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/chat/ChatViewModel.kt

/** Aplica el contexto que viene de la pantalla de Materiales. */
fun applyPendingAsk(ask: PendingAsk) {
    state = state.copy(
        draft = ask.question,
        pinned = ask.pinned,
        pinnedLabel = ask.materialTitle,
        threadId = ask.threadId ?: state.threadId,
    )
}

/** Saca el contexto fijado (el alumno se arrepiente). */
fun clearPinned() {
    state = state.copy(pinned = emptyList(), pinnedLabel = "")
}
```

Y al enviar:

```kotlin
val prepared = ragEngine.prepare(
    question = question,
    history = history,
    useDocs = state.useCourseMaterial,
    pinned = state.pinned,
)
// …se llama al modelo con prepared.messages…

// Y al guardar el mensaje del asistente:
messageRepository.save(
    ChatMessage(
        id = newId(),
        threadId = thread.id,
        role = "assistant",
        text = answer,
        createdAt = kitoru.platform.nowMillis(),
        sources = prepared.sources.map { it.label },          // como siempre
        sourceRefs = prepared.sources.toMaterialRefs(),        // ← nuevo: permite volver al material
        citation = engine.verify(answer, prepared.sources).toStats(),
        latencyMs = elapsed,
        tokensOut = estimateTokens(answer),
        tokensIn = estimateTokens(question),
    )
)
```

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/MaterialSourceRefs.kt
package rag.retrieval

import kitoru.domain.MaterialSourceRef

/** Convierte los fragmentos recuperados en referencias navegables al material. */
fun Chunk.toMaterialRef(): MaterialSourceRef = MaterialSourceRef(
    materialId = ref,
    title = path,
    heading = heading,
    start = start,
    end = end,
)

fun List<Chunk>.toMaterialRefs(): List<MaterialSourceRef> = map { it.toMaterialRef() }
```

### 4.5 Qué ve el alumno mientras pregunta

- En la pantalla de Materiales, al apretar "Preguntar a la IA sobre esto" se
  abre un **diálogo corto** con la pregunta precargada (o vacía, si el alumno
  no escribió nada en el buscador):

  ```
  ┌────────────────────────────────────────────┐
  │ Preguntar sobre «Unidad 1 — Búsqueda…»   │
  │ ┌────────────────────────────────────────┐ │
  │ │ ¿podrías explicarme esto con un ejem…  │ │
  │ └────────────────────────────────────────┘ │
  │ El fragmento seleccionado se manda a la IA │
  │ como contexto prioritario.                 │
  │                    [Cancelar]  [Preguntar] │
  └────────────────────────────────────────────┘
  ```

  Si el alumno escribió una consulta en el buscador, el texto **se propone**
  como pregunta (editable): "Vas a preguntar: «backpropagation»". Si la
  consulta parece una pregunta (termina en `?` o empieza con "¿"), se propone
  tal cual.

- En el chat, el contexto fijado se muestra **sobre el composer**, para que el
  alumno no olvide qué está preguntando:

  ```
  ┌──────────────────────────────────────────────┐
  │ 📎 Preguntando sobre: Unidad 1 — Búsqueda…  ×│
  └──────────────────────────────────────────────┘
  [ Escribí tu pregunta…                          ]  [Enviar]
  ```

  El `×` saca el contexto (y vuelve a la preparación normal: el RAG decide qué
  fragmentos usar).

- En el mensaje del asistente, la fuente `[1]` es **clickeable** y lleva al
  material, en el fragmento exacto (§8).

### 4.6 Qué pasa si la IA no usa el fragmento fijado

Puede pasar: el modelo contesta con el contexto general y no menciona `[1]`.
La app lo muestra sin drama: el `CitationBadge` dice "Sin citas" o "1 cita sin
respaldo", que es exactamente para lo que existe el verificador. **No** se
filtra ni se reintenta: la app no decide si la respuesta es buena, la muestra
con su distintivo.

---

## 5. Materiales ocultos

**Decisión: el material oculto no existe para el alumno. Ni en la lista, ni en
la búsqueda, ni en el RAG.**

| Capa | Qué hace |
|---|---|
| Lista | El filtro es `material.visible == true` en el `MaterialsViewModel`, antes de aplicar los filtros de la UI. |
| Búsqueda | Los ocultos **no están en el índice**: `CourseMaterialSource.load()` filtra `it.visible && it.extractedText.isNotBlank()`. No hay nada que recuperar. |
| Chat / RAG | Ídem: el `KnowledgeBase` de la cátedra se construye con `CourseMaterialSource`, así que el modelo **no puede citar** un material oculto. |
| Descarga | La ruta del archivo se resuelve sólo para materiales visibles: un material oculto no se puede abrir ni exportar. |
| Camino inverso | Un mensaje viejo puede citar un material que después se ocultó. La app lo muestra como **"material oculto (ya no está disponible)"** y **no** permite abrirlo ni ver su texto. |

**Por qué el RAG tampoco lo usa:**

1. Si un material está oculto, es porque el docente lo sacó de circulación (está
   desactualizado, tiene datos personales, o se substituyó por otro). Que el
   modelo lo cite es peor que que no lo cite: el alumno recibe una respuesta
   con una cita `[1]` que no puede abrir ni verificar.
2. La regla general del proyecto es que **el material de la cátedra es el
   corpus del alumno**: si una parte del corpus no es del alumno, no es corpus.
3. Es lo que **ya hace el código**: `CourseMaterialSource` filtra por
   `visible`. La decisión de diseño confirma el comportamiento y lo hace
   explícito; no hay que cambiar una línea del RAG.

**Deuda consciente:** con un solo flag, "oculto" significa las dos cosas a la
vez (no visible **y** no indexado). Si el docente quisiera "indexado pero
oculto" (material de apoyo para la IA que el alumno no lista), habría que
splitear el flag en `visible` + `indexed`. Se anota como deuda; no se resuelve
acá.

---

## 6. Abrir y descargar el material

### 6.1 El seam `expect/actual`: reutilizar `FileSaver`

**El módulo `:ui` ya existe** y ya tiene una costura para archivos:
`kitoru.ui.platform.FileSaver` (`ui/src/commonMain/kotlin/kitoru/ui/platform/FileSaver.kt`),
con su `actual` de escritorio (`java.awt.FileDialog`) y el de Android
(**SAF, `ACTION_CREATE_DOCUMENT`**). No se crea una costura nueva: **se extiende
esa**.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileSaver.kt (modificar)
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import kitoru.platform.FilePath

interface FileSaver {
    /**
     * Pide al usuario dónde guardar y escribe el contenido.
     * @return `true` si se guardó; `false` si el usuario canceló.
     */
    suspend fun save(
        suggestedName: String,
        content: String,
        mimeType: String = "text/plain",
    ): Boolean

    /**
     * Guarda una **copia de un archivo que ya existe en disco**.
     *
     * Es lo que necesita la pantalla de Materiales: el original del material
     * es **binario** (un PDF, un PPTX), así que no puede pasar por
     * `content: String` sin corromperlo.
     *
     * @param source ruta del archivo a copiar (en el equipo del alumno)
     * @return `true` si se guardó; `false` si se canceló o no se pudo copiar.
     */
    suspend fun saveFile(
        suggestedName: String,
        source: FilePath,
        mimeType: String = "application/octet-stream",
    ): Boolean
}

/** Guardador de archivos de la plataforma. */
@Composable
expect fun rememberFileSaver(): FileSaver
```

El `actual` de escritorio copia el archivo con el `FileSystem` que ya existe
en `:core` (`systemFileSystem().copy(from, to)`), sin `java.io` a mano:

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/FileSaver.desktop.kt (modificar)
class DesktopFileSaver : FileSaver {

    override suspend fun save(suggestedName: String, content: String, mimeType: String): Boolean {
        val parent = Frame().apply { isUndecorated = true }
        val dialog = FileDialog(parent, "Guardar como", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (name == null) return false
        val target = File(dir, name)
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** Copia el archivo original al destino elegido. */
    override suspend fun saveFile(
        suggestedName: String,
        source: FilePath,
        mimeType: String,
    ): Boolean {
        val parent = Frame().apply { isUndecorated = true }
        val dialog = FileDialog(parent, "Guardar como", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (name == null) return false
        val target = File(dir, name)
        return runCatching {
            target.parentFile?.mkdirs()
            File(source.value).copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
    }
}
```

En Android, el `actual` tiene que escribir **bytes**, no texto, y el launcher
de SAF se crea con un mime genérico para poder cambiarlo por llamada:

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/FileSaver.android.kt (modificar)
@Composable
actual fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { AndroidSaveHolder() }

    // El launcher se crea con "*/*" para poder pasar el mime real por
    // llamada (un PDF y un TXT necesitan filtros distintos del explorador).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val request = holder.request
        if (uri == null || request == null) {
            holder.complete(false)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    when (request) {
                        is AndroidSaveRequest.Text ->
                            out.write(request.content.toByteArray(Charsets.UTF_8))
                        is AndroidSaveRequest.File ->
                            File(request.source.value).inputStream().use { it.copyTo(out) }
                    }
                } != null
            }.getOrDefault(false)
            holder.complete(ok)
        }
    }

    return remember(launcher, holder) {
        object : FileSaver {
            override suspend fun save(
                suggestedName: String,
                content: String,
                mimeType: String,
            ): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                holder.request = AndroidSaveRequest.Text(suggestedName, content, mimeType)
                holder.deferred = deferred
                launcher.launch(suggestedName)
                return deferred.await()
            }

            override suspend fun saveFile(
                suggestedName: String,
                source: FilePath,
                mimeType: String,
            ): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                holder.request = AndroidSaveRequest.File(suggestedName, source, mimeType)
                holder.deferred = deferred
                launcher.launch(suggestedName)
                return deferred.await()
            }
        }
    }
}
```

La petición pasa a ser una suma de dos casos:

```kotlin
private sealed interface AndroidSaveRequest {
    val name: String
    val mimeType: String

    data class Text(
        override val name: String,
        val content: String,
        override val mimeType: String,
    ) : AndroidSaveRequest

    data class File(
        override val name: String,
        val source: FilePath,
        override val mimeType: String,
    ) : AndroidSaveRequest
}
```

### 6.1.b Abrir con la app por defecto: un seam nuevo

**Abrir** un archivo con otra aplicación no existe todavía y necesita su propia
costura (es una `Intent` en Android y un `Desktop.open` en escritorio). Va
**al lado de `FileSaver`**, en el mismo paquete `kitoru.ui.platform`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileOpener.kt (nuevo)
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import kitoru.platform.FilePath

/**
 * Abre un archivo con la aplicación por defecto del sistema.
 *
 *  - **Escritorio**: `java.awt.Desktop.open` (el PDF se abre en el visor del
 *    sistema: Acrobat, Sumatra, el visor del navegador…).
 *  - **Android**: `FileProvider` + `ACTION_VIEW`. No se puede abrir un
 *    archivo de `filesDir` con un `file://` (desde Android 7 eso lanza
 *    `FileUriExposedException`): hay que exponer una **copia temporal** del
 *    `cacheDir` con un `content://` y permiso temporal de lectura.
 */
interface FileOpener {
    /**
     * Abre el archivo.
     * @return `null` si se abrió; un **mensaje en español** si no se pudo
     *   (por ejemplo: el archivo no está en este dispositivo).
     */
    fun open(file: FilePath, mimeType: String): String?
}

/** Abridor de archivos de la plataforma. */
@Composable
expect fun rememberFileOpener(): FileOpener
```

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/FileOpener.desktop.kt (nuevo)
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kitoru.platform.FilePath
import java.awt.Desktop
import java.io.File

class DesktopFileOpener : FileOpener {
    override fun open(file: FilePath, mimeType: String): String? {
        val f = File(file.value)
        if (!f.exists()) {
            return "El archivo original no está en este equipo: sólo se puede leer el texto extraído."
        }
        if (!Desktop.isDesktopSupported()) {
            return "Este sistema no puede abrir archivos automáticamente. Abrilo a mano desde «${f.absolutePath}»."
        }
        return runCatching {
            Desktop.getDesktop().open(f)
            null
        }.getOrElse { "No se pudo abrir «${f.name}»: ${it.message ?: "error desconocido"}" }
    }
}

@Composable
actual fun rememberFileOpener(): FileOpener = remember { DesktopFileOpener() }
```

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/FileOpener.android.kt (nuevo)
package kitoru.ui.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kitoru.platform.FilePath
import java.io.File

class AndroidFileOpener(private val context: android.content.Context) : FileOpener {

    override fun open(file: FilePath, mimeType: String): String? {
        val f = File(file.value)
        if (!f.exists()) {
            return "El archivo original no está en este dispositivo: sólo se puede leer el texto extraído."
        }
        // 1) Copia al caché: `filesDir` es privado y no se puede compartir.
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val tmp = File(shareDir, f.name)
        f.copyTo(tmp, overwrite = true)

        // 2) Se expone como content:// con permiso temporal de lectura.
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tmp,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            null
        }.getOrElse { "No hay ninguna aplicación instalada para abrir «${f.name}»." }
    }
}

@Composable
actual fun rememberFileOpener(): FileOpener {
    val context = LocalContext.current
    return remember(context) { AndroidFileOpener(context) }
}
```

El `FileProvider` se declara en el **manifiesto de la app** (`:android`), con
la autoridad `${applicationId}.fileprovider`:

```xml
<!-- android/src/main/AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

```xml
<!-- android/src/main/res/xml/file_paths.xml -->
<paths>
    <cache-path name="share" path="share/" />
</paths>
```

> `:ui` ya depende de `androidx.activity.compose` (que trae `androidx.core`
> transitivamente), así que `FileProvider` está disponible; conviene declarar
> `androidx.core` explícito en `ui/build.gradle.kts` para no depender de la
> transitividad.

### 6.1.c El MIME type

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/MimeTypes.kt (nuevo)
package kitoru.ui.platform

/** MIME type según la extensión del archivo. */
fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
    "pdf" -> "application/pdf"
    "md", "txt", "rst", "log" -> "text/plain"
    "csv" -> "text/csv"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "epub" -> "application/epub+zip"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
```

`Material.extension` ya da la extensión en minúsculas (`Course.kt`):
`path.substringAfterLast('.', "").lowercase()`.

### 6.1.d Abrir una URL

Los materiales de tipo `LINK` se abren en el navegador. Es un `Intent` en
Android y `Desktop.browse` en escritorio; como `FileOpener` ya es un seam de
la plataforma, se le agrega un método:

```kotlin
interface FileOpener {
    fun open(file: FilePath, mimeType: String): String?
    /** Abre una URL en el navegador. @return `true` si se abrió. */
    fun openUrl(url: String): Boolean
}
```


### 6.2 La ruta del archivo

`Material.path` es **relativa a la carpeta de la cátedra** (así lo dice el
dominio). La ruta absoluta se resuelve en un solo lugar:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/MaterialPaths.kt
package kitoru.platform

import kitoru.domain.Material

/**
 * Resuelve las rutas de los materiales.
 *
 * `Material.path` es relativa a la carpeta de la cátedra; el archivo real
 * vive en `<dataDir>/courses/<courseId>/<path>`.
 */
object MaterialPaths {

    /** Carpeta de la cátedra. */
    fun courseDir(courseId: String): FilePath = KitoruPaths.coursesDir.resolve(courseId)

    /** Ruta absoluta del archivo original de un material. */
    fun original(courseId: String, material: Material): FilePath =
        courseDir(courseId).resolve(material.path)
}
```

Ejemplo: `KitoruPaths.dataDir = <home>/.kitoru`, `courseId = "ia-2026-1"`,
`material.path = "Unidad 1.pdf"` → `<home>/.kitoru/courses/ia-2026-1/Unidad 1.pdf`.

La existencia del archivo se verifica con el `FileSystem` que ya existe en
`:core` (`systemFileSystem().exists(path)`), sin `java.io` en `commonMain`:

```kotlin
// En el ViewModel o en la pantalla:
val original = MaterialPaths.original(courseId, material)
val existe = systemFileSystem().exists(original)
```

`FilePath.resolve` ya normaliza el separador (`/`), que en Windows acepta
`java.io.File` sin problema.

### 6.3 Escritorio

| Acción | Cómo |
|---|---|
| "Abrir con la app por defecto" | `DesktopFileOpener.open` → `java.awt.Desktop.open(f)`. El PDF se abre en el visor del sistema. |
| "Guardar una copia" | `DesktopFileSaver.saveFile` → `java.awt.FileDialog` (SAVE) + copia del archivo. Es el mismo diálogo que ya usa la app para guardar un bloque de código. |
| "Abrir el enlace" (material `LINK`) | `DesktopFileOpener.openUrl` → `Desktop.browse(URI(url))`. |

Si `Desktop.isDesktopSupported()` es `false` (un Linux sin portal de archivos),
el mensaje es explícito y **no** falla en silencio:

> Este sistema no puede abrir archivos automáticamente. Abrilo a mano desde
> `<ruta>`.

### 6.4 Android: `FileProvider` + SAF

**El problema real:** los archivos de la cátedra viven en
`context.filesDir/kitoru/courses/…`, que es **privado**. Android **no**
permite que otra app lo lea con un `file://` (desde Android 7, eso lanza
`FileUriExposedException`). La forma correcta es exponer una **copia temporal**
del `cacheDir` con un `content://` de `FileProvider` y permiso temporal de
lectura (`FLAG_GRANT_READ_URI_PERMISSION`).

| Acción | Cómo |
|---|---|
| "Abrir con otra app" | `AndroidFileOpener.open` → copia al `cacheDir/share` → `FileProvider.getUriForFile` → `Intent.ACTION_VIEW` con el MIME type. |
| "Guardar una copia" | `AndroidFileSaver.saveFile` → **SAF** (`ACTION_CREATE_DOCUMENT`): el usuario elige la carpeta (Descargas, Drive…) y la app escribe los bytes con el `ContentResolver`. |
| "Abrir el enlace" (material `LINK`) | `AndroidFileOpener.openUrl` → `Intent.ACTION_VIEW` con el `Uri.parse(url)`. |

**Por qué SAF y no `WRITE_EXTERNAL_STORAGE`:** desde Android 10 los permisos de
almacenamiento están restringidos y `MANAGE_EXTERNAL_STORAGE` no se permite en
Play. SAF es la única forma soportada de escribir en una carpeta elegida por el
usuario sin permisos invasivos — y es exactamente lo que ya hace
`FileSaver.android.kt`.

**El `FileProvider` necesita el manifiesto y el `file_paths.xml`** (ver §6.1.b).

### 6.4.b Las acciones de la UI

En la pestaña "Original" (y en el menú `⋮` del detalle):

```
┌─────────────────────────────────────────────────────────────────┐
│ Original                                                        │
│ Unidad 1 — Búsqueda heurística.pdf · 1,2 MB                     │
│                                                                  │
│ El archivo está en este equipo.                                 │
│                                                                  │
│ [Abrir con la app por defecto]   [Guardar una copia]            │
└─────────────────────────────────────────────────────────────────┘
```

Los dos botones llaman a `rememberFileOpener()` y `rememberFileSaver()`; el
resultado se muestra como un `Snackbar` ("Se guardó una copia en …" / el
mensaje de error en español que devuelve el seam).


### 6.5 Materiales sin archivo local

En el milestone 1 el material es un archivo local. Pero un alumno puede
inscribirse a una cátedra cuyos materiales **no copió** (o cuya carpeta se
borró). El diseño lo contempla:

| Situación | Qué ve el alumno |
|---|---|
| El archivo existe | "Abrir" y "Guardar una copia" habilitados. |
| El archivo **no** existe pero hay texto extraído | El texto se lee igual (es lo que importa). "Abrir" queda deshabilitado con el motivo: *"El archivo original no está en este equipo: sólo se puede leer el texto extraído."* |
| No hay archivo **ni** texto | Estado vacío con el aviso de extracción (§7). |

### 6.6 Materiales de tipo `LINK`

Un `MaterialKind.LINK` no tiene archivo: su `path` es una URL. Las acciones son
distintas:

- "Abrir" → `openUrl(url)` (navegador).
- "Descargar" → no aplica; se ofrece "Copiar el enlace".
- ¿Tiene texto extraído? Si el docente pegó el contenido de la página en
  `extractedText`, el material **es buscable** como cualquier otro. Si no, la
  pestaña "Texto" lo dice: "Este material es un enlace: no hay texto guardado
  para buscar. Abrilo para leerlo."

---

## 7. Materiales con problemas de extracción

### 7.1 El estado derivado

`Material.warning` es un string libre que la app escribe al indexar. La
pantalla deriva de ahí un **estado** con tres (o cuatro) valores:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialStatus.kt
package kitoru.domain

/**
 * Estado de la extracción de texto de un material.
 *
 * `Material.warning` es el texto crudo que dejó la extracción; este enum es
 * el estado sobre el que la UI puede decidir (icono, color, acción).
 */
enum class ExtractionStatus(val label: String) {
    /** Se extrajo el texto completo: el material es buscable y preguntable. */
    OK("Texto completo"),

    /** Hay texto, pero la extracción avisó algo (páginas de más, texto ilegible). */
    PARTIAL("Texto parcial"),

    /** No se pudo extraer texto: el material **no** es buscable ni preguntable. */
    EMPTY("Sin texto"),

    /** No aplica: el material no es un archivo (un enlace). */
    NOT_APPLICABLE("Enlace"),
    ;
}

/** Estado de extracción del material. */
val Material.extractionStatus: ExtractionStatus
    get() = when {
        kind == MaterialKind.LINK -> ExtractionStatus.NOT_APPLICABLE
        extractedText.isBlank() -> ExtractionStatus.EMPTY
        warning.isNotBlank() -> ExtractionStatus.PARTIAL
        else -> ExtractionStatus.OK
    }

/** ¿El material se puede buscar y preguntar? */
val Material.isSearchable: Boolean
    get() = extractionStatus == ExtractionStatus.OK || extractionStatus == ExtractionStatus.PARTIAL
```

### 7.2 Qué ve el alumno

**En la lista**, el chip de estado:

| Estado | Chip | Tooltip |
|---|---|---|
| `OK` | — | — |
| `PARTIAL` | ámbar "texto parcial" | El texto de la extracción: *"Se extrajeron 8 de 40 páginas"* |
| `EMPTY` | rojo "sin texto" | *"No se pudo extraer texto de este PDF"* |

**Al abrir un material sin texto**, la pestaña "Texto" muestra un estado
vacío con la explicación y **dos acciones**:

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚠ Este PDF no tiene capa de texto                                │
│                                                                  │
│ Es un escaneo: las páginas son imágenes, no texto. Por eso no se  │
│ puede buscar ni preguntar a la IA sobre este material.           │
│                                                                  │
│ Podés:                                                           │
│   • abrir el archivo original y leerlo con tu visor (o con OCR)  │
│   • pedirle al docente una versión accesible (texto real)        │
│                                                                  │
│              [Abrir el original]  [Pedir versión accesible]      │
└──────────────────────────────────────────────────────────────────┘
```

**En un material con texto parcial**, el aviso es más suave y va arriba del
texto, no en pantalla vacía:

> **Texto parcial.** La extracción avisa: *"Se extrajeron 8 de 40 páginas"*.
> Lo que sigue es el texto que se pudo leer; las páginas que faltan **no** se
> pueden buscar ni preguntar.

### 7.3 Qué puede hacer el alumno

| Acción | Qué hace | Cómo se implementa |
|---|---|---|
| **Abrir el original** | Abre el archivo con la app por defecto (escritorio) o con `FileProvider` (Android). El alumno puede usar su propio lector con OCR. | §6. |
| **Pedir una versión accesible** | Abre un diálogo con un mensaje pre-armado para el docente. | Ver abajo. |
| **Copiar el mensaje** | Copia el texto al portapapeles para mandarlo por mail/WhatsApp. | `LocalClipboard` de Compose. |
| **Comentar el aviso** (si el material está adjunto a un aviso) | Publica un comentario en el aviso, con el texto pre-armado. | Subsistema de anuncios (`domain/07-anuncios.md` §6). |

El mensaje pre-armado (determinista, en el diálogo, editable):

```
Hola, en la cátedra de {materia} el material "{título}" ({tipo}, {tamaño})
no tiene capa de texto (es un escaneo), así que no se puede buscar ni
preguntar a la IA. ¿Podés subir una versión con texto real (o aplicar OCR)?
Gracias.
```

> **Por qué no un botón que "avisa al docente" automáticamente:** en el
> milestone 1 no hay un canal directo alumno→docente. El subsistema de avisos
> tiene comentarios (visibles para toda la cátedra) y el material puede no
> estar adjunto a ningún aviso. Por eso la acción principal es **copiar el
> mensaje** (el alumno lo manda por donde pueda), y las acciones secundarias
> son el comentario en el aviso y la apertura del original. Cuando exista
> Kitoru-Server, esta acción se convierte en una notificación real al
> docente; el texto pre-armado ya está.

### 7.4 Del lado del docente (anotado, no es esta pantalla)

El fix real es del lado docente: aplicar OCR al subir, o subir un `.md`. La
pantalla de materiales del docente (fuera de alcance) debería mostrar el mismo
estado derivado (`extractionStatus`) para que el docente vea *antes* de
publicar que su PDF no se puede leer. Es el mismo enum: no se duplica la
lógica.

---

## 8. Trazabilidad

### 8.1 Ida: de la respuesta al material (ya existe, se completa)

El chat ya guarda `ChatMessage.sources` (los labels `"cátedra: Unidad 1"`) y el
verificador produce el distintivo de citas. Lo que se agrega es `sourceRefs`,
que permite navegar al **material y al fragmento exacto**:

```
┌──────────────────────────────────────────────────────────────┐
│ …la función de evaluación heurística ordena soluciones [1]…  │
│                                              [Citas verificadas (1)]│
│ Fuentes:                                                       │
│   [1] Unidad 1 — Búsqueda heurística · Conceptos clave   →   │  ← clickeable
└──────────────────────────────────────────────────────────────┘
```

Al hacer clic en `[1]`:

1. Se navega a la pantalla de Materiales.
2. Se selecciona el material (`sourceRef.materialId`).
3. Se hace scroll hasta `sourceRef.start` y se resalta el fragmento.

### 8.2 Vuelta: del material a las respuestas

Es el camino que el enunciado pide y el que **no** existe todavía.

**Decisión de privacidad: el alumno sólo ve sus propias respuestas.** Los chats
de otros alumnos no se tocan (aunque el docente los vea según la matriz de
visibilidad de `CONTEXTO.md`): lo que el modelo le respondió a otro compañero
no es información del material, es información de otra persona.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialUsage.kt
package kitoru.domain

/**
 * Camino inverso: en qué respuestas **mías** se usó este material.
 *
 * Sólo se consideran los mensajes del alumno indicado: el material es
 * información de la cátedra, pero las conversaciones son personales.
 */
object MaterialUsage {

    /** Cómo se encontró la coincidencia. */
    enum class MatchKind {
        /** Por `sourceRefs` (exacto). */
        EXACT,
        /** Por el label de la fuente (mensajes viejos, sin `sourceRefs`). */
        BY_LABEL,
    }

    /** Una respuesta que usó el material. */
    data class Entry(
        val messageId: String,
        val threadId: String,
        val threadTitle: String,
        /** La pregunta del alumno que la disparó. */
        val question: String = "",
        /** La respuesta del modelo (recortada para la lista). */
        val answer: String = "",
        val createdAt: Long = 0L,
        /** Offset del fragmento usado, si se pudo resolver (-1 si no). */
        val fragmentStart: Int = -1,
        val fragmentEnd: Int = -1,
        /** Cómo se encontró. */
        val matchedBy: MatchKind = MatchKind.EXACT,
    )

    /**
     * Busca las respuestas del alumno que usaron el material.
     *
     * @param messages todos los mensajes de los hilos del alumno
     * @param threads los hilos del alumno (para el título y para filtrar)
     */
    fun find(
        material: Material,
        messages: List<ChatMessage>,
        threads: List<ChatThread>,
        studentId: String,
    ): List<Entry> {
        val misHilos = threads.filter { it.authorId == studentId }.associateBy { it.id }
        val label = "cátedra: ${material.title}"
        val out = mutableListOf<Entry>()

        for (msg in messages) {
            if (!msg.isAssistant) continue
            if (misHilos[msg.threadId] == null) continue       // sólo mis hilos

            val ref = msg.sourceRefs.firstOrNull { it.materialId == material.id }
            val porLabel = ref == null &&
                msg.sources.any { it == label || it.endsWith(": ${material.title}") }

            if (ref == null && !porLabel) continue

            out += Entry(
                messageId = msg.id,
                threadId = msg.threadId,
                threadTitle = misHilos[msg.threadId]?.title.orEmpty(),
                question = questionBefore(messages, msg),
                answer = msg.text,
                createdAt = msg.createdAt,
                fragmentStart = ref?.start ?: -1,
                fragmentEnd = ref?.end ?: -1,
                matchedBy = if (ref != null) MatchKind.EXACT else MatchKind.BY_LABEL,
            )
        }
        return out.sortedByDescending { it.createdAt }
    }

    /** El mensaje del alumno que precede a esta respuesta (en el mismo hilo). */
    private fun questionBefore(messages: List<ChatMessage>, answer: ChatMessage): String {
        val delHilo = messages.filter { it.threadId == answer.threadId && it.createdAt <= answer.createdAt }
        return delHilo.lastOrNull { it.isUser }?.text.orEmpty()
    }
}
```

**Dónde vive la consulta:** `MessageRepository.listForCourse(courseId)` ya
existe, y `ThreadRepository.listForUser(courseId, userId)` da los hilos del
alumno. El ViewModel cruza ambos y llama a `MaterialUsage.find`. Para el
milestone 1 (archivos JSON) alcanza; con Kitoru-Server, la consulta va al
servidor y se hace lo mismo pero filtrando por `authorId` en el servidor
(**el filtro por autor tiene que estar en el servidor**, no sólo en la UI: si
no, el cliente descarga conversaciones ajenas y las filtra después).

### 8.3 La UI del camino inverso

En el detalle del material, una tarjeta:

```
┌──────────────────────────────────────────────────────────────────┐
│ 🕘 Usado en tus respuestas (3)                                  │
│                                                                  │
│ ▸ "explicame la heurística de la unidad 1" · 14/10              │
│   …la función de evaluación heurística ordena soluciones de…    │
│ ▸ "diferencia entre A* y la búsqueda en anchura" · 12/10        │
│   …en la búsqueda en anchura se expanden todos los nodos…       │
└──────────────────────────────────────────────────────────────────┘
```

- Cada fila: la **pregunta** del alumno, la fecha y un fragmento de la
  respuesta.
- Al hacer clic: se abre el chat en ese hilo y **se hace scroll hasta el
  mensaje** (el `ChatScreen` acepta un `messageId` a destacar).
- Si no hay respuestas: la tarjeta no se muestra (o muestra "Todavía no
  preguntaste nada sobre este material").

**Y a nivel de fragmento**, dentro de la vista de texto:

> Este fragmento se usó en 2 respuestas tuyas.

Eso sale de cruzar los `sourceRefs` con el `start`/`end` del fragmento. El
criterio de coincidencia: `ref.start` dentro de `[start, end]` (con una
tolerancia de ±50 caracteres por el solapamiento del chunker).

### 8.4 El resumen en la lista

En la lista de materiales, un contador chico al final de cada fila:

```
📄 Unidad 1 — Búsqueda heurística      🕘 3
```

que es el número de respuestas del alumno que usaron ese material. Es el
"camino inverso" resumido: el alumno ve de entrada qué material es el que
más usó, sin abrirlo.

---

## 9. Etiquetas y unidades

### 9.1 Hoy las unidades son las etiquetas

El dominio **no tiene** entidad `Unit` (ver `teacher/04-prompt.md` §4.4: las
unidades del prompt salen de "`tags` distintas de los materiales"). La
pantalla de materiales usa la misma fuente, así que el alumno y la IA ven la
misma taxonomía:

- `Material.tags` = `["Unidad-1", "Parcial-1"]`.
- La cátedra sube un material por unidad, con la etiqueta de la unidad.
- El filtro por unidad de la pantalla de materiales y el `{unidades}` del
  prompt del docente salen de los mismos tags.

### 9.2 Detección de "unidad"

Una etiqueta es **unidad** si su forma es la que usa la cátedra:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialUnits.kt
package kitoru.ui.student.material

/**
 * Detección de unidades a partir de las etiquetas.
 *
 * Convención de la cátedra: "Unidad 1", "Unidad 2", "Unidad 10"… (también
 * "Unidad-1", "u1", "UNIDAD 3"). Todo lo que no matchea es una etiqueta
 * suelta.
 */
object MaterialUnits {

    private val UNIDAD = Regex("^\\s*unidad[\\s\\-_]*(\\d{1,2})\\s*$", RegexOption.IGNORE_CASE)

    /** ¿Esta etiqueta es una unidad? */
    fun isUnit(tag: String): Boolean = UNIDAD.matches(tag)

    /** El número de la unidad, si lo es (-1 si no). */
    fun number(tag: String): Int =
        UNIDAD.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1

    /**
     * Orden de las etiquetas: primero las unidades (por número), después el
     * resto (alfabético). Las que se repiten van arriba.
     */
    fun sort(tags: List<String>): List<String> =
        tags.sortedWith(
            compareBy(
                { if (isUnit(it)) 0 else 1 },
                { if (isUnit(it)) number(it) else 0 },
                { it.lowercase() },
            )
        )
}
```

> La convención es una heurística, no un contrato. Si la cátedra usa
> "Tema 1", "Tema 2", el mismo código los ordena por el número si se le
> cambia la regex. La detección real de unidades (entidad `Unit` con orden,
> nombres y fechas) es una deuda conocida: cuando exista, la pantalla pasa a
> leer `course.units` y el resto de la UI no cambia.

### 9.3 La barra de filtros

Debajo del buscador:

```
[ Todos ] [ Documento ] [ Diapositivas ] [ Apuntes ]     ← filtro por tipo
[ Unidad 1 (4) ] [ Unidad 2 (6) ] [ Parcial 1 (2) ]       ← filtro por etiqueta
Ordenar: Título ↑ / Subido ↓ / Tamaño ↓ / Relevancia        ← sólo en modo búsqueda
```

Reglas:

- **Dentro de un grupo, los filtros son OR** (materiales con `Unidad 1` **o**
  `Parcial-1`).
- **Entre grupos, son AND** (tipo `Documento` **y** `Unidad 1`).
- Cada chip muestra el **conteo** de materiales que matchean *con el resto de
  los filtros aplicados* (así el alumno ve qué va a encontrar antes de
  apretar).
- "Todos" limpia los filtros de tipo y de etiqueta.
- Los chips con `0` se muestran deshabilitados (así el alumno ve que existe
  la unidad pero no hay material).

### 9.4 Agrupar por unidad

En vez de una lista plana, el alumno puede agrupar: la lista se convierte en
secciones con encabezado pegajoso:

```
▸ Unidad 1 — Búsqueda heurística
    📄 Unidad 1 — Búsqueda heurística
    📄 Diapositivas — Clase 1
▸ Unidad 2 — Redes neuronales
    📄 Unidad 2 — Redes neuronales
▸ Parcial 1
    📄 Guide — Parcial 1
```

La opción de agrupar vive en el mismo menú de "Ordenar":

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialGrouping.kt
package kitoru.ui.student.material

/** Cómo se agrupan los materiales en la lista. */
sealed interface MaterialGrouping {
    /** Lista plana. */
    data object None : MaterialGrouping
    /** Por unidad (etiqueta que matchea `MaterialUnits.isUnit`). */
    data object ByUnit : MaterialGrouping
    /** Por una etiqueta concreta. */
    data class ByTag(val tag: String) : MaterialGrouping
}
```

Los materiales **sin etiqueta de unidad** van a una sección final, "Otros".

---

## 10. Nombres de archivos, tipos y composables

> El módulo `:ui` **ya está construido** (`ui/build.gradle.kts`, con Compose
> Multiplatform, `kitoru.ui.components` y las costuras de plataforma en
> `kitoru.ui.platform`). Las rutas de abajo son la propuesta de estructura para
> esta pantalla.

### 10.1 `:core` — puro, testeable, sin Compose

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/MaterialStatus.kt` | `ExtractionStatus`, `Material.extractionStatus`, `Material.isSearchable` |
| `core/src/commonMain/kotlin/kitoru/domain/MaterialUsage.kt` | `MaterialUsage` (+ `Entry`, `MatchKind`) |
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` (modificar) | `MaterialSourceRef`, `ChatMessage.sourceRefs` |
| `core/src/commonMain/kotlin/kitoru/platform/Text.kt` (modificar) | `expect fun stripDiacriticsWithMap(text): Pair<String, IntArray>` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/Text.jvm.kt` (modificar) | su `actual` (en `jvmCommonMain`, lo comparten desktop y Android) |
| `core/src/commonMain/kotlin/kitoru/platform/MaterialPaths.kt` | `MaterialPaths.courseDir`, `MaterialPaths.original` |

Tests (`core/src/commonTest`):

| Test | Qué cubre |
|---|---|
| `MaterialStatusTest.kt` | `extractionStatus` para texto completo / parcial / vacío / enlace |
| `MaterialUsageTest.kt` | el camino inverso: match por `sourceRefs`, fallback por label, y que **no** devuelva mensajes de otros alumnos |


### 10.2 `:backend` — RAG y búsqueda

| Archivo | Contenido |
|---|---|
| `backend/src/commonMain/kotlin/rag/retrieval/Models.kt` (modificar) | `Doc.ref`, `Chunk.ref/start/end` |
| `backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt` (modificar) | `CourseMaterialSource` pasa `ref = material.id` |
| `backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt` (modificar) | `Chunker.splitWithOffsets(doc, size, overlap)` (offsets sobre `doc.text`) |
| `backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt` (modificar) | `vocabulary`, `allChunks`, `documentFrequency`, `inverseDocumentFrequency`, `searchTerms(terms, k)`, `searchDetailed(query, k)`; `search(query, k)` se mantiene igual |
| `backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt` (modificar) | `vocabulary`, `allChunks`, `search(query, k, materialId)` |
| `backend/src/commonMain/kotlin/rag/retrieval/MaterialSearch.kt` | `MaterialSearch.search(...)`, `SearchScope`, `SearchOutcome`, `ResolvedTerms` |
| `backend/src/commonMain/kotlin/rag/retrieval/TextHighlight.kt` | `TextHighlight.findRanges`, `TextHighlight.snippet` |
| `backend/src/commonMain/kotlin/rag/retrieval/MaterialSourceRefs.kt` | `Chunk.toMaterialRef()`, `List<Chunk>.toMaterialRefs()` |
| `backend/src/commonMain/kotlin/rag/engine/RagEngine.kt` (modificar) | `prepare(question, history, useDocs, pinned)` |
| `backend/src/commonMain/kotlin/rag/engine/PromptPayload.kt` | `PromptPayload.formatContext(hits)`, `PromptPayload.buildMessages(...)` — **compartido** con la pantalla del docente |

Tests (`backend/src/desktopTest`):

| Test | Qué cubre |
|---|---|
| `MaterialSearchTest.kt` | la pregunta en lenguaje natural, la resolución por prefijo, el barrido por subcadena, el tope por material, el filtro por alcance, los 0 resultados |
| `TextHighlightTest.kt` | resaltado con y sin acentos, límites de palabra, fusión de rangos, el snippet |
| `ChunkerOffsetTest.kt` | que los offsets del chunk apunten al texto correcto del material (con acentos y con `trim`) |
| `RagEnginePinnedTest.kt` | que el fragmento fijado quede como `[1]` y que el verificador lo valide |

### 10.3 `:ui` — la pantalla

| Archivo | Composables / tipos |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialsScreen.kt` | `MaterialsScreen(course, onAsk, ...)`, `MaterialsRoute(...)` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialsState.kt` | `MaterialsState`, `MaterialsViewModel`, `MaterialFilters`, `MaterialSort`, `BrowseMode`, `MaterialDetail` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialListPane.kt` | `MaterialListPane`, `MaterialListItem`, `MaterialKindBadge`, `MaterialTagRow` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialDetailPane.kt` | `MaterialDetailPane`, `MaterialHeader`, `MaterialTextView`, `MaterialFragmentsView`, `MaterialOriginalView` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialSearchPane.kt` | `MaterialSearchPane`, `SearchResultItem`, `SearchEmptyState` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialFilterBar.kt` | `MaterialFilterBar`, `TypeFilterChips`, `TagFilterChips`, `MaterialSortMenu` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialWarningCard.kt` | `MaterialWarningCard` (los tres estados de extracción) |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialAskSheet.kt` | `MaterialAskSheet` (el diálogo de "preguntar sobre esto") |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialUsagePanel.kt` | `MaterialUsagePanel`, `MaterialUsageItem` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialUnits.kt` | `MaterialUnits` (detección y orden de unidades) |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialGrouping.kt` | `MaterialGrouping` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialFormatters.kt` | `formatBytes`, `formatShortDate`, `formatRelativeTime`, `kindIcon` |
| `ui/src/commonMain/kotlin/kitoru/ui/student/chat/PendingAsk.kt` | `PendingAsk` |
| `ui/src/commonMain/kotlin/kitoru/ui/components/HighlightedText.kt` | `HighlightedText(text, ranges)` — composable genérico de resaltado (lo reusarían el chat y las respuestas) |

**Costuras de plataforma** (las que ya existen o se agregan al lado de las
actuales):

| Archivo | Acción |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/platform/FileSaver.kt` (modificar) | agregar `saveFile(suggestedName, source: FilePath, mimeType)` |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FileSaver.desktop.kt` (modificar) | `actual` de `saveFile` (`FileDialog` + copia) |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FileSaver.android.kt` (modificar) | `actual` de `saveFile` (SAF, escribe bytes) + launcher con mime `"*/*"` |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/FileOpener.kt` (nuevo) | `FileOpener` + `expect fun rememberFileOpener()` |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FileOpener.desktop.kt` (nuevo) | `Desktop.getDesktop().open` |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FileOpener.android.kt` (nuevo) | `FileProvider` + `ACTION_VIEW` |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/MimeTypes.kt` (nuevo) | `mimeTypeFor(extension)` |
| `android/src/main/AndroidManifest.xml` + `android/src/main/res/xml/file_paths.xml` | el `FileProvider` |

> **Sobre el render del texto:** `:ui` ya tiene un renderer de Markdown
> (`kitoru.ui.components.MarkdownText`, con `buildInlineAnnotated` para los
> inline). El texto extraído de un material **no** se renderiza con ese
> renderer: el markdown no tiene offsets, así que no se puede resaltar una
> coincidencia ni hacer scroll a un fragmento. El texto del material se
> renderiza como **texto plano por bloques**, con un `AnnotatedString` por
> bloque y los rangos resaltados (`HighlightedText`). Si más adelante se
> quiere Markdown **con** resaltado, hay que portarle al renderer de
> `Markdown.kt` los offsets por bloque: es un trabajo aparte, no un detalle.


### 10.4 El ViewModel

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialsState.kt
data class MaterialsState(
    val courseId: String = "",
    val loading: Boolean = true,
    /** Estado del índice ("Índice: 12 documentos · 318 fragmentos"). */
    val indexStatus: String = "",
    /** Materiales visibles de la cátedra. */
    val materials: List<Material> = emptyList(),
    /** Nº de fragmentos por material (id → cantidad). */
    val fragmentCounts: Map<String, Int> = emptyMap(),
    /** Filtros aplicados. */
    val filters: MaterialFilters = MaterialFilters(),
    /** Agrupación de la lista. */
    val grouping: MaterialGrouping = MaterialGrouping.None,
    /** Id del material seleccionado (panel derecho en escritorio). */
    val selectedId: String = "",
    /** Modo del panel derecho. */
    val mode: BrowseMode = BrowseMode.Browse,
    /** Texto del buscador. */
    val query: String = "",
    /** Estado de la búsqueda. */
    val search: SearchState = SearchState.Idle,
    /** Materiales filtrados (lo que se ve en la lista). */
    val visible: List<Material> = emptyList(),
    /** Menciones de uso del material seleccionado. */
    val usage: List<MaterialUsage.Entry> = emptyList(),
)

/** Modo del panel derecho. */
sealed interface BrowseMode {
    /** Se ve el detalle del material seleccionado. */
    data object Browse : BrowseMode
    /** Se ven los resultados de la búsqueda. */
    data object Search : BrowseMode
}

/** Filtros de la lista. */
data class MaterialFilters(
    val kinds: Set<MaterialKind> = emptySet(),
    val tags: Set<String> = emptySet(),
    val query: String = "",           // filtro de metadatos (título/tags)
)

/** Estado de la búsqueda full-text. */
sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Done(val outcome: SearchOutcome) : SearchState
}
```

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/material/MaterialsViewModel.kt
class MaterialsViewModel(
    private val materialRepository: MaterialRepository,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val knowledgeBase: KnowledgeBase,
    private val courseId: String,
    private val studentId: String,
) {

    val state: StateFlow<MaterialsState> = ...

    /** Carga los materiales visibles y sincroniza el índice. */
    fun load() = viewModelScope.launch {
        val todos = materialRepository.listForCourse(courseId)
        val visibles = todos.filter { it.visible }
        knowledgeBase.sources = listOf(CourseMaterialSource(visibles))
        knowledgeBase.sync { status -> /* barra de progreso */ }
        val counts = knowledgeBase.allChunks.groupingBy { it.ref }.eachCount()
        _state.update { it.copy(materials = visibles, fragmentCounts = counts, loading = false) }
        recalcular()
    }

    /** Filtra la lista (metadatos). */
    fun setFilters(filters: MaterialFilters) { ... }

    /** Búsqueda full-text (BM25). */
    fun search(query: String) = viewModelScope.launch {
        _state.update { it.copy(query = query, mode = BrowseMode.Search, search = SearchState.Searching) }
        val outcome = withContext(kitoru.platform.ioDispatcher) {
            MaterialSearch.search(knowledgeBase, query, scope)
        }
        _state.update { it.copy(search = SearchState.Done(outcome)) }
    }

    /** Selecciona un material y calcula el camino inverso (uso). */
    fun select(materialId: String) = viewModelScope.launch { ... }

    /** Crea el contexto pendiente para el chat. */
    fun askAbout(chunk: Chunk, question: String): PendingAsk = ...
}
```

---

## 11. Resumen de los cambios propuestos en el código existente

| # | Módulo | Cambio | Motivo |
|---|---|---|---|
| 1 | `:core` | `MaterialSourceRef` + `ChatMessage.sourceRefs` | Para poder volver del mensaje al material **y** al fragmento. `sources` (labels) queda como estaba. |
| 2 | `:core` | `ExtractionStatus` + `Material.extractionStatus` | El estado de extracción se decide en un solo lugar, compartido por alumno y docente. |
| 3 | `:core` | `stripDiacriticsWithMap` (`expect/actual`) | Resaltar "Definición" cuando el alumno escribió "definicion". |
| 4 | `:core` | `MaterialPaths` | La ruta del original se resuelve en un solo lugar. |
| 5 | `:ui` | `FileSaver.saveFile(...)` + `FileOpener` + `MimeTypes` | Abrir con la app por defecto (desktop: `Desktop.open`; Android: `FileProvider` + `ACTION_VIEW`) y **guardar una copia** (Android: SAF, que ya está en `FileSaver`). Se **extiende** la costura que ya existe, no se crea otra. |
| 6 | `:backend` | `Doc.ref`, `Chunk.ref/start/end` (con defaults) | Saber de qué material es cada fragmento y dónde está dentro de él. |
| 7 | `:backend` | `Chunker.splitWithOffsets` | Los offsets son lo que permite "ver en el material" y hacer scroll. |
| 8 | `:backend` | `CourseMaterialSource` pasa `ref = material.id` | Sin esto no hay forma de volver del fragmento al material. |
| 9 | `:backend` | `Bm25Index.vocabulary/allChunks/searchTerms/documentFrequency` | Resolver términos por prefijo y puntuar el barrido por subcadena. `search(query, k)` **no cambia**. |
| 10 | `:backend` | `KnowledgeBase.vocabulary/allChunks/search` | La fachada que usa la pantalla. |
| 11 | `:backend` | `MaterialSearch` + `SearchScope` + `SearchOutcome` | El algoritmo de búsqueda completo, determinista y testeable. |
| 12 | `:backend` | `TextHighlight.findRanges/snippet` | Resaltado con offsets reales. |
| 13 | `:backend` | `PromptPayload` (extraído) + `RagEngine.prepare(..., pinned)` | El `CONTEXTO` se arma en un solo lugar; el fragmento fijado entra **primero**. |
| 14 | `:backend` | `Chunk.toMaterialRef()` / `toMaterialRefs()` | Guardar la referencia completa en el mensaje. |
| 15 | `:core` | `MaterialUsage.find` | El camino inverso (material → respuestas), filtrando por autor. |

**Nada de esto cambia el comportamiento del chat para el alumno que no usa la
pantalla de materiales**: los parámetros nuevos tienen defaults, `search(query, k)`
mantiene su firma y `prepare(...)` se llama igual desde el resto del código.

---

## 12. Casos borde (resumen)

| Caso | Qué hace la pantalla |
|---|---|
| Cátedra sin materiales | Estado vacío: "Esta cátedra todavía no tiene material." |
| Material con texto pero sin archivo original | Se lee el texto; "Abrir" deshabilitado con el motivo. |
| Material oculto | No aparece, no se busca, no se abre. Si un mensaje viejo lo citaba, sale como "material oculto (ya no está disponible)". |
| Material de tipo `LINK` | Se abre en el navegador; sólo es buscable si tiene `extractedText`. |
| PDF escaneado | Estado vacío con las dos acciones (§7.3). El chat, al preguntar, no lo recupera (no tiene texto). |
| Búsqueda sin resultados | Mensaje con el motivo + 3 sugerencias. |
| Búsqueda con acentos distintos | Se encuentra (comparación sin diacríticos). |
| Búsqueda de una palabra de una letra ("C") | Barrido por subcadena. |
| Búsqueda de una forma que no está ("gradiente" vs "gradientes") | Resolución por prefijo; el resultado lo indica. |
| 40 coincidencias en un solo PDF | Se muestran 5 + "40 coincidencias en este material". |
| Un fragmento fijado y `topK = 1` | El fijado es `[1]` y no se recupera nada más (`k = 0`). |
| El alumno saca el contexto fijado | Se manda la pregunta normal, con retrieval por BM25. |
| El modelo no cita `[1]` | Se muestra el distintivo de citas tal cual ("Sin citas"). No se reintenta. |
| El material desaparece mientras está abierto | El detalle pasa al estado "material no disponible". |
| Un mensaje guardado antes de `sourceRefs` | El camino inverso lo encuentra por label (`BY_LABEL`) y lo marca como tal. |
| La respuesta usa un material oculto (mensaje viejo) | Se muestra "material oculto", sin texto y sin acceso. |
| La búsqueda con 1 carácter y 10.000 fragmentos | El barrido lineal puede tardar: se muestra el progreso y se limita a los primeros 50. |
| Un material con 0 etiquetas | Va a la sección "Otros" cuando se agrupa por unidad. |

---

## 13. Criterios de aceptación

1. La lista muestra, por material: **tipo, título, tamaño, nº de fragmentos,
   fecha, etiquetas** y el **estado de extracción** cuando no es `OK`.
2. Los materiales con `visible = false` **no aparecen** en la lista, **no
   salen** en los resultados de búsqueda y **no son recuperables** por el RAG.
3. La búsqueda full-text devuelve **fragmentos**, no materiales, con el
   **término resaltado** en el snippet, y el resultado dice de qué material y
   de qué fragmento se trata.
4. La búsqueda funciona con **una pregunta en lenguaje natural**
   ("¿cómo se calcula el gradiente?") y con **términos sueltos**
   ("backpropagation"), y encuentra las variantes morfológicas
   ("gradiente" → "gradientes") por prefijo.
5. "Ver en el material" abre el material y **hace scroll al fragmento
   exacto**, con el término resaltado.
6. "Preguntar a la IA sobre esto" abre el chat con el **fragmento fijado**; el
   prompt que llega al modelo tiene ese fragmento **como `[1]`** en el bloque
   `CONTEXTO`, y el mensaje del usuario incluye la nota de "seleccionado por el
   estudiante".
7. La respuesta guarda las **referencias completas** al material
   (`sourceRefs`), y la fuente `[1]` del chat es clickeable y lleva al
   fragmento.
8. El camino inverso ("usado en tus respuestas") muestra **sólo** las
   conversaciones del propio alumno, y desde un material se llega al mensaje
   exacto.
9. Un material sin texto extraído muestra el aviso explicando **por qué** y
   ofrece **abrir el original** y **pedir una versión accesible** (con el
   mensaje pre-armado).
10. En escritorio, "Abrir" abre el archivo con la app por defecto del sistema;
    en Android, lo abre con `FileProvider` + `ACTION_VIEW` y "Guardar una
    copia" usa SAF (`ACTION_CREATE_DOCUMENT`).
11. El texto del material se renderiza **resaltando** las coincidencias y
    respetando los **offsets** del fragmento (el scroll lands en el lugar
    correcto).
12. La lista se puede filtrar por tipo y por etiqueta (que funcionan como
    unidades), con conteos por etiqueta, y agrupar por unidad.
