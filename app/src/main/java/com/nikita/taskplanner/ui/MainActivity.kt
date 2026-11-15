package com.nikita.taskplanner.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nikita.taskplanner.R
import com.nikita.taskplanner.db.Db
import com.nikita.taskplanner.db.DbHelper
import com.nikita.taskplanner.db.Task
import com.nikita.taskplanner.db.Xlsx
import com.nikita.taskplanner.work.ReminderWorker.Companion.daysLeft
import com.nikita.taskplanner.work.ReminderWorker.Companion.startOfDay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: TaskAdapter
    private var filterCategory: String? = null // null = все категории
    private var periodIndex = 0                // 0 весь срок, 1 неделя, 2 две недели, 3 месяц, 4 больше месяца

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))

    private val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri: Uri? ->
        if (uri != null) exportTo(uri)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = DbHelper(this)
        filterCategory = savedInstanceState?.getString("filter")
        periodIndex = savedInstanceState?.getInt("period", 0) ?: 0
        adapter = TaskAdapter(
            onEdit = { showTaskDialog(it) },
            onToggle = { t, done ->
                db.setDone(t.id, done)
                reload()
            }
        )

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab)
            .setOnClickListener { showTaskDialog(null) }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).apply {
            setTitle(R.string.toolbar_title)
            setTitleTextColor(ContextCompat.getColor(context, R.color.toolbar_green))
            inflateMenu(R.menu.menu_main)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_categories -> { showCategoriesDialog(); true }
                    R.id.menu_theme -> { toggleTheme(); true }
                    R.id.menu_export -> { exportLauncher.launch("Плановые задачи.xlsx"); true }
                    R.id.menu_import -> { importLauncher.launch(arrayOf("*/*")); true }
                    else -> false
                }
            }
        }

        setupFilterSpinner()
        setupPeriodSpinner()
        reload()
        requestNotificationPermission()
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val dark = prefs.getBoolean("dark", true)
        prefs.edit().putBoolean("dark", !dark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        // setDefaultNightMode сам пересоздаёт активити
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filter", filterCategory)
        outState.putInt("period", periodIndex)
    }

    private fun reload() {
        var list = db.tasks(filterCategory)
        val today = startOfDay(System.currentTimeMillis())
        list = when (periodIndex) {
            1 -> list.filter { it.dueDate <= today + TimeUnit.DAYS.toMillis(7) }
            2 -> list.filter { it.dueDate <= today + TimeUnit.DAYS.toMillis(14) }
            3 -> list.filter { it.dueDate <= today + TimeUnit.DAYS.toMillis(30) }
            4 -> list.filter { it.dueDate > today + TimeUnit.DAYS.toMillis(30) }
            else -> list
        }
        adapter.submit(list)
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val a = ArrayAdapter(this, R.layout.spinner_item, items)
        a.setDropDownViewResource(R.layout.spinner_dropdown_item)
        return a
    }

    private fun setupPeriodSpinner() {
        val spinner = findViewById<Spinner>(R.id.period)
        val items = listOf(
            getString(R.string.period_all),
            getString(R.string.period_week),
            getString(R.string.period_2week),
            getString(R.string.period_month),
            getString(R.string.period_more)
        )
        spinner.adapter = spinnerAdapter(items)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                periodIndex = pos
                reload()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    // ---------- Экспорт / импорт .xlsx ----------

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                Xlsx.write(out, db.tasks(null))
            }
            Toast.makeText(this, R.string.export_ok, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_err, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFrom(uri: Uri) {
        val parsed = try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            bytes?.let { Xlsx.read(it) }
        } catch (_: Exception) {
            null
        }
        if (parsed == null) {
            Toast.makeText(this, R.string.import_err, Toast.LENGTH_SHORT).show()
            return
        }
        val (categories, tasks) = parsed
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.import_confirm))
            .setPositiveButton(R.string.save) { _, _ ->
                db.replaceAll(tasks, categories)
                filterCategory = null
                periodIndex = 0
                setupFilterSpinner()
                setupPeriodSpinner()
                reload()
                Toast.makeText(this, getString(R.string.import_ok, tasks.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupFilterSpinner() {
        val spinner = findViewById<Spinner>(R.id.filter)
        val cats = db.categories().toMutableList()
        if (db.categoryNamesInUse().contains(Db.NO_CATEGORY) && !cats.contains(Db.NO_CATEGORY)) {
            cats.add(Db.NO_CATEGORY)
        }
        val items = mutableListOf("Все категории") + cats
        spinner.adapter = spinnerAdapter(items)
        spinner.setSelection(items.indexOf(filterCategory).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                filterCategory = if (pos == 0) null else items[pos]
                reload()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    // ---------- Диалог задачи (создание / редактирование) ----------

    private fun showTaskDialog(task: Task?) {
        val view = layoutInflater.inflate(R.layout.dialog_task, null)
        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etDesc = view.findViewById<EditText>(R.id.etDesc)
        val spCategory = view.findViewById<Spinner>(R.id.spCategory)
        val etDate = view.findViewById<EditText>(R.id.etDate)
        val spPriority = view.findViewById<Spinner>(R.id.spPriority)
        val cbDone = view.findViewById<CheckBox>(R.id.cbDone)

        val newCategoryLabel = getString(R.string.new_category_item)
        var categoryItems: List<String> = emptyList()
        var currentCategory = task?.category ?: (filterCategory ?: db.categories().firstOrNull() ?: Db.NO_CATEGORY)
        var suppressCatListener = false

        fun rebuildCategorySpinner() {
            categoryItems = db.categories() + Db.NO_CATEGORY + newCategoryLabel
            spCategory.adapter = spinnerAdapter(categoryItems)
            suppressCatListener = true
            spCategory.setSelection(categoryItems.indexOf(currentCategory).coerceAtLeast(0))
            suppressCatListener = false
        }

        spCategory.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressCatListener || pos < 0 || pos >= categoryItems.size) return
                val sel = categoryItems[pos]
                if (sel == newCategoryLabel) {
                    askNewCategoryName { name ->
                        if (!db.categoryExists(name)) db.addCategory(name)
                        currentCategory = name
                        rebuildCategorySpinner()
                    }
                } else {
                    currentCategory = sel
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        rebuildCategorySpinner()

        etTitle.setText(task?.title ?: "")
        etDesc.setText(task?.description ?: "")
        etDate.setText(dateFmt.format(task?.dueDate ?: Calendar.getInstance().timeInMillis))
        spPriority.setSelection(task?.priority ?: 1)
        cbDone.isChecked = task?.done ?: false

        etDate.setOnClickListener {
            val cal = Calendar.getInstance()
            try { dateFmt.parse(etDate.text.toString())?.let { cal.time = it } } catch (_: Exception) {}
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
                etDate.setText(dateFmt.format(cal.timeInMillis))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        etDate.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) etDate.callOnClick()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (task == null) R.string.new_task else R.string.edit_task)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            if (task != null) {
                val del = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                del.setText(R.string.delete)
                del.visibility = View.VISIBLE
                del.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_task_confirm, task.title))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            db.deleteTask(task.id)
                            dialog.dismiss()
                            setupFilterSpinner()
                            reload()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    etTitle.error = getString(R.string.err_title)
                    return@setOnClickListener
                }
                val due = try {
                    startOfDay(dateFmt.parse(etDate.text.toString())!!.time)
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.err_date, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val category = currentCategory
                db.saveTask(task?.id, Task(
                    id = task?.id ?: 0L,
                    title = title,
                    description = etDesc.text.toString().trim(),
                    category = category,
                    dueDate = due,
                    priority = spPriority.selectedItemPosition,
                    done = cbDone.isChecked
                ))
                dialog.dismiss()
                setupFilterSpinner()
                reload()
            }
        }
        dialog.show()
    }

    private fun askNewCategoryName(onOk: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.category_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_category)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.err_category, Toast.LENGTH_SHORT).show()
                } else onOk(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Диалог управления категориями ----------

    private fun showCategoriesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_categories, null)
        val list = view.findViewById<LinearLayout>(R.id.catList)
        val input = view.findViewById<EditText>(R.id.etNewCategory)

        fun render() {
            list.removeAllViews()
            for (cat in db.categories()) {
                val row = layoutInflater.inflate(R.layout.item_category, list, false)
                row.findViewById<TextView>(R.id.catName).text = cat
                row.findViewById<ImageButton>(R.id.btnDel).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_category_confirm, cat))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            db.deleteCategory(cat)
                            setupFilterSpinner()
                            reload()
                            render()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                list.addView(row)
            }
        }
        render()

        view.findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> Toast.makeText(this, R.string.err_category, Toast.LENGTH_SHORT).show()
                db.categoryExists(name) -> Toast.makeText(this, R.string.err_category_exists, Toast.LENGTH_SHORT).show()
                else -> {
                    db.addCategory(name)
                    input.setText("")
                    setupFilterSpinner()
                    reload()
                    render()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.categories)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

