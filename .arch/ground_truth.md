# 검증된 현실 사실과 강한 제약 (Ground Truth)

- [GT-01] (검증됨) 사용자 확인: 지금은 안경 자체의 기능을 바꿀 수 없다. OpenRayneo의 주목적은 안경에 이미 있는 공식 기능을 끌어내 2차 처리하는 것이다. -> 아키텍처 제약: 기존 공식 기능과 검증된 연결 경로를 능력의 경계로 삼는다. 동반 소프트웨어는 기능 연결, 출력 확보, 기본 2차 처리, 외부 공개를 맡는다. 안경 펌웨어를 고칠 수 있다거나, 기기 고유 기능을 추가할 수 있다거나, 확인되지 않은 공식 출력이 있다고 가정하지 않는다. 이전의 점진적 배포, 작업 관리 등의 결정은 모두 이 경계 안에서 해석한다.
- [GT-02] (검증됨) 사용자 확인: 휴대폰에 이미 Agent 소프트웨어가 있으며, OpenRayneo는 기존 방식대로 로컬 API를 노출하면 연동할 수 있다. -> 아키텍처 제약: 휴대폰 네이티브 어시스턴트는 로컬 API로 로컬 Agent와 연결한다. Agent는 대화 이해, 답변, 자체 모델이나 네트워크 서비스를 맡는다. OpenRayneo가 이를 위해 원격 Agent 실시간 연결 경로를 따로 만들 필요는 없다.
- [GT-03] 사용자가 확정된 아키텍처대로 개발하도록 승인했으며 서브에이전트에 병렬로 맡길 수 있다. 그래프의 초기 "계획만/추후 구현"은 원래 결정 단계의 설명이다. 현재 구현은 아래 스냅숏과 코드를 본다. solid는 결정이 확정되었다는 뜻이며, 빌드, 인터페이스 검증, 실제 품질은 각각 따로 기록한다. 사용자의 커밋되지 않은 작업을 보존한다.
- [GT-04] (검증됨) 사용자가 화자 처리 정책을 명확히 갱신했다: 안경 2채널 입력의 채널 1은 기본적으로 본인이며, 채널 2에만 음성 지문 매칭과 화자 분할을 한다. 종일 기록은 기본적으로 혼합 음성을 돌려주므로 완전한 혼합 오디오에 화자 처리를 해야 한다. -> 아키텍처 제약: 2채널은 반드시 두 채널을 모두 보존하고 소비한다. 본인 채널은 ASR과 고정 본인 귀속, 환경 채널은 ASR과 음성 지문/분할을 거친다. 실제 입력 모드로 종일 기록의 혼합 mono를 구분하며, 모든 mono 입력을 기본 본인으로 취급하면 안 된다. 오디오 저장, 지연 보충, 전사 출처, 시간 앵커, Agent 전달은 채널 출처와 이전 자료 호환을 유지한다.
- [GT-05] 네이티브 "샤오레이 샤오레이"(중국어 웨이크워드) 연속 대화는 로컬 Agent 대화를 재사용한다. 녹음 기록, 작업, wakeID/SID, Agent 세션 신원은 따로 관리하며 답변은 현재 회차와 대조한다. 명시적인 "새 대화"가 신원을 바꾸며, 취소는 귀속된 요청에만 영향을 준다.
- [GT-06] 사용자가 Mac이 네이티브 capture마다 최신 완전 WAV 한 회차를 자동 저장해 바로 비교에 쓰도록 명확히 요구했다. ASR 확인 흐름과 독립적이며 arm/대기 창이나 추가 60초 저장 제한이 없다. 다음 완전한 회차가 성공하면 이전 오디오는 휴지통으로 가고, 비정상 회차는 직전 유효 latest를 유지하며 captureID로 구분한다.
- [GT-07] 사용자가 `.arch/`를 프로젝트 핵심 문서로 삼고 운영 설명과 계약은 최소한만 두도록 명확히 정했다. 일반 문서는 루트 README, 운영 가이드, 핵심 프로토콜, 동기화 계약으로 모은다. 기존 사실/노드를 갱신하고 회차별 보고서와 중복 아키텍처 그림은 더 만들지 않는다. 핵심 파일 여섯 개는 메인 Git이 일반 파일로 추적하며, `.arch/.git` 섀도 저장소는 로컬에만 두고 무시한다.
- [GT-08] (2026-11-19) 사용자가 Mac 쪽은 더 검증하지 않기로 결정했다. `android-only` 브랜치는 macOS Bridge(`Sources/`, SwiftPM, 오디오 드라이버, Mac 스크립트와 CI, Mac API 스킬, 홍보 영상, Mac 전용 글꼴/아이콘/미리보기 자원)를 지우고 Android, DSH 플러그인, 프로토콜/동기화 문서, `.arch`만 남겼다. GT-06과 그래프의 Mac 노드는 `main` 이력으로만 남는다. Android 쪽 8767 동기화 서비스 코드는 바꾸지 않았고, 이 브랜치에는 Mac 클라이언트가 없다. arch-grower CLI가 로컬에 없어 graph/dashboard는 함께 갱신하지 않았다.
- [GT-09] (2026-11-19) 사용자 요청으로 `android-only`의 문서와 소스 주석/UI 문구를 한국어로 바꿨다. 기존 기록에 저장된 화자 라벨(알 수 없음/본인/혼합)과 이전 수동 지정 사유, 기억 종류는 원래 문자열을 상수로 남겨 호환을 유지하고 화면에는 한국어로 보여 준다. 실제 중국어 입력을 해석하는 코드(내비게이션 알림 파싱, CJK 문자 범위 판정)와 프로토콜 값은 그대로 둔다. `.arch/graph.json`, `dashboard.html`, `grounding.json`은 CLI 관리 대상이라 번역하지 않았다.

## 현재 구현과 미해결 문제

갱신: 2026-11-19. 이 절은 현재 상태를 교체하며 회차별 기록을 덧붙이지 않는다.

