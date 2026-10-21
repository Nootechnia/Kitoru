package kitoru.platform

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * El almacén de secretos del **escritorio** y el motor criptográfico que
 * comparten escritorio y Android.
 *
 * Por qué el almacén del escritorio vive acá y no en `desktopMain`: la
 * selección de protector (nativo del sistema operativo → archivo cifrado →
 * memoria) es lógica de negocio de la plataforma, y el motor AES-GCM lo usan
 * **las dos** plataformas (el fallback del escritorio y el Keystore de
 * Android). Lo que **no** puede estar en un source set compartido es
 * `ProcessBuilder` (ver [runOsCommand]): por eso el comando del sistema
 * operativo entra por un seam.
 */
internal object DesktopSecureStore : SecureStore {

    /** Nombre del sistema operativo, en minúsculas. */
    private val osName: String = System.getProperty("os.name").lowercase()

    private val isWindows: Boolean = osName.contains("win")
    private val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    /**
     * Carpeta del almacén.
     *
     * **No** es `~/.kitoru`: esa es la carpeta que la gente copia, sincroniza
     * con OneDrive/Drive y mete en un backup. Las credenciales no van ahí.
     */
    private val baseDir: File = when {
        isWindows -> File(
            System.getenv("LOCALAPPDATA")
                ?: "${System.getProperty("user.home")}/AppData/Local",
            "Kitoru",
        )
        isMac -> File(System.getProperty("user.home"), "Library/Application Support/Kitoru")
        else -> File(
            System.getenv("XDG_DATA_HOME")
                ?: "${System.getProperty("user.home")}/.local/share",
            "kitoru",
        )
    }

    /** Carpeta de las credenciales dentro de la carpeta del sistema. */
    private val credentialsDir: File = File(baseDir, "credentials")

    /**
     * El protector elegido.
     *
     * Se resuelve una sola vez y se cachea: elegir protector cuesta un
     * comando del sistema operativo (o un cálculo de PBKDF2) y no tiene
     * sentido pagarlo en cada lectura.
     */
    private val protector: SecretProtector by lazy { selectProtector() }

    override val kind: SecureStoreKind get() = protector.kind

    override var lastError: String? = null

    /**
     * Elige el protector y **degrada de forma visible**.
     *
     * La degradación nunca es a texto plano: si no hay ningún protector, el
     * almacén pasa a [InMemorySecureStore] y la UI avisa que la clave no se
     * va a guardar.
     */
    private fun selectProtector(): SecretProtector {
        val nativo: SecretProtector = when {
            isWindows -> DpapiProtector(credentialsDir)
            isMac -> MacosKeychainProtector(credentialsDir)
            else -> SecretToolProtector(credentialsDir)
        }
        if (nativo.isAvailable()) return nativo

        val fallback = FileAesGcmProtector(credentialsDir)
        if (fallback.isAvailable()) return fallback

        // Regla de oro: sin almacén, el secreto vive sólo en memoria.
        return MemoryProtector()
    }

    override fun put(alias: String, secret: String) {
        try {
            protector.put(alias, secret.toByteArray(Charsets.UTF_8))
            lastError = null
        } catch (e: Exception) {
            lastError = "No se pudo guardar la credencial: ${e.message ?: "error desconocido"}"
        }
    }

    override fun get(alias: String): String? = try {
        protector.get(alias)?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        lastError = "No se pudo leer la credencial: ${e.message ?: "error desconocido"}"
        null
    }

    override fun remove(alias: String) {
        try {
            protector.remove(alias)
            lastError = null
        } catch (e: Exception) {
            lastError = "No se pudo borrar la credencial: ${e.message ?: "error desconocido"}"
        }
    }

    override fun has(alias: String): Boolean = try {
        protector.has(alias)
    } catch (e: Exception) {
        false
    }

