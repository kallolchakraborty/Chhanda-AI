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

    override suspend fun ingestPdf(uri: Uri): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chunks = mutableListOf<String>()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fileDescriptor ->
                val pdfRenderer = android.graphics.pdf.PdfRenderer(fileDescriptor)
                val pageCount = pdfRenderer.pageCount
                for (i in 0 until pageCount) {
                    pdfRenderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        
                        val text = extractTextFromBitmap(bitmap)
                        if (text.isNotBlank()) {
                            chunks.add(text)
                        }
                    }
                }
                pdfRenderer.close()
            }
        } catch (e: Exception) {
            throw Exception("Failed to extract text from PDF: ${e.message}")
        }
        chunks
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
                val zipInputStream = java.util.zip.ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        factory.isNamespaceAware = true // IMPORTANT for Word XML
                        val builder = factory.newDocumentBuilder()
                        val doc = builder.parse(zipInputStream)
                        
                        // Use getElementsByTagNameNS if namespace aware, or handle both
                        val nodeList = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
                        val altNodeList = if (nodeList.length == 0) doc.getElementsByTagName("w:t") else nodeList
                        
                        for (i in 0 until altNodeList.length) {
                            val node = altNodeList.item(i)
                            val text = node.textContent
                            if (!text.isNullOrBlank()) {
                                stringBuilder.append(text)
                                stringBuilder.append(" ")
                            }
                        }
                        android.util.Log.d("Ingestor", "Extracted ${stringBuilder.length} characters from Word document.")
                        break
                    }
                    entry = zipInputStream.nextEntry
                }
            }
        } catch (e: Exception) {
            throw Exception("Failed to extract text from Word file: ${e.message}")
        }
        stringBuilder.toString().trim()
    }
}
