package com.retroarcade.handheld

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The on-screen arcade panel: an 8-way stick, the action buttons and the
 * coin / start / menu keys. It covers the whole screen so that one view sees
 * every finger, and draws nothing over the game picture itself.
 *
 * In layout-editing mode the controls stop playing and can be dragged around.
 */
class GamepadView(context: Context) : View(context) {

    interface Listener {
        /** x and y are -1, 0 or 1. Up is y = -1. */
        fun onStick(x: Int, y: Int)

        /** Sixteen-way mode only: the stick as an analog position, each axis -1..1. */
        fun onAnalog(x: Float, y: Float)
        fun onButton(id: Int, pressed: Boolean)
        fun onMenu()

        /** The player pressed "done" in the layout editor. */
        fun onEditFinished()
    }

    var listener: Listener? = null
    var store: PadStore? = null

    var editing = false
        private set

    /** How the stick's position becomes directions: one of the MODE_ constants. */
    var stickMode: Int = MODE_8WAY
        set(value) {
            if (field != value) {
                releaseAll()
                field = value
                invalidate()
            }
        }

    /**
     * Sixteen-way mode sends an analog position. Games with ordinary direction
     * switches ignore that, so they are sent the nearest of the eight directions too.
     */
    var digitalWithAnalog = true

    /** The default layout, and the same layout with the player's moves applied. */
    private var base: ConsoleGeometry? = null
    private var geo: ConsoleGeometry? = null
    private var profileKey = ""
    private var offsets = HashMap<Int, PointF>()

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
    private var analogX = 0f
    private var analogY = 0f

    /** Pointer id -> the control that finger is on (or ID_NONE). */
    private val pointerTargets = SparseIntArray()
    private val pressed = BooleanArray(ConsoleGeometry.ID_COUNT)
    private val scratch = BooleanArray(ConsoleGeometry.ID_COUNT)

    // Layout editor state.
    private val editDone = RectF()
    private val editReset = RectF()
    private var editPressed = EDIT_NOTHING
    private var dragId = ConsoleGeometry.ID_NONE
    private var dragLastX = 0f
    private var dragLastY = 0f
    private val pointA = PointF()
    private val pointB = PointF()

    fun setGeometry(geometry: ConsoleGeometry, key: String) {
        if (base === geometry && profileKey == key) return
        base = geometry
        profileKey = key
        offsets = store?.loadOffsets(key) ?: HashMap()
        geo = geometry.moved(offsets)

        // "Reset" and "done" sit in the middle of the picture while editing.
        val u = geometry.unit
        val pillH = u * 0.1f
        val pillW = u * 0.3f
        val gap = u * 0.04f
        val cx = geometry.game.exactCenterX()
        val cy = geometry.game.exactCenterY() + pillH
        editReset.set(cx - gap / 2f - pillW, cy - pillH / 2f, cx - gap / 2f, cy + pillH / 2f)
        editDone.set(cx + gap / 2f, cy - pillH / 2f, cx + gap / 2f + pillW, cy + pillH / 2f)

        dragId = ConsoleGeometry.ID_NONE
        editPressed = EDIT_NOTHING
        releaseAll()
    }

    /** Lets go of everything, for example when the menu opens or the screen turns. */
    fun releaseAll() {
        stickPointer = -1
        knobDx = 0f
        knobDy = 0f
        setStick(0, 0)
        setAnalog(0f, 0f)
        pointerTargets.clear()
        syncButtons()
        invalidate()
    }

    fun startEditing() {
        releaseAll()
        dragId = ConsoleGeometry.ID_NONE
        editPressed = EDIT_NOTHING
        editing = true
        invalidate()
    }

    fun stopEditing() {
        editing = false
        dragId = ConsoleGeometry.ID_NONE
        editPressed = EDIT_NOTHING
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return onEditTouch(event)
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
            setAnalog(0f, 0f)
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
        if (inflated(g.coin, 0.35f).contains(x, y)) return ConsoleGeometry.ID_COIN
        if (inflated(g.start, 0.35f).contains(x, y)) return ConsoleGeometry.ID_START
        if (inflated(g.menu, 0.35f).contains(x, y)) return ConsoleGeometry.ID_MENU
        return ConsoleGeometry.ID_NONE
    }

    /** [rect] grown on every side by [factor] of its height. Returns a shared scratch rectangle. */
    private fun inflated(rect: RectF, factor: Float): RectF {
        val pad = rect.height() * factor
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
        var dx = x - g.stickCx
        var dy = y - g.stickCy
        // A two-way stick only travels along its one axis.
        if (stickMode == MODE_2WAY_HORIZONTAL) dy = 0f
        if (stickMode == MODE_2WAY_VERTICAL) dx = 0f

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
            setAnalog(0f, 0f)
            return
        }