    override fun aliases(): List<String> = try {
        protector.aliases()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Un protector de secretos: sabe guardar y recuperar el valor de un alias.
 *
 * El secreto viaja como bytes porque el almacén del sistema operativo lo
 * guarda crudo; el texto se convierte en los bordes (UTF-8).
 */
internal interface SecretProtector {
    /** Qué tecnología protege este protector. */
    val kind: SecureStoreKind

    /** ¿El protector está disponible en esta máquina? */
    fun isAvailable(): Boolean

    /** Guarda (o reemplaza) el secreto del alias. */
    fun put(alias: String, secret: ByteArray)

    /** Lee el secreto del alias; `null` si no está. */
    fun get(alias: String): ByteArray?

    /** Borra el secreto del alias. */
    fun remove(alias: String)

    /** ¿Hay un secreto guardado bajo ese alias? */
    fun has(alias: String): Boolean

    /** Aliases guardados (nunca los secretos). */
    fun aliases(): List<String>
}

/**
 * Protector sobre **DPAPI** de Windows (`CryptProtectData`).
 *
 * Sin dependencias externas: la llamada se hace con Windows PowerShell, que
 * tiene `System.Security.Cryptography.ProtectedData` en el framework .NET.
 * DPAPI ata el dato a la **cuenta de Windows** que lo cifró: el mismo
 * archivo, copiado a otra cuenta o a otra máquina, no se puede descifrar.
 *
 * El secreto **no** viaja como argumento del proceso (se ve en `ps`): va por
 * la entrada estándar, en hexadecimal.
 */
private class DpapiProtector(private val dir: File) : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.DPAPI

    override fun isAvailable(): Boolean {
        val probe = runPowerShell(PROBE_SCRIPT, null)
        return probe.exitCode == 0 && probe.output.trim() == "OK"
    }

    override fun put(alias: String, secret: ByteArray) {
        val result = runPowerShell(PROTECT_SCRIPT, Hex.encode(secret))
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "Windows no devolvió el dato cifrado: ${result.output.trim()}",
            )
        }
        writeFile(fileFor(alias), Hex.decode(result.output.trim()) ?: ByteArray(0))
    }

    override fun get(alias: String): ByteArray? {
        val file = fileFor(alias)
        if (!file.exists()) return null
        val result = runPowerShell(UNPROTECT_SCRIPT, Hex.encode(file.readBytes()))
        if (result.exitCode != 0) return null
        return Hex.decode(result.output.trim())
    }

    override fun remove(alias: String) {
        fileFor(alias).delete()
    }

    override fun has(alias: String): Boolean = fileFor(alias).exists()

    override fun aliases(): List<String> = dir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(EXTENSION) }
        .mapNotNull { file ->
            Hex.decode(file.name.removeSuffix(EXTENSION))?.toString(Charsets.UTF_8)
        }

    private fun fileFor(alias: String): File =
        File(dir, Hex.encode(alias.toByteArray(Charsets.UTF_8)) + EXTENSION)

    private companion object {
        const val EXTENSION = ".dpapi"

        /** Sólo verifica que el tipo exista y se pueda cargar. */
        val PROBE_SCRIPT: List<String> = listOf(
            "\$ErrorActionPreference = 'Stop'",
            "foreach (\$a in @('System.Security','System.Security.Cryptography.ProtectedData')) " +
                "{ try { Add-Type -AssemblyName \$a -ErrorAction Stop } catch { } }",
            "if (-not ('System.Security.Cryptography.ProtectedData' -as [type])) { exit 3 }",
            "[Console]::Out.Write('OK')",
        )

        /** Cifra con DPAPI (`CurrentUser`) lo que llega por la entrada estándar. */
        val PROTECT_SCRIPT: List<String> = listOf(
            "\$ErrorActionPreference = 'Stop'",
            "foreach (\$a in @('System.Security','System.Security.Cryptography.ProtectedData')) " +
                "{ try { Add-Type -AssemblyName \$a -ErrorAction Stop } catch { } }",
            "if (-not ('System.Security.Cryptography.ProtectedData' -as [type])) { exit 3 }",
            "\$hex = [Console]::In.ReadToEnd().Trim()",
            "\$bytes = New-Object byte[] (\$hex.Length / 2)",
            "for (\$i = 0; \$i -lt \$bytes.Length; \$i++) " +
                "{ \$bytes[\$i] = [Convert]::ToByte(\$hex.Substring(\$i * 2, 2), 16) }",
            "\$entropy = [System.Text.Encoding]::UTF8.GetBytes('kitoru.credentials.v1')",
            "\$blob = [System.Security.Cryptography.ProtectedData]::Protect(\$bytes, \$entropy, " +
                "[System.Security.Cryptography.DataProtectionScope]::CurrentUser)",
            "\$out = -join (\$blob | ForEach-Object { \$_.ToString('x2') })",
            "[Console]::Out.Write(\$out)",
        )

        /** Descifra con DPAPI lo que llega por la entrada estándar. */
        val UNPROTECT_SCRIPT: List<String> = listOf(
            "\$ErrorActionPreference = 'Stop'",
            "foreach (\$a in @('System.Security','System.Security.Cryptography.ProtectedData')) " +
                "{ try { Add-Type -AssemblyName \$a -ErrorAction Stop } catch { } }",
            "if (-not ('System.Security.Cryptography.ProtectedData' -as [type])) { exit 3 }",
            "\$hex = [Console]::In.ReadToEnd().Trim()",
            "\$bytes = New-Object byte[] (\$hex.Length / 2)",
            "for (\$i = 0; \$i -lt \$bytes.Length; \$i++) " +
                "{ \$bytes[\$i] = [Convert]::ToByte(\$hex.Substring(\$i * 2, 2), 16) }",
            "\$entropy = [System.Text.Encoding]::UTF8.GetBytes('kitoru.credentials.v1')",
            "\$blob = [System.Security.Cryptography.ProtectedData]::Unprotect(\$bytes, \$entropy, " +
                "[System.Security.Cryptography.DataProtectionScope]::CurrentUser)",
            "\$out = -join (\$blob | ForEach-Object { \$_.ToString('x2') })",
            "[Console]::Out.Write(\$out)",
        )
    }
}

