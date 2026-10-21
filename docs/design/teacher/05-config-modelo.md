# 05 — Pantalla de configuración del modelo (docente)

> Subagente: diseño de la pantalla donde el docente fija la **configuración base
> del modelo** de la cátedra.
> Módulos tocados: `:core` (dominio), `:llm-api` (gateway + cliente Kitoru-Server),
> `:ui` (Compose Multiplatform, todavía no construido).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *fail-fast con mensajes en español*, *el estudiante conserva agencia*,
> *el docente no ve lo que no debe*.

---

## 0. Objetivo y alcance

La pantalla es **una pestaña dentro del espacio "cátedra" del docente**
(`Docente → Cátedra → Configuración del modelo`). Es el lugar donde el titular
fija:

- contra qué servidor y con qué clave chatean los alumnos,
- qué modelo y qué parámetros de generación usa la cátedra,
- qué tan profundo va el RAG sobre el material de la cátedra,
- qué herramientas puede pedir el modelo,
- **qué puede tocar el alumno y qué no** (el corazón de la pantalla),
- si se admite IA local en el dispositivo del alumno.

**Fuera de alcance de esta pantalla** (van en otras): materiales y su indexación,
analítica, gestión de alumnos, chats. La de alumnos sólo aparece como *lista de
destinatarios* de las keys por alumno.

### 0.1 Principio de diseño: *un control por opción*

Cada parámetro de la configuración tiene **exactamente un** control en toda la
pantalla. Cuando un valor puede aparecer en dos lugares (por ejemplo, la
temperatura en la sección "Generación" y dentro de un preset), **el preset no
duplica el control**: deja el valor en `null` (= "usa el de la cátedra") y sólo
expone el campo si el docente lo quiere pisar. La UI muestra explícitamente
"usa el valor de la cátedra (0.20)" como placeholder del campo vacío.

### 0.2 Deriva de los datos ya existentes

No se reescribe el dominio: se **extiende** con campos nuevos que tienen
valor por defecto (así los JSON ya guardados siguen deserializando) y se
documentan las deprecaciones.

| Ya existe | Qué le agregamos |
|---|---|
| `ModelPolicy` (`:core`) | `thinking`, `power`, `fieldRanges`, `defaultPresetId`, `revision` |
| `LockedField` | dos valores nuevos (`TOOLS`, `LOCAL_MODE`) y una propiedad `noun` para la frase resumen |
| `Course` | `modelPresets`, `toolRules`, `apiKeyMode`, `localModelsNote` |
| `CourseRepository` | (sin cambios) |
| — | `ModelPreset`, `CourseToolRule`, `ApiKeyGrant`, `ApiKeyMode`, `ApiKeyRepository` |

Deprecaciones explícitas:

- `Course.topK` → queda **derivado** de `ModelPolicy.topK` (que es lo que se
  puede bloquear/rangear). `RagConfig.topK` se arma en runtime desde
  `ModelPolicy.topK`. La UI **no** muestra `Course.topK`.
- `ModelPolicy.toolsEnabled` sigue siendo el **interruptor maestro** de
  herramientas; `Course.toolRules` decide cuáles están activas.

---

## 1. Modelo de dominio (cambios propuestos)

### 1.1 `ModelPolicy` — extensiones

Archivo: `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt`

```kotlin
@Serializable
data class ModelPolicy(
    // ... los campos actuales, sin cambios ...
    val mode: ModelMode = ModelMode.REMOTE,
    val remoteBaseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",

    // ---- Generación (nuevos) ----
    /** Nivel de razonamiento pedido al modelo: "off" | "low" | "medium" | "high". */
    val thinking: String = "off",
    /** Potencia de respuesta: "low" | "medium" | "high". */
    val power: String = "medium",

    // ---- Lo que el alumno puede tocar (nuevos) ----
    /**
     * Rangos permitidos por campo. Semántica de los tres estados:
     *  - campo en [lockedFields]      → BLOQUEADO: manda la cátedra.
     *  - campo en [fieldRanges]       → RANGO: el alumno puede variar dentro.
     *  - campo en ninguno de los dos   → LIBRE: el alumno decide.
     *
     * Un campo nunca puede estar en los dos conjuntos a la vez; [normalize]
     * lo garantiza al guardar.
     */
    val fieldRanges: Map<LockedField, ClosedFloatingPointRange<Double>> = emptyMap(),

    /** Id del preset de modelo que es el default de la cátedra ("" = personalizado). */
    val defaultPresetId: String = "",

    /** Sube en cada guardado. Permite al cliente detectar cambios sin comparar objetos. */
    val revision: Long = 0L,
)
```

Métodos nuevos (puros, testeables, sin UI):

```kotlin
    /** Estado de un campo frente al alumno. */
    enum class FieldAccess { LIBRE, RANGO, BLOQUEADO }

    /** Estado de acceso de [field] según la configuración actual. */
    fun accessOf(field: LockedField): FieldAccess = when {
        field in lockedFields -> FieldAccess.BLOQUEADO
        fieldRanges.containsKey(field) -> FieldAccess.RANGO
        else -> FieldAccess.LIBRE
    }

    /** ¿El alumno puede cambiar [field]? (rango y libre cuentan como "puede"). */
    fun studentCanTouch(field: LockedField): Boolean =
        accessOf(field) != FieldAccess.BLOQUEADO

    /**
     * Deja los conjuntos sin contradicciones: un campo bloqueado no puede
     * tener rango, y todo rango se acota a los límites duros del parámetro.
     * Idempotente. La UI la llama antes de guardar.
     */
    fun normalize(): ModelPolicy

    /**
     * Aplica la configuración de la cátedra sobre lo que el alumno quiere usar.
     * (Extiende la implementación actual: además de copiar los campos bloqueados,
     *  recorta los valores del alumno al rango permitido.)
     */
    fun resolveFor(studentChoice: ModelPolicy): ModelPolicy
```

`resolveFor` actualizado (el diff es aditivo sobre el actual):

```kotlin
    fun resolveFor(studentChoice: ModelPolicy): ModelPolicy {
        if (!allowStudentOverride) return this
        var out = studentChoice
        if (LockedField.API_KEY in lockedFields) out = out.copy(apiKey = apiKey)
        if (LockedField.BASE_URL in lockedFields) out = out.copy(remoteBaseUrl = remoteBaseUrl)
        if (LockedField.MODEL_NAME in lockedFields) out = out.copy(modelName = modelName)
        if (LockedField.SYSTEM_PROMPT in lockedFields) out = out.copy(systemPrompt = systemPrompt)
        if (LockedField.TEMPERATURE in lockedFields) out = out.copy(temperature = temperature)
        if (LockedField.MAX_TOKENS in lockedFields) out = out.copy(maxTokens = maxTokens)
        if (LockedField.TOP_K in lockedFields) out = out.copy(topK = topK)
        // NUEVO: recorte por rango (el alumno puede mover, no salirse).
        fieldRanges[LockedField.TEMPERATURE]?.let { r ->
            out = out.copy(temperature = out.temperature.coerceIn(r.start, r.endInclusive))
        }
        fieldRanges[LockedField.MAX_TOKENS]?.let { r ->
            out = out.copy(maxTokens = out.maxTokens.coerceIn(r.start.toInt(), r.endInclusive.toInt()))
        }
        fieldRanges[LockedField.TOP_K]?.let { r ->
            out = out.copy(topK = out.topK.coerceIn(r.start.toInt(), r.endInclusive.toInt()))
        }
        return out
    }
```

Además, `validate()` se amplía con los mensajes en español de los nuevos campos
(thinking/power inválidos, rango incoherente, presets sin modelo).

### 1.2 `LockedField` — dos valores nuevos y el "noun"

```kotlin
enum class LockedField(val label: String, val noun: String) {
    API_KEY("Clave de API", "la clave"),
    BASE_URL("Dirección del servidor", "la dirección del servidor"),
    MODEL_NAME("Nombre del modelo", "el modelo"),
    SYSTEM_PROMPT("Instrucciones del sistema", "las instrucciones del sistema"),
    TEMPERATURE("Temperatura", "la temperatura"),
    MAX_TOKENS("Límite de tokens", "el límite de tokens"),
    TOP_K("Fragmentos recuperados", "los fragmentos recuperados"),
    TOOLS("Herramientas", "las herramientas"),          // NUEVO
    LOCAL_MODE("IA local", "el uso de IA local"),          // NUEVO
    ;
    /** Campos numéricos: admiten rango, no sólo bloqueo. */
    val isNumeric: Boolean get() = this == TEMPERATURE || this == MAX_TOKENS || this == TOP_K
}
```

`noun` es lo que se usa para armar la frase resumen ("…pero no **la clave**").

### 1.3 `ModelPreset` — los presets de la cátedra

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/ModelPreset.kt`

```kotlin
/**
 * Un "modo de usar el modelo" que la cátedra ofrece al alumno.
 *
 * Un preset es un paquete de parámetros con nombre: el alumno elige "Rápido" o
 * "Razonador" en vez de tocar sliders. Los parámetros en `null` heredan el
 * valor base de la cátedra (sección "Generación"), así no hay dos controles
 * para el mismo parámetro.
 */
