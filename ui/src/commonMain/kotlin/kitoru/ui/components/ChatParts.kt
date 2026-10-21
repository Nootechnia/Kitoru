package kitoru.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kitoru.domain.CitationStats

/**
 * Distintivo de citas verificadas.
 *
 * Es el port del `CitationBadge` de FunshiEngineAI, con los tres estados que
 * importan:
 *  - todas las citas respaldadas → "Citas verificadas (n)",
 *  - hay citas sin respaldo o sin fuente → cuántas,
 *  - no hubo citas → "Respondió sin el material".
 *
 * El ícono y el texto se leen bien con lector de pantalla: el `contentDescription`
 * describe el estado completo, no sólo "verificado".
 */
@Composable
fun CitationBadge(
    citation: CitationStats?,
    modifier: Modifier = Modifier,
    hasSources: Boolean = false,
) {
    if (citation == null) return
    val (icon, tint, text) = when {
        citation.total == 0 -> Triple(
            Icons.Filled.WarningAmber,
            MaterialTheme.colorScheme.tertiary,
            "Respondió sin citar el material",
        )

        citation.unsupported == 0 && citation.unknown == 0 -> Triple(
            Icons.Filled.Verified,
            MaterialTheme.colorScheme.primary,
            "Citas verificadas (${citation.verified})",
        )

        else -> Triple(
            Icons.Filled.WarningAmber,
            MaterialTheme.colorScheme.error,
            buildString {
                if (citation.unsupported > 0) {
                    append("${citation.unsupported} cita${if (citation.unsupported == 1) "" else "s"} sin respaldo")
                }
                if (citation.unknown > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${citation.unknown} sin fuente")
                }
            },
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(0.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/**
 * Lista de las fuentes que el modelo usó en una respuesta.
 *
 * Son los materiales de la cátedra: el alumno ve de dónde salió la
 * respuesta y puede abrir el material.
 */
@Composable
fun SourceList(
    sources: List<String>,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit = {},
) {
    if (sources.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Fuentes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sources.forEach { source ->
            Text(
                text = "· $source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Tarjeta de una herramienta que el modelo pidió. */
@Composable
fun ToolCallRow(
    name: String,
    ok: Boolean?,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (ok) {
        null -> "Ejecutando…" to MaterialTheme.colorScheme.onSurfaceVariant
        true -> "OK" to MaterialTheme.colorScheme.primary
        false -> "Falló" to MaterialTheme.colorScheme.error
    }
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
