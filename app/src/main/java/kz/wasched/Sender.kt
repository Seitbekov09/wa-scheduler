package kz.wasched

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Очередь отправки. Сообщения отправляются строго по одному:
 * включить экран → снять блокировку (при необходимости ввести PIN) → открыть чат →
 * дождаться нажатия «Отправить» → выйти на рабочий стол → следующее.
 * В конце телефон снова блокируется, если был заблокирован.
 * Всё работает в главном потоке.
 */
object Sender {
    private const val TIMEOUT_MS = 75_000L
    private const val POLL_MS = 700L
    private const val MAX_PIN_TRIES = 2   // больше не пробуем, чтобы телефон не заблокировался за неверные попытки

    private const val IDLE = 0
    private const val UNLOCK = 1
    private const val SEND = 2

    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Int>()
    private lateinit var app: Context

    private var current = 0
    private var phase = IDLE
    private var clicked = false
    private var expected = ""
    private var seq = 0
    private var pinTries = 0
    private var lastPinAt = 0L
    private var lockAfter = false
    private var wake: PowerManager.WakeLock? = null

    fun isArmed() = current != 0 && phase == SEND && !clicked
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
        val pm = app.getSystemService(PowerManager::class.java)
        val km = app.getSystemService(KeyguardManager::class.java)
        if (!pm.isInteractive || km.isKeyguardLocked) lockAfter = true
        if (wake == null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wasched:send").apply {
                acquire(10 * 60_000L)
            }
        }

        current = id
        phase = UNLOCK
        clicked = false
        pinTries = 0
        lastPinAt = 0L
        expected = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(15) ?: ""
        val mySeq = ++seq

        if (!pm.isInteractive || km.isKeyguardLocked) {
            // Прозрачный экран: включает дисплей и вызывает окно ввода PIN.
            try {
                svc.startActivity(
                    Intent(svc, LaunchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (_: Exception) {
            }
        }

        h.postDelayed({
            if (seq == mySeq && current == id && !clicked) {
                finish(
                    false,
                    if (phase == UNLOCK) "Не отправлено: не удалось снять блокировку экрана"
                    else "Не отправлено: кнопка «Отправить» не появилась. Проверьте номер — он есть в WhatsApp?",
                )
            }
        }, TIMEOUT_MS)
        h.postDelayed({ poll(mySeq) }, 600)
    }

    private fun poll(mySeq: Int) {
        if (seq != mySeq || current == 0) return
        val svc = WaService.instance
        if (svc == null) {
            finish(false, "Не отправлено: служба «WA Планировщик» отключилась")
            return
        }
        when (phase) {
            UNLOCK -> unlockStep(svc)
            SEND -> if (!clicked) svc.trySend()
        }
        if (seq == mySeq && current != 0) h.postDelayed({ poll(mySeq) }, POLL_MS)
    }

    private fun unlockStep(svc: WaService) {
        val km = app.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            openWhatsApp(svc)
            return
        }
        if (!km.isKeyguardSecure) return // блокировка без пароля — её снимает LaunchActivity

        val pin = PinStore.get(app)
        if (pin.isNullOrEmpty()) {
            finish(false, "Не отправлено: экран защищён паролем. Укажите PIN в приложении (кнопка «PIN экрана»)")
            return
        }
        if (svc.isEnteringPin()) return
        val now = SystemClock.elapsedRealtime()
        if (lastPinAt != 0L && now - lastPinAt < 5000) return
        if (pinTries >= MAX_PIN_TRIES) {
            finish(false, "Не отправлено: PIN не подошёл. Проверьте PIN в приложении")
            return
        }
        if (svc.tryUnlock(pin) == WaService.PIN_ENTERED) {
            pinTries++
            lastPinAt = now
        }
    }

    private fun openWhatsApp(svc: WaService) {
        val m = Store.get(app, current) ?: return finish(false, "Сообщение не найдено")
        val pkg = if (m.business) "com.whatsapp.w4b" else "com.whatsapp"
        val query = "phone=${m.phone}&text=${Uri.encode(m.text)}"
        val uris = listOf("whatsapp://send?$query", "https://api.whatsapp.com/send?$query")
        for (u in uris) {
            try {
                svc.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                phase = SEND
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        finish(false, if (m.business) "WhatsApp Business не установлен" else "WhatsApp не установлен")
    }

    /** Служба нажала «Отправить». */
    fun onClicked() {
        if (current == 0 || clicked) return
        clicked = true
        val mySeq = seq
        h.postDelayed({ if (seq == mySeq) finish(true, "Отправлено") }, 2000)
    }

    private fun finish(ok: Boolean, status: String) {
        val id = current
        if (id == 0) return
        current = 0
        phase = IDLE
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
