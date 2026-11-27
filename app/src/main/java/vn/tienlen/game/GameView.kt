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
import java.util.Random

/**
 * Toàn bộ giao diện + điều khiển. Người chơi 0 (bạn) ở dưới; 1 = phải, 2 = trên, 3 = trái.
 * Kích thước tính theo chiều cao màn hình nên tự co giãn theo TV 720p / 1080p.
 *
 * Điều khiển: hướng (D-pad / HAT / cần trái) luôn cố định; các nút bấm gán được trong màn Cài đặt.
 * Có 3 vùng tiêu điểm: HAND (bộ bài), ACTION (nút ĐÁNH / BỎ LƯỢT), SETTINGS_BTN (nút Cài đặt).
 */
class GameView(context: Context) : View(context) {

    private enum class Zone { HAND, ACTION, SETTINGS_BTN }

    companion object {
        private const val DIR_LEFT = 0
        private const val DIR_RIGHT = 1
        private const val DIR_UP = 2
        private const val DIR_DOWN = 3
        private val DIR_NAMES = arrayOf("TRÁI", "PHẢI", "LÊN", "XUỐNG")
    }

    private val rng = Random()
    private val game = Game(rng)
    private val renderer = CardRenderer()
    private val keymap = KeyMap(context)
    private val ui = Handler(Looper.getMainLooper())
    private val names: Array<String> = run {
        val o = Names.pick(rng, 3)
        arrayOf("Bạn", o[0], o[1], o[2])
    }
    private val rankNames = arrayOf("Nhất", "Nhì", "Ba", "Bét")

    /** Activity cung cấp trạng thái đọc USB để hiện trong màn Cài đặt. */
    var usbStatus: () -> String = { "" }

    // trạng thái giao diện
    private var zone = Zone.HAND
    private var actionIdx = 0 // 0 = ĐÁNH, 1 = BỎ LƯỢT
    private var cursor = 0
    private val selected = HashSet<Int>()
    private var flashMsg: String? = null

    // màn Cài đặt
    private var inSettings = false
    private var settingsIdx = 0
    private var capturing = false
    private var settingsMsg = ""
    private var lastInput = "Chưa nhận phím nào - hãy thử bấm một nút"

    private val bgPaint = Paint()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // chống trùng phím + trục, giữ hướng để lặp
    private var lastNavAt = 0L
    private var lastNavDir = -1
    private var lastDpadKeyAt = 0L
    private var heldDir = -1
    private var virtState = 0
    private var virtInit = false

    private val step = Runnable { doStep() }
    private val clearFlash = Runnable { flashMsg = null; invalidate() }
    private val capTimeout = Runnable {
        if (capturing) {
            capturing = false
            settingsMsg = "Hết thời gian chờ, chưa đổi phím nào"
            invalidate()
        }
    }
    private val repeater = object : Runnable {
        override fun run() {
            if (heldDir < 0) return
            val canRepeat = heldDir <= DIR_RIGHT || inSettings
            if (canRepeat && SystemClock.uptimeMillis() - lastDpadKeyAt > 700) navigate(heldDir)
            ui.postDelayed(this, 120)
        }
    }

    init {
        isFocusable = true
        keepScreenOn = true
        newGame()
    }

    // ------------------------------------------------------------------ vòng đời

    fun pause() {
        ui.removeCallbacks(step)
        ui.removeCallbacks(repeater)
        ui.removeCallbacks(capTimeout)
        heldDir = -1
    }

    fun resume() { schedule() }

