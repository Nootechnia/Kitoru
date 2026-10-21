package kitoru.platform

import kotlinx.coroutines.withContext

/**
 * Almacén seguro de secretos del dispositivo.
 *
 * Es la **única** puerta por la que una credencial entra o sale de la app.
 * El dominio (y los archivos JSON de configuración) guardan **referencias**
 * (`apikey:course:IA-2026-1`), nunca el secreto.
 *
 * Por qué existe: un secreto escrito en un JSON de configuración es un
 * secreto copiable, sincronizable con la nube y respaldable. La separación
 * (referencia en el JSON, secreto en el almacén) es lo que corta la mayoría
 * de los vectores de fuga.
 *
 * Contrato:
 *  - [put] **sobrescribe**: si ya había un secreto con ese alias, se reemplaza
 *    entero (nunca se mezcla un valor nuevo con uno viejo).
 *  - [get] devuelve `null` si no está. **Nunca** lanza: un almacén caído no
 *    puede voltear la app; el motivo queda en [lastError] para la UI.
 *  - [aliases] devuelve las referencias conocidas (**nunca** los secretos),
 *    para poder auditar "qué credenciales tiene guardadas esta instalación".
 *
 * Contrato de seguridad (la "regla de oro"):
 * > Si no hay almacén seguro disponible, el secreto **no** se escribe en
 * > texto plano: vive sólo en memoria y la UI lo dice.
 */
interface SecureStore {

    /** Qué tecnología está protegiendo este almacén. */
    val kind: SecureStoreKind

    /**
     * ¿El secreto está protegido por el almacén seguro del sistema operativo?
     *
     * `true` = Keystore de Android, DPAPI de Windows, llavero de macOS o
     * Secret Service de Linux. `false` = protección reducida (archivo cifrado
     * con clave local) o ninguno (sólo memoria).
     *
     * La UI lo usa para ser honesta con el usuario: no alcanza con "se guardó
     * la clave", hay que decir *cómo* se guardó.
     */
    val isSecure: Boolean get() = !kind.debil

    /**
     * ¿La protección es **reducida**?
     *
     * `true` para el archivo cifrado con clave local y para el almacén en
     * memoria. La UI muestra un aviso en estos casos.
     */
    val debil: Boolean get() = kind.debil

    /** Último error del almacén, para que la UI pueda avisar. Vacío si no hubo. */
    var lastError: String?

    /** Guarda (o reemplaza) el secreto de [alias]. */
    fun put(alias: String, secret: String)

    /** Lee el secreto de [alias]; `null` si no está o si el almacén falló. */
    fun get(alias: String): String?

    /** Borra el secreto de [alias]. */
    fun remove(alias: String)

    /** ¿Hay un secreto guardado bajo [alias]? */
    fun has(alias: String): Boolean

    /** Referencias guardadas (sin los valores). */
    fun aliases(): List<String>
}

/**
 * Qué tecnología protege el almacén en cada plataforma.
 *
 * Los tres niveles existen para que la UI pueda **ser honesta** con el usuario
 * sobre qué lo protege y qué no. Un usuario que cree que su clave está en el
 * Keystore y en realidad está en un archivo con permisos 0600 está engañado.
 */
