# Chhanda (ছন্দ) - The AI Gateway

<div align="center">
  <h3>100% Offline, Privacy-First AI & RAG Ecosystem for Android</h3>
  <p><i>Developed by <b>Kallol Chakraborty</b> | Dedicated to my mother, Chhanda.</i></p>
</div>

---

## 📖 The Philosophy & Dedication

**Chhanda** (ছন্দ) refers to the poetic meter or rhythm in Bengali literature. It structures poetry through the precise arrangement of syllables (*mātrā*), derived from words, creating a musical, rhythmic flow. 

In Bengali poetry, words are broken down into syllables—the basic sound units treated as tokens—for chhanda analysis. Each word’s pronunciation determines *guru* (long) or *laghu* (short) syllables, and stress patterns form the rules of the meter.

**The Connection to AI:**
Just as *Chhanda* structures raw syllables into beautiful, flowing poetry, Large Language Models (LLMs) break down human language into structural **tokens**. By predicting and arranging these tokens based on complex mathematical weights (the meter of the neural network), the AI generates coherent, intelligent, and flowing communication. 

This application is named after and dedicated to my mother, Chhanda, representing the harmony between the foundational structure of language and the beautiful flow of intelligence.

---

## 🛠 Development & Tooling

This project is a testament to modern AI-assisted software engineering:
*   **Developer**: Kallol Chakraborty
*   **IDE**: Antigravity
*   **AI Development Partner**: Gemini 3 Flash
*   **UI/UX Mockups**: Google Stitch
*   **Branding & Logos**: Nanobana

---

## 🚀 Extreme Detail: App Features

Chhanda is not just a chat app; it is a full-fledged local AI server and gateway.

1.  **Zero-Cloud Local Inference**: Utilizes Gemma 2B and 4B (4-bit quantized GGUF) models running entirely on-device via Google LiteRT-LM. No data is ever sent to the cloud.
2.  **Adaptive Multimodal RAG (Retrieval-Augmented Generation)**:
    *   **Documents**: Advanced local parsing for `.pdf`, `.docx`, `.doc`, `.xlsx`, `.xls`, `.json`, `.csv`, and `.txt`.
    *   **Adaptive Similarity Engine**: Optimized **Min-Heap** search with $O(N \log K)$ complexity and fast cosine math.
    *   **Selectivity Layer**: Intelligent 0.15 relevance thresholding.
    *   **Vision**: ML Kit OCR for JPG/PNG.
    *   **Web**: 3-stage scraping (Jsoup/Jina/Semantic).
    *   **Audio**: Support for `.wav`, `.mp3`, `.m4a`.
3.  **TurboQuant & Intelligence Modes**:
    *   **Thinking Mode**: Reasoning traces via `<thought>` tags.
    *   **TurboQuant (KV-Cache Compression)**: Hardware-level KV-cache optimization.
4.  **Hardware Resilience & Safety**:
    *   **Dynamic Compute Scaling**: Thermal-aware auto-throttling.
    *   **Memory Hygiene**: 2.5s RAM flush delay.
5.  **Premium Document & Audio Interface**:
    *   **Context-Aware Iconography**: A sophisticated UI system that dynamically renders unique icons and color-coded metadata for various document types.
    *   **Advanced Media Playback**: Integrated TTS (Text-to-Speech) with a dedicated playback bar, seek controls (forward/backward), and full background execution support.
    *   **Unified Formatting**: Integrated metadata display (`[Source: filename] [Type: format]`) across the Knowledge Base and Chat interfaces.
4.  **Production-Grade RAG Observability**:
    *   **Latency Analysis**: Real-time tracking of p50, p95, and p99 query latencies to monitor tail performance.
    *   **Throughput Metrics**: Live visualization of Queries Per Second (QPS) and document indexing rates.
    *   **Retrieval Quality**: Automated tracking of Recall@K and MRR (Mean Reciprocal Rank) to ensure high-fidelity knowledge retrieval.
    *   **Efficiency Tracking**: Monitoring system-level index efficiency (Chunks per MB) and operational compute costs.
5.  **Offline Document Generation**: The AI can generate `.xlsx`, `.docx`, and `.pdf` files locally on demand, which users can download via the host app or the Web UI.
6.  **Chhanda AI Gateway (Web UI via QR)**: 
    *   Turns the Android device into a local network server via Ktor.
    *   Other devices on the same Wi-Fi/Hotspot can scan a QR code to access a beautiful, responsive Web Chat UI.
    *   Features strict Device Limits, Captive Portals for unauthorized access, and API Key authentication.
