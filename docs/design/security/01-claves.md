# 01 · Seguridad de claves y credenciales

> Subagente: diseño de **cómo se guardan y protegen las credenciales** de Kitoru:
> la API key del servidor de la cátedra, la key personal del alumno, los tokens
> de sesión y de servicio.
>
> **Fuentes leídas antes de diseñar:**
> `docs/design/CONTEXTO.md`,
> `core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt`,
> `core/src/commonMain/kotlin/kitoru/domain/JsonStore.kt` (`KitoruJson`),
> `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt`,
> `core/src/commonMain/kotlin/kitoru/domain/Course.kt` (`Course`, `Enrollment`),
> `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt`,
> `core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt`,
> `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` (`OpenAiCompatibleGateway`),
> `llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt`,
> `llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt`,
> y los documentos hermanos `domain/01-catedra.md` (`KeyLease`),
> `domain/04-conversaciones.md` (`ModelPolicy.redacted()`),
> `domain/08-permisos.md` (`AccessLog`),
> `domain/09-identidad.md` (`CredentialStore`),
> `teacher/05-config-modelo.md` (`ApiKeyGrant`, `ApiKeyMode`),
> `platform/03-kitoru-server-api.md` (formato `ktor_<kid>_<secret>`),
> `security/02-privacidad.md`.
>
> **Reglas del proyecto que condicionan el diseño** (`CONTEXTO.md` §Reglas):
> *reutilizar no reescribir*, *portable por defecto*, *fail-fast con mensajes en
> español*, *el estudiante conserva agencia*, *el docente no ve lo que no debe*.
>
> **Estado:** propuesta cerrada. No se escribió código: este documento es el
> único entregable.

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | La API key **sale** de `ModelPolicy` y queda en un **almacén seguro** de plataforma. `ModelPolicy` guarda sólo un **alias** (`apiKeyRef`). | Un secreto en un JSON de configuración es un secreto copiable, sincronizable y respaldable. La separación (referencia en el JSON, secreto en el almacén) es lo que corta el 90 % de los vectores. |
| D2 | El seam es `expect fun secureStore(): SecureStore`, con la interfaz `SecureStore { put, get, remove, has, refs }` en `commonMain` de `:core`. | `EncryptedSharedPreferences`, DPAPI y el llavero del SO son tres APIs incompatibles. El `expect/actual` es inevitable: **el problema no es que la librería no sea KMP, es que no existe un almacén de secretos multiplataforma.** |
| D3 | **Android: `AndroidKeyStore` + AES/GCM directo. NO se agrega `androidx.security:security-crypto`.** | `EncryptedSharedPreferences` y `MasterKey` están **deprecados** desde `1.1.0-alpha03` (Google recomienda migrar al Keystore directo); la dependencia arrastra Tink y una versión *alpha*; y `minSdk = 24` ya garantiza `KeyGenParameterSpec` (API 23). El código propio son ~80 líneas. |
| D4 | **Desktop: almacén nativo por SO** — DPAPI (Windows), llavero de macOS (`security`), Secret Service (`secret-tool`) — **más un fallback de archivo cifrado con AES-GCM**. | El almacén nativo ya protege en reposo. Agregarle un AES-GCM encima no agrega una frontera de seguridad: sólo agrega un modo de falla. Ver §2.3 para el veredicto completo. |
| D5 | **Regla de oro: nunca caer a texto plano.** Si no hay almacén disponible, el secreto vive **sólo en memoria** y la UI lo dice. | Un fallback "silencioso a texto plano" convierte un bug de plataforma en una fuga. El fallo debe ser *visible*. |
| D6 | El almacén del SO vive **fuera** de la carpeta de datos de Kitoru (`%LOCALAPPDATA%` / `~/Library/Application Support` / `~/.local/share`), no en `~/.kitoru`. | `~/.kitoru` es la carpeta que la gente copia, sincroniza con OneDrive/Drive y mete en un backup. Las credenciales no deben estar ahí. |
| D7 | `apiKeyRef` es un **alias determinista y no secreto** con formato `esquema:ámbito:id` (`apikey:course:<id>`, `apikey:student:<enrollmentId>`, `session:<serverId>`). | Determinista ⇒ la migración es idempotente y el "buscar la key de esta cátedra" es trivial. No secreto ⇒ puede aparecer en logs, en el `AccessLog` y en la UI. |
| D8 | **Migración obligatoria y automática**: al arrancar, `SecretMigration` extrae toda clave en claro de los JSON, la lleva al almacén y **re-escribe el archivo sin ella**. | `ignoreUnknownKeys = true` evita el crash pero **no borra el secreto**: el `apiKey` viejo seguiría en el disco. Hay que sacarlo explícitamente. |
| D9 | **Una key por alumno** es el modo por defecto cuando hay Kitoru-Server. La key compartida de la cátedra queda sólo para el modo "sin servidor". | Una key compartida obliga a rotar para todos cuando se filtra una, impide atribuir el uso y no admite cuota por alumno. Ver §4. |
| D10 | La UI **nunca** muestra la key en claro por defecto: `ktor_K01J…kA`, botón de copiar con **auto-borrado del portapapeles a los 30 s** y aviso explícito de que el portapapeles es legible por otras apps. | "Copiar" es inevitable (hay que pegarla en otro lado), pero no tiene por qué ser permanente ni silencioso. |
| D11 | `redactSecrets(text)` (`SecretRedactor`) se aplica en **todos** los caminos que escriben texto: logs, mensajes de error, `AccessLog`, reportes de crash y exportaciones. | Un secreto se filtra por el camino más olvidado: el `toString()` de un `data class` o el *snippet* del cuerpo de un error HTTP. |
| D12 | **HTTPS obligatorio**; `http://` sólo a loopback (`localhost`, `127.0.0.0/8`, `::1`). Validado en `ModelPolicy.validate()` **y** en el gateway (defensa en profundidad). Sin `trustAll`, sin redirecciones fuera del host. | Una `Authorization: Bearer` en claro por la red es una credencial entregada a cualquiera en el camino. |

---

## 1. El problema

### 1.1 Qué pasa hoy, exactamente

`ModelPolicy` (`core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt:43`) tiene:

```kotlin
@Serializable
data class ModelPolicy(
    val mode: ModelMode = ModelMode.REMOTE,
    val remoteBaseUrl: String = "",
    val apiKey: String = "",          // ← el secreto, serializado
    val modelName: String = "",
    // …
)
```

Y `ModelPolicy` está anidado en `Course` (`Course.kt:27`), que se persiste con
`FileCourseRepository` → `JsonListStore` → `KitoruJson`
(`JsonStore.kt:12`):

```kotlin
val KitoruJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
```

Resultado: la key del servidor de la cátedra queda **literalmente escrita** en
`config/courses.json`, en texto plano, con formato `prettyPrint` para que sea
fácil de leer y copiar:

```json
{
  "id": "IA-2026-1",
  "code": "IA-2026-1",
  "name": "Inteligencia Artificial",
  "modelPolicy": {
    "mode": "REMOTE",
    "remoteBaseUrl": "https://ia.universidad.edu/kitoru/v1",
    "apiKey": "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA",
    "modelName": "Qwen3-8B",
    "…": "…"
  }
}
```

Además hay **tres** copias más del mismo problema:

| Copia | Dónde | Nota |
|---|---|---|
| La key en `courses.json` | `FileCourseRepository` → `config/courses.json` | La principal. |
| La key dentro de un hilo | `ChatThread.effectiveModel: ModelPolicy` → `config/threads.json` | `domain/04-conversaciones.md` §8.4 ya Goldstein propone `redacted()`, pero hasta que exista, cada chat guarda la key. |
| La key en la tabla de keys | `ApiKeyGrant.key` → `config/api-keys.json` | Propuesto en `teacher/05-config-modelo.md` §1.5, que ya lo marca como "deuda conocida". |

### 1.2 Por qué es inaceptable

No es un detalle de endurecimiento: son cuatro escenarios de todos los días.

1. **El archivo se copia.** `~/.kitoru/config/courses.json` va a un backup, a un
   ticket de soporte, a un repositorio de la cátedra, a un pendrive, a un
   "`config.json` de ejemplo" que alguien sube a GitHub. Cada copia es una
   credencial viva.
2. **El archivo se sincroniza con la nube.** OneDrive, Google Drive, Dropbox o
   iCloud sincronizan `~/.kitoru` (o `Documentos/`) sin preguntar. Con
   `apiKey` dentro, la key de la institución queda en la nube del usuario — y
   desde ahí en los respaldos de la nube, en los dispositivos del equipo, y en
   el histórico de versiones (que no se borra al eliminar el archivo).
3. **El archivo queda en un backup.** Un backup de disco o del sistema
   contiene la key **indefinidamente**, mucho después de que la key haya
   rotado. La rotación no sirve de nada si el secreto viejo sigue en 40
   snapshots.
4. **El archivo se abre por error.** Un doble clic, un `cat` en una terminal
   compartida, un `grep -r` que busca otra cosa y muestra la línea de
  copiado. En una sala de computación compartida, la key queda en el historial del
   scrollback del terminal.

Y hay un quinto, más silencioso:

5. **El secreto se propaga solo.** `ModelPolicy` es un `data class`: su
   `toString()` impreso. `GatewayConfig` también (`apiKey: String = ""`). Un
   `println(cfg)` de diagnóstico, un `log.debug { "config=$cfg" }`, o un
   reporte de crash que serialize el estado de la app **muestran la key** sin
   que nadie haya escrito "key" en ningún lado. (Ver §6.4: es un bug real hoy.)

### 1.3 Qué se cubre y qué no

**En alcance:** el secreto de la API key del modelo remoto, los tokens de
sesión Institutional, el token de servicio del docente, el token de
administración, y las reglas de no-filtración (logs, errores, crash, export).

**Fuera de alcance (se documenta, no se implementa acá):** la autorización del
servidor (scopes, `kid`, expiraciones) — que vive en
`platform/03-kitoru-server-api.md`; y la privacidad/consentimiento
(`security/02-privacidad.md`).

**Regla de oro de este documento:**

> **Un secreto jamás se serializa en un archivo de configuración, un log, un
> mensaje de error, un reporte de crash o una exportación.** Los archivos JSON
> de Kitoru guardan *referencias*; los secretos viven en el almacén de
> plataforma.

---

## 2. La solución: un almacén seguro

### 2.1 El seam

La idea central: **el dominio no sabe dónde vive el secreto**. Habla con una
interfaz de tres operaciones que todas las plataformas pueden cumplir.

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecureStore.kt
package kitoru.platform

/**
 * Almacén de secretos del dispositivo.
 *
 * Es la **única** puerta por la que un secreto entra o sale de la app. El
 * dominio (y los JSON) guardan *referencias* (`apikey:course:IA-2026-1`), nunca
 * el secreto.
 *
 * Contrato:
 *  - [put] es **sobrescritura atómica**: si ya había un secreto con esa ref,
 *    se reemplaza entero (nunca se mezcla un valor nuevo con uno viejo).
 *  - [get] devuelve `null` si no está, y **no lanza** si el almacén falla:
 *    devuelve `null` y el error queda en [lastError] para la UI. Un almacén
 *    caído no puede voltear la app.
 *  - [refs] devuelve las referencias conocidas (**nunca** los secretos), para
 *    poder auditar "qué credenciales tiene guardadas esta instalación".
 */
interface SecureStore {
    /** Qué tecnología está protegiendo el almacén en esta plataforma. */
    val kind: SecureStoreKind

    /** Guarda (o reemplaza) el secreto de [ref]. `false` si no se pudo. */
    suspend fun put(ref: String, secret: String): Boolean

    /** Lee el secreto de [ref]; `null` si no está o si el almacén falló. */
    suspend fun get(ref: String): String?

    /** Borra el secreto de [ref]. `true` si ya no está. */
    suspend fun remove(ref: String): Boolean

    /** ¿Hay un secreto guardado bajo [ref]? */
    suspend fun has(ref: String): Boolean

    /** Referencias guardadas (sin los valores). */
    suspend fun refs(): Set<String>

    /** Último error del almacén, para mostrarlo en la UI. */
    val lastError: String?
}

/**
 * Qué tecnología protege el almacén en cada plataforma.
 *
 * Los tres niveles existen para que la UI pueda **ser honesta** con el usuario
 * sobre qué lo protege y qué no.
 */
enum class SecureStoreKind(
    val label: String,
    /** El secreto no se puede leer desde otra cuenta de usuario del SO. */
    val boundToUserAccount: Boolean,
    /** El secreto sobrevive a que se robe el archivo del almacén. */
    val survivesFileCopy: Boolean,
    /** Descripción honesta de qué protege. */
    val guarantees: String,
) {
    /** Android: clave AES en el `AndroidKeyStore`, blob en el sandbox. */
    ANDROID_KEYSTORE(
        "Keystore de Android",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "La clave de cifrado vive en el Keystore del sistema. Copiar el " +
            "archivo de la app a otro dispositivo no alcanza.",
    ),

    /** Windows: `CryptProtectData` (DPAPI), atado a la cuenta de Windows. */
    DPAPI(
        "DPAPI de Windows",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "Windows descifra el dato sólo para tu cuenta de usuario en esta " +
            "máquina. Copiar el archivo a otro equipo no sirve.",
    ),

    /** macOS: llavero del sistema, vía la herramienta `security`. */
    MACOS_KEYCHAIN(
        "Llavero de macOS",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "El secreto vive en el llavero, protegido por tu contraseña de " +
            "sesión.",
    ),

    /** Linux: Secret Service (GNOME Keyring / KWallet), vía `secret-tool`. */
    LIBSECRET(
        "Secret Service de Linux",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "El secreto vive en el gestor de secretos del escritorio.",
    ),

    /** Último recurso: archivo cifrado con AES-GCM y clave local. */
    FILE_FALLBACK(
        "Archivo cifrado (protección reducida)",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "El secreto está cifrado con AES-GCM, pero la clave de cifrado " +
            "está en un archivo junto a él. Protege contra otros usuarios de la " +
            "máquina y contra que se copie sólo la configuración; NO protege contra " +
            "malware que corra como vos.",
    ),

    /** No hay almacén: el secreto sólo vive en memoria, esta sesión. */
    MEMORY(
        "Sólo en memoria (esta sesión)",
        boundToUserAccount = true,
        survivesFileCopy = false,
        guarantees = "No se guarda nada en disco: la clave hay que pegarla de nuevo " +
            "cada vez que se abre la app.",
    ),

    /** No hay ninguna opción disponible. */
    NONE(
        "Sin almacén seguro",
        boundToUserAccount = false,
        survivesFileCopy = false,
        guarantees = "Esta plataforma no ofrece un almacén de secretos. La clave no se " +
            "guarda y hay que pegarla en cada sesión.",
    ),
}

/**
 * El almacén de secretos de esta plataforma.
 *
 * Se resuelve una vez y se cachea: todas las implementaciones son singletons
 * (el Keystore, el DPAPI y el llavero son singletons de por sí).
 */