@Serializable
data class ModelPreset(
    val id: String,
    /** "Rápido", "Razonador", "Económico"… */
    val label: String,
    /** Qué le sirve al alumno de este preset (una línea). */
    val description: String = "",
    /** Id del modelo en el servidor de la cátedra (el que devolvió /models). */
    val modelName: String = "",
    /** null = hereda el valor base de la cátedra. */
    val temperature: Double? = null,
    /** null = hereda el valor base de la cátedra. */
    val maxTokens: Int? = null,
    /** null = hereda. "off" | "low" | "medium" | "high". */
    val thinking: String? = null,
    /** null = hereda. "low" | "medium" | "high". */
    val power: String? = null,
    /** Orden en la lista del alumno. */
    val order: Int = 0,
) {
    /** Compone el preset sobre la base y devuelve la config efectiva del gateway. */
    fun applyTo(base: ModelPolicy): ModelPolicy = base.copy(
        modelName = modelName.ifBlank { base.modelName },
        temperature = temperature ?: base.temperature,
        maxTokens = maxTokens ?: base.maxTokens,
        thinking = thinking ?: base.thinking,
        power = power ?: base.power,
    )
}
```

Los tres presets de fábrica que propone la UI (plantillas, con los parámetros ya
puestos; el docente sólo elige el modelo):

| Preset | temperature | maxTokens | thinking | power | Para qué |
|---|---|---|---|---|---|
| **Rápido** | 0.10 | 1024 | off | medium | Consultas rápidas, definiciones. |
| **Razonador** | 0.30 | 4096 | high | high | Ejercicios, demostraciones, código. |
| **Económico** | 0.20 | 512 | off | low | Chateo largo, saldo de cuota. |

### 1.4 `CourseToolRule` — herramientas por cátedra

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/CourseToolRule.kt`

```kotlin
/**
 * Qué herramientas puede pedir el modelo en esta cátedra.
 *
 * Se apoya en `rag.agent.ToolCategory` (NONE | FILES | WEB | MCP): la categoría
 * define el permiso que el alumno debe dar en su chat; la regla define si la
 * cátedra la habilita.
 */
@Serializable
data class CourseToolRule(
    /** Nombre de la herramienta: "get_time", "create_file", "mcp:<server>:<tool>". */
    val tool: String,
    /** Etiqueta legible para la UI del docente. */
    val label: String,
    val category: ToolCategory,
    /** Si la cátedra la habilita. */
    val enabled: Boolean = true,
    /** Si el alumno debe autorizar cada llamada (derivado de la categoría). */
    val requiresStudentPermission: Boolean = category != ToolCategory.NONE,
    /** Si el modelo la puede pedir sin que el alumno abra un chat. */
    val description: String = "",
)
```

Regla de permiso (ya existe en `rag.agent.ToolPermissions`): `ToolCategory.NONE`
→ se ejecuta siempre (`get_time`); las demás → el alumno autoriza por chat.

### 1.5 `ApiKeyMode` y `ApiKeyGrant` — las keys

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/ApiKeyGrant.kt`

```kotlin
/** Cómo se reparte el acceso al servidor de la cátedra. */
@Serializable
enum class ApiKeyMode(val label: String, val description: String) {
    COURSE(
        "Una key para toda la cátedra",
        "Todos los alumnos usan la misma clave. Simple, pero si se filtra hay que rotarla entera.",
    ),
    PER_STUDENT(
        "Una key por alumno",
        "Cada alumno usa su propia clave. Se puede revocar una sin tocar el resto.",
    ),
}

/**
 * Una key emitida por el Kitoru-Server (o copiada de la institución).
 *
 * `key` puede venir vacía si el servidor ya no la expone (sólo guarda el hash):
 * en ese caso la UI muestra los últimos 4 caracteres y no deja copiar.
 */
@Serializable
data class ApiKeyGrant(
    val id: String,
    val courseId: String,
    /** Id del alumno dueño de la key; vacío = key de la cátedra. */
    val studentId: String = "",
    val studentName: String = "",
    /** Clave en claro (sólo si el servidor o el docente la tienen). */
    val key: String = "",
    /** Últimos 4 caracteres, para mostrarla sin exponerla. */
    val keyLast4: String = "",
    /** Id de la key en el Kitoru-Server (para revocarla o rotarla). */
    val serverKeyId: String = "",
    val issuedAt: Long = 0L,
    /** Epoch ms. 0 = sin vencimiento. */
    val expiresAt: Long = 0L,
    /** Máximo de alumnos que pueden usar la key. */
    val maxUses: Int = 0,
    val revoked: Boolean = false,
    /** true si el servidor ya no puede devolver la clave en claro. */
    val masked: Boolean = false,
)
```

Repositorio nuevo (mismo patrón que `FileCourseRepository`, ver
`core/.../domain/FileRepositories.kt`):

```kotlin
interface ApiKeyRepository {
    suspend fun listForCourse(courseId: String): List<ApiKeyGrant>
    suspend fun get(id: String): ApiKeyGrant?
    suspend fun save(grant: ApiKeyGrant)
    suspend fun delete(id: String)
}

class FileApiKeyRepository(fs: FileSystem) : FileBackedRepository<ApiKeyGrant>(
    fs, "api-keys.json", ApiKeyGrant.serializer(),
), ApiKeyRepository { ... }
```

> **Seguridad**: `api-keys.json` queda en `KitoruPaths.configDir` y contiene
> secretos en claro. Se documenta como archivo sensible (permisos restrictivos en
> escritorio) y queda como deuda conocida: la rotación y el cifrado en reposo
> son un hito posterior.

### 1.6 `Course` — extensiones

```kotlin
@Serializable
data class Course(
    // ... los campos actuales ...
    val modelPolicy: ModelPolicy = ModelPolicy(),
    /** Presets que la cátedra ofrece al alumno. */
    val modelPresets: List<ModelPreset> = emptyList(),
    /** Qué herramientas puede pedir el modelo en esta cátedra. */
    val toolRules: List<CourseToolRule> = defaultToolRules(),
    /** Cómo se reparte la key del servidor. */
    val apiKeyMode: ApiKeyMode = ApiKeyMode.COURSE,
    /** Nota del docente cuando la IA local está deshabilitada ("" = texto por defecto). */
    val localModelsNote: String = "",
    val allowLocalModels: Boolean = true,
)
```

`defaultToolRules()` es el catálogo base (se completa con las tools MCP que la
cátedra registre):

```kotlin
fun defaultToolRules(): List<CourseToolRule> = listOf(
    CourseToolRule(
        tool = "get_time",
        label = "Consultar la hora",
        category = ToolCategory.NONE,
        enabled = true,
        description = "El modelo puede decir la fecha y la hora. No pide permiso.",
    ),
    CourseToolRule(
        tool = "create_file",
        label = "Crear archivos",
        category = ToolCategory.FILES,
        enabled = false,
        description = "El modelo puede crear documentos y código en la carpeta del chat. " +
            "El alumno autoriza cada uso.",
    ),
)
```

---

## 2. Layout de la pantalla

### 2.1 Estructura

Columna única, ancho máximo **760 dp**, centrada; scroll vertical con barra
propia (como `ThemedVerticalScrollbar` de FunshiEngineAI); barra de acciones
fija abajo. No hay panel lateral: en pantallas angostas (Android) la columna
única es la que funciona.

```
┌────────────────────────────────────────────────────────────────────────────┐
│  ← Cátedra · IA-2026-1                                                    │
│  Configuración del modelo                                                 │
│  Define contra qué servidor chatean tus alumnos y qué pueden tocar.        │
│  Los cambios se aplican en el próximo mensaje de cada alumno: los chats    │
│  abiertos no se reinician.                                                 │
├────────────────────────────────────────────────────────────────────────────┤
│ ▾ 1. Servidor de la cátedra                              [Completo]        │
│ ▾ 2. Modelo                                                [3 presets]      │
│ ▾ 3. Generación                                            [0.20 · 1024]   │
│ ▾ 4. RAG                                                   [5 fragmentos]   │
│ ▾ 5. Permisos de herramientas                             [1 de 2 activa]   │
│ ▾ 6. Lo que el alumno puede tocar                          [3 bloqueados]   │
│ ▾ 7. IA local (en el dispositivo del alumno)               [Permitida]      │
├────────────────────────────────────────────────────────────────────────────┤
│  3 cambios sin guardar        [ Descartar ] [ Guardar y aplicar ]         │
└────────────────────────────────────────────────────────────────────────────┘
```

Cada sección es una `Card` con:

- título de sección (`titleMedium`),
- subtítulo de una línea en `bodySmall` / `onSurfaceVariant` que explica qué
  hace y qué ve el alumno,
- un **chip de estado** a la derecha ("Completo", "Falta la clave", "Sin configurar"),
- el cuerpo con los controles.

Las secciones son **acordeones** (abiertas por defecto las 1, 2 y 6; cerradas las
resto) para que la pantalla no sea un muro. El estado (abierto/cerrado) es sólo
UI, no se persiste.

### 2.2 Barra de acciones (sticky bottom bar)

| Elemento | Comportamiento |
|---|---|
| "N cambios sin guardar" | Contador de campos que difieren de la config guardada. Clic → despliega un diff (`Cambios sin aplicar`) con una línea por campo: `Temperatura: 0.20 → 0.50`. |
| "Descartar" | Vuelve el borrador a la config guardada. Deshabilitado si no hay cambios. |
| "Guardar y aplicar" | Valida (`ModelPolicy.validate()` + reglas de negocio), persiste y sube `revision`. Deshabilitado si no hay cambios o si hay errores. |

Botón principal: `Button` (no `FilledTonalButton`), texto **"Guardar y aplicar"**.
`Tooltip`: "Se aplica en el próximo mensaje de cada alumno. Los chats abiertos
no se reinician."

### 2.3 Orden de secciones y por qué

1. **Servidor de la cátedra** — sin esto no hay nada (ni modelo, ni prueba).
2. **Modelo** — qué modelo y qué presets.
3. **Generación** — parámetros base.
4. **RAG** — sólo lo que ve el modelo de la cátedra.
5. **Permisos de herramientas** — qué puede hacer el modelo.
6. **Lo que el alumno puede tocar** — el corazón de la pantalla.
7. **IA local** — la alternativa del alumno.

Las secciones 2 y 3 están separadas pero **encadenadas**: la 2 elige el modelo
(por preset o personalizado) y la 3 los parámetros base que los presets heredan.

---

## 3. Sección 1 — "Servidor de la cátedra"

### 3.1 Controles

| # | Control | Tipo | Dónde vive |
|---|---|---|---|
| 1.1 | "Cómo se accede al servidor" | `SingleChoiceSegmentedButtonRow` (2 opciones) | `Course.apiKeyMode` |
| 1.2 | "URL del Kitoru-Server" | `OutlinedTextField` (placeholder `https://ia.universidad.edu/kitoru/v1`) | `ModelPolicy.remoteBaseUrl` |
| 1.3 | "Clave de API" | `OutlinedTextField` con `PasswordVisualTransformation` + ojo "Mostrar" | `ModelPolicy.apiKey` |
| 1.4 | "Pedir una key al servidor" | `Button` (outlined) + diálogo | — |
| 1.5 | "Probar conexión" | `OutlinedButton` | — |
| 1.6 | "Modelos disponibles" | `FlowRow` de `AssistChip` (sólo después de probar bien) | — |
| 1.7 | "Claves por alumno" (si `PER_STUDENT`) | tabla de alumnos + Issuing | `ApiKeyGrant` |

