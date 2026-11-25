package com.jarvis.phone

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 폰 안의 Gemma 로 요약. 무엇을 어떻게 묻는지는 BriefingComposer(대화방마다 세 줄, 앱 알림은 AI 없이).
 */
class Summarizer private constructor(private val ctx: Context) {

    val modelFile: File get() = File(ctx.filesDir, MODEL_NAME)
    /**
     * 모델을 GPU 에 맞게 바꿔 둔 것(캐시)을 두는 앱 전용 칸 (2026-11-22 선생님: "느려지지 않게 별도 공간에 캐싱").
     * 예전엔 cacheDir 에 두어 저장공간이 모자라거나 '캐시 정리'를 하면 지워졌고, 그러면 다음 요약에서 처음부터 다시 바꾸느라
     * 오래 걸리고 폰이 뜨거워졌다. filesDir 는 안드로이드가 지우지 않는다. 모델을 바꾸면 비운다(clearEngineCache).
     */
    val engineCacheDir: File get() = File(ctx.filesDir, "llm_cache").apply { mkdirs() }
    fun clearEngineCache() { runCatching { File(ctx.filesDir, "llm_cache").deleteRecursively() } }
    fun hasModel() = modelFile.exists() && modelFile.length() > 100_000_000

    private var engine: Engine? = null

    /**
     * ⏱ 이번 요약에서 어디에 시간이 들었나 (2026-11-22 선생님: "2건인데 오래, 70건인데 금방").
     * 모델 불러오기·GPU/CPU·AI 부른 횟수와 시간. 요약 끝에 한 줄로 남겨 실제 폰에서 원인을 본다.
     */
    class Stats { @Volatile var loadMs = 0L; @Volatile var backend = ""; @Volatile var calls = 0; @Volatile var callMs = 0L
        /** 모델이 올라 있을 때 잰 쌤톡스 메모리(MB)의 최댓값 */
        @Volatile var memMb = 0 }
    @Volatile var stats = Stats()
        private set
    fun resetStats() { stats = Stats() }
    private val lock = Mutex()
    /** 화면의 수동 요약과 예약 브리핑이 동시에 모델을 쓰지 않도록 요약 전체를 한 줄로 세움. */
    private val runLock = Mutex()

