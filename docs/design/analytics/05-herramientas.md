# 05 · Analítica de herramientas del modelo

> Subagente: diseño de la **analítica de herramientas** de Kitoru: cómo se mide
> el uso de herramientas que hace el modelo, qué le dice eso al docente y qué
> se muestra (o no) al alumno.
>
> Módulos tocados: `:core` (`kitoru.domain`: `Chat.kt`, `Analytics.kt`,
> `AnalyticsAnalyzer.kt`, + archivos nuevos), `:backend` (`rag.agent`:
> `AgentLoop.kt`, `ToolPermissions.kt`, `ToolCallSanitizer.kt`), `:ui` (la
> pantalla del docente, todavía no construida).
>
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista* (la analítica no depende del modelo ni de la
> red), *fail-fast con mensajes en español*, *el docente no ve lo que no debe*.

---

## 0. Resumen de decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | La unidad de análisis es **la respuesta del asistente**, no la cita. | Una respuesta con 5 citas pesa igual que una con 1. La cita sola rompe la independencia de las observaciones y exagera la diferencia. |
| D2 | Se miden **dos desenlaces co-primarios**: (a) tasa de respuestas **con cita respaldada** entre las que citan, (b) tasa de respuestas **con al menos una cita** sobre todas. | (a) mide la calidad de la cita; (b) mide el anclaje al material. La hipótesis "se dispersa y cita menos" predice una caída en (b) y, si además las citas son malas, en (a). |
| D3 | El contraste es **observacional** y se reporta como *indicio*, con intervalo de confianza y tamaño del efecto; nunca como "probado". | El docente puede activar herramientas en cualquier momento; no hay asignación aleatoria. |
| D4 | El test principal es **Fisher exacto** (2×2, dos-sided) + **Newcombe** para el intervalo de la diferencia de proporciones; un **test de permutación estratificado por hilo** como control de confundidores. | Con n chico, el Wald se rompe y el Fisher no supone nada. El estratificado por hilo elimina la variabilidad entre hilos/alumnos. |
| D5 | Por debajo de **8 respuestas por brazo** (o 30 en total) la comparación devuelve `INSUFFICIENT_DATA` y **no se genera insight**. | Con n chico cualquier diferencia es ruido; el docente no debe actuar sobre eso. |
| D6 | La duración de una herramienta se reporta con **mediana**, no con media. | Una sola tool colgada (60 s de timeout) arruina la media y hace creer que todas las llamadas son lentas. |
| D7 | Se distinguen **denegada por el alumno** (diálogo mostrado y dijo "no"), **bloqueada por la cátedra** (fuera del techo, sin diálogo) e **inválida** (JSON roto que el sanitizador no pudo reparar). | Son tres hechos distintos con consecuencias distintas: uno es del alumno, uno es de política, uno es del modelo. |
| D8 | Los conteos de permisos se miden en el punto donde se **abre el diálogo**, no donde se decide. | "Peticiones por chat" es lo que ve el alumno; "denegaciones" es lo que le dice al docente que el modelo pide cosas no deseadas. |
| D9 | `ChatMessage` gana un bloque `agent: AgentStats?` con los datos del bucle (rondas, motivo de corte, sanitizaciones, permisos). | Hoy el bucle calcula `sanitizeFails` y los `denials` y los **tira**: no hay forma de medirlos después. |
| D10 | `ToolCallStats` se extiende con `category`, `outcome`, `artifacts`, `round`, `repaired` y `durationMs` (este último ya existe en el dominio pero **nadie lo llena**). | Sin categoría no se puede agrupar por `NONE/FILES/WEB/MCP`; sin `outcome` no se puede separar "falló" de "la denegó el alumno". |
| D11 | El generador de insights es una **función pura con reglas y umbrales fijos**, y produce como máximo **un insight por regla**. | Un panel con 30 números no se lee. La analítica tiene que terminar en una frase accionable. |
| D12 | Al alumno se le muestran **sólo los conteos de su propia interacción** con las herramientas (llamadas, diálogos de permiso, denegaciones, archivos recibidos). **No** se le muestra nada de calidad, de comparación de modelos ni de la cátedra. | Es información sobre *su* uso y sus decisiones (ya la viveron en pantalla); la calidad es del modelo y de la cátedra, y mostrarla al alumno lo invita a medirse con una métrica que no controla. |
| D13 | Ninguna agregación usa **el texto** de `args` ni de `result`. | `args`/`result` son texto generado por el modelo: es "texto" a efectos de la visibilidad (`METRICS_ONLY`), y no se agrega. |

---

## 1. De dónde salen los datos

### 1.1 Lo que existe hoy

`rag.agent.AgentLoop.run()` emite tres eventos y devuelve un `AgentAnswer`:

```
AgentEvent.Text(delta)                         → texto visible
AgentEvent.ToolStart(callId, name, argsJson)   → "en curso"
AgentEvent.ToolEnd(callId, name, ok, result, artifacts) → "OK / falló"
```

`AgentAnswer` lleva `text` + `toolCalls: List<ExecutedTool>`, y `ExecutedTool`
tiene `callId, name, argsJson, ok, result, artifacts` — **sin duración**.

El único consumidor actual de esa información es la UI (tarjetas de
herramienta). Nada se persiste: `ChatMessage.toolCalls: List<ToolCallStats>`
existe en el dominio (`core/.../domain/Chat.kt`) pero **no hay código que lo
llene**, y `ToolCallStats.durationMs` está siempre en 0.

`AnalyticsAnalyzer.studentStats()` ya cuenta dos cosas:

```kotlin
toolCalls += message.toolCalls.size
toolFailures += message.toolCalls.count { it.ok == false }
```

que es exactamente el error de medición que este documento corrige: `ok ==
false` mete en el mismo saco "la tool falló", "el alumno la denegó" y "la
llamada llegó rota".

### 1.2 Lo que el bucle calcula y hoy tira

Dentro de `AgentLoop.run()` hay tres contadores locales que se pierden al
terminar la llamada:

| Contador | Significado | Por qué importa al docente |
|---|---|---|
| `sanitizeFails` | llamadas que el sanitizador **no pudo** reparar (JSON inválido) | Señal directa de que el modelo no maneja el protocolo de herramientas. |
| `denials` | veces que el alumno denegó una herramienta en este mensaje | "El modelo pide cosas que el alumno no quiere". |
| `consecutiveToolErrors` | racha de fallos de tool (corta el bucle en 3) | Distingue "la tool está rota" de "el modelo la usa mal". |

Y hay dos cosas que **no se miden hoy** y hacen falta:

- **rondas consumidas** (`maxIterations` = 8 por defecto): cuántas vueltas
  dio el modelo antes de responder.
- **motivo de corte**: por qué terminó el bucle (`ANSWERED`, `ITERATION_CAP`,
  `CONSECUTIVE_ERRORS`, `SANITIZE_CAP`, `PERMISSION_REPEAT`).

### 1.3 Cambios en `:backend` (mínimos, aditivos)

**a) `ExecutedTool` gana lo que falta para medir**

```kotlin
// backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt

/** Una herramienta ya ejecutada dentro de la respuesta (para las tarjetas UI). */
data class ExecutedTool(
    val callId: String,
    val name: String,
    val argsJson: String,
    val ok: Boolean,
    val result: String,
    /** Archivos que produjo la herramienta (adjuntos del mensaje). */
    val artifacts: List<Artifact> = emptyList(),
    // ---- nuevos (todos con valor por defecto: no rompe los tests portados) ----
    /** Duración real de la ejecución (ms). */
    val durationMs: Long = 0L,
    /** Categoría de permiso ("NONE" | "FILES" | "WEB" | "MCP"). */
    val category: String = "NONE",
    /** Resultado desde el punto de vista del bucle. */
    val outcome: String = "OK",
    /** Ronda del bucle en la que se pidió (1 = la primera). */
    val round: Int = 0,
    /** true si el sanitizador tuvo que reparar la llamada. */
    val repaired: Boolean = false,
)
```

**b) `AgentAnswer` gana el resumen del bucle**

```kotlin
data class AgentAnswer(
    val text: String,
    val toolCalls: List<ExecutedTool> = emptyList(),
    // ---- nuevos ----
    /** "NATIVE" (tool_calls del endpoint) | "PROMPT" (bloque ```tool_call). */
    val mode: String = "",
    /** Rondas modelo+herramienta consumidas. */
    val rounds: Int = 0,
    /** Por qué terminó el bucle (ver AgentStopReason). */
    val stopReason: String = "ANSWERED",
    /** Llamadas que el sanitizador no pudo reparar. */
    val sanitizeFails: Int = 0,
    /** Llamadas que el sanitizador reparó (se recuperaron). */
    val repairedCalls: Int = 0,
    /** Diálogos de permiso abiertos al alumno. */
    val permissionRequests: Int = 0,
    /** Permisos denegados por el alumno (hubo diálogo). */
    val permissionDenials: Int = 0,
    /** Llamadas que no se ejecutaron por permiso denegado o bloqueado. */
    val deniedCalls: Int = 0,
)
```

**c) `AgentEvent.ToolEnd` gana `durationMs`** (para que la tarjeta en vivo
muestre el tiempo sin esperar al mensaje persistido):

```kotlin
data class ToolEnd(
    val callId: String,
    val name: String,
    val ok: Boolean,
    val result: String,
    val artifacts: List<Artifact> = emptyList(),
    val durationMs: Long = 0L,
    /** Categoría de la herramienta ("NONE" | "FILES" | ...). */
    val category: String = "NONE",
    /** "OK" | "FAILED" | "DENIED" | "BLOCKED" | "INVALID". */
    val outcome: String = "OK",
)
```

**d) El bucle mide el tiempo y cuenta los permisos**

En `AgentLoop.run()`:

```kotlin
val started = kitoru.platform.nowMillis()          // portable (expect/actual)
val result = if (denied) ToolResult.Error(...) else execute(invocation, ctx)
val durationMs = kitoru.platform.nowMillis() - started
```

Y para los permisos, `ToolPermissions` gana una función detallada **sin
cambiar la firma actual** (el test portado `ToolPermissionsTest` sigue
funcionando tal cual):

```kotlin
// backend/src/commonMain/kotlin/rag/agent/ToolPermissions.kt

/** Resultado detallado de pedir permiso: distingue los cuatro casos. */
sealed class PermissionOutcome {
    /** Categoría NONE: no hace falta permiso. */
    data object NotNeeded : PermissionOutcome()

    /** La categoría ya estaba concedida en este chat: no se preguntó. */
    data class AlreadyGranted(val category: ToolCategory) : PermissionOutcome()

    /** Fuera del techo de la cátedra: se deniega SIN preguntar (fail-closed). */
    data class Blocked(val category: ToolCategory) : PermissionOutcome()

