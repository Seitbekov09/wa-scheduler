package kz.wasched

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба специальных возможностей. Ничего не делает, пока Sender не открыл чат с нашим сообщением.
 * Тогда находит кнопку «Отправить» в WhatsApp и нажимает её.
 */
class WaService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WaService? = null
            private set

        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEND_LABELS = listOf("Отправить", "Send", "Жіберу")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Sender.isArmed()) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in PACKAGES) trySend()
    }

    /** Вызывается из событий и из периодического опроса Sender. */
    fun trySend() {
        if (!Sender.isArmed()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return

        // Защита: нажимаем, только если в поле ввода именно наш текст.
        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()
        val expected = Sender.expectedSnippet()
        if (entry != null && expected.isNotEmpty()) {
            val typed = entry.text?.toString() ?: ""
            if (!typed.contains(expected)) return
        }

        val button = findSendButton(root, pkg) ?: return
        var target: AccessibilityNodeInfo? = button
        while (target != null && !target.isClickable) target = target.parent
        if ((target ?: button).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Sender.onClicked()
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()?.let { return it }
        for (label in SEND_LABELS) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.contentDescription?.toString().equals(label, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }
}
