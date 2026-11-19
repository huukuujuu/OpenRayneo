# OpenRayneo × DSH Go

OpenRayneo owns the glasses connection, audio storage, ASR, local speaker processing and record summaries.
The phone's external Agent decides whether to wait, answer, call tools or run a workflow.
Current delivery: plugin **0.1.9** with the current Android build. From 0.1.9 the app sends the voice facts as JSON and the plugin decides
whether an answer is needed: realtime and all-day/meeting speech first goes through a short wait/hint/agent check, so ordinary talk
opens no DSH session. Agent turns show each utterance once as `speaker: text`; instructions are sent only on a session's first turn.
Use 0.1.9 with current app builds (0.1.8 would answer every utterance); 0.1.9 still accepts the older app's prose context.
Architecture and current limitations live in the workspace `.arch/`; operations live in `docs/runbook.md`.

## Install and connect

Install this directory once:

```sh
dsh plugin --profile web add /absolute/path/openrayneo-dsh
```

For a packaged update, replace the installed plugin directory with `openrayneo-dsh-0.1.9.tgz` contents.
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
The persisted binding is scoped by phone installation, native conversation key and selected Agent preset.
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