7.  **Multi-Source Session Management**:
    *   Maintains unified chat history tagged by source: **Local** (Host device), **QR** (Web UI clients), and **API** (Programmatic access).
    *   **Security Policy**: For enhanced privacy, interactions via the **API source** (Code Editors) are ephemeral and are **not stored** in the local database.
    *   Includes sophisticated searching and sorting across all device histories.
8.  **Telemetry & Accurate Resource Monitoring**: 
    *   **Native RAM Tracking**: Implements PSS (Proportional Set Size) monitoring to capture both Java heap and the massive native memory footprint of the LLM models.
    *   **System-Synced Storage**: All file and model sizes are reported using system-standard formatting (consistent with Android OS file managers).
    *   Real-time tracking of TPS (Tokens Per Second), RT (Response Time), and Battery Temperature.
9.  **Context-Aware Personalities**: 
    *   **Expert Mode (API)**: When accessed via the API (e.g., from an IDE), Chhanda adopts the persona of a **Senior Software Engineer**, providing optimized, technical, and architectural guidance.
    *   **Assistant Mode (Local/Web)**: For on-device or QR-based chat, it acts as a **General Purpose Assistant**, optimized for helpful, clear, and multi-lingual communication.

---

## 🏗 High-Level Architecture (Clean Architecture)

Chhanda strictly follows Android Clean Architecture principles, ensuring separation of concerns, testability, and scalability.

```mermaid
graph TD
    subgraph Presentation Layer [Presentation Layer - Jetpack Compose]
        UI[UI Screens: Dashboard, Chat, etc.]
        VM[ViewModels: State & Intent Management]
    end

    subgraph Domain Layer [Domain Layer - Pure Kotlin]
        UC[Use Cases: SendMessage, IngestDoc]
        TCI[TurnContextIngestor: Routing Logic]
        MOD[Domain Models: TokenUpdate, Session]
        CM[ContextManager: Memory & History]
    end

    subgraph Data Layer [Data Layer - Android/Framework Specific]
        REP[Repositories: ChatRepo, Settings]
        TST[ThermalStatusTracker: Hardware Guard]
        DB[(Room DB: Optimized VectorStore)]
        LRT[LiteRT LM Engine: TurboQuant Enabled]
        KTOR[Ktor Server: Network Gateway]
        WRK[WorkManager: Background Ingestion]
    end

    UI -->|State/Events| VM
    VM -->|Executes| UC
    UC -->|Business Logic| MOD
    UC -->|Routes via| TCI
    TCI -->|Scans| REP
    TCI -->|Triggers| WRK
    LRT -->|Throttled by| TST
    UC -->|Interacts| REP
    CM -->|Formats RAG| REP
    UC -->|Inference| LRT
    KTOR -->|Triggers| UC
    WRK -->|Extracts/Embeds| DB
```

---

## 📱 System Requirements

Running a Large Language Model and a Vector Database on-device is a resource-intensive task. To ensure a smooth experience with Chhanda, your device should meet the following specifications:

| Requirement | Minimum | Recommended |
| :--- | :--- | :--- |
| **Operating System** | Android 9.0 (API 28) | Android 13.0+ (API 33+) |
| **RAM (Total)** | 6 GB | 8 GB - 12 GB+ |
| **RAM (Available)** | 3.5 GB (for 2B models) | 6 GB+ (for 4B/8B models) |
| **Processor** | ARM64-v8a (Octa-core) | Snapdragon 8 Gen 1+ or equivalent |
| **Storage (Base)** | 500 MB | 1 GB+ (excluding models) |
| **Storage (Models)** | 2 GB per GGUF model | 10 GB+ (for multiple models) |
| **Vector DB** | 512 MB | 2.5 GB+ (for large knowledge bases) |

### 🛠 Critical Hardware Considerations:
*   **Memory Management**: Chhanda includes a **Memory Saving Mode** (toggleable in Settings). If your device has 6GB of RAM or less, it is highly recommended to disable the **Vector Database (RAG)** to prevent the Android OS from killing the app during inference.
*   **Thermal Performance**: Sustained inference (long chats) will generate heat. High-end cooling systems in modern flagship phones will result in higher **TPS (Tokens Per Second)**.
*   **Battery**: Background server operation and LLM processing are power-intensive. Using the **Web Gateway** while the device is charging is recommended for long sessions.

---

## 🧱 Technology Stack & Libraries

### Core Frameworks
*   **Language**: Kotlin (Coroutines & StateFlow for reactive asynchronous programming).
*   **UI Framework**: Jetpack Compose (Material 3).