**1.2 URL.** `KeyboardOptions(keyboardType = Uri)`, `singleLine = true`. Debajo, un
texto de ayuda: *"Terminá en `/v1`: es la API compatible con OpenAI que expone el
Kitoru-Server."* Validación en línea con `ModelPolicy.validate()`:
*"La dirección del servidor debe empezar con http:// o https://."*

**1.3 Clave.** Campo con botón ojo. Botón "Copiar" a la derecha (sólo cuando hay
clave). Si está vacía y el modo es `COURSE`: chip de error en el encabezado de la
sección ("Falta la clave").

> La clave **no** se muestra en claro por defecto, no se loguea y no se manda
> al servidor de la cátedra en ningún momento: el alumno la recibe por el canal
> que elija (key compartida o key propia).

### 3.2 Flujo de emisión de la key

Dos caminos, ambos desde la misma pantalla:

**Camino A — la institución le dio la key al docente.**
El docente pega la URL y la key en 1.2 y 1.3, y sigue. (Es el camino por
defecto: no requiere nada del servidor.)

**Camino B — el docente pide la key al Kitoru-Server.**
Botón **"Pedir una key al servidor"** → diálogo `EmitKeyDialog`:

```
┌ Emitir una clave de acceso ─────────────────────────────────┐
│ Se guardará en Kitoru-Server. La clave caduca al terminar la  │
│ cursada.                                                      │
│                                                               │
│ Cátedra (read-only)   IA-2026-1 · Inteligencia Artificial     │
│ Cursada                [2026-1            ]                   │
│ Alumnos que podrán usar la clave   [60      ]                │
│ Vence el               [2026-12-31  ]  (por defecto: fin de   │
│                                             la cursada)      │
│                                                               │
│          [ Cancelar ]  [ Emitir clave ]                       │
└───────────────────────────────────────────────────────────────┘
```

Al confirmar: `POST /keys` → la UI muestra el resultado en un diálogo de
**una sola vez** con la clave en claro:

```
┌ Clave emitida ──────────────────────────────────────────────┐
│ ktr_live_7f3d9c2a…                                          │
│ Vence el 31/12/2026 · hasta 60 alumnos                        │
│                                                               │
│ La clave se muestra una sola vez. Copiala ahora.             │
│ [ Copiar ]                        [ Cerrar ]                  │
└───────────────────────────────────────────────────────────────┘
```

Si el docente cierra sin copiar, la UI lo avisa: *"Copiá la clave antes de
cerrar: el servidor guarda sólo el hash y no la vuelve a mostrar."*
(corresponde a `ApiKeyGrant.masked = true`).

En modo `PER_STUDENT`, el mismo diálogo emite **una key por alumno**: la UI
muestra una barra de progreso ("Emitiendo 12 de 34…") y una tabla de resultados.

### 3.3 Contrato REST mínimo del Kitoru-Server

El Kitoru-Server expone **dos superficies**:

- **API de gestión** (para el docente, autenticada con un token de
  administración), prefijo `/api`.
- **API de inferencia** (OpenAI-compatible, para los alumnos), prefijo `/v1`.

El campo "URL del Kitoru-Server" de la pantalla es la de **inferencia**
(`https://…/v1`). La de **gestión** se deriva de ella:

```kotlin
/** /api de gestión a partir de la /v1 de inferencia. */
fun adminBaseUrl(inferenceBaseUrl: String): String =
    inferenceBaseUrl.trimEnd('/').removeSuffix("/v1") + "/api"
```

y se puede pisar con un campo opcional "URL de administración" (por si el
servidor está detrás de un proxy distinto).

#### Emisión

```http
POST /api/keys
Authorization: Bearer <adminToken>
Content-Type: application/json

{
  "courseId": "IA-2026-1",
  "term": "2026-1",
  "maxUses": 60,
  "label": "Cátedra IA 2026-1",
  "expiresAt": 1798761600000
}
```

`courseId`, `term` y `maxUses` son **obligatorios** (los tres que pide el
enunciado). `label` y `expiresAt` son opcionales: si no viene `expiresAt`, el
servidor usa la **política de vencimiento de la cursada** (fin del período
configurado en el servidor).

Respuesta `201 Created`:

```json
{
  "id": "key_01J9Z8Y2QK",
  "key": "ktr_live_7f3d9c2a4e5b6c8d",
  "courseId": "IA-2026-1",
  "term": "2026-1",
  "label": "Cátedra IA 2026-1",
  "maxUses": 60,
  "remainingUses": 60,
  "createdAt": 1767225600000,
  "expiresAt": 1798761600000
}
```

Campos que la UI usa: `key` (se muestra una vez), `expiresAt` (chip de
vencimiento), `maxUses` (chip "hasta N alumnos"), `id` (para revocar/rotar).

#### Errores (mismo estilo que `LlmException.fromHttp`)

| HTTP | Cuerpo | Significado en la UI |
|---|---|---|
| 400 | `{"error":"maxUses debe ser mayor que 0."}` | "La cantidad de alumnos debe ser mayor que 0." |
| 401 | `{"error":"Falta el token de administración."}` | "El servidor no recibió el token de administración. Revisá la configuración de la cátedra." |
| 403 | `{"error":"El usuario no es docente de esa cátedra."}` | "Tu usuario no está autorizado a emitir claves para esta cátedra." |
| 404 | `{"error":"La cátedra no existe en este servidor."}` | "El servidor no conoce la cátedra IA-2026-1. Pedí a la institución que la registre." |
| 409 | `{"error":"Ya existe una key vigente para esta cursada."}` | "Para la cursada 2026-1 ya hay una key vigente. Podés rotarla o reutilizarla." |
| 429 | `{"error":"Demasiadas emisiones."}` | "El servidor pidió esperar antes de emitir otra clave." |
| 503 | `{"error":"El modelo todavía se está descargando."}` | "El Kitoru-Server está preparando el modelo. Probá en unos minutos." |

#### Resto de la API (mínimo, pero necesario para operar)

```http
GET    /api/keys?courseId=IA-2026-1&term=2026-1   → 200 {"keys":[ ... ]}
DELETE /api/keys/{id}                              → 204
POST   /api/keys/{id}/rotate                       → 201 { ...mismo esquema... }
```

La rotación devuelve una key nueva y anula la anterior: es el camino de
"se filtró la key de la cátedra".

#### Semántica del uso y del vencimiento

- La key viaja como `Authorization: Bearer <key>` en `POST /v1/chat/completions`.
- El cliente del alumno manda además `X-Kitoru-Student: <enrollmentId>`.
- `maxUses` cuenta **alumnos distintos**: el servidor lleva la cuenta por
  `(key, X-Kitoru-Student)`. Si el header no viene (clientes viejos), cuenta
  peticiones, con un tope de seguridad.
- Pasado `expiresAt`, el servidor responde `401 {"error":"La clave venció."}`.
- El Kitoru-Server **debe poder auditar** qué alumno usó qué key y cuándo: es la
  base de la analítica de la cátedra.

### 3.4 Cliente en Kitoru (`:llm-api`)

Archivo nuevo: `llm-api/src/commonMain/kotlin/llm/kitoru/KitoruServerClient.kt`

```kotlin
package llm.kitoru

/**
 * Cliente de la API de gestión del Kitoru-Server (emisión y revocación de keys).
 *
 * Es el mismo stack HTTP que el gateway (Ktor en commonMain), pero habla con
 * `/api` y no con `/v1`.
 */
class KitoruServerClient(
    private val adminBaseUrl: String,
    private val adminToken: String,
    private val http: HttpClient = defaultKitoruHttpClient(),
) {
    /** Emite una key. `expiresAt` en epoch ms; null = política del servidor. */
    suspend fun issueKey(
        courseId: String,
        term: String,
        maxUses: Int,
        label: String = "",
        expiresAt: Long? = null,
    ): IssuedKey

    /** Lista las keys de una cátedra. */
    suspend fun listKeys(courseId: String, term: String? = null): List<IssuedKey>

    /** Revoca una key. */
    suspend fun revokeKey(serverKeyId: String)

    /** Rota una key: devuelve la nueva y anula la anterior. */
    suspend fun rotateKey(serverKeyId: String): IssuedKey

    fun close() = http.close()
}

@Serializable
data class IssuedKey(
    val id: String = "",
    val key: String = "",
    val courseId: String = "",
    val term: String = "",
    val label: String = "",
    val maxUses: Int = 0,
    val remainingUses: Int = 0,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
)
```