    /** Se le preguntó al alumno. */
    data class Asked(
        val category: ToolCategory,
        val decision: PermissionDecision,
    ) : PermissionOutcome()
}

object ToolPermissions {
    /** Firma original: se mantiene para no romper el test portado. */
    suspend fun authorize(tool: Tool, argsJson: String, ctx: ToolContext): PermissionDecision =
        when (val outcome = authorizeDetailed(tool, argsJson, ctx)) {
            is PermissionOutcome.NotNeeded -> PermissionDecision.ALLOW_ONCE
            is PermissionOutcome.AlreadyGranted -> PermissionDecision.ALLOW_ONCE
            is PermissionOutcome.Blocked -> PermissionDecision.DENY
            is PermissionOutcome.Asked -> outcome.decision
        }

    suspend fun authorizeDetailed(tool: Tool, argsJson: String, ctx: ToolContext): PermissionOutcome {
        val category = tool.category
        // OJO: NONE va PRIMERO. Si el techo no incluye NONE, get_time quedaría
        // bloqueada y el bucle perdería una herramienta inocua.
        if (category == ToolCategory.NONE) return PermissionOutcome.NotNeeded
        if (category !in ctx.allowedCategories) return PermissionOutcome.Blocked(category)
        if (category in ctx.grantedCategories) return PermissionOutcome.AlreadyGranted(category)
        val request = PermissionRequest(
            chatId = ctx.chatId,
            tool = tool.spec.name,
            category = category,
            summary = tool.describeCall(parseArgs(argsJson)),
            argsJson = argsJson,
        )
        return PermissionOutcome.Asked(category, ctx.requestPermission(request))
    }
}
```

> `allowedCategories` es el techo fail-closed que agrega el doc
> `domain/08-permisos.md` §6.4. Si todavía no está implementado, este
> documento asume que existe; si no, `authorizeDetailed` compila igual con
> `ctx.grantedCategories` solamente.

**e) El sanitizador informa si reparó**

```kotlin
sealed class SanitizedToolCall {
    /** Llamada reparada y lista para ejecutar. */
    data class Valid(
        val name: String,
        val args: JsonObject,
        val id: String,
        /** true si hubo que reparar el JSON (el modelo casi no lo escribió bien). */
        val repaired: Boolean = false,
    ) : SanitizedToolCall()

    /** Falla tras todos los intentos; [reason] es lo que el modelo verá. */
    data class Invalid(val reason: String) : SanitizedToolCall()
}
```

`repaired = true` cuando el JSON parseó recién después de `repairJson()`
(comillas curvas, comas colgantes, fences) o cuando hubo que recortar el
objeto externo con `cutToOuterBraces`. Es un dato distinto de `Invalid`:
una llamada reparada **funcionó**, pero el modelo la escribió mal.

> Compatibilidad: `Valid` y `Invalid` se construyen en el sanitizador y se
> leen en `ToolCallSanitizerTest`; agregar un parámetro con valor por defecto
> al final **no rompe** el test portado (la igualdad compara `repaired ==
> false` en los dos lados).

**f) El bucle cuenta rondas y motivo de corte**

```kotlin
/** Por qué terminó el bucle del agente. */
enum class AgentStopReason(val label: String) {
    ANSWERED("El modelo respondió"),
    ITERATION_CAP("Agotó el tope de rondas"),
    CONSECUTIVE_ERRORS("Tres fallos de herramienta seguidos"),
    SANITIZE_CAP("Demasiadas llamadas rotas"),
    PERMISSION_REPEAT("Insistió tras un permiso denegado"),
    NO_TOOLS("No hay herramientas disponibles"),
}
```

En `run()`:

```kotlin
var rounds = 0
var stopReason = AgentStopReason.ANSWERED
for (iteration in 1..maxIterations) {
    rounds++
    ...
    if (denied && denials >= 2) { stopReason = AgentStopReason.PERMISSION_REPEAT; break }
    ...
}
// Si se agotó el rango sin break explícito:
if (rounds >= maxIterations && stopReason == AgentStopReason.ANSWERED &&
    ultimaRondaPidioHerramienta) {
    stopReason = AgentStopReason.ITERATION_CAP
}
```

El `ITERATION_CAP` se detecta con una bandera `cutByCap` que se pone en
`true` en cada `break` por tope y en el `for` cuando se agotan las rondas; es
decir: **el bucle terminó sin que el modelo cerrara con texto**.

> Cuidado con un falso positivo: si en la ronda 8 el modelo pide una
> herramienta y el loop se queda sin rondas, `ITERATION_CAP` es correcto. Si
> en la ronda 8 el modelo responde con texto, el loop sale por `break` con
> `ANSWERED`. Por eso el corte se marca explícitamente en cada `break`.

**g) El mapeo al dominio**

`:backend` depende de `:core` (`api(project(":core"))`), así que el mapeo vive
en `:backend`:

```kotlin
// backend/src/commonMain/kotlin/rag/agent/AgentStatsMapper.kt
package rag.agent

import kitoru.domain.AgentStats
import kitoru.domain.ToolCallStats
import kitoru.domain.ToolOutcome

/** Convierte una llamada ejecutada en el dato que se persiste. */
fun ExecutedTool.toStats(): ToolCallStats = ToolCallStats(
    name = name,
    args = argsJson,
    ok = ok,
    result = result,
    durationMs = durationMs,
    category = category,
    outcome = ToolOutcome.fromName(outcome),
    artifacts = artifacts.size,
    artifactBytes = artifacts.sumOf { it.sizeBytes },
    round = round,
    repaired = repaired,
)

/** Convierte la salida del bucle en el bloque de analítica del mensaje. */
fun AgentAnswer.toAgentStats(): AgentStats = AgentStats(
    mode = mode,
    rounds = rounds,
    stopReason = stopReason,
    sanitizeFails = sanitizeFails,
    repairedCalls = repairedCalls,
    permissionRequests = permissionRequests,
    permissionDenials = permissionDenials,
    deniedCalls = deniedCalls,
)
```

---

## 2. Qué se mide por herramienta

### 2.1 Métricas por herramienta (un renglón por nombre)

| Métrica | Definición | Nota |
|---|---|---|
| `calls` | Invocaciones de esa herramienta | Suma de `toolCalls` con `name == t` |
| `successes` | Invocaciones con `outcome == OK` | — |
| `failures` | Invocaciones con `outcome == FAILED` | Error de la tool, timeout, tool desconocida, argumentos inválidos |
| `denied` | `outcome == DENIED` (el alumno dijo que no) | — |
| `blocked` | `outcome == BLOCKED` (fuera del techo de la cátedra) | No se le pidió nada al alumno |
| `invalid` | `outcome == INVALID` (JSON roto irrecuperable) | No se ejecutó |
| `failureRate` | `failures / calls` | La métrica que mira el docente |
| `medianDurationMs` | Mediana de `durationMs` de las llamadas **ejecutadas** | La mediana, no la media (D6) |
| `avgDurationMs` | Media de las llamadas ejecutadas | Se guarda, se muestra en tooltip |
| `artifacts` | Archivos producidos | Suma de `ToolCallStats.artifacts` |
| `artifactBytes` | Bytes de esos archivos | — |

**Trampa importante:** las llamadas inválidas llegan con `name == "tool_call"`
(el `AgentLoop` las registra con ese nombre centinela,
`AgentLoop.kt:198`). El agregador **no** debe reportar una herramienta
llamada `tool_call`: las clasifica por `outcome == INVALID` y las cuenta en
`invalid`, no en `calls` de ninguna herramienta.

### 2.2 Por categoría (`NONE` / `FILES` / `WEB` / `MCP`)

Se agrega por `ToolCallStats.category`, que se llena desde
`Tool.category.name` en el momento de la llamada:

| Categoría | Herramientas típicas | Qué le dice al docente |
|---|---|---|
| `NONE` | `get_time` | Herramientas inocuas: no piden permiso. |
| `FILES` | `create_file` | El modelo puede escribir en la carpeta del chat. Necesita permiso del alumno en cada uso: es la categoría que más denegaciones genera. |
| `WEB` | `web_search` (todavía no implementada) | Cuando exista: es la que más cambia el perfil de la respuesta (el modelo deja de citar el material y cita la web). |
| `MCP` | `mcp:<servidor>:<tool>` | Herramientas externas: su uso depende de qué MCP tenga la cátedra, no del modelo. |

Si el 100 % del uso es `NONE`, el modelo "usa herramientas" sólo para
`get_time`: eso es el **límite inferior** de "cuánto usa herramientas el
modelo" (y no dice nada de la calidad de la respuesta).

> Nota de implementación: `ToolCategory` debería mudarse a `:core` (como
> pide `domain/08-permisos.md` §6.4) con un `typealias` en `rag.agent`, para
> que `ToolCallStats.category` no tenga que guardarse como `String`. En este
> documento se usa `String` para no atar el diseño a ese refactor; si el
> `typealias` se hace, `category` pasa a ser el enum y `fromName` resuelve
> los JSON viejos.

### 2.3 Duración

- Se mide desde **antes** de `execute()` hasta después, con
  `kitoru.platform.nowMillis()` (portable).
- Las llamadas **denegadas o bloqueadas** duración 0 (no se ejecutaron): no
  entran en la media ni en la mediana.
- Se excluyen de la duración las llamadas `INVALID` (tampoco se ejecutaron).
- **Se muestra la mediana.** Motivo concreto: `AgentLoop.DEFAULT_TOOL_TIMEOUT_MS`
  es 60 000 ms; una sola tool colgada en 20 llamadas sube la media de 300 ms a
  3 250 ms y hace creer que todas las llamadas son lentas. La mediana no se
  mueve con un solo valor extremo.

### 2.4 Artefactos

- `artifacts` (cantidad) y `artifactBytes` (bytes) por herramienta y por
  categoría.
- **No se agregan los nombres de archivo**: el nombre lo elige el modelo a
  partir del pedido del alumno (`"notas-final-v2.md"`), o sea que es texto
  derivado del usuario. Se puede agregar por **extensión** (`.md`, `.py`, …),
  que es metadata técnica.
- Si un día se quiere el nombre, es un drill-down con `CHAT_VIEW_OTHER`
  (texto), no una métrica agregada.

---

## 3. Efecto sobre la calidad: ¿usar `create_file` mejora o empeora la tasa de citas respaldadas?

Esta es la pregunta central del documento y la que **no se puede responder
contando números**.

### 3.1 Por qué no alcanza con mirar

La intuición es: "las respuestas que usaron `create_file` citan menos → la
herramienta dispersa al modelo". El problema es que **los dos grupos no son
comparables**:

- Una respuesta que dispara `create_file` es, casi siempre, la respuesta a un
  pedido del tipo "guardame este código / armame este documento". Ese tipo de
  pedido **no tiene material de la cátedra que citar**. Entonces el brazo "con
  herramienta" no es "la misma pregunta con una herramienta encima": es
  **otra pregunta**.
- Lo mismo pasa con `get_time`: se pide en consultas de agenda, no de
  concepto.
- Además, el modelo que usa herramientas suele ser el más grande (o el que
  está con las herramientas habilitadas), y puede tener otra tasa de citas
  respaldadas por motivos que no tienen nada que ver con las herramientas.

Conclusión: **la comparación es observacional y está confundida por el tipo de
pregunta**. El diseño no la puede eliminar; lo que puede hacer es (a) medirla
con honestidad estadística, (b) reducir los confoundores que se pueden reducir,
(c) declarar qué NO se puede concluir.

### 3.2 Variables y unidades

**Unidad = un mensaje del asistente** (`role == "assistant"`) con
`citation != null` (o sea: el verificador de citas corrió sobre esa
respuesta).

Se excluyen del análisis:
- mensajes del usuario,
- mensajes sin `citation` (el verificador no corrió: no hay nada que medir),
- mensajes de hilos de alumnos con visibilidad `NONE` (ya filtrados por
  `AnalyticsAnalyzer`).

**Variable de exposición (el "brazo")**, dos versiones:

| Brazo | Definición | Para qué |
|---|---|---|
| `ANY_TOOL` | `message.toolCalls.isNotEmpty()` (el modelo **intentó** una herramienta) | medir el efecto de "el modelo se metió en herramientas" |
| `TOOL_OK` | el mensaje tiene al menos una llamada con `outcome == OK` (el modelo **obtuvo** un resultado) | medir el efecto de "la herramienta funcionó y su resultado entró al contexto" |
| `TOOL_X` | el mensaje tiene al menos una llamada a la herramienta `X` (p. ej. `create_file`) | medir la hipótesis puntual del enunciado |

Para la hipótesis de `create_file` el contraste principal es
**`create_file` vs. "ninguna herramienta"**, no "create_file vs. cualquier
herramienta": si no, `get_time` (que se pide en otro tipo de consulta)
contamina el grupo de control.

### 3.3 Desenlaces co-primarios (D2)

| # | Desenlace | Cálculo | Interpretación |
|---|---|---|---|
| **O1** | Tasa de respuestas **con respaldo** | entre las respuestas **con al menos una cita**: `respondentes totalmente respaldadas / respondentes citadas` | calidad de la cita |
| **O2** | Tasa de respuestas **citadas** | sobre **todas** las respuestas del brazo: `respondentes con ≥1 cita / respondentes del brazo` | anclaje al material ("¿se dispersa y deja de citar?") |

O2 es el que responde a la hipótesis tal como está enunciada ("cita
menos"). O1 responde a "y cuando cita, ¿cita bien?".

**Por qué separar O2 de O1 y no usar un único número "respuesta bien
anclada":** una respuesta sin citas tiene `CitationStats.supportRate == 0.0`
(el dominio lo define así: `if (total == 0) 0.0`). Si se metiera esa respuesta
en el numerador de O1 como "no respaldada", se mezclarían dos fenómenos
distintos (no citar vs. citar mal) y el número dependería de la proporción de
respuestas sin cita, que es justo lo que se quiere medir.

Se reporta además, **descriptivo** (no inferencial):
- `citationSupportRate` por brazo (agregando citas, no respuestas) — se
  muestra como contexto, no como resultado del test, porque las citas de una
  misma respuesta no son independientes.

### 3.4 El test

#### (a) Estimador e intervalo

- **Diferencia de riesgos** `Δ = p_tools − p_sin_tools`, en puntos
  porcentuales.
- **Intervalo de confianza al 95% de Newcombe (método 10)**, que usa
  intervalos de Wilson para cada brazo:

```
Wilson para un brazo (p̂, n, z = 1.959964):
  centro = (p̂ + z²/(2n)) / (1 + z²/n)
  radio  = z/(1 + z²/n) · sqrt( p̂(1−p̂)/n + z²/(4n²) )
  (l, u) = (centro − radio, centro + radio)

