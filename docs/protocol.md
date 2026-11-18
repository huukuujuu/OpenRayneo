# 기기 프로토콜 계약

이 문서는 현재 OpenRayneo 기기 링크가 의존하는 와이어 프로토콜, 생명주기, 복구 규칙을 정리한다.

> `android-only` 브랜치: 본문의 Swift 구현과 Mac API 링크는 삭제 직전 커밋 `a914718`에 고정되어 있으며 프로토콜 근거로만 남긴다. Android 구현은 `android/app/src/main/java/com/openrayneo/android/`에 있다.

HTTP 경로와 파라미터는 [로컬 API 참조](https://github.com/huukuujuu/OpenRayneo/blob/a914718/.agents/skills/openrayneo-api/references/api.md)를 기준으로 한다.
휴대폰과 Mac 간 데이터 교환은 [기록 동기화 계약](record-sync-contract.md), 실행 방법은 [운영 가이드](runbook.md)를 본다.
프로젝트 계획과 현재 문제는 `.arch`에 있으며, 이 문서에는 빌드 기록이나 매번의 실기기 디버그 로그를 남기지 않는다.

프로토콜 근거는 주로 공식 RayNeo AI Android 1.0.5/1.0.10의 실제 호출, 원시 명령, 블루투스 캡처다.
과거 실기기 관찰은 주로 Strix OS 1.0.5.12 기준이며, 다른 펌웨어에서는 알 수 없는 필드와 원시 결과를 보존해야 한다.
SDK 열거형, 전송 성공, 업무 응답, 설정 재조회, 안경의 실제 표시는 서로 다른 수준의 증거다.
문서 끝 색인은 원시 `analysis/` 경로를 남긴다. 이 자료는 공개 저장소에 포함되지 않을 수 있다.

## 1. 연결, 인증, 페어링

BLE는 검색, 앱 인증, 업무 메시지를 담당하고 Classic Bluetooth SPP는 주 데이터 통로를 담당한다.
공식 SDK의 `highSpeed` / `HIGH_AP(SPP)`는 이 SPP를 뜻하며, 사용 가능한 Wi-Fi/USB 전환 계약은 복원되지 않았다.
완전한 연결은 BLE 인증 완료와 RFCOMM 연결 완료를 기준으로 하며, 시스템 bond, HFP, 안경의 연결 애니메이션으로 대신할 수 없다.

| 항목 | 현재 계약 |
| --- | --- |
| BLE 서비스 | `0000B81D-0000-1000-8000-00805F9B34FB` |
| BLE 송신 | `EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9` |
| BLE 수신 | `7DB3E235-3608-41F3-A03C-955FCBD2EA4B` |
| 페어링 정보 characteristic | `EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9` |
| SPP 서비스 | `99999999-9999-9999-9999-999999999999` |
| RFCOMM | SDP로 서비스 채널 조회. 현재 실기기 채널 26은 타임아웃 후 관찰된 대체값 |
| RFCOMM 하위 계층 | L2CAP PSM `0x0003`. 실측 MTU 1011이 물리 쓰기 분할 상한 |

검색할 때는 이름, 서비스, manufacturer data를 함께 본다. 관찰된 company 바이트는 `16 57`, 모델 바이트는 `06`, 그리고 6바이트 Classic 주소가 있다.
인증 전에 페어링 characteristic의 Classic 주소도 확인해서 비슷한 이름만으로 기기를 고르지 않는다.
앱 등록과 OS의 Classic 블루투스 bond는 따로 처리한다. 현재 **Mac**의 등록 의도는 다음과 같다.

- 새 페어링이나 명시적인 bond 재생성: BLE 등록 `pairValue` / TLV 27에 1을 쓴다.
- 일반 연결, 재연결, 기존 페어링 사용: 2를 쓴다. 이 의도는 GUI/백그라운드 프로세스를 넘어 이번 흐름이 끝날 때까지 유지해야 한다.
- 최초 흐름은 BLE로 등록을 준비하고 LE를 해제해 끊길 때까지 기다린 뒤, 별도 시스템 페어링 도우미로 Classic 연결과 bond를 만든다. 그다음 BLE에 다시 연결해 인증하고 SPP를 연다.
- 일반 연결이 실패했다고 bond를 자동으로 지우지 않는다. HFP 게이트웨이도 앱 연결의 필수 조건이 아니다.

페어링 업무 `0x10`의 내부 패킷은 `command:1 + length:BE16 + payload`이며 일반 protobuf envelope가 아니다.
기기 정보 17, 인증 요청 24, 인증 응답 25의 TLV는 복원되었다. 22/23의 원시 상태는 TLV로 디코딩하면 안 된다.
예를 들어 `17 00 02 02 00`은 명령 23의 원시 페어링 상태이며 앱 ready로 보면 안 된다.
인증은 기존 구현의 challenge/device ID/프로토콜 상수로 계산한다. 계정 바인딩 ECDH 분기는 미구현이며, 만나면 명확히 실패시킨다.
Android의 현재 [RayneoConnection.kt](../android/app/src/main/java/com/openrayneo/android/RayneoConnection.kt)는 여전히 TLV 27=1로 고정되어 있고,
Mac의 일반 재연결 값 2를 따르지 않는다. Mac에서 검증된 흐름을 양쪽이 이미 같다고 일반화하면 안 된다.
상세 필드, 상수, 시스템 페어링 호출은 [main.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/main.swift)와
[BluetoothPairing.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/BluetoothPairing.swift)에 있다. 두 번째 인증 알고리즘을 따로 만들지 않는다.

BLE 쓰기는 `maximumWriteValueLength(.withoutResponse)` 단위로 나누고 `canSendWriteWithoutResponse`를 따른다.
`peripheralIsReady`를 기다린 뒤 계속한다. 블루투스 delegate는 메인 큐에서 돌므로 블로킹 대기는 작업 큐에서 해야 한다.
SPP 쓰기도 실제 MTU 단위로 나누며, 물리 분할은 아래의 앱 프레임을 바꾸지 않는다.

## 2. AA55 프레임과 확인 응답

```text
magic:2 | bodyLength:BE16 | sequence:BE16 | flags:1
        | address?:1 | sliceInfo?:N | businessID:1 | payload | crc16:BE16
```

`AA55`는 데이터, `55AA`는 transport ACK다.
`bodyLength = 4 + addressLength + sliceInfoLength + payload.count`이며 magic, 길이 필드, 끝의 CRC는 포함하지 않는다.
CRC16-XMODEM은 sequence부터 payload 끝까지 body 전체를 덮는다.

| flags 비트 | 의미 |
| --- | --- |
| bit 0 | `needsResponse` |
| bit 1 | `useAddress`, address 바이트 하나가 있음 |
| bit 2, 3–4 | 예약 |
| bits 5–7 | `sliceInfoLength`, 0–7바이트 |

확장이 없는 일반 프레임의 payload 최대 크기는 **65531바이트**이며 protobuf, JSON, UTF-8, base64 오버헤드를 모두 포함한다.
32768–65535 범위의 sequence/길이는 부호 없는 와이어 값으로 다룬다. Java `short`의 부호는 프로토콜 제한이 아니다.
앱 계층의 큰 패킷 분할/재조립 규칙은 복원되지 않았으며, HTTP body 한도를 늘려 16비트 프레임 길이를 우회할 수 없다.
선언된 sliceInfo 필드도 분할 알고리즘을 추측하는 근거가 되지 못한다.

수신한 AA55에 bit 0이 켜져 있으면 같은 sequence/business로 빈 55AA를 보낸다. body 길이 4, flags 0, 올바른 CRC.
ACK에 다시 ACK하지 않는다. 공식 앱의 ACK 대기는 약 5초이며, 타임아웃은 이번 전송 실패일 뿐 제어 동작을 자동 재전송할 근거가 아니다.
sequence는 기기별 16비트 번호다. 대기자는 최소한 기기와 번호로 연결해야 하며, 재연결을 넘어 늦게 온 결과를 받으면 안 된다.
transport ACK는 전송 계층 확인일 뿐이고, 업무 응답과 실제 실행은 각각 따로 확인해야 한다.

BLE와 SPP는 각자 수신 버퍼와 반쪽 프레임 상태를 갖지만 둘 다 업무 프레임을 받는다.
SPP로 보낸 요청의 응답이 BLE로 돌아올 수 있으므로 보낸 링크에만 응답 대기를 등록하면 안 된다.
녹음 등 원본 제어는 보통 flags 0을 쓰므로 응답을 기다리려고 임의로 bit 0으로 바꾸지 않는다.

대부분의 업무 payload는 protobuf `TransportMessage`를 쓴다. field 1 버전, 2 이벤트 type, 3 UTF-8 JSON, 4 바이너리.
일반적인 버전은 1이다. JSON `action`, envelope type, 바깥 businessID, sequence는 서로 독립된 네 필드다.
녹음 제어는 원래 송신기 인코딩대로 빈 field 4를 명시적으로 넣는다. 이것을 응답 없음 문제의 만능 해결책으로 쓰면 안 된다.

## 3. 연결 복구와 호스트 상태

현재 연결 복구는 명시적 연결, 업무 요구, 링크 끊김, 기기 수신 `0x10`/첫 바이트 `0x1e` 신호, 블루투스 전원 켜짐을 한데 모은다.
`0x1e`는 수신 측 복구 단서이며, 이를 근거로 만들 수 있는 같은 이름의 송신 명령은 아직 없다.
현재 Mac의 복구 주기 1회는 최대 3번 시도하며 간격은 1/3초, 총 예산 45초다. BLE 15초, SPP 8초, SDP 3초는 모두 남은 예산 안에서 제한된다.
한 번 연결에 성공했고 여전히 실행을 기대하는 서비스는 실패 주기 후 약 60초 뒤 다시 시도할 수 있다. 첫 실패에서 백그라운드 무한 재시도는 하지 않는다.
일반 업무 쓰기는 백오프를 우회하지 않으며, 명시적 연결/기기 신호/전원 켜짐은 다시 트리거할 수 있다. suspend는 대기와 이후 재시도를 취소한다.

모든 socket/delegate/조회는 대상 기기, 연결 generation, 수신 epoch로 격리한다.
새 연결은 이전 반쪽 프레임, 제어 큐, 영수증 대기를 정리한다. 늦게 도착한 이전 콜백이 새 연결을 가로채면 안 된다.
연결 복구는 이미 실패한 업무 쓰기를 자동 재전송하지 않는다. 기기 상태를 다시 읽은 뒤 다음 단계는 호출자가 정한다.
구현: [main.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/main.swift),
[BridgeProcessLifecycle.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/BridgeProcessLifecycle.swift).

전역 포그라운드/백그라운드 알림은 `0x10` 원시 명령 31을 쓴다. 포그라운드 `1f 00 01 02`, 백그라운드 `1f 00 01 01`.
녹음 `0x0e` type 14의 `{isAppForeground:bool}`은 별개 업무 알림이며 서로 대체할 수 없다.
GUI는 창이 보이는지만이 아니라 `NSApp.isActive`에 따라 동기화한다. headless는 기본이 포그라운드이며 명시적으로 덮어쓸 수 있다.
로컬 `device/visibility`를 조회/설정할 수 있으며, 이 동작 자체가 연결을 만들지는 않는다.
상태는 pending, 전송됨, generation을 따로 기록한다. `applicationAccepted:null`은 업무 수락 확인이 없다는 뜻이다.
정상 종료 시 백그라운드 상태를 보낼 수 있으며, 포커스 변화 때문에 녹음이 자동으로 멈추지는 않는다.
초기 시계/포그라운드 상태/설정/네이티브 녹음/텔레프롬프터 동기화 진입점은
[DeviceStateSynchronization.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/DeviceStateSynchronization.swift)에 있다.

공식 앱에서 handler가 등록되기 전 수신 큐는 `0x0e/0x13/0x06/0x0f/0x14`만 캐시한다. 15초, 총 24개, 업무당 8개,
기본 payload 4096바이트, versionCheck 8192바이트이며 한 번 꺼내면 제거된다.
이것은 송신 오프라인 재시도 큐가 아니다. 현재 Mac 업무 handler는 연결 전부터 상주하며, 영수증 관찰 캐시가 이 큐를 흉내 내지 않는다.

## 4. 활성 업무 맵

| businessID | 용도 | 현재 계약 진입점 |
| --- | --- | --- |
| `0x06` | 기능/버전 협상 | JSON capabilities. 빈 목록에도 정상 응답 가능 |
| `0x09` | OTA | 버전, 패키지 요청, 설치 후 검증 |
| `0x0b` | 로그 | 압축 작업과 별도 파일 전송 |
| `0x0d` | 네이티브 어시스턴트, LifeLog | 서로 다른 두 type 범위 |
| `0x0e` | 네이티브 녹음 | UUID, 오프셋, 오프라인 목록, 완료 신호 |
| `0x0f` | Launcher/설정/Dashboard | `{cmd,payload:{value,mode,data}}` |
| `0x10` | 인증/연결/포그라운드 상태 | 원시 command/length/TLV 또는 상태 |
| `0x11/0x12` | 범용 파일 | SmallCore/LargeCore. 두 가지 무선 연결이 아님 |
| `0x13` | 실시간 자막 | SID 제어, 오디오, 자막 표시 |
| `0x14` | 텔레프롬프터 | 원고/재생/진행. 본문은 파일 통로로 따로 전송 |
| `0x15` | 알림 | 알림 상태, MAP 요청/상태 |
| `0x16` | 일정/할 일 | 일괄, 조회, 상태 변경 |
| `0x17` | 실시간 프롬프트 | 독립적인 지속 오디오/프롬프트 표시 세션 |

SDK 열거형에만 있는 `0x01/02/03/04/07/08/0a/0c/18/20`은 이름만으로 사용 가능하다고 주장할 수 없다.
안경 앱 설치, 독립 동작 키, 자이로 보정, 저음량 설정, 카메라/미디어 스트림, Widget, 하트비트, 내비게이션의 완전한 계약은 아직 복원되지 않았다.
크라운 빠른 설정과 Dashboard는 `0x0f`를 쓴다. 내비게이션 번호는 십진수 32이며 텔레프롬프터의 십진수 20이 아니다.
추측한 하트비트, 사진, 내비게이션 패킷은 보내지 않는다.

## 5. 네이티브 어시스턴트와 자동 보관 오디오

네이티브 웨이크는 `0x0d` AssistantMsg를 쓰며, 실시간 프롬프트 `0x17`이나 LifeLog의 161–168과 다르다.

| type | 방향/의미 |
| --- | --- |
| 1 | 안경 웨이크 `{rc,mode,linkType}`. rc 1/2 음성, 3 버튼, 4 단발, 5 Agent, 6 LiveAI, 7 공유 오디오 |
| 2 | 호스트 수음 시작/중지 `{rc:1}` / `{rc:2}` |
| 3 | 안경 Opus, protobuf 바이너리 field 4 |
| 4 | 호스트 VAD: START 1, STOP 2, TIMEOUT 3 |
| 5 | 호스트 ASR `{text,final}` |
| 6/9 | TTS 상태 / SpeechStatus: LISTENING 1, RESPONDING 2, THINKING 3 |
| 7/8 | 호스트 종료 / 안경 종료. 의미가 불분명한 rc는 임의로 확장하지 않음 |
| 11 | 수동 다음 턴, 직전 답변의 sid를 이어 씀 |
| 12/32 | 워크플로 완료 / SkillPayload 답변. 현재 SID와 호출 순서대로 보냄 |

답변 payload에는 `answer:{text,isFinal}`과 domain/intent/offline/query/round/sid/sub/timestamp/uuid/vendor 등의 필드가 있다.
chat은 `domain=intent=chat`과 Unix 초 단위 timestamp를 쓴다. 전체 직렬화는
[NativeAssistantPayload.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/NativeAssistantPayload.swift)를 기준으로 한다.
웨이크 wakeID, 수음 captureID, 표시 SID, 기록 ID, Agent task ID, DSH 세션 신원은 반드시 분리한다.
수음 중지가 이미 보낸 Agent 요청의 취소를 뜻하지는 않는다. 늦게 온 답변은 현재 wake/SID/표시 의도 검사를 통과해야 한다.
DSH 네이티브 live는 웨이크를 넘어 고정 phone ID + conversation key + preset으로 대화를 재사용하며, 명시적인 새 대화는 key를 바꾼다.
작업 생성 시점에 신원을 스냅숏해 이전 작업이 새 대화를 오염시키지 않게 한다. 같은 세션 안에서는 실제 턴/요청 귀속으로 대기열과 취소를 처리하며, sessionId만으로 답변을 가져오지 않는다.

Mac 네이티브 Opus 디코더는 **16 kHz mono**로 직접 설정해 실제 16k 샘플을 낸다. 48k 데이터의 헤더만 바꿔 16k로 취급하면 안 된다.
ASR feed는 확인 커서가 있는 단기 소비 통로이며, 확인된 오디오는 지울 수 있다.
네이티브 capture마다 이제 독립적으로 16k PCM16 WAV를 자동 스트리밍 저장하며 arm, TTL, 추가 60초 제한이 없다.
기존 10–600초 수음 생명주기는 유지된다. 정상적인 duration 종료에서 파일을 닫으며, 어시스턴트 화면 전체를 나갈 때까지 기다리지 않는다.
완료되면 App Support `OpenRayneo/NativeDiagnostics`에 최신 완전 파일과 `latest.json`을 원자적으로 게시한다.
다음 성공 회차가 이전 latest를 교체하고, 직접 만든 이전 오디오는 휴지통으로 간다. 현재 임시 녹음과 직전 완전 파일은 잠시 함께 존재할 수 있다.
연결 끊김, 디코딩 공백, 쓰기 실패는 incomplete로 표시하고 직전 완전 latest를 유지한다.
상태는 현재 capture와 `latest.captureID`를 구분하며, 비교할 때 직전 회차를 현재로 착각하면 안 된다. 정확한 GET 경로는 API 참조를 본다.
구현: [NativeAssistantController.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/NativeAssistantController.swift),
[NativeDiagnosticRecording.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/NativeDiagnosticRecording.swift).

## 6. 실시간 자막, 프롬프트, 2채널 PCM

자막 `0x13`과 프롬프트 `0x17`은 모두 type 1 시작, 2 결과/유효 설정, 3 제어, 4 오디오, 5 텍스트를 쓴다.
SID는 start/audio/update/exit 전체를 관통해야 하며, SID가 일치하는 성공 설정을 받은 뒤에만 해당 세션에 들어간다.
자막 임시 설정은 type 7/8이다. 사용 중인 표시 설정은 `font_size/content_width/max_lines/position/is_display/straight_view`다.
자막 텍스트는 mode 3, 프롬프트 텍스트는 mode 4이며 status 0은 중간, 1은 최종이다. 내용에는 `source_transcript`와 선택적 번역이 들어간다.
프롬프트 시작은 보통 `{sid,trigger:1,force:false,code:0,settings:{mode:"conversation",source_language:"zh-CN",target_language:"zh-CN",save_audio:false,direction:"ahead"}}`를 쓴다.
성공 결과는 code 1과 final_settings를 담는다. 자막 종료는 reason_code 10, 프롬프트 종료는 reason_code 2다.
`save_audio:false`는 type-4 오디오를 끄지 않으며 로컬 저장 여부도 결정하지 않는다.
화면을 여는 것은 오디오/표시 진입점일 뿐이고, ASR, speaker, 의미 Agent 처리는 로컬 소프트웨어가 맡는다.

실제 실시간 2채널은 frame 단위로 디인터리브하며, 채널마다 FIR/ASR/round/복구 커서를 독립적으로 둔다.

- ch1은 기본값이 본인이라 등록이 필요 없다. VAD/ASR은 하되 embedding/diarization은 건너뛴다.
- ch2는 완전한 speaker 처리 + ASR. 채널 구분, 신원 근거, 모든 텍스트를 보존하며 같은 문장이라고 추측해서 중복 제거하지 않는다.
- 네이티브, 종일 혼합, 실제 mono는 완전한 speaker 처리를 쓴다. `sourceChannel=1` 자체는 본인이라는 증거가 아니다.
- Opus TOC의 coded mono/stereo는 같은 스트림 안에서 프레임마다 바뀔 수 있으므로 물리 입력 채널 수로 쓸 수 없다. 진입점 협상과 실제 디코더 PCM 형식으로 1/2채널을 정한다.
- 명시적인 stereo feed만 2채널로 인터리브한다. 기존 mono 클라이언트는 단일 채널을 유지한다. 샘플레이트/채널 수는 feed 메타데이터와 함께 전달해야 한다.

공식 실시간 진입점은 흔히 48k stereo로 디코딩한다. 로컬 speaker는 lane별로 16k로 리샘플링하며 두 채널이 FIR 상태를 공유하면 안 된다.
실제 단어 시간이 없으면 문장 전체 범위와 speaker turn을 보존하고, 단어 단위 경계를 지어내지 않는다.
전체 필드와 벽시계 매핑은 동기화 계약을 본다. 구현:
[ChannelSpeakerProcessor.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/ChannelSpeakerProcessor.swift),
[DesktopChannelRound.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/DesktopChannelRound.swift),
[AssistantAudioFeed.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/AssistantAudioFeed.swift),
[PromptAudio.kt](../android/app/src/main/java/com/openrayneo/android/PromptAudio.kt).

## 7. LifeLog / 종일 기록 진입점

Launcher `0x0f` type 20 `life_log_switch`가 켜기/끄기를 요청하고, 21 `life_log_switch_result`가 상태를 돌려준다.
영구 스위치, 회차별 수음, 로컬 workflow는 서로 다른 세 생명주기다. result.value나 오디오 한 패킷만으로 전체 상태를 판단할 수 없다.
`life_log_switch_result.value`는 이번 조작 결과다. 0 성공, 1 마이크 권한 없음, 2 안경다리 접힘, 3 배터리 부족. 0이 꺼짐 상태를 뜻하지 않는다.
공식 1.0.10은 켤 때 payload.data를 JSON 문자열 `{delay:2}`로 보내며 delay는 1–60초 중 선택, 끌 때는 `data:""`다.
delay는 VAD 시작 지연이며 A2 idleTimeoutSec를 대신하지 않는다. 현재 펌웨어에서 이 선택 파라미터는 실기기 확인이 더 필요하다.

| Assistant type | 의미 |
| --- | --- |
| 161/A1 | 안경이 이번 회차를 깨움 |
| 162/A2 | 호스트 시작 `{taskId,idleTimeoutSec}` |
| 163/A3 | 실시간 Opus, task/frame/VPU/VAD 메타데이터 |
| 164/A4 | 캐시 호환 통로, taskId/bytes/isFinish |
| 165/A5 | 안경 측 회차 종료. 로컬 전사/요약 완료를 뜻하지 않음 |
| 166/A6 | 호스트 비정상 종료 |
| 167/A7 | 과거 캐시 조회. 공식 1.0.10의 재연결 경로는 이를 건너뜀 |
| 168/A8 | 호스트가 `{inRealtimePage}` 보고 |

A3는 기존 단일 프레임과 240바이트 단위로 나눈 일괄 패킷을 모두 지원하며, frameCount는 실제 사용 가능한 개수로 제한하고 패킷당 최대 31프레임이다.
일괄 패킷 전체를 Opus decode 한 번에 넘기면 안 된다. taskId/mode/vpuMask/vadMask는 보존하지만 이 mask를 인물 신원으로 쓰지 않는다.
A4는 taskId별로 원시 bytes를 저장한다. `.opus` 확장자가 Ogg 컨테이너를 뜻하지는 않는다.
캐시 용량/보관 기간은 확인되지 않았으므로 종일 무손실 오프라인 녹음이나 A7을 통한 안정적인 보충을 약속하지 않는다.
종일 진입점에는 약 20초의 원천 지연이 있을 수 있어 추정치임을 표시해야 하며, 이를 네이티브 실시간 웨이크에 적용하면 안 된다.
녹음 offset은 실제 PCM만 세며, 회차 사이의 무음은 수신 시각 앵커로 표현한다. 종일 시간을 연속 오디오 길이로 압축하지 않는다.
관찰을 멈출 때는 자신이 켠 스위치/워크플로만 되돌려서, 디버그 화면을 나갈 때 다른 소유자의 것을 잘못 끄지 않게 한다.
구현: [LifeLogCaptureStore.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/LifeLogCaptureStore.swift).

## 8. 네이티브 녹음과 오프라인 복구

`0x0e` version 1은 JSON과 바이너리 field 4를 함께 담는다. protocol UUID, sid, 로컬 저장 UUID는 서로 다르다.

| type | 이름 / 호스트 요청 |
| --- | --- |
| 1 | start: `{uuid,sid,type:1,mode:1 또는 2,action:1}`. sid는 시작 시각의 Unix 초 문자열 |
| 3 | 오디오 데이터, uuid/offset 기준으로 저장 |
| 4 | stop: `{uuid:<현재>,code:2,action:1}` |
| 5 | 재연결 상태, 기존 녹음을 다시 맞추는 데 사용 |
| 6/7 | 데이터 완료 / 오프라인 정보 |
| 8 | pause: `{uuid,code:1,action:1}` |
| 9 | resume: `{uuid,action:1}`. code를 임의로 붙이지 않음 |
| 10 | 마이크 모드: `{uuid,mode,action:1}`. 1 WideSpectrum, 2 VoiceIsolation이며 PCM 채널 번호가 아님 |
| 11 | mark. 안경은 action 1, 호스트는 action 2로 응답 |
| 12 | syncList 조회 `{action:1}` / 정렬 응답 action 2 |
| 13/14/255 | 안경다리 상태 / 녹음 포그라운드 상태 / 블루투스 변화 |

복원된 실시간 녹음 형식은 240바이트/20ms Opus 패킷이며 48k stereo로 디코딩된다. 원시 바이트 offset 기준으로 범위를 병합한다.
type 3에 패킷별 업무 ACK를 따로 만들지 않는다. type 6에도 복원된 별도 완료 ACK가 없으며, 바깥 needsResponse는 여전히 독립적으로 처리한다.
`completed:true`는 완료 신호일 뿐이다. 0부터 시작하는 연속 패킷 커버리지, 양수이면서 패킷 단위로 맞는 길이, 알려진 expectedBytes를 모두 만족해야 한다.
바깥 필드 `offline_size`와 목록 항목의 `offlineSize`는 다르다. 후자는 공식 앱이 진행률 총량으로 쓰며, 실제 펌웨어의 비어 있지 않은 목록에서 전체 크기 의미는 아직 확인이 필요하다.
추정 총량, 진행률 100%, 재생 가능한 앞부분을 완전한 녹음으로 취급하지 않으며, 패킷 손실을 무음으로 채우지 않는다.
재연결 시 먼저 상태와 오프라인 목록을 조회하고, 녹음을 자동으로 새로 시작하지 않는다. 이어 받기 위해 UUID, 범위, writer를 유지한다.

syncList 항목은 `{uuid,offlineSize}`다. `items` 생략과 빈 배열을 임의로 같다고 보면 안 된다.
알 수 없는 녹음을 가져오려면 먼저 protocol UUID → 새 local UUID와 writer/cache를 영구적으로 만든 다음 기기 generation을 다시 확인해야 한다.
가져오기는 `{action:2,total_items:N,deleteOther:false}`를 한 번에 보내고 items는 생략한다. N에는 조건을 만족하는 기존 이어받기와 새 가져오기가 모두 포함된다.
이 응답에는 UUID 목록이 없으므로 "선택한 미확인 항목만 다운로드"를 약속할 수 없다. 이미 아는 항목이 다시 전송될 수 있으며 offset으로 중복을 제거한다.
명시적으로 선택한 이어받기는 여전히 items를 쓴다. 가져오는 동안 live/tail이나 다른 전송과 경쟁하지 않게 한다.
오프라인 녹음 삭제는 새로 받은 완전한 목록을 근거로 다른 항목을 보존해야 한다. 누락이나 타임아웃을 전체 삭제 근거로 쓰지 않는다.

mark type 11에서 호스트는 `{uuid,code,action:2}`만 보내며 로컬 표시 텍스트/시간은 보내지 않는다.
code 1 로컬 저장 성공, 2 인접 표시와 5초 미만 간격, 3 일치하는 durable 세션 없음 또는 저장 실패.
위치는 녹음 기준 정수 초이며 녹음 진행도를 우선한다. fallback/수신 offset으로 보정할 때는 timeBasis를 저장해야 한다.
자료실의 편집/삭제는 로컬 색인만 바꾸며, tombstone으로 새로 고침 때 되살아나지 않게 한다. 편집 사본은 남긴 구간에 맞춰 표시를 다시 매핑한다.
구현: [NativeRecordingController.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/NativeRecordingController.swift),
[RecordingMarker.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/RecordingMarker.swift).

## 9. 범용 파일과 텔레프롬프터

`0x11` SmallCore / `0x12` LargeCore는 같은 파일 프로토콜을 쓰며, bus_id 17/18을 텔레프롬프터 업무 20으로 보면 안 된다.
protobuf TransferMessage oneof는 1 FileInfoSync, 2 FileInfoAck, 3 FileChunkReq, 4 FileChunk, 5 FileRecvState, 6 FileCancel이다.
FileInfoSync에는 uuid/filename/filesize(uint64)/md5/bus_id가, chunk에는 uuid/chunkid/chunkstart(uint64)/chunksize/data가 있다.
수신 측이 범위를 요청하며 한 번에 최대 51200바이트다. protobuf 기본값 0은 시작점 0과 SUCCESS 0을 뜻할 수 있다.
범위 합집합과 프로토콜 MD5로 검증한다. 겹치는 범위는 데이터를 두 번 붙이지 않으며, CRC 오류는 제한된 횟수만 재시도한 뒤 실패한다.
송신 완료는 원격 FileRecvState SUCCESS를 기다려야 한다. 수신 완료에는 파일 커버리지와 MD5가 필요하며, 완료 알림 전송 실패는 상태를 따로 기록한다.
취소는 먼저 로컬을 멈추고 미완성 파일을 휴지통으로 보낸다. 연결 가능하면 FileCancel을 보내며, 취소를 위해 강제로 재연결하지 않는다.
120초 동안 진행이 없으면 타임아웃이다. 프로세스를 넘는 자동 이어받기 계약은 아직 없으며, 기존 offset 필드가 영구 resume 프로토콜을 이루지는 않는다.
구현: [FileTransferController.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/FileTransferController.swift).

텔레프롬프터 `0x14`는 action 1 요청/2 응답이며 type 1 목록, 2 원고 선택, 3 읽기 시작, 4 일시정지, 5 계속, 6 중지, 7 설정, 8 진행, 9 오디오, 10 상태, 11 toast다.
목록 type은 fullSync1/batchTop2/batchAdd3/batchDelete4/updateContent5다. 원고 하나를 지울 때 목록 전체 비우기를 보내지 않는다.
원고 did, UTF-8 바이트 total, checksum, UI의 UTF-16 커서는 단위가 다르다.
안경이 원고를 고르면 먼저 로컬 원고를 해석해 실제 본문 길이를 기준으로 type2/action2/code1 또는 원고 없음 code5로 응답한 다음, `0x11`로 본문을 보낸다.
파일 완료와 텔레프롬프터 수신 확인은 따로 기다리며, 후자는 code1/7이 복원된 성공 분기다. 읽기 시작에는 type3으로 다시 응답한다.
scroll은 followSpeak1/linear2/manual3이다. 설정과 페이지 내 강조 위치는 복원된 serializer를 따르고 mode를 추측하지 않는다.
연결 복구 시 상태/checksum을 읽으며, 안경 위치로 로컬의 미저장 편집을 무조건 덮어쓰지 않는다.
구현: [TeleprompterRepository.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/TeleprompterRepository.swift),
[TeleprompterFollowController.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/TeleprompterFollowController.swift).

## 10. 설정, Dashboard, 일정, 알림

Launcher `0x0f`: type 1/4 general status/settings 조회, 2/3 상태 변경, 5/6 설정 변경, 14/15 reset/ack,
16/17 assistant config/sync, 18/19 Dashboard, 20/21 LifeLog, 22/23 setup, 24 preview.
`payload.data`는 흔히 JSON **문자열**이며 중첩 객체로 바로 바꾸면 안 된다.

| 제어 | cmd / 핵심 값 |
| --- | --- |
| 표시/웨이크/개인정보 | `display_config:{height,distance}`, `wakeup_config:{headup_switch,headup_degree,crown_switch}`, `privacy_config` |
| 크라운/머리 제스처 | `crown_config`의 방향 및 double/longPress, `head_gestures` value/mode는 0/1 |
| 단위/시간 | 원래 철자 그대로 `system_unints_config`, `sync_time:{utc,offset,timezone}` |
| 자동 잠금 | `auto_lock_time` 초: 5/10/15/25/40/60/120 |
| 화면 꺼짐 상호작용 | crown_switch 0 끔/1 기존 자이로 유지/2 카드/3 구슬. 단축키가 아님 |
| 집중 모드 | `focus_mode`. data.type=2 정책 저장, 1 수동 켜기/끄기, 4 애니메이션 없이 끄기. 수동일 때 방금 읽은 정책을 유지 |
| AI 켜기/웨이크/끊기 | type16, `set_ai_state/set_ai_voice_wakeup/set_ai_work_mode`, mode0/1 |
| AI 언어/웨이크워드 | `set_ai_lang` mode1–7=zh-CN/zh-HK/en-US/fr-FR/de-DE/es-ES/it-IT, `set_ai_wakeup_word` 1 중국어 웨이크워드(샤오레이)/2 영어 |
| AI 이름 | `set_ai_model_str` data 문자열. 무선 음색(timbre) setter는 복원되지 않음 |

`sync_ai_settings`는 증분 동기화이며 특정 setter의 성공 ACK가 아니다. 전체 voiceAssistantConfig와 병합하고 알 수 없는 값과 미저장 편집을 보존한다.
초기화 type14 `factory_reset` value1, 연결 해제 흐름의 기기 지우기는 value2다. type15 `factory_reset_ack` value0을 명확히 받아야 수락으로 보고한다.
기기 지우기, 공식 계정 연결 해제, 로컬에서 잊기는 서로 다른 세 동작이다. 현재 공식 클라우드가 꺼져 있으므로 자동으로 묶어 실행하지 않는다.

Dashboard `data` 내용과 레이아웃은 분리된다. `dashboard_update`는 `{simple_mode,widgets}`만 직렬화하며 widgets_data 쓰기를 지어내지 않는다.
위젯 와이어 값은 weather1/todo2/schedule3/stock4/email5/worldClock6, simple_mode는 0/1/2다.
도시/주식/메일함/시계 신원은 각자의 완전한 `*_update` 내용과 함께 보낸다. `payload.value` 결과는 0 성공/1 새 데이터 없음/2 네트워크 오류/3 서비스 오류/4 권한 없음.
날씨 위젯 삭제는 레이아웃에서 빼고 로컬 새로 고침을 멈출 뿐이다. "빈 목록으로 마지막 도시 지우기" 펌웨어 계약은 복원되지 않았다.
`glass_preview` type24는 수신 화면 보고이며 제어나 스크린숏이 아니다. data의 `scence`가 scene보다 우선하며, 원래 인덱스는 장면별로 변환해야 한다.

일정/할 일 `0x16`: type5 일정 일괄, 6 할 일 일괄, 15 조회/16 응답, 9 권한 상태. 변경은 현재 serializer를 따로 본다.
일정 startTime/endTime은 Unix **초**, createTime/lastModifiedTime은 밀리초, localTimeOffset은 초다.
total/isLastBatch/eventList, ID, 출처, 상태를 유지한다. 조회 결과가 불완전하면 빠진 항목을 근거로 삭제하지 않는다.
시스템 캘린더는 EventKit 데이터 출처이며 새로운 블루투스 업무가 아니다. 쓰기 대상은 명시적으로 고르고, 실패해도 다른 곳으로 몰래 보내지 않는다. 기본 AI 추가는 안경에만 한다.
선택한 출처의 오늘/내일만 동기화한다. 안경 목록에서 빠졌다고 시스템 이벤트를 지우면 안 된다. 반복 이벤트의 편집/삭제 범위와 종일 이벤트의 배타적 종료일은 EventKit 경로가 처리한다.

알림 `0x15`: type2 내용, 3 상태, 4 답장 `{cmd,notificationUID}`, 0x11 전체 설정.
MAP 요청은 type0x16 `{requestId,enabled:bool}`, type0x17이 `{requestId,state,code?}`로 응답하며 state1 연결/0 끊김이다.
requestId가 일치하는 것만 이번 요청의 완료로 본다. false는 끄기 요청이지 읽기 전용 조회가 아니며, 일반 알림 설정에는 MAP 권한이 포함되지 않는다.
임의 문자 본문/전화번호 전송 계약은 복원되지 않았고, Mac 미러링도 Android MAP/RemoteInput 기능을 제공하지 않는다.
위 제어의 정확한 필드와 API는 문서 끝의 소스와 API 참조에서 관리한다.

## 11. 기능, 유지보수, 클라우드 경계

`0x06` capabilities는 빈 목록에도 OK를 돌려줄 수 있다. "기능 등록"에서 펌웨어 확장/임의 앱 설치나 녹음 등록 필수를 추론하면 안 된다.
OTA `0x09`: type1 버전, 3 NAND 크기(OtaSize=원본 ZIP 바이트), 4 전송 페이지(Mode2 일반), 5 버전 요청, 6 목록, 7 범위 요청, 8 진행, 9 설치, 10 실패.
로컬 패키지는 `OtaFileInfo.json`의 실제 목록/경로/크기/MD5에 맞춰 준비하고, type6은 목록 원문을 유지하며, type7에는 바이너리로 요청에 응답한다.
진행률 100이나 type9는 설치에 들어갔다는 뜻일 뿐이다. 재연결 후 type1 목표 버전이 일치하고 OtaValidationConfirmed=true여야 완료로 표시한다.
확인되지 않은 type2 배터리/11 유휴 응답 필드는 업그레이드 조건으로 쓰지 않는다. 일부 회수한 구성 요소로 완전한 패키지를 흉내 내지 않는다.
로그 `0x0b` type16 `start_log_task`, type17 ACK value0 성공/1 압축 중/2 공간 부족/3 압축 실패/4 기타 오류.
value0의 data는 파일 UUID이며, 기기+UUID 기준으로 범용 파일 전송이 끝나야 다운로드 완료로 본다.

Beta에서는 공식 로그인, 업로드, 클라우드 기억/오디오, 펌웨어 온라인 다운로드, 피드백 전송, `0x19` HTTP 프록시를 끈다.
기존 로컬 캐시는 유지된다. 안경 하드웨어 업무, 로컬 Agent, 휴대폰 동기화, 명시적 로컬 OTA 패키지는 독립된 진입점이다.
경계는 [OfficialCloudPolicy.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/OfficialCloudPolicy.swift)를 기준으로 하며, 이전 클라우드 소스는 검토할 수는 있지만 활성 제품 계약이 아니다.

## 12. 구현과 원시 증거 색인

아래 원시 자료는 로컬 `analysis/`에 보관된다. 여기 다 적지 않은 수치는 해당 serializer/현재 구현에서 가져오며, 예전 UI 문구로 추측하지 않는다.

| 영역 | 현재 구현 | 보관된 역분석 진입점 |
| --- | --- | --- |
| 페어링/이중 링크/복구 | [main.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/main.swift), [BluetoothPairing](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/BluetoothPairing.swift) | [reconnect](../analysis/reverse-2026-11-03/reconnect/), [native-protocols](../analysis/reverse-2026-11-03/native-protocols/) |
| 기능/조기 수신 큐 | [CapabilityController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/CapabilityController.swift), 상주 handler | [capability-registry-init.asm](../analysis/reverse-2026-11-03/capability-registry-init.asm), [pending-message-enqueue.asm](../analysis/reverse-2026-11-03/pending-message-enqueue.asm) |
| 프레임/확장/ACK | main.swift의 ProtocolCodec, RFCOMMTransport | [transport-reader-fallback.java](../analysis/reverse-2026-11-03/transport-reader-fallback.java), [transport-ack-manager.java](../analysis/reverse-2026-11-03/transport-ack-manager.java) |
| 네이티브/종일/실시간 오디오 | NativeAssistantController, LifeLogCaptureStore, SpeechController | [memory-read](../analysis/reverse-2026-11-03/memory-read/), [caption-protocols](../analysis/reverse-2026-11-03/caption-protocols/), [후속 실시간 진입점 감사](../analysis/reverse-2026-11-07/official-interactive/) |
| 네이티브 녹음/오프라인/표시 | NativeRecordingController, RecordingMarker | [recording-orphan-import](../analysis/reverse-2026-11-03/recording-orphan-import/), [recording-marks](../analysis/reverse-2026-11-03/recording-marks/), [원시 패킷](../analysis/reverse-2026-11-03/baseline-hci/) |
| 파일 전송 | FileTransferController | [file_transfer.proto](../analysis/reverse-2026-11-03/apk-native/file_transfer.proto), [SDK 소스](../analysis/reverse-2026-11-03/jadx/sources/K5/) |
| 자막/텔레프롬프터/내비게이션 경계 | [TeleprompterRepository](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/TeleprompterRepository.swift), SpeechController | [display-navigation](../analysis/reverse-2026-11-03/display-navigation/), [glasses-teleprompter-logs](../analysis/reverse-2026-11-03/glasses-teleprompter-logs/) |
| Launcher/AI/크라운/화면 꺼짐 | [GlassesSettingsController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/GlassesSettingsController.swift) | [launcher-setters](../analysis/reverse-2026-11-03/launcher-setters/), [assistant-settings](../analysis/reverse-2026-11-03/assistant-settings/), [device-actions](../analysis/reverse-2026-11-03/device-actions/), [wake-display](../analysis/reverse-2026-11-03/wake-display/) |
| Dashboard/날씨/미리보기 | [DashboardLayoutModel](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/DashboardLayoutModel.swift), [DashboardPreviewStateStore](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/DashboardPreviewStateStore.swift) | [dashboard-serializers](../analysis/reverse-2026-11-03/dashboard-serializers/), [weather-removal](../analysis/reverse-2026-11-03/weather-removal/), [dashboard-preview](../analysis/reverse-2026-11-03/dashboard-preview/) |
| 일정/시스템 캘린더 | [ScheduleController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/ScheduleController.swift), [CalendarSyncModel](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/CalendarSyncModel.swift) | [calendar-writeback](../analysis/reverse-2026-11-03/calendar-writeback/), [mutation-hci](../analysis/reverse-2026-11-03/mutation-hci/) |
| 알림/MAP | [NotificationMirrorModel](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/NotificationMirrorModel.swift) | [notification-replies](../analysis/reverse-2026-11-03/notification-replies/) |
| 기능/OTA/로그/미확인 업무 | [CapabilityController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/CapabilityController.swift), [OTAUpdateController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/OTAUpdateController.swift), [MaintenanceController](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/MaintenanceController.swift) | [maintenance-enums.json](../analysis/reverse-2026-11-03/maintenance-enums.json), [log-ack-handler.asm](../analysis/reverse-2026-11-03/log-ack-handler.asm), [other-device-businesses](../analysis/reverse-2026-11-03/other-device-businesses/) |

SDK 소스, Dart VM 명령 경계, 객체 풀은 [JADX](../analysis/reverse-2026-11-03/jadx/)와
[Blutter](../analysis/reverse-2026-11-03/blutter-output/)에 함께 보관한다. 원시 캡처가 필드 추측보다, 실제 sender가 이름/문자열만 있는 것보다 우선한다.
