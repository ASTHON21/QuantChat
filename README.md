# QuantChat

A 100% offline Android GGUF local AI chat application built with Kotlin, Jetpack Compose, Material 3, Room, and modular native `llama.cpp` (CPU + Vulkan GPU) runtime orchestration.

- **Zero Cloud / Zero Firebase Backend**: All conversations, downloaded weights, KV-cache snapshots, and inference run locally on-device.
- **Modular Multi-Engine Runtime**:
  - `engine-core`: Unified `InferenceBackend`, `ResilientFailoverBackend` (GPU → CPU failover + thermal pacing), `NpuInferenceBackend` abstraction, `DeviceCapabilities` & `DeviceCapabilitiesPolicy`, and `MmapLifecycleRegistry` (`FileChannel` `FileLock` + PFD/`mmap` lease tracking).
  - `engine-llamacpp`: Native `llamacpp-kotlin` CPU inference, streaming token generation, KV-cache session save/restore, and benchmarking.
  - `engine-vulkan`: Modular C++17 JNI `ggml-vulkan` GPU acceleration and live buffer/FP16 probing.
  - `hardware-manager`: Sysfs Prime/Big/LITTLE CPU cluster probing, NPU/Hexagon/APU/NNAPI capability detection, `ProcessMemoryProbe`, and `PowerManager` 4-tier thermal monitoring + headroom forecasting.
  - `resource-manager`: Adaptive 4-mode CPU scheduler, `GpuOffloadPlanner`, 5-term `MemoryAdmissionPlanner`, and `ThermalGovernor` coordinated via `RuntimeOrchestrator`.
  - `model-manager` & `session-manager`: Binary GGUF header metadata parser (`GgufHeaderParser`), `ModelFileVerifier` (GGUF + SHA-256), `StorageSpaceChecker` (`StatFs`), `ModelFileLifecycleManager` (active-mmap safe delete), `.part.checkpoint.json` resume manifests, `n_ctx` token budgeter, and thread KV-cache persistence/pruning.
  - `system-integration`: `ModelDownloadService` (`dataSync` FGS), `InferenceForegroundService` (`specialUse` FGS), `ModelMaintenanceWorker` (`WorkManager`), notification channels, and Share/Process-Text intent routing.
  - `local-server`: Loopback OpenAI-compatible router (`LocalOpenAiRouter` / `LocalHttpServer`).
- **Application-Scoped Decoupling (`QuantRuntimeHost`)**: Long-running model downloads and streaming inference survive Activity rotation and backgrounding via Foreground Services, while deferrable GGUF verification, expired `.part` cleanup, and orphan KV-cache pruning run via `WorkManager`.

## Automated APK Builds & Releases (GitHub Actions)

App versioning is managed centrally in `version.properties` (`VERSION_NAME`, `VERSION_CODE`) and `CHANGELOG.md`. Every push to `main`/`master` or `v*` tag runs `.github/workflows/build-apk.yml`:

1. Runs JVM/Robolectric unit tests (`gradle :app:testDebugUnitTest`).
2. Builds `QuantChat-v<VERSION_NAME>-debug.apk` (and `QuantChat-v<VERSION_NAME>-release.apk` if signing secrets exist) with `SHA256SUMS.txt`.
3. Publishes a formal versioned **GitHub Release** (`v<VERSION_NAME>`, `prerelease: false`, marked as Latest).

## Local Development

Requirements: JDK 21, Android SDK (API 36), Gradle 9.3.1.

```bash
# Run unit & Robolectric tests
gradle :app:testDebugUnitTest

# Build directly installable debug APK
gradle :app:assembleDebug
```
