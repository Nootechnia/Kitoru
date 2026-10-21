# 09 · UI adaptativa (escritorio ⇄ Android)

> Cómo la **misma** interfaz de Kitoru se comporta bien en una ventana de
> escritorio y en un teléfono: clases de tamaño, navegación, densidad de
> información, interacciones que sólo existen con mouse, el menú contextual, el
> scrollbar, el teclado (físico y en pantalla) y los objetivos táctiles.
>
> Transversal a los dos roles: el **alumno** y el **docente** comparten el
> andamiaje (`AdaptiveScaffold`, `WindowSizeClass`, `ContextMenu`,
> `ScrollbarSpec`) y sólo cambian los destinos de navegación y el contenido.
>
> Depende de: `docs/design/CONTEXTO.md`, `docs/design/teacher/01-navegacion.md`
> (§6, navegación responsive), `docs/design/student/02-chat.md` (chat y
> `imePadding`), `docs/design/student/07-historial.md` (menú de hilos),
> `docs/design/student/10-accesibilidad-i18n.md` (teclado y objetivos táctiles).
> Código de referencia: `NavigationRail.kt`, `ChatPane.kt`, `ChatSidebar.kt` y
> `ChatScrollbarAdapter.kt` de FunshiEngineAI.

---

## 0. Decisiones en una línea

| # | Decisión | Alternativa descartada | Por qué |
|---|---|---|---|
| D1 | La clase de tamaño se mide sobre el **ancho disponible del contenedor**, con `BoxWithConstraints` en la raíz. | `LocalWindowInfo.containerSize`, `LocalConfiguration.screenWidthDp`, `material3-window-size-class` | Es lo único que es **igual** en las dos plataformas y que responde al redimensionado de la ventana y al split-screen de Android. `LocalWindowInfo.containerSize` **no existe en Android** (verificado, §3.1). |
| D2 | La clase se **instala una vez** en la raíz (`ProvideWindowSize`) y se lee con `rememberWindowSizeClass()`. | `BoxWithConstraints` dentro de cada componente | `BoxWithConstraints` devuelve *constraints*, no un valor: no se puede llamar "dentro" de una función que devuelve la clase. El patrón correcto es un `CompositionLocal` (§3.2). |
| D3 | La navegación se decide por (clase de ancho × **cantidad de destinos**): rail si `EXPANDED`/`MEDIUM`; bottom bar si `COMPACT` y 3–5 destinos; drawer si `COMPACT` y más de 5. | Regla fija por rol | El alumno tiene 4 destinos (le sirve la bottom bar); el docente tiene 9 (no entran: drawer). Una sola regla, dos resultados (§4.1). |
| D4 | Todo lo que en desktop se hace con **botón derecho** tiene su equivalente táctil: **long-press**. Los dos disparadores conviven en el mismo componente. | Dos componentes distintos por plataforma | Un solo componente, un solo modelo mental, un solo código de acciones (§6). |
| D5 | El menú contextual es **un `DropdownMenu`** en las dos plataformas, anclado a un overlay que llena la ventana; se abre con long-press (táctil) o botón derecho (mouse). | `ModalBottomSheet` para todo | El `DropdownMenu` existe igual en desktop y Android (`expect/actual` de Material 3) y se posiciona con `offset`. La hoja inferior queda reservada para menús de **más de 6 ítems** (§6.5). |
| D6 | El scrollbar **sólo existe en escritorio**, y su código (el `ScrollbarAdapter` estable) vive en `desktopMain`. | Portar `ChatScrollbarAdapter` a `commonMain` | **Verificado**: `androidx.compose.foundation.v2.ScrollbarAdapter` existe en el artefacto *desktop* de CMP 1.7.3 y **no** en el de Android (§7.1). Un port literal a `commonMain` no compila. |
| D7 | El scrollbar se aísla detrás de una interfaz propia (`ScrollbarSpec`) + `expect/actual`, para que la lógica de medición (que sí es portable) no dependa de androidx. | Duplicar el adapter por plataforma | La lógica de "thumb de tamaño estable" es pura (`LazyListState` + mapa) y se reutiliza; sólo el dibujado del scrollbar es de escritorio (§7.2). |
| D8 | **No existe** ningún atajo global para `Ctrl+C/V/X/A/Z`. "Copiar" vive **dentro de la burbuja con el foco**. | Atajo global "copiar el mensaje" | Un atajo global de copiar se come el `Ctrl+C` nativo del campo de texto (§8.3). |
| D9 | El redactor lleva `safeDrawing(horizontal)` + `navigationBars.exclude(ime)` + `imePadding()`, en ese orden. | `imePadding()` solo, o `navigationBarsPadding().imePadding()` | Con el teclado abierto, el inset del IME ya incluye la barra de navegación: sin `exclude`, la barra se cuenta dos veces (§9.2). |
| D10 | Los diálogos en `COMPACT` usan `DialogProperties(usePlatformDefaultWidth = false)` + `fillMaxWidth(0.92f)`. | El ancho por defecto de la plataforma | Con el ancho por defecto, el diálogo de permisos de herramientas se corta en un teléfono de 360 dp (§11). |
| D11 | Los objetivos táctiles son **48 dp en Android** y **40 dp en escritorio**, con `Modifier.minimumInteractiveComponentSize()` y `LocalMinimumInteractiveComponentSize` sobreescrito por plataforma. | 48 dp siempre | 48 dp en escritorio desperdicia espacio y se ve raro con mouse; el mínimo de 40 dp es el que Material usa en desktop (§10.1). |
| D12 | La densidad de información **baja** en `COMPACT`: se ocultan columnas, no se achican. | Achicar tipografía y paddings | En un teléfono, 14 sp de texto de más no se lee: se ve menos información, más grande (§2.3). |

---

## 1. Alcance

### 1.1 Qué resuelve

Kitoru es **una** app, no dos. El mismo árbol de composables se dibuja en una
ventana de 1440 dp y en un teléfono de 360 dp. Este documento define:

1. Las **clases de tamaño** y qué cambia en cada una.
2. Cómo se **mide** el ancho de forma portable.
3. Qué **interacciones sólo de escritorio** existen y cuál es su equivalente táctil.
4. El **menú contextual** multiplataforma (el `PointerButton.Secondary` que en
   Android nunca ocurre).
5. El **scrollbar**: dónde existe y dónde no.
6. El **mapa de atajos** de teclado y el conflicto con `Ctrl+C`.
7. El **teclado en pantalla** (`imePadding`).
8. **Objetivos táctiles** y densidad.
9. El **diálogo de permisos** de herramientas en pantallas chicas.

### 1.2 Qué NO resuelve

- El modelo de datos y la analítica (`domain/*`).
- El contenido de cada pantalla (docs de rol).
- El `NavHost` propio y la pila manual (`teacher/01-navegacion.md` §4.1,
  `student/01-entrada.md` §10.1): este documento **no** cambia la pila, sólo
  cómo se presenta.
- La accesibilidad en general (`student/10-accesibilidad-i18n.md`): aquí sólo se
  tocan los puntos que cambian por plataforma (objetivos táctiles, foco, atajos).

### 1.3 Hechos verificados sobre Compose Multiplatform 1.7.3

Estos tres datos cambian decisiones de código; se verificaron contra los
artefactos reales (`gradle/libs.versions.toml`: `compose = 1.7.3`,
`compileSdk = 35`, `minSdk = 24`).

| # | Hecho | Cómo se comprobó | Consecuencia |
|---|---|---|---|
| H1 | `org.jetbrains.compose.foundation:foundation:1.7.3` **no aporta clases** en Android: su variante `android` no declara `files` y depende de `androidx.compose.foundation:foundation:1.7.6`. | Lectura del `foundation-1.7.3.module` (variante `releaseRuntimeElements-published`). | Lo que exista en `foundation.v2` **no** existe en Android salvo que esté en androidx 1.7.6. |
| H2 | `androidx.compose.foundation:foundation-android:1.7.6` **no contiene ninguna clase de scrollbar** y **no tiene** el paquete `androidx.compose.foundation.v2` (sí lo tiene `foundation-desktop:1.7.3`: `ScrollbarAdapter.class`, `LazyListScrollbarAdapter.class`, `Scrollbar_desktopKt.class`). | Listado de `classes.jar` del AAR `foundation-android-1.7.6.aar` y de `foundation-desktop-1.7.3.jar`; además los *sources* de 1.6.4, 1.7.6 y 1.8.2 no tienen ningún `Scrollbar.kt`. | `androidx.compose.foundation.v2.ScrollbarAdapter` es **API de escritorio (skiko)**. Un archivo en `commonMain` que la importe **no compila** para Android. |
| H3 | `androidx.compose.ui.platform.WindowInfo` de androidx 1.7.6 sólo declara `isWindowFocused` y `keyboardModifiers`. El `containerSize` que usa CMP en desktop está en `WindowInfo.skiko.kt`, **fuera** de `commonMain`. | `commonMain/androidx/compose/ui/platform/WindowInfo.kt` (sources de `ui-android-1.7.6`); `WindowInfo.skiko.kt` en el jar de desktop. | `LocalWindowInfo.current.containerSize` **no compila** en `commonMain`. El tamaño de la ventana de escritorio se lee en `desktopMain` (§3.3). |
| H4 | `DialogProperties` es una `expect class` con **tres** propiedades comunes: `dismissOnBackPress`, `dismissOnClickOutside`, `usePlatformDefaultWidth`. (Android suma `decorFitsSystemWindows`; skiko suma `usePlatformInsets`, `useSoftwareKeyboardInset`, `scrimColor`.) | `commonMain/androidx/compose/ui/window/Dialog.kt` (sources de `ui-android-1.7.6`). | En `commonMain` sólo se pueden usar esas tres; `usePlatformDefaultWidth` sí ✓. |
| H5 | `WindowInsets.ime`, `WindowInsets.navigationBars`, `WindowInsets.safeDrawing`, `WindowInsets.only(WindowInsetsSides)` y `WindowInsets.union/exclude` están en `commonMain` de foundation-layout; en escritorio `ime` devuelve `WindowInsets(0)` (`WindowInsets.notMobile.kt` → `ZeroInsets`). | `WindowInsets_notMobileKt` del jar de desktop; `WindowInsetsKt` (common). | La misma cadena de insets sirve para las dos plataformas: en escritorio todo vale 0 y no hace falta `expect/actual` de insets. |
| H6 | `Modifier.combinedClickable` (estable), `detectTapGestures`, `detectDragGesturesAfterLongPress` y `PointerButton` están en `commonMain`. | `androidx/compose/foundation/ClickableKt.class` (foundation-desktop = common + skiko). | El long-press es el disparador táctil universal, sin código de plataforma. |
| H7 | `DropdownMenu`/`DropdownMenuItem` existen igual en desktop (`SkikoMenu.skiko.kt`) y en Android (`AndroidMenu_androidKt`, material3 1.3.1) con la **misma firma** (`offset`, `properties`, `shape`, `containerColor`, …). El desktop posiciona con `DropdownMenuPositionProvider` **respecto del ancla en la ventana**, sumando `offset` y ajustando a los bordes. | Sources de `material3-desktop-1.7.3`; `classes.jar` de `material3-android-1.3.1`. | El overlay "que llena la ventana" + `offset` funciona igual en las dos plataformas (§6.3). |
| H8 | `Modifier.minimumInteractiveComponentSize()` y `LocalMinimumInteractiveComponentSize` están en **`androidx.compose.material3`** (no en foundation) y existen en common y en Android 1.3.1. | `InteractiveComponentSizeKt.class` en material3-desktop y en material3-android-1.3.1. | Se puede forzar 48 dp / 40 dp por plataforma sin `expect/actual` (§10.1). |

