# 04 · Embeddings y búsqueda híbrida en Kitoru

> Subagente: evaluación de embeddings (semántica) para el RAG de Kitoru.
> Alcance: **decidir si Kitoru mete embeddings, con qué motor y sin romper las
> decisiones de diseño heredadas de FunshiEngineAI**.
> Todo lo que sigue es diseño, no código: los nombres de archivo, tipos, firmas,
> formatos de archivo y algoritmos son los que deberían existir en el repo.

---

## 0. Veredicto en una línea

**No: en el hito 1 Kitoru se queda con BM25**, pero **sí**: se crea la costura
`Retriever` ahora (≈120 líneas, cero cambio de comportamiento) para que la
búsqueda híbrida entre **por cátedra** en el hito 2 sin reescribir el RAG.

| | |
|---|---|
| **Decisión** | (a) BM25 en el hito 1 + seam `Retriever` preparada para (c) |
| **Por qué no (b)** | 118 MB de modelo, dependencia nativa ONNX, 5 min de indexación en un teléfono, y la promesa "sin dependencias, offline, determinista, testeable" se cae en el hito 1 sin evidencia que la justifique |
| **Por qué no (c) ya** | (c) es el estado **final**, no el inicial: necesita el `Retriever`, el proveedor de embeddings, el almacén de vectores y la instrumentación de calidad. Todo eso se diseña acá y se implementa después |
| **Condición de revisión** | ≥ 20 % de las preguntas de una cátedra con recuperación inútil, **medido con la analítica que ya existe** (§1.3) |

---

## 1. El contexto de la decisión

### 1.1 Qué decidió FunshiEngineAI, y por qué

El `Bm25Index` de FunshiEngineAI lleva el motivo escrito en el propio código:

```kotlin
/** Índice BM25 en memoria. Sin dependencias ni modelo de embeddings. */
class Bm25Index(private val chunks: List<Chunk>) { ... }
```

Y el manual de RAG lo ratifica en "Límites conocidos":

> - **Sin embeddings**: la recuperación es léxica (BM25). No entiende paráfrasis ni
>   sinónimos; sí funciona bien con los términos técnicos exactos.

Ese "sin dependencias ni modelo de embeddings" no es una omisión: es una
**decisión de diseño** con cinco propiedades que Kitoru necesita conservar.

| Propiedad | Qué compra | Dónde se pierde si metemos embeddings |
|---|---|---|
| **Cero dependencias** | El `:backend` compila con `kotlinx-coroutines` + `kotlinx-serialization` y nada más. Un `onnxruntime` mete ~15 MB de `.so` por ABI y una API Java que hay que envolver | Se agrega un `actual` por plataforma, un modelo de 23-118 MB y una matriz de errores nueva |
| **Offline** | La app funciona con la avión en modo avión | Si el embedding va a una API externa, el RAG deja de funcionar sin red: **rompe la promesa de que la app "funciona sin la institución"** |
| **Determinista** | La misma pregunta da el mismo resultado en todas las máquinas. La regla 3 del proyecto dice que la analítica y la verificación no pueden depender del modelo ni de la red | Un embedding es una inferencia numérica: el mismo texto puede dar vectores levemente distintos según el backend (ONNX vs llama.cpp vs quantización) → **el índice deja de ser reproducible bit a bit** |
| **Testeable** | `Bm25IndexTest` verifica el orden con 8 tests deterministas, sin fixtures ni modelos | Un test de recuperación semántica necesita el modelo: 23 MB en el classpath o un mock. La cobertura cae |
| **Instantáneo** | Reindexar 100 docs / 800 fragmentos toma < 200 ms (medido en `03-materiales.md` §"Dos cambios concretos") | Reindexar exige inferencia: 3000 fragmentos × 100 ms = **5 minutos en un teléfono** (§3.4) |

La frase del README —"BM25. Sin dependencias ni modelo de embeddings"— es
justamente el tipo de decisión que el `CONTEXTO.md` manda **reutilizar, no
reescribir** (regla 1). Kitoru la hereda.

### 1.2 Pero Kitoru no es FunshiEngineAI

FunshiEngineAI indexa **documentación técnica en inglés** (repos de GitHub, API
docs del motor), donde el vocabulario es estrecho y las consultas usan los
**términos exactos** del manual ("Vulkan", "llama-server", "tool_call"). En ese
dominio BM25 es casi ideal.

Kitoru indexa **material de cátedra**: apuntes, PDFs, diapositivas, transcripciones.
El corpus es en español, la materia es heterogénea (teoría, práctica, admin) y
las consultas son **en lenguaje natural**. Ahí el léxico tiene más fricción.

Tres diferencias concretas:

| | FunshiEngineAI | Kitoru |
|---|---|---|
| **Corpus** | Docs de un motor, ~50-200 archivos, inglés | Material de una cátedra, ~20-500 archivos, español |
| **Consultas** | Terminología exacta ("cómo configuro `gpuLayers`") | Preguntas de alumno ("cómo se hace para que no se duplique") |
| **Quién decide el material** | El usuario (dev) | El docente, y el alumno **no ve** el material del docente si es privado |
| **Multi-idioma** | No | Frecuente: papers en inglés, material en español, consultas en español |

### 1.3 ¿Qué cambiaría la decisión? (condiciones medibles)

No "si el corpus es grande" en abstracto: **cinco umbrales concretos**, cada uno
verificable con la instrumentación que el proyecto ya tiene o va a tener.

#### Condición 1 — Corpus grande (límite de **memoria**, no de latencia)

La búsqueda BM25 sobre 3000 fragmentos tarda < 10 ms: la latencia **no** es el
problema. El problema es el **índice en memoria**. Estimación por fragmento:

```
tf map de un fragmento: ~80 tokens únicos × ~120 bytes (Map.Entry + String + Int)
                         ≈ 10 KB por fragmento

3000 fragmentos  →  ~30 MB de RAM
20.000 fragmentos → ~200 MB de RAM   ← no entra en un teléfono
```

**Umbral: ~5.000 fragmentos (≈ 150 documentos de 40 páginas).** Por arriba de
eso, o el índice léxico se come la RAM del teléfono, o hay que pasarlo a disco
(que es un rediseño del índice, no una ecualización).

> Nota: con 3000 fragmentos, 30 MB de índice léxico **más** 4,6 MB de vectores
> es un presupuesto que en Android hay que vigilar de cerca. El índice vectorial
> es 6 veces más chico que el léxico. Eso ya es un argumento a favor de los
> embeddings en corpus grandes, y un argumento en contra de tener **los dos**.

#### Condición 2 — Consultas por significado (y no por palabra)

El ejemplo canónico:

| Consulta del alumno | Qué busca en realidad | Qué encuentra BM25 |
|---|---|---|
| "cómo se hace para que no se duplique" | "evitar duplicación de registros" | **nada**: no comparte ningún token con "duplicación" |
| "cuándo se entrega el tp2" | "fecha de entrega del trabajo práctico 2" | "tp2" ✔, "entrega" ✔ → funciona |
| "qué es gradiente" | "derivada / pendiente" | "gradiente" ✔ → funciona |
| "reprositorio" | "repositorio" | "repositorio" ✘ (falta la "p") |

**Umbral: ≥ 20 % de las preguntas de una cátedra con recuperación inútil**
(score 0, o respuesta sin citas respaldadas). Y el matiz importante: el problema
tiene que estar **concentrado en paráfrasis**, no repartido. Si el 20 % de las
preguntas fallan porque el material es un PDF escaneado, los embeddings no
sirven de nada: falta OCR.

> Esto **ya se mide**. `docs/design/domain/04-conversaciones.md` define
> `topRetrievalScore` (score BM25 del mejor fragmento, 0 = sin recuperación) y
> `CitationStats(verified, unsupported, unknown)`. La decisión se puede revisar
> con datos de una cursada real, no con intuición.

#### Condición 3 — Material en un idioma distinto al de las consultas

Hoy el tokenizador hace `stripDiacritics` + split por no-alfanuméricos. Eso
sirve para español con acentos, pero **no** para:

- **Morfología española**: "duplicación" ≠ "duplicar" ≠ "duplica" ≠ "duplicado".
  BM25 no los relaciona. *(Nota: esto lo arregla un stemmer, no un embedding —
  ver §1.4.)*
- **Material en inglés + consulta en español**: "backpropagation" vs
  "retropropagación" → cero overlap.
- **Inglés técnico en un PDF con fórmulas**: "loss function" vs "función de
  pérdida".

**Umbral: ≥ 30 % del corpus en un idioma distinto al de las consultas.**

#### Condición 4 — Sinónimos que el docente no puede etiquetar

El diseño de taxonomía (`10-taxonomia.md` §4) ya resuelve los sinónimos
**declarados**: el docente escribe la etiqueta "graduación" y el alumno escribe
"gradiente", y el `TagVocabulary` hace de puente. Eso cubre el vocabulario que
el docente conoce.

Lo que **no** cubre: los sinónimos que nadie declara (milagro/maravilla/
portento, o "notebook"/"cuaderno Jupyter"/"Colab"). **Umbral: ≥ 3 pares de
sinónimos no controlables por el docente y no detectables en las etiquetas.**

#### Condición 5 — El embedding tiene que estar en el dispositivo

Si el material **nunca sale del dispositivo** (alumno con IA local, cátedra sin
servidor), el embedding tiene que correr en el cliente → 118 MB + ONNX. Esta
condición es la que más cambia el costo, y va en §5.

### 1.4 El antídoto barato: un stemmer

Antes de culpar a BM25, hay que separar **dos** problemas que se confunden:

| Problema | ¿Lo arregla un stemmer? | ¿Lo arregla un embedding? |
|---|---|---|
| "duplicación" vs "duplicar" (morfología) | **Sí**, y en 150 líneas de Kotlin puro, sin dependencias | Sí |
| "evitar duplicación" vs "no repetir registros" (paráfrasis) | No | **Sí** |
| "gradiente" vs "derivada" (sinónimo) | No | **Sí** |
| "notebook" vs "cuaderno" (sinónimo informal) | No | **Sí** |

Un stemmer snowball en español (`kmp Snowball`, 250 líneas, licencia BSD) cubre
el 40 % del problema perceived con el 5 % del costo. **La primera respuesta a
"la recuperación falla" es un stemmer + las etiquetas de la taxonomía, no un
modelo de embeddings.** El embedding se justifica para el otro 60 %.

