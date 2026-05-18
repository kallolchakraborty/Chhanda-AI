package com.chhanda.ai.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    /**
     * Tries to get a path from URI, or copies the content to a temporary file in filesDir
     * and returns that path. LlmInference REQUIRES a physical path.
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        // 1. Check if it's a file URI
        if (uri.scheme == "file") return uri.path

        // 2. Otherwise, copy to internal storage to ensure physical path access
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileName(context, uri) ?: "imported_model.bin"
            val targetFile = File(context.filesDir, "models/$fileName")
            if (targetFile.parentFile?.exists() == false) targetFile.parentFile?.mkdirs()

            FileOutputStream(targetFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            return targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Saves base64 data to a file in cache directory and returns its Uri.
     */
    fun saveBase64ToFile(context: Context, base64Data: String, fileName: String): Uri? {
        try {
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val targetFile = File(context.cacheDir, "web_uploads/$fileName")
            if (targetFile.parentFile?.exists() == false) targetFile.parentFile?.mkdirs()
            
            FileOutputStream(targetFile).use { outputStream ->
                outputStream.write(decodedBytes)
            }
            return Uri.fromFile(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun getFileDetails(context: android.content.Context, uri: android.net.Uri): Pair<String, Long> {
        var name = "unknown"
        var size = 0L
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1) name = it.getString(nameIndex)
                    if (sizeIndex != -1) size = it.getLong(sizeIndex)
                }
            }
        } else if (uri.scheme == "file") {
            val file = java.io.File(uri.path ?: "")
            name = file.name
            size = file.length()
        }
        if (name == "unknown" || name.isBlank()) {
            name = uri.lastPathSegment ?: "unknown"
        }
        return Pair(name, size)
    }

    /**
     * Probes the first 4 magic bytes of the file stream if the resolver MIME is generic or empty.
     */
    fun probeMimeType(context: Context, uri: Uri): String {
        val resolverMime = try {
            context.contentResolver.getType(uri)?.lowercase()
        } catch (e: Exception) {
            null
        }
        
        if (!resolverMime.isNullOrBlank() && resolverMime != "application/octet-stream" && resolverMime != "binary/octet-stream") {
            return resolverMime
        }

        // Probe magic bytes
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(4)
                val read = stream.read(bytes)
                if (read >= 2) {
                    val hex = bytes.take(read).joinToString("") { "%02X".format(it) }
                    
                    // PDF Magic bytes: %PDF (25 50 44 46)
                    if (hex.startsWith("25504446")) return "application/pdf"
                    
                    // PNG Magic bytes: 89 50 4E 47
                    if (hex.startsWith("89504E47")) return "image/png"
                    
                    // JPEG Magic bytes: FF D8
                    if (hex.startsWith("FFD8")) return "image/jpeg"
                    
                    // GIF Magic bytes: 47 49 46 38
                    if (hex.startsWith("47494638")) return "image/gif"
                    
                    // ZIP / Office Open XML (DOCX, XLSX): PK.. (50 4B 03 04)
                    if (hex.startsWith("504B0304")) {
                        return "application/zip"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return resolverMime ?: "application/octet-stream"
    }

    /**
     * Inspects zip entries to distinguish docx/xlsx.
     */
    fun isZipWordOrExcel(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                java.util.zip.ZipInputStream(stream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (entry.name.contains("word/document.xml")) {
                            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        } else if (entry.name.contains("xl/workbook.xml")) {
                            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        }
                        entry = zipStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getFileName(context: android.content.Context, uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && result != null) {
                result = result.substring(cut!! + 1)
            }
        }
        return result
    }

    fun createImageUri(context: Context): Uri? {
        try {
            val imageDir = File(context.cacheDir, "camera_images")
            if (!imageDir.exists()) imageDir.mkdirs()
            val file = File(imageDir, "IMG_${System.currentTimeMillis()}.jpg")
            return androidx.core.content.FileProvider.getUriForFile(
                context,
                "com.chhanda.ai.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
