# 06 — El verificador de citas

> **Estado**: diseño propuesto (sustituye al verificador heredado de
> FunshiEngineAI, que hoy vive en
> `backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt`).
> **Motivación**: el verificador es, hoy, la única medida de *calidad* de
> Kitoru que no depende del modelo. Si la métrica central de la plataforma
> falla, falla todo lo demás.
> **Relacionado**: `docs/design/domain/06-tareas.md` §8 (que ya presupone un
> `rag.engine.GroundingChecker`), `docs/design/teacher/08-alumnos.md` §5.3
> (tarjetas de analítica), `docs/design/analytics/07-temporal-cohortes.md`
> (`WeekBucket`, `QualityPoint`).

---

## 0. Resumen de decisiones

| # | Tema | Decisión | Dónde |
|---|---|---|---|
| M1 | Verificar contra **todas** las fuentes | Se hace. Aparece el estado `MISATTRIBUTED` ("la cita [1] en realidad la sostiene [3]"). | §3.1 |
| M2 | Alcance de la comparación | Se extiende de *chunk* a *documento* (todos los chunks de la misma ruta). | §3.2 |
| M3 | Números | Se comparan por **valor canónico**, no por `contains`. "12" ya no matchea "120". | §3.3 |
| M4 | Cobertura | Umbral **adaptativo** por longitud + **mini-stemmer** en vez del prefijo de 5 letras. | §3.4 |
| M5 | Estados | `SUPPORTED` / `MISATTRIBUTED` / `UNSUPPORTED` / `UNVERIFIABLE` / `UNKNOWN_SOURCE` / `VACUOUS`. "Sin respaldo" ≠ "no verificable". | §3.5 |
| M6 | Fragmento | Ventana **centrada en el marcador** (no `take(400)` del principio) + flag `truncated`. | §3.6 |
| M7 | Nombres propios | Se deja de adivinar por mayúscula: se usa el **vocabulario del corpus** para separar "inventado" de "atribuido a otra fuente". | §3.7 |
| M8 | Reporte nuevo | `CitationReport` con `level`, `claims`, `checks` extendidos + `toStats()` que **no rompe** `CitationStats`. | §3.8 |
| §4 | Cita implícita | Se detectan **afirmaciones sin marcador** y se mide **% de afirmaciones con respaldo** (además del % de citas). | §4 |
| §5 | "Buena sin citas" | Dos ejes ortogonales (`citationLevel` y `groundingLevel`) + `NOT_MEASURABLE` explícito. **"No medible" nunca se muestra como 0%.** | §5 |
| §6 | Juez LLM | **Complementario y opt-in**, jamás la métrica oficial. Su valor real es **calibrar** el verificador determinista. | §6 |
| §7 | Tests | 24 tests actuales → 24 + 26 nuevos (+ 2 archivos de test nuevos). 3 expectativas existentes cambian a propósito. | §7 |
| §8 | Dominio | `CitationStats` suma 7 campos con default (JSON viejo sigue leyéndose); `StudentAnalytics` y `CourseAnalytics` pasan a métricas **anulables**. | §8 |

---

## 1. Qué mide hoy el verificador, con precisión

### 1.1 El pipeline

`CitationVerifier.verify(answer, sources)` hace exactamente esto:

```
1. matches = MARKER.findAll(answer)          // MARKER = \[(\d{1,3})]
2. si no hay marcadores:
      devuelve CitationReport(checks = [], hasCitations = false, valid = false,
                              unknown = [], uncited = [1..n])
3. por cada aparición del marcador:
      index   = número del marcador
      snippet = snippetAround(answer, inicio, fin)      // §1.2
      si index fuera de [1..sources.size]:
          check(index, label = "", snippet, supported = false,
                reason = "No hay una fuente [index] entre las recuperadas.")
      si no:
          verdict = checkSnippet(snippet, sources[index-1].text)
          check(index, label = sources[index-1].label, snippet,
                supported = verdict.first, reason = verdict.second)
4. unknown = índices fuera de rango (distinct)
   cited   = índices citados válidos (distinct)
   uncited = [1..n] - cited
5. valid = checks.all { it.supported }
```

Los `sources` son los `Chunk` que el RAG recuperó y que se le mandaron al
modelo (`RagEngine.prepare` arma el contexto como `[1] (label — heading)\ntexto`),
de modo que **el índice `[n]` de la respuesta corresponde a `sources[n-1]`**.

### 1.2 Cómo se extrae el fragmento (`snippetAround`)

- Arranca en el carácter previo a `.`, `!`, `?`, `\n` o `;` (o al principio
  del texto) y termina en el siguiente `.`, `!`, `?`, `\n` o `;` (o al final).
- O sea: **la oración completa** que contiene al marcador, no un entorno.
- Borra todos los marcadores `[n]` del fragmento, colapsa espacios y hace
  `.take(400)`.

### 1.3 Las tres reglas de "respaldada" (`checkSnippet`)

Con `normalize()` = minúsculas + `stripDiacritics` + `camelCase` partido +
puntuación → espacios (todo en minúsculas, sin acentos, sin puntuación):

| Orden | Regla | Condición | Si falla |
|---|---|---|---|
| 1 | **Literal** | `normalize(fuente).contains(normalize(fragmento))` | sigue |
| 2 | **Sin detalles inventados** | Todo *término clave* del fragmento aparece en `normalize(fuente)` (por `contains`) | `UNSUPPORTED` |
| 3 | **Paráfrasis** | `cobertura >= 0.5` de las palabras significativas | `UNSUPPORTED` |

- **Término clave** (`keyTerms`): un token del fragmento que
  (a) es un número con ≥ 2 dígitos o con punto decimal (`12`, `1.0`, `2024`), o
  (b) empieza en mayúscula, **no** es la primera palabra de la frase y **no**
  está en la lista `CAPITALIZED_COMMON`.
- **Palabra significativa** (`significantWords`): token normalizado de
  **≥ 4 letras** que no está en la lista `STOP`.
- **Variación tolerada** (`coveredBy`): coincidencia exacta, o prefijo común
  de **≥ 5 letras** con diferencia de largo **≤ 3**.

Casos que el código trata como respaldados de entrada: fragmento vacío
("Sí [1]."), fuente vacía → **no** respaldada ("La fuente está vacía…"), y
fragmento sin palabras significativas → respaldada.

### 1.4 Qué NO mide hoy

- No mira **otras fuentes**: si la cita [1] falla pero el dato estaba en la
  [3], la respuesta queda "sin respaldo" aunque sea cierta.
- No mira **afirmaciones sin `[n]`**: una respuesta entera sin citas no genera
  ningún `check`; sólo queda `hasCitations = false`.
- No distingue "la fuente no dice esto" de "no hay forma de saberlo".
- No mide la respuesta: mide la **cita**.

---

## 2. Limitaciones concretas (hallazgos)

Cada fila trae un ejemplo **mínimo y reproducible** y un veredicto.
"SE ARREGLA" = hay un cambio de código propuesto; "SE MANTIENE" = el
comportamiento es correcto y se documenta.

| # | Síntoma | Ejemplo mínimo | Tipo | Veredicto |
|---|---|---|---|---|
| **L1** | `MAX_SNIPPET_CHARS = 400` recorta el fragmento **desde el principio**, y el marcador suele estar **al final** de la oración: lo que se pierde es justamente lo que hay que verificar. Una cita cuyo enunciado está en dos oraciones se verifica mal. | `snippetAround` de una oración de 500 caracteres devuelve los primeros 400 y **nunca llega al marcador**. | Falso negativo | **SE ARREGLA** (M6) |
| **L2** | `MIN_WORD_COVERAGE = 0.5` es un umbral fijo: no depende del idioma, ni de la longitud del fragmento, ni de cuántas palabras hay. Con 2 palabras significativas, 1 coincidencia = 50% = pasa. Con 30 palabras, 15 = 50% = pasa. | "El motor usa Vulkan [1]" (2 palabras) → 1 coincidencia alcanza para "validar" algo que no dice la fuente. | Falso positivo | **SE ARREGLA** (M4) |
| **L3** | Los números se comparan con `contains` sobre el texto normalizado: "12" matchea "120", "1.0" se normaliza a "1 0" y matchea cualquier "1 0". | Fuente: *"Windows 120 Hz"*. Respuesta: *"usa DirectX 12 [1]"* → **"12" está en "120"** → la invención pasa como respaldada. | Falso positivo (grave: es el criterio que separa invención de paráfrasis) | **SE ARREGLA** (M3) |
| **L4** | Los nombres propios se detectan **sólo por mayúscula**. Falla con estilos tipográficos en minúscula ("el algoritmo de viterbi") y con nombres que no son propios. | Fuente: *"…se usa Vulkan"*. Respuesta: *"usa directx 12 [1]"* → "directx" minúscula **no es término clave** → la cita se da por respaldada. | Falso negativo | **SE ARREGLA** (M7) |
| **L4b** | Al revés: palabras funcionales capitalizadas que **no** están en `CAPITALIZED_COMMON` se toman por nombres propios. "tambien", "ademas", "cada", "otro" no están en la lista. | Respuesta: *"El motor **también** usa Vulkan [1]"* → "tambien" es término clave y no está en la fuente → **cita inválida**. | Falso positivo | **SE ARREGLA** (M7) |
| **L4c** | `keyTerms` descarta la **primera palabra de la frase** (índice 0), así que un nombre propio al principio de la oración nunca se detecta. | Respuesta: *"Vulkan es el motor gráfico [1]"*, fuente: *"OpenGL es el motor gráfico"* → "Vulkan" (índice 0) se ignora → la invención pasa. | Falso negativo | **SE ARREGLA** (M7) |
| **L5** | La cobertura por prefijo de 5 letras: "aprendizaje" vs "aprendizajes" funciona, pero "minimización" vs "minimiza" no (`stripDiacritics` → "minimizacion", 12 vs 8 letras: diferencia 4 > 3). | Respuesta: *"Se busca la minimiza [1]"*, fuente: *"…la minimización…"* → sin coincidencia. | Falso negativo | **SE ARREGLA** (M4) |
| **L6** | El verificador mira **una sola fuente** por cita: si la respuesta combina dos fuentes y cita una sola, la otra parte queda sin verificar; y si el modelo se equivoca de índice, el resultado es "inventado" cuando el dato **sí** estaba en el material. | Fuentes: 1 = "usa Vulkan", 2 = "usa OpenGL". Respuesta: *"usa OpenGL [1]"* → "sin respaldo", cuando la respuesta es **correcta**. | Falso negativo + pérdida de información | **SE ARREGLA** (M1) |
| **L7** | `valid = checks.all { it.supported }`: una sola cita sin respaldo invalida toda la respuesta; al mismo tiempo una fuente vacía (que no dice nada, no que contradiga) **también** la invalida. | Fuente vacía → `supported = false` → `valid = false`. | Mezcla de conceptos | **SE ARREGLA** (M5) |
| **L7b** | `hasCitations = false` ⇒ `valid = false`: una respuesta sin citas es "inválida". Es el falso positivo de §5. | Respuesta excelente sin citas → `valid = false`. | Falso positivo | **SE ARREGLA** (§5) |
| **L8** | El corte de oración incluye `;`: el fragmento pierde la primera cláusula. | *"El motor usa Vulkan; también compila con CMake [1]."* → snippet = "también compila con CMake" → la cita se verifica sobre la mitad de la frase. | Falso negativo | **SE ARREGLA** (M6) |
| **L9** | `snippetAround` corta en cualquier `.`, incluso en un decimal: "El resultado es 3.14 [1]" → snippet = "El resultado es 3". | Verificable con un caso de test. | Falso negativo | **SE ARREGLA** (M6) |
| **L10** | `significantWords` exige **≥ 4 letras**: "GPU", "IA", "ley", "red", "UBA" no cuentan. Un fragmento con 3 palabras significativas se "respalda" con 1 coincidencia (33%… pero el umbral es 50%, así que en realidad 2 de 3). | "La GPU usa Vulkan [1]" → palabras = {vulkan} (1 palabra) → si aparece, pasa. | Falso positivo | **SE ARREGLA** (M4) |
| **L11** | `unsupported` cuenta también las citas a fuentes inexistentes (`!supported && index > 0`), y `total = verified + unsupported + unknown` las **cuenta dos veces**. | Una cita a [5] inexistente: `unsupported = 1` **y** `unknown = [5]` → total = 2 con una sola cita. | Doble conteo en el denominador | **SE ARREGLA** (M5) |
| **L12** | Una cita **sin texto** ("Sí [1].") cuenta como respaldada: el modelo puede "cumplir" con la cuota de citas poniendo marcadores vacíos. | `"Sí [1]."` → `supported = true`. | Gaming | **SE ARREGLA** (M5: estado `VACUOUS`) |
| **L13** | La cita repetida N veces genera N `checks`: infla el denominador de `supportRate` y hace que una respuesta con la misma cita 5 veces pese 5. | `"A [1] B [1] C [1]."` → 3 checks, 1 afirmación. | Sesgo de métrica | **SE ARREGLA** (M4: se cuenta por afirmación) |
| **L14** | El `reason` no distingue "la fuente no dice esto" de "el modelo atribuyó mal" ni de "no se puede verificar": el docente recibe un texto genérico. | `"El fragmento no aparece en la fuente (sólo el 33% de sus palabras)."` | Información perdida | **SE ARREGLA** (M5) |
| **L15** | `uncited` (fuentes no citadas) no participa de `valid` (bien), pero no se explica en ningún lado: el docente no sabe si es un problema. | — | Menor | **SE MANTIENE** (se documenta: no es un error) |

