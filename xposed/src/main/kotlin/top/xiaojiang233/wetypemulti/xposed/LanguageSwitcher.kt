package top.xiaojiang233.wetypemulti.xposed

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import top.xiaojiang233.wetypemulti.common.layout.JaLayoutRules
import java.lang.ref.WeakReference

/**
 * Globe key that takes over WeType's 中/英 key (id `chSwitch`, bottom row, left of 换行), and the long vowel key (ー) that Japanese
 * mode adds next to 、。.
 *
 * WeType draws its keys itself, one self-drawn view per keyboard, so a key is not a View. [KeyLocator] finds a key's layout
 * object in the keyboard that is shown and reads the two rectangles WeType computed for it (touch cell and drawn button), so the
 * overlay lands on the real key whatever the screen, insets, orientation or layout. Nothing in it relies on an obfuscated name.
 * If the globe's key object cannot be found (a WeType update changed its shape), its place is worked out from the proportions of
 * the layout, anchored to the top of the keyboard view; never from the bottom of the window, which moves with the navigation bar
 * and put the overlay in the wrong place before.
 *
 * The globe covers the whole cell, so WeType never gets a touch that belongs to its original 中/英 key, and draws the key with
 * WeType's geometry and shadow strip. A tap cycles 中 → 英 → あ → 中 through [InputMode]. A long press is handed to WeType so its
 * popup opens.
 *
 * The long vowel key ([LongVowelView]) sits over WeType's emoji key, which the Japanese layout shows (`JaLayoutRules`) to make the
 * room. It is only there while Japanese is on.
 */
object LanguageSwitcher {
    private var globe: GlobeView? = null
    private var vowel: LongVowelView? = null
    private var decorRef: WeakReference<FrameLayout>? = null

    /** The id WeType gives its 中/英 key: `chSwitch` on the Chinese keyboard, `enSwitch` on the English one. */
    private val KEY_IDS = setOf("chSwitch", "enSwitch")
    private val locator = KeyLocator(KEY_IDS)
    private val vowelLocator = KeyLocator(setOf(JaLayoutRules.LONG_VOWEL_SLOT))
    /** WeType's keyboard view class. The views themselves are subclasses (S2ChineseQwertyKeyboard, ...). Fallback only. */
    private const val KEYBOARD_VIEW = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.o"
    /** The keyboards the fallback may be used on: the ones that certainly have the 中/英 key at its usual place. */
    private val FALLBACK_KEYBOARDS = setOf("S2ChineseQwertyKeyboard", "S3EnglishQwertyKeyboard")

    // WeType's layout is 1080 units wide with 4 rows (S2ChineseQwertyKeyboard.json). The key's cell is x 735..840 of the last
    // row; the button is inset 14 from the top and 130 high; the shadow strip is 3 and the corner radius 16 (measured on a device).
    private const val V_WIDTH = 1080f
    private const val ROWS = 4
    private const val ROW_H = 156f
    private const val CELL_LEFT = 735f
    private const val CELL_RIGHT = 840f
    private const val KEY_TOP_PAD = 14f
    private const val KEY_H = 130f
    private const val SHADOW_H = 3f
    private const val CORNER_RADIUS = 16f

    /** Last line written to the log per source, so [place] (it runs on every layout pass) logs only when something changed. */
    private val lastLog = HashMap<String, String>()

    /**
     * Redraw the globe label after the mode changed from elsewhere, and look for the keys again: a change of mode swaps the layout,
     * and WeType rebuilds its key objects a moment later, with no layout pass to tell us.
     */
    fun refresh() {
        val g = globe ?: return
        g.post { g.invalidate(); relayout() }
        g.postDelayed({ relayout() }, 150)
        g.postDelayed({ relayout() }, 450)
    }

    private fun relayout() {
        val decor = decorRef?.get() ?: return
        val g = globe ?: return
        place(decor, g)
    }

    fun attach(svc: InputMethodService, ctl: ImeController) {
        val decor = svc.window.window?.decorView as? FrameLayout ?: return
        decorRef = WeakReference(decor)
        val g = globe?.takeIf { it.parent === decor } ?: GlobeView(svc).also {
            decor.addView(it, FrameLayout.LayoutParams(1, 1))
            globe = it
        }
        g.ctl = ctl
        ctl.onLanguageChanged = { g.post { g.invalidate() } }
        g.syncLabel()
        decor.post { place(decor, g) }
        if (!g.hooked) {
            g.hooked = true
            decor.viewTreeObserver.addOnGlobalLayoutListener { place(decor, g) }
        }
    }

