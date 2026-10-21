# 05 — La capa Android de Kitoru

> Subagente: *diseño de la app Android* (Activity, ciclo de vida, permisos,
> integraciones).
> Documento de diseño. **No** contiene código de producción: sólo firmas,
> nombres de archivos, XML y DSL de Gradle.
> Referencias leídas antes de escribir:
> `docs/design/CONTEXTO.md`,
> `ui/src/androidMain/kotlin/kitoru/ui/platform/FileSaver.android.kt` (el SAF ya implementado),
> `FunshiEngineAI/app-home/llama.cpp/examples/llama.android/app/src/main/java/com/example/llama/MainActivity.kt`,
> `FunshiEngineAI/app-home/llama.cpp/examples/llama.android/lib/src/main/java/com/arm/aichat/AiChat.kt`
> (+ `InferenceEngine.kt`),
> `docs/design/platform/01-local-android.md` (el motor local, ya diseñado),
> `docs/design/process/01-packaging-ci.md` (empaquetado, firma, versionado),
> `docs/design/sync/02-offline.md` (caché en dos niveles, `Outbox`, `NetworkMonitor`),
> `docs/design/teacher/01-navegacion.md` (navegación, breakpoints, pila manual),
> y el código real de `:core`, `:llm-api` y `:ui`.
>
> Reglas del proyecto que se respetan: *portable por defecto*, *fail-fast con
> mensajes en español*, *el estudiante conserva agencia*, *el docente no ve lo
> que no debe*.

---

## 0. Resumen ejecutivo (decisiones)

| # | Decisión | Motivo |
|---|---|---|
| **D1** | **La Activity es un `ComponentActivity`** (no `AppCompatActivity`), con `enableEdgeToEdge()` + `setContent { }` | No hay vistas de Android: todo es Compose. `AppCompatActivity` sólo agregaría dependencias que no se usan. `enableEdgeToEdge()` (activity 1.9) es obligatorio desde Android 15 (API 35), que dibuja las barras del sistema sobre la app. |
| **D2** | **El `KitoruViewModel` plano se envuelve en un `androidx.lifecycle.ViewModel`** (`KitoruStateHolder`) que vive en el `ViewModelStore` de la Activity | El VM plano sobrevive a la **rotación** y al **cambio de configuración**, y su `viewModelScope` se cancela solo en `onCleared()`. Es la única forma de que un chat en curso y la pila de navegación no se pierdan al recrear la Activity. |
| **D3** | **El `KitoruViewModel` recibe el `CoroutineScope` por constructor** (regla ya fijada en `teacher/01-navegacion.md` §5) | Hoy crea su propio `scope` con `Dispatchers.Main` adentro: eso impide testearlo y obliga a que el dispatcher sea inyectado. El cambio es chico y ya está pedido por otro documento. |
| **D4** | **`KitoruPaths` se inicializa en el `onCreate()` de la `Application`**, es idempotente y **falla rápido** si se usa antes | Es el primer código que corre del proceso de la app (antes que cualquier Activity). Un `object` global mutable con default `"."` escribiría en el working directory, que en Android es inescribible: el fallo aparece tarde y sin mensaje útil. |
| **D5** | **Dos carpetas de datos**: `filesDir/kitoru` (privada, con todo lo que es del usuario) y `getExternalFilesDir(null)/models` (sólo los GGUF) | `filesDir` se respalda con Auto Backup y cifra por usuario; `getExternalFilesDir` no cuenta contra la cuota de backup (25 MB) ni se hiberna. Un `.gguf` de 2,4 GB en `filesDir` rompe el backup. |
| **D6** | **Cero permisos de almacenamiento** | SAF (`ACTION_CREATE_DOCUMENT`) no pide ninguno; `getExternalFilesDir` es de la app; `MANAGE_EXTERNAL_STORAGE` está prohibido en Play y es innecesario. |
| **D7** | **Sólo un permiso runtime: `POST_NOTIFICATIONS`** (Android 13+), y **se pide contextualmente**, no en el primer arranque | Se pide cuando el usuario hace algo que lo necesita (descargar un modelo, activar la sincronización en segundo plano). Pedirlo al arrancar es la víaclassicade rechazo. |
| **D8** | **`WorkManager` para todo lo que corre en background** (descargas, sync de la `Outbox`, re-indexado, poda de caché). **Nada de `ForegroundService` en la v1** | La inferencia es *foreground por definición* (ya decidido en `01-local-android.md` §6.2). Un `ForegroundService` que retiene 2 GB de RAM en background lo mata Android igual. Para la descarga larga, `CoroutineWorker.setForeground()` usa el `SystemForegroundService` de WorkManager y alcanza. |
| **D9** | **El botón "atrás" se resuelve con una función pura en `commonMain`** (`resolveBack`) + **un solo `BackHandler` en la raíz** | Los `BackHandler` anidados se resuelven por orden de registro (gana el último): es la fuente clásica de bugs. Concentrar la decisión en una función testeable en `commonMain` la deja verifiable. |
| **D10** | **La pila de navegación global pasa a ser una pila real** (`mutableStateListOf<AppScreen>`) en el `KitoruViewModel` | Hoy `back()` calcula el padre con un `when` plano: no se puede volver de un sub-nivel al nivel anterior real, ni soporta overlays. La pila es lo que hace que "atrás" sea predecible. |
| **D11** | **La resolución de tamaño se hace con `BoxWithConstraints`** (COMPACT < 600 dp / MEDIUM < 840 dp / EXPANDED), **no** con `windowSizeClass` | `material3-window-size-class` no es multiplatform en Compose 1.7.3 (ya decidido en `teacher/01-navegacion.md` §6.1). `BoxWithConstraints` además da el tamaño **de la ventana**, que es lo correcto en pantalla partida. |
| **D12** | **No se bloquea la orientación**; se declara `configChanges` para evitar la recreación y, además, se usa un `ViewModel` | Belt-and-suspenders: `configChanges` evita el parpadeo y la pérdida del estado del `IME`; el `ViewModel` cubre el caso de que el sistema recree la Activity igual (o de que muera el proceso). |
| **D13** | **`applicationId = com.funshiengine.kitoru`**, versión desde `gradle.properties`, keystore **fuera del repo** | Coherente con `process/01-packaging-ci.md` §4. El `applicationId` no se cambia nunca después de la primera release: Android lo trata como otra app. |
| **D14** | **Splash con `androidx.core:core-splashscreen`** + `installSplashScreen()` antes de `setContent` | API 31+ tiene splash del sistema; en API < 31 hay que poner el mismo fondo en `windowBackground` para que no parpadee blanco. |

---

## 1. La Activity, el ViewModel y el ciclo de vida

### 1.1 El problema exacto

Hay tres restricciones que se cruzan:

1. `KitoruViewModel` (`:ui/commonMain`) es **plano**: `mutableStateOf` + un `CoroutineScope` propio. No es `androidx.lifecycle.ViewModel` porque ese tipo no existe en el target común.
2. En Android, la Activity **se recrea** al rotar, al cambiar el tema del sistema, al plegar un plegable, y puede **morir el proceso** en background.
3. `Dispatchers.Main` necesita `kotlinx-coroutines-android` (ya está en `androidMain` de `:core` y `:ui`) y `kotlinx-coroutines-swing` en desktop (también está).

Si el `KitoruViewModel` se crea con `remember { }` en el árbol de Compose, **se recrea con la Activity**: al rotar se pierde el chat en curso, la pila de navegación y el `scope` (que además se cancela, matando la inferencia a medio hacer).

### 1.2 Las tres opciones y por qué se descarta cada una

| Opción | Cómo funciona | Veredicto |
|---|---|---|
| **(a) `androidx.lifecycle.ViewModel` de Android** que envuelve al `KitoruViewModel` plano | El `ViewModel` de Android vive en el `ViewModelStore` de la Activity: sobrevive a la rotación, y su `viewModelScope` se cancela en `onCleared()` | ✅ **Elegida** |
| **(b) `KitoruViewModel` plano + `DisposableEffect` que cancela el scope** | `remember { KitoruViewModel(...) }` + `DisposableEffect(Unit) { onDispose { vm.close() } }` | ❌ Se pierde en la rotación (el `onDispose` corre y mata el scope). Funciona **sólo** si el VM vive en la `Application` (un singleton de proceso), y eso rompe el modelo de "un VM por instancia de pantalla": el estado de la pantalla anterior queda colgado en la app siguiente, y `onCleared()` deja de existir como señal de fin de vida. |
| **(c) `KitoruViewModel` plano + `rememberCoroutineScope()`** | El scope viene del composable | ❌ `rememberCoroutineScope` se cancela cuando el composable sale de la composición: misma pérdida que (b). |

**Por qué (a) y no un `ViewModel` de Android "puro" (sin el plano):** el `ViewModel` de Android no se puede instanciar en `commonMain`, y todo el dominio de la UI (`KitoruViewModel`, `ChatEngine`, `KitoruTheme`) vive ahí. La envoltura es de 20 líneas y deja el 100 % del código de UI en `commonMain`.

### 1.3 La envoltura: `KitoruStateHolder`

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/KitoruStateHolder.kt
package com.funshiengine.kitoru

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kitoru.ui.KitoruViewModel

/**
 * Dueño del [KitoruViewModel] en Android.
 *
 * Es un `ViewModel` de Android **finito**: existe mientras la Activity
 * existe, sobrevive a las recreaciones (rotación, cambio de tema) y
 * `onCleared()` cancela el `viewModelScope`.
 *
 * ¿Por qué no crear el `KitoruViewModel` con `remember { }` en el árbol de
 * Compose? Porque la Activity se recrea al rotar y `remember` no sobrevive:
 * se perdería la pila de navegación y el chat en curso.
 *
 * El `KitoruViewModel` plano (de `:ui/commonMain`) no sabe nada de Android:
 * recibe un `CoroutineScope` y un dispatcher por constructor (regla de
 * `teacher/01-navegacion.md` §5) y se testea en `commonTest` con `runTest`.
 */
class KitoruStateHolder(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    /** El VM de la UI. Se crea una sola vez por `ViewModelStore`. */
    val viewModel: KitoruViewModel by lazy {
        KitoruViewModel(
            courses = KitoruServices.courses,
            enrollments = KitoruServices.enrollments,
            profiles = KitoruServices.profiles,
            scope = viewModelScope,
            dispatcher = Dispatchers.Main.immediate,
        ).also { vm ->
            // Restaura la pila de navegación si el proceso murió (§4).
            vm.restoreSnapshot(savedState.get<UiSnapshot>(KEY_SNAPSHOT))
        }
    }

    /**
     * Guarda el estado de UI sobrevivible al proceso (§4.4).
     *
     * Lo llama la Activity en `onSaveInstanceState`, no el VM: el VM no
     * sabe de `SavedStateHandle`.
     */
    fun persistSnapshot(vm: KitoruViewModel) {
        savedState[KEY_SNAPSHOT] = vm.snapshot()
    }

    private companion object {
        const val KEY_SNAPSHOT = "kitoru.ui.snapshot"
    }
}
```

Y en la Activity:

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt
setContent {
    val holder: KitoruStateHolder = viewModel()   // de androidx.lifecycle.viewmodel.compose
    val vm = holder.viewModel
    ...
}
```

> `viewModel()` es de `androidx.lifecycle:lifecycle-viewmodel-compose` (Android
> únicamente) ⇒ va en `:android`, no en `:ui/commonMain`. La fábrica por
> defecto de `ComponentActivity` (`SavedStateViewModelFactory`) inyecta
> `Application` y `SavedStateHandle` sin que haya que escribir una
> `ViewModelProvider.Factory` a mano.

### 1.4 El cambio que hay que hacer en `:ui/commonMain`

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt (modificado)

