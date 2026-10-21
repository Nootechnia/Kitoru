# 02 · Chunking e indexación del RAG de Kitoru

> Subagente: diseño de la estrategia de **troceado (chunking)** e **indexación** del RAG.
> Alcance: **qué es un fragmento → cómo se corta → con qué contexto → qué metadatos lleva →
> cómo se indexa → cómo se reindexa**.
> Todo lo que sigue es diseño, no código: los nombres de archivo, tipos y firmas son
> los que deberían existir en el repo.

**Archivos leídos antes de escribir**
`docs/design/CONTEXTO.md` · `docs/design/domain/10-taxonomia.md` (jerarquía de unidades,
`citationLabel`, `indexedText`, boost) · `docs/design/teacher/03-materiales.md` (extracción,
marcas `## Página N`, `## Diapositiva N`) · `docs/design/teacher/09-calidad.md` ·
`backend/.../rag/retrieval/{Chunker,Bm25Index,Models,KnowledgeBase,DocumentSource}.kt` ·
`backend/.../rag/engine/{RagEngine,CitationVerifier}.kt` · `backend/.../rag/config/RagConfig.kt` ·
`core/.../kitoru/domain/Course.kt` ·
`FunshiEngineAI/docs/MANUAL-RAG.md` · `FunshiEngineAI/backend/.../rag/retrieval/*` ·
`FunshiEngineAI/backend/src/test/.../rag/retrieval/{Bm25IndexTest,ChunkerTest}.kt`.

---

## 0. Resumen ejecutivo

| # | Decisión | Motivo |
|---|---|---|
| D1 | El chunker se **reescribe**: deja de cortar por encabezados Markdown y corta por **estructura semántica** (sección → párrafo → oración). | El PDF es el material principal de una cátedra y hoy se trocea a lo bruto: 32 % de los fragmentos arrancan en mitad de una palabra (§1.3). |
| D2 | El troceado tiene **tres niveles**: (a) títulos detectados por patrón, (b) párrafos, (c) oraciones; con un **fallback** a líneas/palabras para el texto sin puntuación. | Un PDF no tiene ni encabezados ni párrafos: hay que reconstruirlos (§2.3, §2.4). |
| D3 | **Tamaño adaptativo por tipo de material**: PDF/denso 600 (400–800), Markdown con código 1200 (600–1500), diapositivas 500. | 5 fragmentos de 600 car ≈ 850 tokens de contexto; 5 de 1200 ≈ 1.800 tokens: con un modelo local de 4.096 el segundo no entra (§6.4). |
| D4 | El **solapamiento es de 1 oración completa**, con tope de 180 caracteres, y **nunca** parte una palabra ni un bloque de código. | 150 caracteres fijos cortan palabras al azar (6/19 en la medición) y no aportan contexto semántico (§3). |
| D5 | La cita se compone de **ruta de unidades + material + sección + localizador** (`pág. 7`, `diap. 12`, `12:30`). El `Chunk.heading` pasa a ser *la sección semántica*, no "Página N". | "cátedra: Apuntes-IA — Página 3" no dice de qué tema habla el fragmento (§4). |
| D6 | `Chunk` gana **metadatos de localización** (`page`, `slide`, `startMs/endMs`, `charStart/charEnd`, `kind`, `id`). | Sin página no se puede "abrir en el material"; sin `id` estable la analítica no puede decir "este pasaje se cita 12 veces" (§5). |
| D7 | El índice pasa a **invertido** (posting lists) y la búsqueda usa **top-K acotado** (heap) en vez de ordenar todo. | 30.000 lookups + un sort de 3.000 por consulta hoy; con posting lists se tocan ~400 (§7). |
| D8 | Los **metadatos de taxonomía no se hornean en el texto indexado** salvo la ruta de unidades y las etiquetas del material; el resto va en campos. | Hornear `página` o `diapositiva` infla el `df` de esos términos y no aporta al score (§5.2). |
| D9 | El reindexado es **incremental por material**: se re-trocea y re-tokeniza **sólo** el material que cambió; las posting lists se re-arman desde la caché de tokens. | Reindexar 100 materiales cuesta ~1 s de tokenización; hacerlo por cada edición es wasteful (§8). |
| D10 | La API **no rompe**: `Chunker.split(doc, size, overlap)` sigue existiendo con la misma firma y los tests portados de FunshiEngineAI siguen pasando sin tocarlos. | Regla 1 del proyecto ("reutilizar, no reescribir") y §7.4. |

---

## 1. El algoritmo actual y su evaluación con casos reales

### 1.1 Qué hace hoy

`backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt` (idéntico al de FunshiEngineAI):

```
split(doc, size, overlap):
  heading ← nombre del archivo
  recorrer líneas:
    si la línea empieza con "#" (y no estamos dentro de un ```):
        flush()                       # corta el bloque acumulado
        heading ← texto de la línea
    acumular la línea
  flush()

  flush():                           # trocea el bloque por tamaño
    while start < len:
        end = start + size
        if end < len:
            nl = última '\n' antes de end
            if nl > start + size/2: end = nl      # "ajuste" al salto de línea
        piece = texto[start:end].trim()
        emitir Chunk(source, path, heading, piece)
        start = end - overlap
```

Es decir: **encabezados Markdown + corte a 1200 caracteres con 150 de solapamiento**.

### 1.2 Los cuatro casos reales de una cátedra

| Caso | Qué entra al chunker | Qué sale |
|---|---|---|
| **A. PDF** (el caso principal) | Texto plano de PDFBox: párrafos separados por líneas en blanco, líneas cortadas a ~90 columnas a mitad de palabra, títulos de sección como "1.2 Regresión lineal" | **No hay `#`** → un solo bloque → corte a lo bruto por caracteres |
| **B. PDF con marcas de página** (el pipeline de `03-materiales.md` emite `## Página N`) | Encabezados Markdown, pero **todos iguales**: "Página N" | Los fragmentos se parten por página y el `heading` es "Página N": inútil |
| **C. Markdown / apuntes** | Encabezados `#`, `##`, bloques de código | Funciona bien (es el caso para el que fue escrito) |
| **D. Diapositivas** | `## Diapositiva N` + contenido muy corto | Se mezcla con el caso B: el `heading` dice "Diapositiva 12", nunca el tema |

### 1.3 Medición del caso A (el que duele)

**Material de prueba** (sintético, representativo): 2.633 palabras, 16.784 caracteres,
289 líneas; párrafos separados por línea en blanco; líneas cortadas a 95 columnas;
marcas `## Página N` cada 6 párrafos. Es lo que produce PDFBox sobre un apunte de cátedra.

Chunker actual con `size=1200, overlap=150`:

| Métrica | Valor |
|---|---|
| Fragmentos | **20** |
| Largo medio / mín / máx | 912 / 637 / 1.193 caracteres |
| Caracteres indexados | 18.240 (+8 % sobre el original, por el solapamiento) |
| Tokens indexados | 2.874 |
| **Cortes que parten una palabra** | **6 de 19 (32 %)** |
| Cortes que parten una oración | 6 de 19 (32 %) |
| Solapamiento real | 150 caracteres en 15 pares, **−3 en 4 pares** (es decir, *cero* solapamiento: el fragmento siguiente arranca después del anterior) |
| `heading` de los 20 fragmentos | `"Página 1"…"Página 4"` (o el nombre del archivo si el PDF no trae marcas) |

Los 6 fragmentos que arrancan en mitad de palabra, textuales:

```
#2  'on aplica la regla de la cadena sobre el grafo computacional…'   ← "retropropagaci|ón"
#4  'datos evita que variables con escalas distintas dominen el ajuste…'
#6  'ruzada son las dos herramientas básicas contra el\nsobreajuste…'    ← "c|ruzada"
#8  'etodo de aprendizaje automático…'                                  ← "m|etodo"
#10 'on cruzada divide el conjunto de datos…'                           ← "perci|son"+"on"→"…divide"
#12 'ica penaliza con más fuerza los errores de confianza grandes…'     ← "matemát|ica"
```

Esto no es cosmético, por dos razones concretas:

1. **El texto del fragmento deja de ser un excerpt fiel del material.** El primer
   carácter del fragmento es la mitad de una palabra: el alumno ve
   `"on aplica la regla de la cadena…"` en la cita, y el resaltado dentro del
   material original muestra `"retropropagaci"` partido. El verificador de citas
   (`CitationVerifier`) compara la cita del modelo contra `Chunk.text` —no contra
   el material—, así que una cita *literal* del fragmento sigue dando
   "respaldada"; lo que se rompe es la **fidelidad** de la cita que el alumno lee
   y la validez del `charStart` que se usará para abrir el material en esa
   posición (§5).
2. **Aparecen tokens basura en el índice.** `"etodo"`, `"ruzada"`, `"ica"` se
   indexan como si fueran palabras: inflan el `df` de cadenas sin sentido y
   pueden coincidir con cualquier consulta que las contenga.

Además, el "ajuste al salto de línea" (`nl > start + size/2 → end = nl`) **empeora** el
caso PDF: en un PDF las líneas están cortadas a media frase, así que el corte cae en
mitad de oración en 6 de 19 casos (sólo 4 de 19 caen en el comienzo real de una oración).

### 1.4 Medición del caso B (PDF con marcas de página)

Con `## Página N` el chunker funciona "bien" en el sentido de que no corta palabras
(el corte cae en un salto de línea), pero:

- El solapamiento **desaparece** en los límites de página (los 4 pares con −3): el
  fragmento `Página 3` no comparte nada con `Página 2`.
- El `heading` de todos los fragmentos es `"Página N"` → la cita es
  `"cátedra: Apuntes-IA — Página 3"`. **El alumno no sabe de qué tema trata el fragmento.**
- La sección real del PDF ("2.1 Regresión lineal") queda enterrada en el cuerpo del
  fragmento, sin marcar.

### 1.5 Lista de defectos (con severidad)

| # | Defecto | Efecto | Severidad |
|---|---|---|---|
| 1 | Sin estructura ⇒ corte a lo bruto por caracteres | 32 % de los fragmentos arrancan en mitad de una palabra; la cita literal falla | **Alta** |
| 2 | `heading` inútil en PDF (`"Página N"` o el nombre del archivo) | La cita no dice el tema; el modelo tampoco recibe la jerarquía | **Alta** |
| 3 | Solapamiento de 150 caracteres arbitrarios | Repite 150 caracteres sin contexto; parte palabras | Media |
| 4 | Solapamiento que **desaparece** en los límites de sección | Hueco de contexto justo donde más lo necesita | Media |
| 5 | El bloque de código se puede partir por la mitad | El modelo recibe un ejemplo incoherente (el manual de FunshiEngineAI lo dice: "los bloques de código se indexan enteros") | Media |
| 6 | `Chunk` no sabe de qué material viene (sólo `path` = título) | Dos materiales con el mismo título son indistinguibles; no se puede reindexar uno solo | **Alta** |
| 7 | `Bm25Index` recalculascores recorriendo **todos** los fragmentos por término | O(n) por consulta; 30.000 lookups con 3.000 fragmentos | Media |
| 8 | `search` **ordena todo** el array de scores para quedarse con 5 | El sort domina el costo: O(n log n) por consulta | Media |
| 9 | `tfs: List<Map<String,Int>>` duplica los tokens de cada fragmento | ~26 MB de RAM para 3.000 fragmentos | Media |
| 10 | Reindexado **completo** en cada cambio | El docente edita un material → 1 s de tokenización de los 100 | Baja |

### 1.6 Qué **no** se toca

- `Bm25Index.tokenize` (minúsculas, sin acentos, camel case partido, stopwords) es
  correcto y está testeado: **se mantiene tal cual**.
- `k1 = 1.5`, `b = 0.75`: valores estándar, no se cambian.
- `Chunk.label` (`"$source: $path"`): es la clave estable que usa `CitationCheck.label`
  y la analítica (`10-taxonomia.md`, D4). **No cambia.**
