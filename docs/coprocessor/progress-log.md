# Nhật ký triển khai

Mỗi bước: code, build (`bash ./gradlew assembleDebug`), unit test nếu có, commit, push lên
`origin/feature/rest-api-coprocessor`. Giờ ghi theo Asia/Saigon (UTC+7).

## 2026-11-10 — Bước 0: khảo sát

- `origin` = `git@github.com-personal:nguyenhoangvannha/OnDevice-Face-Recognition-Android.git` (fork cá nhân, không phải upstream).
- Nhánh mặc định `main`, HEAD `f6405d0 core: remove unused FaceNet TFLite files`.
- Máy build: MacBook, JDK Zulu 17.0.16, Android SDK `~/Library/Android/sdk` (platforms tới 37, build-tools 30–36).
- Build gốc `bash ./gradlew assembleDebug`: **thành công** sau 3 phút 31 giây (lần đầu tải Gradle 8.6 và `model.pte` ~94 MB). APK debug ~225 MB (nhiều ABI + model).
- Kết quả khảo sát và khác biệt với kế hoạch: [README.md](README.md).

## 2026-11-10 — Bước a: docs scaffold

- Thêm `docs/coprocessor/` (README, api, build-and-run, configuration-and-security, testing, progress-log).
- `.gitignore`: bỏ qua `app/src/main/assets/model.pte` (file tải lúc build, không commit).
- Các tài liệu mô tả thiết kế mục tiêu; phần nào chưa có code được đánh dấu "chưa".

Commit: `f384910 docs(coprocessor): add design docs, repo survey and progress log`.

## 2026-11-10 — Bước b: FaceRecognitionEngine