class KitoruViewModel(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
    /** Scope de la app. En Android es `viewModelScope`; en desktop, uno propio. */
    private val scope: CoroutineScope,
    /** Dispatcher de las acciones de UI. Inyectado para que los tests sean deterministas. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    // ...
}
```

Y `dispatcher` se usa en vez de `Dispatchers.Main` en todos los `scope.launch`.
El `scope` **no** se crea adentro: hoy el código hace
`internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)`, que
hay que borrar.

> Impacto en el escritorio: el `main()` de `:desktop` va a tener que crear el
> scope (`CoroutineScope(SupervisorJob() + Dispatchers.Swing)`) y pasarlo. Es
> un cambio de dos líneas.

### 1.5 La pila de navegación real (preparado para el `BackHandler`)

El `screen` actual es **un valor**, no una pila. Eso no alcanza para el "atrás"
(§8). El cambio en `KitoruViewModel`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt (modificado)

class KitoruViewModel(...) {

    /**
     * Pila de navegación global.
     *
     * Antes `screen` era un valor suelto y `back()` recalculaba el padre con
     * un `when`: eso no permite volver de un sub-nivel al nivel anterior real
     * (de "Analítica" a "Editor de cátedra" y no a "Panel"), ni permite los
     * overlays. Con una pila, "atrás" es siempre "sacar el último".
     */
    private val stack = mutableStateListOf<AppScreen>(AppScreen.RolePicker)

    /** La pantalla visible: la cima de la pila. */
    var screen: AppScreen
        get() = stack.last()
        private set(value) { ... }   // sólo para el código viejo; preferible usar go()

    /** Cambia de rol: reinicia la pila con la home del rol. */
    fun goToRole(role: AppScreen) {
        stack.clear()
        stack.add(role)
        error = ""
    }

    /** Navega a una pantalla (apila). */
    fun go(target: AppScreen) {
        if (stack.lastOrNull() != target) stack.add(target)
        error = ""
    }

    /** Vuelve atrás. `false` si ya no queda nada: la app se cierra. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        error = ""
        return true
    }

    /** Reemplaza la pila entera (usado al restaurar un snapshot). */
    fun restoreStack(screens: List<AppScreen>) {
        stack.clear()
        stack.addAll(screens.ifEmpty { listOf(AppScreen.RolePicker) })
    }
}
```

> Nota: el subagente de navegación del docente (`teacher/01-navegacion.md` D4)
> define **una pila por sección** (`TeacherNavState`) *dentro* de la sección.
> Las dos pilas conviven: la global decide a qué sección se está y la de la
> sección decide el sub-nivel. El "atrás" los consulta en ese orden (§8.3).

### 1.6 El ciclo de vida de la Activity, punto por punto

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt
package com.funshiengine.kitoru

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1) El splash del sistema (API 31+) o el fondo de la app (API < 31).
        //    Tiene que ser lo PRIMERO: si se llama después de super.onCreate(),
        //    el splash del sistema ya se cerró.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 2) Barras del sistema transparentes (obligatorio en Android 15).
        enableEdgeToEdge()

        // 3) Las carpetas de datos y los singletons de proceso.
        //    Idempotente: si el proceso se recreó, ya está hecho.
        KitoruPathsInitializer.init(applicationContext)
        KitoruServices.init(applicationContext)

        // 4) Los canales de notificación (idempotente: createNotificationChannel
        //    no hace nada si el canal ya existe).
        NotificationChannels.ensure(this)

        // 5) El splash se mantiene hasta que la UI pueda pintar.
        splash.setKeepOnScreenCondition { !holder.viewModel.ready }

        setContent {
            val holder: KitoruStateHolder = viewModel()
            val vm = holder.viewModel
            val dark = rememberKitoruDarkTheme(vm.themeMode)
            KitoruTheme(dark = dark, accent = vm.accent) {
                KitoruApp(vm = vm)
            }
        }
    }

    /**
     * El sistema puede recrear la Activity (rotación, tema, multi-ventana).
     * Acá se guarda lo que tiene que sobrevivir a la muerte del proceso.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // El snapshot va por SavedStateHandle: el sistema lo serializa en el
        // bundle de la Activity y lo restaura aunque el proceso muera.
        // (Ver §4.4.)
    }

    override fun onStop() {
        super.onStop()
        // La app dejó de estar visible. NO se cancela el `viewModelScope`:
        // el `ViewModel` sigue vivo (la Activity no se destruye, sólo se
        // pausa) y la respuesta del chat sigue llegando.
        // Lo que sí se hace es avisarle al motor local (01-local-android §6.2).
    }
}
```

**Qué NO va en el `onCreate`:**

- **No** se crea el `KitoruViewModel` a mano (lo hace `viewModel()`).
- **No** se lanza la sincronización con un `launch` suelto: eso es trabajo de
  `WorkManager` (§7).
- **No** se pide ningún permiso en el arranque (§5.3).

### 1.7 Los insets y el teclado

Con `enableEdgeToEdge()`, el contenido dibuja **debajo** de las barras del
sistema y del teclado. En `commonMain` no hay `WindowInsets` de Android: hace
falta una costura.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/Insets.kt (nuevo)
package kitoru.ui.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Insets del sistema, en términos portables.
 *
 * En Android con `enableEdgeToEdge()` el contenido va debajo de las barras
 * del sistema y del teclado: sin esto, la barra de composing del chat queda
 * tapada por el teclado.
 */
@Composable
expect fun rememberImePadding(): Dp
```

- `androidMain`: `WindowInsets.ime.getBottom(LocalDensity.current).toDp()`
- `desktopMain`: `0.dp`

Y el `Scaffold` de la app aplica `Modifier.imePadding()` en el `content` y
`Modifier.statusBarsPadding()` en la `TopAppBar`.

### 1.8 `onSaveInstanceState` y `SavedStateHandle`: qué se guarda

| Dato | ¿Dónde? | ¿Por qué? |
|---|---|---|
| Pila de navegación (`List<AppScreen>`) | `SavedStateHandle` | Sobrevive a la muerte del proceso |
| Cátedra activa (`activeCourseId`) | `SavedStateHandle` + `app-config.json` | Idem |
| Modo (alumno/docente) | `app-config.json` | Es una preferencia, no un estado de UI |
| Texto del composer (borrador) | `SavedStateHandle` | El usuario no lo pierde al rotar |
| Chat en curso | `app-config.json` (ver §4.6) | Tiene que sobrevivir al proceso |
| Ajustes (tema, acento) | `app-config.json` | Preferencias |

El `SavedStateHandle` es **pequeño por diseño**: Android serializa el bundle en
disco y hay un límite (~500 KB de Binder, pero el `savedInstanceState` real es
mucho más chico). Sólo van ahí identificadores, nunca textos largos.

---

## 2. La carpeta de datos

### 2.1 El mapa completo en Android

```
/data/data/com.funshiengine.kitoru/                    ← context.filesDir (privada, cifrada por usuario)
├── kitoru/                                           ← KitoruPaths.dataDir
│   ├── config/
│   │   ├── app-config.json                            ← perfil, modo, tema, acento, última cátedra
│   │   ├── courses.json
│   │   ├── enrollments.json
│   │   ├── materials.json
│   │   ├── threads.json
│   │   ├── messages.json
│   │   ├── profiles.json
│   │   ├── device.json                                ← id del dispositivo, identidad local
│   │   └── credentials.bin                            ← API key cifrada con la Keystore (§2.6)
│   ├── courses/<courseId>/
│   │   ├── materials/<materialId>.txt                ← texto extraído de los materiales
│   │   ├── index/                                    ← índice BM25 serializado
│   │   ├── threads/<threadId>/messages.jsonl          ← mensajes del hilo
│   │   └── outputs/                                   ← artefactos de las herramientas
│   └── outbox/
│       └── outbox.json                                ← la cola de envíos (NO es caché)
│
├── cache/                                             ← context.cacheDir (descartable por el SO)
│   └── kitoru/
│       ├── corpus/                                    ← réplicas de lectura (anuncios, tareas)
│       ├── thumbs/
│       └── download/                                  ← .part de descargas en curso
│
└── noBackupFilesDir/                                  ← lo que NO se respalda
    └── kitoru/trace.log                                ← log técnico (opcional)

/storage/emulated/0/Android/data/com.funshiengine.kitoru/   ← context.getExternalFilesDir(null)
└── models/                                            ← los .gguf (2,4 GB)
    └── Phi-4-mini-instruct-Q4_K_M.gguf
```

### 2.2 Decisión por tipo de dato

| Tipo de dato | Carpeta | Por qué | ¿Se respalda? | ¿El SO lo borra? |
|---|---|---|---|---|
| **Perfil, config, cátedras, inscripciones** | `filesDir/kitoru/config` | Es dato del usuario, chico, y tiene que viajar con el alumno | ✅ Sí | No |
| **Mensajes de los chats** | `filesDir/kitoru/courses/<id>/threads/<id>/messages.jsonl` | Es lo más valioso del alumno | ✅ Sí | No |
| **Materiales extraídos** | `filesDir/kitoru/courses/<id>/materials/` | Caché del corpus, pero re-derivable; igual va acá para poder worked offline | ✅ Sí | No |
| **Índice BM25** | `filesDir/kitoru/courses/<id>/index/` | Se reconstruye en 2 s; pero conservarlo evita re-indexar al abrir | ✅ Sí | No |
| **Artefactos de herramientas** (`CreateFileTool`) | `filesDir/kitoru/courses/<id>/outputs/` | El usuario los puede querer conservar y exportar | ✅ Sí | No |
| **`Outbox` (cola de envíos)** | `filesDir/kitoru/outbox/` | **No es caché**: es un compromiso con el usuario. Si viviera en `cache/`, el SO la tiraría. | ✅ Sí | No |
| **API key / credenciales** | `filesDir/kitoru/config/credentials.bin`, cifrado con la Keystore | Un JSON plano se copia en el backup de Google | ⚠️ **Excluida del backup** (§12.5) | No |
| **Réplicas de lectura** (anuncios, tareas, chats ajenos) | `cacheDir/kitoru/corpus/` | Reproducibles: se vuelven a pedir | ❌ No | **Sí** (a discreción del SO) |
| **Thumbnails / previews** | `cacheDir/kitoru/thumbs/` | Derivados | ❌ No | Sí |
| **`.part` de descargas** | `cacheDir/kitoru/download/` | Se tira y se re-descarga | ❌ No | Sí |
| **Modelos GGUF** | `getExternalFilesDir(null)/models/` | 2,4 GB: en `filesDir` rompe la cuota de Auto Backup (25 MB) y compite con el almacenamiento interno | ❌ No | No (salvo desinstalar) |
| **Exportaciones del usuario** | **SAF**: `ACTION_CREATE_DOCUMENT` | El usuario elige dónde. Sin permisos. | — | — |
| **Importación de un `.gguf` propio** | SAF `OpenDocument` → se copia a `getExternalFilesDir/models/` | Es el camino "traé tu propio modelo" (el `ensureModelFile` del ejemplo `llama.android`) | ❌ No | No |
| **Log técnico** | `noBackupFilesDir/kitoru/` | Nunca interesa respaldarlo | ❌ No | No |

### 2.3 Por qué el modelo NO va en `filesDir`

Tres razones concretas, en orden de peso:

1. **La cuota de Auto Backup.** `android:allowBackup="true"` respalda `filesDir`,
   `sharedPrefs` y `database`, con un tope de **25 MB por app** (60 MB en
   device-to-device). Un GGUF de 1,1–2,4 GB en `filesDir` hace que el backup
   **falle entero**: no se respalda ni el chat.
2. **La hibernación de apps (Android 12+).** A las apps sin uso se les borra la
   caché y se les revocan permisos. `getExternalFilesDir` está fuera de ese
   ciclo de vida.
3. **El espacio interno.** En muchos teléfonos el almacenamiento interno
   tiene 2 GB de cuota para las apps y el externo 64–256 GB.

**El costo de `getExternalFilesDir`:** la SD puede desmontarse
(`getExternalFilesDirs` devuelve varias). Por eso:

```kotlin
// El directorio de modelos se recalcula en cada arranque y se valida
// antes de cargar el motor.
val modelsDir = context.getExternalFilesDir(null)
    ?: context.filesDir.resolve("models")   // fallback: interno (no alcanza para 2,4 GB,
                                            // pero la app sigue funcionando)
```

Y en `LocalModelService.start()`: si el directorio externo **no está montado**
(`!dir.exists()`), el mensaje al alumno es explícito:
*"El almacenamiento externo está desmontado. Volvé a conectar la tarjeta SD o usá el modelo del servidor."*

### 2.4 Las exportaciones: SAF (ya implementado)

`ui/src/androidMain/kotlin/kitoru/ui/platform/FileSaver.android.kt` ya resuelve
la exportación con `ActivityResultContracts.CreateDocument("text/plain")` +
`ContentResolver.openOutputStream`. Es la pieza correcta y **no se toca**.

Dos mejoras menores que conviene hacer, sin cambiar la costura `FileSaver`:

1. **El MIME type es fijo** en `"text/plain"`. `CreateDocument` acepta el tipo
   como argumento del contrato, pero el contrato se crea una sola vez en el
   `remember`. Para suportar `.csv` / `.md` / `.json` hay que registrar
   **tres** lanzadores (uno por tipo) y elegir el correcto en `save()`, o usar
   `ActivityResultContracts.CreateDocument("*/*")` y pasar el tipo real por
   `Intent`. La opción simple: registrar el lanzador con `"*/*"` y setear el
   tipo del `Intent` a mano antes de `launch()`.

2. **Falta la persistencia del permiso de escritura** (que es lo que permite
   reescribir el mismo archivo después). `ACTION_CREATE_DOCUMENT` no da
   permiso persistente (a diferencia de `ACTION_OPEN_DOCUMENT_TREE` con
   `takePersistableUriPermission`). Para Kitoru no hace falta: cada exportación
   vuelve a pedir la ubicación.

### 2.5 La caché: qué se cachea y con qué política

La tabla de recursos y las políticas de invalidación ya están en
`sync/02-offline.md` §2. Lo propio de Android:

- La caché vive en `context.cacheDir`, que **el SO puede borrar cuando le
  falta espacio**. La app tiene que asumir que en cualquier momento puede
  desaparecer ⇒ **la caché nunca es la única copia** de nada importante.
- `context.cacheDir` **no** se respalda. Correcto: es caché.
- La poda de caché la hace un `WorkManager` periódico (§7.2), con una regla
  LRU por `lastAccess` (mismo criterio que `sync/02-offline.md` §8.3).

### 2.6 La API key: Keystore de Android

`Course.modelPolicy.apiKey` es una cadena en un JSON. En Android eso es un
problema: el JSON está en `filesDir`, que se respalda a Google.

**Decisión: la API key no se guarda en el JSON.** Se guarda cifrada con una
clave AES-GCM de la **Android Keystore**, que no sale del dispositivo.

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/security/CredentialStore.kt
package com.funshiengine.kitoru.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda las credenciales (API key) cifradas con una clave de la Keystore.
 *
 * La clave AES **no sale del dispositivo**: el SO la genera en el TEE/StrongBox
 * y sólo la usa para cifrar. Lo que se guarda en disco es el ciphertext.
 *
 * La API key **no** va en el `.json` de `config/`: ese archivo sí se respalda.
 */
class CredentialStore(private val file: java.io.File) {

    private val keyAlias = "kitoru.credentials.v1"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    fun put(name: String, value: String) { /* cifra + escribe file */ }
    fun get(name: String): String? { /* descifra o null */ }
    fun remove(name: String) { /* borra la entrada */ }
}
```

> `androidx.security:security-crypto` (`EncryptedSharedPreferences`) es la
> versión corta, pero la biblioteca está **deprecada** desde 2025: la
> recomendación de Android es usar la Keystore directamente, como arriba.
> La API key del alumno **no** se sube nunca al servidor de la cátedra.

