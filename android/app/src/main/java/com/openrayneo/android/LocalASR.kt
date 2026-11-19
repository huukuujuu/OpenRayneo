// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One ASR round over 16 kHz mono PCM; timestamps are milliseconds from the first appended sample. */
internal interface SpeechRound {
    fun start()
    fun canAcceptAudio(): Boolean
    fun append(samples: FloatArray)
    fun finish()
    fun cancel()
}

/**
 * Chooses the ASR engine from config key "asrEngine": cloud (Bailian, default), "local" (SenseVoice,
 * sentence by sentence), "stream" (Korean streaming zipformer) or "nemotron" (multilingual streaming).
 */
internal object SpeechRounds {
    fun model(config: JSONObject): LocalModel<*>? = when (config.optString("asrEngine")) {
        "local" -> LocalModels.senseVoice
        "stream" -> LocalModels.zipformer
        "nemotron" -> LocalModels.nemotron
        else -> null
    }
    /** Translation hears Korean and English, so the Korean-only streaming engine falls back to SenseVoice when present, else cloud. */
    fun bilingual(context: Context, config: JSONObject): JSONObject = if (config.optString("asrEngine") != "stream") config
        else JSONObject(config.toString()).put("asrEngine", if (LocalModels.senseVoice.installed(context)) "local" else "cloud")
    /** One language hint fixes the language (locale codes as in the model card); otherwise the model detects it. */
    private fun nemotronLanguage(config: JSONObject): String {
        val hints = config.optString("asrLanguage").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return when (hints.singleOrNull()) { "ko" -> "ko-KR"; "en" -> "en-US"; else -> "auto" }
    }
    /** Whether the configured engine can run now (key entered, or model downloaded). */
    fun ready(context: Context, config: JSONObject) = model(config)?.installed(context) ?: config.optString("asrKey").isNotBlank()
    fun missing(config: JSONObject) = model(config)?.let { "먼저 음성 설정에서 기기 ASR 모델(${it.title})을 내려받으세요" } ?: "먼저 음성 설정에서 Bailian ASR API Key를 입력하세요"
    fun create(context: Context, config: JSONObject,
               sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
               failed: (String) -> Unit, completed: () -> Unit, interim: ((String) -> Unit)? = null, use: AsrUse = AsrUse.LIVE): SpeechRound =
        when (config.optString("asrEngine")) {
            "local" -> LocalASR(context, config, sentence, failed, completed, interim)
            "stream" -> StreamASR(context, LocalModels.zipformer, null, sentence, failed, completed, interim)
            "nemotron" -> StreamASR(context, LocalModels.nemotron, nemotronLanguage(config), sentence, failed, completed, interim)
            else -> ConversationASR(config, use, sentence, failed, completed, interim)
        }
}

/**
 * A model downloaded once from Hugging Face (into the shared folder when all-files access is granted, else app
 * storage) or copied there by the user; loaded while rounds use it and freed after a
 * minute idle. All loading, decoding and releasing happens on [LocalModels.executor].
 */
