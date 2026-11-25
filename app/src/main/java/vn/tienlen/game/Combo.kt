package vn.tienlen.game

enum class Type { SINGLE, PAIR, TRIPLE, QUAD, STRAIGHT, DOUBLE_SEQ }

/** Một bộ bài hợp lệ (lẻ, đôi, sám, tứ quý, sảnh, đôi thông). `cards` luôn được sắp tăng dần. */
class Combo private constructor(val type: Type, val cards: List<Int>) {

    val size: Int get() = cards.size
    val high: Int get() = cards[cards.size - 1]
    val rankHigh: Int get() = Cards.rank(high)
    val pairs: Int get() = cards.size / 2
    val hasTwo: Boolean get() = cards.any { Cards.rank(it) == Cards.TWO_RANK }

    /** true nếu bộ này đánh được (hoặc chặt được) bộ [p] đang nằm trên bàn. */
    fun beats(p: Combo): Boolean {
        if (type == p.type && size == p.size) return high > p.high
        return when {
            // chặt heo lẻ: tứ quý, 3 đôi thông trở lên
            p.type == Type.SINGLE && p.rankHigh == Cards.TWO_RANK ->
                type == Type.QUAD || (type == Type.DOUBLE_SEQ && pairs >= 3)
            // chặt đôi heo: tứ quý, 4 đôi thông trở lên
            p.type == Type.PAIR && p.rankHigh == Cards.TWO_RANK ->
                type == Type.QUAD || (type == Type.DOUBLE_SEQ && pairs >= 4)
            // tứ quý bị chặt bởi 4 đôi thông
            p.type == Type.QUAD ->
                type == Type.DOUBLE_SEQ && pairs >= 4
            // 3 đôi thông bị chặt bởi tứ quý hoặc 4 đôi thông
            p.type == Type.DOUBLE_SEQ && p.pairs == 3 ->
                type == Type.QUAD || (type == Type.DOUBLE_SEQ && pairs >= 4)
            else -> false
        }
    }

    fun label(): String = when (type) {
        Type.SINGLE -> "lẻ"
        Type.PAIR -> "đôi"
        Type.TRIPLE -> "sám cô"
        Type.QUAD -> "tứ quý"
        Type.STRAIGHT -> "sảnh $size"
        Type.DOUBLE_SEQ -> "$pairs đôi thông"
    }

    companion object {
        /** Nhận diện bộ bài từ danh sách lá; trả về null nếu không hợp lệ. */
        fun of(ids: List<Int>): Combo? {
            if (ids.isEmpty()) return null
            val c = ids.sorted()
            if (c.toSet().size != c.size) return null
            val n = c.size
            val r = c.map { Cards.rank(it) }

            if (r.all { it == r[0] }) {
                return when (n) {
                    1 -> Combo(Type.SINGLE, c)
                    2 -> Combo(Type.PAIR, c)
                    3 -> Combo(Type.TRIPLE, c)
                    4 -> Combo(Type.QUAD, c)
                    else -> null
                }
            }
            // heo (rank 12) không được nằm trong sảnh / đôi thông
            if (r[n - 1] >= Cards.TWO_RANK) return null

            if (n >= 3) {
                var ok = true
                for (i in 1 until n) if (r[i] != r[i - 1] + 1) { ok = false; break }
                if (ok) return Combo(Type.STRAIGHT, c)
            }
            if (n >= 6 && n % 2 == 0) {
                var ok = true
                var i = 0
                while (i < n) {
                    if (r[i] != r[i + 1]) { ok = false; break }
                    if (i > 0 && r[i] != r[i - 2] + 1) { ok = false; break }
                    i += 2
                }
                if (ok) return Combo(Type.DOUBLE_SEQ, c)
            }
            return null
        }
    }
}
