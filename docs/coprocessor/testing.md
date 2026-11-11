# Kiểm thử

Trạng thái: **khung**, cập nhật theo từng bước.

## Unit test (JVM)

```bash
bash ./gradlew testDebugUnitTest
# báo cáo: app/build/reports/tests/testDebugUnitTest/index.html
```

| Test | Phạm vi |
| --- | --- |
| `engine/SimilarityMathTest` | cosine similarity, vector 0/rỗng, khác kích thước, ngưỡng bao gồm bằng, NaN, láng giềng gần nhất |
| `coprocessor/server/FaceApiModuleTest` | health không cần token và không lộ thông tin máy; 401 khi thiếu/sai token; recognize trả snake_case + ghi log; 400 thiếu ảnh / ảnh hỏng / ảnh rỗng; 413 quá cỡ; 415 không phải multipart; enroll 400/422/201; thêm ảnh 401/400/404/422/200; persons list; delete 400/404/200 |
| `FaceApiModuleTest` (vòng 2) | API ngưỡng (401, đọc, đặt, 400 khi ngoài 0..1/JSON hỏng); trang web không cần token, đúng content-type, CSP/nosniff, không URL ngoài, 404 đường dẫn lạ; không có header CORS |
| `coprocessor/server/FaceApiServerTest` | Ktor CIO thật trên cổng local: health 200, 401/200 theo token, stop; báo lỗi khi port bị chiếm |
| `coprocessor/TokenUtilsTest` | token 43 ký tự URL-safe, ngẫu nhiên; so khớp, null, rỗng |
| `coprocessor/CoprocessorStateHolderTest` | log giữ N request mới nhất, đếm tổng |
| `engine/PersonBoxTest` (v2) | parse `x,y,w,h` (đúng/sai/NaN/ngoài 0..1/vượt mép), lọc mặt theo tâm + ≥ 50% diện tích |
| `engine/SharpnessTest` (v2) | Laplacian variance khớp OpenCV 5.0.0 (`cv2.resize` + `BGR2GRAY` + `Laplacian CV_64F`) trên 5 ảnh mẫu, sai số ≤ 5% (ảnh gần phẳng ≤ 10%) |
| `engine/strangers/StrangerClustererTest` (v2) | `new`/`seen`/`too_small`, đếm `appearances`, giữ 10 vector, xoá cụm cũ nhất khi đầy, số `NN` không dùng lại, promote theo tên mới/cũ/id, lỗi không có cụm/không có người/trùng tên |
| `engine/strangers/EventTrackerTest` (event_id) | ký tự hợp lệ / sai / quá 64; lặp lại trong TTL, TTL tính từ lần cuối, hết hạn; LRU bỏ event ít dùng nhất |
| `StrangerClustererTest` (event_id) | event lặp lại không +1 `appearances`, chỉ thêm embedding khi nét hơn, giới hạn embedding vẫn áp dụng, không tạo cụm trùng khi lặp lại, frame đầu vẫn tạo được nhiều cụm, quên cụm đã xoá, `too_small` |
| `FaceApiModuleTest` (event_id) | `event_id`/`event_repeat` khi không gửi / mới / lặp lại; query và multipart; frame mờ không thêm embedding, frame nét thì thêm; event khác đếm lại; 5 giá trị sai → `400 invalid_event_id`; 64 ký tự → 200 |
| `FaceApiModuleTest` (web người lạ) | trang có màn hình Strangers, `app.js` gọi `/api/strangers`, promote, cài đặt; không có sink HTML (`innerHTML`…), không URL ngoài |
| `FaceApiModuleTest` (v2) | `person_box` query/multipart + 400; mặc định không đổi trường v1; luồng `cluster_strangers` qua 2 request (new → seen), list, promote 400/404/409/200, delete, delete all; 401 cho mọi endpoint người lạ; `cluster_strangers=yes` → 400; cài đặt người lạ GET/PUT/400; `demographics=true` → 501, `false` → 200, `1` → 400 |

Test REST chạy với backend giả, nên **không** kiểm model thật, ML Kit hay ObjectBox. Gom cụm người lạ được test với
store trong RAM cùng hợp đồng với `StrangerDB` (ObjectBox); bản ObjectBox thật chưa chạy test trên máy (cần thử trên điện thoại).

2026-11-11, nhánh `feature/event-id`: **92/92** test JVM qua, debug + release build qua; client Python 10/10.
2026-11-11, nhánh `feature/web-strangers`: **82/82** test JVM qua, debug + release build qua.
2026-11-11, nhánh `feature/recognize-v2`: **81/81** test JVM qua, `assembleDebug` + `assembleRelease` qua (MacBook).

## Client Python

```bash
cd clients/python && python3 -m unittest -v test_xperia_face_client
```

