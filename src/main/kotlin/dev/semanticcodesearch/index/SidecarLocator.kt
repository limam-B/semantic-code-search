package dev.semanticcodesearch.index

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Finds the two things [RoslynChunker] needs to launch: the bundled sidecar dll, and a usable
 * `dotnet` runtime. Both are resolved fresh each time so a Settings change (dotnet path) takes
 * effect without a restart. Kept separate from the chunker so the pre-index gate can report a
 * *specific* reason ("no .NET" vs "sidecar missing") rather than a generic failure.
 */
object SidecarLocator {

    private val log = logger<SidecarLocator>()
    private const val PLUGIN_ID = "dev.semanticcodesearch"
    private const val DLL_NAME = "roslyn-chunker.dll"

    /** Absolute path to the sidecar dll, or null if it isn't found. */
    fun locateDll(): Path? {
        // 1) Test/dev override.
        System.getProperty("roslyn.sidecar.dll")?.let { p ->
            val path = Paths.get(p)
            if (Files.isRegularFile(path)) return path
        }
        // 2) Installed plugin: <pluginPath>/sidecar/roslyn-chunker.dll (placed there by the Gradle build).
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.let { root ->
            val path = root.resolve("sidecar").resolve(DLL_NAME)
            if (Files.isRegularFile(path)) return path
        }
        // 3) Running from a dev checkout (e.g. tests, runIde): the published/built sidecar output.
        for (rel in DEV_DLL_CANDIDATES) {
            val path = Paths.get(System.getProperty("user.dir")).resolve(rel)
            if (Files.isRegularFile(path)) return path
        }
        log.warn("Roslyn sidecar dll not found (plugin path, dev paths, or -Droslyn.sidecar.dll)")
        return null
    }

    /**
     * A runnable `dotnet`: the user-configured path (file or its folder) if set, else `dotnet` on
     * PATH, verified by a quick `dotnet --version`. Returns the command to run, or null.
     */
    fun resolveDotnet(configuredPath: String): String? {
        if (configuredPath.isNotBlank()) {
            val p = Paths.get(configuredPath)
            val exe = when {
                Files.isRegularFile(p) -> p
                Files.isDirectory(p) -> p.resolve(if (isWindows()) "dotnet.exe" else "dotnet")
                else -> null
            }
            if (exe != null && Files.isRegularFile(exe) && canRun(exe.toString())) return exe.toString()
            log.warn("configured dotnet path is not runnable: $configuredPath")
            return null
        }
        return if (canRun("dotnet")) "dotnet" else null
    }

    /** True if `<cmd> --version` exits 0 within a short timeout. */
    private fun canRun(cmd: String): Boolean = try {
        val p = ProcessBuilder(cmd, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() == 0 else { p.destroyForcibly(); false }
    } catch (e: Exception) {
        false
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val DEV_DLL_CANDIDATES = listOf(
        "sidecar/bin/Release/net8.0/$DLL_NAME",
        "sidecar/bin/Debug/net8.0/$DLL_NAME",
    )
}
