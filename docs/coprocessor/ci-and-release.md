# CI và phát hành APK (GitHub Actions)

Hai workflow trong `.github/workflows/`:

| File | Khi nào chạy | Làm gì |
| --- | --- | --- |
| `ci.yml` | push lên `main` hoặc `feature/**`, pull request, chạy tay | `testDebugUnitTest` + `assembleDebug`; test client Python trên 3.8 và 3.12 |
| `release.yml` | push tag `v*`, hoặc chạy tay (input `tag` tuỳ chọn, tự tạo tag nếu chưa có) | Build APK release đã ký, kiểm chữ ký, đưa lên GitHub Release |

`release.yml` cũ của upstream (`actions/create-release@v1`, `upload-release-asset@v1` đã ngừng bảo trì, chạy `./gradlew build`
rồi `assembleRelease`) được **thay thế**. Tên secret giữ nguyên như upstream để không phải đổi thói quen.

## Secret (đã đặt 2026-11-10)

Theo yêu cầu của anh, em đã tạo keystore và đặt 4 secret trên repo `nguyenhoangvannha/OnDevice-Face-Recognition-Android`
(`gh secret set` đọc từ file qua stdin; không giá trị nào in ra hay nằm trong git):

| Tên | Nội dung |
| --- | --- |
| `KEYSTORE_BASE_64` | `release.jks` (PKCS12) mã hoá base64 một dòng |
| `RELEASE_KEYSTORE_PASSWORD` | Mật khẩu keystore (ngẫu nhiên, `openssl rand`) |
| `RELEASE_KEYSTORE_ALIAS` | Alias khoá ký |
| `RELEASE_KEY_PASSWORD` | Mật khẩu khoá (PKCS12: trùng mật khẩu keystore) |

Khoá: RSA 4096, SHA384withRSA, hạn 10000 ngày (tới 2054-03-28), `CN=NHVN Face Coprocessor`.

**Bản gốc nằm ngoài mọi repo git** trên MacBook: `~/.secrets/face-coprocessor/` (thư mục 700, file 600): `release.jks`,
`alias.txt`, `store_password.txt`, `key_password.txt`, `keystore.b64`, `README.txt`. Anh nên sao lưu thư mục này
(ví dụ vào trình quản lý mật khẩu): mất khoá thì APK sau không cài đè được APK đã ký trước.

Đổi khoá sau này: tạo keystore mới rồi `gh secret set <TÊN> < file` cho từng secret.

Không commit keystore: `.gitignore` đã chặn `*.jks`, `*.keystore`.

## Thiếu secret thì sao

Quyết định: **dừng ngay, báo lỗi rõ**, không phát hành APK chưa ký (APK release chưa ký không cài được, và một bản debug
gắn vào Release dễ gây nhầm). Bước đầu của `release.yml` liệt kê tên secret còn thiếu (`::error::Missing repository secrets: ...`)
trước khi checkout/build. Muốn có APK để thử: tải từ máy build (`assembleDebug`) hoặc chạy CI.

## Ký trong Gradle (`app/build.gradle.kts`)

- Đọc `RELEASE_KEYSTORE_PATH` (mặc định `keystore.jks` ở gốc repo), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEYSTORE_ALIAS`,
  `RELEASE_KEY_PASSWORD` từ biến môi trường.
- Đủ cả 4 → `signingConfigs.release` được tạo và dùng cho bản release. Thiếu → không có signingConfig, `assembleRelease`
  ra `app-release-unsigned.apk`; build debug không bị ảnh hưởng (trước đây upstream luôn trỏ `../keystore.jks`).
- `REQUIRE_RELEASE_SIGNING=true` (workflow release đặt) → thiếu ký là lỗi Gradle ngay khi cấu hình, có liệt kê biến rỗng.
- Workflow giải mã keystore vào `$RUNNER_TEMP`, build, `apksigner verify`, rồi xoá keystore (`if: always()`).

## model.pte trong CI

Task Gradle `downloadModel` (gắn vào `preBuild`) dùng `ant.get` tải từ HuggingFace nếu file chưa có, không kiểm checksum,
và URL là nhánh `main` (có thể đổi). Trong CI:

1. `actions/cache` khoá `facenet-model-<sha256>`.
2. Cache miss → `curl -fL --retry 3` tải về đúng đường dẫn.
3. Luôn kiểm `sha256sum -c` với `06927e2f1e272686258d57fe6a8abaf8efce7e61a26d7b13e6ba40fa130251e2` (file tải trên MacBook ngày 2026-11-10).
4. File đã có nên Gradle bỏ qua bước tải.

Nếu upstream đổi model trên HuggingFace, CI sẽ fail ở bước checksum: đó là chủ đích; cập nhật `MODEL_SHA256` trong cả hai workflow sau khi kiểm tra.

## Phát hành

Trước 2026-11-10 (vòng 3), chạy tay **không** tạo tag: input `tag` là bắt buộc và phải có sẵn (`checkout` tag và
`gh release create --verify-tag` sẽ lỗi nếu chưa có). Nay:

| Cách chạy | Tag | Prerelease |
| --- | --- | --- |
| `git push origin v0.1.0` | tag vừa push | có nếu tag chứa `-` (vd. `v0.2.0-rc1`), không thì release thường |
| Actions → Release APK → Run workflow, chọn nhánh, để trống `tag` | `v<versionName>-<yyyymmdd>.<shortsha>` (ngày UTC), vd. `v0.0.1-20261110.2bf203a` | mặc định **có** (ô `prerelease`) |
| Run workflow với `tag` = `v0.1.0` | `v0.1.0` | theo ô `prerelease` |

Quy tắc:

- Tag chưa có → được tạo (API `git/refs`) trên commit đầu nhánh đã chọn, **chỉ sau khi** APK build và `apksigner verify` qua,
  nên build lỗi không để lại tag rác. Cần `permissions: contents: write` (đã khai báo).
- Tag đã có trên **đúng** commit đó → build lại, ghi đè asset cùng tên.
- Tag đã có trên commit **khác** → dừng với lỗi `Tag ... already exists on <sha>`.
- Tag phải khớp `^v[0-9A-Za-z._+-]+$`.
- Tag do `GITHUB_TOKEN` tạo không kích hoạt lại workflow (không chạy đúp).

Kết quả: Release có `FaceNet-Coprocessor-<tag>.apk` và `.sha256`; link Release ghi vào Summary của lần chạy.

`versionName`/`versionCode` trong Gradle vẫn là `0.0.1`/`1` như upstream: chưa tự lấy từ tag.

## Đã kiểm tra trên MacBook

- `actionlint` 2 workflow: sạch (sau khi sửa một cảnh báo shellcheck SC2012).
- `REQUIRE_RELEASE_SIGNING=true ./gradlew help` không có biến → lỗi như mong đợi.
- `assembleRelease` không biến → `app-release-unsigned.apk`.
- `assembleRelease` với một keystore **dùng một lần** (tạo trong thư mục tạm, mật khẩu ngẫu nhiên không in ra, xoá ngay sau
  đó; không phải khoá của anh) → `apksigner verify`: Verifies, v2 scheme, 1 signer.
