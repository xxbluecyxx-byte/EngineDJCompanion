package de.enginecompanion.data
object MetadataDisplay {
    private val keys = listOf("C", "Am", "G", "Em", "D", "Bm", "A", "F♯m", "E", "D♭m", "B", "A♭m", "F♯", "E♭m", "D♭", "B♭m", "A♭", "Fm", "E♭", "Cm", "B♭", "Gm", "F", "Dm")
    fun key(raw: String?): String = raw?.toIntOrNull()?.let { keys.getOrNull(it) } ?: raw?.let { "Rohwert $it" } ?: "—"
    fun rating(raw: String?): String {
        val value = raw?.toIntOrNull() ?: return raw?.let { "Rating: $it (Rohwert)" } ?: "Rating —"
        if (value !in 0..100 || value % 20 != 0) return "Rating: $raw (Rohwert)"
        return "★".repeat(value / 20) + "☆".repeat(5 - value / 20) + "  ($raw)"
    }
}
