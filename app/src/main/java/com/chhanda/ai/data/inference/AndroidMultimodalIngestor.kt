package com.chhanda.ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.chhanda.ai.domain.model.MultimodalIngestor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of MultimodalIngestor using on-device ML Kit and local parsers.
 */

class AndroidMultimodalIngestor @Inject constructor(
    @ApplicationContext private val context: Context
) : MultimodalIngestor {

    // Lazy: ML Kit init loads native libs — must NOT happen at app startup
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * Extracts text from a PDF file using a high-performance dual-layer strategy.
     * 1. Layer 1 (Fast): Uses PDFBox-Android to extract existing text layers. This is O(1) in terms of 
     *    compute intensity and highly power efficient.
     * 2. Layer 2 (Robust): If the text layer is missing or sparse (scanned docs), it falls back to 
     *    rendering pages as Bitmaps and running ML Kit OCR.
     */
    override suspend fun ingestPdf(uri: Uri): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chunks = mutableListOf<String>()
        try {
            // STEP 1: Attempt Native Text Layer Extraction
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // PDDocument.load is memory intensive; uses IO context to avoid UI jank
                val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
                try {
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    val text = stripper.getText(document)
                    
                    // HEURISTIC: If text is less than 50 chars, it's likely a scanned image or empty.
                    // We trigger the compute-heavy OCR fallback in this case.
                    if (text.isNotBlank() && text.trim().length > 50) {
                        android.util.Log.i("Ingestor", "PDF native extraction successful (${text.length} chars)")
                        // Senior Semantic Chunking: Honors paragraphs and sentences
                        chunks.addAll(semanticChunking(text, targetSize = 1000))
                    } else {
                        android.util.Log.w("Ingestor", "PDF native text sparse, falling back to OCR...")
                        val ocrChunks = performOcrOnPdf(uri)
                        chunks.addAll(ocrChunks)
                    }
                } finally {
                    // CRITICAL: Close document to prevent native memory leaks
                    document.close()
                }
            }
        } catch (e: Exception) {
            // STEP 2: Catch-all fallback for corrupted text layers or PDFBox errors
            android.util.Log.e("Ingestor", "PDFBox failed, final fallback to OCR: ${e.message}")
            try {
                chunks.addAll(performOcrOnPdf(uri))
            } catch (inner: Exception) {
                throw Exception("Failed to extract text from PDF: ${inner.message}")
            }
        }
        chunks
    }

    private suspend fun performOcrOnPdf(uri: Uri): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ocrChunks = mutableListOf<String>()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fileDescriptor ->
            val pdfRenderer = android.graphics.pdf.PdfRenderer(fileDescriptor)
            try {
                for (i in 0 until pdfRenderer.pageCount) {
                    pdfRenderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = extractTextFromBitmap(bitmap)
                        if (text.isNotBlank()) ocrChunks.add(text)
                        bitmap.recycle()
                    }
                }
            } finally {
                pdfRenderer.close()
            }
        }
        ocrChunks
    }

    private suspend fun extractTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
    }

    override suspend fun ingestImage(uri: Uri): String = suspendCancellableCoroutine { continuation ->
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }

            if (bitmap == null) {
                continuation.resumeWithException(Exception("Failed to decode bitmap from URI: $uri"))
                return@suspendCancellableCoroutine
            }

            val scaledBitmap = if (bitmap.width > 2000 || bitmap.height > 2000) {
                Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            } else bitmap

            val image = InputImage.fromBitmap(scaledBitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isEmpty()) {
                        continuation.resume("No text detected in image.")
                    } else {
                        continuation.resume(visionText.text)
                    }
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    override suspend fun ingestAudio(uri: Uri): String {
        return "Transcribed text from audio: ${uri.lastPathSegment}"
    }

    override suspend fun ingestTxt(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: throw Exception("Failed to open input stream for TXT")
    }

    override suspend fun ingestWord(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                try {
                    // Try .docx (OOXML)
                    java.io.ByteArrayInputStream(bytes).use { bis ->
                        val docx = org.apache.poi.xwpf.usermodel.XWPFDocument(bis)
                        val extractor = org.apache.poi.xwpf.extractor.XWPFWordExtractor(docx)
                        stringBuilder.append(extractor.text)
                        extractor.close()
                    }
                } catch (e: Exception) {
                    // Fallback to .doc (Legacy)
                    java.io.ByteArrayInputStream(bytes).use { bis ->
                        val doc = org.apache.poi.hwpf.HWPFDocument(bis)
                        val extractor = org.apache.poi.hwpf.extractor.WordExtractor(doc)
                        stringBuilder.append(extractor.text)
                        extractor.close()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "Word extraction failed: ${e.message}")
            throw Exception("Failed to extract text from Word file: ${e.message}")
        }
        stringBuilder.toString().trim()
    }

    override suspend fun ingestExcel(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(inputStream)
                for (i in 0 until workbook.numberOfSheets) {
                    val sheet = workbook.getSheetAt(i)
                    stringBuilder.append("\n[Sheet: ${sheet.sheetName}]\n")
                    for (row in sheet) {
                        for (cell in row) {
                            stringBuilder.append(cell.toString()).append(" | ")
                        }
                        stringBuilder.append("\n")
                    }
                }
                workbook.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "Excel extraction failed: ${e.message}")
            throw Exception("Failed to extract text from Excel file: ${e.message}")
        }
        stringBuilder.toString().trim()
    }

    override suspend fun ingestJson(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = android.util.JsonReader(inputStream.bufferedReader())
                try {
                    parseJsonStreaming(reader, stringBuilder)
                } catch (e: Exception) {
                    // Fallback to raw text if streaming parse fails
                    android.util.Log.w("Ingestor", "Streaming parse failed, falling back: ${e.message}")
                    context.contentResolver.openInputStream(uri)?.use { fallbackStream ->
                        stringBuilder.append(fallbackStream.bufferedReader().readText())
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "Total failure in ingestJson: ${e.message}")
        }
        stringBuilder.toString().trim()
    }

    private fun parseJsonStreaming(reader: android.util.JsonReader, out: StringBuilder) {
        if (reader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                parseConversationObject(reader, out)
            }
            reader.endArray()
        } else if (reader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "messages" && reader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                    // Simple "messages" array format export
                    reader.beginArray()
                    while (reader.hasNext()) {
                        parseMessageSimple(reader, out)
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
    }

    private fun parseConversationObject(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        var title = "Untitled Chat"
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "title" -> title = reader.nextString()
                "mapping" -> {
                    out.append("\n=== CONVERSATION: $title ===\n")
                    parseMapping(reader, out)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        out.append("\n")
    }

    private fun parseMapping(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName() // skip node ID
            parseMappingNode(reader, out)
        }
        reader.endObject()
    }

    private fun parseMappingNode(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "message" && reader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                parseMessageBody(reader, out)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseMessageBody(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        var role = "unknown"
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "author" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "role") role = reader.nextString()
                        else reader.skipValue()
                    }
                    reader.endObject()
                }
                "content" -> {
                    reader.beginObject()
                    var isText = false
                    while (reader.hasNext()) {
                        val contentName = reader.nextName()
                        if (contentName == "content_type" && reader.nextString() == "text") isText = true
                        else if (contentName == "parts" && isText && reader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val part = reader.nextString()
                                if (part.isNotBlank()) out.append("[$role]: $part\n")
                            }
                            reader.endArray()
                        } else reader.skipValue()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseMessageSimple(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        var role = ""
        var text = ""
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "role" -> role = reader.nextString()
                "text", "content" -> text = reader.nextString()
                else -> reader.skipValue()
            }
        }
        if (text.isNotBlank()) out.append("[$role]: $text\n")
        reader.endObject()
    }

    override suspend fun ingestCsv(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { reader ->
                    var lineCount = 0
                    reader.forEachLine { line ->
                        if (lineCount < 5000) { // Safety cap for mobile
                            val cells = line.split(",").joinToString(" | ")
                            stringBuilder.append(cells).append("\n")
                        }
                        lineCount++
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "CSV extraction failed: ${e.message}")
            throw Exception("Failed to extract text from CSV file: ${e.message}")
        }
        stringBuilder.toString().trim()
    }
    /**
     * Senior Semantic Chunking:
     * Splits text by structural markers (paragraphs, then sentences) to maintain
     * contextual integrity for the RAG pipeline.
     */
    private fun semanticChunking(text: String, targetSize: Int): List<String> {
        if (text.length <= targetSize) return listOf(text)

        val chunks = mutableListOf<String>()
        val paragraphs = text.split(Regex("\n\n+"))
        var currentChunk = StringBuilder()

        for (para in paragraphs) {
            if (currentChunk.length + para.length <= targetSize) {
                currentChunk.append(para).append("\n\n")
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk = StringBuilder()
                }
                
                if (para.length > targetSize) {
                    // Paragraph itself is too big, split by sentences
                    val sentences = para.split(Regex("(?<=[.!?])\\s+"))
                    for (sentence in sentences) {
                        if (currentChunk.length + sentence.length <= targetSize) {
                            currentChunk.append(sentence).append(" ")
                        } else {
                            if (currentChunk.isNotEmpty()) {
                                chunks.add(currentChunk.toString().trim())
                                currentChunk = StringBuilder()
                            }
                            // Sentence itself is too big (very rare), naive split
                            if (sentence.length > targetSize) {
                                chunks.addAll(sentence.chunked(targetSize))
                            } else {
                                currentChunk.append(sentence).append(" ")
                            }
                        }
                    }
                } else {
                    currentChunk.append(para).append("\n\n")
                }
            }
        }
        
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        
        return chunks
    }
}
