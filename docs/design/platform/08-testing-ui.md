# 08 — Estrategia de testing de UI (Compose Multiplatform)

> Subagente: diseño de **cómo se testean las pantallas de Kitoru** en
> Compose Multiplatform 1.7.3.
>
> Módulos tocados: `:ui` (nuevo: casi todo lo que hay que testear vive ahí),
> `:core` (dobles compartidos), `:backend` (conversión de `CitationVerifierTest`),
> raíz (pipeline de CI).
>
> Reglas del proyecto que se respetan: *reutilizar, no reescribir* (los tests
> se portan de FunshiEngineAI, no se inventan), *portable por defecto*, *determinista*,
> *fail-fast con mensajes en español*.
>
> Reglas de testing heredadas: `docs/TESTING.md` de FunshiEngineAI (§4 de este
> documento las adapta).

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| **D1** | La mayor parte del testing de UI va en **`:ui/commonTest` con `kotlin.test`**: lógica de UI pura (parsers, formateadores, derivadores de estado) **y** ViewModel. | Es portable (corre en escritorio **y** en `androidUnitTest`), es rápido y no depende de la instability de Compose. Es el 80 % del valor. |
| **D2** | Los tests de **composición** (`runComposeUiTest`) van en **`:ui/desktopTest`** y son **pocos y quirúrgicos**: sólo componentes hoja y semántica de accesibilidad. | En CMP 1.7.3 `runComposeUiTest` es `@ExperimentalTestApi`: la API puede cambiar al subir de versión. Se lo aísla en un source set y se lo limita a un presupuesto. |
| **D3** | **No** se usa `compose.desktop.uiTestJUnit4` (el `createComposeRule` de escritorio). | Es **JUnit 4** y el proyecto es **JUnit 5**: mezclar obligaría a agregar el *vintage engine* y a tener dos estilos de test en el mismo módulo. |
| **D4** | **No** hay tests de UI instrumentados en Android (`androidTest`). | Requieren emulador: no entran en el pipeline de PR y el `androidTest` de Android **no soporta nombres de método con espacios** (DEX < 040). |
| **D5** | Los **ViewModel reciben el `CoroutineScope` inyectado**. `Dispatchers.Main` no se puede usar en un test de ViewModel (en escritorio es el dispatcher de Swing). | Sin esto, los tests de ViewModel son *flaky* por construcción: el test avanza antes de que la corrutina corra. |
| **D6** | Los dobles compartidos (FS en memoria, repos fake, `ScriptedGateway`, fixtures) van en un **módulo nuevo `:testkit`** (KMP, `commonMain`). | Los necesitan `:core`, `:ui` y `:backend`. Duplicarlos viola la regla *reutilizar, no reescribir*. **Cambia lo que dice `docs/design/analytics/08-testing.md` §1.3/§6.1** (que los ponía en `core/src/commonTest`): hay que actualizar ese documento. |
| **D7** | `CitationVerifierTest` (24 tests) **se mueve a `:backend/commonTest`** con `kotlin.test`. | El verificador es la métrica de calidad que ve el docente y vive en `commonMain`: que corra también en Android vale el costo (≈ 15 min de conversión). Es el piloto para el resto de `:backend`. |
| **D8** | La accesibilidad se testea por **tres mecanismos**: (1) tipado (`KitoruIconButton` con descripción obligatoria), (2) árbol de semántica en `desktopTest`, (3) auditoría de fuente en `desktopTest`. | El mecanismo (1) evita el error en el compilador; (2) verifica lo que el lector de pantalla lee; (3) es una red de seguridad barata para el código nuevo. |
| **D9** | **Ninguna captura golden** como puerta del pipeline. Las capturas se generan en el nightly y se reportan, pero no bloquean. | Un golden es un contrato de píxeles: falla por el antialiasing de otra máquina y entrena al equipo a ignorar los fallos. |
| **D10** | El corpus de la cátedra falsa vive **en código Kotlin** (`Corpus.kt`) y se **exporta** a `fixtures/catedra-falsa/` con una tarea de Gradle. | `commonTest/resources` no se lee igual en todas las plataformas; el código es portable, tipado y grepeable. El exportador evita que el corpus se duplique. |
| **D11** | En el PR corren **dos jobs**: `desktopTest` de los 4 módulos (rápido) y `testDebugUnitTest` (Android, sin emulador). El **nightly** corre el modelo real. | El job de Android es lo que hace valuable `commonTest`: detecta que el código de UI se le escapa a la JVM. |
| **D12** | Todo test que se escriba tiene que **fallar si el comportamiento cambia**. Si un test no puede explicarse en una frase, no se escribe. | Es la regla de FunshiEngineAI aplicada a la UI: un test de UI que no ata comportamiento es decorativo. |
| **D13** | Los formateadores se parten en **decisión** (común, pura, testeable en `commonTest`) y **traducción** (`expect/actual` JVM, testeable en `jvmCommonTest`). | `commonTest` no puede verificar el separador decimal de un locale: eso se prueba en `jvmCommonTest`, que corre en escritorio **y** en Android. |
| **D14** | `useJUnitPlatform()` se acota a la tarea de **escritorio**. | Hoy `:backend` lo aplica a **todas** las tareas `Test`, incluyendo las de Android: eso rompe la resolución de `kotlin.test` en `androidUnitTest` (ver H-UI-1). |

---

## 1. El límite honesto: qué se puede testear de verdad en CMP 1.7.3

Esta sección es la base de todo lo demás. Los hechos están **verificados
contra los artefactos publicados**, no de memoria.

### 1.1 Inventario verificado

| Artefacto / API | ¿Existe en 1.7.3? | Dónde | Detalle verificado |
|---|---|---|---|
| `org.jetbrains.compose.ui:ui-test:1.7.3` | **Sí** | Maven Central (22/12/2024) | Es el artefacto **común** de test de Compose Multiplatform. Su `commonMain` contiene `expect fun runComposeUiTest(effectContext: CoroutineContext = EmptyCoroutineContext, block: ComposeUiTest.() -> Unit)`, anotado `@ExperimentalTestApi`. |
| `androidx.compose.ui.test.SemanticsNodeInteractionsProvider` | **Sí**, pero **dentro del artefacto de test** (`ui-test`) | `commonMain/androidx/compose/ui/test/SemanticsNodeInteractionsProvider.kt` | Está en el `commonMain` *del artefacto de test*, no del de producción: el código que lo usa tiene que vivir en un **source set de test**, y el árbol de nodos sólo existe con una composición **viva**. |
| `ComposeTestRule` / `createComposeRule` | **No** en common | Android: `androidx.compose.ui:ui-test` (instrumentado) · Escritorio: `compose.desktop.uiTestJUnit4` | El de escritorio es **JUnit 4** (`org.jetbrains.compose.ui:ui-test-junit4:1.7.3`). El de Android exige dispositivo/emulador. |
| Implementación de escritorio de `runComposeUiTest` | **Sí** | `skikoMain/androidx/compose/ui/test/ComposeUiTest.skikoMain.kt` del mismo artefacto | Usa `ComposeScene` + `Surface` de **Skia** ⇒ render por software ⇒ **funciona sin display** (sirve para CI). Además `runSkikoComposeUiTest(size, density)` permite fijar el tamaño y la densidad del lienzo. |
| `runComposeUiTest` en Android | **Sí**, pero **no sirve en `androidUnitTest`** | `androidMain` del artefacto | La variante común levanta un **host Activity**: en un test unitario de Android (JVM, sin instrumentación) no hay Activity ⇒ no se puede usar. En `androidTest` (instrumentado) sí, pero necesita emulador y **no acepta nombres de método con espacios** (DEX < 040, ver §1.4). |

> **Corrección de una premisa común**: `runComposeUiTest` **sí** está en el
> target común de CMP 1.7.3 (es un `expect` en `commonMain` con actual en
> `skikoMain` para escritorio). Lo que **no** está en el target común es la
> *rule* de JUnit (`ComposeTestRule`) y, por lo tanto, no hay `setContent` de
> una regla: hay que llamar a `runComposeUiTest { … }` explícitamente.
> En la práctica, para Kitoru, `commonTest` **no** puede usar la API de
> composición: se usa sólo en `desktopTest`.

### 1.2 Qué se puede testear de verdad — tabla de capacidad

| Capacidad de test | ¿Portable? | ¿Dónde vive el test? | ¿La usa Kitoru? |
|---|---|---|---|
| Funciones puras de UI (parsers, formateadores, derivadores) | **Sí** | `:ui/commonTest` | **Sí** — es el grueso del testing |
| Estado de un ViewModel (`mutableStateOf` leído directo) | **Sí** | `:ui/commonTest` | **Sí** — la capa más valiosa |
| Semántica de accesibilidad (`heading`, `stateDescription`, `liveRegion`) | **No en `commonTest`** | `:ui/desktopTest` | **Sí**, de forma acotada |
| Presencia de un `contentDescription` | **No en `commonTest`** | `:ui/desktopTest` | **Sí**, de forma acotada |
| Contraste de color del tema | **Sí** (es aritmética sobre `Color`) | `:ui/commonTest` | **Sí** |
| Tamaño de fuente grande sin desborde | **No** (necesita medir el layout) | `:ui/desktopTest` | **Sí**, 2 tests |
| Persistencia sobre el FS real | **No** | `:core/desktopTest` | ya está cubierto por el doc de analítica |
| Captura golden / pixel | **No** (y frágil) | nightly, **fuera de la puerta** | **No** como puerta |
| Comportamiento real del modelo | **No** (no es determinista) | nightly | **No** como puerta |

### 1.3 El criterio de decisión

