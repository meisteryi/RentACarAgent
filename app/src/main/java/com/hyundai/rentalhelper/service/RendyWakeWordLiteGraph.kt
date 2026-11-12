package com.hyundai.rentalhelper.service

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Derives a "lite G" copy of the Vosk lookahead model for grammar-only wake-word decoding.
 *
 * The wake recognizer always passes a runtime grammar (["렌디","<UNK>"]), so Vosk composes HCLr.fst with a grammar
 * it builds itself; the 48.5 MB general language model graph/Gr.fst is only read at Model load (for its word
 * symbol table). This keeps every file except Gr.fst (linked, not copied, when the file system allows it), writes
 * graph/words.txt from Gr.fst's embedded output symbols, and replaces Gr.fst with a 1-state OpenFst vector FST.
 * words.txt keeps only the grammar words plus the structural symbols (<eps>, <s>, </s>, #n), each with its
 * original id, because HCLr.fst and the runtime grammar refer to words by id.
 *
 * Host, same files/grammar/340 clips: Model RSS 446 MB (full) -> 362 MB (lite, full vocabulary) -> 302 MB (lite,
 * grammar vocabulary), identical wake decisions (reports/wakeword-memory). The archive and extracted model are unchanged.
 */
internal object RendyWakeWordLiteGraph {
    private const val TAG = "RendyWakeWord"
    private const val FST_MAGIC = 2125659606
    private const val SYMBOL_TABLE_MAGIC = 2125658996
    private const val MARKER = "rendy-lite-graph-v4"

    /**
     * Returns the lite directory next to [full], creating it if needed. [grammarWords] must be every word the
     * runtime grammar uses; a missing word throws (the caller then falls back to the full model).
     */
    fun ensure(full: File, grammarWords: List<String>): File {
        val lite = File(full.parentFile, full.name + "-liteg")
        val keepLine = "keep=" + grammarWords.joinToString(",")
        val marker = File(lite, MARKER)
        if (marker.isFile && marker.readLines(Charsets.UTF_8).contains(keepLine)) return lite
        val staging = File(full.parentFile, full.name + "-liteg.installing")
        staging.deleteRecursively()
        full.walkTopDown().forEach { src ->
            val rel = src.relativeTo(full).path
            val dst = File(staging, rel)
            when {
                src.isDirectory -> if (!dst.exists() && !dst.mkdirs()) throw IOException("mkdir $rel")
                rel == "graph${File.separator}Gr.fst" -> Unit
                else -> linkOrCopy(src, dst)
            }
        }
        val graph = File(staging, "graph")
        val count = File(full, "graph/Gr.fst").inputStream().use { input ->
            File(graph, "words.txt").outputStream().buffered(1 shl 16).use { out -> writeWordsTxt(input, out, grammarWords) }
        }
        File(graph, "Gr.fst").writeBytes(minimalVectorFst())
        File(staging, MARKER).writeText("words=$count\n$keepLine\n", Charsets.UTF_8)
        lite.deleteRecursively()
        if (!staging.renameTo(lite)) throw IOException("Cannot finalize lite graph directory")
        Log.i(TAG, "Lite wake-word graph prepared: words=$count, dir=${lite.absolutePath}")
        return lite
    }

    /** Hard link, else symbolic link (app data usually refuses hard links on Android), else a copy. */
    private fun linkOrCopy(src: File, dst: File) {
        try {
            Files.createLink(dst.toPath(), src.toPath()); return
        } catch (_: Exception) { }
        try {
            Files.createSymbolicLink(dst.toPath(), src.absoluteFile.toPath()); return
        } catch (_: Exception) { }
        src.copyTo(dst, overwrite = true)
    }

    /**
     * Writes Gr.fst's output symbols as Kaldi words.txt ("symbol key" per line) from raw bytes; returns the line count.
     * With [keepWords], only those words and the structural symbols are written, and every keep word must exist.
     */
    internal fun writeWordsTxt(input: InputStream, out: OutputStream, keepWords: List<String>? = null): Int {
        var count = 0
        val digits = ByteArray(20)
        val keep = keepWords?.map { it.toByteArray(Charsets.UTF_8) }
        val found = BooleanArray(keep?.size ?: 0)
        forEachOutputSymbol(input) { sym, len, key ->
            if (keep != null) {
                val i = keep.indexOfFirst { k -> k.size == len && (0 until len).all { sym[it] == k[it] } }
                if (i >= 0) found[i] = true else if (!isStructural(sym, len)) return@forEachOutputSymbol
            }
            out.write(sym, 0, len); out.write(' '.code)
            var k = key; var n = 0
            do { digits[n++] = ('0'.code + (k % 10).toInt()).toByte(); k /= 10 } while (k > 0)
            while (n > 0) out.write(digits[--n].toInt())
            out.write('\n'.code); count++
        }
        keepWords?.forEachIndexed { i, w -> if (!found[i]) throw IOException("Grammar word missing from the model vocabulary: $w") }
        return count
    }

    private val STRUCTURAL = listOf("<eps>", "<s>", "</s>").map { it.toByteArray(Charsets.US_ASCII) }

    /** <eps>, <s>, </s> and disambiguation symbols (#0, #1, ...). */
    private fun isStructural(sym: ByteArray, len: Int): Boolean {
        if (len >= 2 && sym[0] == '#'.code.toByte() && (1 until len).all { sym[it] in '0'.code.toByte()..'9'.code.toByte() }) return true
        return STRUCTURAL.any { s -> s.size == len && (0 until len).all { sym[it] == s[it] } }
    }

    /** Output symbols as (symbol, key) pairs (tests). */
    internal fun readOutputSymbols(input: InputStream): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        forEachOutputSymbol(input) { sym, len, key -> out.add(String(sym, 0, len, Charsets.UTF_8) to key) }
        return out
    }

    /** OpenFst binary header (FstHeader::Read) followed by the input and output SymbolTables when flagged. */
    private inline fun forEachOutputSymbol(input: InputStream, visit: (ByteArray, Int, Long) -> Unit) {
        val r = LittleEndianReader(input)
        if (r.int() != FST_MAGIC) throw IOException("Gr.fst: not an OpenFst file")
        r.bytes(); r.bytes(); r.int()               // fst type, arc type, version
        val flags = r.int()
        r.long(); r.long(); r.long(); r.long()      // properties, start, numstates, numarcs
        if (flags and 1 != 0) readSymbolTable(r) { _, _, _ -> }
        if (flags and 2 == 0) throw IOException("Gr.fst has no output symbol table")
        readSymbolTable(r, visit)
    }

    private inline fun readSymbolTable(r: LittleEndianReader, visit: (ByteArray, Int, Long) -> Unit) {
        if (r.int() != SYMBOL_TABLE_MAGIC) throw IOException("Bad symbol table magic")
        r.bytes(); r.long()                          // name, available key
        val size = r.long()
        if (size < 0 || size > Int.MAX_VALUE) throw IOException("Bad symbol table size $size")
        for (i in 0 until size) {
            val len = r.bytes(); val key = r.long()
            visit(r.scratch, len, key)
        }
    }

    /** OpenFst VectorFst v2 (std arcs): start 0, one final state (weight 0), no arcs, no symbols. */
    internal fun minimalVectorFst(): ByteArray {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) { val bytes = s.toByteArray(Charsets.UTF_8); b.putInt(bytes.size); b.put(bytes) }
        b.putInt(FST_MAGIC); str("vector"); str("standard")
        b.putInt(2); b.putInt(0); b.putLong(0L)       // version, flags, properties (unknown)
        b.putLong(0L); b.putLong(1L); b.putLong(0L)   // start, numstates, numarcs
        b.putFloat(0f); b.putLong(0L)                 // state 0: final weight 0, 0 arcs
        return b.array().copyOf(b.position())
    }

    private class LittleEndianReader(input: InputStream) {
        private val d = DataInputStream(if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16))
        private val buf = ByteArray(8)
        private val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var scratch = ByteArray(256)
            private set
        fun int(): Int { d.readFully(buf, 0, 4); return bb.getInt(0) }
        fun long(): Long { d.readFully(buf, 0, 8); return bb.getLong(0) }
        /** Reads a length-prefixed string into [scratch]; returns its byte length. */
        fun bytes(): Int {
            val n = int(); if (n < 0 || n > 1 shl 20) throw IOException("Bad string length $n")
            if (n > scratch.size) scratch = ByteArray(maxOf(n, scratch.size * 2))
            d.readFully(scratch, 0, n); return n
        }
    }
}
