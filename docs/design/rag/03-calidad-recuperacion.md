# Calidad de la recuperación del RAG

> Documento de diseño · RAG · 03
> Tema: **cómo se mide y cómo se mejora** la calidad de lo que el RAG le pasa al modelo.
>
> Archivos leídos antes de escribir:
> `docs/design/CONTEXTO.md`, `backend/.../rag/retrieval/Bm25Index.kt`,
> `backend/.../rag/retrieval/Chunker.kt`, `backend/.../rag/retrieval/KnowledgeBase.kt`,
> `backend/.../rag/retrieval/Models.kt`, `backend/.../rag/retrieval/DocumentSource.kt`,
> `backend/.../rag/config/RagConfig.kt`, `backend/.../rag/engine/RagEngine.kt`,
> `backend/.../rag/engine/CitationVerifier.kt`, `backend/.../desktopTest/.../Bm25IndexTest.kt`,
> `backend/.../desktopTest/.../CitationVerifierTest.kt`,
> `core/.../kitoru/domain/Chat.kt`, `docs/design/domain/10-taxonomia.md`,
> `C:/Users/gianf/Desktop/Projects/FunshiEngineAI/docs/MANUAL-RAG.md`.

---

## 0. Decisiones (resumen ejecutivo)

| # | Decisión | Motivo |
|---|---|---|
| D1 | La calidad de la recuperación **se mide con un benchmark offline y determinista**, no con opiniones. El juez es `CitationVerifier` (determinista, gratis) más las métricas clásicas (recall@k, MRR, nDCG). | Sin dato no hay tuning: hoy cada cambio de `topK` es una apuesta. |
| D2 | El conjunto de prueba principal es el de las **preguntas reales de los alumnos** (ya están en los chats), no un set ficticio. | Es la distribución real de la demanda; además es gratis y se agranda solo. |
| D3 | La "verdad" se define a nivel de **sección del material** (`materialId` + `headingPath`), no de fragmento. | El fragmento depende del `chunkSize`: si cambia, cambian los ids. La sección es estable y permite comparar configuraciones con troceado distinto. |
| D4 | Se usan **dos truths** complementarios: `G_doc` (escrito por el docente, 10–20 ítems, permite medir *recall* puro sin modelo) y `G_cite` (derivado de las citas verificadas, N grande, permite medir *groundedness* end-to-end). | Cada uno cubre el hueco del otro: el set chico es preciso pero chico; el grande es representativo pero exige al modelo. |
| D5 | La métrica primaria de producción es **Groundedness@K** (% de respuestas con todas las citas respaldadas) y la secundaria **CitationPrecision**. Ambas salen del verificador, sin gastar un token. | Es lo que el alumno percibe ("el modelo me inventó") y lo que el docente puede ver en la analítica. |
| D6 | La pregunta "¿topK=5 alcanza?" se responde con la **curva de cobertura** `P(rank ≤ r)` medida sobre el benchmark, no con un porcentaje del corpus. | El % del corpus no dice nada; la curva dice dónde cae la respuesta. |
| D7 | El tokenizador se parametriza: `TokenPolicy(stopWords = ES+EN, stemming = LIGHT, minLength = 2)`. Se conserva `Bm25Index.tokenize` como API compatible. | La lista de palabras vacías actual es media y mezclada; el español de las preguntas está incompleto. |
| D8 | **Stemmer español ligero** sin dependencias: 3 etapas (plural → verbo → derivación) + ajuste de vocal final, raíz mínima de 4 caracteres, 2 excepciones y 3 plurales irregulares. Se aplica simétricamente a índice y consulta. | Plurals y formas verbales son la mayor fuente de "no match" en español técnico. El ajuste de vocal final es lo que hace converger singular y plural en las dos familias del plural español. |
| D9 | El **reranking por LLM no es el default**: con modelos locales cuesta +5–30 s de latencia y es un reranker peor que BM25 en el rango 5–10. Se implementa (b) como modo opt-in y (b1) PRF determinista como alternativa barata. | El reranking aporta poco cuando el primer recall ya es alto y el set de candidatos es chico. |
| D10 | El filtro por **unidad/tema es un pre-filtro duro con fallback**: si el conjunto filtrado no aporta nada por encima del umbral, se vuelve a buscar sin filtro y se marca la respuesta. | Si el filtro falla (unidad mal asignada), el alumno no se queda sin respuesta. |
| D11 | Se agrega una **puerta de relevancia** (`RelevanceGate`) con tres señales: cobertura de términos informativos de la consulta, score relativo al mejor, y umbral absoluto. Devuelve un veredicto `ANSWERABLE / WEAK / NOT_FOUND`. | "No hay resultados" casi nunca se da: lo que hay son 5 resultados **irrelevantes**. El caso NOT_FOUND es el que produce invenciones. |
| D12 | El caso `NOT_FOUND` **debe decirlo el modelo** ("no encontré ese tema en el material") y el docente recibe un **reporte de material faltante** con los términos más pedidos sin respuesta. | Convierte un bug de recuperación en una lista de tareas para el docente. |

---

## 1. El problema: ¿el modelo encuentra lo que el alumno pregunta?

### 1.1 La aritmética (con supuestos explícitos)

El enunciado dice "5 fragmentos de 600 caracteres son ~1 % del documento". Conviene hacer la cuenta con los parámetros **reales** de hoy (`chunkSize = 1200`, `chunkOverlap = 150`, `topK = 5`), porque la conclusión cambia:

| Magnitud | Valor | Supuesto |
|---|---|---|
| Páginas del PDF | 40 | enunciado |
| Palabras por página | 400–500 | apuntes con diagramas y fórmulas |
| Palabras del documento | ~18 000 | 40 × 450 |
| Caracteres | ~112 000 | ≈6,2 caracteres por palabra con espacio |
| Tokens del corpus | ~37 000 | ≈3,0 caracteres/token en español (el BPE fragmenta más que en inglés) |
| Fragmentos con `chunkSize=1200`, `overlap=150` (paso 1050) | **~107** | 112 000 / 1050 |
| Fragmentos con `chunkSize=600` | ~187 | paso 450 |
| Contexto que hoy entra al modelo (`topK=5`) | 6 000 caracteres ≈ **2 000 tokens** | 5,4 % de los caracteres del documento |
| Ventana de contexto de un modelo local de 7B | 4 096 tokens | el caso habitual en un portátil |

Tres consecuencias que cambian el planteo:

1. **El "1 %" es una cota generosa; el número real es ~5,4 % del texto.** Igual el porcentaje del corpus **no es la métrica**: no dice si lo que el alumno preguntaba estaba entre esos 6 000 caracteres. Un 5 % del documento puede contener o no la respuesta; un 60 % tampoco, si el modelo se confunde con tanto contexto.
2. **`topK` no es un parámetro libre: es un presupuesto de tokens.** Con `chunkSize=1200`, pasar de `topK=5` a `topK=8` son 3 200 tokens de contexto; con 4 096 de ventana quedan ~900 tokens para historial + respuesta: no alcanza. Subir `topK` sin achicar `chunkSize` **no es una opción** en el modelo local por defecto. La palanca real es el par (`chunkSize`, `topK`) con el producto ~constante.
   - Config actual: 1 200 × 5 = 6 000 caracteres.
   - Config equivalente en tokens con más piezas: **700 × 8 = 5 600 caracteres** (8 fragmentos en vez de 5) o 900 × 6.
3. **Con 107 fragmentos y 5 recuperados, el azar da 4,7 %.** BM25 concentra la masa (muchas preguntas van a tener la respuesta en el top-5), pero **ese "mucho" no está medido en ninguna parte**. Ése es el agujero: no es que el sistema funcione mal, es que no lo sabemos.

### 1.2 Lo que hoy no se sabe (y debería saberse)

| Pregunta | Hoy | Con el diseño de este documento |
|---|---|---|
| ¿En qué posición quedó la respuesta? | No se guarda el score ni el rango | `SourceRef.score` + `SourceRef.rank` (§3.7) |
| ¿El modelo tuvo en el contexto lo que necesitaba? | No se mide | `Groundedness@K`, `AnswerCoverage@K` (§3.4) |
| ¿Las respuestas sin respaldo son porRetrieval o por modelo? | Indistinguible | `NO_HITS` vs `UNSUPPORTED` (§3.4) |
| ¿Faltan temas en el material? | No se ve | Reporte de material faltante (§9.4) |
| ¿El ajuste de `topK`/`chunkSize` mejora o empeora? | Opción | Grid search offline con split dev/test (§4.2) |

### 1.3 Tres defectos concretos del diseño actual (con el código)

Se documentan porque **son medibles** y cada uno es una mejora chica con impacto grande:

**H1 — La consulta de recuperación mezcla la pregunta con el turno anterior.**
`RagEngine.prepare`: `kb.retrieve("$question $lastUser", cfg.topK)`, donde `lastUser` es el último mensaje de usuario del historial. En un hilo de 5 turnos, la consulta arrastra los términos de la pregunta anterior. BM25 no distingue pesos por término en la consulta (`tokenize(query).distinct()`), así que un turno viejo introduce ruido de primer orden.
→ *Medición*: `recall@5` con la consulta completa vs. sólo la pregunta actual.
→ *Arreglo*: recuperar con la pregunta actual; el "ancla de tema" (§7.5) se agrega aparte y con peso explícito.

**H2 — No hay forma de distinguir "no hay nada" de "hay algo pero es irrelevante".**
`Bm25Index.search` filtra `score > 0`: cualquier coincidencia de un término produce 5 fragmentos, aunque el mejor sea basura. El caso "sin resultados" casi nunca se da; el caso "5 resultados inútiles" es el frecuente. Y ese es exactamente el caso en el que el modelo **inventa**.
→ *Arreglo*: `RelevanceGate` (§9.2) con veredicto explícito.

**H3 — El verificador tiene una generosidad que infla la "verdad" del benchmark.**
`CitationVerifier.checkSnippet`: si el fragmento citado no tiene palabras significativas (≥4 letras, no vacías), devuelve `true` ("respaldada"). Es el comportamiento que fixa el test portado *"una cita sin texto alrededor cuenta como respaldada"* (`"Sí [1]."`). Está bien para la UI (no queremos marcar ruido), pero **para construir el gold set una cita vacua no es evidencia**.
→ *Arreglo*: el verificador no cambia (los tests portados siguen pasando); el harness de evaluación clasifica esas citas como `UNVERIFIABLE` y las excluye del gold. Se documenta el criterio en el reporte.

### 1.4 La pregunta bien enunciada

> "¿El modelo encuentra lo que el alumno pregunta?"

No tiene una respuesta única: se descompone en tres, y cada una es un número:

1. **¿Estaba en el material?** → `Coverage@K`: % de preguntas para las que el top-K contenía material capaz de sostener la respuesta.
2. **¿Lo encontró?** → `recall@5`, `MRR@5`, `nDCG@5` contra el gold set.
3. **¿Lo usó bien?** → `Groundedness@K`: % de respuestas con todas las citas respaldadas.

Las tres se miden con el mismo benchmark y el mismo juez determinista.

---

## 2. El benchmark

### 2.1 Las tres fuentes de preguntas

| Fuente | N típica | Coste | Qué mide bien | Qué no mide |
|---|---|---|---|---|
| (a) **Docente** | 10–20 | ~2 h de trabajo humano | Recall puro, sin modelo. Es el único set que no depende del modelo en absoluto. | No representa la distribución real de las preguntas. |
| (b) **Automática desde títulos** | 50–200 | ~0 | Cobertura de las secciones; detecta secciones *inalcanzables* por el recuperador. | Mide "el recuperador encuentra su propio título", que es un problema fácil. Sirve como **red de seguridad**, no como métrica principal. |
| (c) **Preguntas reales de los alumnos** | 100–1 000 | ~0 (ya están en los chats) | La distribución real, el vocabulario real, y el verificador de citas como juez. | Depende del modelo (hay que regenerar la respuesta para comparar configs). |

**La opción (c) es la buena**, por tres razones concretas:

1. **El juez es determinista y gratis.** `CitationVerifier.verify(answer, sources)` no llama al modelo ni a la red: la misma entrada da siempre el mismo veredicto. Eso hace que un benchmark con (c) sea **reproducible y gratuito por consultas**, a diferencia de un LLM-as-judge (que costaría tokens y tendría varianza).
2. **El material es el material real.** Un set inventado mide el recuperador contra un corpus inventado; el set real mide contra el corpus que los alumnos realmente usan.
3. **Crece solo.** Cada semana de cursada hay más preguntas y más veredictos. El benchmark no es una tarea de una vez, es un activo que se accumulates.

El set (a) se usa como **control**: si el recall contra (c) sube y contra (a) no, el improvement es un artefacto. El set (b) se usa como **test de cordura**: "toda sección del material debería ser alcanzable por su propio título" (si una sección no lo es, el problema es de indexación, no de ranking).

### 2.2 (a) El set del docente