```
¿El comportamiento depende de una composición viva?
├─ NO  → :ui/commonTest, kotlin.test          ← el 80 %
└─ SÍ  → ¿necesita el árbol de semántica o medir el layout?
          ├─ NO  → :ui/commonTest (es derivación de estado)  ← el 15 %
          └─ SÍ  → :ui/desktopTest, runComposeUiTest          ← el 5 %
```

### 1.4 La trampa de los nombres de test en Android

Kotlin permite ``fun `un nombre con espacios`() ``. En el **runtime de Android**
(DEX) los espacios en los nombres de método **no están permitidos antes de
API 30** (DEX 040): es una limitación del formato, no del lenguaje.

Consecuencia práctica para Kitoru:

- En `commonTest`, `desktopTest` y `androidUnitTest` **se puede** usar el estilo
  de FunshiEngineAI (acentos graves, espacios, español). Los tests unitarios de
  Android se ejecutan en la **JVM del host**, no se dexan: no hay problema.
- En `androidTest` (instrumentado) **no** se puede: hay que usar `snake_case`.
  Como Kitoru **no** va a tener tests instrumentados (D4), la convención de
  FunshiEngineAI se aplica entera.

> La convención queda: **nombres en español, entre acentos graves, en tercera
> persona y describiendo el comportamiento** —
> ``fun `una tabla sin separador no es una tabla`() ``.

---

## 2. Las tres capas

### 2.a Lógica de UI pura en `commonTest` — **SÍ, es la mayor parte del valor**

Qué es "lógica de UI pura": todo lo que la UI decide **antes** de dibujar. Un
composable no debería decidir nada: debería **preguntar** a una función pura y
dibujar lo que le devuelve.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/state/ComposerState.kt
package kitoru.ui.state

/** Qué botón principal muestra el composer. */
enum class PrimaryAction { SEND, STOP, CANCEL_QUEUE }

/** El placeholder del campo de texto. */
enum class ComposerHint { ASK, WRITING, NO_MATERIAL, NOT_READY }

/**
 * Lo que el composer muestra, derivado del estado.
 *
 * Es una función pura: el mismo estado produce la misma pantalla. Si esto
 * estuviera dentro del composable, no se podría testear sin composición.
 */
data class ComposerState(
    val primaryAction: PrimaryAction,
    val sendEnabled: Boolean,
    val hint: ComposerHint,
    val queuedCount: Int,
)

/** Deriva el estado del composer. */
fun composerState(
    input: String,
    busy: Boolean,
    queuedCount: Int,
    canChat: Boolean,
    materialReady: Boolean,
): ComposerState = when {
    !canChat -> ComposerState(PrimaryAction.SEND, sendEnabled = false, hint = ComposerHint.NOT_READY, queuedCount)
    busy -> ComposerState(PrimaryAction.STOP, sendEnabled = true, hint = ComposerHint.WRITING, queuedCount)
    queuedCount > 0 -> ComposerState(PrimaryAction.CANCEL_QUEUE, sendEnabled = true, hint = ComposerHint.ASK, queuedCount)
    input.isBlank() -> ComposerState(PrimaryAction.SEND, sendEnabled = false, hint = ComposerHint.ASK, queuedCount)
    else -> ComposerState(PrimaryAction.SEND, sendEnabled = true, hint = ComposerHint.ASK, queuedCount)
}
```

**Por qué esta capa vale más que cualquier test de composición:**

1. Corre en **escritorio y Android** con el mismo archivo: si la lógica de UI
   usa `java.*` por accidente, el job de Android lo detecta.
2. Corre en **milisegundos**: 100 tests en menos de un segundo. Un PR con
   2 minutos de tests es un PR que se mergea; uno con 12, no.
3. **El fallo es legible**: `assertEquals(PrimaryAction.STOP, state.primaryAction)`
   dice exactamente qué se rompió. Un fallo de composición dice "no se encontró
   el nodo".
4. No depende de la instability de Compose ni de Skia: no se rompe al subir de
   versión.

**Regla de diseño que hay que adoptar para que esta capa se pueda testear:**
*ningún `if` que decida qué se muestra vive dentro de un `@Composable`*. Si el
composable tiene que decidir algo, esa decisión vive en una función pura al
lado, con su test.

### 2.b Tests de composición en `desktopTest` — **SÍ, pero con presupuesto**

**Decisión: se hacen, pero son una fracción del total** (17 tests sobre 138).

**Qué aportan que la capa (a) no puede dar:**

- Verificar que la pantalla **realmente pinta** lo que el estado dice (que el
  composable use el derivador y no algo hardcodeado).
- Verificar el **árbol de semántica** (lo que lee el lector de pantalla).
- Medir que un texto **no se recorta** con fuente grande.

**El costo real (y por eso el presupuesto):**

| Problema | Consecuencia | Mitigación |
|---|---|---|
| `runComposeUiTest` es `@ExperimentalTestApi` en 1.7.3 | Al subir a CMP 1.8/1.9 la API puede cambiar (de hecho en 1.9 pasó a `androidx.compose.ui.test.v2`) | Todo el uso queda **aislado** en `:ui/desktopTest`; un cambio de API rompe **sólo** ese source set, y el arreglo es mechanical (cambiar el import) |
| Requiere el runtime de **Skia** (`compose.desktop.currentOs` en `desktopTest`) | El artefacto `ui-test` no trae Skia: sin esa dependencia la composición no arranca | Declarar `implementation(compose.desktop.currentOs)` en `desktopTest` |
| El `runComposeUiTest` interno usa `runTest` con un `TestScope` | Las corrutinas del ViewModel no avanzan solas: hay que usar `mainClock.advanceTimeBy` o `waitForIdle` | Los tests de composición **no** testean ViewModels: testean componentes **hoja** con estado local |
| `Dispatchers.Main` en escritorio = dispatcher de **Swing** (EDT) | Un ViewModel que lanza en `Dispatchers.Main` dentro de un test de composición **no** se sincroniza con el reloj de Compose ⇒ test *flaky* | D5: el ViewModel recibe el scope inyectado; en `desktopTest` se le pasa un `TestScope` |
| Es software-rendering: no hay GPU | Un golden pixel a pixel es frágil | D9: sin goldens como puerta |
| Un test de composición mal escrito **pasa sin comprobar nada**: si el árbol de nodos está vacío y el test busca con `useUnmergedTree` o con un matcher tolerante, la aserción no falla | Falsos verdes | Regla: todo test de composición **asserta al menos un texto o un estado visible**. Nada de "compila y no revienta" |

**El presupuesto, escrito como regla del proyecto:**

> **Regla P-UI-1.** En `:ui/desktopTest` sólo hay tests de **composición** de
> componentes hoja y de **semántica de accesibilidad**. Cada uno asserta **una**
> cosa visible. Si un test de composición necesita más de 10 líneas de
> preparación, la lógica que quiere verificar **está en el sitio equivocado**:
> va a la capa (a) como función pura.

**Lo que NO va a `:ui/desktopTest`:**

- Tests de ViewModel (van a `commonTest`: son más rápidos y más portables).
- Tests de la pantalla completa con navegación real (el ViewModel ya prueba la
  navegación; la composición sólo prueba que la pantalla pinta).
- Capturas golden.

### 2.c Tests de ViewModel en `commonTest` — **es la capa más valiosa**

**El ViewModel no necesita recomposición para leerse**: los `mutableStateOf` se
leen directamente (`vm.screen`, `vm.error`, `vm.messages`). Un test de
ViewModel es un test de funciones con estado: portable, rápido y legible.

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/KitoruViewModelTest.kt
package kitoru.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitoruViewModelTest {

    @Test
    fun `las catedras del docente se separan de las del alumno`() {
        val vm = KitoruViewModel(
            courses = FakeCourseRepository(curso("c1", "IA"), curso("c2", " mates")),
            enrollments = FakeEnrollmentRepository(
                inscripcion("u1", "c1", CourseRole.TITULAR),
                inscripcion("u1", "c2", CourseRole.ALUMNO),
            ),
            profiles = FakeProfileRepository(),
        )

        assertEquals(listOf("c1"), vm.teachingCourses.map { it.id })
        assertEquals(listOf("c2"), vm.learningCourses.map { it.id })
    }
}
```

**Lo que hay que cambiar en `KitoruViewModel` para que esto sea testeable**
(cambio de producción, mínimo y justificado):

```kotlin
class KitoruViewModel(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
    /** Scope de la app. Los tests inyectan el suyo: `Dispatchers.Main` no sirve. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Reloj. Los tests inyectan uno fijo: ningún test depende de la hora. */
    private val clock: TimeSource = TimeSource.System,
) {
    internal val scope = scope            // (hoy: `CoroutineScope(SupervisorJob() + Dispatchers.Main)`)
    …
}
```

Y el reloj, que hoy es `expect fun nowMillis()` global:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/TimeSource.kt
package kitoru.platform

/** Fuente del tiempo. Los tests inyectan una fija: ningún test lee el reloj. */
interface TimeSource {
    fun nowMillis(): Long
}

/** El reloj del sistema. */
object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = nowMillis()
}