expect fun secureStore(): SecureStore
```

> **Por qué `suspend`:** en Android, `Cipher.doFinal` con una clave del Keystore
> puede tardar decenas de milisegundos (la primera vez, más: puede pedir al
> SO que genere la clave). En desktop, la llamada a `security`/`secret-tool` es
> un proceso externo: 50–300 ms. Nada de eso puede bloquear el hilo de la UI.
> Las implementaciones hacen el trabajo en `ioDispatcher`.

### 2.2 Las referencias (`SecretRef`)

Las referencias son **la única cosa que se escribe en los JSON**. Son
deterministas (para que la migración sea idempotente) y **no son secretas**
(pueden aparecer en un log, en el `AccessLog` o en la UI).

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecretRef.kt
package kitoru.platform

/**
 * Nombres de las referencias del almacén de secretos.
 *
 * Formato: `esquema:ámbito:id`. El prefijo permite auditar ("¿qué credenciales
 * tengo?") y evita colisiones entre tipos.
 */
object SecretRef {
    const val SCHEME_APIKEY = "apikey"
    const val SCHEME_SESSION = "session"

    /** Key de la cátedra (modo "una key para todos"). */
    fun forCourse(courseId: String): String = "$SCHEME_APIKEY:course:$courseId"

    /** Key personal del alumno (modo "una key por alumno"). */
    fun forStudent(enrollmentId: String): String = "$SCHEME_APIKEY:student:$enrollmentId"

    /** Token de sesión / de servicio con un servidor institucional. */
    fun forSession(serverId: String): String = "$SCHEME_SESSION:$serverId"

    /** Sólo el `id` de la referencia, para mostrarla. */
    fun shortLabel(ref: String): String = ref.substringAfterLast(':')

    /** ¿La referencia es de una API key? */
    fun isApiKey(ref: String): Boolean = ref.startsWith("$SCHEME_APIKEY:")
}
```

| Referencia | Qué guarda | Quién la usa |
|---|---|---|
| `apikey:course:IA-2026-1` | La key de la cátedra | El docente (en su dispositivo) |
| `apikey:student:enroll_9f3` | La key personal del alumno | El alumno (en su dispositivo) |
| `session:srv-unlp` | El token de sesión o de servicio del servidor | El alumno (vínculo) y el docente (gestión) |

**Los secretos de una y otraInstalling coinciden en el mismo almacén:** el
dispositivo del docente tiene su key de cátedra (`apikey:course:…`) y su token
de servicio (`session:…`); el del alumno tiene su key personal
(`apikey:student:…`) y su token de sesión.

### 2.3 Veredicto: ¿DPAPI + fallback, o una solución multiplataforma?

La pregunta que hay que responder es: **¿los secretos de escritorio se
protegen con el almacén nativo de cada SO, o con un esquema único
"cifrado con una clave que vive en el keychain"?**

#### Opción A — almacén nativo por SO (DPAPI / llavero / Secret Service)

El secreto se guarda **tal cual** en el almacén del sistema operativo, que ya
lo cifra en reposo y lo ata a la cuenta del usuario.

- **A favor**
  - Es el mecanismo **auditado por el SO**: DPAPI es parte de Windows, el
    llavero es parte de macOS, Secret Service es parte del ecosistema GNOME.
    No es criptografía nuestra.
  - La clave maestra **no la tenemos nosotros**: el SO la maneja (login,
    TPM, keychain del usuario).
  - El respaldo del secreto es responsabilidad del SO (DPAPI lo ata al
    `master key` de la cuenta, que no sale de la máquina).
  - Menos código por backend: guardar = una llamada, leer = una llamada.
- **En contra**
  - Hay que escribir tres implementaciones.
  - macOS y Linux dependen de una CLI (`security`, `secret-tool`) que puede no
    estar instalada; el llavero puede pedir permiso y **bloquear la UI**.
  - En Linux "de laboratorio" el Secret Service puede no correr (sesión sin
    escritorio, SSH, contenedor).

#### Opción B — esquema único multiplataforma: AES-GCM con la clave en el keychain

Un motor de cifrado común; la clave AES se guarda en el keychain del SO (o en
un archivo, si no hay keychain) y los secretos van en un archivo cifrado.

- **A favor**
  - Un solo motor criptográfico, un solo formato de archivo.
  - El archivo se puede versionar, respaldar y mover entre máquinas (con la
    clave al lado, o exportando la clave).
- **En contra**
  - **No agrega una frontera de seguridad**: el keychain ya protege en reposo;
    el AES-GCM encima es criptografía nuestra sobre criptografía del SO. Si
    el keychain está roto, el AES no salva nada.
  - **Agrega un modo de falla nuevo**: si el keychain no está disponible
    (Linux mínimo, CI, WSL, contenedor, sesión SSH), la "clave de AES" cae a
    un archivo 0600 al lado del ciphertext. Eso es **peor** que la opción A en
    esa plataforma, porque encima pagamos el costo de mantener el cifrado.
  - **Duplica el secreto en dos lugares**: en el keychain (la clave AES) y en
    el archivo (el cifrado). Dos superficies que respaldar, dos que auditar.
  - En macOS, guardar la clave AES en el llavero vía la CLI `security` tiene el
    **mismo problema de exposición en `ps`** que guardar el secreto ahí
    (§2.5.2): la CLI no tiene forma de recibir el valor por stdin de forma
    confiable. O sea: la opción B no resuelve el problema que dice resolver.

#### Veredicto: **opción A, con la buena idea de la B conservada**

Se implementa **un motor común con N protectores**:

- El **motor** (un blob por referencia, sellado) es común ⇒ un solo motor y un
  solo formato de archivo.
- El **protector** (quién cifra y dónde vive la clave) es por plataforma ⇒
  DPAPI en Windows, llavero en macOS, Secret Service en Linux, Keystore en
  Android, y **AES-GCM con clave local** como último recurso.

Es decir: la opción B se conserva **sólo como el fallback**, no como el
esquema general. Justificación en una frase: **el keychain del SO no es
portable, pero tampoco existe un keychain multiplataforma; lo que hay son tres
mecanismos nativos que hacen exactamente este trabajo, y cada uno lo hace
mejor que el cifrado propio.**

> **Corolario práctico:** el `expect/actual` es inevitable en cualquier
> escenario. La pregunta "multiplataforma vs Windows-only" está mal planteada:
> la decisión real es **qué protector por SO**, y la respuesta es "el nativo".

### 2.4 Android: ¿`EncryptedSharedPreferences` o Keystore directo?

El enunciado propone `androidx.security:security-crypto`
(`EncryptedSharedPreferences` con una clave del Keystore). Evaluación:

| Criterio | `EncryptedSharedPreferences` (AndroidX) | `AndroidKeyStore` directo |
|---|---|---|
| Estado | **Deprecado** desde `1.1.0-alpha03` (`MasterKey` y `EncryptedSharedPreferences` marcados *"This class is deprecated… Use Android Keystore directly"*). | API soportada (`KeyStore` "AndroidKeyStore" desde API 23; `KeyGenParameterSpec` desde API 23). `minSdk = 24` ✓. |
| Tamaño | Arrastra **Tink** (~1 MB) y una dependencia *alpha*. | Cero dependencias. |
| Modelo de datos | Un mapa de *preferencias* (string → string). | Genérico: sirve para un blob binario. |
| Atomicidad | `SharedPreferences.apply()` es asíncrono y no atómico. | Escribimos nosotros el archivo (escritura atómica, §3.5). |
| Código | ~10 líneas. | ~80 líneas. |
| Limitaciones | Key AES-256-GCM con `setUserAuthenticationRequired(false)`; el `MasterKey` usa AES-SIV para las claves y GCM para los valores. | Control total del alias, del propósito, del `BLOCK_MODE` y de la invalidación. |

**Decisión: `AndroidKeyStore` + AES/GCM directo, sin dependencia nueva.**

El argumento de fondo: `EncryptedSharedPreferences` modela "preferencias
encriptadas", que no es lo que necesitamos (necesitamos "un blob sellado por
referencia"). Usar la API de bajo nivel (que es la que Google recomienda desde
la deprecación) da el mismo resultado con menos peso y sin arrastrar una
librería *alpha*.

Además, sobre "no es KMP": la objeción real es que `EncryptedSharedPreferences`
es una clase **Android**, y por lo tanto vive en `androidMain` de todos modos.
El `expect/actual` no se evita: **es inevitable porque no existe un almacén de
secretos multiplataforma.** Lo que sí cambia es *dónde* se implementa el
`actual` de Android: en `androidMain`, con el `Context` inyectado.

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/SecureStore.android.kt
package kitoru.platform

import android.content.Context
import java.io.File

/**
 * Injecta el `Context` de la app.
 *
 * La app lo llama una vez al arrancar, igual que `KitoruPaths.setDataDir(..)`.
 * Es el mismo patrón que ya usa el repo, para no inventar un contenedor de
 * dependencias en el milestone 1.
 */
object AndroidPlatform {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun requireContext(): Context =
        appContext ?: error("Falta llamar a AndroidPlatform.init(context) al arrancar la app.")
}

actual fun secureStore(): SecureStore = AndroidSecureStore

/**
 * Almacén de secretos en Android: una clave AES-256-GCM en el `AndroidKeyStore`
 * y un blob por referencia dentro del sandbox de la app.
 *
 * El sandbox ya aísla el archivo de las demás apps; el Keystore agrega que el
 * archivo **suelto** (un dump del filesystem, un `adb` con root, una copia del
 * `filesDir`) no sirve de nada sin la clave, que no se puede exportar.
 */
private object AndroidSecureStore : SecureStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kitoru.credentials.v1"

    override val kind: SecureStoreKind = SecureStoreKind.ANDROID_KEYSTORE
    override var lastError: String? = null

    private fun dir(): File =
        File(AndroidPlatform.requireContext().filesDir, "secure").apply { mkdirs() }

    private fun fileFor(ref: String) = File(dir(), SecretRef.shortLabel(ref) + ".bin")

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin autenticación del usuario: la clave protege el dato en
                // reposo, no es una puerta al uso. Pedir la huella en cada
                // mensaje sería insostenible.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    override suspend fun put(ref: String, secret: String): Boolean = withContext(ioDispatcher) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val body = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + cipher.iv.size + body.size)
            out[0] = FORMAT_VERSION
            cipher.iv.copyInto(out, 1)
            body.copyInto(out, 1 + cipher.iv.size)
            fileFor(ref).writeBytes(out)   // atómico vía §3.5 (escritura temporal + rename)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "No se pudo guardar la clave en el Keystore: ${e.message}"
            false
        }
    }

    override suspend fun get(ref: String): String? = withContext(ioDispatcher) {
        val f = fileFor(ref)
        if (!f.exists()) return@withContext null
        try {
            val raw = f.readBytes()
            if (raw.isEmpty() || raw[0] != FORMAT_VERSION) {
                lastError = "El archivo de credenciales tiene un formato desconocido."
                return@withContext null
            }
            val iv = raw.copyOfRange(1, 1 + GCM_IV_SIZE)
            val body = raw.copyOfRange(1 + GCM_IV_SIZE, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // Clave rotada, blob corrupto, Keystore invalidado: no se puede recuperar.
            lastError = "No se pudo leer la clave guardada: ${e.message}"
            null
        }
    }

    override suspend fun remove(ref: String): Boolean = withContext(ioDispatcher) {
        val f = fileFor(ref)
        val existed = f.exists()
        f.delete()
        existed
    }

    override suspend fun has(ref: String): Boolean = fileFor(ref).exists()

    override suspend fun refs(): Set<String> =
        dir().listFiles()?.mapNotNull { f ->
            val name = f.name.removeSuffix(".bin")
            if (name.isBlank()) null else name
        }?.toSet() ?: emptySet()

    private const val FORMAT_VERSION: Byte = 1
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
}
```

> **Nota de honestidad (Android):** el Keystore **no** protege contra un
> dispositivo rooting con la app commitmentada: un atacante con control del
> proceso puede pedirle la clave al Keystore. El Keystore protege el **archivo**
> (un dump del filesystem no sirve), que es el escenario realista. La
> explicación al usuario en la UI es: *"la clave está cifrada con una clave
> que vive en el Keystore del sistema; copia los datos de la app y no te
> sirven."*

### 2.5 Desktop: DPAPI, llavero, Secret Service y el fallback

#### 2.5.1 Windows: DPAPI vía JNA

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/DpapiProtector.kt
package kitoru.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.File

/**
 * Protector de secretos sobre DPAPI (`CryptProtectData`) de Windows.
 *
 * DPAPI ata el dato a la **cuenta de Windows que lo cifró**: el mismo archivo
 * en otra cuenta o en otra máquina no se puede descifrar. Es el mecanismo que
 * usa Windows para las credenciales de las apps de escritorio.
 *
 * Requiere JNA (`com.sun.jna:jna` + `jna-platform`).
 */
internal class DpapiProtector(private val dir: File) : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.DPAPI

    // Entropía fija de la app: no es un secreto, pero hace que el blob sólo
    // lo pueda abrir Kitoru (un `CryptUnprotectData` genérico sin esta
    // entropía falla).
    private val entropy = "kitoru.credentials.v1".toByteArray(Charsets.UTF_8)

    override fun store(ref: String, secret: ByteArray) {
        dir.mkdirs()
        val sealed = Crypt32Util.cryptProtectData(
            secret, "Kitoru", entropy, null, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        )
        fileFor(ref).writeBytes(sealed)
    }

    override fun load(ref: String): ByteArray? {
        val f = fileFor(ref)
        if (!f.exists()) return null
        return Crypt32Util.cryptUnprotectData(
            f.readBytes(), entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        )
    }

    override fun remove(ref: String) { fileFor(ref).delete() }

    override fun refs(): Set<String> =
        dir.listFiles()?.map { it.name.removeSuffix(".dpapi") }?.toSet() ?: emptySet()

    private fun fileFor(ref: String) = File(dir, SecretRef.shortLabel(ref) + ".dpapi")
}
```

**Dependencias** (en `gradle/libs.versions.toml`):

```toml
[versions]
jna = "5.14.0"

[libraries]
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
jna-platform = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }
```

```kotlin
// core/build.gradle.kts → desktopMain
desktopMain.dependencies {
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jnaPlatform)
}
```

> **Alternativa sin JNA:** invocar `powershell.exe -NoProfile -Command
> "[Console]::In.ReadToEnd() | …"` por stdin. Se descarta: agrega ~200 ms por
> llamada, depende de Windows PowerShell 5.1 y es frágil con la codificación.
> JNA cuesta ~2 MB en el paquete y es 20× más rápido. La firma exacta de
> `Crypt32Util` hay que verificarla contra la versión fijada (5.14.0): el
> overload usado es
> `cryptProtectData(byte[], String description, byte[] entropy, CRYPTPROTECT_PROMPTSTRUCT, int flags)`
> y `cryptUnprotectData(byte[], byte[] entropy, int flags)`.

#### 2.5.2 macOS: llavero vía la CLI `security`

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/MacosKeychainProtector.kt
package kitoru.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Protector de secretos sobre el llavero de macOS, vía `/usr/bin/security`.
 *
 * Cada referencia es un ítem `generic-password` del llavero de login.
 *
 * Con `-T /usr/bin/security` se le concede a la herramienta el acceso al
 * ítem, para que macOS no abra un diálogo de permisos en el medio de un chat.
 *
 * OJO: la CLI no ofrece una forma documentada de recibir el valor por stdin
 * (ver la advertencia de abajo), así que el valor viaja como argumento — y en
 * macOS los argumentos de los procesos son visibles a todos los usuarios con
 * `ps`. Por eso la implementación hace un **round-trip de verificación** y
 * degrada al fallback de archivo si no es confiable.
 */
