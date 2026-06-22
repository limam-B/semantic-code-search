package dev.semanticcodesearch.index

import dev.semanticcodesearch.embedding.Embedder
import dev.semanticcodesearch.embedding.EmbedderModels
import dev.semanticcodesearch.embedding.OnnxReranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * End-to-end proof on a small synthetic C# "project": chunk -> embed -> dense + BM25 -> RRF -> rerank,
 * then assert a natural-language query lands on the exact method. This is the whole tool, headless,
 * on the real models. Self-skips if the models aren't present.
 */
class EndToEndPipelineTest {

    private val modelsRoot = Paths.get(System.getProperty("user.home"), ".semantic-code-search", "models")
    private val gteDir = modelsRoot.resolve("gte-modernbert-base")
    private val rerankDir = modelsRoot.resolve("gte-reranker-modernbert-base")

    private val sampleProject = mapOf(
        "/proj/RagdollHand.cs" to """
            namespace Game
            {
                public class RagdollHand
                {
                    public void SetHandRotation(Quaternion target)
                    {
                        _handBone.rotation = target;
                    }

                    public void PrepareGrab()
                    {
                        _grabReady = true;
                    }
                }
            }
        """.trimIndent(),
        "/proj/PlayerMovement.cs" to """
            namespace Game
            {
                public class PlayerMovement
                {
                    public void Jump()
                    {
                        _velocity.y = _jumpForce;
                    }

                    public void Move(Vector2 direction)
                    {
                        _velocity += direction * _speed;
                    }
                }
            }
        """.trimIndent(),
        "/proj/Weapon.cs" to """
            namespace Game
            {
                public class Weapon
                {
                    public void Fire()
                    {
                        SpawnProjectile(_muzzle);
                    }

                    public void Reload()
                    {
                        _ammo = _magazineSize;
                    }
                }
            }
        """.trimIndent(),
    )

    @Test
    fun naturalLanguageQueryLandsOnTheExactMethod() {
        assumeTrue("gte model not present — skipping", Files.exists(gteDir.resolve("model.onnx")))

        val chunker = CSharpRegexChunker()
        val chunks = sampleProject.flatMap { (path, src) -> chunker.chunkText(path, src) }
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 5)

        val store = VectorStore(768)
        val lexical = LexicalIndex()

        (EmbedderModels.gteModernBertBase(gteDir) as AutoCloseable).use { e ->
            val emb = e as Embedder
            val vecs = emb.embed(chunks.map { it.code })
            chunks.forEachIndexed { i, c -> store.add(c, vecs[i]) }
            lexical.build(chunks)

            val query = "where is the hand rotation being set"
            val qv = emb.embed(listOf(query)).first()
            val fused = Rrf.fuse(listOf(store.topK(qv, 10), lexical.search(query, 10))).take(10)

            val ranked: List<CodeChunk> = if (Files.exists(rerankDir.resolve("model.onnx"))) {
                OnnxReranker(EmbedderModels.resolveOnnxFile(rerankDir), rerankDir.resolve("tokenizer.json")).use { rr ->
                    val scores = rr.scores(query, fused.map { it.chunk.code })
                    fused.indices.sortedByDescending { scores[it] }.map { fused[it].chunk }
                }
            } else {
                fused.map { it.chunk }
            }

            assertEquals(
                "top hit should be SetHandRotation, full ranking: ${ranked.map { it.symbolName }}",
                "RagdollHand.SetHandRotation",
                ranked.first().symbolName,
            )
        }
    }
}
