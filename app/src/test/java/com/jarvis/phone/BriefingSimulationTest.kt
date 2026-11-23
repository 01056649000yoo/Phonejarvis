package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 시뮬레이션: 가짜 교사 하루 알림(실제 사람·학생 자료 아님)을 예전 방식과 새 방식으로 요약해 나란히 본다.
 * 폰과 같은 Gemma 계열 모델을 맥미니 Ollama 로 부른다.
 *   OLLAMA_MODEL=gemma4:e2b ./gradlew testDebugUnitTest --tests '*BriefingSimulationTest*'
 * 결과: app/build/briefing-simulation.md. OLLAMA_MODEL 이 없으면 건너뛴다(평소 시험에서는 안 돈다).
 */
class BriefingSimulationTest {
    private val model = System.getenv("OLLAMA_MODEL").orEmpty()

    private fun ollama(prompt: String): String {
        val body = """{"model":${q(model)},"prompt":${q(prompt)},"stream":false,"keep_alive":"1m","options":{"temperature":0.2,"num_ctx":4096}}"""
        val c = URI("http://127.0.0.1:11434/api/generate").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("content-type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 300_000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().readText()
        return Regex("\"response\":\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\u003c", "<")?.replace("\\u003e", ">")?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }.orEmpty()
    }
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ── 가짜 하루(선생님 폰에 흔히 오는 모양) ──
    private val day: List<NotiItem> by lazy {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        var id = 0L
        fun at(hm: String) = fmt.parse("2026-11-20 $hm")!!.time
        fun k(hm: String, room: String, who: String, text: String) = NotiItem(id++, "com.kakao.talk", "카카오톡", who, text, at(hm), room)
        fun sms(hm: String, who: String, text: String) = NotiItem(id++, "com.samsung.android.messaging", "메시지", who, text, at(hm))
        fun a(hm: String, app: String, pkg: String, title: String, text: String) = NotiItem(id++, pkg, app, title, text, at(hm))
        listOf(
            k("08:05", "6학년 동학년방", "김부장", "선생님들 오늘 3시 학년 협의회 2층 회의실입니다. 학예회 순서 정해야 해요."),
            k("08:07", "6학년 동학년방", "이선생", "네 알겠습니다"),
            k("08:09", "6학년 동학년방", "박선생", "저는 수업 때문에 10분 늦어요"),
            k("10:40", "6학년 동학년방", "김부장", "각 반 학예회 참가 명단 금요일까지 단톡에 올려 주세요. 반별 공연 시간은 7분 이내입니다."),
            k("10:41", "6학년 동학년방", "최선생", "명단 양식 있을까요?"),
            k("10:45", "6학년 동학년방", "김부장", "공유 폴더에 올려 뒀어요"),
            k("15:55", "6학년 동학년방", "김부장", "협의 결과: 순서는 3반-1반-2반, 리허설은 다음 주 목요일 5교시 강당입니다."),
            k("07:42", "", "서윤 어머니", "선생님 안녕하세요. 서윤이가 열이 나서 오늘 병원 들렀다가 2교시 후에 등교해도 될까요?"),
            k("12:30", "", "서윤 어머니", "병원 다녀왔는데 감기라고 해요. 체육 활동은 쉬게 해 주시면 감사하겠습니다."),
            sms("09:15", "도윤 아버님", "내일 체험학습 도시락 따로 싸야 하나요? 알레르기 있어서 확인 부탁드립니다."),
            k("19:20", "우리 가족", "엄마", "일요일 할머니 생신 점심 12시 한정식집이야. 늦지 말고 와"),
            k("19:22", "우리 가족", "동생", "나 케이크 사 갈게"),
            k("19:25", "우리 가족", "엄마", "너는 꽃 좀 사 와 줄래?"),
            k("21:10", "대학 동기", "지훈", "다음 달 모임 날짜 투표 올렸어 12/13 or 12/20"),
            k("13:02", "", "정은", "ㅋㅋ"),
            a("20:30", "Telegram", "org.telegram.messenger", "자비스", "배포 완료: 독서록 글자 수 수정 (빌드 31)"),
            a("21:00", "Telegram", "org.telegram.messenger", "자비스", "서버 점검: 모두 정상"),
            k("10:12", "", "쿠팡", "고객님이 주문하신 상품이 배송 출발했습니다. 운송장 1234"),
            k("06:30", "", "카카오톡", "새로운 기기에서 카카오톡에 로그인했습니다."),
                        a("11:02", "쿠팡", "com.coupang.mobile", "배송 출발", "주문하신 A4 용지가 오늘 도착 예정입니다."),
            a("14:30", "쿠팡", "com.coupang.mobile", "배송 완료", "문 앞에 놓았습니다."),
            a("16:10", "토스", "viva.republica.toss", "결제 23,400원", "교보문고"),
            a("09:00", "유튜브", "com.google.android.youtube", "추천 동영상", "겨울 교실 놀이 10가지"),
            a("18:00", "11번가", "com.elevenst", "(광고) 블랙프라이데이 최대 70%", ""),
            a("20:05", "삼성 헬스", "com.sec.android.app.shealth", "오늘 걸음 수", "8,342걸음"),
        )
    }

