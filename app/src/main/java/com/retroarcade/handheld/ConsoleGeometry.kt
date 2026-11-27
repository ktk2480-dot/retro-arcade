package com.retroarcade.handheld

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

class PadButton(
    val id: Int,
    val label: String,
    val cx: Float,
    val cy: Float,
    val r: Float,
    val color: Int,
)

/**
 * Where everything sits for one screen size. Held upright the game is on top and
 * the controls fill the lower deck; turned sideways the game sits in the middle
 * with the stick on the left and the buttons on the right.
 */
class ConsoleGeometry(
    val width: Int,
    val height: Int,
    val landscape: Boolean,
    /** 3 (one row), 4 (two by two) or 6 (two rows of three). */
    val buttonCount: Int,
    val game: Rect,
    val stickCx: Float,
    val stickCy: Float,
    val stickR: Float,
    /** A touch that starts anywhere in here steers the stick. */
    val stickZone: RectF,
    val buttons: List<PadButton>,
    val coin: RectF,
    val start: RectF,
    val menu: RectF,
) {
    /** Positions the player has dragged are stored as fractions of this length. */
    val unit: Float get() = min(width, height).toFloat()

    /** Writes the centre of a control into [out]; false when this layout has no such control. */
    fun center(id: Int, out: PointF): Boolean {
        when (id) {
            ID_STICK -> out.set(stickCx, stickCy)
            ID_COIN -> out.set(coin.centerX(), coin.centerY())
            ID_START -> out.set(start.centerX(), start.centerY())
            ID_MENU -> out.set(menu.centerX(), menu.centerY())
            else -> {
                val button = buttons.firstOrNull { it.id == id } ?: return false
                out.set(button.cx, button.cy)
            }
        }
        return true
    }

    /**
     * The same layout with controls shifted by the player's saved offsets
     * (control id -> offset in [unit]s). Nothing can be pushed off the screen.
     */
    fun moved(offsets: Map<Int, PointF>): ConsoleGeometry {
        if (offsets.isEmpty()) return this
        val u = unit

        val newButtons = buttons.map { b ->
            val o = offsets[b.id]
            if (o == null) {
                b
            } else {
                PadButton(
                    b.id,
                    b.label,
                    clamp(b.cx + o.x * u, b.r, width - b.r),
                    clamp(b.cy + o.y * u, b.r, height - b.r),
                    b.r,
                    b.color,
                )
            }
        }

        var newStickCx = stickCx
        var newStickCy = stickCy
        var newZone = stickZone
        val stickOffset = offsets[ID_STICK]
        if (stickOffset != null) {
            newStickCx = clamp(stickCx + stickOffset.x * u, stickR, width - stickR)
            newStickCy = clamp(stickCy + stickOffset.y * u, stickR, height - stickR)
            newZone = RectF(stickZone)
            newZone.offset(newStickCx - stickCx, newStickCy - stickCy)
        }

        return ConsoleGeometry(
            width, height, landscape, buttonCount, game,
            newStickCx, newStickCy, stickR, newZone, newButtons,
            movedPill(coin, offsets[ID_COIN], u),
            movedPill(start, offsets[ID_START], u),
            movedPill(menu, offsets[ID_MENU], u),
        )
    }

    private fun movedPill(rect: RectF, offset: PointF?, u: Float): RectF {
        if (offset == null) return rect
        val halfW = rect.width() / 2f
        val halfH = rect.height() / 2f
        val cx = clamp(rect.centerX() + offset.x * u, halfW, width - halfW)
        val cy = clamp(rect.centerY() + offset.y * u, halfH, height - halfH)
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    companion object {
        const val ID_NONE = -1

        // Action buttons are slots 0-5: A, B, C, then D (four buttons) or X, Y, Z (six).
        const val ID_COIN = 6
        const val ID_START = 7
        const val ID_MENU = 8

        /** Number of pressable controls (everything above). */
        const val ID_COUNT = 9

        /** The stick is not "pressed", but it can be moved in the layout editor. */
        const val ID_STICK = 9

        private val ROW_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt())
        private val TOP_COLORS = intArrayOf(0xFF1E88E5.toInt(), 0xFFECEFF1.toInt(), 0xFF8E24AA.toInt())

        private fun clamp(value: Float, low: Float, high: Float): Float = max(low, min(value, high))

        fun compute(
            w: Int,
            h: Int,
            insetL: Int,
            insetT: Int,
            insetR: Int,
            insetB: Int,
            verticalGame: Boolean,
            buttonCount: Int,
        ): ConsoleGeometry =
            if (w > h) landscape(w, h, insetL, insetR, verticalGame, buttonCount)
            else portrait(w, h, insetT, insetB, verticalGame, buttonCount)

        private fun portrait(
            w: Int,
            h: Int,
            insetT: Int,
            insetB: Int,
            verticalGame: Boolean,
            count: Int,
        ): ConsoleGeometry {
            val usable = (h - insetT - insetB).toFloat()
            val wanted = if (verticalGame) w * 4f / 3f else w * 3f / 4f
            val gameH = min(wanted, usable * 0.58f).toInt()
            val game = Rect(0, insetT, w, insetT + gameH)

            val deckTop = game.bottom.toFloat()
            val deckBottom = (h - insetB).toFloat()
            val deckH = deckBottom - deckTop

            // Coin / start / menu strip directly under the screen.
            val barH = min(deckH * 0.17f, w * 0.14f)
            val barCy = deckTop + barH * 0.58f
            val pillH = barH * 0.56f
            val pillW = w * 0.2f
            val gap = w * 0.03f
            val coin = pill(w * 0.5f - gap / 2f - pillW / 2f, barCy, pillW, pillH)
            val start = pill(w * 0.5f + gap / 2f + pillW / 2f, barCy, pillW, pillH)
            val menu = pill(w * 0.045f + pillW * 0.4f, barCy, pillW * 0.8f, pillH)

            val padTop = deckTop + barH
            val padH = deckBottom - padTop
            val cy = padTop + padH * 0.5f
            val stickR = min(w * 0.19f, padH * 0.36f)
            val buttonR = when (count) {
                3 -> min(w * 0.075f, padH * 0.15f)
                4 -> min(w * 0.09f, padH * 0.15f)
                else -> min(w * 0.072f, padH * 0.12f)
            }
            val buttons = actionButtons(w * 0.74f, cy + buttonR * 0.3f, buttonR, count)
            val zone = RectF(0f, padTop, w * 0.47f, deckBottom)

            return ConsoleGeometry(w, h, false, count, game, w * 0.25f, cy, stickR, zone, buttons, coin, start, menu)
        }

        private fun landscape(
            w: Int,
            h: Int,
            insetL: Int,
            insetR: Int,
            verticalGame: Boolean,
            count: Int,
        ): ConsoleGeometry {
            val availW = w - insetL - insetR
            // Keep enough room either side of the screen for thumbs.
            val minSide = h * 0.36f
            val wanted = if (verticalGame) h * 3f / 4f else h * 4f / 3f
            val gameW = max(min(wanted, availW - 2f * minSide), availW * 0.4f).toInt()
            val gameLeft = insetL + (availW - gameW) / 2
            val game = Rect(gameLeft, 0, gameLeft + gameW, h)

            val sideW = (availW - gameW) / 2f
            val leftCx = insetL + sideW / 2f
            val rightCx = game.right + sideW / 2f

            val barCy = h * 0.11f
            val pillH = h * 0.085f
            val smallW = sideW * 0.42f
            val menu = pill(leftCx - sideW * 0.24f, barCy, smallW, pillH)
            val coin = pill(leftCx + sideW * 0.24f, barCy, smallW, pillH)
            val start = pill(rightCx, barCy, sideW * 0.5f, pillH)

            val cy = h * 0.6f
            val stickR = min(sideW * 0.4f, h * 0.25f)
            val buttonR = when (count) {
                3 -> min(sideW * 0.14f, h * 0.105f)
                4 -> min(sideW * 0.19f, h * 0.11f)
                else -> min(sideW * 0.135f, h * 0.09f)
            }
            val buttons = actionButtons(rightCx, cy + buttonR * 0.3f, buttonR, count)
            val zone = RectF(0f, h * 0.2f, game.left.toFloat(), h.toFloat())

            return ConsoleGeometry(w, h, true, count, game, leftCx, cy, stickR, zone, buttons, coin, start, menu)
        }

        private fun pill(cx: Float, cy: Float, w: Float, h: Float) =
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        /**
         * Three buttons sit in one row (A B C); four in two rows (C D above A B);
         * six in two rows (X Y Z above A B C). Each column is a little higher than
         * the one before, like an arcade panel.
         */
        private fun actionButtons(cx: Float, cy: Float, r: Float, count: Int): List<PadButton> {
            val dx = r * 2.25f
            val dy = r * 2.4f
            val rise = r * 0.32f
            val list = ArrayList<PadButton>(6)

            if (count == 3) {
                val labels = arrayOf("A", "B", "C")
                for (i in 0 until 3) {
                    list.add(PadButton(i, labels[i], cx + (i - 1) * dx, cy - (i - 1) * rise, r, ROW_COLORS[i]))
                }
                return list
            }

            val columns = if (count == 4) 2 else 3
            val bottomLabels = arrayOf("A", "B", "C")
            val topLabels = if (count == 4) arrayOf("C", "D") else arrayOf("X", "Y", "Z")
            for (i in 0 until columns) {
                val x = cx + (i - (columns - 1) / 2f) * dx
                val lift = i * rise
                // Slots: bottom row first (0, 1, 2), then the top row.
                list.add(PadButton(columns + i, topLabels[i], x, cy - dy / 2f - lift, r, TOP_COLORS[i]))
                list.add(PadButton(i, bottomLabels[i], x, cy + dy / 2f - lift, r, ROW_COLORS[i]))
            }
            return list
        }
    }
}
