package com.pocketai.chat.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pocketai.chat.R
import kotlin.math.hypot

/**
 * Pocket AI's own floating button, drawn by the accessibility service over other apps (no extra permission).
 * Tap: ask about the screen. Drag: move it (it snaps to the nearest edge and stays there). Hold: hide it for an
 * hour or until it's turned back on (Pocket AI → Settings, or the Quick Settings tile). It also steps aside while
 * the Pocket AI panel is open, so it doesn't cover the panel's buttons.
 */
class FloatingButton(private val service: AccessibilityService, private val onTap: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = service.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val dp = service.resources.displayMetrics.density
    private val size = (54 * dp).toInt()
    private val margin = (6 * dp).toInt()
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var panelOpen = false
    private val comeBack = Runnable { refresh() }

    /** Shows or removes the button to match the settings (on/off, hidden for a while, panel open). */
    fun refresh() = main.post { if (shouldShow()) show() else remove() }

    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        refresh()
    }

    fun destroy() = main.post {
        removeMenu()
        remove()
        main.removeCallbacks(comeBack)
    }

    /** The Fold opened or closed: keep the button on screen. */
    fun onScreenChanged() = main.post {
        val v = view ?: return@post
        val p = params ?: return@post
        snap(v, p)
    }

    private fun shouldShow() = isOn(service) && !panelOpen && System.currentTimeMillis() >= prefs.getLong(KEY_UNTIL, 0)

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun show() {
        if (view != null) return
        val (w, h) = screen()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, w - size - margin).coerceIn(0, (w - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (h * 0.42).toInt()).coerceIn(0, (h - size).coerceAtLeast(0))
        }
        val v = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF0334FD6.toInt())
            }
            elevation = 6 * dp
            alpha = IDLE_ALPHA
            contentDescription = "Ask Pocket AI about this screen. Hold to hide."
            addView(
                ImageView(service).apply {
                    setImageResource(R.drawable.ic_notification)
                    setColorFilter(Color.WHITE)
                },
                FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER),
            )
        }
        v.setOnTouchListener(Touch(v, p))
        runCatching { wm.addView(v, p) }.onSuccess {
            view = v
            params = p
        }
    }

    private fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    /** Tap / drag / hold, told apart by how far and how long the finger moves. */
    private inner class Touch(private val v: View, private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var held = false
        private val hold = Runnable {
            if (!dragging) {
                held = true
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showMenu()
            }
        }

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    held = false
                    v.alpha = 1f
                    main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !held && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(hold)
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    v.alpha = IDLE_ALPHA
                    when {
                        held -> {}
                        dragging -> snap(v, p)
                        else -> onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    v.alpha = IDLE_ALPHA
                }
            }
            return true
        }
    }

    /** Moves the button to the nearest side edge, inside the screen, and remembers the spot. */
    private fun snap(v: View, p: WindowManager.LayoutParams) {
        val (w, h) = screen()
        p.x = if (p.x + size / 2 < w / 2) margin else w - size - margin
        p.y = p.y.coerceIn((24 * dp).toInt(), (h - size - 48 * dp).toInt().coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, p) }
        prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
    }

    /** The hold menu: hide for an hour, hide until turned back on, or cancel. Tapping outside closes it. */
    private fun showMenu() {
        removeMenu()
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fg = if (night) Color.WHITE else 0xFF1C1B1F.toInt()
        val col = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(if (night) 0xFF2B2930.toInt() else Color.WHITE)
            }
            elevation = 10 * dp
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        fun item(label: String, action: () -> Unit) = TextView(service).apply {
            text = label
            textSize = 16f
            setTextColor(fg)
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
            setOnClickListener {
                removeMenu()
                action()
            }
        }
        col.addView(TextView(service).apply {
            text = "Pocket AI button"
            textSize = 13f
            setTextColor(if (night) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt())
            setPadding((24 * dp).toInt(), (6 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
        })
        col.addView(item("Hide for 1 hour") { hideFor(HOUR) })
        col.addView(item("Hide until I turn it back on") {
            setOn(service, false)
            Toast.makeText(service, "Pocket AI button hidden. Turn it back on in Pocket AI → Settings, or the Quick Settings tile.", Toast.LENGTH_LONG).show()
        })
        col.addView(item("Cancel") {})
        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                removeMenu()
                true
            } else false
        }
        val mp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(col, mp) }.onSuccess { menu = col }
    }

    private fun removeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun hideFor(ms: Long) {
        prefs.edit().putLong(KEY_UNTIL, System.currentTimeMillis() + ms).apply()
        refresh()
        main.removeCallbacks(comeBack)
        main.postDelayed(comeBack, ms + 1_000)
        Toast.makeText(service, "Pocket AI button hidden for an hour", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_ON = "floatingButton"
        private const val KEY_UNTIL = "floatingButtonHiddenUntil"
        private const val KEY_X = "floatingButtonX"
        private const val KEY_Y = "floatingButtonY"
        private const val HOUR = 60 * 60_000L
        private const val IDLE_ALPHA = 0.88f

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

        /** Whether the button is switched on (it may still be hidden for an hour). */
        fun isOn(ctx: Context) = prefs(ctx).getBoolean(KEY_ON, true)

        /** Whether it's on and not hidden for the hour. */
        fun isShowing(ctx: Context) = isOn(ctx) && System.currentTimeMillis() >= prefs(ctx).getLong(KEY_UNTIL, 0)

        /** Turns the button on or off (also ends an hour's hiding) and updates it right away. */
        fun setOn(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ON, on).putLong(KEY_UNTIL, 0).apply()
            AskService.instance?.button?.refresh()
        }
    }
}