10 test với server HTTP giả: bắt buộc biến môi trường, `repr` không lộ token, multipart recognize/enroll/add-images, lỗi 401/404,
`recognize_snapshot` trả `None` khi timeout hoặc không kết nối được; v2: query `liveness`/`person_box`/`cluster_strangers`,
`person_box` sai số phần tử → `ValueError`, `event_id` đúng vào query và sai → `ValueError`, quản trị người lạ (list, promote theo tên, đúng một trong `name`/`person_id`,
xoá, xoá hết, đọc/đặt ngưỡng).

## Trang web trong trình duyệt (chạy tay)

[`tools/web-ui-test/`](../../tools/web-ui-test/README.md): Chrome headless + API giả (`mock_api.py`), không cần điện thoại hay
token thật. `strangers.test.js` kiểm màn hình người lạ (17 kiểm tra, qua 2026-11-11 trên MacBook, Chrome + puppeteer-core 23).
Không chạy trong CI.

## Kiểm thử trên máy thật (chưa làm)

```bash
export XPERIA_FACE_URL=http://<ip-điện-thoại>:8080 XPERIA_FACE_TOKEN=...
curl -s "$XPERIA_FACE_URL/api/health"                                         # 200, không cần token
curl -s -o /dev/null -w '%{http_code}\n' "$XPERIA_FACE_URL/api/status"         # 401
python3 clients/python/xperia_face_client.py status
python3 clients/python/xperia_face_client.py enroll "An" an1.jpg an2.jpg
time python3 clients/python/xperia_face_client.py recognize snapshot.jpg       # so inference_ms và tổng thời gian
```

Backend (CPU/GPU), xem [inference-backends.md](inference-backends.md):

```bash
H="Authorization: Bearer $XPERIA_FACE_TOKEN"
for b in cpu gpu; do
  curl -s -X PUT -H "$H" -H 'Content-Type: application/json' -d "{\"backend\":\"$b\"}" "$XPERIA_FACE_URL/api/settings/backend"
  until curl -s -H "$H" "$XPERIA_FACE_URL/api/settings/backend" | grep -Eq '"phase":"(ready|failed)"'; do sleep 3; done
  curl -s -H "$H" "$XPERIA_FACE_URL/api/settings/backend"                     # active, fallback_reason
  for i in 1 2 3 4 5; do curl -s -H "$H" -F image=@snapshot.jpg "$XPERIA_FACE_URL/api/recognize" | grep -o '"embedding_ms":[0-9]*'; done
done
```

Ghi lại: `active` có đúng là `gpu` không, `embedding_ms` CPU vs GPU, `similarity` của cùng ảnh trên hai backend (phải gần như
bằng nhau), nhiệt độ sau 10 phút gọi liên tục. Nếu app crash khi bật GPU, mở lại app: phải tự ở CPU và báo lý do.

Kiểm 24/7: tắt màn hình, gọi `/api/health` mỗi phút trong 72 giờ, ghi lại `/api/status` (nhiệt độ pin, thermal status).
Kiểm reboot: khởi động lại điện thoại, không mở app, gọi `/api/health`.

## Tiêu chí nghiệm thu (từ kế hoạch, tất cả **chưa kiểm chứng** trên máy thật)

| Hạng mục | Điều kiện đạt |
| --- | --- |
| Độ trễ suy luận | < 100 ms cho ảnh 720p/1080p |
| Độ trễ toàn trình | < 350 ms qua Wi-Fi |
| Chạy nền 24/7 | 72 giờ không bị Android giết khi tắt màn hình |
| Tự phục hồi | Tự chạy lại sau reboot (`BOOT_COMPLETED`) |
| Độ chính xác | Nhận đúng 5–10 người quen ảnh đủ sáng; 0% nhận nhầm người lạ ở ngưỡng đã chọn |
| Nhiệt độ | Pin < 40 °C khi chờ và khi có sự kiện thường |

## 2026-11-11 — Thử thật lần đầu trên Xperia 5 III (ảnh từ thư viện khuôn mặt Frigate)

Điều kiện: app chạy trên Xperia (Android SDK 33), backend `cpu`, ngưỡng đang đặt trên máy **0.40**. Gọi API từ MacBook
qua Wi-Fi LAN. Ảnh là các crop khuôn mặt trong thư viện khuôn mặt của Frigate gia đình (`/media/frigate/clips/faces/<tên>/`,
7 thư mục có tên). Thư mục `faces/known/` kiểu cũ trên Mini đang trống. Thư mục `train/` (ảnh Frigate tự đoán) không dùng
để đăng ký. Tên người = tên thư mục.

### Vấn đề 1: crop Frigate quá sát mặt → app từ chối

