# Cấu hình và bảo mật

Trạng thái: **khung**, cập nhật khi bước c và d xong.

Nguyên tắc:

- Không hardcode hay commit token. Token sinh ngẫu nhiên lần chạy đầu, lưu trong bộ nhớ riêng của app.
- Không cài CORS.
- `/api/health` không cần token và không lộ thông tin máy.
- Chỉ đăng ký khuôn mặt của người trong nhà đã đồng ý.
