# Métricas de Kitoru — Especificación formal

> **Estado**: diseño. Define *qué* se mide, *cómo se calcula*, *qué significa* y
> *qué alertas* dispara. Es el contrato entre la pantalla del docente y el
> código de `:core` (`Analytics.kt`, `AnalyticsAnalyzer.kt`).
>
> **Fuentes leídas para este documento**:
> `docs/design/CONTEXTO.md`, `core/.../domain/Analytics.kt`,
> `core/.../domain/AnalyticsAnalyzer.kt`, `core/.../domain/Chat.kt`,
> `core/.../domain/Course.kt`, `core/.../domain/Roles.kt`,
> `backend/.../rag/engine/CitationVerifier.kt`, `backend/.../rag/engine/RagEngine.kt`,
> `backend/.../rag/retrieval/DocumentSource.kt`.
>
> **Regla de oro del documento**: una métrica sin denominador explícito, sin
> regla para el denominador 0 y sin regla de agregación no está definida.
> Si algo no está especificado acá, no existe.
>
> **Documentos relacionados** (mismo directorio):
>
> | # | Documento | Qué aporta | Relación con este |
> |---|---|---|---|
> | 01 | `01-pipeline.md` | `AnalyticsAccumulator` incremental, caché, invalidación | Define **dónde** y **cuándo** se calculan estas métricas. Sus hallazgos B1–B4 coinciden con los defectos de §0.7. |
> | 02 | `02-metricas.md` (este) | **Qué** se mide y con qué fórmula | Es la referencia canónica de definiciones. |
> | 03 | `03-preguntas-repetidas.md` | Algoritmo de similitud, clusters, puntaje de riesgo | Detalla §7.1 y §7.2. |
> | 04 | `04-por-material.md` | Analítica por material, atribución de fallos | Detalla §5. |
> | 05 | `05-herramientas.md` | Analítica de herramientas y permisos | Detalla §4. |
> | 06 | `06-coste-latencia.md` | `usage` real, tokens, latencia, presupuesto | Detalla §2. |
> | 07 | `07-temporal-cohortes.md` | Día local, semanas, cohortes, retención | Detalla §1.5, §1.6, §7.6 y el anclaje temporal de §0.5. |
> | 08 | `08-testing.md` | Contratos y tests del motor de analítica | Define cómo se verifican estas fórmulas. |
>
> Cuando un detalle aparece en dos documentos, **manda este** para la
> definición de la métrica y el otro para la implementación.

---

## 0. Marco común

### 0.1 Las ocho decisiones que gobiernan todo lo demás

| # | Decisión | Consecuencia práctica |
|---|---|---|
| **D1** | Toda razón se representa como `Double?`. `null` = **sin datos**. `0.0` = un cero real y medido. | Ninguna tarjeta muestra "0 %" cuando en realidad no hay nada que medir. Se elimina la ambigüedad "0 citas ⇒ 0 % de respaldo" vs "0 citas ⇒ 100 % de respaldo". |
| **D2** | Toda razón se define como `numerador / denominador`, y ambos se exponen por separado en la UI. | El docente puede auditar el número; el programador sabe qué agregar. |
| **D3** | Una **cita** es una *aparición* de un marcador `[n]` en el texto de la respuesta. `[1]` dos veces = 2 citas. | Coherente con `CitationVerifier` (`checks` tiene una entrada por aparición). |
| **D4** | Un mensaje `CANCELLED` o `ERROR` **no entra** en denominadores de calidad (citas) ni de latencia/tokens. **Sí** cuenta como mensaje/respuesta. | Una respuesta cortada a mitad no se penaliza como "no citada" ni ensucia la latencia media. |
| **D5** | Una respuesta está **fundamentada** si tiene ≥ 1 cita verificada y 0 citas sin respaldo y 0 citas inexistentes. | "Sin fundamento" incluye explícitamente "no citó nada", que hoy no se cuenta (ver §0.7). |
| **D6** | **Consultado** ≠ **citado**: *consultado* = el material estaba en el contexto RAG de la respuesta; *citado* = un marcador `[n]` apunta a él. | Se puede ver si el modelo recibió el material y lo ignoró. |
| **D7** | **Todo** agregado filtra por `enrollment.visibility.showsMetrics`. Sin excepción. | Hoy `topSources` no filtra: es una fuga de datos de alumnos con visibilidad `NONE` (§0.7). |
| **D8** | Los conteos por material se agregan por **`materialId`**, nunca por etiqueta de texto. | Las etiquetas (`"cátedra: Título"`) son frágiles: el título se edita, se repite, cambia de path. |

### 0.2 Poblaciones y filtros

```
E  = inscripciones de la cátedra con visibility.showsMetrics == true
S  = { e ∈ E : e.role == ALUMNO }              (alumnos)
D  = { e ∈ E : e.role != ALUMNO }              (docentes: titular + ayudantes)
```

Reglas:

1. **Agregados de curso** (volumen, calidad, materiales) se calculan **sólo sobre `S`**.
   La actividad de los docentes se reporta aparte (`teachers`, y sus hilos en
   `perStaff`), para que un profesor probando el sistema no infle las
   métricas de los alumnos.
2. Un alumno con `visibility = NONE` **no aparece** en ningún agregado, ni
   siquiera en los conteos de material (§0.7, defecto 4).
3. Un alumno `METRICS_ONLY` aparece en todos los agregados; su **texto** no se
   lee para nada que no sea un indicador derivado del texto explícitamente
   permitido (§7.1, preguntas repetidas). Si esa métrica no puede calcularse
   sin leer texto y la visibilidad no es `FULL`, la métrica se marca
   `null` (sin datos) para ese alumno.
4. `Course.analyticsEnabled == false` ⇒ la pantalla de analítica no existe
   para esa cátedra; ninguna métrica se calcula.

### 0.3 Notación

Para un alumno `u` (inscripción `e`):

```
Th(u)  = { t ∈ Hilos : t.authorId == u.userId }
Msg(t) = { m ∈ Mensajes : m.threadId == t.id }
U(t)   = { m ∈ Msg(t) : m.role == "user" }
A(t)   = { m ∈ Msg(t) : m.role == "assistant" }
        (los mensajes con role == "tool" o "system" NO son ni U ni A)

U(u) = ⋃_{t∈Th(u)} U(t)          A(u) = ⋃_{t∈Th(u)} A(t)
```

Para un mensaje del asistente `m`:

```
S(m)  = lista de fuentes recuperadas (SourceRef: materialId + etiqueta)
k(m)  = |S(m)|                                 (materiales/fragmentos inyectados)
ch(m) = verificaciones de citas (una por aparición de marcador [n])
V(m)  = #{ c ∈ ch(m) : c.supported }                        (verificadas)
X(m)  = #{ c ∈ ch(m) : ¬c.supported ∧ 1 ≤ c.index ≤ k(m) }  (sin respaldo)
Y(m)  = #{ c ∈ ch(m) : c.index < 1 ∨ c.index > k(m) }        (inexistentes)
C(m)  = |ch(m)| = V(m) + X(m) + Y(m)          (total de citas)
hasCitas(m) = C(m) > 0
```

Los índices `[n]` son **1-based sobre `S(m)` en el orden recuperado**, igual
que en `CitationVerifier.verify` (la fuente `n` es `sources[n-1]`).

### 0.4 Convención de denominador 0 — la regla que evita todos los errores

| Situación | Valor de la razón | Se muestra como |
|---|---|---|
| Denominador > 0 | `num / den` | número con su numerador y denominador a la vista |
| Denominador = 0 | `null` | «—  sin datos» (celda gris, no «0 %») |

**Prohibición explícita**: `citationSupportRate = 0.0` cuando `C = 0`.
Esa convención es exactamente la que produce el falso positivo descrito en
§3.4: un alumno cuyo modelo nunca cita quedaría con «0 % de respaldo» y,
peor, si se invirtiera la métrica, con «100 % de respaldo». Con D1 el caso
queda como **sin datos**, que es la verdad.

Casos borde que se aplican de forma uniforme:

- **Sin mensajes** ⇒ `M = 0`, `R = 0`, `T = 0`, `D = 0`; todas las razones
  derivadas son `null`. El alumno aparece como *inactivo*, no como *pésimo*.
- **Sin respuestas** ⇒ cualquier razón con denominador `R` es `null`.
- **Respuestas canceladas / con error** ⇒ se cuentan en `R` pero no cuentan
  como muestras de calidad ni de latencia (D4). Si **todas** las respuestas
  del alumno están canceladas, `R_completadas = 0` ⇒ las razones de calidad y
  latencia son `null` («—»), no `0`.
- **Alumno sin inscripción visible** ⇒ no se computa nada; no aparece en
  `perStudent`, ni en denominadores de cátedra, ni en `inactiveStudents`.

### 0.5 Día, hora y zona horaria

**Decisión (alineada con `07-temporal-cohortes.md`)**: la cátedra tiene zona
horaria y **cada mensaje guarda el offset de su autor**:

```
CourseSchedule.timeZoneOffsetMinutes : Int     (por cátedra)
ChatMessage.tzOffsetMinutes          : Int     (por mensaje, el del autor)
```

```
dayKey(m) = floor((m.createdAt + m.tzOffsetMinutes·60 000) / 86 400 000) · 86 400 000
```

- El agrupamiento por día usa **medianoche local**, no UTC. El
  `AnalyticsAnalyzer` actual usa UTC (día 86 400 000 fijo) y el comentario de
  `DayActivity` dice "medianoche local": hoy **mienten entre sí**. Para una
  cátedra en Argentina (UTC−3) los mensajes de 21:00 a 24:00 caen en el día
  anterior. **Decisión: el día es local.**
- `offsetZona(instant)` debe venir de la plataforma (`java.time.ZoneId` en
  desktop/android); `:core` no puede calcularlo en `commonMain`. Si el autor
  no tiene offset conocido, se usa el de la cátedra.
- `lastActivity` es un instante absoluto (epoch ms), no un día.
- El mensaje guarda **su propio** offset porque un alumno puede viajar o
  chatear desde otra zona: sin eso, los días de un alumno se deforman.

### 0.6 Estados de un mensaje (campo nuevo, §9.1)

```
enum class MessageStatus { COMPLETED, CANCELLED, ERROR }
```

Reglas de conteo:

| Estado | Cuenta en `M`/`R` | Entra en latencia | Entra en tokens | Entra en citas |
|---|---|---|---|---|
| `COMPLETED` | sí | sí | sí | sí |
| `CANCELLED` (el alumno apretó «Detener») | sí | **no** | tokens parciales, **no** | **no** (no se verifica: el texto está truncado y produciría falsos «sin respaldo») |
| `ERROR` (timeout, HTTP 5xx, error del gateway) | sí | **no** (se mide aparte como latencia de error) | **no** | **no** |

Los errores del modelo se reportan como métrica propia (§9.2, `errorRate`),
que es responsabilidad de la cátedra (infraestructura), no del alumno.

### 0.7 Defectos de la implementación actual (a corregir antes de implementar)

Estos no son opiniones: son errores verificables en el código que hoy produce
números que contradicen esta especificación.

