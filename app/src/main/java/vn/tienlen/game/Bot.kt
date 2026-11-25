package vn.tienlen.game

/** AI đơn giản: đánh bộ nhỏ nhất đủ sức, hạn chế phá đôi/sám, để dành heo và hàng chặt. */
object Bot {

    /** Liệt kê các bộ có thể tạo từ bài trên tay (đã giới hạn số lựa chọn cho sảnh / đôi thông). */
    fun enumerate(hand: List<Int>): List<Combo> {
        val h = hand.sorted()
        val byRank = Array(13) { mutableListOf<Int>() }
        for (c in h) byRank[Cards.rank(c)].add(c)
        val out = ArrayList<Combo>()

        for (c in h) Combo.of(listOf(c))?.let { out.add(it) }

        for (r in 0..12) {
            val g = byRank[r]
            for (i in g.indices) for (j in i + 1 until g.size) {
                Combo.of(listOf(g[i], g[j]))?.let { out.add(it) }
                for (k in j + 1 until g.size) {
                    Combo.of(listOf(g[i], g[j], g[k]))?.let { out.add(it) }
                }
            }
            if (g.size == 4) Combo.of(g)?.let { out.add(it) }
        }

        // sảnh: các lá giữa lấy chất nhỏ nhất, lá cuối thử mọi chất
        for (start in 0..9) {
            if (byRank[start].isEmpty()) continue
            var top = start + 2
            while (top <= 11) {
                var ok = true
                for (r in start..top) if (byRank[r].isEmpty()) { ok = false; break }
                if (!ok) break
                val base = (start until top).map { byRank[it][0] }
                for (t in byRank[top]) Combo.of(base + t)?.let { out.add(it) }
                top++
            }
        }

        // đôi thông: tương tự, cặp cuối thử mọi cặp
        for (start in 0..9) {
            if (byRank[start].size < 2) continue
            var top = start + 2
            while (top <= 11) {
                var ok = true
                for (r in start..top) if (byRank[r].size < 2) { ok = false; break }
                if (!ok) break
                val base = ArrayList<Int>()
                for (r in start until top) { base.add(byRank[r][0]); base.add(byRank[r][1]) }
                val g = byRank[top]
                for (i in g.indices) for (j in i + 1 until g.size) {
                    Combo.of(base + g[i] + g[j])?.let { out.add(it) }
                }
                top++
            }
        }
        return out
    }

    /** Tất cả bộ trên tay đánh được lên bộ [table]. */
    fun beating(hand: List<Int>, table: Combo): List<Combo> =
        enumerate(hand).filter { it.beats(table) }

    /** Chọn nước đi cho người chơi [p]; null nghĩa là bỏ lượt. */
    fun choose(g: Game, p: Int): Combo? {
        val hand = g.hands[p].sorted()
        val legal = enumerate(hand).filter { g.isLegal(it) }
        if (legal.isEmpty()) return null
        // đánh hết bài được thì đánh luôn
        legal.firstOrNull { it.size == hand.size }?.let { return it }

        val counts = IntArray(13)
        for (c in hand) counts[Cards.rank(c)]++
        val t = g.table ?: return lead(g, legal, hand)
        return follow(g, p, legal, t, counts)
    }

    private fun lead(g: Game, legal: List<Combo>, hand: List<Int>): Combo {
        if (g.mustHave3) {
            return legal.sortedWith(compareByDescending<Combo> { it.size }.thenBy { it.high }).first()
        }
        val low = hand[0]
        val plain = legal.filter {
            it.cards.contains(low) && it.type != Type.QUAD && it.type != Type.DOUBLE_SEQ
        }
        val pool = if (plain.isNotEmpty()) plain else legal
        return pool.sortedWith(compareByDescending<Combo> { it.size }.thenBy { it.high }).first()
    }

    private fun follow(g: Game, p: Int, legal: List<Combo>, t: Combo, counts: IntArray): Combo? {
        val normal = legal.filter { it.type == t.type && it.size == t.size }
        val chops = legal.filter { !(it.type == t.type && it.size == t.size) }
        val minOpp = (0..3).filter { it != p }.minOf { g.hands[it].size }
        val danger = minOpp <= 3
        val myCount = g.hands[p].size

        if (normal.isNotEmpty()) {
            val sorted = normal.sortedBy { it.high + penalty(it, counts) }
            val noTwo = sorted.firstOrNull { !it.hasTwo }
            if (noTwo != null) return noTwo
            val best = sorted.first()
            // chỉ tung heo khi cần thiết
            return if (danger || myCount <= 6 || t.rankHigh >= 10) best else null
        }
        if (chops.isNotEmpty()) {
            val worthChop = (t.type == Type.SINGLE || t.type == Type.PAIR) && t.rankHigh == Cards.TWO_RANK ||
                t.type == Type.QUAD || t.type == Type.DOUBLE_SEQ || danger
            if (worthChop) {
                return chops.sortedWith(compareBy<Combo> { it.size }.thenBy { it.high }).first()
            }
        }
        return null
    }

    /** Phạt nếu phải phá đôi / sám / tứ quý để đánh bộ này. */
    private fun penalty(c: Combo, counts: IntArray): Int {
        val r = Cards.rank(c.cards[0])
        return when (c.type) {
            Type.SINGLE -> if (counts[r] >= 2) 60 else 0
            Type.PAIR -> if (counts[r] >= 3) 60 else 0
            Type.TRIPLE -> if (counts[r] >= 4) 60 else 0
            else -> 0
        }
    }
}
