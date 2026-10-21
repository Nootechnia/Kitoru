package kitoru.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher para trabajo de entrada/salida.
 *
 * En las dos plataformas queFlipдан (escritorio y Android) es el mismo
 * dispatcher de la JVM, pero se expone desde `commonMain` para que el
 * código de red pueda usarlo sin saber dónde corre.
 */
expect val ioDispatcher: CoroutineDispatcher
