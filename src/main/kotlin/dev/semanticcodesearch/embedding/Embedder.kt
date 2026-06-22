package dev.semanticcodesearch.embedding

/**
 * Swappable embedding backend. v1 = Jina Code v2 via in-process ONNX (see [JinaCodeEmbedder]).
 *
 * Keeping this a one-method seam means the model or runtime can change (Ollama, a different
 * code embedder, a reranker stage) without touching indexing, the vector store, or the
 * Search Everywhere surface.
 */
interface Embedder {
    /** One L2-normalized vector per input text. All vectors share [dimension]. */
    fun embed(texts: List<String>): List<FloatArray>

    val dimension: Int
}