Todos los métodos traducen los errores HTTP a `LlmException` (reusando
`LlmException.fromHttp`, que ya clasifica 401/403/404/409/429/5xx), de modo que
la UI del docente sólo conoce `LlmException` — igual que el resto de la app.

---

## 4. El botón "Probar conexión"

### 4.1 Qué llama

`OpenAiCompatibleGateway.listModels()` ya implementado en
`llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt`:

```kotlin
client.get("${baseUrl.trimEnd('/')}/models") { if (apiKey.isNotBlank()) bearerAuth(apiKey) }
```

Es decir: `GET {baseUrl}/models` con `Authorization: Bearer <clave>` si hay clave.
Respuesta esperada (OpenAI-compatible): `{"data":[{"id":"..."}, ...]}`.

Se usa **exactamente igual que en FunshiEngineAI** (`ChatCatalog.testApi`), con un
timeout de **20 s** (`withTimeout(20_000)`) y cierre del gateway en `finally`.

### 4.2 Estados

```kotlin
/**
 * Estado de la prueba de conexión. Es común a la pantalla del docente y a la
 * del alumno (ambos la usan): vive en `:ui/commonMain/.../common/`.
 */
sealed interface ConnectionTestState {
    /** Todavía no se probó. */
    data object Idle : ConnectionTestState

    /** En curso. */
    data object Testing : ConnectionTestState

    /** El servidor respondió. */
    data class Ok(
        val models: List<String>,
        /** El modelo configurado no está en la lista (o la lista vino vacía). */
        val warning: String? = null,
    ) : ConnectionTestState

    /** Falló. `title` y `hint` ya están en español. */
    data class Failed(
        val title: String,
        val hint: String,
        val detail: String,
        val retryable: Boolean = true,
    ) : ConnectionTestState
}
```

### 4.3 UI

Botón `OutlinedButton` a la derecha de la URL, debajo la clave:

```
[ Dirección del Kitoru-Server          ]  [ Probar conexión ]
[ Clave de API        [••••••••] 👁 📋 ]

   ● Conectando con https://ia.universidad.edu/kitoru/v1…        (spinner)
```

**Estado OK** (panel verde, `AnimatedVisibility`):

```
┌ Conexión OK ─────────────────────────────────────────────────┐
│ El servidor respondió. Hay 3 modelos disponibles.              │
│                                                                  │
│  [Qwen3-8B]  [Qwen3-8B-Instruct]  [phi4-mini]                  │
│   ↑ al tocar uno se completa el campo "Modelo"                  │
│                                                                  │
│ El modelo configurado ("Qwen3-8B") está en la lista. ✔          │
└──────────────────────────────────────────────────────────────────┘
```

Si el modelo configurado **no** está en la lista, warning en ámbar:

```
│ ⚠ El modelo "gpt-4o" no figura en la lista del servidor.       │
│   Con el Kitoru-Server eso da HTTP 404 al chatear.              │
│   Probá con "Qwen3-8B".                     [ Usar "Qwen3-8B" ] │
```

Si la lista vino vacía: *"Conexión OK, pero el endpoint no devolvió ningún
modelo. Revisá que la URL termine en /v1."*

**Estado Failed** (panel rojo, `errorContainer`):

```
┌ No se pudo conectar ──────────────────────────────────────────┐
│ La clave fue rechazada.                                        │
│ Revisá la clave de la cátedra: puede estar vencida o mal        │
│ copiada.                                                        │
│ Detalle: la API rechazó la clave (HTTP 401).                   │
└──────────────────────────────────────────────────────────────────┘
```

El botón **"Reintentar"** aparece si `retryable`.

### 4.4 Errores tipados → español

Se agrega en `:llm-api` (para que la UI del docente y la del alumno traduzcan
igual):

Archivo nuevo: `llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt`

```kotlin
package llm.error

/** Un error listo para mostrar: título corto + qué revisar + detalle técnico. */
data class LlmErrorText(
    val title: String,
    val hint: String,
    val detail: String,
    val retryable: Boolean,
)

/**
 * Traducción de [LlmException] a mensajes en español con "qué revisar".
 *
 * `LlmException.fromHttp` ya clasifica por status HTTP; esta capa agrega la
 * acción concreta para el docente. No reemplaza al mensaje original: el
 * detalle técnico se muestra aparte.
 */
object LlmErrorMessages {
    fun describe(e: LlmException): LlmErrorText = when (e.code) {
        LlmErrorCode.CLAVE_INVALIDA -> LlmErrorText(
            title = "La clave fue rechazada.",
            hint = "Revisá la clave de la cátedra: puede estar vencida o mal copiada. " +
                "Si es key por alumno, probá emitir una nueva.",
            detail = e.message,
            retryable = false,
        )
        LlmErrorCode.SIN_CUOTA -> LlmErrorText(
            title = "El servidor alcanzó su límite de uso.",
            hint = "Pedí una key nueva al Kitoru-Server o avisale a la institución.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.TIMEOUT -> LlmErrorText(
            title = "El servidor tardó demasiado.",
            hint = "Revisá la URL y que el Kitoru-Server esté encendido.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.MODELO_NO_DISPONIBLE -> LlmErrorText(
            title = "No se pudo consultar el servidor.",
            hint = "Revisá la URL base (tiene que terminar en /v1) y que el servicio esté accesible.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.SERVIDOR_CAIDO -> LlmErrorText(
            title = "No se pudo conectar con el servidor.",
            hint = "El servidor no responde. Si es un Kitoru-Server local, fijate que la app esté abierta.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.CONTEXTO_AGOTADO -> LlmErrorText(
            title = "Se agotó el contexto del modelo.",
            hint = "Subí el tamaño de contexto o bajá la cantidad de fragmentos recuperados.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.NO_CONFIGURADO -> LlmErrorText(
            title = "Falta configuración.",
            hint = "Completá la URL del servidor y la clave antes de probar.",
            detail = e.message,
            retryable = false,
        )
        LlmErrorCode.CANCELADO -> LlmErrorText(
            title = "Se canceló la prueba.",
            hint = "",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.DESCARGA_FALLIDA -> LlmErrorText(
            title = "Falló la descarga.",
            hint = "Revisá la conexión a internet.",
            detail = e.message,
            retryable = true,
        )
        LlmErrorCode.DESCONOCIDO -> LlmErrorText(
            title = "Error inesperado.",
            hint = "Revisá la URL y la clave, y probá de nuevo.",
            detail = e.message,
            retryable = true,
        )
    }
}
```

### 4.5 Efecto sobre el resto de la pantalla

Después de un `Ok`, la **sección "Modelo"** (2) se habilita: el desplegable de
modelo se puebla con los ids devueltos y la lista de presets puede elegir
modelo sin escribir a mano. Si el modelo configurado no está en la lista, el
chip de estado de la sección 2 pasa a "Modelo no disponible" (ámbar).

---

## 5. Sección 2 — "Modelo"

### 5.1 Controles

| # | Control | Tipo | Dónde vive |
|---|---|---|---|
| 2.1 | "Modelo por defecto" | `ExposedDropdownMenuBox` con los ids de `/models` + opción "Escribir a mano" | `ModelPolicy.defaultPresetId` / `ModelPolicy.modelName` |
| 2.2 | "Nombre del modelo (personalizado)" | `OutlinedTextField` (sólo si 2.1 = "Personalizado") | `ModelPolicy.modelName` |
| 2.3 | "Presets que ofrece la cátedra" | lista de `PresetCard` | `Course.modelPresets` |
| 2.4 | "Agregar preset" | `Button` + diálogo con las 3 plantillas | `Course.modelPresets` |
| 2.5 | "Instrucciones del sistema de la cátedra" | `OutlinedTextField` multilínea (8 líneas) | `ModelPolicy.systemPrompt` |

**2.1 Modelo por defecto.** Dos modos, sin duplicar controles:

- *Preset* → el valor por defecto es el preset elegido (`defaultPresetId`), y los
  campos de modelo/temperatura/tokens **no se muestran** (los hereda del preset).
- *Personalizado* → se muestran los campos crudos (`modelName`, y los de la
  sección 3).

**2.3 Presets.** Cada `PresetCard`:

```
┌──────────────────────────────────────────────────────────────┐
│ Razonador                                     ★ por defecto  │
│ Para ejercicios, demostraciones y código.                   │
│                                                               │
│ Modelo          Qwen3-8B                                       │
│ Temperatura     0.30                                           │
│ Límite de tokens 4096                                          │
│ Razonamiento    alto                                           │
│ Potencia        alta                                           │
│                                                               │
│ [ Usar por defecto ]  [ Editar ]  [ ⋮ ]                       │
└──────────────────────────────────────────────────────────────┘
```

- Los campos con `null` muestran *"usa el valor de la cátedra (0.20)"* en gris.
- El menú `⋮` tiene: "Duplicar", "Quitar", "Mover arriba/abajo".
- Un preset no puede quedar sin modelo: "Quitar" está deshabilitado si es el
  único preset o si es el default.

**2.5 Instrucciones del sistema.** Es el `ModelPolicy.systemPrompt`. Debajo,
un botón "Pegar desde el material de la cátedra" no aplica (el prompt es texto
libre). Placeholder:

> "Sos el asistente de la cátedra {NOMBRE}. Respondé con base en el material
> subir. Si no lo sabés, decilo y citá la fuente."

Se muestra un contador de caracteres y un aviso si supera 2.000 caracteres
(la clave se la lleva el contexto).

