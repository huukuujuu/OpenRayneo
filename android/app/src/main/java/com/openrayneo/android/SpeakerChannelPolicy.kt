// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/**
 * Source and actual channel count decide roles; a mono channel is never assigned self by position.
 * New records label speech by channel only (user decision 2026-11-23: voiceprint clustering made a new speaker for
 * almost every utterance). Realtime two-channel keeps channel 1 as the wearer; [FULL_SPEAKER] stays for old records.
 */
internal object SpeakerChannelPolicy {
    /** Older realtime records: channel 1 the wearer, channel 2 voiceprint clustering (kept when they are replayed). */
    const val DUAL_SELF = "dual_channel1_self"
    /** Realtime two-channel now: channel 1 the wearer, channel 2 labeled by channel. */
    const val DUAL_CHANNEL = "dual_channel1_self_channel2"
    const val CHANNEL_ONLY = "channel_only"
    const val FULL_SPEAKER = "full_speaker"
    fun policy(source: ConversationSource, channels: Int) = if (source == ConversationSource.REALTIME && channels == 2) DUAL_CHANNEL else CHANNEL_ONLY
    fun fixedSelf(policy: String, channel: Int) = (policy == DUAL_SELF || policy == DUAL_CHANNEL) && channel == 1
    /** The channel alone names the speaker: no voiceprint engine runs on it. */
    fun channelOnly(policy: String, channel: Int) = policy == DUAL_CHANNEL || policy == CHANNEL_ONLY || fixedSelf(policy, channel)
}
internal data class ConversationPCM(val channels: List<FloatArray>) {
    val frames get() = channels.firstOrNull()?.size ?: 0
    fun interleavedPCM16(): ByteArray {
        val bytes = java.nio.ByteBuffer.allocate(frames * channels.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) channels.forEach { bytes.putShort((it[frame] * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
        return bytes.array()
    }
    companion object {
        fun decode(bytes: ByteArray, channels: Int): ConversationPCM {
            val data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val result = List(channels) { FloatArray(bytes.size / 2 / channels) }
            for (frame in result[0].indices) result.forEach { it[frame] = data.short / 32768f }
            return ConversationPCM(result)
        }
    }
}