---

## 2. Breakpoints

### 2.1 Las tres clases

Los cortes son los de las *window size classes* de Material 3, sobre el **ancho
disponible**, en `dp`:

| Clase | Ancho | Qué es |
|---|---|---|
| **COMPACT** | `< 600 dp` | Teléfono en vertical (360–480 dp), teléfono en horizontal (640–900 dp de ancho, pero **poco alto**), ventana de escritorio muy angosta. |
| **MEDIUM** | `600–839 dp` | Tablet en vertical, teléfono en horizontal grande, ventana de escritorio mediana. |
| **EXPANDED** | `≥ 840 dp` | Tablet en horizontal, monitor de escritorio, ventana grande. |

El **alto** se mide aparte, porque un teléfono en horizontal (ancho 800 dp,
alto 360 dp) es `MEDIUM` por ancho pero no puede mostrar una barra inferior de
80 dp: ahí manda el alto.

```kotlin
enum class WindowHeightClass { SHORT, MEDIUM, TALL }   // < 480 dp · < 720 dp · ≥ 720 dp
```

Regla: **si `heightClass == SHORT`, no hay barra inferior** (se usa el botón de
menú de la `TopAppBar`); el rail de íconos sí puede ir.

### 2.2 Qué cambia en cada clase

| Elemento | COMPACT (< 600) | MEDIUM (600–839) | EXPANDED (≥ 840) |
|---|---|---|---|
| **Navegación primaria** | `NavigationBar` (abajo, 80 dp) si hay 3–5 destinos; si no, `ModalNavigationDrawer` | `NavigationRail` de **íconos** (72–80 dp) | `NavigationRail` **extendida** (200–240 dp, con etiquetas) |
| **Barra superior** | `TopAppBar` con `←` (atrás) + `⋮` | `TopAppBar` con título de sección | `TopAppBar` con selector de cátedra completo |
| **Lista lateral** (hilos, alumnos, materiales) | Pantalla completa apilada, o `ModalBottomSheet` | Panel de 280–320 dp **que reemplaza** a la lista cuando hay un detalle abierto | Columna fija de 280–320 dp, siempre visible |
| **Panel de detalle** | Pantalla completa con `←`; nunca convive con la lista | Panel de 320 dp que **reemplaza** a la lista | Panel fijo de 320–360 dp **al costado del contenido** |
| **Lista + detalle a la vez** | ❌ | ❌ | ✅ |
| **Diálogo de detalle** | `Dialog` a 92 % del ancho | `Dialog` | `Dialog` o panel lateral si el contenido es largo |
| **Hoja de acciones** (fuentes, filtros, acciones del mensaje) | `ModalBottomSheet` | `ModalBottomSheet` | `ModalBottomSheet` o `DropdownMenu` |
| **Menú contextual** | `DropdownMenu` (≤ 6 ítems) / `ModalBottomSheet` (> 6) | `DropdownMenu` | `DropdownMenu` |
| **Scrollbar** | No (lo dibuja el sistema) | No | Sí (12 dp,-themed) |
| **Objetivo táctil** | 48 dp | 48 dp | 40 dp (se usa mouse) |
| **Densidad** | 1 columna, 16 dp de padding, `bodyMedium` | 1–2 columnas, 20 dp | 2–4 columnas, 24 dp, `bodyLarge` en tablas |
| **Barra de acciones del mensaje** | Sólo el `⋮` (menú) | `⋮` + 1–2 iconos | Fila completa de iconos |

### 2.3 Densidad de la información

Regla: **en `COMPACT` se oculta información, no se achica**. El alumnus en un
teléfono necesita menos datos por fila, no datos más chicos.

Ejemplo — fila de la lista de hilos (`student/07-historial.md`):

| Dato | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Título del hilo | ✅ (1 línea) | ✅ | ✅ |
| Fecha relativa | ✅ (a la derecha, 12 sp) | ✅ | ✅ (fecha completa) |
| Modelo (chip) | ❌ | ✅ | ✅ |
| Preview de la última respuesta | ❌ | ✅ | ✅ (2 líneas) |
| Cantidad de mensajes / tokens | ❌ | Sólo mensajes | ✅ mensajes + tokens + latencia |

Ejemplo — tarjeta de cátedra en la home del alumno (`student/06-home-anuncios.md`):

| Dato | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Código + nombre | ✅ | ✅ | ✅ |
| Cursada, nombre del docente | ❌ / docente sí | ✅ | ✅ |
| Estado (publicada, clave vencida) | chip | chip | chip + detalle |
| Contadores (materiales, tareas, anuncios sin leer) | sólo lo pendiente | todo | todo + gráfico |
| Aviso de "IA local activa" | banner de una línea | banner | banner con CTA |

Ejemplo — tabla de analítica del docente (`teacher/01-navegacion.md`):

| COMPACT | MEDIUM | EXPANDED |
|---|---|---|
| Tarjetas apiladas (una métrica por fila), scroll vertical | Tarjetas en 2 columnas | Tabla con columnas + tarjeta de detalle al lado |

### 2.4 Layouts de referencia

**Escritorio, `EXPANDED` (alumno, 1440 dp):**

```
┌────────────┬───────────────┬──────────────────────────────────┬───────────────┐
│  RAIL      │  HILOS        │  CHAT                            │  DETALLE      │
│  200 dp    │  280 dp       │  weight(1f)                      │  320 dp       │
│            │               │                                  │               │
│ 💬 Chat    │ ▸ Parcial 1   │   ┌───────────────────────────┐  │  Ficha del    │
│ 📚 Material│ ▸ Duda SVM    │   │ respuesta del modelo      │  │  material     │
│ 🔔 Avisos  │ ▸ Resumen 1   │   │ (markdown, fuentes)       │  │               │
│ ⚙ Ajustes  │               │   └───────────────────────────┘  │  (o se oculta │
│            │ [+ Nuevo]     │   ┌───────────────────────────┐  │   si no hay)  │
│            │               │   │ pregunta del alumno       │  │               │
│            │               │   └───────────────────────────┘  │               │
├────────────┴───────────────┴──────────────────────────────────┴───────────────┤
│ [Usar material] [Libre]  ┌──────────────────────────────┐  [ Enviar ↑ ]     │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Android, `COMPACT` (360×800 dp):**

```
┌────────────────────────────────┐
│ ← IA-2026-1 · Chat         ⋮  │  ← TopAppBar (48 dp) + botón atrás
├────────────────────────────────┤
│ ⚠ La clave venció. [Cerrar]    │  ← banner plegable a una línea
├────────────────────────────────┤
│                                │
│   mensajes (LazyColumn)        │  ← sin scrollbar (el sistema dibuja
│                                │     el indicador al scrollear)
│                                │
├────────────────────────────────┤
│ [● Usar material] [Libre]      │  ← fila del modo (chips chicos)
│ ┌──────────────────────────┐ ↑ │  ← imePadding(): sube con el teclado
│ │ Escribí tu pregunta…     │   │
│ └──────────────────────────┘   │
├────────────────────────────────┤
│ 💬 Chat  📚 Material  🔔  ⚙   │  ← NavigationBar (80 dp)
└────────────────────────────────┘
```

**Android en horizontal (`MEDIUM` por ancho, `SHORT` por alto, 800×360 dp):**

```
┌────────────────────────────────────────────────────────────┐
│ ← IA-2026-1 · Chat                                     ⋮  │
├──────────┬─────────────────────────────────────────────────┤
│ 💬 📚 🔔  │  mensajes (LazyColumn)                          │
│ ⚙        │                                                 │
│ (rail de │  ┌───────────────────────────────────────────┐  │
│  íconos, │  │ Escribí tu pregunta…            [ ↑ ]    │  │
│  72 dp)  │  └───────────────────────────────────────────┘  │
└──────────┴─────────────────────────────────────────────────┘
   ↑ rail en vez de bottom bar: con 360 dp de alto no hay lugar abajo
```

---

## 3. Medir el ancho: `rememberWindowSizeClass()`

### 3.1 Por qué no `LocalWindowInfo`

La respuesta obvia para desktop sería `LocalWindowInfo.current.containerSize`.
**No sirve en `commonMain`**, por H3: en Android, `androidx.compose.ui`'s
`WindowInfo` (1.7.6) sólo tiene `isWindowFocused` y `keyboardModifiers`; el
`containerSize` que usa CMP en escritorio está en `WindowInfo.skiko.kt`, fuera
de `commonMain`. Un `commonMain` que lo use **no compila** para Android.

Lo mismo con `LocalConfiguration.current.screenWidthDp`: es **de la pantalla**,
no de la ventana. En Android multi-ventana (split-screen, modo libre) y en
escritorio con dos ventanas da un número que no es el del área disponible.
Y `LocalConfiguration` no existe en CMP para desktop.

`androidx.compose.material3:material3-window-size-class` no es multiplatform
en CMP 1.7.3 (es un artefacto de Android).

**Decisión: el ancho se mide con `BoxWithConstraints`**, que es la única fuente
portable que además **reacciona** a:
- el redimensionado de la ventana de escritorio,
- el cambio de orientación del teléfono,
- el split-screen / multi-ventana de Android,
- y el ancho real del contenido cuando hay un panel lateral.

### 3.2 La implementación

`BoxWithConstraints` **no devuelve un valor**: expone `maxWidth`/`maxHeight`
dentro de su lambda. Por eso el patrón correcto es *instalar* la clase una vez
en la raíz y *leerla* desde cualquier parte.

> **Corrección**: el fragmento de `docs/design/teacher/01-navegacion.md` §6.1
> (`BoxWithConstraints { val w = maxWidth; return remember(w) { … } }`) **no
> compila**: `BoxWithConstraints` no es `inline`, así que el `return` no local
> desde su lambda es ilegal. Se reemplaza por el patrón de abajo.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/WindowSizeClass.kt
package kitoru.ui.core

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clase de tamaño del **contenedor disponible**, no de la pantalla.
 *
 * Cortes de Material 3: COMPACT < 600 dp · MEDIUM 600–839 dp · EXPANDED ≥ 840 dp.
 */
enum class WindowSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    /** ¿Entra un panel lateral de [panelWidthDp] dp al costado del contenido? */
    fun fitsSidePanel(panelWidthDp: Int): Boolean = this == EXPANDED

    companion object {
        fun fromWidth(width: Dp): WindowSizeClass = when {
            width < 600.dp -> COMPACT
            width < 840.dp -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Clase de altura (importa en landscape: ancho MEDIUM, alto SHORT). */
enum class WindowHeightClass { SHORT, MEDIUM, TALL }

/** Medidas del contenedor disponible. */
@Immutable
data class WindowSize(
    val sizeClass: WindowSizeClass,
    val heightClass: WindowHeightClass,
    val widthDp: Int,
    val heightDp: Int,
) {
    val isShort: Boolean get() = heightClass == WindowHeightClass.SHORT

    companion object {
        fun of(width: Dp, height: Dp): WindowSize {
            val w = width.value.toInt()
            val h = height.value.toInt()
            return WindowSize(
                sizeClass = WindowSizeClass.fromWidth(width),
                heightClass = when {
                    h < 480 -> WindowHeightClass.SHORT
                    h < 720 -> WindowHeightClass.MEDIUM
                    else -> WindowHeightClass.TALL
                },
                widthDp = w,
                heightDp = h,
            )
        }
    }
}

/** La clase de tamaño, provista por [ProvideWindowSize]. */
val LocalWindowSize = compositionLocalOf { WindowSize.of(360.dp, 640.dp) }

/**
 * Instala las medidas del contenedor. **Va una sola vez**, en la raíz de la app.
 *
 * Es un `Box` porque `BoxWithConstraints` necesita estar en el árbol para
 * conocer las restricciones reales; `fillMaxSize` lo hace ocupar la ventana.
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = remember(maxWidth, maxHeight) { WindowSize.of(maxWidth, maxHeight) }
        CompositionLocalProvider(LocalWindowSize provides size) {
            content()
        }
    }
}

/**
 * La clase de tamaño actual.
 *
 * Se usa en cualquier composable: `[ProvideWindowSize]` ya la midió.
 * Si no hay proveedor (previews, tests), el valor por defecto es COMPACT,
 * que es el peor caso (el diseño compacto es el que tiene que caber en todas
 * partes).
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass = LocalWindowSize.current.sizeClass

/** Las medidas completas (clase de ancho + de alto). */
@Composable
fun rememberWindowSize(): WindowSize = LocalWindowSize.current
```

