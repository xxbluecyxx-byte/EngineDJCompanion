package de.enginecompanion.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.Inflater

data class CuePoint(val number: Int, val label: String, val sampleOffset: Double, val color: String)
data class SavedLoop(val number: Int, val label: String, val startSample: Double, val endSample: Double, val color: String)
data class BeatMarker(val sampleOffset: Double, val beatNumber: Long, val beats: Int)
data class BeatGrid(val sampleRate: Double, val isSet: Boolean, val defaultMarkers: List<BeatMarker>, val adjustedMarkers: List<BeatMarker>)
data class WaveformPoint(val low: Int, val mid: Int, val high: Int)
data class OverviewWaveform(val samplesPerPoint: Double, val points: List<WaveformPoint>, val maxLow: Int, val maxMid: Int, val maxHigh: Int)
data class TrackAnalysis(val beatGrid: BeatGrid?, val cues: List<CuePoint>, val loops: List<SavedLoop>, val mainCueSample: Double?, val waveform: OverviewWaveform? = null)

object AnalysisDecoder {
    private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    fun decode(beatData: ByteArray?, quickCues: ByteArray?, loops: ByteArray?, overviewWaveform: ByteArray? = null): TrackAnalysis {
        val waveform = overviewWaveform?.takeIf { it.isNotEmpty() }?.let(::decodeOverviewWaveform)
        val grid = beatData?.takeIf { it.isNotEmpty() }?.let(::decodeBeatGrid)
        val cueResult = quickCues?.takeIf { it.isNotEmpty() }?.let(::decodeCues)
        val savedLoops = loops?.takeIf { it.isNotEmpty() }?.let(::decodeLoops).orEmpty()
        return TrackAnalysis(grid, cueResult?.first.orEmpty(), savedLoops, cueResult?.second, waveform)
    }

    fun estimatedBpm(grid: BeatGrid): Double? {
        val markers = grid.adjustedMarkers.ifEmpty { grid.defaultMarkers }
        if (markers.size < 2 || !grid.sampleRate.isFinite() || grid.sampleRate <= 0) return null
        val first = markers.first()
        val last = markers.last()
        val seconds = (last.sampleOffset - first.sampleOffset) / grid.sampleRate
        val beats = last.beatNumber - first.beatNumber
        return (beats * 60.0 / seconds).takeIf { it.isFinite() && it > 0 }
    }

    fun report(analysis: TrackAnalysis): String = buildString {
        val rate = analysis.beatGrid?.sampleRate?.takeIf { it.isFinite() && it > 0 }
        appendLine("Dekodierte Analyse-Daten (nur lesend):")
        appendLine("Sample-Rate: ${rate?.let { String.format(Locale.ROOT, "%.0f Hz", it) } ?: "nicht verfügbar"}")
        appendLine("Hot Cues: ${analysis.cues.size}")
        analysis.cues.forEach { cue ->
            appendLine("  ${cue.number}. ${cue.label.ifBlank { "ohne Label" }} · ${position(cue.sampleOffset, rate)} · ${cue.color}")
        }
        analysis.mainCueSample?.takeIf { it >= 0 }?.let { appendLine("Main Cue: ${position(it, rate)}") }
        appendLine("Gespeicherte Loops: ${analysis.loops.size}")
        analysis.loops.forEach { loop ->
            appendLine("  ${loop.number}. ${loop.label.ifBlank { "ohne Label" }} · ${position(loop.startSample, rate)} – ${position(loop.endSample, rate)} · ${loop.color}")
        }
        analysis.beatGrid?.let { grid ->
            val selected = grid.adjustedMarkers.ifEmpty { grid.defaultMarkers }
            appendLine("Beatgrid: ${if (grid.isSet) "gesetzt" else "nicht gesetzt"} · ${grid.defaultMarkers.size} Standard-/ ${grid.adjustedMarkers.size} angepasste Marker")
            selected.forEachIndexed { index, marker ->
                appendLine("  Marker ${index + 1}: ${position(marker.sampleOffset, rate)} · Beat ${marker.beatNumber} · ${marker.beats} Beats")
            }
        } ?: appendLine("Beatgrid: nicht vorhanden")
    }

    private fun position(samples: Double, rate: Double?): String = if (rate == null || !samples.isFinite()) {
        String.format(Locale.ROOT, "%.3f Samples", samples)
    } else {
        String.format(Locale.ROOT, "%.3f s", samples / rate)
    }

    private fun decodeCues(blob: ByteArray): Pair<List<CuePoint>, Double?> {
        val cursor = Cursor(inflateQt(blob))
        val count = cursor.i64(ByteOrder.BIG_ENDIAN).checkedCount("Hot-Cue")
        val cues = buildList {
            repeat(count) { index ->
                val label = cursor.utf8(cursor.u8())
                val offset = cursor.f64(ByteOrder.BIG_ENDIAN)
                cursor.u8()
                val color = color(cursor.u8(), cursor.u8(), cursor.u8())
                if (offset >= 0 || label.isNotEmpty()) add(CuePoint(index + 1, label, offset, color))
            }
        }
        val adjustedMainCue = cursor.f64(ByteOrder.BIG_ENDIAN)
        val adjusted = cursor.u8() != 0
        val defaultMainCue = cursor.f64(ByteOrder.BIG_ENDIAN)
        return cues to (if (adjusted) adjustedMainCue else defaultMainCue)
    }

