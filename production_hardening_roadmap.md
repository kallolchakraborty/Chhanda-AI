# Chhanda AI: Production Hardening Roadmap

This document outlines the architectural, security, and performance refinements required to elevate the Chhanda AI Gateway to a production-grade on-device infrastructure.

---

## 1. Architectural Refactoring (God Object Decomposition)
**Objective**: Reduce complexity and improve testability of the `SystemViewModel`.

- [x] **Extract Hardware Telemetry**: Create a `HardwareMonitor` class to handle CPU, RAM, Battery, and Thermal metrics.
- [x] **Decouple Server Logic**: Extract `ServerOrchestrator` to manage Ktor lifecycle, port binding, and status reporting.
- [x] **Isolate Network Logic**: Move IP scanning, VPN detection, and SSH Tunneling to a `NetworkManager`.
- [x] **Provisioning Service**: Create a `ModelProvisioner` for model discovery, downloads, and HF token validation.
- [x] **Domain Layer Isolation**: Move agentic logic (File generation, Thinking suppression) from `SendMessageUseCase` into standalone domain services.

---

## 2. Performance & Resource Optimization
**Objective**: Ensure system stability under high load and thermal stress.

- [x] **Process Isolation (AIDL Transition)**
    - [x] Define `IInferenceService.aidl` for remote control of the engine.
    - [x] Define `IInferenceCallback.aidl` for streaming tokens across process boundaries.
    - [x] Implement `InferenceService` running in `:inference` process to prevent main-process death.
    - [x] Create `RemoteLLMEngine` proxy for seamless main-process integration.
    - [x] Update Hilt `AppModule` for dynamic cross-process injection.
- [x] **Native Stability (Crash-Proofing)**
    - [x] Implement `NativeCrashInterceptor` to capture C++ SIGSEGV/SIGBUS errors.
    - [x] Add `ANRWatchdog` to monitor main-thread responsiveness.
    - [x] Implement `AutomaticRecovery` logic to restart the inference process without app restart.
- [x] **Dynamic Thermal Feedback**
    - [x] Implement `ThermalStatusTracker` to monitor hardware temperature levels.
    - [x] Implement `AdaptiveSampler` logic to reduce context/compute during heat spikes.
    - [x] Add "Cooling Down" UI indicator during thermal throttling.
- [x] **Vector Store Tiering**
    - [x] Implement SQLite FTS5 (Full-Text Search) for rapid keyword pre-filtering.
    - [x] Implement Two-Stage Retrieval (BM25 -> Cosine Similarity).
    - [x] Implement "Context Pruning" to keep the most relevant context chunks.
- [x] **Low-Memory Strategy**
    - [x] Implement `MemoryPressureMonitor` using `onTrimMemory`.
    - [x] Implement automatic "Hibernation" (Session persistence + Engine shutdown).
    - [x] Add "Wake on Demand" logic for incoming API requests.

---

## 3. Security & Privacy Hardening
**Objective**: Protect user data and prevent unauthorized gateway access.

- [x] **Hardware-Backed Credentials**
    - [x] Implement `MasterKey` generation via Android Keystore.
    - [x] Implement `EncryptedSharedPreferences` or `EncryptedDataStore` for secrets.
    - [x] Migrate HuggingFace tokens and custom API keys to secure storage.
- [x] **Gateway Access Control**
    - [x] Integrate `BiometricPrompt` for Dashboard and Chat History access.
    - [x] Implement "Local Subnet Only" validation in Ktor Server.
    - [x] Add "Revoke All Sessions" feature in the security settings.
- [x] **Local Encryption (HTTPS)**
    - [x] Generate self-signed SSL/TLS certificates on device.
    - [x] Update Ktor Server to support WSS (Secure WebSockets).
    - [x] Add "Download Root CA" button for external clients (Browser/Desktop).
- [x] **Privacy Guard**
    - [x] Implement Regex-based PII (Personally Identifiable Information) scanner (Emails, Phones, CC).
    - [x] Add "Privacy Shield" toggle in settings to redact sensitive data from output.
- [x] Implement Web Template XSS Hardening (Hardened markdown parser).

---

## 4. Senior UI/UX & Interaction Design
**Objective**: Create a premium, "wow-factor" interface with professional aesthetics.

- [x] **Advanced Visuals**
    - [x] Implement Glassmorphic `Box` wrappers using `RenderEffect`.
    - [x] Add Material You dynamic theming support.
    - [x] Add "Ambient Glow" backgrounds that change with the active model's color.
    - [x] Implement `AnimatedContent` transitions between Dashboard and Chat.
