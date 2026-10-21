# 07 · Sincronización del corpus al dispositivo del alumno

> Subagente: diseño de la sincronización del **material de la cátedra** (el
> corpus del RAG) hacia el dispositivo del alumno, para que el RAG local
> funcione igual que el remoto.
> Alcance: **manifiesto → descarga con deltas → indexación local → avisos de
> cambios → privacidad → espacio → modo "sólo texto"**.
> Todo lo que sigue es diseño, no código: los nombres de archivo, tipos y
> firmas son los que deberían existir en el repo.
> Documentos relacionados: `teacher/03-materiales.md` (el pipeline de subida
> y de extracción), `domain/08-permisos.md` (capacidades), `domain/09-identidad.md`
> (tokens, `CredentialStore`, el bundle `.kitoru`) y `09-corpus-gestion.md`
> (catálogo, versiones y publicación del corpus — **los dos documentos
> definen un `CorpusManifest` y hay que fusionarlos: ver §16**).

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | El **texto extraído** es lo que viaja al dispositivo. El archivo original es **opcional** y se baja aparte. | El texto es lo que se indexa: es 5–20× más liviano, no necesita PDFBox/OCR en el teléfono y garantiza que el alumno indexe **exactamente** lo mismo que el docente. |
| D2 | El **manifiesto** (`CorpusManifest`) es el único contrato de sincronización: un JSON por cátedra con los materiales, su `textHash` (sha-256), su tamaño y su fecha. | Un solo archivo, versionable, comparable con `If-None-Match`, y que sirve igual para el canal servidor y para el paquete offline. (El tipo final se fusiona con `09-corpus-gestion.md` en §16.) |
| D3 | Los **deltas se calculan en el cliente**, no en el servidor: se compara el `textHash` del manifiesto contra el estado local. | El planificador es una **función pura** testeable, y el mismo código sirve para el bundle offline. El servidor sólo sirve manifiesto + blobs. |
| D4 | El **Kitoru-Server es también el repositorio de materiales** (recomendado), y el **paquete exportado** (`.kitoru-corpus`) es el camino offline/backup. | El servidor es el punto de contacto entre la institución y los alumnos: es el único que puede autenticar a todos, registrar descargas y servir por HTTP. |
| D5 | El manifiesto **no tiene campo `visible`**: lo que el docente no publica, no está en el manifiesto y nunca sale de su dispositivo. | La privacidad es por construcción, no por un filtro en el cliente. |
| D6 | El **índice NO se precomputa en el servidor** (v1). Se indexa localmente con el mismo `Bm25Index`, en background, en 1–3 s. | El artefacto indexado ata el formato al algoritmo (tokenizador, stopwords, `k1`/`b`): un cambio invisible invalidaría todos los artefactos y haría que el RAG local difiera del remoto. El texto se re-baja por hash; el índice no. |
| D7 | El manifiesto incluye el **bloque `chunking`** (`chunkSize`, `chunkOverlap`, `algorithm`): si el docente cambia el troceado, el alumno **reindexa sin volver a descargar un byte**. | Separa "cambió el texto" (se descarga) de "cambió el troceado" (se reindexa). Es la mitad del tráfico. |
| D8 | El alumno **no descarga nada por defecto**: el manifiesto se consulta siempre (KB) y el texto se baja **a pedido** ("Activar material en este dispositivo"). | Ahorra datos, respeta la política de la cátedra y deja la decisión en el alumno. |
| D9 | La política tiene **dos banderas independientes**: `allowLocalIndex` (bajar e indexar) y `allowLocalModels` (usar el material con una IA en el dispositivo). | Un alumno sin permiso de IA local **igual puede buscar** en el material: el modo "sólo texto" no llama a ningún modelo. |
| D10 | El **límite técnico es explícito**: si el texto llega al dispositivo, ninguna técnica puede impedir que el alumno lo use con un modelo local. El control es **política + entrega**, no DRM. | Decir la verdad: lo que se puede garantizar es que *la app* respeta la política, que el servidor *registra* quién descargó qué, y que el material no se envía a modelos remotos de terceros sin aviso. |
| D11 | El **modo "sólo texto"** (BM25 local, sin modelo, sin red) es una función de producto de primera línea. | Es gratis (el índice ya existe), funciona en modo avión, y es la puerta de entrada al valor de Kitoru para el alumno que no puede usar IA. |
| D12 | Names: `CorpusSync`, `CorpusManifest`, `LocalCorpusStore`, `LocalCorpusIndex`, `LocalCorpusSource`, `CorpusSyncPlanner`, `CorpusClient`, `CorpusPublisher`. | Consistencia con `KnowledgeBase` / `CourseMaterialSource` / `DocumentSource` que ya existen. |

---

## 1. El problema: el RAG tiene dos aguas

### 1.1 Qué necesita el alumno en cada modo

| Modo | ¿Quién indexa? | ¿Dónde está el corpus? | ¿Qué hace el cliente? |
|---|---|---|---|
| **REMOTE** (IA alojada por la institución) | el servidor | en el servidor | manda la pregunta; el servidor recupera y responde |
| **LOCAL** (IA en el dispositivo del alumno) | **el alumno** | **en el dispositivo** | recupera contra el índice local, arma el prompt, llama al modelo local |
| **Sin red** (avión, sin señal) | el alumno | en el dispositivo | mismo que LOCAL, pero sin poder llamar a un modelo remoto |

El tercer caso es el que casi nadie diseña y es el que más valor aporta: el
alumno quiere **buscar en el material** aunque no pueda hablar con la IA.

### 1.2 La condición de paridad

> "El RAG local tiene que funcionar igual que el remoto."

Eso, en concreto, significa que para la misma pregunta y el mismo material, el
alumno tiene que recuperar **los mismos fragmentos** que el docente ve en la
pestaña "Probar pregunta". Para que eso se cumpla hay que igualar cinco cosas:

| # | Qué tiene que ser igual | Cómo se garantiza |
|---|---|---|
| 1 | El **conjunto de materiales** | El manifiesto lista exactamente los materiales publicados (los `visible` los publica el docente; los ocultos nunca salen de su dispositivo). |
| 2 | El **texto** | Viaja el **texto extraído**, no el original: es el mismo string que el docente indexa (`Material.extractedText`). |
| 3 | El **troceado** | El manifiesto lleva `chunking: {chunkSize, chunkOverlap, algorithm}`; el cliente corre el **mismo `Chunker`** con esos parámetros. |
| 4 | El **tokenizado** | El mismo `Bm25Index.tokenize` (el cliente corre el mismo código: es el mismo repo, el mismo artefacto). |
| 5 | Los **parámetros BM25** | `k1` y `b` son constantes del código, iguales por construcción. |

De los cinco, el 3 es el que se rompe solo si alguien lo olvida: por eso el
troceado viaja **explícito** en el manifiesto (D7) y no se re-deriva.

> **Corolario importante:** el alumno **no necesita el original** para que el
> RAG funcione igual. El original es un extra ("abrir el PDF"), no una pieza
> del RAG. Por eso es opcional (D1).

### 1.3 Qué no viaja al dispositivo

- El material **oculto** (`Material.visible == false`): nunca se publica, nunca
  se descarga.
- El **estado interno del docente**: los materiales en `PENDING`, `EXTRACTING`
  o `FAILED` no se publican (no tienen texto estable).
- La **API key**, el `ownerSecretHash` y el `staffCodeHash`: son credenciales,
  no corpus.
- Los **chats de los alumnos**: no son material de la cátedra.

---

## 2. La fuente de verdad del corpus: tres niveles

### 2.1 El nudo

El enunciado tiene razón en el planteo: **el Kitoru-Server, tal como está
descrito en `CONTEXTO.md`, no tiene el material**. El material lo tiene el
docente **en su Kitoru**. Y el alumno necesita el material **en su
dispositivo**. Entre los dos hay un hueco: no hay canal.

Hay que resolver **quién publica** y **quién distribuye**:

```
   ┌──────────────┐   publica     ┌──────────────────┐   descarga    ┌──────────────┐
   │  Kitoru del  │ ────────────► │  Kitoru-Server   │ ────────────► │  Kitoru del  │
   │   docente    │   (push)      │  (repositorio)   │   (pull)      │   alumno     │
   └──────────────┘               └──────────────────┘               └──────────────┘
   verdad de autoría             verdad de distribución            copia consumible
```

Tres niveles distintos, y es importante no confundirlos:

| Nivel | Dónde vive | Qué es | Quién lo escribe | Quién lo lee |
|---|---|---|---|---|
| **Autoría** | el Kitoru del docente | `Material` + `extractedText` + `visible` | el docente | el publicador |
| **Distribución** | el Kitoru-Server | el `CorpusManifest` publicado + los blobs de texto | el publicador (push) | el cliente (pull) |
| **Consumo** | el Kitoru del alumno | `LocalCorpusStore` (manifiesto + textos + estado) | el `CorpusSync` | el `LocalCorpusIndex` |

La **fuente de verdad** de *cada* nivel es su dueño: el docente manda sobre el
nivel de autoría (su material, su visibilidad, su troceado), el servidor
mandan sobre el nivel de distribución (qué generación está publicada y a quién
se le sirvió), y el alumno manda sobre el nivel de consumo (qué versión tiene
en su dispositivo y si la borra).

> El servidor **no es la fuente de verdad**: es un *mirror con versión*. Si el
> docente borra el material de su Kitoru, el servidor no lo sabe hasta que el
> docente publica otra vez. Por eso la publicación es siempre explícita
> ("Publicar"), no implícita.

### 2.2 Las opciones de canal

| | **A. El servidor es el repositorio** | **B. Paquete exportado** | **C. P2P docente → alumno** |
|---|---|---|---|
| Cómo llega | el docente hace push al servidor; el alumno hace pull | el docente exporta una carpeta/zip y la distribuye (Drive, pendrive, QR, curso) | el alumno escanea la red local y baja del dispositivo del docente |
| Autenticación | sesión / API key / código de inscripción | la confianza en el canal (el hash del manifiesto avisa si el archivo se corrompió) | ninguna: cualquiera en la LAN puede leer |
| Auditoría de descargas | sí (el servidor la registra) | no | no |
| Funciona sin red del alumno | no (pero el corpus queda cacheado) | **sí** | sí |
| Escala | sí | no (un archivo por alumno) | no |
| Costo de implementación | endpoint de publish + de pull | export + import | transporte, descubrimiento, seguridad |
| Riesgo | el material queda en el servidor institucional (que es lo que se quiere) | el material viaja por canales que la institución no controla | el material queda accesible a toda la red |

### 2.3 Recomendación: **A como camino principal, B como camino offline**

**Opción A — el Kitoru-Server también es el repositorio de materiales.**

Es la opción obvia y la que hay que recomendar, por cuatro razones concretas:

1. **Es el único punto donde ya hay autenticación, cuota y auditoría.** El
   servidor emite API keys, tiene `Enrollment` con roles, y puede registrar
   "el alumno X descargó la v12 del corpus". Eso convierte al servidor en el
   lugar natural donde vive el material publicado.
2. **El material publicado no es un secreto del docente**: es material de la
   cátedra, que la institución ya aloja el modelo y ya emite credenciales.
   La institución puede decidir por política si ese material se hospeda (una
   cátedra de derecho con material bajo NDA no publica → `allowLocalIndex =
   false` y sigue usando el RAG remoto).
3. **Un solo camino de descarga**: HTTP con `ETag`, `Range`, gzip y reintentos
   es exactamente lo que ya sabe hacer el cliente de `llm-api` (Ktor CIO en
   escritorio, OkHttp en Android). No hay que inventar transporte.
4. **El docente deja de ser el único que puede分发 material**: con el
   servidor, un ayudante publica y todos los alumnos reciben el mismo corpus,
   sin que el titular tenga que reenviar archivos.

**Opción B — el paquete exportado (`.kitoru-corpus`)** se mantiene como:

- el camino **sin servidor** (la cátedra que no tiene Kitoru-Server: el
  `CONTEXTO.md` es explícito en que el servidor es "futuro");
- el camino de **respaldo** (si el servidor se cae, el docente puede exportar
  el corpus y compartirlo por Drive);
- el camino de **aula con mala conexión** (el profesor deja el paquete en un
 pendrive; cada alumno lo importa).

> `domain/09-identidad.md` §6.4 ya describe un "archivo de cátedra" (`.kitoru`)
> como el transporte sin servidor. El `.kitoru-corpus` es **el mismo concepto
> acotado al corpus**: un manifiesto + los textos. No se inventa nada nuevo: se
> reusa el formato del manifiesto y el `LocalCorpusStore` queda igual.

**Opción C (P2P) queda descartada**, pero con un motivo que conviene
dejar escrito: el material de una cátedra es exactamente el tipo de dato que
no debe quedar accesible en una red compartida por toda la facultad. Que el
alumno tenga el material **en su dispositivo, con su cuenta y su registro de
descargas** es una diferencia de riesgo enorme frente a "cualquiera que esté
en el WiFi de la facultad puede leer el PDF de la cátedra".

### 2.4 El flujo completo

```
 DOCENTE (en su Kitoru)                    KITORU-SERVER                ALUMNO
 ─────────────────────                     ─────────────                ──────
 upload → extracción → Material(READY)
        │
        │ "Publicar a los alumnos"
        ▼
 CorpusPublisher.publish()
   ├─ arma el manifiesto con los materiales visibles
   ├─ PUT texto de los materiales cuyo hash cambió
   └─ PUT manifiesto (baseGeneration) ────►  valida OCC
                                              guarda los blobs
                                              generation++  ───────────► GET manifiesto
                                                                   (ETag → 304 si no cambió)
                                                                      GET texto de lo que falta
                                                                      (delta por hash)
                                                                      reindexa local
                                                                      ┌──────────────┐
                                                                      │ RAG local    │
                                                                      │ (mismo Bm25) │
                                                                      └──────────────┘
```

