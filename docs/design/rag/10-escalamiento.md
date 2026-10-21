# 10 · Manejo de escalamiento y calidad operativa del modelo

> Subagente: diseño de **cómo la app maneja los fallos del modelo** para que el
> alumno nunca quede trabado y el docente pueda diagnosticar.
>
> Depende de (leídos antes de escribir):
> `docs/design/CONTEXTO.md`,
> `llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt`,
> `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt`,
> `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` +
> `llm-api/src/desktopMain/kotlin/llm/api/LocalModelService.desktop.kt`,
> `llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt`,
> `backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt`,
> `backend/src/commonMain/kotlin/rag/engine/RagEngine.kt`,
> `core/src/commonMain/kotlin/kitoru/domain/{ModelPolicy,Chat,Analytics,Course}.kt`,
> `docs/design/teacher/05-config-modelo.md` (pantalla de configuración del modelo).
>
> Módulos que toca este diseño: `:core` (dominio), `:llm-api` (gateway, salud,
> reintentos), `:backend` (RAG, agente), `:ui` (indicadores y panel docente).

---

## 0. Resumen en una línea

El modelo es **una dependencia externa que puede fallar**; la app lo trata como
tal: cada fallo termina en un **estado terminal accionable** para el alumno
(texto parcial conservado + un botón concreto), nunca en un spinner colgado, y
cada fallo deja una **traza estructurada** que el docente ve como panel de
diagnóstico del motor.

---

## 1. Las decisiones que mandan

| # | Decisión | Por qué |
|---|---|---|
| **D1** | **Todo error termina en estado terminal con una acción.** No existe el "se cortó y no pasó nada". Cada `LlmErrorCode` tiene: un mensaje en español, un botón, y (a veces) un reintento automático ya agotado. | El alumno nunca queda esperando. Un spinner sin final es el peor estado posible. |
| **D2** | **El texto ya generado no se pierde nunca.** Si el stream se corta, el parcial queda en el chat, marcado como parcial, con "Continuar". | Perder media respuesta es tan malo como no responder. |
| **D3** | **El reintento de transporte vive en un decorador (`RetryingGateway`), no en la UI ni en el `AgentLoop`.** Una sola autoridad de reintento. | Si reintenta la UI *y* el loop, un timeout se convierte en 9 intentos. |
| **D4** | **Sólo se reintenta si no salió ningún token todavía.** Si el modelo ya dijo algo, no se reintenta a ciegas: se ofrece "Continuar". | Reintentar un stream con texto duplica el texto en pantalla. |
| **D5** | **El fallback a IA local NO es automático por defecto.** Se ofrece con un botón, con el aviso de que la respuesta será peor y de que el docente no la verá. Con una preferencia explícita del alumno ("usar mi IA local si el servidor falla") sí es automático, pero siempre anunciado. | El modelo local responde peor y cambia la visibilidad de los chats: eso es una decisión del alumno, no de la app. |
| **D6** | **El estado de salud del motor es visible para el alumno** (chip con última latencia y último chequeo) y **agregado para el docente** (panel "Estado del motor"). | El alumno necesita saber si el problema es suyo; el docente necesita saber si el problema es del servidor. |
| **D7** | **El docente ve la configuración efectiva de cada alumno** (qué modelo usó y qué cambió respecto de la base). Es un diff campo por campo, no un número suelto. | Es el pedido explícito: "que el docente vea qué modifican sus alumnos". |
| **D8** | **Los límites del modelo se previenen con un presupuesto de contexto** (estimación de tokens) que recorta historial → `topK` → tamaño de fragmento, antes de enviar. | `CONTEXTO_AGOTADO` es 100 % evitable si se calcula el presupuesto. |
| **D9** | **`maxTokens` agotado se detecta**: el gateway debe leer `finish_reason` y emitir un chunk `Finish`. Hoy no se lee, y por eso la app no puede distinguir "terminó" de "lo cortaron". | Sin `finish_reason` no hay forma de avisarle al alumno de que su respuesta está incompleta. |
| **D10** | **La reanudación real del stream no existe** (la API OpenAI-compatible no la soporta). El "Continuar" es una **continuación por prompt**, explícitamente no idempotente, y el mensaje queda marcado como tal. | No prometer lo que la API no da. |
| **D11** | **El `AgentLoop` no reintenta el modelo por su cuenta**: propaga el error de transporte y devuelve lo acumulado. El loop sí es dueño de los fallos *semánticos* (JSON de tool call roto, tool desconocida). Y **nunca re-ejecuta una tool** para "reintentar". | Re-ejecutar una tool por un fallo del modelo duplica efectos (crear el mismo archivo dos veces). |
| **D12** | **El detalle técnico va separado del mensaje humano.** Títulos y acciones en español para el alumno; el `message` crudo de `LlmException` se muestra en un bloque "Detalle técnico" plegable y copiable. | El alumno no necesita leer un stack; el docente sí. Se muestran los dos, en jerarquías distintas. |

---

## 2. Reglas de oro (las invariantes del diseño)

1. **Nunca hay un spinner sin final.** Todo camino de error termina en
   `Idle | Generating | Streaming | Partial | Failed`, y `Failed` siempre tiene
   título + botón.
2. **Nunca se borra texto ya escrito en pantalla.** Un corte deja `partial = true`.
3. **El alumno siempre sabe con qué modelo se le respondió.** El chip del chat
   muestra el modelo activo; si se usó el local, dice "IA local".
4. **El error determinista no se reintenta** (clave, cuota, 404 de modelo,
   configuración); **el transitorio sí, con límite** (3 intentos).
5. **El reintento nunca es invisible si el alumno espera.** Si el primer intento
   tarda más de 1,5 s y va a haber reintento, el chat muestra
   "Reintentando (2/3)…" en el pie del mensaje.
6. **La degradación se anuncia.** Si se responde con IA local, el hilo queda
   etiquetado y el mensaje lleva la nota "Respondido con IA local".
7. **El docente sólo ve lo que la visibilidad permite.** Las estadísticas de
   error y el diff de configuración sólo se calculan sobre alumnos con
   `effectiveVisibility.showsMetrics`.

---

## 3. La matriz de fallos

### 3.1 Tabla maestra

| Código | Qué pasó (técnico) | Acción automática de la app | Qué ve el alumno | Qué puede hacer el alumno | Qué ve el docente |
|---|---|---|---|---|---|
| `CLAVE_INVALIDA`<br>(401/403) | El servidor rechazó la clave de la cátedra. | **Ninguna** (no es reintentable). El monitor de salud pasa a "clave rechazada" y se cortan los reintentos por el resto de la sesión. | **"No se pudo usar el modelo de la cátedra."**<br>"La clave que usa la cátedra fue rechazada por el servidor. No es un problema de tu conexión ni de tu equipo." | 1. **Avisarle al docente** (copia un texto listo al portapapeles).<br>2. Reintentar cuando haya clave nueva.<br>3. "Continuar con la IA local" si la tiene. | Incidente rojo en "Estado del motor": **"Clave rechazada · 12 alumnos bloqueados · desde 14:03"**, con botón **Emitir clave nueva** (o **Rotar clave**, si ya había una). En la lista de alumnos, los que quedaron sin respuesta. |
| `SIN_CUOTA`<br>(429) | El servidor alcanzó su límite de uso de la institución. | **0 reintentos en el chat** (fail fast: la ventana de cuota es de minutos/horas, no de segundos). Se registra el incidente y el monitor marca "cuota agotada". | **"Se agotó la cuota de la institución."**<br>"El servidor de la cátedra llegó a su límite de uso. No es culpa tuya: avisale a tu docente." | 1. **Avisarle al docente**.<br>2. **"Continuar con la IA local"** (un clic).<br>3. "Reintentar más tarde". | En "Estado del motor": **"Cuota agotada"** con contador de alumnos bloqueados y, si el servidor manda `Retry-After`, la hora estimada de reinicio ("disponible desde 16:05"). |
| `TIMEOUT` | El servidor tardó más que `chatTimeoutMs` (o cortó el stream a mitad). | **3 intentos** (1 + 2 reintentos), backoff con jitter (§4). **Sólo si no salió ningún token.** Si el corte fue a mitad del texto → parcial + "Continuar" (no reintento). | **"El servidor tardó demasiado."**<br>"Reintenté 2 veces y el servidor de la cátedra no respondió. Es el servidor, no tu conexión." | 1. **Reintentar** (manual, sin límite de reintentos automáticos).<br>2. Bajar la "potencia" a *baja* (respuestas más cortas → menos tiempo).<br>3. IA local. | % de timeouts por alumno y latencia media; si > 20 % de las respuestas: **"El servidor está lento"** en el panel, con la latencia p95. |
| `CONTEXTO_AGOTADO`<br>(400) | La pregunta + el contexto + el historial no entran en la ventana del modelo. | **1 reintento con payload compacto** (§8.2: menos historial → menos `topK` → fragmentos truncados). Si vuelve a fallar → error terminal. | Si el compacto funcionó: **"Ajusté el contexto para que la pregunta entrara."**<br>"Usé 3 fragmentos en vez de 5 y 2 turnos de historial. La respuesta puede tener menos detalle."<br>Si falló: **"La pregunta es muy larga para el modelo."**<br>"Acortá la pregunta o empezá un hilo nuevo." | 1. Acortar la pregunta.<br>2. "Empezar un hilo nuevo" (descarta el historial).<br>3. Si es docente: bajar `topK` o `chunkSize`. | Contador **"contextos agotados / recortados"**; el mensaje queda con `contextPlan` (fragmentos usados, turnos, tokens estimados) para ver si el ajuste automático alcanzó. |
| `MODELO_NO_DISPONIBLE`<br>(404 / 5xx / red) | El modelo no existe en el servidor (404), el servidor falló (5xx) o no hay red. | **404 → 0 reintentos** (no va a aparecer solo). **5xx / red → 3 intentos** con backoff. Al agotar: se abre el **circuit breaker** y se muestra el banner de degradación. | **"El servidor de la cátedra no está disponible."**<br>"Después de 3 intentos no respondió (HTTP 503). Es una falla del servidor, no tuya." | 1. **"Continuar con la IA local"** (si está) — con el aviso de menor calidad y de que el docente no la verá.<br>2. **Reintentar**.<br>3. "Probar conexión" (diagnóstico). | Incidente por tipo con el HTTP y **% de alumnos afectados**; "última latencia" del monitor; si el 404 es sistemático, el error real es **"el modelo configurado no existe en el servidor"** → lo ve en la pantalla de configuración. |
| `SERVIDOR_CAIDO` | El motor local (`llama-server`) no responde o no arrancó. | **3 intentos** con backoff. Si es el modelo local: se ofrece **reiniciar el proceso** (`LocalModelService.start` ya sabe esperarlo). | **"Tu IA local no está respondiendo."**<br>"El proceso `llama-server` se cayó o no llegó a arrancar." | 1. **"Reiniciar la IA local"** (escritorio).<br>2. Volver al servidor de la cátedra.<br>3. Ver el log del proceso. | No ve el texto (es IA local). La analítica sí ve **"este alumno usó IA local"** y, si el alumno lo comparte, el incidente. |
| `DESCARGA_FALLIDA` | Falló la descarga del GGUF (HTTP, red o integridad). | **3 intentos** con backoff 2 s / 5 s / 10 s, **reanudando** desde el `.part` con `Range: bytes=<n>-` (§8.4). Nunca se vuelve a bajar de cero mientras el `.part` sirva. | **"Falló la descarga del modelo."**<br>"Llevás 412 MB de 2,3 GB. Si reintentás, sigue desde donde quedó."<br>+ barra de progreso con el byte offset. | 1. **Reintentar** (reanuda).<br>2. "Elegir otro modelo".<br>3. Cancelar y usar el servidor de la cátedra. | No aplica (es del alumno). Si la cátedra no propone IA local, no aparece nada en su panel. |
| `NO_CONFIGURADO` | Falta URL, clave o nombre de modelo. | **Fail-fast antes de tocar la red**: se abre la pantalla de configuración con el campo faltante ya marcado y enfocado. | **"Falta completar la configuración del modelo."**<br>"Falta la dirección del servidor." (o la clave / el modelo) | Completar el campo. | El mismo chip rojo en la pantalla "Configuración del modelo" del docente. |
| `CANCELADO` | El alumno apretó "Detener". | Se corta el stream, **se guarda el parcial**, no es un error. | No hay mensaje de error. El parcial queda con un pie: **"Detuviste la respuesta."** + "Continuar" / "Copiar". | Continuar, copiar, reintentar. | **No cuenta como error**: se contabiliza aparte como "respuestas interrumpidas". |
| `DESCONOCIDO` | Error no clasificable (ni HTTP ni red reconocidos). | **1 reintento** si trae `cause` (parece un fallo de red). Si no, error terminal con el detalle copiable. | **"Algo falló al hablar con el modelo."**<br>"No pudimos identificar la causa. Podés reintentar o seguir con la IA local." | Reintentar / copiar el detalle / IA local. | Incidente con el `message` crudo y el `cause` (sólo el titular lo ve completo). |

