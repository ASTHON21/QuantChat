# QuantChat

A 100% offline Android GGUF local AI chat application built with Kotlin, Jetpack Compose, Material 3, Room, and bundled `llama.cpp` JNI/NDK inference.

- **Zero Cloud / Zero Firebase Backend**: All conversations, downloaded weights, and inference run locally on-device. No telemetry, analytics, or remote backend keys are compiled into the app.
- **On-Device GGUF Engine**: Uses `llamacpp-kotlin` (`org.nehuatl.llamacpp.LlamaAndroid`) for streaming token generation, stop-generation control, context size / thread / temperature tuning, and automatic chat-template formatting (`Qwen3.5`, `Qwen3`, `Gemma 3`, `Phi-4`).
- **Model Management**:
  - Pre-configured official 3.8B–4B models (`Qwen3.5-4B`, `Qwen3-4B`, `Gemma-3-4B`, `Phi-4-mini-3.8B`).
  - On-demand Hugging Face Hub GGUF model search (network permission checked only when you trigger a search) and resumable/cancellable streaming downloads via OkHttp.
  - Zero-permission Android Storage Access Framework (SAF) import for existing `.gguf` files on your device (with `GGUF` magic header validation).
- **Local Persistence**: Conversations and messages stored locally in Room (`AppChatDatabase`); inference parameters stored in Jetpack DataStore.

## Project Structure

- `app/src/main/java/com/example/data/LocalLlamaRepository.kt` — `llama.cpp` native engine lifecycle & streaming token inference
- `app/src/main/java/com/example/data/HuggingFaceModelRepository.kt` — HF Hub search, streaming GGUF download, SAF `.gguf` import, DataStore settings
- `app/src/main/java/com/example/data/local/ChatDatabase.kt` — Room database (`ChatThreadEntity`, `ChatMessageEntity`, `ChatRepository`)
- `app/src/main/java/com/example/ui/viewmodel/QuantTerminalViewModel.kt` — StateFlow state management across Chats & Models
- `app/src/main/java/com/example/ui/screens/` — `ChatsScreen.kt`, `ChatDetailScreen.kt`, `ModelsScreen.kt`
- `.github/workflows/build-apk.yml` — Automated CI/CD test + APK build + GitHub Release publishing

## Automated APK Builds (GitHub Actions)

Every push to `main`/`master` or `v*` tag automatically runs `.github/workflows/build-apk.yml`:

1. Runs JVM/Robolectric unit tests (`gradle :app:testDebugUnitTest`).
2. Builds `QuantChat-debug.apk` signed for direct Android sideloading.
3. Publishes the raw `.apk` directly to **GitHub Releases** (`latest` rolling build on branch pushes, or versioned release on `v*` tags) and as a workflow artifact under **Actions**.

### Optional: Signed Release APKs

To also build a `QuantChat-release.apk` in CI, add the following repository secrets in **GitHub → Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded `.jks` upload keystore (with key alias `upload`)
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: Key password

## Local Development

Requirements: JDK 21, Android SDK (API 36), Gradle 9.3.1.

```bash
# Run unit & Robolectric tests
gradle :app:testDebugUnitTest

# Build directly installable debug APK
gradle :app:assembleDebug
```