Newcombe para la diferencia d = p1 − p2:
  (d − sqrt((p1−l1)² + (u2−p2)²),  d + sqrt((u1−p1)² + (p2−l2)²))
```

Newcombe es el intervalo correcto acá: el de Wald (p̂ ± 1.96·√(p(1−p)/n))
se sale del rango [0,1] con n chico, y además produce
intervalos demasiado angostos en las colas (exactamente donde van a estar
estos brazos).

#### (b) Test de significación: Fisher exacto

La función es la misma para O1 y para O2; sólo cambia cómo se arma la tabla
2×2. La que se muestra por defecto es la de **O2** (§10.4):

```
              citada    sin citar
con tool        a           b
sin tool       c           d
```

(para O1, la fila "citada" se reemplaza por "respaldada", y sólo se cuentan
las respuestas que citan).

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Stats.kt

/**
 * Test exacto de Fisher bilateral.
 *
 * Suma las probabilidades hipergeométricas de todas las tablas con los
 * mismos márgenes cuya probabilidad es ≤ la de la tabla observada.
 * Es exacto para cualquier n (no requiere asintoticidad) y es el test
 * adecuado con muestras chicas.
 */
fun fisherExactTwoSided(a: Int, b: Int, c: Int, d: Int): Double {
    val n1 = a + b
    val n2 = c + d
    val n = n1 + n2
    if (n == 0 || n1 == 0 || n2 == 0) return 1.0
    val k = a + c                     // successes totales
    val obs = hypergeom(n, k, n1, a) // P(X = a)
    var sum = 0.0
    for (x in max(0, n1 - (n - k))..min(n1, k)) {
        val p = hypergeom(n, k, n1, x)
        if (p <= obs + 1e-12) sum += p
    }
    return min(1.0, sum)
}

/** P(X = x) con X ~ Hypergeom(N, K, n). */
private fun hypergeom(n: Int, k: Int, draws: Int, x: Int): Double {
    if (x < 0 || x > k || draws - x < 0 || draws - x > n - k) return 0.0
    return exp(lnBinom(k, x) + lnBinom(n - k, draws - x) - lnBinom(n, draws))
}

/** ln(C(n, k)) por sumas de log: evita el desbordamiento de Int/Long. */
private fun lnBinom(n: Int, k: Int): Double {
    if (k < 0 || k > n) return Double.NEGATIVE_INFINITY
    var acc = 0.0
    for (i in 0 until k) {
        acc += ln((n - i).toDouble()) - ln((i + 1).toDouble())
    }
    return acc
}
```

`ln` es `kotlin.math.ln` (portable). Todo determinista: mismo input, mismo
p-valor, sin aleatoriedad.

#### (c) Control de confoundidores: permutación estratificada

El test de Fisher **no controla** el tipo de pregunta ni el hilo. Para eso,
el control es un **test de permutación estratificado por hilo**:

- Estratos = `threadId` (dentro de un hilo, la pregunta es parecida y el
  estudiante es el mismo).
- Dentro de cada estrato se barajan las etiquetas "con herramienta" / "sin
  herramienta" de las respuestas, y se recalcula `Δ`.
- El p-valor es la fracción de barajas que dan un `Δ` tan extremo como el
  observado (en valor absoluto, con el signo del observado).

```kotlin
/**
 * Una respuesta del análisis, para el test de permutación.
 *
 * [stratum] es el hilo (o el alumno): dentro de un estrato, las respuestas
 * son comparables (mismo estudiante, tema parecido).
 */
data class PermutationUnit(
    val stratum: Int,
    /** true = la respuesta usó la herramienta. */
    val inToolArm: Boolean,
    /** true = la respuesta tiene al menos una cita (desenlace O2). */
    val cited: Boolean,
)

/**
 * Test de permutación estratificado: baraja el brazo dentro de cada
 * estrato y recalcula la diferencia de proporciones.
 *
 * Enumeración exacta si el número de barajas es manejable; si no, muestreo
 * Monte Carlo con semilla fija (determinista).
 */
fun stratifiedPermutation(
    units: List<PermutationUnit>,
    observed: Double,
    maxExact: Int = 200_000,
    draws: Int = 10_000,
    seed: Long = 0x5EED_1234L,
): Double
```

- El p-valor es la proporción de barajas cuyo `Δ` es **tan extremo como el
  observado** (valor absoluto ≥ el del observado, con el mismo signo).
- El PRNG es un **xorshift64 propio** en `commonMain` (no `kotlin.random`
  con seed no garantizado entre plataformas, ni `java.util.Random`): 8 líneas,
  determinista en todas las plataformas.
- Con `n ≤ 20` por estrato la enumeración exacta es trivial; con 50
  respuestas por brazo son ~10³⁰ barajas → se pasa a Monte Carlo con 10 000
  sorteos (error típico del p-valor ≈ ±0,005).

**Criterio de decisión (explícito, para que el docente no interprete de más):**

Un insight de "las herramientas empeoran la calidad" **sólo** se emite si se
cumplen **las cuatro** condiciones:

1. `status == OK` (n suficiente: ≥ 8 respuestas por brazo);
2. el IC 95% de Newcombe **excluye** el 0 (o sea, no es compatible con
   "no hay diferencia");
3. `|Δ| ≥ 0.10` (10 puntos porcentuales: menos que eso es indistinguible del
   ruido de un curso);
4. el p de Fisher y el de la permutación estratificada **coinciden en signo**
   (los dos sugieren la misma dirección).

Si el IC incluye el 0 pero `Δ` es grande, el insight dice
*"indicio, todavía no concluyente"* y **no** propone cambiar nada.

### 3.5 Los límites, con números

Esto es lo que hay que escribirle al docente, no escondido:

**a) El test exacto no siempre puede dar p < 0,05.** Con la separación
completa (todos los de un brazo respaldados y ninguno del otro), el menor
p-valor bilateral que Fisher puede alcanzar es:

| Respuestas por brazo | p mínimo (separación completa) |
|---|---|
| 3 | 0,10 → **imposible** llegar a 0,05 |
| 4 | 0,029 |
| 5 | 0,0079 |
| 8 | 0,00016 |

Con 3 respuestas por brazo **ningún resultado** puede ser significativo.
Por eso D5 pone el piso en 8 por brazo.

**b) Falta potencia.** Para detectar una diferencia de 15 puntos porcentuales
(0,80 contra 0,65) con α = 0,05 y potencia 80 % hacen falta:

```
n por brazo = ( z_{α/2}·√(2·p̄·q̄) + z_β·√(p₁q₁ + p₂q₂) )² / Δ²
            = ( 1,96·√(2·0,725·0,275) + 0,8416·√(0,8·0,2 + 0,65·0,35) )² / 0,15²
            ≈ 138 respuestas por brazo
```

