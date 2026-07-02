using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;

namespace RoslynChunker;

/// <summary>One embeddable unit of code. Line numbers are 0-based, inclusive — they map directly
/// onto the Kotlin side's <c>CodeChunk</c> contract (navigation uses them as-is).</summary>
public sealed record Chunk(int StartLine, int EndLine, string Symbol, string Code);

/// <summary>
/// Turns a C# source file into <see cref="Chunk"/>s via the Roslyn syntax tree (no compilation,
/// so it's fast and needs no project references). Every documentable declaration becomes a chunk:
/// types (header only), enums, delegates, methods, ctors, properties, indexers, operators, events,
/// and public/documented fields. The <c>///</c> XML doc and the file banner are folded into the
/// embedded <see cref="Chunk.Code"/> text — the navigation line stays on the signature.
/// </summary>
public static class CodeChunker
{
    public static List<Chunk> Chunk(string path, string text)
    {
        var tree = CSharpSyntaxTree.ParseText(text);
        var root = (CompilationUnitSyntax)tree.GetRoot();
        var banner = FileBanner(root);
        var chunks = new List<Chunk>();

        foreach (var node in root.DescendantNodes())
        {
            switch (node)
            {
                // class / struct / interface / record — header only (members are chunked separately).
                case TypeDeclarationSyntax t:
                    chunks.Add(TypeHeaderChunk(t, banner));
                    break;
                case EnumDeclarationSyntax e:
                    chunks.Add(Whole(e, Symbol(e, e.Identifier.Text), banner));
                    break;
                case DelegateDeclarationSyntax d:
                    chunks.Add(Whole(d, Symbol(d, d.Identifier.Text), null));
                    break;
                case MethodDeclarationSyntax m:
                    chunks.Add(Whole(m, Symbol(m, m.Identifier.Text), null));
                    break;
                case ConstructorDeclarationSyntax c:
                    chunks.Add(Whole(c, Symbol(c, c.Identifier.Text), null));
                    break;
                case PropertyDeclarationSyntax p:
                    chunks.Add(Whole(p, Symbol(p, p.Identifier.Text), null));
                    break;
                case IndexerDeclarationSyntax ix:
                    chunks.Add(Whole(ix, Symbol(ix, "this[]"), null));
                    break;
                case OperatorDeclarationSyntax op:
                    chunks.Add(Whole(op, Symbol(op, "operator " + op.OperatorToken.Text), null));
                    break;
                case ConversionOperatorDeclarationSyntax co:
                    chunks.Add(Whole(co, Symbol(co, "operator " + co.Type), null));
                    break;
                case EventDeclarationSyntax ev:
                    chunks.Add(Whole(ev, Symbol(ev, ev.Identifier.Text), null));
                    break;
                case FieldDeclarationSyntax f when ShouldChunkField(f):
                    var fieldName = f.Declaration.Variables.FirstOrDefault()?.Identifier.Text ?? "field";
                    chunks.Add(Whole(f, Symbol(f, fieldName), null));
                    break;
            }
        }
        return chunks;
    }

    /// <summary>A member/enum/delegate chunk covering the whole declaration (signature + body).</summary>
    private static Chunk Whole(SyntaxNode node, string symbol, string? banner)
    {
        var span = node.GetLocation().GetLineSpan();
        return new Chunk(
            span.StartLinePosition.Line,
            span.EndLinePosition.Line,
            symbol,
            BuildCode(symbol, banner, DocText(node), node.ToString()));
    }

    /// <summary>A type chunk covering only the header (up to the opening brace), so the type's own
    /// summary is searchable without re-embedding its entire body (members are chunked on their own).</summary>
    private static Chunk TypeHeaderChunk(TypeDeclarationSyntax t, string? banner)
    {
        var symbol = Symbol(t, t.Identifier.Text);
        var hasBody = !t.OpenBraceToken.IsKind(SyntaxKind.None);
        var headerEnd = hasBody ? t.OpenBraceToken.SpanStart : t.Span.End;
        var headerSpan = TextSpan.FromBounds(t.Span.Start, headerEnd);
        var headerText = t.SyntaxTree.GetText().ToString(headerSpan).TrimEnd();
        var startLine = t.GetLocation().GetLineSpan().StartLinePosition.Line;
        var endLine = t.SyntaxTree.GetLineSpan(headerSpan).EndLinePosition.Line;
        return new Chunk(startLine, endLine, symbol, BuildCode(symbol, banner, DocText(t), headerText));
    }