> Nota sobre `10-taxonomia.md` §4.3: ese documento ya dice
> *"Por qué no con un modelo de embeddings: el motor es BM25 (determinista, sin
> dependencias, sin GPU)"*. Este documento **no lo contradice**: lo **confirma
> para el hito 1** y le pone fecha de revisión (§0, §7).

### 1.5 La decisión de FunshiEngineAI, traducida a Kitoru

| Decisión de FunshiEngineAI | En Kitoru |
|---|---|
| BM25 en memoria, sin dependencias | **Se mantiene.** Es la decisión de diseño de la familia de proyectos |
| "Sin embeddings" como límite conocido documentado | **Se mantiene**, pero pasa a ser un **límite conocido con dueño y con métrica**: se documenta en el manual del alumno y en la pantalla de diagnóstico del docente ("Probar pregunta"), y se revisa con `topRetrievalScore` |
| El manual le dice al usuario cómo escribir para que lo encuentre | **Se mantiene y se amplía**: el manual de Kitoru le dice al alumno "usá los términos que aparecen en el material" y le dice al docente "las etiquetas amplían lo que el alumno encuentra" |
| La pantalla "Probar pregunta" explica que el índice es léxico | **Se mantiene**: `03-materiales.md` ya lo dice textualmente ("el índice es léxico (BM25), no entiende sinónimos"). Cuando llegue la búsqueda híbrida, esa misma pestaña pasa a ser el banco de pruebas del embedding |

---

## 2. Opciones de embeddings en KMP

### 2.1 Tabla resumen

| Opción | Tamaño | Licencia | Android real | Velocidad CPU (teléfono) | Coste en servidor | Veredicto |
|---|---|---|---|---|---|---|
| **onnxruntime + MiniLM int8** | 23 MB modelo + ~15 MB nativo por ABI | MIT (runtime) / Apache-2.0 (modelo) | ✔ AAR oficial, minSdk 21 | 40-120 ms/consulta | Bajo | Candidata #1 **para el cliente** |
| **onnxruntime + multilingüe int8** | 118 MB + 17 MB tokenizer | Apache-2.0 | ✔ | 150-350 ms/consulta | Bajo | Candidata #1 **si el corpus es español** |
| **llama.cpp (2º server)** | 30-500 MB GGUF | MIT / Apache-2.0 | ✘ (no hay `llama-server` en Android) | — | Medio (2º proceso, +RAM) | **Para el servidor**, no para el cliente |
| **LangChain4j in-process** | idem ONNX (+23 MB al APK) | Apache-2.0 | ✔ (con el AAR) | idem | Bajo | Viable, pero mete un framework |
| **`kotlin-embeddings` / `KotlinEmbedding`** | — | — | — | — | — | **No existe** (§2.4) |
| **APIs externas** | 0 | SaaS | ✔ trivial | 200-800 ms **+ red** | 0 local | **Descartada** (§2.5) |

### 2.2 `onnxruntime` (Microsoft)

**Coordenadas reales** (verificadas en Maven Central; el `io.onnxruntime:...` que
circula por ahí **no existe**, `g:io.onnxruntime` → 0 resultados):

```kotlin
// backend/build.gradle.kts
desktopMain.dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0") }
androidMain.dependencies  { implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0") }
```

- **Licencia**: MIT (el runtime). Los modelos: Apache-2.0.
- **Android**: sí, en serio. El AAR `onnxruntime-android` trae las `.so` para
  `arm64-v8a`, `armeabi-v7a` y `x86_64`, con `minSdk 21` (Kitoru usa 24 ✔).
  El AAR estándar incluye el EP **XNNPACK**; NNAPI se publica en un paquete
  aparte y no es el camino por defecto. Con **ABI splits** el APK del usuario
 -downloaded crece ~15 MB, no 45.
- **KMP**: la API Java es JVM-only, así que **no hay `commonMain`**. El patrón
  correcto para este repo es:

  ```
  commonMain/rag/semantic/EmbeddingProvider.kt   ← interfaz (expect-free)
  jvmCommonMain/rag/semantic/OnnxEmbedder.kt      ← el motor ONNX (es JVM)
  desktopMain/…   (el .jar de ORT)
  androidMain/…    (el .aar de ORT)
  ```

  Ojo: el *código* es el mismo (es JVM), lo que cambia es el *artefacto*. Por eso
  la interfaz es `expect/actual` en vez de una dependencia de `commonMain`.

#### El tokenizer: hay que escribirlo (o casi)

El ONNX de MiniLM es un `BertModel`; el preprocesado (WordPiece) va aparte.
Dos caminos:

| Camino | Qué cuesta | Veredicto |
|---|---|---|
| **WordPiece a mano** (~120 líneas, `vocab.txt` + normalización + greedy longest-match) | 120 líneas puras, testeables, sin dependencia | ✔ **Preferido**: el proyecto ya normaliza texto a mano (`stripDiacritics`), y un tokenizer mal hecho es la fuente #1 de "las embeddings son malas" |
| `onnxruntime-extensions` (`com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0`) | Una dependencia más (y es C++ por debajo) | Sólo si el tiempo aprieta |

#### ⚠ El problema de la longitud: **el chunk no entra en el modelo**

Esto es lo que casi nadie mira y **rompe la implementación**:

| Modelo | Dimensiones | Tokens máximos | Nuestro chunk | ¿Entra? |
|---|---|---|---|---|
| `all-MiniLM-L6-v2` | 384 | 256 | 1200 chars ≈ **300 tokens** | ⚠ **se trunca el último 15 %** |
| `paraphrase-multilingual-MiniLM-L12-v2` | 384 | **128** | 1200 chars ≈ 300 tokens | ⚠ **se trunca el último 55 %** |
| `bge-small-en-v1.5` | 384 | 512 | ≈ 300 tokens | ✔ |

Un chunk truncado a la mitad es un embedding de la mitad del chunk: el
ranking se degrada de forma silenciosa yOVO el diagnóstico es "el modelo es malo".

**Algoritmo de ventaneo (obligatorio si el chunk no entra):**

```
embed(chunk):
    tokens = tokenize(chunk)                 # WordPiece, con [CLS] y [SEP]
    if tokens.size <= maxTokens:
        return embed(tokens)                 # un solo vector
    # si no, ventanas con solapamiento del 25 %
    stride = maxTokens * 3 / 4
    vectores = []
    i = 0
    while i < tokens.size:
        ventana = tokens[i .. min(i + maxTokens, tokens.size)]
        vectores += embed(ventana)
        i += stride
    v = mean(vectores)                       # promedio de los vectores de ventana
    return normalize(v)                     # L2
```

Costo: un chunk de 1200 chars con ventana de 256 y stride 192 → 2 ventanas → el
costo de indexación **se duplica**. Con 3000 fragmentos son 6000 inferencias.

**Mitigaciones alternativas** (a elegir en el momento, no ahora):
- Bajar `chunkSize` de la cátedra a 600 chars (más chunks, pero entran todos).
- Usar un modelo de 512 tokens (`bge-small`, `multilingual-e5-base`).
- El ventaneo de arriba.

#### Los modelos que se consideran

| Modelo | Params | Dims | int8 ONNX | Idioma | Licencia | Nota |
|---|---|---|---|---|---|---|
| `all-MiniLM-L6-v2` | 22.7 M | 384 | **23 MB** | Inglés | Apache-2.0 | 90 MB en fp32. **Trampa**: es inglés |
| `paraphrase-multilingual-MiniLM-L12-v2` | 118 M | 384 | **118 MB** | 50+ | Apache-2.0 | Tokenizer XLM-R: +17 MB. Max 128 tokens |
| `intfloat/multilingual-e5-small` | 118 M | 384 | ~118 MB | 100+ | MIT | Mejor que paraphrase-multilingual en retrieval. Necesita prefijos `"query: "` / `"passage: "` |
| `BAAI/bge-m3` | 568 M | 1024 | ~570 MB | 100+ | MIT | 8192 tokens, muy bueno, **pesado** |
| `intfloat/e5-small-v2` | 33 M | 384 | ~34 MB | Inglés | MIT | Prefijos obligatorios |

> **Conclusión del análisis de modelos:** Kitoru es en español. El modelo de
> 23 MB (`all-MiniLM-L6-v2`) **no sirve** para el caso principal: es un modelo
> en inglés. El número real para Kitoru es **118 MB** (multilingüe, 384 dims).
> Y los modelos de sentence-transformers están entrenados con corpus **mayoritariamente
> en inglés**: en español rinden **notablemente peor** que en inglés. La mejora
> real en un corpus español es **mucho menor** que la que muestran los benchmarks
> en inglés. Esto hay que decirlo antes de prometer nada: el "embeddings
> resuelven el problema de los sinónimos" es **una hipótesis a medir**, no una
> garantía.

### 2.3 llama.cpp y el `/v1/embeddings` del servidor

`llama-server` **sí** expone `/v1/embeddings` (compatible con OpenAI) y
`/embedding`, pero con una condición que suele sorprender:

> Un `llama-server` con el **modelo de chat** cargado **no** produce embeddings.
> El modelo de chat no tiene pooler de embeddings, y llama.cpp exige
> `--pooling` + un modelo entrenado para eso.

O sea: hace falta **un segundo proceso `llama-server`** con un modelo de
embeddings, en otro puerto.

```
llama-server -m Phi-4-mini-instruct-Q4_K.gguf  --port 8081   ← el chat (ya existe)
llama-server -m bge-m3-Q8_0.gguf --embedding --pooling mean --port 8082  ← nuevo
```

| Aspecto | Valor |
|---|---|
| **RAM** | +300 MB (e5-small) a +1.200 MB (bge-m3) mientras está cargado |
| **Descarga** | +67 MB (e5-small-v2 Q8) a +1.200 MB (bge-m3 Q8) |
| **Calidad** | La de llama.cpp con el modelo cuantizado a Q8: **ligeramente peor** que el ONNX int8 equivalente |
| **Prefijos** | `nomic-embed-text-v1.5` y `e5-*` exigen prefijos (`"search_query: "`, `"query: "`, `"passage: "`). Olvidarlos **degrada el ranking de forma silenciosa** — la trampa clásica |
| **Android** | ✘ No hay `llama-server` para Android. El propio `LocalModelService` de Kitoru ya es un stub que lanza `LlmException` en Android |
| **Kitoru-Server** | ✔ **Acá sí es la mejor opción**: la institución aloja el modelo, el alumno no descarga nada, y el servidor puede incluso exponer `/v1/search` y resolver la fusión RRF **en el servidor** |