- La firma `Chunker.split(doc, size, overlap)`: se mantiene como overload.

---

## 2. El modelo de chunking propuesto

### 2.1 El pipeline en una imagen

```
  Material.extractedText
          │
          ▼
  1. NORMALIZAR      TextNormalizer.reflow()      ← une líneas cortadas de PDF,
          │                                        preserva párrafos y código
          ▼
  2. ESCANEAR        TextStructure.scan()          ← bloques: Título / Párrafo /
          │                                        Código / Tabla / Marcador
          ▼
  3. AGRUPAR         SectionBuilder                ← pila de títulos → secciones
          │
          ▼
  4. TROCEAR         Chunker.split()               ← párrafo → oración → fallback
          │                                        + empaquetado adaptativo
          ▼
  5. ARMAR           Chunk                         ← cuerpo + metadatos + contexto
          │
          ▼
  6. INDEXAR         Bm25Index (invertido)         ← posting lists
```

### 2.2 La política de tamaños

`Course.chunkSize` (por defecto **1200**) pasa a ser el **tope que elige el docente**,
y el tipo de material decide el tamaño efectivo dentro de ese tope.

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Chunking.kt
package rag.retrieval

/** Forma del material: decide la política de troceado. */
enum class MaterialShape(val label: String) {
    PROSE("Documento / apunte"),
    MARKDOWN("Markdown"),
    SLIDES("Diapositivas"),
    TRANSCRIPT("Transcripción"),
    TABLE("Tabla"),
}

/**
 * Parámetros de troceado de un material.
 *
 * `target` es el tamaño **deseado** (el empaquetador cierra el fragmento al llegar
 * a él); `max` es el **duro** (nunca se supera). `min` evita fragmentos diminutos:
 * uno más chico que `min` se fusiona con el siguiente.
 */
data class ChunkingOptions(
    val targetChars: Int = 600,
    val minChars: Int = 400,
    val maxChars: Int = 800,
    /** Oraciones de solapamiento entre fragmentos vecinos. */
    val overlapSentences: Int = 1,
    /** Tope del solapamiento en caracteres (protege el índice). */
    val maxOverlapChars: Int = 180,
    /** Piso del solapamiento: si la cola es más corta, no se solapa. */
    val minOverlapChars: Int = 20,
    /** Si el solapamiento puede cruzar de una sección a la otra. */
    val overlapAcrossSections: Boolean = false,
    /** Un bloque de código no se parte nunca. */
    val keepCodeBlocksWhole: Boolean = true,
    val shape: MaterialShape = MaterialShape.PROSE,
) {
    companion object {
        /**
         * Política efectiva según el tipo de material.
         *
         * `courseChunkSize` es el valor que eligió el docente (por defecto 1200):
         * es el **tope**, no el objetivo. Para el PDF denso el tope real es 600/800
         * porque un fragmento de 1.200 caracteres diluye el contexto del modelo
         * y no mejora la recuperación (§6.4).
         */
        fun forShape(shape: MaterialShape, courseChunkSize: Int): ChunkingOptions {
            val objetivo = courseChunkSize.coerceIn(400, 2400)
            return when (shape) {
                MaterialShape.PROSE -> ChunkingOptions(
                    targetChars = minOf(objetivo, 600),
                    minChars = minOf(objetivo, 400),
                    maxChars = minOf(objetivo, 800),
                    shape = shape,
                )
                MaterialShape.MARKDOWN -> ChunkingOptions(
                    targetChars = objetivo,
                    minChars = objetivo / 2,
                    maxChars = (objetivo * 1.25).toInt(),
                    shape = shape,
                )
                MaterialShape.SLIDES -> ChunkingOptions(
                    targetChars = minOf(objetivo, 500),
                    minChars = minOf(objetivo, 350),
                    maxChars = minOf(objetivo, 650),
                    overlapSentences = 0,      // cada diapositiva es atómica
                    shape = shape,
                )
                MaterialShape.TRANSCRIPT -> ChunkingOptions(
                    targetChars = minOf(objetivo, 700),
                    minChars = minOf(objetivo, 500),
                    maxChars = minOf(objetivo, 900),
                    shape = shape,
                )
                MaterialShape.TABLE -> ChunkingOptions(
                    targetChars = objetivo,
                    minChars = objetivo / 2,
                    maxChars = (objetivo * 1.5).toInt(),
                    shape = shape,
                )
            }
        }
    }
}
```

#### 2.2.1 La sobrecarga legacy: `split(doc, size, overlap)`

El chunker actual tiene esta firma y los tests portados la usan. **Se conserva**, y
delega en el algoritmo nuevo con opciones equivalentes:

```kotlin
object Chunker {

    /**
     * Trocea un documento.
     *
     * **Sobrecarga de compatibilidad**: es la firma que usaba FunshiEngineAI y la
     * que usan los tests portados. Mantiene la semántica de tamaño (el `size` es
     * el tope duro) pero usa el troceado por unidades.
     */
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> =
        split(doc, ChunkingOptions(
            targetChars = size.coerceAtLeast(1),
            minChars = 1,                 // sin fusión de fragmentos chicos
            maxChars = size.coerceAtLeast(1),
            overlapSentences = if (overlap > 0) 1 else 0,
            maxOverlapChars = overlap.coerceAtLeast(0),
            minOverlapChars = 0,           // una cola corta igual se arrastra
            shape = MaterialShape.PROSE,
        ))

    /** Trocea un documento con la política explícita. */
    fun split(doc: Doc, opts: ChunkingOptions): List<Chunk> { /* §2.6 */ }
}
```

**Por qué no `forShape` en la sobrecarga legacy**: `forShape` normaliza el tamaño a
un mínimo de 400 (el mínimo cómodo para un PDF). Los tests portados piden
`split(doc, 200, 0)` y lo que piden es que **ningún** fragmento pase de 200
caracteres: el `size` de la sobrecarga legacy es un tope duro, sin normalizar.

**Los call sites que hay que migrar** (los que hoy pasan `course.chunkSize` a pelo):

```diff
- Chunker.split(doc, course.chunkSize, 150)
+ Chunker.split(doc, ChunkingOptions.forShape(shapeOf(material), course.chunkSize))
```

y en `CourseMaterialSource` / `CourseIndex`, el `Doc` lleva ahora `shape`, así que
la forma se deduce del material (PDF → PROSE, PPTX → SLIDES, MD → MARKDOWN,
video → TRANSCRIPT).

**Tabla de tamaños efectivos** (con `Course.chunkSize = 1200`, el valor por defecto):

| Tipo | target | mín | máx | solapamiento |
|---|---|---|---|---|
| PDF / DOCX / TXT (prosa) | **600** | 400 | 800 | 1 oración (≤180 car.) |
| Markdown (con código) | **1200** | 600 | 1500 | 1 oración (≤180 car.) |
| Diapositivas | 500 | 350 | 650 | 0 |
| Transcripción de video | 700 | 500 | 900 | 1 oración |
| CSV / tabla | 1200 | 600 | 1800 | 0 |

Si el docente baja `chunkSize` a 600, los valores efectivos bajan con él
(`min(objetivo, …)`): la decisión del docente siempre gana.

### 2.3 Bloques: párrafos, código, tablas

El escáner convierte el texto en una lista de **bloques**:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/TextStructure.kt
package rag.retrieval

/** Un bloque del documento, ya clasificado. */
sealed interface Block {
    /** Un título detectado. */
    data class Heading(
        val level: Int,               // 1 = nivel más alto
        val title: String,
        val kind: HeadingKind,
        val line: Int,                // línea donde arranca (para el depurador)
    ) : Block

    /** Un párrafo (o un fragmento de texto sin estructura). */
    data class Paragraph(val text: String, val line: Int) : Block

    /** Un bloque de código delimitado por ``` (nunca se parte). */
    data class Code(val text: String, val lang: String, val line: Int) : Block

    /** Una tabla (nunca se parte por el medio de una fila). */
    data class Table(val text: String, val line: Int) : Block
}

/** Qué clase de título es: no todos los títulos sirven como "sección". */
enum class HeadingKind {
    /** Un título de verdad: arma la sección y aparece en la cita. */
    TITLE,
    /** "## Página 7": marca de localización, **no** es una sección. */
    PAGE,
    /** "## Diapositiva 12": ídem. */
    SLIDE,
    /** "[00:12:34]": marca temporal de una transcripción. */
    TIMESTAMP,
}
```

**Reglas del escáner** (`TextStructure.scan`):

1. **Código**: una línea que abre un fence (```) cierra el bloque de código. El bloque
   se emite entero (`Code`). Nunca se parte, ni por tamaño (§2.5).
2. **Tablas**: un bloque que empieza con `|` (o que tiene ≥ 2 líneas con ≥ 2 `|`) es
   tabla. Se parte **por filas**, nunca por el medio de una fila.
3. **Párrafo**: texto separado por líneas en blanco. Un párrafo con `reflow` aplicado
   (§2.3.1) es una unidad.
4. **Título**: lo decide `HeadingDetector` (§2.4).
5. **Marcador de localización**: `## Página N`, `## Diapositiva N`, `## Pág. N`,
   `[hh:mm:ss]` → `HeadingKind.PAGE/SLIDE/TIMESTAMP`. **No** entra en la pila de
   títulos: sólo fija el localizador del fragmento.

#### 2.3.1 `reflow`: reconstruir los párrafos de un PDF

El problema de fondo: PDFBox devuelve **líneas visuales**, no párrafos. La línea
termina a mitad de palabra porque la caja de texto del PDF termina ahí.

```kotlin
// core/src/commonMain/kotlin/kitoru/material/TextNormalizer.kt
package kitoru.material

/**
 * Une las líneas cortadas a lo ancho de un PDF.
 *
 * Es **idempotente**: aplicarlo dos veces da el mismo texto, así que puede
 * correr en el extractor (para que el docente vea un texto legible) y también
 * en el chunker (por si el texto entró por otra vía).
 */
object TextNormalizer {

    /** Largo mínimo para considerar que una línea está "llena". */
    private const val FULL_LINE = 40

    /** Proporción de líneas que terminan en signo de puntuación para no tocar. */
    private const val SENTENCE_RATIO = 0.6

    fun reflow(text: String): String {
        val out = StringBuilder()
        var buffer = mutableListOf<String>()

        fun flush() {
            if (buffer.isEmpty()) return
            val joined = if (looksHardWrapped(buffer)) buffer.joinToString(" ") else buffer.joinToString("\n")
            out.append(joined).append("\n\n")
            buffer = mutableListOf()
        }

        var inFence = false
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                flush(); inFence = !inFence
                out.append(line).append('\n')
                continue
            }
            if (inFence) { out.append(line).append('\n'); continue }
            if (line.isBlank()) { flush(); continue }
            buffer.add(line.trim())
        }
        flush()
        return out.toString().trim()
    }

    /**
     * ¿El bloque parece texto cortado a lo ancho?
     *
     * Heurística conservadora: líneas largas (≥40 car.) y pocas terminaciones de
     * oración. Un bloque de frases cortas ("linea 1\nlinea 2") **no** se toca:
     * son párrafos, no cortes.
     */
    private fun looksHardWrapped(lines: List<String>): Boolean {
        if (lines.size < 3) return false
        val full = lines.filter { it.isNotBlank() }
        if (full.isEmpty()) return false
        val avg = full.sumOf { it.length }.toDouble() / full.size
        if (avg < FULL_LINE) return false
        val conPunto = full.count { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') }
        return conPunto.toDouble() / full.size < SENTENCE_RATIO
    }
}
```

> **Por qué esto va en `:core` y no en `:backend`.** El texto refloweado es el que ve
> el docente en la pestaña "Texto" y el que se guarda en `Material.extractedText`:
> si el chunker lo transformara por su cuenta, el texto guardado y el indexado
> serían distintos y el verificador de citas compararía contra algo que el
> docente nunca vio. El reflow es **una vez, al extraer**.