Gửi nguyên crop: 7/8 lần enroll trả `422 Face detection failed`. Nguyên nhân (đọc code `MLKitFaceDetector`): ML Kit tìm thấy
mặt nhưng bbox vượt ra ngoài ảnh, `validateRect` loại bỏ. Ở `/api/recognize` mặt đó bị lọc âm thầm (`faces: []`).
Cách làm trong lần thử: trên Mac, phóng crop lên cạnh ngắn ≥ 224 px rồi thêm viền 40% màu trung bình, lưu JPEG. Sau đó
44/46 ảnh được nhận (2 ảnh bị `No faces`: một ảnh trẻ sơ sinh nằm ngủ, một ảnh chính diện ông cụ).
**Nên sửa trong app:** kẹp (clamp) bbox vào trong ảnh thay vì bỏ, để client không phải tự thêm viền (chưa làm).

### Đăng ký

| Người | Ảnh lúc đăng ký | Ảnh sau khi thêm ảnh giữ lại |
| --- | ---: | ---: |
| A_Tuan_Thao | 3 | 5 |
| Bo | 2 (1 bị từ chối) | 4 |
| Em_be_t | 4 | 5 (1 bị từ chối) |
| Gia_Han | 3 | 5 |
| Me | 3 | 5 |
| Nha | 1 (thư mục chỉ có 3 ảnh) | 3 |
| Thao | 3 | 5 |

Bỏ các ảnh quá nhỏ hoặc không phải mặt (≤ 52 px, mờ). Tổng: 7 người, 32 embedding.

### Nhận diện ảnh giữ lại (không dùng khi đăng ký)

14 ảnh giữ lại (2 ảnh mỗi người): 13 phát hiện được mặt, **13/13 đúng người** (người đúng là gần nhất).
Ảnh còn lại (trẻ sơ sinh nằm ngủ) không phát hiện được mặt.

Cosine với người đúng: 0.878, 0.752 / 0.702, 0.568 / 0.865 / 0.699, 0.784 / 0.663, 0.817 / 0.892, 0.623 / 0.855, 0.866
(min 0.568, trung vị 0.784).

### Người lạ (impostor): bỏ tạm một người, gửi lại ảnh của họ

Lần lượt xoá từng người, gửi lại cả ảnh đăng ký và ảnh giữ lại của họ (32 ảnh phát hiện được mặt), rồi đăng ký lại y như cũ.
Cosine với người gần nhất **khác**: 0.307–0.684, trung vị ~0.53. Cao nhất: Em_be_t ↔ Gia_Han 0.684 (hai bé),
Me ↔ Thao 0.627, Gia_Han ↔ Em_be_t 0.629. Một crop "unknown" mờ từ `train/`: 0.361. Ảnh không có mặt: `faces: []`.

| Ngưỡng | Người quen nhận đúng (13) | Người lạ bị nhận nhầm (33) |
| ---: | ---: | ---: |
| 0.40 (đang đặt) | 13 | **28** |
| 0.55 | 13 | 11 |
| 0.60 | 12 | 7 |
| 0.65 | 11 | 1 |
| 0.70 | 9 | 0 |

Kết luận: ở 0.40, gần như mọi người lạ sẽ bị gán tên một người nhà. Với ảnh CCTV này, khoảng 0.65–0.70 là hợp lý
(0.70 = 0 nhận nhầm trong mẫu nhỏ này, mất 4/13 lần nhận đúng). Hai bé là cặp dễ nhầm nhất. Mẫu rất nhỏ; cần thêm ảnh
đăng ký (nhất là Nha, Bo) và đo lại trên snapshot event thật. Chưa đổi ngưỡng.

### Độ trễ (CPU, 10 lần cùng một ảnh ~ 450×450 sau khi thêm viền)

| | Trung vị | Min–max |
| --- | ---: | ---: |
| Toàn trình (curl, Wi-Fi) | 422 ms | 401–462 ms |
| `inference_ms` | 361 ms | 349–370 ms |
| Phát hiện mặt (ML Kit ACCURATE) | 218 ms | 212–232 ms |
| Embedding FaceNet (XNNPACK) | 138 ms | 135–145 ms |

Chưa đạt tiêu chí (< 100 ms suy luận, < 350 ms toàn trình). Phần lớn là ML Kit chế độ ACCURATE. Chưa thử GPU (Vulkan).
Pin 32.9–33.9 °C, thermal `none` (đang sạc).

## 2026-11-11 (2) — Ngưỡng 0.70, GPU (Vulkan), sửa kẹp khung mặt

Ngưỡng đặt qua `PUT /api/settings/threshold` = **0.70** (GET trả 0.7). Anh đã bật GPU: `/api/status` báo
`inference_backend: gpu`, requested `gpu`, `fallback_reason: null`; `/api/settings/backend` phase `ready`.

