package vn.tienlen.game

import android.content.Context
import android.view.KeyEvent

/** Các chức năng có thể gán lại cho nút bấm trên tay cầm. Hướng (D-pad / HAT / cần trái) luôn cố định. */
enum class Act(val label: String, val default: Int) {
    CONFIRM("Chọn lá / bấm nút trên màn hình", KeyEvent.KEYCODE_BUTTON_A),
    CANCEL("Bỏ chọn tất cả / quay lại", KeyEvent.KEYCODE_BUTTON_B),
    HINT("Gợi ý nước đi", KeyEvent.KEYCODE_BUTTON_Y),
    SETTINGS("Mở Cài đặt phím", KeyEvent.KEYCODE_BUTTON_START),
    JUMP_L("Nhảy sang trái 3 lá", KeyEvent.KEYCODE_BUTTON_L1),
    JUMP_R("Nhảy sang phải 3 lá", KeyEvent.KEYCODE_BUTTON_R1)
}

/**
 * Bảng gán phím, lưu trong SharedPreferences. Phím có thể là mã KeyEvent của Android
 * hoặc "phím ảo" (VK_BASE + chỉ số) cho các trục tay cầm (Z, RZ, RX, RY, trigger).
 * Gán một phím đang dùng cho chức năng khác thì hai chức năng đổi chỗ nhau nên không bao giờ mất phím.
 */
class KeyMap(ctx: Context) {
    companion object {
        const val VK_BASE = 0x10000
        val VK_NAMES = arrayOf(
            "Z-", "Z+", "RZ-", "RZ+", "RX-", "RX+", "RY-", "RY+", "TRIGGER_L", "TRIGGER_R"
        )

        fun isVirtual(code: Int) = code >= VK_BASE && code < VK_BASE + VK_NAMES.size

        /** Số thứ tự nút (1, 2, 3...) trên tay cầm USB đọc trực tiếp ứng với mã phím, hoặc 0 nếu không phải. */
        fun usbButtonNumber(code: Int): Int {
            for (i in 0..11) if (UsbButtons.keyCode(i) == code) return i + 1
            return 0
        }
    }

    private val prefs = ctx.getSharedPreferences("tienlen_keymap", Context.MODE_PRIVATE)
    private val keys = IntArray(Act.values().size)

    init {
        for (a in Act.values()) keys[a.ordinal] = prefs.getInt("k_" + a.name, a.default)
    }

    fun actionFor(code: Int): Act? {
        for (a in Act.values()) if (keys[a.ordinal] == code) return a
        return null
    }

    fun keyOf(a: Act): Int = keys[a.ordinal]

    fun set(a: Act, code: Int) {
        val old = keys[a.ordinal]
        for (o in Act.values()) {
            if (o != a && keys[o.ordinal] == code) keys[o.ordinal] = old // đổi chỗ
        }
        keys[a.ordinal] = code
        save()
    }

    fun reset() {
        for (a in Act.values()) keys[a.ordinal] = a.default
        save()
    }

    private fun save() {
        val e = prefs.edit()
        for (a in Act.values()) e.putInt("k_" + a.name, keys[a.ordinal])
        e.apply()
    }

    /** Tên đầy đủ để hiển thị trong màn Cài đặt. */
    fun keyName(a: Act): String = describe(keys[a.ordinal])

    /** Tên ngắn (A, B, START...) dùng trong dòng hướng dẫn. */
    fun shortName(a: Act): String = rawName(keys[a.ordinal]).replace("BUTTON_", "")

    fun describe(code: Int): String {
        val base = rawName(code)
        val n = usbButtonNumber(code)
        return if (n > 0) "$base  (nút số $n trên tay cầm USB)" else base
    }

    fun rawName(code: Int): String {
        if (isVirtual(code)) return VK_NAMES[code - VK_BASE]
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
    }
}

/** Quy ước nút của tay cầm USB đọc trực tiếp (bố cục PS2), giống phần mềm Mini Game Box. */
object UsbButtons {
    fun keyCode(idx: Int): Int = when (idx) {
        0 -> KeyEvent.KEYCODE_BUTTON_Y
        1 -> KeyEvent.KEYCODE_BUTTON_B
        2 -> KeyEvent.KEYCODE_BUTTON_A
        3 -> KeyEvent.KEYCODE_BUTTON_X
        4 -> KeyEvent.KEYCODE_BUTTON_L1
        5 -> KeyEvent.KEYCODE_BUTTON_R1
        6 -> KeyEvent.KEYCODE_BUTTON_L2
        7 -> KeyEvent.KEYCODE_BUTTON_R2
        8 -> KeyEvent.KEYCODE_BUTTON_SELECT
        9 -> KeyEvent.KEYCODE_BUTTON_START
        10 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        11 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        else -> KeyEvent.KEYCODE_BUTTON_1 + minOf(idx, 15)
    }
}
