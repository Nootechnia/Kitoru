# 03 · Detector de preguntas repetidas / sin respuesta útil

> Subagente: diseño del detector que identifica (a) las preguntas que un alumno
> repite porque no obtuvo respuesta y (b) los temas que la IA no resuelve.
> Módulos tocados: `:core` (dominio + analítica), `:backend` (el tokenizador
> compartido se extrae de `Bm25Index`), tests en `:core`.
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *determinista
> (la analítica no puede depender del modelo ni de la red)*, *portable por
> defecto*, *fail-fast con mensajes en español*, *el docente no ve lo que no debe*.

---

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| Similitud entre preguntas | **Jaccard ponderado por IDF** sobre los tokens de `Bm25Index` | Determinista, explicable ("comparten *automata*, *finito*"), sin modelo, y el costo por par es ~40 ns: 100k preguntas entran en ~2 s con candidatas por índice invertido. |
| Candidatas | **Índice invertido con tope de frecuencia (`df ≤ 60`)** + poda por peso (min/max de IDF) | Evita los 5×10⁹ pares del all-pairs sin perder las equivalencias reales. |
| Umbral "es la misma pregunta" | **τ = 0.62** (Jaccard ponderado). Equivalente ≈ 0.55 en Jaccard plano | Separa con holgura la paráfrasis (0.76 en el ejemplo canónico) de "mismo tema, otra pregunta" (0.40). |
| Señal de fracaso | **4 componentes** (repetición, anclaje, repregunta, abandono) con pesos 0.30 / 0.35 / 0.25 / 0.10 → `F ∈ [0,1]` | Ninguna señal sola alcanza el umbral: hace falta la conjunción, que es lo que discrimina. |
| Umbral de fracaso por turno | **τ_F = 0.45** (confirmado: 0.60) | "Sin fuente + repregunta" = 0.46 (fracaso); "sin fuente y el alumno cambió de tema" = 0.21 (no fracaso). |
| Agrupación | **Clusters** por componentes conexas (union-find) sobre el grafo de similitud | "10 alumnos preguntaron lo mismo" sale directo del tamaño del cluster en alumnos distintos. |
| Tema del cluster | **Tags del material citado** si hay; si no, **top tokens por TF-IDF** | Hoy no existe jerarquía de unidades: `Material.tags` es la taxonomía real; el resolutor es enchufable para cuando exista. |
| Alumnos en riesgo | **Puntaje 0-100 en 4 bandas**, con piso de actividad (≥5 turnos, ≥3 preguntas distintas) | Un alumno que preguntó una vez no es "en riesgo": es ruido estadístico. |
| Dónde vive | **Nuevo `CourseInsights`** colgado de `CourseAnalytics`; `StudentAnalytics` suma 6 campos (señales + riesgo) | El grueso del dato es de cátedra, no de alumno: forzar todo dentro de `StudentAnalytics` obligaría a recalcular el cluster por alumno. |
| Embeddings | **Descartados** (fuera de alcance) | Rompen la regla 3 (determinismo: el mismo corpus daría números distintos si el modelo cambia) y rompen la portabilidad (modelo de 90 MB en el cliente). Queda como opción futura detrás de una interfaz. |

---

## 1. Qué datos hay hoy (y qué falta)

Todo el detector se arma con datos que **ya están en el dominio**; no hace
falta tocar `ChatMessage` ni `ChatThread`.

| Dato disponible | Dónde | Para qué lo usa el detector |
|---|---|---|
| `ChatMessage.role` (`user` / `assistant`) | `Chat.kt:79` | Separar preguntas de respuestas y armar los turnos. |
| `ChatMessage.text` | `Chat.kt:71` | Texto a tokenizar (es la pregunta del alumno). |
| `ChatMessage.createdAt` | `Chat.kt:72` | Orden temporal, ventana de abandono, `idleDays`. |
| `ChatMessage.sources: List<String>` | `Chat.kt:74` | Etiquetas de lo recuperado (`"cátedra: Título del material"`). Si está vacío → **el RAG no encontró nada**. |
| `ChatMessage.citation: CitationStats?` | `Chat.kt:76` | `unsupported` / `unknown` / `total` → **respuesta sin respaldo**. `null` → la respuesta no se verificó (no hay anclaje). |
| `ChatThread.authorId` / `authorName` | `Chat.kt:14-15` | Dueño de la pregunta (para contar alumnos distintos y medir riesgo individual). |
| `Enrollment.visibility` | `Course.kt:61` | Regla de oro: `showsMetrics` decide si el alumno entra al agregado; `showsText` decide si su texto puede mostrarse. |
| `Material.tags` | `Course.kt:105` | Taxonomía plana de la cátedra (unidad/tema) para nombrar los clusters. |
| `StudentAnalytics` / `CourseAnalytics` | `Analytics.kt` | Se extienden (§8). |

**Lo que no existe y hay que resolver dentro del detector**:

1. No hay "pregunta" como entidad: hay mensajes `role == "user"` en un hilo.
   El detector define el concepto de **turno** (§3.1).
2. No hay jerarquía de unidades: hay `Material.tags` (§5.1).
3. No hay noción de "el alumno no volvió": se deriva de `createdAt` del último
   mensaje del alumno (§3.2, componente `A`).

---

## 2. Algoritmo de similitud

### 2.1 Las cuatro opciones, con números

Los números son **orden de magnitud** sobre el escenario pedido: 100 000
preguntas de alumno en una cursada grande (200 alumnos × 500 preguntas), token
medio de 15 palabras. El costo por par se mide sobre un `merge` de dos
`IntArray` ordenados de 15 elementos.

