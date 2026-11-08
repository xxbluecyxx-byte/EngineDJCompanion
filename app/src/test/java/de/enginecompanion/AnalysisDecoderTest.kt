package de.enginecompanion

import de.enginecompanion.data.AnalysisDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class AnalysisDecoderTest {
    @Test fun decodesConfirmedCueLoopAndBeatGridLayout() {
        val analysis = AnalysisDecoder.decode(beatData(), quickCues(), loops())
        assertEquals(44_100.0, analysis.beatGrid!!.sampleRate, 0.001)
        assertEquals(1, analysis.beatGrid!!.defaultMarkers.size)
        assertEquals("Drop", analysis.cues.single().label)
        assertEquals(44_100.0, analysis.cues.single().sampleOffset, 0.001)
        assertEquals("Loop", analysis.loops.single().label)
        assertEquals(88_200.0, analysis.loops.single().startSample, 0.001)
        val report = AnalysisDecoder.report(analysis)
        assertTrue(report.contains("1.000 s"))
        assertTrue(report.contains("2.000 s – 4.000 s"))
    }

    @Test fun rejectsUnsafeEntryCount() {
        val raw = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(10_001).array()
        val error = runCatching { AnalysisDecoder.decode(null, null, raw) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun beatData(): ByteArray {
        val raw = ByteBuffer.allocate(33 + 24).apply {
            order(ByteOrder.BIG_ENDIAN).putDouble(44_100.0).putDouble(4_410_000.0).put(1)
            putLong(1)
            order(ByteOrder.LITTLE_ENDIAN).putDouble(0.0).putLong(1).putInt(100).putInt(0)
            order(ByteOrder.BIG_ENDIAN).putLong(0)
        }.array()
        return qtCompress(raw)
    }

    private fun quickCues(): ByteArray {
        val label = "Drop".toByteArray()
        val raw = ByteBuffer.allocate(8 + 1 + label.size + 8 + 4 + 8 + 1 + 8).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(1).put(label.size.toByte()).put(label).putDouble(44_100.0)
            put(255.toByte()).put(1).put(2).put(3)
            putDouble(88_200.0).put(1).putDouble(0.0)
        }.array()
        return qtCompress(raw)
    }

    private fun loops(): ByteArray {
        val label = "Loop".toByteArray()
        return ByteBuffer.allocate(8 + 1 + label.size + 16 + 6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(1).put(label.size.toByte()).put(label).putDouble(88_200.0).putDouble(176_400.0)
            put(1).put(1).put(255.toByte()).put(10).put(20).put(30)
        }.array()
    }

    private fun qtCompress(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val compressed = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                compressed.write(buffer, 0, count)
            }
            ByteBuffer.allocate(4 + compressed.size()).order(ByteOrder.BIG_ENDIAN)
                .putInt(raw.size).put(compressed.toByteArray()).array()
        } finally { deflater.end() }
    }
}
