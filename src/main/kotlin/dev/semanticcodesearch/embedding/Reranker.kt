package dev.semanticcodesearch.embedding

/**
 * Cross-encoder reranker seam. Scores (query, passage) PAIRS jointly — far more precise than the
 * bi-encoder, but run only over the handful of first-stage candidates. Swappable like [Embedder].
 */
interface Reranker : AutoCloseable {
    /** Relevance score per passage for the query (higher = more relevant). */
    fun scores(query: String, passages: List<String>): FloatArray
}