| 범위 | 현재 사실 |
| --- | --- |
| 플랫폼 역할 | 이 브랜치는 Android 전용: 휴대폰이 안경에 직접 연결하며 일상 사용과 종일 기록을 맡는다(Mac 쪽은 GT-08 참조). 의미 판단/일반 답변은 외부 Agent가 맡고, 동반 앱은 기본 오디오, ASR, 기록, 내장 요약을 유지한다. |
| 기록 | 종일 타임라인 + 요약. 다른 진입점은 시간/내용/요약을 따로 가지며 일/월 일정으로 통합한다. 원본 오디오, 샘플 offset, 벽시계 앵커를 보존한다. 자원이 부족하면 저장을 먼저 하고 처리는 나중에 보충하며, 과거 수정은 출처를 남긴다. |
| 동기화 | 휴대폰이 원본 권위, Mac은 영구 사본/오프라인 파생. 텍스트/인물/요약은 기본으로 보충하고 원본 오디오는 요청 시. 휴대폰 독립 LAN 진입점 8767과 sync token. 기기/Agent 인터페이스는 loopback. 정확한 필드는 [동기화 계약](../docs/record-sync-contract.md)을 본다. |
| 네이티브 Agent | Android 36부터 DSH 플러그인이 phone/conversation key/preset을 영구 바인딩해 웨이크를 넘어 대화 이력을 잇는다(0.1.8 SDK 격리 검사 30항목: 이력, 콜드 재시작, 취소 귀속, 실패 시 재전송 없음). 0.1.9부터 앱은 `request.context`에 음성 사실 JSON만, `input.answerMaxChars`에 표시 길이를 보내고 프롬프트는 플러그인 `voice-modes.json`(voice-three-way-5)이 소유한다(Android 자산 사본 제거). 실시간/종일·회의 발화는 먼저 route(wait/hint/agent, 기본 wait: 본인의 직접 질문·요청, 막힘, RayNeo 호출만 응답)를 거치며 wait는 빈 답으로 끝나 DSH 세션을 만들지 않는다. agent 메시지는 발화를 `화자: 내용`으로 한 번만 쓰고 지침은 세션 첫 턴에만 넣으며(네이티브 바인딩의 `instructed`는 프롬프트가 큐에 들어간 뒤에만 켬, 0.1.8 바인딩은 이미 받은 것으로 봄), 네이티브는 세션 이력이 있어 직전 요청/답변을 다시 넣지 않는다. 판단 품질은 실기기 확인 필요. |
| 휴대폰 ASR | `asrEngine` 선택: 클라우드 Bailian `qwen-audio-3.1-asr-flash-message`(중간 결과, `disfluency_removal_enabled`, `vad_model=near_meeting_16k`. 네이티브 웨이크는 중간 결과를 0x0d type5 `final:false`로 안경에 보냄). 실기기 로그: 답변 뒤 안경은 재생이 끝날 때 type11로 다음 턴을 연다. 예전 흐름은 답변 전에 2 rc2로 수음을 닫아서 답변 중 말해도 소용이 없었다. 지금은 안경 workMode=1(연결 시 설정 자동 조회)이면 수음을 닫지 않고 답변 직후 다음 턴을 연다(2 rc1만 보내고 9 rc1은 보내지 않음). 답변 중 네이티브 마이크(48k)의 10ms 레벨이 `bargeInLevelDb`(기본 -30dBFS)를 `bargeInMinMs`(기본 250ms) 이상 연속으로 넘으면 바로 9 rc1로 끊는다(ASR을 기다리지 않음). 첫 중간/최종 텍스트(2글자 이상이고 답변 텍스트에 없는 것)도 끊는다. 답변 중 최대 레벨을 로그에 남겨 보정에 쓰며, 끊지 않으면 type11을 기다려 9 rc1을 보낸다. AEC가 없을 때 에코로 잘못 끊기는지는 실기기 확인이 필요하다. `stream`=HF에서 받는 한국어 스트리밍 zipformer(int8 encoder/joiner + fp32 decoder, 엔드포인트 0.8s). `local`=SenseVoice 문장 단위 + 언어 힌트 필터(한글 텍스트는 언어 태그 때문에 버리지 않음). `nemotron`=Nemotron 3.5 다국어 스트리밍 0.6B 560ms int8(sherpa≥1.13.3, stream option `language`: 힌트가 하나면 ko-KR/en-US, 아니면 auto. 4스레드. 앱 안에서 HF 서드파티 미러로 받으며 공식은 tar.bz2뿐). 로컬 모델은 사용자가 `/sdcard/OpenRayneo/models/<공식 압축 폴더 이름>/`에 복사한 파일을 먼저 읽는다(MANAGE_EXTERNAL_STORAGE 필요, 재설치해도 유지). 그 권한이 있으면 앱 안 다운로드도 이 폴더에 바로 쓰고, 이미 앱 안에 있는 사본은 한 번에 옮길 수 있다(크기 확인 후 내부 사본 삭제). 권한이 없으면 앱 내부로 받는다. 모든 로컬 엔진은 내장 GTCRN 스트리밍 잡음 제거를 먼저 거친다. `asrKeepLoaded`(기본 켬)면 고른 로컬 모델을 서비스 시작/설정 저장 뒤 미리 올려 상주시키고 나머지 모델은 바로 해제한다. 끄면 60초 유휴 후 해제한다. 실시간 2채널: 20ms마다 ch1이 ch2보다 `selfVoiceMarginDb`(기본 6dB) 이상 크고 ch1>-50dBFS면 본인 발화로 보고 ch2를 무음 처리해 `selfVoiceHoldMs`(기본 300ms) 동안 유지한다(판정 하한 `selfVoiceFloorDb` 기본 -50dBFS, 세 값 모두 음성 설정 슬라이더로 조절). 절대 음량이 아닌 비율로 판단하므로 바깥 소리가 커서 두 채널이 함께 올라가면 작동하지 않는다. 녹음은 원본 그대로이며 backfill에도 같이 적용된다. 종일 A3의 vpuMask/vadMask는 20ms 프레임마다 실시간 ASR/speaker 입력의 게이트로 쓴다(앞 200ms 뒤 500ms, 원본 오디오는 게이트하지 않음, `glassesVoiceGate`로 끌 수 있음). 네이티브 type3에는 mask가 없으며 회차 시작/끝에 대기 flag를 비운다. 실시간 번역에서 stream을 만나면 SenseVoice(없으면 클라우드)로 바꾼다. 실시간 번역은 문장이 끝나지 않는 연속 발화(영상, 강의)에서도 중간 결과를 문장부호(8자 이상), 60자, 또는 문장 시작 후 3초 단위로 잘라 바로 번역하고, 최종 결과가 이미 보낸 앞부분으로 시작하면 나머지만, 군말 제거 등으로 바뀌었으면 문장 전체를 다시 번역한다(누락보다 중복을 택함). 클라우드 ASR은 `asrLanguage`가 있으면 `language_hints`를 보내며 번역은 항상 `ko,en`이다. 기록 요약 모델 주소/이름은 비어 있으면(저장 시에도 같은 기본값으로 채움) Bailian 국제 OpenAI 호환 `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` / `qwen-plus`를 쓴다(키는 직접 입력). mask 의미와 잡음 제거가 인식률에 주는 영향은 실기기 확인이 필요하다. |
| 채널과 신원 | ORPCM1 이전 mono / ORPCM2 2채널 호환. 실제 2채널은 ch1 기본 본인, ch2 완전 speaker, 네이티브/AON mono는 전부 처리. Opus 인코딩 채널 수와 물리 출처는 따로 본다. 자동 신원 매칭은 이름 있는 인물끼리 점수를 겨루되(1·2위 차 0.08 이상, `speakerThreshold` 기본 0.7) 본인(`self`)일 때만 확정하며, 다른 화자는 기록별 라벨(A, B…)과 speakerSamples만 남기고 인물을 자동 생성하지 않는다(이전에는 라벨마다 "이름 없는 인물"을 만들어 인물이 과도하게 늘었다). 이미 만들어진 이름 없는 인물은 데이터 보존을 위해 그대로 둔다. `audioChannel`은 목소리 등록 때 쓸 마이크만 정한다. |
| 인물 수정 | Android 37은 명시적 바인딩으로 출처 익명 화자를 병합하고 과거 ID를 보존한다. 후보 필터는 이미 이름 붙은 인물과 같은 model이면서 완전히 같은 익명 중복 참조만 거른다. 짧은 구간은 남기고, 겹침은 실제 교집합만 표시한다. |
| 현재 산출물 | Android CI(`main`과 `android-only` 푸시 시 실행) 빌드: versionCode=1000+GITHUB_RUN_NUMBER(로컬 37), BuildConfig.GIT_COMMIT을 설정 화면에 표시, versionName=1.(run-23).0으로 매번 올라가며 Nextcloud 파일 이름에 씀(로컬 1.2.0-beta.4), DSH 0.1.9(현재 앱과 짝, 0.1.8은 모든 발화에 답함). Android 36 release를 되돌리기용으로 두며 경로는 [운영 가이드](../docs/runbook.md)를 본다. |
| 안경 내장 기능 | 안경 메뉴의 Voice memo(`0x0e` 네이티브 녹음), Live captions(`0x13` 자막), Live answers(`0x17` 실시간 프롬프트 계열로 추정)는 화면을 안경 펌웨어가 그리고 휴대폰은 오디오를 받아 텍스트를 돌려주는 구조다. Android는 지금 자기가 연 `0x13`(내비게이션)/`0x17`(실시간 힌트) 세션과 `0x0e` 일부 응답만 다루고, 안경 메뉴가 먼저 여는 세션과 오프라인 녹음 목록/가져오기는 처리하지 않아 이 메뉴들은 동작하지 않는다. Live captions는 앱 기능으로 되살렸다: 안경 화면의 "실시간 자막" 버튼이 실시간 번역과 같은 `0x17` 프롬프트 창과 ASR을 번역 없이 쓴다(사용자 ASR 엔진/언어 힌트, 위 줄 = 끝난 문장, 아래 줄 = 말하는 중). 앱 세션이 아닌 `0x13/0x17` 비오디오 프레임은 종류별 처음 5개를 로그("안경 쪽 화면 신호 수신")에 남기고, `0x13` type 1은 안경 메뉴의 자막 시작 요청으로 가정해, 기록·번역·다른 화면이 없을 때만 앱 자막을 시작한다(최근 닫은 sid의 늦은 응답은 제외). 이 가정과 안경 쪽 페이지와의 충돌 여부는 실기기에서 메뉴를 열어 로그로 확인해야 한다. Voice memo 가져오기와 Live answers는 아직 없다. 화면 배치 자체는 바꿀 수 없고(안경 앱 설치 계약 미복원) 보내는 텍스트와 위 표시 설정만 정할 수 있다. |
| 프롬프터 | 이전에는 앱이 시작한 재생만 처리하고 원고 목록을 안경에 보내지 않았으며 안경이 직접 고른 원고(`0x14` type2 action1)에 응답하지 않아 안경 메뉴의 프롬프터로는 원고를 열 수 없었다. 지금은 연결될 때와 저장/가져오기 뒤 전체 목록을 type1(list type 1 fullSync, 3개씩 chunk)으로 보내고, 안경의 목록 요청(type1 action1)에는 다시 보낸다. 안경이 원고를 고르면 type2 action2 code1(없으면 code5)로 답하고 `0x11`로 본문을 보낸 뒤, 안경의 type3 action1(읽기 시작)에 checksum과 함께 type3 action2 code1로 답한다(protocol.md 9절의 복원 흐름). 원고함의 "텍스트 파일 가져오기"는 여러 텍스트 파일(각 1MB 이하, UTF-8 아니면 CP949)을 파일 이름을 제목으로 저장한다. 안경 메뉴 쪽 흐름과 재생 중 목록 갱신의 펌웨어 반응은 실기기 확인 필요. |
| 검증 범위 | 현재 `5542b1f` 독립 재확인: WSL Debug/Release 빌드와 양쪽 lint 통과(46초, 107개 태스크). 변형별 오류 0, 경고 24, 힌트 2. Android 단위 테스트는 NO-SOURCE. 새 복사본의 DSH `npm ci` 및 잠금 의존성을 사용한 모듈 검사 15건 통과. 실제 기기 미연결로 UI/음성/장시간 동작은 미검증. 후속 소스 검토에서 확인한 동기화 시퀀스 후퇴 R12와 음성 설정의 남은 이중 상태 R13은 다음 커밋에서 고쳤고 WSL `assembleDebug`+`lintDebug` 통과(lint 종류별 개수 동일), 독립 리뷰 지적 없음. |