Y en la raíz (`ui/src/commonMain/kotlin/kitoru/ui/KitoruApp.kt`):

```kotlin
@Composable
fun KitoruApp(env: KitoruEnv) {
    val scope = rememberCoroutineScope()
    val app = remember { AppViewModel(scope, env.courses, env.enrollments, env.profiles, env.userId) }

    KitoruTheme(dark = app.isDark, accent = app.accentColor) {
        ProvideWindowSize {                 // ← una sola vez, antes del shell
            AppShell(app = app) {           // el shell lee LocalWindowSize
            }
        }
    }
}
```

**Por qué `compositionLocalOf` y no `staticCompositionLocalOf`:** la clase
cambia al redimensionar la ventana y los lectores tienen que recomponerse;
`staticCompositionLocalOf` no notifica a los lectores.

### 3.3 El tamaño de la ventana en escritorio (opcional, `desktopMain`)

Si en algún momento hace falta el tamaño de la **ventana** y no del
contenedor (por ejemplo, para decidir si el rail es de íconos o extendida
según el ancho *de la ventana*), se lee en `desktopMain`, que es donde la API
existe:

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/core/WindowInfo.desktop.kt
package kitoru.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize

/**
 * Tamaño de la ventana de escritorio, en píxeles.
 *
 * **Sólo escritorio**: `LocalWindowInfo.containerSize` no existe en el
 * artefacto Android de CMP 1.7.3 (H3). En Android se usa
 * `LocalWindowSize.current.widthDp`, que es lo mismo para el propósito.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun rememberDesktopWindowSize(): IntSize = LocalWindowInfo.current.containerSize
```

En `androidMain` no se pone nada: el `actual` no es necesario porque la función
sólo se llama desde código de escritorio. Si más adelante hace falta una
versión Android, se declara `expect` y el `actual` Android devuelve
`IntSize.Zero` (o el tamaño de la vista, vía `LocalView`).

### 3.4 Insets: otra medida, otro propósito

`WindowInsets` y `BoxWithConstraints` no compiten: uno mide **espacio**, el
otro **tamaño**.

| Necesidad | Herramienta | Nota |
|---|---|---|
| ¿Cuánto ancho/alto tengo? | `BoxWithConstraints` (§3.2) | Reacciona a cualquier cambio. |
| ¿Cuánto ocupa la barra de estado / de gestos / el teclado? | `WindowInsets.systemBars`, `.safeDrawing`, `.ime` | Común ✓ (H5). En escritorio valen 0. |
| ¿Dónde está el notch? | `WindowInsets.displayCutout` | Sólo landscape en Android. |

En Android, `WindowInsets` **no** sirve para medir el ancho de la app: los
insets son de las barras del sistema, no del área de contenido. Por eso la
clase de tamaño sale de `BoxWithConstraints` y los insets se usan aparte
(§9).

### 3.5 Pruebas previstas (`:ui` `commonTest`)

| Test | Qué verifica |
|---|---|
| `WindowSizeClassTest` | `fromWidth`: 599.9 dp → COMPACT; 600 → MEDIUM; 839.9 → MEDIUM; 840 → EXPANDED. |
| `WindowSizeTest` | `WindowSize.of(800.dp, 360.dp)` → `MEDIUM` + `SHORT` (el caso landscape del teléfono). |
| `AdaptiveNavigationTest` | (§4.1) alumno (4 destinos) en COMPACT → `BOTTOM_BAR`; docente (9 destinos) en COMPACT → `DRAWER`; cualquier rol en EXPANDED → `RAIL`. |

---

## 4. Navegación adaptativa (rail / bottom bar / drawer)

### 4.1 La regla

La decisión sale de **dos** datos, no de uno: la clase de tamaño y **cuántos
destinos** tiene el rol. Es la forma de que el alumno (4 destinos) y el
docente (9) compartan el mismo componente sin código duplicado.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/AdaptiveNavigation.kt
package kitoru.ui.core

/** Contenedor de navegación primaria. */
enum class AdaptiveNavigation {
    /** `NavigationRail` de íconos (MEDIUM) o extendida (EXPANDED). */
    RAIL,

    /** `NavigationBar` inferior (sólo COMPACT, 3–5 destinos). */
    BOTTOM_BAR,

    /** `ModalNavigationDrawer` (sólo COMPACT, más de 5 destinos). */
    DRAWER,
}

/**
 * Qué contenedor de navegación usar.
 *
 * @param sizeClass clase de ancho del contenedor.
 * @param destinationCount cantidad de destinos primarios del rol.
 * @param heightClass clase de alto (en landscape el teléfono es SHORT y no
 *        hay lugar para una barra inferior).
 */
fun adaptiveNavigation(
    sizeClass: WindowSizeClass,
    destinationCount: Int,
    heightClass: WindowHeightClass = WindowHeightClass.TALL,
): AdaptiveNavigation = when {
    sizeClass == WindowSizeClass.EXPANDED -> AdaptiveNavigation.RAIL
    sizeClass == WindowSizeClass.MEDIUM -> AdaptiveNavigation.RAIL
    heightClass == WindowHeightClass.SHORT -> AdaptiveNavigation.RAIL
    destinationCount in 3..5 -> AdaptiveNavigation.BOTTOM_BAR
    else -> AdaptiveNavigation.DRAWER
}
```

| Rol | Destinos | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|---|
| **Alumno** | Inicio · Materia · Historial · Ajustes (4) | `BOTTOM_BAR` | `RAIL` íconos | `RAIL` extendida |
| **Docente** | 9 secciones | `DRAWER` | `RAIL` íconos | `RAIL` extendida |

Esto **coincide** con la decisión ya escrita en
`docs/design/teacher/01-navegacion.md` §6.4 ("no hay bottom bar" para el
docente: 9 secciones no entran en una `NavigationBar`) y la **extiende** al
alumno, que sí tiene pocos destinos.

### 4.2 `AdaptiveScaffold`

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/AdaptiveScaffold.kt
package kitoru.ui.core

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Contenedor raíz común a los dos roles.
 *
 * Decide rail / bottom bar / drawer a partir de [WindowSize] y de la cantidad
 * de destinos, y **no** decide el contenido: eso es de cada pantalla.
 *
 * @param destinations destinos primarios del rol (id, etiqueta, ícono).
 * @param currentDestinationId destino activo.
 * @param onNavigate callback de navegación.
 * @param content contenido de la sección activa.
 */
@Composable
fun AdaptiveScaffold(
    destinations: List<NavDestination>,
    currentDestinationId: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val size = rememberWindowSize()
    val mode = adaptiveNavigation(size.sizeClass, destinations.size, size.heightClass)

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        // Los insets los maneja cada región explícitamente (§9.4): el Scaffold
        // no los aplica para no contarlos dos veces.
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        when (mode) {
            AdaptiveNavigation.RAIL ->
                Row(Modifier.fillMaxSize()) {
                    NavigationRailPane(
                        destinations = destinations,
                        currentId = currentDestinationId,
                        onNavigate = onNavigate,
                        expanded = size.sizeClass == WindowSizeClass.EXPANDED,
                    )
                    Box(Modifier.weight(1f)) { content(padding) }
                }

            AdaptiveNavigation.BOTTOM_BAR ->
                Box(Modifier.fillMaxSize()) {
                    content(padding)
                    NavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        destinations.forEach { d ->
                            NavigationBarItem(
                                selected = d.id == currentDestinationId,
                                onClick = { onNavigate(d.id) },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label, maxLines = 1) },
                            )
                        }
                    }
                }

            AdaptiveNavigation.DRAWER ->
                ModalNavigationDrawer(
                    drawerContent = {
                        ModalDrawerSheet {
                            destinations.forEach { d ->
                                NavigationDrawerItem(
                                    selected = d.id == currentDestinationId,
                                    onClick = { onNavigate(d.id) },
                                    icon = { Icon(d.icon, contentDescription = null) },
                                    label = { Text(d.label) },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    },
                ) { content(padding) }
        }
    }
}

/** Un destino de navegación primaria. */
data class NavDestination(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
```

### 4.3 El rail colapsable (puerto del patrón de FunshiEngineAI)

`NavigationRail.kt` de FunshiEngineAI es un rail **colapsable** con `expanded`
en `remember`, que pasa de 200 dp a 72 dp. Se porta el patrón, con dos
correcciones:

1. El colapso **manual** (el botón `≡`) sólo tiene sentido con mouse: en
   `COMPACT` el rail no existe, así que el botón desaparece.
2. El rail **extendido** (con etiquetas) es el estado por defecto en
   `EXPANDED`; el colapsado es el modo "ahorro de espacio".

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/NavigationRailPane.kt
package kitoru.ui.core

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Rail de navegación. Colapsable con mouse (`expanded`), siempre expandido en
 * `EXPANDED` salvo que el usuario lo colapse.
 */