enum class SecureStoreKind(
    /** Etiqueta corta para la UI. */
    val label: String,
    /** Si la protección es reducida (o no hay protección). */
    val debil: Boolean,
    /** Descripción honesta de qué protege. */
    val description: String,
) {
    /** Android: clave AES en el `AndroidKeyStore`, blob en el sandbox de la app. */
    ANDROID_KEYSTORE(
        label = "Keystore de Android",
        debil = false,
        description = "La clave de cifrado vive en el Keystore del sistema. Copiar los " +
            "datos de la app a otro dispositivo no alcanza.",
    ),

    /** Windows: `CryptProtectData` (DPAPI), atado a la cuenta de Windows. */
    DPAPI(
        label = "DPAPI de Windows",
        debil = false,
        description = "Windows descifra el dato sólo para tu cuenta de usuario en esta " +
            "máquina. Copiar el archivo a otro equipo no sirve.",
    ),

    /** macOS: llavero del sistema, vía la herramienta `security`. */
    MACOS_KEYCHAIN(
        label = "Llavero de macOS",
        debil = false,
        description = "El secreto vive en el llavero, protegido por tu contraseña de " +
            "sesión.",
    ),

    /** Linux: Secret Service (GNOME Keyring / KWallet), vía `secret-tool`. */
    LIBSECRET(
        label = "Secret Service de Linux",
        debil = false,
        description = "El secreto vive en el gestor de secretos del escritorio.",
    ),

    /**
     * Último recurso: archivo cifrado con AES-GCM y clave derivada del usuario.
     *
     * Protege contra otros usuarios de la máquina y contra que se copie sólo
     * la carpeta de configuración. **No** protege contra malware que corra como
     * el usuario, ni contra que se copie todo el directorio del almacén (la
     * clave vive al lado de los datos).
     */
    FILE_FALLBACK(
        label = "Archivo cifrado (protección reducida)",
        debil = true,
        description = "El secreto está cifrado con AES-GCM, pero la clave de cifrado " +
            "está en un archivo junto a él. Protege contra otros usuarios de la " +
            "máquina; no contra malware que corra como vos.",
    ),

    /** No hay almacén: el secreto sólo vive en memoria, esta sesión. */
    MEMORY(
        label = "Sólo en memoria (esta sesión)",
        debil = true,
        description = "No se guarda nada en disco: la clave hay que pegarla de nuevo " +
            "cada vez que se abre la app.",
    ),
}

/**
 * El almacén de secretos de esta plataforma.
 *
 * Se resuelve una vez y se cachea: todas las implementaciones son singletons
 * (el Keystore, el DPAPI y el llavero lo son de por sí).
 *
 * En escritorio intenta el almacén nativo del sistema operativo; en Android,
 * el `AndroidKeyStore`. Si no hay ninguno disponible, el secreto **no** se
 * escribe en claro: queda en memoria (ver [InMemorySecureStore]).
 */
expect fun secureStore(): SecureStore

/**
 * Nombres de las referencias del almacén de secretos.
 *
 * Formato: `esquema:ámbito:id`. El prefijo permite auditar ("¿qué
 * credenciales tengo?") y evita colisiones entre tipos.
 *
 * Las referencias son **deterministas** (así la migración es idempotente) y
 * **no son secretas**: pueden aparecer en un log, en la auditoría o en la UI.
 */
object SecretRef {

    /** Prefijo de las referencias a API keys. */
    const val SCHEME_APIKEY = "apikey"

    /** Prefijo de las referencias a tokens de sesión o de servicio. */
    const val SCHEME_SESSION = "session"

    /** Key de la cátedra (modo "una key para todos"). */
    fun forCourse(courseId: String): String = "$SCHEME_APIKEY:course:$courseId"

    /** Key personal del alumno (modo "una key por alumno"). */
    fun forStudent(enrollmentId: String): String = "$SCHEME_APIKEY:student:$enrollmentId"

    /** Token de sesión o de servicio con un servidor institucional. */
    fun forSession(serverId: String): String = "$SCHEME_SESSION:$serverId"

    /** Sólo el `id` de la referencia, para mostrarla. */
    fun shortLabel(ref: String): String = ref.substringAfterLast(':')

    /** ¿La referencia es de una API key? */
    fun isApiKey(ref: String): Boolean = ref.startsWith("$SCHEME_APIKEY:")
}

/**
 * Almacén de respaldo: los secretos viven **sólo en memoria**.
 *
 * Es el último eslabón de la "regla de oro": si la plataforma no ofrece un
 * almacén seguro (o si el usuario elige no persistir), el secreto **no** se
 * escribe en ningún archivo. Vive hasta que se cierra la app.
 *
 * Por eso [get] devuelve `null` después de reiniciar: no hay nada en disco
 * que recuperar. La UI tiene que avisar ("la clave no se va a guardar").
 */
class InMemorySecureStore : SecureStore {