Formato: un archivo JSON por cátedra, versionado en el repo de la cátedra (o en el directorio de datos), editable desde la UI del docente.

```jsonc
// eval/<courseId>/teacher-set.json
{
  "version": 3,
  "cases": [
    {
      "id": "t-01",
      "question": "¿Qué es la complejidad amortizada y en qué difiere de la peor caso?",
      "expectedAnswer": "La complejidad amortizada es el costo promedio de una secuencia de operaciones; la peor caso es el máximo de una operación individual.",
      "goldSections": [ { "materialId": "m2", "headingPath": ["Unidad 3", "Complejidad"] } ],
      "tags": ["unidad-3", "examen"],
      "answerable": true,          // false = el material NO lo cubre (caso negativo)
      "notes": "Lo pidió el-alumno en el hilo del 12/5"
    }
  ]
}
```

Reglas para el docente:

* 10–20 preguntas es suficiente **para un control**; el error estándar con N=15 sobre una proporción es ±25 pp, así que **no sirve para medir mejoras finas**: sirve para detectar regresiones grandes (un `topK` que rompe todo).
* Cada caso necesita `goldSections` (dónde está la respuesta) o `answerable=false` (la respuesta **no** está en el material: es un caso negativo para medir la honestidad del "no encontré").
* Se puede **semirar** desde el set (c): el docente marca 20 de las preguntas que ya Learon los alumnos y las valida. Es la forma más barata de tener un gold independiente del modelo.

### 2.3 (b) Generación automática desde los títulos

Para cada sección del material (`headingPath` no vacío) se genera:

```
"¿Qué dice la sección «{headingPath.joinToString(" > ")}»?"
```

y opcionalmente dos variantes:

```
"¿De qué trata {último nivel del título}?"
"Resumí el contenido de {material.title}, sección {heading}."
```

Reglas:

* **No se usan para medir ranking** (son consultas con los términos exactos del título: recall@5 ≈ 100 % y no discrimina nada). Se usan para:
  * **detectar secciones inalcanzables**: si el título de una sección no encuentra su propia sección, hay un problema de indexación (guiones, fórmulas, texto en imágenes, encabezados que el `Chunker` no ve).
  * **medir la sensibilidad al `chunkSize`**: si una sección larga se parte en 4 fragmentos, su título matchea cualquiera de los 4 — el índice "funciona" pero la respuesta puede quedar en el fragmento equivocado.
* El conjunto completo se genera en el `sync()` del índice y se cachea; no requiere modelo.

### 2.4 (c) Las preguntas reales de los alumnos: el pipeline

**Origen de los datos.** `ChatThread` + `ChatMessage` (dominio, `core/.../kitoru/domain/Chat.kt`). El docente tiene acceso a los chats según la visibilidad de la inscripción (`ChatVisibility`): `FULL` da texto; `METRICS_ONLY` da estadísticas pero no texto; `NONE` no da nada. **La regla 6 del proyecto ("el docente no ve lo que no debe") se mantiene: el benchmark sólo usa hilos de alumnos cuya inscripción tiene `showsText`.**

**Extracción.** Para cada hilo:

```
para cada mensaje del asistente con rol="assistant":
    pregunta  = el último mensaje "user" anterior a él (en el mismo hilo)
    respuesta = message.text
    citas     = message.citation  (CitationStats: verified / unsupported / unknown)
    if (pregunta es vacía) → descartar
    if (citas == null || citas.total == 0) → marcar como SIN_CITAS (ver §3.4)
    emitir caso { question, answer, citationStats, threadId, authorId, timestamp }
```

**Limpieza (filtros que hay que aplicar sí o sí):**

| Filtro | Motivo |
|---|---|
| Descartar respuestas sin citas | No hay nada que verificar: el modelo respondió de su memoria (o el caso "no encontré", que es un caso negativo valuable → ver abajo). |
| Descartar hilos de menos de 2 mensajes | La pregunta sin contexto no es reconstruible. |
| Marcar y separar respuestas con `unknown > 0` | El modelo Drojó un número de cita inexistente: caso de calidad del modelo, no de la recuperación. |
| Normalizar la pregunta (trim, colapsar espacios, quitar adjuntos) | Evita duplicados por diferencias de espaciado. |
| Deduplicar por (pregunta normalizada, material) | Los alumnos repiten preguntas. |
| Separar por `authorId` | Para que el split no filtre: ver §2.6. |

**Casos negativos automáticos (muy baratos y valiosos).** Una pregunta es *out-of-corpus* si **ninguno de sus términos informativos aparece en el índice** (df = 0 para todos). Eso se calcula de forma determinista contra el índice:

```
términosInformativos(q) = tokenizar(q) - STOP - {términos con df == 0 en el índice}
si términosInformativos(q) es vacío  →  caso negativo ("no hay material para esto")
```

Estos casos miden la honestidad del sistema ("¿sabés decir que no sabés?") sin necesidad de un set escrito a mano: **el corpus mismo genera los negativos**. Un alumno preguntando "¿cuándo es el parcial de física?" sobre una cátedra de Programación es un negativo perfecto.

### 2.5 La "verdad": cómo se construye el gold set con el verificador

Este es el punto central del diseño, así que va formal.

**Entrada**: una respuesta `a` del modelo y la lista de fragmentos recuperados `F = (f_1 … f_K)` que se le pasaron.

**Salida**: `CitationVerifier.verify(a, F)` devuelve un `CitationReport` con una `CitationCheck` por cada aparición de `[n]` en la respuesta:

```
check = (index n, snippet s, supported b, reason)
```

**Regla de construcción del gold (a nivel de sección)**:

```
G_cite(a, F) = { sección(f_n) : ∃ check con check.index = n ∧ check.supported = true }
```

Es decir: **la "verdad" es el conjunto de secciones del material que efectivamente respaldaron la respuesta**. Es determinista, gratis y sale de un juez ya escrito y testeado.

**Por qué a nivel de sección y no de fragmento.** El fragmento depende del `chunkSize`: si se cambia de 1200 a 700, los fragmentos se recomponen y sus ids cambian; un gold a nivel de fragmento haría incomparables las configuraciones. La sección (`materialId` + `headingPath`) es un identificador estable del material:

```kotlin
/** Identificador estable de una sección del material. Sobrevive a los cambios de chunkSize. */
@Serializable
data class SectionKey(
    val materialId: String,
    /** Pila de encabezados, de la raíz a la hoja. */
    val headingPath: List<String> = emptyList(),
) {
    val id: String get() = "$materialId#" + headingPath.joinToString("/")
}
```

**La circularidad y cómo se rompe.** Hay un problema real: si `G_cite` se deriva de las citas del modelo sobre una corrida con `topK=5`, entonces `G_cite ⊆ R(5)` por construcción, y `recall@5` contra ese gold **vale 1 siempre**: no mide nada.

Se rompe con **pooling al estilo TREC**, que es la solución estándar:

1. Se construye **una vez** un *pool* de verdad con una configuración de referencia de alta cobertura:
   `G_pool = ⋃_{i ∈ consultas} G_cite^{(K_ref=20)}(i)` — se corre la recuperación con `K_ref = 20` (muchos más candidatos de los que entran al modelo), se deja que el modelo cite sobre ese contexto amplio, y se combinan las secciones respaldadas.
   Se puede ampliar con `G_doc` (set del docente, independiente del modelo).
2. El pool **se congela** (se versiona junto al corpus) y pasa a ser el gold de todas las configuraciones candidatas.
3. A partir de ahí, `recall@k`, `MRR@k` y `nDCG@k` de cualquier configuración se calculan **contra el pool**, y el resultado es comparable entre `chunkSize`, `topK`, `k1`, `b`, tokenizador, etc.

**El sesgo del pooling y su límite.** El pool sólo contiene secciones que *alguna vez* recuperó la configuración de referencia. Si el `chunkSize` candidato divide una sección en trozos que ninguno de los runners tocó, esa sección no está en el pool y el recall del candidato no puede mejorarla. Por eso el pool **se une con `G_doc`** (gold humano, independiente de toda recuperación) y por eso la métrica end-to-end (§3.4) se reporta siempre junto al recall: si el recall de una configuración sube pero la groundedness baja, la mejora es falsa.

### 2.6 El pipeline de evaluación

Dos modos, con propósitos distintos:

**Modo A — "replay" (sin modelo, segundos).** Para medir **calidad de ranking** (recall@k, MRR, nDCG) de muchas configuraciones:

```
para cada configuración candidata:
    construir el índice (chunkSize, overlap, tokenizador)      ← única parte cara
    para cada consulta q del benchmark:
        R = recuperar(q, k = poolSize)
        scored = R con la configuración (k1, b)
    calcular recall@k / MRR@k / nDCG@k contra G_pool
```

No genera nada: no llama al modelo. Coste: milisegundos por consulta, segundos por reconfiguración del índice. Permite un grid de cientos de puntos.

**Modo B — "regeneración" (con modelo, minutos).** Para medir **calidad end-to-end** (groundedness, precision de citas, honestidad en los negativos):

```
para cada configuración finalista (3–5):
    construir el índice
    para cada consulta q:
        F = recuperar(q, topK)
        contexto = armarContexto(F)
        a = llm.chat(systemPrompt, "CONTEXTO:…\n\nPREGUNTA:…")     ← cacheable por hash
        report = CitationVerifier.verify(a, F)
        G_cite = secciones respaldadas
        acumular métricas
```

El cache: `hash(pregunta + topK + índice + systemPrompt + modelo) → respuesta`. Como el `topK`, el corpus y el prompt están versionados por hash, repetir la evaluación no vuelve a gastar tokens.

**Split y anti-contaminación.** Split **por alumno**, no por pregunta:

```
60 % dev   → se usa para elegir parámetros
20 % test  → se evalúa una sola vez, al final, con la config elegida
20 % hold  → preguntas de alumnos que se ven por primera vez (opcional)
```

 agrupar por `authorId` evita el leakage trivial ("el mismo alumno pregunta lo mismo con dos palabras distintas" ⇒ el set se infla y la mejora medida es falsa).

### 2.7 Reproducibilidad

Cada corrida escribe un reporte autocontenido:

```jsonc
{
  "runId": "eval-2026-05-12T08-00-00Z",
  "courseId": "c1",
  "corpusHash": "sha256:9f2c…",         // hash de (material.id, material.version, extractedText)
  "indexHash": "sha256:44ab…",          // hash de (chunkSize, overlap, tokenizer, chunks)
  "config": { "chunkSize": 700, "chunkOverlap": 175, "topK": 8,
              "k1": 1.5, "b": 0.75, "stopWords": "es+en", "stemming": "light" },
  "model": { "label": "qwen3-4b-instruct", "promptHash": "sha256:71de…" },
  "n": 214,
  "split": "dev",
  "retrieval": {
    "recallAt5": 0.71, "mrrAt5": 0.62, "ndcgAt5": 0.66,
    "coverageCurve": [ {"rank": 1, "p": 0.31}, {"rank": 3, "p": 0.55}, {"rank": 5, "p": 0.71},
                       {"rank": 10, "p": 0.86}, {"rank": 20, "p": 0.93} ]
  },
  "endToEnd": {
    "groundedness": 0.83, "citationPrecision": 0.91, "answerRate": 0.88,
    "abstentionAccuracy": 0.74, "noCitationRate": 0.09
  },
  "byTag": { "unidad-3": { "n": 38, "groundedness": 0.89, "recallAt5": 0.79 } },
  "failures": [ { "question": "…", "verdict": "NO_HITS", "terms": ["backtracking", "amortizada"] } ]
}
```

`corpusHash` + `indexHash` + `config` + `model` ⇒ cualquier número del reporte es reproducible: "el recall@5 de 0,71 con este corpus, este troceado y este modelo" se puede volver a correr meses después.

---

## 3. Métricas de recuperación

### 3.1 Notación

* Consulta `i` con pregunta `q_i`.
* Corpus troceado: `C = {c_1 … c_N}`; `N ≈ 107` para un PDF de 40 páginas con `chunkSize=1200`.
* Recuperación con la configuración `θ`: lista ordenada `R_θ(q_i, k) = (r_1 … r_k)`, `score(r_1) ≥ score(r_2) ≥ …`.
* Gold a nivel de sección: `G_i ⊆ 𝒮` (secciones del material), congelado (§2.5).
* Relevancia binaria: `rel_θ(i, r) = 1` si `sección(r) ∈ G_i`, si no `0`.
* *Rank* de la primera relevante: `rank_θ(i) = min{ j : rel_θ(i, r_j) = 1 }` (∞ si no hay).

### 3.2 Definiciones formales

**Recall@k** (fracción de secciones que contienen la respuesta y aparecieron en el top-k):

```
recall@k(i) = |{ s ∈ G_i : ∃ r ∈ R_θ(q_i, k) con sección(r) = s }| / |G_i|
recall@k    = (1/|Q|) · Σ_i recall@k(i)
```