/** Reloj fijo, para los tests. */
class FixedTimeSource(var now: Long = 0L) : TimeSource {
    override fun nowMillis(): Long = now
}
```

> **Por qué el reloj tiene que ser inyectable y no global**: la regla 1 de
> `docs/TESTING.md` de FunshiEngineAI dice "un test no lee el reloj". Con un
> `nowMillis()` global, un test que verifica "el mensaje de hace 5 minutos"
> falla un día sí y otro no. Con `TimeSource` inyectado, el test dice "5 minutos
> después de las 12:00".

**Lo que los tests de ViewModel cubren y los de composición no:**

- La **navegación** (a dónde va cada botón, qué vuelve de dónde).
- La **visibilidad** (qué ve el docente de cada alumno) — la regla de privacidad.
- Los **efectos** (guardar, borrar, pedir desbloqueo) con repositorios fake.
- El **streaming** (el texto llega por partes, el último mensaje se actualiza, no
  se crea uno nuevo por fragmento).
- Los **errores** (qué mensaje se muestra, qué se limpia).

---

## 3. La lista concreta de tests

Todos los archivos van en `:ui/src/commonTest/kotlin/kitoru/ui/` salvo los de
composición (`:ui/src/desktopTest/kotlin/kitoru/ui/`).

### 3.1 Capa (a) — lógica pura (`:ui/commonTest`)

#### `components/MarkdownParserTest.kt` — `parseMarkdown` (16)

| # | Nombre del test |
|---|---|
| 1 | `un texto vacío no da nodos` |
| 2 | `un encabezado conserva su nivel` |
| 3 | `un párrafo une las líneas` |
| 4 | `un bloque de código conserva el lenguaje` |
| 5 | `un bloque de código sin lenguaje es texto` |
| 6 | `un bloque de código sin cerrar queda marcado` |
| 7 | `el contenido del código no se interpreta como markdown` |
| 8 | `una lista con viñetas` |
| 9 | `una lista numerada` |
| 10 | `una cita` |
| 11 | `una regla horizontal` |
| 12 | `una tabla con encabezado y filas` |
| 13 | `una tabla sin separador no es una tabla` |
| 14 | `una fórmula centrada en una línea` |
| 15 | `una fórmula sin cerrar no se dibuja pero el texto no se pierde` |
| 16 | `el texto que sigue a una fórmula no se lo come` |

> Los 1–16 son **el mismo conjunto** que el `MarkdownTest` de FunshiEngineAI
> (`frontend/src/test/kotlin/app/ui/MarkdownTest.kt`), **portado** a `kotlin.test`
> y con los tres casos extra (13, 15, 16) que ya están implícitos en el parser
> de Kitoru. Es reutilización, no reinvención.

#### `components/InlineAnnotatedTest.kt` — `buildInlineAnnotated` (6)

| # | Nombre del test |
|---|---|
| 1 | `un texto sin marcas queda igual` |
| 2 | `el negrita queda en negrita` |
| 3 | `el tachado queda tachado` |
| 4 | `el código en línea queda en monoespaciado` |
| 5 | `un enlace muestra el texto y la dirección` |
| 6 | `una marca sin cerrar no rompe el texto` |

#### `components/MathSegmentTest.kt` — `splitMath` (8)

| # | Nombre del test |
|---|---|
| 1 | `un texto sin fórmulas es un solo tramo` |
| 2 | `una fórmula en línea se separa del texto` |
| 3 | `una fórmula centrada se marca como display` |
| 4 | `una fórmula sin cerrar queda como texto` |
| 5 | `los precios no se toman como fórmula` |
| 6 | `el código en línea no se interpreta como fórmula` |
| 7 | `una barra escapada no cierra la fórmula` |
| 8 | `dos fórmulas dan dos tramos` |

#### `components/CodeBlockNameTest.kt` — `codeBlockFileName(lang)` (5)

| # | Nombre del test |
|---|---|
| 1 | `kotlin se guarda como kt` |
| 2 | `python se guarda como py` |
| 3 | `un lenguaje desconocido se guarda como txt` |
| 4 | `el nombre del archivo no depende de las mayúsculas` |
| 5 | `un lenguaje vacío se guarda como txt` |

> Requiere sacar `LANG_TO_EXT` a una función `internal` testeable:
> `internal fun codeBlockFileName(lang: String): String`. `internal` de
> `commonMain` **es visible desde `commonTest`** (compilación asociada).

#### `format/SizeFormatTest.kt` — `sizeValue(bytes)` (6)

| # | Nombre del test |
|---|---|
| 1 | `un archivo de 500 bytes se mide en bytes` |
| 2 | `un archivo de 2 megabytes se mide en megabytes` |
| 3 | `un archivo de cero bytes no divide por cero` |
| 4 | `un tamaño negativo no rompe` |
| 5 | `el límite entre unidades es 1024` |
| 6 | `un gibibyte se mide en gibibytes` |

#### `format/PercentFormatTest.kt` — `rateToPercent(rate)` (5)

| # | Nombre del test |
|---|---|
| 1 | `una tasa de 0_75 se muestra como 75 por ciento` |
| 2 | `una tasa de 0 se muestra como 0 por ciento` |
| 3 | `una tasa de 1 se muestra como 100 por ciento` |
| 4 | `una tasa mayor que uno se muestra tal cual` |
| 5 | `el redondeo del porcentaje es el de medio arriba` |

#### `state/OverrideStateTest.kt` — `overrideStateFor(...)` (8)

| # | Nombre del test |
|---|---|
| 1 | `un campo sin override está heredado` |
| 2 | `un campo con valor propio está personalizado` |
| 3 | `un campo bloqueado queda bloqueado aunque el alumno lo toque` |
| 4 | `un campo bloqueado no muestra el switch` |
| 5 | `un campo bloqueado muestra el botón de pedir desbloqueo` |
| 6 | `un campo personalizado muestra el botón de volver al valor de la cátedra` |
| 7 | `sin override permitido todos los campos están bloqueados` |
| 8 | `la clave de la cátedra nunca es un valor propio del alumno` |

#### `state/ComposerStateTest.kt` — `composerState(...)` (8)

| # | Nombre del test |
|---|---|
| 1 | `con el composer vacío el botón enviar está deshabilitado` |
| 2 | `con texto el botón enviar está habilitado` |
| 3 | `sin permiso para chatear el botón enviar no hace nada` |
| 4 | `mientras responde el botón principal es detener` |
| 5 | `con cola pendiente el botón principal es cancelar el envío` |
| 6 | `el placeholder avisa que está escribiendo mientras responde` |
| 7 | `el placeholder avisa que el material de la cátedra todavía no está` |
| 8 | `en reposo el placeholder ofrece escribir una pregunta` |

#### `state/CitationBadgeTest.kt` — `citationBadge(stats)` (6)

| # | Nombre del test |
|---|---|
| 1 | `sin citas no hay badge` |
| 2 | `todas verificadas muestra el badge de citas verificadas` |
| 3 | `con citas sin respaldo el badge avisa` |
| 4 | `una cita a una fuente inexistente cuenta como sin respaldo` |
| 5 | `la tasa de respaldo se muestra redondeada a un entero` |
| 6 | `el badge nunca depende sólo del color` |

#### `theme/ContrastTest.kt` — contraste del esquema (3)

| # | Nombre del test |
|---|---|
| 1 | `el esquema claro cumple 4_5_1 en todos los pares de texto` |
| 2 | `el esquema oscuro cumple 4_5_1 en todos los pares de texto` |
| 3 | `todos los acentos predefinidos llegan a 4_5_1` |

> Es el `ContrastTest` que ya propone `docs/design/student/10-accesibilidad-i18n.md`
> §5.4. Corre en `commonTest` porque es aritmética sobre `Color`.

**Total capa (a): 71 tests.**

### 3.2 Capa (c) — ViewModel (`:ui/commonTest`)

#### `KitoruViewModelTest.kt` (12)

| # | Nombre del test |
|---|---|
| 1 | `sin cátedras la home del alumno queda vacía` |
| 2 | `las cátedras del docente se separan de las del alumno` |
| 3 | `un ayudante ve la cátedra en el panel del docente` |
| 4 | `un alumno no ve el panel del docente` |
| 5 | `volver desde el chat del alumno vuelve a la home del alumno` |
| 6 | `volver desde la analítica vuelve a la home del docente` |
| 7 | `abrir una cátedra deja activa la cátedra` |
| 8 | `el rol de la cátedra activa sale de la inscripción` |
| 9 | `sin inscripción la visibilidad es ninguna` |
| 10 | `un error al cargar deja un mensaje y no deja la pantalla rota` |
| 11 | `crear un perfil lo guarda y recarga` |
| 12 | `el error se borra al navegar` |

#### `StudentChatViewModelTest.kt` (14)

| # | Nombre del test |
|---|---|
| 1 | `enviar una pregunta vacía no hace nada` |
| 2 | `enviar una pregunta agrega el turno del alumno y el del modelo` |
| 3 | `el texto del modelo llega por streaming y actualiza el último mensaje` |
| 4 | `el streaming no crea un mensaje nuevo por fragmento` |
| 5 | `terminar la respuesta apaga la bandera de streaming` |
| 6 | `la cita se calcula al terminar y no durante el streaming` |
| 7 | `un error del modelo deja el mensaje con el error y sin streaming` |
| 8 | `detener la respuesta corta el streaming` |
| 9 | `lo que se escribe mientras responde queda en cola` |
| 10 | `la cola se envía al terminar la respuesta` |
| 11 | `cancelar la cola descarta lo pendiente` |
| 12 | `el historial que se manda al modelo está recortado` |
| 13 | `sin material de la cátedra la respuesta avisa que no encontró nada` |
| 14 | `el modelo que se usa es el efectivo de la política resuelta` |

#### `StudentConfigViewModelTest.kt` (10)

| # | Nombre del test |
|---|---|
| 1 | `el estado de cada campo sale de la política y de los ajustes del alumno` |
| 2 | `un campo bloqueado no se puede editar` |
| 3 | `volver al valor de la cátedra borra el override del alumno` |
| 4 | `un override inválido no se guarda` |
| 5 | `la configuración que se usa es la resuelta con los bloqueos` |
| 6 | `la clave de la cátedra no se muestra completa` |
| 7 | `probar conexión usa los valores efectivos y no los del formulario` |
| 8 | `el error de validación señala el campo que falla` |
| 9 | `pedir desbloqueo deja un pedido pendiente` |
| 10 | `un preset de la cátedra no pisa los campos bloqueados` |

#### `TeacherAnalyticsViewModelTest.kt` (8)

| # | Nombre del test |
|---|---|
| 1 | `sin analítica habilitada la pantalla queda vacía` |
| 2 | `la analítica muestra sólo los alumnos visibles` |
| 3 | `un alumno oculto no aparece en ningún agregado` |
| 4 | `la tasa de citas se muestra como porcentaje` |
| 5 | `el filtro por día ordena las respuestas` |
| 6 | `un error al analizar deja un mensaje` |
| 7 | `el rango de fechas acota los mensajes` |
| 8 | `exportar genera un CSV con las cabeceras correctas` |

#### `TeacherChatListViewModelTest.kt` (6)

| # | Nombre del test |
|---|---|
| 1 | `un alumno con visibilidad completa muestra sus chats` |
| 2 | `un alumno con métricas sólo muestra los conteos y no el texto` |
| 3 | `un alumno sin visibilidad no aparece` |
| 4 | `el filtro por fuente de material funciona` |
| 5 | `el chat abierto muestra sus mensajes` |
| 6 | `un mensaje con citas sin respaldo se distingue del resto` |

**Total capa (c): 50 tests.**

### 3.3 Capa (b) — composición (`:ui/desktopTest`)

> Presupuesto: **17 tests**. Todos usan `runComposeUiTest` y **no** testean
> ViewModels (sólo componentes hoja con estado local).

#### `components/MarkdownComposeTest.kt` (3)

| # | Nombre del test |
|---|---|
| 1 | `el markdown dibuja el encabezado con su nivel` |
| 2 | `la tabla dibuja una fila por cada fila de la tabla` |
| 3 | `el bloque de código muestra el botón de copiar` |

#### `chat/ComposerBarComposeTest.kt` (3)

| # | Nombre del test |
|---|---|
| 1 | `el botón enviar está deshabilitado con el composer vacío` |
| 2 | `el botón principal cambia a detener mientras responde` |
| 3 | `el estado vacío dice qué hacer` |

#### `chat/MessageBubbleSemanticsTest.kt` (3)

| # | Nombre del test |
|---|---|
| 1 | `el rótulo del turno es un encabezado para el lector de pantalla` |
| 2 | `la tarjeta de herramienta expone su estado` |
| 3 | `un mensaje vacío dice que está vacío` |

#### `components/ErrorBannerSemanticsTest.kt` (2)

| # | Nombre del test |
|---|---|
| 1 | `el banner de error es una región viva asertiva` |
| 2 | `el banner ofrece reintentar cuando el error es reintentable` |

#### `components/CodeBlockComposeTest.kt` (2)

| # | Nombre del test |
|---|---|
| 1 | `el botón de copiar cambia su descripción a copiado` |
| 2 | `el bloque sin cerrar avisa que está en curso` |

#### `a11y/LargeFontTest.kt` (2)

| # | Nombre del test |
|---|---|
| 1 | `el chat no se rompe con la fuente al 200 por ciento` |
| 2 | `la analítica no desborda con la fuente al 200 por ciento` |

#### `a11y/SourceAuditTest.kt` (2)

| # | Nombre del test |
|---|---|
| 1 | `ningún icono se queda sin descripción` |
| 2 | `ningún texto literal se escribe directo en la pantalla` |

**Total capa (b): 17 tests.**

### 3.4 Conteo

| Capa | Source set | Tests | Velocidad |
|---|---|---|---|
| (a) Lógica pura | `:ui/commonTest` | 71 | < 1 s |
| (c) ViewModel | `:ui/commonTest` | 50 | < 2 s |
| (b) Composición | `:ui/desktopTest` | 17 | ~10 s |
| **Total `:ui`** | | **138** | ~13 s |

Los 138 tests corren **dos veces**: en escritorio (`desktopTest`) y en Android
(`testDebugUnitTest`, sin emulador), salvo los 17 de composición.

---

## 4. Las reglas de `docs/TESTING.md` adaptadas a Kitoru

Las cuatro reglas de FunshiEngineAI se mantienen **al pie de la letra** y se
agregan las que hacen falta para la UI.

### 4.1 Las reglas heredadas (sin cambios)

1. **Deterministas.** Un test no toca la red, no lee el reloj y no depende del
   disco del usuario. *En la UI esto obliga a que el reloj sea inyectable
   (`TimeSource`) y a que los gateways sean guiones.*
2. **Un comportamiento por test.** El nombre dice qué tiene que pasar, en
   español, entre acentos graves: ``fun `una tabla sin separador no es una tabla`() ``.
3. **Fallar es información.** El mensaje tiene que explicar qué se esperaba y
   qué se obtuvo. *Regla concreta para la UI: el assert compara **estados**, no
   cadenas de pantalla; si se compara un texto, el mensaje dice cuál.*
4. **Sin red, sin esperas.** Nada de `Thread.sleep` ni de esperar a un servicio
   externo. *En la UI: nada de `delay()` real; el reloj de Compose (`mainClock`)
   es el único reloj que se puede mover, y sólo en `desktopTest`.*

### 4.2 Las reglas nuevas de Kitoru para la UI

| # | Regla | Por qué |
|---|---|---|
| **U1** | **Ninguna decisión dentro de un `@Composable`.** Si el composable tiene que elegir entre dos textos, dos íconos o dos estados, esa decisión vive en una función pura en `commonMain` y tiene su test. | Es lo que hace que la capa (a) exista. Sin esta regla, la capa (a) es vacía. |
| **U2** | **Un test de UI afirma sobre el estado, no sobre el texto.** Se asserta `ComposerState.primaryAction == STOP`, no "el botón dice Detener". El texto se testea en la capa (b), si hace falta. | El texto sale del catálogo de recursos y cambia con el idioma: assertarlo en `commonTest` ata el test a un idioma. |
| **U3** | **Ningún test de UI depende de `Dispatchers.Main`.** Todo ViewModel recibe su scope por constructor; los tests inyectan el suyo (`TestScope` o `UnconfinedTestDispatcher`). | En escritorio `Dispatchers.Main` es el EDT de Swing: el test avanzaría antes de que la corrutina corra ⇒ *flaky*. |
| **U4** | **Ningún test de UI compara capturas de pantalla.** | Fragilidad + ruido. Las capturas se reportan en el nightly, no bloquean. |
| **U5** | **Todo test de composición asserta algo visible.** Un test que sólo "compila y no revienta" no se escribe. | Evita los falsos verdes. |
| **U6** | **El presupuesto de `desktopTest` es de 17 tests.** Si hace falta uno más, primero se pregunta si el comportamiento no debería ser una función pura. | La instability de Compose no puede crecer sin límite. |
| **U7** | **Ningún test de UI toca el disco del usuario.** El FS real se prueba en `:core/desktopTest` con un directorio temporal; la UI usa el doble en memoria. | Determinismo. |
| **U8** | **Los tests que dependen del modelo real se marcan** con `@Tag("model")` y **no** corren en el PR. | La calidad del modelo no es determinista. |

### 4.3 El criterio: "un test debe fallar si el comportamiento cambia"

Es la regla que más se olvida. Para cada capa, el criterio seformula así:

| Capa | Pregunta que el test tiene que responder | Cómo se verifica |
|---|---|---|
| (a) Lógica pura | "¿Cambió lo que la pantalla decide?" | Mutá el parser (agregá un caso borde) y el test tiene que **fallar**. Si sigue verde, el test no ata comportamiento: sobra. |
| (c) ViewModel | "¿Cambió lo que la pantalla **puede hacer**?" | Mutá el permiso (pasá `canChat = false`) y el test tiene que fallar. |
| (b) Composición | "¿Cambió lo que la pantalla **muestra**?" | Borrá el `Text` del composable y el test tiene que fallar. |

**Test deAUTOmatización de la regla** (opcional, nightly): mutar el parser de
markdown y comprobar que la suite se pone roja. Se deja anotado, no se
implementa: mutar a mano es más barato.

---

## 5. Los dobles de prueba

### 5.1 `:testkit` — el módulo de los dobles compartidos

**Decisión (D6):** los dobles que necesitan más de un módulo van en un módulo
KMP nuevo, `:testkit`, con **todo en `commonMain`** (los dobles son código de
test, pero si viven en `commonTest` de un módulo, los otros módulos no los ven).

```
testkit/
├── build.gradle.kts          # KMP: jvm("desktop") + androidTarget, sin plugins de UI
└── src/commonMain/kotlin/kitoru/testkit/
    ├── InMemoryFileSystem.kt   # doble de kitoru.platform.FileSystem
    ├── FakeRepositories.kt     # Course/Enrollment/Material/Thread/Message/Profile
    ├── ScriptedGateway.kt      # doble de llm.api.LlmGateway
    ├── Corpus.kt               # la cátedra falsa (3 documentos + 10 preguntas)
    └── Fixtures.kt             # builders de dominio + constantes de tiempo