El paso de "el docente publica" es **explícito** y no automático: es una
acción visible, con su propio estado ("Publicado · v12 · 24 alumnos tienen la
v11").

### 2.5 Autenticación de la descarga

Se reusa lo que `domain/09-identidad.md` ya diseñó, sin inventar un sistema
nuevo:

| Momento | Credencial | Dónde vive |
|---|---|---|
| **Bootstrap** (la primera vez) | el **código de inscripción** (`Course.joinCode`) | lo tipea el alumno; el servidor lo canjea por un token de alcance `corpus:read` |
| **Uso cotidiano** | el **token de sesión** (`session:<serverId>`) o la **API key de la cátedra** (`apikey:<serverId>:<courseId>`) | `CredentialStore` (Keychain/EncryptedSharedPreferences), nunca en el perfil |
| **Publicación (docente)** | el **secreto de propietario** (`ownerSecretHash`) o el rol del servidor | el servidor valida el rol del `Enrollment` |

El código de inscripción es un *bearer token de grupo* (ya está analizado así
en `09-identidad.md` §6.3): sirve para el bootstrap, no da identidad. Para
bajar el corpus, la identidad no hace falta: hace falta **estar inscripto**.

> **El servidor debe validar la inscripción en cada pedido de material**, no
> sólo en el alta: si el alumno se dio de baja, su token de descarga deja de
> funcionar (y la app borra el corpus local, §7.7).

---

## 3. El manifiesto del corpus

### 3.1 Qué es

> El manifiesto es **un JSON por cátedra** que dice: qué material hay, qué
> versión tiene cada material, cuánto pesa y cuándo se publicó. Con ese
> archivo, el alumno sabe **qué falta** bajarse sin descargar nada.

Es la pieza que hace posible el "delta por hash": sin manifiesto no hay forma
de saber qué cambió sin bajarse el corpus entero.

### 3.2 Formato

```json
{
  "formatVersion": 1,
  "courseId": "c-ia-2026",
  "course": {
    "code": "IA-2026-1",
    "name": "Inteligencia Artificial",
    "term": "2026-1"
  },
  "generation": 12,
  "publishedAt": 1761600000000,
  "publisher": {
    "app": "Kitoru",
    "version": "0.3.0",
    "publisherId": "u-4f1c",
    "publisherName": "Dra. Ramírez"
  },
  "chunking": {
    "algorithm": "kitoru-chunker/1",
    "chunkSize": 1200,
    "chunkOverlap": 150
  },
  "policy": {
    "allowLocalIndex": true,
    "allowLocalModels": true,
    "allowOriginalDownload": true,
    "requireAcceptance": false,
    "notice": "Material de la cursada IA-2026-1. No lo redistribuyas fuera del equipo.",
    "license": "Uso académico interno — IA-2026-1"
  },
  "materials": [
    {
      "id": "mat_7f3a2b",
      "title": "Apuntes-IA.pdf",
      "kind": "DOCUMENT",
      "tags": ["teoría", "parcial-1"],
      "textHash": "sha256:9f2c1d…",
      "textBytes": 48230,
      "textChars": 48117,
      "originalHash": "sha256:1a7b93…",
      "originalBytes": 1204331,
      "originalName": "Apuntes-IA.pdf",
      "contentType": "application/pdf",
      "updatedAt": 1761500000000,
      "warning": ""
    },
    {
      "id": "mat_1c9d40",
      "title": "Diapositivas-2026-1.pptx",
      "kind": "SLIDES",
      "tags": [],
      "textHash": "sha256:44e8a0…",
      "textBytes": 210441,
      "textChars": 209002,
      "originalHash": "sha256:88c1ff…",
      "originalBytes": 8420113,
      "originalName": "Diapositivas-2026-1.pptx",
      "contentType": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "updatedAt": 1761500000000,
      "warning": "3 de 40 diapositivas son imágenes: su texto no se pudo extraer."
    }
  ],
  "manifestHash": "sha256:5c1e90…"
}
```

> **Nota de nombres.** Este documento usa `generation`, `materials` y
> `textHash` porque se escribió de forma independiente. El tipo **final** del
> manifiesto se fusiona con el de `09-corpus-gestion.md` en **§16**, donde
> queda canónico `version`, `documents` y `contentHash`. Lo demás (el
> `chunking`, la `policy`, el `manifestHash`, el `warning`, el hash del
> original) entra tal cual desde este documento.

### 3.3 Campo por campo

| Campo | Tipo | Para qué | Notas |
|---|---|---|---|
| `formatVersion` | `Int` | versión del **formato** (no del contenido) | hoy `1`. Si cambia la estructura, sube. El cliente con una versión mayor **rechaza** con un mensaje claro; el menor la acepta con defaults. |
| `courseId` | `String` | a qué cátedra pertenece | el id del servidor, no el local: el manifiesto es del servidor. |
| `course` | `CorpusCourseRef` | código, nombre y cursada | sólo para mostrar "IA-2026-1 · Inteligencia Artificial" sin consultar la cátedra. |
| `generation` | `Long` | **versión del corpus** | sube en cada publicación. Es el número que el alumno ve ("v12") y el que usa el polling para saber si algo cambió. |
| `publishedAt` | `Long` | cuándo | epoch millis. |
| `publisher` | `CorpusPublisher` | quién publicó y con qué versión de la app | traza. El `publisherId` es un id de usuario, no un nombre de persona: el nombre es informativo. |
| `chunking.algorithm` | `String` | **huella del troceador** | `"kitoru-chunker/1"`. Si el cliente tiene otra versión, avisa (§5.6). |
| `chunking.chunkSize` / `chunkOverlap` | `Int` | los parámetros del `Chunker` | vienen de `Course.chunkSize` y del `RagConfig.chunkOverlap` (150). |
| `policy.*` | `CorpusPolicy` | lo que la cátedra permite | §7. Es la política **efectiva**, ya combinada con la del servidor. |
| `materials[]` | `CorpusEntry` | el material | §3.4. |
| `manifestHash` | `String` | sha-256 del manifiesto **sin este campo** | es el `ETag`. Permite un `304 Not Modified` de 1 KB. |

### 3.4 La entrada de material

| Campo | Para qué | Regla |
|---|---|---|
| `id` | identidad estable del material | el mismo `Material.id` que el docente tiene local. Si el docente re-sube el mismo archivo, la app debe poder decidir si es un material nuevo o una versión nueva (ver `docs/design/teacher/03-materiales.md`): **el `id` no cambia, el `textHash` sí**. |
| `title` | etiqueta de cita | es lo que va en `Doc.path` → `"cátedra: Apuntes-IA.pdf"` en las citas. |
| `kind` | `MaterialKind` | `DOCUMENT`, `SLIDES`, `TRANSCRIPT`, `LINK`, `OTHER`. Reusa el enum del dominio. |
| `tags` | organización | **no afectan al RAG** (BM25 no filtra por etiqueta): replicated de la decisión de `teacher/03-materiales.md` §6.1. |
| `textHash` | **la clave de sincronización** | `sha256:` + 64 hex del **texto UTF-8** (el `extractedText` exacto, con sus saltos de línea). |
| `textBytes` | tamaño en UTF-8 | para el cálculo de cuota y de progreso. |
| `textChars` | cantidad de caracteres | el `textLength` que ya expone `Material`. La diferencia con `textBytes` es la codificación: el español ocupa 2 bytes por carácter acentuado. |
| `originalHash` | versión del archivo original | **vacío** si el docente decidió no distribuir el original (`allowOriginalDownload = false` o material marcado como "no distribuir"). |
| `originalBytes` / `originalName` / `contentType` | metadata del original | para mostrar "PDF · 1,2 MB" y para abrirlo con la app del sistema. |
| `updatedAt` | cuándo cambió el texto | epoch millis. |
| `warning` | aviso del docente | se propaga al alumno ("3 de 40 diapositivas son imágenes"), porque es información que el alumno necesita para no confiar de más en el material. |

> **El `warning` viaja.** Es un detalle chico con un valor real: si el material
> tiene un problema de extracción, el alumno tiene que saberlo, si no va a
> pensar que la IA "no encuentra" lo que está en el PDF.

### 3.5 El hash: sha-256

No hay crypto en `kotlinx.serialization` ni en el stdlib común de Kotlin, pero
`java.security.MessageDigest` existe **en el JVM y en Android** → va como
`expect`/`actual` en `kitoru.platform`, con la implementación real compartida
en `jvmCommonMain`:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Hashing.kt
package kitoru.platform

/** sha-256 del contenido, en hexadecimal minúscula, con el prefijo "sha256:". */
expect fun sha256Hex(bytes: ByteArray): String

/** sha-256 de un texto en UTF-8. */
expect fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/Hashing.jvm.kt
package kitoru.platform

import java.security.MessageDigest

/** sha-256 con MessageDigest (JVM y Android). */
internal fun jvmSha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(bytes).joinToString("") { "%02x".format(it) }
}
```

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/Hashing.desktop.kt
package kitoru.platform

actual fun sha256Hex(bytes: ByteArray): String = jvmSha256Hex(bytes)

// core/src/androidMain/kotlin/kitoru/platform/Hashing.android.kt
package kitoru.platform

actual fun sha256Hex(bytes: ByteArray): String = jvmSha256Hex(bytes)
```

> Los `actual` van en `desktopMain` y `androidMain` (no en `jvmCommonMain`):
> un source set intermedio no puede contener `actual`. El cuerpo real sí se
> comparte en `jvmCommonMain` → no se duplica la lógica.

**Por qué sha-256 y no un hash "de fábrica"**: es el estándar de facto, está
en todas las librerías de HTTP (`ETag`, `Content-MD5`), y una colisión
provocada significaría que el alumno indexa un texto viejo creyendo que es
nuevo. Un FNV-1a de 64 bits alcanzaría, pero sha-256 no cuesta nada en las dos
plataformas y evita tener que explicar en una reunión por qué el hash del
corpus no es el de la industria.

### 3.6 El manifiesto como contrato

El formato del manifiesto es **el contrato entre el Kitoru-Server y el
cliente**, y por lo tanto:

- es **público y documentado** (este documento);
- cambia por `formatVersion`, nunca "en el sitio";
- tiene un **golden file** en los tests (`corpus/src/commonTest/resources/manifest-v1.json`)
  que se compara byte a byte contra el JSON que genera `KitoruJson`: si alguien
  agrega un campo sin querer, el test falla.

---

## 4. Qué se descarga: el texto extraído (y el original, opcional)

### 4.1 La decisión

> **Se baja el texto extraído** (el mismo `extractedText` que el docente
> indexa) **+ opcionalmente el archivo original**.

### 4.2 La justificación, dato por dato

| Criterio | Texto extraído | Archivo original |
|---|---|---|
| Tamaño | 1× el texto (48 KB para 12 páginas de PDF) | 5–20× el texto (1,2 MB para el mismo PDF) |
| CPU en el teléfono | **cero**: es un `.txt` | parsear un PDF en Android = PDFBox-Android (+16 MB de app) y **30–90 s** en un escaneo |
| Paridad con el remoto | **exacta**: es el mismo string que indexa el docente | hay que re-extraer → distinto texto → el RAG local **no** coincide con el remoto |
| OCR | ya lo hizo el docente (o no hay) | el teléfono no tiene OCR en v1 (`OcrEngine` está vacío) |
| "Abrir el original" | ✗ | ✓ |
| Veredicto | **siempre** | **a pedido** |

La comparación que decide es la tercera: **si el alumno parsea el PDF en su
teléfono, el texto que indexa no es el mismo que indexa el docente** (distinta
versión de PDFBox, distinto manejo de columnas, distinta paginación). Eso
rompe el requisito central ("funciona igual que el remoto") y además gasta
batería y datos. El texto extraído lo resuelve de una: **el parseo ocurre una
sola vez, en la máquina del docente, que es la única que tiene el archivo
original**.

El original se conserva como **opción de producto**: "Querés el PDF en el
teléfono?" (para leerlo con un lector que likes, o para imprimirlo). Se baja
**bajo demanda**, con un aviso de tamaño, y el servidor lo puede proibir
(`allowOriginalDownload = false`).

### 4.3 El protocolo

El servidor expone cuatro recursos (los detalles del API están en §11):

```
GET  /v1/courses/{courseId}/corpus/manifest          → el manifiesto (ETag)
GET  /v1/courses/{courseId}/corpus/text/{materialId} → el texto (ETag, gzip)
GET  /v1/courses/{courseId}/corpus/original/{id}     → el original (opcional)
PUT  /v1/courses/{courseId}/corpus/manifest          → publica (docente)
PUT  /v1/courses/{courseId}/corpus/text/{materialId} → sube el texto (docente)
```

**El manifiesto va con `ETag` y `If-None-Match`** → el polling del alumno
cuesta ~1 KB de tráfico por cátedra, aunque el corpus pese 50 MB.

**El texto va con `Content-Encoding: gzip`** → el texto UTF-8 del español
comprime 3,5–4,5× (muchos espacios, palabras repetidas, `\n`). Un corpus de
5 MB baja como ~1,2 MB.

### 4.4 El delta: comparación de hashes, en el cliente

**El plan se calcula en el cliente**, no en el servidor (D3). El manifiesto
trae el `textHash` de cada material; el estado local sabe qué hash tiene cada
material descargado. La comparación da el plan:

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusSyncPlanner.kt
package kitoru.corpus

/**
 * Qué hay que hacer para pasar del estado local al estado del manifiesto.
 *
 * Es una **función pura**: no toca disco, no habla con la red, no lee el reloj.
 * Por eso es trivial de testear y es la misma para el canal servidor y para
 * el paquete offline.
 */
data class CorpusSyncPlan(
    /** La generación del manifiesto que describe este plan. */
    val generation: Long,
    /** No hay nada que hacer: el corpus local está al día. */
    val upToDate: Boolean,
    /** Materiales cuyo texto hay que bajar (nuevos o con hash distinto). */
    val toDownload: List<CorpusEntry>,
    /** Ids de materiales que ya no están en el manifiesto: hay que borrarlos. */
    val toRemove: List<String>,
    /** Materiales que ya están y no cambian. */
    val unchangedCount: Int,
    /** Hay que reindexar: cambió el troceado, el texto, o se borró algo. */
    val reindexRequired: Boolean,
    /** Cuántos bytes de texto hay que bajar. */
    val textBytesToDownload: Long,
) {
    val isEmpty: Boolean get() = toDownload.isEmpty() && toRemove.isEmpty() && !reindexRequired
}
```

```kotlin
object CorpusSyncPlanner {

    fun plan(
        manifest: CorpusManifest,
        local: Map<String, LocalMaterial>,
        localChunking: CorpusChunking?,
        localIndexGeneration: Long,
    ): CorpusSyncPlan {
        val toDownload = mutableListOf<CorpusEntry>()
        var unchanged = 0
        for (entry in manifest.materials) {
            val mine = local[entry.id]
            if (mine != null && mine.textHash == entry.textHash) unchanged++
            else toDownload += entry
        }
        val published = manifest.materials.associate { it.id to it }
        val toRemove = local.keys.filter { it !in published }
        val chunkingChanged = localChunking != null && localChunking != manifest.chunking
        val reindex = chunkingChanged ||
            toDownload.isNotEmpty() ||
            toRemove.isNotEmpty() ||
            localIndexGeneration != manifest.generation
        return CorpusSyncPlan(
            generation = manifest.generation,
            upToDate = toDownload.isEmpty() && toRemove.isEmpty() && !chunkingChanged &&
                localIndexGeneration == manifest.generation,
            toDownload = toDownload,
            toRemove = toRemove,
            unchangedCount = unchanged,
            reindexRequired = reindex,
            textBytesToDownload = toDownload.sumOf { it.textBytes },
        )
    }
}
```

**Los casos del delta:**

| Situación | Qué hace el cliente | Tráfico |
|---|---|---|
| El docente sube un material nuevo | baja **1** texto | el texto nuevo |
| El docente **edita** el texto de un material (o re-extrae) | baja **1** texto (el hash cambió) | el texto nuevo |
| El docente cambia el `chunkSize` | **no baja nada**: reindexa con los textos que ya tiene | 0 bytes |
| El docente saca un material | borra el texto local | 0 bytes |
| No cambió nada | no hace nada (`304`) | ~1 KB (el manifiesto) |

> El caso "cambió el troceado" es el que hace que el manifiesto sea
> **más** que una lista de archivos: es la diferencia entre 3,6 MB y 0 bytes.

### 4.5 Atomicidad: el manifiesto es el punto de_commit

Si la app se muere a mitad de una descarga, el corpus local puede quedar
inconsistente. El protocolo lo evita con un orden fijo:

```
1. Para cada material a descargar:
     a. bajar a  text/<id>__<hash8>.txt.part
     b. verificar sha256 → si no coincide: se descarta y se reintenta (hasta 2)
     c. renombrar a  text/<id>__<hash8>.txt        (atómico)