    /** Los secretos, en memoria. */
    private val secrets = LinkedHashMap<String, String>()

    override val kind: SecureStoreKind = SecureStoreKind.MEMORY

    override var lastError: String? = null

    override fun put(alias: String, secret: String) {
        secrets[alias] = secret
        lastError = null
    }

    override fun get(alias: String): String? = secrets[alias]

    override fun remove(alias: String) {
        secrets.remove(alias)
    }

    override fun has(alias: String): Boolean = secrets.containsKey(alias)

    override fun aliases(): List<String> = secrets.keys.toList()
}

/**
 * Ejecuta una operación del almacén fuera del hilo de interfaz.
 *
 * En escritorio, llamar al almacén del sistema operativo puede tardar cientos
 * de milisegundos (son procesos externos: PowerShell, `security`,
 * `secret-tool`). En Android, la primera vez que se usa la clave del Keystore
 * el sistema puede tardar más todavía. Nada de eso puede bloquear la UI.
 */
suspend fun <T> SecureStore.io(block: SecureStore.() -> T): T =
    withContext(ioDispatcher) { block() }

/**
 * Quita secretos de un texto antes de escribirlo en un log, un error, un
 * reporte de crash o una exportación.
 *
 * Trabaja en dos pasos:
 *  1. **Literales conocidos**: los secretos que la app tiene en memoria se
 *     registran con [register] y se reemplazan por una máscara. Esto cubre el
 *     caso que ningún patrón puede cubrir: una key con un formato raro.
 *  2. **Patrones**: se redactan las formas conocidas de credencial (headers
 *     `Authorization`, pares `api_key=…`, tokens `ktor_…`, `sk-…`, JWT).
 *
 * Es una función **pura y determinista** para un mismo texto y los mismos
 * literales registrados: se puede testear sin disco ni red.
 *
 * Ojo: la lista de patrones es una red de seguridad, no una garantía. La
 * garantía real es (a) no pasar el secreto al logger y (b) [register]arlo
 * apenas se resuelve.
 */
object SecretRedactor {

    /** Máscara con la que se reemplazan los secretos. */
    const val MASK = "«redactado»"

    /** Un literal más corto que esto no se registra: falsea positivo seguro. */
    private const val MIN_LITERAL_LENGTH = 8

    /** Secretos vivos conocidos: secreto → máscara. */
    private val known = mutableMapOf<String, String>()

    /** Registra un secreto vivo para que sea redactado en cualquier texto. */
    fun register(secret: String) {
        val s = secret.trim()
        if (s.length < MIN_LITERAL_LENGTH) return
        synchronized(known) { known[s] = MASK }
    }

    /** Olvida un secreto (cuando la key se rota o se borra). */
    fun forget(secret: String) {
        synchronized(known) { known.remove(secret.trim()) }
    }

    /** Redacta [text]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text

        // 1) Literales conocidos, del más largo al más corto: si un secreto
        //    contiene a otro, se redacta el largo primero y no queda un
        //    fragmento del secreto corto pegado a la máscara.
        val literals = synchronized(known) { known.keys.sortedByDescending { it.length } }
        for (secret in literals) {
            if (out.contains(secret)) out = out.replace(secret, MASK)
        }

        // 2) Patrones conocidos.
        for ((regex, replacement) in PATTERNS) out = regex.replace(out, replacement)
        return out
    }

    /** `ktor_<kid>_<secreto>`. */
    private val KTOR_TOKEN = Regex("""\bktor_([A-Za-z0-9]{4,16})_([A-Za-z0-9_\-]{6,})""")

    /** Prefijos de credenciales conocidos: `ktor_`, `sk-`, `ghp_`, `AKIA`… */
    private const val KNOWN_PREFIXES =
        "ktor_|sk-|ghp_|gho_|ghs_|github_pat_|xoxb-|xoxp_|AIza|ASIA|AKIA"

    /** Token que empieza por un prefijo conocido. */
    private val KNOWN_PREFIX_TOKEN = Regex("""\b(?:$KNOWN_PREFIXES)[A-Za-z0-9_\-]{10,}""")

