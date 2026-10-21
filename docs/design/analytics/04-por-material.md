# 04 — Analítica por material (docente)

> Qué material de la cátedra **funciona** y cuál no: cuánto se consulta, cuántas
> respuestas se apoyan en él, cuáles nunca se tocan, cuáles confunden al modelo
> y cuáles están mal troceados.
>
> Depende de: `docs/design/CONTEXTO.md`, el dominio `kitoru.domain`
> (`Course.kt` → `Material`, `Analytics.kt` → `SourceUsage`,
> `AnalyticsAnalyzer.kt`), el RAG ya portado (`rag.retrieval.Chunker`,
> `rag.retrieval.Chunk`, `rag.retrieval.CourseMaterialSource`,
> `rag.engine.CitationVerifier`) y la pantalla de analítica del docente
> (`docs/design/teacher/01-navegacion.md` → `AnalyticsPane`).
>
> Este doc **no** toca los chats de los alumnos: sólo agrega una pestaña
> "Materiales" dentro de la analítica y el enlace con
> `docs/design/teacher/07-chats.md` (filtro por material ya previsto).

---

## 0. Decisiones en una línea

| # | Decisión | Motivo |
|---|---|---|
| D1 | El identificador del material viaja **dentro de la cita** (`SourceRef.materialId`, `CitationDetail.materialId`), no se deduce del título. | Hoy la cita dice `"cátedra: Apuntes-01.pdf"` (el `Chunk.path` es el **título**). Si el docente renombra el material, la historia queda huérfana; y dos materiales con el mismo título se confunden. |
| D2 | La atribución de un fallo es **de un solo culpable por respuesta** (escalera de 6 peldaños, §5). | Una respuesta cita 5 fuentes: si el fallo se atribuye a todas, ningún material queda limpio y la tabla no dice nada. |
| D3 | La tasa que ordena la tabla es la **ajustada por encogimiento** (`adjustedSupportRate`, §4.3), no la cruda. | Con 1 cita, un material puede tener 0 % y ensuciar el diagnóstico. El ajuste lo sube a la media de la cátedra y deja el problema real arriba. |
| D4 | La tasa de respaldo de la cátedra es **micro** (citas respaldadas / citas totales); el promedio por material se muestra sólo como nota al pie. | Un material con 300 citas no puede hablar por 7 materiales con 1 cita. |
| D5 | Las métricas de calidad se **calculan al vuelo** desde los mensajes; **no** se guardan contadores en `Material`. | Un contador persistido no se puede "descontar" cuando un alumno cambia su visibilidad a `NONE`, ni cuando se renombra o divide un material. Regla 3 del proyecto: la analítica es una función pura. |
| D6 | Lo que **sí** se persiste en `Material` es el estado del material y el **diagnóstico de indexación** (`usage`, `indexStats`), porque describen el material, no la conversación. | El diagnóstico tiene que verse aunque el texto se haya editado después, y tiene que cambiar cuando se reindexa. |
| D7 | `Material.usage` distingue **CITABLE / REFERENCE / ARCHIVED**. Un material "sólo referencia" se indexa pero **no se cita** (§8.4). | Un programa, un aviso o una bibliografía no deben ser la fuente de una cita, pero el modelo puede necesitarlos como contexto. |
| D8 | El diagnóstico de indexación se calcula **re-troceando el material** con `Chunker.split` (función pura ya portada) con los parámetros de la cátedra. | No inventar un segundo troceador: si el diagnóstico no usa el mismo código que el índice, miente. |
| D9 | El panel es una **pestaña local** dentro de `AnalyticsPane` ("Resumen · Materiales"), no una sección nueva del rail. | Regla de `01-navegacion.md`: las pestañas dentro de la sección son locales. |
| D10 | El panel **no muestra ni un fragmento de texto del alumno** (ni el `snippet` ni el `reason` de una cita). | Regla 6 del proyecto. El detalle del fallo se ve en la pantalla de chats, que respeta la visibilidad. |
| D11 | Los "vacíos de cobertura" (temas sin material) se muestran **sin los términos** si el alumno es `METRICS_ONLY`. | Los términos salen del texto de la respuesta del alumno: mostrarlos sería leer texto no autorizado. |
| D12 | Los datos que hoy no se guardan (`sourceRefs`, `citation.details`) se agregan al modelo; sin ellos la calidad por material **no es calculable**. | `CitationStats` hoy guarda 3 contadores: no dice *qué* fuente falló. Sin detalle no hay atribución. |

---

## 1. Objetivo y alcance

**Qué resuelve.** El docente tiene una carpeta con 14 materiales y necesita
saber, sin leer 300 chats:

- ¿Qué material sostienen las respuestas de los alumnos? (calidad del corpus)
- ¿Qué material se consulta y no se cita nunca? (sobra o está mal indexado)
- ¿Qué material, cuando se cita, hace que la respuesta se quede sin respaldo?
  (el material confunde al modelo)
- ¿Qué PDF está indexado como un bloque de 12.000 caracteres y por eso nunca
  aparece? (chunking)
- ¿Qué temas de la cursada no tienen material? (vacío de cobertura)

**Fuera de alcance.** La calidad **por modelo** (doc hermano de analítica), la
calidad por alumno (`StudentAnalytics`, ya implementado) y la edición de
materiales (se reusa lo que ya hay en `MaterialsPane`, `01-navegacion.md`).

**Actor y permisos.** Sólo `TITULAR` y `AYUDANTE` (`CourseRole.canDesign`).
El panel es de lectura para los dos; las **acciones correctivas** (§8) siguen
las reglas de `docs/design/teacher/08-alumnos.md` (§8.5 deja explícita la
coordinación).

**Restricción de visibilidad.** Igual que en `AnalyticsAnalyzer`: sólo entran
los alumnos con `ChatVisibility.showsMetrics` (`FULL` o `METRICS_ONLY`). Los
`NONE` no aportan **ni un número** a este panel.

---

## 2. De dónde salen los datos

### 2.1 La cadena actual

```
Material.extractedText
   └─ Chunker.split(doc, chunkSize, overlap)      → List<Chunk>
        └─ Bm25Index.search(query, topK)           → List<Chunk> (hits)
             └─ RagEngine.prepare()                → contexto "[1] (cátedra: Apuntes-01.pdf — SVM)"
                  └─ el modelo responde con "[1]"
                       └─ CitationVerifier.verify(answer, sources)  → CitationReport
                            └─ ChatMessage.sources + ChatMessage.citation
                                 └─ AnalyticsAnalyzer.analyze(...)  → CourseAnalytics
```

### 2.2 Qué hay y qué falta

| Dato | Hoy | ¿Sirve para esto? |
|---|---|---|
| `Chunk.source` / `Chunk.path` | `"cátedra"` / **título del material** | Parcial: el título cambia y se duplica. Falta el id. |
| `Chunk.heading` | El `#` de Markdown, o el nombre del archivo si no hay ninguno | Sí: es lo que aparece en la cita y sirve para el diagnóstico de troceado. |
| `Chunk.label` | `"$source: $path"` | Es lo que se guarda en `ChatMessage.sources`. Legible, pero **no estable**. |
| `ChatMessage.sources: List<String>` | Etiquetas de las fuentes **recuperadas** | Sí, para contar recuperaciones. No dice el material. |
| `ChatMessage.citation: CitationStats` | `verified` / `unsupported` / `unknown` | Sí, pero **agregado**: no dice qué fuente falló → no hay atribución posible. |
| `CitationReport.checks` (`CitationCheck`) | `index`, `label`, `snippet`, `supported`, `reason` | **Es el detalle que hace falta**: existe en `:backend` pero se descarta al guardar el mensaje. |

**El hueco central:** entre `CitationVerifier.verify()` (que sabe exactamente
qué cita no se apoyó y por qué) y `ChatMessage` (que sólo guarda tres números)
se pierde el detalle. Por eso la atribución (§5) es imposible hoy.

### 2.3 La identidad del material en la cita (D1)

`CourseMaterialSource` construye el `Doc` así:

```kotlin
// :backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt (actual)
Doc(
    source = corpusLabel,                                  // "cátedra"
    path = material.title.ifBlank { material.path },        // ← el TÍTULO
    text = material.extractedText,
)
```

`Chunk.label` es `"$source: $path"` → `"cátedra: Apuntes-01.pdf"`. Es lo que
acaba en `ChatMessage.sources` y es lo que `07-chats.md` matchea contra
`Material.path` por texto.

**Problema.** Si el docente renombra "Apuntes-01.pdf" a "Apuntes-01 (definitivo).pdf",
las citas old quedan apuntando a un título que ya no existe y `07-chats.md`
deja de encontrar el material. Con dos materiales de igual nombre, las citas
se mezclan.

**Solución (D1):** el `Chunk` lleva el **id** del material por separado del
título, y el mensaje guarda **ambos**.

```kotlin
// :backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Id estable del material de origen ("" si la fuente no es de la cátedra). */
    val docId: String = "",
)

data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    /** Id estable del material de origen ("" si la fuente no es de la cátedra). */
    val docId: String = "",
    /** ¿El modelo puede citarlo? (false = material "sólo referencia"). */
    val citable: Boolean = true,
) {
    val label get() = "$source: $path"
}
```

