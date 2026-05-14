# ❓ Chhanda AI - Deep Dive & FAQ

## 🔒 Privacy & Security

### 1. Is Chhanda really 100% offline?
**Yes.** Once the app and models are downloaded, Chhanda requires zero internet for inference, RAG (document search), and chat. 
*   **Code Reference**: `LiteRTLMEngine.kt` handles model loading entirely via native JNI calls without any network stack.
*   **Exception**: The initial "Scraping" phase of a website URL requires internet to fetch the HTML content. Once fetched, the data is processed and stored locally.

### 2. How is my privacy protected during API interactions?
**Source-Based Ephemerality.**
*   **Local & Web (QR) Chat**: Stored in `chat_history` table for persistence.
*   **API Access**: Any request where `source == "api"` is processed without being saved to the database.
*   **Code Reference**: `SendMessageUseCase.kt` (Line 156) checks the `source` parameter to determine session persistence and persona.

### 3. How does the Captive Portal protect the Gateway?
If an unauthorized device on your network tries to access common paths (like `/generate_204` used by Android or `/hotspot-detect.html` used by iOS), Chhanda intercepts these and redirects them to the secure Login/Handshake page.
*   **Code Reference**: `ChhandaServer.kt` (Lines 442-513) implements standard captive portal redirection routes.

---

## 🚀 Performance & Orchestration

### 4. How does Chhanda handle multiple users at once?
Chhanda implements an internal **Load Balancer** that routes incoming requests (from the host device, the web gateway, or the API) to available inference replicas.
*   **Code Reference**: `SendMessageUseCase.kt` (Lines 41-43) utilizes a `LoadBalancer` class to manage multi-client concurrency.

### 5. Why do I see "App RAM" in GBs?
Chhanda monitors **PSS (Proportional Set Size)**, which includes both the Java heap and the **Native Memory** used by the LLM model. Standard RAM monitors often miss the massive native footprint of the active model.
*   **Code Reference**: `RAGMetricsManager.kt` (Line 82) uses `android.os.Debug.getMemoryInfo()` to capture the full native memory usage.

---

## 🧠 Knowledge Base & RAG

### 6. How does Chhanda decide which documents are relevant?
Chhanda uses an **Adaptive Similarity Engine** with two precision tiers based on cosine similarity scores:
*   **Tier 1 (High Precision - 0.82)**: Default for general queries to minimize hallucinations.
*   **Tier 2 (Deep Discovery - 0.65)**: Activated when explicit search keywords (e.g., "in the attachment", "find in file") are detected.
*   **Code Reference**: `ContextManager.kt` (Line 52) implements this dual-threshold logic.

### 7. How is document structure preserved during indexing?
Chhanda uses a **Paragraph-First Chunking Strategy**. It first identifies logical semantic blocks (paragraphs) before splitting by sentence or character length to ensure context isn't lost mid-thought.
*   **Code Reference**: `TextChunker.kt` (Lines 11-12) uses regex splitting on line breaks to preserve paragraph boundaries.

---

## 📡 AI Server Architecture

### 8. What engine powers the Chhanda AI Server?
We use **Ktor** with the **CIO (Coroutine-based I/O)** engine.
*   **Optimization**: We specifically chose **CIO** over **Netty** for Android because Netty's native `tcnative` libraries are often missing or incompatible with various Android architectures, leading to silent binding failures. CIO is 100% Kotlin-native and highly performant.
*   **Code Reference**: `ChhandaServer.kt` (Lines 45-53) explains the rationale for this architecture.

### 9. How does Chhanda ensure the server is actually reachable?
Since Ktor's `start(wait=false)` is asynchronous, Chhanda implements a **TCP Self-Probe Loop**. The app attempts to "ping" its own server across all network interfaces for 1.8 seconds to confirm reachability before showing the "Online" status to the user.
*   **Code Reference**: `ChhandaServer.kt` (Lines 199-225) implements this multi-interface reachability probe.

### 10. How does the "Public URL" (Tunneling) work?
Chhanda uses **JSch** to create a zero-config SSH tunnel via **localhost.run**. This allows you to access your local AI gateway from anywhere in the world without port forwarding on your router.
*   **Code Reference**: `ChhandaServer.kt` (Lines 284-293) manages the SSH session and remote port forwarding.

### 11. What libraries are used in the backend?
*   **Ktor**: Core HTTP server and SSE (Server-Sent Events) streaming.
*   **Kotlin Serialization**: Optimized, reflection-free JSON processing.
*   **JSch**: Secure SSH tunneling.
*   **ExoPlayer**: High-performance audio streaming for TTS responses.
*   **Apache POI**: Local, offline Office document parsing (.docx, .xlsx).

---

## 🛠️ Advanced Settings

### 12. Why does the server restart when I change the app language?
**Localization Safety Protocol.** To ensure the Web Gateway, API responses, and TTS all reflect your new language choice, Chhanda resets active server instances and clears the inference cache.
*   **Code Reference**: `SystemViewModel.kt` (Line 1512) triggers a server restart and model re-initialization when the `appLanguage` setting is updated.

### 13. What is the "Reaper" service?
The **Active Reaper** is a background coroutine that monitors heartbeats from all connected web clients. If a client stops sending heartbeats (e.g., they closed the browser tab), the Reaper purges them after 30 seconds to free up system resources.
*   **Code Reference**: `ChhandaServer.kt` (Lines 248-267) implements the `startReaper` lifecycle management.

---

**Technical Troubleshooting**: For real-time diagnostics, check the **System Logs** in the Dashboard and search for `ChhandaAudit` tags in Logcat.