### 2.1 Lo que está bien y no hay que tocar

- El verificador es **determinista y sin red** (regla 3 de `CONTEXTO.md`).
  Eso se conserva: es la condición para que la analítica sea reproducible.
- La comparación **literal** primero es correcta y barata: es la vía rápida y
  resuelve la mayoría de los casos.
- El criterio 2 (detalles inventados) es la idea más valiosa del verificador
  original: separa "paráfrasis" de "invención". Se **arregla**, no se tira.
- La normalización (minúsculas, sin acentos, sin puntuación, camelCase
  partido) es necesaria porque el modelo escribe "DirectX" y la fuente
  "directx".

---

## 3. Las mejoras propuestas

### 3.0 La arquitectura nueva

Hoy todo vive en un objeto de 300 líneas. La mejora estructural es separar
**el motor de matching** (reusable) del **producto del verificador**:

| Archivo | Contenido | Nuevo |
|---|---|---|
| `backend/src/commonMain/kotlin/rag/engine/Normalizer.kt` | Normalización, tokenización, números canónicos, mini-stemmer, listas de palabras funcionales. | ✅ |
| `backend/src/commonMain/kotlin/rag/engine/TextMatch.kt` | `TextMatch.match(claim, source, options): MatchResult` — **las tres reglas**, con umbral adaptativo. | ✅ |
| `backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt` | Estados, verificación cruzada entre fuentes, `CitationReport`. | 🔁 reescrito |
| `backend/src/commonMain/kotlin/rag/engine/GroundingChecker.kt` | Segmentación en afirmaciones y métrica de afirmaciones con respaldo. | ✅ |
| `backend/src/commonMain/kotlin/rag/eval/CitationJudge.kt` | Juez LLM (opt-in, complementario). | ✅ |

`TextMatch` es el corazón: lo usan **el verificador de citas** (para
fragmentos con marcador), **el verificador de afirmaciones** (para frases sin
marcador) y **el `GroundingChecker` de `06-tareas.md`** (para las entregas del
alumno). Una sola implementación de las reglas ⇒ una sola métrica.

```kotlin
// backend/src/commonMain/kotlin/rag/engine/TextMatch.kt

/** Cómo terminó el cotejo de un fragmento contra una fuente. */
enum class MatchKind {
    /** La fuente contiene el fragmento tal cual. */
    LITERAL,

    /** La fuente dice lo mismo con otras palabras. */
    PARAPHRASE,

    /** La fuente contradice o no menciona un detalle clave. */
    INVENTED_DETAIL,

    /** El fragmento no tiene contenido verificable. */
    NO_CONTENT,

    /** La fuente está vacía: no hay nada con qué comparar. */
    EMPTY_SOURCE,
}

/** Resultado del cotejo. */
data class MatchResult(
    val kind: MatchKind,
    val supported: Boolean,
    /** Proporción de palabras significativas cubiertas (0..1). */
    val coverage: Double,
    /** Cuántas palabras significativas se cubrieron. */
    val matched: Int,
    /** Cuántas palabras significativas había. */
    val total: Int,
    /** Detalles que no aparecen en la fuente. */
    val missingDetails: List<String> = emptyList(),
    /** Explicación para el docente (en español). */
    val reason: String = "",
)

/** Parámetros del cotejo. */
data class MatchOptions(
    /** Umbral de cobertura manual; si es null, se calcula con [coverageThreshold]. */
    val coverage: Double? = null,
    /** Longitud mínima de una palabra significativa. */
    val minWordLength: Int = 4,
    /**
     * Vocabulario del corpus (tokens normalizados). Permite distinguir
     * "el modelo inventó un término" de "el modelo usó un término que está
     * en otra fuente".
     */
    val vocabulary: Set<String> = emptySet(),
)
```

### 3.1 M1 — Verificar la cita contra **todas** las fuentes

**Idea**: una cita no es "buena" o "mala" en el vacío. Si el fragmento no lo
respalda la fuente citada pero **sí** otra fuente recuperada, la respuesta es
*correcta* y lo que falla es la **atribución**. Eso es información distinta
y accionable ("el modelo se equivocó de índice"), y además evita marcar como
"inventado" lo que el material sí dice.

```kotlin
/** Verificación de una cita concreta. */
data class CitationCheck(
    /** Índice citado (1 = la primera fuente recuperada). */
    val index: Int,
    /** Etiqueta de la fuente citada (vacía si el índice no existe). */
    val label: String,
    /** Fragmento de la respuesta que acompaña a la cita. */
    val snippet: String,
    /** Estado de la verificación. */
    val status: CitationStatus,
    /** Por qué (vacío si está respaldada). */
    val reason: String = "",
    /** Fuentes que **sí** respaldan el fragmento (puede incluir la citada). */
    val supportedBy: List<Int> = emptyList(),
    /** Índice de la fuente que mejor respalda el fragmento. */
    val bestSource: Int = 0,
    /** Etiqueta de esa fuente. */
    val bestSourceLabel: String = "",
    /** Cobertura lograda contra la fuente citada. */
    val coverage: Double = 0.0,
    /** El fragmento se recortó: puede faltar contexto. */
    val truncated: Boolean = false,
    /** El marcador no acompaña ninguna afirmación. */
    val vacuous: Boolean = false,
    /** Términos del fragmento que no están en ninguna fuente (posible invención). */
    val unattributable: List<String> = emptyList(),
    /** Índice de la afirmación a la que pertenece (ver §4). */
    val claimIndex: Int = -1,
) {
    /** ¿La fuente citada respalda el fragmento? (compatibilidad con la versión anterior) */
    val supported: Boolean get() = status == CitationStatus.SUPPORTED

    /** ¿Es un problema (no respaldada, mal atribuida o inexistente)? */
    val isProblem: Boolean
        get() = status == CitationStatus.UNSUPPORTED ||
            status == CitationStatus.MISATTRIBUTED ||
            status == CitationStatus.UNKNOWN_SOURCE
}
```

El algoritmo, en `CitationVerifier.verify`:

```kotlin
fun verify(
    answer: String,
    sources: List<Chunk>,
    options: VerifyOptions = VerifyOptions(),
): CitationReport {
    val markers = MARKER.findAll(answer).toList()
    if (markers.isEmpty()) {
        // Sin citas: el verificador de citas no tiene nada que decir.
        // Lo que se mide (si lo hay) es la fundamentación de las afirmaciones.
        val grounding = GroundingChecker.ground(answer, sources, options)
        return CitationReport(
            checks = emptyList(),
            hasCitations = false,
            level = GroundingLevel.NOT_MEASURABLE,
            unknown = emptyList(),
            uncited = sources.indices.map { it + 1 },
            claims = grounding.claims,
            groundingLevel = grounding.level,
        )
    }

    val checks = markers.map { marker ->
        val index = marker.groupValues[1].toIntOrNull() ?: 0
        val snippet = snippetAround(answer, marker.range.first, marker.range.last + 1)
        verifyOne(index, snippet, sources, options)
    }

    val level = aggregateLevel(checks)
    val grounding = GroundingChecker.ground(answer, sources, options)
    return CitationReport(
        checks = checks,
        hasCitations = true,
        level = level,
        unknown = checks.filter { it.status == CitationStatus.UNKNOWN_SOURCE }.map { it.index }.distinct(),
        uncited = sources.indices.map { it + 1 }
            .filter { idx -> checks.none { it.index == idx } },
        claims = grounding.claims,
        groundingLevel = grounding.level,
    )
}

/**
 * Coteja un fragmento contra la fuente citada y contra **todas** las demás.
 */
private fun verifyOne(
    index: Int,
    snippet: Snippet,
    sources: List<Chunk>,
    options: VerifyOptions,
): CitationCheck {
    if (index < 1 || index > sources.size) {
        return CitationCheck(
            index = index, label = "", snippet = snippet.text,
            status = CitationStatus.UNKNOWN_SOURCE,
            reason = "No hay una fuente [$index] entre las recuperadas.",
        )
    }

    // 1. Cotejo contra cada fuente recuperada.
    val verdicts = sources.mapIndexed { i, chunk ->
        val text = options.documents[chunk.path] ?: chunk.text   // M2: documento completo
        i + 1 to TextMatch.match(snippet.text, text, options.match)
    }
    val mine = verdicts.first { it.first == index }
    val others = verdicts.filter { it.first != index }

    val supporting = verdicts.filter { it.second.supported }.map { it.first }
    val best = verdicts.maxByOrNull { it.second.coverage }

    val status = when {
        mine.second.supported -> CitationStatus.SUPPORTED
        supporting.isNotEmpty() -> CitationStatus.MISATTRIBUTED
        mine.second.kind == MatchKind.EMPTY_SOURCE -> CitationStatus.UNVERIFIABLE
        snippet.truncated -> CitationStatus.UNVERIFIABLE
        else -> CitationStatus.UNSUPPORTED
    }

    val reason = when (status) {
        CitationStatus.SUPPORTED -> ""
        CitationStatus.MISATTRIBUTED ->
            "La fuente [$index] no respalda el fragmento, pero la fuente " +
                "[${supporting.joinToString(", ")}] sí: la cita está mal atribuida."
        CitationStatus.UNVERIFIABLE ->
            if (mine.second.kind == MatchKind.EMPTY_SOURCE) "La fuente está vacía: no hay nada que respalde la cita."
            else "El fragmento se recortó: no se puede decidir con el contexto disponible."
        CitationStatus.UNSUPPORTED -> mine.second.reason
        CitationStatus.UNKNOWN_SOURCE -> "No hay una fuente [$index] entre las recuperadas."
        CitationStatus.VACUOUS -> "La cita no acompaña ninguna afirmación."
    }

    return CitationCheck(
        index = index,
        label = sources[index - 1].label,
        snippet = snippet.text,
        status = status,
        reason = reason,
        supportedBy = supporting,
        bestSource = best?.first ?: index,
        bestSourceLabel = best?.let { sources[it.first - 1].label }.orEmpty(),
        coverage = mine.second.coverage,
        truncated = snippet.truncated,
        vacuous = mine.second.kind == MatchKind.NO_CONTENT,
        unattributable = mine.second.missingDetails,
    )
}
```

**Lo que el docente ve** (mensaje de `reason`):

| Situación | `status` | Mensaje |
|---|---|---|
| Todo bien | `SUPPORTED` | — |
| Cita correcta, índice equivocado | `MISATTRIBUTED` | *"La fuente [1] no respalda el fragmento, pero la fuente [3] sí: la cita está mal atribuida."* |
| El dato no está en ninguna fuente | `UNSUPPORTED` | *"La cita menciona "DirectX", que no aparece en ninguna de las fuentes recuperadas."* |
| La fuente está vacía | `UNVERIFIABLE` | *"La fuente está vacía: no hay nada que respalde la cita."* |

### 3.2 M2 — El alcance de la comparación: del chunk al documento

**Aclaración honesta**: hoy el verificador **ya** compara contra el chunk
entero (`checkSnippet(snippet, source.text)`), no contra un fragmento de la
fuente. Lo que falta es el alcance del *chunk*: un chunk es una porción
arbitraria de un documento (por defecto 1200 caracteres con 150 de solape), y
una afirmación puede caer justo en el corte.

**Mejora**: comparar contra el **documento completo** cuando esté disponible.

