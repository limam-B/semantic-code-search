package dev.semanticcodesearch.index

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * C# chunker backed by an out-of-process Roslyn sidecar (see `sidecar/`). The plugin is a pure
 * frontend and can't reach Rider's ReSharper-backend AST, so we run our own Roslyn parse in a
 * long-lived `dotnet` process and talk to it over a Content-Length-framed JSON protocol (LSP-style).
 *
 * Roslyn gives real C# semantics the old regex chunker couldn't: enums, expression-bodied members,
 * delegates, correct generic-method names, and — the point — the `///` XML docs folded into the
 * embedded text. There is **no regex fallback**: if the sidecar can't run, indexing is blocked with
 * guidance (see [CodeSearchIndexService]'s pre-index gate), never silently degraded.
 *
 * [launchProvider] returns how to start the sidecar (dotnet exe + dll), or null when unavailable.
 * Access is serialized by [lock] — one request/response on the pipe at a time.
 */
class RoslynChunker(private val launchProvider: () -> SidecarLaunch?) : Chunker, AutoCloseable {

    data class SidecarLaunch(val dotnet: String, val dll: Path, val workDir: Path?)

    /** Result of [probe]: ok, or a human-readable reason the sidecar couldn't start. */
    data class Probe(val ok: Boolean, val message: String?)

    private val log = logger<RoslynChunker>()
    private val gson = Gson()
    private val lock = Any()

    private var proc: Process? = null
    private var toChild: OutputStream? = null
    private var fromChild: InputStream? = null
    private var nextId = 1

    override fun supports(file: VirtualFile): Boolean = file.extension?.lowercase() == "cs"

    override fun chunk(file: VirtualFile, text: CharSequence): List<CodeChunk> =
        chunkText(file.path, text.toString())

    /** Path-based core (testable without a VirtualFile). Throws if the sidecar is unreachable. */
    fun chunkText(filePath: String, text: String): List<CodeChunk> = synchronized(lock) {
        val req = JsonObject().apply {
            addProperty("id", nextId++)
            addProperty("method", "chunk")
            addProperty("path", filePath)
            addProperty("text", text)
        }
        val resp = roundTripWithRestart(req)
        val chunksEl = resp.getAsJsonArray("chunks") ?: return emptyList()
        chunksEl.map { el ->
            val o = el.asJsonObject
            CodeChunk(
                filePath = filePath,
                startLine = o.get("startLine").asInt,
                endLine = o.get("endLine").asInt,
                symbolName = o.get("symbol").asString,
                code = o.get("code").asString,
            )
        }
    }

    /** Start (if needed) and handshake. Used by the pre-index gate so failures surface before work. */
    fun probe(): Probe = synchronized(lock) {
        try {
            ensureStarted()
            Probe(true, null)
        } catch (e: Exception) {
            Probe(false, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun close() = synchronized(lock) { stop() }

    // ---- process lifecycle ----

    private fun ensureStarted() {
        if (proc?.isAlive == true) return
        stop()
        val launch = launchProvider() ?: throw IOException("Roslyn sidecar is not available")
        log.info("starting Roslyn sidecar: ${launch.dotnet} ${launch.dll}")
        val p = ProcessBuilder(launch.dotnet, launch.dll.toString())
            .directory(launch.workDir?.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD) // sidecar logs to stderr; not needed here
            .start()
        proc = p
        toChild = BufferedOutputStream(p.outputStream)
        fromChild = BufferedInputStream(p.inputStream)

        val hs = JsonObject().apply {
            addProperty("id", nextId++)
            addProperty("method", "handshake")
        }
        val resp = roundTrip(hs)
        val ok = resp.get("ok")?.asBoolean == true
        val version = resp.get("version")?.asString
        if (!ok || version != PROTOCOL_VERSION) {
            stop()
            throw IOException("sidecar handshake failed (ok=$ok, version=$version, expected $PROTOCOL_VERSION)")
        }
    }

    private fun stop() {
        runCatching { toChild?.close() }
        runCatching { fromChild?.close() }
        proc?.let { p -> runCatching { if (p.isAlive) p.destroyForcibly() } }
        proc = null; toChild = null; fromChild = null
    }

    /** One request -> one response, restarting the process once on an I/O error (e.g. it crashed). */
    private fun roundTripWithRestart(req: JsonObject): JsonObject {
        ensureStarted()
        return try {
            roundTrip(req)
        } catch (e: IOException) {
            log.warn("sidecar I/O error, restarting once: ${e.message}")
            stop()
            ensureStarted()
            roundTrip(req)
        }
    }

    private fun roundTrip(req: JsonObject): JsonObject {
        writeMessage(toChild ?: throw IOException("sidecar stdin closed"), gson.toJson(req))
        val body = readMessage(fromChild ?: throw IOException("sidecar stdout closed"))
        return gson.fromJson(body, JsonObject::class.java)
    }

    // ---- Content-Length-framed JSON wire protocol (mirrors the sidecar's Program.cs) ----

    private fun writeMessage(out: OutputStream, json: String) {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
        out.flush()
    }

    private fun readMessage(inp: InputStream): String {
        var contentLength = -1
        // Header block: lines until a blank line.
        while (true) {
            val line = readHeaderLine(inp) ?: throw EOFException("sidecar closed the pipe")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) throw IOException("sidecar response missing Content-Length")
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val got = inp.read(buf, read, contentLength - read)
            if (got <= 0) throw EOFException("sidecar response truncated")
            read += got
        }
        return String(buf, StandardCharsets.UTF_8)
    }

    /** Reads one CRLF- (or LF-) terminated header line; null on EOF before any byte. */
    private fun readHeaderLine(inp: InputStream): String? {
        val sb = StringBuilder()
        var sawByte = false
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sawByte) sb.toString() else null
            sawByte = true
            when (b.toChar()) {
                '\r' -> { /* swallow; the '\n' ends the line */ }
                '\n' -> return sb.toString()
                else -> sb.append(b.toChar())
            }
        }
    }

    companion object {
        /** Wire-format version; must match the sidecar's `ProtocolVersion`. */
        const val PROTOCOL_VERSION = "1"
    }
}
