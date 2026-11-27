package com.retroarcade.handheld

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * The whole handheld: the game picture plus the control panel around it.
 * It re-arranges itself whenever its size changes, which is what makes the
 * portrait / landscape switch happen without restarting the game.
 */
class ConsoleLayout(
    context: Context,
    private val gameView: View,
    private val verticalGame: Boolean,
    private val store: PadStore,
) : ViewGroup(context) {

    val gamepad = GamepadView(context)

    /** Called when the arrangement flips between upright and sideways. */
    var onArrangementChanged: ((landscape: Boolean) -> Unit)? = null

    /** 3, 4 or 6 action buttons. */
    var buttonCount: Int = 6
        set(value) {
            if (field != value) {
                field = value
                geometry = null
                requestLayout()
            }
        }

    private var geometry: ConsoleGeometry? = null
    private var lastLandscape: Boolean? = null
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        gamepad.store = store
        addView(gameView)
        addView(gamepad)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Keep clear of a camera cut-out; everything else may run edge to edge.
        var l = 0
        var t = 0
        var r = 0
        var b = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                l = cutout.safeInsetLeft
                t = cutout.safeInsetTop
                r = cutout.safeInsetRight
                b = cutout.safeInsetBottom
            }
        }
        if (l != insetL || t != insetT || r != insetR || b != insetB) {
            insetL = l
            insetT = t
            insetR = r
            insetB = b
            geometry = null
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (w <= 0 || h <= 0) return

        var g = geometry
        if (g == null || g.width != w || g.height != h) {
            g = ConsoleGeometry.compute(w, h, insetL, insetT, insetR, insetB, verticalGame, buttonCount)
            geometry = g
        }
        gameView.measure(
            MeasureSpec.makeMeasureSpec(g.game.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(g.game.height(), MeasureSpec.EXACTLY),
        )
        gamepad.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val g = geometry ?: return
        gameView.layout(g.game.left, g.game.top, g.game.right, g.game.bottom)
        gamepad.layout(0, 0, right - left, bottom - top)
        gamepad.setGeometry(g, store.profileKey(g.landscape, g.buttonCount))
        if (lastLandscape != g.landscape) {
            lastLandscape = g.landscape
            onArrangementChanged?.invoke(g.landscape)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: return
        // Console body.
        canvas.drawColor(0xFF1D1D26.toInt())
        // Dark surround for the screen, with a thin lit edge.
        val edge = (if (g.landscape) g.height else g.width) * 0.008f
        paint.style = Paint.Style.FILL
        paint.color = 0xFF000000.toInt()
        if (g.landscape) {
            canvas.drawRect(g.game.left.toFloat(), 0f, g.game.right.toFloat(), g.height.toFloat(), paint)
            paint.color = 0xFF34343F.toInt()
            canvas.drawRect(g.game.left - edge, 0f, g.game.left.toFloat(), g.height.toFloat(), paint)
            canvas.drawRect(g.game.right.toFloat(), 0f, g.game.right + edge, g.height.toFloat(), paint)
        } else {
            canvas.drawRect(0f, 0f, g.width.toFloat(), g.game.bottom.toFloat(), paint)
            paint.color = 0xFF34343F.toInt()
            canvas.drawRect(0f, g.game.bottom.toFloat(), g.width.toFloat(), g.game.bottom + edge, paint)
        }
    }
}