@Composable
fun NavigationRailPane(
    destinations: List<NavDestination>,
    currentId: String,
    onNavigate: (String) -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggleExpanded: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(if (expanded) 200.dp else 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        if (expanded) {
            Text(
                "Kitoru",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))

        // El botón de colapso sólo tiene sentido con mouse (P1: hay un botón
        // físico "hamburger" en la TopAppBar en COMPACT).
        if (onToggleExpanded != null) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowLeft else Icons.Default.Menu,
                    contentDescription = if (expanded) "Colapsar la navegación" else "Expandir la navegación",
                )
            }
        }

        destinations.forEach { d ->
            val selected = d.id == currentId
            if (expanded) {
                NavigationRailItem(
                    selected = selected,
                    onClick = { onNavigate(d.id) },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(d.label) },
                    alwaysShowLabel = true,
                )
            } else {
                // Sólo ícono + tooltip: el tooltip es la alternativa al hover
                // cuando no hay etiqueta visible.
                NavigationRailItem(
                    selected = selected,
                    onClick = { onNavigate(d.id) },
                    icon = {
                        Icon(
                            d.icon,
                            contentDescription = d.label,   // el lector lo anuncia
                        )
                    },
                    label = { },
                )
            }
        }
    }
}
```

**Tooltip**: `NavigationRailItem` de Material 3 **no** muestra tooltip en
desktop (los tooltips de M3 son de Android). En el rail colapsado, la etiqueta
se anuncia con `contentDescription` y, además, se agrega un `TooltipBox` si se
quiere el texto flotante:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/HoverTooltip.kt
/**
 * Tooltip multiplataforma.
 *
 * En Android, `TooltipBox` + `TooltipDefaults.rememberPlainTooltipState()` +
 * `Modifier.pointerInput { detectTapGestures(onLongPress = { state.showTooltip = true }) }`
 * (el long-press es el "hover" del dedo).
 * En escritorio, se puede usar `Modifier.onPointerEvent(PointerEventType.Enter)`
 * para mostrarlo al pasar el mouse.
 *
 * Es la alternativa táctil de §5 (hover → toque).
 */
```

### 4.4 La bottom bar del alumno

`NavigationBar` de Material 3 ya cumple 48 dp de alto por ítem y el alto
recomendado (80 dp). Los destinos del alumno:

| Id | Etiqueta | Ícono | Pantalla |
|---|---|---|---|
| `home` | Inicio | `Home` | `StudentHomeScreen` |
| `course` | Materia | `Forum` | Chat/Material/Avisos de la cátedra activa |
| `history` | Historial | `History` | Lista de hilos |
| `settings` | Ajustes | `Settings` | `SettingsPane` |

Las pestañas **dentro** de la cátedra (Chat · Material · Avisos) son un
`ScrollableTabRow` en la `TopAppBar`, no una segunda barra de navegación: en
`COMPACT` no caben dos barras.

### 4.5 El drawer

`ModalNavigationDrawer` es **el mismo componente** en desktop y Android en
CMP 1.7.3 (`NavigationDrawer.skiko.kt` + `androidx.compose.material3`), con
`DrawerState` común. Se usa:

- en `COMPACT` para el docente (9 destinos),
- en cualquier clase como "ver todo" cuando la bottom bar no alcanza
  (el botón `⋮` de la `TopAppBar` lo abre).

Regla de comportamiento: **el drawer se cierra al navegar** y **devuelve el
foco** al botón que lo abrió (`focusManager` + `FocusRequester`).

---

## 5. Interacciones sólo de escritorio y su alternativa táctil

| Interacción de escritorio | Por qué no existe en Android | Alternativa táctil (o de bajo nivel) | Dónde se implementa |
|---|---|---|---|
| **Botón derecho** (`PointerButton.Secondary`) | Android no reporta botón secundario: los eventos de puntero sólo traen `Primary`. | **Long-press** (500 ms) sobre el mismo elemento. | `Modifier.contextMenuAnchor` (§6) |
| **Hover** (tooltip al pasar el mouse) | No hay puntero flotante. | Toque (abre el tooltip) o **long-press**. | `HoverTooltip` (§4.3) |
| **Doble click** | Es un gesto de mouse. | Doble toque (funciona, pero es ambiguo con el scroll). | `combinedClickable(onDoubleClick = …)` |
| **Arrastre con el mouse** (drag & drop de `ChatSidebar.kt`) | Arrastrar con el dedo es ambiguo con el scroll y con el gesto de "atrás" del sistema. | Menú "Mover a…" → hoja con destinos. | `student/07-historial.md` §2.4: el hit-test se hace contra **3–6 cabeceras de grupo**, no contra 40 filas |
| **Ctrl+click** para selección múltiple | No hay Ctrl. | Checkbox explícito en el modo "seleccionar". | `SelectionMode` |
| **Scroll con la rueda / barra de scroll arrastrable** | El dedo scrollea; la barra no se puede arrastrar con el dedo (aunque se puede tocar en desktop). | Scroll táctil nativo del `LazyColumn` + indicador del sistema. | §7 |
| **`JFileChooser`** (Swing) | No existe en Android. | SAF: `ActivityResultContracts.OpenMultipleDocuments` / `CreateDocument`. | `expect` en `platform/FilePicker` (ya planeado en `teacher/01` §4.1) |
| **`java.awt.Desktop.open/browse`** | No existe en Android. | `Intent.ACTION_VIEW` (+ `ACTION_SEND` para compartir). | `expect` en `platform/FileActions` / `ShareSheet` |
| **Atajos de teclado** | El teclado virtual no genera `KeyEvent`. | Botón visible (48 dp) con la misma acción. Todos los atajos tienen botón. | §8 |
| **Borde de ventana redimensionable** | No hay. | Layout responsive + `WindowSizeClass`. | §2–§3 |
| **Botón central del mouse / rueda horizontal** | No hay. | — | — |

### 5.1 Los puertos de plataforma

Los cuatro `expect/actual` que hacen falta (ya previstos en
`teacher/01-navegacion.md` §4.1; se repiten acá porque son la base de esta
tabla):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/PlatformActions.kt
package kitoru.ui.platform

import kitoru.platform.FilePath

/** Elegir archivos del disco. */
expect suspend fun pickFiles(extensions: List<String> = emptyList()): List<FilePath>

/** Guardar un archivo en un destino elegido por el usuario. */
expect suspend fun saveFile(suggestedName: String, extensions: List<String>): FilePath?

/** Abrir un archivo con la app del sistema. */
expect fun openInSystem(path: FilePath): Boolean

/** Compartir texto con otras apps. */
expect suspend fun shareText(text: String, mimeType: String = "text/plain")

/** Revelar una carpeta en el explorador de archivos (sólo desktop). */
expect fun revealInFileManager(path: FilePath): Boolean
```

| `expect` | `desktopMain` | `androidMain` |
|---|---|---|
| `pickFiles` | `JFileChooser` (Swing, en el hilo de Swing) | SAF `OpenMultipleDocuments` |
| `saveFile` | `JFileChooser` modo guardar | SAF `CreateDocument` |
| `openInSystem` | `java.awt.Desktop.getDesktop().open` | `Intent.ACTION_VIEW` + `FileProvider` |
| `shareText` | portapapeles + diálogo "guardar como" | `Intent.ACTION_SEND` con `FileProvider` |
| `revealInFileManager` | `Desktop.browse` | **no existe** → el botón se oculta (no hay un `expect` que devuelva `false`: se decide por `sizeClass`/plataforma) |

> `java.awt.Desktop` y `javax.swing.JFileChooser` **nunca** aparecen en
> `commonMain` (regla 2 del CONTEXTO).

### 5.2 Regla general

> **Toda acción que en desktop se hace con un gesto del mouse tiene un botón
> visible de 48 dp en Android.** No hay acciones "sólo de escritorio": si el
> gesto es cómodo, el botón también; si el botón no cabe, la acción se saca
> del alcance táctil (y entonces **no es una acción importante**).

---

## 6. Menú contextual multiplataforma

### 6.1 El problema

`ChatSidebar.kt` (FunshiEngineAI) abre el menú con:

```kotlin
.onPointerEvent(PointerEventType.Press) { e ->
    if (e.button == PointerButton.Secondary) { … }
}
```

`PointerButton` **existe** en Android (es código común de `compose-ui`), pero
**el evento nunca ocurre**: en Android, `PointerEvent.buttons` siempre es
`PointerButton.Primary` (un dedo o el dedo del stylus). El código compila, no
da error, y **simplemente nunca se dispara**: el menú contextual sería
inalcanzable en Android.

Lo que sí existe en Android es el **long-press**, que es el gesto equivalente
en todas las apps nativas.

**Decisión (D4/D5): un solo componente con dos disparadores.**

| Disparador | Plataforma | Ancla del menú |
|---|---|---|
| Botón secundario (mouse) | Escritorio | **En el cursor** (coordenadas exactas del puntero) |
| Long-press (500 ms) | Android y escritorio | **En el elemento** (borde inferior izquierdo del `boundsInRoot()`), como hace Android |
| Botón `⋮` | Todas | En el elemento, como el long-press |

### 6.2 El componente

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/ContextMenu.kt
package kitoru.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.PopupProperties

/** Un ítem del menú contextual. */
data class ContextMenuItem(
    val label: String,
    val leadingIcon: (@Composable () -> Unit)? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Estado de un menú contextual abierto. */
data class ContextMenuState(
    /** Ancla en **píxeles**, en el sistema de coordenadas de la raíz de composición. */
    val anchor: Offset,
    val items: List<ContextMenuItem>,
)

/**
 * Abre un menú contextual con el **botón derecho** (escritorio) o con
 * **long-press** (táctil). Los dos disparadores están siempre activos: con
 * mouse se puede usar el long-press y con el dedo también el botón `⋮`.
 *
 * El menú se dibuja en [AdaptiveContextMenuHost], que vive en la raíz.
 */
@Composable
fun Modifier.contextMenuAnchor(
    onClick: () -> Unit,
    menuItems: () -> List<ContextMenuItem>,
    onOpen: (ContextMenuState) -> Unit,
    enabled: Boolean = true,
): Modifier {
    var bounds by remember { mutableStateOf(Rect.Zero) }

    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = {
                // El long-press no necesita la posición exacta del dedo: se
                // ancla al elemento, como las apps nativas de Android.
                onOpen(ContextMenuState(anchor = bounds.bottomLeft, items = menuItems()))
            },
        )
        .pointerInput(enabled) {
            // Botón secundario (sólo desktop): el menú se abre EN EL CURSOR.
            // En Android este bloque nunca se ejecuta (no hay botón 2).
            awaitPointerEventScope {
                var secondaryPressed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when {
                        event.type == PointerEventType.Press &&
                            event.buttons == PointerButton.Secondary -> {
                            secondaryPressed = true
                            val local = event.changes.first().position
                            onOpen(
                                ContextMenuState(
                                    anchor = bounds.topLeft + local,
                                    items = menuItems(),
                                )
                            )
                        }
                    }
                    if (secondaryPressed) {
                        // Se consume el down, el move y el up del botón
                        // secundario: si no, el `clickable` lo interpretaría
                        // como un toque normal y la fila se abriría al hacer
                        // click derecho.
                        event.changes.forEach { it.consume() }
                        if (event.type == PointerEventType.Release) secondaryPressed = false
                    }
                }
            }
        }
}

/**
 * Dibuja el menú contextual abierto.
 *
 * Va **una sola vez**, en la raíz de la app, sobre un `Box` que llena la
 * ventana: así el `offset` del `DropdownMenu` está en coordenadas de ventana
 * y el menú aparece donde corresponde en las dos plataformas (H7).
 */
@Composable
fun AdaptiveContextMenuHost(
    state: ContextMenuState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state ?: return
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft }
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = with(density) {
                DpOffset(
                    (current.anchor.x - hostOrigin.x).toDp(),
                    (current.anchor.y - hostOrigin.y).toDp(),
                )
            },
            // `focusable = true` ⇒ el menú se puede usar con teclado (Esc
            // cierra, las flechas mueven el foco) y el toque de afuera lo
            // cierra. NO se pone `usePlatformDefaultWidth = false`: con la
            // ventana ajustada al contenido, el toque de afuera NO alcanzaría
            // el popup y el menú no se cerraría.
            properties = PopupProperties(focusable = true),
        ) {
            current.items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        onDismiss()
                        item.onClick()
                    },
                    enabled = item.enabled,
                    leadingIcon = item.leadingIcon,
                )
            }
        }
    }
}
```

