# Diseño de empaquetado, releases y CI/CD — Kitoru

> Subagente: *packaging / releases / CI-CD*.
> Documento de diseño. **No** contiene código de producción: sólo firmas,
> nombres de archivos, DSL de Gradle y YAML de workflows.
> Referencias: `docs/design/CONTEXTO.md`, `build.gradle.kts`, `settings.gradle.kts`,
> `gradle/libs.versions.toml`, `ui/build.gradle.kts`, y el patrón de
> `FunshiEngineAI/frontend/build.gradle.kts` (`compose.desktop` con
> `Dmg`/`Msi`/`Deb`).

---

## 0. Resumen ejecutivo (decisiones)

| # | Decisión | Motivo |
|---|---|---|
| D1 | **El bloque `compose.desktop.application` vive en `:desktop`, no en `:ui`** | Hoy está en `ui/build.gradle.kts` apuntando a `com.funshiengine.kitoru.desktop.MainKt`, una clase que **no existe en `:ui`**: el `mainClass` no se puede resolver y `run`/`package*` quedan rotos. Además `:ui` tiene `androidTarget`, así que un `application` KMP con target Android es una mezcla que el plugin de Compose no soporta limpiamente. `:desktop` será un módulo `kotlin("jvm")` (como el `frontend/` de FunshiEngineAI) y ahí va todo el packaging de escritorio. |
| D2 | **Una sola versión, en `gradle.properties`** (`kitoru.version` + `kitoru.versionCode`) | Es la fuente de verdad para el `packageVersion` de jpackage, el `versionName`/`versionCode` de Android, la pantalla "Acerca de" y el tag de GitHub. Nunca se escribe la versión a mano en dos lugares. |
| D3 | **`0.1.0` durante el desarrollo**, semver estricto | `MAJOR.MINOR.PATCH`. `0.x` = inestable y cambia; al liberar a un claustro se pasa a `1.0.0`. |
| D4 | **macOS exige `MAJOR > 0`**: el `.dmg` se versiona `1.<minor>.<patch>` | Verificado en el código del plugin (`MacVersionChecker`: `parts.first() > 0`): con `packageVersion = "0.1.0"` el build **falla en tiempo de configuración** con *"Illegal version for 'Dmg'"*. Se resuelve con `macOS.packageVersion` explícito, sin tocar la versión de la app. |
| D5 | **macOS exige `packageBuildVersion`**: se setea con `versionCode` | `validatePackageVersions` exige build version para todo target de macOS; sin eso el build falla. |
| D6 | **El `.msi` lleva un `upgradeUuid` fijo** en `gradle.properties` | Es el GUID del producto MSI. Si cambia entre versiones, el instalador **no reemplaza** al anterior: queda la app vieja instalada. Se genera una vez con `uuidgen` y se versiona en el repo. |
| D7 | **Android se distribuye como APK directo (sideloading)**, no en Play Store | Cuenta de developer (USD 25), revisión que puede rechazar la app, plazos de días y políticas que chocan con el uso de claves de la institución. Un APK en la release de GitHub + actualización manual es lo que necesita una app de facultad. Justificación completa en §4.6. |
| D8 | **El keystore de release vive FUERA del repo** (`~/.kitoru/kitoru-release.jks`), y en CI se reconstruye desde un secreto | Un keystore en el repo es una firma pública: cualquiera puede publicar una actualización que reemplace la app. Se guarda cifrado en GitHub Secrets (`KITORU_KEYSTORE_BASE64`) y en dos respaldos offline del dueño del proyecto. |
| D9 | **`schemaVersion` en `config/app-config.json`**, con cadena de migraciones | Si una app vieja abre datos nuevos, la app **avisa "actualizá la app"** y **no escribe nada** (no corrompe). Si una app nueva abre datos viejos, migra sola. |
| D10 | **El modelo GGUF se descarga de Hugging Face en el primer uso** (escritorio), nunca se empaqueta | Un `.gguf` son GB: no puede ir dentro del `.msi`/`.dmg`. Se porta el `HuggingFaceDownloader` de FunshiEngineAI (`.part` + `Range` + verificación de integridad). En **Android el modelo local no existe** (`LocalModelService.android.kt` es un stub): el alumno usa el modo REMOTE. |
| D11 | **CI en GitHub Actions**: `ci.yml` (Windows + Linux + Android) y `release.yml` (por tag). **Nada de Docker** | Un contenedor no aporta nada a una app de escritorio que se instala con un `.msi`/`.dmg`/`.deb`. Lo que necesita el build es el JDK 21, el SDK de Android y `fakeroot`, que los runners ya traen o se instalan en un paso. |
| D12 | **El `.dmg` no se compila en cada PR** | Los runners de macOS cuestan 10×. El job de macOS corre en `push` a `main`, en `workflow_dispatch` y en el release; los PR sólo corren Windows + Linux + Android. |

---

## 1. Dónde vive cada cosa

### 1.1. Mapa de módulos y su rol en el build

```
:core      KMP (jvm "desktop" + androidTarget)  →  dominio + plataforma
:llm-api   KMP                                    →  gateway LLM, descarga del modelo
:backend   KMP                                    →  RAG + agente
:ui        KMP (jvm "desktop" + androidTarget)  →  Compose Multiplatform (biblioteca)
:desktop   kotlin("jvm") + compose.desktop       →  MAIN DESKTOP + PACKAGING  ← el app
:android   com.android.application + kotlin-android →  APK                    ← el app
```

`:ui` es **biblioteca**: no tiene `mainClass`, no tiene `application`, no genera
`run` ni `package*`. Por eso el bloque `compose.desktop { application { … } }`
que hoy está en `ui/build.gradle.kts` **se mueve tal cual** a `desktop/build.gradle.kts`
(menos el `mainClass`, que ahora sí existe en su propio módulo).

> **Por qué `:desktop` es `kotlin("jvm")` y no KMP:** el plugin de Compose
> Desktop arma la app desde el `SourceSet` `main` de un módulo JVM, o desde un
> target KMP JVM (`configureDefaultApp()` en `JvmApplicationContext.kt`). Con un
> módulo `kotlin("jvm")` el `mainClass` y el classpath de runtime son inequívocos
> y `jpackage` los empaqueta sin ambigüedad. Es exactamente el patrón de
> `FunshiEngineAI/frontend` (que también es `kotlin("jvm")`).

**Resolución de variantes `:desktop` → `:ui`:** `:ui` publica la variante `desktop`
(jvm) vía Kotlin Multiplatform. El consumidor `kotlin("jvm")` pide
`org.jetbrains.kotlin.platform.type=jvm` y `org.gradle.jvm.environment=standard-jvm`:
coincide con lo que publica `jvm("desktop")`. Si en algún momento Gradle se
queja (*"Could not find … org.jetbrains.kotlin.platform.type=jvm"*), el arreglo
explícito en `desktop/build.gradle.kts` es:

```kotlin
dependencies {
    implementation(project(":ui")) {
        attributes {
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }
}
```

### 1.2. Archivos nuevos que introduce este diseño

```
gradle.properties                          (+ kitoru.version, kitoru.versionCode, kitoru.windows.upgradeUuid)
gradle/libs.versions.toml                  (+ kotlinAndroid)
desktop/build.gradle.kts                   (nuevo: módulo de la app de escritorio)
desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt
desktop/icons/kitoru.ico                   (ícono Windows)
desktop/icons/kitoru.png                   (ícono Linux)
desktop/icons/kitoru.icns                  (ícono macOS)
android/build.gradle.kts                   (nuevo: módulo de la app Android)
android/src/main/AndroidManifest.xml
android/src/main/kotlin/com/funshiengine/kitoru/MainActivity.kt
android/src/main/res/values/strings.xml
android/src/main/res/values/themes.xml
android/src/main/res/values/ic_launcher_background.xml
android/src/main/res/drawable/ic_launcher_foreground.xml
android/src/main/res/drawable/ic_launcher_monochrome.xml
android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
android/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
android/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png   (fallback API < 26)
.github/workflows/ci.yml
.github/workflows/release.yml
keystore.properties                        (NO se versiona: está en .gitignore)
README.md                                  (nuevo)
docs/MANUAL_DE_USO.md                      (nuevo)
docs/ARQUITECTURA.md                       (nuevo)
docs/TESTING.md                            (nuevo)
docs/CHANGELOG.md                          (nuevo)
docs/capturas/*.png                        (nuevas)
```

---

## 2. El pipeline de build

### 2.1. Propiedades nuevas

`gradle.properties`:

```properties
# --- Versión (fuente única de verdad) -------------------------------------
# Semver. Durante el desarrollo: 0.1.0, 0.2.0, … La release la decide el
# maintainer y el tag de GitHub tiene que coincidir con este valor.
kitoru.version=0.1.0
# Entero monotónico que usa Android (versionCode) y el build number de macOS.
# Sube en cada release; NUNCA baja.
kitoru.versionCode=1
# GUID del producto MSI. Fijo para siempre: si cambia, el instalador nuevo
# convive con el viejo en vez de reemplazarlo. Generar con:
#   uuidgen   (macOS/Linux)   |   [guid]::NewGuid()   (PowerShell)
kitoru.windows.upgradeUuid=B7F3C1A2-5D64-4E9B-9C08-3A7E1D6F4B25
```

`gradle/libs.versions.toml` (agenda):

