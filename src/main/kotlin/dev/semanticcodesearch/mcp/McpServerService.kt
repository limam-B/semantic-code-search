package dev.semanticcodesearch.mcp

import dev.semanticcodesearch.index.CodeSearchIndexService
import dev.semanticcodesearch.settings.CodeSearchSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/**
 * Project-scoped owner of the [McpServer] lifecycle. Started on project open (from the startup
 * activity) and restarted by the Settings panel when the MCP options change. One server per open
 * project; with the port left at 0 each project binds its own free port, so several projects can be
 * open at once without a clash.
 */
@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    private val log = logger<McpServerService>()

    @Volatile private var server: McpServer? = null

    /** The bound port when running, or -1 when stopped/disabled. */
    val boundPort: Int get() = server?.port ?: -1
    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        val st = CodeSearchSettings.get(project).state
        if (!st.mcpEnabled) return

        val index = CodeSearchIndexService.getInstance(project)
        val s = McpServer(
            requestedPort = st.mcpPort.coerceIn(0, 65535),
            serverVersion = pluginVersion(),
            basePath = project.basePath,
            search = { query, limit ->
                val hits = index.search(query)
                if (limit in 1 until hits.size) hits.take(limit) else hits
            },
        )
        try {
            s.start()
            server = s
            log.warn("MCP server listening on http://127.0.0.1:${s.port}/mcp")
        } catch (e: Exception) {
            runCatching { s.close() }
            log.warn("MCP server failed to start on port ${st.mcpPort}", e)
            notifyWarn("Semantic-search MCP server couldn't start on port ${st.mcpPort}: ${e.message}. " +
                "Pick a different port (or 0 = auto) in Settings → Tools → Semantic Code Search.")
        }
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    fun restart() {
        stop()
        start()
    }

    override fun dispose() = stop()

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("dev.semanticcodesearch"))?.version ?: "0"

    private fun notifyWarn(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Semantic Code Search")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): McpServerService =
            project.getService(McpServerService::class.java)
    }
}
