package dev.semanticcodesearch.index

import com.intellij.openapi.vfs.VirtualFile

/**
 * Splits a source file into [CodeChunk]s.
 *
 * Why a seam (and not a PSI walk): in Rider the C# AST lives in the ReSharper *backend* and
 * is NOT reachable from a pure-Kotlin frontend plugin — so the issue's "walk the PSI" plan
 * can't see C# methods from here. The default frontend-only impl ([CSharpRegexChunker])
 * recovers method/class boundaries from text. A tree-sitter or backend-PSI chunker can be
 * dropped in later without touching the rest of the pipeline.
 */
interface Chunker {
    /** True if this chunker handles the given file (by extension, etc.). */
    fun supports(file: VirtualFile): Boolean

    /** [text] is the file content; returns chunks with 0-based, inclusive line ranges. */
    fun chunk(file: VirtualFile, text: CharSequence): List<CodeChunk>
}
