package dev.semanticcodesearch.embedding

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads the CUDA + cuDNN dependency DLLs into the process by ABSOLUTE PATH before ONNX Runtime loads
 * `onnxruntime_providers_cuda.dll`. Why absolute-path loading (not PATH):
 *
 * Rider hardens its native loader (SetDefaultDllDirectories), so the PATH env var is IGNORED during
 * DLL dependency resolution — a plain "add the dir to PATH" does nothing here (it works in a normal
 * JVM, which is why the dev sandbox loaded CUDA but Toolbox-launched Rider gets Windows error 126).
 * Pre-loading every dependency by absolute path sidesteps the search entirely: once a module is in
 * the process, Windows resolves imports to it by name, regardless of search policy.
 *
 * Must use the CUDA-12 cuDNN build (…\CUDNN\vX\bin\12.x) to match a CUDA 12.x toolkit — loading the
 * CUDA-13 build against a 12.x runtime hard-crashes the JVM (STATUS_STACK_BUFFER_OVERRUN).
 */
object CudaNativeLoader {

    private val LOG = logger<CudaNativeLoader>()

    @Volatile
    private var done = false

    fun cudaAvailable(): Boolean = cudaBinDir() != null

    @Synchronized
    fun ensureLoaded() {
        if (done) return
        done = true

        val targets = ArrayList<Path>()
        cudaBinDir()?.let { bin ->
            for (n in CUDA_LIBS) {
                val f = bin.resolve("$n.dll")
                if (Files.exists(f)) targets.add(f)
            }
        }
        cudnnDir12()?.let { dir ->
            runCatching {
                Files.list(dir).use { s ->
                    s.filter { it.fileName.toString().endsWith(".dll") }.forEach { targets.add(it) }
                }
            }
        }

        if (targets.isEmpty()) {
            LOG.warn("CudaNativeLoader: no CUDA 12 / cuDNN DLLs found — GPU unavailable, will use CPU.")
            return
        }

        // Retry until no further DLL can be loaded (resolves inter-library load order automatically).
        var remaining: List<Path> = targets
        var lastSize = -1
        while (remaining.isNotEmpty() && remaining.size != lastSize) {
            lastSize = remaining.size
            remaining = remaining.filterNot { dll ->
                runCatching { System.load(dll.toString()) }.isSuccess
            }
        }
        LOG.warn("CudaNativeLoader: loaded ${targets.size - remaining.size}/${targets.size} CUDA/cuDNN DLLs by absolute path; ${remaining.size} unresolved")
    }

    /** Newest CUDA toolkit bin that has the v12 runtime. */
    private fun cudaBinDir(): Path? {
        System.getenv("CUDA_PATH")?.let {
            val b = Paths.get(it, "bin")
            if (Files.exists(b.resolve("cudart64_12.dll"))) return b
        }
        val root = Paths.get("C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA")
        if (!Files.isDirectory(root)) return null
        Files.list(root).use { s ->
            return s.filter { Files.isDirectory(it) }
                .map { it.resolve("bin") }
                .filter { Files.exists(it.resolve("cudart64_12.dll")) }
                .sorted(Comparator.reverseOrder())
                .findFirst().orElse(null)
        }
    }

    /** cuDNN dir built for CUDA 12 (folder named 12.x), holding cudnn64_9.dll + its sublibs. */
    private fun cudnnDir12(): Path? {
        val root = Paths.get("C:\\Program Files\\NVIDIA\\CUDNN")
        if (!Files.isDirectory(root)) return null
        Files.walk(root, 5).use { s ->
            return s.filter { it.fileName?.toString() == "cudnn64_9.dll" }
                .map { it.parent }
                .filter { it.fileName.toString().startsWith("12") }
                .findFirst().orElse(null)
        }
    }

    private val CUDA_LIBS = listOf(
        "cudart64_12", "cublasLt64_12", "cublas64_12",
        "cufft64_11", "cusparse64_12", "curand64_10",
    )
}