```toml
[plugins]
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### 2.2. Los atajos

| Atajo | Comando | Qué corre por debajo | Tiempo (estimado) |
|---|---|---|---|
| **Todo** | `./gradlew build` | `:core:assemble` `:llm-api:assemble` `:backend:assemble` `:ui:assemble` `:desktop:assemble` `:android:assembleDebug` + **todos los `check`** (tests desktop + tests unitarios Android) + **Android Lint** | 3–6 min en frío · 45–90 s incremental |
| **Tests (los dos targets)** | `./gradlew check` | `:core:desktopTest` `:core:testDebugUnitTest` `:llm-api:desktopTest` `:llm-api:testDebugUnitTest` `:backend:desktopTest` `:backend:testDebugUnitTest` `:ui:desktopTest` `:ui:testDebugUnitTest` + Lint | 30–90 s |
| **App de escritorio** | `./gradlew run` | `:desktop:run` (`JavaExec` con el classpath de `main`) | 20–40 s la 1ª vez · 5–10 s después |
| **App de escritorio "empaquetada"** | `./gradlew :desktop:runDistributable` | `jpackage --type app-image` → corre desde `build/compose/binaries/<nombre>/app/` | 2–4 min (crea el runtime embebido) |
| **Android en el dispositivo** | `./gradlew :android:installDebug` | compila + `adb install -r` (requiere un device/emulador conectado y `adb` en el PATH) | 2–4 min la 1ª vez · 20 s después |
| **Tests Android** | `./gradlew :android:testDebugUnitTest` | tests unitarios JVM del módulo Android | 30–60 s |
| **Paquete de escritorio** | `./gradlew :desktop:packageDistributionForCurrentOS` | `jpackage` con runtime embebido → `.msi` / `.dmg` / `.deb` según el SO | 2–5 min |
| **APK de release** | `./gradlew :android:assembleRelease` | compila + firma con el keystore | 3–5 min |

**Notas de exactitud sobre los atajos:**

* `./gradlew run` funciona porque Gradle busca la tarea `run` en el proyecto raíz
  **y en todos los subproyectos**. Como el bloque `compose.desktop.application`
  se mueve a `:desktop` (§D1), **sólo `:desktop` tiene `run`**: no hay
  ambigüedad ni dos ventanas abiertas.
* `./gradlew check` corre `check` en todos los módulos. En los módulos KMP con
  target Android, la tarea de tests unitarios es la de AGP
  (`testDebugUnitTest`), no `androidUnitTest` (esa es sólo el *source set*).
* `build` **no** genera instaladores: empaquetar es una tarea aparte
  (`packageDistributionForCurrentOS`), porque jpackage es lento y dependiente
  del SO.
* `build` **sí** corre Android Lint (AGP mete `lint` en `check`). Es
  desirable, pero si molesta: `./gradlew build -x lint`.
* Los tiempos son **estimaciones** sobre un equipo de desarrollo promedio
  (8 núcleos, SSD). Medir de verdad: `./gradlew build --profile` y el reporte
  `build/reports/profile/profile-*.html`; en CI, el resumen de tiempos de
  `gradle/actions/setup-gradle`.

### 2.3. El grafo de tareas (orden real)

```
                        ┌─────────────────────────────┐
                        │  :core:jvmJar / :core:androidRelease AAR
                        └──────────────┬──────────────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  ▼                    ▼                    ▼
        :llm-api:jvmJar        :llm-api:android…    (sin dependencia entre sí)
                  │
                  ▼
        :backend:jvmJar
                  │
                  ▼
        :ui:jvmJar ──────────────┐
        :ui:androidRelease AAR   │
                  │              │
                  ▼              ▼
      :desktop:jar            :android:assembleDebug
      :desktop:run                 │
      :desktop:packageMsi/Dmg/Deb  │
      (jpackage: jlink + jpackage)  │
                                     ▼
                          :android:assembleRelease
                          (apksigner + zipalign)
```

* `jpackage` internally: `createRuntimeImage` (jlink con la lista `modules`) →
  `prepareAppResources` (copia de los jars al sandbox) → `jpackage` → instalador.
* El runtime embebido sale del **mismo JDK que corre Gradle** (JDK 21), recortado
  con `jlink` según `nativeDistributions.modules`.

### 2.4. Atajos extra que conviene tener

En `desktop/build.gradle.kts` y `android/build.gradle.kts` se pueden registrar
tareas de conveniencia. Propuesta (root `build.gradle.kts`):

```kotlin
// Tarea agregada: todo lo que hay que compilar y testear, en un solo comando.
tasks.register("verify") {
    group = "verification"
    description = "Compila los dos targets y corre todos los tests (desktop + Android)."
    dependsOn(
        ":core:desktopTest", ":core:testDebugUnitTest",
        ":llm-api:desktopTest", ":llm-api:testDebugUnitTest",
        ":backend:desktopTest", ":backend:testDebugUnitTest",
        ":ui:desktopTest", ":ui:testDebugUnitTest",
        ":desktop:assemble", ":android:assembleDebug",
    )
}
```

Y en el `README`, una tabla "comandos" con estos atajos.

---

## 3. Empaquetado de escritorio

### 3.1. `desktop/build.gradle.kts` (completo)

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
}

// Versión: una sola fuente de verdad (gradle.properties).
val kitoruVersion: String = providers.gradleProperty("kitoru.version").get()
val kitoruVersionCode: Int = providers.gradleProperty("kitoru.versionCode").get().toInt()
val windowsUpgradeUuid: String = providers.gradleProperty("kitoru.windows.upgradeUuid").get()

// macOS: jpackage exige MAJOR > 0 y un "build version".
// "0.1.0" -> "1.1.0": se sube sólo el major (que en 0.x no significa nada)
// y se preservan minor y patch. La app sigue siendo 0.1.0.
val macSafeVersion: String = run {
    val parts = kitoruVersion.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size == 3 && parts[0] == 0) "1.${parts[1]}.${parts[2]}" else kitoruVersion
}

compose.desktop {
    application {
        mainClass = "com.funshiengine.kitoru.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // --- Metadatos del instalador (van a --name/--description/--vendor/
            //     --copyright/--app-version de jpackage) -----------------------
            packageName = "Kitoru"
            packageVersion = kitoruVersion          // 0.1.0
            description = "Kitoru — IA para cátedras: chats con el material de la cursada."
            vendor = "Nootechnia"
            copyright = "© 2026 Nootechnia"

            // Módulos del runtime embebido (jlink). Los 4 primeros son los que
            // usa Compose por defecto; jdk.crypto.ec hace falta para TLS con
            // certificados EC (Hugging Face, gateways TLS).
            modules = listOf(
                "java.base",
                "java.desktop",     // AWT/Swing: lo necesita Compose Desktop
                "java.logging",
                "java.management",   // métricas del proceso
                "java.net.http",    // por si el gateway usa HttpClient del JDK
                "jdk.crypto.ec",    // TLS (EC)
                "jdk.unsupported",  // Skiko usa sun.misc.Unsafe
            )

            // --- Windows (.msi) ---------------------------------------------
            windows {
                iconFile.set(project.file("icons/kitoru.ico"))
                upgradeUuid = windowsUpgradeUuid   // GUID FIJO (ver D6)
                perUserInstall = true              // instala en el perfil del usuario: sin UAC
                dirChooser = true                  // el alumno elige la carpeta
                shortcut = true                    // acceso directo en el escritorio
                menu = true                        // entrada en el menú Inicio
                menuGroup = "Kitoru"               // subcarpeta del menú Inicio
                console = false                    // app de ventana, no de consola
            }

            // --- macOS (.dmg) -------------------------------------------------
            macOS {
                iconFile.set(project.file("icons/kitoru.icns"))
                bundleID = "com.funshiengine.kitoru"
                packageVersion = macSafeVersion     // "1.1.0" mientras la app sea 0.1.0
                packageBuildVersion = kitoruVersionCode.toString()   // obligatorio en macOS
                dockName = "Kitoru"
                appCategory = "public.app-category.education"
                minimumSystemVersion = "11.0"
                // Firma y notarización: opcionales en 0.x, obligatorias para 1.0.
                // signing { identity = "Developer ID Application: TU ORGANIZACION (ABC123)" }
                // notarization { appleId = "...", password = "...", teamId = "..." }
            }

            // --- Linux (.deb) ---------------------------------------------------
            linux {
                iconFile.set(project.file("icons/kitoru.png"))
                packageName = "kitoru"              // nombre del paquete .deb (minúscula)
                shortcut = true
                menuGroup = "Kitoru"
                appCategory = "Development"          // o "Education"
                debMaintainer = "Nootechnia <soporte@nootechnia.dev>"
                appRelease = kitoruVersionCode.toString()
            }
        }
    }
}
```

### 3.2. Requisitos de cada formato (verificados contra el plugin 1.7.3)

| Formato | SO que lo puede generar | Requisitos | Salida |
|---|---|---|---|
| **`.msi`** | **sólo Windows** | JDK 21 con `jpackage` y `jlink`; **WiX 3** (el plugin lo descarga solo a `~/.gradle/compose-jb/wix311`; para controlarlo, `WIX_PATH` apuntando a la carpeta de WiX o `compose.desktop.application.downloadWix=false`) | `desktop/build/compose/binaries/<app>/msi/Kitoru-<ver>.msi` |
| **`.dmg`** | **sólo macOS** | JDK 21; Xcode Command Line Tools (`xcrun`, `hdiutil`) | `…/dmg/Kitoru-<ver>.dmg` |
| **`.deb`** | **sólo Linux** | JDK 21; **`fakeroot`** (jpackage lo necesita para construir `.deb`; en Debian/Ubuntu: `apt install fakeroot`) | `…/deb/kitoru_<ver>_amd64.deb` |

Además, para los tres:

* **`mainClass` obligatorio y real**: `com.funshiengine.kitoru.desktop.MainKt` en el
  módulo `:desktop`. Si la clase no existe o no tiene `fun main`, jpackage
  falla al empaquetar.
* **Un JDK, no un JRE**: `jpackage` viene con el JDK completo (`java.home` del
  proceso de Gradle). El plugin usa `javaHome` = el JDK que corre Gradle; se
  puede fijar con `compose.desktop.application { javaHome = "…" }`.
* **Los targets incompatibles se deshabilitan solos**: el plugin pone
  `packageTask.enabled = targetFormat.isCompatibleWithCurrentOS`. O sea que
  en Windows `packageDeb` y `packageDmg` existen pero están *disabled* y
  `packageDistributionForCurrentOS` corre sólo el que corresponde.
  **Esto se verificó en el código** (`configureJvmApplication.kt:293`).
* **jpackage no hace cross-build**: el `.msi` se genera en un runner Windows,
  el `.dmg` en un runner macOS, el `.deb` en un runner Linux. Por eso el
  release tiene **tres jobs**.

### 3.3. Validaciones de versión que impone el plugin (y cómo las satisfacemos)

`validatePackageVersions()` corre **en tiempo de configuración** sobre **todos**
los formatos declarados (aunque el SO actual no los soporte). Con
`targetFormats(Dmg, Msi, Deb)` hay que que las tres versiones sean válidas
**siempre**:

| Formato | Regla | `0.1.0` | Qué hacemos |
|---|---|---|---|
| `.msi` | `MAJOR.MINOR.BUILD`, cada parte ≤ 255 / 255 / 65535 | ✅ | `packageVersion = "0.1.0"` |
| `.deb` | `[EPOCH:]VERSION[-REVISION]`, empieza con dígito | ✅ | `packageVersion = "0.1.0"` |
| `.dmg` | `MAJOR[.MINOR][.PATCH]`, **MAJOR > 0** | ❌ **falla** | `macOS.packageVersion = macSafeVersion` → `"1.1.0"` (de `0.1.0`) |
| `.dmg` | exige además `packageBuildVersion` | ❌ **falla** | `macOS.packageBuildVersion = versionCode` |

Precedencia (verificada en `packageVersions.kt`):
`formato específico` → `SO específico` → `packageVersion` global.

