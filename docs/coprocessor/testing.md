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
| `coprocessor/server/FaceApiServerTest` | Ktor CIO thật trên cổng local: health 200, 401/200 theo token, stop; báo lỗi khi port bị chiếm |
| `coprocessor/TokenUtilsTest` | token 43 ký tự URL-safe, ngẫu nhiên; so khớp, null, rỗng |
| `coprocessor/CoprocessorStateHolderTest` | log giữ N request mới nhất, đếm tổng |

Test REST chạy với backend giả, nên **không** kiểm model thật, ML Kit hay ObjectBox.

## Client Python

```bash
cd clients/python && python3 -m unittest -v test_xperia_face_client
```

7 test với server HTTP giả: bắt buộc biến môi trường, `repr` không lộ token, multipart recognize/enroll, lỗi 401/404,
`recognize_snapshot` trả `None` khi timeout hoặc không kết nối được.

## Kiểm thử trên máy thật (chưa làm)

```bash
export XPERIA_FACE_URL=http://<ip-điện-thoại>:8080 XPERIA_FACE_TOKEN=...
curl -s "$XPERIA_FACE_URL/api/health"                                         # 200, không cần token
curl -s -o /dev/null -w '%{http_code}\n' "$XPERIA_FACE_URL/api/status"         # 401
python3 clients/python/xperia_face_client.py status
python3 clients/python/xperia_face_client.py enroll "An" an1.jpg an2.jpg
time python3 clients/python/xperia_face_client.py recognize snapshot.jpg       # so inference_ms và tổng thời gian
```

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
