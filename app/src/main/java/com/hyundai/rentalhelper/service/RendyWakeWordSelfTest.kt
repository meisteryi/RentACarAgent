package com.hyundai.rentalhelper.service

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile

/**
 * Debug builds only (CoachOverlayService ACTION_WAKEWORD_SELFTEST): runs 16 kHz mono 16-bit WAV files from
 * files/wakeeval/<set>/ through the production wake pipeline: the live recognizer (RendyWakeWordController.createRecognizer:
 * grammar, word times, endpointer delays), 2048-byte chunks, stage 1 (decide) and stage 2 (RendyWakeIsolationCheck,
 * always evaluated here so both rules are measured on the same run). No microphone is used.
 *
 * - <set> directories: one fresh recognizer per file (detection sets).
 * - <set>_stream directories: all files in name order as one continuous stream through one recognizer, like live
 *   listening (false activations per hour). Activations are attributed to the file whose audio contains the word start.
 *
 * Results: files/wakeeval/result_<variant>.json (every stage-1 activation with its stage-2 result) and a logcat summary.
 */
internal object RendyWakeWordSelfTest {
    private const val TAG = "RendyWakeSelfTest"
    private const val CHUNK = 2048
    private const val BYTES_PER_SECOND = 32_000.0

    /** Stage-2 settings compared on the same activations; the first is the production default. */
    private val CANDIDATES = listOf(
        RendyWakeIsolationCheck.DEFAULT,
        RendyWakeIsolationCheck.Params(voiceFactor = 2.0, windowMs = 600, maxContextVoicedMs = 60, maxRegionMs = 1500),
        RendyWakeIsolationCheck.Params(voiceFactor = 2.0, windowMs = 600, maxContextVoicedMs = 60, maxRegionMs = 1200),
        RendyWakeIsolationCheck.Params(voiceFactor = 2.0, windowMs = 750, maxContextVoicedMs = 60, maxRegionMs = 1500),
        RendyWakeIsolationCheck.Params(voiceFactor = 2.0, windowMs = 600, maxContextVoicedMs = 40, maxRegionMs = 1500),
        RendyWakeIsolationCheck.Params(voiceFactor = 3.0, windowMs = 600, maxContextVoicedMs = 60, maxRegionMs = 1500)
    ).distinct()