### AI & Machine Learning
*   **LLM Inference**: `com.google.ai.edge.litert` (LiteRT / MediaPipe GenAI) for hardware-accelerated local GGUF execution.
*   **Vision**: `com.google.mlkit:text-recognition` for on-device OCR.
*   **Embeddings**: Local sentence-transformer execution via MediaPipe tasks.

### Data & Persistence
*   **Database**: Room (`androidx.room`) with SQLite. Implements custom vector similarity search for RAG.
*   **DataStore**: Preferences DataStore for local settings (API keys, limits).

### Networking & Web Server
*   **Server**: Ktor Server (`ktor-server-netty`, `ktor-server-cors`) for the embedded Web UI and API.
*   **Web Scraping**: Jsoup for robust HTML parsing and Readability scoring.

### Document Processing
*   **Apache POI**: `poi-ooxml` for reading and generating Microsoft Word and Excel documents completely offline.
*   **PDF**: Android's native `PdfRenderer` and custom PDF generators.

### Dependency Injection & Background Work
*   **DI**: Hilt (`dagger.hilt.android`).
*   **Background Tasks**: WorkManager (`androidx.work`) for heavy document ingestion and scraping tasks to prevent ANRs.

---

## 📱 Screen-Wise Functionality & Flows

### 1. Dashboard Screen (The Command Center)
**Functionality**: 
*   Displays hardware telemetry (RAM, Temp, IP Address).
*   Manages local GGUF models (scan, activate, delete).
*   Controls the Ktor Server (Start/Stop, QR Code generation).
*   Access point for the History/Storage Manager.

### 2. Chat Screen (The Interface)
**Functionality**:
*   Primary interface for interacting with the loaded LLM.
*   Supports attaching images and documents.
*   Real-time streaming UI with Markdown rendering, syntax highlighting, and TPS/RT metric overlays.
*   Handles inline rendering of AI-generated downloadable files.

### 3. History Manager (StorageManagerSheet)
**Functionality**:
*   Unified view of all chats across all connected devices (Local, Web, API).
*   Features deep-search across message contents and multi-criteria sorting.
*   Manages ingested RAG files (view, delete, purge).

### Screen Flow Diagram

```mermaid
stateDiagram-v2
    [*] --> Splash
    Splash --> Dashboard : Initialization Complete
    
    state Dashboard {
        ModelManager : Scan & Load Models
        ServerControls : Start Ktor Server
        Telemetry : Monitor Temp/RAM
    }
    
    Dashboard --> ChatScreen : Click "Try It" (Local Chat)
    Dashboard --> GatewayDialog : Click QR Icon
    Dashboard --> StorageManager : Swipe Up / Click "Manage"
    
    state ChatScreen {
        Input --> StreamingOutput
        AttachmentPicker --> IngestionWorker
    }
    
    state StorageManager {
        DeviceList --> ChatViewer
        RagFiles --> FileViewer
    }
    
    GatewayDialog --> RemoteWebClient : Scan QR
```

---

## ⚙️ Low-Level Design (LLD) Diagrams

### RAG & Document Ingestion Pipeline LLD
When a user uploads a document (Word, Excel, PDF) or an Image, the system processes it asynchronously.

```mermaid
sequenceDiagram
    autonumber
    participant UI as ChatScreen / WebUI
    participant VM as ChatViewModel
    participant WM as WorkManager (IngestionWorker)
    participant EXT as Extractors (POI/MLKit/Jsoup)
    participant EE as EmbeddingEngine
    participant DB as Room DB (VectorChunkDao)
    
    UI->>VM: Upload File / URI
    VM->>WM: Enqueue IngestionWorker
    WM->>EXT: Route by DocType (PDF/CSV/M4A/etc)
    EXT-->>WM: Extracted Raw Text
    WM->>WM: Chunking (Overlap Strategy)
    loop For each chunk
        WM->>EE: Generate Vector (FloatArray)
        EE-->>WM: 384-d Embedding
        WM->>DB: Insert (Text + Vector + Metadata)
    end
    DB-->>DB: Index with Min-Heap Strategy
    WM-->>UI: Broadcast "Ingestion Complete"
```

### ⚔️ Chhanda vs. Google AI Edge Gallery

While Google's AI Edge Gallery is an excellent tool for local AI experimentation and research, **Chhanda** is architected for **Production-Grade Utility** and **Enterprise-Level Context**.

