package kitoru.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatcher de E/S de la JVM. */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