    private fun newGame() {
        game.deal()
        selected.clear()
        cursor = 0
        zone = Zone.HAND
        actionIdx = 0
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

    /** Lên lịch cho lượt tiếp theo (đối thủ đánh, hoặc bạn tự bỏ nếu không có bài chặt). */
    private fun schedule() {
        ui.removeCallbacks(step)
        invalidate()
        if (game.over || inSettings) return
        if (game.current != 0) {
            ui.postDelayed(step, if (game.out[0]) 450L else 1000L)
        } else {
            val t = game.table
            if (t != null && Bot.beating(game.hands[0], t).isEmpty()) {
                flash("Không có bài lớn hơn - tự bỏ lượt")
                ui.postDelayed(step, 1500)
            }
        }
    }

    private fun doStep() {
        if (game.over || inSettings) return
        val before = game.finished.size
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
        announceFinish(before)
        afterMove()
    }

    private fun announceFinish(before: Int) {
        if (game.finished.size > before) {
            val p = game.finished[before]
            flash("${names[p]} về ${rankNames[before]}!", 2800)
        }
    }

    private fun afterMove() {
        val n = game.hands[0].size
        if (cursor >= n) cursor = maxOf(0, n - 1)
        selected.retainAll(game.hands[0].toSet())
        schedule()
    }

    // ------------------------------------------------------------------ điều khiển: nhận phím

    /** Phím từ Android hoặc từ tay cầm USB đọc trực tiếp. Trả về true nếu game đã xử lý. */
    fun onKey(code: Int, repeat: Int, fromUsbIndex: Int = -1): Boolean {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false

        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> DIR_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> DIR_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> DIR_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DIR_DOWN
            else -> -1
        }
        if (dir >= 0) {
            lastDpadKeyAt = SystemClock.uptimeMillis()
            navigate(dir)
            return true
        }

        val act = actionFor(code)
        if (repeat > 0) return act != null || KeyEvent.isGamepadButton(code)

        lastInput = if (fromUsbIndex >= 0)
            "Vừa nhận: tay cầm USB nút số ${fromUsbIndex + 1}  →  ${keymap.rawName(code)}"
        else
            "Vừa nhận: phím ${keymap.rawName(code)}"

        if (inSettings) {
            settingsKey(code, act)
            return true
        }
        if (act == null) {
            invalidate()
            return KeyEvent.isGamepadButton(code)
        }
        doAction(act)
        return true
    }

    fun onUsbButton(index: Int, down: Boolean) {
        if (down) onKey(UsbButtons.keyCode(index), 0, index)
    }

    fun onUsbDirection(retroMask: Int) {
        val dir = when {
            retroMask and (1 shl 6) != 0 -> DIR_LEFT
            retroMask and (1 shl 7) != 0 -> DIR_RIGHT
            retroMask and (1 shl 4) != 0 -> DIR_UP
            retroMask and (1 shl 5) != 0 -> DIR_DOWN
            else -> -1
        }
        setHeld(dir)
    }

    /** HAT / cần trái = hướng; các trục khác (Z, RZ, RX, RY, trigger) = phím ảo gán được. */
    fun onMotion(ev: MotionEvent): Boolean {
        if (!ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.action != MotionEvent.ACTION_MOVE) return false
        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = ev.getAxisValue(MotionEvent.AXIS_X)
        val y = ev.getAxisValue(MotionEvent.AXIS_Y)

        var dir = -1
        if (hx < -0.5f) dir = DIR_LEFT
        else if (hx > 0.5f) dir = DIR_RIGHT
        else if (hy < -0.5f) dir = DIR_UP
        else if (hy > 0.5f) dir = DIR_DOWN
        else if (Math.abs(x) >= Math.abs(y)) {
            if (x < -0.6f) dir = DIR_LEFT else if (x > 0.6f) dir = DIR_RIGHT
        } else {
            if (y < -0.6f) dir = DIR_UP else if (y > 0.6f) dir = DIR_DOWN
        }
        setHeld(dir)

        val t = 0.5f
        val z = ev.getAxisValue(MotionEvent.AXIS_Z)
        val rz = ev.getAxisValue(MotionEvent.AXIS_RZ)
        val rx = ev.getAxisValue(MotionEvent.AXIS_RX)
        val ry = ev.getAxisValue(MotionEvent.AXIS_RY)
        val lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
        var m = 0
        if (z < -t) m = m or (1 shl 0)
        if (z > t) m = m or (1 shl 1)
        if (rz < -t) m = m or (1 shl 2)
        if (rz > t) m = m or (1 shl 3)
        if (rx < -t) m = m or (1 shl 4)
        if (rx > t) m = m or (1 shl 5)
        if (ry < -t) m = m or (1 shl 6)
        if (ry > t) m = m or (1 shl 7)
        if (lt > t) m = m or (1 shl 8)
        if (rt > t) m = m or (1 shl 9)
        if (!virtInit) {
            // lần đầu chỉ ghi nhớ trạng thái nghỉ của các trục, không coi là bấm
            virtInit = true
            virtState = m
        } else {
            val rising = m and virtState.inv()
            virtState = m
            for (b in 0 until KeyMap.VK_NAMES.size) {
                if ((rising shr b) and 1 != 0) onKey(KeyMap.VK_BASE + b, 0)
            }
        }
        return true
    }

