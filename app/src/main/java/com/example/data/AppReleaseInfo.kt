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
            version = "v1.1.0",
            buildNumber = 2,
            date = "2026-11-22",
            badge = "CURRENT",
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