Al revés: con 30 respuestas por brazo, el efecto mínimo detectable al 80 %
de potencia es de ~32 puntos porcentuales. Es decir: **con una cohorte
pequeña, la única diferencia que se puede detectar es enorme**. Por eso el
resultado se presenta como intervalo ("−18 pp, IC [−30, −6]"), que sí
informa aunque no sea significativo, y con el tamaño del efecto al lado.

**c) La confusión por tipo de pregunta no se puede eliminar con estos
datos.** Lo que sí se puede hacer:

- **Sensibilidad por hilo** (permuta estratificada): si el efecto desaparece
  al comparar dentro de los mismos hilos, el efecto era de "qué se pregunta",
  no de "qué herramientas se usan".
- **Sensibilidad por número de citas**: si el brazo con herramientas tiene
  sistemáticamente menos citas por respuesta, parte del Δ de O1 se explica
  por el denominador, no por la calidad.
- **Exclusión por palabras clave** (sólo como análisis exploratorio, con
  todas las advertencias): se pueden excluir del análisis las preguntas
  del usuario que contienen "archivo", "código", "guardá", "notas", etc.,
  porque son las que disparan `create_file`. Es una heurística cruda y puede
  dejar afuera respuestas buenas; se reporta como "análisis exploratorio",
  nunca como el número principal.
- **Diferencia en diferencias** si el docente activa las herramientas en una
  fecha conocida (p. ej. "a partir del 12/03 habilité `create_file`"): se
  compara el cambio de la tasa de respuestas citadas antes y después, entre
  los alumnos que usaron herramientas y los que no. Es el diseño
  observacional más fuerte que se puede hacer sin assignment aleatorio, y
  Kitoru puede sostenerlo porque la app guarda la fecha de cada mensaje.

**d) La calidad de la cita tampoco es perfecta.** `CitationVerifier` es
determinista pero literal: no entiende paráfrasis. Una respuesta bien
redactada con paráfrasis puede marcarse como "sin respaldo". Eso **infla**
la tasa de "sin respaldo" en los dos brazos por igual (ruido no
diferencial), pero si el modelo con herramientas escribe de otra manera
(con código, con viñetas, con tablas), el verificador puede castigar más a
ese brazo: es un sesgo **diferencial** que va en contra de la hipótesis, o
sea que la hipótesis está medida con un sesgo que la **subestima**. No se
puede corregir sin metadatos extra; queda anotado.

### 3.6 Qué NO se concluye

Nunca, con esta analítica:

- "la herramienta `create_file` **causa** menos citas respaldadas" → sólo
  "se asocia con".
- "el modelo con herramientas es **peor**" → la comparación no es entre
  modelos, es entre tipos de respuesta.
- "los alumnos que usan herramientas rinden menos" → el rendimiento del
  alumno no se mide acá (y no debe medirse con esto).

---

## 4. Permisos: "peticiones de herramientas por chat"

### 4.1 Las tres cifras

| Cifra | Definición | Dónde sale |
|---|---|---|
| `permissionRequests` | **Diálogos de permiso abiertos** al alumno | `PermissionOutcome.Asked` |
| `permissionDenials` | De esos diálogos, los que el alumno rechazó | `PermissionDecision.DENY` dentro de `Asked` |
| `deniedCalls` | Llamadas que **no se ejecutaron** por permiso (incluye las repeticiones automáticas que el bucle corta) | `DENIED` + `BLOCKED` |

La distinción importa: después de una denegación el `AgentLoop` **no vuelve
a preguntar** (`if (denials > 0) PermissionDecision.DENY`), así que una
segunda insistencia del modelo **no genera un diálogo nuevo** pero sí una
llamada denegada. Si se contaran sólo los diálogos, se perdería el
comportamiento "el modelo insiste", que es justo lo que el docente quiere ver.

### 4.2 La métrica pedida: peticiones por chat

```
requestsPerChat = permissionRequests / chatsConPeticiones
```

Se reporta **por chat**, no por mensaje, porque el permiso es **por chat**
(la concesión vive en el chat, `ToolContext.grantedCategories`):

| Indicador | Significado para el docente |
|---|---|
| `requestsPerChat` | "En promedio el modelo pide autorización 1,4 veces por chat" |
| `chatsWithDenial` | "En el 23% de los chats el alumno tuvo que decir que no" |
| `denialRate` | `permissionDenials / permissionRequests` — **la métrica clave**: qué fracción de lo que el modelo pide, el alumno no quiere |
| `repeatAfterDenial` | `deniedCalls − permissionDenials` — cuántas veces el modelo insistió después de un "no" |

### 4.3 Cómo se lee

- `denialRate` **alto** (> 30 %) → el modelo pide cosas que el alumno no
  quiere. Dos causas posibles, y el dato no las separa: (a) el catálogo de
  herramientas incluye cosas que no le sirven a la materia; (b) el prompt no
  le dice cuándo **no** usar herramientas. La acción del insight es probar
  (b) primero, porque es gratis: ajustar el prompt.
- `repeatAfterDenial` **> 0** en un porcentaje alto de chats → el modelo no
  respeta la negativa. Eso es un problema del modelo (o del prompt), no del
  alumno, y es un argumento fuerte para pedir un modelo con mejor
  tool-calling.
- `requestsPerChat` **muy alto** (> 3) → el alumno está gastando clics en diálogos. La acción: o se conceden las categorías de una vez (la concesión "siempre" por chat), o se saca la herramienta del catálogo.

---

## 5. El bucle del agente: rondas, topes y fallos

### 5.1 Métricas derivadas de los eventos

Del `AgentAnswer` (rondas, motivo de corte, sanitizaciones) y de los
`ToolStart`/`ToolEnd`:

| Métrica | Definición | Por qué |
|---|---|---|
| `avgRounds` | Rondas modelo+herramienta por respuesta | Una respuesta normal son 2 rondas (preguntar + responder). |
| `roundsHistogram` | Distribución de rondas (1, 2, 3, …, tope) | Ver la forma de la cola. |
| `iterationCapRate` | `respuestas con stopReason == ITERATION_CAP / respuestas` | **"el modelo se dispara"**: pidió herramientas sin parar. |
| `toolFailureRate` | `llamadas con outcome == FAILED / llamadas` | Tool rota o mal usada. |
| `consecutiveErrorCutRate` | `respuestas con stopReason == CONSECUTIVE_ERRORS / respuestas` | El bucle se rindió por 3 fallos seguidos: la tool está rota o el modelo no la usa bien. |
| `permissionRepeatCutRate` | `stopReason == PERMISSION_REPEAT / respuestas` | El modelo insistió tras un "no". |

### 5.2 Interpretación por rango

| `avgRounds` | Lectura |
|---|---|
| 1,0 – 1,2 | El modelo casi no usa herramientas (o no las tiene). |
| 1,5 – 2,5 | Normal: pide una herramienta y responde. |
| > 3,0 | El modelo se enreda: está probando herramientas, falla y reintenta. |
| `iterationCapRate` > 5 % | El 5 % de las respuestas **no terminó**: el modelo se dispara. Una acción concreta: bajar `maxIterations` de 8 a 4 (cuesta latencia, no calidad) o revisar el prompt. |

> El tope de 8 rondas es un default de `AgentLoop.maxIterations`. Si el
> docente lo cambia, el número "cuántas respuestas consumieron el tope" hay
> que interpretarlo contra **el tope que estaba vigente en ese momento**, no
> contra 8. Por eso `AgentStats` no guarda el tope: la tasa se calcula
> como `stopReason == ITERATION_CAP`, que es independiente del valor.

---

## 6. `ToolCallSanitizer`: el modelo y el protocolo

### 6.1 Qué mide

| Cifra | Definición | Origen |
|---|---|---|
| `invalidCalls` | Llamadas con `outcome == INVALID` (el sanitizador no pudo repararlas) | `sanitizeFails` del bucle |
| `repairedCalls` | Llamadas que el sanitizador **sí** reparó | nuevo (`repaired` en `SanitizedToolCall.Valid`) |
| `sanitizeFailRate` | `invalidCalls / llamadas totales` | — |
| `repairedRate` | `repairedCalls / llamadas totales` | — |

### 6.2 Por qué se separan "reparada" de "irrecuperable"

- Una llamada **reparada** funcionó: el modelo escribió JSON con comillas
  curvas o con texto alrededor y el sanitizador lo arregló. Es una molestia,
  no un problema.
- Una llamada **irrecuperable** no se ejecutó, consumió una ronda y volvió al
  modelo como feedback. Si pasa seguido, el modelo no sabe escribir la
  llamada: **eso es un problema de tool-calling del modelo**.

### 6.3 El matiz que cambia la interpretación: NATIVE vs PROMPT

El bucle tiene dos modos (`AgentLoop.nativeToolCalls`):

| Modo | Cómo pide la herramienta | Qué significa un fallo de sanitización |
|---|---|---|
| `NATIVE` | el endpoint OpenAI-compatible devuelve `tool_calls` | **Muy grave**: el servidor debería devolver JSON válido. O el modelo no sabe usar tool-calling nativo, o el gateway está roto. |
| `PROMPT` | el modelo escribe un bloque ```` ```tool_call ```` en el texto | **Esperable**: el protocolo es una instrucción en el prompt; los modelos chicos fallan mucho. |

Por eso `AgentStats.mode` se persiste y el insight de sanitización **distingue
los dos casos**:

- `NATIVE` con `sanitizeFailRate > 3 %` → *"El endpoint de herramientas está
  devolviendo llamadas mal formadas: revisá el gateway o el modelo."*
- `PROMPT` con `sanitizeFailRate > 10 %` → *"El modelo local no maneja bien el
  protocolo de herramientas: probá un modelo más grande con mejor
  tool-calling."*

### 6.4 La decisión que el docente quiere tomar

> "El modelo no maneja bien el protocolo de herramientas → ¿conviene un modelo
> más grande?"

Los datos que la sostienen:

1. `sanitizeFailRate` (modo NATIVE: > 3 %; modo PROMPT: > 10 %).
2. `toolFailureRate` alto **con** `invalidCalls` bajos → el modelo pide bien
   pero la herramienta falla: el problema es la herramienta, no el modelo.
3. `repairedCalls` altos con `invalidCalls` bajos → el modelo "anda" pero con
   ayuda: no justifica cambiar de modelo.
4. `iterationCapRate` alto junto con `invalidCalls` altos → el modelo se
   dispara **porque** las llamadas no salen: sí justifica cambiar de modelo.

La conclusión se arma con esas cuatro señales, no con una sola.

---

## 7. La vista del docente: una frase accionable

### 7.1 Anatomía de un insight

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ToolInsights.kt
package kitoru.domain

/** Gravedad de un hallazgo. */
enum class InsightSeverity(val label: String) {
    INFO("Info"),
    WARN("Atención"),
    ACTION("Actuá"),
}

/**
 * Un hallazgo accionable de la analítica de herramientas.
 *
 * [headline] es la frase que el docente lee primero: dice el hecho y la
 * acción. [evidence] son los números con los que se puede discutir.
 */
data class ToolInsight(
    /** Identificador estable de la regla (para tests y para deduplicar). */
    val id: String,
    val severity: InsightSeverity,
    /** Una frase: el hecho + la acción. */
    val headline: String,
    /** La acción concreta, en imperativo. */
    val action: String,
    /** Números de respaldo ("312 llamadas · 94 fallos · 30%"). */
    val evidence: List<String> = emptyList(),
    /** StudentId vacío = insight de cátedra. */
    val studentId: String = "",
    /** A qué se refiere ("create_file", "bucle", "permisos"). */
    val topic: String = "",
)
```

