package com.retroarcade.handheld

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
    companion object {
        const val ID_NONE = -1

        // Action buttons are identified by position: bottom row 1-3, top row 1-3.
        const val ID_B1 = 0
        const val ID_B2 = 1
        const val ID_B3 = 2
        const val ID_T1 = 3
        const val ID_T2 = 4
        const val ID_T3 = 5
        const val ID_COIN = 6
        const val ID_START = 7
        const val ID_MENU = 8
        const val ID_COUNT = 9

        private val BOTTOM_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt())
        private val TOP_COLORS = intArrayOf(0xFF1E88E5.toInt(), 0xFFECEFF1.toInt(), 0xFF8E24AA.toInt())

        fun compute(
            w: Int,
            h: Int,
            insetL: Int,
            insetT: Int,
            insetR: Int,
            insetB: Int,
            verticalGame: Boolean,
            sixButtons: Boolean,
        ): ConsoleGeometry =
            if (w > h) landscape(w, h, insetL, insetR, verticalGame, sixButtons)
            else portrait(w, h, insetT, insetB, verticalGame, sixButtons)

        private fun portrait(
            w: Int,
            h: Int,
            insetT: Int,
            insetB: Int,
            verticalGame: Boolean,
            six: Boolean,
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
            val buttonR = if (six) min(w * 0.072f, padH * 0.12f) else min(w * 0.09f, padH * 0.15f)
            val buttons = actionButtons(w * 0.74f, cy + buttonR * 0.3f, buttonR, six)
            val zone = RectF(0f, padTop, w * 0.47f, deckBottom)

            return ConsoleGeometry(w, h, false, game, w * 0.25f, cy, stickR, zone, buttons, coin, start, menu)
        }

        private fun landscape(
            w: Int,
            h: Int,
            insetL: Int,
            insetR: Int,
            verticalGame: Boolean,
            six: Boolean,
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
            val buttonR = if (six) min(sideW * 0.135f, h * 0.09f) else min(sideW * 0.19f, h * 0.11f)
            val buttons = actionButtons(rightCx, cy + buttonR * 0.3f, buttonR, six)
            val zone = RectF(0f, h * 0.2f, game.left.toFloat(), h.toFloat())

            return ConsoleGeometry(w, h, true, game, leftCx, cy, stickR, zone, buttons, coin, start, menu)
        }

        private fun pill(cx: Float, cy: Float, w: Float, h: Float) =
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        /** Two rows of two or three buttons, each column a little higher than the last. */
        private fun actionButtons(cx: Float, cy: Float, r: Float, six: Boolean): List<PadButton> {
            val columns = if (six) 3 else 2
            val dx = r * 2.25f
            val dy = r * 2.4f
            val rise = r * 0.32f
            val bottomIds = intArrayOf(ID_B1, ID_B2, ID_B3)
            val topIds = intArrayOf(ID_T1, ID_T2, ID_T3)
            val bottomLabels = if (six) arrayOf("A", "B", "C") else arrayOf("A", "B")
            val topLabels = if (six) arrayOf("X", "Y", "Z") else arrayOf("C", "D")
            val list = ArrayList<PadButton>(columns * 2)
            for (i in 0 until columns) {
                val x = cx + (i - (columns - 1) / 2f) * dx
                val lift = i * rise
                list.add(PadButton(topIds[i], topLabels[i], x, cy - dy / 2f - lift, r, TOP_COLORS[i]))
                list.add(PadButton(bottomIds[i], bottomLabels[i], x, cy + dy / 2f - lift, r, BOTTOM_COLORS[i]))
            }
            return list
        }
    }
}
