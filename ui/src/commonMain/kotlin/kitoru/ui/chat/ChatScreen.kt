package kitoru.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kitoru.ui.components.CitationBadge
import kitoru.ui.components.CodeBlock
import kitoru.ui.components.MarkdownText
import kitoru.ui.components.SourceList
import kitoru.ui.components.ToolCallRow
import kitoru.domain.Course
import kitoru.domain.ModelPolicy
import kitoru.ui.KitoruViewModel
import rag.retrieval.CourseMaterialSource
import rag.retrieval.KnowledgeBase

/**
 * Pantalla de chat del alumno.
 *
 * Es el corazón de la app: el alumno pregunta, el modelo responde **con el
 * material de la cátedra**, y cada respuesta muestra de dónde salió y si
 * las citas se sostienen.
 */
@Composable
fun ChatScreen(vm: KitoruViewModel, courseId: String, threadId: String?) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    // El índice se arma con el material de la cátedra. **Sin esto no hay
    // fuentes**: el modelo respondería sin contexto y el distintivo de citas
    // nunca aparecería (que es justo lo que el docente necesita ver).
    val kb = remember(courseId) {
        KnowledgeBase(rag.config.RagConfig(chunkSize = course.chunkSize, topK = course.modelPolicy.topK))
    }
    var indexStatus by remember(courseId) { mutableStateOf("Preparando el material…") }
    var indexReady by remember(courseId) { mutableStateOf(false) }

    LaunchedEffect(courseId) {
        val materials = vm.materialsFor(courseId).filter { it.visible }
        kb.sources = listOf(CourseMaterialSource(materials))
        kb.sync { indexStatus = it }
        indexReady = true
    }

    val chat = remember(courseId) { ChatViewModel(course, kb) }
    LaunchedEffect(courseId) { chat.start(course.modelPolicy) }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ChatHeader(course, chat)
        if (!indexReady) {
            Text(
                text = indexStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        MessageList(chat, Modifier.weight(1f))
        Composer(chat)
    }
}

/** Encabezado: nombre de la cátedra y el interruptor de "usar el material". */
@Composable
private fun ChatHeader(course: Course, chat: ChatViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = chat.effectivePolicy.modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = chat.useDocs,
                onClick = { chat.toggleDocs() },
                label = { Text(if (chat.useDocs) "Usando el material" else "Pregunta libre") },
            )
        }
    }
}

/** La lista de mensajes, con auto-scroll que sólo sigue si estabas al fondo. */
@Composable
private fun MessageList(chat: ChatViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val followToBottom = remember { mutableStateOf(true) }

    // El flag sólo cambia cuando cambia la posición del scroll: si el
    // usuario sube a leer algo, no se le baja la vista al seguir escribiendo.
    LaunchedEffect(listState) {
        var prev = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { pos ->
                if (pos != prev) {
                    val subio = pos.first < prev.first ||
                        (pos.first == prev.first && pos.second < prev.second)
                    followToBottom.value = if (subio) false else !listState.canScrollForward
                    prev = pos
                }
            }
    }

    LaunchedEffect(chat.messages.size) {
        if (followToBottom.value && chat.messages.isNotEmpty()) {
            listState.scrollToItem(chat.messages.lastIndex)
        }
    }
    LaunchedEffect(chat.messages.lastOrNull()?.text?.length) {
        if (followToBottom.value && chat.messages.isNotEmpty()) {
            listState.scrollToItem(chat.messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(chat.messages.size) { index ->
            MessageBubble(chat.messages[index])
        }
    }
}

/** Una burbuja del chat. */
@Composable
private fun MessageBubble(message: UiChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 720.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                message.toolCalls.forEach { tool ->
                    ToolCallRow(tool.name, tool.ok)
                }

                if (message.text.isNotBlank()) {
                    if (isUser) {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(message.text)
                    }
                } else if (message.error == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 2.dp,
                    )
                }

                message.error?.let {
                    Text(
                        text = "⚠ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!isUser) {
                    CitationBadge(message.citation, modifier = Modifier.padding(top = 6.dp))
                    SourceList(message.sources, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** El redactor de mensajes. */
@Composable
private fun Composer(chat: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            if (chat.queued.isNotEmpty()) {
                Text(
                    text = "${chat.queued.size} mensaje(s) en cola — se enviarán juntos al terminar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Escribí tu pregunta…") },
                    maxLines = 5,
                )
                IconButton(
                    onClick = {
                        if (chat.busy) chat.cancel() else {
                            chat.send(input)
                            input = ""
                        }
                    },
                    enabled = chat.busy || input.isNotBlank(),
                ) {
                    Icon(
                        imageVector = if (chat.busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (chat.busy) "Detener" else "Enviar",
                    )
                }
            }
        }
    }
}
