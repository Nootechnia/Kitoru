# 01 · Contrato de sincronización (KSP/1)

> Diseño del **protocolo de sincronización** entre los tres actores de Kitoru:
> el **Kitoru del alumno**, el **Kitoru del docente** y el **Kitoru-Server**.
>
> Depende de: `docs/design/CONTEXTO.md`, `docs/design/domain/03-inscripciones.md`
> (visibilidad, roles, `Enrollment`), `docs/design/domain/09-identidad.md`
> (perfil local, `CredentialStore`, `InstitutionLink`), `docs/design/teacher/07-chats.md`
> (explorador de chats del docente, `AccessLog`, paquetes `.kitoru`) y el dominio
> real `core/src/commonMain/kotlin/kitoru/domain/{Roles,Course,Chat,Analytics,
> ModelPolicy,Repositories,FileRepositories,JsonStore}.kt`.
>
> **Estado:** propuesta cerrada. Este documento define el contrato; no escribe
> código de producción.

---

## 0. Resumen ejecutivo (12 decisiones)

| # | Decisión | Motivo |
|---|---|---|
| D1 | **REST + JSON** (kotlinx.serialization) sobre HTTPS, con **sincronización delta por cursor opaco** por recurso. | REST es lo que ya conoce el stack (Ktor 3.0.3); el delta evita re-descargar 200 MB por alumno. |
| D2 | El **Kitoru-Server es la fuente de verdad** de la cátedra: materiales, inscripciones, configuración del modelo y **relé de chats**. | Una sola copia del corpus y del padrón; el servidor puede aplicar la visibilidad **decentralizada**. |
| D3 | El **alumno es la fuente de verdad** de sus conversaciones, de su declaración de visibilidad y de sus ajustes de modelo. | El estudiante conserva agencia (regla 5 del proyecto). El servidor sólo relay y filtra. |
| D4 | **Dos tokens distintos**: `inferenceKey` (inferencia) y `syncToken` (sincronización), más el `sessionToken` (SSO, sólo docentes). | La API key es un **permiso de consumo**, no una identidad. Que la key sirva para leer el padrón o subir chats ajenos es un agujero de privilege escalation. |
| D5 | El cursor es **opaco por recurso** (`manifest`, `materials`, `roster`, `chats`), generado por un **log de cambios append-only por cátedra** y firmado con HMAC. | El cliente no puede inventar cursores ni replayar uno de otra cátedra. El servidor puede invalidarlos por antigüedad. |
| D6 | La subida de chats es **idempotente** por `clientMessageId` (UUID v4 generado en el cliente), con un índice único `(authorId, clientMessageId)`. | Reintentar un POST tras un timeout no puede duplicar mensajes: es la diferencia entre "se synchronizó bien" y "el alumno tiene el chat duplicado". |
| D7 | **Outbox** en disco (`outbox.json`) con orden FIFO, reintentos con backoff exponencial y *dead letters*. | El alumno trabaja sin red; al volver, sube solo y en orden. Nada se pierde si la app se cierra a mitad de una subida. |
| D8 | Los **mensajes son append-only** (se fusionan); los **metadatos del hilo** resuelven con LWW por reloj del cliente **con guarda de desfase** (skew > 5 min ⇒ conflicto explícito); los **borrados son tombstones**. | Editar un chat en dos dispositivos no puede perder texto ni restaurar un chat borrado. |
| D9 | Los chats con **IA local nunca se suben solos**. La subida es un acto explícito del alumno ("Compartir mis conversaciones") con un **consentimiento por paquete** (`consent{scope, grantedAt, version}`). | La privacidad no es un permiso que se pueda activar por defecto: es un botón que el alumno aprieta. |
| D10 | El corpus se entrega en **dos niveles**: nivel 0 (metadatos + texto extraído, ~1,4 MB) y nivel 1 (binario original, bajo demanda). El nivel 1 **nunca** se descarga en la primera sincronización. | 500 alumnos × 200 MB = 100 GB de egreso si se descarga todo. Con nivel 0: **750 MB**. Reducción de 133×. |
| D11 | La **analítica no la calcula el servidor**: la calcula el cliente del docente con `AnalyticsAnalyzer.analyze` (función pura ya existente) sobre los chats descargados. | Una sola implementación, determinista y testeable; el servidor queda tonto. |
| D12 | La **primera sincronización** es por fases, con el **corpus en bundle comprimido** (1 request, reanudable) y la indexación en segundo plano: la app es usable antes de que termine. | Un alumno nuevo con 80 materiales no debe esperar 10 minutos bloqueado. |

---

## 1. Los tres actores y qué datos tienen

### 1.1 Kitoru del alumno (cliente)

Corre en el dispositivo del alumno (desktop o Android). Es **la fuente de verdad
de sus propios datos**.

| Dato | Tipo de dominio | Dónde vive en disco | ¿Viaja? |
|---|---|---|---|
| Perfil local | `UserProfile` (`Roles.kt`) | `config/profiles.json` | Sólo con SSO vinculado (`subject`) |
| Inscripción | `Enrollment` (`Course.kt`) | `config/enrollments.json` | **Sí** (declaración de visibilidad, modo de IA) |
| Config del modelo efectiva | `ModelPolicy` | `config/courses.json` (`Course.modelPolicy`) | No (es local; el servidor manda la base) |
| Ajustes del modelo del alumno | `ModelPolicy` local | `config/model_overrides.json` (nuevo) | No |
| Hilos y mensajes | `ChatThread`, `ChatMessage` (`Chat.kt`) | `config/threads.json`, `config/messages.json` | **Sí** (según visibilidad) |
| Copia del corpus | `Material` + `extractedText` | `config/materials.json` + `courses/<id>/corpus/` | Sólo **baja** |
| Originales de los materiales | binarios | `courses/<id>/files/` | Sólo **baja**, bajo demanda |
| Estado de sincronización | `SyncState` (nuevo) | `courses/<id>/sync.json` | No |
| Bandeja de salida | `OutboxEntry` (nuevo) | `courses/<id>/outbox.json` | No (sale de ahí) |
| Credenciales | `inferenceKey`, `syncToken` | `CredentialStore` (`credentials.json` en desktop, `EncryptedSharedPreferences` en Android) | No (nunca en JSON plano) |

**Regla dura:** `UserProfile`, `enrollments.json` y `credentials.json` **no
contienen nunca** la API key ni el token de sincronización. Sólo referencias
(`apikey:<serverId>:<courseId>`, `sync:<serverId>:<courseId>`), como ya se
decidió en `09-identidad.md` §8.

### 1.2 Kitoru del docente (cliente)

Corre en el dispositivo del docente. Es **la fuente de verdad de la edición de
la cátedra** mientras está offline, pero el **servidor es la fuente de verdad
publicada**.

| Dato | Dónde vive | ¿Viaja? |
|---|---|---|
| Cátedra (`Course`: config, `topK`, `chunkSize`, `systemPrompt`, `analyticsEnabled`, `allowLocalModels`, `visibilityPolicy`) | `config/courses.json` | **Sí**, el docente **sube** (push) |
| Materiales (`Material` + binario) | `config/materials.json` + `courses/<id>/files/` | **Sí**, el docente **sube** |
| Inscripciones (`Enrollment`) | `config/enrollments.json` | **Baja** (el docente la edita: rol, estado, `visibilityCap`) |
| Conversaciones de los alumnos | `config/threads.json`, `config/messages.json` | **Baja** (según visibilidad efectiva) |
| Analítica | calculada en memoria | No (se recalcula) |
| `AccessLog` (auditoría de accesos) | `config/access_log.json` | **Sí**, el docente **sube** (append-only) |

El docente **también** tiene outbox: sin servidor, la app del docente es
totalmente funcional (bundle `.kitoru`, roster por archivo, importación de
paquetes). El servidor es la vía **de publicación y de clueless**, no un
requisito.

### 1.3 Kitoru-Server

Es una **app de escritorio** (repo hermano) que además expone una API. Cuatro
responsabilidades:

| # | Responsabilidad | Detalle |
|---|---|---|
| R1 | **Alojar el modelo** | Levanta `llama-server` y expone un gateway OpenAI-compatible en `/v1/chat/completions`. |
| R2 | **Emitir credenciales** | Emite la `inferenceKey` (uso del modelo) y el `syncToken` (sincronización), y los vincula a una inscripción. |
| R3 | **Almacenar el repositorio de materiales** | **Decisión:** los materiales viven en el servidor, **una sola copia por cátedra**, no una por alumno. El servidor extrae el texto, calcula los hashes y sirve el corpus por delta. |
| R4 | **Relé de chats** | Recibe las conversaciones de los alumnos, aplica la visibilidad **en el servidor** y las sirve al docente ya **redactadas** si corresponde. |

R4 es la pieza que hace que la matriz de visibilidad del `CONTEXTO.md` sea
**estructural** y no una promesa: si el texto no debe verse, **nunca sale del
servidor** hacia el docente.

**Lo que el servidor NO hace:**

- No calcula la analítica (la calcula el cliente del docente con
  `AnalyticsAnalyzer.analyze`).
- No genera el modelo.
- No ve los chats de IA local mientras el alumno no los suba a mano.
- No guarda la API key del alumno en ningún lado: la emite y se olvida (sólo
  guarda su *hash* para poder revocarla).

### 1.4 Mapa de fuente de verdad

```
                 ┌───────────────────────────────────────────────┐
                 │              Kitoru-Server                   │
                 │  (fuente de verdad de la CÁTEDRA)             │
                 │                                               │
                 │  Course config ──► materiales ──► inscriptions │
                 │        ▲                  ▲          ▲       │
                 │        │ push docente     │          │       │
                 └────────┼──────────────────┼──────────┼───────┘
                  pull    │                  │ pull     │ pull
        ┌───────────────┴──────────────────┴──────────┴──────┐
        │                                                      │
┌───────┴────────┐                                    ┌────────┴───────┐
│  Kitoru alumno │ ────── push chats (outbox) ──────► │ Kitoru docente│
│  ( chats,      │                                    │ ( cátedra,    │
│    config,     │ ◄──── pull chats (redactados) ────── │   materiales, │
│    corpus )    │                                    │   padrón,     │
└────────────────┘                                    │   analítica ) │
                                                      └────────────────┘
```

---

## 2. Qué se sincroniza y en qué dirección

### 2.1 Tabla de entidades

| Entidad | Fuente de verdad | Dirección | Modo | Dispara | Endpoint |
|---|---|---|---|---|---|
| `Course` (config de la cátedra) | **Servidor** | docente → servidor → todos | delta | cambio del docente | `PUT /v1/courses/{id}` (push), manifiesto (pull) |
| `Course.modelPolicy` (base del modelo) | **Servidor** | docente → servidor → alumnos | delta | cambio del docente | ídem |
| `Course.systemPrompt` | **Servidor** | docente → servidor → alumnos | delta | cambio del docente | ídem |
| `Material` (metadatos) | **Servidor** | docente → servidor → alumnos | delta | alta/edición/baja | `POST/DELETE /v1/courses/{id}/materials` (push), `GET .../materials` (pull) |
| `Material` (texto extraído) | **Servidor** | servidor → alumno | delta por hash | extracción/reextracción | `GET .../materials/{id}/content?variant=text` |
| `Material` (binario original) | **Servidor** | servidor → alumno | bajo demanda | el alumno abre el archivo | `GET .../materials/{id}/content?variant=original` |
| `Enrollment` (rol, estado, `visibilityCap`) | **Servidor** (el docente decide) | docente → servidor → todos | delta | acción del docente | `PATCH /v1/courses/{id}/inscriptions/{id}` |
| `Enrollment.visibility` (declaración del alumno) | **El alumno** | alumno → servidor | push | cambio en Ajustes | `PUT /v1/courses/{id}/inscriptions/me` |
| `Enrollment.aiMode` | **El alumno** | alumno → servidor | push | declara IA local | ídem |
| `ChatThread` (título, tags, modelo) | **El alumno** | alumno → servidor | push | fin de conversación / edición | `POST /v1/courses/{id}/chats` |
| `ChatMessage` (texto, citas, tools, tokens) | **El alumno** | alumno → servidor | push append-only | cada mensaje | `POST /v1/courses/{id}/chats` |
| `AccessLog` (auditoría del docente) | **El docente** | docente → servidor | push append-only | cada consulta del docente | `POST /v1/courses/{id}/access-log` |
| `ModelPolicy` (ajustes del alumno) | **El alumno** | — | **no viaja** | — | local |
| `UserProfile` (nombre, email, alias) | **El alumno** | sólo `subject` si hay SSO | — | — | local |
| Chats con IA local | **El alumno** | **sólo si el alumno los sube a mano** | push manual | acción explícita | `POST /v1/courses/{id}/chats` con `origin: LOCAL_SHARED` |
| `inferenceKey` / `syncToken` | **Servidor** | servidor → alumno | — | emisión / rotación | `POST /v1/join`, `POST /v1/credentials/rotate` |

### 2.2 Qué NO se sincroniza nunca

- El **texto de los chats** de un alumno con `visibility = NONE` o
  `METRICS_ONLY` **no sale del servidor hacia el docente**: el servidor lo
  redacta antes de servirlo (§3.8). Ni siquiera existe un endpoint que lo
  devuelva en crudo.
- La **`inferenceKey`** no se sincroniza: se emite una vez y se guarda en el
  `CredentialStore`.
- El **`UserProfile.email`** no viaja (doc 09, §3.2).
- El **`subject` institucional** no viaja al docente: viaja al servidor, que lo
  usa para fusionar duplicados.

### 2.3 Reglas que la protección de datos impone al protocolo

1. **Mínimo dato**: el `roster` completo sólo se sirve a docentes. Un alumno
   que llama a `GET /v1/courses/{id}/inscriptions` recibe **sólo su propia
   inscripción** (o `403` si pide el padrón).
2. **Redacción en el servidor**, no en el cliente: si el texto no debe verse,
   el servidor no lo manda. "Cargar y después tapar" es una fuga.
3. **El texto es del alumno**: el servidor es un relay con retención; la baja
   del alumno o el fin de la cursada purgan lo que corresponde (§8.4).
4. **Trazabilidad**: toda escritura al servidor deja registro con `actor`, que es
   el `userId` del token, no un campo del cuerpo de la petición (el cliente no
   puede suplantar a otro actor).

---

## 3. El protocolo

### 3.1 Transporte y convenciones

