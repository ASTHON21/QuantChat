# Changelog

## v1.2.0 (Build 3) - 2026-11-25

### Phase 0: Core Runtime Architecture & Native Probing
- **8-Module Architecture Decoupling**: Split inference contracts (`engine-core`), `llama.cpp` JNI adapter (`engine-llamacpp`), hardware probing (`hardware-manager`), RAM/thermal/thread scheduling (`resource-manager`), GGUF metadata inspection (`model-manager`), KV-cache sessions (`session-manager`), loopback OpenAI API (`local-server`), and Android share/text-action intent routing (`system-integration`).
- **Unified `InferenceBackend` Contract**: Added sealed `EngineLifecycleState`, structured `EngineError` hierarchy, `ModelId`/`SessionId` isolation, `Mutex`-serialized concurrency, `Flow` streaming, `tokenize`/`detokenize`, `embed`, `saveSession`/`loadSession`, and throughput benchmarking.
- **Native Hardware & RAM Admission Diagnostics**: Reads `/proc/cpuinfo` SIMD flags (`NEON`, `DOTPROD`, `I8MM`, `FP16`), `/sys` CPU cluster frequencies, `ActivityManager` free RAM, and `PowerManager` thermal status; exposes recommended thread tuning and `Run Bench` in Inference Settings.
- **GGUF Binary Header Inspection & KV-Cache Sessions**: Parses GGUF architecture and context length metadata on-device without loading full weights, enforces `n_ctx` token budgets, and saves/restores per-thread KV snapshots.
- **Android Share & Text Selection Entry**: Added `ACTION_SEND` and `ACTION_PROCESS_TEXT` intents to open shared text directly in a local QuantChat draft.

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
