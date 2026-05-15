# 📘 Chhanda AI - User Manual
**Your Comprehensive Guide to the 100% Offline AI Gateway**

---

## 🚀 1. Getting Started
When you first launch Chhanda, you will be greeted by the **Welcome Screen**. 

*   **Permissions**: Chhanda requires access to your storage (to read documents and store the vector database) and notification permissions (to run the AI server in the background).
*   **Initialization**: The app will perform a quick diagnostic to check your device's RAM and CPU capabilities.

---

## 📊 2. Dashboard: The Command Center
The Dashboard is your primary hub for monitoring system health and managing AI models.

### Key Functions:
1.  **Hardware Telemetry**: Monitor your **RAM Usage**, **Thermal Status**, and **TPS** (Tokens Per Second).
    *   **Health Icons**: Real-time monitoring via the **Thermostat** (Thermal) and **Psychology** (Vector Memory) icons located directly on the Active Model card. They change color (Green -> Yellow -> Red) based on system load.
2.  **Model Management**:
    *   **Scan**: Refresh to find GGUF model files on your device.
    *   **Activate**: Load a model into memory. (Takes 5-10s).
3.  **Server Controls**:
    *   **Start/Stop Server**: Enable the Web Gateway.
    *   **QR Sharing**: Scan to open the **Chhanda Web UI** on any device in your local network.

---

## 💬 3. Chat: Interactive Intelligence
The Chat screen is where you communicate with the loaded LLM.

### Key Functions:
1.  **Thinking Mode**: Enable reasoning traces to see how the AI "thinks" before it answers.
2.  **Multimodal RAG**:
    *   **Attachments**: Click the clip icon to add PDF, Word, Excel, Images, JSON, or CSV files.
    *   **Datasets**: Drop a Kaggle CSV directly into the chat for instant analysis.
    *   **Audio**: Support for MP3 and M4A indexing.
3.  **File Generation**: Ask the AI to create Excel, Word, or PDF files, then download them locally.
4.  **Voice Interaction**: High-quality Bengali, Hindi, and English TTS with a background playback bar.
5.  **Web Scraping**: Paste a URL to index website content instantly.

---

## 🧠 4. Knowledge Base: Long-Term Memory
Manage your persistent knowledge and monitor RAG performance.

### Key Functions:
1.  **Document Ingestion**: View and manage all vectorized files.
2.  **Performance Dashboard**: Track p99 Latency, Throughput, and Recall@K quality.
3.  **Optimized Search**: Uses a high-speed Min-Heap search for near-instant retrieval.

---

## ⚙️ 5. Settings: Customization & Hardened Security
1.  **Hardware-Backed Security**: Sensitive tokens (HF_TOKEN, API_KEY) are now automatically stored in the **Android Secure KeyStore**. No manual setup is required, but you can feel safe knowing your keys are protected by your phone's hardware security chip (TEE/SE).
2.  **TurboQuant**: Hardware-level KV-cache compression for faster, longer chats.
3.  **Advanced PDF Ingestion**: Chhanda now intelligently switches between high-speed native extraction and deep OCR. For most PDFs, you will see a **10x speed increase** in indexing.
4.  **Vector Database (RAG)**: Toggle long-term memory. The search engine is now optimized with a **Min-Heap** for sub-millisecond retrieval.
5.  **Hardware Resilience**: Automatic thermal-aware throttling to protect your phone.
6.  **Expert Persona**: Automatically switches to a **Senior Software Engineer** when accessed via API/IDE.
7.  **Memory Hygiene**: Integrated **LeakCanary** and automated RAM flush protocols ensure the app stays snappy even after long sessions with large models.

---

## 🎨 6. Premium UI & Motion
*   **Shared Element Transitions**: When you click "Try It" on an active model, you'll notice the model's logo and name "fly" and morph into the chat header. This is a high-fidelity visual anchor that maintains your context.
*   **High-Density Telemetry**: The Active Model card now consolidates all critical health data (Temp, Vector RAM, Thermal Status) into a single, high-density monitoring hub.

---

## 💡 Pro Tips:
*   **Privacy**: 100% Offline. Your data never leaves the phone.
*   **Performance**: If the phone gets hot, Chhanda will auto-throttle to keep things safe.
*   **Productivity**: Connect your laptop to your phone's hotspot to use the Web UI on a big screen!

---
**Developed with ❤️ by Kallol Chakraborty**