internal class MacosKeychainProtector : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.MACOS_KEYCHAIN

    override fun store(ref: String, secret: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(secret)
        val result = run(
            listOf(
                "security", "add-generic-password",
                "-U",                                   // actualiza si ya existe
                "-a", ref,                              // account = la referencia
                "-s", SERVICE,                          // service
                "-w", encoded,                          // ← el valor como argumento
                "-T", "/usr/bin/security",              // ACL: la herramienta puede leerlo
            ),
            input = null,
        )
        if (result.exitCode != 0) {
            throw IllegalStateException("El llavero de macOS rechazó el guardado: ${result.stderr}")
        }
    }
    // … load / remove / refs
}
```

> **Advertencia honesta sobre macOS:** la CLI `security` **no** ofrece una forma
> documentada y confiable de recibir el secreto por stdin (`-w` sin valor
> abre un prompt desde la terminal). Por eso el ejemplo anterior pasa el valor
> como argumento, y por eso la implementación **hace un round-trip de
> verificación**: después de guardar, vuelve a leer y compara. Si el round-trip
> falla, el almacén **degrada al fallback de archivo** y lo reporta en
> `kind`. Es una degradación **visible**, no silenciosa (D5).
>
> La exposición del argumento en `ps` existe durante unos milisegundos y la
> ven los demás usuarios de la máquina; en un equipo compartido es un riesgo
> real, y por eso el fallback por-SO de macOS debería ser, en la v1, **el
> archivo cifrado** si el round-trip no es confiable, y no el llavero.

#### 2.5.3 Linux: Secret Service vía `secret-tool`

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/SecretToolProtector.kt
internal class SecretToolProtector : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.LIBSECRET

    override fun store(ref: String, secret: ByteArray) {
        // secret-tool store lee el secreto de STDIN (no de argv): sin
        // exposición en `ps`.
        val encoded = Base64.getEncoder().encodeToString(secret)
        val result = run(
            listOf("secret-tool", "store", "--label=Kitoru", "service", SERVICE, "account", ref),
            input = encoded,
        )
        if (result.exitCode != 0) throw IllegalStateException("secret-tool falló: ${result.stderr}")
    }

    override fun load(ref: String): ByteArray? {
        val result = run(listOf("secret-tool", "lookup", "service", SERVICE, "account", ref), input = null)
        if (result.exitCode != 0) return null   // no existe
        return Base64.getDecoder().decode(result.stdout.trim())
    }
    // …
}
```

`secret-tool` es parte de `libsecret-tools` (GNOME Keyring, KWallet vía
Secret Service). Si no está instalado, el protector devuelve "no disponible" y
se cae al fallback.

#### 2.5.4 El fallback: archivo cifrado con AES-GCM

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/AesGcm.kt
package kitoru.platform

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM. Compartido por el fallback de escritorio y por el motor común.
 *
 * Vive en `jvmCommonMain` porque tanto Android como escritorio son JVM y
 * `javax.crypto` está en ambas plataformas.
 */
internal object AesGcm {
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plain)
        val out = ByteArray(1 + cipher.iv.size + body.size)
        out[0] = 1
        cipher.iv.copyInto(out, 1)
        body.copyInto(out, 1 + cipher.iv.size)
        return out
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray? = runCatching {
        if (sealed.isEmpty() || sealed[0] != 1.toByte()) return null
        val iv = sealed.copyOfRange(1, 1 + IV_SIZE)
        val body = sealed.copyOfRange(1 + IV_SIZE, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(body)
    }.getOrNull()
}
```

El fallback escribe **dos** archivos en el directorio del almacén:

- `credentials.key` → los 32 bytes de la clave AES (o el secreto mismo, si se
  elige el modo "sin cifrar" — **no**: nunca en claro).
- `credentials/<ref>.bin` → el blob cifrado.

Permisos en POSIX: `0600` (sólo el dueño). En Windows, `%LOCALAPPDATA%` ya es
por usuario.

> **Honestidad sobre el fallback:** protege contra (a) otros usuarios de la
> misma máquina, (b) que se copie **sólo** la carpeta de configuración, (c) que
> un backup sincronice `~/.kitoru` **si** el almacén está fuera (D6). **No**
> protege contra malware que corra como el usuario, ni contra que se copie
> **todo** el directorio del almacén (la clave va con los datos). Por eso
> `SecureStoreKind.FILE_FALLBACK` lo dice en la etiqueta y la UI lo muestra.

#### 2.5.5 El selector de desktop

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/SecureStore.desktop.kt
package kitoru.platform

import java.io.File

actual fun secureStore(): SecureStore = DesktopSecureStore

/**
 * Elige el protector según el SO y **degrada de forma visible**.
 *
 * La degradación nunca es a texto plano: si no hay ningún protector, el
 * almacén pasa a [SecureStoreKind.MEMORY] y la UI avisa que la clave no se
 * va a guardar (D5).
 */
private object DesktopSecureStore : SecureStore {

    private val osName: String = System.getProperty("os.name").lowercase()

    /** El almacén del SO **no** está en `~/.kitoru`: vive en el directorio de datos del SO. */
    private val baseDir: File = when {
        osName.contains("win") ->
            File(System.getenv("LOCALAPPDATA") ?: "${System.getProperty("user.home")}/AppData/Local", "Kitoru")
        osName.contains("mac") || osName.contains("darwin") ->
            File(System.getProperty("user.home"), "Library/Application Support/Kitoru")
        else ->
            File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "kitoru")
    }

    private val protector: SecretProtector? by lazy {
        val dir = File(baseDir, "credentials").apply { mkdirs() }
        when {
            osName.contains("win") -> runCatching { DpapiProtector(dir) }.getOrNull()
            osName.contains("mac") -> runCatching { MacosKeychainProtector() }.getOrNull()
            else -> runCatching { SecretToolProtector() }.getOrNull()
        } ?: FileAesGcmProtector(dir)
    }
    // … delega en el protector
}
```

#### 2.5.6 Resumen de la matriz por plataforma

| Plataforma | Protector | Cifra en reposo | Atado a la cuenta | Dependencia extra | Si falla |
|---|---|:---:|:---:|---|---|
| **Android** | `AndroidKeyStore` + AES-GCM | ✔ | ✔ (sandbox) | **ninguna** | `MEMORY` |
| **Windows** | DPAPI (`CryptProtectData`) | ✔ | ✔ | JNA 5.14 | `FILE_FALLBACK` |
| **macOS** | Llavero (`security`) | ✔ | ✔ | sólo el SO | `FILE_FALLBACK` |
| **Linux** | Secret Service (`secret-tool`) | ✔ | ✔ | `libsecret-tools` | `FILE_FALLBACK` |
| **Linux sin Secret Service** | AES-GCM + clave local (0600) | ✔ (reducido) | parcial | ninguna | `MEMORY` |
| **Cualquiera, sin permiso de escritura** | — | — | — | — | `MEMORY` (la clave vive sólo en la sesión) |

### 2.6 La regla de oro: nunca caer a texto plano

Tres reglas, para que un "fallback" nunca sea una fuga:

1. **Nunca** se escribe un secreto en un archivo de configuración, aunque el
   almacén no esté disponible. Si no hay almacén, el secreto **no se guarda**.
2. La degradación es **visible**: `SecureStoreKind` cambia, y la UI lo muestra
   ("En este equipo no hay almacén seguro: la clave no se va a guardar").
3. El usuario puede **forzar** el modo `MEMORY` desde Ajustes, para los que
   prefieran que la clave viva sólo en la sesión.

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecureStorePolicy.kt
package kitoru.platform

/**
 * Si el usuario pidió que ningún secreto se guarde en disco.
 *
 * Vive en `AppSettings` (`domain/08-permisos.md` §9.1) y se consulta antes de
 * cada `put`.
 */
val AppSettings.neverPersistSecrets: Boolean
    get() = !storeSecrets
```

### 2.7 Dónde vive el almacén (y por qué no en `~/.kitoru`)

| | Carpeta de datos de Kitoru | Carpeta del almacén de secretos |
|---|---|---|
| Desktop | `~/.kitoru` | `%LOCALAPPDATA%\Kitoru` · `~/Library/Application Support/Kitoru` · `~/.local/share/kitoru` |
| Android | `filesDir/kitoru` | `filesDir/secure` (dentro del sandbox) |

`~/.kitoru` es la carpeta que la gente **copia, sincroniza y respalda**. Los
secretos no van ahí (D6). En Android el sandbox ya aísla; la separación entre
`filesDir/kitoru` y `filesDir/secure` sirve para que el "borrar mis datos"
(`security/02-privacidad.md` §4.3) pueda decidir si borra también las
credenciales (por defecto: **sí**, y avisa).

---

## 3. El modelo de datos

### 3.1 `ModelPolicy` sin la key

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt
@Serializable
data class ModelPolicy(
    val mode: ModelMode = ModelMode.REMOTE,

    // ---- Servidor remoto (Kitoru-Server / API compatible OpenAI) ----
    val remoteBaseUrl: String = "",

    /**
     * **Referencia** al secreto de la API key en el almacén seguro.
     *
     * NO es la key. Es un alias opaco y determinista
     * (`apikey:course:IA-2026-1` o `apikey:student:enroll_9f3`) que apunta a
     * un secreto guardado en el [kitoru.platform.SecureStore] de la plataforma.
     *
     * `null` = esta política no tiene credencial propia (usa la de la
     * cátedra, o la que emitió el servidor para este alumno).
     */
    val apiKeyRef: String? = null,

    val modelName: String = "",
    // … el resto de los campos, sin cambios …
)
```

**Qué cambia y qué no:**

| | Antes | Ahora |
|---|---|---|
| Campo | `apiKey: String = ""` | `apiKeyRef: String? = null` |
| `toString()` del `data class` | Imprime la key 🔴 | Imprime la referencia (no es secreto) 🟢 |
| `courses.json` | Contiene la key 🔴 | Contiene `apiKeyRef` 🟢 |
| `ChatThread.effectiveModel` | Contiene la key 🔴 | Contiene la referencia 🟢 (y `redacted()` la borra) |
| `GatewayConfig.apiKey` | `String` | Sigue siendo `String`, pero **sólo existe en memoria**, nunca se serializa (ver §3.6) |

#### Por qué `String?` y no `String = ""`

`null` y `""` significan cosas distintas y el modelo necesita distinguirlas:

- `null` → **no hay credencial configurada**. Es el estado normal de una
  política de cátedra que no usa IA alojada, o de un alumno antes de que el
  servidor le emita su key.
- `""` → **hay una credencial configurada pero está rota** (la ref no existe
  en el almacén, o el almacén no está disponible). Es un error que la UI tiene
  que mostrar ("no encontramos la clave guardada; volvé a pegarla"), no un
  estado normal.

Con `String = ""` los dos casos se mezclan y el usuario ve "falta la clave"
cuando en realidad la tiene pero no se pudo leer. Es el mismo criterio que usa
`InstitutionLink?` en `domain/09-identidad.md`.

### 3.2 `resolveFor` y el bloqueo de `API_KEY`