### 3.4. El GUID del `.msi`

* Se genera **una vez**:
  * macOS/Linux: `uuidgen`
  * Windows (PowerShell): `[guid]::NewGuid().ToString().ToUpper()`
  * Linux sin `uuidgen`: `cat /proc/sys/kernel/random/uuid`
* Se copia en `gradle.properties` como `kitoru.windows.upgradeUuid` y **se
  versiona en el repo**: no es un secreto (el ProductCode del MSI es público),
  pero debe ser **constante** para siempre.
* Se pasa a jpackage como `--win-upgrade-uuid`. Con el mismo UUID, el `.msi`
  nuevo **actualiza** el anterior en lugar de instalarse al lado.
* Ejemplo (a reemplazar por uno generado con `uuidgen`): `B7F3C1A2-5D64-4E9B-9C08-3A7E1D6F4B25`.

### 3.5. Íconos

Un único **maestro vectorial** en `assets/branding/kitoru.svg` (512×512) y
exportaciones:

| Destino | Archivo | Cómo se genera |
|---|---|---|
| Ventana de la app (desktop) | `desktop/composeResources/drawable/ic_kitoru.png` (512×512) | del SVG |
| Instalador Windows | `desktop/icons/kitoru.ico` (256, 128, 64, 48, 32, 16) | ImageMagick / Inkscape |
| Instalador Linux | `desktop/icons/kitoru.png` (512×512) | del SVG |
| Instalador macOS | `desktop/icons/kitoru.icns` (16→1024) | `iconutil` / ImageMagick |

El ícono de la **ventana** se usa en `Main.kt`:

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt
fun main() = application {
    val icon = painterResource("/ic_kitoru.png")   // composeResources del módulo
    Window(onCloseRequest = ::exitApplication, title = "Kitoru", icon = icon) {
        KitoruApp()
    }
}
```

> `nativeDistributions.<plataforma>.iconFile` **siempre** hay que setearlo: si
> no, el plugin usa el ícono por defecto de Compose (el cuadrado gris), que
> es lo que hoy se vería en los instaladores.

### 3.6. Qué pasa con `llama-server` (el motor local)

El `.msi`/`.dmg`/`.deb` **no** incluye `llama-server` (son cientos de MB y
dependen de la CPU). La estrategia:

1. El modelo GGUF **sí** se descarga (ver §7) desde Hugging Face.
2. `llama-server` se detecta en el `PATH`; si no está, la app lo dice con una
   guía (igual que hace FunshiEngineAI con `LlamaManager.requireTool`).
3. En el modo **REMOTE** (servidor de la cátedra), que es el modo por defecto,
   no hace falta nada de esto: el alumno sólo necesita la URL y la API key.

---

## 4. El APK de Android

### 4.1. `android/build.gradle.kts` (completo)

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

// --- Versión (misma fuente que el escritorio) -------------------------------
// OJO: se llaman kitoruVersion/kitoruVersionCode y no version/versionCode
// porque dentro de `defaultConfig { }` un `versionCode` a secas se resolvería
// contra la propiedad del propio AGP (auto-asignación) y no contra la local.
val kitoruVersion: String = providers.gradleProperty("kitoru.version").get()
val kitoruVersionCode: Int = providers.gradleProperty("kitoru.versionCode").get().toInt()

// --- Firma: primero las variables de entorno (CI), después keystore.properties ---
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun secret(env: String, key: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(key))?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("KITORU_KEYSTORE_FILE", "storeFile")
val releaseStorePass = secret("KITORU_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = secret("KITORU_KEY_ALIAS", "keyAlias")
val releaseKeyPass = secret("KITORU_KEY_PASSWORD", "keyPassword")
val hasReleaseKeystore = listOf(
    releaseStoreFile, releaseStorePass, releaseKeyAlias, releaseKeyPass
).all { it != null }

android {
    namespace = "com.funshiengine.kitoru"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.funshiengine.kitoru"
        minSdk = 24          // Android 7.0
        targetSdk = 35       // Android 15
        versionCode = kitoruVersionCode
        versionName = kitoruVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        debug {
            // Se puede instalar al lado de la release: el APPLICATION_ID es distinto.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Firma con el keystore de debug que genera el propio Android Studio
            // (~/.android/debug.keystore). No requiere ningún secreto.
        }
        release {
            isMinifyEnabled = false          // R8/ProGuard: apagado en 0.x (Kotlin
            isShrinkResources = false        // Serialization + reflexión: riesgo)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

// Tarea de guarda: si alguien pide assembleRelease sin keystore, que el error
// sea claro en vez de producir un APK sin firmar o firmado con la clave de debug.
val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verifica que el keystore de release esté configurado."
    doLast {
        if (!hasReleaseKeystore) {
            throw GradleException(
                "Falta el keystore de release.\n" +
                "  Local: creá 'keystore.properties' en la raíz del repo (NO se versiona) con\n" +
                "    storeFile=/ruta/absoluta/kitoru-release.jks\n" +
                "    storePassword=…\n" +
                "    keyAlias=kitoru\n" +
                "    keyPassword=…\n" +
                "  CI: exportá KITORU_KEYSTORE_FILE, KITORU_KEYSTORE_PASSWORD,\n" +
                "       KITORU_KEY_ALIAS y KITORU_KEY_PASSWORD."
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(validateReleaseSigning) }
```

### 4.2. `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- La app habla con el servidor de la cátedra (HTTPS) y, si el alumno
         usa IA local, con Hugging Face. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Kitoru">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode|density|fontScale"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.Kitoru">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### 4.3. El ícono adaptativo

**`res/mipmap-anydpi-v26/ic_launcher.xml`** (y `ic_launcher_round.xml`, idéntico):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

**`res/values/ic_launcher_background.xml`**:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0E5C4A</color>
</resources>
```

**`res/drawable/ic_launcher_foreground.xml`** — vector 108×108 `dp`, con el logo
centrado dentro de la **zona segura de 72×72 dp** (el resto lo recorta el
launcher):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- El glifo va entre x=18..90 y y=18..90 -->
    <path android:fillColor="#FFFFFF"
        android:pathData="M54,26 L78,38 L78,58 C78,72 67,80 54,84 C41,80 30,72 30,58 L30,38 Z" />
    <path android:fillColor="#0E5C4A"
        android:pathData="M46,46 L48.5,46 L48.5,62 L46,62 Z M53,46 L55.5,46 L55.5,62 L53,62 Z M60,46 L62.5,46 L62.5,62 L60,62 Z" />
</vector>
```

**`res/drawable/ic_launcher_monochrome.xml`** — la misma silueta en un solo
color (íconos temáticos de Android 13+).

**Fallbacks para API < 26** (`res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`,
48/72/96/144/192 px): PNG del logo sobre el fondo `#0E5C4A`, con la misma
composición (foreground + background).

Además:
* `res/values/strings.xml`: `<string name="app_name">Kitoru</string>`
* `res/values/themes.xml`:
  ```xml
  <style name="Theme.Kitoru" parent="android:Theme.Material.Light.NoActionBar">
      <item name="android:windowBackground">@android:color/white</item>
  </style>
  ```
  (La app dibuja todo con Compose; el tema del sistema sólo evita la barra de
  acción y el fondo parpadea al arrancar.)

### 4.4. Proceso de firma

| | Debug | Release |
|---|---|---|
| Keystore | `~/.android/debug.keystore`, lo genera el propio SDK | `~/.kitoru/kitoru-release.jks`, **generado a mano** |
| Alias | `androiddebugkey` | `kitoru` |
| Contraseña | `android` | **secreto**, nunca en el repo |
| Comando | `./gradlew :android:assembleDebug` | `./gradlew :android:assembleRelease` |
| `applicationId` | `com.funshiengine.kitoru.debug` | `com.funshiengine.kitoru` |
| Se puede instalar junto a la release | ✅ | — |
| Se puede actualizar entre versiones | ✅ (misma clave de debug) | ✅ (misma clave de release) |

**Generación del keystore de release** (una sola vez, en la máquina del
mantainer):

```bash
keytool -genkeypair -v \
  -keystore ~/.kitoru/kitoru-release.jks \
  -alias kitoru \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12 \
  -dname "CN=Kitoru, OU=Nootechnia, O=Nootechnia, L=Buenos Aires, C=AR"
```

`validity 10950` = 30 años (Android exige ≥ 25 años para que las actualizaciones
automáticas de Play funcionen; como no usamos Play, 30 años es sólo prudencia).

**Dónde vive el keystore:**

1. **Nunca** en el repo. `.gitignore` agrega (además de lo que ya tiene):
   ```gitignore
   # Firma de release: NUNCA en el repo.
   keystore.properties
   *.jks
   *.keystore
   ```
   Y conviene una regla defensiva: que un `.jks` que alguien meta por error
   sea rechazado por el build (`tasks.matching { it.name.contains("Release") }`),
   además de un *pre-commit* con `git-secrets` o `detect-secrets`.
2. En la máquina del mantainer: `~/.kitoru/kitoru-release.jks` (fuera del
   proyecto) + `keystore.properties` en la raíz del repo (gitignored) que
   apunta a él:
   ```properties
   storeFile=C:/Users/gianf/.kitoru/kitoru-release.jks
   storePassword=…
   keyAlias=kitoru
   keyPassword=…
   ```
3. En CI: **nunca** se sube el `.jks`. Se guarda el **base64** en un secreto de
   GitHub (`KITORU_KEYSTORE_BASE64`) y el workflow lo reconstruye en el runner
   (§6.2).
4. **Respaldo**: el `.jks` + las 4 contraseñas se guardan cifrados en un
   password manager del proyecto (y una copia offline). Si se pierde el
   keystore, las instalaciones nuevas **no pueden actualizar** las viejas: el
   alumno tiene que desinstalar (y pierde los datos locales). Por eso la
   pantalla "Acerca de" avisa si detecta que la app fue instalada con una
   firma distinta… (en la práctica, Android mismo bloquea la actualización y
   lo explica).

