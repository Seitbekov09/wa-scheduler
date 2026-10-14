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
        private val SEARCH_LABELS = setOf("поиск", "search", "іздеу")

        // ----- Выбор чата в WhatsApp -----
        @Volatile private var capturing = false
        @Volatile private var captureUntil = 0L
        @Volatile private var captured: String? = null

        fun startCapture() {
            captured = null
            capturing = true
            captureUntil = SystemClock.uptimeMillis() + 3 * 60_000L
        }

        fun cancelCapture() {
            capturing = false
        }

        /** Название чата, на который нажали в WhatsApp (один раз). */
        fun takeCaptured(): String? = captured.also { captured = null }
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

    private var lastEventWork = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        try {
            if (capturing && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                onCaptureClick(event)
                return
            }
            // Отправку в чаты по имени двигает только опрос из Sender — здесь не трогаем,
            // чтобы шквал событий WhatsApp не перегружал службу (иначе Android её отключает).
            if (Sender.isArmed() && !Sender.isShareMode()) {
                val now = SystemClock.uptimeMillis()
                if (now - lastEventWork < 300) return
                lastEventWork = now
                trySend()
            }
        } catch (_: Throwable) {
            // Служба не должна падать из-за одной ошибки — иначе система её отключит.
        }
    }

    private fun onCaptureClick(event: AccessibilityEvent) {
        if (SystemClock.uptimeMillis() > captureUntil) {
            capturing = false
            return
        }
        val name = event.source?.let { firstText(it, 0) }
            ?: event.text?.firstOrNull { !it.isNullOrBlank() }?.toString()?.trim()
        if (name.isNullOrBlank()) return
        capturing = false
        captured = name
        // Возвращаемся в редактор; CLEAR_TOP закрывает список WhatsApp поверх него.
        startActivity(
            Intent(this, EditActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    /** Первый непустой текст в строке списка — это название чата. */
    private fun firstText(n: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val cls = n.className?.toString() ?: ""
        if (!cls.contains("EditText")) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { c -> firstText(c, depth + 1)?.let { return it } }
        }
        return null
    }

    // ---------- Отправка в WhatsApp ----------

    fun trySend() {
        try {
            trySendInner()
        } catch (_: Throwable) {
        }
    }

    private fun trySendInner() {
        if (!Sender.isArmed()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        if (Sender.isShareMode()) {
            trySendShare(root, pkg)
            return
        }

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

    // ---------- Отправка в выбранные чаты через список «Поделиться» WhatsApp ----------

    private var lastShareAction = 0L

    /** Убирает пробелы, невидимые символы и приводит к нижнему регистру — для устойчивого сравнения названий. */
    private fun norm(s: String): String = buildString {
        for (ch in s.trim()) {
            if (ch == '️' || ch == '‍' || ch == '​' || ch == '‌' || ch == '﻿') continue
            if (ch.isWhitespace()) continue
            append(ch.lowercaseChar())
        }
    }

    /** Оставляет только буквы и цифры (без эмодзи и знаков) — текстовая часть имени. */
    private fun stripText(s: String): String = buildString {
        for (ch in s) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
    }

    private val HEADERS = setOf(
        "недавние чаты", "другие контакты", "часто общаетесь", "статус",
        "recent chats", "other contacts", "frequently contacted", "status", "контакты",
    )

    /** Первая строка-контакт в результатах поиска: видимый текст ниже поля ввода, не заголовок раздела. */
    private fun firstResultRow(nodes: List<AccessibilityNodeInfo>, editBottom: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        for (n in nodes) {
            val cls = n.className?.toString() ?: ""
            if (cls.contains("EditText") || !n.isVisibleToUser) continue
            val raw = n.text?.toString()?.trim() ?: continue
            if (raw.isEmpty() || raw.lowercase() in HEADERS) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.top < editBottom || r.height() <= 0) continue
            if (r.top < bestTop) {
                bestTop = r.top
                best = n
            }
        }
        return best
    }

    private var scrollTries = 0

    private fun trySendShare(root: AccessibilityNodeInfo, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShareAction < 900) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)

        val target = Sender.nextChat()
        if (target != null) {
            val wantFull = norm(target)
            val wantEmoji = stripText(target) // без эмодзи, чтобы сравнивать текстовую часть имени
            val row = nodes.firstOrNull { n ->
                val cls = n.className?.toString() ?: ""
                if (cls.contains("EditText") || !n.isVisibleToUser) return@firstOrNull false
                val raw = n.text?.toString() ?: return@firstOrNull false
                val full = norm(raw)
                full.isNotEmpty() && (full == wantFull ||
                    (wantEmoji.length >= 2 && stripText(raw) == wantEmoji) ||
                    (wantFull.length >= 3 && (full.startsWith(wantFull) || wantFull.startsWith(full))))
            }
            if (row != null) {
                scrollTries = 0
                var t: AccessibilityNodeInfo? = row
                while (t != null && !t.isClickable) t = t.parent
                if ((t ?: row).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastShareAction = now
                    Sender.chatSelected()
                }
                return
            }
            // Если открыт поиск — печатаем название, затем жмём на первый результат.
            val edit = nodes.firstOrNull { (it.className?.toString() ?: "").contains("EditText") && it.isVisibleToUser }
            if (edit != null) {
                val typed = norm(edit.text?.toString() ?: "")
                // Для имени печатаем текстовую часть без эмодзи — WhatsApp по эмодзи не ищет.
                val queryText = target.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim().ifEmpty { target }
                if (typed != norm(queryText)) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, queryText)
                    }
                    edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    lastShareAction = now
                    return
                }
                // Текст введён — нажимаем на первый контакт в результатах.
                val editBottom = Rect().also { edit.getBoundsInScreen(it) }.bottom
                val first = firstResultRow(nodes, editBottom)
                if (first != null) {
                    var t: AccessibilityNodeInfo? = first
                    while (t != null && !t.isClickable) t = t.parent
                    if ((t ?: first).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        lastShareAction = now
                        scrollTries = 0
                        Sender.chatSelected()
                    }
                }
                return
            }
            // Чата не видно — сначала прокручиваем список, потом открываем поиск.
            if (scrollTries < 6) {
                val scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable }
                if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    scrollTries++
                    lastShareAction = now
                    return
                }
            }
            val search = nodes.firstOrNull { n ->
                val id = n.viewIdResourceName ?: ""
                val d = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                n.isVisibleToUser && (id.endsWith("menuitem_search") || id.endsWith("/search") || d in SEARCH_LABELS)
            }
            if (search != null) {
                var t: AccessibilityNodeInfo? = search
                while (t != null && !t.isClickable) t = t.parent
                (t ?: search).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastShareAction = now
            }
            return
        }

        // Все чаты выбраны — нажимаем «Отправить».
        val send = nodes.firstOrNull { n ->
            val id = n.viewIdResourceName ?: ""
            val d = (n.contentDescription ?: n.text)?.toString()?.trim() ?: ""
            n.isVisibleToUser && (id == "$pkg:id/send" || SEND_LABELS.any { it.equals(d, ignoreCase = true) })
        } ?: return
        var t: AccessibilityNodeInfo? = send
        while (t != null && !t.isClickable) t = t.parent
        if ((t ?: send).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastShareAction = now
            Sender.onClicked()
        }
    }

    // ---------- Ввод PIN на экране блокировки ----------

    fun isEnteringPin() = SystemClock.uptimeMillis() < busyUntil

    /**
     * Ищет цифровую клавиатуру экрана блокировки и набирает PIN.
     * Если клавиатуры не видно — проводит пальцем вверх, чтобы она появилась.
     */
    fun tryUnlock(pin: String): Int {
        return try {
            tryUnlockInner(pin)
        } catch (_: Throwable) {
            PIN_NOTHING
        }
    }

    private fun tryUnlockInner(pin: String): Int {
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
        if (depth > 25 || out.size > 900) return
        out.add(n)
        val count = n.childCount
        for (i in 0 until count) {
            val c = try {
                n.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            collect(c, out, depth + 1)
        }
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