**현재 핵심 문제: 실제 사람 음성 지문 놓침과 한 사람 오분류.** 실제 59.64초 네이티브 입력을 저장했고, 사용자는 본인만 말했다고 확인했다. 결과는 5구간/3그룹, 최고 일치 0.624(나머지 0.602/0.399)이며 현재 기준값은 0.70이다. 등록 참조 6개를 하나씩 빼고 교차 검증하니 2개가 0.659/0.674에 그쳤다. 같은 네이티브 녹음의 A/B 상호 cos는 0.641이다. 짧은 문장 특징의 불안정과 등록/매칭 기준의 불일치는 실증되었고, 등록 ch1과 네이티브 mono 수음 차이가 따로 얼마나 기여하는지는 아직 검증이 필요하다. 이 결과로 모델/기준값/프로필을 바꾸지는 않았다.

로컬 익명 지표와 모델 조건은 `analysis/speaker-debug-2026-11-16/case.json`에 모았고, 공개된 4인 비교 오디오는 그 `samples/`에 있다. 원본 본인 WAV는 앱의 latest 정책이 계속 관리한다. 이후 확인된 같은 사람/다른 사람 샘플로 보정하되, 먼저 사람 목소리/잡음 걸러내기와 짧은 문장 묶기를 확인한다. "등록 성공"만으로 인식 가능하다고 판단하지 않는다.