```kotlin
// :backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt
Doc(
    source = corpusLabel,
    path = material.title.ifBlank { material.path },   // sigue siendo el título: es lo que se muestra
    text = material.extractedText,
    docId = material.id,                                // ← nuevo: identidad estable
)
```

`Chunk.label` **no cambia**: la UI y los chats siguen mostrando
`"cátedra: Apuntes-01.pdf"`. Lo que cambia es que, además de la etiqueta,
viaja el id.

---

## 3. Las métricas por material

### 3.1 Vocabulario (tres cosas que se confunden)

| Término | Significado | Dónde se cuenta |
|---|---|---|
| **Recuperación** (*recovery*) | Un fragmento del material entró al contexto del modelo. | `ChatMessage.sourceRefs` (una entrada por fragmento) |
| **Cita** | El modelo escribió `[n]` apuntando a un fragmento de ese material. | `ChatMessage.citation.details` (una entrada por aparición de `[n]`) |
| **Respuesta que cita el material** | Una respuesta del modelo en la que aparece al menos una cita a ese material. | `ChatMessage.citation.details` agregado por mensaje |

Un material puede tener **varias recuperaciones en la misma respuesta** (el
`topK` se lo lleva entero) y **varias citas** (el modelo repite `[1]`).

> `SourceUsage.times` (el modelo actual) cuenta *apariciones de la etiqueta* en
> `message.sources`: eso es **recuperaciones**, no citas. Se conserva el
> nombre del campo y se le agregan los contadores que faltan (§9.6).

### 3.2 Definiciones exactas

Conjunto de mensajes considerados: mensajes de asistente (`role == "assistant"`)
de hilos cuyo autor es una inscripción con `role == ALUMNO` y
`visibility.showsMetrics`.

Para un material `m`:

| Métrica | Fórmula | Notas |
|---|---|---|
| `retrievals(m)` | nº de entradas de `sourceRefs` con `materialId == m.id` | **Veces Recovery**. Un material puede aparecer 2 veces en la misma respuesta. |
| `answerHits(m)` | nº de mensajes con ≥1 `sourceRef` de `m` | Respuestas en las que el material estuvo disponible. |
| `citations(m)` | nº de `citation.details` con `materialId == m.id` | Citas (apariciones, no fuentes distintas). |
| `supported(m)` | de las anteriores, las que tienen `supported == true` | Respaldadas. |
| `unsupported(m)` | `citations(m) − supported(m)` | Sin respaldo. |
| `citingAnswers(m)` | nº de mensajes con ≥1 cita a `m` | |
| `ungroundedAnswers(m)` | nº de mensajes con ≥1 cita a `m` **sin respaldo** | "Respuestas sin respaldo que citan este material". |
| `supportRate(m)` | `supported(m) / citations(m)` |_cruda_. `null` si `citations(m) == 0`. |
| `ungroundedAnswerRate(m)` | `ungroundedAnswers(m) / citingAnswers(m)` | |
| `adjustedSupportRate(m)` | `(supported(m) + k·prior) / (citations(m) + k)` | La que ordena (§4.3). `prior` = tasa micro de la cátedra, `k = 5`. |
| `charsPerRetrieval(m)` | `textLength(m) / max(1, retrievals(m))` | "Caracteres por cada vez consultado": mucho texto, poca rotación. |
| `blamedAnswers(m)` | nº de respuestas cuyo **único culpable** es `m` (§5) | |
| `neverRetrieved(m)` | `retrievals(m) == 0 ∧ textLength(m) > 0 ∧ usage == CITABLE` | |
| `neverCited(m)` | `citations(m) == 0 ∧ retrievals(m) > 0` | Recuperado pero nunca citado. |

**Sobre `ungroundedAnswers`.** Se cuenta la respuesta **una vez**, aunque tenga
3 citas sin respaldo al mismo material. Y el mismo material puede aparecer en
la columna de otro material de la misma respuesta: **la columna no es sumable**
(§4.5).

### 3.3 Materiales nunca consultados — el dato más accionable

Es el que más rápido arregla una cátedra, pero tiene que **no acusar en falso**.
Se separa en tres situaciones, con tres avisos distintos:

| Situación | Señal | Qué le decimos al docente |
|---|---|---|
| **Nunca recuperado** | `retrievals(m) == 0` y el material tiene texto | "«Glosario.pdf» tiene 32.000 caracteres y ninguna respuesta lo recuperó: revisá la indexación." |
| **Recuperado pero nunca citado** | `retrievals(m) > 0 ∧ citations(m) == 0` | "«Glosario.pdf» apareció en 9 respuestas pero el modelo nunca lo citó: el contenido es redundante o está fuera del temario." |
| **Recién subido** | `uploadedAt` posterior al 60 % de la ventana de datos de la cátedra | "«Glosario.pdf» se subió hace 2 días y hay 5 días de conversaciones: todavía no tuvo oportunidad." |

La tercera fila evita el falso positivo más común: el docente sube el material
al final de la semana y el panel le dice que "nadie lo usa".

**Causas probables y su señal (el panel propone, no acusa):**

| Causa | Cómo se detecta | Acción sugerida |
|---|---|---|
| Mal troceado (un solo bloque gigante) | `chunkCount == 1 ∧ textLength > 2·chunkSize` | Trocear / subir como Markdown (§8.3) |
| Texto plano sin estructura | `flatText` (≤2 líneas y >2.000 chars) | Volver a subir como Markdown |
| Duplicado de otro material | Jaccard de shingles(5) ≥ 0,8 con otro | Borrar o archivar el duplicado |
| Sin encabezados ⇒ citas inútiles | `chunksWithHeading == 0` | Subir con `#` por unidad |
| Themes que los alumnos no preguntan | `answerHits(m) == 0` con datos suficientes | No es un error: está fuera del temario |
| BM25 no lo encuentra | el texto tiene sípalabras pero no en los títulos | Agregar encabezados con los términos que se usan en clase |

### 3.4 Materiales más citados pero con respuestas sin respaldo

Es el reverso: el material se usa mucho y **cuando se cita, la respuesta se
queda sin respaldo**. Ordenamos por `unsupported(m)` (nº absoluto de citas
falladas) y exigimos un **mínimo de muestra** (`citations(m) ≥ 3`) para que un
solo error no sitúe un material arriba.

La columna de la tabla no muestra sólo el conteo: muestra el par

```
"4 de 11"   →  4 respuestas sin respaldo entre las 11 que lo citan
```