Y el host, en la raíz:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruApp.kt
@Composable
fun KitoruApp(env: KitoruEnv) {
    …
    KitoruTheme(dark = app.isDark, accent = app.accentColor) {
        ProvideWindowSize {
            var contextMenu by remember { mutableStateOf<ContextMenuState?>(null) }
            Box(Modifier.fillMaxSize()) {
                AppShell(app = app, onOpenContextMenu = { contextMenu = it })
                AdaptiveContextMenuHost(
                    state = contextMenu,
                    onDismiss = { contextMenu = null },
                )
            }
        }
    }
}
```

### 6.3 Por qué el overlay "que llena la ventana"

`DropdownMenu` se posiciona respecto del **ancla** (el composable que lo
contiene) más el `offset`, y después se ajusta a los bordes de la ventana
(H7). Si el `DropdownMenu` viviera dentro de una columna, el `offset` sería
relativo a esa columna y el menú aparecería en el lugar equivocado.

Por eso:
1. el host es un `Box(Modifier.fillMaxSize())` en la **raíz** de la app →
   su ancla es la ventana;
2. el ancla del menú se guarda en coordenadas de la **raíz de composición**
   (`boundsInRoot()` + posición local del puntero);
3. el host resta su propia posición (`hostOrigin`) para convertir a su
   espacio, que es el de la ventana.

Resultado: el mismo código da el menú **en el cursor** en desktop y **debajo
del elemento** en Android.

### 6.4 Accesibilidad

- `combinedClickable(onLongClick = …)` publica la acción de long-press en la
  semántica: TalkBack la ofrece como "acciones disponibles" y el doble toque
  prolongado la dispara ✓.
- Cada ítem del menú es un `DropdownMenuItem` (semántica de botón + texto) ✓.
- El menú es un `Popup` enfocable: con `Esc` se cierra y el foco vuelve al
  elemento que lo abrió ✓ (§8.4).
- El botón `⋮` es la vía **explícita** (con `contentDescription = "Acciones"`)
  para quien no puede hacer long-press.

### 6.5 Cuándo hoja inferior en vez de `DropdownMenu`

`DropdownMenu` con muchos ítems se sale de la pantalla. Regla:

| Ítems | Estilo |
|---|---|
| ≤ 6 | `DropdownMenu` (desktop y Android) |
| > 6 | `ModalBottomSheet` en `COMPACT`; `DropdownMenu` con scroll en `MEDIUM`/`EXPANDED` |

Esto cubre justo lo que ya decidió `student/07-historial.md` §4.1 (el menú de
hilos tiene 10 ítems → hoja inferior en Android) y lo que decidió
`student/02-chat.md` §2.2 (las acciones de la burbuja del mensaje, 4 ítems →
`DropdownMenu` en las dos plataformas).

```kotlin
/** Estilo del menú contextual. */
enum class ContextMenuStyle { DROPDOWN, BOTTOM_SHEET }

/** Decide el estilo según la cantidad de ítems y el ancho. */
fun contextMenuStyle(itemCount: Int, sizeClass: WindowSizeClass): ContextMenuStyle =
    if (itemCount > 6 && sizeClass == WindowSizeClass.COMPACT) {
        ContextMenuStyle.BOTTOM_SHEET
    } else {
        ContextMenuStyle.DROPDOWN
    }
```

### 6.6 Menos código, más comportamiento

El `ChatSidebar.kt` de FunshiEngineAI **no se porta tal cual**: su drag &
drop manual (`boundsInRoot` + hit-testing sobre todas las filas) y su click
derecho se reemplazan por:

- `Modifier.contextMenuAnchor` (menú unificado), y
- el hit-testing **sólo contra las cabeceras de grupo** (3–6 objetivos), que es
  lo que dice `student/07-historial.md` §2.4.

---

## 7. El scrollbar

### 7.1 Verificación (H2)

| Artefacto | ¿Tiene `androidx.compose.foundation.v2.ScrollbarAdapter`? |
|---|---|
| `org.jetbrains.compose.foundation:foundation-desktop:1.7.3` | **Sí** (`ScrollbarAdapter.class`, `LazyListScrollbarAdapter.class`, `Scrollbar_desktopKt.class`) |
| `org.jetbrains.compose.foundation:foundation:1.7.3` (variante Android) | **No**: no aporta clases; depende de `androidx.compose.foundation:foundation:1.7.6` |
| `androidx.compose.foundation:foundation-android:1.7.6` | **No**: ni el paquete `foundation.v2` ni ninguna clase de scrollbar (revisado el `classes.jar` y los *sources* de 1.6.4, 1.7.6 y 1.8.2) |

**Conclusión:** `androidx.compose.foundation.v2.ScrollbarAdapter` es **API de
escritorio (skiko)**. Un archivo en `commonMain` que la importe **no compila
para Android**.

> **Corrección obligatoria**: `docs/design/student/02-chat.md` §1 (línea 58)
> dice *«Port literal. `androidx.compose.foundation.v2.ScrollbarAdapter` existe
> en CMP 1.7»*. Eso es cierto **sólo para desktop**. El port literal a
> `commonMain` no compila; hay que partirlo como se describe abajo.
> También queda respondida la pregunta abierta de
> `docs/design/teacher/07-chats.md` §4.1 (línea 371).

### 7.2 Decisión (D6/D7)

La **lógica** del adapter estable (medir el tamaño de cada ítem, acumular,
estimar los no vistos con el promedio) es **pura**: sólo usa `LazyListState` y
un mapa de tamaños. Esa lógica se queda en `commonMain`, pero implementando una
**interfaz propia**, sin tipos de androidx:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/components/ScrollbarSpec.kt
package kitoru.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Descripción del estado de scroll, independiente de la plataforma.
 *
 * Es la única parte del scrollbar que se comparte: el dibujado es de
 * escritorio, pero la medición (que es lo que cuesta) es común.
 */
interface ScrollbarSpec {
    /** Offset de scroll actual, en píxeles. */
    val scrollOffset: Double

    /** Tamaño total del contenido, en píxeles. */
    val contentSize: Double

    /** Tamaño de la ventana de scroll, en píxeles. */
    val viewportSize: Double

    /** Lleva el scroll a [offset]. */
    suspend fun scrollTo(offset: Double)
}

/**
 * Scrollbar vertical adaptativo.
 *
 * - Escritorio: scrollbar real (12 dp, con arrastre y atajos de teclado).
 * - Android: **nada** — el sistema dibuja el indicador al scrollear.
 */
@Composable
expect fun AdaptiveVerticalScrollbar(spec: ScrollbarSpec, modifier: Modifier = Modifier)
```

El adapter de FunshiEngineAI se porta **casi literal**, cambiando la
interfaz que implementa:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ChatScrollbarAdapter.kt
package kitoru.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kitoru.ui.components.ScrollbarSpec

/**
 * Adapter de scrollbar del chat con tamaño de thumb ESTABLE.
 *
 * (Port de `app/chat/ChatScrollbarAdapter.kt` de FunshiEngineAI.)
 *
 * **Único cambio respecto del original**: implementa [ScrollbarSpec] (interfaz
 * propia) en vez de `androidx.compose.foundation.v2.ScrollbarAdapter`, porque
 * esa API **sólo existe en el artefacto desktop** de CMP 1.7.3 (verificado,
 * §7.1). La lógica es idéntica y sigue siendo pura.
 */
class StableChatScrollbarAdapter(
    private val listState: LazyListState,
    private val sizes: Map<Int, Float>,
) : ScrollbarSpec {

    private fun averageKnown(): Double =
        if (sizes.isEmpty()) 0.0 else sizes.values.average()

    private fun sizeOf(index: Int, average: Double): Double =
        sizes[index]?.toDouble() ?: average

    private fun prefix(index: Int, average: Double, spacing: Double): Double {
        var acc = 0.0
        for (i in 0 until index) acc += sizeOf(i, average) + spacing
        return acc
    }

    override val scrollOffset: Double
        get() {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return 0.0
            val spacing = info.mainAxisItemSpacing.toDouble()
            return prefix(first.index, averageKnown(), spacing) - first.offset
        }

    override val contentSize: Double
        get() {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return 0.0
            val spacing = info.mainAxisItemSpacing.toDouble()
            val average = averageKnown()
            var acc = 0.0
            for (i in 0 until total) acc += sizeOf(i, average)
            return acc + spacing * (total - 1) + info.beforeContentPadding + info.afterContentPadding
        }

    override val viewportSize: Double
        get() = listState.layoutInfo.viewportSize.height.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return
        val spacing = info.mainAxisItemSpacing.toDouble()
        val average = averageKnown()
        val target = scrollOffset.coerceAtLeast(0.0)
        var index = 0
        var itemStart = 0.0
        while (index < total) {
            val size = sizeOf(index, average)
            if (target < itemStart + size || index == total - 1) {
                val inner = (target - itemStart).toInt()
                    .coerceIn(0, (size.toInt() - 1).coerceAtLeast(0))
                listState.scrollToItem(index, inner)
                return
            }
            itemStart += size + spacing
            index++
        }
    }
}

/** [remember] del adapter, con un mapa de tamaños por conversación. */
@Composable
fun rememberStableChatScrollbarAdapter(
    listState: LazyListState,
    chatId: String,
): StableChatScrollbarAdapter {
    val sizes = remember(chatId) { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(listState, chatId) {
        var lastTotal = 0
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.visibleItemsInfo }
            .collect { (total, visibles) ->
                // Si la conversación se acorta, los índices corren: se
                // descarta el mapa (mismo criterio que el original).
                if (total < lastTotal) sizes.clear()
                lastTotal = total
                for (item in visibles) {
                    val size = item.size.toFloat()
                    if (sizes[item.index] != size) sizes[item.index] = size
                }
            }
    }
    return remember(listState, sizes) { StableChatScrollbarAdapter(listState, sizes) }
}
```

### 7.3 Los dos `actual`

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/components/Scrollbar.desktop.kt
package kitoru.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Traduce el [ScrollbarSpec] al adapter de androidx y dibuja el scrollbar.
 *
 * **Sólo desktop**: `androidx.compose.foundation.v2` no existe en el
 * artefacto Android de CMP 1.7.3 (verificado, §7.1).
 */
@Composable
actual fun AdaptiveVerticalScrollbar(spec: ScrollbarSpec, modifier: Modifier) {
    val adapter = remember(spec) { SpecAsAndroidxAdapter(spec) }
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier,
    )
}

/** Puente entre [ScrollbarSpec] y el adapter de androidx. */
private class SpecAsAndroidxAdapter(private val spec: ScrollbarSpec) : ScrollbarAdapter {
    override val scrollOffset: Double get() = spec.scrollOffset
    override val contentSize: Double get() = spec.contentSize
    override val viewportSize: Double get() = spec.viewportSize
    override suspend fun scrollTo(scrollOffset: Double) = spec.scrollTo(scrollOffset)
}
```

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/components/Scrollbar.android.kt
package kitoru.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * En Android **no hay scrollbar**: el sistema dibuja el indicador al
 * scrollear y arrastrarlo no es un gesto soportado.
 *
 * (Opcional, fase 2: un indicador de 3 dp que aparece 1 s al scrollear y se
 * desvanece. Se puede dibujar con el mismo [ScrollbarSpec]; no lo hace falta
 * para la v1.)
 */