**기타 알려진 경계:** 공식 계정/공식 클라우드 업로드·다운로드/HTTP 프록시는 꺼진 분기다. 기기 간 음성 지문 벡터 동기화는 미구현이며 Mac과 휴대폰의 음성 지문 참조는 독립적이다. Android 네이티브 오프라인 녹음 가져오기, 실제 종일 백그라운드 생존, 배터리 소모는 아직 기기 검수 차이가 있다. 창 포커스가 포그라운드 표시를 동기화하지만, 과거에 있었던 웨이크 복구에는 아직 단일 원인 결론이 없다. 정상 연결을 유지한 채 먼저 이벤트를 확인하고, AI 설정을 무작정 쓰거나 페어링을 반복하지 않는다.

## 구현과 증거 진입점

- `android/app/src/main/java/com/openrayneo/android/`: 휴대폰 서비스, 오디오/인물/기록, API와 동기화. `integrations/dsh-go/`: 외부 Agent 어댑터.
- [핵심 프로토콜](../docs/protocol.md)은 메시지/연결 계약과 원시 역분석 색인을, [운영 가이드](../docs/runbook.md)는 빌드, 설치, 장애 복구를 관리한다.
- 유일한 이전 소스 차이는 Ubuntu `openrayneo-lab` 보관본에 있다. `analysis/`는 원시 역분석 증거, 모델 실험 소스, 로컬 지표를 보관하며, 빌드 산출물과 사용자 Library 자료는 따로 관리한다. 프로토콜/CLI의 전송, 기기 확인, 착용 시 보이는 효과는 각각 검증한다.

## 프로젝트 결함 및 UI·UX 검토

검토 기준: 2026-11-19, `android-only`의 `5542b1f`. 최초 항목의 코드 위치는 `47fb0d2`, 후속 R12/R13 위치는 `5542b1f` 기준이다.
최초 검토는 `a914718`의 Android/Mac 소스를 대상으로 10개 항목을 정리했다. 이후 Android 전용 전환과 문서 교체로 해당 절이 사라져, 같은 ID로 현재 상태와 근거를 복원했다. 이번 작업은 검사와 문서화이며 앱 코드 수정은 하지 않았다.

**현재 결론:** 기존 Android 문제 7개의 대상 경로에 수정이 들어간 것을 확인했고 R11의 잠금 설치 실패는 새 폴더에서 해결을 재확인했다. 후속으로 확인한 R12(손상 기록 제외 후 동기화 시퀀스 후퇴)와 R13(엔진 외 음성 설정의 표시/초안 불일치)도 코드로 고쳤다. 남은 것은 실기기 동작 확인과 테스트 소스 부재다. R10 언어 표시는 개선되었고 Mac 2개 항목은 범위 밖이다. P1은 데이터 보존/서비스 가용성, P2는 주요 기능·개발 재현성/사용성, P3은 표현 일관성이다. UI와 장애 경로는 소스 검토이며 실기기 재현을 완료한 것으로 간주하지 않는다.

아래 Android 파일명은 `android/app/src/main/java/com/openrayneo/android/`를 기준으로 하며 줄 번호는 `47fb0d2` 기준이다.