- Thêm package `engine/` (xem [README.md](README.md#lõi-nhận-diện-engine)).
- Sửa lỗi upstream `flatSearch` với DB < 4 bản ghi; thêm khoá suy luận cho FaceNet/anti-spoof.
- Thêm `testImplementation("junit:junit:4.13.2")` và `SimilarityMathTest` (8 test).
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**, 8/8 test qua.
- Chưa chạy trên máy thật.

Commit: `f318b35 feat(engine): add FaceRecognitionEngine decoupled from CameraX`.

## 2026-11-10 — Bước c: Ktor REST server

- Thêm Ktor 2.3.12 (server-core, cio, content-negotiation, serialization-kotlinx-json, auth, status-pages) và plugin
  `org.jetbrains.kotlin.plugin.serialization` 2.0.0 (cùng version Kotlin của repo). Test: `ktor-server-test-host`.
- Ktor 2.3.12 kéo `kotlinx-serialization-json` 1.5.1, `kotlinx-coroutines-core` 1.7.3. Không có xung đột class (`checkDebugDuplicateClasses` qua).
- Lưu ý API: ở Ktor 2.3 `PartData.FileItem.streamProvider()` là extension, phải import `io.ktor.http.content.streamProvider`.
- Loại `coprocessor_settings.xml` khỏi backup.
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**; 17/17 test qua
  (SimilarityMath 8, FaceApiModule 6, TokenUtils 2, CoprocessorStateHolder 1).
- Server chưa được khởi động ở đâu (bước d).

Commit: `226753b feat(api): add Ktor CIO REST API with bearer auth`.

## 2026-11-10 — Bước d: foreground service

- Thêm `CoprocessorService` (type `specialUse`), `BootReceiver`, quyền trong manifest. Lý do chọn type: [configuration-and-security.md](configuration-and-security.md#chạy-nền-247-coprocessorservice).
- Kiểm tra APK bằng `aapt2 dump xmltree`: service có `foregroundServiceType=0x40000000` (specialUse) và property subtype; đủ quyền.
- `FaceApiServer.start()` giờ chờ `resolvedConnectors()`. Test "port đã bị chiếm" ban đầu **không** ném lỗi
  (khi socket chiếm port bind `0.0.0.0`, server bind `127.0.0.1` trên macOS). Sau khi sửa (chờ bind + socket test
  bind đúng `127.0.0.1`) test qua; chưa tách riêng yếu tố nào quyết định.
- Thêm `FaceApiServerTest`: chạy CIO thật trên JVM, gọi `/api/health` (200), `/api/persons` không token (401) và có token (200), dừng server.
- Bỏ cảnh báo opt-in `explicitNulls` (`@OptIn(ExperimentalSerializationApi::class)`).
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**; 19/19 test qua.
- Chưa chạy trên Xperia (không đụng điện thoại trong lượt này).

Commit: `bcd6ffe feat(service): run the API in a specialUse foreground service`.

## 2026-11-10 — Bước e: dashboard Compose

- Thêm route `coprocessor` trong `MainActivity`, nút mở trên `DetectScreen` (thêm tham số `onOpenCoprocessorClick`).
- `CoprocessorViewModel` (`@KoinViewModel`): KSP sinh binding cho `CoprocessorViewModel`, `AndroidCoprocessorBackend`,
  `CoprocessorSettings`, `FaceRecognitionEngine` (đã kiểm tra trong `build/generated/ksp`).
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**; 19/19 test qua. Chưa có UI test; chưa xem trên máy thật.

Commit: `264a040 feat(ui): add Coprocessor Server dashboard`.

## 2026-11-10 — Bước f: client Python

- Thêm `clients/python/xperia_face_client.py` (stdlib, Python 3.7+), `test_xperia_face_client.py`, `README.md`.
- URL/token/timeout lấy từ `XPERIA_FACE_URL`, `XPERIA_FACE_TOKEN`, `XPERIA_FACE_TIMEOUT`; không có secret trong code; `repr()` không lộ token.
- `recognize_snapshot()` trả `None` khi timeout/mất mạng/lỗi API (thay cho kế hoạch dùng `requests` và IP/token hardcode).
- `python3 -m unittest -v test_xperia_face_client` trên Mac (Python 3.13.6): 7/7 qua. Chưa chạy trên Python 3.7 hay trên Armbian.
- Thêm ghi chú fork ở đầu `README.md` gốc.

## Còn lại (chưa làm)

- Cài APK lên Xperia, bật server, đo độ trễ, nhiệt độ, chạy 72 giờ, thử reboot (cần anh; lượt này không đụng điện thoại).
- Hiệu chỉnh ngưỡng bằng ảnh camera thật.
- Tích hợp phía homelab (notifier/Frigate trên Mac Mini hoặc Armbian Phố): chưa làm, cần anh chọn.
- Quyết định có loại DB ObjectBox (embedding khuôn mặt) khỏi Android backup không.
- Chưa có UI test.

Commit: `745d416 feat(client): add stdlib Python client for the coprocessor API`.

# Vòng 2

## 2026-11-10 — Bước 1: thêm ảnh cho người đã có

- `POST /api/persons/{id}/images` (bearer, 1..10 ảnh, cùng giới hạn kích thước; 404 id lạ; 422 không ảnh nào có đúng 1 mặt; 200 khi thêm được).
- `FaceRecognitionEngine.addImages()` dùng chung bước "1 mặt / ảnh → embedding" với enroll; cập nhật `num_images` của người.
- Client Python: `add_images()` + lệnh `add-images`.
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**, 20/20 test; client Python 8/8.

Commit: `1704c6f feat(api): add POST /api/persons/{id}/images`.

## 2026-11-10 — Bước 2: trang web thử nghiệm + API ngưỡng

- Thêm `app/src/main/resources/coprocessor-web/` (HTML/JS/CSS thuần, ~14 KB) và `WebAssets.kt`; route `/`, `/app.js`, `/app.css`.
- Thêm `GET`/`PUT /api/settings/threshold` (bearer). Dashboard đọc lại ngưỡng mỗi 5 s (trừ lúc đang kéo slider) để thấy thay đổi từ web.
- Header bảo mật trên mọi response; không CORS. Dashboard ghi chú mở địa chỉ trong trình duyệt.
- Kiểm tra APK debug có `coprocessor-web/{index.html,app.js,app.css}`.
- `bash ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**, 23/23 test. `node --check app.js` qua.
- Chưa mở trang trong trình duyệt thật (cần server chạy trên điện thoại).

Commit: `a98de7e feat(web): serve offline test page at / and add threshold API`.

## 2026-11-10 — Bước 3: GitHub Actions

- Thay `release.yml` upstream; thêm `ci.yml`. Chi tiết, tên secret, cách xử lý model: [ci-and-release.md](ci-and-release.md).
- `app/build.gradle.kts`: ký release chỉ khi đủ biến môi trường; `REQUIRE_RELEASE_SIGNING`. `.gitignore` chặn `*.jks`, `*.keystore`.
- Kiểm tra: actionlint sạch; debug build + 23 test qua; release chưa ký build được; release ký bằng khoá dùng một lần verify được.
- Repo fork: Actions bật (`enabled: true`), chưa có secret nào (`gh secret list` rỗng) → release sẽ fail rõ cho tới khi anh thêm.
