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

### 4. How does the internal Load Balancer work?
Chhanda uses a custom **Prefix-Hash Load Balancer** to manage multiple incoming requests concurrently.
*   **Locality (Prompt Affinity)**: The balancer hashes the first 50 characters of a prompt to route similar queries to the same model replica. This maximizes **KV-Cache** reuse, significantly reducing the Time To First Token (TTFT).
*   **Fairness (Least-Loaded Spillover)**: Each model replica has a concurrency bound. If the "affinity" replica is busy, the request is automatically routed to the replica with the lowest active load.
*   **Code Reference**: `SendMessageUseCase.kt` (Lines 398-438) implements the `LoadBalancer` private class using thread-safe synchronization.

### 5. Why do I see "App RAM" in GBs?
Chhanda monitors **PSS (Proportional Set Size)**, which includes both the Java heap and the **Native Memory** used by the LLM model. Standard RAM monitors often miss the massive native footprint of the active model.
*   **Code Reference**: `RAGMetricsManager.kt` (Line 82) uses `android.os.Debug.getMemoryInfo()` to capture the full native memory usage.

### 6. Can I use Chhanda as a system-wide API?
**Yes.** Chhanda exposes a standard REST/SSE API protected by an API Key. You can point your local automation scripts or IDE plugins (like Continue) to Chhanda's IP and port.
*   **Code Reference**: `ChhandaServer.kt` defines the `POST /v1/chat/completions` endpoint (compatible with OpenAI-style headers) to facilitate easy integration.

---

## 🧠 Knowledge Base & RAG

### 7. How does Chhanda decide which documents are relevant?
Chhanda uses an **Adaptive Similarity Engine** with two precision tiers based on cosine similarity scores:
*   **Tier 1 (High Precision - 0.82)**: Default for general queries to minimize hallucinations.
*   **Tier 2 (Deep Discovery - 0.65)**: Activated when explicit search keywords (e.g., "in the attachment", "find in file") are detected.
*   **Code Reference**: `ContextManager.kt` (Line 52) implements this dual-threshold logic.

### 8. How is document structure preserved during indexing?
Chhanda uses a **Paragraph-First Chunking Strategy**. It first identifies logical semantic blocks (paragraphs) before splitting by sentence or character length to ensure context isn't lost mid-thought.
*   **Code Reference**: `TextChunker.kt` (Lines 11-12) uses regex splitting on line breaks to preserve paragraph boundaries.

### 9. How are long-running tasks handled without slowing down the app?
Heavy tasks like PDF vectorization or web scraping are offloaded to **WorkManager** in a foreground service. This allows them to continue even if you minimize the app.
*   **Code Reference**: `IngestionWorker.kt` implements `CoroutineWorker` and uses `setForeground()` to ensure the OS doesn't kill the process during deep indexing.

---

## 📡 AI Server Architecture

### 10. What engine powers the Chhanda AI Server?
We use **Ktor** with the **CIO (Coroutine-based I/O)** engine.
*   **Optimization**: We specifically chose **CIO** over **Netty** for Android because Netty's native `tcnative` libraries are often missing or incompatible with various Android architectures, leading to silent binding failures. CIO is 100% Kotlin-native and highly performant.
*   **Code Reference**: `ChhandaServer.kt` (Lines 45-53) explains the rationale for this architecture.

### 11. How does Chhanda ensure the server is actually reachable?
Since Ktor's `start(wait=false)` is asynchronous, Chhanda implements a **TCP Self-Probe Loop**. The app attempts to "ping" its own server across all network interfaces for 1.8 seconds to confirm reachability before showing the "Online" status to the user.
*   **Code Reference**: `ChhandaServer.kt` (Lines 199-225) implements this multi-interface reachability probe.

### 12. How does the "Public URL" (Tunneling) work?
Chhanda uses **JSch** to create a zero-config SSH tunnel via **localhost.run**. This allows you to access your local AI gateway from anywhere in the world without port forwarding on your router.
*   **Code Reference**: `ChhandaServer.kt` (Lines 284-293) manages the SSH session and remote port forwarding.

---

## 💬 Chat & Personas

### 13. Why does the AI sound like a "Senior Software Engineer" in my IDE?
Chhanda performs **Source-Aware Personality Adaptation**. When it detects a request coming via the API (likely from a developer), it injects a technical system role to provide expert-level code reviews and architectural advice.
*   **Code Reference**: `SendMessageUseCase.kt` (Line 157) injects the `SENIOR SOFTWARE ENGINEER` persona when the source is `api`.

### 14. Can I seek through the AI's voice responses?
**Yes.** The media engine includes a global playback bar with 10s forward/backward seeking and background playback support.
*   **Code Reference**: `TtsPlayer.kt` manages the `ExoPlayer` state and provides the seeking interface used in the Chat UI.

---

## 🛠️ Advanced Settings & Lifecycle

### 15. What is the "Reaper" service?
The **Active Reaper** is a background coroutine that monitors heartbeats from all connected web clients. If a client stops sending heartbeats (e.g., they closed the browser tab), the Reaper purges them after 30 seconds to free up system resources.
*   **Code Reference**: `ChhandaServer.kt` (Lines 248-267) implements the `startReaper` lifecycle management.

### 16. Why does the server restart when I change the app language?
**Localization Safety Protocol.** To ensure the Web Gateway, API responses, and TTS all reflect your new language choice, Chhanda resets active server instances and clears the inference cache.
*   **Code Reference**: `SystemViewModel.kt` (Line 1512) triggers a server restart and model re-initialization when the `appLanguage` setting is updated.

---

**Technical Troubleshooting**: For real-time diagnostics, check the **System Logs** in the Dashboard and search for `ChhandaAudit` tags in Logcat.
