# 10 — Accesibilidad e internacionalización

> Subagente: diseño transversal de **accesibilidad (a11y)** e **internacionalización
> (i18n)** de Kitoru, desde el día 1.
> Módulos tocados: `:core` (dominio + kernel de idioma), `:llm-api` (errores
> tipados), `:backend` (motivo de las citas), `:ui` (catálogo de textos, tema,
> semántica), `:desktop` / `:android` (locale del SO, entry points).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *fail-fast*, *el estudiante conserva agencia*, *el docente no ve lo que
> no debe*.
>
> **Este documento es normativo**: define las reglas que TODA pantalla nueva de
> Kitoru tiene que cumplir. Si una pantalla no lo cumple, no está terminada.

---

## 0. Resumen en cinco líneas

1. **La app se escribe en español rioplatense (voseo)** — el registro que ya usa el
   proyecto—, con el **registro como opción de la app** (rioplatense / tú / usted) y
   con el **idioma de la interfaz como opción aparte** (es / en / pt, una carpeta de
   recursos por idioma; el español es el default y el fallback).
2. **Los textos salen del dominio**: `ModelPolicy.validate()`, `LlmException`,
   `CitationVerifier` y los `label` de los enums dejan de producir frases y
   devuelven **códigos estructurados** (`ValidationIssue`, `LlmErrorCode`,
   `CitationReason`); las frases viven en **un único catálogo**
   (`composeResources/values/strings.xml`) y el compilador obliga a que todo código
   tenga su traducción (`when` exhaustivo).
3. **El modelo responde en el idioma del alumno**: se **detecta** el idioma de la
   pregunta (determinista, sin red) y se **fuerza** con una directiva de idioma que
   viaja como *system message* aparte del prompt de la cátedra (que puede estar
   bloqueado); si la detección falla, se usa el idioma de la conversación y, en
   último caso, el de la app.
4. **La accesibilidad se diseña antes de la primera pantalla**: `contentDescription`
   en todo lo que no es texto, `heading()` + `stateDescription` en la navegación y
   en el chat, foco visible y orden de tabulación en escritorio, contraste **≥ 4.5:1
   verificado por un test**, tamaño de fuente escalable (`sp` + escala de la app),
   tamaño interactivo mínimo (48 dp en Android / 40 dp en escritorio) y reducción
   de movimiento respetada desde el SO.
5. **El chat es la pantalla crítica**: los turnos se marcan como `heading()` para
   navegar con el rotor, la respuesta por streaming se anuncia **una sola vez**
   (nunca token a token), las tarjetas de herramienta exponen su estado con
   `stateDescription`, y los errores de red se anuncian con `liveRegion` +
   `role = Role.Button` en la acción de reintento.

---

## 1. Diagnóstico: qué hay hoy y qué duele

### 1.1 Inventario de textos hardcodeados (con archivo y línea)

| # | Ubicación | Texto | Problema |
|---|---|---|---|
| T1 | `core/.../domain/ModelPolicy.kt:102-117` | `"El límite de tokens debe ser mayor que 0 (actual: $maxTokens)."` | Frase de negocio en el dominio; no traducible; interpolación de un número "a ojo" (sin formato de locale). |
| T2 | `core/.../domain/ModelPolicy.kt:77` | `modelLabel` → `"servidor de la cátedra"` | Idioma del dominio; el nombre de un modelo no debería traducirse, pero el *fallback* sí. |
| T3 | `core/.../domain/ModelPolicy.kt:9,12` | `ModelMode.label` = `"Servidor de la cátedra"`, `"IA local del alumno"` | `label` es **propiedad del constructor** ⇒ está en el JSON serializado. |
| T4 | `core/.../domain/ModelPolicy.kt:18-24` | `LockedField.label` (6 valores) | Ídem. |
| T5 | `core/.../domain/Roles.kt:17-19` | `CourseRole.label` | Ídem. |
| T6 | `core/.../domain/Roles.kt:52-63` | `ChatVisibility.label` + `description` | Ídem; las descripciones son texto largo de UI. |
| T7 | `core/.../domain/Course.kt:71-76` | `MaterialKind.label` (5 valores) | Ídem. |
| T8 | `llm-api/.../llm/error/LlmErrors.kt:62-124` | `fromHttp`, `fromNetwork`, `wrap` arman frases ("la API rechazó la clave (HTTP 401)", "No se pudo hablar con llama-server: …") | El **gateway** decide el idioma. Además mezcla frase traducida + detalle técnico en un solo `message`. |
| T9 | `backend/.../rag/engine/CitationVerifier.kt:110,149,162,173` | `"No hay una fuente [$index] entre las recuperadas."`, `"La fuente está vacía…"`, `"La cita menciona $lista…"`, `"…sólo el $percent% de sus palabras."` | Motivos de verificación en el dominio; se muestran al alumno (badge) y al docente (detalle). |
| T10 | `backend/.../rag/engine/RagEngine.kt:61,79-83` | `"(sin resultados en el material de la cátedra)"`, `GENERAL_PROMPT` | El marcador del CONTEXTO **tiene** que ser estable (lo compara el modelo y la vista previa del docente), pero el prompt general sí es traducible. |
| T11 | `backend/.../rag/engine/...` (RAG) | `"CONTEXTO:"`, `"PREGUNTA:"` | Marcadores del prompt: **no se traducen** (son parte del contrato con el modelo); ver §4.6. |
| T12 | `frontend` de FunshiEngineAI (`MessageBubble.kt`, `ToolCallCard.kt`, `CodeBlock.kt`, `ToolsSettings.kt`) | `"Copiar"`, `"Guardar como…"`, `"Quitar servidor"`, `"Citas verificadas (n)"`, `"Herramienta: …"`, `"OK"`, `"Falló"` | Se portan a Kitoru: hay que hacerlo **desde el catálogo**, no hardcodeados. |

Además, en FunshiEngineAI hay **cero semántica de accesibilidad**: `contentDescription`
aparece 11 veces en todo el frontend, 4 de ellas con `null` (correcto, decorativo) y
7 como cadenas literales en español. No hay `stateDescription`, ni `heading()`, ni
`liveRegion`. **Kitoru no copia eso**: es la chance de hacerlo bien desde el
principio.

### 1.2 Qué es barato de hacer ahora y qué es caro de retrofitear

| Qué | Costo si se hace tarde | Por qué |
|---|---|---|
| `contentDescription` en cada icono | **ALTO** | 300+ sitios; hay que auditar la UI entera, no sólo un archivo. |
| `heading()` / `stateDescription` en la navegación y el chat | **MEDIO-ALTO** | cambia la estructura del `LazyColumn` del chat y hay que re-revisar todas las pantallas. |
| Orden de tabulación y foco visible | **ALTO** | depende del **orden del árbol de composición**: cambiarlo mueve todos los destinos de tabulación. |
| Salida de los textos del dominio | **ALTO** | 300+ literalesials dispersos; también rompe el JSON de `label` de los enums. |
| `sp` en todas las tipografías | **MEDIO** | un `fontSize = 14.dp` en 40 sitios hay que cazarlos todos. |
| Alturas fijas (`Modifier.height(32.dp)`) en contenedores de texto | **MEDIO** | rompen con fuente grande; hay que encontrarlos con un test de layout. |
| Esquema de color derivado del acento (sin colores hardcodeados por pantalla) | **BAJO** | si se hace con `MaterialTheme.colorScheme` desde el día 1. |
| `liveRegion` en errores y estados | **BAJO** | es un `Modifier` en un componente compartido. |
| Reducción de movimiento | **MEDIO** | cada `AnimatedVisibility`, `animateScrollToItem` y spinner hay que hacerlo consciente. |
| Idioma del modelo | **BAJO** | es una capa en `:backend` + una directiva en el prompt. |

> **Regla del proyecto (nueva, de este documento):** *ningún `Text("literal")` en
> `:ui`*. Todo texto visible pasa por el catálogo. La excepción: los nombres propios
> de la cátedra, los identificadores técnicos (nombres de archivo, ids de modelo) y
> el contenido del modelo.

---

## 2. Decisión de idioma: ¿en qué idioma y para quién?

### 2.1 Los dos ejes (no se confunden)

| Eje | Qué es | Valores | Dónde se decide |
|---|---|---|---|
| **Idioma de la interfaz** | En qué idioma está la app | `es` (default), `en`, `pt` (futuro) | `KitoruSettings.language` |
| **Registro** | Cómo se le habla al usuario dentro del español | `RIOPLATENSE` (vos), `TU`, `USTED` | `KitoruSettings.form` |
| **Idioma del modelo** | En qué idioma responde la IA | el que usa el alumno (o el que fija la cátedra) | `Course.answerLanguage` + detección |

El registro **no** es un idioma: por eso no se resuelve con carpetas de recursos
(§3.3), sino con una opción de la app.

### 2.2 ¿Qué español? Decisión

**Decisión: el default es español rioplatense (voseo)**, con las otras dos variantes
disponibles en Preferencias → Apariencia.

Motivos:

1. **Es la voz que el proyecto ya tiene.** Los documentos de diseño
   (`05-config-modelo.md`, `04-prompt.md`) escriben "Revisá", "Pedí", "Copiá",
   "si querés". Los textos de `LlmErrorMessages` del doc 05 están en voseo.
   Cambiar el default a neutro sería reescribir media app.
2. **El voseo se entiende en toda Hispanoamérica** y no ofende en España (se usa
   en TV y publicidad). Es el default regionally más seguro.
3. **La cátedra puede necesitar "usted"**: hay cursos de posgrado y contextos
   institucionales que lo exigen. Por eso la opción existe desde el día 1.

Lo que **no** se hace:

- **No** se不问 por qué país es la cátedra: la app se puede instalar en cualquier
  lado. El registro lo elige el usuario (y el admin puede fijar el default por
  configuración, §9).
- **No** se traducen los **materiales de la cátedra** (son del docente, en el
  idioma que el docente use).
- **No** se traduce el **contenido del modelo** (el modelo responde en el idioma
  del alumno, §4).
- **No** se traducen los **nombres de modelo / rutas / claves de API** (son
  identificadores técnicos).

### 2.3 Matriz de idiomas y registros

| Idioma de la interfaz | Carpeta de recursos | Estado |
|---|---|---|
| Español (default / fallback) | `composeResources/values/` | **En el milestone 1** |
| Inglés | `composeResources/values-en/` | Siguiente (no en el milestone 1) |
| Portugués (Brasil) | `composeResources/values-pt/` | Cuando haya una cátedra de referencia en Brasil |

| Registro | Sufijo en el catálogo | Ejemplo (clave `hint_api_key`) |
|---|---|---|
| Rioplatense (default) | *(sin sufijo)* | "Revisá la clave de la cátedra: puede estar vencida o mal copiada." |
| Tuteo | `_tu` | "Revisa la clave de la cátedra: puede estar vencida o mal copiada." |
| Formal | `_usted` | "Revise la clave de la cátedra: puede estar vencida o mal copiada." |

---

## 3. Mecanismo de i18n

### 3.1 Las tres opciones, evaluadas

| | **A. `compose.components.resources`** (`StringResource`) | **B. Objeto `Strings` con `var locale`** | **C. Catálogo propio `.properties` en `:core`** |
|---|---|---|---|
| Seguridad de tipos | ✅ acceso generado (`Res.strings.x`); un typo **no compila** | ⚠️ si las claves son `String`; ✅ si el objeto declara `val enviar: String` | ⚠️ claves `String` (mitigable con un enum) |
| Plurales | ✅ `<plurals>` + `pluralStringResource` | ❌ a mano | ❌ a mano |
| Fallback por locale | ✅ lo resuelve la librería (idioma → script → región → default) | ❌ a mano | ❌ a mano |
| Usable desde el **dominio** | ❌ `stringResource` es `@Composable`; `getString` es `suspend` | ✅ sí, en cualquier parte | ✅ sí, en cualquier parte |
| Usable desde la **UI** | ✅ `stringResource(Res.strings.x)` | ✅ `Strings.send` (pero hay que propagar el objeto) | ✅ con un `CompositionLocal` propio |
| Idioma en tiempo de ejecución | ⚠️ en CMP 1.7 **no hay API pública** para fijar el locale: hay que usar el `expect/actual` `LocalAppLocale` de la guía oficial de Kotlin | ✅ trivial (`var locale`) | ✅ trivial |
| API experimental | ⚠️ en 1.7.3 es `@ExperimentalResourceApi` (estable en 1.8) | ✅ estable | ✅ estable |
| Cero duplicación | ✅ un solo catálogo | ✅ un solo catálogo | ✅ un solo catálogo |
| Multi-módulo | ✅ (con `publicResClass = true`) | ✅ | ✅ (leer del classpath) |

