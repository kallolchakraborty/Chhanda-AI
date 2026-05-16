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

- [ ] **Process Isolation**: Move the `LiteRTLMEngine` to a separate Android process (`:inference`) to isolate native crashes from the UI.
- [ ] **Dynamic Thermal Feedback**: Implement a control loop that adjusts inference parameters (Context length, Sampler settings) based on thermal status.
- [ ] **Vector Store Tiering**: Implement a two-stage search (Text-based pre-filter -> Vector-based similarity) to handle large knowledge bases without OOM.
- [ ] **Low-Memory Strategy**: Implement an automatic "Hibernation" mode that closes the engine after X minutes of background inactivity.

---

## 3. Security & Privacy Hardening
**Objective**: Protect user data and prevent unauthorized gateway access.

- [ ] **Hardware-Backed Credentials**: Move API Keys and HF Tokens to the **Android Keystore** (encrypted at rest).
- [ ] **Gateway Access Control**: 
    - [ ] Implement Biometric (Face/Fingerprint) lock for the Dashboard.
    - [ ] Restrict Ktor CORS to local subnet patterns (192.168.x.x).
- [ ] **Local Encryption**: Implement self-signed TLS/SSL for the Ktor server to protect data in transit on open networks.
- [ ] **Privacy Guard**: Enhance the `SafetyGuardrails` to automatically redact sensitive patterns (Credit Cards, SSNs) from LLM output.

---

## 4. Senior UI/UX & Interaction Design
**Objective**: Create a premium, "wow-factor" interface with professional aesthetics.

- [ ] **Advanced Visuals**:
    - [ ] Implement Glassmorphic (blurred/frosted) containers using `RenderEffect` (API 31+).
    - [ ] Add smooth Material You dynamic theming.
- [ ] **Micro-Interactions**:
    - [ ] Add subtle haptic feedback for streaming tokens.
    - [ ] Implement contextual progress logs for agentic actions (e.g., "Indexing PDF...", "Generating Word Doc...").
- [ ] **Accessibility (A11y)**: Complete the content-description audit and ensure focus-order is optimized for screen readers.

---

## 5. Observability & Maintainability
**Objective**: Provide professional diagnostics and stable maintenance.

- [ ] **Structured Logging**: Integrate `Timber` + `Logback` for exportable diagnostic logs.
- [ ] **Analytics Dashboard**: Add real-time performance graphs for TPS, Latency, and Memory overhead.
- [ ] **Self-Healing Infrastructure**: Use `WorkManager` to ensure background services (Server, Tunnel) recover automatically from system kills.
- [ ] **Testing Suite**: Implement unit tests for critical paths: `VectorStore` search, `ContextManager` pruning, and `SendMessageUseCase` logic.

---

## 6. Agentic & Human-Centric Features
**Objective**: Increase the utility of the AI for daily productivity.

- [ ] **Intelligent Discovery**: Automatically detect and de-duplicate local model files across shared folders.
- [ ] **Proactive Suggestions**: Add a "Smart Actions" bar that suggests follow-up prompts based on the current conversation context.
- [ ] **Enhanced File Handling**: Support for more document types (e.g., Markdown, CSV) in the RAG pipeline.

---
*Roadmap curated by Antigravity (Senior Google AI Architect & UI Developer)*
