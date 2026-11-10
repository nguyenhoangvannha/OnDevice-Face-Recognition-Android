# CI và phát hành APK (GitHub Actions)

Hai workflow trong `.github/workflows/`:

| File | Khi nào chạy | Làm gì |
| --- | --- | --- |
| `ci.yml` | push lên `main` hoặc `feature/**`, pull request, chạy tay | `testDebugUnitTest` + `assembleDebug`; test client Python trên 3.8 và 3.12 |
| `release.yml` | push tag `v*`, hoặc chạy tay với input `tag` (tag đã có) | Build APK release đã ký, kiểm chữ ký, đưa lên GitHub Release |

`release.yml` cũ của upstream (`actions/create-release@v1`, `upload-release-asset@v1` đã ngừng bảo trì, chạy `./gradlew build`
rồi `assembleRelease`) được **thay thế**. Tên secret giữ nguyên như upstream để không phải đổi thói quen.

## Secret anh cần thêm

Settings → Secrets and variables → Actions → New repository secret (repo `nguyenhoangvannha/OnDevice-Face-Recognition-Android`).
Em không tạo và không đọc giá trị nào.

| Tên | Nội dung |
| --- | --- |
| `KEYSTORE_BASE_64` | File keystore (`.jks`/PKCS12) mã hoá base64 một dòng, ví dụ `base64 -i release.jks \| tr -d '\n'` trên macOS |
| `RELEASE_KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `RELEASE_KEYSTORE_ALIAS` | Alias của khoá ký |
| `RELEASE_KEY_PASSWORD` | Mật khẩu khoá (với PKCS12 thường trùng mật khẩu keystore) |

Tạo keystore (nếu chưa có), làm trên máy anh, giữ bản sao an toàn (mất khoá thì không cập nhật đè được APK đã cài):

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias <alias> -keyalg RSA -keysize 4096 -validity 10000
```

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

```bash
git tag v0.1.0 && git push origin v0.1.0     # anh tự làm; em không tạo tag/release
```

Kết quả: Release `v0.1.0` (notes tự sinh) có `FaceNet-Coprocessor-v0.1.0.apk` và file `.sha256`. Chạy lại cho tag đã có:
Actions → Release APK → Run workflow → nhập tag (ghi đè asset cùng tên).

`versionName`/`versionCode` trong Gradle vẫn là `0.0.1`/`1` như upstream: chưa tự lấy từ tag.

## Đã kiểm tra trên MacBook

- `actionlint` 2 workflow: sạch (sau khi sửa một cảnh báo shellcheck SC2012).
- `REQUIRE_RELEASE_SIGNING=true ./gradlew help` không có biến → lỗi như mong đợi.
- `assembleRelease` không biến → `app-release-unsigned.apk`.
- `assembleRelease` với một keystore **dùng một lần** (tạo trong thư mục tạm, mật khẩu ngẫu nhiên không in ra, xoá ngay sau
  đó; không phải khoá của anh) → `apksigner verify`: Verifies, v2 scheme, 1 signer.
