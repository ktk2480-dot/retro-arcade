package com.retroarcade.handheld

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The on-screen arcade panel: an 8-way stick, the action buttons and the
 * coin / start / menu keys. It covers the whole screen so that one view sees
 * every finger, and draws nothing over the game picture itself.
 */
class GamepadView(context: Context) : View(context) {

    interface Listener {
        /** x and y are -1, 0 or 1. Up is y = -1. */
        fun onStick(x: Int, y: Int)
        fun onButton(id: Int, pressed: Boolean)
        fun onMenu()
    }

    var listener: Listener? = null

    private var geo: ConsoleGeometry? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hitRect = RectF()

    private var stickPointer = -1
    private var stickX = 0
    private var stickY = 0
    private var knobDx = 0f
    private var knobDy = 0f

    /** Pointer id -> the control that finger is on (or ID_NONE). */
    private val pointerTargets = SparseIntArray()
    private val pressed = BooleanArray(ConsoleGeometry.ID_COUNT)
    private val scratch = BooleanArray(ConsoleGeometry.ID_COUNT)

    fun setGeometry(geometry: ConsoleGeometry) {
        if (geo === geometry) return
        geo = geometry
        releaseAll()
    }

    /** Lets go of everything, for example when the menu opens or the screen turns. */
    fun releaseAll() {
        stickPointer = -1
        knobDx = 0f
        knobDy = 0f
        setStick(0, 0)
        pointerTargets.clear()
        syncButtons()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geo ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerDown(g, event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerMove(g, event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pointerUp(g, event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        syncButtons()
        invalidate()
        return true
    }

    private fun pointerDown(g: ConsoleGeometry, id: Int, x: Float, y: Float) {
        val system = hitSystem(g, x, y)
        if (system == ConsoleGeometry.ID_NONE && stickPointer == -1 && g.stickZone.contains(x, y)) {
            stickPointer = id
            updateStick(g, x, y)
            return
        }
        pointerTargets.put(id, if (system != ConsoleGeometry.ID_NONE) system else hitAction(g, x, y))
    }

    private fun pointerMove(g: ConsoleGeometry, id: Int, x: Float, y: Float) {
        if (id == stickPointer) {
            updateStick(g, x, y)
            return
        }
        val index = pointerTargets.indexOfKey(id)
        if (index < 0) return
        // A finger on coin / start / menu stays there; one on the action buttons may roll across them.
        if (pointerTargets.valueAt(index) >= ConsoleGeometry.ID_COIN) return
        pointerTargets.put(id, hitAction(g, x, y))
    }

    private fun pointerUp(g: ConsoleGeometry, id: Int, x: Float, y: Float) {
        if (id == stickPointer) {
            stickPointer = -1
            knobDx = 0f
            knobDy = 0f
            setStick(0, 0)
            return
        }
        val index = pointerTargets.indexOfKey(id)
        if (index < 0) return
        val target = pointerTargets.valueAt(index)
        pointerTargets.removeAt(index)
        if (target == ConsoleGeometry.ID_MENU && hitSystem(g, x, y) == ConsoleGeometry.ID_MENU) {
            listener?.onMenu()
        }
    }

    private fun hitSystem(g: ConsoleGeometry, x: Float, y: Float): Int {
        if (inflated(g.coin).contains(x, y)) return ConsoleGeometry.ID_COIN
        if (inflated(g.start).contains(x, y)) return ConsoleGeometry.ID_START
        if (inflated(g.menu).contains(x, y)) return ConsoleGeometry.ID_MENU
        return ConsoleGeometry.ID_NONE
    }

    private fun inflated(rect: RectF): RectF {
        val pad = rect.height() * 0.35f
        hitRect.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
        return hitRect
    }

    /** The nearest action button whose (slightly enlarged) circle contains the point. */
    private fun hitAction(g: ConsoleGeometry, x: Float, y: Float): Int {
        var best = ConsoleGeometry.ID_NONE
        var bestDistance = Float.MAX_VALUE
        for (b in g.buttons) {
            val d = hypot(x - b.cx, y - b.cy)
            if (d <= b.r * 1.3f && d < bestDistance) {
                bestDistance = d
                best = b.id
            }
        }
        return best
    }

    private fun updateStick(g: ConsoleGeometry, x: Float, y: Float) {
        val dx = x - g.stickCx
        val dy = y - g.stickCy
        val distance = hypot(dx, dy)
        val travel = g.stickR * 0.5f
        if (distance > travel) {
            knobDx = dx / distance * travel
            knobDy = dy / distance * travel
        } else {
            knobDx = dx
            knobDy = dy
        }
        if (distance < g.stickR * 0.22f) {
            setStick(0, 0)
            return
        }
        // Eight 45-degree sectors, 0 = right, counting clockwise on screen.
        val sector = (atan2(dy.toDouble(), dx.toDouble()) / (PI / 4.0)).roundToInt()
        val sx = when (sector) {
            -1, 0, 1 -> 1
            -4, -3, 3, 4 -> -1
            else -> 0
        }
        val sy = when (sector) {
            1, 2, 3 -> 1
            -3, -2, -1 -> -1
            else -> 0
        }
        setStick(sx, sy)
    }

    private fun setStick(x: Int, y: Int) {
        if (x == stickX && y == stickY) return
        stickX = x
        stickY = y
        listener?.onStick(x, y)
    }

    private fun syncButtons() {
        scratch.fill(false)
        for (i in 0 until pointerTargets.size()) {
            val target = pointerTargets.valueAt(i)
            if (target >= 0 && target < scratch.size) scratch[target] = true
        }
        for (id in scratch.indices) {
            if (scratch[id] == pressed[id]) continue
            pressed[id] = scratch[id]
            if (id != ConsoleGeometry.ID_MENU) listener?.onButton(id, scratch[id])
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geo ?: return
        drawStick(canvas, g)
        for (b in g.buttons) drawButton(canvas, b, pressed[b.id])
        drawPill(canvas, g.coin, "COIN", 0xFFFFC107.toInt(), 0xFF2B2100.toInt(), pressed[ConsoleGeometry.ID_COIN])
        drawPill(canvas, g.start, "START", 0xFFECEFF1.toInt(), 0xFF1A1D21.toInt(), pressed[ConsoleGeometry.ID_START])
        drawPill(canvas, g.menu, "MENU", 0xFF3A3A48.toInt(), 0xFFD5DAE2.toInt(), pressed[ConsoleGeometry.ID_MENU])
    }

    private fun drawStick(canvas: Canvas, g: ConsoleGeometry) {
        val r = g.stickR
        fill.color = 0xFF0D0D12.toInt()
        canvas.drawCircle(g.stickCx, g.stickCy, r, fill)
        stroke.color = 0xFF3A3A48.toInt()
        stroke.strokeWidth = r * 0.05f
        canvas.drawCircle(g.stickCx, g.stickCy, r, stroke)
        fill.color = 0xFF1A1A23.toInt()
        canvas.drawCircle(g.stickCx, g.stickCy, r * 0.62f, fill)

        val kx = g.stickCx + knobDx
        val ky = g.stickCy + knobDy
        fill.color = 0x66000000
        canvas.drawCircle(kx + r * 0.04f, ky + r * 0.07f, r * 0.44f, fill)
        fill.color = 0xFFD32F2F.toInt()
        canvas.drawCircle(kx, ky, r * 0.42f, fill)
        fill.color = 0x55FFFFFF
        canvas.drawCircle(kx - r * 0.13f, ky - r * 0.15f, r * 0.12f, fill)
    }

    private fun drawButton(canvas: Canvas, b: PadButton, down: Boolean) {
        val r = if (down) b.r * 0.93f else b.r
        fill.color = 0xFF0D0D12.toInt()
        canvas.drawCircle(b.cx, b.cy, b.r * 1.1f, fill)
        fill.color = b.color
        canvas.drawCircle(b.cx, b.cy, r, fill)
        if (down) {
            fill.color = 0x66000000
            canvas.drawCircle(b.cx, b.cy, r, fill)
        } else {
            fill.color = 0x33FFFFFF
            canvas.drawCircle(b.cx - r * 0.25f, b.cy - r * 0.3f, r * 0.35f, fill)
        }
        text.color = 0xB3000000.toInt()
        text.textSize = b.r * 0.8f
        canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawPill(canvas: Canvas, rect: RectF, label: String, color: Int, labelColor: Int, down: Boolean) {
        val radius = rect.height() / 2f
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (down) {
            fill.color = 0x66000000
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        text.color = labelColor
        text.textSize = rect.height() * 0.42f
        canvas.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }
}