@Composable
actual fun AdaptiveVerticalScrollbar(spec: ScrollbarSpec, modifier: Modifier) {
    // No-op deliberado.
}
```

### 7.4 Reglas de uso

1. El scrollbar **sólo** se dibuja si `WindowSizeClass.EXPANDED` **y** el
   panel tiene contenido que no entra (el `VerticalScrollbar` de androidx ya
   se oculta solo si el thumb llena la pista).
2. En `MEDIUM` el chat **no** lleva scrollbar: el área es chica y el scrollbar
   roba 12 dp.
3. En `COMPACT` **jamás**: el indicador del sistema cumple.
4. El scrollbar de la **lista de hilos** (`ChatSidebar`) tiene el mismo
   tratamiento: sólo en `EXPANDED`.
5. El scrollbar del chat **arrastra** y responde a `↑ ↓ Re Pág Av Pág Inicio
   Fin` en desktop (`student/02-chat.md` §11) — son atajos del componente, no
   atajos globales.

---

## 8. Teclado (escritorio)

### 8.1 El mapa de atajos

Se **extiende** el mapa de `student/10-accesibilidad-i18n.md` §7.2 (que es la
fuente de verdad) con las reglas de este documento:

| Atajo | Acción | ¿Se ignora con un campo de texto con foco? | Dónde se registra |
|---|---|---|---|
| `Ctrl/⌘ + Enter` | **Enviar el mensaje** | **No** (lo maneja el propio campo) | Dentro del composer (common) |
| `Ctrl/⌘ + N` | Nueva conversación | Sí | Global (desktop) |
| `Ctrl/⌘ + K` | Buscar (paleta / buscador global) | Sí | Global (desktop) |
| `Ctrl/⌘ + F` | Buscar en la sección actual | Sí | Global (desktop) |
| `Ctrl/⌘ + ,` | Preferencias | Sí | Global (desktop) |
| `Ctrl/⌘ + 1..9` | Ir al hilo / panel N | Sí | Global (desktop) |
| `F6` | Ciclar entre paneles (nav ↔ contenido ↔ composer) | No | Global (desktop) |
| `Alt + ↑/↓` | Historial del composer (mensajes anteriores) | No | Dentro del composer (common) |
| `Esc` | Cerrar menú → hoja → diálogo → panel; si no hay nada abierto, saca el foco del campo | **Sí** (lo atiende el campo) | Global (desktop) + composer |
| `Ctrl/⌘ + C/V/X/A/Z` | **NUNCA** se interceptan globalmente | — | El campo de texto (nativo) o la burbuja con el foco |

### 8.2 La trampa del `Ctrl+C` y cómo se evita

El danger real: si la app registra un atajo global "copiar el mensaje" con
`Ctrl+C`, ese atajo **se come el `Ctrl+C` nativo del campo de texto** y el
alumno no puede copiar un fragmento de lo que está escribiendo. Peor: si el
atajo global está en `onPreviewKeyEvent` de la raíz, se ejecuta **antes** que
el campo, así que el campo nunca ve la tecla.

Reglas (D8):

1. **El mapa global no contiene `Ctrl+C`, `Ctrl+V`, `Ctrl+X`, `Ctrl+A` ni
   `Ctrl+Z`.** Ni una sola entrada. Es la única garantía fuerte: no hay forma
   de que el dispatcher global se interpole.
2. **"Copiar" vive dentro de la componente que lo ofrece**: la burbuja del
   mensaje. Se implementa con `onKeyEvent` sobre la burbuja, que sólo recibe
   el evento si **la burbuja** tiene el foco. Con el foco en el composer, el
   `Ctrl+C` es del campo ✓.
3. El resto de los atajos con `Ctrl` se **ignoran** si hay un campo de texto
   con el foco. La detección es la bandera explícita `LocalIsEditingText` de
   `student/10-accesibilidad-i18n.md` §7.3 (no introspección del árbol de
   foco: es frágil y no funciona igual en desktop).
4. `Ctrl+Enter` es la **única** excepción: lo maneja el propio campo, no el
   dispatcher global.

### 8.3 El código

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/a11y/GlobalShortcuts.kt
package kitoru.ui.a11y

import androidx.compose.ui.input.key.*

/** Acciones que se pueden lanzar con el teclado. */
enum class ShortcutAction {
    SEND, NEW_THREAD, SEARCH, PALETTE, PREFERENCES, GO_TO_N,
    CYCLE_PANELS, CLOSE, COMPOSER_HISTORY,
}

/**
 * Mapa de atajos globales.
 *
 * **Regla dura:** este objeto NO contiene `Ctrl+C`, `Ctrl+V`, `Ctrl+X`,
 * `Ctrl+A` ni `Ctrl+Z`. Esos los maneja el campo de texto (o la burbuja con
 * el foco): un atajo global de copiar/pegarse rompe la edición de texto
 * (§8.2).
 */
object GlobalShortcuts {

    /**
     * Traduce un evento a una acción, o `null` si no corresponde.
     *
     * @param isTextFieldFocused hay un campo de texto con el foco
     *        (`LocalIsEditingText`). Con un campo con foco, los atajos con
     *        `Ctrl` se ignoran, salvo `Ctrl+Enter`.
     */
    fun resolve(event: KeyEvent, isTextFieldFocused: Boolean): ShortcutAction? {
        if (event.type != KeyEventType.KeyDown) return null
        val ctrl = event.isCtrlPressed || event.isMetaPressed

        return when {
            // Primero, porque vale aunque haya un campo con foco.
            ctrl && event.key == Key.Enter -> ShortcutAction.SEND

            // Cualquier otro atajo con Ctrl: si se está escribiendo, no.
            ctrl && isTextFieldFocused -> null

            ctrl && event.key == Key.N -> ShortcutAction.NEW_THREAD
            ctrl && event.key == Key.K -> ShortcutAction.PALETTE
            ctrl && event.key == Key.F -> ShortcutAction.SEARCH
            ctrl && event.key == Key.Comma -> ShortcutAction.PREFERENCES
            ctrl && event.key in Key.One..Key.Nine -> ShortcutAction.GO_TO_N

            // Sin Ctrl: no se ignoran por el foco.
            event.key == Key.F6 -> ShortcutAction.CYCLE_PANELS
            event.key == Key.Escape && !isTextFieldFocused -> ShortcutAction.CLOSE
            event.altPressed &&
                (event.key == Key.DirectionUp || event.key == Key.DirectionDown) ->
                ShortcutAction.COMPOSER_HISTORY

            else -> null
        }
    }
}
```

Registro (sólo escritorio, porque `Window(onKeyEvent = …)` es API de CMP
desktop):

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt
Window(
    onKeyEvent = { event ->
        if (event.type != KeyEventType.KeyDown) return@Window false
        val action = GlobalShortcuts.resolve(event, isTextFieldFocused = app.isEditingText)
            ?: return@Window false
        app.run(action)
        true
    },
    // …
)
```

Y en el composer (esto es **común**, funciona también en Android con teclado
físico):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ComposerBar.kt
OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    modifier = Modifier
        .fillMaxWidth()
        .onPreviewKeyEvent { event ->
            // Ctrl/Cmd+Enter envía. Se maneja AQUÍ (no globalmente) para no
            // romper el Ctrl+C del campo.
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed && event.key == Key.Enter
            ) {
                send()
                true
            } else {
                false
            }
        },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
    keyboardActions = KeyboardActions(onSend = { send() }),
    // …
)
```

### 8.4 La cascada de `Esc`

`Esc` se resuelve de adentro hacia afuera, en este orden:

1. Si hay un **menú contextual** abierto → se cierra.
2. Si hay un **tooltip** abierto → se cierra.
3. Si hay un **diálogo** abierto → se cierra (con confirmación si descarta
   trabajo).
4. Si hay una **hoja inferior** (`ModalBottomSheet`) → se cierra.
5. Si hay un **panel de detalle** en `COMPACT` → se vuelve a la lista.
6. Si el **campo de texto** tiene el foco → se le saca el foco.
7. Si no hay nada abierto → `navigator.pop()` (o no hacer nada).

Cada nivel es un `Dialog`/`Popup` que ya consume `Esc` por su cuenta (por
`focusable = true`); los niveles 5–7 los resuelve el shell.

### 8.5 Foco visible

- **Nunca** `indication = null` (regla de accesibilidad de
  `teacher/01-navegacion.md` §7.1). En desktop, el indicador de foco de M3 es
  un anillo de 2 dp con `primary` ✓.
- Los elementos del rail (`NavigationRailItem`) son enfocables por defecto ✓.
- Los elementos de la lista (hilos, materiales) usan `Modifier.focusable()` con
  el `indication` por defecto, y `Modifier.focusGroup()` por sección, para que
  `Tab` recorra la sección de punta a punta.
- Al abrir un diálogo, el foco va al **primer campo**; al cerrarlo, **vuelve**
  al elemento que lo abrió (`FocusRequester` guardado al abrir).
- Al cambiar de sección, el foco va al **título** de la sección, no al primer
  control.

---

## 9. El teclado en pantalla: `imePadding`