que es lo que el docente entiende ("un tercio de las respuestas que lo citan
se quedan sin apoyo").

> **Correlación ≠ causalidad.** Que un material aparezca en respuestas sin
> respaldo **no prueba** que el material sea culpable: puede ser que el modelo
> haya inventado un detalle (D2, §5). Por eso la tabla muestra la señal cruda
> y el **detalle** del material muestra la atribución (una respuesta → un
> culpable). El texto del panel lo dice explícitamente.

### 3.5 Materiales con mucho texto pero baja recuperación

Tres medidas, todas relativas al resto de la cátedra (para que no dependan del
tamaño absoluto de la materia):

| Medida | Definición | Por qué |
|---|---|---|
| `charsPerRetrieval(m)` | `textLength / max(1, retrievals)` | "Cuántos caracteres hay que leer por cada vez que sale el material". |
| `textShare(m)` | `textLength(m) / Σ textLength` | "¿Qué parte del corpus es?" |
| `retrievalShare(m)` | `retrievals(m) / Σ retrievals` | "¿Qué parte de las recuperaciones se lleva?" |

**Regla "mucho texto, poca recuperación"** (las tres):

```
textShare(m) ≥ 2 × textShare(mediana)      // el material es grande
retrievalShare(m) ≤ 0,5 × retrievalShare(mediana)   // y casi no se usa
citations(m) == 0                          // y nunca se cita
```

Copy: *"«Teoría-completa.pdf» es el 38 % del corpus y aporta el 2 % de las
recuperaciones (2 citas en 3 semanas). O está duplicado, o el material es tan
grande que el troceado lo vuelve inaccesible."*

**El caso especial "no es que no sirva: es que no se encuentra".** Si el
material tiene palabras que los alumnos usan (los términos de las preguntas) y
aun así no aparece, el problema es de **descubribilidad**, no de contenido: el
chunk está, pero BM25 no lo sube al `topK`. Señal: `textShare` alto +
`retrievalShare` bajo + `chunksWithHeading == 0` (el `Bm25Index` indexa
`"${heading} ${path} ${text}"`, así que los encabezados pesan 3 veces el texto
en la práctica del campo `tf`).

### 3.6 Distribución de citas por unidad / tema

La unidad es una **etiqueta** del material (`Material.tags`, que ya existe):
`"Unidad 1"`, `"Unidad 2"`, `"SVM"`, `"Parcial 1"`. Los docentes ya usan
`tags` para clasificar (ver `MaterialsPane`, `01-navegacion.md`).

```kotlin
@Serializable
data class UnitMetrics(
    /** Etiqueta de la unidad ("Unidad 1"). */
    val unit: String,
    /** Materiales de la unidad. */
    val materials: Int = 0,
    /** Fragmentos recuperados de la unidad. */
    val retrievals: Int = 0,
    /** Citas a materiales de la unidad. */
    val citations: Int = 0,
    /** Citas respaldadas. */
    val supported: Int = 0,
    /** Tasa de respaldo micro de la unidad. */
    val supportRate: Double = 0.0,
    /** Porción del total de citas de la cátedra (0..1). */
    val shareOfCitations: Double = 0.0,
    /** Etiquetas de la unidad sin ningún material. */
    val untagged: Int = 0,
)
```

Reglas:

- Un material con **varias** unidades cuenta en cada una (la cita se reparte por
  partes iguales entre sus unidades para no duplicar el total: `1/n` por
  unidad). Esto evita que la suma de `citations` por unidad supere el total.
- Un material **sin etiqueta** va a la pseudo-unidad `"(sin clasificar)"`.
  Y eso es en sí un aviso: *"4 materiales sin unidad: etiquetalos para ver qué
  temas rinden"*.
- Se muestra también el desglose por **tipo** (`MaterialKind`): diapositivas
  vs apuntes vs documentos. Muchos cursos usan las diapositivas para repasar y
  los apuntes para explicar; que los números lo digan es el objetivo.

Vista: barras horizontales por unidad (citas) con el % de respaldo al lado, y
un chip con la unidad más floja.

---

## 4. El cruce material ↔ calidad

### 4.1 Las tres tasas y cuál va en pantalla

| Tasa | Fórmula | Dónde |
|---|---|---|
| **Micro** (citas) | `Σ supported(m) / Σ citations(m)` | KPI de la cátedra. |
| **Micro** (respuestas) | `respuestas con ≥1 cita sin respaldo / respuestas` | KPI de la cátedra (ya existe como `ungroundedAnswerRate` en `StudentAnalytics`). |
| **Por material** | `supported(m) / citations(m)` | Cada fila de la tabla. |

La tabla muestra **la tasa por material** (una fila = un material). Eso ya
resuelve el problema del sesgo dentro de cada fila: un material con 300 citas
no arrastra a un material con 5 citas, porque **no se promedian entre sí**.

### 4.2 El sesgo que sí queda: promediar las tasas

Si la cátedra tiene 7 materiales y uno concentra el 80 % de las citas, el
**promedio simple** de las tasas por material (`(90+33+100+0)/4`) no describe
nada: le da el mismo peso a un material con 1 cita que a uno con 300.

| Agregado | Fórmula | Qué weigh | Veredicto |
|---|---|---|---|
| Micro | `Σ sup / Σ cit` | cada cita pesa 1 | **Correcto** para "el curso" |
| Macro simple | `mean(rate(m))` | cada material pesa 1 | **No usar**: un material con 1 cita pesa igual que uno con 300 |
| Macro ponderado | `Σ rate(m)·cit(m) / Σ cit(m)` | cada material pesa según sus citas | Equivale a micro; se usa para comparar unidades |

**Regla:** el único número agregado que se muestra es el **micro**. El macro
simple aparece **sólo** como nota al pie de la tabla, con la advertencia
escrita: *"Promedio simple entre materiales: 56 % — no usar para comparar con
el 75 % del curso: un material con una sola cita pesa lo mismo que uno con
200."*

### 4.3 El ajuste por encogimiento (shrinkage) — D3

Una tasa sobre 1 ó 2 citas es ruido. Se usa un prior de la cátedra:

```
prior = Σ supported(m) / Σ citations(m)          (micro de la cátedra)
k     = 5                                          (constante del analizador)
adjustedSupportRate(m) = (supported(m) + k·prior) / (citations(m) + k)
```

Interpretación: cada material arranca con "5 citas prestadas" de la media de
la cátedra. Con muchas citas, el dato real domina; con pocas, el material
tiende a la media y **deja de ser el accused de la tabla**.

Por qué `k = 5`: con 5 citas de muestra, el peso del dato real ya es 50 %;
por debajo de 3 citas el material se marca como **"muestra chica"** en la UI y
la tasa real se muestra en gris al lado de la ajustada.

### 4.4 Ejemplo numérico completo

Cátedra con 4 materiales:

| Material | Citas | Respaldadas | Tasa cruda | Tasa ajustada (prior 0,75 · k 5) |
|---|---|---|---|---|
| Apuntes-01.pdf (Unidad 1) | 30 | 27 | 90 % | **87,9 %** |
| Diapositivas.pdf (Unidad 1) | 12 | 4 | 33 % | **45,6 %** |
| Parcial-2025.pdf (Unidad 2) | 5 | 5 | 100 % | **87,5 %** |
| Glosario.pdf (Unidad 2) | 1 | 0 | 0 % | **62,5 %** |

- **Micro de la cátedra:** 36/48 = **75 %**.
- **Macro simple:** (90+33+100+0)/4 = **55,8 %** → no usar.
- **Orden por tasa ajustada:** Diapositivas (45,6 %) → Glosario (62,5 %) →
  Parcial (87,5 %) → Apuntes (87,9 %).

**Lo que ve el docente.** Sin ajuste, la tabla diría "Glosario.pdf es el peor
material (0 %)". Con ajuste, el problema real —Diapositivas.pdf, 12 citas y
sólo 4 respaldadas— queda arriba, y Glosario (una sola cita) queda como
"muestra chica". Si además miramos las respuestas, Diapositivas aparece en 9
respuestas y 5 de ellas quedan sin respaldo (56 %): ese es el material que hay
que arreglar.

### 4.5 Lo que las columnas **no** pueden sumar

Una respuesta con 5 citas aparece en las filas de 5 materiales. Por eso:

- La columna "Sin respaldo" **no es sumable** entre filas.
- El total de la cátedra ("respuestas sin respaldo") se cuenta **por
  respuesta**, no por material: es `responsesWithUnsupported`, y no coincide
  con `Σ ungroundedAnswers(m)`.
- En la tabla, bajo el total de citas, se muestra el micro de la cátedra
  (75 %) y **debajo**, en letra chica: *"la suma de las filas puede superar
  el total de respuestas porque una respuesta cita varios materiales"*.

---

## 5. El problema de la atribución

### 5.1 Por qué no se puede culpar a todas las fuentes

Una respuesta típica:

```
"El margen es la distancia entre la frontera de decisión y el punto más cercano
de cada clase. Se usa SVM [1]. El margen duro no generaliza [2]. según [3]…"
```

Verificada contra 3 fuentes: `[1]` respaldada, `[2]` sin respaldo, `[3]` sin
respaldo. Si el fallo se reparte entre las dos, **las dos** quedan "con
respuestas sin respaldo" y la tabla no dice qué hacer.

Además, el verificador ya sabe **qué** pasó: `CitationCheck.reason` dice, por
ejemplo, *"La cita menciona \"DirectX 12\", que no aparece en la fuente"* o
*"El fragmento no aparece en la fuente (sólo el 38 % de sus palabras)"*. Ese
detalle es el que hay que usar.

### 5.2 La regla: una respuesta, un culpable

**Cada respuesta con citas sin respaldo recibe un único `BlameKind`** (y, cuando
aplica, un único `materialId`). El culpable se decide con una escalera
determinista:

**Paso 1 — elegir la cita fallada principal.** Entre las citas sin respaldo de
la respuesta, se toma la de **menor similitud** con su fragmento (`similarity`,
la cobertura de palabras del verificador): es el fragmento al que **menos se
parece** lo que el modelo dijo. Es el sospechoso principal. (Empate: la cita
de índice menor.)

**Paso 2 — clasificar la causa.** Con la cita principal `(snippet S, chunk C
del material M)`:

```
T = keyTerms(S)          // nombres propios y números (CitationVerifier.keyTerms)
```

| # | Condición | Culpable | Qué le pasa al material |
|---|---|---|---|
| 1 | `T ≠ ∅` y **ningún** fragmento del corpus contiene los términos de `T` | `MODEL` | Ninguno: el modelo inventó el detalle. |
| 2 | `T ≠ ∅` y los términos están en **otro fragmento del mismo material M** | `CHUNKING` (M) | M: el contenido existe pero quedó partido. |
| 3 | `T ≠ ∅` y los términos están en un fragmento **que fue recuperado** (de cualquier material) | `WRONG_SOURCE` | Ninguno: el modelo tenía el fragmento y citar otro. |
| 4 | `T ≠ ∅` y los términos están en un fragmento **que no fue recuperado** (material X) | `RETRIEVAL` (X) | X: no es descubrible; el RAG no lo trajo. |
| 5 | `T = ∅` y el fragmento **más parecido** del corpus está en M (pero bajo el umbral) | `THIN_CONTENT` (M) | M: el material no alcanza a cubrir lo que se le preguntó. |
| 6 | `T = ∅` y ningún fragmento se parece lo suficiente | `GAP` | Ninguno: **falta material** sobre ese tema. |

**Paso 3 — desempate por gravedad.** Si la respuesta tiene varias citas
falladas y dan culpables distintos, gana el de mayor gravedad (orden de
intervención del docente):

```
CHUNKING(5) > RETRIEVAL(4) > THIN_CONTENT(3) > WRONG_SOURCE(2) > GAP(1) > MODEL(0)
```

Así, una respuesta que a la vez tiene una cita inventada y otra con el
contenido partido se atribuye al **troceado**, que es lo que el docente puede
arreglar; el modelo inventado queda como secundario.

> La atribución es una **causa probable**, no una certeza: depende del umbral
> `MIN_WORD_COVERAGE` del verificador (§12.2). Por eso la interfaz dice
> "causa probable" y el detalle del material separa la señal (columna "Sin
> respaldo") de la atribución (bloque "Causa probable").

### 5.3 El "¿cuál no respaldó?" del verificador

`CitationVerifier` hoy devuelve un `Pair<Boolean, String>`. Para poder
ordenar por "menos parecido" hace falta el número. Cambio mínimo en
`:backend`:

```kotlin
/** Veredicto del verificador para un fragmento contra una fuente. */
data class Verdict(
    val supported: Boolean,
    /** Cobertura de palabras significativas (0..1). */
    val similarity: Double,
    /** Motivo (vacío si está respaldada). */
    val reason: String = "",
)
```

```kotlin
// :backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt
private fun checkSnippet(snippet: String, sourceText: String): Verdict {
    ...
    if (normSource.contains(normSnippet)) return Verdict(true, 1.0)
    val inventados = keyTerms(snippet).filter { !normSource.contains(it.text) }
    if (inventados.isNotEmpty())
        return Verdict(false, 0.0, "La cita menciona ${…}, que no aparece en la fuente.")
    val coverage = covered.toDouble() / words.size
    return if (coverage >= MIN_WORD_COVERAGE) Verdict(true, coverage)
           else Verdict(false, coverage, "El fragmento no aparece en la fuente (sólo el ${…} % de sus palabras).")
}
```

Y `CitationCheck` gana dos campos: `docId` (material) y `similarity`.

**Consecuencia para la atribución:** el `reason` del verificador ya
distingue el caso 1 (términos inventados) del caso "cobertura baja". El paso 2
de la escalera usa `reason` + `keyTerms` para decidir entre `MODEL` y el
resto.

### 5.4 Lo que hay que guardar en el mensaje

Sin esto, la atribución no se puede hacer **después** (el panel corre sobre
mensajes ya guardados). De `CitationReport` hay que persistir el detalle:

```kotlin
// :core/src/commonMain/kotlin/kitoru/domain/Chat.kt
@Serializable
data class CitationDetail(
    /** Número de la cita ([1], [2]…). */
    val index: Int,
    /** Id del material al que apunta ("" si no se pudo resolver). */
    val materialId: String = "",
    /** Etiqueta legible ("cátedra: Apuntes-01.pdf"). */
    val label: String = "",
    /** Encabezado del fragmento citado. */
    val heading: String = "",
    /** ¿El fragmento respalda lo que dice la respuesta? */
    val supported: Boolean = false,
    /** Similitud 0..1 entre el fragmento citado y el texto de la respuesta. */
    val similarity: Double = 0.0,
    /** Motivo del verificador (vacío si está respaldada). */
    val reason: String = "",
    /** Texto de la respuesta alrededor de la cita (≤ 400 caracteres). */
    val snippet: String = "",
)
```

y en `CitationStats`:

```kotlin
@Serializable
data class CitationStats(
    val verified: Int = 0,
    val unsupported: Int = 0,
    val unknown: Int = 0,
    /** Detalle de cada aparición de cita, en el orden en que aparecen. */
    val details: List<CitationDetail> = emptyList(),
) { … }
```

`details` es **aditivo** (`default = emptyList()`): los mensajes viejos siguen
leyéndose y el panel los marca como "sin detalle" (§10).

### 5.5 Qué muestra el panel de cada respuesta

En el detalle de un material, la lista de "respuestas que lo citan" con su
veredicto:

```
✓ 12/03 18:24  ·  respaldada          «El margen es la distancia…»   → Apuntes-01.pdf
⚠ 12/03 18:31  ·  sin respaldo        «…el margen duro no generaliza» → Diapositivas.pdf
      motivo: "El fragmento no aparece en la fuente (sólo el 38 % de sus palabras)."
      atribución: troceado — el contenido está en "Diapositivas.pdf", sección "Tema 3"
```

**Regla de privacidad (D10, D11).** El `snippet` y el `reason` son texto
derivado de la conversación del alumno. En el panel de materiales:

- si el alumno es `FULL` → se muestran;
- si el alumno es `METRICS_ONLY` → **no** se muestran; la fila dice sólo
  "12/03 · sin respaldo · 2 citas" y el motivo se oculta con un candado.

Los **términos de los vacíos de cobertura** (`TopicGap.terms`) siguen la misma
regla (`termsVisible`).

---

## 6. Diagnóstico de indexación

### 6.1 Cómo se calcula

Re-trocear el material con **el mismo código que usa el índice**:

```kotlin
// :backend/src/commonMain/kotlin/rag/analytics/IndexDiagnostics.kt
object IndexDiagnostics {
    fun analyze(material: Material, chunkSize: Int, chunkOverlap: Int): MaterialIndexStats {
        val doc = Doc(
            source = "cátedra",
            path = material.title.ifBlank { material.path },
            text = material.extractedText,
            docId = material.id,
        )
        val chunks = Chunker.split(doc, chunkSize, chunkOverlap)
        val defaultHeading = material.title.ifBlank { material.path }.substringAfterLast('/')
        val lengths = chunks.map { it.text.length }
        return MaterialIndexStats(
            chunkCount = chunks.size,
            avgChunkChars = lengths.average().toInt(),
            minChunkChars = lengths.minOrNull() ?: 0,
            maxChunkChars = lengths.maxOrNull() ?: 0,
            chunksWithHeading = chunks.count { it.heading != defaultHeading },
            distinctHeadings = chunks.map { it.heading }.distinct().size,
            textLength = material.extractedText.length,
            lineCount = material.extractedText.count { it == '\n' } + 1,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
            indexedAt = nowMillis(),
            flatText = material.extractedText.count { it == '\n' } <= 1 &&
                material.extractedText.length > 2_000,
        )
    }
}
```

> **Por qué re-trocear y no guardar los chunks:** el `Chunk` no se serializa hoy
> y el índice es en memoria. Re-trocear es determinista, barato (el corpus de
> una cátedra son cientos de KB) y **garantiza que el diagnóstico describa el
> índice real**. Se cachea por `(materialId, chunkSize, chunkOverlap,
> hash(text))` para no repetirlo en cada recomputo de la analítica.

### 6.2 Las señales y sus umbrales

| Señal | Cálculo | Umbral | Problema | Gravedad |
|---|---|---|---|---|
| `textLength` | `Material.textLength` | `== 0` | `NO_TEXT` | crítica |
| `chunkCount` | `chunks.size` | `== 1 ∧ textLength > 2·chunkSize` | `SINGLE_HUGE_CHUNK` | crítica |
| `avgChunkChars` | media de `len(chunk.text)` | `> 1,5·chunkSize` | `CHUNKS_TOO_BIG` | aviso |
| `avgChunkChars` | | `< 0,25·chunkSize ∧ chunkCount > 20` | `CHUNKS_TOO_SMALL` | aviso |
| `chunksWithHeading / chunkCount` | encabezados ≠ nombre del archivo | `< 0,5` | `NO_HEADINGS` | aviso |
| `distinctHeadings` | | `< 3 ∧ chunkCount > 3` | `NO_HEADINGS` | aviso |
| `flatText` | ≤2 líneas y >2.000 caracteres | `true` | `FLAT_TEXT` | aviso |
| `charsPerLine` | `textLength / lineCount` | `> 300` | `FLAT_TEXT` | aviso |
| `overlapRatio` | `1 − Σ len(chunk) / textLength` | `> 0,35` | informativo | info |
| `charsPerRetrieval` | §3.5 | `≥ 3×` la mediana | `LOW_RETRIEVAL` | aviso |
| Jaccard de shingles(5) entre materiales | | `≥ 0,8` | `DUPLICATE` | aviso |

`overlapRatio` explicativo: el solapamiento (`chunkOverlap = 150`) duplica
caracteres; con `chunkSize = 1200` son ~12 % del corpus repetido. Si sube a
35 %, el `topK` se llena de casi-copias y la recuperación pierde variedad.

### 6.3 Qué le dice al docente (copy exacto)

Cada problema tiene una frase que dice **qué pasó** y **qué hacer**:

| Problema | Copy |
|---|---|
| `NO_TEXT` | "«Apuntes-2025.pdf» no tiene capa de texto: el RAG no tiene nada que buscar. Subilo como Markdown o como PDF con texto seleccionable." |
| `SINGLE_HUGE_CHUNK` | "«Apuntes-2025.pdf» se indexó en 1 fragmento de 12.400 caracteres: conviene subirlo más troceado o como Markdown." |
| `CHUNKS_TOO_BIG` | "«Diapositivas.pdf» se indexó en 3 fragmentos de 12.000 caracteres: conviene subirlo más troceado o como Markdown." |
| `NO_HEADINGS` | "«Diapositivas.pdf» se indexó en 8 fragmentos sin ningún encabezado: las citas van a decir sólo «Diapositivas.pdf». Subilo como Markdown con un título por unidad." |
| `FLAT_TEXT` | "«Apuntes-2025.pdf» tiene 1 línea de 12.400 caracteres (texto de PDF sin saltos de línea): el troceado no puede cortar por secciones. Volvelo a subir como Markdown." |
| `CHUNKS_TOO_SMALL` | "«Teoría.pdf» se indexó en 240 fragmentos de 90 caracteres: cada cita pierde el contexto y las recuperaciones se repiten. Subí un archivo por tema." |
| `LOW_RETRIEVAL` | "«Bibliografía.pdf» tiene 80.000 caracteres y fue consultado 2 veces: hay material de sobra que no se está usando. Mirá si duplica otro material o si conviene dividirlo." |
| `DUPLICATE` | "«Apuntes-01.pdf» y «Apuntes-01 (final).pdf» tienen casi el mismo texto (92 %): el RAG va a elegir uno solo. Archivá el duplicado." |

**El formato es siempre el mismo**: `«Título»` + qué pasó + qué hacer. Nunca
"el material es malo": el panel señala una **acción**.

### 6.4 Ejemplo real

Material: `Apuntes-2025.pdf`, `extractedText` = 12.400 caracteres en 3 líneas
(PDF copiado), `chunkSize` de la cátedra = 1200, `chunkOverlap` = 150.

```
Chunker.split → 3 chunks de ~4.100 caracteres (el troceado por tamaño nunca
entra, porque flush() encuentra un "\n" cada 4.000 caracteres)
avgChunkChars   = 4.133   > 1,5 × 1200  → CHUNKS_TOO_BIG
chunksWithHeading = 0 / 3              → NO_HEADINGS
lineCount = 3, charsPerLine = 4.133     → FLAT_TEXT
```

Copy final que ve el docente:

> **«Apuntes-2025.pdf» se indexó en 3 fragmentos de 4.100 caracteres.**
> Conviene subirlo más troceado o como Markdown: hoy las citas dicen sólo
> "Apuntes-2025.pdf" y el modelo recibe bloques de 4.000 caracteres donde la
> idea queda partida.

---

## 7. La vista: el panel "Materiales"

### 7.1 Dónde vive

Pestaña **local** dentro de `AnalyticsPane` (`01-navegacion.md` §3.f):

```
AnalyticsPane
├── Tab "Resumen"     (lo que ya existe: KPIs, gráfico, fuentes, tabla por alumno)
└── Tab "Materiales"  (este doc)
```

Motivo (D9): es la misma unidad de información (la analítica de la cátedra) y
evita sumar una sección al rail del docente.

### 7.2 Desktop

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Analítica · IA-2026-1        [ Resumen ] [ Materiales ]                     [Exportar CSV] [↻]  │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ⚠ 3 materiales aparecen en respuestas sin respaldo                                             │
│ ⚠ 2 materiales nunca fueron consultados                                                        │
│ ⚠ 1 material con problemas de troceado                                                          │
│ ℹ 4 materiales sin unidad · 1 tema sin cobertura en el material                                  │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Ordenar: [Tasa de respaldo ▾]   Filtro: [Con problemas ▾]   Unidad: [Todas ▾]   Tipo: [Todos ▾]   │
├────────────────────────┬────────┬───────┬───────┬────────┬───────┬───────────────────────────────┤
│ MATERIAL               │ FRAGM. │ RESP. │ CITAS │ RESPAL.│ TASA  │ ESTADO                        │
├────────────────────────┼────────┼───────┼───────┼────────┼───────┼───────────────────────────────┤
│ Diapositivas.pdf       │     34 │     9 │    12 │       4│  45,6%│ ⚠ 5 de 9 sin respaldo        │
│  ▪ Diapositivas · Unidad 1                                                 ⋮                  │
├────────────────────────┼────────┼───────┼───────┼────────┼───────┼───────────────────────────────┤
│ Glosario.pdf           │      2 │     2 │     1 │       0│  62,5%│ ⚠ muestra chica (1 cita)      │
├────────────────────────┼────────┼───────┼───────┼────────┼───────┼───────────────────────────────┤
│ Parcial-2025.pdf       │      9 │     7 │     5 │       5│  87,5%│ ✓ bien                        │
├────────────────────────┼────────┼───────┼───────┼────────┼───────┼───────────────────────────────┤
│ Apuntes-01.pdf         │    118 │    20 │    30 │      27│  87,9%│ ✓ bien                        │
├────────────────────────┼────────┼───────┼───────┼────────┼───────┼───────────────────────────────┤
│ Teoría-completa.pdf    │      2 │     2 │     0 │       0│     — │ ⓘ nunca consultado · 38 % del │
│  ▪ Documento · Unidad 1                                                 │   corpus            │
├────────────────────────┴────────┴───────┴───────┴────────┴───────┴───────────────────────────────┤
│ Tasa de respaldo de la cátedra: 75 % (36 de 48 citas)                                           │
│ La suma de las filas puede superar el total: una respuesta puede citar varios materiales.       │
│ Promedio simple entre materiales: 56 % — no comparable con el 75 % del curso.                    │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 7.3 Las columnas

| Columna | Campo | Formato | Tooltip |
|---|---|---|---|
| **Material** | título, tipo, unidades | 2 líneas: título + chips | "5.412 caracteres · 34 fragmentos · subido por la Dra. Pérez el 12/03" |
| **Fragm.** | `retrievals` | entero | "Veces que un fragmento de este material entró al contexto del modelo" |
| **Resp.** | `answerHits` | entero | "Respuestas en las que el material estuvo disponible" |
| **Citas** | `citations` | entero | "Apariciones de `[n]` que apuntan a este material" |
| **Respal.** | `supported` | entero | "Citas respaldadas por su fuente" |
| **Tasa** | `adjustedSupportRate` | % grande + % crudo en chico | "Tasa ajustada (encogimiento, k=5). Cruda: 33 %. 12 citas: muestra suficiente." |
| **Estado** | flags | chips | ver §7.4 |
| **⋮** | — | menú | §8 |

Los **chips de estado** (orden de prioridad):

| Chip | Condición | Color |
|---|---|---|
| `Nunca consultado` | `neverRetrieved` | ámbar |
| `Recuperado, nunca citado` | `neverCited` | ámbar |
| `Sin respaldo` | `ungroundedAnswers ≥ 1` | rojo |
| `Troceado` | `indexIssue != null` | ámbar |
| `Duplicado` | `duplicateOf != ""` | ámbar |
| `Sólo referencia` | `usage == REFERENCE` | neutro |
| `Archivado` | `usage == ARCHIVED` | neutro |
| `Recién subido` | subida posterior al 60 % de la ventana de datos | info |

### 7.4 Los avisos (copy)

Los avisos son **tarjetas apiladas arriba de la tabla**, cada una con un
"Ver" que filtra la tabla:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ⚠ 3 materiales aparecen en respuestas sin respaldo                                │
│   Diapositivas.pdf (5 de 9 respuestas), Apuntes-02.pdf (2 de 4), Práctica-1.pdf   │
│   (1 de 3). En 4 de esos casos el contenido estaba en el material pero partido   │
│   en otro fragmento: es un problema de troceado, no de contenido.   [Ver ▸]     │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ⚠ 2 materiales nunca fueron consultados                                          │
│   Teoría-completa.pdf (38 % del corpus, 2 citas) y Bibliografía.pdf (12 % del    │
│   corpus, 0 citas). Ninguno fue recuperado por el RAG en 5 semanas de           │
│   conversaciones.                                        [Ver ▸]                 │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ⚠ 1 material con problemas de troceado                                           │
│   «Diapositivas.pdf» se indexó en 3 fragmentos de 4.100 caracteres: conviene      │
│   subirlo más troceado o como Markdown.                    [Ver ▸]                │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ℹ 1 tema sin cobertura en el material                                             │
│   8 respuestas sin respaldo no encuentran su contenido en ningún material de la  │
│   cátedra (términos frecuentes: backpropagation, desvanecimiento).    [Ver ▸]   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

El tercer aviso es el que mejor resume el método: **"es un problema de
troceado, no de contenido"** — ése es el aporte de la atribución (§5).

### 7.5 Detalle de un material (panel lateral)

Al hacer clic en una fila se abre `MaterialDetailPanel` (en desktop, panel
lateral; en Android, pantalla completa, siguiendo `01-navegacion.md` D7):

```
┌────────────────────────────────────────────────────────────────┐
│ Diapositivas.pdf                                       [⋮]     │
│ Diapositivas · Unidad 1 · 412.000 bytes · subido el 12/03      │
├────────────────────────────────────────────────────────────────┤
│ CALIDAD                                                    45,6 %│
│ Tasa de respaldo ajustada (cruda 33 %) · 12 citas              │
│ ▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│
│                                                                │
│ 34 fragmentos recuperados · 9 respuestas · 12 citas · 4 respald. │
│ 5 de las 9 respuestas que lo citan quedaron sin respaldo (56 %) │
│                                                                │
│ CAUSA PROBABLE (atribución de 5 respuestas falladas)            │
│   ▸ Troceado ............ 3   el contenido estaba en este mismo │
│                               material, en otro fragmento        │
│   ▸ Fuente equivocada .. 1   el modelo tenía el fragmento      │
│                               correcto y citar otro             │
│   ▸ Modelo .............. 1   inventó un detalle                 │
│                                                                │
│ INDEXACIÓN                                                    ⚠   │
│ 3 fragmentos · 4.100 caracteres en promedio · sin encabezados  │
│ «Diapositivas.pdf» se indexó en 3 fragmentos de 4.100           │
│ caracteres: conviene subirlo más troceado o como Markdown.     │
│                                                                │
│ UNIDADES                                                      34 %│
│ Unidad 1 ▓▓▓▓▓▓▓▓░░░░░░░░░░  12 citas · 33 % de respaldo      │
│                                                                │
│ ACCIONES                                                      │
│ [Ver chats que lo citan] [Reindexar] [Editar texto] [Dividir]  │
│ [Marcar como sólo referencia]                                 │
└────────────────────────────────────────────────────────────────┘
```

**"Ver chats que lo citan"** salta a la sección de chats con
`ChatFilters.materialId = <id>` (el filtro ya existe en
`docs/design/teacher/07-chats.md` §5.1). Es el ida y vuelta entre "el
material está mal" y "mirá lo que dijo el alumno".

### 7.6 Android

La tabla se convierte en **tarjetas** (una por material), ordenadas igual:
título + chips, la tasa grande a la derecha, y las cifras en dos líneas. Los
avisos son tarjetas apiladas. El detalle es una pantalla con `TopAppBar` y
"ATRÁS".

### 7.7 Componentes

| Componente | Ruta |
|---|---|
| `MaterialAnalyticsPane` | `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/MaterialAnalyticsPane.kt` |
| `MaterialAlertList` / `MaterialAlertCard` | idem |
| `MaterialMetricsTable` | idem |
| `MaterialRow` | idem |
| `MaterialStatusChip` | idem |
| `MaterialDetailPanel` | idem |
| `UnitShareChart` | idem |
| `TopicGapCard` | idem |
| `MaterialActionMenu` | idem |

---

## 8. La acción correctiva

### 8.1 Reindexar

`onReindex(materialId)`:

1. Se re-trocea ese material con los parámetros **actuales** de la cátedra
   (`course.chunkSize`, `course.chunkOverlap`).
2. Se recalcula `material.indexStats`.
3. Se reconstruye el índice del curso (`KnowledgeBase.sync()`), porque el
   `Bm25Index` es inmutable: reindexar un material es reindexar la cátedra.
   Con un corpus de cátedra (cientos de KB) tarda menos de un segundo; el
   progreso se muestra como *"Reindexando… (14 materiales)"*.
4. Se guarda el material.

Copy de confirmación:

> "«Diapositivas.pdf» se reindexó: 14 fragmentos con 9 encabezados (antes 3
> fragmentos de 4.100 caracteres, sin encabezados). Las citas ahora muestran la
> sección de cada unidad."

Si el material **no cambió** pero la cátedra cambió `chunkSize`, el botón dice
*"Reindexar con la configuración actual"* y el detalle muestra el aviso:
*"Indexado con 1.200; la cátedra ahora usa 800."*

### 8.2 Editar el texto extraído

Acción en el detalle: **"Editar texto extraído"** abre un `TextField`
multilínea con `Material.extractedText` (el mismo que se indexa, no el PDF
original). Es la vía para arreglar un PDF mal extraído **sin volver a subirlo**:

```
┌ Editar texto extraído ───────────────────────────────────────────┐
│ «Diapositivas.pdf»                                              │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ # Unidad 1                                                    │ │
│ │ SVM separa datos…                                             │ │
│ │ …                                                             │ │
│ └──────────────────────────────────────────────────────────────┘ │
│ 12.400 caracteres · 3 líneas                                     │
│ [Cancelar]  [Guardar y reindexar]                                │
└──────────────────────────────────────────────────────────────────┘
```

Al guardar: se persiste `extractedText` y se reindexa. Copy:

> "Guardaste el texto corregido. «Diapositivas.pdf» se reindexó: 14
> fragmentos con 9 encabezados."

**Permisos:** `canDesign` (titular y ayudante). **El archivo original no se
toca**: `Material.path` sigue apuntando al PDF subido; lo que cambia es el
texto que ve el RAG. El detalle muestra el aviso: *"Estás editando el texto
que ve el modelo, no el PDF original."*

### 8.3 Dividir el material

Acción **"Dividir"** → diálogo con tres modos:

| Modo | Qué hace |
|---|---|
| **Por encabezado** | Un material nuevo por encabezado (`#` de nivel 1 o 2). Ideal para un PDF con un título por unidad. |
| **Por rango** | El docente marca un corte (nº de caracteres o de líneas) y repite. |
| **Por fragmentos** | Se parte por los fragmentos actuales del índice (si el troceado ya está bien, el problema es que son demasiado grandes: partirlos a mano). |

Al confirmar:

1. Se crean N materiales nuevos (`kind` igual, `extractedText` = la porción,
   `tags` heredados, `splitFrom = <id del original>`).
2. El original queda `usage = ARCHIVED` y
   `supersededBy = [<ids de los nuevos>]`.
   Los archivos originales **no** se borran (el docente puede volver atrás).
3. Se reindexa.

Copy:

> "Se crearon 3 materiales: «Diapositivas (Unidad 1).pdf», «Diapositivas
> (Unidad 2).pdf» y «Diapositivas (Unidad 3).pdf». «Diapositivas.pdf» quedó
> archivado y sale del índice. Podés deshacerlo desde Materiales › Archivados."

### 8.4 Marcar como "sólo referencia"

`Material.usage = REFERENCE`:

- Los fragmentos del material **se indexan y se recuperan** (el modelo puede
  usarlos como contexto).
- **No son citables**: en `RagEngine.prepare()` los hits citable se numeran
  (`[1]`, `[2]`…) y los de referencia van a un bloque aparte:

```
CONTEXTO:
[1] (cátedra: Apuntes-01.pdf — SVM)
…texto…

REFERENCIAS (informativo, no las cites):
(cátedra: Programa-2026.pdf)
…texto…
```

- El verificador sólo verifica las fuentes numeradas.
- En el panel, el material aparece con el chip "Sólo referencia" y **queda
  fuera** de las métricas de calidad (tasa de respaldo, "sin respaldo",
  "nunca consultado"): no se cita por diseño, no es un problema.

Implementación: `Chunk.citable` (nuevo) + el filtro en `prepare()`.

Copy en la UI:

> "«Programa-2026.pdf» pasa a ser sólo referencia: el modelo lo puede leer
> como contexto, pero no lo va a citar. Los materiales de referencia no
> cuentan en las tasas de calidad."

**Alternativa considerada y descartada (v1):** excluir el material del
índice. Es más simple, pero el modelo pierde el contexto y "disponible" deja
de ser cierto. Queda anotado para la v2 (`usage = CONTEXT_ONLY` vs
`REFERENCE`).

`usage = ARCHIVED` sí lo saca del índice (`CourseMaterialSource` lo filtra) y
de las métricas.

### 8.5 Quién puede hacer qué

| Acción | TITULAR | AYUDANTE | Comentario |
|---|---|---|---|
| Ver el panel | ✔ | ✔ | `canDesign` |
| Reindexar | ✔ | ✔ | |
| Editar el texto extraído | ✔ | ✔ | `canDesign` (igual que editar `Material.tags`, `01-navegacion.md`) |
| Dividir | ✔ | ✔ | |
| Marcar "sólo referencia" | ✔ | ✔ | |
| Archivar / eliminar un material | ✔ | — | **A confirmar** con `08-alumnos.md`: es una acción destructiva sobre el corpus. |

---

## 9. Cambios en el modelo

### 9.1 `Material` (`:core`)

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

    // ── Nuevos ───────────────────────────────────────────────
    /** Cómo lo usa el RAG. */
    val usage: MaterialUsage = MaterialUsage.CITABLE,
    /** Diagnóstico de la última indexación (null = nunca indexado). */
    val indexStats: MaterialIndexStats? = null,
    /** Id del material del que salió (si el docente lo dividió). */
    val splitFrom: String = "",
    /** Materiales que reemplazaron a este (si fue archivado por división). */
    val supersededBy: List<String> = emptyList(),
)
```

```kotlin
/** Cómo usa el RAG un material. */
@Serializable
enum class MaterialUsage(val label: String, val description: String) {
    CITABLE(
        "Citable",
        "Se indexa y el modelo puede citarlo.",
    ),
    REFERENCE(
        "Sólo referencia",
        "Se indexa como contexto, pero el modelo no debe citarlo.",
    ),
    ARCHIVED(
        "Archivado",
        "Fuera del índice. Queda en la lista para el docente.",
    ),
}
```

### 9.2 `MaterialIndexStats` (`:core`, nuevo)

```kotlin
@Serializable
data class MaterialIndexStats(
    /** Fragmentos que produjo la indexación. */
    val chunkCount: Int = 0,
    /** Tamaño medio de fragmento, en caracteres. */
    val avgChunkChars: Int = 0,
    val minChunkChars: Int = 0,
    val maxChunkChars: Int = 0,
    /** Fragmentos cuyo encabezado NO es el nombre del archivo. */
    val chunksWithHeading: Int = 0,
    /** Encabezados distintos. */
    val distinctHeadings: Int = 0,
    /** Caracteres indexados. */
    val textLength: Int = 0,
    /** Líneas del texto. */
    val lineCount: Int = 0,
    /** Parámetros usados. */
    val chunkSize: Int = 0,
    val chunkOverlap: Int = 0,
    /** Epoch ms de la indexación. */
    val indexedAt: Long = 0L,
    /** El texto no tiene saltos de línea (típico de un PDF copiado). */
    val flatText: Boolean = false,
)
```

### 9.3 `ChatMessage` y `CitationStats` (`:core`)

```kotlin
@Serializable
data class ChatMessage(
    …,
    /** Etiquetas de las fuentes recuperadas (compatibilidad). */
    val sources: List<String> = emptyList(),
    /** Fuentes recuperadas, con el material al que pertenecen. */
    val sourceRefs: List<SourceRef> = emptyList(),
    /** Verificación de citas (sólo en respuestas del asistente). */
    val citation: CitationStats? = null,
    …
)
```

```kotlin
@Serializable
data class SourceRef(
    /** Posición 1-based: es el número de la cita ([1] = la primera). */
    val index: Int = 0,
    /** Etiqueta legible ("cátedra: Apuntes-01.pdf"). */
    val label: String = "",
    /** Id del material de la cátedra ("" si la fuente es externa). */
    val materialId: String = "",
    /** Encabezado del fragmento (el "#" del Markdown). */
    val heading: String = "",
    /** ¿El modelo puede citarlo? */
    val citable: Boolean = true,
    /** Caracteres del fragmento. */
    val chars: Int = 0,
)
```

**Invariante:** `sourceRefs[i].label == sources[i]` y
`sourceRefs.size == sources.size`. `sources` se conserva porque
`07-chats.md` y `SourceUsageList` lo usan; `sourceRefs` es la versión con
identidad. En el futuro `sources` puede derivarse de `sourceRefs`
(`val sources get() = sourceRefs.map { it.label }`), pero no se cambia ahora
para no romper los stores existentes.

### 9.4 `Course` (`:core`)

```kotlin
data class Course(
    …,
    /** Tamaño del chunk al indexar los materiales. */
    val chunkSize: Int = 1200,
    /** Solapamiento entre fragmentos. */
    val chunkOverlap: Int = 150,   // ← nuevo (RagConfig.chunkOverlap = 150)
    …
)
```

Hoy el solapamiento vive sólo en `RagConfig` (`:backend`) y la cátedra no lo
controla; sin este campo el diagnóstico de indexación no puede reproduci-
bles los parámetros reales.

### 9.5 `Chunk` y `Doc` (`:backend`)

```kotlin
data class Doc(val source: String, val path: String, val text: String, val docId: String = "")
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    val docId: String = "",
    val citable: Boolean = true,
) { val label get() = "$source: $path" }
```

### 9.6 `SourceUsage` (`:core`)

```kotlin
@Serializable
data class SourceUsage(
    val label: String,
    /** Veces que el material fue recuperado (fragmentos). */
    val times: Int,
    /** Id del material ("" si no es de la cátedra). */
    val materialId: String = "",
    /** Citas respaldadas / citas totales a esta fuente. */
    val supported: Int = 0,
    val total: Int = 0,
)
```

`times` conserva la semántica actual (apariciones de la etiqueta), que **es**
"veces Recovery". El panel "Materiales" usa la tabla rica; `SourceUsageList`
del resumen sigue funcionando.

### 9.7 Los tipos de analítica (`:core`, nuevo `MaterialAnalytics.kt`)

```kotlin
@Serializable
data class MaterialAnalytics(
    val courseId: String,
    val perMaterial: List<MaterialMetrics> = emptyList(),
    val perUnit: List<UnitMetrics> = emptyList(),
    val alerts: List<MaterialAlert> = emptyList(),
    val totals: MaterialTotals = MaterialTotals(),
    /** Temas que los alumnos preguntaron y ningún material cubre. */
    val gaps: List<TopicGap> = emptyList(),
)

