# haebom-senior

해봄 고령자용 Android 앱입니다.

## 개발 환경

- JDK 21
- Android SDK Platform 35
- 최소 지원 버전 Android 8.0 (API 26)
- Gradle Wrapper 8.11.1

Android Studio에서 저장소 폴더를 열고 Gradle JDK를 21로 설정합니다. 터미널에서는 Android SDK를 `ANDROID_HOME`으로 지정한 뒤 디버그 APK를 빌드할 수 있습니다.

```sh
./gradlew :app:assembleDebug
```

Windows에서는 `gradlew.bat :app:assembleDebug`를 사용합니다. 빌드 결과는 `app/build/outputs/apk/debug/`에 생성됩니다.

현재 앱에는 초기 화면이 있습니다. 첫 기능은 접근성 서비스에서 현재 화면을 감지하고, 사용자의 터치를 막지 않으면서 안내를 갱신하는 것입니다.
