package rag.agent.tools

import kitoru.platform.nowMillis
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec
import rag.agent.Tool
import rag.agent.ToolContext
import rag.agent.ToolResult

/**
 * Herramienta de prueba del tool-calling: devuelve la fecha y hora actual.
 *
 * Es la que usa FunshiEngineAI para verificar el bucle completo con una
 * pregunta tipo "¿qué hora es?". En Kitoru sirve, además, para que el
 * docente vea en la analítica que el modelo usa herramientas.
 *
 * Es portable: no usa `java.time` (que no está en el código común), sólo el
 * reloj del sistema y un formateo manual.
 */
class GetTimeTool : Tool {
    override val spec = ToolSpec(
        name = "get_time",
        description = "Devuelve la fecha y hora actual del sistema. Usala si el usuario pregunta qué hora es o qué día es hoy.",
        parametersSchema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val millis = nowMillis()
        val formatted = formatUtc(millis)
        return ToolResult.Ok("Fecha y hora actual: $formatted (UTC)")
    }

    companion object {
        /** Formatea epoch millis como "yyyy-MM-dd HH:mm:ss" (UTC). */
        fun formatUtc(millis: Long): String {
            val seconds = millis / 1000
            val days = seconds / 86_400
            val rest = seconds % 86_400
            val hour = rest / 3600
            val minute = (rest % 3600) / 60
            val second = rest % 60
            // Días desde 1970-01-01: se usa el algoritmo civil de Howard Hinnant.
            val (y, m, d) = civilFromDays(days)
            return "%04d-%02d-%02d %02d:%02d:%02d".format(y, m, d, hour, minute, second)
        }

        /** Convierte días desde epoch a (año, mes, día) del calendario gregoriano. */
        private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
            var z = z0
            z += 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = (z - era * 146_097).toInt()
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val y = yoe.toLong() + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val year = if (m <= 2) y + 1 else y
            return Triple(year.toInt(), m, d)
        }
    }
}