### 3.2 Decisión

**Se adopta una solución híbrida, con una sola fuente de verdad:**

1. **La UI usa `compose.components.resources`** (`StringResource`): todo lo que el
   usuario lee. Es tipada, tiene plurales y el fallback por locale ya resuelto.
2. **El dominio no usa strings**: devuelve **códigos estructurados**. La UI los
   traduce con un `when` **exhaustivo** (§3.5). Esto resuelve el problema de
   "el mensaje de negocio está en el código de negocio" **sin** arrastrar Compose
   al módulo `:core`.
3. **La resolución de locale en runtime** se hace con el `expect/actual`
   `LocalAppLocale` de la guía oficial de Kotlin Multiplatform (§3.7), que es
   justamente el workaround que la propia JetBrains documenta para CMP 1.7.

> **Por qué no la opción B (objeto `Strings` con `var locale`)?** Porque obliga a
> mantener **dos** catálogos (el de la UI y el del dominio) o a inventar un puente
> que termina siendo el `when` exhaustivo de la opción 2 de todos modos, pero
> **sin** los plurales, sin los accesores generados y sin el fallback por locale.
> La opción B sólo gana en "cambiar el idioma en runtime sin tocar el SO", y eso
> se resuelve con una línea de `Locale.setDefault` (§3.7).

> **Por qué no la opción C (`.properties` propio)?** Porque obliga a reimplementar
> plurales, formateo de números, fallback por locale y validación de claves, y
> porque `compose.components.resources` ya es el camino oficial de CMP y va a estar
> estable en 1.8 (migrar es quitar un `@OptIn`).

### 3.3 Estructura de archivos

```
ui/
├── build.gradle.kts                     ← compose.resources { … }
└── src/
    ├── commonMain/
    │   ├── composeResources/
    │   │   ├── values/
    │   │   │   ├── strings.xml           ← español rioplatense (DEFAULT y FALLBACK)
    │   │   │   └── plurals.xml           ← plurales (citas, fuentes, alumnos…)
    │   │   ├── values-en/                ← (futuro) inglés
    │   │   │   └── strings.xml
    │   │   └── values-pt/                ← (futuro) portugués
    │   │       └── strings.xml
    │   └── kotlin/kitoru/ui/i18n/
    │       ├── LocalStrings.kt           ← CompositionLocals (idioma, registro)
    │       ├── FormText.kt               ← el resolver de los 3 registros
    │       ├── Formats.kt                ← expect: números y fechas
    │       └── DomainMessages.kt         ← el `when` exhaustivo (§3.5)
    └── desktopTest/kotlin/kitoru/ui/i18n/
        ├── CatalogConsistencyTest.kt    ← los sufijos _tu/_usted están pareados
        └── FormVariantsTest.kt
```

**Reglas de la estructura (importantes):**

- **Un archivo por idioma, sin carpetas por región.** No se usan `values-es-AR/`
  ni `values-419/`. Motivo: la resolución por locale de la librería (filtro
  idioma → script → región → default) puede devolver **más de un candidato** y
  terminar en el error *"Resource has more than one file"* si hay dos archivos con
  el mismo idioma y regiones distintas. Con un archivo por idioma la resolución
  es siempre inequívoca.
  → **La variante regional (voseo/tú/usted) NO se resuelve por locale**: se
  resuelve por la opción de **registro** (§3.6), que es explícita y determinista.
- **Nunca dos archivos con el mismo idioma.** Al agregar un idioma: carpeta
  `values-xx/` con **exactamente** los mismos *names* de `<string>` que `values/`.
- El archivo `values/` es **el fallback absoluto**: si el SO está en inglés y no
  hay `values-en/`, la app se ve en español (no en una mezcla con claves faltantes).

### 3.4 Configuración de Gradle

```kotlin
// ui/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    compilerOptions {
        // En CMP 1.7.3 la API de recursos es experimental; en 1.8 se quita esto.
        optIn.add("org.jetbrains.compose.resources.ExperimentalResourceApi")
    }
    // …
    commonMain.dependencies {
        implementation(compose.components.resources)   // ← el catálogo
        // …
    }
}

compose.resources {
    publicResClass = true                              // la clase se puede usar desde :desktop y :android
    packageOfResClass = "kitoru.ui.resources"          // → kitoru.ui.resources.Res
    generateResClass = always                          // se genera también sin recursos (evita errores de compilación)
}
```

La clase generada es `kitoru.ui.resources.Res`; los accesores son
`Res.strings.<name>` y `Res.plurals.<name>`.

### 3.5 Cómo se sacan los textos del dominio (el punto central)

**Regla:** *el dominio no produce frases; produce hechos. La frase se arma en la
capa de UI, en el idioma del usuario.*

#### 3.5.1 `ModelPolicy.validate()`

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt

/**
 * Un problema de validación de la configuración del modelo.
 *
 * Es **estructurado**, no una frase: el texto lo arma la capa de UI (que sabe el
 * idioma y el registro). Antes eran `String`s en español dentro del dominio.
 */
sealed interface ValidationIssue {
    /** Campo al que apunta el problema (para mover el foco al control). */
    val field: LockedField?

    data class TokensNotPositive(val value: Int) : ValidationIssue {
        override val field = LockedField.MAX_TOKENS
    }

    data class TemperatureOutOfRange(val value: Double) : ValidationIssue {
        override val field = LockedField.TEMPERATURE
    }

    data class TopKNotPositive(val value: Int) : ValidationIssue {
        override val field = LockedField.TOP_K
    }

    data object MissingBaseUrl : ValidationIssue {
        override val field = LockedField.BASE_URL
    }

    data class BaseUrlNotHttp(val value: String) : ValidationIssue {
        override val field = LockedField.BASE_URL
    }

    data object MissingModelName : ValidationIssue {
        override val field = LockedField.MODEL_NAME
    }
}

/** Problemas de la configuración (vacío = válida). */
fun validate(): List<ValidationIssue> = buildList {
    if (maxTokens <= 0) add(ValidationIssue.TokensNotPositive(maxTokens))
    if (temperature < 0.0 || temperature > 2.0) {
        add(ValidationIssue.TemperatureOutOfRange(temperature))
    }
    if (topK <= 0) add(ValidationIssue.TopKNotPositive(topK))
    if (mode == ModelMode.REMOTE) {
        if (remoteBaseUrl.isBlank()) {
            add(ValidationIssue.MissingBaseUrl)
        } else if (!remoteBaseUrl.trim().startsWith("http://") &&
            !remoteBaseUrl.trim().startsWith("https://")
        ) {
            add(ValidationIssue.BaseUrlNotHttp(remoteBaseUrl.trim()))
        } else if (modelName.isBlank()) {
            add(ValidationIssue.MissingModelName)
        }
    }
}
```

La UI lo traduce con un `when` **exhaustivo** sobre el `sealed interface`: si mañana
alguien agrega `ValidationIssue.TemperatureTooHigh`, **el compilador rompe** hasta
que exista la frase en el catálogo. Ese es el mecanismo anti-drift.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/i18n/DomainMessages.kt

/** Frases de los problemas del dominio, en el idioma y el registro del usuario. */
@Composable
fun ValidationIssue.localized(): String = when (this) {
    is ValidationIssue.TokensNotPositive ->
        formText(Res.strings.err_tokens_not_positive, Res.strings.err_tokens_not_positive_tu, Res.strings.err_tokens_not_positive_usted, value)
    is ValidationIssue.TemperatureOutOfRange ->
        formText(Res.strings.err_temperature_range, Res.strings.err_temperature_range_tu, Res.strings.err_temperature_range_usted, value)
    is ValidationIssue.TopKNotPositive ->
        formText(Res.strings.err_topk_not_positive, Res.strings.err_topk_not_positive_tu, Res.strings.err_topk_not_positive_usted, value)
    ValidationIssue.MissingBaseUrl ->
        formText(Res.strings.err_missing_base_url, Res.strings.err_missing_base_url_tu, Res.strings.err_missing_base_url_usted)
    is ValidationIssue.BaseUrlNotHttp ->
        formText(Res.strings.err_base_url_not_http, Res.strings.err_base_url_not_http_tu, Res.strings.err_base_url_not_http_usted)
    ValidationIssue.MissingModelName ->
        formText(Res.strings.err_missing_model_name, Res.strings.err_missing_model_name_tu, Res.strings.err_missing_model_name_usted)
}
```

> **Impacto en otros documentos.** `05-config-modelo.md` usa `policy.validate()`
> como `List<String>`; `01-navegacion.md` muestra el banner de validación. Con este
> cambio, ambos pasan a `List<ValidationIssue>` + `issue.localized()`. La lista de
> frases en español del doc 05 se mantiene **idéntica** (mismo texto, misma clave)
> para no romper lo ya diseñado.

#### 3.5.2 `LlmException`

Hoy `LlmException` **arma la frase** y el código HTTP:

```kotlin
// llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt  (actual)
LlmException(LlmErrorCode.CLAVE_INVALIDA, "$origin rechazó la clave (HTTP $status).")
```

El gateway decide el idioma, y mezcla frase + detalle técnico. Separación nueva:

```kotlin
class LlmException(
    val code: LlmErrorCode,
    /** Detalle técnico **sin traducir**: status HTTP, snippet del cuerpo, causa. */
    val detail: String = "",
    val retryable: Boolean = false,
    override val cause: Throwable? = null,
) : Exception(detail.ifBlank { code.name }, cause) {

    companion object {
        /** Clasifica una respuesta HTTP fallida a un [LlmException] (igual que antes). */
        fun fromHttp(status: Int, body: String, local: Boolean): LlmException {
            val origin = if (local) "llama-server" else "la API"
            val snippet = body.take(300)
            return when {
                status == 401 || status == 403 ->
                    LlmException(LlmErrorCode.CLAVE_INVALIDA, "$origin: HTTP $status. $snippet")
                status == 429 ->
                    LlmException(LlmErrorCode.SIN_CUOTA, "$origin: HTTP $status. $snippet", retryable = true)
                // … (misma tabla de estados que hoy)
            }
        }
    }
}
```

Y la traducción vive en la UI:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/i18n/DomainMessages.kt

/** Título + pista + detalle técnico de un error del gateway. */
@Composable
fun LlmException.localized(): LlmErrorText {
    val title = when (code) {
        LlmErrorCode.SIN_CUOTA -> stringResource(Res.strings.err_quota_title)
        LlmErrorCode.CLAVE_INVALIDA -> stringResource(Res.strings.err_bad_key_title)
        LlmErrorCode.TIMEOUT -> stringResource(Res.strings.err_timeout_title)
        LlmErrorCode.NO_CONFIGURADO -> stringResource(Res.strings.err_not_configured_title)
        LlmErrorCode.MODELO_NO_DISPONIBLE -> stringResource(Res.strings.err_model_unavailable_title)
        LlmErrorCode.CONTEXTO_AGOTADO -> stringResource(Res.strings.err_context_exhausted_title)
        LlmErrorCode.DESCARGA_FALLIDA -> stringResource(Res.strings.err_download_failed_title)
        LlmErrorCode.SERVIDOR_CAIDO -> stringResource(Res.strings.err_server_down_title)
        LlmErrorCode.CANCELADO -> stringResource(Res.strings.err_cancelled_title)
        LlmErrorCode.DESCONOCIDO -> stringResource(Res.strings.err_unknown_title)
    }
    val hint = when (code) { /* … un `when` por código, exhaustivo */ }
    return LlmErrorText(title = title, hint = hint, detail = detail, retryable = retryable)
}
```

> **Impacto en otros documentos.** El objeto `LlmErrorMessages` que propone
> `05-config-modelo.md` (en `:llm-api`, con frases en español) **queda reemplazado**
> por este `when` en `:ui`. Si se quiere mantener el objeto en `:llm-api` para
> reutilizarlo desde `:desktop`, se le cambia la firma a
> `fun describe(code: LlmErrorCode, detail: String): LlmErrorText` con las frases
> **sacadas del catálogo**; la recomendación es la versión en `:ui` (el catálogo es
> de la UI).

#### 3.5.3 `CitationVerifier`

```kotlin
// backend/src/commonMain/kotlin/rag/engine/CitationVerifier.kt