```

```kotlin
// testkit/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm("desktop")
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    sourceSets {
        val commonMain by getting
        commonMain.dependencies {
            api(project(":core"))
            api(project(":llm-api"))
            api(libs.kotlinx.coroutines.core)
        }
        val jvmCommonMain by creating { dependsOn(commonMain) }
        desktopMain.dependsOn(jvmCommonMain)
        androidMain.dependsOn(jvmCommonMain)
    }
}
```

Y en cada módulo que testea:

```kotlin
// ui/build.gradle.kts
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(libs.kotlinx.coroutines.test)
    implementation(project(":testkit"))     // ← los dobles
}
```

> **Conflicto con el doc hermano.** `docs/design/analytics/08-testing.md` §1.3 y
> §6.1 ubican `InMemoryFileSystem` y `Fixtures` en
> `core/src/commonTest/kotlin/kitoru/test/`. Con la capa (b) y (c) de este
> documento, `:ui` también los necesita ⇒ **hay que moverlos a `:testkit`** y
> actualizar ese documento. No es un cambio de fondo: es el mismo código, en un
> lugar visible desde los tres módulos. Lo que **no** se hace es duplicarlos.

### 5.2 `InMemoryFileSystem` — el que todavía no existe

**Estado:** `:core` tiene la interfaz `FileSystem` (`exists`, `isDirectory`,
`sizeBytes`, `readText`, `writeText`, `delete`, `createDirectories`,
`listFiles`, `copy`) y la implementación real sobre `java.io.File`
(`systemFileSystem()`), pero **no hay doble en memoria**. Hay que escribirlo.

```kotlin
// testkit/src/commonMain/kotlin/kitoru/testkit/InMemoryFileSystem.kt
package kitoru.testkit

