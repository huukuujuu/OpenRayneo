# 만남 기록 동기화 계약 v1

휴대폰이 기록, 전사, 요약, 인물 프로필의 권위 있는 원본이다. Mac은 영구 사본과 Agent 파생 항목의 오프라인 큐를 가진다.
휴대폰 주소와 전용 자격 증명을 설정하면 Mac은 15초마다 동기화를 시도한다. 네트워크가 끊겨도 사본을 읽고 메모를 쓸 수 있으며 다음에 이어서 교환한다.
기기 와이어 프로토콜은 [protocol](protocol.md), 정확한 로컬 경로는 [API 참조](https://github.com/huukuujuu/OpenRayneo/blob/a914718/.agents/skills/openrayneo-api/references/api.md)를 본다.
`android-only` 브랜치에는 Mac 클라이언트가 없다. 이 문서는 휴대폰 서버가 지키는 계약으로 남긴다.

## 1. 수신 범위와 권한

휴대폰 만남 동기화의 기본 주소는 `http://<phone>:8767`이며 반드시 `Authorization: Bearer <sync-token>`을 보내야 한다.
이 문서의 exchange/audio 두 경로만 연다. 로컬 Agent token과 분리되어 있어 따로 켜고 끄고 자격 증명을 교체할 수 있다.
로컬 Agent 제어 진입점은 여전히 loopback(휴대폰 기본 8766)이며, Mac 기기 제어 API도 loopback에만 바인딩한다.
동기화를 켰다고 안경 제어나 인물 편집 인터페이스를 LAN에 노출하지 않는다.
현재는 명시적으로 도달 가능한 주소를 쓰며 클라우드 중계가 없다. HTTP 자격 증명은 신뢰할 수 있는 로컬 네트워크로만 전달해야 한다.
휴대폰은 두 수신 범위의 활성화 설정을 영구 저장하며, 프로세스가 재시작되면 이전에 켠 항목을 복원한다.

## 2. 증분 교환

`POST /v1/sync/exchange`, JSON:

```json
{
  "protocolVersion": 1,
  "cursor": 0,
  "limit": 100,
  "derived": [{
    "id": "UUID", "recordId": "record UUID", "revision": 1,
    "sourceRevision": 7, "kind": "note", "text": "추가 메모",
    "tags": [], "agent": "local-agent", "updatedAt": 1794800000000,
    "stale": false
  }]
}
```

응답:

```json
{
  "protocolVersion": 1, "cursor": 12, "hasMore": false,
  "records": [], "people": [], "acceptedDerivedIds": ["UUID"]
}
```

- 휴대폰은 전역 단조 증가 change sequence를 영구 관리한다. 기록과 인물은 같은 시퀀스를 쓰며, 객체마다 현재 최신 스냅숏을 보낸다. 최고값은 기록과 별도인 `sequence-mark` 파일에 먼저 기록하므로 읽을 수 없는 기록을 건너뛰어도 뒤로 가지 않는다. 표식이 없거나 손상된 상태에서 읽을 수 없는 기록이 있으면 현재 시각(ms)으로 건너뛴다. sequence는 증가만 보장하며 연속성은 보장하지 않는다.
- sequence 순으로 정렬하며 records/people를 **합쳐서** limit(현재 범위 1–100)를 차지한다. cursor=0이면 페이지를 넘겨 현재 객체 전체를 받는다.
- 이후에는 sequence가 cursor보다 큰 객체만 받는다. 응답 cursor는 이번 배치의 마지막 sequence이며, 빈 배치는 요청 값을 유지한다.
- `hasMore=true`면 다음 페이지를 이어 받는다. records만 동기화하고 같은 커서 범위의 people를 건너뛰면 안 된다.
- 들어온 derived를 먼저 적용한 뒤 이번 변경 페이지를 계산한다. 파생 수락도 기록 스냅숏 변경에 들어간다.
- 원 기록의 `revision`은 휴대폰만 올린다. 파생 확인이 원본 버전을 올리지 않으며, 시퀀스 커서는 원본 revision이 아니다.
- Mac은 기록, 인물, 파생, outbox, 새 cursor를 원자적으로 디스크에 쓴 뒤에만 메모리 진행 상태를 갱신한다. 쓰기에 실패하면 이전 상태를 유지하고 다시 받는다.
- Mac은 완전한 객체의 알 수 없는 JSON 필드를 보존한다. 중복 페이지는 id/revision으로 병합하며 기록을 중복 추가하지 않는다.
- 빈 페이지나 목록 누락은 삭제를 뜻하지 않는다. 현재 프로토콜에는 범용 기기 간 삭제 묘비나 원격 전체 삭제 명령이 없다.

## 3. 원본 객체와 시간

기록의 핵심 필드는 아래와 같다. 새 필드는 하위 호환이며, 읽는 쪽은 이전 필드 허용 목록으로 객체를 다시 만들지 않는다.

| 필드 | 계약 |
| --- | --- |
| `id,revision` | 휴대폰 기록 신원과 원본 버전 |
| `mode` | `DAILY` / `MEETING` / `ENROLL` |
| `source` | `REALTIME` / `NATIVE` / `ALWAYS_ON` / `VOICE_MEMO`(안경 메뉴 음성 메모, 저장 오디오에서 나중에 전사) |
| `createdAt,endedAt` | Unix 밀리초, endedAt은 비어 있을 수 있음. 오디오 샘플 offset이 아님 |
| `title,segments,summary,summaryStale` | 원문과 이전 버전 요약 호환 필드 |
| `summaries,memories,derived` | 요약 스냅숏, 기억, 파생 |
| `processingPending,audioPending` | 처리 대기 / 원본 오디오 쓰는 중. ASR/모델 실패로 진행 상태를 지우면 안 됨 |
| `audioId,audioSamples,audioChannels` | 오디오 신원, 채널당 16k frames, 실제 저장 채널 수 |
| `speakerPeople,channelPolicy,processingCursors` | 기록 내 신원 매핑, 채널 정책, 채널별 처리 커서 |
| `audioAnchors` | 수음 회차별 PCM offset과 휴대폰 수신 벽시계 매핑 |

`audioAnchors`는 `[{"offsetMS":20000,"receivedAt":1794800000000,"timeBasis":"received"}]` 형태다.
실제 수신 시각을 저장하며, 안경이 검증 가능한 원래 발화 시각을 준다고 주장하지 않는다.
종일 기록의 회차 사이 무음을 "활성화 시각 + 누적 PCM 길이"로 압축하면 안 된다. 재생은 원래 앵커를 따른다.

`segment.beginMS/endMS`는 기록 PCM 샘플 영역의 밀리초이며 ASR/speaker 정렬과 원본 오디오 자르기에 쓴다.
`timelineAt/timelineEndAt`은 앵커로 매핑한 수신 벽시계 범위다. `timeBasis`는 `received` 또는 이전 저장소의 `legacy-estimate`다.
일/월 목록과 일일 요약은 벽시계 기준으로 날짜를 나눈다. 실제 단어 시간이 앵커를 넘으면 나눠 묶을 수 있고, 단어 시간이 없는 문장은 실제 범위를 유지하며 단어 시간을 지어내지 않는다.
Mac 사본은 이 필드를 보존한다. 기록 범위 조회는 여전히 7절의 기록 단위 구간을 쓰므로, 소비자가 문장별로 날짜를 나누려면 timeline 필드를 읽어야 한다.

세그먼트는 `id,text,speaker,personId,stable,mixed,revision,receivedAt,roundId,candidates,matchReason`도 함께 보존한다.
알 수 없음, 겹침, 본인 미인식도 그대로 보관/동기화한다. 인물 이름을 얻으려고 텍스트를 버리지 않는다.
`processingMode=live/backfill`은 처리 경로를 뜻한다. 보충으로 처음 생긴 세그먼트는 로컬 Agent에 넘길 수 있지만, 이미 끝난 이전 세그먼트는 다시 보내지 않는다.
Agent 맥락에는 `timelineAt,timeBasis,asrCompletedAt,age_seconds,processing_delay_seconds`가 들어가며 age/delay는 수신 앵커 기준이다.
종일 진입점의 약 20초 원천 지연은 따로 적는 추정치일 뿐이며, 이를 근거로 네이티브 시간을 고치거나 의미 판단을 대신하지 않는다.
DSH `input.backfill=true`는 과거 맥락이며, 플러그인은 과거 지시를 새 작업으로 자동 실행하지 않는다.

`summaries` 항목마다 `kind,text,createdAt,recordIds,stale,day`가 있다.
kind는 rolling/record_end/day_end다. day_end.day는 `YYYY-MM-DD`이며, 자정을 넘는 기록은 여러 날 스냅숏에 들어갈 수 있다.
원본 세그먼트/인물 귀속이 바뀌면 관련 파생과 요약에 stale을 표시한다. 당일 새 기록도 기존 day_end의 출처 집합을 만료시킨다.

인물에는 `id,name,self,contactUri,model,revision`과 병합/수정 메타데이터가 있다.
`mergedInto`는 은퇴한 신원과 과거 연결을 보존하며, 이전 인물 ID를 지우거나 전체 세그먼트를 자동으로 다시 쓰는 것이 아니다.
명시적 수정/병합/분리는 휴대폰이 과거 범위를 골라 영향받은 기록을 수정한다. 수동 귀속은 오디오 보충이 덮어쓰면 안 된다.
기본 교환은 `speakerSamples`, 인물 samples/embedding, 원본 오디오를 뺀다. Mac 인물 스냅숏을 음성 지문 추론용 참조 라이브러리로 쓰면 안 된다.
Mac 로컬 음성 지문 자료와 휴대폰 음성 지문 자료는 독립적이며, 현재 이 프로토콜로 원시 벡터를 동기화하지 않는다.

## 4. 2채널과 이전 mono

`audioChannels=1/2`는 **실제 저장된** 채널 수다. 이전 ORPCM1에 필드가 없으면 기본 1이다.
`audioSamples`는 채널마다 공유되는 frame 수이며 stereo 인터리브 short 총수가 아니다. PCM 샘플레이트는 16000이다.

| 정책/필드 | 의미 |
| --- | --- |
| `dual_channel1_self` | 실제 2채널 REALTIME: ch1은 독립 ASR, 기본 본인, embedding/diarization 생략. ch2는 독립적인 완전 speaker/ASR |
| `full_speaker` | 네이티브, 종일 혼합, 실제 mono는 모두 완전한 speaker로 처리 |
| `legacy_selected_mono` | 이전 버전은 선택한 한 채널만 저장. 잃어버린 다른 채널을 만들어 낼 수 없음 |
| `segment.sourceChannel` | 보관 PCM의 채널 번호 1/2. mono/혼합도 1이므로 이것만으로 본인이라고 볼 수 없음 |
| `identityBasis` | `channel1_default_self` / `voiceprint` / `unknown` / `mixed` / `manual` |

channelPolicy + sourceChannel + identityBasis를 보존한다. 두 채널이 같은 문장이거나 둘 다 본인과 일치해도 어느 채널도 지우지 않는다.
Opus coded channels는 프레임마다 바뀔 수 있으므로, TOC를 근거로 stereo 세션 구간을 "실제 mono"로 바꾸면 안 된다.

`processingCursors={"1":frames,"2":frames}`는 채널별 영구 완료 커서다.
복구는 min(cursor)부터 공유 raw를 읽은 뒤 채널별 cursor로 이미 끝난 부분을 잘라낸다. ch1이 앞서 있어도 ch2를 건너뛰지 않는다.
음성 없이 성공해도 실제로 끝난 frames까지 진행시킨다. max(text.endMS)만 쓰면 무음 채널이 영원히 다시 실행된다.
audioAnchors는 공유하고 segment ID에는 채널 구분이 들어간다. 과거 분리는 sourceChannel로 해당 PCM을 가져온다.

휴대폰 내부의 ORPCM2는 채널 수를 담은 암호화된 인터리브 PCM이며 ORPCM1 mono도 여전히 읽을 수 있다.
writer는 채널당 1초 단위로 블록을 나눈다(mono 32000 / stereo 64000바이트). 큰 append가 reader가 받지 않는 초과 길이 블록을 쓰면 안 된다.
seek/recover는 frame 단위이며 마지막 블록은 실제 길이를 유지한다. 이 내부 암호화 형식은 기기 간에 쓰지 않으며, Mac은 JSON과 WAV만 소비한다.

## 5. 파생, 확인, 오프라인 재시도

파생 kind는 `analysis/note/tags`다. id는 UUID, revision은 만든 쪽이 올리며, sourceRevision은 분석 근거가 된 원본 버전을 가리킨다.
Mac은 원본 전사나 원본 인물을 쓰지 않는다. 같은 id를 편집할 때는 로컬 색인과 기록 안 derived에서 알려진 최대 revision을 찾아 올린다.
휴대폰은 id/revision으로 멱등 처리한다. 같은 버전이나 더 새 버전이 이미 있으면 확인하고, 기록이 없으면 확인하지 않는다. 적용 후 스냅숏에 그 파생이 들어간다.
원본 revision이 바뀌면 stale=true가 된다. 새 분석은 새 sourceRevision으로 갱신할 수 있고 이전 분석은 보존된다.

`acceptedDerivedIds`에는 ID만 들어가며, 확인 대상은 **이번 요청에서 보낸 버전**이다.
X/rev1을 보낸 뒤 로컬에서 rev2가 생겼다면, rev1 확인으로 rev2 outbox를 지우면 안 된다.
현재 Mac은 API 수정, 네트워크 교환, 영구 저장을 같은 직렬 큐에서 처리하므로 교환 중 revision/peer를 동시에 바꿀 수 없다.
나중에 비동기로 바꾸더라도 전송 스냅숏의 revision과 peer 맥락으로 확인해야 하며, ID만으로 큐를 비우게 바꾸면 안 된다.
확인된 항목도 사본에 남고, 확인되지 않은 항목은 영구 보존된다. 네트워크 타임아웃 시 같은 파생을 다시 보낼 수 있으며 두 번째 항목을 만들지 않는다.

peer 주소가 바뀌면 명시적으로 `resetCursor:true`를 주고 0부터 전체를 받아야 한다. 이전 사본은 유지된다.
이전 peer 요청의 결과/커서를 새 peer에 적용하면 안 된다. 현재는 같은 직렬 큐 덕분에 전환이 진행 중인 교환 사이에 끼지 않는다.
원본 계정/휴대폰이 바뀌어도 이전 사본을 암묵적으로 지울 권한이 생기지 않으며, 현재 휴대폰 간 신원 자동 병합은 없다.

## 6. 요청 시 오디오

`GET /v1/sync/audio?recordId=<URL 인코딩된 UUID>`는 `audio/wav`를 돌려준다.
`GET /v1/sync/logs?bytes=<1000~2000000, 기본 300000>`은 같은 토큰으로 휴대폰 앱 로그(내부 파일 `logs/app.log` + 이전 파일, 각 1MB 회전)의 최신 부분을 `text/plain; charset=utf-8`로 돌려준다. 안경 신호 본문, 인식/번역 오류와 상태가 들어 있으므로 기록과 같은 신뢰 범위로 다룬다.
쓸 수 있는 오디오가 없으면 404, 아직 오디오를 쓰는 중이면 409다. 알려진 recordId로만 해석하며 임의 파일 경로를 읽을 수 없다.

WAV는 16 kHz PCM16, 실제 mono/stereo이며 2채널은 frame 단위로 인터리브된다.
`Content-Length = 44 + audioSamples * audioChannels * 2`.
stereo frame 수를 총 샘플 수로 보지 않으며, 2채널 바이트에 mono 헤더를 붙이지 않는다.
Mac은 명시적으로 요청했을 때만 내려받아 별도 파일로 캐시하며, 일반 교환에서는 오디오를 가져오지 않는다. 로컬 API는 그 캐시 경로를 돌려준다.

## 7. Mac 로컬 Agent 경로

아래 경로는 Mac loopback API와 기존 Bearer 인증을 재사용하며, 휴대폰 LAN sync token을 재사용하지 않는다.

| 경로 | 계약 |
| --- | --- |
| `GET /v1/records` / `POST /v1/records/query` | `from/to` Unix ms, recordId, text. 사본을 돌려줌 |
| `GET /v1/records/people` | 음성 지문 samples를 뺀 인물 스냅숏 |
| `POST /v1/records/derived` | 파생 제출. id/revision/updatedAt/agent를 생략하면 기본값 생성 |
| `POST /v1/records/audio` | `{recordId}`, 오디오 캐시를 명시적으로 가져옴 |
| `GET /v1/records/sync` | token을 뺀 상태, cursor, pendingCount, 오류 |
| `PATCH /v1/records/sync` | `{peerURL,token,enabled,resetCursor?}` |
| `POST /v1/records/sync` | 큐에 넣고 즉시 한 번 시도. 큐 등록 영수증을 교환 완료로 보지 않음 |

Mac 기록 단위 조회는 `[createdAt,endedAt)`와 `[from,to)` 반개구간의 교집합을 쓴다. endedAt=null이면 조회 시각까지 이어진다.
휴대폰 로컬 조회에서 비어 있는 endedAt은 audioPending일 때만 now까지 이어지고, 그렇지 않으면 실제 segment.endTimeOf나 createdAt을 쓴다.
둘 다 누적 PCM offset을 날짜를 넘는 발화 벽시계로 바로 쓰지 않는다. 정확한 문장별 시간은 timelineAt/timelineEndAt을 써야 한다.

## 8. 코드 근거

- 휴대폰 프로토콜과 수신: [RecordSyncServer.kt](../android/app/src/main/java/com/openrayneo/android/RecordSyncServer.kt).
- 원본 revision, 페이지, 파생 멱등, 인물 수정: [ConversationRepository.kt](../android/app/src/main/java/com/openrayneo/android/ConversationRepository.kt).
- 필드와 이중 시간: [ConversationModels.kt](../android/app/src/main/java/com/openrayneo/android/ConversationModels.kt).
- 원본 오디오 블록/seek/recover/WAV: [MeetingAudioStore.kt](../android/app/src/main/java/com/openrayneo/android/MeetingAudioStore.kt).
- 보충과 독립 lane 커서: [ConversationController.kt](../android/app/src/main/java/com/openrayneo/android/ConversationController.kt).
- Mac 원자 사본, 파생 outbox, peer 큐: [RecordSyncController.swift](https://github.com/huukuujuu/OpenRayneo/blob/a914718/Sources/OpenRayneoBridge/RecordSyncController.swift).
