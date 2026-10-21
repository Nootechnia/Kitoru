# 03 — Configuración del modelo (alumno)

> Subagente: diseño de la pantalla donde el **alumno** ajusta la configuración
> que la cátedra le dio.
> Módulos tocados: `:core` (dominio + plataforma), `:llm-api` (gateway, errores,
> catálogo local), `:ui` (Compose Multiplatform, todavía no construido).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *fail-fast con mensajes en español*, *el estudiante conserva
> agencia*, *el docente no ve lo que no debe*.
>
> Documentos relacionados:
> - `docs/design/CONTEXTO.md` — el contexto compartido.
> - `docs/design/teacher/05-config-modelo.md` — la pantalla gemela del docente
>   (define `ModelPolicy`, `ModelPreset`, `LockedField`, `ConnectionTestState`,
>   `LlmErrorMessages` y la frase `overrideSummary()`).
> - `docs/design/domain/08-permisos.md` — capacidades `MODEL_OVERRIDE`,
>   `MODEL_USE_LOCAL`, `MODEL_VIEW_POLICY` y la condición
>   `StudentOverrideAllowed`.

---

## 0. Resumen en cinco líneas

1. El alumno ve **dos valores por campo**: el que heredó de la cátedra y el
   suyo; el tri-estado `INHERITED / CUSTOM / LOCKED` se calcula en `:core` y
   la UI nunca lo inventa.
2. La configuración del alumno vive en un tipo nuevo, `StudentModelSettings`,
   **por cátedra y por usuario**, con campos anulables: `null` = "heredo el de
   la cátedra".
3. La clave de API **nunca** va en claro en un JSON: se guarda en un almacén
   cifrado en reposo (`SecretStore`, `expect/actual`: `EncryptedSharedPreferences`
   en Android, DPAPI en escritorio) y en los archivos queda sólo un alias.
4. La vista "qué cambiaste respecto de la cátedra" es un diff **de una sola
   fuente de verdad** (`StudentPolicy.diff`) que el alumno puede leer, revertir
   campo por campo y copiar como texto para su docente (sin la clave).
5. La IA local se promete sólo donde se puede cumplir: en Android la sección
   queda visible pero cerrada con el motivo (`LocalModelService.available`),
   porque el `LocalModelService` de Android es un stub.

---

## 1. El concepto central: base + override

### 1.1 Las dos fuentes

| Fuente | Dónde vive | Quién la escribe | Cuándo se lee |
|---|---|---|---|
| **La base de la cátedra** | `Course.modelPolicy` (llega al alumno con el bundle o por el Kitoru-Server) | el docente | siempre |
| **El override del alumno** | `StudentModelSettings` (nuevo, local) | el alumno | siempre |

La configuración que **manda** se calcula con la función que ya existe:

```kotlin
// kitoru.domain.ModelPolicy (ya está, no se toca la semántica)
fun resolveFor(studentChoice: ModelPolicy): ModelPolicy
```

`resolveFor` copia los campos bloqueados de la cátedra sobre la elección del
alumno. Lo que hay que agregar es **construir** `studentChoice` a partir del
override del alumno y **decidir qué se ve** en pantalla.

### 1.2 El tri-estado por campo

Es la decisión central de la pantalla. Un campo, para un alumno dado, está en
exactamente uno de estos tres estados:

| Estado | Qué significa | Cómo se ve |
|---|---|---|
| `INHERITED` | El alumno usa el valor de la cátedra. | Chip neutro "De la cátedra". El control muestra el valor heredado, **no editable**. Switch "Usar el valor de la cátedra" **ON**. |
| `CUSTOM` | El alumno puso su propio valor. | Chip de acento "Tu valor". El control es editable. Switch OFF + botón "Volver al valor de la cátedra". |
| `LOCKED` | Manda la cátedra, no hay nada que decidir. | Chip con **candado** "Lo fija la cátedra". Control **deshabilitado**. Sin switch. Botón "Pedir a mi docente". |

> El switch y el chip **no son dos mecanismos**: el chip es la lectura del
> tri-estado y el switch es su único control. `switch ON ⇔ INHERITED`,
> `switch OFF ⇔ CUSTOM`, `LOCKED` no muestra switch (muestra el candado).
> Cuando el campo está bloqueado, el switch se sustituye por el candado +
> el motivo: un switch deshabilitado sugeriría que el alumno puede tocarlo.

Definición (en `:core`, pura y testeable):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentModelSettings.kt
package kitoru.domain

/** Estado de un campo de la configuración frente al alumno. */
enum class OverrideState {
    /** El alumno usa el valor de la cátedra. */
    INHERITED,

    /** El alumno puso su propio valor. */
    CUSTOM,

    /** Manda la cátedra: el alumno no puede tocarlo. */
    LOCKED,
}
```

### 1.3 El tipo nuevo: `StudentModelSettings`

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentModelSettings.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Los ajustes que el alumno le hace a la configuración de la cátedra.
 *
 * Es **por cátedra y por usuario**: el mismo alumno en dos cátedras puede
 * usar modelos, temperaturas y claves distintas.
 *
 * Regla de codificación: un campo `null` significa "heredo el valor de la
 * cátedra"; con valor, "este es mi valor". No hay un `Set<campo>` aparte:
 * la ausencia del valor ya dice "no lo toqué".
 *
 * **No contiene secretos.** La clave de API vive en el almacén cifrado
 * (`kitoru.platform.SecretStore`) y acá sólo queda su alias.
 */
@Serializable
data class StudentModelSettings(
    val courseId: String,
    val userId: String,

    // ---- Modo (alojado ↔ local) ----
    /** null = el modo de la cátedra. */
    val mode: ModelMode? = null,

    // ---- Servidor ----
    /** null = la dirección de la cátedra. */
    val remoteBaseUrl: String? = null,
    /** Alias en el `SecretStore` de la clave **propia** del alumno ("" = usa la de la cátedra). */
    val apiKeyAlias: String = "",

    // ---- Modelo ----
    /** null = el modelo de la cátedra. */
    val modelName: String? = null,
    /** null = las instrucciones de la cátedra. */
    val systemPrompt: String? = null,

    // ---- Generación ----
    val temperature: Double? = null,
    val maxTokens: Int? = null,

    // ---- RAG ----
    val topK: Int? = null,

    // ---- Herramientas ----
    /** null = lo que habilitó la cátedra. */
    val toolsEnabled: Boolean? = null,

    // ---- IA local (selección del modelo GGUF) ----
    val localHfRepo: String? = null,
    val localHfFile: String? = null,
    val localPort: Int? = null,
    val contextSize: Int? = null,
    val gpuLayers: Int? = null,

    /** Última modificación (epoch ms). */
    val updatedAt: Long = 0L,
) {
    /** Id estable del registro (courseId + userId). */
    val id: String get() = "$courseId|$userId"

    /** ¿El alumno tocó algo? (si no, su configuración es la de la cátedra). */
    val hasOverrides: Boolean
        get() = mode != null || remoteBaseUrl != null || apiKeyAlias.isNotBlank() ||
            modelName != null || systemPrompt != null || temperature != null ||
            maxTokens != null || topK != null || toolsEnabled != null ||
            localHfRepo != null || localHfFile != null || localPort != null ||
            contextSize != null || gpuLayers != null
}
```

> **Por qué no un `ModelPolicy` completo guardado por el alumno**: un
> `ModelPolicy` completo no puede distinguir "lo dejé como estaba" de "lo puse
> en el mismo valor que la cátedra", y obligaría a duplicar los defaults.
> Con campos anulables, el estado heredado es **la ausencia**: no hay forma de
> desincronizarse del modelo base.

### 1.4 Resolución: qué se usa realmente

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentPolicy.kt
package kitoru.domain

/**
 * Todo lo que la pantalla del alumno necesita calcular a partir de
 * (cátedra, ajustes del alumno). Funciones puras: sin disco, sin red, sin reloj.
 */
object StudentPolicy {

    /**
     * Estado de cada campo frente al alumno.
     *
     * - Si la cátedra no permite override (`allowStudentOverride = false`),
     *   **todos** los campos quedan LOCKED.
     * - Si el campo está en `lockedFields`, queda LOCKED.
     * - Si no, es CUSTOM cuando el alumno puso un valor **distinto** del de la
     *   cátedra; si puso el mismo valor, se considera INHERITED (así el switch
     *   "usar el valor de la cátedra" no miente).
     */
    fun states(course: Course, settings: StudentModelSettings): Map<StudentField, OverrideState>

    /**
     * La configuración efectiva: la base de la cátedra con los overrides del
     * alumno, pasada por `resolveFor` (que aplica bloqueos y rangos).
     */
    fun effective(course: Course, settings: StudentModelSettings): ModelPolicy

    /** Los cambios del alumno respecto de la cátedra (para la vista de diff). */
    fun diff(course: Course, settings: StudentModelSettings): List<ConfigDiffEntry>