```kotlin
/** Parámetros de una verificación. */
data class VerifyOptions(
    /** Texto completo por ruta de documento (path → texto). Si falta, se usa el chunk. */
    val documents: Map<String, String> = emptyMap(),
    /** Vocabulario de todo el corpus (tokens normalizados), para M7. */
    val vocabulary: Set<String> = emptySet(),
    /** Opciones del cotejo. */
    val match: MatchOptions = MatchOptions(),
    /** Segmentar además las afirmaciones sin cita. */
    val checkClaims: Boolean = true,
)
```

De dónde sale `documents`: el `KnowledgeBase` ya indexa los documentos
completos antes de partirlos (`Chunker.split(doc, …)`), así que alcanza con
exponerlos:

```kotlin
// rag/retrieval/KnowledgeBase.kt (agregar)
/** Texto completo de cada documento indexado, por ruta. */
val documents: Map<String, String> get() = docs.associate { it.path to it.text }
```

Y el call site:

```kotlin
// rag/engine/RagEngine.kt
fun verify(answer: String, sources: List<Chunk>): CitationReport =
    CitationVerifier.verify(
        answer = answer,
        sources = sources,
        options = VerifyOptions(
            documents = kb.documents,
            vocabulary = kb.vocabulary,
        ),
    )
```

**Efecto**: una afirmación que quedó partida entre dos chunks deja de
"inventarse" por un corte de texto. **Coste**: el documento puede ser 10×
más largo que el chunk ⇒ la comparación literal es más cara. Mitigación: se
busca primero en el chunk (rápido) y sólo si falla se busca en el documento
completo (lento, pero es el caso raro).

```kotlin
// En verifyOne: el documento completo es el segundo intento.
val text = options.documents[chunk.path] ?: chunk.text
```
(el orden inverso — chunk primero, documento después — se implementa como
"cotejo en dos pasadas": `TextMatch.match` contra el chunk; si da
`UNSUPPORTED`, se repite contra el documento y se queda el mejor resultado).

### 3.3 M3 — Números con límites

**Problema**: `normSource.contains(it.text)` donde `it.text` es la forma
normalizada del número. "12" ⊂ "120". Y "1.0" se normaliza a "1 0", que
matchea cualquier "1 0" (incluso "1 0 5").

**Solución**: extraer los números como **tokens** y compararlos por **valor
canónico**.

```kotlin
// backend/src/commonMain/kotlin/rag/engine/Normalizer.kt

/** Un número dentro de un texto. */
private val NUMBER_TOKEN = Regex("""\d+(?:[.,]\d+)*""")

/**
 * Forma canónica de un número: se comparan por valor, no por texto.
 *
 * - "1.0" y "1" son el mismo número → "1".
 * - "1,50" (coma decimal) y "1.50" son el mismo → "1.5".
 * - "12" y "120" son distintos → "12" vs "120".
 */
internal fun canonNumber(raw: String): String {
    var s = raw.replace(',', '.')
    if ('.' in s) {
        s = s.trimEnd('0').trimEnd('.')
        if (s.isEmpty()) s = "0"
    }
    return s
}

/**
 * Números que aparecen en un texto, por valor canónico.
 *
 * Se extraen del texto **crudo** (no normalizado): la normalización
 * convierte "1.0" en "1 0" y destruye la información del decimal.
 */
internal fun numbersIn(text: String): Set<String> =
    NUMBER_TOKEN.findAll(text).map { canonNumber(it.value) }.toSet()

/**
 * ¿Está el número [value] en [sourceText]?
 *
 * Comparación por token: "12" NO está en "120 Hz" (el bug L3).
 */
internal fun numberIn(value: String, sourceText: String): Boolean =
    canonNumber(value) in numbersIn(sourceText)
```

Uso dentro de `keyTerms`:

```kotlin
/** Términos clave del fragmento. */
internal fun keyTerms(snippet: String): List<KeyTerm> {
    val tokens = snippet
        .split(' ', '\n', '\t', ',', ';', ':', '(', ')', '"', '\'', '¿', '?', '!', '¡')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return tokens.mapNotNull { token ->
        when {
            // 1) Número: se compara por valor canónico, no por substring.
            NUMBER.matches(token) -> {
                val digits = token.count { it.isDigit() }
                if (digits >= 2 || token.contains('.') || token.contains(',')) {
                    KeyTerm(
                        text = normalize(token),          // "1 0" (para mostrar)
                        raw = token,
                        isNumber = true,
                        canonical = canonNumber(token),   // "1" (para comparar)
                    )
                } else null
            }

            // 2) Nombre propio / tecnicismo capitalizado.
            //    Ya NO se descarta el primer token de la frase: el problema
            //    de "El" / "También" se resuelve con las listas de palabras
            //    funcionales (completadas), no con una excepción posicional.
            else -> {
                if (!token.first().isUpperCase()) return@mapNotNull null
                val norm = normalize(token)
                if (norm in FUNCTIONAL_WORDS) return@mapNotNull null
                KeyTerm(text = norm, raw = token, isNumber = false, canonical = norm)
            }
        }
    }.filter { it.text.isNotBlank() }.distinctBy { it.canonical }
}
```

Y la comparación, en `TextMatch`:

```kotlin
// Regla 2: los detalles concretos tienen que estar en la fuente.
val inventados = keyTerms(claim).filter { term ->
    if (term.isNumber) !numberIn(term.canonical, sourceText)
    else !containsPhrase(sourceTokens, term.canonical)
}
```

`containsPhrase` (en vez de `contains` de cadena) evita que "open" matchee
"opens" o que "direct x" matchee sólo por halves:

```kotlin
/**
 * ¿La secuencia de palabras [phrase] aparece en la fuente?
 *
 * Se busca como **secuencia contigua** de tokens normalizados, no como
 * substring de texto: "direct x" sólo matchea si la fuente tiene el mismo
 * camelCase partido ("directx" → "direct x" en los dos lados), y no si
 * tiene "directo" o "xm.direct".
 */
internal fun containsPhrase(sourceTokens: List<String>, phrase: String): Boolean {
    val parts = phrase.split(' ').filter { it.isNotBlank() }
    if (parts.isEmpty()) return false
    if (parts.size > sourceTokens.size) return false
    return sourceTokens.indices.any { i ->
        parts.indices.all { j -> sourceTokens[i + j] == parts[j] }
    }
}
```

> `TextMatch` trabaja con **dos** vistas de la fuente: `sourceTokens`
> (lista ordenada, para buscar secuencias) y `sourceWords` (conjunto, para
> la cobertura). `sourceWords = sourceTokens.toSet()`.


### 3.4 M4 — Cobertura adaptativa + mini-stemmer

**Dos cambios**: (a) el umbral depende del tamaño del fragmento; (b) la
"variación tolerable" se calcula por raíz, no por prefijo de 5 letras.

#### (a) Umbral adaptativo

```kotlin
/**
 * Umbral de cobertura según cuántas palabras significativas tiene el
 * fragmento.
 *
 * Un fragmento corto tiene que coincidir casi entero: con 2 palabras, exigir
 * el 50% es pedir **una** coincidencia, y el modelo puede decir cualquier
 * cosa. Uno largo tolera más: si no, las paráfrasis legítimas de los
 * modelos chicos nunca pasarían.
 *
 *   1-2 palabras  → 100%  (todo o nada)
 *   3-5 palabras  →  60%
 *   6-12 palabras →  50%
 *   > 12 palabras →  45%
 */
internal fun coverageThreshold(wordCount: Int): Double = when {
    wordCount <= 2 -> 1.0
    wordCount <= 5 -> 0.60
    wordCount <= 12 -> 0.50
    else -> 0.45
}

/**
 * Mínimo de palabras que tienen que coincidir, en absoluto.
 *
 * El porcentaje solo no alcanza: con 30 palabras, el 45% son 13… pero con
 * 3 palabras el 60% es 2. El mínimo absoluto es lo que evita el
 * "1 palabra de 2 = 50% = respaldado".
 */
internal fun minMatches(wordCount: Int): Int = when {
    wordCount <= 2 -> 2
    wordCount <= 5 -> 2
    wordCount <= 12 -> 3
    else -> 4
}
```

El cotejo pasa a ser:

```kotlin
val words = significantWords(normClaim)
if (words.isEmpty()) return MatchResult(MatchKind.NO_CONTENT, supported = true, …)

val matched = words.count { coveredBy(it, sourceWords) }
val coverage = matched.toDouble() / words.size
val threshold = options.coverage ?: coverageThreshold(words.size)
val absolute = minMatches(words.size)

if (matched >= absolute && coverage >= threshold) {
    return MatchResult(MatchKind.PARAPHRASE, supported = true, coverage = coverage, …)
}
```

> **Compatibilidad con los tests actuales**: el caso
> `"Los motores usan Vulkan [1]."` tiene 3 palabras significativas
> (`motores`, `usan`, `vulkan`) y coincide con 2 (`motores`, `vulkan`):
> cobertura 0.67 ≥ 0.60 y 2 coincidencias ≥ 2 ⇒ sigue respaldado. Con la
> tolerancia de una edición, además, "usan" ≈ "usa" y pasa a 3/3.

#### (b) Mini-stemmer

Nada de Snowball: una lista corta de sufijos, determinista y testeable.

```kotlin
/**
 * Radical ligero: suficiente para decidir si dos palabras son "la misma".
 *
 * No es un stemmer linguístico (no maneja ni tildes ni prefijos); es una
 * aproximación con dos reglas:
 *
 *  1. **Plural**: "motores" → "motor", "partículas" → "particula".
 *  2. **Terminación verbal o nominal**: "renderizar" → "renderiz",
 *     "renderiza" → "renderiz", "minimización" → "minimiz".
 *
 * Se aplica sólo si queda una raíz de al menos [MIN_STEM_LEN] letras, para
 * no destrozar palabras cortas.
 */
internal fun stem(word: String): String {
    if (word.length < 5) return word
    var w = word

    // 1) Plurales y plurales con terminación dental.
    if (w.endsWith("ces") && w.length - 3 >= MIN_STEM_LEN) {
        w = w.dropLast(2)                      // "minimizaciones" → "minimizacion"
    } else if (w.endsWith("es") && w.length - 2 >= MIN_STEM_LEN && !w.endsWith("ses")) {
        w = w.dropLast(2)                      // "motores" → "motor"
    } else if (w.endsWith("s") && w.length - 1 >= MIN_STEM_LEN && !w.endsWith("ss")) {
        w = w.dropLast(1)                      // "partículas" → "particula"
    }

    // 2) Terminaciones verbales / nominales (de la más larga a la más corta).
    for (suffix in STEM_SUFFIXES) {
        if (w.endsWith(suffix) && w.length - suffix.length >= MIN_STEM_LEN) {
            w = w.dropLast(suffix.length)
            break
        }
    }
    return w
}

/** Sufijos que se quitan (del más largo al más corto). */
private val STEM_SUFFIXES = listOf(
    "amiento", "imiento", "acion", "ucion", "mente",
    "ando", "iendo", "ado", "ida", "ido", "ada",
    "ar", "er", "ir", "a", "o",
)

/** Largo mínimo de la raíz que queda. */
private const val MIN_STEM_LEN = 4
```

> Ojo con el orden: "minimizacion" (12) → plural? no → sufijo "acion" ⇒
> "minimiz" (7). "minimiza" (8) → no plural → sufijo "a" ⇒ "minimiz" (7).
> Coinciden ⇒ "minimización" ≈ "minimiza" (que era el bug L5).
> "renderizar" → "renderiz"; "renderiza" → "renderiz" ⇒ coinciden.
> "motores" → "motor"; "motor" (5) → sin cambios ⇒ coinciden.

Y el cotejo por raíz:

```kotlin
/**
 * ¿La palabra [word] de la respuesta aparece en la fuente?
 *
 * Tres tolerancias, de la más estricta a la más floja:
 *  1. igualdad exacta (o de la raíz);
 *  2. diferencia de una letra (errata): "renderisación" ≈ "renderización";
 *  3. prefijo común ≥ 5 con diferencia de largo ≤ 4 (derivaciones largas:
 *     "minimiza" ≈ "minimizacion").
 */
private fun coveredBy(word: String, sourceWords: Set<String>): Boolean {
    if (word in sourceWords) return true
    val stem = stem(word)
    if (sourceWords.any { stem(it) == stem }) return true

    val close = sourceWords.filter { editDistance1(word, it) || editDistance1(stem, it) }
    if (close.isNotEmpty()) return true

    return sourceWords.any { other ->
        val common = commonPrefix(word, other)
        common >= 5 && kotlin.math.abs(word.length - other.length) <= 4
    }
}
```

