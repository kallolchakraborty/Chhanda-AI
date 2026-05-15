# Chhanda (ছন্দ) - The AI Gateway

**Harnessing Gemma 4 for 100% Offline, Privacy-First AI Accessibility**

---

## 📽️ Project Video
[Attached Public Video]
*(Replace this with your YouTube/Vimeo link)*

## 💻 Code Repository
[Attached Public Code Repository]
*(Replace this with your GitHub link: https://github.com/kallolchakraborty/Chhanda-AI)*

## 🚀 Live Demo / APK
[Attached Live Demo]
*(Replace this with a link to your hosted APK or a web-based simulator if available)*

---

## 💡 Motivation: The "Offline First" Vision

In many parts of the global south, specifically in rural India and Bangladesh, reliable high-speed internet is a luxury, not a guarantee. Furthermore, for non-English speakers, the "Digital Divide" is a double-edged sword: they lack both connectivity and accessible AI that understands their native rhythms.

**Chhanda (ছন্দ)**, named after the poetic meter in Bengali literature, was born from a simple yet powerful goal: **To put the power of a world-class LLM into the pocket of every user, regardless of their internet status or linguistic background.** 

I wanted to build a system where privacy is the default, not an option, and where intelligence flows as naturally as the *chhanda* of a poem, even in the most remote corners of the world.

## 🛠️ Solution Approach: Gemma 4 at the Edge

I leveraged **Google's Gemma 4 (2B & 4B 4-bit Quantized)** models to create a robust AI gateway on Android. ⚖️ **Built entirely as a solo developer**, this project involved architecting everything from the low-level JNI bindings to the responsive Web Gateway. Gemma 4 provides the perfect balance of parameter efficiency and reasoning capability required for high-performance edge computing. By utilizing **Google LiteRT-LM (formerly MediaPipe GenAI)**, I achieved hardware-accelerated inference that rivals cloud-based APIs while maintaining a zero-footprint architecture.

### Key Innovations:
*   **Chhanda Gateway**: A built-in Ktor-based server that turns the Android phone into a local AI hotspot. Other devices can connect via a QR code to access a high-fidelity Web UI, making one powerful phone an AI hub for an entire classroom or office.
*   **Thinking Mode (Reasoning Traces)**: A toggleable system that exposes the model's step-by-step logic (via `<thought>` tags), drastically improving accuracy for complex reasoning and coding tasks.
*   **TurboQuant (KV-Cache Compression)**: Advanced hardware-level optimization that compresses the model's active memory (Key-Value cache), allowing for 2x longer chat sessions without increasing RAM pressure.
*   **Hardware-Aware Throttling**: A proactive safety system that monitors device thermals and dynamically scales compute (auto-enabling TurboQuant and reducing context) to protect the device during intensive use.
*   **Adaptive Privacy-Native RAG**: A completely offline Retrieval-Augmented Generation pipeline. Featuring an **Optimized Min-Heap Search Engine** ($O(N \log K)$) that ensures lightning-fast retrieval even for massive document libraries.
*   **Multimodal Ingestion (Enterprise Grade)**: Native support for PDF, Word, Excel, **Kaggle Datasets (CSV/JSON)**, Images (OCR), and **M4A Audio**.
*   **Premium Dynamic UI**: A sophisticated design system featuring **Context-Aware Iconography** and real-time **System Health Icons** (Thermal/Vector Memory) for production transparency.
*   **Intelligent Multi-Stage Web Scraper**: An advanced ingestion engine that uses a 3-stage strategy (Jsoup -> Jina Reader -> Semantic DOM) with integrated **Internet Connectivity Guard**.
*   **Production RAG Observability**: A built-in monitoring suite that tracks **Tail Latency (p99)**, **Recall@K**, and **MRR** in real-time.
*   **Offline Document Generation**: Empowering users to generate professional **.pdf**, **.docx**, and **.xlsx** files directly from AI responses, all while remaining 100% offline.
*   **Context-Aware Personalities**: An intelligent role-switching system that identifies the request source (e.g., switches to **Senior Software Engineer** for API/IDE access).
*   **Localized TTS & UI**: Deep integration with Bengali and Hindi, featuring localized personalities and human-like accents.

## 🏗️ Development Process

1.  **Engine Selection**: I opted for GGUF formats optimized for LiteRT to ensure low-latency performance on mid-range Android hardware.
2.  **RAG Hardening**: Implemented an advanced **Selectivity Layer** and a 0.15 relevance threshold to prevent hallucinations.
3.  **UI/UX Refinement**: Developed a custom `RagFileItem` system in Jetpack Compose with real-time hardware health overlays.
4.  **Gateway Architecture**: Developed a responsive web dashboard served directly from the Android device, implementing strict API key security.

## 🚧 Challenges & Triumphs

*   **Memory Management**: Running a 4B model alongside a Ktor server required aggressive memory hygiene. I solved this using scoped storage and a mandatory 2.5s RAM flush delay during model switching.
*   **Performance Scaling**: Solving the $O(N)$ search bottleneck in the vector store by implementing a Min-Heap based similarity engine.
*   **Multilingual Fidelity**: Ensuring the AI maintained its "personality" across English, Bengali, and Hindi through localized system instructions.

## 🌟 Social Impact: "Gemma 4 Good"

Chhanda is designed for:
*   **Education**: Students in low-connectivity areas can index their textbooks and ask questions in their native language.
*   **Privacy Advocacy**: Professionals can handle sensitive data knowing the AI is physically disconnected from the cloud.
*   **Digital Equity**: One smart device can serve an entire group via the Gateway mode.
*   **Security**: Interactions via the Chhanda API are ephemeral and strictly in-memory, ensuring zero-trace professional workflows.

---

## 🖼️ Media Gallery
[Media Gallery]

### App Interface
*   **Dashboard**: Real-time telemetry monitoring TPS and hardware health.
*   **Web Gateway**: The QR-based connection interface for remote devices.
*   **Chat Experience**: Fluid, high-fidelity Markdown rendering in multiple languages.

---

## 📜 License
This project is submitted under the **CC-BY 4.0** license.

Developed by **Kallol Chakraborty** (Solo Developer) using **Antigravity IDE** and **Gemini 3 Flash**.
Dedicated to **Chhanda Chakraborty**.