    /** Nút Back: trả về true nếu game đã dùng (đóng cài đặt, bỏ chọn...). */
    fun onBack(): Boolean {
        if (inSettings) {
            if (capturing) {
                capturing = false
                ui.removeCallbacks(capTimeout)
                settingsMsg = "Đã hủy đổi phím"
                invalidate()
            } else {
                closeSettings()
            }
            return true
        }
        if (selected.isNotEmpty() || zone != Zone.HAND) {
            selected.clear()
            zone = Zone.HAND
            invalidate()
            return true
        }
        return false
    }

    private fun actionFor(code: Int): Act? {
        keymap.actionFor(code)?.let { return it }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Act.CONFIRM
            KeyEvent.KEYCODE_MENU -> Act.SETTINGS
            else -> null
        }
    }

    private fun setHeld(dir: Int) {
        if (dir == heldDir) return
        heldDir = dir
        ui.removeCallbacks(repeater)
        if (dir >= 0) {
            navigate(dir)
            ui.postDelayed(repeater, 380)
        }
    }

    // ------------------------------------------------------------------ điều khiển: hành động trong game

    private fun navigate(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (dir == lastNavDir && now - lastNavAt < 25) return // phím + trục báo trùng
        lastNavDir = dir
        lastNavAt = now
        lastInput = "Vừa nhận: hướng ${DIR_NAMES[dir]}"
        if (capturing) { invalidate(); return }
        if (inSettings) { settingsNav(dir); return }
        if (game.over) { invalidate(); return }
        when (zone) {
            Zone.HAND -> when (dir) {
                DIR_LEFT -> moveCursor(-1)
                DIR_RIGHT -> moveCursor(1)
                DIR_UP -> { zone = Zone.ACTION; actionIdx = 0 }
            }
            Zone.ACTION -> when (dir) {
                DIR_LEFT -> actionIdx = 0
                DIR_RIGHT -> actionIdx = 1
                DIR_UP -> zone = Zone.SETTINGS_BTN
                DIR_DOWN -> zone = Zone.HAND
            }
            Zone.SETTINGS_BTN -> if (dir == DIR_DOWN) zone = Zone.ACTION
        }
        invalidate()
    }

    private fun doAction(act: Act) {
        if (game.over) {
            if (act == Act.CONFIRM) newGame() else if (act == Act.SETTINGS) openSettings()
            return
        }
        when (act) {
            Act.CONFIRM -> when (zone) {
                Zone.HAND -> toggleCard()
                Zone.ACTION -> if (actionIdx == 0) playSelected() else passTurn()
                Zone.SETTINGS_BTN -> openSettings()
            }
            Act.CANCEL -> { selected.clear(); zone = Zone.HAND; invalidate() }
            Act.HINT -> hint()
            Act.SETTINGS -> openSettings()
            Act.JUMP_L -> { zone = Zone.HAND; moveCursor(-3) }
            Act.JUMP_R -> { zone = Zone.HAND; moveCursor(3) }
        }
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
        if (selected.isEmpty()) { flash("Hãy chọn lá bài trước (bấm ${keymap.shortName(Act.CONFIRM)} ở bộ bài)"); return }
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
        val before = game.finished.size
        game.play(0, combo.cards)
        selected.clear()
        zone = Zone.HAND
        if (game.lastChop) flash("Bạn chặt bằng ${combo.label()}!")
        announceFinish(before)
        afterMove()
    }

    private fun passTurn() {
        if (game.current != 0) { flash("Chưa đến lượt bạn"); return }
        if (game.table == null) { flash("Bạn đang dẫn lượt, không thể bỏ"); return }
        ui.removeCallbacks(step)
        game.pass(0)
        selected.clear()
        zone = Zone.HAND
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
            flash("Gợi ý: ${c.label()} - rồi bấm nút ĐÁNH")
        }
        invalidate()
    }

    // ------------------------------------------------------------------ màn Cài đặt

    private fun openSettings() {
        inSettings = true
        settingsIdx = 0
        capturing = false
        settingsMsg = ""
        ui.removeCallbacks(step)
        invalidate()
    }

    private fun closeSettings() {
        inSettings = false
        capturing = false
        ui.removeCallbacks(capTimeout)
        schedule()
    }

    private fun settingsNav(dir: Int) {
        val n = Act.values().size + 2
        if (dir == DIR_UP) settingsIdx = (settingsIdx - 1 + n) % n
        else if (dir == DIR_DOWN) settingsIdx = (settingsIdx + 1) % n
        invalidate()
    }

    private fun settingsKey(code: Int, act: Act?) {
        if (capturing) {
            val target = Act.values()[settingsIdx]
            keymap.set(target, code)
            capturing = false
            ui.removeCallbacks(capTimeout)
            settingsMsg = "Đã gán \"${target.label}\" = ${keymap.rawName(code)}"
            invalidate()
            return
        }
        val confirm = act == Act.CONFIRM || code == KeyEvent.KEYCODE_BUTTON_A
        if (confirm) {
            activateRow()
        } else if (act == Act.CANCEL || act == Act.SETTINGS) {
            closeSettings()
        } else {
            invalidate()
        }
    }

    private fun activateRow() {
        val n = Act.values().size
        when {
            settingsIdx < n -> {
                capturing = true
                settingsMsg = "Bấm nút trên tay cầm muốn gán (Back = hủy)"
                ui.removeCallbacks(capTimeout)
                ui.postDelayed(capTimeout, 8000)
            }
            settingsIdx == n -> {
                keymap.reset()
                settingsMsg = "Đã khôi phục phím mặc định"
            }
            else -> closeSettings()
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
        if (inSettings) {
            drawSettings(canvas, w, h)
            return
        }
        drawScores(canvas, w, h)
        drawSettingsButton(canvas, w, h)
        for (p in 1..3) drawOpponent(canvas, p, w, h)
        drawPile(canvas, w, h)
        drawStatus(canvas, w, h)
        drawActionButtons(canvas, w, h)
        drawHand(canvas, w, h)
        drawHints(canvas, w, h)
        if (game.over) drawGameOver(canvas, w, h)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, maxW: Float = 0f) {
        tp.textSize = size
        if (maxW > 0f) {
            val m = tp.measureText(s)
            if (m > maxW) tp.textSize = size * maxW / m
        }
        tp.color = color
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun drawScores(c: Canvas, w: Float, h: Float) {
        val size = h * 0.022f
        val x = h * 0.03f
        text(c, "Số ván về Nhất", x, h * 0.04f, size, 0xFFFFD54F.toInt(), Paint.Align.LEFT, w * 0.2f)
        for (p in 0..3) {
            text(c, "${names[p]}: ${game.wins[p]}", x, h * 0.04f + (p + 1) * size * 1.2f, size,
                0xCCFFFFFF.toInt(), Paint.Align.LEFT, w * 0.2f)
        }
    }

    private fun drawButton(
        c: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, label: String,
        base: Int, enabled: Boolean, focused: Boolean, size: Float
    ) {
        val grow = if (focused) bh * 0.08f else 0f
        rect.set(cx - bw / 2f - grow, cy - bh / 2f - grow, cx + bw / 2f + grow, cy + bh / 2f + grow)
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = if (enabled) base else 0xFF546E7A.toInt()
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, boxPaint)
        if (focused) {
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = bh * 0.1f
            boxPaint.color = 0xFFFFD54F.toInt()
            c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, boxPaint)
            boxPaint.style = Paint.Style.FILL
        }
        text(
            c, label, cx, cy + size * 0.35f, size,
            if (enabled) 0xFFFFFFFF.toInt() else 0xFFB0BEC5.toInt(), Paint.Align.CENTER, bw * 0.9f
        )
    }

    private fun drawSettingsButton(c: Canvas, w: Float, h: Float) {
        val bw = h * 0.30f
        val bh = h * 0.06f
        drawButton(
            c, w - h * 0.03f - bw / 2f, h * 0.025f + bh / 2f, bw, bh,
            "CÀI ĐẶT PHÍM (${keymap.shortName(Act.SETTINGS)})",
            0xFF37474F.toInt(), true, zone == Zone.SETTINGS_BTN && !game.over, h * 0.026f
        )
    }

    private fun drawActionButtons(c: Canvas, w: Float, h: Float) {
        if (game.over) return
        val bw = h * 0.30f
        val bh = h * 0.075f
        val cy = h * 0.61f
        val gap = h * 0.04f
        val myTurn = game.current == 0 && !game.out[0]
        val canPlay = myTurn && selected.isNotEmpty()
        val canPass = myTurn && game.table != null
        drawButton(
            c, w / 2f - gap / 2f - bw / 2f, cy, bw, bh, "ĐÁNH",
            0xFF2E7D32.toInt(), canPlay, zone == Zone.ACTION && actionIdx == 0, h * 0.040f
        )
        drawButton(
            c, w / 2f + gap / 2f + bw / 2f, cy, bw, bh, "BỎ LƯỢT",
            0xFFC62828.toInt(), canPass, zone == Zone.ACTION && actionIdx == 1, h * 0.040f
        )
    }

    private fun drawOpponent(c: Canvas, p: Int, w: Float, h: Float) {
        val n = game.hands[p].size
        val bw = h * 0.085f
        val bh = bw * 1.4f
        val st = h * 0.026f
        val horizontal = p == 2
        val len = (n - 1).coerceAtLeast(0) * st + if (horizontal) bw else bh

        val x0: Float
        val y0: Float
        when (p) {
            1 -> { x0 = w - h * 0.04f - bw; y0 = h * 0.22f }
            2 -> { x0 = (w - len) / 2f; y0 = h * 0.02f }
            else -> { x0 = h * 0.04f; y0 = h * 0.22f }
        }
        for (i in 0 until n) {
            if (horizontal) renderer.drawBack(c, x0 + i * st, y0, bw, bh)
            else renderer.drawBack(c, x0, y0 + i * st, bw, bh)
        }

        val place = game.place(p)
        val label = if (place > 0) "${names[p]} - ${rankNames[place - 1]}" else "${names[p]}  $n lá"
        val active = game.current == p && !game.over && !game.out[p]
        val size = h * 0.032f
        val lx: Float
        val ly: Float
        val align: Paint.Align
        when (p) {
            1 -> { lx = w - h * 0.03f; ly = h * 0.19f; align = Paint.Align.RIGHT }
            2 -> { lx = w / 2f; ly = y0 + bh + size * 1.6f; align = Paint.Align.CENTER }
            else -> { lx = h * 0.03f; ly = h * 0.19f; align = Paint.Align.LEFT }
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
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = when {
            active -> 0xFFFFD54F.toInt()
            place > 0 -> 0xFF2E7D32.toInt()
            else -> 0x66000000
        }
        c.drawRoundRect(rect, size * 0.5f, size * 0.5f, boxPaint)
        text(c, label, lx, ly, size, if (active) 0xFF3E2723.toInt() else 0xFFFFFFFF.toInt(), align)

        if (game.passed[p] && place == 0) {
            text(c, "Bỏ lượt", lx, ly + size * 1.4f, size * 0.9f, 0xFFFFAB91.toInt(), align)
        }
    }

    /** Hiện đủ mọi lần đánh trong vòng, xếp nối nhau (đè lên nhau nhưng vẫn thấy số + chất), kèm tên người đánh. */
    private fun drawPile(c: Canvas, w: Float, h: Float) {
        var plays = game.history.toList()
        if (plays.isEmpty()) return
        val maxSpan = w * 0.74f
        val gapF = 0.60f
        var cw: Float
        var s: Float
        while (true) {
            val n = plays.sumOf { it.combo.size }
            val g = plays.size
            cw = h * 0.13f
            s = 0.55f
            if (cw * (1 + (n - 1) * s + (g - 1) * gapF) > maxSpan) {
                s = (maxSpan / cw - 1 - (g - 1) * gapF) / maxOf(1, n - 1)
                if (s < 0.40f) {
                    s = 0.40f
                    cw = maxSpan / (1 + (n - 1) * s + (g - 1) * gapF)
                }
            }
            if (cw >= h * 0.075f || plays.size <= 1) break
            plays = plays.drop(1) // quá chật: bỏ bớt các lần đánh cũ nhất
        }
        val n = plays.sumOf { it.combo.size }
        val g = plays.size
        val ch = cw * 1.4f
        val total = cw * (1 + (n - 1) * s + (g - 1) * gapF)
        var x = (w - total) / 2f
        val y0 = h * 0.235f
        val roundOver = game.table == null

        for (gi in plays.indices) {
            val pl = plays[gi]
            val latest = gi == plays.size - 1
            val gx0 = x
            for (card in pl.combo.cards) {
                renderer.drawCard(c, card, x, y0, cw, ch)
                if (!latest || roundOver) {
                    boxPaint.style = Paint.Style.FILL
                    boxPaint.color = 0x30000000 // chỉ phủ nhẹ để vẫn đọc rõ số + chất
                    rect.set(x, y0, x + cw, y0 + ch)
                    c.drawRoundRect(rect, cw * 0.08f, cw * 0.08f, boxPaint)
                }
                x += cw * s
            }
            val gx1 = x - cw * s + cw
            val hot = latest && !roundOver
            if (hot) {
                // viền vàng quanh bộ vừa đánh (mới nhất) để phân biệt với các bộ trước
                boxPaint.style = Paint.Style.STROKE
                boxPaint.strokeWidth = h * 0.005f
                boxPaint.color = 0xFFFFD54F.toInt()
                rect.set(gx0 - cw * 0.04f, y0 - cw * 0.04f, gx1 + cw * 0.04f, y0 + ch + cw * 0.04f)
                c.drawRoundRect(rect, cw * 0.1f, cw * 0.1f, boxPaint)
                boxPaint.style = Paint.Style.FILL
            }
            // phần nhìn thấy được của nhóm: tới chỗ nhóm kế tiếp bắt đầu (nhóm cuối: tới hết lá cuối)
            val visible = if (latest) gx1 - gx0 else x + cw * gapF - gx0
            val lx = gx0 + visible / 2f
            val maxW = visible - cw * 0.05f
            text(c, names[pl.player], lx, y0 + ch + h * 0.034f, h * 0.026f,
                if (hot) 0xFFFFD54F.toInt() else 0xB3FFFFFF.toInt(), Paint.Align.CENTER, maxW)
            text(c, pl.combo.label(), lx, y0 + ch + h * 0.062f, h * 0.022f,
                if (hot) 0xFFFFF59D.toInt() else 0x80FFFFFF.toInt(), Paint.Align.CENTER, maxW)
            x += cw * gapF
        }
    }

    private fun drawStatus(c: Canvas, w: Float, h: Float) {
        val fm = flashMsg
        if (fm != null) {
            val size = h * 0.038f
            tp.textSize = size
            val tw = minOf(tp.measureText(fm), w * 0.9f)
            val pad = size
            rect.set((w - tw) / 2f - pad, h * 0.53f - size * 1.0f, (w + tw) / 2f + pad, h * 0.53f + size * 0.45f)
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = 0xDD000000.toInt()
            c.drawRoundRect(rect, size * 0.6f, size * 0.6f, boxPaint)
            text(c, fm, w / 2f, h * 0.53f, size, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, w * 0.9f)
            return
        }
        if (game.over) return
        val t = game.table
        val msg = when {
            game.out[0] -> "Bạn đã về ${rankNames[game.place(0) - 1]} - đang xem mọi người chơi tiếp..."
            game.current == 0 && t == null ->
                if (game.mustHave3) "Lượt của bạn: đánh bộ có 3 bích" else "Lượt của bạn: đánh bài bất kỳ"
            game.current == 0 -> "Lượt của bạn: đánh ${t!!.label()} lớn hơn, hoặc bỏ lượt"
            t == null -> "${names[game.current]} dẫn vòng mới..."
            else -> "${names[game.current]} đang suy nghĩ..."
        }
        val color = if (game.current == 0 && !game.out[0]) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
        text(c, msg, w / 2f, h * 0.53f, h * 0.038f, color, Paint.Align.CENTER, w * 0.9f)
    }

    private fun drawHand(c: Canvas, w: Float, h: Float) {
        val hand = game.hands[0]
        val n = hand.size
        val place = game.place(0)
        val label = if (place > 0) "Bạn - ${rankNames[place - 1]}" else "Bạn  $n lá"
        text(c, label, h * 0.03f, h * 0.70f, h * 0.030f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT)
        if (n == 0) return

        val cw = h * 0.165f
        val ch = cw * 1.4f
        val maxSpan = w * 0.80f
        val st = if (n > 1) minOf(cw * 0.62f, (maxSpan - cw) / (n - 1)) else 0f
        val total = cw + (n - 1) * st
        val x0 = (w - total) / 2f
        val baseY = h - ch - h * 0.05f
        val raise = h * 0.04f
        val handFocus = zone == Zone.HAND && !game.over

        for (i in 0 until n) {
            val card = hand[i]
            val sel = selected.contains(card)
            val cur = i == cursor
            val x = x0 + i * st
            val y = baseY - (if (sel) raise else 0f) - (if (cur) raise * 0.5f else 0f)
            renderer.drawCard(c, card, x, y, cw, ch)
            if (sel) {
                boxPaint.style = Paint.Style.FILL
                boxPaint.color = 0x3300B0FF
                rect.set(x, y, x + cw, y + ch)
                c.drawRoundRect(rect, cw * 0.08f, cw * 0.08f, boxPaint)
            }
        }
        // khung quanh lá đang trỏ (vẽ sau cùng để không bị lá bên phải che)
        val i = cursor.coerceIn(0, n - 1)
        val sel = selected.contains(hand[i])
        val x = x0 + i * st
        val y = baseY - (if (sel) raise else 0f) - raise * 0.5f
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = if (handFocus) h * 0.007f else h * 0.003f
        boxPaint.color = if (handFocus) 0xFFFFD54F.toInt() else 0x99FFFFFF.toInt()
        rect.set(x, y, x + cw, y + ch)
        c.drawRoundRect(rect, cw * 0.08f, cw * 0.08f, boxPaint)
        boxPaint.style = Paint.Style.FILL
    }

    private fun drawHints(c: Canvas, w: Float, h: Float) {
        val k = keymap
        val s = "Trái/Phải: đổi lá  |  ${k.shortName(Act.CONFIRM)}: chọn lá / bấm nút  |  Lên: tới nút ĐÁNH - BỎ LƯỢT  |  " +
            "${k.shortName(Act.CANCEL)}: bỏ chọn  |  ${k.shortName(Act.HINT)}: gợi ý  |  ${k.shortName(Act.SETTINGS)}: cài đặt phím"
        text(c, s, w / 2f, h * 0.985f, h * 0.024f, 0xAAFFFFFF.toInt(), Paint.Align.CENTER, w * 0.96f)
    }

    private fun drawGameOver(c: Canvas, w: Float, h: Float) {
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = 0xCC000000.toInt()
        c.drawRect(0f, 0f, w, h, boxPaint)
        val myPlace = game.place(0)
        val title = if (myPlace == 1) "BẠN VỀ NHẤT!" else "Bạn về ${rankNames[myPlace - 1]}"
        text(c, title, w / 2f, h * 0.24f, h * 0.10f,
            if (myPlace == 1) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
        for (k in 0 until 4) {
            val p = game.finished[k]
            val mine = p == 0
            val y = h * (0.37f + 0.09f * k)
            text(c, "${rankNames[k]}:", w * 0.36f, y, h * 0.056f,
                if (mine) 0xFFFFD54F.toInt() else 0xFF80DEEA.toInt(), Paint.Align.LEFT)
            text(c, names[p], w * 0.50f, y, h * 0.056f,
                if (mine) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt(), Paint.Align.LEFT)
        }
        text(c, "Bấm ${keymap.shortName(Act.CONFIRM)} để chơi ván mới   |   ${keymap.shortName(Act.SETTINGS)}: cài đặt phím",
            w / 2f, h * 0.84f, h * 0.040f, 0xFF80DEEA.toInt(), Paint.Align.CENTER, w * 0.9f)
    }

    private fun drawSettings(c: Canvas, w: Float, h: Float) {
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = 0x99000000.toInt()
        c.drawRect(0f, 0f, w, h, boxPaint)
        text(c, "CÀI ĐẶT PHÍM TAY CẦM", w / 2f, h * 0.10f, h * 0.06f, 0xFFFFD54F.toInt(), Paint.Align.CENTER)
        text(c, "Hướng (D-pad / cần trái) luôn dùng để di chuyển và không đổi được. Các nút bên dưới gán lại được.",
            w / 2f, h * 0.155f, h * 0.026f, 0xCCFFFFFF.toInt(), Paint.Align.CENTER, w * 0.92f)

        val acts = Act.values()
        val rows = acts.size + 2
        val rh = h * 0.075f
        val top = h * 0.19f
        val left = w * 0.10f
        val right = w * 0.90f
        for (i in 0 until rows) {
            val y = top + i * rh
            val sel = i == settingsIdx
            rect.set(left, y, right, y + rh * 0.9f)
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = if (sel) 0xFFFFD54F.toInt() else 0x44000000
            c.drawRoundRect(rect, rh * 0.25f, rh * 0.25f, boxPaint)
            val fg = if (sel) 0xFF3E2723.toInt() else 0xFFFFFFFF.toInt()
            val ty = y + rh * 0.62f
            val size = h * 0.034f
            if (i < acts.size) {
                text(c, acts[i].label, left + rh * 0.3f, ty, size, fg, Paint.Align.LEFT, (right - left) * 0.50f)
                val v = if (sel && capturing) "Bấm một nút trên tay cầm..." else keymap.keyName(acts[i])
                text(c, v, right - rh * 0.3f, ty, size,
                    if (sel && capturing) 0xFFD84315.toInt() else if (sel) 0xFF1B5E20.toInt() else 0xFF80DEEA.toInt(),
                    Paint.Align.RIGHT, (right - left) * 0.46f)
            } else if (i == acts.size) {
                text(c, "Khôi phục phím mặc định", w / 2f, ty, size, fg, Paint.Align.CENTER)
            } else {
                text(c, "Đóng và quay lại game", w / 2f, ty, size, fg, Paint.Align.CENTER)
            }
        }
        val by = top + rows * rh + h * 0.035f
        if (settingsMsg.isNotEmpty()) {
            text(c, settingsMsg, w / 2f, by, h * 0.032f, 0xFFFFF59D.toInt(), Paint.Align.CENTER, w * 0.92f)
        }
        text(c, lastInput, w / 2f, by + h * 0.05f, h * 0.034f, 0xFF80DEEA.toInt(), Paint.Align.CENTER, w * 0.92f)
        val usb = usbStatus()
        if (usb.isNotEmpty()) {
            text(c, "Tay cầm USB: $usb", w / 2f, h * 0.955f, h * 0.024f, 0xAAFFFFFF.toInt(), Paint.Align.CENTER, w * 0.92f)
        }
        text(c, "Lên/Xuống: chọn dòng  |  ${keymap.shortName(Act.CONFIRM)} (hoặc OK): đổi phím  |  ${keymap.shortName(Act.CANCEL)} / Back: đóng",
            w / 2f, h * 0.985f, h * 0.024f, 0xAAFFFFFF.toInt(), Paint.Align.CENTER, w * 0.96f)
    }
}
