package vn.tienlen.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** Vẽ lá bài hoàn toàn bằng code (không cần file ảnh). */
class CardRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private val rect = RectF()

    private val red = 0xFFD32F2F.toInt()
    private val black = 0xFF212121.toInt()

    fun drawCard(canvas: Canvas, card: Int, x: Float, y: Float, w: Float, h: Float) {
        val r = w * 0.08f
        rect.set(x, y, x + w, y + h)
        fill.style = Paint.Style.FILL
        fill.color = 0xFFFDFDFB.toInt()
        canvas.drawRoundRect(rect, r, r, fill)
        stroke.color = 0xFF9E9E9E.toInt()
        stroke.strokeWidth = maxOf(1f, w * 0.012f)
        canvas.drawRoundRect(rect, r, r, stroke)

        val color = if (Cards.isRed(card)) red else black
        val suit = Cards.suit(card)

        // chỉ số góc trên-trái
        drawIndex(canvas, card, suit, color, x, y, w)
        // chỉ số góc dưới-phải (xoay 180°)
        canvas.save()
        canvas.rotate(180f, x + w / 2f, y + h / 2f)
        drawIndex(canvas, card, suit, color, x, y, w)
        canvas.restore()

        // hình chất lớn ở giữa
        drawSuit(canvas, suit, x + w / 2f, y + h * 0.52f, w * 0.46f, color)
    }

    private fun drawIndex(canvas: Canvas, card: Int, suit: Int, color: Int, x: Float, y: Float, w: Float) {
        text.color = color
        text.textAlign = Paint.Align.CENTER
        val name = Cards.rankName(card)
        text.textSize = if (name.length > 1) w * 0.24f else w * 0.28f
        canvas.drawText(name, x + w * 0.19f, y + w * 0.30f, text)
        drawSuit(canvas, suit, x + w * 0.19f, y + w * 0.47f, w * 0.17f, color)
    }

    fun drawBack(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = w * 0.09f
        rect.set(x, y, x + w, y + h)
        fill.style = Paint.Style.FILL
        fill.color = 0xFFF5F5F5.toInt()
        canvas.drawRoundRect(rect, r, r, fill)
        val m = w * 0.07f
        rect.set(x + m, y + m, x + w - m, y + h - m)
        fill.color = 0xFF1565C0.toInt()
        canvas.drawRoundRect(rect, r * 0.7f, r * 0.7f, fill)
        stroke.color = 0xFF90CAF9.toInt()
        stroke.strokeWidth = maxOf(1f, w * 0.03f)
        rect.set(x + m * 2.2f, y + m * 2.2f, x + w - m * 2.2f, y + h - m * 2.2f)
        canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, stroke)
        // hình thoi trang trí ở giữa
        fill.color = 0xFF90CAF9.toInt()
        drawSuit(canvas, Cards.DIAMOND, x + w / 2f, y + h / 2f, w * 0.34f, 0xFF90CAF9.toInt())
    }

    /** Vẽ một chất; [size] là chiều cao hình, tâm tại (cx, cy). */
    fun drawSuit(canvas: Canvas, suit: Int, cx: Float, cy: Float, size: Float, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = color
        val u = size / 2f
        when (suit) {
            Cards.HEART -> {
                heart(cx, cy, u, 1f)
                canvas.drawPath(path, fill)
            }
            Cards.SPADE -> {
                heart(cx, cy - 0.1f * u, u * 0.95f, -1f)
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(cx - 0.32f * u, cy + 0.98f * u)
                path.lineTo(cx + 0.32f * u, cy + 0.98f * u)
                path.lineTo(cx, cy + 0.25f * u)
                path.close()
                canvas.drawPath(path, fill)
            }
            Cards.DIAMOND -> {
                path.reset()
                path.moveTo(cx, cy - u)
                path.lineTo(cx + 0.72f * u, cy)
                path.lineTo(cx, cy + u)
                path.lineTo(cx - 0.72f * u, cy)
                path.close()
                canvas.drawPath(path, fill)
            }
            else -> { // CLUB
                val rr = 0.46f * u
                canvas.drawCircle(cx, cy - 0.5f * u, rr, fill)
                canvas.drawCircle(cx - 0.55f * u, cy + 0.25f * u, rr, fill)
                canvas.drawCircle(cx + 0.55f * u, cy + 0.25f * u, rr, fill)
                path.reset()
                path.moveTo(cx - 0.3f * u, cy + 0.98f * u)
                path.lineTo(cx + 0.3f * u, cy + 0.98f * u)
                path.lineTo(cx, cy - 0.05f * u)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawCircle(cx, cy + 0.15f * u, 0.3f * u, fill)
            }
        }
    }

    /** Hình trái tim; [f] = -1 để lật ngược (làm lá bích). */
    private fun heart(cx: Float, cy: Float, u: Float, f: Float) {
        path.reset()
        path.moveTo(cx, cy + f * 0.95f * u)
        path.cubicTo(cx - 1.35f * u, cy + f * 0.25f * u, cx - 1.15f * u, cy - f * 0.95f * u, cx - 0.5f * u, cy - f * 0.95f * u)
        path.cubicTo(cx - 0.2f * u, cy - f * 0.95f * u, cx, cy - f * 0.7f * u, cx, cy - f * 0.45f * u)
        path.cubicTo(cx, cy - f * 0.7f * u, cx + 0.2f * u, cy - f * 0.95f * u, cx + 0.5f * u, cy - f * 0.95f * u)
        path.cubicTo(cx + 1.15f * u, cy - f * 0.95f * u, cx + 1.35f * u, cy + f * 0.25f * u, cx, cy + f * 0.95f * u)
        path.close()
    }
}
