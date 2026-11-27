package vn.tienlen.game

import java.util.Collections
import java.util.Random

/** Một lần đánh trong vòng: ai đánh bộ nào. */
class Play(val player: Int, val combo: Combo)

/**
 * Trạng thái một ván Tiến lên miền Nam 4 người. Người chơi 0 là bạn,
 * 1 = bên phải, 2 = đối diện, 3 = bên trái (đánh ngược chiều kim đồng hồ).
 *
 * Ai hết bài trước về nhất; những người còn lại chơi tiếp để xếp nhì, ba, bét.
 * Ván kết thúc khi chỉ còn một người còn bài (người đó về bét).
 */
class Game(private val rng: Random = Random()) {
    val hands: Array<MutableList<Int>> = Array(4) { mutableListOf<Int>() }
    /** Đã bỏ lượt trong vòng hiện tại (bị khóa tới hết vòng). */
    val passed = BooleanArray(4)
    /** Đã hết bài / đã có hạng. */
    val out = BooleanArray(4)
    /** Thứ tự về đích của ván này: phần tử 0 = nhất, 1 = nhì, ... */
    val finished = ArrayList<Int>()
    /** Các bộ đã đánh trong vòng gần nhất (cũ -> mới). Giữ lại sau khi vòng kết thúc cho tới khi có bộ mới. */
    val history = ArrayList<Play>()
    /** Số ván về nhất của mỗi người. */
    val wins = IntArray(4)
    /** Điểm cộng dồn: nhất 3, nhì 2, ba 1, bét 0. */
    val points = IntArray(4)

    var current = 0
        private set
    var table: Combo? = null
        private set
    var lastPlayer = -1
        private set
    var over = false
        private set
    var mustHave3 = false
        private set
    /** true nếu nước đi vừa rồi là chặt (khác loại bộ đang nằm trên bàn). */
    var lastChop = false
        private set

    private var lastWinner = -1

    /** Hạng của người chơi (1..4), 0 nếu chưa về đích. */
    fun place(p: Int): Int = finished.indexOf(p) + 1

    fun deal() {
        val deck = (0 until 52).toMutableList()
        Collections.shuffle(deck, rng)
        for (p in 0 until 4) {
            hands[p].clear()
            hands[p].addAll(deck.subList(p * 13, p * 13 + 13))
            hands[p].sort()
        }
        passed.fill(false)
        out.fill(false)
        finished.clear()
        history.clear()
        table = null
        lastPlayer = -1
        over = false
        lastChop = false
        if (lastWinner < 0) {
            // ván đầu: ai cầm 3 bích đi trước và phải đánh kèm lá đó
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
        if (over || p != current || out[p]) return false
        if (!ids.all { hands[p].contains(it) }) return false
        val combo = Combo.of(ids) ?: return false
        if (!isLegal(combo)) return false

        val t = table
        lastChop = t != null && !(combo.type == t.type && combo.size == t.size)
        if (t == null) history.clear() // bộ đầu tiên của vòng mới: xóa vòng cũ
        hands[p].removeAll(combo.cards.toSet())
        table = combo
        lastPlayer = p
        mustHave3 = false
        history.add(Play(p, combo))

        if (hands[p].isEmpty()) {
            out[p] = true
            finished.add(p)
            if (finished.size == 1) {
                lastWinner = p
                wins[p]++
            }
            val left = (0..3).filter { !out[it] }
            if (left.size <= 1) {
                // chỉ còn một người: người đó về bét, hết ván
                for (q in left) { out[q] = true; finished.add(q) }
                over = true
                val award = intArrayOf(3, 2, 1, 0)
                for (k in finished.indices) points[finished[k]] += award[k]
                return true
            }
        }
        advance(p)
        return true
    }

    fun pass(p: Int): Boolean {
        if (over || p != current || out[p] || table == null) return false
        passed[p] = true
        advance(p)
        return true
    }

    /** Chuyển lượt cho người kế tiếp còn quyền đánh; không ai còn quyền thì mở vòng mới. */
    private fun advance(from: Int) {
        val responders = (0..3).filter { it != lastPlayer && !out[it] && !passed[it] }
        if (responders.isEmpty()) {
            newRound()
            return
        }
        var i = (from + 1) % 4
        while (i !in responders) i = (i + 1) % 4
        current = i
    }

    /** Vòng mới: người đánh sau cùng dẫn; nếu họ đã hết bài thì người kế tiếp còn bài dẫn. */
    private fun newRound() {
        table = null
        passed.fill(false)
        lastChop = false
        var i = lastPlayer
        while (out[i]) i = (i + 1) % 4
        current = i
    }
}