| Aspecto | Definición |
|---|---|
| Transporte | **HTTPS** (TLS 1.2+). `http://` sólo para `localhost` en desarrollo. |
| Formato | `application/json; charset=utf-8` (kotlinx.serialization). Binarios aparte. |
| Versión | Ruta con versión: `/v1/…`. Cabecera `X-Kitoru-Protocol: 1` en toda respuesta. |
| Autenticación | `Authorization: Bearer <token>` (ver §6). |
| Idempotencia | `Idempotency-Key: <uuid>` en los POST del outbox (mismo valor al reintentar). |
| Cache | `ETag` + `Last-Modified` en todos los GET; `Cache-Control: public, max-age=300` en el manifiesto. |
| Compresión | `Accept-Encoding: gzip` en toda petición; el servidor comprime JSON > 1 KB y texto. |
| Paginación | `limit` (por defecto 200, máximo 1000) + `after` (cursor de página) en las colecciones grandes. |
| Lotes | Máximo **100 entradas** o **512 KB** por POST (lo que se alcance antes). |
| Timeouts (cliente) | 15 s en general, 60 s para descargas de material, 120 s para el bundle del corpus. |
| Reintentos (cliente) | Automáticos sólo en `429`, `5xx` y errores de red. `4xx` no se reintentan salvo `408`/`429`. |

### 3.2 Envoltura de error

Todas las respuestas de error comparten la misma forma:

```json
{
  "error": {
    "code": "cursor_expired",
    "message": "El cursor de sincronización venció. Hay que volver a bajar el manifiesto completo.",
    "retryable": false,
    "detail": { "since": "eyJzZSI6ODgxMn0.k3s9d" }
  }
}
```

`message` está en español y es **legible por una persona**: el cliente lo
muestra tal cual (regla 4 del proyecto: fail-fast con mensajes en español).

**Catálogo de códigos**

| HTTP | `code` | Significado | `retryable` |
|---|---|---|---|
| 400 | `malformed_request` | JSON inválido o campo faltante | no |
| 401 | `unauthorized` | falta el token, expiró o fue revocado | no (hay que re-autenticar) |
| 403 | `forbidden` | el token no tiene el permiso para esta operación | no |
| 403 | `not_enrolled` | el token no pertenece a esta cátedra | no |
| 403 | `visibility_denied` | el observador no puede leer el texto de ese alumno | no |
| 403 | `visibility_none` | el alumno declaró "Oculto": no se admite la subida | no |
| 403 | `roster_forbidden` | un alumno intentó leer el padrón | no |
| 404 | `course_not_found` | la cátedra no existe o no es visible | no |
| 404 | `material_not_found` | el material no existe o fue despublicado | no |
| 404 | `thread_not_found` | el hilo no existe | no |
| 409 | `conflict` | conflicto de versión (ver §5.5) | no (hay que resolver) |
| 409 | `thread_deleted` | el hilo fue borrado (tombstone) | no |
| 410 | `cursor_expired` | hay que hacer una sincronización completa | sí (full) |
| 413 | `payload_too_large` | el lote excede 100 entradas / 512 KB | sí (dividir) |
| 415 | `unsupported_media_type` | el `Content-Type` no es el esperado | no |
| 422 | `validation_failed` | el cuerpo no valida contra el esquema | no |
| 429 | `rate_limited` | excedió el cupo | sí (honrar `Retry-After`) |
| 500 | `internal_error` | fallo del servidor | sí |
| 503 | `server_unavailable` | el servidor está en mantenimiento | sí |

### 3.3 Cabeceras comunes

**Petición**

```http
GET /v1/courses/c-ia-2026/manifest?since=eyJzZSI6ODgxMn0.k3s9d HTTP/1.1
Host: kitoru.unlp.edu.ar
Authorization: Bearer sync_t_9f2a…
X-Kitoru-Protocol: 1
If-None-Match: "b7f3"
Accept-Encoding: gzip
```

**Respuesta**

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
ETag: "b7f3"
X-Kitoru-Protocol: 1
X-Kitoru-Server-Time: 1773012345000
Cache-Control: public, max-age=300
Content-Encoding: gzip
```

### 3.3.1 Qué tipos del dominio asume este contrato

El contrato **no inventa** tipos: usa los de `kitoru.domain` y, donde hacen
falta campos que hoy no existen, los toma de los documentos de diseño ya
cerrados. Concretamente:

| Tipo | Fuente | Campos que este contrato usa y que **hoy no existen** en el código |
|---|---|---|
| `Course` | `domain/Course.kt` | `published`, `ownerUserId`, `visibilityPolicy` → propuestos en `domain/03-inscripciones.md` §2 y `domain/08-permisos.md`. Todos con default. |
| `Enrollment` | `domain/Course.kt` | `status`, `visibilityCap`, `aiMode`, `declaredLocalAiAt`, `lastImportAt`, `events` → propuestos en `domain/03-inscripciones.md` §2.1. |
| `ChatThread` | `domain/Chat.kt` | `rev`, `updatedByDevice` (§3.7.1). |
| `ChatMessage` | `domain/Chat.kt` | `rev`, `updatedByDevice`, `redacted` (§3.7.1). |
| `ChatVisibility` | `domain/Roles.kt` | `level` (para `min(...)`) → propuesto en `domain/03-inscripciones.md` §5.3. |
| `Material` | `domain/Course.kt` | `contentHash`, `textHash`, `version` → **sólo en el wire** (`RemoteMaterial`), no en el dominio: son metadatos de transporte. |

Los **tipos nuevos** que introduce este contrato (y que viven en `:core`) son:
`SyncState`, `OutboxEntry`, `OutboxKind`, `SyncClient`, `SyncEngine`,
`ChatSharePackage`, `ShareConsent` y los DTOs de la §11.

---

### 3.4 `GET /v1/courses/{courseId}/manifest`

**Qué hace:** devuelve **qué cambió** en la cátedra desde un cursor. Es la
primera llamada de toda sincronización y la que decide si hay que descargar
materiales, roster o nada.

**Request**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `since` | string (opaco) | — | Cursor de la última sincronización. Si se omite ⇒ manifiesto completo. |
| `include` | csv | `course,materials,roster` | Qué bloques incluir. `roster` sólo para docentes. |

```http
GET /v1/courses/c-ia-2026/manifest?since=eyJzZSI6ODgxMn0.k3s9d&include=course,materials
```

**Response 200**

```json
{
  "protocol": 1,
  "courseId": "c-ia-2026",
  "serverTime": 1773012345000,
  "cursor": "eyJzZSI6ODg0OCwidiI6MX0.k3s9e",
  "hasMore": false,

  "course": {
    "id": "c-ia-2026",
    "code": "IA-2026-1",
    "name": "Inteligencia Artificial",
    "term": "2026-1",
    "description": "Cursada 1°",
    "joinCode": "IA7F3K",
    "systemPrompt": "Sos un tutor de la cátedra de IA...",
    "topK": 5,
    "chunkSize": 1200,
    "analyticsEnabled": true,
    "allowLocalModels": true,
    "published": true,
    "ownerUserId": "u-doc-1",
    "visibilityPolicy": {
      "rules": [
        { "role": "TITULAR",   "maxVisibility": "FULL" },
        { "role": "AYUDANTE",  "maxVisibility": "METRICS_ONLY" },
        { "role": "ALUMNO",    "maxVisibility": "NONE" }
      ]
    },
    "modelPolicy": {
      "mode": "REMOTE",
      "remoteBaseUrl": "https://kitoru.unlp.edu.ar/v1",
      "modelName": "gpt-oss-20b",
      "temperature": 0.2,
      "maxTokens": 2048,
      "topK": 5,
      "allowStudentOverride": true,
      "lockedFields": ["API_KEY", "BASE_URL", "MODEL_NAME"],
      "toolsEnabled": true,
      "systemPrompt": "Respondé en español rioplatense..."
    },
    "version": 12,
    "updatedAt": 1773010000000
  },

  "materials": {
    "upserted": [
      {
        "id": "m-01",
        "courseId": "c-ia-2026",
        "title": "Apuntes-01.pdf",
        "kind": "DOCUMENT",
        "path": "materiales/Apuntes-01.pdf",
        "sizeBytes": 2516582,
        "uploadedBy": "u-doc-1",
        "uploadedAt": 1770000000000,
        "tags": ["unidad-1"],
        "visible": true,
        "warning": "",
        "contentHash": "sha256:9f2c…",
        "textHash": "sha256:cd34…",
        "textBytes": 61440,
        "textEtag": "\"cd34\"",
        "version": 3
      }
    ],
    "removed": ["m-17"]
  },

  "corpus": {
    "version": "b7f3",
    "materialCount": 80,
    "visibleCount": 78,
    "textBytes": 4915200,
    "bundleUrl": "/v1/courses/c-ia-2026/corpus?version=b7f3",
    "bundleBytes": 1468006,
    "bundleEtag": "\"b7f3\""
  },

  "roster": {
    "upserted": [],
    "removed": []
  }
}
```

**Respuestas y errores**

| Situación | HTTP | Cuerpo |
|---|---|---|
| Normal | 200 | el JSON de arriba |
| Sin cambios desde `since` | 200 | `{"courseId":…,"serverTime":…,"cursor":"<el mismo>","hasMore":false}` con `course`, `materials` y `roster` **ausentes** (o vacíos) |
| El `ETag` del `If-None-Match` coincide | 304 | sin cuerpo |
| El cursor venció | 410 | `{"error":{"code":"cursor_expired", …}}` → el cliente hace la **sincronización completa** (§4.3) |
| El token no es de esta cátedra | 403 | `not_enrolled` |
| Un alumno pide `include=roster` | 403 | `roster_forbidden` |

> **Nota:** el bloque `roster` **nunca** se incluye completo para un alumno: el
> servidor lo reemplaza por la entrada del propio alumno (o lo omite si no lo
> pidió). Ver §3.9.

---

### 3.5 `GET /v1/courses/{courseId}/materials`

**Qué hace:** devuelve los metadatos de los materiales **que cambiaron** desde
un cursor. Es el endpoint "delta por material": no re-descarga nada.

**Request**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `since` | string (opaco) | — | Cursor de materiales. Sin él ⇒ lista completa. |
| `ids` | csv | — | Filtrar por ids (útil para reconciliar). |
| `include` | csv | `meta` | `meta` \| `text` (agrega el texto extraído al mismo cuerpo). |
| `limit` | int | 500 | Máximo de entradas (máx. 1000). |

```http
GET /v1/courses/c-ia-2026/materials?since=eyJtIjo0MSwicyI6MX0.k3s9a&limit=500
```

**Response 200**

```json
{
  "cursor": "eyJtIjo0NSwicyI6MX0.k3s9b",
  "hasMore": false,
  "materials": [
    {
      "id": "m-01",
      "courseId": "c-ia-2026",
      "title": "Apuntes-01.pdf",
      "kind": "DOCUMENT",
      "path": "materiales/Apuntes-01.pdf",
      "sizeBytes": 2516582,
      "uploadedBy": "u-doc-1",
      "uploadedAt": 1770000000000,
      "tags": ["unidad-1"],
      "visible": true,
      "warning": "",
      "contentHash": "sha256:9f2c…",
      "textHash": "sha256:cd34…",
      "textBytes": 61440,
      "textEtag": "\"cd34\"",
      "version": 3
    }
  ],
  "removed": ["m-17"],
  "nextSince": null
}
```

Si `include=text`, cada entrada agrega:

```json
{ "text": "¿Qué es una función de pérdida?\n\n…", "textEtag": "\"cd34\"" }
```

**Respuestas y errores**

| Situación | HTTP | `code` |
|---|---|---|
| Normal | 200 | — |
| Material despublicado (`visible=false`) | 200 | se omite del listado; si se pidió explícitamente por `ids`, 404 `material_not_found` |
| Hay más de `limit` | 200 | `hasMore: true` + `nextSince` (cursor para la próxima página) |
| Cursor vencido | 410 | `cursor_expired` |

> El cliente **no** descarga el texto de acá salvo que lo pida explícitamente:
> lo normal es bajar el **bundle del corpus** (§9), que trae todo el texto en
> un request comprimido.

---

### 3.6 `GET /v1/courses/{courseId}/materials/{materialId}/content`

**Qué hace:** entrega el **contenido** de un material. Dos variantes: el texto
extraído (nivel 0, lo que el RAG necesita) y el binario original (nivel 1,
bajo demanda).

**Request**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `variant` | `text` \| `original` | `original` | Qué descargar. |
| `Range` | header | — | Reanudación de descargas parciales. |

```http
GET /v1/courses/c-ia-2026/materials/m-01/content?variant=text HTTP/1.1
Authorization: Bearer sync_t_9f2a…
If-None-Match: "cd34"
```

**Response 200 (variant=text)**

```http
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
ETag: "cd34"
X-Kitoru-Sha256: sha256:cd34…
X-Kitoru-Text-Bytes: 61440
Content-Encoding: gzip
```

```text
¿Qué es una función de pérdida?
…
```

**Response 200 (variant=original)**

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
ETag: "9f2c…"
X-Kitoru-Sha256: sha256:9f2c…
Content-Length: 2516582
Accept-Ranges: bytes
```

**Respuestas y errores**

| Situación | HTTP | `code` | Notas |
|---|---|---|---|
| Normal | 200 | — | `ETag` = hash del contenido |
| Sin cambios | 304 | — | `If-None-Match` coincide |
| Material inexistente o despublicado | 404 | `material_not_found` | No se distingue de "no existe" (no se filtra la existencia) |
| Rango inválido | 416 | `validation_failed` | Con `Content-Range: bytes */N` |
| Descarga interrumpida | — | — | Reanudar con `Range: bytes=<desde>-` |
| Token sin permiso de material | 403 | `forbidden` | No debería ocurrir: `syncToken` de alumno siempre trae `materials:read` para los visibles |

> **El hash manda.** El cliente verifica `X-Kitoru-Sha256` contra el hash que
> tiene en `materials.json`. Si no coincide, descarta el archivo y lo vuelve a
> pedir: una descarga corrupta no puede envenenar el índice BM25.

---

### 3.7 `POST /v1/courses/{courseId}/chats`

**Qué hace:** el alumno sube sus conversaciones. Es el **único** endpoint de
escritura del lado del alumno sobre datos que le pertenecen.

**Request**