/**
 * Protector sobre el **llavero de macOS**, vía la herramienta `security`.
 *
 * Cada alias es un ítem `generic-password` del llavero de login.
 *
 * Aviso honesto: la CLI `security` no tiene forma documentada de recibir el
 * valor por la entrada estándar, así que el secreto viaja como argumento del
 * proceso, y en macOS los argumentos son visibles para los demás usuarios de
 * la máquina con `ps`. Por eso, después de guardar, se **verifica** que el
 * llavero devuelve lo mismo: si no, el almacén degrada al archivo cifrado y
 * lo dice (degradación visible, nunca silenciosa).
 */
private class MacosKeychainProtector(private val dir: File) : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.MACOS_KEYCHAIN

    /** Índice local de aliases: el llavero no los lista de forma confiable. */
    private val indexFile: File = File(dir, "aliases.txt")

    override fun isAvailable(): Boolean {
        val result = runOsCommand(listOf("security", "-h"), null, TIMEOUT_MS)
        return result.exitCode == 0 || result.output.contains("usage")
    }

    override fun put(alias: String, secret: ByteArray) {
        val text = secret.toString(Charsets.UTF_8)
        val result = runOsCommand(
            listOf("security", "add-generic-password", "-U", "-a", alias, "-s", SERVICE, "-w", text),
            null,
            TIMEOUT_MS,
        )
        if (result.exitCode != 0) {
            throw IllegalStateException("El llavero rechazó el guardado: ${result.output.trim()}")
        }
        // Verificación de ida y vuelta: si el llavero no devuelve lo mismo,
        // no sirve y hay que decirlo (el llamador degrada al archivo).
        val guardado = runOsCommand(
            listOf("security", "find-generic-password", "-a", alias, "-s", SERVICE, "-w"),
            null,
            TIMEOUT_MS,
        )
        if (guardado.exitCode != 0 || guardado.output.trim() != text) {
            throw IllegalStateException("El llavero no devolvió la credencial que se guardó.")
        }
        rememberAlias(alias)
    }

    override fun get(alias: String): ByteArray? {
        val result = runOsCommand(
            listOf("security", "find-generic-password", "-a", alias, "-s", SERVICE, "-w"),
            null,
            TIMEOUT_MS,
        )
        if (result.exitCode != 0) return null
        return result.output.trimEnd('\n', '\r').toByteArray(Charsets.UTF_8)
    }

    override fun remove(alias: String) {
        runOsCommand(
            listOf("security", "delete-generic-password", "-a", alias, "-s", SERVICE),
            null,
            TIMEOUT_MS,
        )
        forgetAlias(alias)
    }

    override fun has(alias: String): Boolean = get(alias) != null

    override fun aliases(): List<String> = readIndex()

    private fun rememberAlias(alias: String) {
        val aliases = readIndex().toMutableList()
        if (!aliases.contains(alias)) {
            aliases += alias
            writeIndex(aliases)
        }
    }

    private fun forgetAlias(alias: String) {
        writeIndex(readIndex().filter { it != alias })
    }

    private fun readIndex(): List<String> {
        if (!indexFile.exists()) return emptyList()
        return indexFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun writeIndex(aliases: List<String>) {
        dir.mkdirs()
        indexFile.writeText(aliases.joinToString("\n"), Charsets.UTF_8)
        restrictToOwner(indexFile)
    }

    private companion object {
        const val SERVICE = "kitoru"
        const val TIMEOUT_MS = 15_000L
    }
}

