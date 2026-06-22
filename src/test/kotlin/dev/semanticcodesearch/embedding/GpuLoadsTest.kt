package dev.semanticcodesearch.embedding

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Verifies the CUDA pre-load fix: the embedder must come up on the GPU when a CUDA toolkit is present.
 * The real test is running this with cuDNN/CUDA stripped from PATH — if it still reports GPU, the
 * absolute-path pre-load (CudaNativeLoader) is doing its job and we no longer depend on PATH.
 * Self-skips when the model or a CUDA toolkit isn't present.
 */
class GpuLoadsTest {

    private val gteDir = Paths.get(System.getProperty("user.home"), ".semantic-code-search", "models", "gte-modernbert-base")

    @Test
    fun embedderLoadsOnGpuWhenCudaPresent() {
        assumeTrue("gte model not present — skipping", Files.exists(gteDir.resolve("model.onnx")))
        assumeTrue("no CUDA toolkit on this machine — skipping", CudaNativeLoader.cudaAvailable())

        OnnxEmbedder(
            EmbedderModels.resolveOnnxFile(gteDir),
            gteDir.resolve("tokenizer.json"),
            cudaDir = CudaNativeLoader.detectCudaDir(),
            cudnnDir = CudaNativeLoader.detectCudnnDir(),
        ).use { emb ->
            assertTrue("expected GPU (CUDA) but got CPU — pre-load may have failed", emb.usingGpu)
        }
    }
}