// ---------- Список задач ----------

class TaskAdapter(
    val onEdit: (Task) -> Unit,
    val onToggle: (Task, Boolean) -> Unit
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    private val items = mutableListOf<Task>()
    private val dayFmt = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))

    fun submit(list: List<Task>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tTitle)
        val desc: TextView = v.findViewById(R.id.tDesc)
        val meta: TextView = v.findViewById(R.id.tMeta)
        val cb: CheckBox = v.findViewById(R.id.cbDone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = items[pos]
        val ctx = h.itemView.context
        h.title.text = t.title
        h.title.paint.isStrikeThruText = t.done
        h.title.alpha = if (t.done) 0.5f else 1f
        h.desc.text = t.description
        h.desc.visibility = if (t.description.isEmpty()) View.GONE else View.VISIBLE

        val today = startOfDay(System.currentTimeMillis())
        val days = daysLeft(today, t.dueDate)
        val whenText = when {
            t.done -> dayFmt.format(t.dueDate)
            days < 0 -> "${dayFmt.format(t.dueDate)} · просрочено на ${-days} ${pluralDays(-days)}"
            days == 0L -> "${dayFmt.format(t.dueDate)} · срок сегодня"
            else -> "${dayFmt.format(t.dueDate)} · ${days} ${pluralDays(days)} до срока"
        }
        h.meta.text = "${t.category} · ${priorityName(t.priority)} · $whenText"
        h.meta.setTextColor(
            ContextCompat.getColor(ctx, when {
                t.done -> R.color.text_muted
                days < 0 || days <= 7 -> R.color.danger
                days <= 14 -> R.color.warn
                else -> R.color.text_muted
            })
        )

        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = t.done
        h.cb.setOnCheckedChangeListener { _, checked -> onToggle(t, checked) }
        h.itemView.setOnClickListener { onEdit(t) }
    }

    private fun priorityName(p: Int) = when (p) {
        2 -> "Высокий"; 1 -> "Средний"; else -> "Низкий"
    }

    private fun pluralDays(n: Long): String = when {
        n % 10 == 1L && n % 100 != 11L -> "день"
        n % 10 in 2L..4L && (n % 100 < 12L || n % 100 > 14L) -> "дня"
        else -> "дней"
    }
}
