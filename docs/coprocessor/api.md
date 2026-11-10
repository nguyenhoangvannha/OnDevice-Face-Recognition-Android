# REST API

Code: `app/src/main/java/com/ml/shubham0204/facenet_android/coprocessor/server/` (`FaceApiModule.kt`, `ApiModels.kt`).
Test: `app/src/test/.../coprocessor/server/FaceApiModuleTest.kt` (chạy trên JVM với backend giả).

- Địa chỉ: `http://<ip-điện-thoại>:<port>`. Port mặc định `8080` (đổi trong dashboard), bind `0.0.0.0`.
- Chỉ HTTP thường, không TLS: chỉ dùng trong LAN hoặc qua Tailscale (xem [configuration-and-security.md](configuration-and-security.md)).
- Server: Ktor 2.3.12, engine CIO.
- Xác thực: `Authorization: Bearer <token>` cho mọi endpoint trừ `/api/health`. Sai/thiếu token → `401`
  (header `WWW-Authenticate: Bearer realm="facenet-coprocessor"`, body rỗng).
- JSON snake_case. Lỗi có dạng `{"error": "<mã>", "message": "<mô tả>"}`.
- Không cài CORS: không có client trình duyệt.

| Method | Path | Token | Mô tả |
| --- | --- | --- | --- |
| GET | `/api/health` | không | Sống hay chết; không lộ thông tin máy |
| GET | `/api/status` | có | Pin, nhiệt độ pin, thermal status, heap, số người, ngưỡng, uptime |
| POST | `/api/recognize` | có | Nhận diện mọi khuôn mặt trong 1 ảnh |
| POST | `/api/enroll` | có | Đăng ký người mới: `name` + 1..10 ảnh |
| POST | `/api/persons/{id}/images` | có | Thêm 1..10 ảnh cho người đã có |
| GET | `/api/persons` | có | Danh sách người đã đăng ký |
| DELETE | `/api/persons/{id}` | có | Xoá người và mọi embedding của họ |

## Giới hạn

| Giới hạn | Giá trị | Vượt thì |
| --- | --- | --- |
| Kích thước body (`Content-Length` và tổng byte đọc từ các part) | 8 MiB | `413 payload_too_large` |
| Số ảnh mỗi lần enroll | 10 | `400 too_many_images` |
| Độ dài `name` | 1..64 ký tự (sau khi trim) | `400 invalid_name` |
| Cạnh dài ảnh sau decode | 1920 px (ảnh lớn hơn bị subsample, toạ độ bbox tính trên ảnh đã subsample) | — |

## GET /api/health

```json
{"status": "ok", "service": "facenet-coprocessor", "api_version": 1}
```

## GET /api/status

```json
{
  "battery_pct": 80, "battery_temp_c": 31.5, "charging": true,
  "thermal_status": "none",
  "heap_used_mb": 40, "heap_max_mb": 256,
  "ignoring_battery_optimizations": true,
  "persons": 3, "face_records": 9, "threshold": 0.7,
  "uptime_s": 3600, "requests_served": 120, "android_sdk": 33
}
```

- `thermal_status`: `none|light|moderate|severe|critical|emergency|shutdown` từ `PowerManager` (Android 10+), `null` nếu thấp hơn.
  Không có nhiệt độ SoC: Android không có API công khai cho app thường.
- `heap_*`: heap Java của app, không phải RAM toàn máy.

## POST /api/recognize

`multipart/form-data`, file ở field `image` (nếu không có field tên `image` thì lấy file đầu tiên).
Query tuỳ chọn `liveness=true` để chạy anti-spoof (mặc định tắt; hiệu quả trên ảnh CCTV chưa kiểm chứng).

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" \
     -F "image=@snapshot.jpg;type=image/jpeg" \
     "$XPERIA_FACE_URL/api/recognize"
```

`200`:

```json
{
  "faces": [
    {"bbox": {"left": 1, "top": 2, "right": 30, "bottom": 40},
     "person_id": 1, "name": "An", "similarity": 0.82, "recognized": true, "spoof": null},
    {"bbox": {"left": 50, "top": 60, "right": 90, "bottom": 99},
     "person_id": null, "name": null, "similarity": 0.21, "recognized": false, "spoof": null}
  ],
  "face_count": 2,
  "recognized_count": 1,
  "threshold": 0.7,
  "inference_ms": 42,
  "timings": {"decode_ms": 3, "detection_ms": 10, "embedding_ms": 25, "search_ms": 7, "spoof_ms": 0},
  "image_width": 640,
  "image_height": 480
}
```

- `recognized = similarity >= threshold`. `person_id`/`name` chỉ có khi `recognized`.
- `similarity` là cosine của embedding gần nhất (0 nếu DB rỗng).
- `inference_ms` = detection + embedding + search (+ spoof), không tính decode và mạng.
- Không có mặt nào: `faces: []`, `face_count: 0` (vẫn `200`).
- `spoof` (khi `liveness=true`): `{"is_spoof": false, "score": 0.93}`.

Lỗi: `400 missing_image`, `400 invalid_image` (không decode được hoặc rỗng), `413 payload_too_large`,
`415 unsupported_media_type` (không phải multipart), `401`, `500 internal_error`.

## POST /api/enroll

`multipart/form-data`: field `name` (text) và 1..10 file ở field `image`. Mỗi ảnh phải có **đúng 1** khuôn mặt.

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" \
     -F "name=An" -F "image=@an1.jpg" -F "image=@an2.jpg" \
     "$XPERIA_FACE_URL/api/enroll"
```

`201` (có ít nhất 1 ảnh hợp lệ) hoặc `422` (không ảnh nào hợp lệ, không lưu gì):

```json
{"person_id": 2, "name": "An", "images_accepted": 1,
 "images_rejected": [{"index": 1, "reason": "Multiple faces found in the image"}]}
```

Lỗi khác: `400 invalid_name`, `400 missing_image`, `400 too_many_images`, `400 invalid_image` (một ảnh không decode được → không lưu gì), `413`, `415`, `401`.

Mỗi lần enroll tạo **một người mới**, kể cả trùng tên. Muốn thêm ảnh cho người đã có: dùng endpoint dưới.

## POST /api/persons/{id}/images

`multipart/form-data`, 1..10 file ở field `image`, mỗi ảnh đúng 1 khuôn mặt. Giới hạn kích thước giống enroll.

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" \
     -F "image=@an3.jpg" -F "image=@an4.jpg" \
     "$XPERIA_FACE_URL/api/persons/1/images"
```

`200` (có ít nhất 1 ảnh được thêm; `num_images` của người tăng tương ứng) hoặc `422` (không ảnh nào có đúng 1 mặt, không lưu gì):

```json
{"person_id": 1, "name": "An", "images_accepted": 2, "images_rejected": []}
```

Lỗi khác: `400 invalid_id`, `404 not_found`, `400 missing_image`, `400 too_many_images`, `400 invalid_image`, `413`, `415`, `401`.

## GET /api/persons

```json
{"persons": [{"person_id": 1, "name": "An", "num_images": 2, "added_at_ms": 1762750000000}], "count": 1}
```

## DELETE /api/persons/{id}

`200 {"deleted": 1}`, `404 not_found`, `400 invalid_id`.
