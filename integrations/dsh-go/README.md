# OpenRayneo × DSH Go

OpenRayneo owns the glasses connection, audio storage, ASR, local speaker processing and record summaries.
The phone's external Agent decides whether to wait, answer, call tools or run a workflow.
Current delivery: plugin **0.1.16** with the current Android build. 0.1.16 runs Live answers as one continuous DSH session per record: every heard sentence goes into it, with no separate classification call, and the session itself decides from the whole conversation when the wearer needs a hint (it answers `PASS` otherwise). One turn runs at a time; sentences heard meanwhile join the next turn. The app's realtime hint and always-on still use the one-call wait/hint/agent decision. 0.1.15 always uses folders: a blank `sessionFolder` means `OpenRayneo`, and a relative folder is under the home folder rather than the engine's working folder; wake starts a new conversation there. The 안경 상태 action shows `v<version> · 폴더 있음/없음`. 0.1.14 makes listening answers faster and stops one-session-per-answer: one model call now decides wait/hint/agent and already writes the hint, up to 6 requests run at once (2 before, so answers waited behind later sentences), and agent answers of one listening record reuse one DSH session per feature. The engine log shows `OpenRayneo 플러그인 <version> 시작 · 세션 폴더 …` on start and each folder/workspace it uses; the 안경 상태 action shows the loaded version. 0.1.13 only lets known features name a folder (anything else uses `RayNeo 작업`) and replaces a local phone-side edit that was also called 0.1.12 (its `직접깨움`/`실시간듣기`/`종일기록`/`실시간답변` folders and bindings stay but are no longer used). Plugin code takes effect only after the DSH engine restarts: before that the engine keeps the old code in memory. 0.1.12 gave each glasses feature its own sub-folder, registered as a DSH workspace so DSH Go lists it (`RayNeo 깨우기`, `RayNeo 실시간 답변`, `RayNeo 실시간 힌트`, `RayNeo 종일 기록`, `RayNeo 작업`); a session created with only a folder belongs to no workspace and DSH Go does not show it. Listening modes still open a DSH session only when the router picks `agent`: `wait` and `hint` answer without one. 0.1.11 put every OpenRayneo DSH session in one folder (`sessionFolder`, default `OpenRayneo` under the folder DSH was started in; `~/...` or an absolute path also work, empty keeps the DSH default). Updating from 0.1.10 with a folder set starts a new native conversation there; the old sessions stay in DSH. If the folder cannot be created the session runs in the DSH default folder and is not kept for the next wake. 0.1.10 added the glasses' Live answers mode (`mode: live_answers`): its own routing, hint and agent prompts suggest replies to what other people ask the wearer, while the app's realtime hint keeps answering only the wearer. From 0.1.9 the app sends the voice facts as JSON and the plugin decides
whether an answer is needed: realtime and all-day/meeting speech first goes through a short wait/hint/agent check, so ordinary talk
opens no DSH session. Agent turns show each utterance once as `speaker: text`; instructions are sent only on a session's first turn.
Use 0.1.16 with current app builds (0.1.9 treats Live answers like the realtime hint; 0.1.8 would answer every utterance); it still accepts the older app's prose context.
Architecture and current limitations live in the workspace `.arch/`; operations live in `docs/runbook.md`.

## Install and connect

Install this directory once:

```sh
dsh plugin --profile web add /absolute/path/openrayneo-dsh
```

For a packaged update, replace the installed plugin directory with `openrayneo-dsh-0.1.16.tgz` contents.
The npm archive has a top-level `package/`; use `--strip-components=1` when extracting into that directory.
Update the companion APK and restart the DSH engine after replacing plugin code.
Existing connection credentials and saved phone records remain.

In OpenRayneo enable **DSH Go 및 외부 Agent → 로컬 Agent 엔드포인트 켜기**, then copy its address and token.
In DSH Go tap **Connect / 안경 연결** and allow clipboard access.
The plugin stores the token as the `OPENRAYNEO_TOKEN` credential; clipboard content stays out of prompts.
The default local URL is `http://127.0.0.1:8766`; set the actual Service URL for a custom port.
Keep both phone apps allowed to run in the background.
Record encounter sync uses a separate token and port **8767**; it does not expose device tools.

## Tools and records

The plugin registers `rayneo_status`, `rayneo_tools` and `rayneo_call`.
Read the actual tool definitions before use; they define the available glasses and phone operations.
`record_query` searches by record ID, text or Unix-millisecond interval `[from,to)`.
`record_annotate` attaches `analysis`, `note` or `tags` using the queried `sourceRevision`.
Use a stable annotation `id` for retries; source corrections mark derived results stale.
Todo/calendar mutations require stable `request_id` values and readback after uncertain outcomes.
Raw audio, voice embeddings, model API keys and vendor-cloud credentials are not needed by this plugin.

## Voice delivery and conversation reuse

Completed ASR and speaker results are delivered together, including unknown or mixed segments.
The encrypted phone outbox preserves pending inputs while DSH is unavailable and answers until delivery.
Live native wake-word inputs reuse one DSH conversation across wake, record and task IDs.
The persisted binding is scoped by phone installation, native conversation key, selected Agent preset and session folder.
Stable native keys require Android36 or newer; other sources and historical backfill retain separate sessions.
Phone **어시스턴트 → 새 대화** rotates the native key and clears local short context without deleting records.
Changing the Agent preset chooses its own binding; stale replies cannot enter a new conversation.

The reply uses the current native wake/realtime SID when valid, otherwise a glasses notification.
Stopping capture preserves an already submitted task; cancellation targets only the owned request.
Manual DSH input and other requests keep their own lifecycle; already executed operations remain.
Same-key requests serialize until the prior turn and cancellation cleanup are quiescent.
Bindings survive plugin reload and DSH cold restart through the real session controller and storage domain.
Only a genuinely missing session may be recreated before prompting; transient failures do not replay prompts/tools.
Terminal delivery retries use readback. Approvals and phone-control permissions remain in DSH Go.

Backfill carries `input.backfill=true`, original time facts and processing delay.
It updates historical context; it is not a fresh instruction to execute past commands.
Completed old utterances are not replayed. Explicit current authorization still governs new actions.
