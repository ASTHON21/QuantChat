# Changelog

## v1.1.0 (Build 2) - 2026-11-22

### Fixes & Improvements
- **Local GGUF Streaming Fixed**: Enabled `emit_partial_completion` in `LocalLlamaRepository` so `llama.cpp` streams tokens live to the chat bubble instead of stalling on `"Evaluating prompt on local CPU..."`.
- **Chat Message Persistence Fixed**: Replaced Room `OnConflictStrategy.REPLACE` in `ChatDao` with `INSERT (IGNORE)` + `UPDATE` transactions so updating thread previews no longer triggers `ON DELETE CASCADE` and wipes messages.
- **Native Lifecycle Safety**: Serialized model load/unload/completion with a `Mutex` and awaited active generation stop before releasing native contexts.
- **CI Robolectric Reliability**: Configured Gradle-managed offline Robolectric SDK resolution with Google's Maven Central mirror to avoid Maven Central HTTP 429 failures.
- **Formal Releases & In-App Version Info**: Added formal versioned GitHub Releases (`v1.1.0`), versioned APK artifacts with SHA-256 checksums, and an in-app Version & Changelog viewer.

## v1.0.0 (Build 1) - 2026-11-21

### Initial Release
- 100% offline on-device GGUF chat powered by bundled `llama.cpp` (`llamacpp-kotlin 0.4.0`).
- Curated 3.8B–4B models (`Qwen3.5-4B`, `Qwen3-4B`, `Gemma 3 4B`, `Phi-4-mini`), Hugging Face Hub search, resumable OkHttp downloads, and SAF local `.gguf` import.
- Room local conversation persistence and DataStore inference parameter tuning.