- [x] **Micro-Interactions**
    - [x] Add subtle haptic feedback for streaming tokens.
    - [x] Implement contextual progress logs for agentic actions (e.g., "Indexing PDF...", "Generating Word Doc...").
    - [x] Add "Thinking" indicator with pulse animation for complex reasoning steps.
    - [x] Add "Scroll to Bottom" button with unread message badge.
- [x] **Tier 1: Stability Hardening (CRITICAL)** [COMPLETED]
    - [x] Implement centralized `CrashHandler` (Thread.UncaughtExceptionHandler) to intercept native/Java crashes.
    - [x] Implement `ANRWatchdog` to monitor UI main thread health.
    - [x] Centralize all error telemetry into `AppLogManager`.
- [x] **Tier 2: Premium UI/UX (VISUAL)** [COMPLETED]
    - [x] Implement Skeleton Screens (Shimmer effect) for model lists and RAG ingestion.
    - [x] Enable full Edge-to-Edge support for immersive visuals.
    - [x] Setup Shared Element Transitions for model identity between Dashboard and Chat.
    - [x] Implement **Tactile Haptics** using custom `VibrationEffect` patterns.

---

## 5. Observability & Maintainability
**Objective**: Provide professional diagnostics and stable maintenance.

- [x] **Structured Logging**
    - [x] Integrate `AppLogManager` for context-aware logging.
    - [x] Implement `LogReporter` to zip and share logs for debugging.
- [x] **Analytics Dashboard**
    - [x] Implement `TelemetryGraph` using Canvas for TPS/RAM visualization.
    - [x] Add "Session Summary" showing total tokens/cost saved.
- [x] **Self-Healing Infrastructure**
    - [x] Implement `ServerOrchestrator` health-checks for unexpected port hangs.
    - [x] Add "Reset Engine" button in the Dashboard for native-layer hangs.
    - [x] Implement Fail-proof Port Verification (Automatic port increment on bind failure).
- [x] **Testing Suite**
    - [x] Implement Unit Tests for `ContextManager` logic.
    - [x] Implement UI Tests for the core "Send Message" flow.

---

## 6. Agentic & Human-Centric Features
**Objective**: Increase the utility of the AI for daily productivity.

- [x] **Intelligent Discovery**
    - [x] Implement `ModelScanner` within `ModelProvisioner` to auto-detect `.bin`/`.gguf`/`.litertlm` files.
- [x] **Comparative Intelligence**
    - [x] Implement "Model Comparison" view (Speed vs Accuracy).
    - [x] Add "Proactive Suggestions" based on model capabilities.
    - [x] Add "Quick Action" buttons for Summarize, Translate, and Rewrite.
- [x] **Enhanced File Handling**
    - [x] Add Markdown rendering for RAG-extracted content and agentic responses.
    - [x] Implement "Selective Knowledge" (Toggle specific PDFs on/off).
- [x] **Productivity & Integration**
    - [x] Implement **On-Device STT (Speech-to-Text)** for voice-controlled chat.
    - [x] Add **Multi-Modal Support** (Vision) for image analysis (Llava/Moondream).
    - [x] Implement **Encrypted Cloud Sync** (Google Drive) for cross-device history.
- [x] Implement **Automated Database Migrations** (Room AutoMigrations).

---

## 7. Enterprise-Grade Hardening & Optimization (Tier 4)
**Objective**: Transition from "Feature-Complete" to "Enterprise-Grade Reliability".

- [x] **Thread Concurrency & Flow Optimization**
    - [x] Offload heavy `combine` transformations in `SystemViewModel` to `Dispatchers.Default`.
    - [x] Migrate global preferences to `SharingStarted.Eagerly` to prevent UI flickering.
- [x] **Native & Memory Stability**
    - [x] Implement `NativeLifecycleObserver` for synchronized JNI resource release.
    - [x] Implement `MemoryPressureMonitor` for adaptive bitmap scaling in Multimodal RAG.
    - [x] Implement "Active Thermal Throttling" (Token delays/parameter reduction during heat spikes).
- [x] **Network & Resource Hygiene**
    - [x] Implement "Startup Cache Cleanup" for orphaned web upload temp files.
    - [x] Refactor `Runtime.exit(0)` to a smooth `Activity.recreate()` flow.
- [x] **UI/UX & Accessibility Hardening**
    - [x] Apply `Modifier.semantics(mergeDescendants = true)` to all core cards for TalkBack.
    - [x] Refactor nested interaction targets in `ConfigScreen` (e.g., TTS samples).

---

*Roadmap curated by Antigravity (Senior Google AI Architect & UI Developer)*
