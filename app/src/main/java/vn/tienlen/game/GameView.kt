package vn.tienlen.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Toàn bộ giao diện + điều khiển. Người chơi 0 (bạn) ở dưới; 1 = phải, 2 = trên, 3 = trái.
 * Tất cả kích thước tính theo chiều cao màn hình nên tự co giãn theo TV 720p / 1080p.
 */
class GameView(context: Context) : View(context) {

    private val game = Game()
    private val renderer = CardRenderer()
    private val ui = Handler(Looper.getMainLooper())
    private val names = arrayOf("Bạn", "Bot 1", "Bot 2", "Bot 3")

    private var cursor = 0
    private val selected = HashSet<Int>()
    private var flashMsg: String? = null

    private val bgPaint = Paint()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var lastNavAt = 0L
    private var lastNavDir = 0
    private var motionDirX = 0
    private var motionDirY = 0
    private var motionFireAt = 0L

    private val step = Runnable { doStep() }
    private val clearFlash = Runnable { flashMsg = null; invalidate() }

    init {
        isFocusable = true
        keepScreenOn = true
        newGame()
    }

    // ------------------------------------------------------------------ vòng đời

    fun pause() { ui.removeCallbacks(step) }
    fun resume() { schedule() }

    fun hasSelection(): Boolean = selected.isNotEmpty()
    fun clearSelection() { selected.clear(); invalidate() }

    private fun newGame() {
        game.deal()
        selected.clear()
        cursor = 0
        flashMsg = null
        if (game.current == 0 && game.mustHave3) flash("Bạn có 3 bích - đi trước!")
        schedule()
        invalidate()
    }

    private fun flash(msg: String, ms: Long = 2200) {
        flashMsg = msg
        ui.removeCallbacks(clearFlash)
        ui.postDelayed(clearFlash, ms)
        invalidate()
    }

    /** Lên lịch cho lượt tiếp theo (bot đánh, hoặc người chơi tự bỏ nếu không có bài chặt). */
    private fun schedule() {
        ui.removeCallbacks(step)
        invalidate()
        if (game.winner >= 0) return
        if (game.current != 0) {
            ui.postDelayed(step, 1000)
        } else {
            val t = game.table
            if (t != null && Bot.beating(game.hands[0], t).isEmpty()) {
                flash("Không có bài lớn hơn - tự bỏ lượt")
                ui.postDelayed(step, 1500)
            }
        }
    }

    private fun doStep() {
        if (game.winner >= 0) return
        val p = game.current
        if (p == 0) {
            val t = game.table
            if (t != null && Bot.beating(game.hands[0], t).isEmpty()) game.pass(0)
        } else {
            val c = Bot.choose(game, p)
            if (c == null) {
                game.pass(p)
            } else {
                game.play(p, c.cards)
                if (game.lastChop) flash("${names[p]} chặt bằng ${c.label()}!")
            }
        }
        afterMove()
    }

    private fun afterMove() {
        val n = game.hands[0].size
        if (cursor >= n) cursor = maxOf(0, n - 1)
        selected.retainAll(game.hands[0].toSet())
        schedule()
    }

    // ------------------------------------------------------------------ điều khiển