internal class LocalModel<T>(val title: String, val size: String, private val folderName: String,
    /** Folder name of the official sherpa-onnx archive, looked up under [LocalModels.SHARED_ROOT] so copied files survive reinstalls. */
    val sharedName: String, private val base: String,
    private val files: List<Pair<String, Float>>, private val load: (File) -> T, private val free: (T) -> Unit) {
    var downloading by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var message by mutableStateOf<String?>(null); private set
    private var loaded: T? = null
    private var users = 0
    private var idle: ScheduledFuture<*>? = null

    private fun folder(context: Context) = File(context.filesDir, folderName)
    private fun complete(dir: File) = files.all { (name, _) -> File(dir, name).length() > 0 }
    val sharedFolder get() = File(Environment.getExternalStorageDirectory(), "${LocalModels.SHARED_ROOT}/$sharedName")
    /** The copied folder (needs all-files access) wins over the in-app download. */
    fun location(context: Context): File? =
        sharedFolder.takeIf { Environment.isExternalStorageManager() && complete(it) } ?: folder(context).takeIf(::complete)
    fun installed(context: Context) = location(context) != null
    fun shared(context: Context) = location(context) == sharedFolder
    val fileNames get() = files.map { it.first }

    /** In-app copy that can move to the shared folder (all-files access granted, shared copy not complete yet). */
    fun movable(context: Context) = Environment.isExternalStorageManager() && !complete(sharedFolder) && complete(folder(context))

    /** Downloads into the shared folder when all-files access is granted (a backup that survives reinstalls), else app storage. */
    fun download(context: Context) {
        if (downloading) return
        downloading = true; progress = 0f; message = "내려받는 중…"
        Thread {
            try {
                val dir = (if (Environment.isExternalStorageManager()) sharedFolder else folder(context)).apply { mkdirs() }
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                var before = 0f
                files.forEach { (name, weight) ->
                    val target = File(dir, name)
                    if (target.length() == 0L) {
                        val part = File(dir, "$name.part")
                        http.newCall(Request.Builder().url(base + name).build()).execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            val body = checkNotNull(response.body)
                            val total = body.contentLength()
                            var done = 0L; var shown = 0L
                            body.byteStream().use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    output.write(buffer, 0, count); done += count
                                    if (total > 0 && done - shown > 1_000_000) { shown = done; progress = before + weight * done / total }
                                }
                            } }
                            check(total <= 0 || done == total) { "파일이 덜 받아졌습니다: $name" }
                        }
                        check(part.renameTo(target)) { "파일 저장 실패: $name" }
                    }
                    before += weight; progress = before
                }
                progress = 1f; message = "$title 모델 준비됨 (${dir.absolutePath})"
            } catch (error: Exception) {
                message = "모델 내려받기 실패: ${error.message ?: error.javaClass.simpleName}"
            } finally { downloading = false }
        }.apply { isDaemon = true }.start()
    }

    /** Copies an in-app download to the shared folder, checks every size, then frees the app copy. */
    fun moveToShared(context: Context) {
        if (downloading) return
        downloading = true; progress = 0f; message = "공용 폴더로 옮기는 중…"
        Thread {
            try {
                val source = folder(context); val target = sharedFolder.apply { mkdirs() }
                val total = files.sumOf { (name, _) -> File(source, name).length() }.coerceAtLeast(1)
                var done = 0L
                files.forEach { (name, _) ->
                    val from = File(source, name); val to = File(target, name)
                    if (to.length() != from.length()) {
                        val part = File(target, "$name.part")
                        from.inputStream().use { input -> part.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count); done += count; progress = done.toFloat() / total
                            }
                        } }
                        check(part.length() == from.length()) { "복사 크기가 다릅니다: $name" }
                        check(part.renameTo(to)) { "파일 저장 실패: $name" }
                    } else done += from.length()
                }
                check(complete(target))
                files.forEach { (name, _) -> File(source, name).delete() }
                source.delete()
                progress = 1f; message = "$title 모델을 ${target.absolutePath}로 옮겼습니다"
            } catch (error: Exception) {
                message = "옮기기 실패: ${error.message ?: error.javaClass.simpleName}"
            } finally { downloading = false }
        }.apply { isDaemon = true }.start()
    }

    /** Executor thread only. Resident: load now and stay; otherwise free at once when unused. */
    fun settle(context: Context, resident: Boolean) {
        idle?.cancel(false); idle = null
        if (resident) {
            if (loaded == null) location(context)?.let { dir ->
                try { loaded = load(dir); message = "$title 모델을 메모리에 올려 둠" }
                catch (error: Exception) { message = "$title 모델 불러오기 실패: ${error.message ?: error.javaClass.simpleName}" }
            }
        } else if (users == 0) { loaded?.let(free); loaded = null }
    }

    /** Executor thread only. A round calls this once when it starts using the model. */
    fun acquire(context: Context): T {
        idle?.cancel(false); idle = null
        val model = loaded ?: run {
            val dir = checkNotNull(location(context)) { "기기 ASR 모델($title)이 없습니다. 음성 설정에서 내려받거나 폴더에 복사하세요" }
            load(dir).also { loaded = it }
        }
        users++
        return model
    }
    /** Executor thread only. The last user leaving schedules the release. */
    fun releaseUser() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && loaded != null && LocalModels.resident !== this) {
            idle?.cancel(false)
            idle = LocalModels.executor.schedule(Runnable { if (users == 0) { loaded?.let(free); loaded = null }; idle = null }, 60, TimeUnit.SECONDS)
        }
    }
}

