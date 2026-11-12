package com.hyundai.rentalhelper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RendyWakeWordLiteGraphTest {

    private class Writer {
        val b: ByteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun int(v: Int) = apply { b.putInt(v) }
        fun long(v: Long) = apply { b.putLong(v) }
        fun str(s: String) = apply { val x = s.toByteArray(Charsets.UTF_8); b.putInt(x.size); b.put(x) }
        fun symbols(name: String, entries: List<Pair<String, Long>>) = apply {
            int(2125658996); str(name); long(entries.size.toLong()); long(entries.size.toLong())
            entries.forEach { (s, k) -> str(s); long(k) }
        }
        fun bytes(): ByteArray = b.array().copyOf(b.position())
    }

    private fun header(w: Writer, flags: Int) =
        w.int(2125659606).str("ngram").str("standard").int(1).int(flags).long(0).long(0).long(1).long(0)

    @Test
    fun readsOutputSymbolsAfterInputSymbols() {
        val osyms = listOf("<eps>" to 0L, "렌디" to 9516L, "<UNK>" to 253415L)
        val w = Writer()
        header(w, flags = 3).symbols("isyms", listOf("<eps>" to 0L, "x" to 1L)).symbols("osyms", osyms)
        assertEquals(osyms, RendyWakeWordLiteGraph.readOutputSymbols(ByteArrayInputStream(w.bytes())))
    }

    @Test
    fun readsOutputSymbolsWithoutInputSymbols() {
        val osyms = listOf("<eps>" to 0L, "렌디" to 1L)
        val w = Writer()
        header(w, flags = 2).symbols("osyms", osyms)
        assertEquals(osyms, RendyWakeWordLiteGraph.readOutputSymbols(ByteArrayInputStream(w.bytes())))
    }

    @Test
    fun writesKaldiWordsTxtFromOutputSymbols() {
        val osyms = listOf("<eps>" to 0L, "렌디" to 9516L, "<UNK>" to 253415L)
        val w = Writer()
        header(w, flags = 3).symbols("isyms", listOf("<eps>" to 0L)).symbols("osyms", osyms)
        val out = java.io.ByteArrayOutputStream()
        assertEquals(3, RendyWakeWordLiteGraph.writeWordsTxt(ByteArrayInputStream(w.bytes()), out))
        assertEquals("<eps> 0\n렌디 9516\n<UNK> 253415\n", out.toString("UTF-8"))
    }

    @Test
    fun keepsOnlyGrammarAndStructuralSymbolsWithOriginalIds() {
        val osyms = listOf("<eps>" to 0L, "가" to 1L, "<UNK>" to 253415L, "렌디" to 9516L, "렌디뒤" to 9517L,
            "#0" to 503349L, "#가" to 7L, "<s>" to 503351L, "</s>" to 503351L)
        val w = Writer()
        header(w, flags = 2).symbols("osyms", osyms)
        val out = java.io.ByteArrayOutputStream()
        val n = RendyWakeWordLiteGraph.writeWordsTxt(ByteArrayInputStream(w.bytes()), out, RendyWakeWordController.GRAMMAR_WORDS)
        assertEquals("<eps> 0\n<UNK> 253415\n렌디 9516\n#0 503349\n<s> 503351\n</s> 503351\n", out.toString("UTF-8"))
        assertEquals(6, n)
    }

    @Test(expected = IOException::class)
    fun failsWhenAGrammarWordIsMissingFromTheVocabulary() {
        val w = Writer()
        header(w, flags = 2).symbols("osyms", listOf("<eps>" to 0L, "<UNK>" to 1L))
        RendyWakeWordLiteGraph.writeWordsTxt(ByteArrayInputStream(w.bytes()), java.io.ByteArrayOutputStream(), listOf("렌디", "<UNK>"))
    }

    @Test
    fun grammarStringMatchesGrammarWords() {
        assertEquals("[\"렌디\", \"<UNK>\"]", RendyWakeWordController.KEYWORD_GRAMMAR)
    }

    @Test(expected = IOException::class)
    fun rejectsFstWithoutOutputSymbols() {
        RendyWakeWordLiteGraph.readOutputSymbols(ByteArrayInputStream(header(Writer(), flags = 0).bytes()))
    }

    @Test(expected = IOException::class)
    fun rejectsNonFstFile() {
        RendyWakeWordLiteGraph.readOutputSymbols(ByteArrayInputStream(ByteArray(64)))
    }

    @Test
    fun minimalFstIsOneFinalStateVectorFst() {
        val b = ByteBuffer.wrap(RendyWakeWordLiteGraph.minimalVectorFst()).order(ByteOrder.LITTLE_ENDIAN)
        fun str(): String { val n = b.int; val x = ByteArray(n); b.get(x); return String(x, Charsets.UTF_8) }
        assertEquals(2125659606, b.int)
        assertEquals("vector", str()); assertEquals("standard", str())
        assertEquals(2, b.int)          // version
        assertEquals(0, b.int)          // flags: no symbol tables
        assertEquals(0L, b.long)        // properties
        assertEquals(0L, b.long)        // start
        assertEquals(1L, b.long)        // numstates
        assertEquals(0L, b.long)        // numarcs
        assertEquals(0f, b.float)       // final weight (tropical one)
        assertEquals(0L, b.long)        // arcs of state 0
        assertTrue(!b.hasRemaining())
    }
}
