# Khảo sát tăng tốc GPU/NPU cho FaceNet (chỉ đọc)

> **Cập nhật vòng 3:** nguồn model đã được xác nhận (facenet-pytorch InceptionResnetV1 VGGFace2, cosine 1.000000 so với
> `model.pte`), có script export tái tạo được ở `tools/export/`, và app đã có lựa chọn CPU/GPU (Vulkan). NPU vẫn chưa có.
> Xem [inference-backends.md](inference-backends.md). Phần dưới giữ nguyên như lúc khảo sát.

Ngày khảo sát: 2026-11-10. Lượt này **chỉ khảo sát**: không đổi model, không đổi dependency, không
chạy gì trên điện thoại. Mục nào chưa đo/chưa thấy tài liệu khẳng định được ghi **chưa kiểm chứng**.

Thiết bị đích: Sony Xperia 5 III, Snapdragon 888 (SM8350), GPU Adreno 660, Hexagon 780 (HTP v68).

## 1. Hiện trạng (đã kiểm tra trong repo)

| Thành phần | Runtime | Phần cứng thực chạy | Nguồn kiểm tra |
| --- | --- | --- | --- |
| Phát hiện mặt | ML Kit Face Detection | do ML Kit quyết định | `FaceDetector`/engine |
| FaceNet 512D | ExecuTorch `org.pytorch:executorch-android:1.2.0`, file `model.pte` (~94 MB) | **CPU qua XNNPACK, fp32** | xem dưới |
| Anti-spoof (FASNet) | LiteRT 1.1.2 `Interpreter` | **CPU, 4 luồng, không XNNPACK, không GPU** (mặc định `useGpu=false`, `useXNNPack=false`) | `FaceSpoofDetector.kt` |

Bằng chứng FaceNet đang chạy XNNPACK CPU:

- `model.pte` (sha256 `06927e2f…0251e2`) có header `ET12`; chuỗi `XnnpackBackend` xuất hiện 5 lần.
  Không có delegate Vulkan/QNN thật (vài chuỗi giống "QNN"/"MPS" chỉ là byte ngẫu nhiên trong trọng số).
- URL tải: `…/shubhxm0204/facenet-executorch/resolve/main/vggface2-inception-resnetv1-xnnpack-fp32`.
- AAR `executorch-android-1.2.0` chỉ có `jni/arm64-v8a/libexecutorch.so` (8.5 MB) và bản x86_64;
  trong đó chỉ có `XnnpackBackend`, không có Vulkan hay QNN.
- Input: tensor `[1, 160, 160, 3]` float, giá trị pixel thô 0–255 (RGB). Việc chuẩn hoá có nằm trong
  graph hay không: **chưa kiểm chứng**.

Nguồn gốc model:

- Model card trên HuggingFace trống, không có script export. Commit upstream `b71dd16`
  ("Use ExecuTorch") và `f8c8f51` (task tải model) không ghi cách export.
- Tên file gợi ý InceptionResnetV1 huấn luyện trên VGGFace2 (kiểu `facenet-pytorch`): **chưa kiểm chứng**.
- Trước `f6405d0`, repo dùng `facenet.tflite`/`facenet_512.tflite` từ `deepface` (Keras → TFLite fp16,
  có script trong README cũ, commit `e8d51c9`) và đã bật `GpuDelegate`/NNAPI. Đây là model **khác**
  (trọng số khác, ngưỡng khác: upstream từng hạ ngưỡng 0.4 → 0.3 ở `f619051`), nên đổi về TFLite
  nghĩa là phải đăng ký lại khuôn mặt và hiệu chỉnh lại ngưỡng.

Chưa có số đo độ trễ trên máy thật (chưa cài APK). Mọi so sánh tốc độ dưới đây là **chưa kiểm chứng**
cho tới khi đo `inference_ms` trên Xperia.

## 2. Các phương án

### A. Giữ ExecuTorch, giữ XNNPACK CPU (hiện tại)

- Không đổi gì. Ổn định, không phụ thuộc driver GPU/NPU.
- Biến thể rẻ: export lại bản **int8 (XNNPACKQuantizer)** để giảm kích thước (~4 lần) và thường nhanh hơn
  trên CPU ARM. Cần script export + bộ ảnh hiệu chỉnh; độ chính xác embedding phải kiểm lại. **Chưa kiểm chứng**.
- Nguồn: https://docs.pytorch.org/executorch/stable/backends-xnnpack.html

### B. ExecuTorch + Vulkan (GPU Adreno 660)

- Backend ET-VK hướng tới GPU Android, cần Vulkan 1.1; hỗ trợ FP32/FP16; lượng tử hoá hiện chỉ cho
  linear, **chưa có conv lượng tử** (FaceNet chủ yếu là conv). Đang "active development".
- Cần export lại `.pte` với `VulkanPartitioner` (op không hỗ trợ sẽ rơi về CPU).
- Android: thay `executorch-android` bằng `executorch-android-vulkan`. Đã kiểm AAR 1.2.0: 9.6 MB,
  `libexecutorch.so` arm64 14 MB, chứa **cả** `VulkanBackend` và `XnnpackBackend` → có thể giữ XNNPACK làm fallback.
  APK tăng khoảng +5.5 MB/ABI (so với AAR hiện tại, ước tính từ kích thước `.so`).
- Không thể gộp nhiều AAR backend (trùng class); muốn Vulkan + QNN cùng lúc phải tự build AAR.
- Rủi ro: InceptionResnetV1 phủ op Vulkan đầy đủ hay không, và nhanh hơn XNNPACK trên Adreno 660 hay không:
  **chưa kiểm chứng**. GPU cũng chịu ảnh hưởng nhiệt khi chạy 24/7.
