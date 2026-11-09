package top.xiaojiang233.wetypemulti.xposed

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The long vowel key (ー), for Japanese mode. Japanese needs ー all the time (コーヒー, ラーメン), and WeType's keyboard has no
 * place for it: the swipe-up on A that used to carry it fires by accident on the most used vowel. So it has a key of its own, as
 * on Gboard. [LanguageSwitcher] lays this view over WeType's shift key (which acts as '词 on Chinese QWERTY).
 * The key is drawn like WeType's grey functional keys (matching Delete and 123 on the perimeter), in colors resolved from the
 * theme the keyboard itself uses, and types on press like they do.
 */
internal class LongVowelView(context: Context) : View(context) {
    var ctl: ImeController? = null
    var keyboardContext: Context? = null

    // The key inside this view, which covers the whole touch cell (so WeType never sees a touch that belongs to the slot).
    private var keyLeft = 0f
    private var keyTop = 0f
    private var keyWidth = 0f
    private var keyHeight = 0f
    private var shadowHeight = 0f
    private var cornerRadius = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setKeyGeometry(left: Float, top: Float, width: Float, height: Float, shadowPx: Float, radiusPx: Float) {
        keyLeft = left
        keyTop = top
        keyWidth = width
        keyHeight = height
        shadowHeight = shadowPx
        cornerRadius = radiusPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (keyWidth <= 0f || keyHeight <= 0f) return
        val colors = KeyTheme.greyKey(keyboardContext ?: context)
        val radius = if (cornerRadius > 0f) cornerRadius else keyHeight * 0.12f
        val l = keyLeft
        val t = keyTop
        val r = keyLeft + keyWidth
        val b = keyTop + keyHeight
        shade.color = colors.shadow
        canvas.drawRoundRect(l, t + shadowHeight, r, b + shadowHeight, radius, radius, shade)
        bg.color = colors.bg
        canvas.drawRoundRect(l - 0.5f, t - 0.5f, r + 0.5f, b + 0.5f, radius, radius, bg)
        if (isPressed) {
            bg.color = colors.press
            canvas.drawRoundRect(l, t, r, b, radius, radius, bg)
        }
        text.color = colors.text
        text.textSize = keyHeight * 0.38f
        val m = text.fontMetrics
        canvas.drawText(LABEL, (l + r) / 2f, (t + b) / 2f - (m.ascent + m.descent) / 2f, text)
    }

    /** Types on press, like WeType's own keys. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                ctl?.onLongVowel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val LABEL = "ー"
    }
}

/** Colors of WeType's keys, read from the theme the keyboard is drawn with, so the module's keys follow light and dark mode. */
internal object KeyTheme {
    class Colors(val bg: Int, val shadow: Int, val text: Int, val press: Int) {
        override fun toString() = "bg=%08x shadow=%08x text=%08x press=%08x".format(bg, shadow, text, press)
    }

    /** The grey keys (functional keys: shift, delete, 123, etc.). */
    fun greyKey(ctx: Context): Colors {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return Colors(
            bg = attr(ctx, "ime_color_btn_grey_bg") ?: if (night) 0xFF414141.toInt() else 0xFFB7BCC4.toInt(),
            shadow = attr(ctx, "ime_color_btn_grey_shadow") ?: if (night) 0xFF1C1C1E.toInt() else 0xFFA1A2A7.toInt(),
            text = attr(ctx, "ime_color_btn_grey_text") ?: if (night) 0xFFE5E5E7.toInt() else 0xFF121213.toInt(),
            press = attr(ctx, "ime_color_btn_grey_press") ?: if (night) 0x26FFFFFF else 0x26000000,
        )
    }

    /** The white keys (letters and symbols). WeType names these theme attributes in its layout files, e.g. `@attr/ime_color_btn_white_bg`. */
    fun whiteKey(ctx: Context): Colors {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return Colors(
            bg = attr(ctx, "ime_color_btn_white_bg") ?: if (night) 0xFF4A4A4C.toInt() else Color.WHITE,
            shadow = attr(ctx, "ime_color_btn_white_shadow") ?: if (night) 0xFF1C1C1E.toInt() else 0xFFB9BCC2.toInt(),
            text = attr(ctx, "ime_color_btn_white_text") ?: if (night) 0xFFE5E5E7.toInt() else 0xFF2B2B2B.toInt(),
            press = attr(ctx, "ime_color_btn_white_press") ?: if (night) 0x26FFFFFF else 0x26000000,
        )
    }

    private fun attr(ctx: Context, name: String): Int? = runCatching {
        val id = ctx.resources.getIdentifier(name, "attr", ctx.packageName)
        if (id == 0) return null
        val value = TypedValue()
        if (!ctx.theme.resolveAttribute(id, value, true)) return null
        when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> ctx.getColor(value.resourceId)
            else -> null
        }
    }.getOrNull()
}
