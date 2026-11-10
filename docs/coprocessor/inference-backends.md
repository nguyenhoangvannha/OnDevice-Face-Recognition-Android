# Chọn phần cứng suy luận: CPU / GPU / NPU

Vòng 3 (2026-11-10). Người dùng chọn phần cứng chạy FaceNet trong dashboard (thẻ **Inference backend**),
trên trang web (`GET /`), hoặc qua `PUT /api/settings/backend`. Lựa chọn được lưu (`coprocessor_settings`,
khoá `inference_backend`) và áp dụng lại khi app khởi động.

## Trạng thái từng backend

| Backend | Model | Đã có trong bản này | Bằng chứng | Chưa kiểm chứng |
| --- | --- | --- | --- | --- |
| **CPU** (mặc định) | `model.pte` upstream, XNNPACK fp32, đóng gói trong APK | **có** | Script export tái tạo cho embedding trùng (cosine 1.000000, sai khác < 3e-7) | Tốc độ trên Xperia |
| **GPU** (Vulkan) | `facenet-vulkan-fp32.pte`, tải khi dùng lần đầu từ release `models-v1` | **có** | Export xong, 572/575 node vào Vulkan, sha256 cố định, export lặp lại cho file giống hệt; app tự kiểm tra trên máy bằng probe | Chạy thật trên Adreno 660, tốc độ, nhiệt, độ chính xác số học trên GPU |
| **NPU** (QNN HTP, SM8350) | chưa có | **không** (hiện trong UI nhưng bị khoá, API trả `409`) | Script `export_pte.py --backend qnn` đã viết, chưa chạy | Toàn bộ |

### Vì sao NPU chưa có

1. Export cần **Qualcomm AI Runtime (QNN) SDK** trên Linux x86_64. SDK tải được không cần tài khoản, nhưng đi kèm
   "AI Stack License" của Qualcomm: tải/dùng là đồng ý điều khoản, và điều khoản xếp **nhận dạng sinh trắc học** vào nhóm
   "High-Risk" mà Qualcomm khuyên không dùng. Đây là quyết định pháp lý của anh, nên em **không** chấp nhận thay; file SDK
   đã tải thử về box để kiểm tra cấu trúc đã bị xoá, không dùng để export.
