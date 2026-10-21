package kitoru.ui

/**
 * La fuente del tiempo de la UI.
 *
 * Los ViewModel no leen el reloj del sistema: lo reciben inyectado. Así un
 * test puede congelar el tiempo y saber exactamente qué marca se guarda, en
 * vez de depender de la hora en que casualmente corrió.
 *
 * Es el mismo patrón que el FS y los repositorios: la UI no sabe de dónde
 * vienen los datos, sólo los pide.
 */
interface Clock {

    /** Milisegundos desde epoch. */
    fun nowMillis(): Long
}

/**
 * El reloj del sistema: el que usa la app.
 *
 * Se llama [SystemClock] y no `nowMillis` a propósito: dentro del objeto, un
 * `nowMillis()` sin receiver se resolvería a sí mismo (el del reloj) en vez de
 * al de `kitoru.platform`, y el reloj se llamaría a sí mismo para siempre.
 */
object SystemClock : Clock {
    override fun nowMillis(): Long = kitoru.platform.nowMillis()
}