```json
{
  "clientId": "d-9f2a7c1",
  "clientTime": 1773012300000,
  "entries": [
    {
      "kind": "thread",
      "clientThreadId": "t-9f2",
      "origin": "HOSTED",
      "consent": null,
      "thread": {
        "id": "c-ia-2026|t-9f2",
        "title": "Duda de parcial",
        "createdAt": 1773012000000,
        "updatedAt": 1773012340000,
        "modelLabel": "gpt-oss-20b",
        "effectiveModel": { "mode": "REMOTE", "modelName": "gpt-oss-20b", "temperature": 0.3 },
        "tags": ["parcial"],
        "rev": 1,
        "updatedByDevice": "d-9f2a7c1"
      }
    },
    {
      "kind": "message",
      "clientThreadId": "t-9f2",
      "clientMessageId": "m-1a2b3c4d5e6",
      "origin": "HOSTED",
      "consent": null,
      "message": {
        "id": "m-1a2b3c4d5e6",
        "role": "user",
        "text": "¿Me explicás SVM con margen?",
        "createdAt": 1773012100000,
        "sources": [],
        "citation": null,
        "toolCalls": [],
        "latencyMs": 0,
        "tokensIn": 24,
        "tokensOut": 0,
        "rev": 1,
        "updatedByDevice": "d-9f2a7c1"
      }
    },
    {
      "kind": "message",
      "clientThreadId": "t-9f2",
      "clientMessageId": "m-2b3c4d5e6f7",
      "origin": "HOSTED",
      "consent": null,
      "message": {
        "id": "m-2b3c4d5e6f7",
        "role": "assistant",
        "text": "El margen es la distancia…",
        "createdAt": 1773012340000,
        "sources": ["corpus:materiales/Apuntes-01.pdf"],
        "citation": { "verified": 2, "unsupported": 0, "unknown": 0 },
        "toolCalls": [],
        "latencyMs": 2140,
        "tokensIn": 118,
        "tokensOut": 412,
        "rev": 1,
        "updatedByDevice": "d-9f2a7c1"
      }
    }
  ]
}
```

**Campos de la entrada**

| Campo | Obligatorio | Descripción |
|---|---|---|
| `kind` | sí | `thread` \| `message`. |
| `clientThreadId` | sí | Id local del hilo. El servidor lo namespasea: `courseId\|clientThreadId`. |
| `clientMessageId` | si `kind=message` | **Clave de deduplicación**. UUID v4 generado en el cliente al crear el mensaje. |
| `origin` | sí | `HOSTED` (chat con la IA de la cátedra) \| `LOCAL_SHARED` (IA local, subida manual). |
| `consent` | si `origin=LOCAL_SHARED` | Ver §7.2. **Obligatorio**: sin él, `422 validation_failed`. |
| `thread` / `message` | sí | El objeto del dominio, serializado con los serializadores de `Chat.kt`. |

**Response 200**

El resultado es **por entrada**: el servidor procesa cada una y dice qué pasó.

```json
{
  "serverTime": 1773012400000,
  "cursor": "eyJzZSI6ODgxNiwidiI6MX0.k3s9c",

  "accepted": [
    { "kind": "thread",  "clientThreadId": "t-9f2", "id": "c-ia-2026|t-9f2", "serverSeq": 8814, "status": "created" },
    { "kind": "message", "clientMessageId": "m-1a2b3c4d5e6", "id": "m-1a2b3c4d5e6", "serverSeq": 8815, "status": "created" },
    { "kind": "message", "clientMessageId": "m-2b3c4d5e6f7", "id": "m-2b3c4d5e6f7", "serverSeq": 8816, "status": "created" }
  ],

  "duplicates": [
    { "kind": "message", "clientMessageId": "m-1a2b3c4d5e6", "id": "m-1a2b3c4d5e6", "status": "duplicate" }
  ],

  "rejected": [
    {
      "kind": "message",
      "clientMessageId": "m-9z9z9z9z9z9",
      "code": "visibility_none",
      "message": "Tu inscripción está configurada como «Oculto». Cambiá la visibilidad en Ajustes para poder compartir conversaciones."
    }
  ],

  "conflicts": [
    {
      "kind": "message",
      "clientMessageId": "m-7h7h7h7h7h7",
      "reason": "stale_revision",
      "serverRev": 3,
      "clientRev": 2,
      "server": { "id": "m-7h7h7h7h7h7", "text": "…", "rev": 3, "updatedAt": 1773011000000, "updatedByDevice": "d-aaaa" },
      "client": { "id": "m-7h7h7h7h7h7", "text": "…", "rev": 2, "updatedAt": 1773009000000, "updatedByDevice": "d-9f2a7c1" }
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `accepted` | La entrada se guardó. `status` ∈ `created` \| `revised`. |
| `duplicates` | Ya estaba (mismo `clientMessageId`): **no se creó nada nuevo**. Es la respuesta normal de un reintento. |
| `rejected` | No se admitió (visibilidad, hilo borrado, validación). El cliente **no reintenta** esas entradas: van a *dead letters*. |
| `conflicts` | Hay que resolverlos (§5.5). |

**Respuestas y errores**

| Situación | HTTP | `code` | Qué hace el cliente |
|---|---|---|---|
| Normal (parcial o total) | 200 | — | procesa `accepted`/`duplicates`/`rejected`/`conflicts` |
| JSON inválido | 400 | `malformed_request` | error al usuario, no reintenta |
| Token vencido | 401 | `unauthorized` | re-autentica (§6.3) |
| No inscripto | 403 | `not_enrolled` | error al usuario |
| El alumno declaró "Oculto" | 403 | `visibility_none` | el cliente muestra el diálogo de visibilidad |
| Conflicto de versión (todo el lote) | 409 | `conflict` | abre el diálogo de resolución |
| Lote demasiado grande | 413 | `payload_too_large` | divide el lote y reintenta |
| Falta `consent` en un `LOCAL_SHARED` | 422 | `validation_failed` | bug del cliente: se avisa en desarrollo |
| Hilo borrado (tombstone) | 409 | `thread_deleted` | dead letter: *"Este chat fue borrado en otro dispositivo"* |

> **Por qué 200 y no 207:** el resultado es por entrada y los clientes HTTP de
> Ktor manejan mejor un `200` con cuerpo que un `207 Multi-Status`. El `409` a
> nivel HTTP queda reservado para el caso "el lote entero es inválido por
> versión" (p. ej. todas las entradas son ediciones obsoletas).

#### 3.7.1 Mapeo con el dominio (`Chat.kt`)

El contrato **reutiliza los serializadores del dominio** y sólo agrega tres
cosas. Es importante que quede explícito, porque es la frontera entre el
dominio y el wire:

| Campo del dominio | Qué hace el contrato | Por qué |
|---|---|---|
| `ChatThread.id` | El cliente manda **sin** `id`; el servidor lo compone: `"$courseId|$clientThreadId"` | Es la convención que ya usa `FileMessageRepository.listForCourse` (filtra por `"$courseId|"`). El `id` del hilo **lo genera el cliente** para que el outbox pueda referenciarlo antes de que exista en el servidor. |
| `ChatThread.authorId` / `authorName` | **No viajan.** El servidor los deriva del token (`sub` → `userId`) y de `Enrollment.displayName` (la **etiqueta**, nunca el nombre real — doc 09 §4.3). | Un alumno no puede escribir en nombre de otro. |
| `ChatThread.rev`, `updatedByDevice` | **Nuevos** (`Int = 1`, `String = ""`). | Necesarios para la resolución de conflictos (§5.5). Backwards-compatible: los `threads.json` viejos se leen con los defaults. |
| `ChatMessage.id` | Viaja y es el `clientMessageId` (UUID v4 generado por el cliente). | El servidor **preserva** el id ⇒ no hace falta tabla de mapeo en el cliente. La clave de unicidad en el servidor es `(author_id, client_message_id)`. |
| `ChatMessage.threadId` | **No viaja** en la entrada: se deriva de `clientThreadId`. | Evita que el cliente mande un `threadId` inconsistente. |
| `ChatMessage.rev`, `updatedByDevice` | **Nuevos** (`Int = 1`, `String = ""`). | Igual que en el hilo. |
| `ChatMessage.text` | Se **omite** si el alcance del consentimiento es `METRICS_ONLY`. | El texto no se transmite cuando no se puede ver. |
| `ChatMessage.redacted` | **Nuevo** (`Boolean = false`), sólo en las respuestas del servidor. | Marca "este mensaje tiene métricas pero no texto" (doc 07 §3.3). |

En resumen, **dos campos nuevos** en `Chat.kt` (`rev`, `updatedByDevice`) y
**uno** en la respuesta (`redacted`), todos con default ⇒ ningún archivo JSON
existente se rompe.

**Equivalente del lado del docente:** `POST /v1/courses/{id}/access-log`
(append-only, `AccessLog` de `teacher/07-chats.md` §6).

---

### 3.8 `GET /v1/courses/{courseId}/chats`

**Qué hace:** el docente consulta las conversaciones. **El servidor redacta
antes de servir.**

**Request**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `author` | string | — | `userId` del alumno. Obligatorio para el docente. Un alumno **sólo** puede pedir los suyos (el servidor lo fuerza). |
| `since` | string (opaco) | — | Cursor de chats: "traeme lo que cambió desde acá". |
| `from` / `to` | epoch ms | — | Rango de fechas sobre `createdAt`. |
| `include` | csv | `threads,messages` | `threads` \| `messages` \| `metrics`. |
| `limit` | int | 500 | Máximo de hilos/mensajes por página. |

```http
GET /v1/courses/c-ia-2026/chats?author=u-ana&since=eyJjIjo4ODEyLCJ2IjoxfQ.k3s9d&include=threads,messages
```

**Response 200**

```json
{
  "cursor": "eyJjIjo4ODE0LCJ2IjoxfQ.k3s9e",
  "hasMore": false,

  "threads": [
    {
      "id": "c-ia-2026|t-9f2",
      "courseId": "c-ia-2026",
      "authorId": "u-ana",
      "authorName": "Ana Ruiz",
      "title": "Duda de parcial",
      "createdAt": 1773012000000,
      "updatedAt": 1773012340000,
      "modelLabel": "gpt-oss-20b",
      "tags": ["parcial"],
      "messageCount": 12,
      "origin": "HOSTED",
      "redacted": false
    }
  ],

  "messages": [
    {
      "id": "m-1a2b3c4d5e6",
      "threadId": "c-ia-2026|t-9f2",
      "role": "user",
      "text": "¿Me explicás SVM con margen?",
      "createdAt": 1773012100000,
      "sources": [],
      "citation": null,
      "toolCalls": [],
      "latencyMs": 0,
      "tokensIn": 24,
      "tokensOut": 0,
      "redacted": false
    },
    {
      "id": "m-2b3c4d5e6f7",
      "threadId": "c-ia-2026|t-9f2",
      "role": "assistant",
      "text": "",
      "redacted": true,
      "createdAt": 1773012340000,
      "sources": ["corpus:materiales/Apuntes-01.pdf"],
      "citation": { "verified": 2, "unsupported": 0, "unknown": 0 },
      "toolCalls": [],
      "latencyMs": 2140,
      "tokensIn": 118,
      "tokensOut": 412
    }
  ],

  "removed": [],
  "visibility": { "effective": "METRICS_ONLY", "showsText": false, "showsMetrics": true }
}
```

**La regla de redacción, en el servidor:**

```
texto_enviado =
    si visibility_efectiva(observador, alumno).showsText  →  el texto real
    si no, pero .showsMetrics                              →  "" con redacted = true
    si no                                                   →  el mensaje no aparece
