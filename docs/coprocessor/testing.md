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

## Tiêu chí nghiệm thu (từ kế hoạch, tất cả **chưa kiểm chứng** trên máy thật)

| Hạng mục | Điều kiện đạt |
| --- | --- |
| Độ trễ suy luận | < 100 ms cho ảnh 720p/1080p |
| Độ trễ toàn trình | < 350 ms qua Wi-Fi |
| Chạy nền 24/7 | 72 giờ không bị Android giết khi tắt màn hình |
| Tự phục hồi | Tự chạy lại sau reboot (`BOOT_COMPLETED`) |
| Độ chính xác | Nhận đúng 5–10 người quen ảnh đủ sáng; 0% nhận nhầm người lạ ở ngưỡng đã chọn |
| Nhiệt độ | Pin < 40 °C khi chờ và khi có sự kiện thường |
