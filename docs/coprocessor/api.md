# REST API

Trạng thái: **thiết kế** (sẽ cập nhật khi bước c xong).

- Địa chỉ: `http://<ip-điện-thoại>:<port>`; port mặc định `8080`, bind `0.0.0.0`.
- Chỉ HTTP trong LAN/Tailscale; không có TLS (xem [configuration-and-security.md](configuration-and-security.md)).
- Xác thực: header `Authorization: Bearer <token>` cho mọi endpoint trừ `/api/health`.
- Body JSON dùng snake_case. Lỗi luôn có dạng `{"error": "<mã>", "message": "<mô tả>"}`.

| Method | Path | Token | Mô tả |
| --- | --- | --- | --- |
| GET | `/api/health` | không | Sống hay chết. Chỉ trả `status`, `service`, `version` |
| GET | `/api/status` | có | Pin, nhiệt độ, thermal status, RAM heap, số người, ngưỡng, uptime |
| POST | `/api/recognize` | có | Nhận diện các khuôn mặt trong 1 ảnh |
| POST | `/api/enroll` | có | Đăng ký người mới: `name` + 1..10 ảnh |
| GET | `/api/persons` | có | Danh sách người đã đăng ký |
| DELETE | `/api/persons/{id}` | có | Xoá người và toàn bộ embedding của họ |

Chi tiết request/response sẽ được viết khi code xong.