Se usa la versión **por sección** (no por fragmento) porque el gold es por sección. Es la métrica que responde "¿estaba la respuesta entre los k primeros?".

**MRR@k** (posición del primer fragmento relevante):

```
MRR@k(i) = 1 / rank_θ(i)            si rank_θ(i) ≤ k
         = 0                        si no hay relevante en el top-k
MRR@k    = (1/|Q|) · Σ_i MRR@k(i)
```

**nDCG@k** (ganancia logarítmica, para cuando hay varias secciones relevantes y el orden importa):

```
DCG@k(i)  = Σ_{j=1..k}  (2^rel(i, r_j) − 1) / log₂(j + 1)
IDCG@k(i) = Σ_{j=1..min(k, |G_i|)} 1 / log₂(j + 1)        // gold ideal, todo relevante arriba
nDCG@k(i) = DCG@k(i) / IDCG@k(i)
nDCG@k    = (1/|Q|) · Σ_i nDCG@k(i)
```

Se usa `2^rel − 1` (ganancia exponencial) para castigar más los aciertos abajo que los de arriba; con gold binario y una sola sección relevante, nDCG@5 es una versión suavizada de MRR@5.

**Precisión@k** (complementaria; se reporta pero **no** es la primaria):

```
precision@k(i) = |{ r ∈ R(q_i,k) : sección(r) ∈ G_i }| / k
```

Con `G_i` de 1–2 secciones y `k=8`, la precisión@8 será ~0,15–0,25 siempre: no discrimina. Sirve para el diagnóstico ("el modelo recibió mucho material no relevante").

### 3.3 La verdad como "el modelo respondió con respaldo"

Cuando la verdad es *"el modelo respondió con respaldo"* en vez de *"el fragmento X era el correcto"*, las métricas se redefinen sobre el resultado del verificador. Sea `R_v(a, F)` el `CitationReport` de `CitationVerifier.verify(a, F)`:

| Métrica | Definición | Qué mide |
|---|---|---|
| **Groundedness@K** | `(1/N) · Σ_i 1[ R_v.hasCitations ∧ R_v.valid ]` | La respuesta se apoya **enteramente** en el material. Es la métrica de producción. |
| **CitationPrecision@K** | `(1/N) · Σ_i verified_i / max(1, total_i)` | Proporción de citas apoyadas. Distingue "1 cita inventada entre 5" de "todo inventado". |
| **AnswerCoverage@K** | `(1/N) · Σ_i 1[ G_cite(i) ≠ ∅ ]` | La fracción de preguntas donde **algún** fragmento recuperado respaldó la respuesta. |
| **NoCitationRate** | `(1/N) · Σ_i 1[ !R_v.hasCitations ]` | Respuestas sin citas: el modelo respondió de su memoria (o se abstuvo). |
| **AbstentionAccuracy** |sobre el set negativo: `(1/M) · Σ_j 1[ la respuesta se abstuvo ∧ no afirmó nada con cita ]` | Si el sistema dice "no encontré" cuando no debe. |
| **VacuousSupportRate** | `(1/N) · Σ_i 1[ ∃ check con `significantWords(snippet).isEmpty()` ∧ supported ]` | Citas vacías (§1.3 H3). Se **excluye** del gold y se reporta aparte. |

**Cómo se mapea "respaldado" a las métricas clásicas.** La idea es que `G_cite` (secciones respaldadas) es el "relevante" de la consulta, y `R` es lo recuperado. Entonces:

```
recall@k(i) := |G_cite(i) ∩ secciones(R(q_i,k))| / |G_cite(i)|
```

es decir: **¿el top-k contenía las secciones que respaldaron la respuesta?** Con `G_cite` derivado de una corrida con `K_ref = 20` (§2.5), el recall de una configuración con `k = 5` **sí** discrimina: mide si la configuración Bring the right sections up front.

Y la versión end-to-end de la misma idea:

```
Groundedness@K = (1/N) · Σ_i 1[ la respuesta del modelo, generada con el top-K de la
                            configuración candidata, tiene TODAS sus citas respaldadas ]
```

que es la métrica que decide si un cambio de `chunkSize`/`topK` **realmente** mejora el producto (no sólo el ranking).

### 3.4 La curva de cobertura: la respuesta directa a "¿topK=5 alcanza?"

```
C(r) = (1/N) · Σ_i 1[ rank_θ(i) ≤ r ]        r = 1, 2, 3, 5, 8, 10, 15, 20
```

* `C(1)`: la respuesta estaba en el primer fragmento.
* `C(5)`: la respuesta estaba en el top-5 (con la configuración de hoy).
* `C(8) − C(5)`: **cuánto se ganaría subiendo `topK` a 8**. Si es 3 pp, no vale la pena (el `topK` sube, la latencia y los tokens suben); si es 15 pp, vale la pena ajustando `chunkSize` para mantener el presupuesto.
* `C(20) − C(5)`: el techo alcanzable con el material actual: lo que falta está **más arriba del top-20**, o sea que **no es un problema de ranking sino de cobertura léxica** (la pregunta usa palabras que el material no tiene) → eso es synonymía (§7.1) o material faltante (§9.4).

Esta curva es **el artefacto central** del diseño: con 30 minutos de trabajo y 200 preguntas reales se obtiene el número que responde la pregunta del §1.

### 3.5 Error estándar y tamaño mínimo del benchmark

Para una proporción `p` sobre `N` casos independientes, el intervalo al 95 % es `p ± 1,96·√(p(1−p)/N)`:

| N | p=0,50 | p=0,70 | p=0,85 |
|---|---|---|---|
| 30 | ±17,9 pp | ±16,4 pp | ±12,7 pp |
| 50 | ±13,9 pp | ±12,7 pp | ±9,8 pp |
| 100 | ±9,8 pp | ±9,0 pp | ±6,9 pp |
| 200 | ±6,9 pp | ±6,4 pp | ±4,9 pp |
| 500 | ±4,4 pp | ±4,0 pp | ±3,1 pp |

* El set del docente (10–20) **no puede** medir mejoras de 5 pp: sólo regresiones grandes.
* Con N=200 preguntas reales se detectan diferencias de ~6 pp con 95 % de confianza sin aparear.
* **Apareado** (mismas consultas, dos configuraciones) el ruido baja mucho: la comparación relevante es `McNemar` sobre los pares discordantes. Con ~100 consultas y un 15 % de pares discordantes, se detecta un cambio de ~7 pp.

**Regla operativa:** el benchmark se considera "suficiente" cuando `N ≥ 150` y la diferencia medida supera **2× el error estándar**. Con menos, el resultado se reporta como "no concluyente".

### 3.6 Métricas por segmento (lo que el docente necesita)

No basta con un número global: hay que saber **qué parte de la materia falla**.

```
por unidad (UnidadTree):      n, recall@5, groundedness, abstentionRate
por material:                  idem
por tipo de pregunta:          "definición" / "procedimiento" / "comparación" / "cálculo" /
                              "procedimiento paso a paso" / "referencia a un material"
por momento:                   semana de la cursada
por calidad de la fuente:      material con PDF escaneado (texto pobre) vs. material limpio
```

El último es muy informativo: si el recall del PDF escaneado es la mitad del del `.md`, la conclusión no es "el recuperador es malo" sino "el OCR/extractor de ese material hay que revisarlo" — una acción del docente, no del motor.

### 3.7 Lo que se guarda en el mensaje (para que la analítica pueda calcular esto)

`ChatMessage` ya guarda `sources` (etiquetas) y `citation` (`CitationStats`). Falta lo que permite reconstruir la recuperación:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt — adición
@Serializable
data class SourceRef(
    val index: Int = 0,                 // 1-based: el número de la cita [n]
    val materialId: String = "",
    val materialTitle: String = "",
    val unitIds: List<String> = emptyList(),
    val unitPath: List<String> = emptyList(),
    val heading: String = "",
    val headingPath: List<String> = emptyList(),
    val week: Int = 0,
    val label: String = "",
    val score: Double = 0.0,            // score BM25 final (con boosts)
    val rank: Int = 0,                  // 1..K
    val sectionId: String = "",         // SectionKey.id  → permite el gold por sección
    val chunkId: String = "",           // estable dentro de una indexación
    val coverage: Double = 0.0,         // fracción de términos informativos de la consulta cubiertos
    val verdict: String = "",           // "ANSWERABLE" | "WEAK" | "NOT_FOUND"
)

@Serializable
data class RetrievalStats(
    /** Veredicto de la puerta de relevancia. */
    val verdict: String = "",
    /** Cobertura de términos informativos de la consulta (0..1). */
    val coverage: Double = 0.0,
    /** Score del mejor fragmento y del peor que entró. */
    val bestScore: Double = 0.0,
    val worstScore: Double = 0.0,
    /** Términos informativos de la consulta que no apareció en el corpus. */
    val missingTerms: List<String> = emptyList(),
    /** Consulta efectiva que se mandó al índice. */
    val effectiveQuery: String = "",
)
```

Y en `ChatMessage`: `val sourceRefs: List<SourceRef> = emptyList()` y `val retrieval: RetrievalStats? = null`.

Esto no agrega costo (son unos cientos de bytes por mensaje) y es lo que convierte la analítica de "cuántas citas" en "dónde falla la recuperación".

---

## 4. Los parámetros a ajustar

### 4.1 Estado actual (código)

| Parámetro | Valor | Dónde | Comentario |
|---|---|---|---|
| `k1` | 1.5 | `Bm25Index` (privado, no configurable) | Saturación de frecuencia. |
| `b` | 0.75 | `Bm25Index` (privado) | Normalización por longitud. |
| `chunkSize` | 1200 | `RagConfig` (por cátedra) | Caracteres por fragmento. |
| `chunkOverlap` | 150 | `RagConfig` | 12,5 % del `chunkSize`. |
| `topK` | 5 | `RagConfig` | Fragmentos al contexto. |
| Tokenizador | camelCase + minúsculas + sin acentos + stop ES/EN parcial | `Bm25Index.Companion.tokenize` | Sin stemming. |
| Consulta | `"$question $lastUser"` | `RagEngine.prepare` | Mezcla con el turno anterior (H1). |
| Índice de texto | `"${heading} ${path} ${text}"` | `Bm25Index` init | El título y el path se indexan **una vez extra** ⇒ `tf` artificial +1/+2 para los términos del título. |
| `b` effectively | — | — | El índice no guarda `df` por término para la consulta: `df` sí, pero `avgLen` es global. |

### 4.2 Procedimiento de ajuste sobre el benchmark

**Reglas del procedimiento:**

1. **El corpus y el split se congelan.** Se hace un `corpusHash`; si cambia el material, el benchmark se vuelve a correr desde cero.
2. **Se ajusta por etapas (búsqueda por coordenadas), no en grid completo.** El grid completo de
   `k1(4) × b(4) × chunkSize(4) × overlap(3) × topK(5) × tokenizador(3) × expansión(2) = 5 760` configuraciones
   es innecesario: los parámetros se affects de forma casi ortogonal.
3. **Se mide primero lo barato (ranking) y después lo caro (generación).**
4. **Nunca se ajusta sobre el set de test.** El test se corre una vez, al final.
5. **Se usa un score compuesto**, no un único número: una configuración que gana recall pero pierde groundedness no se adopta.

**Etapa 0 — Instrumentación (previa).**
Sin `SourceRef.score/rank/sectionId` (§3.7) y sin `RetrievalStats`, el benchmark no puede reconstruir qué se recuperó. Es el prerrequisito de todo lo demás.

**Etapa 1 — Ranking puro (segundos).** No se llama al modelo. Se mide `recall@5`, `MRR@5`, `nDCG@5` contra `G_pool`, y la curva `C(r)`.

```
para cada (chunkSize, overlap) en { (1200,150), (900,225), (700,175), (600,150), (500,125) }:
    construir el índice
    para cada (k1, b) en {0.8,1.2,1.5,2.0} × {0.3,0.5,0.75,0.9}:
        para cada tokenizador en {base, +stopES, +stem}:
            evaluar recall@5 / MRR@5 / nDCG@5 con topK fijo = 8
```

Costo: 5 reconstrucciones de índice (cada una ~0,1–1 s para 100–200 fragmentos) × 48 configuraciones de scoring × 200 consultas × O(N) por consulta. Todo el barrido corre en **menos de un minuto** en un portátil. Se puede hacer un grid completo aquí, sin problema: 5 × 48 × 3 = 720 evaluaciones.

**Etapa 2 — Top-K y presupuesto (segundos).** Con el mejor `(chunkSize, overlap, k1, b, tokenizador)`, se barre `k ∈ {3,5,6,8,10,12}` y se elige el **menor** `k` que no pierde recall (criterio: el `k` más pequeño whose `recall@k ≥ recall@k+1 − 1 pp`). La curva `C(r)` da la respuesta directamente.

**Etapa 3 — End-to-end (minutos).** Sólo las **3–5** configuraciones finalistas se evalúan con generación real (§2.6 modo B), midiendo `Groundedness@K`, `CitationPrecision`, `AbstentionAccuracy` y la latencia. Criterio de adopción: *groundedness no baja* y *recall sube*.

**Etapa 4 — Validación (una vez).** La config elegida se corre sobre el set de test. Si el improvement es < 2× el error estándar, se declara "no concluyente" y se busca otra cosa.

**Score compuesto (para elegir, no para reportar):**

```
scoreCompuesto(θ) = 0,5 · recall@5_normalizado
                  + 0,3 · nDCG@5_normalizado
                  + 0,2 · groundedness