    /// <summary>Fully-qualified-ish symbol: enclosing type chain + member name (namespaces omitted,
    /// matching the existing convention, e.g. <c>MotionStateMachine.TryTransition</c>).</summary>
    private static string Symbol(SyntaxNode node, string name)
    {
        var parts = new List<string>();
        foreach (var anc in node.Ancestors())
        {
            switch (anc)
            {
                case TypeDeclarationSyntax td: parts.Add(td.Identifier.Text); break;
                case EnumDeclarationSyntax ed: parts.Add(ed.Identifier.Text); break;
            }
        }
        parts.Reverse();
        parts.Add(name);
        return string.Join(".", parts);
    }

    /// <summary>Embed text = symbol + (file banner) + (cleaned XML doc) + the code itself.</summary>
    private static string BuildCode(string symbol, string? banner, string doc, string body)
    {
        var sb = new StringBuilder();
        sb.AppendLine(symbol);
        if (!string.IsNullOrEmpty(banner)) sb.AppendLine(banner);
        if (doc.Length > 0) sb.AppendLine(doc);
        sb.Append(body);
        return sb.ToString();
    }

    /// <summary>Chunk a field only when it carries intent worth searching: public/protected/internal,
    /// or documented. Skips private undocumented backing fields (noise).</summary>
    private static bool ShouldChunkField(FieldDeclarationSyntax f) =>
        DocText(f).Length > 0 ||
        f.Modifiers.Any(m =>
            m.IsKind(SyntaxKind.PublicKeyword) ||
            m.IsKind(SyntaxKind.ProtectedKeyword) ||
            m.IsKind(SyntaxKind.InternalKeyword));

    /// <summary>Plain-text of a node's leading <c>///</c> XML doc: markers and tags stripped, entities
    /// decoded, whitespace collapsed. Empty string when the node has no doc comment.</summary>
    private static string DocText(SyntaxNode node)
    {
        var raw = new StringBuilder();
        foreach (var tr in node.GetLeadingTrivia())
        {
            if (tr.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia) ||
                tr.IsKind(SyntaxKind.MultiLineDocumentationCommentTrivia))
            {
                raw.Append(tr.ToFullString());
            }
        }
        return CleanDoc(raw.ToString());
    }

    private static readonly Regex Markers = new(@"^\s*(///|/\*\*|\*/|\*)", RegexOptions.Compiled);
    private static readonly Regex Tags = new("<[^>]+>", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    // Keep the referenced name from self-closing reference tags before the generic tag strip, so
    // "<see cref="Current"/>" contributes "Current" instead of vanishing.
    private static readonly Regex RefTags = new(
        """<(?:see|seealso|paramref|typeparamref)\b[^>]*?(?:cref|name|langword)\s*=\s*"([^"]*)"[^>]*/?>""",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private static string CleanDoc(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var lines = raw.Replace("\r", "").Split('\n').Select(l => Markers.Replace(l, "").Trim());
        var joined = string.Join(" ", lines);
        joined = RefTags.Replace(joined, " $1 ");
        joined = Tags.Replace(joined, " ");
        joined = WebUtility.HtmlDecode(joined);
        return Whitespace.Replace(joined, " ").Trim();
    }

    /// <summary>The file-level banner: leading single-line <c>//</c> comments on the first token,
    /// cleaned to plain text. This is the dashed <c>// &lt;summary&gt;</c> header convention.</summary>
    private static string? FileBanner(CompilationUnitSyntax root)
    {
        var first = root.GetFirstToken(includeZeroWidth: true);
        var raw = new StringBuilder();
        foreach (var tr in first.LeadingTrivia)
        {
            if (tr.IsKind(SyntaxKind.SingleLineCommentTrivia) ||
                tr.IsKind(SyntaxKind.MultiLineCommentTrivia))
            {
                raw.Append(tr.ToString());
                raw.Append('\n');
            }
        }
        var cleaned = CleanDocFromSlashSlash(raw.ToString());
        return cleaned.Length > 0 ? cleaned : null;
    }

    private static readonly Regex DashRun = new(@"-{3,}", RegexOptions.Compiled);

    private static string CleanDocFromSlashSlash(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var lines = raw.Replace("\r", "").Split('\n')
            .Select(l => l.TrimStart().TrimStart('/').Trim());
        var joined = string.Join(" ", lines);
        joined = Tags.Replace(joined, " ");
        joined = DashRun.Replace(joined, " ");
        joined = WebUtility.HtmlDecode(joined);
        return Whitespace.Replace(joined, " ").Trim();
    }
}
