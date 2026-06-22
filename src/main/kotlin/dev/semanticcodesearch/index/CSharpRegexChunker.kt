package dev.semanticcodesearch.index

import com.intellij.openapi.vfs.VirtualFile

/**
 * Frontend-only, heuristic C# chunker. Recovers type + method boundaries from text by
 * brace-matching (no ReSharper backend, no PSI). Tuned for Unity's Allman style (opening
 * brace on its own line), which is what Rider/Unity default to.
 *
 * Known limitations (acceptable for v1 — swap in tree-sitter later if needed):
 *   - Braces inside strings / chars / comments are counted as real braces.
 *   - Same-line-brace methods (`void Foo() {`) are missed; Allman `void Foo()\n{` works.
 *   - Expression-bodied members (`int X => 5;`) are not chunked.
 */
class CSharpRegexChunker(
    private val minLines: Int = 1,
    private val maxChunkChars: Int = 8_000,
) : Chunker {

    override fun supports(file: VirtualFile): Boolean =
        file.extension?.lowercase() == "cs"

    private val typeDecl =
        Regex("""\b(class|struct|interface|enum|record)\s+([A-Za-z_]\w*)""")

    // A method/ctor header line ending right after the parameter list (Allman: brace next line).
    private val methodDecl =
        Regex("""^\s*(?:\[[^\]]*]\s*)*[A-Za-z_][\w<>\[\].,?\s]*\b([A-Za-z_]\w*)\s*\([^;{}]*\)\s*(?:where[^{};]*)?$""")

    private data class Pending(val name: String, val isType: Boolean, val declLine: Int)

    override fun chunk(file: VirtualFile, text: CharSequence): List<CodeChunk> = chunkText(file.path, text)

    /** Path-based core, so it can be unit-tested without a VirtualFile. */
    fun chunkText(filePath: String, text: CharSequence): List<CodeChunk> {
        val lines = text.split('\n')
        val chunks = ArrayList<CodeChunk>()
        val typeStack = ArrayDeque<String>()
        val openStack = ArrayDeque<Pending>()   // one entry per open '{'
        var pending: Pending? = null

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (!trimmed.startsWith("//")) {
                val t = typeDecl.find(trimmed)
                when {
                    t != null -> pending = Pending(t.groupValues[2], isType = true, declLine = i)
                    pending == null -> {
                        val m = methodDecl.find(raw)
                        if (m != null) pending = Pending(m.groupValues[1], isType = false, declLine = i)
                    }
                }
            }

            for (c in raw) {
                when (c) {
                    '{' -> {
                        val p = pending ?: Pending("", isType = false, declLine = i)
                        openStack.addLast(p)
                        if (p.isType && p.name.isNotEmpty()) typeStack.addLast(p.name)
                        pending = null
                    }
                    '}' -> {
                        val opened = openStack.removeLastOrNull() ?: continue
                        if (opened.name.isEmpty()) continue
                        if (opened.isType) {
                            typeStack.removeLastOrNull()
                        } else {
                            val start = opened.declLine
                            val end = i
                            if (end - start + 1 >= minLines) {
                                val enclosing = typeStack.lastOrNull()
                                val symbol = if (enclosing != null) "$enclosing.${opened.name}" else opened.name
                                val code = lines.subList(start, (end + 1).coerceAtMost(lines.size))
                                    .joinToString("\n").take(maxChunkChars)
                                chunks.add(CodeChunk(filePath, start, end, symbol, code))
                            }
                        }
                    }
                }
            }
        }
        return chunks
    }
}