internal object LocalModels {
    /** The model kept in memory between rounds (the configured on-device engine), or null. */
    @Volatile var resident: LocalModel<*>? = null; private set
    /**
     * Keeps the configured engine's model loaded so a wake never waits for a multi-second load, and frees the
     * others (setting "asrKeepLoaded", default on). Call on service start and after settings are saved.
     */
    fun keepResident(context: Context, config: JSONObject) {
        val target = if (config.optString("asrKeepLoaded", "true") != "false") SpeechRounds.model(config) else null
        executor.execute {
            resident = target
            listOf(senseVoice, zipformer, nemotron).forEach { it.settle(context.applicationContext, it === target) }
        }
    }
    /** Copy official model folders here (e.g. from a PC over USB): /sdcard/OpenRayneo/models/<archive folder>/ */
    const val SHARED_ROOT = "OpenRayneo/models"
    /** All recognizer work (load, decode, release) for every on-device round is serialized here. */
    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "local-asr").apply { isDaemon = true } }

    /** SenseVoice int8 (zh/en/ja/ko/yue), automatic language detection; rounds filter by their language hints. */
    val senseVoice = LocalModel("SenseVoice", "약 240MB", "asr/sense-voice-2024-07-17",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/",
        // Model first, so progress tracks the large file.
        listOf("model.int8.onnx" to 0.99f, "tokens.txt" to 0.01f), { dir ->
            OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(File(dir, "model.int8.onnx").absolutePath, "", useInverseTextNormalization = true),
                    tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2)))
        }, { it.release() })

    /** Korean-only streaming zipformer transducer (KsponSpeech recipe); int8 encoder/joiner, fp32 decoder as in upstream examples. */
    val zipformer = LocalModel("한국어 스트리밍", "약 130MB", "asr/zipformer-ko-2024-06-16",
        "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
        "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main/",
        listOf("encoder-epoch-99-avg-1.int8.onnx" to 0.9f, "decoder-epoch-99-avg-1.onnx" to 0.06f,
            "joiner-epoch-99-avg-1.int8.onnx" to 0.03f, "tokens.txt" to 0.01f), { dir ->
            OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath),
                    tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2, modelType = "zipformer"),
                // Close a sentence after 0.8 s of silence once something was said (default 1.4 s), and cap it at 15 s.
                endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 0.8f, 0.0f), rule3 = EndpointRule(false, 0.0f, 15.0f)),
                enableEndpoint = true))
        }, { it.release() })

    /**
     * NVIDIA Nemotron 3.5 ASR Streaming 0.6B, multilingual (ko-KR, en-US, ...), 560 ms chunks, int8.
     * k2-fsa publishes it only as a tar.bz2, so the in-app download uses a Hugging Face mirror of the same
     * four files (sizes match the official listing); copying the official archive's folder works too.
     */
    val nemotron = LocalModel("Nemotron 다국어 스트리밍", "약 680MB", "asr/nemotron-3.5-streaming-560ms-int8",
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        "https://huggingface.co/apbaxel/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-int8/resolve/main/",
        listOf("encoder.int8.onnx" to 0.96f, "decoder.int8.onnx" to 0.02f, "joiner.int8.onnx" to 0.015f, "tokens.txt" to 0.005f), { dir ->
            OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath),
                    tokens = File(dir, "tokens.txt").absolutePath, numThreads = 4),
                endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 0.8f, 0.0f), rule3 = EndpointRule(false, 0.0f, 15.0f)),
                enableEndpoint = true))
        }, { it.release() })

    /** Streaming GTCRN speech enhancement (bundled asset); one instance per round because it keeps state. */
    fun denoiser(context: Context) = OnlineSpeechDenoiser(context.assets, OnlineSpeechDenoiserConfig(OfflineSpeechDenoiserModelConfig(
        gtcrn = OfflineSpeechDenoiserGtcrnModelConfig("voice-models/gtcrn-simple.onnx"), numThreads = 1)))
}

/**
 * On-device round: streaming GTCRN removes background noise, silero VAD cuts sentences on the cleaned
 * audio, and SenseVoice decodes each one (non-streaming).
 * With [interim] set, the unfinished utterance is re-decoded about once a second for live captions.
 * Callbacks run on [LocalModels.executor]; callers post onto their own threads as with the cloud round.
 */
