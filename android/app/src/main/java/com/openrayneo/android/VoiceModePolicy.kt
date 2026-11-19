// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Facts for the external phone Agent, sent as the request context JSON. The DSH plugin owns the prompts
 * (integrations/dsh-go/lib/voice-modes.json), decides whether an answer is needed and renders the speech once.
 * Kept compact on purpose: every field here is model input on each voice turn, so only what the
 * Agent needs to answer is sent (text, likely speaker, age when it matters, the previous exchange).
 */
internal class VoiceModePolicy {
    fun input(source: ConversationSource, segments: List<SpeechSegment>, people: List<VoicePerson>,
              previous: List<String>, previousReply: String): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().put("mode", source.name.lowercase())
            .put("now", ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("segments", JSONArray(segments.map { segment ->
                val person = people.firstOrNull { it.id == segment.personId }
                val speaker = when {
                    segment.identityBasis == "channel1_default_self" -> "본인(채널1 기본값)"
                    person != null -> person.name + if (!segment.stable) "(추정)" else ""
                    segment.speaker.isNotBlank() && segment.speaker != UNKNOWN_SPEAKER -> segment.speaker
                    else -> null
                }
                val age = (now - (segment.timelineAt.takeIf { it > 0 } ?: segment.receivedAt)).coerceAtLeast(0) / 1000
                JSONObject().put("text", segment.text).apply {
                    speaker?.let { put("speaker", it) }
                    if (person?.self == true && segment.stable) put("is_self", true)
                    if (segment.mixed) put("mixed", true)
                    if (age >= 10) put("age_s", age)
                }
            })).apply {
                if (previous.isNotEmpty()) put("previous_request", JSONArray(previous))
                if (previousReply.isNotBlank()) put("previous_reply", previousReply)
            }
    }
}
