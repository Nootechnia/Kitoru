# Diseño 05 — Política de configuración del modelo

> **Cátedra pone la base, el alumno ajusta.**
> Este documento define el modelo final de `ModelPolicy`: qué decide la
> cátedra, qué decide el alumno, cómo se resuelve la configuración efectiva,
> cómo ve el docente qué cambiaron sus alumnos y cómo viven las API keys.

Estado: **diseño cerrado** (propuesta lista para implementar).
Archivos Affected:

| Archivo | Acción |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt` | se reescribe (bloque final en §10.1) |
| `core/src/commonMain/kotlin/kitoru/domain/StudentModelProfile.kt` | **nuevo** (§10.3) |
| `core/src/commonMain/kotlin/kitoru/domain/ModelResolver.kt` | **nuevo** (§10.4) |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | + `StudentProfileRepository` (§8) |
| `core/src/commonMain/kotlin/kitoru/domain/Course.kt` | `Course.allowLocalModels` queda canónico; `Course.modelPolicy` gana campos |
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | `ChatThread.effectiveModel: ModelPolicy` → `EffectiveModelConfig` (§9.1) |
| `llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt` | + adaptadores `EffectiveModelConfig → GatewayConfig / LocalModelSettings` (§9.2) |

---

## 1. Diagnóstico: por qué el modelo actual no alcanza

El modelo que hay hoy en `ModelPolicy.kt` es:

```kotlin
val allowStudentOverride: Boolean = true
val lockedFields: Set<LockedField> = setOf(LockedField.API_KEY)
fun resolveFor(studentChoice: ModelPolicy): ModelPolicy { ... }
```

Es un buen primer paso (coherente con "la cátedra pone la base"), pero tiene
cinco problemas concretos:

1. **Todo o nada.** `Set<LockedField>` sólo sabe decir "esto no se toca". El
   caso que más va a pedir un docente — *"la temperatura puede ir entre 0 y
   0.8, pero no la toques"*— no se puede expresar: `TEMPERATURE` o está
   bloqueada (rigidez) o está libre (el alumno pone 1.7 y alucina).
2. **El valor por defecto no está modelado.** "La cátedra pone 0.4 y el alumno
   puede bajarlo a 0.1" es exactamente "libre con un default", pero el modelo
   no tiene noción de *default*: el default es implícito (el valor de la
   cátedra) y la UI no puede ofrecer "volver al de la cátedra" como opción.
3. **No hay elegibilidad.** "El modelo tiene que ser uno de estos tres" o
   "el razonamiento sólo `off` o `high`" no se puede expresar.
4. **`resolveFor(studentChoice: ModelPolicy)` mezcla los dos roles.** La
   política de la cátedra y la elección del alumno son del **mismo tipo**:
   no hay forma de distinguirlas, no hay forma de saber *qué* eligió el
   alumno (sólo el resultado final), y no hay forma de guardarle al alumno
   "mis ajustes" sin copiar la política entera.
5. **No hay noción de credencial.** `apiKey: String` no tiene vencimiento, no
   dice de quién es (cátedra vs personal) y no tiene estado. Con la
   institución emitiendo keys que vencen al final de la cursada, eso es
   insuficiente.

**Veredicto:** el `Set` no es suficiente como mecanismo de bloqueo. Hay que
conservarlo (compatibilidad con los JSON ya guardados y atajo natural para
"esto no se toca") pero **agregarle un modelo fino por campo**.

---

## 2. El modelo final en una línea

```
ModelPolicy  = la BASE de la cátedra (valores) + las REGLAS (cuánto puede
               mover el alumno cada campo) + los PRESETS + la KEY de la cátedra
StudentModelProfile = los DELTAS del alumno (sólo lo que cambió) + su modelo
               local + su key personal
ModelResolver.resolve(policy, student) = la configuración EFECTIVA + el
               DIFF campo a campo + el estado de la credencial
```

Regla de oro del diseño:

> **La cátedra guarda valores; el alumno guarda deltas.**
> El perfil del alumno **nunca** copia la política: guarda sólo lo que
> cambió. Por eso el diff es gratis (es la lista de deltas) y por eso un
> cambio en la cátedra (subir la temperatura base de 0.2 a 0.3) se refleja
> en todos los alumnos sin tocar sus perfiles.

---

## 3. `LockedField`: el vocabulario de los campos acotables

`LockedField` deja de significar "campo bloqueado" y pasa a significar
"campo acotable". El nombre se conserva (es lo que está serializado en los
`courses.json` ya guardados) y se agrega el alias `ConfigField` para los
códigos nuevos donde "locked" engaña.

Se agrupan por naturaleza, porque la regla que aplica depende del tipo:

| Tipo | Campos | Reglas que admiten |
|---|---|---|
| **Numéricos** | `TEMPERATURE`, `MAX_TOKENS`, `TOP_K`, `LOCAL_PORT`, `CONTEXT_SIZE`, `GPU_LAYERS` | `Locked`, `Ranged`, `Free` |
| **Texto** | `BASE_URL`, `MODEL_NAME`, `SYSTEM_PROMPT`, `LOCAL_HF_REPO`, `LOCAL_HF_FILE`, `LOCAL_MODELS_DIR`, `LLAMA_SERVER_PATH` | `Locked`, `Choice`, `Free` |
| **Discretos** | `MODE`, `TOOLS_ENABLED`, `THINKING`, `POWER` | `Locked`, `Choice`, `Free` |
| **Secreto** | `API_KEY` | `Locked`, `Choice` (`COURSE` / `PERSONAL`), `Free` |

`SYSTEM_PROMPT` merece un caso especial (ver §5.4): no se "reemplaza" sino
que se **concatena** (instrucciones de la cátedra + instrucciones del
alumno), salvo que esté bloqueado.

---

## 4. `FieldConstraint`: las cuatro formas de acotar un campo

El set se reemplaza por un `Map<LockedField, FieldConstraint>` con cuatro
estados. Cada estado responde: **¿qué puede hacer el alumno?**

| Estado | Qué puede hacer el alumno | Cómo se aplica | Ejemplo |
|---|---|---|---|
| `Free` | cambiar el valor como quiera | gana el valor del alumno | la temperatura del alumno |
| `Ranged(min, max)` | moverlo dentro del intervalo | se recorta al borde | temperatura 0.0 – 0.8 |
| `Choice(values)` | elegir uno de los valores | si no está en la lista, gana la base | razonamiento `off` \| `high` |
| `Locked` | nada | gana el valor de la cátedra | la key y la URL del servidor |

Y el **cuarto elemento que pedía el enunciado —"valor por defecto"—** no es
un estado del `constraint` sino el **valor base de la cátedra**: para cada
campo, la base es el valor que tiene la cátedra en `ModelPolicy`. O sea:

- *bloqueado* = `Locked`;
- *rango permitido* = `Ranged`;
- *valor por defecto* = la base de `ModelPolicy` (siempre existe; es lo que
  la UI muestra como "por defecto" y lo que el alumno puede recuperar con un
  botón "volver al de la cátedra");
- *libre* = `Free`.

`Choice` hace explícito el default para los campos discretos: **el primer
valor de `values` es el valor por defecto de la cátedra**.

### 4.1 Cómo se guardan las dos cosas (y por qué)

`ModelPolicy` queda con los dos mecanismos, sin duplicar información:

```kotlin
val lockedFields: Set<LockedField>       // legado/compat + atajo "esto no se toca"
val fieldRules: Map<LockedField, FieldConstraint>   // modelo fino
```

- `effectiveRules` = `lockedFields → Locked` **pisado por** `fieldRules`.
  Es decir: `fieldRules` manda; `lockedFields` es el atajo.
- `withRule(field, rule)` mantiene los dos coherentes: si `rule` es
  `Locked` → agrega a `lockedFields` y saca de `fieldRules`; si no →
  saca de `lockedFields` y pone en `fieldRules`. **Una sola fuente de verdad
  por campo.**
- Un `courses.json` viejo (sin `fieldRules`) sigue funcionando: `KitoruJson`
  tiene `ignoreUnknownKeys = true` y los campos nuevos tienen default.

### 4.2 Tabla de equivalencias (para migrar la UI actual)

| Lo que el docente quiere | Cómo se modela |
|---|---|
| "no se puede tocar" | `LockedField.X in lockedFields` **o** `fieldRules[X] = Locked` |
| "sólo entre 0 y 0.8" | `fieldRules[TEMPERATURE] = Ranged(0.0, 0.8)` |
| "sólo estos modelos" | `fieldRules[MODEL_NAME] = Choice(listOf("qwen3-32b", "phi-4"))` |
| "razonamiento off o high" | `fieldRules[THINKING] = Choice(listOf("off", "high"))` |
| "dejalo como yo lo puse" | `fieldRules[X] = Choice(listOf(valorBase))` (default explícito) |
| "el alumno puede hacer lo que quiera" | no va en `lockedFields` ni en `fieldRules` (o `Free` explícito) |
| "los alumnos usan su propia key" | `fieldRules[API_KEY] = Choice(listOf("PERSONAL"))` |
| "la key es la de la cátedra" | `fieldRules[API_KEY] = Choice(listOf("COURSE"))` o `Locked` |

### 4.3 Reglas por defecto (sensible)

Una `ModelPolicy` nueva (`ModelPolicy()`) deja:

```kotlin
lockedFields = setOf(LockedField.API_KEY)   // la key es de la institución
allowStudentOverride = true
fieldRules = emptyMap()
```

Es decir, **todot freer excepto la key**. La cátedra que quiera rigor tiene
que activarlo explícitamente; la que quiera control total, poner
`allowStudentOverride = false`.

### 4.4 El único campo que no se puede bloquear del todo: el MODO

`allowStudentOverride = false` significa: *el alumno usa **exactamente** lo
de la cátedra*. Pero el **modo** (REMOTE ↔ LOCAL) queda exceptuado, y es a
propósito:

- Si la key de la cátedra venció y el alumno tiene IA local descargada,
  bloquearle el modo lo deja sin poder estudiar. El modo es la **válvula de
  escape**.
- La ESI (visibilidad del texto) ya está modelada aparte
  (`ChatVisibility`): en LOCAL el docente no ve el texto salvo que el alumno
  lo comparta. No hace falta bloquear el modo para proteger la privacidad.

Regla exacta:

```
modo efectivo =
    si el alumno pidió LOCAL y la cátedra NO permite local  → REMOTE (la de la cátedra)
    si la regla de MODE es Locked                            → policy.mode
    si la regla de MODE es Choice y no está permitido         → policy.mode
    si no                                                    → lo que pidió el alumno
```

---

## 5. La resolución: `ModelResolver.resolve`

### 5.1 Firma

```kotlin
object ModelResolver {
    fun resolve(
        course: Course,
        student: StudentModelProfile,
        now: Long = nowMillis(),
    ): EffectiveModelConfig