---

## 3. `KitoruPaths`: dónde y cuándo se inicializa

### 3.1 El problema con el `object` actual

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt (hoy)
object KitoruPaths {
    var dataDir: FilePath = FilePath(".")   // ← el default es "el directorio actual"
        private set
    fun setDataDir(path: FilePath) { dataDir = path }
    val configDir: FilePath get() = dataDir.resolve("config")
    ...
}
```

Dos problemas, en Android:

1. **El default `"."` es un fallo silencioso.** En Android el working directory
   del proceso es `/` (o `/data/user/0/<paquete>` según la versión). Escribir
   ahí falla con `EACCES` en un mensaje que no dice nada de Kitoru. Peor:
   `FilePath(".").resolve("config")` da `"./config"`, y `File("./config")` se
   resuelve **contra el cwd**, que en Android puede ser la raíz del sistema de
   archivos: el error es "Permission denied" en `/config`.
2. **No hay forma de saber si se inicializó.** `dataDir` siempre tiene valor.

### 3.2 La forma nueva: fail-fast + idempotencia

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt (modificado)
package kitoru.platform

/**
 * Raíz de datos de la aplicación.
 *
 * Cada plataforma la fija al arrancar:
 *  - Desktop: `<user.home>/.kitoru`
 *  - Android: `context.filesDir/kitoru` (privada) + `context.cacheDir/kitoru`
 *    (caché) + `context.getExternalFilesDir(null)/models` (modelos).
 *
 * **No tiene valor por defecto.** Si se usa antes de inicializar, revienta
 * con un mensaje que dice qué hacer: un default silencioso escribiría en el
 * working directory, que en Android es inescribible y produce un error
 * incomprensible a kilómetros del problema.
 */
object KitoruPaths {

    /** Las tres raíces. `null` = todavía no se inicializó. */
    private var roots: Roots? = null

    private data class Roots(
        val data: FilePath,
        val cache: FilePath,
        val outbox: FilePath,
        val models: FilePath,
    )

    /** ¿Ya se inicializó? */
    val isInitialized: Boolean get() = roots != null

    /** Carpeta de datos (privada). */
    val dataDir: FilePath get() = requireRoots("dataDir").data

    /** Carpeta de caché (descartable). */
    val cacheDir: FilePath get() = requireRoots("cacheDir").cache

    /** Carpeta de la cola de envíos. NO es caché: no se descarta. */
    val outboxDir: FilePath get() = requireRoots("outboxDir").outbox

    /** Carpeta de los modelos locales (`.gguf`). */
    val modelsDir: FilePath get() = requireRoots("modelsDir").models

    /** Carpeta de configuración. */
    val configDir: FilePath get() = dataDir.resolve("config")

    /** Carpeta de datos de las cátedras (materiales, índice, chats). */
    val coursesDir: FilePath get() = dataDir.resolve("courses")

    /**
     * Fija las carpetas. Lo hace la app al arrancar.
     *
     * Es idempotente: llamarla dos veces con los mismos valores no hace
     * nada; llamarla con valores distintos **sí** cambia (así el escritorio
     * puede cambiar la carpeta en "Ajustes").
     */
    fun setDataDir(
        path: FilePath,
        cache: FilePath = path.resolve("cache"),
        outbox: FilePath = path.resolve("outbox"),
        models: FilePath = path.resolve("models"),
    ) {
        roots = Roots(path, cache, outbox, models)
    }

    private fun requireRoots(what: String): Roots = roots ?: error(
        "KitoruPaths no está inicializado (se pidió $what). " +
            "Llamá a KitoruPaths.setDataDir(...) al arrancar la app: " +
            "en Android, en el onCreate() de la Application; " +
            "en escritorio, en el main() antes de abrir la ventana."
    )
}
```

> El default `FilePath(".")` desaparece. La única consecuencia: **todo** el
> código que use `KitoruPaths` tiene que inicializarlo antes. Es exactamente
> lo que queremos (fail-fast, regla 4 del CONTEXTO).

### 3.3 Dónde se inicializa: `Application`, no `Activity`

| Opción | ¿Sirve? | Por qué |
|---|---|---|
| **`Application.onCreate()`** | ✅ **Elegida** | Es el primer código del proceso de la app. Corre **antes** que cualquier Activity, que cualquier `Service` y que cualquier `Worker`. Es el único punto que garantiza que `KitoruPaths` esté listo para todos. |
| `Activity.onCreate()` | ❌ | Los `Worker` de `WorkManager` pueden correr **sin** que haya ninguna Activity viva (la app se cerró, el sistema reanuda el trabajo). El `Worker` se encontraría con `KitoruPaths` sin inicializar. |
| `ContentProvider.onCreate()` | ⚠️ Trampa | Los `ContentProvider` se crean **antes** que `Application.onCreate()`. Es el punto donde se inicializa `WorkManager` (`androidx.startup`). Si el `Provider` tocara `KitoruPaths`, explotaría. **Por eso el `Worker` vuelve a llamar al inicializador** (§3.5). |
| `MainActivity.onCreate()` | ❌ | Igual que la Activity: demasiado tarde para los workers. |

### 3.4 El inicializador

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/KitoruPathsInitializer.kt
package com.funshiengine.kitoru

import android.content.Context
import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths

/**
 * Fija las carpetas de Kitoru a partir del `Context`.
 *
 * **Idempotente**: se puede llamar desde `Application.onCreate()`, desde un
 * `Worker`, desde un `Service` o desde la `Activity`, sin efectos raros.
 *
 * Existe como función (y no como código suelto en la `Application`) porque
 * **el orden real de arranque de Android no es el que uno espera**: los
 * `ContentProvider` se crean antes que `Application.onCreate()`, y un
 * `Worker` de WorkManager puede arrancar el proceso sin que ninguna Activity
 * exista jamás.
 */
object KitoruPathsInitializer {

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val data = FilePath(app.filesDir.absolutePath).resolve("kitoru")
            val cache = FilePath(app.cacheDir.absolutePath).resolve("kitoru")
            // El directorio de modelos vive afuera (ver §2.3). Si no hay
            // almacenamiento externo montado, cae adentro: la app sigue
            // funcionando, pero el modelo no entra.
            val external = app.getExternalFilesDir(null)
            val models = (external?.let { FilePath(it.absolutePath).resolve("models") }
                ?: data.resolve("models"))

            KitoruPaths.setDataDir(
                path = data,
                cache = cache,
                outbox = data.resolve("outbox"),
                models = models,
            )
            initialized = true
        }
    }
}
```

### 3.5 El orden real de arranque de Android (y por qué importa)

```
1. Application.attachBaseContext(context)
2. ContentProvider.onCreate()          ← ¡aquí se inicializa WorkManager!
3. Application.onCreate()               ← ← ← KitoruPaths se fija ACÁ
4. Activity.onCreate()  /  Service.onCreate()  /  Worker.doWork()
```

El paso 2 es el problema: `androidx.startup.InitializationProvider` (y el
`WorkManagerInitializer`) se ejecutan en el paso 2, **antes** del paso 3. Si
alguno de esos tocara `KitoruPaths`, reventaría.

**Regla:** `KitoruPaths` **sólo** se toca a partir del paso 3. Para que un
`Worker` que arranca el proceso desde cero tenga las carpetas listas, el
propio `Worker` llama al inicializador:

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/work/KitoruWorker.kt
package com.funshiengine.kitoru.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.funshiengine.kitoru.KitoruPathsInitializer
import com.funshiengine.kitoru.KitoruServices

/**
 * Base de los workers de Kitoru.
 *
 * Un `Worker` puede arrancar el proceso **sin** que haya ninguna Activity
 * viva (el sistema reanuda trabajo pendiente después de que el usuario
 * cerró la app). Por eso el worker inicializa las carpetas y los servicios
 * antes de hacer nada.
 */
abstract class KitoruWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    init {
        KitoruPathsInitializer.init(context.applicationContext)
        KitoruServices.init(context.applicationContext)
    }
}
```

### 3.6 Qué pasa si se usa antes de inicializar

Con el diseño de §3.2, **no hay un "si se usa antes"**: hay un **fallo
inmediato y explicito**:

```
java.lang.IllegalStateException: KitoruPaths no está inicializado (se pidió dataDir).
Llamá a KitoruPaths.setDataDir(...) al arrancar la app: en Android, en el onCreate()
de la Application; en escritorio, en el main() antes de abrir la ventana.
```

Eso es **mucho** mejor que el fallo actual (`FileNotFoundException: /config/...`
o un `EACCES` en la raíz del sistema de archivos), porque el mensaje dice
exactamente qué hacer.

**Además, un test que lo cubre:**

```kotlin
// core/src/commonTest/kotlin/kitoru/platform/KitoruPathsTest.kt
class KitoruPathsTest {
    @Test
    fun `usar KitoruPaths antes de inicializar da un error explicito`() {
        val ex = assertFailsWith<IllegalStateException> { KitoruPaths.dataDir }
        assertTrue(ex.message!!.contains("no está inicializado"))
    }
}
```

> El test necesita poder resetear el estado. Para eso, `KitoruPaths` lleva un
> `internal fun resetForTests()` que sólo existe en el source set de test
> (o con `@VisibleForTesting`).

---

## 4. Multi-ventana y muerte del proceso

### 4.1 Los tres eventos que hay que resilientar

| Evento | Qué pasa | Consecuencia |
|---|---|---|
| **Rotación / cambio de configuración** | La Activity se recrea (`onDestroy` + `onCreate`) | Se pierde todo lo que sólo viva en la Activity |
| **Multi-ventana (split screen, dos instancias)** | Puede haber **dos Activities** de la app vivas al mismo tiempo | Dos escritores sobre los mismos JSON |
| **Muerte del proceso en background** | Android mata el proceso; en la vuelta, se recrea desde cero | Se pierde todo lo que esté en memoria |

### 4.2 Multi-ventana: dos Activity, un solo archivo

El caso: el usuario abre Kitoru desde el launcher mientras ya hay una instancia
en pantalla partida. Con `android:launchMode="standard"` (el default) se crea
una **segunda Activity** en otra task. Resultado: **dos `KitoruStateHolder`**
⇒ **dos `KitoruViewModel`** ⇒ dos flujos de escritura sobre
`courses.json` / `messages.json`.

El `JsonListStore` escribe de forma atómica (temp + rename, según
`sync/02-offline.md` §4.3), así que no se corrompe el archivo: se **pierde una
escritura** (último que escribe gana). Eso es aceptable para un chat, pero
igual hay que mitigarlo.

**Mitigaciones (las tres):**

1. **`android:launchMode="singleTask"`** en la Activity: una sola instancia por
   app; el segundo toque del launcher trae la existente al frente
   (`onNewIntent`). Es lo correcto para una app de un solo usuario.
   Alternativa menos agresiva: `singleTop` + `FLAG_ACTIVITY_MULTIPLE_TASK`
   controlado. Se elige **`singleTask`**.
2. **Escrituras serializadas con un mutex de proceso.** Los `JsonListStore`
   son **singletons de proceso** (viven en `KitoruServices`, §1.3) ⇒ todas las
   instancias de la app comparten el mismo `Mutex` ⇒ no se intercalan escrituras.
   (Dos *procesos* distintos no comparten el mutex, pero Kitoru corre en un
   solo proceso: no hay `:remote` ni `android:process` en el manifest.)
3. **El `Outbox` es la única fuente de verdad de lo que hay que mandar**: si
   dos instancias escriben, la cola acumula y el `SyncWorker` manda todo, en
   orden, con idempotencia (ya diseñado en `sync/02-offline.md` §4.7).

### 4.3 Qué se pierde exactamente cuando muere el proceso

| Dato | ¿Se pierde? | ¿Por qué? |
|---|---|---|
| Perfil, cátedras, inscripciones, materiales | **No** | Están en `filesDir/kitoru/config/*.json` |
| Historial de chats | **No** | `filesDir/kitoru/courses/*/threads/*/messages.jsonl` |
| Configuración (tema, acento, modo, modelo) | **No** | `app-config.json` |
| Cola de envíos | **No** | `outbox/outbox.json` |
| Materiales extraídos e índice | **No** | `courses/<id>/materials/` y `courses/<id>/index/` |
| **Texto parcial de la respuesta en curso** | **Sí** | Vive en memoria mientras se transmite |
| **Estado de la generación en curso** | **Sí** | Idem |
| **Pila de navegación** | **No**, si se usa `SavedStateHandle` | El sistema lo serializa en el bundle |
| **Borrador del composer** | **No**, si se usa `SavedStateHandle` | Idem |
| Modelo local cargado en RAM | **Sí** | Se recarga (3 s) |
| **Índice BM25 en memoria** | **Sí** | Se reconstruye (2 s) |

### 4.4 Los dos niveles de persistencia

| Nivel | Mecanismo | Qué guarda | Cuándo se escribe | Sobrevive a |
|---|---|---|---|---|
| **A — UI transitoria** | `SavedStateHandle` (→ `Bundle` de la Activity → `savedInstanceState`) | Pila de navegación, cátedra activa, borrador del composer, pestaña abierta, scroll | `onSaveInstanceState` | Recreación de la Activity **y** muerte del proceso. **No** sobrevive a desinstalar |
| **B — Durable** | `app-config.json` + repositorios (`JsonListStore`) | Perfil, modo, tema, acento, modelo configurado, cátedras, chats, outbox | Al cambiar (escritura atómica temp+rename) | Todo, hasta que se desinstale |

**Regla de reparto:** lo que el usuario **no puede volver a escribir** (su
perfil, sus chats, lo que le mandaron) va al nivel B. Lo que es
"dónde estaba mirando" va al nivel A.

### 4.5 El snapshot de UI

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt (nuevo, en el mismo archivo o aparte)

