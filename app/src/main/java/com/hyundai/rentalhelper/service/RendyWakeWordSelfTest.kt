package com.hyundai.rentalhelper.service

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile

/**
 * Debug builds only (CoachOverlayService ACTION_WAKEWORD_SELFTEST): decodes 16 kHz mono 16-bit WAV files from
 * files/wakeeval/<label>/ with the production model, grammar, 2048-byte chunking and accept rule
 * (RendyWakeWordController.decide), so wake detection can be compared between model variants on the device.
 * Results: files/wakeeval/result_<variant>.json and a logcat summary. No microphone is used.
 */
internal object RendyWakeWordSelfTest {
    private const val TAG = "RendyWakeSelfTest"

    fun run(context: Context) {
        val root = File(context.filesDir, "wakeeval")
        val started = SystemClock.elapsedRealtime()
        val model = RendyWakeWordModelCache.getOrLoad(context)
        val variant = RendyWakeWordModelCache.loadedVariant
        val results = JSONArray()
        val counts = mutableMapOf<String, IntArray>()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }?.forEach { wav ->
                val hyps = JSONArray()
                var accepted = false
                Recognizer(model, 16_000f, RendyWakeWordController.KEYWORD_GRAMMAR).use { rec ->
                    rec.setWords(true)
                    val pcm = readPcm(wav)
                    var i = 0
                    while (i < pcm.size) {
                        val n = minOf(2048, pcm.size - i)
                        if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) {
                            val d = RendyWakeWordController.decide(rec.result)
                            if (d.normalized.isNotEmpty()) hyps.put("${d.normalized}:${d.confidence}")
                            accepted = accepted || d.accepted
                        }
                        i += n
                    }
                    val d = RendyWakeWordController.decide(rec.finalResult)
                    if (d.normalized.isNotEmpty()) hyps.put("${d.normalized}:${d.confidence}")
                    accepted = accepted || d.accepted
                }
                counts.getOrPut(dir.name) { IntArray(2) }.let { it[0]++; if (accepted) it[1]++ }
                results.put(JSONObject().put("label", dir.name).put("file", wav.name).put("detected", accepted).put("hyps", hyps))
            }
        }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val summary = counts.entries.joinToString { (k, v) -> "$k=${v[1]}/${v[0]}" }
        File(root, "result_$variant.json").writeText(
            JSONObject().put("variant", variant).put("summary", summary).put("totalPssKb", mem.totalPss)
                .put("nativePssKb", mem.nativePss).put("results", results).toString(), Charsets.UTF_8
        )
        Log.i(TAG, "Wake self-test done: variant=$variant, detected/total: $summary, totalPssKb=${mem.totalPss}, elapsedMs=${SystemClock.elapsedRealtime() - started}")
    }

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
