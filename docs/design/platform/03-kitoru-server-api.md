# 03 — API del Kitoru-Server (contrato Kitoru ↔ Kitoru-Server)

> **Qué es este documento.** Define el contrato HTTP que el **Kitoru-Server**
> (la app de escritorio que aloja el modelo de la institución) expone y que
> **Kitoru** consume. Es la especificación que otro agente implementará en el
> repo hermano `Kitoru-Server`.
>
> **Fuentes leídas:** `docs/design/CONTEXTO.md`, `llm-api/.../LlmGateway.kt`
> (`OpenAiCompatibleGateway`, `LlmErrorCode`), `teacher/05-config-modelo.md` §3
> (contrato REST mínimo ya esbozado), `teacher/07-chats.md` (visibilidad y
> auditoría), `Silvy/README.md` + `Silvy/DISENO.md` (referencia de protocolo:
> `apiVersion` en las respuestas, errores tipados, cliente OpenAI-compatible con
> SSE) y `FunshiEngineAI/.../rag/mcp/McpProtocol.kt` (patrón de protocolo:
> JSON compacto, `ignoreUnknownKeys`, campos opcionales al final).
>
> **Reglas del proyecto que condicionan el diseño** (`CONTEXTO.md` §Reglas):
> portable por defecto, determinista, fail-fast con mensajes en español, el
> docente no ve lo que no debe, el estudiante conserva agencia.

---

## 0. Resumen ejecutivo (decisiones)

| # | Decisión | Motivo |
|---|---|---|
| D1 | **Un solo proceso** (`kitoru-server`) con **dos listeners**: `:8080` (red, `/v1/*`) y `:8081` (loopback, `/local/*`) | El modelo vive en la memoria del proceso; las keys y el outbox de chats comparten ese estado. Separar en procesos obligaría a un IPC local. Separar el *control plane* (loopback) del *data plane* (red) sí aporta seguridad. |
| D2 | **Una sola superficie versionada `/v1`** para inferencia + keys + docente | Una sola URL que configurar y un solo certificado. La gestión se separa por **credencial**, no por prefijo de ruta. *(Cambia `/api` de `05-config-modelo.md` §3.3 → ver §16.)* |
| D3 | Token **autocontenido por formato y caché**: `ktor_<kid>_<secreto>`; el servidor **no toca la BD en el camino caliente** | Requisito explícito del enunciado. Ver §2. |
| D4 | Las tres funciones usan **el mismo esquema de errores** `{"error":{code,message,…}}` y **HTTP 429 se desdobla** en `rate_limited` vs `quota_exceeded` | La app debe distinguir "cuota agotada" de "servidor caído" (`LlmErrorCode`). Ver §7.4. |
| D5 | `expiresAt` de una key **nunca puede pasar del `endsAt` de la cursada** | La promesa institucional "la clave vence al terminar la cursada" es una invariante del servidor, no una convención social. Ver §6. |
| D6 | Los **chats se entregan por pull** (`GET /v1/chats?afterSeq=`), no push | La app del docente es de escritorio y suele estar detrás de NAT; el servidor es el que tiene la IP pública. El push queda como opción (`POST /v1/webhooks`, v1.1). |
| D7 | El servidor **nunca afirma "este alumno no usó IA"**; afirma "**no usó la IA de la institución**" (evidencia dura) y "**declaró que usa IA local**" (evidencia blanda) | Honestidad epistémica: la IA local del alumno es invisible para el servidor. Es el punto clave del enunciado. Ver §9. |
| D8 | La **visibilidad se resuelve en el servidor** antes de entregar chats al docente (`min(roster del docente, declaración del alumno)`) | Defensa en profundidad: el docente no debe recibir texto que no puede ver, aunque el cliente falle. Ver §8.4. |
| D9 | Toda respuesta JSON lleva **`apiVersion`** (entero), al estilo Silvy | Permite evolucionar el contrato sin romper clientes. Ver §12. |

---

## 1. Los tres roles del Kitoru-Server

### 1.1 Los roles

| Rol | Qué hace | Quién lo consume | Puerto |
|---|---|---|---|
| **(a) Inference** | Aloja el modelo (descarga el GGUF, supervisa `llama-server` como proceso hijo) y sirve inferencia **compatible con OpenAI** (`/v1/chat/completions`, `/v1/models`), con streaming SSE, tool calling y `usage`. | Alumnos y cualquier cliente OpenAI-compatible (incluido Silvy). | `:8080` |
| **(b) Emisión/administración de keys** | Emite API keys con **vencimiento por cursada**, las renueva, las rota y las revoca; aplica cuotas por key/alumno/cátedra. | Docente e institución (con **token de servicio**). | `:8080` |
| **(c) Reenvío de chats al docente** | Registra cada intercambio served (lo ve porque es el que infiere) y lo **entrega** al docente ya recortado por visibilidad; además informa **cobertura** (quién usó la IA de la institución y quién no). | App del docente (token de servicio). | `:8080` |

### 1.2 ¿Mismo proceso o procesos separados?

**Un solo proceso: `kitoru-server`.** Los tres roles comparten:

- el **modelo cargado en memoria** (el rol (a) es el dueño del `llama-server`);
- el **estado de las keys** (el rol (b) decide si el rol (a) acepta la petición);
- el **outbox de chats** (el rol (c) se alimenta del propio tráfico del rol (a)).

Separarlos en procesos diferentes obligaría a un IPC local (un socket, una cola, un
formato más) para justo tres cosas que son locales. El aislamiento real que
importa no es entre roles sino **entre redes**: por eso se separan los listeners.

### 1.3 Los dos listeners

```
┌──────────────────────── proceso kitoru-server ────────────────────────┐
│                                                                        │
│  ┌── listener :8080 (red) ── prefijo /v1 ──────────────────────────┐  │
│  │  /v1/chat/completions   /v1/models        ← inferencia (a)      │  │
│  │  /v1/keys/**            /v1/terms/**     ← keys (b)             │  │
│  │  /v1/chats/**  /v1/usage/report  /v1/audit/**  ← chats (c)      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  ┌── listener :8081 (SOLO 127.0.0.1) ── prefijo /local ────────────┐  │
│  │  /local/model/status   /local/model/download   /local/logs/tail  │  │
│  │  /local/config   /local/shutdown   (control de la app desktop) │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  ┌── estado local ──────────────────────────────────────────────────┐  │
│  │  llama-server (hijo)  ·  almacén de keys  ·  outbox de chats    │  │
│  │  contadores de cuota  ·  denylist de revocados  ·  audit log     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

- `:8080` es el **único** que la institución expone (con TLS o detrás de un
  terminador TLS). Es la URL que el docente configura en
  `ModelPolicy.remoteBaseUrl` → `https://ia.universidad.edu/kitoru/v1`.
- `:8081` es **loopback puro**: lo usa la UI de la app de escritorio para mostrar
  el estado de la descarga, ver logs y apagar el servidor. No requiere
  autenticación (no es alcanzable desde la red) y **no forma parte del contrato
  público** — se documenta acá sólo para que quede claro que existe.
- **Endurecimiento opcional:** `--admin-bind 10.0.0.5:8090` sirve las rutas de
  gestión (`/v1/keys/**`, `/v1/terms/**`, `/v1/courses/**`, `/v1/chats/**`,
  `/v1/audit/**`) **sólo** en ese address:port, y devuelve 404 en `:8080`. Es
  para instituciones que quieren que el gateway institucional sea el único que
  ve el tráfico de gestión.

### 1.4 Configuración del proceso (resumen)

```
--host 0.0.0.0 --port 8080        # listener de red
--admin-bind <host:puerto>        # opcional: separa la gestión en otro puerto
--local-port 8081                 # listener loopback
--model <repo/file.gguf>          # modelo a servir (o el ya descargado)
--data-dir <carpeta>              # keys, outbox, auditoría, contadores
--tls <cert.pem> --tls-key <key.pem>   # TLS directo (si no hay proxy)
--allow-http-local                # permitir HTTP sólo en loopback (dev)
```

---

## 2. Credenciales y autenticación

### 2.1 Formato del token

Todas las credenciales del Kitoru-Server tienen la misma forma:

```
ktor_<kid>_<secret>
```

