package top.xiaojiang233.wetypemulti.xposed

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import top.xiaojiang233.wetypemulti.common.engine.CandidateLimits
import top.xiaojiang233.wetypemulti.common.engine.Context

/** Minimal candidate strip drawn into the IME window, independent of WeType's own views. */
object CandidateBar {
    private var bar: LinearLayout? = null

    /** Safe from any thread: views are only touched on the main looper; failures never reach the IME. */
    fun update(svc: InputMethodService?, ctx: Context?, suggestions: List<String>?) {
        svc ?: return
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() === main) runCatching { render(svc, ctx, suggestions) }
        else android.os.Handler(main).post { runCatching { render(svc, ctx, suggestions) } }
    }

    private fun render(svc: InputMethodService, ctx: Context?, suggestions: List<String>?) {
        val b = bar ?: LinearLayout(svc).also { l ->
            l.orientation = LinearLayout.HORIZONTAL
            l.setBackgroundColor(Color.parseColor("#F2F2F2"))
            val scroll = HorizontalScrollView(svc).apply { addView(l) }
            runCatching {
                svc.window.window?.decorView?.let { (it as? ViewGroup)?.addView(scroll, ViewGroup.LayoutParams(-1, -2)) }
            }
            bar = l
        }
        b.removeAllViews()
        val items = CandidateLimits.sanitize(
            if (ctx != null && ctx.preedit.isNotEmpty()) ctx.candidates.map { it.text } else suggestions.orEmpty(), 9)
        items.forEachIndexed { i, t ->
            b.addView(TextView(svc).apply {
                text = "${i + 1} $t"; textSize = 18f; setTextColor(Color.BLACK)
                gravity = Gravity.CENTER; setPadding(24, 12, 24, 12)
                setOnClickListener { onPick?.invoke(i, t) }
            })
        }
        (b.parent as? HorizontalScrollView)?.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    var onPick: ((Int, String) -> Unit)? = null
}
