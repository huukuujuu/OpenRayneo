# 운영 가이드

목표, 모듈, 현재 완성도는 [아키텍처 사실](../.arch/ground_truth.md)과 [대시보드](../.arch/dashboard.html)에서 함께 관리한다. 이 문서는 바로 따라 할 수 있는 설치, 실행, 복구 절차만 다룬다. 이 브랜치에는 Android만 있으며, Mac Bridge의 빌드와 운영 설명은 `main` 이력에 남아 있다.

## 현재 로컬 후보

- Android: [37 release](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-release.apk), [37 debug](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-debug.apk), 버전 `1.2.0-beta.4`. CI 버전 이름은 `1.(run-23).0`, 업로드 파일 이름은 `openrayneo-<버전>-<sha7>.apk`.
- DSH: [플러그인 0.1.8](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/openrayneo-dsh-0.1.8.tgz). Android 36 이후로 이 플러그인 버전을 계속 쓴다.
- Android 되돌리기용: [36 release](../android/app/build/outputs/candidates/android36-1.2.0-beta.3/OpenRayneo-v36-release.apk). 같은 서명으로 덮어 설치하면 설정과 기록이 유지되며, 개인 저장소와 Keystore도 함께 유지된다.

이것들은 개발 PC의 산출물이다. 현재 소스 빌드는 이 문서의 명령을 기준으로 하며, 저장소에는 설치 파일, 다운로드한 모델, 개인 키를 커밋하지 않는다.

## 빌드

Android는 JDK 17, SDK Platform 36 / Build-Tools 36.0.0이 필요하다. `ANDROID_HOME`이나 커밋하지 않는 `android/local.properties`로 로컬 SDK를 가리킨다.

```sh
cd android
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug -Popenrayneo.abis=arm64-v8a,x86_64 --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.openrayneo.android/.MainActivity
```

기본 Gradle 출력은 다음 빌드 때 새로 만들어진다. 고정 배포물은 `outputs/candidates/<버전>/`에 둔다. 덮어 설치를 위해 원래 개발 서명 `~/.android/debug.keystore`를 보관하며, 문서나 소스에 비밀번호를 쓰지 않는다. 시스템이 ADB 설치를 막으면 APK를 휴대폰 Download 폴더로 보낸 뒤 사용자가 직접 설치한다.

## 연결과 복구

1. 평소 전환할 때는 먼저 사용 중인 쪽에서 안경 연결을 끊고 기존 페어링은 유지한다. 휴대폰, 다른 기기, 공식 앱이 동시에 연결을 다투면 데이터 통로가 막힌다.
2. Android에서 근처 기기 권한을 허용한 뒤 대상을 고른다. 첫 페어링은 앱의 페어링 흐름을 쓰고, 시스템 안내는 사용자가 확인한다.
3. BLE 인증, RFCOMM 데이터 통로, 화면/오디오 업무 상태를 각각 확인한다.
4. `channel did not finish opening`이 나오면 진단 정보를 남기고 이번 실패 연결을 정리한다. 다른 앱이 연결을 놓았는지, 안경을 펴서 착용하고 화면이 켜졌는지 확인한 뒤 한 번 더 연결한다. 연결 버튼을 연달아 누르지 말고, 일반 복구에서 페어링 삭제나 앱 데이터 삭제를 하지 않는다.
5. 안경이 웨이크에 반응하지 않으면 기기가 반응하지 않는 것인지 휴대폰이 받지 못한 것인지 먼저 구분한다. 앱 로그의 음성 이벤트와 연결 상태를 보고, 어시스턴트/음성 웨이크/웨이크워드의 실제 보고값을 확인한다. 어시스턴트 화면이 아직 남아 있으면 먼저 시계 화면으로 돌아간다. 기본 체크 상태나 보낸 명령이 기기에서 다시 읽은 값을 대신하지 않는다.

블루투스 bond가 손상된 경우에만 다시 페어링한다. 자세한 내용은 [프로토콜](protocol.md)을 본다.