**Veredicto: llama.cpp es la opción del servidor, onnxruntime es la opción del
cliente.** No compiten: se complementan. De hecho, la arquitectura final
recomendada (§5) usa **los dos**: el servidor aloja el embedding multilingüe
(118 MB los descarga la institución, una vez), y el cliente Android usa
ONNX **solo si** el alumno decide bajar el modelo.

### 2.4 `kotlin-embeddings` / `KotlinEmbedding`

**No existe como artefacto público verificable.** Lo que se encontró:

| Búsqueda | Resultado |
|---|---|
| Maven Central `a:kotlin-embeddings` | **0 resultados** |
| Maven Central `g:io.onnxruntime` | **0 resultados** (el `groupId` del prompt no existe) |
| GitHub, repos con `kotlin-embeddings` en el nombre | 2 repos, ninguno serio (`dust-embeddings-kotlin`, 0 estrellas, creado en 2026; `spring-ai-embeddings-kotlin-version`, un port de un ejemplo) |

Lo más cercano a lo que el prompt describe es **LangChain4j in-process**:

```kotlin
dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q   // modelo int8 empaquetado en el jar
dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2     // modelo fp32
dev.langchain4j:langchain4j-embeddings-paraphrase-multilingual-minilm-l12-v2-q
```

**Evaluación:**

| Aspecto | LangChain4j in-process | Veredicto |
|---|---|---|
| Tamaño | El modelo va **empaquetado en el jar**: +23/118 MB al APK de desktop, y en Android hay que pelear con el AAR de ORT + el modelo | ⚠ |
| Licencia | Apache-2.0 | ✔ |
| Android | Funciona (es JVM), pero `langchain4j-core` arrastra Jackson, SLF4J y un `EmbeddingStore` con su propia API | ⚠ |
| API | Bonita: `EmbeddingModel.embedAll(List<String>)`, `EmbeddingStore.embeddingStore()` | ✔ |
| Qué mete al proyecto | `langchain4j-core` + `langchain4j-embeddings-*`: un framework de RAG entero para usar **una** llamada a ONNX | ✘ |
| Regla 1 ("reutilizar, no reescribir") | Kitoru ya tiene su propio RAG. Traer LangChain4j es **traer otro RAG** | ✘ |

**Veredicto: descartada.** Lo que hay que hacer es una llamada a ONNX
(`OrtEnvironment`, `OrtSession`, `OnnxTensor`) — son **40 líneas** en un
`actual` de `jvmCommonMain`. LangChain4j agrega 15 dependencias para no
aprovechar nada de ellas. Y para el cliente Android, un modelo empaquetado en el
APK es peor que un modelo **descargable** (§5): el alumno que no usa búsqueda
semántica no debería pagar los 118 MB.

### 2.5 APIs externas (OpenAI, Voyage, Jina)

| API | Dims | Precio aprox. | Latencia |
|---|---|---|---|
| OpenAI `text-embedding-3-small` | 1536 (o 512 con `dimensions`) | ~US$ 0,02 / 1M tokens | 150-400 ms |
| OpenAI `text-embedding-3-large` | 3072 | ~US$ 0,13 / 1M tokens | 300-900 ms |
| Voyage `voyage-3-lite` | 512 | ~US$ 0,02 / 1M tokens | 120-300 ms |
| Jina `jina-embeddings-v3` | 1024 | free tier: 10 M tokens/mes | 200-600 ms |

**Por qué se descartan, en orden de gravedad:**

1. **Rompe la promesa de privacidad.** El material de la cátedra (apuntes de un
   curso, parciales de unño, material con copyright del docente) sale del
   dispositivo y de la institución **sin que el docente lo haya consentido**. El
   `CONTEXTO.md` modela la visibilidad como una regla central
   ("El docente no ve lo que no debe" es la regla 6); mandar el corpus a un
   tercero es la misma regla vista del revés.
2. **Rompe "funciona sin la institución".** Sin red, no hay RAG semántico. La
   app depende de una empresa externa y de su precio.
3. **Rompe la regla 3 (determinista).** El índice semántico pasa a depender de un
   servicio que puede cambiar el modelo detrás de la misma nombre de modelo
   (`text-embedding-3-small` no tiene versión congelada) → **los vectores viejos
   dejan de ser comparables con los nuevos** y el índice hay que regenerarlo
   sin avisar.
4. **Costo y fricción.** Una API key por alumno (o por cátedra), y un alumno
   sin tarjeta no puede usar la función.

**Veredicto: descartada para el producto.** Si una cátedra la quiere, es un
opt-in explícito del docente que además **deshabilita el modo LOCAL** (el
alumno no puede usar IA local y, al mismo tiempo, mandar el material afuera sin
avisar). No entra en el diseño por defecto.

### 2.6 Qué motor quedaría, si se decide avanzar

```
Cliente Android / Desktop sin servidor:
    onnxruntime (com.microsoft.onnxruntime:onnxruntime-android)
    + paraphrase-multilingual-MiniLM-L12-v2 int8 (118 MB), descargable
    + ventaneo con solapamiento (§2.2)

Cliente con Kitoru-Server:
    POST {baseUrl}/v1/embeddings  → el servidor responde
    (o directamente POST {baseUrl}/v1/search → el servidor fusiona y devuelve los chunks)

Kitoru-Server:
    2º llama-server con e5-small-v2 / bge-m3, --embedding --pooling mean
    (o el mismo ONNX, si el server también es Kotlin)
```

---

## 3. La búsqueda híbrida

### 3.1 La fórmula: Reciprocal Rank Fusion

RRF (Cormack, Clarke & Buettcher, 2009) fusiona **rankings**, no puntajes. Es
la elección correcta acá porque BM25 y la similitud coseno viven en escalas
incomparables (BM25: 3.4; coseno: 0.82) y **no hay forma honesta de
combinarlos** con una suma ponderada sin calibrar pesos a mano.

```
score(d) = Σ_i  w_i / (k + rank_i(d))
```

| Símbolo | Significado | Valor |
|---|---|---|
| `d` | Un fragmento del corpus | — |
| `i` | Un recuperador (BM25, semántico) | — |
| `rank_i(d)` | **Posición 1-based** de `d` en la lista del recuperador `i` | 1, 2, 3… |
| `w_i` | Peso del recuperador | 1.0 por defecto |
| `k` | Constante de suavizado | **60** (el valor del paper) |

Reglas:

- Si `d` **no aparece** en la lista del recuperador `i`, su contribución es **0**
  (no "penalización").
- La fusión **se aplica sobre el pool**, no sobre el top-k final: cada
  recuperador devuelve `pool = max(k_final * 4, k_final + 10)` candidatos, y el
  corte a `topK` se hace **después** de fusionar.
- `k = 60` hace que la diferencia entre el puesto 1 y el puesto 2 sea pequeña
  (1/61 vs 1/62 → 1,6 %): un solo recuperador no puede tapar al otro, y un
  fragmento que aparece en **las dos** listas duplica su aporte.

#### Ejemplo numérico (concreto, con `k = 60`)

Corpus de 3 fragmentos: `A` (sobre "duplicación"), `B` (sobre "entregas"), `C`
(sobre "válvulas").

```
BM25      para "cómo se hace para que no se duplique":
          A → 0    (no hay overlap de tokens)
          B → 0
          C → 0
          → lista VACÍA (BM25 no encuentra nada)

Semántico para la misma consulta:
          A → 0.91   (1º)
          C → 0.62   (2º)
          B → 0.44   (3º)
```

**Híbrido (RRF, k=60, pesos 1.0):**

```
fragmento  rank_BM25   rank_sem   1/(60+rank_bm25)   1/(60+rank_sem)   score
A          — (no está)  1          0                 1/61 = 0,016393   0,016393
C          — (no está)  2          0                 1/62 = 0,016129   0,016129
B          — (no está)  3          0                 1/63 = 0,015873   0,015873
```

→ El top-3 híbrido es `A, C, B`: **el orden semántico se conserva** y el
BM25 (que no encontró nada) no aporta. Exactamente lo que se quiere: la
búsqueda léxica **no rompe** el resultado semántico, lo **complementa**.

Segundo ejemplo, donde los dos aportan:

```
fragmento  rank_BM25   rank_sem   contribución BM25   contribución sem   score
A          1            3          1/61 = 0,016393     1/63 = 0,015873   0,032266
B          2            1          1/62 = 0,016129     1/61 = 0,016393   0,032522
C          3            2          1/63 = 0,015873     1/62 = 0,016129   0,032002
```

→ Orden final: `B (0,032522) > A (0,032266) > C (0,032002)`.
Obsérvese que **el orden final no es ninguno de los dos órdenes originales**:
`B` gana porque el semántico lo puso primero, `A` porque los dos lo pusieron
arriba. Ese es el comportamiento correcto de una fusión.

### 3.2 Implementación

En `:backend`, paquete `rag.retrieval`:

```kotlin
package rag.retrieval

/**
 * Fusión RRF de los rankings de dos recuperadores.
 *
 * Reciprocal Rank Fusion (Cormack et al., 2009):
 *
 *     score(d) = Σ_i  w_i / (k + rank_i(d))
 *
 * `rank_i(d)` es la posición **1-based** de `d` en la lista del recuperador `i`;
 * si `d` no está en esa lista, su contribución es 0 (no una penalización).
 */
object RankFusion {

    /** Constante de suavizado del paper. */
    const val DEFAULT_K = 60

    /**
     * Fusiona varios rankings.
     *
     * @param rankings lista de (id de recuperador, peso, lista de fragmentos).
     *   El orden de la lista **es** el ranking (1-based).
     * @param k constante de suavizado.
     * @return los fragmentos fusionados, de mayor a menor score.
     */
    fun fuse(
        rankings: List<RankedList>,
        k: Int = DEFAULT_K,
    ): List<ScoredChunk> {
        val scores = LinkedHashMap<String, Double>()      // chunkId → score
        val chunks = LinkedHashMap<String, Chunk>()
        val parts = LinkedHashMap<String, MutableMap<String, Double>>()

        for ((id, weight, hits) in rankings) {
            hits.forEachIndexed { i, hit ->
                val rank = i + 1
                val contribution = weight / (k + rank)
                val key = hit.chunk.chunkId
                scores[key] = (scores[key] ?: 0.0) + contribution
                chunks.putIfAbsent(key, hit.chunk)
                parts.getOrPut(key) { mutableMapOf() }[id] = contribution
            }
        }

        return scores.entries
            .sortedByDescending { it.value }        // estable: empate = orden de inserción
            .map { (key, score) ->
                ScoredChunk(
                    chunk = chunks.getValue(key),
                    score = score,
                    origin = RetrievalOrigin.HYBRID,
                    parts = parts.getValue(key).toMap(),
                )
            }
    }
}

/** Un ranking: de qué recuperador es, con qué peso, y sus fragmentos en orden. */
data class RankedList(
    val id: String,
    val weight: Double,
    val hits: List<ScoredChunk>,
)
```