    /** Where the key is, in the keyboard view's pixel coordinates; [source] says how it was found, for the log. */
    private class Placement(val view: View, val cell: Rect, val key: Rect, val shadowPx: Float, val radiusPx: Float, val source: String)

    private fun locate(decor: View): Placement? {
        locator.locate(decor)?.let { h ->
            val scale = h.view.width / V_WIDTH
            return Placement(h.view, h.cell, h.key, (h.shadow ?: SHADOW_H) * scale, (h.corner ?: CORNER_RADIUS) * scale, "key object")
        }
        return proportional(decor)
    }

    /**
     * The key object was not found: work the cell out from the layout's proportions, from the top of the keyboard view.
     * Only for the Chinese and English QWERTY keyboards; the number, symbol and emoji keyboards have no such key, so
     * there the globe stays hidden instead of covering some other key.
     */
    private fun proportional(decor: View): Placement? {
        val kb = shownKeyboard(decor)?.takeIf { it.javaClass.simpleName in FALLBACK_KEYBOARDS } ?: return null
        val sx = kb.width / V_WIDTH
        val rowH = kb.height / ROWS.toFloat()
        val sy = rowH / ROW_H
        val cell = Rect((CELL_LEFT * sx).toInt(), (rowH * (ROWS - 1)).toInt(), (CELL_RIGHT * sx).toInt(), kb.height)
        val key = Rect(cell.left, cell.top + (KEY_TOP_PAD * sy).toInt(), cell.right, cell.top + ((KEY_TOP_PAD + KEY_H) * sy).toInt())
        return Placement(kb, cell, key, SHADOW_H * sx, CORNER_RADIUS * sx, "proportional")
    }

