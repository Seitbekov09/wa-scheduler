package kz.wasched

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба специальных возможностей. Ничего не делает, пока Sender не начал отправку.
 * Умеет: ввести PIN на экране блокировки и нажать «Отправить» в WhatsApp.
 */
class WaService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WaService? = null
            private set

        const val PIN_NOTHING = 0
        const val PIN_SWIPED = 1
        const val PIN_ENTERED = 2

        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEND_LABELS = listOf("Отправить", "Send", "Жіберу")
        private val ENTER_LABELS = setOf("enter", "ввод", "ok", "ок", "готово", "подтвердить", "done", "войти")
        private val KEY_ID = Regex("""[:/](?:key|digit)_?([0-9])$""")
        private const val TAP_GAP_MS = 260L
    }

    private val h = Handler(Looper.getMainLooper())
    private var busyUntil = 0L
    private var lastSwipe = 0L

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

    // ---------- Отправка в WhatsApp ----------

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

    // ---------- Ввод PIN на экране блокировки ----------

    fun isEnteringPin() = SystemClock.uptimeMillis() < busyUntil

    /**
     * Ищет цифровую клавиатуру экрана блокировки и набирает PIN.
     * Если клавиатуры не видно — проводит пальцем вверх, чтобы она появилась.
     */
    fun tryUnlock(pin: String): Int {
        if (isEnteringPin()) return PIN_NOTHING
        val dm = resources.displayMetrics

        val nodes = ArrayList<AccessibilityNodeInfo>()
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) {
            val pkg = r.packageName?.toString() ?: ""
            if (pkg.contains("systemui") || pkg.contains("keyguard")) collect(r, nodes, 0)
        }

        val keys = HashMap<Char, Rect>()
        // 1) Кнопки с id вида com.android.systemui:id/key5
        for (n in nodes) {
            val m = KEY_ID.find(n.viewIdResourceName ?: "") ?: continue
            val r = visibleBounds(n) ?: continue
            keys.putIfAbsent(m.groupValues[1][0], r)
        }
        // 2) Кнопки, на которых просто написана цифра (оболочки Xiaomi и др.)
        if (keys.size < 10) {
            for (n in nodes) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: continue
                if (t.length != 1 || !t[0].isDigit() || keys.containsKey(t[0])) continue
                val r = visibleBounds(n) ?: continue
                if (r.top > dm.heightPixels * 0.3) keys[t[0]] = r
            }
        }

        if (!pin.all { keys.containsKey(it) }) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSwipe > 1500) {
                lastSwipe = now
                swipeUp(dm.widthPixels, dm.heightPixels)
                return PIN_SWIPED
            }
            return PIN_NOTHING
        }

        val points = pin.map { keys.getValue(it) }.toMutableList()
        nodes.firstOrNull { n ->
            val id = n.viewIdResourceName ?: ""
            val desc = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase() ?: ""
            id.endsWith("key_enter") || id.endsWith("enter") || desc in ENTER_LABELS
        }?.let { visibleBounds(it) }?.let { points.add(it) }
        // Если кнопки «Ввод» нет — большинство телефонов проверяют PIN сами после последней цифры.

        busyUntil = SystemClock.uptimeMillis() + points.size * TAP_GAP_MS + 1500
        points.forEachIndexed { i, r ->
            h.postDelayed({ tap(r.exactCenterX(), r.exactCenterY()) }, i * TAP_GAP_MS)
        }
        return PIN_ENTERED
    }

    private fun visibleBounds(n: AccessibilityNodeInfo): Rect? {
        if (!n.isVisibleToUser) return null
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40 || out.size > 3000) return
        out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(),
            null, null,
        )
    }

    private fun swipeUp(w: Int, hgt: Int) {
        val p = Path().apply {
            moveTo(w / 2f, hgt * 0.85f)
            lineTo(w / 2f, hgt * 0.30f)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(),
            null, null,
        )
    }
}
