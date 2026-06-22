package dev.semanticcodesearch.index

/**
 * Reciprocal Rank Fusion: merges several ranked hit lists into one, by chunk identity (file + line
 * range). Rank-based, so it fuses dense (cosine) and lexical (BM25) scores that aren't comparable in
 * magnitude. The fused score is Σ 1/(K + rank) across lists.
 */
object Rrf {
    private const val K = 60.0

    fun fuse(rankings: List<List<VectorStore.Hit>>): List<VectorStore.Hit> {
        val score = HashMap<String, Double>()
        val chunkOf = HashMap<String, CodeChunk>()
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, hit ->
                val key = keyOf(hit.chunk)
                score[key] = (score[key] ?: 0.0) + 1.0 / (K + rank + 1)
                // Prefer a chunk instance that actually carries code (dense hits loaded from a cached
                // index have empty bodies; lexical hits always carry code) — the reranker needs it.
                val existing = chunkOf[key]
                if (existing == null || existing.code.isEmpty()) chunkOf[key] = hit.chunk
            }
        }
        return score.entries.sortedByDescending { it.value }
            .map { VectorStore.Hit(chunkOf.getValue(it.key), it.value.toFloat()) }
    }

    private fun keyOf(c: CodeChunk) = "${c.filePath}#${c.startLine}-${c.endLine}"
}
