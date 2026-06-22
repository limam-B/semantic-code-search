package dev.semanticcodesearch.embedding

/**
 * Known models for the Settings dropdowns. Picking an embedder fixes its pooling + dimension, so the
 * user never sets those by hand. To add a model, add an entry here.
 */
object ModelCatalog {

    data class EmbedderInfo(val key: String, val pooling: PoolingMode, val dimension: Int)

    val embedders: List<EmbedderInfo> = listOf(
        EmbedderInfo("gte-modernbert-base", PoolingMode.CLS, 768),
        EmbedderInfo("jina-embeddings-v2-base-code", PoolingMode.MEAN, 768),
    )

    val rerankers: List<String> = listOf("gte-reranker-modernbert-base")

    /** Look up an embedder by key, falling back to the first entry for an unknown/blank key. */
    fun embedder(key: String): EmbedderInfo = embedders.firstOrNull { it.key == key } ?: embedders.first()
}
