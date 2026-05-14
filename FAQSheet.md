# ❓ Chhanda AI - Frequently Asked Questions (FAQ)

## 🔒 Privacy & Security

### 1. Is Chhanda really 100% offline?
**Yes.** Once the app and models are downloaded, Chhanda requires zero internet for inference, RAG (document search), and chat. The only feature that requires internet is the initial "Scraping" phase of a website URL. All your data, models, and embeddings stay strictly within the Android app sandbox.

### 2. Are my API interactions stored?
**It depends on the source.** 
*   **Local & Web (QR) Chat**: Stored in your local history for your convenience.
*   **API Access**: Ephemeral. Any request tagged as "API" (e.g., from a code editor) is processed strictly in-memory and is **never persisted** to the database to ensure maximum development security.

### 3. How secure is the Web Gateway?
The built-in Ktor server uses mandatory **API Key Authentication**. Additionally, if an unauthorized IP address attempts to discover the server, they are met with a "Captive Portal" that restricts access until they are validated.

---

## 🚀 Performance & Hardware

### 4. Why is the AI responding slowly?
Inference speed (TPS - Tokens Per Second) depends on your device's hardware:
*   **RAM**: Larger models (4B/8B) require more RAM and may be slower on mid-range devices.
*   **Thermal Throttling**: If your phone gets hot, the OS may slow down the processor.
*   **Background Tasks**: Heavy document indexing in the background can temporarily impact chat speed.

### 5. My app crashed when loading a model. What happened?
This is usually an **Out of Memory (OOM)** event. If your device has < 6GB of RAM, loading a 4B model while having other apps open can cause the Android OS to reclaim memory. 
*   **Solution**: Close background apps, use a smaller 1.5B or 2B model, and enable "Memory Saving Mode" (Disable RAG) in Settings.

### 6. Does Chhanda drain battery?
Running Large Language Models locally is one of the most CPU/GPU intensive tasks a phone can perform. You will notice increased battery drain and heat during long sessions. Using the Web Gateway while the device is charging is recommended for prolonged use.

---

## 🧠 Knowledge Base & RAG

### 7. What is RAG and why should I use it?
**RAG (Retrieval-Augmented Generation)** allows the AI to "read" your personal documents. Instead of just relying on its pre-trained knowledge, it searches your PDFs, Word files, and Excel sheets to provide accurate, context-aware answers based on **your** data.

### 8. What file formats are supported?
Chhanda supports:
*   **Documents**: PDF, DOCX, DOC, XLSX, XLS, TXT.
*   **Images**: JPG, PNG (processed via OCR).
*   **Web**: Any public URL.
*   **Audio**: Voice notes (processed via Speech-to-Text).

### 9. Why can't the AI find information in my large document?
*   **Indexing Time**: Large files (50+ pages) take time to vectorize. Check the "Memory" screen to ensure the file is fully "Indexed".
*   **Similarity Threshold**: If the information is too vague, the search engine (threshold 0.82) might filter it out to prevent hallucinations. Try being more specific in your query.

---

## 🌐 Connectivity & Gateway

### 10. Can I use Chhanda on my PC?
**Yes!** Start the server on your phone, click the QR icon, and scan/enter the link on your PC browser. Ensure both devices are on the same Wi-Fi or that the PC is connected to the phone's Hotspot.

### 11. Can multiple people use one Chhanda Gateway?
Yes, the system supports multiple concurrent sessions. However, each active connection shares the device's hardware resources. Performance will decrease as more users chat simultaneously.

---

## 🛠️ Models & Technical

### 12. What models does Chhanda support?
Chhanda is optimized for **Google Gemma** (2B/4B/7B) in **LiteRT/GGUF** format. It also supports other LiteRT-compatible models like Phi-4 and Qwen-2.5.

### 13. How do I add my own models?
You can manually place your `.litertlm`, `.bin`, or `.tflite` model files in your phone's **Downloads** folder. Click the **Scan** icon on the Dashboard, and Chhanda will automatically find and register them.

---

**Have more questions?** Check the [User Manual](MANUAL.md) or the [System Logs](Dashboard) for technical diagnostics.