/**
 * Estado de UI que se puede guardar y restaurar.
 *
 * Vive en `commonMain` para que el mismo snapshot sirva para el `Bundle` de
 * Android y para el archivo de sesión del escritorio.
 */
@Serializable
data class UiSnapshot(
    /** La pila de navegación completa (no sólo la pantalla actual). */
    val stack: List<String> = emptyList(),
    /** La cátedra abierta. */
    val activeCourseId: String? = null,
    /** El borrador del composer del chat. */
    val draft: String = "",
    /** La sección abierta, si la navegación del docente tiene sub-niveles. */
    val section: String? = null,
)
```

Y los métodos en el VM:

```kotlin
/** Toma el snapshot del estado actual. */
fun snapshot(): UiSnapshot = UiSnapshot(
    stack = stack.map { it::class.simpleName.orEmpty() },  // ← ver nota
    activeCourseId = activeCourseId,
    draft = draft,
    section = currentSection,
)

/** Restaura un snapshot (o `null` si no hay). */
fun restoreSnapshot(s: UiSnapshot?) {
    if (s == null) return
    restoreStack(s.stack.mapNotNull { screenByName(it) })
    activeCourseId = s.activeCourseId
    draft = s.draft
    currentSection = s.section
}
```

> **Nota sobre la serialización de la pila:** `AppScreen` es un `sealed class`
> con `data object` y `data class`. Kotlinx-serialization no serializa
> `sealed class` con objetos `data object` sin `@Serializable`. Dos opciones:
> (a) anotar `AppScreen` con `@Serializable` y darle `@SerialName` a cada
> rama (recomendado: es un tipo de dominio de la UI y serializarlo es útil
> también para el escritorio); o (b) mapear a `String` a mano con un `when`
> explícito (más verboso pero sin tocar el tipo). **Se elige (a)**: se anota
> `AppScreen` con `@Serializable` y `@SerialName("StudentChat")` en cada rama,
> y `StudentChat`/`TeacherCourse`/etc. llevan sus campos serializados.

El `SavedStateHandle` necesita que el valor sea `Bundle`-serializable: un
`data class` con `@Serializable` **no** lo es. Dos caminos:

- **A)** Guardar el snapshot como `String` (JSON con `Json.encodeToString`) en
  el `SavedStateHandle`. Simple, portable y ≤ 1 KB. **Se elige este.**
- B) `@Parcelize` (requiere el plugin `kotlin-parcelize`, que es Android-only
  ⇒ no sirve para el `commonMain`).

```kotlin
// En KitoruStateHolder
fun persistSnapshot(vm: KitoruViewModel) {
    savedState[KEY_SNAPSHOT] = Json.encodeToString(UiSnapshot.serializer(), vm.snapshot())
}
```

### 4.6 El chat en curso

**El problema concreto:** el alumno le pregunta algo al modelo, la respuesta
llega token a token, y en medio del stream el proceso muere.

**Decisión: el mensaje del asistente se persiste sólo cuando está completo.**

| Momento | Qué hay en disco | Qué ve el alumno al volver |
|---|---|---|
| Antes de enviar | El mensaje del usuario ya está persistido (`status = "pendiente"`) | — |
| Durante el stream | Nada nuevo en disco | — |
| Proceso muere | El mensaje del usuario queda con `status = "pendiente"` y sin respuesta | *"La respuesta quedó incompleta: la app se cerró mientras el modelo respondía."* + botón **"Reintentar"** |
| El stream termina | Se persiste el mensaje del asistente con `status = "completa"` + `latencyMs` + `tokensOut` + citas | Normal |

Por qué **no** persistir chunks parciales:

1. Un mensaje a medias en `messages.json` **contamina la analítica del docente**
   (las citas verificadas de una respuesta truncada son ruido).
2. El `CitationVerifier` necesita el texto completo para verificar las citas.
3. Escribir en cada token (4–15 tokens/s) somete la flash a una escritura
   constante: desgaste innecesario.

**Estado del mensaje** (extensión de `ChatMessage` en `:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (modificado)
@Serializable
enum class MessageStatus {
    /** Enviado por el alumno; esperando la respuesta. */
    PENDIENTE,
    /** La respuesta llegó completa y fue verificada. */
    COMPLETA,
    /** El proceso se murió (o el usuario canceló) antes de que terminara. */
    INTERRUMPIDA,
}
```

**Antes de inferir, el mensaje del usuario se persiste.** Es la regla de oro
de este diseño: *si no está en disco, no existe*.

**Al volver, la UI muestra el marcador.** En `:ui/commonMain`:

```
┌───────────────────────────────────────────────┐
│  ¿Qué es el sistema de archivos?               │
│                                                │
│  ⚠ La respuesta quedó incompleta.              │
│    La app se cerró mientras el modelo           │
│    respondía.                                  │
│                                                │
│  [ Reintentar ]   [ Descartar ]                │
└───────────────────────────────────────────────┘
```

El botón "Reintentar" vuelve a lanzar la misma pregunta con el mismo contexto.

### 4.7 La configuración

`app-config.json` (diseñado en `process/01-packaging-ci.md` §6.1) ya cubre lo
durable. Lo que hay que **agregar** para Android:

| Campo nuevo | Para qué |
|---|---|
| `theme: "system" \| "light" \| "dark"` | El modo oscuro (§9) |
| `dynamicColor: Boolean` | Color dinámico de Material 3 (§9.3) |
| `notificationsEnabled: Boolean` | Si el usuario apagó las notificaciones desde la app |
| `lastCourseId: String?` | Reabrir la cátedra |
| `uiMode: "ALUMNO" \| "DOCENTE"` | Con qué rol se entra |
| `pendingSyncAt: Long?` | Cuándo se vació la `Outbox` por última vez |
| `schemaVersion: Int` | Migraciones (§6 de packaging) |

**Todo esto se escribe en `filesDir`**, que se respalda: el alumno cambia de
teléfono y conserva su configuración.

### 4.8 Resumen: qué sobrevive a qué

| Evento | Config | Chats | Chat en curso | Pila de nav | Índice |
|---|---|---|---|---|---|
| Rotación | ✅ | ✅ | ✅ (sigue en RAM) | ✅ | ✅ |
| App al background | ✅ | ✅ | ✅ (sigue en RAM) | ✅ | ✅ |
| Proceso muerto | ✅ | ✅ | ❌ → "interrumpida" | ✅ (`SavedStateHandle`) | ❌ → se reconstruye |
| Desinstalación | ❌ | ❌ | ❌ | ❌ | ❌ |

---

## 5. Los permisos

### 5.1 La lista completa

```xml
<!-- android/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- ============ Permisos normales (no se pide nada al usuario) ========= -->

    <!-- La app habla con el servidor de la cátedra y con Hugging Face. -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Para saber si hay red (ConnectivityManager) y para el heartbeat. -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- WorkManager: reanuda trabajo pendiente después de un reinicio. -->
    <!-- (Lo declara la propia biblioteca; se explicita por claridad.) -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- WorkManager usa WakeLock internamente. -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- ============ Permiso runtime (Android 13+) ========= -->
    <!-- Se pide contextualmente, NO al arrancar (§5.3). -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- ============ Permisos con condición ========= -->
    <!--
      Sólo hacen falta si se implementa la inferencia local con FGS.
      En la v1 NO se usan (§7.3): se dejan comentados.
    -->
    <!-- <uses-permission android:name="android.permission.FOREGROUND_SERVICE" /> -->
    <!-- <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" /> -->

    <!--
      NUNCA:
      - READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE  → SAF no los necesita
      - MANAGE_EXTERNAL_STORAGE                            → prohibido en Play
      - READ_MEDIA_*                                      → no hay fotos ni videos
      - CAMERA / RECORD_AUDIO / ACCESS_FINE_LOCATION      → no hay nada que grabar
      - SCHEDULE_EXACT_ALARM                              → no hay alarmas
    -->

    <application ... />
</manifest>
```

### 5.2 Por qué ningún permiso de almacenamiento

| Mecanismo | ¿Permiso? | Nota |
|---|---|---|
| SAF `ACTION_CREATE_DOCUMENT` | **Ninguno** | El usuario elige el archivo; la app escribe con `ContentResolver` |
| SAF `ACTION_OPEN_DOCUMENT` | **Ninguno** | Lectura del `.gguf` que el usuario eligió |
| `getExternalFilesDir(null)` | **Ninguno** | Es el directorio **de la app**: el sistema lo crea y lo borra al desinstalar |
| `filesDir` / `cacheDir` | **Ninguno** | Siempre fueron sin permiso |
| `MANAGE_EXTERNAL_STORAGE` | **Sí, pero** | "All files access": prohibido en Google Play desde 2021 y **innecesario** |

Además, desde Android 10 (API 29) `WRITE_EXTERNAL_STORAGE` ya no existe como
permiso funcional, y desde Android 11 el acceso al almacenamiento compartido
es **por archivo** (MediaStore), no por permiso. Pedir "almacenamiento" en
2026 es una señal de que la app está mal diseñada.

### 5.3 Cuándo pedir `POST_NOTIFICATIONS`

**Nunca en el primer arranque.** Android 13+ muestra el diálogo del sistema
una sola vez por app; si lo gastás en el arranque y el usuario lo rechaza, no
lo vuelve a ver y perdiste la función para siempre.

**El momento correcto:** cuando el usuario hace una acción que **necesita**
una notificación.

```
Usuario: "Descargar modelo"  ──►  "¿Querés que te avisemos cuando termine?"
                                  [ Sí, avisarme ]  [ No ]

Usuario: "Activar sincronización automática"  ──►  idem

Usuario: "Enviar este chat al docente"  ──►  (no necesita notificación: es inmediato)
```

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/notif/NotificationPermission.kt
package com.funshiengine.kitoru.notif

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Permiso de notificaciones (Android 13+).
 *
 * En API < 33 el permiso no existe: el usuario lo controla desde los ajustes
 * del sistema y no hay nada que pedir.
 */
object NotificationPermission {

    /** ¿La app puede mostrar notificaciones ahora mismo? */
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** El permiso que hay que pedir, o `null` si no hay nada que pedir. */
    val runtimePermission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
}
```

Y el pedido, desde Compose:

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/NotificationPermission.android.kt
@Composable
actual fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> /* el resultado se consulta con NotificationPermission.isGranted */ }
    return remember(launcher) {
        {
            val p = NotificationPermission.runtimePermission ?: return@remember
            launcher.launch(p)
        }
    }
}
```

> Si el usuario lo rechaza dos veces, **dejar de pedir** y mostrar el atajo a
> los ajustes del sistema: `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)`
> con `EXTRA_APP_PACKAGE`. Es la práctica recomendada por Google.

### 5.4 El mapa de permisos del dominio vs. los de Android

No hay que confundir dos cosas distintas:

| | Permisos del dominio (Kitoru) | Permisos del sistema (Android) |
|---|---|---|
| Dónde | `kitoru.domain.Permission` (`domain/08-permisos.md`) | `AndroidManifest.xml` |
| Qué son | "Este alumno puede ver los chats de esta cátedra" | "Esta app puede mandar notificaciones" |
| Quién los concede | La cátedra / la inscripción | El usuario del teléfono |
| Cómo se Ask | La UI los pide al confirmar una herramienta | `ActivityResultContracts.RequestPermission` |

El `AgentLoop` pide permisos del **dominio** (`requestPermission` en
`ToolContext`); los del **sistema** no intervienen en el chat.

---

## 6. Las notificaciones

### 6.1 Los canales

| Canal | ID | `IMPORTANCE` | Para qué | ¿Aparece en el launcher? |
|---|---|---|---|---|
| Descargas | `kitoru.descargas` | `LOW` | Progreso de la descarga del modelo | No (sólo en la bandeja) |
| Sincronización | `kitoru.sync` | `LOW` | La `Outbox` se vació / falló | No |
| Avisos | `kitoru.avisos` | `DEFAULT` | Anuncio de la cátedra, respuesta del docente | **Sí** |
| Errores | `kitoru.errores` | `DEFAULT` | Algo falló y el usuario tiene que saberlo | Sí |

**La importancia se fija al crear el canal y no se puede cambiar después**
(el usuario tiene que ir a los ajustes del sistema). Por eso hay que pensar
la importancia **la primera vez** y no improvisar.

### 6.2 Crear los canales

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/notif/NotificationChannels.kt
package com.funshiengine.kitoru.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

/**
 * Canales de notificación de Kitoru.
 *
 * Se crean en el `onCreate()` de la `Application`: `createNotificationChannel`
 * es idempotente (si el canal existe, no hace nada) y es seguro llamarlo en
 * cada arranque.
 *
 * En API < 26 los canales no existen: el `NotificationManager` ignora el
 * `channelId` y usa los ajustes globales del sistema.
 */
object NotificationChannels {

    const val DESCARGAS = "kitoru.descargas"
    const val SINCRONIZACION = "kitoru.sync"
    const val AVISOS = "kitoru.avisos"
    const val ERRORES = "kitoru.errores"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return

        fun channel(id: String, name: String, importance: Int, description: String) {
            nm.createNotificationChannel(
                NotificationChannel(id, name, importance).apply {
                    this.description = description
                    setShowBadge(true)
                },
            )
        }

        channel(
            DESCARGAS, "Descargas", NotificationManager.IMPORTANCE_LOW,
            "Progreso de la descarga de modelos de IA.",
        )
        channel(
            SINCRONIZACION, "Sincronización", NotificationManager.IMPORTANCE_LOW,
            "Envío de cambios al servidor de la cátedra.",
        )
        channel(
            AVISOS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT,
            "Anuncios de la cátedra y novedades del docente.",
        )
        channel(
            ERRORES, "Errores", NotificationManager.IMPORTANCE_DEFAULT,
            "Algo falló y necesitás enterarte.",
        )
    }
}
```