```

`visibility_efectiva = min(tope_por_rol(observador), alumno.visibilityCap,
alumno.visibility)` — exactamente la fórmula de `03-inscripciones.md` §5.3
(`AccessControl.effectiveVisibility`). El servidor **implementa esa misma
fórmula**; el cliente del docente la vuelve a aplicar como verificación
(por si el servidormintió o si el docente importó un paquete).

> **Por qué `text: ""` y no omitir el mensaje:** el docente necesita las
> **métricas** (citas, latencia, tokens, fuentes) aunque no pueda leer el texto.
> `teacher/07-chats.md` §3.3 define exactamente esta vista: ficha por mensaje
> sin texto.

**Respuestas y errores**

| Situación | HTTP | `code` |
|---|---|---|
| Normal | 200 | — |
| Un alumno pide `author` de otro | 403 | `forbidden` |
| El observador no puede ver ni métricas | 200 | respuesta vacía + `visibility.effective = "NONE"` (no es un error: es una respuesta correcta) |
| Un alumno pide su propio `author` | 200 | sus propios chats (derecho propio) |
| Hilo puntual | 200 | `GET /v1/courses/{id}/chats/{threadId}?limit=200&after=<seq>` |
| El hilo no existe | 404 | `thread_not_found` |
| Cursor vencido | 410 | `cursor_expired` |

**Para leer un hilo puntual que exige texto** (`GET .../chats/{threadId}`), si la
visibilidad no lo permite, el servidor responde `403 visibility_denied` en vez
de devolver un hilo vacío: es más claro para la UI.

---

### 3.9 `GET /v1/courses/{courseId}/inscriptions`

**Qué hace:** devuelve el padrón (docente) o la inscripción propia (alumno).

**Request**

| Parámetro | Tipo | Default | Descripción |
|---|---|---|---|
| `since` | string (opaco) | — | Cursor del padrón. |
| `role` | `TITULAR`\|`AYUDANTE`\|`ALUMNO` | — | Filtro. |
| `status` | `EnrollmentStatus` | — | Filtro. |

```http
GET /v1/courses/c-ia-2026/inscriptions?since=eyJyIjo0NSwiciI6MX0.k3s9a&role=ALUMNO
```

**Response 200 (docente)**

```json
{
  "cursor": "eyJyIjo0OCwiciI6MX0.k3s9b",
  "hasMore": false,
  "enrollments": [
    {
      "id": "c-ia-2026|u-ana",
      "courseId": "c-ia-2026",
      "userId": "u-ana",
      "displayName": "Ana Ruiz",
      "role": "ALUMNO",
      "status": "ACTIVE",
      "joinedAt": 1770000000000,
      "acceptedAt": 1770000600000,
      "leftAt": 0,
      "lastAccessAt": 1770500000000,
      "visibility": "METRICS_ONLY",
      "visibilityCap": "FULL",
      "aiMode": "LOCAL",
      "declaredLocalAiAt": 1770100000000,
      "lastImportAt": 0,
      "events": []
    }
  ],
  "removed": [],
  "summary": { "students": 500, "active": 487, "pending": 9, "suspended": 4 }
}
```

**Response 200 (alumno, pide el padrón completo)** → `403 roster_forbidden`.

**Response 200 (alumno, `GET .../inscriptions/me`)** → **sólo su inscripción**:

```json
{
  "enrollment": {
    "id": "c-ia-2026|u-ana",
    "courseId": "c-ia-2026",
    "userId": "u-ana",
    "displayName": "Fantasma",
    "role": "ALUMNO",
    "status": "ACTIVE",
    "joinedAt": 1770000000000,
    "visibility": "METRICS_ONLY",
    "visibilityCap": "FULL",
    "aiMode": "LOCAL",
    "declaredLocalAiAt": 1770100000000
  },
  "effectiveVisibilityForTeacher": "METRICS_ONLY"
}
```

**El endpoint que escribe la declaración del alumno:**

```http
PUT /v1/courses/c-ia-2026/inscriptions/me
Authorization: Bearer sync_t_9f2a…
Idempotency-Key: 8f3c-…
```

```json
{
  "visibility": "METRICS_ONLY",
  "aiMode": "LOCAL",
  "declaredLocalAiAt": 1770100000000,
  "displayName": "Fantasma"
}
```

**Response 200**

```json
{
  "enrollment": {
    "id": "c-ia-2026|u-ana",
    "visibility": "METRICS_ONLY",
    "aiMode": "LOCAL",
    "displayName": "Fantasma",
    "updatedAt": 1773012400000,
    "version": 7
  },
  "effectiveVisibilityForTeacher": "METRICS_ONLY"
}
```

**Reglas del endpoint `me`:**

1. El alumno puede **bajar** su `visibility` en cualquier momento (y surte
   efecto **inmediato**: el servidor deja de servir el texto).
2. El alumno **no puede subir** su `visibility` por encima de lo que el docente
   ya le impuso (`visibilityCap`): `422 validation_failed` con el mensaje
   *"El docente limitó tu visibilidad; no se puede subir por encima."*
3. El `displayName` que se manda es **la etiqueta** (`Enrollment.displayName`),
   nunca el nombre real: el cliente lo calcula con `IdentityRules.resolveDisplayName`.
4. Cambiar `visibility` a `NONE` **deja de servir** el texto de los chats ya
   subidos, de inmediato. No se borran: el dato queda retenido por el servidor
   con el alcance de retención de §8.4.

---

### 3.10 Endpoints del lado docente (push)

No son el foco del encargo, pero el contrato los necesita para que el docente
pueda publicar.

| Endpoint | Request | Response | Errores |
|---|---|---|---|
| `PUT /v1/courses/{id}` | Cuerpo = `Course` completo + `version` (optimistic lock) | `{ "course": {...}, "version": 13 }` | `409 conflict` si `version` no es la actual; `403 forbidden` si no es titular (`EDIT_MODEL_CONFIG`); `422` |
| `POST /v1/courses/{id}/materials` | `multipart/form-data`: `metadata` (JSON `Material`) + `file` (binario) | `{ "material": {...}, "contentHash": "sha256:…", "extraction": { "status": "READY", "textBytes": 61440 } }` | `413` si el archivo supera el tope; `422` |
| `PATCH /v1/courses/{id}/materials/{materialId}` | Campos parciales (`title`, `tags`, `visible`, `extractedText`) | `{ "material": {...}, "version": 4 }` | `404`, `409`, `403` |
| `DELETE /v1/courses/{id}/materials/{materialId}` | — | `204` | `404`; el material queda como tombstone 30 días |
| `PATCH /v1/courses/{id}/inscriptions/{enrollmentId}` | Rol, estado, `visibilityCap` | `{ "enrollment": {...}, "version": 8 }` | `403 forbidden` (un ayudante no puede cambiar rol ni bajar `visibilityCap`: `REDUCE_STUDENT_VISIBILITY` es del titular); `409 conflict` |
| `POST /v1/courses/{id}/access-log` | `AccessLog[]` (append-only) | `{ "accepted": 12 }` | `413` |
| `GET /v1/courses/{id}/corpus?version=b7f3` | — | `application/gzip` (tar.gz del corpus visible) | `404` si cambia de versión; `403` |

El **`corpus`** es el bundle comprimido de la §9.

### 3.11 Tabla resumen de endpoints

| Método | Ruta | Rol | Auth | Idempotente | Cursor |
|---|---|---|---|---|---|
| `GET` | `/v1/courses/{id}/manifest` | todos | `syncToken` | sí (ETag) | `since` → `cursor` |
| `GET` | `/v1/courses/{id}/materials` | todos | `syncToken` | sí (ETag) | `since` → `cursor` |
| `GET` | `/v1/courses/{id}/materials/{mid}/content` | todos | `syncToken` | sí (ETag) | no |
| `GET` | `/v1/courses/{id}/corpus` | todos | `syncToken` | sí (versionado) | no |
| `POST` | `/v1/courses/{id}/chats` | alumno | `syncToken` (`chats:write`) | **sí** (`clientMessageId`) | devuelve `cursor` |
| `GET` | `/v1/courses/{id}/chats` | docente | `syncToken` (`chats:read`) | sí (ETag) | `since` → `cursor` |
| `GET` | `/v1/courses/{id}/chats/{tid}` | docente | `syncToken` | sí (ETag) | `after` |
| `GET` | `/v1/courses/{id}/inscriptions` | docente | `syncToken` (`roster:read`) | sí (ETag) | `since` → `cursor` |
| `GET` | `/v1/courses/{id}/inscriptions/me` | alumno | `syncToken` | sí | no |
| `PUT` | `/v1/courses/{id}/inscriptions/me` | alumno | `syncToken` | sí (`Idempotency-Key`) | no |
| `PUT` | `/v1/courses/{id}` | docente (titular) | `sessionToken` | no (`version`) | no |
| `POST` | `/v1/courses/{id}/materials` | docente | `sessionToken` | sí (`Idempotency-Key`) | no |
| `PATCH` | `/v1/courses/{id}/materials/{mid}` | docente | `sessionToken` | no (`version`) | no |
| `DELETE` | `/v1/courses/{id}/materials/{mid}` | docente | `sessionToken` | sí | no |
| `PATCH` | `/v1/courses/{id}/inscriptions/{eid}` | docente | `sessionToken` | no (`version`) | no |
| `POST` | `/v1/courses/{id}/access-log` | docente | `sessionToken` | sí (`Idempotency-Key`) | no |
| `POST` | `/v1/join` | — | `code` de cátedra | sí | no |
| `POST` | `/v1/auth/exchange` | — | `code` SSO | sí | no |
| `POST` | `/v1/credentials/rotate` | todos | `sessionToken` \| `syncToken` | sí | no |
| `POST` | `/v1/chat/completions` | alumno | `inferenceKey` | no | no |

---

## 4. El cursor de sincronización

### 4.1 Modelo del log de cambios

El servidor mantiene, **por cátedra**, un log append-only:

```sql
CREATE TABLE course_changes (
  course_id   TEXT     NOT NULL,
  seq         INTEGER  NOT NULL,      -- monotónico por cátedra
  kind        TEXT     NOT NULL,      -- 'course' | 'material' | 'enrollment' | 'thread' | 'message' | 'accesslog'
  entity_id   TEXT     NOT NULL,
  op          TEXT     NOT NULL,      -- 'upsert' | 'remove'
  actor_id    TEXT     NOT NULL,
  at          INTEGER  NOT NULL,
  PRIMARY KEY (course_id, seq)
);
```

`seq` es **monotónico y sin huecos dentro de la cátedra**. Cada escritura
(por más pequeña que sea) consume un `seq`. El manifiesto es una vista del log
en su `seq` actual.

Los cursores son **por recurso**, pero todos apuntan al **mismo log**: cada
endpoint devuelve el `seq` más alto que incluyó en la respuesta. Así el cliente
pide "materiales desde X" y "roster desde Y" de forma independiente, sin
cursores cruzados ni inconsistencias.

### 4.2 Formato del cursor

```
cursor = base64url(payload) + "." + base64url(hmacSha256(payload, serverKey)[0..8])
```

`payload` (JSON canónico, sin espacios):

```json
{"v":1,"c":"c-ia-2026","s":8814,"e":1775604345000}
```

| Campo | Significado |
|---|---|
| `v` | versión del formato del cursor (1) |
| `c` | `courseId`: ata el cursor a una cátedra ⇒ un cursor de la cátedra A no sirve en la B |
| `s` | `seq`: "todo lo que cambió después de X" |
| `e` | expiración (epoch ms) |

**El cliente trata el cursor como opaco**: lo guarda y lo manda. Nunca lo
interpreta, nunca lo construye. (Se puede *decodificar* con fines de
diagnóstico, pero ninguna decisión del cliente depende de eso.)

**El servidor valida:**
1. Firma HMAC correcta ⇒ el cursor lo emitió este servidor.
2. `c == courseId` de la ruta ⇒ no hay replay entre cátedras.
3. `e > now` ⇒ no vencido.
4. `s <= seq_actual` ⇒ no apunta al futuro.

Un cursor que falla 1, 2 o 4 es `400 malformed_request`. Uno que falla 3 es
`410 cursor_expired`.

### 4.3 Ciclo de vida y expiración

| Aspecto | Valor | Motivo |
|---|---|---|
| Validez por defecto | **30 días** | Cubre una cursada completa sin invalidar. |
| Renovación | Cada respuesta devuelve un cursor nuevo (`cursor`), aunque no haya cambios. | El cliente renueva el plazo con cada sincronización. |
| Cursor vencido | `410 cursor_expired` → el cliente hace **sincronización completa** | El servidor ya no tiene el log. |
| Poda del log | Al expirar un cursor se podan las filas con `seq <= s_min_vigente` | El log no crece sin límite. |

**Sincronización completa (el camino de recuperación):**

1. `GET /manifest` **sin** `since` → manifiesto completo (course + los 80
   materiales + `corpus.version`).
2. El cliente compara material por material con su `materials.json` local,
   usando `textHash` / `contentHash`:
   - igual ⇒ no descarga nada,
   - distinto ⇒ descarga el texto nuevo (`variant=text`),
   - ausente localmente ⇒ lo descarga.
3. Reconstruye el índice BM25 local.
4. Borra los materiales que el manifiesto ya no lista.

Esto es **idempotente y barata**: como compara por hash, un alumno que pierde
el cursor no re-descarga 200 MB, descarga **lo que cambió**.

### 4.4 Almacenamiento en el cliente

**Archivo:** `courses/<courseId>/sync.json` (un archivo por cátedra, junto al
resto del estado de la cátedra).

```json
{
  "courseId": "c-ia-2026",
  "serverId": "srv-unlp",
  "serverBaseUrl": "https://kitoru.unlp.edu.ar",
  "enrollmentId": "c-ia-2026|u-ana",
  "cursors": {
    "manifest":  "eyJzZSI6ODg0OCwidiI6MX0.k3s9e",
    "materials": "eyJtIjo0NSwicyI6MX0.k3s9b",
    "roster":    "eyJyIjo0OCwiciI6MX0.k3s9b",
    "chats":     "eyJjIjo4ODE0LCJ2IjoxfQ.k3s9e"
  },
  "corpusVersion": "b7f3",
  "lastSyncAt": 1773012400000,
  "lastPullAt": 1773012400000,
  "lastPushAt": 1773012390000,
  "lastError": "",
  "consecutiveErrors": 0,
  "pendingOutbox": 0
}
```

**Reglas de escritura:**

1. Se escribe **atómicamente**: archivo temporal + `rename` (el `FileSystem` de
   `kitoru.platform` necesita un `writeBytes` + `rename`; si no, se escribe
   `.tmp` y se copia). Si el archivo se corrompe, `JsonStore` devuelve el valor
   vacío → el cliente hace una sincronización completa (no se rompe).
2. Se escribe **después** de aplicar los cambios, nunca antes: si se corrompe,
   lo peor que pasa es una sincronización completa.
3. **Nunca** se borra un cursor al que le queda un outbox pendiente de esa
   misma dirección: si hay chats sin subir, el cursor de `chats` del cliente
   **no avanza** (o se guarda aparte el `lastAckedCursor`).

**Tipos propuestos** (`:core`, `commonMain`):

```kotlin
// core/src/commonMain/kotlin/kitoru/sync/SyncState.kt
@Serializable
data class SyncState(
    val courseId: String,
    val serverId: String = "",
    val serverBaseUrl: String = "",
    val enrollmentId: String = "",
    /** cursor opaco por recurso. */
    val cursors: Map<String, String> = emptyMap(),
    /** versión del corpus descargada ("b7f3"). */
    val corpusVersion: String = "",
    val lastSyncAt: Long = 0L,
    val lastPullAt: Long = 0L,
    val lastPushAt: Long = 0L,
    val lastError: String = "",
    val consecutiveErrors: Int = 0,
    val pendingOutbox: Int = 0,
)
```

El recurso es una `String` y no un enum a propósito: agregar un recurso nuevo
no debe requerir migrar archivos. Si se quiere tipado, se usa un enum con
`@JsonNames` y un `valueOf` tolerante.

---

## 5. Offline-first: la bandeja de salida (outbox)

### 5.1 Modelo

**Archivo:** `courses/<courseId>/outbox.json`.

```json
[
  {
    "id": "op-7f3c1a2b",
    "kind": "APPEND_MESSAGES",
    "clientThreadId": "t-9f2",
    "clientMessageId": "m-2b3c4d5e6f7",
    "createdAt": 1773012340000,
    "attempts": 0,
    "lastAttemptAt": 0,
    "nextAttemptAt": 0,
    "lastError": "",
    "dead": false,
    "payload": { "…": "el objeto a enviar, idéntico al de la API" }
  }
]
```

```kotlin
// core/src/commonMain/kotlin/kitoru/sync/Outbox.kt
@Serializable
enum class OutboxKind {
    /** Alta o edición de un hilo (título, tags, modelo). */
    UPSERT_THREAD,
    /** Mensajes nuevos (append-only). */
    APPEND_MESSAGES,
    /** Declaración del alumno (visibilidad, modo de IA, etiqueta). */
    SET_VISIBILITY,
    /** Subida manual de chats con IA local, con consentimiento. */
    SHARE_LOCAL_CHATS,
    /** Auditoría de accesos del docente. */
    APPEND_ACCESS_LOG,
}