> **Nota de privacidad**: la columna "qué ve el docente" sólo aplica si el alumno
> tiene `effectiveVisibility.showsMetrics`. Con `NONE`, el docente no ve ni el
> incidente ni el diff de configuración de ese alumno.

### 3.2 El texto exacto (copy) de cada tarjeta de error

La tarjeta de error del chat es siempre la misma estructura:

```
┌────────────────────────────────────────────────────────────┐
│ ⛔  Se agotó la cuota de la institución.                   │
│                                                            │
│ El servidor de la cátedra llegó a su límite de uso.        │
│ No es culpa tuya: avisale a tu docente para que renueve    │
│ el acceso.                                                 │
│                                                            │
│ Detalle técnico (plegable):                               │
│   la API alcanzó su límite de uso (HTTP 429).              │
│                                                            │
│ [ Continuar con la IA local ]   [ Reintentar ]   [ Copiar ]│
└────────────────────────────────────────────────────────────┘
```

Variantes por código (sólo cambia el título y el cuerpo):

| Código | Título | Cuerpo (2º párrafo) |
|---|---|---|
| `CLAVE_INVALIDA` | No se pudo usar el modelo de la cátedra. | La clave que usa la cátedra fue rechazada por el servidor. No es un problema de tu conexión ni de tu equipo. Avisale a tu docente para que emita una clave nueva. |
| `SIN_CUOTA` | Se agotó la cuota de la institución. | El servidor de la cátedra llegó a su límite de uso. No es culpa tuya: avisale a tu docente para que renueve el acceso. |
| `TIMEOUT` | El servidor tardó demasiado. | Reintenté 2 veces y el servidor de la cátedra no respondió. Es el servidor, no tu conexión. |
| `CONTEXTO_AGOTADO` | La pregunta es muy larga para el modelo. | Acortá la pregunta o empezá un hilo nuevo: el historial y el material de la cátedra llenaron la ventana de contexto. |
| `MODELO_NO_DISPONIBLE` | El servidor de la cátedra no está disponible. | Después de 3 intentos no respondió. Es una falla del servidor, no tuya. |
| `SERVIDOR_CAIDO` | Tu IA local no está respondiendo. | El proceso `llama-server` se cayó o no llegó a arrancar. Podés reiniciarlo. |
| `DESCARGA_FALLIDA` | Falló la descarga del modelo. | Llevás 412 MB de 2,3 GB. Si reintentás, sigue desde donde quedó. Revisá tu conexión a internet. |
| `NO_CONFIGURADO` | Falta completar la configuración del modelo. | Falta la dirección del servidor. Completala para poder chatear. |
| `DESCONOCIDO` | Algo falló al hablar con el modelo. | No pudimos identificar la causa. Podés reintentar o seguir con la IA local. |

> El cuerpo se arma con un **objeto único de traducción** (no con `when` sueltos
> en la UI), para que el texto sea el mismo en el chat del alumno, en el
> "Probar conexión" del docente y en los avisos del sistema.

### 3.3 Los tres niveles de escalamiento

El "escalamiento" de Kitoru tiene tres escalones, y cada fallo sube sólo hasta
el escalón que le corresponde:

| Nivel | Qué hace la app | Cuándo | Qué ve el alumno | Qué ve el docente |
|---|---|---|---|---|
| **E0 — automático e invisible** | Reintento con backoff, compactación de contexto, reanudación de descarga, circuit breaker. | Fallo transitorio. | Nada (o "Reintentando (2/3)…" si el 1er intento tardó más de 1,5 s). | Sólo las métricas agregadas. |
| **E1 — visible al alumno** | Banner en el chat, texto parcial conservado, botón "Continuar", oferta de IA local. | Se agotó E0. | Sí: el error + acciones. | El incidente, sin detalle técnico. |
| **E2 — escalado al docente** | Se registra el incidente con código, endpoint, alumno, HTTP, e impacto ("N alumnos bloqueados"). | Errores deterministas (clave, cuota) o cuando ≥ 3 alumnos fallan en 15 min con el mismo código. | Nada extra (el texto de "avisale al docente" ya lo orienta). | Panel "Estado del motor" + lista de incidentes + acción correctiva (emitir clave / avisar a la institución). |

**Regla de promoción a E2**: un incidente sube a E2 si (a) es determinista
(clave/cuota/config) o (b) en los últimos 15 minutos el mismo código de error
afectó a **≥ 3 alumnos distintos** o al **≥ 20 %** de los alumnos activos de la
cátedra. Así el docente no recibe ruido de un alumno con un problema local.

---

## 4. La política de reintento

### 4.1 Qué es reintentable

`LlmException.retryable` ya existe y es la fuente de verdad **por defecto**, pero
no alcanza: hay dos refinamientos necesarios.

1. **Un 404 es reintentable por código pero no lo es en la práctica**: el modelo
   no va a aparecer solo en el servidor. `fromHttp` marca 404 como
   `MODELO_NO_DISPONIBLE` con `retryable = true`; el `RetryPolicy` lo corrige
   usando el status HTTP.
2. **Un 429 (cuota) no se reintenta en el chat**: la ventana de cuota no es de
   segundos. En tareas de fondo (descargas, indexado) sí se reintenta.

Para poder decidir por status, `LlmException` necesita dos campos más
(cambio **aditivo**, con defaults → los JSON/llamadas existentes siguen
compilando):

```kotlin
// llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt
class LlmException(
    val code: LlmErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    override val cause: Throwable? = null,
    /** Status HTTP si el error vino de una respuesta (0 = fallo de transporte). */
    val httpStatus: Int = 0,
    /** Valor de `Retry-After` en ms (0 = el servidor no lo mandó). */
    val retryAfterMs: Long = 0L,
) : Exception(message, cause)
```

Y `fromHttp` gana un parámetro opcional:

```kotlin
fun fromHttp(status: Int, body: String, local: Boolean, retryAfterMs: Long = 0L): LlmException
```
que setea `httpStatus = status` y `retryAfterMs`, y usa `retryAfterMs` como
techo del backoff cuando viene `Retry-After`.

### 4.2 La tabla de la política

`RetryPolicy` es un objeto **puro y testeable** (sin red, sin reloj): recibe el
código, el intento y el contexto, y devuelve la decisión.

| Código | `retryable` (base) | Intentos en **chat interactivo** | Espera entre intentos | Por qué |
|---|---|---|---|---|
| `TIMEOUT` | sí | **3** (1 + 2 reintentos) | 1 s → 2 s (con jitter, §4.3) | Red lenta o servidor ocupado: tiene sentido insistir un poco. |
| `MODELO_NO_DISPONIBLE` (5xx o red) | sí | **3** | 1 s → 2 s | El servidor puede volver. |
| `MODELO_NO_DISPONIBLE` (**404**) | sí | **1** (nunca) | — | El modelo no existe: reintentar es tiempo perdido. Acción: avisar al docente, que corrija el nombre del modelo. |
| `SERVIDOR_CAIDO` | sí | **3** | 1 s → 2 s | `llama-server` puede estar arrancando. |
| `SIN_CUOTA` (429) | sí | **1** (nunca en chat) | — | La cuota se repone por ventana, no por segundo. En tareas de fondo: 3 intentos con 30 s / 60 s. |
| `CONTEXTO_AGOTADO` | sí | **1 + 1 compacta** | inmediato | El reintento tiene que cambiar el payload (§8.2), no repetirlo. Lo hace el `ContextBudget`/`RagEngine`, no el decorador. |
| `CLAVE_INVALIDA` | no | **1** | — | Determinista. |
| `NO_CONFIGURADO` | no | **1** | — | Determinista (fail-fast antes de la red). |
| `DESCARGA_FALLIDA` | sí | **3** (en el gestor de modelos) | 2 s → 5 s → 10 s | Se reanuda desde el `.part`. |
| `CANCELADO` | no | — | — | Cancelación del usuario: nunca se reintenta. |
| `DESCONOCIDO` | no | **2** si trae `cause` | 1 s | Un `cause` no vacío suele ser un fallo de red mal clasificado. |