### 6.3 Publicar

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/notif/AppNotifications.kt
package com.funshiengine.kitoru.notif

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.funshiengine.kitoru.MainActivity
import com.funshiengine.kitoru.R

/**
 * Publica notificaciones de Kitoru.
 *
 * Todas pasan por acá, y todas **verifican el permiso** antes de publicar:
 * `NotificationManagerCompat.notify` **lanza `SecurityException`** si al
 * usuario le revocó las notificaciones desde los ajustes (cosa que puede
 * pasar entre dos llamadas).
 */
object AppNotifications {

    private const val ID_DESCARGA = 1001
    private const val ID_AVISO = 1002
    private const val ID_ERROR = 1003

    /** Notificación de progreso de una descarga. */
    fun progresoDescarga(context: Context, titulo: String, hecho: Long, total: Long) {
        if (!NotificationPermission.isGranted(context)) return
        val pct = if (total > 0) ((hecho * 100) / total).toInt() else 0
        val n = NotificationCompat.Builder(context, NotificationChannels.DESCARGAS)
            .setSmallIcon(R.drawable.ic_stat_kitoru)
            .setContentTitle(titulo)
            .setProgress(100, pct, total <= 0)
            .setOnlyAlertOnce(true)          // no vibrate en cada Actualización
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        safeNotify(context, ID_DESCARGA, n)
    }

    /** Notificación de error (toca y abre la app en la pantalla que corresponde). */
    fun error(context: Context, titulo: String, texto: String, destino: Destino) {
        if (!NotificationPermission.isGranted(context)) return
        val n = NotificationCompat.Builder(context, NotificationChannels.ERRORES)
            .setSmallIcon(R.drawable.ic_stat_kitoru)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir(context, destino))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(context, ID_ERROR, n)
    }

    private fun safeNotify(context: Context, id: Int, n: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** `PendingIntent` que abre la Activity en la pantalla indicada. */
    private fun abrir(context: Context, destino: Destino): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DESTINO, destino.name)
        }
        return PendingIntent.getActivity(
            context,
            destino.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** A dónde lleva el toque en la notificación. */
    enum class Destino { INICIO, CURSO, AJUSTES, DESCARGAS }
}
```

> `PendingIntent.FLAG_IMMUTABLE` es **obligatorio** desde Android 12 (API 31):
> sin él, `PendingIntent.getActivity` lanza `IllegalArgumentException`.

### 6.4 El icono de la notificación

Android exige un **icono de silueta monochrome** para la notificación
(`setSmallIcon`). Un PNG a color se muestra como un cuadro blanco. Hay que
dibujar `res/drawable/ic_stat_kitoru.xml` como vector con la silueta del logo
en blanco. Es un requisito que se descubre tarde (la notificación aparece
con un cuadrado blanco) ⇒ conviene crearlo desde el primer día.

### 6.5 El toque en la notificación

`MainActivity` recibe un `Intent` con `EXTRA_DESTINO`. En `onCreate` y en
`onNewIntent` se lee y se navega:

```kotlin
// En MainActivity
private fun manejarIntent(intent: Intent?) {
    val destino = intent?.getStringExtra(EXTRA_DESTINO) ?: return
    when (Destino.valueOf(destino)) {
        Destino.CURSO -> vm.abrirUltimaCatedra()
        Destino.AJUSTES -> vm.go(AppScreen.Ajustes)
        Destino.DESCARGAS -> vm.go(AppScreen.Descargas)
        Destino.INICIO -> vm.goToRole(vm.homeRole())
    }
}
```

`onNewIntent` importa porque con `launchMode="singleTask"` la Activity
**no se recrea**: hay que manejar el intent nuevo explícitamente.

---

## 7. El proceso en background

### 7.1 La decisión: `WorkManager` para todo, `ForegroundService` para nada (v1)

| Tarea | Herramienta | Razón |
|---|---|---|
| Descargar un modelo (1–2,4 GB) | `CoroutineWorker` + `setForeground()` | Necesita red y CPU prolongadas; `WorkManager` las resuelve con una notificación |
| Vaciar la `Outbox` | `CoroutineWorker` | Periódico, con `NetworkType.CONNECTED` |
| Re-indexar el corpus (BM25) | `CoroutineWorker` | CPU, 2 s, se puede hacer en background |
| Podar la caché | `CoroutineWorker` | Periódico, diario |
| **Inferencia local** | **Nada** (foreground) | Decidido en `01-local-android.md` §6.2: *"Nunca se usa un foreground service para inferir"* |
| **Limpiar la app (swipe desde recientes)** | **Nada** | Android lo hace solo |

### 7.2 Los workers concretos

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/work/SyncWorker.kt
package com.funshiengine.kitoru.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kitoru.sync.SyncCoordinator

/**
 * Vacía la `Outbox` contra el servidor de la cátedra.
 *
 * Es el worker que reemplaza al "daemon" del escritorio. La `Outbox` es
 * durable (está en `filesDir`), así que si el proceso muere, el siguiente
 * intento sigue donde quedó.
 */
class SyncWorker(context: Context, params: WorkerParameters) : KitoruWorker(context, params) {

    override suspend fun doWork(): Result {
        val coordinator = SyncCoordinator(/* … */)
        return when (val r = coordinator.flush()) {
            is FlushResult.Ok -> Result.success()
            is FlushResult.Transient -> Result.retry()      // red caída: reintenta
            is FlushResult.Permanent -> {
                // Un 403 no se arregla reintentando: la API key venció.
                Result.failure(workDataOf(KEY_ERROR to r.mensaje))
            }
        }
    }

    companion object {
        const val KEY_ERROR = "error"
        const val UNIQUE_NAME = "kitoru-sync"
    }
}
```

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/work/WorkerScheduler.kt
package com.funshiengine.kitoru.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Encola y cancela el trabajo de fondo de Kitoru.
 *
 * Todo el trabajo se encola **por nombre único**: reencolar es idempotente
 * y no se duplica.
 */
object WorkerScheduler {

    private fun wm(context: Context) = WorkManager.getInstance(context)

    /** Sincronización periódica de la `Outbox`. Cada 15 min (el mínimo de Android). */
    fun scheduleSync(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,   // no reinicia el periodo en cada arranque
            req,
        )
    }

    /** Descarga de un modelo. Inmediata, con red. */
    fun enqueueModelDownload(context: Context, repo: String, file: String, sha256: String) {
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    ModelDownloadWorker.KEY_REPO to repo,
                    ModelDownloadWorker.KEY_FILE to file,
                    ModelDownloadWorker.KEY_SHA256 to sha256,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)     // no descargar si el disco está lleno
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorker.TAG)
            .build()
        wm(context).enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    /** Poda de la caché (LRU). Diario. */
    fun scheduleCachePrune(context: Context) { /* … */ }

    /** Cancela todo lo pendiente de descargas (el alumno apretó "cancelar"). */
    fun cancelDownloads(context: Context) {
        wm(context).cancelAllWorkByTag(ModelDownloadWorker.TAG)
    }
}
```

> `ExistingPeriodicWorkPolicy.KEEP` es importante: con `UPDATE` (el default
> desde WorkManager 2.8) cada arranque **reinicia el periodo**, y como la app
> se lanza seguido, la sync nunca llega a correr. Es un bug clásico.

### 7.3 `ForegroundService`: la decisión y el por qué

**En la v1 no se escribe ningún `ForegroundService` propio.** Razones:

1. **La inferencia local es foreground por definición.** El alumno la está
   mirando. Si la app va al background, la inferencia se cancela
   (`01-local-android.md` §6.2). Un FGS que retenga 2 GB de RAM en background
   lo mata Android igual, y es mala citizenship.
2. **La descarga larga ya la resuelve `WorkManager`.** `CoroutineWorker` +
   `setForeground()` levanta el `SystemForegroundService` **de la propia
   biblioteca**, que ya cumple las reglas de Android 14/15.
3. **Android 15 (API 35) limita `dataSync` a 6 h por 24 h.** Si un día hace
   falta un FGS propio, tiene que ser `specialUse` (con justificación para
   Play) o `dataSync` con el tope.

**Pero el diseño queda prepared** por si el motor local necesita seguir
generando con la pantalla apagada (por ejemplo, para una tarea programada de
largo plazo):

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/background/InferenceForegroundService.kt
package com.funshiengine.kitoru.background

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.funshiengine.kitoru.KitoruPathsInitializer
import com.funshiengine.kitoru.KitoruServices
import com.funshiengine.kitoru.notif.AppNotifications
import com.funshiengine.kitoru.notif.NotificationChannels

/**
 * Servicio en primer plano para la inferencia local larga.
 *
 * **NO se usa en la v1** (§7.3): la inferencia es foreground por definición
 * y se cancela al ir al background. Este archivo queda como documentación
 * ejecutable del contrato, para el caso en que se necesite (por ejemplo,
 * una tarea programada que el docente quiere que termine aunque el alumno
 * cierre la app).
 *
 * Reglas de Android 14+ (API 34) que hay que respetar:
 *  - `foregroundServiceType` **explícito** en el manifest.
 *  - `startForeground()` tiene que llamarse **dentro de los primeros 5 s**
 *    o el sistema tira `ForegroundServiceStartNotAllowedException` (API 31+)
 *    / `ForegroundServiceDidNotStartInTimeException` (API 34+).
 *  - `dataSync` tiene un tope de 6 h por 24 h en API 35.
 *  - No se puede arrancar un FGS desde background salvo excepciones
 *    explícitas (API 31+): hay que hacerlo desde un `WorkManager` o desde
 *    una notificación del usuario.
 */
class InferenceForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        KitoruPathsInitializer.init(applicationContext)
        KitoruServices.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificacion = notificacionProgreso()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notificacion)
        }
        // START_REDELIVER_INTENT: si el sistema mata el servicio, lo reinicia
        // con el mismo intent. El trabajo pesado tiene que ser idempotente.
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificacionProgreso(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, NotificationChannels.DESCARGAS)
            .setSmallIcon(com.funshiengine.kitoru.R.drawable.ic_stat_kitoru)
            .setContentTitle("Generando con el modelo local")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.funshiengine.kitoru.action.STOP_INFERENCE"
    }
}
```

Y en el manifest (si se activara):

```xml
<service
    android:name=".background.InferenceForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

### 7.4 El ciclo de vida del proceso: `onTrimMemory` y `ProcessLifecycleOwner`

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/KitoruApp.kt
package com.funshiengine.kitoru

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.funshiengine.kitoru.notif.NotificationChannels

/**
 * Punto de entrada del proceso de Kitoru.
 *
 * Hace tres cosas, en este orden:
 *  1. Fija las carpetas de datos (`KitoruPaths`) — antes que nadie.
 *  2. Crea los canales de notificación.
 *  3. Observa el ciclo de vida del **proceso** para la política del motor
 *     local (`01-local-android.md` §6.2 y §6.4).
 */
class KitoruApp : Application(), ComponentCallbacks2 {

    override fun onCreate() {
        super.onCreate()

        // 1) Las carpetas. Esto tiene que ser lo primero: cualquier cosa que
        //    toque los repositorios las necesita.
        KitoruPathsInitializer.init(this)
        KitoruServices.init(this)

        // 2) Los canales de notificación.
        NotificationChannels.ensure(this)

        // 3) La política del motor local, si está activo.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    // La app dejó de estar visible: se corta la generación en
                    // curso y se programa la descarga del modelo a los 5 min.
                    LocalEngineLifecycle.onAppBackgrounded()
                }
            },
        )
    }

    /**
     * Android está pidiendo memoria. Los niveles van de "nada" (0) a
     * "todo" (80+), así que el primer `>=` que matchea gana.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // La UI se ocultó (el alumno apretó Home): NO es presión de memoria.
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                LocalEngineLifecycle.cancelGeneration()

            // Presión real: se corta y se suelta el KV cache (100–450 MB).
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                LocalEngineLifecycle.cancelGeneration()
                LocalEngineLifecycle.clearKvCache()
            }

            // El sistema va a matar el proceso: se suelta todo.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                LocalEngineLifecycle.unload()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // El tema del sistema cambió (claro/oscuro): Compose lo recalcula solo
        // porque `isSystemInDarkTheme()` lee la configuración actual.
    }
}
```

Y el manifest la declara:

```xml
<application
    android:name=".KitoruApp"
    android:allowBackup="true"
    android:largeHeap="true"          <!-- el índice BM25 + Compose -->
    android:extractNativeLibs="true"  <!-- los backends de CPU dinámicos de llama.cpp -->
    ...>
```

---

## 8. El botón "atrás"

### 8.1 El problema

El `BackHandler` de Compose (`androidx.activity.compose.BackHandler`) usa el
`OnBackPressedDispatcher` de la Activity. **Gana el callback registrado
últimamente** que esté habilitado. Si cada nivel de la UI registra su propio
`BackHandler`, el orden de registro depende del orden de composición, y
cualquier refactor lo rompe en silencio.

Además, el `KitoruViewModel` actual tiene `screen` como **valor**, no pila, y
`back()` recalcula el padre con un `when` plano.

### 8.2 La decisión: una función pura + un solo `BackHandler`

**La lógica de "qué hacer con el atrás" es una función pura en `commonMain`**,
testeable sin Android. **El `BackHandler` es un adapter finito**.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/nav/Back.kt (nuevo)
package kitoru.ui.nav

/**
 * Qué hacer cuando el usuario aprieta "atrás".
 *
 * Es una función **pura**: recibe el estado y devuelve la decisión. Toda la
 * lógica de "atrás" queda en `commonMain` y se testea sin Android; el
 * `BackHandler` de cada plataforma es un adapter de 5 líneas.
 */
sealed interface BackDecision {

    /** No hay nada que cerrar: la app sale (el comportamiento por defecto). */
    data object ExitApp : BackDecision