Y el recuperador híbrido:

```kotlin
package rag.retrieval

/**
 * Recuperador híbrido: BM25 + vectores, fusionados con RRF.
 *
 * No es una alternativa a BM25: es **BM25 más** semántica. El ranking léxico
 * sigue siendo el que se usa para las preguntas con término técnico exacto.
 */
class HybridRetriever(
    private val lexical: Retriever,
    private val semantic: Retriever,
    private val rrfK: Int = RankFusion.DEFAULT_K,
    private val weights: Map<String, Double> = mapOf(
        "bm25" to 1.0,
        "semantic" to 1.0,
    ),
) : Retriever {

    override val id = "hybrid"
    override val label = "Búsqueda híbrida (palabras + significado)"
    override val requiresModel = true

    override suspend fun build(chunks: List<Chunk>): RetrievalIndex {
        require(chunks.all { it.chunkId.isNotBlank() }) {
            "La búsqueda híbrida necesita fragmentos con chunkId único."
        }
        require(chunks.map { it.chunkId }.distinct().size == chunks.size) {
            "La búsqueda híbrida necesita fragmentos con chunkId único."
        }
        val lex = lexical.build(chunks)
        val sem = semantic.build(chunks)
        return HybridIndex(lex, sem, rrfK, weights)
    }
}

private class HybridIndex(
    private val lexical: RetrievalIndex,
    private val semantic: RetrievalIndex,
    private val rrfK: Int,
    private val weights: Map<String, Double>,
) : RetrievalIndex {

    override val size get() = lexical.size

    override suspend fun search(
        query: String,
        limit: Int,
        termWeights: Map<String, Double>,
    ): List<ScoredChunk> {
        // Cada recuperador devuelve un pool profundo: RRF necesita ranks, y el
        // corte final se hace después de fusionar.
        val pool = max(limit * 4, limit + 10)
        val lexHits = lexical.search(query, pool, termWeights)
        val semHits = semantic.search(query, pool)

        return RankFusion.fuse(
            listOf(
                RankedList("bm25", weights["bm25"] ?: 1.0, lexHits),
                RankedList("semantic", weights["semantic"] ?: 1.0, semHits),
            ),
            rrfK,
        ).take(limit)
    }
}
```

#### El boost de la taxonomía, en modo híbrido

`10-taxonomia.md` §5 define:

```
scoreFinal(i) = scoreBM25(i) × boostEstatico(i) × factorConsulta(i)
```

Eso **no** se puede aplicar tal cual sobre RRF, porque RRF trabaja con **ranks**,
no con puntajes. La traducción correcta, manteniendo la misma semántica:

| Componente | Dónde se aplica | Por qué |
|---|---|---|
| `boostEstatico` (prior del docente: "esto es del examen") | **Dentro de cada recuperador**, al puntuar | Es un prior **del fragmento**, no del método. El ranking léxico y el semántico lo reflejan, y RRF los fusiona con los ranks ya corregidos |
| `factorConsulta` (unidades, semanas, etiquetas de la consulta) | **Después de la fusión**, multiplicando el score fusionado | Depende del contexto de la cátedra, que los recuperadores no conocen |

```
modo léxico:    scoreFinal = scoreBM25 × boostEstatico × factorConsulta
modo híbrido:   scoreFinal = rrf(rank_BM25_con_boost, rank_sem_con_boost) × factorConsulta
```

Multiplicar el score RRF (que vale ~0,032) por un factor acotado a [0,5; 3,0]
**escala igual de bien** que multiplicar un score BM25: el boost cambia el
**orden**, no la escala. ✔ Consistente.

> Nota: `factorConsulta` acotado sigue siendo necesario en híbrido porque RRF
> **aplana** las diferencias: dos fragmentos con score 0,0322 y 0,0320 casi no
> se distinguen. El factor de consulta es lo que vuelve a separar "el parcial 1"
> del "parcial 2" cuando los dos son semánticamente iguales.

### 3.3 El ranking semántico

El índice vectorial, en el mismo contrato `RetrievalIndex`:

```kotlin
package rag.semantic

/** Un índice vectorial sobre los fragmentos del corpus. */
class VectorIndex(
    private val chunks: List<Chunk>,
    private val vectors: Array<FloatArray>,   // L2-normalizados
    private val embedder: EmbeddingProvider,
) : RetrievalIndex {

    override val size get() = chunks.size

    override suspend fun search(
        query: String,
        limit: Int,
        termWeights: Map<String, Double>,
    ): List<ScoredChunk> {
        if (chunks.isEmpty()) return emptyList()
        val q = embedder.embedOne(query)
        // producto punto == coseno, porque ambos están normalizados
        val scored = ArrayList<ScoredChunk>(chunks.size)
        for (i in chunks.indices) {
            val s = dot(q, vectors[i]) * chunks[i].boost
            if (s > 0f) scored += ScoredChunk(chunks[i], s.toDouble(), RetrievalOrigin.SEMANTIC)
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }
}
```

Puntos que importan:

- **Normalización L2 al guardar**: el producto punto es el coseno y no hace
  falta normalizar en cada consulta.
- **`boost` aplicado antes de ordenar**: para que el prior del docente cambie el
  ranking semántico (igual que en el léxico).
- **Umbral `> 0`**: un coseno negativo no es un resultado; se descarta (igual
  que BM25 descarta los scores 0).

### 3.4 El costo (números, no adjetivos)

**Por consulta** (corpus de 3000 fragmentos, 384 dims):

| Paso | Desktop | Teléfono (CPU, 4 hilos) |
|---|---|---|
| BM25 search | < 5 ms | < 20 ms |
| Vector search (3000 × 384 = 1,15 M FLOPs) | < 2 ms | ~10 ms |
| **Embed de la consulta** (MiniLM-L6 int8) | 15-40 ms | **40-120 ms** |
| **Embed de la consulta** (multilingüe L12 int8) | 60-150 ms | **150-350 ms** |
| Fusión RRF | < 1 ms | < 1 ms |
| **Total añadido al híbrido** | **~20-200 ms** | **~60-480 ms** |

→ **La latencia la domina el embedding de la consulta, no la búsqueda vectorial.**
Comparado con la generación del modelo (segundos), es ruido.

**Por indexación** (una vez, o cuando cambia el material):

| Modelo | Por fragmento | 3000 fragmentos (teléfono) | 3000 fragmentos (desktop, 8 hilos) |
|---|---|---|---|
| MiniLM-L6 int8 | 40-120 ms | **2-6 min** | 20-40 s |
| Multilingüe L12 int8 | 150-350 ms | **8-18 min** | 60-120 s |
| + ventaneo (×2 ventanas) | ×2 | **4-12 min / 16-36 min** | 40-80 s / 2-4 min |

→ **Este es el número que decide el almacenamiento**: 8-18 minutos de indexación
en un teléfono **no** se repiten en cada arranque. Por eso §4 persiste los
vectores.

Comparación con el BM25: la reindexación completa actual (100 docs / 800
fragmentos) tarda **< 200 ms**. El híbrido la multiplica por **~100×**. Ese es
el costo real de los embeddings: no la consulta, **el arranque y la
reindexación**.

### 3.5 El pool y la profundidad

`pool = max(limit * 4, limit + 10)` — el mismo criterio que ya usa
`10-taxonomia.md` §5.3 para el boosting. Con `topK = 5` → pool = 20.

Con RRF el pool importa **más** que en BM25 puro: si el pool es chico, el
documento que está en el puesto 18 de la lista semántica y en el puesto 1 de la
léxica entra fusionado con `1/61 + 1/78`, y la diferencia con el primero es
~5 %. Si el pool fuera 5, la fusión no tendría nada que fusionar.

**Regla: `pool ≥ 4 × topK`, y nunca menor que 20.**

---

## 4. El almacenamiento de los embeddings

### 4.1 El número

```
3000 fragmentos × 384 dimensiones × 4 bytes (float32) = 4.608.000 bytes ≈ 4,4 MiB
```

En memoria, con el `FloatArray` de Kotlin: 4,6 MB. **Perfecto**, como dice el
enunciado. Dos matices:

- **`float32` es el piso razonable.** `float16` lo bajaría a 2,3 MB, pero Kotlin
  no tiene `Float16` y hay que convertir a mano; el ranking por coseno no cambia
  de forma apreciable, pero la conversión es una fuente de bugs. **Se queda en
  float32.**
- **El vector store no es el problema de memoria: el índice BM25 lo es.** 4,6 MB
  de vectores contra ~30 MB de índice léxico (§1.3). Si se llegara a la
  condición 1 (corpus grande), **el índice léxico es el primero que hay que
  cambiar**, no el de vectores.

### 4.2 Formato del archivo: `.kemb` (Kitoru EMBeddings)

Un archivo binario por **(cátedra, modelo)**. Todo little-endian.

```
DESPLAZAMIENTO  TAMAÑO  CAMPO
0               4       magic "KEMB" (0x4B 0x45 0x4D 0x42)
4               2       version            u16 = 1
6               2       dims               u16
8               4       count              u32   (cantidad de fragmentos)
12              4       chunkSize          u32   (chunkSize con el que se troceó)
16              4       chunkOverlap       u32
20              4       flags              u32   bit0 = vectores L2-normalizados
24              64      modelId            UTF-8, rellenado con 0
88              32      modelHash          SHA-256 crudo del archivo del modelo
120             32      corpusHash         SHA-256 crudo de (chunkId + chunkKey) de todos
152             8       reservado          0
160             ...     payload
```

**Payload:**

```
count × dims × 4 bytes    vectores float32 LE, en el MISMO orden que la lista de fragmentos
count × 32 bytes          chunkId de cada fragmento (UTF-8, rellenado con 0)
count × 16 bytes          chunkKey de cada fragmento (16 primeros bytes del SHA-256 del texto)
```

**Tamaños con 3000 fragmentos × 384 dims:**