    /** Cabecera `Authorization` (con o sin esquema). */
    private val AUTHORIZATION = Regex(
        """(?i)(["']?\bauthorization\b["']?\s*[:=]\s*["']?(?:bearer\s+|basic\s+|token\s+)?)([^"'\s,;}]+)""",
    )

    /** `Bearer <token>` sin la cabecera delante. */
    private val BEARER = Regex("""(?i)\b(bearer\s+)([A-Za-z0-9._\-+/=]{8,})""")

    /** `api_key=…`, `"apiKey": "…"`, `password=…`, `secret=…`… */
    private val CREDENTIAL_PAIR = Regex(
        """(?i)\b(api[_-]?key|apikey|authorization|password|passwd|pwd|secret""" +
            """|access[_-]?token|refresh[_-]?token|private[_-]?key|client[_-]?secret)""" +
            """\b(["']?\s*[:=]\s*["']?)([^\s"',;}\]]{8,})""",
    )

    /** `token=…`, `key=…`, `auth=…`: sólo si el valor parece una credencial. */
    private val WEAK_CREDENTIAL_PAIR = Regex(
        """(?i)\b(token|key|auth)\b(["']?\s*[:=]\s*["']?)([^\s"',;}\]]{12,})""",
    )

    /** `sk-…`. */
    private val OPENAI_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")

    /** JWT (tres partes separadas por puntos). */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")

    /**
     * Patrones de credencial: cada uno es un par (regex, reemplazo).
     *
     * El orden importa: primero el token de Kitoru completo (que es el más
     * específico), después las cabeceras y los pares clave=valor.
     */
    private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // ktor_<kid>_<secreto> → conserva el kid (público) y tapa el secreto.
        KTOR_TOKEN to { m ->
            val parts = m.value.split('_')
            if (parts.size >= 3) "ktor_${parts[1].take(4)}…" else "ktor_…"
        },

        // Cualquier otro token que empiece por un prefijo conocido: formato
        // desconocido, se tapa entero.
        KNOWN_PREFIX_TOKEN to { MASK },

        // Authorization: Bearer xxx  /  "authorization": "xxx"
        AUTHORIZATION to { m -> m.groupValues[1] + MASK },

        // "Bearer xxx" suelto, sin la cabecera delante.
        BEARER to { m -> m.groupValues[1] + MASK },

        // Pares clave=valor con nombres de credencial conocidos.
        CREDENTIAL_PAIR to { m -> m.groupValues[1] + m.groupValues[2] + MASK },

        // Pares con nombres genéricos (token, key): sólo si el valor *parece*
        // una credencial, para no romper texto normal ("tokens: demasiados").
        WEAK_CREDENTIAL_PAIR to { m ->
            val valor = m.groupValues[3]
            val pareceCredencial = valor.any { it.isDigit() } ||
                valor.contains('_') || valor.contains('-')
            if (pareceCredencial) m.groupValues[1] + m.groupValues[2] + MASK else m.value
        },

        // sk-… (formato de OpenAI y similares): se deja el prefijo como pista.
        OPENAI_KEY to { "sk-…" },

        // JWT.
        JWT to { "«jwt»" },
    )
}

/** Atajo: redacta secretos de un texto. */
fun redactSecrets(text: String): String = SecretRedactor.redact(text)

/**
 * Codificación hexadecimal (sólo ASCII).
 *
 * Sirve para dos cosas:
 *  - Convertir un alias (que puede tener `:` y otros caracteres no válidos en
 *    un nombre de archivo de Windows) en un nombre de archivo reversible.
 *  - Mandar bytes a un proceso externo sin depender de `java.util.Base64`,
 *    que en Android sólo existe desde la API 26 y el proyecto soporta la 24.
 */
internal object Hex {

    private const val DIGITS = "0123456789abcdef"

    /** Codifica [data] en hexadecimal en minúsculas. */
    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Decodifica hexadecimal; `null` si el texto no es hexadecimal válido. */
    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = value(text[i * 2])
            val lo = value(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun value(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
