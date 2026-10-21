package kitoru.platform

/**
 * Quita los diacríticos de un texto: "Definición" → "Definicion".
 *
 * La normalización es la base de la tokenización del índice BM25 y de la
 * verificación de citas: si "Definición" y "definicion" se comparan sin
 * normalizar, el índice no encuentra lo que el modelo escribió.
 */
expect fun stripDiacritics(text: String): String

/** Milisegundos desde epoch. */
expect fun nowMillis(): Long