Un insight **nunca** es un párrafo: es una frase, una acción y los números.
El cuerpo largo (tablas, comparaciones) vive en el detalle, no en el
resumen.

### 7.2 Catálogo de reglas

Todas las reglas tienen un **mínimo de datos** (D5). Si no se alcanza, la
regla no se dispara: no hay insights con "0 llamadas".

| # | `id` | Condición | Severidad | Acción propuesta |
|---|---|---|---|---|
| R0 | `tool.ok` | `calls ≥ 20` y `failureRate < 5 %` y `denialRate < 10 %` | INFO | "No hace falta tocar nada." |
| R1 | `tool.usage.frequent` | `replies ≥ 30` y `toolUsageRate > 0,40` | WARN | "Revisá el prompt: el modelo se dispara con herramientas." |
| R2 | `tool.failure.high` | existe una tool con `calls ≥ 20` y `failureRate ≥ 0,25` | ACTION | "Probá un modelo con mejor tool-calling o revisá la definición de la tool." |
| R3 | `tool.protocol.breaks` | `calls ≥ 20` y `sanitizeFailRate ≥ 0,03` (NATIVE) o `≥ 0,10` (PROMPT) | ACTION | "Probá un modelo con mejor tool-calling." / "Revisá el gateway." |
| R4 | `tool.loop.exhausted` | `replies ≥ 30` y `iterationCapRate ≥ 0,05` | WARN | "Bajá el tope de rondas o ajustá el prompt." |
| R5 | `tool.denial.high` | `permissionRequests ≥ 10` y `denialRate ≥ 0,30` | WARN | "Revisá qué herramientas habilitás y ajustá el prompt." |
| R6 | `tool.concentration` | una tool concentra `≥ 0,70` de las llamadas y `calls ≥ 20` | INFO | "El modelo usa casi sólo una herramienta: revisá si es la que querés." |
| R7 | `tool.grounding.drop` | comparación `OK`, IC 95% excluye 0, `Δ ≤ −0,10`, Fisher y permutación coinciden | ACTION | "Probá desactivar `create_file` una semana y compará." |
| R8 | `tool.never.called` | tool habilitada en la cátedra con `calls == 0` y `replies ≥ 30` | INFO | "Sacala del catálogo: cada herramienta ocupa tokens del prompt." |
| R9 | `tool.repeat.denied` | `repeatAfterDenial ≥ 3` y `chatsWithDenial ≥ 5` | ACTION | "El modelo insiste tras un no: probá otro modelo." |

### 7.3 El generador

```kotlin
object ToolInsightGenerator {
    /**
     * Genera los hallazgos de la analítica de herramientas.
     *
     * Función PURA: mismos datos → mismos insights, en el mismo orden.
     * Orden: severidad (ACTION, WARN, INFO) y, dentro de la misma, por
     * impacto (nº de respuestas afectadas) y finalmente por id (estable).
     */
    fun generate(
        course: Course,
        analytics: CourseAnalytics,
        registry: List<CourseToolRule> = course.toolRules,
    ): List<ToolInsight>
}
```

Reglas de implementación:

- **Un insight por regla.** Si dos tools fallan mucho, se informa la peor
  (más llamadas afectadas), y las demás quedan en la tabla de detalle.
- **Orden estable** por (severidad, impacto desc, id asc): el docente ve
  siempre lo mismo en el mismo orden, y los tests pueden comparar.
- **Umbrales en constantes nombradas** (`object ToolThresholds`), no
  números sueltos: los tests los usan y el docente puede adjustarlos sin
  tocar la lógica.
- **Nada de texto generado por un modelo**: las frases se arman con
  plantillas y números. Es determinista y auditable.

### 7.4 Ejemplos reales (con los números del enunciado)

Con 320 respuestas, 38 con herramientas (11,9 %), 45 llamadas de las cuales
41 son `create_file` (91 %) y 13 fallan (31,6 % de las de `create_file`):

```
ACTION  ·  El modelo falla seguido con create_file
         13 de 41 llamadas a create_file fallaron (32%).
         Probá un modelo con mejor tool-calling.

INFO    ·  El modelo casi sólo usa una herramienta
         41 de 45 llamadas fueron create_file (91%).
```

Las dos frases del enunciado ("el modelo usa herramientas en el 12% de las
respuestas; el 90% de esas son `create_file` y fallan el 30% de las veces")
**salen de dos reglas distintas** (R6 y R2). El panel las muestra juntas y
arriba del todo pone la de mayor severidad.

Un insight con el test estadístico (ejemplo con n chico):

```
ACTION  ·  Las respuestas con create_file citan menos
         41% con cita contra 68% sin herramienta (−27 pp, IC 95% [−48, −4],
         n = 22 vs 118, Fisher p = 0,03).
         Es un indicio, no una prueba: probá desactivar create_file una
         semana y compará.
```

Y el caso "no hay datos":

```
INFO    ·  Todavía no hay datos suficientes
         6 respuestas usaron herramientas y 14 no: hace falta al menos 8
         por grupo para comparar. Seguí chateando.
```

### 7.5 Formateo de números (portable)

`String.format` no existe en `commonMain`. Un formateador propio, sin
dependencia de locale:

```kotlin
/** Porcentaje entero con signo "%" (0..1 → "12%"). */
fun pct(v: Double): String = "${(v * 100).roundToInt()}%"

/** Puntos porcentuales con signo ("-27 pp"). */
fun pp(v: Double): String = "${if (v >= 0) "+" else "−"}${(abs(v) * 100).roundToInt()} pp"

/** Miles con punto ("1 204"). Sin locale: se separa a mano. */
fun grouped(n: Int): String { ... }
```

Los p-valores se muestran con 3 decimales (`0,031`) o como `< 0,001`.

---

## 8. Qué es útil y qué es ruido (y qué ve el alumno)

### 8.1 Para el docente: la lista

| Métrica | ¿Útil? | Por qué |
|---|---|---|
| Tasa de uso de herramientas (respuestas con herramienta) | **Sí** | El número de arriba de todo: "el modelo usa herramientas en el 12% de las respuestas". |
| Distribución por herramienta | **Sí** | Dice qué está usando el modelo. |
| Tasa de fallo por herramienta | **Sí** | Dice si la herramienta sirve. |
| Denegaciones y peticiones por chat | **Sí** | Dice si el modelo pide lo que el alumno no quiere. |
| Rondas medias y tope consumido | **Sí** | Dice si el modelo se dispara. |
| Sanitizaciones | **Sí** | Dice si el modelo maneja el protocolo. |
| Artefactos (cantidad y bytes) | **Sí** | Dice cuánto material produce la herramienta. |
| Comparación con la calidad (O1/O2) | **Sí, con cuidado** | La pregunta del enunciado. Se muestra con IC y "es un indicio". |
| Duración por llamada | **Medio** | Sólo como mediana por herramienta, y sólo si hay ≥ 20 llamadas. |
| Latencia total de la respuesta | **Ya existe** | En `avgLatencyMs`. |
| Duración de cada llamada individual | **Ruido** | Nadie la va a mirar; el timeout la distorsiona. |
| Contenido de `args` y de `result` | **Ruido y prohibido** | Es texto del alumno (D13). |
| Nombres de archivo | **Ruido** | Contenido derivado del pedido del alumno. Se agrega por extensión. |
| Tokens gastados en el resultado de la tool | **Ruido** | No se mide. |
| Qué tool pidió cada alumno | **Medio** | Sólo agregado por alumno y con umbral de supresión (§8.3). |

### 8.2 Para el alumno: qué se decide

**Decisión (D12): al alumno se le muestran únicamente los conteos de su
propia interacción con las herramientas, y nada más.**

| Se muestra al alumno | No se muestra al alumno |
|---|---|
| "En este chat el modelo pidió 3 herramientas; autorizaste 2 y denegaste 1." | La tasa de citas respaldadas (ni la suya ni la de la cátedra). |
| "Recibiste 2 archivos generados por el modelo." | La comparación con/sin herramientas. |
| En el chat: las tarjetas de herramienta que ya ve. | Los p-valores, los intervalos, el "probá otro modelo". |
| Si usa IA local: un diagnóstico técnico de su propia sesión. | Cualquier ranking entre alumnos o entre modelos. |

**Por qué esta línea:**

1. **Es lo que le sirve.** Lo que el alumno puede cambiar es su relación con
   el diálogo de permiso ("si no querés que cree archivos, no lo autorices") y
   el modelo que eligió cuando usa IA local. Todo lo demás no lo controla.
2. **Es lo que ya vio.** Los conteos de permisos y archivos son cosas que
   ocurrieron **en su pantalla**: mostrárselos no inventa nada nuevo.
3. **Lo demás se puede volver en contra del alumno.** Una cifra como "tu tasa
   de citas respaldadas cayó 12 pp" (aunque sea una asociación, no una
   causalidad) se vive como una nota. Y peor: puede empujar a evitar las
   herramientas para "mejorar el número", perdiendo la capacidad de pedir un
   archivo o un cálculo.
4. **Es coherente con la visibilidad.** `ChatVisibility.METRICS_ONLY` ya
   define que las "métricas" (conteos, nombres de herramienta, estados) son
   del docente y el texto no. Agregar una analítica de calidad al alumno
   inventa una tercera categoría que el modelo de visibilidad no tiene.

**Qué pasa con el `NONE`:** un alumno en `NONE` no aparece en ningún
agregado del docente (ya está así en `AnalyticsAnalyzer`), y el propio alumno
sí puede ver sus conteos (son suyos, y son locales).

### 8.3 Umbral de supresión