`editDistance1` (distancia de Levenshtein ≤ 1) es O(n) con dos filas y sólo
se aplica a palabras de ≤ 12 letras.

**Sobre L10** (palabras de < 4 letras no cuentan): se baja `minWordLength` a
**3** y se agrega una lista corta de siglas frecuentes ("GPU", "CPU", "IA",
"UBA", "API", "URL"). "GPU" sigue sin contar como *palabra* (es un
identificador), pero sí como *detalle* si aparece capitalizado en el
fragmento — que es exactamente lo que hace la regla 2 con las siglas.

**Sobre L13** (citas repetidas): la métrica se cuenta **por afirmación**, no
por marcador. `"A [1] B [1] C [1]."` es **una** afirmación con tres
marcadores: el marcador se reporta tres veces (para el detalle) pero la
afirmación cuenta una.

### 3.5 M5 — Estados: "sin respaldo" ≠ "no verificable"

```kotlin
/** Estado de una cita. */
enum class CitationStatus(val label: String) {
    /** La fuente citada respalda el fragmento. */
    SUPPORTED("Respaldada"),

    /** La fuente citada no lo respalda, pero otra fuente recuperada sí. */
    MISATTRIBUTED("Atribución incorrecta"),

    /** Ninguna fuente recuperada respalda el fragmento. */
    UNSUPPORTED("Sin respaldo"),

    /** No se puede decidir (fuente vacía, fragmento truncado, sin material). */
    UNVERIFIABLE("No verificable"),

    /** La cita apunta a un índice que no existe entre las fuentes. */
    UNKNOWN_SOURCE("Fuente inexistente"),

    /** El marcador no acompaña ninguna afirmación. */
    VACUOUS("Sin contenido"),
}
```

Y el agregado:

```kotlin
/**
 * Nivel de calidad, en un eje.
 *
 * La distinción clave es [NOT_MEASURABLE]: **"no se puede medir" no es
 * "malo"**. Una respuesta sin citas, o sin material contra el cual cotejar,
 * no es una respuesta inválida: es una respuesta fuera del alcance de la
 * métrica. En la UI se muestra como tal, nunca como 0%.
 */
enum class GroundingLevel(val label: String) {
    /** No hay nada que medir. */
    NOT_MEASURABLE("No medible"),

    /** Todo lo medible tiene respaldo. */
    SUPPORTED("Respaldada"),

    /** Parte tiene respaldo y parte no (o hay partes no verificables). */
    PARTIAL("Parcial"),

    /** Nada tiene respaldo. */
    UNSUPPORTED("Sin respaldo"),
}
```

Reglas de agregación:

```kotlin
/**
 * Nivel del eje de CITACIÓN.
 *
 * - sin citas                       → NOT_MEASURABLE
 * - alguna cita sin respaldo o
 *   a una fuente inexistente        → UNSUPPORTED
 * - alguna cita mal atribuida o
 *   no verificable                  → PARTIAL
 * - todas respaldadas               → SUPPORTED
 */
internal fun aggregateLevel(checks: List<CitationCheck>): GroundingLevel = when {
    checks.isEmpty() -> GroundingLevel.NOT_MEASURABLE
    checks.any { it.status == CitationStatus.UNSUPPORTED || it.status == CitationStatus.UNKNOWN_SOURCE } ->
        GroundingLevel.UNSUPPORTED
    checks.any { it.status == CitationStatus.MISATTRIBUTED || it.status == CitationStatus.UNVERIFIABLE } ->
        GroundingLevel.PARTIAL
    else -> GroundingLevel.SUPPORTED
}
```

Y el `CitationReport`:

```kotlin
/** Resultado de verificar todas las citas de una respuesta. */
data class CitationReport(
    /** Verificación de cada cita (una por aparición de marcador). */
    val checks: List<CitationCheck>,

    /** ¿La respuesta tiene alguna cita? */
    val hasCitations: Boolean,

    /** Nivel del eje de citación. */
    val level: GroundingLevel,

    /** Índices citados que no corresponden a ninguna fuente. */
    val unknown: List<Int> = emptyList(),

    /** Fuentes recuperadas que el modelo no citó. */
    val uncited: List<Int> = emptyList(),

    /** Afirmaciones detectadas en la respuesta (con y sin marcador). */
    val claims: List<ClaimCheck> = emptyList(),

    /** Nivel del eje de fundamentación (afirmaciones con respaldo). */
    val groundingLevel: GroundingLevel = GroundingLevel.NOT_MEASURABLE,
) {
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int get() = checks.count { it.status == CitationStatus.SUPPORTED }

    /** Citas sin respaldo (ninguna fuente respalda el fragmento). */
    val unsupported: Int get() = checks.count { it.status == CitationStatus.UNSUPPORTED }

    /** Citas respaldadas, pero atribuidas al número equivocado. */
    val misattributed: Int get() = checks.count { it.status == CitationStatus.MISATTRIBUTED }

    /** Citas que no se pueden verificar. */
    val unverifiable: Int get() = checks.count { it.status == CitationStatus.UNVERIFIABLE }

    /** Citas sin contenido verificable. */
    val vacuous: Int get() = checks.count { it.status == CitationStatus.VACUOUS }

    /** Citas que apuntan a una fuente inexistente. */
    val unknownCount: Int get() = unknown.size

    /** Total de citas (sin doble conteo: cada cita en un solo estado). */
    val total: Int get() = checks.size

    /**
     * Proporción de citas respaldadas (0..1).
     *
     * El denominador excluye las citas **no medibles** (fuente vacía,
     * fragmento recortado) y las vacías: no son "respaldadas" ni "sin
     * respaldo", son **incertidumbre**. Por eso el nivel por defecto es
     * [GroundingLevel.NOT_MEASURABLE] y no 0.
     */
    val supportRate: Double?
        get() {
            val measurable = verified + misattributed + unsupported + unknownCount
            return if (measurable == 0) null else verified.toDouble() / measurable
        }

    /** % de afirmaciones con respaldo (0..1). null si no hay afirmaciones medibles. */
    val claimSupportRate: Double?
        get() {
            val measurable = claims.count { it.status != ClaimStatus.NOT_MEASURABLE }
            if (measurable == 0) return null
            val grounded = claims.count {
                it.status == ClaimStatus.CITED_SUPPORTED || it.status == ClaimStatus.IMPLICIT_SUPPORTED
            }
            return grounded.toDouble() / measurable
        }

    /**
     * Alias compatible con la versión anterior del reporte.
     *
     * Antes: `valid = checks.all { it.supported }`.
     * Ahora: "no hay citas problemáticas". Las citas no verificables ya no
     * invalidan (se informan aparte), y una respuesta sin citas queda
     * [GroundingLevel.NOT_MEASURABLE], que es información distinta de
     * "inválida".
     */
    val valid: Boolean
        get() = level == GroundingLevel.SUPPORTED || level == GroundingLevel.PARTIAL

    /** Conversión al resumen que se persiste en el dominio. */
    fun toStats(): CitationStats = CitationStats(
        verified = verified,
        unsupported = unsupported,
        unknown = unknownCount,
        misattributed = misattributed,
        unverifiable = unverifiable,
        vacuous = vacuous,
        claims = claims.count { it.status != ClaimStatus.NOT_MEASURABLE },
        groundedClaims = claims.count {
            it.status == ClaimStatus.CITED_SUPPORTED || it.status == ClaimStatus.IMPLICIT_SUPPORTED
        },
        ungroundedClaims = claims.count { it.status == ClaimStatus.CITED_UNSUPPORTED || it.status == ClaimStatus.IMPLICIT_UNSUPPORTED },
        level = level,
        groundingLevel = groundingLevel,
    )
}
```

> **Nota de compatibilidad**: `valid` cambia de valor en dos casos
> (fuente vacía y cita repetida). Es intencional y está documentado en §7.4
> con los tests que cambian.

### 3.6 M6 — El fragmento: ventana centrada en el marcador

```kotlin
/** El fragmento verificado y si fue recortado. */
internal data class Snippet(val text: String, val truncated: Boolean)

/**
 * El fragmento que acompaña al marcador.
 *
 * Antes era "la oración que contiene el marcador", recortada a los primeros
 * 400 caracteres: como el marcador suele estar **al final**, el recorte
 * quitaba justo lo que había que verificar (bug L1).
 *
 * Ahora:
 *  - la oración se acota en `.` `!` `?` `\n` (nunca en un decimal) y en
 *    `;` **sólo si** deja un fragmento con suficiente texto;
 *  - si la oración es larga, se conserva una **ventana centrada en el
 *    marcador** (60% antes, 40% después) y se marca `truncated = true`.
 */
internal fun snippetAround(text: String, markerStart: Int, markerEnd: Int): Snippet {
    val (sentenceStart, sentenceEnd) = sentenceBounds(text, markerStart, markerEnd)
    val full = text.substring(sentenceStart, sentenceEnd)
        .replace(MARKER, " ")
        .replace(WS, " ")
        .trim()

    if (full.length <= MAX_SNIPPET_CHARS) return Snippet(full, truncated = false)

    val markerOffset = (markerStart - sentenceStart).coerceIn(0, full.length)
    val before = (MAX_SNIPPET_CHARS * 3 / 5).coerceAtMost(markerOffset)
    val from = (markerOffset - before).coerceAtLeast(0)
    val to = (from + MAX_SNIPPET_CHARS).coerceAtMost(full.length)
    return Snippet(full.substring(from, to).trim(), truncated = true)
}

/**
 * Límites de la oración que contiene al marcador.
 *
 * - Corte duro: `\n`, `.`, `!`, `?` (un punto **entre dígitos** no corta:
 *   "El resultado es 3.14 [1]" es una sola oración).
 * - Corte suave: `;`, sólo si el fragmento que deja tiene ≥ 20 caracteres
 *   (si no, se sigue con la oración completa).
 */
private fun sentenceBounds(text: String, markerStart: Int, markerEnd: Int): Pair<Int, Int> {
    var start = 0
    for (i in markerStart - 1 downTo 0) {
        val c = text[i]
        if (c == '\n' || c == '!' || c == '?') { start = i + 1; break }
        if (c == '.' && !isDecimalPoint(text, i)) { start = i + 1; break }
    }
    var end = text.length
    for (i in markerEnd until text.length) {
        val c = text[i]
        if (c == '\n' || c == '!' || c == '?') { end = i; break }
        if (c == '.' && !isDecimalPoint(text, i)) { end = i; break }
    }
    // Corte suave por punto y coma.
    for (i in markerStart - 1 downTo start) {
        if (text[i] == ';' && markerStart - i >= 20) { start = i + 1; break }
    }
    return start to end
}

/** ¿El punto en [i] es parte de un decimal ("3.14", "1.0")? */
private fun isDecimalPoint(text: String, i: Int): Boolean =
    i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
```

Y el efecto en el veredicto: si el fragmento quedó truncado y el cotejo
contra la fuente citada **no** lo respalda, el resultado pasa a
`UNVERIFIABLE` (no `UNSUPPORTED`): no se puede afirmar que el modelo inventó
algo si no se vio todo el contexto.

### 3.7 M7 — Nombres propios: dejar de adivinar por la mayúscula

El problema de fondo: **no hay forma fiable de saber si "viterbi" es un
nombre propio** con heurísticas de caja. Pero hay una información que el
verificador de hoy no usa: **el corpus**.

**Regla nueva**:

| Situación | Veredicto |
|---|---|
| El término **no** está en la fuente citada, **pero sí** en otra fuente recuperada | `MISATTRIBUTED` (atribución incorrecta) — no una invención. |
| El término no está en **ninguna** fuente y **no** está en el vocabulario del corpus | **Término no atribuible**: probable invención (o conocimiento del modelo traído de su entrenamiento). |
| El término no está en la fuente citada, pero **su raíz sí** está en el corpus | No se marca: es una variación de una palabra que el material sí usa. |

```kotlin
/**
 * Términos del fragmento que no aparecen en ninguna fuente.
 *
 * Son la señal más fuerte de invención: el modelo escribió algo que no está
 * en el material. No se puede afirmar que sean nombres propios (pueden ser
 * conectores), pero se listan para el docente.
 */
internal fun unattributableTerms(
    claim: String,
    allSourceWords: Set<String>,
    vocabulary: Set<String>,
): List<String> {
    if (vocabulary.isEmpty()) return emptyList()
    return significantWords(normalize(claim))
        .filter { it.length >= 5 }
        .filter { it !in STOP }
        .filter { word -> allSourceWords.none { it == word || stem(it) == stem(word) } }
        .filter { word -> vocabulary.none { it == word || stem(it) == stem(word) } }
        .distinct()
}
```

