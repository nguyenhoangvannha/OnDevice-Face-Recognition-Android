# Trang web thử nghiệm (GET /)

Ktor phục vụ một trang HTML/JS/CSS thuần tại `http://<ip-điện-thoại>:<port>/`, để thử API từ trình duyệt
trên máy cùng mạng mà không cần curl.

- File: `app/src/main/resources/coprocessor-web/` (`index.html`, `app.js`, `app.css`), đóng gói trong APK dưới dạng
  Java resource, đọc bằng `ClassLoader.getResourceAsStream` (`coprocessor/server/WebAssets.kt`).
- Chỉ 3 đường dẫn cố định: `/`, `/app.js`, `/app.css`. Không liệt kê thư mục; đường dẫn khác → 404.
- Không CDN, không font/ảnh ngoài, không request ra ngoài: chạy được khi không có Internet.
- Tải trang **không** cần token. Mọi lời gọi API trong trang gửi token qua header `Authorization: Bearer`.

## Token

- Dán token (lấy trên dashboard app) vào ô Token → lưu trong `sessionStorage` của tab, mất khi đóng tab.
- Không bao giờ đặt token vào URL, cookie hay `localStorage`. `fetch` dùng `credentials: "omit"`.
- Nút **Forget** xoá token khỏi tab.

## Chức năng

| Khối | Gọi API |
| --- | --- |
| Status | `GET /api/status`: pin, nhiệt độ pin, thermal status, heap, số người, ngưỡng, uptime |
| Threshold | `GET`/`PUT /api/settings/threshold`; lưu cùng chỗ với slider trong app (dashboard tự cập nhật sau ≤ 5 s) |
| Recognize | `POST /api/recognize` (tuỳ chọn liveness), vẽ khung lên ảnh: xanh = người quen, đỏ = lạ, nhãn `tên similarity`; hiện `inference_ms`, thời gian khứ hồi, JSON thô |
| Persons | `GET /api/persons`; nút Delete có hộp xác nhận → `DELETE /api/persons/{id}` |
| Enroll | `POST /api/enroll` (tên + 1–10 ảnh) |
| Add images | `POST /api/persons/{id}/images` |

Toạ độ khung là theo ảnh server đã decode (xoay theo EXIF, có thể subsample về 1920 px); trang co giãn theo
`image_width`/`image_height` trong kết quả. Trình duyệt hiện đại cũng xoay theo EXIF khi vẽ, nên hai bên khớp
(trên trình duyệt cũ: **chưa kiểm chứng**).

## Bảo mật

- Cùng origin: trang và API cùng host:port. **Không cài CORS**; test kiểm rằng không có `Access-Control-Allow-Origin`
  kể cả khi request có `Origin` lạ hoặc là preflight `OPTIONS`.
- Header trên **mọi** response (trang và API):

| Header | Giá trị |
| --- | --- |
| `Content-Security-Policy` | `default-src 'self'; img-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `no-referrer` |

  `img-src blob:` cần để vẽ ảnh vừa chọn (object URL) lên canvas. Không có script/style inline (CSP chặn).
- Dữ liệu từ server (tên người…) chỉ được chèn bằng `textContent` hoặc vẽ lên canvas, không dùng `innerHTML`.
- HTTP thường, không TLS: chỉ dùng trong LAN/Tailscale. Ai nghe lén được mạng thì thấy token, như với mọi client khác.

## Kiểm thử

- `FaceApiModuleTest.webPageIsServedWithoutTokenAndWithSecurityHeaders`: `/` 200 HTML không cần token, không có URL ngoài,
  CSP + nosniff; `/app.js`, `/app.css` đúng content-type; `/index.html` và `/../build.gradle.kts` → 404.
- `noCorsHeadersEvenWithOrigin`, `thresholdApi`.
- `node --check app.js` qua; mọi `id` mà JS dùng đều có trong HTML.
- **Chưa** mở trang trong trình duyệt thật với server chạy trên điện thoại (cần cài APK).
