package kz.wasched

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Очередь отправки. Сообщения отправляются строго по одному:
 * открыть чат → дождаться, пока служба нажмёт «Отправить» → выйти на рабочий стол → следующее.
 * Всё работает в главном потоке.
 */
object Sender {
    private const val TIMEOUT_MS = 45_000L
    private const val POLL_MS = 700L

    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Int>()
    private lateinit var app: Context

    private var current = 0          // id сообщения, которое отправляется сейчас
    private var started = false      // WhatsApp уже открыт с нашим текстом
    private var clicked = false
    private var expected = ""        // кусок текста для проверки, что открыт правильный черновик
    private var seq = 0
    private var lockAfter = false
    private var wake: PowerManager.WakeLock? = null

    fun isArmed() = current != 0 && started && !clicked
    fun expectedSnippet() = expected

    fun enqueue(c: Context, id: Int) {
        app = c.applicationContext
        h.post {
            if (id != current && id !in queue) queue.addLast(id)
            if (current == 0) next()
        }
    }

    private fun next() {
        val id = queue.removeFirstOrNull()
        if (id == null) {
            done()
            return
        }
        val m = Store.get(app, id)
        if (m == null) {
            next()
            return
        }
        val svc = WaService.instance
        if (svc == null) {
            report(m, false, "Не отправлено: выключена служба «WA Планировщик» в Спец. возможностях")
            next()
            return
        }
        if (wake == null) {
            val pm = app.getSystemService(PowerManager::class.java)
            lockAfter = !pm.isInteractive
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wasched:send").apply {
                acquire(10 * 60_000L)
            }
        }

        current = id
        started = false
        clicked = false
        expected = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(15) ?: ""
        val mySeq = ++seq

        try {
            svc.startActivity(
                Intent(svc, LaunchActivity::class.java)
                    .putExtra("id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: Exception) {
            finish(false, "Не удалось открыть экран: ${e.message}")
            return
        }
        h.postDelayed({
            if (seq == mySeq && current == id && !clicked) {
                finish(false, "Не отправлено: кнопка «Отправить» не появилась. Проверьте номер — он есть в WhatsApp?")
            }
        }, TIMEOUT_MS)
    }

    /** LaunchActivity открыла WhatsApp. Начинаем искать кнопку. */
    fun started(id: Int) = h.post {
        if (current != id) return@post
        started = true
        poll(seq)
    }

    private fun poll(mySeq: Int) {
        if (seq != mySeq || !isArmed()) return
        WaService.instance?.trySend()
        h.postDelayed({ poll(mySeq) }, POLL_MS)
    }

    /** Служба нажала «Отправить». */
    fun onClicked() {
        if (current == 0 || clicked) return
        clicked = true
        val mySeq = seq
        h.postDelayed({ if (seq == mySeq) finish(true, "Отправлено") }, 2000)
    }

    fun fail(id: Int, reason: String) = h.post {
        if (current == id) finish(false, reason)
    }

    private fun finish(ok: Boolean, status: String) {
        val id = current
        if (id == 0) return
        current = 0
        started = false
        seq++
        Store.get(app, id)?.let { report(it, ok, status) }
        WaService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        h.postDelayed({ if (current == 0) next() }, 1500)
    }

    private fun done() {
        if (lockAfter && Build.VERSION.SDK_INT >= 28) {
            WaService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
        lockAfter = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun report(m: Msg, ok: Boolean, status: String) {
        Store.setStatus(app, m.id, status)
        Notify.show(app, (if (ok) "✅ " else "⚠️ ") + m.label(), status)
    }
}

object Notify {
    private const val CHANNEL = "results"

    fun show(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Результаты отправки", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val pi = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 1_000_000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}