@Serializable
data class OutboxEntry(
    val id: String,                    // UUID v4 de la operación
    val kind: OutboxKind,
    val clientThreadId: String = "",
    /** Clave de deduplicación contra el servidor. */
    val clientMessageId: String = "",
    val createdAt: Long = 0L,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val nextAttemptAt: Long = 0L,
    val lastError: String = "",
    /** La entrada no se reintenta más: hay que resolverla a mano. */
    val dead: Boolean = false,
    val payload: JsonObject = JsonObject(emptyMap()),
)
```

**El outbox se escribe en la misma operación que guarda el mensaje local.**
Orden físico:

1. Se genera el `clientMessageId` (UUID v4) y se guarda el `ChatMessage` en
   `messages.json` **con** su `clientMessageId`.
2. Se agrega la entrada al `outbox.json`.
3. Si hay red, se dispara el vaciado del outbox en segundo plano.

Si la app se cierra entre (1) y (2), el mensaje queda local sin subir: la
siguiente apertura lo detecta comparando `messages.json` contra `outbox.json` y
re-encola lo que falte (esta es la razón por la que el `clientMessageId` vive
**dentro** del mensaje y no sólo en el outbox).

### 5.2 Orden

1. **FIFO estricto** por `createdAt`, desempatado por `id`.
2. **Regla dura:** un `UPSERT_THREAD` va **antes** que los `APPEND_MESSAGES` de
   ese hilo. Se garantiza con una **clave de ordenación de dos partes**:
   `(createdAt, kind.priority)`, donde `UPSERT_THREAD` tiene prioridad 0 y
   `APPEND_MESSAGES` prioridad 1.
   - Corolario: si el mensaje se creó **antes** que la entrada de hilo (caso
     normal, porque el hilo se encola al terminar la conversación), la
     prioridad lo pone **después** del `UPSERT_THREAD` de todos modos. Es
     decir: **el hilo siempre se sube antes que sus mensajes**, sin importar
     la marca de tiempo.
   - El servidor además **crea el hilo on demand** cuando llega un mensaje cuyo
     `clientThreadId` no existe: la regla 2 es una cortesía para que el hilo
     lleve su título y sus tags completos, no un requisito de correctitud.
3. **Agrupación:** las entradas del mismo `clientThreadId` se agrupan en el
   mismo lote cuando es posible, para no fragmentar.
4. **Lote:** hasta **100 entradas** o **512 KB**, lo que se alcance antes.
5. **Lotes secuenciales**, nunca en paralelo: el orden es parte del contrato.
6. El vaciado **se detiene** en el primer error transitorio (no saltea entradas):
   reintenta ese lote con backoff. Un error permanente (`rejected`) marca esa
   entrada como *dead* y **sigue** con las demás.

### 5.3 Idempotencia

**Clave:** `clientMessageId`, un UUID v4 que el cliente genera **al crear el
mensaje** y que viaja dentro del mensaje. El servidor mantiene el índice único:

```sql
CREATE UNIQUE INDEX ux_chat_message_author_cmid
  ON chat_messages (author_id, client_message_id);