Para que un conteo por alumno no sea un dato aislado (y para no exponer a
alumnos con 1-2 llamadas), **no se muestran filas por alumno con menos de 5
llamadas de herramientas**. El panel muestra el agregado de la cátedra y
lista los alumnos **por encima del umbral**; los que quedan por debajo se
cuentan en una línea agregada ("4 alumnos con menos de 5 llamadas").

Es el mismo criterio k-anónimo que se usa para no publicar celdas con muy
pocos casos: se puede ver el agregado sin poder singularizar a alguien con un
dato mínimo.

### 8.4 Dónde vive cada vista

| Vista | Audience | Contenido |
|---|---|---|
| "Herramientas" (pestaña de la analítica de cátedra) | Docente | R0–R9 + tabla por herramienta + comparación de calidad + desglose por alumno (con umbral) |
| "Herramientas" en el panel de un alumno | Docente | Mismo generador, `studentId` lleno; sin la comparación de calidad si el alumno tiene < 8 respuestas en cada brazo |
| Ficha del mensaje (visor de chats) | Docente | Las tarjetas de herramienta que ya existen (duración, estado, artefactos) |
| Ficha del mensaje | Alumno | Idéntica (ya la ve) |
| Resumen "Tus herramientas" | Alumno | 3 números: llamadas, diálogos de permiso, denegaciones |

---

## 9. Cambios en el modelo

### 9.1 `ToolCallStats` (extensión exacta)

Archivo: `core/src/commonMain/kotlin/kitoru/domain/Chat.kt`

```kotlin
/**
 * Resultado de una llamada a herramienta, desde el punto de vista del bucle.
 *
 * Distingue los cinco finales posibles, que antes se confundían todos en
 * `ok = false`:
 *  - OK:      se ejecutó y terminó bien.
 *  - FAILED:  se ejecutó y falló (error, timeout, tool desconocida).
 *  - DENIED:  el alumno no autorizó (hubo diálogo y dijo que no).
 *  - BLOCKED: la cátedra no permite esa categoría (ni se le preguntó).
 *  - INVALID: la llamada llegó rota y no se pudo sanear (no se ejecutó).
 */
@Serializable
enum class ToolOutcome(val label: String) {
    UNKNOWN("Desconocido"),
    OK("Completada"),
    FAILED("Falló"),
    DENIED("Denegada por el alumno"),
    BLOCKED("Bloqueada por la cátedra"),
    INVALID("Llamada inválida"),
    ;

    /** Lee un outcome persistido; tolera nombres desconocidos. */
    companion object {
        fun fromName(name: String): ToolOutcome =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Herramienta que el modelo pidió durante una respuesta. */
@Serializable
data class ToolCallStats(
    val name: String,
    val args: String = "",
    /** null = la herramienta se está ejecutando. */
    val ok: Boolean? = null,
    val result: String = "",
    val durationMs: Long = 0L,
    // ---- nuevos (todos con valor por defecto) ----
    /** Categoría de permiso: "NONE" | "FILES" | "WEB" | "MCP". */
    val category: String = "",
    /** Resultado de la llamada (ver [ToolOutcome]). */
    val outcome: ToolOutcome = ToolOutcome.UNKNOWN,
    /** Cantidad de archivos que produjo. */
    val artifacts: Int = 0,
    /** Bytes totales de esos archivos. */
    val artifactBytes: Long = 0L,
    /** Ronda del bucle en la que se pidió (1 = la primera). */
    val round: Int = 0,
    /** true si el sanitizador tuvo que reparar el JSON de la llamada. */
    val repaired: Boolean = false,
) {
    /**
     * ¿La llamada se ejecutó con éxito?
     *
     * `outcome` manda cuando se conoce; si no (mensajes viejos), se cae al
     * `ok` de siempre.
     */
    val succeeded: Boolean
        get() = when (outcome) {
            ToolOutcome.OK -> true
            ToolOutcome.UNKNOWN -> ok == true
            else -> false
        }

    /** Categoría legible; "NONE" si no se conoce. */
    val categoryName: String get() = category.ifBlank { "NONE" }
}
```

**Por qué `category` es `String` y no el enum `ToolCategory`:** `ToolCategory`
vive hoy en `:backend` (`rag.agent`), y `:core` no puede depender de `:backend`.
El doc `domain/08-permisos.md` §6.4 ya resuelve esto moviendo
`ToolCategory` a `:core` con un `typealias` en `rag.agent`. **Si se aplica ese
refactor**, `category` pasa a ser `ToolCategory` y `ToolCategory.fromName()`
(respetando mayúsculas) resuelve los JSON viejos. Hasta entonces, `String` +
`ToolCategory.fromName()` del lado de `:backend` es el camino compatible.

### 9.2 `AgentStats` (nuevo) y su campo en `ChatMessage`

```kotlin
/**
 * Resumen del bucle del agente para una respuesta.
 *
 * Es lo que hoy se calcula y se tira: las rondas, el motivo de corte, las
 * sanitizaciones fallidas y las peticiones de permiso. Sin esto no hay forma
 * de saber si el modelo se disparó ni si el alumno denegó herramientas.
 */
@Serializable
data class AgentStats(
    /** "NATIVE" (tool_calls del endpoint) | "PROMPT" (bloque ```tool_call) | "". */
    val mode: String = "",
    /** Rondas modelo+herramienta consumidas. */
    val rounds: Int = 0,
    /** Por qué terminó el bucle (ver rag.agent.AgentStopReason). */
    val stopReason: String = "",
    /** Llamadas que el sanitizador no pudo reparar. */
    val sanitizeFails: Int = 0,
    /** Llamadas que el sanitizador reparó. */
    val repairedCalls: Int = 0,
    /** Diálogos de permiso abiertos al alumno. */
    val permissionRequests: Int = 0,
    /** Permisos denegados por el alumno. */
    val permissionDenials: Int = 0,
    /** Llamadas no ejecutadas por permiso (denegadas o bloqueadas). */
    val deniedCalls: Int = 0,
)
```

Y en `ChatMessage`:

```kotlin
@Serializable
data class ChatMessage(
    val id: String,
    val threadId: String,
    val role: String,
    val text: String,
    val createdAt: Long = 0L,
    val sources: List<String> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val latencyMs: Long = 0L,
    val tokensOut: Int = 0,
    val tokensIn: Int = 0,
    // ---- nuevo ----
    /** Datos del bucle del agente (sólo en respuestas del asistente). */
    val agent: AgentStats? = null,
)
```

`agent` es **nullable** a propósito: las respuestas sin herramientas no
cargan el bloque (`"agent": null` en el JSON, ~16 bytes), y las anteriores a
esta versión no tienen la clave.

### 9.3 Serialización y compatibilidad

`KitoruJson` (core/.../domain/JsonStore.kt) ya tiene
`ignoreUnknownKeys = true` y `encodeDefaults = true`:

| Situación | Resultado |
|---|---|
| JSON viejo (sin `outcome`, sin `agent`) leído por el código nuevo | OK: los campos nuevos tienen valor por defecto. `outcome = UNKNOWN` y el agregador cae al `ok` de siempre. |
| JSON nuevo leído por un código viejo | OK: `ignoreUnknownKeys` descarta `outcome`, `category`, `agent`, etc. |
| JSON viejo de `ChatMessage` sin `agent` | OK: `agent = null`. |

Reglas:

- **Todos los campos nuevos llevan valor por defecto.** Ningún campo sin
  default: rompería la lectura de los `chats.json` ya guardados.
- **`ToolOutcome` se serializa por nombre** (`@Serializable enum`). El
  `fromName` tolera nombres desconocidos → `UNKNOWN` (no excepción): un
  `chats.json` de una versión futura con un outcome nuevo no rompe la app.
- **No se cambia el nombre de ninguna clave existente.** `ok` sigue
  significando "terminó bien" y se conserva para el código y los JSON
  viejos.
- **Tamaño**: `agent` es `null` en la mayoría de los mensajes → +16 bytes por
  mensaje. `ToolCallStats` crece ~120 bytes **por llamada de herramienta**
  (que es un porcentaje mínimo de los mensajes). Aceptable.

Ejemplo de un mensaje persistido nuevo:

```json
{
  "id": "m-1042",
  "threadId": "t-88",
  "role": "assistant",
  "text": "Acá tenés el resumen…",
  "createdAt": 1767225600000,
  "sources": ["corpus: Apuntes-01.pdf"],
  "citation": { "verified": 2, "unsupported": 0, "unknown": 0 },
  "toolCalls": [
    {
      "name": "create_file",
      "args": "{\"path\":\"resumen.md\",\"content\":\"…\"}",
      "ok": false,
      "result": "La extensión \".pdf\" no está permitida. Extensiones válidas: …",
      "durationMs": 41,
      "category": "FILES",
      "outcome": "FAILED",
      "artifacts": 0,
      "artifactBytes": 0,
      "round": 1,
      "repaired": false
    }
  ],
  "latencyMs": 3120,
  "tokensOut": 412,
  "tokensIn": 118,
  "agent": {
    "mode": "NATIVE",
    "rounds": 2,
    "stopReason": "ANSWERED",
    "sanitizeFails": 0,
    "repairedCalls": 0,
    "permissionRequests": 1,
    "permissionDenials": 0,
    "deniedCalls": 0
  }
}
```

### 9.4 Compatibilidad con el modelo de visibilidad

- `ToolCallStats.name`, `category`, `outcome`, `round`, `durationMs`,
  `artifacts`, `repaired` son **metadatos técnicos** → visibles para el
  docente también en `METRICS_ONLY` (el doc `teacher/07-chats.md` §3.3 ya
  lista `toolCalls (nombre, args, estado, duración)` como métrica).
- `ToolCallStats.args` y `result` son **texto** → en `METRICS_ONLY` se
  ocultan (o se truncan a 200 caracteres, como ya se decidió para `args`)
  y **nunca se agregan**.
- `AgentStats` es 100 % metadatos → visible siempre.

---

## 10. Agregaciones

### 10.1 Tipos nuevos (`:core`)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ToolAnalytics.kt
package kitoru.domain

/** Uso de una herramienta concreta. */
@Serializable
data class ToolUsageStat(
    val name: String,
    /** "NONE" | "FILES" | "WEB" | "MCP". */
    val category: String = "NONE",
    val calls: Int = 0,
    val failures: Int = 0,
    val denials: Int = 0,
    val blocked: Int = 0,
    val invalid: Int = 0,
    val avgDurationMs: Long = 0L,
    val medianDurationMs: Long = 0L,
    val artifacts: Int = 0,
    val artifactBytes: Long = 0L,
) {
    /** Tasa de fallo sobre las llamadas de esta herramienta. */
    val failureRate: Double get() = if (calls == 0) 0.0 else failures.toDouble() / calls
}

/** Uso de una categoría de herramientas. */
@Serializable
data class ToolCategoryUsage(
    val category: String,
    val calls: Int = 0,
    val failures: Int = 0,
    val denials: Int = 0,
)

/**
 * Resumen del uso de herramientas de un alumno o de una cátedra.
 *
 * Es un bloque: se agrega entero y se guarda en `StudentAnalytics` y en
 * `CourseAnalytics`. Los campos derivados son propiedades (no se guardan).
 */
@Serializable
data class ToolUsageSummary(
    /** Llamadas de herramienta (todas). */
    val calls: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    /** Denegadas por el alumno (hubo diálogo y dijo que no). */
    val denials: Int = 0,
    /** Bloqueadas por la política de la cátedra. */
    val blocked: Int = 0,
    /** Llamadas inválidas (JSON roto irrecuperable). */
    val invalid: Int = 0,
    /** Respuestas del asistente que usaron alguna herramienta. */
    val repliesWithTools: Int = 0,
    /** Respuestas del asistente totales (denominador de la tasa de uso). */
    val replies: Int = 0,
    /** Diálogos de permiso abiertos. */
    val permissionRequests: Int = 0,
    /** Permisos denegados por el alumno. */
    val permissionDenials: Int = 0,
    /** Llamadas que no se ejecutaron por permiso (denegadas o bloqueadas). */
    val deniedCalls: Int = 0,
    /** Rondas de agente acumuladas. */
    val agentRounds: Int = 0,
    /** Respuestas que agotaron el tope de rondas. */
    val iterationCapHits: Int = 0,
    /** Respuestas cortadas por 3 fallos de tool seguidos. */
    val consecutiveErrorCuts: Int = 0,
    /** Respuestas cortadas por insistencia tras un permiso denegado. */
    val permissionRepeatCuts: Int = 0,
    /** Llamadas reparadas por el sanitizador. */
    val repairedCalls: Int = 0,
    /** Suma de duraciones de las llamadas ejecutadas (para la media). */
    val toolDurationMs: Long = 0L,
    /** Artefactos producidos. */
    val artifacts: Int = 0,
    val artifactBytes: Long = 0L,
    /** Uso por herramienta (ordenado por llamadas, desc). */
    val perTool: List<ToolUsageStat> = emptyList(),
    /** Uso por categoría. */
    val perCategory: List<ToolCategoryUsage> = emptyList(),
    /** Chats en los que se pidió algún permiso. */
    val chatsWithRequests: Int = 0,
    /** Chats en los que el alumno denegó algún permiso. */
    val chatsWithDenials: Int = 0,
) {
    /** Proporción de respuestas que usaron alguna herramienta (0..1). */
    val toolUsageRate: Double
        get() = if (replies == 0) 0.0 else repliesWithTools.toDouble() / replies

    /** Proporción de llamadas que fallaron (0..1). */
    val failureRate: Double
        get() = if (calls == 0) 0.0 else failures.toDouble() / calls

    /** Proporción de permisos denegados sobre los pedidos (0..1). */
    val denialRate: Double
        get() = if (permissionRequests == 0) 0.0 else permissionDenials.toDouble() / permissionRequests

    /** Peticiones de permiso por chat que las tuvo. */
    val requestsPerChat: Double
        get() = if (chatsWithRequests == 0) 0.0 else permissionRequests.toDouble() / chatsWithRequests

    /** Rondas medias de agente por respuesta. */
    val avgRounds: Double
        get() = if (replies == 0) 0.0 else agentRounds.toDouble() / replies

    /** Proporción de respuestas que agotaron el tope de rondas. */
    val iterationCapRate: Double
        get() = if (replies == 0) 0.0 else iterationCapHits.toDouble() / replies

    /** Proporción de llamadas con JSON inválido. */
    val sanitizeFailRate: Double
        get() = if (calls == 0) 0.0 else invalid.toDouble() / calls

    /** Duración media de las llamadas ejecutadas (la mediana va en perTool). */
    val avgToolDurationMs: Long
        get() = if (calls == 0) 0L else toolDurationMs / calls
}
```