### 5.2 Modelo de datos de un preset

Ya definido en §1.3 (`ModelPreset`). La UI usa el mapeo directo:

| Campo de UI | `ModelPreset` |
|---|---|
| Modelo | `modelName` |
| Temperatura | `temperature: Double?` |
| Límite de tokens | `maxTokens: Int?` |
| Razonamiento | `thinking: String?` |
| Potencia | `power: String?` |

`ModelPreset.applyTo(base)` produce el `ModelPolicy` efectivo → de ahí sale el
`GatewayConfig` (mapeo 1:1 con `llm.config.GatewayConfig`).

---

## 6. Sección 3 — "Generación"

Parámetros **base** de la cátedra. Los presets los heredan salvo que los pisen.

| # | Control | Tipo | Rango | Dónde vive |
|---|---|---|---|---|
| 3.1 | Temperatura | `Slider` 0.0–2.0 (paso 0.05) + valor numérico editable | 0.0–2.0 | `ModelPolicy.temperature` |
| 3.2 | Límite de tokens | `Slider` 256–8192 (paso 128) + valor | 256–8192 | `ModelPolicy.maxTokens` |
| 3.3 | Razonamiento | `SegmentedButton` off/low/medium/high | — | `ModelPolicy.thinking` |
| 3.4 | Potencia | `SegmentedButton` low/medium/high | — | `ModelPolicy.power` |

Ayuda bajo cada control:

- 3.1: *"Más bajo = más preciso y repetible. Más alto = más creativo. Para una
  cátedra, 0.1–0.4 suele ser lo mejor."*
- 3.2: *"Cuánto puede escribir el modelo por respuesta. 1024 ≈ una página."*
- 3.3: *"Sólo funciona en modelos que soportan razonamiento (Qwen3, o1, GPT-5).
  En los demás no tiene efecto."*
- 3.4: *"Ajusta el límite de tokens automáticamente: baja = 256, media = el valor
  que fijaste, alta = al menos 4096."*

**3.3 tiene una trampa**: `GatewayConfig.thinking` se manda como
`reasoning_effort` y muchos endpoints lo rechazan. Por eso el control va
deshabilitado con la explicación *"El modelo elegido no soporta razonamiento"* si
el servidor no lo declara, y el valor efectivo por defecto es `off`.

---

## 7. Sección 4 — "RAG"

| # | Control | Tipo | Rango | Dónde vive |
|---|---|---|---|---|
| 4.1 | "Usar el material de la cátedra" | `Switch` (siempre ON en el milestone 1, deshabilitado con explicación) | — | — |
| 4.2 | Fragmentos recuperados | `Slider` 1–20 (paso 1) + valor | 1–20 | `ModelPolicy.topK` |
| 4.3 | Tamaño del fragmento | `Slider` 600–2400 (paso 200) | 600–2400 | `Course.chunkSize` |
| 4.4 | "Rondas de historial" | `Slider` 2–12 | 2–12 | `RagConfig.historyTurns` (hoy fijo en 6) |

Textos:

- 4.1: *"El chat usa **el material que subió la cátedra**, no documentación
  general. El alumno no puede cambiarlo."* (switch deshabilitado, con el motivo).
- 4.2: *"Cuántos fragmentos del material se le pasan al modelo en cada
  pregunta. Más fragmentos = más contexto y más costo."*
- 4.3: *"Cada cuánto se corta el material al indexarlo. Cambiarlo exige
  reindexar los materiales."* — con un botón **"Reindexar material"** que
  dispara la reindexación (enlaza con la pantalla de materiales).
- 4.4: *"Cuántas rondas de conversación anterior se reenvían al modelo."*

> `ModelPolicy.topK` es la fuente de verdad (es bloqueable/rangeable).
> `Course.topK` queda como campo legacy y `RagConfig` se arma en runtime:
> `RagConfig(chunkSize = course.chunkSize, topK = policy.topK, historyTurns = …)`.

---

## 8. Sección 5 — "Permisos de herramientas"

### 8.1 Modelo mental (importante para el docente)

Dos niveles distintos, y la UI los separa con una línea divisoria:

1. **Qué habilita la cátedra** → esta sección. "El modelo *puede pedir* esta herramienta".
2. **Qué autoriza el alumno** → en su chat, por categoría, con
   `rag.agent.ToolPermissions`. "El alumno *da permiso* para esta categoría".

La UI lo dice explícitamente en el subtítulo:

> *"Lo que habilitás es qué herramientas puede pedir el modelo. El alumno sigue
> autorizando cada uso en su chat, salvo las que no necesitan permiso."*

### 8.2 Controles

| # | Control | Tipo | Dónde vive |
|---|---|---|---|
| 5.1 | "Permitir herramientas" | `Switch` maestro | `ModelPolicy.toolsEnabled` |
| 5.2 | Lista de herramientas | filas con `Switch` por herramienta | `Course.toolRules` |

**Lo que NO está en esta sección**: el campo "Instrucciones del sistema" vive
**una sola vez**, en la sección "Modelo" (control 2.5). No se repite acá
(regla de *un control por opción*).

**5.1** apagado → todo el cuerpo de 5.2 se deshabilita (grey) y aparece:

> *"Con las herramientas apagadas el modelo sólo puede responder con texto."*

**5.2** Filas agrupadas por `ToolCategory`:

```
┌ Archivos ─────────────────────────────────────────────────────┐
│ El modelo necesita permiso del alumno en cada chat.            │
│                                                                    │
│ [switch] Crear archivos                    create_file           │
│         El modelo crea documentos y código en la carpeta del    │
│         chat. El alumno autoriza cada uso.                      │
└────────────────────────────────────────────────────────────────────┘

┌ Sin permisos ─────────────────────────────────────────────────┐
│ Se pueden usar siempre: no piden autorización.                   │
│                                                                    │
│ [switch] Consultar la hora                 get_time             │
│         El modelo dice la fecha y la hora. No pide permiso.     │
└────────────────────────────────────────────────────────────────────┘

┌ Web ───────────────────────────────────────────────────────────┐
│ [switch] Buscar en la web                  web_search          │
│         Todavía no está implementado en Kitoru.                 │
│         (chip "Próximamente")                                   │
└────────────────────────────────────────────────────────────────────┘

┌ MCP ───────────────────────────────────────────────────────────┐
│ [switch] (servidor "Universidad")           mcp:univ:*          │
│         El docente puede registrar servidores MCP desde la      │
│         sección "Servidores MCP".                               │
└────────────────────────────────────────────────────────────────────┘
```

Cada fila muestra:

- el switch (interruptor por herramienta),
- el nombre técnico en `bodySmall` monoespaciado (`create_file`),
- la descripción,
- un **chip con la categoría** ("requiere permiso del alumno" / "sin permiso").

### 8.3 La categoría `WEB` y `MCP`

- `ToolCategory.WEB` existe en `rag.agent.ToolCategory` pero **no hay tool
  implementada**: la fila aparece con el chip "Próximamente" y el switch
  deshabilitado. No se miente al docente.
- `ToolCategory.MCP`: las tools MCP se descubren en caliente
  (`ToolRegistry.replaceOrigin`). La UI lista los servidores MCP registrados y
  sus tools; el docente habilita servidor por servidor. El alta de servidores
  MCP vive en otra pantalla ("Servidores MCP"), esta sección sólo muestra y
  habilita.

### 8.4 Mapeo al runtime

```kotlin
/**
 * Qué herramientas puede pedir el modelo en esta cátedra.
 *
 * Intersección de: el switch maestro, las reglas por herramienta y lo que el
 * ToolRegistry tenga registrado (una tool habilitada pero no registrada no
 * existe).
 */
fun Course.enabledToolNames(registry: ToolRegistry): Set<String> {
    if (!modelPolicy.toolsEnabled) return emptySet()
    val allowed = toolRules.filter { it.enabled }.map { it.tool }.toSet()
    return registry.all()
        .map { it.spec.name }
        .filter { name -> allowed.contains(name) || allowed.any { it == name || it.endsWith(":$name") } }
        .toSet()
}
```

Y en el `AgentLoop`, el catálogo que se pasa al gateway es
`registry.specs().filter { it.name in enabledToolNames }`.

---

## 9. Sección 6 — "Lo que el alumno puede tocar"

La sección clave. Responde: *¿quéfreedom tiene el alumno sobre la configuración
que fijó la cátedra?*

### 9.1 Modelo de reglas

Tres estados por campo (ver §1.1):

| Estado | Significado | Cómo lo ve el alumno |
|---|---|---|
| **LIBRE** | El alumno decide el valor. | El campo es editable, sin límites de la cátedra (salvo los límites duros del cliente). |
| **RANGO** | El alumno puede mover el valor dentro de un intervalo. | El campo es editable pero acotado; si manda un valor fuera, se recorta. |
| **BLOQUEADO** | Manda la cátedra. | El campo aparece deshabilitado, con un candado y la explicación. |

Global: `ModelPolicy.allowStudentOverride == false` → **todo** bloqueado, sin
importar las reglas por campo. El conmutador está arriba de la tabla.

