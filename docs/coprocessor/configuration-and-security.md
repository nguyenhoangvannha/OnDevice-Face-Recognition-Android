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

## Chạy nền 24/7 (`coprocessor/service/`)

### Foreground service

`CoprocessorService`, `android:foregroundServiceType="specialUse"`, kèm
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` mô tả mục đích. Lý do không dùng `dataSync` như kế hoạch:

- Khi app target API 35 (Android 15), `dataSync` bị giới hạn khoảng 6 giờ mỗi 24 giờ, và **không** được khởi động
  từ `BOOT_COMPLETED`. Repo hiện target 34, nhưng một server 24/7 không phải "đồng bộ dữ liệu".
- `specialUse` không có giới hạn thời gian và không nằm trong danh sách type bị chặn khởi động từ `BOOT_COMPLETED`.
  Nhược điểm: nếu phát hành trên Google Play phải giải trình; app này chỉ cài tay (sideload) nên không sao.
- Android 14+ gọi `startForeground(id, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`; Android ≤ 13 gọi bản không type.

Luồng:

1. `startForegroundService` → `startForeground` ngay (thông báo "Starting…"), giữ WakeLock + WifiLock.
2. Trên luồng IO: nạp backend (FaceNet, ML Kit, ObjectBox), rồi mở port. Ktor CIO bind bất đồng bộ, nên
   `FaceApiServer.start()` chờ `resolvedConnectors()` (tối đa 10 s) để bắt lỗi port đã bị chiếm.
3. Lỗi khởi động → trạng thái `Failed(message)` (hiện trên dashboard), nhả lock, dừng service.
4. `START_STICKY`: hệ thống giết thì tự khởi động lại.
5. Thông báo có nút **Stop** (đặt "server bật" = false rồi dừng).

### WakeLock và WifiLock

- `PARTIAL_WAKE_LOCK` giữ suốt thời gian service chạy (không timeout, có chủ đích).
- `WIFI_MODE_FULL_HIGH_PERF`: Wi-Fi không vào power-save khi tắt màn hình, **trên Android ≤ 13**.
  Từ API 34 (Android 14) chế độ này bị deprecate và hệ thống tự đổi thành `WIFI_MODE_FULL_LOW_LATENCY`, chỉ hiệu lực
  khi màn hình bật và app ở foreground. Xperia 5 III đang chạy Android bản nào: **chưa kiểm chứng**.
  Nếu là Android 14+, độ trễ khi tắt màn hình phải đo thực tế.

### Tự khởi động (`BootReceiver`)

- Nghe `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`.
- Chỉ khởi động lại khi người dùng để server **bật** (`server_enabled = true`).
- Ba broadcast này được miễn hạn chế khởi động FGS từ nền của Android 12+.
- Sau khi cài APK phải mở app ít nhất 1 lần (app mới cài ở trạng thái "stopped", không nhận broadcast).

### Quyền trong manifest

| Quyền | Để làm gì |
| --- | --- |
| `INTERNET` | Mở socket server |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Đọc trạng thái mạng, WifiLock |
| `WAKE_LOCK` | Partial wake lock, WifiLock |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Foreground service type `specialUse` (Android 14+) |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Hộp thoại xin bỏ tối ưu pin |
| `POST_NOTIFICATIONS` | Hiện thông báo của service (Android 13+ phải xin lúc chạy) |

Có sẵn từ trước: `CAMERA`, `READ_EXTERNAL_STORAGE`. Manifest đã merge còn có `WRITE_EXTERNAL_STORAGE` và
`READ_PHONE_STATE`: **không do nhánh này thêm**, chúng bị suy ra (implied) từ `com.google.ai.edge.litert:litert-gpu-api:1.1.2`
vì thư viện đó khai báo targetSdk < 4 (theo `manifest-merger-debug-report.txt`).

### Việc phải làm tay trên điện thoại

- Cho phép thông báo; tắt tối ưu pin cho app (nút trên dashboard, hoặc Settings → Battery → Unrestricted).
- Kiểm tra các tính năng tiết kiệm pin riêng của Sony (STAMINA): **chưa kiểm chứng** ảnh hưởng.
- Cắm sạc 24/7: nên dùng tính năng sạc giới hạn/“Battery Care” của Sony nếu có (**chưa kiểm chứng** trên máy).

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
