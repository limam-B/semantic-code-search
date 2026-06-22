plugins {
    kotlin("jvm") version "2.3.0"   // must be >= the Kotlin bundled in Rider 2026.1 (2.3.0) to read its metadata
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "dev.semanticcodesearch"
// Overridable from CI: `-PpluginVersion=1.2.3` (the build.yml derives it from the git tag).
version = providers.gradleProperty("pluginVersion").getOrElse("0.5.0")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Local Rider install enables runIde; if absent (e.g. on a collaborator's machine) the build falls
// back to downloading the SDK so it still compiles/packages. Override with -PriderLocalPath=<path>
// or riderLocalPath=<path> in gradle.properties.
val riderLocalPath: String? = providers.gradleProperty("riderLocalPath").orNull
    ?: "${System.getProperty("user.home")}/AppData/Local/Programs/Rider".takeIf { file(it).exists() }

dependencies {
    intellijPlatform {
        // local() uses the real install so runIde can launch a sandbox; the downloaded SDK is
        // compile-only (runIde fails with ClassNotFound com.intellij.idea.Main) but builds fine.
        if (riderLocalPath != null) local(riderLocalPath) else rider("2026.1.2", useInstaller = false)
    }

    // In-process, offline embedding: HuggingFace tokenizer + ONNX Runtime.
    // onnxruntime ships its native libs inside the jar; the DJL tokenizer fetches a small
    // native once on first run, then is offline. The MODEL is supplied locally (see README) —
    // we never use DJL's model zoo, so nothing else phones home.
    implementation("ai.djl.huggingface:tokenizers:0.31.1")
    // GPU build: bundles the CUDA execution-provider native. Needs CUDA 12.x + cuDNN 9.x present on
    // the machine (matching ORT 1.20); the embedder auto-falls back to CPU if they're missing.
    implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Don't launch a headless IDE to pre-index settings during packaging — leaner, avoids sandbox locks.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }   // open-ended; personal tool on one pinned Rider
        }
    }
}

kotlin {
    jvmToolchain(21)   // Rider 2026.1 runs on JBR 21 (class-file major 65, per the jar decompile)
}
