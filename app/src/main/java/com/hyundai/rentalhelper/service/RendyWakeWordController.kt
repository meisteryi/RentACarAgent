package com.hyundai.rentalhelper.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.sqrt

/** Korean offline keyword spotting backed by the bundled Vosk Korean model. */
class RendyWakeWordController(
    context: Context,
    private val onDetected: (Detection) -> Unit,
    private val onListening: () -> Unit = {}
) {
    data class Detection(
        val detectedAtElapsedMs: Long,
        val firstVoiceAtElapsedMs: Long,
        val lastVoiceAtElapsedMs: Long,
        val confidence: Float,
        val text: String
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RendyWakeWordCapture").apply { isDaemon = true }
    }
    private val lock = Any()
    private val stopWaiters = mutableListOf<Pair<Long, () -> Unit>>()

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var desiredListening = false
    @Volatile private var modelPreparing = false
    @Volatile private var detectionDelivered = false
    @Volatile private var pendingDetection: Detection? = null
    @Volatile private var captureTaskActive = false
    @Volatile private var released = false
    @Volatile private var generation = 0

    val isRunning: Boolean get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun isConfigured(): Boolean = try {
        appContext.assets.open(MODEL_ARCHIVE_ASSET).use { true }
    } catch (_: IOException) {
        false
    }

    /** Load the model while another subsystem (such as the intro TTS) owns the microphone. */
    fun prepare() {
        synchronized(lock) {
            if (RendyWakeWordModelCache.isReady || modelPreparing || released) return
            modelPreparing = true
        }
        try {
            executor.execute { loadModelAndStartIfRequested() }
        } catch (e: Exception) {
            synchronized(lock) { modelPreparing = false }
            Log.e(TAG, "Wake word model preparation could not be queued; PTT remains available", e)
        }
    }

    fun start(): Boolean {
        if (appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Wake word start failed: RECORD_AUDIO permission missing; PTT remains available")
            return false
        }
        val requestGeneration: Int
        val shouldQueue: Boolean
        val shouldLoad: Boolean
        synchronized(lock) {
            if (released) return false
            if (desiredListening) return true
            desiredListening = true
            detectionDelivered = false
            requestGeneration = ++generation
            shouldQueue = RendyWakeWordModelCache.isReady
            shouldLoad = !RendyWakeWordModelCache.isReady && !modelPreparing && !RendyWakeWordModelCache.isPreparing
            if (shouldLoad) modelPreparing = true
            Log.i(TAG, "Wake word requested: phrase=$WAKE_PHRASE, model=$MODEL_NAME, threshold=$MIN_CONFIDENCE, endSilence=${END_OF_SPEECH_SECONDS}s")
        }
        if (shouldQueue) {
            try {
                executor.execute { beginCapture(requestGeneration) }
            } catch (e: Exception) {
                failStart(requestGeneration, "capture task could not be queued", e)
                return false
            }
        } else if (shouldLoad) {
            try {
                executor.execute { loadModelAndStartIfRequested() }
            } catch (e: Exception) {
                failStart(requestGeneration, "model load task could not be queued", e)
                return false
            }
        } else {
            synchronized(lock) {
                if (modelPreparing && !RendyWakeWordModelCache.isReady) {
                    Log.i(TAG, "Wake word waiting for the shared Vosk model to finish preparing")
                }
            }
        }
        return true
    }

    private fun loadModelAndStartIfRequested() {
        try {
            val prepareStartedAt = SystemClock.elapsedRealtime()
            RendyWakeWordModelCache.getOrLoad(appContext)
            val startGeneration: Int?
            synchronized(lock) {
                modelPreparing = false
                startGeneration = if (desiredListening && !released) generation else null
            }
            Log.i(TAG, "Wake word model ready: processCached=${RendyWakeWordModelCache.wasReused}, waitMs=${SystemClock.elapsedRealtime() - prepareStartedAt}")
            if (startGeneration != null) beginCapture(startGeneration)
        } catch (e: Exception) {
            synchronized(lock) {
                modelPreparing = false
                if (desiredListening) desiredListening = false
            }
            Log.e(TAG, "Wake word model preparation failed: ${e.message}; PTT remains available", e)
        }
    }

    private fun beginCapture(requestGeneration: Int) {
        val modelForSession = try { RendyWakeWordModelCache.getOrLoad(appContext) } catch (e: Exception) {
            failStart(requestGeneration, "Vosk model unavailable: ${e.message}", e)
            return
        }
        synchronized(lock) {
            if (!isCurrent(requestGeneration)) return
            captureTaskActive = true
        }
        var recognizer: Recognizer? = null
        var recorder: AudioRecord? = null
        val captureStartedAt = SystemClock.elapsedRealtime()
        try {
            recognizer = Recognizer(modelForSession, SAMPLE_RATE_HZ.toFloat(), KEYWORD_GRAMMAR).apply {
                setWords(true)
                setEndpointerDelays(5.0f, END_OF_SPEECH_SECONDS, 8.0f)
            }
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBuffer <= 0) throw IOException("AudioRecord has no 16 kHz mono PCM input (minBuffer=$minimumBuffer)")
            val readBuffer = ByteArray(READ_CHUNK_BYTES)
            val internalBufferBytes = maxOf(minimumBuffer * 2, readBuffer.size * 4)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                internalBufferBytes
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord initialization failed")
            synchronized(lock) {
                if (!isCurrent(requestGeneration)) {
                    return
                }
                audioRecord = recorder
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("AudioRecord did not enter RECORDSTATE_RECORDING")
            }
            val actualRate = recorder.sampleRate
            val voiceThreshold = RendyWakeWordSettings.VOICE_RMS_THRESHOLD
            var firstVoiceAt = 0L
            var lastVoiceAt = 0L
            var lastMetricsAt = captureStartedAt
            var lastPartialAt = captureStartedAt
            var lastPartial = ""
            var totalBytes = 0L
            var chunkCount = 0L
            Log.i(TAG, "Wake word microphone active: requestedRate=$SAMPLE_RATE_HZ, actualRate=$actualRate, channels=1, encoding=PCM_16BIT, readChunkBytes=${readBuffer.size}, minBufferBytes=$minimumBuffer, bufferBytes=$internalBufferBytes, source=VOICE_RECOGNITION")
            onListening()

            while (isCurrent(requestGeneration)) {
                val read = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                val readAt = SystemClock.elapsedRealtime()
                if (read < 0) {
                    if (isCurrent(requestGeneration)) throw IOException("AudioRecord.read failed: $read")
                    break
                }
                if (read == 0) continue
                totalBytes += read
                chunkCount++
                val rms = calculateRms(readBuffer, read)
                if (rms >= voiceThreshold) {
                    if (firstVoiceAt == 0L) firstVoiceAt = readAt
                    lastVoiceAt = readAt
                }
                if (readAt - lastMetricsAt >= METRICS_INTERVAL_MS) {
                    val silenceMs = if (lastVoiceAt == 0L) readAt - captureStartedAt else readAt - lastVoiceAt
                    Log.i(TAG, "Audio metrics: sampleRate=$actualRate, chunkBytes=$read, totalBytes=$totalBytes, chunks=$chunkCount, rms=${"%.1f".format(rms)}, dbfs=${"%.1f".format(rmsToDbfs(rms))}, voiceThreshold=$voiceThreshold, silenceMs=$silenceMs")
                    lastMetricsAt = readAt
                }

                val segmentEnded = recognizer.acceptWaveForm(readBuffer, read)
                if (segmentEnded) {
                    val hypothesis = recognizer.result
                    if (inspectResult(hypothesis, requestGeneration, "segment", firstVoiceAt, lastVoiceAt)) return
                    firstVoiceAt = 0L
                    lastVoiceAt = 0L
                    lastPartial = ""
                } else if (readAt - lastPartialAt >= PARTIAL_LOG_INTERVAL_MS) {
                    val partialJson = recognizer.partialResult
                    val partial = try { JSONObject(partialJson).optString("partial").trim() } catch (_: Exception) { "" }
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        Log.i(TAG, "Partial candidate: text=$partial, source=partial, json=$partialJson")
                        lastPartial = partial
                    }
                    lastPartialAt = readAt
                }
            }
        } catch (e: Exception) {
            if (isCurrent(requestGeneration)) failStart(requestGeneration, "microphone/decoder failure: ${e.message}", e)
        } finally {
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (e: Exception) { Log.w(TAG, "AudioRecord stop during cleanup: ${e.message}") }
            try { recorder?.release() } catch (e: Exception) { Log.w(TAG, "AudioRecord release failed: ${e.message}") }
            try { recognizer?.close() } catch (e: Exception) { Log.w(TAG, "Vosk recognizer release failed: ${e.message}") }
            val detection: Detection?
            val waiters: List<Pair<Long, () -> Unit>>
            synchronized(lock) {
                if (audioRecord === recorder) audioRecord = null
                captureTaskActive = false
                val candidate = pendingDetection.also { pendingDetection = null }
                detection = if (candidate != null && desiredListening && generation == requestGeneration && !released) {
                    desiredListening = false
                    generation++
                    candidate
                } else null
                waiters = stopWaiters.toList()
                stopWaiters.clear()
            }
            val stoppedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "Wake word capture ended: elapsedMs=${stoppedAt - captureStartedAt}, requestCurrent=${isCurrent(requestGeneration)}, audioRecordReleased=${recorder == null || recorder.state == AudioRecord.STATE_UNINITIALIZED}")
            if (detection != null) {
                Log.i(TAG, "Wake handoff ready: audioRecordReleased=true, recognizerReleased=true, detectionToCallbackMs=${SystemClock.elapsedRealtime() - detection.detectedAtElapsedMs}")
                if (!released) onDetected(detection)
            }
            waiters.forEach { (requestedAt, callback) ->
                Log.i(TAG, "Wake mic release handoff ready: elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}, audioRecordReleased=true, recognizerReleased=true")
                runCatching(callback).onFailure { Log.e(TAG, "Wake mic release callback failed", it) }
            }
        }
    }

    private fun inspectResult(
        hypothesis: String,
        requestGeneration: Int,
        source: String,
        firstVoiceAt: Long,
        lastVoiceAt: Long
    ): Boolean {
        if (!isCurrent(requestGeneration) || detectionDelivered) return false
        val decision = decide(hypothesis)
        val text = decision.text
        val normalized = decision.normalized
        val confidence = decision.confidence
        Log.i(TAG, "Recognition candidate: source=$source, text=$text, normalized=$normalized, confidence=$confidence, json=$hypothesis")
        if (decision.accepted) {
            val detectedAt = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (!desiredListening || generation != requestGeneration || detectionDelivered) return false
                detectionDelivered = true
            }
            Log.i(TAG, "Wake word detected: phrase=$WAKE_PHRASE, confidence=$confidence, source=$source, voiceToDetectMs=${if(firstVoiceAt > 0L) detectedAt - firstVoiceAt else -1L}, endpointSilenceMs=${if(lastVoiceAt > 0L) detectedAt - lastVoiceAt else -1L}")
            val event = Detection(detectedAt, firstVoiceAt, lastVoiceAt, confidence, text)
            pendingDetection = event
            return true
        }
        val reason = when {
            normalized.isEmpty() -> "empty_result"
            normalized != WAKE_PHRASE -> "text_mismatch"
            else -> "confidence_below_threshold"
        }
        Log.i(TAG, "Wake word rejected: reason=$reason, candidate=$normalized, confidence=$confidence, required=$MIN_CONFIDENCE, source=$source")
        return false
    }

    private fun calculateRms(data: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun rmsToDbfs(rms: Double): Double = 20.0 * log10((rms / 32768.0).coerceAtLeast(1e-6))

    private fun isCurrent(requestGeneration: Int) = desiredListening && generation == requestGeneration && !released

    private fun failStart(requestGeneration: Int, reason: String, error: Exception? = null) {
        synchronized(lock) {
            if (generation == requestGeneration) desiredListening = false
        }
        if (error == null) Log.e(TAG, "Wake word capture failed: $reason; PTT remains available")
        else Log.e(TAG, "Wake word capture failed: $reason; PTT remains available", error)
    }

    fun stop() {
        val recorder: AudioRecord?
        synchronized(lock) {
            desiredListening = false
            generation++
            recorder = audioRecord
        }
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (e: Exception) { Log.w(TAG, "Wake word microphone stop failed: ${e.message}") }
        Log.i(TAG, "Wake word microphone stop requested")
    }

    /** Stop capture and invoke the callback only after AudioRecord and Vosk Recognizer are released. */
    fun stopAndRunAfterRelease(onReleased: () -> Unit) {
        val requestedAt = SystemClock.elapsedRealtime()
        val canRunNow: Boolean
        synchronized(lock) {
            canRunNow = !captureTaskActive && audioRecord == null
            if (!canRunNow) stopWaiters += requestedAt to onReleased
        }
        stop()
        if (canRunNow) {
            Log.i(TAG, "Wake mic release handoff ready: elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}, audioRecordReleased=true, recognizerReleased=true, alreadyIdle=true")
            runCatching(onReleased).onFailure { Log.e(TAG, "Wake mic release callback failed", it) }
        }
    }

    fun release() {
        stop()
        synchronized(lock) { released = true }
        executor.shutdown()
        Log.i(TAG, "Wake word controller released; process Vosk model retained for service reuse")
    }

    private fun installModelIfNeeded(): File {
        val baseDirectory = File(appContext.filesDir, "rendy-wakeword")
        val modelDirectory = File(baseDirectory, MODEL_NAME)
        if (File(modelDirectory, "am/final.mdl").isFile && File(modelDirectory, "graph/HCLr.fst").isFile) return modelDirectory
        if (!baseDirectory.exists() && !baseDirectory.mkdirs()) throw IOException("Cannot create model directory")
        val staging = File(baseDirectory, "$MODEL_NAME.installing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create model staging directory")
        val canonicalRoot = staging.canonicalPath + File.separator
        appContext.assets.open(MODEL_ARCHIVE_ASSET).use { asset ->
            ZipInputStream(asset.buffered()).use { zip ->
                var entry = zip.nextEntry
                val buffer = ByteArray(32 * 1024)
                while (entry != null) {
                    val output = File(staging, entry.name)
                    if (!output.canonicalPath.startsWith(canonicalRoot)) throw IOException("Unsafe model archive entry")
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Cannot create model directory: ${entry.name}")
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create model path") }
                        output.outputStream().buffered().use { out ->
                            var count = zip.read(buffer)
                            while (count >= 0) {
                                if (count > 0) out.write(buffer, 0, count)
                                count = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val extractedModel = File(staging, MODEL_NAME)
        if (!File(extractedModel, "am/final.mdl").isFile || !File(extractedModel, "graph/HCLr.fst").isFile) {
            staging.deleteRecursively()
            throw IOException("Korean Vosk model archive is incomplete")
        }
        modelDirectory.deleteRecursively()
        if (!extractedModel.renameTo(modelDirectory)) throw IOException("Cannot finalize Korean Vosk model extraction")
        staging.deleteRecursively()
        Log.i(TAG, "Installed public Korean Vosk model into app-private storage: ${modelDirectory.absolutePath}")
        return modelDirectory
    }

    companion object {
        fun prepareProcessModel(context: Context) = RendyWakeWordModelCache.prepare(context)
        private const val TAG = "RendyWakeWord"
        private const val WAKE_PHRASE = RendyWakeWordSettings.KEYWORD
        private const val MODEL_NAME = "vosk-model-small-ko-0.22"
        private const val MODEL_ARCHIVE_ASSET = "rendy/vosk-model-small-ko-0.22.zip"
        private const val SAMPLE_RATE_HZ = RendyWakeWordSettings.SAMPLE_RATE_HZ
        private const val MIN_CONFIDENCE = RendyWakeWordSettings.MIN_CONFIDENCE
        private const val END_OF_SPEECH_SECONDS = RendyWakeWordSettings.END_OF_SPEECH_SECONDS
        private const val READ_CHUNK_BYTES = 2_048
        private const val METRICS_INTERVAL_MS = 1_000L
        private const val PARTIAL_LOG_INTERVAL_MS = 300L
        /** Words of the Vosk runtime grammar; the lite model vocabulary (RendyWakeWordLiteGraph) is built from this list. */
        internal val GRAMMAR_WORDS = listOf(WAKE_PHRASE, "<UNK>")
        internal val KEYWORD_GRAMMAR = GRAMMAR_WORDS.joinToString(", ", "[", "]") { "\"$it\"" }

        internal data class Decision(val text: String, val normalized: String, val confidence: Float, val accepted: Boolean)

        /** The single wake accept rule (also used by the debug self-test): text == "렌디" and min word conf >= threshold. */
        internal fun decide(hypothesisJson: String): Decision {
            val text = try { JSONObject(hypothesisJson).optString("text").trim() } catch (_: Exception) { "" }
            val normalized = text.replace(Regex("\\s+"), "").replace(Regex("[\\p{Punct}\\p{P}]+$"), "")
            val confidence = extractConfidence(hypothesisJson)
            return Decision(text, normalized, confidence, normalized == WAKE_PHRASE && confidence >= MIN_CONFIDENCE)
        }

        private fun extractConfidence(json: String): Float {
            return try {
                val words = JSONObject(json).optJSONArray("result") ?: return 0f
                if (words.length() == 0) return 0f
                var confidence = 1f
                for (i in 0 until words.length()) confidence = minOf(confidence, words.getJSONObject(i).optDouble("conf", 0.0).toFloat())
                confidence
            } catch (_: Exception) { 0f }
        }
    }
}
