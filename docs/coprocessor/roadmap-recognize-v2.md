# Lộ trình `recognize` v2 (2026-11-11)

Nguồn: `docs/cameras/recognition/xperia-vs-macmini-capability-and-resource-assessment.md` trong DIY-NHVN-HOME
(đánh giá Xperia so với Frigate và pipeline Mac Mini) và yêu cầu của anh ngày 2026-11-11 12:00.

## 1. Phần Xperia còn thiếu theo bản đánh giá

| # | Phần thiếu | Mac Mini đang làm ở đâu | Kế hoạch |
| --- | --- | --- | --- |
| 1 | Ràng buộc bbox người Frigate (`person_box`) | `face_notifier.py` | **v2, mục A** |
| 2 | Độ nét mặt (phương sai Laplacian) | `face_clustering.py::_sharpness` (crop → 112×112 xám → `cv2.Laplacian` → `var()`) | **v2, mục B** |
| 3 | Gom cụm người lạ `Person_XX` | `face_clustering.py` (SFace 128D, `CLUSTER_THRESHOLD = 0.42`, tối đa 15 vector/cụm, JSON) | **v2, mục C** |
| 4 | Tuổi / giới tính | `genderage.onnx` (InsightFace) qua OpenCV DNN | **v2, mục D: chỉ API + 501**, chờ anh quyết licence |
| 5 | Person ReID (dáng người) | `person_reid_youtu_2021nov.onnx` | Chưa làm. Cần crop toàn thân, endpoint riêng (`POST /api/reid`), runtime ONNX hoặc chuyển sang LiteRT/ExecuTorch; licence model chưa kiểm |
| 6 | Pose chó/người (`yolo11m-pose`) | Mac Mini, lazy-load | Không đưa lên điện thoại (bản đánh giá cũng khuyên vậy; ~80 MB) |
| 7 | Tích hợp Frigate UI / ghi nhãn vào DB Frigate | Frigate native | Việc của client trên Mac Mini (gọi API Frigate `sub_label`), không phải của app |
| 8 | Chọn frame tốt nhất từ burst | `face_notifier.py` | Ở client; `sharpness` (mục B) cho client tiêu chí chọn |

Ghi chú về con số trong bản đánh giá: "~35–50 ms suy luận" và "~100–150 ms toàn trình" **chưa khớp đo thật**. Đo trên Xperia
ngày 2026-11-11 (ảnh crop ~450×500): CPU suy luận 361 ms / toàn trình 422 ms; GPU 172–330 ms / tốt nhất 243 ms. ML Kit
chế độ ACCURATE chiếm 66–230 ms (xem [testing.md](testing.md)).

## 2. Thiết kế v2

Mọi thay đổi chỉ **thêm** tham số và trường; client cũ không gửi tham số mới thì nhận đúng như trước (thêm vài trường mới).
Tham số mới có thể gửi ở query string hoặc field multipart cùng tên; query thắng. Giá trị boolean chỉ nhận `true|false`;
sai → `400 invalid_parameter` (khác `liveness` cũ, vẫn giữ kiểu lỏng để tương thích).

### A. `person_box`

- `POST /api/recognize?person_box=x,y,w,h`, số thực 0..1 theo quy ước Frigate (`event.data.box` = `[x, y, w, h]` chuẩn hoá
  theo khung hình; đã kiểm trên Frigate gia đình 0.18.2). Ảnh gửi phải là cùng khung hình (snapshot toàn khung, không crop).
- Kiểm: đúng 4 số hữu hạn, `x,y ∈ [0,1]`, `w,h ∈ (0,1]`, `x+w ≤ 1`, `y+h ≤ 1` (dung sai 1e-6) → sai thì `400 invalid_person_box`.
- Luật giữ mặt (sau ML Kit, **trước** FaceNet): tâm khung mặt nằm trong `person_box` **và** ≥ 50% diện tích khung mặt nằm trong
  `person_box`. Mặt bị bỏ không tốn embedding.
- Response: `person_box` (echo, `null` nếu không gửi), `faces_outside_person_box` (số mặt bị bỏ, 0 nếu không gửi).
- Rủi ro: client gửi box của khung khác (snapshot đã crop/resize khác tỉ lệ) → bỏ nhầm. Box Frigate là box người, mặt ở
  phần trên; luật "tâm + 50%" chấp nhận mặt sát mép trên của box.

### B. `sharpness`

- Mỗi mặt có `sharpness` (số thực, 1 chữ số thập phân): crop mặt (khung đã kẹp) → resize bilinear về **112×112**
  (cùng quy ước toạ độ tâm pixel như OpenCV `INTER_LINEAR`) → xám `0.299R + 0.587G + 0.114B` → nhân chập
  `[[0,1,0],[1,-4,1],[0,1,0]]` (biên `REFLECT_101` như OpenCV) → phương sai toàn ảnh.
  Cùng công thức với `face_clustering.py`, nên ngưỡng cũ của Mac Mini (vd. chặn trên 300 trong điểm chất lượng) dùng lại được;
  sai khác nhỏ do làm tròn kênh xám và JPEG.
- Luôn tính (rẻ, ~1 ms/mặt ước lượng, chưa đo trên máy), thời gian ở `timings.sharpness_ms`.

### C. Gom cụm người lạ (`cluster_strangers=true`, mặc định tắt)

- ObjectBox: entity mới `StrangerRecord` (id, số thứ tự, `appearances`, `first_seen_ms`, `last_seen_ms`) và
  `StrangerEmbeddingRecord` (id, `strangerId` có index, vector 512D có `@HnswIndex` cosine, thời điểm thêm). Không lưu ảnh.