| # | Defecto | Ubicación | Efecto | Corrección |
|---|---|---|---|---|
| 1 | Una cita a fuente inexistente se cuenta **dos veces**: como `unsupported` y como `unknown`. | `CitationVerifier.kt`: `unsupported get() = checks.count { !it.supported && it.index > 0 }` — un check con `index=999` cumple la condición; y `unknown` también lo cuenta. | `C = V + X + Y` cuenta la cita inexistente dos veces ⇒ la tasa de respaldo queda inflada a la baja y el total de citas no cuadra. | `unsupported` debe exigir `it.index in 1..sources.size`. Como el `report` no guarda `sources.size`, agregar `sourceCount` a `CitationReport`. |
| 2 | `unknownCount` cuenta **índices distintos**, mientras `unsupported` y `verified` cuentan **apariciones**. | `CitationVerifier.kt`: `unknownCount get() = unknown.size`. | `[7] [7] [9]` ⇒ 3 apariciones, 2 unknown. La aritmética del dominio no cierra. | Canonicalizar a **apariciones** (D3). `unknown` (lista) queda como detalle; el conteo de la métrica es sobre `checks`. |
| 3 | `answersWithUnsupportedCitations` **no cuenta las respuestas sin citas**. | `AnalyticsAnalyzer.kt`: `if (it.unsupported > 0 || it.unknown > 0) answersWithUnsupported++`. | Un modelo que nunca cita tiene tasa de respuestas sin fundamento = 0 (¡perfecto!) cuando en realidad no fundamentó nada. | Usar la definición de «fundamentada» (D5) sobre `hasCitas`. |
| 4 | `topSources` **no filtra por visibilidad**. | `AnalyticsAnalyzer.kt`: el bucle `for (thread in threads)` no consulta `byUser[thread.authorId].visibility`. | Un alumno con `visibility = NONE` igual alimenta las fuentes más consultadas: fuga de datos y falsea el indicador. | Filtrar por `visibility.showsMetrics` (D7) y agregar por `materialId` (D8). |
| 5 | La latencia de cátedra se reconstruye desde promedios **truncados**. | `AnalyticsAnalyzer.kt`: `it.avgLatencyMs * it.replies` con `avgLatencyMs` entero. | Error de truncamiento acumulado en la media ponderada. Además no se puede calcular p50/p95. | Guardar `latencySumMs` y `latencySamples`, más un histograma (§9.1). |
| 6 | `citationSupportRate` devuelve `0.0` con `total == 0`. | `Analytics.kt`. | Ver §0.4: se muestra «0 % de respaldo» para quien no tiene nada que evaluar. | `Double?` (D1). |
| 7 | El día se calcula en UTC. | `AnalyticsAnalyzer.dayStart`. | Ver §0.5. | Día local con offset configurable. |
| 8 | `days` cuenta días de **cualquier** mensaje del hilo (incluye respuestas del modelo). | `AnalyticsAnalyzer.studentStats`: `messages.map { dayStart(it.createdAt) }.distinct()`. | Una respuesta que llega de madrugada cuenta como día activo del alumno. | Definir `D` sobre `U(u)` (días en que el alumno escribió). Se puede ofrecer `D_total` como dato secundario. |
| 9 | No se distingue "chateo general" de "modo material sin resultados". | `ChatMessage.sources` es una lista de strings vacía en los dos casos. | Impide medir «respuestas con material vs sin material» y el *hit rate* de la recuperación. | Campo `grounding` (§9.1). |
| 10 | `CitationVerifier.normalize` usa `java.text.Normalizer` en `commonMain`. | `backend/src/commonMain/.../CitationVerifier.kt`. | `commonMain` no compila contra la stdlib de JVM; y el mismo normalizador se necesitará para la métrica de preguntas repetidas (§7.1). | Extraer la normalización a `:core` (`kitoru.text.normalizeForCompare`), sobre `kitoru.platform.stripDiacritics`, y que el verificador la use. |
| 11 | `citationsUnsupported` incluye citas inexistentes por el defecto 1, y `StudentAnalytics.citationSupportRate` usa ese total. | `Analytics.kt`. | La tasa de respaldo de un curso con citas inventadas es artificialmente baja y mezcla dos fallos distintos. | Separar `X` (fuente existe, no respalda) de `Y` (fuente inexistente) y exponer las dos tasas. |
| 12 | No hay forma de distinguir «no se verificó» de «verificado y no había citas». | `ChatMessage.citation` es `null` en ambos casos. | No se puede calcular «tasa de respuestas sin cita». | `CitationStats` gana `hasCitations: Boolean` y `sourceCount: Int` (§9.1). |

---

## 1. Volumen de actividad

### 1.1 Mensajes del alumno — `M`

- **Nombre**: Mensajes (written messages)
- **Símbolo**: `M(u)`
- **Unidad**: mensajes (enteros)

**Fórmula**

```
M(u) = |U(u)| = Σ_{t∈Th(u)} |U(t)|
```

**Interpretación**: cuánto escribió el alumno. Es la medida de *volumen de
uso*, **no** de aprendizaje ni de calidad. Un alumno con 0 mensajes está
*inactivo*, no *mal*.

**Casos borde**

- Sin hilos ⇒ `M = 0`.
- Los mensajes con `role == "tool"` (resultados de herramientas que se
  guardan como mensajes) **no** se cuentan: no los escribió el alumno.
- Los reintentos de una misma pregunta **sí** se cuentan (el volumen es
  volumen); la redundancia se mide aparte (§7.1).

**Agregación**: `SUM` por alumno, `SUM` por material (no aplica), `SUM` por día
(ya está en `DayActivity.messages`), `SUM` por curso.

**Trampas**

- Un alumno que hace 300 preguntas de prueba tiene volumen alto y valor nulo.
  Por eso el volumen **no debe usarse como señal de desempeño** (§10.1,
  regla 4).
- Contar mensajes del modelo como "mensajes del alumno" infla todo por 2.

---

### 1.2 Respuestas del modelo — `R`

- **Símbolo**: `R(u)`, unidad: respuestas.

**Fórmula**

```
R(u) = |A(u)|
R_completadas(u) = |{ m ∈ A(u) : m.status == COMPLETED }|
```

**Interpretación**: cuántas veces el sistema respondió. Sirve como
**denominador de calidad**: sin respuestas no hay nada que evaluar.

**Casos borde**

- `R_completadas = 0` con `R > 0` ⇒ todo fue cancelado o falló ⇒ todas las
  razones de calidad son `null` («—») y el error es visible en `errorRate`.

**Agregación**: `SUM`.

**Trampas**: `R` incluye respuestas vacías o de error; usá `R_completadas`
para todo lo que hable de calidad.

---

### 1.3 Hilos — `T`

- **Símbolo**: `T(u)`, unidad: hilos.

**Fórmula**

```
T(u) = |Th(u)|
```

**Interpretación**: cuántas conversaciones distintas abrió. Distingue
"mucho en un hilo" (reflexión, seguimiento) de "muchos hilos sueltos"
(consultas puntuales o abandonment).

**Casos borde**: `T = 0` ⇒ todas las razones derivadas de hilos (`turnos por
hilo`, `abandono`) son `null`.

**Agregación**: `SUM` por alumno y curso. **NO** se agrega "por día" (un hilo
pertenece a un día: el de su creación) y **NO** por material.

**Trampas**: un hilo vacío (creado y abandonado antes de escribir) infla `T`
y baja la media de turnos por hilo. Filtrar hilos con `|U(t)| + |A(t)| = 0`.

---

### 1.4 Turnos por hilo

- **Símbolo**: `turnosPorHilo(u)`, unidad: turnos (pares usuario→respuesta) por hilo.

**Fórmula**

```
turnosPorHilo(u) = (Σ_{t∈Th(u)} |U(t)|) / T(u)          (T > 0)
```

**Interpretación**: cuánto dura una conversación. Valor bajo = consultas
puntuales ("hacé el parcial 1"). Valor alto = diálogo sostenido, que es lo
que la cátedra quiere (preguntar, corregir, re-preguntar).

**Casos borde**: `T = 0` ⇒ `null`. Hilos vacíos excluidos del numerador y del
denominador.

**Agregación por curso**: **ponderada por hilos** —
`Σ turnos / Σ hilos`, nunca el promedio de los promedios (§8.2).

**Trampas**: el promedio esconde la distribución. Publicar también la
**mediana** de turnos por hilo y el % de hilos de un solo turno.

---

### 1.5 Días activos — `D`

- **Símbolo**: `D(u)`, unidad: días.

**Fórmula**

```
D(u) = |{ dayKey(m.createdAt) : m ∈ U(u) }|
```

(días locales en los que el alumno **escribió**; ver defecto 8)

**Interpretación**: distribución del uso. Un alumno con 40 mensajes en 2
días tiene otra relación con la herramienta que uno con 8 mensajes en 8 días.

**Casos borde**: `D = 0` ⇒ no hay actividad.

**Agregación**:
- por día → `DayActivity` (ya existe).
- por curso → **unión** de días distintos (`|⋃_u días(u)|`), **no** la suma.
  Con 20 alumnos de 5 días cada uno, el curso puede tener sólo 8 días
  distintos. La suma (100) no significa nada.
- **Media y mediana** de `D` por alumno: se muestran juntas.

**Trampas**: `D` sube artificialmente si el alumno chatea desde dos zonas
horarias. Se usa la zona de la cátedra (§0.5).

---

### 1.6 Participación en el período — `D / Dc`

- **Símbolo**: `participacion(u)`, unidad: fracción 0..1.

**Fórmula**

```
Dc(u) = |{ días desde max(joinedAt, inicioCursada) hasta min(t_last, finCursada) }| + 1
participacion(u) = D(u) / Dc(u)
```

**Interpretación**: qué fracción del período disponible usó la herramienta.
Es la única forma justa de comparar alumnos que se inscribieron en fechas
distintas.

**Casos borde**: `Dc ≤ 0` ⇒ `null`. `D = 0` ⇒ 0 (es un cero real: no usó
la herramienta en un período bien definido).

**Trampas**: si la cursada no tiene fechas de inicio/fin registradas,
`Dc` se toma desde `joinedAt` hasta hoy, y el indicador **cambia con el
calendario** (hoy vale menos que dentro de un mes para el mismo
comportamiento). Etiquetarlo «participación hasta hoy».

---

### 1.7 Última actividad e inactividad

- **Símbolo**: `t_last(u)` (epoch ms), `inactividad(u)` (días).

**Fórmula**

```
t_last(u)      = max { m.createdAt : m ∈ U(u) ∪ A(u) }     (0 si no hay mensajes)
inactividad(u) = floor((ahora − t_last(u)) / 86 400 000)
```

**Interpretación**: cuándo se vio por última vez. Es el reloj del "alumno
inactivo": la lista `inactiveStudents` actual es `messages == 0`; esto es
más amplio.

**Casos borde**: sin mensajes ⇒ `t_last = 0` ⇒ se muestra «nunca». En la UI,
`0` **no** es una fecha.

**Agregación por curso**: `MAX` (la última actividad de la cátedra), **nunca**
promedio ni suma.

**Trampas**: la última actividad puede ser la respuesta tardía del modelo a
una pregunta de hace tres semanas → el alumno parece activo sin haber
escrito. Se define `t_last` sobre `U(u) ∪ A(u)` pero se reporta
`t_last_alumno` (última escritura) y `t_last_sistema` por separado.

---

## 2. Recursos: tokens y latencia

### 2.1 Tokens de entrada y salida

- **Símbolo**: `tokensIn(u)`, `tokensOut(u)`, `tokensTotal(u)`. Unidad: tokens.

**Fórmula**

```
tokensIn(u)  = Σ_{m ∈ U(u)}  tokensIn(m)
tokensOut(u) = Σ_{m ∈ A(u)}  tokensOut(m)
tokensTotal(u) = tokensIn(u) + tokensOut(u)
```

**Interpretación**: consumo de la cuota de la cátedra (si el modelo es
remoto con API key) y tamaño del trabajo.

**Definición precisa (importante)**: `tokensIn(m)` es el tamaño del **prompt
completo** enviado al modelo para ese turno:

```
prompt(m) = system_prompt + contexto_RAG(m) + historial(turnos previos) + pregunta(m)
```

`tokensOut(m)` es lo generado. Esta definición hace que `tokensIn` **crezca
dentro de un mismo hilo** (el historial se reenvía), lo cual es correcto: es
lo que se cobra.

**Procedencia del dato (ver `06-coste-latencia.md`)**: el gateway pide
`stream_options: {include_usage: true}` y lee el `usage` real del último chunk
del SSE. Si el servidor no lo informa (IA local, gateway viejo), se usa un
estimador portable y el registro queda marcado `UsageSource.ESTIMATED`.

**Casos borde**

- `UsageSource.ESTIMATED` ⇒ la UI debe marcarlo con `~` y los agregados que
  mezclen estimados y reportados se marcan como aproximados. **Una
  estimación nunca se disfraza de dato** (regla 3 del proyecto).
- Las respuestas `CANCELLED`/`ERROR` no aportan tokens (D4).
- Si `tokensIn` se guarda como *sólo el texto del alumno* en vez del prompt
  completo, el número subestima el gasto real. **Regla: verificar en la
  implementación que `tokensIn` sea el prompt completo** (o sea, el
  `usage.prompt_tokens` del proveedor).

**Agregación**: `SUM` en todos los cortes.

**Trampas**

- **Trampa de la API key**: un alumno con 40× la mediana de tokens puede
  estar consumiendo la cuota de la cátedra. Umbral rojo en §10.3. No es un
  problema de "productividad".
- Los tokens de contexto RAG se pueden confundir con tokens de historial:
  conviene desglosar `tokensContexto`, `tokensHistorial`, `tokensPregunta`
  (§9.2) para ver cuánto del gasto es RAG.

---

### 2.2 Latencia

- **Símbolo**: `L̄(u)` (media), `L50`, `L95` (percentiles). Unidad: milisegundos.

**Fórmula**