    fun run(context: Context) {
        val root = File(context.filesDir, "wakeeval")
        val started = SystemClock.elapsedRealtime()
        val model = RendyWakeWordModelCache.getOrLoad(context)
        val variant = RendyWakeWordModelCache.loadedVariant
        val sets = JSONObject()
        val summary = StringBuilder()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
            val acts = JSONArray()
            var seconds = 0.0
            if (dir.name.endsWith("_stream")) {
                seconds = runStream(model, wavs, acts)
            } else {
                wavs.forEach { wav -> seconds += runFile(model, wav, acts) }
            }
            var stage1 = 0; var stage2 = 0
            for (i in 0 until acts.length()) { stage1++; if (acts.getJSONObject(i).getBoolean("isolated")) stage2++ }
            sets.put(dir.name, JSONObject().put("files", wavs.size).put("seconds", seconds).put("activations", acts))
            summary.append("${dir.name}: files=${wavs.size}, hours=${"%.3f".format(seconds / 3600)}, stage1=$stage1, stage1+2=$stage2; ")
        }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        File(root, "result_$variant.json").writeText(
            JSONObject().put("variant", variant).put("isolationParams", isolationParams()).put("totalPssKb", mem.totalPss)
                .put("sets", sets).toString(), Charsets.UTF_8
        )
        Log.i(TAG, "Wake self-test done: variant=$variant, $summary totalPssKb=${mem.totalPss}, elapsedMs=${SystemClock.elapsedRealtime() - started}")
    }

    private fun runFile(model: Model, wav: File, acts: JSONArray): Double {
        val pcm = readPcm(wav)
        val energy = RendyWakeIsolationCheck.FrameEnergyTracker(capacityFrames = 600)
        createRecognizer(model).use { rec ->
            var i = 0
            while (i < pcm.size) {
                val n = minOf(CHUNK, pcm.size - i)
                val chunk = pcm.copyOfRange(i, i + n)
                energy.feed(chunk, n)
                if (rec.acceptWaveForm(chunk, n)) record(rec.result, energy, wav.name, 0.0, acts)
                i += n
            }
            record(rec.finalResult, energy, wav.name, 0.0, acts)
        }
        return pcm.size / BYTES_PER_SECOND
    }

    private fun runStream(model: Model, wavs: List<File>, acts: JSONArray): Double {
        val energy = RendyWakeIsolationCheck.FrameEnergyTracker(capacityFrames = 600)
        val starts = ArrayList<Pair<Double, String>>()
        var offsetBytes = 0L
        createRecognizer(model).use { rec ->
            wavs.forEach { wav ->
                val pcm = readPcm(wav)
                starts.add(offsetBytes / BYTES_PER_SECOND to wav.name)
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(CHUNK, pcm.size - i)
                    val chunk = pcm.copyOfRange(i, i + n)
                    energy.feed(chunk, n)
                    if (rec.acceptWaveForm(chunk, n)) record(rec.result, energy, null, 0.0, acts, starts)
                    i += n
                }
                offsetBytes += pcm.size
            }
            record(rec.finalResult, energy, null, 0.0, acts, starts)
        }
        return offsetBytes / BYTES_PER_SECOND
    }

    private fun createRecognizer(model: Model): Recognizer = RendyWakeWordController.createRecognizer(model)

    /** Records a stage-1 activation with its stage-2 result (stage 2 always evaluated here, independent of the flag). */
    private fun record(json: String, energy: RendyWakeIsolationCheck.FrameEnergyTracker, file: String?, offset: Double,
                       acts: JSONArray, starts: List<Pair<Double, String>> = emptyList()) {
        val d = RendyWakeWordController.decide(json)
        if (!d.accepted || d.wordStartSec < 0) return
        val t0 = System.nanoTime()
        val iso = energy.evaluate(d.wordStartSec, d.wordEndSec)
        val evalMicros = (System.nanoTime() - t0) / 1_000
        val variants = JSONObject()
        CANDIDATES.forEach { p -> variants.put(p.label, energy.evaluate(d.wordStartSec, d.wordEndSec, p).isolated) }
        val name = file ?: starts.lastOrNull { it.first <= d.wordStartSec }?.second ?: "?"
        val fileStart = starts.lastOrNull { it.first <= d.wordStartSec }?.first ?: 0.0
        acts.put(JSONObject().put("file", name).put("t", d.wordStartSec - fileStart + offset).put("end", d.wordEndSec - fileStart + offset)
            .put("conf", d.confidence.toDouble()).put("isolated", iso.isolated).put("reason", iso.reason)
            .put("regionMs", iso.regionMs).put("preMs", iso.preVoicedMs).put("postMs", iso.postVoicedMs).put("floor", iso.floor)
            .put("evalMicros", evalMicros).put("variants", variants))
    }

    private fun isolationParams() = JSONObject().put("default", RendyWakeIsolationCheck.DEFAULT.label)
        .put("candidates", JSONArray(CANDIDATES.map { it.label }))

    /** Reads the data chunk of a PCM WAV file. */
    private fun readPcm(f: File): ByteArray = RandomAccessFile(f, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = (bytes[p + 4].toInt() and 0xff) or ((bytes[p + 5].toInt() and 0xff) shl 8) or
                ((bytes[p + 6].toInt() and 0xff) shl 16) or ((bytes[p + 7].toInt() and 0xff) shl 24)
            if (id == "data") return bytes.copyOfRange(p + 8, minOf(bytes.size, p + 8 + size))
            p += 8 + size + (size and 1)
        }
        ByteArray(0)
    }
}