/** Aviso accionable del panel. */
@Serializable
data class MaterialAlert(
    val kind: AlertKind,
    val severity: IssueSeverity = IssueSeverity.WARNING,
    /** Materiales involucrados en el aviso. */
    val materialIds: List<String> = emptyList(),
    /** Titular: "3 materiales aparecen en respuestas sin respaldo". */
    val title: String = "",
    /** Detalle: qué pasó y qué hacer. */
    val detail: String = "",
)

/** Tipos de aviso del panel. */
@Serializable
enum class AlertKind(val label: String) {
    UNGROUNDED("Respuestas sin respaldo"),
    NEVER_RETRIEVED("Nunca consultado"),
    NEVER_CITED("Recuperado pero nunca citado"),
    INDEXING("Problemas de troceado"),
    DUPLICATE("Material duplicado"),
    UNTAGGED("Materiales sin unidad"),
    COVERAGE_GAP("Tema sin cobertura"),
    LOW_RETRIEVAL("Poco consultado"),
}

/** Tema que los alumnos preguntaron y que ningún material cubre. */
@Serializable
data class TopicGap(
    /** Términos frecuentes del tema ("" si no se pueden mostrar). */
    val terms: List<String> = emptyList(),
    /** Respuestas sin respaldo que no encuentran su contenido. */
    val answers: Int = 0,
    /** ¿El docente puede ver el texto de esas respuestas? */
    val termsVisible: Boolean = true,
)