### 4.3 El backoff con jitter (fórmula exacta)

```
nominal(n) = min(capDelayMs, baseDelayMs * 2^n)          // n = 0, 1, 2...
espera(n)  = nominal(n)/2 + random(0, nominal(n)/2)      // jitter medio
```

Con `baseDelayMs = 1_000` y `capDelayMs = 8_000`:

| Reintento `n` | `nominal` | `espera` real (rango) |
|---|---|---|
| 0 | 1 000 ms | 500 – 1 000 ms |
| 1 | 2 000 ms | 1 000 – 2 000 ms |
| 2 | 4 000 ms | 2 000 – 4 000 ms |
| ≥3 | 8 000 ms (tope) | 4 000 – 8 000 ms |

- **Jitter medio** (no full jitter) y no determinista: 40 alumnos que chocan
  contra el mismo servidor caído no lo mantienen caído de forma sincronizada,
  pero ningún alumno espera "0 ms" y think-timea la red.
- Si el servidor manda `Retry-After`, `espera = max(espera, retryAfterMs)` con
  tope de 60 s; si excede el tope, **no se reintenta** (se informa).
- El `delay` es **cancelable**: `CANCELADO` del usuario aborta la espera
  inmediatamente (usa `delay` de coroutines, que es cancelable).

### 4.4 El decorador `RetryingGateway`

Archivo nuevo: `llm-api/src/commonMain/kotlin/llm/api/RetryingGateway.kt`.

```kotlin
package llm.api

/** Qué pasó en un reintento, para que la UI pueda contarlo. */
data class RetryEvent(
    val attempt: Int,          // 1 = primer reintento
    val maxAttempts: Int,
    val code: LlmErrorCode,
    val delayMs: Long,
)

/**
 * Decorador de [LlmGateway] que reintenta los fallos transitorios.
 *
 * Reglas duras:
 *  - **Nunca reintenta si ya salió texto**: el parcial está en pantalla y
 *    reintentar lo duplicaría. En ese caso el error sube y la UI ofrece
 *    "Continuar" (§9).
 *  - **Nunca reintenta un error determinista** (clave, configuración, 404,
 *    429 en chat): reintentar es tiempo perdido y quema cuota.
 *  - **Nunca reintenta un `CancellationException`**: el "Detener" del usuario
 *    corta hasta la espera del backoff.
 *  - Un `LlmException` que no es de la API se normaliza con
 *    [LlmException.wrap].
 */
class RetryingGateway(
    private val delegate: LlmGateway,
    private val policy: RetryPolicy = RetryPolicy.default(),
    private val onRetry: (RetryEvent) -> Unit = {},
    /** Inyectable para tests: por defecto `delay`. */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    /** Aleatoriedad inyectable para tests (jitter determinista). */
    private val random: Random = Random.Default,
) : LlmGateway {

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        var attempt = 1
        var textEmitted = false
        while (true) {
            try {
                delegate.chatStream(messages, tools).collect { chunk ->
                    if (chunk is LlmChunk.Text && chunk.delta.isNotEmpty()) textEmitted = true
                    emit(chunk)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e                                  // "Detener" del usuario
            } catch (e: Exception) {
                val err = LlmException.wrap(e)
                val decision = policy.decide(err, attempt, textEmitted)
                if (!decision.retry) throw err
                onRetry(RetryEvent(attempt, policy.maxAttempts, err.code, decision.delayMs))
                sleeper(decision.delayMs)
                attempt++
            }
        }
    }

    /** `listModels` también reintenta (lo usan "Probar conexión" y el monitor). */
    override suspend fun listModels(): List<String> {
        var attempt = 1
        while (true) {
            try {
                return delegate.listModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = LlmException.wrap(e)
                val decision = policy.decide(err, attempt, emittedText = false)
                if (!decision.retry) throw err
                sleeper(decision.delayMs)
                attempt++
            }
        }
    }

    override fun close() = delegate.close()
}
```

Y la política, como objeto puro:

```kotlin
/**
 * Decide si se reintenta y cuánto se espera. Función pura: la misma
 * (código, intento, ¿salió texto?) → la misma decisión. Testeable sin red.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000,
    val capDelayMs: Long = 8_000,
    /** Si el código 429 debe reintentarse (tareas de fondo: sí; chat: no). */
    val retryQuota: Boolean = false,
    /** Techo del `Retry-After` del servidor. */
    val maxRetryAfterMs: Long = 60_000,
) {
    fun decide(error: LlmException, attempt: Int, emittedText: Boolean): RetryDecision {
        // 1. Ya salió texto: no se reintenta (se duplicaría en pantalla).
        if (emittedText) return RetryDecision.No
        // 2. Cancelación: nunca.
        if (error.code == LlmErrorCode.CANCELADO) return RetryDecision.No
        // 3. Errores deterministas.
        if (!error.retryable) return RetryDecision.No
        // 4. 404: el modelo no existe, no va a aparecer.
        if (error.httpStatus == 404) return RetryDecision.No
        // 5. 429: la cuota no se repone en segundos.
        if (error.code == LlmErrorCode.SIN_CUOTA && !retryQuota) return RetryDecision.No
        // 6. Contexto agotado: lo resuelve el compactador, no un reintento igual.
        if (error.code == LlmErrorCode.CONTEXTO_AGOTADO) return RetryDecision.No
        // 7. ¿Quedan intentos?
        if (attempt >= maxAttempts) return RetryDecision.No
        return RetryDecision.Yes(delayFor(attempt, error.retryAfterMs))
    }

    private fun delayFor(attempt: Int, retryAfterMs: Long): Long {
        val nominal = minOf(capDelayMs, baseDelayMs * (1L shl (attempt - 1)))
        val half = nominal / 2
        val jittered = half + random.nextLong(0, half + 1)
        return if (retryAfterMs > 0) {
            if (retryAfterMs > maxRetryAfterMs) maxRetryAfterMs else maxOf(jittered, retryAfterMs)
        } else jittered
    }

    companion object {
        fun default() = RetryPolicy()
        /** Variante para tareas de fondo (descargas, indexado): reintenta la cuota. */
        fun background() = RetryPolicy(maxAttempts = 4, baseDelayMs = 2_000, retryQuota = true)
    }
}

data class RetryDecision(val retry: Boolean, val delayMs: Long = 0L) {
    companion object {
        val No = RetryDecision(false)
        fun Yes(ms: Long) = RetryDecision(true, ms)
    }
}
```

> `Random` es `kotlin.random.Random` (stdlib común) → portable a Android.

### 4.5 Dónde se engancha el decorador

En el **composition root** de la app (el lugar donde hoy se construye el
`OpenAiCompatibleGateway`):

```kotlin
val raw: LlmGateway = OpenAiCompatibleGateway(gatewayConfig)
val gateway: LlmGateway = RetryingGateway(
    delegate = raw,
    policy = RetryPolicy.default(),
    onRetry = { ev -> chatState.onRetry(ev) },   // "Reintentando (2/3)…"
)
```

El `AgentLoop` y el `RagEngine` reciben **el gateway decorado**: no cambian y
heredan los reintentos gratis (regla D3).

### 4.6 El circuit breaker (evitar el martilleo)

Un servidor caído con 40 alumnos reintentando 3 veces cada uno son 120 pedidos
en 10 segundos. El breaker lo evita:

```kotlin
// llm-api/src/commonMain/kotlin/llm/health/CircuitBreaker.kt
enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

/**
 * Corta la cadena cuando el destino falla repetidamente.
 * - 3 fallos consecutivos → OPEN durante 60 s.
 * - Pasado el tiempo → HALF_OPEN: se permite **un** intento.
 * - Éxito → CLOSED (contador a cero).
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000,
    private val now: () -> Long = { kitoru.platform.nowMillis() },
) {
    private var consecutiveFailures = 0
    private var openedAt = 0L
    var state: BreakerState = BreakerState.CLOSED; private set

    /** ¿Se permite intentar? (false ⇒ el breaker está abierto). */
    fun allow(): Boolean = when (state) {
        BreakerState.CLOSED -> true
        BreakerState.OPEN -> if (now() - openedAt >= openDurationMs) {
            state = BreakerState.HALF_OPEN; true
        } else false
        BreakerState.HALF_OPEN -> false   // ya hay un intento en vuelo
    }

    fun recordSuccess() { consecutiveFailures = 0; state = BreakerState.CLOSED }
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold && state != BreakerState.HALF_OPEN) {
            state = BreakerState.OPEN
            openedAt = now()
        }
    }
}
```

Integración con el `RetryingGateway`:

- **Antes** de cada `chatStream`: `if (!breaker.allow()) throw LlmException(SERVIDOR_CAIDO, "El servidor no responde desde hace un momento. Probá en un minuto.", retryable = true)`.
- Después de cada intento: `recordSuccess()` o `recordFailure()`.
- El breaker **sólo cuenta fallos de chat**, no del probe de salud (el probe es
  pasivo); pero cuando el breaker está `OPEN`, el probe hace de intento de
  `HALF_OPEN`: si responde, se cierra el breaker y los alumnos se recuperan
  solos al siguiente mensaje.

### 4.7 La regla del "primer token"

Regla D4, en código, dentro del decorador: `textEmitted` se pone en `true` con
el **primer `LlmChunk.Text` no vacío**. A partir de ahí:

- `TIMEOUT` a mitad de stream → **no** se reintenta → el parcial queda y la UI
  ofrece "Continuar".
- `SERVIDOR_CAIDO` a mitad de stream → ídem.
- Un `ToolCall` emitido **no** bloquea el reintento: la tool aún no se ejecutó
  (el `AgentLoop` la ejecuta cuando el stream cierra), así que repetir es
  seguro.

---

## 5. El fallback a IA local

### 5.1 La decisión