/** Por qué una cita no está respaldada. Estructurado, no una frase. */
sealed interface CitationReason {
    /** El número citado no corresponde a ninguna fuente recuperada. */
    data class SourceNotFound(val index: Int) : CitationReason

    /** La fuente está vacía: no hay nada que respalde la cita. */
    data object EmptySource : CitationReason

    /** La cita menciona términos que no aparecen en la fuente. */
    data class InventedTerms(val terms: List<String>) : CitationReason

    /** La cita es una paráfrasis demasiado lejana (cobertura < 50 %). */
    data class LowCoverage(val percent: Int) : CitationReason
}

// En CitationCheck:
data class CitationCheck(
    val index: Int,
    val label: String,
    val snippet: String,
    val supported: Boolean,
    /** Por qué no está respaldada (vacío / null si sí lo está). */
    val reason: CitationReason? = null,
)
```

`CitationCheck` **no** es `@Serializable` (lo serializado es `CitationStats`), así
que el cambio no toca ningún JSON. La UI traduce el motivo con otro `when`
exhaustivo.

#### 3.5.4 Los `label` de los enums

`ModelMode(val label: String)`, `LockedField(val label: String)`, `CourseRole(val label: String)`,
`MaterialKind(val label: String)`, `ChatVisibility(val label: String, val description: String)`

**Problema:** el `label` es una **propiedad del constructor** ⇒ está en el JSON
serializado (`{"type":"REMOTE","label":"Servidor de la cátedra"}`).

**Solución:** sacar `label`/`description` del constructor y dejar el enum como dato
puro; la etiqueta vive en el catálogo.

```kotlin
@Serializable
enum class ModelMode { REMOTE, LOCAL }

@Serializable
enum class CourseRole { TITULAR, AYUDANTE, ALUMNO }
```

**Impacto en los JSON guardados:**

- `KitoruJson` tiene `ignoreUnknownKeys = true` ⇒ al **leer** un archivo viejo, la
  clave `label` desconocida se **ignora**: los archivos existentes se siguen
  abriendo sin cambios. ✓
- Al **escribir**, el JSON nuevo ya no lleva `label`. ✓ (es lo que queremos).
- **Verificación obligatoria**: agregar `core/src/commonTest/kotlin/kitoru/domain/LegacyJsonTest.kt`
  con un JSON viejo hardcodeado (`{"type":"REMOTE","label":"Servidor de la cátedra"}`)
  que debe deserializar sin excepción. El test fija el formato exacto y evita
  sorpresas si la forma de serializar enums con parámetros cambia.

> Ojo: `ChatVisibility.label`/`description` y `CourseRole.label` se usan en la
> **analítica** que ve el docente (etiquetas de ejes, Leyenda). Con el cambio, esas
> etiquetas salen del catálogo: el ViewModel de analítica resuelve los `String` con
> `stringResource` **antes** de pasarlos al componente de gráfico. Si el gráfico
> necesita `String` (no `StringResource`), se resuelven en el composable, no en el
> ViewModel.

#### 3.5.5 Resumen de los cambios en el dominio

| Archivo | Cambio | ¿Toca JSON? |
|---|---|---|
| `core/.../ModelPolicy.kt` | `validate(): List<ValidationIssue>`; `label` fuera del constructor | No (el `label` de `ModelMode`/`LockedField` sí: se ignora al leer) |
| `core/.../Roles.kt` | `CourseRole`, `ChatVisibility` sin `label`/`description` | Sí (ignorados al leer) |
| `core/.../Course.kt` | `MaterialKind` sin `label` | Sí (ignorados al leer) |
| `llm-api/.../LlmErrors.kt` | `LlmException(code, detail, retryable)`; sin frases | No |
| `backend/.../CitationVerifier.kt` | `reason: CitationReason?` | No |

### 3.6 El registro (voseo / tú / usted) en el catálogo

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/i18n/FormText.kt

/** Registro con el que la app le habla al usuario. */
enum class AppForm { RIOPLATENSE, TU, USTED }

/**
 * Resuelve una frase según el registro activo.
 *
 * El catálogo tiene la variante rioplatense en la clave base y las otras dos con
 * sufijo (`_tu`, `_usted`). Las tres variantes **siempre** existen juntas: lo
 * verifica `CatalogConsistencyTest`.
 */
@Composable
fun formText(
    rioplatense: StringResource,
    tu: StringResource,
    usted: StringResource,
    vararg args: Any,
): String = when (LocalAppForm.current) {
    AppForm.RIOPLATENSE -> stringResource(rioplatense, *args)
    AppForm.TU -> stringResource(tu, *args)
    AppForm.USTED -> stringResource(usted, *args)
}
```

Convenciones del catálogo:

- **Prefijos de clave** (agrupan y permiten revisarlas):
  | Prefijo | Qué es |
  |---|---|
  | `nav_`, `chat_`, `teacher_`, `student_`, `settings_` | Pantallas y secciones |
  | `act_` | Acciones (botones, ítems de menú) |
  | `err_` | Errores y validaciones |
  | `hint_` | Ayudas y explicaciones |
  | `empty_` | Estados vacíos |
  | `a11y_` | **Textos para el lector de pantalla** (`contentDescription`, live regions) |
  | `plur_` (en `plurals.xml`) | Cantidades |
- **Los sustantivos y los imperativos son invariantes** ("Guardar", "Copiar",
  "Enviar", "Configuración"): no necesitan variante. ** Sólo cambian las formas
  verbales de segunda persona (~35 claves) y algunos sustantivos regionalismos
  ("celular" / "móvil" → se usa "celular" en la variante rioplatense y "móvil" en las
  otras dos; se evita el regionalismo y se usa un término neutro: **"teléfono"**).

### 3.7 Idioma de la app en tiempo de ejecución

`compose.components.resources` en CMP 1.7 **no expone una API pública** para fijar
el locale: la librería lee el locale del SO. La guía oficial de Kotlin Multiplatform
("Manage local resource environment") propone exactamente este patrón `expect/actual`,
que es el que se adopta:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/i18n/LocalAppLocale.kt
package kitoru.ui.i18n

import androidx.compose.runtime.*
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Locale de la app ( BCP 47: "es", "en", "pt").
 *
 * `null` = seguir el locale del sistema. La implementación real está en
 * `desktopMain` / `androidMain` (el patrón oficial de Kotlin para CMP 1.7).
 */
expect object LocalAppLocale {
    /** Locale activo, como tag BCP 47. */
    val current: String

    /** Fija el locale de la app (`null` = volver al del sistema). */
    infix fun provides(value: String?): ProvidedValue<*>
}

/**
 * Locale vigente, publicado por el `actual`.
 *
 * Es el que lee la librería de recursos cuando recompone, y el que usa
 * [formatNumber] / [formatDateTime] para formatear.
 */
val LocalAppLocaleState: ProvidableCompositionLocal<String> =
    staticCompositionLocalOf { "es" }
```

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/i18n/LocalAppLocale.desktop.kt
actual object LocalAppLocale {
    private var systemLocale: Locale? = null

    actual val current: String
        @Composable get() = LocalAppLocaleState.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (systemLocale == null) systemLocale = Locale.getDefault()
        val new = if (value == null) systemLocale!! else Locale.forLanguageTag(value)
        Locale.setDefault(new)
        return LocalAppLocaleState.provides(new.toLanguageTag())
    }
}
```

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/i18n/LocalAppLocale.android.kt
actual object LocalAppLocale {
    private var default: Locale? = null

    @Composable
    actual val current: String
        get() = LocalAppLocaleState.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val ctx = LocalContext.current
        if (default == null) default = Locale.getDefault()
        val new = if (value == null) default!! else Locale.forLanguageTag(value)
        Locale.setDefault(new)
        val conf = LocalConfiguration.current
        conf.setLocale(new)
        ctx.resources.updateConfiguration(conf, ctx.resources.displayMetrics)
        return LocalConfiguration.provides(conf)
    }
}
```

Y el contenedor raíz de la app:

```kotlin
@Composable
fun KitoruApp(settings: KitoruSettings, …) {
    var customLocale by remember { mutableStateOf(settings.language?.tag) }
    CompositionLocalProvider(
        LocalAppLocale provides customLocale,
        LocalAppForm provides settings.form,
    ) {
        key(customLocale) {           // fuerza el re-resolución de los recursos
            KitoruTheme(...) { KitoruNavHost(...) }
        }
    }
}
```

> **Nota de CMP 1.7.3**: el recurso se resuelve con el locale **del sistema**; el
> `provides` de arriba cambia `Locale.getDefault()` (desktop) y la `Configuration`
> (Android), que es de donde la librería lee. Si en una versión concreta no
>ailandar, el plan B es **no** usar las carpetas por locale y resolver el idioma a
> mano con un `CompositionLocal` + un mapa de `StringResource` por idioma (es lo que
> hace la opción B). Se deja anotado como el primer punto a verificar con un test
> de integración en `:desktop`.

### 3.8 Números, fechas y unidades

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/i18n/Formats.kt

/** Número con el formato del locale (1.234,5 en es; 1,234.5 en en). */
expect fun formatNumber(value: Double, decimals: Int = 1): String

/** Fecha y hora corta en el formato del locale. */
expect fun formatDateTime(epochMs: Long): String
```

- Los `actual` viven en **`jvmCommonMain`** (desktop **y** Android son JVM): usan
  `java.text.NumberFormat` y `java.text.DateFormat` con el `Locale` activo.
- **No** se usa `java.time`: `minSdk = 24` y `java.time` necesita API 26 (o
  *core library desugaring*). `java.text.DateFormat` + `java.util.Calendar` funcionan
  desde API 1 y no obligan a tocar el build de Android.
- Los **porcentajes** de la analítica (`supportRate`) se formatean con
  `formatNumber(x * 100, 0)` + "%" (el separador decimal es el del locale).
- La hora del chat se muestra como **hora corta** ("14:32"); la fecha completa
  aparece en el tooltip y en el `contentDescription` del mensaje.

### 3.9 Plurals

```xml
<!-- ui/src/commonMain/composeResources/values/plurals.xml -->
<plurals name="plur_citas_sin_respaldo">
    <item quantity="one">%1$d cita sin respaldo</item>
    <item quantity="other">%1$d citas sin respaldo</item>
</plurals>
```

```kotlin
val n = pluralStringResource(Res.plurals.plur_citas_sin_respaldo, count, count)
```

Los plurales en español tienen `one`/`other` (y `many` a partir de 1e6, que no
aparece en esta app). **No** se usa `zero`.

### 3.10 Checklist para agregar un idioma nuevo

1. Crear `ui/src/commonMain/composeResources/values-xx/strings.xml` con **exactamente**
   los mismos *names* que `values/strings.xml` (copiar y traducir).
2. Traducir también `plurals.xml`.
3. Agregar `xx` a `KitoruSettings.language` (enum + etiqueta).
4. Correr `CatalogConsistencyTest`: verifica que no falte ninguna clave y que las
   variantes `_tu`/`_usted` estén pareadas.
5. Nada más: no hay código que tocar (el idioma es un tag BCP 47).

---

## 4. El idioma del modelo

### 4.1 La regla

> **El modelo responde en el idioma en que escribió el alumno.** Si el alumno
> escribe en inglés, responde en inglés; si escribe en español, en español.
> La cátedra puede fijar el idioma (por ejemplo, una cátedra de ^^) y el alumno
> puede pedir que le traduzcan.

Esto **ya está en el prompt** (regla 5 de `PromptPresets.DEFAULT_COURSE_PMPT`:
*"Responde en el mismo idioma de la pregunta"*). Lo que falta es **garantizarlo**:

- Un modelo chico (Phi-4-mini, Qwen3-8B) con la regla suelta cambia de idioma a
  mitad de la respuesta o responde en inglés aunque la pregunta sea en español.
- El prompt de la cátedra puede estar **bloqueado** (`LockedField.SYSTEM_PROMPT`)
  y puede **no** traer la regla.

Por eso el diseño es **detectar + forzar**:

### 4.2 Detección (determinista, sin red)

