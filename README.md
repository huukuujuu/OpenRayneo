# OpenRayneo

<img src="docs/assets/openrayneo-logo.png" alt="OpenRayneo" width="120" />

RayNeo iO 안경용 Android 동반 앱이다. 안경에 이미 있는 기능을 끌어내 수음, 기본 처리, 기록, 로컬 Agent 연동을 하며, 일상에서 쓰기 쉬운 것과 종일 기록에 중점을 둔다.

`android-only` 브랜치에는 Android 앱과 DSH 플러그인만 있다. macOS Bridge, 오디오 드라이버, Mac API 스킬, 홍보 영상 소스는 `main` 이력에 남아 있으며 이 브랜치에서는 더 이상 빌드하거나 관리하지 않는다.

## 아키텍처가 주 문서다

- [아키텍처 대시보드](.arch/dashboard.html): `.arch/graph.json`에서 만든 현재 노드와 계약(그래프의 Mac 노드는 이력 기록).
- [사실, 현재 구현, 미해결 문제](.arch/ground_truth.md): 개발과 검수의 현재 기준선.
- [북극성](.arch/SEED.md)과 [폐기한 방안](.arch/graveyard.md): 목표와 선택의 근거.
- [개발 규칙](AGENTS.md): 아키텍처, 코드, 최소 문서를 관리하는 규칙.

저장소 루트에서 `arch-grower`를 쓴다.

```sh
uv run ~/.agents/skills/arch-grower/scripts/arch_cli.py focus --node speaker_channel_policy
uv run ~/.agents/skills/arch-grower/scripts/arch_cli.py export-ui --out .arch/dashboard.html
```

아키텍처 그래프는 CLI로 갱신한다. 노드 결정, 현재 구현, 검증 결과, 미해결 문제를 따로 관리하며, 새 작업은 기존 사실과 노드를 갱신한다.

## 꼭 필요한 운영 문서와 계약만 둔다

| 하려는 일 | 문서 |
| --- | --- |
| 설치, 빌드, 연결, DSH, 음성 디버그, 장애 복구 | [운영 가이드](docs/runbook.md) |
| 기기 프레임, 인증, 제어 생명주기, 역분석 증거 색인 | [핵심 프로토콜](docs/protocol.md) |
| 휴대폰 기록 동기화 서비스, 커서, 파생 회신 | [동기화 계약](docs/record-sync-contract.md) |
| 휴대폰 로컬 DSH Agent 플러그인 | [플러그인 설명](integrations/dsh-go/README.md) |

현재 후보 패키지와 품질 문제는 [구현 스냅숏](.arch/ground_truth.md#현재-구현과-미해결-문제)을 본다. 공식 계정/공식 클라우드 서비스 분기는 현재 Beta에서 꺼져 있으며, 안경 펌웨어와 공식 앱이 이미 가진 기능이 기기 능력의 경계다.

최근 [결함·UI/UX 검토](.arch/ground_truth.md#프로젝트-결함-및-uiux-검토)와 [재검증 결과](.arch/ground_truth.md#완료-신호-이후-재검증-결과): 2026-11-19, `a6ca12c`. 공식 APK 반영 후 변경 파일 30개와 관련 경로를 검토했다. 양쪽 빌드/lint, DSH 잠금 설치·격리 검사 11건은 통과했지만 세션 최종 확인 누락(R14), 본인 전용 자동 식별 우회(R15), 반복 자막 누락(R16)을 재현했다. 세 건은 다음 커밋에서 고쳤다. 상세 재현·개선 기준과 미검증 범위는 기존 검토 문서에 기록했으며 실기기 검수는 남아 있다.

[Apache License 2.0](LICENSE). 이 프로젝트는 RayNeo와 무관하며, 공식 APK, 펌웨어, 사용자 녹음, 자격 증명은 소스와 함께 배포하지 않는다.
