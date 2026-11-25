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
            version = "v1.4.0",
            buildNumber = 5,
            date = "2026-11-25",
            badge = "CURRENT",
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