| ID | 우선순위·상태 | 근거, 영향, 개선·확인 기준 |
| --- | --- | --- |
| R01 | P1 · 수정됨(코드) | `ConversationRepository.kt:24–25,138–144`가 시작 시 전체 기록을 복호화/파싱하며 개별 실패를 분리하지 않는다. `ConversationController.kt:48` → `BridgeService.kt:117`의 초기화로 예외가 전파될 수 있어 손상 기록 하나가 연결·기록 서비스 시작을 막을 위험이 있다. 손상 원본을 보존·표시하고 정상 기록과 서비스를 이용 가능하게 해야 한다. 개발용 복제 데이터 한 파일의 손상/읽기 실패로 검증할 것. 사용자 데이터 손상은 관측되지 않았다. **수정:** 읽기/복호화에 실패한 `session-*.enc`는 건너뛰고 파일은 그대로 두며, 개수와 파일명을 기록 화면 오류로 표시한다. 인물 파일 실패는 덮어쓰기 위험 때문에 그대로 실패시킨다. |
| R02 | P1 · 수정됨(코드) | `RecordSyncServer.kt:20,34,38–41,56`의 4스레드 풀에 accept와 처리를 함께 넣고, 수락한 소켓/대기 작업 수를 제한하지 않는다. timeout은 처리 진입 후 적용되며 인증 전 연결도 자원을 점유한다. 느린 연결 집중 시 정상 동기화 지연과 자원 누적 위험. 수락 단계 슬롯 제한과 전체 요청 기한이 필요하다. 격리 환경에서 열린 소켓 상한과 정상 요청/중지 동작을 확인할 것. 실제 LAN 부하 실험은 하지 않았다. **수정:** accept 전용 스레드, 슬롯 4개 세마포어(초과 연결은 즉시 닫음), 토큰·출처 확인 전 20초 전체 기한. 종료 경합의 `RejectedExecutionException`도 슬롯을 반환한다. |
| R03 | P1 · 현재 범위 제외 | 이전 `a914718`의 `Sources/OpenRayneoBridge/TeleprompterLibraryPanel.swift:132–145,218–244`: 자동 저장 실패 반환값을 버리고 다른/새 원고로 전환하여 미저장 초안을 덮어쓰는 경로. 저장 성공 확인 후에만 전환하도록 하는 것이 개선안이었다. `78bbfa7`에서 Mac 코드 제거. 수정 완료가 아니라 대상 제거로 분류한다. |
| R04 | P2 · 수정됨(코드) | `VoiceSettingsScreen.kt:29–33,128–136`의 화면 엔진은 저장된 `config`, 저장 동작은 지속되는 `formDrafts`를 참조한다. 엔진 A 저장 → B 선택 → 미저장 이탈 → 재진입하면 화면은 A, 초안은 B가 될 수 있다. 단일 초안 상태로 통일하고 재진입/회전 후 표시·모델 안내·저장 엔진이 같은지 확인할 것. **수정:** 라디오 선택을 `formDrafts`에서만 읽어 표시·저장 엔진이 같다. |
| R05 | P2 · 수정됨(코드) | `CompanionApp.kt:52–55`: `LaunchedEffect(model.notice)`가 `notice=null`로 자신의 키를 바꾸고 `showSnackbar`를 호출한다. 재구성 때 효과가 취소되어 저장/복사 완료 안내가 짧게 사라질 위험. 안정적 이벤트 소비 또는 표시 종료 후 안전한 소비가 필요하다. 연속 알림과 실제 표시 시간을 기기에서 검증할 것. **수정:** 스낵바가 끝난 뒤에만 같은 알림을 지운다. |
| R06 | P2 · 수정됨(코드) | `ConversationScreen.kt:28,45–47`의 상세 선택을 상위 `CompanionApp.kt:39–50`의 뒤로 가기가 알지 못하고 `tab=0`으로 이동한다. 상세에서 시스템 뒤로 가기가 목록 대신 안경 탭으로 간다. 상세 화면이 먼저 뒤로 가기를 소비해야 하며 화면 내 버튼과 시스템 제스처 모두 기존 날짜/검색을 보존한 목록으로 돌아와야 한다. **수정:** 상세 화면 안의 `BackHandler`가 먼저 상세를 닫는다. |
| R07 | P2 · 수정됨(코드) | `CompanionLibraryScreen.kt:78–81,91–92,101`: 원고 초안은 `remember`에만 있으며 다른 원고 선택/새 원고가 즉시 덮어쓴다. 재생성·이탈·전환으로 미저장 초안이 유실될 수 있다. ViewModel/초안 저장소와 저장·폐기 선택을 제공하고 회전/뒤로 가기/전환별 보존을 확인할 것. **수정:** 원고 초안을 `rememberSaveable` + `SaveableStateProvider("teleprompter")`로 보존하고, 미저장 변경이 있으면 원고 전환/새 원고 전에 버리기 확인을 띄운다. |
| R08 | P2 · 수정됨(코드) | `ConversationScreen.kt:243`의 `distinct().take(6)` 때문에 7번째 이후 화자는 개별 필터로 선택할 수 없다. 전체 타임라인은 남는다. 임의 상한 제거 또는 전체 화자 선택기가 필요하며 7명 이상 기록으로 확인할 것. **수정:** 화자 칩 상한 제거(가로 스크롤). |
| R09 | P2 · 현재 범위 제외 | 이전 `a914718`의 `TeleprompterLibraryPanel.swift:260–271,406`: 확인 없는 삭제가 로컬 휴지통 이동과 안경 삭제 동기화로 이어짐. 확인/실행 취소와 복구 안내가 개선안이었다. 로컬 휴지통 복구가 가능하여 영구 삭제로 분류하지 않았다. Mac 제거로 현재 범위 밖이다. |
| R10 | P3 · 코드 개선 확인 | 이전 수동 사유 `手动标注`의 한국어 화면 직접 노출. 현재 `ConversationModels.kt:10–20`에 과거 값 보존/표시 변환이 있고, `ConversationScreen.kt:172`가 `displayLabel`을 사용한다. `ConversationRepository.kt:90`은 한국어 상수를 저장하며 파서는 과거/현재 수동 사유를 모두 인식한다. 양쪽 빌드 통과, 실기기에서 과거 기록 표시 확인은 남아 있다. |
| R11 | P2 · 수정됨(실행 확인) | DSH `npm ci --ignore-scripts --no-audit --no-fund` 실패. 잠금 파일에 `@deepseek-ai/dsh-storage-domain`과 그 의존성 해석이 누락되어 깨끗한 재설치를 재현할 수 없다. 오류에는 storage-domain/storage `0.2.2-rc.5`와 zod 해석 누락이 보고되었다. 해당 잠금 파일과 peer 의존성 선언은 Claude 작업 전부터 같은 상태라 이번 번역 변경의 새 회귀로 귀속하지 않는다. 승인된 SDK 버전으로 잠금 파일을 갱신한 뒤 새 폴더의 `npm ci`로 확인해야 한다. 이번에는 원본 잠금 파일을 바꾸지 않았다. **수정:** 선언된 peer 범위 그대로 `npm install --package-lock-only`로 잠금 파일을 다시 만들었다(storage/storage-domain `0.2.2-rc.5` 추가). 새 폴더 `npm ci` 통과. |