import kitoru.platform.FilePath
import kitoru.platform.FileSystem

/**
 * Sistema de archivos en memoria.
 *
 * Sirve para testear la persistencia sin tocar el disco: los tests son
 * deterministas, no dependen del sistema de archivos real y funcionan igual en
 * el escritorio y en Android.
 *
 * Además de la interfaz, expone ganchos para simular fallas:
 *  - [fallarEscritura]: hace fallar la escritura a una ruta;
 *  - [sembrar]: escribe un archivo directamente (para simular corrupción).
 */
class InMemoryFileSystem : FileSystem {

    private val archivos = mutableMapOf<String, String>()
    private val carpetas = mutableSetOf<String>()

    /** Si devuelve `true`, la escritura a esa ruta falla. */
    var fallarEscritura: (FilePath) -> Boolean = { false }

    /** Contadores, para aserciones. */
    var lecturas = 0
        private set
    var escrituras = 0
        private set

    // --- helpers ---

    private fun clave(p: FilePath): String {
        val v = p.value.replace('\\', '/')
        return if (v.length > 1) v.trimEnd('/') else v
    }

    private fun padreDe(c: String): String? {
        val i = c.lastIndexOf('/')
        return if (i <= 0) null else c.substring(0, i)
    }

    private fun crearCarpetas(c: String) {
        var actual = c
        while (actual.isNotEmpty() && actual != "/") {
            carpetas += actual
            actual = padreDe(actual) ?: ""
        }
    }

    // --- FileSystem ---

    override fun exists(path: FilePath): Boolean {
        val c = clave(path)
        return archivos.containsKey(c) || carpetas.contains(c)
    }

    override fun isDirectory(path: FilePath): Boolean = carpetas.contains(clave(path))

    override fun sizeBytes(path: FilePath): Long = archivos[clave(path)]?.length?.toLong() ?: 0L

    override fun readText(path: FilePath): String {
        lecturas++
        val c = clave(path)
        return archivos[c] ?: throw IllegalStateException("No existe el archivo: $c")
    }

    override fun writeText(path: FilePath, text: String) {
        val c = clave(path)
        if (fallarEscritura(path)) {
            throw IllegalStateException("Fallo de escritura simulado: $c")
        }
        padreDe(c)?.let { crearCarpetas(it) }
        archivos[c] = text
        escrituras++
    }

    override fun delete(path: FilePath) {
        val c = clave(path)
        archivos -= c
        carpetas -= c
        val prefijo = "$c/"
        archivos.keys.filter { it.startsWith(prefijo) }.toList().forEach { archivos -= it }
        carpetas.filter { it.startsWith(prefijo) }.toList().forEach { carpetas -= it }
    }

    override fun createDirectories(path: FilePath) {
        crearCarpetas(clave(path))
    }

    override fun listFiles(dir: FilePath): List<FilePath> {
        val c = clave(dir)
        return archivos.keys
            .filter { padreDe(it) == c }
            .sorted()
            .map { FilePath(it) }
    }

    override fun copy(from: FilePath, to: FilePath) {
        val o = clave(from)
        val texto = archivos[o] ?: throw IllegalStateException("No existe el archivo: $o")
        writeText(to, texto)
    }

    // --- ganchos de test ---

    /** Escribe un archivo directamente, saltando la lógica de escrituras. */
    fun sembrar(path: FilePath, texto: String) {
        val c = clave(path)
        padreDe(c)?.let { crearCarpetas(it) }
        archivos[c] = texto
    }

    /** Rutas de todos los archivos (no carpetas). */
    fun archivos(): Set<String> = archivos.keys.toSet()

    /** Texto crudo de un archivo, o `null` si no está. */
    fun texto(path: FilePath): String? = archivos[clave(path)]
}
```

> **Requisito pendiente (del doc de analítica, H6):** la escritura atómica
> necesita `FileSystem.move(from, to)`. Si se agrega `move` a la interfaz, el
> doble **tiene que implementarlo** (mismo `override fun move`), si no el
> proyecto no compila. Se deja anotado para que los dos documentos no se
> pisen.

### 5.3 Repositorios fake

```kotlin
// testkit/src/commonMain/kotlin/kitoru/testkit/FakeRepositories.kt
package kitoru.testkit

import kitoru.domain.*

/**
 * Repositorio en memoria.
 *
 * Implementa el contrato real (`CourseRepository` etc.) para que los tests
 * usen el mismo código de producción contra datos controlados. Guarda todo en
 * listas; `list()` devuelve una copia para que el test no pueda mutar el
 * estado interno por accidente.
 */
class FakeCourseRepository(initial: List<Course> = emptyList()) : CourseRepository {
    private val items = initial.associateBy { it.id }.toMutableMap()
    override suspend fun list(): List<Course> = items.values.sortedBy { it.id }
    override suspend fun get(id: String): Course? = items[id]
    override suspend fun save(course: Course) { items[course.id] = course }
    override suspend fun delete(id: String) { items -= id }
}

/** Igual para los demás repositorios (enrollments, materials, threads, messages, profiles). */
class FakeEnrollmentRepository(initial: List<Enrollment> = emptyList()) : EnrollmentRepository {
    private val items = initial.associateBy { it.id }.toMutableMap()
    override suspend fun listForCourse(courseId: String) = items.values.filter { it.courseId == courseId }
    override suspend fun listForUser(userId: String) = items.values.filter { it.userId == userId }
    override suspend fun get(id: String): Enrollment? = items[id]
    override suspend fun save(enrollment: Enrollment) { items[enrollment.id] = enrollment }
    override suspend fun delete(id: String) { items -= id }
}
```

**Por qué repositorios fake y no el FS en memoria en los tests de ViewModel:**
el ViewModel no sabe de disco; sabe de `CourseRepository`. Un fake en memoria
hace el test **más rápido** y el error **más claro** ("el repo no devolvió la
cátedra" en vez de "el JSON estaba roto"). El FS en memoria se usa en los tests
de **persistencia** (`:core`), donde sí importa.

### 5.4 `ScriptedGateway` — portado a `commonTest`

El doble ya existe en FunshiEngineAI y está portado en
`:backend/src/desktopTest/kotlin/rag/agent/AgentLoopTest.kt` (anidado en la
clase). El doc de analítica propone extraerlo a
`backend/src/desktopTest/kotlin/rag/test/ScriptedGateway.kt`.

**Para la UI hace falta en `commonTest`** (los tests de ViewModel del chat lo
usan) ⇒ va a `:testkit`:

```kotlin
// testkit/src/commonMain/kotlin/kitoru/testkit/ScriptedGateway.kt
package kitoru.testkit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage

/**
 * Gateway que responde con un guion de rondas prefijado.
 *
 * Es el doble de prueba que ya usaba el `AgentLoopTest` de FunshiEngineAI
 * (portado a Kitoru); se extrae a su propio archivo y al módulo `:testkit`
 * para que lo usen `:backend` y `:ui`.
 *
 * Cada request queda registrado en [requests] para poder assertear qué se le
 * pasó al modelo.
 */
class ScriptedGateway(private val script: List<List<LlmChunk>>) : LlmGateway {

    /** Cada request que recibió el gateway. */
    val requests = mutableListOf<List<ChatMessage>>()

    /** El catálogo de tools que recibió en cada ronda (null = modo por prompt). */
    val catalogs = mutableListOf<List<ToolSpec>?>()

    /** Cuántas rondas consumió. */
    var round = 0
        private set

