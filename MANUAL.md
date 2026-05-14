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
1.  **Hardware Telemetry**: Monitor your **RAM Usage**, **CPU Temperature**, and **TPS** (Tokens Per Second) in real-time. This helps you understand how the model is performing on your hardware.
2.  **Model Management**:
    *   **Scan**: Click the refresh icon to search your phone's internal storage for GGUF model files.
    *   **Activate**: Tap "Activate" on a model to load it into memory. *Note: Loading a large model may take 5-10 seconds depending on your RAM speed.*
    *   **Download**: Access the "Downloadable Models" section to fetch verified Gemma models directly.
3.  **Server Controls**:
    *   **Start/Stop Server**: Once a model is active, you can start the Ktor server to enable the Web Gateway.
    *   **QR Sharing**: Click the QR icon to generate a unique link. Scan this with any other device on your Wi-Fi or Hotspot to open the **Chhanda Web UI**.

---

## 💬 3. Chat: Interactive Intelligence
The Chat screen is where you communicate with the loaded LLM.


### Key Functions:
1.  **Tiered Context (RAG)**: Chhanda doesn't just chat; it remembers. It automatically pulls context from your **Knowledge Base** and **Current Attachments**.
2.  **Multimodal Attachments**:
    *   Click the **Clip Icon** to attach PDF, Word, Excel, or Image files.
    *   Images are processed via **OCR** to extract text.
    *   Documents are parsed and indexed into temporary memory for the duration of the chat.
3.  **Code & Tables**: The AI provides beautifully formatted Markdown code blocks with syntax highlighting and structured tables.
4.  **File Generation**: You can ask Chhanda to "Generate an Excel report" or "Write a Word document". The generated file will appear inline for immediate download.
5.  **Voice Interaction**: Use the microphone to speak your prompts. You can also listen to the AI's responses with natural, high-quality voices.

---

## 🧠 4. Knowledge Base: Long-Term Memory
The "Memory" or Knowledge Base screen manages your persistent data.


### Key Functions:
1.  **Document Ingestion**: View all files that have been vectorized and stored in your local database.
2.  **Search & Filter**: Quickly find specific documents or information within your indexed knowledge.
3.  **Storage Status**: Track how much space the vector database is consuming. 
4.  **Management**: Delete specific files or purge the entire database to free up memory.

---

## ⚙️ 5. Settings: Customization & Optimization
Tailor Chhanda to your device and personal preferences.


### Key Functions:
1.  **Vector Database (RAG) Toggle**: 
    *   **ON**: Full long-term memory experience.
    *   **OFF (Memory Saving Mode)**: Disables persistent vector storage to save RAM. The AI will only rely on direct attachments or its pre-trained knowledge. *Recommended for devices with < 6GB RAM.*
2.  **Voice Selection**: Choose between different natural-sounding voice profiles (e.g., Kallol, Chhanda).
3.  **Appearance**: Switch between Light, Dark, and System modes.
4.  **Auto-Delete**: Set a schedule (e.g., 30 days) to automatically clear old chat histories and free up storage.
5.  **API Management**: Configure your local API keys for programmatic access.

---

## 📝 6. System Logs
For power users and developers, the Logs screen provides a real-time feed of system events, server connections, and inference diagnostics. If you encounter an issue, check here first for error codes.

---

## 💡 Pro Tips:
*   **Privacy**: Remember, everything stays on your phone. Disconnecting from Wi-Fi will not stop Chhanda from working!
*   **Performance**: If the AI feels slow, try disabling the "Vector Database" in Settings or using a smaller 2B model.
*   **Web UI**: You can connect your laptop to your phone's Hotspot and use your laptop's keyboard to chat with Chhanda via the QR link!
*   **Expert Mode**: When you access Chhanda via the API (e.g., from a code editor), it automatically switches to an **Expert Senior Engineer** persona to provide better technical guidance.

---
**Developed with ❤️ by Kallol Chakraborty**