```
Muestras(u) = { m ∈ A(u) : m.status == COMPLETED }
L̄(u)       = Σ_{m ∈ Muestras(u)} m.latencyMs / |Muestras(u)|
L50 / L95   = percentiles sobre el mismo conjunto de muestras
```

**Interpretación**: cuánto tarda el sistema en responder. Es una métrica de
**infraestructura y de experiencia**, no del alumno.

**Casos borde**

- `|Muestras(u)| = 0` ⇒ `null` («—»).
- Las respuestas canceladas no son muestras (su latencia es "cuánto tardó
  el alumno en cansarse", no "cuánto tarda el sistema").
- Los errores sí tienen tiempo de espera, pero se miden aparte
  (`latenciaDeError`), no mezclados con la latencia de respuesta exitosa.

**Agregación por curso — REGLA CRÍTICA**

```
L̄_curso = Σ_{m ∈ todas las muestras} latencyMs / |todas las muestras|
```

Es decir, **ponderada por número de respuestas**. El promedio de los
promedios por alumno está **prohibido**: el ejemplo de §8.2 muestra por qué
puede desviar el resultado en un factor de 3.

**Percentiles**: se calculan sobre la **muestra agregada** de la población.
Promediar percentiles por alumno no es un percentile (no es asociativo). Para
poder calcular p50/p95 sin guardar todas las muestras se usa un
**histograma exponencial de 128 buckets** (base 2^(1/8) ≈ 1,0905) con
interpolación logarítmica y convención de punto medio dentro del bucket
(`frac = (rank − cum − 0,5) / n`), según `06-coste-latencia.md`. El
histograma **se fusiona exactamente** sumando contadores: por eso el mismo
histograma sirve para el alumno, la cátedra y el agregado institucional.

**Trampas**

- La latencia depende del hardware (modelo local) y de la red (modelo remoto):
  **un umbral absoluto de latencia sólo tiene sentido si la cátedra fija el
  modelo por defecto**. Si el alumno puede cambiar de modelo (agencia del
  alumno), la latencia compara peras con manzanas ⇒ usar umbrales **relativos**
  contra la línea base de la cátedra (§10.3).
- `L̄` esconde la cola: un p50 de 2 s con un p95 de 40 s es una experiencia
  mala aunque la media sea aceptable. Publicar siempre p50 y p95.
- La **duración de una herramienta** también se reporta con mediana, no con
  media (`05-herramientas.md`): una sola tool colgada arruina la media.

---

### 2.3 Rendimiento (tokens por segundo)

- **Símbolo**: `tps(u)`. Unidad: tokens/s.

**Fórmula**

```
tps(u) = tokensOut(u) / (Σ latencyMs(u) / 1000)
```

**Interpretación**: eficiencia del modelo/servicio. Útil para detectar que un
modelo local se está yendo a CPU y no a GPU.

**Casos borde**: latencia total 0 ⇒ `null`.

**Trampas**: mezcla latencia de red con velocidad de generación; sólo
comparable dentro de la misma configuración de modelo.

---

## 3. Calidad de la fundamentación (citas)

Esta es la parte que justifica que Kitoru use un verificador **determinista**
y no "la opinión del modelo". Todas las métricas de esta sección se
calculan sobre respuestas `COMPLETED` con `citation != null`.

### 3.1 Anatomía de una cita

El `CitationVerifier` abre el fragmento de respuesta alrededor del marcador
`[n]` (la oración que lo contiene, hasta 400 caracteres) y lo contrasta con
el texto de la fuente `n`:

| Criterio (en orden) | Resultado |
|---|---|
| 1. **Literal**: el fragmento normalizado aparece tal cual en la fuente | `supported = true` |
| 2. **Sin detalles inventados**: todo nombre propio y todo número ≥ 2 dígitos del fragmento aparece en la fuente | si falla → `supported = false`, motivo `INVENTED_TERM` |
| 3. **Paráfrasis**: ≥ 50 % (`MIN_WORD_COVERAGE`) de las palabras significativas del fragmento aparecen en la fuente | si falla → `supported = false`, motivo `LOW_COVERAGE` |
| — | si la fuente está vacía → `supported = false`, motivo `EMPTY_SOURCE` |
| — | si el índice `[n]` no corresponde a ninguna fuente recuperada → `supported = false`, `index` fuera de rango ⇒ cita **inexistente** |

Dos consecuencias que la cátedra debe entender:

1. `unsupported` **no significa "inventó"**. Puede ser una paráfrasis
   legítima que el verificador no reconoce (el verificador es
   intencionalmente estricto: `MIN_WORD_COVERAGE = 0.5`). Por eso hace falta
   separar el motivo (§3.9): **la invención de un dato concreto es un
   problema serio; la paráfrasis es un problema de estilo**.
2. Los fragmentos RAG se cortan a mitad de oración (`chunkSize = 1200`), lo
   que produce falsos «sin respaldo» en la primera y en la última frase de
   cada fragmento. **Existe un piso de ruido** de aproximadamente 5–10 % en
   `unsupported`: por debajo de ese valor, la tasa no es interpretable.

### 3.2 Citas verificadas, sin respaldo e inexistentes

- **Símbolos**: `V(u)`, `X(u)`, `Y(u)`. Unidad: citas (apariciones).

**Fórmula**

```
V(u) = Σ_{m ∈ respuestas verificadas de u} V(m)
X(u) = Σ_{m ∈ …} X(m)
Y(u) = Σ_{m ∈ …} Y(m)
```

donde «respuestas verificadas de u» = `{ m ∈ A(u) : m.status == COMPLETED ∧ m.citation != null }`.

**Interpretación**

| Contador | Significado | Responsabilidad |
|---|---|---|
| `V` | la respuesta está apoyada en el material | ✓ |
| `X` | la fuente existe pero **no dice lo que la respuesta afirma** | calidad del modelo (o límite del verificador) |
| `Y` | la cita apunta a una fuente **inexistente** | **alucinación de referencia**: el modelo inventó el número |

**Casos borde**

- Una cita repetida (`[1]` dos veces) cuenta **dos** veces (D3). Si además
  apunta a fuente inexistente, cuenta una vez en `Y` (no también en `X` —
  defecto 1 corregido).
- `Y` se reporta en **apariciones**; el detalle de índices distintos
  (`CitationReport.unknown`) queda disponible para el detalle por mensaje.

**Agregación**: `SUM`. Agregar por material: `V`, `X`, `Y` **por materialId**
(la cita `[n]` apunta a `S(m)[n-1]`, así que se puede desglosar por material;
hoy la info se pierde porque `CitationStats` no guarda el desglose — campo
nuevo §9.1).

**Trampas**

- El `X` alto puede ser culpa del **corpus**, no del modelo: si el material
  está mal extraído (PDF sin capa de texto, OCR malo), el verificador falla
  siempre. Mirar `Material.warning` antes de culpar al modelo.
- `Y` alto no se arregla agrandando el corpus: es el modelo inventando el
  número de fuente.

---

### 3.3 Total de citas

- **Símbolo**: `C(u) = V(u) + X(u) + Y(u)`. Unidad: citas.

**Interpretación**: cuánto *"citó"* la herramienta en total. Sirve de
**magnitude** para las tasas: una tasa calculada sobre 3 citas no dice nada
sobre una calculada sobre 300.

**Trampas**: comparar tasas entre alumnos con escalas muy distintas de `C`
sin mirar `C` es un error de lectura. **Toda tarjeta de tasa debe mostrar
`num/den` junto al porcentaje** (D2). Y una tasa con `den < 10` debe
marcarse «muestra pequeña».

---

### 3.4 Tasa de respaldo — `CSR`

- **Nombre**: Citation Support Rate / Tasa de respaldo de citas
- **Símbolo**: `CSR(u)`, unidad: fracción 0..1 (se muestra en %).

**Fórmula**

```
CSR(u) = V(u) / C(u)          con C(u) > 0
CSR(u) = null                 con C(u) = 0        ← D1
```

**Interpretación**: la proporción de citas que la fuente respalda. Es el
indicador central de **calidad de la fundamentación**: mide si cuando el
modelo dice «según el material, X», el material efectivamente dice X.

**Variante complementaria (recomendada publicarla)**

```
CSR_conocida(u) = V(u) / (V(u) + X(u))     con V+X > 0
```

excluye las citas inexistentes, que son un fallo de naturaleza distinta (no
"falta respaldo", sino "el número no existe"). Con un modelo que inventa
índices, `CSR` baja por dos razones a la vez y `CSR_conocida` aísla el
problema.

**Casos borde**

- `C = 0` ⇒ `null` («— sin citas para evaluar»). **Nunca** `0.0` (§0.4) y
  **nunca** `1.0`.
- `C < 10` ⇒ mostrar el valor pero marcar «muestra pequeña» (umbral general
  de §3.3).

**Valores de referencia** (sobre las citas de respuestas `COMPLETED` verificadas)

| Rango | Valor | Fundamento |
|---|---|---|
| Bueno | `CSR ≥ 0.85` | Con material bien extraído y un prompt que exige citar, el verificador literal/paráfrasis debería respaldar la gran mayoría. |
| Aceptable | `0.70 ≤ CSR < 0.85` | Zona dominada por paráfrasis y por fragmentos RAG cortados a mitad de oración (§3.1). No es motivo de alarma, pero sí de revisar el prompt y el `chunkSize`. |
| Preocupante | `0.50 ≤ CSR < 0.70` | Una de cada dos citas no se sostiene. O el corpus está mal extraído, o el modelo alucina detalles, o el `chunkSize` (1200) es demasiado grande. |
| Crítico | `CSR < 0.50` | Más allá del ruido del verificador: hay que intervenir (revisar material, prompt, modelo, `topK`). |

**Agregación por curso — REGLA CRÍTICA**

```
CSR_curso = Σ V(u) / Σ C(u)          (ponderada por número de citas)
```

**Prohibido** `media(CSR(u))` (§8.2, ejemplo con dos alumnos).

**Trampas**

- **La trampa del alumno que nunca cita**: con `C = 0`, `CSR` es `null`
  («sin datos»), **no** 100 % ni 0 %. La UI *no* debe pintar esa tarjeta en
  verde. La información correcta es que ese alumno no generó evidencia de
  calidad: para evaluarlo hay que mirar `tasa de respuestas sin cita` (§3.6)
  y, si el modelo no cita por diseño, la cátedra tiene que decirlo.
- `CSR` alto puede significar "el modelo copia textualmente del fragmento":
  es calidad *de fundamentación*, no calidad *de síntesis*. Complementar con
  la tasa de uso del material (§6.3).
- `CSR` es **por cita**, no **por respuesta**: una respuesta con 10 citas
  verificables y 1 inventada tiene `CSR = 0.91` y es una respuesta con una
  alucinación. Complementar con §3.5.

---

### 3.5 Tasa de respuestas sin fundamento — `USR`

- **Nombre**: Ungrounded Answer Rate / Tasa de respuestas sin fundamento
- **Símbolo**: `USR(u)`, unidad: fracción 0..1.

**Definiciones previas**

```
G(u)      = { m ∈ A(u) : m.status == COMPLETED ∧ m.grounding == RAG ∧ m.sourceCount > 0 }
           (respuestas fundamentables: el modelo tenía material y se le pidió citar)

F(m)      = m.hasCitas ∧ m.citation.unsupported == 0 ∧ m.citation.unknown == 0
           ∧ m.citation.verified ≥ 1                 ← D5
SF(u)     = { m ∈ G(u) : ¬F(m) }                     (sin fundamento)
SFc(u)    = { m ∈ G(u) : m.citation == null }         (no verificadas: no contables)

USR(u)    = |SF(u)| / |G(u)|          con |G(u)| > 0
USR(u)    = null                      con |G(u)| = 0
```

**Interpretación**: la proporción de respuestas que el alumno recibió **sin
apoyo verificable en el material**. Responde a la pregunta del docente:
*"¿le puedo decir a este alumno que la respuesta se apoya en el curso?"*.

A diferencia de `CSR` (por cita), `USR` es **por respuesta**: una sola cita
inventada hunde la respuesta completa.

**Casos borde**

- `|G(u)| = 0` ⇒ `null`. Un alumno que sólo chatted en modo general (sin
  material) **no** tiene "0 % de respuestas sin fundamento": no hay nada que
  evaluar. Mostrar «—  (sólo chat general)» es más honesto que 0 %.
- Una respuesta `CANCELLED` o `ERROR` no entra en `G` (D4).
- Una respuesta en modo RAG **sin fuentes recuperadas** (`sourceCount == 0`)
  no entra en `G` (no había material que citar) pero sí se cuenta en la
  métrica de §6.2 («RAG sin material»). Si el docente quiere el criterio
  más estricto, usar `G' = { m : grounding == RAG }` (incluye las que no
  recuperaron nada); se recomienda publicar **las dos** variantes: `USR`
  (sobre respuestas con material) y `USR+` (sobre todas las respuestas en
  modo RAG).

**Valores de referencia**

| Rango | Valor | Fundamento |
|---|---|---|
| Bueno | `USR ≤ 0.10` | Con un prompt de cátedra que exige citar, el modelo fundamenta casi todo lo que dice sobre el material. |
| Aceptable | `0.10 < USR ≤ 0.25` | Típico de modelos pequeños (3–4 B) que a veces contestan de memoria. Se puede mejorar con prompt y `topK`. |
| Preocupante | `0.25 < USR ≤ 0.40` | Un cuarto de las respuestas no se puede sostener con el material: hay que revisar el prompt del sistema y la calidad del corpus. |
| Crítico | `USR > 0.40` | El sistema no está fundamentando: o el modelo ignora el material, o el material no sirve. |

**Agregación por curso**

```
USR_curso = Σ |SF(u)| / Σ |G(u)|      (ponderada por respuestas fundamentables)
```

**Trampas**

- **Ésta es la métrica que hoy está rota**: la implementación actual cuenta
  "respuestas con citas sin respaldo" como numerador y usa `replies`
  (todas las respuestas, incluidas las de chat general) como denominador. Con
  eso, un alumno que nunca cita da `0.0` ⇒ «calidad perfecta». Hay que
  migrarla a la definición de arriba (§0.7, defecto 3).
- El denominador `G` excluye las respuestas sin material: si el corpus falla
  en recuperar, `USR` baja (aparentemente bien) mientras el sistema está
  fallando. Por eso `USR` **nunca** se muestra solo: siempre acompañado de
  §6.2 y §6.4.
- No confundir `USR` con la tasa de error del modelo: un mensaje de error no
  es una respuesta sin fundamento, es una ausencia de respuesta.

---

### 3.6 Tasa de respuestas sin cita — `NSC`

- **Símbolo**: `NSC(u)`, unidad: fracción 0..1.

**Fórmula**

```
NSC(u) = |{ m ∈ G(u) : ¬m.hasCitas }| / |G(u)|      (|G(u)| > 0; si no, null)
```

**Interpretación**: cuántas respuestas **recibieron material** y **no
citaron nada**. Es la métrica de *disciplina de citación del modelo*, y
separa dos fallos que `USR` mezcla:

- `USR` alto con `NSC` bajo ⇒ el modelo cita pero **inventa** (el peor caso).
- `NSC` alto ⇒ el modelo **ignora** el mecanismo de citas: el prompt no
  funciona, el modelo es demasiado pequeño, o el `topK`/contexto están mal.

**Valores de referencia**: bueno `≤ 0.05`; aceptable `0.05–0.20`;
preocupante `0.20–0.35`; crítico `> 0.35`.

**Trampas**: es la métrica que **más se manipula sin querer**: si el docente
cambia el prompt para "no obligar a citar", `NSC` sube y `CSR` baja, aunque
la calidad real no haya cambiado. Registrar el prompt de la cátedra junto a
cada periodo de análisis (o al menos versionarlo) para que las comparaciones
tengan sentido.

---

### 3.7 Cobertura de fuentes — `uncited`

- **Símbolo**: `coberturaFuentes(u)`. Unidad: fracción 0..1.

**Fórmula**

```
citadas(m)   = { n ∈ 1..k(m) : n aparece como marcador [n] en el texto de m }
sinCitar(m)  = k(m) − |citadas(m)|

coberturaFuentes(u) = Σ_m sinCitar(m) / Σ_m k(m)      (Σ k(m) > 0; si no, null)
```

**Interpretación**: cuánto del material que se le puso en el contexto el
modelo **no usó**. Alto = el contexto es de sobra (o irrelevante) → bajar
`topK` o mejorar el `chunkSize`.

**Casos borde**: `k(m) = 0` no aporta nada al numerador ni al denominador.

**Valores de referencia**: con `topK = 5` es normal que 2–3 fuentes no se
cieten (el modelo elige las mejores). Bueno `≤ 0.50`; aceptable `0.50–0.70`;
preocupante `> 0.70` (contexto casi siempre desperdiciado).

**Trampas**: **no es un defecto del modelo**. El material recuperado se
inyecta completo; que no se cite es lo esperado. Medirlo mal lleva a
"optimizar" quitando contexto y a empeorar la fundamentación. Usar esta
métrica para **decidir `topK`**, no para judging al modelo.

---

### 3.8 Citas por respuesta

- **Símbolo**: `citasPorRespuesta(u)`. Unidad: citas/respuesta.

**Fórmula**

```
citasPorRespuesta(u) = C(u) / |{ m ∈ A(u) : m.status == COMPLETED ∧ m.hasCitas }|
```

**Interpretación**: densidad de citación. Una respuesta con 8 citas y otra
con 1 no pesan lo mismo en `CSR`.

**Casos borde**: denominador 0 ⇒ `null`.

**Trampas**: un modelo que cita `[1]` al final de cada párrafo infla este
número sin aportar valor. Se combina con `CSR` (densidad × respaldo).

---

### 3.9 Citas con detalle inventado — `citasInventadas` (métrica nueva, ver §9.2)

- **Símbolo**: `tasaInv(CIT)`. Unidad: fracción 0..1.

**Fórmula**

```
inventadas(m) = #{ c ∈ ch(m) : c.reasonCode == INVENTED_TERM }
tasaInv(u)    = Σ inventadas(m) / C(u)        (C(u) > 0; si no, null)
```

**Interpretación**: la fracción de citas que mencionan **un nombre propio o
un número que no existe en la fuente**. Es el indicador de alucinación más
señal de Kitoru, porque a diferencia de `X` (paráfrasis) **no admite
interpretación benigna**: si el modelo escribió "DirectX 12" y la fuente
habla de "Vulkan", inventó.

**Valores de referencia**: bueno `≤ 0.02`; aceptable `0.02–0.08`;
preocupante `> 0.08`. Con `Y` (citas inexistentes) `> 0.02` ⇒ crítico
(inventar el número de fuente es la forma más grave de alucinar).

**Trampas**: requiere el campo `reasonCode` en `CitationCheck` (§9.1); hoy el
motivo es texto libre y no se puede agregar por categoría.

---

## 4. Herramientas

### 4.1 Invocaciones — `toolCalls`

- **Símbolo**: `toolCalls(u)`. Unidad: invocaciones.

**Fórmula**

```
toolCalls(u) = Σ_{m ∈ A(u)} |m.toolCalls|
```

**Interpretación**: cuántas herramientas pidió el modelo. Mide el uso del
*agente*, no del alumno.

**Casos borde**: los mensajes con `role == "tool"` no se cuentan (sólo se
cuentan las llamadas registradas en el mensaje del asistente).

**Trampas**: el número de llamadas depende del catálogo de herramientas
disponible, que es de la cátedra. Comparar entre cátedras no tiene sentido.

### 4.2 Fallos de herramienta

- **Símbolo**: `toolFailures(u)`. Unidad: invocaciones.

**Fórmula**

```
toolOk(u)        = #{ tc : tc.ok == true }
toolFail(u)      = #{ tc : tc.ok == false }
toolIncomplete(u)= #{ tc : tc.ok == null }     ← abortada / en curso al cortar
```

**Interpretación**: `toolFail` = la herramienta falló (timeout, permiso
denegado, archivo no escribible, error de la tool). `toolIncomplete` = la
llamada quedó a medias (el alumno cortó la respuesta).

**Distinción obligatoria (ver `05-herramientas.md`)**: una herramienta puede
**fallar** o **no llegar a ejecutarse**. Son tres hechos distintos, con
responsabilidades distintas:

| Resultado | Qué es | De quién es el problema |
|---|---|---|
| `FAILED` | la tool corrió y falló (timeout, error de I/O) | de la cátedra (configuración) |
| `DENIED` | el alumno dijo "no" en el diálogo de permiso | del alumno (y es una decisión legítima) |
| `BLOCKED` | la cátedra la bloqueó (fuera del techo, sin diálogo) | de la política de la cátedra |
| `INVALID` | el modelo pidió JSON roto que el sanitizador no pudo reparar | del modelo |

Sólo `FAILED` entra en `TFR` (§4.3). `DENIED` y `BLOCKED` **no** son
fallos: contarlos como tales castiga al alumno por una decisión de la
cátedra. `INVALID` se reporta aparte: mide la calidad del protocolo del
modelo.

**Casos borde**: `ok == null` **no** es un fallo: es una llamada inconclusa.
Se reporta aparte; si se quiere un único número de "problemas", definir
`toolProblems = toolFail + toolIncomplete` y usarlo sólo para diagnóstico.

**Trampas**: **un fallo de herramienta es un problema de la cátedra, no del
alumno** (permisos, sandbox, `toolTimeoutMs`, binarios). La métrica debe
aparecer en el panel de infraestructura de la cátedra, nunca en el
"comportamiento del alumno".

### 4.3 Tasa de fallo de herramientas — `TFR`

- **Símbolo**: `TFR(u)` / `TFR_curso`.

**Fórmula**

```
TFR(u) = toolFail(u) / (toolOk(u) + toolFail(u))     (denominador > 0; si no, null)
```

**Interpretación**: proporción de llamadas que fallaron.

**Valores de referencia**: bueno `≤ 0.02`; aceptable `0.02–0.10`;
preocupante `0.10–0.25`; crítico `> 0.25`. Con `topK`/agente, un fallo
sostenido significa que la tool está mal configurada (permisos, paths).

**Agregación**: `SUM toolFail / SUM (toolOk + toolFail)` (ponderada por
llamadas).

### 4.4 Herramientas por respuesta

- **Símbolo**: `toolsPorRespuesta(u)` = `toolCalls(u) / R_completadas(u)`.

**Interpretación**: cuánta acción pide el modelo por respuesta. Valores altos
(> 5) con `TFR` alto indican un bucle de agentic fallando.

### 4.5 Herramientas más usadas — `topHerramientas`

- `usoHerramienta(nombre)` = número de invocaciones por nombre; ranking
  descendente, desempate por nombre ascendente (determinismo).
- `topHerramientas` = top 10.

---

## 5. Materiales

> Detalle completo en `04-por-material.md`: atribución de fallos por
> material, diagnóstico de indexación y el panel del docente. Acá va la
> **definición** de cada número.

### 5.1 Consultas por material

- **Símbolo**: `consultas(material)`. Unidad: veces.

**Fórmula**

```
consultas(m)  = #{ s ∈ S(m) : s.materialId == id }     (ocurrencias en el contexto RAG)
citadas(m)    = #{ n ∈ 1..k(m) : S(m)[n-1].materialId == id ∧ [n] aparece en el texto }
respaldadas(m)= #{ c ∈ ch(m) : c.supported ∧ S(m)[c.index-1].materialId == id }

consultas(material) = Σ_{m ∈ respuestas de alumnos visibles} consultas(m)
```

**Interpretación**: tres números distintos y complementarios por material:

| Número | Significado |
|---|---|
| `consultas` | el material estuvo en el contexto (el RAG lo recuperó) |
| `citadas` | el modelo lo señaló explícitamente |
| `respaldadas` | las citas al material fueron verificadas |

**Regla D6**: **consultado ≠ citado**. `consultas − citadas` mide material
"puesto en contexto y descartado".

**Casos borde**: un fragmento del mismo material puede aparecer dos veces en
la misma respuesta (chunking con solapamiento) ⇒ `consultas` cuenta
ocurrencias; para "materiales alcanzados" usar el booleano
"apareció al menos una vez".

**Trampas**

- **El nombre del material no es la etiqueta**: `ChatMessage.sources` guarda
  etiquetas tipo `"cátedra: Título del material"` (construidas en
  `Chunk.label = "$source: $path"`). Parsearlas por texto es frágil (el
  título se edita; dos materiales pueden llamarse igual). Hay que guardar
  el `materialId` (§9.1) y agregar por id (D8).
- Contar "material consultado" desde los *chunks recuperados* infla el número
  respecto de "materiales distintos alcanzados": un PDF de 40 páginas aporta
  40 chunks. Publicar ambas cifras: `consultas` (ocurrencias) y
  `materialesAlcanzados` (materiales distintos).

### 5.2 Materiales más consultados — `topSources`

- **Símbolo**: `topMateriales`. Unidad: lista ordenada (material → veces).

**Fórmula**

```
topMateriales = sort_by( material → consultas(material), desc; luego título asc )
```

Se muestran los `N` primeros (`N = 10` por defecto, configurable), con:

```
cuota(material) = consultas(material) / Σ consultas(*)      (participación en el corpus)
```

**Interpretación**: qué parte del corpus está usando efectivamente la
cátedra. Es la métrica que le dice al docente qué material hay que
mantener, corregir o complementar.

**Agregación por día**: se cuenta por el día de la respuesta
(`dayKey(m.createdAt)`), no por el día de subida del material.

**Casos borde**: `consultas(*) = 0` ⇒ lista vacía («aún no se consultó
ningún material»).

**Trampas**

- Ranking por etiqueta (bug 4): además de la fuga de privacidad, mezcla
  materiales con nombres parecidos.
- **Trampa de la popularity**: el material más consultado es el que se
  subió primero o el más grande (más chunks ⇒ más oportunidades de
  aparecer). Para comparar materiales hay que normalizar por
  `chunksDisponibles(material)`: `tasaCaptura(material) = consultas /
  chunksIndexados`. Sin ese denominador, el ranking premia el tamaño, no la
  utilidad.

### 5.3 Materiales nunca consultados

- **Símbolo**: `materialesNoConsultados`. Unidad: lista.

**Conjunto de materialesConsiderados (corpus consultable)**

```
C = { mat ∈ Materiales de la cátedra :
      mat.visible == true
      ∧ mat.extractedText.isNotBlank()
      ∧ mat.warning.isBlank()
      ∧ mat.usage != ARCHIVED }        (Material.usage: CITABLE / REFERENCE / ARCHIVED)
```

Exclusiones, y el motivo de cada una:

| Excluido | Por qué |
|---|---|
| `visible == false` | no está en el corpus: no es un fallo |
| `extractedText` vacío | no se pudo extraer texto: se cuenta aparte como «no indexable» |
| `warning` no vacío | idem (PDF sin capa de texto, etc.) |
| `usage == ARCHIVED` | material dado de baja: no se espera que se consulte |
| `uploadedAt > fin del período analizado` | se subió después: no hubo oportunidad |

`usage == REFERENCE` (un programa, un aviso, una bibliografía) **sí** se
indexa y sí puede estar en el contexto, pero **no es citable**: no se espera
que una cita apunte a él. Por eso entra en el denominador de "materiales
consultables" pero sus citas no cuentan para el respaldo.

**Fórmula**

```
materialesNoConsultados = { mat ∈ C : consultas(mat) == 0 }
tasaNoConsultados = |materialesNoConsultados| / |C|      (|C| > 0; si no, null)
```

**Interpretación**: cuánto del corpus es letra muerta. Un valor alto
significa material que la cátedra sube y nadie consulta (o que el RAG no
recupera nunca — que son dos problemas distintos con la misma métrica; se
distingue mirando §6.4 y el hit rate por material).

**Valores de referencia**: bueno `≤ 0.20`; aceptable `0.20–0.50`;
preocupante `0.50–0.70`; crítico `> 0.70` (el corpus es mayoritariamente
irrelevante para lo que se pregunta).

**Trampas**: un material recién subido aparece como "nunca consultado" y
dispara una alerta falsa. **Regla**: todo material con `uploadedAt` dentro
de la ventana de alerta queda *excluido del denominador* hasta que pase un
plazo de gracia (7 días) o se lo marque explícitamente.

### 5.4 Concentración del corpus

- **Símbolo**: `concentracionTop1` y `indiceHH`.

**Fórmula**

```
concentracionTop1 = consultas(material más consultado) / Σ consultas(*)
indiceHH          = Σ (consultas(material) / Σ consultas(*))²
```

**Interpretación**: `concentracionTop1` cerca de 1 significa que el
"corpus" es en realidad un solo documento. `indiceHH` va de 0 (uso repartido
en N materiales) a 1 (todo en uno).

**Valores de referencia**: bueno `concentracionTop1 ≤ 0.40`; aceptable
`0.40–0.70`; preocupante `> 0.70` ⇒ el curso se apoya en un único documento
(riesgo: ese documento está desactualizado y todo el curso se apoya en él).

### 5.5 Material consultado y nunca citado

- **Símbolo**: `consultadoNoCitado(material) = consultas(material) − citasDistintas(material)`.

**Interpretación**: material que se le puso al modelo pero que nunca usó.
Sirve para decidir `topK` y para detectar contexto redundante.

**Trampas**: `consultas` son ocurrencias y `citasDistintas` son
materiales distintos por respuesta: hay que comparar con la misma unidad
(materiales distintos alcanzados por respuesta vs. materiales distintos
citados por respuesta).

### 5.6 Cobertura del corpus

- **Símbolo**: `coberturaCorpus` = `materialesAlcanzados / |C|`.

Complementa §5.3: mide qué fracción del corpus **sí** se usó alguna vez.

### 5.7 Tasa de respaldo por material (ajustada)

- **Símbolo**: `adjustedSupportRate(material)` (ver `04-por-material.md`).

**Fórmula**

```
CSR_bruto(mat)   = citasVerificadas(mat) / citasTotales(mat)          (si citas > 0)
CSR_ajustada(mat)= (V(mat) + a·p₀) / (C(mat) + a)      con a = 10, p₀ = CSR_curso
```

**Interpretación**: con una sola cita, un material puede tener 0 % y
ensuciar el diagnóstico. El ajuste lo sube hacia la media de la cátedra
(encogimiento bayesiano) y deja el problema real arriba.

**Trampas**: la media aritmética de las tasas por material **no** es la tasa
de la cátedra: la tasa de la cátedra es **micro** (`Σ V / Σ C`), y el
promedio por material es sólo una nota al pie (`04-por-material.md`, D4).

---

## 6. Uso del material por respuesta (con material vs. sin material)

### 6.1 Modo de fundamentación (campo nuevo `grounding`)

```
enum class GroundingMode { GENERAL, RAG }
```

Se graba **por mensaje del asistente**, en el momento de generarlo:

- `RAG`: el motor armó el prompt con contexto del corpus (`RagEngine.prepare
  (…, useDocs = true)`).
- `GENERAL`: el alumno chateó sin material (`useDocs = false`, prompt
  `GENERAL_PROMPT`).

**Por qué es imprescindible**: hoy `sources` vacío significa dos cosas
distintas ("chateo general" y "busqué en el material y no encontré nada") y
la analítica no puede distinguirlas. Sin este campo, §6.2 y §6.4 no son
calculables.

### 6.2 Respuestas con material / sin material

- **Símbolos**: `conMaterial(u)`, `sinMaterial(u)`, `enGeneral(u)`.

**Fórmula**

```
conMaterial(u)  = |{ m ∈ A(u) : m.status == COMPLETED ∧ m.grounding == RAG ∧ m.sourceCount > 0 }|
sinMaterial(u)  = |{ m ∈ A(u) : m.status == COMPLETED ∧ m.grounding == RAG ∧ m.sourceCount == 0 }|
enGeneral(u)    = |{ m ∈ A(u) : m.status == COMPLETED ∧ m.grounding == GENERAL }|

tasaUsoMaterial(u) = conMaterial(u) / (conMaterial(u) + sinMaterial(u))
                     (denominador > 0; si no, null)
```

**Interpretación**: de las respuestas en modo material, ¿en cuántas hubo
material? La tasa separa dos Worlds:

- `tasaUsoMaterial` baja porque **el alumno casi no usa el modo material**
  (va a `enGeneral`) ⇒ problema de uso/adopción: la cátedra no explicó cómo
  funciona, o el alumno prefiere el chat libre.
- `tasaUsoMaterial` baja porque **el RAG no recupera nada** ⇒ problema del
  corpus: las preguntas no matchean con el material ⇒ **el docente tiene que
  subir material relevante** (o mejorar `chunkSize`/`topK`).

**Casos borde**

- `conMaterial + sinMaterial = 0` ⇒ `null`.
- Un mensaje en modo GENERAL **no** cuenta como "sin material": es otra
  cosa (chat sin corpus). Esta distinción es la que evita la falsa alarma
  más común.

**Valores de referencia** (sólo entre respuestas en modo RAG): bueno
`≥ 0.80`; aceptable `0.50–0.80`; preocupante `0.20–0.50`; crítico `< 0.20`
⇒ el corpus no está cubriendo lo que se pregunta.

### 6.3 Tasa de acierto de recuperación — `hitRate`

- **Símbolo**: `hitRate(u)` / `hitRate_curso`.

**Fórmula**

```
hitRate(u) = |{ m ∈ A(u) : m.grounding == RAG ∧ m.sourceCount > 0 }|
             / |{ m ∈ A(u) : m.grounding == RAG }|
```

**Interpretación**: el % de preguntas en modo material para las cuales el
RAG encontró algo. Mide la **cobertura del corpus** desde el lado de las
preguntas reales.

**Diferencia con §6.2**: `tasaUsoMaterial` y `hitRate` tienen el mismo
numerador y denominadores casi iguales; la diferencia es que §6.2 excluye
las respuestas en modo general del denominador y `hitRate` no. **Se define
una sola**: `hitRate` es la métrica oficial de cobertura del corpus;
`tasaUsoMaterial` se reporta como indicador de modo de uso. No multiplicar
indicadores redundantes en la pantalla.

### 6.4 Fragmentos recuperados y contexto

- `fragmentosPorRespuesta(u)` = `Σ k(m) / |{m : grounding == RAG}|`.
- `tokensContexto(u)` = tokens atribuibles al bloque CONTEXTO (campo nuevo
  §9.2). Sirve para ver cuánto cuesta el RAG en tokens y para detectar que
  `topK × chunkSize` desborda el contexto del modelo.

**Trampas**: `topK = 5` con `chunkSize = 1200` son ~6 000 tokens de contexto
por turno, y con historial el prompt crece rápido. Un `topK` alto puede
**empeorar** la calidad (ruido en el contexto) y la latencia. Medir
`CSR`/`USR` contra `topK` antes de cambiarlo.

---

## 7. Comportamiento

### 7.1 Preguntas repetidas — `tasaRepeticion`

> Algoritmo completo, clusters y puntaje de riesgo: `03-preguntas-repetidas.md`.
> Acá va la **definición** de la métrica.

- **Símbolo**: `repetidas(u)`, `tasaRepeticion(u)`. Unidad: mensajes, fracción.

**Definición**

```
norm(s)      = kitoru.text.normalizeForCompare(s)
              (minúsculas, sin acentos, sin puntuación, espacios colapsados)
tokens(s)    = tokens de norm(s) con longitud ≥ 4 y que no están en la lista
              de palabras vacías (la misma del verificador de citas)
idf(t)       = peso inverso de la frecuencia del token en el corpus de la cátedra
sim(a, b)    = Σ_{t ∈ tokens(a) ∩ tokens(b)} idf(t)²
               / Σ_{t ∈ tokens(a) ∪ tokens(b)} idf(t)²      (Jaccard ponderado por IDF)
τ            = 0.62   (≈ 0.55 en Jaccard plano)

repetida(m)  ⟺ ∃ m' ∈ U(u) : m'.createdAt < m.createdAt ∧ sim(m, m') ≥ τ
repetidas(u) = |{ m ∈ U(u) : repetida(m) }|
tasaRepeticion(u) = repetidas(u) / M(u)         (M(u) > 0; si no, null)
```

**Por qué IDF**: dos preguntas que comparten palabras comunes ("¿qué es
esto?") no son la misma pregunta; dos que comparten términos técnicos
("autómata finito", "Lema de bombeo") sí lo son. La ponderación hace que el
umbral separe la paráfrasis de la coincidencia casual.

**Sub-tipos (importan)**

| Sub-tipo | Definición | Lectura |
|---|---|---|
| `repeticionEnHilo` | `m'` está en el mismo hilo | el alumno no avanza: la respuesta anterior no le sirvió |
| `repeticionEntreHilos` | `m'` está en otro hilo | re-pregunta: el alumno no obtuvo respuesta en el hilo anterior |
| `preguntaExacta` | `norm(m) == norm(m')` | copia y pega literal |

**Sub-métrica: clusters** (ver `03-preguntas-repetidas.md`): las preguntas
repetidas se agrupan en **componentes conexas** del grafo de similitud. El
tamaño del cluster **en alumnos distintos** ("10 alumnos preguntaron lo
mismo") es una métrica de cátedra, no de alumno: es la señal de que a la
cátedra le falta un material o una explicación.

**Sub-métrica: señal de fracaso** (`03`): la repetición por sí sola no es un
problema; el problema es la **conjunción** de cuatro señales —repetición
(0,30), anclaje sin respuesta útil (0,35), repregunta (0,25) y abandono
(0,10)— que produce un puntaje `F ∈ [0,1]`. Se declara **fracaso de turno**
con `F ≥ 0,45`. Esta métrica sí es la que identifica "el sistema no le
resolvió nada", y es la que se cruza con `USR` (§3.5) para decidir si el
problema es del modelo o del corpus.

**Interpretación**: mide cuánto avanza el alumno con la herramienta.
Alta repetición + alta `USR` ⇒ el modelo responde mal y el alumno insiste:
es una señal de **problema del sistema**, no del alumno.

**Casos borde**

- `M = 0` ⇒ `null`.
- La primera pregunta de cada alumno nunca es "repetida".
- Comparar sólo contra mensajes **anteriores** (no posterior): la primera
  aparición es la original.

**Valores de referencia**: bueno `≤ 0.20`; aceptable `0.20–0.40`;
preocupante `0.40–0.60`; crítico `> 0.60`. Un curso donde la mitad de las
preguntas son repeticiones indica que el corpus o el modelo no están
resolviendo.

**Trampas**

- **No es "copia y pega para hacer trampa"**: es la métrica que más se mal
  interpreta. Repetir la misma pregunta del parcial de 2024 en tres hilos
  distintos es legítimo. Hay que mirarla **junto con** `USR` y `CSR`:
  repetición + fundamentación alta = el alumno está explotando bien la
  herramienta; repetición + fundamentación baja = el sistema lo está
  fallando.
- La similitud depende por completo de la normalización, de la lista de
  palabras vacías y de los pesos IDF: implementarla **exactamente** como se
  especifica o los números no son reproducibles entre versiones. Registrar
  la versión del algoritmo (`sim-v1`) junto al resultado, para que un cambio
  de umbral no invalide meses de histórico.
- El costo es O(pares): el all-pairs es inviable. `03` lo resuelve con un
  **índice invertido con tope de frecuencia** (`df ≤ 60`) y poda por peso,
  lo que deja el costo en ~40 ns por par.
- Requiere leer texto ⇒ si la visibilidad es `METRICS_ONLY`, la métrica es
  `null` para ese alumno (§0.2, regla 3).

### 7.2 Abandono de hilo — `tasaAbandono`

- **Símbolo**: `hilosAbandonados(u)`, `tasaAbandono(u)`. Unidad: hilos, fracción.

**Definiciones**

```
último(t)        = mensaje de Msg(t) con mayor createdAt (desempate por id)
hiloAbandonado(t) ⟺ último(t) ∈ U(t)
                    (o último(t) es assistant con status != COMPLETED)

hilosAbandonados(u) = |{ t ∈ Th(u) : hiloAbandonado(t) ∧ |Msg(t)| > 0 }|
tasaAbandono(u)     = hilosAbandonados(u) / T(u)     (T > 0; si no, null)
```

**Variante fuerte (la que realmente indica un problema)**

```
hiloAbandonadoPorFallo(t) ⟺ el último turno es del usuario y
   (no hay respuesta COMPLETED posterior
    ∨ esa respuesta no estaba fundamentada: sin citas, o con citas sin respaldo)
```

`abandonoPorFallo(t)` = variante fuerte. Se recomienda publicar **las dos**:
`abandono` (el alumno no volvió) y `abandonoPorFallo` (no volvió porque la
respuesta no sirvió).

**Interpretación**: mide cuánta confianza pierde el alumno en la
herramienta. Es la métrica que más se relaciona con la percepción de
utilidad del RAG.

**Casos borde**

- Hilos con un solo mensaje del usuario y respuesta: no abandonado.
- Un hilo cerrado por el alumno con "fin": no hay marca de fin en el modelo
  actual; si se agrega `ChatThread.closedByUser`, usarla.

**Valores de referencia**: bueno `≤ 0.15`; aceptable `0.15–0.30`;
preocupante `0.30–0.45`; crítico `> 0.45`.

**Trampas**: el abandono también ocurre porque el alumno se distrajo. Cruzarlo
con `tasaUsoMaterial`: si el alumno usa modo general, el abandono es menos
informativo.

### 7.3 Mensajes por sesión

- **Símbolo**: `sesiones(u)`, `mensajesPorSesion(u)`.

**Definición de sesión**

```
Ordenar todos los mensajes de U(u) ∪ A(u) por (createdAt, id).
sesión = grupo maximal de mensajes consecutivos con hueco ≤ G = 30 minutos
         entre el fin de uno y el inicio del siguiente.

sesiones(u)         = número de grupos
mensajesPorSesion(u) = |U(u)| / sesiones(u)      (sesiones > 0; si no, null)
duracionMediaSesion(u) = media de (último − primero) de cada sesión
```

**Interpretación**: la "sesión de trabajo" con la herramienta. Es la unidad
natural de uso (una tarde de estudio) y evita que el volumen se lea como
"muchos mensajes sueltos".

**Por qué no usar el hilo como sesión**: un hilo puede vivir semanas con
pausas (el alumno vuelve al mismo tema) y una sesión puede abarcar varios
hilos. Se pueden reportar ambas: `mensajesPorSesion` (por hueco temporal) y
`mensajesPorHilo` (por hilo). **No mezclarlas.**

**Casos borde**: `sesiones = 0` ⇒ `null`.

**Valores de referencia**: bueno `≥ 4` mensajes por sesión; aceptable `2–4`;
preocupante `< 2` (uso fragmentado: consultas sueltas sin continuidad).

**Trampas**: el valor de `G = 30 min` cambia el número de sesiones. Fijarlo
por defecto, hacerlo configurable **por cátedra** y **registrar el valor
usado** en el informe (si no, dos semestres no son comparables).

### 7.4 Ratio de turnos completados — `completitud`

- **Símbolo**: `completitud(u)`.

**Fórmula**

```
contestadas(u) = |{ m ∈ U(u) : ∃ m' ∈ A(u) del mismo hilo con m'.createdAt > m.createdAt
                                      y m'.status == COMPLETED }|
completitud(u) = contestadas(u) / M(u)      (M > 0; si no, null)
```

**Interpretación**: proporción de preguntas que **recibieron respuesta**. Es
la contraparte de "respuestas canceladas o con error": mide si el sistema
contesta.

**Valores de referencia**: bueno `≥ 0.95`; aceptable `0.85–0.95`;
preocupante `0.70–0.85`; crítico `< 0.70` (⇒ mirar `errorRate` y el timeout
de herramientas).

### 7.5 Continuidad (preguntas de seguimiento)

- **Símbolo**: `tasaSeguimiento(u)`.

**Fórmula**

```
seguimiento(m) ⟺ m ∈ U(u) ∧ el hilo de m ya tenía ≥ 1 mensaje de usuario previo
tasaSeguimiento(u) = |{m ∈ U(u) : seguimiento(m)}| / M(u)
```

**Interpretación**: ¿el alumno profundiza en un hilo o abre otro? Alto =
trabajo profundo; bajo = consultas sueltas. Se usa junto con
`turnosPorHilo`.

### 7.6 Distribución temporal y racha

- `rachaActual(u)` = días consecutivos hasta hoy (o hasta `t_last`) con actividad.
- `rachaMáxima(u)` = racha máxima histórica.
- `distribucionTemporal(u)` = qué fracción de la actividad cae en el último
  20 % del período (detecta "todo el trabajo la última noche").

**Trampas**: ninguna de estas es una métrica de calidad. Sólo sirven para
describir el ritmo de trabajo.

---

## 8. Reglas de agregación

### 8.1 Tabla de tipos de agregación

| Métrica | Tipo | Por alumno → por curso | ¿Ponderada? | Nota |
|---|---|---|---|---|
| Mensajes, respuestas, hilos | contador | `SUM` | no | sumas puras |
| Días activos | cardinalidad | `UNIÓN` de días | no | **nunca** `SUM` |
| Última actividad | extremo | `MAX` | no | **nunca** media |
| Tokens in/out/total | contador | `SUM` | no | |
| Latencia media | razón de sumas | `Σ latencia / Σ respuestas` | **sí** | por número de respuestas |
| p50 / p95 | percentil | sobre la muestra agregada (histograma) | **sí** | no promediar percentiles |
| Citas V / X / Y | contador | `SUM` | no | |
| Tasa de respaldo `CSR` | razón | `Σ V / Σ C` | **sí** | por número de citas |
| Tasa de respuestas sin fundamento `USR` | razón | `Σ SF / Σ G` | **sí** | por respuestas fundamentables |
| Tasa de respuestas sin cita `NSC` | razón | `Σ sinCita / Σ G` | **sí** | |
| Cobertura de fuentes | razón | `Σ sinCitar / Σ k` | **sí** | por fragmentos inyectados |
| Herramientas invocadas / falladas | contador | `SUM` | no | |
| Tasa de fallo de herramienta | razón | `Σ fail / Σ (ok+fail)` | **sí** | por llamadas |
| Consultas por material | contador | `SUM` por `materialId` | no | |
| Materiales nunca consultados | conjunto | unión de conjuntos, intersección con corpus | no | |
| Concentración corpus | razón | recalcular sobre los totales | **sí** | |
| Turnos por hilo | razón | `Σ turnos / Σ hilos` | **sí** | por hilos |
| Mensajes por sesión | razón | `Σ mensajes / Σ sesiones` | **sí** | por sesiones |
| Tasa de repetición | razón | `Σ repetidas / Σ M` | **sí** | por mensajes |
| Tasa de abandono | razón | `Σ abandonados / Σ T` | **sí** | por hilos |
| Participación | razón | `Σ D / Σ Dc` | **sí** | por días |
| Días activos del curso | cardinalidad | `|∪ días|` | no | |

**Regla general**: una razón se agrega **siempre** como
`Σ numeradores / Σ denominadores`. La media de promedios sólo es admisible
cuando se **quiere** promediar la experiencia de cada unidad (por ejemplo,
"latencia media *por alumno*"), y en ese caso hay que decirlo explícitamente
en la etiqueta ("latencia media por alumno").

**Dónde se aplica (ver `01-pipeline.md`)**: el `AnalyticsAccumulator` mantiene
un cubo por usuario **independiente de la visibilidad** y aplica el filtro de
visibilidad en el *freeze*. Eso no cambia ninguna fórmula de esta sección,
pero fija una regla: **el filtro por visibilidad se aplica una sola vez, al
freeze, nunca dentro de los acumuladores**. Por eso el defecto 4 (§0.7:
`topSources` sin filtro) es exactamente la clase de bug que la arquitectura
de `01` evita por construcción.

**Reglas de agregación "micro vs. macro"** (ver `04-por-material.md`, D4):

- **micro** (ponderada): `Σ numeradores / Σ denominadores` sobre la
  población. Es la cifra oficial de la cátedra.
- **macro** (por unidad): el promedio de las tasas de cada unidad. **No se
  usa como número de la cátedra**; como mucho, como nota al pie ("promedio
  por material", "promedio por alumno") con la etiqueta explícita.

### 8.2 El error del promedio de promedios

**Caso A — tasa de respaldo (el promedio de promedios *exagera* el problema)**

| Alumno | Respuestas | V | X | Y | `CSR` |
|---|---|---|---|---|---|
| A | 100 | 150 | 30 | 0 | 150/180 = **0.833** |
| B | 2 | 0 | 1 | 1 | 0/2 = **0.000** |

- Media de promedios: `(0.833 + 0.000)/2 = 0.417` → «41 % de respaldo»:
  parece una catástrofe.
- Ponderado por citas: `(150+0)/(180+2) = 0.824` → «82 % de respaldo»:
  la realidad.

**Caso B — latencia (también exagera)**

| Alumno | Respuestas | Latencia total | `L̄` |
|---|---|---|---|
| A | 20 | 40 000 ms | 2 000 ms |
| B | 2 | 60 000 ms | 30 000 ms |

- Media de promedios: `(2000 + 30000)/2 = 16 000 ms` → «16 s de media».
- Ponderado: `100 000 / 22 = 4 545 ms` → «4.5 s de media».

**Caso C — latencia (el promedio de promedios también exagera)**

| Alumno | Respuestas | Latencia total | `L̄` |
|---|---|---|---|
| A | 1 | 10 000 ms | 10 000 ms |
| B | 100 | 200 000 ms | 2 000 ms |

- Media de promedios: `(10000 + 2000)/2 = 6 000 ms` → «6 s».
- Ponderado: `210 000 / 101 = 2 079 ms` → «2.1 s».

**Caso D — latencia (y aquí el promedio de promedios *subestima* el problema)**

| Alumno | Respuestas | Latencia total | `L̄` |
|---|---|---|---|
| A | 100 | 500 000 ms | 5 000 ms |
| B | 2 | 2 000 ms | 1 000 ms |

- Media de promedios: `(5000 + 1000)/2 = 3 000 ms` → «3 s»: parece mejor de
  lo que es.
- Ponderado: `502 000 / 102 = 4 921 ms` → «4.9 s»: la realidad.

Los cuatro casos muestran que el promedio de promedios **mueve el número en
la dirección equivocada según quién tenga la muestra grande**: no hay forma
de arreglarlo salvo no usarlo. Por eso no se permite, en ninguna pantalla,
presentar una media de promedios sin indicarlo.

### 8.3 Percentiles

- Los percentiles **no** son asociativos: `p95(media(p95_i)) ≠ p95(Σ muestras)`.
- Se calculan sobre la **muestra agregada**. Para hacerlo sin guardar todas
  las muestras: **histograma logarítmico de latencia** con buckets
  `{0.1, 0.25, 0.5, 1, 2, 4, 8, 15, 30, 60, 120, ∞}` segundos.
  Los histogramas se suman y el percentile se interpola dentro del bucket
  (error ≤ el ancho del bucket).
- Alternativa aceptable: **reservoir sample** de 1 000 latencias por alumno
  (semilla fija ⇒ reproducible).

### 8.4 Simpson

Agregar por cátedra y comparar con un subgrupo puede invertir la conclusión.
Regla: cuando se comparen dos grupos (por ejemplo, dos modelos o dos
semanas), **publicar siempre el agregado de la cátedra como contexto** y
preferir la comparación **dentro del mismo agregado** (pondereando por el
mismo denominador), nunca "promedio del grupo A vs. promedio del grupo B" sin
el agregado de la cátedra al lado.

### 8.5 Reglas por corte

| Corte | Qué se agrega | Regla |
|---|---|---|
| **Por alumno** | todo | `StudentAnalytics` es el nivel atómico |
| **Por material** | consultas, citas, material nunca consultado | por `materialId`; §5 |
| **Por día** | mensajes, respuestas, citas, consultas | día local; §0.5 |
| **Por curso** | todo | `SUM` de contadores, razones ponderadas, `UNIÓN` de días, `MAX` de actividad |
| **Por docente** | actividad del equipo docente | separado de los alumnos; §0.2 |

**Coherencia con el pipeline** (`01-pipeline.md`): el acumulador es
incremental y O(1) por mensaje, así que **todas** las razones deben poder
mantenerse como **dos contadores** (numerador y denominador) por usuario.
Ninguna métrica de este documento necesita recomputar un histórico: o es un
contador, o es una razón de dos contadores, o es un percentil de un
histograma que se fusiona sumando buckets. Si una métrica no cabe en ese
modelo, hay que rediseñarla (o declararla fuera del pipeline incremental).

---

## 9. Lo que falta

### 9.1 Cambios de modelo de datos BLOQUEANTES

Sin estos campos, varias métricas de este documento **no son calculables**.
Van en `:core`, `kitoru.domain`:

| # | Cambio | Tipo | Para qué |
|---|---|---|---|
| 1 | `ChatMessage.status: MessageStatus` (`COMPLETED`/`CANCELLED`/`ERROR`) | campo nuevo | D4, §0.6: latencia, tokens y citas correctos |
| 2 | `ChatMessage.grounding: GroundingMode` (`GENERAL`/`RAG`) | campo nuevo | §6: con material vs. sin material, hit rate |
| 3 | `ChatMessage.sourceCount: Int` y `ChatMessage.sources: List<SourceRef>` con `SourceRef(materialId, materialTitle, heading)` | cambio de tipo | §5: contar por `materialId` (D8) y saber cuántas fuentes hubo |
| 4 | `CitationStats.hasCitations: Boolean` | campo nuevo | §3.6: distinguir "no supo citar" de "no verificado" |
| 5 | `CitationStats.sourceCount: Int` y `CitationStats.uncitedCount: Int` | campos nuevos | §3.7: cobertura de fuentes |
| 6 | `CitationStats.byMaterial: Map<String, Int>` (materialId → citas) | campo nuevo | §5: citas por material |
| 7 | `CitationCheck.reasonCode: CitationReason` (`NONE`, `EMPTY_SOURCE`, `INVENTED_TERM`, `LOW_COVERAGE`, `UNKNOWN_SOURCE`) | campo nuevo | §3.9: separar invención de paráfrasis (hoy el motivo es texto libre) |
| 8 | `CitationReport.sourceCount: Int` | campo nuevo | corregir el doble conteo del defecto 1 |
| 9 | `StudentAnalytics.latencySumMs: Long`, `latencySamples: Int`, `latencyHistogram: IntArray` | campos nuevos | §2.2: media ponderada exacta y percentiles |
| 10 | `ChatMessage.tokensEstimated: Boolean` y desglose `tokensContext/tokensHistory/tokensQuestion` | campos nuevos | §2.1: distinguir reportado de estimado y ver el costo del RAG |
| 11 | `ChatMessage.errorKind: String?` (`timeout`, `http5xx`, `gateway`, `cancelled`) | campo nuevo | `errorRate` desglosada |
| 12 | `ToolCallStats.status`: usar `ok == null` explícitamente y agregar `ToolCallStats.aborted: Boolean` | campo nuevo | §4.2: separar "abortada" de "falló" |
| 13 | `ChatMessage.tzOffsetMinutes: Int` y `CourseSchedule.timeZoneOffsetMinutes: Int` | campos nuevos | §0.5: el día local (`07-temporal-cohortes.md`) |
| 14 | `UsageRecord` / `UsageSource` (`REPORTED` / `ESTIMATED`) / `UsageLedger` | tipos nuevos | §2.1: procedencia del dato de tokens (`06-coste-latencia.md`) |
| 15 | `Material.usage: MaterialUsage` (`CITABLE` / `REFERENCE` / `ARCHIVED`) | campo nuevo | §5.3: qué materiales son citable (`04-por-material.md`) |
| 16 | `ChatMessage.agent: AgentStats?` (rondas, motivo de corte, sanitizaciones, permisos) | campo nuevo | §4 y §7.2: por qué una respuesta se cortó (`05-herramientas.md`) |
| 17 | `ToolCallStats.outcome` (`OK`/`FAILED`/`DENIED`/`BLOCKED`/`INVALID`) y `category` | campos nuevos | §4.2: distinguir "falló" de "la denegó el alumno" |
| 18 | `CitationDetail` con `materialId` y `reasonCode` | tipos nuevos | §3.9 y §5: atribución de fallos por material |
| 19 | `Enrollment` gana `AnalyticsScope` (datos crudos vs. preagregados) | campo nuevo | ver más abajo |
| 20 | `kitoru.text.normalizeForCompare(text)` en `:core` (usando `kitoru.platform.stripDiacritics`) | función nueva | §7.1 + defecto 10: una sola normalización para citas y para repeticiones |

**Sobre (19) — `AnalyticsScope`.** Para que un docente pueda ver el detalle de
un alumno sin guardar el texto completo de los chats de todos los alumnos,
lo natural es que el **servidor de la cátedra** (Kitoru-Server) preagregue
y que el cliente sólo muestre. Definir un sobre de analítica preagregada
—`CourseAnalyticsSnapshot`— con: los contadores por alumno y por material,
el histograma de latencia, y (opcionalmente) los descriptores de la
similaridad calculados **en el servidor**. El cliente **no** recalcula
métricas de comportamiento por sí mismo cuando los datos vienen del
servidor. Es una decisión de arquitectura que excede este documento, pero
las métricas deben poder expresarse en ambos modos: **función pura** (para
`:core` y los tests) **y** preagregado equivalente (para el servidor). La
función pura es la referencia; el preagregado debe dar **exactamente** los
mismos números para el mismo conjunto de datos (invariante que hay que
testear).

### 9.2 Métricas nuevas propuestas (no implementables con el modelo actual)

| Métrica | Símbolo | Qué aporta | Prioridad |
|---|---|---|---|
| Tasa de respuestas sin cita | `NSC` | separa "ignoró las citas" de "inventó" | **Alta** |
| Tasa de respuestas sin fundamento (corregida) | `USR` | el indicador central de calidad, hoy roto | **Alta** |
| Citas con detalle inventado | `tasaInv` | la señal real de alucinación | **Alta** |
| Tasa de acierto de recuperación | `hitRate` | cobertura del corpus frente a las preguntas reales | **Alta** |
| Respuestas con material / sin material / en general | `conMaterial`, `sinMaterial`, `enGeneral` | separa uso del modo general de fallo de recuperación | **Alta** |
| Tasa de error del modelo | `errorRate` | calidad del servicio (infraestructura) | **Alta** |
| Cobertura de fuentes | `coberturaFuentes` | decide `topK` | Media |
| Concentración del corpus | `concentracionTop1`, `HH` | detecta corpus monocéntrico | Media |
| Tasa de captura por material | `consultas / chunksIndexados` | ranking justo entre materiales | Media |
| Abandono por respuesta inútil | `abandonoPorFallo` | el «el modelo no le sirvió» | Media |
| Repetición entre hilos | `repeticionEntreHilos` | la re-pregunta | Media |
| Turnos completados | `completitud` | mide si el sistema contesta | Media |
| Mensajes por sesión | `mensajesPorSesion` | unidad natural de uso | Media |
| Tokens de contexto | `tokensContexto` | costo y ruido del RAG | Media |
| Distribución temporal / racha | `rachaActual`, `rachaMáxima` | ritmo de trabajo | Baja |
| Índice de Herfindahl del corpus | `HH` | concentración | Baja |
| Tasa de preguntas de seguimiento | `tasaSeguimiento` | profundidad | Baja |
| Citas por respuesta | `citasPorRespuesta` | densidad | Baja |
| Respuestas largas por tokens/s | `tps` | eficiencia del modelo | Baja |

### 9.3 Métricas que NO se recomiendan (y por qué)

- **"Calidad del alumno"**: no hay una métrica que mida aprendizaje. Kitoru
  mide el **sistema** y el **uso**. El uso de la IA como proxy de evaluación
  es un riesgo ético y pedagógico: encourage copiar y pensar menos.
- **"Cantidad de material consumido"** como indicador de calidad: leer más no
  es aprender más. `consultas(material)` mide el uso del corpus, no el
  aprendizaje.
- **Tiempo total de chat**: premia al que se queda pegado. Se puede mostrar
  como dato, nunca como objetivo.
- **Porcentaje de respuestas "correctas"**: no hay verdad de referencia; el
  verificador mide *respaldo*, no *corrección*.
- **Comparaciones causales entre modelos o entre configuraciones**: el
  docente puede cambiar el modelo cuando quiere; no hay asignación aleatoria.
  `05-herramientas.md` lo resuelve así: se reporta como *indicio* con
  intervalo de confianza y tamaño del efecto, nunca como "probado". Esta
  regla aplica a todas las métricas de este documento: **correlación no es
  causal, y el docente tiene que leerlo así**.
- **Un "score de calidad del alumno"**: aunque se pueda calcular un número
  (por ejemplo, una media ponderada de `CSR`, `USR` y `hitRate`), **no se
  publica**: un número único por alumno invita a compararlo entre personas y
  a usarlo como nota. La calidad es del **sistema**; se mide por cátedra.

---

## 10. Umbrales de alerta

### 10.1 Principios

1. **Una alerta necesita datos suficientes.** Con menos de
   `n_min = 20` respuestas (o 10 citas, o 5 hilos), la métrica se muestra
   como «muestra pequeña» y **no** dispara alerta. Sin esto, un alumno que
   hizo 2 preguntas dispara ruido.
2. **Una alerta es un pedido de mirar, no un juicio.** El texto de la alerta
   dice qué mirar, no "el alumno hizo trampa".
3. **Separar alertas de sistema y alertas de uso.** Las primeras (citas
   inventadas, fallos de herramientas, latencia, `errorRate`) son
   responsabilidad de la cátedra. Las de uso (participación, repetición,
   abandono) son *observaciones* y por defecto son informativas, no alertas.
4. **Nada de alertas por volumen bajo de uso.** Un alumno que no usa la
   herramienta no es un problema del sistema.

### 10.2 Umbrales absolutos

| Métrica | Ámbito | Bueno | 🟡 Amarillo | 🔴 Rojo | Criterio / fundamento |
|---|---|---|---|---|---|
| `CSR` tasa de respaldo | alumno / curso | ≥ 0.85 | 0.70 – 0.85 | < 0.70 | Por debajo de 0.70 el ruido del verificador (paráfrasis + chunks cortados) ya no explica la desviación. |
| `USR` respuestas sin fundamento | alumno / curso | ≤ 0.10 | 0.10 – 0.25 | > 0.25 | Un cuarto de las respuestas sin respaldo verificable ⇒ hay que revisar prompt, corpus o modelo. |
| `NSC` respuestas sin cita (en modo RAG) | alumno / curso | ≤ 0.05 | 0.05 – 0.20 | > 0.20 | El modelo tiene material y no cita: el mecanismo de citación no está funcionando. |
| `tasaInv` citas con detalle inventado | curso | ≤ 0.02 | 0.02 – 0.08 | > 0.08 | Es el único tipo de cita sin lectura benigna posible. |
| `Y/C` citas inexistentes | curso | 0 | 0 – 0.02 | > 0.02 | Inventar el número de fuente es la forma más grave de alucinación. |
| `X/C` citas sin respaldo | curso | ≤ 0.15 | 0.15 – 0.30 | > 0.30 | Alto: revisar `chunkSize`, `topK` y el prompt; puede ser el corpus. |
| `TFR` tasa de fallo de herramientas | curso | ≤ 0.02 | 0.02 – 0.10 | > 0.10 | La herramienta está mal configurada (permisos, timeout, paths). |
| `errorRate` errores del modelo | curso | ≤ 0.02 | 0.02 – 0.05 | > 0.05 | Caída del servicio o incompatibilidad del modelo. |
| `completitud` turnos completados | alumno | ≥ 0.95 | 0.85 – 0.95 | < 0.85 | El sistema no contesta: mirar cancelaciones y errores. |
| `tasaAbandono` hilos abandonados | alumno | ≤ 0.15 | 0.15 – 0.30 | > 0.30 | Pérdida de confianza en la herramienta. |
| `abandonoPorFallo` | alumno | ≤ 0.10 | 0.10 – 0.25 | > 0.25 | El alumno se va porque la respuesta no sirvió. |
| `tasaRepeticion` | alumno | ≤ 0.20 | 0.20 – 0.40 | > 0.40 | Mitad de las preguntas repetidas ⇒ el sistema no está resolviendo. |
| `hitRate` acierto de recuperación | curso | ≥ 0.80 | 0.50 – 0.80 | < 0.50 | El corpus no cubre lo que se pregunta: **acción del docente** (subir material). |
| `tasaNoConsultados` | curso | ≤ 0.20 | 0.20 – 0.50 | > 0.50 | Más de la mitad del corpus es letra muerta. |
| `concentracionTop1` | curso | ≤ 0.40 | 0.40 – 0.70 | > 0.70 | La cursada depende de un único documento. |
| `coberturaFuentes` | curso | ≤ 0.50 | 0.50 – 0.70 | > 0.70 | Contexto mayoritariamente desperdiciado: revisar `topK`. |
| `mensajesPorSesion` | alumno | ≥ 4 | 2 – 4 | < 2 | Uso fragmentado: la sesión de trabajo no se está exploitando. |
| `participacion` | alumno | ≥ 0.50 | 0.20 – 0.50 | < 0.20 | **Informativa**, no es una alerta de calidad (§10.1, regla 4). |

### 10.3 Umbrales relativos (contra la línea base de la cátedra)

La línea base (`baseline`) es la **mediana de la cátedra en los últimos 30
días** (misma población, misma configuración de modelo). Se usa cuando la
métrica depende del hardware, del modelo o de la red.

| Métrica | Baseline | 🟡 Amarillo | 🔴 Rojo | Fundamento |
|---|---|---|---|---|
| `L50` latencia mediana | mediana 30 d | > 1.5× | > 3× | El hardware del alumno y el modelo pueden cambiar la latencia; lo anómalo es la desviación contra el propio curso. |
| `L95` latencia p95 | mediana 30 d | > 2× | > 4× | La cola es la que el usuario siente. |
| `tokensPorAlumno` | mediana 30 d | > 2× | > 5× (y > 10× ⇒ abuso de la API key) | Gasto desmedido de la cuota de la cátedra. |
| `messagesPorAlumno` | mediana 30 d | > 3× | > 10× | Posible uso automatizado o compartido de la cuenta. |
| `toolCallsPorRespuesta` | mediana 30 d | > 2× | > 4× | Bucle de herramientas (agentic loop) descontrolado. |
| `respuestasPorAlumno` | mediana 30 d | < 0.3× | < 0.1× | **Informativa**: caída de uso tras un cambio de prompt o de material. |

Reglas de los umbrales relativos:

- La baseline se recalcula **por semana** y con una **ventana mínima de
  30 observaciones**; sin eso, no hay alerta relativa.
- La comparación es **con comparable**: mismo período, mismo tipo de día
  (los fines de semana no se comparan con los días de semana).
- Si el modelo por defecto de la cátedra cambió en el período, la baseline
  anterior **se invalida**: hay que marcar el cambio de configuración en el
  informe.

### 10.4 Reglas de disparo y de presentación

1. **Prioridad**: 🔴 > 🟡 > informativo. Un docente ve primero lo rojo.
2. **Agrupación**: las alertas de un mismo alumno se agrupan en una sola
   tarjeta ("3 alertas en Ana Pérez") con un enlace al detalle.
3. **Contexto obligatorio**: cada alerta muestra el valor, el umbral, el
   denominador (`n`) y el **qué mirar**: "12/58 citas sin respaldo — revisar
   `chunkSize` y la extracción de texto de los materiales".
4. **Sin alerta de volumen bajo** (§10.1, regla 4).
5. **Sin alerta por defecto en modo `METRICS_ONLY`** para las métricas que
   requieren texto (repetición, abandono por detalle): se pueden calcular
   en el servidor sin exponer el texto, pero sólo si el servidor tiene el
   texto; si el alumno es local y no comparte, la métrica es `null`.
6. **Todo alerta es accionable o no es alerta.** Si no hay una acción clara
   (subir material, ajustar el prompt, revisar `topK`, cambiar el modelo),
   el indicador es informativo y se muestra sin color de alerta.
7. **Muestra insuficiente ⇒ sin alerta**: el mismo criterio que
   `05-herramientas.md` aplica a los insights de herramientas
   (`INSUFFICIENT_DATA` por debajo de 8 respuestas por brazo o 30 en total).
   Una alerta sobre 3 respuestas es ruido, y el ruido entrena al docente a
   ignorar las alertas.
8. **Verificabilidad**: cada umbral debe estar cubierto por un test de
   contrato en `08-testing.md`, de modo que un cambio de fórmula no pueda
   cambiar un umbral sin que se note.

---

## 11. Ejemplo de cálculo completo

Alumno **Ana**, inscripción `ALUMNO`, visibilidad `FULL`, 2 hilos.
Modo: `RAG`, `topK = 5`.

| # | Hilo | Rol | Estado | Texto (resumen) | Fuentes | Citas (V/X/Y) | Latencia | tokensIn/out |
|---|---|---|---|---|---|---|---|---|
| 1 | t1 | user | — | "¿Qué dice el apunte sobre Vulkan?" | — | — | — | 12 / 0 |
| 2 | t1 | assistant | COMPLETED | "Vulkan es una API gráfica [1]. Requiere DirectX 12 [2]." | 5 | 1 / 1 / 1 | 4 200 ms | 900 / 60 |
| 3 | t1 | user | — | "¿Y el teclado?" | — | — | — | 9 / 0 |
| 4 | t1 | assistant | COMPLETED | "El teclado se lee con SDL [1]." | 5 | 1 / 0 / 0 | 3 800 ms | 880 / 25 |
| 5 | t1 | user | — | "¿Y el teclado?" (repetida) | — | — | — | 9 / 0 |
| 6 | t1 | assistant | CANCELLED | "…" (cortada) | 5 | no verificada | — | — / — (D4) |
| 7 | t2 | user | — | "Repite lo del teclado" | — | — | — | 8 / 0 |
| 8 | t2 | assistant | COMPLETED | "El teclado se lee con SDL [1]." | 5 | 1 / 0 / 0 | 3 900 ms | 870 / 24 |

**Cálculo**

```
M = 4            (mensajes #1, #3, #5, #7)
R = 4            (#2, #4, #6, #8)   R_completadas = 3  (#2, #4, #8)
T = 2
D = 1            (un solo día local)
G = { #2, #4, #8 }   (modo RAG, 5 fuentes, COMPLETED)      ⇒ |G| = 3
   (#6 está CANCELLED: no es muestra de calidad ni de latencia)
```

```
V = 1 + 1 + 1 = 3
X = 1 + 0 + 0 = 1
Y = 1 + 0 + 0 = 1
C = 5
CSR = 3/5 = 0.60                       ← 🔴 (rojo: < 0.70)

fundamentadas: #2 (Y=1 ⇒ no), #4 (V=1, X=0, Y=0 ⇒ sí), #8 (sí)
SF = {#2} ⇒ USR = 1/3 = 0.333          ← 🔴 (rojo: > 0.25)
NSC = 0/3 = 0.0                        ← 🟢 (cita siempre)
tasaInv = 1/5 = 0.20                   ← 🔴 (rojo: > 0.08)  [razón: INVENTED_TERM "DirectX 12"]

conMaterial = 3 ; sinMaterial = 0 ; enGeneral = 0
tasaUsoMaterial = 3/3 = 1.0            ← 🟢
hitRate = 3/3 = 1.0                    ← 🟢 (el corpus responde bien)

latencia: muestras = {#2, #4, #8} ⇒ L̄ = (4200+3800+3900)/3 = 3 966 ms
tokensIn = 12 + 9 + 9 + 8 = 38 ; tokensOut = 60 + 25 + 24 = 109
tokensTotal = 147

repetición: #5 es repetida de #3 (mismo texto normalizado ⇒ sim = 1.0 ≥ 0.62) ⇒ repetidas = 1
   (¿es repetida también de #7? "Y el teclado" vs "Repite lo del teclado":
    tokens(#3) = {teclado}; tokens(#7) = {repite, teclado};
    sim = idf(teclado)² / (idf(teclado)² + idf(repite)²) < 0.62
    ("repite" es un término raro ⇒ idf alto ⇒ pesa más que el coincidente)
    ⇒ NO es repetida por similitud. Sólo #5.)
tasaRepeticion = 1/4 = 0.25            ← 🟡 (amarillo: 0.20–0.40)

abandono: t1 termina en #6 (assistant CANCELLED) ⇒ abandonado; t2 termina en #8 (COMPLETED) ⇒ no
   hilosAbandonados = 1 ⇒ tasaAbandono = 1/2 = 0.50   ← 🔴 (rojo: > 0.30)
   (si la regla "hilo abandonado = último mensaje del usuario", t1 NO estaría
    abandonado: por eso la definición incluye las respuestas canceladas.)

completitud: contestadas = #1(→#2), #3(→#4), #5(no), #7(→#8) ⇒ 3/4 = 0.75
   ⇒ 🔴 (rojo: < 0.85) — el turno #5 quedó sin respuesta.
```

**Lectura docente**: el corpus funciona (`hitRate = 1`), el modelo cita
(`NSC = 0`) pero **inventa detalles** (`tasaInv = 0.20`, `Y = 1`) y hay un
problema operativo: una respuesta cancelada y un turno sin contestar
(`completitud = 0.75`). La acción es sobre el modelo/prompt, no sobre Ana.

---

## 12. Glosario

| Término | Definición |
|---|---|
| **Aparición de cita** | Una vez `[n]` en el texto de una respuesta. Es la unidad de conteo de citas (D3). |
| **Cita verificada** | La fuente `n` respalda el fragmento citado (literal, sin detalles inventados o paráfrasis ≥ 50 %). |
| **Cita sin respaldo** | La fuente `n` existe pero no respalda lo que se afirma. |
| **Cita inexistente** | El índice `n` no corresponde a ninguna fuente recuperada. |
| **Fundamentada** | Respuesta con ≥ 1 cita verificada y 0 sin respaldo y 0 inexistentes (D5). |
| **Consultado** | El material estuvo en el contexto RAG de la respuesta (D6). |
| **Citado** | Un marcador `[n]` de la respuesta apunta a ese material (D6). |
| **Sesión** | Grupo maximal de mensajes con huecos ≤ 30 min. |
| **Turno** | Un mensaje del usuario y la(s) respuesta(s) del modelo que lo siguen. |
| **Sin datos** | `null`: el denominador es 0 (D1). Se muestra «—». |
| **Muestra pequeña** | Denominador por debajo del mínimo para interpretar la métrica. |
| **Línea base** | Mediana de la cátedra en los últimos 30 días, para umbrales relativos. |