/**
 * Protector sobre el **Secret Service** de Linux (GNOME Keyring, KWallet),
 * vía `secret-tool`.
 *
 * `secret-tool store` lee el secreto de la entrada estándar: el secreto
 * **no** aparece en la lista de argumentos del proceso (a diferencia del
 * llavero de macOS).
 */
private class SecretToolProtector(private val dir: File) : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.LIBSECRET

    override fun isAvailable(): Boolean {
        val result = runOsCommand(listOf("secret-tool", "--version"), null, TIMEOUT_MS)
        return result.exitCode == 0
    }

    override fun put(alias: String, secret: ByteArray) {
        val result = runOsCommand(
            listOf("secret-tool", "store", "--label=Kitoru", "service", SERVICE, "account", alias),
            secret.toString(Charsets.UTF_8),
            TIMEOUT_MS,
        )
        if (result.exitCode != 0) {
            throw IllegalStateException("El gestor de secretos rechazó el guardado: ${result.output.trim()}")
        }
    }

    override fun get(alias: String): ByteArray? {
        val result = runOsCommand(
            listOf("secret-tool", "lookup", "service", SERVICE, "account", alias),
            null,
            TIMEOUT_MS,
        )
        if (result.exitCode != 0) return null
        return result.output.trimEnd('\n', '\r').toByteArray(Charsets.UTF_8)
    }

    override fun remove(alias: String) {
        runOsCommand(
            listOf("secret-tool", "clear", "service", SERVICE, "account", alias),
            null,
            TIMEOUT_MS,
        )
    }

    override fun has(alias: String): Boolean = get(alias) != null

    override fun aliases(): List<String> {
        val result = runOsCommand(
            listOf("secret-tool", "search", "--all", "service", SERVICE),
            null,
            TIMEOUT_MS,
        )
        if (result.exitCode != 0) return emptyList()
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("attribute.account") }
            .map { it.substringAfter('=').trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private companion object {
        const val SERVICE = "kitoru"
        const val TIMEOUT_MS = 15_000L
    }
}

/**
 * Último recurso: un archivo cifrado con **AES-GCM** y una clave derivada con
 * **PBKDF2**.
 *
 * Qué protege: que otro usuario de la misma máquina no lea la credencial y
 * que copiar **sólo** la carpeta de configuración no sirva.
 *
 * Qué **no** protege: malware que corra como el usuario, ni que se copie
 * todo el directorio del almacén (la clave vive al lado de los datos). Por
 * eso el almacén queda marcado como de protección reducida y la UI lo dice.
 *
 * La clave se deriva con PBKDF2 de tres cosas: una **semilla aleatoria**
 * guardada con permisos `0600`, el **nombre del usuario** y su carpeta
 * personal. La semilla sola no alcanza: el archivo de datos y el de la clave
 * están en el mismo directorio.
 */
private class FileAesGcmProtector(private val dir: File) : SecretProtector {

