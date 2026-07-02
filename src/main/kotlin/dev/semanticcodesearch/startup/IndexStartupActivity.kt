package dev.semanticcodesearch.startup

import dev.semanticcodesearch.index.CodeSearchIndexService
import dev.semanticcodesearch.mcp.McpServerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: touch the index service (its init wires the incremental file listener), load a
 * previously saved index from disk (so you don't have to Rebuild every session), and start the MCP
 * server (no-op if disabled in Settings) so agents can query as soon as the project is open.
 */
class IndexStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(CodeSearchIndexService::class.java)
        service.ensureModelDirsExist()
        service.loadFromDisk()
        McpServerService.getInstance(project).start()
    }
}