### 10.2 `StudentAnalytics` y `CourseAnalytics`

```kotlin
@Serializable
data class StudentAnalytics(
    // ... los campos actuales ...
    val toolCalls: Int = 0,          // se conserva (deprecated en favor de toolUsage)
    val toolFailures: Int = 0,       // idem
    // ---- nuevo ----
    /** Uso de herramientas del alumno. */
    val toolUsage: ToolUsageSummary = ToolUsageSummary(),
)

@Serializable
data class CourseAnalytics(
    // ... los campos actuales ...
    // ---- nuevo ----
    /** Uso de herramientas de toda la cátedra (agregado de los alumnos). */
    val toolUsage: ToolUsageSummary = ToolUsageSummary(),
    /** Comparación de calidad con/sin herramientas (ver §3). */
    val toolGrounding: GroundingComparison = GroundingComparison(),
    /** Findings accionables para el docente. */
    val toolInsights: List<ToolInsight> = emptyList(),
)
```

**Los campos `toolCalls` y `toolFailures` no se borran**: son parte del
contrato persistido (`StudentAnalytics` se guarda en JSON) y hay código que
los lee. Se marcan como *legacy* en el KDoc y se recalculan desde
`toolUsage` para que nunca divergan:

```kotlin
// En studentStats():
toolCalls = summary.calls,
toolFailures = summary.failures,
```

### 10.3 La comparación de calidad

```kotlin
/** Estado de una comparación estadística. */
enum class ComparisonStatus(val label: String) {
    OK("Calculada"),
    INSUFFICIENT_DATA("Datos insuficientes"),
    SINGLE_ARM("Un solo grupo"),
}

/** Estadísticas de un brazo (con o sin herramienta). */
@Serializable
data class GroundingArm(
    val label: String,          // "create_file" | "sin herramienta"
    /** Respuestas del brazo. */
    val responses: Int = 0,
    /** Respuestas con al menos una cita. */
    val citedResponses: Int = 0,
    /** Respuestas con ≥1 cita y todas verificadas. */
    val backedResponses: Int = 0,
    /** Citas del brazo (descriptivo). */
    val citations: Int = 0,
    val verifiedCitations: Int = 0,
    val unsupportedCitations: Int = 0,
    val unknownCitations: Int = 0,
) {
    /** O1: tasa de respuestas respaldadas entre las que citan (0..1). */
    val backedRateAmongCited: Double
        get() = if (citedResponses == 0) 0.0 else backedResponses.toDouble() / citedResponses

    /** O2: tasa de respuestas citadas sobre todas (0..1). */
    val citedRate: Double
        get() = if (responses == 0) 0.0 else citedResponses.toDouble() / responses
}

/** Resultado de comparar la calidad de las respuestas con y sin herramientas. */
@Serializable
data class GroundingComparison(
    val status: ComparisonStatus = ComparisonStatus.INSUFFICIENT_DATA,
    /** La herramienta que define el brazo "con herramienta" ("" = cualquiera). */
    val toolName: String = "",
    val withTool: GroundingArm = GroundingArm("con herramienta"),
    val withoutTool: GroundingArm = GroundingArm("sin herramienta"),
    /** Diferencia de proporciones en puntos porcentuales (O2 por defecto). */
    val riskDifference: Double = 0.0,
    val ciLow: Double = 0.0,
    val ciHigh: Double = 0.0,
    /** p del test exacto de Fisher (2×2) sobre O2. */
    val fisherP: Double = 1.0,
    /** p del test de permutación estratificado por hilo. */
    val permutationP: Double = 1.0,
    /** El intervalo excluye el 0 y el efecto supera el mínimo (0.10). */
    val significant: Boolean = false,
    /** Texto para el docente ("n = 22 vs 118"). */
    val note: String = "",
)
```

### 10.4 La función pura de agregación

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ToolAnalyticsAnalyzer.kt
object ToolAnalyticsAnalyzer {

    /** Resumen de uso de herramientas de un conjunto de respuestas. */
    fun summarize(
        messages: List<ChatMessage>,
        chatsWithRequests: Int = 0,
        chatsWithDenials: Int = 0,
    ): ToolUsageSummary

    /** Uso de herramientas de un alumno (encapsula `summarize`). */
    fun summarizeForStudent(
        messages: List<ChatMessage>,
        threads: List<ChatThread>,
    ): ToolUsageSummary

    /**
     * Compara la calidad de las respuestas con y sin una herramienta.
     *
     * Puro y determinista. Si no hay datos suficientes devuelve
     * `status = INSUFFICIENT_DATA` con los números descriptivos cargados.
     */
    fun compareGrounding(
        messages: List<ChatMessage>,
        toolName: String = "",           // "" = cualquier herramienta
        minPerArm: Int = MIN_PER_ARM,    // 8
    ): GroundingComparison
}
```

**Algoritmo de `summarize`** (pseudo):

```
para cada mensaje assistant:
    replies++
    si message.toolCalls no vacío:
        repliesWithTools++
    para cada call en message.toolCalls:
        calls++
        según call.outcome (o call.ok si UNKNOWN):
            OK      -> successes++
            FAILED  -> failures++
            DENIED  -> denials++
            BLOCKED -> blocked++
            INVALID -> invalid++
        si call.repaired: repairedCalls++
        si outcome == OK o FAILED:      // se ejecutó
            toolDurationMs += call.durationMs
        artifacts += call.artifacts
        artifactBytes += call.artifactBytes
        bucket por call.name (excluyendo el centinela "tool_call" con INVALID)
    si message.agent != null:
        agentRounds += agent.rounds
        según agent.stopReason:
            ITERATION_CAP     -> iterationCapHits++
            CONSECUTIVE_ERRORS-> consecutiveErrorCuts++
            PERMISSION_REPEAT -> permissionRepeatCuts++
        permissionRequests += agent.permissionRequests
        permissionDenials  += agent.permissionDenials
        deniedCalls       += agent.deniedCalls
        sanitizeFails     += agent.sanitizeFails
```

Sobre la **mediana**: `summarize` no puede calcularla desde las
`ToolUsageStat` agregadas (la mediana de medianas no es la mediana). Por eso
`ToolUsageStat.medianDurationMs` se calcula **en el mismo paso** que se
recorre la lista de duraciones por herramienta, con el mismo conjunto de
llamadas que se usa para `avgDurationMs`:

```kotlin
/** Mediana de una lista de enteros (copia y ordena; n es chico). */
fun median(values: List<Long>): Long {
    if (values.isEmpty()) return 0L
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}
```

**Algoritmo de `compareGrounding`**:

```
1. responses = mensajes assistant con citation != null
2. conTool   = responses.filter { tieneTool(m, toolName) }   // toolName=="" -> cualquiera
   sinTool   = responses.filter { !tieneTool(m, toolName) }
3. Cargar los dos brazos (GroundingArm) con:
   responses, citedResponses (citation.total > 0), backedResponses
   (citation.total > 0 && citation.fullySupported), y los conteos de citas.