    fun resolve(
        policy: ModelPolicy,
        student: StudentModelProfile,
        allowLocalModels: Boolean = true,
        now: Long = nowMillis(),
    ): EffectiveModelConfig
}
```

Es **determinista**: no toca la red y el reloj entra por parámetro (el
default es `nowMillis()`). Eso la hace testeable y hace que el diff que ve
el docente sea reproducible.

`Course.allowLocalModels` queda **canónico** (no se duplica en
`ModelPolicy`): es una decisión de la cátedra, no del modelo, y ya existe
en el dominio. Por eso el resolver tiene dos overloads: uno con `Course`
(uso normal) y otro con la política suelta (tests).

### 5.2 El algoritmo, campo por campo

Para cada campo `F` el resolver calcula tres valores:

- **base** = el valor de la cátedra (`policy.<campo>`).
- **pedido** = `delta del alumno` ?: `valor del preset elegido` ?: `base`.
  (Si `allowStudentOverride = false`, el "pedido" es la base: el alumno no
  corre.)
- **efectivo** = `aplicar(regla[F], base, pedido)`.

Aplicación de la regla:

| Regla | efectivo | estado del cambio |
|---|---|---|
| `Locked` | `base` | `REJECTED` si el pedido ≠ base |
| `Ranged(min,max)` | `pedido.coerceIn(min,max)` | `CLAMPED` si recortó, `ACCEPTED` si no |
| `Choice(values)` | `pedido` si está en la lista, si no `base` | `REJECTED` si no estaba |
| `Free` / sin regla | `pedido` | `ACCEPTED` si ≠ base |

Y el **origen** del pedido (`ChangeSource`) para que el docente entienda de
dónde salió: `STUDENT` (delta propio), `PRESET` (tomó un preset de la
cátedra), `POLICY` (no lo tocó).

### 5.3 Los tres casos que pide el enunciado

**(a) La cátedra no permite override** (`allowStudentOverride = false`):

```
modo         → el de la cátedra (salvo LOCAL si la cátedra lo permite y el alumno lo pide)
baseUrl      → el de la cátedra
apiKey       → la de la cátedra
modelName    → el de la cátedra
temperature  → 0.2 (la de la cátedra)
maxTokens    → 1024
topK         → 5
systemPrompt → el de la cátedra
```

El alumno no ve sliders: ve la configuración de la cátedra como informativa
(sin controles) y un cartel: *"La cátedra no permite ajustar la
configuración del modelo"*.

**(b) La cátedra bloquea la API key pero permite el modelo:**

```kotlin
ModelPolicy(
    remoteBaseUrl = "https://ia.un.edu.ar/kitoru/v1",
    apiKey = "kitoru-abc123",
    apiKeyExpiresAt = <fin de la cursada>,
    modelName = "qwen3-32b",
    lockedFields = setOf(LockedField.API_KEY),
    fieldRules = mapOf(
        LockedField.TEMPERATURE to FieldConstraint.Ranged(0.0, 0.8),
        LockedField.MAX_TOKENS to FieldConstraint.Ranged(256, 4096),
        LockedField.TOP_K to FieldConstraint.Ranged(3, 8),
    ),
    presets = listOf(
        ModelPreset(id = "rapido",  label = "Rápido",    modelName = "qwen3-8b",  tag = "rápido",   temperature = 0.2),
        ModelPreset(id = "razonador", label = "Razonador", modelName = "qwen3-32b", tag = "razonador", temperature = 0.4, thinking = "high"),
        ModelPreset(id = "barato",   label = "Económico",  modelName = "qwen3-8b",  tag = "barato",   maxTokens = 512),
    ),
    defaultPresetId = "rapido",
)
```

⇒ el alumno **no puede cambiar la key** (bloqueada), **sí puede cambiar el
modelo** (libre o por preset) y **puede mover la temperatura dentro de
0.0–0.8**: si pone 0.95, el efectivo es 0.8 y el diff lo marca como
`CLAMPED` con el motivo *"La cátedra limita la temperatura a 0.00 – 0.80"*.

**(c) El alumno usa IA local:**

```
baseUrl      → "" (no aplica)
apiKey       → "" (no aplica)  → KeySource.NONE
modelName    → el nombre de su GGUF (Phi-4-mini-instruct por defecto)
local        → SU LocalModelProfile (hfRepo/hfFile/puerto/contexto/capas)
temperature  → la regla de la cátedra se sigue aplicando (0.0–0.8)
maxTokens    → ídem
topK         → ídem (el RAG sigue siendo el de la cátedra)
systemPrompt → el de la cátedra + el del alumno (si no está bloqueado)
```

Detalle importante: **el modelo local es del alumno, pero los parámetros de
generación siguen siendo los de la cátedra**. La cátedra no puede "usar" el
GGUF del alumno, pero sí puede acotarle la temperatura o el top-k (para que
las respuestas sean comparables entre alumnos). Y si `CONTEXT_SIZE` tiene
`Ranged(2048, 16384)`, se le recorta el contexto del modelo local.

### 5.4 El `systemPrompt` es composición, no reemplazo

El prompt del sistema de la cátedra es lo que define el rol ("Sos un tutor
de la materia X"). El del alumno es un extra ("resumí en viñetas"). Entonces:

- `SYSTEM_PROMPT` libre → efectivo = `promptCátedra.trim() + "\n\n" + promptAlumno`
- `SYSTEM_PROMPT` bloqueado → efectivo = `promptCátedra`; lo del alumno se
  descarta y el diff lo muestra como `REJECTED`.

---

## 6. API keys: de la cátedra, personal, y cuándo vencen

### 6.1 Dónde vive cada key

| | **Key de la cátedra** | **Key personal del alumno** |
|---|---|---|
| Dónde vive | `ModelPolicy.apiKey` + `apiKeyExpiresAt` (dentro del `Course`, en `courses.json`) | `StudentModelProfile.personalKey` (en `config/student-model-profiles.json` del alumno) |
| Quién la ve | todos los alumnos inscriptos de esa cátedra | sólo su dueño |
| Quién la emite | la institución / Kitoru-Server | el alumno (trae la suya de OpenAI, Anthropic, etc.) |
| Vencimiento | `expiresAt` = fin de la cursada (obligatorio en el flujo institucional) | el que el alumno quiera (o el del proveedor) |
| ¿Llega al docente? | **no** (ver §6.4) | **nunca** |

`StudentModelProfile.personalKey` es un `ApiCredential` completo, así que
admite expiración propia (un alumno con una key de prueba que vence en una
semana).

### 6.2 `ApiCredential` y `expiresAt`

La expiración se modela con **`expiresAt: Long` en epoch millis, `0` = no
vence**. Es el mismo criterio que ya usa el repo para `createdAt`,
`joinedAt`, `uploadedAt`: un `Long`, sin depender de `java.time` ni de
`kotlinx-datetime` (que no está en las dependencias).

```kotlin
fun status(now: Long): CredentialStatus = when {
    key.isBlank()            -> CredentialStatus.MISSING
    expiresAt <= 0L          -> CredentialStatus.VALID
    now >= expiresAt         -> CredentialStatus.EXPIRED
    expiresAt - now <= EXPIRING_SOON_MS -> CredentialStatus.EXPIRING_SOON
    else                     -> CredentialStatus.VALID
}
```

`EXPIRING_SOON_MS` = 72 h: la UI avisa "vence en 2 días" para que el alumno
no se quede sin poder chatear el viernes a la noche.

### 6.3 Qué key se usa (y por qué)

La regla del campo `API_KEY` decide:

| Regla | Key efectiva |
|---|---|
| `Locked` | **siempre** la de la cátedra (el alumno no puede traer la suya) |
| `Choice(["COURSE"])` | la de la cátedra |
| `Choice(["PERSONAL"])` | la personal (si no tiene, queda bloqueado) |
| `Choice(["COURSE","PERSONAL"])` | la personal si existe y sirve; si no, la de la cátedra |
| `Free` (o sin regla) | la personal si existe y **no está vencida**; si no, la de la cátedra |

Detalle fino: si la key **personal está vencida** pero la de la cátedra
sigue viva, se cae a la de la cátedra (mejor UX que dejar al alumno sin
nada). Si la key personal venció y la de la cátedra también, el alumno
ve el aviso de vencimiento.

### 6.4 Qué ve el alumno si su key venció

`EffectiveModelConfig` lleva tres campos para eso:

```kotlin
val blocked: Boolean            // no se puede chatear
val problem: String             // mensaje en español para la UI
val canUseLocalFallback: Boolean  // puede salvarse con su IA local
```

Mensajes (textos finales, en español, fail-fast como manda el CONTEXTO):

- Key de la cátedra vencida:
  > **"La clave de la cátedra venció el 30/06/2026. Pedile al profesor una nueva."**
- Key personal vencida:
  > **"Tu clave de API venció el 12/05/2026. Pedí una nueva para seguir usando el servidor de la cátedra."**
- Sin key:
  > **"Falta la clave de API para usar el servidor de la cátedra."**
- Con red de escape (si `allowLocalModels` y el modo no está bloqueado):
  > **"Podés seguir usando tu IA local mientras tanto."**

En la UI: el input del chat queda deshabilitado con el motivo, y aparecen
dos acciones: *"Copiar el pedido de clave"* (arma el texto para el profesor)
y *"Usar mi IA local"*.

### 6.5 La key nunca se persiste en los hilos ni se muestra

- `EffectiveModelConfig.apiKey` es **`@Transient`**: no se serializa. Vive
  sólo en memoria mientras se está chateando. Consecuencia directa: el
  `threads.json` que puede leer el docente **no puede contener la key**.
- `EffectiveModelConfig.redacted()` devuelve una copia sin key, para
  persistir o mostrar.
- En el diff y en los reportes del docente la key aparece **enmascarada**
  (`••••cdef`) y sólo como *estado* (`Válida`, `Vence pronto`, `Vencida`) y
  *origen* (`Clave de la cátedra`, `Clave personal`).
- **Fase 2 (fuera de este documento):** hoy la key de la cátedra vive en
  claro en el `courses.json` del alumno. Cuando exista el Kitoru-Server, la
  key debería bajar del servidor al alumno y guardarse en el *keychain* del
  sistema (`SecretStore`: Windows Credential Manager / Keychain / Keystore de
  Android), con la política guardando sólo un `secretRef`. El modelo de
  dominio ya está preparado: `apiKey` + `apiKeyExpiresAt` + `ApiCredential`.

---

## 7. Presets: los modelos preconfigurados de la cátedra

### 7.1 Dónde viven

**Dentro de `ModelPolicy`** (`val presets: List<ModelPreset>`), no en una
entidad aparte. Razones:

1. Un preset **no significa nada fuera de la cátedra**: su `modelName`
   sólo existe si el servidor de esa cátedra lo tiene. Un `PresetRepository`
   global obligaría a resolver el contexto en cada uso.
2. El preset viaja con la `Course`: el alumno que se inscribe recibe la
   cátedra completa (política + presets) de un tirón.
3. Es **información de la cátedra**: el docente tiene derecho a ver qué
   preset usa cada alumno (es parte del diff de §8).

Lo que **no** vive en la política es la **elección** del alumno: eso es
`StudentModelProfile.presetId` (el lado del alumno).

### 7.2 El tipo

```kotlin
@Serializable
data class ModelPreset(
    val id: String,                    // id estable
    val label: String,                 // "Rápido", "Razonador", "Económico"
    val description: String = "",
    val tag: String = "",              // "rápido" | "razonador" | "barato" (badge de la UI)
    val mode: ModelMode = ModelMode.REMOTE,
    val remoteBaseUrl: String = "",    // vacío = la de la cátedra
    val modelName: String = "",        // vacío = el de la cátedra
    val temperature: Double? = null,   // null = la de la cátedra
    val maxTokens: Int? = null,        // null = la de la cátedra
    val thinking: String = "",         // vacío = la de la cátedra
    val power: String = "",            // vacío = la de la cátedra
)
```

Los campos **anulables/vacíos significan "hereda de la cátedra"**: el preset
define *lo que cambia* y deja el resto en manos de la base.

### 7.3 Semántica

- El alumno elige un preset ⇒ sus deltas expluestos pisan al preset, y el
  preset pisa a la base. Es decir: `pedido = deltaAlumno ?: preset ?: base`.
- Un preset **no esquiva las reglas**: si el preset "Razonador" trae
  `temperature = 0.4` y la cátedra tiene `Ranged(0.0, 0.8)`, se aplica 0.4.
  Si el preset trae algo fuera de rango (0.9), se recorta a 0.8 y el diff
  lo marca como `CLAMPED`.
- Elegir un preset en la UI **limpia los deltas del alumno** en los campos
  que el preset define (si no, el ajuste manual previo sobrevive y el
  alumno cree que el preset no hizo nada). El botón dice
  *"Aplicar preset"* y es explícito.
- `defaultPresetId` es el preset con el que arranca el alumno que nunca
  tocó nada. **No lo aplica el resolver**: al inscribirse, el perfil del
  alumno se crea con `presetId = defaultPresetId` (si el alumno después
  elige otro, cambia). Así la elección queda registrada en el perfil —que es
  del alumno— y el diff la muestra honestamente como "preset Razonador",
  no como un ajuste de la cátedra.

---

## 8. El diff: qué cambió el alumno respecto de la base

### 8.1 El cálculo

El diff **no se calcula comparando dos objetos**: se calcula **durante** la
resolución. Cada vez que el resolver aplica una regla a un campo, deja una
`ConfigChange` con los tres valores (base / pedido / efectivo) y el estado.
Esto garantiza que el diff que ve el docente **es** la configuración que se
usó, sin posibilidad de que se desincronicen.

Los campos que se trackean:

`MODE`, `BASE_URL`, `MODEL_NAME`, `API_KEY` (sólo estado, sin material),
`TEMPERATURE`, `MAX_TOKENS`, `TOP_K`, `SYSTEM_PROMPT`, `TOOLS_ENABLED`,
`THINKING`, `POWER`, `LOCAL_HF_REPO`, `LOCAL_HF_FILE`, `LOCAL_PORT`,
`CONTEXT_SIZE`, `GPU_LAYERS`.

### 8.2 Los estados

| Estado | Significado | Ejemplo |
|---|---|---|
| `ACCEPTED` | el alumno lo cambió y se aplicó tal cual | puso temperatura 0.5 (permitido) |
| `CLAMPED` | se aplicó pero recortado | puso 0.95 con rango 0.0–0.8 → quedó 0.8 |
| `REJECTED` | se descartó | la key está bloqueada y el alumno puso la suya |
| `UNCHANGED` | no hubo cambio | no lo tocó |

### 8.3 Cómo se muestra

**En el chat** (para el alumno): en la pantalla de configuración, al lado de
cada control, un cartel con el motivo cuando el ajuste fue recortado o
descartado. Ejemplos:

> *"Ajustado a 0.80 — la cátedra limita la temperatura a 0.00 – 0.80."*
> *"Ignorado — la cátedra fijó las instrucciones del sistema."*

**En la vista del docente** (la que le corresponde): una pantalla
*"Configuración del modelo"* con una tabla por alumno:

```
Alumno        | Modo   | Modelo            | Cambios           | Key
Juan Pérez    | Remoto | qwen3-32b         | 3 (1 ajustado)    | Cátedra · Válida
Ana Gómez     | Local  | Phi-4-mini        | 1                 | —
(luis)        | Remoto | qwen3-8b          | 5 (2 ignorados)   | Personal · Vencida
```

Al abrir un alumno, el diff campo a campo:

```
Campo                 | Base (cátedra) | Pedido (alumno) | Efectivo | Estado
Temperatura           | 0.20           | 0.95            | 0.80     | Ajustado a la cátedra
Límite de tokens      | 1024           | 1024            | 1024     | Sin cambios
Fragmentos RAG        | 5              | 10              | 8        | Ajustado a la cátedra
Clave de API          | —              | sk-propia-…9f2a | —        | Ignorado (bloqueado)
Modelo                | qwen3-32b      | qwen3-8b        | qwen3-8b | Aplicado (preset "Rápido")
```

Y el diff **histórico**: cada `ChatThread` guarda la `EffectiveModelConfig`
con su lista de `changes`, así que el docente puede abrir un hilo de hace
un mes y ver **con qué configuración** se generó esa respuesta (y qué
había cambiado el alumno en ese momento).

### 8.4 Límites de la visibilidad (regla 6 del CONTEXTO)

El diff es información **de la cátedra** (la configuración), no contenido
del alumno. Aun así:

- Si la inscripción tiene `ChatVisibility.NONE`, el alumno **no aparece**
  en la tabla de configuración (no se reporta ni el modo).
- Con `METRICS_ONLY` aparecen el modo, el modelo y los estados de los
  cambios, pero no los valores exactos de los campos que el alumno ajustó
  (p. ej. no se muestra su prompt personal).
- Con `FULL` se muestra todo el diff.
- **Nunca** se muestra material de la key, ni el prompt personal completo
  (se muestra como "agregó instrucciones propias", con el texto recortado a
  120 caracteres).

---

## 9. Impacto en el resto del código

### 9.1 `ChatThread`

```kotlin
// antes
val effectiveModel: ModelPolicy = ModelPolicy()
// después
val effectiveModel: EffectiveModelConfig = EffectiveModelConfig()
```

`EffectiveModelConfig` es serializable y **no tiene secretos** (la key es
`@Transient`), así que es segura de persistir y de mostrar al docente.
`ChatThread.modelLabel` pasa a salir de `effectiveModel.modelLabel`.

### 9.2 `:llm-api` — el puente hacia el gateway

`:core` no puede importar `:llm-api` (la dependencia va al revés:
`llm-api → core`), así que los adaptadores viven en `:llm-api`:

```kotlin
// llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt  (o un archivo nuevo)