### 9.2 UI

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [switch] El alumno puede ajustar la configuración del modelo                │
│           Si lo apagás, los alumnos usan exactamente lo que fijó la cátedra.│
├────────────────────────────────────────────────────────────────────────────┤
│ Campo                 │ El alumno   │ Rango permitido                     │
│ ──────────────────────┼─────────────┼───────────────────────────────────── │
│ Clave de API          │ [switch ○]  │ —                                   │
│ Dirección del servidor│ [switch ○]  │ —                                   │
│ Modelo                │ [switch ●]  │ —                                   │
│ Instrucciones del sist│ [switch ○]  │ —                                   │
│ Temperatura           │ [switch ●]  │ [slider 0.00 ────●──── 1.00]  0.0–1.0│
│ Límite de tokens      │ [switch ○]  │ —                                   │
│ Fragmentos recuperados│ [switch ●]  │ [slider 1 ──●────────── 8]   1–8    │
│ Herramientas          │ [switch ○]  │ —                                   │
│ Uso de IA local       │ [switch ●]  │ —                                   │
└────────────────────────────────────────────────────────────────────────────┘

   El alumno puede cambiar el modelo, la temperatura (0.0–1.0) y los fragmentos
   recuperados (1–8), pero no la clave, la dirección del servidor, el límite de
   tokens ni las herramientas.
```

**Detalle de la fila:**

- **Switch "El alumno"**: OFF = BLOQUEADO, ON = LIBRE.
- Cuando el switch está ON **y el campo es numérico**, aparece además el
  control de **rango**: dos sliders (`RangeSlider` si está disponible en
  Compose Multiplatform 1.7.3; si no, dos `Slider` con validación cruzada) con
  los extremos, más una etiqueta con el intervalo.
  - El rango por defecto al activarlo: `min = valor de la cátedra`, `max = tope
    duro` (temperatura 2.0, tokens 8192, topK 20). El docente lo ajusta con los
    sliders.
  - Si el valor de la cátedra cae **fuera** del rango, la UI avisa:
    *"La temperatura de la cátedra (0.90) está fuera del rango que definiste
    (0.00–0.80). El alumno siempre va a ver 0.80."* y el `normalize()` la recorta.
- Cuando el switch está OFF (bloqueado), la fila muestra un chip con candado
  "Lo fija la cátedra" y el valor de la cátedra en gris, para que el docente
  vea qué está deafirmando.
- Los campos **no numéricos** no tienen control de rango: para ellos la decisión
  es binaria (libre / bloqueado). Es una decisión de diseño explícita: un
  "rango" de claves o de modelos no tiene sentido.

### 9.3 El control es un switch, no un slider

Se elige **switch + rango opcional** y no "slider de rango permitido" solo,
por dos razones concretas:

1. El switch **explica** el bloqueo (lo ve el docente: "el alumno puede / no
   puede tocar esto"), que es la información que el docente necesita.
2. El slider de rango por sí solo no dice si el campo está bloqueado: un
   "temperatura 0.0–1.0" con el switch apagado se lee como "el alumno puede
   cambiar la temperatura entre 0 y 1", que es falso.

### 9.4 La frase resumen

Se genera sola a partir de las reglas (función pura, testeable):

```kotlin
/**
 * Resumen en una frase de lo que el alumno puede tocar.
 *
 * Ejemplos:
 *  - "El alumno puede cambiar el modelo pero no la clave."
 *  - "El alumno puede cambiar la temperatura (0.0–1.0) y los fragmentos
 *     recuperados (1–8), pero no la clave ni la dirección del servidor."
 *  - "El alumno no puede tocar nada: la cátedra fija toda la configuración."
 */
fun ModelPolicy.overrideSummary(): String
```

Algoritmo:

1. Si `!allowStudentOverride` → *"El alumno no puede tocar nada: la cátedra fija
   toda la configuración del modelo."*
2. `can` = campos LIBRE (por `noun`) + campos RANGO (por `noun` + intervalo).
   `cannot` = campos BLOQUEADOS (por `noun`).
3. Si `can` está vacío y `cannot` no → *"El alumno no puede tocar nada: la
   cátedra fija el modelo, la clave y todos los parámetros."*
4. Si `cannot` está vacío → *"El alumno puede tocar todos los ajustes del
   modelo."*
5. Si no → *"El alumno puede cambiar X, Y y Z, pero no A ni B."*
   - "X, Y y Z" con comas y "y" antes del último.
   - "A ni B" con "ni" antes del último.
   - Si hay un solo elemento en cada lado: *"El alumno puede cambiar el modelo
     pero no la clave."* (forma singular, sin comas).

La frase se muestra (a) debajo de la tabla, en un `Surface` con `primaryContainer`,
y (b) **es la misma frase que ve el alumno** en su panel de configuración (el
alumno necesita saber qué freedoms tiene, sin adivinar). Se calcula una sola vez
en el dominio y viaja al cliente.

**Casos borde**:

- Un campo bloqueado cuyo `noun` ya está implícito en otro (p. ej. bloquear
  `BASE_URL` y `API_KEY`): *"…pero no la dirección del servidor ni la clave."*
- Rango degenerado (min == max): se trata como BLOQUEADO, no como rango, y la
  frase nombra el campo en "no puede".
- El mismo `noun` no se repite.

---

## 10. Sección 7 — "IA local (en el dispositivo del alumno)"

### 10.1 Controles

| # | Control | Tipo | Dónde vive |
|---|---|---|---|
| 7.1 | "Permitir IA local" | `Switch` | `Course.allowLocalModels` |
| 7.2 | "Modelo por defecto (GGUF)" | `OutlinedTextField` repo + archivo | `ModelPolicy.hfRepo`, `hfFile` |
| 7.3 | "Tamaño de contexto" | `Slider` 2048–32768 (paso 2048) | `ModelPolicy.contextSize` |
| 7.4 | "Capas en GPU" | `Slider` 0–64 | `ModelPolicy.gpuLayers` |
| 7.5 | "Puerto local" | `OutlinedTextField` numérico | `ModelPolicy.localPort` |
| 7.6 | "Carpeta de modelos" | `OutlinedTextField` | `ModelPolicy.modelsDir` |
| 7.7 | "Mensaje para el alumno" | `OutlinedTextField` multilínea (2 líneas) | `Course.localModelsNote` |

- 7.2–7.6 sólo se muestran si 7.1 está ON.
- 7.2 usa los defaults de `ModelPolicy`: `unsloth/Phi-4-mini-instruct-GGUF` /
  `Phi-4-mini-instruct-Q4_K_M.gguf`.
- Texto de ayuda de 7.2: *"Es sólo el punto de partida: el alumno puede
  descargar otro modelo si el equipo le da."* (salvo que 7.1 + la regla de
  bloqueo digan otra cosa).

### 10.2 Cómo se le comunica al alumno

**Regla de oro**: el alumno **nunca** queda frente a una pantalla muerta ni a un
error sin explicación. Cuando la IA local está deshabilitada:

1. **El chat sigue funcionando** con el modelo de la cátedra. No hay ninguna
   pantalla de error.
2. En el **panel de configuración del modelo del alumno**, la pestaña/píldora
   "IA local" sigue **visible** pero **deshabilitada**, con el motivo al lado:

   ```
   ○ IA local — Tu cátedra no permite IA local
   ```

   (No se oculta la opción: ocultarla deja al alumno creyendo que no existe.)
3. En la **cabeza del chat** aparece un avisoDismissible (una sola vez por
   curso, con "No mostrar más"):

   ```
   ℹ Estás usando el modelo de tu cátedra (Qwen3-8B) en el servidor de la
      universidad. Tu cátedra no permite IA local en este momento.
      Si necesitás usarla, consultá con tu docente.
   ```

4. Si el alumno tenía IA local configurada y la cátedra la deshabilita, el
   cliente **no borra nada** de su configuración local: la conserva y la
   reactiva sola cuando la cátedra vuelve a permitirlo. Se muestra un aviso
   distinto:

   ```
   ℹ Tu cátedra desactivó la IA local. Guardamos tu configuración; cuando la
      vuelva a permitir, vas a poder usarla sin volver a configurarla.
   ```

5. **El texto por defecto** (si `Course.localModelsNote` está vacío):

   > "Tu cátedra no permite IA local: los chats usan el modelo del servidor de
   > la cátedra. Si creés que debería estar disponible, consultá con tu docente."

   El docente puede escribir el suyo (7.7) y ese texto se muestra tal cual.

### 10.3 Matriz de visibilidad (coherente con `CONTEXTO.md`)

| Modo | Config del alumno | ¿El docente ve el texto? |
|---|---|---|
| Servidor de la cátedra | key de la cátedra | Sí (según `Enrollment.visibility`) |
| Servidor de la cátedra | key propia del alumno | Sí (según `Enrollment.visibility`) |
| IA local | — | No, salvo que el alumno lo comparta |

El bloqueo de "IA local" (`LockedField.LOCAL_MODE`) es un caso particular de
`Course.allowLocalModels`: si el campo está BLOQUEADO, el alumno ni siquiera
puede cambiar a local aunque la cátedra lo permita.

---

## 11. Guardado y "aplicar"

### 11.1 Modelo del borrador

La pantalla **nunca** escribe directo en el repositorio: trabaja sobre un
borrador en el ViewModel.

```kotlin
data class ModelConfigState(
    val course: Course? = null,
    /** Borrador de la política del modelo (lo que ve el docente). */
    val draft: ModelPolicy = ModelPolicy(),
    /** Borrador de los presets. */
    val presets: List<ModelPreset> = emptyList(),
    /** Borrador de las reglas de herramientas. */
    val toolRules: List<CourseToolRule> = emptyList(),
    /** Modo de reparto de keys. */
    val apiKeyMode: ApiKeyMode = ApiKeyMode.COURSE,
    /** Estado de la prueba de conexión. */
    val connection: ConnectionTestState = ConnectionTestState.Idle,
    /** Ids de modelo devueltos por el último test OK. */
    val availableModels: List<String> = emptyList(),
    /** true si el borrador difiere de lo guardado. */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Errores de validación (español), por sección. */
    val errors: Map<SectionId, List<String>> = emptyMap(),
    /** Mensaje de una línea para el Snackbar. */
    val toast: String? = null,
)
```

`dirty` se calcula comparando el borrador con lo guardado (campo por campo,
ignorando el orden de los `Set`).

### 11.2 Guardar

```kotlin
fun ModelConfigViewModel.save() {
    val course = state.course ?: return
    val policy = state.draft.normalize()
    val problems = policy.validate() + validateBusinessRules(state)
    if (problems.isNotEmpty()) { state = state.copy(errors = groupBySection(problems)); return }
    scope.launch {
        state = state.copy(saving = true)
        val updated = course.copy(
            modelPolicy = policy.copy(revision = course.modelPolicy.revision + 1),
            modelPresets = state.presets.sortedBy { it.order },
            toolRules = state.toolRules,
            apiKeyMode = state.apiKeyMode,
        )
        courseRepository.save(updated)
        state = state.copy(course = updated, draft = updated.modelPolicy, dirty = false, saving = false)
        toast = "Configuración guardada. Se aplica en el próximo mensaje de cada alumno."
    }
}
```

Guardado **atómico**: un `CourseRepository.save` con el curso entero (un solo
JSON). No hay estado intermedio en disco.

### 11.3 "Aplicar": qué cambia y cuándo

**Regla**: cambiar la configuración base **no reinicia el chat de los alumnos**.
No se manda ninguna señal de "reiniciar" a los clientes, no se borra historial,
no se cierra el hilo.

El efecto es **en el próximo mensaje**:

1. El cliente del alumno, al **enviar** un mensaje (no antes), resuelve la
   política vigente:

   ```kotlin
   // En el cliente del alumno, al enviar un mensaje:
   val policy = courseRepository.currentPolicy(courseId)      // relee (o usa la revisión)
   val effective = policy.resolveFor(studentLocalChoices)      // aplica bloqueos y rangos
   val config = effective.toGatewayConfig()
   gateway.reconfigure(config)   // §11.4
   gateway.chatStream(messages, tools)
   ```

2. Un mensaje **en curso** cuando el docente guarda termina con la configuración
   **anterior** (no se corta): la petición ya salió. El `ChatMessage` guarda el
   `effectiveModel` con el que se generó, así que la analítica queda consistente.

3. El hilo **no** se reinicia: el historial sigue, el modelo puede cambiar a
   mitad de conversación (eso es correcto y útil) y `ChatThread.effectiveModel`
   se actualiza para el próximo mensaje.

4. Si el docente **bloquea** algo que el alumno tenía cambiado (por ejemplo, pasa
   la clave a bloqueada), el próximo mensaje del alumno usa la clave de la
   cátedra y la UI del alumno muestra el campo con candado.

5. La analítica del docente distingue: cada `ChatMessage` guarda el modelo
   efectivo; si el docente cambia de modelo a mitad de cursada, la analítica
   puede filtrar por modelo.

### 11.4 Cómo se hace sin reiniciar: `reconfigure` en el gateway

Hoy `OpenAiCompatibleGateway` toma `baseUrl`, `apiKey`, `model`, `temperature`,
`maxTokens`… en el constructor, así que cambiar la config implica **crear otro
gateway** (y otro `HttpClient`) → cortar el stream en curso.

Propuesta (cambio pequeño en `:llm-api`):

```kotlin
class OpenAiCompatibleGateway(...) : LlmGateway {
    /**
     * Configuración viva. El gateway la lee UNA vez al empezar cada petición
     * (snapshot local), así una reconfiguración a mitad de stream no parte el
     * stream en curso: el mensaje en curso termina con la config anterior y el
     * siguiente usa la nueva.
     */
    @Volatile
    private var live: GatewayConfig = GatewayConfig(...)