```

| Situación | Respuesta del servidor | Efecto en el cliente |
|---|---|---|
| Primer POST | `accepted: [{status:"created"}]` | La entrada sale del outbox |
| Reintento tras timeout (el servidor **sí** guardó) | `duplicates: [{status:"duplicate"}]` | La entrada sale del outbox. **No se duplica nada.** |
| Reintento tras timeout (el servidor **no** guardó) | `accepted: [{status:"created"}]` | Idem |
| Dos dispositivos del mismo alumno suben el mismo mensaje | `duplicates` | Idempotente por construcción |

Lo mismo para los hilos: la clave es `clientThreadId` (el servidor usa
`courseId|clientThreadId` como id), y el `POST` de un hilo existente se
interpreta como **revisión** (§5.5).

**`Idempotency-Key`:** además, cada POST lleva `Idempotency-Key: <outbox.id>`
(cabecera HTTP estándar). Si el servidor ve la misma clave dentro de las
últimas 24 h, devuelve el **mismo cuerpo** de respuesta sin reprocesar. Es una
banda de seguridad adicional para el nivel HTTP.

### 5.4 Reintentos

| Error | Acción | Espera |
|---|---|---|
| Red caída / timeout | Reintentar | Backoff exponencial: 2 s, 4 s, 8 s, 16 s, 32 s, 60 s (tope) |
| `429 rate_limited` | Reintentar | `Retry-After` de la respuesta; si no viene, backoff |
| `500`, `503` | Reintentar | Backoff |
| `401 unauthorized` | **Re-autenticar** (§6.3) y reintentar 1 vez | Inmediato |
| `409 conflict` | **No** reintentar: abrir la resolución de conflicto (§5.5) | — |
| `403`, `422`, `404`, `410` | **Dead letter**: la entrada queda marcada y la UI avisa | — |
| `413 payload_too_large` | Dividir el lote a la mitad y reintentar | Inmediato |

- **Máximo 8 intentos** por entrada. Después pasa a `dead = true`.
- La UI muestra un badge: *"3 cambios pendientes no se pudieron subir"*, con
  un botón **Reintentar** y otro **Descartar** (con confirmación).
- Las *dead letters* se conservan 30 días en `outbox.json` y luego se purgan
  (guardando un resumen en `config/sync_errors.json`).

**Disparadores del vaciado:**

1. Al abrir la app, si hay red.
2. Al terminar una respuesta del chat (se encola y se sube).
3. Al volver de segundo plano (en Android: `ConnectivityManager`).
4. Cada N minutos mientras la app está abierta y hay red.
5. A demanda: botón **"Subir ahora"**.

**Restricción de la arquitectura Android:** el vaciado corre en un
`WorkManager`/`coroutineScope` de foreground **con red**. Si la app está en
segundo plano, se sigue subiendo (el outbox es chico); si el proceso muere, el
outbox queda en disco y se reanuda al volver a abrir.

### 5.5 Conflictos: el alumno editó un chat en dos dispositivos

Este es el caso central. La respuesta corta: **los mensajes nunca se pisan;
los metadatos sí, y el servidor no resuelve solo cuando hay dudas.**

#### 5.5.1 Taxonomía

| Tipo | Ejemplo | Resolución |
|---|---|---|
| **A. Mensajes nuevos en dos dispositivos** | El dispositivo A y el B crean mensajes distintos del mismo hilo | **Sin conflicto**: `clientMessageId` distinto ⇒ son mensajes distintos. El servidor los **fusiona** y los ordena por `createdAt`. Si dos mensajes quedan a más de 5 min de diferencia con `createdAt` invertido, el servidor marca `clockSkew: true` en la respuesta y el cliente avisa: *"Algunas fechas de este chat no coinciden entre dispositivos."* |
| **B. Edición del texto de un mensaje ya subido** | Se edita un mensaje en A y en B | **Conflicto explícito** (§5.5.2) |
| **C. Título y tags del hilo** | Se renombra el hilo en A y en B | **LWW con guarda de desfase** (§5.5.3) |
| **D. Borrado vs. edición** | Se borra el hilo en A, se edita en B | **Gana el borrado**: tombstone (§5.5.4) |
| **E. Visibilidad vs. cualquier cosa** | El alumno baja la visibilidad en un dispositivo | **Gana la visibilidad**: es el techo del alumno y el servidor la aplica de inmediato en todos los dispositivos (§5.5.5) |

#### 5.5.2 Edición de un mensaje (caso B)

Cada mensaje lleva `rev` (empieza en 1) y `updatedByDevice`.

- El cliente envía `rev` y el servidor compara con el suyo.
- Si `rev == serverRev + 1` ⇒ es la revisión siguiente ⇒ **se acepta**
  (`status: "revised"`).
- Si `rev <= serverRev` ⇒ el servidor ya tiene una versión más nueva ⇒
  **conflicto**: responde en el array `conflicts` con **ambas** versiones
  (la del servidor y la del cliente), y **no** aplica el cambio.

**Resolución en el cliente** (diálogo "Hay dos versiones de este mensaje"):

```
┌──────────────────────────────────────────────────────────────┐
│  Hay dos versiones de este mensaje                          │
│  Editado en "Notebook" (14/03 10:15) y en "Teléfono" (…)   │
│                                                              │
│  ┌── Notebook ──────────────┐  ┌── Teléfono ──────────────┐  │
│  │ …texto de la versión A…  │  │ …texto de la versión B…  │  │
│  └──────────────────────────┘  └──────────────────────────┘  │
│                                                              │
│  [ Conservar la del Notebook ]  [ Conservar la del Teléfono ] │
│  [ Combinar… ]                              [ Cancelar ]      │
└──────────────────────────────────────────────────────────────┘
```

- "Conservar la mía" ⇒ envía `rev = serverRev + 1` con `force: true`; el
  servidor acepta y sobrescribe.
- "Conservar la del otro" ⇒ adopta la versión del servidor localmente y
  descarta el outbox.
- "Combinar" ⇒ abre un editor con el texto del servidor y el del cliente
  marcados; el alumno edita y se envía como nueva revisión.
- "Cancelar" ⇒ se conservan ambas en el outbox (queda pendiente) hasta que el
  alumno decida.

El servidor **guarda las 3 últimas revisiones** de cada mensaje durante 7 días:
permite recuperar un texto que el alumno haya pisado por error.

#### 5.5.3 Título y tags del hilo (caso C)

**LWW por reloj del cliente, con guarda de desfase:**

1. El servidor compara `thread.updatedAt` (reloj del cliente) con
   `server.updatedAt` (reloj del servidor, o sea, cuándo llegó).
2. Si `|clientUpdatedAt - serverReceivedAt| <= 5 min` ⇒ no hay sospechas de
   relojes desfasados ⇒ **gana el `updatedAt` mayor** (LWW simple). El cliente
   que pierde recibe en la respuesta un `superseded: [{entity:"thread", …}]`
   y muestra un banner no bloqueante:
   > "El título de este chat cambió en otro dispositivo."
3. Si la diferencia supera los 5 minutos ⇒ **conflicto explícito**: el servidor
   devuelve `conflicts` con las dos versiones y el cliente abre el diálogo
   (como el caso B, pero con un solo campo).

**Por qué no "último que llega gana":** un dispositivo offline con el reloj
corrido podría pisar un título nuevo con uno viejo. La guarda de desfase
convierte ese caso raro en una pregunta al usuario en vez de una pérdida
silenciosa.

El servidor guarda la **versión anterior** del hilo durante 7 días, para
poder hacer "restaurar".

#### 5.5.4 Borrado (caso D)

- El borrado de un hilo es un **tombstone**: `deletedAt` + `deletedByDevice`.
- El tombstone **viaja** a todos los dispositivos del alumno por el cursor de
  `chats` (aparece en el array `removed`).
- Un mensaje que llega para un hilo con tombstone ⇒ `409 thread_deleted` ⇒
  dead letter con el mensaje *"Este chat fue borrado en otro dispositivo."*
- El tombstone se purga a los 30 días (el contenido, antes: 7 días).
- **Borrar en A no borra en B hasta que B sincroniza**: la UI lo aclara:
  *"Se borrará también en tus otros dispositivos cuando sincronicen."*

#### 5.5.5 Visibilidad (caso E)

La declaración del alumno **nunca** entra en conflicto:

- El servidor guarda `Enrollment.visibility` con la última escritura
  (**la del cliente gana**, no hay LWW por reloj: es un dato de ceilings, y
  bajarlo siempre es más seguro).
- Bajar la visibilidad surte efecto **inmediato** en el servidor: los chats
  dejan de servirse al docente en la siguiente petición.
- El cliente que está "behind" recibe la inscripción nueva en el delta del
  roster y actualiza su UI.
- El docente **nunca** puede subir la `visibility` del alumno por encima de
  lo declarado (sólo bajarla con `visibilityCap`).

#### 5.5.6 Tabla resumen

| Dato | Estrategia | Pierde algo | Se avisa |
|---|---|---|---|
| Mensajes nuevos | Union (append-only) | No | Sólo si hay desfase de reloj |
| Texto de un mensaje editado | Conflicto explícito + 3 revisiones | No | Sí (diálogo) |
| Título/tags del hilo | LWW + guarda de desfase | A veces (5 min de reloj) | Sí (banner) |
| Borrado | Tombstone | No | Sí (banner) |
| Visibilidad | El cliente gana, efecto inmediato | No | No hace falta |
| `modelLabel` / `effectiveModel` | LWW (metadato de máquina) | No | No |

---

## 6. Autenticación: tres papeles, tres tokens

### 6.1 Los tres tokens

| Token | Para qué | Alcance | Dónde vive en el cliente | Vida |
|---|---|---|---|---|
| **`inferenceKey`** | Llamar al **modelo** (`POST /v1/chat/completions`) | `model:invoke` en el modelo de la cátedra | `CredentialStore` ref `apikey:<serverId>:<courseId>` | Emitida por el servidor; **vence al terminar la cursada** (CONTEXTO) |
| **`syncToken`** | Toda la **API de sincronización** | `chats:read`, `chats:write`, `materials:read`, `roster:read` (sólo el propio), `course:read` | `CredentialStore` ref `sync:<serverId>:<courseId>` | 90 días, renovable |
| **`sessionToken`** | Sesión **SSO** del docente | `course:write`, `materials:write`, `roster:write`, `accesslog:write` | `CredentialStore` ref `session:<serverId>` | 1 h + refresh token |

### 6.2 ¿La misma key para inferencia y sincronización? **No.**

**Por qué separarlas:**

| Si la `inferenceKey` sirviera para sincronizar… | Consecuencia |
|---|---|
| El alumno podría leer el padrón de la cátedra | Filtración de datos de terceros (viola la regla 6 del proyecto) |
| El alumno podría subir chats con el `authorId` de otro | **Falsificación de evidencia académica**: el docente vería conversaciones que el alumno no tuvo |
| El alumno podría cambiar la configuración de la cátedra | Un alumno borra el material de la cátedra |
| La key se comparte en el bundle `.kitoru` del docente | El bundle se convierte en un instrumento de intrusión en otras cátedras |
| El servidor no puede revocar "leer chats" sin cortar la inferencia | El alumno no puede renovar su permiso de modelo sin perder el de datos |

**Regla dura del servidor:**

```
El endpoint /v1/chat/completions   acepta SOLO inferenceKey.
Todos los endpoints /v1/courses/*   aceptan SOLO syncToken o sessionToken.
Una inferenceKey en /v1/courses/*  → 401 unauthorized.
Un syncToken en /v1/chat/completions → 401 unauthorized.
```

Además, el `syncToken` de un **alumno** tiene `roster:read` limitado a su
propia inscripción, y `chats:write` limitado a su propio `authorId`: el
servidor **ignora** cualquier `authorId` del cuerpo y usa el del token. Un
alumno no puede escribir en nombre de otro **aunque el endpoint lo permita
estructuralmente**.

### 6.3 Emisión

**Momento 1 — el alumno se inscribe con el código:**

```http
POST /v1/join HTTP/1.1
Content-Type: application/json
```

```json
{
  "code": "IA7F3K",
  "deviceId": "d-9f2a7c1",
  "alias": { "kind": "PSEUDONYM", "value": "Fantasma" },
  "appVersion": "1.0.0",
  "platform": "desktop"
}
```

```json
{
  "course": {
    "id": "c-ia-2026",
    "code": "IA-2026-1",
    "name": "Inteligencia Artificial",
    "term": "2026-1",
    "allowLocalModels": true,
    "analyticsEnabled": true
  },
  "enrollment": {
    "id": "c-ia-2026|u-ana",
    "role": "ALUMNO",
    "status": "ACTIVE",
    "displayName": "Fantasma",
    "seatNumber": 37,
    "aliasKind": "PSEUDONYM"
  },
  "credentials": {
    "inferenceKey": "ktr_live_9f2a7c1…",
    "syncToken": "sync_t_9f2a7c1…",
    "syncTokenExpiresAt": 1775604345000
  },
  "model": {
    "baseUrl": "https://kitoru.unlp.edu.ar/v1",
    "modelName": "gpt-oss-20b"
  }
}
```

> El `join` de `09-identidad.md` §6.2 devuelve `{course, enrollment, model}`.
> Se le **agrega** `credentials`. Es el único punto donde nacen los dos tokens
> del alumno. El `inferenceKey` **no** se persiste en `profiles.json` ni en
> `courses.json`: va al `CredentialStore`.

**Momento 2 — el docente se vincula con la institución (SSO):**

```http
POST /v1/auth/exchange
{ "code": "…", "redirectUri": "kitoru://cb", "state": "…" }
```

```json
{
  "accessToken": "sess_…",
  "refreshToken": "sess_r_…",
  "expiresAt": 1773016000000,
  "user": { "id": "u-doc-1", "displayName": "Dra. Pérez", "institutions": ["unlp"] },
  "courses": [
    { "courseId": "c-ia-2026", "role": "TITULAR", "enrollmentId": "c-ia-2026|u-doc-1" }
  ]
}
```

El docente **no** recibe `inferenceKey` por SSO: si quiere usar el modelo en su
propio cliente, pide una key desde la consola del servidor (o el servidor se la
emite al crear la cátedra).

**Momento 3 — rotación:**

```http
POST /v1/credentials/rotate HTTP/1.1
Authorization: Bearer sync_t_9f2a…        (o sessionToken)
Idempotency-Key: <uuid>
```

```json
{ "reason": "ROTATE_SYNC_TOKEN" }
```

```json
{
  "inferenceKey": "ktr_live_nuevo…",
  "syncToken": "sync_t_nuevo…",
  "syncTokenExpiresAt": 1778184345000
}
```

El token viejo se **revoca en el acto** (`jti` en una lista de revocación en
memoria + persistida). El cliente reemplaza el secreto en el `CredentialStore`
y descarta el `syncToken` viejo.

**Por qué el `syncToken` expira (90 días) y la `inferenceKey` no:**

- La `inferenceKey` es de **consumo**: que sobreviva a la cursada es el
  problema, y el servidor la vence por fecha de cursada (la institución la
  provee y caduca).
- El `syncToken` es de **acceso a datos**: caduca para que un token robado en
  un dispositivo perdido no sirva para siempre. 90 días >> una cursada, así
  que en la práctica el alumno nunca lo ve caducar.

### 6.4 Formato del `syncToken`

Opaque para el cliente (JWT firmado por el servidor, con una clave que el
servidor rota; el cliente **nunca** lo decodifica).

```json
{
  "iss": "kitoru-server",
  "sub": "u-ana",
  "aud": "kitoru-sync",
  "jti": "9f2a7c1-…",
  "scp": ["chats:read", "chats:write", "materials:read", "roster:read:self", "course:read"],
  "cid": "c-ia-2026",
  "eid": "c-ia-2026|u-ana",
  "did": "d-9f2a7c1",
  "iat": 1773012345,
  "exp": 1775604345
}
```

| Claim | Uso |
|---|---|
| `sub` | `userId` del actor. **El servidor lo usa como `authorId`**: ignora cualquier autor del cuerpo. |
| `eid` | `Enrollment.id`: ata el token a una inscripción concreta. |
| `cid` | Cátedra. Un token de la cátedra A no sirve en la B. |
| `scp` | Permisos. |
| `did` | `deviceId`: para auditoría ("este token salió de la notebook de Ana"). |

### 6.5 Qué se guarda en el cliente

```
CredentialStore
  apikey:<serverId>:c-ia-2026   → ktr_live_9f2a7c1…
  sync:<serverId>:c-ia-2026     → sync_t_9f2a7c1…
  session:<serverId>            → sess_…   (sólo docentes)
```

**Nunca** en `profiles.json`, `courses.json` ni `enrollments.json`. El `Course`
del bundle `.kitoru` puede llevar una `inferenceKey` **compartida por el
docente a propósito** (caso "material sin servidor"), pero no un `syncToken`.

### 6.6 Manejo de `401` en el cliente

```kotlin
// El cliente reintenta UNA vez tras re-autenticar.
when (response.status) {
    HttpStatusCode.Unauthorized -> {
        rotateSyncToken()          // POST /v1/credentials/rotate
        retryOnce()
    }
    ...
}
```

Si la rotación falla (el token fue revocado desde el servidor), la app **no
borra nada local**: el alumno sigue usando el chat local y la IA local; lo
único que se desactiva es la sincronización, con un aviso honesto:

> "Tu acceso al servidor venció. Tus conversaciones siguen en este dispositivo
> y no se están subiendo."

---

## 7. El alumno con IA local

### 7.1 Por qué no sube nada solo

Con `ModelMode.LOCAL` el modelo corre en el dispositivo del alumno. El chat se
escribe en `config/messages.json` y **no entra al outbox**: el cliente
decide, al encolar, según el modo del modelo del hilo.

```kotlin
// La regla, en una línea:
encolarSiElServidorLo DebeVer = (thread.effectiveModel.mode == ModelMode.REMOTE)
```

Es una regla **estructural**, no un permiso: un alumno con IA local puede
cambiar su `ModelPolicy` a `REMOTE` después, pero el mensaje que ya se generó
con el modelo local **nunca se sube solo**. El hilo guarda `origin = LOCAL` y
`effectiveModel.mode = LOCAL` para siempre.

**El caso HYBRID** (`Enrollment.aiMode = HYBRID`): los hilos con
`effectiveModel.mode == LOCAL` no se suben; los `REMOTE` sí. El outbox
decide por **hilo**, no por inscripción.

### 7.2 El flujo de "subir mi historial manualmente"

Es una acción explícita del alumno, en **Ajustes → Privacidad → "Compartir mis
conversaciones con la cátedra"** (o desde el `⋮` del chat: *"Compartir este
chat"*).

**Paso 1 — el diálogo de consentimiento.** Muestra **exactamente** qué se va a
mandar:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Compartir conversaciones con la cátedra                              │
│                                                                      │
│  Vas a compartir con los docentes de IA-2026-1:                     │
│                                                                      │
│    14 conversaciones · 132 mensajes                                 │
│    del 12/03/2026 al 18/03/2026                                     │
│    generadas con IA local (Phi-4-mini-instruct)                     │
│                                                                      │
│  Qué pueden ver:                                                     │
│    ○ El texto completo de las conversaciones                         │
│    ● Sólo métricas (volumen, calidad de citas, modelos usados)        │
│                                                                      │
│  ⊙ Entiendo que los docentes de la cátedra van a leer este contenido │
│    y que puedo dejar de compartirlo en cualquier momento.            │
│                                                                      │
│  [ Cancelar ]                                    [ Compartir ]       │
└──────────────────────────────────────────────────────────────────────┘
```

Reglas de la UI:

- El botón **Compartir** está deshabilitado hasta que se marca la casilla de
  consentimiento.
- El selector de alcance (`texto` / `métricas`) **arranca en "métricas"**: el
  alumno tiene que hacer un acto extra para compartir el texto.
- Si la inscripción tiene `visibility = NONE`, el diálogo **no permite**
  compartir: muestra *"Tu inscripción está configurada como «Oculto». Cambiá la
  visibilidad en Ajustes si querés compartir."* (con un botón que lleva ahí).
- Si la inscripción tiene `visibility = METRICS_ONLY`, el botón dice
  **"Compartir métricas"** y no ofrece la opción de texto (sería rechazado por
  el servidor de todas formas: §7.3).

**Paso 2 — la construcción del paquete.** El cliente arma, en memoria, un
`ChatSharePackage`:

```kotlin
@Serializable
data class ChatSharePackage(
    val format: String = "kitoru.chat-share",
    val formatVersion: Int = 1,
    val courseId: String,
    val studentId: String,
    val scope: ShareScope,             // FULL | METRICS_ONLY
    val grantedAt: Long,
    val consentVersion: Int = 1,
    val threads: List<ChatThread>,
    val messages: List<ChatMessage>,
    val packageHash: String,            // sha256 del contenido canónico
)
```

`packageHash` se calcula sobre los threads+messages canónicos (JSON estable,
sin `packageHash` dentro). Permite al servidor detectar que el mismo paquete
se subió dos veces (idempotencia a nivel paquete) y al docente auditar qué se
compartió.

**Paso 3 — el envío.** Se encola **una** entrada de outbox
`SHARE_LOCAL_CHATS` con **todas** las entradas del paquete en el payload (o
varios lotes de 100 entradas, si hay más de 100 mensajes). El payload incluye
el consentimiento:

```json
{
  "kind": "message",
  "clientThreadId": "t-loc-1",
  "clientMessageId": "m-loc-0001",
  "origin": "LOCAL_SHARED",
  "consent": {
    "grantedAt": 1773012400000,
    "scope": "METRICS_ONLY",
    "version": 1,
    "packageHash": "sha256:7f3c…",
    "uiText": "Compartido desde «Compartir conversaciones con la cátedra»"
  },
  "message": { "…": "ChatMessage" }
}
```

**Paso 4 — el servidor valida y guarda.**

```
aceptado  ⇔  consent presente
          ∧  consent.version >= 1
          ∧  visibilidad_efectiva(alumno→docente) >= consent.scope
          ∧  enrollment.aiMode ∈ {LOCAL, HYBRID}
```

Si la visibilidad efectiva es `NONE` ⇒ **`403 visibility_none`**, y el cliente
muestra: *"Tu inscripción está en «Oculto»: no se puede subir. Cambiá la
visibilidad en Ajustes."* (El servidor **rechaza** en vez de guardar: guardar
texto que nadie puede leer y que el servidor retiene sería un trato injusto con
el alumno.)

Si la visibilidad efectiva es `METRICS_ONLY` y el `consent.scope` es `FULL` ⇒
el servidor **redacta** y lo acepta:

```json
{
  "kind": "message",
  "clientMessageId": "m-loc-0001",
  "status": "redacted",
  "note": "Se guardaron las métricas; el texto no se almacena porque tu visibilidad es «Sólo métricas»."
}
```

### 7.3 Qué ve el servidor y qué ve el docente

| Dato | Servidor | Docente titular | Ayudante |
|---|---|---|---|
| Texto del chat local **compartido** (`scope=FULL`, visibilidad FULL) | Sí | Sí | No (tope por rol `METRICS_ONLY`) |
| Texto del chat local compartido con `scope=METRICS_ONLY` | **No** (se descarta al recibir) | No | No |
| Métricas del chat local compartido | Sí | Sí | Sí |
| Texto de los chats locales **no compartidos** | No (nunca llegó) | No | No |
| El hecho de que el alumno usa IA local | Sí (`aiMode`) | Sí (badge en el padrón) | Sí |
| `consent` (fecha, alcance, hash del paquete) | Sí | Sí (en la ficha del hilo: "compartido el 18/03") | Sí |

**Regla de retención del texto local compartido:** el servidor lo guarda con la
misma retención que el resto de los chats (fin de cursada + 90 días), y lo
**purga** si el alumno baja la visibilidad a `NONE`.

### 7.4 Registro de consentimiento

Cada subida deja un registro que el alumno puede consultar
(*"¿qué compartí y cuándo?"*):

```
config/consent_log.json   (local, en el dispositivo del alumno)
[
  {
    "at": 1773012400000,
    "courseId": "c-ia-2026",
    "courseName": "Inteligencia Artificial",
    "scope": "METRICS_ONLY",
    "threads": 14,
    "messages": 132,
    "packageHash": "sha256:7f3c…",
    "result": "uploaded"
  }
]
```

El servidor guarda el mismo registro por su parte (`share_consents`), y el
docente lo ve en la ficha del alumno: *"Ana Ruiz · IA local declarada el
12/03 · 14 conversaciones compartidas el 18/03 (solo métricas)"*.

**Revocación:** el alumno puede bajar la visibilidad a `NONE` en cualquier
momento ⇒ el servidor deja de servir el texto **de inmediato**. El cliente lo
dice con todas sus letras:

> "Dejaste de compartir. Tus conversaciones dejan de estar visibles para los
> docentes desde ahora. Lo que ya se había subido queda en el servidor hasta
> el fin de la cursada; si querés que se borre, pedilo al docente."

### 7.5 Relación con el paquete `.kitoru` (importación del docente)

El flujo del §7 es **el camino preferido** (subida directa con consentimiento
en el servidor). El paquete `.kitoru` de `teacher/07-chats.md` §8 es el
**camino alternativo** para cuando **no hay servidor**: el alumno exporta, el
docente importa. Los dos caminos aplican **las mismas reglas de visibilidad**
al importar (doc 03, §6.5):

| `Enrollment.visibility` | Qué hace la importación |
|---|---|
| `FULL` | Importa todo (texto, métricas, citas) |
| `METRICS_ONLY` | Importa **sólo métricas**: `text = ""`, `redacted = true` |
| `NONE` | **Rechaza**: *"Este alumno declaró que no comparte sus conversaciones."* |

En el mundo con servidor, la importación por paquete es un caso **raro**
(inscripción sin servidor en una cátedra con servidor). Sigue soportada.

---

## 8. Costos y límites

### 8.1 Números de partida

| Parámetro | Valor | Nota |
|---|---|---|
| Alumnos por cátedra | **500** | Una cátedra grande. |
| Materiales por cátedra | **80** | Caso del encargo. |
| Tamaño original promedio | 2,5 MB | ⇒ corpus original **200 MB** |
| Texto extraído promedio | 60 KB | ⇒ texto total **4,8 MB** |
| Bundle del corpus (gzip) | **1,4 MB** | Un 3× mejor que el texto crudo (el texto se comprime muy bien). |
| Días de cursada | 30 | |
| Alumno activo | 20 días/mes | |
| Mensajes por alumno | 6/día (3 del alumno + 3 del modelo) ⇒ **120/mes** | |
| Hilos por alumno | 15/mes | |
| Tamaño de un mensaje en JSON | **1,2 KB** | Texto ~700 B + metadatos + citas + tools. |
| Cambios de material | 2/mes | |
| Poll del manifiesto | 4/hora con la app abierta (12 h/día) | |
| Coste de egreso | 0,01 USD/GB (hipótesis) | |

### 8.2 Tráfico por escenario

#### (a) Primera sincronización de un alumno nuevo (80 materiales)

| Ítem | Sin optimizar | **Optimizado (nivel 0)** |
|---|---|---|
| Manifiesto completo | 20 KB | 20 KB |
| Corpus (texto + metadatos) | **200 MB** (los 80 binarios) | **1,4 MB** (bundle gzip) |
| Índice BM25 local | — | CPU local (~3 s, en segundo plano) |
| **Total por alumno** | **200 MB** | **1,4 MB** |
| **Total × 500 alumnos** | **100 GB** | **700 MB** |

**Reducción: 143×.** Y el binario original sigue disponible bajo demanda
(§8.3).

#### (b) Estado estacionario (un mes)

| Ítem | Por alumno/mes | × 500 alumnos |
|---|---|---|
| **Egreso** — poll del manifiesto (304, sin cuerpo) | 960 × 250 B = 240 KB | 120 MB |
| **Egreso** — manifiesto con cambios (delta, ~1 KB) | 20 × 1 KB = 20 KB | 10 MB |
| **Egreso** — delta de materiales (texto cambiado) | 2 × 60 KB = 120 KB | 60 MB |
| **Egreso** — originales, bajo demanda (≈20 % de los alumnos abren 5 archivos) | — | 1,25 GB |
| **Egreso total** | | **≈ 190 MB + 1,25 GB (a demanda)** |
| **Ingreso** — chats del alumno | 120 × 1,2 KB = 144 KB | 72 MB (≈ 30 MB gzip) |
| **Ingreso** — access log del docente | — | ~1 MB |

**Total mensual ≈ 200 MB de egreso + 72 MB de ingreso** para 500 alumnos
activos, más los originales bajo demanda.

**Comparación con la alternativa ingenua** (bajar todo el corpus cada vez que
cambia un material): 500 × 2 × 200 MB = **200 GB/mes**. La técnica de delta
por hash lo baja a 60 MB: **3.300× menos**.

#### (c) Cierre de cursada

El servidor borra lo que corresponde (§8.4). El egreso de ese mes baja a
~0.

### 8.3 Optimizaciones (con su número)

| # | Optimización | Ahorro |
|---|---|---|
| O1 | **Delta por hash**: el cliente compara `textHash`/`contentHash` antes de descargar. | Evita el 99 % del tráfico repetido. |
| O2 | **Nivel 0 / nivel 1**: el texto extraído se descarga siempre; el binario **nunca** en la primera sincronización, sólo cuando el alumno abre el archivo. | 200 MB → 1,4 MB por alumno (−99,3 %). |
| O3 | **Bundle comprimido del corpus**: un `tar.gz` con todos los textos visibles en un request (reanudable con `Range`). | 80 requests → 1; 4,8 MB → 1,4 MB (gzip). |
| O4 | **ETag / 304**: el poll del manifiesto no descarga cuerpo si nada cambió. | 20 KB → 250 B por poll (−99 %). |
| O5 | **El roster no se manda a los alumnos**: el manifiesto de un alumno excluye el padrón; el docente (3 personas) lo recibe completo. | 100 KB/manifesto × 500 alumnos/día → 0. |
| O6 | **Delta del manifiesto**: con `since`, el manifiesto lista **sólo lo que cambió**, no los 80 materiales. | 20 KB → 1 KB por cambio. |
| O7 | **gzip** en JSON y texto (el texto de un PDF extraído comprime 3-4×). | 4,8 MB → 1,4 MB. |
| O8 | **Poll adaptativo**: 5 min con la ventana activa, 30 min en segundo plano, **cero** con la app cerrada. | −60 % de polls frente a un poll fijo. |
| O9 | **Sin push obligatorio**: no se requiere una conexión permanente (SSE/WebSocket). El poll con ETag es robusto en redes de facultad. |
| O10 | **El `syncToken` del alumno no habilita el roster**: el padrón viaja una sola vez al docente. | Seguridad + tráfico. |
| O11 | **CDN/object storage para el bundle**: el corpus es **inmutable** por versión ⇒ `Cache-Control: public, max-age=31536000, immutable`. El egreso del corpus sale del servidor de la API. | Costo ≈ 0. |

### 8.4 Límites del servidor

| Recurso | Límite | Motivo |
|---|---|---|
| Tamaño de un material | 50 MB | Un PDF de cátedra no pasa de eso. |
| Materiales por cátedra | 500 | |
| Tamaño del corpus (texto total) | 200 MB | |
| Entradas por POST de chats | 100 | El cliente divide el lote si excede. |
| Tamaño del POST de chats | 512 KB | |
| Cuerpo máximo | 2 MB | |
| Originales subidos por el docente | 1 por material, se reescribe con PATCH | |
| **Polls del manifiesto** | **1 cada 60 s por token** | `429` + `Retry-After: 60` si se excede. |
| Requests de sync | 120/min por token (burst 240) | |
| **Chats por alumno** | 50 000 mensajes | |
| **Retención de chats** | Hasta el fin de cursada + 90 días | |
| **Retención de materiales** | Hasta el fin de cursada + 180 días | |
| **Retención de tombstones** | 30 días | |
| **Revisiones de un mensaje** | 3 últimas, 7 días | |
| **Cursada vencida** | La `inferenceKey` deja de funcionar; el `syncToken` también | "la institución la provee; vence al terminar la cursada". |
| **Llamadas de inferencia** | Cuota por token (tokens/hora) | Control de costo del modelo. |

### 8.5 Coste de disco del servidor

| Dato | Por cátedra |
|---|---|
| Materiales (originales + texto) | 200 MB + 5 MB |
| Chats (500 alumnos × 400 mensajes × 1,2 KB) | 240 MB/año |
| Log de cambios (podado) | < 50 MB |
| **Total** | **≈ 500 MB por año y por cátedra** |

Con 40 cátedras: **20 GB**. Trivial.

### 8.6 Coste de CPU del cliente

| Operación | Costo | Cuándo |
|---|---|---|
| Indexar el corpus con BM25 (`KnowledgeBase.sync`) | ~3 s para 4,8 MB de texto | En segundo plano, tras la primera sincronización |
| Verificar hashes (SHA-256) de 80 materiales | ~0,2 s | En segundo plano |
| Descomprimir el bundle | ~0,1 s | En segundo plano |

**Ninguna de las tres bloquea la UI.** El chat arranca a funcionar con el
índice anterior (o vacío) mientras se reconstruye (§9.3).

---

## 9. La primera sincronización

### 9.1 Fases

```
t=0.0s   ┌─ FASE 0 · Manifiesto ─────────────────────────────────────┐
         │ GET /v1/courses/{id}/manifest   (sin since)                │
         │ → course config + 80 materiales (metadatos) + corpusVersion │
         └───────────────────────────────────────────────────────────┘
t=0.3s   ┌─ FASE 1 · Corpus (nivel 0) ────────────────────────────────┐
         │ GET /v1/courses/{id}/corpus?version=b7f3                  │
         │ → application/gzip (1,4 MB)  ·  1 request ·  reanudable    │
         │ → courses/<id>/corpus/b7f3/manifest.json + textos          │
         └───────────────────────────────────────────────────────────┘
t=1.8s   ┌─ FASE 2 · Verificación ────────────────────────────────────┐
         │ SHA-256 de cada texto vs textHash del manifiesto           │
         │ → los que no coinciden se vuelven a pedir (raro)          │
         └───────────────────────────────────────────────────────────┘
t=2.0s   ┌─ FASE 3 · Índice (en segundo plano) ──────────────────────┐
         │ KnowledgeBase.sync() → BM25 en memoria (3 s, 1 hilo)      │
         │ La UI muestra "Indexando el material… 34/80"              │
         └───────────────────────────────────────────────────────────┘
t=2.1s   ┌─ FASE 4 · Contenido original (bajo demanda) ──────────────┐
         │ El alumno abre un PDF → GET .../content?variant=original  │
         │ → cache en courses/<id>/files/  ·  sin blockear el chat    │
         └───────────────────────────────────────────────────────────┘
```

### 9.2 Tiempos reales

| Red | Corpus (1,4 MB) | Índice | Total hasta "chat usable" |
|---|---|---|---|
| Wi-Fi de facultad (20 Mbps) | ~0,6 s | 3 s (en paralelo) | **~2 s** |
| 4G (5 Mbps) | ~2,3 s | 3 s | **~3 s** |
| ADSL (1 Mbps) | ~11 s | 3 s | **~12 s** (el chat ya funciona a los 2 s) |
| 3G (400 kbps) | ~28 s | 3 s | **~30 s** |

**"Chat usable"** = el alumno ya puede chatear y recibir respuesta. Eso
requiere sólo la Fase 0 + Fase 1 parcial. El material faltante degrada la
calidad del RAG, no la capacidad de responder.

### 9.3 Sin bloqueos

**Reglas:**

1. **Ninguna fase bloquea la UI.** Todo corre en `Dispatchers.Default` /
   `Dispatchers.IO` con un `Mutex` por curso. La app arranca y es usable
   aunque la red esté lenta o caiga.
2. **Degradación explícita:** un banner de estado, no una pantalla de carga:

   ```
   ┌──────────────────────────────────────────────────────────────┐
   │ ⏳ Sincronizando el material de IA-2026-1… 34/80            │
   │    Podés chatear mientras tanto.                             │
   │                                    [ Pausar ]  [ Ver detalle ]│
   └──────────────────────────────────────────────────────────────┘
   ```

3. **Cancelable:** "Pausar" detiene la sincronización; el cursor **no**
   avanza hasta que la fase termine. Cancelar y volver a empezar es seguro
   (todo es idempotente y comparado por hash).
4. **La indexación es incremental:** el `KnowledgeBase` ya tiene `sync()`; se
   indexa primero lo que ya está en disco y se reindexa cuando llega el resto.
   Un material nuevo ⇒ **reindexado** (3 s para 80 docs es instantáneo).
5. **Sin red:** la primera sincronización **se aplaza**, no falla. El chat
   local funciona con el material que haya (bundle importado, o nada). El
   banner dice: *"Sin conexión. El material de la cátedra no se pudo
   descargar."* y reintenta solo cuando vuelve la red.
6. **El corpus no bloquea el chat:** si el índice está vacío, el RAG responde
   **sin fuentes** y lo dice: *"No encontré material de la cátedra todavía."*

### 9.4 Reanudación

- El bundle del corpus admite `Range: bytes=<desde>-` ⇒ una descarga
  interrumpida (se cerró la laptop, se cortó el wifi) se **continúa** desde
  donde quedó.
- El cliente guarda el `.part` en `cache/` y lo mueve a `courses/<id>/corpus/`
  sólo cuando el SHA-256 del bundle verificó.
- Si el `corpusVersion` del manifiesto cambia mientras se descarga (el
  docente subió material), el servidor responde `404` al `GET /corpus?version=…`
  viejo ⇒ el cliente descarta el `.part` y arranca con la versión nueva.

### 9.5 Qué ve el alumno durante la primera sincronización

```
┌──────────────────────────────────────────────────────────────────────┐
│  IA-2026-1 · Inteligencia Artificial                    [ ⚙ ]      │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ ⏳ Preparando el material de la cátedra…                      │  │
│  │                                                                │  │
│  │   Manifiesto          ✔                                        │  │
│  │   Material (34/80)    ████████░░░░░░░░  1,2 MB de 1,4 MB      │  │
│  │   Indexando           ○                                         │  │
│  │                                                                │  │
│  │   Podés chatear mientras tanto.                                │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ 👤 ¿Qué es una función de pérdida?                             │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 10. Casos borde

| Caso | Comportamiento definido |
|---|---|
| El alumno abre la app sin red | Todo lo local funciona. El outbox acumula. La UI muestra *"Sin conexión: 4 cambios pendientes de subir"*. |
| La red se corta a mitad de un POST | El cliente reintenta con el **mismo** `Idempotency-Key` y el mismo `clientMessageId` ⇒ no se duplica nada. |
| El servidor devuelve `410 cursor_expired` | El cliente hace una sincronización completa: manifiesto sin `since` + comparación por hash + sólo descarga lo que falta. |
| El token de sincronización expiró | Se rota (§6.3). Si la rotación falla, la sincronización se pausa con un aviso; **no se borra nada local**. |
| El docente borró un material que el alumno ya tenía | El material desaparece del manifiesto (`removed`) y se borra local. El RAG se reindexa. |
| El docente despublicó un material (`visible=false`) | El material sale del listado para el alumno; su texto **se borra** del dispositivo (no queda una copia oculta). |
| El alumno se reinscribe tras una baja (`LEFT` → `ACTIVE`) | El servidor reactiva la misma inscripción (`Enrollment.id` estable) ⇒ el historial que ya estaba subido sigue associated. |
| Dos dispositivos del mismo alumno suben el mismo mensaje | Idempotencia por `clientMessageId`: el segundo recibe `duplicate`. |
| El alumno borró un chat en el dispositivo A y chatéo en el B | El tombstone gana: el mensaje de B se rechaza con `409 thread_deleted` → dead letter con aviso. |
| El reloj del dispositivo del alumno está 2 horas atrasado | La guarda de desfase (§5.5.3) convierte el LWW del título en conflicto explícito. Los **mensajes** se ordenan por `createdAt` con marca `clockSkew`. |
| El servidor no está disponible (503) | Backoff exponencial. La app sigue usable en local. |
| El alumno usa IA local y **nunca** sube sus chats | El docente lo ve en el padrón como *"IA local declarada · sin datos compartidos"*. Es un estado legítimo, no un error. |
| El alumno sube chats locales y después baja la visibilidad a `NONE` | El servidor deja de servir el texto **de inmediato**. Lo ya subido queda retenido hasta el fin de cursada (aviso explícito). |
| La cátedra llega al tope de alumnos | `422` con el mensaje del dominio: *"La cátedra IA-2026-1 alcanzó el tope de 40 alumnos."* |
| Un alumno intenta leer el padrón | `403 roster_forbidden`. El servidor **no** filtra qué datos existen: no devuelve ni un conteo. |
| Un ayudante intenta bajar la `visibilityCap` de un alumno | `403 forbidden`: `REDUCE_STUDENT_VISIBILITY` es del titular. |
| El cliente descarga un material corrupto | El SHA-256 no coincide ⇒ se descarta y se vuelve a pedir. El índice BM25 nunca se construye con texto corrupto. |
| La app se cierra durante la primera sincronización | Se reanuda: el cursor no avanzó, el `.part` del corpus sigue en `cache/` y se continúa con `Range`. |
| El alumno cambia de `ModelPolicy` a `LOCAL` a mitad de una cursada | Los hilos ya subidos quedan; los nuevos hilos locales **no** se suben solos. El badge "IA local declarada" aparece en el padrón. |
| La `inferenceKey` venció (fin de cursada) | La inferencia alojada deja de funcionar con un mensaje claro: *"La clave de la cátedra venció. Podés seguir usando IA local."* La **sincronización** también se corta (§8.4). |

---

## 11. Tipos de cliente (propuesta)

> **No es código de producción**: es la forma que tendría el contrato en
> `:core`, para que el servidor y el cliente puedan implementarlo en paralelo.

```kotlin
// core/src/commonMain/kotlin/kitoru/sync/SyncClient.kt
package kitoru.sync

/**
 * Cliente de la API de sincronización de Kitoru-Server.
 *
 * Implementación sobre el `HttpClient` de Ktor 3 (el stack ya lo tiene).
 * `expect fun systemHttpClient()` en `jvmCommonMain` (OkHttp/CIO).
 */