fun EffectiveModelConfig.toGatewayConfig(chatTimeoutMs: Long = 0L): GatewayConfig =
    GatewayConfig(
        baseUrl = remoteBaseUrl,
        apiKey = apiKey,
        model = modelName,
        temperature = temperature,
        maxTokens = maxTokens,
        chatTimeoutMs = chatTimeoutMs,
        thinking = thinking,
        power = power,
    )

fun EffectiveModelConfig.toLocalModelSettings(): LocalModelSettings =
    LocalModelSettings(
        hfRepo = local.hfRepo,
        hfFile = local.hfFile,
        llamaServerPath = local.llamaServerPath,
        localPort = local.localPort,
        contextSize = local.contextSize,
        gpuLayers = local.gpuLayers,
        modelsDir = local.modelsDir,
    )
```

### 9.3 Persistencia

| Dato | Dónde | Repositorio |
|---|---|---|
| `Course` (con `modelPolicy`, presets y key de la cátedra) | `config/courses.json` | `CourseRepository` (existente) |
| `StudentModelProfile` (deltas del alumno, modelo local, key personal) | `config/student-model-profiles.json` | **nuevo** `StudentProfileRepository` |
| `ChatThread` (con la config efectiva y su diff) | `config/threads.json` | `ThreadRepository` (existente) |

```kotlin
interface StudentProfileRepository {
    suspend fun listForUser(userId: String): List<StudentModelProfile>
    suspend fun listForCourse(courseId: String): List<StudentModelProfile>
    suspend fun get(userId: String, courseId: String): StudentModelProfile?
    suspend fun save(profile: StudentModelProfile)
}
```

### 9.4 Migración de los datos existentes

`courses.json` v1 → v2:

- `modelPolicy.fieldRules` → `{}` (los `lockedFields` siguen mandando).
- `modelPolicy.apiKeyExpiresAt` → `0` (sin vencimiento: el flujo institucional
  lo va a setear al Soon).
- `modelPolicy.presets` → `[]`.
- `modelPolicy.defaultPresetId` → `null`.

No hace falta script: los campos nuevos tienen default y `KitoruJson` tiene
`ignoreUnknownKeys = true`. Los `lockedFields` viejo se leen tal cual.

---

## 10. Código Kotlin final

### 10.1 `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt` (completo)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kitoru.platform.nowMillis
import kotlin.math.roundToInt

/** De dónde corre el modelo que el alumno está usando. */
@Serializable
enum class ModelMode(val label: String) {
    /** Modelo alojado por la institución (Kitoru-Server) con API key. */
    REMOTE("Servidor de la cátedra"),

    /** Modelo corriendo en el propio dispositivo del alumno (llama.cpp). */
    LOCAL("IA local del alumno"),
}

/**
 * Campos de la configuración del modelo que la cátedra puede acotar.
 *
 * Antes este enum sólo significaba "bloqueado"; ahora es el **vocabulario**
 * de los campos acotables, y lo que se puede hacer con cada uno lo dice
 * [FieldConstraint]. Se conserva el nombre `LockedField` (y por lo tanto el
 * formato de los JSON ya guardados) y se agrega el alias [ConfigField] para
 * los códigos nuevos, donde "locked" engaña.
 */
@Serializable
enum class LockedField(val label: String) {
    /** De dónde corre el modelo: servidor de la cátedra o IA local. */
    MODE("Modo de ejecución"),

    /** Clave con la que se autentica contra el servidor. */
    API_KEY("Clave de API"),

    /** URL base del endpoint (compatible con OpenAI). */
    BASE_URL("Dirección del servidor"),

    /** Nombre del modelo en el servidor. */
    MODEL_NAME("Nombre del modelo"),

    /** Repo de Hugging Face del GGUF local. */
    LOCAL_HF_REPO("Repo del modelo local"),

    /** Archivo GGUF local dentro del repo. */
    LOCAL_HF_FILE("Archivo del modelo local"),

    /** Carpeta donde se guardan los GGUF descargados. */
    LOCAL_MODELS_DIR("Carpeta de modelos"),

    /** Ruta del binario `llama-server` (vacío = autodetectar en el PATH). */
    LLAMA_SERVER_PATH("Ruta de llama-server"),

    /** Puerto del `llama-server` local. */
    LOCAL_PORT("Puerto local"),

    /** Ventana de contexto del modelo local. */
    CONTEXT_SIZE("Tamaño del contexto"),

    /** Capas del modelo local que van a la GPU (0 = sólo CPU). */
    GPU_LAYERS("Capas en GPU"),

    /** Instrucciones del sistema que se le mandan al modelo. */
    SYSTEM_PROMPT("Instrucciones del sistema"),

    /** Temperatura de muestreo. */
    TEMPERATURE("Temperatura"),

    /** Tope de tokens por respuesta. */
    MAX_TOKENS("Límite de tokens"),

    /** Cuántos fragmentos del corpus recupera el RAG. */
    TOP_K("Fragmentos recuperados"),

    /** Si el modelo puede usar herramientas (archivos, hora, etc.). */
    TOOLS_ENABLED("Herramientas"),

    /** Nivel de razonamiento pedido ("off" | "low" | "medium" | "high"). */
    THINKING("Nivel de razonamiento"),

    /** Potencia de respuesta ("low" | "medium" | "high"). */
    POWER("Potencia de respuesta"),
    ;

    /** ¿Es un campo numérico (admiten [FieldConstraint.Ranged])? */
    val isNumeric: Boolean get() = this in NUMERIC

    /** ¿Es un secreto (nunca se muestra en crudo)? */
    val isSecret: Boolean get() = this == API_KEY

    companion object {
        /** Campos numéricos. */
        val NUMERIC: Set<LockedField> = setOf(
            TEMPERATURE, MAX_TOKENS, TOP_K, LOCAL_PORT, CONTEXT_SIZE, GPU_LAYERS,
        )

        /** Campos booleanos. */
        val BOOLEAN: Set<LockedField> = setOf(TOOLS_ENABLED)

        /** Niveles de razonamiento válidos. */
        val THINKING_LEVELS: List<String> = listOf("off", "low", "medium", "high")

        /** Niveles de potencia válidos. */
        val POWER_LEVELS: List<String> = listOf("low", "medium", "high")

        /** Contexto mínimo razonable para un modelo local. */
        const val MIN_CONTEXT: Int = 128

        /** Contexto máximo (llama.cpp puede más, pero acotamos). */
        const val MAX_CONTEXT: Int = 40_960

        /** Busca por nombre, tolerando mayúsculas (migración de JSON). */
        fun fromName(name: String): LockedField? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Alias legible: `ConfigField` es el nombre conceptual del enum. */
typealias ConfigField = LockedField

/**
 * Cuánto puede mover el alumno un campo de la configuración.
 *
 * Reemplaza el booleano "bloqueado / no bloqueado" por cuatro estados:
 *
 * | Estado    | Qué puede hacer el alumno        | Cómo se aplica               |
 * |-----------|-----------------------------------|------------------------------|
 * | [Free]   | cambiar el valor como quiera      | gana el valor del alumno     |
 * | [Ranged] | moverlo dentro de un intervalo    | se recorta al borde          |
 * | [Choice] | elegir uno de los valores         | si no está, gana el de base  |
 * | [Locked] | nada                             | gana el valor de la cátedra |
 *
 * El **valor por defecto** de cada campo es el valor que tiene la cátedra en
 * [ModelPolicy] (la "base"). "Libre con base" y "valor por defecto" son el
 * mismo mecanismo; la diferencia es de dónde sale el valor inicial y de que
 * la UI lo ofrezca como botón "volver al de la cátedra".
 */
@Serializable
sealed interface FieldConstraint {

    /** Libre: el alumno puede cambiar el valor por cualquiera. */
    @Serializable
    data object Free : FieldConstraint

    /** Bloqueado: el valor de la cátedra es el único válido. */
    @Serializable
    data object Locked : FieldConstraint

    /**
     * Rango permitido: el alumno puede mover el valor entre [min] y [max]
     * (ambos inclusive). Un valor fuera se recorta al borde y el cambio se
     * marca como "ajustado" en el diff.
     */
    @Serializable
    data class Ranged(val min: Double, val max: Double) : FieldConstraint

    /**
     * Elección entre valores permitidos. El **primer valor de [values] es el
     * valor por defecto** de la cátedra; los demás son las alternativas que
     * se le ofrecen al alumno (ej. `["alta", "baja"]`).
     */
    @Serializable
    data class Choice(val values: List<String>) : FieldConstraint
}

/**
 * Un modelo preconfigurado que la cátedra ofrece a sus alumnos.
 *
 * "Rápido", "Razonador", "Económico": son atajos a una combinación de
 * modelo + parámetros. Los campos vacíos o nulos significan **"hereda de la
 * cátedra"**, así el preset define sólo lo que cambia.
 *
 * Vive en [ModelPolicy] (es parte de la cátedra y viaja con ella); la
 * *elección* del alumno vive en [StudentModelProfile.presetId].
 */
@Serializable
data class ModelPreset(
    /** Id estable del preset. */
    val id: String,

    /** Etiqueta visible ("Rápido", "Razonador", "Económico"). */
    val label: String,

    /** Explicación corta de cuándo conviene. */
    val description: String = "",

    /** Badge de categoría para la UI ("rápido", "razonador", "barato"). */
    val tag: String = "",

    /** Modo al que apunta el preset. */
    val mode: ModelMode = ModelMode.REMOTE,

    /** URL base propia del preset (vacío = la de la cátedra). */
    val remoteBaseUrl: String = "",

    /** Modelo del preset (vacío = el de la cátedra). */
    val modelName: String = "",

    /** Temperatura del preset (null = la de la cátedra). */
    val temperature: Double? = null,

    /** Tope de tokens del preset (null = el de la cátedra). */
    val maxTokens: Int? = null,

    /** Nivel de razonamiento del preset (vacío = el de la cátedra). */
    val thinking: String = "",

    /** Potencia del preset (vacío = la de la cátedra). */
    val power: String = "",
) {
    /** ¿El preset trae algo propio o es sólo un alias de la base? */
    val isCustom: Boolean
        get() = remoteBaseUrl.isNotBlank() || modelName.isNotBlank() ||
            temperature != null || maxTokens != null || thinking.isNotBlank() || power.isNotBlank()
}

/** De dónde sale la clave que se está usando. */
@Serializable
enum class KeySource(val label: String) {
    /** La credencial compartida que emite la institución. */
    COURSE("Clave de la cátedra"),

    /** La clave personal que trajo el alumno. */
    PERSONAL("Clave personal"),

    /** No hace falta clave (IA local). */
    NONE("Sin clave"),
}

/** Estado de una credencial frente al reloj. */
@Serializable
enum class CredentialStatus(val label: String) {
    /** No hay clave cargada. */
    MISSING("Sin clave"),

    /** La clave sirve. */
    VALID("Válida"),

    /** Vence dentro de [ApiCredential.EXPIRING_SOON_MS]. */
    EXPIRING_SOON("Vence pronto"),

    /** Ya venció: no se puede usar. */
    EXPIRED("Vencida"),
}

/**
 * Una credencial de acceso al servidor de la cátedra.
 *
 * La usan las dos flavors de key:
 *  - la **de la cátedra** (compartida, la emite la institución y vence al
 *    terminar la cursada) → vive en [ModelPolicy.apiKey] / [ModelPolicy.apiKeyExpiresAt];
 *  - la **personal del alumno** (la suya) → vive en
 *    [StudentModelProfile.personalKey].
 *
 * La expiración es un `Long` en epoch millis (`0` = no vence), el mismo
 * criterio que el resto del dominio (`createdAt`, `joinedAt`…).
 */
@Serializable
data class ApiCredential(
    /** La clave en crudo. Nunca se muestra ni se persiste en los hilos. */
    val key: String = "",

    /** Epoch millis de emisión (0 = desconocida). */
    val issuedAt: Long = 0L,

    /** Epoch millis de vencimiento; 0 = no vence. */
    val expiresAt: Long = 0L,

    /** De quién es la clave. */
    val source: KeySource = KeySource.COURSE,

    /** Etiqueta para la UI ("Cursada 2026-1", "mi key de OpenAI"). */
    val label: String = "",
) {
    /** Estado de la credencial con el reloj [now]. */
    fun status(now: Long): CredentialStatus = when {
        key.isBlank() -> CredentialStatus.MISSING
        expiresAt <= 0L -> CredentialStatus.VALID
        now >= expiresAt -> CredentialStatus.EXPIRED
        expiresAt - now <= EXPIRING_SOON_MS -> CredentialStatus.EXPIRING_SOON
        else -> CredentialStatus.VALID
    }

    /** ¿Ya venció? */
    fun isExpired(now: Long): Boolean = status(now) == CredentialStatus.EXPIRED

    /** Milisegundos que le quedan (`Long.MAX_VALUE` si no vence). */
    fun remainingMs(now: Long): Long =
        if (expiresAt <= 0L) Long.MAX_VALUE else (expiresAt - now).coerceAtLeast(0L)

    /**
     * Versión enmascarada para mostrar o persistir: nunca el valor completo.
     * `"sk-1234567890abcdef"` → `"••••cdef"`.
     */
    fun masked(): String = when {
        key.isBlank() -> ""
        key.length <= 8 -> "••••"
        else -> "••••" + key.takeLast(4)
    }

    companion object {
        /** A partir de acá la credencial se marca como "vence pronto" (72 h). */
        const val EXPIRING_SOON_MS: Long = 72L * 60L * 60L * 1000L
    }
}

/**
 * Configuración base del modelo que impone la cátedra + cuánto puede tocar
 * el alumno cada campo.
 *
 * Es el contrato entre el docente y sus alumnos:
 *  - la **base**: los valores con los que la cátedra quiere que se trabaje
 *    (endpoint, modelo, temperatura, prompt, top-k…);
 *  - las **reglas** ([fieldRules] + [lockedFields]): cuánto puede mover el
 *    alumno cada campo;
 *  - los **presets**: los modelos preconfigurados que la cátedra ofrece;
 *  - la **key de la cátedra**: la credencial compartida que emite la
 *    institución y que vence al terminar la cursada.
 *
 * Lo que eligió el alumno **no** vive acá: vive en [StudentModelProfile],
 * que es del alumno y nunca se manda a la cátedra.
 */
@Serializable
data class ModelPolicy(
    // ------------------------------------------------------------------
    // General
    // ------------------------------------------------------------------

    /**
     * Modo por defecto de la cátedra: [ModelMode.REMOTE] (el servidor de la
     * institución) o [ModelMode.LOCAL] (el modelo del alumno).
     *
     * Es la **base**, no una imposición: el alumno puede cambiar a LOCAL si
     * la cátedra lo permite ([Course.allowLocalModels]) y la regla de
     * [LockedField.MODE] no lo bloquea.
     */
    val mode: ModelMode = ModelMode.REMOTE,

    // ------------------------------------------------------------------
    // Servidor remoto (Kitoru-Server / API compatible con OpenAI)
    // ------------------------------------------------------------------

    /** URL base del endpoint (ej. "https://ia.un.edu.ar/kitoru/v1"). */
    val remoteBaseUrl: String = "",

    /**
     * Clave de API **de la cátedra**: la emite la institución y la
     * comparten todos los alumnos de la materia.
     *
     * Vive acá (y no en el perfil del alumno) porque es de la cátedra. Ojo:
     * en el modelo actual de Kitoru el archivo de la cátedra es legible por
     * todos los alumnos inscriptos; cuando exista el Kitoru-Server, la key
     * debería bajar del servidor y guardarse en el keychain del sistema
     * (ver §6.5 del diseño).
     */
    val apiKey: String = "",

    /**
     * Vencimiento de la key de la cátedra (epoch millis; 0 = no vence).
     *
     * En el flujo institucional se setea al final de la cursada.
     */
    val apiKeyExpiresAt: Long = 0L,

    /** Etiqueta de la key ("Cursada 2026-1", "Key de la cátedra"). */
    val apiKeyLabel: String = "",

    /** Nombre del modelo en el servidor (ej. "qwen3-32b"). */
    val modelName: String = "",

    // ------------------------------------------------------------------
    // Modelo local (llama.cpp en el dispositivo del alumno)
    //
    // Estos son los valores *sugeridos* por la cátedra (ej. "Phi-4-mini
    // Q4_K_M"). El GGUF que realmente usa el alumno vive en
    // [LocalModelProfile], del lado del alumno.
    // ------------------------------------------------------------------

    /** Repo de Hugging Face del GGUF sugerido. */
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",

    /** Archivo GGUF sugerido dentro del repo. */
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",

    /** Carpeta sugerida para los modelos descargados. */
    val modelsDir: String = "models",

    /** Ruta sugerida del binario `llama-server` (vacío = autodetectar). */
    val llamaServerPath: String = "",

    /** Puerto sugerido para el `llama-server` local. */
    val localPort: Int = 8081,

    /** Contexto sugerido para el modelo local. */
    val contextSize: Int = 8192,

    /** Capas en GPU sugeridas (0 = sólo CPU). */
    val gpuLayers: Int = 0,

    // ------------------------------------------------------------------
    // Generación
    // ------------------------------------------------------------------

    /** Temperatura de muestreo de la cátedra. */
    val temperature: Double = 0.2,

    /** Tope de tokens por respuesta de la cátedra. */
    val maxTokens: Int = 1024,

    /** Nivel de razonamiento pedido ("off" | "low" | "medium" | "high"). */
    val thinking: String = "off",

    /** Potencia de respuesta ("low" | "medium" | "high"). */
    val power: String = "medium",

    // ------------------------------------------------------------------
    // RAG
    // ------------------------------------------------------------------

    /** Cuántos fragmentos del corpus recupera el RAG. */
    val topK: Int = 5,

    // ------------------------------------------------------------------
    // Comportamiento del alumno
    // ------------------------------------------------------------------

    /**
     * Si el alumno puede ajustar la configuración.
     *
     * `false` = usa **exactamente** lo de la cátedra. La única excepción es
     * el modo (REMOTE ↔ LOCAL), que es la válvula de escape para cuando la
     * key de la cátedra venció.
     */
    val allowStudentOverride: Boolean = true,

    /**
     * Campos totalmente bloqueados.
     *
     * Se conserva por compatibilidad con los JSON ya guardados y porque es
     * el atajo natural para "esto no se toca". El modelo fino es
     * [fieldRules]; las dos cosas se combinan en [effectiveRules].
     */
    val lockedFields: Set<LockedField> = setOf(LockedField.API_KEY),

    /**
     * Regla fina por campo: bloqueado / rango / elección / libre.
     *
     * Si un campo aparece acá, su regla pisa la de [lockedFields].
     */
    val fieldRules: Map<LockedField, FieldConstraint> = emptyMap(),

    /** Si el modelo puede usar herramientas. */
    val toolsEnabled: Boolean = true,

    /** Instrucciones del sistema de la cátedra. */
    val systemPrompt: String = "",

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    /** Modelos preconfigurados que la cátedra ofrece (rápido, razonador, barato). */
    val presets: List<ModelPreset> = emptyList(),

    /** Id del preset con el que arranca el alumno (null = la base). */
    val defaultPresetId: String? = null,
) {
    /**
     * Reglas efectivas: [lockedFields] (bloqueados) + [fieldRules] (pisan).
     */
    val effectiveRules: Map<LockedField, FieldConstraint>
        get() {
            val out = LinkedHashMap<LockedField, FieldConstraint>()
            lockedFields.forEach { out[it] = FieldConstraint.Locked }
            out.putAll(fieldRules)
            return out
        }

    /** Campos que quedan totalmente bloqueados (regla [FieldConstraint.Locked]). */
    val lockedFieldSet: Set<LockedField>
        get() = effectiveRules.filterValues { it is FieldConstraint.Locked }.keys

    /** Regla vigente de un campo ([FieldConstraint.Free] si no hay ninguna). */
    fun ruleFor(field: LockedField): FieldConstraint =
        effectiveRules[field] ?: FieldConstraint.Free

    /**
     * Devuelve la política con la regla del campo cambiada, manteniendo
     * [lockedFields] y [fieldRules] coherentes (una sola fuente de verdad
     * por campo).
     */
    fun withRule(field: LockedField, rule: FieldConstraint): ModelPolicy = when (rule) {
        is FieldConstraint.Locked -> copy(
            lockedFields = lockedFields + field,
            fieldRules = fieldRules - field,
        )
        else -> copy(
            lockedFields = lockedFields - field,
            fieldRules = fieldRules + (field to rule),
        )
    }

    /** Etiqueta corta del modelo de la cátedra, para la UI. */
    val modelLabel: String
        get() = when (mode) {
            ModelMode.REMOTE -> modelName.ifBlank { "servidor de la cátedra" }
            ModelMode.LOCAL -> hfFile.ifBlank { hfRepo }.substringBeforeLast('.')
        }

    /**
     * Problemas de la configuración de la cátedra (vacío = válida).
     *
     * Fail-fast y en español, como manda el CONTEXTO.
     */
    fun validate(now: Long = nowMillis()): List<String> {
        val errors = mutableListOf<String>()

        // --- Generación ---
        if (maxTokens <= 0) {
            errors += "El límite de tokens debe ser mayor que 0 (actual: $maxTokens)."
        }
        if (temperature < 0.0 || temperature > 2.0) {
            errors += "La temperatura debe estar entre 0 y 2 (actual: $temperature)."
        }
        if (topK <= 0) {
            errors += "El número de fragmentos recuperados debe ser mayor que 0 (actual: $topK)."
        }
        if (thinking !in LockedField.THINKING_LEVELS) {
            errors += "El nivel de razonamiento debe ser uno de: " +
                "${LockedField.THINKING_LEVELS.joinToString(", ")} (actual: $thinking)."
        }
        if (power !in LockedField.POWER_LEVELS) {
            errors += "La potencia debe ser una de: " +
                "${LockedField.POWER_LEVELS.joinToString(", ")} (actual: $power)."
        }

        // --- Local ---
        if (localPort !in 1..65535) {
            errors += "El puerto local debe estar entre 1 y 65535 (actual: $localPort)."
        }
        if (contextSize < LockedField.MIN_CONTEXT || contextSize > LockedField.MAX_CONTEXT) {
            errors += "El tamaño del contexto debe estar entre ${LockedField.MIN_CONTEXT} y " +
                "${LockedField.MAX_CONTEXT} (actual: $contextSize)."
        }
        if (gpuLayers < 0) {
            errors += "Las capas en GPU no pueden ser negativas (actual: $gpuLayers)."
        }
        if (mode == ModelMode.LOCAL && (hfRepo.isBlank() || hfFile.isBlank())) {
            errors += "Falta indicar el modelo local (repo y archivo GGUF)."
        }

        // --- Remoto ---
        if (mode == ModelMode.REMOTE) {
            if (remoteBaseUrl.isBlank()) {
                errors += "Falta la dirección del servidor de la cátedra."
            } else if (!remoteBaseUrl.trim().startsWith("http://") &&
                !remoteBaseUrl.trim().startsWith("https://")
            ) {
                errors += "La dirección del servidor debe empezar con http:// o https://."
            } else if (modelName.isBlank()) {
                errors += "Falta el nombre del modelo en el servidor."
            }
        }
        if (apiKey.isBlank() && mode == ModelMode.REMOTE && !isLoopback(remoteBaseUrl)) {
            errors += "Falta la clave de API de la cátedra (si el servidor es local no hace falta)."
        }
        if (apiKeyExpiresAt in 1..now) {
            errors += "La clave de la cátedra venció el ${formatDate(apiKeyExpiresAt)}: " +
                "pedí una nueva antes de publicar la cátedra."
        }

        // --- Reglas ---
        effectiveRules.forEach { (field, rule) ->
            when (rule) {
                is FieldConstraint.Ranged -> {
                    if (rule.min > rule.max) {
                        errors += "El rango de «${field.label}» está invertido " +
                            "(${rule.min} > ${rule.max})."
                    } else if (!field.isNumeric) {
                        errors += "«${field.label}» no es un campo numérico: no se le puede poner un rango."
                    }
                }
                is FieldConstraint.Choice -> {
                    if (rule.values.isEmpty()) {
                        errors += "«${field.label}» no tiene valores permitidos."
                    }
                }
                else -> Unit
            }
        }

        // --- Presets ---
        val ids = presets.map { it.id }
        if (ids.any { it.isBlank() }) errors += "Hay un preset sin id: renómbralo o borralo."
        if (ids.distinct().size != ids.size) errors += "Hay presets con el mismo id: los id tienen que ser únicos."
        presets.forEach { preset ->
            val t = preset.temperature
            if (t != null && (t < 0.0 || t > 2.0)) {
                errors += "El preset «${preset.label}» tiene una temperatura fuera de rango ($t)."
            }
            if (preset.maxTokens != null && preset.maxTokens!! <= 0) {
                errors += "El preset «${preset.label}» tiene un límite de tokens inválido (${preset.maxTokens})."
            }
        }
        if (defaultPresetId != null && defaultPresetId !in ids) {
            errors += "El preset por defecto («$defaultPresetId») no existe en la lista de presets."
        }

        return errors
    }

    /**
     * Problemas de la **elección del alumno** frente a las reglas de la
     * cátedra (vacío = todo bien).
     *
     * A diferencia de [validate], acá no se juzga si la configuración es
     * "buena": se chequea que el alumno no haya pedido algo que la cátedra no
     * permite (o que se haya corregido a la fuerza).
     */
    fun validateFor(
        student: StudentModelProfile,
        allowLocalModels: Boolean = true,
        now: Long = nowMillis(),
    ): List<String> {
        val errors = mutableListOf<String>()
        val rules = effectiveRules

        if (student.mode == ModelMode.LOCAL && !allowLocalModels) {
            errors += "La cátedra no permite usar IA local en esta materia."
        }
        student.mode?.let { mode ->
            if (rules[LockedField.MODE] is FieldConstraint.Locked && mode != policyMode()) {
                errors += "La cátedra fijó el modo en «${policyMode().label}»: no se puede cambiar."
            }
        }

        student.temperature?.let { value ->
            val rule = rules[LockedField.TEMPERATURE]
            when (rule) {
                is FieldConstraint.Ranged ->
                    if (value < rule.min || value > rule.max) {
                        errors += "La temperatura que elegiste ($value) está fuera del rango permitido " +
                            "por la cátedra (${round2(rule.min)} – ${round2(rule.max)}): se usará " +
                            "${round2(value.coerceIn(rule.min, rule.max))}."
                    }
                is FieldConstraint.Locked ->
                    errors += "La cátedra fijó la temperatura en ${round2(temperature)}: no se puede cambiar."
                else -> Unit
            }
        }

        student.maxTokens?.let { value ->
            val rule = rules[LockedField.MAX_TOKENS]
            when (rule) {
                is FieldConstraint.Ranged ->
                    if (value < rule.min || value > rule.max) {
                        errors += "El límite de tokens que elegiste ($value) está fuera del rango " +
                            "permitido por la cátedra (${rule.min.toInt()} – ${rule.max.toInt()})."
                    }
                is FieldConstraint.Locked ->
                    errors += "La cátedra fijó el límite de tokens en $maxTokens: no se puede cambiar."
                else -> Unit
            }
        }

        student.topK?.let { value ->
            val rule = rules[LockedField.TOP_K]
            if (rule is FieldConstraint.Ranged && (value < rule.min || value > rule.max)) {
                errors += "Los fragmentos recuperados que elegiste ($value) están fuera del rango " +
                    "permitido por la cátedra (${rule.min.toInt()} – ${rule.max.toInt()})."
            }
        }

        if (student.personalKey.key.isNotBlank() && rules[LockedField.API_KEY] is FieldConstraint.Locked) {
            errors += "La cátedra usa su propia clave: la clave personal que cargaste se ignora."
        }
        if (student.personalKey.isExpired(now)) {
            errors += "Tu clave de API venció el ${formatDate(student.personalKey.expiresAt)}: " +
                "pedí una nueva o usá la clave de la cátedra."
        }

        return errors
    }

    /** Modo de la cátedra (helper para los mensajes). */
    private fun policyMode(): ModelMode = mode

    companion object {
        /** ¿La URL apunta a un servidor local (no necesita clave)? */
        fun isLoopback(url: String): Boolean {
            val u = url.trim().lowercase()
            return u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]")
        }

        /**
         * Redondea a 2 decimales, independiente del locale.
         */
        fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

        /**
         * Fecha en formato "dd/MM/yyyy" (algoritmo civil de Hinnant: puro y
         * portable, sin `java.time` ni `kotlinx-datetime`).
         */
        fun formatDate(millis: Long): String {
            val days = millis / 86_400_000L
            val z = days + 719_468L
            val era = (if (z >= 0) z else z - 146_096L) / 146_097L
            val doe = z - era * 146_097L
            val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
            val y = yoe + era * 400L
            val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
            val mp = (5L * doy + 2L) / 153L
            val d = doy - (153L * mp + 2L) / 5L + 1L
            val m = if (mp < 10L) mp + 3L else mp - 9L
            val year = if (m <= 2L) y + 1L else y
            return "${d.toInt()}/${m.toInt().toString().padStart(2, '0')}/${year}"
        }
    }
}
```

