package vn.tienlen.game

import java.util.Collections
import java.util.Random

/**
 * Trạng thái một bàn Tiến lên miền Nam 4 người. Người chơi 0 là bạn,
 * 1 = bên phải, 2 = đối diện, 3 = bên trái (đánh ngược chiều kim đồng hồ).
 * Ván kết thúc khi có người hết bài đầu tiên.
 */
class Game(private val rng: Random = Random()) {
    val hands: Array<MutableList<Int>> = Array(4) { mutableListOf<Int>() }
    val passed = BooleanArray(4)
    val wins = IntArray(4)

    var current = 0
        private set
    var table: Combo? = null
        private set
    var lastPlayer = -1
        private set
    var winner = -1
        private set
    var mustHave3 = false
        private set
    /** true nếu nước đi vừa rồi là chặt (khác loại bộ đang nằm trên bàn). */
    var lastChop = false
        private set

    private var lastWinner = -1

    fun deal() {
        val deck = (0 until 52).toMutableList()
        Collections.shuffle(deck, rng)
        for (p in 0 until 4) {
            hands[p].clear()
            hands[p].addAll(deck.subList(p * 13, p * 13 + 13))
            hands[p].sort()
        }
        passed.fill(false)
        table = null
        lastPlayer = -1
        winner = -1
        lastChop = false
        if (lastWinner < 0) {
            // ván đầu: ai cầm 3 bích đi trước và phải đánh lá đó
            current = (0..3).first { hands[it].contains(0) }
            mustHave3 = true
        } else {
            current = lastWinner
            mustHave3 = false
        }
    }

    fun isLegal(combo: Combo): Boolean {
        val t = table ?: return !mustHave3 || combo.cards.contains(0)
        return combo.beats(t)
    }

    fun play(p: Int, ids: List<Int>): Boolean {
        if (winner >= 0 || p != current) return false
        if (!ids.all { hands[p].contains(it) }) return false
        val combo = Combo.of(ids) ?: return false
        if (!isLegal(combo)) return false

        val t = table
        lastChop = t != null && !(combo.type == t.type && combo.size == t.size)
        hands[p].removeAll(combo.cards.toSet())
        table = combo
        lastPlayer = p
        mustHave3 = false
        if (hands[p].isEmpty()) {
            winner = p
            wins[p]++
            lastWinner = p
            return true
        }
        current = nextActive(p)
        return true
    }

    fun pass(p: Int): Boolean {
        if (winner >= 0 || p != current || table == null) return false
        passed[p] = true
        val active = (0..3).count { !passed[it] }
        if (active == 1) {
            // mọi người đã bỏ: người đánh sau cùng được dẫn vòng mới
            table = null
            passed.fill(false)
            current = lastPlayer
            lastChop = false
        } else {
            current = nextActive(p)
        }
        return true
    }

    private fun nextActive(from: Int): Int {
        var i = (from + 1) % 4
        while (passed[i]) i = (i + 1) % 4
        return i
    }
}
