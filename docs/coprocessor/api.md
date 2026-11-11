# REST API

Code: `app/src/main/java/com/ml/shubham0204/facenet_android/coprocessor/server/` (`FaceApiModule.kt`, `ApiModels.kt`).
Test: `app/src/test/.../coprocessor/server/FaceApiModuleTest.kt` (chạy trên JVM với backend giả).

- Địa chỉ: `http://<ip-điện-thoại>:<port>`. Port mặc định `8080` (đổi trong dashboard), bind `0.0.0.0`.
- Chỉ HTTP thường, không TLS: chỉ dùng trong LAN hoặc qua Tailscale (xem [configuration-and-security.md](configuration-and-security.md)).
- Server: Ktor 2.3.12, engine CIO.
- Xác thực: `Authorization: Bearer <token>` cho mọi endpoint trừ `/api/health`. Sai/thiếu token → `401`
  (header `WWW-Authenticate: Bearer realm="facenet-coprocessor"`, body rỗng).
- JSON snake_case. Lỗi có dạng `{"error": "<mã>", "message": "<mô tả>"}`.
- Không cài CORS: trang web thử nghiệm cùng origin với API.
- Mọi response có `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer` (chi tiết: [web-test-page.md](web-test-page.md#bảo-mật)).

| Method | Path | Token | Mô tả |
| --- | --- | --- | --- |
| GET | `/`, `/app.js`, `/app.css` | không | Trang web thử nghiệm ([web-test-page.md](web-test-page.md)) |
| GET | `/api/health` | không | Sống hay chết; không lộ thông tin máy |
| GET | `/api/status` | có | Pin, nhiệt độ pin, thermal status, heap, số người, ngưỡng, uptime |
| POST | `/api/recognize` | có | Nhận diện mọi khuôn mặt trong 1 ảnh |
| POST | `/api/enroll` | có | Đăng ký người mới: `name` + 1..10 ảnh |
| POST | `/api/persons/{id}/images` | có | Thêm 1..10 ảnh cho người đã có |
| GET | `/api/persons` | có | Danh sách người đã đăng ký |
| GET / PUT | `/api/settings/threshold` | có | Đọc / đặt ngưỡng cosine (lưu cùng chỗ với slider trong app) |
| GET / PUT | `/api/settings/backend` | có | Đọc / chọn phần cứng chạy FaceNet: `cpu` / `gpu` / `npu` |
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
  "uptime_s": 3600, "requests_served": 120, "android_sdk": 33,
  "inference_backend": "cpu", "inference_backend_requested": "gpu",
  "inference_backend_fallback_reason": "gpu failed: ..."
}
```

- `inference_backend`: phần cứng đang tính embedding (`null` khi model chưa nạp xong);
  `inference_backend_requested`: lựa chọn đã lưu; khác nhau khi đang tải/nạp hoặc đã rơi về CPU (lý do ở trường cuối).

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
- `bbox` luôn nằm trong ảnh: khung ML Kit vượt ra ngoài ảnh được **kẹp** vào trong (từ nhánh `fix/clamp-face-box`). Một mặt
  chỉ bị bỏ (không có trong `faces`) khi sau khi kẹp còn cạnh < 20 px hoặc còn < 50% diện tích khung phát hiện. Trước đây mọi
  mặt có khung chạm/vượt mép ảnh đều bị bỏ âm thầm.
- `similarity` là cosine của embedding gần nhất (0 nếu DB rỗng).
- `inference_ms` = detection + embedding + search (+ spoof), không tính decode và mạng.
- Không có mặt nào: `faces: []`, `face_count: 0` (vẫn `200`).
- `spoof` (khi `liveness=true`): `{"is_spoof": false, "score": 0.93}`.

Lỗi: `400 missing_image`, `400 invalid_image` (không decode được hoặc rỗng), `413 payload_too_large`,
`415 unsupported_media_type` (không phải multipart), `401`, `500 internal_error`.

### Recognize v2 (nhánh `feature/recognize-v2`, chưa cài lên máy)

Tham số tuỳ chọn mới, gửi qua query **hoặc** field text trong multipart (query thắng). Không gửi gì → giống hệt v1 (chỉ thêm
trường `sharpness`, `sharpness_ms`, `person_box: null`, `faces_outside_person_box: 0`).

| Tham số | Giá trị | Tác dụng |
| --- | --- | --- |
| `person_box` | `x,y,w,h` chuẩn hoá 0..1 (đúng `data.box` của event Frigate) | Chỉ giữ mặt có tâm nằm trong khung **và** ≥ 50% diện tích mặt trong khung. Lọc trước khi tính embedding. Sai định dạng (≠ 4 số, x/y ngoài 0..1, w/h ≤ 0 hoặc > 1, x+w > 1, y+h > 1) → `400 invalid_person_box` |
| `cluster_strangers` | `true`/`false` | Mặt không khớp người nhà được gom vào cụm `Person_NN` lưu trên máy (chỉ vector, không ảnh). Giá trị khác → `400 invalid_parameter` |
| `event_id` | 1..64 ký tự `A-Z a-z 0-9 . _ : -` (vd. id event Frigate `1794372518.917899-f7uo07`) | Nhiều frame của cùng event không đếm `appearances` hai lần (xem dưới). Sai → `400 invalid_event_id` |
| `demographics` | `true`/`false` | **Chưa có model**: `true` → `501 not_available`. `false` như không gửi. Giá trị khác → `400 invalid_parameter` |

`liveness` giữ hành vi v1 (khác `true` thì coi là tắt).

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" \
     -F "image=@snapshot.jpg;type=image/jpeg" \
     "$XPERIA_FACE_URL/api/recognize?person_box=0.41,0.22,0.12,0.55&cluster_strangers=true"
```

```json
{
  "faces": [
    {"bbox": {"left": 812, "top": 240, "right": 880, "bottom": 318},
     "person_id": 9, "name": "Nha", "similarity": 0.81, "recognized": true, "spoof": null,
     "sharpness": 168.4, "is_stranger": false, "stranger_id": null, "stranger_status": null,
     "appearances": null, "stranger_similarity": null},
    {"bbox": {"left": 1010, "top": 260, "right": 1062, "bottom": 322},
     "person_id": null, "name": "Person_03", "similarity": 0.41, "recognized": false, "spoof": null,
     "sharpness": 92.7, "is_stranger": true, "stranger_id": 3, "stranger_status": "seen",
     "appearances": 5, "stranger_similarity": 0.78}
  ],
  "face_count": 2, "recognized_count": 1, "threshold": 0.7, "inference_ms": 260,
  "timings": {"decode_ms": 12, "detection_ms": 70, "embedding_ms": 170, "search_ms": 3, "spoof_ms": 0,
              "sharpness_ms": 1, "stranger_ms": 2},
  "image_width": 1920, "image_height": 1080,
  "person_box": [0.41, 0.22, 0.12, 0.55], "faces_outside_person_box": 1,
  "event_id": null, "event_repeat": false
}
```

(Số liệu trong ví dụ là minh hoạ, chưa đo trên máy.)

- `sharpness`: phương sai Laplacian trên mặt resize 112×112 xám, cùng công thức với `face_clustering.py` của Mac Mini
  (`cv2.Laplacian(cv2.cvtColor(cv2.resize(roi,(112,112)),BGR2GRAY),CV_64F).var()`), so khớp OpenCV trong unit test (sai số ≤ 5%,
  ảnh gần phẳng ≤ 10%). Lớn = nét. 1 chữ số thập phân. Không đặt ngưỡng phía app: client tự quyết (Mac Mini đang dùng ngưỡng riêng).
- `person_box` trong response: khung đã parse (hoặc `null`); `faces_outside_person_box`: số mặt phát hiện bị loại vì nằm ngoài khung.
  `face_count` chỉ đếm mặt được giữ.
- Người lạ: `is_stranger` là `null` khi không bật `cluster_strangers`, `false` cho người nhà, `true` cho mặt không khớp.
  `stranger_status`: `new` (vừa tạo cụm), `seen` (khớp cụm cũ, `appearances` +1), `too_small` (cạnh ngắn của mặt < `min_face_px`
  = 40 px: không gom, `stranger_id`/`name` `null`). Với người lạ `name = "Person_NN"` nhưng **`recognized` vẫn `false` và
  `person_id` vẫn `null`** (để client cũ không coi người lạ là người quen).
- Ghép cụm: cosine với vector gần nhất trong mọi cụm ≥ ngưỡng người lạ (mặc định **0.70**, đổi bằng
  `PUT /api/settings/strangers`). Mỗi cụm giữ 10 vector mới nhất; tối đa 200 cụm, đầy thì xoá cụm lâu không thấy nhất. Số `NN`
  tăng mãi, không dùng lại. Không có `event_id` thì mỗi request đếm 1 lần xuất hiện; gửi nhiều frame/event thì kèm `event_id`.

#### `event_id` (chống đếm trùng)

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" -F "image=@frame2.jpg" \
     "$XPERIA_FACE_URL/api/recognize?cluster_strangers=true&event_id=1794372518.917899-f7uo07"
# {..., "faces": [{..., "name": "Person_03", "stranger_status": "seen", "appearances": 5}],
#  "event_id": "1794372518.917899-f7uo07", "event_repeat": true}
```

- Response luôn có `event_id` (bản sao, `null` khi không gửi) và `event_repeat` (`true` khi id này đã gặp; `false` khi mới
  hoặc không gửi).
- Nhận diện người nhà chạy bình thường với mọi frame; `event_id` chỉ đổi cách gom người lạ:
  - Một cụm chỉ được +1 `appearances` **một lần cho mỗi event**. Frame sau của cùng event vẫn trả `seen` + `appearances`
    hiện tại, cập nhật `last_seen_ms`.
  - Mỗi event giữ **tối đa một ô embedding** trong mỗi cụm. Frame sau chỉ **thay** embedding của ô đó khi mặt **nét hơn**
    (`sharpness` lớn hơn) mặt đang lưu; không thêm embedding mới. Ô giữ nguyên tuổi khi bị thay, nên một event không đẩy
    embedding của event khác ra khỏi giới hạn 10. Không có `event_id`: mỗi request thêm một embedding, đủ 10 thì bỏ cái cũ nhất.
  - Ô gắn với **mục event trong RAM** (khoá ngẫu nhiên mỗi lần tạo mục): event hết hạn, bị LRU bỏ hoặc app khởi động lại thì
    cùng `event_id` được coi là event mới, có ô mới (ô cũ ở lại như embedding thường, theo giới hạn 10).
  - Event lặp lại mà mặt không khớp cụm nào (dưới ngưỡng, vd. quay nghiêng) → gán vào cụm **gần nhất trong các cụm của
    event đó** thay vì tạo `Person_NN` mới (một event Frigate theo dõi một người; dùng kèm `person_box` để chỉ còn mặt của
    người đó). Frame đầu tiên của event vẫn có thể tạo nhiều cụm nếu ảnh có nhiều người lạ.
  - Cụm bị xoá/promote/thay thế giữa chừng thì event quên cụm đó.
- Bộ nhớ event: **chỉ trong RAM**, LRU tối đa 1.000 event, TTL 24 giờ tính từ lần thấy cuối. Khởi động lại app → quên hết,
  frame đầu sau đó được tính như event mới (nhiều nhất thêm 1 `appearances`). Chọn RAM vì đơn giản, không ghi flash mỗi
  request; đổi sang ObjectBox nếu thấy app hay bị khởi động lại.

Lỗi mới: `400 invalid_person_box`, `400 invalid_parameter`, `400 invalid_event_id`, `501 not_available` (`demographics=true`).

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

Lý do từ chối một ảnh (`images_rejected[].reason`): `No faces were in the image`, `Multiple faces found in the image`,
`Face is too small or mostly outside the image` (sau khi kẹp khung: cạnh < 20 px hoặc < 50% khung nằm trong ảnh; trước bản sửa
mọi khung chạm/vượt mép ảnh đều trả `Face detection failed`), `Face detection failed` (lỗi khác).

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

## GET / PUT /api/settings/threshold

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" "$XPERIA_FACE_URL/api/settings/threshold"
# {"threshold": 0.7}
curl -s -X PUT -H "Authorization: Bearer $XPERIA_FACE_TOKEN" -H "Content-Type: application/json" \
     -d '{"threshold": 0.65}' "$XPERIA_FACE_URL/api/settings/threshold"
# {"threshold": 0.65}
```

- Giá trị 0..1 (kể cả 0 và 1). Ngoài khoảng, thiếu trường, hay JSON hỏng → `400 invalid_threshold`. Body > 4 KiB → `413`.
- Lưu vào `CoprocessorSettings` (SharedPreferences), có hiệu lực ngay cho request sau. Slider trong app cho phép
  0.30–0.95; giá trị đặt qua API ngoài khoảng đó vẫn được lưu, slider chỉ hiển thị kẹp trong khoảng.

## Người lạ: /api/strangers (v2)

```bash
H="Authorization: Bearer $XPERIA_FACE_TOKEN"
curl -s -H "$H" "$XPERIA_FACE_URL/api/strangers"
# {"strangers": [{"stranger_id": 3, "name": "Person_03", "appearances": 5, "num_embeddings": 5,
#                 "first_seen_ms": 1762840000000, "last_seen_ms": 1762843600000}], "count": 1, "max_clusters": 200}
curl -s -X POST -H "$H" -H "Content-Type: application/json" -d '{"name": "Chu_Ba"}' \
     "$XPERIA_FACE_URL/api/strangers/3/promote"
# {"stranger_id": 3, "stranger_name": "Person_03", "person_id": 16, "name": "Chu_Ba", "created": true, "embeddings_moved": 5}
curl -s -X POST -H "$H" -H "Content-Type: application/json" -d '{"person_id": 9}' \
     "$XPERIA_FACE_URL/api/strangers/4/promote"
curl -s -X DELETE -H "$H" "$XPERIA_FACE_URL/api/strangers/5"      # {"deleted": 5}
curl -s -X DELETE -H "$H" "$XPERIA_FACE_URL/api/strangers"        # {"deleted": <số cụm đã xoá>}
curl -s -H "$H" "$XPERIA_FACE_URL/api/settings/strangers"
# {"threshold": 0.7, "max_clusters": 200, "max_embeddings_per_cluster": 10, "min_face_px": 40}
curl -s -X PUT -H "$H" -H "Content-Type: application/json" -d '{"threshold": 0.72}' "$XPERIA_FACE_URL/api/settings/strangers"
```

- `GET /api/strangers`: mới thấy gần nhất trước.
- `promote`: `{"name": ...}` → nhập vào người nhà có đúng tên đó (`created: false`) hoặc tạo người mới (`created: true`);
  `{"person_id": n}` → nhập vào người đó (gửi cả hai thì `person_id` thắng). Toàn bộ vector của cụm chuyển sang người nhà,
  `num_images` tăng tương ứng, cụm bị xoá. Lỗi: `400 invalid_promote` (thiếu/rỗng/tên quá dài/`person_id` ≤ 0),
  `404 not_found` (không có cụm), `404 person_not_found`, `409 ambiguous_name` (nhiều người cùng tên → gửi `person_id`),
  `400 invalid_id`.
- `DELETE /api/strangers/{id}`: `200`, `404 not_found`, `400 invalid_id`. `DELETE /api/strangers` xoá hết (dữ liệu sinh trắc
  người lạ).
- `PUT /api/settings/strangers`: chỉ đổi `threshold` (0..1); sai → `400 invalid_threshold`. Các giới hạn khác cố định trong bản này.
- `GET /api/status` có thêm `strangers` (số cụm).


## GET / PUT /api/settings/backend

```bash
curl -s -H "Authorization: Bearer $XPERIA_FACE_TOKEN" "$XPERIA_FACE_URL/api/settings/backend"
curl -s -X PUT -H "Authorization: Bearer $XPERIA_FACE_TOKEN" -H "Content-Type: application/json" \
     -d '{"backend": "gpu"}' "$XPERIA_FACE_URL/api/settings/backend"     # 202 Accepted
```

```json
{
  "requested": "gpu", "active": "cpu", "phase": "downloading",
  "downloaded_bytes": 31457280, "total_bytes": 94010112, "fallback_reason": null,
  "backends": [
    {"id": "cpu", "label": "Default (CPU, XNNPACK)", "available": true, "reason": null,
     "source": "bundled", "downloaded": true, "size_bytes": 94011392, "precision": "fp32"},
    {"id": "gpu", "label": "GPU (Vulkan)", "available": true, "reason": null,
     "source": "download", "downloaded": false, "size_bytes": 94010112, "precision": "fp32"},
    {"id": "npu", "label": "NPU (Qualcomm QNN HTP, SM8350)", "available": false, "reason": "Not shipped yet: ...",
     "source": "none", "downloaded": false, "size_bytes": null, "precision": "int8 (planned)"}
  ]
}
```

- `PUT` lưu lựa chọn rồi chuyển **nền** → luôn `202`; theo dõi bằng `GET` (`phase`: `idle|downloading|loading|ready|failed`).
  Trong lúc tải/nạp, nhận diện vẫn chạy trên backend cũ.
- Tên không hợp lệ / JSON hỏng → `400 invalid_backend`; backend chưa có model (hiện là `npu`) → `409 backend_unavailable`;
  body > 4 KiB → `413`.
- `phase: failed` + `fallback_reason`: backend được chọn không chạy được (tải lỗi, checksum sai, nạp lỗi, chạy lỗi, kết quả khác CPU,
  hoặc lần trước làm app crash) → đang dùng CPU. Lựa chọn vẫn được giữ; `PUT` lại để thử lại.
- Chi tiết: [inference-backends.md](inference-backends.md).