| Parte | Formato | Significado |
|---|---|---|
| `ktor_` | 5 chars | Prefijo fijo. Permite reconocer una credencial de Kitoru en un log o un proxy. |
| `kid` | 12 chars, **base32 Crockford** (alfabeto `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, sin `I`, `L`, `O`, `U`) | Identificador **público** de la credencial. Es el `id` de la key y el que devuelve `GET /v1/keys/{id}`. El **primer carácter indica el tipo**: `K` = key de alumno, `S` = token de servicio (docente), `A` = token de administración (institución). Ej.: `K01J9Z8Y2QK4M`. |
| `secret` | 43 chars **base64url** de 32 bytes CSPRNG | El secreto real. Viaja **una sola vez** (al emitir la credencial) y no se vuelve a mostrar. |

Longitud total: **61 caracteres**. Ejemplo real:

```
ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA
```

El `Authorization` es el estándar de OpenAI:

```http
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA
```

> **Nota de seguridad.** El `kid` NO es un secreto: aparece en la UI del
> docente, en los logs del servidor y en las cabeceras de respuesta. Filtrar el
> `kid` no sirve de nada. Filtrar el `secret` sí.

### 2.2 Por qué el servidor no consulta la BD en cada request

El camino caliente es `POST /v1/chat/completions`, que puede recibir cientos de
peticiones por minuto por alumno. Una consulta a la BD (SQLite, Postgres) por
petición es un cuello de botella evitable. El diseño lo evita con **tres capas**:

**Capa 1 — el secreto nunca se guarda en claro.**
El servidor guarda `SHA-256(pepper || secret)` con un *pepper* secreto del
servidor (32 bytes, generado en el primer arranque, guardado cifrado en reposo
con DPAPI/Keychain). El `pepper` se puede rotar sin invalidar las keys
(re-hasheando todas: `POST /v1/admin/rotate-pepper`).

**Capa 2 — caché en memoria de credenciales resueltas.**

```
Cache de credenciales (en memoria del proceso)
  kid ──► { hash, tipo, courseId, term, subject, expiresAt, scopes, limits, status }
  TTL: 60 s   ·   Miss → 1 lectura indexada a la BD → se cachea
  Revocación/rotación/renew desde ESTE proceso → invalidación inmediata (sin esperar TTL)
```

Con esto, una petición de inferencia hace **cero** accesos a la BD mientras la
credencial esté cacheada. Entre dos instancias del servidor (si alguna vez se
escala), el TTL de 60 s es el techo de retraso de una revocación; el diseño
recomienda **una sola instancia** por institución (el modelo ya es un recurso
exclusivo).

**Capa 3 — variante firmada (opcional, `format = 2`).**
Para quien quiera un token **100 % autocontenido** (verificable sin ninguna
consulta, incluso sin caché), existe un segundo formato:

```
ktor_<kid>_<claims_b64url>.<firma_b64url>

claims = base64url( {"c":"IA-2026-1","t":"2026-2","s":"K","e":1798761600000,"v":2} )
firma  = base64url( HMAC-SHA256(claims, serverSecret)[0..21] )   # 22 bytes
```

El servidor verifica la firma con su secreto local: **no consulta nada**. La
revocación, en este modo, se resuelve con una **denylist** de `kid` (en
memoria, sincronizada entre instancias por evento) que se consulta en cada
request — pero es una lookup en un `HashSet`, no una consulta de BD.

**Recomendación:** arrancar con el formato opaco + caché (formato 1) y dejar el
firmado para cuando haga falta (ver §11.4).

### 2.3 Matriz de credenciales

| Tipo | Prefijo del `kid` | Dónde se usa | Puede |
|---|---|---|---|
| **Key de alumno** | `K…` | `POST /v1/chat/completions` | Hablar con el modelo. **Nada más**: no lee chats, no emite keys, no ve la auditoría. |
| **Token de servicio** | `S…` | `/v1/keys/**`, `/v1/chats/**`, `/v1/courses/**`, `/v1/audit/**` | Lo que el `scope` de la credencial permita, y **sólo** sobre las cátedra de su `scope`. |
| **Token de administración** | `A…` | Todo `/v1/**` + `/v1/terms/**` + `/v1/admin/**` | Emitir tokens de servicio, definir cursadas, configurar el servidor. |

El alcance de un token de servicio se define al emitirlo:

```json
{
  "scopes": ["keys:issue", "keys:revoke", "chats:read", "audit:read", "audit:write"],
  "courseIds": ["IA-2026-1"],
  "roles": ["TITULAR", "AYUDANTE"],
  "expiresAt": 1790000000000
}
```

Una key de alumno **no** puede llamar a `/v1/keys/**` (403 `insufficient_scope`),
aunque el `kid` sea válido: el tipo se lee del registro cacheado.

---

## 3. Endpoint de inferencia — `POST /v1/chat/completions`

Compatible con OpenAI: **Kitoru lo habla sin cambios** (`OpenAiCompatibleGateway`
en `llm-api/.../LlmGateway.kt` ya hace `POST {baseUrl}/chat/completions` con
`stream: true`, SSE y accumulation de `delta.tool_calls`).

### 3.1 Petición

```http
POST /v1/chat/completions HTTP/1.1
Host: ia.universidad.edu
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA
Content-Type: application/json
Accept: text/event-stream
```

Cabeceras **opcionales** de Kitoru (si no vienen, el servidor funciona igual;
sólo pierde granularidad de auditoría y de agrupación de hilos):

| Cabecera | Valor | Para qué |
|---|---|---|
| `X-Kitoru-Student-Id` | `enroll_9f3` | Identifica al alumno dentro de la key. Alias aceptado: `X-Kitoru-Student` (el de `05-config-modelo.md` §3.3). |
| `X-Kitoru-Thread-Id` | `thr_7a2` | Agrupa los turnos en un hilo (el docente ve “conversaciones”). |
| `X-Kitoru-Course-Id` | `IA-2026-1` | Se **valida** contra la key: si no coincide → 403. |
| `X-Kitoru-Term` | `2026-2` | Se valida contra la key. |
| `X-Kitoru-Request-Id` | `req_01J9…` | Correlación cliente↔servidor (se devuelve en la respuesta). |
| `X-Kitoru-Client` | `kitoru/0.3.0 (desktop)` | Telemetría honesta del cliente. |

Cuerpo: **exactamente el de OpenAI**. El servidor acepta (y tolera) los campos
que ya manda `OpenAiCompatibleGateway`:

```json
{
  "model": "alias:catedra/rapido",
  "stream": true,
  "temperature": 0.10,
  "max_tokens": 1024,
  "reasoning_effort": "off",
  "tools": [
    { "type": "function", "function": { "name": "get_time", "description": "…", "parameters": { "type": "object", "properties": {} } } }
  ],
  "messages": [
    { "role": "system", "content": "Sos un tutor de la cátedra IA-2026-1…" },
    { "role": "user", "content": "¿Me explicás SVM con margen?" }
  ]
}
```

Reglas del servidor sobre el cuerpo:

- `model`: acepta un **alias** (§4) o el id concreto del modelo. Si el alias no
  existe para la cátedra de la key → `404 model_not_found`.
- `stream`: `true` (SSE) o `false` (JSON). `false` no lo usa Kitoru hoy, pero la
  API debe ofrecerlo (es OpenAI-compatible).
- `reasoning_effort`: se acepta y se **ignora con elegancia** si el backend no lo
  soporta (nunca 400 por esto). Si el modelo no razona, se responde 200 normal.
- `max_tokens`: si supera el tope de la política de la cátedra, el servidor lo
  **recorta** al tope y lo informa en la cabecera `X-Kitoru-Applied-Max-Tokens`
  (no es un error: el alumno pidió más de lo permitido).
- `messages`: se reenvían tal cual al `llama-server`. El servidor **no** inyecta
  ni modifica el prompt del sistema (el `systemPrompt` de la cátedra lo pone el
  cliente, como ya hace hoy `ModelPolicy.systemPrompt`).
- `user`: se acepta y se usa como respaldo del `X-Kitoru-Student-Id`.
- Campos desconocidos: se ignoran (`ignoreUnknownKeys`, como en `McpProtocol`).

### 3.2 Respuesta con streaming (`stream: true`)

`Content-Type: text/event-stream`. Cada evento es `data: {json}\n\n` y el
stream cierra con `data: [DONE]\n\n` — el parser de Kitoru (`readUTF8Line()` +
`startsWith("data:")` + `data == "[DONE]"` → break) funciona sin cambios.

**Chunk de contenido** (OpenAI estándar):

```json
{"id":"chatcmpl-01J9Z8Y2QK4M7fQ","object":"chat.completion.chunk","created":1772000123,
 "model":"Qwen3-8B-Instruct",
 "choices":[{"index":0,"delta":{"role":"assistant","content":"El margen es"},"finish_reason":null}]}
```

**Chunk de tool call** (fragmentado, con `index` — el acumulador de Kitoru
agrupa por índice):

```json
{"id":"chatcmpl-…","object":"chat.completion.chunk","created":1772000123,"model":"Qwen3-8B-Instruct",
 "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a1","type":"function",
   "function":{"name":"get_time","arguments":"{\"tz\":\""}}]},"finish_reason":null}]}
```

**Chunk final con `usage`** — **el extra clave**:

```json
{"id":"chatcmpl-01J9Z8Y2QK4M7fQ","object":"chat.completion.chunk","created":1772000124,
 "model":"Qwen3-8B-Instruct",
 "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
 "usage":{"prompt_tokens":118,"completion_tokens":412,"total_tokens":530,
          "prompt_tokens_details":{"cached_tokens":0},
          "completion_tokens_details":{"reasoning_tokens":0}}}
```

> **Por qué `usage` va siempre, sin `stream_options`.** El cliente OpenAI
> estándar sólo pide `usage` en streaming si manda
> `stream_options: {"include_usage": true}`. **`OpenAiCompatibleGateway` de
> Kitoru no lo manda** (mira `llm-api/.../LlmGateway.kt`, `chatStream`), así que
> un servidor OpenAI "puro" nunca le mandaría `usage` y Kitoru perdería los
> tokens (que `ChatMessage.tokensIn/tokensOut` necesita). Por eso el
> Kitoru-Server **manda `usage` siempre** en el último chunk, **y además** manda
> un chunk extra con `"choices": []` si el cliente pidió
> `stream_options.include_usage` (compatibilidad OpenAI estricta).
>
> Esto **no rompe** el parser actual: `choices` vacío → `firstOrNull()` → `null`
> → `?: continue`. ✔

Cabeceras de respuesta (útiles para la UI y para el cliente):

| Cabecera | Ejemplo | Para qué |
|---|---|---|
| `X-Kitoru-Request-Id` | `req_01J9…` | Correlación con los logs del servidor. |
| `X-Kitoru-Key-Id` | `K01J9Z8Y2QK4M` | Qué key se usó. |
| `X-Kitoru-Course-Id` / `X-Kitoru-Term` | `IA-2026-1` / `2026-2` | Contexto de facturación. |
| `X-Kitoru-Resolved-Model` | `Qwen3-8B-Instruct` | Modelo real detrás del alias. |
| `X-Kitoru-Quota-Tokens-Remaining` | `143210` | Tokens que le quedan hoy (para el chip de cuota). |
| `X-Kitoru-Quota-Tokens-Reset` | `1772409600000` | Cuándo se reinicia el contador (epoch ms). |
| `X-Kitoru-Quota-Rpm-Remaining` | `17` | Peticiones/minuto restantes. |
| `X-Kitoru-Quota-Rpm-Reset` | `1772000160000` | Cuándo se abre la ventana del rate limit. |

### 3.3 Respuesta sin streaming (`stream: false`)

```json
{
  "id": "chatcmpl-01J9Z8Y2QK4M7fQ",
  "object": "chat.completion",
  "created": 1772000123,
  "model": "Qwen3-8B-Instruct",
  "choices": [
    { "index": 0, "message": { "role": "assistant", "content": "El margen es…" },
      "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 118, "completion_tokens": 412, "total_tokens": 530 }
}
```

### 3.4 Tool calling

- Formato **OpenAI exacto**: `tools[]` con `type: "function"`, `function.name`,
  `function.description`, `function.parameters` (JSON Schema).
- En streaming: `delta.tool_calls[]` con `index`, `id` (sólo en el primer delta
  de esa call), `type: "function"`, `function.name` (sólo en el primero),
  `function.arguments` **fragmentado** como string JSON.
- `finish_reason: "tool_calls"` cuando el modelo pide herramientas.
- Los mensajes `role: "tool"` con `tool_call_id` se reenvían tal cual.
- El servidor **no** ejecuta las herramientas: eso lo hace el `AgentLoop` de
  Kitoru en el cliente. El servidor sólo las registra (para el docente).
- El servidor **aplica la política de la cátedra**: si la cátedra desactivó una
  herramienta, el servidor **no la anuncia** en `tools[]` (filtra la lista antes
  de mandársela al backend). Si el modelo pide una tool que la política no
  habilita, la respuesta es `403 tool_not_allowed` con un mensaje en español.

### 3.5 `GET /v1/models`

Es lo que llama `OpenAiCompatibleGateway.listModels()` (`GET {baseUrl}/models`)
para el botón **"Probar conexión"** del docente. Debe devolver
`{"data":[{"id":"…"}]}` — el parser sólo lee `data[].id`.

```json
{
  "object": "list",
  "apiVersion": 1,
  "data": [
    { "id": "Qwen3-8B-Instruct", "object": "model", "created": 1770000000, "owned_by": "institution" },
    { "id": "Qwen3-14B-Instruct", "object": "model", "created": 1770000000, "owned_by": "institution" },
    { "id": "alias:catedra/rapido",     "object": "model", "owned_by": "course:IA-2026-1",
      "kitoru": { "kind": "alias", "scope": "course", "resolvesTo": "Qwen3-8B-Instruct",
                  "label": "Rápido", "description": "Respuestas rápidas para consultas." } },
    { "id": "alias:catedra/razonador",  "object": "model", "owned_by": "course:IA-2026-1",
      "kitoru": { "kind": "alias", "scope": "course", "resolvesTo": "Qwen3-14B-Instruct",
                  "label": "Razonador", "description": "Para ejercicios y demostraciones." } },
    { "id": "alias:servidor/default",   "object": "model", "owned_by": "server",
      "kitoru": { "kind": "alias", "scope": "server", "resolvesTo": "Qwen3-8B-Instruct",
                  "label": "Modelo por defecto de la institución" } }
  ]
}
```

El listado se **filtra por la credencial**: una key de alumno ve los alias de
**su** cátedra + los alias de servidor + los modelos concretos permitidos por
la política; un token de servicio ve todo lo de sus cátedras.

### 3.6 Otros endpoints de inferencia

| Endpoint | Estado | Nota |
|---|---|---|
| `POST /v1/embeddings` | **No en v1** | El RAG de Kitoru es BM25 local (`rag.retrieval.Bm25Index`); no hace falta embeddings remotos. |
| `POST /v1/completions` (legacy) | **No** | No lo usa nadie. |
| `GET /v1/health` | Sí | `{"status":"ok","model":"ready","apiVersion":1,"uptimeSec":8123}` → 200. Si el modelo no está listo → 503 `model_not_ready`. Lo usa el cliente de escritorio para el estado. |

---

## 4. Modelos y alias

### 4.1 Sintaxis

```
model: <id-concreto>  |  alias:<ámbito>/<nombre>
```

| Ámbito | Significado | Quién lo define |
|---|---|---|
| `catedra` | Alias de **la cátedra a la que pertenece la key**. Ej. `alias:catedra/rapido`. | El docente, en `Course.modelPresets` (`05-config-modelo.md` §1.3); se sincroniza al servidor. |
| `servidor` | Alias **de la institución**, disponible para todas las cátedras. Ej. `alias:servidor/default`. | La institución (token `A…`). |
| `material` | Alias que apunta a un **material concreto** del modelo ya cargado (no previsto en v1; reservado). | — |

### 4.2 Resolución

1. Si `model` **no** empieza por `alias:` → es un id concreto: se usa tal cual
   (y se verifica contra la política de la cátedra).
2. Si empieza por `alias:` → se separa en `ámbito/nombre`:
   - `catedra` → se busca el alias en la política de la cátedra de la key.
     El `id` del preset (`ModelPreset.id`) es el `nombre` del alias.
   - `servidor` → se busca en la configuración del servidor.
3. Si no existe → `404` con `error.code = "model_not_found"` y un mensaje que
   nombra los alias disponibles.

### 4.3 Política de modelo por cátedra (la aplica el servidor)

El servidor es la **autoridad** sobre la política, aunque el cliente ya la
aplique localmente (`ModelPolicy.resolveFor` + `LockedField`):

```json
{
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "modelPolicy": {
    "allowedModels": ["Qwen3-8B-Instruct", "Qwen3-14B-Instruct"],
    "aliases": [
      { "id": "rapido",     "label": "Rápido",     "model": "Qwen3-8B-Instruct",
        "temperature": 0.10, "maxTokens": 1024, "thinking": "off", "power": "medium" },
      { "id": "razonador",  "label": "Razonador",  "model": "Qwen3-14B-Instruct",
        "temperature": 0.30, "maxTokens": 4096, "thinking": "high", "power": "high" }
    ],
    "lockedFields": ["MODEL_NAME", "TEMPERATURE", "MAX_TOKENS"],
    "maxTokensCap": 4096,
    "temperatureCap": 1.0,
    "toolsEnabled": true,
    "allowedTools": ["get_time", "create_file"]
  }
}
```

Reglas:

- Si `MODEL_NAME` está bloqueado y el alumno pide otro modelo → el servidor
  **ignora el pedido** y usa el del alias/policy (`200`, con
  `X-Kitoru-Resolved-Model` y `X-Kitoru-Model-Locked: true`). No es un error:
  el bloqueo es una decisión de la cátedra, no un fallo del alumno.
- Si `MODEL_NAME` **no** está bloqueado pero el modelo pedido no está en
  `allowedModels` → `403 model_not_allowed` con el mensaje
  *"El modelo «X» no está permitido en esta cátedra. Modelos disponibles: …"*.
- `max_tokens` por encima de `maxTokensCap` → se recorta (cabecera
  `X-Kitoru-Applied-Max-Tokens`), nunca 400.
- `temperature` por encima de `temperatureCap` → se recorta al tope.
- `toolsEnabled = false` → el servidor **omite** el campo `tools` al backend
  (el modelo no ve herramientas). Si el cliente mandó `tools[]` y la política los
  desactiva, el servidor responde 200 sin herramientas y añade
  `X-Kitoru-Tools-Denied: get_time,create_file`.

---

## 5. API de keys

Prefijo: `/v1/keys`. Autenticación: **token de servicio** (`S…`) o de
administración (`A…`). Una key de alumno (`K…`) recibe `403 insufficient_scope`.

### 5.1 `POST /v1/keys` — emitir

**Quién puede:** el docente con `keys:issue` y la cátedra dentro de su `scope`;
la institución con token `A…`.

```http
POST /v1/keys HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
Content-Type: application/json
```

```json
{
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "subject": { "type": "STUDENT", "id": "enroll_9f3", "displayName": "Ana Ruiz" },
  "label": "Ana Ruiz — IA 2026-2",
  "scope": ["chat"],
  "expiresAt": null,
  "maxUses": 1,
  "models": ["alias:catedra/rapido", "alias:catedra/razonador"],
  "limits": { "requestsPerMinute": 20, "tokensPerDay": 200000, "maxConcurrentRequests": 2 },
  "metadata": { "enrollmentId": "enroll_9f3" }
}
```

| Campo | Obligatorio | Reglas |
|---|---|---|
| `courseId` | ✔ | Debe existir en el servidor (`404 course_not_found`) y estar en el `scope` del token (`403 course_forbidden`). Es el **código** de la cátedra (p. ej. `IA-2026-1`), igual que en `05-config-modelo.md` §3.3. |
| `term` | ✔ | Debe existir (`404 term_not_found`). Si no, la key **no vence** → error. |
| `subject` | — | `type`: `STUDENT` \| `COURSE`. Si es `STUDENT`, `id` es el id de inscripción. Si es `COURSE`, es la key compartida de la cátedra (`ApiKeyMode.COURSE`). |
| `label` | — | Texto libre para la UI. |
| `scope` | — | `["chat"]` por defecto. `["chat", "tools"]` habilita tool calling (si la política de la cátedra lo permite). |
| `expiresAt` | — | Epoch ms. Si viene `null`/ausente → **`term.endsAt`** (política de cursada). Si viene un valor **> `term.endsAt`** → `400 expires_after_term` (§6.2). |
| `maxUses` | — | Nº máximo de **alumnos distintos** que pueden usar esta key (0 = ilimitado). `ApiKeyMode.COURSE` lo usa como "cupo de alumnos". |
| `models` | — | Lista blanca de modelos/alias. Vacío = los permitidos por la política de la cátedra. |
| `limits` | — | Sobrescribe los límites por key (§7). Si falta un campo, se hereda el de la cátedra. |
| `metadata` | — | Pares libres (≤ 16 claves, ≤ 256 chars). Sirve para `enrollmentId`, `nroMesa`, etc. |

**Respuesta `201 Created`** (el `token` se devuelve **una sola vez**):

```json
{
  "apiVersion": 1,
  "id": "K01J9Z8Y2QK4M",
  "token": "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA",
  "tokenLast4": "J0kA",
  "type": "STUDENT_KEY",
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "subject": { "type": "STUDENT", "id": "enroll_9f3", "displayName": "Ana Ruiz" },
  "label": "Ana Ruiz — IA 2026-2",
  "scope": ["chat"],
  "status": "ACTIVE",
  "createdAt": 1772000000000,
  "expiresAt": 1798761600000,
  "termEndsAt": 1798761600000,
  "renewals": 0,
  "maxUses": 1,
  "usedSubjects": 0,
  "lastUsedAt": null,
  "revokedAt": null,
  "limits": { "requestsPerMinute": 20, "tokensPerDay": 200000, "maxConcurrentRequests": 2 },
  "models": ["alias:catedra/rapido", "alias:catedra/razonador"]
}
```

**Errores:**

| HTTP | `error.code` | Mensaje (español, mostrable) |
|---|---|---|
| 400 | `invalid_request` | "Faltan campos obligatorios: courseId, term." |
| 400 | `expires_after_term` | "La clave no puede vencer después del fin de la cursada 2026-2 (31/12/2026)." |
| 401 | `missing_credentials` | "Falta el token de servicio." |
| 403 | `insufficient_scope` | "Tu credencial no puede emitir claves." |
| 403 | `course_forbidden` | "Tu credencial no tiene acceso a la cátedra IA-2026-1." |
| 404 | `course_not_found` | "El servidor no conoce la cátedra IA-2026-1." |
| 404 | `term_not_found` | "No existe la cursada 2026-2 en este servidor." |
| 409 | `key_limit_reached` | "La cátedra IA-2026-1 ya tiene 40 claves vigentes para 2026-2 (máximo 40)." |
| 429 | `rate_limited` | "Demasiadas emisiones seguidas. Probá en 12 s." (+ `Retry-After`) |
| 503 | `model_not_ready` | "El Kitoru-Server todavía está preparando el modelo. Probá en unos minutos." |

### 5.2 `GET /v1/keys/{id}` — consultar

```http
GET /v1/keys/K01J9Z8Y2QK4M HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
```

`200` con **el mismo esquema** de §5.1 **sin** `token`, más:

```json
{
  "apiVersion": 1,
  "id": "K01J9Z8Y2QK4M",
  "tokenLast4": "J0kA",
  "status": "ACTIVE",
  "…": "…",
  "usage": {
    "requestsToday": 42,
    "tokensToday": 51230,
    "tokensTodayLimit": 200000,
    "tokensTodayResetsAt": 1772409600000,
    "requestsTotal": 1287,
    "tokensTotal": 1493002,
    "lastUsedAt": 1772000123000,
    "lastUsedIp": "10.0.3.17",
    "distinctSubjects": 1
  },
  "renewals": 0,
  "renewalPolicy": { "selfRenewable": true, "maxSelfRenewals": 1, "renewableUntil": 1798761600000 }
}
```

El **secreto nunca vuelve a salir**: el servidor guarda sólo el hash. `tokenLast4`
permite al docente reconocer la key sin exponerla (mismo criterio que
`ApiKeyGrant.keyLast4` / `masked` de `05-config-modelo.md` §1.5).

| HTTP | `error.code` | Mensaje |
|---|---|---|
| 404 | `key_not_found` | "No existe la clave K01J9Z8Y2QK4M." |
| 403 | `course_forbidden` | "Esa clave es de otra cátedra." |

### 5.3 `GET /v1/keys` — listar

```
GET /v1/keys?courseId=IA-2026-1&term=2026-2&status=ACTIVE&subjectId=enroll_9f3&limit=100&offset=0
```

```json
{
  "apiVersion": 1,
  "keys": [ { "id": "K01J9Z8Y2QK4M", "…": "…" } ],
  "total": 34,
  "nextOffset": 34
}
```

Filtros: `courseId` (obligatorio si el token no es `A…`), `term`, `status`
(`ACTIVE|SUSPENDED|REVOKED|EXPIRED`), `subjectId`, `q` (busca en `label`).
Paginación por `limit`/`offset` (máx. 200 por página).

### 5.4 `DELETE /v1/keys/{id}` — revocar

```http
DELETE /v1/keys/K01J9Z8Y2QK4M HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
```

→ **`204 No Content`** (sin cuerpo). La revocación es **inmediata e irrevocable**:
el `kid` entra en la denylist en memoria (invalidación instantánea, sin esperar
el TTL de la caché) y el registro queda `REVOKED` con `revokedAt`.

Un token de alumno con esa key recibe desde ese momento:

| HTTP | `error.code` | Mensaje en la app del alumno |
|---|---|---|
| 401 | `key_revoked` | "Esta clave fue revocada. Pedí una nueva a tu docente." |

### 5.5 `POST /v1/keys/{id}/extend` — renovar

```http
POST /v1/keys/K01J9Z8Y2QK4M/extend HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…     ← o la key del alumno (§5.5.1)
Content-Type: application/json

{ "expiresAt": 1798761600000 }
```

o, equivalente:

```json
{ "days": 30 }
```

**Respuesta `200`**: el mismo esquema de §5.1 (sin `token`), con `expiresAt`
actualizado, `renewals` incrementado y `status: "ACTIVE"`.

Reglas:

- **Nunca** se extiende más allá de `term.endsAt` → `400 expires_after_term`.
- La renovación **no cambia el secreto**: el alumno sigue usando la misma key.
  (Renovar ≠ rotar; rotar es §5.6.)
- Queda auditada: `audit: key.extended` con `kid`, actor (`S…`/`K…`), `expiresAt`
  anterior y nuevo.
- Si la key estaba `EXPIRED`, la renovación la **reactiva** (mientras no se haya
  revocado). Si estaba `REVOKED`, no se reactiva → `409 key_revoked`.

#### 5.5.1 El alumno renueva su propia key

El enunciado pregunta explícitamente qué pasa si un alumno renueva.

**Decisión: el alumno puede auto-renovar su propia key**, y sólo hasta el fin de
la cursada:

- El alumno llama `POST /v1/keys/{id}/extend` con **su propia key** como
  `Authorization`. El servidor reconoce el `kid` de la key, verifica que el
  `subject` es `STUDENT` y que la key no está revocada.
- El límite duro es `term.endsAt`: **la key nunca sobrevive a la cursada**,
  aunque el alumno renueve todos los días.
- Límite blando: `maxSelfRenewals` (por defecto 1 por cursada, configurable por
  la cátedra) para que el botón no sea un "eterno" y el docente reciba un aviso
  en la UI ("Ana renovó su clave 2 veces").
- El servidor responde `200` con la nueva expiración, o:
  - `403 self_renewal_not_allowed` → "Tu clave no se puede renovar: consultá a tu docente."
  - `403 renewal_limit_reached` → "Ya renovaste tu clave el máximo de veces permitido."
  - `400 expires_after_term` → "Tu clave vence con la cursada (31/12/2026)."

En la app del alumno esto se ve como un chip rojo → botón **"Renovar mi clave"**
que llama a ese endpoint (no hace falta intervención del docente).

### 5.6 `POST /v1/keys/{id}/rotate` — rotar (filtración)

Devuelve una key **nueva** (mismo `id` de familia, nuevo `secret`) y **anula la
anterior de inmediato**. Es el camino de "se filtró la key".

```http
POST /v1/keys/K01J9Z8Y2QK4M/rotate HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
```

→ `201` con el mismo esquema de §5.1 **incluyendo `token`** (el nuevo secreto,
mostrado una sola vez). `oldSecretValidUntil` permite un grace period de
opcional `{"graceSeconds": 3600}` durante el cual la clave vieja todavía
funciona (para que los alumnos no se queden sin conexión).

### 5.7 Otros endpoints de keys

| Endpoint | Para qué | Nota |
|---|---|---|
| `POST /v1/keys/{id}/suspend` | Pausar una key sin revocarla (`SUSPENDED` → 401 `key_suspended`). | Un alumno la puede "reactivar" con `POST /v1/keys/{id}/resume`. Útil para una cursada que se suspende. |
| `POST /v1/keys/{id}/resume` | Reactivar una key pausada. | |
| `GET /v1/keys/{id}/usage` | Sólo los contadores de uso (sin metadatos). | |

### 5.8 `GET /v1/keys/me` — introspección de la key del alumno

Útil para la app del alumno (chip de cuota, fecha de vencimiento, si su clave
sigue viva):

```http
GET /v1/keys/me HTTP/1.1
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…
```

```json
{
  "apiVersion": 1,
  "id": "K01J9Z8Y2QK4M",
  "status": "ACTIVE",
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "termEndsAt": 1798761600000,
  "expiresAt": 1798761600000,
  "renewals": 0,
  "canSelfRenew": true,
  "usage": { "requestsToday": 42, "tokensToday": 51230, "tokensTodayLimit": 200000,
             "tokensTodayResetsAt": 1772409600000 },
  "limits": { "requestsPerMinute": 20, "tokensPerDay": 200000 }
}
```

---

## 6. Cursadas (`term`) y vencimiento

### 6.1 El modelo `term` en el servidor

La cursada es una **entidad de primer clase** del servidor, no un texto libre.

```json
{
  "apiVersion": 1,
  "id": "2026-2",
  "label": "Cursada 2026-2",
  "startsAt": 1775000000000,
  "endsAt": 1798761600000,
  "enrollmentOpensAt": 1773000000000,
  "enrollmentClosesAt": 1795000000000,
  "active": true,
  "createdAt": 1770000000000,
  "note": "Segundo semestre de 2026."
}
```

| Campo | Significado |
|---|---|
| `id` | `AAAA-N` (año-semestre). Es el `term` de `Course.term` en Kitoru. |
| `startsAt` / `endsAt` | Ventana de la cursada. `endsAt` es **el techo de expiración** de todas las keys de esa cursada. |
| `enrollmentOpensAt` / `enrollmentClosesAt` | Ventana en la que se pueden emitir keys (opcional; por defecto `[startsAt - 30d, endsAt]`). |
| `active` | Si está `false`, el servidor **rechaza** nuevas emisiones para esa cursada (`409 term_closed`) pero las keys ya emitidas siguen funcionando hasta su `expiresAt`. |

**Endpoints** (token `A…` para escribir; `S…` puede leer las cursadas de sus cátedras):

| Endpoint | Efecto |
|---|---|
| `GET /v1/terms` | Lista las cursadas. |
| `POST /v1/terms` | Crea una cursada (token `A…`). `201`. |
| `PATCH /v1/terms/{id}` | Edita `label`, `active`, fechas. **No se puede mover `endsAt` por debajo del `expiresAt` de una key viva**: en ese caso el servidor mueve también las keys vivas o las deja vencer — decisión explícita, por defecto **las deja vencer** (`409 term_end_conflict` con el detalle de cuántas keys quedan fuera). |
| `GET /v1/terms/{id}` | Detalle + conteo de keys vivas de la cursada. |

### 6.2 La invariante de expiración

> **Toda key tiene `expiresAt ≤ term.endsAt`.**

Se aplica en `POST /v1/keys` y en `POST /v1/keys/{id}/extend`. Si se viola:
`400 expires_after_term` con el mensaje

```
"La clave no puede vencer después del fin de la cursada 2026-2 (31/12/2026)."
```

Una key **sin** `term` (modo "clave de la institución", p. ej. para un piloto
fuera de cursada) sí puede tener `expiresAt` propio, con un tope global de
configuración (`maxKeyLifetimeDays`, por defecto 365) → `400 key_lifetime_too_long`.

### 6.3 El ciclo completo de una cursada

```
1. La institución crea la cursada           POST /v1/terms        {id:"2026-2", endsAt:…}
2. El docente registra su cátedra           PUT  /v1/courses/IA-2026-1
3. El docente emite keys                    POST /v1/keys         (courseId, term, subject…)
   …o los alumnos se auto-inscriben         POST /v1/enrollments   (courseId + joinCode)
4. Los alumnos chatean                      POST /v1/chat/completions
5. Alguien renueva                          POST /v1/keys/{id}/extend   (≤ term.endsAt)
6. Alguien filtra una key                   POST /v1/keys/{id}/rotate  → el secreto viejo muere
7. Se filtra la key de la cátedra (PEER)    DELETE /v1/keys/{id}        → 204
8. Termina la cursada                       GET /v1/keys?status=EXPIRED → todas vencidas
```

### 6.4 Cómo se refleja en Kitoru

| Dónde | Qué |
|---|---|
| `Course.term` (dominio, ya existe) | El valor `"2026-2"` que el docente escribe. Se manda como `term` al emitir keys y al sincronizar la cátedra. |
| `ModelPolicy.apiKey` | La key que el alumno tiene configurada. La app del alumno **no** guarda el `kid` aparte: lo deriva parseando el token (`ktor_<kid>_<secret>`) → sirve para llamar a `GET /v1/keys/me` y para `POST /v1/keys/{id}/extend`. |
| Chip de la app del alumno | "🔑 Vence el 31/12/2026 · Cursada 2026-2" + botón **Renovar** (si `canSelfRenew`). |
| Chip de la app del docente | En la tabla de claves: estado (`VIGENTE` / `PAUSADA` / `VENCIDA` / `REVOCADA`), alumnos, cuota consumida, y el botón **Renovar** / **Rotar** / **Revocar**. |
| `ApiKeyGrant` (dominio, `05-config-modelo.md` §1.5) | Se mapea 1:1 con la respuesta de `GET /v1/keys/{id}`: `id`→`serverKeyId`, `key`→`key` (sólo al emitir), `keyLast4`→`keyLast4`, `issuedAt`→`createdAt`, `expiresAt`→`expiresAt`, `revoked`→`status == REVOKED`, `masked`→`true` siempre salvo el momento de emisión. |

Nuevo dato en el dominio (propuesto):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ServerKey.kt
@Serializable
data class ServerKeyInfo(
    val id: String = "",              // kid
    val status: String = "ACTIVE",    // ACTIVE | SUSPENDED | REVOKED | EXPIRED
    val courseId: String = "",
    val term: String = "",
    val termEndsAt: Long = 0L,
    val expiresAt: Long = 0L,
    val renewals: Int = 0,
    val canSelfRenew: Boolean = false,
    val tokensToday: Long = 0L,
    val tokensTodayLimit: Long = 0L,
    val tokensTodayResetsAt: Long = 0L,
)
```

---

## 7. Límites, cuotas y errores

### 7.1 Los tres niveles de cuota

| Nivel | Contadores | Dónde se cuentan |
|---|---|---|
| **Por key** | `requestsPerMinute`, `tokensPerDay`, `maxConcurrentRequests`, `maxTokensPerRequest` | Contadores en memoria del servidor, indexados por `kid`. |
| **Por alumno** (`subject`) | `tokensPerDay`, `requestsPerDay` | Indexados por `(courseId, term, subject.id)`. Un alumno con varias keys no multiplica su cuota. |
| **Por cátedra** | `tokensPerDay`, `tokensPerTerm`, `requestsPerMinute` (agregado), `maxConcurrentRequests` | Indexados por `(courseId, term)`. |

Defaults del servidor (overridables por la política de la cátedra):

```json
{
  "requestsPerMinute": 20,
  "requestsPerDay": 500,
  "tokensPerDay": 200000,
  "maxConcurrentRequests": 2,
  "maxTokensPerRequest": 4096
}
```

### 7.2 Semántica de los contadores

- **Ventana del minuto (RPM)**: ventana **deslizante** de 60 s. Al superarla →
  `429 rate_limited` con `Retry-After: <segundos>` (ceil de lo que falta para
  que se libere la ventana).
- **Contador diario (TPD)**: cubeta por **día UTC** (00:00–23:59:59 UTC). Se
  reinicia a medianoche UTC. La respuesta dice cuándo: `tokensTodayResetsAt`.
  (Documentar la diferencia de huso con el docente: "se renueva a las 00:00 UTC".)
- **Concurrencia**: contador de peticiones en vuelo por key. Si se supera →
  `429 concurrency_limit` con `Retry-After: 2`.
- Los contadores viven **en memoria** (se pierden al reiniciar el servidor). Es
  aceptable: son controles de abuso de primer nivel, no facturación. Si la institución quiere
  persistencia, se persisten en el almacén local (mismo archivo de datos).

### 7.3 Efecto de los límites sobre la respuesta

- `maxTokensPerRequest` superado → el `max_tokens` se **recorta** al tope (no es
  error) + cabecera `X-Kitoru-Applied-Max-Tokens`.
- `tokensPerDay` superado → `429 quota_exceeded` (terminal hasta el reset).
- `requestsPerMinute` superado → `429 rate_limited` (reintentable).

### 7.4 Formato de error y el caso del 429

**Todos** los errores (de inferencia y de gestión) usan el mismo cuerpo:

```json
{
  "apiVersion": 1,
  "error": {
    "code": "quota_exceeded",
    "message": "Alcanzaste la cuota diaria de la cursada 2026-2 (200.000 tokens). Se renueva el 01/03/2026 a las 00:00 UTC.",
    "type": "insufficient_quota",
    "param": null,
    "retryAfter": null,
    "details": { "scope": "KEY", "limit": 200000, "used": 200431, "resetsAt": 1772409600000 }
  }
}
```

`message` está **en español y listo para mostrar** al alumno: la app lo muestra
tal cual (con un `hint` propio si hace falta).

**El 429 se desdobla en dos `code`s**, y esto es lo que permite a la app
distinguir "cuota agotada" de "servidor caído":

| Situación | HTTP | `error.code` | `type` | `Retry-After` | En la app (con el cambio de §14.3) |
|---|---|---|---|---|---|
| Se pasó el ritmo (20 req/min) | **429** | `rate_limited` | `rate_limit_error` | `12` | `SIN_CUOTA`, **reintentable** → "Vas muy rápido, probá en 12 s." |
| Se agotó la cuota del día | **429** | `quota_exceeded` | `insufficient_quota` | — | `SIN_CUOTA`, **no reintentable** → "Alcanzaste la cuota diaria. Se renueva el 01/03." |
| El modelo no está cargado / se está descargando | **503** | `model_not_ready` | `server_error` | `30` | `MODELO_NO_DISPONIBLE`, reintentable → "El servidor está preparando el modelo." |
| El backend (llama-server) se cayó | **502** / **503** | `upstream_unavailable` | `server_error` | `5` | `MODELO_NO_DISPONIBLE`, reintentable → **"servidor caído"**, ≠ cuota. |
| La key venció | **401** | `key_expired` | `authentication_error` | — | `CLAVE_INVALIDA` → "Tu clave venció (31/12/2026). Renovala." |
| La key fue revocada | **401** | `key_revoked` | `authentication_error` | — | `CLAVE_INVALIDA` → "Esta clave fue revocada." |
| Contexto agotado | **400** | `context_length_exceeded` | `invalid_request_error` | — | `CONTEXTO_AGOTADO` |
| Todo lo demás | **4xx/5xx** | — | — | — | según `LlmException.fromHttp` |

> **Por qué 429 y no 402/403.** `LlmException.fromHttp` ya mapea **429 → `SIN_CUOTA`**
> y **5xx → `MODELO_NO_DISPONIBLE`**. Con esos dos status, la distinción
> "cuota agotada" vs "servidor caído" ya existe. Lo que falta es poder
> distinguir **dentro** del 429 (ritmo vs cuota diaria) y poder decir
> "no reintentable". Por eso el `code` del cuerpo es el que decide, y por eso
> se propone el cambio mínimo en `:llm-api` (§14.3).

> **Regla de redacción (importante).** `LlmException.fromHttp` clasifica un
> **400** como `CONTEXTO_AGOTADO` si el cuerpo contiene la regex
> `context|ctx_size|kv cache|kv_cache|tokens` (ver `llm-api/.../LlmErrors.kt`).
> Por lo tanto **ningún mensaje de error 400 del servidor puede usar la palabra
> "tokens"** salvo que el error *sea* de contexto. Ejemplos:
> - correcto: `"message": "El límite de longitud de la respuesta es 4096."`
> - incorrecto: `"message": "max_tokens (9999) supera el límite permitido."` ←
>   se clasificaría como contexto agotado y la app mostraría un mensaje
>   engañoso.
> El equipo que implemente el servidor debe tener esta tabla a mano.

### 7.5 Tabla de errores del servidor (resumen)

| HTTP | `error.code` | Cuándo | Reintentable |
|---|---|---|---|
| 400 | `invalid_request` | Body malformado o campo faltante. | No |
| 400 | `expires_after_term` | La expiración pasa del fin de cursada. | No |
| 400 | `context_length_exceeded` | El prompt no entra en la ventana. | Sí (recortar) |
| 400 | `unsupported_api_version` | `X-Kitoru-Api-Version` desconocida. | No |
| 401 | `missing_credentials` | Sin `Authorization`. | No |
| 401 | `invalid_token` | Token mal formado o hash distinto. | No |
| 401 | `key_expired` | `now ≥ expiresAt`. | No (renovar) |
| 401 | `key_revoked` | Revocada. | No |
| 401 | `key_suspended` | Pausada. | No |
| 402 | — | **No se usa.** | — |
| 403 | `insufficient_scope` | La credencial no puede hacer esto. | No |
| 403 | `course_forbidden` | La cátedra está fuera del alcance. | No |
| 403 | `model_not_allowed` | Modelo fuera de la política. | No |
| 403 | `tool_not_allowed` | Herramienta deshabilitada por la cátedra. | No |
| 403 | `self_renewal_not_allowed` | El alumno no puede renovar. | No |
| 404 | `model_not_found` | Alias o modelo inexistente. | No |
| 404 | `course_not_found` / `term_not_found` / `key_not_found` | Entidad inexistente. | No |
| 409 | `key_limit_reached` | Demasiadas keys vivas para el curso. | No |
| 409 | `term_closed` | La cursada no acepta emisiones. | No |
| 409 | `key_revoked` | No se renueva una key revocada. | No |
| 429 | `rate_limited` | RPM/concurrencia. | **Sí** |
| 429 | `quota_exceeded` | TPD/por alumno/por cátedra. | **No** |
| 500 | `internal_error` | Bug del servidor. | Sí |
| 502 | `upstream_unavailable` | El `llama-server` no responde. | Sí |
| 503 | `model_not_ready` | Modelo descargando/cargando. | Sí |
| 503 | `server_overloaded` | Cola de requests llena. | Sí |
| 504 | `upstream_timeout` | El modelo tardó demasiado. | Sí |

---

## 8. Reenvío de chats al docente

### 8.1 Push o pull: **pull**

**Decisión: el docente consulta al Kitoru-Server.** Razones:

1. La app del docente es de escritorio y muchas veces está detrás de NAT o de un
   firewall institucional: no puede "recibir" conexiones entrantes.
2. El servidor tiene la dirección pública y la credencial: el flujo natural es
   "el cliente trae los datos".
3. El lote (batch) se puede paginar, reanudar con un cursor y reintentar sin
   duplicados.
4. Si el docente está 3 días sin abrir la app, igual recibe todo el histórico
   (con un cursor no se pierde nada).

El push queda disponible como **opción v1.1** (`POST /v1/webhooks`, §8.6) con el
**mismo payload**, para instituciones que quieran tiempo real.

### 8.2 El flujo completo

```
ALUMNO (Kitoru)                         KITORU-SERVER                      DOCENTE (Kitoru)
────────────────                        ─────────────                      ───────────────
                                        ┌──────────────────────┐
POST /v1/chat/completions ─────────────▶│ autentica la key     │
  Authorization: Bearer ktor_K…         │ aplica política       │
  X-Kitoru-Student-Id: enroll_9f3       │ aplica cuotas         │
  X-Kitoru-Thread-Id: thr_7a2           │ llama a llama-server  │
                                        │ registra el evento    │
◀────────── SSE (texto + usage) ───────┤ en el outbox (seq=N)  │
                                        └──────────────────────┘
                                                                     GET /v1/chats?courseId=…&afterSeq=N-1
                                                                 ◀──────────────────────────────────────
                                                                ◀────── {"events":[…], "nextSeq": N}
                                        (guarda en TeacherChatRepository)
```

El **outbox** es una cola append-only en el almacén local del servidor, con un
`seq` monotónico creciente. Retención por defecto: **90 días** (configurable
`--chat-retention-days`), después los eventos se purgan (y el `seq` no se
reinicia: los cursores viejos siguen siendo válidos, simplemente no devuelven
nada).

### 8.3 `GET /v1/chats` — traer eventos

```http
GET /v1/chats?courseId=IA-2026-1&term=2026-2&afterSeq=8123&limit=200 HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
```

| Parámetro | Por defecto | Significado |
|---|---|---|
| `courseId` | — | **Obligatorio** salvo token `A…`. Debe estar en el `scope` del token. |
| `term` | el activo | Filtra por cursada. |
| `afterSeq` | `0` | Devuelve los eventos con `seq > afterSeq`. |
| `limit` | `100` | Máximo 500. |
| `subjectId` | — | Filtra por alumno. |
| `includeText` | `true` | `false` → sólo métricas, sin texto (ver §8.4). |

**Respuesta `200`:**

```json
{
  "apiVersion": 1,
  "events": [
    {
      "seq": 8124,
      "id": "evt_01J9Z8Y2QK4M7fQ",
      "courseId": "IA-2026-1",
      "term": "2026-2",
      "keyId": "K01J9Z8Y2QK4M",
      "subject": { "type": "STUDENT", "id": "enroll_9f3", "displayName": "Ana Ruiz" },
      "threadId": "thr_7a2",
      "createdAt": 1772000123000,
      "model": { "requested": "alias:catedra/rapido", "resolved": "Qwen3-8B-Instruct" },
      "usage": { "promptTokens": 118, "completionTokens": 412, "totalTokens": 530 },
      "latencyMs": 2100,
      "finishReason": "stop",
      "visibility": "FULL",
      "textAvailable": true,
      "messages": [
        { "id": "m_01J9Z8Y2QK4M8fR", "role": "user", "text": "¿Me explicás SVM con margen?",
          "createdAt": 1772000100000, "tokensIn": 118 },
        { "id": "m_01J9Z8Y2QK4M8fS", "role": "assistant", "text": "El margen es la distancia…",
          "createdAt": 1772000121000, "tokensOut": 412, "latencyMs": 2100,
          "sources": ["Apuntes-01.pdf"], "toolCalls": [] }
      ]
    }
  ],
  "nextSeq": 8125,
  "hasMore": false
}
```

Cuando **no** hay texto disponible (alumno `METRICS_ONLY` o `NONE`), el evento
**no trae `messages`**: trae `metrics` y `textAvailable: false`:

```json
{
  "seq": 8125,
  "id": "evt_01J9Z8Y2QK4M8fT",
  "subject": { "type": "STUDENT", "id": "enroll_9f4", "displayName": "Bruno Díaz" },
  "threadId": "thr_7b1",
  "createdAt": 1772000200000,
  "visibility": "METRICS_ONLY",
  "textAvailable": false,
  "messages": [],
  "metrics": {
    "messages": 2, "requests": 2, "promptTokens": 240, "completionTokens": 880,
    "totalTokens": 1120, "latencyMsAvg": 2450, "latencyMsMax": 3100,
    "byRole": { "user": 1, "assistant": 1 },
    "sources": ["Apuntes-02.pdf"], "toolCalls": [{ "name": "get_time", "ok": true, "durationMs": 12 }]
  }
}
```

Esto es exactamente lo que pide `teacher/07-chats.md` §3.3: la ficha por mensaje
**sin texto** y el panel de métricas.

### 8.4 La visibilidad se aplica en el servidor

El servidor **nunca** entrega texto de un alumno que no puede ver el docente.
Resolución:

```
visibilidadEfectiva = min(visibilidadDelRosterDelDocente, visibilidadDeclaradaPorElAlumno)
```

| `ChatVisibility` | Texto | Métricas |
|---|---|---|
| `FULL` | Sí | Sí |
| `METRICS_ONLY` | **No** | Sí |
| `NONE` | **No** | **No** |

- El **roster del docente** (padrón de la cátedra) es lo que el docente empuja al
  servidor (`PUT /v1/courses/{courseId}/enrollments`). Es la autoridad.
- La **declaración del alumno** (su propia elección en su app, `NONE` = "no
  quiero que me leas") se envía en `X-Kitoru-Visibility` o en
  `POST /v1/usage/report`. **Sólo puede reducir**, nunca ampliar.
- Si el alumno nunca habló con el servidor (p. ej. usa IA local y no tiene key),
  el servidor aplica sólo el roster del docente — pero no hay texto que
  entregar de todas formas.
- El recorte ocurre **en el servidor, antes de serializar** (`messages: []` +
  `textAvailable: false`). El cliente del docente además no pide texto
  (`includeText=false`) y la app aplica `ChatPolicy.canReadText` al leer
  (`teacher/07-chats.md` §3.1): **dos capas**.

### 8.5 `GET /v1/chats/{id}` — un evento puntual

Devuelve un evento con el mismo formato. `404 chat_event_not_found` si no
existe o si la visibilidad no lo permite (en ese caso, `403` en vez de `404`,
que es más honesto para el cliente institucional).

### 8.6 Push opcional (v1.1): `POST /v1/webhooks`

```http
POST /v1/webhooks HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…
Content-Type: application/json

{
  "url": "https://docente.universidad.edu/kitoru/ingest",
  "courseIds": ["IA-2026-1"],
  "secret": "whsec_…",          // para firmar el payload
  "minIntervalMs": 5000,
  "active": true
}
```

El servidor hace `POST` a esa URL con el mismo `{"events":[…]}` y la firma
`X-Kitoru-Signature: sha256=<hmac>` (HMAC-SHA256 del cuerpo con `secret`).
Reintentos con backoff (1 s, 5 s, 30 s, 2 min, 10 min) y desactivación
automática tras 10 fallos seguidos.

### 8.7 Enriquecimiento opcional del cliente (fase 2)

Las **citas verificadas** y el detalle de herramientas los calcula el
`CitationVerifier` de Kitoru (en el cliente, `:backend`), no el servidor. Si el
docente quiere verlos, el cliente puede reportarlos:

```http
POST /v1/chats/annotations HTTP/1.1
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…
```

```json
{
  "courseId": "IA-2026-1",
  "threadId": "thr_7a2",
  "clientMessageId": "m_01J9Z8Y2QK4M8fS",
  "citations": { "verified": 2, "unsupported": 1, "unknown": 0 },
  "sources": ["Apuntes-01.pdf"],
  "toolCalls": [{ "name": "get_time", "args": {}, "ok": true, "durationMs": 12 }],
  "latencyMs": 2100
}
```

El servidor lo **mergea** en el evento correspondiente (por `threadId` +
`clientMessageId`). Es **opcional**: si el cliente no lo manda, el evento tiene
`citations: null` y la UI muestra "citas: —". El servidor **no valida** estos
datos (vienen del cliente); sí los registra como `source: "client"` para que la
UI pueda distinguir "medido por el servidor" de "reportado por el cliente".

---

## 9. IA local: qué ve y qué no ve el docente

Este es el punto que más se presta a confusiones, así que va explícito.

### 9.1 La regla

> El Kitoru-Server **sólo** ve lo que pasa por su puerto de inferencia.
> Si un alumno usa un modelo **local** (llama.cpp en su máquina, Ollama, LM
> Studio), **el servidor no ve sus chats**: no ve las preguntas, ni las
> respuestas, ni cuántos mensajes hubo.
>
> Lo único que el servidor puede afirmar con **evidencia dura** es:
> *"este alumno no hizo ninguna inferencia con la IA de la institución"*.

### 9.2 Los estados que el servidor reporta

El servidor cruza (a) el tráfico de inferencia que vio, (b) el padrón de la
cátedra que le empujó el docente, y (c) lo que el alumno **declara**:

```jsonc
{
  "apiVersion": 1,
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "generatedAt": 1772500000000,
  "rosterSize": 34,
  "students": [
    {
      "subject": { "type": "STUDENT", "id": "enroll_9f3", "displayName": "Ana Ruiz" },
      "institutionAi": {
        "status": "USED",                  // USED | NEVER_USED | UNKNOWN
        "requests": 42,
        "tokens": 51230,
        "firstSeenAt": 1772000123000,
        "lastSeenAt": 1772500111000,
        "evidence": "SERVER_LOGGED"        // evidencia dura: el servidor lo vio
      },
      "localAi": {
        "status": "DECLARED",              // DECLARED | NOT_DECLARED
        "declaredAt": 1772500200000,
        "requests": 12,
        "tokens": 3400,
        "model": "local:qwen3-8b-instruct-q4",
        "evidence": "SELF_REPORTED"        // evidencia blanda: lo dijo el alumno
      },
      "coverage": "MIXED"                   // REMOTE_ONLY | LOCAL_ONLY | MIXED | NONE_REPORTED
    },
    {
      "subject": { "type": "STUDENT", "id": "enroll_9f4", "displayName": "Bruno Díaz" },
      "institutionAi": { "status": "NEVER_USED", "requests": 0, "tokens": 0, "evidence": "SERVER_LOGGED" },
      "localAi": { "status": "DECLARED", "declaredAt": 1772501000000, "requests": 30, "tokens": 91000,
                   "model": "local:phi4-mini", "evidence": "SELF_REPORTED" },
      "coverage": "LOCAL_ONLY"
    },
    {
      "subject": { "type": "STUDENT", "id": "enroll_9f5", "displayName": "Carla Sosa" },
      "institutionAi": { "status": "NEVER_USED", "requests": 0, "tokens": 0, "evidence": "SERVER_LOGGED" },
      "localAi": { "status": "NOT_DECLARED", "declaredAt": null },
      "coverage": "NONE_REPORTED"
    }
  ],
  "summary": {
    "usedInstitutionAi": 31,
    "neverUsedInstitutionAi": 3,
    "declaredLocalAi": 2,
    "noData": 1
  }
}
```

### 9.3 Los cuatro valores de `coverage` y qué significan

| `coverage` | Significado | Evidencia | Chip en la UI del docente |
|---|---|---|---|
| `REMOTE_ONLY` | Sólo hubo tráfico con la IA de la institución. No hay declaración de uso local. | Dura (servidor) | 🟢 **"Usó la IA de la institución"** |
| `MIXED` | Hubo tráfico con la IA de la institución **y** el alumno declara usar IA local. | Mixta | 🟡 **"Usó la IA de la institución y declaró IA local"** |
| `LOCAL_ONLY` | No hubo tráfico con la IA de la institución, pero el alumno **declara** usar IA local. | Blanda (declaración) | 🟠 **"Declaró que usa IA local (no usó la IA de la institución)"** |
| `NONE_REPORTED` | No hay tráfico ni declaración. | — | ⚪ **"Sin datos"** — *no significa que no haya usado IA* |

> **Prohibición explícita.** El servidor **nunca** emite un estado
> "NO_USED_IA" (es decir, "no usó IA"). El estado `NEVER_USED` de
> `institutionAi` significa **siempre** "no usó **la IA de la institución**", y
> la UI del docente debe decirlo con esas palabras. Un alumno que usó un
> modelo local en su casa y nunca se conectó al servidor queda en
> `NONE_REPORTED`, no en "no usó IA". Esto evita que la institución castigue
> al alumno por algo que el sistema no puede observar.

### 9.4 Cómo el alumno declara su uso local

El alumno no necesita key para usar IA local, así que **no siempre puede hablar
con el servidor**. El camino de declaración es:

```http
POST /v1/usage/report HTTP/1.1
Authorization: Bearer ktor_K01J9Z8Y2QK4M_x7Fq…      ← su key de alumno (si tiene)
Content-Type: application/json
```

```json
{
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "subject": { "type": "STUDENT", "id": "enroll_9f3", "displayName": "Ana Ruiz" },
  "mode": "LOCAL",
  "visibility": "METRICS_ONLY",
  "model": "local:qwen3-8b-instruct-q4",
  "period": { "from": 1772000000000, "to": 1772600000000 },
  "metrics": {
    "requests": 12,
    "promptTokens": 3400,
    "completionTokens": 5100,
    "totalTokens": 8500,
    "threads": 3,
    "messages": 24,
    "latencyMsAvg": 2400,
    "sourcesUsed": ["Apuntes-01.pdf"],
    "toolCalls": 2
  }
}
```

**Reglas duras:**

1. El payload **nunca contiene texto de conversaciones**: sólo métricas. El
   servidor rechaza (`400 report_contains_text`) cualquier campo de texto de
   conversación que no sea un nombre de material.
2. La declaración es **confesional y revocable**: el alumno puede mandarla
   cuando quiere (o nunca mandarla). El servidor la marca como
   `evidence: SELF_REPORTED` para siempre.
3. `mode` puede ser `LOCAL`, `REMOTE` o `MIXED`. Con `REMOTE` el servidor
   **fusiona** los contadores con los del tráfico real (que son más fiables).
4. `visibility` es la declaración del alumno sobre su propia privacidad
   (`FULL` | `METRICS_ONLY` | `NONE`). Sólo puede **reducir** (§8.4).
5. El reporte es **idempotente** por `(courseId, subject, period.from)`: repetirlo
   actualiza, no duplica.

El envío es **opt-in desde la app del alumno**, con una frase clara en la UI:

> "Podés reportar a tu cátedra que usás IA local, sin mostrar el contenido de
> tus conversaciones. Tu docente verá métricas, no texto. ¿Querés hacerlo?"

### 9.5 `GET /v1/courses/{courseId}/coverage`

Devuelve el reporte de §9.2. Es lo que consume la pestaña "Cobertura" de la app
del docente.

### 9.6 Matriz de visibilidad → lo que ve el docente

(Consistente con `CONTEXTO.md` y `teacher/07-chats.md` §3.)

| Modo del alumno | ¿El servidor vio el texto? | ¿El docente ve el texto? | ¿El docente ve métricas? |
|---|---|---|---|
| IA alojada (`REMOTE`), visibilidad `FULL` | Sí | Sí | Sí |
| IA alojada, visibilidad `METRICS_ONLY` | Sí | **No** (el servidor lo recorta) | Sí |
| IA alojada, visibilidad `NONE` | Sí | **No** | **No** |
| IA local (`LOCAL`) | **No** | No (salvo que el alumno comparta el paquete `.kitoru`, ver `07-chats.md` §8) | Sólo las que el alumno **declare** |

---

## 10. Autenticación del docente

### 10.1 El token de servicio

La credencial del docente es un **token de servicio** (`kid` empieza con `S`),
emitido por la institución (token `A…`) — normalmente una vez por cátedra y por
rol, o una vez por docente.

```http
POST /v1/admin/service-tokens HTTP/1.1
Authorization: Bearer ktor_A01J9Z8Y2QK4M_x7Fq…
Content-Type: application/json

{
  "label": "Dra. Pérez — IA 2026-2",
  "courseIds": ["IA-2026-1"],
  "roles": ["TITULAR"],
  "scopes": ["keys:issue", "keys:revoke", "chats:read", "audit:read", "audit:write"],
  "expiresAt": 1790000000000,
  "maxUses": 1
}
```

→ `201` con `token` (una sola vez) y el mismo esquema de credencial.

El docente lo pega en la app del docente (Kitoru) en un campo
**"Token de servicio del Kitoru-Server"** → queda en
`KitoruPaths.configDir/service-token.json` (archivo sensible, permisos
restrictivos, fuera del paquete de exportación `.kitoru`).

### 10.2 Scopes

| Scope | Permite |
|---|---|
| `keys:issue` | `POST /v1/keys`, `POST /v1/keys/{id}/extend`, `rotate`, `suspend`. |
| `keys:revoke` | `DELETE /v1/keys/{id}`. |
| `keys:read` | `GET /v1/keys*`. |
| `chats:read` | `GET /v1/chats*`, `GET /v1/courses/{id}/coverage`. |
| `audit:read` / `audit:write` | `GET /v1/audit` / `POST /v1/audit`. |
| `terms:read` | `GET /v1/terms*`. |
| `admin:*` | Sólo tokens `A…`. |

Un token sin scopes explícitos recibe el conjunto por defecto de un docente:
`["keys:read", "keys:issue", "chats:read", "audit:read"]`.

### 10.3 SSO institucional (opcional, v1.1)

Si la institución tiene OIDC/SAML, el servidor puede intercambiar el token de
SSO por un token de servicio de corto):

```http
POST /v1/auth/exchange HTTP/1.1
Content-Type: application/json

{ "idToken": "eyJhbGciOi…", "courseId": "IA-2026-1" }
```

→ `200 { "token": "ktor_S…", "expiresAt": … }`. El token de servicio resultante
hereda los cursos y roles del directorio institucional.

### 10.4 Qué **no** puede hacer el docente

- Emitir keys para una cátedra fuera de su `scope` → `403 course_forbidden`.
- Ver chats de otra cátedra → `403 course_forbidden`.
- Ver el **secreto** de una key (nunca está en el servidor).
- Ver los chats de un alumno con visibilidad `NONE` → no existen para él.

---

## 11. Seguridad

### 11.1 Transporte

- **HTTPS obligatorio** para todo lo que salga de la máquina del servidor.
  Dos despliegue soportados:
  1. **Terminador TLS externo** (Caddy/nginx en la misma máquina o en un
     bastion): Kitoru-Server habla HTTP en loopback y el proxy habla HTTPS.
     *Recomendado*: el certificado lo renueva Let's Encrypt solo.
  2. **TLS nativo** (`--tls cert.pem --tls-key key.pem`): Kitoru-Server hace
     TLS 1.2+ con su propio certificado (institucional o autofirmado; en este
     caso la app del alumno muestra un aviso de "certificado no confiable" con
     la huella para fijarla).
- **HTTP plano sólo en loopback** (`--allow-http-local`), y sólo para desarrollo.
  Si se detecta una petición HTTP plano en el listener de red → el servidor
  responde `403 insecure_transport` y lo registra como incidente de seguridad.

### 11.2 Almacenamiento de secretos

| Secreto | Dónde | Cómo |
|---|---|---|
| `secret` de una credencial | **Nunca** en claro en el servidor. | `SHA-256(pepper || secret)`. |
| `pepper` | `data-dir/secrets/pepper.bin` | 32 bytes, cifrados en reposo con DPAPI (Windows) o con clave del sistema. |
| Token de servicio en la app del docente | `configDir/service-token.json` | Permisos 600 (desktop). No viaja en los paquetes `.kitoru`. |
| Key del alumno en la app del alumno | `ModelPolicy.apiKey` | Igual que hoy; se documenta como sensible. |
| Certificados TLS | `data-dir/secrets/` | Permisos 600. |

- La respuesta de emisión es la **única** vez que el secreto sale del servidor.
  Después sólo `tokenLast4`.
- Los logs **nunca** incluyen el `Authorization` completo: se loguea el `kid`
  y los últimos 4.

### 11.3 Rotación

| Secreto | Política | Cómo se rota |
|---|---|---|
| `secret` de una key de alumno | Al filtrarse, o cada cursada | `POST /v1/keys/{id}/rotate` (§5.6) |
| Token de servicio (docente) | Cada **180 días**, o al salir el docente | `POST /v1/admin/service-tokens/{id}/rotate` → nuevo token; el viejo sigue válido `graceSeconds` (por defecto 3600) |
| Token de administración | Cada **90 días** | Igual, con aviso al owner |
| `pepper` | Cada **180 días** o tras incidente | `POST /v1/admin/rotate-pepper` re-hashea todas las credenciales (operación en background; el servidor acepta requests durante el re-hash con el pepper viejo y nuevo a la vez) |
| Certificado TLS | Automática (Let's Encrypt) o manual | — |

### 11.4 Auditoría de accesos

**Todo** request se registra en una tabla `audit_log` append-only (y en un
`audit.jsonl` con rotación por tamaño):

```json
{
  "at": 1772000123456,
  "actor": { "type": "STUDENT_KEY", "id": "K01J9Z8Y2QK4M", "subjectId": "enroll_9f3" },
  "action": "chat.completion",
  "method": "POST",
  "path": "/v1/chat/completions",
  "status": 200,
  "durationMs": 2134,
  "ip": "10.0.3.17",
  "userAgent": "kitoru/0.3.0 (desktop)",
  "courseId": "IA-2026-1",
  "term": "2026-2",
  "requestId": "req_01J9…",
  "detail": { "model": "alias:catedra/rapido", "resolvedModel": "Qwen3-8B-Instruct",
              "tokens": 530, "finishReason": "stop" }
}
```

Acciones registradas (además de las de request):

| `action` | Cuándo |
|---|---|
| `key.issued` | Se emite una key (actor: `S…`/`A…`). |
| `key.extended` | Se renueva (actor y `from`/`to` del `expiresAt`). |
| `key.rotated` | Se rota el secreto. |
| `key.revoked` | Se revoca. |
| `key.suspended` / `key.resumed` | — |
| `chats.viewed` | El docente **lee** chats: qué alumno, cuántos mensajes, rango de fechas. Es la trazabilidad que pide `07-chats.md` §6 (el `AccessLog` del docente). |
| `chats.exported` | El docente exporta. |
| `audit.viewed` | Alguien consulta la auditoría. |
| `coverage.viewed` | El docente mira la cobertura. |
| `usage.reported` | El alumno declara uso local. |

La app del docente **empuja** sus entradas de `AccessLog` al servidor con
`POST /v1/audit` (para que el rastro no dependa de un archivo local que el
docente pueda borrar — el problema que `07-chats.md` §6.4 señala):

```http
POST /v1/audit HTTP/1.1
Authorization: Bearer ktor_S01J9Z8Y2QK4M_x7Fq…

{ "entries": [ { "at": 1772000123000, "kind": "VIEW_TEXT", "studentId": "enroll_9f3",
                 "threadId": "thr_7a2", "detail": "×3" } ] }
```

### 11.5 Si se filtra una key — runbook

1. **Revocar** de inmediato: `DELETE /v1/keys/{id}` → `204`. (Effective
   inmediato: denylist en memoria.)
2. **Rotar** si la key es de la cátedra y no se puede revocar sin cortar el
   servicio: `POST /v1/keys/{id}/rotate` → los alumnos tienen que pegar la key
   nueva (o el docente la reparte). Con `graceSeconds: 0` el corte es limpio.
3. **Revisar el uso sospechoso**: `GET /v1/keys/{id}/usage` → `lastUsedIp`,
   `distinctSubjects`, `requestsToday`. `GET /v1/audit?keyId=…` para el detalle.
4. **Pausar la cátedra** si el vector de ataque es el curso:
   `POST /v1/courses/IA-2026-1/pause` → las inferencias dan
   `503 course_paused` y las keys no revocadas siguen “vivas” pero inoperantes.
5. **Comunicar** a la cátedra y, si procede, **rotar el pepper**
   (`POST /v1/admin/rotate-pepper`) y **cambiar la contraseña del admin**.
6. **Registrar el incidente** en la auditoría (`action: "security.key_leaked"`).

El servidor **no** puede "dar de baja" un `kid` que ya se filtró si el atacante
lo usa contra el mismo servidor (el `kid` es público): por eso la rotación de
secretos es la única defensa real. El diseño lo hace barato (una llamada).

### 11.6 Límites de exposición

- El **padrón de alumnos** (nombres) es dato sensible: la API de chats y de
  cobertura sólo lo devuelve a tokens con `chats:read` y para cátedras del
  `scope`.
- Los **nombres de material** que aparecen en `sources` son de la cátedra (no
  del alumno); se exponen con el mismo criterio que el resto.
- Los `args` de herramientas se **truncan a 200 caracteres** (misma regla que
  `07-chats.md` §3.3).

---

## 12. Versionado

### 12.1 Reglas

1. **El prefijo `/v1`** versiona **toda** la API (inferencia, keys, docente).
   Cambiar la API breaking → `/v2`, y `/v1` sigue sirviendo.
2. **Toda respuesta JSON** lleva `"apiVersion": 1` (entero). Es la convención
   de Silvy (`{"apiVersion": 1, "ok": true, "result": {…}}`) y se mantiene aquí
   aunque el cuerpo sea OpenAI-compatible (el cliente OpenAI ignora campos
   extra: `Json { ignoreUnknownKeys = true }`).
3. **Los campos nuevos se agregan al final** y **siempre son opcionales** dentro
   de `/v1` (igual que en `McpProtocol`: los campos desconocidos se ignoran).
4. El cliente puede mandar `X-Kitoru-Api-Version: 1`. Si no lo manda, se asume
   `1`. Si manda una versión que el servidor no soporta → `400 unsupported_api_version`
   con la lista de versiones soportadas.
5. El servidor responde siempre `X-Kitoru-Api-Version: 1` (la negociada).

### 12.2 Política de deprecación

| Situación | Qué hace el servidor | Anuncio |
|---|---|---|
| Campo nuevo opcional | Se agrega; `/v1` no cambia de número. | — |
| Campo viejo que se quiere quitar | Se mantiene **6 meses** y se marca `deprecated: true` en el esquema. | `Deprecation: true` + `Sunset: <RFC1123>` en las cabeceras de los endpoints afectados. |
| Endpoint viejo que se quiere quitar | Idem, 6 meses. El endpoint devuelve `200` con un aviso, no `404` de golpe. | cabeceras `Deprecation` / `Sunset` + aviso en `GET /v1/version`. |
| Cambio incompatible | Se abre `/v2`; `/v1` sigue **6 meses más** desde la apertura de `/v2`. | `GET /v1/version` incluye `deprecations` y `sunset`. |

```http
GET /v1/version HTTP/1.1

{
  "apiVersion": 1,
  "serverVersion": "0.4.0",
  "build": "2026-03-12T10:00:00Z",
  "apiVersions": [1],
  "deprecations": [
    { "path": "/v1/keys/{id}/usage", "since": "0.4.0", "sunset": "2026-09-12",
      "replacement": "GET /v1/keys/{id} (campo usage)" }
  ],
  "model": { "id": "Qwen3-8B-Instruct", "status": "ready", "contextSize": 32768 }
}
```

---

## 13. OpenAPI (esbozo para la implementación)

> Es un **esbozo**: define paths, esquemas y ejemplos. El implementador debe
> generar el `openapi.yaml` completo (3.1) a partir de esta especificación y
> publicarlo en `GET /openapi.yaml` y `GET /docs` (Swagger UI) en el listener
> de red.

```yaml
openapi: 3.1.0
info:
  title: Kitoru-Server API
  version: "1.0.0"
  description: |
    Contrato entre la app Kitoru y el Kitoru-Server.
    - /v1/chat/completions y /v1/models: compatibles con OpenAI (los usa OpenAiCompatibleGateway sin cambios).
    - /v1/keys**: gestión de claves con token de servicio.
    - /v1/chats**, /v1/usage/report, /v1/audit**: reenvío de chats al docente.
servers:
  - url: https://ia.universidad.edu/kitoru/v1
    description: Kitoru-Server de la institución
security:
  - bearerAuth: []
tags:
  - name: inference
  - name: keys
  - name: terms
  - name: courses
  - name: chats
  - name: audit
  - name: service

paths:
  # ─────────────────────────── INFERENCIA (OpenAI-compatible) ───────────────────────────
  /chat/completions:
    post:
      tags: [inference]
      summary: Chat completion compatible con OpenAI (streaming SSE o JSON)
      security: [{ bearerAuth: [] }]
      parameters:
        - { name: X-Kitoru-Student-Id, in: header, required: false, schema: { type: string },
            description: "Id de inscripción del alumno (dentro de la key)." }
        - { name: X-Kitoru-Thread-Id, in: header, required: false, schema: { type: string } }
        - { name: X-Kitoru-Course-Id, in: header, required: false, schema: { type: string } }
        - { name: X-Kitoru-Term, in: header, required: false, schema: { type: string } }
        - { name: X-Kitoru-Request-Id, in: header, required: false, schema: { type: string } }
        - { name: X-Kitoru-Visibility, in: header, required: false,
            schema: { type: string, enum: [FULL, METRICS_ONLY, NONE] } }
        - { name: X-Kitoru-Api-Version, in: header, required: false, schema: { type: integer, default: 1 } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/ChatCompletionRequest" }
            examples:
              streaming:
                value:
                  model: "alias:catedra/rapido"
                  stream: true
                  temperature: 0.1
                  max_tokens: 1024
                  messages:
                    - { role: system, content: "Sos un tutor de IA-2026-1." }
                    - { role: user, content: "¿Me explicás SVM con margen?" }
      responses:
        "200":
          description: |
            JSON si stream=false; SSE (text/event-stream) si stream=true.
            El stream cierra con `data: [DONE]`. El último chunk incluye `usage`
            (siempre, aunque el cliente no mande stream_options).
          headers:
            X-Kitoru-Request-Id: { schema: { type: string } }
            X-Kitoru-Key-Id: { schema: { type: string } }
            X-Kitoru-Resolved-Model: { schema: { type: string } }
            X-Kitoru-Quota-Tokens-Remaining: { schema: { type: integer } }
            X-Kitoru-Quota-Tokens-Reset: { schema: { type: integer } }
          content:
            text/event-stream:
              schema: { type: string }
              examples:
                finalConUsage:
                  value: |
                    data: {"id":"chatcmpl-01J9","object":"chat.completion.chunk","created":1772000124,"model":"Qwen3-8B-Instruct","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":118,"completion_tokens":412,"total_tokens":530}}

                    data: [DONE]

        "400": { $ref: "#/components/responses/Error400" }
        "401": { $ref: "#/components/responses/Error401" }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }
        "429": { $ref: "#/components/responses/Error429" }
        "502": { $ref: "#/components/responses/Error5xx" }
        "503": { $ref: "#/components/responses/Error5xx" }

  /models:
    get:
      tags: [inference]
      summary: Modelos y alias disponibles (OpenAI-compatible)
      responses:
        "200":
          description: "Lista filtrada por la credencial."
          content:
            application/json:
              schema:
                type: object
                required: [object, data]
                properties:
                  apiVersion: { type: integer, const: 1 }
                  object: { type: string, const: list }
                  data:
                    type: array
                    items: { $ref: "#/components/schemas/ModelEntry" }
              examples:
                alias:
                  value:
                    apiVersion: 1
                    object: list
                    data:
                      - { id: "Qwen3-8B-Instruct", object: model, owned_by: institution }
                      - id: "alias:catedra/rapido"
                        object: model
                        owned_by: "course:IA-2026-1"
                        kitoru:
                          kind: alias
                          scope: course
                          resolvesTo: "Qwen3-8B-Instruct"
                          label: Rápido
        "401": { $ref: "#/components/responses/Error401" }

  # ─────────────────────────── KEYS ───────────────────────────
  /keys:
    post:
      tags: [keys]
      summary: Emitir una API key (scope keys:issue)
      description: "El `token` se devuelve una sola vez. `expiresAt` por defecto = fin de la cursada."
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/KeyCreateRequest" }
            examples:
              porAlumno:
                value:
                  courseId: "IA-2026-1"
                  term: "2026-2"
                  subject: { type: STUDENT, id: enroll_9f3, displayName: "Ana Ruiz" }
                  label: "Ana Ruiz — IA 2026-2"
                  maxUses: 1
      responses:
        "201":
          description: "Key emitida."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Key" }
              examples:
                creada:
                  value:
                    apiVersion: 1
                    id: "K01J9Z8Y2QK4M"
                    token: "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"
                    tokenLast4: "J0kA"
                    type: STUDENT_KEY
                    courseId: "IA-2026-1"
                    term: "2026-2"
                    subject: { type: STUDENT, id: enroll_9f3, displayName: "Ana Ruiz" }
                    status: ACTIVE
                    createdAt: 1772000000000
                    expiresAt: 1798761600000
                    termEndsAt: 1798761600000
                    renewals: 0
                    maxUses: 1
                    limits: { requestsPerMinute: 20, tokensPerDay: 200000, maxConcurrentRequests: 2 }
        "400": { $ref: "#/components/responses/Error400" }
        "401": { $ref: "#/components/responses/Error401" }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }
        "409": { $ref: "#/components/responses/Error409" }
        "429": { $ref: "#/components/responses/Error429" }
    get:
      tags: [keys]
      summary: Listar keys
      parameters:
        - { name: courseId, in: query, schema: { type: string } }
        - { name: term, in: query, schema: { type: string } }
        - { name: status, in: query, schema: { type: string, enum: [ACTIVE, SUSPENDED, REVOKED, EXPIRED] } }
        - { name: subjectId, in: query, schema: { type: string } }
        - { name: limit, in: query, schema: { type: integer, default: 100, maximum: 200 } }
        - { name: offset, in: query, schema: { type: integer, default: 0 } }
      responses:
        "200":
          description: "Lista de keys (sin secretos)."
          content:
            application/json:
              schema:
                type: object
                properties:
                  apiVersion: { type: integer, const: 1 }
                  keys: { type: array, items: { $ref: "#/components/schemas/Key" } }
                  total: { type: integer }
                  nextOffset: { type: integer }

  /keys/me:
    get:
      tags: [keys]
      summary: Introspección de la credencial del alumno (cuota y vencimiento)
      responses:
        "200":
          description: "Estado de la key con la que se llama."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/KeySelf" }
        "401": { $ref: "#/components/responses/Error401" }

  /keys/{id}:
    parameters: [ { name: id, in: path, required: true, schema: { type: string, pattern: "^[KSA][0-9A-Z]{11}$" } } ]
    get:
      tags: [keys]
      summary: Consultar una key (sin secreto)
      responses:
        "200":
          description: "Key + contadores de uso."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Key" }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }
    delete:
      tags: [keys]
      summary: Revocar una key (efecto inmediato)
      responses:
        "204": { description: "Revocada." }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }

  /keys/{id}/extend:
    parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
    post:
      tags: [keys]
      summary: Renovar la expiración (nunca más allá del fin de la cursada)
      description: "El alumno puede renovar su propia key con su propia credencial (ver §5.5.1)."
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                expiresAt: { type: integer, description: "Epoch ms." }
                days: { type: integer, description: "Alternativa a expiresAt." }
      responses:
        "200":
          description: "Key con la nueva expiración."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Key" }
        "400": { $ref: "#/components/responses/Error400" }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }
        "409": { $ref: "#/components/responses/Error409" }

  /keys/{id}/rotate:
    parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
    post:
      tags: [keys]
      summary: Rotar el secreto (filtración) — devuelve un token nuevo
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                graceSeconds: { type: integer, default: 0 }
      responses:
        "201":
          description: "Nuevo token; el anterior vence al terminar el grace."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Key" }
        "403": { $ref: "#/components/responses/Error403" }
        "404": { $ref: "#/components/responses/Error404" }

  # ─────────────────────────── CURSADAS ───────────────────────────
  /terms:
    get:
      tags: [terms]
      summary: Listar cursadas
      responses:
        "200":
          description: "Cursadas."
          content:
            application/json:
              schema:
                type: object
                properties:
                  apiVersion: { type: integer, const: 1 }
                  terms: { type: array, items: { $ref: "#/components/schemas/Term" } }
    post:
      tags: [terms]
      summary: Crear una cursada (token A)
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/Term" }
      responses:
        "201": { description: "Cursada creada." }
        "400": { $ref: "#/components/responses/Error400" }
        "403": { $ref: "#/components/responses/Error403" }

  /terms/{id}:
    parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
    patch:
      tags: [terms]
      summary: Editar una cursada
      responses:
        "200": { description: "Cursada actualizada." }
        "409": { $ref: "#/components/responses/Error409" }

  # ─────────────────────────── CURSOS / PADRÓN ───────────────────────────
  /courses/{courseId}:
    parameters: [ { name: courseId, in: path, required: true, schema: { type: string } } ]
    put:
      tags: [courses]
      summary: Registrar/actualizar la cátedra, su política de modelo y sus alias
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/CourseUpsert" }
      responses:
        "200": { description: "Cátedra actualizada." }

  /courses/{courseId}/enrollments:
    parameters: [ { name: courseId, in: path, required: true, schema: { type: string } } ]
    put:
      tags: [courses]
      summary: Subir el padrón de la cátedra (inscripciones y visibilidad)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [enrollments]
              properties:
                enrollments:
                  type: array
                  items:
                    type: object
                    required: [id]
                    properties:
                      id: { type: string }
                      displayName: { type: string }
                      visibility: { type: string, enum: [FULL, METRICS_ONLY, NONE], default: FULL }
      responses:
        "200": { description: "Padrón actualizado." }

  /courses/{courseId}/coverage:
    parameters: [ { name: courseId, in: path, required: true, schema: { type: string } } ]
    get:
      tags: [courses]
      summary: Cobertura de uso de la IA de la institución (REMOTE vs LOCAL)
      responses:
        "200":
          description: "Reporte de cobertura."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/CoverageReport" }
              examples:
                mixto:
                  value:
                    apiVersion: 1
                    courseId: "IA-2026-1"
                    term: "2026-2"
                    students:
                      - subject: { type: STUDENT, id: enroll_9f3, displayName: "Ana Ruiz" }
                        institutionAi: { status: USED, requests: 42, tokens: 51230, evidence: SERVER_LOGGED }
                        localAi: { status: DECLARED, requests: 12, tokens: 3400, evidence: SELF_REPORTED }
                        coverage: MIXED
                      - subject: { type: STUDENT, id: enroll_9f5, displayName: "Carla Sosa" }
                        institutionAi: { status: NEVER_USED, requests: 0, tokens: 0, evidence: SERVER_LOGGED }
                        localAi: { status: NOT_DECLARED }
                        coverage: NONE_REPORTED
                    summary: { usedInstitutionAi: 31, neverUsedInstitutionAi: 3, declaredLocalAi: 2, noData: 1 }
        "403": { $ref: "#/components/responses/Error403" }

  /courses/{courseId}/pause:
    parameters: [ { name: courseId, in: path, required: true, schema: { type: string } } ]
    post:
      tags: [courses]
      summary: Pausar la cátedra (bloquea inferencia y emisiones)
      responses:
        "200": { description: "Cátedra pausada." }

  # ─────────────────────────── CHATS ───────────────────────────
  /chats:
    get:
      tags: [chats]
      summary: Traer los chats registrados (pull, paginado por seq)
      parameters:
        - { name: courseId, in: query, required: false, schema: { type: string } }
        - { name: term, in: query, schema: { type: string } }
        - { name: afterSeq, in: query, schema: { type: integer, default: 0 } }
        - { name: limit, in: query, schema: { type: integer, default: 100, maximum: 500 } }
        - { name: subjectId, in: query, schema: { type: string } }
        - { name: includeText, in: query, schema: { type: boolean, default: true } }
      responses:
        "200":
          description: "Lote de eventos."
          content:
            application/json:
              schema:
                type: object
                properties:
                  apiVersion: { type: integer, const: 1 }
                  events: { type: array, items: { $ref: "#/components/schemas/ChatEvent" } }
                  nextSeq: { type: integer }
                  hasMore: { type: boolean }
        "403": { $ref: "#/components/responses/Error403" }

  /chats/{id}:
    parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
    get:
      tags: [chats]
      summary: Un evento puntual
      responses:
        "200":
          description: "Evento."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/ChatEvent" }
        "404": { $ref: "#/components/responses/Error404" }

  /chats/annotations:
    post:
      tags: [chats]
      summary: Reportar métricas calculadas en el cliente (citas verificadas, tools)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [courseId, threadId, clientMessageId]
              properties:
                courseId: { type: string }
                threadId: { type: string }
                clientMessageId: { type: string }
                citations: { $ref: "#/components/schemas/CitationStats" }
                sources: { type: array, items: { type: string } }
                toolCalls: { type: array, items: { type: object } }
                latencyMs: { type: integer }
      responses:
        "202": { description: "Anotación registrada." }

  /usage/report:
    post:
      tags: [chats]
      summary: Declaración de uso de IA LOCAL (métricas, nunca texto)
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/UsageReport" }
            examples:
              local:
                value:
                  courseId: "IA-2026-1"
                  term: "2026-2"
                  subject: { type: STUDENT, id: enroll_9f3, displayName: "Ana Ruiz" }
                  mode: LOCAL
                  visibility: METRICS_ONLY
                  model: "local:qwen3-8b-instruct-q4"
                  period: { from: 1772000000000, to: 1772600000000 }
                  metrics: { requests: 12, promptTokens: 3400, completionTokens: 5100, threads: 3 }
      responses:
        "202": { description: "Reporte registrado." }
        "400": { $ref: "#/components/responses/Error400" }

  # ─────────────────────────── AUDITORÍA ───────────────────────────
  /audit:
    get:
      tags: [audit]
      summary: Consultar la auditoría de accesos (scope audit:read)
      parameters:
        - { name: courseId, in: query, schema: { type: string } }
        - { name: action, in: query, schema: { type: string } }
        - { name: subjectId, in: query, schema: { type: string } }
        - { name: since, in: query, schema: { type: integer } }
        - { name: limit, in: query, schema: { type: integer, default: 100 } }
      responses:
        "200":
          description: "Entradas de auditoría."
          content:
            application/json:
              schema:
                type: object
                properties:
                  apiVersion: { type: integer, const: 1 }
                  entries: { type: array, items: { $ref: "#/components/schemas/AuditEntry" } }
                  total: { type: integer }
    post:
      tags: [audit]
      summary: La app del docente empuja su AccessLog (scope audit:write)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [entries]
              properties:
                entries: { type: array, items: { $ref: "#/components/schemas/AuditEntry" } }
      responses:
        "202": { description: "Entradas registradas." }

  # ─────────────────────────── SERVICIO ───────────────────────────
  /version:
    get:
      tags: [service]
      summary: Versión del servidor y de la API
      security: []
      responses:
        "200":
          description: "Versiones."
          content:
            application/json:
              schema: { $ref: "#/components/schemas/VersionInfo" }

  /health:
    get:
      tags: [service]
      summary: Estado del servidor y del modelo
      security: []
      responses:
        "200": { description: "OK." }
        "503": { $ref: "#/components/responses/Error5xx" }

components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: "Token de Kitoru: `ktor_<kid>_<secret>` (key de alumno, de servicio o de administración)."

  responses:
    Error400:
      description: "Petición inválida."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
          examples:
            contexto:
              value: { apiVersion: 1, error: { code: context_length_exceeded, message: "El contexto del modelo se agotó. Probá con menos material.", type: invalid_request_error } }
    Error401:
      description: "Credencial ausente, inválida, vencida o revocada."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
          examples:
            vencida:
              value: { apiVersion: 1, error: { code: key_expired, message: "Tu clave venció el 31/12/2026. Pedí una renovación.", type: authentication_error } }
    Error403:
      description: "Sin permisos para esta operación o esta cátedra."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
    Error404:
      description: "Recurso inexistente."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
    Error409:
      description: "Conflicto de estado."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
    Error429:
      description: |
        Límite alcanzado. `error.code` distingue:
        - `rate_limited` → se pasó el ritmo (reintentable; hay `Retry-After`).
        - `quota_exceeded` → se agotó la cuota (no reintentable hasta el reset).
      headers:
        Retry-After: { schema: { type: integer }, description: "Segundos a esperar (sólo rate_limited)." }
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
          examples:
            cuotaDiaria:
              value:
                apiVersion: 1
                error:
                  code: quota_exceeded
                  message: "Alcanzaste la cuota diaria de la cursada 2026-2 (200.000 tokens). Se renueva el 01/03/2026 a las 00:00 UTC."
                  type: insufficient_quota
                  details: { scope: KEY, limit: 200000, used: 200431, resetsAt: 1772409600000 }
            ritmo:
              value:
                apiVersion: 1
                error:
                  code: rate_limited
                  message: "Vas muy rápido. Podés hacer 20 peticiones por minuto; probá en 12 s."
                  type: rate_limit_error
                  retryAfter: 12
    Error5xx:
      description: "Fallo del servidor o del modelo (distinto de cuota)."
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorBody" }
          examples:
            modelo:
              value: { apiVersion: 1, error: { code: model_not_ready, message: "El Kitoru-Server todavía está preparando el modelo. Probá en unos minutos.", type: server_error } }

  schemas:
    ErrorBody:
      type: object
      required: [apiVersion, error]
      properties:
        apiVersion: { type: integer, const: 1 }
        error:
          type: object
          required: [code, message]
          properties:
            code: { type: string, description: "Código estable, en snake_case." }
            message: { type: string, description: "Mensaje en español, listo para mostrar al usuario." }
            type: { type: string, description: "Familia estilo OpenAI (invalid_request_error, authentication_error, insufficient_quota, rate_limit_error, server_error)." }
            param: { type: [string, "null"] }
            retryAfter: { type: [integer, "null"], description: "Segundos a esperar." }
            details: { type: object, additionalProperties: true }

    ChatCompletionRequest:
      type: object
      required: [model, messages]
      properties:
        model: { type: string, description: "Id concreto o alias (`alias:catedra/rapido`)." }
        messages: { type: array, items: { type: object } }
        stream: { type: boolean, default: false }
        temperature: { type: number }
        max_tokens: { type: integer }
        reasoning_effort: { type: string, enum: [off, low, medium, high] }
        tools: { type: array, items: { type: object } }
        tool_choice: { type: [string, object] }
        user: { type: string }

    ModelEntry:
      type: object
      required: [id, object]
      properties:
        id: { type: string }
        object: { type: string, const: model }
        created: { type: integer }
        owned_by: { type: string }
        kitoru:
          type: object
          properties:
            kind: { type: string, enum: [model, alias] }
            scope: { type: string, enum: [server, course] }
            resolvesTo: { type: string }
            label: { type: string }
            description: { type: string }

    KeyCreateRequest:
      type: object
      required: [courseId, term]
      properties:
        courseId: { type: string }
        term: { type: string }
        subject:
          type: object
          properties:
            type: { type: string, enum: [STUDENT, COURSE] }
            id: { type: string }
            displayName: { type: string }
        label: { type: string }
        scope: { type: array, items: { type: string, enum: [chat, tools] } }
        expiresAt: { type: [integer, "null"], description: "Epoch ms; null = fin de la cursada." }
        maxUses: { type: integer, default: 0, description: "Alumnos distintos; 0 = ilimitado." }
        models: { type: array, items: { type: string } }
        limits:
          type: object
          properties:
            requestsPerMinute: { type: integer }
            tokensPerDay: { type: integer }
            maxConcurrentRequests: { type: integer }
            maxTokensPerRequest: { type: integer }
        metadata: { type: object, additionalProperties: { type: string } }

    Key:
      type: object
      required: [apiVersion, id, status, courseId, term]
      properties:
        apiVersion: { type: integer, const: 1 }
        id: { type: string, description: "kid público." }
        token: { type: string, description: "Sólo en la creación/rotación: se muestra una vez." }
        tokenLast4: { type: string }
        type: { type: string, enum: [STUDENT_KEY, SERVICE_TOKEN, ADMIN_TOKEN] }
        courseId: { type: string }
        term: { type: string }
        subject: { type: object }
        label: { type: string }
        scope: { type: array, items: { type: string } }
        status: { type: string, enum: [ACTIVE, SUSPENDED, REVOKED, EXPIRED] }
        createdAt: { type: integer }
        expiresAt: { type: integer }
        termEndsAt: { type: integer }
        renewals: { type: integer }
        maxUses: { type: integer }
        usedSubjects: { type: integer }
        lastUsedAt: { type: [integer, "null"] }
        revokedAt: { type: [integer, "null"] }
        limits: { type: object }
        models: { type: array, items: { type: string } }
        usage: { $ref: "#/components/schemas/KeyUsage" }
        renewalPolicy:
          type: object
          properties:
            selfRenewable: { type: boolean }
            maxSelfRenewals: { type: integer }
            renewableUntil: { type: integer }

    KeyUsage:
      type: object
      properties:
        requestsToday: { type: integer }
        tokensToday: { type: integer }
        tokensTodayLimit: { type: integer }
        tokensTodayResetsAt: { type: integer }
        requestsTotal: { type: integer }
        tokensTotal: { type: integer }
        lastUsedAt: { type: [integer, "null"] }
        lastUsedIp: { type: string }
        distinctSubjects: { type: integer }

    KeySelf:
      allOf:
        - $ref: "#/components/schemas/Key"
        - type: object
          properties:
            canSelfRenew: { type: boolean }

    Term:
      type: object
      required: [id, startsAt, endsAt]
      properties:
        id: { type: string, description: "AAAA-N." }
        label: { type: string }
        startsAt: { type: integer }
        endsAt: { type: integer }
        enrollmentOpensAt: { type: integer }
        enrollmentClosesAt: { type: integer }
        active: { type: boolean, default: true }
        note: { type: string }

    CourseUpsert:
      type: object
      required: [courseId, term]
      properties:
        courseId: { type: string }
        name: { type: string }
        term: { type: string }
        joinCode: { type: string }
        keyPolicy:
          type: object
          properties:
            selfIssuance: { type: string, enum: [ALLOWED, TEACHER_ONLY], default: TEACHER_ONLY }
            maxKeysPerStudent: { type: integer, default: 1 }
            maxSelfRenewals: { type: integer, default: 1 }
        modelPolicy: { type: object }
        analyticsEnabled: { type: boolean, default: true }
        paused: { type: boolean, default: false }

    ChatEvent:
      type: object
      required: [apiVersion, seq, id, courseId, subject, visibility, textAvailable]
      properties:
        apiVersion: { type: integer, const: 1 }
        seq: { type: integer }
        id: { type: string }
        courseId: { type: string }
        term: { type: string }
        keyId: { type: string }
        subject:
          type: object
          properties:
            type: { type: string, enum: [STUDENT, COURSE] }
            id: { type: string }
            displayName: { type: string }
        threadId: { type: string }
        createdAt: { type: integer }
        model:
          type: object
          properties:
            requested: { type: string }
            resolved: { type: string }
        usage: { type: object }
        latencyMs: { type: integer }
        finishReason: { type: string }
        visibility: { type: string, enum: [FULL, METRICS_ONLY, NONE] }
        textAvailable: { type: boolean }
        messages: { type: array, items: { type: object } }
        metrics: { type: object }
        annotations: { type: object, description: "Datos reportados por el cliente (citas, tools)." }

    CoverageReport:
      type: object
      properties:
        apiVersion: { type: integer, const: 1 }
        courseId: { type: string }
        term: { type: string }
        generatedAt: { type: integer }
        rosterSize: { type: integer }
        students:
          type: array
          items:
            type: object
            properties:
              subject: { type: object }
              institutionAi:
                type: object
                properties:
                  status: { type: string, enum: [USED, NEVER_USED, UNKNOWN] }
                  requests: { type: integer }
                  tokens: { type: integer }
                  firstSeenAt: { type: [integer, "null"] }
                  lastSeenAt: { type: [integer, "null"] }
                  evidence: { type: string, enum: [SERVER_LOGGED] }
              localAi:
                type: object
                properties:
                  status: { type: string, enum: [DECLARED, NOT_DECLARED] }
                  declaredAt: { type: [integer, "null"] }
                  requests: { type: integer }
                  tokens: { type: integer }
                  model: { type: string }
                  evidence: { type: string, enum: [SELF_REPORTED] }
              coverage: { type: string, enum: [REMOTE_ONLY, LOCAL_ONLY, MIXED, NONE_REPORTED] }
        summary: { type: object }

    UsageReport:
      type: object
      required: [courseId, subject, mode]
      properties:
        courseId: { type: string }
        term: { type: string }
        subject:
          type: object
          required: [id]
          properties:
            type: { type: string, enum: [STUDENT] }
            id: { type: string }
            displayName: { type: string }
        mode: { type: string, enum: [LOCAL, REMOTE, MIXED] }
        visibility: { type: string, enum: [FULL, METRICS_ONLY, NONE] }
        model: { type: string }
        period:
          type: object
          properties:
            from: { type: integer }
            to: { type: integer }
        metrics:
          type: object
          description: "Sólo métricas. Nunca texto de conversaciones."
          properties:
            requests: { type: integer }
            promptTokens: { type: integer }
            completionTokens: { type: integer }
            totalTokens: { type: integer }
            threads: { type: integer }
            messages: { type: integer }
            latencyMsAvg: { type: number }
            sourcesUsed: { type: array, items: { type: string } }
            toolCalls: { type: integer }

    CitationStats:
      type: object
      properties:
        verified: { type: integer }
        unsupported: { type: integer }
        unknown: { type: integer }

    AuditEntry:
      type: object
      properties:
        at: { type: integer }
        kind: { type: string, description: "VIEW_TEXT | VIEW_METRICS | SEARCH | EXPORT | IMPORT." }
        studentId: { type: string }
        threadId: { type: string }
        detail: { type: string }

    VersionInfo:
      type: object
      properties:
        apiVersion: { type: integer, const: 1 }
        serverVersion: { type: string }
        build: { type: string }
        apiVersions: { type: array, items: { type: integer } }
        deprecations: { type: array, items: { type: object } }
        model: { type: object }
```

---

## 14. Impacto en el repo Kitoru (lo que hay que tocar del lado cliente)

> Esto **no** cambia el contrato de `/v1/chat/completions`; es el trabajo que
> Kitoru necesita para *usar* el contrato.

### 14.1 `:llm-api` — nuevo cliente de gestión

```
llm-api/src/commonMain/kotlin/llm/kitoru/KitoruServerClient.kt    # API de gestión
llm-api/src/commonMain/kotlin/llm/kitoru/KitoruToken.kt            # parseo/validación de "ktor_<kid>_<secret>"
llm-api/src/commonMain/kotlin/llm/kitoru/KitoruApiException.kt     # error propio de la API de gestión
llm-api/src/commonMain/kotlin/llm/kitoru/KitoruModels.kt            # @Serializable de las respuestas
```

```kotlin
// llm/kitoru/KitoruToken.kt
/** Token de Kitoru: "ktor_<kid>_<secret>". */
data class KitoruToken(val kid: String, val secret: String, val type: Char) {
    val isStudentKey: Boolean get() = type == 'K'
    val isServiceToken: Boolean get() = type == 'S'
    val isAdminToken: Boolean get() = type == 'A'

    companion object {
        private val RX = Regex("^ktor_([KSA][0-9A-HJKMNP-TV-Z]{11})_([A-Za-z0-9_-]{43})$")
        fun parse(raw: String): KitoruToken? {
            val m = RX.matchEntire(raw.trim()) ?: return null
            return KitoruToken(m.groupValues[1], m.groupValues[2], m.groupValues[1][0])
        }
    }
}
```

`KitoruServerClient` (reemplaza el esbozo de `05-config-modelo.md` §3.4, con la
URL **única** `/v1`):

```kotlin
class KitoruServerClient(
    private val baseUrl: String,        // "https://ia.universidad.edu/kitoru/v1"
    private val token: String,          // token de servicio del docente
    private val http: HttpClient = defaultKitoruHttpClient(),
) {
    suspend fun issueKey(req: KeyCreateRequest): ServerKeyInfo
    suspend fun getKey(id: String): ServerKeyInfo
    suspend fun listKeys(courseId: String, term: String? = null): List<ServerKeyInfo>
    suspend fun revokeKey(id: String)
    suspend fun extendKey(id: String, expiresAt: Long? = null, days: Int? = null): ServerKeyInfo
    suspend fun rotateKey(id: String, graceSeconds: Int = 0): ServerKeyInfo
    suspend fun selfInfo(): ServerKeyInfo              // GET /v1/keys/me
    suspend fun renewOwnKey(id: String): ServerKeyInfo   // POST /v1/keys/{id}/extend con la key del alumno
    suspend fun fetchChats(courseId: String, afterSeq: Long, limit: Int = 100): ChatBatch
    suspend fun coverage(courseId: String, term: String? = null): CoverageReport
    suspend fun reportUsage(report: UsageReportRequest)
    suspend fun pushAudit(entries: List<AuditEntryDto>)
    suspend fun upsertCourse(course: CourseUpsertDto)
    suspend fun upsertEnrollments(courseId: String, enrollments: List<EnrollmentDto>)
}
```

**No** debe reutilizar `LlmException.fromHttp` tal cual: un `404` de
`GET /v1/keys/{id}` significa "no existe esa key", no "modelo no disponible".
Propuesta:

```kotlin
class KitoruApiException(
    val status: Int,
    val code: String,          // "key_not_found", "quota_exceeded"…
    override val message: String,   // el `error.message` del servidor, en español
    val retryable: Boolean,
) : Exception(message)
```

### 14.2 `:llm-api` — cabeceras de contexto en el gateway

`OpenAiCompatibleGateway` necesita poder mandar las cabeceras `X-Kitoru-*`
(§3.1) sin romper su uso contra llama.cpp/Ollama (que las ignoran):

```kotlin
class OpenAiCompatibleGateway(
    …,
    /** Cabeceras extra (X-Kitoru-Student-Id, X-Kitoru-Thread-Id, …). */
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    // en chatStream: extraHeaders.forEach { (k, v) -> header(k, v) }
}
```

Y un contenedor de contexto:

```kotlin
/** Contexto de la cátedra que viaja en las cabeceras X-Kitoru-*. */
data class KitoruRequestContext(
    val courseId: String = "",
    val term: String = "",
    val studentId: String = "",
    val threadId: String = "",
    val visibility: String = "",   // FULL | METRICS_ONLY | NONE
)
```

El `ChatThread`/`Enrollment` del dominio ya tienen los datos necesarios
(`ChatThread.id` → `X-Kitoru-Thread-Id`; `Enrollment.id` → `X-Kitoru-Student-Id`).

### 14.3 `:llm-api` — distinguir "cuota" de "servidor caído"

**Único cambio de comportamiento** en el cliente de inferencia, y es
**opcional pero recomendado**:

```kotlin
// llm/error/LlmErrors.kt — fromHttp actualiza la clasificación del 429
fun fromHttp(status: Int, body: String, local: Boolean): LlmException {
    val origin = if (local) "llama-server" else "la API"
    val snippet = body.take(300).ifBlank { "(sin cuerpo)" }
    val code = extractErrorCode(body)   // "error.code" del Kitoru-Server
    return when {
        status == 401 || status == 403 -> …
        status == 429 && code == "rate_limited" ->
            LlmException(LlmErrorCode.SIN_CUOTA, "Vas muy rápido. Probá en ${retryAfter}s.", retryable = true)
        status == 429 ->
            LlmException(LlmErrorCode.SIN_CUOTA, body.substringAfter("\"message\":\"").substringBefore("\""),
                         retryable = false)   // cuota agotada: reintentar no sirve
        …
    }
}
```

Con esto:

- **cuota agotada** → `SIN_CUOTA` **no reintentable** → la app muestra el
  mensaje del servidor y **no** reintenta sola.
- **servidor caído** → `MODELO_NO_DISPONIBLE` (5xx) → reintentable → la app
  muestra "el servidor no responde" y reintenta con backoff.

Sin este cambio, los dos casos quedan en `SIN_CUOTA` reintentable y la app
insiste contra un servidor que no va a responder. **Es el punto 5 del
enunciado** y el cambio es de ~10 líneas.

### 14.4 `:core` — tipos nuevos

```
core/src/commonMain/kotlin/kitoru/domain/ServerKey.kt        # ServerKeyInfo, TermInfo
core/src/commonMain/kotlin/kitoru/domain/ServerChat.kt        # ChatEventDto, ChatBatch, CoverageReport
core/src/commonMain/kotlin/kitoru/domain/UsageReport.kt       # UsageReportRequest
```

`Course.term` ya existe; `ApiKeyGrant` (de `05-config-modelo.md` §1.5) ya
cubre el mapeo de la key.

### 14.5 `:ui` — pantallas

| Pantalla | Cambio |
|---|---|
| Alumno — chip de clave | "🔑 Vence el 31/12/2026 (cursada 2026-2) · 143.210 tokens hoy" + botón **Renovar** (si `canSelfRenew`) + botón **Reportar uso local** (§9.4). |
| Alumno — error de cuota | Mostrar `error.message` tal cual; si `code == quota_exceeded`, mostrar "Se renueva el {fecha}". |
| Docente — tabla de claves | Estado, cuota consumida, **Renovar**, **Rotar**, **Revocar**; el token se muestra **una sola vez** al emitir/rotar (`ApiKeyGrant.masked`). |
| Docente — chats | Poll de `GET /v1/chats?afterSeq=`; guardado en `TeacherChatRepository`; aplicar `ChatPolicy.canReadText` al leer. |
| Docente — cobertura | `GET /v1/courses/{id}/coverage` → la tabla de §9.2 con los chips 🟢🟡🟠⚪. |
| Docente — auditoría | `GET /v1/audit` + push del `AccessLog` local. |

---

## 15. Checklist de implementación (repo Kitoru-Server)

1. **Proceso y listeners**
   - [ ] Arranque con `--port`, `--local-port`, `--data-dir`.
   - [ ] Levantar `llama-server` como proceso hijo (mismo patrón que
         `Silvy/src/Modelo/SupervisorLlama.cpp`), con health-check y
         re-lanzamiento.
   - [ ] Estado del modelo: `DOWNLOADING → LOADING → READY → FAILED`, expuesto en
         `GET /v1/health` (503 mientras no esté `READY`).
2. **Inferencia**
   - [ ] `POST /v1/chat/completions`: proxy a `llama-server` con SSE, tool
         calling, `reasoning_effort` tolerado.
   - [ ] `usage` **siempre** en el último chunk + chunk extra si piden
         `stream_options`.
   - [ ] `GET /v1/models` con alias y filtrado por credencial.
   - [ ] Cabeceras `X-Kitoru-*` de respuesta.
3. **Criptografía y credenciales**
   - [ ] Generación de `kid` (base32 Crockford, 12 chars, prefijo de tipo) y
         `secret` (32 bytes CSPRNG).
   - [ ] `SHA-256(pepper || secret)`; pepper cifrado en reposo.
   - [ ] Caché de credenciales en memoria (TTL 60 s) + invalidación inmediata
         al revocar/rotar.
   - [ ] Denylist de revocados (en memoria).
4. **Cursadas y keys**
   - [ ] CRUD de `/v1/terms`.
   - [ ] CRUD de `/v1/keys` + `extend` + `rotate` + `suspend`/`resume`.
   - [ ] Invariante `expiresAt ≤ term.endsAt`.
   - [ ] Auto-renovación del alumno con tope.
5. **Cuotas**
   - [ ] Contadores por key / alumno / cátedra (in-memory).
   - [ ] Ventana deslizante de 60 s para RPM; cubeta diaria UTC para TPD.
   - [ ] Cabeceras de cuota en las respuestas.
6. **Chats**
   - [ ] Outbox append-only con `seq` monotónico y retención (90 días).
   - [ ] Recorte por visibilidad **en el servidor** antes de serializar.
   - [ ] `GET /v1/chats` con cursor, filtros y `includeText`.
   - [ ] `POST /v1/usage/report` (validar que no trae texto).
   - [ ] `GET /v1/courses/{id}/coverage` con los 4 estados de `coverage`.
7. **Auditoría**
   - [ ] Log de todo request + acciones de negocio.
   - [ ] `POST /v1/audit` (push del cliente docente).
8. **Seguridad**
   - [ ] TLS (directo o por proxy) + `403 insecure_transport` en HTTP plano.
   - [ ] `--admin-bind` opcional.
   - [ ] Nunca loguear el `Authorization` completo.
9. **Contrato**
   - [ ] `apiVersion` en todas las respuestas.
   - [ ] `GET /openapi.yaml` + `GET /docs`.
   - [ ] `GET /v1/version` con `deprecations`.
10. **Tests mínimos**
    - [ ] Token: parseo, expiración, revocación, rotación.
    - [ ] `expiresAt > term.endsAt` → 400.
    - [ ] 429 `rate_limited` vs `quota_exceeded` con mensajes correctos.
    - [ ] Chat de alumno `METRICS_ONLY` → la respuesta **no** contiene texto.
    - [ ] `coverage` de un alumno sin tráfico y sin declaración →
          `NONE_REPORTED` (nunca "no usou IA").
    - [ ] Streaming: el último chunk trae `usage` y el stream cierra con
          `[DONE]`.

---

## 16. Reconciliación con los demás documentos de diseño

Este documento **cambia** dos cosas ya escritas en `teacher/05-config-modelo.md`
§3.3. Se listan explícitamente para que el otro agente (o el que implemente
Kitoru) no las aplique dos veces:

| En `05-config-modelo.md` §3.3 | acá | Motivo |
|---|---|---|
| API de gestión con prefijo `/api`; helper `adminBaseUrl()` que hace `removeSuffix("/v1") + "/api"` | **Todo bajo `/v1`** (`/v1/keys/**`, `/v1/chats/**`, …). **Una sola URL** que configurar. | Una sola URL y un solo certificado; la separación se hace por credencial (scope), no por ruta. El helper `adminBaseUrl()` deja de hacer falta: `adminBaseUrl = inferenceBaseUrl`. |
| Formato de key `ktr_live_7f3d9c2a4e5b6c8d` | **`ktor_<kid>_<secret>`** (61 chars, `kid` con tipo embebido). | El `kid` permite `GET /v1/keys/{id}`, la revocación inmediata y la auditoría; el formato nuevo separa key de alumno / de servicio / de admin. |
| `POST /api/keys/{id}/rotate` | `POST /v1/keys/{id}/rotate` (+ `POST /v1/keys/{id}/extend`) | Se agrega `extend`, que el enunciado pide explícitamente. |
| `maxUses` cuenta "alumnos distintos" con `X-Kitoru-Student` | Igual, y además `X-Kitoru-Student-Id` (alias) y cuota por alumno/cátedra | Se generaliza el modelo de cuotas a tres niveles. |
| `{"error":"texto"}` (string) | `{"apiVersion":1,"error":{"code","message","type",…}}` | El `code` es lo que permite distinguir `rate_limited` de `quota_exceeded` (requisito del enunciado: la app debe separar "cuota agotada" de "servidor caído"). Los mensajes siguen siendo en español y mostrables. |
| `POST /api/keys` devuelve `key` + `id` | Devuelve `token` + `id` (kid) + `tokenLast4` | Consistencia con el token autocontenido. |

Lo que **no** cambia (los otros docs siguen siendo válidos):

- `teacher/05-config-modelo.md` §1.5 (`ApiKeyGrant`) y §3.2 (diálogo de emisión):
  el mapeo 1:1 con `/v1/keys` se mantiene.
- `teacher/05-config-modelo.md` §4 ("Probar conexión"): sigue siendo
  `GET {baseUrl}/models` con `Authorization: Bearer <key>`; el parser actual no
  cambia.
- `teacher/07-chats.md` §3 (visibilidad) y §6 (auditoría): el servidor los
  **aplica** (§8.4) y los **guarda** (§11.4); la app sigue siendo la segunda
  capa.
- `CONTEXTO.md` (matriz de visibilidad): se respeta exactamente.

---

## 17. Preguntas abiertas (para el agente que implemente Kitoru-Server)

1. **¿El Kitoru-Server es multi-institución o mono?** El diseño soporta
   multi-institución por token `A…` con `courseIds`, pero la UI de escritorio
   asume una sola institución. Si es mono, se puede simplificar: el token `A…`
   se genera en el primer arranque y se muestra una vez en pantalla.
2. **¿Dónde se guarda el estado?** Hoy: archivos JSON en `data-dir`
   (`keys.json`, `terms.json`, `outbox.jsonl`, `audit.jsonl`, `counters.json`).
   ¿SQLite? Recomiendo **SQLite** si el implementador ya lo tiene en el stack
   (Ktor + JDBC), porque el outbox crece; si no, JSON-lines con append y
   compactación periódica.
3. **¿Se puede emitir una key sin `term`?** Hoy sí, con tope global de
   `maxKeyLifetimeDays`. Si la institución quiere que **toda** key esté atada a
   una cursada, el `term` pasa a ser obligatorio (recomendado: sí).
4. **La currícula del alumno sin key**: si la app del alumno no tiene key
   (usa IA local), ¿pide una key "de reserva" al entrar a la cátedra? El
   diseño asume que sí (`POST /v1/enrollments` con `joinCode`), pero con
   `keyPolicy.selfIssuance = TEACHER_ONLY` queda deshabilitado.
5. **Webhooks (push)**: queda fuera de v1. Si la institución lo pide, es
   `POST /v1/webhooks` con el mismo payload de `GET /v1/chats`.

---

*Documento vivo: se actualiza en el mismo commit del cambio que lo motive.*
*Las referencias a `teacher/05-config-modelo.md` §3.3 y `teacher/07-chats.md`
deben reconciliarse en el mismo commit que incorpore este contrato.*