```kotlin
// core/src/commonMain/kotlin/kitoru/i18n/LanguageDetector.kt
package kitoru.i18n

/** Idiomas que la app sabe detectar y forzar. */
enum class AppLanguage(val tag: String, val ownName: String) {
    SPANISH("es", "español"),
    ENGLISH("en", "English"),
    PORTUGUESE("pt", "português"),
    UNKNOWN("und", ""),
}

/**
 * Detecta el idioma de un texto corto contando palabras funcionales.
 *
 * No usa red ni modelo: es una función pura y testeable. El objetivo no es
 * 100 % de acierto (imposible con dos palabras) sino **no fallar nunca de
 * forma ruidosa**: si la señal es débil devuelve [AppLanguage.UNKNOWN] y el
 * llamador decide el fallback (§4.4).
 */
object LanguageDetector {

    /** Mínimo de señales para dar el idioma por seguro. */
    const val MIN_SIGNALS = 2

    /** Si el mejor idioma no supera al segundo por este factor, no se decide. */
    const val MARGIN = 1.5

    fun detect(text: String): AppLanguage {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return AppLanguage.UNKNOWN
        if (isMostlyCode(tokens)) return AppLanguage.UNKNOWN
        val scores = AppLanguage.entries
            .filter { it != AppLanguage.UNKNOWN }
            .associateWith { lang -> tokens.count { it in STOPWORDS[lang] } }
        val best = scores.maxBy { it.value }
        val second = scores.filterKeys { it != best.key }.maxBy { it.value }
        return if (best.value >= MIN_SIGNALS && best.value > second.value * MARGIN) best.key
        else AppLanguage.UNKNOWN
    }

    private val TOKEN = Regex("[\\p{L}\\p{N}']+")

    private fun tokenize(text: String): List<String> =
        TOKEN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 }
            .toList()

    /** ¿El texto es código o fórmula? (entonces el idioma no importa). */
    private fun isMostlyCode(tokens: List<String>): Boolean {
        val codeish = tokens.count { t ->
            t.length <= 2 || t.any { it.isDigit() } || t.contains('_')
        }
        return codeish * 2 > tokens.size
    }

    /** Palabras funcionales por idioma (muestra mínima viable). */
    private val STOPWORDS: Map<AppLanguage, Set<String>> = mapOf(
        AppLanguage.SPANISH to setOf(
            "de", "la", "que", "el", "en", "los", "del", "las", "por", "para", "con",
            "una", "un", "es", "se", "no", "su", "al", "lo", "como", "qué", "cuál",
            "cuáles", "cuándo", "dónde", "por qué", "porque", "pero", "más", "muy",
            "entre", "sobre", "también", "explica", "diferencia", "definición",
        ),
        AppLanguage.ENGLISH to setOf(
            "the", "of", "and", "to", "is", "in", "it", "that", "for", "how", "what",
            "why", "which", "when", "where", "are", "was", "were", "be", "can", "do",
            "does", "difference", "define", "definition", "explain", "between", "not",
            "you", "we", "i", "this", "with", "from", "but", "more", "very",
        ),
        AppLanguage.PORTUGUESE to setOf(
            "de", "que", "não", "para", "com", "uma", "os", "no", "se", "como", "por",
            "em", "é", "do", "da", "ao", "dos", "das", "qual", "quais", "quando",
            "onde", "por que", "porque", "mas", "mais", "muito", "entre", "sobre",
            "também", "explique", "diferença", "definição",
        ),
    )
}
```

Test (`:core/src/commonTest`): "hola" → `UNKNOWN`; "¿qué es una función deheurística?"
→ `SPANISH`; "what is a hash function" → `ENGLISH`; "defina hash" → `SPANISH`;
"```kotlin\nfun main() {}\n```" → `UNKNOWN`; "com queprecision" (mezcla) → el que gane.

### 4.3 Forzaje: la directiva de idioma

La directiva **no** se mete dentro del prompt de la cátedra (que puede estar
bloqueado y es del docente): viaja como **un `system` message aparte, justo antes
del turno del usuario**, para que sea lo último que el modelo lee.

```kotlin
// core/src/commonMain/kotlin/kitoru/i18n/LanguageDirective.kt
package kitoru.i18n

/**
 * La instrucción que se le manda al modelo para que responda en un idioma.
 *
 * Se escribe **en el idioma destino** (los modelos obedecen mejor una
 * instrucción en su propio idioma) y menciona el registro cuando el destino es
 * español, para que la respuesta tenga el mismo tono que la interfaz.
 */
object LanguageDirective {

    /**
     * @param lang idioma pedido.
     * @param form registro esperado (sólo aplica al español).
     * @param codeOnly la pregunta es código o fórmula: se pide que responda en el
     *   idioma de la conversación, no en un idioma fijo.
     */
    fun forLanguage(lang: AppLanguage, form: AppForm, codeOnly: Boolean = false): String =
        when (lang) {
            AppLanguage.SPANISH -> when {
                codeOnly -> "Responde en español, en el mismo idioma de la conversación."
                form == AppForm.RIOPLATENSE -> "Respondé en español rioplatense (usá 'vos')."
                form == AppForm.USTED -> "Respondé en español, usando 'usted'."
                else -> "Respondé en español, con tuteo."
            }
            AppLanguage.ENGLISH -> "Reply in English."
            AppLanguage.PORTUGUESE -> "Responda em português."
            AppLanguage.UNKNOWN -> ""
        }
}
```

### 4.4 La cadena de decisión completa

```
1. ¿Course.answerLanguage está fijado (≠ AUTO)?      → ese idioma, sin detección.
2. Detectar el idioma de la pregunta del alumno.
   ├─ detectado              → ese idioma.
   └─ UNKNOWN:
        3. ¿es código/fórmula?  → "codeOnly" (idioma de la conversación).
        4. ¿hay turnos previos? → el idioma del último mensaje del alumno
                                 (detectado o el que se le pidió).
        5. ¿se detectó algo?   → ese idioma.
        6. si no, el idioma de la app (si es es/en/pt) → ese idioma.
        7. si no, NO se manda directiva: manda la regla 5 del prompt de la cátedra.
```

Se implementa en `:backend`, en el punto donde ya se arman los mensajes
(`RagEngine.prepare` / el `AgentLoop`):

```kotlin
// backend/src/commonMain/kotlin/rag/engine/LanguagePolicy.kt

/**
 * Decide el idioma de la respuesta y produce la directiva.
 *
 * @param course la cátedra (puede fijar el idioma).
 * @param question la pregunta actual del alumno.
 * @param history los turnos previos (para inferir el idioma de la conversación).
 * @param appLanguage el idioma de la interfaz (último recurso).
 */
data class LanguageDecision(
    val language: AppLanguage,
    val directive: String,
    val detected: Boolean,
)

object LanguagePolicy {
    fun decide(
        course: Course,
        question: String,
        history: List<ChatMessage>,
        appLanguage: AppLanguage,
        appForm: AppForm,
    ): LanguageDecision { … }
}
```

Y el mensaje se inserta así (en `RagEngine.prepare`):

```kotlin
val messages = buildList {
    add(ChatMessage("system", systemPrompt()))
    addAll(history.takeLast(cfg.historyTurns * 2))
    if (decision.directive.isNotBlank()) {
        add(ChatMessage("system", decision.directive))   // ← la directiva, al final
    }
    add(ChatMessage("user", "CONTEXTO:\n$context\n\nPREGUNTA:\n$question"))
}
```

> La directiva es un `system` **extra**, no modifica el prompt de la cátedra: el
> docente sigue siendo dueño de su prompt y el alumno no puede eludir la
> configuración bloqueada.

### 4.5 Verificación posterior (P2, no bloqueante)

Cuando llega la respuesta, se le detecta el idioma:

- Si es **distinto** al pedido → se muestra un chip no bloqueante:
  *"La respuesta llegó en inglés"* + botón **"Pedirla en español"**, que reenvía
  la misma pregunta con la directiva forzada.
- Si coincide → nada (no se molesta al usuario con un aviso innecesario).

Es una red de seguridad barata: el usuario siempre tiene forma de obtener la
respuesta en su idioma.

### 4.6 Qué **no** se traduce (contrato con el modelo)

| Elemento | ¿Se traduce? | Por qué |
|---|---|---|
| `CONTEXTO:` / `PREGUNTA:` | **No** | Son marcadores del prompt: el modelo los reconoce y la vista previa del docente (doc 04) muestra la forma exacta. Cambiarlos rompe la comparación con la vista previa y con los prompts de la cátedra ya escritos. |
| `(sin resultados en el material de la cátedra)` | **No** | Mismo motivo: es una cadena estable que el modelo ve siempre. |
| Los `[n]` de las citas | **No** | Contrato con `CitationVerifier` (`MARKER = \[(\d{1,3})]`). |
| Las etiquetas de las fuentes (`material: unidad-1.md`) | **No** | Vienen del nombre del archivo. |
| El prompt de la cátedra | **Sí, si el docente lo escribe así** | Es contenido del docente: la app lo muestra como está. |

---

## 5. Accesibilidad en Compose: el contrato

### 5.1 Lo que hay que hacer desde el día 1 (checklist por pantalla)

Cada pantalla nueva se revisa contra esta lista antes de darse por terminada:

- [ ] Todo `Icon` tiene `contentDescription` **explícito** (o `null` si es decorativo).
- [ ] Todo elemento interactivo tiene un **nombre accesible**: texto visible, o
      `contentDescription`, o `Modifier.semantics { contentDescription = … }`.
- [ ] Los estados (abierto/cerrado,checked, en curso/ok/falló) se exponen con
      `stateDescription`.
- [ ] El título de la pantalla y los títulos de sección llevan `heading()`.
- [ ] Contraste ≥ 4.5:1 en todo texto y ≥ 3:1 en iconos/bordes que significan algo.
- [ ] Foco visible en escritorio (anillo de foco de 2 dp, ≥ 3:1 contra el fondo).
- [ ] Orden de tabulación = orden de lectura (navegación → contenido → acciones).
- [ ] `Escape` cierra diálogos y hojas; no se traga el foco del campo de texto.
- [ ] Todos los tamaños de fuente en `sp`; ninguna altura fija en un contenedor de texto.
- [ ] Los objetivos interactivos: ≥ 48 dp en Android, ≥ 40 dp en escritorio.
- [ ] Las animaciones responden a "reducir movimiento".
- [ ] Los estados vacíos y los errores dicen **qué pasó** y **qué hacer**.

### 5.2 `contentDescription`

Reglas:

1. **Descriptivo y accionable**: "Enviar mensaje", no "Enviar".
2. **Sin el nombre del icono**: si el ícono es una lupa, la descripción es
   "Buscar en el material", no "Lupa".
3. **`null` sólo para decorativos**: separadores, flechas dentro de un texto que ya
   se lee, iconos junto a una etiqueta visible.
   ⚠️ Regla de Compose: cuando un `Icon` está **dentro** de un `Button` con texto,
   el `Icon` debe llevar `contentDescription = null` (el texto del botón ya es el
   nombre); cuando el botón **sólo** tiene ícono, el `Icon` lleva la descripción.
4. **Las descripciones largas van en la UI, no en el `contentDescription`**: si un
   control necesita explicarse, es un `Text` de ayuda visible u oculto por
   `semantics`, no una descripción de 3 líneas.

Catálogo: las descripciones viven en el catálogo con prefijo `a11y_`:

```xml
<string name="a11y_send">Enviar mensaje</string>
<string name="a11y_stop_generation">Detener la respuesta</string>
<string name="a11y_attach_material">Adjuntar material de la cátedra</string>
<string name="a11y_citation_verified">Citas verificadas</string>
<string name="a11y_citation_unsupported">Citas sin respaldo</string>
<string name="a11y_thread_menu">Opciones de la conversación</string>
```

Envoltorio opcional para no olvidarse:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/a11y/KitoruIconButton.kt

/**
 * `IconButton` con descripción obligatoria por parámetro de tipo.
 *
 * Obliga a que el llamador pase la descripción: no hay forma de "olvidarse"
 * (el compilador no deja llamar `KitoruIconButton` sin `a11yDescription`).
 */