    /** Si es `true`, el próximo `chatStream` falla. */
    var fallarEnLaRonda: Int? = null

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> {
        requests += messages
        catalogs += tools
        if (fallarEnLaRonda == round) {
            round++
            return flow { throw IllegalStateException("Fallo simulado del gateway") }
        }
        val chunks = script.getOrElse(round) { emptyList() }
        round++
        return flow { chunks.forEach { emit(it) } }
    }
}
```

> **Portar, no reescribir:** el cuerpo es el del `AgentLoopTest` de
> FunshiEngineAI, con dos agregados mínimos que la UI necesita: `fallarEnLaRonda`
> (para probar el camino de error) y `requests` (para verificar qué se le
> mandó al modelo). El `AgentLoopTest` pasa a usar este doble compartido.

### 5.5 Otros dobles

| Doble | Dónde | Para qué |
|---|---|---|
| `FixedTimeSource` | `:core/commonMain` (producción, inyectable) | Congelar el reloj |
| `FakeFileSaver` | `:ui/desktopTest` | El "Guardar como…" del bloque de código: en `desktopTest` se puede fakear la plataforma; en `commonTest` **no** (es `expect/actual`), por eso la lógica de nombre de archivo se extrae a `codeBlockFileName` (testeable en `commonTest`) |
| `FakeSecretStore` | `:testkit` | La clave de API del alumno: la pantalla de config la enmascara |

---

## 6. El caso especial de `CitationVerifier`

### 6.1 Estado actual

`backend/src/desktopTest/kotlin/rag/engine/CitationVerifierTest.kt` — **24 tests**,
JUnit 5, sobre `rag.engine.CitationVerifier` (que vive en
`:backend/commonMain`). Es la pieza que produce la métrica de calidad que ve el
docente (`CitationStats.verified / unsupported / unknown`).

### 6.2 ¿Deberían moverse a `commonTest`?

**Decisión: SÍ (D7).**

**Costo de la conversión (evaluado en detalle):**

| Trabajo | Costo | Nota |
|---|---|---|
| Mover el archivo a `backend/src/commonTest/kotlin/rag/engine/` | 1 min | `git mv` |
| Cambiar los imports | 2 min | `org.junit.jupiter.api.Test` → `kotlin.test.Test`; `org.junit.jupiter.api.Assertions.assertEquals/assertTrue/assertFalse` → `kotlin.test.assertEquals/assertTrue/assertFalse`. **Los nombres y el orden de los argumentos son idénticos** (`assertEquals(expected, actual, message)`), así que el cuerpo de los 24 tests **no se toca** |
| Revisar `runTest` | 5 min | `rag/TestSupport.kt` define `fun runTest(block) = runBlocking { block() }`. **`runBlocking` no existe en `commonMain`** de `kotlinx-coroutines-core` ⇒ el helper tiene que pasar a `kotlinx.coroutines.test.runTest` (que sí es common). Ojo con el choque de nombres: `import kotlinx.coroutines.test.runTest` y el propio `runTest` local |
| `useJUnitPlatform()` | 5 min | Hay que **acotarlo a la tarea de escritorio** (H-UI-1) para que `kotlin.test` resuelva a JUnit 5 en escritorio y a JUnit 4 en Android |
| Agregar `junit:junit:4.13.2` a `androidUnitTest` | 2 min | `kotlin-test-junit` lo necesita en Android |
| Correr en los dos targets | 5 min | `./gradlew :backend:desktopTest :backend:testDebugUnitTest` |

**Costo total: ≈ 20 minutos.** **Beneficio:**

1. El verificador es **la métrica que ve el docente**: que corra en Android
   garantiza que la app del alumno calcula las citas igual que la de escritorio.
2. Es el **piloto** que abre la puerta a los otros 7 archivos de `:backend`
   (`Bm25IndexTest`, `ChunkerTest`, `AgentLoopTest`, `ToolCallSanitizerTest`,
   `ToolPermissionsTest`, `ToolRegistryTest`, `PromptToolParserTest`): **todos
   son puros y portables**. Con D7, `:backend` pasa a tener `commonTest` real.
3. Elimina la dependencia de JUnit 5 en `desktopTest`: `:backend` queda con
   **un solo framework** (`kotlin.test`), que es lo que pide la regla "mezclar
   frameworks en un mismo módulo es peor que la alternativa" del doc de
   analítica.

**Riesgos y cómo se mitigan:**

| Riesgo | Mitigación |
|---|---|
| `AgentLoopTest` usa corrutinas y `runBlocking` | Se migra junto con el helper; `kotlinx-coroutines-test` ya está en `commonTest` |
| Los tests de JUnit 5 tienen `assertEquals(1, report.verified)` con `Int` | `kotlin.test.assertEquals` genérico funciona igual |
| Mezcla de frameworks si quedan tests JUnit 5 en `desktopTest` | **Regla:** después de la migración, `:backend` usa `kotlin.test` **en todas partes**. Si queda un JUnit 5, se migra también |

**El plan, en tres pasos:**

1. Mover `CitationVerifierTest` a `commonTest` y convertirlo a `kotlin.test`.
   Verificar que corre en escritorio **y** en `androidUnitTest`.
2. Mover `rag/TestSupport.kt` a `commonTest` con `kotlinx.coroutines.test.runTest`.
3. (Siguiente milestone) Migrar los otros 7 archivos, en el mismo movimiento.

> **Conclusión de costo/beneficio: 20 minutos de trabajo para que el test de la
> métrica de calidad corra en las dos plataformas. Se hace.**

---

## 7. Los tests de accesibilidad

### 7.1 ¿Se puede testear que un botón tiene `contentDescription`?

**Sí, pero sólo con una composición viva** — o sea, en `:ui/desktopTest`.

El detalle técnico: `SemanticsNodeInteractionsProvider` **existe en el
`commonMain` del artefacto `ui-test`** (o sea, el código que lo usa puede
escribir en `commonTest`), **pero** el árbol de nodos de semántica sólo existe
con una composición en marcha. Por eso:

- `commonTest`: **no** se puede assertar sobre `contentDescription`.
- `desktopTest` con `runComposeUiTest`: **sí**:

```kotlin
// ui/src/desktopTest/kotlin/kitoru/ui/chat/MessageBubbleSemanticsTest.kt
package kitoru.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MessageBubbleSemanticsTest {

    @Test
    fun `el boton de enviar tiene descripcion accesible`() = runComposeUiTest {
        setContent { KitoruTheme { ComposerBar(...) } }

        onNodeWithContentDescription("Enviar mensaje").assertIsDisplayed()
    }
}
```

### 7.2 Decisión: tres mecanismos, en orden de fuerza

| # | Mecanismo | Dónde | Qué atrapa | Costo |
|---|---|---|---|---|
| **1** | **Tipado**: `KitoruIconButton(a11yDescription: String, …)` — la descripción es un parámetro **obligatorio** | `:ui/commonMain` | El icono sin descripción **no compila** | Ya está diseñado en el doc de accesibilidad §5.2 |
| **2** | **Árbol de semántica**: `onNodeWithContentDescription`, `assertIsDisplayed`, `assert(hasStateDescription())` | `:ui/desktopTest` | Que la descripción **exista en pantalla**, que el heading sea un heading, que el live region sea una live region | 8 tests (los de §3.3) |
| **3** | **Auditoría de fuente**: un test que **lee los `.kt` de `commonMain`** y busca `Icon(` sin `contentDescription` | `:ui/desktopTest` | El icono nuevo sin descripción, en **cualquier** archivo, sin tener que componer la pantalla | 1 test |

El mecanismo (3) es una red de seguridad barata y muy efectiva: el error más
común de accesibilidad es "se agregó un ícono y se olvidó la descripción", y
ese error **no** aparece en los tests de semántica si nadie compone esa
pantalla.

```kotlin
// ui/src/desktopTest/kotlin/kitoru/ui/a11y/SourceAuditTest.kt
package kitoru.ui.a11y

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

/**
 * Auditoría de las fuentes de la UI.
 *
 * No compone nada: lee los `.kt` de `commonMain` y verifica las reglas que
 * el compilador no puede expresar:
 *
 *  - todo `Icon(` lleva `contentDescription` (explícito o `null` decorativo);
 *  - todo `Text("literal")` pasa por el catálogo de recursos.
 *
 * Es una red de seguridad para el código **nuevo**: las pantallas viejas se
 * auditan una vez a mano.
 *
 * La ruta de las fuentes la pasa el build:
 * `systemProperty("kitoru.uiSources", "<project>/src/commonMain/kotlin")`.
 */
class SourceAuditTest {

    private val raiz: File = File(
        System.getProperty("kitoru.uiSources")
            ?: error("Falta la system property kitoru.uiSources (la define :ui/build.gradle.kts)"),
    )

    @Test
    fun `ningun icono se queda sin descripcion`() {
        val culpables = raiz.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexed { i, l -> f to (i + 1) to l } }
            .filter { (_, _, linea) ->
                Regex("""\bIcon\s*\(""").containsMatchIn(linea) &&
                    !linea.contains("contentDescription") &&
                    !linea.contains("Icon(") // (el caso multilínea se revisa aparte)
            }
            .map { (f, (n, _)) -> "${f.name}:$n" }
            .toList()

        assertEquals(emptyList(), culpables, "iconos sin contentDescription: $culpables")
    }
}
```

> Este test tiene que ser **tolerante a los casos multilínea** (`Icon(\n … )`):
> la versión final busca el bloque entre `Icon(` y el `)` que lo cierra, no la
> línea. Es un test de grep, no un parser: se documenta como tal y se acepta
> que dé algún falso positivo (que se revisan a mano).

### 7.3 Qué **no** se testea de accesibilidad

| Qué | Por qué no |
|---|---|
| El **orden de tabulación** real | Es el orden del árbol de composición: se verifica con la **estructura del código** (el layout tiene que coincidir con el orden de lectura) y a mano en QA |
| El **anillo de foco** visible | Es un `drawBehind`: no hay API para assertar "se dibujó un anillo". Se verifica a mano y con un test de layout si aparece |
| El **lector de pantalla** real (TalkBack / VoiceOver) | Requiere dispositivo. El árbol de semántica es el proxy: si el nodo tiene la descripción correcta, el lector la dirá |
| El **tamaño mínimo del objetivo** (48 dp / 40 dp) | Se puede medir con `onNodeWithTag(...).getUnclippedBoundsInRoot()` en `desktopTest`; **se deja para el milestone 2** (es un test útil, pero depende del layout, que es justo lo que D9 quiere evitar) |

---

## 8. El pipeline

### 8.1 Qué corre en cada PR (obligatorio, < 5 minutos)

**Job 1 — `tests-rápidos` (escritorio).**

```bash
./gradlew :core:desktopTest :backend:desktopTest :llm-api:desktopTest :ui:desktopTest
```

- Corre los 138 tests de `:ui` + los de `:core`, `:backend` y `:llm-api`.
- Sin emulador, sin red, sin disco de usuario.
- **Puerta**: si falla, el PR no se mergea.

**Job 2 — `android-unit` (Android, sin emulador).**

```bash
./gradlew :core:testDebugUnitTest :backend:testDebugUnitTest :ui:testDebugUnitTest
```

- Corre **los mismos fuentes de `commonTest`** en la JVM de Android.
- No necesita emulador: `testDebugUnitTest` corre en el host.
- **Puerta**: si falla, el PR no se mergea.
- **Por qué es obligatorio**: es lo que convierte `commonTest` en una garantía y
  no en una aspiración. Sin este job, `commonTest` es "el mismo código en
  dos lugares" y nadie se entera de que la UI se le escapa a la JVM.

**Job 3 — `lint` (rápido).**

```bash
./gradlew :ui:compileKotlinDesktop :ui:compileDebugKotlinAndroid
```

Detecta que la UI sigue compilando en los dos targets (el `commonTest` no
compila si el `commonMain` se rompe).

### 8.2 Reglas de control (para que "los tests pasan" signifique algo)

1. **El total de tests tiene que ser > 0.** Es el bug H10 del doc de analítica:
   hoy `:backend` reporta 0 tests y el build pasa en verde (ya está arreglado
   ahí; **no está arreglado en `:core` ni en `:ui`**, que no declaran
   `useJUnitPlatform()`). En CI, un paso que falle si el reporte dice
   `tests = 0`.
2. **Ningún test del PR toca la red.** Lo que la necesite va marcado
   `@Tag("network")` y excluido.
3. **Ningún test del PR depende del reloj.** `TimeSource` inyectado.
4. **Ningún test del PR escribe en el disco del usuario.** El FS real se prueba
   en un directorio temporal; el resto usa el doble en memoria.
5. **Los tests que fallan hoy** están marcados en el código con un comentario
   que enlace al hallazgo; cuando se arregla, se borra el comentario.

### 8.3 Qué corre a la noche (con modelo real)

**Job 4 — `modelo-real` (nightly).**

Arranca un backend de modelo real (llama-server con un modelo chico, o un
Kitoru-Server de prueba) y corre el **corpus de la cátedra falsa** (§9) de punta
a punta: 10 preguntas × (preparar contexto → responder → verificar citas).

Qué mide y qué **no** es puerta:

| Métrica | Puerta | Cómo |
|---|---|---|
| El modelo responde | **Sí** | Si el gateway falla, el nightly falla (avisa de que el modelo está caído) |
| La tasa de citas respaldadas | **No** (tendencia) | Se registra y se compara con la corrida anterior: una caída grande avisa, pero no bloquea |
| La latencia | **No** (tendencia) | Se registra el percentil 95 |
| Las citas del verificador | **Sí** (determinista) | `CitationVerifier` sobre la respuesta real: si el verificador tira, es un bug de código, no del modelo |

> Regla: lo que es **determinista** (el verificador, el parser, el RAG) es puerta
> en el PR. Lo que depende del **modelo** (la calidad de la respuesta) es
> tendencia en el nightly. La línea entre "bug" y "calidad" no se cruza.

**Job 5 — `capturas` (nightly).**

Renderiza las 5 pantallas principales (home alumno, chat, config, home docente,
analítica) con la fuente al 100 % y al 200 %, guarda los PNG en
`build/screenshots/<fecha>/` y **publica el diff con el nightly anterior** como
artefacto de la corrida.

**No es puerta** (D9): un golden que falla por el antialiasing de otra máquina
entrena al equipo a ignorar los fallos. Se publica para que el ojo humano
diga "esto cambió".

### 8.4 Los tags

| Tag | Qué marca | ¿Corre en el PR? |
|---|---|---|
| (ninguno) | Test determinista, rápido, portable | **Sí** |
| `@Tag("network")` | Necesita red | **No** |
| `@Tag("model")` | Necesita un modelo real | **No** |
| `@Tag("perf")` | Mide rendimiento (frágil en CI) | **No** |

En Gradle:

```kotlin
// ui/build.gradle.kts (y en los demás módulos)
tasks.withType<Test>().configureEach {
    useJUnitPlatform() {
        excludeTags("network", "model", "perf")
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
```

---

## 9. Los fixtures: la cátedra falsa

### 9.1 El contenido

Una cátedra ficticia, completa y **pequeña**, con material que permite probar
el RAG de punta a punta:

```
Corpus
├── material-1.md      "Búsqueda heurística"      (~900 palabras, 3 encabezados)
├── material-2.md      "Árboles de decisión"      (~600 palabras, 2 encabezados)
└── material-3.txt     "Programa de la cátedra"   (texto plano, ~200 palabras)
```

Y **10 preguntas**, cada una con:

| Campo | Ejemplo | Para qué |
|---|---|---|
| `pregunta` | "¿Qué diferencia hay entre búsqueda exhaustiva y heurística?" | La pregunta que se le hace al modelo |
| `documentoEsperado` | `"material-1.md"` | Qué documento **debe** recuperar el RAG (determinista ⇒ testeable en `commonTest`) |
| `fraseClave` | `"no garantiza el óptimo"` | La frase que tiene que aparecer en el material recuperado |
| `respuestaIdeal` | "La búsqueda heurística no garantiza… [1]" | Qué se espera que responda el modelo (para el nightly) |
| `nota` | "pide que compare las dos" | Lo que tiene que responder **el modelo** (no el RAG) |

### 9.2 Dónde vive

**Decisión (D10): en código Kotlin, en `:testkit/commonMain`, y exportado a
`fixtures/catedra-falsa/` con una tarea de Gradle.**

```kotlin
// testkit/src/commonMain/kotlin/kitoru/testkit/Corpus.kt
package kitoru.testkit

/**
 * La cátedra falsa.
 *
 * Es el corpus de prueba de Kitoru: tres documentos y diez preguntas. Vive en
 * código (y no en `resources`) porque `commonTest/resources` no se lee igual en
 * todas las plataformas: en el código es portable, tipado y se puede grepear.
 *
 * La misma información se exporta a `fixtures/catedra-falsa/` con la tarea
 * `:testkit:exportFixtures`, para el harness de evaluación y para el QA manual.
 */
object Corpus {

    /** Los tres documentos de la cátedra. */
    val documentos: List<DocumentoCorpus> = listOf(
        DocumentoCorpus(
            id = "material-1",
            titulo = "Búsqueda heurística",
            archivo = "material-1.md",
            texto = """
                # Búsqueda heurística

                La búsqueda exhaustiva explora **todas** las combinaciones posibles
                y garantiza el óptimo, si el espacio de búsqueda es finito.

                La búsqueda heurística aplica una **regla de descarte** que elimina
               ramaS antes de explorarlas. No garantiza el óptimo: garantiza
                encontrar *una* solución buena en mucho menos tiempo.

                ## Función de evaluación

                Una función de evaluación estima el costo de una rama sin
                explorarla. La heurística usa el valor de la evaluación para
                decidir qué ramas descartar.
            """.trimIndent(),
        ),
        // … material-2 y material-3
    )

    /** Las diez preguntas del corpus. */
    val preguntas: List<PreguntaCorpus> = listOf(
        PreguntaCorpus(
            pregunta = "¿Qué diferencia hay entre búsqueda exhaustiva y heurística?",
            documentoEsperado = "material-1.md",
            fraseClave = "no garantiza",
            nota = "comparar las dos",
        ),
        // … 9 más
    )
}

/** Un documento del corpus. */
data class DocumentoCorpus(val id: String, val titulo: String, val archivo: String, val texto: String)

/** Una pregunta del corpus, con lo que se espera de cada etapa. */
data class PreguntaCorpus(
    val pregunta: String,
    val documentoEsperado: String,
    val fraseClave: String,
    val nota: String,
)
```

### 9.3 Por qué en código y no en `commonTest/resources`

| Opción | A favor | En contra | Decisión |
|---|---|---|---|
| `commonTest/resources` | Los archivos se editan como texto | En KMP el acceso a recursos **no es igual en cada plataforma**: en `commonTest` no hay API común de lectura; hay que escribir un `expect/actual` de lectura ⇒ un `expect` más por leer un archivo de prueba | **No** |
| Archivos sueltos en `fixtures/` | Editables, la herramienta de RAG los puede leer | El test tiene que leerlos del disco ⇒ rompe el aislamiento y no es portable | **No** (como fuente) |
| **Código Kotlin** | Portable, tipado, grepeable, sin `expect/actual` | Editarlo es menos cómodo que editar un `.md` | **Sí**, con exportador |

### 9.4 El exportador (una sola fuente de verdad)

```kotlin
// testkit/build.gradle.kts
val exportFixtures by tasks.registering {
    val outDir = rootProject.layout.projectDirectory.dir("fixtures/catedra-falsa")
    outputs.dir(outDir)
    doLast {
        // Escribe el corpus desde Corpus.kt: los .md del repo y el código
        // nunca se desincronizan.
        …
    }
}
```

> El exportador escribe `fixtures/catedra-falsa/material-1.md`, … y
> `fixtures/catedra-falsa/preguntas.json`. Es una tarea de Gradle que corre en
> el nightly y cuando se toca el corpus. Los `.md` del repo **no se editan a
> mano**: si se editan, el siguiente export los pisa.

### 9.5 Qué se testea con el corpus

| Test | Dónde | Qué verifica |
|---|---|---|
| `cada pregunta del corpus recupera su documento esperado` | `:backend/commonTest` | La recuperación es determinista (BM25) ⇒ **es un test de puerta**, en `commonTest` |
| `cada documento del corpus tiene material extraído` | `:backend/commonTest` | El texto extraído no está vacío |
| `el corpus tiene diez preguntas y tres documentos` | `:testkit` (o `:backend/commonTest`) | Fija el tamaño del corpus: si cambia, hay que revisar los tests |
| `la respuesta del nightly trae citas verificables` | nightly | El flujo completo con modelo real |

---

## 10. Los cambios de build (prerrequisitos)

```kotlin
// ui/build.gradle.kts
kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":testkit"))          // los dobles
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)  // Skia: sin esto no compone
                implementation("org.jetbrains.compose.ui:ui-test:1.7.3")  // runComposeUiTest
                implementation(libs.junit.jupiter)           // por si conviven con JUnit 5
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")          // kotlin-test-junit lo necesita
            }
        }
    }
}