```

con los tres normalizados contra la mejor configuración de su etapa. Se usa **sólo** para ordenar candidatos; el reporte muestra las métricas crudas.

### 4.3 Valores recomendados (con el porqué y el efecto esperado)

Los números de "impacto esperado" son **hipótesis a validar** con el benchmark; sedan como hipótesis, no como promesas.

| Parámetro | Hoy | Recomendado | Por qué | Efecto esperado |
|---|---|---|---|---|
| `k1` | 1.5 | **1.5** (exponerlo: 0.8–2.0) | El título y el path ya se indexan una vez extra (`tf` +1/+2), así que los términos de título están sobre-representados. Con `k1=0.8` la penalización por repetición es fuerte y los títulos dominates. `k1=1.5` es el punto medio sano. | 0 (se mantiene); medir 1.2 y 2.0 en la etapa 1. |
| `b` | 0.75 | **0.75** (probar 0.5 y 0.9) | `b=0.75` castiga moderately los fragmentos largos. En material heterogéneo (PDF con tablas → fragmentos de 100 caracteres mezclados con párrafos de 1 200) `b=0.9` es más seguro. | ±1–2 pp de recall según la corpus. |
| `chunkSize` | 1200 | **700–900** (por material, ver abajo) | Con 1 200, la respuesta ("el estimador de mínimos cuadrados es…") queda mezclada con 1 000 caracteres de contexto. Con 700–900 hay 33 % más fragmentos para el mismo presupuesto de tokens y la "densidad de la respuesta" dentro del fragmento sube. | +3–8 pp de recall@5 y +5–15 pp de MRR@5. |
| `chunkOverlap` | 150 (12,5 %) | **25 % del `chunkSize`** (175 con 700, 300 con 1 200) | Una respuesta que cae justo en el corte del fragmento se pierde. Con 25 % de solapamiento, la chance de que una frase de ~50 caracteres quede entera en algún fragmento es ≈1. | +3–8 pp de recall@5 (sobre todo en respuestas de 1–2 frases). |
| `topK` | 5 | **8 con `chunkSize` 700** (o 6 con 900) | Con 700×8 = 5 600 caracteres, el presupuesto es *menor* que el actual (6 000) y entran 8 fragmentos. Subir `topK` con `chunkSize=1200` desborda la ventana de 4 096. | +5–12 pp de recall@5 sin costo de tokens; −0–3 pp de groundedness (más contexto puede confundir al modelo ⇒ hay que medir). |
| Tokenizador | stop ES/EN parcial | **stop ES+EN curado + stemmer ligero** | §5 y §6. | +3–7 pp de recall@5 combinado. |
| Consulta | pregunta + turno anterior | **sólo la pregunta actual** (+ ancla de tema con peso) | H1. | +2–5 pp de recall@5 en hilos multi-turno; 0 en la primera pregunta. |
| Expansión | ninguna | **etiquetas + índice de la cátedra** (peso 0.3) | El documento de taxonomía ya lo propone (`TagVocabulary`, `CourseQueryPlanner`). | +2–5 pp, sobre todo en preguntas con vocabulario de la materia ("¿qué es RL?" → "regresión lineal"). |
| `topK` adaptativo | fijo | **`topK = floor(0,5 · ventanaTokens / tokensPorFragmento)`** | Si la ventana es 8 192, entran 10 fragmentos de 700; si es 4 096, entran 5. La calidad sigue a la capacidad del modelo. | +1–3 pp sin costo de tokens; evita el desbordamiento de contexto. |

**Regla de oro del troceado:** `chunkSize` debería ser **2–4 × la longitud de la respuesta típica** (una definición de cátedra: 200–400 caracteres; un procedimiento paso a paso: 400–800). De ahí la recomendación de 700–900.

**`chunkSize` por material, no por cátedra.** Un PDF de diapositivas (muchos fragmentos cortos) y un apunte de prosa (párrafos largos) quieren valores distintos. El `RagConfig.chunkSize` global debería pasar a ser **un default con override por material**:

```kotlin
// Material (dominio) — campo nuevo
/** Tamaño de fragmento sugerido para este material (0 = usar el de la cátedra). */
val chunkSize: Int = 0,
```

Regla de auto-sugerencia (determinista, en el `sync()`): `chunkSize_material = clamp(2 × longitudMediaDePárrafo, 400, 1 500)`, con la longitud media calculada sobre los párrafos del texto extraído.

**`topK` adaptativo:**

```kotlin
// rag/config/RagConfig.kt — campo nuevo
/** Tamaño de la ventana de contexto del modelo, en tokens (0 = sin ajuste). */
val contextSize: Int = 0,

// rag/retrieval/TopKBudget.kt
object TopKBudget {
    /** Caracteres por token, conservador para español con BPE. */
    const val CHARS_PER_TOKEN = 3.0

    /**
     * Cuántos fragmentos entran al contexto sin desbordar la ventana.
     * Se usa la mitad de la ventana: el resto es historial + respuesta.
     */
    fun topK(contextSize: Int, chunkSize: Int, configured: Int): Int {
        if (contextSize <= 0) return configured
        val tokensPerChunk = (chunkSize / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
        val budget = (contextSize * 0.5).toInt()
        return (budget / tokensPerChunk).coerceIn(1, configured)
    }
}
```

Ejemplos: ventana 4 096 + chunk 700 → `floor(2048/233) = 8`; ventana 8 192 + chunk 700 → `floor(4096/233) = 17` (acotado por `configured`, p. ej. 10); ventana 4 096 + chunk 1 200 → `floor(2048/400) = 5` (el valor de hoy, por eso el sistema "funciona").

### 4.4 Qué NO se toca

| No tocar | Por qué |
|---|---|
| El `stable sort` del ranking | Los empates deben resolverse siempre igual (reproducibilidad del benchmark). |
| El filtro `score > 0` | Un fragmento que no matchea nada no entra. Un boost **multiplica**, no suma (decisión D7 de la taxonomía). |
| La firma `search(query, k)` | Sigue compilando; los parámetros nuevos van con default. |
| `CitationVerifier` | Es el juez del benchmark: cambiarlo cambia la vara de medir. Sólo se le agrega un modo `strict` opcional (§1.3 H3) que **no** cambia el comportamiento por defecto. |

---

## 5. ¿Stop words en español?

### 5.1 Auditoría del estado actual (dato, no opinión)

`Bm25Index.STOP` tiene **32 entradas**: 19 en español (`el, la, los, las, de, del, un, una, y, o, en, que, por, para, con, es, se, como, cual`) y 13 en inglés (`the, an, and, or, of, to, in, is, for, on, with, how, what`).

Corrección al enunciado: **"para", "como" y "cual" sí están**. Lo que falta es otra cosa y es más grave:

| Falta | Frecuencia en preguntas de alumno | Consecuencia |
|---|---|---|
| `cuando`, `donde`, `quien` | muy alta | ruido: aparecen en casi todas las preguntas y en pocos fragmentos ⇒ `idf` bajo ⇒ noIy damage, pero ensucian `avgLen` |
| `cuales` | alta | **el plural no está**: "cuál"→"cual" (stop) pero "cuáles"→"cuales" (no stop) |
| `este/esta/esto/esos/esas/aquel` | alta | ruido |
| `tambien`, `entre`, `sobre`, `desde`, `hasta`, `hacia`, `mediante`, `segun` | media | ruido |
| `todo/toda/todos/todas/cada` | media | ruido |
| `mas`, `muy`, `menos`, `solo`, `ademas`, `aun`, `tampoco` | alta | ruido |
| `porque`, `pero`, `aunque`, `sin`, `bajo`, `durante`, `contra`, `tras` | media | ruido |
| `al`, `lo`, `le`, `les`, `su`, `sus`, `mi`, `mis`, `tu`, `tus`, `ya`, `hay`, `ha`, `he`, `han`, `fue`, `son` | alta | ruido |
| Verbos de cortesía del alumno: `podes`, `podrias`, `quisiera`, `necesito`, `querria`, `explicame`, `explicas`, `decime`, `repasame`, `ayuda`, `favor` | **muy alta** | **ruido con contenido semántico bajo y `idf` inútil** |

Y hay un problema de diseño, no de lista: la lista **mezcla español e inglés en un solo set**. Eso funciona por accidente (el material puede ser bilingüe), pero impide aplicar "sólo español" y hace imposible auditar qué se está sacando.

### 5.2 Por qué importa (y por qué no exagerar)

Dos efectos opuestos, y el error más común es cláusulas el segundo:

* **Sacando de más**: se elimina un término discriminativo y se pierde un acierto. Coste alto.
* **Sacando de menos**: el término queda, pero su `idf` es bajo (aparece en muchos fragmentos) y aporta poco al score. Coste **bajo**, pero no nulo:
  1. infla `avgLen` de forma desigual (los fragmentos con más palabras vacías "cuestan" más);
  2. hace que la consulta tenga términos que matchean cualquier cosa y **suben el score de fragmentos irrelevantes** (el caso "5 resultados basura" del §1.3 H2);
  3. **ensancha la consulta**: hoy `retrieve("$question $lastUser")` multiplica el ruido por dos.

**Regla de diseño:** la lista de palabras vacías debe contener **palabras gramaticales e interrogativas**, y **nunca palabras técnicas**, por frecuente que sean.

### 5.3 La lista correcta en español

Organizada por categoría, con la regla de arriba. Los nombres técnicos que aparecen seguido en una cátedra **no** entran, aunque su frecuencia sea alta: `definicion`, `diferencia`, `formula`, `teorema`, `proposicion`, `demostracion`, `practico`, `parcial`, `examen`, `tema`, `unidad`, `ejemplo`, `concepto`, `propiedad`, `resultado`, `procedimiento`, `ejercicio`.

```kotlin
package rag.retrieval

/**
 * Palabras vacías por idioma.
 *
 * Regla: sólo palabras **gramaticales** o **interrogativas**.
 * Nunca palabras técnicas, por frecuente que sean ("definición" es el
 * contenido de la pregunta, no una palabra vacía).
 */
object StopWords {

    /** Español: artículo, pronombre, preposición, conjunción, adverbio, verbo auxiliar. */
    val ES: Set<String> = setOf(
        // artículos
        "el", "la", "los", "las", "un", "una", "unos", "unas", "lo",
        // pronombres
        "yo", "tu", "tus", "ella", "nosotros", "vos", "ellos", "ellas",
        "usted", "ustedes", "me", "se", "te", "os", "le", "les", "mi", "mis",
        "su", "sus", "nuestro", "nuestra", "este", "esta", "esto", "estos", "estas",
        "ese", "esa", "esos", "esas", "aquel", "aquella", "aquellos", "aquellas",
        "cual", "cuales", "quien", "quienes",
        // determinantes y cuantificadores
        "no", "otra", "otro", "otras", "otros",
        // preposiciones
        "a", "al", "de", "del", "en", "desde", "hasta", "hacia", "para", "por",
        "con", "sin", "sobre", "entre", "contra", "durante", "tras",
        "mediante", "segun", "bajo", "ante",
        // conjunciones
        "y", "e", "o", "u", "ni", "pero", "que", "porque", "aunque", "si", "sino",
        "como", "cuando", "donde", "mientras", "entonces", "luego",
        "ademas", "tambien", "tampoco", "aun", "ya",
        // auxiliares y verbos de apoyo
        "es", "son", "era", "eran", "fue", "fueron", "sea", "sean", "ser",
        "he", "has", "ha", "han", "hemos", "hay",
        "puede", "pueden", "pudo", "debe", "deben", "deberia",
        "tiene", "tienen", "tengo",
        // adverbios y cuantificadores
        "muy", "mas", "menos", "todo", "toda", "todos", "todas", "cada",
        "algo", "nada", "solo", "unicamente", "solamente",
        "asi", "aqui", "ahi", "alla",
        "ahora", "antes", "despues", "todavia",
        "bien", "mal", "mejor", "peor", "mucho", "poco", "bastante",
        "demasiado", "tan", "tanto", "tanta", "tantos", "tantas",
        "cuanto", "cuantos", "cuantas",
        // cortesía y formas coloquiales del alumno
        "gracias", "hola", "buenas", "buenos", "dias", "favor", "ayuda", "ayudan",
        "podrias", "podes", "puedes", "podria", "quisiera", "queria", "querrias",
        "necesito", "necesitaria", "explicame", "explicas", "decime", "dime",
        "repasame", "repasa", "contame", "enserame", "enseriar",
        "consulta", "pregunta", "duda", "ayudame", "sabes",
        // verbos vacíos de intención
        "sirve", "sirven", "significa", "significado", "quiere", "decir",
        "veamos", "hagamos", "mirar", "ver", "pasa",
    )