@Composable
fun KitoruIconButton(
    a11yDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Box(Modifier.semantics { contentDescription = a11yDescription }) { content() }
    }
}
```

### 5.3 `Modifier.semantics`: `stateDescription`, `heading()`, `role`, `liveRegion`

| Propiedad | Cuándo | Ejemplo en Kitoru |
|---|---|---|
| `contentDescription` | Elemento no textual (ícono, control) | botón de enviar |
| `stateDescription` | El estado **no** se ve en el texto (o se ve pero el lector necesita la frase) | "expandida" / "replegada"; "en curso" / "terminó" / "falló"; "activado" / "desactivado" |
| `heading()` | Título de pantalla, título de sección, **rotulo de turno del chat** | "Cátedra IA-2026-1", "Respuesta de Kitoru" |
| `role = Role.X` | Elemento custom que no es un componente Material | `Role.Button` en la tarjeta de herramienta, `Role.Checkbox` |
| `liveRegion = Polite` | Cambios que hay que anunciar **una vez** | estado del chat, errores no bloqueantes |
| `liveRegion = Assertive` | Sólo errores que interrumpen (uso restringido) | banner de error de conexión |
| `clearAndSetSemantics { }` | Quitar por completo la semántica de un subtree decorativo | gradiente de fondo, separador con dibujo |

Ejemplo (tarjeta de herramienta del chat):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ToolCallCard.kt

@Composable
fun ToolCallCard(call: ToolCallStats, expanded: Boolean, onToggle: () -> Unit) {
    val status = when (call.ok) {
        null -> stringResource(Res.strings.tool_status_running)     // "en curso"
        true -> stringResource(Res.strings.tool_status_ok)         // "terminó correctamente"
        false -> stringResource(Res.strings.tool_status_failed)    // "falló"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = status
                role = Role.Button
            }
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Build, contentDescription = null)   // decorativo
        Text(stringResource(Res.strings.tool_label, call.name))  // "Herramienta: crear archivo"
        Text(status)
    }
}
```

El lector de pantalla lee: *"Herramienta: crear archivo — botón — estado: en curso"*.

### 5.4 Contraste mínimo 4.5:1

**El contraste no se calcula "a ojo": lo calcula el tema y lo verifica un test.**

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/theme/Contrast.kt
package kitoru.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Luminancia relativa WCAG 2.1 sobre sRGB. */
fun relativeLuminance(c: Color): Double {
    fun lin(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** Razón de contraste WCAG entre dos colores opacos (1.0 … 21.0). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * Devuelve el color de texto (negro o blanco) que alcanza [minRatio] contra
 * [background].
 *
 * Si ninguno de los dos llega, se **ajusta el fondo** mezclándolo con blanco o
 * negro en pasos de 5 % hasta que alguno llegue. Nunca devuelve un par que no
 * cumpla el mínimo.
 */
fun onColorFor(background: Color, minRatio: Double = 4.5): Pair<Color, Color> {
    var bg = background
    repeat(24) {
        val black = contrastRatio(bg, Color.Black)
        val white = contrastRatio(bg, Color.White)
        if (black >= minRatio) return bg to Color.Black
        if (white >= minRatio) return bg to Color.White
        // El acento es claro ⇒ oscurecer; es oscuro ⇒ aclarar.
        bg = if (relativeLuminance(background) > 0.35) lerp(bg, Color.Black, 0.05f)
        else lerp(bg, Color.White, 0.05f)
    }
    return bg to Color.White
}
```

Test (`:ui/src/commonTest`):

```kotlin
class ContrastTest {
    @Test
    fun `el esquema claro cumple 4_5_1 en todos los pares de texto`() {
        val scheme = KitoruColorScheme.light(AccentColor.INDIGO)
        assertTextContrast(scheme)
    }

    @Test
    fun `el esquema oscuro cumple 4_5_1 en todos los pares de texto`() {
        val scheme = KitoruColorScheme.dark(AccentColor.INDIGO)
        assertTextContrast(scheme)
    }

    @Test
    fun `todos los acentos predefinidos llegan a 4_5_1`() {
        for (accent in AccentColor.entries) {
            val (primary, onPrimary) = onColorFor(accent.color)
            assertTrue(contrastRatio(primary, onPrimary) >= 4.5, "acento ${accent.name}")
        }
    }
}

private fun assertTextContrast(s: ColorScheme) {
    val pairs = listOf(
        s.onPrimary to s.primary,
        s.onSecondary to s.secondary,
        s.onTertiary to s.tertiary,
        s.onBackground to s.background,
        s.onSurface to s.surface,
        s.onSurfaceVariant to s.surface,
        s.onPrimaryContainer to s.primaryContainer,
        s.onSecondaryContainer to s.secondaryContainer,
        s.onTertiaryContainer to s.tertiaryContainer,
        s.onError to s.error,
        s.onErrorContainer to s.errorContainer,
    )
    for ((fg, bg) in pairs) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(ratio >= 4.5, "contraste $ratio entre $fg y $bg")
    }
    // Contraste no textual (bordes, iconos con significado): 3:1
    assertTrue(contrastRatio(s.outline, s.surface) >= 3.0)
}
```

Además, **reglas de uso** que el test no puede verificar:

- El color **nunca** es el único portador de información: el badge de citas lleva
  ícono + texto; el estado de la herramienta lleva texto; los errores llevan ícono.
- Texto deshabilitado: `onSurface` al 38 % ⇒ **no** cumple 4.5:1. Los controles
  deshabilitados **no** necesitan cumplir 4.5:1 (WCAG 1.4.3 excluye los
  deshabilitados), pero **sí** deben ser legibles: se usa `onSurface.copy(alpha = 0.5f)`
  y el componente sigue siendo identificable por su etiqueta.
- El **anillo de foco** necesita ≥ 3:1 contra el fondo y ≥ 3:1 contra el color del
  componente enfocado (WCAG 2.4.11 / 1.4.11).

### 5.5 Foco visible y orden de tabulación (escritorio)

Compose **no** dibuja un indicador de foco en escritorio: hay que hacerlo.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/a11y/FocusRing.kt

/**
 * Anillo de foco visible para escritorio.
 *
 * Se dibuja con `drawBehind` (no con `border`) para no alterar el layout, y sólo
 * aparece cuando el elemento tiene el foco del teclado.
 *
 * @Composable porque lee `MaterialTheme` y porque necesita estado propio para el
 * `onFocusChanged`.
 */
@Composable
fun Modifier.kitoruFocusRing(
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 2.dp,
    corner: Dp = 6.dp,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            if (focused) {
                drawRoundRect(
                    color = color,
                    size = size,
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(width = width.toPx()),
                )
            }
        }
}
```

> El anillo se dibuja **alrededor** del componente, no dentro: si el componente
> tiene su propio fondo, un borde interior quedaría tapado. Para los controles de
> Material hay que **desactivar el indicador por defecto** de M3 en escritorio
> (M3 no lo dibuja) y usar este anillo.

**Orden de tabulación.** Compose recorre los focusables **en el orden del árbol de
composición**. Por lo tanto:

- El orden del **layout** es el orden del **tab**: barra de navegación → contenido →
  barra de acciones. Si la barra de acciones está en el `Row` de abajo pero el
  contenido está antes en el árbol, el tab va primero al contenido. **La estructura
  del layout tiene que coincidir con el orden de lectura.**
- Las barras de herramientas usan `Modifier.focusGroup()` para que el Tab **cicle
  dentro** del grupo en vez de saltar a toda la pantalla:
  ```kotlin
  Row(Modifier.focusGroup()) { /* botones de la barra */ }
  ```
- Las listas largas (`LazyColumn` de hilos, de alumnos) usan `Modifier.focusGroup()`
  + `Modifier.focusRestorer()` para que el foco vuelva al ítem al salir de la lista.
- `Modifier.focusRequester()` + `focusRequester.requestFocus()` para mover el foco
  programáticamente (al abrir un diálogo, al enviar un mensaje, al navegar).

**En Android** el foco por teclado existe pero el TalkBack no lo usa; el mismo
`focusGroup()` sirve para los lectores de pantalla que sí lo usan (Switch Access).

### 5.6 Tamaño interactivo mínimo

| Plataforma | Mínimo | Cómo se garantiza |
|---|---|---|
| Android | **48 × 48 dp** | Material3 ya lo aplica vía `LocalMinimumInteractiveComponentSize` (48 dp por defecto) a `Button`, `IconButton`, `Checkbox`, `Switch`… **Los clickables propios hay que ampliarlos**: `Modifier.minimumInteractiveComponentSize()`. |
| Escritorio | **40 × 40 dp** (mínimo WCAG 2.2: 24 × 24 CSS px) | Los componentes M3 miden 40 dp (`IconButton`). Los clickables propios: `Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)`. |

```kotlin
// Acciones de la burbuja del chat (Kitoru): botones propios, no Material.
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(
        onClick = onCopy,
        modifier = Modifier
            .minimumInteractiveComponentSize()   // 48 dp en Android
            .heightIn(min = 40.dp),              // 40 dp en escritorio
    ) { Text(stringResource(Res.strings.act_copy)) }
}
```

> `Modifier.minimumInteractiveComponentSize()` (Material3 1.3, el que usa CMP 1.7.3)
> reserva 48 dp **sólo en Android**: en escritorio el `LocalMinimumInteractiveComponentSize`
> es `Dp.Unspecified` y no hace nada. Por eso el `heightIn(min = 40.dp)` explícito.

**Contraste de tamaño:** en el chat, los botones "Copiar / Editar / Borrar" del
mensaje son pequeños ⇒ se agrupan en un menú `⋮` (un solo objetivo) en vez de
tres de 32 dp.

### 5.7 Tipografía escalable

1. **Todos los tamaños de fuente en `sp`.** Nunca `dp`, nunca `Int` de px.
2. **Escala de la app** (escritorio no tiene escala del SO): `KitoruSettings.fontScale`
   ∈ {1.0, 1.15, 1.3, 1.5, 2.0}. Se aplica **una sola vez**, en el tema:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruTheme.kt

/** Multiplica todos los tamaños de la tipografía (y los interlineados). */
fun scaledTypography(base: Typography, scale: Float): Typography = Typography(
    displayLarge = base.displayLarge.scaled(scale),
    displayMedium = base.displayMedium.scaled(scale),
    // … todas
)

private fun TextStyle.scaled(scale: Float): TextStyle =
    copy(
        fontSize = fontSize * scale,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
```

3. **Reglas para que no se rompa con fuente grande** (WCAG 1.4.4):

| Regla | Bien | Mal |
|---|---|---|
| Alturas | `Modifier.heightIn(min = 40.dp)` | `Modifier.height(40.dp)` sobre texto |
| Anchos | `Modifier.widthIn(max = 640.dp)` | `Modifier.width(200.dp)` sobre un párrafo |
| Líneas | `maxLines = 2, overflow = Ellipsis` en **texto secundario** | `maxLines = 1` en títulos o mensajes |
| Layout | `FlowRow` para chips y botones | `Row` horizontal con 5 botones |
| Columnas | `Modifier.weight(1f)` en el texto, ancho fijo en el control | `Modifier.width(120.dp)` en el texto |
| Tablas | Filas que se envuelven, celdas con `widthIn(min/max)` | `Row` con `weight` en columnas de datos |
| Iconos | `Modifier.size(20.dp)` junto a `Text` (el ícono no crece) | ícono que crece con la fuente y empuja el texto |

4. **Prueba de layout**: un test de Compose que renderiza la pantalla del chat y la
   de preferencias con `fontScale = 2.0` y verifica que **no hay texto recortado**
   ni desbordamiento horizontal:

```kotlin
// ui/src/desktopTest/kotlin/kitoru/ui/a11y/LargeFontTest.kt
@OptIn(ExperimentalTestApi::class)
@Test
fun `el chat no se rompe con fuente al 200 por ciento`() {
    composeRule.setContent {
        KitoruTheme(settings = settings.copy(fontScale = 2.0f)) {
            ChatPane(messages = sampleMessages())
        }
    }
    composeRule.onNodeWithText("Enviar").assertIsDisplayed()
}
```

### 5.8 Reducción de movimiento

§9 lo desarrolla en detalle. El contrato en la UI:

```kotlin
/** Si hay que reducir el movimiento. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Duración de animación según el ajuste. */
@Composable
fun motionDuration(base: Int): Int = if (LocalReduceMotion.current) 0 else base
```

### 5.9 Errores y estados vacíos accesibles

**Componente único de error** (el mismo en toda la app):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/KitoruErrorBanner.kt

/**
 * Banner de error accesible.
 *
 * - Se anuncia con `liveRegion = Assertive` (interrumpe: es un error).
 * - El texto **diga qué pasó y qué hacer**, no sólo el código.
 * - El detalle técnico va en un desplegable, no en el mensaje principal.
 * - Si hay reintento, el botón es un `Button` real (con `Role.Button` implícito).
 */
@Composable
fun KitoruErrorBanner(
    title: String,
    hint: String,
    detail: String? = null,
    retryable: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null) // decorativo
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
            if (detail != null) {
                TextButton(onClick = { showDetail = !showDetail }) {
                    Text(if (showDetail) stringResource(Res.strings.act_hide_detail)
                         else stringResource(Res.strings.act_show_detail))
                }
                if (showDetail) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { contentDescription = detail },
                    )
                }
            }
            if (retryable && onRetry != null) {
                Button(onClick = onRetry) { Text(stringResource(Res.strings.act_retry)) }
            }
        }
    }
}
```

**Ejemplo de un error de red en el chat** (texto final, registro rioplatense):

> **No se pudo conectar con el servidor de la cátedra.**
> El servidor no responde. Revisá la dirección y la clave, o probá de nuevo en un
> momento.
> [Reintentar] · Mostrar detalle técnico

El detalle técnico es el `detail` crudo de `LlmException` ("la API: HTTP 503. …").

**Estados vacíos**: siempre con **título** (`heading()`), **explicación** y
**acción**:

```kotlin
Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(48.dp))
    Text(
        stringResource(Res.strings.empty_chats_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(Res.strings.empty_chats_body))
    Button(onClick = onNewChat) { Text(stringResource(Res.strings.act_new_chat)) }
}
```

---

## 6. El chat con lector de pantalla

El chat es la pantalla más usada y la que más sufre: **contenido que cambia
constantemente** (streaming) y **muchos elementos**. Es la que hay que designingar
primero.

### 6.1 Anatomía de un mensaje

```
┌──────────────────────────────────────────────────────────────┐
│  ● Pregunta del alumno            14:32        ← heading()   │
│  ¿Qué es una función de heurística?                          │
│                                                               │
│  ● Respuesta de Kitoru            14:32        ← heading()   │
│  Es una función que… [1]                                       │
│  ─────────────────────────────────────────────────────────    │
│  ✔ Citas verificadas (1)                                      │
│  Fuentes:                                                     │
│    [1] material: unidad-1.md — Búsqueda heurística           │
└──────────────────────────────────────────────────────────────┘
```

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/MessageBubble.kt

@Composable
fun MessageBubble(
    m: UiMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val roleLabel = if (m.isUser) stringResource(Res.strings.chat_role_student)
                    else stringResource(Res.strings.chat_role_kitoru)
    val timeLabel = formatDateTime(m.createdAt)

    Column(modifier.fillMaxWidth()) {
        // Rótulo del turno: es el "heading" con el que se navega con el rotor.
        Row(
            modifier = Modifier
                .semantics(mergeDescendants = true) { heading() }
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(roleLabel, style = MaterialTheme.typography.labelMedium)
            Text(" · $timeLabel", style = MaterialTheme.typography.labelSmall)
        }

        // Cuerpo: el texto real, sin contentDescription (lo lee el lector).
        MarkdownText(text = m.text.ifEmpty { stringResource(Res.strings.chat_empty_message) })

        // Metadatos
        if (m.sources.isNotEmpty()) SourcesList(m.sources)
        m.citation?.let { CitationBadge(it) }
    }
}
```

**Por qué el `heading()` va en el rótulo y no en la burbuja**: el `heading()` le
dice al lector "esto es un título de sección": TalkBack lo ofrece en la
navegación por encabezados y VoiceOver en el rotor, y así se puede saltar de
turno en turno. Si el `heading()` estuviera en toda la burbuja, el rotor leería
toda la respuesta como un título.

**Consecuencia**: el rotor de VoiceOver y la navegación por encabezados de TalkBack
muestran "Pregunta del alumno" / "Respuesta de Kitoru" repetidos; es aceptable y es
lo estándar en apps de chat (Slack, ChatGPT hacen lo mismo con el nombre del
remitente).

### 6.2 El `LazyColumn`

```kotlin
LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
        MessageBubble(m = m, isStreaming = m.id == streamingId)
    }
}
```

- **`key = { _, m -> m.id }`** es **obligatorio**: sin key, al llegar un mensaje
  nuevo Compose reutiliza el nodo de otro mensaje y el lector de pantalla puede
  volver a leer el mensaje anterior.
- `mergeDescendants = false` en el cuerpo del mensaje: cada `Text` se lee por
  separado (markdown con listas, código, etc.).
- `userScrollEnabled` normal; el auto-scroll a "pie" usa `scrollToItem` (§7.5).

### 6.3 Anunciar la respuesta que llega por streaming — sin inundar

**Regla: el contenido que cambia token a token NO se anuncia.** Se anuncia el
**cambio de estado**, una vez.

Implementación con una **línea de estado visible** (que además sirve a todos):

```kotlin
// En el chat:
val statusText = when {
    streaming -> stringResource(Res.strings.chat_status_writing)      // "Kitoru está escribiendo…"
    justFinished -> stringResource(Res.strings.chat_status_done, sourcesCount) // "Respuesta lista · 2 fuentes"
    else -> ""
}