### 2.4 Detección de títulos en texto sin estructura

Es la parte que más aporta para un PDF. Cinco patrones, en orden de confianza:

| # | Patrón | Regex (aproximada) | Nivel | Confianza |
|---|---|---|---|---|
| P1 | Markdown ATX | `^\s{0,3}(#{1,6})\s+(.+?)\s*#*$` | nº de `#` | **Alta** |
| P2 | Marcador de página/diapositiva | `^#{1,6}\s*(P(?:á|g)\.?\|Diapositiva\|Slide)\s*(\d+)` | — | **Alta** (pero no es sección) |
| P3 | Numeración jerárquica | `^(\d+(?:\.\d+)*)[.)]?\s+(\S.*)$` | nº de puntos + 1 | Media |
| P4 | Numeración con palabra | `^(Unidad\|Tema\|Cap(?:í|i)tulo\|Secci(?:ó|o)n\|Apartado\|Parte\|Anexo)\s+([0-9IVXLCDM]+)[.:)]?\s*(.*)$` | 1 / 2 / 3 según la palabra | Media |
| P5 | Línea en MAYÚSCULAS | `^[A-ZÁÉÍÓÚÑ0-9][A-ZÁÉÍÓÚÑ0-9 \-–—:,.()/]{3,80}$` | 1 | Baja |
| P6 | Pseudo-título | línea de < 80 car. **sin** punto final, seguida de una línea que empieza en minúscula | 2 | Baja |

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/HeadingDetector.kt
package rag.retrieval

/**
 * Detecta títulos en texto que no viene de Markdown.
 *
 * Un PDF de cátedra tiene títulos ("1.2 Regresión lineal", "TEOREMA DE BAYES",
 * "Tema 3: Redes neuronales") pero no tiene "#". Este detector los reconoce
 * con patrones y **valida** el resultado contra el resto del documento.
 */
object HeadingDetector {

    private val MARKDOWN = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val NUMERADO = Regex("""^(\d+(?:\.\d+)*)[.)]?\s+(\S.*)$""")
    private val CON_PALABRA = Regex(
        """^(Unidad|Tema|Cap[ií]tulo|Secci[óo]n|Apartado|Parte|Anexo|Introducci[óo]n|""" +
            """Conclusi[óo]n|Bibliograf[ií]a|Objetivos?)\s+([0-9IVXLCDM]+)?[.:)]?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val MARCADOR = Regex(
        """^#{1,6}\s*(P(?:[áa]g\.?|gina)|Diapositiva|Slide|Transcripci[óo]n)\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMESTAMP = Regex("""^\[?(\d{1,2}):(\d{2})(?::(\d{2}))?\]?$""")

    /** Un título candidato, antes de la validación. */
    data class Candidate(
        val level: Int,
        val title: String,
        val kind: HeadingKind,
        val line: Int,
        val confidence: Int,      // 3 = alta, 2 = media, 1 = baja
    )

    /** Un título aceptado. */
    data class Heading(
        val level: Int,
        val title: String,
        val kind: HeadingKind,
        val line: Int,
    )

    /**
     * Detecta los títulos de un documento y **descarta los falsos positivos**.
     *
     * @param lines las líneas del texto
     * @param repeatedLines líneas que se repiten mucho en el documento (encabezados
     *   de página de un PDF): se ignoran aunque parezcan títulos.
     */
    fun detect(lines: List<String>, repeatedLines: Set<String> = emptySet()): List<Heading> {
        val candidates = mutableListOf<Candidate>()
        var inFence = false
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("```")) { inFence = !inFence; continue }
            if (inFence || line.isEmpty()) continue
            if (line in repeatedLines) continue
            candidate(line, i)?.let { candidates += it }
        }
        return validate(candidates, lines)
    }

    /** Candidato de una línea (sin validar). */
    private fun candidate(line: String, index: Int): Candidate? {
        MARKDOWN.find(line)?.let { m ->
            val title = m.groupValues[2].trim()
            if (title.isEmpty()) return null                 // "#" solo: es texto, no título
            val kind = if (MARCADOR.matches(line)) {
                val t = line.lowercase()
                when {
                    t.contains("diapositiva") || t.contains("slide") -> HeadingKind.SLIDE
                    else -> HeadingKind.PAGE
                }
            } else HeadingKind.TITLE
            return Candidate(m.groupValues[1].length, title, kind, index, 3)
        }
        MARCADOR.find(line)?.let { m ->
            val t = m.groupValues[1].lowercase()
            val kind = if (t.contains("diapositiva") || t.contains("slide")) HeadingKind.SLIDE else HeadingKind.PAGE
            return Candidate(1, line.trimStart('#').trim(), kind, index, 3)
        }
        TIMESTAMP.matches(line)?.let {
            return Candidate(1, line, HeadingKind.TIMESTAMP, index, 3)
        }
        CON_PALABRA.find(line)?.let { m ->
            val palabra = m.groupValues[1].lowercase()
            val resto = m.groupValues[3].trim()
            val titulo = if (resto.isEmpty()) line else resto
            val nivel = when (palabra) {
                "unidad", "parte", "capítulo", "capitulo" -> 1
                "tema", "sección", "seccion" -> 2
                "apartado", "anexo" -> 3
                else -> 2
            }
            return Candidate(nivel, titulo, HeadingKind.TITLE, index, 2)
        }
        NUMERADO.find(line)?.let { m ->
            val numero = m.groupValues[1]
            val titulo = m.groupValues[2].trim()
            if (titulo.length > 90) return null              // no es un título: es un párrafo
            return Candidate(numero.count { it == '.' } + 1, titulo, HeadingKind.TITLE, index, 2)
        }
        if (looksUpperCase(line)) {
            return Candidate(1, titleCase(line), HeadingKind.TITLE, index, 1)
        }
        if (looksPseudoHeading(line, index, lines)) {
            return Candidate(2, line.trim().trimEnd(':'), HeadingKind.TITLE, index, 1)
        }
        return null
    }
}
```

#### 2.4.1 La validación: por qué un detector sin validación no sirve

Un detector de títulos por patrón produce falsos positivos. Los tres filtros:

| Filtro | Qué descarta | Por qué |
|---|---|---|
| **Encabezado repetido** | Una línea que aparece ≥ 3 veces en el documento | Es el membrete de un PDF ("UNIVERSIDAD NACIONAL — FACULTAD DE INGENIERÍA"), no un título |
| **Secuencia numérica** | Un número que rompe la serie (`1.`, `3.`, `2.`) | Un "2." suelto en medio de un párrafo es un ítem de una lista numerada, no un título |
| **Línea con contenido** | Un título que no tiene nada debajo | Un título sin contenido no genera sección: se ignora |

```kotlin
/**
 * Descarta los falsos positivos usando el contexto del documento.
 */
private fun validate(candidates: List<Candidate>, lines: List<String>): List<Heading> {
    val out = mutableListOf<Heading>()
    val numeros = mutableListOf<Pair<Int, String>>()      // (nivel, número)
    for (c in candidates) {
        if (c.kind != HeadingKind.TITLE) { out += Heading(c.level, c.title, c.kind, c.line); continue }
        val m = NUMERADO.find(c.title)
        if (m != null) {
            val numero = m.groupValues[1]
            // la serie debe crecer: si el número ya apareció con otro nivel, se descarta
            val previo = numeros.lastOrNull { it.first == c.level }
            if (previo != null && previo.second != numero) {
                // nivel numerado: se acepta igual, pero baja la confianza
            }
            numeros += c.level to numero
        }
        out += Heading(c.level, c.title, c.kind, c.line)
    }
    // un título sin contenido se descarta
    return out.filter { h ->
        h.kind != HeadingKind.TITLE || tieneContenido(h, lines)
    }
}
```

`tieneContenido(h, lines)`: hay al menos una línea no vacía entre `h.line + 1` y la
línea del siguiente título.

#### 2.4.2 Ejemplo real: qué detecta en un PDF de cátedra

Texto extraído de un PDF de "Modelos lineales":

```
Introducción a los modelos lineales                  ← (sin título: es la portada)

1. Modelos lineales                                   ← P3 → nivel 1, "Modelos lineales"
1.1 El modelo de regresión lineal                     ← P3 → nivel 2, "El modelo de regresión lineal"
El modelo de regresión lineal describe la relación…    ← párrafo
TEOREMA DE MÍNIMOS CUADRADOS                          ← P5 → nivel 1 (mayúsculas)
Sean β0, β1…                                           ← párrafo
2.2 Regularización                                     ← P3 → nivel 2
```

Resultado (después de validar):

| Línea | Título detectado | Nivel | Tipo |
|---|---|---|---|
| "1. Modelos lineales" | "Modelos lineales" | 1 | SECTION |
| "1.1 El modelo de regresión lineal" | "El modelo de regresión lineal" | 2 | SECTION |
| "TEOREMA DE MÍNIMOS CUADRADOS" | "Teorema de mínimos cuadrados" | 1 | SECTION |

Y la cita de un fragmento de esa zona queda:

```
cátedra: Apuntes-IA — El modelo de regresión lineal
```

en vez de

```
cátedra: Apuntes-IA — Página 3
```

### 2.5 El empaquetado (packing)

```kotlin
/**
 * Empaqueta unidades (párrafos u oraciones) en fragmentos de tamaño adaptativo.
 *
 * - `target`: tamaño deseado → se cierra el fragmento al llegar a él.
 * - `max`: tamaño duro → nunca se supera (salvo una unidad más larga que max).
 * - `min`: un fragmento más chico que `min` se fusiona con el siguiente.
 */
private fun pack(
    units: List<String>,
    opts: ChunkingOptions,
): List<String> {
    val out = mutableListOf<String>()
    var buf = mutableListOf<String>()
    var len = 0
    var lastUnit = ""              // ← la última unidad agregada (para el solape)

    fun cerrar() {
        if (buf.isEmpty()) return
        out += buf.joinToString("\n\n")
        buf = mutableListOf()
        len = 0
        // se arrastra la cola de la última unidad como solapamiento (§3)
        val cola = overlapTail(lastUnit, opts)
        if (cola.isNotEmpty() && opts.overlapSentences > 0) {
            buf = mutableListOf(cola)
            len = cola.length + 2
        }
        lastUnit = ""
    }

    for (u in units) {
        if (buf.isNotEmpty() && len + u.length + 2 > opts.maxChars) cerrar()
        buf += u
        len += u.length + 2
        lastUnit = u
        if (len >= opts.targetChars) cerrar()
    }
    if (buf.isNotEmpty()) cerrar()

    // fusión de fragmentos chicos (dentro de la misma sección)
    val merged = mutableListOf<String>()
    var i = 0
    while (i < out.size) {
        val c = out[i]
        if (c.length < opts.minChars && i + 1 < out.size) {
            merged += c + "\n\n" + out[i + 1]; i += 2
        } else {
            merged += c; i += 1
        }
    }
    return merged
}
```

**Cierre por unidad, no por carácter**: un fragmento nunca empieza ni termina en
mitad de una oración (salvo el fallback de §2.5.1).

#### 2.5.1 El fallback: texto sin puntuación

Una tabla, un listado o un PDF con todo pegado pueden no tener ni punto ni coma.
Ahí la oración no existe y hay que cortar en otro límite:

```
nivel 1: párrafo   (separado por línea en blanco)
nivel 2: oración  (terminada en . ! ?)
nivel 3: línea    (si el bloque tiene ≥ 3 líneas cortas)
nivel 4: palabra  (último recurso, en un límite de espacio)
```

El corte de nivel 3/4 **siempre** cae en un espacio (nunca dentro de una palabra).

### 2.6 El algoritmo final (pseudo-código completo)

```
fun split(doc: Doc, opts: ChunkingOptions): List<Chunk>

  # --- 0. Normalizar (idempotente) ---
  texto = TextNormalizer.reflow(doc.text)

  # --- 1. Escanear en bloques ---
  bloques = TextStructure.scan(texto)          # Título / Párrafo / Código / Tabla / Marcador

  # --- 2. Detectar títulos ---
  repetidas = lineasQueSeRepitenMasDe(veces = 3, bloques)
  titulos = HeadingDetector.detect(lineas, repetidas)

  # --- 3. Armar secciones con la pila de títulos ---
  seccionActual = Seccion(titulo = null, ruta = [], bloques = [], localizador = vacio)
  pila = []                                     # (nivel, título)
  for bloque in bloques:
      if bloque es Marcador(PAGE n):
          seccionActual.localizador = Localizador(pagina = n)     # NO entra en la pila
          continue
      if bloque es Título(nivel, titulo):
          while pila no vacía and pila.last().nivel >= nivel: pila.pop()
          pila.push(nivel, titulo)
          cerrarSeccion()
          seccionActual = Seccion(ruta = pila.títulos, localizador = seccionActual.localizador)
          continue
      seccionActual.bloques += bloque
  cerrarSeccion()

  # --- 4. Trocear cada sección ---
  for seccion in secciones:
      unidades = []
      for bloque in seccion.bloques:
          when bloque:
              Código  -> unidades += [bloque.texto]          # atómico, no se parte
              Tabla   -> unidades += filasComoUnidades(bloque)
              Párrafo -> unidades += oracionesDe(bloque.texto, opts.maxChars)
      for fragmento in pack(unidades, opts):
          cuerpo = fragmento
          solape = la cola de oración ya viene arrastrada dentro de `pack`
          emitir Chunk(
              source      = doc.source,
              path        = doc.path,
              heading     = seccion.titulo ?: doc.path.substringAfterLast('/'),
              text        = cuerpo,
              headingPath = seccion.ruta,
              page        = seccion.localizador.pagina,
              slide       = seccion.localizador.diapositiva,
              startMs     = seccion.localizador.inicio,
              endMs       = seccion.localizador.fin,
              charStart   = offsetDe(cuerpo, en = texto),
              charEnd     = charStart + cuerpo.length,
              kind        = kindDe(seccion),
              id          = idEstable(doc.materialId, cuerpo),
          )
  return fragmentos