    /** Inglés (el material puede ser bilingüe). */
    val EN: Set<String> = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by",
        "at", "from", "is", "are", "was", "were", "be", "been", "being", "it",
        "its", "this", "that", "these", "those", "how", "what", "when", "where",
        "which", "who", "why", "do", "does", "did", "can", "could", "would",
        "should", "will", "shall", "may", "might", "must", "there", "their",
        "they", "them", "we", "you", "i", "he", "she", "his", "her", "as", "if",
        "but", "not", "no", "so", "than", "then", "into", "about", "have", "has",
    )

    /** Todas, para el tokenizador por defecto. */
    val ALL: Set<String> = ES + EN
}
```

**Dos reglas de uso:**

1. **Los tokens de longitud 1 se descartan** (`length > 1`, como hoy): "a", "y", "e", "u" desaparecen solos.
2. **Los números de un dígito también** ("3"), pero **"unidad 3"** es un caso especial que el filtro por unidad (§8) resuelve antes de la tokenización: el número llega como metadato, no como término de BM25.

### 5.4 El impacto, cuantificado

Experimento (etapa 1 del §4.2), métrica primaria `recall@5` contra `G_pool`, secundaria "tasa de consultas cuyo mejor fragmento tiene `score < umbral`" (falsos positivos):

| Config | recall@5 | MRR@5 | consultas con match irrelevante |
|---|---|---|---|
| Stop actual (ES parcial + EN parcial) | *línea base* | *línea base* | *línea base* |
| + `StopWords.ALL` | +2 a +4 pp | +1 a +3 pp | **−30 % a −50 %** |
| + stopwords y `minLength = 3` (descarta "dato", "ley", "voz") | −1 a −2 pp | −1 pp | +5 % |

*Expectativa*: el recall sube poco (los términos que sobran tenían `idf` bajo) pero la **calidad de la cola** mejora mucho: el fragmento 4 y 5 dejan de ser relleno. Ese es el efecto que más se nota en el modelo chico: menos contexto irrelevante ⇒ menos invenciones y menos "mezcla" de dos temas.

### 5.5 Dónde vive el tokenizador

```kotlin
package rag.retrieval

/**
 * Política de tokenización de la cátedra.
 */