4. Si min(conTool.responses, sinTool.responses) < minPerArm  -> INSUFFICIENT_DATA
   (pero igual se devuelven los arms con los números descriptivos).
5. Si alguno de los dos brazos tiene 0 respuestas -> SINGLE_ARM.
6. O1: tabla 2x2 sobre backed/cited:
       a = conTool.backedResponses; b = conTool.citedResponses - a
       c = sinTool.backedResponses; d = sinTool.citedResponses - c
   fisherP = fisherExactTwoSided(a, b, c, d)
   Δ_O1 y su IC por Newcombe (sobre las tasas "respuestas respaldadas entre
   las citadas" de cada brazo).
7. O2 (es el desenlace principal): tabla 2x2 sobre "citada / no citada":
       a = conTool.citedResponses;  b = conTool.responses - a
       c = sinTool.citedResponses;  d = sinTool.responses - c
   Δ_O2 y su IC por Newcombe; fisherP_O2 = fisherExactTwoSided(a,b,c,d).
8. permutationP = test de permutación estratificado por threadId sobre O2.
9. significant = (el IC de O2 excluye 0) && (|Δ_O2| >= 0.10) &&
                  (sign(fisherP_O2 - 0.5) == sign(Δ_O2)) &&
                  (sign(permutationP - 0.5) == sign(Δ_O2))
10. note = "n = ${conTool.responses} vs ${sinTool.responses}"
```

**Qué se guarda**: `riskDifference`, `ciLow`, `ciHigh`, `fisherP` y
`permutationP` corresponden a **O2** (la tasa de respuestas citadas), que es
el desenlace principal. Los números de O1 quedan visibles en los dos
`GroundingArm` (el docente los ve en la tabla) pero **no** se les calcula un
p-valor, para no multiplicar tests y el error de interpretación (D3).

### 10.5 Dónde se engancha

En `AnalyticsAnalyzer.analyze()`:

```kotlin
val perStudent = visible.map { enrollment ->
    ...
    val summary = ToolAnalyticsAnalyzer.summarizeForStudent(userMessages, userThreads)
    studentStats(enrollment, userThreads, userMessages, summary)
}
```

Y en el `CourseAnalytics`:

```kotlin
val toolUsage = ToolAnalyticsAnalyzer.summarize(
    messages = allVisibleAssistantMessages,
    chatsWithRequests = ...,
    chatsWithDenials = ...,
)
val toolGrounding = ToolAnalyticsAnalyzer.compareGrounding(allVisibleAssistantMessages)
val toolInsights = ToolInsightGenerator.generate(course, analytics)
```

> `allVisibleAssistantMessages` = mensajes de hilos cuyo autor tiene
> `visibility.showsMetrics`. Es **el mismo filtro** que ya aplica
> `AnalyticsAnalyzer` (`visible = enrollments.filter { it.visibility.showsMetrics }`):
> no se duplica la regla, no se inventa un camino que la esquive.

### 10.6 El agregado por cátedra: no es la suma de los alumnos

`CourseAnalytics.toolUsage` se calcula **sobre los mensajes**, no sumando los
`StudentAnalytics`: la mediana por herramienta y las tasas se calculan sobre
el conjunto real. Los `perStudent` siguen teniendo su propio `toolUsage` para
el detalle por alumno.

Cuidado con un doble conteo clásico: si `CourseAnalytics.toolUsage` se
calculara sumando los `perStudent.toolUsage`, el `calls` coincidiría, pero
`replies` y `repliesWithTools` también, y las **tasas** quedarían bien sólo
si todos los alumnos tuvieran el mismo peso. Se calcula sobre los mensajes:
es más simple y no tiene el problema.

---

## 11. Plan de tests

| Test | Dónde | Qué cubre |
|---|---|---|
| `ToolOutcomeTest` | `:core` commonTest | `fromName` con nombres desconocidos → `UNKNOWN`; `succeeded` con `outcome` y con el fallback a `ok`. |
| `ToolCallStatsJsonTest` | `:core` commonTest | JSON viejo (sin los campos nuevos) deserializa con los defaults; JSON nuevo con `outcome` desconocido no rompe. |
| `ToolAnalyticsAnalyzerTest` | `:core` commonTest | `summarize`: conteos por outcome, el centinela `"tool_call"` con `INVALID` **no** aparece como herramienta; `replies` cuenta sólo respuestas del asistente; la tasa de uso. |
| `ToolAnalyticsAnalyzerMedianTest` | `:core` commonTest | La mediana ignora un valor extremo (un timeout de 60 s no mueve la mediana; sí mueve la media, que se guarda aparte). |
| `StatsTest` | `:core` commonTest | `fisherExactTwoSided` contra valores conocidos (tabla 2×2 clásica); `wilson` y `newcombe` contra valores de referencia; `lnBinom` con n grande (sin overflow). |
| `GroundingComparisonTest` | `:core` commonTest | Con n chico devuelve `INSUFFICIENT_DATA`; con n suficiente devuelve `OK`; `significant` sólo si se cumplen las 4 condiciones; una diferencia de 0 no es significativa. |
| `PermutationTest` | `:core` commonTest | El p-valor es 1.0 si los brazos son idénticos; es determinista (misma semilla → mismo p); la estratificación no cambia el resultado cuando el confusor es nulo. |
| `ToolInsightGeneratorTest` | `:core` commonTest | Cada regla dispara con datos que la satisfacen y **no** dispara sin ellos; el orden es estable; el `headline` contiene los números; no hay dos insights con el mismo `id`. |
| `ToolInsightTextTest` | `:core` commonTest | Las frases no contienen `%` mal formados ni comas decimales (el formateo es propio, sin locale). |
| `AgentLoopStatsTest` | `:backend` desktopTest | El bucle cuenta rondas, sanitizaciones, denegaciones y motivo de corte; `ITERATION_CAP` sólo cuando el modelo no cerró con texto; `PERMISSION_REPEAT` cuando insiste; `durationMs > 0` en una tool ejecutada. |
| `ToolPermissionsDetailedTest` | `:backend` desktopTest | `NotNeeded` / `AlreadyGranted` / `Blocked` / `Asked`; y `authorize` (firma vieja) sigue devolviendo lo mismo. |
| `SanitizerRepairedTest` | `:backend` desktopTest | Una llamada con comillas curvas queda `repaired = true`; una irreparable sigue siendo `Invalid`; el test portado sigue pasando. |

Todos los tests de `:core` son **funciones puras**: sin disco, sin red, sin
reloj (el reloj sólo entra en `:backend`, al medir la duración).

---

## 12. Archivos

```
core/src/commonMain/kotlin/kitoru/domain/
├── Chat.kt                     # + ToolOutcome, + campos de ToolCallStats, + AgentStats, + ChatMessage.agent
├── Analytics.kt                # + StudentAnalytics.toolUsage, + CourseAnalytics.toolUsage/toolGrounding/toolInsights
├── ToolAnalytics.kt            # NUEVO: ToolUsageStat, ToolCategoryUsage, ToolUsageSummary,
│                               #        GroundingArm, GroundingComparison, ComparisonStatus, ToolInsight,
│                               #        InsightSeverity
├── ToolAnalyticsAnalyzer.kt    # NUEVO: summarize / summarizeForStudent / compareGrounding
├── ToolInsightGenerator.kt     # NUEVO: reglas R0..R9 + umbrales + formateo
└── Stats.kt                    # NUEVO: fisherExactTwoSided, wilson, newcombe, lnBinom,
                                #        permutationP (xorshift propio), median

backend/src/commonMain/kotlin/rag/agent/
├── AgentLoop.kt                # + durationMs, + category/outcome/round/repaired en ExecutedTool,
│                               # + rounds/stopReason/permisos en AgentAnswer, + AgentStopReason
├── ToolPermissions.kt          # + PermissionOutcome, + authorizeDetailed (authorize se mantiene)
├── ToolCallSanitizer.kt        # + SanitizedToolCall.Valid.repaired
└── AgentStatsMapper.kt         # NUEVO: ExecutedTool.toStats(), AgentAnswer.toAgentStats()

backend/src/desktopTest/kotlin/rag/agent/
├── AgentLoopStatsTest.kt       # NUEVO
├── ToolPermissionsDetailedTest.kt # NUEVO
└── ToolCallSanitizerRepairedTest.kt # NUEVO

core/src/commonTest/kotlin/kitoru/domain/
├── ToolAnalyticsAnalyzerTest.kt    # NUEVO
├── StatsTest.kt                    # NUEVO
├── GroundingComparisonTest.kt      # NUEVO
└── ToolInsightGeneratorTest.kt     # NUEVO
```

---

## 13. Riesgos y deuda

| Riesgo | Mitigación |
|---|---|
| El sesgo de confusión por tipo de pregunta infla el Δ. | Se declara en el texto del insight ("es un indicio"), se ofrece el análisis por hilo (permutación estratificada) y la comparación se limita a respuestas **con cita** cuando se usa O1. |
| Un docente lee el Δ como causalidad y apaga las herramientas por un ruido. | El insight exige IC que excluye 0 **y** \|Δ\| ≥ 10 pp **y** n ≥ 8 por brazo; y dice explícitamente "probá desactivar una semana y compará" (diseño quasi-experimental), no "desactivá". |
| Las respuestas viejas no tienen `outcome` → los conteos mezclan "falló" con "denegó". | `outcome = UNKNOWN` cae al `ok` de siempre: los datos viejos cuentan como "no exitoso" y se marcan como `legacy`. Cuando se migren, se re-calculan. |
| Se midió el efecto de herramientas que ya no existen (tool renombrada). | `perTool` se agrupa por nombre persistido; el panel muestra el nombre técnico. |
| La duración no es comparable entre herramientas de distinta naturaleza. | Se muestra por herramienta, nunca sumada entre herramientas distintas. |
| La mediana no se puede recalcular desde los agregados por alumno. | La mediana se calcula en el mismo recorrido que `summarize`, sobre el conjunto real de mensajes. |

**Deuda consciente (anotada, no implementada):**

- **Interrumpción de series temporales** (before/after de una fecha en la que el
  docente activa herramientas): los datos necesarios están (mensajes con
  fecha), falta la pantalla que lo guíe.
- **Comparación entre modelos**: si el mismo hilo tiene respuestas con modelos
  distintos (`ChatThread.effectiveModel`), se podría comparar dentro del
  alumno. Requiere que el modelo se registre por mensaje (hoy está en el
  hilo).
- **Coste real de las herramientas** (tokens gastados en el `RESULT` que
  vuelve al modelo): `AgentLoop.MAX_RESULT_CHARS = 4000` acota el tamaño,
  pero no se mide el coste.
