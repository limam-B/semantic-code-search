package dev.semanticcodesearch.embedding

import java.nio.file.Files
import java.nio.file.Path

/**
 * The embedding model. gte-modernbert-base, CLS pooling (verified from its sentence-transformers
 * 1_Pooling/config.json). Behind the [Embedder] seam so the backend stays swappable.
 */
object EmbedderModels {

    fun gteModernBertBase(dir: Path): Embedder = OnnxEmbedder(
        modelPath = resolveOnnxFile(dir),
        tokenizerPath = dir.resolve("tokenizer.json"),
        dimension = 768,
        pooling = PoolingMode.CLS,
    )

    /** Prefers model.onnx, else the first *.onnx in the dir. Shared with the reranker. */
    fun resolveOnnxFile(dir: Path): Path {
        val preferred = dir.resolve("model.onnx")
        if (Files.exists(preferred)) return preferred
        Files.list(dir).use { stream ->
            return stream.filter { it.fileName.toString().endsWith(".onnx") }.findFirst()
                .orElseThrow { IllegalStateException("No .onnx file found in $dir — see README.") }
        }
    }
}