internal class LocalASR(private val context: Context, config: JSONObject,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit,
    private val interim: ((String) -> Unit)? = null) : SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val allowed = config.optString("asrLanguage").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val executor = LocalModels.executor
    private val queued = AtomicInteger()
    @Volatile private var ended = false
    @Volatile private var finishing = false
    // Executor-thread state.
    private var vad: Vad? = null
    private var denoiser: OnlineSpeechDenoiser? = null
    private var recognizer: OfflineRecognizer? = null
    private val window = FloatArray(WINDOW)
    private var windowSize = 0
    private var index = 0
    private val recent = ArrayDeque<FloatArray>()
    private var live = FloatArray(MAX_LIVE)
    private var liveSize = 0
    private var liveDecodedAt = 0

    override fun start() { executor.execute { if (!ended) attempt { prepare() } } }
    override fun canAcceptAudio() = !ended && !finishing && queued.get() < MAX_QUEUED
    override fun append(samples: FloatArray) {
        if (ended || finishing) return
        // Same contract as the cloud round: a backlog fails the round so the caller pauses and backfills later.
        check(queued.get() + samples.size <= MAX_BACKLOG) { "기기 ASR 대기 오디오가 15초를 넘어 이번 라운드를 중지했습니다" }
        queued.addAndGet(samples.size)
        executor.execute {
            queued.addAndGet(-samples.size)
            if (!ended) attempt { feed(samples) }
        }
    }
    override fun finish() {
        if (ended || finishing) return
        finishing = true
        executor.execute {
            if (ended) return@execute
            attempt {
                val detector = prepare()
                windows(detector, checkNotNull(denoiser).flush().samples)
                if (windowSize > 0) { detector.acceptWaveform(window.copyOf(windowSize)); windowSize = 0 }
                detector.flush(); drain(detector)
                ended = true; release(); completed()
            }
        }
    }
    override fun cancel() { if (!ended) { ended = true; executor.execute { release() } } }

    private fun attempt(work: () -> Unit) {
        try { work() } catch (error: Exception) {
            if (!ended) { ended = true; release(); failed("기기 ASR 실패: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    private fun prepare(): Vad {
        vad?.let { return it }
        if (recognizer == null) recognizer = LocalModels.senseVoice.acquire(context)
        if (denoiser == null) denoiser = LocalModels.denoiser(context)
        // A slightly longer silence keeps short pauses inside one sentence instead of decoding fragments.
        return Vad(context.assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig("voice-models/silero-vad.onnx", minSilenceDuration = 0.5f, maxSpeechDuration = 12f),
            sampleRate = 16000, numThreads = 1)).also { vad = it }
    }
    private fun release() {
        vad?.release(); vad = null
        denoiser?.release(); denoiser = null
        if (recognizer != null) { recognizer = null; LocalModels.senseVoice.releaseUser() }
    }

    private fun feed(samples: FloatArray) {
        val detector = prepare()
        windows(detector, checkNotNull(denoiser).run(samples, 16000).samples)
    }

    private fun windows(detector: Vad, samples: FloatArray) {
        var first = 0
        while (first < samples.size) {
            val count = minOf(WINDOW - windowSize, samples.size - first)
            samples.copyInto(window, windowSize, first, first + count)
            windowSize += count; first += count
            if (windowSize == WINDOW) {
                val chunk = window.copyOf()
                detector.acceptWaveform(chunk); windowSize = 0
                if (interim != null) track(detector, chunk)
                drain(detector)
            }
        }
    }

    /** Keeps the audio of the utterance in progress (with a short lead-in) and decodes it periodically. */
    private fun track(detector: Vad, chunk: FloatArray) {
        if (detector.isSpeechDetected()) {
            if (liveSize == 0) { recent.forEach(::keep); liveDecodedAt = 0 }
            keep(chunk)
            // Captions are best effort: skip while audio is waiting, so finals never fall behind.
            if (liveSize - liveDecodedAt >= INTERIM_STEP && queued.get() < INTERIM_STEP) {
                liveDecodedAt = liveSize
                decode(live.copyOfRange(maxOf(0, liveSize - MAX_INTERIM), liveSize))?.let { (text, _) -> interim?.invoke(text) }
            }
        } else liveSize = 0
        recent.addLast(chunk); while (recent.size > LEAD_WINDOWS) recent.removeFirst()
    }

    /** Appends to the in-progress buffer; past [MAX_LIVE] the oldest half is dropped. */
    private fun keep(chunk: FloatArray) {
        if (liveSize + chunk.size > MAX_LIVE) {
            val half = MAX_LIVE / 2
            live.copyInto(live, 0, liveSize - half, liveSize); liveSize = half; liveDecodedAt = 0
        }
        chunk.copyInto(live, liveSize); liveSize += chunk.size
    }

    private fun drain(detector: Vad) {
        while (!detector.empty()) {
            val segment = detector.front(); detector.pop()
            liveSize = 0; liveDecodedAt = 0
            val (text, _) = decode(segment.samples) ?: continue
            val begin = segment.start.toLong() * 1000 / 16000
            sentence(text, begin, begin + segment.samples.size * 1000L / 16000, "$task:${index++}", emptyList())
        }
    }

    /** Returns null for empty results or a detected language outside the configured hints (noise is often read as zh). */
    private fun decode(samples: FloatArray): Pair<String, String>? {
        val recognizer = checkNotNull(recognizer)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            val lang = result.lang.removePrefix("<|").removeSuffix("|>")
            if (text.isEmpty()) return null
            // The language tag can be wrong on noisy audio; Hangul text is still Korean speech.
            val hangul = "ko" in allowed && text.any { it in '가'..'힣' }
            if (allowed.isNotEmpty() && lang.isNotEmpty() && lang !in allowed && !hangul) return null
            return text to lang
        } finally { stream.release() }
    }

    private companion object {
        const val WINDOW = 512
        const val LEAD_WINDOWS = 8
        const val INTERIM_STEP = 16000
        const val MAX_LIVE = 16000 * 12
        const val MAX_QUEUED = 16000 * 8
        const val MAX_BACKLOG = 16000 * 15
        const val MAX_INTERIM = 16000 * 6
    }
}