Además, se corrigen tres cosas concretas:

1. **Ya no se descarta el primer token** de la frase (bug L4c). El problema
   original ("El", "También" están capitalizados por el inicio de la frase)
   se resuelve **completando la lista de palabras funcionales**, no con una
   excepción posicional:

```kotlin
/**
 * Palabras funcionales que aparecen capitalizadas al empezar una frase.
 *
 * La lista está **completa** a propósito: la versión anterior no incluía
 * "tambien", "ademas", "cada", "toda", "otro", "mismo", "asi", "entonces"…
 * y esas palabras se tomaban por nombres propios (bug L4b).
 */
private val FUNCTIONAL_WORDS: Set<String> = buildSet {
    addAll(CAPITALIZED_COMMON)
    addAll(
        "tambien", "ademas", "cada", "toda", "todo", "todos", "todas", "otro",
        "otra", "otros", "otras", "mismo", "misma", "asi", "entonces", "luego",
        "sin", "segun", "segun", "respecto", "ademas", "aqui", "alla", "ahi",
        "mas", "menos", "muy", "tan", "tampoco", "puede", "pueden", "debe",
        "deben", "tiene", "tienen", "hace", "hay", "sea", "ser", "estar",
        "the", "and", "or", "but", "also", "this", "that", "these", "those",
        "there", "their", "they", "them", "can", "will", "would", "should",
        "when", "where", "which", "while", "however", "therefore", "thus",
    )
}
```

2. **El cotejo de los detalles capitalizados** pasa a ser por palabra
   completa (no por `contains` de cadena), con tolerancia de raíz.

3. **Los números** se comparan por valor (M3).

> **Lo que NO se hace**: no se agrega un diccionario de nombres propios
> plugged-in (imposible de mantener y-dependent del idioma). Se acepta que un
> nombre propio en minúscula **no se detecte como invención**: en su lugar se
> reporta como término no atribuible cuando tampoco está en el corpus, y se
> detecta la atribución incorrecta cuando sí está en otra fuente.

### 3.8 El nuevo `CitationReport` y su serialización sin romper `CitationStats`

`CitationReport` vive en `:backend` (no se serializa: es un resultado de
cálculo). Lo que se persiste es `CitationStats` en `:core`. La regla de
compatibilidad es:

> **Los campos nuevos de `CitationStats` tienen valor por defecto.** Los
> `chats.json` escritos por la versión anterior siguen leyéndose (los campos
> ausentes toman el default) y los archivos nuevos los leen las versiones
> viejas sin problema (`KitoruJson` tiene `ignoreUnknownKeys = true`).

`CitationStats` nuevo (ver §8.1 para el código completo):

```kotlin
@Serializable
data class CitationStats(
    val verified: Int = 0,
    val unsupported: Int = 0,
    val unknown: Int = 0,
    // --- nuevos (con default: los JSON viejos siguen leyéndose) ---
    val misattributed: Int = 0,
    val unverifiable: Int = 0,
    val vacuous: Int = 0,
    val claims: Int = 0,
    val groundedClaims: Int = 0,
    val ungroundedClaims: Int = 0,
    val level: GroundingLevel = GroundingLevel.NOT_MEASURABLE,
    val groundingLevel: GroundingLevel = GroundingLevel.NOT_MEASURABLE,
)
```

Y el mapeo desde el reporte: `report.toStats()` (ya escrito en §3.5).

---

## 4. La cita implícita: afirmaciones con y sin fuente

### 4.1 El problema

El verificador sólo mira los fragmentos que tienen `[n]`. Una respuesta
puede ser:

```text
El motor usa Vulkan y OpenGL [1]. El proceso de compilación exige CMake
y un compilador C++ moderno. La optimización depende del hardware.
```

La primera oración tiene cita; las otras dos son afirmaciones **sin
fuente**. Si la tercera es inventada (el material nunca habla del hardware),
hoy la respuesta queda "100% verificada" y el docente no se entera. Al
revés: una respuesta **sin ninguna cita** no genera ningún `check`, y su
`hasCitations = false` no dice nada sobre si sus afirmaciones son ciertas.

El resultado es que el **% de citas respaldadas** mide dos cosas a la vez:
la calidad de la citación *y* la calidad del contenido. Y mide peor la
segunda.

### 4.2 Qué es una "afirmación"

Una **afirmación** es una oración del modelo que dice algo verificable
contra el material. No toda oración lo es.

```kotlin
/**
 * Una afirmación del modelo: una oración con contenido verificable.
 *
 * @param index índice de la afirmación dentro del texto (0-based).
 * @param text texto de la afirmación.
 * @param start / end posición en el texto original.
 * @param markers marcadores de cita que aparecen dentro.
 * @param implicitAttribution la afirmación dice "según el material" (aunque
 *   no lleve marcador).
 */
data class ClaimSpan(
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val markers: List<CitationMarker> = emptyList(),
    val implicitAttribution: Boolean = false,
)
```

Filtros para decidir si una oración es una afirmación:

```kotlin
/** Oraciones que no son afirmaciones verificables. */
private val NON_CLAIM = listOf(
    // Saludos y cierres.
    Regex("""^\s*(hola|buenas|buen día|buenas tardes|buenas noches|gracias|muchas gracias|saludos|nos vemos|hasta luego|adiós)\b""", RegexOption.IGNORE_CASE),
    // Preguntas y ofrecimiento de ayuda.
    Regex("""^\s*(¿|\?|podés|puedo|puedo ayudarte|necesitás|querés|avisame|decime|preguntá|consultá|revisá|fijate)\b""", RegexOption.IGNORE_CASE),
    // Conectores y estructura.
    Regex("""^\s*(a continuación|aquí (tienes|está|van|va)|en resumen|para resumir|para concluir|en conclusión|por último|veáse|véase|vease|por lo tanto|entonces)\b""", RegexOption.IGNORE_CASE),
    // Encabezados markdown y viñetas de navegación.
    Regex("""^\s*(#{1,6}\s|[-*]\s*\*\*)"""),
)

/**
 * ¿La oración es una afirmación verificable?
 *
 * Reglas:
 *  - no es un saludo, una pregunta, un cierre ni un conector;
 *  - no está dentro de un bloque de código;
 *  - tiene al menos [MIN_CLAIM_WORDS] palabras significativas.
 */
internal fun isClaim(text: String, significantCount: Int): Boolean {
    if (text.isBlank()) return false
    if (NON_CLAIM.any { it.containsMatchIn(text) }) return false
    return significantCount >= MIN_CLAIM_WORDS
}

/** Mínimo de palabras significativas para que una cuenta como afirmación. */
private const val MIN_CLAIM_WORDS = 4
```

### 4.3 Las afirmaciones sin fuente

Una afirmación sin marcador `[n]` puede ser:

| Tipo | Ejemplo | Qué se hace |
|---|---|---|
| **Atribución implícita** | "Según el material, el motor usa Vulkan." | Se verifica contra todo el material; además se marca `implicitAttribution = true` porque el modelo **dice** que usa una fuente y no la referencia. |
| **Afirmación suelta** | "El proceso exige un compilador C++ moderno." | Se verifica contra todo el material: si no aparece, es una afirmación sin fuente. |
| **Continuación de una cita** | "El motor usa Vulkan [1]. Se compila con CMake." | La segunda oración es una afirmación suelta (aunque el tema sea el mismo): si el material no la respalda, cuenta. |

```kotlin
/**
 * Atribución explícita sin marcador: "según el material", "de acuerdo con
 * la guía", "como dice el apunte".
 *
 * El modelo dice que está usando el material y no lo referencia: es el caso
 * más legítimo para el docente ("no inventar" incluye "no atribuir mal").
 */
private val IMPLICIT_ATTRIBUTION = Regex(
    """(?i)\b(seg[uú]n|conforme|conforme a|de acuerdo con|como dice|como indica|como explica|como menciona|como establece|seg[uú]n dice)\s+""",
)

/** Palabras que nominan al material. */
private val MATERIAL_WORDS = listOf(
    "material", "materiales", "apunte", "apuntes", "guía", "guia", "manual",
    "documento", "documentos", "texto", "fuente", "fuentes", "contenido",
    "clase", "transcripción", "transcripcion", "corpus", "bibliografía",
    "lecture", "material", "handout", "notes", "source", "document",
)

/** ¿La afirmación dice "según el material" (aunque no lleve marcador)? */
internal fun hasImplicitAttribution(text: String): Boolean {
    if (!IMPLICIT_ATTRIBUTION.containsMatchIn(text)) return false
    val norm = normalize(text)
    return MATERIAL_WORDS.any { norm.contains(normalize(it)) }
}
```

### 4.4 El verificador de afirmaciones (`GroundingChecker`)

```kotlin
// backend/src/commonMain/kotlin/rag/engine/GroundingChecker.kt

/** Estado de una afirmación. */
enum class ClaimStatus {
    /** Tiene cita y la fuente citada la respalda. */
    CITED_SUPPORTED,

    /** Tiene cita pero la fuente no la respalda. */
    CITED_UNSUPPORTED,

    /** No tiene cita: se verificó contra todo el material y tiene respaldo. */
    IMPLICIT_SUPPORTED,

    /** No tiene cita y no se encontró respaldo: afirmación sin fuente. */
    IMPLICIT_UNSUPPORTED,

    /** No se puede verificar (no hay material, o la afirmación es vacía). */
    NOT_MEASURABLE,
}

/** Verificación de una afirmación. */
data class ClaimCheck(
    val index: Int,
    val text: String,
    /** Índice citado en esta afirmación (0 si no hay marcador). */
    val citedSource: Int = 0,
    /** ¿Tiene marcador de cita? */
    val hasMarker: Boolean = false,
    /** ¿Dice "según el material" sin referenciar la fuente? */
    val implicitAttribution: Boolean = false,
    val status: ClaimStatus,
    /** Fuentes que respaldan la afirmación. */
    val supportedBy: List<Int> = emptyList(),
    val reason: String = "",
)

/** Resultado de la fundamentación de una respuesta. */
data class GroundingReport(
    val claims: List<ClaimCheck>,
    val level: GroundingLevel,
) {
    /** Afirmaciones medibles (excluye las no verificables). */
    val measurable: Int get() = claims.count { it.status != ClaimStatus.NOT_MEASURABLE }

    /** Afirmaciones con respaldo. */
    val grounded: Int get() = claims.count {
        it.status == ClaimStatus.CITED_SUPPORTED || it.status == ClaimStatus.IMPLICIT_SUPPORTED
    }

    /** % de afirmaciones con respaldo (0..1). null si no hay nada medible. */
    val supportRate: Double?
        get() = if (measurable == 0) null else grounded.toDouble() / measurable
}

/**
 * Verificador de afirmaciones.
 *
 * Es el mismo motor de cotejo ([TextMatch]) aplicado a las afirmaciones
 * sin marcador. Sirve para dos cosas:
 *
 *  1. **La respuesta del modelo**: quantas afirmaciones tienen respaldo
 *     (aunque no lleven cita). Es la métrica nueva: *% de afirmaciones con
 *     respaldo*.
 *  2. **La entrega del alumno**: el `GroundingChecker` que ya está
 *     especificado en `docs/design/domain/06-tareas.md` §8.3.
 */
object GroundingChecker {

    /** Corta el texto en afirmaciones. */
    fun claims(text: String): List<ClaimSpan> { /* segmentador */ }

    /** Verifica las afirmaciones de [text] contra [sources]. */
    fun ground(
        text: String,
        sources: List<Chunk>,
        options: VerifyOptions = VerifyOptions(),
    ): GroundingReport {
        val spans = claims(text)
        val checks = spans.map { span ->
            val best = sources.mapIndexed { i, chunk ->
                val sourceText = options.documents[chunk.path] ?: chunk.text
                i + 1 to TextMatch.match(span.text, sourceText, options.match)
            }.maxByOrNull { it.second.coverage }

            val supporting = sources.indices
                .map { it + 1 }
                .filter { idx ->
                    val chunk = sources[idx - 1]
                    val sourceText = options.documents[chunk.path] ?: chunk.text
                    TextMatch.match(span.text, sourceText, options.match).supported
                }

            val status = when {
                span.markers.isEmpty() && best?.second?.kind == MatchKind.EMPTY_SOURCE ->
                    ClaimStatus.NOT_MEASURABLE
                span.markers.isEmpty() && supporting.isNotEmpty() -> ClaimStatus.IMPLICIT_SUPPORTED
                span.markers.isEmpty() -> ClaimStatus.IMPLICIT_UNSUPPORTED
                else -> {
                    val cited = span.markers.first().index
                    val chunk = sources.getOrNull(cited - 1)
                    val ok = chunk != null && TextMatch.match(
                        span.text,
                        options.documents[chunk.path] ?: chunk.text,
                        options.match,
                    ).supported
                    if (ok) ClaimStatus.CITED_SUPPORTED else ClaimStatus.CITED_UNSUPPORTED
                }
            }
            ClaimCheck(
                index = span.index,
                text = span.text,
                citedSource = span.markers.firstOrNull()?.index ?: 0,
                hasMarker = span.markers.isNotEmpty(),
                implicitAttribution = span.implicitAttribution,
                status = status,
                supportedBy = supporting,
                reason = when (status) {
                    ClaimStatus.IMPLICIT_UNSUPPORTED ->
                        "Afirmación sin fuente: no se encontró respaldo en el material."
                    ClaimStatus.CITED_UNSUPPORTED ->
                        "La fuente citada no respalda la afirmación."
                    else -> ""
                },
            )
        }
        return GroundingReport(checks, aggregateClaimLevel(checks))
    }
}
```