기존 음성 지문 오분류, 에코 오감지, 종일 기록의 전력·발열·백그라운드 생존은 위 현재 사실의 미해결 항목을 유지한다. 작은 화면/큰 글꼴의 긴 한국어 버튼, TalkBack, 키보드와 저장 버튼 접근성, 다운로드 실패/재시도는 추가 실기기 검수 항목이며 이번에 확정한 결함으로 집계하지 않았다. HTTP 기록 동기화의 신뢰 네트워크 제약은 기존 동기화 계약에 따른 것으로 별도 신규 결함으로 중복 집계하지 않는다.

### 수정 후 확인된 문제와 후속 수정

**R12 · P1 · 수정됨(코드) · 손상 기록 제외 후 동기화 시퀀스 후퇴.** **수정:** 발급한 최고 sequence를 기록과 별도인 `sequence-mark`(AtomicFile)에 데이터 파일보다 먼저 쓰고, 시작 시 읽을 수 있는 기록의 최대값과 표식 중 큰 값을 쓴다. 표식이 없거나 손상된 상태에서 읽을 수 없는 기록이 있으면 현재 시각(ms)으로 건너뛴다. [동기화 계약](../docs/record-sync-contract.md)에 반영. 실기기 손상 재현은 남아 있다. 원래 지적: `ConversationRepository.kt:26–32`는 읽을 수 없는 기록을 제외한 뒤 남은 records/people의 최대값으로 `sequence`를 다시 만든다. `81,127–128`은 이 값을 증가시켜 저장하고 클라이언트 cursor보다 큰 값만 내보낸다. 예를 들어 클라이언트가 cursor=100까지 받은 뒤 sequence=100의 기록이 손상되고, 정상 객체 최대가 10이면 재시작 후 새 기록은 11부터 저장된다. 해당 클라이언트는 100을 넘을 때까지 새 기록을 받지 못하며 중간 객체는 이후 수정하지 않으면 계속 누락될 수 있다. 기존 서비스 시작 실패는 완화됐지만 동기화 계약의 영구 단조 증가 조건은 깨질 수 있다. 기록과 독립된 영구 high-water mark 또는 명시적 epoch/전체 재동기화 절차가 필요하다. 개발용 복제 데이터에서 최고 sequence 기록만 손상시킨 뒤 기존 cursor로 새 기록이 도착하는지 확인해야 한다. 이번에는 암호화 사용자 기록을 손상시키거나 실제 동기화 장애를 유발하지 않았다.

**R13 · P2 · 수정됨(코드) · 엔진 외 음성 설정의 표시/저장 불일치.** **수정:** 상주 모델, 언어 힌트, 자동 응답, 음성 게이트, 자동 요약 체크박스가 모두 `formDrafts`를 직접 읽는다. 기본값 의미(없음=켜짐/자동 응답은 꺼짐)는 그대로다. 원래 지적: `VoiceSettingsScreen.kt:65–91,107–108`의 상주 모델, 언어 선택, 자동 응답, 음성 게이트, 자동 요약은 여전히 `remember(model.config)`로 표시 상태를 만들고 변경 시 `formDrafts`에도 쓴다. 엔진 라디오만 초안을 직접 읽도록 바뀌었다. 예: 저장된 자동 응답=false → 켬 → 저장 없이 이탈/재진입하면 화면은 꺼짐, 초안은 true → 저장하면 자동 응답이 켜질 수 있다. 언어·게이트도 같은 경로다. 모든 해당 컨트롤을 초안에서 직접 읽도록 통일하고 미저장 이탈/재진입/회전 후 표시와 저장값을 대조해야 한다. 실제 기기 검증은 남아 있다.

## 완료 신호 이후 재검증 결과

### 현재 수정 커밋 재확인 — `5542b1f`

사용자의 수정 확인 요청에 따라 Git 상태와 변경된 Kotlin 파일 및 잠금 파일을 대조했다. 시작 시 코드 미커밋 변경 없이 `ok`만 미추적이었다. `git -c core.autocrlf=false archive`로 새 WSL 검증 폴더 `/home/green/openrayneo-validation/5542b1f`를 만들었고 앱 소스는 수정하지 않았다. arch-grower는 여전히 찾지 못해 graph/dashboard를 직접 다루지 않았다.

- Android: 기존 운영 명령의 `assembleDebug`, `assembleRelease`, `lintDebug`, `lintRelease`, `testDebugUnitTest`, `testReleaseUnitTest`를 두 ABI(`arm64-v8a,x86_64`)로 한 번에 실행. **BUILD SUCCESSFUL, 46초, 107개 태스크**. 양쪽 lint 각각 오류 0/경고 24/힌트 2로 이전과 동일. 단위 테스트는 둘 다 `NO-SOURCE`라 0건이다. 빌드 로그는 검증 폴더 `verification-gradle.log`, lint 보고서는 `android/app/build/reports/`에 보존했다.
- DSH: 새 Windows Temp 복사본 `openrayneo-verify-5542b1f/dsh`에 `npm ci --ignore-scripts --no-audit --no-fund` 실행, **22개 패키지 설치 성공**. 일부 npmmirror tarball 재시도 경고가 있었으나 최종 종료 코드는 0. 이번에는 `npm install` 우회를 쓰지 않았다. JS 4개 구문 검사와 아래에 설명된 동일한 격리 모듈 검사 **15/15**를 잠금 파일의 실제 의존성으로 통과했다. DSH 실서비스/기기 세션을 실행한 검사는 아니다.
- ADB `devices -l`은 연결 기기 0대. 화면 조작, 손상 기록/느린 연결의 Android 실행 재현, 실제 음성 및 백그라운드 검증은 미실시. 새 영구 테스트/CI/기기 설치는 하지 않았다.
- 기존 수정 확인: R01 기록 파일 개별 오류 격리/안내, R02 accept 분리·4슬롯·인증 전 20초 기한, R04 엔진 초안 일원화, R05 Snackbar 완료 후 메시지 소비, R06 상세 BackHandler, R07 원고 상태 보존/변경 폐기 확인, R08 필터 상한 제거를 코드에서 확인했다. R01 후속 영향과 R04의 유사 잔여 경로는 R12/R13으로 남긴다.
- `git diff 47fb0d2..5542b1f --check` 통과. 현재 검사 익명 지표는 [analysis/verification-5542b1f/results.json](../analysis/verification-5542b1f/results.json)에 저장했다. 이전 지표는 덮어쓰지 않았다. 검토 결과 문서와 README만 갱신했고 코드 수정/커밋/배포는 하지 않았다.

