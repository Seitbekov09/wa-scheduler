package kz.wasched

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Одно запланированное сообщение. */
data class Msg(
    var id: Int = 0,
    var phone: String = "",       // только цифры, в международном формате: 77011234567
    var name: String = "",
    var text: String = "",
    var time: Long = 0L,          // ближайшее время отправки (мс)
    var repeat: Int = ONCE,
    var business: Boolean = false,
    var enabled: Boolean = true,
    var lastStatus: String = "",
    var lastAt: Long = 0L,
    var chats: List<String> = emptyList(), // названия чатов/групп WhatsApp, выбранные в самом WhatsApp
) {
    fun label(): String = when {
        chats.isNotEmpty() -> chats.joinToString(", ")
        name.isNotBlank() -> name
        else -> "+$phone"
    }

    /** Следующее время отправки строго позже [after], или null для разового сообщения. */
    fun nextAfter(after: Long): Long? {
        if (repeat == ONCE) return null
        val c = Calendar.getInstance().apply { timeInMillis = this@Msg.time }
        var guard = 0
        while (c.timeInMillis <= after || (repeat == WEEKDAYS && isWeekend(c))) {
            when (repeat) {
                WEEKLY -> c.add(Calendar.WEEK_OF_YEAR, 1)
                else -> c.add(Calendar.DAY_OF_MONTH, 1)
            }
            if (++guard > 10_000) return null
        }
        return c.timeInMillis
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("phone", phone).put("name", name).put("text", text)
        .put("time", time).put("repeat", repeat).put("business", business)
        .put("enabled", enabled).put("lastStatus", lastStatus).put("lastAt", lastAt)
        .put("chats", JSONArray(chats))

    companion object {
        const val ONCE = 0
        const val DAILY = 1
        const val WEEKDAYS = 2
        const val WEEKLY = 3

        val REPEAT_NAMES = arrayOf("Один раз", "Каждый день", "По будням (пн–пт)", "Каждую неделю")

        private fun isWeekend(c: Calendar): Boolean {
            val d = c.get(Calendar.DAY_OF_WEEK)
            return d == Calendar.SATURDAY || d == Calendar.SUNDAY
        }

        fun fromJson(o: JSONObject) = Msg(
            id = o.getInt("id"),
            phone = o.optString("phone"),
            name = o.optString("name"),
            text = o.optString("text"),
            time = o.optLong("time"),
            repeat = o.optInt("repeat"),
            business = o.optBoolean("business"),
            enabled = o.optBoolean("enabled", true),
            lastStatus = o.optString("lastStatus"),
            lastAt = o.optLong("lastAt"),
            chats = o.optJSONArray("chats")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )

        /** Приводит номер к виду 77011234567. 8XXXXXXXXXX и 10-значные номера считаются казахстанскими/российскими (+7). */
        fun normalizePhone(raw: String): String {
            val d = raw.filter { it.isDigit() }
            return when {
                d.length == 11 && d.startsWith("8") -> "7" + d.drop(1)
                d.length == 10 && !raw.trim().startsWith("+") -> "7$d"
                else -> d
            }
        }
    }
}

/** Хранилище сообщений в SharedPreferences (JSON). */
object Store {
    private const val PREFS = "store"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableList<Msg> {
        val arr = JSONArray(prefs(c).getString("list", "[]"))
        return MutableList(arr.length()) { Msg.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    private fun write(c: Context, list: List<Msg>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("list", arr.toString()).commit()
    }

    fun get(c: Context, id: Int): Msg? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun save(c: Context, m: Msg): Msg {
        if (m.id == 0) {
            val p = prefs(c)
            val next = p.getInt("nextId", 1)
            p.edit().putInt("nextId", next + 1).commit()
            m.id = next
        }
        val list = all(c)
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list.add(m)
        write(c, list)
        return m
    }

    @Synchronized
    fun delete(c: Context, id: Int) {
        write(c, all(c).filter { it.id != id })
    }

    @Synchronized
    fun setStatus(c: Context, id: Int, status: String) {
        val m = get(c, id) ?: return
        m.lastStatus = status
        m.lastAt = System.currentTimeMillis()
        save(c, m)
    }
}