@Serializable
data class MaterialTotals(
    val materials: Int = 0,
    val retrievals: Int = 0,
    val citations: Int = 0,
    val supported: Int = 0,
    val unsupported: Int = 0,
    val answers: Int = 0,
    val ungroundedAnswers: Int = 0,
    val neverRetrieved: Int = 0,
    val neverCited: Int = 0,
    /** Tasa micro de la cátedra. */
    val supportRate: Double = 0.0,
)

@Serializable
data class MaterialMetrics(
    val materialId: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val usage: MaterialUsage = MaterialUsage.CITABLE,
    val units: List<String> = emptyList(),
    val retrievals: Int = 0,
    val answerHits: Int = 0,
    val citations: Int = 0,
    val supported: Int = 0,
    val unsupported: Int = 0,
    val citingAnswers: Int = 0,
    val ungroundedAnswers: Int = 0,
    /** Tasa cruda de respaldo (0..1). */
    val supportRate: Double = 0.0,
    /** Tasa ajustada por encogimiento: la que ordena la tabla. */
    val adjustedSupportRate: Double = 0.0,
    /** Proporción de respuestas sin respaldo entre las que citan el material. */
    val ungroundedAnswerRate: Double = 0.0,
    /** Respuestas en las que este material es el único culpable. */
    val blamedAnswers: Int = 0,
    /** Culpa por tipo (sólo los que aplican). */
    val blameChunking: Int = 0,
    val blameRetrieval: Int = 0,
    val blameThinContent: Int = 0,
    val blameWrongSource: Int = 0,
    val blameModel: Int = 0,
    val blameGap: Int = 0,
    /** Señales del material. */
    val textLength: Int = 0,
    val chunkCount: Int = 0,
    val avgChunkChars: Int = 0,
    val charsPerRetrieval: Int = 0,
    /** Problema de indexación detectado (null = todo bien). */
    val indexIssue: IndexIssue? = null,
    /** Id del material duplicado ("" si no hay duplicado). */
    val duplicateOf: String = "",
    /** ¿Subido hace poco? (no hay datos suficientes para juzgarlo). */
    val recentlyUploaded: Boolean = false,
    /** La atribución no se puede calcular (mensajes sin detalle). */
    val attributionAvailable: Boolean = true,
)
```

```kotlin
/** A quién se le atribuye un fallo. */
@Serializable
enum class BlameKind(val label: String, val weight: Int) {
    CHUNKING("Troceado", 5),
    RETRIEVAL("Recuperación", 4),
    THIN_CONTENT("Contenido escueto", 3),
    WRONG_SOURCE("Fuente equivocada", 2),
    GAP("Falta material", 1),
    MODEL("Modelo", 0),
}