**No se cambia solo.** Cuando el servidor de la institución no responde y el
alumno tiene IA local disponible, la app:

1. **No cambia de modelo por sorpresa.**
2. Muestra un banner de degradación con dos botones: **"Continuar con la IA
   local"** y **"Reintentar"**.
3. El banner explica el costo de la opción local, en dos frases.

Por qué no automático:

| Motivo | Consecuencia |
|---|---|
| El modelo local responde **peor** (Phi-4-mini contra el modelo de la facultad). | El alumno puede tomar una respuesta floja como si fuera "la de la cátedra". |
| El cambio **altera la visibilidad**: en IA local el docente **no ve** el chat. | Es un cambio de privacidad, y la privacidad la decide el alumno, no la app. |
| El modelo local puede **no estar descargado**, y arrancar `llama-server` tarda 10–60 s y come RAM. | "Automático" podría ser más lento que esperar. |

### 5.2 El banner

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚠  El servidor de la cátedra no está disponible.                │
│                                                                  │
│ Podés seguir con la IA local (Phi-4-mini, en tu equipo).         │
│ Las respuestas serán de menor calidad y tu docente no podrá       │
│ ver este chat.                                                   │
│                                                                  │
│ [ Continuar con la IA local ]  [ Reintentar ]  [ Cancelar ]      │
└──────────────────────────────────────────────────────────────────┘
```

- Aparece **en el chat**, no como modal: no bloquea la lectura de lo escrito.
- Si el modelo local **no está descargado**, el botón dice
  **"Descargar y usar la IA local (2,3 GB)"** y lleva a la pantalla de
  modelos; si tampoco hay `llama-server`, dice "Instalá llama.cpp primero"
  y sólo ofrece "Reintentar".
- "Reintentar" no reinicia el contador de intentos automáticos (ya se
  agotaron); es un intento manual, siempre permitido.

### 5.3 La preferencia persistente (fallback automático con consentimiento)

En *Ajustes → Modelo* hay una opción:

```
[switch] Si el servidor de la cátedra falla, usar mi IA local automáticamente
```

- **Apagada (por defecto)**: comportamiento de §5.1 (se ofrece).
- **Encendida**: el cambio ocurre solo, **pero siempre anunciado**:
  - el chip del chat pasa a `● IA local` en ámbar con el motivo
    ("el servidor no responde"),
  - el mensaje lleva un pie **"Respondido con la IA local"**,
  - el hilo se etiqueta `modelLabel = "Phi-4-mini (local)"`,
  - `effectiveModel.mode = LOCAL` ⇒ el docente ve (si la visibilidad lo
    permite) que ese hilo se respondió con el modelo local, no con el de la
    cátedra.

Esto mantiene D5 y la coherencia con la matriz de visibilidad de `CONTEXTO.md`
(IA local ⇒ el docente no ve el texto).

### 5.4 Qué queda registrado

Cuando se usa IA local (por el botón o por la preferencia), el hilo guarda:

- `modelLabel = "Phi-4-mini (local)"`,
- `effectiveModel = ModelPolicy(mode = LOCAL, hfRepo = …, hfFile = …)`,
- y en el mensaje: `engineError` (el error del servidor que disparó el
  fallback) + `fallbackToLocal = true`.

De modo que la analítica del docente puede responder "¿cuántos alumnos
respondieron con su propio modelo porque el servidor estaba caído?".

---

## 6. El estado de salud visible

### 6.1 El modelo de dominio (`:core`)

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/EngineHealth.kt`.

```kotlin
@Serializable
enum class EngineHealthState(val label: String) {
    DESCONOCIDO("Sin chequear"),
    OPERATIVO("Operativo"),
    LENTO("Lento"),
    INESTABLE("Inestable"),
    CAIDO("Caído"),
}

/** Una comprobación puntual del endpoint. */
@Serializable
data class HealthCheck(
    val at: Long = 0L,
    val ok: Boolean = false,
    val latencyMs: Long = 0L,
    val errorCode: String = "",       // LlmErrorCode.name, "" si ok
    val errorMessage: String = "",
)

/** Foto del estado del motor en el momento actual. */
@Serializable
data class EngineHealthSnapshot(
    val state: EngineHealthState = EngineHealthState.DESCONOCIDO,
    /** Host del endpoint (sin clave, sin query). */
    val endpoint: String = "",
    val modelName: String = "",
    val lastCheckAt: Long = 0L,
    val lastLatencyMs: Long = 0L,
    /** Mediana de las últimas 10 comprobaciones buenas. */
    val medianLatencyMs: Long = 0L,
    val lastErrorCode: String = "",
    val lastErrorMessage: String = "",
    val consecutiveFailures: Int = 0,
    /** Historial circular (últimas 20). */
    val checks: List<HealthCheck> = emptyList(),
    /** Desde cuándo está en este estado. */
    val sinceStateAt: Long = 0L,
    /** % de éxito de las últimas 20 comprobaciones (0..1). */
    val successRate: Double = 0.0,
)
```

> `EngineHealthState` vive en `:core` (no en `:llm-api`) porque `:llm-api`
> depende de `:core`, no al revés. Los códigos de error se guardan como
> `String` (el `name` del `LlmErrorCode`) para no cruzar la dependencia.

### 6.2 El `HealthMonitor`

Archivo nuevo: `llm-api/src/commonMain/kotlin/llm/health/HealthMonitor.kt`.

```kotlin
package llm.health

/**
 * Latido del motor: sondea `GET {baseUrl}/models` periódicamente y publica un
 * [EngineHealthSnapshot].
 *
 * Reglas:
 *  - **No sondea en modo LOCAL** (no hay endpoint remoto que sondear).
 *  - **No sondea si ya hay un sondeo en vuelo** (no apila sondeos).
 *  - Cada sondeo tiene su propio timeout (10 s) y **no** pasa por el
 *    `RetryingGateway`: el sondeo mide la salud real, sin reintentos que
 *    disimulen la latencia.
 *  - Es **pasivo**: no consume cuota de inferencia (sólo `/models`).
 *  - Cuando el circuit breaker está abierto, el sondeo funciona como el
 *    intento de `HALF_OPEN`: si responde, el breaker se cierra.
 */
class HealthMonitor(
    /** El gateway a sondear (null si el alumno está en modo local). */
    private val gatewayProvider: () -> LlmGateway?,
    private val intervalMs: Long = 120_000,      // 2 minutos
    private val probeTimeoutMs: Long = 10_000,
    private val slowThresholdMs: Long = 3_000,
    private val historySize: Int = 20,
) {
    private val _state = MutableStateFlow(EngineHealthSnapshot())
    val state: StateFlow<EngineHealthSnapshot> = _state

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                probeNow()
                delay(intervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Sondeo manual ("Probar conexión" / "Chequear ahora"). */
    suspend fun probeNow(): EngineHealthSnapshot {
        val gw = gatewayProvider() ?: return _state.value   // modo local: no hay a quién sondear
        val started = nowMillis()
        val check = try {
            withTimeout(probeTimeoutMs) { gw.listModels() }
            HealthCheck(nowMillis(), ok = true, latencyMs = nowMillis() - started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = LlmException.wrap(e)
            HealthCheck(nowMillis(), ok = false, latencyMs = nowMillis() - started,
                errorCode = err.code.name, errorMessage = err.message)
        }
        _state.value = reduce(_state.value, check)
        return _state.value
    }

    /** Máquina de estados pura (testeable sin red). */
    private fun reduce(prev: EngineHealthSnapshot, check: HealthCheck): EngineHealthSnapshot {
        val checks = (prev.checks + check).takeLast(historySize)
        val consecutive = if (check.ok) 0 else prev.consecutiveFailures + 1
        val state = when {
            check.ok && check.latencyMs > slowThresholdMs -> EngineHealthState.LENTO
            check.ok -> EngineHealthState.OPERATIVO
            consecutive >= 3 -> EngineHealthState.CAIDO
            consecutive >= 1 -> EngineHealthState.INESTABLE
            else -> prev.state
        }
        val okChecks = checks.filter { it.ok }
        return prev.copy(
            state = state,
            lastCheckAt = check.at,
            lastLatencyMs = check.latencyMs,
            medianLatencyMs = okChecks.map { it.latencyMs }.sorted().let {
                if (it.isEmpty()) 0L else it[it.size / 2]
            },
            lastErrorCode = check.errorCode,
            lastErrorMessage = check.errorMessage,
            consecutiveFailures = consecutive,
            checks = checks,
            sinceStateAt = if (state != prev.state) check.at else prev.sinceStateAt,
            successRate = if (checks.isEmpty()) 0.0
                          else checks.count { it.ok }.toDouble() / checks.size,
        )
    }
}
```

**Frecuencia**: 2 minutos por defecto. Se pausa si la app no está en foreground
y no hay ningún chat abierto (Desktop: la ventana está minimizada; Android:
la app está en background). Se puede probar a mano en cualquier momento.

### 6.3 La UI del alumno

**Chip en la cabecera del chat** (siempre visible, no un aviso transitorio):

```
┌──────────────────────────────────────────────┐
│ IA-2026-1 · Inteligencia Artificial          │
│ ● Servidor de la cátedra · 320 ms · hace 1 min│   ← verde
└──────────────────────────────────────────────┘
```

| Estado | Chip | Color |
|---|---|---|
| `OPERATIVO` | `● Servidor de la cátedra · 320 ms · hace 1 min` | verde |
| `LENTO` | `● Servidor lento · 4,2 s · hace 40 s` | ámbar |
| `INESTABLE` | `● Servidor inestable · hace 40 s` | ámbar |
| `CAIDO` | `● Servidor caído · hace 2 min` | rojo |
| `DESCONOCIDO` | `○ Sin chequear` (chip gris, con "Chequear" al click) | gris |

Al hacer click se abre un panel:

```
Estado del servidor de la cátedra
─────────────────────────────────
Endpoint      https://ia.universidad.edu/kitoru/v1
Modelo        Qwen3-8B
Estado        Operativo
Última latencia   320 ms  (mediana: 280 ms)
Último chequeo    hace 1 min
Éxito (20 chequeos) 95 %

  ✓ 14:03   320 ms
  ✓ 14:01   280 ms
  ✓ 13:59   305 ms
  ✗ 13:57   HTTP 503
  ✓ 13:55   290 ms

[ Chequear ahora ]   [ Copiar diagnóstico ]
```

