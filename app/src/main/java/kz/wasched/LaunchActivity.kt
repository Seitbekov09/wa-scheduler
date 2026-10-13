package kz.wasched

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/** Невидимый экран: включает дисплей, снимает блокировку без пароля и открывает чат с готовым текстом. */
class LaunchActivity : Activity() {
    private var id = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra("id", 0)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }

        val km = getSystemService(KeyguardManager::class.java)
        when {
            !km.isKeyguardLocked -> openWhatsApp()
            km.isKeyguardSecure -> failAndClose(
                "Не отправлено: экран заблокирован PIN/паролем/отпечатком. " +
                    "Для автоотправки блокировка должна быть «Нет» или «Провести по экрану».",
            )
            else -> km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openWhatsApp()
                override fun onDismissError() = failAndClose("Не отправлено: не удалось снять блокировку экрана")
                override fun onDismissCancelled() = failAndClose("Не отправлено: блокировка экрана не снята")
            })
        }
    }

    private fun openWhatsApp() {
        val m = Store.get(this, id) ?: return failAndClose("Сообщение не найдено")
        val pkg = if (m.business) "com.whatsapp.w4b" else "com.whatsapp"
        val query = "phone=${m.phone}&text=${Uri.encode(m.text)}"
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?$query")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?$query")),
        )
        for (i in intents) {
            i.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                Sender.started(id)
                finish()
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        failAndClose(if (m.business) "WhatsApp Business не установлен" else "WhatsApp не установлен")
    }

    private fun failAndClose(reason: String) {
        Sender.fail(id, reason)
        finish()
    }
}
