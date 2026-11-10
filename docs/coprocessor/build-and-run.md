# Build và chạy

Trạng thái: **khung**, cập nhật theo từng bước.

## Build trên Mac (không build trên Armbian)

```bash
cd ~/AndroidStudioProjects/personal/OnDevice-Face-Recognition-Android
git checkout feature/rest-api-coprocessor
bash ./gradlew assembleDebug          # gradlew không có bit thực thi trong git
bash ./gradlew testDebugUnitTest      # unit test JVM
```

- Cần JDK 17 và Android SDK (platform 34). Lần build đầu tự tải `model.pte` (~94 MB) từ HuggingFace vào
  `app/src/main/assets/` (đã gitignore).
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Release đã ký: xem [ci-and-release.md](ci-and-release.md). `assembleRelease` không có biến ký → APK chưa ký.
- `assembleRelease` chỉ chứa thư viện `arm64-v8a` (~153 MB thay vì ~225 MB). Cần bản cho mọi ABI:
  `bash ./gradlew assembleRelease -PuniversalRelease=true`. Bản debug luôn đủ ABI (chạy được trên emulator x86_64).
- Trên GitHub Release, tải `FaceNet-Coprocessor-<tag>-arm64-v8a.apk` cho Xperia.

## Cài và bật trên Xperia (chưa làm, cần anh)

1. Cài APK debug (ví dụ `adb install -r app/build/outputs/apk/debug/app-debug.apk`, hoặc copy file sang máy).
2. Mở app một lần (để `BootReceiver` hoạt động), cấp quyền camera nếu dùng màn hình camera.
3. Đăng ký người quen: màn hình Face List → Add face (cách cũ), hoặc `POST /api/enroll`.
4. Mở **Coprocessor Server** (biểu tượng máy chủ trên thanh trên cùng) → bật công tắc → cho phép thông báo.
5. Bấm **Allow running in background** (bỏ tối ưu pin).
6. Ghi lại IP `wlan0` hiện trên dashboard; đặt DHCP reservation trên router để IP không đổi (đổi router cần anh duyệt).
7. Sao chép token, lưu ở client dưới dạng biến môi trường `XPERIA_FACE_TOKEN` (không commit).
8. Kiểm tra từ máy trong LAN: `curl http://<ip>:8080/api/health`.

Lần khởi động đầu, `FaceNet` copy `model.pte` (~94 MB) từ assets sang bộ nhớ app, nên trạng thái "Starting" có thể kéo dài vài giây.