interface SyncClient {
    suspend fun manifest(courseId: String, since: String?): ManifestResponse
    suspend fun materials(courseId: String, since: String?): MaterialsResponse
    suspend fun materialContent(courseId: String, materialId: String, variant: ContentVariant): ByteArray
    suspend fun corpus(courseId: String, version: String): ByteArray
    suspend fun pushChats(courseId: String, batch: List<ChatUploadEntry>, idempotencyKey: String): PushResult
    suspend fun chats(courseId: String, author: String?, since: String?): ChatsResponse
    suspend fun enrollments(courseId: String, since: String?): EnrollmentsResponse
    suspend fun myEnrollment(courseId: String): EnrollmentResponse
    suspend fun updateMyEnrollment(courseId: String, body: UpdateEnrollmentBody): EnrollmentResponse
    suspend fun rotateCredentials(courseId: String, reason: String): Credentials
}

/** Qué versión del contenido de un material se quiere. */
enum class ContentVariant { TEXT, ORIGINAL }
```

```kotlin
// core/src/commonMain/kotlin/kitoru/sync/SyncModels.kt
@Serializable
data class ManifestResponse(
    val protocol: Int = 1,
    val courseId: String,
    val serverTime: Long = 0,
    val cursor: String = "",
    val hasMore: Boolean = false,
    val course: RemoteCourse? = null,
    val materials: MaterialDelta? = null,
    val roster: RosterDelta? = null,
    val corpus: CorpusInfo? = null,
)

