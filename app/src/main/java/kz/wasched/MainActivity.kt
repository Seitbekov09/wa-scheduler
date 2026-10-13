package kz.wasched

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var statusCard: View
    private lateinit var statusText: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnBattery: Button
    private lateinit var btnPin: Button

    private var items: List<Msg> = emptyList()
    private val fmt = SimpleDateFormat("d MMM, EE, HH:mm", Locale("ru"))
    private val fmtShort = SimpleDateFormat("d MMM HH:mm", Locale("ru"))

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_message, parent, false)
            val m = items[position]
            v.findViewById<TextView>(R.id.title).text =
                m.label() + if (m.business) "  · Business" else ""
            v.findViewById<TextView>(R.id.whenText).text =
                if (m.enabled) "⏰ ${fmt.format(Date(m.time))} · ${Msg.REPEAT_NAMES[m.repeat]}"
                else "Выключено · ${Msg.REPEAT_NAMES[m.repeat]}"
            v.findViewById<TextView>(R.id.text).text = m.text
            val st = v.findViewById<TextView>(R.id.status)
            if (m.lastStatus.isNotBlank()) {
                st.visibility = View.VISIBLE
                st.text = "${fmtShort.format(Date(m.lastAt))} — ${m.lastStatus}"
            } else {
                st.visibility = View.GONE
            }
            val sw = v.findViewById<Switch>(R.id.enabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = m.enabled
            sw.setOnCheckedChangeListener { _, on -> toggle(m.id, on) }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        btnAccess = findViewById(R.id.btnAccess)
        btnBattery = findViewById(R.id.btnBattery)
        btnPin = findViewById(R.id.btnPin)
        btnPin.setOnClickListener { pinDialog() }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openEditor(items[pos].id) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { openEditor(0) }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openEditor(id: Int) {
        startActivity(Intent(this, EditActivity::class.java).putExtra("id", id))
    }

    private fun toggle(id: Int, on: Boolean) {
        val m = Store.get(this, id) ?: return
        if (on) {
            val now = System.currentTimeMillis()
            if (m.time <= now) {
                val next = m.nextAfter(now)
                if (next == null) {
                    Toast.makeText(this, "Время уже прошло — откройте сообщение и выберите новое", Toast.LENGTH_LONG).show()
                    refresh()
                    return
                }
                m.time = next
            }
        }
        m.enabled = on
        Store.save(this, m)
        if (on) Scheduler.schedule(this, m) else Scheduler.cancel(this, m.id)
        refresh()
    }

    private fun refresh() {
        items = Store.all(this).sortedWith(compareBy<Msg> { !it.enabled }.thenBy { it.time })
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val a11y = isServiceEnabled()
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        btnAccess.visibility = if (a11y) View.GONE else View.VISIBLE
        btnBattery.visibility = if (battery) View.GONE else View.VISIBLE

        val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val pinOk = !secure || PinStore.has(this)
        btnPin.text = if (PinStore.has(this)) "🔒 PIN экрана: задан (изменить)" else "🔒 Указать PIN экрана"

        if (a11y && battery && pinOk) {
            statusCard.setBackgroundResource(R.drawable.card_ok)
            statusText.text = "✅ Всё готово. Сообщения уйдут автоматически, даже если телефон заблокирован."
        } else {
            statusCard.setBackgroundResource(R.drawable.card_warn)
            val sb = StringBuilder("Осталось настроить:\n")
            if (!a11y) {
                sb.append("• Включите службу «WA Планировщик» в Спец. возможностях — она нажимает «Отправить».\n")
                if (Build.VERSION.SDK_INT >= 33) {
                    sb.append("  Если переключатель серый: Настройки → Приложения → WA Планировщик → ⋮ → «Разрешить ограниченные настройки».\n")
                }
            }
            if (!battery) sb.append("• Разрешите работу в фоне, чтобы система не усыпляла приложение.\n")
            if (!pinOk) sb.append("• На телефоне стоит пароль. Укажите PIN экрана — приложение введёт его само в момент отправки.")
            statusText.text = sb.toString().trimEnd()
        }
    }

    private fun pinDialog() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN из цифр"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("PIN экрана блокировки")
            .setMessage(
                "Приложение введёт этот PIN, чтобы разблокировать телефон в момент отправки, " +
                    "а после отправки снова заблокирует экран.\n\n" +
                    "Работает только с цифровым PIN (не с графическим ключом и не с паролем из букв). " +
                    "PIN хранится только на этом телефоне в зашифрованном виде.",
            )
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val pin = et.text.toString().trim()
                if (pin.length < 4 || !pin.all { it.isDigit() }) {
                    Toast.makeText(this, "PIN должен состоять минимум из 4 цифр", Toast.LENGTH_LONG).show()
                } else {
                    PinStore.save(this, pin)
                    Toast.makeText(this, "PIN сохранён", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton("Отмена", null)
        if (PinStore.has(this)) {
            b.setNeutralButton("Удалить") { _, _ ->
                PinStore.clear(this)
                refresh()
            }
        }
        b.show()
    }

    private fun isServiceEnabled(): Boolean {
        if (WaService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }
}
