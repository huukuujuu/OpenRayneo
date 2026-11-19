// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** All native inference runs on the conversation worker, never on the decoder or UI thread. */
internal class SpeakerEngine(context: Context, private val people: () -> List<VoicePerson>,
    private val threshold: () -> Float, private val classified: (Long, Long) -> Unit) {
    companion object {
        const val MODEL = "campplus-zh-16k-common/sherpa-2.0.1"
        /** Cosine at which a turn joins an existing speaker label (was 0.6 on the best single sample: one talker split into A, B, C…). */
        private const val SAME_SPEAKER = 0.5f
        /** Shortest clean speech (0.5 s at 16 kHz) whose voiceprint may join a known label. */
        private const val SHORT_SAMPLES = 8_000
        /** Cosine for a self-matched turn to mark an existing label as the user's own. */
        private const val SELF_CLAIM = 0.7f
        private const val CLUSTER_SAMPLES = 12
    }
    data class Evidence(val beginMS: Long, val endMS: Long, val speaker: String,
                        val personId: String?, val score: Float?, val mixed: Boolean, val embedding: FloatArray?)
    data class Assignment(val speaker: String = UNKNOWN_SPEAKER, val personId: String? = null,
                          val score: Float? = null, val stable: Boolean = false, val mixed: Boolean = false,
                          val candidates: List<VoiceMatch> = emptyList(), val reason: String = "아직 충분히 선명한 음성을 얻지 못함")
    private data class Cluster(val label: String, val samples: MutableList<FloatArray>, var lastSeenMS: Long, var self: Boolean = false)
    private val extractor = SpeakerEmbeddingExtractor(context.assets,
        SpeakerEmbeddingExtractorConfig("voice-models/campplus-zh-16k.onnx", numThreads = 1))
    private val vad = Vad(context.assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig("voice-models/silero-vad.onnx", minSilenceDuration = 0.4f, maxSpeechDuration = 8f),
        sampleRate = 16000, numThreads = 1))
    private val diarizer = OfflineSpeakerDiarization(context.assets, OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig("voice-models/segmentation.int8.onnx"), numThreads = 1),
        embedding = SpeakerEmbeddingExtractorConfig("voice-models/campplus-zh-16k.onnx", numThreads = 1),
        clustering = FastClusteringConfig(threshold = 0.8f), minDurationOn = 0.25f, minDurationOff = 0.3f))
    private var nextLabel = 0
    private var labelPrefix = ""
    private val clusters = mutableListOf<Cluster>()
    val evidence = mutableListOf<Evidence>()
    private var offsetMS = 0L
    private var keepHistory = false
    private var enrolling = false
    private val vadWindow = FloatArray(512)
    private var vadWindowSize = 0
    private var acceptedSamples = 0L
    var analyzedThroughMS = 0L; private set

    fun reset(offsetMS: Long = 0, meeting: Boolean = false, enrollment: Boolean = false, labelPrefix: String = "") {
        vad.reset(); vadWindowSize = 0; acceptedSamples = 0; analyzedThroughMS = offsetMS; evidence.clear(); clusters.clear(); nextLabel = 0; this.offsetMS = offsetMS; this.labelPrefix = labelPrefix; keepHistory = meeting; enrolling = enrollment
    }
    fun beginRound(offsetMS: Long) { flush(); vad.reset(); vadWindowSize = 0; acceptedSamples = 0; this.offsetMS = offsetMS; analyzedThroughMS = offsetMS }
    fun accept(samples: FloatArray) {
        acceptedSamples += samples.size
        // Native VAD merges multiple windows in one call. A fixed model window keeps
        // packet batches and desktop file imports on the same segmentation baseline.
        var first = 0
        while (first < samples.size) {
            val count = minOf(512 - vadWindowSize, samples.size - first)
            samples.copyInto(vadWindow, vadWindowSize, first, first + count)
            vadWindowSize += count; first += count
            if (vadWindowSize == 512) { vad.acceptWaveform(vadWindow); drain(); vadWindowSize = 0 }
        }
        val safe = if (vad.isSpeechDetected()) evidence.maxOfOrNull { it.endMS } ?: offsetMS
            else (offsetMS + acceptedSamples * 1000 / 16000 - 1000).coerceAtLeast(offsetMS)
        analyzedThroughMS = maxOf(analyzedThroughMS, safe)
    }
        // A completed VAD chunk is fully classified before this watermark advances.
    fun flush() {
        if (vadWindowSize > 0) { vad.acceptWaveform(FloatArray(512).also { vadWindow.copyInto(it, endIndex = vadWindowSize) }); vadWindowSize = 0 }
        vad.flush(); drain(); analyzedThroughMS = offsetMS + acceptedSamples * 1000 / 16000
    }

    private fun drain() {
        while (!vad.empty()) {
            val speech = vad.front()
            val samples = speech.samples
            val begin = offsetMS + speech.start.toLong() * 1000 / 16000
            val end = begin + samples.size * 1000L / 16000
            vad.pop()
            if (enrolling) {
                if (samples.size < 12_800) {
                    evidence += Evidence(begin, end, UNKNOWN_SPEAKER, null, null, false, null)
                    classified(begin, end); continue
                }
                // Guided, single-speaker registration: use VAD utterances directly. Meeting
                // diarization on sub-second/short utterances can invent extra speakers or discard
                // most of an otherwise useful utterance; it must not gate enrollment.
                for (first in samples.indices step 48000) {
                    val last = minOf(first + 48000, samples.size)
                    if (last - first < 12800) continue
                    val vector = embedding(samples.copyOfRange(first, last)) ?: continue
                    evidence += Evidence(begin + first * 1000L / 16000, begin + last * 1000L / 16000, "등록", null, null, false, vector)
                }
                evidence.removeAll { it.endMS < end - 60_000 }
                if (evidence.size > 30) evidence.subList(0, evidence.size - 30).clear()
                classified(begin, end); continue
            }
            val intervals = diarizer.process(samples).mapNotNull { turn ->
                val first = (turn.start * 16000).toInt().coerceIn(0, samples.size)
                val last = (turn.end * 16000).toInt().coerceIn(first, samples.size)
                if (last > first) SpeakerInterval(first, last, turn.speaker) else null
            }
            val regions = speakerRegions(intervals)
            val vectors = mutableMapOf<Int, FloatArray>()
            val labels = mutableMapOf<Int, String>()
            val matches = mutableMapOf<Int, Pair<String?, Float>?>()
            intervals.map { it.speaker }.distinct().forEach { speaker ->
                val clean = regions.filter { it.speakers == setOf(speaker) }
                val count = clean.sumOf { it.last - it.first }
                val vector = if (count >= SHORT_SAMPLES) {
                    val audio = FloatArray(count)
                    var position = 0
                    clean.forEach { region ->
                        samples.copyInto(audio, position, region.first, region.last)
                        position += region.last - region.first
                    }
                    embedding(audio)
                } else null
                // Short turns give unreliable voiceprints: they may join a known voice but never start a new speaker.
                val short = count < 12_800
                val match = vector?.let(::person)
                if (vector != null && !short) vectors[speaker] = vector
                labels[speaker] = vector?.let { cluster(it, end, isSelf = !short && match?.first != null, create = !short)?.label }
                    ?: UNKNOWN_SPEAKER
                matches[speaker] = if (short) null else match
            }
            regions.forEach { region ->
                val mixed = region.speakers.size > 1
                val speaker = region.speakers.first()
                val match = if (mixed) null else matches[speaker]
                evidence += Evidence(offsetMS + (speech.start.toLong() + region.first) * 1000 / 16000,
                    offsetMS + (speech.start.toLong() + region.last) * 1000 / 16000,
                    if (mixed) UNKNOWN_SPEAKER else labels.getValue(speaker), match?.first, match?.second, mixed,
                    if (mixed) null else vectors[speaker])
            }
            if (!keepHistory) { evidence.removeAll { it.endMS < end - 120_000 }; clusters.removeAll { it.lastSeenMS < end - 120_000 } }
            classified(begin, end)
        }
    }
    fun embedding(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000); stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }
    // Named people (never auto-created anonymous ones) are scored so another enrolled voice can beat the user's own,
    // but only the user's own voice is ever accepted automatically; everyone else stays a per-record label (A, B, ...).
    private fun candidates(vector: FloatArray): List<VoiceMatch> = voiceIdentityReferences(people().filter { !it.unnamed }, MODEL)
        .map { (person, samples) -> VoiceMatch(person.id, samples.map { cosine(vector, it) }
            .sortedDescending().take(3).average().toFloat()) }.sortedByDescending { it.score }
    private fun accepted(matches: List<VoiceMatch>): VoiceMatch? = matches.firstOrNull()?.takeIf {
        it.score >= threshold() && (matches.size < 2 || it.score - matches[1].score >= 0.08f)
    }
    private fun person(vector: FloatArray): Pair<String?, Float>? {
        val matches = candidates(vector)
        val self = people().firstOrNull { it.self && it.mergedInto == null }?.id
        return matches.firstOrNull()?.let { accepted(matches)?.personId?.takeIf { id -> id == self } to it.score }
    }
    /**
     * Online speaker label within a record. One talker's turns vary a lot (short, noisy, Korean on a Chinese-trained
     * model), so a turn joins the closest label by its best match or the label's average voice. A turn matching the
     * enrolled self voice joins the self label. [create] false (short turns) only joins an existing label.
     */
    private fun cluster(vector: FloatArray, endMS: Long, isSelf: Boolean = false, create: Boolean = true): Cluster? {
        val selfCluster = if (isSelf) clusters.firstOrNull { it.self } else null
        val nearest = selfCluster?.let { it to 1f } ?: clusters.map { cluster ->
            cluster to maxOf(cluster.samples.maxOf { cosine(vector, it) }, cosine(vector, centroid(cluster.samples)))
        }.maxByOrNull { it.second }
        // The first self-matched turn only claims an existing label that clearly is the same voice; else it starts its own.
        val needed = if (isSelf && selfCluster == null) SELF_CLAIM else SAME_SPEAKER
        if (nearest != null && nearest.second >= needed) {
            val chosen = nearest.first
            chosen.lastSeenMS = endMS
            if (isSelf) chosen.self = true
            if (create) { chosen.samples += vector; if (chosen.samples.size > CLUSTER_SAMPLES) chosen.samples.removeAt(1) }
            return chosen
        }
        if (!create) return null
        val count = nextLabel++
        return Cluster(labelPrefix + if (count < 26) ('A'.code + count).toChar().toString() else "S${count + 1}", mutableListOf(vector), endMS, isSelf).also { clusters += it }
    }
    private fun centroid(samples: List<FloatArray>): FloatArray {
        val sum = FloatArray(samples[0].size)
        samples.forEach { sample ->
            var norm = 0.0; sample.forEach { norm += it * it }
            val scale = (1 / sqrt(norm).coerceAtLeast(1e-12)).toFloat()
            for (i in sum.indices) sum[i] += sample[i] * scale
        }
        return sum
    }
    fun assignment(beginMS: Long, endMS: Long, wordLevel: Boolean = false): Assignment {
        val covered = evidence.filter { min(it.endMS, endMS) > max(it.beginMS, beginMS) }
        if (covered.isEmpty()) return Assignment()
        fun duration(e: Evidence) = (min(e.endMS, endMS) - max(e.beginMS, beginMS)).coerceAtLeast(0)
        val mixed = covered.any { it.mixed } || (!wordLevel && covered.map { it.speaker }.filter { it != UNKNOWN_SPEAKER }.distinct().size > 1)
        val total = covered.sumOf(::duration).coerceAtLeast(1)
        val speaker = covered.groupBy { it.speaker }.maxBy { (_, list) -> list.sumOf(::duration) }
        // ASR timestamps include pauses. Identity coverage is measured over voiced evidence,
        // otherwise a clearly matched voice is discarded just because a sentence has silence.
        val stable = !mixed && speaker.key != UNKNOWN_SPEAKER && speaker.value.sumOf(::duration).toDouble() / total >= 0.85 && (wordLevel || total >= 700)
        val ranked = speaker.value.filter { !it.mixed && it.embedding != null }.flatMap { e ->
            candidates(e.embedding!!).map { it to duration(e) }
        }.groupBy { it.first.personId }.map { (id, votes) ->
            VoiceMatch(id, (votes.sumOf { it.first.score.toDouble() * it.second } / votes.sumOf { it.second }.coerceAtLeast(1)).toFloat())
        }.sortedByDescending { it.score }
        val known = speaker.value.filter { !it.mixed && it.embedding != null }
            .mapNotNull { e -> accepted(candidates(e.embedding!!))?.let { it.personId to duration(e) } }
            .groupBy { it.first }.maxByOrNull { (_, votes) -> votes.sumOf { it.second } }
        val self = people().firstOrNull { it.self && it.mergedInto == null }?.id
        val identity = known?.takeIf { stable && it.key == self && it.value.sumOf { vote -> vote.second }.toDouble() / total >= 0.85 }?.key
        val reason = when {
            mixed -> "여러 사람이 겹쳐 신원을 아직 확정하지 않음"
            !stable -> "음성이 너무 짧거나 한 문장에 여러 화자가 포함됨"
            ranked.isEmpty() -> "비교할 등록된 목소리가 아직 없음"
            ranked.first().score < threshold() -> "최고 유사도가 확정 임계값에 미달 ${"%.2f".format(threshold())}"
            ranked.size > 1 && ranked[0].score - ranked[1].score < 0.08f -> "두 후보 목소리가 비슷해 더 많은 발화를 기다림"
            known != null && known.key != self -> "본인이 아닌 등록 인물과 닮음, 자동으로 귀속하지 않음"
            identity == null -> "구간별 매칭이 일치하지 않아 더 많은 발화를 기다림"
            else -> "등록된 목소리와 일치함"
        }
        return Assignment(speaker.key, identity, ranked.firstOrNull()?.score, stable, mixed, ranked.take(2), reason)
    }
    fun enrollment(): VoiceEnrollment.Assessment = VoiceEnrollment.assess(evidence.filter { !it.mixed && it.embedding != null }
        .map { VoiceEnrollment.Sample(it.beginMS, it.endMS, it.embedding!!) })
    fun speakerSamples(): Map<String, List<FloatArray>> = evidence.filter { !it.mixed && it.embedding != null && it.speaker != UNKNOWN_SPEAKER }
        .groupBy { it.speaker }.mapValues { (_, turns) -> turns.sortedByDescending { it.endMS - it.beginMS }.take(5).map { it.embedding!! } }
    fun close() { vad.release(); diarizer.release(); extractor.release() }
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return (dot / sqrt(aa * bb).coerceAtLeast(1e-12)).toFloat()
    }
}
