# Tiến Lên Miền Nam – Android TV box (FPT Play Box 2018)

Game bài Tiến lên miền Nam 4 người (1 bạn + 3 bot), điều khiển bằng gamepad / remote.
Kotlin thuần, **không dùng engine hay thư viện ngoài**; lá bài được vẽ bằng code nên không có file ảnh bài.

- Android 6.0.1 (`minSdk 23`), không cần root
- Có `LEANBACK_LAUNCHER` + banner nên hiện trong danh sách ứng dụng của TV box

## Build APK bằng GitHub Actions
1. Tạo repo mới, đẩy toàn bộ thư mục này lên (nhánh `main` hoặc `master`).
2. Vào tab **Actions → Build APK** (tự chạy khi push; hoặc bấm *Run workflow*).
3. Tải artifact **TienLen-debug-apk** → giải nén ra `app-debug.apk`.

Muốn build trên máy: cần JDK 17 + Android SDK + Gradle 8.9, chạy `gradle assembleDebug`.

## Cài lên FPT Play Box
- Copy `app-debug.apk` vào USB, cắm vào box, mở bằng trình quản lý file, bật *Cho phép cài từ nguồn không rõ*; hoặc
- `adb connect <IP-box>:5555` rồi `adb install app-debug.apk` (cần bật gỡ lỗi USB/ADB trong Cài đặt → Tùy chọn nhà phát triển).

## Điều khiển

| Nút | Chức năng |
|---|---|
| Trái / Phải (D-pad) | Di chuyển con trỏ giữa các lá |
| L1 / R1 | Nhảy 3 lá |
| **A** / OK | Chọn / bỏ chọn lá đang trỏ |
| **X** hoặc D-pad Lên (hoặc Start) | **Đánh** các lá đã chọn |
| **Y** hoặc D-pad Xuống | Bỏ lượt |
| B / Back | Bỏ chọn tất cả (Back 2 lần để thoát) |
| Select | Gợi ý nước đi |
| A / Start khi hết ván | Ván mới |

Nếu không có bài lớn hơn, game tự bỏ lượt cho bạn.

## Luật đã cài
- Thứ tự: 3 < 4 < … < 10 < J < Q < K < A < 2; chất: bích < chuồn < rô < cơ.
- Bộ hợp lệ: lẻ, đôi, sám cô, tứ quý, sảnh (≥3 lá, không có 2), đôi thông (≥3 đôi, không có 2).
- Đánh cùng loại, cùng số lá, lá cao nhất lớn hơn.
- Chặt: tứ quý hoặc 3 đôi thông chặt heo lẻ; tứ quý hoặc 4 đôi thông chặt đôi heo; 4 đôi thông chặt tứ quý; tứ quý / 4 đôi thông chặt 3 đôi thông.
- Ván đầu: ai cầm 3 bích đi trước và phải đánh kèm 3 bích. Ván sau: người thắng ván trước đi trước.
- Bỏ lượt là bị khóa tới hết vòng; mọi người bỏ thì người đánh sau cùng dẫn vòng mới.
- Ván kết thúc khi có người hết bài đầu tiên.

Chưa có: tới trắng (tứ quý 3, sáu đôi…), thối heo/tính tiền, chặt chồng, xếp hạng nhì/ba/bét.

## Cấu trúc
```
app/src/main/java/vn/tienlen/game/
  Cards.kt         mã hóa lá bài
  Combo.kt         nhận diện bộ bài + luật chặt
  Game.kt          trạng thái ván, lượt, bỏ lượt
  Bot.kt           AI đối thủ
  CardRenderer.kt  vẽ lá bài bằng Canvas
  GameView.kt      giao diện + điều khiển gamepad
  MainActivity.kt  toàn màn hình, nút Back
```
