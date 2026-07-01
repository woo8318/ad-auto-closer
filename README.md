# ad-auto-closer

전면광고(interstitial)가 **끝나면 자동으로 닫기(X) 버튼을 눌러주는** 안드로이드 앱.

> 광고를 차단/스킵하는 앱이 아님. 광고는 끝까지 보고, 닫는 동작만 자동화한다.

## 핵심 요약

- **기술**: 안드로이드 접근성 서비스(AccessibilityService)
- **언어/빌드**: Kotlin + Gradle
- **개발 방식**: 로컬 개발환경 없음 → **GitHub Actions에서 APK 빌드**
- **테스트 기기**: 갤럭시 S24 (Android 14 / One UI 6.1)

## 문서

| 파일 | 내용 |
|------|------|
| [docs/01-concept.md](docs/01-concept.md) | 기획 정리 (목표·원리·감지 전략·한계) |
| [docs/02-build-and-test-workflow.md](docs/02-build-and-test-workflow.md) | 빌드·설치·테스트 전체 워크플로우 |

## 작업 시작점

1. 이 폴더를 에디터로 연다
2. [docs/02-build-and-test-workflow.md](docs/02-build-and-test-workflow.md) 의 단계대로 진행
3. 첫 목표: Android 프로젝트 스캐폴딩 + GitHub Actions 빌드 파이프라인 구성

## 다음 단계 (TODO)

- [x] Android(Kotlin) 프로젝트 스캐폴딩 (Gradle)
- [x] GitHub Actions 워크플로우(`.github/workflows/build.yml`) 작성 → debug APK artifact 생성
- [x] `AccessibilityService` 1차 감지 로직 (텍스트 + contentDescription)
- [x] Release 워크플로우(`release.yml`) — 태그 push → APK 첨부 배포
- [x] 인앱 업데이트 (실행 시 최신 Release 확인 → 다운로드/설치)
- [ ] GitHub 리포 생성 + 코드 푸시 → Actions 빌드 확인
- [ ] S24에 APK 설치 + 접근성 서비스 활성화
- [ ] 실기기 테스트 → 안 닫히는 광고 기록 → 감지 규칙 보완
- [ ] (후순위) 위치 기반 탭 / 화이트리스트 / 이미지 인식

## 프로젝트 구조

```
ad-auto-closer/
├─ .github/workflows/build.yml   # CI: gradle assembleDebug → app-debug artifact
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
└─ app/
   ├─ build.gradle.kts           # com.adautocloser, minSdk 26, targetSdk 34
   └─ src/main/
      ├─ AndroidManifest.xml      # MainActivity + AdCloserService 등록
      ├─ java/com/adautocloser/
      │  ├─ MainActivity.kt        # 접근성 설정 화면 + 활성화 상태 표시
      │  └─ AdCloserService.kt     # 닫기 버튼 감지 + 자동 탭 (핵심)
      └─ res/xml/accessibility_service_config.xml
```

> **빌드 방식**: 로컬에 Gradle/Android Studio 불필요. GitHub에 push하면 Actions가
> Gradle 8.7로 직접 빌드해 `app-debug` artifact를 만든다 (wrapper jar 미포함).
> 로컬에서 빌드하려면 `gradle wrapper` 로 래퍼를 생성한 뒤 `./gradlew assembleDebug`.

## 배포 (Release) & 인앱 업데이트

**버전 배포** — 태그를 push하면 [release.yml](.github/workflows/release.yml) 이 APK를 빌드해
GitHub Release에 자동 첨부한다. 태그의 버전이 APK에 주입된다.

```bash
git tag v0.2.0
git push origin v0.2.0     # → Release "v0.2.0" 에 ad-auto-closer-v0.2.0.apk 첨부
```

**인앱 업데이트** — 앱을 켜면 GitHub의 최신 Release를 조회해서 설치된 버전보다 높으면
"새 버전 있음" 다이얼로그를 띄우고, 누르면 APK를 내려받아 설치한다.
([UpdateChecker.kt](app/src/main/java/com/adautocloser/UpdateChecker.kt) /
[ApkInstaller.kt](app/src/main/java/com/adautocloser/ApkInstaller.kt))

> ⚠️ **첫 배포 전 1줄 수정 필요**: [app/build.gradle.kts](app/build.gradle.kts) 의
> `GITHUB_OWNER` 값을 본인 GitHub 사용자명으로 바꿔야 업데이트 조회가 동작한다
> (현재 `"woo8318"` 로 가정해둠).
>
> 버전 비교는 semver(`0.2.0 > 0.1.0`). versionCode 도 태그에서 자동 산출되므로
> 같은 기기에 덮어쓰기 설치된다.
