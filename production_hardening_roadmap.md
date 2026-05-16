# Chhanda AI: Production Hardening Roadmap

This document outlines the architectural, security, and performance refinements required to elevate the Chhanda AI Gateway to a production-grade on-device infrastructure.

---

## 1. Architectural Refactoring (God Object Decomposition)
**Objective**: Reduce complexity and improve testability of the `SystemViewModel`.

- [ ] **Extract Hardware Telemetry**: Create a `HardwareMonitor` class to handle CPU, RAM, Battery, and Thermal metrics.
- [ ] **Decouple Server Logic**: Extract `ServerOrchestrator` to manage Ktor lifecycle, port binding, and status reporting.
- [ ] **Isolate Network Logic**: Move IP scanning, VPN detection, and SSH Tunneling to a `NetworkManager`.
- [ ] **Provisioning Service**: Create a `ModelProvisioner` for model discovery, downloads, and HF token validation.
- [ ] **Domain Layer Isolation**: Move agentic logic (File generation, Thinking suppression) from `SendMessageUseCase` into standalone domain services.

---

## 2. Performance & Resource Optimization
**Objective**: Ensure system stability under high load and thermal stress.

- [ ] **Process Isolation (AIDL Transition)**
    - [ ] Define `IInferenceService.aidl` for remote control of the engine.
    - [ ] Define `IInferenceCallback.aidl` for streaming tokens across process boundaries.
    - [ ] Implement `InferenceService` running in `:inference` process.
    - [ ] Create `RemoteLLMEngine` proxy for seamless main-process integration.
    - [ ] Update Hilt `AppModule` for dynamic cross-process injection.
- [ ] **Dynamic Thermal Feedback**
    - [ ] Implement `ThermalStatusTracker` to monitor hardware temperature levels.
    - [ ] Implement `AdaptiveSampler` logic to reduce context/compute during heat spikes.
    - [ ] Add "Cooling Down" UI indicator during thermal throttling.
- [ ] **Vector Store Tiering**
    - [ ] Implement SQLite FTS5 (Full-Text Search) for rapid keyword pre-filtering.
    - [ ] Implement Two-Stage Retrieval (BM25 -> Cosine Similarity).
    - [ ] Implement "Context Pruning" to keep the most relevant 20% of chunks.
- [ ] **Low-Memory Strategy**
    - [ ] Implement `MemoryPressureMonitor` using `onTrimMemory`.
    - [ ] Implement automatic "Hibernation" (Session persistence + Engine shutdown).
    - [ ] Add "Wake on Demand" logic for incoming API requests.

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
    - [ ] Add "Revoke All Sessions" feature in the security settings.
- [x] **Local Encryption (HTTPS)**
    - [x] Generate self-signed SSL/TLS certificates on device.
    - [x] Update Ktor Server to support WSS (Secure WebSockets).
    - [ ] Add "Download Root CA" button for external clients (Browser/Desktop).
- [ ] **Privacy Guard**
    - [ ] Implement Regex-based PII (Personally Identifiable Information) scanner.
    - [ ] Add "Private Mode" toggle (disable all logging and history).

---

## 4. Senior UI/UX & Interaction Design
**Objective**: Create a premium, "wow-factor" interface with professional aesthetics.

- [x] **Advanced Visuals**
    - [x] Implement Glassmorphic `Box` wrappers using `RenderEffect`.
    - [x] Add Material You dynamic theming support.
    - [ ] Add "Ambient Glow" backgrounds that change with the active model's color.
    - [x] Implement `AnimatedContent` transitions between Dashboard and Chat.
- [ ] **Micro-Interactions**
    - [ ] Add `HapticFeedback` for token generation and file indexing.
    - [ ] Create a "Thinking Animation" (Suppressed text placeholder) for agentic reasoning.
    - [ ] Add "Scroll to Bottom" button with unread message badge.
- [ ] **Accessibility (A11y)**
    - [ ] Complete `contentDescription` audit for all icons.
    - [ ] Optimize `SemanticNode` tree for TalkBack users.

---

## 5. Observability & Maintainability
**Objective**: Provide professional diagnostics and stable maintenance.

- [ ] **Structured Logging**
    - [ ] Integrate `Timber` for context-aware logging.
    - [ ] Implement `LogReporter` to zip and share logs for debugging.
- [ ] **Analytics Dashboard**
    - [ ] Implement `TelemetryGraph` using Canvas for TPS/RAM visualization.
    - [ ] Add "Session Summary" showing total tokens/cost saved.
- [ ] **Self-Healing Infrastructure**
    - [ ] Implement `ServiceRestarter` using `WorkManager` for unexpected crashes.
    - [ ] Add "Reset Engine" button in the Dashboard for native-layer hangs.
- [ ] **Testing Suite**
    - [ ] Implement Unit Tests for `ContextManager` logic.
    - [ ] Implement UI Tests for the core "Send Message" flow.

---

## 6. Agentic & Human-Centric Features
**Objective**: Increase the utility of the AI for daily productivity.

- [ ] **Intelligent Discovery**
    - [ ] Implement `ModelScanner` to auto-detect `.bin`/`.gguf`/`.tflite` files.
    - [ ] Add "Model Comparison" view to see benchmarks (Speed vs Accuracy).
- [ ] **Proactive Suggestions**
    - [ ] Implement `ContextualSuggester` based on last 3 messages.
    - [ ] Add "Quick Action" buttons for Summarize, Translate, and Rewrite.
- [ ] **Enhanced File Handling**
    - [ ] Add Markdown rendering for RAG-extracted content.
    - [ ] Implement "Selective Knowledge" (Toggle specific PDFs on/off).

---
*Roadmap curated by Antigravity (Senior Google AI Architect & UI Developer)*
