package dev.semanticcodesearch.index

import kotlin.math.ln

/**
 * In-memory BM25 lexical index over the chunk corpus (symbol name + code). Tokenizes identifiers and
 * splits camelCase/PascalCase so a query like "hand rotation" matches the symbol `SetHandRotation`.
 *
 * Model-independent: built once per rebuild and reused across embedder A/B swaps (the chunks are the
 * same regardless of which embedding model is active). Uses an inverted index so a query only scans
 * the docs that actually contain its terms.
 */
class LexicalIndex {
    private var chunks: List<CodeChunk> = emptyList()
    private var tf: List<Map<String, Int>> = emptyList()
    private var inverted: Map<String, MutableList<Int>> = emptyMap()
    private var docLen: IntArray = IntArray(0)
    private var avgdl: Double = 0.0

    val size: Int get() = chunks.size

    fun clear() {
        chunks = emptyList(); tf = emptyList(); inverted = emptyMap()
        docLen = IntArray(0); avgdl = 0.0
    }

    fun build(corpus: List<CodeChunk>) {
        chunks = corpus
        val tfs = ArrayList<Map<String, Int>>(corpus.size)
        val inv = HashMap<String, MutableList<Int>>()
        val lens = IntArray(corpus.size)
        corpus.forEachIndexed { i, c ->
            val toks = tokenize(c.symbolName + " " + c.code)
            val m = HashMap<String, Int>()
            for (t in toks) m[t] = (m[t] ?: 0) + 1
            tfs.add(m)
            lens[i] = toks.size
            for (t in m.keys) inv.getOrPut(t) { ArrayList() }.add(i)
        }
        tf = tfs; inverted = inv; docLen = lens
        avgdl = if (corpus.isEmpty()) 0.0 else lens.sum().toDouble() / corpus.size
    }

    fun search(query: String, k: Int): List<VectorStore.Hit> {
        if (chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val scores = HashMap<Int, Double>()
        for (t in tokenize(query).toSet()) {
            val docs = inverted[t] ?: continue
            val idf = ln(1.0 + (n - docs.size + 0.5) / (docs.size + 0.5))
            for (i in docs) {
                val f = tf[i][t] ?: continue
                val denom = f + K1 * (1 - B + B * docLen[i] / avgdl)
                scores[i] = (scores[i] ?: 0.0) + idf * (f * (K1 + 1)) / denom
            }
        }
        return scores.entries.sortedByDescending { it.value }
            .take(k)
            .map { VectorStore.Hit(chunks[it.key], it.value.toFloat()) }
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        for (raw in s.split(NON_WORD)) {
            if (raw.isEmpty()) continue
            out.add(raw.lowercase())          // whole identifier
            splitCamel(raw, out)              // + camel/digit subwords
        }
        return out
    }

    private fun splitCamel(token: String, out: MutableList<String>) {
        var start = 0
        for (i in 1 until token.length) {
            val prev = token[i - 1]
            val c = token[i]
            val boundary = (c.isUpperCase() && prev.isLowerCase()) || (c.isDigit() != prev.isDigit())
            if (boundary) {
                if (i - start > 1) out.add(token.substring(start, i).lowercase())
                start = i
            }
        }
        if (start > 0 && token.length - start > 1) out.add(token.substring(start).lowercase())
    }

    companion object {
        private const val K1 = 1.5
        private const val B = 0.75
        private val NON_WORD = Regex("[^A-Za-z0-9]+")
    }
}