2. App sẽ cần runtime QNN: AAR `executorch-android-qnn` **không gộp được** với `executorch-android-vulkan` (trùng class,
   [executorch#18683](https://github.com/pytorch/executorch/issues/18683)), nên phải tự build AAR có cả XNNPACK + Vulkan + QNN,
   cộng `com.qualcomm.qti:qnn-runtime:2.37.0` (~52 MB AAR, nên chỉ lấy thư viện HTP V68).
3. HTP V68 (Snapdragon 888) không có fp16 → model NPU phải là int8 (8a8w/16a8w).

Ước lượng ảnh hưởng int8 (mô phỏng trên Mac, bộ lượng tử XNNPACK thay cho QNN, 600 cặp LFW; `tools/export/quant_drift.py`):

| | cos(fp32, int8) mỗi ảnh | Độ chính xác tốt nhất | Ngưỡng tốt nhất |
| --- | --- | --- | --- |
| fp32 ↔ fp32 | — | 0.992 | 0.38 |
| int8 ↔ int8 | trung bình 0.998, thấp nhất 0.992 | 0.988 | 0.40 |
| mặt đăng ký bằng CPU ↔ truy vấn int8 | | 0.990 | 0.38 |

→ Nếu làm NPU int8, khuôn mặt đã đăng ký vẫn dùng được nhưng nên đo lại ngưỡng; app đã để ngưỡng probe NPU 0.99.

Ghi chú phụ quan trọng: trên LFW, ngưỡng tốt nhất ~**0.38**, trong khi mặc định API đang là **0.70**
(cặp cùng người trung bình chỉ ~0.75). Cần hiệu chỉnh bằng ảnh camera thật trước khi dùng (**chưa kiểm chứng** với camera nhà).

## Cách app chuyển backend (`engine/backend/BackendController.kt`)

1. Khởi động: luôn nạp **CPU** trước (như trước đây), tính embedding của một ảnh probe cố định (ảnh nhiễu giả ngẫu nhiên
   160×160, giống nhau trên mọi máy).
2. Nếu đã chọn GPU: chạy nền — tải model nếu chưa có (HTTPS, kiểm sha256, file `.part` rồi đổi tên), nạp
   (`Module.load` + `loadMethod`), chạy probe, so với CPU. Chỉ khi **cosine ≥ 0.999** mới thay CPU; nếu không, giữ CPU và
   ghi lý do. Trong lúc đó nhận diện vẫn chạy CPU.
3. Mọi lỗi (tải, checksum, nạp, chạy, sai số) → CPU + `fallback_reason`, hiện ở `/api/status`, `/api/settings/backend`,
   dashboard và trang web.
4. **Chống crash lặp**: trước khi khởi tạo native của GPU, app ghi cờ `inference_backend_pending_probe` (commit đồng bộ).
   Nếu driver Vulkan làm app chết, lần mở sau thấy cờ → ở lại CPU, báo "crashed during initialisation", không thử lại cho tới
   khi chọn lại.
5. Đổi qua lại: model cũ được `destroy()` sau khi model mới đã sẵn sàng; tối đa 2 model trong RAM ngắn hạn (~190 MB native).

Embedding giữa các backend tương thích vì **cùng trọng số fp32** và có kiểm tra probe; không cần đăng ký lại khi chuyển CPU ↔ GPU.

## Kích thước APK và cách giao model

| Thay đổi | Ảnh hưởng |
| --- | --- |
| `executorch-android` → `executorch-android-vulkan` 1.2.0 (có cả XNNPACK + Vulkan) | `libexecutorch.so` arm64 8.53 → 14.07 MB (+5.5 MB), x86_64 11.23 → 16.96 MB (+5.7 MB); `.so` lưu không nén → APK **+~11.3 MB** (máy arm64 chỉ dùng +5.5 MB) |
| Model GPU (94 MB) | **Không** đóng gói: tải khi chọn GPU lần đầu, lưu ở `filesDir` |
| APK release chưa ký sau thay đổi | 224.6 MB (trước: ~213 MB, ước từ chênh lệch thư viện) |

Quyết định: **tải theo yêu cầu** từ GitHub Release `models-v1` của fork (asset công khai, URL + sha256 + kích thước ghim trong
`ModelCatalog`), thay vì nhân đôi APK lên ~320 MB. Release `models-v1` là prerelease riêng cho model, không phải bản APK.
Muốn thu nhỏ APK thêm: `abiFilters += "arm64-v8a"` cho bản release (bỏ x86/x86_64/armeabi-v7a của các thư viện khác) —
chưa làm vì sẽ không cài được trên emulator.

Lưu ý: điện thoại cần ra Internet (tới `github.com` / `objects.githubusercontent.com`) **một lần** khi chọn GPU. Không có mạng
→ `fallback_reason` "gpu failed: ..." và vẫn chạy CPU.

## Kiểm thử

- JVM (`BackendControllerTest`, 15 test): mặc định CPU; chuyển GPU sau tải + probe; khôi phục GPU khi khởi động; rơi về CPU khi
  tải lỗi / nạp lỗi / chạy lỗi / kết quả khác CPU; cờ crash; cờ probe được đặt trong lúc khởi tạo native; NPU bị từ chối;
  quay về CPU; khởi tạo lười; tên backend; catalog; probe tất định.
- API (`FaceApiModuleTest.backendApi`): 401, GET, PUT 202, 409 cho NPU, 400, 413.
- **Không đo được tốc độ thật**: chưa có điện thoại trong lượt này. Cần đo `timings.embedding_ms` của `/api/recognize` trên
  Xperia cho CPU và GPU (xem [testing.md](testing.md)).

## Tái tạo model

Xem [tools/export/README.md](../../tools/export/README.md): nguồn trọng số, phiên bản ghim, lệnh export, sha256.