/**
 * On-device streaming round: GTCRN denoising feeds the Korean zipformer continuously. Partial text is
 * reported on every change (it can still be corrected while the sentence goes on), and the endpoint rule
 * closes the sentence. Callbacks run on [LocalModels.executor].
 */
internal class StreamASR(private val context: Context, private val source: LocalModel<OnlineRecognizer>,
    /** Per-stream "language" option (Nemotron); null for single-language models. */
    private val language: String?,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit,
    private val interim: ((String) -> Unit)? = null) : SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val executor = LocalModels.executor
    private val queued = AtomicInteger()
    @Volatile private var ended = false
    @Volatile private var finishing = false
    // Executor-thread state.
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var denoiser: OnlineSpeechDenoiser? = null
    private var fed = 0L
    private var sentenceStart = 0L
    private var partial = ""
    private var index = 0

    override fun start() { executor.execute { if (!ended) attempt { prepare() } } }
    override fun canAcceptAudio() = !ended && !finishing && queued.get() < MAX_QUEUED
    override fun append(samples: FloatArray) {
        if (ended || finishing) return
        check(queued.get() + samples.size <= MAX_BACKLOG) { "기기 ASR 대기 오디오가 15초를 넘어 이번 라운드를 중지했습니다" }
        queued.addAndGet(samples.size)
        executor.execute {
            queued.addAndGet(-samples.size)
            if (!ended) attempt { prepare(); accept(checkNotNull(denoiser).run(samples, 16000).samples) }
        }
    }
    override fun finish() {
        if (ended || finishing) return
        finishing = true
        executor.execute {
            if (ended) return@execute
            attempt {
                prepare()
                accept(checkNotNull(denoiser).flush().samples)
                // Trailing silence lets the last frames through the encoder before input ends.
                accept(FloatArray(TAIL))
                val current = checkNotNull(stream); val model = checkNotNull(recognizer)
                current.inputFinished()
                while (model.isReady(current)) model.decode(current)
                close(model.getResult(current).text.trim())
                ended = true; release(); completed()
            }
        }
    }
    override fun cancel() { if (!ended) { ended = true; executor.execute { release() } } }

    private fun attempt(work: () -> Unit) {
        try { work() } catch (error: Exception) {
            if (!ended) { ended = true; release(); failed("기기 ASR 실패: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    private fun prepare() {
        if (stream != null) return
        val model = recognizer ?: source.acquire(context).also { recognizer = it }
        if (denoiser == null) denoiser = LocalModels.denoiser(context)
        stream = model.createStream().also(::configure)
    }
    private fun release() {
        stream?.release(); stream = null
        denoiser?.release(); denoiser = null
        if (recognizer != null) { recognizer = null; source.releaseUser() }
    }

    private fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        val current = checkNotNull(stream); val model = checkNotNull(recognizer)
        current.acceptWaveform(samples, 16000); fed += samples.size
        while (model.isReady(current)) model.decode(current)
        val text = model.getResult(current).text.trim()
        if (text.isNotEmpty() && text != partial) { partial = text; interim?.invoke(text) }
        if (model.isEndpoint(current)) { close(text); model.reset(current); configure(current) }
    }
    /** The language must be set before audio; set it again after each reset in case reset clears options. */
    private fun configure(stream: OnlineStream) { language?.let { stream.setOption("language", it) } }
    private fun close(text: String) {
        if (text.isNotEmpty()) sentence(text, sentenceStart * 1000 / 16000, fed * 1000 / 16000, "$task:${index++}", emptyList())
        sentenceStart = fed; partial = ""
    }

    private companion object {
        const val MAX_QUEUED = 16000 * 8
        const val MAX_BACKLOG = 16000 * 15
        const val TAIL = 16000 * 6 / 10
    }
}
