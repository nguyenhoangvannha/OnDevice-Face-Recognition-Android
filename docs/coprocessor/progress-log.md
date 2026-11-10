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