### 9.1 El `Modifier` exacto

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ComposerBar.kt
@Composable
fun ComposerBar(
    vm: StudentChatViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            // 1) Costuras horizontales: muesca (notch) y barra de gestos en
            //    landscape. Sólo ancho: arriba y abajo los maneja el teclado.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            )
            // 2) Barra de navegación del sistema, descontando la parte que
            //    tapa el teclado (para no contarla dos veces).
            .windowInsetsPadding(
                WindowInsets.navigationBars.exclude(WindowInsets.ime)
            )
            // 3) El teclado en pantalla.
            .imePadding(),
        // …
    ) {
        // campo + botón
    }
}
```

### 9.2 Por qué ese orden (el error clásico)

- `WindowInsets.ime` en Android **incluye** la altura de la barra de
  navegación cuando el teclado está abierto (el teclado la tapa).
- `WindowInsets.navigationBars.exclude(WindowInsets.ime)` da la barra de
  navegación **menos** lo que el teclado ya cubre: con el teclado abierto da
  **0**; con el teclado cerrado da la barra completa ✓.
- `.imePadding()` agrega la altura del teclado.

Resultado: teclado cerrado → se reserva la barra del sistema; teclado abierto
→ se reserva **exactamente** el teclado, sin doble conteo.

El error a evitar: `navigationBarsPadding().imePadding()` a secas → con el
teclado abierto la barra se cuenta **dos veces** y el composer queda 48 dp más
arriba de lo que debería.

En **escritorio** los tres valen 0 (`ime` es `ZeroInsets` en el
`actual` "notMobile", H5) ⇒ **la misma cadena sirve para las dos plataformas,
sin `expect/actual` de insets**.

### 9.3 El `Scaffold` y los insets

Para que nada se cuente dos veces, la app usa una regla única:

> **El `Scaffold` raíz aplica `contentWindowInsets = WindowInsets(0.dp)` y cada
> región maneja sus propios insets.**

| Región | Insets que aplica |
|---|---|
| `TopAppBar` | `TopAppBarDefaults.windowInsets` (status bar) — por defecto en M3 ✓ |
| Lista / contenido | `WindowInsets.safeDrawing.only(Horizontal)` |
| `NavigationBar` (bottom bar) | `WindowInsets.navigationBars.exclude(ime)` |
| `ComposerBar` | la cadena de §9.1 |
| Diálogos | `DialogProperties.usePlatformDefaultWidth` según el ancho (§11) |

### 9.4 Landscape y el notch

En landscape, la muesca (o el cámara) invade el borde lateral:
`safeDrawing.only(WindowInsetsSides.Horizontal)` lo resuelve. Sin eso, el
campo de texto queda debajo del notch.

Además, con `heightClass == SHORT` (teléfono en horizontal), el alto útil es
mínimo: el composer tiene un máximo de 3 líneas visibles
(`maxLines = 3`) y la lista de mensajes usa todo lo demás.

### 9.5 El botón "Enviar" del teclado

- En Android, el `ImeAction.Send` del campo manda el mensaje
  (`keyboardActions(onSend = { send() })`) — es el camino principal del
  teléfono, no un atajo.
- En desktop, `Ctrl+Enter` ✓.
- El botón **visible** de enviar (48 dp) está siempre: es el camino que no
  depende de ninguna tecla.

---

## 10. Objetivos táctiles, scrolling y densidad

### 10.1 48 dp en Android, 40 dp en escritorio

| Plataforma | Mínimo | Cómo se fuerza |
|---|---|---|
| Android | **48 dp** | `Modifier.minimumInteractiveComponentSize()` (M3, common) — el default de `LocalMinimumInteractiveComponentSize` ya es 48 dp |
| Escritorio | **40 dp** | `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp)` en la raíz del desktop |

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/core/InteractiveSize.desktop.kt
@Composable
fun PlatformInteractiveSize() {
    // En escritorio el mouse no necesita 48 dp: 40 dp alcanza y deja más
    // espacio para el contenido.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        content()
    }
}
```

Reglas:

- Todo control propio (no los de M3, que ya lo cumplen) lleva
  `Modifier.minimumInteractiveComponentSize()`.
- Un ícono de 20 dp dentro de un `IconButton` de M3 ya mide 48 dp ✓; un `Text`
  con `padding(8.dp)` **no** ⇒ hay que ponerle el modifier.
- Las filas de lista usan `heightIn(min = 48.dp)` (nunca `height = 40.dp`
  fijo: el texto escalado a 200 % debe poder crecer).
- La separación entre controles adyacentes es ≥ 8 dp (para que no se confundan
  los toques).
- El área de toque del *swipe* (deslizar para borrar un hilo) tiene que
  competir con el scroll: el umbral es "una dirección horizontal clara", no
  "cualquier movimiento".

### 10.2 Scrolling táctil

- `LazyColumn` con `contentPadding` que **deja respirar** el último ítem
  (12–24 dp) para que el dedo no quede pegado al borde.
- El `LazyColumn` **no** lleva scrollbar en `COMPACT` (el indicador del
  sistema aparece al scrollear y se desvanece solo).
- `Modifier.animateScrollToItem` / `scrollToItem` para "ir al final" ✓
  (funciona en las dos plataformas).
- El FAB "↓" (ir al final) aparece **sólo** si `listState.canScrollForward`:
  en Android ocupa 56 dp y tapa mensajes; en desktop, `scrollToBottom` con el
  atajo `End`.
- **Pull-to-refresh**: sólo en Android, con `PullToRefreshBox` (M3 1.3) — en
  desktop, un botón "Actualizar".

### 10.3 Densidad

| | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Padding horizontal | 16 dp | 20 dp | 24 dp |
| Separación entre tarjetas | 8 dp | 12 dp | 16 dp |
| Tipografía de lista | `bodyMedium` (14 sp) | `bodyLarge` (16 sp) | `bodyLarge` |
| Tipografía de metadatos | `labelSmall` (11 sp) | `labelMedium` (12 sp) | `labelMedium` |
| Ancho máximo de una burbuja de chat | `fillMaxWidth()` − 24 dp | 560 dp | 700 dp |
| Ancho de una columna de texto (materiales, prompts) | `fillMaxWidth()` | 640 dp | 720 dp |
| Chips visibles por fila | 2 + "▾" | 4 | todos, con wrap |

> El ancho máximo del texto (560/640/720 dp) es la regla de lectura: una línea
> de 90 caracteres es incómoda. Se aplica con `widthIn(max = …)`, no con
> `fillMaxWidth`.

---

## 11. El diálogo de permisos de herramientas

### 11.1 Qué es

`student/02-chat.md` §1 porta `app/chat/PermissionDialog.kt`: el modelo pide
permiso para usar una categoría de herramientas (archivos, web) y el alumno
tiene que aceptar o rechazar.

El contenido del diálogo:

```
┌─────────────────────────────────────┐
│ El modelo pide usar archivos         │
│                                      │
│ La respuesta que está generando       │
│ necesita leer y escribir archivos en  │
│ este dispositivo.                     │
│                                      │
│ ☐ Archivos del dispositivo            │
│ ☐ Carpeta de la cátedra (sólo lectura)│
│                                      │
│ El permiso vale sólo para esta        │
│ conversación.                         │
│                                      │
│        [Ahora no]  [ Permitir ]       │
└─────────────────────────────────────┘
```

### 11.2 El problema del ancho

En Android, `DialogProperties.usePlatformDefaultWidth = true` (el default)
hace que la ventana del diálogo use el ancho por defecto de la plataforma. En
un teléfono de 360 dp, con etiquetas largas ("Archivos del dispositivo",
"carpeta de la cátedra (sólo lectura)") y un `Checkbox`, el contenido **se
corta**: el `AlertDialog` de M3 pone el contenido en una columna con
`padding` y el texto se parte en varias líneas, pero el alto disponible es
justo y las opciones quedan tapadas detrás del teclado.

La solución es tomar control del ancho:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/PermissionDialog.kt
@Composable
fun PermissionDialog(
    request: PermissionRequest,
    onGrant: (Set<PermissionCategory>) -> Unit,
    onDeny: () -> Unit,
) {
    val sizeClass = rememberWindowSizeClass()
    var granted by remember { mutableStateOf(request.grantedCategories.toSet()) }

    AlertDialog(
        onDismissRequest = onDeny,        // cerrar = denying (fail-closed)
        modifier = if (sizeClass == WindowSizeClass.COMPACT) {
            // Con `usePlatformDefaultWidth = false` la ventana del diálogo
            // se ajusta al contenido: hay que darle el ancho a mano.
            Modifier.fillMaxWidth(0.92f)
        } else {
            Modifier   // en desktop/tablet, el ancho por defecto (contenido)
        },
        properties = if (sizeClass == WindowSizeClass.COMPACT) {
            // `usePlatformDefaultWidth` es la ÚNICA propiedad de
            // `DialogProperties` que existe en las dos plataformas
            // (además de `dismissOnBackPress` y `dismissOnClickOutside`) — H4.
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,   // tocar afuera NO concede nada
                usePlatformDefaultWidth = false,
            )
        } else {
            DialogProperties()
        },
        title = { Text("El modelo pide permiso para usar ${request.categoryLabel}") },
        text = {
            Column {
                Text(request.explanation)
                Spacer(Modifier.height(12.dp))
                request.categories.forEach { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)      // objetivo táctil
                            .clickable { … }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = category in granted, onCheckedChange = { … })
                        Spacer(Modifier.width(8.dp))
                        Text(category.label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "El permiso vale sólo para esta conversación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onGrant(granted) }) { Text("Permitir") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Ahora no") }
        },
    )
}
```

### 11.3 Reglas

1. **Fail-closed**: cerrar el diálogo (tocar afuera, `Esc`, atrás) = **no
   conceder**. Nunca "conceder todo por defecto".
2. `dismissOnClickOutside = false`: tocar afuera no debe conceder ni dejar el
   modelo esperando; se interpreta como "ahora no" y se avisa.
3. En `COMPACT`, `usePlatformDefaultWidth = false` + `fillMaxWidth(0.92f)`: el
   diálogo usa el 92 % del ancho en vez del ancho de la plataforma, y el
   contenido se puede scrollear si el teclado lo tapa.
4. En `EXPANDED`/`MEDIUM` se deja el diálogo al tamaño del contenido (más
   elegante en escritorio) — **el mismo componente, sin `expect/actual`**.
5. En Android, el diálogo debe quedar **por encima del teclado**: el `ime`
   sube el diálogo porque la ventana del diálogo es una ventana del sistema
   que se reposiciona; si no, se le aplica
   `Modifier.imePadding()` al `text`.

### 11.4 El mismo criterio para los otros diálogos

| Diálogo | COMPACT | MEDIUM / EXPANDED |
|---|---|---|
| Permisos de herramientas | `usePlatformDefaultWidth = false` + 92 % | ancho de contenido |
| Renombrar chat / material | idem | ancho de contenido |
| Confirmación de borrado | idem | ancho de contenido |
| Hoja de fuentes | `ModalBottomSheet` | `ModalBottomSheet` o panel |
| Filtros de la lista | `ModalBottomSheet` | `DropdownMenu` en línea |

---

## 12. Archivos y composables

### 12.1 `:ui` — `commonMain` (nuevos o modificados)

| Archivo | Contenido |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/core/WindowSizeClass.kt` | `WindowSizeClass`, `WindowHeightClass`, `WindowSize`, `LocalWindowSize`, `ProvideWindowSize`, `rememberWindowSizeClass()`, `rememberWindowSize()` (**reemplaza** el fragmento de `teacher/01` §6.1) |
| `ui/src/commonMain/kotlin/kitoru/ui/core/AdaptiveNavigation.kt` | `AdaptiveNavigation`, `adaptiveNavigation(sizeClass, destinationCount, heightClass)` |
| `ui/src/commonMain/kotlin/kitoru/ui/core/AdaptiveScaffold.kt` | `AdaptiveScaffold`, `NavDestination` |
| `ui/src/commonMain/kotlin/kitoru/ui/core/NavigationRailPane.kt` | `NavigationRailPane` (puerto de `NavigationRail.kt`) |
| `ui/src/commonMain/kotlin/kitoru/ui/core/BottomBarPane.kt` | `BottomBarPane` (los destinos del alumno) |
| `ui/src/commonMain/kotlin/kitoru/ui/components/ContextMenu.kt` | `ContextMenuItem`, `ContextMenuState`, `ContextMenuStyle`, `contextMenuStyle()`, `Modifier.contextMenuAnchor`, `AdaptiveContextMenuHost` |
| `ui/src/commonMain/kotlin/kitoru/ui/components/ScrollbarSpec.kt` | `ScrollbarSpec`, `expect AdaptiveVerticalScrollbar` |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/ChatScrollbarAdapter.kt` | `StableChatScrollbarAdapter` (implementa `ScrollbarSpec`), `rememberStableChatScrollbarAdapter` — **corregido** |
| `ui/src/commonMain/kotlin/kitoru/ui/a11y/GlobalShortcuts.kt` | `ShortcutAction`, `GlobalShortcuts.resolve` |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/PlatformActions.kt` | `expect` de archivos, abrir, compartir (§5.1) |