"Copiar diagnóstico" copia un bloque de texto con endpoint, estado, latencia,
últimos 5 chequeos y la versión de la app: es lo que el alumno pega en el
correo al docente. Es el **E1 → E2** automático: el alumno lleva el
diagnóstico.

En modo LOCAL el chip dice `● IA local · 1,9 s/token` y no hay sondeo.

### 6.4 Lo que el docente ve

El mismo `HealthMonitor` corre en la app del docente (él también es un cliente
del Kitoru-Server), más el agregado de los alumnos (§7).

---

## 7. El diagnóstico para el docente: panel "Estado del motor"

### 7.1 Contenido del panel

Pantalla del docente: `Docente → Cátedra → Estado del motor`. Cuatro secciones.

**1) El servidor de la cátedra** (estado propio del docente)

```
Servidor de la cátedra
─────────────────────────────────────────────
Endpoint          https://ia.universidad.edu/kitoru/v1
Modelos           [Qwen3-8B] [Qwen3-8B-Instruct] [phi4-mini]
Estado            ● Lento — 4,2 s (último chequeo: hace 40 s)
Éxito (20)       90 %
[ Probar conexión ]  [ Chequear ahora ]
```

**2) Uso por los alumnos (últimos 7 días)** — sólo alumnos con
`effectiveVisibility.showsMetrics`:

```
Uso por los alumnos
─────────────────────────────────────────────
Modelo                      Alumnos  Mensajes  Latencia  Errores
Qwen3-8B (servidor)              28       412     1,9 s      4 %
Phi-4-mini (local del alumno)     5        37     8,4 s      0 %
Qwen3-8B-Instruct                 1         3     2,1 s      0 %

Errores por tipo (412 mensajes)
  ████████░░░░░░  TIMEOUT            9   (2,2 %)
  ██░░░░░░░░░░░░  SIN_CUOTA           4   (1,0 %)
  █░░░░░░░░░░░░░  MODELO_NO_DISP.    3   (0,7 %)
  ░░░░░░░░░░░░░░  (sin error)       396  (96,1 %)
```

**3) Incidentes recientes** (regla de promoción a E2, §3.3):

```
Incidentes
─────────────────────────────────────────────
🔴 14:03  CLAVE_INVALIDA     12 alumnos   "la API rechazó la clave (HTTP 401)"
                                 [ Emitir clave nueva ]
🟡 12:41  SIN_CUOTA           3 alumnos   "la API alcanzó su límite de uso (HTTP 429)"
🟡 11:15  TIMEOUT             1 alumno    "La petición superó 120000 ms."
🟢 10:02  CONTEXTO_AGOTADO    1 alumno    "la API agotó el contexto: …"  (se recompactó)
```

Cada incidente es accionable:
- `CLAVE_INVALIDA` → **Emitir clave nueva** / **Rotar clave** (la pantalla de
  `teacher/05-config-modelo.md` §3.2 ya define `KitoruServerClient.issueKey`
  y `rotateKey`).
- `SIN_CUOTA` → **Avisar a la institución** (copia un texto con el horario y
  el número de alumnos afectados).
- `TIMEOUT` sostenido → **Revisar la carga del servidor** (ver §7.1).
- `MODELO_NO_DISPONIBLE` con 404 → **Corregir el nombre del modelo**.

**4) Configuración efectiva por alumno** — el pedido explícito del usuario.

### 7.2 La configuración efectiva de cada alumno

La fuente de datos ya existe: `ChatThread.effectiveModel` (la configuración con
la que se generó el hilo) y `ChatThread.modelLabel`. Lo que falta es (a) un
**diff** legible y (b) la agregación por alumno.

**El diff** (`:core`, función pura y testeable):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicyDiff.kt

/** Un campo que difiere entre la base de la cátedra y lo que usó el alumno. */
@Serializable
data class FieldChange(
    val field: LockedField,
    val label: String,          // "Temperatura"
    val base: String,           // "0.20"
    val effective: String,      // "0.50"
    /** El docente tenía este campo bloqueado ⇒ el alumno no pudo cambiarlo. */
    val blocked: Boolean = false,
)

/**
 * Diferencia entre la configuración base de la cátedra y la efectiva de un
 * alumno. Objeto puro: mismo par (base, efectiva) → mismo diff.
 */
object ModelPolicyDiff {

    fun between(base: ModelPolicy, effective: ModelPolicy): List<FieldChange> {
        val out = mutableListOf<FieldChange>()
        fun add(f: LockedField, b: String, e: String, blocked: Boolean) {
            if (b != e) out += FieldChange(f, f.label, b, e, blocked)
        }
        // El modo se muestra aparte (no es un campo "bloqueable" hoy).
        add(LockedField.BASE_URL, host(base.remoteBaseUrl), host(effective.remoteBaseUrl),
            LockedField.BASE_URL in base.lockedFields)
        add(LockedField.MODEL_NAME, base.modelName, effective.modelName,
            LockedField.MODEL_NAME in base.lockedFields)
        add(LockedField.TEMPERATURE, fmt(base.temperature), fmt(effective.temperature),
            LockedField.TEMPERATURE in base.lockedFields)
        add(LockedField.MAX_TOKENS, base.maxTokens.toString(), effective.maxTokens.toString(),
            LockedField.MAX_TOKENS in base.lockedFields)
        add(LockedField.TOP_K, base.topK.toString(), effective.topK.toString(),
            LockedField.TOP_K in base.lockedFields)
        // El prompt del sistema se reporta como sí/no (es largo).
        if (base.systemPrompt != effective.systemPrompt) {
            out += FieldChange(LockedField.SYSTEM_PROMPT, "Instrucciones del sistema",
                if (base.systemPrompt.isBlank()) "(las de la cátedra)" else "(personalizadas)",
                "(personalizadas)",
                LockedField.SYSTEM_PROMPT in base.lockedFields)
        }
        return out
    }
}
```

La tabla del panel:

```
Configuración efectiva de los alumnos
──────────────────────────────────────────────────────────────
Alumno            Modelo         Servidor      Temp  Tokens  Frag  Cambios
Ana Pérez         Qwen3-8B       *(cátedra)*   0.50  2048    5     ⚙ 2
Luis Díaz         Qwen3-8B       *(cátedra)*   0.20  1024    5     —
Mara Sol          Phi-4-mini     (local)       0.20  1024    3     ⚙ 4
Nina Goya         Qwen3-8B       *(cátedra)*   0.20  1024    5     —
```

- Click en una fila → panel de detalle con el diff campo por campo:

```
Ana Pérez — configuración con la que chateó
─────────────────────────────────────────────
Base de la cátedra (IA-2026-1)          Lo que usó Ana

Temperatura            0.20         →    0.50        (libre)
Límite de tokens       1024         →    2048        (libre)
Fragmentos recuperados 5            →    5           (sin cambios)
Modelo                 Qwen3-8B     →    Qwen3-8B   (sin cambios)
Clave de API           *(oculta)*   →    *(oculta)*  (bloqueada por la cátedra)
Servidor               ia.univ…/v1 →    ia.univ…/v1

Última actividad: hoy 18:22 · 42 mensajes · latencia media 1,7 s
```

- Resumen arriba de la tabla:
  **"18 de 34 alumnos usan la configuración de la cátedra sin cambios.
  9 cambiaron la temperatura, 5 el modelo, 3 el límite de tokens y 2 usan
  IA local."**
- Filtros: "sólo los que cambiaron algo", "sólo IA local", "por campo cambiado".
- Exportable a CSV (mismo mecanismo que el padrón de alumnos).

**Honestidad del dato**: esto es **la configuración con la que el alumno generó
su último mensaje**, no su configuración en vivo (la app del alumno no puede
empujar cambios en tiempo real). El encabezado del panel lo dice:
*"Configuración del último mensaje de cada alumno (hoy 21:04)."*

### 7.3 Extensiones de dominio necesarias

**(a) El error, en el mensaje** (`ChatMessage`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (aditivo)
@Serializable
data class EngineErrorInfo(
    /** `LlmErrorCode.name`. */
    val code: String,
    val message: String = "",
    val at: Long = 0L,
    /** Cuántos intentos se hicieron antes de rendirse. */
    val attempts: Int = 1,
    /** Quedó texto parcial en pantalla. */
    val partial: Boolean = false,
    /** Status HTTP (0 = fallo de transporte). */
    val httpStatus: Int = 0,
    /** Se respondió con IA local en lugar del servidor. */
    val fallbackToLocal: Boolean = false,
)
```

Y en `ChatMessage`: `val engineError: EngineErrorInfo? = null`,
`val truncated: Boolean = false` (se cortó por `max_tokens`),
`val continuations: Int = 0` (cuántas veces se continued una respuesta),
`val contextPlan: ContextPlanInfo? = null`.

**(b) El plan de contexto** (para que el docente vea si el recorte automático
alcanzó):

```kotlin
@Serializable
data class ContextPlanInfo(
    val topK: Int = 0,                 // fragmentos realmente usados
    val historyTurns: Int = 0,         // turnos realmente usados
    val estimatedTokens: Int = 0,      // tokens estimados del pedido
    val contextSize: Int = 0,          // ventana del modelo
    val compacted: Boolean = false,    // ¿hubo que recortar?
)
```

**(c) El uso por modelo** (agregado para el panel):

```kotlin
@Serializable
data class ModelUsage(
    val modelLabel: String,
    val mode: String,                   // "REMOTE" | "LOCAL"
    val students: Int = 0,
    val messages: Int = 0,
    val avgLatencyMs: Long = 0L,
    /** Errores por código (`LlmErrorCode.name` → cantidad). */
    val errorsByCode: Map<String, Int> = emptyMap(),
    val errorRate: Double = 0.0,
)
```

Y `CourseAnalytics` gana `val modelUsage: List<ModelUsage> = emptyList()`,
calculado por `AnalyticsAnalyzer` (función pura, como el resto del analizador).

> `AnalyticsAnalyzer` debe seguir siendo **puro**: los datos de error vienen de
> los `ChatMessage`, no de ninguna llamada a red. Se respeta la regla 3 del
> proyecto ("la analítica no puede depender del modelo ni de la red").

---

## 8. Los límites del modelo

Son **dos límites distintos** que la app confundía en uno:

| Límite | Qué se agota | Síntoma | Dónde se detecta |
|---|---|---|---|
| `maxTokens` | La **salida** | La respuesta se cortó a mitad. | `finish_reason == "length"` (hoy **no se lee**). |
| `contextSize` | La **entrada** (pregunta + historial + material) | El modelo ni empezó a responder: HTTP 400. | HTTP 400 con indicios de tokens (`LlmException.fromHttp` ya lo clasifica como `CONTEXTO_AGOTADO`). |

### 8.1 `maxTokens` agotado: hay que poder detectarlo

El gateway actual **ignora `finish_reason`**: no puede distinguir "el modelo
terminó" de "lo cortamos nosotros". Hay que agregar un chunk de fin de stream.

**Cambio en `:llm-api`**: `LlmChunk` vive en `llm/api/Tools.kt` (no en
`LlmGateway.kt`), así que el tipo nuevo va ahí y el parser del stream, en
`LlmGateway.kt`.

```kotlin
// llm/api/Tools.kt (dentro de `sealed class LlmChunk`)

/** Por qué terminó la generación. */
enum class FinishReason(val label: String) {
    STOP("terminó"),
    LENGTH("se alcanzó el límite de tokens"),
    TOOL_CALLS("pidió una herramienta"),
    CONTENT_FILTER("lo frenó el filtro de contenido"),
    OTHER("terminó de otra forma"),
}

sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()
    data class ToolCall(val id: String, val name: String, val argsJson: String) : LlmChunk()
    /** Cierre del stream: por qué terminó y cuántos tokens costó. NUEVO. */
    data class Finish(
        val reason: FinishReason,
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
    ) : LlmChunk()
}
```

En `OpenAiCompatibleGateway.chatStream`, al leer cada `data:` se captura
`choices[0].finish_reason` y, al terminar el stream, se emite
`LlmChunk.Finish(...)` (con `usage` si el servidor lo manda; si no, 0).

> **Impacto**: el `when (chunk)` del `AgentLoop.run` y de `textOnly` es
> exhaustivo sobre `Text | ToolCall` ⇒ hay que agregar la rama
> `is LlmChunk.Finish -> { /* se guarda el motivo */ }`. Es el único cambio
> obligatorio fuera de `:llm-api` que esta pieza provoca, y se lista en §11.

**Lo que ve el alumno** cuando `reason == LENGTH`:

```
┌────────────────────────────────────────────────────────────┐
│ ✂  La respuesta se cortó porque llegó al límite de tokens  │
│    (1024).                                                  │
│                                                            │
│ Podés pedirle que continúe desde donde quedó.               │
│                                                            │
│ [ Continuar ]  [ Regenerar ]  [ Copiar ]                   │
└────────────────────────────────────────────────────────────┘
```

- El mensaje queda con `truncated = true`.
- El botón **"Continuar"** usa exactamente el mismo mecanismo que §9
  (continuación por prompt), así que hay **un solo** camino de reanudación.
- Si el docente fijó `maxTokens` muy bajo, el panel de "Estado del motor"
  muestra **"12 respuestas truncadas esta semana"** → es una señal de que
  `maxTokens` está mal calibrado para la materia.

**Prevención**: `maxTokens` debería estar calibrado por la cátedra. En la
pantalla de configuración del modelo (`teacher/05` §6.2) se puede mostrar el
estimado: "Con 1024 tokens salen respuestas de ~1 página; los ejercicios
derivados se cortan. Subilo a 2048."

### 8.2 `contextSize` agotado: la app lo puede evitar

**Estimador de tokens** (portable, en `:backend`):

```kotlin
// backend/src/commonMain/kotlin/rag/engine/TokenEstimator.kt
object TokenEstimator {
    /**
     * Estimación de tokens por caracteres. Los tokenizers BPE actuales usan
     * ~3,5–4 caracteres por token en español e inglés. Se usa 3,6 con
     * redondeo hacia arriba: preferimos sobrar que quedarse corto.
     */
    const val CHARS_PER_TOKEN = 3.6

    fun estimate(text: String): Int =
        if (text.isEmpty()) 0 else ceil(text.length / CHARS_PER_TOKEN).toInt()
}
```

**Presupuesto de contexto** (`:backend`):

```kotlin
// backend/src/commonMain/kotlin/rag/engine/ContextBudget.kt

/**
 * Calcula si el pedido entra en la ventana del modelo y, si no, arma el
 * plan de recorte. Determinista: los mismos datos ⇒ el mismo plan.
 *
 * El orden de recorte es fijo y va de lo más barato a lo más caro:
 *   1. historial (6 → 4 → 2 → 0 turnos)
 *   2. topK (5 → 4 → 3 → 2 → 1)
 *   3. tamaño del fragmento (1200 → 900 → 600 → 400 caracteres)
 *   4. si todavía no entra → CONTEXTO_AGOTADO (error terminal)
 */
data class ContextPlan(
    val topK: Int,
    val historyTurns: Int,
    val maxChunkChars: Int,       // 0 = sin truncar
    val estimatedTokens: Int,
    val contextSize: Int,
    val fits: Boolean,
    val compacted: Boolean,
)

class ContextBudget(
    private val contextSize: Int,      // ventana del modelo
    private val maxTokens: Int,        // tokens reservados para la respuesta
    private val safetyMargin: Int = 512,
) {
    fun plan(
        systemPrompt: String,
        toolCatalog: String,
        history: List<ChatMessage>,
        question: String,
        chunkTexts: List<String>,
        baseTopK: Int,
        baseHistoryTurns: Int,
    ): ContextPlan { /* ... */ }
}
```

El cálculo:

```
reservado  = maxTokens + safetyMargin
ocupado   = est(systemPrompt) + est(toolCatalog) + est(historial) + est(pregunta) + est(chunks)
disponible= contextSize - reservado
```

Si `ocupado > disponible`, se aplica el plan de recorte (§8.2) y se recalcula
hasta que entre o se agoten los pasos.

**El recorte se aplica en `RagEngine.prepare()`**, que hoy arma los mensajes:

```kotlin
// rag/engine/RagEngine.kt (firma nueva, con default → compatible)
data class Prepared(
    val sources: List<Chunk>,
    val messages: List<ChatMessage>,
    val plan: ContextPlan? = null,     // NUEVO
)

fun prepare(
    question: String,
    history: List<ChatMessage>,
    useDocs: Boolean,
    budget: ContextBudget? = null,     // null = sin control de contexto
): Prepared
```

Con `budget != null`:

1. Se recuperan `topK` fragmentos con el BM25.
2. Se calcula el plan; si hay recorte, **se vuelve a recuperar** con el `topK`
   del plan y se **truncan** los textos de los fragmentos a `maxChunkChars`
   (conservando el comienzo y la oración que más se parezca a la consulta —
   con la implementación simple: los primeros N caracteres, marcados con "…").
3. Se recorta el historial a `historyTurns * 2` mensajes.
4. El `plan` se guarda en el mensaje (`contextPlan`) ⇒ el docente ve que se
   recorta y cuánto.

**Si el plan no logra que entre**, se lanza:

```kotlin
LlmException(
    LlmErrorCode.CONTEXTO_AGOTADO,
    "La pregunta y el material ocupan ${plan.estimatedTokens} tokens y el modelo " +
        "tiene ${plan.contextSize}. No entra ni recortando el historial.",
    retryable = false,
)
```

⇒ el alumno ve "La pregunta es muy larga para el modelo. Acortá la pregunta o
empezá un hilo nuevo." (§3.1).

### 8.3 Lo que el docente puede ajustar (prevención)

En la pantalla de configuración del modelo, una tarjeta de **presupuesto de
contexto** con números estimados:

```
Presupuesto de contexto (estimado)
────────────────────────────────────────────────────────
Ventana del modelo                    8.192 tokens
Instrucciones + catálogo de tools     ~1.100
Historial (6 turnos)                    ~900
Fragmentos (topK 5 × 1.200 car.)      ~5.800
Reservado para la respuesta           1.024
Margen de seguridad                      512
────────────────────────────────────────────────────────
Total estimado                         9.336 tokens   ⚠ no entra

Recomendación: bajá "fragmentos recuperados" a 4   → ~7.900 tokens
o bajá "tamaño de fragmento" a 900                → ~7.600 tokens
```

Los dos controles que la cátedra puede tocar (ya existen en el dominio):
`ModelPolicy.topK` (1–20) y `Course.chunkSize` (600–2400). El segundo exige
reindexar: el botón **"Reindexar material"** de la pantalla de materiales.

El `contextSize` del modelo remoto: hoy `ModelPolicy.contextSize` (8192) está
pensado para `llama-server`. Se reutiliza como **la ventana efectiva del
modelo activo**: para el modo REMOTE, el valor por defecto es 8192 y, si el
Kitoru-Server lo expone en `/models` (`{"id":"Qwen3-8B","context_size":32768}`),
la app lo completa sola al hacer "Probar conexión". Si no lo expone, el docente
lo puede fijar a mano en la configuración.

### 8.4 `DESCARGA_FALLIDA`: reintento con reanudación

El gestor de modelos **no existe todavía en Kitoru** (en FunshiEngineAI está en
`llm/catalog/HuggingFaceDownloader.kt`, con reanudación por `.part` + `Range`).
Se porta tal cual (regla 1 del proyecto: *reutilizar, no reescribir*), con dos
cambios:

1. La política de reintentos pasa a `RetryPolicy.background()` (4 intentos,
   base 2 s, `retryQuota = true`).
2. La reanudación es **obligatoria**, no opcional: el archivo `.part` se
   conserva y el pedido siguiente manda `Range: bytes=<ya-descargado>-`.

```kotlin
// port de FunshiEngineAI: llm-api/src/commonMain/kotlin/llm/catalog/HuggingFaceDownloader.kt
// (la lógica clave, sin cambios de comportamiento)
if (already > 0) header(HttpHeaders.Range, "bytes=$already-")
val resumed = resp.status == HttpStatusCode.PartialContent
val offset = if (resumed) already else 0L
// 416 = el .part ya tiene todo lo que hay: se da por completo.
// Si total > 0 && done < total → LlmException(DESCARGA_FALLIDA, …) y el .part queda para reanudar.
```

La UI de descarga muestra el offset para que el alumno vea que **no se pierde
progreso**: "Reanudando desde 412 MB de 2,3 GB".