    /** Hay que cerrar el overlay identificado (un id de diálogo/hoja). */
    data class CloseOverlay(val overlayId: String) : BackDecision

    /** Hay que desapilar una entrada de la pila global de navegación. */
    data class PopNavigation(val target: String) : BackDecision

    /** Hay que confirmar antes de abandonar algo en curso. */
    data class Confirm(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: BackDecision,
    ) : BackDecision
}

/**
 * Resuelve el "atrás".
 *
 * El orden es **de lo más profundo a lo más superficial**:
 *  1. Un overlay abierto (diálogo, hoja inferior, panel de detalle).
 *  2. La pila interna de la sección (si la hay).
 *  3. La pila global de navegación.
 *  4. Un chat generándose (confirmar).
 *  5. Salir.
 */
fun resolveBack(state: BackState): BackDecision = when {
    state.overlayId != null -> BackDecision.CloseOverlay(state.overlayId)
    state.sectionStack.isNotEmpty() -> BackDecision.PopNavigation(state.sectionStack.last())
    state.globalStack.size > 1 -> BackDecision.PopNavigation(state.globalStack.last())
    state.generating -> BackDecision.Confirm(
        title = "La respuesta sigue generándose",
        message = "Si salís ahora, la respuesta queda incompleta. ¿Querés continuar?",
        confirmLabel = "Salir igual",
        onConfirm = BackDecision.ExitApp,
    )
    else -> BackDecision.ExitApp
}

/** El estado que necesita [resolveBack]. */
data class BackState(
    /** Id del overlay abierto (diálogo, hoja). `null` si no hay ninguno. */
    val overlayId: String? = null,
    /** Pila interna de la sección (docente). */
    val sectionStack: List<String> = emptyList(),
    /** Pila global de navegación. */
    val globalStack: List<String> = emptyList(),
    /** ¿Hay una generación en curso? */
    val generating: Boolean = false,
)
```

### 8.3 El `BackHandler` de Android

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/BackHandler.android.kt
package com.funshiengine.kitoru.ui.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kitoru.ui.nav.BackDecision
import kitoru.ui.nav.resolveBack

/**
 * El botón "atrás" de Android.
 *
 * **Un solo `BackHandler`, en la raíz.** Los `BackHandler` anidados se
 * resuelven por orden de registro (gana el último), lo que convierte
 * cualquier refactor en un bug silencioso. Con uno sólo, la decisión la
 * toma [resolveBack], que es una función pura testeable en `commonMain`.
 *
 * Cada overlay (diálogo, hoja) que necesite su propio "atrás" lo maneja
 * fuera de este mecanismo: un `Dialog` de Compose ya lo cierra con el
 * `onDismissRequest`, y una `ModalBottomSheet` de Material3 también.
 */
@Composable
actual fun PlatformBackHandler(
    state: () -> BackState,
    onDecision: (BackDecision) -> Unit,
) {
    val current by rememberUpdatedState(state)
    val currentOnDecision by rememberUpdatedState(onDecision)

    BackHandler {
        val decision = resolveBack(current())
        when (decision) {
            is BackDecision.ExitApp -> {
                // Dejar que el sistema cierre la Activity:
                // `finish()` explícito, o simplemente no hacer nada y dejar
                // que el `OnBackPressedDispatcher` termine el callback.
                // En la raíz de una app, "atrás" **no** cierra la app: la
                // manda al background. Para cerrarla de verdad:
                //   currentOnDecision(decision)  →  activity.finish()
                currentOnDecision(decision)
            }
            else -> currentOnDecision(decision)
        }
    }
}
```

Y el `commonMain` expone el `expect`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/BackHandler.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import kitoru.ui.nav.BackDecision
import kitoru.ui.nav.BackState

/**
 * El botón "atrás" de la plataforma.
 *
 * En Android es el botón físico / gestual del sistema. En el escritorio es
 * la tecla `Esc` (que ya está mapeada en `teacher/01-navegacion.md` §7.2).
 */
@Composable
expect fun PlatformBackHandler(
    state: () -> BackState,
    onDecision: (BackDecision) -> Unit,
)
```

El `desktopMain` implementa el mismo contrato con `onPreviewKeyEvent` filtrando
`Key.Escape`.

### 8.4 La escalera del "atrás", en detalle

| Nivel | Qué hay abierto | Qué hace el "atrás" | Quién lo implementa |
|---|---|---|---|
| 0 | Teclado abierto | **Cierra el teclado** | El sistema (IME), antes del dispatcher |
| 1 | Diálogo (`AlertDialog`, hoja inferior) | Cierra el diálogo | `Dialog(onDismissRequest)` / `ModalBottomSheet` |
| 2 | Panel de detalle en COMPACT | Cierra el panel, vuelve a la lista | `resolveBack` → `PopNavigation` |
| 3 | Sub-nivel de una sección (docente: material → ficha) | Vuelve al sub-nivel anterior | `resolveBack` → `PopNavigation` (pila de la sección) |
| 4 | Chat | Vuelve a la home del rol | `resolveBack` → `PopNavigation` (pila global) |
| 5 | Home del rol, con un chat generándose | **Confirma** | `resolveBack` → `Confirm` |
| 6 | Home del rol, sin nada en curso | **Manda la app al background** | El sistema (no se intercepta) |

> **El nivel 6 no se intercepta.** Es el comportamiento estándar de Android: el
> "atrás" en la raíz **no** cierra la app, la manda al background. La app
> sigue viva, el `ViewModel` sigue vivo, y una respuesta que se estaba
> generando **sigue llegando**. Esto es mejor que interceptar y llamar a
> `finish()`, que mataría el `ViewModel` y cancelaría la generación.
>
> Si el producto quiere "salir de Kitoru" explícito, se pone un ítem de menú
> "Cerrar sesión" / "Salir", no se secuestra el "atrás".

### 8.5 El "atrás" en un chat que está generando

Decisión de producto: **salir del chat no cancela la generación**. La
respuesta sigue llegando en el `viewModelScope` (que sobrevive porque la
Activity no se destruye) y se persiste cuando termina. El alumno puede
volver al chat y ver el resultado.

Si el alumno quiere cortar: botón explícito **"Detener"** en el composer, que
hace `job.cancel()` y marca el mensaje como `INTERRUMPIDA`.

### 8.6 Errores típicos a evitar

| Error | Qué pasa | Cómo se evita |
|---|---|---|
| **Dos `BackHandler` en niveles distintos** | El que se registró último gana, y el orden cambia al refactorizar | **Un solo** `BackHandler` en la raíz |
| **Un `Dialog` con su propio `BackHandler` arriba de la raíz** | El `BackHandler` del diálogo queda registrado después ⇒ gana ⇒ correcto, pero frágil | Que el `Dialog` use su `onDismissRequest` y no registre un `BackHandler` |
| **`enableOnBackInvokedCallback = true`** | En Android 13+ con el dispatcher predictivo, el "atrás" puede llegar por el callback de Activity en vez del dispatcher | Dejar `enableOnBackInvokedCallback` en `false` (default) hasta que el `BackHandler` de Compose se integre con él |
| **Ignorar el `Overlay`** | El "atrás" cierra el chat en vez del diálogo de permisos de herramienta | `resolveBack` mira el overlay **primero** |

---

## 9. El modo oscuro automático

### 9.1 La decisión: tres modos, no dos

El `AppConfig` ya tiene `theme: String = "system"` (de
`process/01-packaging-ci.md` §6.1). Los tres valores:

| Valor | Significado | Quién lo elige |
|---|---|---|
| `"system"` | Sigue al sistema | **Default** |
| `"light"` | Siempre claro | El usuario, en Ajustes |
| `"dark"` | Siempre oscuro | El usuario, en Ajustes |

### 9.2 `isSystemInDarkTheme()`

Compose Multiplatform 1.7.3 expone `isSystemInDarkTheme()` en
`androidx.compose.foundation`. En Android lee la `Configuration.uiMode` del
sistema y además respeta el `AppCompatDelegate` si la app lo usara (no lo
usa).

Como no está garantizado que esté disponible en `commonMain` de CMP 1.7.3, se
hace una costura explícita:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/ThemeMode.kt (nuevo)
package kitoru.ui.platform

import androidx.compose.runtime.Composable

/**
 * Si el sistema está en modo oscuro.
 *
 * Android: `isSystemInDarkTheme()` (lee `Configuration.uiMode`).
 * Escritorio: `false` por default (el tema del SO no se consulta: la app
 * tiene su propio ajuste "Claro/Oscuro", que es lo que espera el usuario de
 * una app de escritorio con ventanas).
 */
@Composable
expect fun isSystemDarkTheme(): Boolean
```

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/ThemeMode.android.kt
package com.funshiengine.kitoru.ui.platform

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
actual fun isSystemDarkTheme(): Boolean = isSystemInDarkTheme()
```

Y la resolución final (en `commonMain`):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/ThemeMode.kt (completo)

/**
 * El tema oscuro efectivo.
 *
 * @param modo `"system"`, `"light"` o `"dark"`.
 */
@Composable
fun rememberDarkTheme(modo: String): Boolean = when (modo) {
    "dark" -> true
    "light" -> false
    else -> isSystemDarkTheme()
}
```

> Si en el futuro CMP expone `isSystemInDarkTheme()` en `commonMain`, este
> `expect/actual` se puede borrar y usar el de la biblioteca. El `expect` es
> barato y aísla el riesgo.

### 9.3 El acento y el color dinámico

El `KitoruTheme` actual toma `accent: Int` (un color ARGB). Se mantiene.

**Color dinámico de Material 3 (`dynamicLightColorScheme` / `dynamicDarkColorScheme`):**
disponible desde API 31. **Decisión: desactivado por default**
(`dynamicColor = false` en `AppConfig`), con un interruptor en Ajustes:

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/ThemeMode.android.kt (completo)

@Composable
actual fun rememberKitoruColorScheme(
    dark: Boolean,
    accent: Int,
    dynamic: Boolean,
): ColorScheme {
    val ctx = LocalContext.current
    if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    return MaterialTheme.colorScheme   // el que ya calculó KitoruTheme
}
```

**Motivo de desactivarlo por default:** la identidad visual de una app de
cátedra es parte del producto (el docente comparte capturas, los alumnos la
reconocen). El color dinámico la vuelve genérica. Pero se deja el
interruptor porque a muchos alumnos les gusta.

### 9.4 El cambio de tema del sistema en caliente

Con `android:configChanges="uiMode"` (declarado en el manifest), la Activity
**no** se recrea al cambiar el tema del sistema: Compose recalcula
`isSystemInDarkTheme()` y vuelve a componer. Sin el `configChanges`, la
Activity se recrearía y el `ViewModel` conservaría el estado, pero habría un
parpadeo.

---

## 10. Orientación, redimensionado y multi-ventana

### 10.1 No se bloquea la orientación

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:resizeableActivity="true"
    android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|keyboard|navigation|uiMode|density|fontScale|layoutDirection"
    android:windowSoftInputMode="adjustResize"
    android:theme="@style/Theme.Kitoru">
```

| Atributo | Por qué |
|---|---|
| **Sin `android:screenOrientation`** | La app tiene que funcionar en horizontal y vertical. Bloquear la orientación es una decisión contra la accesibilidad y contra la tendencia de Android. |
| `resizeableActivity="true"` | Obligatorio desde API 24 (y por defecto `true` si `targetSdk ≥ 24`). En pantalla partida, sin esto, la app no se puede mostrar en una ventana chica. |
| `configChanges` con `orientation\|screenSize\|…` | **Evita la recreación** de la Activity: no hay parpadeo, no se pierde el estado del `IME`, no se re-crean los `ViewModel`s. |
| `windowSoftInputMode="adjustResize"` | El contenido se redimensiona cuando aparece el teclado. Con `enableEdgeToEdge()` hay que aplicar además `Modifier.imePadding()`. |

> **Belt-and-suspenders:** `configChanges` evita la recreación *normal*, pero
> el sistema puede recrear la Activity igual (memoria, "Don't keep activities",
> cambios de locale). Por eso **también** hay `ViewModel` + `SavedStateHandle`.
> Las dos mecanismos se complementan: el `ViewModel` cubre el caso difícil
> (muerte del proceso), el `configChanges` cubre el caso común (rotación).

### 10.2 El colapso de las tres columnas

El layout de tres columnas (alumnos · hilos · visor, del rol docente) se
colapsa según el ancho **de la ventana**, medido con `BoxWithConstraints`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/WindowSizeClass.kt
// (ya diseñado en teacher/01-navegacion.md §6.1 — se reusa tal cual)