// Visible arriba del composer, y es la live region:
Text(
    text = statusText,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
)
```

- Cuando arranca el streaming → el texto pasa de "" a "Kitoru está escribiendo…" ⇒
  **un** anuncio.
- Cuando termina → "Respuesta lista · 2 fuentes citadas" ⇒ **un** anuncio más.
- Nunca se anuncia el texto parcial.

**En escritorio**, Compose **no implementa live regions** (la accesibilidad de
Compose Desktop llega a `Text`, `EditableText`, `ToggleableState` y `Focused`; no
hay eventos de "cambio de texto" paraAccessibility). Por eso, en escritorio, el
anuncio se hace **moviendo el foco**:

```kotlin
// Al terminar la respuesta, en escritorio:
LaunchedEffect(finishedMessageId) {
    if (isDesktop && !isEditingText) {
        focusRequesterForAnswer.requestFocus()
    }
}
```

El `FocusRequester` apunta al `Row` del rótulo del mensaje terminado (que es el
`heading`): al recibir el foco, el lector lo lee ("Respuesta de Kitoru, 14:32") y
el usuario puede seguir leyendo con la flecha hacia abajo.

**Reglas de no inundar:**

| Regla | Motivo |
|---|---|
| Nunca `liveRegion` sobre el texto que hace streaming | Hablaría cada token. |
| Un anuncio por evento (arranque / fin), no por delta | 2 anuncios por respuesta. |
| El texto de estado **desaparece** a los 6 s (con `delay`) | El lector no lo re-anuncia; y el nodo queda vacío. |
| Si el usuario está escribiendo en el composer, **no** se mueve el foco | No se le roba el foco (§7.3). |
| Con "reducir movimiento", el texto de estado aparece/desaparece sin transición | |

### 6.4 Las tarjetas de herramienta

Cada herramienta que el modelo pide genera una tarjeta. Accesibilidad:

```kotlin
@Composable
fun ToolCallCard(call: ToolCallStats, expanded: Boolean, onToggle: () -> Unit) {
    val status = when (call.ok) {
        null -> stringResource(Res.strings.tool_status_running)
        true -> stringResource(Res.strings.tool_status_ok)
        false -> stringResource(Res.strings.tool_status_failed)
    }
    val duration = if (call.durationMs > 0) formatDuration(call.durationMs) else ""
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = status
                role = Role.Button
            }
            .clickable { onToggle() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, contentDescription = null)
            Text(stringResource(Res.strings.tool_label, call.name))
            Text(" · $status")
            if (duration.isNotEmpty()) Text(" · $duration")
        }
        if (expanded) {
            // Argumentos y resultado: texto monoespaciado, seleccionable.
            CodeBlock(call.args)
            if (call.result.isNotBlank()) CodeBlock(call.result)
        }
    }
}
```

- `stateDescription` = "en curso" / "terminó correctamente" / "falló" ⇒ el lector
  lo anuncia aunque el usuario no abra la tarjeta.
- El **resultado** de la herramienta (que puede ser un archivo creado) también
  necesita nombre accesible: `"Archivo creado: notas.md"`.
- La tarjeta **en curso** no debe anunciar cambios por token: su `stateDescription`
  cambia **una vez** (de "en curso" a "terminó"), lo que produce **un** anuncio.

### 6.5 Citas y fuentes

```kotlin
@Composable
fun CitationBadge(stats: CitationStats) {
    val text = when {
        stats.total == 0 -> stringResource(Res.strings.citation_none)          // "Sin citas"
        stats.fullySupported -> stringResource(Res.strings.citation_ok, stats.total) // "Citas verificadas (2)"
        else -> stringResource(Res.strings.citation_bad, stats.unsupported, stats.unknown)
    }
    Row(Modifier.semantics { stateDescription = text }) {
        Icon(
            if (stats.fullySupported) Icons.Default.Verified else Icons.Default.WarningAmber,
            contentDescription = null,   // decorativo: el texto ya lo dice
        )
        Text(text)
    }
}
```

El ícono **no** lleva descripción (el texto al lado ya lo dice: si el ícono
también hablara, el lector diría "verificado, Citas verificadas (2)").

### 6.6 El compositor (campo de pregunta)

```kotlin
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "" },   // el placeholder ya la nombra
        placeholder = { Text(stringResource(Res.strings.chat_ask_placeholder)) },
        label = { Text(stringResource(Res.strings.chat_ask_label)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        trailingIcon = {
            if (busy) {
                KitoruIconButton(a11yDescription = stringResource(Res.strings.a11y_stop_generation)) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                }
            } else {
                KitoruIconButton(a11yDescription = stringResource(Res.strings.a11y_send)) {
                    Icon(Icons.Default.Send, contentDescription = null)
                }
            }
        },
    )
}
```

- **El botón de enviar no se deshabilita cuando el campo está vacío.** TalkBack
  salta los elementos deshabilitados: un "Enviar" apagado es un botón que el
  usuario con lector de pantalla **no descubre**. Se deja **siempre habilitado** y,
  si se pulsa con el campo vacío, no hace nada visible pero tampoco rompe nada
  (`onSend` es un no-op con `value.isBlank()`). Cuando el campo **sí** tiene
  texto, el botón se destaca visualmente (`primary` en vez de `tonal`).

- `keyboardOptions(imeAction = Send)` + `keyboardActions(onSend)` ⇒ en Android, el botón "Enviar" del teclado manda el mensaje. En escritorio, `Ctrl+Enter` manda (§7).
- El campo tiene `label` (no sólo `placeholder`): el placeholder desaparece al
  escribir y el lector necesita un nombre **permanente**.

---

## 7. Navegación por teclado completa (escritorio)

### 7.1 Requisitos

1. **Todo** lo interactivo es alcanzable con Tab.
2. El foco es **siempre visible**.
3. El orden de tabulación coincide con el **orden de lectura**.
4. `Shift+Tab` va para atrás.
5. `Enter`/`Espacio` activan; `Escape` cierra.
6. Los atajos globales **no** le roban el foco al campo de texto.

### 7.2 Los atajos

Se registran **en la ventana**, no en cada pantalla (`Window(onKeyEvent = …)` en
`:desktop`), para que sean globales:

| Atajo | Acción | ¿Se ignora con un campo de texto con foco? |
|---|---|---|
| `Ctrl + Enter` | **Enviar el mensaje** | **No** (se maneja *dentro* del campo) |
| `Ctrl + N` | Nueva conversación | **Sí** |
| `Ctrl + ,` | Preferencias | **Sí** |
| `Ctrl + 1..9` | Ir al hilo / panel N | **Sí** |
| `F6` | Ciclar entre paneles (nav ↔ contenido ↔ composer) | No |
| `Escape` | Cerrar diálogo / hoja / menú | **Sí** (saca el foco del campo) |
| `Escape` (en el composer) | Sacar el foco del campo | — |
| `Ctrl + F` | Buscar en el material | **Sí** |
| `Alt + ←/→` | Historial del composer (como en un chat) | No |

Implementación:

```kotlin
// desktop/src/main/kotlin/…/Main.kt
Window(
    onKeyEvent = { event ->
        if (event.type != KeyEventType.KeyDown) return@Window false
        val shortcut = GlobalShortcuts.resolve(event, isTextFieldFocused = vm.isEditingText)
        if (shortcut == null) return@Window false
        vm.run(shortcut)
        true
    },
    // …
)
```

### 7.3 Que los atajos no roben el foco del campo de texto

**Regla: un atajo global sin modificador (`Escape`, `F6`) sí se aplica; un atajo
con `Ctrl` se ignora si hay un campo de texto con el foco** (salvo `Ctrl+Enter`,
que es la acción del propio campo).

Detección de "hay un campo de texto con el foco": se mantiene una bandera
explícita, alimentada por el propio composable (no por introspección del árbol de
foco, que es frágil):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/a11y/InputMode.kt

/** Si el usuario está escribiendo en un campo de texto. */
val LocalIsEditingText = staticCompositionLocalOf { false }

/** `OutlinedTextField` de Kitoru: publica el estado de foco. */
@Composable
fun KitoruTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit = {},
    /* … */
) {
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalIsEditingText provides focused) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            /* … */
        )
    }
}
```

