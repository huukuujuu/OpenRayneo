// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal enum class AnswerSource { MOBILE_AGENT, COMPUTER_CODEX }
internal data class AnswerRef(val source: AnswerSource, val taskId: Long)
internal data class AnswerContent(val ref: AnswerRef, val question: String, val text: String)
internal enum class ReturnTarget { IDLE, PROMPT_STANDBY, NAVIGATION }
internal sealed interface DisplayIntent {
    data object Idle : DisplayIntent
    data object PromptStandby : DisplayIntent
    data object BlankPrompt : DisplayIntent
    data object Navigation : DisplayIntent
    data object Translation : DisplayIntent
    data class Answer(val ref: AnswerRef, val returnTo: ReturnTarget) : DisplayIntent
}
internal enum class DisplayOwnership { APP, YIELDED }
internal enum class LinkPhase { DISCONNECTED, SCANNING, CONNECTING, BLE_ONLY, FULL, RETRY_WAIT, CLOSING }
internal data class LinkState(val phase: LinkPhase = LinkPhase.DISCONNECTED, val detail: String = "안경 연결 대기 중")
/** NAVIGATION and CAPTION use the caption business 0x13 (text mode 3), PROMPT the prompt business 0x17 (text mode 4). */
internal enum class PageMode { NAVIGATION, PROMPT, CAPTION }
internal val PageMode.protocol: Int get() = if (this == PageMode.PROMPT) 0x17 else 0x13
internal val PageMode.channels: Int get() = if (this == PageMode.PROMPT) 2 else 1
/** A session the glasses opened themselves (their menu sent type 1 with their own sid); the phone answers it with type 2. */
internal data class GlassesOffer(val mode: PageMode, val sid: String, val settings: String?) { val protocol get() = mode.protocol }
internal enum class PagePhase { STARTING, LAYOUT, SETTLING, READY, CLOSING }
internal data class PageContent(val key: String, val text: String, val answer: String? = null)
internal data class PageSession(
    val id: Long, val mode: PageMode, val phase: PagePhase, val content: PageContent,
    val writtenContent: PageContent? = null,
    val offer: GlassesOffer? = null,
)
internal data class TranslationView(val source: String = "", val target: String = "")
internal data class NavigationState(val enabled: Boolean = false, val key: String? = null, val frame: String? = null)
internal enum class AudioPhase { STOPPED, WAITING_PACKET, WAITING_PCM, RECEIVING, STALLED, FAILED }
internal data class AudioState(val phase: AudioPhase = AudioPhase.STOPPED, val detail: String = "안경 음성 수신이 중지됨")
internal data class AnswerPermit(val ref: AnswerRef, val revision: Long)
internal data class GlassesState(
    val intent: DisplayIntent = DisplayIntent.Idle,
    val ownership: DisplayOwnership = DisplayOwnership.APP,
    val link: LinkState = LinkState(),
    val navigation: NavigationState = NavigationState(),
    val session: PageSession? = null,
    val audio: AudioState = AudioState(),
    val answer: AnswerContent? = null,
    val translation: TranslationView = TranslationView(),
    /** The translation window currently shows live captions instead of a translation. */
    val captions: Boolean = false,
    /** The captions run in the glasses' own caption screen (opened from their menu) instead of the app's prompt window. */
    val glassesCaptions: Boolean = false,
    /** Start request from the glasses menu waiting for the matching page to open. */
    val offer: GlassesOffer? = null,
    /** Live caption on the realtime-hint standby page: last heard sentence and a status line. */
    val standby: TranslationView = TranslationView(),
    val revision: Long = 0,
    val nextSession: Long = 1,
    val answerPermit: AnswerPermit? = null,
    val restoreRevision: Long? = null,
    val blockedRevision: Long? = null,
    val error: String? = null,
    val disconnecting: Boolean = false,
) {
    val displayDescription: String get() = when {
        disconnecting -> "안경 세션 닫는 중"
        ownership == DisplayOwnership.YIELDED -> "안경 표시를 양보했습니다. 재개할 기능을 선택하세요"
        error != null -> error
        session?.phase == PagePhase.CLOSING -> "이전 페이지 닫는 중"
        link.phase == LinkPhase.BLE_ONLY -> "BLE 진단 연결 전용, 표시나 음성 수신은 시작하지 않음"
        link.phase != LinkPhase.FULL -> link.detail
        session == null -> "연결됨, 안경 표시 유휴"
        session.phase == PagePhase.STARTING -> "안경의 시작 확인 대기"
        session.phase == PagePhase.LAYOUT -> "내비게이션 레이아웃 확인 중"
        session.phase == PagePhase.SETTLING -> "페이지 확인됨, 내용 전송 중"
        session.writtenContent != session.content -> "안경 내용 업데이트 중"
        session.mode == PageMode.NAVIGATION -> "내비게이션 세션 준비됨"
        session.mode == PageMode.CAPTION -> "안경 실시간 자막 세션 준비됨"
        intent == DisplayIntent.BlankPrompt -> "화면을 지웠고 음성 수신은 유지됨"
        intent is DisplayIntent.Answer -> "답변 세션 준비됨"
        intent == DisplayIntent.Translation -> if (captions) "실시간 자막 세션 준비됨" else "실시간 번역 세션 준비됨"
        else -> "실시간 힌트 세션 준비됨"
    }
}
internal sealed interface PageSignal {
    data class AudioAccepted(val channels: Int) : PageSignal
    data object LayoutPending : PageSignal
    data object Settling : PageSignal
    data class Written(val content: PageContent) : PageSignal
    data class Failed(val message: String) : PageSignal
    data class Ended(val message: String) : PageSignal
    data class Closed(val error: String? = null) : PageSignal
}
internal sealed interface GlassesEvent {
    data object BeginConnection : GlassesEvent
    data class LinkChanged(val state: LinkState) : GlassesEvent
    data class NavigationChanged(val state: NavigationState) : GlassesEvent
    data class SelectNavigation(val state: NavigationState) : GlassesEvent
    data object StopNavigation : GlassesEvent
    data object PauseDisplay : GlassesEvent
    data object OpenStandby : GlassesEvent
    /** [captions] = live captions (speech only, no translation) on the same window. */
    data class StartTranslation(val captions: Boolean = false, val glassesPage: Boolean = false) : GlassesEvent
    data class GlassesOffered(val offer: GlassesOffer) : GlassesEvent
    /** Forget the pending glasses start request ([sid] null = any); [decline] = tell the glasses we will not serve it. */
    data class ClearOffer(val sid: String?, val decline: Boolean) : GlassesEvent
    data class TranslationText(val view: TranslationView) : GlassesEvent
    data class StandbyText(val view: TranslationView) : GlassesEvent
    data object ClearDisplay : GlassesEvent
    data class ForgetAnswer(val source: AnswerSource) : GlassesEvent
    data object RetryAudio : GlassesEvent
    data object DismissAnswer : GlassesEvent
    data object Disconnect : GlassesEvent
    data class AnswerRequested(val ref: AnswerRef) : GlassesEvent
    data class CancelAnswer(val source: AnswerSource) : GlassesEvent
    data class AnswerCompleted(val content: AnswerContent, val permit: AnswerPermit) : GlassesEvent
    data class RevealAnswer(val content: AnswerContent) : GlassesEvent
    data class SessionSignal(val id: Long, val signal: PageSignal) : GlassesEvent
    data class AudioChanged(val id: Long, val state: AudioState) : GlassesEvent
    data class RestoreAnswer(val id: Long, val revision: Long, val ref: AnswerRef) : GlassesEvent
    data class CloseTimedOut(val id: Long) : GlassesEvent
    data class ExternalPage(val description: String) : GlassesEvent
}
internal sealed interface GlassesEffect {
    data class StartPage(val session: PageSession) : GlassesEffect
    data class UpdatePage(val id: Long, val content: PageContent) : GlassesEffect
    data class ClosePage(val id: Long) : GlassesEffect
    data object ResetPage : GlassesEffect
    data class StartAudio(val id: Long, val channels: Int) : GlassesEffect
    data object StopAudio : GlassesEffect
    data class FollowNavigation(val key: String) : GlassesEffect
    data object StopFollowing : GlassesEffect
    data class ScheduleRestore(val id: Long, val revision: Long, val ref: AnswerRef) : GlassesEffect
    data object CancelRestore : GlassesEffect
    data class ScheduleCloseTimeout(val id: Long) : GlassesEffect
    data object CancelCloseTimeout : GlassesEffect
    data object DisconnectTransport : GlassesEffect
    data class DeclineOffer(val offer: GlassesOffer) : GlassesEffect
}
internal data class GlassesTransition(val state: GlassesState, val effects: List<GlassesEffect>)
