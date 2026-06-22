package dev.semanticcodesearch.embedding

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.intellij.openapi.diagnostic.logger
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-encoder reranker (e.g. gte-reranker-modernbert-base). For each candidate it tokenizes the
 * (query, passage) PAIR, runs one ONNX forward, and reads the single relevance logit. Same native-load
 * + CUDA-with-CPU-fallback dance as [OnnxEmbedder].
 */
class OnnxReranker(
    modelPath: Path,
    tokenizerPath: Path,
    private val maxTokens: Int = 1024,
) : Reranker {

    private val env: OrtEnvironment
    private val session: OrtSession
    private val tokenizer: HuggingFaceTokenizer
    private val needsTokenTypeIds: Boolean
    val usingGpu: Boolean

    init {
        val prev = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = javaClass.classLoader
        try {
            val e = OrtEnvironment.getEnvironment()
            val bytes = Files.readAllBytes(modelPath)
            var gpu = false
            val s = try {
                CudaNativeLoader.ensureLoaded()
                val opts = OrtSession.SessionOptions()
                opts.addCUDA(0)
                val sess = e.createSession(bytes, opts)
                gpu = true
                sess
            } catch (t: Throwable) {
                LOG.warn("Reranker CUDA unavailable — using CPU. Reason: ${t.message}")
                e.createSession(bytes, OrtSession.SessionOptions())
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
            LOG.warn("OnnxReranker ready on ${if (gpu) "GPU (CUDA)" else "CPU"}")
        } finally {
            Thread.currentThread().contextClassLoader = prev
        }
    }

    override fun scores(query: String, passages: List<String>): FloatArray {
        if (passages.isEmpty()) return FloatArray(0)
        return FloatArray(passages.size) { scoreOne(query, passages[it]) }
    }

    private fun scoreOne(query: String, passage: String): Float {
        val enc = tokenizer.encode(query, passage)   // sentence-pair encoding
        val ids = enc.ids
        val mask = enc.attentionMask
        val shape = longArrayOf(1, ids.size.toLong())

        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        if (needsTokenTypeIds) {
            inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
        }

        val result = session.run(inputs)
        try {
            // Classification head with 1 label: logits shape [1, 1] (float[][]) or [1] (float[]).
            return when (val out = result[0].value) {
                is Array<*> -> (out[0] as FloatArray)[0]
                is FloatArray -> out[0]
                else -> 0f
            }
        } finally {
            result.close()
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    companion object {
        private val LOG = logger<OnnxReranker>()
    }
}