**Verificación de la firma en CI:**

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  android/build/outputs/apk/release/android-release.apk
```

### 4.5. `versionCode` / `versionName`

* `versionName` = `kitoru.version` (semver, se muestra al usuario).
* `versionCode` = `kitoru.versionCode` (entero, **monotónico**; Android
  rechaza un APK con `versionCode` menor o igual al instalado).
* En el `.apk` el `versionName` visible es `0.1.0`; el `versionCode` es `1`.
* El `.desktop` y el `.apk` **deben** llevar la misma `versionName`: es lo que
  permite un mensaje único de "hay una versión nueva" y un `CHANGELOG` único.
* El CI **verifica** que el tag coincida:
  `v0.1.0` ⇒ `kitoru.version=0.1.0` y `kitoru.versionCode` mayor que el del
  tag anterior. Si no coinciden, el release falla.

### 4.6. Play Store vs APK directo — decisión y justificación

**Decisión: APK directo (sideloading), publicado en la release de GitHub.**

| Criterio | Play Store | APK directo (elegido) |
|---|---|---|
| Cuenta de developer | USD 25/año + verificación de identidad | 0 |
| Revisión | Días a semanas; rechazo probable (la app guarda API keys de una institución, contenido académico) | Ninguna |
| Distribución a un aula | Google Play Classroom / link | **Un link + un QR en el aula** |
| Actualización | Automática (Play) | Manual: el usuario baja el `.apk` nuevo e instala (Android permite actualizar si la firma es la misma) |
| Métricas | Panel de Play | Las que mida el servidor de la institución |
| Riesgo de firma | Verificación de firma de app (Play App Signing) | Una sola clave: si se pierde, hay que reinstalar |
| Privacidad | Play recopila telemetría; las apps educativas tienen requisitos extra | La app **no** manda telemetría a nadie: todo queda en el dispositivo o en el servidor de la institución |

**Cómo se resuelve la actualización manual:**

1. La app (escritorio y Android) consulta **a pedido** (un botón "Buscar
   actualizaciones" en "Acerca de"; y como máximo una vez al día al arrancar)
   la API de GitHub:
   `GET https://api.github.com/repos/Nootechnia/Kitoru/releases/latest`
   → `{ "tag_name": "v0.2.0", "html_url": "…", "assets": [ … ] }`.
   Límite de la API sin token: 60 requests/h por IP → alcanza de sobra.
2. Se compara `tag_name` con `BuildInfo.VERSION_NAME` por semver.
3. Si hay versión nueva: diálogo **"Hay una versión nueva (0.2.0)"** con el
   changelog y un botón **"Descargar"** que abre el navegador (escritorio) o
   un `Intent.ACTION_VIEW` (Android) sobre la URL del `.apk` o de la release.
4. El APK nuevo **reemplaza** al viejo sin perder datos (misma firma, y los
   datos viven en `context.filesDir/kitoru`).
5. Si la versión instalada es **más nueva** que la del repo (instalación manual
   de un APK de prueba), la app lo dice: "Estás usando una versión de
   desarrollo (0.2.0-beta). Las actualizaciones automáticas están desactivadas."

**Dónde vive el endpoint de updates:** una constante en `:core`
(`kitoru.platform.UpdateEndpoint`), para poder cambiarla (por ejemplo, apuntar
a un mirror propio de la facultad) sin recompilar la app entera.

---

## 5. Versionado

### 5.1. Semver

| Parte | Significado | Ejemplo |
|---|---|---|
| `MAJOR` | Cambio incompatible de comportamiento o de formato de datos | `1.0.0` |
| `MINOR` | Funcionalidad nueva compatible | `0.2.0` |
| `PATCH` | Correcciones | `0.1.1` |

Durante el desarrollo: `0.1.0` → `0.2.0` → … → `1.0.0` (primera release para
un claustro real). Pre-release: `0.2.0-rc1` en el **nombre del tag**
(`v0.2.0-rc1`) pero **nunca** en `kitoru.version` si se va a empaquetar en un
`.msi`/`.dmg` (el validador de Windows exige `MAJOR.MINOR.BUILD` numérico y el
de macOS también). Para las pre-releases se sube el `versionCode` y se publica
el APK con `versionName = "0.2.0"` + un sufijo en el nombre del archivo
(`kitoru-0.2.0-rc1.apk`).

### 5.2. `BuildInfo` (la versión dentro de la app)

Se **genera** en `:core` para que los dos targets muestren el mismo número.

`core/build.gradle.kts` (agenda):

```kotlin
// Genera core/src/commonMain/kotlin/kitoru/platform/BuildInfo.kt
val generateBuildInfo by tasks.registering {
    // Se llaman kitoruVersion/kitoruVersionCode para no pisar `project.version`.
    val kitoruVersion = providers.gradleProperty("kitoru.version").get()
    val kitoruVersionCode = providers.gradleProperty("kitoru.versionCode").get()
    val commit = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifBlank { "desconocido" }
    val builtAt = providers.provider { java.time.Instant.now().toString() }

    val outputDir = layout.buildDirectory.dir("generated/kitoru/commonMain/kotlin")
    val outputFile = outputDir.map { it.file("kitoru/platform/BuildInfo.kt") }

    inputs.property("version", kitoruVersion)
    inputs.property("versionCode", kitoruVersionCode)
    outputs.file(outputFile)

    doLast {
        val f = outputFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            package kitoru.platform

            /** Datos de build. Generado por Gradle: NO editar a mano. */
            object BuildInfo {
                const val VERSION_NAME: String = "$kitoruVersion"
                const val VERSION_CODE: Int = $kitoruVersionCode
                const val COMMIT: String = "$commit"
                const val BUILT_AT: String = "$builtAt"

                /** "0.1.0 (1)" — como lo ve el usuario. */
                val VERSION_LABEL: String get() = "$kitoruVersion ($kitoruVersionCode)"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateBuildInfo)
```

> `kitoru.platform.BuildInfo` queda en `commonMain`, o sea que **desktop y
> Android muestran exactamente la misma versión**. En Android se puede
> contrastar contra `PackageManager.getPackageInfo(...)` (que devuelve el
> `versionName` real del APK); si difieren, manda el del paquete.

### 5.3. La pantalla "Acerca de"

`ui/src/commonMain/kotlin/kitoru/ui/settings/AboutScreen.kt`:

```
┌────────────────────────────────────────────┐
│  [logo]   Kitoru                           │
│           Asistente de cátedras            │
│                                            │
│  Versión            0.1.0 (1)              │
│  Commit             a1b2c3d                │
│  Compilado          2026-10-21T12:00:00Z   │
│  Esquema de datos   v1                     │
│  Java               21.0.5 (Temurin)       │
│                                            │
│  [ Buscar actualizaciones ]                │
│  Hay una versión nueva: 0.2.0  [Descargar] │
│                                            │
│  ▸ Licencias (MIT / Apache-2.0)            │
│  ▸ Manual de uso                           │
│  ▸ Carpeta de datos  (abrir)               │
└────────────────────────────────────────────┘
```

* El botón "Buscar actualizaciones" llama a `UpdateChecker` (§4.6).
* "Esquema de datos v1" muestra `SchemaVersion.CURRENT` (§6): si el usuario
  tiene datos de una versión más nueva, acá se explica por qué no puede
  escribir.
* "Carpeta de datos" abre `<dataDir>` (`~/.kitoru` en escritorio,
  `context.filesDir/kitoru` en Android) — imprescindible para que un alumno
  pueda respaldar sus chats.

### 5.4. El `CHANGELOG`

`docs/CHANGELOG.md` con formato Keep-a-Changelog:

```markdown
## [0.1.0] - 2026-10-21
### Agregado
- Chat con el material de la cátedra, con citas verificadas.
- Modo IA local con descarga del modelo desde Hugging Face.
### Cambiado
- …
```

El `release.yml` lo adjunta a la release de GitHub y la app lo puede mostrar
en el diálogo de "hay una versión nueva" (se lee el `CHANGELOG.md` del repo
`main` por HTTPS, o el `body` de la release de la API de GitHub).

---

## 6. Versionado del esquema de datos

### 6.1. El archivo de configuración

Hoy `:core` guarda un JSON por tipo (`courses.json`, `enrollments.json`,
`materials.json`, `threads.json`, `messages.json`, `profiles.json`) y **no
tiene ningún marcador de versión**. Se agrega un archivo más:

`config/app-config.json`:

```json
{
  "schemaVersion": 1,
  "profile": { "id": "u-...", "displayName": "Ana", "email": "" },
  "uiMode": "ALUMNO",
  "lastCourseId": "c-...",
  "theme": "system",
  "model": { "mode": "REMOTE", "remoteBaseUrl": "...", "apiKey": "...", "modelName": "..." },
  "modelCatalog": [ … ]
}
```

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AppConfig.kt
@Serializable
data class AppConfig(
    /**
     * Versión del esquema con el que se escribió el archivo.
     * El default es 0 (a propósito): un archivo viejo que no tiene el campo
     * se decodifica como 0 y el loader lo migra. Si el default fuera CURRENT,
     * no se podría distinguir "archivo v0" de "archivo v1".
     */
    val schemaVersion: Int = 0,
    val profile: UserProfile? = null,
    val uiMode: String = "ALUMNO",     // String y no AppMode: AppMode vive en :ui
    val lastCourseId: String? = null,
    val theme: String = "system",
    val model: ModelPolicy = ModelPolicy(),
    val modelCatalog: List<ModelPreset> = emptyList(),
) {
    companion object {
        /** Config nueva (instalación limpia): ya en la versión actual. */
        fun empty(): AppConfig = AppConfig(schemaVersion = SchemaVersion.CURRENT)
    }
}
```

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/SchemaVersion.kt
object SchemaVersion {
    /** Versión actual del esquema que la app sabe LEER y ESCRIBIR. */
    const val CURRENT: Int = 1

    /** ¿Estos datos los escribió una app más nueva que esta? */
    fun isTooNew(found: Int): Boolean = found > CURRENT
}
```

### 6.2. Reglas

1. **Todo campo nuevo tiene default.** `KitoruJson` ya está configurado con
   `ignoreUnknownKeys = true` y `encodeDefaults = true`. Consecuencia
   práctica: **agregar un campo NO requiere bumpear `schemaVersion`** — una app
   vieja lo ignora (no lo conoce) y una app nueva lo lee con el default si el
   archivo es viejo.
2. **Sólo se bumpea `schemaVersion` cuando hace falta una migración**:
   renombrar un campo, cambiarle el tipo, reinterpretar un valor, o separar
   un campo en dos.
3. **La migración es una función pura y testeada**:
   ```kotlin
   // core/src/commonMain/kotlin/kitoru/domain/Migrations.kt
   object Migrations {
       /** Aplica las migraciones de `from` hasta SchemaVersion.CURRENT. */
       fun migrate(config: AppConfig, from: Int): AppConfig {
           var c = config
           var v = from
           while (v < SchemaVersion.CURRENT) {
               c = when (v) {
                   0 -> c.copy(schemaVersion = 1)          // ej. v0 → v1
                   else -> error("Falta la migración de $v a ${v + 1}")
               }
               v++
           }
           return c.copy(schemaVersion = SchemaVersion.CURRENT)
       }
   }
   ```
   Con un test por paso: `MigrationsTest` (en `:core/src/desktopTest`).
   El `when` es **exhaustivo a propósito**: si se sube `CURRENT` y no se
   escribe el paso nuevo, el test falla en compilación, no en producción.
