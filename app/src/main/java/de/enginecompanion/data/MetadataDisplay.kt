package de.enginecompanion.data
object MetadataDisplay {
    private val keys = listOf("C", "Am", "G", "Em", "D", "Bm", "A", "F♯m", "E", "D♭m", "B", "A♭m", "F♯", "E♭m", "D♭", "B♭m", "A♭", "Fm", "E♭", "Cm", "B♭", "Gm", "F", "Dm")
    private val camelot = listOf("8B", "8A", "9B", "9A", "10B", "10A", "11B", "11A", "12B", "12A", "1B", "1A", "2B", "2A", "3B", "3A", "4B", "4A", "5B", "5A", "6B", "6A", "7B", "7A")
    fun key(raw: String?): String = raw?.toIntOrNull()?.let { camelot.getOrNull(it) } ?: raw?.let { "Rohwert $it" } ?: "—"
    fun keyWithName(raw: String?): String = raw?.toIntOrNull()?.let { index ->
        camelot.getOrNull(index)?.let { "$it · ${keys[index]}" }
    } ?: raw?.let { "Rohwert $it" } ?: "—"
    fun rating(raw: String?): String {
        val value = raw?.toIntOrNull() ?: return raw?.let { "Rating: $it (Rohwert)" } ?: "Rating —"
        if (value !in 0..100 || value % 20 != 0) return "Rating: $raw (Rohwert)"
        return "★".repeat(value / 20) + "☆".repeat(5 - value / 20) + "  ($raw)"
    }
}
