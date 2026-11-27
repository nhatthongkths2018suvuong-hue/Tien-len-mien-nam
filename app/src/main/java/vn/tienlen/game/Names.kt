package vn.tienlen.game

import java.util.Collections
import java.util.Random

/** Tên Việt Nam cho 3 đối thủ, lấy ngẫu nhiên mỗi lần mở game. */
object Names {
    private val POOL = listOf(
        "Anh Hùng", "Chị Lan", "Bác Tư", "Cô Mai", "Chú Ba", "Dì Hạnh",
        "Cậu Tuấn", "Mợ Hoa", "Ông Sáu", "Bà Năm", "Anh Khoa", "Chị Thủy",
        "Chú Dũng", "Cô Thảo", "Anh Long", "Chị Ngọc", "Bác Hải", "Cô Liên",
        "Chú Phúc", "Anh Quân", "Chị Trang", "Bác Cường", "Cô Huệ", "Chú Tâm",
        "Anh Bảo", "Chị Vân", "Ông Bảy", "Bà Tám", "Anh Phát", "Chị Yến"
    )

    fun pick(rng: Random, n: Int): List<String> {
        val l = POOL.toMutableList()
        Collections.shuffle(l, rng)
        return l.subList(0, n)
    }
}
