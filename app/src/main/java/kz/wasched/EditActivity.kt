package kz.wasched

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditActivity : Activity() {
    private companion object {
        const val REQ_CONTACT = 10
    }

    private var m = Msg()
    private val cal: Calendar = Calendar.getInstance()

    private lateinit var etPhone: EditText
    private lateinit var etName: EditText
    private lateinit var etText: EditText
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var spRepeat: Spinner
    private lateinit var rbWab: RadioButton
    private lateinit var rbWa: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        etPhone = findViewById(R.id.etPhone)
        etName = findViewById(R.id.etName)
        etText = findViewById(R.id.etText)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        spRepeat = findViewById(R.id.spRepeat)
        rbWa = findViewById(R.id.rbWa)
        rbWab = findViewById(R.id.rbWab)

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

        etPhone.setText(if (m.phone.isNotEmpty()) "+${m.phone}" else "")
        etName.setText(m.name)
        etText.setText(m.text)
        spRepeat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Msg.REPEAT_NAMES)
        spRepeat.setSelection(m.repeat)
        if (m.business) rbWab.isChecked = true else rbWa.isChecked = true
        updateDateTime()

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
        findViewById<Button>(R.id.btnContact).setOnClickListener {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT,
                )
            } catch (_: Exception) {
                toast("Не удалось открыть контакты")
            }
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
        val phone = Msg.normalizePhone(etPhone.text.toString())
        if (phone.length < 10) {
            etPhone.error = "Введите номер в международном формате, например +7 701 123 45 67"
            etPhone.requestFocus()
            return false
        }
        val text = etText.text.toString()
        if (text.isBlank()) {
            etText.error = "Напишите текст"
            etText.requestFocus()
            return false
        }
        m.phone = phone
        m.name = etName.text.toString().trim()
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    etPhone.setText(c.getString(0) ?: "")
                    if (etName.text.isBlank()) etName.setText(c.getString(1) ?: "")
                }
            }
        } catch (_: Exception) {
            toast("Не удалось прочитать контакт")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