enum class WindowSizeClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    BoxWithConstraints {
        val w = maxWidth
        return remember(w) {
            when {
                w < 600.dp -> WindowSizeClass.COMPACT
                w < 840.dp -> WindowSizeClass.MEDIUM
                else -> WindowSizeClass.EXPANDED
            }
        }
    }
}
```

**Por qué `BoxWithConstraints` y no `WindowSizeClass` de Material 3:** la
biblioteca `androidx.compose.material3:material3-window-size-class` no es
multiplatform en Compose 1.7.3. Además, `BoxWithConstraints` mide la
**ventana**, que en pantalla partida es más chico que la pantalla: es
exactamente lo que se necesita.

**Qué muestra cada clase en Android:**

| Clase | Ancho | En un teléfono (360–480 dp) | En un teléfono en horizontal (640–900 dp) | En una tablet |
|---|---|---|---|---|
| **COMPACT** | < 600 dp | 1 columna, drawer, detalle a pantalla completa | 1 columna (el ancho en dp no cambia al rotar en un teléfono pequeño) | — |
| **MEDIUM** | 600–839 dp | — | Rail de iconos (72 dp) + 1 columna | Rail de iconos + panel de detalle de 320 dp |
| **EXPANDED** | ≥ 840 dp | — | — | Rail expandido (240 dp) + 2 columnas |

**Detalle importante de un teléfono en horizontal:** un Pixel de 1080×2400
tiene ~411 dp de ancho en vertical. En horizontal, el ancho **no** cambia (sigue
siendo 411 dp de ancho, 2400 de alto): lo que cambia es el **alto**. Por eso
en un teléfono el layout es **siempre COMPACT**, y en horizontal hay más
espacio **vertical** (útil para el chat, que gana altura) pero no más ancho.

**Regla de oro:** el layout de tres columnas **nunca** aparece en un teléfono.
Aparece en tablets y en el escritorio. En Android, el "panel de tres columnas"
del docente es una **pila** (`NavHost` propio), tal como dice
`teacher/07-chats.md` D1.

### 10.3 Qué se colapsa exactamente

| Elemento | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Navegación primaria | `ModalNavigationDrawer` (hamburger) | `NavigationRail` de iconos (72 dp) | `NavigationRail` expandido (240 dp) |
| Lista + detalle | Nunca juntos | El detalle reemplaza a la lista (320 dp) | Juntos (lista 320 dp + detalle flexible) |
| Panel de detalle | Pantalla completa con `←` | Panel derecho | Columna fija |
| Dialogs | Diálogo | Diálogo | Panel si hay espacio |
| Filtros avanzados | Diálogo | Diálogo | En línea |

**En COMPACT, el detalle es un elemento de la pila**: `nav.push(section, elementId)`
muestra el detalle a pantalla completa, y el "atrás" lo saca (§8.4, nivel 2).

### 10.4 Pantalla partida (split screen)

| Aspecto | Comportamiento |
|---|---|
| La ventana puede medir 200 dp de ancho | `COMPACT` → el drawer y el detalle a pantalla completa siguen funcionando |
| La app se puede arrastrar a una ventana de 1/3 | `BoxWithConstraints` recalcula la clase al instante; **la pila no se pierde**: si se estaba viendo un detalle, se sigue viendo |
| `configChanges` cubre el `screenSize` | La Activity no se recrea al cambiar el tamaño ⇒ no hay parpadeo |
| Multi-ventana con dos instancias | `launchMode="singleTask"` + `Mutex` de proceso (§4.2) |

**Regla:** cambiar el tamaño **nunca** cambia el contenido, sólo la
disposición. Si en COMPACT se estaba en "Material → ficha", al pasar a
EXPANDED la misma ficha aparece en el panel derecho, con la lista al lado.

### 10.5 El teclado

Con `adjustResize` + `enableEdgeToEdge()`:

```kotlin
// En el Scaffold raíz
Scaffold(
    modifier = Modifier.fillMaxSize(),
) { inner ->
    Box(
        Modifier
            .padding(inner)
            .imePadding(),      // ← el composer del chat no queda tapado
    ) { /* … */ }
}
```

`imePadding()` es de `androidx.compose.foundation.layout` y funciona en
Compose Multiplatform (en desktop es 0).

**Cuidado:** `adjustResize` + `imePadding()` puede **contar dos veces** el
inset del teclado en algunas versiones. La receta que funciona:

- `enableEdgeToEdge()` **y** `Modifier.imePadding()` en la columna de contenido,
- `Scaffold` con `contentWindowInsets = WindowInsets(0)` para que no aplique los
  insets por defecto,
- `windowSoftInputMode="adjustResize"`.

### 10.6 Plegables y la bisagra

`WindowInfoTracker` (androidx.window) avisa de la postura del plegable. **Fuera
del alcance de la v1**: con `BoxWithConstraints` + `COMPACT/MEDIUM/EXPANDED` la
app ya se comporta bien en un plegable desplegado (pantalla grande → EXPANDED).
Se anota como mejora futura: separar la app en dos panes con `FoldingFeature`.

---

## 11. Launcher, ícono y splash

### 11.1 El ícono del launcher

El ícono adaptativo ya está diseñado en `process/01-packaging-ci.md` §4.3.
Se reitera la **estructura de archivos**:

```
android/src/main/res/
├── drawable/
│   ├── ic_launcher_foreground.xml      (vector 108×108, glifo dentro de la zona segura de 72×72)
│   ├── ic_launcher_monochrome.xml      (misma silueta, un solo color: íconos temáticos de Android 13+)
│   └── ic_stat_kitoru.xml              (silueta para las notificaciones — §6.4)
├── values/
│   ├── ic_launcher_background.xml      (<color name="ic_launcher_background">#0E5C4A</color>)
│   └── strings.xml
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml                 (<adaptive-icon>)
    └── ic_launcher_round.xml           (idéntico)
```

**El ícono monocromo** (`<monochrome>`) es lo que Android 13+ muestra cuando
el usuario activa los íconos temáticos. Sin ese `<monochrome>`, el ícono se
muestra con la cuadrícula de colores del launcher, que no es lo que quiere
Kitoru.

**Los PNG de fallback** (API < 26) son opcionales desde Android 8: los
dispositivos con API < 26 son < 1 % y los genera el sistema a partir del
adaptativo... en realidad **no**: los adaptive icons son API 26+, así que para
API 24–25 hay que proveerse los PNG. Como `minSdk = 24`, hay que incluirlos.

### 11.2 El splash

**Android 12+ (API 31+)** tiene splash del sistema: se muestra el ícono de la
app sobre el color de fondo, y la app decide cuánto dura con
`setKeepOnScreenCondition`.

**API < 31** muestra la `windowBackground` del tema mientras arranca la app.
Para que no haya un parpadeo blanco → la `windowBackground` tiene que ser el
color de la marca.

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt (extracto)
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

override fun onCreate(savedInstanceState: Bundle?) {
    // El splash del sistema tiene que instalarse ANTES de super.onCreate().
    val splash = installSplashScreen()
    super.onCreate(savedInstanceState)
    ...
    // Se mantiene mientras la UI pueda pintar de verdad:
    splash.setKeepOnScreenCondition { !holder.viewModel.ready }
}
```

Y los temas:

```xml
<!-- android/src/main/res/values/themes.xml -->
<resources>
    <!-- Tema de la app (después del splash). -->
    <style name="Theme.Kitoru" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/kitoru_surface</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>

    <!--
      Tema del splash. En API 31+ el sistema usa windowSplashScreenBackground
      + windowSplashScreenAnimatedIcon. En API < 31 se usa el drawable.
    -->
    <style name="Theme.Kitoru.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/kitoru_brand</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <!-- Para API < 31 -->
        <item name="android:windowBackground">@drawable/splash_background</item>
    </style>
</resources>
```

```xml
<!-- android/src/main/res/values-night/themes.xml -->
<resources>
    <style name="Theme.Kitoru" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/kitoru_surface_dark</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

> `Theme.SplashScreen` viene de `androidx.core:core-splashscreen`
> (dependencia nueva). Para API < 31 hay que poner
> `postSplashScreenTheme` en el tema de splash, pero como **toda** la app se
> dibuja con Compose, el tema de splash se aplica al manifest y Compose
> pinta encima; el `postSplashScreenTheme` no es estrictamente necesario
> porque el `Theme.Kitoru` se aplica en `onCreate` con `setTheme(R.style.Theme_Kitoru)`.

```xml
<!-- drawable/splash_background.xml (para API < 31) -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/kitoru_brand" />
    <item
        android:gravity="center"
        android:width="144dp"
        android:height="144dp"
        android:drawable="@drawable/ic_launcher_foreground" />
</layer-list>
```

**Reglas del splash:**

1. **Sin timeout artificial.** El splash se mantiene lo que haga falta para
   que la primera pantalla se pueda pintar de verdad (`ready == true`), y ni un
   milisegundo más.
2. **Sin trabajo pesado detrás.** Si hay que leer 2,7 MB de `messages.json` para
   pintar, eso va en un `LaunchedEffect` con un estado de carga, **no** en el
   splash.
3. `installSplashScreen()` **antes** de `super.onCreate()`.

### 11.3 El ícono dentro de la app

La app usa `compose.materialIconsExtended`, así que los íconos de Material
están disponibles. El logo de Kitoru (el "k" sobre un glifo) se puede
dibujar como un `ImageVector` propio en `:ui/commonMain`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/theme/KitoruLogo.kt (nuevo)
package kitoru.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * El logo de Kitoru, como `ImageVector`.
 *
 * Es el mismo path que el `ic_launcher_foreground.xml` del Android, para que
 * el ícono del launcher y el de la app sean el mismo dibujo.
 */
val KitoruLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "Kitoru",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 108f,
        viewportHeight = 108f,
    ).apply {
        // path del glifo (el mismo del vector XML)
        path(fill = SolidColor(Color.Black)) {
            moveTo(46f, 46f); lineTo(48.5f, 46f); lineTo(48.5f, 62f); lineTo(46f, 62f); close()
            moveTo(53f, 46f); lineTo(55.5f, 46f); lineTo(55.5f, 62f); lineTo(53f, 62f); close()
            moveTo(60f, 46f); lineTo(62.5f, 46f); lineTo(62.5f, 62f); lineTo(60f, 62f); close()
        }
    }.build()
}
```

---

## 12. `applicationId`, versión y firma

### 12.1 El `applicationId`

```kotlin
// android/build.gradle.kts (extracto)
android {
    namespace = "com.funshiengine.kitoru"

    defaultConfig {
        applicationId = "com.funshiengine.kitoru"
        minSdk = 24
        targetSdk = 35
        versionCode = kitoruVersionCode
        versionName = kitoruVersion
    }

    buildTypes {
        debug {
            // Se puede instalar al lado de la release.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false       // kotlinx.serialization + reflexión: no risking
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

**Reglas:**

| Regla | Detalle |
|---|---|
| El `applicationId` **no se cambia nunca** después de la primera release | Android lo trata como otra app: la nueva **no** reemplaza a la vieja y el usuario tiene que desinstalar (perdiendo los datos) |
| El `namespace` puede cambiar (es sólo el nombre de las clases R/BuildConfig) | No afecta al `applicationId` |
| Los módulos de biblioteca tienen su propio `namespace` | `:core` → `com.funshiengine.kitoru.core`, `:ui` → `com.funshiengine.kitoru.ui`, `:llm-api` → `com.funshiengine.kitoru.llm` |
| El `debug` tiene su propio `applicationId` | Se pueden instalar los dos a la vez |
| El `versionCode` es **monotónico** | Android rechaza un APK con `versionCode` ≤ al instalado |

### 12.2 El versionado

Idéntico al escritorio (`process/01-packaging-ci.md` §5.1): la fuente única es
`gradle.properties`.

```properties
# gradle.properties
kitoru.version=0.1.0
kitoru.versionCode=1
```

```kotlin
// android/build.gradle.kts
val kitoruVersion: String = providers.gradleProperty("kitoru.version").get()
val kitoruVersionCode: Int = providers.gradleProperty("kitoru.versionCode").get().toInt()
```

El `BuildInfo` generado en `:core` (`process/01-packaging-ci.md` §5.2) muestra
la misma versión en las dos plataformas. En Android se puede contrastar contra
el paquete instalado:

```kotlin
// En la pantalla "Acerca de", si el APK fue instalado a mano con otra versión:
val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
```

### 12.3 La firma

**El keystore vive FUERA del repo.**

| | Debug | Release |
|---|---|---|
| Keystore | `~/.android/debug.keystore` (lo genera el SDK) | `~/.kitoru/kitoru-release.jks` (**fuera del proyecto**) |
| Alias | `androiddebugkey` | `kitoru` |
| Contraseña | `android` | **secreto** |
| `applicationId` | `com.funshiengine.kitoru.debug` | `com.funshiengine.kitoru` |

El bloque de firma (idéntico al de `process/01-packaging-ci.md` §4.1) busca
primero las variables de entorno (CI) y después `keystore.properties`:

```kotlin
// android/build.gradle.kts
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun secret(env: String, key: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(key))?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("KITORU_KEYSTORE_FILE", "storeFile")
// …

// Tarea de guarda: si alguien pide assembleRelease sin keystore, el error
// es claro en vez de producir un APK sin firmar.
val validateReleaseSigning by tasks.registering {
    group = "verification"
    doLast {
        if (!hasReleaseKeystore) {
            throw GradleException(
                "Falta el keystore de release.\n" +
                "  Local: creá 'keystore.properties' en la raíz del repo (NO se versiona).\n" +
                "  CI: exportá KITORU_KEYSTORE_FILE, KITORU_KEYSTORE_PASSWORD,\n" +
                "       KITORU_KEY_ALIAS y KITORU_KEY_PASSWORD."
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(validateReleaseSigning) }
```

Y el `.gitignore`:

```gitignore
# Firma de release: NUNCA en el repo.
keystore.properties
*.jks
*.keystore
```

**Generación (una sola vez, en la máquina del mantainer):**

```bash
keytool -genkeypair -v \
  -keystore ~/.kitoru/kitoru-release.jks \
  -alias kitoru \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12 \
  -dname "CN=Kitoru, OU=Nootechnia, O=Nootechnia, L=Buenos Aires, C=AR"
```

**En CI:** el `.jks` nunca se sube; se guarda el base64 en
`KITORU_KEYSTORE_BASE64` (secreto de GitHub) y el workflow lo reconstruye en el
runner.

### 12.4 La distribución: APK directo, no Play Store

Decisión de `process/01-packaging-ci.md` D7 (sideloading por APK en la release
de GitHub). Las consecuencias en Android:

| Tema | Qué implica |
|---|---|
| "Instalar apps desconocidas" | El usuario tiene que habilitarlo para el navegador/files. La app debe **explicarlo** con un link a `https://support.google.com/android/answer/9457058` |
| Actualización | El usuario baja el `.apk` nuevo e lo instala: Android permite actualizar si la firma es la misma. **Hay que avisar** si el `versionCode` no es mayor |
| El `.apk` tiene que ser universal o por ABI | Con `abiFilters = ["arm64-v8a"]` sale un APK más chico, pero deja afuera a los emuladores x86_64. Para el primer release conviene `arm64-v8a` + `x86_64` (emulador) |
| Play Protect | Puede avisar "app de origen desconocido". Con un APK de una app no verificada por Google, Android lo muestra en "Play Protect" y a veces lo bloquea. Se documenta en el README |

### 12.5 El backup: qué se respalda y qué no

```xml
<!-- android/src/main/res/xml/backup_rules.xml  (API ≤ 30) -->
<full-backup-content>
    <!-- Todo de filesDir se respalda, MENOS las credenciales. -->
    <include domain="file" path="kitoru/" />
    <exclude domain="file" path="kitoru/config/credentials.bin" />
    <exclude domain="file" path="kitoru/config/credentials.bin.bak" />
    <!-- La caché no se respalda (ni siquiera está en filesDir). -->
    <!-- noBackupFilesDir tampoco. -->
</full-backup-content>
```

```xml
<!-- android/src/main/res/xml/data_extraction_rules.xml  (API 31+) -->
<data-extraction-rules>
    <cloud-backup>
        <include domain="file" path="kitoru/" />
        <exclude domain="file" path="kitoru/config/credentials.bin" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="kitoru/" />
        <exclude domain="file" path="kitoru/config/credentials.bin" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- En el manifest -->
<application
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... />
```

> Las credenciales se **excluyen a propósito**: la clave AES de la Keystore
> **no viaja** con el backup (la Keystore es del dispositivo). Si se respaldara
> el ciphertext sin la clave, el alumno vería una credencial inútil y el
> respaldo sería engañoso. La API key se **re-ingresa** en el teléfono nuevo.

---

## 13. Inventario: qué hay que escribir para que la app arranque

### 13.1 `:android` — el módulo de la app (completo)

```
android/build.gradle.kts                              (nuevo)
android/proguard-rules.pro                            (nuevo)
android/src/main/AndroidManifest.xml                   (nuevo)
android/src/main/kotlin/com/funshiengine/kitoru/
├── KitoruApp.kt                        (nuevo)  Application + onTrimMemory + canales
├── MainActivity.kt                     (nuevo)  ComponentActivity + setContent + splash + edge-to-edge
├── KitoruStateHolder.kt                (nuevo)  AndroidViewModel que envuelve al KitoruViewModel
├── KitoruPathsInitializer.kt           (nuevo)  fija KitoruPaths con el Context (idempotente)
├── KitoruServices.kt                   (nuevo)  singletons de proceso: repositorios + CredentialStore
├── AndroidPlatformContext.kt           (nuevo)  PlatformContext de Android (§5.2 de 01-local-android)
├── security/CredentialStore.kt         (nuevo)  API key cifrada con la Android Keystore
├── notif/NotificationChannels.kt       (nuevo)  los 4 canales
├── notif/NotificationPermission.kt     (nuevo)  isGranted / runtimePermission
├── notif/AppNotifications.kt           (nuevo)  publicar + PendingIntent
├── work/KitoruWorker.kt                (nuevo)  base: inicializa paths y servicios
├── work/SyncWorker.kt                  (nuevo)  vacía la Outbox
├── work/ModelDownloadWorker.kt         (nuevo)  descarga el .gguf (de 01-local-android)
├── work/IndexWorker.kt                 (nuevo)  re-indexa el corpus BM25
├── work/CachePruneWorker.kt            (nuevo)  poda LRU de la caché
├── work/WorkerScheduler.kt             (nuevo)  encola/cancela todo
├── background/InferenceForegroundService.kt  (nuevo, documentado, NO activo en v1)
└── lifecycle/LocalEngineLifecycle.kt   (nuevo)  política del motor local (§7.4)
```

**Recursos:**

```
android/src/main/res/
├── values/strings.xml                   (nuevo)  app_name, textos del sistema
├── values/colors.xml                    (nuevo)  kitoru_brand, kitoru_surface…
├── values/themes.xml                    (nuevo)  Theme.Kitoru + Theme.Kitoru.Starting
├── values-night/themes.xml              (nuevo)  la variante oscura del tema del sistema
├── values/ic_launcher_background.xml    (nuevo)
├── drawable/ic_launcher_foreground.xml  (nuevo)
├── drawable/ic_launcher_monochrome.xml  (nuevo)
├── drawable/ic_stat_kitoru.xml          (nuevo)  silueta para las notificaciones
├── drawable/splash_background.xml       (nuevo)  para API < 31
├── mipmap-anydpi-v26/ic_launcher.xml    (nuevo)
├── mipmap-anydpi-v26/ic_launcher_round.xml (nuevo)
├── mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png  (nuevo)  fallback API 24-25
└── xml/backup_rules.xml                 (nuevo)
    xml/data_extraction_rules.xml        (nuevo)
    xml/network_security_config.xml      (nuevo)  (§13.6)
```

### 13.2 `:ui/src/androidMain` — la biblioteca de UI en Android

```
ui/src/androidMain/kotlin/kitoru/ui/platform/
├── FileSaver.android.kt                (ya existe — no se toca)
├── ThemeMode.android.kt                (nuevo)  isSystemInDarkTheme + color dinámico
├── BackHandler.android.kt              (nuevo)  el BackHandler + resolveBack
├── Insets.android.kt                   (nuevo)  rememberImePadding
├── NotificationPermission.android.kt   (nuevo)  pedir POST_NOTIFICATIONS
└── WindowSizeClass.android.kt          (nuevo, opcional)  si se necesita el tamaño exacto
```

### 13.3 `:ui/src/commonMain` — los cambios que Android obliga

| Archivo | Cambio | Por qué |
|---|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt` | **`scope` y `dispatcher` por constructor** | §1.4 |
| `ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt` | **`screen` → pila real** (`mutableStateListOf`) | §1.5 y §8 |
| `ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt` | **`snapshot()` / `restoreSnapshot()`** | §4.5 |
| `ui/src/commonMain/kotlin/kitoru/ui/nav/Back.kt` | **nuevo** — `BackDecision`, `BackState`, `resolveBack` | §8.2 |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/BackHandler.kt` | **nuevo** — el `expect` | §8.3 |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/ThemeMode.kt` | **nuevo** — `expect isSystemDarkTheme` + `rememberDarkTheme` | §9.2 |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/Insets.kt` | **nuevo** — `expect rememberImePadding` | §1.7 |
| `ui/src/commonMain/kotlin/kitoru/ui/KitoruApp.kt` | **nuevo** — el composable raíz (`KitoruApp(vm)`) | lo necesitan las dos plataformas |
| `ui/src/commonMain/kotlin/kitoru/ui/domain/Chat.kt` (en `:core`) | **`MessageStatus`** | §4.6 |

### 13.4 `:core`

| Archivo | Cambio |
|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt` | **`dataDir`/`cacheDir`/`outboxDir`/`modelsDir` con fail-fast + `setDataDir` con 4 parámetros** (§3.2) |
| `core/src/commonMain/kotlin/kitoru/platform/PlatformContext.kt` | **nuevo** (ya está diseñado en `01-local-android.md` §5.2) |
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | **`MessageStatus`** en `ChatMessage` (§4.6) |
| `core/src/commonMain/kotlin/kitoru/domain/AppConfig.kt` | **nuevo** — `theme`, `dynamicColor`, `notificationsEnabled` (§4.7) |
| `core/src/commonMain/kotlin/kitoru/domain/AppScreen.kt` (en `:ui`) | **`@Serializable`** en `AppScreen` para el snapshot (§4.5) |

### 13.5 `:llm-api`

| Archivo | Cambio |
|---|---|
| `llm-api/src/androidMain/kotlin/llm/api/LocalModelService.android.kt` | **ya existe** (stub). Cambiar a `createLocalModelService(ctx: PlatformContext)` según `01-local-android.md` §5.3 |
| `llm-api/src/androidMain/kotlin/llm/local/AndroidDeviceProfile.kt` | **nuevo** — RAM del dispositivo (§4.2 de 01-local-android) |

### 13.6 Dependencias nuevas

En `gradle/libs.versions.toml`:

```toml
[versions]
androidxCore = "1.15.0"
androidxWork = "2.10.0"
androidxSplash = "1.0.1"
androidxLifecycle = "2.8.7"      # ya existe

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "androidxSplash" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "androidxWork" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }  # ya existe
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "androidxLifecycle" }
```

En `android/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ui"))
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)   // ← nuevo: viewModel()
    implementation(libs.androidx.lifecycle.process)            // ← nuevo: ProcessLifecycleOwner
    implementation(libs.androidx.core.ktx)                      // ← nuevo: NotificationCompat
    implementation(libs.androidx.core.splashscreen)             // ← nuevo: installSplashScreen()
    implementation(libs.androidx.work.runtime.ktx)              // ← nuevo: WorkManager
}
```

### 13.7 La política de red (cleartext)

El modo remoto habla con el servidor de la cátedra por **HTTPS**. Si alguna
cátedra usa un servidor interno con HTTP, hace falta una excepción explícita
(si no, `usesCleartextTraffic` es `false` por default desde API 28):

```xml
<!-- android/src/main/res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <!-- Sólo para el servidor de la cátedra, si es HTTP en la red interna: -->
    <!-- <domain-config cleartextTrafficPermitted="true"> -->
    <!--     <domain includeSubdomains="true">campus.university.edu</domain> -->
    <!-- </domain-config> -->