    private fun decodeLoops(blob: ByteArray): List<SavedLoop> {
        val cursor = Cursor(blob)
        val count = cursor.i64(ByteOrder.LITTLE_ENDIAN).checkedCount("Loop")
        return buildList {
            repeat(count) { index ->
                val label = cursor.utf8(cursor.u8())
                val start = cursor.f64(ByteOrder.LITTLE_ENDIAN)
                val end = cursor.f64(ByteOrder.LITTLE_ENDIAN)
                val startSet = cursor.u8() != 0
                val endSet = cursor.u8() != 0
                cursor.u8()
                val color = color(cursor.u8(), cursor.u8(), cursor.u8())
                if (startSet || endSet || label.isNotEmpty()) add(SavedLoop(index + 1, label, start, end, color))
            }
        }
    }

    private fun decodeBeatGrid(blob: ByteArray): BeatGrid {
        val cursor = Cursor(inflateQt(blob))
        val sampleRate = cursor.f64(ByteOrder.BIG_ENDIAN)
        cursor.f64(ByteOrder.BIG_ENDIAN)
        val isSet = cursor.u8() != 0
        return BeatGrid(sampleRate, isSet, decodeMarkers(cursor), decodeMarkers(cursor))
    }

    private fun decodeMarkers(cursor: Cursor): List<BeatMarker> {
        val count = cursor.i64(ByteOrder.BIG_ENDIAN).checkedCount("Beatgrid-Marker")
        return List(count) {
            val offset = cursor.f64(ByteOrder.LITTLE_ENDIAN)
            val beat = cursor.i64(ByteOrder.LITTLE_ENDIAN)
            val beats = cursor.i32(ByteOrder.LITTLE_ENDIAN)
            cursor.i32(ByteOrder.LITTLE_ENDIAN)
            BeatMarker(offset, beat, beats)
        }
    }

    private fun Long.checkedCount(name: String): Int {
        require(this in 0..MAX_ENTRIES.toLong()) { "$name-Anzahl außerhalb des sicheren Bereichs: $this" }
        return toInt()
    }

    private fun color(red: Int, green: Int, blue: Int) = "#%02X%02X%02X".format(red, green, blue)


    private fun decodeOverviewWaveform(blob: ByteArray): OverviewWaveform {
        val cursor = Cursor(inflateQt(blob))
        val count1 = cursor.i64(ByteOrder.BIG_ENDIAN).toInt()
        val count2 = cursor.i64(ByteOrder.BIG_ENDIAN).toInt()
        require(count1 == count2 && count1 in 1..8192) { "Ungültige Waveform-Länge: $count1" }
        val samplesPerPoint = cursor.f64(ByteOrder.BIG_ENDIAN)
        val points = List(count1) {
            WaveformPoint(cursor.u8(), cursor.u8(), cursor.u8())
        }
        val maxLow = cursor.u8()
        val maxMid = cursor.u8()
        val maxHigh = cursor.u8()
        return OverviewWaveform(samplesPerPoint, points, maxLow, maxMid, maxHigh)
    }

    private fun inflateQt(blob: ByteArray): ByteArray {
        require(blob.size >= 6) { "Komprimierter Analyse-BLOB ist zu kurz." }
        val expected = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        require(expected in 1..MAX_INFLATED_BYTES.toLong()) { "Unkomprimierte Größe außerhalb des sicheren Bereichs: $expected" }
        val inflater = Inflater()
        return try {
            inflater.setInput(blob, 4, blob.size - 4)
            val output = ByteArrayOutputStream(expected.toInt())
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read > 0) {
                    require(output.size() + read <= MAX_INFLATED_BYTES) { "Analyse-BLOB überschreitet das Größenlimit." }
                    output.write(buffer, 0, read)
                } else require(!inflater.needsInput() && !inflater.needsDictionary()) { "Analyse-BLOB ist unvollständig oder benötigt ein Wörterbuch." }
            }
            output.toByteArray().also { require(it.size.toLong() == expected) { "Längenangabe des Analyse-BLOBs stimmt nicht." } }
        } finally { inflater.end() }
    }

    private class Cursor(private val bytes: ByteArray) {
        private var position = 0
        private fun take(count: Int): ByteBuffer {
            require(count >= 0 && position <= bytes.size - count) { "Analyse-BLOB endet unerwartet bei Byte $position." }
            return ByteBuffer.wrap(bytes, position, count).also { position += count }
        }
        fun u8(): Int = take(1).get().toInt() and 0xff
        fun i32(order: ByteOrder): Int = take(4).order(order).int
        fun i64(order: ByteOrder): Long = take(8).order(order).long
        fun f64(order: ByteOrder): Double = take(8).order(order).double
        fun utf8(length: Int): String {
            val buffer = take(length)
            val value = ByteArray(length)
            buffer.get(value)
            return value.toString(Charsets.UTF_8)
        }
    }
}