| Feature | Google AI Edge Gallery | Chhanda AI Gateway |
| :--- | :--- | :--- |
| **Core Philosophy** | Research & Experimentation | Production & Productivity |
| **RAG Engine** | Limited / Basic Skills | **Enterprise RAG**: PDF, Docx, Xlsx, CSV, Json, M4A, Web |
| **Observability** | Basic Benchmarking | **Real-time**: p99 Latency, Thermal Status, Vector Memory |
| **Stability** | Standard Android lifecycle | **Senior Grade**: Thermal-aware throttling, 2.5s RAM flush |
| **Integration** | Standalone App | **Gateway**: QR Web UI + OpenAI-Compatible API |
| **Personality** | General Assistant | **Reasoning Mode**: Thinking traces + Expert Role switching |
| **Multimedia** | Audio/Vision Demos | **Playback Engine**: Global seek, background TTS |
| **Data Generation** | None | **Document Creator**: Generate .docx, .xlsx, .pdf locally |
| **Localization** | Broad (Google Default) | **Deep Local**: Specialized Bengali/Hindi personalities |
| **Connectivity** | On-Device Only | **Multi-Client**: Connect laptops/IDEs via local network |

### 🏆 Why Choose Chhanda?

1.  **Context is King**: Unlike standard demos, Chhanda's **Multimodal RAG** allows it to truly understand *your* business documents and data structure.
2.  **Developer Force Multiplier**: By acting as an **API Gateway**, Chhanda turns your Android device into a powerful local co-pilot for your IDE (VS Code, Cursor, etc.), enabling private coding assistance without subscription costs.
3.  **Production Readiness**: With real-time tracking of **Recall@K** and **Native RAM (PSS)**, Chhanda provides the diagnostics needed to understand exactly how the model is performing on edge hardware.
4.  **Beyond Chat**: Chhanda doesn't just talk; it **creates**. Whether you need an Excel report or a structured Word doc, Chhanda generates production-ready files entirely offline.

---

## 🛠️ Architecture & Tech Stack

This diagram illustrates the core components of the Chhanda ecosystem, showing how the UI communicates with the domain logic and how the inference engines are abstracted for stability.

```mermaid
classDiagram
    %% Presentation Layer (MVVM)
    class SystemViewModel {
        -SettingsRepository settingsRepository
        -LLMEngine llmEngine
        -VectorStore vectorStore
        -ChhandaServer chhandaServer
        +ramUsage Flow~String~
        +isIngesting Flow~Boolean~
        +scanForModels()
        +activateModel(name)
        +toggleRag(enabled)
        +startServer()
    }

    class ChatViewModel {
        -SendMessageUseCase sendMessageUseCase
        -ChatDao chatDao
        +uiState Flow~ChatUiState~
        +sendMessage(text, attachments)
        +stopInference()
    }

    %% Domain Layer (Business Logic)
    class SendMessageUseCase {
        -LLMEngine llmEngine
        -ContextManager contextManager
        -SettingsRepository settingsRepository
        +invoke(userText, deviceId, modelName, sessionId, attachments) Flow~TokenUpdate~
    }

    class IngestDocumentUseCase {
        -MultimodalIngestor ingestor
        -EmbeddingEngine embeddingEngine
        -VectorStore vectorStore
        +ingestLocalUri(uri, type)
        +ingestScrapedText(text, source, type)
    }

    class ScrapeUrlUseCase {
        -IngestDocumentUseCase ingestDocUseCase
        +scrape(url) Result~String~
    }

    class ContextManager {
        -ChatDao chatDao
        -VectorStore vectorStore
        +getOptimizedContext(query, deviceId) Pair~History, RAGContext~
        +maintainMemoryHygiene()
    }

    %% Interfaces (Abstractions)
    class LLMEngine {
        <<interface>>
        +initModel(path)
        +generateResponse(prompt, history) Flow~TokenUpdate~
        +stopInference()
    }

    class VectorStore {
        <<interface>>
        +add(text, embedding, metadata)
        +search(query, topK) List~SearchResult~
    }

    class EmbeddingEngine {
        <<interface>>
        +embed(text) Embedding
    }

    class MultimodalIngestor {
        <<interface>>
        +ingestPdf(uri) List~String~
        +ingestImage(uri) String
        +ingestWord(uri) String
    }

    %% Implementation Layer (Native & Framework)
    class LiteRTLMEngine {
        -LlmInference nativeEngine
        +initModel(path)
    }

    class LocalVectorStore {
        -VectorChunkDao vectorChunkDao
        +search(query, topK)
    }

    class LiteRTEmbeddingEngine {
        -TextEmbedder embedder
        +embed(text)
    }

    class AndroidMultimodalIngestor {
        -TextRecognizer ocr
        -PdfRenderer pdfRenderer
        +ingestImage(uri)
    }

    class ChhandaServer {
        -KtorServer server
        -SendMessageUseCase sendMessageUseCase
        +start(port)
        +stop()
    }

    %% Data & Infrastructure
    class AppDatabase {
        <<RoomDatabase>>
        +chatDao() ChatDao
        +vectorChunkDao() VectorChunkDao
        +uploadedFileDao() UploadedFileDao
    }

    class SettingsRepository {
        -DataStore preferences
        +ragEnabledFlow Flow~Boolean~
        +updateRagEnabled(enabled)
    }

    %% Relationships & Dependencies
    SystemViewModel ..> LLMEngine : uses
    SystemViewModel ..> VectorStore : uses
    SystemViewModel ..> ChhandaServer : uses
    SystemViewModel ..> IngestDocumentUseCase : uses
    
    ChatViewModel ..> SendMessageUseCase : uses
    
    SendMessageUseCase --> LLMEngine : executes
    SendMessageUseCase --> ContextManager : fetches context
    SendMessageUseCase --> SettingsRepository : checks config
    
    ContextManager --> VectorStore : semantic search
    
    IngestDocumentUseCase --> MultimodalIngestor : extracts
    IngestDocumentUseCase --> EmbeddingEngine : vectorizes
    IngestDocumentUseCase --> VectorStore : persists
    
    ScrapeUrlUseCase --> IngestDocumentUseCase : triggers ingestion
    
    LiteRTLMEngine ..|> LLMEngine : implements
    LocalVectorStore ..|> VectorStore : implements
    LiteRTEmbeddingEngine ..|> EmbeddingEngine : implements
    AndroidMultimodalIngestor ..|> MultimodalIngestor : implements
    
    ChhandaServer --> SendMessageUseCase : routes requests
```

