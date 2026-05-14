package com.chhanda.ai.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

object DocumentGenerator {

    /**
     * Generates a PDF file from text.
     */
    fun generatePdf(context: Context, fileName: String, content: String): File? {
        val targetFile = File(context.filesDir, "generated/$fileName")
        if (targetFile.parentFile?.exists() == false) targetFile.parentFile?.mkdirs()

        try {
            val pdfDocument = PdfDocument()
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
            }
            
            // Simple pagination logic
            val lines = content.lines()
            val margin = 50f
            var y = margin
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas

            for (line in lines) {
                if (y > 800) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = margin
                }
                canvas.drawText(line, margin, y, paint)
                y += 20f
            }
            
            pdfDocument.finishPage(page)
            FileOutputStream(targetFile).use { pdfDocument.writeTo(it) }
            pdfDocument.close()
            return targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Generates a Word file (.docx) from text.
     */
    fun generateWord(context: Context, fileName: String, content: String): File? {
        val targetFile = File(context.filesDir, "generated/$fileName")
        if (targetFile.parentFile?.exists() == false) targetFile.parentFile?.mkdirs()

        try {
            val document = XWPFDocument()
            val paragraph = document.createParagraph()
            val run = paragraph.createRun()
            
            content.lines().forEach { line ->
                run.setText(line)
                run.addBreak()
            }
            
            FileOutputStream(targetFile).use { document.write(it) }
            document.close()
            return targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Generates an Excel file (.xlsx) from structured data.
     * Content is expected to be CSV-like or a Markdown table.
     */
    fun generateExcel(context: Context, fileName: String, content: String): File? {
        val targetFile = File(context.filesDir, "generated/$fileName")
        if (targetFile.parentFile?.exists() == false) targetFile.parentFile?.mkdirs()

        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Data")
            
            val lines = content.lines().filter { it.isNotBlank() }
            lines.forEachIndexed { rowIndex, line ->
                val row = sheet.createRow(rowIndex)
                // Split by | (markdown table) or , (CSV)
                val cells = if (line.contains("|")) {
                    line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    line.split(",").map { it.trim() }
                }
                
                cells.forEachIndexed { colIndex, cellValue ->
                    row.createCell(colIndex).setCellValue(cellValue)
                }
            }
            
            FileOutputStream(targetFile).use { workbook.write(it) }
            workbook.close()
            return targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