/** La cátedra como la ve el cliente (no incluye secretos). */
@Serializable
data class RemoteCourse(
    val id: String,
    val code: String = "",
    val name: String = "",
    val term: String = "",
    val description: String = "",
    val joinCode: String = "",
    val systemPrompt: String = "",
    val topK: Int = 5,
    val chunkSize: Int = 1200,
    val analyticsEnabled: Boolean = true,
    val allowLocalModels: Boolean = true,
    val published: Boolean = true,
    val ownerUserId: String = "",
    val visibilityPolicy: VisibilityPolicy = VisibilityPolicy(),
    val modelPolicy: ModelPolicy = ModelPolicy(),
    val version: Int = 1,
    val updatedAt: Long = 0L,
)

/** La política de visibilidad por rol (propuesta en `03-inscripciones.md` §5.3). */
@Serializable
data class VisibilityPolicy(
    val rules: List<RoleVisibilityRule> = emptyList(),
)

@Serializable
data class RoleVisibilityRule(
    val role: String,                    // CourseRole
    val maxVisibility: String,           // ChatVisibility
)

@Serializable
data class RosterDelta(
    val upserted: List<Enrollment> = emptyList(),
    val removed: List<String> = emptyList(),
)

@Serializable
data class EnrollmentResponse(
    val enrollment: Enrollment,
    /** Visibilidad que el docente tiene sobre este alumno, ya calculada. */
    val effectiveVisibilityForTeacher: String = "FULL",
)

@Serializable
data class MaterialDelta(
    val upserted: List<RemoteMaterial> = emptyList(),
    val removed: List<String> = emptyList(),
)

@Serializable
data class MaterialsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val materials: List<RemoteMaterial> = emptyList(),
    val removed: List<String> = emptyList(),
    val nextSince: String? = null,
)

@Serializable
data class Credentials(
    val inferenceKey: String = "",
    val syncToken: String = "",
    val syncTokenExpiresAt: Long = 0L,
)

@Serializable
data class RemoteMaterial(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: String = "DOCUMENT",
    val path: String = "",
    val sizeBytes: Long = 0,
    val uploadedBy: String = "",
    val uploadedAt: Long = 0,
    val tags: List<String> = emptyList(),
    val visible: Boolean = true,
    val warning: String = "",
    val contentHash: String = "",
    val textHash: String = "",
    val textBytes: Long = 0,
    val textEtag: String = "",
    val version: Int = 1,
)

@Serializable
data class CorpusInfo(
    val version: String,
    val materialCount: Int = 0,
    val visibleCount: Int = 0,
    val textBytes: Long = 0,
    val bundleUrl: String = "",
    val bundleBytes: Long = 0,
    val bundleEtag: String = "",
)

@Serializable
data class ChatUploadEntry(
    val kind: String,                    // "thread" | "message"
    val clientThreadId: String,
    val clientMessageId: String = "",
    val origin: String = "HOSTED",       // "HOSTED" | "LOCAL_SHARED"
    val consent: ShareConsent? = null,
    val thread: RemoteThread? = null,
    val message: RemoteMessage? = null,
)

/** Hilo tal como viaja en el wire: sin `id`, sin `authorId`, sin `authorName`. */
@Serializable
data class RemoteThread(
    val title: String = "Conversación",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val effectiveModel: ModelPolicy = ModelPolicy(),
    val tags: List<String> = emptyList(),
    val rev: Int = 1,
    val updatedByDevice: String = "",
)

/** Mensaje tal como viaja en el wire: sin `threadId`. */
@Serializable
data class RemoteMessage(
    val id: String,                      // = clientMessageId
    val role: String,                    // "user" | "assistant"
    val text: String = "",
    val createdAt: Long = 0L,
    val sources: List<String> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val latencyMs: Long = 0L,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val rev: Int = 1,
    val updatedByDevice: String = "",
)

@Serializable
data class ShareConsent(
    val grantedAt: Long,
    val scope: String,                   // "FULL" | "METRICS_ONLY"
    val version: Int = 1,
    val packageHash: String = "",
)

@Serializable
data class AcceptedEntry(
    val kind: String,
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    /** Id final en el servidor (el del cliente, namespaseado para los hilos). */
    val id: String = "",
    /** Posición en el log de cambios de la cátedra. */
    val serverSeq: Long = 0,
    val status: String = "",             // "created" | "revised" | "duplicate" | "redacted"
)

@Serializable
data class RejectedEntry(
    val kind: String,
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    val code: String,
    val message: String,
)

@Serializable
data class ConflictEntry(
    val kind: String,
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    val reason: String,                  // "stale_revision" | "clock_skew"
    val serverRev: Int = 0,
    val clientRev: Int = 0,
    val server: JsonObject = JsonObject(emptyMap()),
    val client: JsonObject = JsonObject(emptyMap()),
)

/** Aviso de que el servidor tiene una versión más nueva de algo. */
@Serializable
data class SupersededEntry(
    val kind: String,
    val entityId: String,
    val serverRev: Int = 0,
    val serverUpdatedAt: Long = 0,
)

@Serializable
data class PushResult(
    val serverTime: Long = 0,
    val cursor: String = "",
    val accepted: List<AcceptedEntry> = emptyList(),
    val duplicates: List<AcceptedEntry> = emptyList(),
    val rejected: List<RejectedEntry> = emptyList(),
    val conflicts: List<ConflictEntry> = emptyList(),
    val superseded: List<SupersededEntry> = emptyList(),
)

@Serializable
data class ChatsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val threads: List<RemoteThreadView> = emptyList(),
    val messages: List<RemoteMessageView> = emptyList(),
    val removed: List<String> = emptyList(),
    val visibility: VisibilityView = VisibilityView(),
)

@Serializable
data class RemoteThreadView(
    val id: String,
    val courseId: String,
    val authorId: String,
    val authorName: String,
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val tags: List<String> = emptyList(),
    val messageCount: Int = 0,
    val origin: String = "HOSTED",
    val redacted: Boolean = false,
)

@Serializable
data class RemoteMessageView(
    val id: String,
    val threadId: String,
    val role: String,
    val text: String = "",
    val createdAt: Long = 0L,
    val sources: List<String> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val latencyMs: Long = 0L,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val redacted: Boolean = false,
)

@Serializable
data class VisibilityView(
    val effective: String = "FULL",      // ChatVisibility
    val showsText: Boolean = true,
    val showsMetrics: Boolean = true,
)

@Serializable
data class EnrollmentsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val enrollments: List<Enrollment> = emptyList(),
    val removed: List<String> = emptyList(),
    val summary: RosterSummary = RosterSummary(),
)

@Serializable
data class RosterSummary(
    val students: Int = 0,
    val active: Int = 0,
    val pending: Int = 0,
    val suspended: Int = 0,
)

@Serializable
data class UpdateEnrollmentBody(
    val visibility: String,              // ChatVisibility
    val aiMode: String = "HOSTED",       // StudentAiMode
    val declaredLocalAiAt: Long = 0L,
    val displayName: String = "",
)
```

**El motor de sincronización** (el orquestador, en `:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/sync/SyncEngine.kt
/**
 * Orquesta una ronda de sincronización de una cátedra.
 *
 * Orden fijo:
 *   1. vaciar el outbox (push)   → si falla por red, se corta acá
 *   2. pull del manifiesto        → decide si hay que seguir
 *   3. pull de materiales (delta)
 *   4. pull del roster (delta)
 *   5. pull de chats (delta, sólo docente)
 *   6. escribir el SyncState (cursores)
 *
 * Es idempotente: se puede cortar en cualquier punto y retomar.
 */
class SyncEngine(
    private val client: SyncClient,
    private val state: SyncStateRepository,
    private val outbox: OutboxRepository,
    private val materials: MaterialRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val enrollments: EnrollmentRepository,
) {
    suspend fun sync(courseId: String, mode: SyncMode): SyncReport
}

enum class SyncMode { FULL, DELTA, PUSH_ONLY }
```

---

## 12. Resumen de decisiones

| Decisión | Motivo |
|---|---|
| REST + JSON sobre HTTPS | Lo que ya conoce el stack (Ktor); auditable con `curl`. |
| Delta por cursor opaco por recurso | 500 alumnos × 200 MB no es viable; el delta lo baja a 1,4 MB. |
| Log de cambios append-only por cátedra | Un único mecanismo para todos los recursos; el cursor es un `seq` firmado. |
| Cursor con HMAC + `courseId` + expiración | El cliente no puede inventar cursores ni replayar entre cátedras; el servidor puede podar el log. |
| Cursores en `courses/<id>/sync.json`, escritura atómica | Si se corrompe, el peor caso es una resincronización completa, no un crash. |
| Servidor = fuente de verdad de la cátedra; alumno = de sus chats | Cada actor es dueño de lo suyo; el servidor coordina. |
| Materiales en el servidor, una copia por cátedra | 200 MB, no 500 × 200 MB. Y el servidor extrae el texto una sola vez. |
| Relé de chats con redacción en el servidor | La visibilidad es estructural: si no debe verse, no sale. |
| Dos tokens (inferencia / sincronización) | La key es permiso de consumo; mezclarla habilita escalamiento de privilegios. |
| `sessionToken` sólo para docentes | El SSO es del docente; el alumno se autentica con el código de la cátedra. |
| Outbox en disco, FIFO, con backoff | El alumno trabaja sin red y nada se pierde. |
| `clientMessageId` (UUID del cliente) como clave de deduplicación | Reintentar nunca duplica mensajes. |
| Mensajes append-only; metadatos con LWW + guarda de desfase; borrados con tombstone | Ninguna edición se pierde en silencio; las decisiones dudosas se preguntan. |
| IA local: subida sólo manual, con consentimiento explícito y alcance granular | La privacidad es un acto, no un permiso por defecto. |
| Nivel 0 (texto) siempre, nivel 1 (binario) bajo demanda | 143× menos tráfico en la primera sincronización. |
| El servidor no calcula la analítica | Una sola implementación pura y testeable (`AnalyticsAnalyzer`). |
| Primera sincronización en fases, sin bloquear, reanudable | Un alumno nuevo no espera 10 minutos. |

---

## 13. Pendientes y fuera de alcance

1. **El bundle del corpus**: definir el formato exacto (`tar.gz` con
   `manifest.json` + `texts/<materialId>.txt`) y si se firma. La firma permite
   verificar la integridad en el cliente sin trusting al TLS.
2. **Push del servidor al cliente**: hoy es poll con `ETag`. Si la experiencia
   lo pide, un canal SSE (`GET /v1/courses/{id}/events`) puede reemplazar el
   poll; el contrato del cursor **no cambia** (el evento sólo dice "hay algo
   nuevo", el delta se sigue bajando por el mismo endpoint).
3. **Sincronización de las tareas y anuncios** (`domain/06-tareas.md`,
   `domain/07-anuncios.md`): el mismo esquema (cursor + delta) pero en
   `/v1/courses/{id}/tasks` y `/v1/courses/{id}/announcements`. Queda fuera de
   este contrato.
4. **Retención y purga**: los plazos de §8.4 son propuestas; la política final
   la define la institución (y debería quedar escrita en el aviso de la
   cátedra).
5. **`AccessLog` remoto**: el servidor debería validar el log que sube el
   docente (que no se auto-atribuya accesos de otros) — es unloggable por
   definición, así que la confianza es "de buena fe" hasta que haya un
   apéndice firmado.
6. **Sincronización del `modelo` local**: si el alumno descarga un modelo GGUF
   grande, conviene que el servidor lo sirva con el mismo mecanismo de
   delta (hash + `Range`), reusando el diseño de materiales.