    /** Frase resumen: "Usás la configuración de la cátedra con 2 ajustes propios." */
    fun summary(course: Course, settings: StudentModelSettings): String
}
```

`effective` es donde se reencuentran el dominio que ya existe y el override:

```kotlin
fun effective(course: Course, settings: StudentModelSettings): ModelPolicy {
    val base = course.modelPolicy
    val desired = base.copy(
        mode = settings.mode ?: base.mode,
        remoteBaseUrl = settings.remoteBaseUrl ?: base.remoteBaseUrl,
        apiKey = /* la clave resuelta por el ViewModel (ver §8) */ base.apiKey,
        modelName = settings.modelName ?: base.modelName,
        systemPrompt = settings.systemPrompt ?: base.systemPrompt,
        temperature = settings.temperature ?: base.temperature,
        maxTokens = settings.maxTokens ?: base.maxTokens,
        topK = settings.topK ?: base.topK,
        toolsEnabled = settings.toolsEnabled ?: base.toolsEnabled,
        hfRepo = settings.localHfRepo ?: base.hfRepo,
        hfFile = settings.localHfFile ?: base.hfFile,
        localPort = settings.localPort ?: base.localPort,
        contextSize = settings.contextSize ?: base.contextSize,
        gpuLayers = settings.gpuLayers ?: base.gpuLayers,
    )
    // resolveFor aplica los bloqueos y los rangos de la cátedra: es la
    // única fuente de verdad de "qué gana".
    return base.resolveFor(desired)
}
```

> La clave es el único campo que `effective` **no** puede resolver solo,
> porque no está en el JSON: la resuelve el ViewModel leyendo el `SecretStore`
> (§8). Por eso `effective` toma la clave ya resuelta por parámetro en la
> versión final; la firma de arriba se simplifica en el texto.

### 1.5 El catálogo de campos

La pantalla necesita una lista estable de campos con su etiqueta, su campo
bloqueable y su tipo de control. Eso es un enum (no una `when` gigante en la
UI), y es lo que permite generar el diff, el resumen y los textos.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentField.kt
package kitoru.domain

/** Los campos de la configuración que el alumno puede ver (y a veces tocar). */
enum class StudentField(
    /** Etiqueta para la UI. */
    val label: String,
    /** El campo bloqueable del dominio que lo gobierna. */
    val lockedField: LockedField,
    /** Qué control usa la pantalla. */
    val kind: FieldKind,
    /** Sección de la pantalla donde vive. */
    val section: ConfigSection,
) {
    SERVER_URL("Dirección del servidor", LockedField.BASE_URL, FieldKind.TEXT, ConfigSection.SERVER),
    API_KEY("Clave de API", LockedField.API_KEY, FieldKind.SECRET, ConfigSection.SERVER),
    MODEL_NAME("Nombre del modelo", LockedField.MODEL_NAME, FieldKind.TEXT, ConfigSection.MODEL),
    SYSTEM_PROMPT("Instrucciones del sistema", LockedField.SYSTEM_PROMPT, FieldKind.TEXTAREA, ConfigSection.MODEL),
    TEMPERATURE("Temperatura", LockedField.TEMPERATURE, FieldKind.DECIMAL, ConfigSection.GENERATION),
    MAX_TOKENS("Límite de tokens", LockedField.MAX_TOKENS, FieldKind.INT, ConfigSection.GENERATION),
    TOP_K("Fragmentos recuperados", LockedField.TOP_K, FieldKind.INT, ConfigSection.RAG),
    TOOLS("Herramientas", LockedField.TOOLS, FieldKind.BOOLEAN, ConfigSection.TOOLS),
    LOCAL_MODE("IA local", LockedField.LOCAL_MODE, FieldKind.BOOLEAN, ConfigSection.LOCAL),
    ;

    companion object {
        /** Los campos que la pantalla de "qué cambiaste" sabe mostrar. */
        val ALL: List<StudentField> = entries.toList()
    }
}

/** Tipo de control de un campo. */
enum class FieldKind { TEXT, TEXTAREA, SECRET, DECIMAL, INT, BOOLEAN, MODEL }

/** Las secciones de la pantalla. */
enum class ConfigSection(val label: String) {
    SERVER("Servidor"),
    MODEL("Modelo"),
    GENERATION("Generación"),
    RAG("RAG"),
    TOOLS("Herramientas"),
    LOCAL("IA local"),
}
```

> `LockedField.TOOLS` y `LockedField.LOCAL_MODE` son **propuestos** en
> `teacher/05-config-modelo.md` §1.2. Esta pantalla los necesita: sin ellos no
> se puede bloquear "las herramientas" ni "el uso de IA local". Son la única
> extensión de `LockedField` que pide el lado alumno.

### 1.6 Casos del modelo

| Situación | Resultado |
|---|---|
| `allowStudentOverride = false` | Todos los campos `LOCKED`. La pantalla muestra sólo lectura, con un banner: *"Tu cátedra no permite cambiar la configuración del modelo."* |
| Campo en `lockedFields` | `LOCKED`: el control del alumno se ignora y gana el de la cátedra. |
| Campo con rango (propuesto en 05 §1.1) | No es `LOCKED`: es `CUSTOM` con el valor recortado al rango. Si el valor del alumno quedó fuera, la UI avisa: *"La cátedra limita la temperatura a 0.00–0.80. Se va a usar 0.80."* |
| El alumno puso el mismo valor que la cátedra | `INHERITED` (no `CUSTOM`): el switch queda ON y no ensucia el diff. |
| La cátedra cambió después de que el alumno ajustara | El override sigue vivo y se reaplica; si el campo pasó a `LOCKED`, el override se ignora y la UI lo muestra bloqueado (y avisa: *"Tu docente bloqueó la temperatura; se volvió a usar el valor de la cátedra."*). |

---

## 2. Layout de la pantalla

### 2.1 Estructura

Columna única, ancho máximo **760 dp**, centrada; scroll vertical con barra
propia (el patrón `ThemedVerticalScrollbar` de FunshiEngineAI). En Android
(ventana angosta) es exactamente la misma columna: la pantalla es vertical por
naturaleza.

```
┌────────────────────────────────────────────────────────────────────────────┐
│  ← IA-2026-1 · Inteligencia Artificial                                     │
│  Configuración del modelo                                                   │
│  Usás la configuración de tu cátedra con 2 ajustes propios.                │
│  La cátedra puede cambiar la configuración en cualquier momento: tus        │
│  ajustes se reaplican solos.                                                │
├────────────────────────────────────────────────────────────────────────────┤
│  ¿Dónde corre el modelo?                                                   │
│  ┌──────────────────────────┐  ┌──────────────────────────┐                │
│  │ ● Servidor de la cátedra │  │ ○ IA local en tu equipo   │                │
│  └──────────────────────────┘  └──────────────────────────┘                │
├────────────────────────────────────────────────────────────────────────────┤
│ ▾ 1. Servidor                                              [Completo]      │
│ ▾ 2. Modelo                                                [Qwen3-8B]      │
│ ▾ 3. Generación                                            [0.20 · 1024]  │
│ ▾ 4. RAG                                                   [5 fragmentos]  │
│ ▾ 5. Herramientas                                          [Activadas]    │
│ ▾ 6. IA local                                              [No disponible] │
├────────────────────────────────────────────────────────────────────────────┤
│  2 cambios respecto de la cátedra      [ Ver el diff ]  [ Descartar todo ] │
└────────────────────────────────────────────────────────────────────────────┘
```

Encabezado: título, subtítulo con el **resumen** (`StudentPolicy.summary`) y
un aviso de que los cambios de la cátedra se reaplican solos.

Barra inferior (sticky): contador de overrides + "Ver el diff" + "Descartar
todo" (vuelve a la configuración de la cátedra). **No hay botón "Guardar"**:
cada cambio se persiste al instante (el ajuste de un slider es una acción, no
un borrador). Eso evita el caso "¿guardé?" y hace que la pantalla sirva
también como un panel de "qué tengo puesto ahora".

### 2.2 Las secciones

| # | Sección | Contenido | Estado del chip |
|---|---|---|---|
| 1 | **Servidor** | Dirección del servidor, clave de API, "Probar conexión" | "Completo" / "Falta la clave" / "Sin configurar" |
| 2 | **Modelo** | Nombre del modelo, presets que ofrece la cátedra, instrucciones del sistema | "Qwen3-8B" / "El tuyo" |
| 3 | **Generación** | Temperatura, límite de tokens, razonamiento y potencia (si existen) | "0.20 · 1024" |
| 4 | **RAG** | Fragmentos recuperados; "Usar el material de la cátedra" (fijo) | "5 fragmentos" |
| 5 | **Herramientas** | Interruptor maestro + explicación de las dos capas de permiso | "Activadas" |
| 6 | **IA local** | Selección del modelo GGUF, catálogo, descarga | "No disponible" / "Qwen3-8B-Q4" |

Las secciones son **acordeones**: abiertas por defecto 1, 2 y 6; cerradas 3, 4
y 5 (las que casi no se tocan). El estado abierto/cerrado es sólo UI.

### 2.3 El control de campo: `OverrideFieldRow`

Un solo composable para los nueve campos. Es la pieza que hace que la pantalla
"muestre el valor heredado y el valor propio" y **explique el bloqueo**.

```
┌────────────────────────────────────────────────────────────────────────┐
│ Temperatura                              [ Tu valor ]                  │
│ De la cátedra: 0.20                                                      │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │            ────────●────────                                       │ │
│ │                      0.50                                          │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ [ ] Usar el valor de la cátedra        Volver al valor de la cátedra   │
└────────────────────────────────────────────────────────────────────────┘
```

Variante **bloqueada**:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Clave de API                      🔒 Lo fija la cátedra                │
│ De la cátedra: ktr_live_••••••••3f9c                                  │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ ktr_live_••••••••3f9c                                     👁   📋  │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ La clave la provee la institución y no se puede cambiar.               │
│ [ Pedir a mi docente ]                                                  │
└────────────────────────────────────────────────────────────────────────┘
```

Variante **heredada**:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Límite de tokens                        [ De la cátedra ]              │
│ De la cátedra: 1024                                                     │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ 1024                                                      (apagado) │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ [✓] Usar el valor de la cátedra                                       │
└────────────────────────────────────────────────────────────────────────┘
```

Firma:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/config/components/OverrideFieldRow.kt
package kitoru.ui.student.config.components

