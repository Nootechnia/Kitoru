package rag.agent.tools

import kitoru.platform.FileSystem
import kitoru.platform.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import llm.api.ToolSpec
import rag.agent.Artifact
import rag.agent.Tool
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolResult

/**
 * Extensiones que el modelo puede crear (texto y código).
 *
 * Es la misma lista de FunshiEngineAI: el modelo no escribe binarios ni
 * ejecutables, sólo documentos y código.
 */
private val ALLOWED_EXTENSIONS = setOf(
    "md", "mdx", "txt", "rst", "adoc", "csv", "tsv", "json", "yaml", "yml", "toml",
    "ini", "cfg", "conf", "properties", "xml", "html", "css", "scss", "js", "jsx",
    "ts", "tsx", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go",
    "rs", "rb", "php", "swift", "lua", "dart", "sql", "sh", "bat", "ps1", "gradle",
    "cmake", "glsl", "hlsl", "vert", "frag", "tex", "bib", "asm", "s", "r", "jl",
    "vue", "svg", "log", "env",
)

/** Nombres sin extensión que se permiten (los clásicos de proyecto). */
private val ALLOWED_BARE_NAMES = setOf("makefile", "dockerfile", "license", "readme", "gitignore")

/** Tope del nombre de archivo. */
private const val MAX_NAME_LENGTH = 120

/** Tipo MIME deducido de la extensión (para la tarjeta de adjunto). */
private val MIME_BY_EXT = mapOf(
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "md" to "text/markdown",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "html" to "text/html",
    "kt" to "text/x-kotlin",
    "py" to "text/x-python",
    "java" to "text/x-java",
    "js" to "text/javascript",
    "ts" to "text/x-typescript",
    "c" to "text/x-c",
    "cpp" to "text/x-c++",
    "cs" to "text/x-csharp",
    "go" to "text/x-go",
    "rs" to "text/x-rust",
    "sh" to "text/x-shellscript",
    "sql" to "text/x-sql",
    "tex" to "text/x-tex",
    "glsl" to "text/x-glsl",
)

/**
 * Herramienta de archivos: el modelo crea un documento de texto.
 *
 * Es la tool `create_file` de FunshiEngineAI, con dos cambios:
 *  - pide permiso de la categoría FILES (apagado por defecto, se concede por
 *    chat), igual que allá;
 *  - escribe con la abstracción de archivos portable ([FileSystem]) en vez de
 *    `java.nio.file`, para que también funcione en Android.
 *
 * Los archivos se escriben **siempre** dentro de `outputs/<chat-id>/`: el
 * modelo decide el nombre, no la carpeta.
 */
class CreateFileTool(
    private val fs: FileSystem,
    /** Tope de tamaño del archivo creado (en bytes). */
    private val maxBytes: Long = 5L * 1024 * 1024,
) : Tool {

    override val category = ToolCategory.FILES

    override val spec = ToolSpec(
        name = "create_file",
        description = "Crea un archivo de texto o código con el contenido dado. " +
            "Usala cuando el usuario pida un archivo, un código guardado o un documento.",
        parametersSchema = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Nombre del archivo con extensión (ej. notas.md)"},
              "content":{"type":"string","description":"Contenido del archivo"}
            },"required":["path","content"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return "Crear el archivo \"${path.ifBlank { "(sin nombre)" }}\""
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = (args["path"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val content = (args["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (rawPath.isBlank()) {
            return ToolResult.Error("Falta el campo \"path\": decí el nombre del archivo (con extensión).")
        }
        val name = rawPath.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank()) return ToolResult.Error("El nombre del archivo no es válido.")
        if (name.length > MAX_NAME_LENGTH) {
            return ToolResult.Error("El nombre es demasiado largo (máximo $MAX_NAME_LENGTH caracteres).")
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val bare = name.lowercase()
        if (ext.isNotEmpty() && ext !in ALLOWED_EXTENSIONS && bare !in ALLOWED_BARE_NAMES) {
            return ToolResult.Error(
                "La extensión \".$ext\" no está permitida. " +
                    "Extensiones válidas: ${ALLOWED_EXTENSIONS.sorted().joinToString(", ")}.",
            )
        }
        if (ext.isEmpty() && bare !in ALLOWED_BARE_NAMES) {
            return ToolResult.Error("El archivo necesita una extensión válida.")
        }
        val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > maxBytes) {
            return ToolResult.Error(
                "El archivo sería demasiado grande (${bytes / 1024} KB; el tope es ${maxBytes / 1024} KB).",
            )
        }

        val target = ctx.outputsDir.resolve(name)
        return withContext(ioDispatcher) {
            try {
                fs.createDirectories(ctx.outputsDir)
                fs.writeText(target, content)
                val artifact = Artifact(
                    name = name,
                    path = target.value,
                    sizeBytes = bytes,
                    mime = MIME_BY_EXT[ext] ?: "text/plain",
                )
                ToolResult.Ok("Archivo creado: $name (${bytes / 1024} KB)", listOf(artifact))
            } catch (e: Exception) {
                ToolResult.Error("No se pudo crear el archivo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