4. **Los archivos por tipo no llevan versión propia**: heredan la de
   `app-config.json`. Si `app-config.json` no existe (instalación nueva), se
   crea con `AppConfig.empty()` (`schemaVersion = CURRENT`) y los demás
   archivos se crean vacíos.
5. **Antes de migrar, se hace una copia de seguridad**:
   `config/backups/app-config.v{from}.json` (una sola por versión; se
   sobreescribe, no se acumulan).

### 6.3. Qué pasa si una app vieja abre datos nuevos

**Escenario:** la app 0.2.0 migró los datos a `schemaVersion = 2`. El alumno
vuelve a abrir la app 0.1.0 (o instala una versión vieja por error).

**Comportamiento diseñado:**

1. Al arrancar, la app lee `config/app-config.json` y compara
   `schemaVersion` con `SchemaVersion.CURRENT`.
2. Si `schemaVersion > CURRENT`:
   * **no** se migra, **no** se escribe **nada** (ni config, ni repositorios);
   * se muestra una **pantalla bloqueante** (no un toast: la app no puede
     seguir):

   ```
   ┌──────────────────────────────────────────────┐
   │  ⚠  Actualizá Kitoru                          │
   │                                              │
   │  Estos datos los creó una versión más nueva   │
   │  de Kitoru (esquema 2). Esta versión (0.1.0)  │
   │  sólo entiende hasta el esquema 1.             │
   │                                              │
   │  Si actualizás, vas a poder seguir usando     │
   │  todo tal cual. Si no, no vamos a tocar tus    │
   │  datos.                                       │
   │                                              │
   │  [ Actualizar ahora ]   [ Salir ]             │
   └──────────────────────────────────────────────┘
   ```

   * "Actualizar ahora" abre la página de releases (o la del APK) con
     `openUrl()` (expect/actual en `:core`).
3. Mientras esté en esa pantalla, **todas las operaciones de escritura están
   bloqueadas** (el `AppState` arranca en `Blocked(SchemaTooNew)`). Esto evita
   que un `JsonStore.write()` parcial destruya datos.
4. **No se toca ningún archivo**: el mensaje lo dice explícitamente para que
   el alumno no entre en pánico.

### 6.4. Qué pasa si una app nueva abre datos viejos

Se aplica `Migrations.migrate(config, from)` al cargar, se persiste el
resultado (con el respaldo previo) y la app sigue normal. Si la migración
falla con una excepción:

* se **restaura** el respaldo (`app-config.v{from}.json`);
* se muestra un mensaje: "No se pudo actualizar la configuración de la app.
  Se restauró la copia anterior. Podés seguir usando la app, pero es posible
  que falten algunos ajustes."

---

## 7. Los artefactos de modelo (GGUF)

### 7.1. Decisión

**Sí: el modelo se descarga de Hugging Face en el primer uso**, igual que en
FunshiEngineAI. Pero:

| | Escritorio | Android |
|---|---|---|
| Modelo local (`ModelMode.LOCAL`) | ✅ soportado (llama.cpp) | ❌ **no soportado**: `llm-api/src/androidMain/…/LocalModelService.android.kt` es un stub que tira `LlmException` |
| Descarga del `.gguf` | ✅ | ❌ (no hay motor local que lo use) |
| Modo por defecto | REMOTE (servidor de la cátedra) | REMOTE (obligatorio) |

* El modelo **nunca** va dentro del `.msi`/`.dmg`/`.deb`: son GB y cambiarían
  con cada modelo.
* El modelo **tampoco** va dentro del APK (mismo motivo, y el límite de tamaño
  de un APK y de Play).
* El modelo se guarda en `<dataDir>/models/`, que en escritorio es
  `~/.kitoru/models/`.

### 7.2. Catálogo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPreset.kt
@Serializable
data class ModelPreset(
    val id: String,
    val label: String,
    val repo: String,          // "unsloth/Phi-4-mini-instruct-GGUF"
    val file: String,          // "Phi-4-mini-instruct-Q4_K_M.gguf"
    val sizeBytes: Long = 0,
    val sha256: String = "",   // opcional: si está, se verifica
    val minRamGb: Int = 8,
    val note: String = "",
)
```

> `ModelPreset` vive en **`:core`** (es un concepto de dominio y `AppConfig`
> lo referencia), mientras que el **descargador** vive en `:llm-api`
> (`llm.model.HuggingFaceDownloader`). `:core` no depende de `:llm-api`: la
> dependencia va al revés.

Catálogo por defecto (idéntico al de FunshiEngineAI, con el `sha256` opcional):

| id | Modelo | Repo / archivo | Tamaño | RAM |
|---|---|---|---|---|
| `phi4-mini-q4` | Phi-4 mini (Q4_K_M) | `unsloth/Phi-4-mini-instruct-GGUF` / `Phi-4-mini-instruct-Q4_K_M.gguf` | ~2,4 GB | 8 GB |
| `qwen25-05b-q4` | Qwen2.5 0.5B (Q4_K_M) | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` / `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~400 MB | 4 GB |
| `gemma2-2b-q4` | Gemma 2 2B (Q4_K_M) | `bartowski/gemma-2-2b-it-GGUF` / `gemma-2-2b-it-Q4_K_M.gguf` | ~1,7 GB | 6 GB |

El **recomendado para probar** es `qwen25-05b-q4` (400 MB): baja rápido y
alcanza para verificar el flujo completo.

### 7.3. La descarga

Se porta el `HuggingFaceDownloader` de FunshiEngineAI
(`llm-api/src/main/kotlin/llm/catalog/HuggingFaceDownloader.kt`) a
`:llm-api` (commonMain, con Ktor CIO en desktop y OkHttp en Android). El
código es común (así se testea una sola vez), pero **sólo la UI de escritorio
lo expone**: en Android no hay motor local (§7.1), así que la pantalla de
modelos no aparece.

* Streaming por chunks de 64 KB **directo a disco** (nunca el modelo entero en
  memoria: es el problema clásico del "array buffer").
* **Reanudación**: se descarga a `<archivo>.part`; si ya existe, se pide
  `Range: bytes=<lo que hay>-`. Si el servidor responde 416 (ya estaba
  completo), se da por bueno.
* **Lock por archivo** (`Mutex` con clave = ruta absoluta) para que dos
  intentos simultáneos no se pisen.
* **Cancelación**: es un `Job` de corrutina; el botón "Cancelar" lo cancela.
* **Progreso** con throttling de 250 ms, pero **siempre** emitiendo el 100 %
  final.

### 7.4. Integridad

Tres niveles, en este orden:

1. **Tamaño**: la API de HF (`/api/models/<repo>?blobs=true`) devuelve el
   tamaño real de cada `sibling`; se compara con el tamaño del archivo
   descargado. Si no coinciden → descarga incompleta → el `.part` se conserva
   para reanudar.
2. **SHA-256** (el fuerte): los archivos GGUF en HF son archivos **LFS**, y la
   API devuelve su hash en `siblings[].lfs.oid` (que es el SHA-256 del
   contenido). Con ese valor se verifica el archivo descargado:
   ```kotlin
   val expected = blobs[file]?.lfs?.oid   // sha256 hexadecimal
   val actual = sha256Hex(File(target))
   if (expected != null && expected != actual) { … }
   ```
   Si no hay `lfs.oid` (archivos pequeños no-LFS), se cae al chequeo de tamaño.
3. **Firma GGUF**: la cabecera del archivo GGUF tiene el magic `GGUF`; un
   chequeo trivial (leer los 4 primeros bytes) detecta un archivo corrupto o
   un HTML de error de HF guardado como `.gguf`.

### 7.5. Manejo de fallos (mensajes en español, fail-fast)

| Fallo | Detección | Mensaje | Qué hace la app |
|---|---|---|---|
| **Red caída / timeout** | excepción de Ktor, `socketTimeout` | "No se pudo descargar el modelo: no hay conexión con Internet. Se reanudará desde 640 MB." | Reintenta con backoff exponencial (3 intentos: 2 s, 8 s, 30 s). El `.part` queda. El chat sigue funcionando en modo REMOTE. |
| **404 (repo/archivo inexistente o privado)** | HTTP 404 | "El modelo «Phi-4-mini-q4» no está disponible (HTTP 404). Revisa el nombre del repo o pedí acceso." | No reintenta (no es transitorio). Botón "Probar otro modelo". |
| **401/403 (HF pide token)** | HTTP 401/403 | "Hugging Face pide un token para este modelo. Pegá tu token en Ajustes → Modelos." | Campo de texto para el token. |
| **Disco lleno** | `File.getUsableSpace()` **antes** de descargar (desktop) / `StatFs` (Android) | "Espacio insuficiente: el modelo ocupa 2,4 GB y hay 1,1 GB libres. Liberá espacio o elegí un modelo más chico." | No empieza la descarga. Botón "Abrir carpeta de modelos" / "Ver modelos más chicos". |
| **Integridad (sha distinto)** | SHA-256 ≠ esperado | "El archivo descargado está dañado. Se eliminó y se va a volver a bajar." | Borra el `.part` y reintenta **desde cero** (máx. 2 veces). Si vuelve a fallar: "No se pudo descargar el modelo correctamente. Probá con otro modelo o descargalo a mano desde <url> y ponelo en la carpeta de modelos." |
| **Cancelación del usuario** | `job.cancel()` | "Descarga cancelada. Podés retomarla cuando quieras." | El `.part` se conserva; la próxima vez se reanuda. |
| **Sin `llama-server`** | `LlamaManager.requireTool` (portado) | "No se encontró «llama-server». Instalá llama.cpp o indicá la ruta en Ajustes → Motor." | El chat local queda deshabilitado; el remoto sigue. |
| **El modelo no arranca** | el proceso hijo muere al cargar | "El modelo no se pudo cargar. Probá con menos capas en GPU o un modelo más chico." | Sugerencias accionables. |

### 7.6. El mensaje en la UI

```
┌───────────────────────────────────────────────────────┐
│  Modelo:  IA local del alumno                        │
│                                                       │
│  ▸ Phi-4 mini (Q4_K_M)          2,4 GB   [Descargar] │
│  ▸ Qwen2.5 0.5B (Q4_K_M)        400 MB   [Descargar] │  ← recomendado
│  ▸ Gemma 2 2B (Q4_K_M)          1,7 GB   [Descargar] │
│                                                       │
│  Descargando Qwen2.5 0.5B…                            │
│  ████████████████░░░░░░░░░  62%   248 MB / 400 MB    │
│  1,2 MB/s · faltan 24 s                    [Cancelar]│
└───────────────────────────────────────────────────────┘
```

En Android, la sección "Modelo local" **no aparece**: el mensaje es
"El modelo local todavía no está disponible en Android. Usá el servidor de tu
cátedra."

---

## 8. CI — GitHub Actions

### 8.1. `.github/workflows/ci.yml` (completo)

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

# Un push nuevo cancela el run anterior del mismo ref.
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

# El heap del build sale de gradle.properties (org.gradle.jvmargs=-Xmx3g);
# los runners de GitHub tienen 7 GB, así que no hace falta tocarlo acá.

jobs:
  # ------------------------------------------------------------------ #
  # Escritorio · Windows                                              #
  # ------------------------------------------------------------------ #
  desktop-windows:
    name: Desktop · Windows (tests + .msi)
    runs-on: windows-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      # Cachea ~/.gradle (dependencias) y el wrapper.
      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Tests + compilación (desktop)
        run: >-
          .\gradlew.bat --no-daemon --stacktrace
          :core:desktopTest
          :llm-api:desktopTest
          :backend:desktopTest
          :ui:desktopTest
          :desktop:assemble
        shell: pwsh

      - name: Empaquetado (.msi)
        run: .\gradlew.bat --no-daemon --stacktrace :desktop:packageMsi
        shell: pwsh

      - name: Subir el .msi
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-desktop-windows
          path: desktop/build/compose/binaries/**/msi/*.msi
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Escritorio · Linux                                                #
  # ------------------------------------------------------------------ #
  desktop-linux:
    name: Desktop · Linux (tests + .deb)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      # jpackage necesita fakeroot para construir el .deb.
      - name: Instalar fakeroot
        run: |
          sudo apt-get update
          sudo apt-get install -y fakeroot

      - name: Tests + compilación (desktop)
        run: >-
          ./gradlew --no-daemon --stacktrace
          :core:desktopTest
          :llm-api:desktopTest
          :backend:desktopTest
          :ui:desktopTest
          :desktop:assemble

      - name: Empaquetado (.deb)
        run: ./gradlew --no-daemon --stacktrace :desktop:packageDeb

      - name: Comprobar el .deb
        run: |
          set -euo pipefail
          DEB="$(find desktop/build/compose/binaries -name '*.deb' -print -quit)"
          echo "Paquete: $DEB"
          ls -lh "$DEB"
          dpkg-deb --info "$DEB" | head -30

      - name: Subir el .deb
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-desktop-linux
          path: desktop/build/compose/binaries/**/deb/*.deb
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Escritorio · macOS                                                #
  # (caro: sólo en main, manual y release)                            #
  # ------------------------------------------------------------------ #
  desktop-macos:
    name: Desktop · macOS (.dmg)
    if: github.event_name == 'workflow_dispatch' || github.ref == 'refs/heads/main'
    runs-on: macos-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Empaquetado (.dmg)
        run: ./gradlew --no-daemon --stacktrace :desktop:packageDmg

      - name: Subir el .dmg
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-desktop-macos
          path: desktop/build/compose/binaries/**/dmg/*.dmg
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Android · unit tests + APK de debug                               #
  # ------------------------------------------------------------------ #
  android:
    name: Android (unit tests + APK debug)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      # Instala el SDK de Android y define ANDROID_HOME (AGP lo usa).
      # 'packages' corre sdkmanager por nosotros.
      - name: Instalar el SDK de Android
        uses: android-actions/setup-android@v3
        with:
          packages: >-
            platform-tools
            platforms;android-35
            build-tools;35.0.0

      - name: Tests unitarios (los dos targets)
        run: >-
          ./gradlew --no-daemon --stacktrace
          :core:testDebugUnitTest
          :llm-api:testDebugUnitTest
          :backend:testDebugUnitTest
          :ui:testDebugUnitTest
          :android:testDebugUnitTest

      - name: APK de debug
        run: ./gradlew --no-daemon --stacktrace :android:assembleDebug

      - name: Subir el APK de debug
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-android-debug
          path: android/build/outputs/apk/debug/*.apk
          if-no-files-found: error
```

