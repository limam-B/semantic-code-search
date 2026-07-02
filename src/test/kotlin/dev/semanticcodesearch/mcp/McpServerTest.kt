package dev.semanticcodesearch.mcp

import com.google.gson.JsonParser
import dev.semanticcodesearch.index.CodeChunk
import dev.semanticcodesearch.index.VectorStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Drives the MCP server over real HTTP (no IntelliJ platform needed) to prove the JSON-RPC wiring:
 * initialize / tools/list / tools/call round-trips, plus that unsupported verbs are rejected.
 */
class McpServerTest {

    private lateinit var server: McpServer
    private val client = HttpClient.newHttpClient()

    private val fakeHits = listOf(
        VectorStore.Hit(CodeChunk("/proj/Game/RagdollHand.cs", 41, 44, "RagdollHand.SetHandRotation", "public void SetHandRotation(Quaternion q) { }"), 0.873f),
    )

    @Before
    fun setUp() {
        server = McpServer(
            requestedPort = 0,
            serverVersion = "9.9.9",
            basePath = "/proj",
            search = { _, _ -> fakeHits },
        )
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun rpc(body: String): Pair<Int, String> {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return resp.statusCode() to resp.body()
    }

    @Test
    fun initializeReportsServerInfo() {
        val (code, body) = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(200, code)
        val result = JsonParser.parseString(body).asJsonObject.getAsJsonObject("result")
        assertEquals("2025-06-18", result.get("protocolVersion").asString)
        assertEquals("rider-semantic-code-search", result.getAsJsonObject("serverInfo").get("name").asString)
        assertEquals("9.9.9", result.getAsJsonObject("serverInfo").get("version").asString)
    }

    @Test
    fun toolsListAdvertisesSearchCode() {
        val (_, body) = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = JsonParser.parseString(body).asJsonObject.getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals("search_code", tools[0].asJsonObject.get("name").asString)
        assertTrue(tools[0].asJsonObject.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("query"))
    }

    @Test
    fun toolsCallReturnsHitWithRelativePathAndCode() {
        val (code, body) = rpc(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_code","arguments":{"query":"hand rotation"}}}"""
        )
        assertEquals(200, code)
        val result = JsonParser.parseString(body).asJsonObject.getAsJsonObject("result")
        assertEquals(false, result.get("isError").asBoolean)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue("symbol present: $text", text.contains("RagdollHand.SetHandRotation"))
        // 0-based startLine 41 shown 1-based as 42, and path relativized against basePath.
        assertTrue("relative file:line present: $text", text.contains("Game/RagdollHand.cs:42"))
        assertTrue("code body present: $text", text.contains("SetHandRotation(Quaternion q)"))
    }

    @Test
    fun missingQueryIsAToolError() {
        val (_, body) = rpc(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_code","arguments":{}}}"""
        )
        val result = JsonParser.parseString(body).asJsonObject.getAsJsonObject("result")
        assertEquals(true, result.get("isError").asBoolean)
    }

    @Test
    fun unknownMethodYieldsJsonRpcError() {
        val (_, body) = rpc("""{"jsonrpc":"2.0","id":5,"method":"does/notExist"}""")
        val obj = JsonParser.parseString(body).asJsonObject
        assertEquals(-32601, obj.getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun getIsNotAllowed() {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/mcp")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, resp.statusCode())
    }
}