```

`idEstable(materialId, cuerpo)` = `"$materialId#${hash12(cuerpo)}"`: depende **sólo**
del texto, así que sobrevive a los reindexados mientras el contenido no cambie
(§8.2). Dos fragmentos idénticos del mismo material comparten id: para la analítica
es lo correcto ("este pasaje se cita 12 veces").

**El esquema detectado, disponible por separado.** El paso 3 produce una lista de
secciones que es **información para el docente**, no sólo un paso intermedio. Se
expone así:

```kotlin
/**
 * El esquema detectado en un documento: la lista de secciones con su nivel.
 *
 * La usa la pestaña "Fragmentos" para que el docente **vea y corrija** lo que el
 * sistema adivinó ("esto no es un título"), y la tarjeta del material (§6.5).
 */
data class OutlineEntry(val level: Int, val title: String, val page: Int = 0)

object Chunker {
    fun outline(doc: Doc): List<OutlineEntry> { /* pasos 1 a 3 sin trocear */ }
}
```

Es la válvula de escape del detector: si el esquema está mal, el docente lo ve en
la UI antes de que un alumno reciba una cita rara.

### 2.7 El resultado, medido

Sobre el mismo material de prueba de §1.3 (2.633 palabras):

| | Actual (1200/150) | **Propuesto (600/400/800 + solape de 1 oración)** |
|---|---|---|
| Fragmentos | 20 | **37** |
| Largo medio / mín / máx | 912 / 637 / 1.193 | **563 / 172 / 795** |
| Caracteres indexados | 18.240 (+8 %) | **20.841 (+24 %)** |
| Tokens indexados | 2.874 | **3.267** |
| Fragmentos que arrancan en mitad de palabra | **6 (32 %)** | **0** |
| Fragmentos que arrancan en mitad de oración | 6 (32 %) | 0 (por diseño) |
| `heading` de los fragmentos | "Página 1..4" | "Modelos lineales", "Regresión lineal", … |
| Contexto para `topK=5` | 4.560 car (≈ 1.380 tok.) | **2.815 car (≈ 853 tok.)** |

El mínimo de 172 es el **último** fragmento de una sección (no se fusiona con la
siguiente: fusionar cruzaría el límite de sección). Es intencional.

---

## 3. El solapamiento

### 3.1 Por qué 150 caracteres fijos no sirve

El solapamiento sirve para una cosa: que una idea que quedó partida entre dos
fragmentos aparezca **completa** en al menos uno de los dos, y para que el modelo
vea el contexto de la oración que quedó al borde. 150 caracteres arbitrarios:

1. **Parten palabras**: en la medición, 6 de 19 solapamientos cortaban una palabra
   por la mitad, y el fragmento resultante empezaba con `"on aplica"`, `"etodo de"`,
   `"ruzada son"`. Eso mutila la cita que lee el alumno y mete tokens basura
   (`"etodo"`, `"ruzada"`) en el índice.
2. **No son semánticos**: 150 caracteres son ~1,5 oraciones de prosa o **una sola
   oración larga**: a veces no solapa nada y a veces repite tres.
3. **Se pierden en los límites de sección**: con las marcas `## Página N`, el
   solapamiento real medido fue de **−3 caracteres** en 4 de 19 pares (o sea, nulo).
4. **Cuestan índice**: 8 % de caracteres indexados de más con 1200; con 600 serían
   el 25 %.

### 3.2 El solapamiento por oraciones

**Reglas:**

| Regla | Detalle |
|---|---|
| R1 | Se arrastra la **última oración completa** de la última unidad del fragmento anterior. Si esa unidad no tiene puntuación (texto plano, tablas), se arrastra su **última línea**. |
| R2 | Tope de **180 caracteres**: si la oración es más larga, se corta en el último espacio antes del tope (nunca a mitad de palabra). |
| R3 | Mínimo de **20 caracteres** (`minOverlapChars`): si la cola es más corta, no hay solapamiento (no vale la pena duplicar "Sí."). En la sobrecarga legacy el mínimo es 0. |
| R4 | **Nunca** se solapa entre secciones distintas (`overlapAcrossSections = false`): un fragmento no debe repetir el contenido de la sección anterior. |
| R5 | **Nunca** se solapa un bloque de código ni una tabla: se repiten enteros. |
| R6 | El fragmento que hereda la cola **no** la vuelve a arrastrar: es una sola generación de solapamiento (sin cadena). |
| R7 | Con `overlapSentences = 0` (diapositivas, tablas) no hay solapamiento: cada unidad es atómica. |

```kotlin
/**
 * La cola de solapamiento: la última oración de la última unidad del fragmento.
 *
 * Se calcula **sobre la unidad**, no sobre el texto entero del fragmento: si se
 * calculara sobre el texto, en un bloque sin puntuación la "última oración"
 * sería el fragmento entero.
 */
private fun overlapTail(unit: String, opts: ChunkingOptions): String {
    if (opts.overlapSentences <= 0 || unit.isBlank()) return ""
    val oraciones = SentenceSplitter.split(unit)
    var cola = when {
        oraciones.size > 1 -> oraciones.last()
        oraciones.size == 1 -> oraciones.first().takeIf { it.contains('.') || it.contains('!') || it.contains('?') }
            ?: unit.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()   // sin puntuación: la última línea
        else -> unit.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    }
    if (cola.isBlank()) return ""
    if (cola.length < opts.minOverlapChars) return ""      // R3
    if (cola.length > opts.maxOverlapChars) {                // R2
        cola = cola.take(opts.maxOverlapChars)
        val corte = cola.lastIndexOf(' ')
        if (corte > opts.maxOverlapChars / 2) cola = cola.take(corte)
    }
    return cola.trim()
}
```

**Divisor de oraciones** (`SentenceSplitter`), portable y sin dependencias:

```kotlin
object SentenceSplitter {

    /** Abreviaturas que NO terminan una oración. */
    private val ABREVIATURAS = setOf(
        "sr", "sra", "srta", "dr", "dra", "prof", "lic", "ing", "etc", "ej", "pág", "pag",
        "cap", "fig", "núm", "num", "art", "vol", "ed", "min", "max", "e", "i",
    )

    private val CORTE = Regex("""(?<=[.!?…])\s+""")

    fun split(text: String): List<String> {
        val bruto = CORTE.split(text.trim()).filter { it.isNotBlank() }
        // se re-une lo que quedó partido por una abreviatura
        val out = mutableListOf<String>()
        for (parte in bruto) {
            val previa = out.lastOrNull()
            if (previa != null && previa.endsWithAbbreviation()) out[out.lastIndexOf(previa)] = "$previa $parte"
            else out += parte
        }
        return out
    }

    private fun String.endsWithAbbreviation(): Boolean {
        val ultima = trimEnd('.').split(' ').lastOrNull()?.lowercase() ?: return false
        return ultima in ABREVIATURAS
    }
}
```

> Cuidado con "3.14": el regex exige espacio después del punto, así que los
> decimales no se cortan. "e.g." está en la lista de abreviaturas.

### 3.3 Lo que **no** se solapa

| Contenido | Por qué |
|---|---|
| Bloques de código | Repetir la mitad de un ejemplo no aporta y confunde al modelo |
| Tablas | Una fila repetida "parece" una tabla más grande |
| Fragmentos de secciones distintas | El fragmento pertenece a una sección; el contexto del anterior no le corresponde |
| El primer fragmento de una sección | No hay anterior |

### 3.4 Números

| | Solapamiento fijo (hoy) | Solapamiento por oración (propuesto) |
|---|---|---|
| Fragmentos que arrancan en mitad de palabra | 6/19 (32 %) | **0/36** |
| Sobrecosto de índice (caracteres) | +8 % (1200) | +24 % (600) — 1 oración por fragmento |
| Solapamiento en los límites de sección | 0 (medido: −3 car.) | 0 (por diseño, R4) |
| Copia legible | "150 caracteres arbitrarios" | "la oración anterior completa" |

El sobrecosto del 24 % se paga en índice, pero se gana en **precisión**: el fragmento
que contiene la respuesta tiene la densidad de término 2× mayor (600 en vez de 1200
caracteres), y BM25 premia la densidad con la normalización por longitud.

---

## 4. La `heading` en la cita

### 4.1 Qué es hoy y qué debería ser

| Campo | Significado actual | Significado nuevo |
|---|---|---|
| `Chunk.source` | El corpus: `"cátedra"` | Igual (no cambia) |
| `Chunk.path` | El título del material | Igual (no cambia) |
| `Chunk.heading` | El **último encabezado Markdown** (en un PDF: "Página 3" o el nombre del archivo) | **La sección semántica** del fragmento ("Regresión lineal"); si no se detectó ninguna, el nombre del material |
| `Chunk.label` | `"$source: $path"` | **No cambia** (es la clave de analítica y del verificador) |
| `Chunk.citationLabel` | — (nuevo en `10-taxonomia.md`) | `"Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal · pág. 7"` |

### 4.2 La composición

```
citationLabel = unitPath            # ["Unidad 2", "Tema 2.1"]     (puede estar vacío)
              + materialTitle       # "Apuntes-IA"
              + sectionTitle        # "Regresión lineal"           (si ≠ materialTitle)
              + localizador         # "· pág. 7" | "· diap. 12" | "· 12:30–13:05"
```