### 10.2 `core/src/commonMain/kotlin/kitoru/domain/EffectiveModelConfig.kt` (nuevo)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** De dónde salió el valor que el alumno pidió. */
@Serializable
enum class ChangeSource(val label: String) {
    /** Lo ajustó el alumno a mano. */
    STUDENT("Ajuste del alumno"),

    /** Vino de un preset de la cátedra. */
    PRESET("Preset de la cátedra"),

    /** Es el valor de la cátedra (no lo tocó nadie). */
    POLICY("Cátedra"),
}

/** Qué pasó con el cambio que pidió el alumno. */
@Serializable
enum class ChangeStatus(val label: String) {
    /** El alumno lo cambió y se aplicó tal cual. */
    ACCEPTED("Aplicado"),

    /** Se aplicó, pero recortado al rango permitido. */
    CLAMPED("Ajustado a la cátedra"),

    /** Se descartó: el campo está bloqueado o el valor no está permitido. */
    REJECTED("Ignorado (bloqueado)"),

    /** No hubo cambio. */
    UNCHANGED("Sin cambios"),
}

/**
 * Un cambio de configuración pedido por un alumno, campo a campo.
 *
 * Es lo que el docente ve: qué pidió, qué terminó usándose y por qué.
 * Nunca contiene material de la API key.
 */
