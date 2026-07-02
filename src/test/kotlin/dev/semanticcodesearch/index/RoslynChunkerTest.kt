package dev.semanticcodesearch.index

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Integration test for the Roslyn sidecar chunker. Self-skips (via [assumeTrue]) when the sidecar
 * isn't built — same convention as the real-model tests — so a clean/CI checkout stays green.
 * Build it first with: `dotnet build -c Release sidecar/`.
 */
class RoslynChunkerTest {

    private val sidecarDll =
        Paths.get(System.getProperty("user.dir"), "sidecar", "bin", "Release", "net8.0", "roslyn-chunker.dll")

    private fun chunk(src: String): List<CodeChunk> {
        assumeTrue("Roslyn sidecar not built — run: dotnet build -c Release sidecar/", Files.exists(sidecarDll))
        return RoslynChunker { RoslynChunker.SidecarLaunch("dotnet", sidecarDll, sidecarDll.parent) }
            .use { it.chunkText("/proj/Sample.cs", src) }
    }

    @Test
    fun capturesEnumExpressionBodiedDelegateAndDocs() {
        val src = """
            namespace Game
            {
                /// <summary>Discrete motion states.</summary>
                public enum MotionState { Ground, Air }

                public class Hand
                {
                    /// <summary>Rotates the hand bone to match tracker input.</summary>
                    public void SetHandRotation(Quaternion q) { _bone.rotation = q; }

                    /// <summary>Current dwell time.</summary>
                    public float Dwell => _timer;

                    public bool TryThing<TContext>(TContext ctx) where TContext : struct { return true; }
                }

                public delegate bool GuardDelegate<TContext>(in TContext ctx) where TContext : struct;
            }
        """.trimIndent()

        val names = chunk(src).map { it.symbolName }

        // All of these were invisible (or mis-named) to the old regex chunker.
        assertTrue("enum chunked: $names", names.contains("MotionState"))
        assertTrue("delegate chunked: $names", names.contains("GuardDelegate"))
        assertTrue("expression-bodied property chunked: $names", names.contains("Hand.Dwell"))
        // Generic method named correctly — the regex chunker produced "Hand.TContext".
        assertTrue("generic method named correctly: $names", names.contains("Hand.TryThing"))
    }

    @Test
    fun foldsXmlDocIntoEmbeddedCode() {
        val src = """
            namespace Game
            {
                public class Hand
                {
                    /// <summary>Rotates the hand bone to match tracker input.</summary>
                    public void SetHandRotation(Quaternion q) { _bone.rotation = q; }
                }
            }
        """.trimIndent()

        val rot = chunk(src).first { it.symbolName == "Hand.SetHandRotation" }
        assertTrue("xml doc text should be in the embedded code: ${rot.code}",
            rot.code.contains("Rotates the hand bone to match tracker input"))
    }
}