---

## 9. El streaming interrumpido

### 9.1 Qué ve el alumno

El texto parcial **se queda en el chat**, con un pie de aviso:

```
┌────────────────────────────────────────────────────────────┐
│ La respuesta se cortó a mitad: la conexión con el servidor  │
│ se perdió. Lo que llegó hasta acá quedó guardado.          │
│                                                            │
│ [ Continuar ]  [ Regenerar ]  [ Copiar ]                    │
└────────────────────────────────────────────────────────────┘
```

- El mensaje queda con `engineError.partial = true` y `continuations = 0`.
- El botón "Regenerar" **reemplaza** el parcial (pide confirmación:
  *"Esto va a reemplazar la respuesta parcial"*).
- El pie **desaparece** cuando la respuesta queda completa (se reescribe el
  mensaje con el texto final, sin el pie).

### 9.2 ¿Se puede reanudar? Decisión

**No hay reanudación real.** La API OpenAI-compatible no tiene identificador
de mensaje ni cursor de stream: no existe forma de pedirle al servidor "seguí
desde el token 437". Reanudar el mismo stream HTTP tampoco sirve: la conexión
ya está muerta.

Lo que sí se puede hacer es una **continuación por prompt**:

```
messages = [ ...historial..., user(pregunta), assistant(texto_parcial),
             user("Continuá la respuesta anterior desde donde quedó. No repitas "
                  "lo que ya escribiste ni lo reformules: seguí desde la última frase.")]
```

**Advertencias honestas** (van en el diseño, no en la UI):

- No es idempotente: el modelo puede repetir la última frase, o contradecirse.
  Por eso se limpia el solapamiento: se recorta el parcial en la última
  oración completa (§9.3).
- El modelo puede no "entender" que tiene que continuar: depende del modelo.
  Phi-4-mini en local lo hace razonablemente bien; un modelo de instruct
  grande, mejor.
- **No es una promesa de igualdad**: el resultado no es idéntico a lo que
  habría dado el stream original.

Por eso la UI es explícita: el botón dice **"Continuar"** (no "Reanudar"), y
la respuesta queda marcada con `continuations = 1` (visible para el docente en
la analítica: "N respuestas se continuing").

### 9.3 Implementación de la continuación

```kotlin
// backend/src/commonMain/kotlin/rag/engine/StreamRecovery.kt

/**
 * Reanudación (aproximada) de una respuesta cortada.
 *
 * No es una reanudación del stream: es una **nueva** pedido que le pide al
 * modelo continuar. Por eso hay que limpiar el solapamiento: se recorta el
 * parcial en la última oración completa para que el modelo no la repita.
 */
object StreamRecovery {

    /** Corta el parcial en la última oración completa (si no es demasiado tarde). */
    fun trimToLastSentence(partial: String): String {
        val text = partial.trimEnd()
        if (text.isEmpty()) return ""
        val idx = text.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'))
        // Si la última oración completa está muy al principio, mejor no cortar:
        // el modelo necesita contexto para continuar.
        if (idx <= 0 || idx < text.length * 0.5) return text
        return text.substring(0, idx + 1)
    }

    /** Arma el mensaje de continuación. */
    fun continuationPrompt(): String =
        "Continuá la respuesta anterior desde donde quedó. No repitas lo que ya " +
            "escribiste ni lo reformules: seguí desde la última frase."

    /**
     * Construye los mensajes para continuar. Devuelve null si no hay nada
     * que continuar.
     */
    fun buildContinuation(
        messages: List<ChatMessage>,
        partial: String,
    ): List<ChatMessage>? {
        val trimmed = trimToLastSentence(partial)
        if (trimmed.isEmpty()) return null
        return messages + listOf(
            ChatMessage("assistant", trimmed),
            ChatMessage("user", continuationPrompt()),
        )
    }
}
```

**El flujo en la UI**:

1. El stream falla → el parcial queda en el buffer del mensaje.
2. Se guarda el mensaje con `partial = true`.
3. "Continuar" → `StreamRecovery.buildContinuation(mensajes, parcial)` → se
   manda el pedido → **el texto nuevo se agrega al mismo mensaje** (no crea un
   mensaje nuevo), y se incrementa `continuations`.
4. Si el nuevo stream también se corta, se repite el procedimiento (hasta 3
   continuaciones; a la cuarta se ofrece "Regenerar").

El caso "el modelo se cortó por `maxTokens`" (§8.1) usa **exactamente el mismo
camino**: la razón de la continuación es distinta, pero la mecánica es una.
Por eso hay un único botón, una única implementación.

---

## 10. Los reintentos del agente (`AgentLoop`)

### 10.1 Reparto de responsabilidades

```
┌──────────────────────────────────────────────────────────────────┐
│ UI / ViewModel                                                  │
│   · muestra errores, ofrece acciones, decide el fallback local   │
├──────────────────────────────────────────────────────────────────┤
│ AgentLoop                                                        │
│   · fallos SEMÁNTICOS del modelo: JSON de tool call roto, tool   │
│     desconocida, tool que falló → realimenta al modelo           │
│   · NUNCA reintenta fallos de TRANSPORTE                          │
├──────────────────────────────────────────────────────────────────┤
│ RetryingGateway (decorador)                                      │
│   · fallos de TRANSPORTE: red, timeout, 5xx → 3 intentos         │
│   · circuit breaker                                              │
└──────────────────────────────────────────────────────────────────┘
```

**El `AgentLoop` no implementa reintentos de transporte.** Si lo hiciera,
`TIMEOUT` × 3 (gateway) × 8 rondas = 24 pedidos. Con un solo nivel de
reintento, el máximo es 3 por ronda y el tope de rondas (`maxIterations = 8`)
sigue siendo el tope real.

### 10.2 Qué hace el loop con un error del modelo

Hoy el `AgentLoop` deja que la excepción del gateway escape, y **se pierde el
texto visible** de la ronda. Eso contradice D2. El cambio propuesto:

```kotlin
// rag/agent/AgentLoop.kt

/** Evento nuevo: la llamada al modelo falló. */
data class Failed(
    val code: LlmErrorCode,
    val message: String,
    /** Quedó texto parcial en pantalla. */
    val partial: Boolean,
) : AgentEvent()

/** Respuesta del loop: ahora puede traer error. */
data class AgentAnswer(
    val text: String,
    val toolCalls: List<ExecutedTool> = emptyList(),
    /** Error de la última llamada al modelo (si lo hubo). */
    val error: LlmException? = null,
    /** El texto es parcial: el stream se cortó. */
    val partial: Boolean = false,
    /** Rondas ejecutadas. */
    val rounds: Int = 0,
    /** Fallos consecutivos de la llamada al modelo. */
    val modelFailures: Int = 0,
)
```

Y en `run()`, el `gateway.chatStream(...)` se envuelve:

```kotlin
var consecutiveModelFailures = 0
for (iteration in 1..maxIterations) {
    var roundText = StringBuilder()
    try {
        gateway.chatStream(work, tools).collect { chunk ->
            when (chunk) {
                is LlmChunk.Text -> { /* … como hoy … */ }
                is LlmChunk.ToolCall -> call = chunk
                is LlmChunk.Finish -> finishReason = chunk.reason   // NUEVO
            }
        }
    } catch (e: CancellationException) {
        throw e                      // "Detener" del usuario: se propaga
    } catch (e: LlmException) {
        consecutiveModelFailures++
        onEvent(AgentEvent.Failed(e.code, e.message, roundText.isNotEmpty()))
        // El loop NO reintenta el transporte (lo hizo el gateway).
        // Si hubo texto parcial, se devuelve lo acumulado: nunca se pierde.
        return AgentAnswer(
            text = visible.toString(),
            toolCalls = used,
            error = e,
            partial = roundText.isNotEmpty(),
            rounds = iteration - 1,
            modelFailures = consecutiveModelFailures,
        )
    }
    consecutiveModelFailures = 0
    // … resto del loop (tools, sanitización) …
}
```

**Reglas resultantes**:

| Situación | Qué hace el loop |
|---|---|
| El modelo falla **antes** de emitir texto | Devuelve `AgentAnswer(text = "", error = e)`. La UI muestra la tarjeta de error con "Reintentar". |
| El modelo falla **después** de emitir texto | Devuelve `AgentAnswer(text = parcial, error = e, partial = true)`. La UI muestra el parcial + "Continuar". |
| El modelo pide una tool y el stream se corta antes de cerrar | La tool **no** se ejecutó (el loop la ejecuta al cerrar el stream). El parcial se conserva; si el alumno continúa, el modelo puede volver a pedirla. |
| El modelo pide una tool, la tool **se ejecutó**, y la llamada siguiente al modelo falla | El loop devuelve lo acumulado + el error. **La tool NO se re-ejecuta** (§10.3). |
| La tool falló (excepción o timeout) | **Esto sí lo maneja el loop**: genera feedback para el modelo (comportamiento actual) y cuenta `consecutiveToolErrors`. |
| El JSON de la tool call está roto | **Esto sí lo maneja el loop**: sanitiza y reintenta hasta `MAX_SANITIZE_RETRIES = 2` (comportamiento actual). |
| El usuario apretó "Detener" | `CancellationException` se propaga (comportamiento actual, no se toca). |

### 10.3 Idempotencia de herramientas

El riesgo real del punto anterior: si el modelo pide `create_file` con los
mismos argumentos, la tool corre, y después falla la llamada siguiente al
modelo, un reintento posterior podría **volver a pedir la misma tool** y el
loop la ejecutaría otra vez (dos archivos iguales, o un archivo pisado).

Mitigación en el loop: una caché de resultados por firma de llamada.

```kotlin
// rag/agent/AgentLoop.kt (campo privado)
private val executedCalls = mutableMapOf<String, ToolResult>()

/** Firma estable de una invocación: nombre + argumentos normalizados. */
private fun signature(call: LlmChunk.ToolCall): String =
    call.name.trim().lowercase() + "|" + call.argsJson.replace(Regex("\\s+"), " ")
```

En el punto donde se ejecuta la tool:

```kotlin
val sig = signature(invocation)
val cached = executedCalls[sig]
val result = if (cached != null) {
    // Ya se ejecutó en esta corrida: se devuelve el mismo resultado, sin
    // repetir el efecto.
    ToolResult.Ok(
        cached.render() + " (ya se había ejecutado en esta respuesta; no se repitió)",
        cached.artifacts,
    )
} else {
    val r = if (denied) ToolResult.Error("…") else execute(invocation, ctx)
    executedCalls[sig] = r
    r
}
```

