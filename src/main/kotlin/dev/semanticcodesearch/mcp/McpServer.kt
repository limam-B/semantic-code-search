package dev.semanticcodesearch.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.semanticcodesearch.index.VectorStore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny, dependency-free MCP server that exposes the project's semantic search as a single
 * `search_code` tool over the MCP Streamable-HTTP transport (JSON-RPC 2.0 over HTTP POST).
 *
 * In-process by design: it reuses the already-loaded index + ONNX embedder owned by
 * `CodeSearchIndexService`, so answering a query is the same sub-ms path the Search Everywhere
 * tab uses — no second model load, no on-disk snapshot going stale. Built on the JDK's
 * `com.sun.net.httpserver` (no new dependency) and Gson (already on the classpath).
 *
 * Security posture: binds to the loopback address only. It is a read-only surface — the one tool
 * runs a search and returns matches; it cannot mutate the index or the project.
 *
 * Not implemented (intentional, for this minimal cut): sessions (`Mcp-Session-Id`), server-initiated
 * SSE (GET returns 405), JSON-RPC batching (the 2025-06-18 spec dropped it), and resources/prompts.
 */
class McpServer(
    private val requestedPort: Int,                 // 0 = let the OS pick a free port
    private val serverVersion: String,
    private val basePath: String?,                  // project base, to show project-relative paths
    private val search: (query: String, limit: Int) -> List<VectorStore.Hit>,
) : AutoCloseable {

    private val gson = Gson()
    private var http: HttpServer? = null
    private var pool: ExecutorService? = null

    /** The actually-bound port (differs from [requestedPort] when that was 0), or -1 if not started. */
    val port: Int get() = http?.address?.port ?: -1

    fun start() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0)
        server.createContext("/mcp", ::handle)
        pool = Executors.newFixedThreadPool(2).also { server.executor = it }
        server.start()
        http = server
    }

    override fun close() {
        runCatching { http?.stop(0) }
        runCatching { pool?.shutdownNow() }
        http = null
        pool = null
    }

    // ---- HTTP ----

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "POST" -> handlePost(exchange)
                "DELETE" -> respondEmpty(exchange, 202)   // stateless: accept a session teardown
                else -> respondEmpty(exchange, 405)       // no GET/SSE stream offered
            }
        } catch (e: Exception) {
            runCatching { respondJson(exchange, 200, rpcError(null, -32700, "Parse error: ${e.message}")) }
        } finally {
            exchange.close()
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(UTF_8)
        val root = JsonParser.parseString(body)
        if (root !is JsonObject) {                        // arrays/batches unsupported
            respondJson(exchange, 200, rpcError(null, -32600, "Expected a single JSON-RPC object"))
            return
        }
        val id = root.get("id")?.takeUnless { it.isJsonNull }
        val method = root.get("method")?.asString

        when (method) {
            "initialize" -> respondJson(exchange, 200, rpcResult(id, initializeResult(root)))
            "tools/list" -> respondJson(exchange, 200, rpcResult(id, toolsList()))
            "tools/call" -> respondJson(exchange, 200, rpcResult(id, toolsCall(root)))
            "ping" -> respondJson(exchange, 200, rpcResult(id, JsonObject()))
            "notifications/initialized",
            "notifications/cancelled" -> respondEmpty(exchange, 202)
            else ->
                if (id != null) respondJson(exchange, 200, rpcError(id, -32601, "Unknown method: $method"))
                else respondEmpty(exchange, 202)
        }
    }

    // ---- MCP methods ----

    private fun initializeResult(request: JsonObject): JsonObject {
        val requested = request.getAsJsonObject("params")?.get("protocolVersion")?.asString
        return JsonObject().apply {
            addProperty("protocolVersion", requested ?: PROTOCOL_VERSION)
            add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "rider-semantic-code-search")
                addProperty("version", serverVersion)
            })
        }
    }

    private fun toolsList(): JsonObject {
        val schema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("query", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Natural-language description of the code you're looking for.")
                })
                add("limit", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("description", "Max results to return (default: the plugin's configured Results-shown).")
                    addProperty("minimum", 1)
                    addProperty("maximum", 100)
                })
            })
            add("required", JsonArray().apply { add("query") })
        }
        val tool = JsonObject().apply {
            addProperty("name", "search_code")
            addProperty("description",
                "Semantic + lexical search over this Rider project's C# code. Returns the best-matching " +
                    "symbols with their file:line and source (XML docs folded in). Ask in natural language, " +
                    "e.g. \"where is the hand rotation set\".")
            add("inputSchema", schema)
        }
        return JsonObject().apply { add("tools", JsonArray().apply { add(tool) }) }
    }

    private fun toolsCall(request: JsonObject): JsonObject {
        val params = request.getAsJsonObject("params")
        val name = params?.get("name")?.asString
        if (name != "search_code") return toolError("Unknown tool: $name")

        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val query = args.get("query")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        if (query.isEmpty()) return toolError("The 'query' argument is required.")
        val limit = args.get("limit")?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrNull() ?: 0

        val hits = search(query, limit)
        return toolText(formatHits(query, hits))
    }

    private fun formatHits(query: String, hits: List<VectorStore.Hit>): String {
        if (hits.isEmpty()) {
            return "No results for: $query\n\n" +
                "The index may be empty or not built yet — open the Rider project and run " +
                "\"Index now\" (Settings → Tools → Semantic Code Search)."
        }
        return buildString {
            append("${hits.size} result(s) for: $query\n")
            hits.forEachIndexed { i, h ->
                val c = h.chunk
                append("\n${i + 1}. ${c.symbolName}  —  ${relativize(c.filePath)}:${c.startLine + 1}")
                append("  (score ${String.format(Locale.ROOT, "%.3f", h.score)})\n")
                append("```csharp\n")
                append(c.code.trim())
                append("\n```\n")
            }
        }
    }

    private fun relativize(path: String): String {
        val base = basePath?.replace('\\', '/')?.trimEnd('/') ?: return path
        val p = path.replace('\\', '/')
        return if (p.startsWith("$base/")) p.substring(base.length + 1) else p
    }

    // ---- JSON-RPC envelope helpers ----

    private fun rpcResult(id: com.google.gson.JsonElement?, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun rpcError(id: com.google.gson.JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    /** A tool result carrying a single text block. */
    private fun toolText(text: String) = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", false)
    }

    /** A tool result flagged as an error (returned to the model, not a protocol failure). */
    private fun toolError(text: String) = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", true)
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = gson.toJson(body).toByteArray(UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondEmpty(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
    }
}