/**
 * Fila de un campo de configuración con su valor heredado y el del alumno.
 *
 * Un solo control por opción (regla del proyecto): el switch "Usar el valor de
 * la cátedra" ES el estado del campo, no un control extra.
 */
@Composable
fun OverrideFieldRow(
    field: StudentField,
    state: OverrideState,
    /** Valor heredado, ya formateado para mostrar. */
    inheritedText: String,
    /** Valor propio, o null si hereda. */
    ownText: String?,
    /** Motivo del bloqueo (null si no está bloqueado). */
    lockReason: String?,
    /** Texto del botón de pedir desbloqueo (null si no aplica). */
    requestLabel: String? = null,
    onUseInherited: () -> Unit,
    onRequestUnlock: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** El control concreto del campo (text field, slider, switch…). */
    control: @Composable (enabled: Boolean) -> Unit,
)
```

Reglas de implementación:

- El chip de estado (`OverrideStateChip`) **nunca** depende sólo del color:
  lleva texto ("De la cátedra" / "Tu valor" / "Lo fija la cátedra").
- El `control` recibe `enabled = state != LOCKED`.
- El switch "Usar el valor de la cátedra" **sólo** aparece si `state != LOCKED`
  y estáChecked = `state == INHERITED`.
- El botón "Volver al valor de la cátedra" aparece si `state == CUSTOM`.
- El botón "Pedir a mi docente" aparece si `state == LOCKED` **y** el campo es
  de los que el docente puede desbloquear.
- `stateDescription` de los controles: *"Temperatura: 0.50 (de la cátedra: 0.20)"*.

### 2.4 Los textos de ayuda

Cada campo tiene **un** texto de ayuda (el del dominio), no tres. El de la
cátedra es el que el alumno lee; el del alumno, si el control lo muestra, va
en gris arriba.

| Campo | Texto de ayuda |
|---|---|
| Dirección del servidor | *"Es el servidor que usa tu cátedra. Si tu docente lo cambia, la dirección se actualiza sola."* |
| Clave de API | *"La clave la provee la institución. Si la cátedra usa una clave compartida, no se muestra completa."* |
| Nombre del modelo | *"Podés elegir otro modelo del servidor de tu cátedra si lo permite."* |
| Instrucciones del sistema | *"Le dicen al modelo cómo debe responder. Si las cambiás, el material de la cátedra sigue siendo la fuente."* |
| Temperatura | *"Más bajo = más preciso. Más alto = más creativo. 0.2 es un buen valor para una cátedra."* |
| Límite de tokens | *"Cuánto puede escribir el modelo por respuesta. 1024 ≈ una página."* |
| Fragmentos recuperados | *"Cuántos fragmentos del material de la cátedra se le pasan al modelo. Más fragmentos = más contexto."* |
| Herramientas | *"Lo que la cátedra habilitó. El modelo pide permiso por chat antes de usar cada una."* |
| IA local | *"Corre en tu equipo y no se lo ve nadie: tu docente no recibe ni el texto ni las estadísticas."* |

---

## 3. Sección por sección

### 3.1 Servidor

| Control | Tipo | Estado |
|---|---|---|
| Dirección del servidor | `OutlinedTextField` (`Uri`, `singleLine`) | editable sólo si `BASE_URL` no está bloqueado |
| Clave de API | `SecretField` (enmascarada + ojo + copiar) | editable sólo si `API_KEY` no está bloqueado |
| Probar conexión | `OutlinedButton` | siempre (incluso bloqueada: prueba lo que se va a usar) |

Detalles:

- **La URL** muestra abajo el valor heredado: *"De la cátedra: https://ia.universidad.edu/kitoru/v1"*.
  Si el alumno la overridea, el botón "Volver al valor de la cátedra" aparece.
- **La clave** (§8) es un `SecretField`:
  - con la clave de la cátedra: **siempre enmascarada**, sin botón de
    "mostrar" y sin copiar; el texto es *"Clave de la cátedra (no se muestra
    completa: la provee la institución)"*.
  - con clave propia del alumno: enmascarada con botón de ojo y "Copiar".
- **Probar conexión** prueba **los valores efectivos** (los que se van a
  usar), no los del formulario: si el alumno tiene un override de URL, se
  prueba el override. Ver §7.

### 3.2 Modelo

| Control | Tipo |
|---|---|
| Modelo | `ExposedDropdownMenuBox` con los ids de `/models` (tras un "Probar conexión" exitoso) + "Escribir a mano" |
| Presets de la cátedra | `FlowRow` de `AssistChip` (los `ModelPreset` de `Course.modelPresets`) |
| Instrucciones del sistema | `OutlinedTextField` multilínea (6 líneas) + contador de caracteres |

- Los **presets de la cátedra** son una comodidad: al tocar uno se aplican sus
  valores (los que no fija el preset heredan el de la cátedra). Si el preset
  toca un campo bloqueado, ese campo **no** cambia y la UI lo avisa: *"Tu
  cátedra bloquea la temperatura: el preset «Razonador» se aplica con 0.20."*
- Las **instrucciones del sistema**: si `SYSTEM_PROMPT` está bloqueado, el
  textarea se ve en sólo lectura con el texto de la cátedra (es público: el
  alumno puede leerlo, no cambiarlo). Si no está bloqueado, el alumno puede
  escribir las suyas; la UI aclara que el RAG sigue usando el material de la
  cátedra.

### 3.3 Generación

| Control | Tipo | Rango |
|---|---|---|
| Temperatura | `Slider` + valor editable | 0.0–2.0 (o el rango de la cátedra, si lo hay) |
| Límite de tokens | `Slider` + valor editable | 256–8192 (o el rango de la cátedra) |
| Razonamiento / Potencia | `SegmentedButton` | off/low/medium/high · low/medium/high |

Los sliders se **deshabilitan** si el campo está bloqueado, con el candado y
el motivo. Si la cárea definió un **rango** (05 §1.1), el slider se acota al
rango y, si el valor quedó recortado, aparece la nota: *"La cátedra limita la
temperatura a 0.00–0.80. Se va a usar 0.80."*

### 3.4 RAG

| Control | Tipo | Nota |
|---|---|---|
| Usar el material de la cátedra | `Switch` **fijo en ON, deshabilitado** | *"El chat usa el material que subió tu cátedra, no documentación general."* |
| Fragmentos recuperados | `Slider` 1–20 | editable si `TOP_K` no está bloqueado |

La línea fija es importante: el alumno **no** puede salirse del material de la
cátedra (es la regla del producto), pero **sí** puede ajustar la profundidad de la
recuperación si el docente no la bloqueó.

### 3.5 Herramientas

Una sola fila, no una lista:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Permitir que el modelo use herramientas              [switch] 🔒         │
│ De la cátedra: activadas                                               │
│                                                                          │
│ Lo que la cátedra habilitó es lo que el modelo PUEDE pedir. Vos seguís   │
│ autorizando cada uso en el chat (salvo las que no piden permiso).       │
│ Las herramientas que puede pedir tu cátedra: Consultar la hora.        │
└──────────────────────────────────────────────────────────────────────────┘
```

La lista de herramientas habilitadas **no** es editable por el alumno (si lo
fuera, podría deshabilitar el control de herramientas y dejaría de ser
"lo que habilitó la cátedra"). Si `TOOLS` no está bloqueado, el alumno puede
**apagar** las herramientas para su cuenta (no activarlas): es el control
maestro del `AgentLoop`.

### 3.6 IA local

Es la sección con más lógica; se desarrolla en §6.

---

## 4. El diff: "qué cambiaste respecto de la cátedra"

### 4.1 Modelo del diff

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentPolicy.kt (parte 2)

