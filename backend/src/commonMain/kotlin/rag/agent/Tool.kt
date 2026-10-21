package rag.agent

import kitoru.platform.FilePath
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec

enum class ToolCategory(val label: String) {
    NONE("Sin permisos"),
    FILES("Archivos"),
    WEB("Web"),
    MCP("MCP"),
    ;

    companion object {
        /** Convierte el nombre persistido ("FILES") al enum; null si no existe. */
        fun fromName(name: String): ToolCategory? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

enum class ToolOrigin(val label: String) {
    LOCAL("Local"),
    MCP("MCP"),
}

enum class PermissionDecision {
    ALLOW_ONCE,
    ALLOW_ALWAYS,
    DENY,
}

data class PermissionRequest(
    val chatId: String,
    val tool: String,
    val category: ToolCategory,
    val summary: String,
    val argsJson: String = "",
)

data class ToolContext(
    val chatId: String,
    /** Carpeta donde la app deja los archivos del chat (`outputs/<chat-id>`). */
    val outputsDir: FilePath,
    /** Categorías ya concedidas en este chat (se ejecutan sin preguntar). */
    val grantedCategories: Set<ToolCategory> = emptySet(),
    /** Pide permiso al usuario; la UI muestra el diálogo y responde. */
    val requestPermission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.DENY },
)

data class Artifact(
    val name: String,
    /** Ruta del archivo en disco. */
    val path: String,
    val sizeBytes: Long,
    val mime: String = "",
)

sealed class ToolResult {
    data class Ok(val text: String, val artifacts: List<Artifact> = emptyList()) : ToolResult()
    data class Error(val message: String) : ToolResult()

    val ok: Boolean get() = this is Ok

    fun render(): String = when (this) {
        is Ok -> text
        is Error -> message
    }
}

interface Tool {
    val spec: ToolSpec

    /** Categoría de permiso que exige (NONE = siempre disponible). */
    val category: ToolCategory get() = ToolCategory.NONE

    /** De dónde viene la herramienta (LOCAL por defecto; MCP la sobrescribe). */
    val origin: ToolOrigin get() = ToolOrigin.LOCAL

    /** Resumen legible de la llamada, para el diálogo de permiso. */
    fun describeCall(args: JsonObject): String = spec.name

    /** Ejecuta la herramienta. Todo I/O bloqueante va en un dispatcher de E/S. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
