package dev.semanticcodesearch.embedding

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Integration eval — the objective answer to "is it good?". Loads the REAL local ONNX models and
 * asserts that a relevant code snippet outranks an irrelevant one. Self-skips (Assume) when the
 * models aren't present (e.g. CI), so it never breaks a clean build.
 *
 * Run with: gradle test  (or the green run-gutter on a method).
 */
class OnnxRelevanceTest {

    private val modelsRoot = Paths.get(System.getProperty("user.home"), ".semantic-code-search", "models")

    private val query = "where is the hand rotation set"
    private val relevant = "public void SetHandRotation(Quaternion q) { _handBone.rotation = q; }"
    private val irrelevant = "public int Add(int a, int b) { return a + b; }"

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += (a[i] * b[i]).toDouble()
        return s
    }

    @Test
    fun embedderRanksRelevantCodeHigher() {
        val dir = modelsRoot.resolve("gte-modernbert-base")
        assumeTrue("gte-modernbert-base not present — skipping", Files.exists(dir.resolve("model.onnx")))

        (EmbedderModels.gteModernBertBase(dir) as AutoCloseable).use { e ->
            val emb = e as Embedder
            val v = emb.embed(listOf(query, relevant, irrelevant))
            val (q, rel, irr) = Triple(v[0], v[1], v[2])
            assertTrue("relevant should be closer than irrelevant", dot(q, rel) > dot(q, irr))
        }
    }

    @Test
    fun rerankerScoresRelevantAboveIrrelevant() {
        val dir = modelsRoot.resolve("gte-reranker-modernbert-base")
        assumeTrue("gte-reranker not present — skipping", Files.exists(dir.resolve("model.onnx")))

        OnnxReranker(EmbedderModels.resolveOnnxFile(dir), dir.resolve("tokenizer.json")).use { rr ->
            val scores = rr.scores(query, listOf(relevant, irrelevant))
            // This also validates the reranker's ONNX output-shape cast produces meaningful scores.
            assertTrue("reranker should score relevant higher (got ${scores.toList()})", scores[0] > scores[1])
        }
    }
}
