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
                    parseAnyJsonValue(reader, stringBuilder)
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

    private fun parseAnyJsonValue(reader: android.util.JsonReader, out: StringBuilder) {
        when (reader.peek()) {
            android.util.JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    parseAnyJsonValue(reader, out)
                }
                reader.endArray()
            }
            android.util.JsonToken.BEGIN_OBJECT -> {
                parseJsonObjectAdaptively(reader, out)
            }
            else -> {
                reader.skipValue()
            }
        }
    }

    private fun parseJsonObjectAdaptively(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        var role: String? = null
        var text: String? = null
        var title: String? = null
        
        while (reader.hasNext()) {
            val name = reader.nextName()
            val token = reader.peek()
            when {
                name == "title" && token == android.util.JsonToken.STRING -> {
                    title = reader.nextString()
                }
                name == "mapping" && token == android.util.JsonToken.BEGIN_OBJECT -> {
                    val titlePrefix = if (title != null) ": $title" else ""
                    out.append("\n=== CONVERSATION$titlePrefix ===\n")
                    parseMapping(reader, out)
                }
                name == "role" && token == android.util.JsonToken.STRING -> {
                    role = reader.nextString()
                }
                name == "author" -> {
                    if (token == android.util.JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "role" && reader.peek() == android.util.JsonToken.STRING) {
                                role = reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else if (token == android.util.JsonToken.STRING) {
                        role = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                (name == "text" || name == "content" || name == "body" || name == "message" || name == "prompt" || name == "response") -> {
                    if (token == android.util.JsonToken.STRING) {
                        text = reader.nextString()
                    } else if (token == android.util.JsonToken.BEGIN_OBJECT) {
                        val contentBuilder = StringBuilder()
                        parseContentObject(reader, contentBuilder)
                        text = contentBuilder.toString().trim()
                    } else if (token == android.util.JsonToken.BEGIN_ARRAY) {
                        val contentBuilder = StringBuilder()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == android.util.JsonToken.STRING) {
                                contentBuilder.append(reader.nextString()).append(" ")
                            } else {
                                parseAnyJsonValue(reader, contentBuilder)
                            }
                        }
                        reader.endArray()
                        text = contentBuilder.toString().trim()
                    } else {
                        reader.skipValue()
                    }
                }
                token == android.util.JsonToken.BEGIN_OBJECT || token == android.util.JsonToken.BEGIN_ARRAY -> {
                    parseAnyJsonValue(reader, out)
                }
                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        
        if (role != null || text != null) {
            val r = role ?: "unknown"
            val t = text ?: ""
            if (t.isNotBlank()) {
                out.append("[$r]: $t\n")
            }
        }
    }

    private fun parseContentObject(reader: android.util.JsonReader, out: StringBuilder) {
        reader.beginObject()
        var isText = false
        while (reader.hasNext()) {
            val name = reader.nextName()
            val token = reader.peek()
            when {
                name == "content_type" && token == android.util.JsonToken.STRING -> {
                    if (reader.nextString() == "text") isText = true
                }
                name == "parts" && token == android.util.JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == android.util.JsonToken.STRING) {
                            out.append(reader.nextString()).append("\n")
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                }
                token == android.util.JsonToken.BEGIN_OBJECT || token == android.util.JsonToken.BEGIN_ARRAY -> {
                    parseAnyJsonValue(reader, out)
                }
                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
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

    override suspend fun ingestCsv(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            parseDelimited(uri, ',')
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "CSV extraction failed: ${e.message}")
            throw Exception("Failed to extract text from CSV file: ${e.message}")
        }
    }

    override suspend fun ingestTsv(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            parseDelimited(uri, '\t')
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "TSV extraction failed: ${e.message}")
            throw Exception("Failed to extract text from TSV file: ${e.message}")
        }
    }

    override suspend fun ingestXml(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stringBuilder = java.lang.StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(inputStream, null)
                var eventType = parser.eventType
                val tagStack = java.util.Stack<String>()
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            tagStack.push(parser.name)
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            val text = parser.text?.trim() ?: ""
                            if (text.isNotBlank() && tagStack.isNotEmpty()) {
                                val path = tagStack.joinToString(" > ")
                                stringBuilder.append("$path: $text\n")
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (tagStack.isNotEmpty()) tagStack.pop()
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "XML extraction failed: ${e.message}")
            try {
                context.contentResolver.openInputStream(uri)?.use { fallbackStream ->
                    stringBuilder.setLength(0)
                    stringBuilder.append(fallbackStream.bufferedReader().readText())
                }
            } catch (inner: Exception) {}
        }
        stringBuilder.toString().trim()
    }

    override suspend fun ingestHtml(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val html = inputStream.bufferedReader().readText()
                val doc = org.jsoup.Jsoup.parse(html)
                doc.select("script, style, iframe, noscript, header, footer, nav").remove()
                doc.body().text()
            } ?: throw Exception("Failed to open HTML stream")
        } catch (e: Exception) {
            android.util.Log.e("Ingestor", "HTML extraction failed: ${e.message}")
            throw Exception("Failed to extract text from HTML: ${e.message}")
        }
    }

    override suspend fun ingestMd(uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: throw Exception("Failed to open input stream for MD")
    }

    private fun parseDelimited(uri: Uri, delimiter: Char): String {
        val stringBuilder = java.lang.StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { reader ->
                val rows = mutableListOf<List<String>>()
                var lineCount = 0
                reader.forEachLine { line ->
                    if (lineCount < 5000) {
                        val row = parseCsvRow(line, delimiter)
                        if (row.isNotEmpty()) {
                            rows.add(row)
                        }
                    }
                    lineCount++
                }
                
                if (rows.isNotEmpty()) {
                    val headers = rows.first()
                    for (i in 1 until rows.size) {
                        val row = rows[i]
                        stringBuilder.append("### Record ${i} ###\n")
                        for (j in 0 until headers.size) {
                            val colName = headers.getOrNull(j) ?: "Column_$j"
                            val colVal = row.getOrNull(j) ?: ""
                            stringBuilder.append("$colName: $colVal\n")
                        }
                        stringBuilder.append("\n")
                    }
                }
            }
        }
        return stringBuilder.toString().trim()
    }

    private fun parseCsvRow(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var curVal = java.lang.StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    curVal.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == delimiter && !inQuotes) {
                result.add(curVal.toString().trim())
                curVal = java.lang.StringBuilder()
            } else {
                curVal.append(c)
            }
            i++
        }
        result.add(curVal.toString().trim())
        return result
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
