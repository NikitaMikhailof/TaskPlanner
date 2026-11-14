package com.nikita.taskplanner.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Task(
    val id: Long,
    val title: String,
    val description: String,
    val category: String,
    val dueDate: Long,          // epoch millis, начало дня срока
    val priority: Int,          // 0 = Низкий, 1 = Средний, 2 = Высокий
    val done: Boolean
)

object Db {
    const val TASKS = "tasks"
    const val CATEGORIES = "categories"
    const val NO_CATEGORY = "Без категории"
}

class DbHelper(context: Context) :
    SQLiteOpenHelper(context, "taskplanner.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL,
                dueDate INTEGER NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                done INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE TABLE categories (name TEXT PRIMARY KEY, sort INTEGER NOT NULL DEFAULT 0)")

        // Стартовые данные из "Плановые задачи.xlsx"
        val cats = listOf(
            "Дача", "Квартира (старая)", "Квартира (новая)",
            "Автомобиль", "Здоровье", "Семья", "Образование"
        )
        cats.forEachIndexed { i, c ->
            db.insert(Db.CATEGORIES, null,
                ContentValues().apply { put("name", c); put("sort", i) })
        }

        fun ts(y: Int, m: Int, d: Int): Long =
            java.util.Calendar.getInstance().run {
                clear(); set(y, m - 1, d); timeInMillis
            }

        val P = mapOf("Низкий" to 0, "Средний" to 1, "Высокий" to 2)
        val seed = listOf(
            listOf("Датчик кислорода", "Заменить датчик кислорода после катализатора", "Автомобиль", ts(2026, 12, 1), "Средний"),
            listOf("Покдкрылок передний", "Заменить передний левый подкрылок", "Автомобиль", ts(2026, 12, 10), "Низкий"),
            listOf("Обогрев стекла", "Припаять обогрев заднего стекла", "Автомобиль", ts(2026, 12, 15), "Низкий"),
            listOf("Подкрылок задний", "Установить задние подкрылки на автомобиль", "Автомобиль", ts(2026, 12, 15), "Низкий"),
            listOf("Видеорегистратор", "Купить видеорегистратор и установить", "Автомобиль", ts(2026, 12, 20), "Низкий"),
            listOf("Лампа заднего хода", "Свозить к автоэлектрику сделать лампу заднего хода", "Автомобиль", ts(2026, 12, 20), "Низкий"),
            listOf("Ошибка АБС", "Свозить к автоэлектрику решить вопрос с ошибкой", "Автомобиль", ts(2026, 12, 20), "Средний"),
            listOf("Подтекает крыша", "Свозить к мастеру устранить подтек крышы через лобовое с водительской стороны", "Автомобиль", ts(2027, 1, 15), "Средний"),
            listOf("Автозапуск", "Установить автозапуск", "Автомобиль", ts(2026, 12, 15), "Средний"),
            listOf("План-проект новой квартиры", "Составить подробный план-проект новой квартиры в remplaner", "Квартира (новая)", ts(2026, 12, 1), "Низкий"),
            listOf("Уборка мусора новая квартира", "Вынести стекла, коробки и прочий мусор", "Квартира (новая)", ts(2026, 12, 5), "Низкий"),
            listOf("Договор водоканал", "Заключить договор с водоканалом понедельник-среда", "Квартира (новая)", ts(2026, 12, 10), "Высокий"),
            listOf("Посчитать и заказать вагонку", "Посчитать смету по вагонке в предбанике. Заказать в ЭКО ДОМ у семейного магнита", "Дача", ts(2026, 12, 20), "Низкий"),
            listOf("Обрамить банную дверь", "Купить штапки 20мм обработать маслом обрамить входную банную дверь", "Дача", ts(2026, 12, 25), "Низкий")
        )
        for (t in seed) {
            db.insert(Db.TASKS, null, ContentValues().apply {
                put("title", t[0] as String); put("description", t[1] as String)
                put("category", t[2] as String)
                put("dueDate", t[3] as Long); put("priority", P[t[4] as String] ?: 0)
                put("done", 0)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) { /* v1 */ }

    // ---- Задачи ----

    fun tasks(categoryFilter: String?): List<Task> {
        val where = if (categoryFilter == null) null else "category = ?"
        val args = categoryFilter?.let { arrayOf(it) }
        val out = mutableListOf<Task>()
        readableDatabase.query(Db.TASKS, null, where, args, null, null,
            "done ASC, dueDate ASC, priority DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Task(
                        id = c.getLong(0),
                        title = c.getString(1),
                        description = c.getString(2),
                        category = c.getString(3),
                        dueDate = c.getLong(4),
                        priority = c.getInt(5),
                        done = c.getInt(6) != 0
                    )
                )
            }
        }
        return out
    }

    fun categories(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.query(Db.CATEGORIES, null, null, null, null, null, "sort, name").use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun categoryNamesInUse(): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT category FROM tasks", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun saveTask(id: Long?, t: Task): Long {
        val cv = ContentValues().apply {
            put("title", t.title); put("description", t.description)
            put("category", t.category); put("dueDate", t.dueDate)
            put("priority", t.priority); put("done", if (t.done) 1 else 0)
        }
        return if (id == null) writableDatabase.insert(Db.TASKS, null, cv)
        else writableDatabase.update(Db.TASKS, cv, "id = ?", arrayOf(id.toString())).toLong()
    }

    fun deleteTask(id: Long) {
        writableDatabase.delete(Db.TASKS, "id = ?", arrayOf(id.toString()))
    }

    fun setDone(id: Long, done: Boolean) {
        writableDatabase.execSQL("UPDATE tasks SET done = ? WHERE id = ?",
            arrayOf(if (done) 1 else 0, id))
    }

    // ---- Категории ----

    fun addCategory(name: String) {
        val max = readableDatabase.rawQuery("SELECT COALESCE(MAX(sort), -1) FROM categories", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        writableDatabase.insert(Db.CATEGORIES, null, ContentValues().apply {
            put("name", name); put("sort", max + 1)
        })
    }

    fun deleteCategory(name: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE tasks SET category = ? WHERE category = ?",
                arrayOf(Db.NO_CATEGORY, name))
            writableDatabase.delete(Db.CATEGORIES, "name = ?", arrayOf(name))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun categoryExists(name: String): Boolean =
        readableDatabase.query(Db.CATEGORIES, null, "name = ?", arrayOf(name), null, null, null)
            .use { it.count > 0 }
}