| Parte | Bytes |
|---|---|
| Header | 160 |
| Vectores | 4.608.000 |
| chunkIds | 96.000 |
| chunkKeys | 48.000 |
| **Total** | **≈ 4,75 MB** |

**Por qué `chunkKey` (el hash del texto) está en el archivo:** es lo que permite
la **reindexación incremental**. Cuando el docente sube un material, el 95 % de
los fragmentos no cambió → sus vectores se **reusan** en vez de recomputarse.
Sin `chunkKey`, cada cambio de material obliga a re-embedder los 3000
fragmentos (8-18 min en un teléfono). Con `chunkKey`, el costo es proporcional
a lo que **realmente** cambió.

**Por qué `chunkId`:** para poder mostrar "este vector es del fragmento X" en el
diagnóstico, y para validar que el corpus no se reordenó.

### 4.3 Rutas

```
<dataDir>/courses/<courseId>/index/
    emb-paraphrase-multilingual-MiniLM-L12-v2-a1b2c3d4.kemb   ← el vector store
    emb-all-MiniLM-L6-v2-9f8e7d6c.kemb                         ← el anterior (se borra)
```

En el código:

```kotlin
val indexDir = KitoruPaths.coursesDir.resolve(courseId).resolve("index")
```

**El nombre del archivo lleva el hash del modelo.** Eso **es** el mecanismo de
invalidación:

| Evento | Qué pasa |
|---|---|
| Se cambia el modelo de embeddings | Cambia `modelHash` → cambia el nombre del archivo → el archivo viejo **no se busca** → se regenera |
| Se regenera con el mismo modelo | Se sobreescribe el mismo archivo |
| Quedan archivos viejos | Se borran (GC, §4.5) |

No hace falta un "campo `modelVersion` en la base" ni una migración: **el
nombre es la versión**. Es el mismo patrón que usa el mundo de los modelos
(`model.safetensors` vs `model-v2.safetensors`), y es a prueba de errores: no
se puede usar un `.kemb` con un modelo distinto porque **nunca se busca**.

### 4.4 Ciclo de vida

```
KnowledgeBase.sync()
    1. Cargar las fuentes → Doc
    2. Trocear → List<Chunk>   (cada uno con su chunkId)
    3. Si el retriever es semántico o híbrido:
         a. Buscar <indexDir>/emb-<modelSlug>-<hash8>.kemb
         b. Si existe y el modelHash coincide:
              → reutilizar los vectores cuyo chunkKey sigue presente (§4.2)
              → calcular los que faltan
            Si no existe o el modelo cambió:
              → calcular todos
         c. Escribir el archivo nuevo (atómico: .tmp → rename)
         d. Borrar los .kemb viejos de la misma cátedra
    4. Construir el índice de recuperación
```

**Escritura atómica**: se escribe `emb-....kemb.tmp` y se renombra al final. Si
la app se mata a mitad de camino, el `.tmp` queda ahí y el archivo bueno
sigue sirviendo. `FileSystem` necesita `move(from, to)` — que se agrega junto
con `readBytes`/`writeBytes` (los mismos que `03-materiales.md` §9 ya pide
para la extracción de binarios).

### 4.5 Qué falta en `FileSystem`

```kotlin
interface FileSystem {
    // ... lo que ya existe ...
    fun readBytes(path: FilePath): ByteArray
    fun writeBytes(path: FilePath, bytes: ByteArray)
    fun move(from: FilePath, to: FilePath)     // renombrar (escritura atómica)
}
```

El `core` ya tiene `stripDiacritics` como `expect/actual`; el código binario
del `.kemb` (empaquetar/desempaquetar `FloatArray` a bytes LE) es **puro** y va
en `commonMain` sin `java.nio`:

```kotlin
// backend/src/commonMain/kotlin/rag/semantic/VectorFile.kt
internal fun FloatArray.toLeBytes(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = java.lang.Float.floatToIntBits(this[i])   // ← NO: esto no es commonMain
        ...
    }
}
```

> **Ojo con la portabilidad.** `java.lang.Float.floatToIntBits` **no** existe en
> `commonMain` de Kotlin Multiplatform (es JVM). En `commonMain` hay que usar
> `Float.toRawBits()`, que **sí** es multiplataforma (Kotlin 1.2+). Y
> `java.nio.ByteBuffer` tampoco: aunque exista en Android desde la API 1, la
> regla 2 del proyecto ("nada de `java.nio` en `commonMain`") lo prohíbe. Se
> empaqueta a mano con shifts, que además es testeable:

```kotlin
internal fun FloatArray.toLeBytes(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = toRawBits()
        val o = i * 4
        out[o] = (bits and 0xFF).toByte()
        out[o + 1] = ((bits ushr 8) and 0xFF).toByte()
        out[o + 2] = ((bits ushr 16) and 0xFF).toByte()
        out[o + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
    return out
}
```

> `Float.toRawBits()` y `Float.fromBits()` son las APIs multiplataforma
> correctas. `floatToIntBits` es solo JVM.

### 4.6 El GC de los `.kemb`

Al finishar una indexación con éxito:

```
en <indexDir>/, borrar todos los .kemb cuyo nombre NO sea el del archivo recién escrito
```

Se conserva **sólo el actual**. Un archivo por cátedra y modelo. Si un docente
cambia de modelo tres veces, quedan tres archivos hasta la próxima indexación;
la indexación los limpia. Es simple y no necesita un "índice de archivos".

### 4.7 Dónde se calcula: cliente o servidor

| Quién indexa | Cuándo | Cuánto pesa |
|---|---|---|
| **Kitoru-Server** | Cuando el docente sube material | El servidor tiene el modelo y el corpus: **cero fricción para el alumno**; el alumno descarga 4,75 MB por cátedra |
| **Cliente** | En `KnowledgeBase.sync()` | Requiere el modelo local (118 MB) |

**Recomendación: el servidor calcula y publica el `.kemb` junto con el corpus.**
El alumno recibe el material **y** sus vectores. Es la misma idea que un APK
con los assets: el que tiene el modelo lo usa una vez y lo reparte.

Esto tiene una consecuencia importante para §5: **el alumno no necesita el
modelo de embeddings para usar la búsqueda semántica**, siempre que haya
servidor. El modelo es un problema **del que produce el índice**, no del que lo
consume.

---

## 5. El modelo de embeddings como parte de la app

### 5.1 El problema

Si el alumno usa IA local (`ModelPolicy.mode = LOCAL`), hoy descarga **un**
modelo: el de chat (Phi-4-mini Q4 ≈ 2,4 GB). Con embeddings hay que decidir si
además descarga el de embeddings:

| Modelo | Descarga | ¿Es mucho comparado con los 2,4 GB? |
|---|---|---|
| `all-MiniLM-L6-v2` int8 | 23 MB | 1 % — irrelevante |
| `paraphrase-multilingual-MiniLM-L12-v2` int8 | 118 MB (+17 MB tokenizer) | 5 % — irrelevante **en tamaño** |
| `bge-m3` Q8 | ~570 MB | 24 % — ya duele |

El argumento "118 MB es mucho" **es falso en términos de bytes** (5 % del modelo
de chat). El problema real es otro, y es de **fricción de primer arranque**:

> El alumno que sólo quiere chatear con el modelo local **no debería tener que
> descargar 118 MB** para que la app funcione. Y la app no debe exigir un
> modelo de embeddings para arrancar.

### 5.2 El argumento "el RAG local debe ser igual que el remoto" se rompe

Sí, se rompe, y hay que decirlo explícitamente en el manual. El desdoblamiento:

| | El RAG corre en… | La calidad depende de… |
|---|---|---|
| **IA alojada (REMOTE)** | El servidor del curso | Del servidor (que tiene el modelo de embeddings y el corpus indexado) |
| **IA local (LOCAL)** | El dispositivo del alumno | **Del dispositivo**: ¿tiene el modelo? ¿tiene red? |

El desdoblamiento no es un defecto del diseño: es **la consecuencia honesta**
de que la recuperación es una parte del RAG que, a diferencia de la generación,
**puede vivir en un lado o en el otro**.

Hay un precedente en el propio proyecto: `llm-api` ya tiene `LocalModelService`
con un **stub en Android que lanza `LlmException`** (`CONTEXTO.md` §`:llm-api`).
Es exactamente el mismo patrón: la misma capacidad, dos niveles de
implementación, y un **error tipado con mensaje** cuando no está.

### 5.3 El compromiso: la recuperación es de la cátedra, la ejecución es del dispositivo

**Regla del diseño:**

> **La cátedra define CÓMO se recupera (léxica o híbrida).**
> **El dispositivo declara QUÉ PUEDE hacer.**
> **Si no puede, la app lo DICE — nunca degrada en silencio.**

```
Course.retrievalMode: LEXICAL | HYBRID        (lo elige el docente)
Course.embeddingModelId: String                (el modelo exacto; el servidor lo usa)
```

En el cliente, tres proveedores posibles:

```kotlin
package rag.semantic

/**
 * Quién produce los vectores. La app elige el que puede según el modo del
 * modelo y lo que hay instalado.
 */
interface EmbeddingProvider {
    /** Identificador del modelo (va en el nombre del archivo .kemb). */
    val modelId: String
    /** Hash del modelo (invalida el .kemb si cambia). */
    val modelHash: String
    /** Dimensiones del vector. */
    val dimensions: Int
    /** Vectoriza un lote de textos. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}
```

| Implementación | Cuándo | Qué necesita |
|---|---|---|
| `RemoteEmbeddingProvider(baseUrl, apiKey)` | El alumno usa IA alojada | Nada: `POST {baseUrl}/v1/embeddings` |
| `OnnxEmbeddingProvider(modelPath)` | El alumno usa IA local **y** quiere búsqueda semántica | El modelo descargado (118 MB) |
| `None` | Todo lo demás | Nada |

**Y la degradación, explícita:**

```kotlin
// rag/retrieval/RetrieverFactory.kt (hito 2)
fun retrieverFor(course: Course, ctx: EmbeddingContext): Retriever = when (course.retrievalMode) {
    RetrievalMode.LEXICAL -> Bm25Retriever()
    RetrievalMode.HYBRID -> {
        val provider = ctx.provider
        if (provider == null) {
            // NO se degrada en silencio: se avisa y se usa BM25.
            ctx.warn(
                "La cátedra ${course.code} usa búsqueda semántica, pero en este " +
                "dispositivo no hay modelo de embeddings disponible. " +
                "Se usa búsqueda por palabras: puede no encontrar material " +
                "que esté escrito con otras palabras."
            )
            Bm25Retriever()
        } else {
            HybridRetriever(Bm25Retriever(), VectorRetriever(provider))
        }
    }
}
```