    /** The largest shown keyboard view of WeType's self-drawn keyboard family (matched through the class hierarchy). */
    private fun shownKeyboard(root: View): View? {
        var best: View? = null
        fun walk(v: View) {
            if (v === globe || v === vowel || v.visibility != View.VISIBLE) return
            if (v.isShown && v.width > 0 && v.height > 0 && isKeyboardView(v.javaClass) &&
                (best?.let { it.width * it.height < v.width * v.height } != false)) best = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return best
    }

    private fun isKeyboardView(cls: Class<*>): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.name == KEYBOARD_VIEW) return true
            c = c.superclass
        }
        return false
    }

    /** Both overlays, on every layout pass. A failure in one must not take the other down. */
    private fun place(decor: FrameLayout, g: GlobeView) {
        if (decor.width == 0) return
        runCatching { placeGlobe(decor, g) }.onFailure { log("globe", "place failed: $it") }
        runCatching { placeLongVowel(decor, g) }.onFailure { log("vowel", "place failed: $it") }
    }

    /**
     * [at] in the window's coordinates. The cell has to sit inside the keyboard view, and the view inside the window: anything
     * else is a wrong reading, and a key drawn in the wrong place is worse than none.
     */
    private fun cellInDecor(decor: View, at: Placement): RectF? {
        val kb = at.view
        val dl = IntArray(2); decor.getLocationInWindow(dl)
        val kl = IntArray(2); kb.getLocationInWindow(kl)
        val offX = kl[0] - dl[0]
        val offY = kl[1] - dl[1]
        val cell = RectF(
            (offX + at.cell.left).toFloat(), (offY + at.cell.top).toFloat(),
            (offX + at.cell.right).toFloat(), (offY + at.cell.bottom).toFloat()
        )
        val inside = at.cell.left >= 0 && at.cell.top >= 0 && at.cell.right <= kb.width && at.cell.bottom <= kb.height &&
            cell.left >= 0f && cell.top >= 0f && cell.right <= decor.width && cell.bottom <= decor.height && cell.height() >= 20f
        return cell.takeIf { inside }
    }

    private fun layoutOver(view: View, cell: RectF) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val w = cell.width().toInt()
        val h = cell.height().toInt()
        if (lp.width != w || lp.height != h || lp.leftMargin != cell.left.toInt() || lp.topMargin != cell.top.toInt()) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = cell.left.toInt()
            lp.topMargin = cell.top.toInt()
            view.layoutParams = lp
        }
    }

    private fun placeGlobe(decor: FrameLayout, g: GlobeView) {
        val at = locate(decor)
        if (at == null) {
            g.visibility = View.GONE
            log("globe", "no shown keyboard with a $KEY_IDS key; globe hidden")
            return
        }
        val cell = cellInDecor(decor, at)
        if (cell == null) {
            g.visibility = View.GONE
            log("globe", "${at.source}: the cell is outside the keyboard (${at.view.width}x${at.view.height}); globe hidden")
            return
        }
        val kb = at.view
        val kl = IntArray(2); kb.getLocationInWindow(kl)
        g.visibility = View.VISIBLE
        g.setKeyGeometry(
            (at.key.left - at.cell.left).toFloat(), (at.key.top - at.cell.top).toFloat(),
            at.key.width().toFloat(), at.key.height().toFloat(), at.shadowPx, at.radiusPx
        )
        g.keyboard = kb
        g.keyboardOrigin = intArrayOf(kl[0], kl[1])
        log("globe", "${at.source}: ${kb.javaClass.simpleName} ${kb.width}x${kb.height}@(${kl[0]},${kl[1]}) decor=${decor.width}x${decor.height} cell=$cell key=${at.key}")
        layoutOver(g, cell)
    }

    /** The overlay key: only while an active profile defines an overlayKeySlot, and enabled in config. */
    private fun placeLongVowel(decor: FrameLayout, g: GlobeView) {
        val ctl = g.ctl
        val slot = ctl?.currentProfile?.overlayKeySlot
        val config = ConfigRepository.get()
        val enabled = ctl != null && ctl.active && slot != null && config.longVowelKeyEnabled
        val hit = if (enabled) vowelLocator.locate(decor) else null
        val kbName = hit?.view?.javaClass?.simpleName ?: ""
        if (hit != null && !kbName.startsWith("S2ChineseQwertyKeyboard")) {
            vowel?.visibility = View.GONE
            log("vowel", "ignoring $slot in non-Chinese-QWERTY keyboard ($kbName); hidden")
            return
        }
        val at = hit?.let {
            val scale = it.view.width / V_WIDTH
            Placement(it.view, it.cell, it.key, (it.shadow ?: SHADOW_H) * scale, (it.corner ?: CORNER_RADIUS) * scale, "slot")
        }
        val cell = at?.let { cellInDecor(decor, it) }
        if (at == null || cell == null) {
            vowel?.visibility = View.GONE
            if (ctl != null && ctl.active && slot != null && config.longVowelKeyEnabled) {
                log("vowel", if (at == null) "no valid $slot slot in the shown keyboard; overlay key hidden"
                else "the slot is outside the keyboard; overlay key hidden")
            }
            return
        }
        val v = vowel ?: LongVowelView(g.context).also {
            decor.addView(it, FrameLayout.LayoutParams(1, 1))
            vowel = it
        }
        v.ctl = ctl
        v.keyboardContext = at.view.context
        v.visibility = View.VISIBLE
        v.setKeyGeometry(
            (at.key.left - at.cell.left).toFloat(), (at.key.top - at.cell.top).toFloat(),
            at.key.width().toFloat(), at.key.height().toFloat(), at.shadowPx, at.radiusPx
        )
        log("vowel", "${at.source}: ${at.view.javaClass.simpleName} cell=$cell key=${at.key} colors=${KeyTheme.greyKey(at.view.context)}")
        layoutOver(v, cell)
    }

    private fun log(source: String, m: String) {
        if (lastLog.put(source, m) != m) XposedCompat.log("WeTypeMulti/switcher", "$source: $m")
    }

    class GlobeView(private val svc: InputMethodService) : View(svc) {
        var ctl: ImeController? = null
        var keyboard: View? = null
        var keyboardOrigin = intArrayOf(0, 0)
        var hooked = false

        // Exact button placement within this cell
        private var keyLeft = 0f
        private var keyTop = 0f
        private var keyWidth = 0f
        private var keyHeight = 0f
        private var shadowHeight = 0f
        private var cornerRadius = 0f

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val ovalRect = RectF()
        private var downAt = 0L

        fun setKeyGeometry(left: Float, top: Float, width: Float, height: Float, shadowPx: Float, radiusPx: Float) {
            keyLeft = left
            keyTop = top
            keyWidth = width
            keyHeight = height
            shadowHeight = shadowPx
            cornerRadius = radiusPx
            invalidate()
        }

        fun syncLabel() = invalidate()

        private fun label(): String = ctl?.let { InputMode.label(it) } ?: "中"
        private fun dark() = (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        override fun onDraw(canvas: Canvas) {
            if (keyWidth <= 0f || keyHeight <= 0f) return
            val night = dark()
            val radius = if (cornerRadius > 0f) cornerRadius else keyHeight * 0.12f
            val rLeft = keyLeft
            val rRight = keyLeft + keyWidth
            val rTop = keyTop
            val rBottom = keyTop + keyHeight

            // 1. Shadow strip under the key
            shade.color = if (night) 0xFF1C1C1E.toInt() else 0xFFB9BCC2.toInt()
            canvas.drawRoundRect(rLeft, rTop + shadowHeight, rRight, rBottom + shadowHeight, radius, radius, shade)

            // 2. Key background (opaque, completely covering WeType's underlying key)
            bg.color = if (isPressed) {
                if (night) 0xFF3A3A3C.toInt() else 0xFFE0E0E2.toInt()
            } else {
                if (night) 0xFF4A4A4C.toInt() else Color.WHITE
            }
            // Draw 0.5px wider to prevent antialiasing fringe from revealing underlying characters
            canvas.drawRoundRect(rLeft - 0.5f, rTop - 0.5f, rRight + 0.5f, rBottom + 0.5f, radius, radius, bg)

            // 3. Globe icon & language label
            val fg = if (night) 0xFFE5E5E7.toInt() else 0xFF2B2B2B.toInt()
            stroke.color = fg
            stroke.strokeWidth = keyHeight * 0.028f
            val cx = rLeft + keyWidth / 2f
            val cy = rTop + keyHeight * 0.42f
            val r = keyHeight * 0.19f
            canvas.drawCircle(cx, cy, r, stroke)
            ovalRect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
            canvas.drawOval(ovalRect, stroke)
            canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
            canvas.drawLine(cx, cy - r, cx, cy + r, stroke)

            text.color = fg
            text.textSize = keyHeight * 0.22f
            canvas.drawText(label(), cx, rTop + keyHeight * 0.86f, text)
        }

        private var longPressing = false
        private val longPress = Runnable { longPressing = true }
        private var lastX = 0f
        private var lastY = 0f

        /**
         * Short tap cycles the mode; holding hands the whole gesture to WeType so its 中/英 popup
         * (with our 日语 entry) opens and drag-to-select works.
         */
        override fun onTouchEvent(e: MotionEvent): Boolean {
            lastX = e.x
            lastY = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    invalidate()
                    downAt = SystemClock.uptimeMillis()
                    longPressing = false
                    forward(MotionEvent.ACTION_DOWN, e.x, e.y, downAt)
                    postDelayed(longPress, 250)
                }
                MotionEvent.ACTION_MOVE -> forward(MotionEvent.ACTION_MOVE, e.x, e.y, downAt)
                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    invalidate()
                    removeCallbacks(longPress)
                    if (longPressing) {
                        forward(MotionEvent.ACTION_UP, e.x, e.y, downAt)
                    } else {
                        forward(MotionEvent.ACTION_CANCEL, e.x, e.y, downAt)
                        if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onTap()
                    }
                    longPressing = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    invalidate()
                    removeCallbacks(longPress)
                    forward(MotionEvent.ACTION_CANCEL, e.x, e.y, downAt)
                    longPressing = false
                }
            }
            return true
        }

        private fun forward(action: Int, x: Float, y: Float, downTime: Long) {
            val kb = keyboard ?: return
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return
            val ev = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                lp.leftMargin + x - (keyboardOrigin[0] - (parent as View).left),
                lp.topMargin + y - (keyboardOrigin[1] - (parent as View).top),
                0
            )
            runCatching { kb.dispatchTouchEvent(ev) }
            ev.recycle()
        }

        private fun onTap() {
            val next = ctl?.switchLanguage() ?: InputMode.Mode.CN
            XposedCompat.log("WeTypeMulti", "switcher tapped -> $next")
            syncLabel()
        }
    }
}
