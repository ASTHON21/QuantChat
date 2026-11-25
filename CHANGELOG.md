# Changelog

## v1.6.0 (Build 7) - 2026-11-25

### Phase 1.4: Unified Hardware Scheduling Interface (`DeviceCapabilities`)
- **Unified Capability Contract (`engine-core`)**: Added canonical `DeviceCapabilities` data class (`cpuCores`, `cpuFeatures`, `gpuBackends`, `npuAvailable`, `availableMemoryBytes`, `thermalStatus`), `HardwareCapabilityProvider` (`deviceCapabilities: StateFlow<DeviceCapabilities>` + synchronous `getDeviceCapabilities()`), and `DeviceCapabilitiesPolicy`.
- **Live Hardware Emission (`hardware-manager`)**: Upgraded `HardwareCapabilityManager` to implement `HardwareCapabilityProvider` and continuously emit unified `DeviceCapabilities` snapshots across hardware initialization, Vulkan/NPU probes, and `PowerManager` thermal transitions.
- **Engine Policy & Thermal Downshift (`engine-core` / `resource-manager`)**: Wired `HardwareCapabilityProvider` directly into `ResilientFailoverBackend` so `loadModel()` and `generate()` enforce GPU eligibility, low-memory/severe-thermal CPU failover, and automatic thread downshifting (`THERMAL_STATUS_MODERATE+`) while syncing snapshots into `RuntimeOrchestrator`.
- **Unified Diagnostics UI (`app`)**: Added `UnifiedCapabilitiesCard` inside Inference Settings showing the live 6-field `DeviceCapabilities` snapshot linked to scheduler routing and thermal guard status.

## v1.5.0 (Build 6) - 2026-11-25

### Phase 1.3: NPU / Hexagon Capability Detection & GGUF Compatibility Gate
- **Multi-Vendor NPU Probing (`hardware-manager`)**: Added `NpuCapabilityProbe` inspecting SoC manufacturer/model, chipset prefixes, OS NNAPI availability/deprecation (API 35+), and optional non-root JNI `dlopen` checks (`libQnnHtp.so` / `libcdsprpc.so`) for Qualcomm Snapdragon Hexagon HTP, MediaTek APU, Google Tensor TPU, Samsung Exynos NPU, and Android NNAPI.
- **Vendor-Agnostic NPU Backend Contract (`engine-core`)**: Added `NpuInferenceBackend`, `NpuVendorDriverAdapter`, `NpuProbeResult`, `ModelArtifactFormat`, `NpuCompatibilityReport`, and `EngineError.NpuBackendError` cleanly wired into `ResilientFailoverBackend`.
- **Automatic GGUF Compatibility Gate (`resource-manager` / `app`)**: Added `NpuCompatibilityEvaluator`, `"Run NPU Probe"` live diagnostics panel (`NpuDiagnosticsSection`) in Inference Settings, and local GGUF NPU compatibility gate badges in Models Screen explaining why `.gguf` files route via `Vulkan GPU → CPU`.

## v1.4.0 (Build 5) - 2026-11-25

### Phase 1.2: Native Vulkan GPU Acceleration & Safe CPU Failover
- **Modular Vulkan Backend (`engine-vulkan`)**: Added pluggable `VulkanLlamaBackend`, `VulkanNativeBridge`, `CMakeLists.txt`, and C++17 `quantchat_vulkan_jni.cpp` integrating `llama.cpp` `ggml-vulkan` (`arm64-v8a` built in GitHub Actions CI with safe `System.loadLibrary` fallback when omitted).
- **Resilient GPU → CPU Failover (`engine-core`)**: Added `ResilientFailoverBackend` that probes Vulkan readiness before GPU load, catches initialization or pre-decode runtime faults, tears down partial Vulkan handles, and falls back seamlessly to the existing `LlamaCppBackend` CPU path.
- **3-Layer Vulkan Probing & Self-Test (`hardware-manager`)**: Added `VulkanCapabilityProbe` inspecting Android `FEATURE_VULKAN_HARDWARE_LEVEL` / version, GPU vendor/memory heaps, `VK_KHR_16bit_storage` / `shaderFloat16`, and executing an actual GPU buffer allocation/transfer + `ggml-vulkan` check (`OpenCL` explicitly tracked as Phase 2).
- **Auto RAM/VRAM `n_gpu_layers` Planner & Controls (`resource-manager` / `app`)**: Added `GpuOffloadPlanner` respecting unified LPDDR safety headroom, DataStore-persisted 3-mode accelerator preference (`Auto`, `Prefer Vulkan`, `CPU Only`), manual layer slider, clickable Chat GPU/CPU status pill, and GPU probe action in Inference Settings.

## v1.3.0 (Build 4) - 2026-11-25

### Phase 1.1: Native CPU Cluster Scheduling & Thread Calibration
- **Sysfs CPU Cluster Probing (`hardware-manager`)**: Reads `/sys/devices/system/cpu/online`, `cpu_capacity`, `cpufreq/cpuinfo_max_freq`, and `topology/cluster_id` with non-root kernel restriction fallback to classify `Prime / Big / LITTLE` core groups.
- **4-Mode Adaptive CPU Scheduler (`resource-manager`)**: Implements `Eco`, `Balanced`, `Performance`, and `Thermal Safe` profiles with prompt/context workload classification (`LIGHT`, `STANDARD`, `LONG_CONTEXT`), dynamic per-request `n_threads` injection, and legal `Process.setThreadPriority` hints (no root ops or model reload required).
- **Live Thermal Protection**: Registers `PowerManager.OnThermalStatusChangedListener` (API 29+) to auto-downshift to `Thermal Safe` at `MODERATE`+ thermal states and restore when cooled.
- **1-Tap Multi-Thread Calibration & Quick Controls**: Sweeps topology-aware thread candidates via `InferenceBackend.benchmarkWithThreads(...)`, persists optimal thread count by device signature in DataStore, and adds a clickable CPU profile pill in Chat Detail plus full cluster matrix and calibration comparison table in Inference Settings.

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
