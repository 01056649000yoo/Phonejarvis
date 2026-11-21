package com.jarvis.phone

import android.content.Context
import com.google.ai.edge.litertlm.Backend
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
    fun hasModel() = modelFile.exists() && modelFile.length() > 100_000_000

    private var engine: Engine? = null
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
            val e = try {
                onStatus("모델 불러오는 중 (GPU)…")
                Engine(EngineConfig(modelPath = modelFile.path, backend = Backend.GPU(),
                    cacheDir = ctx.cacheDir.path)).also { it.initialize() }
            } catch (t: Throwable) {
                onStatus("GPU 실패, CPU로 불러오는 중…")
                Engine(EngineConfig(modelPath = modelFile.path, backend = Backend.CPU(),
                    cacheDir = ctx.cacheDir.path)).also { it.initialize() }
            }
            engine = e
            e
        }
      }
    }

    private suspend fun ask(prompt: String, onStatus: (String) -> Unit): String {
        val e = engine(onStatus)
        return withContext(Dispatchers.IO) {
            e.createConversation().use { conv -> conv.sendMessage(prompt).toString().trim() }
        }
    }

    /** 짧은 질문 한 번 (알림 앱 정리 추천 등). 요약과 겹치지 않게 같은 순서로 실행. */
    suspend fun complete(prompt: String, onStatus: (String) -> Unit = {}): String =
        runLock.withLock { ask(prompt, onStatus) }

    /** 화면·알림의 "중지" 버튼. 지금 하던 부분(최대 1분 정도)이 끝나면 멈추고 그때까지의 결과를 돌려줌. */
    @Volatile var stopRequested = false
        private set
    fun requestStop() { stopRequested = true }

    /** 중지했거나 시간 제한에 걸렸는데 정리된 부분이 하나도 없을 때. */
    class StoppedException(msg: String) : Exception(msg)

    suspend fun summarize(items: List<NotiItem>, onStatus: (String) -> Unit): BriefingComposer.Result =
        runLock.withLock {
            stopRequested = false
            try { summarizeLocked(items, onStatus) } finally { stopRequested = false }
        }

    /**
     * v1.4.0: 사람 메시지는 대화방마다 따로 세 줄(요점·요청·답장)로, 앱 알림은 AI 없이 건수로 — BriefingComposer.
     * 중지·시간 제한은 대화방 하나가 끝날 때마다 본다.
     */
    private suspend fun summarizeLocked(items: List<NotiItem>, onStatus: (String) -> Unit): BriefingComposer.Result {
        val deadline = System.currentTimeMillis() + MAX_MILLIS
        var called = 0
        val result = BriefingComposer.compose(
            items,
            ask = { prompt -> called++; ask(prompt, onStatus) },
            onStatus = onStatus,
            stopReason = {
                when {
                    stopRequested -> "중지해서 여기까지만 정리했어요."
                    System.currentTimeMillis() > deadline -> "시간이 오래 걸려 여기까지만 정리했어요."
                    else -> null
                }
            },
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
        // 요약 한 번에 쓰는 최대 시간. 넘으면 그때까지 정리한 부분만 보여 줌 (멈추지 않는 문제 방지)
        private const val MAX_MILLIS = 10 * 60_000L   // 30개씩 나눠 모든 대화방을 요약하므로 넉넉히(v1.8.0)
    }
}