data class TokenPolicy(
    /** Palabras vacías a descartar. */
    val stopWords: Set<String> = StopWords.ALL,
    /** ¿Aplicar el stemmer ligero? */
    val stemming: Boolean = true,
    /** Longitud mínima de un token (1 = no se filtra nada). */
    val minLength: Int = 2,
) {
    companion object {
        /** Política por defecto de Kitoru. */
        val DEFAULT = TokenPolicy()
    }
}
```

Y `Bm25Index` pasa a recibir la política con default, **sin romper la API**:

```diff
- class Bm25Index(private val chunks: List<Chunk>) {
+ class Bm25Index(
+     private val chunks: List<Chunk>,
+     private val policy: TokenPolicy = TokenPolicy.DEFAULT,
+ ) {
      ...
-     chunks.map { c -> tokenize("${c.heading} ${c.path} ${c.text}").groupingBy { it }.eachCount() }
+     chunks.map { c -> tokenize(c.indexedText, policy).groupingBy { it }.eachCount() }
```

`Bm25Index.tokenize(text)` sigue existiendo con la misma firma (los tests portados siguen pasando); se agrega la sobrecarga `tokenize(text, policy)`.

---

## 6. ¿Stemming?

### 6.1 Por qué el verificador no sirve como stemmer

`CitationVerifier.coveredBy` compara por **prefijo común ≥ 5 letras** con diferencia de longitud ≤ 3. Es una aproximación de raíz *para comparar dos palabras*, no para indexar:

* Es **O(|V|)** por comparación (el `sourceWords.any { … }` recorre el vocabulario entero): indexar 100 000 palabras con eso es inviable.
* No es **idempotente** ni **composicional**: "nación" y "nacional" comparten "naci" (4) ⇒ no matchean; "regresión" y "regresivo" comparten "regres" (5) ⇒ **matchean, y son cosas distintas**.
* Sirve para ser **leniente** al verificar ("renderiza" ≈ "renderizar"). Al indexar hay que ser todo lo contrario: **estricto**, porque un falso positivo en la indexación mete fragmentos irrelevantes en el top-k.

Conclusión: el verificador se queda como está (es un juez, no un indexador) y el tokenizador recibe su propio stemmer, **conservador**.

### 6.2 El stemmer ligero en español

Sin dependencias externas, en `commonMain`, portable. **Cinco reglas que hacen que funcione:**

1. **Etapas en un orden fijo**: plural → verbo → derivación → vocal final. Si se mezclan, singular y plural divergen ("definiciones" → `definicion` pero "definicion" → `defi`) y el stemming no casa nada.
2. **Longitud mínima de raíz: 4** y **palabras de 3 letras o menos intactas**. Con `MIN_STEM = 4`, "dato" no se recorta a "dat".
3. **Nunca se recorta `-cion`/`-ion`**: se *normaliza* el plural ("-ciones" → "-cion") pero la raíz nominal se deja entera. Recortarla rompe la convergencia.
4. **La vocal final tras consonante se quita al final** ("responsable" → "responsabl", "arbole" → "arbol"). Es el paso que hace converger las dos familias del plural español: la de raíz consonante ("arbol**es**") y la de raíz vocal ("responsable**s**"). Sin este paso, una de las dos familias nunca casa con su singular.
5. **Simetría**: la misma función se usa al indexar y al consultar.

```kotlin
package rag.retrieval

/**
 * Stemmer ligero para español.
 *
 * No es Snowball ni Lucene: son reglas de sufijo en tres etapas más un
 * ajuste final, sin dependencias externas, suficientes para casar plurales
 * y formas verbales en material técnico.
 *
 * Reglas:
 *  1. Etapas en orden fijo: plural → verbo → derivación → vocal final.
 *     El orden importa: "formulas" tiene que converger con "formula".
 *  2. La raíz no puede quedar con menos de [MIN_STEM] caracteres.
 *  3. Hay una lista de excepciones y otra de plurales irregulares.
 */
object SpanishStemmer {

    /** Longitud mínima de la raíz. */
    const val MIN_STEM = 4

    /**
     * Palabras cuyo singular termina en "-s": a estas el plural no se les
     * puede quitar mecánicamente ("ingles" → "ingle" sería incorrecto).
     */
    private val EXCEPTIONS: Set<String> = setOf("ingles", "atlas")

    /** Plurales irregulares que las reglas no resuelven. */
    private val IRREGULAR: Map<String, String> = mapOf(
        "indices" to "indice",
        "vertices" to "vertice",
        "apices" to "apice",
    )

    /** Etapa 1 — plural nominal: "-ciones" → "-cion", "-iones" → "-ion". */
    private val NOMINAL: List<Pair<String, String>> = listOf(
        "ciones" to "cion", "iones" to "ion", "uciones" to "ucion",
    )

    /** Etapa 2 — terminaciones verbales. */
    private val VERB: List<Pair<String, String>> = listOf(
        "aremos" to "", "eremos" to "", "iremos" to "",
        "amos" to "", "emos" to "", "imos" to "",
        "aron" to "", "ieron" to "",
        "ando" to "", "iendo" to "",
        "ado" to "", "ada" to "", "ido" to "", "ida" to "",
        "ar" to "", "er" to "", "ir" to "",
    )

    /** Etapa 3 — derivación nominal y adverbios. */
    private val DERIVATIONAL: List<Pair<String, String>> = listOf(
        "amientos" to "", "amiento" to "",
        "imientos" to "", "imiento" to "",
        "amente" to "", "mente" to "",
    )

    /**
     * Terminaciones verbales en "-s": no se les quita la "s" del plural
     * ("calculamos" no es "calculamo").
     */
    private val VERBAL_PLURAL: List<String> = listOf("amos", "emos")

    private const val VOWELS = "aeiou"

    /** Raíz de una palabra (ya normalizada: minúsculas y sin acentos). */
    fun stem(word: String): String {
        if (word.length < MIN_STEM) return word
        if (word in EXCEPTIONS) return word
        var w = IRREGULAR[word] ?: word

        w = dePluralizar(w)
        w = applyStage(w, VERB)
        w = applyStage(w, DERIVATIONAL)

        // Vocal final tras consonante: "responsable" → "responsabl",
        // "arbole" → "arbol". Es lo que hace converger singular y plural.
        for (vocal in listOf('e', 'o')) {
            if (w.length >= 5 && w.endsWith(vocal) && w[w.length - 2] !in VOWELS) {
                w = w.dropLast(1)
            }
        }
        return w
    }

    /** Etapa 1 — el plural se reduce a su singular. */
    private fun dePluralizar(word: String): String {
        for ((suffix, replacement) in NOMINAL) {
            if (!word.endsWith(suffix)) continue
            val base = word.dropLast(suffix.length) + replacement
            if (base.length >= MIN_STEM) return base
        }
        // "-ces" → "-z": "matrices" → "matriz".
        if (word.endsWith("ces")) {
            val base = word.dropLast(3) + "z"
            if (base.length >= MIN_STEM) return base
        }
        // "-s": "arboles" → "arbole" → (ajuste final) → "arbol".
        if (word.endsWith("s") && VERBAL_PLURAL.none { word.endsWith(it) }) {
            val base = word.dropLast(1)
            if (base.length >= MIN_STEM) return base
        }
        return word
    }

    /** Aplica las reglas de una etapa: la primera que encaja, y sólo esa. */
    private fun applyStage(word: String, rules: List<Pair<String, String>>): String {
        for ((suffix, replacement) in rules) {
            if (!word.endsWith(suffix)) continue
            val base = word.dropLast(suffix.length) + replacement
            if (base.length < MIN_STEM) continue
            return base
        }
        return word
    }

    /** Aplica el stemmer a una lista de tokens. */
    fun stemAll(tokens: List<String>): List<String> = tokens.map { stem(it) }
}
```

**Comportamiento verificado** (salida real del algoritmo; con acentos ya quitos por `stripDiacritics`):

| Original | Raíz | Por qué |
|---|---|---|
| `arboles` | `arbol` | plural + vocal final |
| `arbol` | `arbol` | ya es raíz |
| `profesores` | `profesor` | plural + vocal final |
| `clases` | `clas` | plural + vocal final |
| `clase` | `clas` | vocal final ⇒ **converge con el plural** |
| `responsables` | `responsabl` | plural + vocal final |
| `responsable` | `responsabl` | vocal final ⇒ **converge con el plural** |
| `grafos` | `graf` | plural + vocal final |
| `grafo` | `graf` | vocal final |
| `grafico` | `grafic` | ≠ `graf`: son cosas distintas |
| `regresiones` | `regresion` | plural; la raíz **no** se recorta |
| `regresion` | `regresion` | intacta |
| `definiciones` | `definicion` | plural |
| `definicion` | `definicion` | intacta |
| `evaluaciones` | `evaluacion` | plural |
| `informaciones` | `informacion` | plural |
| `calculo` | `calcul` | vocal final (1ª persona del verbo) |
| `calculos` | `calcul` | plural + vocal final |
| `calcular` | `calcul` | "-ar" (etapa 2) |
| `calculado` | `calcul` | "-ado" (etapa 2) |
| `calculamos` | `calcul` | "-amos" (etapa 2) |
| `rapidamente` | `rapid` | "-mente" (etapa 3) |
| `rapido` | `rapid` | vocal final ⇒ **converge con el anterior** |
| `almacenamiento` | `almacen` | "-amiento" (etapa 3) |
| `almacenar` | `almacen` | "-ar" |
| `matrices` | `matriz` | "-ces" → "-z" |
| `matriz` | `matriz` | intacta |
| `indices` | `indic` | irregular + vocal final |
| `indice` | `indic` | vocal final ⇒ **converge con el plural** |
| `series` | `serie` | plural |
| `serie` | `serie` | intacta ⇒ converge |
| `datos` | `dato` | plural |
| `dato` | `dato` | intacta (raíz < 4) ⇒ converge |
| `paises` | `pais` | plural + vocal final |
| `ingleses` | `ingles` | plural + vocal final |
| `ingles` | `ingles` | excepción ⇒ converge |
| `universidades` | `universidad` | plural + vocal final |
| `funciones` | `funcion` | plural |
| `busquedas` | `busqueda` | plural |
| `importantes` | `important` | plural + vocal final |
| `importante` | `important` | vocal final ⇒ converge |
| `resultados` | `result` | plural + "-ado" |
| `resultado` | `result` | "-ado" ⇒ converge |

**Las parejas que hay que mirar siempre** (si dos formas de la misma palabra caen en raíces distintas, el stemming no sirve). Las 65 parejas del test golden tienen que converger:

| Pareja | Raíz de ambas | Estado |
|---|---|---|
| `calculo` / `calculos` / `calcular` / `calculado` / `calculamos` | `calcul` | ✔ |
| `definicion` / `definiciones` | `definicion` | ✔ |
| `arbol` / `arboles` | `arbol` | ✔ |
| `importante` / `importantes` | `important` | ✔ |
| `dato` / `datos` | `dato` | ✔ |
| `indice` / `indices` | `indic` | ✔ |

**Correcciones sobre el borrador inicial del enunciado** (importan porque son las que rompen cosas):

* **`-ar/-er/-ir` no se aplican a cualquier palabra.** "generar" → "gener" está bien; "otro", "secreto", "motor" no terminan en verbo y quedan intactos. La regla sólo se aplica si la raíz queda con ≥4 caracteres.
* **`-ado/-ido` son ambiguos**: "partido" → "part", "dado" → "dado" (raíz de 3 letras < 4 → se conserva). "partido" y "parte" quedan en raíces distintas, pero **no tienen por qué casar**: son palabras distintas.
* **`-ciones`/`-amiento`/`-mente` sí se aplican**, pero `-ciones` se **normaliza** ("-ciones" → "-cion"), no se recorta: "definiciones" → `definicion`, y "definicion" → `definicion`. Si se recortara, las dos divergirían y el stemming no aportaría nada.
* **El plural español tiene dos familias** y ninguna regla morfológica las separa: "árbol**es**" (raíz consonante) y "responsable**s**" (raíz vocal) terminan igual. La regla "quitar '-es'" favorece a la primera y rompe a la segunda; la regla "quitar '-s'" al revés. La salida es quitar **"-s" siempre** y ajustar la **vocal final** al final, que es lo que hace converger a las dos.

**Lo que el stemmer NO resuelve (y hay que decirlo):**

* La 3ª persona del singular ("calcula", "resulta"): "calcula" → `calcula` ≠ `calcul`. Se acepta: es la forma conjugada más difícil y la que más falsos positivos generaría.
* Sufijos derivativos nominales ("analítico" ↔ "análisis"): 3–5 % de las consultas; no vale el riesgo de una regla "-ico" que mezcla familias de palabras.
* Palabras de 3 letras o menos ("red", "fin", "ley"): quedan intactas, y su plural puede divergir ("red" / "redes"). Es el precio de la raíz mínima.
* Falsas fusiones ocasionales ("libre" y "libro" → `libr`; "serie" y "serio" → `seri`). Son pocas y el benchmark mide si el saldo es positivo.

### 6.3 Efecto esperado

En material técnico en español, la diferencia morfológica entre la pregunta y el documento se reparte así (estimación sobre consultas de alumno):

| Fenómeno | Frecuencia en las consultas | Efecto |
|---|---|---|
| Plural vs. singular ("árbol" ↔ "árboles", "matriz" ↔ "matrices") | 25–35 % de las consultas contienen al menos un plural mal casado | **el mayor** |
| Forma verbal ("calcular" ↔ "calcula" ↔ "cálculo") | 10–15 % | alto |
| Sufijo nominal ("evaluación" ↔ "evaluaciones", "definición" ↔ "definiciones") | 10–20 % | alto, y ya lo cubre el plural |
| Sufijos derivativos ("análisis" ↔ "analítico") | 3–5 % | bajo (y es donde el stemming puede equivocarse) |

**Expectativa de mejora:** `recall@5` **+3 a +7 pp**, `MRR@5` +2 a +5 pp. El efecto es mayor en las preguntas de alumnos que en las del docente, porque el alumno escribe en lenguaje colloquial ("pasame los árboles", "como se calcula") y el apunte está en registro formal ("árboles binarios", "cálculo del estimador").

**Riesgos y su mitigación:**

| Riesgo | Mitigación |
|---|---|
| Fusionar palabras distintas ("regresión" vs "regresivo") | No se recorta `-ion` ni `-ivo`; `MIN_STEM = 4`; las fusiones que quedan ("libr" = libre/libro) son pocas y el benchmark mide si el saldo es positivo. |
| Que el plural no case con el singular | Es el riesgo número uno del stemming y el que Justifica el ajuste de vocal final (§6.2): **el test golden comprueba que 65 parejas singular/plural convergen**, y se agrega una pareja nueva cada vez que se rompe una. |
| Sobrecostrar palabras del mismo campo semántico | El stemming no cambia el `idf` de forma apreciable: sólo cambia qué tokens existen. El efecto real es que antes **no había coincidencia** (score 0 para ese término); después la hay. |
| Índice más grande | Cada token único ocupa más: irrelevante a esta escala (100–2 000 fragmentos). |
| Romper los tests portados | `Bm25Index.tokenize` mantiene el comportamiento por defecto **sin stemming** si no se pasa `TokenPolicy`; el stemming se activa explícitamente. Los tests de `Bm25IndexTest` siguen pasando. |

**Plan de prueba:** un archivo golden `backend/src/desktopTest/resources/stems.txt` con 150 pares `palabra → raíz`, más un segundo archivo `stems-convergencia.txt` con 65 parejas singular/plural que deben dar **la misma** raíz. Los dos tests son parametrizados y baratos; son la red que blinda el paso más delicado de todo el diseño.

### 6.4 Veredicto

**Sí, pero después de las stop words y después de tener el benchmark.** Sin benchmark, el stemming es un cambio a ciegas; con benchmark, es un experimento de una línea de configuración.

---

## 7. Reranking sin modelo de embeddings

### 7.1 Las tres opciones, evaluadas

| Opción | Costo | Ganancia esperada | Riesgo | Veredicto |
|---|---|---|---|---|
| **(a) Expansión de la consulta con sinónimos del material** | ~0 (índice en memoria) | +2 a +5 pp de recall | *topic drift* | **Adoptar** (es lo que ya propone la taxonomía: `TagVocabulary`) |
| **(b) Reranking por LLM** (el modelo reordena los 10 fragmentos) | +1 llamada: 4 000 tokens de prefill + ~20 de salida. En `llama.cpp` sobre un portátil: **+5 a +30 s** | +0 a +8 pp de MRR; −2 a 0 pp de groundedness | que el modelo **empeore** el orden | **No por defecto**; experimental y opt-in |
| **(b1) PRF determinista** (relevancia espuria: se re-consulta con los términos del top-3) | ~0 (2.º barrido del índice) | +2 a +5 pp de MRR | *topic drift* | **Adoptar** (es el "reranking" que sí se puede pagar) |
| **(c) Sin reranking** | 0 | línea base | — | Estado actual |

**Por qué (b) no es el default, en números.** El reranking por LLM sólo paga cuando el recall del primer nivel es **alto** (el fragmento correcto está en el pool) **y** el orden es **malo**. Aquí:

* el pool son 5–10 fragmentos, no 100: el margen de mejora del ranking es chico de entrada;
* el modelo local es un **reranker débil**: pedirle "ordená estos 10 fragmentos" a un modelo de 3–7B le da un orden con ruido, y ese ruido **destruye** el ranking determinista de BM25 que sí sabemos medir;
* el costo en latencia es del orden de la latencia total de la respuesta: para el alumno es inaceptable en una app de escritorio, y en Android es inviable.

La regla de decisión: **el reranking por LLM se implementa como un modo experimental con timeout, y sólo se activa si el benchmark muestra que mejora la groundedness** (no sólo el MRR). Si no lo muestra, no se activa nunca.

### 7.2 (a) Expansión con el vocabulario del material

Fuentes de sinónimos **sin modelo**:

1. **Etiquetas libres de materiales y unidades** (`Material.tags`, `Unit.tags`): "graduación" ↔ "degradado". Ya está diseñado en `10-taxonomia.md` (`TagVocabulary`, peso 0,3).
2. **Índice de la cátedra** (los títulos de unidades y temas): si la pregunta dice "¿qué es RL?" y hay una unidad "Regresión lineal", se agregan "regresion" y "lineal" con peso 0,3.
3. **Co-ocurrencia dentro del corpus**: los términos que aparecen siempre en el mismo fragmento se pueden tratar como sinónimos ("mínimos cuadrados" ↔ "OLS" ↔ "regresión lineal"). Se construye una tabla de co-ocurrencia en el `sync()` (determinista, sin modelo) y se usa para expandir con peso 0,2.
4. **Sinónimos explícitos del docente**: un campo `Material.synonyms: List<String>` (o `Unit.aliases`) — el docente escribe "OLS = regresión lineal ordinary least squares". Es la fuente de más calidad y de menor costo: 5 minutos del docente.

**Reglas de la expansión (no negociables):**

* Un término que ya está en la pregunta conserva peso 1,0 (nunca se baja).
* La expansión **no puede** crear el término que hace que un fragmento entre: se aplica con peso < 0,3 y el factor de expansión se multiplica, no se suma.
* Máximo 5 términos expandidos por consulta.
* La expansión **se puede apagar por cátedra** (`RagConfig.expansion: Boolean = true`).

### 7.3 (b1) PRF determinista: el reranking que sí se puede pagar

```
1. R0 = recuperar(q, k = 8)                       # primera pasada, normal
2. términos candidatos = los 5 términos con mayor tf-idf en R0[0..2]
3. q' = q + esos términos con peso 0,15
4. R1 = recuperar(q', k = 8)
5. resultado = R1
```

* Determinista, sin modelo, ~1,3× el costo de una búsqueda (el índice está en memoria: una búsqueda sobre 100–2 000 fragmentos es de microsegundos).
* El riesgo es el **topic drift**: si el fragmento 1 habla de otra cosa, la expansión arrastra al resultado a ese otro tema. Mitigación: peso 0,15 (no más), máximo 5 términos, y **sólo términos con `idf` por encima de la mediana** (o sea, no palabras common).
* Se mide en la etapa 1 del ajuste: es un parámetro más (`prf: Boolean`, `prfWeight: Double`).

**Variante "ancla de tema"** (útil en chats multi-turno y determinista): si la pregunta actual es corta o referencial ("¿y eso?", "¿de la otra?"), se agrega el título de las secciones que la **pregunta anterior** usó como contexto, con peso 0,5. No es un modelo: es una regla.

### 7.4 (b) Reranking por LLM: cómo se implementaría si se decide probarlo

Modo opt-in (`RagConfig.rerank: RerankMode = NONE`), con estas reglas de seguridad:

```
1. Se recuperan K0 = 10 fragmentos (pool más amplio que topK).
2. Se arma el prompt de reranking:
      "Ordená estos fragmentos por relevancia para la pregunta.
       Respondé SOLO con los números, separados por comas.
       PREGUNTA: <pregunta>
       [1] <fragmento>
       ...
       [10] <fragmento>"
3. Salida esperada: "3,7,1,9,2,10,4,5,8,6"
4. Reglas:
   a. Timeout: 3 s (local) / 10 s (remoto). Si expira → se usa el orden de BM25.
   b. Parseo: si la salida no es una permutación válida de 1..10 → se usa el orden de BM25.
   c. Es una PERMUTACIÓN: el rerank NUNCA descarta fragmentos.
      (descartar puede perder la respuesta; reordenar no)
   d. El topK final se toma de la permutación.
   e. Se registra el resultado del rerank en `RetrievalStats` (qué orden propuso,
      cuánto tardó, si hubo fallback) para poder medirlo.
```

El punto (c) es el que más se olvida y el que más daño hace: un reranker que descarta fragmentos convierte un problema de ranking en un problema de cobertura.

### 7.5 El arreglo de fondo: la consulta de recuperación

`RagEngine.prepare` arma `retrieve("$question $lastUser", cfg.topK)`. La corrección no es un parche local: es **usar el planificador que ya propone el diseño de taxonomía** (`10-taxonomia.md`, §7.9), que devuelve la consulta, los pesos por término y el contexto de boost, todo determinista.

```diff
  fun prepare(question: String, history: List<ChatMessage>, useDocs: Boolean): Prepared {
      ...
-         val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()
-         val hits = kb.retrieve("$question $lastUser", cfg.topK)
+         // La consulta de recuperación es la pregunta actual: el turno anterior
+         // se agrega como "ancla de tema" con peso explícito, nunca concatenado.
+         val plan = planner.plan(question, nowMillis())
+         val hits = kb.retrieve(plan.query, cfg.topK, plan.termWeights, plan.boost)
```

El planificador (`CourseQueryPlanner`) ya resuelve, sin modelo:

| Parte de la consulta | Peso | Origen |
|---|---|---|
| La pregunta actual | 1,0 | texto del alumno |
| El **ancla de tema** (los títulos de las secciones que la respuesta anterior usó), si la pregunta es referencial | 0,5 | historial + `SourceRef` |
| La expansión de vocabulario de la cátedra (etiquetas, sinónimos del docente, índice de unidades) | 0,3 | `TagVocabulary` + `Material.synonyms` |

El "ancla de tema" es una adición al planificador y se define así:

```kotlin
package rag.engine

import llm.config.ChatMessage
import kitoru.domain.SourceRef

/**
 * Ancla de tema para las preguntas de seguimiento.
 *
 * Si el alumno escribe "y eso?" o "¿de la otra?", la pregunta por sí sola
 * no tiene términos: sin ancla, la recuperación no encuentra nada.
 *
 * Determinista: se toma de las fuentes que la respuesta anterior usó.
 */
object TopicAnchor {

    /** ¿La pregunta es referencial? */
    private val REFERENTIAL = Regex(
        """^(y|e|o)?\s*(eso|ese|esta|este|esto|el otro|la otra|lo otro|por que|mas|seguimos|entonces)\b""",
    )

    /** ¿La pregunta depende del turno anterior? */
    fun isReferential(question: String): Boolean =
        REFERENTIAL.containsMatchIn(question.trim().lowercase())

    /**
     * Títulos de las secciones usadas por las últimas respuestas del hilo.
     * @param history historial del hilo (el más reciente al final).
     */
    fun lastCitedSections(history: List<ChatMessage>): List<String> {
        val refs = history.asReversed()
            .filter { it.role == "assistant" }
            .flatMap { it.sourceRefs }
            .mapNotNull { it.headingPath.lastOrNull() ?: it.heading.ifBlank { null } }
        return refs.distinct().take(2)
    }
}
```

El detalle importante: la concatenación con pesos exige que `Bm25Index.search` acepte `termWeights` (que la taxonomía ya propone) — así que este arreglo depende de ese cambio, no es independiente. Y `CourseQueryPlanner` es el owner de la construcción de la consulta: este documento no agrega un segundo planificador, le agrega el ancla de tema.

---

## 8. Recuperación por unidad/tema

### 8.1 El diseño

Si el alumno pregunta *"¿de la unidad 3?"*, el filtro por unidad **antes de puntuar** reduce el conjunto de candidatos de ~107 a ~15. Es la mejora de recall más grande y más barata del catálogo: el efecto no es "un poco mejor", es que la pregunta pasa de "buscar en 107 fragmentos" a "buscar en 15".

**Pre-filtro duro, con fallback.** No boost: filtro. Motivo: si el alumno **dice** "de la unidad 3", el material de la unidad 5 es ruido, no una alternativa. Pero con fallback, porque la asignación de materiales a unidades puede estar mal.

```
consulta → detectar unidades (determinista) → conjunto de candidatos C
  ├─ si C no está vacío:
  │    hits = recuperar(C, k = topK)
  │    si hits tiene ≥1 fragmento por encima del umbral:
  │        → responder con hits, verdict = ANSWERABLE | WEAK
  │    si no:
  │        → hay dos salidas posibles:
  │            (i)  la unidad no tiene material  → verdict = UNIT_EMPTY
  │            (ii) el filtro era incorrecto → fallback sin filtro, verdict = FALLBACK
  └─ si C está vacío (no se detectó ninguna unidad):
       hits = recuperar(todo, k = topK)   → camino normal
```

La distinción entre (i) y (ii) es importante: (i) es un **hallazgo para el docente** ("la unidad 3 no tiene material"), (ii) es una señal de que la taxonomía necesita un ajuste.

### 8.2 La detección (determinista)

Se apoya en `UnitTree.search` y `HeadingKey.containsPhrase` (del documento de taxonomía), más un detector de referencias numéricas:

```kotlin
package rag.engine

/**
 * Detecta a qué unidades se refiere una pregunta.
 *
 * Reglas:
 *  - "unidad 3", "u3", "tema 2.1", "la unidad de modelos lineales"
 *    → referencias explícitas: alta confianza.
 *  - Un número suelto ("3") NO cuenta: matchearía media cátedra.
 *  - Si se menciona un Tema, se incluyen su unidad padre y sus descendientes.
 */
object UnitReference {
    private val RE_EXPLICIT = Regex("""\b(unidad|unidades|u|tema|temas|apartado|parcial)\s*([0-9]+(?:\.[0-9]+)?)""")
    private val RE_BY_NAME = Regex("""\b(?:de|del|en|sobre)\s+(?:la\s+|el\s+)?(.+?)(?:\s+de\s+la|\s+del|\s+en\s+la|\?|$)""")

    fun detect(question: String, units: List<Unit>): Set<String> { … }
}
```

Reglas de seguridad:

* **El número explícito manda**: "unidad 3" ⇒ `Unit.number == "3"`. La coincidencia por nombre ("la unidad de modelos lineales") usa `HeadingKey.containsPhrase` sobre `Unit.fullLabel`.
* **Un número suelto no cuenta.** "la pregunta 3" no es una unidad.
* **Se incluyen los descendientes**: si el alumno dice "unidad 2", entran también "Tema 2.1" y "Tema 2.2".
* **Se incluyen los hermanos de la misma unidad padre** si la pregunta menciona un tema específico: "tema 2.1" ⇒ sólo el 2.1 (no todo el 2). El filtro es lo más específico que se puede inferir.
* Si la pregunta menciona **dos** unidades ("compara la unidad 2 y la 3"), el filtro es la **unión** de las dos.

### 8.3 La implementación en el índice

```diff
  class Bm25Index(chunks, policy) {
-     fun search(query: String, k: Int, termWeights: Map<String, Double> = emptyMap()): List<Pair<Chunk, Double>>
+     fun search(
+         query: String,
+         k: Int,
+         termWeights: Map<String, Double> = emptyMap(),
+         /** Filtro de candidatos (pre-filtrado por unidad). */
+         candidate: (Chunk) -> Boolean = { true },
+     ): List<Pair<Chunk, Double>>
```

Dentro del scoring, los fragmentos que no pasan el filtro se saltan. **`df`, `avgLen` y `idf` siguen siendo globales** (no se recalculan sobre el subconjunto): es una aproximación conocida y aceptable — el score sigue siendo comparable entre consultas, que es lo que importa para el benchmark. Documentarlo en el KDoc.

Coste: el filtro es un `if` por fragmento. Con 107–2 000 fragmentos es irrelevante.

### 8.4 Efecto esperado y cómo se mide

| Tipo de pregunta | recall@5 sin filtro | recall@5 con filtro | Por qué |
|---|---|---|---|
| Con unidad explícita ("de la unidad 3") | 0,55–0,70 | **0,80–0,92** | el conjunto relevante se reduce 5–10× |
| Sin unidad | 0,65–0,75 | 0,65–0,75 (sin cambio) | el filtro no aplica |
| Con nombre de tema ("regresión") | 0,60–0,75 | **0,72–0,85** | |

La métrica de control es la **tasa de fallback** (preguntas con unidad detectada donde el filtro no dio resultado y hubo que buscar sin filtro). Si es > 10 %, el problema es de taxonomía (materiales sin unidad asignada), no de recuperación. Ese número se muestra al docente: "el 18 % de las preguntas sobre 'unidad 3' no encuentran material: la Unidad 3 no tiene materiales asignados".

---

## 9. Cuando no hay nada relevante

### 9.1 El caso real

`RagEngine.prepare` hoy pone `"(sin resultados en el material de la cátedra)"` cuando `hits.isEmpty()`. Pero `hits` está vacío **sólo** si ningún término de la consulta matchea algo. En la práctica:

| Situación | Probabilidad | Qué ve el alumno |
|---|---|---|
| La pregunta es del material, con vocabulario compartido | 40–60 % | respuesta buena |
| La pregunta es del material, con otra terminología ("RL" vs "regresión lineal") | 15–25 % | respuesta parcial o inventada |
| La pregunta es del material pero el material no está subido | 10–20 % | **invención** (el modelo contesta con su memoria) |
| La pregunta no es de la cátedra ("¿me hacés la tarea?") | 5–15 % | **invención** |

El caso "sin resultados" literal es raro. **El caso peligroso es el inverso: 5 resultados irrelevantes que el modelo interpreta como contexto y contesta inventando.**

### 9.2 La puerta de relevancia (`RelevanceGate`)

Tres señales, todas deterministas:

```
señal 1 — COBERTURA de términos informativos
    términosInformativos(q) = tokens de la consulta, sin stop words, con idf > umbral
    cobertura(i) = |términosInformativos(q) ∩ tokens(i)| / |términosInformativos(q)|
    → el mejor fragmento debe cubrir ≥ 0,5 (o ≥ 0,34 si hay ≥3 términos)

señal 2 — SCORE RELATIVO
    score(i) / score(1) ≥ 0,35   → el fragmento está en la misma zona de score
    (descarta la cola: el 4.º y 5.º fragmentos que matchearon un solo término)

señal 3 — SCORE ABSOLUTO (relativo al corpus)
    score(1) ≥ umbralRelativoALCorpus
    (el IDF normalizado: score(1) / idfMedio; si el mejor match es un término
     presente en la mitad del corpus, no es evidencia de nada)
```

Veredicto:

```kotlin
package rag.retrieval

/**
 * Veredicto de la puerta de relevancia.
 */
enum class RelevanceVerdict {
    /** Hay material que sostiene la respuesta. */
    ANSWERABLE,
    /** Hay material, pero flojo: cobertura parcial. El modelo debe avisar. */
    WEAK,
    /** No hay material que responda la pregunta. */
    NOT_FOUND,
    /** La unidad preguntada no tiene material. */
    UNIT_EMPTY,
    /** El filtro por unidad no dio resultado y se buscó sin filtro (§8). */
    FALLBACK,
}
```

Códigos de salida (nombres en español, como manda la regla 4):

```
ANSWERABLE  → "[1] (Unidad 2 > …)\n…"
WEAK        → "[1] (Unidad 2 > …)\n…\n\n---\n\n(El material disponible cubre sólo parte de la pregunta.)"
NOT_FOUND   → "(no se encontró material de la cátedra que responda la pregunta)"
UNIT_EMPTY  → "(la unidad 3 no tiene material cargado en la cátedra)"
FALLBACK    → como ANSWERABLE, pero la respuesta se marca en la UI:
              "La búsqueda se hizo sobre todo el material, no sólo sobre la unidad 3."
```

### 9.3 Qué tiene que hacer el modelo

El prompt del sistema de la cátedra (que edita el docente) tiene que incluir la instrucción explícita. **Texto propuesto para el prompt por defecto de la cátedra:**

```
Respondé EXCLUSIVAMENTE con la información del CONTEXTO.
Si el CONTEXTO no contiene la respuesta, decilo explícitamente:
"En el material de la cátedra no encontré información sobre <tema>".
No completes con conocimiento propio ni con suposiciones.
Si el CONTEXTO cubre sólo una parte de la pregunta, decilo y contestá sólo esa parte.
Cada afirmación que tome del CONTEXTO va entre corchetes con el número de la
fuente: [1], [2]. Si no usás ninguna fuente, no pongas corchetes.
```

Y la app, del lado del cliente, fuerza la forma del CONTEXTO según el veredicto (§9.2). Esto **no** contradice la regla de que el prompt es del docente: es la parte que la app controla (el armazón del contexto), no el texto.

### 9.4 El valor para el docente: la agenda de material faltante

Este es el producto más valioso de todo el diseño. Cada consulta que termina en `NOT_FOUND` o `WEAK` **es una tarea para el docente**: "los alumnos me preguntan X y no tengo X en el material".

```
Reporte de material faltante (por cátedra, por período)

Términos más pedidos sin material:
  1. backtracking ...................... 23 consultas  (última: 12/05)  [Unidad 3]
  2. complejidad amortizada ........... 17 consultas  (última: 10/05)  [Unidad 3]
  3. np-dureza ........................ 11 consultas  (última: 08/05)  [Unidad 5]
  4. normalización .....................  9 consultas  (última: 03/05)
  5. gradiente descendente .............  8 consultas  (última: 28/04)

Consultas con material parcial (WEAK): 14% del total
Unidades sin material: Unidad 6 (0 consultas asignadas)
```

Cada línea es accionable: el docente sube el material o escribe el tema. **Es el puente entre la analítica y la acción**, y es lo que justifica todo el apparatus de medición.

Implementación: `RetrievalStats.missingTerms` (los términos informativos de la consulta con `df == 0` en el índice) ya se guarda por mensaje (§3.7); el agregado es una función pura sobre los mensajes:

```kotlin
package kitoru.domain

/**
 * Términos que los alumnos piden y el material de la cátedra no tiene.
 *
 * Se arma desde [ChatMessage.retrieval]: los términos informativos de la
 * consulta que no aparecen en el índice (df == 0).
 */
data class MissingMaterialReport(
    /** Término normalizado. */
    val term: String,
    /** Cuántas consultas lo pidieron. */
    val queries: Int,
    /** Última vez que se preguntó. */
    val lastAskedAt: Long,
    /** Ejemplos de preguntas (para que el docente entienda el contexto). */
    val samples: List<String>,
    /** Unidad que el alumno mencionó, si la mencionó. */
    val unitIds: List<String> = emptyList(),
)
```

### 9.5 Las métricas del caso negativo

Con el set de negativos (§2.4, los casos whose informative terms have `df == 0` en el índice):

| Métrica | Definición | Objetivo |
|---|---|---|
| `AbstentionRate` | % de consultas negativas cuya respuesta se abstiene (sin citas afirmativas + dice "no encontré") | **> 80 %** |
| `FalseAnswerRate` | % de consultas negativas cuya respuesta **afirma** algo con cita | **< 10 %** |
| `AbstentionPrecision` | de las respuestas que se abstienen, % que eran realmente consultas negativas | > 85 % |

Una respuesta que se abstiene es detectada de forma determinista: `!report.hasCitations` **o** el texto matchea un patrón de abstención:

```kotlin
package rag.engine

/**
 * Detecta si la respuesta se abstiene.
 *
 * Determinista: mira las citas y el texto. No hace falta un modelo.
 */
object AbstentionDetector {
    private val PATTERNS = listOf(
        "no encontre", "no hay informacion", "no aparece en el material",
        "no esta en el material", "no tengo", "no hay material", "no lo encontre",
        "el material no", "no puedo", "no surge", "no lo dice",
    )

    fun abstained(answer: String, report: CitationReport): Boolean {
        if (!report.hasCitations) return true
        val norm = CitationVerifier.normalize(answer)
        return PATTERNS.any { norm.contains(it) }
    }
}
```

---

## 10. Plan de mejora priorizado

**Orden por (impacto ÷ esfuerzo) y por dependencia.** El impacto esperado es una **hipótesis**; cada línea dice con qué métrica se valida. Nada se adopta sin pasar el benchmark (§4.2).

### P0 — Sin esto no se puede medir nada (habilitante)

| # | Mejora | Esfuerzo | Métrica de validación | Impacto esperado |
|---|---|---|---|---|
| **P0.1** | **Instrumentar la recuperación**: guardar `SourceRef.score/rank/sectionId/chunkId/coverage` y `RetrievalStats` en cada mensaje | S (1–2 días) | — (habilita todo lo demás) | Habilitante. Sin esto no hay curva de cobertura ni reporte de faltantes. |
| **P0.2** | **Harness de evaluación offline**: `EvalCase`, `EvalSet`, `RetrievalBenchmark` (modos A y B), `RetrievalMetrics` (recall@k, MRR, nDCG, groundedness, abstención), `BenchmarkReport` + tests | M (3–5 días) | — | Convierte opiniones en datos. Entrega el número que responde "¿topK=5 alcanza?". |
| **P0.3** | **Tokenizador paramétrico**: `TokenPolicy`, `StopWords.ES` completo (199 palabras en español + 69 en inglés), mantener `Bm25Index.tokenize` compatible | S (0,5–1 día) | recall@5, nº de consultas con match irrelevante | +2–4 pp recall; **−30–50 %** de consultas con fragmentos irrelevantes en el top-5. |
| **P0.4** | **Puerta de relevancia** (`RelevanceGate`): cobertura + score relativo + veredicto, con los 4 modos de contexto | M (2–3 días) | `AbstentionRate`, `FalseAnswerRate` | Abstención correcta > 80 %; **−10–20 pp de invenciones** en preguntas fuera de alcance. |
| **P0.5** | **"No encontré" explícito** en el prompt del sistema de la cátedra + `AbstentionDetector` | S (0,5 día) | `AbstentionRate` | Convierte el silencio del modelo en un mensaje accionable. |
| **P0.6** | **Reporte de material faltante** (agregado de `missingTerms`) en la analítica del docente | M (1–2 días) | nº de consultas NOT_FOUND agregadas | **Es el entregable de mayor valor para el docente**: la lista de qué subir. |
| **P0.7** | **Filtro por unidad con fallback** (`UnitReference` + filtro en `Bm25Index.search`) | M (1–2 días) | recall@5 en preguntas con unidad | **+15–30 pp** en preguntas con unidad explícita; +5–12 pp con nombre de tema. |
| **P0.8** | **Corregir la consulta de recuperación**: usar sólo la pregunta actual (H1) + ancla de tema con peso | S (0,5–1 día) | recall@5 en hilos multi-turno | +2–5 pp en chats de más de un turno. |

### P1 — Con el benchmark yamidiendo

| # | Mejora | Esfuerzo | Métrica | Impacto esperado |
|---|---|---|---|---|
| **P1.1** | **Troceado**: solapamiento 25 % del `chunkSize`; `chunkSize` por material (auto-sugerido por longitud media de párrafo) | M (2 días) | recall@5 (a nivel de sección) | +3–8 pp recall; +5–15 pp MRR@5. |
| **P1.2** | **Stemmer ligero español** (§6) + golden test de 150 pares | M (1–2 días) | recall@5 | +3–7 pp recall; +2–5 pp MRR@5. |
| **P1.3** | **`topK` adaptativo** a la ventana de contexto (`TopKBudget`) | S (0,5 día) | groundedness vs. latencia | +1–3 pp sin costo de tokens; evita desbordes de contexto. |
| **P1.4** | **Expansión de consulta**: etiquetas de la cátedra + índice de unidades + sinónimos del docente (`Material.synonyms`) | M (2 días) | recall@5 | +2–5 pp; +8–15 pp en preguntas con abreviaturas ("¿qué es RL?"). |
| **P1.5** | **PRF determinista** (relevancia espuria, peso 0,15) | M (1 día) | MRR@5 | +2–5 pp MRR. |
| **P1.6** | **Ajustar `k1`/`b`/`topK`** con el grid del §4.2 y fijar los defaults por cátedra | M (1 día + corrida) | score compuesto | Produce **el número que hoy no existe**. El ajuste en sí puede resultar ±0, y eso también es información: significa que el problema no estaba en los parámetros. |

### P2 — Experimental, sólo si el benchmark lo pide

| # | Mejora | Esfuerzo | Métrica | Impacto esperado |
|---|---|---|---|---|
| **P2.1** | **Reranking por LLM** (opt-in, timeout, permutación, fallback) | L (3–5 días) | groundedness **y** MRR | +0–8 pp MRR, **−2–0 pp groundedness**, +5–30 s de latencia. **No es default** salvo que el benchmark diga lo contrario. |
| **P2.2** | **Re-embeddings / vectorstore** | XL | — | Fuera de alcance: contradice "sin dependencias, sin GPU" y el target Android. |
| **P2.3** | **UI de ajuste para el docente**: mostrar la curva de cobertura y el recall@5 por unidad, y permitir mover `topK`/`chunkSize` viendo el efecto | M (3 días) | — | Convierte el ajuste en algo visible y no en algo que hay que deducir leyendo el código. |
| **P2.4** | **Conjunto de preguntas por unidad** generado automáticamente desde los títulos (§2.3) | S | recall@5 por sección | Detecta secciones inalcanzables: "la sección 'Sockets' no aparece en ningún top-5". |

### 10.1 El plan mínimo viable (2 semanas)

Si sólo se puede hacer una tanda corta, **P0.1 + P0.2 + P0.3 + P0.7**:
dan el número que falta (¿el recuperador encuentra lo que el alumno pide?),_STATEMENTS mejoran el tokenizador y traen el filtro por unidad, que es la mejora de mayor impacto individual.

---

## 11. Archivos, tipos y tests que este diseño implica

```
backend/src/commonMain/kotlin/rag/eval/
    EvalCase.kt              // caso de benchmark (pregunta, gold, origen, tags)
    EvalSet.kt               // carga/serialización del set (JSON)
    RetrievalMetrics.kt      // recall@k, MRR@k, nDCG@k, coverage curve, groundedness
    RetrievalBenchmark.kt    // modo A (ranking) y modo B (end-to-end)
    BenchmarkReport.kt       // reporte serializable (§2.7)
backend/src/commonMain/kotlin/rag/retrieval/
    TokenPolicy.kt           // stop words + stemming + minLength
    StopWords.kt             // StopWords.ES / StopWords.EN
    SpanishStemmer.kt        // stemmer ligero (§6.2)
    RelevanceGate.kt         // veredicto + señales (§9.2)
    TopKBudget.kt            // topK adaptativo (§4.3)
    TopicAnchor.kt           // ancla de tema para preguntas de seguimiento (§7.5)
    SectionKey.kt            // identificador estable de sección
core/src/commonMain/kotlin/kitoru/domain/
    SourceRef.kt             // + score, rank, sectionId, chunkId, coverage, verdict
    RetrievalStats.kt        // verdict, coverage, missingTerms, effectiveQuery
    MissingMaterialReport.kt // reporte de material faltante (§9.4)
```

> `CourseQueryPlanner` (la consulta efectiva con pesos) **no** se crea en este documento: lo define `10-taxonomia.md` §7.9. Acá sólo se le agrega el ancla de tema (`TopicAnchor`).

Tests (`backend/src/desktopTest/kotlin/rag/`):

| Test | Qué verifica |
|---|---|
| `StopWordsTest` | Las palabras clave del español están; las técnicas **no** están; `tokenize` no las elimina. |
| `SpanishStemmerTest` | 150 pares golden; casos límite (`MIN_STEM`, excepciones, plural irregular). |
| `RetrievalMetricsTest` | recall@k / MRR / nDCG contra casos calculados a mano; curva de cobertura monótona. |
| `RelevanceGateTest` | Los 4 veredictos con entradas escritas a mano. |
| `BenchmarkRunnerTest` | Un benchmark end-to-end chico (3 documentos, 5 preguntas) con un gateway falso. |
| `Bm25IndexTest` (existente) | Sigue pasando sin cambios. |
| `CitationVerifierTest` (existente) | Sigue pasando sin cambios (el verificador no se toca). |

---

## 12. Riesgos y límites (por honestidad)

| Riesgo | Mitigación |
|---|---|
| **El benchmark se sobreajusta** (se elige la config que funciona para estas 200 preguntas) | Split dev/test por alumno; el set del docente como control; documentar el `corpusHash` de cada reporte. |
| **La calidad de la cita no es la calidad de la recuperación** | Se reportan las dos familias de métricas por separado (§3.3) y la regla de adopción exige que la groundedness no baje. |
| **El verificador es leniente** (§1.3 H3) | Se clasifican las citas vacías como `UNVERIFIABLE` en el harness; no entran al gold; se reporta `VacuousSupportRate`. |
| **El modelo local cambia entre corridas** | `model` + `promptHash` en el reporte; el modo A (ranking) no depende del modelo y es el que se usa para el grid. |
| **El costo del modo B** | Cache por hash de (pregunta, topK, índice, prompt, modelo); sólo 3–5 configuraciones por tanda. |
| **El filtro por unidad corta material válido** | Fallback obligatorio + métrica de tasa de fallback visible para el docente. |
| **El stemming fusiona palabras** | `MIN_STEM = 4`, ajuste de vocal final, 2 excepciones y 3 plurales irregulares; golden test de 150 pares + 65 parejas de convergencia singular/plural; y se mide el delta en el benchmark (si no ayuda, se apaga). |
| **El "no encontré" se usa como excusa** | Se mide `AbstentionPrecision` (de las que se abstienen, cuántas eran realmente negativo): si el modelo se abstiene de más, se ve en el número. |
| **La analítica del docente ve datos de alumnos** | Sólo hilos con `ChatVisibility.FULL`; el reporte de material faltante guarda **términos y ejemplos de preguntas**, no texto completo, salvo que la cátedra lo habilite. |

---

## 13. Resumen en 5 líneas

1. **Hoy no se mide nada**: `topK=5` sobre ~107 fragmentos de un PDF de 40 páginas es una apuesta; el número que falta es `recall@5` y `groundedness`, y salen de `CitationVerifier` sin gastar un token.
2. **El benchmark se arma con las preguntas reales de los alumnos** (500+ gratis), la verdad se define por **sección** (estable ante cambios de `chunkSize`) y se mide con recall@k / MRR / nDCG + `Groundedness@K`; con N=200 se detectan diferencias de ~6 pp.
3. **Los parámetros**: mantener `k1=1.5` y `b=0.75`, pero pasar a `chunkSize≈700–900` con `overlap=25 %` y `topK=8` (mismo presupuesto de tokens, 8 fragmentos en vez de 5) y `topK` adaptativo a la ventana del modelo.
4. **Tokenizador**: lista de palabras vacías en español completa (199; hoy hay 19 mezcladas con 13 inglesas) + **stemmer ligero** sin dependencias (3 etapas + ajuste de vocal final, raíz ≥4, 65 parejas singular/plural verificadas) → +2–4 pp y +3–7 pp respectivamente; el reranking por LLM **no** es default (cuesta +5–30 s y es peor reranker que BM25 en un pool de 10).
5. **Prioridades**: P0 instrumentación + harness + stop words + filtro por unidad (recall +15–30 pp en preguntas con unidad) y, en paralelo, la **puerta de relevancia** con "no encontré" explícito, que convierte el fallo en el **reporte de material faltante** — la lista de qué subir a la cátedra.