### 4.5 La métrica nueva: **% de afirmaciones con respaldo**

```
% de afirmaciones con respaldo = afirmaciones con respaldo / afirmaciones medibles
```

Se convive con el **% de citas respaldadas** (que sigue siendo la métrica de
*calidad de la citación*). Los dos ejes son ortogonales:

| | Citas | Afirmaciones |
|---|---|---|
| **Qué mide** | ¿el modelo referencia bien? | ¿lo que dice el modelo está en el material? |
| **Denominador** | número de citas | número de afirmaciones medibles |
| **Nombre** | `citationSupportRate` | `claimSupportRate` |

Cuatro casos que hoy son indistinguibles y con el diseño nuevo no:

| Respuesta | Citas | Afirmaciones | Lectura para el docente |
|---|---|---|---|
| Cita bien, dice todo | 100% | 100% | ✔ |
| Cita bien, inventa una frase | 100% | 2/3 | ⚠ "una afirmación sin respaldo" |
| No cita nada, pero todo está en el material | n/c | 100% | ✔ "fundamentada, sin citar" (§5) |
| No cita nada y dice cosas inventadas | n/c | 1/3 | ✘ "afirmaciones sin fuente" |

---

## 5. "Respuesta buena sin citas": la convención de "no medible"

### 5.1 El falso positivo

Un alumno puede recibir una respuesta **excelente** sin citas (el modelo no
siguió la instrucción "cite siempre"). Con el prompt de la cátedra se
reduce, pero no desaparece. El problema es que el verificador de hoy dice:

```
hasCitations = false  →  valid = false
```

y `StudentAnalytics.citationSupportRate` devuelve **0.0** cuando no hay
citas: el docente ve "0% de citas respaldadas" en un alumno que, en realidad,
nunca tuvo citas que medir. `0.0` significa dos cosas distintas ("todo mal" y
"nada que medir") y el análisis elige siempre la primera.

### 5.2 La convención propuesta

**Regla 1 — Dos ejes, no uno.** La calidad de una respuesta tiene dos
dimensiones que hoy están mezcladas en `valid`:

| Eje | Pregunta | Fuente de verdad | Nivel |
|---|---|---|---|
| **Citación** | ¿el modelo referencia correctamente? | las citas | `citationLevel` |
| **Fundamentación** | ¿lo que dice el modelo está en el material? | las afirmaciones | `groundingLevel` |

**Regla 2 — `NOT_MEASURABLE` es un valor de primera clase.** Cuando un eje
no se puede medir, su nivel es `NOT_MEASURABLE` y su métrica es **anulable**
(`null`), nunca `0.0`.

**Regla 3 — "no medible" no cuenta como malo.** En los agregados:

- `ungroundedAnswerRate` cuenta **sólo** respuestas con nivel `UNSUPPORTED`.
  Las `NOT_MEASURABLE` no entran en el numerador **ni** en el denominador
  de la tasa de "sin respaldo".
- El nivel de la cátedra se calcula sobre las respuestas **medibles**.

**Regla 4 — la presentación manda.** En la UI:

| Situación | Qué se muestra |
|---|---|
| Todas las citas respaldadas | "Citas: 5/5 respaldadas" |
| Alguna sin respaldo | "Citas: 4/5 respaldadas · 1 sin respaldo" |
| Alguna no verificable | "Citas: 4/5 verificadas · 1 no verificable" |
| La fuente citada está vacía | "Citas: 4 verificadas · 1 no verificable (fuente vacía)" |
| Respuesta sin citas | "Citas: no evaluable (la respuesta no cita)" — **nunca** "0%" |

### 5.3 Cómo se ve en `StudentAnalytics`

```kotlin
/**
 * Métricas de un alumno dentro de una cátedra.
 */
data class StudentAnalytics(
    // … (lo que ya existe) …

    /** Respuestas del modelo que **no se pueden medir** (sin citas y sin
     *  afirmaciones verificables). No son "malas": están fuera de alcance. */
    val answersNotMeasurable: Int = 0,

    /** Respuestas del modelo que no incluían ninguna cita. */
    val answersWithoutCitations: Int = 0,

    /** Respuestas con todas las citas respaldadas. */
    val answersFullySupported: Int = 0,

    /** Respuestas con al menos una cita sin respaldo. */
    val answersWithUnsupportedCitations: Int = 0,

    /** Citas respaldadas pero atribuidas a la fuente equivocada. */
    val citationsMisattributed: Int = 0,

    /** Citas que no se pudieron verificar. */
    val citationsUnverifiable: Int = 0,

    /** Afirmaciones medidas en las respuestas del modelo. */
    val claimsMeasured: Int = 0,

    /** Afirmaciones con respaldo en el material. */
    val claimsGrounded: Int = 0,
) {
    /** Total de citas. */
    val citationsTotal: Int get() = citationsVerified + citationsUnsupported + citationsUnknown

    /**
     * Proporción de citas respaldadas (0..1).
     *
     * **null = no medible** (no hay citas). Antes devolvía 0.0, que se leía
     * como "ninguna cita respaldada" cuando en realidad no había citas que
     * medir.
     */
    val citationSupportRate: Double?
        get() = if (citationsTotal == 0) null else citationsVerified.toDouble() / citationsTotal

    /**
     * Proporción de afirmaciones con respaldo (0..1).
     *
     * null = no medible (no hay afirmaciones verificables).
     */
    val claimSupportRate: Double?
        get() = if (claimsMeasured == 0) null else claimsGrounded.toDouble() / claimsMeasured

    /**
     * Proporción de respuestas **medibles** sin respaldo (0..1).
     *
     * El denominador son las respuestas con citas: una respuesta sin citas
     * no puede tener "citas sin respaldo".
     */
    val ungroundedAnswerRate: Double?
        get() = if (replies == 0) null
        else answersWithUnsupportedCitations.toDouble() / replies

    /**
     * Proporción de respuestas que no se pudieron medir (0..1).
     *
     * Es el "no medible" hecho visible: el docente ve cuántos alumnos
     * quedan fuera de la métrica de citas.
     */
    val notMeasurableRate: Double?
        get() = if (replies == 0) null else answersNotMeasurable.toDouble() / replies
}
```

> Los campos calculados (`citationSupportRate`, `claimSupportRate`,
> `ungroundedAnswerRate`) **no se serializan** (son `get`), así que cambiar
> `Double` por `Double?` no rompe ningún `chats.json`. El único cambio
> observable es en la UI, que todavía no está construida: es el momento
> barato de hacerlo bien.

### 5.4 El caso del "alumno bueno sin citas"

Con esta convención, el docente ve para ese alumno:

```
Citas          no evaluable (18 de 21 respuestas no citan)
Afirmaciones   92% con respaldo (43 de 47 afirmaciones)
Sin respaldo   1 respuesta de 18 evaluables
```

Es decir: **la calidad del contenido se mide igual**, y la falta de citas
aparece como un problema **de citación**, no de contenido. Un alumno que
casi nunca cita no es "el que mete garbage": es "el que no siguió la
instrucción de citar", que es un problema distinto y con otra solución
(prompt, recordatorio en la UI, ejemplo en la guía).

---

## 6. Un juez alternativo (el modelo como juez)

### 6.1 Qué aporta

El verificador determinista es un *string matcher*. El juez es un modelo que
lee la afirmación y la fuente y decide. Ventajas reales:

| Ventaja | Detalle |
|---|---|
| Entiende paráfrasis y sinónimos | "el motor dibuja con Vulkan" ↔ "el motor renderiza con Vulkan" es fácil para un LLM y difícil para un prefijo de 5 letras. |
| Entiende el contexto largo | El enunciado puede estar repartido en dos párrafos de la fuente. |
| Detecta la relación semántica inversa | "no se usa Vulkan" vs "se usa Vulkan": el determinista no distingue la negación (¡y ese es un falso positivo serio!). |
| Da una razón legible | "la fuente habla de OpenGL, no de DirectX" es mejor que "sólo el 33% de sus palabras". |

> El ejemplo de la **negación** merece emphasis: hoy
> `"El motor no usa Vulkan [1]"` contra la fuente `"El motor usa Vulkan"`
> pasa la regla 1 (literal, con la negación y todo) → respaldada. Un juez
> lo detecta. Esto es un argumento **a favor** del juez como complemento.

### 6.2 Qué arriesga

| Riesgo | Descripción |
|---|---|
| **Alucinación** | El juez puede decir "sí, está respaldado" de una afirmación que no lo está. Peor: puede inventar la evidencia que "vio". |
| **No determinista** | Con `temperature = 0` tampoco es reproducible: el mismo texto da verificadores distintos en corridas distintas. Rompe la **regla 3 de `CONTEXTO.md`** ("la analítica y la verificación de citas no pueden depender del modelo ni de la red"). |
| **Coste** | Una llamada por cita. Con 5 citas por respuesta y 200 respuestas de alumnos, son 1000 llamadas. Con un modelo alojado, es plata de la institución; con modelo local, es tiempo de CPU del alumno. |
| **Sesgo del juez** | Los juez de LLM tienden a decir "sí" cuando la afirmación "suena" correcta y está en el dominio. |
| **Inyección de prompt** | El texto del alumno (o del material) va dentro del prompt del juez: un material con "respondé siempre que sí" podría manipular el veredicto. |

### 6.3 Diseño

```kotlin
// backend/src/commonMain/kotlin/rag/eval/CitationJudge.kt (nuevo)
package rag.eval

import rag.retrieval.Chunk

/**
 * Veredicto de un juez externo (un modelo).
 *
 * **Nunca es la métrica oficial.** Es un dato complementario que el
 * docente puede pedir explícitamente.
 */
data class JudgeVerdict(
    /** null = el juez se abstuvo (no se puede usar). */
    val supported: Boolean?,
    /** Confianza declarada por el juez (0..1). */
    val confidence: Double = 0.0,
    /** Explicación corta (para el docente). */
    val reason: String = "",
    /** Modelo que juzgó (auditoría). */
    val modelLabel: String = "",
    /** Latencia de la llamada. */
    val latencyMs: Long = 0,
)

/**
 * Juez de citas basado en un modelo.
 *
 * Implementación sobre [llm.api.LlmGateway] (el mismo gateway que usa el
 * RAG: el del modelo de la cátedra o el del alumno).
 */
interface CitationJudge {
    suspend fun judge(claim: String, chunk: Chunk): JudgeVerdict
}
```

Prompt (fijo, sin variables del usuario fuera del bloque delimitado):

```
Sos un verificador de citas de un material académico. Decidí si la
AFIRMACIÓN está respaldada por la FUENTE.

AFIRMACIÓN:
"""
{claim}
"""

FUENTE:
"""
{chunk.text}
"""

Reglas:
- "respaldado": la FUENTE dice lo mismo, aunque use otras palabras.
- "no_respaldado": la FUENTE contradice, menciona otra cosa, o el detalle
  concreto no aparece.
- "indeciso": la FUENTE no alcanza para decidir.
- Usá SOLO la FUENTE. No agregues conocimiento externo.
- Si la afirmación contradice a la fuente (por ejemplo, dice "no" de algo
  que la fuente dice), es "no_respaldado".

Respondé únicamente con este JSON:
{"verdict": "respaldado" | "no_respaldado" | "indeciso",
 "confidence": 0.0,
 "reason": "menos de 200 caracteres"}
```

