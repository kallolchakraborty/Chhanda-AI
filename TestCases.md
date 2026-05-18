# Chhanda AI Gateway - Senior SDET Test Specification Suite

This document outlines the master test cases, validation steps, edge conditions, and corner-case mappings for the **Chhanda AI Local LLM Gateway** on Android. It acts as the core guide for regression testing, manual verification, and automated instrumentation test coverage.

---

## 📋 Test Matrix Overview

| Test ID | Test Category | Target Component | Validation Objective | Complexity |
| :--- | :--- | :--- | :--- | :--- |
| **TC-001** | Multimodal RAG | `TurnContextIngestor` | Extensionless MIME Resolution (`content://` URIs) | Medium |
| **TC-002** | Voice Loop | `VoiceAssistant` | continuous Dialogue & SpeechService Binder Re-use | High |
| **TC-003** | Conversational UX | `PersonaManager` | Short affirmation processing & brief dynamic responses | Low |
| **TC-004** | Offline UX | `DashboardScreen` | Hotspot trigger via local IP validation (`127.0.0.1`) | Medium |
| **TC-005** | Chat Memory | `SendMessageUseCase` | Hidden XML `<turn_context>` parsing & history recall | High |

---

## 🛠️ Step-by-Step Test Specification

### TC-001: Robust Multimodal MIME Ingestion (SDET Corner-Case)
*   **Objective**: Ensure that files lacking suffixes (e.g. from Google Drive `content://` URIs) are correctly classified and parsed rather than defaulting to plain text.
*   **Pre-conditions**: Device connected, local model loaded.
*   **Test Steps**:
    1. Open Chhanda Chat Screen.
    2. Attach a PDF file named `financial_sheet` (no extension) directly from Google Drive / Android Document Provider.
    3. Ask the LLM: *"Summarize this document"*
    4. Attach an image named `photo_frame` (no extension, MIME type `image/jpeg`).
    5. Ask: *"What text is in this image?"*
*   **Expected Pass Criteria**:
    *   The app does NOT crash.
    *   The PDF is ingested via Apache PDFBox/MLKit (`DocType.PDF`) rather than falling back to text.
    *   The image is ingested via `DocType.IMAGE` and OCR successfully runs.

### TC-002: Continuous Voice Loop Execution
*   **Objective**: Validate that voice interactions run continuously and hands-free without stalling or silent failure after the first turn.
*   **Pre-conditions**: Microphone permission granted.
*   **Test Steps**:
    1. Tap the Microphone Icon on the Chat Screen to enter continuous Voice Mode.
    2. Ask: *"What is the weather?"*
    3. Wait for the TTS engine to finish speaking the entire weather report.
    4. Observe the mic icon.
    5. Immediately say: *"What was the speed of the wind you mentioned?"* (without tapping the screen).
*   **Expected Pass Criteria**:
    *   The microphone icon starts pulsing again immediately after speech completes.
    *   No binder deadlocks or `ERROR_RECOGNIZER_BUSY` crashes.
    *   The model listens, captures the second query, and responds in kind.

### TC-003: Conversational Dynamics & Brevity on Affirmations
*   **Objective**: Prevent verbose, rambling paragraphs when the user only gives a short positive acknowledgment.
*   **Pre-conditions**: None.
*   **Test Steps**:
    1. Send a standard prompt: *"Describe a red apple."* (Get response).
    2. Respond with a simple acknowledgment: *"OK"* or *"Understood"* or *"Got it"*.
*   **Expected Pass Criteria**:
    *   The LLM's response is extremely brief (e.g., *"Glad to help! Is there anything else I can help you with?"*).
    *   The response does NOT repeat facts about apples or list bullet points.

### TC-004: Offline Hotspot Prompt Activation (No Network Loop)
*   **Objective**: Ensure that the QR Code configuration forces a local hotspot instruction if the user has no local network connection.
*   **Pre-conditions**: Wi-Fi disconnected (only Mobile Data or Airplane Mode).
*   **Test Steps**:
    1. Go to the Dashboard Screen.
    2. Click the QR Code Sharing card under Connectivity.
*   **Expected Pass Criteria**:
    *   The app recognizes that the active local IP is strictly `127.0.0.1` (localhost).
    *   The app triggers the **No Local Network / Hotspot Config Prompt**.
    *   Clicking "Confirm & Show QR" opens Android Hotspot settings cleanly.

### TC-005: Chat History & Context Memory Recall (Long-term Context)
*   **Objective**: Confirm that the AI retains precise memories of what was attached in previous messages during subsequent follow-up queries.
*   **Pre-conditions**: Load a standard local model.
*   **Test Steps**:
    1. Upload an image containing a specific sentence (e.g., *"The project deadline is June 15"*).
    2. Type: *"Store this information."* (Get response).
    3. Type a secondary prompt completely unrelated: *"Tell me a joke."* (Get response).
    4. Type: *"What was the deadline in the image I showed you earlier?"*
*   **Expected Pass Criteria**:
    *   The Chat bubble remains visually clean (no raw image text shown in the user's bubble).
    *   The LLM successfully reads the history containing the hidden `<turn_context>` and answers: *"The deadline is June 15"*.

---

## 🔄 SDET Self-Healing Regression Suite

To execute a fresh clean validation run locally, use the following SDET deployment pipeline:

```bash
# 1. Fully uninstall existing instance to clear cached storage
adb uninstall com.chhanda.ai

# 2. Build a fresh debug package
./gradlew assembleDebug

# 3. Re-install fresh build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