- Luồng: mặt không khớp người nhà (`similarity < threshold`) và cạnh ngắn ≥ `min_face_px` (40) → tìm tuyến tính chính xác
  trong vector người lạ (tối đa 200 × 10 = 2.000 vector) → cosine ≥ `stranger threshold` thì `seen` (+1 `appearances`, thêm vector,
  giữ 10 vector mới nhất) → không thì tạo `Person_NN` mới (`new`). Số `NN` tăng đơn điệu, không dùng lại sau khi xoá.
  Đủ 200 cụm → xoá cụm có `last_seen_ms` cũ nhất trước khi tạo cụm mới.
- Mặt quá nhỏ: `stranger_status: "too_small"`, không tạo cụm.
- Ngưỡng người lạ mặc định **0.70** (bằng ngưỡng người nhà). Dữ liệu 2026-11-11: hai người nhà khác nhau đạt tới 0.684, cùng
  người thấp nhất 0.568. Ngưỡng thấp hơn ~0.68 sẽ gộp nhầm người khác nhau (tệ hơn tách đôi một người). Mac Mini dùng 0.42
  nhưng đó là SFace 128D, không so được. Đổi qua `PUT /api/settings/strangers {"threshold": x}`.
- Trường mới mỗi mặt: `is_stranger` (`null` khi tắt), `stranger_id`, `stranger_status` (`new|seen|too_small`),
  `appearances`, `stranger_similarity`. Mặt người lạ có `name = "Person_NN"`.
  **Khác đề xuất:** `recognized` vẫn `false` và `person_id` vẫn `null` cho người lạ (`person_id` chỉ là id người nhà).
  Client cũ dựa vào `recognized: true` để coi là người quen; đổi nghĩa sẽ báo nhầm người lạ thành người quen.
- Quản trị (đều cần token):
  - `GET /api/strangers` → danh sách cụm, mới thấy gần nhất trước.
  - `POST /api/strangers/{id}/promote` `{"name": "An"}` hoặc `{"person_id": 3}` → chuyển toàn bộ vector sang người nhà
    (người có đúng tên đó, hoặc tạo mới; trùng tên nhiều người → `409 ambiguous_name`, khi đó gửi `person_id`), xoá cụm.
  - `DELETE /api/strangers/{id}`, `DELETE /api/strangers` (xoá hết).
  - `GET / PUT /api/settings/strangers`.
- Rủi ro: (1) burst nhiều frame cùng event làm tăng `appearances` nhiều lần (client nên gửi 1 frame/event; có thể thêm
  `event_id` để chống đếm trùng ở bản sau). (2) Cụm bị "trôi" nếu một vector sai lọt vào; giữ 10 vector mới nhất giới hạn
  ảnh hưởng. (3) Dữ liệu sinh trắc của người lạ nằm trên điện thoại (chỉ vector) → có endpoint xoá hết.

### D. `demographics=true`

- Yêu cầu: `genderage.onnx` (~1.3 MB) trả `gender`, `gender_score`, `age`.
- Kiểm (2026-11-11, README InsightFace và `model_zoo/README.md`): code InsightFace MIT, nhưng "the models trained with these
  data are available for **non-commercial research purposes only**"; model zoo ghi "ALL models are available for
  non-commercial research purposes only". Model Gender&Age trong model zoo: MobileNet-0.25, 0.3 M tham số, train trên **CelebA**
  (bộ CelebA cũng chỉ cho nghiên cứu phi thương mại, theo hiểu biết của em, **chưa kiểm lại**).
- Runtime: app có ExecuTorch + LiteRT, **không** có ONNX Runtime. Thêm `onnxruntime-android` làm APK lớn thêm
  (chưa đo; ước lượng vài chục MB cho arm64, **chưa kiểm**), hoặc chuyển model sang LiteRT/ExecuTorch (cần thử).
- Quyết định: **chưa nhúng model**. `demographics=true` trả `501 not_available`; `demographics=false` hoặc không gửi như cũ.
  Lựa chọn cho anh:
  1. Chấp nhận licence InsightFace (dùng trong nhà, phi thương mại; có phải "research" không là việc anh tự đánh giá),
     chuyển `genderage.onnx` sang LiteRT để khỏi thêm ONNX Runtime.
  2. Tìm model có licence rõ ràng (vd. model train trên FairFace; bộ FairFace ghi CC BY 4.0, **chưa kiểm** licence của
     từng model) rồi đo độ chính xác trên ảnh CCTV.
  3. Để ở Mac Mini như hiện tại (chỉ gọi khi cần thống kê).
- Hợp đồng dự kiến khi có model: mỗi mặt thêm `gender` (`male|female`), `gender_score` (0..1), `age` (số nguyên, năm),
  thời gian ở `timings.demographics_ms`.

## 3. Thứ tự làm và kiểm thử

Trạng thái 2026-11-11: bước 1–6 **đã làm** trên `feature/recognize-v2` (81 test JVM + 10 test Python qua, debug/release build
qua). **Chưa** thử trên điện thoại. `event_id` chống đếm trùng: làm ở `feature/event-id`. Giao diện quản trị người lạ trên trang web
(**đã làm** sau đó ở `feature/web-strangers`), test ObjectBox thật cho `StrangerDB`.


1. Tài liệu này. 2. `person_box`. 3. `sharpness`. 4. Gom cụm người lạ + API quản trị + cài đặt. 5. `demographics` → 501.
6. Trang web, client Python, tài liệu.

Mỗi bước: unit test JVM (logic thuần Kotlin, giả backend cho Ktor), `testDebugUnitTest assembleDebug assembleRelease` trên
MacBook, CI xanh. Chưa thử trên điện thoại (máy đang chạy bản cũ; anh cài sau khi phát hành).