> La alternativa "inspeccionar el nodo de foco" (`focusManager.focusOwner` →
> `SemanticsProperties.EditableText`) es más automática pero **no funciona en
> escritorio** igual que en Android. La bandera explícita es determinista y
> testeable.

Además, en el campo de texto:

```kotlin
// Escape saca el foco del campo
Modifier.onPreviewKeyEvent { event ->
    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
        focusManager.clearFocus()
        true
    } else false
}
```

### 7.4 Escape

`Escape` tiene que cerrar, en este orden de prioridad:

1. Un menú o un popup abierto.
2. Un diálogo (`AlertDialog` de Compose **no** cierra con Escape en escritorio ⇒
   hay que hacerlo a mano).
3. Una hoja expandida.
4. El foco dentro de un campo de texto (lo saca).
5. Si no hay nada de lo anterior: no hace nada (no cierra la ventana).

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/KitoruDialog.kt

@Composable
fun KitoruDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        modifier = modifier
            // Escape cierra el diálogo
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onDismissRequest(); true
                } else false
            }
            .focusRequester(focusRequester),
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                content()
            }
        }
    }
}
```

El `FocusRequester` **devuelve el foco al elemento que lo tenía** al cerrar
(Compose restaura el foco automáticamente si el nodo sigue en el árbol; si no,
`Modifier.focusRestorer()`).

### 7.5 El scroll automático del chat

El scroll a "pie" durante el streaming **es una animación**: con "reducir
movimiento" tiene que ser instantáneo.

```kotlin
suspend fun scrollToBottom(listState: LazyListState, instant: Boolean) {
    val last = messages.lastIndex
    if (last < 0) return
    if (instant) {
        listState.scrollToItem(last)
    } else {
        listState.animateScrollToItem(last)
    }
}
```

Además, **el scroll automático sólo ocurre si el usuario no subió** (el
`followToBottom` que ya está implementado en `ChatPane.kt` de FunshiEngineAI):
mover el foco automáticamente también se interrumpe si el usuario está leyendo
arriba.

---

## 8. Tema y contraste

### 8.1 Qué se mantiene de `FunshiEngineAI`

`LlmConfig` de FunshiEngineAI tiene:

```kotlin
val uiDark: Boolean = false,
val uiAccent: Int = 0xFF6200EE.toInt(),
```

**Se mantienen las dos ideas, se cambian las dos formas:**

| FunshiEngineAI | Kitoru | Por qué |
|---|---|---|
| `uiDark: Boolean` | `KitoruSettings.theme: ThemeMode` (`SYSTEM`, `LIGHT`, `DARK`) | El boolean no puede expresar "seguir al sistema", que es lo que quiere la mayoría. `SYSTEM` usa `isSystemInDarkTheme()`. |
| `uiAccent: Int` (ARGB libre) | `KitoruSettings.accent: AccentColor` (enum con ARGB fijo) **+** `customAccent: Int?` | Un color libre rompe el contraste si no se ajusta. Con el enum, **todos** los acentos pasan el test de contraste. El color personalizado pasa por `onColorFor()` (§5.4), que lo ajusta hasta cumplir 4.5:1. |

El acento por defecto de Kitoru es **`0xFF3B5BDB`** (azul indigo, el color de
Material 3 "primary" por defecto, con 8.1:1 de contraste con el blanco) — el
violeta `0xFF6200EE` de FunshiEngineAI da 5.0:1 con blanco y **4.6:1** con su
`onPrimary`, que está justo; con el ajuste automático se resuelve.

### 8.2 El esquema se deriva del acento (no a mano)

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruColorScheme.kt
package kitoru.ui.theme

/** Acentos predefinidos. Todos cumplen 4.5:1 con su `onPrimary`. */
enum class AccentColor(val color: Color) {
    INDIGO(Color(0xFF3B5BDB)),
    TEAL(Color(0xFF00696D)),
    VIOLET(Color(0xFF6B4EA8)),
    GREEN(Color(0xFF2E6B34)),
    AMBER(Color(0xFF8A5300)),
}

/**
 * Construye el esquema de Material3 a partir del acento.
 *
 * A diferencia de FunshiEngineAI (que sólo pisa `primary` y deja `onPrimary`
 * como venia), acá **se recalculan los pares de color** para garantizar el
 * contraste mínimo.
 */
object KitoruColorScheme {

    fun light(accent: AccentColor, custom: Color? = null): ColorScheme {
        val base = lightColorScheme()
        return applyAccent(base, custom ?: accent.color, dark = false)
    }

    fun dark(accent: AccentColor, custom: Color? = null): ColorScheme {
        val base = darkColorScheme()
        return applyAccent(base, custom ?: accent.color, dark = true)
    }

    private fun applyAccent(base: ColorScheme, accent: Color, dark: Boolean): ColorScheme {
        // 1) El acento se ajusta hasta que negro o blanco le llegue a 4.5:1.
        val (primary, onPrimary) = onColorFor(accent)
        // 2) El contenedor es una versión atenuada del acento.
        val container = if (dark) lerp(primary, Color.Black, 0.55f) else lerp(primary, Color.White, 0.82f)
        val onContainer = if (dark) lerp(primary, Color.White, 0.85f) else lerp(primary, Color.Black, 0.55f)
        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = container,
            onTertiaryContainer = onContainer,
        )
    }
}
```

> El resto de los roles (`surface`, `background`, `outline`, `error`…) **no** se
> tocan: la paleta base de Material3 ya cumple los contrastes.

### 8.3 El tema

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruTheme.kt

@Composable
fun KitoruTheme(
    settings: KitoruSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        settings.customAccent != null ->
            if (dark) KitoruColorScheme.dark(settings.accent, Color(settings.customAccent!!))
            else KitoruColorScheme.light(settings.accent, Color(settings.customAccent!!))
        dark -> KitoruColorScheme.dark(settings.accent)
        else -> KitoruColorScheme.light(settings.accent)
    }
    val typography = scaledTypography(MaterialTheme.typography, settings.fontScale)

    CompositionLocalProvider(
        LocalReduceMotion provides settings.reduceMotion.resolve(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            content()
        }
    }
}
```

### 8.4 Preajustes del acento

La pantalla de preferencias ofrece **5 acentos predefinidos** (con su nombre en
español) **+** un selector de color libre (que se ajusta al contraste). El nombre
del acento es parte del catálogo (`a11y`/etiqueta): "Índigo", "Verde agua",
"Violeta", "Verde", "Ámbar".

---

## 9. Reducción de movimiento

### 9.1 Qué es "reducir movimiento"

Es un ajuste de **sistema operativo**, no de la app:

| SO | Ajuste | Cómo se lee desde Kotlin |
|---|---|---|
| Android | "Eliminar animaciones" | `ValueAnimator.areAnimatorsEnabled()` (API 26+) o `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` |
| Windows | "Animaciones" en Accesibilidad | `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, …)` |
| macOS | "Reducir movimiento" | `NSWorkspace.accessibilityDisplayShouldReduceMotion` (no accesible desde AWT de forma estable) |
| Linux | Depends del escritorio | no hay API portable |

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/a11n/ReduceMotion.kt
package kitoru.ui.a11y

/** Si el sistema pide reducir el movimiento (o la app lo fuerza). */
expect fun systemReduceMotion(): Boolean
```

- `androidMain`: `ValueAnimator.areAnimatorsEnabled()` con el fallback de
  `Settings.Global` para API 24-25.
- `desktopMain`: `false` en todas las plataformas salvo Windows (SPI). En macOS y
  Linux se devuelve `false` y **la app ofrece su propio interruptor**.

### 9.2 El ajuste de la app

```kotlin
/** Cómo se decide si se reduce el movimiento. */
enum class ReduceMotionMode { SYSTEM, ALWAYS, NEVER }
```

En Preferencias → Apariencia: **"Animaciones: Sistema / Reducidas / Normales"**.
El valor por defecto es `SYSTEM`.

### 9.3 Qué cambia cuando se reduce el movimiento

| Animación | Con movimiento | Reducido |
|---|---|---|
| Texto del streaming | Aparece progresivamente (ya es un flujo) | **Igual** (es contenido, no decoración) |
| Cursor parpadeante al final del texto | Parpadea | **No parpadea** (se muestra un bloque estático) |
| Auto-scroll al pie | `animateScrollToItem` | `scrollToItem` (instantáneo) |
| Aparición de un mensaje | fundido de 150 ms |Instantáneo |
| `AnimatedVisibility` de paneles | expande | aparece de una |
| `Crossfade` entre pestañas | fundido | instantáneo |
| Spinner de progreso | gira | **texto estático** ("Trabajando…") |
| Ripple / hover | sutil | se mantiene (no es movimiento) |
| Diálogo | escala + fade | fade somente, o sin transición |

```kotlin
// Uso en la UI
val reduce = LocalReduceMotion.current
val duration = if (reduce) 0 else 150

AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(duration)),
    exit = fadeOut(tween(duration)),
) { … }
```

**El ripple no se quita**: es una realimentación tátil, no movimiento无害; y sin
ripple el usuario pierde la confirmación del toque.

### 9.4 La regla del "streaming"

El streaming **no es una animación decorativa**: es la llegada de contenido. **No se
quita** con "reducir movimiento". Lo que sí se quita es el *cursor parpadeante* y
las transiciones de entrada/salida.

---

## 10. Archivos, tipos y composables

### 10.1 `:core` (puro, sin Compose)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/i18n/AppLanguage.kt` | `AppLanguage` (es/en/pt/unknown), `AppForm` (rioplatense/tú/usted) |
| `core/src/commonMain/kotlin/kitoru/i18n/LanguageDetector.kt` | `LanguageDetector.detect(text)` |
| `core/src/commonMain/kotlin/kitoru/i18n/LanguageDirective.kt` | `LanguageDirective.forLanguage(lang, form, codeOnly)` |
| `core/src/commonMain/kotlin/kitoru/domain/ValidationIssue.kt` | `ValidationIssue` (sealed) |
| `core/src/commonMain/kotlin/kitoru/domain/Settings.kt` | `KitoruSettings`, `ThemeMode`, `AccentId`, `ReduceMotionMode`, `LanguagePreference` |

> **`AccentId` vive en `:core`; el `Color` en `:ui`.** `:core` no puede usar
> `androidx.compose.ui.graphics.Color` (rompería la pureza del dominio), así que
> el acento se guarda como un identificador: `enum class AccentId { INDIGO, TEAL,
> VIOLA, GREEN, AMBER }`, y `:ui` lo mapea a `Color`. El acento **personalizado**
> se guarda como `customAccent: Int` (ARGB) y pasa por `onColorFor()` (§5.4) antes
> de usarse, así que también cumple el contraste.

### 10.2 `:llm-api` y `:backend`

| Archivo | Cambio |
|---|---|
| `llm-api/.../llm/error/LlmErrors.kt` | `LlmException(code, detail, retryable)`; sin frases |
| `llm-api/.../llm/error/LlmErrorCode.kt` | sin cambios (los códigos ya son identificadores, no texto) |
| `backend/.../rag/engine/CitationVerifier.kt` | `CitationReason` sealed; `reason: CitationReason?` |
| `backend/.../rag/engine/LanguagePolicy.kt` | `LanguageDecision` + `LanguagePolicy.decide(...)` |
| `backend/.../rag/engine/RagEngine.kt` | inserta el `system` de la directiva |

### 10.3 `:ui`

