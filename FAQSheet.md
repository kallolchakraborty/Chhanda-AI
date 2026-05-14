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

## 🚀 Performance & Hardware

### 4. Why is the AI responding slowly on my device?
Inference speed is measured in **TPS (Tokens Per Second)**. On Android, this is hardware-accelerated via GPU/NPU.
*   **Code Reference**: `SystemViewModel.kt` (Lines 1067-1075) identifies your processor (Snapdragon, Tensor, etc.) and optimizes the LiteRT execution delegate accordingly.

### 5. Why do I see "App RAM" in GBs?
Chhanda monitors **PSS (Proportional Set Size)**, which includes both the Java heap and the **Native Memory** used by the LLM model. Since LiteRT models are loaded in native memory, standard RAM monitors often show incorrect (lower) usage.
*   **Code Reference**: `RAGMetricsManager.kt` (Line 82) uses `android.os.Debug.getMemoryInfo()` to capture the full native footprint.

---

## 🧠 Knowledge Base & RAG

### 6. How does Chhanda decide which documents are relevant?
Chhanda uses an **Adaptive Similarity Engine** with two precision tiers:
*   **Tier 1 (High Precision - 0.82)**: Used for general queries to prevent irrelevant knowledge injection.
*   **Tier 2 (Deep Discovery - 0.65)**: Used when the user explicitly asks about "files", "attachments", or "web search".
*   **Code Reference**: `ContextManager.kt` (Line 52) implements this dual-threshold logic using cosine similarity scores.

### 7. How are complex Word and Excel files read offline?
We use **Apache POI**, a production-grade library for Microsoft Office formats, optimized for Android to avoid excessive memory usage.
*   **Code Reference**: `MultimodalIngestor.kt` implements the `.docx` and `.xlsx` extraction logic using `XWPFDocument` and `XSSFWorkbook`.

---

## 💬 Chat & Personas

### 8. Why does the AI sound like a "Senior Software Engineer" when I use the API?
Chhanda identifies the request source. Requests from programmatic sources (IDEs, Scripts) trigger the **Expert Persona** to provide high-fidelity technical and architectural guidance.
*   **Code Reference**: `SendMessageUseCase.kt` (Line 157-159) injects the `SENIOR SOFTWARE ENGINEER` system role when the source is `api`.

### 9. Can I seek through the AI's voice responses?
**Yes.** The new media engine includes a global playback bar that supports seeking, background playback, and forward/backward controls.
*   **Code Reference**: `TtsPlayer.kt` handles the `ExoPlayer` instance for low-latency audio streaming with background service support.

---

## 🌐 Connectivity & Gateway

### 10. Can I connect my laptop to Chhanda via a QR code?
**Yes.** As long as both are on the same Wi-Fi. 
*   **Code Reference**: `ChhandaServer.kt` uses Ktor's `Netty` engine to serve a full-featured web app from the Android device.

### 11. What is the "Internet Connectivity Guard"?
When you attempt to scrape a website, Chhanda first checks the system's network capability. If no internet is detected, it prevents the operation and notifies you, rather than hanging.
*   **Code Reference**: `ScrapeUrlUseCase.kt` uses `ConnectivityManager` to verify internet availability before initiating a `Jsoup` fetch.

---

**Technical Troubleshooting**: Check the **System Logs** in the Dashboard for real-time `ChhandaAudit` tags.
