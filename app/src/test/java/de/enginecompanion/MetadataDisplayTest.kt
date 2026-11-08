package de.enginecompanion
import de.enginecompanion.data.MetadataDisplay
import org.junit.Assert.*
import org.junit.Test
class MetadataDisplayTest {
    @Test fun knownKeysMapToCamelotAndUnknownKeysStayRaw() {
        assertEquals("8B", MetadataDisplay.key("0"))
        assertEquals("11A", MetadataDisplay.key("7"))
        assertEquals("7A", MetadataDisplay.key("23"))
        assertEquals("11A · F♯m", MetadataDisplay.keyWithName("7"))
        assertEquals("Rohwert 99", MetadataDisplay.key("99"))
    }
    @Test fun ratingDoesNotSilentlyClampUnknownValues() { assertEquals("★★★★★  (100)",MetadataDisplay.rating("100"));assertTrue(MetadataDisplay.rating("120").contains("Rohwert"));assertTrue(MetadataDisplay.rating("3").contains("Rohwert")) }
    @Test fun bpmUsesOneDecimalAndRejectsInvalidValues() {
        assertEquals("141.4 BPM", MetadataDisplay.bpm(141.44770812988284))
        assertEquals("BPM —", MetadataDisplay.bpm(Double.NaN))
        assertEquals("BPM —", MetadataDisplay.bpm(0.0))
    }
    @Test fun durationUsesMinutesAndSeconds() {
        assertEquals("4:00", MetadataDisplay.duration(240.9))
        assertEquals("—:—", MetadataDisplay.duration(-1.0))
    }
}
