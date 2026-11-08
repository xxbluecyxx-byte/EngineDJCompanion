package de.enginecompanion.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import de.enginecompanion.network.EngineXTrackMedia
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max

class HighResolutionWaveform(private val context: Context) {
    private val directory = File(context.cacheDir, "detailed-waveforms").apply { mkdirs() }

    fun loadOrCreate(trackId: Long, media: EngineXTrackMedia): OverviewWaveform {
        val cached = File(directory, "$trackId.wfd")
        read(cached)?.let { return it }
        val audio = File.createTempFile("track-$trackId-", ".audio", context.cacheDir)
        return try {
            download(media, audio)
            analyze(audio).also { write(cached, it) }
        } finally {
            audio.delete()
        }
    }

    private fun download(media: EngineXTrackMedia, target: File) {
        val connection = (URL(media.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Authorization", media.authorization)
            setRequestProperty("Accept", "audio/*")
            useCaches = false
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Audio-Download antwortet mit HTTP $code." }
            connection.inputStream.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun analyze(file: File): OverviewWaveform {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Keine Audiospur gefunden.")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audioformat unbekannt.")
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            val pointDurationUs = 10_000L
            val count = ceil(durationUs.toDouble() / pointDurationUs).toInt().coerceIn(1, 120_000)
            val low = FloatArray(count)
            val mid = FloatArray(count)
            val high = FloatArray(count)

            val decoder = MediaCodec.createDecoderByType(mime).apply { configure(inputFormat, null, null, 0); start() }
            codec = decoder
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var encoderDelayFrames = if (inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY)) inputFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0) else 0
            var decodedFrames = 0L
            var lowState = 0.0
            var midState = 0.0

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder-Eingabepuffer fehlt.")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) encoderDelayFrames = format.getInteger(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            val lowAlpha = 1.0 - exp(-2.0 * PI * 180.0 / sampleRate)
                            val midAlpha = 1.0 - exp(-2.0 * PI * 2_500.0 / sampleRate)
                            repeat(frames) { frame ->
                                var mono = 0.0
                                repeat(channels) {
                                    mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.toDouble() else buffer.short / 32768.0
                                }
                                mono /= channels
                                lowState += lowAlpha * (mono - lowState)
                                midState += midAlpha * (mono - midState)
                                val middle = midState - lowState
                                val upper = mono - midState
                                val alignedFrame = decodedFrames + frame - encoderDelayFrames
                                if (alignedFrame >= 0) {
                                    val timeUs = alignedFrame * 1_000_000L / sampleRate
                                    val bucket = (timeUs / pointDurationUs).toInt().coerceIn(0, count - 1)
                                    low[bucket] = max(low[bucket], kotlin.math.abs(lowState).toFloat())
                                    mid[bucket] = max(mid[bucket], kotlin.math.abs(middle).toFloat())
                                    high[bucket] = max(high[bucket], kotlin.math.abs(upper).toFloat())
                                }
                            }
                            decodedFrames += frames
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return toWaveform(low, mid, high, sampleRate * pointDurationUs / 1_000_000.0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun toWaveform(low: FloatArray, mid: FloatArray, high: FloatArray, samplesPerPoint: Double): OverviewWaveform {
        fun reference(values: FloatArray): Float {
            val nonZero = values.filter { it > 0f }.sorted()
            return nonZero.getOrElse((nonZero.size * 0.995).toInt().coerceAtMost(nonZero.lastIndex)) { 1f }.coerceAtLeast(0.0001f)
        }
        val combined = FloatArray(low.size) { index -> maxOf(low[index], mid[index] * 1.8f, high[index] * 3.2f) }
        val commonRef = reference(combined)
        fun byte(value: Float) = (value / commonRef * 255f).toInt().coerceIn(0, 255)
        val points = low.indices.map { WaveformPoint(byte(low[it]), byte(mid[it] * 1.8f), byte(high[it] * 3.2f)) }
        return OverviewWaveform(samplesPerPoint, points, 255, 255, 255)
    }

    private fun write(file: File, waveform: OverviewWaveform) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeInt(2)
            output.writeDouble(waveform.samplesPerPoint)
            output.writeInt(waveform.points.size)
            waveform.points.forEach { output.writeByte(it.low); output.writeByte(it.mid); output.writeByte(it.high) }
        }
        file.delete()
        check(temporary.renameTo(file)) { "Waveform-Cache konnte nicht aktiviert werden." }
    }

    private fun read(file: File): OverviewWaveform? = runCatching {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 2)
            val samplesPerPoint = input.readDouble()
            val count = input.readInt()
            require(count in 1..120_000)
            val points = List(count) { WaveformPoint(input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte()) }
            OverviewWaveform(samplesPerPoint, points, 255, 255, 255)
        }
    }.getOrNull()
}
