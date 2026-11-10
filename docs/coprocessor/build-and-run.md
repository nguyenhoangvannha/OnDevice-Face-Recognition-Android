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
