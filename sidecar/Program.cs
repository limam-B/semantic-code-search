using System.Text;
using System.Text.Json;
using RoslynChunker;

// Protocol version — the Kotlin client's handshake checks this. Bump on any wire-format change.
const string ProtocolVersion = "1";

// --- Self-test mode: `dotnet run -- --selftest <file.cs>` prints chunks for eyeballing. ---
if (args.Length >= 2 && args[0] == "--selftest")
{
    var src = File.ReadAllText(args[1]);
    foreach (var c in CodeChunker.Chunk(args[1], src))
    {
        Console.WriteLine($"[{c.StartLine}-{c.EndLine}] {c.Symbol}");
        Console.WriteLine(c.Code);
        Console.WriteLine("--------");
    }
    return 0;
}

// --- Server mode: Content-Length-framed JSON over stdin/stdout (LSP-style). ---
// Requests:  {"id":N,"method":"handshake"}              -> {"id":N,"ok":true,"version":"1"}
//            {"id":N,"method":"chunk","path":..,"text":..} -> {"id":N,"chunks":[{startLine,endLine,symbol,code}]}
var stdin = Console.OpenStandardInput();
var stdout = Console.OpenStandardOutput();

while (true)
{
    var body = ReadMessage(stdin);
    if (body is null) break; // EOF — parent closed the pipe.

    string response;
    try
    {
        using var doc = JsonDocument.Parse(body);
        var rootEl = doc.RootElement;
        var id = rootEl.TryGetProperty("id", out var idEl) ? idEl.GetInt32() : 0;
        var method = rootEl.TryGetProperty("method", out var mEl) ? mEl.GetString() : null;

        response = method switch
        {
            "handshake" => JsonSerializer.Serialize(new { id, ok = true, version = ProtocolVersion }),
            "chunk" => HandleChunk(id, rootEl),
            _ => JsonSerializer.Serialize(new { id, error = $"unknown method '{method}'" }),
        };
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine($"sidecar: {ex}");
        response = JsonSerializer.Serialize(new { id = 0, error = ex.Message });
    }

    WriteMessage(stdout, response);
}
return 0;

static string HandleChunk(int id, JsonElement root)
{
    var path = root.TryGetProperty("path", out var pEl) ? pEl.GetString() ?? "" : "";
    var text = root.TryGetProperty("text", out var tEl) ? tEl.GetString() ?? "" : "";
    List<Chunk> chunks;
    try
    {
        chunks = CodeChunker.Chunk(path, text);
    }
    catch (Exception ex)
    {
        // A single bad file must not kill the server; return no chunks and log to stderr.
        Console.Error.WriteLine($"sidecar: chunk failed for {path}: {ex.Message}");
        chunks = new List<Chunk>();
    }
    return JsonSerializer.Serialize(new
    {
        id,
        chunks = chunks.Select(c => new { startLine = c.StartLine, endLine = c.EndLine, symbol = c.Symbol, code = c.Code }),
    });
}

// Reads one Content-Length-framed message; returns its UTF-8 body, or null on clean EOF.
static byte[]? ReadMessage(Stream s)
{
    var headers = ReadHeaders(s);
    if (headers is null) return null;
    var length = headers.TryGetValue("Content-Length", out var v) && int.TryParse(v, out var n) ? n : -1;
    if (length < 0) throw new IOException("missing or invalid Content-Length header");

    var buf = new byte[length];
    var read = 0;
    while (read < length)
    {
        var got = s.Read(buf, read, length - read);
        if (got <= 0) return null; // truncated — treat as EOF.
        read += got;
    }
    return buf;
}

// Reads header lines up to the blank line. Returns null on EOF before any header.
static Dictionary<string, string>? ReadHeaders(Stream s)
{
    var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    var line = new StringBuilder();
    var sawAny = false;
    var prevCr = false;

    while (true)
    {
        var b = s.ReadByte();
        if (b < 0) return sawAny ? headers : null;
        sawAny = true;

        if (b == '\r') { prevCr = true; continue; }
        if (b == '\n')
        {
            if (line.Length == 0) return headers; // blank line ends the header block.
            var text = line.ToString();
            var colon = text.IndexOf(':');
            if (colon > 0) headers[text[..colon].Trim()] = text[(colon + 1)..].Trim();
            line.Clear();
            prevCr = false;
            continue;
        }
        if (prevCr) { line.Append('\r'); prevCr = false; }
        line.Append((char)b);
    }
}

static void WriteMessage(Stream s, string json)
{
    var payload = Encoding.UTF8.GetBytes(json);
    var header = Encoding.ASCII.GetBytes($"Content-Length: {payload.Length}\r\n\r\n");
    s.Write(header, 0, header.Length);
    s.Write(payload, 0, payload.Length);
    s.Flush();
}
