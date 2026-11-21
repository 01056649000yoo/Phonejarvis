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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 1차: 규칙으로 알림을 거르고 묶는다 (광고 제거, 앱·대화방별 그룹화)
 * 2차: Gemma가 묶음별 요약 → 최종 브리핑 (map-reduce)
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

    suspend fun summarize(items: List<NotiItem>, onStatus: (String) -> Unit): String =
        runLock.withLock { summarizeLocked(items, onStatus) }

    private suspend fun summarizeLocked(items: List<NotiItem>, onStatus: (String) -> Unit): String {
        val groups = preprocess(items)
        if (groups.isEmpty()) return "요약할 알림이 없어요."

        // 묶음들을 모델 입력 한도에 맞춰 덩어리로 나눔
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (g in groups) {
            if (sb.length + g.length > CHUNK_CHARS && sb.isNotEmpty()) {
                chunks += sb.toString(); sb.clear()
            }
            sb.append(g.take(CHUNK_CHARS)).append("\n")
        }
        if (sb.isNotEmpty()) chunks += sb.toString()

        val partials = chunks.mapIndexed { i, chunk ->
            onStatus("요약 중… (${i + 1}/${chunks.size})")
            ask(PARTIAL_PROMPT + chunk, onStatus)
        }
        if (partials.size == 1) return partials[0]

        onStatus("최종 브리핑 정리 중…")
        return ask(FINAL_PROMPT + partials.joinToString("\n\n---\n\n"), onStatus)
    }

    /** 규칙 기반 전처리: 광고 제거, 같은 앱·같은 제목(대화방) 묶기. */
    private fun preprocess(items: List<NotiItem>): List<String> {
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        return items
            .filterNot { it.title.startsWith("(광고)") || it.text.startsWith("(광고)") }
            .groupBy { it.app to it.title }
            .map { (key, list) ->
                val (app, title) = key
                buildString {
                    append("■ [$app] $title (${list.size}건)\n")
                    // 대화방이 길면 최근 30개만
                    list.takeLast(30).forEach { append("  ${fmt.format(it.time)} ${it.text}\n") }
                }
            }
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
        // 기본 컨텍스트가 2048토큰일 수 있어 보수적으로 설정. "token" 관련 오류가 나면 줄이세요.
        private const val CHUNK_CHARS = 1500

        private val PARTIAL_PROMPT = """
            너는 교사인 사용자의 비서 '쌤톡스'야. 아래는 오늘 휴대폰에 온 알림 목록이야.
            한국어로 핵심만 요약해. 규칙:
            - 사용자가 답장하거나 처리해야 할 일(약속, 요청, 결제, 마감)을 맨 위에 "할 일"로
            - 그다음 사람/대화방별로 중요한 내용 한 줄씩
            - 배송, 금융 거래는 짧게 한 줄
            - 목록에 없는 내용은 절대 지어내지 마

            알림 목록:

        """.trimIndent()

        private val FINAL_PROMPT = """
            너는 교사인 사용자의 비서 '쌤톡스'야. 아래는 오늘 알림을 부분별로 요약한 것들이야.
            하나의 브리핑으로 합쳐줘. 형식:
            📌 할 일
            💬 대화·연락
            📦 배송·금융·기타
            중복은 합치고, 없는 내용은 지어내지 마. 전체 15줄 이내.

            부분 요약들:

        """.trimIndent()
    }
}