        when (stickMode) {
            MODE_2WAY_HORIZONTAL -> setStick(if (dx > 0f) 1 else -1, 0)
            MODE_2WAY_VERTICAL -> setStick(0, if (dy > 0f) 1 else -1)
            MODE_4WAY -> {
                val ax = abs(dx)
                val ay = abs(dy)
                // Lean towards the direction already held, so a thumb resting near a
                // diagonal does not flicker between two directions.
                val horizontal = when {
                    stickX != 0 -> ax * 1.3f >= ay
                    stickY != 0 -> ax > ay * 1.3f
                    else -> ax >= ay
                }
                if (horizontal) setStick(if (dx > 0f) 1 else -1, 0) else setStick(0, if (dy > 0f) 1 else -1)
            }
            MODE_16WAY -> {
                val angle = atan2(dy.toDouble(), dx.toDouble())
                val snapped = (angle / (PI / 8.0)).roundToInt() * (PI / 8.0)
                setAnalog(cos(snapped).toFloat(), sin(snapped).toFloat())
                if (digitalWithAnalog) setEightWay(angle) else setStick(0, 0)
            }
            else -> setEightWay(atan2(dy.toDouble(), dx.toDouble()))
        }
    }

    /** Eight 45-degree sectors, 0 = right, counting clockwise on screen. */
    private fun setEightWay(angle: Double) {
        val sector = (angle / (PI / 4.0)).roundToInt()
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

    private fun setAnalog(x: Float, y: Float) {
        if (x == analogX && y == analogY) return
        analogX = x
        analogY = y
        listener?.onAnalog(x, y)
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

    // ---- layout editor ----------------------------------------------------------------------

    private fun onEditTouch(event: MotionEvent): Boolean {
        val b = base ?: return true
        val g = geo ?: return true
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editPressed = when {
                    editDone.contains(x, y) -> EDIT_DONE
                    editReset.contains(x, y) -> EDIT_RESET
                    else -> EDIT_NOTHING
                }
                dragId = if (editPressed == EDIT_NOTHING) hitMovable(g, x, y) else ConsoleGeometry.ID_NONE
                dragLastX = x
                dragLastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragId != ConsoleGeometry.ID_NONE) {
                    dragBy(b, g, dragId, x - dragLastX, y - dragLastY)
                    dragLastX = x
                    dragLastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragId != ConsoleGeometry.ID_NONE) {
                    store?.saveOffsets(profileKey, offsets)
                } else if (editPressed == EDIT_RESET && editReset.contains(x, y)) {
                    offsets.clear()
                    store?.clearOffsets(profileKey)
                    geo = b
                } else if (editPressed == EDIT_DONE && editDone.contains(x, y)) {
                    stopEditing()
                    listener?.onEditFinished()
                }
                dragId = ConsoleGeometry.ID_NONE
                editPressed = EDIT_NOTHING
            }
            MotionEvent.ACTION_CANCEL -> {
                dragId = ConsoleGeometry.ID_NONE
                editPressed = EDIT_NOTHING
            }
        }
        invalidate()
        return true
    }

    private fun hitMovable(g: ConsoleGeometry, x: Float, y: Float): Int {
        val system = hitSystem(g, x, y)
        if (system != ConsoleGeometry.ID_NONE) return system
        val action = hitAction(g, x, y)
        if (action != ConsoleGeometry.ID_NONE) return action
        if (hypot(x - g.stickCx, y - g.stickCy) <= g.stickR) return ConsoleGeometry.ID_STICK
        return ConsoleGeometry.ID_NONE
    }

    private fun dragBy(b: ConsoleGeometry, g: ConsoleGeometry, id: Int, dx: Float, dy: Float) {
        if (!g.center(id, pointA) || !b.center(id, pointB)) return
        val u = b.unit
        val offset = offsets.getOrPut(id) { PointF() }
        offset.set((pointA.x + dx - pointB.x) / u, (pointA.y + dy - pointB.y) / u)
        val moved = b.moved(offsets)
        // Keep what was actually reached, so a control held against an edge comes
        // straight back when the finger turns round.
        if (moved.center(id, pointA)) {
            offset.set((pointA.x - pointB.x) / u, (pointA.y - pointB.y) / u)
        }
        geo = moved
    }

    // ---- drawing ----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geo ?: return
        drawStick(canvas, g)
        for (b in g.buttons) drawButton(canvas, b, pressed[b.id])
        drawPill(canvas, g.coin, "COIN", 0xFFFFC107.toInt(), 0xFF2B2100.toInt(), pressed[ConsoleGeometry.ID_COIN])
        drawPill(canvas, g.start, "START", 0xFFECEFF1.toInt(), 0xFF1A1D21.toInt(), pressed[ConsoleGeometry.ID_START])
        drawPill(canvas, g.menu, "MENU", 0xFF3A3A48.toInt(), 0xFFD5DAE2.toInt(), pressed[ConsoleGeometry.ID_MENU])
        if (editing) drawEditor(canvas, g)
    }

    private fun drawStick(canvas: Canvas, g: ConsoleGeometry) {
        val r = g.stickR
        fill.color = 0xFF0D0D12.toInt()
        canvas.drawCircle(g.stickCx, g.stickCy, r, fill)
        stroke.color = 0xFF3A3A48.toInt()
        stroke.strokeWidth = r * 0.05f
        canvas.drawCircle(g.stickCx, g.stickCy, r, stroke)
        // The gate shows which way the stick can go.
        fill.color = 0xFF1A1A23.toInt()
        when (stickMode) {
            MODE_2WAY_HORIZONTAL -> drawSlot(canvas, g, true)
            MODE_2WAY_VERTICAL -> drawSlot(canvas, g, false)
            MODE_4WAY -> {
                drawSlot(canvas, g, true)
                drawSlot(canvas, g, false)
            }
            else -> canvas.drawCircle(g.stickCx, g.stickCy, r * 0.62f, fill)
        }
        if (stickMode == MODE_16WAY) {
            fill.color = 0xFF6A6A7A.toInt()
            for (i in 0 until 16) {
                val a = i * (PI / 8.0)
                canvas.drawCircle(
                    g.stickCx + (cos(a) * r * 0.8).toFloat(),
                    g.stickCy + (sin(a) * r * 0.8).toFloat(),
                    r * 0.03f,
                    fill,
                )
            }
        }

        val kx = g.stickCx + knobDx
        val ky = g.stickCy + knobDy
        fill.color = 0x66000000
        canvas.drawCircle(kx + r * 0.04f, ky + r * 0.07f, r * 0.44f, fill)
        fill.color = 0xFFD32F2F.toInt()
        canvas.drawCircle(kx, ky, r * 0.42f, fill)
        fill.color = 0x55FFFFFF
        canvas.drawCircle(kx - r * 0.13f, ky - r * 0.15f, r * 0.12f, fill)
    }

    private fun drawSlot(canvas: Canvas, g: ConsoleGeometry, horizontal: Boolean) {
        val along = g.stickR * 0.8f
        val across = g.stickR * 0.3f
        if (horizontal) {
            hitRect.set(g.stickCx - along, g.stickCy - across, g.stickCx + along, g.stickCy + across)
        } else {
            hitRect.set(g.stickCx - across, g.stickCy - along, g.stickCx + across, g.stickCy + along)
        }
        canvas.drawRoundRect(hitRect, across, across, fill)
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

    private fun drawEditor(canvas: Canvas, g: ConsoleGeometry) {
        val u = g.unit

        // Dim the picture and say what to do.
        fill.color = 0xB3000000.toInt()
        canvas.drawRect(g.game, fill)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = u * 0.045f
        canvas.drawText("버튼을 끌어서 옮기세요", editDone.left - u * 0.02f, editDone.top - u * 0.11f, text)
        text.color = 0xFFB8BCC6.toInt()
        text.textSize = u * 0.032f
        canvas.drawText("세로·가로 배치는 따로 저장됩니다", editDone.left - u * 0.02f, editDone.top - u * 0.045f, text)
        drawPill(canvas, editReset, "초기화", 0xFF3A3A48.toInt(), 0xFFD5DAE2.toInt(), editPressed == EDIT_RESET)
        drawPill(canvas, editDone, "완료", 0xFFFFC107.toInt(), 0xFF2B2100.toInt(), editPressed == EDIT_DONE)

        // Outline everything that can be dragged; the one in hand is brighter.
        stroke.strokeWidth = u * 0.006f
        for (b in g.buttons) {
            stroke.color = outlineColor(b.id)
            canvas.drawCircle(b.cx, b.cy, b.r * 1.2f, stroke)
        }
        stroke.color = outlineColor(ConsoleGeometry.ID_STICK)
        canvas.drawCircle(g.stickCx, g.stickCy, g.stickR * 1.05f, stroke)
        drawPillOutline(canvas, g.coin, ConsoleGeometry.ID_COIN)
        drawPillOutline(canvas, g.start, ConsoleGeometry.ID_START)
        drawPillOutline(canvas, g.menu, ConsoleGeometry.ID_MENU)
    }

    private fun outlineColor(id: Int): Int = if (id == dragId) 0xFFFFFFFF.toInt() else 0xCCFFC107.toInt()

    private fun drawPillOutline(canvas: Canvas, rect: RectF, id: Int) {
        stroke.color = outlineColor(id)
        val outline = inflated(rect, 0.18f)
        val radius = outline.height() / 2f
        canvas.drawRoundRect(outline, radius, radius, stroke)
    }

    companion object {
        const val MODE_8WAY = 0
        const val MODE_4WAY = 1
        const val MODE_2WAY_HORIZONTAL = 2
        const val MODE_2WAY_VERTICAL = 3
        const val MODE_16WAY = 4

        private const val EDIT_NOTHING = 0
        private const val EDIT_RESET = 1
        private const val EDIT_DONE = 2
    }
}