## Agent와 기록 동기화

음성 응답, 대기/힌트, 도구, 워크플로는 로컬 외부 Agent가 맡는다. OpenRayneo는 오디오, 기본 전사, 기록, 공식 통로로의 결과 회신을 맡는다.

| 진입점 | 기본 수신 | 자격 증명 |
| --- | --- | --- |
| Android 로컬 Agent API | loopback `8766`, 설정에서 켬 | 휴대폰 로컬 token |
| 기록 동기화 서비스 | 휴대폰 LAN `8767`, 따로 켬 | 전용 sync token |

`GET /health`는 token이 필요 없고 나머지 경로는 Bearer를 쓴다. 자격 증명을 출력하지 않는다.

휴대폰의 "설정 → DSH Go 및 외부 Agent"에서 로컬 Agent 엔드포인트를 켠다. DSH에서 "본 기기 / This phone"을 고르고 일반 채팅부터 확인한다. 플러그인 설치와 도구는 [플러그인 설명](../integrations/dsh-go/README.md)을 본다. tgz의 최상위 폴더는 `package/`이며, 휴대폰 DSH 터미널에서 풀어 설치한다.

```sh
mkdir -p "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
tar -xzf /sdcard/Download/openrayneo-dsh-0.1.8.tgz --strip-components=1 -C "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
dsh plugin --profile web add "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
```

처음 설치할 때 add를 실행한다. 업데이트는 같은 폴더를 교체한 뒤 DSH "엔진" 화면에서 커널을 재시작한다. 연결 동작은 `OPENRAYNEO_TOKEN` 자격 증명 서비스에 저장된다. live 네이티브 웨이크는 웨이크를 넘어 대화를 이어 쓰며, 휴대폰 어시스턴트의 "새 대화"로 명시적으로 새로 연다. 녹음/작업/웨이크 신원은 각각 독립적이다.

기록 동기화 서비스의 커서와 파생 회신 규칙은 [동기화 계약](record-sync-contract.md)을 본다. 이 브랜치에는 그 Mac 클라이언트가 없다.

## 음성과 녹음

- 네이티브 자동 응답은 명시적으로 켜야 한다. 2채널은 기본적으로 ch1을 본인, ch2를 전체 화자로 다루며, 네이티브/종일 혼합 mono는 전부 처리한다. 수동 등록은 기본으로 실시간 ch1을 고른다.
- 휴대폰 "기록 → 기록 일정"에서 끝난 기록을 열고 "내보내기: WAV"를 누르면 원래 채널 그대로 저장된다. 지금 녹음 중인 기록은 먼저 멈추고 마무리될 때까지 기다린다. 파일 이름으로 네이티브/실시간/종일 출처를 판단하지 않는다.
- 로컬 ASR 모델은 `/sdcard/OpenRayneo/models/<공식 압축 폴더 이름>/`에 둘 수 있다("모든 파일 접근" 권한 필요). 앱을 지워도 남는다.
- 실제 음성 지문은 아직 놓침과 한 사람 오분류가 있다. 현재 기준과 다음 단계는 [미해결 문제](../.arch/ground_truth.md#현재-구현과-미해결-문제)를 본다.

## 데이터와 원격 개발

휴대폰의 암호화 기록과 Keystore는 사용자 데이터이므로 빌드 산출물을 지울 때도 보존한다. 원본 APK/펌웨어/디컴파일 결과와 현재 진단 증거는 무시 목록에 있는 `analysis/`에 있으며 배포 자원이 아니다.

일부 개발 증거는 SSH 별칭 `ubuntu-rx7800xt`의 `~/openrayneo-lab/`에 있다. 원격 작업은 `ssh-control` 스킬을 쓴다. 원래 소스 기준선, 모델, SDK, 사용자 자료는 보존하고, 임시 디버그 코드는 단계가 끝나면 휴지통으로 보낸다. 원격 포트, 키, 개인 기록을 프로젝트 문서에 옮겨 적지 않는다.