@Serializable
data class ConfigChange(
    /** Campo afectado. */
    val field: LockedField,

    /** Etiqueta del campo (copia de [LockedField.label] para no depender del enum al mostrar). */
    val label: String = field.label,

    /** Valor de la cátedra (la base). */
    val baseValue: String = "",

    /** Valor pedido por el alumno. */
    val studentValue: String = "",

    /** Valor final después de aplicar las reglas. */
    val effectiveValue: String = "",

    /** Qué pasó con el cambio. */
    val status: ChangeStatus = ChangeStatus.UNCHANGED,

    /** Por qué (mensaje en español para la UI). */
    val reason: String = "",

    /** De dónde salió el valor pedido. */
    val source: ChangeSource = ChangeSource.STUDENT,
) {
    /** ¿Es un cambio real (no "sin cambios")? */
    val isChange: Boolean get() = status != ChangeStatus.UNCHANGED
}

/**
 * La configuración **efectiva**: lo que realmente se usó para chatear.
 *
 * Es el resultado de aplicar la política de la cátedra sobre la elección del
 * alumno ([ModelResolver.resolve]). Se persiste en cada hilo para que el
 * docente pueda ver con qué configuración se generó cada respuesta.
 *
 * **No tiene secretos**: la API key es `@Transient`, así que el
 * `threads.json` no puede contenerla nunca.
 */