Es una protección **por corrida** (por mensaje del usuario), no global: si el
alumno repregunta en otro mensaje, la tool se puede volver a ejecutar (que es
lo correcto).

### 10.4 El "Continuar" en modo agente

Cuando el loop devuelve un error con texto parcial, el botón "Continuar" (§9)
arma los mensajes así:

```
[ …historial…, user(pregunta), assistant(parcial), user(continuationPrompt()) ]
```

y **vuelve a entrar al `AgentLoop` con ese mismo historial**. Consecuencia
importante: si el loop había ejecutado una tool antes de romperse, el historial
ya incluye el mensaje `role=tool` con su resultado ⇒ **la tool no se vuelve a
ejecutar** (el modelo recibe el resultado y continúa). La caché de §10.3
cubre el caso en que el modelo **vuelva a pedir** la misma tool.

---

## 11. Archivos nuevos y cambios

### 11.1 Archivos nuevos

| Ruta | Qué es |
|---|---|
| `llm-api/src/commonMain/kotlin/llm/api/RetryingGateway.kt` | El decorador de reintentos + `RetryPolicy` + `RetryDecision` + `RetryEvent`. |
| `llm-api/src/commonMain/kotlin/llm/health/CircuitBreaker.kt` | El breaker de 3 fallos / 60 s. |
| `llm-api/src/commonMain/kotlin/llm/health/HealthMonitor.kt` | El latido (`GET /v1/models` cada 2 min) y la máquina de estados. |
| `llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt` | **Extender** el que ya propone `teacher/05` §4.4: agregar `forStudent(e)` con título + cuerpo + acciones. |
| `llm-api/src/commonMain/kotlin/llm/catalog/HuggingFaceDownloader.kt` | Port de FunshiEngineAI (descarga con `.part` + `Range`). |
| `backend/src/commonMain/kotlin/rag/engine/TokenEstimator.kt` | Estimación de tokens portable. |
| `backend/src/commonMain/kotlin/rag/engine/ContextBudget.kt` | El plan de recorte del contexto. |
| `backend/src/commonMain/kotlin/rag/engine/StreamRecovery.kt` | La construcción de la continuación. |
| `core/src/commonMain/kotlin/kitoru/domain/EngineHealth.kt` | `EngineHealthState`, `HealthCheck`, `EngineHealthSnapshot`. |
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicyDiff.kt` | El diff base ↔ efectiva (función pura). |
| `ui/src/commonMain/kotlin/kitoru/ui/components/EngineHealthChip.kt` | El chip del chat (compartido por alumno y docente). |
| `ui/src/commonMain/kotlin/kitoru/ui/components/EngineErrorCard.kt` | La tarjeta de error con sus acciones. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/EngineStatusScreen.kt` | El panel "Estado del motor" del docente. |

### 11.2 Cambios a archivos existentes

| Archivo | Cambio |
|---|---|
| `llm/.../error/LlmErrors.kt` | `LlmException`: + `httpStatus`, + `retryAfterMs`. `fromHttp`: setea ambos (+ parámetro opcional `retryAfterMs`). |
| `llm/.../api/Tools.kt` | `LlmChunk.Finish` + `FinishReason` (el tipo de chunk nuevo). |
| `llm/.../api/LlmGateway.kt` | El parser del stream captura `choices[0].finish_reason` y `usage`, y emite `LlmChunk.Finish` al cerrar. |
| `llm/.../api/LocalModelService.desktop.kt` | Al fallar el arranque: mensaje que ofrezca "reiniciar"; el `start` ya reintenta la espera de salud 120 × 500 ms (se conserva, pero se reporta el estado "arrancando" para la UI). |
| `rag/agent/AgentLoop.kt` | `AgentEvent.Failed`; `AgentAnswer.error/partial/rounds/modelFailures`; rama `is LlmChunk.Finish`; captura de `LlmException`; caché de tools idempotentes. |
| `rag/engine/RagEngine.kt` | `Prepared.plan`; `prepare(..., budget)`; recorte de historial y de chunks. |
| `core/.../domain/Chat.kt` | `ChatMessage.engineError`, `+ truncated`, `+ continuations`, `+ contextPlan`. |
| `core/.../domain/Analytics.kt` | `ModelUsage`; `CourseAnalytics.modelUsage`; `AnalyticsAnalyzer` lo calcula. |
| `core/.../domain/ModelPolicy.kt` | (opcional) `contextSize` como ventana efectiva también en REMOTE. |
| `ui/.../ChatScreen` (a construir) | Chip de salud, tarjeta de error, banner de fallback local, botón "Continuar". |

### 11.3 Orden de implementación (por dependencias)

1. `LlmException` + `LlmChunk.Finish` (cambios chicos, habilitan todo lo demás).
2. `RetryingGateway` + `RetryPolicy` + tests (función pura, se testea sin red).
3. `CircuitBreaker` + tests.
4. `HealthMonitor` + tests de la máquina de estados.
5. `EngineErrorInfo` en el dominio + `LlmErrorMessages.forStudent` + `EngineErrorCard`.
6. `TokenEstimator` + `ContextBudget` + `RagEngine.prepare(budget)`.
7. `StreamRecovery` + botón "Continuar".
8. `AgentLoop`: `Failed`, captura de errores, idempotencia de tools.
9. Panel del docente: `ModelPolicyDiff` + `ModelUsage` + `AnalyticsAnalyzer`.
10. `HuggingFaceDownloader` (port) + reanudación.

### 11.4 Tests previstos

| Test | Qué fija |
|---|---|
| `RetryPolicyTest` | 404 no reintenta; 429 no reintenta en chat; `CONTEXTO_AGOTADO` no reintenta; 5xx reintenta 3 veces; con texto emitido no reintenta; el jitter está dentro del rango; `Retry-After` se respeta. |
| `RetryingGatewayTest` | Con un gateway falso: 2 fallos y luego OK ⇒ emite el texto una sola vez; un fallo después de emitir texto ⇒ no reintenta y propaga. |
| `CircuitBreakerTest` | 3 fallos ⇒ OPEN; un intento en HALF_OPEN; éxito ⇒ CLOSED. |
| `HealthMonitorTest` | La máquina de estados: OK→OPERATIVO, OK lento→LENTO, 1 fallo→INESTABLE, 3 fallos→CAIDO, `successRate`, mediana. |
| `ContextBudgetTest` | Con ventana chica: el plan recorta historial antes que `topK` antes que el chunk; si no entra, `fits = false`. |
| `StreamRecoveryTest` | `trimToLastSentence` recorta en la última oración y no recorta si queda menos del 50 %. |
| `ModelPolicyDiffTest` | Detecta los campos cambiados; marca los bloqueados; no reporta campos iguales. |
| `AgentLoopTest` (extender) | Un gateway que falla después de emitir texto ⇒ el loop devuelve el parcial y `error != null`; una tool no se ejecuta dos veces con la misma firma. |

---

## 12. Riesgos y deuda

| # | Riesgo | Mitigación / deuda |
|---|---|---|
| R1 | El `Retry-After` no está disponible con la firma actual de `fromHttp` (no recibe headers). | Se cambia la firma con un parámetro opcional; el gateway tiene que leer el header. |
| R2 | El modelo remoto no expone `context_size` ⇒ la estimación del presupuesto puede ser incorrecta. | El docente puede fijar `contextSize` a mano; el Kitoru-Server debería devolverlo en `/models`. |
| R3 | La estimación de tokens (3,6 car./token) es aproximada: con un tokenizer distinto puede desviarse ±15 %. | Margen de seguridad del 512 tokens + el backend real devuelve `CONTEXTO_AGOTADO`: el plan evita el 95 % de los casos y el error real avisa del resto. |
| R4 | La continuación por prompt puede duplicar o contradecir texto. | Se recorta en la última oración, se avisa en la UI y se registra `continuations`. |
| R5 | El breaker es **por proceso**: si el alumno tiene dos ventanas abiertas con dos gateways, hay dos breakers. | El breaker se comparte por endpoint (clave del `baseUrl`) en un registro global del proceso. |
| R6 | Los incidentes del docente dependen de que los alumnos **compartan** sus datos o de que el Kitoru-Server los reenvíe. | El panel trabaja con lo que hay (visibilidad) y lo dice: "datos de 28 de 34 alumnos". El envío al Kitoru-Server es del hito de sincronización (`docs/design/sync/01-contrato.md`). |
| R7 | El monitor de salud genera tráfico: 1 `GET /models` cada 2 min por alumno. | Es trivial (sin inferencia, sin cuota) y se pausa en background. |
| R8 | `finish_reason` no lo manda todos los servers. | Si falta, se asume `STOP` (comportamiento actual) y el truncado no se detecta; el resto del diseño no depende de ello. |

---

## 13. Resumen en 5 líneas

1. **Cada fallo del modelo tiene un mensaje en español, una acción concreta y un
   estado terminal**: nunca hay spinner colgado ni texto perdido.
2. **El reintento vive en un decorador (`RetryingGateway`) con 3 intentos,
   backoff exponencial con jitter y circuit breaker**, y sólo cuando no salió
   ningún token; los errores deterministas (clave, cuota, 404, contexto) no se
   reintentan.
3. **La IA local no se activa sola**: se ofrece con un botón que explica que la
   respuesta será peor y que el docente no la verá (salvo que el alumno active
   la preferencia persistente, que siempre se anuncia).
4. **El alumno ve el estado del motor** (chip con latencia y último chequeo,
   `HealthMonitor` cada 2 min) y **el docente ve el panel "Estado del motor"**:
   modelo, endpoint, latencia, % de errores por tipo, incidentes accionables y
   la **configuración efectiva de cada alumno con el diff respecto de la base**.
5. **Los límites se previenen**: el presupuesto de contexto recorta
   historial → `topK` → tamaño de fragmento antes de enviar, y `finish_reason`
   permite avisar cuando la respuesta se cortó por `maxTokens`; el streaming
   cortado deja parcial + "Continuar" (continuación por prompt, sin reanudación
   real) y el `AgentLoop` nunca re-ejecuta una herramienta para "reintentar".