2. Borrar los materiales que sobran (los que no están en el manifiesto)
3. Escribir  manifest.json.part  → renombrar a  manifest.json   ← COMMIT
4. Escribir  state.json
5. Reindexar (en background) → al terminar, state.indexedGeneration = generation
```

- **El paso 3 es el commit**: hasta que el manifiesto nuevo está escrito, el
  estado local sigue siendo el viejo y el cliente sigue usando el corpus viejo
  (funcional). Si el manifiesto se escribe y el indexado no llega a terminar,
  el siguiente `sync` ve `localIndexGeneration != manifest.generation` y
  reindexa.
- Los archivos `.part` y los huérfanos (los que no referencia el manifiesto)
  se limpian al principio de cada `sync`: **el directorio de texto se puede
  regenerar siempre desde el manifiesto**.
- El **hash va en el nombre del archivo** (`<id>__<hash8>.txt`) → el estado
  local es **derivable del listado del directorio**: si `state.json` se
  corrompe, se reconstruye listando `text/`. Es una red de seguridad contra
  el archivo de estado corrupto (el mismo criterio que `JsonStore`, que ya
  ignora los archivos corruptos en vez de voltear la app).

> **Requiere un `move` en `FileSystem`**: hoy `kitoru.platform.FileSystem` no
> tiene `move`/`rename`, y sin renombrado atómico no hay commit. Hay que
> agregarlo:
> ```kotlin
> // core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt
> /** Renombra (mueve) un archivo. Debe ser atómico cuando el sistema lo permite. */
> fun move(from: FilePath, to: FilePath)
> ```
> Implementación JVM: `File.renameTo` (atómico en el mismo volumen; si falla,
> `copy` + `delete`). Es un cambio chico y con dos plataformas que lo soportan
> sin desugaring.

### 4.6 El manifiesto no lleva `visible`

`Material.visible` existe en el dominio, y `CourseMaterialSource` filtra por
él. **El manifiesto no necesita el campo**, y esa ausencia es una decisión de
seguridad:

- El publicador arma el manifiesto **sólo con los materiales visibles**.
- El servidor nunca recibe el material oculto → no hay filtrado que se pueda
  equivocar, ni en el cliente ni en el servidor.
- El alumno no puede saber que existió un material oculto (ni por tamaño, ni
  por cantidad).

Si más adelante hace falta "el docente ocultó un material que yo ya tenía", el
manifiesto lo resuelve solo: el `toRemove` lo borra del dispositivo en el
próximo sync. **Ocultar es una operación real, no cosmética.**

---

## 5. La indexación local

### 5.1 Dónde vive

El índice local es **exactamente el mismo tipo** que el del docente:
un `Bm25Index` sobre los `Chunk` que produce el `Chunker`. La diferencia es
de dónde salen los `Doc`:

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/LocalCorpusSource.kt
package kitoru.corpus

import rag.retrieval.Doc
import rag.retrieval.DocumentSource

/**
 * Fuente de documentos que lee el texto del corpus descargado.
 *
 * Implementa [DocumentSource], así que entra en el [rag.retrieval.KnowledgeBase]
 * **sin tocar `:backend`**: el RAG local corre el mismo motor, el mismo
 * troceador y el mismo índice que el RAG del servidor. Eso es exactamente lo
 * que significa "funciona igual que el remoto".
 */
class LocalCorpusSource(
    private val store: LocalCorpusStore,
    private val manifest: CorpusManifest,
    private val courseId: String,
    private val corpusLabel: String = "cátedra",
) : DocumentSource {

    override val label: String get() = corpusLabel

    override suspend fun load(): List<Doc> = manifest.materials.mapNotNull { entry ->
        val text = store.readText(courseId, entry.id, entry.textHash) ?: return@mapNotNull null
        Doc(
            source = corpusLabel,
            path = entry.title,
            text = text,
            docId = entry.id,          // ← requiere el `docId` de teacher/03-materiales.md §4.7
        )
    }
}
```

> `Doc` y `Chunk` hoy no tienen `docId` (`backend/.../rag/retrieval/Models.kt`).
> `teacher/03-materiales.md` §4.7 ya propone agregarlo y es **un prerrequisito
> de este diseño**: sin `docId` no se puede filtrar la búsqueda por material
> (§9.3) ni mostrar "de qué material vino este fragmento".

Y el `KnowledgeBase` se usa tal cual:

```kotlin
kb.sources = listOf(LocalCorpusSource(store, manifest, courseId))
kb.sync(onStatus)      // trocea con manifest.chunking y arma el Bm25Index
```

### 5.2 Cuándo se indexa

| Momento | Qué pasa | Dónde |
|---|---|---|
| **Al terminar la descarga** | se indexa en background | `CorpusSync.sync` lanza la indexación al final |
| **Al abrir la app** | si `state.indexedGeneration != manifest.generation` → reindexa (típicamente es un no-op) | `CorpusSync.ensureIndex` |
| **Cuando el docente cambia el troceado** | reindexa **sin descargar** | el plan marca `reindexRequired` |
| **Manual** | botón "Reindexar" en la pantalla de material (diagnóstico) | igual que el docente |

**Nunca bloquea la UI.** La indexación va en `Dispatchers.Default` (es CPU,
no I/O) y el índice se **cambia al final**, no durante: `Bm25Index` es
inmutable y `KnowledgeBase` ya tiene `@Volatile var index` → el patrón de
"swap atómico" ya está hecho. Mientras se reindexa, el alumno sigue
consultando el índice viejo (que es el de la generación anterior): **nunca hay
un estado intermedio roto**.

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/LocalCorpusIndex.kt
package kitoru.corpus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kitoru.domain.CorpusManifest
import rag.config.RagConfig
import rag.retrieval.Bm25Index
import rag.retrieval.Chunk
import rag.retrieval.Chunker
import rag.retrieval.Doc

/**
 * El índice BM25 del corpus local del alumno.
 *
 * Es el mismo [Bm25Index] que el del docente: la paridad del RAG local con el
 * remoto no depende de un formato de índice serializado, sino de correr el
 * mismo código sobre el mismo texto con el mismo troceado.
 */
class LocalCorpusIndex {

    @Volatile
    private var index: Bm25Index = Bm25Index(emptyList())

    /** La generación del manifiesto con la que se construyó el índice. */
    @Volatile
    var generation: Long = -1L
        private set

    /** Cuántos fragmentos hay indexados. */
    val chunkCount: Int get() = index.size

    /**
     * Reconstruye el índice desde el corpus local.
     *
     * @param onStatus progreso legible ("Indexando 1.203 fragmentos…").
     */
    suspend fun build(
        store: LocalCorpusStore,
        manifest: CorpusManifest,
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        val cfg = RagConfig(
            chunkSize = manifest.chunking.chunkSize,
            chunkOverlap = manifest.chunking.chunkOverlap,
        )
        val chunks = mutableListOf<Chunk>()
        var i = 0
        for (entry in manifest.materials) {
            i++
            if (i % 10 == 0) onStatus("Indexando… $i/${manifest.materials.size} materiales")
            val text = store.readText(manifest.courseId, entry.id, entry.textHash) ?: continue
            val doc = Doc("cátedra", entry.title, text, entry.id)
            chunks += Chunker.split(doc, cfg.chunkSize, cfg.chunkOverlap)
        }
        onStatus("Indexando ${chunks.size} fragmentos…")
        index = Bm25Index(chunks)   // ← el swap: el índice viejo sigue vivo hasta acá
        generation = manifest.generation
    }

    /** Busca. Es lo que usan el RAG local y el modo "sólo texto". */
    fun search(query: String, k: Int): List<Pair<Chunk, Double>> = index.search(query, k)

    /** Libera la memoria del índice (el alumno no está chateando). */
    fun release() {
        index = Bm25Index(emptyList())
    }
}
```

> `RagConfig` es de `:backend`; `LocalCorpusIndex` también debe vivir en un
> módulo que dependa de `:backend` (el módulo `:corpus` de §10.1).

### 5.3 Cuánto tarda

El cálculo está en el Anexo B, con el detalle. El resumen:

| Corpus | Fragmentos | Escritorio (JVM) | Teléfono de gama media | Teléfono de gama baja |
|---|---|---|---|---|
| Cátedra chica (10 materiales, 120 k car.) | ~120 | 15 ms | 50 ms | 120 ms |
| Cátedra típica (40 materiales, 900 k car.) | ~800 | 0,1 s | 0,3 s | 0,8 s |
| Cátedra grande (120 materiales, 3,6 M car.) | ~3 000 | 0,35 s | **1–1,5 s** | 2,5–3,5 s |

**Conclusión: indexar localmente es barato.** 3 000 fragmentos en 1,5 s en un
teléfono: es un trabajo de background, con una barra de progreso, que no
molesta. Y como el índice se reconstruye **completo** (no incremental), el
código es el mismo del docente y no hay una clase más que mantener.

> Un detalle de honestidad: el `Bm25Index` actual **no** es incremental, y por
> diseño no lo va a ser en v1. Reconstruir 3 000 fragmentos cuesta 1,5 s;
> un índice incremental costaría una clase nueva (`MutableBm25Index`) con
> merging de `df` y rebalanceo, y un bug ahí es un bug silencioso (resultados
> distintos del remoto). El trade no vale: se reconstruye.

### 5.4 La memoria

El `Bm25Index` en memoria, para 3 000 fragmentos de 1 200 caracteres:

| Estructura | Tamaño |
|---|---|
| `tfs`: 3 000 mapas de ~150 términos | ~21 MB (nodos + `Integer` boxed) |
| tablas de los mapas | ~3 MB |
| textos de los fragmentos (copias de `substring`) | ~7 MB |
| `df` (50 k términos únicos) | ~2 MB |
| **total en RAM** | **~33 MB** |

33 MB en un teléfono de gama baja con 2 GB de RAM es aceptable, pero no es
gratis. Política:

- el índice **se libera** (`release()`) cuando el alumno no está chateando y
  la app pasa a segundo plano en Android (el sistema puede matar el proceso
  en cualquier momento; retener 33 MB para nada es una invitations a que nos
  maten);
- se **reconstruye** en ~1,5 s la próxima vez que hace falta (y se puede
  reconstruir en background mientras el alumno lee);
- el texto completo en disco es mucho más barato que el índice en RAM: por eso
  el índice **no se persiste** en v1 (ver §5.5).

### 5.5 ¿Precomputar el índice en el servidor?

> **Decisión: NO. En v1 el índice se arma en el dispositivo del alumno.**

La alternativa era que el servidor serializara el `Bm25Index` (las postings
`término → tf` por fragmento) a un archivo binario y lo bajara el alumno. Las
razones para descartarla, en orden de peso:

**1. Acopla el formato al algoritmo (el riesgo principal).**
El artefacto depende de: el tokenizador (`stripDiacritics`, el regex `CAMEL`,
la lista de `STOP`), `k1`, `b`, el `chunkSize`, el `chunkOverlap` **y la
versión del código**. Cualquier cambio invisible (agregar una stopword,
cambiar una regla de tildes, tocar el `Chunker`) produce un artefacto
**silenciosamente incompatible**: el alumno recuperaría fragmentos distintos
que el docente, es decir, exactamente el fallo que este diseño tiene que
evitar. Para blindarlo hay que inventar una *huella del algoritmo* +
invalidación + regenerar el artefacto en el servidor → es la misma
complejidad que reindexar, pero con un archivo que además hay que
distribuir.

**2. El delta es pésimo.** El texto se delta bien (por hash, material por
material: si el docente sube un apunte, se baja 48 KB). El índice **no**:
las postings son todas juntas o nada → cualquier cambio del docente obliga a
re-bajar el artefacto completo (2–4 MB para 3 000 fragmentos) en lugar de
48 KB.

**3. El tamaño no compensa.** Serializado en binario compacto (id de término
varint + tf varint), el artefacto de 3 000 fragmentos mide ~1,2–1,8 MB de
postings + ~1–1,5 MB de diccionario. El texto, que hay que bajar **igual**
(para poder mostrar los fragmentos recuperados), son 3,6 MB. O sea: el artefacto
es un **+60 %** sobre lo que ya se descarga, para ahorrar 1,5 s de CPU. En
datos móviles, es un mal negocio; en WiFi de la facultad, es irrelevante.

**4. La CPU es barata y el trabajo es en background.** 1,5 s de CPU por
generación, una vez, en un hilo de fondo, mientras el alumno sigue usando la
app. No es un costo que se note.

**Lo que sí queda de esta idea (y conviene dejarlo anotado):** si algún día
hace falta (corpus enormes, o clientes con CPU muy lenta), el artefacto se
puede agregar **como optimización aditiva**, con dos condiciones:

- el manifiesto declara la huella: `chunking.algorithm = "kitoru-bm25/1"`;
- el cliente **valida la huella antes de usarlo** y, si no coincide,
  **reindexa localmente** en lugar de confiar en el artefacto.

O sea: el artefacto sería un **atajo con verificación**, no una fuente de
verdad. Con eso, el acoplamiento deja de ser un riesgo: es una optimización
que se ignora si no aplica. En v1 no se implementa, pero el hueco queda
diseñado (el `algorithm` ya está en el manifiesto).

### 5.6 La paridad del troceado

Si el cliente tiene una versión del `Chunker` distinta de la del docente
(`algorithm != "kitoru-chunker/1"`), los fragmentos pueden no coincidir. El
comportamiento definido:

| Situación | Qué hace el cliente |
|---|---|
| `algorithm` coincide | indexa con confianza |
| `algorithm` es **más nuevo** que el del cliente (el docente usa una app más nueva) | **igual indexa**, pero avisa: *"El material fue preparado con una versión más nueva de Kitoru. Los fragmentos pueden no coincidir exactamente con los del docente."* |
| `algorithm` es **más viejo** que el del cliente | indexa igual (el `Chunker` es retro-compatible por diseño: los cambios son de segmentación, no de formato) |

En los dos casos **no se bloquea**: la paridad exacta importa menos que que
el alumno pueda usar el material. Y el aviso es honesto.

---

## 6. Los cambios del docente

### 6.1 Publicar

La publicación es una **acción explícita del docente** ("Publicar a los
alumnos"), no un efecto colateral de subir un archivo. Motivo: el docente
sube, revisa el texto extraído, corrige a mano lo que salió mal y recién
ahí publica. Publicar sin querer expone material a medio preparar.

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusPublisher.kt
package kitoru.corpus

/**
 * Publica el corpus de la cátedra en el Kitoru-Server.
 *
 * Es el lado docente del canal: arma el manifiesto con los materiales
 * visibles, sube el texto de los que cambiaron y confirma el manifiesto.
 */
class CorpusPublisher(
    private val client: CorpusClient,
    private val materials: MaterialRepository,
    private val courses: CourseRepository,
    private val state: PublishedCorpusStore,   // el último manifiesto publicado
) {
    suspend fun publish(
        courseId: String,
        onProgress: (String) -> Unit = {},
    ): PublishResult {
        val course = courses.get(courseId) ?: return PublishResult.Failed("La cátedra no existe.")
        val publicables = materials.listForCourse(courseId)
            .filter { it.visible && it.status == MaterialStatus.READY && it.extractedText.isNotBlank() }
        val manifest = CorpusManifestBuilder.build(
            course = course,
            materials = publicables,
            generation = state.generation(courseId) + 1,
            publisher = CorpusPublisherInfo(
                version = appVersion(),
                publisherId = currentUserId(),
                publisherName = currentUserName(),
            ),
        )
        // 1. Subir el texto de los materiales cuyo hash cambió.
        for (entry in manifest.materials) {
            if (state.hasText(courseId, entry.id, entry.textHash)) continue   // ya está en el servidor
            onProgress("Subiendo «${entry.title}»…")
            if (!client.putText(courseId, entry.id, entry.textHash, entry.textOf())) {
                return PublishResult.Failed("No se pudo subir «${entry.title}».")
            }
        }
        // 2. Confirmar el manifiesto (el servidor valida la generación base).
        val ok = client.putManifest(manifest, baseGeneration = state.generation(courseId))
        if (!ok) return PublishResult.Conflict("Otro docente publicó mientras tanto. Volvé a publicar.")
        state.remember(manifest)
        return PublishResult.Published(manifest.generation, manifest.materials.size)
    }
}

/** Lo que el publicador recuerda del último manifiesto publicado. */
@Serializable
data class PublishedCorpusState(
    val courseId: String = "",
    val generation: Long = 0L,
    val manifestHash: String = "",
    /** materialId → textHash ya subido al servidor. */
    val texts: Map<String, String> = emptyMap(),
)
```