/** Una diferencia entre la configuración de la cátedra y la del alumno. */
data class ConfigDiffEntry(
    val field: StudentField,
    /** Etiqueta del campo ("Temperatura"). */
    val label: String,
    /** Valor de la cátedra, formateado ("0.20"). */
    val inherited: String,
    /** Valor del alumno, formateado ("0.50"). */
    val own: String,
    /** Si el campo quedó bloqueado, el override se ignora: se marca acá. */
    val ignored: Boolean = false,
    /** Motivo por el que se ignora (bloqueo o recorte por rango). */
    val ignoredReason: String? = null,
)
```

Reglas del diff:

1. Sólo aparecen los campos donde el valor **efectivo** del alumno difiere del
   de la cátedra. Si el override está bloqueado, el campo aparece **igual**
   pero con `ignored = true` (el alumno tiene que saber que lo que puso no se
   está usando: si no, el diff miente).
2. La clave de API **nunca** aparece con su valor: la fila dice
   *"Clave: la de tu cátedra"* o *"Clave: la tuya (no se muestra)"*.
3. El diff es **estable y ordenado** por el orden de `StudentField.entries`
   (servidor → modelo → generación → RAG → herramientas → local), no por el
   orden en que el alumno tocó las cosas.

### 4.2 La vista

Un panel desplegable (o una pantalla secundaria con deeplink
`kitoru://config/diff`):

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Qué cambiaste respecto de la cátedra                    2 ajustes propios │
├────────────────────────────────────────────────────────────────────────────┤
│  Temperatura                                                    [revertir] │
│  De la cátedra: 0.20        Tu valor: 0.50                               │
│                                                                            │
│  Límite de tokens                                                  [revertir]│
│  De la cátedra: 1024        Tu valor: 2048                               │
├────────────────────────────────────────────────────────────────────────────┤
│  Usás la configuración de tu cátedra con 2 ajustes propios.               │
│  Tus ajustes no cambian lo que ve tu docente: sólo cambian tu chat.       │
│                                                                            │
│  [ Copiar el resumen ]        [ Compartir con mi docente ]                │
└────────────────────────────────────────────────────────────────────────────┘
```

- Cada fila tiene un botón **"revertir"** que devuelve ese campo al valor de
  la cátedra (pone el valor en `null` en `StudentModelSettings`).
- Si el diff está vacío: *"Usás exactamente la configuración de tu cátedra."*
- Si hay overrides **ignorados** (bloqueados), arriba del todo:

  ```
  ⚠ 1 de tus ajustes no se está usando
     Límite de tokens: tu cátedra lo fijó en 1024. [revertir]
  ```

### 4.3 El resumen para compartir

Texto plano, generado en `:core` (determinista, testeable):

```kotlin
object ConfigShareSummary {
    /**
     * Texto para pegar en un mensaje al docente.
     * NUNCA incluye la clave ni el prompt completo.
     */
    fun render(course: Course, settings: StudentModelSettings, studentName: String): String
}
```

Salida de ejemplo:

```
IA-2026-1 · Inteligencia Artificial — ajustes de Ana Pérez
- Temperatura: 0.20 → 0.50
- Límite de tokens: 1024 → 2048
- Clave de API: uso la de la cátedra (no se comparte)
Generado con Kitoru.
```

- "Copiar el resumen" → al portapapeles (con `expect`/`actual` para el
  portapapeles, que en Compose Multiplatform no es igual en Android y
  escritorio).
- "Compartir con mi docente" → abre el flujo de §5.3 (el pedido) con el
  resumen pegado.

> **El diff es información del alumno, no un reporte del docente.** El
> docente puede verlo si el alumno se lo manda; la app **no** lo sube sola.
> Justifica el MODEL_OVERRIDE: el ajuste es privado por defecto.

---

## 5. Los campos bloqueados

### 5.1 Cómo se ve un campo bloqueado

Tres señales **juntas** (una sola no alcanza):

1. El control está **deshabilitado** (atenuado, `enabled = false`).
2. Un **chip con candado** `🔒 Lo fija la cátedra` en la cabecera de la fila.
3. Una **línea de motivo** bajo el control: *"La clave la provee la institución
   y no se puede cambiar."*

Además, el valor heredado se muestra **completo** (no atenuado al 30 %): el
alumno tiene que poder leer qué se está usando.

El tooltip (`TooltipBox` de Material 3, que en Compose Multiplatform 1.7.3 hay
que portar desde `androidx.compose.material3`) **no** es el único portador del
motivo: el motivo está **siempre visible** en la línea de ayuda, porque en
Android el tooltip no se puede tocar y los motivos son importantes.

### 5.2 Los motivos

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/LockReasons.kt
package kitoru.domain

/**
 * Por qué un campo está bloqueado, en una frase que el alumno entienda.
 *
 * Los motivos son **del dominio** (no de la UI): los usa la pantalla del
 * alumno, el diálogo de "pedir a mi docente" y el resumen.
 */
object LockReasons {

    fun of(field: LockedField, course: Course): String = when (field) {
        LockedField.API_KEY ->
            "La clave la provee la institución y no se puede cambiar."
        LockedField.BASE_URL ->
            "El servidor lo define tu cátedra: no se puede cambiar."
        LockedField.MODEL_NAME ->
            "El modelo lo elige tu cátedra: no se puede cambiar."
        LockedField.SYSTEM_PROMPT ->
            "Las instrucciones del sistema las fija tu cátedra: no se pueden cambiar."
        LockedField.TEMPERATURE ->
            "La temperatura la fija tu cátedra: no se puede cambiar."
        LockedField.MAX_TOKENS ->
            "El límite de tokens lo fija tu cátedra: no se puede cambiar."
        LockedField.TOP_K ->
            "La cantidad de fragmentos la fija tu cátedra: no se puede cambiar."
        LockedField.TOOLS ->
            "Las herramientas las habilita tu cátedra: no se pueden cambiar."
        LockedField.LOCAL_MODE ->
            "Tu cátedra no permite usar IA local en el dispositivo."
    }

    /** Si el docente puede desbloquear este campo (algunos no: el modo local depende de otra cosa). */
    fun unlockable(field: LockedField, course: Course): Boolean = when (field) {
        LockedField.LOCAL_MODE -> course.allowLocalModels
        else -> true
    }
}
```

### 5.3 "Pedir a mi docente"

Un botón **"Pedir a mi docente"** al pie de cada fila bloqueada (o un único
botón al pie de la pantalla que lista todos los bloqueos). Abre un diálogo
con el mensaje **generado y editable**:

```
┌ Pedir a tu docente ──────────────────────────────────────────────────┐
│ Hola, soy Ana Pérez (alumno de IA-2026-1).                            │
│                                                                          │
│ ¿Pueden desbloquear el campo «Límite de tokens»? Lo necesito para     │
│ las resoluciones largas de la TP2.                                      │
│                                                                          │
│ (editable)                                                               │
│                                                                          │
│ [ Cancelar ]              [ Copiar ]   [ Guardar el pedido ]           │
└────────────────────────────────────────────────────────────────────────┘
```

Generador de mensajes (en `:core`, determinista):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ConfigRequestTemplates.kt
package kitoru.domain

/** Los mensajes que el alumno le pide a su docente, ya redactados. */
object ConfigRequestTemplates {

    fun unlockField(course: Course, field: StudentField, studentName: String): String =
        "Hola, soy $studentName (alumno de ${course.shortName}). " +
            "¿Pueden desbloquear el campo «${field.label}»? " +
            "Me sirve para ${reasonFor(field)}."

    fun localAi(course: Course, studentName: String): String =
        "Hola, soy $studentName (alumno de ${course.shortName}). " +
            "¿Es posible usar IA local en mi equipo para esta materia? " +
            "A veces no puedo conectarme al servidor de la facultad."

    private fun reasonFor(field: StudentField): String = when (field) {
        StudentField.API_KEY -> "usar mi propia clave"
        StudentField.BASE_URL -> "conectarme a otro servidor compatible"
        StudentField.MODEL_NAME -> "probar con otro modelo del servidor"
        StudentField.SYSTEM_PROMPT -> "escribir mis propias instrucciones"
        StudentField.TEMPERATURE -> "probar otras respuestas"
        StudentField.MAX_TOKENS -> "que las respuestas no se corten"
        StudentField.TOP_K -> "recuperar más material de la cátedra"
        StudentField.TOOLS -> "decidir si el modelo usa herramientas"
        StudentField.LOCAL_MODE -> "trabajar sin conexión"
    }
}
```

El pedido se guarda localmente como `ConfigRequest` (§9) para que el alumno
no lo pierda, y se **copia** al portapapeles. Cuando exista el Kitoru-Server,
el mismo registro se sube por `POST /api/requests` y el docente lo ve en su
pantalla; hoy la app lo dice sin mentir:

> *"El pedido quedó guardado en este dispositivo y el texto se copió al
> portapapeles. Cuando la app se conecte al servidor de la cátedra, se envía
> solo."*

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ConfigRequest.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Un pedido del alumno a su docente: "desbloqueame esto".
 *
 * Es local hasta que exista el Kitoru-Server: el registro se guarda en el
 * dispositivo (así el alumno no lo pierde) y el texto se copia al portapapeles.
 */
@Serializable
data class ConfigRequest(
    val id: String,
    val courseId: String,
    val userId: String,
    val userName: String,
    /** Campo por el que se pide ("" = pedido general, p. ej. "IA local"). */
    val field: String = "",
    /** Etiqueta legible del campo (copia: el enum puede cambiar). */
    val fieldLabel: String = "",
    /** El mensaje redactado (editable por el alumno antes de enviarlo). */
    val message: String,
    val createdAt: Long = 0L,
    /** Estado local del pedido. */
    val status: RequestStatus = RequestStatus.PENDIENTE,
    /** Cuándo se envió (0 = no se envió). */
    val sentAt: Long = 0L,
)

/** Estado de un pedido. */
@Serializable
enum class RequestStatus(val label: String) {
    PENDIENTE("Guardado en el dispositivo"),
    ENVIADO("Enviado a la cátedra"),
    RESUELTO("Resuelto"),
}
```

Si el campo bloqueado es `LOCAL_MODE` y la cátedra **tampoco** permite IA
local, el botón desaparece (no tiene sentido pedirlo: la restricción no es del
campo sino de la política de la cátedra).

---

## 6. Cambiar de modo: alojado ↔ local

### 6.1 El selector de modo

Arriba de las secciones, con el patrón `ModeSegment` de
`FunshiEngineAI/frontend/.../ApisScreen.kt` (el mismo control, portado):

```
┌──────────────────────────┐  ┌──────────────────────────┐
│ ● Servidor de la cátedra │  │ ○ IA local en tu equipo   │
└──────────────────────────┘  └──────────────────────────┘
```

- El segmento activo muestra el modelo que se va a usar:
  `"Servidor de la cátedra (Qwen3-8B)"` / `"IA local en tu equipo (Qwen3-8B-Q4)"`.
- Al cambiar de modo **no se reinicia el chat**: el cambio aplica en el
  próximo mensaje (mismo criterio que la pantalla del docente, 05 §11.3).

### 6.2 Si la cátedra no permite IA local

El segmento **queda visible** pero **deshabilitado**, con el motivo al lado
(regla del producto: ocultar la opción deja al alumno creyendo que no existe):

```
┌──────────────────────────┐  ┌────────────────────────────────┐
│ ● Servidor de la cátedra │  │ 🔒 IA local en tu equipo        │
└──────────────────────────┘  │   Tu cátedra no permite IA local │
                               └────────────────────────────────┘
```

Motivo: `Course.localModelsNote` si el docente escribió uno; si no, el texto
por defecto de 05 §10.2:

> *"Tu cátedra no permite IA local: los chats usan el modelo del servidor de
> la cátedra. Si creés que debería estar disponible, consultá con tu docente."*

Si además `LockedField.LOCAL_MODE` está bloqueado, el motivo es el del campo
(§5.2) y el botón "Pedir a mi docente" aparece.

**Nada se borra**: si el alumno tenía IA local configurada y la cátedra la
deshabilita, su configuración local se conserva y se reactiva sola cuando la
cátedra vuelve a permitirlo. La pantalla avisa con un texto distinto:

> *"Tu cátedra desactivó la IA local. Guardamos tu configuración; cuando la
> vuelva a permitir, vas a poder usarla sin volver a configurarla."*

### 6.3 El catálogo local (port de `ModelsScreen.kt`)

Si la cátedra permite IA local **y** la plataforma la soporta, la sección
"IA local" muestra el catálogo, con el patrón de
`FunshiEngineAI/frontend/src/main/kotlin/app/screens/ModelsScreen.kt`:

1. **Buscador de Hugging Face** arriba: campo de búsqueda + botón "Buscar";
   resultados en acordeón; al expandir un repo se listan sus `.gguf` con el
   peso real (`HfGgufFile.display` + `humanSize`).
2. **Modelo activo + progreso real**: `LinearProgressIndicator` con
   `percent`, "42 % · 1.2 GB de 2.8 GB" y botón "Cancelar descarga".
3. **Catálogo**: una `Card` por modelo con el estado
   (*Activo · Descargado · Descarga parcial · No descargado*) y los botones
   **"Descargar y usar"**, **"Reanudar"**, **"Usar"**, **"Quitar"**.
4. **Alta manual**: repo + archivo + etiqueta.

Lo que **cambia** respecto de FunshiEngineAI:

| En FunshiEngineAI | En Kitoru | Por qué |
|---|---|---|
| El catálogo es global de la app | El **catálogo es del dispositivo** (`LocalModelCatalog`); lo **por cátedra** es sólo qué modelo tenés elegido | El mismo GGUF sirve para todas tus cátedras: no tiene sentido descargarlo dos veces. |
| `modelsDir` relativo al directorio de la app | `KitoruPaths.modelsDir` = `<dataDir>/models` | Ruta absoluta y known. |
| `ModelManager` en `llm-api` con `java.nio.file` | Versión portable en `:llm-api` (`llm.catalog`) sobre `kitoru.platform.FileSystem` | `commonMain` no puede usar `java.nio.file` (regla 2 del proyecto). |
| "Descargar" siempre habilitado | "Descargar" sólo si la plataforma puede correr modelos (§6.4) | No se promete lo que no se cumple. |

Al elegir un modelo del catálogo, el alumno **no** cambia la configuración de
la cátedra: se guarda en **sus** ajustes (`localHfRepo` / `localHfFile`) y el
diff muestra *"Modelo local: (el de la cátedra) → Qwen3-8B-Q4"*.

### 6.4 La descarga en Android: qué se promete y qué no

**El problema.** En Android no hay motor local: `LocalModelService.android.kt`
es un stub que lanza

```kotlin
LlmException(LlmErrorCode.NO_CONFIGURADO, "El modelo local todavía no está disponible en Android. …")
```

Además, en Kitoru **la descarga del GGUF todavía no existe**: el
`HuggingFaceDownloader` de FunshiEngineAI no está portado (usa
`java.nio.file`), y el `LocalModelService` de escritorio **no** descarga: espera
que el binario y el `.gguf` ya estén en la máquina.

**La regla de oro**: la pantalla no puede ofrecer un botón que va a fallar.
Un botón "Descargar" que después dice "no disponible" es peor que no tenerlo.

**La solución: un flag de capacidad, no un `if (isAndroid)` en la UI.**

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt (extensión)
interface LocalModelService {
    /** ¿Esta plataforma puede correr un modelo local? */
    val available: Boolean

    /** Por qué no, si no puede (vacío = sí puede). Texto en español, para la UI. */
    val unavailableReason: String

    // … lo que ya está
}
```

- **Desktop** (`LlamaServerLocalModelService`): `available = true`,
  `unavailableReason = ""`. Si falta el binario, el error de `start()` ya lo
  explica: *"No se encontró llama-server. Indicá la ruta…"*.
- **Android** (`UnavailableLocalModelService`): `available = false`,
  `unavailableReason = "Todavía no podemos correr un modelo local en Android. Usá el servidor de la cátedra o la app de escritorio."`.

Con ese flag, la UI hace tres cosas distintas y ninguna miente:

| Situación | Qué muestra la sección "IA local" |
|---|---|
| La cátedra no permite IA local | Segmento de modo deshabilitado con el motivo de la cátedra (§6.2). |
| La cátedra permite pero **la plataforma no** | La sección se muestra con una tarjeta explicativa, **sin** buscador, **sin** catálogo y **sin** botón de descarga: *"La IA local todavía no está disponible en este dispositivo. El chat sigue funcionando con el servidor de tu cátedra. En el escritorio de la app sí podés descargar y usar un modelo propio."* El segmento de modo local también queda deshabilitado, con ese mismo motivo. |
| La cátedra permite y la plataforma sí | El catálogo completo (§6.3). |

> **El catálogo en Android no se muestra aunque se pueda descargar.** Descargar
> 2 GB para un motor que no existe es una trampa. La capacidad que decide es
> la del **runtime** (`LocalModelService.available`), no la del downloader.

**Además**: el modelo local es el modo en que el docente **no** ve los chats.
Cuando el alumno cambia a local, la pantalla le recuerda el efecto:

> *"Con IA local, tu docente no ve ni el texto de tus chats ni las
> estadísticas. Podés volver al servidor de la cátedra cuando quieras."*

Y el chat muestra un chip con el modelo que se está usando
(`ChatThread.effectiveModel` ya lo guarda).

### 6.5 El port del downloader (para que la promesa sea cumplible)

Para que "Descargar y usar" sea verdad en escritorio, hay que portar
`llm.catalog.HuggingFaceDownloader` + `llm.catalog.ModelManager` de
FunshiEngineAI a `:llm-api` de Kitoru, **portable**:

| Pieza de FunshiEngineAI | Adaptación en Kitoru |
|---|---|
| `HuggingFaceDownloader.ensureModel(repo, file, dir, onProgress)` | Igual, pero `dir: FilePath` y escritura por `FileSystem.writeBytes` (chunks de 64 KB, nunca el modelo entero en memoria: el "array buffer problem"). |
| `java.io.FileOutputStream(part, resumed)` + `Files.move(part, target)` | `FileSystem.writeBytes(path, bytes, append = true)` + `FileSystem.move` (nuevo; o borrar+escribir). |
| `header(HttpHeaders.Range, "bytes=$already-")` para reanudar | Igual: Ktor `header` con `Range`. |
| `ModelManager` con `Path` | `LocalModelCatalog` con `FilePath`. |
| `ModelOpProgress` | Se reusa (es un tipo de UI neutro) o se devuelve como `DownloadProgress`. |

Extensión necesaria de `FileSystem` (en `:core`):

```kotlin
interface FileSystem {
    // … lo que ya está …
    /** Escribe bytes; con [append] los agrega al final (para reanudar descargas). */
    fun writeBytes(path: FilePath, bytes: ByteArray, append: Boolean = false)
    /** Mueve (renombra) un archivo. */
    fun move(from: FilePath, to: FilePath)
}
```

Y `LocalModelService` de escritorio **arranca** `llama-server` con el modelo
elegido (ya lo hace: `findModel(settings)` busca el `.gguf` en `modelsDir`).

---

## 7. "Probar conexión"

### 7.1 Qué llama

`OpenAiCompatibleGateway.listModels()` (ya implementado en
`llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt`): `GET {baseUrl}/models`
con `Authorization: Bearer <clave>` si hay clave.

El patrón es **exactamente** el de `ChatCatalog.testApi` de FunshiEngineAI:
`withTimeout(20_000)` alrededor de la llamada y `gw.close()` en el `finally`.

```kotlin
// FunshiEngineAI/frontend/src/main/kotlin/app/chat/ChatCatalog.kt (patrón a portar)
val names = withTimeout(20_000) { gw.listModels() }
```

En la pantalla del alumno, la prueba usa la **configuración efectiva**
(§1.4): si el alumno tiene un override de URL o de modelo, se prueba eso.

En modo local, la prueba apunta a `http://127.0.0.1:{localPort}/v1` y sirve
para verificar que el modelo local está levantado:
*"El modelo local respondió: 1 modelo disponible."* / *"No responde el modelo
local: ¿lo arrancaste?"* (el `LlmException` de `OpenAiCompatibleGateway` ya
distingue `SERVIDOR_CAIDO` para URLs loopback).

### 7.2 Los estados

Se reutiliza el `ConnectionTestState` sellado propuesto en
`teacher/05-config-modelo.md` §4.2 (`Idle`, `Testing`, `Ok(models, warning)`,
`Failed(title, hint, detail, retryable)`), que ya es común a las dos pantallas:

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

El aviso del modelo ausente (el caso 404 de Gemini) se conserva:

> *"El modelo «gpt-4o» no figura en la lista del servidor. Con el
> Kitoru-Server eso da HTTP 404 al chatear."* + botón **"Usar «Qwen3-8B»"**.

### 7.3 Errores: `LlmException` → español

El traductor es **compartido** con la pantalla del docente:
`llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt` (propuesto en
05 §4.4). Se le agrega un parámetro de **perspectiva** para que el consejo
sea el del alumno:

```kotlin
// llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt (extensión)
package llm.error

/** A quién se le escribe el consejo: el consejo cambia según quién mira. */
enum class ErrorAudience { GENERAL, STUDENT, TEACHER }

/** Un error listo para mostrar: título corto + qué revisar + detalle técnico. */
data class LlmErrorText(
    val title: String,
    val hint: String,
    val detail: String,
    val retryable: Boolean,
)

object LlmErrorMessages {
    /** Traduce la excepción; el consejo depende de [audience]. */
    fun describe(e: LlmException, audience: ErrorAudience = ErrorAudience.GENERAL): LlmErrorText
}
```

Los mensajes del **alumno** (los que la pantalla muestra):

| `LlmErrorCode` | Título | Consejo al alumno |
|---|---|---|
| `CLAVE_INVALIDA` | "La clave fue rechazada." | "Si usás la clave de tu cátedra, no la toques: puede estar vencida. Pedile una nueva a tu docente. Si usás una clave propia, revisala." |
| `SIN_CUOTA` | "El servidor alcanzó su límite de uso." | "La cuota de la cátedra se agotó. Avisale a tu docente: no es un problema de tu configuración." |
| `TIMEOUT` | "El servidor tardó demasiado." | "Revisá que la dirección sea correcta y que el servicio esté accesible. Si estás en la facultad, probá con otra red." |
| `MODELO_NO_DISPONIBLE` | "No se pudo consultar el servidor." | "Revisá la dirección (tiene que terminar en /v1) y que el modelo exista. Probá "Probar conexión" de nuevo." |
| `SERVIDOR_CAIDO` | "No se pudo conectar con el servidor." | "El servidor no responde. Si es un servidor local, fijate de que la app esté abierta." |
| `CONTEXTO_AGOTADO` | "Se agotó el contexto del modelo." | "Subí el límite de tokens o bajá la cantidad de fragmentos recuperados." |
| `NO_CONFIGURADO` | "Falta configuración." | "Completá la dirección del servidor y el modelo antes de probar." |
| `DESCARGA_FALLIDA` | "Falló la descarga del modelo." | "Revisá tu conexión a internet y volvé a intentarlo; la descarga se reanuda donde quedó." |
| `CANCELADO` | "Se canceló la prueba." | "" |
| `DESCONOCIDO` | "Error inesperado." | "Revisá la dirección y la clave, y probá de nuevo." |

> Todos los mensajes ya vienen **en español** en `LlmException.fromHttp` /
> `fromNetwork`; lo que agrega `LlmErrorMessages` es la **acción concreta**.
> El detalle técnico se muestra aparte, en letra chica, para que el alumno
> pueda reportarlo.

### 7.4 Lo que la prueba **no** hace

- No guarda nada: probar no cambia la configuración.
- No manda unaCompletion al modelo (sólo `GET /models`): no gasta cuota ni
  aparece en la analítica de la cátedra.