// Sólo la tarea de escritorio usa JUnit Platform (ver H-UI-1).
tasks.withType<Test>().configureEach {
    if (name.contains("desktop") || name.contains("Desktop")) {
        useJUnitPlatform()
    }
}
```

**Y la tarea que pasa la ruta de las fuentes al test de auditoría:**

```kotlin
// ui/build.gradle.kts
tasks.withType<Test>().configureEach {
    if (name.contains("desktop")) {
        systemProperty("kitoru.uiSources", file("src/commonMain/kotlin").absolutePath)
    }
}
```

---

## 11. Hallazgos que la estrategia hace visibles

| # | Hallazgo | Dónde | Impacto | Arreglo |
|---|---|---|---|---|
| **H-UI-1** | `:backend` aplica `useJUnitPlatform()` a **todas** las tareas `Test`, incluidas las de Android (`testDebugUnitTest`). Con `kotlin.test` eso hace que el target Android resuelva `kotlin-test-junit5` sin tener `junit-jupiter` en el `androidUnitTest` ⇒ los tests unitarios de Android quedan sin framework. | `backend/build.gradle.kts:65-71` | Rompe el job de Android (y por lo tanto `commonTest`) | Acotar a la tarea de escritorio (ver §10) **o** declarar `junit-jupiter` también en `androidUnitTest` |
| **H-UI-2** | `:core` y `:ui` **no** declaran `useJUnitPlatform()`. Con `kotlin("test")` en `commonTest`, el runner por defecto es JUnit 4: los tests pasan, pero con el framework equivocado (y `kotlin-test-junit` en vez de `junit5`). | `core/build.gradle.kts`, `ui/build.gradle.kts` | `commonTest` no es portable a `androidUnitTest` sin `junit:junit` | Agregar `useJUnitPlatform()` acotado a escritorio + `junit:junit:4.13.2` en `androidUnitTest` |
| **H-UI-3** | `KitoruViewModel` crea su `CoroutineScope` con `Dispatchers.Main` adentro: no se puede testear sin EDT. | `ui/src/commonMain/.../KitoruViewModel.kt:74` | Los tests de ViewModel serían *flaky* por construcción | Inyectar el scope (D5) |
| **H-UI-4** | `kitoru.platform.nowMillis()` es global: ningún test puede verificar "hace 5 minutos" sin depender de la hora real. | `core/src/commonMain/.../Dispatchers.kt` | Tests dependentos del reloj | `TimeSource` inyectable (§2.c) |
| **H-UI-5** | `LANG_TO_EXT` y `tokenize` son `private` en `CodeBlock.kt`: la lógica de resaltado no es testeable. | `ui/src/commonMain/.../components/CodeBlock.kt` | La parte testeable de la UI se pierde | Pasarlas a `internal` (visible desde `commonTest`) |
| **H-UI-6** | `CodeBlock` usa `Thread.sleep` en dos `LaunchedEffect` (los avisos "Copiado" / "Guardado"). | `ui/src/commonMain/.../components/CodeBlock.kt:178,181` | Bloquea un hilo del pool; y en un test de composición hace esperar de verdad | Reemplazar por `delay()` (corrutina, no bloquea) — y en un test, el reloj de Compose lo controla |
| **H-UI-7** | `:backend` no tiene **ningún** archivo en `commonTest`: todo su testing es `desktopTest` con JUnit 5, y por lo tanto **nada** corre en Android. | `backend/src/` | El verificador de citas (la métrica del docente) no está verificado en Android | D7: `CitationVerifierTest` a `commonTest`, y el resto en el mismo movimiento |

---

## 12. Checklist de implementación

### Prioridad 0 — sin esto no hay ningún test

- [ ] Crear el módulo `:testkit` (KMP, `commonMain`) con `InMemoryFileSystem`,
      `FakeRepositories`, `ScriptedGateway`, `Corpus`, `Fixtures`.
- [ ] Mover `InMemoryFileSystem` y `Fixtures` desde `core/src/commonTest` (según
      el doc de analítica) a `:testkit`, y **actualizar** ese documento.
- [ ] Agregar `move` a `FileSystem` (si se aplica la escritura atómica del
      doc de analítica) **y** al doble en memoria.
- [ ] Arreglar **H-UI-1** y **H-UI-2**: `useJUnitPlatform()` acotado al
      escritorio + `junit:junit:4.13.2` en `androidUnitTest`.
- [ ] Agregar el paso de CI que falla si el total de tests es 0.

### Prioridad 1 — la capa (a): lógica pura

- [ ] `MarkdownParserTest` (16) — portado de `MarkdownTest` de FunshiEngineAI.
- [ ] `InlineAnnotatedTest` (6).
- [ ] `MathSegmentTest` (8).
- [ ] `CodeBlockNameTest` (5) — requiere `internal fun codeBlockFileName`.
- [ ] `SizeFormatTest` (6) y `PercentFormatTest` (5).
- [ ] `OverrideStateTest` (8), `ComposerStateTest` (8), `CitationBadgeTest` (6).
- [ ] `ContrastTest` (3) — del doc de accesibilidad §5.4.

### Prioridad 2 — la capa (c): ViewModel

- [ ] Inyectar `scope` y `clock` en `KitoruViewModel` (H-UI-3, H-UI-4).
- [ ] `KitoruViewModelTest` (12).
- [ ] `StudentChatViewModelTest` (14) — con `ScriptedGateway`.
- [ ] `StudentConfigViewModelTest` (10).
- [ ] `TeacherAnalyticsViewModelTest` (8).
- [ ] `TeacherChatListViewModelTest` (6).

### Prioridad 3 — la capa (b): composición

- [ ] Agregar `compose.desktop.currentOs` y `ui-test` a `desktopTest`.
- [ ] `MarkdownComposeTest` (3), `ComposerBarComposeTest` (3),
      `CodeBlockComposeTest` (2).
- [ ] `MessageBubbleSemanticsTest` (3), `ErrorBannerSemanticsTest` (2).
- [ ] `LargeFontTest` (2).
- [ ] `SourceAuditTest` (2) + la `systemProperty` en el build.

### Prioridad 4 — `:backend`

- [ ] Mover `CitationVerifierTest` a `commonTest` con `kotlin.test` (D7).
- [ ] Mover `rag/TestSupport.kt` a `commonTest` con `runTest` de coroutines-test.
- [ ] Migrar los otros 7 archivos de `:backend` a `commonTest`.
- [ ] `CorpusRetrievalTest`: cada pregunta del corpus recupera su documento.

### Prioridad 5 — pipeline y corpus

- [ ] Job de CI `tests-rápidos` + `android-unit` + `lint`.
- [ ] Tarea `:testkit:exportFixtures` → `fixtures/catedra-falsa/`.
- [ ] Job nightly `modelo-real` (10 preguntas con modelo real).
- [ ] Job nightly `capturas` (sin puerta).

---

## 13. Resumen en cinco líneas

1. En Compose Multiplatform 1.7.3 **no hay `ComposeTestRule` en el target
   común**: `runComposeUiTest` sí existe (experimental, con actual de Skia que
   renderiza por software y funciona headless), pero sólo sirve en
   `desktopTest`; `createComposeRule` de escritorio es JUnit 4 y el de Android
   necesita emulador y no acepta nombres con espacios.
2. Por eso la estrategia es de **tres capas**: **71 tests de lógica de UI pura**
   (parsers, formateadores, derivadores de estado) y **50 tests de ViewModel**
   en `:ui/commonTest` con `kotlin.test` — portables, rápidos y legibles — más
   **17 tests de composición** en `:ui/desktopTest`, acotados a un presupuesto
   para que la inestabilidad de la API experimental no crezca.
3. Los ViewModel son la capa más valiosa porque **no necesitan recomposición
   para leerse**; para eso hay que inyectarles el `CoroutineScope` y el reloj
   (`TimeSource`), porque `Dispatchers.Main` y `nowMillis()` son la fuente de
   tests *flaky*.
4. Los dobles compartidos (`InMemoryFileSystem`, repos fake, `ScriptedGateway`,
   corpus) van en un módulo nuevo **`:testkit`**; el `CitationVerifierTest` (24
   tests) **se mueve a `commonTest`** con `kotlin.test` — cuesta 20 minutos y
   hace que la métrica de calidad del docente se verifique también en Android.
5. El pipeline es: **PR** = tests de escritorio + tests unitarios de Android
   (sin emulador) + compilación, con la puerta de que el total de tests sea
   > 0; **nightly** = modelo real sobre un corpus de 3 documentos y 10
   preguntas (exportado a `fixtures/catedra-falsa/`), con tendencia de calidad
   y capturas **fuera** de la puerta, porque la calidad del modelo no es
   determinista y un golden que falla entrena a ignorar los fallos.