| Archivo | Contenido |
|---|---|
| `ui/src/commonMain/composeResources/values/strings.xml` | el catálogo (español rioplatense) |
| `ui/src/commonMain/composeResources/values/plurals.xml` | plurales |
| `ui/src/commonMain/kotlin/kitoru/ui/i18n/LocalAppLocale.kt` (+ actuals) | locale de la app |
| `ui/src/commonMain/kotlin/kitoru/ui/i18n/FormText.kt` | `AppForm`, `formText(...)` |
| `ui/src/commonMain/kotlin/kitoru/ui/i18n/DomainMessages.kt` | los `when` exhaustivos (validación, LLM, citas) |
| `ui/src/commonMain/kotlin/kitoru/ui/i18n/Formats.kt` (+ `jvmCommonMain`) | números y fechas |
| `ui/src/commonMain/kotlin/kitoru/ui/a11y/FocusRing.kt` | `Modifier.kitoruFocusRing()` |
| `ui/src/commonMain/kotlin/kitoru/ui/a11y/ReduceMotion.kt` (+ actuals) | `systemReduceMotion()`, `LocalReduceMotion` |
| `ui/src/commonMain/kotlin/kitoru/ui/a11y/InputMode.kt` | `LocalIsEditingText`, `KitoruTextField` |
| `ui/src/commonMain/kotlin/kitoru/ui/a11y/KitoruIconButton.kt` | botón de ícono con descripción obligatoria |
| `ui/src/commonMain/kotlin/kitoru/ui/components/KitoruErrorBanner.kt` | banner de error accesible |
| `ui/src/commonMain/kotlin/kitoru/ui/components/KitoruEmptyState.kt` | estado vacío accesible |
| `ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruColorScheme.kt` | derivación del esquema desde el acento |
| `ui/src/commonMain/kotlin/kitoru/ui/theme/Contrast.kt` | WCAG: luminancia, razón de contraste, `onColorFor` |
| `ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruTheme.kt` | tema + escala de fuente + reduce motion |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/MessageBubble.kt` | heading + stateDescription |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/ToolCallCard.kt` | estado accesible |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/ChatStatus.kt` | la línea de estado / live region |

### 10.4 `:desktop` y `:android`

| Archivo | Contenido |
|---|---|
| `desktop/src/main/kotlin/…/Main.kt` | `Window(onKeyEvent = …)` con los atajos globales |
| `desktop/src/main/kotlin/…/Shortcuts.kt` | `GlobalShortcuts.resolve(event, isTextFieldFocused)` |
| `android/.../KitoruActivity.kt` | `CompositionLocalProvider(LocalAppLocale provides …)` |

### 10.5 Tests

| Test | Qué cubre |
|---|---|
| `core/src/commonTest/…/LanguageDetectorTest.kt` | detección es/en/pt, código, ambiguos, vacío |
| `core/src/commonTest/…/LanguageDirectiveTest.kt` | la directiva por idioma × registro |
| `core/src/commonTest/…/LegacyJsonTest.kt` | un JSON viejo con `label` en los enums se lee igual |
| `core/src/commonTest/…/ValidationIssueTest.kt` | `validate()` devuelve los issues correctos |
| `ui/src/commonTest/…/ContrastTest.kt` | contraste ≥ 4.5:1 en claro y oscuro, y para cada acento |
| `ui/src/desktopTest/…/CatalogConsistencyTest.kt` | toda clave `_tu` tiene `_usted` y viceversa; los archivos tienen las mismas claves |
| `ui/src/desktopTest/…/LargeFontTest.kt` | el chat y las preferencias no se rompen con `fontScale = 2.0` |
| `ui/src/desktopTest/…/SemanticsTest.kt` | el mensaje del chat expone `heading()`; el banner de error expone `liveRegion` |

---

## 11. Casos borde

| Caso | Qué hace la app |
|---|---|
| El SO está en `en-US` y no hay `values-en/` | La app se muestra en español (el fallback `values/`). El banner de idioma no aparece: no hay traducción parcial. |
| El usuario elige "Inglés" y no hay `values-en/` | El selector **no ofrece** idiomas sin carpeta: sólo se ofrecen los idiomas traducidos. |
| El usuario cambia el registro a "usted" con un chat abierto | El cambio se aplica al instante (es un `CompositionLocal`): la interfaz cambia sin reiniciar. Los **mensajes ya escritos** no se re-traducen (son texto del modelo). |
| La pregunta del alumno es 3 palabras en español ("¿qué es IA?") | La detección puede dar `UNKNOWN` → se usa el idioma de la conversación → si no hay, el de la app → si no, no hay directiva. |
| La pregunta es código (`"¿Qué hace este ```kotlin fun main() {}```?`) | Detección `UNKNOWN` → directiva `codeOnly`: "respondé en el idioma de la conversación". |
| El alumno escribe en inglés en una cátedra que fijó español | La cátedra manda: `Course.answerLanguage = ES` ⇒ directiva en español. (Es una decisión explícita del docente.) |
| El modelo responde en otro idioma | Chip no bloqueante "La respuesta llegó en inglés" + botón "Pedirla en español" (§4.5). |
| La app se usa por primera vez en un portátil con fuente del SO al 150 % (Android) | La tipografía usa `sp` ⇒ el SO ya escala; la escala de la app se **multiplica** (1.0 × 1.5). En escritorio (sin escala del SO) el usuario la sube a mano en Preferencias. |
| Un mensaje del chat tiene 40 párrafos | El `heading()` está en el rótulo, no en el cuerpo: el rotor no lee el mensaje entero. |
| El lector de pantalla está activo y el usuario scrollea | El `LazyColumn` con `key` estable: no se re-anuncia lo que ya se leyó. |
| El usuario tiene "reducir movimiento" en Windows | `systemReduceMotion()` lo detecta ⇒ sin animaciones de scroll, sin cursor parpadeante, sin spinner giratorio. |
| El foco está en el campo de pregunta y el usuario aprieta `Ctrl+N` | **No** pasa nada (el atajo se ignora con un campo de texto con foco). `Ctrl+Enter` **sí** manda el mensaje. |
| El usuario aprieta `Escape` con un diálogo abierto | Cierra el diálogo. Con un menú abierto, cierra el menú. Con el composer con foco, saca el foco. |
| El error de red aparece mientras el usuario está escribiendo | El banner se anuncia (live region) pero **no** mueve el foco ni interrumpe la escritura. |
| El acento personalizado es un amarillo muy claro (#FFF700) | `onColorFor()` lo oscurece hasta que el texto negro alcance 4.5:1; el color elegido se muestra "ajustado" en el selector. |
| Un teacher escribe un `systemPrompt` en inglés | Se usa tal cual (es contenido del docente); la directiva de idioma se agrega igual. |

---

## 12. Criterios de aceptación

1. **Un solo catálogo.** No hay ningún `Text("literal en español")` en `:ui`
   (salvo los nombres propios y los identificadores técnicos). Los literales están
   en `composeResources/values/strings.xml`.
2. **El dominio no tiene frases.** `ModelPolicy.validate()` devuelve
   `List<ValidationIssue>`; `LlmException` no arma frases; `CitationCheck.reason`
   es un `CitationReason`. Los JSON viejos con `label` se siguen leyendo
   (`LegacyJsonTest`).
3. **Anti-drift por el compilador.** Agregar un `ValidationIssue`, un
   `LlmErrorCode` o un `CitationReason` **rompe la compilación** hasta que exista
   su frase en el catálogo (los `when` son exhaustivos).
4. **Consistencia del catálogo.** `CatalogConsistencyTest` falla si falta una
   variante `_tu`/`_usted`, o si `values/` y `values-es/` divergen.
5. **Contraste verificado.** `ContrastTest` pasa para el esquema claro, el oscuro
   y los 5 acentos: todos los pares de texto ≥ 4.5:1 y los no textuales ≥ 3:1.
6. **Todo tiene nombre accesible.** Cada icono tiene `contentDescription` (o
   `null` si es decorativo); cada control tiene nombre; los estados se exponen con
   `stateDescription`.
7. **El chat es navegable.** Los rótulos de turno tienen `heading()`; el lector
   puede saltar de turno en turno con el rotor / la navegación por encabezados.
8. **El streaming no inunda.** Hay **exactamente** un anuncio al empezar y uno al
   terminar; ninguno por token. En escritorio, el foco se mueve a la respuesta
   terminada si el usuario no está escribiendo.
9. **Teclado completo.** Todo es alcanzable con Tab, el foco es visible, el
   orden coincide con el orden de lectura, `Escape` cierra, y `Ctrl+N`/`Ctrl+,`/
   `Ctrl+1..9` se ignoran si hay un campo de texto con el foco. `Ctrl+Enter` manda
   el mensaje.
10. **Fuente grande no rompe.** Con `fontScale = 2.0` no hay texto recortado ni
    desbordes en el chat, las preferencias y la analítica (`LargeFontTest`).
11. **Objetivos táctiles.** En Android, todo control custom mide ≥ 48 dp; en
    escritorio, ≥ 40 dp.
12. **Reducir movimiento.** Con el ajuste del SO (o el de la app) activo: el
    scroll del chat es instantáneo, no hay cursor parpadeante, no hay spinner
    giratorio y las transiciones duran 0 ms.
13. **Errores legibles.** Todo error de red muestra título + qué revisar + acción
    ("Reintentar"), se anuncia con `liveRegion` y el detalle técnico va aparte.
14. **El modelo responde en el idioma del alumno.** Con la detección + la
    directiva, una pregunta en inglés recibe una respuesta en inglés, y una
    pregunta en español rioplatense recibe una respuesta en voseo.
15. **Windows con lector de pantalla.** La distribución nativa incluye el módulo
    `jdk.accessibility` (`compose.desktop { application { nativeDistributions {
    modules("jdk.accessibility") } } }`) y la documentación de instalación dice que
    hay que habilitar el Java Access Bridge (`jabswitch -enable`).

---

## 13. Coordinación con otros documentos

| Documento | Impacto |
|---|---|
| `teacher/05-config-modelo.md` | `policy.validate()` pasa a `List<ValidationIssue>`; el objeto `LlmErrorMessages` en `:llm-api` se reemplaza por el `when` en `:ui` (o se le cambia la firma). Las frases proposals se mantienen idénticas. |
| `teacher/04-prompt.md` | La vista previa del prompt **no** cambia (los marcadores `CONTEXTO:`/`PREGUNTA:` no se traducen, §4.6). Se agrega la **directiva de idioma** como `system` extra: el doc debe mostrarla en la vista previa del payload. |
| `teacher/01-navegacion.md` | El banner de validación usa `issue.localized()`. La navegación necesita `heading()` en los títulos de panel. |
| `domain/07-anuncios.md` | `Announcement.validate(): List<String>` → debería ser `List<ValidationIssue>` por el mismo criterio (el texto del anuncio es contenido, no un mensaje de error). |
| `student/*` (chat) | El chat aplica §6: heading por turno, live region de estado, tarjetas de herramienta accesibles. |
| `ui` (no construido) | Se implementa desde el día 1 con este contrato: sin literales, sin colores hardcodeados, con `sp`, con semántica. |

---

## 14. Deuda conocida y fuera de alcance

| Tema | Estado | Nota |
|---|---|---|
| Traducción al inglés/portugués | Fuera del milestone 1 | La estructura ya está; falta `values-en/`. |
| Lectura de pantalla en **Linux** | **No soportado** por Compose Desktop | Documentado en Preferencias → Accesibilidad: "En Linux, el soporte de lector de pantalla depende del escritorio". |
| `liveRegion` en escritorio | No soportado por Compose Desktop | Se compensa moviendo el foco (§6.3). |
| Traducción de los **materiales de la cátedra** | Fuera de alcance | Son contenido del docente. |
| Traducción de las **respuestas del modelo** | Fuera de alcance | El modelo responde en el idioma del alumno. |
| Escalado de fuente del SO en **escritorio** | No existe | Se resuelve con el ajuste de la app (§5.7). |
| Traducción a **braille** / alto contraste del SO | No soportado por Compose Desktop | La app ofrece tema claro/oscuro + acento; no puede prometer alto contraste del SO. |
| Internacionalización de los **gráficos** (ejes de la analítica) | Parcial | Las etiquetas de los ejes se traducen; los nombres de series son datos. |

---

## 15. Checklist de revisión para el code review

Cuando se revisa una pantalla nueva, esta lista es el filtro:

```
[ ] ¿Hay algún Text("literal")?                        → debe salir del catálogo
[ ] ¿Todos los Icon tienen contentDescription explícito?
[ ] ¿Los estados se anuncian (stateDescription)?
[ ] ¿Los títulos tienen heading()?
[ ] ¿El contraste pasa el test?                        → no hay colores sueltos
[ ] ¿Hay alturas fijas sobre texto?                    → heightIn, no height
[ ] ¿Los tamaños de fuente son sp?
[ ] ¿Los clickables propios miden 48/40 dp?
[ ] ¿Las animaciones respetan LocalReduceMotion?
[ ] ¿Los diálogos cierran con Escape y devuelven el foco?
[ ] ¿Los atajos globales se ignoran con un campo de texto con foco?
[ ] ¿El estado vacío dice qué hacer?
[ ] ¿El error dice qué pasó y qué hacer?
```