- Nguồn: https://docs.pytorch.org/executorch/stable/backends/vulkan/vulkan-overview.html ,
  https://docs.pytorch.org/executorch/stable/using-executorch-android.html ,
  https://github.com/pytorch/executorch/issues/18683

### C. ExecuTorch + QNN (Hexagon NPU)

- Tài liệu nói đã kiểm trên SM8550/SM8450. `QcomChipset` **có** `SM8350` (HTP v68, VTCM 4 MB) trong
  `qc_schema.py`, nhưng chạy thật trên SM8350: **chưa kiểm chứng**.
- Cần Qualcomm QNN SDK (khuyến nghị 2.37.0) trên host **Linux/Windows/WSL** (không phải macOS) để export;
  `.pte` biên dịch **riêng cho từng SoC**. Lượng tử 8a8w (mặc định) hoặc fp16.
- Android: `executorch-android-qnn` 1.2.0 (3.3 MB, có `libqnn_executorch_backend.so` + XNNPACK) kéo theo
  `com.qualcomm.qti:qnn-runtime:2.37.0` (AAR ~52 MB) → APK tăng mạnh. Phiên bản SDK lúc export và runtime phải khớp.
- Đây là phương án có tiềm năng tiết kiệm điện/nhiệt nhất cho chạy 24/7, nhưng tốn công nhất và rủi ro nhất.
- Nguồn: https://docs.pytorch.org/executorch/stable/backends-qualcomm.html ,
  https://github.com/pytorch/executorch/blob/main/backends/qualcomm/serialization/qc_schema.py

### D. Quay lại LiteRT (TFLite) với GPU/NPU

- LiteRT 2.x có `CompiledModel` API: GPU (OpenCL/OpenGL, engine ML Drift, Google báo ~1.4x so với GPU
  delegate cũ) và NPU qua Qualcomm AI Engine Direct. Danh sách SoC Qualcomm được LiteRT liệt kê bắt đầu từ
  **SM8450**; SM8350 không có trong danh sách → NPU qua LiteRT trên Xperia 5 III: **chưa kiểm chứng**, khả năng thấp.
- GPU delegate kiểu cũ và NNAPI đã deprecated; NNAPI deprecated từ Android 15 và theo Google sẽ bị gỡ khỏi
  LiteRT từ 2027 → **không** nên dựa vào NNAPI.
- Cần model `.tflite` khác (vd. FaceNet512 của deepface, fp16) → phải đăng ký lại mặt và chỉnh ngưỡng;
  hoặc chuyển model hiện tại sang TFLite (vd. qua ai-edge-torch): **chưa kiểm chứng**.
- Nguồn: https://developers.google.com/edge/litert/next/gpu ,
  https://developers.google.com/edge/litert/next/qualcomm ,
  https://developers.google.com/edge/litert/android ,
  https://developers.googleblog.com/en/smaller-apps-and-faster-gpu-inference-with-litert-in-google-play-services/ ,
  https://developer.android.com/ndk/guides/neuralnetworks/migration-guide

### E. Anti-spoof

- Model nhỏ, đang chạy CPU không XNNPACK. Bật `useXNNPack = true` là thay đổi một dòng, không đổi dependency
  (ngoài phạm vi lượt này). Lợi ích thực: **chưa kiểm chứng**. Chỉ đáng làm nếu đo thấy spoof chiếm phần lớn thời gian.

## 3. So sánh nhanh

| Phương án | Công sức | Đổi model/đăng ký lại | APK tăng | Hỗ trợ SM8350 | Rủi ro |
| --- | --- | --- | --- | --- | --- |
| A. XNNPACK fp32 (hiện tại) | 0 | không | 0 | có (đang dùng) | thấp |
| A'. XNNPACK int8 | thấp–vừa | export lại, kiểm độ chính xác | giảm | có | thấp–vừa |
| B. Vulkan | vừa | export lại | ~+5.5 MB | Vulkan 1.1 có; hiệu năng chưa kiểm chứng | vừa |
| C. QNN | cao | export lại per-SoC, int8/fp16 | ~+50 MB | có trong enum; chưa kiểm chứng | cao |
| D. LiteRT GPU | vừa–cao | model khác, đăng ký lại | nhỏ | GPU có; NPU khả năng không | vừa |

## 4. Khuyến nghị

1. **Giữ XNNPACK CPU** cho bản đầu tiên. Ưu tiên cài APK và đo thật: `inference_ms` trong response,
   nhiệt độ/thermal status trên dashboard, ở tải thật (vài ảnh/phút từ Frigate). Với tải homelab, CPU
   rất có thể đã đủ (**chưa kiểm chứng**).
2. Chỉ khi số đo cho thấy chậm hoặc nóng: thử **A' (int8 XNNPACK)** trước, rồi **B (Vulkan)** — cả hai
   giữ cùng model gốc nên ít phải đăng ký lại (vẫn phải kiểm độ lệch embedding).
3. **QNN** để sau cùng, làm như thí nghiệm riêng trên một nhánh khác, cần host Linux có QNN SDK.
4. **Không** dùng NNAPI. Không quay lại TFLite chỉ để có GPU, trừ khi B thất bại.

Điều kiện tiên quyết cho mọi phương án B/C/A': cần **script export** tái tạo được `model.pte` hiện tại
(nguồn trọng số, chuẩn hoá input). Hiện chưa có → việc đầu tiên nếu muốn tăng tốc là tìm/viết lại script đó.

## 5. Chưa làm

- Chưa đo trên máy thật; chưa export thử bất kỳ `.pte` nào; chưa đổi dependency.
- Chưa xác nhận nguồn trọng số và bước chuẩn hoá của `model.pte`.
