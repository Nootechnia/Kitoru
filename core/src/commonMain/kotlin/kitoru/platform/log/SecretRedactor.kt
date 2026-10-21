package kitoru.platform.log

/**
 * Redacción de secretos.
 *
 * Se aplica **en el logger** (antes de construir el registro), no en los
 * call sites: es una defensa en profundidad. Si alguien escribe
 * `logger.info("key=$apiKey")`, la clave no llega al archivo, ni al buffer
 * circular, ni al portapapeles del diagnóstico.
 *
 * El principio es simple: **más vale tapar de más que filtrar de menos**. Un
 * texto que se redacta de más se lee raro; una API key filtrada se lee en un
 * bug report público.
 */
object SecretRedactor {

    /** Máximo de caracteres de un preview de texto. */
    const val MAX_PREVIEW = 160

    /** Lo que queda en el lugar del secreto. */
    const val MASK = "[REDACTED]"

    /**
     * Nombres de campo tratados como secretos.
     *
     * `token` **no** está en la lista a propósito: en Kitoru "token" es la
     * unidad de conteo del modelo ("se agotaron los tokens"), no un secreto.
     * Sólo se tapan los tokens que tienen nombre de secreto
     * (`access_token`, `refresh_token`…).
     */
    private val SECRET_FIELDS = listOf(
        "api[\\s_-]?key",        // apiKey, api_key, api-key, "api key"
        "x-api-key",
        "authorization",
        "secret[\\s_-]?key",
        "access[\\s_-]?key",
        "private[\\s_-]?key",
        "client[\\s_-]?secret",
        "secret",
        "password",
        "passwd",
        "access[\\s_-]?token",
        "refresh[\\s_-]?token",
        "session[\\s_-]?token",
        "id[\\s_-]?token",
    )

    /**
     * `api_key=valor`, `"api_key": "valor"`, `Authorization: Bearer valor`…
     *
     * El valor se come el prefijo `Bearer` cuando está: si no, el token
     * "Bearer eyJhbGciOi…" quedaría a la vista (se redactaría el nombre del
     * campo, pero no el token en sí).
     *
     * El separador admite la comilla de cierre del nombre del campo para
     * tapar también la forma JSON: `"api_key": "valor"`.
     */
    private val FIELD_PATTERN = Regex(
        "(?i)\\b(${SECRET_FIELDS.joinToString("|")})\\b([\"']?\\s*[:=]\\s*)" +
            "(\"(?:Bearer\\s+)?[^\"]*\"|(?:Bearer\\s+)?[^\"\\s,;&}]+)",
    )

    /** `Bearer eyJhbGciOi...` suelto, sin el nombre del campo. */
    private val BEARER_PATTERN = Regex("(?i)\\b(bearer)\\s+([A-Za-z0-9._\\-]{8,})")

    /** Tokens con prefijo reconocible (OpenAI, GitHub, Google, Ktor). */
    private val PREFIXED_PATTERNS = listOf(
        Regex("\\bsk-[A-Za-z0-9_\\-]{6,}"),          // OpenAI
        Regex("\\bgh[pousr]_[A-Za-z0-9]{16,}"),      // GitHub
        Regex("\\bAIza[0-9A-Za-z_\\-]{20,}"),        // Google
        Regex("\\bktor_[A-Za-z0-9_\\-]{4,}"),        // tokens de Ktor
    )

    /** `https://usuario:clave@host` → `https://[REDACTED]@host`. */
    private val URL_USERINFO_PATTERN = Regex("(?i)(://)[^/\\s:@]+:[^/\\s@]+@")

    /**
     * Reemplaza los secretos que se puedan detectar en [text].
     *
     * El orden importa: primero los campos con nombre (que se comen el
     * `Bearer` junto), después los `Bearer` sueltos, después los tokens con
     * prefijo conocido y por último las URLs con usuario y clave.
     */
    fun redact(text: String): String {
        var out = FIELD_PATTERN.replace(text) { m ->
            val name = m.groupValues[1]
            val separator = m.groupValues[2]
            val value = m.groupValues[3]
            // Si el valor estaba entre comillas, se conservan las comillas:
            // el texto resultante sigue siendo un JSON o un YAML válido.
            val masked = if (value.startsWith("\"")) "\"$MASK\"" else MASK
            "$name$separator$masked"
        }
        out = BEARER_PATTERN.replace(out) { "${it.groupValues[1]} $MASK" }
        PREFIXED_PATTERNS.forEach { pattern -> out = pattern.replace(out, MASK) }
        out = URL_USERINFO_PATTERN.replace(out) { "${it.groupValues[1]}$MASK@" }
        return out
    }

    /**
     * Estado de un secreto, sin revelar nada: `configurada` / `no configurada`.
     *
     * No se muestra ni un prefijo ni los últimos caracteres: la regla es
     * "nunca", no "casi nunca".
     */
    fun maskSecret(secret: String): String =
        if (secret.isBlank()) "no configurada" else "configurada"

    /**
     * Preview de un texto para el log: los primeros [max] caracteres.
     *
     * Se usa **sólo** para textos que no son del alumno (nombres de archivo,
     * mensajes de error). Para el contenido del alumno se usa [lengthOnly].
     */
    fun preview(text: String, max: Int = MAX_PREVIEW): String = when {
        text.length <= max -> text
        else -> text.take(max) + "… (+${text.length - max} caracteres)"
    }

    /** Marca de longitud: el contenido no se registra. */
    fun lengthOnly(text: String): String = "($(text.length) caracteres, contenido no registrado)"
}