- Si el campo está bloqueado, la prueba **igual** prueba el valor heredado:
  es la información que el alumno necesita ("el servidor de la cátedra no
  responde").

---

## 8. La clave de la API

### 8.1 Qué ve el alumno

| De quién es la clave | ¿Se ve completa? | Controles |
|---|---|---|
| **De la cátedra** (compartida) | **No.** Sólo los últimos 4 caracteres: `ktr_live_••••••••3f9c` | Sin botón de ojo, sin copiar. Texto: *"Clave de la cátedra (no se muestra completa: la provee la institución)"*. |
| **Propia del alumno** | Sí, si el alumno la escribió | Enmascarada por defecto, con ojo ("Mostrar") y "Copiar". |

Reglas:

1. La clave de la cátedra **nunca** se muestra completa en la pantalla del
   alumno, ni siquiera con un "Mostrar": no es suya.
2. La clave propia **sólo** la ve su dueño (el alumno). El docente no la ve
   (no está en `Course.modelPolicy` ni en `ApiKeyGrant`).
3. El **diff** y el **resumen para compartir** nunca incluyen la clave (ver
   §4.3).
4. La clave **nunca** se loguea, nunca va en una URL y nunca se manda a un
   servicio de telemetría.

Enmascarado (función pura en `:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/SecretMask.kt
package kitoru.domain

/** Enmascarado de secretos para la UI. */
object SecretMask {

    /** "ktr_live_7f3d9c2a" → "ktr_live_••••••••••••". */
    fun mask(secret: String): String {
        if (secret.isEmpty()) return ""
        val keep = 4
        return if (secret.length <= keep) "•".repeat(secret.length)
        else "•".repeat(secret.length - keep) + secret.takeLast(keep)
    }

    /** Sólo los últimos 4 caracteres, con puntos delante: "••••••••3f9c". */
    fun tail(secret: String, visible: Int = 4): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= visible) return "•".repeat(secret.length)
        return "•".repeat(8) + secret.takeLast(visible)
    }

    /** Un identificador corto para citar una clave sin exponerla: "clave …3f9c". */
    fun label(secret: String): String =
        if (secret.isBlank()) "sin clave" else "clave …${secret.takeLast(4)}"
}
```

### 8.2 El almacén seguro (`expect/actual`)

La clave **no** va en el JSON. Va en un almacén cifrado en reposo, con un
seam portable:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecretStore.kt
package kitoru.platform

/**
 * Almacén de secretos cifrados en reposo.
 *
 * Es el **único** lugar donde vive una clave de API. Los archivos JSON de
 * Kitoru guardan sólo el *alias* del secreto (`kitoru/course/<id>/apikey/...`),
 * nunca el valor.
 *
 * Contrato:
 *  - [put] es idempotente: el mismo alias se reemplaza.
 *  - [get] devuelve `null` si el alias no existe.
 *  - [aliases] devuelve **sólo los alias** (para diagnóstico), nunca valores.
 *  - Si el almacén no está disponible en la plataforma, [available] es `false`
 *    y la app **no** cae a guardar en claro: falla y lo dice (§8.4).
 */
interface SecretStore {
    val available: Boolean
    fun put(alias: String, secret: String)
    fun get(alias: String): String?
    fun delete(alias: String)
    fun aliases(): List<String>
}

/** Crea el almacén de secretos de la plataforma. */
expect fun createSecretStore(): SecretStore
```

**Android** (`core/src/androidMain/kotlin/kitoru/platform/SecretStore.android.kt`):

- `androidx.security:security-crypto` → `EncryptedSharedPreferences` con
  `MasterKey` (AES256_GCM, `setUserAuthenticationRequired(false)`,
  `setKeyScheme(AES256_GCM)`), la clave maestra en el **Android Keystore**.
- Si hay hardware-backed (`StrongBox`) se lo pide; si no, Keystore por software.
- Necesita el `Context`: se inicializa como `KitoruPaths.setDataDir(...)`, con
  un `KitoruAndroid.init(context)` (el mismo patrón que propone
  `domain/07-anuncios.md` §5.3).
- Si el Keystore falla (device roto, emulator sin keystore),
  `available = false` → la app **no** guarda la clave en claro.

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/SecretStore.android.kt (extracto)
class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs by lazy {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kitoru_secrets",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    override val available: Boolean get() = runCatching { prefs }.isSuccess
    override fun put(alias: String, secret: String) { prefs.edit().putString(alias, secret).apply() }
    override fun get(alias: String): String? = prefs.getString(alias, null)
    override fun delete(alias: String) { prefs.edit().remove(alias).apply() }
    override fun aliases(): List<String> = prefs.all.keys.sorted()
}
```

**Escritorio** (`core/src/desktopMain/kotlin/kitoru/platform/SecretStore.desktop.kt`):

- **Windows**: **DPAPI** (`CryptProtectData` / `CryptUnprotectData`, ámbito
  *usuario*, no máquina) vía JNA (`net.java.dev.jna:jna-platform:5.14.0`).
  El valor cifrado se guarda en `config/secrets/secrets.bin` (base64 del blob
  DPAPI). La clave queda atada al usuario de Windows: otro usuario de la
  misma máquina no la puede leer.
- **Linux / macOS**: no hay DPAPI. Fallback: `config/secrets/secrets.json` con
  permisos `0600` (sólo el dueño) y una advertencia explícita en el panel de
  ajustes: *"En este sistema las claves se guardan con permisos de archivo, no
  cifradas."* Es protección parcial y **se dice**, no se disimula.
- Si en el futuro se quiere cifrado real en Linux/macOS: `libsecret` (GNOME
  Keyring) o el Llavero de macOS vía JNA. Queda anotado, no implementado.

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/SecretStore.desktop.kt (extracto)
class DesktopSecretStore(private val fs: FileSystem, private val dir: FilePath) : SecretStore {
    // Windows: DPAPI (JNA). Linux/macOS: AES-GCM con clave maestra local
    // (archivo 0600) + aviso de "protección parcial" en la UI.
}
```

### 8.3 Cómo se conecta el secreto con el modelo de dominio

`ModelPolicy.apiKey` es un `String` en claro (lo usa el gateway). El problema
es **persistirlo**. Solución: agregar un campo con valor por defecto (los JSON
viejos siguen deserializando) y dos funciones de ida y vuelta.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt (extensión aditiva)
data class ModelPolicy(
    // … lo que ya está …
    /**
     * Alias en el `SecretStore` donde vive la clave compartida de la cátedra.
     * "" = la clave está en claro en este objeto (caso del docente, que la
     * tiene en su propia máquina).
     */
    val apiKeyRef: String = "",
)
```

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/PolicySecrets.kt
package kitoru.domain

import kitoru.platform.SecretStore

/**
 * Ida y vuelta de la clave de API entre el modelo y el almacén seguro.
 *
 * En el dispositivo del ALUMNO la clave **nunca** se persiste en claro:
 * al importar la cátedra se la mete en el `SecretStore` y en el JSON queda
 * sólo el alias.
 */
object PolicySecrets {

    /** Mete la clave en el almacén y devuelve la política sin el secreto. */
    fun dehydrate(policy: ModelPolicy, store: SecretStore, alias: String): ModelPolicy {
        if (policy.apiKey.isBlank()) return policy
        store.put(alias, policy.apiKey)
        return policy.copy(apiKey = "", apiKeyRef = alias)
    }

    /** Vuelve a poner la clave en la política (desde el alias). */
    fun hydrate(policy: ModelPolicy, store: SecretStore): ModelPolicy {
        if (policy.apiKeyRef.isBlank()) return policy
        val secret = store.get(policy.apiKeyRef) ?: return policy
        return policy.copy(apiKey = secret)
    }

    /** Borra la clave del almacén (al borrar el curso o al dejar de usarla). */
    fun revoke(policy: ModelPolicy, store: SecretStore) {
        if (policy.apiKeyRef.isNotBlank()) store.delete(policy.apiKeyRef)
    }
}
```

Los alias (convención única, en `:core`):

```kotlin
object SecretAliases {
    /** Clave compartida de la cátedra. */
    fun sharedKey(courseId: String) = "kitoru/course/$courseId/apikey/shared"

    /** Clave personal del alumno para esa cátedra. */
    fun personalKey(courseId: String, userId: String) =
        "kitoru/course/$courseId/apikey/personal/$userId"
}
```

### 8.4 Reglas de seguridad (criterio de aceptación)

1. Ningún archivo JSON de `config/` contiene una clave de API en claro.
2. `StudentModelSettings.apiKeyAlias` es un **alias**, nunca la clave.
3. Si el `SecretStore` no está disponible, la app **no** guarda la clave en
   claro: el campo queda deshabilitado con el motivo *"No podemos guardar la
   clave de forma segura en este dispositivo"*.
4. La clave de la cátedra se **importa** al `SecretStore` al recibir el bundle
   de la cátedra (`dehydrate`), y se rehidrata al usarla (`hydrate`).
5. La clave nunca aparece en logs, ni en mensajes de error, ni en el diff, ni
   en el resumen para el docente.

---

## 9. Persistencia: la configuración es por cátedra

### 9.1 Dónde se guarda

| Dato | Ámbito | Archivo | Repositorio |
|---|---|---|---|
| `StudentModelSettings` | **por cátedra + usuario** | `config/student-settings.json` | `StudentSettingsRepository` |
| Catálogo de modelos locales | **del dispositivo** | `config/local-models.json` | `LocalModelCatalogRepository` |
| Pedidos al docente | por cátedra + usuario | `config/config-requests.json` | `ConfigRequestRepository` |
| Claves de API | **fuera de los JSON** | almacén cifrado | `SecretStore` |
| Archivos `.gguf` | del dispositivo | `<dataDir>/models/` | (archivos) |

El mismo alumno en dos cátedras tiene **dos** registros de
`StudentModelSettings`: puede usar el modelo de una y el local en la otra.

### 9.2 Las interfaces

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Repositories.kt (extensión)
interface StudentSettingsRepository {
    suspend fun get(courseId: String, userId: String): StudentModelSettings?
    suspend fun listForUser(userId: String): List<StudentModelSettings>
    suspend fun save(settings: StudentModelSettings)
    /** Vuelve a la configuración de la cátedra (borra los overrides). */
    suspend fun reset(courseId: String, userId: String)
}

interface LocalModelCatalogRepository {
    suspend fun list(): List<LocalModelEntry>
    suspend fun save(entry: LocalModelEntry)
    suspend fun remove(id: String)
}

interface ConfigRequestRepository {
    suspend fun listForCourse(courseId: String): List<ConfigRequest>
    suspend fun listForUser(userId: String): List<ConfigRequest>
    suspend fun save(request: ConfigRequest)
}
```

Implementaciones `File*` con el patrón de `FileBackedRepository` (ya existente
en `FileRepositories.kt`): `FileStudentSettingsRepository(fs)` sobre
`"student-settings.json"`, etc.

> `StudentSettingsRepository.get(courseId, userId)` necesita una clave compuesta:
> `FileBackedRepository` usa el id por defecto, así que la subclase sobreescribe
> `idOf(item) = "${item.courseId}|${item.userId}"`.

### 9.3 El modelo local descargado es del dispositivo

El `.gguf` pesa cientos de MB: **no** se duplica por cátedra. El catálogo
(`LocalModelEntry`) es global:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/LocalModelEntry.kt
package kitoru.domain

/** Un modelo GGUF en el catálogo del dispositivo. */
@Serializable
data class LocalModelEntry(
    val id: String,
    /** Repo de Hugging Face. */
    val repo: String,
    /** Archivo .gguf dentro del repo. */
    val file: String,
    /** Etiqueta para mostrar. */
    val label: String = "",
    /** Tamaño esperado (bytes), si se conoce. */
    val sizeBytes: Long = 0L,
    /** ¿Está descargado? (lo calcula el `LocalModelCatalog` sobre el disco). */
    val downloaded: Boolean = false,
    /** Bytes ya bajados de una descarga a medias. */
    val partBytes: Long = 0L,
    /** ¿Es el modelo activo en alguna cátedra? (lo calcula el ViewModel). */
    val active: Boolean = false,
)
```

Lo **por cátedra** es sólo la *elección* (`localHfRepo` / `localHfFile` en
`StudentModelSettings`).

### 9.4 Versionado

`StudentModelSettings` es nuevo: no hay migración. Si mañana se le agregan
campos, `KitoruJson` ya tiene `ignoreUnknownKeys = true` y `encodeDefaults =
true`, así que los JSON viejos siguen deserializando con los defaults.

---

## 10. Nombres: archivos, composables, view-models

### 10.1 `:core` (dominio y plataforma)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/StudentModelSettings.kt` | `StudentModelSettings`, `OverrideState` |
| `core/src/commonMain/kotlin/kitoru/domain/StudentField.kt` | `StudentField`, `FieldKind`, `ConfigSection` |
| `core/src/commonMain/kotlin/kitoru/domain/StudentPolicy.kt` | `StudentPolicy` (states/effective/diff/summary), `ConfigDiffEntry` |
| `core/src/commonMain/kotlin/kitoru/domain/LockReasons.kt` | `LockReasons` (motivos en español) |
| `core/src/commonMain/kotlin/kitoru/domain/ConfigRequestTemplates.kt` | `ConfigRequestTemplates` (mensajes al docente) |
| `core/src/commonMain/kotlin/kitoru/domain/ConfigRequest.kt` | `ConfigRequest` |
| `core/src/commonMain/kotlin/kitoru/domain/LocalModelEntry.kt` | `LocalModelEntry` |
| `core/src/commonMain/kotlin/kitoru/domain/SecretMask.kt` | `SecretMask` |
| `core/src/commonMain/kotlin/kitoru/domain/PolicySecrets.kt` | `PolicySecrets` (dehydrate/hydrate/revoke) |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | `StudentSettingsRepository`, `LocalModelCatalogRepository`, `ConfigRequestRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | `FileStudentSettingsRepository`, `FileLocalModelCatalogRepository`, `FileConfigRequestRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt` | **+** `apiKeyRef` |
| `core/src/commonMain/kotlin/kitoru/platform/SecretStore.kt` | `SecretStore` + `expect createSecretStore()` |
| `core/src/androidMain/kotlin/kitoru/platform/SecretStore.android.kt` | `EncryptedSharedPreferences` |
| `core/src/desktopMain/kotlin/kitoru/platform/SecretStore.desktop.kt` | DPAPI (Windows) / permisos 600 (Linux-macOS) |
| `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` | **+** `writeBytes`, `move` |

### 10.2 `:llm-api`

| Archivo | Contenido |
|---|---|
| `llm-api/src/commonMain/kotlin/llm/error/LlmErrorMessages.kt` | `LlmErrorText`, `LlmErrorMessages.describe(e, audience)`, `ErrorAudience` |
| `llm-api/src/commonMain/kotlin/llm/api/LocalModelService.kt` | **+** `available`, `unavailableReason` |
| `llm-api/src/androidMain/kotlin/llm/api/LocalModelService.android.kt` | `available = false` + motivo |
| `llm-api/src/desktopMain/kotlin/llm/api/LocalModelService.desktop.kt` | `available = true` |
| `llm-api/src/commonMain/kotlin/llm/catalog/HuggingFaceDownloader.kt` | **port** de FunshiEngineAI, portable (Ktor + `FileSystem`) |
| `llm-api/src/commonMain/kotlin/llm/catalog/LocalModelCatalog.kt` | **port** de `ModelManager`: catálogo + descarga con progreso + `activate` |

### 10.3 `:ui` — paquete `kitoru.ui.student.config`

```
ui/src/commonMain/kotlin/kitoru/ui/
├── common/                                ← compartido con el docente
│   ├── ConnectionTestState.kt             (viene de 05 §4.2)
│   ├── SectionCard.kt
│   └── components/
│       └── ConnectionTestPanel.kt         (el mismo panel para ambos lados)
└── student/
    ├── config/
    │   ├── StudentModelConfigScreen.kt     ← la pantalla
    │   ├── StudentModelConfigViewModel.kt  ← estado y acciones
    │   ├── StudentModelConfigState.kt      ← el estado del ViewModel
    │   ├── sections/
    │   │   ├── ServerSection.kt            ← 1
    │   │   ├── ModelSection.kt             ← 2
    │   │   ├── GenerationSection.kt        ← 3
    │   │   ├── RagSection.kt               ← 4
    │   │   ├── ToolsSection.kt             ← 5
    │   │   └── LocalAiSection.kt           ← 6
    │   ├── components/
    │   │   ├── OverrideFieldRow.kt         ← la fila con heredado + propio
    │   │   ├── OverrideStateChip.kt        ← los tres chips
    │   │   ├── LockedNotice.kt             ← candado + motivo + "Pedir a mi docente"
    │   │   ├── SecretField.kt              ← campo enmascarado (ojo + copiar)
    │   │   └── ConfigDiffPanel.kt          ← la vista de diff
    │   ├── dialogs/
    │   │   ├── RequestUnlockDialog.kt      ← "Pedir a mi docente"
    │   │   └── ShareSummaryDialog.kt       ← copiar / compartir el resumen
    │   └── local/
    │       ├── LocalModelCatalogScreen.kt  ← port de ModelsPane
    │       ├── LocalModelCard.kt           ← port de ModelCard
    │       └── RepoFilesList.kt            ← port de RepoFilesList
    └── components/
        └── ModelQuickSheet.kt              ← mini-panel desde el chat
```

### 10.4 Los composables principales

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/config/StudentModelConfigScreen.kt
@Composable
fun StudentModelConfigScreen(
    courseId: String,
    viewModel: StudentModelConfigViewModel,
    modifier: Modifier = Modifier,
)

/** Atajo desde el chat: un bottom sheet con el modelo activo y el diff. */
@Composable
fun ModelQuickSheet(
    state: StudentModelConfigState,
    onOpenFull: () -> Unit,
    onDismiss: () -> Unit,
)
```

### 10.5 El ViewModel

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/config/StudentModelConfigViewModel.kt
class StudentModelConfigViewModel(
    private val courseId: String,
    private val userId: String,
    private val courseRepository: CourseRepository,
    private val settingsRepository: StudentSettingsRepository,
    private val catalogRepository: LocalModelCatalogRepository,
    private val requestRepository: ConfigRequestRepository,
    private val secretStore: SecretStore,
    private val localModelService: LocalModelService,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<StudentModelConfigState>

    fun load()
    fun setMode(mode: ModelMode)
    fun override(field: StudentField, value: String?)     // null = heredar
    fun revert(field: StudentField)                        // vuelve al de la cátedra
    fun revertAll()
    fun savePersonalApiKey(key: String)                     // va al SecretStore
    fun clearPersonalApiKey()
    fun testConnection()
    fun requestUnlock(field: StudentField)                  // genera el mensaje
    fun useLocalModel(entry: LocalModelEntry)               // elige el GGUF
    fun downloadLocalModel(entry: LocalModelEntry)          // desktop
}
```

Estado:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/config/StudentModelConfigState.kt
data class StudentModelConfigState(
    val course: Course? = null,
    /** Los ajustes del alumno (el borrador vivo). */
    val settings: StudentModelSettings = StudentModelSettings("", ""),
    /** Estado de cada campo. */
    val states: Map<StudentField, OverrideState> = emptyMap(),
    /** La configuración que se va a usar de verdad. */
    val effective: ModelPolicy = ModelPolicy(),
    /** El diff contra la cátedra. */
    val diff: List<ConfigDiffEntry> = emptyList(),
    /** Estado de la prueba de conexión. */
    val connection: ConnectionTestState = ConnectionTestState.Idle,
    /** Modelos que devolvió el último test OK. */
    val availableModels: List<String> = emptyList(),
    /** Catálogo local del dispositivo. */
    val catalog: List<LocalModelEntry> = emptyList(),
    /** Progreso de descarga (null = nada en curso). */
    val download: DownloadProgress? = null,
    /** ¿La plataforma puede correr un modelo local? */
    val localAvailable: Boolean = false,
    /** Motivo por el que no puede (si no puede). */
    val localUnavailableReason: String = "",
    /** Diálogo de pedido abierto (campo o null). */
    val requestField: StudentField? = null,
    /** Última acción (para el Snackbar). */
    val message: String? = null,
)
```

### 10.6 Tests (`:core`, `commonTest`)

| Test | Qué cubre |
|---|---|
| `StudentPolicyTest` | `states` con override permitido / bloqueado / sin override; `effective` aplica bloqueos vía `resolveFor`; `diff` no incluye campos iguales; el diff marca los overrides ignorados. |
| `StudentFieldTest` | Cada campo del enum tiene su `LockedField` correcto y aparece en una sección. |
| `SecretMaskTest` | Enmascarado, cola de 4, casos vacíos y cortos. |
| `PolicySecretsTest` | `dehydrate` guarda en el store y vacía el claro; `hydrate` lo repone; `revoke` borra. Con un `SecretStore` falso en memoria. |
| `LockReasonsTest` | Cada `LockedField` tiene un motivo no vacío, en español, y termina en punto. |
| `ConfigRequestTemplatesTest` | El mensaje menciona el campo, la cátedra y el nombre del alumno; no contiene secretos. |
| `ConfigShareSummaryTest` | El resumen **no** contiene la clave ni el prompt completo; sí contiene los campos con diferencias. |

---

## 11. Casos borde

| Caso | Qué hace la pantalla |
|---|---|
| El alumno no tiene ajustes guardados | Todo `INHERITED`. La pantalla muestra la config de la cátedra en sólo lectura, con el switch "Usar el valor de la cátedra" en ON. |
| `allowStudentOverride = false` | Banner arriba: *"Tu cátedra no permite cambiar la configuración del modelo."* Todos los campos `LOCKED`, sin switches. El diff está vacío. |
| El docente bloquea un campo **después** de que el alumno lo ajustó | El override queda guardado pero ignorado; el campo pasa a `LOCKED`; el diff lo muestra con `ignored = true` y el motivo; el chat usa el valor de la cátedra. |
| El docente cambia la config base | El override del alumno se reaplica sin que el alumno toque nada; si el valor heredado cambió y el alumno no había tocado ese campo, se ve el nuevo valor. |
| El alumno puso el mismo valor que la cátedra | Se muestra `INHERITED` (no ensucia el diff). |
| El alumno borró su clave personal | El campo queda vacío con el texto "Sin clave: se usará la de tu cátedra". |
| La clave de la cátedra no llegó (bundle sin clave) | El campo de clave muestra "Sin clave" en rojo y el botón "Probar conexión" avisa: *"Falta la clave de la cátedra. Pedile una a tu docente."* |
| El `SecretStore` no está disponible | El campo de clave propia queda deshabilitado con el motivo: *"No podemos guardar la clave de forma segura en este dispositivo."* Nunca se cae a texto plano. |
| La cátedra permite IA local pero el dispositivo no | Sección cerrada con el motivo de la plataforma (§6.4); el segmento de modo local deshabilitado. |
| El modelo local fue borrado del disco | El catálogo lo muestra como "No descargado" y el chat local avisa: *"No se encontró el modelo X. Descargalo de nuevo."* |
| La descarga se corta | El `.part` queda y el botón dice "Reanudar"; la UI muestra "Descarga parcial · 1.2 GB de 2.8 GB". |
| El puerto local está ocupado | `LocalModelService.start` falla con `LlmException(SERVIDOR_CAIDO)`; la UI lo traduce: *"El modelo local no responde. El puerto 8081 puede estar ocupado."* |
| El alumno tiene 5 cátedras | Cada una con su configuración: la pantalla siempre muestra la de la cátedra abierta. |
| Cambiar de cátedra con cambios sin guardar | No hay "sin guardar": todo se guarda al instante. Al cambiar de cátedra se recarga el estado de la nueva. |
| El modelo del servidor no está en la lista de `/models` | Warning ámbar con el modelo sugerido y un botón para usarlo (§7.2). |
| `topK` fuera del rango que puso la cátedra | Se recorta y se avisa: *"La cátedra limita los fragmentos a 5. Se va a usar 5."* |

---

## 12. Criterios de aceptación

1. La pantalla muestra, **por campo**, el valor heredado de la cátedra y el
   valor propio del alumno, con un chip que dice en qué estado está
   (`De la cátedra` / `Tu valor` / `Lo fija la cátedra`).
2. Un campo bloqueado está deshabilitado, con candado y con el motivo escrito
   en español; el motivo nunca depende de un tooltip.
3. El switch "Usar el valor de la cátedra" por campo es el **único** control
   del estado: no hay dos controles para la misma opción.
4. La vista "qué cambiaste respecto de la cátedra" lista sólo los campos que
   difieren, permite revertir uno o todos, y **no muestra nunca la clave**.
5. "Pedir a mi docente" genera un mensaje en español que menciona el campo, la
   cátedra y el nombre del alumno; el mensaje es editable y se puede copiar.
6. Cambiar a IA local cuando la cátedra no lo permite deja el control visible
   pero deshabilitado, con el motivo; **no** se borra la configuración local
   previa del alumno.
7. En una plataforma sin motor local (Android), la sección de IA local no
   muestra botones de descarga ni barras de progreso: muestra el motivo y el
   chat sigue funcionando con el servidor de la cátedra.
8. "Probar conexión" llama a `listModels()` con timeout de 20 s, muestra los
   modelos disponibles y traduce los `LlmException` a mensajes en español con
   el consejo del alumno.
9. La clave de la cátedra se muestra sólo enmascarada (últimos 4) y sin
   "mostrar"; la clave propia se puede revelar y copiar.
10. **Ninguna clave de API está en claro en `config/*.json`**: la clave vive en
    el `SecretStore` (EncryptedSharedPreferences en Android, DPAPI en
    escritorio) y en los archivos queda sólo el alias.
11. La configuración del alumno es **por cátedra**: el mismo alumno en dos
    cátedras tiene dos configuraciones independientes.
12. `resolveFor` sigue siendo la única fuente de verdad de "qué gana": la
    pantalla no reimplementa los bloqueos.