/** Problemas de indexación detectables. */
@Serializable
enum class IndexIssue(val label: String, val severity: IssueSeverity) {
    NO_TEXT("Sin capa de texto", IssueSeverity.CRITICAL),
    SINGLE_HUGE_CHUNK("Un solo fragmento gigante", IssueSeverity.CRITICAL),
    CHUNKS_TOO_BIG("Fragmentos demasiado grandes", IssueSeverity.WARNING),
    CHUNKS_TOO_SMALL("Fragmentos demasiado chicos", IssueSeverity.WARNING),
    NO_HEADINGS("Sin encabezados", IssueSeverity.WARNING),
    FLAT_TEXT("Texto sin estructura", IssueSeverity.WARNING),
    LOW_RETRIEVAL("Poco consultado", IssueSeverity.WARNING),
    DUPLICATE("Duplicado", IssueSeverity.WARNING),
}

/** Gravedad de un problema o de un aviso. */
@Serializable
enum class IssueSeverity(val label: String) {
    INFO("Info"),
    WARNING("Aviso"),
    CRITICAL("Crítico"),
}
```

### 9.8 ¿Dónde vive el analizador?

**Decisión: el analizador de materiales vive en `:backend`
(`rag.analytics.MaterialAnalyticsAnalyzer`), no en `:core`.**

Motivo: necesita el **corpus** (re-trocear con `Chunker`) y la **semántica del
verificador** (`CitationVerifier`, `keyTerms`, cobertura). `:core` no puede
depender de `:backend` (la dependencia va al revés: `rag.retrieval` ya importa
`kitoru.domain.Material`). Los **tipos** (`MaterialAnalytics`,
`MaterialMetrics`, …) sí viven en `:core`, porque son datos puros y la UI los
consume sin conocer el RAG.

```kotlin
// :backend/src/commonMain/kotlin/rag/analytics/MaterialAnalyticsAnalyzer.kt
object MaterialAnalyticsAnalyzer {
    fun analyze(
        course: Course,
        materials: List<Material>,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
    ): MaterialAnalytics
}
```

Además, `AnalyticsAnalyzer.analyze()` (`:core`) recibe `materials` para poder
resolver los ids en `topSources` y para **no contar** los hilos de los docentes
(hoy `sourceCounts` recorre **todos** los hilos, incluidos los del titular: es
un bug que esta tabla destapa).

### 9.9 Persistir o calcular al vuelo — la justificación (D5)

**Se calcula al vuelo.** Los contadores por material **no** se guardan en
`Material`.

| Razón | Detalle |
|---|---|
| **Visibilidad mutable** | Si un alumno pasa de `FULL` a `NONE`, sus mensajes deben desaparecer de todos los agregados. Un contador guardado en `Material` no se puede descontar. Lo mismo con `METRICS_ONLY`. |
| **El material cambia** | Renombrar, dividir, archivar o reindexar invalida los contadores. Con cálculo al vuelo, el resultado siempre corresponde al estado actual. |
| **Pureza y testeabilidad** | Regla 3 del proyecto: la analítica es una función pura. `MaterialAnalyticsAnalyzer.analyze(...)` con la misma entrada da siempre la misma salida: se testea sin disco ni red. |
| **Volumen** | Una cátedra tiene cientos o miles de mensajes, no millones: una pasada O(mensajes + corpus) es instantánea. |
| **Sin estado obsoleto** | No hay "contadores que hay que migrar" cuando cambia la fórmula. |

**Lo que sí se persiste** es lo que describe el *material*, no la conversación:
`usage`, `indexStats`, `splitFrom`, `supersededBy` (§9.1, §9.2). Esos datos
sobreviven a los cambios de visibilidad y se actualizan en el momento en que
ocurre el evento (subida, división, reindexado).

---

## 10. Compatibilidad hacia atrás

Los mensajes guardados antes de este cambio **no** tienen `sourceRefs` ni
`citation.details`. El panel no debe romperse:

| Dato viejo | Qué se hace |
|---|---|
| `sources` con etiquetas, sin `sourceRefs` | Se resuelve el material **por título**: se busca `Material.title == etiqueta.substringAfter(": ")`; si hay exactamente uno, se usa su id; si hay varios o ninguno, la fuente queda **sin material resuelto** (`materialId = ""`) y se cuenta aparte como "fuentes externas". |
| `citation` sin `details` | Se sabe **cuántas** citas fallaron, pero no **a qué material**. Se muestran los conteos; la atribución se marca como no disponible (`attributionAvailable = false`) y el panel muestra: *"Las conversaciones anteriores a esta versión no guardan el detalle de las citas: el diagnóstico por material empieza desde hoy."* |
| `Material` sin `indexStats` | Se calcula al vuelo (`IndexDiagnostics.analyze`) y se marca como *"sin diagnóstico guardado"*; la acción "Reindexar" lo deja persistido. |
| `Material` sin `usage` | `CITABLE` por defecto. |

**El panel no dice "0 %" cuando no hay datos**: distingue *"sin citas"* (—) de
*"0 % de respaldo"* (0 % con ≥1 cita). Un material con 0 citas tiene tasa
"—" y **no** entra en la media ponderada.

---

## 11. Casos borde y tests

`backend/src/desktopTest/kotlin/rag/analytics/MaterialAnalyticsAnalyzerTest.kt`
(JUnit 5, como el resto de `:backend`):

| Test | Qué verifica |
|---|---|
| `material nunca recuperado` | `neverRetrieved = true`, aparece en el aviso correspondiente. |
| `muestra chica se encoge` | 1 cita, 0 respaldadas, `prior = 0,75` → `adjustedSupportRate = 0,625` (no 0). |
| `orden por tasa ajustada` | El material con 12 citas y 33 % queda primero, no el de 1 cita y 0 %. |
| `atribución por troceado` | La cita fallada tiene sus términos en otro fragmento del mismo material → `blameChunking = 1`, `blamedAnswers = 1`. |
| `atribución al modelo` | Los términos no están en ningún fragmento → `blameModel = 1`, ningún material culpado. |
| `culpable único` | Una respuesta con dos citas falladas de distinto tipo → una sola `blameKind` (la de mayor peso). |
| `visibilidad NONE excluida` | Los mensajes de un alumno `NONE` no aportan citas ni recuperaciones. |
| `hilos del docente excluidos` | Los chats del titular no cuentan como uso del material. |
| `material REFERENCE fuera de las tasas` | `usage = REFERENCE` no aparece en "nunca consultado" ni en las tasas. |
| `material invisible` | `visible = false` no se indexa: 0 recuperaciones y aviso "nunca consultado" sólo si `visible` (si está oculto a propósito, no es un problema). |
| `sin citas` | `supportRate` es `null`/`—`, no 0. |
| `diagnóstico de troceado` | Texto de 12.400 caracteres en 3 líneas → `CHUNKS_TOO_BIG` + `FLAT_TEXT`. |
| `encabezados perdidos` | Texto sin `#` → `chunksWithHeading == 0` → `NO_HEADINGS`. |
| `duplicados` | Dos materiales con el mismo texto → `duplicateOf`. |

