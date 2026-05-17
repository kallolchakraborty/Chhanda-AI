# 📘 Chhanda AI — User Manual

**Your Comprehensive Guide to the 100% Offline AI Gateway**
**Version 1.2 · Last Updated: May 2026**

---

## Table of Contents

1. [Getting Started](#-1-getting-started)
2. [Dashboard: The Command Center](#-2-dashboard-the-command-center)
3. [Model Management](#-3-model-management)
4. [Chat: Interactive Intelligence](#-4-chat-interactive-intelligence)
5. [Knowledge Base (RAG)](#-5-knowledge-base-rag)
6. [AI Gateway Server](#-6-ai-gateway-server)
7. [Settings & Configuration](#-7-settings--configuration)
8. [System Logs](#-8-system-logs)
9. [Safety & Privacy](#-9-safety--privacy)
10. [Troubleshooting](#-10-troubleshooting)
11. [Keyboard Shortcuts & Pro Tips](#-11-pro-tips)

---

## 🚀 1. Getting Started

### 1.1 First Launch
When you first launch Chhanda, you will see the **Welcome Screen** with the Chhanda logo and branding. Tap "Get Started" to proceed to the Dashboard.

### 1.2 Permissions
Chhanda requests the following permissions on first launch:

| Permission | Purpose | Required? |
|:---|:---|:---|
| **Notifications** (API 33+) | Background server status alerts | Yes |
| **Media Access** (API 33+) | RAG document ingestion from shared storage | Yes |
| **Storage** (API < 33) | Read/write model files and documents | Yes |
| **Camera** | QR code scanning for device pairing | Optional |
| **Microphone** | Voice input for chat | Optional |

> **Privacy Note:** Since the removal of automated hotspot management, Chhanda no longer requests `ACCESS_FINE_LOCATION` or `NEARBY_WIFI_DEVICES`. This minimizes your privacy footprint.

### 1.3 System Requirements

| Requirement | Minimum | Recommended |
|:---|:---|:---|
| **Android Version** | 8.0 (Oreo, API 26) | 13+ (Tiramisu) |
| **RAM** | 4 GB | 8 GB+ |
| **Storage** | 3 GB (for Gemma 2B) | 8 GB+ (for Gemma 4B) |
| **Processor** | ARM64 (any) | Snapdragon 8 Gen 1+ / Tensor G2+ |

---

## 📊 2. Dashboard: The Command Center

The Dashboard is the central control panel for all Chhanda operations.

```mermaid
graph TD
    subgraph "Dashboard Layout"
        direction TB
        TB["Top Bar: Chhanda Logo + QR Button"]
        AMC["Active Model Card<br/>• Model name & status<br/>• IP address & port<br/>• Temperature & TPS<br/>• Vector memory usage<br/>• Start/Stop/Try It controls"]
        IML["Internal Models List<br/>• Scan for models<br/>• Import custom models<br/>• Activate / Deactivate"]
        SML["Shared Models List<br/>• Auto-detected from storage"]
        DML["Downloadable Models<br/>• Gemma 2B / 4B / 4n<br/>• Download / Pause / Resume"]
        CHM["Chat Management<br/>• Open Storage Manager"]
    end

    TB --> AMC --> IML --> SML --> DML --> CHM
```

### 2.1 Active Model Card
The large hero card at the top shows:
- **Model Name**: Currently loaded GGUF model (e.g., `gemma3-4b-it-int4.task`)
- **Server Status**: Green pulse = active, Gray = inactive
- **IP Address**: The local network address clients can connect to
- **Port**: The Ktor-CIO server port (default: 8080)
- **Temperature**: Current device thermal reading (°C)
- **Thermal Status**: `NONE` → `LIGHT` → `MODERATE` → `SEVERE` → `CRITICAL` → `EMERGENCY` → `SHUTDOWN`
- **Vector Memory**: Current RAG storage usage vs. dynamic capacity limit
- **TPS**: Real-time tokens per second during active inference

### 2.2 Visibility-Aware Telemetry
The Dashboard's hardware monitors (RAM, thermal, TPS) **automatically suspend** when you navigate away or background the app. This saves approximately **12% battery** during idle periods.

**How it works**: `SystemViewModel` tracks app visibility via `onVisibilityChanged()`. When `isAppVisible = false`, all polling coroutines skip their iterations.

### 2.3 QR Code Sharing
Tap the **QR icon** in the top-right to open the Gateway Dialog:
1. The QR code encodes: `http://<IP>:<PORT>?key=<API_KEY>`
2. Any device on the same network can scan this to connect
3. If no network is detected, Chhanda will prompt you to enable your phone's system hotspot

### 2.4 Background Mode
When the server is running and you press **Back**:
- A dialog asks: "Keep Running" or "Stop Server"
- "Keep Running" moves the app to the background while maintaining the foreground service
- The notification bar shows an ongoing notification with a **Stop** action button

---

## 🧩 3. Model Management

### 3.1 Model Sources

```mermaid
graph LR
    subgraph "Model Discovery"
        A["Internal Storage<br/>/files/"] --> D["Model Scanner"]
        B["Downloads/<br/>(Shared)"] --> D
        C["Custom Import<br/>(File Picker)"] --> D
    end

    D --> E{"Is valid .task file?"}
    E -- Yes --> F["Add to Owned<br/>or Shared list"]
    E -- No --> G["Ignore"]
```

| Source | Location | Label in UI |
|:---|:---|:---|
| **Internal** | App's private `filesDir` | "Internal Models" (🔒 Secure) |
| **Shared** | `Downloads/`, external storage | "Shared Models" (Detected) |
| **Downloaded** | HuggingFace CDN → `filesDir` | Progress bar during download |

### 3.2 Downloading Models
1. Scroll to "Downloadable Models" section
2. Tap the **Download** button on any model card
3. The download runs via `DownloadWorker` (WorkManager) — survives app closure
4. Controls: **Pause** / **Resume** / **Cancel**
5. Progress is shown as a percentage bar with download speed

### 3.3 Model Activation
1. Tap **Activate** on any model card (or use the model picker dialog)
2. Chhanda will:
   - Stop any currently running server
   - Wait **2.5 seconds** for RAM to flush (prevents OOM)
   - Load the model into memory via LiteRT-LM
   - Start the Ktor-CIO server on the configured port
   - Register mDNS for network discovery
3. The Active Model Card will show a green pulsing indicator

### 3.4 Model Deletion
Tap the **Delete** icon on any model card → Confirm in the dialog. This removes the `.task` file from disk.

---

## 💬 4. Chat: Interactive Intelligence

### 4.1 Starting a Chat
1. Tap **Try It** on any active model card (or from model list)
2. This opens the Chat History bottom sheet
3. Choose **Start New Chat** or resume a previous session

### 4.2 Chat Features

```mermaid
graph TD
    subgraph "Chat Capabilities"
        MSG["Text Messages"]
        ATT["File Attachments<br/>(up to 3 files)"]
        TTS["Text-to-Speech<br/>with seek bar"]
        THK["Thinking Mode<br/>(show/hide reasoning)"]
        PER["Persona Selection<br/>• Senior Teacher<br/>• Senior Engineer<br/>• General Companion<br/>• Friend"]
        FG["File Generation<br/>• PDF, DOCX, XLSX"]
        SUG["Smart Suggestions<br/>(from RAG sources)"]
    end
```

### 4.3 Persona System
Chhanda dynamically adjusts its personality based on context:

| Persona | When Active | Behavior |
|:---|:---|:---|
| **Senior Teacher** | User selects in chat | Educational, uses analogies, step-by-step |
| **Senior Software Engineer** | API source OR user selects | Expert-level, architectural, performance-focused |
| **General Companion** | Default for local chat | Balanced, helpful, approachable |
| **Friend** | User selects in chat | Casual, supportive, informal |
| **Gateway Orchestrator** | Fallback | Neutral, context-aware, tiered knowledge |

### 4.4 Thinking Mode
Toggle in Settings → Appearance:
- **ON**: Shows the model's internal reasoning (wrapped in `<thought>` tags) before the final answer
- **OFF**: Strips all reasoning traces, showing only the final response
- The thinking content is parsed in real-time during token streaming

### 4.5 File Attachments
1. Tap the **Attachment** icon in the chat input bar
2. Select up to 3 files (PDF, DOCX, XLSX, images)
3. Files are processed inline by `TurnContextIngestor`:
   - Text is extracted using the appropriate parser
   - Content is injected as **Tier 1 (Immediate)** context in the prompt
4. This is separate from the permanent RAG Knowledge Base

### 4.6 Document Generation
Ask the AI to create files:
- *"Create an Excel spreadsheet comparing programming languages"*
- *"Generate a PDF report of our conversation"*
- *"Make a Word document summarizing this topic"*

The AI uses `[GENERATE_FILE]` tags internally. Files are saved to `filesDir/generated/` and a download link appears in the chat bubble.

### 4.7 Text-to-Speech
- Tap the **Speaker** icon on any AI response to hear it read aloud
- A global playback bar appears with:
  - Play/Pause button
  - 10-second forward/backward seeking
  - Progress indicator
- Voice selection: Settings → TTS Voice → Preview with "Play Sample"

---

## 📚 5. Knowledge Base (RAG)

### 5.1 What is RAG?
Retrieval-Augmented Generation (RAG) allows Chhanda to search your personal documents before answering, grounding responses in your actual data rather than relying solely on pre-trained knowledge.

### 5.2 Ingestion Pipeline

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant UI as KnowledgeBase Screen
    participant VM as SystemViewModel
    participant WM as WorkManager
    participant IW as IngestionWorker
    participant MI as MultimodalIngestor
    participant TC as TextChunker
    participant EE as EmbeddingEngine
    participant VS as LocalVectorStore
    participant DB as Room DB

    U->>UI: Upload file(s) or URL
    UI->>VM: ingestDocuments(uris)
    VM->>VM: Check file size

    alt Size > 1 MB
        VM-->>UI: Show background prompt
        U->>VM: Confirm background processing
        VM->>WM: Enqueue IngestionWorker
        WM->>IW: Execute in background
    else Size ≤ 1 MB
        VM->>VM: processIngestDocuments(uris)
    end

    IW->>MI: extractText(uri, docType)
    Note over MI: PDF → PDFBox<br/>DOCX → Apache POI<br/>XLSX → Apache POI<br/>IMG → ML Kit OCR<br/>HTML → Jsoup
    MI-->>IW: Raw text string
    IW->>TC: chunk(text, maxChunkSize=500)
    TC-->>IW: List<String> chunks
    
    loop For each chunk
        IW->>EE: embed(chunk)
        EE-->>IW: Float[512] vector
        IW->>IW: Quantize Float→Int8 (×127)
        IW->>VS: add(text, embedding, metadata)
        VS->>DB: Insert VectorChunkEntity (BLOB)
    end
    
    IW-->>UI: Ingestion complete ✅
```

### 5.3 Supported File Types

| Format | Parser | Icon |
|:---|:---|:---|
| **PDF** | `PdfBox-Android 2.0.27.0` | 📄 |
| **DOCX** | Apache POI `XWPFDocument` | 📝 |
| **DOC** | Apache POI `HWPFDocument` (Scratchpad) | 📝 |
| **XLSX** | Apache POI `XSSFWorkbook` | 📊 |
| **XLS** | Apache POI `HSSFWorkbook` (Scratchpad) | 📊 |
| **CSV** | Robust state-machine RFC 4180-compliant parser (key-value formatted) | 📊 |
| **TSV / TAB** | Robust state-machine tab-separated parser (key-value formatted) | 📊 |
| **XML** | Hierarchical tag-path preserving XML context parser | 💻 |
| **HTML** | Jsoup HTML body text extractor with script/style removal | 🌐 |
| **MD** | Raw Markdown with outline headers preserved | 📃 |
| **JSON** | Adaptively streamed JSON semantic tree parser | 💻 |
| **TXT** | Direct text read | 📃 |
| **Images** | Google ML Kit Text Recognition v16 | 🖼️ |
| **Websites** | Jsoup HTML extraction | 🌐 |

### 5.4 Vector Storage Details
- **Dimensionality**: 512 (MediaPipe tasks-text)
- **Quantization**: Each float is mapped to `(value × 127).toByte()` for Int8 storage
- **Storage Format**: Room BLOB column (`ByteArray`) in `VectorChunkEntity`
- **Search Algorithm**: PriorityQueue Min-Heap with O(N log K) complexity
- **Capacity**: Dynamically calculated — minimum 1 GB, up to 15% of free device storage

### 5.5 Adaptive Similarity Thresholds

```mermaid
flowchart TD
    A["User Query"] --> B{"Contains search keywords?<br/>(file, attachment, web, search)"}
    B -- Yes --> C["Explicit Search Mode<br/>Threshold = 0.60"]
    B -- No --> D{"Is follow-up?<br/>(short query or pronouns)"}
    D -- Yes --> E["Follow-up Mode<br/>Threshold = 0.50<br/>(fallback)"]
    D -- No --> F["General Mode<br/>Threshold = 0.80"]
    C --> G["Fetch top-K chunks"]
    E --> G
    F --> G
    G --> H["Format as XML tags:<br/>&lt;retrieved_knowledge&gt;"]
    H --> I["Inject into Prompt<br/>as TIER 2 context"]
```

### 5.6 File Management
- **Recent Files**: Shows last 5 ingested files
- **All Files**: Full list with timestamps
- **Delete**: Select files → Delete removes from disk, DB, and vector store
- **Auto-Delete**: Enable in Settings → files older than N days are auto-purged
- **Storage Warning**: At 90% capacity, a dialog blocks further ingestion

---

## 🌐 6. AI Gateway Server

### 6.1 Architecture Overview
Chhanda transforms your Android phone into a full AI inference server accessible to any device on the local network.

```mermaid
graph TD
    subgraph "Server Lifecycle"
        A["User taps Start"] --> B["ChhandaForegroundService"]
        B --> C["Acquire WakeLock + WiFiLock"]
        B --> D["ChhandaServer.start(port)"]
        D --> E["Ktor-CIO Engine Boot"]
        E --> F{"Port available?"}
        F -- Yes --> G["Bind to 0.0.0.0:PORT"]
        F -- No --> H["Try PORT+1, PORT+2..."]
        H --> G
        G --> I["Self-Probe: /ping → pong"]
        I --> J["Register mDNS<br/>(NSD _chhanda._tcp)"]
        J --> K["Start Reaper Coroutine<br/>(30s heartbeat check)"]
        K --> L["✅ Server Active"]
    end
```

### 6.2 API Endpoints

| Endpoint | Method | Auth | Description |
|:---|:---|:---|:---|
| `/ping` | GET | None | Health check — returns `pong` |
| `/v1/chat/completions` | POST | X-API-Key | OpenAI-compatible chat API |
| `/v1/models` | GET | X-API-Key | List available models |
| `/api/chat` | POST | X-API-Key | Legacy chat endpoint |
| `/` | GET | X-API-Key | Web UI (HTML/CSS/JS chat interface) |
| `/heartbeat` | POST | X-API-Key | Client keepalive signal |

### 6.3 Rate Limiting
- **Leaky Bucket**: Each client IP gets 1 request per second
- **Concurrency**: Max 2 simultaneous inference tasks (semaphore-guarded)
- **Excess requests**: Return HTTP 429 (Too Many Requests)

### 6.4 Device Management
- Connected devices are tracked in Room DB via `DeviceDao`
- Each device has: name, IP, user-agent, connection time, last active timestamp
- The **Reaper** coroutine marks devices as disconnected after 30 seconds of no heartbeat
- The Dashboard shows active device count; Device Manager shows full connection history

### 6.5 SSH Tunneling
1. Tap "Enable Tunnel" in the Gateway Dialog
2. Chhanda creates a reverse SSH tunnel via JSch to `localhost.run`
3. You get a public URL (e.g., `https://abc123.lhr.life/`)
4. This allows **remote access without port forwarding** — useful when devices are on different networks

### 6.6 VPN Warning
If a VPN is detected (TUN/PPP/IPSec interface), a warning banner appears on the Dashboard. VPNs can interfere with local network routing and prevent other devices from connecting.

---

## ⚙️ 7. Settings & Configuration

### 7.1 Appearance
| Setting | Options | Effect |
|:---|:---|:---|
| **Dark Mode** | Toggle | Switches between `darkColorScheme()` and `lightColorScheme()` |
| **Thinking Mode** | Toggle | Show/hide model reasoning traces in chat |
| **Language** | English, Bengali, Hindi | Full UI + TTS localization |
| **TTS Voice** | Kallol (Indian Male), Chhanda (Indian Female) | AI voice persona with sample preview |

### 7.2 Network Settings
| Setting | Range | Effect |
|:---|:---|:---|
| **Server Port** | Any valid port | Ktor-CIO bind port (requires restart) |
| **Context Length** | 102–32,768 tokens | LLM context window size |
| **TurboQuant** | Toggle | KV-cache compression for memory savings |
| **Max Devices** | 1–20 | Maximum simultaneous client connections |
| **Vector Database (RAG)** | Toggle | Enable/disable the entire RAG pipeline |
| **DB Capacity** | Auto-calculated | 1 GB min, up to 15% of free storage |
| **Internet Search Capability** | Toggle | Enable or completely disable the external Google Web Search fallback querying pipeline |

### 7.3 Fallback Web Search Logic

The **Internet Search Capability** toggle provides complete digital sovereignty. When enabled:
* If a query yields poor similarity matches in the local RAG knowledge base, Chhanda attempts to query Google Search dynamically to augment its response.
* This operates purely on an online connection. If the device goes offline, search transitions gracefully back to pre-trained parameters.

When disabled:
* Chhanda acts as a **100% disconnected local bubble**, completely ignoring the web scraper regardless of your internet connection or search queries. Any fallback triggers pre-trained parameters with immediate notification banners on the screen.

### 7.4 Auto-Delete Settings
| Setting | Options | Effect |
|:---|:---|:---|
| **Auto Delete** | Toggle | Enable automatic cleanup of old RAG data |
| **Delete After** | 1–30 days | Files older than this threshold are purged |

### 7.5 Security
| Setting | Details |
|:---|:---|
| **API Key** | Auto-generated `CH-XXXXXXXX` format; copy, regenerate, or set custom |
| **HuggingFace Token** | For downloading gated models from HF Hub |
| **Storage** | Stored in `EncryptedSharedPreferences` (AES-256-GCM, KeyStore-backed) |

---

## 📋 8. System Logs

### 8.1 Log Categories
| Tag | Meaning |
|:---|:---|
| `SYSTEM` | App lifecycle, permission status, monitors |
| `SERVER` | Ktor-CIO events, port binding, mDNS |
| `NETWORK` | Device connections, VPN detection, IP changes |
| `INFERENCE` | Model loading, TPS, context window adjustments |
| `RAG` | Ingestion progress, vector search metrics |
| `STORAGE` | File management, cleanup operations |
| `CRASH` | Recovered crash logs from previous sessions |
| `TUNNEL` | SSH tunnel status and URL |

### 8.2 Log Severity
- 🟢 `SUCCESS` — Operation completed successfully
- 🔵 `INFO` — Informational status update
- 🟡 `WARNING` — Non-critical issue (degraded functionality)
- 🔴 `ERROR` — Critical failure requiring attention

### 8.3 Log Management
- **Select & Delete**: Tap individual logs → batch delete
- **Clear All**: Remove all logs at once
- **Auto-Recovery**: Crash logs from previous sessions are automatically imported on next launch

---

## 🔒 9. Safety & Privacy

### 9.1 Defense-in-Depth Security Model

```mermaid
graph LR
    subgraph "Input Path"
        A["User Input"] --> B["PII Redaction<br/>(Email, Phone, SSN, CC)"]
        B --> C["Prohibited Content<br/>Filter"]
        C --> D["Injection Detection<br/>(Keywords + Heuristic Regex)"]
        D --> E["Defensive Delimiters<br/>[USER_INPUT_START/END]"]
    end

    subgraph "Context Path"
        F["RAG Retrieved Text"] --> G["Context Sanitization<br/>[EXTERNAL_CONTEXT_START/END]"]
    end

    subgraph "Output Path"
        H["LLM Response"] --> I["Output PII Redaction"]
        I --> J["Clean Response"]
    end

    E --> K["LLM Engine"]
    G --> K
    K --> H
```

### 9.2 What Gets Stored?

| Data Type | Local Chat | Web Chat | API Chat |
|:---|:---|:---|:---|
| **User Messages** | ✅ Persisted | ✅ Persisted | ❌ Ephemeral |
| **AI Responses** | ✅ Persisted | ✅ Persisted | ❌ Ephemeral |
| **API Keys** | ✅ Hardware-encrypted | — | — |
| **RAG Vectors** | ✅ Int8 BLOB in Room | — | — |
| **Device Logs** | ✅ In-memory only | — | — |

---

## 🔧 10. Troubleshooting

| Issue | Cause | Solution |
|:---|:---|:---|
| **"No Models Found"** | No `.task` files on device | Download from the Downloadable Models section or import manually |
| **App crashes on model load** | Insufficient RAM | Use a smaller model (Gemma 2B) or close background apps |
| **Server won't start** | Port conflict | Change port in Settings → Network Settings |
| **Other devices can't connect** | VPN active or different network | Disable VPN and ensure devices are on the same Wi-Fi/Hotspot |
| **RAG returns irrelevant results** | Low similarity threshold | Use explicit search keywords ("search for X in my files") |
| **Device overheating** | Extended inference sessions | The thermal tracker will auto-throttle; take breaks between sessions |
| **"Storage Nearly Full"** | Vector DB at 90% capacity | Open Storage Manager → delete old files or increase capacity |
| **QR code not working** | No network interface detected | Enable Wi-Fi or Mobile Hotspot first |

---

## 💡 11. Pro Tips

1. **Android Studio Integration**: For developers seeking to extend Chhanda AI, open the workspace in **Android Studio**. Utilize the *Android Profiler* to monitor real-time RAM allocation during 4B model swaps, inspect layout integrity with Jetpack Compose *Layout Inspector*, and check multi-process binding diagnostics via Android Studio *Logcat* using the filter `package:mine`.

2. **Google Gemini 3 Flash Exclusive Engineering**: The entire code verification pipeline, RAG similarity optimizations, and local completions server logic were co-engineered and hardened using **Google's Gemini 3 Flash** as the exclusive AI agent. Its high-fidelity scanning guarantees absolute code resilience.

3. **Hotspot Mode**: For the best gateway experience, use your phone's **Mobile Hotspot**. This creates a dedicated local network that works even without internet access.

4. **RAG Precision**: When asking about specific documents, use phrases like *"search my files for..."* or *"what does the attachment say about..."* — this triggers the lower similarity threshold for deeper discovery.

5. **Batch Ingestion**: You can select multiple files at once in the Knowledge Base screen. Files over 1 MB are automatically routed to background processing via WorkManager.

---

**Developed with ❤️ by Kallol Chakraborty**
**IDE & Tooling Partner**: Android Studio
**Exclusive AI Partner**: Google Gemini 3 Flash
**Dedicated to Chhanda Chakraborty**