    /** Reconfigura sin cortar lo que está en curso. */
    fun reconfigure(cfg: GatewayConfig) { live = cfg }

    override fun chatStream(...): Flow<LlmChunk> = flow {
        val cfg = live                    // snapshot
        val body = buildJsonObject {
            put("model", cfg.model)
            put("temperature", cfg.temperature)
            put("max_tokens", effectiveMaxTokens(cfg))
            ...
        }
        ...
    }
}
```

Con esto, el cliente del alumno **mantiene un único gateway por chat** y sólo
llama a `reconfigure(...)` cuando la política resuelta cambió. No se reinicia
nada: no se cierra el `HttpClient`, no se cancela el `Job` del chat, no se
borra la vista.

### 11.5 El diff ("cambios sin aplicar")

El contador de la barra inferior muestra cuántos campos cambiaron. Al tocarlo:

```
Cambios sin aplicar
  Temperatura            0.20  →  0.50
  Límite de tokens       1024  →  2048
  Modelo                 —     →  Qwen3-8B
  Clave de API           —     →  ktr_live_••••
```

Las claves se muestran **siempre enmascaradas** en el diff, aunque el campo esté
en claro en la pantalla.

### 11.6 Guardado parcial por sección

Sólo la **sección de herramientas** y la de **modelos** tienen guardado
inmediato opcional (switch "Guardar esta sección sola"), porque son las que más
se tocan durante la cursada. Si está apagado (por defecto), todo se guarda
junto desde la barra inferior. **No** hay guardado automático por cambio de
campo: el docente aprieta "Guardar y aplicar" cuando quiere.

### 11.7 Permisos

Sólo el **TITULAR** puede guardar (`CourseRole.canConfigureModel == true`).
Los **AYUDANTES** ven la pantalla en **modo lectura**: todos los controles
deshabilitados, con un banner arriba:

> *"Sólo el profesor titular puede cambiar la configuración del modelo. Podés
> consultarla y ver el material."*

El botón "Guardar y aplicar" no aparece para ayudantes.

---

## 12. Composables y archivos

### 12.1 `:ui` (módulo nuevo, aún no construido)

Paquete: `kitoru.ui.teacher`. Todo en `commonMain` (Compose Multiplatform).

```
ui/src/commonMain/kotlin/kitoru/ui/
├── common/
│   ├── ConnectionTestState.kt        ← sellada compartida (docente + alumno)
│   ├── FieldRuleUi.kt                ← helpers de acceso/rango + frase resumen
│   └── SectionCard.kt                ← card con título, subtítulo y chip de estado
└── teacher/
    ├── ModelConfigScreen.kt          ← la pantalla (layout + secciones)
    ├── ModelConfigViewModel.kt       ← estado, borrador, acciones
    ├── ModelConfigUiState.kt         ← ModelConfigState + SectionId
    ├── sections/
    │   ├── ServerSection.kt          ← 1. Servidor de la cátedra
    │   ├── ModelSection.kt           ← 2. Modelo
    │   ├── PresetsSection.kt         ← 2.b Presets
    │   ├── GenerationSection.kt      ← 3. Generación
    │   ├── RagSection.kt             ← 4. RAG
    │   ├── ToolsSection.kt           ← 5. Permisos de herramientas
    │   ├── StudentOverridesSection.kt← 6. Lo que el alumno puede tocar
    │   └── LocalAiSection.kt         ← 7. IA local
    ├── components/
    │   ├── ConnectionTestPanel.kt     ← panel idle/probando/ok/error
    │   ├── OverrideFieldRow.kt        ← fila con switch + rango
    │   ├── RangeSliderRow.kt          ← doble slider de rango
    │   ├── PresetCard.kt              ← tarjeta de preset
    │   ├── ToolRow.kt                 ← fila de herramienta con switch
    │   ├── ApiKeyTable.kt             ← tabla de keys por alumno
    │   └── StatusChip.kt              ← chip de estado de sección
    └── dialogs/
        ├── EmitKeyDialog.kt           ← emisión de key
        ├── IssuedKeyDialog.kt         ← muestra la key una sola vez
        └── PresetEditorDialog.kt      ← alta/edición de preset
```

### 12.2 `:core`

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt` | extensiones: `thinking`, `power`, `fieldRanges`, `defaultPresetId`, `revision`, `accessOf`, `normalize`, `resolveFor` extendido, `validate` ampliado |
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicyOverride.kt` (nuevo) | `FieldAccess`, `overrideSummary()`, `clampTo()` |
| `core/src/commonMain/kotlin/kitoru/domain/ModelPreset.kt` (nuevo) | `ModelPreset` + `defaultModelPresets()` |
| `core/src/commonMain/kotlin/kitoru/domain/CourseToolRule.kt` (nuevo) | `CourseToolRule` + `defaultToolRules()` |
| `core/src/commonMain/kotlin/kitoru/domain/ApiKeyGrant.kt` (nuevo) | `ApiKeyMode`, `ApiKeyGrant` |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | `ApiKeyRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | `FileApiKeyRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/Course.kt` | `modelPresets`, `toolRules`, `apiKeyMode`, `localModelsNote` |

### 12.3 `:llm-api`

| Archivo | Contenido |
|---|---|
| `llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt` (nuevo) | `LlmErrorText` + `LlmErrorMessages.describe()` |
| `llm-api/src/commonMain/kotlin/llm/kitoru/KitoruServerClient.kt` (nuevo) | `KitoruServerClient`, `IssuedKey`, `adminBaseUrl()` |
| `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` | `OpenAiCompatibleGateway.reconfigure()` + `live: GatewayConfig` |
| `llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt` | `ModelPolicy.toGatewayConfig()` (extensión en `:core` o aquí) |

### 12.4 Firmas de los composables principales

