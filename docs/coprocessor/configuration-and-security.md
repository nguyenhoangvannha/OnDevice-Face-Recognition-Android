# Cấu hình và bảo mật

## Cấu hình (`CoprocessorSettings`)

Lưu trong SharedPreferences riêng của app, file `coprocessor_settings.xml`.

| Khoá | Mặc định | Ghi chú |
| --- | --- | --- |
| Token API | sinh ngẫu nhiên lần đầu | 32 byte `SecureRandom`, Base64 URL-safe (43 ký tự). Xem/sao chép/tạo lại trong dashboard |
| Port | `8080` | 1024–65535; có hiệu lực khi khởi động lại server |
| Ngưỡng cosine | `0.70` | 0–1, đọc lại mỗi request, không cần restart |
| Server bật | `false` | Trạng thái mong muốn; BootReceiver chỉ khởi động lại khi `true` |

Host bind cố định `0.0.0.0` (mọi interface: Wi-Fi, Tailscale nếu có).

## Token

- Không hardcode, không commit token. Token chỉ nằm trong bộ nhớ riêng của app trên điện thoại.
- So sánh constant-time (`MessageDigest.isEqual`). Token được đọc lại mỗi request: tạo lại token thì token cũ hết hiệu lực ngay.
- Log request trong app không chứa token hay dữ liệu ảnh.
- `coprocessor_settings.xml` bị **loại khỏi** cloud backup và device transfer
  (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).
- Phía client: đặt token trong biến môi trường hoặc file secret có quyền `600`, không ghi vào repo.

## Mạng

- HTTP thường, không TLS. Chỉ mở trong LAN tin cậy hoặc qua Tailscale. **Không** port-forward ra Internet.
- Không cài CORS (không có client trình duyệt, tránh `anyHost()`).
- `/api/health` không cần token và chỉ trả `status`; thông tin máy ở `/api/status` (cần token).
- Giới hạn body 8 MiB, ảnh decode tối đa 1920 px cạnh dài.

## Quyền riêng tư

- Chỉ đăng ký khuôn mặt của người trong nhà **đã đồng ý**. Không dùng để nhận diện hàng xóm hay nơi công cộng.
- Embedding khuôn mặt là dữ liệu sinh trắc học, nằm trong ObjectBox của app. Upstream để
  `android:allowBackup="true"` và **chưa loại** DB ObjectBox khỏi backup: câu hỏi mở, cần anh quyết.
- Khi tích hợp với Frigate: ảnh và kết quả của camera `*_private` chỉ được đi luồng riêng, không vào nhóm gia đình.
