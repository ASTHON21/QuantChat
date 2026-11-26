package com.example.data

import com.example.BuildConfig

data class ReleaseChangelogEntry(
    val version: String,
    val buildNumber: Int,
    val date: String,
    val badge: String,
    val highlights: List<String>
)

object AppReleaseInfo {
    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE
    val buildType: String get() = BuildConfig.BUILD_TYPE
    val gitSha: String get() = BuildConfig.GIT_SHA
    val fullVersionLabel: String get() = "v$versionName ($versionCode)"

    val changelog: List<ReleaseChangelogEntry> = listOf(
        ReleaseChangelogEntry(
            version = "v1.10.0",
            buildNumber = 11,
            date = "2026-11-26",
            badge = "CURRENT",
            highlights = listOf(
                "Phase 2.4: Retained ParcelFileDescriptor + mmap model loading with full file lifecycle governance across download, verification, mapping, unload, and safe deletion.",
                "Added GGUF structural header + Hugging Face LFS SHA-256 verification (ModelFileVerifier & .verified.json sidecars) enforcing pre-load integrity checks in RuntimeOrchestrator and on-demand Verify actions.",
                "Added OS FileChannel FileLock coordination (MmapLifecycleRegistry) pairing ref-counted shared read locks during active mmap leases with exclusive .lck write locks during downloads, SAF imports, and deletion.",
                "Tracked ParcelFileDescriptor & mmap leases in LlamaCppBackend and VulkanLlamaBackend so unloadModel() cleanly closes native contexts, PFDs, and FileLocks before releasing storage, plus active-mapping guards blocking deletion until unloaded.",
                "Added persistent .part.checkpoint.json manifests with HTTP Range/If-Range resume recovery (206 append, 200 reset, verified 416 promotion) and StatFs storage space checks with 256 MiB / 5% safety buffers."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.9.0",
            buildNumber = 10,
            date = "2026-11-26",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 2.3: Decoupled long-running GGUF downloads and local LLM inference from Activity/ViewModel lifecycles via Application-scoped QuantRuntimeHost and Android Foreground Services.",
                "Added ModelDownloadService (foregroundServiceType=\"dataSync\") with live progress notifications, Pause/Cancel notification actions, and Android 15 onTimeout safety handling.",
                "Added InferenceForegroundService (foregroundServiceType=\"specialUse\" with PROPERTY_SPECIAL_USE_FGS_SUBTYPE) displaying live token/backend telemetry, Stop action, and background survival on Activity rotation or task removal.",
                "Added ModelMaintenanceWorker via WorkManager for deferrable 24h storage cleanup (expired .part removal, GGUF magic check, orphan KV cache pruning, and catalog check) with Thermal Guard deferral, plus Android 13+ POST_NOTIFICATIONS & FGS background launch compliance."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.8.0",
            buildNumber = 9,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 2.2: Implemented 4-tier Android PowerManager Thermal API governance covering THERMAL_STATUS_NONE / LIGHT / MODERATE / SEVERE / CRITICAL and PowerManager thermal headroom forecasting.",
                "MODERATE thermal state automatically reduces CPU threads (Thermal Safe) and halves GPU layer offload (n_gpu_layers) in GpuOffloadPlanner.",
                "SEVERE thermal state enforces CPU-only execution, throttles token generation speed via inter-token pacing delays, and pauses non-essential tasks (thread calibration, hardware benchmarks, and active Hub downloads).",
                "CRITICAL thermal state immediately stops active inference (EngineError.ThermalShutdown), cancels pending tasks, and safely unloads the active llama.cpp / Vulkan context to release memory, with live status matrix and chat thermal banners."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.7.0",
            buildNumber = 8,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 2.1: Implemented 5-term memory admission planning (M_total = M_weights + M_KV + M_compute + M_runtime + M_reserve) using GGUF architecture dimensions, context window, batch size, and Vulkan GPU staging costs.",
                "Added ProcessMemoryProbe in :hardware-manager combining ActivityManager.MemoryInfo (availMem, threshold, lowMemory, memoryClass), process PSS, native heap allocation, and /proc/self/status VmRSS.",
                "Added strict pre-load admission gating in RuntimeOrchestrator throwing EngineError.InsufficientMemory before native llama.cpp allocation when headroom is exceeded, with automated safe n_ctx / CPU-only downgrade suggestions.",
                "Added 5-term memory admission breakdown pills on downloaded model cards, live process telemetry in Inference Settings, and an interactive Blocked Admission recovery dialog with 1-tap Downgrade & Load."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.6.0",
            buildNumber = 7,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 1.4: Added canonical DeviceCapabilities schema and HardwareCapabilityProvider contract in :engine-core with both reactive StateFlow updates and synchronous getDeviceCapabilities() snapshots.",
                "Upgraded HardwareCapabilityManager to maintain and emit live unified CPU, SIMD, GPU, NPU, memory, and integer thermal status snapshots across probes and PowerManager thermal callbacks.",
                "Integrated DeviceCapabilitiesPolicy directly into ResilientFailoverBackend for automatic GPU eligibility gating (low-memory & severe-thermal CPU fallback) and per-request thermal thread downshifting.",
                "Added Unified Device Capabilities card in Inference Settings displaying the live 6-field snapshot linked directly to engine routing and thermal guard status."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.5.0",
            buildNumber = 6,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 1.3: Added non-root Android NPU hardware detection (NpuCapabilityProbe) prioritizing Qualcomm Snapdragon Hexagon HTP (SM8xxx/SM7xxx generation hints) alongside MediaTek APU, Google Tensor TPU, Samsung Exynos NPU, and Android NNAPI.",
                "Added vendor-agnostic NpuInferenceBackend & NpuVendorDriverAdapter abstraction in :engine-core with typed EngineError.NpuBackendError rejection for unbundled SDKs or incompatible artifacts.",
                "Added optional JNI dlopen runtime check (libQnnHtp.so / libcdsprpc.so) in VulkanNativeBridge respecting non-root Android SELinux linker namespace boundaries.",
                "Added NpuCompatibilityEvaluator & UI NPU Diagnostics / Compatibility Gate banners that automatically route .gguf weights to Vulkan GPU or CPU with clear operator/format explanations."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.4.0",
            buildNumber = 5,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 1.2: Added modular :engine-vulkan backend with C++17 JNI integration against llama.cpp ggml-vulkan and CI arm64-v8a cross-compilation.",
                "Added 3-layer Vulkan capability probing (Android system features, GPU heap/16-bit/FP16 inspection, and live GPU buffer copy/operator self-test) with OpenCL documented as Phase 2.",
                "Added ResilientFailoverBackend routing and automatic CPU failover on Vulkan probe, load, or pre-decode inference failures without breaking existing CPU inference.",
                "Added unified Android RAM/VRAM auto GPU layer offload planner (n_gpu_layers) plus manual override slider, Chat GPU/CPU status pill, and GPU probe diagnostics."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.3.0",
            buildNumber = 4,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Phase 1.1: Upgraded sysfs CPU topology inspection (/sys/devices/system/cpu/) to classify Prime / Big / LITTLE clusters via cpu_capacity and frequency fallback.",
                "Added 4 adaptive CPU scheduling profiles (Eco, Balanced, Performance, Thermal Safe) with per-request dynamic n_threads injection and legal Process.setThreadPriority hints without reloading GGUF weights.",
                "Added live PowerManager thermal status listener that automatically downshifts to Thermal Safe at Moderate+ device temperatures and restores when cooled.",
                "Added 1-tap multi-thread benchmark calibration (Calibrate Optimal Threads) with DataStore device-signature persistence and clickable chat telemetry profile pill."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.2.0",
            buildNumber = 3,
            date = "2026-11-25",
            badge = "RELEASED",
            highlights = listOf(
                "Refactored runtime into 8 decoupled Gradle modules with unified InferenceBackend contracts and EngineLifecycleState.",
                "Added real Android CPU SIMD/cluster topology, free RAM admission, thermal monitoring, and hardware Run Bench diagnostics.",
                "Added binary GGUF header metadata parser, token context budgeting, and per-thread KV-cache session persistence.",
                "Established loopback OpenAI router module and Android Share / Process Text local prompt integration."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.1.0",
            buildNumber = 2,
            date = "2026-11-22",
            badge = "RELEASED",
            highlights = listOf(
                "Enabled llama.cpp partial token streaming so responses appear token-by-token instead of stalling on CPU prompt evaluation.",
                "Fixed Room foreign-key cascade bug where updating thread metadata wiped existing chat messages.",
                "Serialized native model load/unload lifecycle with active generation cancellation and added live token counter.",
                "Added versioned formal GitHub Releases, BuildConfig commit tracking, and in-app changelog viewer."
            )
        ),
        ReleaseChangelogEntry(
            version = "v1.0.0",
            buildNumber = 1,
            date = "2026-11-21",
            badge = "INITIAL",
            highlights = listOf(
                "100% offline GGUF local inference powered by bundled llama.cpp (arm64-v8a / x86_64).",
                "Curated 3.8B–4B models, Hugging Face Hub search, resumable OkHttp downloads, and SAF local .gguf import.",
                "Room local conversation persistence and DataStore inference parameter tuning."
            )
        )
    )
}