```kotlin
@Composable
fun ModelConfigScreen(
    courseId: String,
    viewModel: ModelConfigViewModel,
    modifier: Modifier = Modifier,
)

@Composable
private fun ServerSection(
    state: ModelConfigState,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onKeyModeChange: (ApiKeyMode) -> Unit,
    onTestConnection: () -> Unit,
    onEmitKey: (courseId: String, term: String, maxUses: Int, expiresAt: Long?) -> Unit,
    onUseModel: (String) -> Unit,
)

@Composable
private fun ModelSection(
    state: ModelConfigState,
    onDefaultPresetChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onPresetsChange: (List<ModelPreset>) -> Unit,
)

@Composable
private fun GenerationSection(
    policy: ModelPolicy,
    onPolicyChange: (ModelPolicy) -> Unit,
)

@Composable
private fun RagSection(
    state: ModelConfigState,
    onTopKChange: (Int) -> Unit,
    onChunkSizeChange: (Int) -> Unit,
    onHistoryTurnsChange: (Int) -> Unit,
    onReindex: () -> Unit,
)

@Composable
private fun ToolsSection(
    policy: ModelPolicy,
    rules: List<CourseToolRule>,
    onToolsEnabledChange: (Boolean) -> Unit,
    onRuleToggle: (String, Boolean) -> Unit,
)

@Composable
private fun StudentOverridesSection(
    policy: ModelPolicy,
    onAllowOverrideChange: (Boolean) -> Unit,
    onFieldAccessChange: (LockedField, Boolean) -> Unit,
    onFieldRangeChange: (LockedField, ClosedFloatingPointRange<Double>) -> Unit,
)

@Composable
private fun LocalAiSection(
    course: Course,
    policy: ModelPolicy,
    onAllowLocalChange: (Boolean) -> Unit,
    onLocalDefaultsChange: (ModelPolicy) -> Unit,
    onNoteChange: (String) -> Unit,
)
```

### 12.5 ViewModel

```kotlin
class ModelConfigViewModel(
    private val courseId: String,
    private val courseRepository: CourseRepository,
    private val enrollmentRepository: EnrollmentRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val serverClientFactory: (String, String) -> KitoruServerClient,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<ModelConfigState>

    fun load()
    fun updatePolicy(policy: ModelPolicy)          /* dirty = true */
    fun setFieldAccess(field: LockedField, allowed: Boolean)
    fun setFieldRange(field: LockedField, range: ClosedFloatingPointRange<Double>)
    fun testConnection()
    fun emitKey(term: String, maxUses: Int, expiresAt: Long?)
    fun useModel(modelId: String)
    fun save()
    fun discard()
}
```

`testConnection()` reusa el patrón de FunshiEngineAI (`ChatCatalog.testApi`):

```kotlin
fun testConnection() {
    val url = state.value.draft.remoteBaseUrl.trim()
    if (url.isBlank()) { /* error: "Completá la URL del servidor." */ return }
    scope.launch {
        state = state.copy(connection = ConnectionTestState.Testing)
        try {
            val models = withContext(ioDispatcher) {
                val gw = OpenAiCompatibleGateway(
                    baseUrl = url,
                    apiKey = state.value.draft.apiKey.trim(),
                    model = state.value.draft.modelName,
                    temperature = state.value.draft.temperature,
                    maxTokens = state.value.draft.maxTokens,
                    chatTimeoutMs = 20_000,
                )
                try { withTimeout(20_000) { gw.listModels() } } finally { gw.close() }
            }
            val stripped = models.map { it.removePrefix("models/") }
            val configured = state.value.draft.modelName.trim()
            val warning = when {
                models.isEmpty() -> "Conexión OK, pero el endpoint no devolvió ningún modelo."
                configured.isNotBlank() && stripped.none { it.equals(configured, ignoreCase = true) } ->
                    "El modelo \"$configured\" no figura en la lista del servidor. " +
                        "Con el Kitoru-Server eso da HTTP 404 al chatear."
                else -> null
            }
            state = state.copy(
                connection = ConnectionTestState.Ok(models, warning),
                availableModels = models,
            )
        } catch (e: TimeoutCancellationException) {
            state = state.copy(connection = ConnectionTestState.Failed(
                title = "El servidor no respondió en 20 s.",
                hint = "Revisá la URL base y que el Kitoru-Server esté encendido.",
                detail = e.message ?: "timeout",
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val llm = LlmException.wrap(e)
            val t = LlmErrorMessages.describe(llm)
            state = state.copy(connection = ConnectionTestState.Failed(t.title, t.hint, t.detail, t.retryable))
        }
    }
}
```

---

## 13. Casos borde, accesibilidad y textos

### 13.1 Casos borde

| Caso | Comportamiento |
|---|---|
| URL sin `http://` | Error en línea: *"La dirección del servidor debe empezar con http:// o https://."* y el botón "Probar conexión" deshabilitado. |
| URL termina en `/` | Se normaliza al probar (`trimEnd('/')`) pero se deja como la escribió el docente. |
| Clave vacía y `apiKeyMode = COURSE` | El chip de estado de la sección 1 dice "Falta la clave"; el botón "Probar conexión" **sí** funciona (el servidor puede no pedir clave: es el caso de un Kitoru-Server en la LAN de la facultad). |
| Key vencida | La columna "Vence" muestra la fecha en rojo y el chip de la sección 1 dice "La key vence el 31/12/2026". |
| El servidor devuelve 200 pero `data` vacío | `Ok(models = [], warning = "…no devolvió ningún modelo…")`. |
| El servidor devuelve 200 con un JSON que no es OpenAI-compatible | `listModels()` devuelve `emptyList()` (el `jsonObject["data"]` no existe) → warning de arriba, no error. |
| Un preset sin modelo | No se puede guardar: *"El preset \"Razonador\" no tiene modelo."* |
| El modelo por defecto es un preset que se borró | Al cargar, `defaultPresetId` inválido → cae a "Personalizado" y el chip de la sección 2 avisa *"El preset por defecto ya no existe."* |
| Rango con min > max | `normalize()` los ordena; la UI avilla antes de dejar guardar. |
| El valor de la cátedra fuera del rango del alumno | Se recorta al rango y se avisa (ver §9.2). |
| El docente bloquea `MODEL_NAME` pero deja `TEMPERATURE` libre | El alumno puede cambiar la temperatura pero no el modelo: la frase lo dice. |
| `allowStudentOverride = false` + reglas por campo | Las reglas se muestran **deshabilitadas y atenuadas** con el texto *"No aplican: el alumno no puede tocar nada."* |
| El docente no tiene `adminToken` | El botón "Pedir una key al servidor" muestra el error 401 ya traducido: *"El servidor no recibió el token de administración…"*. |
| Guardar con la sección de herramientas incompleta | No bloquea: se puede dejar sin herramientas (switch maestro apagado). |

### 13.2 Accesibilidad

- Cada switch tiene `contentDescription` con el nombre del campo y el estado
  ("El alumno puede cambiar la temperatura: activado").
- Los sliders numéricos tienen `stateDescription` con el valor ("0.30").
- Los chips de estado no dependen sólo del color: llevan texto.
- La sección "Lo que el alumno puede tocar" se puede recorrer completa con
  teclado: el switch y los sliders son controles de foco independientes.
- Contraste: los textos de ayuda usan `onSurfaceVariant`, nunca gris claro
  sobre blanco.

### 13.3 Textos de la pantalla (texto final, para implementación)

| Elemento | Texto |
|---|---|
| Título de la pantalla | "Configuración del modelo" |
| Subtítulo | "Define contra qué servidor chatean tus alumnos y qué pueden tocar." |
| Aviso de aplicación | "Los cambios se aplican en el próximo mensaje de cada alumno. Los chats abiertos no se reinician." |
| Botón principal | "Guardar y aplicar" |
| Chip de estado OK | "Completo" |
| Chip de estado con falta | "Falta la clave" / "Falta el modelo" / "Sin configurar" |
| Botón de prueba | "Probar conexión" / "Probando…" / "Reintentar" |
| Frase resumen (ejemplo) | "El alumno puede cambiar el modelo pero no la clave." |

---

## 14. Resumen de decisiones

1. **Un control por opción**: los presets **heredan** los parámetros base
   (`null` = hereda) en vez de duplicarlos; el "modelo por defecto" es un preset
   o "personalizado", nunca los dos.
2. **Bloqueo en tres estados** (libre / rango / bloqueado) con **switch por
   campo** + sliders de rango **sólo para los campos numéricos**, y una frase
   resumen derivada del dominio (`overrideSummary()`) que se muestra en la
   pantalla del docente **y** en la del alumno.
3. **"Probar conexión"** reusa `OpenAiCompatibleGateway.listModels()` con
   timeout de 20 s y cuatro estados; los errores se traducen con
   `LlmErrorMessages.describe(LlmException)` (nuevo, en `:llm-api`).
4. **Emisión de keys**: dos caminos (pegar la de la institución o pedirla al
   Kitoru-Server) y un contrato REST mínimo
   `POST /api/keys {courseId, term, maxUses} → {key, expiresAt, …}`; la key se
   muestra **una sola vez** y queda enmascarada en el resto de la UI.
5. **La config base no reinicia nada**: `resolveFor()` se aplica por mensaje y
   `OpenAiCompatibleGateway.reconfigure()` cambia la config en caliente con
   snapshot por petición.
6. **La IA local deshabilitada nunca deja al alumno sin salida**: la opción
   sigue visible con el motivo, el chat sigue funcionando con el modelo de la
   cátedra, la configuración local del alumno se conserva y hay un mensaje
   editable por el docente.
7. **Herramientas en dos niveles**: la cátedra habilita la herramienta; el
   alumno autoriza la categoría en su chat (`rag.agent.ToolPermissions`).
8. **Permisos**: sólo el TITULAR guarda; los ayudantes ven la pantalla en modo
   lectura con un banner explicativo.