### 이전 기준선 — `47fb0d2`

**총평: Android 빌드·정적 검사는 통과했지만 전체 품질 통과로 종료하지 않는다.** 아래 표는 `47fb0d2` 시점 기록이다. 이후 R01–R08(Mac 제외)과 R11 수정으로 DSH 잠금 기반 재설치는 통과하며, 실행할 Android 단위 테스트 부재와 실제 기기 미검증이 남아 있다.

### 작업 인계와 기준선

- 사용자 요청대로 최상위 `D:\rayneo_io\OpenRayneo\ok`를 약 60초 간격으로 확인했다. 처음에는 없었으며 `2026-11-19 02:06:54 +09:00` 수정 시각의 3바이트 파일(본문 `ok`) 생성을 확인한 뒤 검사를 시작했다. 신호 파일은 직접 생성/삭제/수정하지 않았다.
- Claude 완료 후 `78bbfa7`(Android 전용 전환), `47fb0d2`(한국어화)를 확인했다. 검사 시작과 종료 시 HEAD는 동일하고 미추적 파일은 `ok`였다. 이전 Mac 검토를 현재 Android 검사에 섞지 않았다.
- 작업 중 원본 코드/의존성/CI/서명 자료를 수정하지 않았다. 최종 변경은 기존 `ground_truth.md`, `README.md`, `docs/runbook.md`의 문서와 무시되는 익명 결과 JSON이다. 커밋, 배포, 기기 설치는 하지 않았다.

### 환경과 실행 결과

Windows에서 Node `24.22.1`, npm `11.19.1`, `uv`를 사용했다. Windows PATH에는 Java/Swift/ADB가 없었지만, 추가 조사로 WSL Ubuntu-24.04의 Temurin JDK `17.0.21+9`, Android SDK Platform 36/Build-Tools 36.0.0과 Windows WinGet의 ADB를 찾았다. WSL에서 D: 자동 마운트 경로를 사용할 수 없어 Git 기준 소스를 별도 Linux 폴더로 내보냈다.

| 검사 | 결과 | 증거와 범위 |
| --- | --- | --- |
| `:app:assembleDebug`, `:app:assembleRelease` | 통과 | `arm64-v8a,x86_64`, Gradle 8.14. 첫 묶음 실행 3분 37초, 103개 태스크 실행. Kotlin/Java 컴파일과 APK 생성 완료. |
| `:app:lintDebug`, `:app:lintRelease` | 통과, 경고 있음 | 각각 오류 0, 경고 24, 힌트 2. Release 전체 lint는 별도 실행하여 20초에 완료. 두 보고서가 같은 위치를 지적하므로 48개 독립 결함으로 합산하지 않는다. |
| `:app:testDebugUnitTest`, `:app:testReleaseUnitTest` | `NO-SOURCE` | 영구 테스트 소스 없음, 실행 테스트 0건. 테스트 성공 건수로 세지 않는다. |
| APK `apksigner verify --verbose` | 2개 통과 | Debug/Release 모두 v2 서명 유효. 이 검증용 로컬 키가 기존 배포 키와 같은지는 검증하지 않았으며 사용자 기기에 덮어 설치하지 않았다. |
| APK ZIP/메타데이터/내용 | 2개 통과 | ZIP CRC 이상 없음. 패키지 `com.openrayneo.android`, 버전 `37 / 1.2.0-beta.4`, minSDK 33/targetSDK 36. 두 ABI의 ONNX/sherpa JNI 라이브러리와 campplus/silero-vad/GTCRN/segmentation 모델 4개 포함 확인. |
| DSH JS 구문 | 4개 통과 | `api.js`, `harness.js`, `index.js`, `native-sessions.js`에 `node --check`. |
| JSON 및 모드 일치 | 통과 | package/lock/두 voice-modes JSON 4개 파싱, Android와 DSH voice-modes 구조 동등 확인. |
| DSH `npm ci` | `47fb0d2` 실패 → 수정 후 통과 | R11. 잠금 파일 재생성 후 새 폴더에서 22개 패키지 설치 성공. |
| DSH 임시 의존성 설치/격리 모듈 검사 | 15/15 통과 | 복사본에 `npm install --package-lock=false --ignore-scripts --no-audit --no-fund`. SDK `0.2.2-rc.5`, schemastery `3.19.0`, zod `4.6.7`. 실제 서비스 대신 모의 세션 컨트롤러 사용. 잠금 파일 재현성·실제 DSH 대화 검증과 구별한다. |
| DSH 배포 패키지 | 통과 | `npm pack --dry-run` 및 실제 `npm pack --ignore-scripts`, tgz 16,779바이트/9개 항목, 최상위 `package/`와 필요한 lib/manifest/설명 확인. 설치·게시하지 않았다. |
| Python/XML/shell | 통과 | `uv run --no-project python`으로 `phone-codex.py` AST 1개와 Android XML 6개 파싱. Linux `sh -n`으로 모델 준비 스크립트와 gradlew 2개 검사. |
| Git 변경 공백 검사 | 통과 | `git diff a914718..47fb0d2 --check`; 최종 문서 수정도 `git diff --check`로 확인. |
| ADB/실기기/에뮬레이터 | 미실시 | ADB `devices -l`은 성공했으나 연결 기기 0대. WSL SDK에 emulator/system-images/AVD 없음. Bluetooth, 실제 UI, 오디오, LAN 동기화 실동작, 전력·백그라운드 검사는 하지 못했다. |
| Mac/arch-grower | 제외/실행 불가 | Mac은 현재 브랜치에서 제거됨. arch-grower CLI는 로컬 스킬 경로에서 찾지 못해 focus/export-ui 미실행. graph/dashboard를 직접 읽거나 수정하지 않았다. |