    fun onGameKey(keyCode: Int, repeat: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { nav(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { nav(1); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (repeat == 0) moveCursor(-3); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (repeat == 0) moveCursor(3); return true }
        }
        if (repeat > 0) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT -> true
                else -> false
            }
        }
        if (game.winner >= 0) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_1 -> {
                    newGame(); true
                }
                else -> false
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> toggleCard()
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_START -> playSelected()
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_DPAD_DOWN -> passTurn()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> clearSelection()
            KeyEvent.KEYCODE_BUTTON_SELECT -> hint()
            else -> return false
        }
        return true
    }

    /** Cần/D-pad dạng trục (một số tay cầm báo D-pad bằng HAT thay vì phím). */
    fun onGameMotion(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            ev.action != MotionEvent.ACTION_MOVE) return false
        val hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val ax = ev.getAxisValue(MotionEvent.AXIS_X)
        val dx = when {
            hatX < -0.5f || ax < -0.6f -> -1
            hatX > 0.5f || ax > 0.6f -> 1
            else -> 0
        }
        val dy = when {
            hatY < -0.5f -> -1
            hatY > 0.5f -> 1
            else -> 0
        }
        val now = SystemClock.uptimeMillis()
        if (dx == 0) motionDirX = 0
        else if (dx != motionDirX) { motionDirX = dx; motionFireAt = now + 350; nav(dx) }
        else if (now >= motionFireAt) { motionFireAt = now + 130; nav(dx) }

        if (dy == 0) motionDirY = 0
        else if (dy != motionDirY) {
            motionDirY = dy
            if (game.winner < 0) { if (dy < 0) playSelected() else passTurn() }
        }
        return true
    }

    /** Di chuyển trái/phải, bỏ trùng khi tay cầm gửi cả phím lẫn trục cùng lúc. */
    private fun nav(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (dir == lastNavDir && now - lastNavAt < 25) return
        lastNavDir = dir
        lastNavAt = now
        moveCursor(dir)
    }

    private fun moveCursor(d: Int) {
        val n = game.hands[0].size
        if (n == 0) return
        cursor = (cursor + d).coerceIn(0, n - 1)
        invalidate()
    }

    private fun toggleCard() {
        val hand = game.hands[0]
        if (hand.isEmpty()) return
        val c = hand[cursor.coerceIn(0, hand.size - 1)]
        if (!selected.remove(c)) selected.add(c)
        invalidate()
    }

    private fun playSelected() {
        if (game.current != 0) { flash("Chưa đến lượt bạn"); return }
        if (selected.isEmpty()) { flash("Hãy chọn lá bài (nút A) trước"); return }
        val combo = Combo.of(selected.toList())
        if (combo == null) { flash("Bộ bài không hợp lệ"); return }
        if (!game.isLegal(combo)) {
            val t = game.table
            flash(
                if (t == null) "Ván đầu phải đánh kèm 3 bích"
                else "Không đánh được lên ${t.label()} này"
            )
            return
        }
        ui.removeCallbacks(step)
        game.play(0, combo.cards)
        selected.clear()
        if (game.lastChop) flash("Bạn chặt bằng ${combo.label()}!")
        afterMove()
    }

    private fun passTurn() {
        if (game.current != 0) { flash("Chưa đến lượt bạn"); return }
        if (game.table == null) { flash("Bạn đang dẫn lượt, không thể bỏ"); return }
        ui.removeCallbacks(step)
        game.pass(0)
        selected.clear()
        afterMove()
    }

    private fun hint() {
        if (game.current != 0) { flash("Chưa đến lượt bạn"); return }
        val c = Bot.choose(game, 0)
        selected.clear()
        if (c == null) {
            flash("Gợi ý: nên bỏ lượt")
        } else {
            selected.addAll(c.cards)
            flash("Gợi ý: ${c.label()} - bấm X để đánh")
        }
        invalidate()
    }

    // ------------------------------------------------------------------ vẽ

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = RadialGradient(
            w / 2f, h * 0.45f, maxOf(w, h) * 0.75f,
            0xFF1B7A43.toInt(), 0xFF0B3D20.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        drawScores(canvas, h)
        for (p in 1..3) drawOpponent(canvas, p, w, h)
        drawTableCombo(canvas, w, h)
        drawStatus(canvas, w, h)
        drawHand(canvas, w, h)
        drawHints(canvas, w, h)
        flashMsg?.let { drawFlash(canvas, it, w, h) }
        if (game.winner >= 0) drawGameOver(canvas, w, h)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun drawScores(c: Canvas, h: Float) {
        val s = "Thắng:  Bạn ${game.wins[0]}  |  Bot 1 ${game.wins[1]}  |  Bot 2 ${game.wins[2]}  |  Bot 3 ${game.wins[3]}"
        text(c, s, h * 0.03f, h * 0.05f, h * 0.030f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT)
    }

    private fun drawOpponent(c: Canvas, p: Int, w: Float, h: Float) {
        val n = game.hands[p].size
        val bw = h * 0.085f
        val bh = bw * 1.4f
        val st = h * 0.026f
        val horizontal = p == 2
        val length = if (horizontal) bw + (n - 1).coerceAtLeast(0) * st else bh + (n - 1).coerceAtLeast(0) * st

        val x0: Float
        val y0: Float
        when (p) {
            1 -> { x0 = w - h * 0.04f - bw; y0 = h * 0.24f }
            2 -> { x0 = (w - length) / 2f; y0 = h * 0.03f }
            else -> { x0 = h * 0.04f; y0 = h * 0.24f }
        }
        for (i in 0 until n) {
            if (horizontal) renderer.drawBack(c, x0 + i * st, y0, bw, bh)
            else renderer.drawBack(c, x0, y0 + i * st, bw, bh)
        }

        val label = "${names[p]}  ${n} lá"
        val active = game.current == p && game.winner < 0
        val size = h * 0.032f
        val lx: Float
        val ly: Float
        val align: Paint.Align
        when (p) {
            1 -> { lx = w - h * 0.03f; ly = h * 0.20f; align = Paint.Align.RIGHT }
            2 -> { lx = w / 2f; ly = y0 + bh + size * 1.4f; align = Paint.Align.CENTER }
            else -> { lx = h * 0.03f; ly = h * 0.20f; align = Paint.Align.LEFT }
        }
        tp.textSize = size
        val tw = tp.measureText(label)
        val padX = size * 0.6f
        val left = when (align) {
            Paint.Align.RIGHT -> lx - tw - padX
            Paint.Align.CENTER -> lx - tw / 2f - padX
            else -> lx - padX
        }
        rect.set(left, ly - size * 1.05f, left + tw + padX * 2f, ly + size * 0.35f)
        boxPaint.color = if (active) 0xFFFFD54F.toInt() else 0x66000000
        c.drawRoundRect(rect, size * 0.5f, size * 0.5f, boxPaint)
        text(c, label, lx, ly, size, if (active) 0xFF3E2723.toInt() else 0xFFFFFFFF.toInt(), align)

        if (game.passed[p]) {
            text(c, "Bỏ lượt", lx, ly + size * 1.4f, size * 0.9f, 0xFFFFAB91.toInt(), align)
        }
    }

    private fun drawTableCombo(c: Canvas, w: Float, h: Float) {
        val t = game.table ?: return
        val n = t.size
        val cw = h * 0.13f
        val ch = cw * 1.4f
        val maxSpan = w * 0.5f
        val st = minOf(cw * 0.55f, (maxSpan - cw) / maxOf(1, n - 1))
        val total = cw + (n - 1) * st
        val x0 = (w - total) / 2f
        val y0 = h * 0.30f
        for (i in 0 until n) renderer.drawCard(c, t.cards[i], x0 + i * st, y0, cw, ch)
        val who = if (game.lastPlayer in 0..3) names[game.lastPlayer] else ""
        text(c, "$who: ${t.label()}", w / 2f, y0 - h * 0.02f, h * 0.034f, 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
    }

    private fun drawStatus(c: Canvas, w: Float, h: Float) {
        if (game.winner >= 0) return
        val t = game.table
        val msg = when {
            game.current == 0 && t == null ->
                if (game.mustHave3) "Lượt của bạn: đánh bộ có 3 bích" else "Lượt của bạn: đánh bài bất kỳ"
            game.current == 0 -> "Lượt của bạn: đánh ${t!!.label()} lớn hơn, hoặc bỏ lượt"
            t == null -> "${names[game.current]} dẫn vòng mới..."
            else -> "${names[game.current]} đang suy nghĩ..."
        }
        val color = if (game.current == 0) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
        text(c, msg, w / 2f, h * 0.575f, h * 0.040f, color, Paint.Align.CENTER)
    }

    private fun drawHand(c: Canvas, w: Float, h: Float) {
        val hand = game.hands[0]
        val n = hand.size
        if (n == 0) return
        val cw = h * 0.19f
        val ch = cw * 1.4f
        val maxSpan = w * 0.80f
        val st = if (n > 1) minOf(cw * 0.62f, (maxSpan - cw) / (n - 1)) else 0f
        val total = cw + (n - 1) * st
        val x0 = (w - total) / 2f
        val baseY = h - ch - h * 0.075f
        val raise = h * 0.05f

        for (i in 0 until n) {
            val card = hand[i]
            val sel = selected.contains(card)
            val cur = i == cursor
            val x = x0 + i * st
            val y = baseY - (if (sel) raise else 0f) - (if (cur) raise * 0.5f else 0f)
            renderer.drawCard(c, card, x, y, cw, ch)
            if (sel) {
                boxPaint.color = 0x3300B0FF
                rect.set(x, y, x + cw, y + ch)
                c.drawRoundRect(rect, cw * 0.08f, cw * 0.08f, boxPaint)
            }
        }
        // khung vàng quanh lá đang trỏ (vẽ sau cùng để không bị lá bên phải che)
        val i = cursor.coerceIn(0, n - 1)
        val sel = selected.contains(hand[i])
        val x = x0 + i * st
        val y = baseY - (if (sel) raise else 0f) - raise * 0.5f
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = h * 0.007f
        boxPaint.color = 0xFFFFD54F.toInt()
        rect.set(x, y, x + cw, y + ch)
        c.drawRoundRect(rect, cw * 0.08f, cw * 0.08f, boxPaint)
        boxPaint.style = Paint.Style.FILL

        text(c, "Bạn  ${n} lá", x0, baseY - raise * 1.8f, h * 0.030f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT)
    }

    private fun drawHints(c: Canvas, w: Float, h: Float) {
        val s = "Trái/Phải: di chuyển   A: chọn lá   X (hoặc Lên): ĐÁNH   Y (hoặc Xuống): bỏ lượt   B: bỏ chọn   Select: gợi ý"
        text(c, s, w / 2f, h * 0.975f, h * 0.026f, 0xAAFFFFFF.toInt(), Paint.Align.CENTER)
    }

    private fun drawFlash(c: Canvas, msg: String, w: Float, h: Float) {
        val size = h * 0.038f
        tp.textSize = size
        val tw = tp.measureText(msg)
        val pad = size
        rect.set((w - tw) / 2f - pad, h * 0.115f - size * 1.1f, (w + tw) / 2f + pad, h * 0.115f + size * 0.5f)
        boxPaint.color = 0xDD000000.toInt()
        c.drawRoundRect(rect, size * 0.6f, size * 0.6f, boxPaint)
        text(c, msg, w / 2f, h * 0.115f, size, 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
    }

    private fun drawGameOver(c: Canvas, w: Float, h: Float) {
        boxPaint.color = 0xAA000000.toInt()
        c.drawRect(0f, 0f, w, h, boxPaint)
        val win = game.winner == 0
        val title = if (win) "BẠN THẮNG!" else "${names[game.winner]} thắng"
        text(c, title, w / 2f, h * 0.42f, h * 0.11f, if (win) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
        val s = "Bạn ${game.wins[0]}  |  Bot 1 ${game.wins[1]}  |  Bot 2 ${game.wins[2]}  |  Bot 3 ${game.wins[3]}"
        text(c, s, w / 2f, h * 0.54f, h * 0.045f, 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
        text(c, "Nhấn A hoặc Start để chơi ván mới", w / 2f, h * 0.66f, h * 0.045f, 0xFF80DEEA.toInt(), Paint.Align.CENTER)
    }
}