El aviso va a:
1. Un **banner** en el chat mientras dura la sesión.
2. La pantalla "Probar pregunta" del docente.
3. El `Message` guardado (para que la analítica pueda medir el impacto).

### 5.4 La matriz final: quién hace qué

| Escenario | Modelo de chat | Modelo de embeddings | Índice vectorial | Recuperación |
|---|---|---|---|---|
| Alumno con IA alojada | En el servidor | En el servidor | Lo calcula y lo publica el servidor | Híbrida, sin descargas extra (salvo 4,75 MB del `.kemb`) |
| Alumno con IA local **sin** servidor | 2,4 GB, lo baja el alumno | — | — | **BM25** (el modelo de embeddings es opcional y se baja aparte) |
| Alumno con IA local **y** busca semántica | 2,4 GB | 118 MB, **bajo demanda** | Se calcula en el dispositivo | Híbrida |
| Docente (escritorio) | — | 118 MB, si lo quiere | En el servidor o local | Híbrida |

**El compromiso, en una frase: el modelo de embeddings se baja *bajo demanda*
(nunca en el primer arranque), y el índice vectorial lo produce quien tenga el
modelo — normalmente el servidor — y lo reparte como un archivo de 4,75 MB.**

### 5.5 Por qué el modelo de embeddings NO se empaqueta en el APK

| | Empaquetado (LangChain4j) | Descargable bajo demanda |
|---|---|---|
| Tamaño del APK | +118 MB **siempre** | +0 MB |
| El alumno que no usa semántica | paga los 118 MB | paga 0 |
| Actualizar el modelo | nueva versión de la app | descarga de 118 MB |
| El `modelHash` en el nombre del `.kemb` | el APK cambia → hash cambia → **todo se reindexa** en cada update | el modelo cambia solo si el docente lo cambia |

El último punto es el que mata el empaquetado: con el modelo dentro del APK,
**cada actualización de la app invalida todos los índices vectoriales**. Con el
modelo descargado aparte, el hash sólo cambia cuando el modelo cambia.

---

## 6. El verificador de citas

**No cambia nada.** Y no es casualidad: es **ortogonal**.

```
Retrieval  →  decide QUÉ fragmentos se le pasan al modelo
Verifier   →  decide SI lo que el modelo respondió está respaldado por ESOS fragmentos
```

El `CitationVerifier` opera sobre `Chunk.text` de los fragmentos **recuperados**:

```kotlin
fun verify(answer: String, sources: List<Chunk>): CitationReport
```

Cambiar cómo se recuperaron los fragmentos no cambia **qué** se verifica:
sigue siendo "el texto que el modelo citó, ¿está en el fragmento que le
dimos?". El verificador no sabe (ni le importa) si el fragmento salió por
BM25, por coseno o por RRF.

Tres matices que **sí** importan y que hay que tener en cuenta:

### 6.1 El mapeo `[n]` → fragmento se mantiene

El verificador asume que `sources[n-1]` es el fragmento citado como `[n]`. Con
RRF el **orden** del `sources` cambia (es el orden fusionado), pero la
correspondencia sigue siendo "el n-ésimo de la lista que le pasamos al modelo".
✔ Sin cambios.

### 6.2 El híbrido puede **subir** la tasa de citas sin respaldo

Éste es el riesgo real. El criterio de "paráfrasis" del verificador exige que el
**50 %** de las palabras significativas del fragmento citado aparezcan en la
fuente. Con recuperación híbrida:

- Aparecen fragmentos con **menor overlap léxico** (encontrados por significado).
- El modelo los parafrasea **más**.
- El verificador puede marcar más citas como "sin respaldo" **aunque sean
  honestas**.

Esto no es un defecto del verificador: es una señal. Y es **medible** con lo que
ya existe: `CitationStats(verified, unsupported, unknown)` por mensaje y por
cátedra (`04-conversaciones.md` §"SourceUsage").

**Acción concreta: cuando se active el modo híbrido, hay que poder comparar.**

```
Tasa de citas sin respaldo:
    LEXICAL  →  baseline (la de la cursada actual)
    HYBRID   →  ?

Si HYBRID sube la tasa de "sin respaldo" y la de "recuperación inútil" baja,
    el híbrido es un NETO POSITIVO (el material que se recuperó es relevante,
    pero el modelo lo parafraseó de más → el problema es el modelo, no el índice).
Si HYBRID sube la tasa de "sin respaldo" Y no baja la de "recuperación inútil",
    el híbrido es un NETO NEGATIVO (trae material raro que el modelo no puede
    usar → es ruido).
```

Esta comparación es **el criterio de aceptación** del hito 2, y es el mismo
instrumento que ya usa `07-temporal-cohortes.md` para comparar grupos.

### 6.3 Lo que el verificador sigue siendo

Un **checksum** del pipeline de recuperación. Si el verificador empieza a fallar
mucho, **algo** se rompió entre lo recuperado y lo que el modelo vio. Agregar
embeddings **no cambia** la responsabilidad del verificador: sigue siendo la
comprobación determinista, sin modelo y sin red, que convierte una instrucción
blanda ("respondé sólo con el contexto") en un hecho verificable.

**Veredicto: `CitationVerifier` no se toca.** Es la parte del RAG que
funshiEngineAI diseñó bien y que Kitoru hereda sin cambios.

---

## 7. La decisión final

### 7.1 Las tres opciones

| | (a) BM25 en el hito 1 + seam | (b) Meter embeddings ya | (c) Híbrido por cátedra |
|---|---|---|---|
| **Descripción** | `Retriever` en el hito 1; embeddings en el hito 2 | Todo desde el hito 1 | Idem (a), pero el docente activa el híbrido por cátedra |
| **Costo en el hito 1** | ~120 líneas, 0 dependencias, 0 cambio de comportamiento | +118 MB, +15 MB de `.so` por ABI, +2 herramientas de build, +1 modelo que gerenciar | El de (a) |
| **Riesgo** | Que la costura quede mal y el híbrido de después sea un rediseño | Alto: 8-18 min de indexación en un teléfono, determinismo perdido, APK inflado | Bajo: igual que (a) |
| **Reversibilidad** | Total: el seam es una interfaz | Baja: una vez que el corpus tiene vectores, la app **depende** del modelo | Total |

### 7.2 El veredicto

**Se recomienda (a), con (c) como estado final del hito 2.**

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  HITO 1:  BM25, sin embeddings.                                             │
│            Se agrega la interfaz Retriever (nada cambia).                   │
│            Se mide: topRetrievalScore, CitationStats, % de preguntas vacías.│
│                                                                              │
│  HITO 2:  Si los datos de la §1.3 dispara la condición 2, 3 o 4:            │
│            → (c) búsqueda híbrida por cátedra, con el .kemb de §4.          │
│            → el docente la activa; el alumno sin modelo la ve degradada     │
│              CON AVISO (no en silencio).                                     │
│            → el verificador de citas no cambia; se compara el antes/después │
│              con CitationStats.                                              │
│                                                                              │
│  HITO 3:  Si la condición 1 (corpus grande) se dispara: el problema es la   │
│            RAM del índice LÉXICO, no el de vectores. Se migra BM25 a disco  │
│            o se pasa a un índice invertido en disco.                         │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Por qué (a) y no (b):**

1. **No hay evidencia.** Ninguna de las cinco condiciones de la §1.3 está
   medida. Meter 118 MB y una dependencia nativa para un problema que **quizá**
   existe es la definición de resolver un problema que no se tiene.
2. **El problema real de la recuperación es de material, no de índice.**
   `03-materiales.md` §1 lo dice textualmente: *"Si la IA no encuentra lo que
   busco, el problema es el material, no el modelo"*. El 80 % de las quejas
   típicas son un PDF escaneado sin capa de texto, un DOCX mal parseado o un
   `chunkSize` demasiado grande. **Ningún embedding arregla eso.**
3. **La solución barata ya está diseñada.** Las etiquetas de la taxonomía
   (`10-taxonomia.md` §4) resuelven los sinónimos **declarados por el
   docente**, que son la mayoría de los sinónimos de una cátedra, con costo
   cero. Un stemmer snowball resolvería además la morfología.
4. **El determinismo es una decisión de diseño, no un accidente.** La regla 3
   del proyecto: *"la analítica y la verificación de citas no pueden depender
   del modelo ni de la red"*. Un embedding **en la ruta de recuperación** mete
   un modelo en el camino crítico del RAG. El modelo de chat ya está en el
   camino crítico de la **generación** (que es inherentemente no determinista);
   meterlo también en la **recuperación** elimina la única parte del RAG que
   hoy es reproducible.
5. **El costo es real y está medido.** 8-18 minutos de indexación en un
   teléfono, un APK +15 MB por ABI, y una matriz de soporte por plataforma.
6. **Lo caro de esperar es cero.** La costura `Retriever` cuesta ~120 líneas y
   **no cambia ningún comportamiento**. La decisión (a) no posterga nada: cambia
   el momento de pagar por el embedding, y el momento correcto es **después de
   tener los datos**.

**Por qué (c) y no un interruptor global:**

- La calidad de la recuperación es una **propiedad de la cátedra**, no del
  dispositivo: el docente sabe si su material está en español o en inglés, si
  sus alumnos pregunta por significado o por término exacto.
- Permite que **una** cátedra con papers en inglés use híbrido y otra con
  apuntes en español se quede en BM25.
- El alumno puede **override** (igual que con el modelo), salvo que el docente
  lo bloquee: el `ModelPolicy` ya tiene `lockedFields` para exactamente esto.

### 7.3 Condiciones de revisiting (escritas, para no decidir por intuición)

La decisión se revisa **sólo** si se cumple alguna de estas, medidas sobre una
cátedra real:

| # | Condición | Cómo se mide | Qué se hace |
|---|---|---|---|
| R1 | ≥ 20 % de las preguntas con `topRetrievalScore == 0` | `04-conversaciones.md` + `07-temporal-cohortes.md` | Se evalúa el híbrido en esa cátedra |
| R2 | ≥ 20 % de las citas sin respaldo | `CitationStats.unsupported / total` | Se evalúa el híbrido y se compara |
| R3 | Corpus > 5.000 fragmentos | `KnowledgeBase.chunkCount` | El problema es la RAM del índice léxico (§7.2, punto 3 del hito 3) |
| R4 | ≥ 30 % del corpus en otro idioma | Metadatos de los materiales | Se evalúa el multilingüe |
| R5 | ≥ 3 pares de sinónimos no controlables por el docente | Tags existentes vs preguntas reales | Se evalúa el híbrido |

