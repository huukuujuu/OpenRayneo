// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Pure decision logic. The service commits the state before executing effects or accepting callbacks. */
internal object GlassesCoordinator {
    fun reduce(previous: GlassesState, event: GlassesEvent): GlassesTransition {
        var state = previous
        val effects = mutableListOf<GlassesEffect>()
        fun cancelRestore() {
            if (state.restoreRevision != null) effects += GlassesEffect.CancelRestore
            state = state.copy(restoreRevision = null)
        }
        fun setIntent(intent: DisplayIntent) {
            cancelRestore()
            state = state.copy(intent = intent, revision = state.revision + 1,
                ownership = DisplayOwnership.APP, answerPermit = null, blockedRevision = null, error = null)
        }
        fun resetSession() {
            cancelRestore()
            effects += GlassesEffect.ResetPage
            effects += GlassesEffect.StopAudio
            effects += GlassesEffect.CancelCloseTimeout
            state = state.copy(session = null, audio = AudioState())
        }
        fun returnIntent(target: ReturnTarget): DisplayIntent = when (target) {
            ReturnTarget.NAVIGATION -> if (state.navigation.enabled) DisplayIntent.Navigation else DisplayIntent.Idle
            ReturnTarget.PROMPT_STANDBY -> DisplayIntent.PromptStandby
            ReturnTarget.IDLE -> DisplayIntent.Idle
        }
        fun showAnswer(content: AnswerContent) {
            val target = when (val intent = state.intent) {
                DisplayIntent.Navigation -> ReturnTarget.NAVIGATION
                DisplayIntent.PromptStandby, DisplayIntent.BlankPrompt -> ReturnTarget.PROMPT_STANDBY
                is DisplayIntent.Answer -> intent.returnTo
                DisplayIntent.Idle, DisplayIntent.Translation -> ReturnTarget.IDLE
            }
            setIntent(DisplayIntent.Answer(content.ref, target))
            state = state.copy(answer = content)
        }
        when (event) {
            GlassesEvent.BeginConnection -> {
                resetSession()
                setIntent(DisplayIntent.Idle)
                state = state.copy(navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
                state = state.copy(link = LinkState(LinkPhase.CONNECTING, "안경에 연결 중"), disconnecting = false)
            }
            is GlassesEvent.LinkChanged -> {
                val lost = state.link.phase == LinkPhase.FULL && event.state.phase != LinkPhase.FULL
                if (lost) {
                    resetSession()
                    state = state.copy(revision = state.revision + 1, answerPermit = null, blockedRevision = null, error = null)
                }
                state = state.copy(link = event.state)
                if (state.disconnecting && event.state.phase == LinkPhase.DISCONNECTED) state = state.copy(disconnecting = false)
            }
            is GlassesEvent.NavigationChanged -> {
                state = state.copy(navigation = event.state)
            }
            is GlassesEvent.SelectNavigation -> {
                setIntent(DisplayIntent.Navigation)
                state = state.copy(navigation = event.state)
                effects += GlassesEffect.FollowNavigation(checkNotNull(event.state.key))
            }
            GlassesEvent.StopNavigation -> {
                val current = state.intent
                val next = if (current is DisplayIntent.Answer && current.returnTo == ReturnTarget.NAVIGATION)
                    current.copy(returnTo = ReturnTarget.IDLE) else if (current == DisplayIntent.Navigation) DisplayIntent.Idle else current
                val owner = state.ownership
                setIntent(next)
                state = state.copy(ownership = owner, navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
            }
            is GlassesEvent.ForgetAnswer -> {
                if (state.answer?.ref?.source == event.source) state = state.copy(answer = null)
                val intent = state.intent as? DisplayIntent.Answer
                if (intent?.ref?.source == event.source) setIntent(returnIntent(intent.returnTo))
            }
            GlassesEvent.PauseDisplay -> setIntent(DisplayIntent.Idle)
            GlassesEvent.OpenStandby -> {
                setIntent(DisplayIntent.PromptStandby)
                state = state.copy(standby = TranslationView(), standbyAnswer = null)
            }
            is GlassesEvent.StandbyText -> state = state.copy(standby = event.view)
            is GlassesEvent.StandbyAnswer -> state = state.copy(standbyAnswer = event.content)
            is GlassesEvent.StandbyAnswerPage -> state.standbyAnswer?.takeIf { it.ref == event.ref }?.let {
                state = state.copy(standbyAnswer = it.copy(page = event.page.coerceIn(0, it.pages.lastIndex)))
            }
            is GlassesEvent.ClearStandbyAnswer -> if (state.standbyAnswer?.ref == event.ref) state = state.copy(standbyAnswer = null)
            is GlassesEvent.StartTranslation -> {
                setIntent(DisplayIntent.Translation)
                state = state.copy(translation = TranslationView(), captions = event.captions, glassesCaptions = event.glassesPage)
            }
            is GlassesEvent.GlassesOffered -> {
                state.offer?.takeIf { it.sid != event.offer.sid }?.let { effects += GlassesEffect.DeclineOffer(it) }
                state = state.copy(offer = event.offer)
            }
            is GlassesEvent.ClearOffer -> state.offer?.takeIf { event.sid == null || it.sid == event.sid }?.let {
                if (event.decline && state.link.phase == LinkPhase.FULL) effects += GlassesEffect.DeclineOffer(it)
                state = state.copy(offer = null)
            }
            is GlassesEvent.TranslationText -> if (state.intent == DisplayIntent.Translation) state = state.copy(translation = event.view)
            GlassesEvent.ClearDisplay -> if (state.session?.mode == PageMode.PROMPT && state.ownership == DisplayOwnership.APP) {
                setIntent(DisplayIntent.BlankPrompt)
            }
            GlassesEvent.RetryAudio -> state.session?.takeIf {
                it.mode != PageMode.FLASHCARD && state.link.phase == LinkPhase.FULL && state.ownership == DisplayOwnership.APP &&
                    !state.disconnecting && it.phase in setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY)
            }?.let {
                state = state.copy(audio = AudioState(AudioPhase.WAITING_PACKET, "디코더 재시작됨, 새 안경 오디오 패킷 대기"))
                effects += GlassesEffect.StopAudio
                effects += GlassesEffect.StartAudio(it.id, it.mode.channels)
            }
            GlassesEvent.DismissAnswer -> (state.intent as? DisplayIntent.Answer)?.let { setIntent(returnIntent(it.returnTo)) }
            GlassesEvent.Disconnect -> {
                setIntent(DisplayIntent.Idle)
                state = state.copy(navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
                state = state.copy(disconnecting = true)
            }
            is GlassesEvent.AnswerRequested -> state = state.copy(answerPermit = AnswerPermit(event.ref, state.revision))
            is GlassesEvent.CancelAnswer -> if (state.answerPermit?.ref?.source == event.source) state = state.copy(answerPermit = null)
            is GlassesEvent.AnswerCompleted -> {
                if (state.answerPermit == event.permit && event.permit.ref == event.content.ref &&
                    event.permit.revision == state.revision && state.link.phase == LinkPhase.FULL &&
                    state.ownership == DisplayOwnership.APP && !state.disconnecting) showAnswer(event.content)
            }
            is GlassesEvent.RevealAnswer -> if (!state.disconnecting) showAnswer(event.content)
            is GlassesEvent.AnswerPage -> state.answer?.takeIf { it.ref == event.ref && (state.intent as? DisplayIntent.Answer)?.ref == event.ref }?.let {
                state = state.copy(answer = it.copy(page = event.page.coerceIn(0, it.pages.lastIndex)))
            }
            is GlassesEvent.SessionSignal -> {
                val session = state.session
                if (session?.id != event.id) return GlassesTransition(state, emptyList())
                when (val signal = event.signal) {
                    is PageSignal.Closed -> if (session.phase == PagePhase.CLOSING) {
                        effects += GlassesEffect.CancelCloseTimeout
                        state = state.copy(session = null)
                        if (signal.error != null) state = state.copy(error = signal.error, blockedRevision = state.revision)
                    }
                    is PageSignal.Ended -> {
                        resetSession()
                        setIntent(DisplayIntent.Idle)
                        state = state.copy(ownership = DisplayOwnership.YIELDED, error = signal.message)
                    }
                    else -> if (session.phase != PagePhase.CLOSING) when (signal) {
                        is PageSignal.AudioAccepted -> {
                            state = state.copy(audio = AudioState(AudioPhase.WAITING_PACKET, "오디오 세션 확인됨, 안경 오디오 대기"))
                            effects += GlassesEffect.StartAudio(session.id, signal.channels)
                        }
                        PageSignal.LayoutPending -> state = state.copy(session = session.copy(phase = PagePhase.LAYOUT))
                        PageSignal.Settling -> state = state.copy(session = session.copy(phase = PagePhase.SETTLING))
                        is PageSignal.Written -> {
                            val latest = signal.content == session.content
                            state = state.copy(session = session.copy(writtenContent = signal.content,
                                phase = if (latest) PagePhase.READY else session.phase))
                            val answer = state.intent as? DisplayIntent.Answer
                            if (latest && session.mode != PageMode.FLASHCARD && answer?.returnTo == ReturnTarget.NAVIGATION && state.navigation.enabled && state.restoreRevision != state.revision) {
                                cancelRestore()
                                state = state.copy(restoreRevision = state.revision)
                                effects += GlassesEffect.ScheduleRestore(session.id, state.revision, answer.ref)
                            }
                        }
                        is PageSignal.Failed -> {
                            cancelRestore()
                            state.offer?.let { effects += GlassesEffect.DeclineOffer(it); state = state.copy(offer = null) }
                            state = state.copy(error = signal.message, blockedRevision = state.revision, answerPermit = null)
                        }
                        else -> Unit
                    }
                }
            }
            is GlassesEvent.AudioChanged -> if (state.session?.id == event.id && state.session?.phase != PagePhase.CLOSING) state = state.copy(audio = event.state)
            is GlassesEvent.RestoreAnswer -> {
                val answer = state.intent as? DisplayIntent.Answer
                if (state.session?.id == event.id && state.session?.phase == PagePhase.READY &&
                    state.revision == event.revision && answer?.ref == event.ref && state.ownership == DisplayOwnership.APP) {
                    setIntent(returnIntent(answer.returnTo))
                }
            }
            is GlassesEvent.CloseTimedOut -> if (state.session?.id == event.id && state.session?.phase == PagePhase.CLOSING) {
                state.offer?.let { effects += GlassesEffect.DeclineOffer(it); state = state.copy(offer = null) }
                resetSession()
                state = state.copy(error = "페이지 닫기가 완료되지 않았습니다. 다시 시도하세요. 안경의 실제 페이지는 알 수 없습니다", blockedRevision = state.revision)
            }
            is GlassesEvent.ExternalPage -> {
                if (state.ownership == DisplayOwnership.YIELDED && state.session == null && state.intent == DisplayIntent.Idle)
                    return GlassesTransition(state, emptyList())
                resetSession()
                setIntent(DisplayIntent.Idle)
                state = state.copy(ownership = DisplayOwnership.YIELDED, error = event.description)
            }
        }

        val target = target(state)
        val session = state.session
        if (state.link.phase == LinkPhase.FULL && session != null && session.phase != PagePhase.CLOSING &&
            (target == null || target.first != session.mode || state.blockedRevision == state.revision)) {
            cancelRestore()
            state = state.copy(session = session.copy(phase = PagePhase.CLOSING), audio = AudioState())
            effects += GlassesEffect.StopAudio
            effects += GlassesEffect.ScheduleCloseTimeout(session.id)
            effects += GlassesEffect.ClosePage(session.id)
        } else if (state.link.phase == LinkPhase.FULL && target != null && state.blockedRevision != state.revision) {
            if (session == null) {
                // A pending glasses start request is answered by the first page of its business; any other page drops it.
                val offer = state.offer?.takeIf { it.mode == target.first }
                state.offer?.takeIf { it != offer }?.let { effects += GlassesEffect.DeclineOffer(it) }
                val newSession = PageSession(state.nextSession, target.first, PagePhase.STARTING, target.second, offer = offer)
                state = state.copy(session = newSession, nextSession = state.nextSession + 1, offer = null)
                effects += GlassesEffect.StartPage(newSession)
            } else if (session.phase != PagePhase.CLOSING && session.content != target.second) {
                state = state.copy(session = session.copy(content = target.second))
                effects += GlassesEffect.UpdatePage(session.id, target.second)
            }
        }
        if (state.disconnecting && state.session == null && state.link.phase != LinkPhase.CLOSING) {
            state = state.copy(link = LinkState(LinkPhase.CLOSING, "안경 연결 해제 중"))
            effects += GlassesEffect.DisconnectTransport
        }
        return GlassesTransition(state, effects)
    }

    private fun target(state: GlassesState): Pair<PageMode, PageContent>? {
        if (state.disconnecting || state.ownership != DisplayOwnership.APP) return null
        return when (val intent = state.intent) {
            DisplayIntent.Idle -> null
            DisplayIntent.BlankPrompt -> PageMode.PROMPT to PageContent("blank", "　", "　")
            // Listening page: the live transcript on top (its newest words), the latest answer below it while there is one.
            // The window cuts after about 120 characters, so the transcript shrinks while an answer page is shown.
            DisplayIntent.PromptStandby -> state.standbyAnswer.let { answer ->
                val heard = state.standby.source.trim()
                if (answer == null) PageMode.PROMPT to PageContent("standby",
                    LiveTranslator.clip(heard, LISTENING_CHARS).ifBlank { "OpenRayneo" }, state.standby.target.ifBlank { "실시간 힌트 준비됨" })
                else PageMode.PROMPT to PageContent("standby",
                    LiveTranslator.clip(heard, LISTENING_WITH_ANSWER_CHARS).ifBlank { answer.question.ifBlank { "OpenRayneo" }.let { LiveTranslator.clip(it, LISTENING_WITH_ANSWER_CHARS) } },
                    answer.pages.getOrElse(answer.page) { _ -> answer.text })
            }
            DisplayIntent.Translation -> if (state.glassesCaptions) PageMode.CAPTION to PageContent(if (state.captions) "captions" else CAPTION_TRANSLATED,
                state.translation.source, state.translation.target, state.translation.serial, state.translation.final)
            else if (state.captions) PageMode.PROMPT to PageContent("captions",
                state.translation.source.ifBlank { "실시간 자막" }, state.translation.target.ifBlank { "말소리를 기다리는 중" })
            else PageMode.PROMPT to PageContent("translation",
                state.translation.source.ifBlank { "실시간 번역 · 한국어 ↔ 영어" }, state.translation.target.ifBlank { "말하면 번역됩니다" })
            DisplayIntent.Navigation -> state.navigation.frame?.takeIf { state.navigation.enabled }?.let {
                PageMode.NAVIGATION to PageContent("navigation", it)
            }
            is DisplayIntent.Answer -> state.answer?.takeIf { it.ref == intent.ref }?.let {
                val page = it.pages.getOrElse(it.page) { _ -> it.text }
                if (it.ref.source == AnswerSource.FLASHCARD)
                    return PageMode.FLASHCARD to PageContent("anki:${it.ref.taskId}", it.question, page)
                // The question line shares the window: keep it short so the answer page fits.
                PageMode.PROMPT to PageContent("${it.ref.source}:${it.ref.taskId}", it.question.ifBlank { "Agent" }.trim().let { q -> if (q.length <= 30) q else q.take(29) + "…" },
                    page)
            }
        }
    }
}

private const val LISTENING_CHARS = 90
private const val LISTENING_WITH_ANSWER_CHARS = 30
