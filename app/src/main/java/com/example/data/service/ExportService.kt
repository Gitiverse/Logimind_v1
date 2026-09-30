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
import com.example.data.model.EntryAnswer
import com.example.data.model.FormEntry
import com.example.data.model.FormField
import com.example.data.model.FormFieldOption
import com.example.data.model.FormSection
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

    // SimpleDateFormat is not thread-safe; exports run on background threads, so create per use.
    private val dateFormatter get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fileDateFormatter get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    // Exports contain patient data; keep only the newest file of each kind in the cache.
    private fun newCacheFile(context: Context, prefix: String, extension: String): File {
        context.cacheDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        return File(context.cacheDir, "${prefix}${fileDateFormatter.format(Date())}.$extension")
    }

    private fun labelFor(key: String, fieldLabelMap: Map<String, String>): String {
        fieldLabelMap[key]?.let { return it }
        if (uuidPattern.matches(key)) return "Deleted question"
        return key.split("_").joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

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
        fieldLabelMap: Map<String, String> = emptyMap(),
        fieldOrder: Map<String, Int> = emptyMap()
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
                    // Template order (section, then question); unknown keys go last.
                    val keys = answers.keys().asSequence()
                        .sortedBy { fieldOrder[it] ?: Int.MAX_VALUE }
                        .iterator()
                    
                    canvas.drawText("Clinical Question & Response Checklist:", margin + 10f, yPos, boldBodyPaint)
                    yPos += 15f
                    
                    if (!keys.hasNext()) {
                        canvas.drawText("No questions answered.", margin + 20f, yPos, subtitlePaint)
                        yPos += 15f
                    }

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val answerValue = answers.optString(key)
                        val formattedLabel = labelFor(key, fieldLabelMap)
                        
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
                                
                                val parts = mutableListOf<String>()
                                if (providerName.isNotBlank() && providerName != "Unknown") parts += "Performed by: $providerName${if (providerType.isNotBlank()) " ($providerType)" else ""}"
                                if (supervisor.isNotBlank()) parts += "Supervisor/witness: $supervisor${if (verified) " (verified)" else ""}"
                                val attrText = "  └ " + parts.joinToString(" | ").ifBlank { "No supervisor recorded" }
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

        val tempFile = newCacheFile(context, "LogiMind_Report_", "pdf")
        val outputStream = FileOutputStream(tempFile)
        pdfDocument.writeTo(outputStream)
        outputStream.flush()
        outputStream.close()
        pdfDocument.close()
        
        return tempFile
    }

    // One row per entry and one column per question (labelled, in template order). The raw
    // JSON columns stay at the end so the CSV still holds everything.
    fun generateCsv(
        context: Context,
        entries: List<FormEntry>,
        fieldLabelMap: Map<String, String> = emptyMap(),
        fieldOrder: Map<String, Int> = emptyMap()
    ): File {
        val tempFile = newCacheFile(context, "LogiMind_Export_", "csv")
        val parsed = entries.map { e ->
            e to try {
                val obj = JSONObject(e.answersJson)
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } catch (ex: Exception) {
                emptyMap()
            }
        }
        val questionIds = parsed.flatMap { it.second.keys }.distinct().sortedBy { fieldOrder[it] ?: Int.MAX_VALUE }
        val formatter = dateFormatter

        tempFile.bufferedWriter().use { w ->
            val header = listOf("RecordID", "Title", "PatientName", "TemplateName", "LoggedDate") +
                questionIds.map { labelFor(it, fieldLabelMap) } + listOf("AnswersJson", "AttributionsJson")
            w.write(header.joinToString(",") { escapeCsv(it) })
            w.write("\n")
            for ((e, answers) in parsed) {
                val row = listOf(
                    e.uid,
                    e.title,
                    e.patientName ?: "Theatre",
                    e.templateName ?: "Custom Log",
                    formatter.format(Date(e.createdAt))
                ) + questionIds.map { id -> answers[id]?.let { formatDisplayAnswer(it) } ?: "" } +
                    listOf(e.answersJson, e.attributionsJson ?: "")
                w.write(row.joinToString(",") { escapeCsv(it) })
                w.write("\n")
            }
        }
        return tempFile
    }

    data class ProcedureCaseRow(
        val procedure: String,
        val date: Long,
        val patientName: String,
        val templateName: String,
        val performedByMe: Boolean
    )

    // Procedure logbook report: totals per procedure (by me / assisted), totals per month, case list.
    fun generateProcedureReport(context: Context, cases: List<ProcedureCaseRow>, title: String): File {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = 60f

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val headPaint = Paint().apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val bodyPaint = Paint().apply { color = Color.BLACK; textSize = 10f }
        val mutedPaint = Paint().apply { color = Color.DKGRAY; textSize = 9f }
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }

        fun newPageIfNeeded(space: Float) {
            if (y + space <= pageHeight - margin) return
            pdf.finishPage(page)
            pageNum++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
            y = margin + 20f
        }
        fun row(cols: List<String>, xs: List<Float>, paint: Paint) {
            newPageIfNeeded(16f)
            cols.forEachIndexed { i, text ->
                val maxWidth = (if (i + 1 < xs.size) xs[i + 1] - xs[i] else pageWidth - margin - xs[i]) - 6f
                var t = text
                while (t.isNotEmpty() && paint.measureText(t) > maxWidth) t = t.dropLast(1)
                canvas.drawText(if (t.length < text.length) t.dropLast(1) + "…" else t, xs[i], y, paint)
            }
            y += 15f
        }

        canvas.drawText(title, margin, y, titlePaint)
        y += 20f
        canvas.drawText("Generated ${dateFormatter.format(Date())} • ${cases.size} procedure(s) • ${cases.count { it.performedByMe }} performed by me", margin, y, mutedPaint)
        y += 24f

        val cols = listOf(margin, 330f, 410f, 490f)
        canvas.drawText("Totals by procedure", margin, y, headPaint); y += 16f
        row(listOf("Procedure", "By me", "Assisted", "Total"), cols, headPaint)
        canvas.drawLine(margin, y - 11f, pageWidth - margin, y - 11f, linePaint)
        cases.groupBy { it.procedure }.toList().sortedByDescending { it.second.size }.forEach { (name, list) ->
            row(listOf(name, "${list.count { it.performedByMe }}", "${list.count { !it.performedByMe }}", "${list.size}"), cols, bodyPaint)
        }

        y += 14f
        newPageIfNeeded(40f)
        canvas.drawText("Totals by month", margin, y, headPaint); y += 16f
        row(listOf("Month", "By me", "Assisted", "Total"), cols, headPaint)
        canvas.drawLine(margin, y - 11f, pageWidth - margin, y - 11f, linePaint)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.US)
        val monthLabel = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        cases.groupBy { monthKey.format(Date(it.date)) }.toSortedMap(compareByDescending { it }).forEach { (_, list) ->
            row(listOf(monthLabel.format(Date(list.first().date)), "${list.count { it.performedByMe }}", "${list.count { !it.performedByMe }}", "${list.size}"), cols, bodyPaint)
        }

        y += 14f
        newPageIfNeeded(40f)
        canvas.drawText("Case list", margin, y, headPaint); y += 16f
        val caseCols = listOf(margin, 130f, 300f, 440f)
        row(listOf("Date", "Procedure", "Patient", "Role"), caseCols, headPaint)
        canvas.drawLine(margin, y - 11f, pageWidth - margin, y - 11f, linePaint)
        cases.sortedByDescending { it.date }.forEach { c ->
            row(listOf(dateFormatter.format(Date(c.date)), c.procedure, c.patientName, if (c.performedByMe) "Performed" else "Assisted"), caseCols, bodyPaint)
        }

        pdf.finishPage(page)
        val file = newCacheFile(context, "LogiMind_Procedures_", "pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun escapeCsv(raw: String): String {
        // Leading =, +, -, @ would run as a formula when the CSV is opened in a spreadsheet.
        val text = if (raw.isNotEmpty() && raw[0] in "=+-@") "'$raw" else raw
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        return text
    }

    // Generates a complete JSON backup. schemaVersion 2 = v1 (templates, patients, logs) plus
    // sections, fields, options and answers; v1 readers ignore the extra arrays.
    fun generateJsonBackup(
        context: Context,
        templates: List<FormTemplate>,
        patients: List<Patient>,
        entries: List<FormEntry>,
        sections: List<FormSection> = emptyList(),
        fields: List<FormField> = emptyList(),
        options: List<FormFieldOption> = emptyList(),
        answers: List<EntryAnswer> = emptyList()
    ): File {
        val backupJson = JSONObject()
        backupJson.put("schemaVersion", 2)
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

        backupJson.put("sections", org.json.JSONArray().apply {
            for (sec in sections) put(JSONObject().apply {
                put("uid", sec.uid)
                put("templateId", sec.templateId)
                put("title", sec.title)
                put("sortOrder", sec.sortOrder)
                put("isExpandable", sec.isExpandable)
            })
        })
        backupJson.put("fields", org.json.JSONArray().apply {
            for (f in fields) put(JSONObject().apply {
                put("uid", f.uid)
                put("sectionId", f.sectionId)
                put("type", f.type)
                put("label", f.label)
                put("hint", f.hint)
                put("isRequired", f.isRequired)
                put("sortOrder", f.sortOrder)
                put("conditionalLogicJson", f.conditionalLogicJson)
                put("defaultValue", f.defaultValue)
                put("isProcedure", f.isProcedure)
                put("askIfPerformedByMe", f.askIfPerformedByMe)
                put("startMinimized", f.startMinimized)
            })
        })
        backupJson.put("options", org.json.JSONArray().apply {
            for (o in options) put(JSONObject().apply {
                put("uid", o.uid)
                put("fieldId", o.fieldId)
                put("label", o.label)
                put("sortOrder", o.sortOrder)
                put("isAdvanced", o.isAdvanced)
            })
        })
        backupJson.put("answers", org.json.JSONArray().apply {
            for (a in answers) put(JSONObject().apply {
                put("uid", a.uid)
                put("entryId", a.entryId)
                put("fieldId", a.fieldId)
                put("value", a.value)
                put("providerType", a.providerType)
                put("providerName", a.providerName)
                put("providerId", a.providerId)
                put("supervisorWitness", a.supervisorWitness)
                put("isWitnessVerified", a.isWitnessVerified)
                put("performedByMe", a.performedByMe ?: JSONObject.NULL)
            })
        })
        backupJson.put("attachments", org.json.JSONArray()) // future expansion

        val tempFile = newCacheFile(context, "LogiMind_Backup_", "json")
        FileOutputStream(tempFile).use { fos ->
            fos.write(backupJson.toString(2).toByteArray())
        }
        return tempFile
    }

    // Packages both CSV, JSON and PDF compiled versions inside a single standalone ZIP archive
    fun generateZipArchive(
        context: Context,
        jsonBackupFile: File,
        entries: List<FormEntry>,
        fieldLabelMap: Map<String, String>,
        fieldOrder: Map<String, Int> = emptyMap()
    ): File {
        val pdfFile = generatePdf(context, entries, fieldLabelMap, fieldOrder)
        val jsonFile = jsonBackupFile

        // Construct ZIP
        val zipFile = newCacheFile(context, "LogiMind_Archive_", "zip")
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