Además, en `:core` (`AnalyticsAnalyzerTest`, si existe o se crea):
`topSources` ya no cuenta los hilos del docente y resuelve `materialId`.

---

## 12. Riesgos y límites conocidos

1. **Sin `sourceRefs`/`details` no hay panel.** Es el bloqueo principal: hasta
   que el cliente del alumno guarde el detalle de las citas, sólo se pueden
   dar métricas de recuperación por etiqueta. Por eso §9.3 es parte de este
   diseño y no un extra.
2. **La atribución es una heurística, no una prueba.** Depende del umbral
   `MIN_WORD_COVERAGE = 0,5` del verificador. Por eso la interfaz habla de
   "causa probable" y no de "causa". Un material con `blameChunking` alto
   merece una mirada, no una sentencia.
3. **El re-troceado en cada análisis** es O(caracteres del corpus). Se cachea
   por `(materialId, chunkSize, chunkOverlap, hash(extractedText))`; con 14
   materiales, la caché entra holgada en memoria.
4. **El corpus se recalcula con los parámetros actuales de la cátedra**, no con
   los del momento de la indexación. Si el docente cambió `chunkSize` después,
   el diagnóstico refleja el estado *actual*; `indexStats.chunkSize` guarda el
   de la última indexación y la UI avisa si difieren.