**Notas importantes sobre este workflow:**

* **El SDK de Android es obligatorio**: sin `setup-android`, AGP falla con
  *"SDK location not found"*. `local.properties` **no** se versiona (está en
  `.gitignore`), así que en CI la ruta del SDK tiene que venir de
  `ANDROID_HOME` (que el action exporta) o de `ANDROID_SDK_ROOT`.
* `platforms;android-35` hace falta porque `compileSdk = 35`;
  `build-tools;35.0.0` es el que usa AGP 8.7 (también lo bajaría solo, pero
  hacerlo explícito es más rápido y reproducible).
* `actions/setup-java` + `gradle/actions/setup-gradle@v4`: el segundo cachea
  el `~/.gradle/caches` y el wrapper. **No** hace falta un `actions/cache`
  aparte (sería redundante).
* Los tests de Android son **unit tests JVM** (`testDebugUnitTest`): no
  necesitan emulador. Los instrumented tests (`connectedAndroidTest`) **no**
  están en el CI de cada push: corren en `workflow_dispatch` o cuando haya un
  emulador disponible (mucho más caro).
* Los jobs de Windows y Linux **no** dependen entre sí: corren en paralelo.
* El job de macOS se saltea en los PR (D12).

### 8.2. ¿Y Docker?

**No aplica, y no se va a agregar.** Razones:

1. Kitoru es una **app de escritorio**: se instala con un `.msi`/`.dmg`/`.deb`.
   El "contenedor" de una app de escritorio es… la app instalada.
2. El build no necesita un entorno exótico: **JDK 21** (con `jpackage` y
   `jlink`), **SDK de Android** y, en Linux, **`fakeroot`**. Los runners de
   GitHub ya traen el JDK; el SDK se instala con un action; `fakeroot` con un
   `apt-get`.
3. Jpackage tiene que generar el **instalador nativo del SO**: una imagen de
   Docker con Linux no puede producir un `.msi` de Windows ni un `.dmg` de
   macOS. Los tres builds tienen que correr en el SO correspondiente.
4. El repo **no** tiene ningún componente servidor que containerizar: el
   Kitoru-Server (el que aloja el modelo y emite las API keys) es ** otro
   repo**, y ahí sí tendría sentido un `Dockerfile`.
5. Una imagen de Docker para el build sólo agregaría una capa de caché que
   `gradle/actions/setup-gradle` ya resuelve mejor.

### 8.3. Badges y protecciones del repo

En el `README`:

```markdown
![CI](https://github.com/Nootechnia/Kitoru/actions/workflows/ci.yml/badge.svg)
```

Y en el repo, Settings → Branches → Rules → "Require status checks":
`desktop-windows`, `desktop-linux`, `android`.

---

## 9. Release — GitHub Actions

### 9.1. `.github/workflows/release.yml` (completo)