</network-security-config>
```

### 13.8 El orden de trabajo

| # | Paso | Depende de | Effort |
|---|---|---|---|
| 1 | `android/build.gradle.kts` + `AndroidManifest.xml` + `MainActivity` mínimo + `KitoruApp` | El composable raíz de `:ui` | ½ día |
| 2 | `KitoruPaths` fail-fast + `KitoruPathsInitializer` + `KitoruServices` | — | ½ día |
| 3 | `KitoruStateHolder` + el `KitoruViewModel` con scope inyectado | El paso 1 | 1 día |
| 4 | Los canales de notificación + el permiso | — | ½ día |
| 5 | El `BackHandler` + `resolveBack` + la pila de navegación | El paso 3 | 1 día |
| 6 | `ThemeMode` + `isSystemInDarkTheme` + los temas XML + el splash | — | ½ día |
| 7 | `WorkManager`: `KitoruWorker` + `SyncWorker` + `WorkerScheduler` | El paso 2 | 1 día |
| 8 | `CredentialStore` (Keystore) | El paso 2 | 1 día |
| 9 | Los `Worker`s de descarga e indexado | El paso 7 | 2 días |
| 10 | `dataExtractionRules` + `backup_rules` | El paso 8 | ½ día |
| 11 | `MessageStatus` + el marcador de "respuesta interrumpida" | El paso 3 | 1 día |
| 12 | Los tests de instrumentación del `BackHandler` y de la persistencia | Los pasos 3 y 5 | 1 día |

**Total estimado: ~10 días** para una app Android que arranca, chatea contra el
servidor de la cátedra, guarda su configuración y no se rompe al rotar.

---

## 14. Resumen

1. **La Activity es un `ComponentActivity`** con `enableEdgeToEdge()` +
   `installSplashScreen()` + `setContent { }`; el `KitoruViewModel` plano se
   **envuelve en un `androidx.lifecycle.ViewModel`** (`KitoruStateHolder`) que
   lo sobrevive a la rotación y le da `viewModelScope` (por eso el VM tiene
   que recibir el `CoroutineScope` por constructor, como ya pide
   `teacher/01-navegacion.md` §5).
2. **Dos carpetas**: `filesDir/kitoru` para todo lo del usuario (config,
   chats, materiales, `Outbox`) y `getExternalFilesDir(null)/models` para los
   GGUF (2,4 GB no entran en la cuota de 25 MB de Auto Backup); la caché en
   `cacheDir/kitoru`; las exportaciones por SAF, sin permisos.
3. **`KitoruPaths` se inicializa en el `onCreate()` de la `Application`** (el
   primer código del proceso), es idempotente, y **falla rápido en español**
   si se usa antes — nada de un default `"."` que en Android escribe en la
   raíz del sistema de archivos.
4. **Dos niveles de persistencia**: `SavedStateHandle` para el estado de UI
   (pila de navegación, borrador) y `app-config.json` + repositorios para lo
   durable; el mensaje del asistente se persiste **completo**, y si el
   proceso muere a mitad del stream queda marcado `INTERRUMPIDA` con un botón
   "Reintentar".
5. **Cero permisos de almacenamiento**; el único runtime es
   `POST_NOTIFICATIONS`, y se pide **contextualmente** (al descargar un modelo
   o al activar la sincronización), nunca al arrancar; `WorkManager` para
   todo lo que corre en background, y **ningún `ForegroundService` en la v1**
   (la inferencia es foreground por definición).
6. El "atrás" se resuelve con una **función pura en `commonMain`**
   (`resolveBack`) y **un solo `BackHandler` en la raíz**; en el nivel raíz no
   se intercepta (la app va al background y el chat en curso sigue
   llegando); la app usa `BoxWithConstraints` (COMPACT/MEDIUM/EXPANDED), no
   bloquea la orientación, declara `configChanges` y usa `singleTask` para
   que no haya dos instancias escribiendo los mismos JSON.
7. `applicationId = com.funshiengine.kitoru` (con `.debug` para debug),
   versión desde `gradle.properties`, keystore **fuera del repo** (`~/.kitoru/`
   + `keystore.properties` gitignored + secreto en CI) y distribución por APK
   directo.
8. El inventario concreto está en §13: **11 archivos Kotlin** en `:android`
   (+ 4 subpaquetes), **5 archivos** en `:ui/src/androidMain`, **5 cambios**
   en `:ui/commonMain` + `:core`, **2 dependencias** nuevas
   (`core-splashscreen`, `work-runtime-ktx`) y ~10 días de trabajo.