> `MaterialStatus` es el enum que propone `teacher/03-materiales.md` §4.1
> (`PENDING`/`EXTRACTING`/`INDEXING`/`READY`/`NEEDS_REVIEW`/`FAILED`): sólo se
> publica lo que está `READY`. Un material `NEEDS_REVIEW` (PDF escaneado) **no
> se publica**: no tiene texto estable ypublicarlo sería publicar algo que
> el modelo no puede usar.
>
> `entry.textOf()` es el texto extraído del material correspondiente: el
> `CorpusEntry` del manifiesto sólo lleva el hash y los tamaños (el texto
> viaja una vez, en el `PUT` del texto).

**Publicar es idempotente y barato**: el publicador recuerda el último
manifiesto publicado y **no vuelve a subir** el texto cuyo hash ya está en el
servidor. Publicar 30 veces al día son 30 requests de manifiesto, no 30
uploads.

### 6.2 ¿Cómo se entera el alumno?

Tres mecanismos, con una recomendación clara:

| Mecanismo | Cómo | Veredicto |
|---|---|---|
| **(a) Polling al abrir la app** | `GET manifest` con `If-None-Match` → 304 si no cambió | **Es el mecanismo de v1.** ~1 KB por polling. |
| **(a') Polling periódico en primer plano** | cada 15 min mientras la app está visible | **Sí, de v1.** El costo es despreciable y resuelve el caso "el docente publicó mientras yo tenía la app abierta". |
| **(b) Push (FCM / WebSocket)** | el servidor notifica "cambió la vN" | **No en v1.** En escritorio no hay FCM; y el payload del push no transportedía nada (sólo un "hay algo nuevo"), así que no ahorra la descarga. |

**El argumento de fondo:** cualquier mecanismo de push termina siendo "un
`GET manifest` disparado por un aviso". El valor del push es la **latencia**
(no la ancho de banda). Y la latencia no es crítica: el material de una
cátedra cambia unas pocas veces por semana, y el alumno abre la app de todas
formas.

Lo que sí conviene diseñar **ahora** es que el push sea trivial de agregar:
- el manifiesto tiene `generation` → el cliente puede compararla con la local
  y decidir si hay que hacer algo;
- el `CorpusSync` es un `suspend` idempotente → se puede llamar desde un
  push, desde el polling, desde un botón manual, sin duplicar lógica;
- si algún día se agrega push (o SSE: `GET /v1/courses/{id}/corpus/events`),
  el handler es **una línea**: `scope.launch { corpusSync.sync(courseId) }`.

**Reglas del polling:**

| Regla | Detalle |
|---|---|
| Al abrir la app (escritorio) / en `onStart` (Android) | siempre |
| Cada 15 min en primer plano | con `ETag`; 304 → no pasa nada |
| Al volver del segundo plano | sí (elElapsed puede ser largo) |
| Con datos móviles | **sí**: el manifiesto son ~2–10 KB. Los **textos**, en cambio, respetan la política de red: por defecto sólo con WiFi (`downloadOnMetered = false` en `AppSettings`). |
| Batería baja | se puede pausar; no es crítico |

**Qué muestra la UI cuando algo cambió:**

```
┌───────────────────────────────────────────────────────────┐
│  Material de la cátedra                                   │
│  24 materiales · 4,2 MB de texto · actualizado hace 2 h   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ ⚠ Hay una versión nueva del material (v12).          │  │
│  │   "Apuntes-IA.pdf" cambió y "Diapositivas" se agregó. │  │
│  │   [Actualizar]  [Después]                             │  │
│  └───────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────┘
```

El mensaje **nombra qué cambió** (los títulos, no los ids): sale de comparar
el manifiesto nuevo con el local.

### 6.3 Concurrencia entre docentes

Dos docentes pueden publicar a la vez (titular + ayudante). El servidor
resuelve con **control de concurrencia optimista** sobre `generation`:

```
PUT /v1/courses/{id}/corpus/manifest
body: { manifest, baseGeneration: 11 }

→ si la generación actual del servidor es 11: se acepta, generation = 12
→ si es 12: 409 Conflict  {"error": "generation_conflict", "currentGeneration": 12}
```

El publicador, ante un 409, **re-descarga el manifiesto, vuelve a armar el
manifiesto con los cambios propios sobre la base nueva y reintenta** (hasta 3
veces). Los conflictos de material (los dos cambiaron el mismo apunte) se
resuelven **último que publica gana**, y el manifiesto lo refleja: el `textHash`
que gana es el del último.

Es la misma política que usa Git con un push forzado, y es suficiente: el
conflicto real (dos docentes editando el mismo material al mismo tiempo) es
raro, y el conflicto de *publicaciones* (que es el de verdad) se resuelve
sin intervención humana.

### 6.4 Qué ve el docente

El servidor registra las descargas (§7.4), así que el docente puede ver:

```
Material de la cátedra
  v12 · 24 materiales · publicado hace 2 h por "Dra. Ramírez"
  Descargas: 31 de 38 alumnos tienen la v12 · 5 están en la v11
  [Ver quién no actualizó]
```

Es información **sobre el material**, no sobre los chats: no invade la
matriz de visibilidad de `domain/08-permisos.md` (que regulates los chats).
Y es útil: el docente sabe que la mitad de la clase todavía no tiene el
material nuevo.

---

## 7. Privacidad: el material de la cátedra y la IA local

### 7.1 La pregunta y la respuesta honesta

> *¿Puede el alumno usar el material de su cátedra en su IA local?*

**Respuesta corta: sí, y no hay forma técnica de impedirlo una vez que el
material está en su dispositivo.** El material descargado es un archivo en el
dispositivo del alumno, con los permisos del usuario. Un alumno que sabe
usar un teléfono puede leerlo, copiarlo, mandarlo por mail o pegarlo en
cualquier IA que quiera. No hay DRM que valga: no hay una clave, y el
decodificador está en el mismo equipo que el contenido.

Esto hay que **decirlo**, porque la alternativa es diseñar una falsa
seguridad. Un "bloqueo" que sólo funciona si el alumno no lo busca es peor que
no tener bloqueo: hace que la institución crea que tiene un control que no
tiene.

### 7.2 Los tres niveles de política

La cátedra tiene **dos banderas independientes** en `Course` (y el manifiesto
las publica como `CorpusPolicy`):

| Bandera | Qué habilita | Qué no habilita |
|---|---|---|
| `allowLocalIndex` | descargar el texto, indexarlo, **buscar** en el material | usarlo con un modelo |
| `allowLocalModels` | usar los fragmentos recuperados **con una IA en el dispositivo** | — |

| Configuración | Descargar | Buscar | IA local con el material | IA remota de la institución |
|---|---|---|---|---|
| `allowLocalIndex = false` | ✗ | ✗ | ✗ | ✓ (el servidor recupera) |
| `allowLocalIndex = true`, `allowLocalModels = false` | ✓ | ✓ | ✗ (la app no lo hace) | ✓ |
| `allowLocalIndex = true`, `allowLocalModels = true` | ✓ | ✓ | ✓ | ✓ |

**La fila del medio es la que hace valuable el modo "sólo texto"** (§9): un
alumno con IA local prohibida **igual puede buscar** en el material, porque
buscar no es generar.

### 7.3 El límite técnico, explícito

| Lo que la institución quiere | ¿Se puede garantizar? | Cómo |
|---|---|---|
| "El material no sale de la red de la facultad" | **Sí, totalmente** | el material se descarga del servidor institucional y no se envía a ningún servicio externo por Kitoru |
| "El material no se usa con IA local" | **No, si el texto está en el dispositivo** | la app no lo hace (respeta la política), pero el alumno puede leer el archivo y hacer lo que quiera |
| "El material no se usa con IA de terceros" | **Parcialmente** | Kitoru avisa y lo bloquea por default; un alumno que use otra herramienta a mano queda fuera del alcance de la app |
| "Nadie sin autorización ve el material" | **Sí** | el servidor valida la inscripción en cada pedido; el código de inscripción es un bearer token y el token de descarga se puede revocar |
| "Se sabe quién descargó qué" | **Sí** | el servidor registra cada descarga (deviceId + material + generación) |

La fila que hay que tener clara es la segunda. **El control real de la
institución sobre el material es la entrega, no el uso**: si la política dice
que el material no puede estar en dispositivos, la forma de respetarla es
**no entregarlo** (`allowLocalIndex = false`), y entonces el RAG es remoto y
nada más. Si se lo entrega, hay que aceptar que el alumno lo tiene.

> Eso no es una limitación de Kitoru: es la misma elección que hace una
> carrera al decidir si reparte el PDF del programa por el campus virtual o
> no. La diferencia es que Kitoru **deja la decisión escrita, registrada y
> visible** en vez de resolverla por la vía de los hechos.

### 7.4 Lo que sí se puede garantizar (y se implementa)

1. **La app respeta la política.** `LocalCorpusIndex` no se construye si
   `policy.allowLocalIndex == false`; `RagEngine` no recibe fragmentos si
   `policy.allowLocalModels == false`; el botón de "usar IA local" está
   deshabilitado con el motivo a la vista.
2. **El servidor registra las descargas** (`CorpusDownloadEvent`: deviceId,
   enrollmentId, materialId, generation, at). Es la única señal disponible y
   es legítima: el material se le entregó a esa persona.
3. **El material no se manda a un modelo remoto de terceros sin aviso.**
   Cuando el alumno overridea la configuración y apunta a un modelo remoto
   **que no es el de la institución**, Kitoru muestra: *"Estás usando un
   modelo remoto de terceros: el material de la cátedra se va a enviar a
   `api.ejemplo.com`."* Con un botón "Entiendo, seguir". Y el
   `RagEngine` **no** manda los fragmentos al modelo local **ni** al remoto
   de terceros si `allowLocalModels == false`.
   > Esto engancha con `domain/08-permisos.md` §3.3: `MODEL_USE_LOCAL` ya es
   > condicional a `LocalModelsAllowed` (que es `Course.allowLocalModels`).
   > Lo que se agrega acá es la distinción entre "remoto de la institución" y
   > "remoto de terceros": el primero es el modo normal de la cátedra, el
   > segundo es un override del alumno y por lo tanto amerita un aviso
   > explícito.
4. **Borrar el corpus al darse de baja.** `CorpusSync.removeCourse` borra
   la carpeta del curso (manifiesto, textos, estado). La UI lo dice: *"Te
   diste de baja de IA-2026-1. Se borró el material de la cátedra de este
   dispositivo."*
5. **El material no se envía en los chats al servidor si el alumno usa IA
   local.** Es la regla de `CONTEXTO.md`: en modo LOCAL, el texto de los
   chats no sale del dispositivo (salvo que el alumno lo comparta).

### 7.5 La aceptación de las condiciones

Si la cátedra quiere un rastro explícito, `policy.requireAcceptance = true`
hace que la app pida aceptar las condiciones **antes del primer download**:

```
┌───────────────────────────────────────────────────────┐
│  Material de IA-2026-1                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ Material de la cursada IA-2026-1.                │  │
│  │ Uso académico interno — IA-2026-1                │  │
│  │                                                 │  │
│  │ No lo redistribuyas fuera del equipo.            │  │
│  │                                                 │  │
│  │ [ ] Entiendo y acepto                            │  │
│  └─────────────────────────────────────────────────┘  │
│  [Cancelar]                    [Aceptar y descargar]   │
└───────────────────────────────────────────────────────┘
```

La aceptación se guarda **localmente** (para no depender de la red) y se
reporta al servidor (`POST /v1/courses/{id}/corpus/accept`) → el docente ve
quién aceptó. **Es un acto de buena fe, no un candado**: sirve para tener el
registro, no para impedir nada. Conviene decirlo en la propia pantalla ("el
registro de aceptación es informativo"), porque prometer más de lo que se
cumple es la forma rápida de perder la confianza de la institución.

### 7.6 Lo que la política **no** puede cubrir

| Limitación | Por qué |
|---|---|
| El alumno puede **copiar** el texto a otro lado | es un archivo en su dispositivo |
| El alumno puede **dar su dispositivo** con el material | es material que se le entregó |
| Un alumno puede **reenviar** el material a otro alumno | el código de inscripción ya es un bearer token de grupo (`09-identidad.md` §6.3); esto no lo empeora, pero no lo arregla |
| Un alumno puede **OCR-ear** el material si no se le entrega el texto | por eso el `allowLocalIndex = false` tiene que ser real (no "le doy el PDF y que no lo indexe") |

### 7.7 La baja de la inscripción

Cuando el alumno se da de baja (o el docente lo desinscribe):

1. El servidor invalida su token de corpus → los pedidos de material fallan
   con 403.
2. La app borra el corpus local (`LocalCorpusStore.clear(courseId)`).
3. La UI lo dice explícitamente, porque borrar datos sin avisar es
  -hospitalario: *"Se borró el material de la cátedra de este dispositivo."*

---

## 8. Caché, espacio y cuotas

### 8.1 El layout en disco

Raíz: `KitoruPaths.coursesDir` (`<dataDir>/courses`), que ya existe.

```
<dataDir>/courses/<courseId>/corpus/
├── manifest.json            # el manifiesto tal cual lo devolvió el servidor
├── state.json               # estado local: generación, troceado, por-material
├── text/
│   ├── mat_7f3a2b__9f2c1d40.txt     # <id>__<hash8>.txt  (el hash en el nombre)
│   └── mat_1c9d40__44e8a0b1.txt
├── original/                # opcional, si el alumno pidió los originales
│   └── mat_7f3a2b__1a7b93c4.pdf
└── .part/                   # descargas en curso (se limpian al empezar)
```

| Ruta | Contenido | Quién la escribe |
|---|---|---|
| `corpus/manifest.json` | el manifiesto de la generación local | `CorpusSync` (§4.5) |
| `corpus/state.json` | `{courseId, generation, chunking, materials: {id → {textHash, textBytes, chunkCount, downloadedAt}}, lastSyncAt, indexedGeneration}` | `CorpusSync` |
| `corpus/text/<id>__<hash8>.txt` | el texto extraído, UTF-8 | `CorpusSync` |
| `corpus/original/<id>__<hash8>.<ext>` | el archivo original (a pedido) | `CorpusSync` |

> El nombre del archivo lleva el hash → **el estado local es derivable del
> listado del directorio**. `state.json` es una caché de la UI (contadores,
> fecha), no la fuente de verdad. Si se corrompe, se regenera listando
> `text/`.

> El directorio de la cátedra (`courses/<courseId>/`) es el mismo que usa el
> resto de la app (los materiales del docente, los chats). El subdirectorio
> `corpus/` es del alumno: **no se mezcla** con `files/` (los originales del
> docente) ni con `config/materials.json`.

### 8.2 Cuánto ocupa

| Corpus | Texto (disco) | Originales (a pedido) | RAM con el índice | Descarga (gzip) |
|---|---|---|---|---|
| Cátedra chica (10 mat., 120 k car.) | 120 KB | 4 MB | 4 MB | 35 KB |
| Cátedra típica (40 mat., 900 k car.) | 0,9 MB | 26 MB | 11 MB | 240 KB |
| Cátedra grande (120 mat., 3,6 M car.) | 3,6 MB | 95 MB | 33 MB | 0,9 MB |
| Corpus extremo (400 mat., 15 M car.) | 15 MB | 400 MB | 120 MB ⚠ | 3,8 MB |

**Conclusiones prácticas:**

- El **texto** es barato: una cátedra típica ocupa **menos de 1 MB** en el
  teléfono. Se puede dejar descargado sin pedir permiso.
- Los **originales** son 10–25× más pesados: 26 MB en una cátedra típica,
  95 MB en una grande. Por eso son **a pedido** y no por defecto.
- El **índice en RAM** es el costo real: 33 MB en una cátedra grande. De ahí
  la política de liberarlo (§5.4).

### 8.3 Cuotas y el material enorme

| Límite | Valor por defecto | Dónde se aplica |
|---|---|---|
| `maxCorpusTextBytes` (por cátedra) | 200 MB | **el servidor**, al publicar |
| `maxMaterialTextBytes` (por material) | 2 MB (2 000 000 caracteres) | el publicador (ya está en `teacher/03-materiales.md` §4.9: `maxCharsPerMaterial`) |
| `maxMaterialOriginalBytes` | 50 MB | el publicador |
| `maxCorpusTextBytes` (por dispositivo, total) | 500 MB | el cliente, antes de descargar |
| Espacio libre requerido | 2× el tamaño a descargar | el cliente, antes de descargar |

**La cuota se aplica en el servidor, en la publicación**, no en el cliente:
si el corpus no entra, el problema es del docente y se le dice ahí, con
números:

> *"El corpus de IA-2026-1 pesa 340 MB de texto y el límite por cátedra es
> 200 MB. Bajá la cantidad de material, quitá los originales de la
> distribución o dividí la cátedra."*

Si el límite se aplicara en el cliente, el alumno vería un error de
descarga sin saber por qué, y el docente no se enteraría nunca.

**Si el material es enorme** (un libro de 300 MB): el pipeline de extracción
ya lo recorta (`maxCharsPerMaterial`), y el texto resultante entra bien. El
original es lo que no entra → `originalHash = ""` y no se distribuye. Es
decir: **el recorte no es un parche, es el diseño**: lo que se distribuye es
el texto, y el texto tiene un tope.

En el cliente, antes de descargar: si no hay espacio → *"No hay espacio
suficiente en el dispositivo: necesitás 8,4 MB y tenés 3,1 MB libres."* y se
ofrece borrar el material de otras cátedras.

### 8.4 Liberación de espacio

- **"Liberar espacio"** en la pantalla de la cátedra: borra los **textos** y
  deja el manifiesto → el alumno puede volver a descargarlos cuando quiera
  (el índice se reconstruye en 1,5 s).
- **Evicción por LRU** entre cátedras cuando el disco está al 90 %: nunca se
  evicciona la cátedra que el alumno está usando, y **la evicción es
  explícita en la UI** ("Se borró el material de Redes-2026-1 para liberar
  espacio"). Nunca se borra material a escondidas: el alumno puede haber
  pagado datos por esa descarga.
- El texto borrado **se vuelve a bajar cuando el alumno lo pide de nuevo**
  (o cuando vuelve a usar la búsqueda): si el docente no tocó el material, el
  hash del manifiesto es el mismo y la descarga es la de siempre (48 KB). El
  manifiesto sigue siendo la fuente de verdad: no hace falta guardar el texto
  para saber si hay que bajarlo. El estado local lo refleja con
  `chunkCount = 0`.

---

## 9. El modo "sólo texto"

### 9.1 Qué es

> **Buscar en el material de la cátedra, sin usar ningún modelo.**

Es un buscador local sobre el mismo índice BM25 que usa el RAG, sin LLM, sin
red y sin credenciales. El alumno escribe una pregunta y ve **los fragmentos
del material que la contienen**, con su encabezado, su material y su puntaje.

### 9.2 Por qué es importante

| Argumento | Detalle |
|---|---|
| **Es gratis** | el índice ya existe (o se construye en 1,5 s). No llama a un modelo: no cuesta ni un token ni latencia. |
| **Funciona sin red** | en un avión, en el campo, en el subsuelo. El corpus ya está en el dispositivo. |
| **Funciona sin permiso de IA local** | `allowLocalIndex = true` + `allowLocalModels = false` → el alumno puede buscar igual. Es exactamente el caso que la política de la cátedra suele esconder sin querer. |
| **Responde una pregunta real** | "¿en qué página está la definición de perceptrón?" → un fragmento con la cita exacta. Muchos alumnos no quieren una respuesta de la IA: quieren **el pasaje**. |
| **Es honesto** | muestra el texto real, con su encabezado ("Página 7"), no una paráfrasis. |
| **Es la puerta de entrada** | el alumno que sólo quiere buscar igual termina usando el corpus; el que quiere la IA termina descubriendo que tiene el material local. |

### 9.3 La UI

```
┌──────────────────────────────────────────────────────────────┐
│  🔍 Buscar en el material                        [IA-2026-1 ▾]│
│  ┌──────────────────────────────────────────────────────────┐│
│  │ perceptrón multicapa                                    ││
│  └──────────────────────────────────────────────────────────┘│
│  14 resultados · 1,2 s                                     │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐   │
│  │ 12,43   cátedra · Apuntes-IA.pdf · Página 7             │   │
│  │ …el **perceptrón** multicapa se define como una unidad…  │   │
│  └────────────────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────────┐   │
│  │  3,10   cátedra · Apuntes-IA.pdf · Página 3             │   │
│  │ …las redes **perceptrón** se componen de capas…         │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                               │
│  [ ⚠ El índice es léxico (BM25): no entiende sinónimos.      │
│    "auto" no encuentra "automóvil". ]                        │
└──────────────────────────────────────────────────────────────┘
```

Elementos:

- **Un filtro por material** ("buscar sólo en Apuntes-IA") → requiere el
  `docId` en los fragmentos (§5.1).
- **El aviso de BM25**: es la misma advertencia que ya aparece en la
  pestaña "Probar pregunta" del docente (`teacher/03-materiales.md` §5.3):
  el índice es léxico, no vectorial. Ocultarlo garantiza las decepciones.
- **"Copiar el fragmento"** y **"Abrir el material"** (si se descargó el
  original) → el resultado es accionable.
- **Nada de esto habla con el modelo**: el botón "Preguntar a la IA con estos
  resultados" es opcional y es el único punto donde el RAG entra.

### 9.4 La implementación

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusSearch.kt
package kitoru.corpus

/**
 * Búsqueda en el corpus local, sin modelo.
 *
 * Es el mismo [rag.retrieval.Bm25Index] que usa el RAG: la búsqueda "sólo
 * texto" y la recuperación del RAG son la misma operación, una con modelo y
 * la otra sin.
 */
class CorpusSearch(private val index: LocalCorpusIndex) {

    data class Hit(
        val materialId: String,
        val materialTitle: String,
        val heading: String,
        val snippet: String,
        val score: Double,
    )

    /**
     * @param materialId si no es null, busca sólo en ese material.
     */
    fun search(query: String, k: Int = 20, materialId: String? = null): List<Hit> =
        index.search(query, k * 3)
            .filter { materialId == null || it.first.docId == materialId }
            .take(k)
            .map { (chunk, score) ->
                Hit(
                    materialId = chunk.docId,
                    materialTitle = chunk.path,
                    heading = chunk.heading,
                    snippet = snippetAround(chunk.text, query),
                    score = score,
                )
            }

    /** Recorta el fragmento alrededor de la coincidencia, con el término marcado. */
    private fun snippetAround(text: String, query: String, radius: Int = 160): String {
        val terms = rag.retrieval.Bm25Index.tokenize(query).distinct()
        if (terms.isEmpty()) return text.take(radius * 2)
        // Se busca la primera coincidencia de cualquiera de los términos.
        val lower = text.lowercase()
        val at = terms.mapNotNull { t -> lower.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        val from = maxOf(0, at - radius)
        val to = minOf(text.length, at + radius)
        val cut = text.substring(from, to)
        return (if (from > 0) "…" else "") + cut + (if (to < text.length) "…" else "")
    }
}
```

El resaltado del término se hace con el **mismo tokenizador** del índice
(`Bm25Index.tokenize`), así que lo que se resalta es exactamente lo que se
indexó.

### 9.5 Cuando el corpus no está descargado

La pantalla de búsqueda muestra el estado real y ofrece la acción:

```
┌──────────────────────────────────────────────────────────┐
│  Buscar en el material                                   │
│                                                          │
│  El material de IA-2026-1 no está en este dispositivo.   │
│  24 materiales · 4,2 MB de texto                        │
│                                                          │
│  [Descargar material (4,2 MB)]      [Buscar sólo en lo   │
│                                      que ya tenés]       │
└──────────────────────────────────────────────────────────┘
```

La segunda opción (buscar en lo que ya está) es un detalle de cortesía que
evita el "no puedo buscar nada" cuando el alumno tiene 3 de 24 materiales.

---

## 10. Clases, archivos y firmas

### 10.1 El módulo `:corpus` (nuevo)

El material del alumno necesita tres cosas que hoy están en módulos
distintos: los modelos de dominio (`:core`), el RAG (`:backend`) y el cliente
HTTP (`ktor.client`, que hoy sólo está en `:llm-api`). Se crea un módulo
`:corpus` que depende de `:core` y `:backend` y agrega el cliente Ktor:

```kotlin
// corpus/build.gradle.kts
kotlin {
    // … igual que :llm-api
    commonMain.dependencies {
        api(project(":core"))
        api(project(":backend"))
        api(libs.ktor.client.core)
    }
    desktopMain.dependencies { implementation(libs.ktor.client.cio) }
    androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
}
```

```
corpus/src/commonMain/kotlin/kitoru/corpus/
├── CorpusClient.kt        # el cliente HTTP (manifiesto, texto, original, publish)
├── CorpusManifestBuilder.kt # arma el manifiesto desde los materiales
├── CorpusPublisher.kt     # el lado docente: publicar el corpus
├── CorpusSearch.kt        # el modo "sólo texto"
├── CorpusSource.kt        # CorpusSource: ServerCorpusSource / BundleCorpusSource
├── CorpusSync.kt          # el orquestador (estado + sincronización)
├── CorpusSyncPlanner.kt   # la función pura del plan de deltas
├── LocalCorpusIndex.kt    # el Bm25Index local
├── LocalCorpusSource.kt   # la fuente local (DocumentSource) ← la pieza clave
├── LocalCorpusStore.kt    # el disco: manifiesto, estado, textos
└── CorpusState.kt         # los estados de la UI (CorpusSyncState, SyncOutcome, LocalCorpusState)
```

### 10.2 `CorpusSource`: los dos canales, un solo código

El canal servidor y el paquete offline se unifican **por arriba**: los dos
terminan dejando el corpus en el `LocalCorpusStore` y devolviendo el mismo
manifiesto. El `CorpusSync` no sabe de dónde vino.

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusSource.kt
package kitoru.corpus

import kitoru.domain.CorpusEntry
import kitoru.domain.CorpusManifest

/**
 * De dónde sale el corpus.
 *
 * Dos implementaciones:
 *  - [ServerCorpusSource]: el Kitoru-Server (HTTP, con ETag y gzip).
 *  - [BundleCorpusSource]: un paquete exportado por el docente (offline).
 *
 * La diferencia entre las dos se queda adentro: el resto de la app (el
 * store, el índice, el RAG, la búsqueda) no se entera.
 */
interface CorpusSource {
    /** Etiqueta para la UI ("servidor de la facultad" / "paquete local"). */
    val label: String

    /** El manifiesto vigente, o null si no hay nada que leer. */
    suspend fun manifest(courseId: String): CorpusManifest?

    /**
     * El texto de un material.
     * @return el texto, o null si no se pudo (no cambió → null no es un error).
     */
    suspend fun fetchText(courseId: String, entry: CorpusEntry): String?

    /** El original (opcional). Null si no se distribuye o no existe. */
    suspend fun fetchOriginal(courseId: String, entry: CorpusEntry): ByteArray?
}

/** El canal del servidor institucional. */
class ServerCorpusSource(private val client: CorpusClient) : CorpusSource {
    override val label get() = "servidor de la facultad"
    override suspend fun manifest(courseId: String): CorpusManifest? =
        when (val r = client.fetchManifest(courseId)) {
            is ManifestResult.Updated -> r.manifest
            ManifestResult.NotModified -> null      // el que llama ya lo tiene
        }
    override suspend fun fetchText(courseId: String, entry: CorpusEntry): String? =
        client.fetchText(courseId, entry)
    override suspend fun fetchOriginal(courseId: String, entry: CorpusEntry): ByteArray? =
        client.fetchOriginalBytes(courseId, entry)
}

/** El canal offline: un paquete exportado por el docente. */
class BundleCorpusSource(private val bundleDir: FilePath) : CorpusSource {
    override val label get() = "paquete importado"
    override suspend fun manifest(courseId: String): CorpusManifest? =
        readManifestFile(bundleDir.resolve("manifest.json"))
    override suspend fun fetchText(courseId: String, entry: CorpusEntry): String? =
        readTextFile(bundleDir.resolve("text").resolve(textFileName(entry)))
    override suspend fun fetchOriginal(courseId: String, entry: CorpusEntry): ByteArray? =
        if (entry.originalHash.isBlank()) null
        else readBytesFile(bundleDir.resolve("original").resolve(originalFileName(entry)))
}
```

> El nombre del archivo de texto es **el mismo** en los dos canales
> (`<id>__<hash8>.txt`): el store no distingue de dónde vino el material, y
> por eso el bundle importado y el descargado producen **exactamente el mismo
> estado local** → el mismo índice → los mismos fragmentos.
>
> Los helpers (`readManifestFile`, `readTextFile`, `readBytesFile`,
> `textFileName`, `originalFileName`) son privados del archivo y usan el
> `FileSystem` común: funcionan igual en escritorio y en Android.

### 10.3 Los modelos (en `:core`, para que los comparta el futuro servidor)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusManifest.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * El manifiesto del corpus de una cátedra.
 *
 * Es el contrato entre el Kitoru-Server y el cliente: con este archivo el
 * alumno sabe qué material hay, qué versión tiene y qué falta bajarse, sin
 * descargar nada.
 *
 * Está en `:core` (y no en `:corpus`) porque es un **contrato de dominio**:
 * el publicador lo arma, el cliente lo consume y el futuro servidor lo
 * produce.
 */
@Serializable
data class CorpusManifest(
    /** Versión del formato. 1 = el primero. */
    val formatVersion: Int = 1,
    val courseId: String,
    val course: CorpusCourseRef = CorpusCourseRef(),
    /** Versión del corpus: sube en cada publicación. */
    val generation: Long = 0L,
    val publishedAt: Long = 0L,
    val publisher: CorpusPublisherInfo = CorpusPublisherInfo(),
    /** Cómo trocear para obtener los mismos fragmentos que el docente. */
    val chunking: CorpusChunking = CorpusChunking(),
    /** Lo que la cátedra permite hacer con el material en el dispositivo. */
    val policy: CorpusPolicy = CorpusPolicy(),
    val materials: List<CorpusEntry> = emptyList(),
    /** sha-256 del manifiesto sin este campo. Es el ETag. */
    val manifestHash: String = "",
)

/** Identidad mínima de la cátedra, para mostrar sin consultar nada más. */
@Serializable
data class CorpusCourseRef(
    val code: String = "",
    val name: String = "",
    val term: String = "",
)

/** Quién publicó el corpus. */
@Serializable
data class CorpusPublisherInfo(
    val app: String = "Kitoru",
    val version: String = "",
    val publisherId: String = "",
    val publisherName: String = "",
)

/**
 * Cómo hay que trocear el texto.
 *
 * Viaja en el manifiesto porque el **troceado es parte de la paridad**: si el
 * cliente trocea distinto, recupera fragmentos distintos y el RAG local deja
 * de coincidir con el remoto.
 */
@Serializable
data class CorpusChunking(
    /** Huella del troceador. "kitoru-chunker/1". */
    val algorithm: String = "kitoru-chunker/1",
    val chunkSize: Int = 1200,
    val chunkOverlap: Int = 150,
)

/**
 * La política de la cátedra sobre el material en el dispositivo.
 *
 * Son **dos banderas independientes** a propósito: se puede permitir tener
 * el material y buscar en él (barato, sin modelo) sin permitir usarlo con
 * una IA local (que es lo que el docente suele temer).
 */
@Serializable
data class CorpusPolicy(
    /** El alumno puede descargar e indexar el material en su dispositivo. */
    val allowLocalIndex: Boolean = true,
    /** El alumno puede usar el material con una IA en su dispositivo. */
    val allowLocalModels: Boolean = true,
    /** El alumno puede descargar el archivo original (además del texto). */
    val allowOriginalDownload: Boolean = true,
    /** Hay que aceptar las condiciones antes del primer download. */
    val requireAcceptance: Boolean = false,
    /** Texto de las condiciones (se muestra en la pantalla de aceptación). */
    val notice: String = "",
    /** Licencia declarada por la cátedra. */
    val license: String = "",
)

/**
 * Un material del corpus: su versión (hash), su tamaño y su fecha.
 */
@Serializable
data class CorpusEntry(
    val id: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val tags: List<String> = emptyList(),
    /** sha-256 del texto UTF-8, con prefijo "sha256:". */
    val textHash: String,
    val textBytes: Long = 0L,
    val textChars: Int = 0,
    /** sha-256 del original; vacío si el original no se distribuye. */
    val originalHash: String = "",
    val originalBytes: Long = 0L,
    val originalName: String = "",
    val contentType: String = "",
    val updatedAt: Long = 0L,
    /** Aviso del docente sobre el material (se propaga al alumno). */
    val warning: String = "",
)
```

**Y en `Course` (`:core`)**, dos campos nuevos:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt (agregar)
data class Course(
    // … los campos actuales …
    /**
     * Si el alumno puede tener el material de la cátedra en su dispositivo.
     * (La extracción y el índice son una cosa; usarlo con una IA local, otra:
     * eso lo decide [allowLocalModels].)
     */
    val allowLocalIndex: Boolean = true,
    /** Texto legal que se muestra al aceptar las condiciones del material. */
    val corpusNotice: String = "",
)
```

### 10.4 `LocalCorpusStore` y el estado local

El estado local **no** es parte del contrato con el servidor → vive en
`:corpus`, no en `:core`:

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusState.kt
package kitoru.corpus

import kotlinx.serialization.Serializable
import kitoru.domain.CorpusChunking

/**
 * El estado local del corpus de una cátedra.
 *
 * Es una **caché**: la verdad son los archivos de `text/` (el hash va en el
 * nombre del archivo), así que si este archivo se corrompe se puede
 * reconstruir listando el directorio.
 */
@Serializable
data class LocalCorpusState(
    val courseId: String = "",
    /** La generación del manifiesto local. */
    val generation: Long = 0L,
    /** La generación con la que se construyó el índice. */
    val indexedGeneration: Long = -1L,
    /** El troceado con el que se construyó el índice. */
    val chunking: CorpusChunking = CorpusChunking(),
    /** MaterialId → estado local. */
    val materials: Map<String, LocalMaterial> = emptyMap(),
    val lastSyncAt: Long = 0L,
    /** Último error, para mostrarlo en la UI. */
    val lastError: String = "",
)

/** El estado local de un material. */
@Serializable
data class LocalMaterial(
    val id: String,
    /** El hash del texto que está en el disco. */
    val textHash: String,
    val textBytes: Long = 0L,
    /** Cuántos fragmentos aporta al índice (0 si no se ha indexado). */
    val chunkCount: Int = 0,
    val downloadedAt: Long = 0L,
)
```

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/LocalCorpusStore.kt
package kitoru.corpus

import kitoru.domain.CorpusEntry
import kitoru.domain.CorpusManifest
import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths

/**
 * El disco del corpus local: manifiesto, estado y textos.
 *
 * Layout: <coursesDir>/<courseId>/corpus/{manifest.json, state.json, text/, original/}
 */
class LocalCorpusStore(
    private val fs: FileSystem,
    private val root: FilePath = KitoruPaths.coursesDir,
) {
    fun dir(courseId: String): FilePath = root.resolve(courseId).resolve("corpus")

    suspend fun readManifest(courseId: String): CorpusManifest?
    suspend fun writeManifest(courseId: String, manifest: CorpusManifest)   // atómico
    suspend fun readState(courseId: String): LocalCorpusState
    suspend fun writeState(courseId: String, state: LocalCorpusState)

    /** ¿Está el texto de este material con ESTE hash? */
    suspend fun hasText(courseId: String, id: String, hash: String): Boolean

    /** Escribe el texto de forma atómica y devuelve la ruta. */
    suspend fun writeText(courseId: String, entry: CorpusEntry, text: String): FilePath

    suspend fun readText(courseId: String, id: String, hash: String): String?
    suspend fun removeMaterial(courseId: String, id: String)
    suspend fun clear(courseId: String)                 // borra toda la carpeta
    suspend fun usageBytes(courseId: String): Long
    suspend fun collectGarbage(courseId: String)        // borra .part y huérfanos
}
```

### 10.5 `CorpusSync` (el orquestador)

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusSync.kt
package kitoru.corpus

/**
 * Sincroniza el corpus de una cátedra con el dispositivo.
 *
 * Es idempotente: se puede llamar desde el polling, desde un botón, desde un
 * push o desde el arranque de la app, y siempre hace lo que falta.
 */
class CorpusSync(
    private val source: CorpusSource,        // servidor o paquete: no sabe cuál
    private val store: LocalCorpusStore,
    private val index: LocalCorpusIndex,
    private val scope: CoroutineScope,
) {
    /** El estado, para la UI. */
    val state: StateFlow<CorpusSyncState>

    /** El manifiesto local (null si todavía no se descargó nada). */
    suspend fun localManifest(courseId: String): CorpusManifest?

    /**
     * Sincroniza. [force] ignora la generación local y re-descarga el manifiesto
     * (no los textos: el delta es siempre por hash).
     */
    suspend fun sync(courseId: String, force: Boolean = false): SyncOutcome

    /** Descarga el corpus completo bajo demanda (el botón "Activar material"). */
    suspend fun downloadAll(courseId: String): SyncOutcome

    /** Reindexa si hace falta. Devuelve si el índice quedó al día. */
    suspend fun ensureIndex(courseId: String): Boolean

    /** Borra el corpus de una cátedra (baja de inscripción). */
    suspend fun removeCourse(courseId: String)
}

/**
 * El algoritmo de [CorpusSync.sync], en orden (es lo que hay que leer para
 * entender la atomicidad):
 *
 *  1. state  ← store.readState(courseId)
 *  2. manifest ← source.manifest(courseId)      (304 → no hay nada nuevo)
 *  3. si !force && manifest.manifestHash == state.manifestHash → UpToDate
 *  4. plan ← CorpusSyncPlanner.plan(manifest, state.materials, state.chunking, state.indexedGeneration)
 *  5. si !manifest.policy.allowLocalIndex → Forbidden("La cátedra no permite tener el material en este dispositivo.")
 *  6. por cada e en plan.toDownload:
 *       texto ← source.fetchText(courseId, e)
 *       si sha256(texto) != e.textHash → se descarta y se reintenta (2 veces)
 *       store.writeText(courseId, e, texto)        (atómico)
 *  7. por cada id en plan.toRemove → store.removeMaterial(id)
 *  8. store.writeManifest(courseId, manifest)      ← EL COMMIT
 *  9. store.writeState(nuevo estado)
 * 10. si plan.reindexRequired → index.build(store, manifest)   (background)
 * 11. → Updated(generation, descargados, bytes)
 */

/** El estado de la sincronización, para la UI. */
sealed interface CorpusSyncState {
    data object Idle : CorpusSyncState
    data object Checking : CorpusSyncState              // consultando el manifiesto
    data class UpToDate(val generation: Long) : CorpusSyncState
    data class Downloading(
        val done: Int, val total: Int, val bytes: Long,
    ) : CorpusSyncState
    data class Indexing(val chunks: Int) : CorpusSyncState
    data class Ready(val generation: Long, val chunks: Int) : CorpusSyncState
    data class Stale(val localGeneration: Long, val remoteGeneration: Long) : CorpusSyncState
    data class Forbidden(val reason: String) : CorpusSyncState
    data class Failed(val reason: String) : CorpusSyncState
}

/** El resultado de una sincronización. */
sealed interface SyncOutcome {
    data object UpToDate : SyncOutcome
    data class Updated(val generation: Long, val downloaded: Int, val bytes: Long) : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
    data class Forbidden(val reason: String) : SyncOutcome
}
```

### 10.6 `CorpusClient`

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusClient.kt
package kitoru.corpus

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Cliente HTTP del corpus.
 *
 * Ktor, como el resto del cliente de `llm-api` (CIO en escritorio, OkHttp en
 * Android). Es el único lugar que conoce las URLs y el formato de la red.
 */
class CorpusClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val token: suspend () -> String?,     // del CredentialStore
) {
    /** El manifiesto. `NotModified` si el ETag coincide. */
    suspend fun fetchManifest(courseId: String): ManifestResult

    /** El texto de un material. Verifica el hash. */
    suspend fun fetchText(courseId: String, entry: CorpusEntry): String?

    /**
     * El original (opcional), como bytes.
     * Null si la política no lo permite o si el material no lo distribuye.
     */
    suspend fun fetchOriginalBytes(courseId: String, entry: CorpusEntry): ByteArray?

    /**
     * El original escrito directo al disco (para no cargarlo en memoria:
     * un original puede pesar 50 MB). Devuelve los bytes escritos.
     */
    suspend fun fetchOriginal(courseId: String, entry: CorpusEntry, dest: FilePath, fs: FileSystem): Long

    /** Publicar (lado docente). */
    suspend fun putManifest(manifest: CorpusManifest, baseGeneration: Long): Boolean
    suspend fun putText(courseId: String, id: String, hash: String, text: String): Boolean
}

sealed interface ManifestResult {
    data class Updated(val manifest: CorpusManifest) : ManifestResult
    data object NotModified : ManifestResult
}
```

### 10.7 El publicador (lado docente)

Ya está en §6.1. Los tipos de apoyo:

```kotlin
// corpus/src/commonMain/kotlin/kitoru/corpus/CorpusManifestBuilder.kt
package kitoru.corpus

import kitoru.domain.*

object CorpusManifestBuilder {
    /**
     * Arma el manifiesto a partir de los materiales publicables de la cátedra.
     *
     * Sólo entran los materiales `visible` (los ocultos no salen del
     * dispositivo del docente) → el manifiesto no tiene campo `visible`
     * porque lo que no se publica no está.
     */
    fun build(
        course: Course,
        materials: List<Material>,
        generation: Long,
        publisher: CorpusPublisherInfo = CorpusPublisherInfo(),
    ): CorpusManifest
}
```

El `manifestHash` se calcula **al final**, sobre el manifiesto con el campo
vacío (si no, el hash dependería de sí mismo).

### 10.8 Los cambios requeridos en `:core` y `:backend`

| Cambio | Dónde | Por qué |
|---|---|---|
| `CorpusManifest`, `CorpusEntry`, `CorpusChunking`, `CorpusPolicy`, `CorpusCourseRef`, `CorpusPublisherInfo` | `core/.../kitoru/domain/CorpusManifest.kt` | los modelos del contrato |
| `Course.allowLocalIndex`, `Course.corpusNotice` | `core/.../kitoru/domain/Course.kt` | la política de la cátedra |
| `sha256Hex` (expect/actual) | `core/.../kitoru/platform/Hashing.kt` | el hash del manifiesto y de los textos |
| `FileSystem.move(from, to)` | `core/.../kitoru/platform/FileSystem.kt` | el commit atómico del manifiesto |
| `FileSystem.writeBytes(path, bytes, append)` | ídem | la descarga de los originales (>2 MB) sin meterlo todo en memoria |
| `Doc.docId`, `Chunk.docId` | `backend/.../rag/retrieval/Models.kt` | identificar de qué material salió cada fragmento (propuesto en `teacher/03-materiales.md` §4.7) |
| `KnowledgeBase.retrieveScored` | `backend/.../rag/retrieval/KnowledgeBase.kt` | el puntaje, para el modo "sólo texto" y para la UI |

**Ninguno de estos cambios toca el algoritmo del RAG**: el `Bm25Index`, el
`Chunker` y el `RagEngine` quedan como están. Esa es la prueba de que la
paridad se logra por construcción y no por un formato serializado.

---

## 11. El API del servidor (Kitoru-Server)

| Método | Ruta | Auth | Qué hace |
|---|---|---|---|
| `GET` | `/v1/courses/{courseId}/corpus/manifest` | token de sesión / API key / código | devuelve el manifiesto, con `ETag: "<manifestHash>"`. `If-None-Match` → **304**. |
| `GET` | `/v1/courses/{courseId}/corpus/text/{materialId}` | ídem | el texto en `text/plain; charset=utf-8`, con `ETag` (el `textHash`) y `Content-Encoding: gzip`. |
| `GET` | `/v1/courses/{courseId}/corpus/original/{materialId}` | ídem | el archivo original. **403** si `policy.allowOriginalDownload == false`. |
| `POST` | `/v1/courses/{courseId}/corpus/accept` | ídem | registra la aceptación de las condiciones (si `requireAcceptance`). |
| `PUT` | `/v1/courses/{courseId}/corpus/text/{materialId}` | rol docente | sube el texto. El servidor **verifica el hash** del body: si no coincide → **412**. |
| `PUT` | `/v1/courses/{courseId}/corpus/manifest` | rol docente | publica. `baseGeneration` desfasado → **409**. |
| `GET` | `/v1/courses/{courseId}/corpus/downloads` | rol docente | quién descargó qué (registro de descargas). |
| `GET` | `/v1/courses/{courseId}/corpus/events` | token de sesión | **opcional (v2)**: SSE con `{generation}` cuando cambia. |

**Códigos de error y su mensaje en la app:**

| Código | Significado | Qué muestra el alumno |
|---|---|---|
| 401 | sin credencial / token vencido | *"Tu sesión con la institución expiró. Volvé a vincular tu cuenta."* (el texto de `09-identidad.md` §8.3) |
| 403 | la política no lo permite | *"La cátedra no permite tener el material en este dispositivo."* |
| 403 | no está inscripto | *"Ya no estás inscripto en IA-2026-1."* (+ se borra el corpus local) |
| 404 | material o curso inexistente | *"Este material ya no está en la cátedra."* |
| 409 | conflicto de publicación | (lado docente) *"Otro docente publicó mientras tanto. Volvé a publicar."* |
| 412 | el hash del texto no coincide | (lado docente) *"El texto que se subió está corrupto. Volvé a subirlo."* |
| 413 | el texto excede el tope | (lado docente) *"El material «X» supera el máximo de 2 MB de texto."* |
| 429 | cuota de la cátedra excedida | (lado docente) *"El corpus de la cátedra supera los 200 MB. Bajá material."* |

**Dónde guarda el servidor el corpus:**

```
<data>/corpus/<courseId>/
├── manifest.json              # el manifiesto actual (el puntero)
├── blobs/<sha256>.txt         # los textos, direccionados por contenido
├── blobs/<sha256>.<ext>       # los originales (opcional)
└── downloads.log              # append-only: quién bajó qué
```

**Direccionado por contenido**: dos docentes que suben el mismo texto
ocupan una sola copia, y un texto nunca se muta (es inmutable por hash) → el
caché del CDN/proxy y las descargas repetidas funcionan.

---

## 12. El paquete offline: `.kitoru-corpus`

El camino sin servidor (o el respaldo). Formato: **una carpeta**, no un zip
(para no tener que cargar 300 MB en memoria):

```
IA-2026-1.corpus/
├── manifest.json
├── text/
│   ├── mat_7f3a2b__9f2c1d40.txt
│   └── mat_1c9d40__44e8a0b1.txt
└── original/                     # opcional, si el docente distribuye los originales
    └── mat_7f3a2b__1a7b93c4.pdf
```

- Se genera desde el Kitoru del docente: *"Exportar material para los alumnos"*.
- Se puede comprimir en un `.zip` si el corpus es chico (< 25 MB): la lectura
  se hace con `ZipInputStream` (ya disponible en `jvmCommonMain`, que el
  pipeline de DOCX usa) → no hay que agregar ninguna dependencia.
- El alumno lo **importa**: el importador lee el manifiesto, verifica los
  hashes de cada texto, y escribe en el `LocalCorpusStore` **con el mismo
  código que el sync** → a partir de ahí, el alumno usa el material igual que
  si lo hubiera descargado del servidor.
- El manifiesto del paquete lleva `publisher.app = "Kitoru"` y un
  `publisher` con el nombre del docente → el alumno ve de quién es el
  material.
- **Sin firma criptográfica en v1**: el hash del manifiesto detecta
  corrupción, no manipulación. Quien controla el archivo controla el
  contenido. Se anota como deuda (firmar con la clave del docente es
  razonable: el `ownerSecretHash` ya existe en el modelo de identidad).

---

## 13. Casos borde

| Caso | Qué pasa |
|---|---|
| El servidor no tiene el material (el docente nunca publicó) | el manifiesto devuelve 404 → el alumno ve "Esta cátedra todavía no publicó material." y el chat remoto funciona con el corpus vacío. |
| El alumno se inscribe **después** de que el docente publicó | el primer sync baja todo (no hay delta previo). Es el caso normal. |
| El docente publica mientras el alumno está descargando | el manifiesto local es el viejo → el alumno sigue con el viejo; el próximo sync ve la generación nueva y baja el delta. **La generación es monótona: nunca se pierde un cambio.** |
| El texto cambió **mientras** se descarga | el hash no coincide → se descarta el `.part` y se reintenta (2 veces). Si insiste → error claro: *"El material cambió mientras se descargaba. Reintentá."* |
| El servidor devuelve un texto con el hash correcto pero con el `textHash` de otro | imposible por construcción (el servidor verifica al publicar), pero el cliente **vuelve a verificar** al descargar: el hash manda. |
| El material es un `LINK` | no tiene texto ni original: se publica con `textBytes = 0` y `textHash` del texto de la URL (o vacío). El cliente lo muestra como enlace y no lo indexa (igual que hoy `CourseMaterialSource` filtra por `extractedText.isNotBlank()`). |
| El material tiene un `warning` (PDF escaneado) | se publica con el texto vacío y el warning → el alumno ve el aviso y sabe que **ese material no aporta al índice**. Es la misma información que ve el docente. |
| La cátedra cambia el `chunkSize` a 800 | el manifiesto cambia su bloque `chunking` → el cliente reindexa sin bajar un byte. |
| El alumno tiene el corpus pero la app se actualiza y cambia el `Chunker` | el `algorithm` del manifiesto no coincide → el cliente reindexa (y avisa si el manifiesto es más nuevo que el cliente). |
| El disco del teléfono está lleno a mitad de la descarga | se para, se borra el `.part`, el estado queda como estaba (el corpus viejo sigue sirviendo) y la UI dice *"No hay espacio suficiente: necesitás 8,4 MB más."* |
| El alumno tiene 5 cátedras y 3 GB de material descargado | el LRU de §8.4 libera la más vieja; **nunca** la que está en uso; siempre con aviso. |
| El servidor devuelve 304 pero el cliente no tiene el manifiesto (archivo borrado) | el cliente detecta que no tiene manifiesto local y **fuerza** la bajada completa (ignora el ETag). |
| El material cambia de título (el docente lo renombra) | el `id` no cambia y el `textHash` tampoco → el cliente actualiza el título **sin descargar nada** (el título está en el manifiesto, que ya se bajó). |
| El docente borra un material | el `toRemove` lo borra del dispositivo en el siguiente sync. |
| La inscripción del alumno se da de baja en el servidor | su token de corpus deja de servir; el próximo sync falla con 403 y la app borra el corpus local. |
| El alumno usa la app en dos dispositivos | cada uno tiene su copia y su estado. No hay sincronización entre dispositivos del alumno (no hace falta: el manifiesto es la fuente común). |
| El hash del manifiesto no cuadra con el `ETag` | el servidor lo calcula; el cliente **no** recalcula el `manifestHash` (es la firma del servidor). El cliente verifica los **hashes de los textos**, que sí recalcula. |
| La red se corta a mitad de la descarga de 8 materiales | se bajaron 5 → no se commitea el manifiesto → el estado local sigue en la generación vieja → se reintenta el sync y se siguen bajando los 5 que faltaban (el hash los marca como ya bajados sólo si están en el estado commiteado; como no, se re bajan: **costo de 5 archivos, no de 8**). |

---

## 14. Plan de tests

| Test | Qué cubre |
|---|---|
| `CorpusManifestSerializationTest` (commonTest) | el JSON del manifiesto round-trippea; el **golden file** `resources/manifest-v1.json` coincide byte a byte. |
| `CorpusManifestBuilderTest` | el manifiesto sale con los materiales visibles, con los hash correctos y con `manifestHash` estable (mismo material → mismo hash; cambiar un texto → cambia el hash). |
| `CorpusSyncPlannerTest` | los cinco casos del delta: material nuevo, texto editado, troceado cambiado, material borrado, sin cambios. Es una **función pura**: sin disco, sin red, sin reloj. |
| `LocalCorpusStoreTest` | el layout en disco, la escritura atómica, la lectura por hash, `collectGarbage` (borra los `.part` y los huérfanos), `clear`. |
| `LocalCorpusStoreRecoveryTest` | `state.json` corrupto → el estado se reconstruye listando `text/` (el hash está en el nombre del archivo). |
| `LocalCorpusIndexTest` | el índice local devuelve **los mismos top-k** que un `Bm25Index` construido directamente sobre el mismo texto con el mismo troceado → la prueba de paridad. |
| `LocalCorpusParityTest` | dado un manifiesto y un texto, los fragmentos del cliente coinciden con los del docente (mismo `Chunker`, mismos parámetros del manifiesto). |
| `CorpusSearchTest` | el filtro por material, el recorte del snippet, el orden por puntaje. |
| `CorpusPolicyTest` | `allowLocalIndex = false` → el sync devuelve `Forbidden` y no se escribe nada; `allowLocalModels = false` → el índice se construye pero el RAG no se lo pasa al modelo. |
| `CorpusClientTest` (desktopTest, con un servidor de test de Ktor) | 304 con `If-None-Match`; el texto se verifica por hash; el 409 en la publicación; el 412 con un hash que no cuadra. |
| `CorpusPublisherTest` | publica sólo los materiales visibles; no vuelve a subir un texto cuyo hash ya está; reintenta ante un 409. |
| `CorpusBundleImportTest` | importar un paquete deja el `LocalCorpusStore` en el mismo estado que un sync desde el servidor. |

---

## 15. Archivos

```
core/src/commonMain/kotlin/kitoru/domain/
└── CorpusManifest.kt              # CorpusManifest, CorpusEntry, CorpusChunking, CorpusPolicy, …
core/src/commonMain/kotlin/kitoru/domain/
└── Course.kt                      # + allowLocalIndex, + corpusNotice
core/src/commonMain/kotlin/kitoru/platform/
├── Hashing.kt                     # expect sha256Hex
└── FileSystem.kt                  # + move, + writeBytes
core/src/jvmCommonMain/kotlin/kitoru/platform/
└── Hashing.jvm.kt                 # jvmSha256Hex (MessageDigest)
core/src/desktopMain/kotlin/kitoru/platform/
└── Hashing.desktop.kt             # actual
core/src/androidMain/kotlin/kitoru/platform/
└── Hashing.android.kt             # actual
backend/src/commonMain/kotlin/rag/retrieval/
└── Models.kt                      # + docId en Doc y Chunk

corpus/build.gradle.kts
corpus/src/commonMain/kotlin/kitoru/corpus/
├── CorpusClient.kt
├── CorpusManifestBuilder.kt
├── CorpusPublisher.kt
├── CorpusSearch.kt
├── CorpusSource.kt
├── CorpusState.kt                 # CorpusSyncState, SyncOutcome, LocalCorpusState, LocalMaterial
├── CorpusSync.kt
├── CorpusSyncPlanner.kt
├── LocalCorpusIndex.kt
├── LocalCorpusSource.kt
└── LocalCorpusStore.kt
corpus/src/commonTest/kotlin/kitoru/corpus/
├── CorpusSyncPlannerTest.kt
├── CorpusManifestSerializationTest.kt
├── LocalCorpusStoreTest.kt
└── LocalCorpusIndexTest.kt
corpus/src/commonTest/resources/manifest-v1.json    # golden file
```

---

## 16 · Reconciliación con `09-corpus-gestion.md`

Los dos documentos definen un `CorpusManifest` en
`core/.../kitoru/domain/CorpusManifest.kt`. **No pueden ser dos tipos**: el
manifiesto es el contrato entre el publicador, el servidor y el cliente, y
tiene que haber **uno solo**. Esta sección propone el tipo fusionado, para que
la sesión padre pueda decidir sin volver a pensar el contrato.

### 16.1 Las dos propuestas, cara a cara

| Concepto | `07-corpus-local` (este doc) | `09-corpus-gestion` | Veredicto |
|---|---|---|---|
| Versión monótona del corpus | `generation` | `version` | **`version`** (el nombre más estándar) |
| Momento de la publicación | `publishedAt` | `generatedAt` | **`publishedAt`** (es más preciso: no es "generado", es "publicado") |
| Modo de publicación | — | `mode: CorpusPublishMode` | **se conserva el de 09** (INMEDIATO / AL_PUBLICAR_CATEDRA / DIARIO) |
| Lista de documentos | `materials: List<CorpusEntry>` | `documents: List<ManifestEntry>` | **`documents` + `ManifestEntry`** (más dominio: versiones y roles) |
| Hash del texto | `textHash` | `contentHash` | **`contentHash`** |
| Tamaño | `textBytes` + `textChars` | `charCount` | **los dos**: `charCount` (09) + `byteCount` (07, para la cuota y el gzip) |
| Licencia y rol por material | — | `role: MaterialUsagePolicy`, `license: MaterialLicense` | **se conservan los de 09** |
| **Troceado** | `chunking: {algorithm, chunkSize, chunkOverlap}` | — | **se agrega de 07** (es el mecanismo de paridad) |
| **Política de transporte** | `policy: {allowLocalIndex, allowLocalModels, allowOriginalDownload, requireAcceptance, notice}` | `CourseCorpusPolicy` (parcial) | **se agrega de 07** (son las banderas que gobiernan el cliente) |
| Autor de la publicación | `publisher: CorpusPublisherInfo` | — | **se agrega de 07** (traza) |
| **ETag** | `manifestHash` | — | **se agrega de 07** (el polling barato) |
| Ruta del texto | — | `textPath` | **se conserva, pero el cliente HTTP la ignora** (arma la URL) |
| Original | `originalHash`, `originalBytes`, `originalName`, `contentType` | `filePath` | **se conservan los de 07** (el original es opcional y necesita su hash) |
| Aviso del docente | `warning` | — | **se agrega de 07** (el alumno tiene que saber que un material salió mal) |

### 16.2 El tipo fusionado

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CorpusManifest.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Manifiesto del corpus publicado de una cátedra.
 *
 * Es el contrato entre el publicador, el Kitoru-Server y el cliente del
 * alumno. La [version] es monótona: si el cliente la ve más grande que la
 * local, hay que sincronizar.
 *
 * El [chunking] es lo que hace que el RAG local del alumno recupere **los
 * mismos fragmentos** que el del docente: sin él, el troceado sería una
 * suposición.
 */
@Serializable
data class CorpusManifest(
    /** Versión del formato del manifiesto (no del contenido). */
    val formatVersion: Int = 1,
    val courseId: String,
    /** Versión del manifiesto: sube en cada publicación. Monótona. */
    val version: Long = 0L,
    /** Cuándo se publicó. */
    val publishedAt: Long = 0L,
    /** Modo con el que se publicó. */
    val mode: CorpusPublishMode = CorpusPublishMode.INMEDIATO,
    /** Quién publicó. */
    val publisher: CorpusPublisherInfo = CorpusPublisherInfo(),
    /** Cómo hay que trocear el texto para obtener los mismos fragmentos. */
    val chunking: CorpusChunking = CorpusChunking(),
    /** Lo que la cátedra permite hacer con el material en el dispositivo. */
    val policy: CorpusPolicy = CorpusPolicy(),
    /** Los documentos publicados. */
    val documents: List<ManifestEntry> = emptyList(),
    /** sha-256 del manifiesto sin este campo. Es el ETag del servidor. */
    val manifestHash: String = "",
) {
    /** Ids de los materiales publicados. */
    val materialIds: List<String> get() = documents.map { it.materialId }
}

/** Un documento dentro del manifiesto. */
@Serializable
data class ManifestEntry(
    val materialId: String,
    val versionId: String,
    val versionNumber: Int,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /** Qué se puede hacer con este material (editorial, por material). */
    val role: MaterialUsagePolicy = MaterialUsagePolicy.INDEXADO,
    /** Licencia declarada (legal, por material). */
    val license: MaterialLicense = MaterialLicense.SIN_LICENCIA,
    /** sha-256 del texto publicado (la clave de sincronización). */
    val contentHash: String = "",
    /** Caracteres del texto. */
    val charCount: Int = 0,
    /** Bytes UTF-8 del texto (para la cuota y el progreso de descarga). */
    val byteCount: Long = 0L,
    /**
     * Ruta del texto publicado, relativa a la carpeta publicada.
     * La usa el canal offline (el paquete); el canal HTTP la ignora y arma
     * la URL a partir del [materialId].
     */
    val textPath: String = "",
    /**
     * Ruta del archivo original (opcional: puede no estar en el cliente).
     * El canal HTTP la ignora igual que [textPath].
     */
    val filePath: String = "",
    /** sha-256 del original; vacío si el original no se distribuye. */
    val originalHash: String = "",
    /** Bytes del original. */
    val originalByteCount: Long = 0L,
    /** Nombre con el que el alumno lo verá ("Apuntes-IA.pdf"). */
    val originalName: String = "",
    /** MIME del original, para abrirlo con la app del sistema. */
    val contentType: String = "",
    /** Cuándo se publicó esta versión. */
    val publishedAt: Long = 0L,
    /** Aviso del docente ("3 de 40 diapositivas son imágenes"). */
    val warning: String = "",
)
```

### 16.3 Cómo se combinan las dos políticas

`09` modela la política **por material** (`MaterialUsagePolicy`: indexado /
sólo referencia / sin IA local / excluido) y `CourseCorpusPolicy`; este
documento modela la política **por cátedra** y **de transporte**
(`CorpusPolicy`). No se contradicen: **se componen**, y la regla efectiva es
la intersección:

| Eje | Pregunta | Regla |
|---|---|---|
| **Descarga** | ¿el texto puede llegar al dispositivo? | `manifest.policy.allowLocalIndex` (por cátedra) **y** el documento no está excluido |
| **Indexado y búsqueda** | ¿el texto se indexa y se puede buscar? | `allowLocalIndex` **y** `role ∈ {INDEXADO, SOLO_REFERENCIA}` (el excluido no) |
| **Uso con un modelo** | ¿el fragmento puede entrar en el prompt? | `manifest.policy.allowLocalModels` **y** `role == INDEXADO` (ni `SOLO_REFERENCIA` ni `SIN_IA_LOCAL`) |
| **Original** | ¿se puede bajar el archivo? | `manifest.policy.allowOriginalDownload` **y** `originalHash != ""` |

La última fila del segundo eje es el modo "sólo texto" de §9: con
`allowLocalModels = false` (por cátedra) o `role = SIN_IA_LOCAL` (por
material), el texto **se puede buscar** pero **no se puede mandar a un
modelo**. Es exactamente la política que el docente quiere cuando dice "el
material no puede ir a la IA".

> La diferencia de granularidad es real y está bien: `SIN_IA_LOCAL` por
> material (un parcial con copyright) convive con la regla de la cátedra
> (toda la materia).

### 16.4 Lo que hay que decidir al fusionar

| Decisión | Recomendación |
|---|---|
| ¿`version` o `generation`? | `version` (nombre estándar, y el 09 ya lo usa con el mismo significado) |
| ¿`textPath` o URL derivada? | **las dos**: `textPath` para el paquete offline, URL derivada para HTTP. El cliente ignora la que no aplica. |
| ¿el `mode` de publicación viaja al cliente? | no hace falta, pero **no molesta**: se puede dejar en el manifiesto para que el cliente muestre "publicado en lote" si quiere. Es opcional. |
| ¿dónde vive `CorpusPolicy`? | en `Course` (como `corpusPolicy`) y el manifiesto lleva la **copia efectiva** (el manifiesto manda: es lo último que publicó el docente). |
| ¿`formatVersion`? | sólo en el manifiesto: es el versionado del **formato**, no del contenido. `version` es la del contenido. No confundirlos. |

---

## Anexo A · Ejemplos JSON completos

### A.1 El manifiesto (v1)

```json
{
  "formatVersion": 1,
  "courseId": "c-ia-2026",
  "course": { "code": "IA-2026-1", "name": "Inteligencia Artificial", "term": "2026-1" },
  "generation": 12,
  "publishedAt": 1761600000000,
  "publisher": {
    "app": "Kitoru",
    "version": "0.3.0",
    "publisherId": "u-4f1c",
    "publisherName": "Dra. Ramírez"
  },
  "chunking": { "algorithm": "kitoru-chunker/1", "chunkSize": 1200, "chunkOverlap": 150 },
  "policy": {
    "allowLocalIndex": true,
    "allowLocalModels": false,
    "allowOriginalDownload": true,
    "requireAcceptance": true,
    "notice": "Material de la cursada IA-2026-1. No lo redistribuyas fuera del equipo.",
    "license": "Uso académico interno — IA-2026-1"
  },
  "materials": [
    {
      "id": "mat_7f3a2b",
      "title": "Apuntes-IA.pdf",
      "kind": "DOCUMENT",
      "tags": ["teoría", "parcial-1"],
      "textHash": "sha256:9f2c1d40b8a7e35c6f1d2a4b9e8c07315d6f2a8b4c1e9d7f3a5b2c8e6d4f1a09b3",
      "textBytes": 48230,
      "textChars": 48117,
      "originalHash": "sha256:1a7b93c4e2d5f6081a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80912",
      "originalBytes": 1204331,
      "originalName": "Apuntes-IA.pdf",
      "contentType": "application/pdf",
      "updatedAt": 1761500000000,
      "warning": ""
    }
  ],
  "manifestHash": "sha256:5c1e90a4b7d2f8361e0c9a5b3d7f2184c6a9e0b5d3f1c8a7e6b4d2f0a9c8e7b6d"
}
```

### A.2 El estado local (`state.json`)

```json
{
  "courseId": "c-ia-2026",
  "generation": 12,
  "indexedGeneration": 12,
  "chunking": { "algorithm": "kitoru-chunker/1", "chunkSize": 1200, "chunkOverlap": 150 },
  "materials": {
    "mat_7f3a2b": {
      "id": "mat_7f3a2b",
      "textHash": "sha256:9f2c1d40b8a7e35c6f1d2a4b9e8c07315d6f2a8b4c1e9d7f3a5b2c8e6d4f1a09b3",
      "textBytes": 48230,
      "chunkCount": 41,
      "downloadedAt": 1761600123456
    }
  },
  "lastSyncAt": 1761600123456,
  "lastError": ""
}
```

---

## Anexo B · El cálculo del tiempo y del tamaño

### B.1 El costo de construir un `Bm25Index`

Por fragmento (1 200 caracteres, ~200 tokens, ~150 términos únicos), medido a
partir de la implementación real de `Bm25Index`:

| Paso | Qué hace | Escritorio (JVM) | Teléfono (ART, gama media) |
|---|---|---|---|
| `replace(CAMEL, "$1 $2")` | regex sobre 1 200 caracteres | 3–8 µs | 10–25 µs |
| `lowercase()` | 1 200 caracteres | 1–5 µs | 4–15 µs |
| `stripDiacritics` | `Normalizer.normalize(NFD)` + regex de marcas | 30–60 µs | 60–120 µs |
| `split(NON_ALNUM)` | regex split, ~200 subcadenas | 15–30 µs | 40–80 µs |
| `filter` + `STOP` | 200 comparaciones | 3–6 µs | 8–20 µs |
| `groupingBy{}.eachCount()` | ~150 `put` en un `LinkedHashMap` | 10–20 µs | 25–60 µs |
| `df` (merge) | ~150 merges | 8–15 µs | 20–50 µs |
| **total por fragmento** | | **~70–150 µs** | **~170–370 µs** |

| Corpus | Fragmentos | Escritorio | Teléfono (gama media) | Teléfono (gama baja) |
|---|---|---|---|---|
| 120 k caracteres | ~120 | 10–18 ms | 20–45 ms | 50–120 ms |
| 900 k caracteres | ~800 | 55–120 ms | 0,14–0,30 s | 0,3–0,8 s |
| 3,6 M caracteres | ~3 000 | 0,2–0,45 s | **0,5–1,1 s** | 1,2–2,5 s |
| 15 M caracteres | ~12 500 | 0,9–1,9 s | 2,1–4,6 s | 5–12 s |

> El teléfono de gama baja es un 3× más lento que la gama media. Con 12 500
> fragmentos la indexación deja de ser invisible: por eso el caso "corpus
> extremo" (§8.3) se corta por cuota, no por rendimiento.

**El número que importa: 3 000 fragmentos ≈ 1 s en un teléfono de gama
media.** Es un trabajo de background, no un bloqueo de UI.

### B.2 El costo en memoria

Por fragmento: un `HashMap`/`LinkedHashMap` con ~150 entradas → ~150 ×
(node 32 B + `Integer` boxed 16 B) ≈ 7,2 KB, más la tabla (~1 KB) → **~8 KB
por fragmento**.

| Fragmentos | Índice (tfs + df) | Textos de los fragmentos | Total |
|---|---|---|---|
| 800 | ~7 MB | ~2 MB | ~9 MB |
| 3 000 | ~25 MB | ~7 MB | **~33 MB** |
| 12 500 | ~104 MB | ~30 MB | ~134 MB ⚠ |

### B.3 El tamaño del artefacto indexado (la opción descartada)

Para 3 000 fragmentos, en binario compacto (id de término varint + tf varint,
diccionario de términos con su postings):

| Parte | Tamaño |
|---|---|
| postings (450 000 pares) | ~1,4 MB |
| diccionario (~50 000 términos) | ~1,1 MB |
| metadata de los fragmentos (encabezado, longitudes, offsets) | ~0,2 MB |
| **artefacto total** | **~2,7 MB** |
| texto (que hay que bajar igual) | 3,6 MB |
| **total si se precomputa** | **6,3 MB** (+75 %) |

Por 1,5 s de CPU ahorrada en el teléfono. En una red móvil, es un mal
intercambio; en WiFi, es irrelevante. La razón de peso para descartarlo sigue
siendo el **acoplamiento al algoritmo** (§5.5), no el tamaño.

---

## Anexo C · Lo que este diseño NO resuelve

| Hueco | Por qué | Qué se deja anotado |
|---|---|---|
| **Firma criptográfica del manifiesto** | en v1 el hash detecta corrupción, no manipulación | firmar el manifiesto con la clave del docente es razonable (el `ownerSecretHash` ya existe) |
| **Sincronización entre los dispositivos del mismo alumno** | no hace falta: el manifiesto es la fuente común y el texto se re-baja en segundos | — |
| **El corpus del docente en su propio dispositivo** | ya está: es el `CourseMaterialSource` sobre `Material.extractedText` | — |
| **Descargas "delta" dentro de un mismo material** (texto que cambió un 5 %) | hoy se re-baja el texto entero (48 KB): no compensa un diff | si algún día hace falta: `GET` con `Range` + un diff por bloques |
| **Índice persistido en disco** | reconstruarlo cuesta 1 s; un índice en disco suma un formato que hay que versionar | si algún día hace falta: el mismo esquema del artefacto de §5.5, con la huella del algoritmo |
| **Push (FCM / WebSocket)** | el polling alcanza y es multiplataforma | §6.2: el handler es una línea cuando aparezca |
| **OCR en el teléfono** | no hay motor y no hace falta: el texto lo extrae el docente | `OcrEngine` queda como la costura (§4.4 de `teacher/03-materiales.md`) |
| **El material "muy grande" (un libro entero)** | el recorte por `maxCharsPerMaterial` lo resuelve | el texto que se distribuye ya viene recortado |
