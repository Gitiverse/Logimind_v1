package com.example.data.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.FormEntry
import com.example.data.model.FormTemplate
import com.example.data.model.Patient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportService {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private fun formatDisplayAnswer(answer: String): String {
        if (answer.trim().startsWith("{")) {
            try {
                val obj = JSONObject(answer)
                if (obj.has("choices_perf")) {
                    val choicesPerf = obj.optJSONObject("choices_perf")
                    val resultList = mutableListOf<String>()
                    if (choicesPerf != null) {
                        val keys = choicesPerf.keys()
                        while (keys.hasNext()) {
                            val choice = keys.next()
                            val performed = choicesPerf.optBoolean(choice, false)
                            resultList.add("$choice (Performed by me: ${if (performed) "Yes" else "No"})")
                        }
                    }
                    if (resultList.isNotEmpty()) {
                        return resultList.joinToString(", ")
                    }
                    val choicesStr = obj.optString("choices")
                    if (!choicesStr.isNullOrBlank()) {
                        return choicesStr
                    }
                } else if (obj.has("procedure")) {
                    val proc = obj.optString("procedure")
                    val perf = obj.optBoolean("performedByMe", false)
                    val completed = obj.optBoolean("completed", false)
                    if (completed) {
                        return "$proc (Performed by me: ${if (perf) "Yes" else "No"})"
                    } else {
                        return "$proc (Not Completed)"
                    }
                }
            } catch (e: Exception) {}
        }
        if (answer.equals("true", ignoreCase = true)) return "Yes"
        if (answer.equals("false", ignoreCase = true)) return "No"
        return answer
    }

    // Generates a professional monochrome printable PDF of log entries
    fun generatePdf(
        context: Context,
        entries: List<FormEntry>,
        fieldLabelMap: Map<String, String> = emptyMap()
    ): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 standard width in points
        val pageHeight = 842 // A4 standard height in points
        
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        // Paint definitions
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        val sectionPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val boldBodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val linePaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }

        var yPos = 60f
        val margin = 40f
        val contentWidth = pageWidth - (margin * 2)

        // Helper to handle text wrapping and page breaks
        fun drawWrappedText(text: String, paint: Paint, offsetLeft: Float = 0f): Float {
            val words = text.split(" ")
            var currentLine = StringBuilder()
            var linesDrawn = 0
            val leading = paint.textSize + 4f

            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                val textWidth = paint.measureText(testLine)
                if (textWidth > (contentWidth - offsetLeft)) {
                    // Check page overflow
                    if (yPos + leading > pageHeight - margin) {
                        pdfDocument.finishPage(page)
                        pageNum++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        yPos = margin + 20f
                    }
                    canvas.drawText(currentLine.toString(), margin + offsetLeft, yPos, paint)
                    yPos += leading
                    linesDrawn++
                    currentLine = StringBuilder(word)
                } else {
                    currentLine.append(if (currentLine.isEmpty()) word else " $word")
                }
            }

            if (currentLine.isNotEmpty()) {
                if (yPos + leading > pageHeight - margin) {
                    pdfDocument.finishPage(page)
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    yPos = margin + 20f
                }
                canvas.drawText(currentLine.toString(), margin + offsetLeft, yPos, paint)
                yPos += leading
                linesDrawn++
            }
            return linesDrawn * leading
        }

        // Header Section
        canvas.drawText("LOGIMIND CLINICAL LOGBOOK REPORT", margin, yPos, titlePaint)
        yPos += 22f
        canvas.drawText("Exported On: ${dateFormatter.format(Date())} | Total Logs: ${entries.size} Record(s)", margin, yPos, subtitlePaint)
        yPos += 15f
        canvas.drawLine(margin, yPos, pageWidth - margin, yPos, linePaint)
        yPos += 25f

        for ((index, entry) in entries.withIndex()) {
            // Check spacing before beginning entry block
            if (yPos > pageHeight - 120f) {
                pdfDocument.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPos = margin + 20f
            }

            // Entry Header Box
            canvas.drawRect(margin, yPos, pageWidth - margin, yPos + 35f, borderPaint)
            canvas.drawText("${index + 1}. LOG ENTITY RECORD: ${entry.title}", margin + 10f, yPos + 15f, sectionPaint)
            val dateStr = dateFormatter.format(Date(entry.createdAt))
            canvas.drawText("Logged Date: $dateStr", margin + 10f, yPos + 28f, subtitlePaint)
            yPos += 45f

            // Patient details row
            canvas.drawText("Patient: ", margin + 10f, yPos, boldBodyPaint)
            canvas.drawText(entry.patientName ?: "General Theatre", margin + 70f, yPos, bodyPaint)
            yPos += 15f

            // Answers Map parsing
            try {
                if (!entry.answersJson.isBlank()) {
                    val answers = JSONObject(entry.answersJson)
                    val keys = answers.keys()
                    
                    canvas.drawText("Clinical Question & Response Checklist:", margin + 10f, yPos, boldBodyPaint)
                    yPos += 15f
                    
                    if (!keys.hasNext()) {
                        canvas.drawText("No questions answered.", margin + 20f, yPos, subtitlePaint)
                        yPos += 15f
                    }

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val answerValue = answers.optString(key)
                        val formattedLabel = fieldLabelMap[key] ?: key.split("_").joinToString(" ") { fieldPart ->
                            fieldPart.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        }
                        
                        // Ensure yPos has room for the label before drawing it
                        if (yPos + 22f > pageHeight - margin) {
                            pdfDocument.finishPage(page)
                            pageNum++
                            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                            page = pdfDocument.startPage(pageInfo)
                            canvas = page.canvas
                            yPos = margin + 20f
                        }
                        
                        canvas.drawText("• $formattedLabel:", margin + 20f, yPos, boldBodyPaint)
                        yPos += boldBodyPaint.textSize + 4f
                        
                        val displayAns = formatDisplayAnswer(answerValue)
                        drawWrappedText(displayAns, bodyPaint, offsetLeft = 32f)
                        
                        // Parse attribution for field if present
                        if (!entry.attributionsJson.isNullOrBlank()) {
                            val attributions = JSONObject(entry.attributionsJson)
                            val attrObj = attributions.optJSONObject(key)
                            if (attrObj != null) {
                                val providerName = attrObj.optString("providerName", "Unknown")
                                val providerType = attrObj.optString("providerType", "Student")
                                val supervisor = attrObj.optString("supervisorWitness", "")
                                val verified = attrObj.optBoolean("isWitnessVerified", false)
                                
                                val attrText = "  └ Attributed To: $providerName ($providerType) | Witness: ${if (supervisor.isBlank()) "None" else supervisor}${if (verified) " (✓ Verified)" else ""}"
                                drawWrappedText(attrText, subtitlePaint, offsetLeft = 32f)
                            }
                        }
                        yPos += 5f
                    }
                }
            } catch (e: Exception) {
                drawWrappedText("Failed parsing data responses: ${e.localizedMessage}", subtitlePaint, offsetLeft = 20f)
            }

            yPos += 15f
            canvas.drawLine(margin, yPos, pageWidth - margin, yPos, linePaint)
            yPos += 20f
        }

        // Add page footer to all pages before generating
        pdfDocument.finishPage(page)

        val tempFile = File(context.cacheDir, "LogiMind_Report_${fileDateFormatter.format(Date())}.pdf")
        val outputStream = FileOutputStream(tempFile)
        pdfDocument.writeTo(outputStream)
        outputStream.flush()
        outputStream.close()
        pdfDocument.close()
        
        return tempFile
    }

    // Generates a standardized comma-separated clinical schema output
    fun generateCsv(context: Context, entries: List<FormEntry>): File {
        val tempFile = File(context.cacheDir, "LogiMind_Export_${fileDateFormatter.format(Date())}.csv")
        val fileWriter = tempFile.bufferedWriter()
        
        // CSV Header
        fileWriter.write("RecordID,Title,PatientName,TemplateName,LoggedDate,AnswersJson,AttributionsJson\n")
        
        for (e in entries) {
            val safeId = escapeCsv(e.uid)
            val safeTitle = escapeCsv(e.title)
            val safePatient = escapeCsv(e.patientName ?: "Theatre")
            val safeTemplate = escapeCsv(e.templateName ?: "Custom Log")
            val dateStr = dateFormatter.format(Date(e.createdAt))
            val safeAnswers = escapeCsv(e.answersJson)
            val safeAttrs = escapeCsv(e.attributionsJson ?: "")
            
            fileWriter.write("$safeId,$safeTitle,$safePatient,$safeTemplate,$dateStr,$safeAnswers,$safeAttrs\n")
        }
        
        fileWriter.flush()
        fileWriter.close()
        return tempFile
    }

    private fun escapeCsv(text: String): String {
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        return text
    }

    // Generates a lossless JSON backup including schema details, templates and patients
    fun generateJsonBackup(
        context: Context,
        templates: List<FormTemplate>,
        patients: List<Patient>,
        entries: List<FormEntry>
    ): File {
        val backupJson = JSONObject()
        backupJson.put("schemaVersion", 1)
        backupJson.put("exportedAt", System.currentTimeMillis())
        backupJson.put("appName", "LogiMind")

        // Templates
        val templatesArr = org.json.JSONArray()
        for (t in templates) {
            val tObj = JSONObject()
            tObj.put("uid", t.uid)
            tObj.put("name", t.name)
            tObj.put("isActive", t.isActive)
            tObj.put("createdAt", t.createdAt)
            tObj.put("updatedAt", t.updatedAt)
            templatesArr.put(tObj)
        }
        backupJson.put("templates", templatesArr)

        // Patients
        val patientsArr = org.json.JSONArray()
        for (p in patients) {
            val pObj = JSONObject()
            pObj.put("uid", p.uid)
            pObj.put("name", p.name)
            pObj.put("medicalId", p.medicalId)
            pObj.put("phone", p.phone)
            pObj.put("age", p.age)
            pObj.put("insurance", p.insurance)
            pObj.put("notes", p.notes)
            pObj.put("customAnswersJson", p.customAnswersJson)
            pObj.put("createdAt", p.createdAt)
            patientsArr.put(pObj)
        }
        backupJson.put("patients", patientsArr)

        // Logs/Entries
        val logsArr = org.json.JSONArray()
        for (e in entries) {
            val eObj = JSONObject()
            eObj.put("uid", e.uid)
            eObj.put("templateId", e.templateId)
            eObj.put("patientId", e.patientId)
            eObj.put("templateName", e.templateName)
            eObj.put("patientName", e.patientName)
            eObj.put("title", e.title)
            eObj.put("answersJson", e.answersJson)
            eObj.put("attributionsJson", e.attributionsJson)
            eObj.put("createdAt", e.createdAt)
            eObj.put("updatedAt", e.updatedAt)
            logsArr.put(eObj)
        }
        backupJson.put("logs", logsArr)
        backupJson.put("attachments", org.json.JSONArray()) // future expansion

        val tempFile = File(context.cacheDir, "LogiMind_Backup_${fileDateFormatter.format(Date())}.json")
        FileOutputStream(tempFile).use { fos ->
            fos.write(backupJson.toString(2).toByteArray())
        }
        return tempFile
    }

    // Packages both CSV, JSON and PDF compiled versions inside a single standalone ZIP archive
    fun generateZipArchive(
        context: Context,
        templates: List<FormTemplate>,
        patients: List<Patient>,
        entries: List<FormEntry>
    ): File {
        val pdfFile = generatePdf(context, entries)
        val jsonFile = generateJsonBackup(context, templates, patients, entries)
        
        // Construct ZIP
        val zipFile = File(context.cacheDir, "LogiMind_Archive_${fileDateFormatter.format(Date())}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // logs.json
            zos.putNextEntry(ZipEntry("logs.json"))
            zos.write(jsonFile.readBytes())
            zos.closeEntry()

            // logs.pdf
            zos.putNextEntry(ZipEntry("logs.pdf"))
            zos.write(pdfFile.readBytes())
            zos.closeEntry()

            // metadata.json
            zos.putNextEntry(ZipEntry("metadata.json"))
            val meta = JSONObject()
            meta.put("exportedAt", System.currentTimeMillis())
            meta.put("recordCount", entries.size)
            meta.put("provider", "LogiMind EMR System")
            zos.write(meta.toString(2).toByteArray())
            zos.closeEntry()
        }

        return zipFile
    }

    // Shares file using native intent launcher
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "com.example.fileprovider", file)
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LogiMind Clinical Resource Export")
            putExtra(Intent.EXTRA_TEXT, "Forwarding printed medical log entry exports compiled by LogiMind.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "Share Clinical Export via:"))
    }
}
