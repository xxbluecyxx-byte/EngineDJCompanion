package de.enginecompanion
import de.enginecompanion.data.MetadataDisplay
import org.junit.Assert.*
import org.junit.Test
class MetadataDisplayTest {
    @Test fun knownKeysMapAndUnknownKeysStayRaw() { assertEquals("C",MetadataDisplay.key("0"));assertEquals("Dm",MetadataDisplay.key("23"));assertEquals("Rohwert 99",MetadataDisplay.key("99")) }
    @Test fun ratingDoesNotSilentlyClampUnknownValues() { assertEquals("★★★★★  (100)",MetadataDisplay.rating("100"));assertTrue(MetadataDisplay.rating("120").contains("Rohwert"));assertTrue(MetadataDisplay.rating("3").contains("Rohwert")) }
}