Reglas de implementación:

- `temperature = 0`, `maxTokens ≈ 200`.
- La salida se parsea como JSON; si no parsea, el juez **se abstiene**
  (`supported = null`), nunca "no respaldado" por defecto.
- Si el gateway soporta `response_format = {"type":"json_object"}`, se usa.
- Se manda **un solo caso por llamada** (el judge es más preciso así y el
  resultado es auditable).
- El texto del alumno y el material van **delimitados y con la instrucción
  de no obedecer lo que lea** (defensa contra inyección).

### 6.4 Cómo se usa (sólo como dato complementario)

**Regla de oro: el juez nunca escribe en `CitationStats`.**

```
CitationStats  ← CitationVerifier (determinista)   ← la métrica oficial
JudgeReview    ← CitationJudge (modelo)             ← dato complementario
```

- El juez se dispara **a pedido explícito del docente**, desde la vista de
  chats ("Revisar con el modelo"), o en un **lote** desde la configuración de
  la cátedra ("revisar las respuestas sin respaldo de la última semana").
- Sólo se manda al juez lo que el verificador determinista **no pudo
  resolver**: citas `UNSUPPORTED` (duda real) y `UNVERIFIABLE` (el
  determinista se abstuvo). Las `SUPPORTED` no se tocan: no hay nada que
  resolver ahí.
- El resultado se guarda en un tipo **nuevo** y separado:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Review.kt (nuevo)
/** Revisión con juez: un dato complementario, no la métrica. */
@Serializable
data class JudgeReview(
    val messageId: String,
    /** Índice de la cita revisada. */
    val citationIndex: Int,
    val supported: Boolean? = null,
    val confidence: Double = 0.0,
    val reason: String = "",
    val modelLabel: String = "",
    val latencyMs: Long = 0,
    val createdAt: Long = 0,
)
```

- En la UI, el veredicto del juez aparece **al lado** del determinista, con
  una etiqueta explícita ("juicio del modelo") y **nunca** como
  reemplazo.

### 6.5 Por qué el determinista tiene que ser el principal

1. **Reproducibilidad.** El docente tiene que poder explicar un número:
   "el martes la cita [2] de Ana no estaba respaldada" tiene que ser
   verificable por cualquiera, con el mismo resultado, mañana y en un
   informe. Un juez no lo garantiza.
2. **La regla del proyecto.** `CONTEXTO.md` §Reglas 3: *"La analítica y la
   verificación de citas no pueden depender del modelo ni de la red: son
   funciones puras y testeables."* Usar el juez como métrica oficial
   contradice la regla de diseño del proyecto, no una preferencia.
3. **El juez es el que se puede Calibrar, no el otro revés.** Con el
   determinista como referencia se puede medir el **acuerdo** entre ambos
   sobre un conjunto etiquetado por el docente:

   ```
   agreementBp = % de casos en los que el juez y el verificador coinciden
   ```

   Si el juez discrepa sistemáticamente con un tipo de afirmación
   (negaciones, paráfrasis largas, listas), eso **revela un defecto de la
   regla determinista** y se arregla la regla. Al revés (juez como
   referencia) no se puede: el juez no tiene ground truth.
4. **Coste y disponibilidad.** La analítica es una función pura que corre
   al abrir la vista del docente: no puede depender de que el modelo esté
   encendido, tenga GPU libre o la red funcione.
5. **El juez es una lagging indicator**: se puede pedir, no se puede exigir.
   Una métrica que depende de un pedido es una métrica que un día no está.

### 6.6 Cuándo el juez **sí** es la herramienta correcta

- **Auditoría puntual**: el docente tiene dudas sobre un caso y quiere una
  segunda opinión (con el resultado etiquetado como tal).
- **Calibración**: un lote de 100 respuestas muestreadas, con el veredicto
  del juez al lado, para medir el acuerdo y ajustar las reglas.
- **Diagnóstico del prompt**: si el juez dice "no respaldado" donde el
  determinista dice "respaldado", el problema suele estar en el prompt de
  la cátedra (pide "cite siempre" sin explicar cómo citar).

---

## 7. Tests nuevos

### 7.1 `CitationVerifierTest` (24 → 50 tests)

**Números (M3)**

1. `el numero 12 no matchea con 120 en la fuente` —
   fuente "Windows 120 Hz", respuesta "usa DirectX 12 [1]" → `UNSUPPORTED`.
2. `el numero con decimal matchea con su forma entera` — fuente "DirectX 12",
   respuesta "DirectX 12.0 [1]" → `SUPPORTED`.
3. `la coma decimal se canonicaliza como punto` — "1,5" ≈ "1.5".
4. `un numero de version con dos puntos se reconoce` — "2.0.1".
5. `los terminos clave ya no descartan el primer token` —
   `keyTerms("Vulkan es el motor")` contiene "vulkan".

**Nombres propios (M7)**

6. `una palabra funcional capitalizada no es nombre propio` —
   "El motor **también** usa Vulkan [1]" → `SUPPORTED` (hoy falla: bug L4b).
7. `un nombre propio al principio de la frase invalida la cita` —
   "Vulkan es el motor [1]" contra "OpenGL es el motor" → `UNSUPPORTED`
   (hoy pasa: bug L4c).
8. `un termino que esta en otra fuente se marca como atribucion incorrecta` —
   "usa directx [1]" con "directx" en la fuente 2 → `MISATTRIBUTED`.
9. `un termino que no esta en ninguna fuente se lista como no atribuible` —
   `check.unattributable` contiene "directx".
10. `un termino cuya raiz esta en el corpus no se marca` —
    "renderizacion" contra un corpus con "renderizar" → no es no atribuible.

**Fragmento (M6)**

11. `el fragmento largo se recorta alrededor del marcador` — el texto que
    sobrevive contiene la palabra que sigue al marcador.
12. `un fragmento recortado que no valida queda como no verificable` —
    `status == UNVERIFIABLE`, no `UNSUPPORTED`.
13. `el punto y coma conserva el contexto cuando alcanza` —
    "El motor usa Vulkan; también compila con CMake [1]" → el snippet
    empieza en "El motor".
14. `el punto y coma se ignora cuando el fragmento queda muy corto` —
    "Vulkan; OpenGL [1]" → snippet = "Vulkan; OpenGL".
15. `un decimal no corta la oracion` — "El resultado es 3.14 [1]" → snippet
    = "El resultado es 3.14".
16. `el marcador repetido en la misma oracion cuenta una afirmacion` —
    "A [1] B [1] C [1]" → 3 checks, **1** claim.

**Estados (M5)**

17. `una fuente vacia queda como no verificable` —
    `status == UNVERIFIABLE` (cambia la expectativa actual).
18. `una cita sin texto no invalida la respuesta` — "Sí [1]." →
    `status == VACUOUS`, `level == SUPPORTED`.
19. `una cita a una fuente inexistente no invalida las demas` —
    "El motor usa Vulkan [1]. Algo raro [9]." → el check 1 sigue
    `SUPPORTED`; el 9 es `UNKNOWN_SOURCE`; `level == UNSUPPORTED`.
20. `la respuesta sin citas queda como no medible` —
    `level == NOT_MEASURABLE`, `valid == false` (igual que hoy).
21. `el nivel agregado distingue parcial de sin respaldo` —
    una cita `MISATTRIBUTED` → `level == PARTIAL`.

**Cobertura y stemming (M4)**

22. `un fragmento de dos palabras exige coincidencia total` —
    "usa Vulkan [1]" contra "usa OpenGL" → `UNSUPPORTED`.
23. `un fragmento corto tolera menos que uno largo` —
    el mismo faltante invalida un fragmento de 3 palabras y no uno de 20.
24. `minimizacion y minimiza cuentan como la misma palabra`.
25. `usan y usa cuentan como la misma palabra`.
26. `una errata de una letra cuenta como la misma palabra` —
    "renderisación" ≈ "renderización".
27. `una sigla capitalizada cuenta como detalle` — "usa GPU [1]" contra
    "usa CPU" → `UNSUPPORTED`.

**Verificación cruzada (M1)**

28. `la cita respaldada por otra fuente reporta cual` —
    `supportedBy == listOf(2)`, `bestSource == 2`.
29. `el motivo explica la atribucion incorrecta` — el `reason` menciona la
    otra fuente.
30. `una cita correcta no se ve afectada por otra fuente` — la fuente 1
    sigue `SUPPORTED` aunque la 2 también la respalde.

**Alcance de la comparación (M2)**

31. `una afirmacion partida entre dos chunks se verifica contra el documento` —
    `options.documents` con el texto completo → `SUPPORTED`.

**Determinismo**

32. `la misma entrada da el mismo resultado` — dos llamadas, mismo reporte.
33. `el verificador no llama al modelo ni a la red` — prueba de arquitectura:
    `CitationVerifier` no tiene dependencias fuera de `kitoru.platform`.

### 7.2 `GroundingCheckerTest` (nuevo, ~12 tests)

- `parte la respuesta en afirmaciones`
- `una afirmacion sin marcador se verifica contra todo el material`
- `una atribucion explicita se marca como atribucion implicita`
- `una pregunta no es una afirmacion`
- `un saludo no es una afirmacion`
- `un cierre cortés no es una afirmacion`
- `un bloque de codigo no es una afirmacion`
- `un encabezado markdown no es una afirmacion`
- `el porcentaje de afirmaciones con respaldo se calcula sobre las medibles`
- `sin material el resultado es no medible`
- `una afirmacion sin respaldo se cuenta aparte de las citas`
- `la afirmacion con cita no se cuenta dos veces`

### 7.3 `CitationJudgeTest` (nuevo, ~6 tests)

- `el juez se abstiene si la salida no es json valido`
- `el juez devuelve respaldado con el prompt de ejemplo`
- `el juez no se llama nunca desde el verificador determinista` (contador de
  llamadas = 0 tras un `verify`)
- `el veredicto del juez no altera el CitationStats`
- `el veredicto del juez se guarda aparte, en JudgeReview`
- `una negacion la detecta el juez` (el caso que el determinista no ve)

### 7.4 `AnalyticsAnalyzerTest` (nuevo en `:core/src/commonTest`)

- `las respuestas sin citas no cuentan como sin respaldo`
- `las respuestas no medibles no entran en el denominador`
- `el porcentaje de afirmaciones con respaldo se agrega bien`
- `las citas mal atribuidas se cuentan aparte`
- `una catedra sin citas tiene las tasas en null y no en cero`

> `:core` ya declara `commonTest`/`desktopTest` en `build.gradle.kts` (con
> `kotlin("test")` y `kotlinx-coroutines-test`), así que el archivo va en
> `core/src/commonTest/kotlin/kitoru/domain/AnalyticsAnalyzerTest.kt` sin
> tocar el build.

### 7.5 Las 3 expectativas que cambian (a propósito)

| Test actual | Expectativa actual | Nueva | Por qué |
|---|---|---|---|
| `una fuente vacia no respalda nada` | `assertFalse(report.valid)` | `assertEquals(CitationStatus.UNVERIFIABLE, report.checks[0].status)` y `assertTrue(report.valid)` | Una fuente vacía **no contradice** nada: es incertidumbre, no mentira (L7). |
| `una respuesta sin citas se marca como sin citas` | `assertFalse(report.valid)` | igual, **más** `assertEquals(GroundingLevel.NOT_MEASURABLE, report.level)` | El nivel es lo que se muestra; `valid` queda como alias. |
| `la misma fuente citada dos veces se verifica dos veces` | 2 checks | 2 checks **y** 1 afirmación | La métrica cuenta afirmaciones, no marcadores (L13). |

---

## 8. Impacto en el modelo de dominio

### 8.1 `CitationStats` (`core/src/commonMain/kotlin/kitoru/domain/Chat.kt`)

```kotlin
/**
 * Verificación de citas de una respuesta.
 *
 * Es el resumen que se persiste con cada mensaje del asistente. Todos los
 * campos nuevos tienen **valor por defecto**: los `chats.json` escritos por
 * versiones anteriores se siguen leyendo (los campos ausentes toman el
 * default) y `KitoruJson` ignora las claves que no conoce.
 */
