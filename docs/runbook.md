# 운영 가이드

목표, 모듈, 현재 완성도는 [아키텍처 사실](../.arch/ground_truth.md)과 [대시보드](../.arch/dashboard.html)에서 함께 관리한다. 이 문서는 바로 따라 할 수 있는 설치, 실행, 복구 절차만 다룬다. 이 브랜치에는 Android만 있으며, Mac Bridge의 빌드와 운영 설명은 `main` 이력에 남아 있다.

## 현재 로컬 후보

- Android: [37 release](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-release.apk), [37 debug](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-debug.apk), 버전 `1.2.0-beta.4`. CI 버전 이름은 `1.(run-13).0`, 버전 코드는 `1010+run`, 업로드 파일 이름은 `openrayneo-<버전>-<sha7>.apk`.
- DSH: 플러그인 0.1.16(Live answers는 기록마다 DSH 세션 하나에 들린 문장을 모두 이어 넣고 세션이 힌트 시점을 판단, 필요 없으면 PASS. 0.1.15: 폴더 설정이 비어도 `OpenRayneo`, 상대 경로는 홈 기준, 안경 상태 버튼에 `v버전 · 폴더 있음/없음`. 0.1.14: 판단과 hint 답을 한 번의 호출로, 동시 처리 6개, 듣기 기록마다 기능별 DSH 세션 하나를 재사용. 엔진 로그에 `OpenRayneo 플러그인 <버전> 시작`, 안경 상태 버튼에 로드된 버전 표시. 0.1.13: 휴대폰에서 직접 고친 같은 번호의 0.1.12를 대체, 알 수 없는 기능은 `RayNeo 작업` 폴더. 0.1.12부터 기능별 하위 폴더 `RayNeo 깨우기`/`RayNeo 실시간 답변`/`RayNeo 실시간 힌트`/`RayNeo 종일 기록`/`RayNeo 작업`을 DSH 작업 공간으로 등록해 세션을 만든다. 듣기 모드는 판단이 agent일 때만 세션을 만들고 wait/hint는 세션 없이 끝난다. 상위 폴더는 `sessionFolder`. 기본 `OpenRayneo`는 DSH를 실행한 폴더 아래(0.1.10에서 올리면 네이티브 대화가 새 폴더에서 새로 시작), `~/...`나 절대 경로도 되고 비우면 DSH 기본 폴더. 0.1.10부터 안경 Live answers 전용 프롬프트, 0.1.9는 Live answers를 실시간 힌트처럼 처리). `integrations/dsh-go`에서 `npm pack --ignore-scripts`로 tgz를 만든다. 현재 앱은 음성 맥락을 JSON으로만 보내므로 0.1.9 이상이 필요하다(0.1.8은 모든 발화에 답한다). 이전 0.1.8 tgz는 `outputs/candidates/android37-1.2.0-beta.4/`에 있다.
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

## 개발 검증 명령

