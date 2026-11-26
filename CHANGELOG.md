# Changelog

## v1.10.0 (Build 11) - 2026-11-26

### Phase 2.4: `ParcelFileDescriptor + mmap` File Lifecycle Governance
- **File Integrity Verification (`model-manager` / `resource-manager`)**: Added `ModelFileVerifier` checking GGUF binary magic/version/tensor header bounds, exact byte size, and streaming SHA-256 digests (from Hugging Face `lfs.oid` and SAF imports), persisting `.verified.json` sidecar manifests and enforcing pre-load verification in `RuntimeOrchestrator`.
- **OS `FileChannel` File Locking (`engine-core`)**: Added `MmapLifecycleRegistry` coordinating ref-counted shared read locks (`FileChannel.tryLock(..., shared = true)`) across active `mmap` leases with exclusive `.lck` write locks during downloads, SAF imports, and safe deletion.
- **Unmap-Before-Unload Lifecycle (`engine-llamacpp` / `engine-vulkan`)**: Tracked `MmapLeaseHandle` and `ParcelFileDescriptor` ownership across native initialization and `unloadLocked()`, releasing native contexts, PFDs, and shared file locks deterministically on unload, fallback, or error.
- **Download Interruption Recovery (`app` / `model-manager`)**: Persisted `.part.checkpoint.json` manifests (repo/URL, expected size, SHA-256, ETag, disk offset), handled HTTP `Range` + `If-Range` (`206` append, `200` reset, verification-gated `416` promotion), and restored paused downloads on cold start.
- **Pre-Flight Storage Space Checks (`model-manager` / `app`)**: Added `StorageSpaceChecker` using Android `StatFs` with a 256 MiB / 5% safety buffer enforced prior to starting or resuming Hub downloads and SAF imports, surfaced via `DeviceStorageAndMmapCard` and Hub inspection warnings.
- **Active-Mapping Pre-Delete Guard (`model-manager` / `app`)**: Added `ModelFileLifecycleManager.deleteSafely` refusing file deletion while any active `mmap` lease or exclusive lock is held, plus `ActiveMmapDeleteConfirmDialog` prompting users to stop & unload before deleting mapped weights.

## v1.9.0 (Build 10) - 2026-11-26

### Phase 2.3: Foreground Services & WorkManager Background Decoupling
- **Activity-Independent Runtime (`app` / `system-integration`)**: Moved active download, model loading, and streaming inference execution into Application-scoped `QuantRuntimeHost` (`QuantChatApplication`) so `ViewModel.onCleared()` and Activity rotation no longer terminate active downloads or generation.
- **`ModelDownloadService` (`dataSync` FGS)**: Added foreground service with `FOREGROUND_SERVICE_DATA_SYNC`, ongoing progress/speed notification, Pause/Cancel `PendingIntent` actions, and Android 15 `onTimeout()` pause compliance.
- **`InferenceForegroundService` (`specialUse` FGS)**: Added foreground service with `FOREGROUND_SERVICE_SPECIAL_USE` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, live phase/token/backend notification, Stop action, and `onTaskRemoved()` persistence while inference is active.
- **`ModelMaintenanceWorker` (`WorkManager`)**: Scheduled periodic 24h + 1-tap immediate maintenance sweeps for expired `.part` cleanup, GGUF binary header verification, orphaned KV-cache pruning (`SessionCacheManager.pruneOrphanedCaches`), and Thermal Guard `Result.retry()` deferral (strictly excluded from real-time token generation).
- **Permissions & Background Launch Compliance**: Declared `FOREGROUND_SERVICE*` & `POST_NOTIFICATIONS` permissions, added Compose runtime notification permission request (`API 33+`), guarded background FGS starts against `ForegroundServiceStartNotAllowedException`, and surfaced `BackgroundServicesCard` in Inference Settings.

## v1.8.0 (Build 9) - 2026-11-25

### Phase 2.2: PowerManager Thermal Monitoring & 4-Tier Inference Governance
- **4-Tier Thermal Policy (`engine-core` / `hardware-manager`)**: Added `ThermalInferenceAction`, `ThermalPolicyDecision`, `DeviceCapabilitiesPolicy.evaluateThermalPolicy()`, `EngineError.ThermalShutdown`, and `PowerManager.getThermalHeadroom` telemetry across `THERMAL_STATUS_NONE`, `LIGHT`, `MODERATE`, `SEVERE`, and `CRITICAL`.
- **Load Reduction at `MODERATE`**: Automatically shifts scheduling to `THERMAL_SAFE` and halves Vulkan `n_gpu_layers` in `GpuOffloadPlanner`.
- **Speed Pacing & Task Pause at `SEVERE`**: Routes inference to CPU-only with <=2 background threads, adds cooperative inter-token pacing (`ResilientFailoverBackend`), and pauses/blocks non-essential tasks (benchmarks, thread calibration sweeps, and active model downloads).
- **Emergency Stop & Safe Release at `CRITICAL`**: Immediately cancels ongoing generation, blocks new inference, and calls `unloadModel()` inside `RuntimeOrchestrator` to safely release native weights & KV memory, surfaced via `ThermalStatusMatrixCard` and `ChatThermalGuardBanner`.

## v1.7.0 (Build 8) - 2026-11-25

### Phase 2.1: Memory Admission Control & 5-Term Runtime Budget
- **5-Term Memory Admission Model (`resource-manager`)**: Added `MemoryAdmissionPlanner` and `MemoryBudgetBreakdown` calculating `M_total = M_weights + M_KV + M_compute + M_runtime + M_reserve` from GGUF layer/KV/embedding metadata, `n_ctx`, `n_batch`, GPU offload staging, and Android LMK reserve thresholds instead of relying on nominal device RAM.
- **OS & Process Memory Probing (`hardware-manager`)**: Added `ProcessMemoryProbe` and `ProcessMemoryTelemetry` reading `ActivityManager.MemoryInfo` (`availMem`, `threshold`, `lowMemory`, `memoryClass`), `ActivityManager.getProcessMemoryInfo` (`totalPss`), native heap allocated size, and `/proc/self/status` `VmRSS`.
- **Strict Pre-Load Gate & Auto-Downgrade (`resource-manager` / `app`)**: Enforced `AdmissionVerdict.BLOCKED` inside `RuntimeOrchestrator.loadModel()` before native allocation, computing concrete safe downgrade candidates (`n_ctx` reduction and/or CPU-only 0 GPU layers).
- **Admission Diagnostics & Recovery UI (`app`)**: Added `MemoryAdmissionBreakdownStrip` on local GGUF cards, `ProcessAndAdmissionTelemetrySection` in Inference Settings, and `MemoryAdmissionBlockedDialog` with one-tap **Downgrade & Load**.

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
