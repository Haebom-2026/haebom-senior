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

## 화면 안내 연습

1. 앱에서 **접근성 설정 열기**를 누르고 **해봄 화면 안내** 서비스를 켭니다.
2. 앱으로 돌아와 **화면 안내 연습**을 엽니다. **다음** 버튼에 빨간 테두리가 표시됩니다.
3. 표시된 버튼을 누르면 테두리가 **연습 끝내기** 버튼으로 이동합니다. 연습을 끝내거나 다른 앱으로 이동하면 테두리가 사라집니다.

현재 안내 대상은 해봄 앱의 두 연습 화면입니다. 대상 버튼의 위치는 기기 안에서만 확인합니다.