    /** 예전 방식(v1.3.0 까지): 1,500자 덩어리 요약 → 다시 15줄로 — git 의 옛 Summarizer 프롬프트 그대로 */
    private fun oldWay(items: List<NotiItem>): String {
        val partial = """
            너는 교사인 사용자의 비서 '쌤톡스'야. 아래는 오늘 휴대폰에 온 알림 목록이야.
            한국어로 핵심만 요약해. 규칙:
            - 사용자가 답장하거나 처리해야 할 일(약속, 요청, 결제, 마감)을 맨 위에 "할 일"로
            - 그다음 사람/대화방별로 중요한 내용 한 줄씩
            - 배송, 금융 거래는 짧게 한 줄
            - 목록에 없는 내용은 절대 지어내지 마

            알림 목록:

        """.trimIndent()
        val final = """
            너는 교사인 사용자의 비서 '쌤톡스'야. 아래는 오늘 알림을 부분별로 요약한 것들이야.
            하나의 브리핑으로 합쳐줘. 형식:
            📌 할 일
            💬 대화·연락
            📦 배송·금융·기타
            중복은 합치고, 없는 내용은 지어내지 마. 전체 15줄 이내.

            부분 요약들:

        """.trimIndent()
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val groups = items.filterNot { it.title.startsWith("(광고)") }.groupBy { it.app to it.roomOrTitle }
            .map { (key, list) -> list.maxOf { it.time } to buildString {
                append("■ [${key.first}] ${key.second} (${list.size}건)\n")
                list.takeLast(15).forEach { val who = if (it.room.isNotEmpty() && it.title != it.room) "${it.title}: " else ""; append("  ${fmt.format(it.time)} $who${it.text.take(200)}\n") }
            } }.sortedByDescending { it.first }.map { it.second }
        val chunks = mutableListOf<String>(); val sb = StringBuilder()
        for (g in groups) { if (sb.length + g.length > 1500 && sb.isNotEmpty()) { chunks += sb.toString(); sb.clear() }; sb.append(g.take(1500)).append("\n") }
        if (sb.isNotEmpty()) chunks += sb.toString()
        val partials = chunks.take(8).map { ollama(partial + it) }
        return if (partials.size == 1) partials[0] else ollama(final + partials.joinToString("\n\n---\n\n"))
    }

    @Test fun 예전과_새_방식_나란히() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 시뮬레이션을 건너뜀", model.isNotEmpty())
        val t0 = System.currentTimeMillis()
        val old = oldWay(day)
        val t1 = System.currentTimeMillis()
        var calls = 0
        val new = BriefingComposer.compose(day, ask = { calls++; ollama(it) })
        val t2 = System.currentTimeMillis()
        val report = """
            |# 쌤톡스 요약 시뮬레이션 ($model, 가짜 알림 ${day.size}건)
            |
            |## 예전 방식 (${(t1 - t0) / 1000}초)
            |```
            |$old
            |```
            |
            |## 새 방식 (${(t2 - t1) / 1000}초, 모델 $calls 번)
            |```
            |${new.text}
            |```
        """.trimMargin()
        File("build/briefing-simulation.md").writeText(report)
        println(report)
    }
}