```yaml
name: Release

on:
  push:
    tags: [ 'v*' ]
  workflow_dispatch:
    inputs:
      version:
        description: 'Versión a publicar (debe coincidir con kitoru.version de gradle.properties)'
        required: true
        type: string
      dry_run:
        description: 'Compilar sin publicar la release (para probar el pipeline)'
        type: boolean
        default: false

permissions:
  contents: write

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

# El heap del build sale de gradle.properties (org.gradle.jvmargs=-Xmx3g).

jobs:
  # ------------------------------------------------------------------ #
  # Calcula la versión del tag y valida contra gradle.properties      #
  # ------------------------------------------------------------------ #
  prepare:
    name: Preparar (versión del tag)
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.ver.outputs.version }}
      version_code: ${{ steps.ver.outputs.version_code }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Leer y validar la versión
        id: ver
        run: |
          set -euo pipefail
          if [ "${GITHUB_REF_TYPE}" = "tag" ]; then
            TAG="${GITHUB_REF_NAME}"
          else
            TAG="${{ inputs.version }}"
            if [ -z "$TAG" ]; then
              echo "::error::Para publicar a mano hay que indicar la versión (inputs.version)."
              exit 1
            fi
          fi
          VERSION="${TAG#v}"
          echo "version=${VERSION}" >> "$GITHUB_OUTPUT"

          # La versión del tag tiene que coincidir con gradle.properties.
          DECLARED="$(grep '^kitoru.version=' gradle.properties | cut -d= -f2)"
          if [ "$DECLARED" != "$VERSION" ]; then
            echo "::error::El tag ${TAG} dice ${VERSION} pero gradle.properties dice kitoru.version=${DECLARED}."
            exit 1
          fi

          CODE="$(grep '^kitoru.versionCode=' gradle.properties | cut -d= -f2)"
          echo "version_code=${CODE}" >> "$GITHUB_OUTPUT"
          echo "Versión ${VERSION} (code ${CODE})"

  # ------------------------------------------------------------------ #
  # Escritorio · Windows → .msi                                       #
  # ------------------------------------------------------------------ #
  desktop-windows:
    name: .msi
    needs: prepare
    runs-on: windows-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Tests (no se publica un instalador con tests rojos)
        run: >-
          .\gradlew.bat --no-daemon --stacktrace
          :core:desktopTest :llm-api:desktopTest :backend:desktopTest :ui:desktopTest
        shell: pwsh

      - name: Empaquetado (.msi)
        run: >-
          .\gradlew.bat --no-daemon --stacktrace
          :desktop:packageMsi
          -Pkitoru.version=${{ needs.prepare.outputs.version }}
          -Pkitoru.versionCode=${{ needs.prepare.outputs.version_code }}
        shell: pwsh

      - name: Subir el .msi
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-msi
          path: desktop/build/compose/binaries/**/msi/*.msi
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Escritorio · Linux → .deb                                         #
  # ------------------------------------------------------------------ #
  desktop-linux:
    name: .deb
    needs: prepare
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Instalar fakeroot
        run: |
          sudo apt-get update
          sudo apt-get install -y fakeroot

      - name: Tests
        run: >-
          ./gradlew --no-daemon --stacktrace
          :core:desktopTest :llm-api:desktopTest :backend:desktopTest :ui:desktopTest

      - name: Empaquetado (.deb)
        run: >-
          ./gradlew --no-daemon --stacktrace
          :desktop:packageDeb
          -Pkitoru.version=${{ needs.prepare.outputs.version }}
          -Pkitoru.versionCode=${{ needs.prepare.outputs.version_code }}

      - name: Subir el .deb
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-deb
          path: desktop/build/compose/binaries/**/deb/*.deb
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Escritorio · macOS → .dmg                                         #
  # ------------------------------------------------------------------ #
  desktop-macos:
    name: .dmg
    needs: prepare
    runs-on: macos-latest
    timeout-minutes: 75
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Empaquetado (.dmg)
        run: >-
          ./gradlew --no-daemon --stacktrace
          :desktop:packageDmg
          -Pkitoru.version=${{ needs.prepare.outputs.version }}
          -Pkitoru.versionCode=${{ needs.prepare.outputs.version_code }}

      - name: Subir el .dmg
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-dmg
          path: desktop/build/compose/binaries/**/dmg/*.dmg
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Android → APK firmado                                             #
  # ------------------------------------------------------------------ #
  android:
    name: APK
    needs: prepare
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Cache de Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Instalar el SDK de Android
        uses: android-actions/setup-android@v3
        with:
          packages: >-
            platform-tools
            platforms;android-35
            build-tools;35.0.0

      # El keystore NUNCA está en el repo: se reconstruye desde el secreto.
      - name: Reconstruir el keystore de release
        env:
          KEYSTORE_BASE64: ${{ secrets.KITORU_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          if [ -z "${KEYSTORE_BASE64}" ]; then
            echo "::error::Falta el secreto KITORU_KEYSTORE_BASE64."
            exit 1
          fi
          python3 -c "import base64,os,sys;open(sys.argv[1],'wb').write(base64.b64decode(os.environ['KEYSTORE_BASE64']))" \
            "${{ github.workspace }}/android/kitoru-release.jks"
          chmod 600 "${{ github.workspace }}/android/kitoru-release.jks"

      - name: Tests unitarios
        run: >-
          ./gradlew --no-daemon --stacktrace
          :core:testDebugUnitTest :llm-api:testDebugUnitTest
          :backend:testDebugUnitTest :ui:testDebugUnitTest

      - name: APK de release (firmado)
        env:
          KITORU_KEYSTORE_FILE: ${{ github.workspace }}/android/kitoru-release.jks
          KITORU_KEYSTORE_PASSWORD: ${{ secrets.KITORU_KEYSTORE_PASSWORD }}
          KITORU_KEY_ALIAS: ${{ secrets.KITORU_KEY_ALIAS }}
          KITORU_KEY_PASSWORD: ${{ secrets.KITORU_KEY_PASSWORD }}
        run: >-
          ./gradlew --no-daemon --stacktrace
          :android:assembleRelease
          -Pkitoru.version=${{ needs.prepare.outputs.version }}
          -Pkitoru.versionCode=${{ needs.prepare.outputs.version_code }}

      - name: Verificar la firma del APK
        run: |
          set -euo pipefail
          "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
            android/build/outputs/apk/release/android-release.apk

      - name: Subir el APK
        uses: actions/upload-artifact@v4
        with:
          name: kitoru-apk
          path: android/build/outputs/apk/release/*.apk
          if-no-files-found: error

  # ------------------------------------------------------------------ #
  # Publicar la release de GitHub                                     #
  # ------------------------------------------------------------------ #
  publish:
    name: Publicar la release
    needs: [ prepare, desktop-windows, desktop-linux, desktop-macos, android ]
    runs-on: ubuntu-latest
    if: ${{ !inputs.dry_run }}
    steps:
      - uses: actions/checkout@v4

      - name: Bajar los artefactos
        uses: actions/download-artifact@v4
        with:
          path: dist
          merge-multiple: true

      - name: Renombrar con la versión
        run: |
          set -euo pipefail
          cd dist
          V="${{ needs.prepare.outputs.version }}"
          for f in *; do
            [ -f "$f" ] || continue
            ext="${f##*.}"
            case "$ext" in
              msi) name="kitoru-${V}-windows-x64.msi" ;;
              dmg) name="kitoru-${V}-macos.dmg" ;;
              deb) name="kitoru-${V}-linux-amd64.deb" ;;
              apk) name="kitoru-${V}-android.apk" ;;
              *)   name="kitoru-${V}-${f}" ;;
            esac
            mv "$f" "$name"
          done
          ls -lh

      - name: Checksums
        run: |
          set -euo pipefail
          cd dist
          find . -maxdepth 1 -type f ! -name 'SHA256SUMS.txt' -print0 \
            | sort -z | xargs -0 sha256sum > SHA256SUMS.txt
          cat SHA256SUMS.txt

      - name: Extraer las notas de la versión del CHANGELOG
        run: |
          set -euo pipefail
          V="${{ needs.prepare.outputs.version }}"
          awk -v v="$V" '
            $0 ~ "^## \\[" v "\\]" { found=1; next }
            found && /^## \[/ { exit }
            found { print }
          ' docs/CHANGELOG.md > release-notes.md
          if [ ! -s release-notes.md ]; then
            echo "Ver el CHANGELOG completo en el repositorio." > release-notes.md
          fi
          cat release-notes.md

      - name: Crear la release
        uses: softprops/action-gh-release@v2
        with:
          name: Kitoru ${{ needs.prepare.outputs.version }}
          tag_name: v${{ needs.prepare.outputs.version }}
          draft: false
          prerelease: ${{ contains(needs.prepare.outputs.version, '-') }}
          body_path: release-notes.md
          files: |
            dist/*.msi
            dist/*.dmg
            dist/*.deb
            dist/*.apk
            dist/SHA256SUMS.txt
```

### 9.2. Secretos de GitHub (Settings → Secrets and variables → Actions)

| Secreto | Contenido | Cómo se genera |
|---|---|---|
| `KITORU_KEYSTORE_BASE64` | El `.jks` en base64 | `base64 -w0 ~/.kitoru/kitoru-release.jks` |
| `KITORU_KEYSTORE_PASSWORD` | La contraseña del keystore | la que se puso en `keytool` |
| `KITORU_KEY_ALIAS` | `kitoru` | — |
| `KITORU_KEY_PASSWORD` | La contraseña de la clave | la que se puso en `keytool` |

**Nunca** se sube el `.jks` como archivo del repo ni como artefacto de un
workflow.

### 9.3. Qué se publica

| Artefacto | SO | Nombre final | Tamaño esperado |
|---|---|---|---|
| `kitoru-0.1.0-windows-x64.msi` | Windows 10/11 x64 | instalador con acceso directo y entrada en el menú Inicio | 90–140 MB (con el runtime embebido) |
| `kitoru-0.1.0-macos.dmg` | macOS 11+ (Apple Silicon y Intel) | imagen de disco con Kitoru.app | 90–140 MB |
| `kitoru-0.1.0-linux-amd64.deb` | Linux x64 (Debian/Ubuntu) | paquete `.deb` | 80–130 MB |
| `kitoru-0.1.0-android.apk` | Android 7.0+ | APK firmado (universal arm64 + x86_64) | 15–30 MB |
| `SHA256SUMS.txt` | — | checksums para verificar la descarga | 1 KB |

> El `.dmg` se compila **sin firmar** en 0.x. macOS va a mostrar "aplicación
> descargada de internet". Workaround documentado en el manual:
> `xattr -dr com.apple.quarantine /Applications/Kitoru.app`.
> Para 1.0 hay que firmarlo con "Developer ID Application" y notarizarlo
> (`macOS.signing` / `macOS.notarization` en el `build.gradle.kts`).

> El APK es **universal** (arm64-v8a + x86_64) salvo que se agreguen ABI
> splits; 15–30 MB está bien para el sideloading.

### 9.4. Publicación manual (sin CI)

Para una release puntual en una facultad sin acceso a GitHub Actions, el
mismo proceso manual:

```bash
# 1. Windows
./gradlew :desktop:packageMsi
# 2. Linux (en una máquina Linux)
./gradlew :desktop:packageDeb
# 3. macOS (en una máquina macOS)
./gradlew :desktop:packageDmg
# 4. Android
./gradlew :android:assembleRelease
# 5. Publicar
gh release create v0.1.0 \
  desktop/build/compose/binaries/**/msi/*.msi \
  desktop/build/compose/binaries/**/dmg/*.dmg \
  desktop/build/compose/binaries/**/deb/*.deb \
  android/build/outputs/apk/release/*.apk \
  --title "Kitoru 0.1.0" --notes-file docs/CHANGELOG.md
```

---

## 10. La documentación del usuario

### 10.1. `README.md` (raíz)

Estructura, siguiendo el patrón del README de FunshiEngineAI (que funciona
bien: qué es, capacidades, documentación, configuración, cómo correrlo, estado):

```markdown
# Kitoru

IA para cátedras: chateá con el material de tu cursada, con respuestas que
se pueden verificar contra las fuentes.

![Captura del chat de un alumno](docs/capturas/01-chat-alumno.png)

## Qué hace

- **Alumno**: se inscribe en una cátedra con un código, chatea con el modelo
  que la cátedra configuró (o con su propio modelo local), y las respuestas
  citan el material de la cursada.
- **Docente**: diseña la cátedra, sube el material, fija la configuración base
  del modelo y analiza las estadísticas de los chats.

## Instalar

| SO | Cómo | Nota |
|---|---|---|
| Windows 10/11 | Descargar el `.msi` de la última release | Instala en tu perfil, sin permisos de administrador |
| macOS 11+ | Descargar el `.dmg` y arrastrar Kitoru a Aplicaciones | En 0.x sin firmar: ver `docs/MANUAL_DE_USO.md` |
| Debian/Ubuntu | Descargar el `.deb` e `sudo apt install ./kitoru_*.deb` | |
| Android 7.0+ | Descargar el `.apk` e instalarlo ("permitir unknown sources") | Actualización manual |

→ Los detalles, en **[`docs/MANUAL_DE_USO.md`](docs/MANUAL_DE_USO.md)**.

## Compilar desde el código

Requisitos: **JDK 21** y (para Android) el **SDK de Android 35**.

```bash
git clone https://github.com/Nootechnia/Kitoru.git
cd Kitoru
./gradlew run                 # app de escritorio
./gradlew check               # tests de los dos targets
./gradlew :android:installDebug
```

## Módulos

| Módulo | Qué es |
|---|---|
| `:core` | Dominio + plataforma portable (archivos, paths, repositorios) |
| `:llm-api` | Gateway LLM (SSE, OpenAI-compatible) + descarga del modelo |
| `:backend` | RAG (BM25), verificador de citas, bucle de herramientas |
| `:ui` | Compose Multiplatform (todas las pantallas) |
| `:desktop` | App de escritorio + empaquetado (jpackage) |
| `:android` | App Android |

## Documentación

| Documento | Para qué |
|---|---|
| [`docs/MANUAL_DE_USO.md`](docs/MANUAL_DE_USO.md) | **Manual de uso**: instalar, unirse a una cátedra, chatear, materiales, modelo local, actualizar. |
| [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) | Cómo está hecho por dentro. |
| [`docs/TESTING.md`](docs/TESTING.md) | Los tests y cómo correrlos. |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Historial por versión. |
| [`docs/design/`](docs/design/) | Los documentos de diseño. |
```

