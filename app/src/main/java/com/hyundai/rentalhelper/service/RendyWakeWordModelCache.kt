package com.hyundai.rentalhelper.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipInputStream

/** Holds one native Vosk Model for the lifetime of the app process. */
internal object RendyWakeWordModelCache {
    private const val TAG = "RendyWakeWord"
    private const val MODEL_NAME = "vosk-model-small-ko-0.22"
    private const val MODEL_ARCHIVE_ASSET = "rendy/vosk-model-small-ko-0.22.zip"
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RendyVoskModelPrepare").apply { isDaemon = true }
    }
    @Volatile private var future: Future<Model>? = null
    @Volatile var wasReused = false
        private set
    val isReady: Boolean get() = future?.let { it.isDone && runCatching { it.get() }.isSuccess } == true
    val isPreparing: Boolean get() = future?.let { !it.isDone } == true

    fun prepare(context: Context) {
        synchronized(lock) {
            if (future != null) return
            future = executor.submit<Model> { load(context.applicationContext) }
            Log.i(TAG, "Vosk model prewarm queued on background executor")
        }
    }

    fun getOrLoad(context: Context): Model {
        val task = synchronized(lock) {
            future?.also { wasReused = true } ?: executor.submit<Model> {
                load(context.applicationContext)
            }.also { future = it }
        }
        return task.get()
    }

    private fun load(context: Context): Model {
        val totalStarted = SystemClock.elapsedRealtime()
        val base = File(context.filesDir, "rendy-wakeword")
        val directory = File(base, MODEL_NAME)
        val validationStarted = SystemClock.elapsedRealtime()
        val existing = File(directory, "am/final.mdl").isFile && File(directory, "graph/HCLr.fst").isFile
        val validationMs = SystemClock.elapsedRealtime() - validationStarted
        var extractionMs = 0L
        if (!existing) {
            val extractionStarted = SystemClock.elapsedRealtime()
            extract(context, base, directory)
            extractionMs = SystemClock.elapsedRealtime() - extractionStarted
        }
        if (debugProperty("debug.rendy.wakeword") == "off") {
            // Debug-only memory baseline: run the app without the Vosk model.
            throw IOException("Wake word model disabled by debug.rendy.wakeword=off")
        }
        val liteStarted = SystemClock.elapsedRealtime()
        val modelDirectory = if (useLiteGraph()) {
            try {
                RendyWakeWordLiteGraph.ensure(directory, RendyWakeWordController.GRAMMAR_WORDS)
            } catch (e: Exception) {
                Log.w(TAG, "Lite graph preparation failed; using the full model", e)
                directory
            }
        } else directory
        val liteMs = SystemClock.elapsedRealtime() - liteStarted
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val initStarted = SystemClock.elapsedRealtime()
        val model = Model(modelDirectory.absolutePath)
        val initMs = SystemClock.elapsedRealtime() - initStarted
        loadedVariant = if (modelDirectory == directory) "full" else "lite"
        Log.i(TAG, "Vosk model timing: variant=$loadedVariant, fileValidationMs=$validationMs, archiveExtractionMs=$extractionMs, litePrepareMs=$liteMs, nativeModelInitMs=$initMs, totalMs=${SystemClock.elapsedRealtime() - totalStarted}, filesAlreadyPrepared=$existing")
        return model
    }

    /** "full" or "lite" once loaded. */
    @Volatile var loadedVariant: String = "none"
        private set

    /** Build flag rendy.wakeword.liteGraph (default true); debug builds may override with debug.rendy.wakeword.graph=full|lite. */
    private fun useLiteGraph(): Boolean = when (debugProperty("debug.rendy.wakeword.graph")) {
        "full" -> false
        "lite" -> true
        else -> com.hyundai.rentalhelper.BuildConfig.RENDY_WAKEWORD_LITE_GRAPH
    }

    /** android.os.SystemProperties via reflection; debug builds only, otherwise always null. */
    internal fun debugProperty(name: String): String? {
        if (!com.hyundai.rentalhelper.BuildConfig.DEBUG) return null
        return try {
            val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
            v.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun extract(context: Context, base: File, destination: File) {
        if (!base.exists() && !base.mkdirs()) throw IOException("Cannot create model directory")
        val staging = File(base, "$MODEL_NAME.installing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create model staging directory")
        val canonicalRoot = staging.canonicalPath + File.separator
        context.assets.open(MODEL_ARCHIVE_ASSET).use { asset ->
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
        val extracted = File(staging, MODEL_NAME)
        if (!File(extracted, "am/final.mdl").isFile || !File(extracted, "graph/HCLr.fst").isFile) {
            staging.deleteRecursively()
            throw IOException("Korean Vosk model archive is incomplete")
        }
        destination.deleteRecursively()
        if (!extracted.renameTo(destination)) throw IOException("Cannot finalize Korean Vosk model extraction")
        staging.deleteRecursively()
        Log.i(TAG, "Installed public Korean Vosk model into app-private storage: ${destination.absolutePath}")
    }
}