    override val kind: SecureStoreKind = SecureStoreKind.FILE_FALLBACK

    private val keyFile = File(dir, "credentials.key")
    private val saltFile = File(dir, "credentials.salt")
    private val secretsDir = File(dir, "secrets")

    /** La clave derivada: se calcula una vez y se cachea (PBKDF2 es caro). */
    private val key: SecretKey by lazy { deriveKey() }

    override fun isAvailable(): Boolean = try {
        dir.mkdirs()
        secretsDir.mkdirs()
        secretsDir.canWrite()
    } catch (e: Exception) {
        false
    }

    override fun put(alias: String, secret: ByteArray) {
        val sealed = AesGcm.seal(key, secret, aadOf(alias))
        writeFile(fileFor(alias), sealed)
    }

    override fun get(alias: String): ByteArray? {
        val file = fileFor(alias)
        if (!file.exists()) return null
        return AesGcm.open(key, file.readBytes(), aadOf(alias))
    }

    override fun remove(alias: String) {
        fileFor(alias).delete()
    }

    override fun has(alias: String): Boolean = fileFor(alias).exists()

    override fun aliases(): List<String> = secretsDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(EXTENSION) }
        .mapNotNull { file ->
            Hex.decode(file.name.removeSuffix(EXTENSION))?.toString(Charsets.UTF_8)
        }

    /**
     * El alias va como datos autenticados: si alguien cambia el nombre de un
     * archivo para moverle la credencial de otro alias, el descifrado falla.
     */
    private fun aadOf(alias: String): ByteArray = alias.toByteArray(Charsets.UTF_8)

    private fun fileFor(alias: String): File =
        File(secretsDir, Hex.encode(alias.toByteArray(Charsets.UTF_8)) + EXTENSION)

    private fun deriveKey(): SecretKey {
        dir.mkdirs()
        secretsDir.mkdirs()
        if (!keyFile.exists()) writeMaterial(keyFile, SEED_SIZE)
        if (!saltFile.exists()) writeMaterial(saltFile, SALT_SIZE)

        val seed = keyFile.readBytes()
        val salt = saltFile.readBytes()
        val user = System.getProperty("user.name").orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        // La contraseña del PBKDF2 mezcla la semilla aleatoria con datos del
        // usuario: con el archivo de datos solo no alcanza para derivarla.
        val password = (Hex.encode(seed) + "|" + user + "|" + home).toCharArray()

        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            val factory = secretKeyFactory()
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * `PBKDF2WithHmacSHA256` es el preferred; el SHA-1 es el respaldo para
     * las plataformas donde el SHA-256 no está (Android por debajo de la 26).
     */
    private fun secretKeyFactory(): SecretKeyFactory =
        runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            .getOrElse { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }

    private fun writeMaterial(file: File, size: Int) {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        writeFile(file, bytes)
        restrictToOwner(file)
    }

    private companion object {
        const val EXTENSION = ".bin"
        const val SEED_SIZE = 32
        const val SALT_SIZE = 16
        const val ITERATIONS = 210_000
        const val KEY_BITS = 256
    }
}

/**
 * El almacén en memoria, presentado como protector.
 *
 * Es el último eslabón de la regla de oro: si la máquina no ofrece ningún
 * almacén (ni nativo, ni un archivo cifrado), el secreto **no** se escribe
 * en ningún lado: vive en memoria hasta que se cierra la app.
 */
private class MemoryProtector : SecretProtector {

    private val store = InMemorySecureStore()

    override val kind: SecureStoreKind = SecureStoreKind.MEMORY

    override fun isAvailable(): Boolean = true

    override fun put(alias: String, secret: ByteArray) {
        store.put(alias, secret.toString(Charsets.UTF_8))
    }

    override fun get(alias: String): ByteArray? = store.get(alias)?.toByteArray(Charsets.UTF_8)

    override fun remove(alias: String) {
        store.remove(alias)
    }

    override fun has(alias: String): Boolean = store.has(alias)

    override fun aliases(): List<String> = store.aliases()
}