5. **Los alumnos `METRICS_ONLY` recortan el detalle**, no los números: las
   métricas se calculan igual, pero sin snippets ni motivos (D10, D11).
6. **La atribución requiere el corpus completo** (incluidos materiales no
   citable) para decidir `GAP` vs `THIN_CONTENT`. Por eso el `REFERENCE` se
   incluye en la búsqueda de "dónde está el contenido" aunque no sea citable.
7. **Coordinar con `08-alumnos.md`** quién puede archivar/eliminar materiales
   (§8.5) y con `01-navegacion.md` la pestaña "Materiales" dentro de
   `AnalyticsPane` (§7.1) y el `MaterialDetail` de `MaterialsPane`, que
   debe mostrar el mismo diagnóstico de indexación.

---

## 13. Resumen en cinco líneas

1. El panel "Materiales" cruza **veces Recovery** (`SourceUsage.times`),
   **citas**, **respuestas sin respaldo** y **tasa de respaldo por material**,
   con la tasa ajustada por encogimiento (k=5) para que un material con una
   sola cita no acuse a nadie.
2. La atribución es de **un solo culpable por respuesta** (escalera de 6
   peldaños: modelo / contenido escueto / falta material / troceado / fuente
   equivocada / recuperación), sobre el detalle de citas que hoy se descarta
   (`CitationStats.details`) y sobre el id estable del material (`SourceRef.materialId`).
3. El diagnóstico de indexación re-trocea el material con el `Chunker` real y
   avisa con frases accionables ("se indexó en 3 fragmentos de 4.100
   caracteres: conviene subirlo más troceado o como Markdown").
4. Las correcciones son del panel: **reindexar**, **editar el texto
   extraído**, **dividir** y **marcar "sólo referencia"** (`Material.usage`).
5. Las métricas se **calculan al vuelo** desde los mensajes (función pura,
   respeta la visibilidad); en `Material` sólo se persisten `usage` e
   `indexStats`.
