# Đọc và sửa bản dịch — Shelf 0.3.0

1. Mở trang tiếng Trung trong Internet rồi bấm Reader, hoặc mở EPUB tiếng Trung.
2. Chạm trực tiếp vào một cụm đã dịch: popup hiển thị hv (Hán Việt), HV (viết hoa
   đầu từng từ), zw (chữ Trung), Tr (Smooth Xianxia), tc (bản dịch tự nhập).
3. Dùng Mở rộng trái/phải để chọn cụm dài hơn. Chọn Name global hoặc Name riêng
   truyện, rồi tick cạnh bản dịch muốn dùng. App lưu và áp dụng ngay.
4. Bấm Name để sửa bảng `chữ Trung=bản dịch`. Chỉ có Global/Riêng truyện và
   Lưu lại/Chạy. Name riêng ưu tiên hơn Name global. Cùng một truyện STV chuyển
   chương hoặc đổi giữa sangtacviet.app và sangtacviet.com vẫn dùng chung Name.
5. Bảng góc phải đổi nền, chữ, bóng, font, cỡ chữ, giãn dòng, căn lề và đổi giữa
   bản gốc/bản dịch. Các thay đổi giao diện được lưu cùng JSON trên máy.
6. Bấm Nghe sách để mở danh sách câu. Phát/Tạm dừng, Dừng, Đi đến câu và phần
   cài đặt giọng/tốc độ nằm trong bảng. Câu đang đọc được tô sáng và cuộn tới.
   Có thể kéo bảng bằng thanh tiêu đề hoặc thu nhỏ/đóng bằng nút trên cùng.

Name và giao diện lưu trong file riêng của app `translation-names.json`, không
cần tài khoản/server. Bản sao lưu dữ liệu đọc có chứa bảng Name này. Cài bản mới
đè lên bản cũ để giữ dữ liệu; gỡ app sẽ xóa dữ liệu riêng nếu chưa sao lưu.

Chỉ tra được chữ gốc khi nguồn có tiếng Trung. Nếu trang/truyện chỉ cung cấp bản
Việt, app không thể suy ngược chữ Trung chính xác. Nghe sách cần giọng Việt/Trung
đã cài trong bộ TTS Android; Smooth Xianxia là từ điển ngoại tuyến, không phải mô
hình AI viết lại câu.