El bloqueo de `API_KEY` sigue significando lo mismo ("el alumno no elige la
credencial"), pero ahora hay **dos** credenciales en juego: la de la cátedra y
la del alumno. El caso "key por alumno" (D9) rompe la regla ingenua.

```kotlin
fun resolveFor(studentChoice: ModelPolicy): ModelPolicy {
    if (!allowStudentOverride) return this
    var out = studentChoice
    if (LockedField.API_KEY in lockedFields) {
        // La cátedra impone su credencial… **si tiene**.
        //
        // En modo "una key por alumno" la cátedra NO tiene key: la emite el
        // servidor, una por alumno, y el bloqueo de API_KEY significa otra
        // cosa: "no uses una key ajena a la que te emitió el servidor para
        // esta cátedra". En ese caso se respeta la del alumno.
        if (apiKeyRef != null) out = out.copy(apiKeyRef = apiKeyRef)
    }
    if (LockedField.BASE_URL in lockedFields) out = out.copy(remoteBaseUrl = remoteBaseUrl)
    if (LockedField.MODEL_NAME in lockedFields) out = out.copy(modelName = modelName)
    if (LockedField.SYSTEM_PROMPT in lockedFields) out = out.copy(systemPrompt = systemPrompt)
    if (LockedField.TEMPERATURE in lockedFields) out = out.copy(temperature = temperature)
    if (LockedField.MAX_TOKENS in lockedFields) out = out.copy(maxTokens = maxTokens)
    if (LockedField.TOP_K in lockedFields) out = out.copy(topK = topK)
    return out
}
```

| Situación | `apiKeyRef` de la cátedra | `apiKeyRef` del alumno | Resultado |
|---|---|---|---|
| Key por alumno, `API_KEY` bloqueado | `null` | `apikey:student:enroll_9f3` | Se usa la del alumno (la del servidor) |
| Key por alumno, `API_KEY` libre | `null` | `apikey:student:enroll_9f3` | Se usa la del alumno; puede pegar otra |
| Key compartida, `API_KEY` bloqueado | `apikey:course:IA-2026-1` | `null` | Se usa la de la cátedra |
| Key compartida, `API_KEY` libre | `apikey:course:IA-2026-1` | `apikey:course:IA-2026-1` (la misma) | Se usa la de la cátedra |
| Sin key en ningún lado | `null` | `null` | El chat falla con un mensaje claro (§3.6) |

### 3.3 `redacted()`

`domain/04-conversaciones.md` §8.4 ya define `redacted()`. Se actualiza para la
nueva forma:

```kotlin
/**
 * Copia sin la referencia a la credencial (y sin las rutas locales del equipo).
 *
 * Lo que se persiste en un hilo y lo que se exporta en un `.kitoru` es
 * siempre esta versión: la credencial de la cátedra no se escribe en el disco
 * del alumno ni viaja en una exportación.
 */
fun redacted(): ModelPolicy = copy(
    apiKeyRef = null,
    modelsDir = "",
    llamaServerPath = "",
)
```

Además, `redacted()` debe ser **obligatorio** en el punto de escritura, no una
convención. Se propone que `ChatThread.effectiveModel` se sete siempre con
`redacted()` en el repositorio (`FileThreadRepository.save`), de modo que
ningún camino de escritura pueda saltárselo.

### 3.4 La migración: de `apiKey` en claro a `apiKeyRef`

Este es el punto más delicado del cambio, y la razón por la que `apiKey` **no
puede simplemente borrarse** del `data class`.

#### 3.4.1 El problema con `ignoreUnknownKeys = true`

`KitoruJson` tiene `ignoreUnknownKeys = true`. Eso resuelve el **crash**
(un `courses.json` viejo con `apiKey` no rompe la deserialización), pero **no
resuelve la fuga**: el campo `apiKey` sigue escrito en el archivo hasta que
algo lo reescriba. Peor: como `ModelPolicy` ya no declara `apiKey`, la
deserialización **lo ignora** → la app no puede ni leerlo para migrarlo.

Por eso la migración **no** puede ser "leer el modelo tipado y guardar el
secreto": hay que hacerlo **a nivel de archivo**, sobre el JSON crudo.

#### 3.4.2 El migrador

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/SecretMigration.kt
package kitoru.domain

import kotlinx.serialization.json.*
import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.SecureStore
import kitoru.platform.SecretRef

/**
 * Migración de secretos: mueve las claves que quedaron **en texto plano**
 * dentro de los JSON al almacén seguro y las borra del archivo.
 *
 * - **Corre en cada arranque** y es **idempotente**: después de la primera
 *   pasada ya no queda ningún `apiKey` en los JSON, así que no hace nada.
 * - Sólo toca el JSON crudo (`JsonElement`), no el modelo tipado: el modelo
 *   ya no tiene un campo `apiKey` y no podría leerlo.
 * - Si el almacén seguro **no está disponible**, **no borra nada**: deja el
 *   archivo como está y avisa, para no perder la clave del usuario. La UI
 *   ofrece "borrar la clave del archivo y volver a escribirla" (que sí
 *   descarta el secreto, a costa de que el usuario tenga que pegarla de
 *   nuevo).
 */
class SecretMigration(
    private val fs: FileSystem,
    private val store: SecureStore,
) {

    /** Resultado de una pasada de migración. */
    data class Report(
        /** Cuántos secretos se movieron al almacén. */
        val migrated: Int = 0,
        /** Cuántos secretos en claro se borraron sin migrar (hilos). */
        val discarded: Int = 0,
        /** Archivos que se reescribieron. */
        val rewritten: List<String> = emptyList(),
        /** Mensaje para la UI (vacío = nada que avisar). */
        val warning: String = "",
    )

    suspend fun migrate(): Report {
        var report = Report()

        // 1) La cátedra: la key del docente SÍ se migra (es su credencial).
        report = migrateFile(
            path = FilePath("config/courses.json"),
            refFor = { courseId -> SecretRef.forCourse(courseId) },
            keep = true,
            report = report,
        )

        // 2) La tabla de keys del docente: idem.
        report = migrateFile(
            path = FilePath("config/api-keys.json"),
            refFor = { grantId -> "apikey:grant:$grantId" },
            keep = true,
            report = report,
        )

        // 3) Los hilos: la key que se guardó ahí era la **efectiva** (puede
        //    ser la personal del alumno). No se migra: se borra. El hilo
        //    registra qué modelo se usó, no con qué credencial.
        report = migrateFile(
            path = FilePath("config/threads.json"),
            refFor = { _ -> null },
            keep = false,
            report = report,
        )

        return report
    }

    /**
     * Reescribe un JSON quitando todo campo `apiKey` en claro.
     *
     * El recorrido es **genérico y recursivo**: sirve para
     * `modelPolicy.apiKey`, para `effectiveModel.apiKey` anidado en un hilo y
     * para cualquier otro nivel, sin tener que conocer la forma de cada
     * archivo.
     */
    private suspend fun migrateFile(
        path: FilePath,
        refFor: (ownerId: String) -> String?,
        keep: Boolean,
        report: Report,
    ): Report {
        if (!fs.exists(path)) return report
        val text = try { fs.readText(path) } catch (_: Exception) { return report }
        val root = try { KitoruJson.parseToJsonElement(text) } catch (_: Exception) { return report }

        val moved = mutableListOf<Pair<String, String>>()   // ref → secreto
        val cleaned = stripSecrets(root, ownerId = "", refFor = refFor, keep = keep, moved = moved)

        if (moved.isEmpty() && cleaned == root) return report

        // 1) ¿Se puede guardar en el almacén? Si no, no se borra nada.
        if (moved.isNotEmpty() && store.kind == SecureStoreKind.NONE) {
            return report.copy(warning = "Hay una clave guardada en texto plano en " +
                "${path.name} y este equipo no tiene un almacén seguro. Se dejó el " +
                "archivo como estaba para no perderla.")
        }
        for ((ref, secret) in moved) {
            if (!store.put(ref, secret)) {
                return report.copy(warning = "No se pudo guardar la clave en el almacén " +
                    "seguro: ${store.lastError.orEmpty()}. Se dejó el archivo como estaba.")
            }
        }

        // 2) Se reescribe el archivo SIN el secreto. Escritura atómica (§3.5).
        fs.writeTextAtomic(path, KitoruJson.encodeToString(JsonElement.serializer(), cleaned))
        return report.copy(
            migrated = report.migrated + moved.size,
            discarded = report.discarded + if (keep) 0 else countLegacyKeys(root),
            rewritten = report.rewritten + path.name,
        )
    }
}

/**
 * Cuenta cuántos campos `apiKey` con valor había en el árbol original.
 *
 * Sirve para avisar "se quitó la clave en claro de N conversaciones".
 */
private fun countLegacyKeys(element: JsonElement): Int = when (element) {
    is JsonObject -> element.entries.sumOf { (k, v) ->
        val isLegacy = k == "apiKey" &&
            (v as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        if (isLegacy) 1 else countLegacyKeys(v)
    }
    is JsonArray -> element.sumOf { countLegacyKeys(it) }
    else -> 0
}
```

El recorrido recursivo (`stripSecrets`) es la parte no trivial:

```kotlin
/**
 * Devuelve una copia del árbol sin los campos `apiKey`, y recoge los secretos
 * que encontró.
 *
 * - Si encuentra `"apiKey": "<no vacío>"`:
 *   - `keep = true`  → guarda el secreto bajo la ref y pone `"apiKeyRef": ref`.
 *   - `keep = false` → borra el campo y no pone nada.
 * - Los demás campos se copian tal cual.
 *
 * [ownerId] es el id del objeto que se está recorriendo: sirve para
 * construir la ref (`apikey:course:<id>`). Se deduce del propio objeto (su
 * `id` o su `courseId`) y se hereda hacia los hijos.
 */
private fun stripSecrets(
    element: JsonElement,
    ownerId: String,
    refFor: (ownerId: String) -> String?,
    keep: Boolean,
    moved: MutableList<Pair<String, String>>,
): JsonElement = when (element) {
    is JsonObject -> {
        val out = LinkedHashMap<String, JsonElement>(element.size)
        val legacy = (element["apiKey"] as? JsonPrimitive)?.contentOrNull
        val ownId = (element["id"] as? JsonPrimitive)?.contentOrNull
            ?: (element["courseId"] as? JsonPrimitive)?.contentOrNull
            ?: ownerId
        for ((k, v) in element) {
            when {
                k == "apiKey" && legacy != null && legacy.isNotBlank() -> {
                    val ref = refFor(ownId)
                    if (keep && ref != null) {
                        moved += ref to legacy
                        out["apiKeyRef"] = JsonPrimitive(ref)
                    }
                    // keep == false → se omite la clave: el secreto desaparece
                }
                else -> out[k] = stripSecrets(v, ownId, refFor, keep, moved)
            }
        }
        JsonObject(out)
    }
    is JsonArray -> JsonArray(element.map { stripSecrets(it, ownerId, refFor, keep, moved) })
    else -> element
}
```

**Idempotencia:** después de la primera pasada, el JSON no tiene ningún
`apiKey` → `moved` queda vacío → no se reescribe nada → la segunda pasada no
hace nada.

**Cases borde resueltos:**

| Caso | Qué pasa |
|---|---|
| `courses.json` con `apiKey` en claro | Se migra a `apikey:course:<id>` y el archivo se reescribe sin la key. |
| `courses.json` con `apiKey: ""` | No se toca (no hay secreto). El `apiKeyRef` queda `null`. |
| `courses.json` con `apiKey` y **sin** `apiKeyRef` | Idem primer caso. |
| `courses.json` con `apiKeyRef` ya migrado **y** un `apiKey` residual (migración a medias) | El residual se migra y pisa el `apiKeyRef` (la versión en claro es la más reciente). |
| `courses.json` corrupto | `parseToJsonElement` falla → no se toca nada (fail-safe). |
| `courses.json` con `apiKey` pero el almacén no está disponible | **No se borra nada**; se avisa (fail-safe: no se pierde la credencial del usuario). |
| `threads.json` con `effectiveModel.apiKey` | Se borra (sin migrar) y se cuenta en `discarded`. La UI avisa: *"Se quitó una clave en texto plano de tus conversaciones; si la necesitás, volvé a configurarla."* |
| `api-keys.json` con `ApiKeyGrant.key` en claro | Se migra a `apikey:grant:<id>`; el campo `key` desaparece y queda `keyRef`. |

**Cuándo corre:** al arrancar la app, **antes** de leer los repositorios.
Como es una operación de I/O sobre un par de archivos, va en `ioDispatcher` y
no bloquea el primer render.

#### 3.4.3 La migración de `ApiKeyGrant`

`teacher/05-config-modelo.md` §1.5 propone `ApiKeyGrant.key: String` (la key
en claro en `api-keys.json`). Con este documento, ese campo **no existe**:
se reemplaza por `keyRef: String?`, igual que en `ModelPolicy`. Es el mismo
patrón y la misma migración.

### 3.5 Escritura atómica

Hoy `JsonStore._write` hace `fs.writeText` (escribe encima). Si la app se
muere a mitad de la escritura, `courses.json` queda **truncado** → con
`_read` devolviendo `empty()`, la cátedra del usuario desaparece. Con secretos
de por medio, un archivo a medias es peor.

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt
interface FileSystem {
    // … lo que ya existe …
    /**
     * Escribe [text] de forma atómica: primero en un archivo temporal al
     * lado, después `rename` sobre el destino.
     *
     * Un corte de luz a mitad de la escritura deja el archivo viejo intacto,
     * no uno truncado. Es lo que permite que la migración de secretos pueda
     * reescribir `courses.json` sin riesgo.
     */
    fun writeTextAtomic(path: FilePath, text: String)
}
```

Implementación JVM (`jvmCommonMain`): `Files.move(tmp, dst, REPLACE_EXISTING,
ATOMIC_MOVE)` en escritorio; en Android, `File.renameTo` (atómico dentro del
mismo filesystem) con fallback a copiar.

`JsonStore._write` pasa a usar `writeTextAtomic`. Es un cambio chico que
mejora todo el modelo de datos, no sólo la parte de secretos.

### 3.6 Resolver la credencial en runtime

El secreto se lee del almacén **justo antes de la petición** y se inyecta en
la configuración del gateway. Nunca se queda en un `data class` serializable
ni en un estado de la UI.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CredentialResolver.kt
package kitoru.domain

import kitoru.platform.SecureStore

/**
 * Resuelve la credencial efectiva de una política.
 *
 * Es el **único** punto donde un secreto sale del almacén. Devuelve un
 * [String] "peligroso": el que lo reciba tiene que tratararlo como secreto
 * (no loguearlo, no guardarlo).
 */
class CredentialResolver(private val store: SecureStore) {

    /** Resultado de la resolución. */
    sealed interface Resolution {
        /** Se encontró la credencial. */
        data class Found(val secret: String) : Resolution

        /** La política no declara credencial. */
        data object Missing : Resolution

        /** Se declaró, pero no se pudo leer (almacén caído o ref rota). */
        data class Unreadable(val reason: String) : Resolution
    }

    /**
     * Lee el secreto de [ref].
     *
     * **Nunca** lanza: un almacén caído no puede voltear el chat.
     */
    suspend fun resolve(ref: String?): Resolution {
        if (ref == null) return Resolution.Missing
        val secret = store.get(ref)
            ?: return if (store.lastError != null) {
                Resolution.Unreadable(store.lastError!!)
            } else {
                Resolution.Missing
            }
        return if (secret.isBlank()) Resolution.Missing else Resolution.Found(secret)
    }
}
```

Y el factory del gateway:

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/GatewayFactory.kt
/**
 * Construye el gateway con la credencial resuelta **en el momento**.
 *
 * El `GatewayConfig` que lleva la key es un objeto **efímero**: no se
 * guarda, no se serializa y no se muestra. [GatewayConfig.toString] está
 * sobrescrito para que no pueda filtrar la key por accidente (§6.4).
 */
class GatewayFactory(
    private val resolver: CredentialResolver,
    private val policy: ModelPolicy,
) {
    suspend fun create(): LlmGateway {
        val resolution = resolver.resolve(policy.apiKeyRef)
        val key = when (resolution) {
            is CredentialResolver.Resolution.Found -> resolution.secret
            is CredentialResolver.Resolution.Missing ->
                throw LlmException(
                    LlmErrorCode.NO_CONFIGURADO,
                    "Falta la clave de API. Pedila a tu docente o pegala en los ajustes.",
                )
            is CredentialResolver.Resolution.Unreadable ->
                throw LlmException(
                    LlmErrorCode.NO_CONFIGURADO,
                    "No se pudo leer la clave guardada: ${resolution.reason}. " +
                        "Volvé a pegarla en los ajustes.",
                )
        }
        return OpenAiCompatibleGateway(
            baseUrl = policy.remoteBaseUrl,
            apiKey = key,
            model = policy.modelName,
            temperature = policy.temperature,
            maxTokens = policy.maxTokens,
            chatTimeoutMs = 0L,
        )
    }
}
```

> `GatewayConfig` (que ya existe en `llm-api/.../config/GatewayConfig.kt`)
> queda para el caso "el gateway ya tiene su config" (modelo local, pruebas),
> pero **su `toString()` se sobrescribe** (§6.4).

---

## 4. La key por alumno

### 4.1 El problema de la key compartida

Con una única key para toda la cátedra:

| Problema | Detalle |
|---|---|
| **Radio de impacto** | Si la filtran **un** alumno, hay que rotarla para **los 60**. Todos dejan de trabajar hasta que el docente re-dispache la nueva. |
| **Sin atribución** | El servidor no puede saber quién consumió qué: la analítica por alumno (que el enunciado pide: "estadísticas, comportamiento y calidad") se pierde. |
| **Sin cuota por alumno** | No se puede poner un tope por persona; el que abuse, gasta la cuota de todos. |
| **Revocación masiva** | "Dar de baja" a un alumno no puede revocar su acceso al modelo, porque comparte la key. |
| **El alumno ve la de los demás** | Si el docente comparte el bundle con la key, **todos ven la misma clave**: cualquiera puede usarla fuera de la app, y el docente no puede saber quién fue. |
| **Filtración trivial** | Con 60 alumnos usando el mismo secreto, la probabilidad de que uno la pegue en un chat o la mande por WhatsApp es altísima. |

### 4.2 Veredicto: **una key por alumno**, emitida por el servidor

**Decisión: cuando hay Kitoru-Server, el modo por defecto es `PER_STUDENT`.**
La key compartida de la cátedra queda como el modo de **respaldo** (el
"camino A" de `teacher/05-config-modelo.md` §3.2: la institución le dio una key
al docente y no hay servidor que emita nada).

Justificación:

1. **Es lo que el enunciado ya pide del servidor**: "Kitoru-Server **emite API
   keys** para que los alumnos chateen contra la IA alojada (la institución la
   provee; **vence al terminar la cursada**)". Una key por alumno cumple la
   promesa de vencimiento **individual** (`expiresAt ≤ term.endsAt`,
   `platform/03-kitoru-server-api.md` §6.2).
2. **La rotación deja de ser un evento masivo**: se rota la de un alumno.
3. **Habilita la analítica**: el servidor puede atribuir el uso a una
   `subjectId` (que es justo lo que la analítica del docente necesita).
4. **Habilita la cuota por alumno** y el `rate limit` por minuto.
5. **La revocar es local**: `DELETE /v1/keys/{id}` (o `POST …/suspend`) afecta
   a un alumno.
6. **El costo en el servidor es bajo**: una fila más por alumno en la tabla de
   keys, con `subjectId`. El modelo de `platform/03-kitoru-server-api.md` §2 ya
   lo soporta (`X-Kitoru-Student-Id`).

Lo que **no** cambia: la key sigue siendo un **secreto de la app del alumno**,
guardado en el almacén seguro del alumno, nunca en un JSON.

### 4.3 El modelo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ApiKeyGrant.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Una credencial de acceso al servidor de la cátedra.
 *
 * **Nunca contiene el secreto**: sólo la [keyRef] (un alias al almacén
 * seguro) y el [keyLast4] (para reconocerla sin exponerla). El campo `key`
 * que proponía `teacher/05-config-modelo.md` §1.5 se elimina: es un secreto
 * en un JSON.
 */
@Serializable
data class ApiKeyGrant(
    val id: String,
    val courseId: String,

    /** Id del alumno dueño de la key; vacío = key de la cátedra. */
    val studentId: String = "",
    val studentName: String = "",

    /**
     * Referencia al secreto en el almacén seguro.
     *
     * `apikey:course:<courseId>` para la key de la cátedra;
     * `apikey:student:<enrollmentId>` para la personal.
     */
    val keyRef: String? = null,

    /** Últimos 4 caracteres del secreto, para reconocerlo sin exponerlo. */
    val keyLast4: String = "",

    /** Id público de la credencial en el servidor (el `kid`). No es secreto. */
    val serverKeyId: String = "",

    /** Estado según el servidor. */
    val status: ApiKeyStatus = ApiKeyStatus.ACTIVA,

    val issuedAt: Long = 0L,
    /** Epoch ms. 0 = sin vencimiento conocido. Nunca pasa del fin de la cursada. */
    val expiresAt: Long = 0L,
    /** Máximo de alumnos que pueden usar esta key (0 = sin tope). */
    val maxUses: Int = 0,
    /** Cuántas renovaciones se hicieron. */
    val renewals: Int = 0,
    /** Si el servidor ya no puede devolver el secreto en claro. */
    val masked: Boolean = true,
)

/** Estado de una credencial. */
@Serializable
enum class ApiKeyStatus(val label: String) {
    ACTIVA("Activa"),
    POR_VENCER("Por vencer"),
    VENCIDA("Vencida"),
    PAUSADA("Pausada"),
    REVOCADA("Revocada"),
    ROTA("Rotada"),
}
```

Y el modo, que ya existe en `teacher/05-config-modelo.md` §1.5:

```kotlin
@Serializable
enum class ApiKeyMode(val label: String, val description: String) {
    COURSE(
        "Una key para toda la cátedra",
        "Todos los alumnos usan la misma clave. Simple, pero si se filtra hay que " +
            "rotarla entera y se pierde la atribución del uso.",
    ),
    PER_STUDENT(
        "Una key por alumno",
        "Cada alumno usa su propia clave, emitida por el servidor y vencida con la " +
            "cursada. Se puede revocar una sin tocar el resto.",
    ),
}
```

**Cambio de default:** `Course.apiKeyMode` pasa de `COURSE` a **`PER_STUDENT`**
cuando hay Kitoru-Server configurado, y a `COURSE` cuando el docente pega una
key a mano (no hay servidor que emita). Es una decisión *derivada*, no un
default fijo: el docente puede forzar `COURSE` en un piloto sin servidor.

### 4.4 El flujo de emisión y de entrega

```
ALUMNO                                          KITORU-SERVER
──────                                          ─────────────
1. Se inscribe con el código de la cátedra
   POST /v1/enrollments {courseId, joinCode}
                                                → crea la inscripción
2. La respuesta trae la key del alumno:
   { enrollment: {...}, apiKey: "ktor_K01J…kA" }
                                                (el secreto viaja UNA vez)
3. La app la guarda en el almacén seguro:
   put("apikey:student:enroll_9f3", "ktor_K01J…kA")
   y NUNCA la escribe en ningún JSON.
4. Chatea:
   POST /v1/chat/completions
   Authorization: Bearer ktor_K01J…kA
   X-Kitoru-Student-Id: enroll_9f3
                                                → valida subject vs key
5. GET /v1/keys/me  → estado, cuota, vencimiento
6. POST /v1/keys/{id}/extend  (auto-renovación)
```

**Detalle de seguridad del paso 2:** la respuesta de la inscripción trae la
key en claro **una sola vez** (el servidor guarda sólo el hash). Si el alumno
cierra la app antes de guardarla, la key se pierde y tiene que pedir otra
(`POST /v1/keys` con su token de sesión, o al docente). La UI debe **pedir
confirmación** ("¿guardar la clave?") antes de cerrar el flujo, como ya se
propone en `teacher/05-config-modelo.md` §3.2 para el docente.

**En el modo `COURSE` (sin servidor):** el docente pega la key en la pantalla
de configuración. La app la guarda en **su** almacén seguro
(`apikey:course:<id>`) y **no** la escribe en `courses.json`. El alumno la
recibe por el canal que elija (correo, chat, QR) y la pega en su app, que la
guarda en **su** almacén (`apikey:course:<id>` — la misma ref, en otro
dispositivo). La app **no puede** proteger un secreto que el usuario ya
copió a un canal que no controla: lo que sí hace es no lo persistir en claro
y avisar del riesgo.

**El bundle `.kitoru`:** el export **no lleva credenciales** por defecto
(`redacted()`). Si el docente quiere incluirla (modo `COURSE`, sin servidor),
tiene que marcarlo explícitamente y la app **avisa**:

> "El paquete va a incluir la clave de la cátedra en texto plano. Cualquiera
> que lo abra podrá usarla. ¿Seguir?"

Y al **importar** un paquete con credencial, el importador la extrae
**inmediatamente** al almacén seguro y reescribe el paquete sin ella (el mismo
mecanismo que la migración, §3.4.2).

### 4.5 Qué ve cada uno

| | Docente | Alumno |
|---|---|---|
| **La key de la cátedra** (modo `COURSE`) | En su app, enmascarada (`ktor_K01J…kA`), con botón de copiar | En su app, enmascarada, si se la pasaron |
| **La key de otro alumno** | **Nunca.** Sólo el `kid`, el `keyLast4` y el estado | **Nunca** |
| **El `kid`** | Sí (es público: aparece en los logs del servidor) | Sí, si lo parsea de su token |
| **El estado de cada key** | Sí: `ACTIVA` / `POR_VENCER` / `VENCIDA` / `PAUSADA` / `REVOCADA` / `ROTA`, con `expiresAt`, cuota consumida y `renewals` | Sí, la suya: chip "🔑 Vence el 31/12/2026" + botón "Renovar" (si `canSelfRenew`) |
| **El token de servicio** (para gestionar las keys) | Sí, en su almacén seguro (`session:<serverId>`) | No |

### 4.6 Verificación del lado del servidor

Para que la key personal sirva de algo, el servidor tiene que atarla al alumno:

- La key tiene un `subjectId` (el `Enrollment.id` del servidor).
- Cada petición de inferencia manda `X-Kitoru-Student-Id: enroll_9f3`.
- El servidor compara: si el `subjectId` de la key **no** coincide con el header
  ⇒ `403 student_mismatch` ("Esta clave no es la tuya.").

Sin esa comprobación, un alumno que se pase la key de otro puede usarla y
**contaminar la analítica** de esa persona. Con ella, la atribución es
confiable, y revocar la key de un alumno es efectivo de verdad.

> **Nota de coordinación:** el endpoint de inscripción que devuelve la key
> (`POST /v1/enrollments` → `{enrollment, apiKey}`) es una **extensión** de
> `platform/03-kitoru-server-api.md`, que hoy no lo define. Hay que
>agregarlo como §5.9 de ese documento ("emitir la key propia en la inscripción")
> o como endpoint aparte `POST /v1/keys/self`. **Deuda a cerrar.**

---

## 5. Mostrar la key en la UI

### 5.1 Enmascarado

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/SecretMask.kt
package kitoru.domain

/**
 * Cómo se muestra un secreto sin mostrarlo.
 *
 * Nunca se muestra la key en claro salvo que el usuario pulse "Mostrar"
 * explícitamente (y entonces se auto-oculta a los 30 s).
 */
object SecretMask {

    /**
     * Versión enmascarada de una credencial de Kitoru.
     *
     * `ktor_K01J9Z8Y2QK4M_x7Fq…kA` → `ktor_K01J…kA`
     *
     * El `kid` (la parte pública) se muestra parcialmente porque no es
     * secreto; el `secret` (la parte privada) se muestra **sólo** en sus
     * últimos 4 caracteres, que es lo que el servidor también expone como
     * `tokenLast4` para que el usuario pueda reconocer su key.
     */
    fun mask(secret: String): String {
        val s = secret.trim()
        if (s.isEmpty()) return ""
        val parts = s.split('_')
        if (parts.size >= 3 && parts[0] == "ktor") {
            val kid = parts[1]
            val tail = parts.last().takeLast(4)
            return "ktor_${kid.take(4)}…$tail"
        }
        // Credencial de otro formato (una key de proveedor, por ejemplo).
        return if (s.length <= 8) "•".repeat(s.length)
        else s.take(3) + "…" + s.takeLast(4)
    }

    /** La etiqueta corta que se ve en el chip: "…kA" o "Configurada". */
    fun chipLabel(secret: String): String =
        if (secret.isBlank()) "Sin clave" else mask(secret)
}
```

> **Por qué los últimos 4 y no los primeros:** en un token `ktor_<kid>_<secret>`
> el prefijo es público (el `kid` sale en los logs del servidor) y el sufijo es
> el que identifica de forma única a la credencial. Mostrar el prefijo del
> *secreto* ayudaría a distinguir dos keys del mismo `kid` tras una rotación, y
> es el criterio que ya usa el servidor (`tokenLast4`).

### 5.2 La pantalla de la clave

```
┌─ Clave de API ────────────────────────────────────────────────────┐
│                                                                       │
│   ktor_K01J…kA                                    [ 👁 Ver ] [ 📋 ]  │
│                                                                       │
│   ● Activa · Vence el 31/12/2026 · Cursada 2026-2                     │
│                                                                       │
│   ▸ Se guarda cifrada en el almacén seguro de este equipo.            │
│   ▸ No se escribe en ningún archivo de configuración.                 │
│   ▸ Se manda únicamente al servidor, en el encabezado Authorization.  │
│                                                                       │
│   [ Probar conexión ]                              [ Quitar la clave ]│
└───────────────────────────────────────────────────────────────────────┘
```

- El campo es de **un solo sentido**: la app **nunca** vuelve a mostrar la key
  en claro al abrir la pantalla (la lee del almacén para probar la conexión,
  pero la caja de texto arranca vacía y enmascarada).
- "Ver" (el ojo) muestra la key en claro durante 30 s y se vuelve a enmascarar
  sola. En Android se puede marcar la pantalla con `FLAG_SECURE` para que no
  entre en las capturas de pantalla.
- "Quitar la clave" borra la entrada del almacén (`remove(ref)`) y pone
  `apiKeyRef = null` en la política.

### 5.3 Copiar al portapapeles

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Clipboard.kt
package kitoru.platform

/**
 * Portapapeles del sistema.
 *
 * Es una superficie **pública**: cualquier app puede leerlo. Por eso la app
 * avisa y borra sola lo que copió.
 */
interface ClipboardGuard {
    /** Copia [text] y programa su borrado en [clearAfterMs]. */
    suspend fun copySensitive(text: String, clearAfterMs: Long = DEFAULT_CLEAR_MS)

    companion object {
        /** 30 s: suficiente para pegar, corto para que no quede. */
        const val DEFAULT_CLEAR_MS = 30_000L
    }
}

/** Portapapeles de la plataforma. */
expect fun clipboardGuard(): ClipboardGuard
```

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/Clipboard.android.kt
actual fun clipboardGuard(): ClipboardGuard = AndroidClipboardGuard

private object AndroidClipboardGuard : ClipboardGuard {
    override suspend fun copySensitive(text: String, clearAfterMs: Long) {
        val ctx = AndroidPlatform.requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Clave de Kitoru", text))
        scheduleClear(cm, text, clearAfterMs)
    }
    // … programa el borrado con un Handler en el hilo principal …
}
```

**Textos de la UI (literales):**

Al copiar:

> **Clave copiada.** El portapapeles del sistema es legible por otras
> aplicaciones: no la pegues en un chat ni en un mail. La borramos en 30
> segundos.

Si el usuario cancela el borrado automático (o la app se cierra antes):

> **La clave quedó en el portapapeles.** Si querés, la borramos ahora.

Y, en la ayuda de la sección:

> **Nunca hace falta copiar la clave.** Sólo se copia para pegarla en otro
> equipo o en el portal de la institución. Dentro de Kitoru no se usa.

**Por qué avisar y no simplemente no ofrecer "copiar":** hay un caso real (el
alumno que pega la key de la institución en el portal del servidor, o el
docente que la pega en el Kitoru-Server). Prohibirlo no ayuda; **avisar** sí.

**Detalle de plataforma:** en Android 13+ (API 33) el sistema muestra un
toast propio cuando una app **lee** el portapapeles. La app **no debe leerlo**
nunca (sólo escribir), así que ese toast no aparece. En Windows 10+ hay un
historial del portapapeles (Win+V) que **guarda lo copiado**: por eso el
aviso es explícito.

### 5.4 Qué **no** se muestra

| Nunca | Por qué |
|---|---|
| La key en una lista de cátedras | Sólo el estado ("Activa", "Vence el 31/12"). |
| La key en el `toString()` de un modelo | §6.4. |
| La key en un `Snackbar` de error | Se muestra el código de error, no la credencial. |
| La key en un log | §6. |
| La key en un QR del bundle, salvo que el docente lo pida explícitamente | Un QR es una foto de la key. |

---

## 6. Logs, errores y reportes de crash

### 6.1 La regla

> **Un secreto no se escribe jamás en: un log, un mensaje de error, un reporte
> de crash, una entrada del `AccessLog`, una exportación, ni el `toString()` de
> un objeto de dominio.**

La regla se implementa en **dos capas**:

1. **Prevención** — el secreto no llega al logger: no se pasa como argumento,
   los `data class` sensibles sobrescriben `toString()`, y el logger de HTTP
   sanea el `Authorization`.
2. **Defensa** — si un secreto llega a un texto, `redactSecrets` lo quita
   **antes** de escribirlo.

### 6.2 `redactSecrets`

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/SecretRedactor.kt
package kitoru.platform

/**
 * Quita secretos de un texto antes de escribirlo en un log, un error, un
 * reporte de crash o una exportación.
 *
 * Trabaja en dos pasos:
 *  1. **Literales conocidos**: los secretos que la app tiene en memoria se
 *     registran con [register] y se reemplazan por una máscara. Esto cubre el
 *     caso que ningún patrón puede cubrir: una key con un formato raro.
 *  2. **Patrones**: se redactan las formas conocidas de credencial.
 *
 * Es una función **pura y determinista**: mismo texto + mismos secretos
 * registrados ⇒ mismo resultado. Testeable sin disco ni red.
 */
object SecretRedactor {

    /** Máscara con la que se reemplazan los secretos. */
    const val MASK = "«redactado»"

    private val known = ConcurrentHashMap<String, String>()   // secreto → máscara

    /** Registra un secreto vivo para que sea redactado en cualquier texto. */
    fun register(secret: String) {
        val s = secret.trim()
        if (s.length < 8) return            // demasiado corto: falsea positivo
        known.putIfAbsent(s, MASK)
    }

    /** Olvida un secreto (cuando la key se rota o se borra). */
    fun forget(secret: String) {
        known.remove(secret.trim())
    }

    /** Redacta [text]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        // 1) Literales conocidos (los más largos primero, para que un secreto
        //    que contiene a otro no se redacto a medias).
        for (secret in known.keys.sortedByDescending { it.length }) {
            if (out.contains(secret)) out = out.replace(secret, MASK)
        }
        // 2) Patrones.
        for ((regex, replace) in PATTERNS) out = regex.replace(out, replace)
        return out
    }

    /**
     * Patrones de credencial: cada uno es un par (regex, reemplazo).
     *
     * El orden importa: primero el token de Kitoru completo (que es el más
     * específico), después las cabeceras y los pares clave=valor.
     */
    private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // ktor_<kid>_<secret>  →  conserva el kid (público), tapa el secreto.
        Regex("""\bktor_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{20,}""") to { m ->
            val kid = m.value.substringAfter('_').substringBefore('_')
            "ktor_${kid.take(4)}…"
        },
        // Authorization: Bearer xxx   /   "authorization": "xxx"
        Regex("""(?i)(authorization\s*[:=]\s*)(?:bearer\s+)?["']?[A-Za-z0-9._\-]{8,}["']?""") to { m ->
            m.groupValues[1] + MASK
        },
        // api_key=…  /  "apiKey": "…"  /  token=…  /  secret=…  /  password=…
        Regex("""(?i)\b(api[_-]?key|apikey|token|secret|password|passwd|pwd)(["']?\s*[:=]\s*["']?)([^\s"',}\]]{8,})""") to { m ->
            m.groupValues[1] + m.groupValues[2] + MASK
        },
        // sk-…  (formato de OpenAI y similares)
        Regex("""\bsk-[A-Za-z0-9_\-]{16,}""") to { "sk-…" },
        // JWT
        Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}""") to { "«jwt»" },
    )
}
```

> La lista de patrones es **punto de partida, no una garantía**. La garantía
> real la da la combinación de (a) registrar el secreto vivo con `register` y
> (b) **no pasar el secreto a los loggers**. Los patrones son la red de
> seguridad para los casos en que el secreto llegó sin registrarse.

### 6.3 Dónde se aplica

| Punto de salida | Qué se redacta | Cómo |
|---|---|---|
| **Logger de Ktor** | Cabecera `Authorization`, cuerpo de la respuesta | Un `Logger` propio que sanea la cabecera y pasa todo por `redact` |
| **`LlmException.fromHttp`** | El *snippet* del cuerpo HTTP (§6.4) | `SecretRedactor.redact(snippet)` |
| **`LlmException` en general** | El mensaje completo | En el `toString()` y en el `message` |
| **URLs en errores** | `userinfo` y query string | `EndpointPolicy.safeUrl(baseUrl)` |
| **Reportes de crash** | Todo el reporte | `CrashReportSanitizer.sanitize(report)` |
| **`AccessLogEntry.detail`** | Todo el `detail` | `redact` al construir la entrada |
| **Exportación de datos** | Todo el JSON | `redact` + **excluir** el directorio del almacén |
| **Estado de la app en un bug report** | `GatewayConfig`, `ModelPolicy` | `toString()` sobrescrito (§6.4) |

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/KitoruLogging.kt
package llm.api

import io.ktor.client.plugins.logging.*
import kitoru.platform.SecretRedactor

/**
 * Logger de Ktor que **nunca** escribe la cabecera `Authorization` ni un
 * cuerpo sin redactar.
 *
 * `Logger.DEFAULT` (el que se instala por defecto) no loguea cabeceras, pero
 * `Logger.HEADERS` sí, y el cuerpo de la respuesta puede traer el error del
 * servidor con el token reflected. Por eso el logger propio:
 */
internal fun redactingLogger(): Logger = object : Logger {
    override fun log(message: String) { println(SecretRedactor.redact(message)) }

    override fun log(message: String, cause: Throwable?) {
        println(SecretRedactor.redact(message))
        cause?.let { println(SecretRedactor.redact(it.stackTraceToString())) }
    }
}
```

Y el `Logging` del cliente, si se instala:

```kotlin
client = HttpClient {
    // …
    install(Logging) {
        logger = redactingLogger()
        level = LogLevel.INFO          // nunca HEADERS ni BODY
    }
}
```

> **Decisión:** `LogLevel.INFO` como máximo, y **nunca** `LogLevel.HEADERS`
> (escribe el `Authorization`) ni `LogLevel.ALL` (escribe el prompt del
> alumno, que es dato personal — ver `security/02-privacidad.md` §8).

### 6.4 Auditoría del código actual (hallazgos concretos)

Revisión del código del repo, con lo que hay que cambiar:

| # | Hallazgo | Archivo y línea | Riesgo | Corrección |
|---|---|---|---|---|
| H1 | `GatewayConfig` es un `data class` con `apiKey: String`: su `toString()` **imprime la key**. Cualquier `log("cfg=$cfg")`, `println` de diagnóstico o reporte de crash que lo incluya la filtra. | `llm-api/.../config/GatewayConfig.kt:14-26` | 🔴 Alto | Sobrescribir `toString()` (§abajo) |
| H2 | `LlmException.fromHttp` mete `body.take(300)` en el **mensaje** del error. El cuerpo de un 401/403 de un proxy o de un servidor mal implementado puede **reflejar el token**. | `llm-api/.../error/LlmErrors.kt:63-87` | 🔴 Alto | `SecretRedactor.redact(snippet)` + truncar **después** de redactar |
| H3 | `LlmException.toString()` imprime el mensaje ⇒ arrastra lo de H2 a cualquier log. | `llm-api/.../error/LlmErrors.kt:54` | 🟠 Medio | Redactar en `toString()` |
| H4 | `transportError` incluye `baseUrl` crudo: si la URL trae `user:pass@` o `?api_key=`, la credencial va al mensaje de error. | `llm-api/.../api/LlmGateway.kt:159-176` | 🟠 Medio | `EndpointPolicy.safeUrl(baseUrl)` |
| H5 | `ModelPolicy` es un `data class` con `apiKey`: su `toString()` imprime la key. | `core/.../domain/ModelPolicy.kt:38` | 🔴 Alto | Resuelto por §3.1 (el campo desaparece) + `redacted()` |
| H6 | `OpenAiCompatibleGateway` **no** instala `Logging` y **no** imprime nada: **no filtra hoy**. ✔ | `llm-api/.../api/LlmGateway.kt` | 🟢 Bien | Mantener así; si se agrega `Logging`, usar el logger redactante (§6.3) |
| H7 | `bearerAuth(apiKey)` manda la key como cabecera `Authorization`. Es lo correcto, pero obliga a (a) HTTPS, (b) nunca loguear cabeceras, (c) no seguir redirecciones a otro host. | `LlmGateway.kt:214, 288` | 🟠 Medio | §8 |
| H8 | `GatewayConfig` es `@Serializable`: **puede** serializarse a un archivo si alguien lo guarda. Hoy nadie lo hace, pero el tipo lo permite. | `llm-api/.../config/GatewayConfig.kt:13` | 🟠 Medio | Quitar `@Serializable` de `GatewayConfig` (no se persiste) o marcarlo con un serializer que redacte |

**Corrección de H1:**

```kotlin
// llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt
@Serializable
data class GatewayConfig(
    val baseUrl: String,
    val apiKey: String = "",
    // …
) {
    /**
     * `toString()` **redactado**.
     *
     * El `toString()` autogenerado de un `data class` imprime todos los
     * campos: con `apiKey` adentro, un `log` de diagnóstico o un reporte de
     * crash filtra la credencial. Este `toString()` es la barrera.
     */
    override fun toString(): String =
        "GatewayConfig(baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "\"\"" else "«redactado»"}, " +
            "model=$model, temperature=$temperature, maxTokens=$maxTokens, " +
            "chatTimeoutMs=$chatTimeoutMs, thinking=$thinking, power=$power)"
}
```

**Corrección de H2 (el más importante, porque es un camino real):**

```kotlin
// llm-api/src/commonMain/kotlin/llm/error/LlmErrors.kt
fun fromHttp(status: Int, body: String, local: Boolean): LlmException {
    val origin = if (local) "llama-server" else "la API"
    // **Redactar antes de truncar**: si no, un token al final del cuerpo
    // sobrevive al corte de 300 caracteres.
    val snippet = kitoru.platform.SecretRedactor.redact(body).take(300).ifBlank { "(sin cuerpo)" }
    // …
}
```

> **Nota de orden:** redactar **después** de truncar deja una ventana: el
> `take(300)` corta el token por la mitad y el patrón deja de matchear. Por eso
> el orden correcto es **redactar → truncar**.

### 6.5 El reporte de crash

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/CrashReport.kt
package kitoru.platform

/**
 * Sanea un reporte de crash antes de mandarlo o de guardarlo.
 *
 * Aplica [SecretRedactor.redact] a **todo** el reporte (incluidos los valores
 * de las variables y el stack trace) y garantiza que **no** incluya el
 * directorio del almacén de secretos.
 */
object CrashReportSanitizer {
    fun sanitize(report: String): String = SecretRedactor.redact(report)
}
```

Y una regla de inventario: el reporte de crash **nunca** adjunta
`GatewayConfig`, `ModelPolicy` con credenciales, ni el contenido del
almacén. Si el estado de la app se adjunta, se adjunta con `redacted()`.

### 6.6 La auditoría: qué credenciales hay guardadas

La UI de Ajustes puede mostrar **qué referencias** hay guardadas (no los
secretos). Es útil y es seguro:

```
┌─ Credenciales guardadas ───────────────────────────────────────┐
│                                                                  │
│  Clave de la cátedra "IA-2026-1"          ktor_K01J…kA   [ 🗑 ]  │
│  Clave personal (IA-2026-1)               ktor_K7F2…rT   [ 🗑 ]  │
│  Sesión con srv-unlp                     session:…     [ 🗑 ]  │
│                                                                  │
│  Guardadas en: DPAPI de Windows (atado a tu cuenta de Windows).  │
│  [ Borrar todas las credenciales ]                               │
└──────────────────────────────────────────────────────────────────┘
```

`refs()` (§2.1) da exactamente esta lista.

---

## 7. Rotación y revocación

### 7.1 Qué significa rotar

| Operación | Qué cambia en el servidor | Qué cambia en el cliente |
|---|---|---|
| **Renovar** (`extend`) | `expiresAt` (nunca más allá del fin de la cursada). **El secreto no cambia.** | Nada: la key sigue siendo la misma. Sólo se actualiza el chip de vencimiento. |
| **Rotar** (`rotate`) | Se emite un **secreto nuevo** para la misma credencial; el viejo muere (con un *grace period* opcional). | Hay que **reemplazar** el secreto en el almacén. |
| **Revocar** (`DELETE`) | La credencial queda `REVOKED`; el `kid` entra en la denylist. | La app recibe `401 key_revoked` → **borra** el secreto local y avisa. |
| **Pausar / Reanudar** | `SUSPENDED` ↔ `ACTIVE`. | La app muestra "pausada" y no reintenta. |

### 7.2 Qué ven los alumnos

**Modo `PER_STUDENT` (el normal):**

- El docente rota **la key de un alumno**: sólo ese alumno la ve rota.
  - La app del alumno recibe `401 key_revoked` (o `key_rotated`) → muestra un
    diálogo:
    > **Tu clave fue rotada por tu docente.**
    > La anterior ya no sirve. Pedí la nueva o esperá a que tu docente te la
    > envíe.
    > [ Pedir una clave nueva ]
  - Si el servidor permite la reemisión automática (el alumno tiene token de
    sesión), el botón llama a `POST /v1/keys` y guarda el secreto nuevo
    **bajo la misma ref** (`apikey:student:<enrollmentId>`), reemplazando el
    viejo atómicamente. El alumno no tiene que hacer nada.
  - Si el docente rota **todas** las keys de la cátedra, cada alumno recibe su
    diálogo y su propia key nueva: **la rotación es masiva pero el radio de
    impacto de cada key es 1**.
- El docente renueva una key: no hay nada que hacer en el cliente (el secreto
  no cambia).

**Modo `COURSE` (sin servidor):**

- El docente rota la key de la cátedra → la nueva la pega en su app (y, si
  comparte bundle, en los alumnos).
- Los alumnos con la key vieja reciben `401` → el diálogo:
  > **La clave de la cátedra cambió.**
  > La que tenés ya no sirve. Pedile la nueva a tu docente y pegala en los
  > ajustes.
- **El alumno borra el secreto viejo de su almacén** cuando detecta el 401
  (`remove(ref)` + `apiKeyRef = null`): no tiene sentido conservar una
  credencial que el servidor ya rechazó.

### 7.3 Qué ve el docente

La tabla de claves (de `teacher/08-alumnos.md`):

```
┌─ Claves de IA-2026-1 ─────────────────────────────────────────────────┐
│ Alumno       │ Clave        │ Estado  │ Vence      │ Cuota  │ …    │
│ ─────────────┼──────────────┼─────────┼────────────┼────────┼────  │
│ Ana Pérez    │ ktor_K01J…kA │ ● Activa│ 31/12/2026 │  12 %  │ ⋮    │
│ Bruno Díaz   │ ktor_K7F2…rT │ ● Activa│ 31/12/2026 │   4 %  │ ⋮    │
│ Carla Ruiz   │ ktor_K9X0…bB │ ▲ Vencida│ 15/07/2026│ 100 %  │ ⋮    │
│ Diego Soto   │ ktor_K2M1…pQ │ ✖ Revocada│ —        │  88 %  │ ⋮    │
└──────────────────────────────────────────────────────────────────────┘
```

El menú `⋮` por fila: **Renovar** · **Rotar** · **Pausar/Reanudar** ·
**Revocar** · **Copiar `kid`**.

- **Rotar** muestra la key nueva **una sola vez** (mismo diálogo que la
  emisión), y avisa: *"La clave anterior deja de funcionar en 5 minutos"* (el
  `graceSeconds`).
- **Revocar** pide confirmación: *"Revocás la clave de Diego Soto. Va a dejar de
  poder chatear con la IA de la cátedra. Es reversible (puede reemitirse). "*
- Todo queda en la **auditoría** del servidor (`audit: key.rotated`,
  `audit: key.revoked`), con el `kid`, el actor y la fecha — **sin** el
  secreto.

### 7.4 La rotación desde la app del alumno

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/KeyRotation.kt
package kitoru.domain

/**
 * Qué hacer cuando el servidor rechaza la credencial.
 *
 * Es una función **pura**: recibe el estado y devuelve la acción. La UI y el
 * servicio la usan igual, y es testeable sin red.
 */
object KeyRotation {

    /** Qué hacer con la credencial local. */
    sealed interface Action {
        /** Todo bien: seguir. */
        data object Keep : Action

        /** Se renueva (el secreto no cambia): sólo actualizar el chip. */
        data class Renewed(val expiresAt: Long) : Action

        /** Se rotó: hay que borrar el secreto viejo y pedir uno nuevo. */
        data class Rotated(val reason: String) : Action

        /** Se revocó: borrar el secreto y avisar al usuario. */
        data class Revoked(val reason: String) : Action

        /** Se pausó: no reintentar hasta que el usuario lo pida. */
        data class Paused(val reason: String) : Action
    }

    /**
     * Decide la acción a partir del error del servidor.
     *
     * El servidor responde con `{"error": {"code": "key_revoked", …}}`
     * (`platform/03-kitoru-server-api.md` §5.4). Si el cuerpo no se puede
     * parsear, se asume lo peor: **revocada**.
     */
    fun decide(errorCode: String?, message: String): Action = when (errorCode) {
        "key_revoked" -> Action.Revoked(message)
        "key_rotated" -> Action.Rotated(message)
        "key_suspended" -> Action.Paused(message)
        "key_expired" -> Action.Renewed(0L)
        "key_extended" -> Action.Renewed(0L)
        else -> Action.Revoked("La clave dejó de ser válida. Pedile una nueva a tu docente.")
    }
}
```

Y el servicio que la aplica:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/CredentialService.kt
class CredentialService(
    private val store: SecureStore,
    private val resolver: CredentialResolver,
) {
    /**
     * Reacciona a un rechazo del servidor: borra el secreto muerto, avisa y
     * deja la política sin credencial (para que la UI muestre el pedido de
     * una key nueva).
     */
    suspend fun handleRejection(policy: ModelPolicy, action: KeyRotation.Action): ModelPolicy {
        val ref = policy.apiKeyRef ?: return policy
        when (action) {
            is KeyRotation.Action.Revoked, is KeyRotation.Action.Rotated -> {
                store.remove(ref)          // el secreto muerto no se queda
                return policy.copy(apiKeyRef = null)
            }
            else -> return policy
        }
    }
}
```

> **Detalle importante:** al borrar el secreto, la app **no** puede borrar el
> `kid` del servidor (eso es del servidor). La app pide una key nueva y la
> guarda bajo **la misma ref**, de modo que el `apiKeyRef` del modelo no
> cambia y no hay que tocar los JSON.

---

## 8. La credencial en la red

### 8.1 ¿Se puede evitar que un proxy capture la key?

**No, no en el caso general.** Es una limitación del modelo de TLS, no de
Kitoru. Lo que se puede y debe hacer:

| Escenario | ¿Ve la key? | Mitigación |
|---|---|---|
| **Tráfico en claro** (`http://`) | **Sí**, cualquiera en la ruta | **Prohibido**: HTTPS obligatorio (§8.2) |
| **Proxy que intercepta TLS** (con una CA instalada en la máquina: corporativo, malware con privilegios) | **Sí**: el proxy termina el TLS y ve la cabecera | Radio de impacto mínimo (key por alumno), expiración corta, rotación. Es el modelo de amenaza aceptado. |
| **Proxy que sólo reenvía** (sin terminar el TLS: `CONNECT` a un puerto 443) | **No** | — |
| **Un intermediario que loguea el tráfico en el servidor** (el propio Kitoru-Server) | **Sí**, si loguea cabeceras | **Contrato**: el servidor loguea el `kid`, nunca el secreto (§8.3) |
| **El propio dispositivo (malware con permisos del usuario)** | **Sí** | Irremediable: la key está en memoria para poder mandarla |

Lo que **sí** se puede hacer, y se hace:

1. **HTTPS obligatorio** (salvo loopback).
2. **Sin `trustAll`**: el cliente usa la cadena de confianza del SO. Un
   certificado autofirmado **no** se acepta salvo que el docente confirme la
   huella (§8.4).
3. **Sin redirecciones a otro host**: un `302` a `https://otro-servidor/v1`
   con la misma ruta NO debe llevar el `Authorization`.
4. **La key no viaja en la URL**: nunca `?api_key=…` (queda en los logs de
   acceso del servidor y en el `Referer`).
5. **El `kid` sí puede viajar** (es público).

### 8.2 La configuración de red

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/EndpointPolicy.kt
package kitoru.domain

/**
 * Política de red para las URLs del servidor de la IA.
 *
 * Regla: **HTTPS siempre**, con una única excepción: `http://` a loopback
 * (el `llama-server` local y el Kitoru-Server corriendo en la misma
 * máquina), que no sale de la máquina.
 */
object EndpointPolicy {

    /** Hosts de loopback: el tráfico no sale del equipo. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")

    /** ¿La URL es de loopback? */
    fun isLoopback(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host in LOOPBACK_HOSTS) return true
        // Todo 127.0.0.0/8 es loopback.
        return host.startsWith("127.") && host.substringAfter('.').all { it.isDigit() }
    }

    /** ¿Se puede mandar una credencial a esta URL? */
    fun allowsCredentials(url: String): Boolean {
        val scheme = schemeOf(url)?.lowercase() ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> isLoopback(url)
            else -> false
        }
    }

    /** Problemas de la URL, para `ModelPolicy.validate()`. En español. */
    fun validate(url: String): List<String> {
        val errors = mutableListOf<String>()
        val scheme = schemeOf(url)?.lowercase()
        if (scheme == null) {
            errors += "La dirección del servidor no es válida."
            return errors
        }
        if (scheme == "http" && !isLoopback(url)) {
            errors += "La dirección del servidor no es segura: usá https://. " +
                "Sólo se permite http:// en localhost (por ejemplo, un Kitoru-Server " +
                "que corre en tu propia máquina)."
        }
        if (scheme !in setOf("http", "https")) {
            errors += "La dirección del servidor debe ser http:// o https://."
        }
        return errors
    }

    /**
     * Versión de la URL **segura de mostrar y de loguear**.
     *
     * Le saca el `userinfo` (`https://user:pass@host`) y el query string
     * (`?api_key=…`): los dos pueden llevar credenciales.
     */
    fun safeUrl(url: String): String {
        val u = url.trim()
        val schemeIdx = u.indexOf("://")
        if (schemeIdx < 0) return u
        val afterScheme = u.substring(schemeIdx + 3)
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        val rest = if (slash < 0) "" else afterScheme.substring(slash)
        val host = authority.substringAfterLast('@')      // sin userinfo
        val clean = u.substring(0, schemeIdx + 3) + host + rest.substringBefore('?')
        return clean
    }

    private fun schemeOf(url: String): String? =
        url.trim().substringBefore("://").takeIf { it.isNotEmpty() && it.none { c -> c == '/' || c == ':' } }

    private fun hostOf(url: String): String? {
        val s = url.trim().substringAfter("://", "")
        if (s.isEmpty()) return null
        val authority = s.substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.lowercase().removePrefix("[").removeSuffix("]")
    }
}
```

Y `ModelPolicy.validate()` agrega la comprobación:

```kotlin
if (mode == ModelMode.REMOTE) {
    if (remoteBaseUrl.isBlank()) {
        errors += "Falta la dirección del servidor de la cátedra."
    } else {
        errors += EndpointPolicy.validate(remoteBaseUrl)
        if (modelName.isBlank()) {
            errors += "Falta el nombre del modelo en el servidor."
        }
    }
}
```

> **Ojo con el orden de las validaciones:** hoy el `else if` hace que, si la
> URL es inválida, **no** se reporte el nombre del modelo. Con
> `EndpointPolicy.validate()` se separa: la URL y el modelo se validan
> **independientemente**, así el usuario ve los dos problemas de una vez.

### 8.3 Defensa en profundidad en el gateway

La validación de la UI no alcanza: el gateway es el que **manda** la key.

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt
class OpenAiCompatibleGateway(
    private val baseUrl: String,
    private val apiKey: String,
    // …
) : LlmGateway {

    init {
        // Si hay credencial y la URL no es segura, **no** se manda nada.
        // Esto es la última línea de defensa: aunque una URL mal formada
        // se colara por la UI, la key no viaja en claro.
        if (apiKey.isNotBlank() && !EndpointPolicy.allowsCredentials(baseUrl)) {
            throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "La dirección del servidor no usa https://, así que la clave no se puede " +
                    "mandar de forma segura. Corregí la dirección en los ajustes.",
                retryable = false,
            )
        }
    }
}
```

Y el cliente HTTP, endurecido:

```kotlin
internal fun defaultKitoruHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = 15_000
    }
    install(HttpRedirect) {
        maxRedirects = 3
        // **Sólo** redirecciones al mismo host: si el servidor responde
        // `302 https://otro-host/v1/...`, el `Authorization` no debe viajar
        // al otro host.
        shouldRedirectRequest = { original, target ->
            original.url.host.equals(target.url.host, ignoreCase = true) &&
                original.url.protocol == target.url.protocol
        }
    }
    expectSuccess = false
    // **Nunca** un `trustAll` ni un `TrustManager` propio: la cadena de
    // confianza es la del sistema operativo.
}
```

> **Ktor y las redirecciones:** el comportamiento por defecto de Ktor
> (3.x) es **conservar las cabeceras** al redirigir, incluso a otro host.
> Por eso la restricción es explícita. Si en una versión de Ktor el
> comportamiento cambia, la regla queda documentada y testeada (§11).

**Contrato con el servidor (logging):** el Kitoru-Server **loguea el `kid`,
nunca el secreto**. Ya está pedido en `platform/03-kitoru-server-api.md` §2.1
("el `kid` NO es un secreto… filtrar el `secret` sí"). Se le agrega el
requisito explícito: **ningún log del servidor contiene el valor de la
cabecera `Authorization`**, ni siquiera a nivel `DEBUG`.

### 8.4 Pinning opcional (v1.1)

El Kitoru-Server institucional puede tener un certificado autofirmado. Sin
pinning, la app no puede distinguirlo del de un atacante. Con pinning por
**huella** (no por CA):

- El docente ve la huella SHA-256 del certificado cuando la primera conexión
  es por `http://` local o por un certificado no confiable, y la confirma.
- La huella se guarda en `courses.json` (no es un secreto).
- El cliente la verifica en cada conexión.

Es una decisión de producto (hay que hablar con el docente) y por eso queda
para la v1.1, **anotada**. Lo que sí es de la v1 es el "no confíes en todo":
jamás un `trustAll`.

---

## 9. Threat model mini

| # | Amenaza | Vector | Impacto | Mitigación | Residuo honesto |
|---|---|---|---|---|---|
| T1 | **Robo del dispositivo** | Se clona el disco o se extraen los archivos | La key de la cátedra sirve desde otro equipo | La key **no está** en los JSON: está en un almacén atado a la cuenta del SO (DPAPI / llavero / Keystore). Copiar el disco no alcanza (el Keystore/DPAPI no viajan) | Con el fallback de archivo, un atacante con el disco **del usuario** sí la obtiene. Mitigado parcialmente por D6 (el almacén está fuera de `~/.kitoru`) |
| T2 | **El archivo de configuración se copia** | Un backup, un ticket, un `config.json` de ejemplo en un repo | La key viaja | La key no está en `config/courses.json`; el bundle exportado va con `redacted()`; la migración borra los residuos | Si el usuario exportó un bundle *con* credencial antes de este cambio, ese archivo sí tiene la key: hay que avisarlo |
| T3 | **Sincronización en la nube** | OneDrive/Drive/Dropbox suben `~/.kitoru` | La key de la institución queda en la nube | El almacén del SO está **fuera** de la carpeta sincronizada (D6) y su contenido está atado a la cuenta (copiarlo no sirve) | Si el usuario sincroniza **toda** la home, el blob del fallback de archivo también viaja (con su clave al lado) |
| T4 | **Key filtrada por un alumno** | La pegó en un chat / se la pasó a un amigo | Uso de la cuota ajena | **Key por alumno** (§4): se rota **una**, el radio de impacto es 1; el servidor la revoca y la app borra el secreto local | La key filtrada sigue viva hasta que se rota: hay una ventana de uso indebido |
| T5 | **App rooteada / con inyección en el proceso** | Atacar el proceso de la app | Lectura de la key en memoria | La key **no se persiste en claro**; el Keystore/DPAPI protegen el archivo; en Android, `FLAG_SECURE` en la pantalla de la clave | **Irremediable**: la key está en memoria para poder mandarla. Un atacante con control del proceso la lee |
| T6 | **Alguien mira el portapapeles** | Otra app lee el portapapeles | Key copiada | Auto-borrado a los 30 s + aviso explícito + la recomendación de no copiar | Hay una ventana de 30 s; en Android 10+ el acceso al portapapeles de otra app está restringido salvo que esté en foco |
| T7 | **Proxy que intercepta TLS** | CA instalada en la máquina (corporativa, malware con privilegios) | Ve la `Authorization` | HTTPS obligatorio; sin `trustAll`; sin redirecciones fuera del host | **Aceptado**: mitigado con key por alumno (radio 1), expiración corta y rotación |
| T8 | **Log del servidor con la key** | El Kitoru-Server loguea cabeceras | La key queda en un log del servidor | Contrato: el servidor loguea el `kid`, nunca el secreto; la cabecera `Authorization` está en la lista de cabeceras que **no** se loguean | — |
| T9 | **Otro alumno usa la key de otro** | Copia del token | Contaminación de la analítica | El servidor valida `X-Kitoru-Student-Id` contra el `subject` de la key ⇒ `403 student_mismatch` | En el modo `COURSE` (sin servidor) no hay validación: es el precio del modo sin servidor |
| T10 | **Regresión: alguien vuelve a poner `apiKey` en el modelo** | Code review descuidado | La key vuelve al JSON | Un **test** (§11) falla si `kitoru.domain` vuelve a declarar un campo de secreto; `SecretMigration` corre en cada arranque y limpia lo que aparezca | Un test no es una ley, pero convierte la regresión en un build rojo |
| T11 | **El usuario pega la key en un chat de la app** | Descuido | La key va a un servicio externo | La app **no puede** evitarlo (es texto libre) | El aviso de la UI y la regla de no pegar credenciales en el chat. Es un problema de educación, no de código |
| T12 | **Exportación / reporte de crash** | El bundle de diagnóstico sale del equipo | La key sale con él | `redactSecrets` en toda exportación; el directorio del almacén **se excluye**; `toString()` redactado | — |
| T13 | **Docente que comparte la key de la cátedra** | Canal del docente (mail, chat) | Los alumnos ven la key común | En modo `PER_STUDENT` no hay key común; en modo `COURSE` la app avisa al exportar el bundle y al copiar | Es el límite del modo sin servidor: la seguridad la aporta el canal del docente |

---

## 10. Plan de implementación

Se propone en **cuatro fases**, cada una dejando la app en un estado
consistente y ejecutable.

### Fase 1 — Sellar las fugas (sin cambiar el modelo)

| # | Cambio | Archivo |
|---|---|---|
| 1.1 | `toString()` redactado en `GatewayConfig` | `llm-api/.../config/GatewayConfig.kt` |
| 1.2 | `SecretRedactor.redact` en `LlmException.fromHttp` (redactar **antes** de truncar) | `llm-api/.../error/LlmErrors.kt` |
| 1.3 | `EndpointPolicy.safeUrl` en `transportError` | `llm-api/.../api/LlmGateway.kt` |
| 1.4 | `SecretRedactor` + `SecretMask` nuevos | `core/.../platform/` |
| 1.5 | `writeTextAtomic` en `FileSystem` + `JsonStore` | `core/.../platform/FileSystem.kt`, `domain/JsonStore.kt` |

> La Fase 1 **no cambia el modelo de datos**: se puede hacer en un PR
> aparte, y ya elimina las fugas de los logs, de los errores y de los crash
> reports.

### Fase 2 — El almacén seguro

| # | Cambio | Archivo |
|---|---|---|
| 2.1 | `SecureStore`, `SecureStoreKind`, `SecretRef` | `core/.../platform/SecureStore.kt`, `SecretRef.kt` |
| 2.2 | `AesGcm` (motor común) | `core/src/jvmCommonMain/.../AesGcm.kt` |
| 2.3 | `AndroidPlatform.init` + `SecureStore.android.kt` (Keystore) | `core/src/androidMain/.../SecureStore.android.kt` |
| 2.4 | `DpapiProtector` + JNA | `core/src/desktopMain/.../DpapiProtector.kt` + `libs.versions.toml` |
| 2.5 | `MacosKeychainProtector`, `SecretToolProtector`, `FileAesGcmProtector` | `core/src/desktopMain/.../` |
| 2.6 | `SecureStore.desktop.kt` (selector + degradación visible) | `core/src/desktopMain/.../SecureStore.desktop.kt` |
| 2.7 | `ClipboardGuard` + `Clipboard.android.kt` / `.desktop.kt` | `core/.../platform/Clipboard.kt` |
| 2.8 | `neverPersistSecrets` en `AppSettings` | `core/.../domain/AppSettings.kt` |

### Fase 3 — Sacar la key del modelo

| # | Cambio | Archivo |
|---|---|---|
| 3.1 | `ModelPolicy.apiKey` → `apiKeyRef`; `resolveFor` actualizado; `redacted()` | `core/.../domain/ModelPolicy.kt` |
| 3.2 | `ApiKeyGrant.key` → `keyRef` | `core/.../domain/ApiKeyGrant.kt` |
| 3.3 | `SecretMigration` (recursivo, idempotente) | `core/.../domain/SecretMigration.kt` |
| 3.4 | `CredentialResolver` + `CredentialService` | `core/.../domain/CredentialResolver.kt` |
| 3.5 | `GatewayFactory` (inyecta la key en el momento) | `llm-api/.../api/GatewayFactory.kt` |
| 3.6 | `EndpointPolicy` + validación en `ModelPolicy.validate()` + `init` del gateway | `core/.../domain/EndpointPolicy.kt`, `llm-api/.../api/LlmGateway.kt` |
| 3.7 | Llamada a la migración al arrancar la app | `:desktop` / `:android` (entry points) |
| 3.8 | `KeyRotation` (decidir la acción a partir del error) | `core/.../domain/KeyRotation.kt` |

### Fase 4 — Key por alumno y UI

| # | Cambio | Archivo |
|---|---|---|
| 4.1 | `ApiKeyMode.PER_STUDENT` como default con servidor | `core/.../domain/ApiKeyGrant.kt` |
| 4.2 | Emisión de la key propia en la inscripción (cliente del servidor) | `llm-api/.../kitoru/KitoruServerClient.kt` |
| 4.3 | `SecretMask` en la UI de la clave + copiar con auto-borrado | `:ui` |
| 4.4 | Pantalla "Credenciales guardadas" | `:ui` |
| 4.5 | Chips de estado de la key en el chat del alumno y en la cátedra del docente | `:ui` |
| 4.6 | `X-Kitoru-Student-Id` en las peticiones | `llm-api/.../api/LlmGateway.kt` |

---

## 11. Plan de tests

| Test | Qué cubre |
|---|---|
| `SecretRedactorTest` | Los patrones known: `ktor_<kid>_<secret>` → `ktor_K01J…`; `Authorization: Bearer x` → redactado; `api_key=`, `"apiKey":`, `sk-…`, JWT; los literales registrados; que un texto sin secretos **no cambie** (idempotencia). |
| `SecretMaskTest` | `ktor_K01J9Z8Y2QK4M_x7Fq…` → `ktor_K01J…kA`; una key corta → bullet; vacía → `""`; que **nunca** devuelva el secreto completo. |
| `SecretMigrationTest` | `courses.json` con `apiKey` → se migra, el archivo se reescribe **sin** la key y el `apiKeyRef` queda; **idempotencia** (la segunda pasada no hace nada); `threads.json` → la key se **borra** y no se migra; archivo corrupto → no se toca; almacén `NONE` → no se borra nada y se avisa; `apiKey` anidado en `effectiveModel` → se detecta (recursión). |
| `NoPlaintextSecretsTest` | **Guardia de regresión**: recorre las clases `@Serializable` de `kitoru.domain` y **falla** si alguna declara un campo cuyo nombre case con `apiKey|secret|password|token` y que no sea una `*Ref`. |
| `JsonStoreAtomicWriteTest` | Una escritura interrumpida deja el archivo viejo intacto. |
| `EndpointPolicyTest` | `https://` OK; `http://localhost` OK; `http://127.0.0.1` OK; `http://10.0.0.1` **rechazada**; `http://` a un host con `userinfo` → `safeUrl` lo saca; `?api_key=` → `safeUrl` lo saca; `ftp://` rechazada. |
| `ModelPolicyTest` | `resolveFor` con `API_KEY` bloqueado y cátedra **sin** key (key por alumno) → se respeta la del alumno; con cátedra **con** key → se impone la de la cátedra; `redacted()` limpia `apiKeyRef`. |
| `CredentialResolverTest` | `null` → `Missing`; ref inexistente → `Missing`; almacén con `lastError` → `Unreadable`; **nunca** lanza. |
| `KeyRotationTest` | `key_revoked` → `Revoked`; `key_rotated` → `Rotated`; `key_suspended` → `Paused`; error desconocido → `Revoked` (fail-closed). |
| `LlmExceptionRedactionTest` | Un cuerpo HTTP con `ktor_K…_…` **al final** (después del corte de 300 caracteres) queda redactado — **verifica el orden redactar→truncar**. |
| `GatewayConfigToStringTest` | `toString()` **no** contiene la key. |
| `SecureStoreDesktopTest` (desktopTest) | El fallback de archivo: guardar → leer → devolver lo mismo; permisos 0600 en POSIX; un archivo corrupto devuelve `null` y no revienta. |
| `SecureStoreRoundTripTest` | Guardar y leer en la plataforma real; si el SO no tiene almacén, el test **se salta** con un mensaje claro (no falla por el entorno). |

---

## 12. Archivos

```
core/src/commonMain/kotlin/kitoru/platform/
├── SecureStore.kt            # SecureStore, SecureStoreKind, expect fun secureStore()
├── SecretRef.kt              # SecretRef (apikey:course:…, apikey:student:…, session:…)
├── SecretRedactor.kt         # redactSecrets / SecretRedactor
├── SecretMask.kt             # (en domain: enmascarado para la UI)
├── Clipboard.kt              # ClipboardGuard + expect fun clipboardGuard()
├── EndpointPolicy.kt         # (en domain: política de URLs seguras)
└── CrashReport.kt            # CrashReportSanitizer

core/src/jvmCommonMain/kotlin/kitoru/platform/
└── AesGcm.kt                 # motor de cifrado compartido

core/src/androidMain/kotlin/kitoru/platform/
├── SecureStore.android.kt    # AndroidPlatform.init + SecureStore (AndroidKeyStore)
└── Clipboard.android.kt

core/src/desktopMain/kotlin/kitoru/platform/
├── SecureStore.desktop.kt    # selector de protector + degradación visible
├── DpapiProtector.kt         # Windows (JNA)
├── MacosKeychainProtector.kt # macOS (security)
├── SecretToolProtector.kt    # Linux (secret-tool)
├── FileAesGcmProtector.kt    # fallback
└── Clipboard.desktop.kt

core/src/commonMain/kotlin/kitoru/domain/
├── ModelPolicy.kt            # apiKey → apiKeyRef; resolveFor; redacted()
├── ApiKeyGrant.kt            # key → keyRef; ApiKeyStatus
├── SecretMigration.kt        # migración del JSON crudo
├── CredentialResolver.kt     # resuelve la credencial en runtime
├── CredentialService.kt      # aplica KeyRotation sobre el almacén
├── KeyRotation.kt            # decisión pura a partir del error del servidor
└── EndpointPolicy.kt

llm-api/src/commonMain/kotlin/llm/
├── config/GatewayConfig.kt   # toString() redactado
├── error/LlmErrors.kt        # redactar el snippet del cuerpo HTTP
└── api/LlmGateway.kt         # init que valida la URL; no seguir redirects a otro host
```

---

## 13. Casos borde

| Caso | Comportamiento |
|---|---|
| La app arranca con `courses.json` viejo (con `apiKey`) | La migración corre primero: la key va al almacén y el archivo se reescribe sin ella. La UI muestra un aviso: *"Guardamos tu clave en el almacén seguro y la quitamos del archivo de configuración."* |
| El usuario tiene `courses.json` viejo **y** el almacén no está disponible | No se borra nada; aviso: *"Hay una clave en texto plano en el archivo y este equipo no tiene un almacén seguro."* + botón "Borrar la clave del archivo" (que descarta el secreto). |
| La key guardada no se puede descifrar (blob corrupto, clave del Keystore invalidada) | `get` devuelve `null` + `lastError`. La UI: *"No encontramos la clave guardada. Volvé a pegarla."* El `apiKeyRef` **no** se borra (así el usuario puede recuperar la key si repara el almacén). |
| El usuario borra la key desde Ajustes | `remove(ref)` + `apiKeyRef = null` en la política + se guarda la política. |
| Dos cursadas con keys de cursos distintos | Cada una con su ref (`apikey:course:<id>`); el almacén las guarda todas; la UI las lista. |
| El alumno se inscribe en dos cátedras | Dos refs (`apikey:student:<enroll1>`, `apikey:student:<enroll2>`). |
| El docente rota la key de la cátedra en el servidor | El cliente del docente hace `POST /v1/keys/{id}/rotate`, guarda el secreto nuevo **bajo la misma ref** y el viejo muere. Los alumnos con el viejo reciben 401 → diálogo (§7.2). |
| El servidor responde `401` por una key **expirada** (no revocada) | `KeyRotation.Renewed` → el botón "Renovar" (`POST /v1/keys/{id}/extend`); el secreto **no** cambia. |
| `http://192.168.0.10:8080/v1` (servidor de la cátedra en la red del laboratorio, sin TLS) | `validate()` lo rechaza con el mensaje en español. **No** hay cómo pedirle al usuario que acepte el riesgo: la credencial travelaría en claro por la red. (Excepción documentada: `localhost`.) |
| `http://localhost:8081/v1` (Kitoru-Server local) | Permitido, con el aviso "Conexión sin cifrar: sólo a tu propia máquina." |
| La app se copia a otra máquina (con `~/.kitoru`) | La config llega, **la key no**: la ref apunta a un almacén que no existe → la UI pide pegarla de nuevo. |
| El usuario exporta un bundle con credencial | Aviso explícito; el bundle sale con la key en claro **sólo** si el usuario lo marca. Al importarlo, la app la extrae al almacén y reescribe el paquete sin ella. |
| Un alumno usa la key de otro (modo `COURSE`, sin servidor) | La app no puede detectarlo (no hay servidor). En modo `PER_STUDENT` el servidor responde `403 student_mismatch`. |

---

## 14. Deuda y decisiones abiertas

| # | Tema | Estado | Nota |
|---|---|---|---|
| D-1 | Emisión de la key propia del alumno en la inscripción (`POST /v1/enrollments` → `{enrollment, apiKey}`) | **Abierto con el doc del servidor** | Hay que agregarlo a `platform/03-kitoru-server-api.md` (§5.9) o como endpoint aparte. |
| D-2 | La macro de `ps` en macOS (el secreto como argumento de `security`) | **Mitigada** | Por eso el round-trip de verificación y la degradación visible al fallback. |
| D-3 | Pinning del certificado del servidor | **v1.1** | §8.4. |
| D-4 | `AppSettings.neverPersistSecrets` (el modo "sólo memoria" como preferencia del usuario) | **Propuesta** | §2.6. |
| D-5 | Reconciliación de nombres con `domain/09-identidad.md` §8.2, que propone `CredentialStore` con `put/get/delete/has` | **A cerrar** | Este documento propone `SecureStore` con `put/get/remove/has/refs`. Son el mismo concepto: conviene que `09-identidad.md` use `SecureStore` y que `session:<serverId>` sea una de sus referencias. |
| D-6 | `teacher/05-config-modelo.md` §1.5 propone `ApiKeyGrant.key` en claro y avisa que es deuda | **Resuelto por este documento** | `key` → `keyRef`; la nota de "deuda conocida" se puede borrar. |
| D-7 | `teacher/05-config-modelo.md` §3.3 usa `/api/keys`; `platform/03-kitoru-server-api.md` §D2 usa `/v1/keys` | **Resuelto por el doc del servidor** | TodoCredential va por `/v1`. |
| D-8 | `domain/04-conversaciones.md` §8.4 propone `redacted()`; este documento lo actualiza | **A aplicar** | `apiKeyRef = null` en vez de `apiKey = ""`. |
| D-9 | ¿El `kid` se guarda en el modelo? | **No, por ahora** | `platform/03-kitoru-server-api.md` §6.4 dice que la app lo deriva parseando el token. Con `apiKeyRef` el `kid` no está en el JSON: hay que derivarlo del secreto al usarlo, o pedirlo a `GET /v1/keys/me`. **A decidir.** |

---

## 15. Lo que este documento NO hace (y por qué)

- **No diseña la autorización del servidor** (scopes, `kid`, expiraciones,
  denylist): vive en `platform/03-kitoru-server-api.md` y está bien hecho
  ahí. Este documento sólo dice cómo el **cliente** guarda y usa el secreto.
- **No diseña el consentimiento ni la privacidad**: es
  `security/02-privacidad.md`.
- **No implementa criptografía propia**: el almacén nativo del SO es el que
  protege; `AesGcm` es sólo el fallback y el motor común.
- **No resuelve el malware con privilegios del usuario**: es irreducible; la
  app sólo se asegura de no ponérselo fácil.
- **No protege el secreto que el usuario pega en un chat**: es texto libre que
  va a un servicio externo. Se avisa, no se bloquea.