검증 기준과 미해결 문제는 [재검증 결과](../.arch/ground_truth.md#완료-신호-이후-재검증-결과)를 본다. 빌드 성공, lint 경고, 테스트 소스 부재, 실제 기기 검수는 각각 기록한다. 테스트가 `NO-SOURCE`라면 실행 테스트 0건이다.

Linux/WSL에서 JDK와 SDK를 지정하고 Android 폴더에서 실행한다. 아래 경로는 2026-11-19 검증 PC 기준이며 환경에 맞게 바꾼다.

```sh
export JAVA_HOME=/home/green/tools/jdk17
export ANDROID_HOME=/home/green/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  -Popenrayneo.abis=arm64-v8a,x86_64 --console=plain
./gradlew :app:lintRelease -Popenrayneo.abis=arm64-v8a,x86_64 --console=plain
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

결과는 `app/build/reports/lint-results-{debug,release}.{html,xml}`과 `app/build/outputs/apk/`에서 확인한다. 검증용 키로 만든 APK를 기존 서명과 같은 것으로 가정해 설치하지 않는다.

WSL에서 Windows 원본 드라이브가 보이지 않으면 원본을 옮기지 말고, 깨끗한 Git 기준 커밋을 `git -c core.autocrlf=false archive --format=tar --output=<archive.tar> <commit>`으로 내보내 별도 Linux 검증 폴더에서 푼다. 미커밋 코드가 있으면 archive에 빠지므로 검사 기준과 별도 반영 여부를 반드시 기록한다. 2026-11-19에는 `ok` 외 미커밋 코드가 없었고 검증 복사본은 `/home/green/openrayneo-validation/47fb0d2`였다.

DSH는 `integrations/dsh-go`의 격리 복사본에서 실행한다.

```sh
node --check lib/api.js
node --check lib/harness.js
node --check lib/index.js
node --check lib/native-sessions.js
npm ci --ignore-scripts --no-audit --no-fund
npm pack --dry-run --ignore-scripts
npm pack --ignore-scripts --pack-destination <output-directory>
```

잠금 파일은 peer 범위를 바꾸지 않고 `npm install --package-lock-only --ignore-scripts`로 갱신하며, 합격 조건은 새 폴더의 `npm ci` 성공이다. 실제 DSH 호스트와 휴대폰 연동은 별도로 검수한다. 사용자 기기 검수 전에는 `adb devices -l`로 연결 상태를 확인한다.

## 연결과 복구

1. 평소 전환할 때는 먼저 사용 중인 쪽에서 안경 연결을 끊고 기존 페어링은 유지한다. 휴대폰, 다른 기기, 공식 앱이 동시에 연결을 다투면 데이터 통로가 막힌다.
2. Android에서 근처 기기 권한을 허용한 뒤 대상을 고른다. 첫 페어링은 앱의 페어링 흐름을 쓰고, 시스템 안내는 사용자가 확인한다.
3. BLE 인증, RFCOMM 데이터 통로, 화면/오디오 업무 상태를 각각 확인한다.
4. `channel did not finish opening`이 나오면 진단 정보를 남기고 이번 실패 연결을 정리한다. 다른 앱이 연결을 놓았는지, 안경을 펴서 착용하고 화면이 켜졌는지 확인한 뒤 한 번 더 연결한다. 연결 버튼을 연달아 누르지 말고, 일반 복구에서 페어링 삭제나 앱 데이터 삭제를 하지 않는다.
5. 안경이 웨이크에 반응하지 않으면 기기가 반응하지 않는 것인지 휴대폰이 받지 못한 것인지 먼저 구분한다. 앱 로그의 음성 이벤트와 연결 상태를 보고, 어시스턴트/음성 웨이크/웨이크워드의 실제 보고값을 확인한다. 어시스턴트 화면이 아직 남아 있으면 먼저 시계 화면으로 돌아간다. 기본 체크 상태나 보낸 명령이 기기에서 다시 읽은 값을 대신하지 않는다.
6. 앱 로그를 PC로 가져올 때는 USB(adb)로 `adb pull /sdcard/Android/data/com.openrayneo.android/files/logs`를 쓴다(현재+이전 파일, 각 1MB, 1.44.0부터). 실시간으로는 `adb logcat -s OpenRayneo`. 케이블 없이(같은 Wi-Fi 또는 Tailscale)는 앱의 기록 동기화를 켜고 "동기화 주소와 전용 토큰 복사" 내용을 무시 목록의 `analysis/phone-sync.txt`에 붙여 둔 뒤 `uv run python scripts/pull-phone-log.py`(`GET /v1/sync/logs`, `analysis/phone-logs/`에 저장)를 쓴다.

블루투스 bond가 손상된 경우에만 다시 페어링한다. 자세한 내용은 [프로토콜](protocol.md)을 본다.

### 리모컨 키 변경

휴대폰에 컨트롤러나 외부 키보드를 연결하고 **설정 → 리모컨 입력**에서 프롬프터 또는 Anki 동작을 누른 뒤 사용할 버튼이나 키를 누른다. 같은 화면에서 이미 사용 중인 키에는 중복 안내가 표시된다. 설정은 휴대폰에 저장되며, 동작을 다시 열어 **기본 키 복원**을 누르면 기본 배치로 돌아간다. 연결 모드가 바뀌어 Android가 다른 입력 기기로 인식하면 해당 키를 다시 지정할 수 있다.

### 8BitDo Micro 버튼 확인

1. Micro의 모드 스위치를 K로 옮기고 홈 버튼을 눌러 켠다. 처음 연결할 때는 페어링 버튼을 약 1초 눌러 LED가 빠르게 깜빡이면 휴대폰 블루투스 설정에서 8BitDo Micro를 선택한다. [8BitDo 공식 매뉴얼](https://manual.8bitdo.com/micro/micro-keyboard-bluetooth.html)
2. OpenRayneo에서 **설정 → 리모컨 입력 → 입력 확인 시작**을 선택한다. 버튼을 한 번씩 누르면 장치 이름, Android 키 코드, 스캔 코드가 최신순으로 표시된다. 버튼 이름과 화면의 키 코드를 대조하고, 입력 확인을 종료한 뒤에도 목록을 확인할 수 있다.
3. 입력 확인 화면은 포그라운드에서 외부 장치 키를 읽는다. Android 뒤로 가기는 그대로 동작한다.

### 8BitDo Micro 기본 조작

| 화면 | 동작 | D 모드 | K 모드 키 |
| --- | --- | --- | --- |
| Anki 복습 | 뒷면 보기 | + | E |
| Anki 복습 | 이전 쪽 | ← / ↑ | F / D |
| Anki 복습 | 다음 쪽 | → / ↓ | J / C |
| Anki 복습 | 다시 | X | X |
| Anki 복습 | 어려움 | Y | Y |
| Anki 복습 | 알맞음 | A | A |
| Anki 복습 | 쉬움 | B | B |
| 프롬프터 | 일시정지·재개 | + | E |
| 프롬프터 | 중지 | − | I |
| 프롬프터 | 위로 스크롤 | ↑ | D |
| 프롬프터 | 아래로 스크롤 | ↓ | C |

프롬프터 재생을 시작한 뒤 원고함에서 **리모컨 켜기**를 누른다. 위·아래 스크롤은 안경의 현재 원고 위치에서 한 줄 분량만큼 이동하며, 원고함의 버튼으로도 실행할 수 있다. Anki는 카드가 열린 동안 입력을 받으며, 뒷면에서 현재 카드에 표시된 평가만 저장한다. 안경의 긴 질문이나 답안은 방향키 또는 휴대폰의 이전 쪽·다음 쪽 버튼으로 넘긴다. 입력할 때까지 같은 쪽을 표시한다. D 모드의 +/−는 Android에 시작/선택으로 들어온다. K 모드는 물리 버튼 위치와 별개로 휴대폰에 전달된 문자를 기준으로 한다. K 모드로 채점할 때는 8BitDo Ultimate Software에서 X/Y/A/B 버튼이 각각 X/Y/A/B 키를 보내도록 설정한다.

휴대폰 화면이 켜져 있고 OpenRayneo의 해당 화면에 포커스가 있어야 버튼이 동작한다. 잠금 화면과 다른 앱이 앞에 있는 동안의 입력은 현재 지원 범위 밖이다.

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
tar -xzf /sdcard/Download/openrayneo-dsh-0.1.16.tgz --strip-components=1 -C "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
dsh plugin --profile web add "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
```

처음 설치할 때 add를 실행한다. 업데이트는 같은 폴더를 교체한 뒤 DSH "엔진" 화면에서 커널을 재시작한다. 재시작 전에는 엔진이 이전 코드를 메모리에 들고 있어 폴더가 만들어지지 않는다(실기기). 연결 동작은 `OPENRAYNEO_TOKEN` 자격 증명 서비스에 저장된다. live 네이티브 웨이크는 웨이크를 넘어 대화를 이어 쓰며, 휴대폰 어시스턴트의 "새 대화"로 명시적으로 새로 연다. 녹음/작업/웨이크 신원은 각각 독립적이다.

기록 동기화 서비스의 커서와 파생 회신 규칙은 [동기화 계약](record-sync-contract.md)을 본다. 이 브랜치에는 그 Mac 클라이언트가 없다.

## 음성과 녹음

- 네이티브 자동 응답은 명시적으로 켜야 한다. 2채널은 기본적으로 ch1을 본인, ch2를 전체 화자로 다루며, 네이티브/종일 혼합 mono는 전부 처리한다. 수동 등록은 기본으로 실시간 ch1을 고른다.
- 휴대폰 "기록 → 기록 일정"에서 끝난 기록을 열고 "내보내기: WAV"를 누르면 원래 채널 그대로 저장된다. 지금 녹음 중인 기록은 먼저 멈추고 마무리될 때까지 기다린다. 파일 이름으로 네이티브/실시간/종일 출처를 판단하지 않는다.
- 로컬 ASR 모델은 `/sdcard/OpenRayneo/models/<공식 압축 폴더 이름>/`에 둘 수 있다("모든 파일 접근" 권한 필요). 앱을 지워도 남는다.
- 실제 음성 지문은 아직 놓침과 한 사람 오분류가 있다. 현재 기준과 다음 단계는 [미해결 문제](../.arch/ground_truth.md#현재-구현과-미해결-문제)를 본다.

## 데이터와 원격 개발

휴대폰의 암호화 기록과 Keystore는 사용자 데이터이므로 빌드 산출물을 지울 때도 보존한다. 원본 APK/펌웨어/디컴파일 결과와 현재 진단 증거는 무시 목록에 있는 `analysis/`에 있으며 배포 자원이 아니다.

일부 개발 증거는 SSH 별칭 `ubuntu-rx7800xt`의 `~/openrayneo-lab/`에 있다. 원격 작업은 `ssh-control` 스킬을 쓴다. 원래 소스 기준선, 모델, SDK, 사용자 자료는 보존하고, 임시 디버그 코드는 단계가 끝나면 휴지통으로 보낸다. 원격 포트, 키, 개인 기록을 프로젝트 문서에 옮겨 적지 않는다.
