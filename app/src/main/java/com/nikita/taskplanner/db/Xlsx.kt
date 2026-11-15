package com.nikita.taskplanner.db

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Запись и чтение .xlsx без внешних библиотек:
 * xlsx — это zip-архив с XML внутри. Экспорт пишет inlineStr-ячейки
 * (открывается в Excel и LibreOffice), импорт понимает и inlineStr,
 * и sharedStrings, и даты-числа (serial date).
 */
object Xlsx {

    private val FMT = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))

    // ---------- Экспорт ----------

    fun write(out: OutputStream, tasks: List<Task>) {
        ZipOutputStream(out).use { z ->
            fun entry(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", ROOT_RELS)
            entry("xl/workbook.xml", WORKBOOK)
            entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            entry("xl/worksheets/sheet1.xml", sheetXml(tasks))
        }
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun cell(ref: String, text: String): String =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(text)}</t></is></c>"

    private fun sheetXml(tasks: List<Task>): String {
        val sb = StringBuilder()
        sb.append(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
        )
        val header = listOf("Наименование", "Описание", "Категория", "Срок выполнения", "Приоритет", "Статус")
        sb.append("<row r=\"1\">")
        header.forEachIndexed { i, h -> sb.append(cell(colLetter(i) + "1", h)) }
        sb.append("</row>")
        for ((idx, t) in tasks.withIndex()) {
            val r = idx + 2
            sb.append("<row r=\"$r\">")
            sb.append(cell(colLetter(0) + r, t.title))
            sb.append(cell(colLetter(1) + r, t.description))
            sb.append(cell(colLetter(2) + r, t.category))
            sb.append(cell(colLetter(3) + r, FMT.format(t.dueDate)))
            sb.append(cell(colLetter(4) + r, priorityName(t.priority)))
            sb.append(cell(colLetter(5) + r, if (t.done) "True" else "False"))
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun priorityName(p: Int) = when (p) {
        2 -> "Высокий"; 1 -> "Средний"; else -> "Низкий"
    }

    // ---------- Импорт ----------

    /** Возвращает пары (категории, задачи) или null, если файл не удалось прочитать. */
    fun read(bytes: ByteArray): Pair<List<String>, List<Task>>? {
        var shared: List<String> = emptyList()
        var sheet: ByteArray? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    when (e.name) {
                        "xl/sharedStrings.xml" -> shared = readSharedStrings(z.readBytes())
                        "xl/worksheets/sheet1.xml" -> sheet = z.readBytes()
                    }
                    e = z.nextEntry
                }
            }
            val s = sheet ?: return null
            return parseSheet(s, shared)
        } catch (_: Exception) {
            return null
        }
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), null)
        var buf = StringBuilder()
        var inSi = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") { inSi = true; buf = StringBuilder() }
                XmlPullParser.TEXT -> if (inSi) buf.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "si") { inSi = false; out.add(buf.toString().trim()) }
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String?): Int {
        if (ref.isNullOrBlank()) return -1
        var i = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') i = i * 26 + (ch - 'A' + 1) - 1
            else break
        }
        return i
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): Pair<List<String>, List<Task>>? {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), null)

        val rows = mutableListOf<Map<Int, String>>()
        var cells = mutableMapOf<Int, String>()
        var curCol = -1
        var curType = ""
        var buf = StringBuilder()
        var inCell = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cells = mutableMapOf()
                    "c" -> {
                        inCell = true
                        curCol = colIndex(p.getAttributeValue(null, "r"))
                        curType = p.getAttributeValue(null, "t") ?: ""
                        buf = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> if (inCell) buf.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "c" -> {
                        if (curCol >= 0) cells[curCol] = resolveCell(curType, buf.toString().trim(), shared)
                        inCell = false
                    }
                    "row" -> rows.add(cells.toMap())
                }
            }
            ev = p.next()
        }

        // Первая строка — заголовок; находим колонки по названиям
        var colTitle = 0; var colDesc = 1; var colCat = 2; var colDue = 3; var colPrio = 4; var colDone = 5
        if (rows.isNotEmpty()) {
            val head = rows[0]
            fun find(prefix: String): Int =
                head.entries.firstOrNull { it.value.lowercase(Locale("ru")).startsWith(prefix) }?.key ?: -1
            find("наимен").takeIf { it >= 0 }?.let { colTitle = it }
            find("описан").takeIf { it >= 0 }?.let { colDesc = it }
            find("категор").takeIf { it >= 0 }?.let { colCat = it }
            find("срок").takeIf { it >= 0 }?.let { colDue = it }
            find("приорит").takeIf { it >= 0 }?.let { colPrio = it }
            find("статус").takeIf { it >= 0 }?.let { colDone = it }
        }

        val tasks = mutableListOf<Task>()
        val categories = LinkedHashSet<String>()
        for ((ri, row) in rows.withIndex()) {
            if (ri == 0 && row.values.any { it.lowercase(Locale("ru")).startsWith("наимен") }) continue
            val title = row[colTitle]?.trim().orEmpty()
            if (title.isEmpty()) continue
            val cat = row[colCat]?.trim().orEmpty().ifEmpty { Db.NO_CATEGORY }
            if (cat != Db.NO_CATEGORY) categories.add(cat)
            tasks.add(
                Task(
                    id = 0L,
                    title = title,
                    description = row[colDesc]?.trim().orEmpty(),
                    category = cat,
                    dueDate = parseDate(row[colDue]),
                    priority = parsePriority(row[colPrio]),
                    done = parseDone(row[colDone])
                )
            )
        }
        return Pair(categories.toList(), tasks)
    }

    private fun resolveCell(type: String, raw: String, shared: List<String>): String = when (type) {
        "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        "inlineStr", "str" -> raw
        else -> raw
    }

    private fun parseDate(v: String?): Long {
        val s = v?.trim().orEmpty()
        for (pattern in listOf("dd.MM.yyyy", "dd.MM.yy", "d.M.yyyy", "d.M.yy", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(pattern, Locale("ru"))
                f.isLenient = false
                f.parse(s)?.let { return startOfDay(it.time) }
            } catch (_: Exception) {}
        }
        // serial date из Excel (дни с 1899-12-30)
        val num = s.replace(",", ".").toDoubleOrNull()
        if (num != null && num > 20000 && num < 80000) {
            return startOfDay(((num - 25569.0) * 86400000.0).toLong())
        }
        // не распознали — срок: сегодня + 7 дней
        return startOfDay(System.currentTimeMillis()) + 7L * 86400000L
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().run {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun parsePriority(v: String?): Int {
        val s = v?.lowercase(Locale("ru")).orEmpty()
        return when {
            s.contains("высок") -> 2
            s.contains("средн") -> 1
            else -> 0
        }
    }

    private fun parseDone(v: String?): Boolean {
        val s = v?.lowercase(Locale("ru")).orEmpty()
        return s in setOf("true", "истина", "да", "выполнено", "выполнена", "x", "1")
    }

    // ---------- Константы xlsx ----------

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Задачи\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"
}