### 12.2 `:ui` — específico de plataforma

| Archivo | Contenido |
|---|---|
| `ui/src/desktopMain/kotlin/kitoru/ui/core/WindowInfo.desktop.kt` | `rememberDesktopWindowSize()` (§3.3) |
| `ui/src/desktopMain/kotlin/kitoru/ui/core/InteractiveSize.desktop.kt` | `LocalMinimumInteractiveComponentSize = 40.dp` |
| `ui/src/desktopMain/kotlin/kitoru/ui/components/Scrollbar.desktop.kt` | `actual AdaptiveVerticalScrollbar` con `foundation.v2` (§7.3) |
| `ui/src/androidMain/kotlin/kitoru/ui/components/Scrollbar.android.kt` | `actual AdaptiveVerticalScrollbar` no-op (§7.3) |
| `ui/src/androidMain/kotlin/kitoru/ui/core/InteractiveSize.android.kt` | 48 dp (o nada: es el default) |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FilePicker.desktop.kt` | `JFileChooser` |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FilePicker.android.kt` | SAF (`OpenDocument` / `CreateDocument`) |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FileActions.desktop.kt` | `java.awt.Desktop` |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FileActions.android.kt` | `Intent.ACTION_VIEW` / `ACTION_SEND` |

### 12.3 Composables públicos

| Composable | Dónde | Qué hace |
|---|---|---|
| `ProvideWindowSize` | raíz de `KitoruApp` | Mide el contenedor e instala `LocalWindowSize` |
| `rememberWindowSizeClass()` | cualquiera | La clase de tamaño |
| `rememberWindowSize()` | cualquiera | Clase + alto + medidas |
| `AdaptiveScaffold` | raíz del shell | Rail / bottom bar / drawer |
| `NavigationRailPane` | shell | Rail colapsable |
| `AdaptiveContextMenuHost` | raíz de `KitoruApp` | Dibuja el menú contextual |
| `Modifier.contextMenuAnchor` | filas de lista, burbujas | Botón derecho (desktop) + long-press (Android) |
| `AdaptiveVerticalScrollbar` | paneles y listas | Scrollbar de escritorio, nada en Android |
| `GlobalShortcuts.resolve` | shell de escritorio | Mapa de atajos |

---

## 13. Casos borde

| Caso | Comportamiento |
|---|---|
| La ventana de escritorio se redimensiona de 1200 dp a 500 dp | `BoxWithConstraints` recalcula la clase en el mismo frame: el rail se colapsa, la lista se vuelve drawer, el panel de detalle pasa a pantalla completa. **El contenido no se pierde**: la pila de navegación y el `elementId` seleccionado se conservan (`teacher/01` §6.3). |
| El usuario arrastra la ventana a 500 dp **mientras está escribiendo** | El composer no se recompone con pérdida de foco: `TextFieldValue` + `FocusRequester` sobreviven al recomposición (el estado del campo está en el ViewModel, no en el composable). |
| El teléfono rota a landscape (COMPACT → MEDIUM) | Aparece el rail de íconos; la bottom bar desaparece; el `ime` se recalcula. El mensaje en streaming **no** se interrumpe: el job vive en el ViewModel. |
| Split-screen de Android: la app ocupa 300 dp | `COMPACT` ⇒ bottom bar; el diálogo de permisos usa el ancho de contenido con 92 %. |
| Tablet en landscape (1280×800 dp) | `EXPANDED` + `MEDIUM` en alto: rail extendida, lista + contenido, panel de detalle **no** cabe en el alto ⇒ el detalle se abre como overlay. |
| Pantalla de 360 dp con el teclado abierto y un diálogo abierto | El diálogo sube con el `ime`; el `AlertDialog` de M3 en Android ya se reposiciona; si no, `Modifier.imePadding()` en el `text`. |
| El usuario hace long-press sobre una fila **y** la arrastra | El long-press gana (se consume el evento) y no se dispara el arrastre; el arrastre de grupos sólo existe en desktop (§5). |
| El usuario hace click derecho **y** suelta fuera de la ventana | El menú se abrió y el `Popup` sigue; al hacer click afuera se cierra (`dismissOnClickOutside`). |
| Un `Popup` del menú contextual sobrevive a un cambio de configuración (rotación) | El estado del menú (`ContextMenuState`) vive en el `rememberSaveable` del shell, no en el composable de la fila. |
| El usuario arrastra el scrollbar del chat y suelta fuera de la ventana | `VerticalScrollbar` de androidx corta el arrastre al salir de la ventana; el chat queda en la posición soltada (no vuelve al origen). |
| El usuario toca "Enviar" con el campo vacío | No hace nada visible (no deshabilita el botón: TalkBack no descubre los deshabilitados — `student/10-accesibilidad` §6). |
| El usuario pulsa `Ctrl+C` con el composer con foco | Copia el texto **del campo** ✓ (no hay atajo global de copiar). |
| El usuario pulsa `Ctrl+C` con una burbuja con el foco | Copia **el mensaje** ✓ (atajo local de la burbuja). |
| El usuario pulsa `Ctrl+V` con una burbuja con el foco | **No** pega nada (el atajo local de la burbuja sólo maneja `Ctrl+C`); el `Ctrl+V` nativo no aplica porque el foco no está en un campo. Se documenta: para pegar, el foco tiene que estar en el composer. |
| El usuario pulsa `Esc` con el teclado virtual abierto | El teclado virtual se cierra (comportamiento del sistema) y el `Esc` no llega a la app. |

---

## 14. Criterios de aceptación

1. La app corre en una ventana de 1440×900 dp y en un teléfono de 360×800 dp
   **con el mismo código de `commonMain`** (salvo los `expect/actual` de
   plataforma listados en §12.2).
2. `rememberWindowSizeClass()` devuelve `COMPACT` por debajo de 600 dp,
   `MEDIUM` entre 600 y 839 dp y `EXPANDED` desde 840 dp, y **recalcula al
   redimensionar** la ventana.
3. En `COMPACT` el alumno tiene `NavigationBar` inferior; el docente tiene
   drawer; en `MEDIUM` y `EXPANDED` ambos tienen rail.
4. El menú contextual de una fila se abre con **botón derecho** en desktop y
   con **long-press** en Android, con el **mismo** componente y el mismo
   contenido.
5. El click derecho **no** dispara el click normal de la fila (el botón
   secundario se consume).
6. `Ctrl+C` con el composer con foco copia el texto del campo; con una
   burbuja con el foco, copia el mensaje. **Ningún** atajo global roba
   `Ctrl+C/V/X/A/Z`.
7. Con el teclado virtual abierto, el composer queda **completamente
   visible** y la lista de mensajes se encoge; al cerrar el teclado, el
   composer vuelve a su lugar **sin** dejar un hueco de 48 dp (la barra de
   navegación no se cuenta dos veces).
8. El chat de escritorio muestra scrollbar con **thumb de tamaño estable**; el
   de Android **no** muestra ninguno.
9. Todo control táctil mide ≥ 48 dp en Android y ≥ 40 dp en escritorio.
10. El diálogo de permisos de herramientas, en 360 dp de ancho, **no** corta
    ninguna etiqueta ni deja las opciones debajo del teclado.

---

## 15. Correcciones que este documento impone a otros

| Documento | Qué dice hoy | Qué hay que cambiar |
|---|---|---|
| `student/02-chat.md` §1 (línea 58) | *«Port literal. `androidx.compose.foundation.v2.ScrollbarAdapter` existe en CMP 1.7»*, en `commonMain` | **No compila para Android** (H2). Partir como en §7: `ScrollbarSpec` en common, `actual` en desktop y android. |
| `student/02-chat.md` §10.2 | `chat/ChatScrollbarAdapter.kt` en `commonMain` | Igual: el archivo se parte en `ChatScrollbarAdapter.kt` (common) + `Scrollbar.desktop.kt` / `Scrollbar.android.kt`. |
| `teacher/07-chats.md` §4.1 (línea 371) | *«Verificar que `rememberScrollbarAdapter` exista en `commonMain` de CMP 1.7.3; si no, … sin scrollbar explícito en Android»* | **Respondido**: no existe en `commonMain`. El scrollbar es de escritorio; en Android, nada (§7). |
| `teacher/01-navegacion.md` §6.1 | `BoxWithConstraints { val w = maxWidth; return remember(w) { … } }` | **No compila** (`return` no local desde un lambda no `inline`). Usar `ProvideWindowSize` + `CompositionLocalProvider` (§3.2). |
| `teacher/01-navegacion.md` §4.3 (línea 623) | *«Tamaño de ventana: `BoxWithConstraints` (common) · `rememberWindowState` (desktop) · `WindowSizeClass` de Activity (Android)»* | Correcto, pero se **precisa**: la clase se mide una vez en la raíz (`ProvideWindowSize`); `LocalWindowInfo.containerSize` sólo en `desktopMain` (§3.3) y `LocalConfiguration` no se usa. |
| `teacher/01-navegacion.md` §6.4 | «No hay bottom bar» (para el docente) | Se **generaliza**: la regla es "3–5 destinos ⇒ bottom bar; más ⇒ drawer". El docente (9) sigue con drawer; el alumno (4) gana bottom bar (§4.1). |
| `student/02-chat.md` §2.3 | `navigationBars.exclude(ime)` + `imePadding()` | Se **completa** con los insets horizontales (`safeDrawing.only(Horizontal)`) para el notch en landscape (§9.1). |
| `student/10-accesibilidad-i18n.md` §7.2 | El mapa de atajos | Se **confirma** y se le agrega la regla dura: el mapa global **no** contiene `Ctrl+C/V/X/A/Z` (§8.2). |

---

## 16. Decisiones abiertas

| # | Pregunta | Propuesta |
|---|---|---|
| A1 | ¿Haber un `NavigationBar` en desktop muy angosto (< 600 dp)? Hoy no: en `COMPACT` el desktop usa drawer, como Android. | Mantenerlo igual en las dos plataformas: menos código, menos sorpresas. |
| A2 | ¿Indicador de scroll propio en Android (3 dp que se desvanece)? | No en la v1: el indicador del sistema alcanza. La interfaz `ScrollbarSpec` ya lo permite (§7.3). |
| A3 | ¿El rail de íconos de `MEDIUM` muestra tooltip al pasar el mouse? | Sí, con `TooltipBox` + `onPointerEvent(Enter)` en desktop y long-press en Android. Es el "hover → toque" de §5. |
| A4 | ¿El alumno puede forzar el ancho (por ejemplo, "expandir la lista")? | No en la v1. La densidad se ajusta sola. |
| A5 | ¿La bottom bar del alumno muestra badge de avisos sin leer? | Sí, `NavigationBarItem` con badge; el número sale de `StudentHomeState` (`student/06`). |