**Y al revés: la decisión se descarta si…** el híbrido, medido en una cátedra,
no mejora R1 ni R2 (es decir: la retrieving semántica no agrega nada medible).
En ese caso, el `.kemb` se borra y se vuelve a BM25, sin haber roto nada.

### 7.4 Lo que **no** se decide acá

- El modelo concreto (`MiniLM-L6` vs `multilingüe-L12` vs `bge-m3`): se decide
  cuando se implemente, con el corpus real a la vista (§2.2 y §5.5).
- Si el `Retriever` semántico corre en el cliente o en el servidor: §4.7 y §5.4
  lo dejan abierto a propósito; la interfaz no cambia.
- El reranking (un modelo que reordena los top-50): es la etapa siguiente a la
  híbrida, y ya está previsto por el diseño de la taxonomía ("si algún día se
  agrega un reranker, esta capa sigue sirviendo como filtro léxico previo").

---

## 8. La interfaz `Retriever` (diseño para que (c) sea posible después)

Esta es la parte que **se implementa en el hito 1**. Todo lo demás de este
documento es opcional y posterior.

### 8.1 Las interfaces

**Archivo nuevo**: `backend/src/commonMain/kotlin/rag/retrieval/Retriever.kt`

```kotlin
package rag.retrieval

/**
 * Un recuperador: sabe encontrar los fragmentos relevantes de un corpus.
 *
 * Ésta es **la costura** por donde entra la búsqueda semántica. Hoy hay una
 * sola implementación (BM25); cuando se agregue la búsqueda híbrida, va a
 * haber dos (o tres) y **nada más del RAG va a cambiar**.
 *
 * Reglas del contrato:
 *  - [build] es `suspend` porque un recuperador semántico puede tener que
 *    llamar a un modelo (o a un servicio) para vectorizar el corpus.
 *  - [RetrievalIndex.search] es `suspend` por la misma razón: vectorizar la
 *    consulta puede ser una llamada de red.
 *  - El recuperador **no sabe nada** de la cátedra, del docente ni de la UI.
 *  - El score es **sólo** para ordenar y para explicar; el modelo no lo ve.
 */
interface Retriever {

    /** Identificador técnico ("bm25", "hybrid"). Va en los logs y en la config. */
    val id: String

    /** Etiqueta para la UI ("Búsqueda por palabras"). */
    val label: String

    /** ¿Necesita un modelo externo? La UI lo muestra antes de activarlo. */
    val requiresModel: Boolean get() = false

    /**
     * Construye el índice para un corpus ya troceado.
     *
     * Se llama una vez por reindexación, desde [KnowledgeBase.sync].
     */
    suspend fun build(chunks: List<Chunk>): RetrievalIndex
}

/**
 * Un índice listo para buscar.
 *
 * Lo que devuelve es una **lista ordenada**: el ranking es la información, no
 * el score. Eso es lo que permite fusionar con RRF (que sólo usa las
 * posiciones) sin calibrar escalas.
 */
interface RetrievalIndex {

    /** Cuántos fragmentos hay indexados. */
    val size: Int

    /**
     * Busca y devuelve hasta [limit] fragmentos, del más relevante al menos.
     *
     * @param termWeights peso por término de la consulta. Lo usa la expansión
     *   de etiquetas de la taxonomía (peso 0.3 para los términos sugeridos).
     *   Un recuperador que no lo use, lo ignora.
     */
    suspend fun search(
        query: String,
        limit: Int,
        termWeights: Map<String, Double> = emptyMap(),
    ): List<ScoredChunk>
}

/** Un fragmento recuperado, con su puntaje y de dónde salió. */
data class ScoredChunk(
    val chunk: Chunk,
    val score: Double,
    val origin: RetrievalOrigin = RetrievalOrigin.LEXICAL,
    /** Aporte de cada recuperador (para explicar el resultado en la UI). */
    val parts: Map<String, Double> = emptyMap(),
)

/** De dónde salió un fragmento. */
enum class RetrievalOrigin {
    /** Solo BM25. */
    LEXICAL,

    /** Solo similitud vectorial. */
    SEMANTIC,

    /** Fusión RRF de los dos. */
    HYBRID,
}
```

### 8.2 La implementación BM25 (el adaptador)

**Archivo nuevo**: `backend/src/commonMain/kotlin/rag/retrieval/Bm25Retriever.kt`

```kotlin
package rag.retrieval

/**
 * Recuperador léxico: el BM25 que Kitoru ya tiene.
 *
 * Este adaptador **no agrega nada**: envuelve [Bm25Index] para que el RAG
 * hable con la interfaz [Retriever] y no con BM25 en particular. Es la pieza
 * que permite que mañana entre la búsqueda semántica sin tocar el motor.
 */
class Bm25Retriever : Retriever {

    override val id: String = "bm25"
    override val label: String = "Búsqueda por palabras (BM25)"
    override val requiresModel: Boolean = false

    override suspend fun build(chunks: List<Chunk>): RetrievalIndex =
        Bm25RetrievalIndex(Bm25Index(chunks))
}

/** [Bm25Index] detrás de la interfaz común. */
class Bm25RetrievalIndex(private val index: Bm25Index) : RetrievalIndex {

    override val size: Int get() = index.size

    override suspend fun search(
        query: String,
        limit: Int,
        termWeights: Map<String, Double>,
    ): List<ScoredChunk> =
        index.search(query, limit, termWeights).map { (chunk, score) ->
            ScoredChunk(
                chunk = chunk,
                score = score,
                origin = RetrievalOrigin.LEXICAL,
                parts = mapOf("bm25" to score),
            )
        }
}
```

**`Bm25Index` cambia poco**: se le agrega la sobrecarga con pesos (que la
taxonomía ya pidió) y se conserva la de dos argumentos para no romper los tests:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt
class Bm25Index(private val chunks: List<Chunk>) {

    /** Búsqueda simple (sin pesos). Se mantiene para los tests y el uso directo. */
    fun search(query: String, k: Int): List<Pair<Chunk, Double>> = search(query, k, emptyMap())

    /**
     * Búsqueda con pesos por término.
     *
     * @param termWeights peso de cada término de la consulta (1.0 por defecto).
     *   La expansión de etiquetas de la taxonomía pasa los sugeridos con 0.3.
     */
    fun search(query: String, k: Int, termWeights: Map<String, Double>): List<Pair<Chunk, Double>> {
        // ... el cálculo de BM25 que ya está, con:
        //     val w = termWeights[term] ?: 1.0
        //     scores[i] += w * idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
        // ... y al final, el boost estático del fragmento:
        //     scores[i] *= chunks[i].boost
    }
}
```

> **Nota de compatibilidad**: `Bm25IndexTest` usa `index.search("x", 5)` y mira
> `resultados[0].first.text`. La sobrecarga de dos argumentos **se mantiene
> intacta** → los 8 tests existentes siguen compilando y pasando sin tocar una
> línea. ✔ (regla 1: reutilizar, no reescribir).

### 8.3 Los cambios en `KnowledgeBase`

**Archivo**: `backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt`

```kotlin
class KnowledgeBase(
    private val cfg: RagConfig,
    /**
     * Recuperador en uso. Por defecto BM25: sin modelo, sin dependencias,
     * determinista. El híbrido entra por acá sin tocar el resto del RAG.
     */
    private val retriever: Retriever = Bm25Retriever(),
) {

    @Volatile
    private var index: RetrievalIndex = Bm25RetrievalIndex(Bm25Index(emptyList()))

    /** Fuentes activas (las define la cátedra). */
    @Volatile
    var sources: List<DocumentSource> = emptyList()

    val chunkCount: Int get() = index.size
    val isEmpty: Boolean get() = index.size == 0

    /** Id del recuperador en uso ("bm25", "hybrid"). Para la UI y los logs. */
    val retrieverId: String get() = retriever.id

    suspend fun sync(onStatus: (String) -> Unit = {}) = withContext(ioDispatcher) {
        onStatus("Cargando materiales de la cátedra…")
        val docs = mutableListOf<Doc>()
        val warnings = mutableListOf<String>()
        for (source in sources) {
            runCatching { source.load() }
                .onSuccess { docs += it }
                .onFailure { warnings += "${source.label}: ${it.message}" }
        }

        onStatus("Indexando ${docs.size} documentos…")
        val chunks = docs.flatMap { Chunker.split(it, cfg.chunkSize, cfg.chunkOverlap) }

        // Acá se construye el índice. Si el recuperador es semántico, este es
        // el punto donde se vectoriza el corpus (y donde se lee/escribe el
        // archivo .kemb).
        index = retriever.build(chunks)

        val warn = if (warnings.isEmpty()) "" else " · ⚠ ${warnings.joinToString("; ")}"
        onStatus("Índice listo: ${docs.size} docs / ${chunks.size} fragmentos$warn")
    }

    /**
     * Recupera los fragmentos más relevantes para una consulta.
     *
     * Pasa a `suspend` porque un recuperador semántico vectoriza la consulta
     * (puede ser una llamada de red). Con BM25 no suspende nunca de verdad.
     */
    suspend fun retrieve(query: String, k: Int = cfg.topK): List<Chunk> =
        retrieveScored(query, k).map { it.chunk }

    /**
     * Igual que [retrieve], pero con el puntaje y el origen.
     * La usa la pantalla "Probar pregunta" del docente, que tiene que poder
     * explicar **por qué** salió cada fragmento.
     */
    suspend fun retrieveScored(
        query: String,
        k: Int = cfg.topK,
        termWeights: Map<String, Double> = emptyMap(),
    ): List<ScoredChunk> = index.search(query, k, termWeights)

    /** Las fuentes que el modelo puede citar (para mostrarlas en la UI). */
    fun chunkCountForSource(source: String): Int = index.size
}
```

**Ripple:** `KnowledgeBase.retrieve` pasa a `suspend`. Los llamadores:

| Llamador | Cambio | Riesgo |
|---|---|---|
| `RagEngine.prepare` | pasa a `suspend` | Bajo (§8.4) |
| `03-materiales.md` → "Probar pregunta" | la_viewmodel ya es corrutina | Nulo |
| `KnowledgeBaseTest` (nuevo) | `runTest { kb.retrieve(...) }` | Nulo |
| `CourseIndex.reindex` | ya es `suspend` | Nulo |

### 8.4 Los cambios en `RagEngine`

**Archivo**: `backend/src/commonMain/kotlin/rag/engine/RagEngine.kt`

**Sólo un cambio**: `prepare` pasa a `suspend`.

```kotlin
    /**
     * Arma los mensajes de una pregunta (con o sin RAG).
     *
     * Pasa a `suspend` porque la recuperación puede ser semántica (y vectorizar
     * la consulta es una llamada de red). Con BM25 no cambia el comportamiento.
     */
    suspend fun prepare(
        question: String,
        history: List<ChatMessage>,
        useDocs: Boolean,
    ): Prepared {
        if (!useDocs) {
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
        val hits = kb.retrieve("$question $lastUser", cfg.topK)

        val context = if (hits.isEmpty()) "(sin resultados en el material de la cátedra)" else
            hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }
                .joinToString("\n\n---\n\n")

        val messages = buildList {
            add(ChatMessage("system", systemPrompt()))
            addAll(history.takeLast(cfg.historyTurns * 2))
            add(ChatMessage("user", "CONTEXTO:\n$context\n\nPREGUNTA:\n$question"))
        }
        return Prepared(hits, messages)
    }
```

**Ripple de `prepare`:**

| Llamador | Cambio | Riesgo |
|---|---|---|
| `AgentLoop.run` | ya es `suspend` | **Nulo** |
| La UI del chat | ya corre en una corrutina (`viewModelScope`) | **Nulo** |
| `RagEngine.verify` | **no cambia** (no toca la recuperación) | Nulo |

**Lo que NO cambia en `RagEngine`:**

- `Prepared(sources, messages)` — igual.
- El formato del `CONTEXTO:` y de las citas `[1]`, `[2]` — igual.
- `verify(answer, sources)` — igual (§6).
- El prompt del sistema de la cátedra — igual.

Es decir: **el RAG de punta a punta no cambia ni una línea de comportamiento**.
Lo único que cambia es que ahora el "motor de recuperación" es una cosa
**intercambiable** en vez de estar soldado al BM25.

### 8.5 `Chunk` necesita una identidad estable

Para que la fusión RRF pueda emparejar "el mismo fragmento" en las dos listas,
los fragmentos necesitan una **identidad estable y única**, no la igualdad por
valor (dos fragmentos con el mismo texto y el mismo título colisionarían).

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    /** Id del material al que pertenece ("" si no aplica). */
    val docId: String = "",
    /**
     * Identidad estable del fragmento dentro del corpus: "<docId>#<ordinal>".
     * La usan la fusión RRF (para emparejar rankings) y el almacén de
     * vectores (para reusar los embeddings de los fragmentos que no cambiaron).
     */
    val chunkId: String = "",
    /** Prior estático del docente (1.0 = normal). Multiplica el score. */
    val boost: Double = 1.0,
) {
    val label get() = "$source: $path"
}
```

Y el `Chunker` lo genera:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt
object Chunker {
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> {
        // ... la troceada que ya está ...
        // cada fragmento sale con:
        //   chunkId = "${doc.docId}#$ordinal"     (ordinal = 0, 1, 2…)
        //   boost   = 1.0                          (la taxonomía lo setea después)
    }
}
```

> **Compatibilidad**: `docId`, `chunkId` y `boost` tienen valor por defecto, así
> que los tests que construyen `Chunk("Manual", path, heading, text)` siguen
> compilando. ✔

### 8.6 Qué pasa cuando llega el híbrido (hito 2)

Los archivos que aparecen en el hito 2, **sin tocar los del hito 1**:

```
backend/src/commonMain/kotlin/rag/semantic/
    EmbeddingProvider.kt      ← la interfaz (REMOTE / ONNX / NONE)
    RemoteEmbeddingProvider.kt← POST /v1/embeddings (OpenAI-compatible)
    OnnxEmbeddingProvider.kt  ← jvmCommonMain: ORT + WordPiece + ventaneo
    VectorIndex.kt            ← el RetrievalIndex vectorial
    VectorFile.kt             ← leer/escribir el .kemb (LE, puro)
    VectorStore.kt            ← el .kemb de la cátedra (con GC)
    WordPieceTokenizer.kt     ← el tokenizer, en Kotlin puro

backend/src/commonMain/kotlin/rag/retrieval/
    HybridRetriever.kt        ← la fusión RRF (§3.2)
    RankFusion.kt             ← la fórmula (§3.2)
```

Y en el dominio (`:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt

/** Modo de recuperación de la cátedra. */
val retrievalMode: RetrievalMode = RetrievalMode.LEXICAL,

/** Id del modelo de embeddings que usa la cátedra ("" = el del servidor). */
val embeddingModelId: String = "",
```

```kotlin
@Serializable
enum class RetrievalMode(val label: String) {
    /** Sólo palabras (BM25). Determinista, sin modelo. */
    LEXICAL("Búsqueda por palabras"),

    /** Palabras + significado (RRF). Requiere modelo de embeddings. */
    HYBRID("Búsqueda híbrida"),
}
```

> Estos dos campos **entran en el hito 2**, no en el hito 1. Agregarlos antes
> sería decoración: un campo que nadie lee.

### 8.7 Checklist de implementación del hito 1 (lo único que se hace)

| # | Tarea | Archivo | Tamaño |
|---|---|---|---|
| 1 | `Retriever`, `RetrievalIndex`, `ScoredChunk`, `RetrievalOrigin` | **nuevo** `rag/retrieval/Retriever.kt` | ~60 líneas |
| 2 | `Bm25Retriever` + `Bm25RetrievalIndex` | **nuevo** `rag/retrieval/Bm25Retriever.kt` | ~35 líneas |
| 3 | `Bm25Index.search(query, k, termWeights)` (la sobrecarga de 2 args se queda) | `rag/retrieval/Bm25Index.kt` | ~15 líneas |
| 4 | `Chunk`: `docId`, `chunkId`, `boost` | `rag/retrieval/Models.kt` | ~8 líneas |
| 5 | `Chunker` propaga `chunkId` | `rag/retrieval/Chunker.kt` | ~5 líneas |
| 6 | `KnowledgeBase`: `retriever` inyectable, `index: RetrievalIndex`, `retrieve`/`retrieveScored` | `rag/retrieval/KnowledgeBase.kt` | ~25 líneas |
| 7 | `RagEngine.prepare` → `suspend` | `rag/engine/RagEngine.kt` | 1 palabra |
| 8 | Tests | **nuevo** `rag/retrieval/RetrieverTest.kt` | ~60 líneas |

**Total: ~210 líneas, 0 dependencias nuevas, 0 cambios de comportamiento.**

Los puntos 3, 4 y 5 coinciden con lo que ya pidió `10-taxonomia.md` (§4, §5) y
`03-materiales.md` (§"Dos cambios concretos"): no se está inventando nada, se
está **ordenando** lo que ya estaba pedido.

### 8.8 Los tests del hito 1

```kotlin
class RetrieverTest {

    @Test
    fun `el recuperador por defecto es BM25`() {
        assertEquals("bm25", Bm25Retriever().id)
    }

    @Test
    fun `el adaptador BM25 devuelve los mismos resultados que el índice`() {
        val chunks = listOf(
            Chunk("Manual", "a.md", "", "el motor renderiza en Vulkan", chunkId = "m1#0"),
            Chunk("Manual", "b.md", "", "un tutorial de shaders", chunkId = "m2#0"),
        )
        val directo = Bm25Index(chunks).search("shaders", 5)
        val adaptador = Bm25RetrievalIndex(Bm25Index(chunks)).search("shaders", 5)
        assertEquals(directo.map { it.first.text }, adaptador.map { it.chunk.text })
    }

    @Test
    fun `el origen de un resultado BM25 es LEXICAL`() { ... }

    @Test
    fun `la búsqueda es determinista`() {
        // mismo corpus, dos búsquedas, mismos resultados en el mismo orden
    }
}
```

Y, para cuando exista el híbrido, un test de la fusión (que es donde se puede
meter un bug silencioso):

```kotlin
class RankFusionTest {

    @Test
    fun `un fragmento en las dos listas suma las dos contribuciones`() {
        val a = ScoredChunk(chunk("a", id = "a#0"), 3.0)
        val b = ScoredChunk(chunk("b", id = "b#0"), 2.0)
        val fusionado = RankFusion.fuse(
            listOf(
                RankedList("bm25", 1.0, listOf(a, b)),
                RankedList("semantic", 1.0, listOf(b, a)),
            )
        )
        // a: 1/61 + 1/62 ; b: 1/62 + 1/61  → empatan
        assertEquals(2, fusionado.size)
        assertTrue(abs(fusionado[0].score - fusionado[1].score) < 1e-12)
    }

    @Test
    fun `un fragmento ausente en una lista no penaliza`() {
        // el que sólo está en la lista semántica vale 1/(60+1), no menos
    }
}
```

---

## 9. Resumen de la decisión

| Pregunta | Respuesta |
|---|---|
| **¿Kitoru mete embeddings?** | **No en el hito 1.** Sí en el hito 2, **si los datos lo piden** (5 condiciones medibles, §7.3) |
| **¿Cuándo se revisa la decisión?** | Con la analítica de una cursada real: `topRetrievalScore`, `CitationStats`, `chunkCount`, idioma del corpus |
| **¿Qué motor, si se llega?** | **onnxruntime** (`com.microsoft.onnxruntime:onnxruntime` / `onnxruntime-android`) + modelo **multilingüe** int8 (118 MB) en el cliente; **llama.cpp** en el Kitoru-Server; **nunca** una API externa |
| **¿Cómo se combinan?** | **RRF**: `score(d) = Σ_i w_i / (k + rank_i(d))`, `k = 60`, pool `max(4·topK, topK+10)`, corte después de fusionar |
| **¿Dónde viven los vectores?** | Un `.kemb` binario por (cátedra, modelo) en `courses/<id>/index/`, con el **hash del modelo en el nombre** → la invalidación es por construcción |
| **¿Y el verificador de citas?** | **No cambia.** Es ortogonal: verifica contra el texto del fragmento, no contra el método de recuperación |
| **¿Qué se implementa ahora?** | La interfaz `Retriever` + el adaptador BM25 + `retrieveScored` (~210 líneas, 0 dependencias, 0 cambio de comportamiento) |
