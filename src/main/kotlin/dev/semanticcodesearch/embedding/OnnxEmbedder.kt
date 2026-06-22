package dev.semanticcodesearch.embedding

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import com.intellij.openapi.diagnostic.logger
import kotlin.math.sqrt

enum class PoolingMode { MEAN, CLS, LAST_TOKEN }

/**
 * Generic in-process ONNX embedder. Pooling is configurable so one class serves multiple models
 * behind the [Embedder] seam:
 *   - jina-embeddings-v2-base-code -> MEAN
 *   - gte-modernbert-base          -> CLS
 *   - (future) Qwen-LLM encoders    -> LAST_TOKEN (+ instruction prefixes — not wired yet)
 *
 * Pipeline: tokenize -> ONNX forward -> pool -> L2 normalize. token_type_ids is supplied only if
 * the graph asks for it. Output[0] is taken as the last hidden state [1, seqLen, dim].
 */
class OnnxEmbedder(
    modelPath: Path,
    tokenizerPath: Path,
    override val dimension: Int = 768,
    private val pooling: PoolingMode = PoolingMode.MEAN,
    private val maxTokens: Int = 1024,
    cudaDir: Path? = null,
    cudnnDir: Path? = null,
) : Embedder, AutoCloseable {

    private val env: OrtEnvironment
    private val session: OrtSession
    private val tokenizer: HuggingFaceTokenizer
    private val needsTokenTypeIds: Boolean

    /** True if the ONNX session runs on the CUDA GPU provider; false = CPU fallback. */
    val usingGpu: Boolean

    init {
        // DJL locates its native tokenizer lib via the THREAD CONTEXT classloader. Inside IntelliJ
        // that's a platform classloader that can't see our bundled DJL jars, so it fails with
        // "No tokenizers version found in property file." Swap in the plugin classloader for init.
        val prev = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = javaClass.classLoader
        try {
            val e = OrtEnvironment.getEnvironment()
            val modelBytes = Files.readAllBytes(modelPath)

            // Try CUDA (the RTX GPU) first; fall back to CPU if the CUDA/cuDNN runtime isn't present.
            var gpu = false
            val s = try {
                CudaNativeLoader.ensureLoaded(cudaDir, cudnnDir)
                val opts = OrtSession.SessionOptions()
                opts.addCUDA(0)
                val sess = e.createSession(modelBytes, opts)
                gpu = true
                sess
            } catch (t: Throwable) {
                LOG.warn("CUDA execution provider unavailable — using CPU. Reason: ${t.message}")
                e.createSession(modelBytes, OrtSession.SessionOptions())
            }

            val tok = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optMaxLength(maxTokens)
                .build()

            env = e
            session = s
            tokenizer = tok
            needsTokenTypeIds = s.inputNames.contains("token_type_ids")
            usingGpu = gpu
            LOG.warn("OnnxEmbedder ready on ${if (gpu) "GPU (CUDA)" else "CPU"} — dim=$dimension, pooling=$pooling")
        } finally {
            Thread.currentThread().contextClassLoader = prev
        }
    }

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.map { embedOne(it) }
    }

    private fun embedOne(text: String): FloatArray {
        val enc = tokenizer.encode(text)
        val ids = enc.ids                 // LongArray
        val mask = enc.attentionMask      // LongArray
        val seqLen = ids.size
        val shape = longArrayOf(1, seqLen.toLong())

        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        if (needsTokenTypeIds) {
            inputs["token_type_ids"] =
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seqLen)), shape)
        }

        val result = session.run(inputs)
        try {
            @Suppress("UNCHECKED_CAST")
            val hidden = (result[0].value as Array<Array<FloatArray>>)[0]   // [seqLen, dim]
            val pooled = pool(hidden, mask)
            l2NormalizeInPlace(pooled)
            return pooled
        } finally {
            result.close()
            inputs.values.forEach { it.close() }
        }
    }

    private fun pool(hidden: Array<FloatArray>, mask: LongArray): FloatArray = when (pooling) {
        PoolingMode.CLS -> hidden[0].copyOf()
        PoolingMode.LAST_TOKEN -> {
            var last = 0
            for (t in mask.indices) if (mask[t] != 0L) last = t
            hidden[last].copyOf()
        }
        PoolingMode.MEAN -> {
            val dim = hidden[0].size
            val acc = FloatArray(dim)
            var count = 0f
            for (t in hidden.indices) {
                if (mask[t] == 0L) continue
                val row = hidden[t]
                for (d in 0 until dim) acc[d] += row[d]
                count += 1f
            }
            if (count > 0f) for (d in 0 until dim) acc[d] /= count
            acc
        }
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = sqrt(s).toFloat()
        if (n > 0f) for (i in v.indices) v[i] /= n
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    companion object {
        private val LOG = logger<OnnxEmbedder>()
    }
}