### Web Gateway & Server LLD (`ChhandaServer`)
Handles multiple remote clients via the Android host.

```mermaid
sequenceDiagram
    participant RC as Remote Client (Browser/API)
    participant KTOR as ChhandaServer (Ktor)
    participant SM as Session Manager
    participant UC as SendMessageUseCase
    
    RC->>KTOR: GET / (Check API Key)
    KTOR->>RC: Return HTML/JS (buildChatHtml)
    RC->>KTOR: POST /register (Handshake & Name)
    KTOR->>SM: Track Device IP & Name
    RC->>KTOR: POST /chat (SSE Request)
    KTOR->>UC: invoke(source="qr" or "api")
    UC-->>KTOR: Flow<TokenUpdate>
    loop Streaming
        KTOR-->>RC: data: {token}
    end
    KTOR-->>RC: data: [DONE]
```

---

## 🔒 Security & Privacy Posture
*   **Data Sovereignty**: 100% of embeddings, models, and chat histories reside within the Android App Sandbox. 
*   **Network Protection**: The Ktor server enforces API key validation for all endpoints. Unauthorized IP addresses attempting discovery are met with a Captive Portal intercept.
*   **Safe Execution**: The inference engine implements strict threading controls and mutex locks to prevent C++ level memory corruption during rapid multi-client access.

---

## 🌍 Localization & Universal Accessibility
 
Chhanda is built for global utility with a deep focus on the Indian subcontinent:
*   **Multi-Language UI**: Full localization in **English**, **Bengali** (বাংলা), and **Hindi** (हिन्दी).
*   **App-wide Translation**: All system messages, instructions, and error codes are translated to ensure non-technical users can operate the gateway.
*   **Cross-Language RAG**: The system can ingest documents in any language and provide semantic search results in the user's preferred language.
*   **Localized TTS (Text-to-Speech)**: Features human-like voices for each supported language. 
    *   **Bengali & Hindi Support**: Optimized for Indian accents and clear pronunciation.
    *   **Virtual Personalities**: Choice between different voice profiles (e.g., Kallol, Chhanda) that map to the highest quality system voices available on the device.
*   **Language Safety Gate**: Switching app languages triggers a safety protocol that shuts down active server instances and cleans the inference cache to ensure all components (including the Web Gateway) restart with the correct locale.
 
---
 
## 📜 Conclusion
Chhanda represents a harmony of complex AI systems, working together in a specific, optimized rhythm—much like its namesake. It bridges the gap between high-end cloud AI capabilities and the absolute privacy of edge computing.