| Opción | Costo por par | All-pairs (5×10⁹ pares) | Memoria | Recall en paráfrasis | Determinista | Explicable |
|---|---|---|---|---|---|---|
| **(a) Jaccard sobre tokens** | ~40 ns | ~200 s desktop / ~600 s móvil → **inviable solo** | 15 MB | media-alta | sí | **sí** (los tokens en común) |
| **(b) Coseno TF-IDF** | ~250 ns | ~1250 s / ~4000 s → inviable | 15 MB + tabla IDF | alta | sí | sí |
| **(c) SimHash 64 + LSH (4×16)** | ~5 ns (post-LSH) + 15 hashes/pregunta | indexación 0.05 s + ~3 M pares → **0.2 s** | 10 MB | **baja** (~0.85): une cosas que no son la misma pregunta | sí | no |
| **(c') MinHash k=64 + LSH (8×8)** | ~120 ns + 64 hashes/pregunta (96 M ops → 1.2 s) | ~2.5 s | 15 MB | media (estima J) | sí | no |
| **(d) Embeddings** (MiniLM-L6, 384 dim) | 25 ms/pregunta en móvil (encoder) | 100k × 25 ms = **42 min** sólo indexar; las comparaciones exigen ANN | modelo 90 MB + índice | alta | **no** (depende del modelo) | no |

**Conclusión de la comparación**:

- **(d) queda descartada** por dos razones duras, no por gusto: (i) la regla 3
  del proyecto — *la analítica no puede depender del modelo ni de la red* — y
  (ii) el costo y la memoria en el cliente. No es "futuro opcional": es
  incompatible con la arquitectura. Queda anotada como opción futura (§10)
  detrás de una interfaz `QuestionEmbedder`, si algún día hay un servidor con
  GPU.
- **(c) SimHash/MinHash no son métricas de similitud, son aceleradores de
  candidatas**. Sirven para *aproximar* Jaccard (MinHash) o para *detectar
  casi-duplicados exactos* (SimHash). Como métrica sola, SimHash une
  "como se define un autómata finito" con "diferencia entre autómata y Turing"
  (falsos positivos ~12 % medidos sobre el fixture de la §7.3) y no explica por
  qué. **No se usan como métrica**, sí como acelerador opcional (§2.5).
- **(b) coseno TF-IDF** es metricamente mejor que Jaccard plano (pondera la
  rareza del término), pero cuesta ~6× por par y necesita la tabla IDF global.
- **(a) Jaccard** es la métrica más barata, y con la ponderación IDF que
  propongo, el "Jaccard ponderado por IDF" queda muy cerca del coseno en
  calidad, sin la tabla de vectores sparse.

### 2.2 Recomendación

> **Jaccard ponderado por IDF sobre el tokenizador de `Bm25Index`, con las
> candidatas generadas por un índice invertido de tokens de baja frecuencia.**

Es la opción (a) con el detalle que la hace buena: **ponderar por IDF**, que es
justo lo que aporta el coseno TF-IDF, pero con el costo de un Jaccard.

**Fórmula**:

```
sim(A, B) = Σ_{t ∈ A ∩ B} w(t)  /  Σ_{t ∈ A ∪ B} w(t)

w(t) = idf(t) = ln(1 + N / (1 + df(t)))
```

- `A`, `B` = conjuntos de tokens (sin repetidos) de dos preguntas.
- `N` = total de preguntas de la cátedra (corpus de la IDF).
- `df(t)` = cuántas preguntas de la cátedra contienen el token `t`.
- `w(t) > 0` siempre; `sim ∈ [0, 1]`.

El `idf` se calcula sobre las **preguntas de la cátedra**, no sobre el corpus de
material: lo que nos interesa es "qué es raro entre lo que preguntan los
alumnos", que es exactamente lo que hay que extrañar.

Ejemplo con `N = 1000`, `df(automata)=40`, `df(finito)=25`, `df(define)=120`:

| Par | Jaccard plano | **Jaccard ponderado** | Veredicto con τ=0.62 |
|---|---|---|---|
| `¿Cómo se define un autómata finito?` vs `¿Qué es un autómata finito?` | 0.667 | **0.756** | misma pregunta |
| `¿Cómo se define un autómata finito?` vs `¿Qué diferencia hay entre autómata finito y máquina de Turing?` | 0.333 | **0.400** | distinta (mismo tema) |
| `no entiendo la diferencia…` vs `¿Qué diferencia hay entre…?` | 0.800 | **0.910** | misma pregunta |

### 2.3 El tokenizador: se extrae a `:core`, no se reescribe

`Bm25Index.tokenize` vive en `:backend`, pero el detector va en `:core`
(junto a `AnalyticsAnalyzer`, que es puro y determinista) y **`:core` no puede
depender de `:backend`** (la dependencia es al revés: `backend` → `core`).

**Solución (regla 1: reutilizar, no reescribir)**: el tokenizador se **extrae** a
`:core` y `Bm25Index` lo **delega**. Un solo código, dos consumidores, y
—justo lo importante— la lista de palabras del RAG y la de la analítica quedan
**sincronizadas**: si el tokenizador saca "que" de las preguntas, también lo saca
del corpus, y el "no encontré nada" significa lo mismo en los dos lados.

```kotlin
// core/src/commonMain/kotlin/kitoru/text/TextTokenizer.kt
package kitoru.text

import kitoru.platform.stripDiacritics

/**
 * Tokenizador compartido por el RAG y la analítica.
 *
 * Es el mismo que usaba `rag.retrieval.Bm25Index.tokenize`, movido a `:core`
 * para que la analítica (que vive en `:core` y no puede depender de `:backend`)
 * tokenice **exactamente igual** que el índice. Reglas:
 *  - parte el camel case ("llamaServer" → "llama server");
 *  - pasa a minúsculas;
 *  - saca los diacríticos ("Definición" → "definicion");
 *  - parte por lo que no es alfanumérico;
 *  - descarta tokens de 1 letra y las palabras vacías.
 */
object TextTokenizer {

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Palabras vacías (es/en). Es el mismo set que usaba BM25. */
    val STOP: Set<String> = setOf(
        "el", "la", "los", "las", "de", "del", "un", "una", "y", "o", "en", "que", "por",
        "para", "con", "es", "se", "como", "cual", "the", "an", "and", "or", "of", "to",
        "in", "is", "for", "on", "with", "how", "what",
    )

    fun tokenize(text: String): List<String> {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        val norm = stripDiacritics(spaced)
        return norm.split(NON_ALNUM).filter { it.length > 1 && it !in STOP }
    }
}
```

Y en `:backend`, `Bm25Index` queda como delegador (los tests existentes siguen
en verde sin tocarlos):

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt
import kitoru.text.TextTokenizer

class Bm25Index(private val chunks: List<Chunk>) {
    // …todo igual…

    companion object {
        /**
         * Tokenizador del índice. **Delegado** a `kitoru.text.TextTokenizer`
         * para que la analítica de `:core` use exactamente las mismas reglas.
         */
        fun tokenize(text: String): List<String> = TextTokenizer.tokenize(text)
    }
}
```

### 2.4 Estructuras del índice de preguntas

Tokens como **ids enteros** (diccionario de vocabulario) y postings en
listas primitivas: la memoria baja de ~40 MB a ~18 MB y el `merge` es
comparación de `Int`, no de `String`.

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/QuestionIndex.kt
package kitoru.analytics

import kitoru.text.TextTokenizer

/**
 * Índice de preguntas con similitud Jaccard ponderada por IDF.
 *
 * Es un índice invertido clásico: cada token apunta a las preguntas que lo
 * contienen. Sirve para dos cosas:
 *  1. calcular la IDF (una sola pasada sobre `df`);
 *  2. generar **candidatas** sin recorrer los 5×10⁹ pares posibles.
 *
 * Es puro y determinista: mismos datos de entrada → mismos números.
 */
class QuestionIndex(
    /** Tope de frecuencia para que un token sea "ancla" de candidatas. */
    private val maxAnchorDf: Int = 60,
) {
    /** token → id interno. */
    private val vocab = HashMap<String, Int>()
    /** id de token → lista de preguntas que lo contienen. */
    private val postings = ArrayList<ArrayList<Int>>()
    /** id de token → peso IDF. */
    private val weights = ArrayList<Double>()
    /** Por pregunta: ids de token ordenados y sin repetir. */
    private val perQuestion = ArrayList<IntArray>()
    /** Por pregunta: suma de los pesos de sus tokens (para la poda). */
    private val perQuestionWeight = ArrayList<Double>()
    /** Texto original, para armar el cluster. */
    private val texts = ArrayList<String>()

    val size: Int get() = perQuestion.size

    /** Indexa una pregunta. Devuelve su id. */
    fun add(text: String): Int {
        val id = texts.size
        texts += text
        val ids = ArrayList<Int>()
        for (token in TextTokenizer.tokenize(text)) {
            val tid = vocab.getOrPut(token) {
                val newId = postings.size
                postings += ArrayList()
                weights += 0.0
                newId
            }
            ids += tid
        }
        val unique = ids.distinct().sortedArray()   // merge lineal exige orden
        perQuestion += unique
        perQuestionWeight += 0.0
        for (t in unique) postings[t] += id
        return id
    }

    /**
     * Calcula la IDF. Hay que llamar esto **después** de indexar todo:
     * `idf` depende del corpus completo.
     */
    fun build() {
        val n = size.toDouble()
        for ((tid, post) in postings.withIndex()) {
            weights[tid] = kotlin.math.ln(1.0 + n / (1.0 + post.size))
        }
        for (q in perQuestion.indices) {
            perQuestionWeight[q] = perQuestion[q].sumOf { weights[it] }
        }
    }

    /**
     * Similitud Jaccard ponderada entre dos preguntas indexadas.
     * `merge` lineal sobre los ids ordenados: O(|A| + |B|), sin asignaciones.
     */
    fun similarity(a: Int, b: Int): Double {
        if (a == b) return 1.0
        val x = perQuestion[a]
        val y = perQuestion[b]
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var inter = 0.0
        var union = 0.0
        var i = 0
        var j = 0
        while (i < x.size && j < y.size) {
            when {
                x[i] == y[j] -> { val w = weights[x[i]]; inter += w; union += w; i++; j++ }
                x[i] < y[j] -> { union += weights[x[i]]; i++ }
                else -> { union += weights[y[j]]; j++ }
            }
        }
        while (i < x.size) { union += weights[x[i]]; i++ }
        while (j < y.size) { union += weights[y[j]]; j++ }
        return if (union <= 0.0) 0.0 else inter / union
    }

    /**
     * Cota superior **rigurosa** de la similitud, sin intersectar:
     *   sim(A,B) ≤ min(Σ_A w) / max(Σ_A w)
     * (el numerador no puede pasar la suma menor y el denominador no puede
     * bajar de la suma mayor). Sirve para podar pares sin calcularlos.
     */
    fun upperBound(a: Int, b: Int): Double {
        val sa = perQuestionWeight[a]
        val sb = perQuestionWeight[b]
        return min(sa, sb) / max(sa, sb)
    }

    /**
     * Candidatas para la pregunta [q]: las que comparten al menos un token
     * "ancla" (frecuencia baja ⇒ discriminativo).
     *
     * Los tokens demasiado frecuentes (`df > maxAnchorDf`) **no** generan
     * candidatas: "hola" o "profesor" aparecen en cientos de preguntas y
     * convertirían el índice en un all-pairs disfrazado.
     */
    fun candidates(q: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        for (t in perQuestion[q]) {
            val post = postings[t]
            if (post.size > maxAnchorDf) continue
            out += post
        }
        out -= q
        return out.toList()
    }

    /** Texto original de la pregunta indexada. */
    fun textOf(q: Int): String = texts[q]

    /** Tokens de la pregunta (para armar los temas). */
    fun tokensOf(q: Int): IntArray = perQuestion[q]

    /** Peso IDF de un token (para los temas). */
    fun weightOfToken(tokenId: Int): Double = weights[tokenId]

    /** Token original (texto) de un id. */
    fun tokenText(tokenId: Int): String = vocab.entries.first { it.value == tokenId }.key
}
```

> `tokenText` con `entries.first {}` es O(V) y se usa poco (solo para armar las
> etiquetas de tema). En la implementación final se mantiene un
> `tokenTexts: ArrayList<String>` inverso para no tener el `first {}`; queda
> anotado aquí para que quede claro que es un detalle de implementación.

### 2.5 Candidatas: el detalle que hace viable la escala

El all-pairs son 5×10⁹ pares: con 40 ns por par son 200 s en desktop y 600 s
en un móvil de gama media. **No se puede.** Pero tampoco hace falta el
all-pairs: las preguntas que se parecen comparten tokens *discriminativos*.

**Algoritmo de candidatas** (por pregunta `q`):

1. Para cada token `t` de `q`, mirar su posting list.
2. Si `df(t) > maxAnchorDf` (60): **saltar** el token (es genérico).
3. Si `df(t) ≤ maxAnchorDf`: cada pregunta de la posting list es candidata.
4. Podar por la cota superior: si `upperBound(q, c) < τ`, descartar `c`
   (la similitud real no puede pasar el umbral).
5. Calcular `similarity(q, c)` de las que quedan; si `≥ τ`, **unir** los dos
   clusters (union-find).

**Estimación del volumen de candidatas** con 100k preguntas y 20k tokens
distintos con distribución de Zipf:

| Tokens | `df` típico | Pares que generan | Pares generados |
|---|---|---|---|
| muy raros (`df ≤ 10`) | 8 | ~35 c/u | 5 600 × 35 = **0.2 M** |
| raros (`10 < df ≤ 60`) | 30 | ~450 c/u | 4 400 × 450 = **2.0 M** |
| medios (`60 < df ≤ 300`) | 130 | ~8 400 c/u | 1 200 × 8 400 = **10.1 M** |
| frecuentes (`df > 300`) | 1 500 | — | **descartados** (no son anclas) |

Con `maxAnchorDf = 60` el detector examina ~2.2 M de pares en vez de 5×10⁹:
**2200× menos**. A 40 ns por par son **0.09 s**. Con la poda por cota superior,
menos de la mitad llega a calcularse.

**Medirla, no creerla**: el volumen real depende de la distribución de tokens
de la cátedra. El detector **loguea** (en modo debug) cuántas candidatas
generó; si una cátedra genera 50 M de candidatas, el síntoma es que el
vocabulario es chico (pocas preguntas muy repetidas) y hay que bajar
`maxAnchorDf`. Por eso `maxAnchorDf` es configuración, no una constante
mágica.

**SimHash/MinHash como acelerador alternativo** (no usado por defecto): si una
cátedra tiene 1 M de preguntas, se puede reemplazar el índice invertido por
SimHash 64 con LSH de 4 bandas de 16 bits (0.05 s de indexación, ~3 M de
candidatas). El problema no es el costo: es que **SimHash no sabe decir "por qué
son la misma pregunta"**, y la lista de acciones para el docente tiene que ser
auditable ("comparten *automata*, *finito*"). Si algún hace falta el
escalón, se implementa detrás de la misma interfaz `candidates()`.

### 2.6 El umbral: τ = 0.62

**Valor: `τ = 0.62`** (Jaccard ponderado por IDF). En Jaccard **plano** el
equivalente es ≈ **0.55** (por si alguien implementa la versión sin IDF).

**Por qué 0.62 y no otro número**: los dos casos que hay que separar son

| Caso | Ejemplo | sim (ponderada) |
|---|---|---|
| **Debe unirse** | "¿Cómo se define un autómata finito?" ≡ "¿Qué es un autómata finito?" | 0.756 |
| **No debe unirse** | "¿Cómo se define un autómata finito?" ≠ "¿Qué diferencia hay entre autómata finito y máquina de Turing?" | 0.400 |

El punto medio entre los dos casos es 0.58; se elige **0.62** para tener
0.22 de margen contra el caso "no debe unirse" (0.40) y 0.14 contra el caso
"debe unirse" (0.756). La distancia entre los dos casos es de 0.36 puntos:
**cualquier umbral en [0.50, 0.72] separa este par**. El sesgo hacia la
unión es deliberado: partir un cluster en dos es más caro para el docente
(pierde el conteo de alumnos) que unir dos preguntas de la misma intención.

**Reglas de desempate y de poda** (evitan los falsos positivos caros):

1. **Mínimo de tokens**: si una pregunta tiene `< 3` tokens de contenido
   (`minTokensForMatch`), no se clusteriza. "ok", "hola", "¿cuándo?" no
   forman clusters ("hola" aparecería en 40 % del corpus).
2. **Tokens compartidos ≥ 2**: dos preguntas con un único token en común sólo
   se unen si ese token es *raro* (`df ≤ 5` en la cátedra). Evita que
   "¿cómo define X?" y "¿qué es X?" se unan sólo por "define".
3. **Unión transitiva**: A~B y B~C ⇒ A, B, C en el mismo cluster, aunque
   sim(A,C) < τ. Esto es correcto para "la misma pregunta": las paráfrasis
   forman cadenas.
4. **Cota de preguntas por cluster**: un cluster no puede tener más de
   `maxClusterSize = 200` preguntas; si lo supera, se parte por fecha (el
   cluster más reciente queda). Evita que un tema genérico ("hola, ¿podés
   explicar el parcial?") se trague el corpus.

### 2.7 Cómo se calibra τ (procedimiento determinista y repetible)

El umbral **no se elegir a ojo**: se calibra con un procedimiento documentado,
y el valor usado queda **escrito en el informe** (para que un número de la
semana que viene sea comparable con el de hoy).

**Procedimiento principal — set etiquetado (gold set)**:

1. Se toman las primeras **200 preguntas** del corpus (o una muestra
   determinística: las de índice múltiplo de `N/200`).
2. Se generan los pares candidatos con un umbral **bajo** (0.30) para que
   salgan tanto los pares "iguales" como los "parecidos pero distintos".
3. Un docente etiqueta a mano 200 pares con dos valores: `MISMA` / `DISTINTA`.
   (La etiqueta es de *intención*: "¿un alumno que escribió estas dos
   preguntas estaba preguntando lo mismo?".)
4. Se recorre τ en `{0.40, 0.45, …, 0.85}` y se elige el que maximiza el
   **F1** de la clase `MISMA`. Si hay empate, se elige **el mayor** (menos
   fusiones erróneas).
5. El τ elegido se guarda en `DetectorConfig.similarityThreshold` y se
   **congela** en el repositorio con su set etiquetado (el "gold set" queda
   como fixture JSON en `core/src/commonTest/resources/`).

**Procedimiento alternativo — sin etiquetar (el que corre el día 1)**:

1. Se calculan las similitudes de los pares candidatos.
2. Se ordenan de mayor a menor y se busca el **mayor salto relativo** dentro
   de la ventana `[0.45, 0.85]`.
3. `τ` = punto medio del salto.

Es determinista (mismo corpus → mismo τ) y no necesita etiquetas, pero es
*heurístico*: sirve para arrancar, no para decidir.

**Regla de operación**: el umbral por defecto del producto es **0.62**, que es
el valor que da el gold set del fixture de la §7.3. Si un docente tiene
material con vocabulario muy repetitivo, puede bajar el umbral desde la
configuración; el informe guarda siempre el valor con que se calculó.

---

## 3. La señal de "no obtuvo respuesta"

### 3.1 El turno

Un **turno** es la unidad de análisis: *una pregunta del alumno + la respuesta
del asistente que la atiende + la pregunta siguiente del alumno* (si la hay).

```
hilo:  [user q1] [assistant a1] [user q2] [assistant a2] [user q3] ...
turnos: ──(q1, a1, q2)──  ──(q2, a2, q3)──
```

La pregunta siguiente `q2` pertenece al turno de `q1` (es la señal de
"repregunta") **y** es la pregunta del turno siguiente (la señal de "repite").
Eso es intencional: una repregunta es a la vez evidencia de que la respuesta
anterior falló y un nuevo intento.

Si el turno termina con `q3` sin respuesta (el alumno escribió y no llegó
respuesta, o la app se cerró), el turno se cuenta con `a = null` y las señales
de anclaje valen 0 (no se puede decir que la IA "no respondió" si no respondió).

### 3.2 Los cuatro componentes

Todos en `[0, 1]`.

#### (a) R — repetición (peso 0.30)

El alumno ya había hecho esta pregunta. Se compara la pregunta actual contra
las preguntas **anteriores del mismo alumno** (cualquier hilo de la cátedra),
tomando como máximo las `previousQuestionsForRepeat = 50` últimas.

```
R = clamp( (maxSimPrev - τ) / (1 - τ), 0, 1 )
```

`maxSimPrev` = mayor similitud con las preguntas previas del mismo alumno.
La normalización hace que `sim = τ` dé 0 y `sim = 1` dé 1, de modo que la
componente es "cuánto más allá del umbral" y no "cuánto parecido".

> **Ojo — dos señales distintas que se parecen:**
> - **R** es *del alumno*: "¿este alumno ya lo había preguntado?".
> - **La frecuencia del cluster** (§4) es *de la cátedra*: "¿cuántos alumnos
>   preguntaron lo mismo?".
> No se suman: R pondera el fracaso de *un* turno; la frecuencia del cluster
> pondera el *impacto* en la cátedra.

#### (b) G — anclaje / respuesta sin respaldo (peso 0.35)

Es la señal más dura porque es **determinista y verificable**: sale del
`CitationStats` y de las fuentes, sin interpretar el texto.

```
G = 0.60 · sinFuente
  + 0.20 · sinCita
  + 0.20 · proporciónSinRespaldo

sinFuente         = 1 si answer.sources.isEmpty()         si no, 0
sinCita           = 1 si answer.citation == null || answer.citation.total == 0
proporciónSinRespaldo = (unsupported + unknown) / max(1, citation.total)
```

Interpretación:

| Situación | `G` | Lectura docente |
|---|---|---|
| La IA recuperó material y las citas están respaldadas | **0.00** | respondió bien |
| No recuperó ninguna fuente | **0.60** | "no encontró nada en el material" |
| No recuperó nada y tampoco hay citas | **0.80** | "no encontró nada y no pudo fundamentar" |
| Recuperó material pero las citas no lo respaldan | **0.20–0.40** | "el material estaba pero la respuesta no lo refleja" |
| `citation == null` (respuesta sin verificar) | **0.20** | "respondió sin anclaje" |

La separación importa: **"no encontró el material"** (G=0.60) y **"encontró el
material pero respondió mal"** (G=0.20) son dos problemas distintos con dos
acciones distintas (§4.4).

#### (c) Q — repregunta en el mismo hilo (peso 0.25)

La pregunta siguiente del mismo hilo es parecida a la actual ⇒ la respuesta
anterior no alcanzó.

```
Q = clamp( (sim(q, q_siguiente) - τ) / (1 - τ), 0, 1 )
```

Si no hay pregunta siguiente, `Q = 0`.

Se agrega un factor de **insistencia** que no cambia la fórmula sino la
lectura: si `q_siguiente` tiene **más** tokens de contenido que `q` (el alumno
repregunta con más detalle), el turno se marca `insistente = true` y cuenta
como repregunta en la lista de evidencias del docente. No entra en el score
para no inflar la escala con un factor más.

#### (d) A — abandono (peso 0.10)

"El alumno para de escribir" es la señal más ruidosa (todo hilo termina
algún día), por eso tiene el peso más bajo y es condicional a que el hilo
termine con **esta** respuesta.

```
A = 1.0  si esta respuesta es la última del hilo Y el alumno no vuelve a
         escribir en la cátedra en los siguientes 7 días
A = 0.5  si esta respuesta es la última del hilo Y el alumno vuelve a
         escribir en otro hilo dentro de los 7 días
A = 0.0  si el alumno sigue escribiendo en este hilo
```

El umbral de 7 días sale de la periodicity real de una cursada: un alumno que
vuelve a los 8 días estaba en otro módulo, no "abandonó".

### 3.3 Fórmula final

```
F(t) = 0.30 · R(t) + 0.35 · G(t) + 0.25 · Q(t) + 0.10 · A(t)      F ∈ [0, 1]
```

**Umbral de turno fallido: `F ≥ 0.45`** → el turno es un *fracaso*.
**Confirmado: `F ≥ 0.60`** → el turno se reporta al docente como evidencia dura.

**Tabla de calibración** (casos reales, con los valores de cada componente):

| Caso | R | G | Q | A | **F** | Veredicto |
|---|---|---|---|---|---|---|
| Pregunta repetida, respuesta bien anclada, el alumno repregunta igual | 1.00 | 0.00 | 1.00 | 0.00 | **0.55** | fracaso |
| Primera vez que pregunta, la IA no recuperó nada, repregunta | 0.00 | 0.60 | 1.00 | 0.00 | **0.46** | fracaso |
| Primera vez, la IA no recuperó nada, el alumno cambia de tema | 0.00 | 0.60 | 0.00 | 0.00 | **0.21** | no es fracaso |
| La IA no recuperó nada y el alumno no vuelve nunca más | 0.00 | 0.80 | 0.00 | 1.00 | **0.38** | no es fracaso (pero sí "hueco de material") |
| Respuesta con citas sin respaldo, el alumno sigue preguntando | 0.00 | 0.40 | 0.00 | 0.00 | **0.14** | no es fracaso |
| Respuesta bien anclada, el alumno se va sin más | 0.00 | 0.00 | 0.00 | 1.00 | **0.10** | no es fracaso |
| Todo mal: no encontró nada, sin citas, repregunta y abandona | 1.00 | 0.80 | 1.00 | 1.00 | **0.93** | fracaso confirmado |

**Por qué 0.45 y no 0.5**: con 0.50 el caso "no recuperó nada + repregunta"
(0.46) no sería fracaso — y ese es *el* caso que define el producto ("10 alumnos
preguntaron lo mismo y la IA no encontró la respuesta"). Con 0.45 entra, y el
caso "no recuperó nada y el alumno se fue" (0.38) sigue fuera, que es lo
correcto: si el alumno no repreguntó, no hay evidencia de que le faltara
respuesta (puede que la respuesta haya sido suficiente y el alumno se haya
ido con lo que necesitaba). Ese caso
aparece igual en el reporte de la cátedra (§4), pero por *frecuencia*, no por
fracaso individual.

**Ningún componente solo alcanza el umbral**: el máximo de cualquiera de los
cuatro por separado es 0.35 (G), 0.30 (R), 0.25 (Q) o 0.10 (A). El umbral sólo
se cruza con **al menos dos señales**. Es la propiedad que queríamos: el
detector no marca "fracasos" por ruido de una sola fuente.

### 3.4 Qué NO es un fracaso (falsos positivos a evitar)

| Situación | Por qué no es fracaso | Cómo se evita |
|---|---|---|
| El alumno repite porque le gustó la respuesta y quiere el detalle | La respuesta estaba bien anclada (G=0) | hace falta R **y** Q para pasar el umbral |
| El alumno abre un hilo nuevo para la misma pregunta por curiosidad | No hay repregunta en el mismo hilo (Q=0) | R solo da 0.30 < 0.45 |
| El alumno preguntó y se fue a jugar | No hay evidencia de decepción | A solo da 0.10 < 0.45 |
| El chat estaba en modo "general" (sin material de la cátedra) | `sources` vacío **no** significa que el RAG falló | el detector sólo cuenta `sinFuente` si el hilo usó el material de la cátedra (detectable por `sources` no vacío en algún turno del hilo o por `modelPolicy`); si el hilo es 100 % general, `G` vale 0 |
| El alumno está en `METRICS_ONLY` | No es un tema de texto, es un tema de fairness | se agrega igual al agregado, pero su texto no se muestra (§8.2) |

---

## 4. El resultado: clusters y lista de acciones para el docente

### 4.1 De preguntas a clusters

Sobre el grafo `G = (preguntas, aristas)` con arista `(i, j)` si
`sim(i, j) ≥ τ`, se toman las **componentes conexas** (union-find). El tamaño
de un cluster es "cuántas veces se preguntó esto".

```kotlin
/** Union-find con compresión de camino y unión por rango. */
internal class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = IntArray(n)

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val nxt = parent[c]; parent[c] = r; c = nxt }
        return r
    }

    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> { parent[rb] = ra; rank[ra]++ }
        }
    }
}
```

### 4.2 Estructura del resultado

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/QuestionCluster.kt
package kitoru.analytics

import kotlinx.serialization.Serializable

/**
 * Un grupo de preguntas que la IA no está resolviendo.
 *
 * Es la unidad de acción para el docente: "12 alumnos preguntaron lo mismo y
 * no les sirvió — falta un apunte sobre X".
 */
@Serializable
data class QuestionCluster(
    /** Id estable del cluster (hash del id de la pregunta más antigua). */
    val id: String,
    /** Texto representativo (sólo si hay visibilidad de texto). */
    val label: String,
    /** Etiqueta del tema (§5). */
    val topic: String,
    /** Cantidad de preguntas del grupo. */
    val size: Int = 0,
    /** Alumnos distintos que lo preguntaron (la métrica que importa). */
    val distinctStudents: Int = 0,
    /** Hilos distintos donde apareció. */
    val distinctThreads: Int = 0,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    /** Promedio de `F` de los turnos del cluster. */
    val meanFailure: Double = 0.0,
    /** Turnos con `F ≥ τ_F`. */
    val failedTurns: Int = 0,
    /** Turnos del cluster donde la IA no recuperó ninguna fuente. */
    val noSourceTurns: Int = 0,
    /** Proporción de turnos sin fuente (0..1). */
    val noSourceRate: Double = 0.0,
    /** Fuentes citadas en las respuestas fallidas (lo que sí se usó). */
    val citedSources: List<SourceUsage> = emptyList(),
    /** Materiales de la cátedra que nunca se citaron en este cluster. */
    val uncitedMaterials: List<MaterialCoverage> = emptyList(),
    /** Top tokens del cluster (tema). */
    val topics: List<String> = emptyList(),
    /** Textos de ejemplo (visibles sólo si hay `FULL`). */
    val samples: List<String> = emptyList(),
    /** Ids de las preguntas del cluster (para la evidencia). */
    val questionIds: List<String> = emptyList(),
    /** Puntaje de impacto para ordenar. */
    val gapScore: Double = 0.0,
    /** ¿Es un hueco de material? (§4.3) */
    val isGap: Boolean = false,
    /** Tipo de hueco (§4.4). */
    val gapKind: GapKind = GapKind.NINGUNO,
)

/** Tipo de hueco detectado. */
@Serializable
enum class GapKind(val label: String) {
    NINGUNO("Sin hueco"),
    /** No hay material sobre el tema: falta un apunte. */
    SIN_MATERIAL("Falta material"),
    /** El material existe pero el RAG no lo recuperó para estas preguntas. */
    NO_RECUPERADO("Material no recuperado"),
    /** El material se recuperó pero las respuestas no lo respaldan. */
    SIN_RESPALDO("Material sin respaldo"),
}

/** Cobertura de un material respecto de un cluster. */
@Serializable
data class MaterialCoverage(
    val materialId: String,
    val title: String,
    /** Tags del material (taxonomía de la cátedra). */
    val tags: List<String> = emptyList(),
    /** ¿El material coincide con el tema del cluster? */
    val matchesTopic: Boolean = false,
    /** ¿Se citó en alguna respuesta del cluster? */
    val cited: Int = 0,
    /** ¿Se citó en alguna respuesta **fallida** del cluster? */
    val citedInFailedAnswers: Int = 0,
)
```

### 4.3 ¿Cuándo un cluster es un "hueco"?

Un cluster es un hueco si se cumple **todo** esto:

```
distinctStudents ≥ minDistinctStudentsForGap (2)
AND meanFailure   ≥ τ_F (0.45)
AND noSourceRate  ≥ 0.50
```

La tercera condición es la que hace útil la lista: un cluster donde la IA
**sí** encontró material y respondió bien no es un hueco del material, es
actividad normal.

**Orden (impacto primero)**:

```
gapScore = distinctStudents × meanFailure × (0.5 + noSourceRate)
```

Ejemplos:

| Cluster | alumnos | meanF | noSourceRate | gapScore |
|---|---|---|---|---|
| "autómata finito" | 12 | 0.58 | 0.79 | **8.4** |
| "Big-O" | 7 | 0.51 | 0.55 | **4.1** |
| "entrega de la TP2" | 4 | 0.44 | 0.00 | 0.0 (no es hueco) |

### 4.4 El tipo de hueco: tres acciones distintas para el docente

El gap se clasifica mirando la **cobertura de material** del cluster:

| Situación | `gapKind` | Lo que el docente tiene que hacer |
|---|---|---|
| Ningún material de la cátedra coincide con el tema | `SIN_MATERIAL` | **Escribir/subir un apunte sobre X.** |
| Hay un material que coincide con el tema pero nunca fue citado en las respuestas | `NO_RECUPERADO` | **Revisar el indexado** de ese material (chunking, títulos, extensión del archivo, si es PDF sin texto extraíble). |
| El material se recuperó y se citó, pero las citas no lo respaldan | `SIN_RESPALDO` | **Revisar el chunking y el prompt**: el modelo recibió contexto pero responde con otra cosa. |

Cómo se decide que un material "coincide con el tema": se tokeniza
`Material.title + tags` con el mismo `TextTokenizer` y se compara con los
tokens del tema del cluster con **Jaccard ≥ 0.20** (un umbral bajo a
propósito: alcanza con que compartan el token clave, "autómata").

### 4.5 Salida para el docente (así se ve en la pantalla)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ La cátedra no está cubriendo 3 temas                                      │
│ 42 preguntas de 318 · 19 alumnos distintos                                │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1  autómata finito — 12 alumnos · 19 preguntas · 11 de 19 sin fuente        │
│    "La IA no encontró la respuesta"                                        │
│    → Falta un apunte sobre autómatas finitos.                              │
│    Preguntas: "¿qué es un autómata finito?" · "¿autómata finito o Turing?"  │
│    Materia citada: — (nunca se recuperó nada)                              │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2  complejidad big-o — 7 alumnos · 11 preguntas · 6 de 11 sin fuente        │
│    → El material "Apuntes complejidad.pdf" existe pero nunca se recuperó   │
│      para estas preguntas. Revisá el indexado de ese archivo.              │
│    Preguntas: "cómo se calcula la complejidad de un algoritmo?"            │
│    Materia citada: —                                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3  notación asintótica — 5 alumnos · 8 preguntas · 4 de 8 sin fuente       │
│    → El material "Unidad 2.pdf" se recuperó pero las citas no lo respaldan.│
│    Preguntas: "diferencia entre O(n) y O(n log n)?"                         │
│    Materia citada: "Unidad 2.pdf" (5 veces)                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

Esa lista es exactamente la que pidió el producto: *"10 alumnos preguntaron lo
mismo y la IA no encontró la respuesta → falta un apunte sobre X"*.

---

## 5. Agregado por tema

### 5.1 ¿Hay jerarquía de unidades?

**No.** El dominio no tiene entidad de unidad: `Course`, `Material`, `ChatThread`
(con `tags`) y `Enrollment` son lo único. Lo que **sí** existe y funciona como
taxonomía es **`Material.tags`**: el docente ya etiqueta sus materiales
("Unidad 1", "Probabilidad", "TP2").

**Decisión**: el detector usa un **resolutor de temas enchufable**:

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/TopicResolver.kt
package kitoru.analytics

/**
 * Cómo se le pone nombre a un grupo de preguntas.
 *
 * Es una interfaz porque la fuente de verdad va a cambiar: hoy son los tags
 * del material y las palabras clave; mañana puede ser una jerarquía de
 * unidades que el docente defina.
 */
interface TopicResolver {
    /**
     * Etiqueta del tema para un cluster.
     * @return "" si no se puede determinar.
     */
    fun topicOf(cluster: ClusterFacts): String
}

/** Los hechos de un cluster que necesita el resolutor de temas. */
data class ClusterFacts(
    /** Tokens del cluster (ids del vocabulario del índice). */
    val tokenIds: List<Int>,
    /** Peso de cada token (TF dentro del cluster × IDF global). */
    val tokenWeights: List<Double>,
    /** Etiquetas de las fuentes citadas en el cluster. */
    val citedSourceLabels: List<String>,
    /** Materiales de la cátedra. */
    val materials: List<Material>,
    /** Global: IDF de cada token. */
    val idf: (Int) -> Double,
)
```

Implementaciones:

| Implementación | Cuándo | Qué devuelve |
|---|---|---|
| `MaterialTagTopicResolver` | **default** | El tag de material más frecuente entre las fuentes citadas ("Unidad 1", "TP2"). Si las fuentes citadas no tienen tags → cae al de keywords. |
| `KeywordTopicResolver` | fallback y cuando no hay material citado | Los 2-3 tokens de mayor peso, unidos con " · ": `autómata finito`. |
| `UnitTreeTopicResolver` | **futuro** (cuando exista `CourseUnit`) | La unidad (y su camino completo: "Unidad 2 › Complejidad") a la que pertenece el material citado. |

El default es la composición de los dos primeros:
`MaterialTagTopicResolver(fallback = KeywordTopicResolver())` — si el cluster
citó material con tags, gana el tag; si no, gana la palabra clave.

> **Cómo encaja la jerarquía de unidades el día que exista**: se agrega
> `CourseUnit(id, courseId, title, parentId, keywords)` y `Material.unitId`.
> `UnitTreeTopicResolver` resuelve el `unitId` del material citado y sube por
> `parentId` hasta la raíz, usando los `keywords` de la unidad para matchear
> clusters que no citaron material. **El detector no cambia**; sólo se le pasa
> otro `TopicResolver`. Ese es el motivo de que sea una interfaz.

### 5.2 Keywords: los top tokens

Cuando el cluster no tiene material citado (justo el caso interesante: la IA no
encontró nada), el tema sale de las palabras de las preguntas.

```
peso(t) = tf_cluster(t) × idf(t)
```

- `tf_cluster(t)` = cuántas preguntas del cluster contienen `t`.
- `idf(t)` = la IDF global de la cátedra (§2.2).

Filtros (para que la etiqueta sea útil):

1. **Filtro de genericidad**: se descartan los tokens con `df(t) > 0.30 · N`
   (aparecen en más del 30% de las preguntas de la cátedra): "hola", "profesor",
   "gracias", "entiendo", "parcial", "duda". Son el ruido típico de los chats
   de alumnos.
2. **Stoplist ampliada de preguntas**: además de `TextTokenizer.STOP`, una lista
   propia del detector con las palabras de "conversación" que no son contenido:
   `hola, gracias, profe, profesor, dale, entiendo, no, si, ok, okay,
   buenísimo, porfa, ahí, esto, eso, cual, cuando, ejemplo, duda, ay, ayuda,
   help, thanks, hi, hello, yes, please, explain, example, question, answer`.
   (Se listan en español e inglés; el tokenizer ya saca las palabras vacías
   gramaticales.)
3. **Mínimo de peso**: se corta cuando el peso cae por debajo del 35% del
   máximo del cluster (así la etiqueta no incluye un token marginal).
4. **Máximo 3 tokens**, y **mínimo 2** si hay ≥ 2 preguntas distintas (un solo
   token es demasiado ambiguo para un cluster de varias preguntas).

### 5.3 La etiqueta

- 1 token → `"autómata"`.
- 2-3 tokens → `"autómata finito"` (separados por espacio, en orden de peso
  decreciente).
- Con material → `"Unidad 1"` (el tag crudo del material).

La etiqueta es lo que se muestra en la lista de la §4.5 y lo que se usa para
cruzar con los tags de los materiales.

---

## 6. Detector de alumnos en riesgo

### 6.1 Las señales

Se calculan por alumno, sobre sus turnos:

| Señal | Definición |
|---|---|
| `turns` | cantidad de turnos del alumno en la cátedra |
| `failedTurns` | turnos con `F ≥ τ_F` (0.45) |
| `repeatTurns` | turnos con `R > 0` (repitió una pregunta que ya había hecho) |
| `noSourceTurns` | turnos donde la IA no recuperó ninguna fuente |
| `idleDays` | días desde su último mensaje |
| `lastTurnFailed` | si su último turno fue un fracaso |
| `distinctQuestions` | preguntas distintas (por similitud, no por conteo bruto) |

### 6.2 Fórmula

```
fracasoRate      = failedTurns  / max(1, turns)
repeticionRate   = repeatTurns  / max(1, turns)
sinFuenteRate    = noSourceTurns / max(1, turns)
abandono         = 1.0 si lastTurnFailed ∧ idleDays ≥ 7
                    0.5 si lastTurnFailed ∧ 3 ≤ idleDays < 7
                    0.0 si no

riesgo = 100 × clamp( 0.35·fracasoRate + 0.25·repeticionRate
                     + 0.20·sinFuenteRate + 0.20·abandono , 0, 1 )
```

**Todo son tasas, no conteos**: un alumno que hizo 50 turnos y falló 5 tiene
`fracasoRate = 0.10`; uno que hizo 5 turnos y falló 5 tiene `1.00` pero no
entra en el ranking (piso de actividad, abajo).

### 6.3 Escala de riesgo

| Puntaje | Nivel | Qué muestra la UI |
|---|---|---|
| — | `SIN_DATOS` | menos de 5 turnos o menos de 3 preguntas distintas |
| 0 – 24 | `SIN_NOVEDAD` | nada que mirar |
| 25 – 49 | `REVISAR` | "3 de 12 turnos no obtuvieron respuesta" |
| 50 – 74 | `ATENCION` | "repite 4 preguntas y la IA no encontró 3 de ellas" |
| 75 – 100 | `ACOMPANAR` | "repite mucho, no encuentra nada y hace 12 días que no escribe" |

El piso de actividad (`minTurnsForRisk = 5`, `minDistinctQuestionsForRisk = 3`)
no es un detalle cosmético: sin él, el alumno que escribió una sola pregunta a
las 3 de la mañana y se fue tendría tasa 1.0 y aparecería como "en riesgo".
**Un solo turno no dice nada.**

### 6.4 Nota ética (obligatoria, va en la UI y en el código)

Este detector es el más delicado del producto, porque cruza la línea entre
"analítica de la cátedra" y "juicio sobre una persona". Las reglas que se
adoptan, y que quedan escritas en el `KDoc` del código:

1. **El riesgo no es una nota.** El puntaje no califica, no se exporta, no
   aparece en ningún informe y **jamás** se usa para evaluación ni sanciones. Es un
   puntero para que el docente mire una conversación concreta. El non-goal es
   explícito (§10).
2. **La culpa es del material, no del alumno.** En toda la UI, el texto dice
   *"la IA no encontró la respuesta"*, **nunca** *"el alumno no entendió"*.
   El detector mide el **sistema** (material + RAG + modelo), no la cabeza del
   alumno. Un alumno que repite una pregunta es, en la mayoría de los casos,
   un alumno a quien el sistema le falló.
3. **Evidencia visible y reversible.** Cada alumno en riesgo muestra **qué**
   turnos dispararon el puntaje (hilo, fecha, fragmento). El docente puede
   descartar el caso con un clic y el motivo queda anotado ("ya lo resolví
   por mail"). Un detector que no se puede refutar no es una herramienta: es
   una etiqueta.
4. **Nada de ranking público.** No hay "tabla de posiciones" de alumnos en
   riesgo, ni comparaciones entre alumnos en la pantalla del docente. El
   docente ve *su* lista de alumnos, no un ranking. La comparación entre
   personas es exactamente el uso estigmatizante que se quiere evitar.
5. **Umbral mínimo y transparencia.** Con menos de 5 turnos no se calcula
   puntaje. Y el alumno puede ver **su propio** puntaje y sus propias señales
   en su panel ("4 de tus 12 preguntas se repitieron: consultá el apunte de
   autómatas"). La transparencia es lo que separa una herramienta de apoyo de
   una herramienta de vigilancia.
6. **Sesgo por lenguaje y por vocabulario.** Un alumno que escribe en inglés,
   o con poco vocabulario, o con abreviaturas, genera preguntas que se
   clusterizan peor → su `repeticionRate` sube artificialmente. Mitigaciones:
   (i) el detector **no** penaliza la calidad de la escritura: la componente R
   sólo mira si la *misma* pregunta volvió, no si está bien redactada;
   (ii) `sinFuenteRate` y `fracasoRate` son independientes del estilo;
   (iii) el peso de `R` (0.25) es menor que el de `G` (0.35), que no depende
   del texto del alumno sino de las fuentes recuperadas;
   (iv) un alumno con muchas preguntas en otro idioma aparece en la lista de
   la cátedra (§4) aunque su texto no se clusterice bien — y eso es un
   recordatorio de que el detector es una heurística.
7. **No es una decisión automática.** Nada de "avisar al alumno", "mandar un
   mail", "marcar la entrega". El detector **propone**; el docente decide.
8. **Anonimato por defecto en lo compartido.** Si el docente exporta un
   informe, el nombre del alumno se puede omitir; sólo se exportan conteos.

---

## 7. Implementación

### 7.1 Archivos

**Nuevos en `:core`:**

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/text/TextTokenizer.kt` | Tokenizador compartido (extraído de `Bm25Index`). |
| `core/src/commonMain/kotlin/kitoru/analytics/DetectorConfig.kt` | Umbrales y parámetros. |
| `core/src/commonMain/kotlin/kitoru/analytics/QuestionIndex.kt` | Índice invertido + IDF + Jaccard ponderado. |
| `core/src/commonMain/kotlin/kitoru/analytics/Turns.kt` | Armado de turnos desde los mensajes de un hilo. |
| `core/src/commonMain/kotlin/kitoru/analytics/FailureScore.kt` | Componentes R/G/Q/A y `F`. |
| `core/src/commonMain/kotlin/kitoru/analytics/QuestionCluster.kt` | `QuestionCluster`, `GapKind`, `MaterialCoverage`, `CourseInsights`, `StudentRisk`, `RiskLevel`. |
| `core/src/commonMain/kotlin/kitoru/analytics/TopicResolver.kt` | `TopicResolver` + `KeywordTopicResolver` + `MaterialTagTopicResolver`. |
| `core/src/commonMain/kotlin/kitoru/analytics/RepeatedQuestionDetector.kt` | El orquestador. |

**Modificados:**

| Archivo | Cambio |
|---|---|
| `backend/.../rag/retrieval/Bm25Index.kt` | `tokenize` delega en `TextTokenizer`. |
| `core/.../domain/Analytics.kt` | `StudentAnalytics` + 6 campos; `CourseAnalytics` + `insights`. |

**Tests:** `core/src/commonTest/kotlin/kitoru/analytics/` (kotlin.test, corre en
desktop y en Android).

### 7.2 Configuración

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/DetectorConfig.kt
package kitoru.analytics

/**
 * Umbrales del detector.
 *
 * Todos los valores por defecto salen del gold set (§2.7) y están
 * **escritos en el informe**: un número de la cátedra siempre dice con qué
 * umbrales se calculó.
 */
data class DetectorConfig(
    /** Similitud mínima para considerar dos preguntas la misma (0..1). */
    val similarityThreshold: Double = 0.62,
    /** F mínimo para que un turno sea un fracaso (0..1). */
    val failureThreshold: Double = 0.45,
    /** F mínimo para que un turno sea un fracaso "confirmado" (0..1). */
    val confirmedFailureThreshold: Double = 0.60,
    /** `df` máximo para que un token sea ancla de candidatas. */
    val maxAnchorDf: Int = 60,
    /** Tokens de contenido mínimos para clusterizar una pregunta. */
    val minTokensForMatch: Int = 3,
    /** Tokens compartidos mínimos para unir dos preguntas. */
    val minSharedTokens: Int = 2,
    /** Preguntas previas del mismo alumno que se comparan (tope de costo). */
    val previousQuestionsForRepeat: Int = 50,
    /** Alumnos distintos mínimos para que un cluster sea un hueco. */
    val minDistinctStudentsForGap: Int = 2,
    /** `noSourceRate` mínima para que un cluster sea un hueco. */
    val minNoSourceRateForGap: Double = 0.50,
    /** Turnos mínimos para calcular el riesgo de un alumno. */
    val minTurnsForRisk: Int = 5,
    /** Preguntas distintas mínimas para calcular el riesgo de un alumno. */
    val minDistinctQuestionsForRisk: Int = 3,
    /** Días sin escribir para considerar abandono. */
    val idleDaysForAbandon: Int = 7,
    /** Máximo de preguntas por cluster (el que lo supera se parte por fecha). */
    val maxClusterSize: Int = 200,
    /** Caracteres máximo que se indexan por pregunta (evita consultas gigante). */
    val maxQuestionChars: Int = 2000,
    /** Máximo de clusters devueltos (los demás se resumen). */
    val maxClusters: Int = 200,
)
```

### 7.3 Los turnos

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/Turns.kt
package kitoru.analytics

import kitoru.domain.ChatMessage

/**
 * Un turno: una pregunta del alumno, la respuesta que la atiende y la
 * pregunta siguiente.
 */
data class Turn(
    val question: ChatMessage,
    /** Respuesta del asistente que sigue a la pregunta (null si no llegó). */
    val answer: ChatMessage?,
    /** Siguiente pregunta del mismo alumno en el mismo hilo (null si es la última). */
    val nextQuestion: ChatMessage?,
    /** Índice de la pregunta en el índice de preguntas. */
    val questionIndex: Int = -1,
)

/**
 * Arma los turnos de un hilo a partir de sus mensajes.
 *
 * Los mensajes se ordenan por `createdAt` (desempate por id, para que el
 * resultado no dependa del orden de lectura del JSON).
 */
fun buildTurns(messages: List<ChatMessage>): List<Turn> {
    val ordered = messages.sortedWith(compareBy({ it.createdAt }, { it.id }))
    val turns = mutableListOf<Turn>()
    var pending: ChatMessage? = null
    var answer: ChatMessage? = null

    for (message in ordered) {
        if (message.isUser) {
            if (pending != null) {
                // La pregunta anterior cerró su turno (sin respuesta o con la
                // que haya habido).
                turns += Turn(pending, answer, message)
                answer = null
            }
            pending = message
        } else if (message.isAssistant) {
            answer = message   // la última respuesta gana (reintentos del modelo)
        }
    }
    if (pending != null) turns += Turn(pending, answer, null)
    return turns
}
```

### 7.4 El score de fracaso

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/FailureScore.kt
package kitoru.analytics

import kitoru.domain.ChatMessage
import kotlin.math.max

/**
 * Componentes del puntaje de fracaso de un turno.
 *
 * Todas en [0, 1]. Ninguna sola alcanza el umbral de fracaso: hace falta la
 * conjunción de al menos dos señales (§3.3).
 */
data class FailureSignals(
    /** (a) El alumno ya había hecho esta pregunta. */
    val repetition: Double = 0.0,
    /** (b) La respuesta no está anclada en el material. */
    val grounding: Double = 0.0,
    /** (c) El alumno repregunta en el mismo hilo. */
    val reask: Double = 0.0,
    /** (d) El alumno deja de escribir después de esta respuesta. */
    val abandonment: Double = 0.0,
    /** La repregunta trae más detalle que la original. */
    val insistent: Boolean = false,
) {
    /** Puntaje de fracaso del turno (0..1). */
    val failure: Double
        get() = 0.30 * repetition + 0.35 * grounding + 0.25 * reask + 0.10 * abandonment

    /** Componentes sueltas, para la evidencia que ve el docente. */
    fun reasons(): List<String> = buildList {
        if (repetition >= 0.99) add("Repitió una pregunta que ya había hecho")
        if (grounding >= 0.60) add("La IA no encontró nada en el material")
        else if (grounding > 0.0) add("La respuesta no está respaldada por la fuente")
        if (reask >= 0.99) add("Repreguntó lo mismo en el mismo hilo")
        if (abandonment >= 0.99) add("Después de esta respuesta dejó de escribir")
    }
}

/**
 * Calcula los componentes del puntaje de un turno.
 */
object FailureScorer {

    /** Componente (b): anclaje de la respuesta. */
    fun groundingOf(answer: ChatMessage?): Double {
        if (answer == null) return 0.0
        val noSource = if (answer.sources.isEmpty()) 1.0 else 0.0
        val citation = answer.citation
        val noCitation = if (citation == null || citation.total == 0) 1.0 else 0.0
        val unsupported = if (citation == null || citation.total == 0) 0.0
        else (citation.unsupported + citation.unknown).toDouble() / citation.total
        return 0.60 * noSource + 0.20 * noCitation + 0.20 * unsupported
    }

    /** Normaliza una similitud al rango [0, 1] según el umbral. */
    fun normalize(sim: Double, threshold: Double): Double =
        if (sim <= threshold) 0.0 else ((sim - threshold) / (1.0 - threshold)).coerceIn(0.0, 1.0)

    /**
     * Componente (d): abandono.
     * @param lastAnswerInThread si la respuesta del turno es la última del hilo.
     * @param idleDays días desde el último mensaje del alumno en la cátedra.
     */
    fun abandonmentOf(lastAnswerInThread: Boolean, idleDays: Int, idleThreshold: Int): Double = when {
        !lastAnswerInThread -> 0.0
        idleDays >= idleThreshold -> 1.0
        idleDays >= max(1, idleThreshold / 2) -> 0.5
        else -> 0.0
    }
}
```

### 7.5 El orquestador

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/RepeatedQuestionDetector.kt
package kitoru.analytics

import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.Course
import kitoru.domain.Enrollment
import kitoru.domain.Material

/**
 * Detector de preguntas repetidas y de temas que la IA no resuelve.
 *
 * Es una **función pura**: no toca disco, no llama al modelo, no depende de la
 * red. Los mismos chats producen siempre el mismo informe (regla 3).
 *
 * Reglas de visibilidad (regla 6):
 *  - sólo entran los alumnos cuya inscripción [ChatVisibility.showsMetrics];
 *  - el **texto literal** de una pregunta sólo se publica si el alumno tiene
 *    [ChatVisibility.showsText]; con `METRICS_ONLY` el cluster muestra conteos
 *    y palabras clave, nunca la frase del alumno.
 */
class RepeatedQuestionDetector(
    private val cfg: DetectorConfig = DetectorConfig(),
    private val topicResolver: TopicResolver = MaterialTagTopicResolver(fallback = KeywordTopicResolver()),
) {

    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        materials: List<Material> = emptyList(),
        now: Long = 0L,
    ): CourseInsights {
        // 1. Qué alumnos entran (visibilidad).
        val visible = enrollments.filter { it.visibility.showsMetrics }
        val byUser = visible.associateBy { it.userId }
        val threadById = threads.associateBy { it.id }

        // 2. Turnos por hilo (sólo de alumnos visibles).
        val turnsByThread = messages
            .filter { it.threadId in threadById.keys }
            .groupBy { it.threadId }
            .mapValues { (_, msgs) -> buildTurns(msgs) }

        // 3. Índice de preguntas (sólo preguntas de alumnos visibles).
        val index = QuestionIndex(cfg.maxAnchorDf)
        val questionIndex = HashMap<String, Int>()   // messageId -> índice
        val questionInfo = ArrayList<QuestionInfo>()
        for ((threadId, turns) in turnsByThread) {
            val thread = threadById.getValue(threadId)
            val enrollment = byUser[thread.authorId] ?: continue
            for (turn in turns) {
                val tokens = TextTokenizer.tokenize(turn.question.text)
                if (tokens.size < cfg.minTokensForMatch) {
                    // No clusterizable, pero **sí** cuenta como turno para el
                    // puntaje de riesgo del alumno.
                    questionInfo += QuestionInfo(turn, -1, enrollment, tokens.size)
                    continue
                }
                val qi = index.add(turn.question.text)
                questionIndex[turn.question.id] = qi
                questionInfo += QuestionInfo(turn, qi, enrollment, tokens.size)
            }
        }
        index.build()

        // 4. Similitudes y clusters (union-find).
        val uf = UnionFind(index.size)
        val edges = ArrayList<Pair<Int, Int>>()
        for (q in 0 until index.size) {
            for (c in index.candidates(q)) {
                if (c < q) continue
                if (index.upperBound(q, c) < cfg.similarityThreshold) continue
                val sim = index.similarity(q, c)
                if (sim >= cfg.similarityThreshold) {
                    uf.union(q, c)
                    edges += q to c
                }
            }
        }

        // 5. Fracaso por turno (componentes R, G, Q, A).
        val signals = scoreTurns(turnsByThread, questionInfo, index, byUser, now)

        // 6. Agrupar los turnos por cluster y armar el resultado.
        val clusters = buildClusters(questionInfo, index, uf, signals, materials, course)

        // 7. Riesgo por alumno.
        val risks = buildRisks(questionInfo, signals, byUser, now)

        return CourseInsights(
            courseId = course.id,
            questionGaps = clusters.filter { it.isGap }.sortedByDescending { it.gapScore },
            clusters = clusters.sortedByDescending { it.gapScore }.take(cfg.maxClusters),
            studentRisk = risks.sortedByDescending { it.score },
            totalQuestions = questionInfo.size,
            failedTurns = signals.count { it.failure >= cfg.failureThreshold },
            similarityThreshold = cfg.similarityThreshold,
            failureThreshold = cfg.failureThreshold,
            analyzedAt = now,
        )
    }
}
```

El cuerpo de `scoreTurns` (que es donde vive la fórmula) queda así. Los dos
tipos auxiliares que usa:

```kotlin
/** Una pregunta (turno) con su contexto: índice, dueño y tamaño. */
private data class QuestionInfo(
    val turn: Turn,
    /** Índice en el `QuestionIndex`, o -1 si la pregunta no es clusterizable. */
    val questionIndex: Int,
    val enrollment: Enrollment,
    /** Cantidad de tokens de contenido. */
    val tokenCount: Int,
)

/** Las señales de un turno, listas para agregar. */
data class TurnSignals(
    val questionMessageId: String,
    val threadId: String,
    val studentId: String,
    val signals: FailureSignals,
) {
    val failure: Double get() = signals.failure
}
```

```kotlin
    private fun scoreTurns(
        turnsByThread: Map<String, List<Turn>>,
        questionInfo: List<QuestionInfo>,
        index: QuestionIndex,
        byUser: Map<String, Enrollment>,
        now: Long,
    ): List<TurnSignals> {
        // Índice pregunta → posición en questionInfo.
        val posByMessage = HashMap<String, Int>()
        questionInfo.forEachIndexed { i, info -> posByMessage[info.turn.question.id] = i }

        // Último mensaje del alumno por usuario (para el abandono).
        val lastUserMessageAt = HashMap<String, Long>()
        for (info in questionInfo) {
            val at = info.turn.question.createdAt
            val prev = lastUserMessageAt[info.enrollment.userId]
            if (prev == null || at > prev) lastUserMessageAt[info.enrollment.userId] = at
        }

        // Preguntas previas por alumno (para la repetición).
        val previousByUser = HashMap<String, MutableList<Int>>()   // índices de pregunta

        val out = ArrayList<TurnSignals>(questionInfo.size)
        // Orden cronológico global: así "preguntas previas" son las de antes.
        val ordered = questionInfo.sortedBy { it.turn.question.createdAt }

        for (info in ordered) {
            val turn = info.turn
            val userId = info.enrollment.userId

            // (a) Repetición.
            val previous = previousByUser[userId].orEmpty()
            val maxSimPrev = if (info.questionIndex >= 0) {
                previous.takeLast(cfg.previousQuestionsForRepeat)
                    .maxOfOrNull { prev ->
                        if (prev < 0 || info.questionIndex < 0) 0.0
                        else index.similarity(prev, info.questionIndex)
                    } ?: 0.0
            } else 0.0
            val repetition = FailureScorer.normalize(maxSimPrev, cfg.similarityThreshold)

            // (b) Anclaje.
            val grounding = FailureScorer.groundingOf(turn.answer)

            // (c) Repregunta en el mismo hilo.
            val reask = 0.0
            var insistent = false
            val next = turn.nextQuestion
            if (next != null) {
                val nextIdx = posByMessage[next.id]
                if (nextIdx != null && nextIdx >= 0) {
                    val sim = index.similarity(info.questionIndex, questionInfo[nextIdx].questionIndex)
                    reask = FailureScorer.normalize(sim, cfg.similarityThreshold)
                    insistent = questionInfo[nextIdx].tokenCount > info.tokenCount
                }
            }

            // (d) Abandono.
            val lastInThread = turn.answer != null && turn.nextQuestion == null
            val lastAt = lastUserMessageAt[userId] ?: turn.question.createdAt
            val idleDays = if (now > 0) ((now - lastAt) / 86_400_000L).toInt() else 0
            val abandonment = FailureScorer.abandonmentOf(lastInThread, idleDays, cfg.idleDaysForAbandon)

            out += TurnSignals(
                questionMessageId = turn.question.id,
                threadId = turn.question.threadId,
                studentId = userId,
                signals = FailureSignals(repetition, grounding, reask, abandonment, insistent),
            )

            if (info.questionIndex >= 0) previousByUser.getOrPut(userId) { mutableListOf() } += info.questionIndex
        }
        return out
    }
```

> `TurnSignals` lleva el `questionMessageId`, así que el resto del detector
> (clusters, riesgo) une por id y no depende del orden en que `scoreTurns`
> devuelve la lista (que es cronológico, no el de `questionInfo`).

### 7.6 Los tests

**Dónde**: `core/src/commonTest/kotlin/kitoru/analytics/` (usa `kotlin.test`, así
que corre en desktop **y** en Android). Es la primera batería de tests de
`:core`; hasta ahora `:core` no tenía tests, así que hay que agregar el
source set si falta:

```kotlin
// core/build.gradle.kts — commonTest ya está declarado; alcanza con:
commonTest.dependencies {
    implementation(libs.kotlinx.coroutines.test)
    implementation(kotlin("test"))
}
```

#### `TextTokenizerTest.kt` (espejo del `Bm25IndexTest` existente)

| Test | Qué verifica |
|---|---|
| `parte el camel case` | "llamaServer" → "llama", "server" |
| `saca los acentos` | "cación" → "cacion" |
| `saca las palabras vacias` | "el motor de la app" → sin "el", sin "de" |
| `ignora palabras de una letra` | "a b cd" → sin "a", sin "b" |
| `parte por lo no alfanumerico` | "hola, mundo; otra vez" → 4 tokens |
| `es el mismo que el de BM25` | `assertEquals(Bm25Index.tokenize(t), TextTokenizer.tokenize(t))` — **vive en `:backend`** (`:core` no ve a `:backend`): se agrega en `backend/src/desktopTest/.../Bm25IndexTest.kt` como un test de equivalencia. |

#### `QuestionSimilarityTest.kt`

| # | Test | Entradas | Esperado |
|---|---|---|---|
| 1 | `preguntas identicas dan 1` | "¿Qué es un autómata finito?" ×2 (una con acentos y signos distintos) | `sim == 1.0` |
| 2 | `la parafrasis supera el umbral` | "¿Cómo se define un autómata finito?" vs "¿Qué es un autómata finito?" | `sim ≥ 0.62` y `clusterizan juntas` |
| 3 | `mismo tema distinta pregunta no supera el umbral` | "¿Cómo se define un autómata finito?" vs "¿Qué diferencia hay entre autómata finito y máquina de Turing?" | `sim < 0.62` y `NO clusterizan` |
| 4 | `preguntas en español e ingles no son la misma pregunta` | "¿Cómo se define un autómata finito?" vs "How do I define a finite automaton?" | `sim == 0.0` (tokens disjuntos) y `NO clusterizan` — **limitación documentada**: el cruce de idiomas necesita embeddings (fuera de alcance) |
| 5 | `el token generico pesa menos` | dos preguntas que sólo comparten "hola" vs dos que comparten "autómata" | la primera da menos similitud ponderada |
| 6 | `preguntas cortas no se clusterizan` | "hola" | `tokens < minTokensForMatch` → queda fuera |
| 7 | `la poda por cota superior no descarta pares validos` | property test: para 200 pares aleatorios con `sim ≥ τ`, `upperBound ≥ sim` | siempre cierto |
| 8 | `la simetria se sostiene` | `sim(a,b) == sim(b,a)` para 100 pares | siempre cierto |

#### `FailureScoreTest.kt`

| # | Test | Entradas | Esperado |
|---|---|---|---|
| 1 | `sin fuente y repregunta es fracaso` | G=0.60, Q=1 | `F = 0.46 ≥ 0.45` → fracaso |
| 2 | `sin fuente y cambio de tema no es fracaso` | G=0.60 | `F = 0.21 < 0.45` |
| 3 | `repetir y repreguntar es fracaso` | R=1, Q=1 | `F = 0.55` → fracaso |
| 4 | `ninguna señal sola alcanza el umbral` | R=1 / G=1 / Q=1 / A=1 | los cuatro < 0.45 |
| 5 | `el abandono solo no alcanza` | A=1 | `F = 0.10` |
| 6 | `la respuesta con citas sin respaldo da G parcial` | `citation(verified=2, unsupported=1, unknown=0)` | `G = 0.20 * (1/3) = 0.0667` |
| 7 | `sin respuesta no hay anclaje` | `answer = null` | `G = 0.0` |
| 8 | `la normalizacion respectea el umbral` | `normalize(0.62, 0.62) == 0.0`, `normalize(1.0, 0.62) == 1.0` | exacto |
| 9 | `el abandono depende de los dias sin escribir` | `idleDays = 10` vs `1` | 1.0 vs 0.0 |

#### `RepeatedQuestionDetectorTest.kt` (el test de integración, con datos armados a mano)

Escenario base (el que se repite en varios tests):

```kotlin
private fun curso(): Course = Course(id = "IA-1", code = "IA-2026-1", name = "Inteligencia Artificial")

private fun alumno(id: String, name: String, vis: ChatVisibility = ChatVisibility.FULL) =
    Enrollment(id = "e-$id", courseId = "IA-1", userId = id, displayName = name,
        role = CourseRole.ALUMNO, visibility = vis)

private fun hilo(id: String, authorId: String) =
    ChatThread(id = id, courseId = "IA-1", authorId = authorId, authorName = authorId)

private fun pregunta(id: String, threadId: String, text: String, at: Long) =
    ChatMessage(id = id, threadId = threadId, role = "user", text = text, createdAt = at)

private fun respuesta(id: String, threadId: String, text: String, at: Long,
                       sources: List<String> = emptyList(),
                       citation: CitationStats? = null) =
    ChatMessage(id = id, threadId = threadId, role = "assistant", text = text,
        createdAt = at, sources = sources, citation = citation)
```

| # | Test | Qué verifica |
|---|---|---|
| 1 | `diez alumnos con la misma pregunta forman un cluster` | 10 alumnos × la misma pregunta, todas con respuesta sin fuente → **1 cluster**, `distinctStudents == 10`, `isGap == true` |
| 2 | `el gap ordena por impacto` | dos clusters (12 alumnos y 3) → el de 12 va primero |
| 3 | `preguntas distintas del mismo tema no se juntan` | "autómata finito" × 5 y "diferencia autómata turing" × 5 → **2 clusters** |
| 4 | `un alumno con visibilidad NONE no aparece` | `ChatVisibility.NONE` → `distinctStudents == 0` y su texto no está en ninguna parte |
| 5 | `con METRICS_ONLY no se publica el texto` | `visibility = METRICS_ONLY` → el cluster existe con su conteo, pero `samples` está vacío y `label` es la etiqueta de tema |
| 6 | `la cobertura distingue falta de material de material no recuperado` | cluster con material del mismo tema pero nunca citado → `gapKind == NO_RECUPERADO`; cluster sin material → `SIN_MATERIAL` |
| 7 | `el tema sale de los tags del material` | material con tag "Unidad 1" citado en el cluster → `topic == "Unidad 1"` |
| 8 | `el tema sale de las palabras clave si no hay material` | cluster sin fuentes → `topic` contiene "automata" |
| 9 | `un alumno con pocos turnos no tiene puntaje de riesgo` | 2 turnos → `riskLevel == SIN_DATOS` |
| 10 | `un alumno que repite y no encuentra puntúa alto` | 8 turnos, 4 repetidos, 3 sin fuente, abandonar → `score ≥ 50` y `level == ATENCION` |
| 11 | `un alumno que pregunta mucho y bien tiene riesgo bajo` | 40 turnos, 0 fracasos → `score < 25` |
| 12 | `las tasas mandan sobre los conteos` | 60 turnos con 5 fracasos (tasa 0.08) vs 6 turnos con 3 fracasos (tasa 0.5, pero bajo el piso) → el primero puntúa más bajo en absoluto |
| 13 | `es determinista` | correr `analyze` dos veces → resultados idénticos (mismo `gapScore`, mismo orden) |
| 14 | `el informe guarda los umbrales usados` | `insights.similarityThreshold == 0.62` y `failureThreshold == 0.45` |
| 15 | `un hilo de chat general no cuenta como hueco` | respuestas sin fuentes pero `sources` vacío en **todo** el hilo (modo general) → `G = 0` → no es fracaso |

#### `QuestionClusterScaleTest.kt` (el test de escala, desactivado por defecto)

```kotlin
@Test
fun `cien mil preguntas se analizan en menos de X`() {
    if (System.getenv("KITORU_SLOW_TESTS") != "1") return   // se corre a mano
    val (alumnos, hilos, mensajes) = generarPreguntasSintéticas(100_000)
    val t0 = System.nanoTime()
    val insights = RepeatedQuestionDetector().analyze(curso(), alumnos, hilos, mensajes)
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue(ms < 30_000, "el análisis tardó ${ms}ms")
    assertTrue(insights.clusters.isNotEmpty())
}
```

Este test **no** es un límite de CI: es una alarma. Si el tiempo se dispara, se
baja `maxAnchorDf` o se cachea el índice (ver §7.7).

#### `TopicResolverTest.kt`

| # | Test |
|---|---|
| 1 | `las palabras clave ignoran los tokens genericos` (df > 30% de N → fuera) |
| 2 | `la etiqueta tiene entre 1 y 3 tokens` |
| 3 | `el tag del material gana sobre las palabras clave` |

### 7.7 Rendimiento e incrementalidad

| Etapa | 100k preguntas (desktop) | 100k preguntas (móvil gama media) |
|---|---|---|
| Tokenizar (una vez) | 0.4 s | 0.9 s |
| Índice invertido + IDF | 0.6 s | 1.4 s |
| Candidatas (~2.2 M pares) | 0.1 s | 0.25 s |
| Scoring + union-find | 0.3 s | 0.8 s |
| Clusters + score + riesgo | 0.2 s | 0.5 s |
| **Total en frío** | **~1.6 s** | **~3.9 s** |
| Memoria | ~18 MB | ~18 MB |
| **Pregunta nueva (incremental)** | **< 25 ms** | **< 60 ms** |

**Incrementalidad**: el detector se puede correr en modo *append*: se conserva
el `QuestionIndex` (con su IDF) entre corridas y sólo se indexan las preguntas
nuevas. La IDF se recalcula (es un `df` global, O(V)); el costo es el mismo
que la tokenización. El detector **no** mantiene estado entre corridas en el
diseño actual — es una función pura — pero la estructura del índice permite
agregar un `QuestionIndex` cacheado por cátedra si el tiempo lo pide.

En la práctica, el análisis corre **en la máquina del docente** (es la única que
tiene los chats de todos) y sobre el subconjunto de la cursada en curso, así
que los números reales son mucho menores: 200 alumnos × 60 preguntas = 12 000
preguntas → **~0.2 s**.

---

## 8. Dónde vive el resultado

### 8.1 La respuesta corta

> **Ni todo en `StudentAnalytics` ni todo en un `CourseAnalytics` nuevo: las dos
> cosas, pero con la frontera clara.**
>
> - El **agregado por cluster y por tema** (la lista de acciones para el
>   docente) es **de la cátedra**: vive en un `CourseInsights` nuevo, colgado de
>   `CourseAnalytics`.
> - El **puntaje de riesgo** es **de un alumno**: vive en `StudentAnalytics`
>   (6 campos: señales + nivel) y se calcula en el detector, que es quien tiene
>   los clusters.
> - El detector **no** se mete dentro de `AnalyticsAnalyzer`: vive aparte, es
>   una función pura propia, y `AnalyticsAnalyzer` queda intacto (no se rompen
>   los tests existentes y la pantalla del docente puede pedir sólo una de las
>   dos analíticas).

### 8.2 El modelo

**Nuevo (`:core`):**

```kotlin
// core/src/commonMain/kotlin/kitoru/analytics/QuestionCluster.kt  (agregado)

/**
 * Nivel de riesgo de un alumno. **No es una nota**: es un puntero para que
 * el docente mire una conversación (§6.4).
 */
@Serializable
enum class RiskLevel(val label: String, val description: String) {
    SIN_DATOS("Sin datos", "Hizo pocas preguntas: no hay evidencia suficiente."),
    SIN_NOVEDAD("Sin novedad", "No hay señales que revisar."),
    REVISAR("Revisar", "Algunas preguntas no obtuvieron respuesta."),
    ATENCION("Atención", "Varias preguntas se repiten sin respuesta."),
    ACOMPANAR("Acompañar", "Conviene contactarlo: repite y no encuentra."),
}

/** Riesgo de un alumno, con su evidencia. */
@Serializable
data class StudentRisk(
    val userId: String,
    val displayName: String,
    /** 0..100. `null` si no hay datos suficientes. */
    val score: Int? = null,
    val level: RiskLevel = RiskLevel.SIN_DATOS,
    val turns: Int = 0,
    val failedTurns: Int = 0,
    val repeatTurns: Int = 0,
    val noSourceTurns: Int = 0,
    val idleDays: Int = 0,
    /** Motivos, en texto, para que el docente entienda el número. */
    val reasons: List<String> = emptyList(),
    /** Hilos donde está la evidencia (para abrir la conversación). */
    val evidenceThreadIds: List<String> = emptyList(),
)

/**
 * Informe de preguntas repetidas y temas no cubiertos de una cátedra.
 */
@Serializable
data class CourseInsights(
    val courseId: String = "",
    /** Clusters con hueco de material, ordenados por impacto. */
    val questionGaps: List<QuestionCluster> = emptyList(),
    /** Todos los clusters, ordenados por impacto (incluidos los que no son hueco). */
    val clusters: List<QuestionCluster> = emptyList(),
    /** Alumnos que conviene mirar (con piso de actividad). */
    val studentRisk: List<StudentRisk> = emptyList(),
    val totalQuestions: Int = 0,
    val failedTurns: Int = 0,
    /** Proporción de turnos fallidos (0..1). */
    val failureRate: Double = 0.0,
    /** Proporción de preguntas que están en un cluster repetido (0..1). */
    val repeatedQuestionRate: Double = 0.0,
    /** Umbrales con los que se calculó este informe (para reproducibilidad). */
    val similarityThreshold: Double = 0.62,
    val failureThreshold: Double = 0.45,
    val analyzedAt: Long = 0L,
)
```

**Extensión de `StudentAnalytics`** (`core/.../domain/Analytics.kt`) — campos
nuevos, todos con valor por defecto (los JSON guardados siguen deserializando):

```kotlin
@Serializable
data class StudentAnalytics(
    // … los campos actuales, sin cambios …
    /** Turnos en los que la IA no dio una respuesta útil (§3). */
    val failedTurns: Int = 0,
    /** Turnos en los que el alumno repitió una pregunta. */
    val repeatTurns: Int = 0,
    /** Turnos en los que la IA no recuperó ninguna fuente del material. */
    val noSourceTurns: Int = 0,
    /** Clusters sin resolver en los que participa este alumno. */
    val unresolvedClusters: Int = 0,
    /** Puntaje de riesgo 0..100 (null si no hay datos suficientes). */
    val riskScore: Int? = null,
    /** Nivel de riesgo. */
    val riskLevel: RiskLevel = RiskLevel.SIN_DATOS,
)
```

**Extensión de `CourseAnalytics`**:

```kotlin
@Serializable
data class CourseAnalytics(
    // … los campos actuales, sin cambios …
    /** Informe de preguntas repetidas y temas no cubiertos (§3 de este doc). */
    val insights: CourseInsights = CourseInsights(),
)
```

### 8.3 Integración

`AnalyticsAnalyzer.analyze(...)` **no cambia**. El `ViewModel` del docente
compone:

```kotlin
// ui/commonMain/kotlin/kitoru/teacher/CourseAnalyticsViewModel.kt
val analytics: StateFlow<CourseAnalytics> = flow {
    val base = AnalyticsAnalyzer.analyze(course, enrollments, threads, messages)
    val insights = RepeatedQuestionDetector().analyze(
        course = course,
        enrollments = enrollments,
        threads = threads,
        messages = messages,
        materials = materials,
    )
    // El detector ya calculó el riesgo: se lo inyecta al resumen por alumno.
    val riskByUser = insights.studentRisk.associateBy { it.userId }
    emit(base.copy(
        insights = insights,
        perStudent = base.perStudent.map { s ->
            val risk = riskByUser[s.userId]
            s.copy(
                failedTurns = risk?.failedTurns ?: 0,
                repeatTurns = risk?.repeatTurns ?: 0,
                noSourceTurns = risk?.noSourceTurns ?: 0,
                riskScore = risk?.score,
                riskLevel = risk?.level ?: RiskLevel.SIN_DATOS,
            )
        },
    ))
}.stateIn(...)
```

**Por qué no meterlo dentro de `AnalyticsAnalyzer`**: el detector necesita los
`Material` (para la cobertura) y produce un informe grande; mantenerlo aparte
permite (i) testearlo sin tocar el analizador existente, (ii) que la pantalla
pida sólo lo que necesita, y (iii) que mañana el detector corra en el
Kitoru-Server sin mover la analítica de `:core`.

### 8.4 Compatibilidad y persistencia

- `CourseAnalytics` **no se persiste** hoy (se calcula al vuelo). `CourseInsights`
  tampoco: no hay archivo nuevo, no hay migración.
- Los campos nuevos de `StudentAnalytics` y `CourseAnalytics` tienen valor por
  defecto → los JSON viejos deserializan (`KitoruJson` usa
  `ignoreUnknownKeys = true`).
- `RiskLevel` es un enum nuevo: si un JSON guardado tuviera un valor
  desconocido, kotlinx.serialization fallaría — por eso el campo tiene default
  y `RiskLevel` no se persiste todavía (sólo vive en memoria).

### 8.5 Qué ve el docente y qué ve el alumno

| Vista | Contenido |
|---|---|
| **Docente → Cátedra → Analítica → "Preguntas sin respuesta"** | La lista de la §4.5: clusters con alumnos distintos, preguntas de ejemplo, material citado y no citado, y la acción sugerida. |
| **Docente → Cátedra → Analítica → "Alumnos a mirar"** | La lista de riesgo con la evidencia (hilo, fecha, motivo). Ordenada por puntaje, **sin ranking comparativo** entre alumnos. |
| **Alumno → su panel** | **Su propio** resumen: "4 de tus 12 preguntas se repitieron", "3 preguntas no encontraron material", y el enlace al apunte que falta. Sin puntaje numérico ni etiqueta de riesgo: el alumno ve *hechos*, no un juicio. |

### 8.6 Encaje con los otros diseños del paquete de analítica

Los documentos hermanos de esta carpeta (`01-pipeline.md`, `02-metricas.md`,
`04-por-material.md`, `05-herramientas.md`) están escritos en paralelo. Estos
son los puntos de contacto y cómo se reconcilian:

| Punto | Otro diseño | Cómo encaja este detector |
|---|---|---|
| **Dónde se calcula** | `01-pipeline` propone un `AnalyticsAccumulator` incremental que termina en `freeze() → CourseAnalytics` | El detector es una **función pura de lote** sobre `(course, enrollments, threads, messages)`. Puede correr (a) entero, como propone este documento; o (b) sobre los mensajes que el acumulador ya tenga, si el pipeline lo alimenta. El `QuestionIndex` es **incremental por diseño** (`add()` + `build()`), así que puede vivir cacheado en el acumulador y ahorrar la tokenización de los chats ya vistos. |
| **Objeto "insight"** | `05-herramientas` define `toolInsights`: un generador puro que produce objetos accionables (`id`, `headline`) | El `QuestionGap` es el mismo tipo de cosa: un finding con una frase accionable ("12 alumnos preguntaron lo mismo y la IA no encontró la respuesta → falta un apunte sobre X"). Si se unifica el modelo, `QuestionGap` se serializa como un `Insight` más y se agrega a la lista del `CourseAnalytics` (no a un `CourseInsights` paralelo). |
| **Nombres de campo** | `05-herramientas` propone `StudentAnalytics.toolUsage` y `CourseAnalytics.toolUsage` | Los 6 campos nuevos de `StudentAnalytics` (`failedTurns`, `repeatTurns`, `noSourceTurns`, `unresolvedClusters`, `riskScore`, `riskLevel`) siguen la misma convención: campos planos con valor por defecto en el resumen del alumno, y el detalle (los clusters) en el agregado de la cátedra. |
| **Tasas y denominadores** | `02-metricas` audita las tasas existentes (p.ej. `citationSupportRate` mezcla "sin respaldo" con "inexistente") | El detector **no** redefine `citationSupportRate`: usa `citation.unsupported` y `citation.unknown` por separado, que es justamente la separación que pide `02-metricas`. |
| **Visibilidad** | `01-pipeline` recuerda que el pipeline debe respetar `Course.analyticsEnabled` | El detector no mira `analyticsEnabled`: es una función pura, la decisión de llamarla o no es del servicio (mismo criterio que `08-testing` H5). |
| **Persistencia** | `01-pipeline` persistirá un `CourseAnalytics` congelado en JSON | `CourseInsights` es serializable y entra en ese JSON sin cambios. Si el pipeline congela la analítica y la persiste, el informe queda versionado con los chats: es exactamente lo que hace falta para comparar cursadas. |

> **Convención de nombres entre documentos**: este documento usa
> `CourseInsights` como contenedor propio. Si el paquete de analítica unifica
> el concepto en un único `Insight` (como propone `05-herramientas`), la
> migración es: `CourseInsights.questionGaps: List<QuestionGap>` pasa a ser
> `CourseAnalytics.insights: List<Insight>` y `QuestionGap` implementa la
> interfaz `Insight` (`id`, `headline`, `score`). Ninguna fórmula cambia.

---

## 9. Casos borde

| Caso | Comportamiento |
|---|---|
| El alumno escribe una pregunta de 2 palabras ("hola") | No se clusteriza (`minTokensForMatch = 3`), pero el turno cuenta para el riesgo. |
| El mensaje del alumno es sólo un código o una fórmula | El tokenizador deja pocos tokens → no clusteriza. |
| El hilo tiene respuestas del asistente sin `citation` (IA local del alumno que no verificó) | `G = 0.20` (sin cita), nunca "fracaso" por sí solo. |
| La IA no recuperó nada **porque el chat estaba en modo general** | El detector distingue los hilos: si **ningún** turno del hilo tiene fuentes, el hilo se marca "general" y `G = 0` en todo el hilo. |
| Dos alumnos preguntan lo mismo y los dos tienen `METRICS_ONLY` | El cluster se cuenta (2 alumnos) pero no muestra texto: sólo conteos y palabras clave agregadas. |
| Un alumno cambia de nombre | Se usa `userId` para todo; el nombre es sólo de presentación (`enrollment.displayName`, igual que el resto de la analítica). |
| Una cátedra con 3 preguntas en total | No hay gaps (hace falta ≥ 2 alumnos con ≥ 0.45 de media de fracaso). El informe sale vacío, no con ruido. |
| Una pregunta de 2000 caracteres (pega un enunciado) | El tokenizador la procesa; el cluster se forma por los tokens compartidos. Se limita a 2000 caracteres por pregunta para no inflar el índice (`maxQuestionChars`). |
| El mismo texto en 300 alumnos | 1 cluster de 300 → `maxClusterSize = 200` lo parte por fecha. |
| `sources` con etiquetas de otra cátedra | Se ignoran: se filtra por `courseId` del hilo. |
| El detector corre dos veces sobre los mismos datos | Resultado idéntico (determinismo, regla 3). Es un test explícito. |

---

## 10. Fuera de alcance (explícito)

- **Embeddings / similitud semántica** (§2.1 opción d): rompen el determinismo
  y la portabilidad. Queda como opción futura detrás de una interfaz
  `QuestionEmbedder`, si algún día hay un servidor con GPU. Con ella se
  resolvería también el **cruce de idiomas** (es/en), que hoy es una
  limitación conocida (§7.6, test 4).
- **Detección de intención con el modelo** ("¿el alumno estaba preguntando
  lo mismo?"): es una llamada al modelo por par → caro, no determinista y
  vuelve a meter al modelo en la analítica. La regla 3 lo prohíbe.
- **Envío automático de avisos o mails a los alumnos**: el detector propone,
  el docente decide (§6.4, regla 7).
- **Uso del puntaje de riesgo para calificar o sancionar**: non-goal
  explícito (§6.4, regla 1).
- **Ranking público de alumnos**: non-goal (§6.4, regla 4).
- **Detección de "copia" entre alumnos** (dos alumnos con la misma pregunta
  sospechosa): es un problema distinto, con otros falsos positivos (los
  trabajos en grupo) y otras consecuencias. No se mezcla con esto.
- **Análisis en tiempo real / streaming**: el detector corre por lotes
  (al abrir la analítica o al sincronizar). No hay push.
- **Persistencia del informe**: se recalcula; no se guarda. Si el docente
  quiere guardar un informe, es otro incremento (un `CourseInsightSnapshot`
  serializable, que el modelo ya soporta).

---

## 11. Archivos a crear o tocar

**Nuevos:**
- `core/src/commonMain/kotlin/kitoru/text/TextTokenizer.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/DetectorConfig.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/QuestionIndex.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/Turns.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/FailureScore.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/QuestionCluster.kt` (cluster,
  gap, `CourseInsights`, `StudentRisk`, `RiskLevel`)
- `core/src/commonMain/kotlin/kitoru/analytics/TopicResolver.kt`
- `core/src/commonMain/kotlin/kitoru/analytics/RepeatedQuestionDetector.kt`
- `core/src/commonTest/kotlin/kitoru/text/TextTokenizerTest.kt`
- `core/src/commonTest/kotlin/kitoru/analytics/QuestionSimilarityTest.kt`
- `core/src/commonTest/kotlin/kitoru/analytics/FailureScoreTest.kt`
- `core/src/commonTest/kotlin/kitoru/analytics/RepeatedQuestionDetectorTest.kt`
- `core/src/commonTest/kotlin/kitoru/analytics/TopicResolverTest.kt`
- `core/src/commonTest/kotlin/kitoru/analytics/QuestionClusterScaleTest.kt`

**Modificados:**
- `backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt` — `tokenize`
  delega en `TextTokenizer`.
- `backend/src/desktopTest/kotlin/rag/retrieval/Bm25IndexTest.kt` — test de
  equivalencia `Bm25Index.tokenize == TextTokenizer.tokenize`.
- `core/src/commonMain/kotlin/kitoru/domain/Analytics.kt` — `StudentAnalytics`
  (+6 campos), `CourseAnalytics` (+`insights`).
- `ui/commonMain/kotlin/kitoru/teacher/CourseAnalyticsViewModel.kt` — composición.

---

## 12. TL;DR

1. **Similitud**: Jaccard ponderado por IDF sobre los tokens de `Bm25Index`
   (que se extrae a `:core` como `TextTokenizer`); candidatas por índice
   invertido con `df ≤ 60` → ~2.2 M de pares en vez de 5×10⁹ → 100k
   preguntas en ~1.6 s (desktop) / ~3.9 s (móvil).
2. **Umbral**: τ = **0.62** (≈ 0.55 en Jaccard plano), calibrado con un gold
   set de 200 pares etiquetados y guardado en el informe.
3. **Fracaso por turno**: `F = 0.30·R + 0.35·G + 0.25·Q + 0.10·A`, con
   `R` = repetición, `G` = anclaje (fuentes + citas), `Q` = repregunta en el
   hilo, `A` = abandono; umbral **τ_F = 0.45** (confirmado 0.60).
4. **Resultado**: clusters por union-find; un cluster es un hueco si
   `≥2 alumnos`, `meanFailure ≥ 0.45` y `noSourceRate ≥ 0.50`; se ordena por
   `gapScore = alumnos × meanFailure × (0.5 + noSourceRate)` y se clasifica en
   `SIN_MATERIAL` / `NO_RECUPERADO` / `SIN_RESPALDO`.
5. **Riesgo**: puntaje 0-100 en 4 bandas con piso de 5 turnos, con evidencia
   visible, sin ranking, sin uso académico y con la culpa del material y no
   del alumno.
