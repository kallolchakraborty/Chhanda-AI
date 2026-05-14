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
            
            val lines = content.lines()
            val margin = 50f
            var y = margin
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas

            for (rawLine in lines) {
                if (y > 780) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = margin
                }
                
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    y += 15f
                    continue
                }
                
                val isHeading = line.startsWith("#")
                if (isHeading) {
                    paint.textSize = 18f
                    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                } else {
                    paint.textSize = 12f
                    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                }
                
                val textToDraw = line.replace("#", "").replace("**", "").trim()
                
                val words = textToDraw.split(" ")
                var currentLine = ""
                val leftMargin = margin + if (line.startsWith("- ") || line.startsWith("* ")) 20f else 0f
                
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    val width = paint.measureText(testLine)
                    if (width > 595 - margin - leftMargin) {
                        canvas.drawText(currentLine, leftMargin, y, paint)
                        y += if (isHeading) 26f else 20f
                        currentLine = word
                    } else {
                        currentLine = testLine
                    }
                }
                if (currentLine.isNotEmpty()) {
                    canvas.drawText(currentLine, leftMargin, y, paint)
                    y += if (isHeading) 26f else 20f
                }
                y += 5f
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
            
            content.lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    val p = document.createParagraph()
                    val r = p.createRun()
                    r.addBreak()
                    return@forEach
                }
                
                val p = document.createParagraph()
                val isHeading = line.startsWith("#")
                val isBullet = line.startsWith("- ") || line.startsWith("* ")
                
                var cleanLine = line
                if (isHeading) {
                    val hashes = line.takeWhile { it == '#' }.length
                    cleanLine = line.substring(hashes).trim()
                    p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                } else if (isBullet) {
                    cleanLine = line.substring(2).trim()
                    p.indentationLeft = 400
                }
                
                // Parse bold text
                val boldParts = cleanLine.split("**")
                boldParts.forEachIndexed { index, part ->
                    if (part.isNotEmpty()) {
                        val r = p.createRun()
                        if (index % 2 == 1) { // It's bold
                            r.isBold = true
                        }
                        if (isHeading) {
                            r.isBold = true
                            r.fontSize = 16
                        } else {
                            r.fontSize = 12
                        }
                        r.setText(part)
                    }
                }
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
            
            val headerStyle = workbook.createCellStyle().apply {
                val font = workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 12
                }
                setFont(font)
                fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
                borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
            }
            
            val lines = content.lines()
                .filter { it.isNotBlank() }
                .filter { !it.matches(Regex("^[|\\-\\s:]+$")) }
                
            lines.forEachIndexed { rowIndex, line ->
                val row = sheet.createRow(rowIndex)
                val cells = if (line.contains("|")) {
                    line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    line.split(",").map { it.trim() }
                }
                
                cells.forEachIndexed { colIndex, cellValue ->
                    val cell = row.createCell(colIndex)
                    cell.setCellValue(cellValue.replace("**", ""))
                    if (rowIndex == 0) {
                        cell.cellStyle = headerStyle
                    }
                }
            }
            
            // Auto size columns
            if (lines.isNotEmpty()) {
                val numCols = sheet.getRow(0)?.physicalNumberOfCells ?: 0
                for (i in 0 until numCols) {
                    sheet.autoSizeColumn(i)
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
