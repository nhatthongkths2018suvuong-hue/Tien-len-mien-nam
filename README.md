# Tiến Lên Miền Nam – Android TV box (FPT Play Box 2018)

Game bài Tiến lên miền Nam 4 người (bạn + 3 đối thủ tên Việt Nam ngẫu nhiên), chơi bằng gamepad.
Kotlin thuần, không dùng engine hay thư viện ngoài; lá bài vẽ bằng code. Android 6.0.1 (`minSdk 23`), không cần root.

## Build APK
Đẩy toàn bộ lên GitHub (nhánh `main`/`master`) → **Actions → Build APK** → tải artifact `TienLen-debug-apk` (có `app-debug.apk`).
Workflow dùng lại cách build của Mini Game Box (cài Gradle 8.9 + SDK 34, không cần `gradlew`).

## Tay cầm USB (DragonRise 0079:0006)
Giống Mini Game Box: nếu Android không tự nhận tay cầm, game đọc thẳng qua USB (`UsbPad.java`, giữ nguyên bản của Mini Game Box).
- Lần đầu cắm, box hỏi quyền USB → bấm OK.
- Quy ước nút USB (bố cục PS2): 1 = Y (tam giác), 2 = B (tròn), 3 = A (chéo), 4 = X (vuông), 5/6 = L1/R1, 7/8 = L2/R2, 9 = Select, 10 = Start.
- Tay cầm Android nhận sẵn (hoặc remote) cũng dùng được.

## Điều khiển (mặc định)
| Nút | Chức năng |
|---|---|
| Hướng (D-pad / HAT / cần trái) | Trái/Phải: đổi lá. **Lên**: sang hàng nút ĐÁNH / BỎ LƯỢT (Lên nữa: nút Cài đặt). **Xuống**: về bộ bài |
| A (hoặc OK) | Ở bộ bài: chọn / bỏ chọn lá. Ở hàng nút: bấm **ĐÁNH** hoặc **BỎ LƯỢT** |
| B | Bỏ chọn tất cả |
| Y | Gợi ý nước đi (chỉ chọn sẵn lá, vẫn phải bấm ĐÁNH) |
| Start | Mở Cài đặt phím |
| L1 / R1 | Nhảy 3 lá sang trái / phải |

Đánh bài và bỏ lượt **chỉ** làm được bằng 2 nút trên màn hình (nằm cạnh nhau, ngay trên bộ bài), không có phím tắt nên không bấm nhầm.

## Cài đặt phím
Nút **CÀI ĐẶT PHÍM** (góc phải trên) hoặc phím Start: chọn một chức năng → bấm nút trên tay cầm muốn gán (hết 8 giây tự hủy).
Hai chức năng trùng phím sẽ đổi chỗ nhau. Phím A/OK luôn xác nhận được trong màn này nên không bao giờ bị khóa; có nút "Khôi phục phím mặc định".
Màn này còn hiện dòng "Vừa nhận: …" và trạng thái đọc USB để kiểm tra tay cầm. Hướng luôn cố định.

## Luật đã cài
- Thứ tự: 3 < 4 < … < 10 < J < Q < K < A < 2; chất: bích < chuồn < rô < cơ.
- Bộ hợp lệ: lẻ, đôi, sám cô, tứ quý, sảnh (≥3 lá, không có 2), đôi thông (≥3 đôi, không có 2).
- Cùng loại, cùng số lá, lá cao nhất lớn hơn. Chặt: tứ quý / 3 đôi thông chặt heo lẻ; tứ quý / 4 đôi thông chặt đôi heo; 4 đôi thông chặt tứ quý; tứ quý / 4 đôi thông chặt 3 đôi thông.
- **Xếp hạng**: ai hết bài trước về Nhất; những người còn lại chơi tiếp để phân Nhì, Ba, Bét. Ván kết thúc khi chỉ còn một người còn bài.
- Người đã hết bài bị bỏ qua khi chuyển lượt. Nếu người về hạng vừa đánh bộ cuối mà mọi người đều bỏ, người kế tiếp còn bài dẫn vòng mới.
- Ván đầu: ai cầm 3 bích đi trước và phải đánh kèm 3 bích. Các ván sau: người về Nhất đi trước.
- Bỏ lượt là bị khóa tới hết vòng; mọi người bỏ thì người đánh sau cùng dẫn vòng mới.
- Màn hình hiện **đủ mọi bộ đã đánh trong vòng**, xếp nối nhau, kèm tên người đánh và loại bộ; bộ mới nhất có viền vàng.
- Nếu bạn không có bài lớn hơn, game tự bỏ lượt.

Chưa có: tới trắng, tính tiền thối heo, chặt chồng.

## Cấu trúc
```
app/src/main/java/vn/tienlen/game/
  Cards.kt          mã hóa lá bài
  Combo.kt          nhận diện bộ bài + luật chặt
  Game.kt           ván chơi: lượt, bỏ lượt, xếp hạng, lịch sử vòng
  Bot.kt            AI đối thủ
  Names.kt          tên Việt Nam ngẫu nhiên
  CardRenderer.kt   vẽ lá bài bằng Canvas
  GameView.kt       giao diện + điều khiển + màn Cài đặt
  KeyMap.kt         bảng gán phím (lưu SharedPreferences) + quy ước nút USB
  UsbController.kt  quét / xin quyền / đọc tay cầm USB
  UsbPad.java       đọc HID trực tiếp (từ Mini Game Box)
  MainActivity.kt   toàn màn hình, nhận phím
```