@Serializable
data class EffectiveModelConfig(
    /** Modo efectivo. */
    val mode: ModelMode = ModelMode.REMOTE,

    /** URL base efectiva (vacía en modo local). */
    val remoteBaseUrl: String = "",

    /** Modelo efectivo (remoto: nombre en el servidor; local: archivo GGUF). */
    val modelName: String = "",

    /** Temperatura efectiva. */
    val temperature: Double = 0.2,

    /** Tope de tokens efectivo. */
    val maxTokens: Int = 1024,

    /** Fragmentos del RAG efectivos. */
    val topK: Int = 5,

    /** Instrucciones del sistema efectivas (cátedra + alumno). */
    val systemPrompt: String = "",

    /** ¿Herramientas? */
    val toolsEnabled: Boolean = true,

    /** Razonamiento efectivo. */
    val thinking: String = "off",

    /** Potencia efectiva. */
    val power: String = "medium",

    /** Perfil local efectivo (sólo se usa en modo LOCAL). */
    val local: LocalModelProfile = LocalModelProfile(),

    /** De dónde salió la key. */
    val keySource: KeySource = KeySource.NONE,

    /** Vencimiento de la key (0 = no vence). */
    val keyExpiresAt: Long = 0L,

    /** Estado de la key. */
    val keyStatus: CredentialStatus = CredentialStatus.MISSING,

    /** Preset usado (null = la base de la cátedra). */
    val presetId: String? = null,

    /** Qué cambió el alumno respecto de la base (diff campo a campo). */
    val changes: List<ConfigChange> = emptyList(),

    /** Si no se puede chatear (key vencida o falta). */
    val blocked: Boolean = false,

    /** Mensaje en español para la UI cuando está bloqueado. */
    val problem: String = "",

    /** Si puede salvarse con su IA local (cátedra que la permite). */
    val canUseLocalFallback: Boolean = false,

    /**
     * Clave con la que se habla al servidor.
     *
     * `@Transient`: **no se serializa**. Vive sólo en memoria mientras se
     * está chateando, para que ni el hilo ni el archivo de la cátedra
     * puedan contenerla.
     */
    @Transient val apiKey: String = "",
) {
    /** Etiqueta corta del modelo (para la lista de hilos). */
    val modelLabel: String
        get() = when (mode) {
            ModelMode.REMOTE -> modelName.ifBlank { "servidor de la cátedra" }
            ModelMode.LOCAL -> local.hfFile.ifBlank { local.hfRepo }.substringBeforeLast('.')
        }

    /** ¿Se puede chatear con esta configuración? */
    val canChat: Boolean get() = !blocked

    /** Copia sin la clave (para persistir o mostrar). */
    fun redacted(): EffectiveModelConfig = copy(apiKey = "")
}
```

### 10.3 `core/src/commonMain/kotlin/kitoru/domain/StudentModelProfile.kt` (nuevo)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * El modelo local **del alumno**: su GGUF, su puerto, su contexto.
 *
 * Vive en el perfil del alumno (`config/student-model-profiles.json`), nunca
 * en la política de la cátedra: el modelo que corre en la notebook del
 * alumno es un asunto suyo. La cátedra puede *acotar* algunos de estos
 * campos (contexto máximo, capas en GPU) con las reglas de [ModelPolicy],
 * pero no puede verlos ni fijarlos.
 */
@Serializable
data class LocalModelProfile(
    /** Repo de Hugging Face del GGUF. */
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",

    /** Archivo GGUF dentro del repo. */
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",

    /** Carpeta donde se guarda el modelo descargado. */
    val modelsDir: String = "models",

    /** Ruta del binario `llama-server` (vacío = autodetectar en el PATH). */
    val llamaServerPath: String = "",

    /** Puerto del `llama-server` local. */
    val localPort: Int = 8081,

    /** Ventana de contexto. */
    val contextSize: Int = 8192,

    /** Capas en GPU (0 = sólo CPU). */
    val gpuLayers: Int = 0,
) {
    /** Etiqueta corta del modelo local ("Phi-4-mini-instruct"). */
    val label: String get() = hfFile.ifBlank { hfRepo }.substringBeforeLast('.')
}

/**
 * Lo que el alumno eligió en una cátedra: sus **deltas** sobre la base.
 *
 * Regla de oro: acá **no** se copia la política de la cátedra. Cada campo
 * nullable es "el alumno no lo tocó" (`null`) o "el alumno lo puso en
 * este valor". Por eso:
 *  - el diff es la lista de deltas (gratis y exacto);
 *  - cuando la cátedra cambia su base, todos los alumnos se ven afectados
 *    sin tocar sus perfiles;
 *  - el perfil del alumno es suyo y no se manda a la cátedra.
 */
@Serializable
data class StudentModelProfile(
    /** Cátedra a la que pertenece este perfil. */
    val courseId: String = "",

    /** Alumno dueño del perfil. */
    val userId: String = "",

    /** Modo pedido por el alumno (null = el de la cátedra). */
    val mode: ModelMode? = null,

    /** Preset elegido (null = la base de la cátedra). */
    val presetId: String? = null,

    /** URL base pedida por el alumno (null = la de la cátedra o la del preset). */
    val remoteBaseUrl: String? = null,

    /** Modelo pedido (null = el de la cátedra o el del preset). */
    val modelName: String? = null,

    /** Temperatura pedida (null = no la tocó). */
    val temperature: Double? = null,

    /** Tope de tokens pedido (null = no lo tocó). */
    val maxTokens: Int? = null,

    /** Fragmentos del RAG pedidos (null = no los tocó). */
    val topK: Int? = null,

    /** Instrucciones del sistema del alumno (null = las de la cátedra). */
    val systemPrompt: String? = null,

    /** ¿Herramientas? (null = las de la cátedra). */
    val toolsEnabled: Boolean? = null,

    /** Razonamiento pedido (null = el de la cátedra). */
    val thinking: String? = null,

    /** Potencia pedida (null = la de la cátedra). */
    val power: String? = null,

    /** Credencial personal del alumno para esta cátedra (si se la trae). */
    val personalKey: ApiCredential = ApiCredential(),

    /** Su propio modelo local (GGUF, puerto, contexto…). */
    val local: LocalModelProfile = LocalModelProfile(),

    /** Última vez que el alumno usó el modelo (para la analítica). */
    val lastUsedAt: Long = 0L,
) {
    /** ¿El alumno tocó algo? (si no, usa la base tal cual). */
    val hasOverrides: Boolean
        get() = mode != null || presetId != null || remoteBaseUrl != null ||
            modelName != null || temperature != null || maxTokens != null ||
            topK != null || systemPrompt != null || toolsEnabled != null ||
            thinking != null || power != null
}
```

### 10.4 `core/src/commonMain/kotlin/kitoru/domain/ModelResolver.kt` (nuevo)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kitoru.platform.nowMillis

/**
 * Resolución de la configuración: **base de la cátedra + libertad del
 * alumno**.
 *
 * Todo es determinista: no toca la red y el reloj entra por parámetro (el
 * default es `nowMillis()`). Así se puede testear y el diff que ve el
 * docente es reproducible.
 *
 * Para cada campo se calculan tres valores:
 *  - **base** = el valor de la cátedra;
 *  - **pedido** = delta del alumno ?: valor del preset ?: base;
 *  - **efectivo** = aplicar la regla del campo.
 */
object ModelResolver {

    // ------------------------------------------------------------------
    // Resolución
    // ------------------------------------------------------------------

    /** Resuelve con la cátedra completa (uso normal de la app). */
    fun resolve(
        course: Course,
        student: StudentModelProfile,
        now: Long = nowMillis(),
    ): EffectiveModelConfig = resolve(course.modelPolicy, student, course.allowLocalModels, now)

    /** Resuelve con la política suelta (tests y usos sin `Course`). */
    fun resolve(
        policy: ModelPolicy,
        student: StudentModelProfile,
        allowLocalModels: Boolean = true,
        now: Long = nowMillis(),
    ): EffectiveModelConfig {
        val rules = policy.effectiveRules
        // Sólo se honra el preset **elegido por el alumno**. El preset por
        // defecto de la cátedra (`ModelPolicy.defaultPresetId`) se materializa
        // en el perfil del alumno al inscribirse, así la elección queda
        // registrada y el resolver no tiene que inventar una.
        val preset = policy.presets.firstOrNull { it.id == student.presetId }
        val changes = mutableListOf<ConfigChange>()
        val override = policy.allowStudentOverride

        // ---- 1. Modo ---------------------------------------------------
        val mode = resolveMode(policy, student, preset, allowLocalModels, rules, changes)

        // ---- 2. Prompt del sistema (composición) ----------------------
        val systemPrompt = resolveSystemPrompt(policy, student, override, changes)

        // ---- 3. Parámetros de generación -------------------------------
        val temperature = resolveNumber(
            LockedField.TEMPERATURE,
            rules[LockedField.TEMPERATURE],
            base = policy.temperature,
            requested = if (override) (student.temperature ?: preset?.temperature ?: policy.temperature) else policy.temperature,
            source = sourceOf(student.temperature != null, preset?.temperature != null),
            changes = changes,
        )
        val maxTokens = resolveNumber(
            LockedField.MAX_TOKENS,
            rules[LockedField.MAX_TOKENS],
            base = policy.maxTokens.toDouble(),
            requested = if (override) (student.maxTokens ?: preset?.maxTokens ?: policy.maxTokens).toDouble() else policy.maxTokens.toDouble(),
            source = sourceOf(student.maxTokens != null, preset?.maxTokens != null),
            changes = changes,
        ).toInt()
        val topK = resolveNumber(
            LockedField.TOP_K,
            rules[LockedField.TOP_K],
            base = policy.topK.toDouble(),
            requested = if (override) (student.topK ?: policy.topK).toDouble() else policy.topK.toDouble(),
            source = sourceOf(student.topK != null, false),
            changes = changes,
        ).toInt()
        val thinking = resolveText(
            LockedField.THINKING,
            rules[LockedField.THINKING],
            base = policy.thinking,
            requested = if (override) (student.thinking?.takeIf { it.isNotBlank() } ?: preset?.thinking?.takeIf { it.isNotBlank() } ?: policy.thinking) else policy.thinking,
            source = sourceOf(student.thinking != null, preset?.thinking?.isNotBlank() == true),
            changes = changes,
        )
        val power = resolveText(
            LockedField.POWER,
            rules[LockedField.POWER],
            base = policy.power,
            requested = if (override) (student.power?.takeIf { it.isNotBlank() } ?: preset?.power?.takeIf { it.isNotBlank() } ?: policy.power) else policy.power,
            source = sourceOf(student.power != null, preset?.power?.isNotBlank() == true),
            changes = changes,
        )
        val toolsEnabled = resolveFlag(
            LockedField.TOOLS_ENABLED,
            rules[LockedField.TOOLS_ENABLED],
            base = policy.toolsEnabled,
            requested = if (override) (student.toolsEnabled ?: policy.toolsEnabled) else policy.toolsEnabled,
            source = sourceOf(student.toolsEnabled != null, false),
            changes = changes,
        )

        // ---- 4. Modo local: el modelo es del alumno --------------------
        if (mode == ModelMode.LOCAL) {
            val local = resolveLocal(policy, student.local, rules, changes)
            return EffectiveModelConfig(
                mode = ModelMode.LOCAL,
                remoteBaseUrl = "",
                modelName = local.hfFile,
                temperature = temperature,
                maxTokens = maxTokens,
                topK = topK,
                systemPrompt = systemPrompt,
                toolsEnabled = toolsEnabled,
                thinking = thinking,
                power = power,
                local = local,
                keySource = KeySource.NONE,
                keyExpiresAt = 0L,
                keyStatus = CredentialStatus.MISSING,
                presetId = preset?.id,
                changes = changes,
                blocked = false,
                problem = "",
                canUseLocalFallback = false,
                apiKey = "",
            )
        }

        // ---- 5. Modo remoto: endpoint, modelo y credencial ------------
        val presetBaseUrl = preset?.remoteBaseUrl?.takeIf { it.isNotBlank() }
        val presetModel = preset?.modelName?.takeIf { it.isNotBlank() }
        val remoteBaseUrl = resolveText(
            LockedField.BASE_URL,
            rules[LockedField.BASE_URL],
            base = policy.remoteBaseUrl,
            requested = if (override) {
                student.remoteBaseUrl?.takeIf { it.isNotBlank() } ?: presetBaseUrl ?: policy.remoteBaseUrl
            } else {
                policy.remoteBaseUrl
            },
            source = sourceOf(student.remoteBaseUrl?.isNotBlank() == true, presetBaseUrl != null),
            changes = changes,
        )
        val modelName = resolveText(
            LockedField.MODEL_NAME,
            rules[LockedField.MODEL_NAME],
            base = policy.modelName,
            requested = if (override) {
                student.modelName?.takeIf { it.isNotBlank() } ?: presetModel ?: policy.modelName
            } else {
                policy.modelName
            },
            source = sourceOf(student.modelName?.isNotBlank() == true, presetModel != null),
            changes = changes,
        )

        val credential = resolveCredential(policy, student, rules[LockedField.API_KEY], now)
        val keyStatus = credential.status(now)

        var blocked = false
        var problem = ""
        when (keyStatus) {
            CredentialStatus.MISSING -> {
                blocked = true
                problem = "Falta la clave de API para usar el servidor de la cátedra."
            }
            CredentialStatus.EXPIRED -> {
                blocked = true
                problem = if (credential.source == KeySource.PERSONAL) {
                    "Tu clave de API venció el ${ModelPolicy.formatDate(credential.expiresAt)}: " +
                        "pedí una nueva para seguir usando el servidor."
                } else {
                    "La clave de la cátedra venció el ${ModelPolicy.formatDate(credential.expiresAt)}: " +
                        "pedile al profesor una nueva."
                }
            }
            else -> Unit
        }

        val canUseLocal = allowLocalModels && rules[LockedField.MODE] !is FieldConstraint.Locked

        return EffectiveModelConfig(
            mode = ModelMode.REMOTE,
            remoteBaseUrl = remoteBaseUrl,
            modelName = modelName,
            temperature = temperature,
            maxTokens = maxTokens,
            topK = topK,
            systemPrompt = systemPrompt,
            toolsEnabled = toolsEnabled,
            thinking = thinking,
            power = power,
            local = student.local,
            keySource = credential.source,
            keyExpiresAt = credential.expiresAt,
            keyStatus = keyStatus,
            presetId = preset?.id,
            changes = changes,
            blocked = blocked,
            problem = problem,
            canUseLocalFallback = canUseLocal && blocked,
            apiKey = credential.key,
        )
    }