En código (extiende el `citationLabel` de `10-taxonomia.md` §7.5):

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    // … metadatos (ver §5)
) {
    /** Etiqueta corta y estable: "cátedra: Apuntes-IA". No cambia. */
    val label get() = "$source: $path"

    /**
     * Etiqueta de cita con la jerarquía y la localización.
     *
     * "Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal · pág. 7"
     */
    val citationLabel: String
        get() = buildString {
            for (u in unitPath) { append(u).append(" > ") }
            append(if (title.isNotBlank()) title else path)
            val seccion = headingPath.lastOrNull()?.takeIf { it.isNotBlank() && it != title && it != path }
            if (seccion != null) { append(" > ").append(seccion) }
            append(locatorLabel)
        }

    /** "· pág. 7" / "· diap. 12" / "· 12:30" / "" (según el tipo de material). */
    val locatorLabel: String
        get() = when {
            page > 0 -> " · pág. $page"
            slide > 0 -> " · diap. $slide"
            endMs > 0L -> " · ${hhmm(startMs)}–${hhmm(endMs)}"
            else -> ""
        }
}
```

### 4.3 Ejemplos

| Material | Unidad | Sección detectada | `citationLabel` |
|---|---|---|---|
| Apuntes-IA.pdf | Unidad 2 > Tema 2.1 | Regresión lineal | `Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal · pág. 7` |
| Apuntes-IA.pdf | (ninguna) | (ninguna) | `Apuntes-IA · pág. 3` |
| Diapositivas-1.pptx | Unidad 1 | (ninguna) | `Unidad 1 > Diapositivas-1 · diap. 12` |
| apuntes.md | Unidad 2 | `## 2.1 Regresión` | `Unidad 2 > apuntes.md > 2.1 Regresión` |
| clase.mp4 (transcripción) | Unidad 3 | — | `Unidad 3 > clase.mp4 · 12:30–13:05` |

### 4.4 Qué cambia en el prompt y en la UI

```diff
// backend/src/commonMain/kotlin/rag/engine/RagEngine.kt
- hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }
+ hits.mapIndexed { i, c -> "[${i + 1}] (${c.citationLabel})\n${c.text}" }
```

```
[1] (Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal · pág. 7)
La regresión lineal modela y = b0 + b1*x1 + ... + bn*xn + e. El estimador de
mínimos cuadrados minimiza la suma de residuos al cuadrado…
```

En la UI del alumno, la lista de fuentes pasa de
`"cátedra: Apuntes-IA — Página 3"` a la etiqueta de arriba, **con la página en un
botón que abre el material en esa página** (el `PdfBackend` ya sabe abrir por página).

### 4.5 El "prepender el contexto al chunk": dónde va exactamente

El enunciado del diseño pide *prepender* al fragmento el título de sección y el de
la unidad. Hay que decidir **dónde**, y la respuesta es "en tres lugares distintos,
no en el cuerpo":

| Lugar | Qué se antepone | Por qué |
|---|---|---|
| **La etiqueta de cita** (`citationLabel`) | ruta de unidades + material + sección | Es lo que lee el alumno y lo que el modelo recibe entre paréntesis |
| **El texto indexado** (`Chunk.indexedText`) | la ruta de unidades + la pila de títulos + el cuerpo | Es lo que hace que "regresión" encuentre el fragmento aunque la palabra no esté en el cuerpo |
| **El cuerpo** (`Chunk.text`) | **nada** | El cuerpo es lo que compara el verificador de citas; anteponerle el título duplicaría los términos en el `tf` y podría hacer que una cita "literal" del modelo no coincida con la fuente |

```kotlin
/**
 * Texto que se indexa: metadatos + cuerpo.
 *
 * Para un fragmento sin metadatos produce exactamente los mismos tokens que
 * "${heading} ${path} ${text}" (los espacios sobrantes no generan tokens).
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
```

> **Nota (coherencia con `10-taxonomia.md`).** La ruta de unidades **sí** va en el
> texto indexado, aunque el documento ya dice que eso aporta poco al score léxico
> (el `df` de "unidad" es altísimo ⇒ `idf` ≈ 0). Se mantiene por compatibilidad con
> el diseño de taxonomía, y porque el efecto real de la jerarquía viene del
> **boost** y del filtrado, no del score. Lo que **no** se mete en el texto
> indexado son los localizadores (`pág. 7`, `diap. 12`): inflan el `df` de
> "página" sin aportar nada.

---

## 5. Los metadatos del fragmento

### 5.1 El `Chunk` extendido

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
package rag.retrieval

/**
 * Documento que entra al índice.
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
    /** Forma del material: decide la política de troceado. */
    val shape: MaterialShape = MaterialShape.PROSE,
)

/** Qué clase de contenido tiene el fragmento. */
enum class BlockKind(val label: String) {
    TEXT("Texto"),
    CODE("Código"),
    TABLE("Tabla"),
    SLIDE("Diapositiva"),
    TRANSCRIPT("Transcripción"),
}

/**
 * Fragmento indexado.
 *
 * El constructor posicional `(source, path, heading, text)` no cambia: todos los
 * campos nuevos tienen valor por defecto ⇒ los tests portados de FunshiEngineAI
 * siguen compilando y pasando.
 */
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    // ---- taxonomía (de 10-taxonomia.md) ----
    val materialId: String = "",
    val title: String = "",
    val unitIds: List<String> = emptyList(),
    val unitPath: List<String> = emptyList(),
    val headingPath: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val week: Int = 0,
    val boost: Double = 1.0,
    // ---- localización (nuevos) ----
    /** Página del PDF (0 = no aplica). */
    val page: Int = 0,
    /** Número de diapositiva (0 = no aplica). */
    val slide: Int = 0,
    /** Inicio del fragmento en una transcripción (ms). 0 = no aplica. */
    val startMs: Long = 0L,
    /** Fin del fragmento en una transcripción (ms). 0 = no aplica. */
    val endMs: Long = 0L,
    /** Offset del inicio del cuerpo dentro del texto del material. */
    val charStart: Int = 0,
    /** Offset del fin del cuerpo dentro del texto del material. */
    val charEnd: Int = 0,
    /** Clase de contenido. */
    val kind: BlockKind = BlockKind.TEXT,
    /** Id estable del fragmento: "materialId#hash(texto)". */
    val id: String = "",
) {
    val label get() = "$source: $path"
    val citationLabel: String get() = /* §4.2 */
    val locatorLabel: String get() = /* §4.2 */
    val indexedText: String get() = /* §4.5 */
}
```

### 5.2 Qué guarda cada campo y para qué

| Campo | Para qué sirve | ¿Entra al texto indexado? |
|---|---|---|
| `materialId` | Reindexado incremental; analítica por material; "sólo este material" en la prueba del docente | No (va en `path`/`title`) |
| `headingPath` | La pila de títulos del documento ("Unidad 2" > "Regresión"); alinea con la ruta de unidades | Sí (una vez) |
| `unitPath` / `unitIds` | La atribución de la cátedra; el boost; la analítica por unidad | Sí (una vez) — ver la nota de §4.5 |
| `tags` | Etiquetas libres del material; expansión de consulta | Sí (una vez) |
| `week` | La analítica por semana | No |
| `boost` | La prior estática | No (se aplica después del score) |
| **`page`** | "Abrí el PDF en la página 7"; la analítica por página ("el 60 % de las citas son de la pág. 7") | **No** |
| **`slide`** | "Abrí la diapositiva 12" | **No** |
| **`startMs`/`endMs`** | "Escuchá desde el minuto 12:30"; las citas de un video | **No** |
| **`charStart`/`charEnd`** | Resaltar el pasaje exacto dentro del material; "copiar el fragmento"; el `id` estable | **No** |
| **`kind`** | No trocear código/tablas; mostrarlos con otra tipografía; detectar "el modelo recibió código partido" | **No** |
| **`id`** | La analítica: "este pasaje se cita 12 veces" | **No** |

**Regla general**: al texto indexado va **el vocabulario** (lo que el alumno puede
buscar); a los metadatos va **la geometría** (dónde está el pasaje). Mezclarlos
ensucia el `df`.

### 5.3 El efecto en la cita

Ya está en §4. La diferencia clave: la cita pasa de un rótulo genérico a algo
**accionable**:

| Antes | Después |
|---|---|
| `cátedra: Apuntes-IA — Página 3` | `Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal · pág. 7` |

Y en la UI, la etiqueta se descompone en partes navegables:
`Unidad 2 >` (abre la unidad) `Tema 2.1 >` (abre el tema) `Apuntes-IA >`
(abre el material) `Regresión lineal` (resalta el pasaje) `· pág. 7` (abre la página).

### 5.4 El efecto en la analítica

Hoy la analítica sólo puede decir "se usó el material X" (`SourceUsage`). Con estos
metadatos:

| Pregunta del docente | Antes | Después |
|---|---|---|
| ¿Qué material se consulta? | ✔ (por `label`) | ✔ (por `materialId`, sin ambigüedad) |
| ¿De qué tema preguntan? | ✘ | ✔ (`unitPath` en `SourceRef`) |
| ¿Qué página del apunto se usa más? | ✘ | ✔ (`page`) |
| ¿Qué diapositiva se usa más? | ✘ | ✔ (`slide`) |
| ¿El material está mal troceado? (muchas citas del mismo párrafo) | ✘ | ✔ (`charStart`/`charEnd` + `id`: "el 80 % de las citas caen en 2 párrafos") |
| ¿Los alumnos citesn un pasaje vacío o inútil? | ✘ | ✔ (`id` con pocas citas + `kind`) |

La extensión de `SourceRef` (`10-taxonomia.md` §7.12) suma los campos de localización:

```kotlin
@Serializable
data class SourceRef(
    val index: Int = 0,
    val materialId: String = "",
    val materialTitle: String = "",
    val unitIds: List<String> = emptyList(),
    val unitPath: List<String> = emptyList(),
    val heading: String = "",
    val week: Int = 0,
    val label: String = "",
    val score: Double = 0.0,
    // ---- nuevos ----
    /** Página del PDF (0 = no aplica). */
    val page: Int = 0,
    /** Diapositiva (0 = no aplica). */
    val slide: Int = 0,
    /** Inicio en una transcripción (ms). */
    val startMs: Long = 0L,
    /** Id estable del fragmento ("materialId#hash"). */
    val chunkId: String = "",
)
```

### 5.5 El efecto en el verificador de citas

`CitationVerifier.verify(answer, sources)` mira **`source.text`** (el cuerpo del
fragmento), no el material. Con el cuerpo limpio (sin preámbulo) el comportamiento
es **idéntico** al de hoy: no se rompe ninguna de las tres reglas (literal, sin
detalles inventados, paráfrasis).

Lo que cambia es la **fidelidad**: hoy el fragmento puede empezar en mitad de una
palabra (`"on aplica"`), así que la cita que el alumno lee está mutilada aunque el
verificador la dé por respaldada. Con el troceado por oraciones, el texto del
fragmento es un trozo de prosa bien formado.

**Verificación nueva que conviene agregar** (un test, no una regla del verificador):
*"el texto del fragmento es una subcadena del texto del material"*. Es el invariante
que garantiza que un fragmento es un excerpt fiel, y el que detectaría el preámbulo
dentro del cuerpo (que §4.5 descarta explícitamente).

---

## 6. ¿Un chunk por material o varios?

### 6.1 El corpus de una cátedra típica

| Supuesto | Valor |
|---|---|
| Materiales por cátedra | 100 |
| Palabras por material | 3.000 |
| Palabras totales | 300.000 |
| Caracteres (≈ 6 por palabra + espacio) | **≈ 1,8 M de caracteres** (1,8 MB) |
| Fragmentos con 600 car. (sin solape) | 3.000 |
| Fragmentos con 600 car. **con** el solape de 1 oración (+24 %) | **3.700** |
| Tokens indexados (≈ 100 tokens por fragmento de 600) | ≈ 327.000 |

### 6.2 Cantidad de fragmentos

| Configuración | Fragmentos | Caracteres indexados | Tokens |
|---|---|---|---|
| Actual (1200/150) | 2.000 | 1,82 MB | 287.000 |
| **Propuesta (600/400/800)** | **3.700** | **2,08 MB** | 327.000 |
| Propuesta con Markdown a 1200 (mixto realista: 60 % PDF, 40 % MD) | 3.100 | 1,95 MB | 295.000 |

Un material de 3.000 palabras pasa de **20 a 37 fragmentos**: el docente tiene que
saber que "Fragmentos (37)" es lo normal, no un bug (la pestaña "Fragmentos" de
`03-materiales.md` lo muestra).

### 6.3 Memoria

Estimación en la JVM (desktop) para 3.700 fragmentos y 327.000 tokens:

| Estructura | Cálculo | RAM |
|---|---|---|
| **Actual**: `List<Map<String,Int>>` (un `HashMap` por fragmento) | 327.000 nodos × 32 B + 327.000 `String` duplicados × ~56 B + `Integer` (cacheados hasta 127) | **≈ 29 MB** |
| **Actual**: textos de los fragmentos | 2,08 M chars × 2 B (UTF-16) | ≈ 4,2 MB |
| **Actual**: `df` (mapa de términos) | ~25.000 términos × ~80 B | ≈ 2 MB |
| **Propuesto**: posting lists (`IntArray` de ids + `IntArray` de tf) | 327.000 × 8 B | **≈ 2,6 MB** |
| **Propuesto**: vocabulario compartido (`String` interneados) | 25.000 × ~64 B | ≈ 1,6 MB |
| **Propuesto**: textos | 2,08 M × 2 B | ≈ 4,2 MB |
| **Propuesto**: `df` como `IntArray` | 25.000 × 4 B | ≈ 0,1 MB |
| **Total actual** | | **≈ 35 MB** |
| **Total propuesto** | | **≈ 8,5 MB** |

**El índice invertido es ~4× más liviano aun con el 85 % más de fragmentos.**
El motivo: el diseño actual **duplica el texto de cada token** en cada fragmento
(`groupingBy { it }.eachCount()` crea un `String` nuevo por token y por fragmento);
el invertido guarda **ids numéricos** y un único diccionario.

En Android (que es donde más duele la RAM: un móvil de gama media con 2 GB) la
diferencia es la que decide si una cátedra con 200 materiales entra en memoria.

### 6.4 Tiempo

| Operación | Actual | Propuesto |
|---|---|---|
| Tokenizar 100 materiales (2,08 M car.) | ~0,9 s (JVM) / ~2,5 s (Android) | ~0,9 s — **pero sólo del material que cambió** (§8) |
| Construir el índice | 327.000 inserciones en `HashMap` ≈ 0,3 s | 327.000 appends en arrays ≈ **40 ms** |
| Consulta (10 términos) | 10 × 3.700 = **37.000 lookups** + un sort de 3.700 → ~1,5–3 ms | posting lists tocadas ≈ 400–2.000 + top-K acotado → **~0,2–0,5 ms** |
| Consulta con 30.000 fragmentos | 300.000 lookups + sort de 30.000 → ~15–40 ms | ~1–3 ms |

**Presupuesto de contexto** (`topK=5`, español ≈ 3,3 caracteres/token):

| | Fragmentos | Contexto | Tokens |
|---|---|---|---|
| Actual | 5 × 912 | 4.560 car | ~1.380 |
| **Propuesto (PDF)** | 5 × 563 | 2.815 car | **~853** |
| Propuesto (Markdown) | 5 × 1.200 | 6.000 car | ~1.820 |

Con un modelo local de **4.096 tokens** de contexto (el caso de un alumno con
llama.cpp en el teléfono), el prompt del sistema (500) + 6 turnos de historial (600)
+ contexto (853) + respuesta (500) = **2.453 tokens**: entra con margen. Con
fragmentos de 1.200 la misma cuenta da **3.420**: entra raspando. Ésa es la razón
cuantitativa del `target=600` para PDF.

### 6.5 Un chunk por material: no, pero **una tarjeta por material**

Trocear el material en **un solo fragmento de 30.000 caracteres** está descartado:

- BM25 normaliza por longitud (`b=0.75`): un término que aparece 3 veces en 30.000
  caracteres tiene `tf` = 3 pero una longitud 15× mayor que la media ⇒ su score
  se divide por ~7. Compite con cualquier otro fragmento en desventaja.
- El modelo recibe 30.000 caracteres de contexto (9.000 tokens): imposible.
- La cita sería "todo el documento": no sirve para nada.

Lo que **sí** sirve es un fragmento extra por material: la **tarjeta**.

```kotlin
/**
 * Tarjeta del material: un fragmento sintético con su identidad.
 *
 * Existe para que el material sea encontrable por su propio nombre y por su
 * esquema, aunque la respuesta esté repartida por varios fragmentos.
 */
