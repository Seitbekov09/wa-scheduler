package kz.wasched

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

/** Точные будильники через AlarmManager.setAlarmClock — срабатывают даже в режиме сна (Doze). */
object Scheduler {
    private const val ACTION = "kz.wasched.FIRE"

    /** Разблокирован ли телефон (после перезагрузки данные доступны только после первой разблокировки). */
    fun isUnlocked(c: Context): Boolean =
        c.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    private fun pending(c: Context, id: Int, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, AlarmReceiver::class.java).setAction(ACTION).putExtra("id", id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(c: Context, m: Msg, at: Long = m.time) {
        cancel(c, m.id)
        if (!m.enabled) return
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c, m.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(c: Context, id: Int) {
        val pi = pending(c, id, PendingIntent.FLAG_NO_CREATE) ?: return
        c.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /**
     * После перезагрузки/смены времени: заново ставим будильники и обрабатываем пропущенное.
     * [canSendNow] = true, если экран уже разблокирован (можно слать сразу);
     * иначе пропущенное помечается и уйдёт при первой разблокировке (sendPending).
     */
    fun rescheduleAll(c: Context, canSendNow: Boolean) {
        val now = System.currentTimeMillis()
        for (m in Store.all(c)) {
            if (!m.enabled) continue
            if (m.time > now) {
                schedule(c, m)
                continue
            }
            // Время уже прошло — телефон был выключен или заблокирован.
            if (canSendNow) {
                Sender.enqueue(c, m.id)
            } else {
                m.pendingCatchUp = true
                m.lastStatus = "Ждёт разблокировки телефона"
                m.lastAt = now
                Store.save(c, m)
            }
            val next = m.nextAfter(now)
            if (next != null) {
                m.time = next
                Store.save(c, m)
                schedule(c, m)
            } else if (canSendNow) {
                m.enabled = false
                Store.save(c, m)
            }
        }
    }

    /** При первой разблокировке после перезагрузки: досылаем всё помеченное. */
    fun sendPending(c: Context) {
        for (m in Store.all(c)) {
            if (!m.pendingCatchUp) continue
            m.pendingCatchUp = false
            if (m.nextAfter(System.currentTimeMillis()) == null) m.enabled = false
            Store.save(c, m)
            Sender.enqueue(c, m.id)
        }
    }
}

/** Срабатывает в назначенное время. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val m = Store.get(c, id) ?: return
        if (!m.enabled) return
        val next = m.nextAfter(System.currentTimeMillis())
        if (next != null) m.time = next else m.enabled = false
        Store.save(c, m)
        if (next != null) Scheduler.schedule(c, m)
        // Экран может быть заблокирован — отправка сама снимет блокировку и введёт PIN.
        Sender.enqueue(c, id)
    }
}

/** Перезагрузка, обновление приложения, смена времени/часового пояса. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.rescheduleAll(c, canSendNow = Scheduler.isUnlocked(c))
    }
}

/** Разблокировка экрана: досылаем сообщения, пропущенные пока телефон был заблокирован после перезагрузки. */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.sendPending(c)
    }
}
