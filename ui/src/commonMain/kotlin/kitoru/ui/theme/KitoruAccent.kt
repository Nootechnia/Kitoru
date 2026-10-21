package kitoru.ui.theme

/**
 * Colores de acento que puede elegir el usuario.
 *
 * Es la misma idea que el `uiAccent` de FunshiEngineAI (un entero ARGB en la
 * config), pero con una lista cerrada: así el tema se ve bien en las dos
 * variantes (claro y oscuro) y el usuario no puede elegir un acento ilegible.
 */
enum class KitoruAccent(val label: String, val value: Int) {
    /** Violeta: el color con el que arrancó FunshiEngineAI. */
    Violeta("Violeta", 0xFF6200EE.toInt()),

    /** Azul institucional. */
    Azul("Azul", 0xFF1E5AA8.toInt()),

    /** Verde. */
    Verde("Verde", 0xFF2E7D4F.toInt()),

    /** Terracota. */
    Terracota("Terracota", 0xFFB4552D.toInt()),

    /** Gris azulado (el más sobrio). */
    Pizarra("Pizarra", 0xFF44586B.toInt()),
    ;

    companion object {
        /** Acento por defecto. */
        val Default: KitoruAccent = Violeta

        /** Busca por nombre; si no existe, devuelve el de por defecto. */
        fun fromName(name: String): KitoruAccent? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