### 10.2. `docs/MANUAL_DE_USO.md` — el manual

Es el documento más importante para el usuario final. Se sigue el patrón de
`FunshiEngineAI/docs/MANUAL-RAG.md`: **en español, con tablas, con ejemplos
concretos, y con capturas**. Estructura:

```
# Manual de uso de Kitoru

> Para alumnos y docentes. Si querés ver cómo está hecho por dentro,
> mirá [ARQUITECTURA.md](ARQUITECTURA.md).

## Índice
1. Qué es Kitoru
2. Requisitos e instalación
   2.1 Windows
   2.2 macOS
   2.3 Linux
   2.4 Android
   2.5 ¿Qué es el "servidor de la cátedra"?
3. Primer arranque
   3.1 Crear tu perfil
   3.2 Unirse a una cátedra con un código
   3.3 La pantalla de inicio
4. Chatear
   4.1 Enviar un mensaje
   4.2 Las citas [1] [2]
   4.3 El distintivo de citas verificadas
   4.4 Cambiar el modelo (si la cátedra lo permite)
   4.5 Herramientas y permisos
5. Los materiales de la cátedra (para docentes)
   5.1 Subir un PDF
   5.2 Qué se indexa y qué no
   5.3 Cómo se trocea y se recupera
6. El modelo local (IA en tu máquina)
   6.1 Requisitos
   6.2 Descargar el modelo
   6.3 Si algo falla
7. Tus datos
   7.1 Dónde están
   7.2 Copia de seguridad
   7.3 "Actualizá la app": qué significa
8. Actualizar Kitoru
9. Problemas frecuentes
10. Glosario
```

**Reglas de estilo (tomadas del `MANUAL-RAG.md`):**

* Español rioplatense, tono de "manual", frases cortas.
* **Una tabla por lo que se compara** (modo local vs remoto, qué se indexa, …).
* **Capturas en cada sección de procedimiento**, no sólo al principio.
* **Ejemplos reales**: nombres de cátedras ("Programación 2"), textos de
  ejemplo, nombres de archivos.
* **Bloques de código** sólo cuando hay que hacer algo en una terminal.
* Sin jerga interna: si aparece `BM25`, se explica como "busca las palabras
  que aparecen en tu pregunta y en los documentos".

### 10.3. Las capturas

* Carpeta: `docs/capturas/`.
* Formato: **PNG**, ancho máximo 1600 px, nombre numerado y con guion:
  `01-instalacion-windows.png`, `02-chat-alumno.png`, `03-citas.png`,
  `04-materiales.png`, `05-modelo-local.png`, `06-acerca-de.png`,
  `07-actualizar.png`, `08-android-instalacion.png`.
* Se versionan en el repo (son pocos KB con PNG optimizado).
* En el markdown: `![El chat de un alumno, con las citas al lado de la respuesta](capturas/02-chat-alumno.png)`
  — **con texto alternativo**: sin ALT, las capturas no sirven para quien usa
  lector de pantalla, y es un requisito de accesibilidad de la app.
* Para mantenerlas al día: cada release revisa si alguna captura cambió
  (está en la checklist de release).

### 10.4. Otros documentos

| Documento | Contenido |
|---|---|
| `docs/ARQUITECTURA.md` | Módulos, paquetes, el RAG, el verificador de citas, el tool-calling, la persistencia, el build y el empaquetado. (El análogo de `FunshiEngineAI/docs/ARCHITECTURE.md`.) |
| `docs/TESTING.md` | Qué se testea, cómo correrlo (`./gradlew check`), qué reglas siguen los tests (determinismo, sin red, mensajes en español). |
| `docs/CHANGELOG.md` | Un bloque por versión (Keep a Changelog). |
| `docs/INSTALL.md` (opcional) | Instrucciones de instalación para el personal de soporte de la facultad (sideloading en Android, firewall, proxy de la facultad). |

---

## 11. Checklist de release

Imprimible. Para una release `0.x.0`:

### Antes

- [ ] `git checkout main && git pull --ff-only` — el árbol está limpio (`git status`).
- [ ] Actualizar `gradle.properties`: `kitoru.version=0.2.0` y
      `kitoru.versionCode=2` (el `versionCode` **nunca** baja).
- [ ] Si cambió el formato de los datos: subir `SchemaVersion.CURRENT`,
      escribir la migración en `Migrations.kt` **y su test**, y verificar que
      una app vieja con los datos nuevos avisa "actualizá la app".
- [ ] `docs/CHANGELOG.md` con la sección de la versión.
- [ ] `docs/MANUAL_DE_USO.md` actualizado (si cambió algo de la interfaz) y
      **capturas regeneradas**.
- [ ] `README.md` actualizado (instalación, módulo nuevo, capacidades nuevas).

### Compilar y testear

- [ ] `./gradlew clean build` — pasa todo (tests de los dos targets + Lint).
- [ ] `./gradlew :desktop:runDistributable` — la app arranca **empaquetada**
      (con el runtime embebido) y abre bien.
- [ ] Pruebas manuales de la app empaquetada: chat, cambio de cátedra,
      "Acerca de" muestra la versión correcta.

### Artefactos (uno por SO)

- [ ] **Windows**: `./gradlew :desktop:packageMsi` → instalar el `.msi` en una
      máquina limpia → verificar el ícono, el acceso directo, el menú Inicio,
      la carpeta de datos (`~/.kitoru`) → **desinstalar y reinstalar** para
      comprobar que el `upgradeUuid` reemplaza la instalación anterior.
- [ ] **Linux**: `./gradlew :desktop:packageDeb` en Linux →
      `sudo apt install ./kitoru_*.deb` → abrir el `.desktop` → desinstalar con
      `sudo apt remove kitoru`.
- [ ] **macOS**: `./gradlew :desktop:packageDmg` en macOS → abrir el `.dmg` →
      arrastrar a Aplicaciones → abrir → **"Acerca de" muestra la versión**
      → verificar que `Info.plist` tiene `CFBundleIdentifier =
      com.funshiengine.kitoru` y `CFBundleVersion = <versionCode>`.
- [ ] **Android**: `./gradlew :android:assembleRelease` (con el keystore) →
      `adb install -r` en un dispositivo real con Android 7 y en uno con
      Android 15 → verificar el **ícono adaptativo** (que el logo no se corte
      en los íconos circulares ni en los launchers cuadrados), el nombre, el
      arranque, la rotación de pantalla →
      **actualizar sobre la versión anterior** (misma firma) y comprobar que
      **no se pierden los chats**.

### Publicar

- [ ] `git tag -a v0.2.0 -m "Kitoru 0.2.0"` y `git push origin v0.2.0`.
- [ ] El workflow `release.yml` corre verde en GitHub.
- [ ] Verificar la release: están los 4 artefactos + `SHA256SUMS.txt`.
- [ ] Verificar los checksums localmente:
      `sha256sum -c SHA256SUMS.txt` (después de bajar los archivos).
- [ ] Verificar la firma del APK:
      `apksigner verify --print-certs kitoru-0.2.0-android.apk`.
- [ ] Probar la **actualización manual**: instalar la versión anterior,
      abrir "Acerca de" → "Buscar actualizaciones" → aparece la nueva →
      el link abre la release.
- [ ] Anunciar en el canal de la facultad: link a la release + qué cambió +
      si hay algo que los alumnos tengan que hacer (borrar la app, migrar
      datos, reconfigurar la API key).
- [ ] Guardar el `CHANGELOG` en la release y en el announcement.

### Después

- [ ] Etiquetar la issue/milestone como cerrada.
- [ ] Si hubo migraciones de datos: guardar una copia de `~/.kitoru` de
      prueba como referencia para los alumnos que reporten problemas.

---

## 12. Riesgos y pendientes

| # | Riesgo | Mitigación |
|---|---|---|
| R1 | **`compose.desktop.application` mal ubicado** (hoy está en `:ui`) | Se mueve a `:desktop` (§D1). Es el primer cambio de este documento. |
| R2 | **Resolución de variantes `:desktop` (kotlin-jvm) → `:ui` (KMP `jvm("desktop")`)** | Diseñado para funcionar; si Gradle se queja, está el `attributes { }` explícito (§1.1). |
| R3 | **`packageVersion` inválido en macOS con `0.x`** | `macOS.packageVersion = macSafeVersion` + `packageBuildVersion` (§3.3). Verificado contra el validador del plugin. |
| R4 | **El `.dmg` sin firmar macOS lo bloquea Gatekeeper** | Documentar el `xattr -dr com.apple.quarantine` en el manual; firmar + notarizar antes de la 1.0. |
| R5 | **Perder el keystore de release** | Dos respaldos cifrados offline + el secreto de GitHub; la app avisa que la actualización manual puede requerir reinstalar. |
| R6 | **El APK sideloadeado no se actualiza solo** | Diálogo "hay una versión nueva" + link directo al `.apk` (§4.6). |
| R7 | **El modelo son GB y muchos alumnos no lo pueden bajar** | El modo por defecto es REMOTE (servidor de la cátedra); el modelo local es el *fallback*; catálogo con un modelo chico (400 MB). |
| R8 | **La app vieja sobre los datos nuevos** | `schemaVersion` + pantalla bloqueante "Actualizá la app" + **no se escribe nada** (§6.3). |
| R9 | **El CI se pone lento** | `gradle/actions/setup-gradle` cachea; el job de macOS no corre en cada PR; se puede activar `configuration-cache` cuando el build esté estable. |
| R10 | **`schemaVersion` queda desactualizado** | La regla "todo campo nuevo tiene default" reduce el riesgo a cero; sólo hace falta bumpear cuando hay una migración real, y cada migración lleva su test. |

### Pendientes que este diseño deja abiertos

* [ ] ¿Versionado del **servidor** (Kitoru-Server, otro repo)? El `modelLabel`
      y la API key viajan en la config; la app muestra "Servidor de la cátedra".
      No lo define este documento.
* [ ] ¿El APK debería ser **split por ABI** (`arm64-v8a` + `x86_64` separados)
      para que pesen 8 MB en vez de 20? Ralentiza la actualización manual;
      dejarlos uno solo es más simple para el alumno.
* [ ] ¿Habilitar `configuration-cache=true`? Requiere que el build no use APIs
      no compatibles (el `providers.exec` de `BuildInfo` podría necesitar
      ajustes).
* [ ] ¿Automatizar la captura de pantallas con un script de Compose UI test?
      Requiere la versión desktop de las pruebas; es mucho trabajo para un
      beneficio chico.