    /** 모델 로드는 수 초 걸리므로 한 번만 하고 재사용. GPU 실패 시 CPU로. */
    private suspend fun engine(onStatus: (String) -> Unit): Engine {
      return lock.withLock {
        engine?.let { return@withLock it }
        // Gemma 4 MTP(다중 토큰 예측): E4B에서 생성 속도를 크게 올려줌, 품질 손실 없음
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = true
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            // 🌡️ 폰에 무리가 덜 가게(2026-11-22): AI 작업 공간(한 번에 다루는 글 길이)을 실제로 쓰는 만큼만(MAX_CONTEXT),
            // CPU 로 돌 땐 코어를 다 쓰지 않게(PhoneLoad.cpuThreads). 작업 공간 상한을 못 받는 판이면 상한 없이 다시.
            fun load(backend: Backend, name: String, cap: Boolean) =
                Engine(EngineConfig(modelPath = modelFile.path, backend = backend,
                    maxNumTokens = if (cap) MAX_CONTEXT else null,
                    cacheDir = engineCacheDir.path)).also { it.initialize(); backendName = name }
            onStatus("모델 불러오는 중 (GPU)…")
            val e = runCatching { load(Backend.GPU(), "GPU", true) }
                .recoverCatching { load(Backend.GPU(), "GPU", false) }
                .recoverCatching {
                    onStatus("GPU 실패, CPU로 불러오는 중…")
                    load(Backend.CPU(PhoneLoad.cpuThreads()), "CPU", true)
                }
                .getOrElse { load(Backend.CPU(PhoneLoad.cpuThreads()), "CPU", false) }
            stats.loadMs += System.currentTimeMillis() - t0
            engine = e
            e
        }
      }
    }

    /** 지금 올라 있는 모델이 GPU 인지 CPU 인지 (CPU 면 몇 배 느림) */
    @Volatile private var backendName = ""
    @Volatile private var capOk = true

    /** maxTokens: 답 길이 상한 — 모델은 한 글자씩 만들어 내서 길게 쓸수록 느림(요약 세 줄·답장 초안은 짧게 막음) */
    private suspend fun ask(prompt: String, onStatus: (String) -> Unit, maxTokens: Int? = null): String {
        val e = engine(onStatus)
        stats.backend = backendName
        val t0 = System.currentTimeMillis()
        return try {
            withContext(Dispatchers.IO) {
                // 답 길이 상한을 못 받는 폰·판이면(처음 쓰는 설정) 예전처럼 상한 없이 다시 — 요약이 통째로 실패하지 않게
                val capped = if (maxTokens != null && capOk) runCatching {
                    e.createConversation(ConversationConfig(maxOutputToken = maxTokens)).use { conv -> conv.sendMessage(prompt).toString().trim() }
                }.onFailure { capOk = false }.getOrNull() else null
                capped ?: e.createConversation().use { conv -> conv.sendMessage(prompt).toString().trim() }
            }
        } finally {
            stats.calls++; stats.callMs += System.currentTimeMillis() - t0
            stats.memMb = maxOf(stats.memMb, PhoneLoad.appMemoryMb())
        }
    }

    /** 짧은 질문 한 번 (알림 앱 정리 추천 등). 요약과 겹치지 않게 같은 순서로 실행. */
    suspend fun complete(prompt: String, onStatus: (String) -> Unit = {}, maxTokens: Int? = null): String =
        runLock.withLock { ask(prompt, onStatus, maxTokens) }

    /** 화면·알림의 "중지" 버튼. 지금 하던 부분(최대 1분 정도)이 끝나면 멈추고 그때까지의 결과를 돌려줌. */
    /** 마지막 요약이 시간 제한으로 잘렸나(중지 단추·미리 요약 멈춤과 구분 — 시간 때문일 때만 이어서 마무리) */
    @Volatile var cutByTime = false
        private set
    @Volatile var stopRequested = false
        private set
    fun requestStop() { stopRequested = true }

    /** 중지했거나 시간 제한에 걸렸는데 정리된 부분이 하나도 없을 때. */
    class StoppedException(msg: String) : Exception(msg)

    /** cache·since·onCached: 미리 요약(PreSummary) — 덮은 방은 다시 묻지 않고, 정리할 때마다 저장. extraStop: 미리 요약은 화면이 켜지면 멈춤 */
    suspend fun summarize(items: List<NotiItem>, onStatus: (String) -> Unit,
                          replyAllowed: (BriefingComposer.Conversation) -> Boolean = { true },
                          cache: Map<String, BriefingComposer.Cached> = emptyMap(), since: Long = 0L,
                          onCached: (BriefingComposer.Cached) -> Unit = {}, extraStop: () -> String? = { null },
                          groups: List<BriefingComposer.Group> = emptyList(), maxMillis: Long = MAX_MILLIS, focus: Boolean = false,
                          before: (BriefingComposer.Conversation) -> List<NotiItem> = { emptyList() },
                          levels: ((BriefingComposer.Conversation) -> Int)? = null,
                          mentions: BriefingComposer.Mentions? = null, compact: Boolean = false): BriefingComposer.Result =
        runLock.withLock {
            stopRequested = false; cutByTime = false
            try { summarizeLocked(items, onStatus, replyAllowed, cache, since, onCached, extraStop, groups, maxMillis, focus, before, levels, mentions, compact) } finally { stopRequested = false }
        }

    /**
     * v1.4.0: 사람 메시지는 대화방마다 따로 세 줄(요점·요청·답장)로, 앱 알림은 AI 없이 건수로 — BriefingComposer.
     * 중지·시간 제한은 대화방 하나가 끝날 때마다 본다.
     */
    private suspend fun summarizeLocked(items: List<NotiItem>, onStatus: (String) -> Unit,
                                        replyAllowed: (BriefingComposer.Conversation) -> Boolean,
                                        cache: Map<String, BriefingComposer.Cached>, since: Long,
                                        onCached: (BriefingComposer.Cached) -> Unit, extraStop: () -> String?,
                                        groups: List<BriefingComposer.Group>, maxMillis: Long, focus: Boolean,
                                        before: (BriefingComposer.Conversation) -> List<NotiItem>,
                                        levels: ((BriefingComposer.Conversation) -> Int)?,
                                        mentions: BriefingComposer.Mentions?, compact: Boolean): BriefingComposer.Result {
        val deadline = System.currentTimeMillis() + maxMillis
        var called = 0
        val result = BriefingComposer.compose(
            items,
            ask = { prompt -> called++; ask(prompt, onStatus, BriefingComposer.MAX_ANSWER_TOKENS) },
            onStatus = onStatus,
            stopReason = {
                when {
                    stopRequested -> "중지해서 여기까지만 정리했어요."
                    System.currentTimeMillis() > deadline -> { cutByTime = true; "시간이 오래 걸려 여기까지만 정리했어요." }
                    else -> extraStop()
                }
            },
            replyAllowed = replyAllowed,
            cache = cache, since = since, onCached = onCached, groups = groups, focus = focus, before = before, levels = levels, mentions = mentions, compact = compact,
        )
        if (stopRequested && called == 0) throw StoppedException("중지했어요.")
        return result
    }

    /** 모델 파일 교체 직전처럼 엔진을 바로 내려야 할 때. */
    fun close() { engine?.close(); engine = null }

    /** 진행 중인 요약이 끝난 뒤 엔진을 내려 메모리(수 GB)를 돌려준다. 백그라운드 브리핑 후 호출. */
    suspend fun release() = runLock.withLock { lock.withLock { close() } }

    companion object {
        @Volatile private var inst: Summarizer? = null
        /** 화면과 백그라운드 브리핑이 같은 엔진을 공유 (모델을 두 번 올리면 메모리 부족). */
        fun get(ctx: Context): Summarizer =
            inst ?: synchronized(this) {
                inst ?: Summarizer(ctx.applicationContext).also { inst = it }
            }

        const val MODEL_NAME = "model.litertlm"
        /** AI 작업 공간(토큰). 요약 한 번 = 넣는 글 1600자 + 질문 + 답 200, 답장 초안 = 문자 8개(2400자) + 답 220 → 넉넉히 4096 */
        const val MAX_CONTEXT = 4096
        // 요약 한 번에 쓰는 최대 시간. 넘으면 그때까지 정리한 부분만 보여 줌 (멈추지 않는 문제 방지)
        const val MAX_MILLIS = 10 * 60_000L   // 30개씩 나눠 모든 대화방을 요약하므로 넉넉히(v1.8.0)
    }
}