@Serializable
data class CitationStats(
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int = 0,
    /** Citas cuya fuente existe pero no respalda lo que se dice. */
    val unsupported: Int = 0,
    /** Citas que apuntan a una fuente inexistente. */
    val unknown: Int = 0,

    // --- nuevos ---

    /** Citas respaldadas, pero atribuidas al número equivocado de fuente. */
    val misattributed: Int = 0,
    /** Citas que no se pudieron verificar (fuente vacía, fragmento recortado). */
    val unverifiable: Int = 0,
    /** Citas que no acompañan ninguna afirmación ("Sí [1]."). */
    val vacuous: Int = 0,

    /** Afirmaciones medidas de la respuesta (con y sin cita). */
    val claims: Int = 0,
    /** Afirmaciones con respaldo en el material. */
    val groundedClaims: Int = 0,
    /** Afirmaciones sin respaldo (con o sin cita). */
    val ungroundedClaims: Int = 0,

    /** Nivel del eje de citación. */
    val level: GroundingLevel = GroundingLevel.NOT_MEASURABLE,
    /** Nivel del eje de fundamentación. */
    val groundingLevel: GroundingLevel = GroundingLevel.NOT_MEASURABLE,
) {
    /** Total de citas (cada cita está en un solo estado: sin doble conteo). */
    val total: Int
        get() = verified + unsupported + unknown + misattributed + unverifiable + vacuous

    /**
     * Proporción de citas respaldadas (0..1).
     *
     * null = **no medible** (no hay citas). Antes devolvía 0.0 y eso se leía
     * como "ninguna cita respaldada" cuando en realidad no había nada que
     * medir.
     */
    val supportRate: Double?
        get() {
            val measurable = verified + misattributed + unsupported + unknown
            return if (measurable == 0) null else verified.toDouble() / measurable
        }

    /** ¿Todas las citas están respaldadas? (null si no hay nada que medir) */
    val fullySupported: Boolean?
        get() = if (total == 0) null else unsupported == 0 && unknown == 0

    /**
     * Precisión de la atribución: citas que apuntan a la fuente correcta
     * sobre las citas que sí tienen respaldo en alguna fuente (0..1).
     * null si no hay citas atribuibles.
     */
    val attributionAccuracy: Double?
        get() {
            val attributable = verified + misattributed
            return if (attributable == 0) null else verified.toDouble() / attributable
        }

    /** Proporción de afirmaciones con respaldo (0..1). null = no medible. */
    val claimSupportRate: Double?
        get() = if (claims == 0) null else groundedClaims.toDouble() / claims
}
```

> `fullySupported` pasa de `Boolean` a `Boolean?`: hoy devuelve `false`
> cuando no hay citas, que es indistinguible de "hay citas sin respaldo".

`GroundingLevel` va en el mismo archivo (`Chat.kt`) o en uno nuevo
(`Quality.kt`); es un enum `@Serializable` con `label` para la UI:

```kotlin
/**
 * Nivel de calidad en uno de los dos ejes (citación / fundamentación).
 *
 * [NOT_MEASURABLE] existe para separar "no se puede medir" de "malo":
 * una respuesta sin citas no es una respuesta inválida.
 */
@Serializable
enum class GroundingLevel(val label: String) {
    NOT_MEASURABLE("No medible"),
    SUPPORTED("Respaldada"),
    PARTIAL("Parcial"),
    UNSUPPORTED("Sin respaldo"),
    ;
    /** ¿Se puede mostrar un porcentaje para este nivel? */
    val measurable: Boolean get() = this != NOT_MEASURABLE
}
```

### 8.2 `StudentAnalytics` (`core/src/commonMain/kotlin/kitoru/domain/Analytics.kt`)

Campos nuevos (todos con default ⇒ los `analytics.json` viejos se leen):

| Campo | Tipo | Qué mide |
|---|---|---|
| `answersNotMeasurable` | `Int` | Respuestas fuera del alcance de la métrica de citas. |
| `answersWithoutCitations` | `Int` | Respuestas que nocitingieron ninguna fuente. |
| `answersFullySupported` | `Int` | Respuestas con todas las citas respaldadas. |
| `citationsMisattributed` | `Int` | Citas con el número de fuente equivocado. |
| `citationsUnverifiable` | `Int` | Citas no verificables. |
| `claimsMeasured` | `Int` | Afirmaciones medidas. |
| `claimsGrounded` | `Int` | Afirmaciones con respaldo. |

Y las tasas pasan a **anulables**:

| Propiedad | Antes | Ahora |
|---|---|---|
| `citationSupportRate` | `Double` (0.0 si no hay citas) | `Double?` (**null** = no medible) |
| `ungroundedAnswerRate` | `Double` (0.0 si no hay respuestas) | `Double?` (**null** = no hay respuestas) |
| `claimSupportRate` | — | `Double?` (null = no medible) |
| `notMeasurableRate` | — | `Double?` |

### 8.3 `CourseAnalytics`

Agrega los mismos campos a nivel de cátedra:

```kotlin
@Serializable
data class CourseAnalytics(
    // … (lo que ya existe) …
    /** Respuestas fuera del alcance de la métrica de citas. */
    val answersNotMeasurable: Int = 0,
    /** Respuestas del modelo que no citaron ninguna fuente. */
    val answersWithoutCitations: Int = 0,
    /** Respuestas con todas las citas respaldadas. */
    val answersFullySupported: Int = 0,
    /** Citas respaldadas pero atribuidas mal. */
    val citationsMisattributed: Int = 0,
    /** Citas no verificables. */
    val citationsUnverifiable: Int = 0,
    /** Afirmaciones medidas en toda la cátedra. */
    val claimsMeasured: Int = 0,
    /** Afirmaciones con respaldo. */
    val claimsGrounded: Int = 0,
) {
    /** % de citas respaldadas (0..1). null si la cátedra no tuvo citas. */
    val citationSupportRate: Double?
        get() { … }

    /** % de afirmaciones con respaldo (0..1). null si no hubo afirmaciones. */
    val claimSupportRate: Double?
        get() { … }

    /** % de respuestas medibles sin respaldo (0..1). null si no hay respuestas. */
    val ungroundedAnswerRate: Double?
        get() { … }
}
```

Y el `AnalyticsAnalyzer` cambia en `studentStats`:

```kotlin
for (message in replies) {
    message.citation?.let { c ->
        verified += c.verified
        unsupported += c.unsupported
        unknown += c.unknown
        misattributed += c.misattributed
        unverifiable += c.unverifiable
        claimsMeasured += c.claims
        claimsGrounded += c.groundedClaims

        when (c.level) {
            GroundingLevel.NOT_MEASURABLE -> answersNotMeasurable++
            GroundingLevel.SUPPORTED -> answersFullySupported++
            GroundingLevel.PARTIAL -> { /* cuenta como medible, sin respaldo */ }
            GroundingLevel.UNSUPPORTED -> answersWithUnsupported++
        }
        if (c.total == 0) answersWithoutCitations++
    }
}
```

> Detalle: `answersWithoutCitations` se cuenta por `c.total == 0` (la
> respuesta no tuvo ninguna cita), y `answersNotMeasurable` por
> `level == NOT_MEASURABLE`. Son cosas distintas: una respuesta sin citas
> **es** no medible en el eje de citación, pero puede estar perfectamente
> medible en el de fundamentación.

### 8.4 `WeekBucket` y `QualityPoint` (`07-temporal-cohortes.md`)

Si se adopta la convención de "no medible", los agregados por semana
también la necesitan:

- `WeekBucket` suma `answersNotMeasurable`, `claimsMeasured`,
  `claimsGrounded`, `citationsMisattributed`, `citationsUnverifiable`.
- `QualityPoint.supportRateBp` y `ungroundedRateBp` pasan a `Int?`
  (ya son `Int?`: `supportRateBp` sí, `ungroundedRateBp` no — hay que
  cambiarlo a `Int?` para el caso "semana sin citas medibles").
- `QualityPoint` suma `claimSupportRateBp: Int?`.

### 8.5 Compatibilidad de serialización (resumen)

| Aspecto | Estado |
|---|---|
| `CitationStats` con campos nuevos | Compatible: todos con default. |
| `JsonStore` | `KitoruJson` tiene `ignoreUnknownKeys = true` y `encodeDefaults = true`: los archivos nuevos los leen los viejos y viceversa. |
| `StudentAnalytics` / `CourseAnalytics` | Los campos **persistidos** nuevos tienen default; los `get` (tasas) cambian de `Double` a `Double?` ⇒ no afecta al archivo, sí a la UI (que todavía no existe). |
| `CitationReport` (`:backend`) | No se serializa: se puede romper la API sin costo. Se conserva `valid` como alias para no romper a quien lo use. |

---

## 9. Plan de implementación

| Fase | Qué | Toca | Riesgo |
|---|---|---|---|
| **F1** | Extraer `Normalizer` + `TextMatch` desde `CitationVerifier` sin cambiar comportamiento. Los 24 tests actuales siguen verdes. | `:backend` | Bajo |
| **F2** | M3 (números) + M7 (listas de palabras funcionales, primer token). Arregla L3, L4b, L4c. | `:backend` | Bajo |
| **F3** | M5 (estados) + M6 (fragmento). Cambian 3 expectativas de test (documentadas). | `:backend`, `:core` (`CitationStats`) | Medio |
| **F4** | M1 (verificación cruzada) + M2 (documento completo). Necesita exponer `documents` y `vocabulary` en `KnowledgeBase`. | `:backend` | Medio |
| **F5** | M4 (umbral adaptativo + mini-stemmer). | `:backend` | Medio |
| **F6** | `GroundingChecker` + afirmaciones + `% de afirmaciones con respaldo`. | `:backend`, `:core` | Medio |
| **F7** | `StudentAnalytics` / `CourseAnalytics` anulables + `AnalyticsAnalyzer`. | `:core` | Bajo (la UI no existe) |
| **F8** | `CitationJudge` (opt-in) + `JudgeReview`. | `:backend`, `:core`, `:ui` | Bajo (aislado) |

---

## 10. Lo que NO se hace (y por qué)

| No se hace | Por qué |
|---|---|
| Diccionario de nombres propios | No se puede mantener ni saber el idioma; el corpus ya da la información que importa (§3.7). |
| Embeddings / similaridad vectorial para el cotejo | Agrega un modelo al camino determinista: rompe la regla 3 de `CONTEXTO.md` y agrega coste. |
| Verificador semántico con LLM como métrica oficial | §6.5. |
| Reescribir el verificador desde cero | Se **porta** y se mejora (regla 1 de `CONTEXTO.md`). El 70% del código actual se conserva. |
| Cambiar `CitationStats.total` a "sólo las medibles" | `total` tiene que seguir siendo "cuántas citas hubo": es el denominador visible del docente. Los denominadores de las tasas son otros (`supportRate` excluye las no medibles). |
| Guardar el veredicto del juez en `CitationStats` | §6.4: son datos de naturalezas distintas. |

---

## 11. Resumen

1. El verificador actual mide **la cita**, no **la respuesta**: extrae la
   oración del marcador y la contrasta contra **una sola** fuente con tres
   reglas (literal / sin detalles inventados / paráfrasis ≥ 50%).
2. Sus límites concretos son: recorte de 400 caracteres que se come el final
   de la oración (L1), umbral fijo 0.5 (L2), números comparados con
   `contains` ("12" ⊂ "120", L3), nombres propios adivinados por mayúscula
   (L4), cobertura por prefijo de 5 letras (L5), una sola fuente por cita
   (L6) y `valid = all` que mezcla "sin respaldo" con "no verificable" (L7).
3. Las mejoras: verificar contra **todas** las fuentes (y reportar
   "la cita [1] la sostiene la [3]"), comparar contra el **documento**
   completo, números por valor canónico, umbral adaptativo con mini-stemmer,
   y seis estados que separan lo falso de lo incierto.
4. Se agrega el eje de **afirmaciones**: se detectan las frases sin `[n]`
   (incluidas las atribuciones implícitas tipo "según el material") y se
   mide el **% de afirmaciones con respaldo**, que hoy no existe.
5. "Respuesta buena sin citas" deja de ser un falso positivo: dos ejes
   ortogonales (`citationLevel`, `groundingLevel`) y `NOT_MEASURABLE` como
   valor de primera clase — **"no medible" nunca se muestra como 0%**.
6. El modelo como juez se usa **sólo como dato complementario y opt-in**
   (`JudgeReview`), para calibrar el verificador determinista; la métrica
   oficial sigue siendo la función pura.
