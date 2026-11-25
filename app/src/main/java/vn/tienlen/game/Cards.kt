package vn.tienlen.game

/**
 * Lá bài được mã hóa thành số nguyên 0..51:  id = rank * 4 + suit
 *  - rank: 0 = 3, 1 = 4, ... 7 = 10, 8 = J, 9 = Q, 10 = K, 11 = A, 12 = 2 (heo, lớn nhất)
 *  - suit: 0 = bích, 1 = chuồn (tép), 2 = rô, 3 = cơ  (tăng dần theo độ lớn)
 * So sánh hai lá chỉ cần so sánh id.
 */
object Cards {
    val RANK_NAMES = arrayOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2")
    val SUIT_NAMES = arrayOf("bích", "chuồn", "rô", "cơ")

    const val SPADE = 0
    const val CLUB = 1
    const val DIAMOND = 2
    const val HEART = 3

    const val TWO_RANK = 12

    fun rank(c: Int): Int = c shr 2
    fun suit(c: Int): Int = c and 3
    fun isRed(c: Int): Boolean = suit(c) >= DIAMOND
    fun rankName(c: Int): String = RANK_NAMES[rank(c)]
    fun name(c: Int): String = rankName(c) + " " + SUIT_NAMES[suit(c)]
}
