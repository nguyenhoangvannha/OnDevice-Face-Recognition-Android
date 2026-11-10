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
| `coprocessor/server/FaceApiModuleTest` | health không cần token và không lộ thông tin máy; 401 khi thiếu/sai token; recognize trả snake_case + ghi log; 400 thiếu ảnh / ảnh hỏng / ảnh rỗng; 413 quá cỡ; 415 không phải multipart; enroll 400/422/201; persons list; delete 400/404/200 |
| `coprocessor/TokenUtilsTest` | token 43 ký tự URL-safe, ngẫu nhiên; so khớp, null, rỗng |
| `coprocessor/CoprocessorStateHolderTest` | log giữ N request mới nhất, đếm tổng |

Test REST chạy với backend giả, nên **không** kiểm model thật, ML Kit hay ObjectBox.

## Tiêu chí nghiệm thu (từ kế hoạch, tất cả **chưa kiểm chứng** trên máy thật)

| Hạng mục | Điều kiện đạt |
| --- | --- |
| Độ trễ suy luận | < 100 ms cho ảnh 720p/1080p |
| Độ trễ toàn trình | < 350 ms qua Wi-Fi |
| Chạy nền 24/7 | 72 giờ không bị Android giết khi tắt màn hình |
| Tự phục hồi | Tự chạy lại sau reboot (`BOOT_COMPLETED`) |
| Độ chính xác | Nhận đúng 5–10 người quen ảnh đủ sáng; 0% nhận nhầm người lạ ở ngưỡng đã chọn |
| Nhiệt độ | Pin < 40 °C khi chờ và khi có sự kiện thường |