격리 모듈 검사 15건: 네이티브 scope 신원, backfill 제외, 비네이티브 제외, 신원 누락 제외, preset 분리, 신규 세션 생성/저장, 기존 세션 재사용, 일시 오류 시 신규 생성 방지, 없는 세션 재생성, 취소 시 생성 방지, 저장 메타데이터 스키마, LAN API 거부, HTTPS API 거부, API 오류의 일시성 분류, SDK와 플러그인 모듈 import. 검사는 stdin으로 실행하여 영구 테스트 파일을 추가하지 않았다.

### lint와 빌드 주의 항목

| 종류 | 변형별 개수 | 해석 |
| --- | --- | --- |
| UseKtx | 15 | SharedPreferences/URI 편의 API 사용 제안. 동작 실패로 확인된 것은 없음. |
| GradleDependency / AndroidGradlePluginVersion | 3 / 1 | 도구·라이브러리 업데이트 제안. 검사 중 버전을 변경하지 않았다. |
| ChromeOsAbiSupport | 1 | 기본 `arm64-v8a` 설정에 대한 지적. 이번 명령은 두 ABI를 지정했고 실제 두 APK에 x86_64가 들어 있음을 별도 확인했다. |
| HardwareIds | 1 | `RayneoConnection.kt:334` 식별자 사용 검토 필요. |
| DataExtractionRules | 1 | Manifest의 백업/기기 전송 규칙을 명시적으로 검토할 것. |
| ObsoleteSdkInt / UnusedResources | 1 / 1 | 불필요한 v26 리소스 구분과 미사용 색상. |
| AutoboxingStateCreation | 힌트 2 | Int/Float 전용 Compose 상태 사용 제안. |

빌드 중 일부 네이티브 라이브러리의 심볼 제거를 하지 못해 원본 그대로 패키징했다는 경고와 Gradle 9 비호환 예정의 deprecated API 경고가 있었다. 현재 Gradle 8.14 빌드는 성공했으며 배포 크기/도구 업그레이드 검토에 남긴다.

### 검사 중 장애와 복구 기록

- 최초 Windows `gradlew.bat --version`은 Java 부재로 실패했다. WSL에 이미 설치된 JDK/SDK를 찾아 사용했으며 시스템 Java를 새로 설치하지 않았다.
- 최초 소스 archive는 Windows 줄바꿈 설정으로 shell 스크립트가 CRLF가 되어 실행되지 않았고, WSL 명령 전달 중 PATH/변수 인용 문제도 있었다. `git -c core.autocrlf=false archive`로 기준 커밋을 다시 내보내고 고정 Linux PATH와 직접 인자를 사용하여 해결했다. 저장소의 원래 Git blob은 LF였으며 앱 소스 결함으로 분류하지 않았다. APK 서명 검사도 초기 인자 전달 실패 후 명시적 경로로 재실행해 통과했다.
- 최초 `npm pack --dry-run`은 저장소 루트에서 실행하여 package.json 부재로 실패했다. 플러그인 폴더에서 다시 실행해 dry-run과 실제 pack 모두 통과했다.
- `npm ci` 실패는 실제 잠금 파일 문제로 남겼다. 추가 모듈 검사를 위해서만 Temp 복사본에 잠금 파일을 쓰지 않는 설치를 수행했다. 원본 package/lock은 변경하지 않았다.
- `.agents/skills/` 잔여 디렉터리에는 접근 거부가 있어 Git/파일 탐색 경고가 나왔다. 현재 Android 전용 HEAD에는 Mac API 스킬이 없으며, 권한 변경/강제 삭제 없이 검사했다.
- 검증에 필요한 Gradle/모델/npm 의존성을 캐시에 내려받았고 ADB 조회가 로컬 ADB 서버를 시작했다. 사용자 모델·녹음·기기 설정을 열거나 바꾸지 않았다.

### 결과와 산출물 보존

- 익명 검사 지표와 경고 위치: [analysis/verification-47fb0d2/results.json](../analysis/verification-47fb0d2/results.json). Git 무시 대상이며 개발 PC의 재현 자료다. 경고 파일 경로는 저장소 상대 경로로 정리했고 자격 증명·오디오를 넣지 않았다.
- WSL 검증 복사본: `/home/green/openrayneo-validation/47fb0d2/`. `verification-gradle.log`, `verification-lint-release.log`, `android/app/build/reports/lint-results-{debug,release}.{xml,html}`, `android/app/build/outputs/apk/{debug,release}/app-*.apk`를 보존했다.
- Windows 임시 작업 폴더: `C:/Users/green/AppData/Local/Temp/openrayneo-verify-47fb0d2/`. LF 교정 전/후 소스 archive, DSH 의존성 검사 복사본(`dsh/`), 패키지 `openrayneo-dsh-0.1.8.tgz`가 있다. 캐시/임시 폴더 정리 시 없어질 수 있으며 현재 기준의 영구 결론은 이 문서에 남긴다.
- 재현 명령과 환경은 [운영 가이드](../docs/runbook.md#개발-검증-명령)에 유지한다. 상주 테스트/CI를 새로 추가하거나 `ok`를 커밋하지 않았다.