    // ------------------------------------------------------------------
    // Reportes para el docente
    // ------------------------------------------------------------------

    /**
     * Resumen de la configuración de cada alumno, para la vista del docente.
     *
     * Respeta la visibilidad de la inscripción: con [ChatVisibility.NONE] el
     * alumno no aparece (no se reporta ni el modo).
     */
    fun reports(
        course: Course,
        enrollments: List<Enrollment>,
        profiles: List<StudentModelProfile>,
        now: Long = nowMillis(),
    ): List<StudentConfigReport> {
        val byUser = profiles.associateBy { it.userId }
        return enrollments
            .filter { it.role == CourseRole.ALUMNO && it.visibility.showsMetrics }
            .map { enrollment ->
                val profile = byUser[enrollment.userId]
                    ?: StudentModelProfile(courseId = course.id, userId = enrollment.userId)
                val effective = resolve(course, profile, now)
                StudentConfigReport(
                    studentId = enrollment.userId,
                    displayName = enrollment.displayName,
                    mode = effective.mode,
                    modelLabel = effective.modelLabel,
                    presetId = effective.presetId,
                    keyStatus = effective.keyStatus,
                    keySource = effective.keySource,
                    keyExpiresAt = effective.keyExpiresAt,
                    changes = effective.changes,
                    appliedCount = effective.changes.count { it.status == ChangeStatus.ACCEPTED },
                    clampedCount = effective.changes.count { it.status == ChangeStatus.CLAMPED },
                    rejectedCount = effective.changes.count { it.status == ChangeStatus.REJECTED },
                    lastUsedAt = profile.lastUsedAt,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    // ------------------------------------------------------------------
    // Piezas de la resolución
    // ------------------------------------------------------------------

    private fun resolveMode(
        policy: ModelPolicy,
        student: StudentModelProfile,
        preset: ModelPreset?,
        allowLocalModels: Boolean,
        rules: Map<LockedField, FieldConstraint>,
        changes: MutableList<ConfigChange>,
    ): ModelMode {
        val requested = student.mode ?: preset?.mode ?: policy.mode
        var effective = requested
        var status = ChangeStatus.UNCHANGED
        var reason = ""

        if (requested == ModelMode.LOCAL && !allowLocalModels) {
            effective = policy.mode
            status = ChangeStatus.REJECTED
            reason = "La cátedra no permite usar IA local en esta materia."
        }
        when (val rule = rules[LockedField.MODE]) {
            is FieldConstraint.Locked -> {
                if (effective != policy.mode) {
                    effective = policy.mode
                    status = ChangeStatus.REJECTED
                    reason = "La cátedra fijó el modo en «${policy.mode.label}»."
                }
            }
            is FieldConstraint.Choice -> {
                if (effective.name !in rule.values) {
                    effective = policy.mode
                    status = ChangeStatus.REJECTED
                    reason = "El modo «${effective.label}» no está permitido por la cátedra " +
                        "(${rule.values.joinToString(", ")})."
                }
            }
            else -> Unit
        }
        push(
            changes, LockedField.MODE,
            base = policy.mode.label, requested = requested.label, effective = effective.label,
            status = status, reason = reason,
            source = sourceOf(student.mode != null, preset?.mode != null),
        )
        return effective
    }

    private fun resolveSystemPrompt(
        policy: ModelPolicy,
        student: StudentModelProfile,
        override: Boolean,
        changes: MutableList<ConfigChange>,
    ): String {
        val extra = if (override) student.systemPrompt.orEmpty().trim() else ""
        val rule = policy.ruleFor(LockedField.SYSTEM_PROMPT)
        return if (rule is FieldConstraint.Locked) {
            push(
                changes, LockedField.SYSTEM_PROMPT,
                base = policy.systemPrompt, requested = extra, effective = policy.systemPrompt,
                status = if (extra.isBlank()) ChangeStatus.UNCHANGED else ChangeStatus.REJECTED,
                reason = if (extra.isBlank()) "" else
                    "La cátedra fijó las instrucciones del sistema: las tuyas se descartan.",
                source = ChangeSource.STUDENT,
            )
            policy.systemPrompt
        } else {
            val combined = when {
                extra.isBlank() -> policy.systemPrompt
                policy.systemPrompt.isBlank() -> extra
                else -> policy.systemPrompt.trimEnd() + "\n\n" + extra
            }
            push(
                changes, LockedField.SYSTEM_PROMPT,
                base = policy.systemPrompt, requested = extra, effective = combined,
                status = if (extra.isBlank()) ChangeStatus.UNCHANGED else ChangeStatus.ACCEPTED,
                reason = "",
                source = ChangeSource.STUDENT,
            )
            combined
        }
    }

    private fun resolveLocal(
        policy: ModelPolicy,
        local: LocalModelProfile,
        rules: Map<LockedField, FieldConstraint>,
        changes: MutableList<ConfigChange>,
    ): LocalModelProfile {
        val hfRepo = resolveText(
            LockedField.LOCAL_HF_REPO, rules[LockedField.LOCAL_HF_REPO],
            base = policy.hfRepo, requested = local.hfRepo,
            source = ChangeSource.STUDENT, changes = changes,
        )
        val hfFile = resolveText(
            LockedField.LOCAL_HF_FILE, rules[LockedField.LOCAL_HF_FILE],
            base = policy.hfFile, requested = local.hfFile,
            source = ChangeSource.STUDENT, changes = changes,
        )
        val modelsDir = resolveText(
            LockedField.LOCAL_MODELS_DIR, rules[LockedField.LOCAL_MODELS_DIR],
            base = policy.modelsDir, requested = local.modelsDir,
            source = ChangeSource.STUDENT, changes = changes,
        )
        val llamaServerPath = resolveText(
            LockedField.LLAMA_SERVER_PATH, rules[LockedField.LLAMA_SERVER_PATH],
            base = policy.llamaServerPath, requested = local.llamaServerPath,
            source = ChangeSource.STUDENT, changes = changes,
        )
        val localPort = resolveNumber(
            LockedField.LOCAL_PORT, rules[LockedField.LOCAL_PORT],
            base = policy.localPort.toDouble(), requested = local.localPort.toDouble(),
            source = ChangeSource.STUDENT, changes = changes,
        ).toInt()
        val contextSize = resolveNumber(
            LockedField.CONTEXT_SIZE, rules[LockedField.CONTEXT_SIZE],
            base = policy.contextSize.toDouble(), requested = local.contextSize.toDouble(),
            source = ChangeSource.STUDENT, changes = changes,
        ).toInt()
        val gpuLayers = resolveNumber(
            LockedField.GPU_LAYERS, rules[LockedField.GPU_LAYERS],
            base = policy.gpuLayers.toDouble(), requested = local.gpuLayers.toDouble(),
            source = ChangeSource.STUDENT, changes = changes,
        ).toInt()

        return LocalModelProfile(
            hfRepo = hfRepo,
            hfFile = hfFile,
            modelsDir = modelsDir,
            llamaServerPath = llamaServerPath,
            localPort = localPort,
            contextSize = contextSize,
            gpuLayers = gpuLayers,
        )
    }

    /**
     * Decide qué credencial se usa, según la regla de [LockedField.API_KEY].
     *
     * - `Locked` → siempre la de la cátedra.
     * - `Choice` → según la lista ("COURSE" / "PERSONAL").
     * - libre → la personal si existe y no venció; si no, la de la cátedra.
     */
    private fun resolveCredential(
        policy: ModelPolicy,
        student: StudentModelProfile,
        rule: FieldConstraint?,
        now: Long,
    ): ApiCredential {
        val course = ApiCredential(
            key = policy.apiKey,
            issuedAt = 0L,
            expiresAt = policy.apiKeyExpiresAt,
            source = KeySource.COURSE,
            label = policy.apiKeyLabel.ifBlank { "Clave de la cátedra" },
        )
        val personal = student.personalKey.copy(
            source = KeySource.PERSONAL,
            label = student.personalKey.label.ifBlank { "Clave personal" },
        )
        return when (rule) {
            is FieldConstraint.Locked -> course
            is FieldConstraint.Choice -> {
                val allowCourse = rule.values.contains("COURSE")
                val allowPersonal = rule.values.contains("PERSONAL")
                when {
                    allowPersonal && !allowCourse -> personal
                    allowPersonal && personal.status(now) != CredentialStatus.MISSING -> personal
                    else -> course
                }
            }
            else -> when (personal.status(now)) {
                CredentialStatus.MISSING, CredentialStatus.EXPIRED -> course
                else -> personal
            }
        }
    }

    // ------------------------------------------------------------------
    // Aplicación de reglas por tipo de campo
    // ------------------------------------------------------------------

    private fun resolveNumber(
        field: LockedField,
        rule: FieldConstraint?,
        base: Double,
        requested: Double,
        source: ChangeSource,
        changes: MutableList<ConfigChange>,
    ): Double {
        val effective: Double
        val status: ChangeStatus
        val reason: String
        when (rule) {
            is FieldConstraint.Locked -> {
                effective = base
                status = if (requested != base) ChangeStatus.REJECTED else ChangeStatus.UNCHANGED
                reason = if (requested != base) {
                    "La cátedra fijó ${field.label.lowercase()} en ${ModelPolicy.round2(base)}."
                } else ""
            }
            is FieldConstraint.Ranged -> {
                val clamped = requested.coerceIn(rule.min, rule.max)
                effective = clamped
                status = when {
                    clamped != requested -> ChangeStatus.CLAMPED
                    requested != base -> ChangeStatus.ACCEPTED
                    else -> ChangeStatus.UNCHANGED
                }
                reason = if (clamped != requested) {
                    "La cátedra limita ${field.label.lowercase()} a " +
                        "${ModelPolicy.round2(rule.min)} – ${ModelPolicy.round2(rule.max)}: " +
                        "se usa ${ModelPolicy.round2(clamped)}."
                } else ""
            }
            is FieldConstraint.Choice -> {
                // Una lista de valores no aplica a un número: se trata como fijo.
                effective = base
                status = if (requested != base) ChangeStatus.REJECTED else ChangeStatus.UNCHANGED
                reason = if (requested != base) "${field.label} está fijo por la cátedra." else ""
            }
            else -> {
                effective = requested
                status = if (requested != base) ChangeStatus.ACCEPTED else ChangeStatus.UNCHANGED
                reason = ""
            }
        }
        push(changes, field, base, requested, effective, status, reason, source)
        return effective
    }

    private fun resolveText(
        field: LockedField,
        rule: FieldConstraint?,
        base: String,
        requested: String,
        source: ChangeSource,
        changes: MutableList<ConfigChange>,
    ): String {
        val effective: String
        val status: ChangeStatus
        val reason: String
        when (rule) {
            is FieldConstraint.Locked -> {
                effective = base
                status = if (requested != base) ChangeStatus.REJECTED else ChangeStatus.UNCHANGED
                reason = if (requested != base) {
                    "La cátedra fijó ${field.label.lowercase()}: tu valor se descarta."
                } else ""
            }
            is FieldConstraint.Choice -> {
                val allowed = requested in rule.values
                effective = if (allowed) requested else base
                status = when {
                    !allowed -> ChangeStatus.REJECTED
                    requested != base -> ChangeStatus.ACCEPTED
                    else -> ChangeStatus.UNCHANGED
                }
                reason = if (!allowed) {
                    "«$requested» no está entre los valores permitidos por la cátedra " +
                        "(${rule.values.joinToString(", ")})."
                } else ""
            }
            else -> {
                effective = requested
                status = if (requested != base) ChangeStatus.ACCEPTED else ChangeStatus.UNCHANGED
                reason = ""
            }
        }
        push(changes, field, base, requested, effective, status, reason, source)
        return effective
    }

    private fun resolveFlag(
        field: LockedField,
        rule: FieldConstraint?,
        base: Boolean,
        requested: Boolean,
        source: ChangeSource,
        changes: MutableList<ConfigChange>,
    ): Boolean {
        val effective: Boolean
        val status: ChangeStatus
        val reason: String
        when (rule) {
            is FieldConstraint.Locked -> {
                effective = base
                status = if (requested != base) ChangeStatus.REJECTED else ChangeStatus.UNCHANGED
                reason = if (requested != base) "La cátedra fijó ${field.label.lowercase()}." else ""
            }
            is FieldConstraint.Choice -> {
                // Las listas de un campo booleano usan "Sí" / "No".
                val requestedText = if (requested) "Sí" else "No"
                val allowed = requestedText in rule.values
                effective = if (allowed) requested else base
                status = when {
                    !allowed -> ChangeStatus.REJECTED
                    requested != base -> ChangeStatus.ACCEPTED
                    else -> ChangeStatus.UNCHANGED
                }
                reason = if (!allowed) {
                    "«$requestedText» no está permitido por la cátedra " +
                        "(${rule.values.joinToString(", ")})."
                } else ""
            }
            else -> {
                effective = requested
                status = if (requested != base) ChangeStatus.ACCEPTED else ChangeStatus.UNCHANGED
                reason = ""
            }
        }
        push(changes, field, base, requested, effective, status, reason, source)
        return effective
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun sourceOf(explicit: Boolean, fromPreset: Boolean): ChangeSource = when {
        explicit -> ChangeSource.STUDENT
        fromPreset -> ChangeSource.PRESET
        else -> ChangeSource.POLICY
    }

    private fun push(
        changes: MutableList<ConfigChange>,
        field: LockedField,
        base: Any?,
        requested: Any?,
        effective: Any?,
        status: ChangeStatus,
        reason: String,
        source: ChangeSource,
    ) {
        changes += ConfigChange(
            field = field,
            label = field.label,
            baseValue = fmt(base),
            studentValue = fmt(requested),
            effectiveValue = fmt(effective),
            status = status,
            reason = reason,
            source = source,
        )
    }

    /** Formatea un valor para mostrarlo (sin depender del locale). */
    fun fmt(value: Any?): String = when (value) {
        null -> "—"
        is Boolean -> if (value) "Sí" else "No"
        is Double -> ModelPolicy.round2(value).toString()
        is Float -> ModelPolicy.round2(value.toDouble()).toString()
        is String -> if (value.length <= 120) value else value.take(117) + "…"
        else -> value.toString()
    }
}

/**
 * Resumen de la configuración de un alumno, para la vista del docente.
 *
 * Es **información de la cátedra** (la configuración), no contenido del
 * alumno: por eso existe aunque la visibilidad de la inscripción sea
 * [ChatVisibility.METRICS_ONLY]. Con [ChatVisibility.NONE] el alumno no
 * aparece.
 */
@Serializable
data class StudentConfigReport(
    /** Alumno. */
    val studentId: String,

    /** Nombre visible. */
    val displayName: String = "",

    /** Modo que usó. */
    val mode: ModelMode = ModelMode.REMOTE,

    /** Modelo que usó (etiqueta legible). */
    val modelLabel: String = "",

    /** Preset que usó (null = la base). */
    val presetId: String? = null,

    /** Estado de la credencial. */
    val keyStatus: CredentialStatus = CredentialStatus.MISSING,

    /** De dónde vino la credencial. */
    val keySource: KeySource = KeySource.NONE,

    /** Vencimiento de la credencial (0 = no vence). */
    val keyExpiresAt: Long = 0L,

    /** Cambios campo a campo. */
    val changes: List<ConfigChange> = emptyList(),

    /** Cuántos cambios se aplicaron tal cual. */
    val appliedCount: Int = 0,

    /** Cuántos fueron recortados por una regla de rango. */
    val clampedCount: Int = 0,

    /** Cuántos fueron descartados (bloqueados). */
    val rejectedCount: Int = 0,

    /** Último uso del modelo. */
    val lastUsedAt: Long = 0L,
) {
    /** ¿Usó IA local? */
    val usedLocal: Boolean get() = mode == ModelMode.LOCAL

    /** Sólo los cambios reales (sin los "sin cambios"). */
    val effectiveChanges: List<ConfigChange> get() = changes.filter { it.isChange }
}
```

---

## 11. Validación: mensajes finales

### 11.1 `ModelPolicy.validate()` (la cátedra)

| Situación | Mensaje |
|---|---|
| `maxTokens <= 0` | *"El límite de tokens debe ser mayor que 0 (actual: 0)."* |
| temperatura fuera de 0..2 | *"La temperatura debe estar entre 0 y 2 (actual: 2.5)."* |
| `topK <= 0` | *"El número de fragmentos recuperados debe ser mayor que 0 (actual: 0)."* |
| razonamiento inválido | *"El nivel de razonamiento debe ser uno de: off, low, medium, high (actual: best)."* |
| potencia inválida | *"La potencia debe ser una de: low, medium, high (actual: full)."* |
| puerto inválido | *"El puerto local debe estar entre 1 y 65535 (actual: 99999)."* |
| contexto fuera de rango | *"El tamaño del contexto debe estar entre 128 y 40960 (actual: 256)."* |
| GPU negativas | *"Las capas en GPU no pueden ser negativas (actual: -1)."* |
| falta la URL | *"Falta la dirección del servidor de la cátedra."* |
| URL sin esquema | *"La dirección del servidor debe empezar con http:// o https://."* |
| falta el modelo | *"Falta el nombre del modelo en el servidor."* |
| falta la key (remoto no-local) | *"Falta la clave de API de la cátedra (si el servidor es local no hace falta)."* |
| key vencida | *"La clave de la cátedra venció el 30/06/2026: pedí una nueva antes de publicar la cátedra."* |
| rango invertido | *"El rango de «Temperatura» está invertido (0.8 > 0.0)."* |
| rango en campo no numérico | *"«Nombre del modelo» no es un campo numérico: no se le puede poner un rango."* |
| lista vacía | *"«Nivel de razonamiento» no tiene valores permitidos."* |
| presets con id repetido | *"Hay presets con el mismo id: los id tienen que ser únicos."* |
| preset por defecto inexistente | *"El preset por defecto («rápido») no existe en la lista de presets."* |

### 11.2 `ModelPolicy.validateFor(student)` (el alumno)

| Situación | Mensaje |
|---|---|
| pidió LOCAL y no está permitido | *"La cátedra no permite usar IA local en esta materia."* |
| pidió LOCAL con el modo bloqueado | *"La cátedra fijó el modo en «Servidor de la cátedra»: no se puede cambiar."* |
| temperatura fuera del rango | *"La temperatura que elegiste (0.95) está fuera del rango permitido por la cátedra (0.0 – 0.8): se usará 0.8."* |
| temperatura bloqueada | *"La cátedra fijó la temperatura en 0.2: no se puede cambiar."* |
| tokens fuera del rango | *"El límite de tokens que elegiste (8192) está fuera del rango permitido por la cátedra (256 – 4096)."* |
| top-k fuera del rango | *"Los fragmentos recuperados que elegiste (20) están fuera del rango permitido por la cátedra (3 – 8)."* |
| key personal con la key bloqueada | *"La cátedra usa su propia clave: la clave personal que cargaste se ignora."* |
| key personal vencida | *"Tu clave de API venció el 12/05/2026: pedí una nueva o usá la clave de la cátedra."* |

---

## 12. Tests propuestos (`core/src/commonTest/kotlin/kitoru/domain/ModelResolverTest.kt`)

```kotlin
class ModelResolverTest {

    private val ahora = 1_700_000_000_000L

    @Test
    fun `la catedra bloquea la api key pero permite el modelo`() {
        val policy = ModelPolicy(
            remoteBaseUrl = "https://ia.un.edu.ar/kitoru/v1",
            apiKey = "key-catedra",
            modelName = "qwen3-32b",
            lockedFields = setOf(LockedField.API_KEY),
        )
        val student = StudentModelProfile(courseId = "c1", userId = "u1", modelName = "qwen3-8b")

        val out = ModelResolver.resolve(policy, student, now = ahora)

        assertEquals("key-catedra", out.apiKey)
        assertEquals("qwen3-8b", out.modelName)
        assertFalse(out.blocked)
    }

    @Test
    fun `la temperatura fuera de rango se recorta y queda en el diff`() {
        val policy = ModelPolicy(
            remoteBaseUrl = "https://x/v1", apiKey = "k", modelName = "m",
            fieldRules = mapOf(LockedField.TEMPERATURE to FieldConstraint.Ranged(0.0, 0.8)),
        )
        val student = StudentModelProfile(courseId = "c1", userId = "u1", temperature = 0.95)

        val out = ModelResolver.resolve(policy, student, now = ahora)

        assertEquals(0.8, out.temperature, 1e-9)
        val change = out.changes.first { it.field == LockedField.TEMPERATURE }
        assertEquals(ChangeStatus.CLAMPED, change.status)
    }

    @Test
    fun `sin override el alumno usa exactamente la catedra`() {
        val policy = ModelPolicy(
            remoteBaseUrl = "https://x/v1", apiKey = "k", modelName = "m",
            temperature = 0.3, allowStudentOverride = false,
        )
        val student = StudentModelProfile(courseId = "c1", userId = "u1", temperature = 0.9, modelName = "otro")

        val out = ModelResolver.resolve(policy, student, now = ahora)

        assertEquals(0.3, out.temperature, 1e-9)
        assertEquals("m", out.modelName)
    }

    @Test
    fun `en modo local se ignora baseUrl y apiKey pero se respeta el rango`() {
        val policy = ModelPolicy(
            remoteBaseUrl = "https://x/v1", apiKey = "k", modelName = "m",
            fieldRules = mapOf(LockedField.TEMPERATURE to FieldConstraint.Ranged(0.0, 0.8)),
        )
        val student = StudentModelProfile(courseId = "c1", userId = "u1", mode = ModelMode.LOCAL, temperature = 0.5)

        val out = ModelResolver.resolve(policy, student, now = ahora)

        assertEquals(ModelMode.LOCAL, out.mode)
        assertEquals("", out.remoteBaseUrl)
        assertEquals("", out.apiKey)
        assertEquals(KeySource.NONE, out.keySource)
        assertEquals(0.5, out.temperature, 1e-9)
        assertFalse(out.blocked)
    }

    @Test
    fun `la key vencida bloquea el chat y avisa`() {
        val policy = ModelPolicy(
            remoteBaseUrl = "https://x/v1", apiKey = "k", modelName = "m",
            apiKeyExpiresAt = ahora - 1000,
        )
        val out = ModelResolver.resolve(policy, StudentModelProfile(), now = ahora)

        assertTrue(out.blocked)
        assertTrue(out.problem.contains("venció"))
    }
}
```

---

## 13. Resumen de decisiones

| Decisión | Motivo |
|---|---|
| `lockedFields` (set) **+** `fieldRules` (map) | el set no alcanza para rangos ni elecciones; se conserva por compat y como atajo |
| `FieldConstraint` con 4 casos (`Free`/`Ranged`/`Choice`/`Locked`) | cubre bloqueado, rango, default explícito y libre |
| La base es el valor por defecto | un solo mecanismo para "libre con default" |
| El alumno guarda **deltas**, no una copia de la política | el diff es exacto y gratis; los cambios de la cátedra se propagan solos |
| `resolveFor` se reemplaza por `ModelResolver.resolve` | la política y la elección del alumno son de tipos distintos |
| `apiKey` es `@Transient` en `EffectiveModelConfig` | ni los hilos ni el `courses.json` pueden filtrar la key |
| `expiresAt: Long` (0 = no vence) | mismo criterio que el resto del dominio; sin `java.time` |
| La key de la cátedra vive en `ModelPolicy`; la personal en `StudentModelProfile` | son de dueños distintos y viajan por caminos distintos |
| Los presets viven en `ModelPolicy`; la elección en el perfil del alumno | son config de la cátedra, pero la elección es del alumno |
| El modelo local vive en `LocalModelProfile` (lado del alumno) | no ensucia la política; la cátedra sólo puede acotar contexto/capas |
| El modo es la única excepción a `allowStudentOverride = false` | válvula de escape cuando la key de la cátedra venció |
| El diff se calcula durante la resolución | el diff **es** la config usada; no puede desincronizarse |