fun buildCard(doc: Doc, outline: List<String>): Chunk = Chunk(
    source = doc.source,
    path = doc.path,
    heading = doc.title.ifBlank { doc.path },
    text = buildString {
        append(doc.title).append('\n')
        if (doc.unitPath.isNotEmpty()) append(doc.unitPath.joinToString(" > ")).append('\n')
        if (doc.tags.isNotEmpty()) append(doc.tags.joinToString(", ")).append('\n')
        if (outline.isNotEmpty()) append(outline.joinToString(" · ")).append('\n')
        append(doc.text.take(300))
    },
    materialId = doc.materialId,
    title = doc.title,
    unitPath = doc.unitPath,
    headingPath = emptyList(),
    tags = doc.tags,
    boost = 1.15,          // un poco por encima: representa al material entero
    kind = BlockKind.TEXT,
    id = "${doc.materialId}#card",
)
```

- 1 tarjeta por material: +100 fragmentos (2,7 % del índice) — barato.
- Se indexa con su propio `boost` (1,15): aparece cuando alguien pregunta por el
  material ("¿qué dice el apunte de regresión?"), no en cada pregunta.
- El esquema detectado (`outline`) es el resultado del `HeadingDetector`: le sirve
  al docente para **revisar** la estructura que el sistema adivinó.

---

## 7. Índice invertido en vez del recorrido total

### 7.1 El costo actual, medido

`Bm25Index.search` hace, por cada término de la consulta:

```kotlin
for (i in chunks.indices) {              // 3.700 iteraciones POR TÉRMINO
    val f = tfs[i][term] ?: continue
    scores[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
}
```

y después **ordena el array completo** para quedarse con 5:

```kotlin
scores.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(k)
```

Con 10 términos y 3.700 fragmentos: **37.000 lookups en un `HashMap`** + un sort de
3.700 elementosboxed (≈ 45.000 comparaciones de `Double`). Con 30.000 fragmentos:
300.000 lookups + un sort de 30.000.

Medición con una simulación del mismo algoritmo (3.000 fragmentos, 300.000 tokens,
vocabulario de 20.828 términos, consultas de 10 términos):

| | Lookups / postings tocadas | Tiempo (proxy Python) |
|---|---|---|
| Recorrido lineal + sort | 30.000 lookups + sort de 3.000 | 14,6 ms |
| **Invertido + top-K acotado** | **266–4.127 postings** (mediana ~400) | **0,26 ms** |

> Los tiempos absolutos de la simulación en Python no son los de la JVM (que es
> ~50× más rápida en estos bucles), pero los **recuentos de operaciones** son
> exactos y son los que determinan el escalado: 30.000 → 400 es un factor 75 en la
> parte dominante del costo. El sort completo desaparece del medio.

Además, el recorrido lineal produce un resultado **idéntico** al invertido (el
verificador de la simulación dio el mismo top-5 en 200/200 consultas): la diferencia
es sólo de costo, no de semántica.

### 7.2 La estructura de datos

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt
package rag.retrieval

import kitoru.platform.stripDiacritics
import kotlin.math.ln

/**
 * Índice BM25 en memoria, con **índice invertido**.
 *
 * Cambios respecto de la versión lineal:
 *  - el vocabulario es **compartido**: cada término tiene un id entero y una sola
 *    cadena internada (la versión anterior duplicaba el texto de cada token en
 *    cada fragmento: ~29 MB para 3.700 fragmentos, contra ~8,5 MB acá);
 *  - las posting lists son `IntArray` paralelos (ids de fragmento + frecuencia);
 *  - la búsqueda **no** recorre todos los fragmentos: recorre las posting lists
 *    de los términos de la consulta;
 *  - el top-K es **acotado** (heap de tamaño K) en vez de ordenar el array entero.
 *
 * La API pública no cambia: `search(query, k)` sigue devolviendo
 * `List<Pair<Chunk, Double>>` con los mismos valores.
 */
class Bm25Index private constructor(
    private val chunks: List<Chunk>,
    /** Tokens ya calculados (o `null` ⇒ hay que tokenizar). */
    private val preTokenized: List<TokenizedChunk>?,
) {

    /** Constructor normal: tokeniza los fragmentos. */
    constructor(chunks: List<Chunk>) : this(chunks, null)

    private val k1 = 1.5
    private val b = 0.75

    // ---- vocabulario ----
    private val vocab = HashMap<String, Int>()      // término -> termId
    private val terms = ArrayList<String>()         // termId -> término

    // ---- posting lists ----
    private val postChunk = ArrayList<IntArray>()   // termId -> ids de fragmento
    private val postTf = ArrayList<IntArray>()      // termId -> frecuencias

    // ---- estadísticas ----
    private val lens = IntArray(chunks.size)
    private val avgLen: Double

    init { build(preTokenized) }

    val size get() = chunks.size

    /**
     * Construye el índice desde tokens **ya calculados**.
     *
     * Es lo que permite el reindexado incremental (§8): el `CourseIndex` guarda
     * los tokens por material y rearma el índice sin volver a tokenizar.
     */
    companion object {
        fun fromTokens(chunks: List<Chunk>, tokens: List<TokenizedChunk>): Bm25Index =
            Bm25Index(chunks, tokens)
    }

    /** Nº de fragmentos que contienen el término (para diagnóstico). */
    fun documentFrequency(term: String): Int = postChunk.getOrNull(vocab[term] ?: -1)?.size ?: 0

    /**
     * Busca los fragmentos relevantes.
     *
     * @param termWeights peso por término de la consulta (1.0 por defecto).
     */
    fun search(
        query: String,
        k: Int,
        termWeights: Map<String, Double> = emptyMap(),
    ): List<Pair<Chunk, Double>> {
        if (chunks.isEmpty() || k <= 0) return emptyList()
        val n = chunks.size.toDouble()
        val scores = DoubleArray(chunks.size)
        val tocados = IntArray(chunks.size)          // marcas de "ya sumando"

        for (term in tokenize(query).distinct()) {
            val id = vocab[term] ?: continue
            val posting = postChunk[id]
            val tfs = postTf[id]
            val d = posting.size
            val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
            val w = termWeights[term] ?: 1.0
            for (p in posting.indices) {
                val i = posting[p]
                val f = tfs[p]
                scores[i] += w * idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
                tocados[i] = 1
            }
        }

        // ---- top-K acotado (heap de tamaño k) ----
        val heap = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }.thenBy { -it.second })
        for (i in scores.indices) {
            if (tocados[i] == 0 || scores[i] <= 0.0) continue
            heap.add(scores[i] to i)
            if (heap.size > k) heap.poll()
        }
        return heap.sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenBy { it.second })
            .map { chunks[it.second] to it.first }
    }
}
```

**Detalles que importan:**

| Detalle | Por qué |
|---|---|
| El acumulador es un `DoubleArray(chunks.size)` (30 KB con 3.700 fragmentos) | En producción conviene **reusarlo** (un buffer por índice o un `ThreadLocal`): por pregunta son 60 KB de asignación (scores + marcas) |
| La lista `tocados` evita recorrer los 3.700 fragmentos al final | El recorrido final es O(n) y no O(postings) |
| El desempate es por **índice de fragmento ascendente** | `sortedByDescending` de Kotlin es estable: hoy los empates conservan el orden del índice. El heap tiene que reproducirlo (`thenBy { -it.second }` al insertar, `thenBy { it.second }` al sacar) |
| Los scores se suman **en el mismo orden de términos** que antes | Los `Double` dan **exactamente el mismo valor** bit a bit ⇒ los tests siguen valiendo y los resultados son reproducibles |
| `k` mayor que el número de candidatos | `heap.size > k` nunca se dispara ⇒ devuelve todos |

### 7.3 El top-K acotado

Ordenar 3.700 elementos para quedarse con 5 es un desperdicio. Con un heap de
tamaño `k`:

- `k = 5`: cada candidato hace 1 `add` + 1 `poll` (O(log 5) ≈ 2 comparaciones).
- Total: O(postings × log k) en vez de O(n log n).

A 30.000 fragmentos, k=5: 30.000 × 2 comparaciones ≈ 60.000, contra 30.000 × 15
(= 450.000) del sort completo.

### 7.4 El impacto en los tests existentes (`Bm25IndexTest`)

Los tests portados de FunshiEngineAI (`backend/src/desktopTest/kotlin/rag/retrieval/Bm25IndexTest.kt`)
**no se tocan**. Análisis test por test:

| Test | Qué exige | Por qué sigue valiendo |
|---|---|---|
| `la tokenizacion parte el camel case` | `tokenize` parte `llamaServer` | El tokenizador no se toca |
| `la tokenizacion saca los acentos` | idem | idem |
| `la tokenizacion saca las palabras vacias` | idem | idem |
| `la tokenizacion ignora las palabras de una letra` | idem | idem |
| `la tokenizacion parte por lo que no es alfanumerico` | idem | idem |
| `un indice vacio no devuelve nada` | `size == 0`, `search` vacío | `search` devuelve `emptyList()` si `chunks.isEmpty()` |
| `la busqueda devuelve el chunk que mas coincide` | 1 resultado para "shaders" | Sólo los fragmentos con el término tienen score > 0 |
| `la busqueda ordena por relevancia` | el chunk con "motor motor motor render" va primero | Los scores son idénticos (misma fórmula, mismo orden de suma); el desempate por índice se preserva |
| `la busqueda respeta el limite de resultados` | `take(k)` | El heap de tamaño k |
| `una busqueda sin coincidencias devuelve nada` | lista vacía | Todos los scores quedan en 0 ⇒ filtrados por `scores[i] > 0` |
| `una consulta vacia no devuelve nada` | lista vacía | `tokenize("")` no da términos ⇒ el heap queda vacío |
| `el titulo y el path tambien cuentan para la busqueda` | "shaders" en el `path` encuentra el fragmento | `indexedText` incluye `path` y `heading` |

**Los dos riesgos reales** (y cómo se neutralizan):

1. **Cambiar el texto indexado.** Si `indexedText` dejara de incluir `heading` o
   `path`, el último test fallaría. Por eso `indexedText` se define de forma que, sin
   metadatos, produzca **exactamente** los mismos tokens que
   `"${heading} ${path} ${text}"`.
2. **Cambiar el orden de los empates.** El test `la busqueda ordena por relevancia`
   depende del orden estable. El heap tiene que desempatar por índice ascendente.

### 7.5 El índice y la búsqueda por material ("sólo este material")

La pestaña "Probar pregunta" del docente arma un `Bm25Index` **efímero** con los
fragmentos de un solo material. Con el índice invertido eso es trivial y además
**correcto**: el `idf` de un índice de un material es el de ese material (los términos
comunes a toda la cátedra tienen `idf` bajo). Es exactamente lo que el docente
quiere medir: "¿este material, aisladamente, sabe la respuesta?".

---

## 8. Reindexado incremental

### 8.1 El problema

Hoy `KnowledgeBase.sync()` **reconstruye todo**: vuelve a trocear los 100 materiales
y a construir el índice completo. El flujo del docente es:

> El docente edita un material → se reindexa **todo** → 0,9 s de tokenización +
> 0,3 s de construcción de índice → el cliente espera.

Con 100 materiales es tolerable. Con 400 materiales (una cátedra con 4 años de
material) son 4 s por cada edición. Y el reindexado completo **invalida** la
memoria del índice entero (garbage collector).

### 8.2 El diseño: "material → fragmentos"

```
CourseIndex
  ├─ materiales:   Map<materialId, Material>
  ├─ fragmentos:   Map<materialId, List<Chunk>>        ← el troceado, cacheado
  ├─ tokens:       Map<materialId, List<TokenizedChunk>> ← la tokenización, cacheada
  └─ índice:       Bm25Index                            ← se rearma desde la caché
```

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/CourseIndex.kt
package rag.retrieval

/**
 * Índice vivo de una cátedra.
 *
 * Es la capa que hace que el reindexado sea **incremental**: guarda el troceado
 * y la tokenización **por material**, así que cuando el docente edita un material
 * sólo se vuelve a trocear y tokenizar ese material.
 *
 * Lo que **no** es incremental es el rearme de las posting lists: se rehace
 * entero desde la caché de tokens. Es barato (327.000 appends ≈ 40 ms) y
 * garantiza que `df` y `avgLen` sean **exactos** (no aproximados).
 */
class CourseIndex(private val cfg: RagConfig) {

    private val chunks = LinkedHashMap<String, List<Chunk>>()
    private val tokens = LinkedHashMap<String, List<TokenizedChunk>>()

    @Volatile
    private var index: Bm25Index = Bm25Index(emptyList())

    val chunkCount get() = index.size

    /** Fragmentos por material (lo que usa la pestaña "Fragmentos"). */
    fun chunksOf(materialId: String): List<Chunk> = chunks[materialId].orEmpty()

    /** Cuántos fragmentos aporta cada material. */
    fun chunkCountByMaterial(): Map<String, Int> = chunks.mapValues { it.value.size }

    /**
     * Reindexa **un** material (o lo agrega / lo saca).
     *
     * @param material el material nuevo o editado; `null` = sacar el material.
     */
    fun upsert(material: Material?) {
        val id = material?.id ?: return
        if (material == null || !material.visible || material.extractedText.isBlank()) {
            remove(id)
        } else {
            val opts = ChunkingOptions.forShape(shapeOf(material), cfg.chunkSize)
            val nuevos = Chunker.split(
                Doc(
                    source = "cátedra",
                    path = material.title.ifBlank { material.path },
                    text = material.extractedText,
                    materialId = material.id,
                    title = material.title,
                    shape = shapeOf(material),
                ),
                opts,
            )
            chunks[id] = nuevos
            tokens[id] = nuevos.map { Tokenizer.tokenizeChunk(it) }
        }
        rebuild()
    }

    /** Saca un material del índice. */
    fun remove(materialId: String) {
        chunks.remove(materialId)
        tokens.remove(materialId)
        rebuild()
    }

    /** Reindexa todo (o el subconjunto visible). */
    fun rebuild(materials: List<Material>) {
        chunks.clear(); tokens.clear()
        for (m in materials) upsertSinRebuild(m)
        rebuild()
    }

    /** Rearma las posting lists desde la caché de tokens. */
    private fun rebuild() {
        val todos = chunks.values.flatten()
        val todosTokens = tokens.values.flatten()
        index = Bm25Index.fromTokens(todos, todosTokens)
    }
}
```

**El punto clave: `Bm25Index.fromTokens(chunks, tokens)`.** El índice se construye a
partir de los tokens **ya calculados**; no vuelve a tokenizar. Reindexar 1 material
de 100 cuesta:

| Paso | Costo |
|---|---|
| Trocear 1 material | ~3 ms |
| Tokenizar 1 material | ~9 ms |
| Rearmar las posting lists de 3.700 fragmentos (327.000 tokens) | ~40 ms |
| **Total** | **~52 ms** (vs. ~1,2 s del reindexado completo) |

Y el `df`/`avgLen` quedan **exactos** porque se recalculan en el rearme.

### 8.3 Cuándo se reindexa

| Evento | Qué se reindexa |
|---|---|
| Se sube un material | **ese** material |
| Se edita el texto a mano | **ese** material |
| Se cambia la visibilidad | **ese** material (si se ocultó, se saca del índice del alumno) |
| Se borra el material | **ese** material (se saca) |
| Cambia `Course.chunkSize` | **todo** (los tamaños cambian) |
| Cambia la taxonomía (unidades, etiquetas, boost) | **todo** (los metadatos se hornean en `indexedText` y en el `boost`) |
| Se edita el prompt del sistema | **nada** (el prompt no está en el índice) |

> **Nota sobre la taxonomía.** `10-taxonomia.md` mete `unitPath` y `tags` en
> `indexedText`. Eso implica que **cambiar el nombre de una unidad obliga a
> reindexar todo**. Es el precio de que el nombre de la unidad sea buscable.
> Alternativa: dejar la jerarquía **fuera** del texto indexado (sólo en los
> metadatos y en el boost) y reindexar sólo cuando cambia el contenido. Se
> recomienda la alternativa si la taxonomía va a cambiar seguido; por defecto se
> mantiene el diseño de taxonomía (coherencia entre documentos).

### 8.4 La invalidación visible

`03-materiales.md` ya pide el banner "El índice está desactualizado". Con el
reindexado incremental, el estado por material se guarda en el `Material`:

```kotlin
/** Hash del texto con el que se indexó por última vez. */
val indexedHash: String = ""
```

- Al indexar: `material.indexedHash = hash(material.extractedText)`.
- En la UI: `material.indexedHash != hash(material.extractedText)` ⇒ "el texto
  cambió después del último reindexado".

### 8.5 Persistencia del índice (opcional, no en el milestone 1)

El índice se puede **reconstruir** en ~1,2 s al abrir la cátedra (o 52 ms por
material si se cachean los tokens en disco: `courses/<id>/index/tokens.bin`). Para
el milestone 1 se reconstruye en memoria al abrir; el cache en disco queda como
mejora.

---

## 9. Los tests que hay que escribir

### 9.1 `ChunkerTest` (se **extiende**, no se reemplaza)

Los 9 tests actuales **se conservan tal cual** (compatibilidad garantizada por
D10) y se agregan:

| # | Test | Qué verifica |
|---|---|---|
| 1 | `un pdf sin encabezados se trocea por parrafos` | Un texto sin `#` produce varios fragmentos con cortes en límites de párrafo |
| 2 | `ningun fragmento arranca en mitad de una palabra` | **Invariante**: para todos los fragmentos > 1, el carácter anterior al inicio no es alfanumérico o el primero sí |
| 3 | `ningun fragmento se pasa del maximo` | `chunk.text.length <= maxChars` (+ tolerancia para una unidad indivisible) |
| 4 | `un fragmento chico se fusiona con el siguiente` | Un párrafo de 50 caracteres no queda solo si el siguiente es de la misma sección |
| 5 | `el solapamiento es una oracion completa` | El final del fragmento N y el comienzo del N+1 comparten una oración entera |
| 6 | `el solapamiento no cruza una seccion` | El primer fragmento de una sección no repite nada de la anterior |
| 7 | `un bloque de codigo no se parte` | Un bloque de 900 caracteres queda entero en un fragmento (o se parte **entre** bloques) |
| 8 | `una tabla no se parte por el medio de una fila` | Cada fila aparece completa en algún fragmento |
| 9 | `un titulo numerado se detecta como seccion` | "1.2 Regresión lineal" → `heading` = "Regresión lineal", nivel 2 |
| 10 | `una linea en mayusculas se detecta como seccion` | "TEOREMA DE BAYES" → sección |
| 11 | `un titulo con palabra clave se detecta` | "Tema 3: Redes neuronales" → sección nivel 2 |
| 12 | `el membrete repetido no es una seccion` | Una línea que aparece 5 veces no genera sección |
| 13 | `la marca de pagina no es una seccion` | "## Página 7" fija `page = 7` pero **no** aparece en el `heading` |
| 14 | `la marca de diapositiva fija el numero` | "## Diapositiva 12" → `slide = 12` |
| 15 | `un timestamp fija el intervalo` | "[00:12:34]" → `startMs` |
| 16 | `la seccion detectada aparece en la cita` | `citationLabel` contiene el título de la sección |
| 17 | `el fragmento guarda la pagina y el offset` | `page`, `charStart`, `charEnd` |
| 18 | `el id del fragmento es estable entre troceados` | Trocear dos veces el mismo texto da los mismos `id` |
| 19 | `el texto indexado incluye el titulo de seccion` | `indexedText` contiene el título (para que "regresión" encuentre el fragmento) |
| 20 | `el cuerpo del fragmento no lleva el titulo` | `chunk.text` no empieza con el título (para que la cita literal no se contamine) |
| 21 | `el texto del fragmento es una subcadena del texto del material` | **Invariante de fidelidad**: con `charStart`, `material.text.substring(charStart, charEnd) == chunk.text` |
| 21 | `el texto se puede buscar con su unidad` | Un fragmento de un material con `unitPath` encuentra "unidad" en `indexedText` |
| 22 | `el esquema detectado se puede listar` | `Chunker.outline(doc)` devuelve la lista de secciones (para la UI del docente) |
| 23 | `un documento vacio no da fragmentos` | ya existe |
| 24 | `un texto con acentos y eñes se trocea bien` | "Introducción" no se parte |

### 9.2 `Bm25IndexTest` (se **extiende**)

| # | Test | Qué verifica |
|---|---|---|
| 1…12 | Los 12 tests actuales | **Sin tocar** (§7.4) |
| 13 | `el indice invertido y el recorrido lineal dan el mismo resultado` | Implementación de referencia en el test (la lineal) vs. la de producción, 200 consultas aleatorias: mismos top-K y **mismos scores con 4 decimales** |
| 14 | `los empates se resuelven por orden de fragmento` | Dos fragmentos con el mismo score → gana el primero |
| 15 | `el indice queda consistente despues de reindexar` | Tras `upsert`/`remove`, `df` y `avgLen` siguen siendo los del corpus (comparado con una reconstrucción completa) |
| 16 | `el top-k acotado da el mismo resultado que ordenar todo` | `search(q, 5)` == `search(q, 100).take(5)` |
| 17 | `un top-k mayor que el corpus no rompe` | `search(q, 10_000)` sobre 3 fragmentos devuelve 3 |
| 18 | `la frecuencia documental se puede consultar` | `documentFrequency("motor")` == nº de fragmentos que lo contienen |
| 19 | `los terminos con peso se respetan` | `termWeights = {"parcial": 0.3}` baja el score de ese término (de `10-taxonomia.md`) |

### 9.3 `ChunkingPolicyTest` (nuevo)

| # | Test |
|---|---|
| 1 | `el pdf usa 600 caracteres` |
| 2 | `el markdown usa el chunkSize de la catedra` |
| 3 | `el valor de la catedra manda sobre el default` (`chunkSize = 500` ⇒ target 500) |
| 4 | `las diapositivas no tienen solapamiento` |

### 9.4 `CourseIndexTest` (nuevo)

| # | Test |
|---|---|
| 1 | `reindexar un material no re-trocea los demas` (se cuenta con un spy cuántas veces se llama al troceador) |
| 2 | `reindexar un material no cambia los resultados de los demas` |
| 3 | `borrar un material lo saca del indice` |
| 4 | `un material oculto no aporta fragmentos` |
| 5 | `un material sin texto no aporta fragmentos` |
| 6 | `el indice completo y el incremental dan los mismos resultados` (**test de equivalencia**: la red de seguridad más importante) |
| 7 | `el hash de indexacion detecta el texto editado` |

### 9.5 `TextStructureTest` / `HeadingDetectorTest` (nuevos)

| # | Test |
|---|---|
| 1 | `detecta los titulos markdown con su nivel` |
| 2 | `detecta la numeracion jerarquica` ("1", "1.1", "1.1.1") |
| 3 | `detecta los titulos con palabra` ("Tema 3", "Capítulo 2", "Unidad 4") |
| 4 | `detecta las lineas en mayusculas` |
| 5 | `ignora el membrete repetido` |
| 6 | `ignora los numeros sueltos de una lista` |
| 7 | `no confunde un parrafo numerado con un titulo` |
| 8 | `no ve titulos dentro de un bloque de codigo` |
| 9 | `reconoce la marca de pagina` |
| 10 | `el texto con lineas cortadas se reflowea` / `las frases cortas no se tocan` |

### 9.6 Test de extremo a extremo

| # | Test | Qué verifica |
|---|---|---|
| 1 | `un pdf de una pagina responde una pregunta` | Pipeline completo: extracción (texto de prueba) → troceado → índice → recuperación → el fragmento esperado está en el top-1 |
| 2 | `el fragmento recuperado tiene la pagina correcta` | El localizador no se pierde en el camino |

---

## 10. Plan de implementación

| Fase | Qué | Archivos | Riesgo |
|---|---|---|---|
| **F1** | `SentenceSplitter`, `TextNormalizer`, `TextStructure`, `HeadingDetector` (puro, testeable) | `backend/.../rag/retrieval/{SentenceSplitter,TextStructure,HeadingDetector}.kt`, `core/.../kitoru/material/TextNormalizer.kt` | Bajo (aditivo) |
| **F2** | `Chunker` nuevo **detrás de la misma firma**: `split(doc, size, overlap)` delega en el algoritmo nuevo con `ChunkingOptions.forShape(PROSE, size)` | `backend/.../rag/retrieval/Chunker.kt`, `Chunking.kt` | **Medio**: hay que mantener los 9 tests actuales en verde |
| **F3** | `Chunk` extendido (`page`, `slide`, `startMs`, `charStart`, `kind`, `id`) + `citationLabel` con localizador + `indexedText` | `backend/.../rag/retrieval/Models.kt` | Bajo (defaults) |
| **F4** | `Bm25Index` invertido + top-K acotado | `backend/.../rag/retrieval/Bm25Index.kt` | **Medio**: los scores tienen que ser idénticos (test de equivalencia) |
| **F5** | `CourseIndex` incremental + `TokenizedChunk` | `backend/.../rag/retrieval/CourseIndex.kt` | Bajo |
| **F6** | `RagEngine` usa `citationLabel` + el `Chunker` nuevo; `SourceRef` con los campos nuevos | `backend/.../rag/engine/RagEngine.kt`, `core/.../kitoru/domain/Chat.kt` | Bajo |
| **F7** | La UI: "Fragmentos" muestra sección y página; la cita es navegable | `:ui` | Bajo |

**Reglas de rollback**: F2 y F4 son los únicos que tocan algo que ya funciona. Los
dos se pueden hacer *detrás* de un flag (`RagConfig.chunkingVersion = 1 | 2`) durante
la transición, y los tests de equivalencia (`§9.4 #6`) son la red.

---

## 11. Riesgos y límites

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El detector de títulos produce falsos positivos | Secciones inventadas → citas raras | Validación por secuencia + filtro de contenido + el docente **ve y edita** el esquema en la pestaña "Fragmentos" |
| El detector **no** encuentra los títulos de un PDF muy plano | Se vuelve al comportamiento actual (corte por párrafos) | Mejor que hoy: al menos los límites son de párrafo, no de carácter |
| Fragmentos más chicos → más fragmentos → más índice | +85 % de fragmentos | El índice invertido lo compensa (4× menos RAM por token) y el solapamiento está acotado |
| El reflow puede alterar el texto del material | El texto guardado ≠ al indexado | El reflow es **idempotente** y se aplica **una vez, al extraer**; el chunker lo vuelve a aplicar y obtiene el mismo resultado |
| La taxonomía horneada en `indexedText` obliga a reindexar todo | Reindexado completo al cambiar una unidad | Aceptado (documentado en §8.3) |
| `avgLen` y `df` se recalculan en cada rearme | 40 ms | Aceptable |

**Lo que este diseño NO resuelve** (y queda explícito):

- **Sin embeddings**: el índice sigue siendo léxico. Un alumno que pregunta
  "auto" no encuentra "automóvil". La expansión por sinónimos es un tema aparte.
- **Sin OCR**: un PDF escaneado sigue sin texto (`03-materiales.md` §4.6).
- **La calidad del chunker no se puede medir sin un corpus real**: las cifras de este
  documento son de una simulación con material sintético representativo. El primer
  trabajo de implementación debería ser correr el chunker sobre **material real de 3
  cátedras** y medir: % de fragmentos que arrancan en mitad de una palabra (hoy 32 %,
  objetivo 0 %), nº de secciones detectadas por documento (hoy 1, objetivo 3–8) y
  hit-rate@5 sobre 20 preguntas por cátedra escritas por el docente.

---

## Anexo A · Reproducir las mediciones

Las cifras del documento salen de una simulación en Python del algoritmo exacto de
`Chunker.kt` y del algoritmo propuesto, sobre el material sintético descrito en §1.3
(2.633 palabras, 16.784 caracteres, 289 líneas, líneas cortadas a 95 columnas).

```python
# actual (puerto exacto de Chunker.kt)
def chunk_current(text, size=1200, overlap=150):
    out, heading, buf = [], "Apuntes-IA.pdf", []
    def flush():
        text = "\n".join(buf).strip(); buf.clear()
        if not text.strip(): return
        start = 0
        while start < len(text):
            end = min(start + size, len(text))
            if end < len(text):
                nl = text.rfind("\n", 0, end)
                if nl > start + size // 2: end = nl
            piece = text[start:end].strip()
            if piece: out.append((heading, piece))
            if end >= len(text): break
            start = max(end - overlap, start + 1)
    for line in text.split("\n"):
        if line.lstrip().startswith("```"): pass
        if line.startswith("#"):
            flush(); heading = line.lstrip("#").strip() or heading
        buf.append(line)
    flush()
    return out
```

El script completo (material sintético, métricas de corte, empaquetado y BM25 con
índice invertido) queda como referencia en el repositorio de diseño.

## Anexo B · Resumen de archivos

| Archivo | Acción | Qué |
|---|---|---|
| `backend/.../rag/retrieval/Chunking.kt` | **nuevo** | `ChunkingOptions`, `MaterialShape`, la política por tipo de material |
| `backend/.../rag/retrieval/TextStructure.kt` | **nuevo** | `Block`, `HeadingKind`, el escáner de bloques |
| `backend/.../rag/retrieval/HeadingDetector.kt` | **nuevo** | Los 6 patrones + la validación |
| `backend/.../rag/retrieval/SentenceSplitter.kt` | **nuevo** | El divisor de oraciones con abreviaturas |
| `backend/.../rag/retrieval/Chunker.kt` | **reescrito** | El algoritmo nuevo; conserva `split(doc, size, overlap)` |
| `backend/.../rag/retrieval/Models.kt` | **modificado** | `Chunk` con `page`/`slide`/`startMs`/`charStart`/`kind`/`id`; `citationLabel` con localizador |
| `backend/.../rag/retrieval/Bm25Index.kt` | **reescrito** | Índice invertido + top-K acotado + `fromTokens` |
| `backend/.../rag/retrieval/CourseIndex.kt` | **nuevo** | Índice incremental por material |
| `backend/.../rag/retrieval/Tokenizer.kt` | **nuevo** | `TokenizedChunk` (para la caché de tokens) |
| `backend/.../rag/engine/RagEngine.kt` | **modificado** | Renderiza `citationLabel` |
| `core/.../kitoru/material/TextNormalizer.kt` | **nuevo** | El `reflow` (idempotente) |
| `core/.../kitoru/domain/Chat.kt` | **modificado** | `SourceRef` con `page`/`slide`/`startMs`/`chunkId` |
| `backend/src/desktopTest/.../rag/retrieval/ChunkerTest.kt` | **extendido** | +24 tests |
| `backend/src/desktopTest/.../rag/retrieval/Bm25IndexTest.kt` | **extendido** | +7 tests (los 12 actuales, sin tocar) |
| `backend/src/desktopTest/.../rag/retrieval/CourseIndexTest.kt` | **nuevo** | El reindexado incremental y su equivalencia |
| `backend/src/desktopTest/.../rag/retrieval/HeadingDetectorTest.kt` | **nuevo** | La detección de títulos |
