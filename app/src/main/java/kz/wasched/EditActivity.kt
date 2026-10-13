package kz.wasched

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditActivity : Activity() {
    private companion object {
        const val MAX_CHATS = 5 // столько чатов WhatsApp разрешает выбрать за одну отправку
        const val STATE_CHATS = "chats"
        const val STATE_TIME = "time"
    }

    private var m = Msg()
    private val cal: Calendar = Calendar.getInstance()
    private val chats = ArrayList<String>()

    private lateinit var etPhone: EditText
    private lateinit var etText: EditText
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var spRepeat: Spinner
    private lateinit var rbWab: RadioButton
    private lateinit var rbWa: RadioButton
    private lateinit var chipsBox: LinearLayout
    private lateinit var tvNoChats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        etPhone = findViewById(R.id.etPhone)
        etText = findViewById(R.id.etText)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        spRepeat = findViewById(R.id.spRepeat)
        rbWa = findViewById(R.id.rbWa)
        rbWab = findViewById(R.id.rbWab)
        chipsBox = findViewById(R.id.chipsBox)
        tvNoChats = findViewById(R.id.tvNoChats)

        val id = intent.getIntExtra("id", 0)
        if (id != 0) Store.get(this, id)?.let { m = it.copy() }
        title = if (m.id == 0) "Новое сообщение" else "Изменить сообщение"

        if (m.time == 0L) {
            cal.add(Calendar.MINUTE, 5)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        } else {
            cal.timeInMillis = m.time
        }
        chats.addAll(m.chats)
        savedInstanceState?.let { st ->
            st.getStringArrayList(STATE_CHATS)?.let { chats.clear(); chats.addAll(it) }
            cal.timeInMillis = st.getLong(STATE_TIME, cal.timeInMillis)
        }

        etPhone.setText(if (m.phone.isNotEmpty()) "+${m.phone}" else "")
        etText.setText(m.text)
        spRepeat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Msg.REPEAT_NAMES)
        spRepeat.setSelection(m.repeat)
        if (m.business) rbWab.isChecked = true else rbWa.isChecked = true
        updateDateTime()
        renderChats()

        findViewById<Button>(R.id.btnPickWa).setOnClickListener { pickInWhatsApp() }

        btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, mo, d ->
                cal.set(y, mo, d)
                updateDateTime()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        }
        btnTime.setOnClickListener {
            TimePickerDialog(this, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh)
                cal.set(Calendar.MINUTE, mm)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                updateDateTime()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (collect(requireFuture = true)) {
                m.enabled = true
                Store.save(this, m)
                Scheduler.schedule(this, m)
                toast("Запланировано")
                finish()
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (collect(requireFuture = false)) {
                m.enabled = m.time > System.currentTimeMillis()
                Store.save(this, m)
                Scheduler.schedule(this, m)
                if (WaService.instance == null) {
                    toast("Сначала включите службу в Спец. возможностях")
                    return@setOnClickListener
                }
                toast("Открываю WhatsApp…")
                Sender.enqueue(this, m.id)
                finish()
            }
        }
        val btnDelete = findViewById<Button>(R.id.btnDelete)
        btnDelete.visibility = if (m.id == 0) View.GONE else View.VISIBLE
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Удалить это сообщение?")
                .setPositiveButton("Удалить") { _, _ ->
                    Scheduler.cancel(this, m.id)
                    Store.delete(this, m.id)
                    finish()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_CHATS, chats)
        outState.putLong(STATE_TIME, cal.timeInMillis)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        takeCaptured()
    }

    override fun onResume() {
        super.onResume()
        takeCaptured()
        WaService.cancelCapture()
    }

    private fun takeCaptured() {
        val name = WaService.takeCaptured() ?: return
        if (name !in chats && chats.size < MAX_CHATS) chats.add(name)
        renderChats()
    }

    /** Открывает список чатов WhatsApp; служба запомнит, на какой чат вы нажмёте. */
    private fun pickInWhatsApp() {
        if (WaService.instance == null) {
            toast("Сначала включите службу «WA Планировщик» в Спец. возможностях")
            return
        }
        if (chats.size >= MAX_CHATS) {
            toast("Можно выбрать не больше $MAX_CHATS чатов")
            return
        }
        val pkg = if (rbWab.isChecked) "com.whatsapp.w4b" else "com.whatsapp"
        WaService.startCapture()
        try {
            startActivity(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Выбор чата для WA Планировщика")
                    .setPackage(pkg),
            )
            toast("Нажмите на нужный чат или группу")
        } catch (_: ActivityNotFoundException) {
            WaService.cancelCapture()
            toast(if (rbWab.isChecked) "WhatsApp Business не установлен" else "WhatsApp не установлен")
        }
    }

    private fun renderChats() {
        chipsBox.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (name in chats) {
            val chip = TextView(this).apply {
                text = "👤  $name      ✕"
                textSize = 16f
                setTextColor(getColor(R.color.text_main))
                setBackgroundResource(R.drawable.chip)
                setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    chats.remove(name)
                    renderChats()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad / 2 }
            chipsBox.addView(chip, lp)
        }
        tvNoChats.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateDateTime() {
        btnDate.text = SimpleDateFormat("EE, d MMMM yyyy", Locale("ru")).format(cal.time)
        btnTime.text = SimpleDateFormat("HH:mm", Locale("ru")).format(cal.time)
    }

    /** Переносит поля формы в [m]. */
    private fun collect(requireFuture: Boolean): Boolean {
        val rawPhone = etPhone.text.toString()
        val phone = Msg.normalizePhone(rawPhone)
        if (chats.isEmpty()) {
            if (phone.length < 10) {
                etPhone.error = if (rawPhone.isBlank()) "Выберите чат в WhatsApp или введите номер"
                else "Введите номер в международном формате, например +7 701 123 45 67"
                etPhone.requestFocus()
                return false
            }
        }
        val text = etText.text.toString()
        if (text.isBlank()) {
            etText.error = "Напишите текст"
            etText.requestFocus()
            return false
        }
        m.chats = ArrayList(chats)
        m.phone = if (chats.isEmpty()) phone else ""
        m.name = ""
        m.text = text.trim()
        m.repeat = spRepeat.selectedItemPosition
        m.business = rbWab.isChecked
        m.time = cal.timeInMillis

        val now = System.currentTimeMillis()
        if (m.time <= now) {
            val next = m.nextAfter(now)
            if (next != null) {
                m.time = next
            } else if (requireFuture) {
                toast("Выберите время в будущем")
                return false
            }
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