/**
 * AES-256-GCM: el motor de cifrado de los blobs de credenciales.
 *
 * Vive en el código compartido de las dos plataformas JVM porque lo usan
 * **las dos**: el archivo cifrado del escritorio y el blob del Keystore de
 * Android.
 *
 * El formato del blob es: `[versión][IV de 12 bytes][texto cifrado + tag]`.
 * El tag de GCM autentica el contenido: si alguien modifica el archivo, el
 * descifrado falla.
 */
internal object AesGcm {

    /** Tamaño del IV (el que recomienda el NIST para GCM). */
    const val IV_SIZE = 12

    /** Bits del tag de autenticación. */
    const val TAG_BITS = 128

    /** Versión del formato del blob. */
    const val FORMAT_VERSION: Byte = 1

    /** Cifra [plain] con [key], usando [aad] como datos autenticados. */
    fun seal(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        val out = ByteArray(1 + iv.size + body.size)
        out[0] = FORMAT_VERSION
        iv.copyInto(out, 1)
        body.copyInto(out, 1 + iv.size)
        return out
    }

    /** Descifra [blob]; `null` si el formato no sirve o si fue manipulado. */
    fun open(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray? {
        if (blob.size < 1 + IV_SIZE + 16) return null
        if (blob[0] != FORMAT_VERSION) return null
        return try {
            val iv = blob.copyOfRange(1, 1 + IV_SIZE)
            val body = blob.copyOfRange(1 + IV_SIZE, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (e: Exception) {
            // Clave rotada, blob corrupto, archivo manipulado: no se puede recuperar.
            null
        }
    }
}

/**
 * Escribe un archivo de forma **atómica**: primero en un temporal al lado y
 * después lo renombra sobre el destino.
 *
 * Un corte de luz a mitad de la escritura deja el archivo viejo intacto, no
 * uno truncado: una credencial a medias es peor que una credencial vieja.
 */
internal fun writeFile(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) {
        // Algunos sistemas (Windows con el destino abierto) no renombran:
        // se copia y se borra el temporal.
        file.writeBytes(bytes)
        temp.delete()
    }
}

/**
 * Deja un archivo sólo legible y escribible por su dueño (`0600`).
 *
 * En Windows no tiene efecto: la carpeta de datos del usuario ya es privada.
 */
internal fun restrictToOwner(file: File) {
    runCatching {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}

/**
 * Ejecuta un comando del sistema operativo.
 *
 * Es un `expect` porque `ProcessBuilder` **no** puede usarse en el código
 * compartido con Android (ver la tabla de portabilidad del `AGENTS.md`): en
 * Android no hay comandos del sistema operativo que ejecutar, y el `actual`
 * de Android devuelve un error explícito.
 *
 * El secreto, cuando viaja, va por la **entrada estándar**: los argumentos
 * de un proceso son visibles para los demás usuarios de la máquina.
 */
internal expect fun runOsCommand(
    command: List<String>,
    stdin: String?,
    timeoutMs: Long,
): OsCommandResult

/**
 * El resultado de ejecutar un comando del sistema operativo.
 *
 * `exitCode` negativo significa que el comando ni siquiera llegó a ejecutarse.
 */
internal data class OsCommandResult(val exitCode: Int, val output: String)

/**
 * Ejecuta un script de PowerShell.
 *
 * Se intenta primero Windows PowerShell (`powershell.exe`, que trae
 * `System.Security`) y después PowerShell 7 (`pwsh`), que no siempre tiene
 * el ensamblado de DPAPI.
 */
internal fun runPowerShell(script: List<String>, stdin: String?): OsCommandResult {
    val joined = script.joinToString("\n")
    for (shell in listOf("powershell.exe", "pwsh")) {
        val result = runOsCommand(
            listOf(shell, "-NoProfile", "-NonInteractive", "-Command", joined),
            stdin,
            TIMEOUT_MS,
        )
        // Si el comando no existe, el proceso no arranca: se prueba el otro.
        if (result.exitCode != SHELL_NOT_FOUND) return result
    }
    return OsCommandResult(SHELL_NOT_FOUND, "No hay PowerShell en esta máquina.")
}

private const val SHELL_NOT_FOUND = -1
private const val TIMEOUT_MS = 20_000L
