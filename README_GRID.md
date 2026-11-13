# J2ME Loader Grid

Bản sửa từ J2ME-Loader 1.7.9: mở app là thấy lưới 2x2, chạy tối đa 4 game cùng lúc.

## Build bằng GitHub
1. Tạo repo mới, upload toàn bộ nội dung thư mục này (cả `.github/`).
2. Vào tab **Actions** → workflow **Build APK** (chạy tự động khi push, hoặc bấm *Run workflow*).
3. Xong tải artifact `J2ME-Loader-Grid-apk` (có `openDebug` và `openRelease`, cả hai đã ký bằng debug key nên cài được ngay).

## Cách dùng
- Mỗi ô có 4 nút: **Chạy/Dừng**, **Thiết lập**, **Chọn file .jar (convert)**, **Phóng to/Thu nhỏ**.
- Game mới: chọn jar → convert → bấm Chạy → hiện thiết lập lần đầu → bấm *Start*.
- Nút 3 chấm góc trên phải: thiết lập mặc định của game (profile), cài đặt app (có "không tắt màn hình"), chế độ gốc, dừng tất cả, thoát.
- Mở file .jar từ app khác → hỏi chọn lưới.

## Thiết kế
- Mỗi lưới = 1 process riêng (`:slot0..3`), thư mục riêng `.../slots/slotN/{converted,configs,data,cache}` nên RMS/thiết lập không đụng nhau.
- Cửa sổ game của mỗi lưới là cửa sổ nổi đặt đúng toạ độ ô; tất cả cùng 1 task nên recents chỉ có 1 app.
- Dạng lưới: chỉ *present* 1 khung hình/giây (`Canvas.requestFlushToScreen`), game thread vẫn chạy full tốc độ. Phóng to: present bình thường.
- Chuyển lưới ↔ phóng chỉ đổi kích thước/vị trí cửa sổ (không tạo lại Activity/Surface).
- Game chỉ bị tạm dừng khi cả app ra nền (Home), không bị dừng khi mở màn hình thiết lập.
