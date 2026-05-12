# Chhanda AI Gateway - Architectural Design & Developer Guide

## 🎭 Role & Purpose of this Document
This document is written from the perspective of a **Senior Architect**. It provides an exhaustive, file-by-file and layer-by-layer breakdown of the Chhanda application. It is designed to guide a junior developer through the complex interplay of local LLM inference, vector databases, and background processing on Android, enabling them to make fixes and scale the system confidently.

---

## 🏛 1. High-Level Architecture

Chhanda follows a strict **Clean Architecture** pattern combined with **MVI (Model-View-Intent)** principles in the presentation layer. It is 100% offline-first, meaning all inference and storage happen on the device.

### 🗺 High-Level System Overview
This diagram shows how the user, background processes, and external apps interact with the system.

```mermaid
graph TD
    User([User]) <-->|Interacts| UI[Jetpack Compose UI]
    UI <-->|Flows/Events| VM[ViewModels State Management]
    VM -->|Executes| UC[Use Cases Business Logic]
    UC -->|Queries| VS[Local Vector Store Room DB]
    UC -->|Inference| LE[LiteRT LM Engine]
    UC -->|Web Search| GS[Google Search Scraper]
    
    Docs[(Local Documents PDF/Img/Aud)] -->|Ingested by| IW[IngestionWorker]
    IW -->|Parses| MI[Multimodal Ingestor OCR]
    MI -->|Chunks| TC[Text Chunker]
    TC -->|Embeds| EE[Embedding Engine]
    EE -->|Saves| VS
    
    ExternalApp([External App]) <-->|API Request| KS[Ktor API Server]
    KS -->|Calls| UC
```

---

## 📂 2. Layer-by-Layer Breakdown & Component Diagram

### 🧱 Component & Layer Interaction
This diagram illustrates the separation of concerns and how layers depend on each other. Dependency flows downwards.

```mermaid
graph TB
    subgraph "Presentation Layer (UI & State)"
        direction TB
        CS[ChatScreen]
        DS[DashboardScreen]
        CVM[ChatViewModel]
        SVM[SystemViewModel]
        CS -.-> CVM
        DS -.-> SVM
    end
    
    subgraph "Domain Layer (Pure Business Logic)"
        direction TB
        SMUC[SendMessageUseCase]
        IDUC[IngestDocumentUseCase]
        GSP[GemmaPromptBuilder]
        SUUC[ScrapeUrlUseCase]
        GSUC[GoogleSearchUseCase]
    end
    
    subgraph "Data Layer (Persistence & AI Engines)"
        direction TB
        LE[LiteRTLMEngine]
        EE[LiteRTEmbeddingEngine]
        LVS[LocalVectorStore]
        RD[(Room Database)]
    end
    
    subgraph "Infrastructure (System Services)"
        direction TB
        CServ[ChhandaServer Ktor]
        IW[IngestionWorker]
    end
    
    Presentation Layer --> Domain Layer
    Domain Layer --> Data Layer
    Infrastructure --> Domain Layer
    Infrastructure --> Data Layer
```

### 🔵 2.1 Presentation Layer Files
*   **`MainActivity.kt`**: Entry point, sets up Navigation graph.
*   **`ChatScreen.kt`**: Real-time streaming chat interface.
*   **`ConfigScreen.kt`**: Settings panel (Context length, TurboQuant toggle).
*   **`SystemViewModel.kt`**: Manages global state, storage, and workers.
*   **`ChatViewModel.kt`**: Handles message flow and LLM interaction.

### 🟢 2.2 Domain Layer Files
*   **`SendMessageUseCase.kt`**: Orchestrates retrieval and LLM inference.
*   **`ScrapeUrlUseCase.kt`**: Robust web content extraction.
*   **`GoogleSearchUseCase.kt`**: Performs Google search as a fallback.

### 🟡 2.3 Data Layer Files
*   **`LiteRTLMEngine.kt`**: Core LLM runner (LiteRT/TFLite) with memory management.
*   **`LocalVectorStore.kt`**: Similarity search over Room-stored embeddings.
*   **`SettingsRepository.kt`**: DataStore for persistent configuration.

---

## 🔄 3. Control Flow: Message Processing & RAG Fallback

This sequence diagram details the exact control flow when a user sends a message, showing the decision-making process for local RAG vs. Web Search fallback.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant CS as ChatScreen
    participant VM as ChatViewModel
    participant UC as SendMessageUseCase
    participant CM as ContextManager
    participant VS as LocalVectorStore
    participant GS as GoogleSearchUseCase
    participant LE as LiteRTLMEngine
    
    User->>CS: Type message & send
    CS->>VM: SendMessage(text)
    VM->>UC: invoke(text)
    UC->>CM: getOptimizedContext(text)
    CM->>VS: query(text)
    VS-->>CM: Return snippets (if score > 0.02)
    
    alt Local Context Found
        CM-->>UC: Return longTermContext
    else No Local Context
        CM-->>UC: Return empty
        UC->>GS: Search(text)
        GS-->>UC: Return top links & snippets
        UC->>UC: Scrape content (up to 2 sources)
    end
    
    UC->>LE: generateResponse(prompt, context)
    loop Token Streaming
        LE-->>UC: emit(TokenUpdate)
        UC-->>VM: emit(TokenUpdate)
        VM-->>CS: Update UI
    end
    CS-->>User: Display final response
```

---

## 🏗 4. Low-Level Diagram: RAG Ingestion Pipeline

This diagram shows the low-level data transformation steps when a document is ingested into the system.

```mermaid
graph LR
    A[File Selected] --> B{File Type?}
    B -->|PDF| C[PDFBox / OCR]
    B -->|Image| D[ML Kit OCR]
    B -->|Audio| E[Whisper / Speech-to-Text]
    B -->|Text| F[Raw Text]
    
    C --> G[Extracted Text]
    D --> G
    E --> G
    F --> G
    
    G --> H[Recursive Text Splitter]
    H --> I[Chunks of ~500 chars]
    
    I --> J[LiteRT Embedding Engine]
    J --> K[384-dim Vector]
    
    K --> L[Insert into Room DB]
    L --> M[Update LocalVectorStore]
```

---

## 🛠 5. Edge Case Engineering: Overcoming Native & Hardware Limitations

### 🚨 Problem 1: Native C++ Engine "Busy" Deadlocks
*   **Symptom**: Inference engine remains busy after cancellation.
*   **Solution**: Implemented a retry loop in `LiteRTLMEngine.kt` (30 attempts, 400ms delay) and strict calling of `session.close()`.

### 🚨 Problem 2: Out of Memory (OOM) on Low-RAM Devices
*   **Symptom**: App crashes when loading large models.
*   **Solution**: Dynamic context length scaling (halving on failure) and mandatory `System.gc()` with a 2500ms delay before loading.

### 🚨 Problem 3: RAG Hallucination & Low Recall
*   **Symptom**: System says "I don't know" for available data.
*   **Solution**: Lowered similarity threshold to `0.02` and implemented the Google Search fallback detailed in the control flow.

---

## 👶 6. Guide for Junior Developers: How to Scale the App

### How to add a new Setting
1.  Add key in `SettingsRepository.kt` -> `PreferencesKeys`.
2.  Add flow/setter in `SettingsRepository.kt`.
3.  Expose in `SystemViewModel.kt`.
4.  Add UI in `ConfigScreen.kt`.

### How to debug Inference Issues
1.  Check the **Logs** screen in the app for `[INFERENCE]` tags.
2.  Errors in JNI callbacks must be caught aggressively in `LiteRTLMEngine.kt`.