### Độ trễ GPU (cùng ảnh Thao như lần CPU, 10 lần)

| | CPU (sáng nay) | GPU lần 1 | GPU lần 2 (6 lần, vài phút sau) |
| --- | ---: | ---: | ---: |
| Toàn trình (curl, Wi-Fi) | 422 ms | 243 ms (217–279) | — |
| `inference_ms` | 361 ms | 172 ms (154–186) | ~330 ms |
| Phát hiện mặt (ML Kit, luôn CPU) | 218 ms | 66 ms (45–80) | 211–233 ms |
| Embedding FaceNet | 138 ms (XNNPACK) | 105 ms (104–108, Vulkan) | 112–119 ms |

GPU chỉ bớt ~30 ms ở phần embedding. Thời gian ML Kit dao động 3 lần giữa hai đợt đo cách nhau vài phút (có lẽ do
CPU governor/màn hình), nên con số toàn trình chưa ổn định; cần đo dài hơn. Cosine của ảnh đã đăng ký (embedding CPU)
gửi lại qua GPU = 1.000, nên embedding GPU khớp CPU.

### Ảnh "giữ lại" ở ngưỡng 0.70

Các ảnh giữ lại hôm trước đã được thêm vào DB sau lần đo, nên giờ trả cosine 1.000 (không còn là ảnh giữ lại). Thay vào đó
dùng crop **mới** trong `train/` của Frigate từ **camera gia đình** (`camera_que_2`; bỏ các crop từ camera `*_private`),
nhãn là Frigate đoán (0.92–0.99), đã xem bằng mắt cho hợp lý. Không xoá/đăng ký lại ai.

| Ảnh | Nguyên crop (app hiện tại) | Thêm viền |
| --- | --- | --- |
| Gia_Han #1 | **Em_be_t 0.713 (nhầm)** | không nhận (0.697) |
| Gia_Han #2 | Gia_Han 0.744 | **Em_be_t 0.750 (nhầm)** |
| Gia_Han #3 | Gia_Han 0.709 | Gia_Han 0.732 |
| Me #1..3 | không thấy mặt | Me 0.711, Me 0.721, không nhận (0.661) |
| Em_be_t (mờ, nửa mặt) | không thấy mặt | không thấy mặt |
| Snapshot rộng `camera_que_2` (event Frigate gắn Gia_Han) | **Em_be_t 0.758 (nhầm)**, mặt 37×49 px | — |
| 3 snapshot rộng khác (`camera_que_1/2`) | không thấy mặt | — |

Kết luận: 0.70 vẫn để lọt nhầm giữa hai bé (Gia_Han ↔ Em_be_t, 0.71–0.76). Ngưỡng không giải quyết được; cần thêm ảnh
đăng ký cho hai bé (nhiều góc, nhiều tuổi) và cân nhắc luật khoảng cách top-1/top-2. Snapshot toàn khung camera có mặt quá
nhỏ (2304×1296, mặt ~40 px) để nhận diện tin cậy; nên gửi crop người (bbox `person` của Frigate) thay vì cả khung.

### Sửa kẹp khung mặt (nhánh `fix/clamp-face-box`, chưa cài lên máy)

`FaceBoxClamp`: khung ML Kit được kẹp vào ảnh; chỉ bỏ khi cạnh sau kẹp < 20 px hoặc phần trong ảnh < 50% diện tích khung.
Áp cho cả enroll (`getCroppedFace`) và recognize (`getAllCroppedFaces`, cũng là đường camera trực tiếp). Lý do từ chối mới:
`Face is too small or mostly outside the image`. 9 unit test mới (`FaceBoxClampTest`); tổng 48 test qua.
Cần kiểm lại trên máy sau khi cài: các crop Frigate nguyên bản trong `~/Downloads/xperia-face-test/` phải được nhận
mà không cần thêm viền.

## Recognize v2 trên máy thật (chưa làm)

Máy đang chạy bản trước v2. Sau khi cài bản từ `feature/recognize-v2`:

1. Không tham số: response như cũ + `sharpness`. So `sharpness` với Mac Mini trên cùng crop (kỳ vọng gần bằng, khác do JPEG decode).
2. Lấy event Frigate có nhiều người, gửi snapshot + `person_box=<data.box>`: chỉ còn mặt của người đó, `faces_outside_person_box` > 0.
3. `cluster_strangers=true` với ảnh người lạ (không phải người nhà) 2 lần → `new` rồi `seen`; `GET /api/strangers`; promote thử
   rồi xoá người vừa tạo; `DELETE /api/strangers` để dọn.
4. Đo thêm `stranger_ms` khi có vài trăm vector người lạ.
